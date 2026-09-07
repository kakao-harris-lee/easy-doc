package kr.easydoc.infrastructure.usage

import kr.easydoc.application.usage.PurposeUsage
import kr.easydoc.application.usage.UsageReadRepository
import kr.easydoc.application.usage.WorkspaceUsage
import kr.easydoc.core.llm.LlmCallPurpose
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 워크스페이스·기간별 사용량 집계(U2) — `llm_calls`(V14) **한 표만** 읽는다.
 *
 * **`documents` 표를 참조하지 않는다(2026-09-08 리뷰 정정).** 처음 설계는 [documents]·
 * [characters]·[credits]를 `documents.created_at` 기준으로 그 표에서 직접 셌다 — 그런데
 * `documents` 행은 보존 만료·사용자 삭제로 지워지고, 그러면 지난달 이미 청구했어야 할
 * 문서의 문자 수·크레딧이 이번 조회에서 사라진다. U1이 `llm_calls`를 append-only
 * 원장으로 만든 이유가 정확히 이 문제를 막는 것이었는데, 이 저장소가 `documents`를
 * 그대로 계속 읽으면 그 목적이 무의미해진다(`LlmCallEntry.documentCharCount` KDoc,
 * V14 머리주석 3차 정정).
 *
 * 그래서 [documents]·[characters]·[credits]도 `llm_calls.document_char_count`(그 호출이
 * 속한 문서의 `documents.char_count` 스냅샷)에서 유도한다 — **그 기간에 완료된 LLM 호출이
 * 하나라도 있던 문서만 센다.** 등록만 되고 한 번도 변환되지 않은 문서는 비용도 크레딧도
 * 없으므로 셀 이유가 없다(호출이 없으면 이 표에 그 문서의 행 자체가 없다). 같은 문서를
 * 대상으로 하는 여러 행(변환·보정·재시도)이 같은 `document_char_count` 값을 반복해
 * 담으므로, `document_id`로 distinct 한 뒤에만 합한다 — distinct 하지 않으면 재시도 한
 * 번마다 그 문서의 문자 수가 다시 더해진다.
 *
 * **소유 확인이 먼저다.** [aggregate]는 워크스페이스 존재·소유 여부를 별도 질의로 확인해
 * `null`을 돌려줄지 정한 뒤에만 나머지 집계를 돈다 — 그래야 "워크스페이스가 없다"와
 * "그 기간에 값이 0이다"를 구분할 수 있다(둘 다 집계 질의만으로는 0행으로 보인다).
 *
 * **실패 호출(`outcome = provider_error`, V18)은 문서·문자·크레딧·토큰·비용 집계에서
 * 빠진다** — 실제로 쓴 자원이 없다(토큰 0, 비용 미상). `documents`·`callTotals`·
 * `callTotalsByPurpose` 는 전부 `FILTER (WHERE outcome = 'completed')` 로 그 집계를
 * 좁힌다. 대신 [WorkspaceUsage.failedCalls]·[PurposeUsage.failedCalls] 가 실패 건수를
 * 따로 낸다 — `FILTER` 를 성공 집계와 나란히 걸어 실패만 있는 기간·목적도 (0, 실패
 * 건수)로 드러나게 한다(백로그 「실패 호출 원장 추적」, 2026-09-08).
 */
class JdbcUsageReadRepository(private val jdbc: JdbcClient) : UsageReadRepository {
    override fun aggregate(
        ownerId: UUID,
        workspaceId: UUID,
        from: Instant,
        toExclusive: Instant,
    ): WorkspaceUsage? {
        ownedWorkspaceName(ownerId, workspaceId) ?: return null
        val documents = documentTotalsFromCalls(ownerId, workspaceId, from, toExclusive)
        val calls = callTotals(ownerId, workspaceId, from, toExclusive)
        val byPurpose = callTotalsByPurpose(ownerId, workspaceId, from, toExclusive)
        return WorkspaceUsage(
            documents = documents.documents,
            characters = documents.characters,
            credits = documents.credits,
            llmCalls = calls.llmCalls,
            inputTokens = calls.inputTokens,
            outputTokens = calls.outputTokens,
            estimatedCostUsd = calls.estimatedCostUsd,
            costUnknownCalls = calls.costUnknownCalls,
            failedCalls = calls.failedCalls,
            byPurpose = byPurpose,
        )
    }

    /** [ownerId] 소유가 맞으면 그 워크스페이스 이름, 아니면 `null` — 존재를 숨기는 404 판정. */
    private fun ownedWorkspaceName(
        ownerId: UUID,
        workspaceId: UUID,
    ): String? =
        jdbc
            .sql("SELECT name FROM workspaces WHERE id = :workspaceId AND user_id = :ownerId")
            .param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .query { rs, _ -> rs.getString("name") }
            .optional()
            .orElse(null)

    /**
     * 그 기간에 완료된 호출이 있던 **문서 단위**로 집계한다 — `document_id`로 distinct 한
     * 뒤에만 문자 수·크레딧을 더한다. `DISTINCT ON (document_id)`은 같은 문서의 여러 행 중
     * 하나만 남기는데, `document_char_count`는 같은 문서의 모든 행에서 값이 같으므로(등록
     * 시 확정돼 바뀌지 않는다) 어느 행이 남든 값은 같다.
     */
    private fun documentTotalsFromCalls(
        ownerId: UUID,
        workspaceId: UUID,
        from: Instant,
        toExclusive: Instant,
    ): DocumentTotals =
        jdbc
            .sql(
                """
                SELECT
                    count(*) AS documents,
                    coalesce(sum(document_char_count), 0) AS characters,
                    -- 문서별로 올림한 뒤 더한다 — 합계 문자수를 나중에 한 번만 올리면 다르다.
                    coalesce(sum(ceil(document_char_count::numeric / 1000)), 0)::bigint AS credits
                FROM (
                    -- outcome = 'completed' 만 본다 — 실패 호출(provider_error)만 있던
                    -- 문서는 실제로 변환되지 않았으므로 문서·문자·크레딧에 넣지 않는다.
                    SELECT DISTINCT ON (document_id) document_id, document_char_count
                    FROM llm_calls
                    WHERE workspace_id = :workspaceId AND user_id = :ownerId
                      AND called_at >= :from AND called_at < :toExclusive
                      AND outcome = 'completed'
                    ORDER BY document_id
                ) AS distinct_documents
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("from", from.toOffsetDateTime())
            .param("toExclusive", toExclusive.toOffsetDateTime())
            .query { rs, _ ->
                DocumentTotals(
                    documents = rs.getInt("documents"),
                    characters = rs.getLong("characters"),
                    credits = rs.getLong("credits"),
                )
            }.single()

    /**
     * `outcome = 'completed'` 인 행만 토큰·비용·호출 수에 더한다. **실패 건수는 같은
     * 질의에서 `FILTER` 로 함께 센다** — 별도 질의로 나누면 그 기간에 실패만 있고
     * 완료가 하나도 없는 경우를 놓치지 않는다는 확인이 추가로 필요해진다(V18).
     */
    private fun callTotals(
        ownerId: UUID,
        workspaceId: UUID,
        from: Instant,
        toExclusive: Instant,
    ): CallTotals =
        jdbc
            .sql(
                """
                SELECT
                    count(*) FILTER (WHERE outcome = 'completed') AS llm_calls,
                    coalesce(sum(input_tokens) FILTER (WHERE outcome = 'completed'), 0) AS input_tokens,
                    coalesce(sum(output_tokens) FILTER (WHERE outcome = 'completed'), 0) AS output_tokens,
                    -- sum()은 NULL을 건너뛰고, 행이 전부 NULL이면 NULL을 돌려준다 — 미상 비용을
                    -- 0으로 섞지 않는다는 규칙(계획 §2 결정 4)과 그대로 맞아떨어진다.
                    sum(estimated_cost_usd) FILTER (WHERE outcome = 'completed') AS estimated_cost_usd,
                    count(*) FILTER (WHERE outcome = 'completed' AND estimated_cost_usd IS NULL)
                        AS cost_unknown_calls,
                    count(*) FILTER (WHERE outcome = 'provider_error') AS failed_calls
                FROM llm_calls
                WHERE workspace_id = :workspaceId AND user_id = :ownerId
                  AND called_at >= :from AND called_at < :toExclusive
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("from", from.toOffsetDateTime())
            .param("toExclusive", toExclusive.toOffsetDateTime())
            .query { rs, _ ->
                CallTotals(
                    llmCalls = rs.getInt("llm_calls"),
                    inputTokens = rs.getLong("input_tokens"),
                    outputTokens = rs.getLong("output_tokens"),
                    estimatedCostUsd = rs.getBigDecimal("estimated_cost_usd"),
                    costUnknownCalls = rs.getInt("cost_unknown_calls"),
                    failedCalls = rs.getInt("failed_calls"),
                )
            }.single()

    /**
     * 목적별 소계 — `GROUP BY purpose` 는 outcome 을 가리지 않는다. 그래야 그 기간에
     * 실패 호출만 있던 목적(예: 보정이 매번 실패)도 (llm_calls=0, failed_calls>0) 행으로
     * 나타난다 — outcome 으로 먼저 걸렀다면 그 목적 자체가 목록에서 사라졌을 것이다.
     */
    private fun callTotalsByPurpose(
        ownerId: UUID,
        workspaceId: UUID,
        from: Instant,
        toExclusive: Instant,
    ): List<PurposeUsage> =
        jdbc
            .sql(
                """
                SELECT
                    purpose,
                    count(*) FILTER (WHERE outcome = 'completed') AS llm_calls,
                    coalesce(sum(input_tokens) FILTER (WHERE outcome = 'completed'), 0) AS input_tokens,
                    coalesce(sum(output_tokens) FILTER (WHERE outcome = 'completed'), 0) AS output_tokens,
                    sum(estimated_cost_usd) FILTER (WHERE outcome = 'completed') AS estimated_cost_usd,
                    count(*) FILTER (WHERE outcome = 'provider_error') AS failed_calls
                FROM llm_calls
                WHERE workspace_id = :workspaceId AND user_id = :ownerId
                  AND called_at >= :from AND called_at < :toExclusive
                GROUP BY purpose
                ORDER BY purpose
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("ownerId", ownerId)
            .param("from", from.toOffsetDateTime())
            .param("toExclusive", toExclusive.toOffsetDateTime())
            .query { rs, _ -> toPurposeUsage(rs) }
            .list()

    private fun toPurposeUsage(rs: ResultSet): PurposeUsage =
        PurposeUsage(
            purpose = purposeOf(rs.getString("purpose")),
            llmCalls = rs.getInt("llm_calls"),
            inputTokens = rs.getLong("input_tokens"),
            outputTokens = rs.getLong("output_tokens"),
            estimatedCostUsd = rs.getBigDecimal("estimated_cost_usd"),
            failedCalls = rs.getInt("failed_calls"),
        )

    private fun purposeOf(wireName: String): LlmCallPurpose =
        LlmCallPurpose.entries.firstOrNull { it.wireName == wireName }
            ?: error(
                "llm_calls.purpose '$wireName' 이 LlmCallPurpose 안에 없다 — " +
                    "V14 CHECK 제약과 이 enum이 서로 어긋났다",
            )

    /** 읽는 쪽 관례 — `JdbcLlmCallLedger`의 쓰는 쪽과 짝을 맞춘다(UTC 오프셋으로 통일). */
    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private data class DocumentTotals(
        val documents: Int,
        val characters: Long,
        val credits: Long,
    )

    private data class CallTotals(
        val llmCalls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val estimatedCostUsd: BigDecimal?,
        val costUnknownCalls: Int,
        val failedCalls: Int,
    )
}
