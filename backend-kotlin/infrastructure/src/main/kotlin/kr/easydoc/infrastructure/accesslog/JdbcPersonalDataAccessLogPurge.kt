package kr.easydoc.infrastructure.accesslog

import kr.easydoc.application.accesslog.PersonalDataAccessLogPurge
import kr.easydoc.application.accesslog.PersonalDataAccessLogPurgeResult
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant

/**
 * `personal_data_access_logs`(V22)를 [accessedBefore] 기준으로 고르고 지운다 — 서브쿼리
 * 한 문장으로 서버 안에서 끝낸다(`JdbcSignupGrantRecordPurge`와 같은 형태). `client_ip`·
 * `actor_user_id`·`subject_scope`를 JVM으로 꺼내지 않는다 — 지울 행의 id만 서브쿼리 안에서
 * 골라 `DELETE`에 바로 쓴다. `accessed_at`에 인덱스(`ix_personal_data_access_logs_accessed_at`)
 * 가 있어 정렬·`LIMIT`이 그 인덱스를 탄다.
 *
 * `FOR UPDATE SKIP LOCKED`는 그대로 유지한다 — 여러 worker 인스턴스가 같은 배치를 동시에
 * 돌려도 서로 다른 행 집합을 골라 잠그므로 경합·중복 삭제 시도가 없다.
 */
class JdbcPersonalDataAccessLogPurge(private val jdbc: JdbcClient) : PersonalDataAccessLogPurge {
    override fun purge(
        accessedBefore: Instant,
        batchSize: Int,
    ): PersonalDataAccessLogPurgeResult {
        val deleted =
            jdbc
                .sql(DELETE_EXPIRED_SQL)
                .param("accessedBefore", Timestamp.from(accessedBefore))
                .param("limit", batchSize)
                .update()
        return PersonalDataAccessLogPurgeResult(enabled = true, deleted = deleted)
    }

    private companion object {
        val DELETE_EXPIRED_SQL =
            """
            DELETE FROM personal_data_access_logs
            WHERE id IN (
                SELECT id
                FROM personal_data_access_logs
                WHERE accessed_at < :accessedBefore
                ORDER BY accessed_at ASC
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            """.trimIndent()
    }
}
