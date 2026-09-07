package kr.easydoc.infrastructure.usage

import kr.easydoc.application.usage.UsageReportRepository
import kr.easydoc.application.usage.UsageReportRow
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 운영 리포트(U3) — 소유자 전체를 **한 SQL**로 `(user_id, workspace_id)` 단위로 묶는다.
 *
 * U2([JdbcUsageReadRepository])는 워크스페이스 하나를 소유 확인 뒤 집계하지만, 이 리포트는
 * 청구서 발송 대상인 **모든 사용자**를 한 번에 훑어야 하고 `workspace_id IS NULL`
 * (워크스페이스가 나중에 삭제된, V14 `SET NULL`) 행도 자기 행으로 포함해야 한다 — U2가
 * 워크스페이스 단위 조회라 다루지 않는 행이다(`UsageReadRepository` KDoc).
 *
 * 문서 수·문자 수·크레딧은 U2와 같은 규칙을 쓴다 — 같은 문서를 대상으로 한 여러 행
 * (재시도·보정)은 `document_id`로 distinct 한 뒤에만 합산한다(`document_totals` CTE).
 * `call_totals`는 U2의 `callTotals`와 같은 식으로 비용 미상(`estimated_cost_usd IS NULL`)을
 * 0으로 섞지 않고 [UsageReportRow.costUnknownCalls]로만 센다.
 *
 * **`owner_email`은 원장 행의 `llm_calls.user_id`로 찾은 이메일이다** — `workspaces.user_id`가
 * 아니다. 워크스페이스는 지금 스키마에서 단일 소유자(`workspaces.user_id`, 생성 시
 * 확정, 이관 기능 없음)이므로 값 자체는 워크스페이스 소유자 이메일과 같다. 그래도
 * `workspaces`를 `w.user_id = c.user_id`로 한 번 더 좁히는 조인은 **일부러 하지 않는다** —
 * 그러면 `workspace_id IS NULL`(워크스페이스가 삭제된) 행이 `w`에 매치할 행이 없어
 * INNER 취급으로 조용히 빠진다. `users`만 조인하고 `workspaces`는 이름 표시용으로만
 * `LEFT JOIN`하는 지금 형태가 옳다.
 *
 * **실패 호출(`outcome = provider_error`, V18)은 `call_totals`의 `FILTER` 로 걸러
 * 토큰·비용에서 빠지고 [UsageReportRow.failedCalls] 로 따로 센다** — U2
 * ([JdbcUsageReadRepository])와 같은 규칙(백로그 「실패 호출 원장 추적」, 2026-09-08).
 * `document_totals` 도 `outcome = 'completed'` 인 행만 문서로 센다. **`call_totals` 는
 * `period_calls` 를 outcome 으로 먼저 거르지 않는다** — 그 기간에 실패 호출만 있고
 * 완료가 하나도 없는 `(user_id, workspace_id)` 쌍도 이 CTE 에 행 하나가 남아야
 * `llm_calls=0, failed_calls>0` 으로 리포트에 나타난다. 미리 걸렀다면 그 쌍은
 * `call_totals` 에서 통째로 빠져 리포트 자체에 나타나지 않았을 것이다 — 이 기능의
 * 목적(실패 호출을 보이게 하는 것)과 정면으로 어긋난다.
 */
class JdbcUsageReportRepository(private val jdbc: JdbcClient) : UsageReportRepository {
    override fun reportRows(
        fromInstant: Instant,
        toExclusiveInstant: Instant,
    ): List<UsageReportRow> =
        jdbc
            .sql(REPORT_SQL)
            .param("from", fromInstant.toOffsetDateTime())
            .param("toExclusive", toExclusiveInstant.toOffsetDateTime())
            .query { rs, _ -> toRow(rs) }
            .list()

    private fun toRow(rs: ResultSet): UsageReportRow =
        UsageReportRow(
            userId = rs.getObject("user_id", UUID::class.java),
            ownerEmail = rs.getString("owner_email"),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            workspaceName = rs.getString("workspace_name"),
            documents = rs.getInt("documents"),
            characters = rs.getLong("characters"),
            credits = rs.getLong("credits"),
            llmCalls = rs.getInt("llm_calls"),
            inputTokens = rs.getLong("input_tokens"),
            outputTokens = rs.getLong("output_tokens"),
            estimatedCostUsd = rs.getBigDecimal("estimated_cost_usd"),
            costUnknownCalls = rs.getInt("cost_unknown_calls"),
            failedCalls = rs.getInt("failed_calls"),
        )

    /** 쓰는 쪽(`JdbcLlmCallLedger`)과 같은 관례 — UTC 오프셋으로 통일한다. */
    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        val REPORT_SQL =
            """
            WITH period_calls AS (
                SELECT user_id, workspace_id, document_id, document_char_count, input_tokens, output_tokens,
                       estimated_cost_usd, outcome
                FROM llm_calls
                WHERE called_at >= :from AND called_at < :toExclusive
            ),
            -- 같은 문서를 대상으로 한 여러 행(재시도·보정)은 document_id로 distinct 한
            -- 뒤에만 문자 수·크레딧을 합한다 — U2(JdbcUsageReadRepository)와 같은 규칙이다.
            -- outcome = 'completed' 만 본다 — 실패 호출만 있던 문서는 변환되지 않았다.
            document_totals AS (
                SELECT user_id, workspace_id,
                       count(*) AS documents,
                       coalesce(sum(document_char_count), 0) AS characters,
                       -- 문서별로 올림한 뒤 더한다 — 합계 문자수를 나중에 한 번만 올리면 다르다.
                       coalesce(sum(ceil(document_char_count::numeric / 1000)), 0)::bigint AS credits
                FROM (
                    SELECT DISTINCT ON (user_id, workspace_id, document_id)
                        user_id, workspace_id, document_id, document_char_count
                    FROM period_calls
                    WHERE outcome = 'completed'
                    ORDER BY user_id, workspace_id, document_id
                ) distinct_documents
                GROUP BY user_id, workspace_id
            ),
            -- outcome 으로 미리 거르지 않는다(위 클래스 KDoc) — 실패 호출만 있던
            -- (user_id, workspace_id) 쌍도 이 CTE 에 행 하나를 남겨야 리포트에 나타난다.
            -- FILTER 로 completed·provider_error 를 나란히 센다.
            call_totals AS (
                SELECT user_id, workspace_id,
                       count(*) FILTER (WHERE outcome = 'completed') AS llm_calls,
                       count(*) FILTER (WHERE outcome = 'provider_error') AS failed_calls,
                       coalesce(sum(input_tokens) FILTER (WHERE outcome = 'completed'), 0) AS input_tokens,
                       coalesce(sum(output_tokens) FILTER (WHERE outcome = 'completed'), 0) AS output_tokens,
                       -- sum()은 NULL을 건너뛰고 행이 전부 NULL이면 NULL을 돌려준다 — 미상 비용을
                       -- 0으로 섞지 않는다(계획 §2 결정 4).
                       sum(estimated_cost_usd) FILTER (WHERE outcome = 'completed') AS estimated_cost_usd,
                       count(*) FILTER (WHERE outcome = 'completed' AND estimated_cost_usd IS NULL)
                           AS cost_unknown_calls
                FROM period_calls
                GROUP BY user_id, workspace_id
            )
            SELECT
                c.user_id,
                u.email AS owner_email,
                c.workspace_id,
                w.name AS workspace_name,
                coalesce(d.documents, 0) AS documents,
                coalesce(d.characters, 0) AS characters,
                coalesce(d.credits, 0) AS credits,
                c.llm_calls,
                c.failed_calls,
                c.input_tokens,
                c.output_tokens,
                c.estimated_cost_usd,
                c.cost_unknown_calls
            FROM call_totals c
            JOIN users u ON u.id = c.user_id
            -- workspace_id가 NULL인 행(삭제된 워크스페이스)은 이름도 NULL로 남는다 — 서비스
            -- 층(UsageReportService)이 그 자리를 고정 안내문으로 바꾼다.
            LEFT JOIN workspaces w ON w.id = c.workspace_id
            LEFT JOIN document_totals d
                ON d.user_id = c.user_id AND d.workspace_id IS NOT DISTINCT FROM c.workspace_id
            ORDER BY u.email, w.name
            """.trimIndent()
    }
}
