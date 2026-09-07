package kr.easydoc.application.admin

import kr.easydoc.core.privacy.CONTENT_MASK
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 관리자 워크스페이스 목록·상세 조회 포트 — 어드민 최소 계획
 * `docs/plans/2026-09-07-admin-minimum.md` §2 결정 4. **소유자 확인 없이 전체를 훑는다**
 * (관리자 전용 — `OwnershipPredicateGuardTest`의 소유 술어 축과 무관, 그 스캐너는
 * `documents`·`conversions`만 본다).
 *
 * [creditBalances]·[monthUsage]는 **목록** 전용 배치 조회다 — 독립 리뷰 지적(페이지당
 * N+1 질의)에 따라, `AdminQueryService.listWorkspaces`가 페이지의 워크스페이스 id
 * 집합을 한 번에 넘겨 한 질의로 받는다. 상세(`workspaceDetail`)는 이 배치 경로를 쓰지
 * 않는다 — 그쪽은 이미 워크스페이스 하나만 다루므로 기존 서비스(`CreditAccountService`·
 * `UsageQueryService`)를 그대로 재사용하는 편이 낫고, 없는 계정은 그 서비스가 이미
 * 예외로 던진다.
 */
interface AdminWorkspaceQueryRepository {
    /**
     * [query]는 이름·소유자 이메일 부분 일치(대소문자 무시)다. `null`이면 전체.
     * [page]는 1부터, [size]는 1~100.
     */
    fun search(
        query: String?,
        page: Int,
        size: Int,
    ): AdminWorkspaceSearchResult

    /** 없으면 `null`. */
    fun find(workspaceId: UUID): AdminWorkspaceRow?

    /**
     * [workspaceIds]의 크레딧 잔액을 한 질의로 묶어 읽는다. 계정 행이 없는 워크스페이스는
     * 결과 맵에 그 id가 없다 — 호출자가 0/0/0으로 채운다(목록은 관용, 상세는 여전히 엄격).
     */
    fun creditBalances(workspaceIds: Collection<UUID>): Map<UUID, AdminCreditBalance>

    /**
     * [workspaceIds]의 `[from, toExclusive)` 사용량을 한 번에 묶어 읽는다 — `llm_calls`
     * 원장에서 유도하는 규칙은 `JdbcUsageReadRepository`와 같다(문서 단위 distinct,
     * 비용 미상은 합계에서 제외). 그 기간에 호출이 없던 워크스페이스는 결과 맵에 없다.
     */
    fun monthUsage(
        workspaceIds: Collection<UUID>,
        from: Instant,
        toExclusive: Instant,
    ): Map<UUID, AdminMonthUsage>
}

/** [AdminWorkspaceQueryRepository.creditBalances] 결과 한 건. */
data class AdminCreditBalance(
    val balance: Int,
    val reserved: Int,
)

/**
 * [AdminWorkspaceQueryRepository.monthUsage] 결과 한 건 — 목록 요약이 쓰는 세 값뿐이다
 * (`AdminWorkspaceSummary.monthDocuments`·`monthCredits`·`monthCostUsd`). 상세는 여전히
 * `UsageQueryService`의 전체 `WorkspaceUsage`를 쓴다.
 */
data class AdminMonthUsage(
    val documents: Int,
    val credits: Long,
    val estimatedCostUsd: BigDecimal?,
)

/** 워크스페이스 한 건 — 목록·상세가 공유하는 기본 정보. */
data class AdminWorkspaceRow(
    val workspaceId: UUID,
    val ownerId: UUID,
    val ownerEmail: String,
    val name: String,
    val createdAt: Instant,
) {
    /** 이름·이메일을 찍지 않는다 — `Workspace`·`User`와 같은 규약. */
    override fun toString(): String =
        "AdminWorkspaceRow(workspaceId=$workspaceId, ownerId=$ownerId, ownerEmail=$CONTENT_MASK, " +
            "name=$CONTENT_MASK, createdAt=$createdAt)"
}

/** [AdminWorkspaceQueryRepository.search] 결과 — 페이지 항목 + 전체 건수(다음 페이지 유무 판정용). */
data class AdminWorkspaceSearchResult(
    val items: List<AdminWorkspaceRow>,
    val total: Int,
)
