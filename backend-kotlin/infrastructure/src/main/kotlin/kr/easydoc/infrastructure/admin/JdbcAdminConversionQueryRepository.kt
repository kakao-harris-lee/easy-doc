package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminConversionQueryRepository
import kr.easydoc.application.admin.AdminConversionRow
import kr.easydoc.application.admin.AdminErrorRow
import kr.easydoc.application.admin.AdminFailureCount
import kr.easydoc.application.admin.AdminProviderFailureCount
import kr.easydoc.core.document.ConversionStatus
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 관리자 오류·최근 변환 조회 — `conversions` × `documents`, **소유 술어 없이** 워크스페이스를
 * 가로지른다(관리자 전용, `AdminConversionQueryRepository` KDoc). `documents`·`conversions`에
 * 닿는 문장이라 `OwnershipPredicateGuardTest`의 정확 열거 핀에 있다 — 그 인구조사가 「소유
 * 술어 없음」을 놓치지 않는다는 확인이지, 여기서는 **의도한 설계**다(관리자도 본문·프롬프트는
 * 어디에서도 읽지 않는다 — `failure_code`·제목·상태·시각뿐).
 *
 * [providerFailureCounts]만 `llm_calls`(V18)를 읽는다 — 그 표는 암호화 대상 열이 없어
 * `OwnershipPredicateGuardTest`·`EnvelopeColumnWriteGuardTest`(둘 다
 * [kr.easydoc.core.crypto.EncryptedField]가 아는 표만 훑는다) 인구조사 대상이 아니다
 * (V14·V18 머리주석, `JdbcLlmCallLedger` KDoc과 같은 판단).
 */
class JdbcAdminConversionQueryRepository(private val jdbc: JdbcClient) : AdminConversionQueryRepository {
    override fun recentForWorkspace(
        workspaceId: UUID,
        limit: Int,
    ): List<AdminConversionRow> =
        jdbc
            .sql(RECENT_FOR_WORKSPACE_SQL)
            .param("workspaceId", workspaceId)
            .param("limit", limit)
            .query { rs, _ -> toConversionRow(rs) }
            .list()

    override fun failureCounts(
        from: Instant,
        toExclusive: Instant,
    ): List<AdminFailureCount> =
        jdbc
            .sql(FAILURE_COUNTS_SQL)
            .param("from", from.atOffset(java.time.ZoneOffset.UTC))
            .param("toExclusive", toExclusive.atOffset(java.time.ZoneOffset.UTC))
            .query { rs, _ ->
                AdminFailureCount(failureCode = rs.getString("failure_code"), count = rs.getLong("cnt"))
            }.list()

    override fun recentFailures(
        from: Instant,
        toExclusive: Instant,
        limit: Int,
    ): List<AdminErrorRow> =
        jdbc
            .sql(RECENT_FAILURES_SQL)
            .param("from", from.atOffset(java.time.ZoneOffset.UTC))
            .param("toExclusive", toExclusive.atOffset(java.time.ZoneOffset.UTC))
            .param("limit", limit)
            .query { rs, _ -> toErrorRow(rs) }
            .list()

    override fun providerFailureCounts(
        from: Instant,
        toExclusive: Instant,
    ): List<AdminProviderFailureCount> =
        jdbc
            .sql(PROVIDER_FAILURE_COUNTS_SQL)
            .param("from", from.atOffset(java.time.ZoneOffset.UTC))
            .param("toExclusive", toExclusive.atOffset(java.time.ZoneOffset.UTC))
            .query { rs, _ ->
                AdminProviderFailureCount(
                    failureClass = rs.getString("failure_class"),
                    count = rs.getLong("cnt"),
                )
            }.list()

    private fun toConversionRow(rs: ResultSet): AdminConversionRow =
        AdminConversionRow(
            id = rs.getObject("id", UUID::class.java),
            documentTitle = rs.getString("title"),
            status = ConversionStatus.ofWireName(rs.getString("status")),
            failureCode = rs.getString("failure_code"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun toErrorRow(rs: ResultSet): AdminErrorRow =
        AdminErrorRow(
            id = rs.getObject("id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            failureCode = rs.getString("failure_code"),
        )

    private companion object {
        val RECENT_FOR_WORKSPACE_SQL =
            """
            SELECT c.id, d.title, c.status, c.failure_code, c.created_at
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE d.workspace_id = :workspaceId
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT :limit
            """.trimIndent()

        val FAILURE_COUNTS_SQL =
            """
            SELECT failure_code, count(*) AS cnt
            FROM conversions
            WHERE status = 'failed' AND created_at >= :from AND created_at < :toExclusive
            GROUP BY failure_code
            ORDER BY cnt DESC
            """.trimIndent()

        val RECENT_FAILURES_SQL =
            """
            SELECT c.id, d.workspace_id, c.created_at, c.failure_code
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.status = 'failed' AND c.created_at >= :from AND c.created_at < :toExclusive
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT :limit
            """.trimIndent()

        /** `failure_class`는 `outcome = provider_error`일 때만 값이 있으므로 NULL은 나오지 않는다. */
        val PROVIDER_FAILURE_COUNTS_SQL =
            """
            SELECT failure_class, count(*) AS cnt
            FROM llm_calls
            WHERE outcome = 'provider_error' AND called_at >= :from AND called_at < :toExclusive
            GROUP BY failure_class
            -- 건수 동률이면 failure_class로 더 좁혀 결과 순서를 안정시킨다(2026-09-08 리뷰) —
            -- ORDER BY cnt DESC 만으로는 동률 행의 순서가 PostgreSQL 실행계획에 좌우된다.
            ORDER BY cnt DESC, failure_class
            """.trimIndent()
    }
}
