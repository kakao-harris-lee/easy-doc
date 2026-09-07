package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminCreditBalance
import kr.easydoc.application.admin.AdminMonthUsage
import kr.easydoc.application.admin.AdminWorkspaceQueryRepository
import kr.easydoc.application.admin.AdminWorkspaceRow
import kr.easydoc.application.admin.AdminWorkspaceSearchResult
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 관리자 워크스페이스 목록·상세 — `workspaces` × `users`, 소유 술어 없이 전체를 훑는다
 * (`AdminWorkspaceQueryRepository` KDoc). `documents`·`conversions`에는 닿지 않으므로
 * `OwnershipPredicateGuardTest`(그 스캐너는 두 표만 본다) 인구조사 대상이 아니다.
 * [creditBalances]·[monthUsage]가 읽는 `workspace_credit_accounts`·`llm_calls`도 같은
 * 이유로 그 스캐너 밖이다.
 */
class JdbcAdminWorkspaceQueryRepository(private val jdbc: JdbcClient) : AdminWorkspaceQueryRepository {
    override fun search(
        query: String?,
        page: Int,
        size: Int,
    ): AdminWorkspaceSearchResult {
        val pattern = query?.let { "%${escapeLike(it)}%" }
        val total =
            jdbc
                .sql(COUNT_SQL)
                .param("pattern", pattern)
                .query { rs, _ -> rs.getInt(1) }
                .single()
        val items =
            jdbc
                .sql(SEARCH_SQL)
                .param("pattern", pattern)
                .param("limit", size)
                // Int 곱은 큰 page·size 조합에서 넘칠 수 있다 — Long 산술로 막는다
                // (계약 `page`의 상한(100000)이 있어도 방어를 코드 층에 둔다).
                .param("offset", (page.toLong() - 1) * size)
                .query { rs, _ -> toRow(rs) }
                .list()
        return AdminWorkspaceSearchResult(items, total)
    }

    override fun find(workspaceId: UUID): AdminWorkspaceRow? =
        jdbc
            .sql(FIND_SQL)
            .param("workspaceId", workspaceId)
            .query { rs, _ -> toRow(rs) }
            .optional()
            .orElse(null)

    /** 페이지의 워크스페이스 id 전부를 한 질의로 묶어 읽는다 — N+1 방지(독립 리뷰 지적). */
    override fun creditBalances(workspaceIds: Collection<UUID>): Map<UUID, AdminCreditBalance> {
        if (workspaceIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(CREDIT_BALANCES_SQL)
            .param("ids", workspaceIds.toList())
            .query { rs, _ ->
                rs.getObject("workspace_id", UUID::class.java) to
                    AdminCreditBalance(balance = rs.getInt("balance"), reserved = rs.getInt("reserved"))
            }.list()
            .toMap()
    }

    /**
     * 같은 이유로 배치다. `llm_calls`에서 유도하는 규칙(문서 단위 distinct, 비용 미상은
     * 합계에서 제외)은 `JdbcUsageReadRepository`와 같다 — 목록 요약이 쓰는 세 값만 낸다.
     */
    override fun monthUsage(
        workspaceIds: Collection<UUID>,
        from: Instant,
        toExclusive: Instant,
    ): Map<UUID, AdminMonthUsage> {
        if (workspaceIds.isEmpty()) return emptyMap()
        val documents = documentTotalsByWorkspace(workspaceIds, from, toExclusive)
        val costs = costTotalsByWorkspace(workspaceIds, from, toExclusive)
        return (documents.keys + costs.keys).associateWith { id ->
            val totals = documents[id]
            AdminMonthUsage(
                documents = totals?.documents ?: 0,
                credits = totals?.credits ?: 0,
                estimatedCostUsd = costs[id],
            )
        }
    }

    private fun documentTotalsByWorkspace(
        workspaceIds: Collection<UUID>,
        from: Instant,
        toExclusive: Instant,
    ): Map<UUID, DocumentTotals> =
        jdbc
            .sql(DOCUMENT_TOTALS_BY_WORKSPACE_SQL)
            .param("ids", workspaceIds.toList())
            .param("from", from.toOffsetDateTime())
            .param("toExclusive", toExclusive.toOffsetDateTime())
            .query { rs, _ ->
                rs.getObject("workspace_id", UUID::class.java) to
                    DocumentTotals(documents = rs.getInt("documents"), credits = rs.getLong("credits"))
            }.list()
            .toMap()

    private fun costTotalsByWorkspace(
        workspaceIds: Collection<UUID>,
        from: Instant,
        toExclusive: Instant,
    ): Map<UUID, BigDecimal?> =
        jdbc
            .sql(COST_TOTALS_BY_WORKSPACE_SQL)
            .param("ids", workspaceIds.toList())
            .param("from", from.toOffsetDateTime())
            .param("toExclusive", toExclusive.toOffsetDateTime())
            .query { rs, _ ->
                rs.getObject("workspace_id", UUID::class.java) to rs.getBigDecimal("estimated_cost_usd")
            }.list()
            .toMap()

    private fun toRow(rs: ResultSet): AdminWorkspaceRow =
        AdminWorkspaceRow(
            workspaceId = rs.getObject("id", UUID::class.java),
            ownerId = rs.getObject("user_id", UUID::class.java),
            ownerEmail = rs.getString("email"),
            name = rs.getString("name"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    /** 읽는 쪽 관례 — `JdbcUsageReadRepository`와 짝을 맞춘다(UTC 오프셋으로 통일). */
    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    /**
     * `ILIKE` 패턴의 특수문자(`\`·`%`·`_`)를 리터럴로 이스케이프한다 — 검색어에 `%`나
     * `_`가 들어 있으면 그 자체를 와일드카드로 오해해 의도보다 넓게 매치하는 것을 막는다
     * (독립 리뷰 지적, `q=%`가 「전체」로 새지 않아야 한다).
     */
    private fun escapeLike(raw: String): String = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private data class DocumentTotals(
        val documents: Int,
        val credits: Long,
    )

    private companion object {
        const val BASE_FROM =
            """
            FROM workspaces w
            JOIN users u ON u.id = w.user_id
            WHERE :pattern::text IS NULL
               OR w.name ILIKE :pattern ESCAPE '\'
               OR u.email ILIKE :pattern ESCAPE '\'
            """

        val COUNT_SQL = "SELECT count(*) $BASE_FROM".trimIndent()

        val SEARCH_SQL =
            """
            SELECT w.id, w.user_id, u.email, w.name, w.created_at
            $BASE_FROM
            ORDER BY w.created_at DESC, w.id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent()

        val FIND_SQL =
            """
            SELECT w.id, w.user_id, u.email, w.name, w.created_at
            FROM workspaces w
            JOIN users u ON u.id = w.user_id
            WHERE w.id = :workspaceId
            """.trimIndent()

        val CREDIT_BALANCES_SQL =
            """
            SELECT workspace_id, balance, reserved
            FROM workspace_credit_accounts
            WHERE workspace_id IN (:ids)
            """.trimIndent()

        /**
         * `JdbcUsageReadRepository.documentTotalsFromCalls`와 같은 규칙 — `document_id`로
         * distinct 한 뒤에만 문서 단위로 더한다. 워크스페이스별로 나누지 않고 한 번에
         * `GROUP BY workspace_id`로 묶는다.
         */
        val DOCUMENT_TOTALS_BY_WORKSPACE_SQL =
            """
            SELECT
                workspace_id,
                count(*) AS documents,
                coalesce(sum(ceil(document_char_count::numeric / 1000)), 0)::bigint AS credits
            FROM (
                SELECT DISTINCT ON (document_id) document_id, workspace_id, document_char_count
                FROM llm_calls
                WHERE workspace_id IN (:ids) AND called_at >= :from AND called_at < :toExclusive
                ORDER BY document_id
            ) AS distinct_documents
            GROUP BY workspace_id
            """.trimIndent()

        /** `sum()`은 NULL을 건너뛴다 — 비용 미상 호출이 섞여도 0으로 새지 않는다(계획 §2 결정 4). */
        val COST_TOTALS_BY_WORKSPACE_SQL =
            """
            SELECT workspace_id, sum(estimated_cost_usd) AS estimated_cost_usd
            FROM llm_calls
            WHERE workspace_id IN (:ids) AND called_at >= :from AND called_at < :toExclusive
            GROUP BY workspace_id
            """.trimIndent()
    }
}
