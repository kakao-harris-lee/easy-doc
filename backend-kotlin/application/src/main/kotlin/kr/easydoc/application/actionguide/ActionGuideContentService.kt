package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.CONTENT_REVISION_CONFLICT_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_DONE_MESSAGE
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideCandidateParser
import kr.easydoc.core.actionguide.ActionGuideCandidateValidator
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.segment.splitUnits
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class ActionGuideStatus(val wireName: String) {
    NOT_GENERATED("not_generated"),
    DRAFT("draft"),
    REVIEWED("reviewed"),
    STALE("stale"),
}

data class ActionGuideCandidateView(
    val candidateId: UUID,
    val basedOnContentRevision: Long,
    val state: String,
    val content: ActionGuideCandidate,
)

data class ActionGuideView(
    val guideId: UUID,
    val basedOnContentRevision: Long,
    val guideRevision: Long,
    val status: ActionGuideStatus,
    val content: ActionGuideCandidate,
    val reviewedAt: Instant?,
    val reviewedBy: UUID?,
)

data class ActionGuideResourceView(
    val status: ActionGuideStatus,
    val guide: ActionGuideView?,
    val activeJobId: UUID?,
    val latestJobId: UUID?,
)

/** 암호화된 후보와 담당자 수정본을 현재 본문 버전에만 연결한다. */
@Suppress("LongParameterList", "TooManyFunctions")
class ActionGuideContentService(
    private val enabled: Boolean,
    private val jobs: ActionGuideJobRepository,
    private val contents: ActionGuideContentRepository,
    private val documents: DocumentRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun get(
        ownerId: UUID,
        conversionId: UUID,
    ): ActionGuideResourceView {
        requireEnabled()
        return transaction.inTransaction {
            val context = ownedContext(ownerId, conversionId)
            val stored = contents.findGuideOwned(ownerId, conversionId)
            val active = jobs.findActiveOwned(ownerId, conversionId)
            val latest = active ?: jobs.findLatestOwned(ownerId, conversionId)
            val guide = stored?.let { openGuide(it, context.contentRevision) }
            ActionGuideResourceView(
                guide?.status ?: ActionGuideStatus.NOT_GENERATED,
                guide,
                active?.jobId,
                latest?.jobId,
            )
        }
    }

    fun candidateForJob(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): ActionGuideCandidateView? {
        requireEnabled()
        return transaction.inTransaction {
            val context = ownedContext(ownerId, conversionId)
            val job =
                jobs.findOwned(ownerId, conversionId, jobId)
                    ?: throw NotFoundException(ACTION_GUIDE_JOB_NOT_FOUND_MESSAGE)
            val stored = contents.findCandidateForJobOwned(ownerId, conversionId, jobId)
            if (stored == null) return@inTransaction null
            check(job.status == kr.easydoc.core.actionguide.ActionGuideJobStatus.SUCCEEDED) {
                "완료되지 않은 행동 안내 작업에 후보가 있습니다"
            }
            ActionGuideCandidateView(
                stored.candidateId,
                stored.basedOnContentRevision,
                if (stored.basedOnContentRevision == context.contentRevision) "current" else "stale",
                openCandidate(stored),
            )
        }
    }

    @Suppress("ThrowsCount", "LongMethod", "CyclomaticComplexMethod")
    fun save(
        ownerId: UUID,
        conversionId: UUID,
        candidateId: UUID?,
        expectedContentRevision: Long,
        expectedGuideRevision: Long?,
        content: ActionGuideCandidate,
        markReviewed: Boolean,
    ): ActionGuideView {
        requireEnabled()
        if (expectedContentRevision !in 1..MAX_JS_SAFE_INTEGER ||
            (expectedGuideRevision != null && expectedGuideRevision !in 0..MAX_JS_SAFE_INTEGER)
        ) {
            throw InvalidInputException("행동 안내문 버전이 올바르지 않습니다")
        }
        return transaction.inTransaction {
            val context = ownedContext(ownerId, conversionId)
            if (!context.completed) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
            if (context.contentRevision != expectedContentRevision) {
                throw ConflictException(CONTENT_REVISION_CONFLICT_MESSAGE)
            }
            val previous = contents.findGuideOwned(ownerId, conversionId)
            if (previous?.guideRevision != expectedGuideRevision) {
                throw ConflictException(GUIDE_REVISION_CONFLICT_MESSAGE)
            }
            if (previous != null && previous.basedOnContentRevision != context.contentRevision && candidateId == null) {
                throw ConflictException("이전 본문으로 만든 행동 안내문입니다. 다시 만들어 주세요")
            }
            if (candidateId != null) {
                val candidate =
                    contents.findCandidateOwned(ownerId, conversionId, candidateId)
                        ?: throw NotFoundException("행동 안내 후보를 찾을 수 없습니다")
                if (candidate.basedOnContentRevision != context.contentRevision) {
                    throw ConflictException("이전 본문으로 만든 행동 안내 후보입니다")
                }
            }
            val source = sourceUnits(ownerId, context.documentId)
            ActionGuideCandidateValidator.validate(content, source)
            val encoded = ActionGuideCandidateParser.encode(content)
            val previousContent =
                previous?.let { stored ->
                    cipher.decrypt(stored.payload, stored.guideId, EncryptedField.ACTION_GUIDE_PAYLOAD).value
                }
            val sameContent = previousContent == encoded
            val canMarkReviewed =
                markReviewed && candidateId == null && previous != null &&
                    previous.basedOnContentRevision == context.contentRevision && sameContent
            if (canMarkReviewed && content.sections.any { it.status == ActionGuideSectionStatus.NEEDS_REVIEW }) {
                throw ConflictException("확인이 필요한 항목을 먼저 원문과 대조해 주세요")
            }
            val status = if (canMarkReviewed) ActionGuideStatus.REVIEWED else ActionGuideStatus.DRAFT
            if (previous != null) {
                val sameVersionAndStatus =
                    previous.basedOnContentRevision == context.contentRevision && previous.status == status.wireName
                if (sameVersionAndStatus && sameContent) {
                    return@inTransaction openGuide(previous, context.contentRevision)
                }
            }
            val now = clock.instant()
            val guideId = previous?.guideId ?: UUID.randomUUID()
            val updated =
                StoredActionGuide(
                    guideId = guideId,
                    conversionId = conversionId,
                    basedOnContentRevision = context.contentRevision,
                    guideRevision = (previous?.guideRevision ?: 0) + 1,
                    status = status.wireName,
                    payload = cipher.encrypt(PlainBody(encoded), guideId, EncryptedField.ACTION_GUIDE_PAYLOAD),
                    reviewedAt = if (canMarkReviewed) now else null,
                    reviewedBy = if (canMarkReviewed) ownerId else null,
                    createdAt = previous?.createdAt ?: now,
                    updatedAt = now,
                )
            if (!contents.saveGuide(ownerId, expectedContentRevision, expectedGuideRevision, updated)) {
                throw ConflictException(GUIDE_REVISION_CONFLICT_MESSAGE)
            }
            openGuide(updated, context.contentRevision)
        }
    }

    @Suppress("ThrowsCount")
    fun export(
        ownerId: UUID,
        conversionId: UUID,
        guideRevision: Long,
    ): ByteArray {
        requireEnabled()
        if (guideRevision !in 1..MAX_JS_SAFE_INTEGER) {
            throw InvalidInputException("행동 안내문 버전이 올바르지 않습니다")
        }
        return transaction.inTransaction {
            val context = ownedContext(ownerId, conversionId)
            val stored =
                contents.findGuideOwned(ownerId, conversionId)
                    ?: throw ConflictException("저장된 행동 안내문이 없습니다")
            if (stored.guideRevision != guideRevision || stored.basedOnContentRevision != context.contentRevision ||
                stored.status != ActionGuideStatus.REVIEWED.wireName
            ) {
                throw ConflictException("현재 본문의 확인된 행동 안내문만 내려받을 수 있습니다")
            }
            val guide = openGuide(stored, context.contentRevision)
            renderText(guide.content).toByteArray(Charsets.UTF_8)
        }
    }

    private fun ownedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): ActionGuideJobContext =
        jobs.lockOwnedContext(ownerId, conversionId)
            ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)

    private fun sourceUnits(
        ownerId: UUID,
        documentId: UUID,
    ): List<String> {
        val source =
            documents.findOwnedSource(ownerId, documentId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        return splitUnits(cipher.decrypt(source.sourceText, documentId, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
    }

    private fun openCandidate(stored: StoredActionGuideCandidate): ActionGuideCandidate =
        ActionGuideCandidateParser.decode(
            cipher.decrypt(stored.payload, stored.candidateId, EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD).value,
        )

    private fun openGuide(
        stored: StoredActionGuide,
        contentRevision: Long,
    ): ActionGuideView {
        val status =
            if (stored.basedOnContentRevision != contentRevision) {
                ActionGuideStatus.STALE
            } else {
                ActionGuideStatus.entries.singleOrNull { it.wireName == stored.status }
                    ?: throw StorageException("행동 안내문 상태가 올바르지 않습니다")
            }
        return ActionGuideView(
            stored.guideId,
            stored.basedOnContentRevision,
            stored.guideRevision,
            status,
            ActionGuideCandidateParser.decode(
                cipher.decrypt(stored.payload, stored.guideId, EncryptedField.ACTION_GUIDE_PAYLOAD).value,
            ),
            if (status == ActionGuideStatus.REVIEWED) stored.reviewedAt else null,
            if (status == ActionGuideStatus.REVIEWED) stored.reviewedBy else null,
        )
    }

    private fun renderText(content: ActionGuideCandidate): String =
        buildString {
            appendLine("행동 안내문")
            content.sections.forEach { section ->
                appendLine()
                appendLine("[${heading(section.kind)}]")
                if (section.status == ActionGuideSectionStatus.NOT_IN_SOURCE) {
                    appendLine("원문에 안내가 없습니다.")
                } else {
                    section.items.forEachIndexed { index, item ->
                        appendLine("${index + 1}. ${item.text}")
                        item.cautions.forEach { appendLine("   주의: $it") }
                    }
                }
            }
        }

    private fun heading(kind: ActionGuideSectionKind): String =
        when (kind) {
            ActionGuideSectionKind.ELIGIBILITY -> "대상"
            ActionGuideSectionKind.BENEFITS -> "받는 도움"
            ActionGuideSectionKind.DOCUMENTS -> "준비 서류"
            ActionGuideSectionKind.STEPS -> "할 일"
            ActionGuideSectionKind.EXCEPTIONS -> "예외와 주의"
            ActionGuideSectionKind.CONTACT -> "문의처"
        }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }

    private companion object {
        const val MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
