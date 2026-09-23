package kr.easydoc.infrastructure.auth

import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
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
import java.math.BigDecimal
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
     * 수용 기준 5·6·7 의 V28~V34 확장 — 검수 지원(V28)·행동 안내 후보와 안내문(V30)·표
     * 구조(V32)·검수 이력(V33)·그림 배치(V34)는 전부 `documents`/`conversions` 에
     * `ON DELETE CASCADE` 로 매달려 있으니 탈퇴 한 번으로 사라져야 한다.
     *
     * 행동 안내 작업은 **끝난 상태**(`succeeded`/`consumed`)로 둔다 — V29 의 문서 삭제
     * 트리거가 정산하는 대상은 `queued`·`running` 뿐이라 이 테스트가 재는 CASCADE 범위와
     * 무관하다. 활성 작업을 쥔 계정의 탈퇴는 별개의 결함이라 이 파일에서 재지 않는다
     * (PR 본문 참고).
     */
    @Test
    @DisplayName("문서 있는 계정을 탈퇴하면 V28~V34 파생 행이 전부 0건이 된다")
    fun `탈퇴가 V28부터 V34까지의 파생 행을 지운다`() {
        val userId = insertUser(isAdmin = false, passwordHash = HASH)
        val workspaceId = insertWorkspace(userId)
        val documentId = insertDocument(userId, workspaceId)
        val conversionId = insertConversion(documentId)
        insertCreditAccount(workspaceId)
        val jobId = insertSettledActionGuideJob(userId, workspaceId, documentId, conversionId)
        insertDerivedRows(userId, documentId, conversionId, jobId)
        DERIVED_TABLES.forEach { (table, column) ->
            assertThat(countWhere(table, column, if (column == DOCUMENT_ID) documentId else conversionId))
                .withFailMessage("%s 에 파생 행을 심지 못했다 — 이 테스트가 아무것도 재지 못한다", table)
                .isEqualTo(1)
        }

        assertThatCode {
            repository.deleteConversionFeedback(userId)
            repository.deleteUser(userId)
        }.doesNotThrowAnyException()

        DERIVED_TABLES.forEach { (table, column) ->
            assertThat(countWhere(table, column, if (column == DOCUMENT_ID) documentId else conversionId))
                .withFailMessage("%s 의 파생 행이 탈퇴 뒤에도 남았다 — 파기 범위가 새고 있다", table)
                .isZero()
        }
        assertThat(countWhere("users", "id", userId)).isZero()
        assertThat(countWhere("documents", "user_id", userId)).isZero()
        assertThat(countWhere("action_guide_jobs", "id", jobId))
            .withFailMessage("작업 감사행이 사라졌다 — FK 를 일부러 두지 않은 청구 근거다(V29)")
            .isEqualTo(1)
        assertThat(countWhere("credit_transactions", "owner_user_id", userId))
            .withFailMessage("탈퇴한 사용자를 가리키는 거래가 남았다 — FK CASCADE 가 닿지 않았다")
            .isZero()
        assertThat(countWhere("workspace_credit_accounts", "workspace_id", workspaceId))
            .withFailMessage("작업 공간 크레딧 계정이 남았다 — V15 의 CASCADE 가 닿지 않았다")
            .isZero()
    }

    @Test
    @DisplayName("소셜 전용 계정(비밀번호 없음)도 탈퇴된다")
    fun `비밀번호 없는 계정도 탈퇴된다`() {
        val userId = insertUser(isAdmin = false, passwordHash = null)

        repository.deleteConversionFeedback(userId)
        repository.deleteUser(userId)

        assertThat(countWhere("users", "id", userId)).isZero()
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

    private fun insertCreditAccount(workspaceId: UUID) {
        jdbcClient
            .sql(
                """
                INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved)
                VALUES (:workspaceId, :balance, :reserved)
                """.trimIndent(),
            ).param("workspaceId", workspaceId)
            .param("balance", CREDITS)
            .param("reserved", BigDecimal.ZERO)
            .update()
    }

    /** 끝난 작업 — V29 의 문서 삭제 트리거는 `queued`·`running` 만 정산한다. */
    private fun insertSettledActionGuideJob(
        userId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcClient
            .sql(
                """
                INSERT INTO action_guide_jobs
                    (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                     expected_content_revision, based_on_content_revision, input_fingerprint,
                     status, reserved_credits, settlement)
                VALUES (:id, :request, :userId, :workspaceId, :documentId, :conversionId,
                        1, 1, :fingerprint, 'succeeded', :credits, 'consumed')
                """.trimIndent(),
            ).param("id", id)
            .param("request", UUID.randomUUID())
            .param("userId", userId)
            .param("workspaceId", workspaceId)
            .param("documentId", documentId)
            .param("conversionId", conversionId)
            .param("fingerprint", FINGERPRINT)
            .param("credits", CREDITS)
            .update()
        return id
    }

    /** V28~V34 가 문서·변환에 매단 파생 행 한 벌. */
    private fun insertDerivedRows(
        userId: UUID,
        documentId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ) {
        insertReviewSupportRow(conversionId)
        insertTableStructureRow(documentId)
        insertReviewHistoryRows(userId, conversionId)
        insertIllustrationPlacementRow(conversionId)
        insertActionGuideContentRows(conversionId, jobId)
    }

    /** 이름 있는 파라미터를 한 번에 묶는다 — 파생 행 SQL 이 전부 같은 모양이라 반복을 줄인다. */
    private fun insertRow(
        sql: String,
        params: Map<String, Any>,
    ) {
        var spec = jdbcClient.sql(sql.trimIndent())
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        spec.update()
    }

    private fun insertReviewSupportRow(conversionId: UUID) =
        insertRow(
            """
            INSERT INTO review_assessments
                (id, conversion_id, content_revision, analyzer_version,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversionId, 1, 'fact-preservation-v1', :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversionId" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )

    private fun insertTableStructureRow(documentId: UUID) =
        insertRow(
            """
            INSERT INTO document_table_structures
                (document_id, payload_encrypted, encryption_scheme, key_version)
            VALUES (:documentId, :bytes, :scheme, 1)
            """,
            mapOf(
                "documentId" to documentId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )

    private fun insertReviewHistoryRows(
        userId: UUID,
        conversionId: UUID,
    ) {
        val snapshotId = UUID.randomUUID()
        insertRow(
            """
            INSERT INTO review_snapshots
                (id, conversion_id, content_revision, kind, payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversionId, 1, 'review_assessment', :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to snapshotId,
                "conversionId" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )
        insertRow(
            """
            INSERT INTO review_events
                (id, conversion_id, event_type, actor_user_id, created_at, content_revision, snapshot_id)
            VALUES (:id, :conversionId, 'item_confirmed', :userId, now(), 1, :snapshotId)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversionId" to conversionId,
                "userId" to userId,
                "snapshotId" to snapshotId,
            ),
        )
    }

    private fun insertIllustrationPlacementRow(conversionId: UUID) =
        insertRow(
            """
            INSERT INTO illustration_placements
                (id, conversion_id, content_revision, payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversionId, 1, :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversionId" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )

    private fun insertActionGuideContentRows(
        conversionId: UUID,
        jobId: UUID,
    ) {
        insertRow(
            """
            INSERT INTO action_guide_candidates
                (id, job_id, conversion_id, based_on_content_revision,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :jobId, :conversionId, 1, :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "jobId" to jobId,
                "conversionId" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )
        insertRow(
            """
            INSERT INTO action_guides
                (id, conversion_id, based_on_content_revision, guide_revision, status,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (:id, :conversionId, 1, 1, 'draft', :bytes, :scheme, 1)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "conversionId" to conversionId,
                "bytes" to PAYLOAD,
                "scheme" to EncryptionScheme.AES_256_GCM_V1,
            ),
        )
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

        const val DOCUMENT_ID = "document_id"
        const val CONVERSION_ID = "conversion_id"

        /** `ck_action_guide_jobs_fingerprint_length` 이 정확히 64자를 요구한다. */
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** `ck_action_guide_jobs_reserved_credits_positive` 과 `…_tenth`(V31)를 함께 만족한다. */
        val CREDITS: BigDecimal = BigDecimal("1.0")

        val PAYLOAD: ByteArray = byteArrayOf(1, 2, 3, 4)

        /** 전부 `ON DELETE CASCADE` — 탈퇴 한 번으로 사라져야 한다. */
        val DERIVED_TABLES: List<Pair<String, String>> =
            listOf(
                "review_assessments" to CONVERSION_ID,
                "document_table_structures" to DOCUMENT_ID,
                "review_snapshots" to CONVERSION_ID,
                "review_events" to CONVERSION_ID,
                "illustration_placements" to CONVERSION_ID,
                "action_guide_candidates" to CONVERSION_ID,
                "action_guides" to CONVERSION_ID,
            )

        var counter = 0
    }
}
