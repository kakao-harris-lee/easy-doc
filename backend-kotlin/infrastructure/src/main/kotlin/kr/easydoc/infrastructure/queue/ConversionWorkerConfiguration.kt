package kr.easydoc.infrastructure.queue

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.conversion.ConversionJobHeartbeat
import kr.easydoc.application.conversion.ConversionJobLease
import kr.easydoc.application.conversion.ConversionJobLeasePort
import kr.easydoc.application.conversion.ConversionWorkStore
import kr.easydoc.application.conversion.ConversionWorkerPolicy
import kr.easydoc.application.conversion.ConversionWorkerRuntime
import kr.easydoc.application.conversion.ConversionWorkerStores
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.application.conversion.ProcessConversionJob
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.MaskedItemWriter
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.infrastructure.document.JdbcConversionWorkStore
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
    @Bean
    fun convertDocumentUseCase(provider: LlmProvider): ConvertDocumentUseCase = ConvertDocumentUseCase(provider)

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
        maskedItems: MaskedItemWriter,
    ): ConversionWorkerStores = ConversionWorkerStores(leases, work, cipher, maskedItems)

    @Bean
    fun processConversionJob(
        stores: ConversionWorkerStores,
        convert: ConvertDocumentUseCase,
        transactionRunner: TransactionRunner,
        runtime: ConversionWorkerRuntime,
    ): ProcessConversionJob =
        ProcessConversionJob(
            stores = stores,
            convert = convert,
            transaction = transactionRunner,
            runtime = runtime,
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
                { leases.renew(lease, policy.leaseDuration) },
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

    private companion object {
        const val HEARTBEAT_THREAD: String = "conversion-lease-heartbeat"
    }
}
