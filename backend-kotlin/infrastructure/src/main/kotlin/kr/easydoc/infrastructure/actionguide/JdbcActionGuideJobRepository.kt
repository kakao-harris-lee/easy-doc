package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideJobAcquire
import kr.easydoc.application.actionguide.ActionGuideJobContext
import kr.easydoc.application.actionguide.ActionGuideJobInsert
import kr.easydoc.application.actionguide.ActionGuideJobLease
import kr.easydoc.application.actionguide.ActionGuideJobRepository
import kr.easydoc.application.actionguide.ActionGuideOperation
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.actionguide.ActionGuideJobFailureCode
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** PostgreSQL 한 트랜잭션에서 접수 멱등성과 worker fencing을 지키는 R2 저장소. */
@Suppress("TooManyFunctions")
class JdbcActionGuideJobRepository(private val jdbc: JdbcClient) : ActionGuideJobRepository {
    override fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): ActionGuideJobContext? =
        jdbc
            .sql(LOCK_CONTEXT_SQL)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .query { rs, _ ->
                ActionGuideJobContext(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    conversionId = rs.getObject("conversion_id", UUID::class.java),
                    contentRevision = rs.getLong("content_revision"),
                    guideRevision = rs.getObject("guide_revision") as? Long,
                    charCount = rs.getInt("char_count"),
                    completed = rs.getString("conversion_status") == "done",
                )
            }.optional()
            .orElse(null)

    override fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredActionGuideJob? = findOne(OWNED_REQUEST_SQL, ownerId, conversionId, requestId = requestId)

    override fun insert(job: StoredActionGuideJob): ActionGuideJobInsert {
        val attempts =
            jdbc
                .sql(
                    """
                    SELECT count(*) FROM action_guide_jobs
                    WHERE owner_user_id = :ownerId AND conversion_id = :conversionId
                      AND provider_started_at IS NOT NULL
                    """.trimIndent(),
                ).param("ownerId", job.ownerId)
                .param("conversionId", job.conversionId)
                .query { rs, _ -> rs.getInt(1) }
                .single()
        if (attempts >= MAX_ATTEMPTS_PER_CONVERSION) return ActionGuideJobInsert.AttemptLimit

        val inserted =
            jdbc
                .sql(INSERT_SQL)
                .param("id", job.jobId)
                .param("requestId", job.requestId)
                .param("ownerId", job.ownerId)
                .param("workspaceId", job.workspaceId)
                .param("documentId", job.documentId)
                .param("conversionId", job.conversionId)
                .param("expectedContentRevision", job.basedOnContentRevision)
                .param("expectedGuideRevision", job.expectedGuideRevision)
                .param("fingerprint", fingerprint(job.basedOnContentRevision, job.expectedGuideRevision, job.operation))
                .param("operation", job.operation.wireName)
                .param("status", job.status.wireName)
                .param("reservedCredits", job.reservedCredits)
                .param("createdAt", utc(job.createdAt))
                .param("updatedAt", utc(job.updatedAt))
                .update()
        return if (inserted == 1) {
            ActionGuideJobInsert.Inserted(job)
        } else {
            if (findByRequestId(job.ownerId, job.conversionId, job.requestId) != null) {
                ActionGuideJobInsert.RequestConflict
            } else {
                ActionGuideJobInsert.ActiveConflict
            }
        }
    }

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredActionGuideJob? = findOne(OWNED_ID_SQL, ownerId, conversionId, jobId = jobId)

    override fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuideJob? = findOne(ACTIVE_SQL, ownerId, conversionId)

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuideJob? = findOne(LATEST_SQL, ownerId, conversionId)

    override fun findActiveOwnedForOperation(
        ownerId: UUID,
        conversionId: UUID,
        operation: ActionGuideOperation,
    ): StoredActionGuideJob? = findForOperation(ACTIVE_OPERATION_SQL, ownerId, conversionId, operation)

    override fun findLatestOwnedForOperation(
        ownerId: UUID,
        conversionId: UUID,
        operation: ActionGuideOperation,
    ): StoredActionGuideJob? = findForOperation(LATEST_OPERATION_SQL, ownerId, conversionId, operation)

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): ActionGuideJobAcquire {
        // 두 replica가 같은 free slot을 동시에 보지 않게 획득 결정만 짧게 직렬화한다.
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:key)")
            .param("key", ACQUIRE_LOCK_KEY)
            .query { _, _ -> true }
            .single()
        acquireExpired(owner, leaseDuration, maxLeaseAttempts)?.let { return it }
        return acquireQueued(owner, leaseDuration) ?: ActionGuideJobAcquire.Empty
    }

    override fun lockIfHeld(lease: ActionGuideJobLease): StoredActionGuideJob? =
        jdbc
            .sql(LOCK_HELD_SQL)
            .param("jobId", lease.jobId)
            .param("owner", lease.owner)
            .param("fence", lease.fence)
            .query(::mapJob)
            .optional()
            .orElse(null)

    override fun hasCurrentInput(
        lease: ActionGuideJobLease,
        basedOnContentRevision: Long,
    ): Boolean {
        val ownerId =
            jdbc
                .sql("SELECT owner_user_id FROM action_guide_jobs WHERE id = :jobId")
                .param("jobId", lease.jobId)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .optional()
                .orElse(null) ?: return false
        return jdbc
            .sql(HAS_CURRENT_INPUT_SQL)
            .param("jobId", lease.jobId)
            .param("owner", lease.owner)
            .param("fence", lease.fence)
            .param("revision", basedOnContentRevision)
            .param("ownerId", ownerId)
            .query { rs, _ -> rs.getBoolean(1) }
            .optional()
            .orElse(false)
    }

    override fun markProviderStarted(
        lease: ActionGuideJobLease,
        executionId: UUID,
        startedAt: Instant,
    ): Boolean =
        jdbc
            .sql(MARK_STARTED_SQL)
            .param("jobId", lease.jobId)
            .param("owner", lease.owner)
            .param("fence", lease.fence)
            .param("executionId", executionId)
            .param("startedAt", utc(startedAt))
            .update() == 1

    override fun markSucceeded(
        lease: ActionGuideJobLease,
        updatedAt: Instant,
    ): Boolean = finish(lease, ActionGuideJobStatus.SUCCEEDED, null, "consumed", updatedAt)

    override fun markFailed(
        lease: ActionGuideJobLease,
        failureCode: ActionGuideJobFailureCode,
        updatedAt: Instant,
    ): Boolean = finish(lease, ActionGuideJobStatus.FAILED, failureCode, "released", updatedAt)

    override fun markSuperseded(
        lease: ActionGuideJobLease,
        updatedAt: Instant,
    ): Boolean = finish(lease, ActionGuideJobStatus.SUPERSEDED, null, "released", updatedAt)

    /** 시작한 호출의 결과는 알 수 없으므로, 불명확 회수가 리스 재획득 상한보다 우선한다. */
    private fun acquireExpired(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): ActionGuideJobAcquire? =
        jdbc
            .sql(ACQUIRE_EXPIRED_SQL)
            .param("owner", owner)
            .param("leaseSeconds", leaseDuration.seconds)
            .query { rs, _ ->
                val attempts = rs.getInt("attempts")
                val lease = ActionGuideJobLease(rs.getObject("id", UUID::class.java), owner, attempts)
                when {
                    rs.getObject("provider_started_at") != null -> ActionGuideJobAcquire.RecoverUnknown(lease)
                    attempts > maxLeaseAttempts -> ActionGuideJobAcquire.DeadLettered(lease)
                    else -> ActionGuideJobAcquire.Held(lease)
                }
            }.optional()
            .orElse(null)

    private fun acquireQueued(
        owner: String,
        leaseDuration: Duration,
    ): ActionGuideJobAcquire? =
        jdbc
            .sql(ACQUIRE_QUEUED_SQL)
            .param("owner", owner)
            .param("leaseSeconds", leaseDuration.seconds)
            .query { rs, _ ->
                ActionGuideJobAcquire.Held(
                    ActionGuideJobLease(rs.getObject("id", UUID::class.java), owner, rs.getInt("attempts")),
                )
            }.optional()
            .orElse(null)

    private fun finish(
        lease: ActionGuideJobLease,
        status: ActionGuideJobStatus,
        failureCode: ActionGuideJobFailureCode?,
        settlement: String,
        updatedAt: Instant,
    ): Boolean =
        jdbc
            .sql(FINISH_SQL)
            .param("jobId", lease.jobId)
            .param("owner", lease.owner)
            .param("fence", lease.fence)
            .param("status", status.wireName)
            .param("failureCode", failureCode?.wireName)
            .param("settlement", settlement)
            .param("updatedAt", utc(updatedAt))
            .update() == 1

    private fun findForOperation(
        sql: String,
        ownerId: UUID,
        conversionId: UUID,
        operation: ActionGuideOperation,
    ): StoredActionGuideJob? =
        jdbc
            .sql(sql)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .param("operation", operation.wireName)
            .query(::mapJob)
            .optional()
            .orElse(null)

    private fun findOne(
        sql: String,
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID? = null,
        jobId: UUID? = null,
    ): StoredActionGuideJob? {
        var statement = jdbc.sql(sql).param("ownerId", ownerId).param("conversionId", conversionId)
        requestId?.let { statement = statement.param("requestId", it) }
        jobId?.let { statement = statement.param("jobId", it) }
        return statement.query(::mapJob).optional().orElse(null)
    }

    private fun mapJob(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): StoredActionGuideJob =
        StoredActionGuideJob(
            jobId = rs.getObject("id", UUID::class.java),
            ownerId = rs.getObject("owner_user_id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            requestId = rs.getObject("request_id", UUID::class.java),
            expectedGuideRevision = rs.getObject("expected_guide_revision", Long::class.javaObjectType),
            basedOnContentRevision = rs.getLong("based_on_content_revision"),
            reservedCredits = rs.getBigDecimal("reserved_credits"),
            status = ActionGuideJobStatus.ofWireName(rs.getString("status")),
            failureCode = rs.getString("failure_code")?.let(ActionGuideJobFailureCode::ofWireName),
            executionId = rs.getObject("provider_execution_id", UUID::class.java),
            providerStartedAt = rs.getObject("provider_started_at", OffsetDateTime::class.java)?.toInstant(),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
            operation = ActionGuideOperation.entries.single { it.wireName == rs.getString("operation") },
        )

    private fun fingerprint(
        contentRevision: Long,
        guideRevision: Long?,
        operation: ActionGuideOperation,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                "${operation.wireName}:$contentRevision:${guideRevision ?: "null"}".toByteArray(StandardCharsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val MAX_ATTEMPTS_PER_CONVERSION: Int = 3
        const val ACQUIRE_LOCK_KEY: Long = 4_163_247_522L

        val JOB_COLUMNS =
            """
            id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
            expected_guide_revision, based_on_content_revision, reserved_credits, status,
            failure_code, provider_execution_id, provider_started_at, created_at, updated_at, operation
            """.trimIndent()

        val LOCK_CONTEXT_SQL =
            """
            SELECT d.workspace_id, d.id AS document_id, c.id AS conversion_id,
                   c.content_revision, c.status AS conversion_status, d.char_count,
                   g.guide_revision
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            LEFT JOIN action_guides g ON g.conversion_id = c.id
            WHERE c.id = :conversionId
              AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            FOR NO KEY UPDATE OF c
            """.trimIndent()

        val OWNED_REQUEST_SQL =
            """
            SELECT $JOB_COLUMNS FROM action_guide_jobs
            WHERE owner_user_id = :ownerId AND conversion_id = :conversionId
              AND (request_id = :requestId OR (
                  operation = 'analysis' AND request_id IN (
                      SELECT original.request_id
                      FROM action_guide_analysis_requests requested
                      JOIN action_guide_analysis_requests original
                        ON original.conversion_id = requested.conversion_id
                       AND original.analysis_id = requested.analysis_id
                      WHERE requested.conversion_id = :conversionId AND requested.request_id = :requestId
                  )
              ))
            ORDER BY (request_id = :requestId) DESC LIMIT 1
            """.trimIndent()
        val OWNED_ID_SQL =
            "SELECT $JOB_COLUMNS FROM action_guide_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND id = :jobId"
        val ACTIVE_SQL =
            "SELECT $JOB_COLUMNS FROM action_guide_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND status IN ('queued','running') ORDER BY created_at DESC LIMIT 1"
        val LATEST_SQL =
            "SELECT $JOB_COLUMNS FROM action_guide_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId ORDER BY created_at DESC LIMIT 1"

        val ACTIVE_OPERATION_SQL =
            "SELECT $JOB_COLUMNS FROM action_guide_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND operation = :operation " +
                "AND status IN ('queued','running') ORDER BY created_at DESC LIMIT 1"
        val LATEST_OPERATION_SQL =
            "SELECT $JOB_COLUMNS FROM action_guide_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND operation = :operation ORDER BY created_at DESC LIMIT 1"

        val INSERT_SQL =
            """
            INSERT INTO action_guide_jobs
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, expected_guide_revision, based_on_content_revision,
                 input_fingerprint, status, reserved_credits, created_at, updated_at, operation)
            VALUES
                (:id, :requestId, :ownerId, :workspaceId, :documentId, :conversionId,
                 :expectedContentRevision, :expectedGuideRevision, :expectedContentRevision,
                 :fingerprint, :status, :reservedCredits, :createdAt, :updatedAt, :operation)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        val ACQUIRE_EXPIRED_SQL =
            """
            WITH picked AS MATERIALIZED (
                SELECT id FROM action_guide_jobs
                WHERE status = 'running' AND lease_until < now()
                ORDER BY lease_until, id
                FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE action_guide_jobs AS job
            SET lease_owner = :owner,
                lease_until = now() + (:leaseSeconds * INTERVAL '1 second'),
                attempts = attempts + 1,
                updated_at = now()
            FROM picked
            WHERE job.id = picked.id
            RETURNING job.id, job.attempts, job.provider_started_at
            """.trimIndent()

        val ACQUIRE_QUEUED_SQL =
            """
            WITH picked AS MATERIALIZED (
                SELECT id FROM action_guide_jobs
                WHERE status = 'queued'
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED LIMIT 1
            ), free_slot AS MATERIALIZED (
                SELECT slot FROM generate_series(1, 2) AS slot
                WHERE NOT EXISTS (
                    SELECT 1 FROM action_guide_jobs running
                    WHERE running.status = 'running' AND running.worker_slot = slot
                )
                ORDER BY slot LIMIT 1
            )
            UPDATE action_guide_jobs AS job
            SET status = 'running',
                lease_owner = :owner,
                lease_until = now() + (:leaseSeconds * INTERVAL '1 second'),
                worker_slot = free_slot.slot,
                attempts = attempts + 1,
                updated_at = now()
            FROM picked, free_slot
            WHERE job.id = picked.id
            RETURNING job.id, job.attempts
            """.trimIndent()

        val LOCK_HELD_SQL =
            "SELECT $JOB_COLUMNS FROM action_guide_jobs WHERE id = :jobId AND status = 'running' " +
                "AND lease_owner = :owner AND attempts = :fence FOR UPDATE"

        val HAS_CURRENT_INPUT_SQL =
            """
            SELECT true
            FROM action_guide_jobs job
            JOIN conversions c ON c.id = job.conversion_id
            JOIN documents d ON d.id = c.document_id
            WHERE job.id = :jobId AND job.status = 'running'
              AND job.lease_owner = :owner AND job.attempts = :fence
              AND c.content_revision = :revision
              AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            FOR NO KEY UPDATE OF c
            """.trimIndent()

        val MARK_STARTED_SQL =
            """
            UPDATE action_guide_jobs
            SET provider_attempts = 1, provider_execution_id = :executionId,
                provider_started_at = :startedAt, updated_at = :startedAt
            WHERE id = :jobId AND status = 'running' AND lease_owner = :owner
              AND attempts = :fence AND provider_attempts = 0
            """.trimIndent()

        val FINISH_SQL =
            """
            UPDATE action_guide_jobs
            SET status = :status, settlement = :settlement, failure_code = :failureCode,
                lease_owner = NULL, lease_until = NULL, worker_slot = NULL, updated_at = :updatedAt
            WHERE id = :jobId AND status = 'running' AND lease_owner = :owner AND attempts = :fence
            """.trimIndent()
    }
}
