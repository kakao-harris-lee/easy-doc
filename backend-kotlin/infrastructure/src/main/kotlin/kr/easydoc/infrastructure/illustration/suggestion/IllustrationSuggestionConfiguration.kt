package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionCreditPort
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRepository
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRunner
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobService
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobWorkerPolicy
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionLlmCallLedger
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultRepository
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultService
import kr.easydoc.application.illustration.suggestion.ProcessIllustrationSuggestionJob
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.infrastructure.credit.CreditsProperties
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.llm.LlmProviderConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.net.InetAddress
import java.time.Duration

/**
 * R7 ER-17 그림 제안의 공개 기능과 worker 는 각각 명시적으로 켜며 기본값은 모두 꺼져 있다.
 * 기존 `easydoc.illustrations.enabled`(ER-15 카탈로그)와 **독립**이다(명세 §6).
 *
 * [creditsPer100Chars] 는 **미설정과 0이 다르다**(명세 §3).
 * - 미설정(`null`): 기능이 켜져 있어도 접수를 503 으로 거부하고 capability 를 false 로 낸다.
 * - `0`: 설정된 값이며 크레딧 거래 행을 만들지 않는다. fake 모드 전용이고, 실제 provider
 *   worker 는 기동에서 이 값을 거부한다([ProviderIllustrationSuggestionWorkerConfiguration]).
 */
@ConfigurationProperties(prefix = "easydoc.illustration-suggestions")
data class IllustrationSuggestionProperties(
    val enabled: Boolean = false,
    val workerEnabled: Boolean = false,
    val owner: String = "",
    val leaseDurationSeconds: Long = DEFAULT_LEASE_SECONDS,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    val maxLeaseAttempts: Int = DEFAULT_MAX_LEASE_ATTEMPTS,
    val maxProviderAttemptsPerConversion: Int = DEFAULT_MAX_PROVIDER_ATTEMPTS,
    val creditsPer100Chars: BigDecimal? = null,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) {
    init {
        require(leaseDurationSeconds > 0) { "리스 수명은 양수여야 합니다: $leaseDurationSeconds" }
        require(pollIntervalMs > 0) { "폴링 간격은 양수여야 합니다: $pollIntervalMs" }
        require(maxLeaseAttempts in 1..MAX_ALLOWED_LEASE_ATTEMPTS) {
            "리스 재획득 상한은 1 이상 $MAX_ALLOWED_LEASE_ATTEMPTS 이하여야 합니다: $maxLeaseAttempts"
        }
        require(maxProviderAttemptsPerConversion >= 1) {
            "문서당 provider 시도 상한은 1 이상이어야 합니다: $maxProviderAttemptsPerConversion"
        }
        require(maxOutputTokens > 0) { "출력 상한은 양수여야 합니다: $maxOutputTokens" }
        creditsPer100Chars?.let { rate ->
            require(rate.signum() >= 0) { "이용량 단가는 음수일 수 없습니다: $rate" }
            // 0.1 단위가 아니면 예약·정산이 `Credits` 로 표현되지 않는다 — 기동에서 끊는다.
            require(rate.stripTrailingZeros().scale() <= CREDIT_SCALE) {
                "이용량 단가는 0.1 단위여야 합니다: $rate"
            }
        }
    }

    companion object {
        const val DEFAULT_LEASE_SECONDS: Long = 120
        const val DEFAULT_POLL_INTERVAL_MS: Long = 500
        const val DEFAULT_MAX_LEASE_ATTEMPTS: Int = 5
        const val DEFAULT_MAX_PROVIDER_ATTEMPTS: Int = 3
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 8_192

        /** 리스 수명이 120초면 100회는 이미 3시간이 넘는다. 그보다 큰 값은 상한이 아니다. */
        const val MAX_ALLOWED_LEASE_ATTEMPTS: Int = 100

        private const val CREDIT_SCALE = 1
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IllustrationSuggestionProperties::class)
@Profile("!$MIGRATE_PROFILE")
class IllustrationSuggestionConfiguration {
    @Bean
    fun illustrationSuggestionJobRepository(
        jdbcClient: JdbcClient,
        properties: IllustrationSuggestionProperties,
    ): IllustrationSuggestionJobRepository =
        JdbcIllustrationSuggestionJobRepository(jdbcClient, properties.maxProviderAttemptsPerConversion)

    @Bean
    fun illustrationSuggestionResultRepository(jdbcClient: JdbcClient): IllustrationSuggestionResultRepository =
        JdbcIllustrationSuggestionResultRepository(jdbcClient)

    @Bean
    fun illustrationSuggestionCreditPort(
        jdbcClient: JdbcClient,
        creditsProperties: CreditsProperties,
    ): IllustrationSuggestionCreditPort = JdbcIllustrationSuggestionCreditPort(jdbcClient, creditsProperties.enforced)

    @Bean
    fun illustrationSuggestionLlmCallLedger(jdbcClient: JdbcClient): IllustrationSuggestionLlmCallLedger =
        JdbcIllustrationSuggestionLlmCallLedger(jdbcClient)

    @Bean
    fun illustrationSuggestionJobService(
        properties: IllustrationSuggestionProperties,
        jobs: IllustrationSuggestionJobRepository,
        credits: IllustrationSuggestionCreditPort,
        transactionRunner: TransactionRunner,
    ): IllustrationSuggestionJobService =
        IllustrationSuggestionJobService(
            properties.enabled,
            properties.creditsPer100Chars,
            jobs,
            credits,
            transactionRunner,
        )

    @Bean
    fun illustrationSuggestionResultService(
        properties: IllustrationSuggestionProperties,
        jobs: IllustrationSuggestionJobRepository,
        results: IllustrationSuggestionResultRepository,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
    ): IllustrationSuggestionResultService =
        IllustrationSuggestionResultService(
            properties.enabled,
            properties.creditsPer100Chars,
            jobs,
            results,
            cipher,
            transactionRunner,
        )
}

/** intake OFF + worker ON 이면 기존 queued 작업을 provider 호출 없이 비우는 운영 구성. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@ConditionalOnExpression(
    "'\${easydoc.illustration-suggestions.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.illustration-suggestions.enabled:false}' != 'true'",
)
class IllustrationSuggestionDrainWorkerConfiguration {
    @Bean
    fun illustrationSuggestionWorkerPolicy(
        properties: IllustrationSuggestionProperties,
    ): IllustrationSuggestionJobWorkerPolicy = workerPolicy(properties)

    @Bean
    fun disabledIllustrationSuggestionJobRunner(): IllustrationSuggestionJobRunner =
        IllustrationSuggestionJobRunner { error("intake OFF drain 경로에서 runner를 호출하면 안 됩니다") }

    @Suppress("LongParameterList")
    @Bean
    fun processIllustrationSuggestionJob(
        jobs: IllustrationSuggestionJobRepository,
        credits: IllustrationSuggestionCreditPort,
        ledger: IllustrationSuggestionLlmCallLedger,
        results: IllustrationSuggestionResultRepository,
        cipher: ContentCipher,
        runner: IllustrationSuggestionJobRunner,
        transactionRunner: TransactionRunner,
        policy: IllustrationSuggestionJobWorkerPolicy,
    ): ProcessIllustrationSuggestionJob =
        ProcessIllustrationSuggestionJob(
            jobs,
            credits,
            ledger,
            results,
            cipher,
            runner,
            transactionRunner,
            policy,
        )
}

/** 검증 전용. 이 프로필 없이는 fake runner 와 poller 처리 빈이 만들어지지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker & $ILLUSTRATION_SUGGESTION_FAKE_PROFILE")
@ConditionalOnExpression(
    "'\${easydoc.illustration-suggestions.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.illustration-suggestions.enabled:false}' == 'true'",
)
class FakeIllustrationSuggestionWorkerConfiguration {
    @Bean
    fun illustrationSuggestionWorkerPolicy(
        properties: IllustrationSuggestionProperties,
    ): IllustrationSuggestionJobWorkerPolicy = workerPolicy(properties)

    @Bean
    fun fakeIllustrationSuggestionJobRunner(
        jdbcClient: JdbcClient,
        cipher: ContentCipher,
    ): IllustrationSuggestionJobRunner =
        FakeIllustrationSuggestionJobRunner(JdbcIllustrationSuggestionInputSource(jdbcClient, cipher))

    @Suppress("LongParameterList")
    @Bean
    fun processIllustrationSuggestionJob(
        jobs: IllustrationSuggestionJobRepository,
        credits: IllustrationSuggestionCreditPort,
        ledger: IllustrationSuggestionLlmCallLedger,
        results: IllustrationSuggestionResultRepository,
        cipher: ContentCipher,
        runner: IllustrationSuggestionJobRunner,
        transactionRunner: TransactionRunner,
        policy: IllustrationSuggestionJobWorkerPolicy,
    ): ProcessIllustrationSuggestionJob =
        ProcessIllustrationSuggestionJob(
            jobs,
            credits,
            ledger,
            results,
            cipher,
            runner,
            transactionRunner,
            policy,
        )
}

/** 실제 호출은 intake 와 worker 플래그를 함께 켠 worker 프로필에서만 가능하다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker & !$ILLUSTRATION_SUGGESTION_FAKE_PROFILE")
@ConditionalOnExpression(
    "'\${easydoc.illustration-suggestions.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.illustration-suggestions.enabled:false}' == 'true'",
)
class ProviderIllustrationSuggestionWorkerConfiguration {
    @Bean
    fun illustrationSuggestionWorkerPolicy(
        properties: IllustrationSuggestionProperties,
    ): IllustrationSuggestionJobWorkerPolicy = workerPolicy(properties)

    @Bean
    fun providerIllustrationSuggestionJobRunner(
        jdbcClient: JdbcClient,
        cipher: ContentCipher,
        properties: IllustrationSuggestionProperties,
        llmProperties: LlmProperties,
        environment: Environment,
    ): IllustrationSuggestionJobRunner {
        // 0 크레딧은 fake 모드 전용이다(명세 §3) — 유료 호출을 무료로 돌리는 구성을 기동에서 끊는다.
        // 사용자 잘못이 아니라 운영 설정이라 `ConfigurationException` 이다(저장소 관례).
        val rate = properties.creditsPer100Chars
        if (rate == null || rate.signum() <= 0) {
            throw ConfigurationException(PROVIDER_RATE_REQUIRED_MESSAGE)
        }
        // 별도 provider 인스턴스에 응답 제한을 둔다. 변환 worker 의 긴 출력·타임아웃은 건드리지 않는다.
        val boundedProperties =
            llmProperties.copy(
                maxOutputTokens = properties.maxOutputTokens,
                readTimeout = Duration.ofSeconds(SUGGESTION_PROVIDER_TIMEOUT_SECONDS),
            )
        val provider = LlmProviderConfiguration().llmProvider(boundedProperties, environment)
        return ProviderIllustrationSuggestionJobRunner(
            JdbcIllustrationSuggestionInputSource(jdbcClient, cipher),
            provider,
            properties.maxOutputTokens,
        )
    }

    @Suppress("LongParameterList")
    @Bean
    fun processIllustrationSuggestionJob(
        jobs: IllustrationSuggestionJobRepository,
        credits: IllustrationSuggestionCreditPort,
        ledger: IllustrationSuggestionLlmCallLedger,
        results: IllustrationSuggestionResultRepository,
        cipher: ContentCipher,
        runner: IllustrationSuggestionJobRunner,
        transactionRunner: TransactionRunner,
        policy: IllustrationSuggestionJobWorkerPolicy,
    ): ProcessIllustrationSuggestionJob =
        ProcessIllustrationSuggestionJob(
            jobs,
            credits,
            ledger,
            results,
            cipher,
            runner,
            transactionRunner,
            policy,
        )
}

/** e2e·검증에서만 켜는 fake provider 프로필 이름. */
const val ILLUSTRATION_SUGGESTION_FAKE_PROFILE: String = "illustration-suggestion-fake"

/** 실제 provider worker 가 기동에서 거부하는 구성. 값 자체는 비밀이 아니라 메시지에 넣지 않는다. */
const val PROVIDER_RATE_REQUIRED_MESSAGE: String =
    "실제 provider 모드에서는 easydoc.illustration-suggestions.credits-per-100-chars 가 0보다 커야 합니다"

private fun workerPolicy(properties: IllustrationSuggestionProperties): IllustrationSuggestionJobWorkerPolicy =
    IllustrationSuggestionJobWorkerPolicy(
        owner = properties.owner.ifBlank(::hostOwner).take(OWNER_MAX_LENGTH),
        leaseDuration = Duration.ofSeconds(properties.leaseDurationSeconds),
        maxLeaseAttempts = properties.maxLeaseAttempts,
    )

private fun hostOwner(): String =
    runCatching { InetAddress.getLocalHost().hostName }
        .getOrElse { DEFAULT_OWNER }
        .ifBlank { DEFAULT_OWNER }

private const val DEFAULT_OWNER: String = "illustration-suggestion-worker"
private const val OWNER_MAX_LENGTH: Int = 64
private const val SUGGESTION_PROVIDER_TIMEOUT_SECONDS: Long = 90
