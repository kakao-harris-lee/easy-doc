package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.illustration.generation.GeneratedIllustrationAssetRepository
import kr.easydoc.application.illustration.generation.GeneratedIllustrationAssetService
import kr.easydoc.application.illustration.generation.IllustrationGenerationCreditPort
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobRepository
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobRunner
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobService
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobWorkerPolicy
import kr.easydoc.application.illustration.generation.IllustrationGenerationLlmCallLedger
import kr.easydoc.application.illustration.generation.ProcessIllustrationGenerationJob
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultRepository
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.infrastructure.credit.CreditsProperties
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.queue.WorkerLeaseSupport
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal

@ConfigurationProperties(prefix = "easydoc.illustration-generation")
data class IllustrationGenerationProperties(
    val enabled: Boolean = false,
    val readEnabled: Boolean = true,
    val workerEnabled: Boolean = false,
    val owner: String = "",
    val leaseDurationSeconds: Long = DEFAULT_LEASE_SECONDS,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    val maxLeaseAttempts: Int = DEFAULT_MAX_LEASE_ATTEMPTS,
    val creditsPerImage: BigDecimal? = null,
    val providerCostUsdPerImage: BigDecimal? = null,
    val model: String = DEFAULT_OPENAI_IMAGE_MODEL,
) {
    init {
        require(leaseDurationSeconds > 0) { "리스 수명은 양수여야 합니다" }
        require(pollIntervalMs > 0) { "폴 주기는 양수여야 합니다" }
        require(maxLeaseAttempts in 1..MAX_ALLOWED_LEASE_ATTEMPTS) { "리스 재획득 상한이 올바르지 않습니다" }
        require(model.isNotBlank()) { "이미지 모델은 비워 둘 수 없습니다" }
        creditsPerImage?.let {
            require(it.signum() >= 0 && it.stripTrailingZeros().scale() <= 1) {
                "고객 차감량은 0 이상의 0.1 단위여야 합니다"
            }
        }
        providerCostUsdPerImage?.let { require(it.signum() >= 0) { "provider 가격은 음수일 수 없습니다" } }
    }

    fun capabilityAvailable(zeroCreditsAllowed: Boolean): Boolean =
        enabled && creditsPerImage != null &&
            if (zeroCreditsAllowed) {
                true
            } else {
                creditsPerImage.signum() > 0 && providerCostUsdPerImage?.signum() == 1
            }

    companion object {
        const val DEFAULT_LEASE_SECONDS: Long = 120
        const val DEFAULT_POLL_INTERVAL_MS: Long = 500
        const val DEFAULT_MAX_LEASE_ATTEMPTS: Int = 5
        const val MAX_ALLOWED_LEASE_ATTEMPTS: Int = 100
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IllustrationGenerationProperties::class)
@Profile("!$MIGRATE_PROFILE")
class IllustrationGenerationConfiguration {
    @Bean
    fun illustrationGenerationJobRepository(jdbcClient: JdbcClient): IllustrationGenerationJobRepository =
        JdbcIllustrationGenerationJobRepository(jdbcClient)

    @Bean
    fun generatedIllustrationAssetRepository(jdbcClient: JdbcClient): GeneratedIllustrationAssetRepository =
        JdbcGeneratedIllustrationAssetRepository(jdbcClient)

    @Bean
    fun illustrationGenerationCreditPort(
        jdbcClient: JdbcClient,
        creditsProperties: CreditsProperties,
    ): IllustrationGenerationCreditPort = JdbcIllustrationGenerationCreditPort(jdbcClient, creditsProperties.enforced)

    @Bean
    fun illustrationGenerationLlmCallLedger(jdbcClient: JdbcClient): IllustrationGenerationLlmCallLedger =
        JdbcIllustrationGenerationLlmCallLedger(jdbcClient)

    @Suppress("LongParameterList")
    @Bean("illustrationGenerationWorkerFactory")
    internal fun illustrationGenerationWorkerFactory(
        jobs: IllustrationGenerationJobRepository,
        credits: IllustrationGenerationCreditPort,
        ledger: IllustrationGenerationLlmCallLedger,
        assets: GeneratedIllustrationAssetRepository,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
    ): IllustrationGenerationWorkerFactory =
        IllustrationGenerationWorkerFactory(jobs, credits, ledger, assets, cipher, transactionRunner)

    @Suppress("LongParameterList")
    @Bean
    fun illustrationGenerationJobService(
        properties: IllustrationGenerationProperties,
        jobs: IllustrationGenerationJobRepository,
        suggestions: IllustrationSuggestionResultRepository,
        credits: IllustrationGenerationCreditPort,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
        environment: Environment,
    ): IllustrationGenerationJobService =
        IllustrationGenerationJobService(
            properties.enabled,
            properties.creditsPerImage,
            zeroCreditsAllowed(environment),
            properties.providerCostUsdPerImage?.signum() == 1,
            jobs,
            suggestions,
            credits,
            cipher,
            transactionRunner,
            readEnabled = properties.readEnabled,
        )

    @Bean
    fun generatedIllustrationAssetService(
        properties: IllustrationGenerationProperties,
        jobs: IllustrationGenerationJobRepository,
        assets: GeneratedIllustrationAssetRepository,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
    ): GeneratedIllustrationAssetService =
        GeneratedIllustrationAssetService(properties.readEnabled, jobs, assets, cipher, transactionRunner)
}

@Configuration(proxyBeanMethods = false)
@Profile("worker")
@ConditionalOnExpression(
    "'\${easydoc.illustration-generation.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.illustration-generation.enabled:false}' != 'true'",
)
class IllustrationGenerationDrainWorkerConfiguration {
    @Bean
    fun illustrationGenerationWorkerPolicy(
        properties: IllustrationGenerationProperties,
    ): IllustrationGenerationJobWorkerPolicy = workerPolicy(properties)

    @Bean
    fun disabledIllustrationGenerationJobRunner(): IllustrationGenerationJobRunner =
        IllustrationGenerationJobRunner { error("intake OFF drain 경로에서 runner를 호출하면 안 됩니다") }

    @Bean("processIllustrationGenerationJob")
    internal fun processIllustrationGenerationJob(
        factory: IllustrationGenerationWorkerFactory,
        runner: IllustrationGenerationJobRunner,
        policy: IllustrationGenerationJobWorkerPolicy,
    ): ProcessIllustrationGenerationJob = factory.create(runner, policy)
}

@Configuration(proxyBeanMethods = false)
@Profile("worker & $ILLUSTRATION_GENERATION_FAKE_PROFILE & (local | test | e2e)")
@ConditionalOnExpression(
    "'\${easydoc.illustration-generation.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.illustration-generation.enabled:false}' == 'true'",
)
class FakeIllustrationGenerationWorkerConfiguration {
    @Bean
    fun illustrationGenerationWorkerPolicy(
        properties: IllustrationGenerationProperties,
    ): IllustrationGenerationJobWorkerPolicy = workerPolicy(properties)

    @Bean
    fun fakeIllustrationGenerationJobRunner(cipher: ContentCipher): IllustrationGenerationJobRunner =
        ProviderIllustrationGenerationJobRunner(cipher, FakeIllustrationGenerationProvider(), BigDecimal.ZERO)

    @Bean("processIllustrationGenerationJob")
    internal fun processIllustrationGenerationJob(
        factory: IllustrationGenerationWorkerFactory,
        runner: IllustrationGenerationJobRunner,
        policy: IllustrationGenerationJobWorkerPolicy,
    ): ProcessIllustrationGenerationJob = factory.create(runner, policy)
}

@Configuration(proxyBeanMethods = false)
@Profile("worker & !$ILLUSTRATION_GENERATION_FAKE_PROFILE")
@ConditionalOnExpression(
    "'\${easydoc.illustration-generation.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.illustration-generation.enabled:false}' == 'true'",
)
class ProviderIllustrationGenerationWorkerConfiguration {
    @Bean
    fun illustrationGenerationWorkerPolicy(
        properties: IllustrationGenerationProperties,
    ): IllustrationGenerationJobWorkerPolicy = workerPolicy(properties)

    @Bean
    fun providerIllustrationGenerationJobRunner(
        cipher: ContentCipher,
        properties: IllustrationGenerationProperties,
        llmProperties: LlmProperties,
    ): IllustrationGenerationJobRunner {
        val credits = properties.creditsPerImage
        val providerCost = properties.providerCostUsdPerImage
        if (credits == null || credits.signum() <= 0) {
            throw ConfigurationException(GENERATION_PROVIDER_PRICING_REQUIRED_MESSAGE)
        }
        if (providerCost == null || providerCost.signum() <= 0) {
            throw ConfigurationException(GENERATION_PROVIDER_PRICING_REQUIRED_MESSAGE)
        }
        return ProviderIllustrationGenerationJobRunner(
            cipher,
            OpenAiImageGenerationProvider(llmProperties.openAiApiKey, properties.model),
            providerCost,
        )
    }

    @Bean("processIllustrationGenerationJob")
    internal fun processIllustrationGenerationJob(
        factory: IllustrationGenerationWorkerFactory,
        runner: IllustrationGenerationJobRunner,
        policy: IllustrationGenerationJobWorkerPolicy,
    ): ProcessIllustrationGenerationJob = factory.create(runner, policy)
}

const val ILLUSTRATION_GENERATION_FAKE_PROFILE: String = "illustration-generation-fake"
const val GENERATION_PROVIDER_PRICING_REQUIRED_MESSAGE: String =
    "실제 그림 생성에는 provider 가격과 0보다 큰 고객 차감량이 필요합니다"

fun zeroCreditsAllowed(environment: Environment): Boolean =
    ILLUSTRATION_GENERATION_FAKE_PROFILE in environment.activeProfiles &&
        environment.activeProfiles.any { it in setOf("local", "test", "e2e") }

private fun workerPolicy(properties: IllustrationGenerationProperties): IllustrationGenerationJobWorkerPolicy =
    WorkerLeaseSupport
        .resolve(
            configuredOwner = properties.owner,
            defaultOwner = DEFAULT_OWNER,
            leaseDurationSeconds = properties.leaseDurationSeconds,
            maxLeaseAttempts = properties.maxLeaseAttempts,
        ).let { IllustrationGenerationJobWorkerPolicy(it.owner, it.leaseDuration, it.maxLeaseAttempts) }

private const val DEFAULT_OWNER: String = "illustration-generation-worker"
