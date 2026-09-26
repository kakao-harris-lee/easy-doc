package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideAnalysisService
import kr.easydoc.application.actionguide.GuideAnalyzer
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient

@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class GuideAnalysisConfiguration {
    @Bean
    fun guideAnalysisService(
        jdbc: JdbcClient,
        cipher: ContentCipher,
        transaction: TransactionRunner,
        environment: Environment,
    ): ActionGuideAnalysisService {
        val enabled = requireFakeAnalysisConfiguration(environment)
        val analyzer =
            GuideAnalyzer { _, units ->
                GuideAnalysisResult(
                    GuideSuitability.UNCERTAIN,
                    GuideActionPresence.UNCERTAIN,
                    "검증용 분석입니다. 실제 안내문 판단이나 행동 추출을 수행하지 않았습니다.",
                    emptyList(),
                    emptyList(),
                    units.map { GuideUnitAssessment(it.id, GuideCoverageStatus.NEEDS_REVIEW, emptyList()) },
                    listOf("fake_analysis_requires_review"),
                    false,
                )
            }
        return ActionGuideAnalysisService(enabled, JdbcGuideAnalysisRepository(jdbc, cipher), analyzer, transaction)
    }
}

internal fun requireFakeAnalysisConfiguration(environment: Environment): Boolean {
    val enabled = environment.getProperty("easydoc.action-guide.analysis-enabled", Boolean::class.java, false)
    val profiles = environment.activeProfiles.toSet()
    val fake = "action-guide-analysis-fake" in profiles
    val local = profiles.any { it in setOf("local", "test") }
    val production = profiles.any { it in setOf("prod", "production", "pilot") }
    check(!fake || (local && !production)) {
        "행동 분석 fake 프로필은 local/test에서만 사용할 수 있습니다"
    }
    return enabled && fake
}
