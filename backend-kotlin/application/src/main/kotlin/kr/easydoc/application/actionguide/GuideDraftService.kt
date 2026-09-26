package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.core.actionguide.GuideAnalysisPolicy
import kr.easydoc.core.actionguide.GuideInformation
import kr.easydoc.core.actionguide.GuideInformationStatus
import kr.easydoc.core.actionguide.GuideOutputMode
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import java.time.Instant
import java.util.UUID

// Every caller supplies ownership plus independent content, analysis, review and draft CAS versions.
@Suppress("LongParameterList", "ComplexCondition")
class GuideDraftService(
    private val enabled: Boolean,
    private val analyses: GuideAnalysisRepository,
    private val drafts: GuideDraftRepository,
    private val transaction: TransactionRunner,
) : GuideDraftApplySource {
    fun create(
        owner: UUID,
        conversion: UUID,
        request: UUID,
        analysisId: UUID,
        revision: GuideReviewRevision,
        mode: GuideOutputMode,
    ): GuideDraft =
        transaction.inTransaction {
            if (!enabled) throw NotFoundException("행동 보완 요청을 사용할 수 없습니다")
            analyses.lockInput(owner, conversion) ?: throw NotFoundException("변환을 찾을 수 없습니다")
            drafts.findRequest(owner, conversion, request)?.let { existing ->
                if (existing.analysisId != analysisId || existing.mode != mode ||
                    existing.basedOnContentRevision != revision.content ||
                    existing.analysisRevision != revision.analysis ||
                    existing.analysisReviewRevision != revision.review
                ) {
                    throw ConflictException("요청 키의 입력이 다릅니다")
                }
                return@inTransaction existing
            }
            val analysis = applicableAnalysis(owner, conversion, analysisId, revision)
            if (mode !in
                GuideAnalysisView(analysis, "current").allowedModes
            ) {
                throw ConflictException("행동 안내를 만들 수 없는 분석입니다")
            }
            val blocks = blocks(analysis)
            val supplement =
                "행동 안내\n\n" +
                    blocks.joinToString("\n\n") { block ->
                        block.text + if (block.cautions.isEmpty()) "" else "\n" + block.cautions.joinToString("\n")
                    }
            val body =
                if (mode ==
                    GuideOutputMode.FULL_DOCUMENT
                ) {
                    analysis.savedBody + "\n\n" + supplement
                } else {
                    supplement
                }
            if (body.codePointCount(0, body.length) >
                MAX_DRAFT_CHARS
            ) {
                throw InvalidInputException("보완 결과가 20,000자 상한을 넘습니다. 전체 본문을 유지하고 추가 안내 자료를 이용하세요")
            }
            val draft =
                GuideDraft(
                    UUID.randomUUID(),
                    analysisId,
                    revision.analysis,
                    revision.review,
                    revision.content,
                    1,
                    mode,
                    body,
                    blocks,
                    false,
                    Instant.now(),
                )
            drafts.insertOwned(owner, conversion, request, draft)
            draft
        }

    private fun blocks(analysis: GuideAnalysisSnapshot): List<GuideDraftBlock> =
        analysis.result.actions.map { action ->
            val details =
                listOf(
                    "행동" to action.instruction,
                    "행동할 사람" to action.actor,
                    "대상" to action.beneficiaries,
                    "기한" to action.deadline,
                    "준비" to action.preparation,
                    "문의" to action.contact,
                ) + action.conditions.map { "조건" to it }
            val cautions =
                details
                    .filter {
                        it.second.status in
                            setOf(GuideInformationStatus.NOT_IN_SOURCE, GuideInformationStatus.NOT_APPLICABLE)
                    }.map {
                        "${it.first}: " +
                            if (it.second.status ==
                                GuideInformationStatus.NOT_IN_SOURCE
                            ) {
                                "원문에 적혀 있지 않음"
                            } else {
                                "이 행동에는 해당하지 않음"
                            }
                    }
            val detailText =
                details
                    .filter { it.second.status == GuideInformationStatus.PRESENT }
                    .joinToString("\n") { "${it.first}: ${it.second.text}" }
            val predecessors =
                action.afterActionIds.map { id ->
                    analysis.result.actions
                        .first { it.id == id }
                        .instruction.text
                }
            val text =
                detailText +
                    if (predecessors.isEmpty()) "" else "\n먼저 할 행동: " + predecessors.joinToString(" / ")
            GuideDraftBlock(
                "action-${action.id}",
                action.id,
                text,
                cautions,
                (details.flatMap { it.second.evidence } + action.orderEvidence).distinct(),
            )
        }

    fun list(
        owner: UUID,
        conversion: UUID,
    ): List<GuideDraft> =
        transaction.inTransaction {
            analyses.lockInput(owner, conversion) ?: throw NotFoundException("변환을 찾을 수 없습니다")
            drafts.listOwned(owner, conversion)
        }

    fun review(
        owner: UUID,
        conversion: UUID,
        draftId: UUID,
        revision: GuideReviewRevision,
        draftRevision: Long,
        confirmedBlocks: List<String>,
    ): GuideDraft =
        transaction.inTransaction {
            val draft = currentDraft(owner, conversion, draftId, revision, draftRevision)
            if (confirmedBlocks.toSet() != draft.blocks.map { it.id }.toSet() ||
                confirmedBlocks.size != draft.blocks.size
            ) {
                throw InvalidInputException("모든 행동 블록을 대조한 뒤 확인하세요")
            }
            val updated = draft.copy(draftRevision = draft.draftRevision + 1, reviewed = true)
            if (!drafts.replaceOwned(
                    owner,
                    conversion,
                    draftRevision,
                    updated,
                )
            ) {
                throw ConflictException("보완문 버전이 바뀌었습니다")
            }
            updated
        }

    fun export(
        owner: UUID,
        conversion: UUID,
        draftId: UUID,
    ): GuideDraft =
        transaction.inTransaction {
            val draft = drafts.findOwned(owner, conversion, draftId) ?: throw NotFoundException("보완문을 찾을 수 없습니다")
            currentDraft(
                owner,
                conversion,
                draftId,
                GuideReviewRevision(
                    draft.basedOnContentRevision,
                    draft.analysisRevision,
                    draft.analysisReviewRevision,
                ),
                draft.draftRevision,
            )
            if (!draft.reviewed) throw ConflictException("보완문 대조 확인이 필요합니다")
            draft
        }

    override fun requireApplicable(
        ownerId: UUID,
        conversionId: UUID,
        command: GuideDraftApplyCommand,
    ): PlainBody {
        val draft =
            currentDraft(
                ownerId,
                conversionId,
                command.draftId,
                GuideReviewRevision(
                    command.expectedContentRevision,
                    command.expectedAnalysisRevision,
                    command.expectedReviewRevision,
                ),
                command.expectedDraftRevision,
            )
        if (draft.mode != GuideOutputMode.FULL_DOCUMENT ||
            !draft.reviewed
        ) {
            throw ConflictException("확인한 전체 문서 보완만 반영할 수 있습니다")
        }
        return PlainBody(draft.body)
    }

    @Suppress("ThrowsCount") // Missing resource, blocked mode and stale revisions are distinct rejection paths.
    private fun currentDraft(
        owner: UUID,
        conversion: UUID,
        draftId: UUID,
        revision: GuideReviewRevision,
        draftRevision: Long,
    ): GuideDraft {
        val draft = drafts.findOwned(owner, conversion, draftId) ?: throw NotFoundException("보완문을 찾을 수 없습니다")
        val analysis = applicableAnalysis(owner, conversion, draft.analysisId, revision)
        if (draft.mode !in
            GuideAnalysisView(analysis, "current").allowedModes
        ) {
            throw ConflictException("전체 본문 대조 확인이 필요합니다")
        }
        if (draft.basedOnContentRevision != revision.content || draft.analysisRevision != revision.analysis ||
            draft.analysisReviewRevision != revision.review ||
            draft.draftRevision != draftRevision
        ) {
            throw ConflictException("보완문 입력 버전이 바뀌었습니다")
        }
        return draft
    }

    private fun applicableAnalysis(
        owner: UUID,
        conversion: UUID,
        analysisId: UUID,
        revision: GuideReviewRevision,
    ): GuideAnalysisSnapshot {
        val input = analyses.lockInput(owner, conversion) ?: throw NotFoundException("변환을 찾을 수 없습니다")
        val analysis = analyses.find(owner, conversion, analysisId) ?: throw NotFoundException("분석을 찾을 수 없습니다")
        requireApplicableRevision(input, analysis, revision)
        return analysis
    }

    private fun requireApplicableRevision(
        input: GuideAnalysisInput,
        analysis: GuideAnalysisSnapshot,
        revision: GuideReviewRevision,
    ) {
        if (!input.completed || input.contentRevision != revision.content ||
            analysis.basedOnContentRevision != revision.content ||
            analysis.analysisRevision != revision.analysis || analysis.reviewRevision != revision.review ||
            !analysis.reviewed ||
            analysis.signals.isEmpty() ||
            analysis.signals.any { !it.resolved && it.kind !in setOf("source_body", "fact_difference") }
        ) {
            throw ConflictException("현재 본문과 분석 대조 확인이 필요합니다")
        }
    }

    companion object {
        const val MAX_DRAFT_CHARS: Int = 20_000
    }
}
