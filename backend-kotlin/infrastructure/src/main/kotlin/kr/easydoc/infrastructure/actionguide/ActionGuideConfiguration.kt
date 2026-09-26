package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideContentRepository
import kr.easydoc.application.actionguide.ActionGuideContentService
import kr.easydoc.application.actionguide.ActionGuideCreditPort
import kr.easydoc.application.actionguide.ActionGuideJobRepository
import kr.easydoc.application.actionguide.ActionGuideJobRunner
import kr.easydoc.application.actionguide.ActionGuideJobService
import kr.easydoc.application.actionguide.ActionGuideJobWorkerPolicy
import kr.easydoc.application.actionguide.ActionGuideLlmCallLedger
import kr.easydoc.application.actionguide.ActionGuideOperation
import kr.easydoc.application.actionguide.ActionGuideProviderCall
import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.application.actionguide.GuideAnalysisRepository
import kr.easydoc.application.actionguide.ProcessActionGuideJob
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.ReviewHistoryAppender
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.infrastructure.credit.CreditsProperties
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.llm.LlmProviderConfiguration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** R2 공개 기능과 worker는 각각 명시적으로 켜며 기본값은 모두 꺼져 있다. */
@ConfigurationProperties(prefix = "easydoc.action-guide")
data class ActionGuideProperties(
    val enabled: Boolean = false,
    val workerEnabled: Boolean = false,
    val owner: String = "",
    val leaseDurationSeconds: Long = DEFAULT_LEASE_SECONDS,
    val maxLeaseAttempts: Int = DEFAULT_MAX_LEASE_ATTEMPTS,
    val analysisEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_LEASE_SECONDS: Long = 120
        const val DEFAULT_MAX_LEASE_ATTEMPTS: Int = 5
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class ActionGuideConfiguration {
    @Bean
    fun actionGuideContentRepository(jdbcClient: JdbcClient): ActionGuideContentRepository =
        JdbcActionGuideContentRepository(jdbcClient)

    @Bean
    fun actionGuideJobRepository(jdbcClient: JdbcClient): ActionGuideJobRepository =
        JdbcActionGuideJobRepository(jdbcClient)

    @Bean
    fun actionGuideCreditPort(
        jdbcClient: JdbcClient,
        creditsProperties: CreditsProperties,
    ): ActionGuideCreditPort = JdbcActionGuideCreditPort(jdbcClient, creditsProperties.enforced)

    @Bean
    fun actionGuideLlmCallLedger(jdbcClient: JdbcClient): ActionGuideLlmCallLedger =
        JdbcActionGuideLlmCallLedger(jdbcClient)

    @Bean
    fun actionGuideJobService(
        properties: ActionGuideProperties,
        jobs: ActionGuideJobRepository,
        credits: ActionGuideCreditPort,
        transactionRunner: TransactionRunner,
    ): ActionGuideJobService =
        ActionGuideJobService(
            properties.enabled,
            jobs,
            credits,
            transactionRunner,
            analysisEnabled = properties.analysisEnabled,
        )

    @Suppress("LongParameterList")
    @Bean
    fun actionGuideContentService(
        properties: ActionGuideProperties,
        jobs: ActionGuideJobRepository,
        contents: ActionGuideContentRepository,
        documents: DocumentRepository,
        conversions: ConversionRepository,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
        reviewHistory: ReviewHistoryAppender,
    ): ActionGuideContentService =
        ActionGuideContentService(
            properties.enabled,
            jobs,
            contents,
            documents,
            cipher,
            transactionRunner,
            Clock.systemUTC(),
            conversions,
            reviewHistory,
        )
}

/** intake OFF + worker ON이면 기존 queued 작업을 provider 호출 없이 비우는 운영 구성. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@ConditionalOnExpression(
    "'\${easydoc.action-guide.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.action-guide.enabled:false}' != 'true'",
)
class ActionGuideDrainWorkerConfiguration {
    @Bean
    fun actionGuideWorkerPolicy(properties: ActionGuideProperties): ActionGuideJobWorkerPolicy =
        workerPolicy(properties)

    @Bean
    fun disabledActionGuideJobRunner(): ActionGuideJobRunner =
        ActionGuideJobRunner { error("intake OFF drain 경로에서 runner를 호출하면 안 됩니다") }

    @Suppress("LongParameterList")
    @Bean
    fun processActionGuideJob(
        jobs: ActionGuideJobRepository,
        credits: ActionGuideCreditPort,
        ledger: ActionGuideLlmCallLedger,
        contents: ActionGuideContentRepository,
        cipher: ContentCipher,
        @Qualifier("disabledActionGuideJobRunner") runner: ActionGuideJobRunner,
        transactionRunner: TransactionRunner,
        policy: ActionGuideJobWorkerPolicy,
        analyses: GuideAnalysisRepository? = null,
        properties: ActionGuideProperties = ActionGuideProperties(),
    ): ProcessActionGuideJob =
        ProcessActionGuideJob(
            jobs,
            credits,
            ledger,
            contents,
            cipher,
            runner,
            transactionRunner,
            policy,
            analyses = analyses,
            operationEnabled = { it != ActionGuideOperation.ANALYSIS || properties.analysisEnabled },
        )
}

/** ER-05 검증 전용. 이 프로필 없이는 fake runner와 poller 처리 빈이 만들어지지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker & action-guide-fake")
@ConditionalOnExpression(
    "'\${easydoc.action-guide.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.action-guide.enabled:false}' == 'true'",
)
class FakeActionGuideWorkerConfiguration {
    @Bean
    fun actionGuideWorkerPolicy(properties: ActionGuideProperties): ActionGuideJobWorkerPolicy =
        workerPolicy(properties)

    @Bean
    fun fakeActionGuideJobRunner(
        @Qualifier("guideAnalysisJobRunner") analysisRunner: ActionGuideJobRunner? = null,
    ): ActionGuideJobRunner =
        ActionGuideJobRunner { job ->
            if (job.operation == ActionGuideOperation.ANALYSIS) {
                return@ActionGuideJobRunner checkNotNull(analysisRunner).prepare(job)
            }
            ActionGuideProviderCall {
                val record =
                    LlmCallRecord(
                        purpose = LlmCallPurpose.ACTION_GUIDE,
                        provider = "fake",
                        model = "fake-action-guide-r2",
                        inputTokens = 0,
                        outputTokens = 0,
                        latencyMs = 0,
                        estimatedCostUsd = null,
                        pricingInputUsdPerMtok = null,
                        pricingOutputUsdPerMtok = null,
                        charCount = 0,
                        calledAt = Instant.now(),
                        outcome = LlmCallOutcome.COMPLETED,
                    )
                ActionGuideRunResult.Valid(
                    record,
                    ActionGuideCandidate(
                        schemaVersion = 1,
                        sections =
                            ActionGuideSectionKind.entries.map { kind ->
                                ActionGuideSection(kind, ActionGuideSectionStatus.NOT_IN_SOURCE, emptyList())
                            },
                    ),
                )
            }
        }

    @Suppress("LongParameterList")
    @Bean
    fun processActionGuideJob(
        jobs: ActionGuideJobRepository,
        credits: ActionGuideCreditPort,
        ledger: ActionGuideLlmCallLedger,
        contents: ActionGuideContentRepository,
        cipher: ContentCipher,
        @Qualifier("fakeActionGuideJobRunner") runner: ActionGuideJobRunner,
        transactionRunner: TransactionRunner,
        policy: ActionGuideJobWorkerPolicy,
        analyses: GuideAnalysisRepository? = null,
        properties: ActionGuideProperties = ActionGuideProperties(),
    ): ProcessActionGuideJob =
        ProcessActionGuideJob(
            jobs,
            credits,
            ledger,
            contents,
            cipher,
            runner,
            transactionRunner,
            policy,
            analyses = analyses,
            operationEnabled = { it != ActionGuideOperation.ANALYSIS || properties.analysisEnabled },
        )
}

/** 실제 호출은 intake와 worker 플래그를 함께 켠 worker 프로필에서만 가능하다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker & !action-guide-fake")
@ConditionalOnExpression(
    "'\${easydoc.action-guide.worker-enabled:false}' == 'true' && " +
        "'\${easydoc.action-guide.enabled:false}' == 'true'",
)
class ProviderActionGuideWorkerConfiguration {
    @Bean
    fun actionGuideWorkerPolicy(properties: ActionGuideProperties): ActionGuideJobWorkerPolicy =
        workerPolicy(properties)

    @Bean
    fun providerActionGuideJobRunner(
        jdbcClient: JdbcClient,
        cipher: ContentCipher,
        llmProperties: LlmProperties,
        environment: Environment,
        @Qualifier("guideAnalysisJobRunner") analysisRunner: ActionGuideJobRunner? = null,
    ): ActionGuideJobRunner {
        // 별도 provider 인스턴스에 90초 응답 제한을 둔다. 변환 worker의 긴 출력·타임아웃은 건드리지 않는다.
        val boundedProperties =
            llmProperties.copy(
                maxOutputTokens = ProviderActionGuideJobRunner.MAX_OUTPUT_TOKENS,
                readTimeout = Duration.ofSeconds(ACTION_GUIDE_PROVIDER_TIMEOUT_SECONDS),
            )
        val provider = LlmProviderConfiguration().llmProvider(boundedProperties, environment)
        val legacy = ProviderActionGuideJobRunner(JdbcActionGuideInputSource(jdbcClient, cipher), provider)
        return ActionGuideJobRunner { job ->
            if (job.operation == ActionGuideOperation.ANALYSIS) {
                checkNotNull(analysisRunner).prepare(job)
            } else {
                legacy.prepare(job)
            }
        }
    }

    @Suppress("LongParameterList")
    @Bean
    fun processActionGuideJob(
        jobs: ActionGuideJobRepository,
        credits: ActionGuideCreditPort,
        ledger: ActionGuideLlmCallLedger,
        contents: ActionGuideContentRepository,
        cipher: ContentCipher,
        @Qualifier("providerActionGuideJobRunner") runner: ActionGuideJobRunner,
        transactionRunner: TransactionRunner,
        policy: ActionGuideJobWorkerPolicy,
        analyses: GuideAnalysisRepository? = null,
        properties: ActionGuideProperties = ActionGuideProperties(),
    ): ProcessActionGuideJob =
        ProcessActionGuideJob(
            jobs,
            credits,
            ledger,
            contents,
            cipher,
            runner,
            transactionRunner,
            policy,
            analyses = analyses,
            operationEnabled = { it != ActionGuideOperation.ANALYSIS || properties.analysisEnabled },
        )
}

private fun workerPolicy(properties: ActionGuideProperties): ActionGuideJobWorkerPolicy =
    ActionGuideJobWorkerPolicy(
        owner = properties.owner.ifBlank(::hostOwner).take(OWNER_MAX_LENGTH),
        leaseDuration = Duration.ofSeconds(properties.leaseDurationSeconds),
        maxLeaseAttempts = properties.maxLeaseAttempts,
    )

private fun hostOwner(): String =
    runCatching { InetAddress.getLocalHost().hostName }
        .getOrElse { "action-guide-worker" }
        .ifBlank { "action-guide-worker" }

private const val OWNER_MAX_LENGTH: Int = 64
private const val ACTION_GUIDE_PROVIDER_TIMEOUT_SECONDS: Long = 90
