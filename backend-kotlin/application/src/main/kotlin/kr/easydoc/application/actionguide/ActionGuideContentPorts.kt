package kr.easydoc.application.actionguide

import kr.easydoc.core.crypto.EncryptedContent
import java.time.Instant
import java.util.UUID

/** 후보 본문은 job당 한 번만 저장하며, 평문은 이 경계를 지나 DB에 남지 않는다. */
data class StoredActionGuideCandidate(
    val candidateId: UUID,
    val jobId: UUID,
    val conversionId: UUID,
    val basedOnContentRevision: Long,
    val payload: EncryptedContent,
    val createdAt: Instant,
)

/** 확인 상태와 버전은 평문 메타이며, 섹션·인용은 암호문 payload에만 둔다. */
data class StoredActionGuide(
    val guideId: UUID,
    val conversionId: UUID,
    val basedOnContentRevision: Long,
    val guideRevision: Long,
    val status: String,
    val payload: EncryptedContent,
    val reviewedAt: Instant?,
    val reviewedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 모든 사용자 조회·저장은 소유권과 문서 보존기간을 같은 SQL에서 확인한다. */
interface ActionGuideContentRepository {
    /** 잠긴 실행 작업에서만 후보를 삽입한다. 본문 버전이 바뀌었으면 false. */
    fun insertCandidate(
        job: StoredActionGuideJob,
        candidate: StoredActionGuideCandidate,
    ): Boolean

    fun findCandidateForJobOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredActionGuideCandidate?

    fun findCandidateOwned(
        ownerId: UUID,
        conversionId: UUID,
        candidateId: UUID,
    ): StoredActionGuideCandidate?

    fun findGuideOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuide?

    /** expectedGuideRevision=null이면 최초 INSERT, 아니면 정확한 버전만 UPDATE한다. */
    fun saveGuide(
        ownerId: UUID,
        expectedContentRevision: Long,
        expectedGuideRevision: Long?,
        guide: StoredActionGuide,
    ): Boolean
}
