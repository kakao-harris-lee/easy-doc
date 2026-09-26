package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.GuideAnalysisSnapshot
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** Private storage codec is isolated from HTTP mapper settings and needs no Kotlin reflection module. */
internal object GuideAnalysisSnapshotCodec {
    private val mapper = JsonMapper.builder().build()

    fun encode(snapshot: GuideAnalysisSnapshot): ByteArray = mapper.writeValueAsBytes(snapshot)

    fun decode(bytes: ByteArray): GuideAnalysisSnapshot {
        val root = mapper.readTree(bytes)
        val result = root["result"]
        return GuideAnalysisSnapshot(
            UUID.fromString(root["analysisId"].asString()),
            root["basedOnContentRevision"].asLong(),
            root["analysisRevision"].asLong(),
            root["sourceUnits"].toList().map { GuideSourceUnit(it["id"].asInt(), it["text"].asString()) },
            root["savedBody"].asString(),
            root["readingLevel"].asString(),
            GuideAnalysisResult(
                GuideSuitability.valueOf(result["suitability"].asString()),
                GuideActionPresence.valueOf(result["actionPresence"].asString()),
                result["reason"].asString(),
                anchors(result["evidence"]),
                result["actions"].toList().map(::action),
                result["coverage"].toList().map {
                    GuideUnitAssessment(
                        it["sourceUnitId"].asInt(),
                        GuideCoverageStatus.valueOf(it["status"].asString()),
                        strings(it["actionIds"]),
                    )
                },
                strings(result["unresolvedSignals"]),
                result["extractionReviewComplete"].asBoolean(),
            ),
            Instant.parse(root["createdAt"].asString()),
        )
    }

    private fun action(node: JsonNode): ExtractedGuideAction =
        ExtractedGuideAction(
            node["id"].asString(),
            info(node["instruction"]),
            info(node["actor"]),
            info(node["beneficiaries"]),
            node["conditions"].toList().map(::info),
            info(node["deadline"]),
            info(node["preparation"]),
            info(node["contact"]),
            strings(node["afterActionIds"]),
            anchors(node["orderEvidence"]),
        )

    private fun info(node: JsonNode): GuideInformation =
        GuideInformation(
            GuideInformationStatus.valueOf(node["status"].asString()),
            node["text"].takeUnless { it.isNull }?.asString(),
            anchors(node["evidence"]),
        )

    private fun strings(node: JsonNode): List<String> = node.toList().map { it.asString() }

    private fun anchors(node: JsonNode): List<ActionGuideSourceAnchor> =
        node.toList().map {
            ActionGuideSourceAnchor(
                it["sourceUnitIndexes"].toList().map { index ->
                    index.asInt()
                },
                it["quote"].asString(),
            )
        }
}
