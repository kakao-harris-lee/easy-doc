package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideCreditPort
import kr.easydoc.application.actionguide.ActionGuideJobRepository
import kr.easydoc.application.actionguide.ActionGuideJobRunner
import kr.easydoc.application.actionguide.ActionGuideJobService
import kr.easydoc.application.actionguide.ActionGuideJobWorkerPolicy
import kr.easydoc.application.actionguide.ActionGuideLlmCallLedger
import kr.easydoc.application.actionguide.ProcessActionGuideJob
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.infrastructure.credit.CreditsProperties
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import java.net.InetAddress
import java.time.Duration
import java.time.Instant

/** R2 공개 기능과 worker는 각각 명시적으로 켜며 기본값은 모두 꺼져 있다. */
@ConfigurationProperties(prefix = "easydoc.action-guide")
data class ActionGuideProperties(
    val enabled: Boolean = false,
    val workerEnabled: Boolean = false,
    val owner: String = "",
    val leaseDurationSeconds: Long = DEFAULT_LEASE_SECONDS,
) {
    companion object {
        const val DEFAULT_LEASE_SECONDS: Long = 120
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class ActionGuideConfiguration {
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
    ): ActionGuideJobService = ActionGuideJobService(properties.enabled, jobs, credits, transactionRunner)
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
        runner: ActionGuideJobRunner,
        transactionRunner: TransactionRunner,
        policy: ActionGuideJobWorkerPolicy,
    ): ProcessActionGuideJob = ProcessActionGuideJob(jobs, credits, ledger, runner, transactionRunner, policy)
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
    fun fakeActionGuideJobRunner(): ActionGuideJobRunner =
        ActionGuideJobRunner {
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
        }

    @Suppress("LongParameterList")
    @Bean
    fun processActionGuideJob(
        jobs: ActionGuideJobRepository,
        credits: ActionGuideCreditPort,
        ledger: ActionGuideLlmCallLedger,
        runner: ActionGuideJobRunner,
        transactionRunner: TransactionRunner,
        policy: ActionGuideJobWorkerPolicy,
    ): ProcessActionGuideJob = ProcessActionGuideJob(jobs, credits, ledger, runner, transactionRunner, policy)
}

private fun workerPolicy(properties: ActionGuideProperties): ActionGuideJobWorkerPolicy =
    ActionGuideJobWorkerPolicy(
        owner = properties.owner.ifBlank(::hostOwner).take(OWNER_MAX_LENGTH),
        leaseDuration = Duration.ofSeconds(properties.leaseDurationSeconds),
    )

private fun hostOwner(): String =
    runCatching { InetAddress.getLocalHost().hostName }
        .getOrElse { "action-guide-worker" }
        .ifBlank { "action-guide-worker" }

private const val OWNER_MAX_LENGTH: Int = 64
