package kr.easydoc.infrastructure.actionguide

import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisPolicy
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.core.exceptions.InvalidInputException
import tools.jackson.databind.json.JsonMapper

internal object GuideProviderAnalysisParser {
    private val mapper = JsonMapper.builder().build()

    fun parse(
        text: String,
        units: List<String>,
    ): GuideAnalysisResult {
        try {
            val root = mapper.readTree(text)
            val result =
                GuideAnalysisResult(
                    GuideSuitability.valueOf(root["suitability"].asString()),
                    GuideActionPresence.valueOf(root["actionPresence"].asString()),
                    root["reason"].asString(),
                    GuideAnalysisSnapshotCodec.anchors(root["evidence"]),
                    root["actions"].toList().map(GuideAnalysisSnapshotCodec::action),
                    root["coverage"].toList().map {
                        GuideUnitAssessment(
                            it["sourceUnitId"].asInt(),
                            GuideCoverageStatus.valueOf(it["status"].asString()),
                            it["actionIds"].toList().map { id ->
                                id.asString()
                            },
                        )
                    },
                    emptyList(),
                    false,
                )
            GuideAnalysisPolicy.validate(result, units)
            return result
        } catch (_: RuntimeException) {
            throw InvalidInputException("행동 분석 응답의 구조 또는 근거가 올바르지 않습니다")
        }
    }
}
