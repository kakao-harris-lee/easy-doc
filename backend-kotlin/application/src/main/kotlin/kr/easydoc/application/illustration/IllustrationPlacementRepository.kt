package kr.easydoc.application.illustration

import kr.easydoc.core.crypto.EncryptedContent
import java.time.Instant
import java.util.UUID

/**
 * 저장된 그림 배치 한 행 — 변환 한 건에 현재 집합 하나만 있다(이력 없음). [id] 는 회전이
 * 잠그고 재봉인하는 행 식별자이자 봉인 AAD 결속 대상이다 — `conversion_id` 는 UNIQUE라
 * 동등하게 안정적이지만, `review_assessments`(행 여러 개, id로 결속) 와 같은 결속 축을
 * 그대로 따른다.
 */
data class StoredIllustrationPlacements(
    val id: UUID,
    val conversionId: UUID,
    val contentRevision: Long,
    val payload: EncryptedContent,
    val updatedAt: Instant,
) {
    /** 식별자·변환·버전만 남긴다 — 암호문은 [EncryptedContent.toString] 이 이미 가린다. */
    override fun toString(): String =
        "StoredIllustrationPlacements($id, conversion=$conversionId, revision=$contentRevision)"
}

/** `illustration_placements` 저장소. 구현은 `infrastructure` 가 진다. */
interface IllustrationPlacementRepository {
    fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationPlacements?

    /**
     * 현재 집합을 통째로 교체한다(upsert). [id] 는 행이 아직 없을 때 새로 쓸 식별자다 — 이미
     * 있으면 그 행의 기존 `id` 가 유지된다(봉인 AAD가 `id`에 결속되므로, 호출자는 기존 행이
     * 있으면 [findOwned] 로 읽은 `id` 를 그대로 다시 넘겨야 한다).
     */
    @Suppress("LongParameterList")
    fun replaceOwned(
        ownerId: UUID,
        conversionId: UUID,
        id: UUID,
        contentRevision: Long,
        payload: EncryptedContent,
    ): Boolean

    /** 배치를 지운다(빈 목록으로 저장한 경우). 지웠으면 `true`. */
    fun deleteOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): Boolean

    /** 회전 대상 행을 읽고 **그 행을 잠근다**. 없으면 `null`. */
    fun lockEnvelope(id: UUID): StoredIllustrationPlacements?

    /** 암호문과 봉투 두 값을 **한 UPDATE 로** 바꾼다. 갱신됐으면 `true`. */
    fun rewriteEnvelope(
        expected: StoredIllustrationPlacements,
        payload: EncryptedContent,
    ): Boolean

    /** 키 회전 배치의 후보. [kr.easydoc.application.document.DocumentRepository.idsOlderThan] 과 같은 커서 규약이다. */
    fun idsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID>
}
