package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobAcquire
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobContext
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobInsert
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobLease
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRepository
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobFailureCode
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import org.springframework.jdbc.core.simple.JdbcClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * PostgreSQL 한 트랜잭션에서 접수 멱등성과 worker fencing 을 지키는 R7 ER-17 저장소
 * (V35, 구조는 `JdbcActionGuideJobRepository` 와 같다).
 *
 * R2 와 다른 점 둘.
 * ⑴ 문서당 provider 시작 상한이 코드 상수가 아니라 **구성값**이다
 *    (`easydoc.illustration-suggestions.max-provider-attempts-per-conversion`, 명세 §2).
 * ⑵ 예약이 0 인 작업의 정산 값은 `not_charged` 로 고정이라 종결 쓰기가 그 값을 보존한다 —
 *    「예약이 없다」를 「반환했다」로 덮어쓰면 원장 대조가 두 경우를 구분하지 못한다.
 */
@Suppress("TooManyFunctions")
class JdbcIllustrationSuggestionJobRepository(
    private val jdbc: JdbcClient,
    private val maxProviderAttemptsPerConversion: Int,
) : IllustrationSuggestionJobRepository {
    init {
        require(maxProviderAttemptsPerConversion >= 1) {
            "문서당 provider 시도 상한은 1 이상이어야 합니다: $maxProviderAttemptsPerConversion"
        }
    }

    override fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionJobContext? =
        jdbc
            .sql(LOCK_CONTEXT_SQL)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .query { rs, _ ->
                IllustrationSuggestionJobContext(
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    conversionId = rs.getObject("conversion_id", UUID::class.java),
                    contentRevision = rs.getLong("content_revision"),
                    charCount = rs.getInt("char_count"),
                    completed = rs.getString("conversion_status") == "done",
                )
            }.optional()
            .orElse(null)

    override fun findByRequestId(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): StoredIllustrationSuggestionJob? = findOne(OWNED_REQUEST_SQL, ownerId, conversionId, requestId = requestId)

    override fun insert(job: StoredIllustrationSuggestionJob): IllustrationSuggestionJobInsert {
        // 문서당 상한은 provider 호출이 실제로 시작된 작업만 센다 — 시작하지 않은 실패는 시도가 아니다.
        val attempts =
            jdbc
                .sql(STARTED_ATTEMPTS_SQL)
                .param("ownerId", job.ownerId)
                .param("conversionId", job.conversionId)
                .query { rs, _ -> rs.getInt(1) }
                .single()
        if (attempts >= maxProviderAttemptsPerConversion) return IllustrationSuggestionJobInsert.AttemptLimit

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
                .param("fingerprint", fingerprint(job.basedOnContentRevision))
                .param("status", job.status.wireName)
                .param("reservedCredits", job.reservedCredits)
                .param("settlement", settlementFor(job))
                .param("createdAt", utc(job.createdAt))
                .param("updatedAt", utc(job.updatedAt))
                .update()
        return if (inserted == 1) {
            IllustrationSuggestionJobInsert.Inserted(job)
        } else {
            if (findByRequestId(job.ownerId, job.conversionId, job.requestId) != null) {
                IllustrationSuggestionJobInsert.RequestConflict
            } else {
                IllustrationSuggestionJobInsert.ActiveConflict
            }
        }
    }

    override fun findOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredIllustrationSuggestionJob? = findOne(OWNED_ID_SQL, ownerId, conversionId, jobId = jobId)

    override fun findActiveOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionJob? = findOne(ACTIVE_SQL, ownerId, conversionId)

    override fun findLatestOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredIllustrationSuggestionJob? = findOne(LATEST_SQL, ownerId, conversionId)

    override fun acquire(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationSuggestionJobAcquire {
        // 두 replica가 같은 free slot을 동시에 보지 않게 획득 결정만 짧게 직렬화한다.
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:key)")
            .param("key", ACQUIRE_LOCK_KEY)
            .query { _, _ -> true }
            .single()
        acquireExpired(owner, leaseDuration, maxLeaseAttempts)?.let { return it }
        return acquireQueued(owner, leaseDuration) ?: IllustrationSuggestionJobAcquire.Empty
    }

    override fun lockIfHeld(lease: IllustrationSuggestionJobLease): StoredIllustrationSuggestionJob? =
        jdbc
            .sql(LOCK_HELD_SQL)
            .param("jobId", lease.jobId)
            .param("owner", lease.owner)
            .param("fence", lease.fence)
            .query(::mapJob)
            .optional()
            .orElse(null)

    override fun hasCurrentInput(
        lease: IllustrationSuggestionJobLease,
        basedOnContentRevision: Long,
    ): Boolean {
        val ownerId =
            jdbc
                .sql("SELECT owner_user_id FROM illustration_suggestion_jobs WHERE id = :jobId")
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
        lease: IllustrationSuggestionJobLease,
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
        lease: IllustrationSuggestionJobLease,
        updatedAt: Instant,
    ): Boolean = finish(lease, IllustrationSuggestionJobStatus.SUCCEEDED, null, "consumed", updatedAt)

    override fun markFailed(
        lease: IllustrationSuggestionJobLease,
        failureCode: IllustrationSuggestionJobFailureCode,
        updatedAt: Instant,
    ): Boolean = finish(lease, IllustrationSuggestionJobStatus.FAILED, failureCode, "released", updatedAt)

    override fun markSuperseded(
        lease: IllustrationSuggestionJobLease,
        updatedAt: Instant,
    ): Boolean = finish(lease, IllustrationSuggestionJobStatus.SUPERSEDED, null, "released", updatedAt)

    /** 시작한 호출의 결과는 알 수 없으므로, 불명확 회수가 리스 재획득 상한보다 우선한다. */
    private fun acquireExpired(
        owner: String,
        leaseDuration: Duration,
        maxLeaseAttempts: Int,
    ): IllustrationSuggestionJobAcquire? =
        jdbc
            .sql(ACQUIRE_EXPIRED_SQL)
            .param("owner", owner)
            .param("leaseSeconds", leaseDuration.seconds)
            .query { rs, _ ->
                val attempts = rs.getInt("attempts")
                val lease = IllustrationSuggestionJobLease(rs.getObject("id", UUID::class.java), owner, attempts)
                when {
                    rs.getObject("provider_started_at") != null -> {
                        IllustrationSuggestionJobAcquire.RecoverUnknown(lease)
                    }

                    attempts > maxLeaseAttempts -> {
                        IllustrationSuggestionJobAcquire.DeadLettered(lease)
                    }

                    else -> {
                        IllustrationSuggestionJobAcquire.Held(lease)
                    }
                }
            }.optional()
            .orElse(null)

    private fun acquireQueued(
        owner: String,
        leaseDuration: Duration,
    ): IllustrationSuggestionJobAcquire? =
        jdbc
            .sql(ACQUIRE_QUEUED_SQL)
            .param("owner", owner)
            .param("leaseSeconds", leaseDuration.seconds)
            .query { rs, _ ->
                IllustrationSuggestionJobAcquire.Held(
                    IllustrationSuggestionJobLease(
                        rs.getObject("id", UUID::class.java),
                        owner,
                        rs.getInt("attempts"),
                    ),
                )
            }.optional()
            .orElse(null)

    private fun finish(
        lease: IllustrationSuggestionJobLease,
        status: IllustrationSuggestionJobStatus,
        failureCode: IllustrationSuggestionJobFailureCode?,
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
    ): StoredIllustrationSuggestionJob? {
        var statement = jdbc.sql(sql).param("ownerId", ownerId).param("conversionId", conversionId)
        requestId?.let { statement = statement.param("requestId", it) }
        jobId?.let { statement = statement.param("jobId", it) }
        return statement.query(::mapJob).optional().orElse(null)
    }

    private fun mapJob(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): StoredIllustrationSuggestionJob =
        StoredIllustrationSuggestionJob(
            jobId = rs.getObject("id", UUID::class.java),
            ownerId = rs.getObject("owner_user_id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            requestId = rs.getObject("request_id", UUID::class.java),
            basedOnContentRevision = rs.getLong("based_on_content_revision"),
            reservedCredits = rs.getBigDecimal("reserved_credits"),
            status = IllustrationSuggestionJobStatus.ofWireName(rs.getString("status")),
            failureCode = rs.getString("failure_code")?.let(IllustrationSuggestionJobFailureCode::ofWireName),
            executionId = rs.getObject("provider_execution_id", UUID::class.java),
            providerStartedAt = rs.getObject("provider_started_at", OffsetDateTime::class.java)?.toInstant(),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )

    /** 예약이 0이면 정산할 것이 없다 — V35 CHECK 가 그 작업에 `not_charged` 를 요구한다. */
    private fun settlementFor(job: StoredIllustrationSuggestionJob): String =
        if (job.reservedCredits.signum() == 0) NOT_CHARGED else "reserved"

    private fun fingerprint(contentRevision: Long): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$contentRevision".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val NOT_CHARGED = "not_charged"

        /** R2 의 획득 잠금과 다른 값이어야 두 기능의 worker 가 서로를 막지 않는다. */
        const val ACQUIRE_LOCK_KEY: Long = 7_218_930_641L

        val JOB_COLUMNS =
            """
            id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
            based_on_content_revision, reserved_credits, status,
            failure_code, provider_execution_id, provider_started_at, created_at, updated_at
            """.trimIndent()

        val LOCK_CONTEXT_SQL =
            """
            SELECT d.workspace_id, d.id AS document_id, c.id AS conversion_id,
                   c.content_revision, c.status AS conversion_status, d.char_count
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = :conversionId
              AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            FOR NO KEY UPDATE OF c
            """.trimIndent()

        val STARTED_ATTEMPTS_SQL =
            """
            SELECT count(*) FROM illustration_suggestion_jobs
            WHERE owner_user_id = :ownerId AND conversion_id = :conversionId
              AND provider_started_at IS NOT NULL
            """.trimIndent()

        val OWNED_REQUEST_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_suggestion_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND request_id = :requestId"
        val OWNED_ID_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_suggestion_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND id = :jobId"
        val ACTIVE_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_suggestion_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId AND status IN ('queued','running') ORDER BY created_at DESC LIMIT 1"
        val LATEST_SQL =
            "SELECT $JOB_COLUMNS FROM illustration_suggestion_jobs WHERE owner_user_id = :ownerId " +
                "AND conversion_id = :conversionId ORDER BY created_at DESC LIMIT 1"

        val INSERT_SQL =
            """
            INSERT INTO illustration_suggestion_jobs
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, based_on_content_revision,
                 input_fingerprint, status, reserved_credits, settlement, created_at, updated_at)
            VALUES
                (:id, :requestId, :ownerId, :workspaceId, :documentId, :conversionId,
                 :expectedContentRevision, :expectedContentRevision,
                 :fingerprint, :status, :reservedCredits, :settlement, :createdAt, :updatedAt)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        val ACQUIRE_EXPIRED_SQL =
            """
            WITH picked AS MATERIALIZED (
                SELECT id FROM illustration_suggestion_jobs
                WHERE status = 'running' AND lease_until < now()
                ORDER BY lease_until, id
                FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE illustration_suggestion_jobs AS job
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
                SELECT id FROM illustration_suggestion_jobs
                WHERE status = 'queued'
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED LIMIT 1
            ), free_slot AS MATERIALIZED (
                SELECT slot FROM generate_series(1, 2) AS slot
                WHERE NOT EXISTS (
                    SELECT 1 FROM illustration_suggestion_jobs running
                    WHERE running.status = 'running' AND running.worker_slot = slot
                )
                ORDER BY slot LIMIT 1
            )
            UPDATE illustration_suggestion_jobs AS job
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
            "SELECT $JOB_COLUMNS FROM illustration_suggestion_jobs WHERE id = :jobId AND status = 'running' " +
                "AND lease_owner = :owner AND attempts = :fence FOR UPDATE"

        val HAS_CURRENT_INPUT_SQL =
            """
            SELECT true
            FROM illustration_suggestion_jobs job
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
            UPDATE illustration_suggestion_jobs
            SET provider_attempts = 1, provider_execution_id = :executionId,
                provider_started_at = :startedAt, updated_at = :startedAt
            WHERE id = :jobId AND status = 'running' AND lease_owner = :owner
              AND attempts = :fence AND provider_attempts = 0
            """.trimIndent()

        // 예약이 0인 작업은 정산 값을 바꾸지 않는다 — V35 CHECK 가 그 갈래에 not_charged 를 요구한다.
        val FINISH_SQL =
            """
            UPDATE illustration_suggestion_jobs
            SET status = :status,
                settlement = CASE WHEN reserved_credits = 0 THEN '$NOT_CHARGED' ELSE :settlement END,
                failure_code = :failureCode,
                lease_owner = NULL, lease_until = NULL, worker_slot = NULL, updated_at = :updatedAt
            WHERE id = :jobId AND status = 'running' AND lease_owner = :owner AND attempts = :fence
            """.trimIndent()
    }
}
