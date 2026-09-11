package kr.easydoc.infrastructure.accesslog

import kr.easydoc.application.accesslog.PersonalDataAccessLogEntry
import kr.easydoc.application.accesslog.PersonalDataAccessLogWriter
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `personal_data_access_logs`(V22) 삽입 전용 어댑터 — **`UPDATE`·`DELETE` 문을 담지 않는다**
 * (`PersonalDataAccessLogWriter` KDoc, 계획
 * `docs/plans/2026-09-11-access-log-retention.md` §3.1 수용 기준 5).
 */
class JdbcPersonalDataAccessLogWriter(private val jdbc: JdbcClient) : PersonalDataAccessLogWriter {
    override fun insert(entry: PersonalDataAccessLogEntry) {
        jdbc
            .sql(INSERT_SQL)
            .param("id", UUID.randomUUID())
            .param("actorUserId", entry.actorUserId)
            .param("accessedAt", OffsetDateTime.ofInstant(entry.accessedAt, ZoneOffset.UTC))
            .param("clientIp", entry.clientIp)
            .param("operation", entry.operation)
            .param("subjectScope", entry.subjectScope)
            .param("outcome", entry.outcome.wireName)
            .update()
    }

    private companion object {
        const val INSERT_SQL =
            """
            INSERT INTO personal_data_access_logs
                (id, actor_user_id, accessed_at, client_ip, operation, subject_scope, outcome)
            VALUES (:id, :actorUserId, :accessedAt, :clientIp, :operation, :subjectScope, :outcome)
            """
    }
}
