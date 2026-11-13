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

    /**
     * 접수 경로 — 변환 행을 잠근 **뒤에** 문서 행을 `FOR KEY SHARE SKIP LOCKED` 로 잡는다.
     *
     * **변환이 먼저인 이유는** 접수가 문서 행을 쥔 채 변환 행을 기다리면 안 되기 때문이고
     * (이슈 #219), **문서를 건너뛰며 잡는 이유는 삭제의 실질 순서가 그 반대이기 때문이다.**
     * `DocumentService.delete` 가 변환 행에 닿는 길인 `lockPendingReservation` 은 예약이 남은
     * `pending`·`processing` 변환만 고른다. 접수가 요구하는 `done` 변환에서는 한 행도 고르지
     * 않으므로 삭제는 FK CASCADE(`conversions.document_id ON DELETE CASCADE`)로만 변환 행에
     * 닿고, 그것은 문서 행 **뒤**다 — 삭제가 문서→변환이 된다. 여기서 문서 행을 기다리면 그
     * 사이클이 닫히지만, 건너뛰면 기다리지 않는다.
     *
     * 건너뛰는 것이 타협이 아니라 **옳은 답**이다. `FOR KEY SHARE` 는 `FOR UPDATE`·키 갱신·삭제
     * 와만 충돌한다. 제품 코드의 유일한 `FOR UPDATE OF d` 는 보존 만료 파기(그 자신도
     * `SKIP LOCKED`)이고, 변환 worker 의 `FOR NO KEY UPDATE OF c, d` 는 충돌하지 않으며 보통의
     * 문서 갱신은 키 갱신이 아니다. 그러니 건너뛴 행은 **지금 지워지는 중인 문서**이고,
     * 호출자의 `null` → 404 가 사용자에게 보여야 할 바로 그 답이다.
     *
     * 접수가 **먼저** 닿았을 때 문서 행을 잡는 의미는 그대로다. 뒤이어 오는 삭제가 이 트랜잭션을
     * 기다리므로 BEFORE DELETE 트리거(`settle_document_jobs_before_delete`)가 지금 삽입 중인
     * 작업까지 함께 정산한다 — 작업 행에는 문서로 가는 외래키가 없어, 중간에 끼어든 삭제는 **지금
     * 접수 중인 작업만** 정산에서 빠뜨려 예약을 영원히 묶어 둔다.
     *
     * 두 잠금 모두 트랜잭션과 함께 풀리므로 **반드시 트랜잭션 안에서 부른다.**
     */
    override fun lockOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionJobContext? {
        val context = ownedContext(LOCK_CONTEXT_SQL, ownerId, conversionId) ?: return null
        return if (lockDocument(SKIP_LOCKED_DOCUMENT_SQL, ownerId, conversionId)) context else null
    }

    /**
     * 같은 스냅샷을 변환 행 잠금 없이, **문서 행만** `FOR KEY SHARE` 로 잡아 읽는다 — 삭제와의
     * 직렬화는 그 문서 잠금이 지킨다.
     *
     * 읽기는 변환 행을 잠그지 않으므로 [lockOwnedContext] 가 설명한 순환에 끼지 못한다 —
     * 문서 행을 기다릴 뿐 아무도 이 트랜잭션을 기다리지 않는다.
     */
    override fun readOwnedContext(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionJobContext? {
        if (!lockDocument(LOCK_DOCUMENT_SQL, ownerId, conversionId)) return null
        return ownedContext(CONTEXT_SQL, ownerId, conversionId)
    }

    private fun lockDocument(
        sql: String,
        ownerId: UUID,
        conversionId: UUID,
    ): Boolean =
        jdbc
            .sql(sql)
            .param("ownerId", ownerId)
            .param("conversionId", conversionId)
            .query { _, _ -> true }
            .optional()
            .orElse(false)

    private fun ownedContext(
        sql: String,
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionJobContext? =
        jdbc
            .sql(sql)
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

        val CONTEXT_SQL =
            """
            SELECT d.workspace_id, d.id AS document_id, c.id AS conversion_id,
                   c.content_revision, c.status AS conversion_status, d.char_count
            FROM conversions c
            JOIN documents d ON d.id = c.document_id
            WHERE c.id = :conversionId
              AND d.user_id = :ownerId
              AND d.retention_expires_at > now()
            """.trimIndent()

        val LOCK_CONTEXT_SQL = "$CONTEXT_SQL FOR NO KEY UPDATE OF c"

        val LOCK_DOCUMENT_SQL =
            """
            SELECT d.id FROM documents d
            JOIN conversions c ON c.document_id = d.id
            WHERE c.id = :conversionId AND d.user_id = :ownerId AND d.retention_expires_at > now()
            FOR KEY SHARE OF d
            """.trimIndent()

        /** 접수의 문서 문장 — 삭제 뒤에서 기다리지 않는다([lockOwnedContext]). */
        val SKIP_LOCKED_DOCUMENT_SQL = "$LOCK_DOCUMENT_SQL SKIP LOCKED"

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
