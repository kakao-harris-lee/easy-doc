package kr.easydoc.core.actionguide

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.segment.isSourceAnchorShapeValid
import kr.easydoc.core.segment.isSourceAnchorSupported

/** Analysis v2 does not certify meaning preservation, even when every source unit has a mapping. */
enum class GuideSuitability { GUIDE, NON_GUIDE, MIXED, UNCERTAIN }

enum class GuideActionPresence { FOUND, NONE, UNCERTAIN }

enum class GuideInformationStatus { PRESENT, NOT_IN_SOURCE, NOT_APPLICABLE, NEEDS_REVIEW }

enum class GuideCoverageStatus { ACTION, CONTEXT, NEEDS_REVIEW }

enum class GuideOutputMode { ADDITIONAL_GUIDE, FULL_DOCUMENT }

data class GuideSourceUnit(
    val id: Int,
    val text: String,
) {
    override fun toString(): String = "GuideSourceUnit(id=$id)"
}

data class GuideInformation(
    val status: GuideInformationStatus,
    val text: String?,
    val evidence: List<ActionGuideSourceAnchor>,
) {
    override fun toString(): String = "GuideInformation(status=$status)"
}

data class ExtractedGuideAction(
    val id: String,
    val instruction: GuideInformation,
    val actor: GuideInformation,
    val beneficiaries: GuideInformation,
    val conditions: List<GuideInformation>,
    val deadline: GuideInformation,
    val preparation: GuideInformation,
    val contact: GuideInformation,
    val afterActionIds: List<String>,
    val orderEvidence: List<ActionGuideSourceAnchor>,
) {
    override fun toString(): String = "ExtractedGuideAction()"
}

data class GuideUnitAssessment(
    val sourceUnitId: Int,
    val status: GuideCoverageStatus,
    val actionIds: List<String>,
)

data class GuideAnalysisResult(
    val suitability: GuideSuitability,
    val actionPresence: GuideActionPresence,
    val reason: String,
    val evidence: List<ActionGuideSourceAnchor>,
    val actions: List<ExtractedGuideAction>,
    val coverage: List<GuideUnitAssessment>,
    /** Unresolved semantic/extraction/absence signals. Empty does not mean human review is complete. */
    val unresolvedSignals: List<String>,
    val extractionReviewComplete: Boolean,
) {
    override fun toString(): String = "GuideAnalysisResult(suitability=$suitability, actions=${actions.size})"
}

object GuideAnalysisPolicy {
    fun allowedModes(result: GuideAnalysisResult): List<GuideOutputMode> =
        if (hasGroundedActions(result) && hasResolvedExtraction(result)) {
            GuideOutputMode.entries
        } else {
            emptyList()
        }

    private fun hasGroundedActions(result: GuideAnalysisResult): Boolean =
        result.suitability in setOf(GuideSuitability.GUIDE, GuideSuitability.MIXED) &&
            result.actionPresence == GuideActionPresence.FOUND && result.actions.isNotEmpty()

    private fun hasResolvedExtraction(result: GuideAnalysisResult): Boolean =
        result.extractionReviewComplete && result.unresolvedSignals.isEmpty() &&
            result.coverage.none { it.status == GuideCoverageStatus.NEEDS_REVIEW }

    /** The source denominator belongs to the server. It cannot be shortened by an analyzer. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // Each independent grounding invariant is explicit.
    fun validate(
        result: GuideAnalysisResult,
        sourceUnits: List<String>,
    ) {
        requireValid(result.reason.isNotBlank())
        requireValid(result.coverage.map { it.sourceUnitId }.sorted() == sourceUnits.indices.toList())
        requireValid(
            result.actions
                .map { it.id }
                .distinct()
                .size == result.actions.size,
        )
        requireValid(result.actions.all { it.id.isNotBlank() })
        requireValid((result.actionPresence == GuideActionPresence.FOUND) == result.actions.isNotEmpty())
        result.evidence.forEach { validateAnchor(it, sourceUnits) }
        val ids = result.actions.map { it.id }.toSet()
        result.coverage.forEach { unit ->
            requireValid(unit.actionIds.all { it in ids })
            requireValid((unit.status == GuideCoverageStatus.ACTION) == unit.actionIds.isNotEmpty())
        }
        result.actions.forEach { action ->
            requireValid(action.instruction.status == GuideInformationStatus.PRESENT)
            (
                listOf(
                    action.instruction,
                    action.actor,
                    action.beneficiaries,
                    action.deadline,
                    action.preparation,
                    action.contact,
                ) + action.conditions
            ).forEach {
                validateInformation(it, sourceUnits)
                if (result.extractionReviewComplete) requireValid(it.status != GuideInformationStatus.NEEDS_REVIEW)
            }
            requireValid(action.afterActionIds.all { it in ids && it != action.id })
            requireValid(action.afterActionIds.isEmpty() == action.orderEvidence.isEmpty())
            action.orderEvidence.forEach { validateAnchor(it, sourceUnits) }
            requireValid(result.coverage.any { action.id in it.actionIds })
            val groundedUnits =
                (
                    listOf(
                        action.instruction,
                        action.actor,
                        action.beneficiaries,
                        action.deadline,
                        action.preparation,
                        action.contact,
                    ) + action.conditions
                ).flatMap { it.evidence }.flatMap { it.sourceUnitIndexes }.toSet()
            requireValid(result.coverage.filter { action.id in it.actionIds }.all { it.sourceUnitId in groundedUnits })
        }
        val pending = result.actions.associate { it.id to it.afterActionIds.toMutableSet() }.toMutableMap()
        while (pending.isNotEmpty()) {
            val ready = pending.filterValues { it.isEmpty() }.keys
            requireValid(ready.isNotEmpty())
            ready.forEach { pending.remove(it) }
            pending.values.forEach { it.removeAll(ready) }
        }
        // Neither a full mapping nor a model's claim of absence proves semantic completeness.
        if (result.extractionReviewComplete) {
            requireValid(result.unresolvedSignals.isEmpty())
            requireValid(result.coverage.none { it.status == GuideCoverageStatus.NEEDS_REVIEW })
        }
    }

    private fun validateInformation(
        info: GuideInformation,
        units: List<String>,
    ) {
        if (info.status == GuideInformationStatus.PRESENT) {
            requireValid(!info.text.isNullOrBlank() && info.evidence.isNotEmpty())
        }
        if (info.status in setOf(GuideInformationStatus.NOT_IN_SOURCE, GuideInformationStatus.NOT_APPLICABLE)) {
            requireValid(info.text == null && info.evidence.isEmpty())
        }
        info.evidence.forEach { validateAnchor(it, units) }
    }

    private fun validateAnchor(
        anchor: ActionGuideSourceAnchor,
        units: List<String>,
    ) {
        requireValid(isSourceAnchorShapeValid(anchor.sourceUnitIndexes, anchor.quote))
        requireValid(isSourceAnchorSupported(anchor.sourceUnitIndexes, anchor.quote, units))
    }

    private fun requireValid(valid: Boolean) {
        if (!valid) throw InvalidInputException("행동 분석 결과 검증에 실패했습니다")
    }
}
