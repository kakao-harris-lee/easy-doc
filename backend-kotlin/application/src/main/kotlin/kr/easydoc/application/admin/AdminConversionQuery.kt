package kr.easydoc.application.admin

import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant
import java.util.UUID

/**
 * 관리자 오류·최근 변환 조회 포트 — 어드민 최소 계획
 * `docs/plans/2026-09-07-admin-minimum.md` §2 결정 4. **문서 제목·`failure_code`만
 * 본다 — 변환 본문·프롬프트는 어디에도 없다**(범위 밖 「관리자도 본문을 보지 않는다」).
 *
 * 이 포트를 구현하는 저장소(`JdbcAdminConversionQueryRepository`, `infrastructure`)는
 * `documents`·`conversions`에 소유 술어 없이 닿는다 — 관리자는 의도적으로 워크스페이스를
 * 가로지른다(`OwnershipPredicateGuardTest`의 정확 열거 핀에 주석과 함께 기록돼 있다).
 */
interface AdminConversionQueryRepository {
    /** 그 워크스페이스의 최근 변환 — status·failure_code·문서 제목·생성 시각. 최신순. */
    fun recentForWorkspace(
        workspaceId: UUID,
        limit: Int,
    ): List<AdminConversionRow>

    /** 기간 내 `failed` 변환의 `failure_code`별 건수. */
    fun failureCounts(
        from: Instant,
        toExclusive: Instant,
    ): List<AdminFailureCount>

    /** 기간 내 `failed` 변환 최근 목록 — 본문 없음. 최신순. */
    fun recentFailures(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): List<AdminErrorRow>
}

/** `GET /admin/workspaces/{workspace_id}` 상세의 「최근 변환」 항목 하나. */
data class AdminConversionRow(
    val id: UUID,
    val documentTitle: String,
    val status: ConversionStatus,
    val failureCode: String?,
    val createdAt: Instant,
) {
    /** 문서 제목을 찍지 않는다 — `Workspace`와 같은 규약. */
    override fun toString(): String =
        "AdminConversionRow(id=$id, documentTitle=$CONTENT_MASK, status=$status, " +
            "failureCode=$failureCode, createdAt=$createdAt)"
}

/** `GET /admin/errors`의 `failure_code`별 건수 한 줄. */
data class AdminFailureCount(
    val failureCode: String,
    val count: Long,
)

/** `GET /admin/errors`의 최근 실패 목록 한 줄 — 본문·문서 제목 없음. */
data class AdminErrorRow(
    val id: UUID,
    val workspaceId: UUID,
    val createdAt: Instant,
    val failureCode: String,
)
