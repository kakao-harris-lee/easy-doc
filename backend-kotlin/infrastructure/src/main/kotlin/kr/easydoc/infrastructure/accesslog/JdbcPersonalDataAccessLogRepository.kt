package kr.easydoc.infrastructure.accesslog

import kr.easydoc.application.accesslog.PersonalDataAccessLogRepository
import kr.easydoc.application.accesslog.PersonalDataAccessLogRow
import kr.easydoc.core.accesslog.PersonalDataAccessOutcome
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 점검 보고서(§3.5) 읽기 어댑터 — 집계는 SQL이 아니라
 * [kr.easydoc.application.accesslog.PersonalDataAccessReportService]가 한다(표 하나·건수가
 * 적어 SQL 집계가 필요 없다).
 */
class JdbcPersonalDataAccessLogRepository(private val jdbc: JdbcClient) : PersonalDataAccessLogRepository {
    override fun findBetween(
        fromInstant: Instant,
        toExclusiveInstant: Instant,
    ): List<PersonalDataAccessLogRow> =
        jdbc
            .sql(SELECT_SQL)
            .param("from", fromInstant.toOffsetDateTime())
            .param("toExclusive", toExclusiveInstant.toOffsetDateTime())
            .query { rs, _ -> toRow(rs) }
            .list()

    private fun toRow(rs: ResultSet): PersonalDataAccessLogRow =
        PersonalDataAccessLogRow(
            id = rs.getObject("id", UUID::class.java),
            actorUserId = rs.getObject("actor_user_id", UUID::class.java),
            accessedAt = rs.getObject("accessed_at", OffsetDateTime::class.java).toInstant(),
            clientIp = rs.getString("client_ip"),
            operation = rs.getString("operation"),
            subjectScope = rs.getString("subject_scope"),
            outcome = PersonalDataAccessOutcome.valueOf(rs.getString("outcome").uppercase()),
        )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val SELECT_SQL =
            """
            SELECT id, actor_user_id, accessed_at, client_ip, operation, subject_scope, outcome
            FROM personal_data_access_logs
            WHERE accessed_at >= :from AND accessed_at < :toExclusive
            ORDER BY accessed_at
            """
    }
}
