package kr.easydoc.application.actionguide

import kr.easydoc.application.document.DocumentDraft
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.FakeContentCipher
import kr.easydoc.application.document.StoredSourceText
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideCandidateParser
import kr.easydoc.core.actionguide.ActionGuideCandidateValidator
import kr.easydoc.core.actionguide.ActionGuideItem
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.actionguide.ActionGuideSectionKind
import kr.easydoc.core.actionguide.ActionGuideSectionStatus
import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 행동 안내문을 재방문·교체·확인·출력하는 사용자 흐름의 버전 경계를 검증한다. */
class ActionGuideContentServiceTest {
    private val jobs = FakeActionGuideJobs()
    private val contents = InMemoryContentRepository()
    private val cipher = FakeContentCipher(writeKeyVersion = 1)
    private val documents = SourceOnlyDocuments(cipher)
    private val service =
        ActionGuideContentService(
            enabled = true,
            jobs = jobs,
            contents = contents,
            documents = documents,
            cipher = cipher,
            transaction = DirectTransaction(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    @Test
    fun `재방문은 빈 상태와 현재 안내문과 이전 본문 안내문을 구분한다`() {
        assertThat(service.get(OWNER, CONVERSION).status).isEqualTo(ActionGuideStatus.NOT_GENERATED)
        val saved = save(null, null, 3, grounded())
        assertThat(service.get(OWNER, CONVERSION).guide?.content).isEqualTo(grounded())
        assertThat(service.get(OWNER, CONVERSION).status).isEqualTo(ActionGuideStatus.DRAFT)

        jobs.context = context(4)
        val stale = service.get(OWNER, CONVERSION)
        assertThat(stale.status).isEqualTo(ActionGuideStatus.STALE)
        assertThat(stale.guide?.guideId).isEqualTo(saved.guideId)
        assertThat(stale.guide?.reviewedAt).isNull()
    }

    @Test
    fun `새 후보는 stale 안내문을 CAS로 교체하며 오래된 편집은 거절된다`() {
        val first = save(null, null, 3, grounded())
        jobs.context = context(4)
        assertThatThrownBy { save(null, first.guideRevision, 4, grounded()) }
            .isInstanceOf(ConflictException::class.java)

        val candidateId = UUID.randomUUID()
        contents.candidates[candidateId] = candidate(candidateId, 4, grounded())
        val replaced = save(candidateId, first.guideRevision, 4, grounded())
        assertThat(replaced.guideId).isEqualTo(first.guideId)
        assertThat(replaced.guideRevision).isEqualTo(first.guideRevision + 1)
        assertThat(replaced.basedOnContentRevision).isEqualTo(4)
        assertThat(replaced.status).isEqualTo(ActionGuideStatus.DRAFT)
        assertThatThrownBy { save(candidateId, first.guideRevision, 4, grounded()) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `같은 내용 재저장은 no-op이고 변경 또는 확인은 guide revision을 증가시킨다`() {
        val first = save(null, null, 3, grounded())
        val same = save(null, first.guideRevision, 3, grounded())
        assertThat(same.guideRevision).isEqualTo(1)
        assertThat(contents.writes).isEqualTo(1)

        val reviewed = service.save(OWNER, CONVERSION, null, 3, 1, grounded(), markReviewed = true)
        assertThat(reviewed.guideRevision).isEqualTo(2)
        assertThat(reviewed.status).isEqualTo(ActionGuideStatus.REVIEWED)
        assertThat(reviewed.reviewedBy).isEqualTo(OWNER)
        assertThat(service.save(OWNER, CONVERSION, null, 3, 2, grounded(), markReviewed = true).guideRevision)
            .isEqualTo(2)
        assertThat(contents.writes).isEqualTo(2)
    }

    @Test
    fun `확인이 필요한 섹션은 reviewed 저장을 거절한다`() {
        val content = needsReview()
        ActionGuideCandidateValidator.validate(content, listOf(SOURCE_UNIT))
        val draft = save(null, null, 3, content)
        assertThatThrownBy {
            service.save(OWNER, CONVERSION, null, 3, draft.guideRevision, content, markReviewed = true)
        }.isInstanceOf(ConflictException::class.java)
        assertThat(contents.guide?.status).isEqualTo(ActionGuideStatus.DRAFT.wireName)
    }

    @Test
    fun `새 후보 적용과 내용 수정은 확인 요청이어도 먼저 draft가 된다`() {
        val candidateId = UUID.randomUUID()
        contents.candidates[candidateId] = candidate(candidateId, 3, grounded())
        val applied = service.save(OWNER, CONVERSION, candidateId, 3, null, grounded(), markReviewed = true)
        assertThat(applied.status).isEqualTo(ActionGuideStatus.DRAFT)
        assertThatThrownBy { service.export(OWNER, CONVERSION, applied.guideRevision) }
            .isInstanceOf(ConflictException::class.java)

        val reviewed = service.save(OWNER, CONVERSION, null, 3, applied.guideRevision, grounded(), markReviewed = true)
        assertThat(reviewed.status).isEqualTo(ActionGuideStatus.REVIEWED)
        val changed =
            grounded().let { current ->
                val first = current.sections.first()
                val item = first.items.single().copy(cautions = listOf("추가 확인"))
                current.copy(sections = listOf(first.copy(items = listOf(item))) + current.sections.drop(1))
            }
        val edited = service.save(OWNER, CONVERSION, null, 3, reviewed.guideRevision, changed, markReviewed = true)
        assertThat(edited.status).isEqualTo(ActionGuideStatus.DRAFT)
        assertThat(edited.reviewedAt).isNull()
    }

    @Test
    fun `현재 본문의 확인된 버전만 UTF-8 TXT로 내려받는다`() {
        val draft = save(null, null, 3, grounded())
        assertThatThrownBy { service.export(OWNER, CONVERSION, draft.guideRevision) }
            .isInstanceOf(ConflictException::class.java)
        val reviewed = service.save(OWNER, CONVERSION, null, 3, 1, grounded(), markReviewed = true)
        assertThatThrownBy { service.export(OWNER, CONVERSION, 1) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(String(service.export(OWNER, CONVERSION, reviewed.guideRevision), Charsets.UTF_8))
            .contains("행동 안내문", "[대상]", SOURCE_UNIT)

        jobs.context = context(4)
        assertThatThrownBy { service.export(OWNER, CONVERSION, reviewed.guideRevision) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `완료 작업의 후보 조회는 현재와 stale을 구분한다`() {
        jobs.rows[JOB] = storedJob(status = ActionGuideJobStatus.SUCCEEDED)
        val candidateId = UUID.randomUUID()
        contents.candidates[candidateId] = candidate(candidateId, 3, grounded())
        assertThat(service.candidateForJob(OWNER, CONVERSION, JOB)?.state).isEqualTo("current")
        jobs.context = context(4)
        assertThat(service.candidateForJob(OWNER, CONVERSION, JOB)?.state).isEqualTo("stale")
    }

    private fun save(
        candidateId: UUID?,
        expectedGuideRevision: Long?,
        expectedContentRevision: Long,
        content: ActionGuideCandidate,
    ): ActionGuideView =
        service.save(
            OWNER,
            CONVERSION,
            candidateId,
            expectedContentRevision,
            expectedGuideRevision,
            content,
            markReviewed = false,
        )

    private fun candidate(
        id: UUID,
        basedOn: Long,
        content: ActionGuideCandidate,
    ): StoredActionGuideCandidate =
        StoredActionGuideCandidate(
            id,
            JOB,
            CONVERSION,
            basedOn,
            cipher.encrypt(
                PlainBody(ActionGuideCandidateParser.encode(content)),
                id,
                EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD,
            ),
            NOW,
        )

    private fun context(revision: Long): ActionGuideJobContext = defaultContext().copy(contentRevision = revision)

    private fun grounded(): ActionGuideCandidate =
        ActionGuideCandidate(
            1,
            ActionGuideSectionKind.entries.map { kind ->
                if (kind == ActionGuideSectionKind.ELIGIBILITY) {
                    ActionGuideSection(
                        kind,
                        ActionGuideSectionStatus.AVAILABLE,
                        listOf(
                            ActionGuideItem(
                                SOURCE_UNIT,
                                emptyList(),
                                listOf(ActionGuideSourceAnchor(listOf(0), SOURCE_UNIT)),
                            ),
                        ),
                    )
                } else {
                    ActionGuideSection(kind, ActionGuideSectionStatus.NOT_IN_SOURCE, emptyList())
                }
            },
        )

    private fun needsReview(): ActionGuideCandidate =
        grounded().let { current ->
            val first =
                current.sections.first().copy(
                    status = ActionGuideSectionStatus.NEEDS_REVIEW,
                    items = listOf(ActionGuideItem("담당자 확인", emptyList(), emptyList())),
                )
            current.copy(sections = listOf(first) + current.sections.drop(1))
        }

    private class InMemoryContentRepository : ActionGuideContentRepository {
        var guide: StoredActionGuide? = null
        val candidates = mutableMapOf<UUID, StoredActionGuideCandidate>()
        var writes = 0

        override fun insertCandidate(
            job: StoredActionGuideJob,
            candidate: StoredActionGuideCandidate,
        ): Boolean {
            candidates[candidate.candidateId] = candidate
            return true
        }

        override fun findCandidateForJobOwned(
            ownerId: UUID,
            conversionId: UUID,
            jobId: UUID,
        ): StoredActionGuideCandidate? =
            candidates.values.singleOrNull { ownerId == OWNER && it.conversionId == conversionId && it.jobId == jobId }

        override fun findCandidateOwned(
            ownerId: UUID,
            conversionId: UUID,
            candidateId: UUID,
        ): StoredActionGuideCandidate? =
            candidates[candidateId]?.takeIf { ownerId == OWNER && it.conversionId == conversionId }

        override fun findGuideOwned(
            ownerId: UUID,
            conversionId: UUID,
        ): StoredActionGuide? = guide?.takeIf { ownerId == OWNER && it.conversionId == conversionId }

        override fun saveGuide(
            ownerId: UUID,
            expectedContentRevision: Long,
            expectedGuideRevision: Long?,
            guide: StoredActionGuide,
        ): Boolean {
            val owned = ownerId == OWNER && guide.conversionId == CONVERSION
            val versionsMatch =
                guide.basedOnContentRevision == expectedContentRevision &&
                    this.guide?.guideRevision == expectedGuideRevision
            if (!owned || !versionsMatch) return false
            this.guide = guide
            writes++
            return true
        }
    }

    private class SourceOnlyDocuments(private val cipher: FakeContentCipher) : DocumentRepository {
        override fun findOwnedSource(
            ownerId: UUID,
            documentId: UUID,
        ): StoredSourceText? =
            if (ownerId == OWNER && documentId == DOCUMENT) {
                StoredSourceText(
                    DOCUMENT,
                    SourceFormat.TEXT,
                    SOURCE_UNIT.length,
                    cipher.encrypt(PlainBody(SOURCE_UNIT), DOCUMENT, EncryptedField.DOCUMENT_SOURCE_TEXT),
                    WORKSPACE,
                )
            } else {
                null
            }

        override fun insert(
            ownerId: UUID,
            draft: DocumentDraft,
            sourceText: EncryptedContent,
        ): Document = error("사용하지 않는 경로")

        override fun listOwned(
            ownerId: UUID,
            workspaceId: UUID?,
            limit: Int,
            offset: Int,
        ): List<DocumentListing> = error("사용하지 않는 경로")

        override fun lockSourceText(documentId: UUID): EncryptedContent? = error("사용하지 않는 경로")

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            sourceText: EncryptedContent,
        ): Boolean = error("사용하지 않는 경로")

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = error("사용하지 않는 경로")

        override fun deleteOwned(
            ownerId: UUID,
            documentId: UUID,
        ): Boolean = error("사용하지 않는 경로")
    }

    private companion object {
        const val SOURCE_UNIT = "신청 대상은 19세 이상입니다."
    }
}
