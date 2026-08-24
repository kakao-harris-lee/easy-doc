package kr.easydoc.infrastructure.queue

import kr.easydoc.application.conversion.ConversionAcquire
import kr.easydoc.application.conversion.ConversionJobLease
import kr.easydoc.application.conversion.ConversionJobLeasePort
import kr.easydoc.application.document.ConversionQueue
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID

/** `conversion_jobs` 테이블에 작업을 등록하고 소비한다. 스키마는 `V1__initial_schema.sql`. */
class JdbcConversionQueue(private val jdbc: JdbcClient) :
    ConversionQueue,
    ConversionJobLeasePort {
    override fun enqueue(conversionId: UUID) {
        jdbc
            .sql(
                """
                INSERT INTO conversion_jobs (conversion_id, state, attempts, next_attempt_at)
                VALUES (:conversionId, :state, 0, now())
                ON CONFLICT (conversion_id) DO NOTHING
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("state", READY_STATE)
            .update()
    }

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxAttempts: Int,
    ): ConversionAcquire =
        jdbc
            .sql(ACQUIRE_SQL)
            .param("owner", owner)
            .param("leaseSeconds", leaseDuration.seconds)
            .param("maxAttempts", maxAttempts)
            .query { rs, _ ->
                val conversionId = rs.getObject("conversion_id", UUID::class.java)
                if (rs.getBoolean("exhausted")) {
                    ConversionAcquire.Exhausted(conversionId)
                } else {
                    ConversionAcquire.Held(
                        ConversionJobLease(
                            conversionId = conversionId,
                            owner = rs.getString("lease_owner"),
                            attempts = rs.getInt("attempts"),
                        ),
                    )
                }
            }.optional()
            .orElse(ConversionAcquire.Empty)

    override fun renew(
        lease: ConversionJobLease,
        leaseDuration: Duration,
    ): Boolean =
        jdbc
            .sql(RENEW_SQL)
            .param("id", lease.conversionId)
            .param("owner", lease.owner)
            .param("attempts", lease.attempts)
            .param("leased", LEASED_STATE)
            .param("seconds", leaseDuration.seconds)
            .update() > 0

    override fun lockIfHeld(lease: ConversionJobLease): Boolean =
        jdbc
            .sql(LOCK_HELD_SQL)
            .param("id", lease.conversionId)
            .param("leased", LEASED_STATE)
            .param("owner", lease.owner)
            .param("attempts", lease.attempts)
            .query { rs, _ -> rs.getObject("conversion_id", UUID::class.java) }
            .optional()
            .isPresent

    override fun complete(lease: ConversionJobLease): Boolean = finish(lease, DONE_STATE)

    override fun retry(
        lease: ConversionJobLease,
        delay: Duration,
    ): Boolean =
        jdbc
            .sql(RETRY_SQL)
            .param("id", lease.conversionId)
            .param("owner", lease.owner)
            .param("attempts", lease.attempts)
            .param("leased", LEASED_STATE)
            .param("ready", READY_STATE)
            .param("seconds", delay.seconds)
            .update() > 0

    override fun fail(lease: ConversionJobLease): Boolean = finish(lease, FAILED_STATE)

    private fun finish(
        lease: ConversionJobLease,
        state: String,
    ): Boolean =
        jdbc
            .sql(COMPLETE_SQL)
            .param("id", lease.conversionId)
            .param("owner", lease.owner)
            .param("attempts", lease.attempts)
            .param("leased", LEASED_STATE)
            .param("state", state)
            .update() > 0

    companion object {
        /** 갓 등록된 작업의 상태. `ck_conversion_jobs_state_valid` 목록 안이다. */
        const val READY_STATE: String = "ready"

        const val LEASED_STATE: String = "leased"

        const val DONE_STATE: String = "done"

        const val FAILED_STATE: String = "failed"

        /**
         * PG12+ 는 CTE 를 인라인할 수 있다. `FOR UPDATE SKIP LOCKED` 가 UPDATE 에 접히면
         * 두 세션이 같은 행을 고른다. MATERIALIZED 로 고르기·잠금을 먼저 고정한다.
         */
        val ACQUIRE_SQL =
            """
            WITH picked AS MATERIALIZED (
                SELECT conversion_id, attempts
                FROM conversion_jobs
                WHERE (state = '$READY_STATE' AND next_attempt_at <= now())
                   OR (state = '$LEASED_STATE' AND lease_until < now())
                ORDER BY next_attempt_at ASC, conversion_id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE conversion_jobs AS job
            SET state = CASE
                    WHEN picked.attempts >= :maxAttempts THEN '$FAILED_STATE'
                    ELSE '$LEASED_STATE'
                END,
                lease_owner = CASE
                    WHEN picked.attempts >= :maxAttempts THEN NULL
                    ELSE :owner
                END,
                lease_until = CASE
                    WHEN picked.attempts >= :maxAttempts THEN NULL
                    ELSE now() + (:leaseSeconds * INTERVAL '1 second')
                END,
                attempts = CASE
                    WHEN picked.attempts >= :maxAttempts THEN job.attempts
                    ELSE job.attempts + 1
                END,
                updated_at = now()
            FROM picked
            WHERE job.conversion_id = picked.conversion_id
            RETURNING job.conversion_id, job.lease_owner, job.attempts,
                      (picked.attempts >= :maxAttempts) AS exhausted
            """.trimIndent()

        val LOCK_HELD_SQL =
            """
            SELECT conversion_id
            FROM conversion_jobs
            WHERE conversion_id = :id
              AND state = :leased
              AND lease_owner = :owner
              AND attempts = :attempts
            FOR UPDATE
            """.trimIndent()

        val RENEW_SQL =
            """
            UPDATE conversion_jobs
            SET lease_until = now() + (:seconds * INTERVAL '1 second'),
                updated_at = now()
            WHERE conversion_id = :id
              AND state = :leased
              AND lease_owner = :owner
              AND attempts = :attempts
            """.trimIndent()

        val RETRY_SQL =
            """
            UPDATE conversion_jobs
            SET state = :ready,
                lease_owner = NULL,
                lease_until = NULL,
                next_attempt_at = now() + (:seconds * INTERVAL '1 second'),
                updated_at = now()
            WHERE conversion_id = :id
              AND state = :leased
              AND lease_owner = :owner
              AND attempts = :attempts
            """.trimIndent()

        val COMPLETE_SQL =
            """
            UPDATE conversion_jobs
            SET state = :state,
                lease_owner = NULL,
                lease_until = NULL,
                updated_at = now()
            WHERE conversion_id = :id
              AND state = :leased
              AND lease_owner = :owner
              AND attempts = :attempts
            """.trimIndent()
    }
}
