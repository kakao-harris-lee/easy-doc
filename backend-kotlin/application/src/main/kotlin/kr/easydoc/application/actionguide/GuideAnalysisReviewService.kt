package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.actionguide.GuideAnalysisPolicy
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideInformationStatus
import kr.easydoc.core.easyread.findMissingFacts
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.segment.isSourceAnchorSupported
import kr.easydoc.core.segment.splitUnits
import java.util.UUID

data class GuideReviewSignal(
    val id: String,
    val kind: String,
    val sourceUnitIds: List<Int>,
    val actionId: String?,
    val detail: String,
    val resolved: Boolean = false,
    val resolvable: Boolean = true,
    val resolutionNote: String? = null,
    val bodyUnitIndexes: List<Int> = emptyList(),
    val bodyQuote: String? = null,
) {
    override fun toString(): String = "GuideReviewSignal(id=$id, resolved=$resolved)"
}

data class GuideReviewRevision(
    val content: Long,
    val analysis: Long,
    val review: Long,
)

/** Every original unit and every absence assertion requires its own review. Model booleans are ignored. */
object GuideReviewSignals {
    @Suppress("LongMethod") // Complete denominator and absence signals are deliberately enumerated together.
    fun create(snapshot: GuideAnalysisSnapshot): List<GuideReviewSignal> =
        buildList {
            snapshot.sourceUnits.filter { it.text.isNotBlank() }.forEach { unit ->
                add(
                    GuideReviewSignal(
                        "source-${unit.id}",
                        "source_body",
                        listOf(unit.id),
                        null,
                        "원문 부분을 현재 본문과 대조하고 같은 의미를 담은 본문 근거를 지정하세요.",
                    ),
                )
                add(
                    GuideReviewSignal(
                        "extract-${unit.id}",
                        "extraction",
                        listOf(unit.id),
                        null,
                        "이 부분의 행동·대상·조건이 빠지지 않았는지 확인하세요. 누락은 분석 교정으로 보완하세요.",
                    ),
                )
            }
            snapshot.result.actions.forEachIndexed { actionIndex, action ->
                val fields =
                    listOf(
                        "actor" to action.actor,
                        "beneficiaries" to action.beneficiaries,
                        "deadline" to action.deadline,
                        "preparation" to action.preparation,
                        "contact" to action.contact,
                    ) +
                        action.conditions.mapIndexed { index, value -> "condition-$index" to value }
                fields.filter { it.second.status != GuideInformationStatus.PRESENT }.forEach { (name, info) ->
                    add(
                        GuideReviewSignal(
                            "absence-$actionIndex-$name",
                            "missing_information",
                            emptyList(),
                            action.id,
                            "$name: ${info.status.name.lowercase()} 판단을 원문 전체와 대조하세요. 잘못된 미기재는 분석 교정이 필요합니다.",
                            resolvable = info.status != GuideInformationStatus.NEEDS_REVIEW,
                        ),
                    )
                }
            }
            if (findMissingFacts(
                    snapshot.sourceUnits.joinToString("\n") { it.text },
                    snapshot.savedBody,
                ).isNotEmpty()
            ) {
                add(
                    GuideReviewSignal(
                        "missing-facts",
                        "fact_difference",
                        emptyList(),
                        null,
                        "원문의 수치·날짜 등 일부 정보가 현재 본문에 없습니다. 본문을 보완하고 다시 분석하세요.",
                        resolvable = false,
                    ),
                )
            }
            add(
                GuideReviewSignal(
                    "reading-level",
                    "reading_level",
                    emptyList(),
                    null,
                    "선택한 읽기 수준에 맞는 행동 설명인지 확인하세요.",
                ),
            )
        }
}

// Keep every authorization and independent CAS version visible at the review boundary.
@Suppress("LongParameterList", "ComplexCondition")
class GuideAnalysisReviewService(
    private val repository: GuideAnalysisRepository,
    private val transaction: TransactionRunner,
) {
    fun resolve(
        owner: UUID,
        conversion: UUID,
        analysis: UUID,
        revision: GuideReviewRevision,
        signalId: String,
        note: String,
        bodyIndexes: List<Int>,
        bodyQuote: String?,
    ): GuideAnalysisView =
        transaction.inTransaction {
            val snapshot = current(owner, conversion, analysis, revision)
            val signal = snapshot.signals.find { it.id == signalId } ?: throw NotFoundException("검토 항목을 찾을 수 없습니다")
            if (!signal.resolvable || note.isBlank() ||
                note.length > MAX_RESOLUTION_NOTE_LENGTH
            ) {
                throw InvalidInputException("본문 또는 분석 교정이 필요합니다")
            }
            if (signal.kind == "source_body" && (
                    bodyQuote.isNullOrBlank() ||
                        !kr.easydoc.core.segment
                            .isSourceAnchorShapeValid(bodyIndexes, bodyQuote) ||
                        !isSourceAnchorSupported(bodyIndexes, bodyQuote, splitUnits(snapshot.savedBody))
                )
            ) {
                throw InvalidInputException("현재 본문에 대응하는 근거를 지정하세요")
            }
            val updated =
                snapshot.copy(
                    reviewRevision = snapshot.reviewRevision + 1,
                    reviewed = false,
                    signals =
                        snapshot.signals.map {
                            if (it.id == signalId) {
                                it.copy(
                                    resolved = true,
                                    resolutionNote = note,
                                    bodyUnitIndexes = bodyIndexes,
                                    bodyQuote = bodyQuote,
                                )
                            } else {
                                it
                            }
                        },
                    result = snapshot.result.copy(extractionReviewComplete = false),
                )
            save(owner, conversion, snapshot, updated)
        }

    fun correct(
        owner: UUID,
        conversion: UUID,
        analysis: UUID,
        revision: GuideReviewRevision,
        result: GuideAnalysisResult,
    ): GuideAnalysisView =
        transaction.inTransaction {
            val snapshot = current(owner, conversion, analysis, revision)
            val corrected = result.copy(extractionReviewComplete = false, unresolvedSignals = emptyList())
            GuideAnalysisPolicy.validate(corrected, snapshot.sourceUnits.map { it.text })
            val updated =
                snapshot.copy(
                    analysisRevision = snapshot.analysisRevision + 1,
                    reviewRevision = snapshot.reviewRevision + 1,
                    reviewed = false,
                    result = corrected,
                )
            save(owner, conversion, snapshot, updated.copy(signals = GuideReviewSignals.create(updated)))
        }

    fun review(
        owner: UUID,
        conversion: UUID,
        analysis: UUID,
        revision: GuideReviewRevision,
    ): GuideAnalysisView =
        transaction.inTransaction {
            val snapshot = current(owner, conversion, analysis, revision)
            if (snapshot.signals.isEmpty() ||
                snapshot.signals.any { !it.resolved && it.kind !in setOf("source_body", "fact_difference") }
            ) {
                throw ConflictException("검토할 항목이 남아 있습니다")
            }
            val result = snapshot.result.copy(extractionReviewComplete = true, unresolvedSignals = emptyList())
            GuideAnalysisPolicy.validate(result, snapshot.sourceUnits.map { it.text })
            save(
                owner,
                conversion,
                snapshot,
                snapshot.copy(reviewRevision = snapshot.reviewRevision + 1, reviewed = true, result = result),
            )
        }

    private fun current(
        owner: UUID,
        conversion: UUID,
        analysis: UUID,
        revision: GuideReviewRevision,
    ): GuideAnalysisSnapshot {
        val input = repository.lockInput(owner, conversion) ?: throw NotFoundException("변환을 찾을 수 없습니다")
        val snapshot = repository.find(owner, conversion, analysis) ?: throw NotFoundException("분석을 찾을 수 없습니다")
        requireCurrentRevision(input, snapshot, revision)
        return snapshot
    }

    private fun requireCurrentRevision(
        input: GuideAnalysisInput,
        snapshot: GuideAnalysisSnapshot,
        revision: GuideReviewRevision,
    ) {
        if (!input.completed || input.contentRevision != revision.content ||
            snapshot.basedOnContentRevision != revision.content ||
            snapshot.analysisRevision != revision.analysis ||
            snapshot.reviewRevision != revision.review
        ) {
            throw ConflictException("분석 또는 본문 버전이 바뀌었습니다")
        }
    }

    private fun save(
        owner: UUID,
        conversion: UUID,
        previous: GuideAnalysisSnapshot,
        updated: GuideAnalysisSnapshot,
    ): GuideAnalysisView {
        if (!repository.replace(
                owner,
                conversion,
                previous.analysisRevision,
                previous.reviewRevision,
                updated,
            )
        ) {
            throw ConflictException("분석 버전이 바뀌었습니다")
        }
        return GuideAnalysisView(updated, "current")
    }

    companion object {
        private const val MAX_RESOLUTION_NOTE_LENGTH = 2_000
    }
}
