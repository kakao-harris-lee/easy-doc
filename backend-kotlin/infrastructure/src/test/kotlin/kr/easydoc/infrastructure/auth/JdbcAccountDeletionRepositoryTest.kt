package kr.easydoc.infrastructure.auth

import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.DerivedRows
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * 회원 탈퇴 저장소 — 실제 PostgreSQL에서만 잴 수 있는 것들(FK CASCADE·NO ACTION 통과 여부,
 * `llm_calls` SET NULL). 계획 `docs/plans/2026-09-09-account-deletion.md` §4 수용 기준
 * 5·6·7.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAccountDeletionRepositoryTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbcClient: JdbcClient
    private lateinit var repository: JdbcAccountDeletionRepository
    private lateinit var users: JdbcUserRepository

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("account_deletion_repository")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbcClient = JdbcClient.create(dataSource())
        repository = JdbcAccountDeletionRepository(jdbcClient)
        users = JdbcUserRepository(jdbcClient)
    }

    /** 수용 기준 9 — 탈퇴한 이메일로 다시 가입할 수 있다(계획 §2 결정 8, 재가입은 막지 않는다). */
    @Test
    @DisplayName("탈퇴한 이메일로 다시 가입할 수 있다")
    fun `탈퇴한 이메일로 재가입할 수 있다`() {
        val email = "reuse-${uniqueSuffix()}@example.test"
        val first = users.create(email, HASH)

        repository.deleteConversionFeedback(first.id)
        repository.deleteUser(first.id)

        assertThatCode { users.create(email, HASH) }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName("lockForDeletion 은 관리자 여부·비밀번호 해시를 읽는다")
    fun `lockForDeletion 은 상태를 읽는다`() {
        val passwordUser = insertUser(isAdmin = false, passwordHash = HASH)
        val socialUser = insertUser(isAdmin = false, passwordHash = null)
        val admin = insertUser(isAdmin = true, passwordHash = null)

        assertThat(repository.lockForDeletion(passwordUser)?.isAdmin).isFalse()
        assertThat(repository.lockForDeletion(passwordUser)?.passwordHash).isEqualTo(HASH)
        assertThat(repository.lockForDeletion(socialUser)?.hasPassword).isFalse()
        assertThat(repository.lockForDeletion(admin)?.isAdmin).isTrue()
    }

    @Test
    @DisplayName("lockForDeletion 은 없는 사용자에 null 이다")
    fun `없는 사용자는 null 이다`() {
        assertThat(repository.lockForDeletion(UUID.randomUUID())).isNull()
    }

    @Test
    @DisplayName("pendingInvoiceRequestIds 는 그 사용자의 requested 상태만, 다른 사용자·다른 상태를 섞지 않는다")
    fun `처리 대기 요청만 돌려준다`() {
        val userId = insertUser(isAdmin = false, passwordHash = null)
        val stranger = insertUser(isAdmin = false, passwordHash = null)
        val workspaceId = insertWorkspace(userId)
        val strangerWorkspaceId = insertWorkspace(stranger)

        val requested = insertInvoiceRequest(userId, workspaceId, status = "requested")
        insertInvoiceRequest(userId, workspaceId, status = "issued")
        insertInvoiceRequest(stranger, strangerWorkspaceId, status = "requested")

        assertThat(repository.pendingInvoiceRequestIds(userId)).containsExactly(requested)
    }

    @Test
    @DisplayName("deleteConversionFeedback 은 그 사용자 변환의 피드백만 지운다 — 타인 것은 남는다")
    fun `피드백 삭제가 소유 범위로 좁혀진다`() {
        val userId = insertUser(isAdmin = false, passwordHash = null)
        val stranger = insertUser(isAdmin = false, passwordHash = null)
        val workspaceId = insertWorkspace(userId)
        val strangerWorkspaceId = insertWorkspace(stranger)
        val documentId = insertDocument(userId, workspaceId)
        val strangerDocumentId = insertDocument(stranger, strangerWorkspaceId)
        val conversionId = insertConversion(documentId)
        val strangerConversionId = insertConversion(strangerDocumentId)
        insertFeedback(conversionId, userId)
        insertFeedback(strangerConversionId, stranger)

        repository.deleteConversionFeedback(userId)

        assertThat(feedbackCount(conversionId)).isZero()
        assertThat(feedbackCount(strangerConversionId)).isEqualTo(1)
    }

    /** 수용 기준 5·6·7 — 문서가 있는 계정도 탈퇴되고, 파기 범위가 정확하다. */
    @Test
    @DisplayName("문서 있는 계정을 탈퇴하면 CASCADE 대상이 0건, llm_calls 는 user_id 만 null 로 남는다")
    fun `탈퇴가 파기 범위를 정확히 지킨다`() {
        val userId = insertUser(isAdmin = false, passwordHash = HASH)
        val workspaceId = insertWorkspace(userId)
        val documentId = insertDocument(userId, workspaceId)
        val conversionId = insertConversion(documentId)
        insertFeedback(conversionId, userId)
        insertUserIdentity(userId)
        insertCreditTransaction(userId, workspaceId)
        insertInvoiceRequest(userId, workspaceId, status = "requested")
        val llmCallId = insertLlmCall(userId, workspaceId, documentId, conversionId)

        // 서비스가 하는 순서 그대로 — 피드백을 먼저, 사용자를 나중에.
        assertThatCode {
            repository.deleteConversionFeedback(userId)
            repository.deleteUser(userId)
        }.doesNotThrowAnyException()

        assertThat(countWhere("documents", "user_id", userId)).isZero()
        assertThat(countWhere("conversions", "document_id", documentId)).isZero()
        assertThat(countWhere("workspaces", "user_id", userId)).isZero()
        assertThat(countWhere("credit_transactions", "owner_user_id", userId)).isZero()
        assertThat(countWhere("invoice_requests", "owner_user_id", userId)).isZero()
        assertThat(countWhere("user_identities", "user_id", userId)).isZero()
        assertThat(feedbackCount(conversionId)).isZero()
        assertThat(countWhere("users", "id", userId)).isZero()

        // llm_calls 행 자체는 남고 user_id 만 null 이다(V19, SET NULL).
        val remaining =
            jdbcClient
                .sql("SELECT user_id FROM llm_calls WHERE id = :id")
                .param("id", llmCallId)
                .query { rs, _ -> rs.getObject("user_id") }
                .list()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single()).isNull()
    }

    /**
     * 활성 행동 안내 작업(`action_guide_jobs` queued/reserved)이 있는 계정의 탈퇴 — `users`
     * 삭제 CASCADE 가 `documents` 를 지나갈 때 V29 의 BEFORE DELETE trigger 가 해제 거래를
     * 적는다. 그 거래의 `workspace_id`·`owner_user_id` 가 **같은 문장에서 이미 사라진 뒤**면
     * FK 위반으로 탈퇴 전체가 실패한다 — 문서를 사용자보다 먼저 지워야 하는 이유다.
     */
    @Test
    @DisplayName("대기 중 행동 안내 작업이 있어도 탈퇴되고 예약이 해제된다")
    fun `대기 중 행동 안내 작업이 있어도 탈퇴된다`() {
        val userId = insertUser(isAdmin = false, passwordHash = HASH)
        val workspaceId = insertWorkspace(userId)
        insertCreditAccount(workspaceId, reserved = RESERVED_CREDITS)
        val documentId = insertDocument(userId, workspaceId)
        val conversionId = insertConversion(documentId)
        DerivedRows.requireNonEmptyCensus()
        DerivedRows.seed(dataSource(), userId, workspaceId, documentId, conversionId)
        val jobId = insertActionGuideJob(userId, workspaceId, documentId, conversionId, running = false)
        // 픽스처가 운영과 같은 상태인지 먼저 못박는다 — 활성 작업에는 예약 원장 한 행이 있다.
        assertThat(countWhere("credit_transactions", "owner_user_id", userId)).isEqualTo(1)
        assertThat(derivedCounts(documentId, conversionId))
            .withFailMessage("파생 행을 심지 못한 표가 있다 — 아래 0건 단언이 아무것도 재지 못한다")
            .isEqualTo(everyDerivedTable(1))

        // 서비스가 하는 순서 그대로 — 피드백을 먼저, 사용자를 나중에.
        assertThatCode {
            repository.deleteConversionFeedback(userId)
            repository.deleteUser(userId)
        }.doesNotThrowAnyException()

        assertThat(countWhere("users", "id", userId)).isZero()
        assertThat(countWhere("workspaces", "user_id", userId)).isZero()
        assertThat(countWhere("documents", "user_id", userId)).isZero()
        assertThat(countWhere("conversions", "document_id", documentId)).isZero()
        assertThat(derivedCounts(documentId, conversionId))
            .withFailMessage("V28~V34 파생 행이 탈퇴 뒤에도 남았다 — 파기 범위가 새고 있다")
            .isEqualTo(everyDerivedTable(0))
        // 해제 거래는 trigger 가 적고, 곧이어 `users` CASCADE 로 함께 사라진다.
        assertThat(countWhere("credit_transactions", "owner_user_id", userId)).isZero()
        // 작업 행 자체는 설계상 남는다(FK 가 SET NULL) — 정산 상태만 종결로 바뀐다.
        assertThat(jobStateOf(jobId)).isEqualTo("superseded|released")
        assertThat(jobOwnerAndWorkspaceOf(jobId)).containsExactly(null, null)
    }

    /**
     * 실행 중(`running`) 작업은 해제 거래에 더해 진행 중 호출 원장을 `outcome_unknown` 으로
     * 정리한다(V29 함수의 마지막 두 문장). 그 정리도 문서 삭제 시점에 일어나야 한다.
     */
    @Test
    @DisplayName("실행 중 행동 안내 작업이 있어도 탈퇴되고 진행 중 호출이 outcome_unknown 이 된다")
    fun `실행 중 행동 안내 작업이 있어도 탈퇴된다`() {
        val userId = insertUser(isAdmin = false, passwordHash = HASH)
        val workspaceId = insertWorkspace(userId)
        insertCreditAccount(workspaceId, reserved = RESERVED_CREDITS)
        val documentId = insertDocument(userId, workspaceId)
        val conversionId = insertConversion(documentId)
        val jobId = insertActionGuideJob(userId, workspaceId, documentId, conversionId, running = true)
        val llmCallId = insertInProgressActionGuideLlmCall(userId, workspaceId, documentId, conversionId, jobId)

        assertThatCode {
            repository.deleteConversionFeedback(userId)
            repository.deleteUser(userId)
        }.doesNotThrowAnyException()

        assertThat(countWhere("users", "id", userId)).isZero()
        assertThat(countWhere("documents", "user_id", userId)).isZero()
        assertThat(countWhere("credit_transactions", "owner_user_id", userId)).isZero()
        assertThat(jobStateOf(jobId)).isEqualTo("superseded|released")

        // 호출 원장 행은 남고(V14 청구 근거) outcome 만 정리되며 user_id 는 null 이다(V19).
        val call =
            jdbcClient
                .sql("SELECT outcome, user_id FROM llm_calls WHERE id = :id")
                .param("id", llmCallId)
                .query { rs, _ -> rs.getString("outcome") to rs.getObject("user_id") }
                .single()
        assertThat(call.first).isEqualTo("outcome_unknown")
        assertThat(call.second).isNull()
    }

    /**
     * 수용 기준 5·6·7 의 V28~V34 확장 — 검수 지원(V28)·행동 안내 후보와 안내문(V30)·표
     * 구조(V32)·검수 이력(V33)·그림 배치(V34)는 전부 `documents`/`conversions` 에
     * `ON DELETE CASCADE` 로 매달려 있으니 탈퇴 한 번으로 사라져야 한다.
     *
     * 활성 작업이 없는 갈래를 여기서 잰다 — [DerivedRows.seed] 가 심는 작업은 끝난 상태다.
     * 활성(`queued`/`running`) 작업을 쥔 계정의 탈퇴는 위 두 시험이 따로 잰다.
     */
    @Test
    @DisplayName("문서 있는 계정을 탈퇴하면 V28~V34 파생 행이 전부 0건이 된다")
    fun `탈퇴가 V28부터 V34까지의 파생 행을 지운다`() {
        val userId = insertUser(isAdmin = false, passwordHash = HASH)
        val workspaceId = insertWorkspace(userId)
        val documentId = insertDocument(userId, workspaceId)
        val conversionId = insertConversion(documentId)
        insertCreditAccount(workspaceId, reserved = 0)
        DerivedRows.requireNonEmptyCensus()
        val jobId = DerivedRows.seed(dataSource(), userId, workspaceId, documentId, conversionId)
        assertThat(derivedCounts(documentId, conversionId))
            .withFailMessage("파생 행을 심지 못한 표가 있다 — 이 테스트가 아무것도 재지 못한다")
            .isEqualTo(everyDerivedTable(1))

        assertThatCode {
            repository.deleteConversionFeedback(userId)
            repository.deleteUser(userId)
        }.doesNotThrowAnyException()

        assertThat(derivedCounts(documentId, conversionId))
            .withFailMessage("파생 행이 탈퇴 뒤에도 남았다 — 파기 범위가 새고 있다")
            .isEqualTo(everyDerivedTable(0))
        assertThat(countWhere("users", "id", userId)).isZero()
        assertThat(countWhere("documents", "user_id", userId)).isZero()
        assertThat(countWhere("action_guide_jobs", "id", jobId))
            .withFailMessage("작업 감사행이 사라졌다 — FK 를 일부러 두지 않은 청구 근거다(V29)")
            .isEqualTo(1)
        assertThat(jobOwnerAndWorkspaceOf(jobId))
            .withFailMessage("남은 작업 감사행이 아직 탈퇴한 사용자·작업 공간을 가리킨다 — V29 의 SET NULL 이 닿지 않았다")
            .containsExactly(null, null)
        assertThat(countWhere("credit_transactions", "owner_user_id", userId))
            .withFailMessage("탈퇴한 사용자를 가리키는 거래가 남았다 — FK CASCADE 가 닿지 않았다")
            .isZero()
        assertThat(countWhere("workspace_credit_accounts", "workspace_id", workspaceId))
            .withFailMessage("작업 공간 크레딧 계정이 남았다 — V15 의 CASCADE 가 닿지 않았다")
            .isZero()
    }

    /** [DerivedRows.CENSUS] 의 「표 이름 → 남은 행 수」. */
    private fun derivedCounts(
        documentId: UUID,
        conversionId: UUID,
    ): Map<String, Int> = DerivedRows.counts(dataSource(), documentId, conversionId)

    /** 모든 파생 표가 [rows] 행씩인 기대값 — 어긋난 표 이름이 실패 메시지에 그대로 나온다. */
    private fun everyDerivedTable(rows: Int): Map<String, Int> =
        DerivedRows.CENSUS.associate { (table, _) -> table to rows }

    @Test
    @DisplayName("소셜 전용 계정(비밀번호 없음)도 탈퇴된다")
    fun `비밀번호 없는 계정도 탈퇴된다`() {
        val userId = insertUser(isAdmin = false, passwordHash = null)

        repository.deleteConversionFeedback(userId)
        repository.deleteUser(userId)

        assertThat(countWhere("users", "id", userId)).isZero()
    }

    /** `action_guide_jobs` 의 종결 상태 — 상태와 정산을 한 값으로 읽는다. */
    private fun jobStateOf(jobId: UUID): String =
        jdbcClient
            .sql("SELECT status || '|' || settlement FROM action_guide_jobs WHERE id = :id")
            .param("id", jobId)
            .query { rs, _ -> rs.getString(1) }
            .single()

    /** 탈퇴 뒤 작업 행에 남는 소유 연결 — 둘 다 `SET NULL` 이라 끊겨야 한다. */
    private fun jobOwnerAndWorkspaceOf(jobId: UUID): List<Any?> =
        jdbcClient
            .sql("SELECT owner_user_id, workspace_id FROM action_guide_jobs WHERE id = :id")
            .param("id", jobId)
            .query { rs, _ -> listOf(rs.getObject("owner_user_id"), rs.getObject("workspace_id")) }
            .single()

    private fun insertCreditAccount(
        workspaceId: UUID,
        reserved: Int,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved)
                VALUES (:workspaceId, 10, :reserved)
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("reserved", reserved)
            .update()
    }

    /**
     * 활성(`queued`/`running`) 행동 안내 작업 한 건 — 운영과 같이 예약 원장 행을 함께 남긴다
     * (`JdbcActionGuideCreditPort.reserve`, `uq_credit_transactions_action_guide_reserve`).
     * `running` 은 lease·worker slot·provider 시작 시각이 함께 있어야 V29 의 CHECK 제약을
     * 통과한다. `uq_action_guide_jobs_active_owner` 때문에 한 사용자에게 활성 작업은 동시에
     * 하나뿐이라 두 상태를 각각 다른 시험에서 잰다.
     */
    private fun insertActionGuideJob(
        userId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
        running: Boolean,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO action_guide_jobs
                    (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                     expected_content_revision, based_on_content_revision, input_fingerprint,
                     status, settlement, reserved_credits, provider_attempts, provider_execution_id,
                     provider_started_at, lease_owner, lease_until, worker_slot)
                VALUES (:id, :requestId, :userId, :workspaceId, :documentId, :conversionId,
                        1, 1, :fingerprint, :status, 'reserved', :reservedCredits, :providerAttempts,
                        :providerExecutionId, :providerStartedAt, :leaseOwner, :leaseUntil, :workerSlot)
                """.trimIndent(),
            ).param("id", id)
            .param("requestId", UUID.randomUUID())
            .param("userId", userId)
            .param("workspaceId", workspaceId)
            .param("documentId", documentId)
            .param("conversionId", conversionId)
            .param("fingerprint", "0".repeat(FINGERPRINT_LENGTH))
            .param("status", if (running) "running" else "queued")
            .param("reservedCredits", RESERVED_CREDITS)
            .param("providerAttempts", if (running) 1 else 0)
            .param("providerExecutionId", if (running) UUID.randomUUID() else null)
            .param("providerStartedAt", if (running) Timestamp.from(Instant.now()) else null)
            .param("leaseOwner", if (running) "worker-fixture" else null)
            .param("leaseUntil", if (running) Timestamp.from(Instant.now().plusSeconds(LEASE_SECONDS)) else null)
            .param("workerSlot", if (running) 1 else null)
            .update()
        insertActionGuideReserveTransaction(id, userId, workspaceId, documentId)
        return id
    }

    /**
     * 예약 시점에 남는 원장 한 행 — `reserved_delta` 는 작업이 잡아 둔 양 그대로다. 삭제
     * 정산의 release 행과 짝을 이루며, 둘 다 `owner_user_id` CASCADE 로 탈퇴와 함께 사라진다.
     */
    private fun insertActionGuideReserveTransaction(
        jobId: UUID,
        userId: UUID,
        workspaceId: UUID,
        documentId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO credit_transactions
                    (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                     reserved_delta, reason, action_guide_job_id)
                VALUES (:id, :workspaceId, :userId, :documentId, 'reserve', 0, :reservedCredits,
                        'action_guide', :jobId)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("userId", userId)
            .param("documentId", documentId)
            .param("reservedCredits", RESERVED_CREDITS)
            .param("jobId", jobId)
            .update()
    }

    /**
     * 진행 중 호출 원장 한 행 — V29 의 `ck_llm_calls_unfinished_zero_usage` 때문에
     * provider·model 은 `null`, 토큰은 0 이어야 한다.
     */
    private fun insertInProgressActionGuideLlmCall(
        userId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO llm_calls
                    (id, workspace_id, user_id, document_id, conversion_id, purpose, provider, model,
                     input_tokens, output_tokens, char_count, document_char_count, outcome,
                     action_guide_job_id)
                VALUES (:id, :workspaceId, :userId, :documentId, :conversionId, 'action_guide', NULL,
                        NULL, 0, 0, 4, 4, 'in_progress', :jobId)
                """.trimIndent(),
            ).param("id", id)
            .param("workspaceId", workspaceId)
            .param("userId", userId)
            .param("documentId", documentId)
            .param("conversionId", conversionId)
            .param("jobId", jobId)
            .update()
        return id
    }

    private fun feedbackCount(conversionId: UUID): Int =
        jdbcClient
            .sql("SELECT count(*) FROM conversion_feedback WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun countWhere(
        table: String,
        column: String,
        value: UUID,
    ): Int =
        jdbcClient
            .sql("SELECT count(*) FROM $table WHERE $column = :value")
            .param("value", value)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun insertUser(
        isAdmin: Boolean,
        passwordHash: PasswordHash?,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO users (id, email, password_hash, is_admin, email_verified_at)
                VALUES (:id, :email, :passwordHash, :isAdmin, now())
                """.trimIndent(),
            ).param("id", id)
            .param("email", "account-deletion-${uniqueSuffix()}@example.test")
            .param("passwordHash", passwordHash?.reveal())
            .param("isAdmin", isAdmin)
            .update()
        return id
    }

    private fun insertWorkspace(userId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", id)
            .param("userId", userId)
            .param("name", "작업 공간 ${uniqueSuffix()}")
            .update()
        return id
    }

    private fun insertDocument(
        userId: UUID,
        workspaceId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO documents
                    (id, user_id, workspace_id, title, source_format, source_text_encrypted, char_count,
                     encryption_scheme, key_version)
                VALUES (:id, :userId, :workspaceId, 'fixture', 'docx', :bytes, 1, :scheme, 1)
                """.trimIndent(),
            ).param("id", id)
            .param("userId", userId)
            .param("workspaceId", workspaceId)
            .param("bytes", byteArrayOf(0))
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
        return id
    }

    private fun insertConversion(documentId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO conversions (id, document_id, encryption_scheme, key_version)
                VALUES (:id, :documentId, :scheme, 1)
                """.trimIndent(),
            ).param("id", id)
            .param("documentId", documentId)
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
        return id
    }

    private fun insertFeedback(
        conversionId: UUID,
        userId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO conversion_feedback (conversion_id, user_id, publish_intent, quality_score, minutes_spent)
                VALUES (:conversionId, :userId, 'as_is', 5, 3)
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("userId", userId)
            .update()
    }

    private fun insertUserIdentity(userId: UUID) {
        jdbcClient
            .sql(
                """
                INSERT INTO user_identities (id, user_id, provider, provider_user_id, email_verified)
                VALUES (:id, :userId, 'google', :providerUserId, true)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("userId", userId)
            .param("providerUserId", "provider-${uniqueSuffix()}")
            .update()
    }

    private fun insertCreditTransaction(
        userId: UUID,
        workspaceId: UUID,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO credit_transactions (id, workspace_id, owner_user_id, kind, reason)
                VALUES (:id, :workspaceId, :userId, 'grant', 'signup')
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("workspaceId", workspaceId)
            .param("userId", userId)
            .update()
    }

    private fun insertInvoiceRequest(
        userId: UUID,
        workspaceId: UUID,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO invoice_requests
                    (id, workspace_id, owner_user_id, business_number, company_name, contact_email,
                     period_from, period_to, status)
                VALUES (:id, :workspaceId, :userId, '1234567890', '상호', 'ops@example.test',
                        '2026-08-01', '2026-08-31', :status)
                """.trimIndent(),
            ).param("id", id)
            .param("workspaceId", workspaceId)
            .param("userId", userId)
            .param("status", status)
            .update()
        return id
    }

    private fun insertLlmCall(
        userId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO llm_calls
                    (id, workspace_id, user_id, document_id, conversion_id, purpose, provider, model,
                     input_tokens, output_tokens, char_count, document_char_count)
                VALUES (:id, :workspaceId, :userId, :documentId, :conversionId, 'convert', 'anthropic',
                        'claude', 10, 10, 4, 4)
                """.trimIndent(),
            ).param("id", id)
            .param("workspaceId", workspaceId)
            .param("userId", userId)
            .param("documentId", documentId)
            .param("conversionId", conversionId)
            .update()
        return id
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun uniqueSuffix(): Int = counter++

    private companion object {
        val HASH = PasswordHash("\$argon2id\$v=19\$m=65536,t=3,p=4\$YWJjZGVmZ2hpamtsbW5vcA\$c3RvcmVkLWhhc2g")

        /** 활성 작업이 잡아 둔 예약량 — 작업 행·예약 원장·계정 `reserved` 가 같은 값을 쓴다. */
        const val RESERVED_CREDITS = 2

        /** `ck_action_guide_jobs_fingerprint_length` — 정확히 64자여야 한다. */
        const val FINGERPRINT_LENGTH = 64

        const val LEASE_SECONDS = 60L

        var counter = 0
    }
}
