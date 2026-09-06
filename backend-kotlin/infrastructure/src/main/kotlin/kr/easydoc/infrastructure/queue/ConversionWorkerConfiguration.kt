package kr.easydoc.infrastructure.queue

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.conversion.ConversionCompletedNotifier
import kr.easydoc.application.conversion.ConversionJobHeartbeat
import kr.easydoc.application.conversion.ConversionJobLease
import kr.easydoc.application.conversion.ConversionJobLeasePort
import kr.easydoc.application.conversion.ConversionNotificationStore
import kr.easydoc.application.conversion.ConversionWorkStore
import kr.easydoc.application.conversion.ConversionWorkerPolicy
import kr.easydoc.application.conversion.ConversionWorkerRuntime
import kr.easydoc.application.conversion.ConversionWorkerStores
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.application.conversion.DictionaryContextSource
import kr.easydoc.application.conversion.NoDictionaryContext
import kr.easydoc.application.conversion.ProcessConversionJob
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.mail.MailSender
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.infrastructure.app.AppProperties
import kr.easydoc.infrastructure.dictionary.DictionaryIndexHolder
import kr.easydoc.infrastructure.dictionary.DictionaryProperties
import kr.easydoc.infrastructure.dictionary.IndexedDictionaryContextSource
import kr.easydoc.infrastructure.document.JdbcConversionNotificationStore
import kr.easydoc.infrastructure.document.JdbcConversionWorkStore
import kr.easydoc.infrastructure.llm.LlmProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** worker 실행 설정. 바인딩 접두사는 `easydoc.worker`. */
@ConfigurationProperties(prefix = "easydoc.worker")
data class ConversionWorkerProperties(
    val owner: String = "",
    val leaseDurationSeconds: Long = DEFAULT_LEASE_SECONDS,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val retryBackoffSeconds: Long = DEFAULT_RETRY_BACKOFF_SECONDS,
    val heartbeatIntervalSeconds: Long = DEFAULT_HEARTBEAT_SECONDS,
) {
    companion object {
        const val DEFAULT_LEASE_SECONDS: Long = 120
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_RETRY_BACKOFF_SECONDS: Long = 5
        const val DEFAULT_HEARTBEAT_SECONDS: Long = 40
    }
}

/** worker 프로필에서만 변환 처리 유스케이스를 조립한다. API 프로세스는 큐를 소비하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class ConversionWorkerConfiguration {
    /**
     * `LlmOptions` 를 여기서 조립한다 — 출력 토큰 상한을 구성값(`easydoc.llm.max-output-tokens`)
     * 에서 받아 실제 워커 호출까지 흘려보내는 유일한 지점이다. `properties.validatedMaxOutputTokens()`
     * 가 운영자 오설정(0 이하)을 `ConfigurationException` 으로 여기서 먼저 거절한다 —
     * [LlmOptions] 의 `init` `require` 는 그 계약이 지켜지지 않았을 때의 마지막 방어선이다.
     */
    @Bean
    fun convertDocumentUseCase(
        provider: LlmProvider,
        dictionary: DictionaryContextSource,
        properties: LlmProperties,
    ): ConvertDocumentUseCase =
        ConvertDocumentUseCase(
            provider,
            dictionary = dictionary,
            defaultOptions = LlmOptions(maxTokens = properties.validatedMaxOutputTokens()),
        )

    /**
     * 사전 컨텍스트 공급원. **색인 적재는 `DictionaryConfiguration.dictionaryIndexHolder` 하나로
     * 모여 있다(2026-09-06, `docs/kotlin-redevelopment-backlog.md` §1.1 「사전 색인이
     * API·worker 프로필 동시 기동 시 두 번 적재된다」 해결, 2차 정정으로 즉시 로드를 lazy 로
     * 바꿨다 — [DictionaryIndexHolder] KDoc 참고)** — 이 빈은 그 공유 홀더를 소비하기만
     * 하고 자기 색인을 다시 읽지 않는다. API·worker 프로필을 한 프로세스에 함께 켜도 색인
     * 파일은 한 번만 읽힌다.
     *
     * 주입 스위치([DictionaryProperties.enabled])가 꺼져 있으면 [DictionaryIndexHolder
     * .indexOrNull] 을 **부르지도 않는다** — 이 클래스는 `@Profile("worker")` 라 API 전용
     * 프로세스에서는 이 `@Bean` 자체가 존재하지 않지만, worker 프로세스 안에서도 이 스위치가
     * 꺼져 있으면(조회만 켜진 조합) 홀더가 읽을 수 있는 상태여도 이 소비자는 읽지 않는다.
     * 켜져 있는데 `indexOrNull()` 이 `null` 이면 구성 조립이 깨진 것이다(이 스위치가 켜져
     * 있으면 홀더의 `enabled` 도 합집합으로 반드시 참이므로, 이 분기가 켜져 있는 이상 구성상
     * 발생할 수 없다) — 조용히 사전 없이 흘려보내는 대신 fail-fast 한다.
     */
    @Bean
    fun dictionaryContextSource(
        properties: DictionaryProperties,
        dictionaryIndexHolder: DictionaryIndexHolder,
    ): DictionaryContextSource =
        if (properties.enabled) {
            val index =
                dictionaryIndexHolder.indexOrNull()
                    ?: throw ConfigurationException(
                        "easydoc.dictionary.enabled=true 인데 사전 색인이 적재되지 않았다 " +
                            "— DictionaryConfiguration.dictionaryIndexHolder 조립을 확인한다 (구성상 발생할 수 없다)",
                    )
            IndexedDictionaryContextSource(index = index, policy = properties.policy())
        } else {
            NoDictionaryContext
        }

    @Bean
    fun conversionWorkerPolicy(properties: ConversionWorkerProperties): ConversionWorkerPolicy =
        ConversionWorkerPolicy(
            owner = properties.owner.ifBlank { hostOwner() }.take(ConversionWorkerPolicy.OWNER_MAX_LENGTH),
            leaseDuration = Duration.ofSeconds(properties.leaseDurationSeconds),
            maxAttempts = properties.maxAttempts,
            retryBackoff = Duration.ofSeconds(properties.retryBackoffSeconds),
        )

    @Bean
    fun conversionWorkerRuntime(
        leases: ConversionJobLeasePort,
        policy: ConversionWorkerPolicy,
        properties: ConversionWorkerProperties,
    ): ConversionWorkerRuntime =
        ConversionWorkerRuntime(
            heartbeat =
                ScheduledConversionJobHeartbeat(
                    leases = leases,
                    policy = policy,
                    interval = Duration.ofSeconds(properties.heartbeatIntervalSeconds),
                ),
            policy = policy,
        )

    @Bean
    fun conversionWorkStore(jdbcClient: JdbcClient): ConversionWorkStore = JdbcConversionWorkStore(jdbcClient)

    @Bean
    fun conversionWorkerStores(
        leases: ConversionJobLeasePort,
        work: ConversionWorkStore,
        cipher: ContentCipher,
    ): ConversionWorkerStores = ConversionWorkerStores(leases, work, cipher)

    @Bean
    fun conversionNotificationStore(jdbcClient: JdbcClient): ConversionNotificationStore =
        JdbcConversionNotificationStore(jdbcClient)

    /**
     * 완료 알림 유스케이스. 메일 발송기(`easydoc.mail.provider`)와 공개 기준 URL
     * (`easydoc.app.public-base-url`)을 여기서 묶는다 — [ProcessConversionJob] 은
     * 완료 커밋 뒤 이 빈을 부르기만 한다.
     */
    @Bean
    fun conversionCompletedNotifier(
        store: ConversionNotificationStore,
        mailSender: MailSender,
        appProperties: AppProperties,
    ): ConversionCompletedNotifier = ConversionCompletedNotifier(store, mailSender, appProperties.publicBaseUrl)

    @Bean
    fun processConversionJob(
        stores: ConversionWorkerStores,
        convert: ConvertDocumentUseCase,
        transactionRunner: TransactionRunner,
        runtime: ConversionWorkerRuntime,
        notifier: ConversionCompletedNotifier,
    ): ProcessConversionJob =
        ProcessConversionJob(
            stores = stores,
            convert = convert,
            transaction = transactionRunner,
            runtime = runtime,
            notifier = notifier,
        )

    private fun hostOwner(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .getOrElse { "worker" }
            .ifBlank { "worker" }
}

/** LLM 구간에서 리스 만료를 밀어 준다. 실패해도 호출을 막지 않는다 — 저장 시점 fencing 이 막는다. */
class ScheduledConversionJobHeartbeat(
    private val leases: ConversionJobLeasePort,
    private val policy: ConversionWorkerPolicy,
    private val interval: Duration,
) : ConversionJobHeartbeat {
    private val log = LoggerFactory.getLogger(ScheduledConversionJobHeartbeat::class.java)

    override fun <T> whileHeld(
        lease: ConversionJobLease,
        block: () -> T,
    ): T {
        val scheduler =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, HEARTBEAT_THREAD).apply { isDaemon = true }
            }
        val periodMs = interval.toMillis().coerceAtLeast(1)
        val future =
            scheduler.scheduleAtFixedRate(
                { renewQuietly(lease) },
                periodMs,
                periodMs,
                TimeUnit.MILLISECONDS,
            )
        try {
            return block()
        } finally {
            future.cancel(true)
            scheduler.shutdownNow()
        }
    }

    /** 한 번의 연장 실패가 `scheduleAtFixedRate` 후속 실행을 죽이면 긴 LLM 호출 중 리스가 만료된다. */
    @Suppress("TooGenericExceptionCaught")
    private fun renewQuietly(lease: ConversionJobLease) {
        try {
            leases.renew(lease, policy.leaseDuration)
        } catch (exc: RuntimeException) {
            log.warn("변환 작업 리스 연장에 실패했다: conversionId={}", lease.conversionId, exc)
        }
    }

    private companion object {
        const val HEARTBEAT_THREAD: String = "conversion-lease-heartbeat"
    }
}
