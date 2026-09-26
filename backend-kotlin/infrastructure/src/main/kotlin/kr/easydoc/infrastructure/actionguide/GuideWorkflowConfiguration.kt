package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideJobRunner
import kr.easydoc.application.actionguide.ActionGuideProviderCall
import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.application.actionguide.GuideAnalysisRepository
import kr.easydoc.application.actionguide.GuideAnalysisReviewService
import kr.easydoc.application.actionguide.GuideAnalysisSnapshot
import kr.easydoc.application.actionguide.GuideDraftApplyService
import kr.easydoc.application.actionguide.GuideDraftRepository
import kr.easydoc.application.actionguide.GuideDraftService
import kr.easydoc.application.actionguide.GuideReviewSignals
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.actionguide.ExtractedGuideAction
import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideInformation
import kr.easydoc.core.actionguide.GuideInformationStatus
import kr.easydoc.core.actionguide.GuideSourceUnit
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.segment.splitUnits
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.llm.LlmProperties
import kr.easydoc.infrastructure.llm.LlmProviderConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class GuideWorkflowConfiguration {
    @Bean
    fun guideAnalysisIntakeService(
        properties: ActionGuideProperties,
        analyses: GuideAnalysisRepository,
        jobs: kr.easydoc.application.actionguide.ActionGuideJobService,
        jobRepository: kr.easydoc.application.actionguide.ActionGuideJobRepository,
        transaction: TransactionRunner,
    ): kr.easydoc.application.actionguide.GuideAnalysisIntakeService =
        kr.easydoc.application.actionguide.GuideAnalysisIntakeService(
            properties.enabled && properties.analysisEnabled,
            analyses,
            jobs,
            jobRepository,
            transaction,
        )

    @Bean
    fun guideAnalysisRepository(
        jdbc: JdbcClient,
        cipher: ContentCipher,
    ): GuideAnalysisRepository = JdbcGuideAnalysisRepository(jdbc, cipher)

    @Bean
    fun guideDraftRepository(
        jdbc: JdbcClient,
        cipher: ContentCipher,
    ): GuideDraftRepository = JdbcGuideDraftRepository(jdbc, cipher)

    @Bean
    fun guideAnalysisReviewService(
        repository: GuideAnalysisRepository,
        transaction: TransactionRunner,
    ): GuideAnalysisReviewService = GuideAnalysisReviewService(repository, transaction)

    @Bean
    fun guideDraftService(
        properties: ActionGuideProperties,
        analyses: GuideAnalysisRepository,
        drafts: GuideDraftRepository,
        transaction: TransactionRunner,
    ): GuideDraftService =
        GuideDraftService(properties.enabled && properties.analysisEnabled, analyses, drafts, transaction)

    @Bean
    fun guideDraftApplyService(
        conversions: ConversionRepository,
        jdbc: JdbcClient,
        drafts: GuideDraftService,
        cipher: ContentCipher,
        transaction: TransactionRunner,
    ): GuideDraftApplyService =
        GuideDraftApplyService(conversions, JdbcGuideDraftApplyRepository(jdbc), drafts, cipher, transaction)

    @Bean("guideAnalysisJobRunner")
    fun guideAnalysisJobRunner(
        repository: GuideAnalysisRepository,
        properties: LlmProperties,
        environment: Environment,
    ): ActionGuideJobRunner {
        val profiles = environment.activeProfiles.toSet()
        if ("action-guide-fake" in profiles || "action-guide-analysis-fake" in profiles) {
            check(
                profiles.any { it in setOf("local", "test") } &&
                    profiles.none { it in setOf("prod", "production", "pilot") },
            ) {
                "검증용 행동 분석은 local/test 환경에서만 사용할 수 있습니다"
            }
            return fakeRunner(repository)
        }
        // Lazy provider creation: API-only and disabled deployments do not need provider configuration.
        return ActionGuideJobRunner { job ->
            val bounded =
                properties.copy(
                    maxOutputTokens = ProviderGuideAnalysisJobRunner.MAX_OUTPUT_TOKENS,
                    readTimeout = Duration.ofSeconds(ANALYSIS_TIMEOUT_SECONDS),
                )
            ProviderGuideAnalysisJobRunner(
                repository,
                LlmProviderConfiguration().llmProvider(bounded, environment),
            ).prepare(job)
        }
    }

    @Suppress("LongMethod") // One explicit deterministic fake fixture keeps production behavior separate.
    private fun fakeRunner(repository: GuideAnalysisRepository): ActionGuideJobRunner =
        ActionGuideJobRunner { job ->
            val input = repository.lockInput(job.ownerId, job.conversionId) ?: return@ActionGuideJobRunner null
            val units = splitUnits(input.sourceText).mapIndexed { index, text -> GuideSourceUnit(index, text) }
            ActionGuideProviderCall {
                val absent = GuideInformation(GuideInformationStatus.NOT_IN_SOURCE, null, emptyList())
                val actions =
                    units.filter { it.text.isNotBlank() }.map { unit ->
                        ExtractedGuideAction(
                            "a${unit.id}",
                            GuideInformation(
                                GuideInformationStatus.PRESENT,
                                unit.text,
                                listOf(ActionGuideSourceAnchor(listOf(unit.id), unit.text)),
                            ),
                            absent,
                            absent,
                            emptyList(),
                            absent,
                            absent,
                            absent,
                            emptyList(),
                            emptyList(),
                        )
                    }
                val result =
                    GuideAnalysisResult(
                        GuideSuitability.GUIDE,
                        GuideActionPresence.FOUND,
                        "검증용 고정 분석입니다. 실제 판단 품질을 증명하지 않습니다.",
                        emptyList(),
                        actions,
                        units.map {
                            GuideUnitAssessment(
                                it.id,
                                if (it.text.isBlank()) GuideCoverageStatus.CONTEXT else GuideCoverageStatus.ACTION,
                                if (it.text.isBlank()) emptyList() else listOf("a${it.id}"),
                            )
                        },
                        emptyList(),
                        false,
                    )
                kr.easydoc.core.actionguide.GuideAnalysisPolicy
                    .validate(result, units.map { it.text })
                val snapshot =
                    GuideAnalysisSnapshot(
                        UUID.randomUUID(),
                        input.contentRevision,
                        1,
                        units,
                        input.savedBody,
                        input.readingLevel,
                        result,
                        Instant.now(),
                        "fake",
                        "fake-grounded-v1",
                    )
                ActionGuideRunResult.ValidAnalysis(
                    LlmCallRecord(
                        LlmCallPurpose.ACTION_GUIDE,
                        "fake",
                        "fake-guide-analysis",
                        0,
                        0,
                        0,
                        null,
                        null,
                        null,
                        input.sourceText.length,
                        Instant.now(),
                        LlmCallOutcome.COMPLETED,
                    ),
                    snapshot.copy(signals = GuideReviewSignals.create(snapshot)),
                )
            }
        }

    private companion object {
        const val ANALYSIS_TIMEOUT_SECONDS: Long = 90
    }
}
