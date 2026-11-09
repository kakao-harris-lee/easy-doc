package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.illustration.generation.IllustrationGenerationJobAcquire
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobContext
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobInsert
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobLease
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobRepository
import kr.easydoc.application.illustration.generation.StoredIllustrationGenerationJob
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobFailureCode
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Suppress("TooManyFunctions")
class JdbcIllustrationGenerationJobRepository(private val jdbc: JdbcClient) : IllustrationGenerationJobRepository {
    override fun lockAndCountDocumentJobs(
        ownerId: UUID,
        documentId: UUID,
    ): Int {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtextextended('illustration-generation:' || :documentId, 0))")
            .param("documentId", documentId.toString())
            .query { _, _ -> true }
            .single()
        return countDocumentJobs(ownerId, documentId)
    }

    override fun countDocumentJobs(
        ownerId: UUID,
        documentId: UUID,
    ): Int =
        jdbc
            .sql(
                """
                SELECT count(*) FROM illustration_generation_jobs j
                JOIN documents d ON d.id = j.document_id
                WHERE j.document_id = :documentId AND d.user_id = :ownerId
                """.trimIndent(),
            ).param("documentId", documentId)
            .param("ownerId", ownerId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    override fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobContext? = ownedContext(LOCK_CONTEXT_SQL, ownerId, conversionId)

    override fun readOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobContext? = ownedContext(CONTEXT_SQL, ownerId, conversionId)

    private fun ownedContext(
        sql: String,
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationGenerationJobContext? {
        // Keep deletion behind the document lock; only conversion writes need the stronger conversion lock.
        val documentExists =
            jdbc
                .sql(LOCK_DOCUMENT_SQL)
                .param("ownerId", ownerId)
                .param("conversionId", conversionId)
                .query { _, _ -> true }
                .optional()
                .orElse(false)
        if (!documentExists) return null
        return jdbc
            .sql(sql)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .query { rs, _ ->
                IllustrationGenerationJobContext(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    conversionId = rs.getObject("conversion_id", UUID::class.java),
                    contentRevision = rs.getLong("content_revision"),
                    completed = rs.getString("conversion_status") == "done",
                )
            }.optional()
            .orElse(null)
    }

    override fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredIllustrationGenerationJob? = findOne(OWNED_REQUEST_SQL, ownerId, conversionId, requestId = requestId)

    override fun insert(job: StoredIllustrationGenerationJob): IllustrationGenerationJobInsert {
        val inserted =
            jdbc
                .sql(INSERT_SQL)
                .param("id", job.jobId)
                .param("requestId", job.requestId)
                .param("ownerId", job.ownerId)
                .param("workspaceId", job.workspaceId)
                .param("documentId", job.documentId)
                .param("conversionId", job.conversionId)
                .param("suggestionResultId", job.suggestionResultId)
                .param("suggestionId", job.suggestionId)
                .param("analysisVersion", job.analysisVersion)
                .param("configurationRevision", job.configurationRevision)
                .param("revision", job.basedOnContentRevision)
                .param("fingerprint", job.inputFingerprint)
                .param("input", job.inputPayload.bytes)
                .param("scheme", job.inputPayload.scheme)
                .param("keyVersion", job.inputPayload.keyVersion)
                .param("status", job.status.wireName)
                .param("reservedCredits", job.reservedCredits)
                .param("settlement", if (job.reservedCredits.signum() == 0) NOT_CHARGED else "reserved")
                .param("createdAt", utc(job.createdAt))
                .param("updatedAt", utc(job.updatedAt))
                .update()
        return if (inserted == 1) {
            IllustrationGenerationJobInsert.Inserted(job)
        } else if (findByRequestId(job.ownerId, job.conversionId, job.requestId) != null) {
            IllustrationGenerationJobInsert.RequestConflict
        } else {
            IllustrationGenerationJobInsert.ActiveConflict
        }
    }

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredIllustrationGenerationJob? = findOne(OWNED_ID_SQL, ownerId, conversionId, jobId = jobId)

    override fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationGenerationJob? = findOne(ACTIVE_SQL, ownerId, conversionId)

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationGenerationJob? = findOne(LATEST_SQL, ownerId, conversionId)

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationGenerationJobAcquire {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:key)")
            .param("key", ACQUIRE_LOCK_KEY)
            .query { _, _ -> true }
            .single()
        acquireExpired(owner, leaseDuration, maxLeaseAttempts)?.let { return it }
        return acquireQueued(owner, leaseDuration) ?: IllustrationGenerationJobAcquire.Empty
    }

    override fun lockIfHeld(lease: IllustrationGenerationJobLease): StoredIllustrationGenerationJob? =
        jdbc
            .sql(LOCK_HELD_SQL)
            .param("jobId", lease.jobId)
            .param("owner", lease.owner)
            .param("fence", lease.fence)
            .query(::mapJob)
            .optional()
            .orElse(null)

    override fun hasCurrentInput(
        lease: IllustrationGenerationJobLease,
        basedOnContentRevision: Long,
        suggestionResultId: UUID,
    ): Boolean {
        val ownerId =
            jdbc
                .sql("SELECT owner_user_id FROM illustration_generation_jobs WHERE id = :jobId")
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
            .param("suggestionResultId", suggestionResultId)
            .param("ownerId", ownerId)
            .query { rs, _ -> rs.getBoolean(1) }
            .optional()
            .orElse(false)
    }

    override fun markProviderStarted(
        lease: IllustrationGenerationJobLease,
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
        lease: IllustrationGenerationJobLease,
        updatedAt: Instant,
    ): Boolean = finish(lease, IllustrationGenerationJobStatus.SUCCEEDED, null, "consumed", updatedAt)

    override fun markFailed(
        lease: IllustrationGenerationJobLease,
        failureCode: IllustrationGenerationJobFailureCode,
        updatedAt: Instant,
    ): Boolean = finish(lease, IllustrationGenerationJobStatus.FAILED, failureCode, "released", updatedAt)

    override fun markSuperseded(
        lease: IllustrationGenerationJobLease,
        updatedAt: Instant,
    ): Boolean = finish(lease, IllustrationGenerationJobStatus.SUPERSEDED, null, "released", updatedAt)

    private fun acquireExpired(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationGenerationJobAcquire? =
        jdbc
            .sql(ACQUIRE_EXPIRED_SQL)
            .param("owner", owner)
            .param("leaseSeconds", leaseDuration.seconds)
            .query { rs, _ ->
                val attempts = rs.getInt("attempts")
                val lease = IllustrationGenerationJobLease(rs.getObject("id", UUID::class.java), owner, attempts)
                when {
                    rs.getObject("provider_started_at") != null -> {
                        IllustrationGenerationJobAcquire.RecoverUnknown(lease)
                    }

                    attempts > maxLeaseAttempts -> {
                        IllustrationGenerationJobAcquire.DeadLettered(lease)
                    }

                    else -> {
                        IllustrationGenerationJobAcquire.Held(lease)
                    }
                }
            }.optional()
            .orElse(null)

    private fun acquireQueued(
        owner: String,
        leaseDuration: Duration,
    ): IllustrationGenerationJobAcquire? =
        jdbc
            .sql(ACQUIRE_QUEUED_SQL)
            .param("owner", owner)
            .param("leaseSeconds", leaseDuration.seconds)
            .query { rs, _ ->
                IllustrationGenerationJobAcquire.Held(
                    IllustrationGenerationJobLease(
                        rs.getObject("id", UUID::class.java),
                        owner,
                        rs.getInt("attempts"),
                    ),
                )
            }.optional()
            .orElse(null)

    private fun finish(
        lease: IllustrationGenerationJobLease,
        status: IllustrationGenerationJobStatus,
        failureCode: IllustrationGenerationJobFailureCode?,
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

    private fun findOne(
        sql: String,
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID? = null,
        jobId: UUID? = null,
    ): StoredIllustrationGenerationJob? {
        var statement = jdbc.sql(sql).param("ownerId", ownerId).param("conversionId", conversionId)
        requestId?.let { statement = statement.param("requestId", it) }
        jobId?.let { statement = statement.param("jobId", it) }
        return statement.query(::mapJob).optional().orElse(null)
    }

    private fun mapJob(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): StoredIllustrationGenerationJob =
        StoredIllustrationGenerationJob(
            jobId = rs.getObject("id", UUID::class.java),
            ownerId = rs.getObject("owner_user_id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            requestId = rs.getObject("request_id", UUID::class.java),
            suggestionResultId = rs.getObject("suggestion_result_id", UUID::class.java),
            suggestionId = rs.getObject("suggestion_id", UUID::class.java),
            analysisVersion = rs.getString("analysis_version"),
            configurationRevision = rs.getString("configuration_revision"),
            basedOnContentRevision = rs.getLong("based_on_content_revision"),
            inputFingerprint = rs.getString("input_fingerprint"),
            inputPayload =
                EncryptedContent(
                    rs.getBytes("input_payload_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
            reservedCredits = rs.getBigDecimal("reserved_credits"),
            status = IllustrationGenerationJobStatus.ofWireName(rs.getString("status")),
            failureCode = rs.getString("failure_code")?.let(IllustrationGenerationJobFailureCode::ofWireName),
            executionId = rs.getObject("provider_execution_id", UUID::class.java),
            providerStartedAt = rs.getObject("provider_started_at", OffsetDateTime::class.java)?.toInstant(),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val NOT_CHARGED = "not_charged"
        const val ACQUIRE_LOCK_KEY: Long = 7_218_930_647L

        val JOB_COLUMNS =
            """
            id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
            suggestion_result_id, suggestion_id, analysis_version, configuration_revision,
            based_on_content_revision, input_fingerprint, input_payload_encrypted, encryption_scheme, key_version,
            reserved_credits, status, failure_code, provider_execution_id, provider_started_at, created_at, updated_at
            """.trimIndent()

        val CONTEXT_SQL =
            """
            SELECT d.workspace_id, d.id AS document_id, c.id AS conversion_id,
                   c.content_revision, c.status AS conversion_status
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
            """.trimIndent()

        val LOCK_CONTEXT_SQL = "$CONTEXT_SQL FOR NO KEY UPDATE OF c"

        val LOCK_DOCUMENT_SQL =
            """
            SELECT d.id FROM documents d
            JOIN conversions c ON c.document_id = d.id
            WHERE c.id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
            FOR KEY SHARE OF d
            """.trimIndent()

        val OWNED_REQUEST_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_generation_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND request_id = :requestId"
        val OWNED_ID_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_generation_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND id = :jobId"
        val ACTIVE_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_generation_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND status IN ('queued','running') ORDER BY created_at DESC LIMIT 1"
        val LATEST_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_generation_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId ORDER BY created_at DESC LIMIT 1"

        val INSERT_SQL =
            """
            INSERT INTO illustration_generation_jobs
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 suggestion_result_id, suggestion_id, analysis_version, configuration_revision,
                 expected_content_revision, based_on_content_revision, input_fingerprint,
                 input_payload_encrypted, encryption_scheme, key_version,
                 status, reserved_credits, settlement, created_at, updated_at)
            VALUES
                (:id, :requestId, :ownerId, :workspaceId, :documentId, :conversionId,
                 :suggestionResultId, :suggestionId, :analysisVersion, :configurationRevision,
                 :revision, :revision, :fingerprint, :input, :scheme, :keyVersion,
                 :status, :reservedCredits, :settlement, :createdAt, :updatedAt)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        val ACQUIRE_EXPIRED_SQL =
            """
            WITH picked AS MATERIALIZED (
                SELECT id FROM illustration_generation_jobs
                WHERE status = 'running' AND lease_until < now()
                ORDER BY lease_until, id FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE illustration_generation_jobs AS job
            SET lease_owner = :owner, lease_until = now() + (:leaseSeconds * INTERVAL '1 second'),
                attempts = attempts + 1, updated_at = now()
            FROM picked WHERE job.id = picked.id
            RETURNING job.id, job.attempts, job.provider_started_at
            """.trimIndent()

        val ACQUIRE_QUEUED_SQL =
            """
            WITH picked AS MATERIALIZED (
                SELECT id FROM illustration_generation_jobs
                WHERE status = 'queued' ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
            ), free_slot AS MATERIALIZED (
                SELECT slot FROM generate_series(1, 2) AS slot
                WHERE NOT EXISTS (
                    SELECT 1 FROM illustration_generation_jobs running
                    WHERE running.status = 'running' AND running.worker_slot = slot
                ) ORDER BY slot LIMIT 1
            )
            UPDATE illustration_generation_jobs AS job
            SET status = 'running', lease_owner = :owner,
                lease_until = now() + (:leaseSeconds * INTERVAL '1 second'),
                worker_slot = free_slot.slot, attempts = attempts + 1, updated_at = now()
            FROM picked, free_slot WHERE job.id = picked.id
            RETURNING job.id, job.attempts
            """.trimIndent()

        val LOCK_HELD_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_generation_jobs WHERE id = :jobId AND status = 'running' " +
                "AND lease_owner = :owner AND attempts = :fence FOR UPDATE"

        val HAS_CURRENT_INPUT_SQL =
            """
            SELECT true
            FROM illustration_generation_jobs job
            JOIN conversions c ON c.id = job.conversion_id
            JOIN documents d ON d.id = c.document_id
            WHERE job.id = :jobId AND job.status = 'running'
              AND job.lease_owner = :owner AND job.attempts = :fence
              AND c.content_revision = :revision AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
              AND job.suggestion_result_id = :suggestionResultId
              AND (SELECT r.id FROM illustration_suggestion_results r
                   WHERE r.conversion_id = c.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) = :suggestionResultId
            FOR NO KEY UPDATE OF c
            """.trimIndent()

        val MARK_STARTED_SQL =
            """
            UPDATE illustration_generation_jobs
            SET provider_attempts = 1, provider_execution_id = :executionId,
                provider_started_at = :startedAt, updated_at = :startedAt
            WHERE id = :jobId AND status = 'running' AND lease_owner = :owner
              AND attempts = :fence AND provider_attempts = 0
            """.trimIndent()

        val FINISH_SQL =
            """
            UPDATE illustration_generation_jobs
            SET status = :status,
                settlement = CASE WHEN reserved_credits = 0 THEN '$NOT_CHARGED' ELSE :settlement END,
                failure_code = :failureCode,
                lease_owner = NULL, lease_until = NULL, worker_slot = NULL, updated_at = :updatedAt
            WHERE id = :jobId AND status = 'running' AND lease_owner = :owner AND attempts = :fence
            """.trimIndent()
    }
}
