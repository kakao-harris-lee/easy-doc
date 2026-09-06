package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.LlmCallEntry
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `llm_calls` 원장(V12) — 실물 PostgreSQL. 다중 VALUES 삽입과, 보존 결정의 정정(계획 §2
 * 결정 1, 2026-09-08 리뷰로 3차 정정)을 실제 FK 로 잰다.
 *
 * **`conversion_id`·`document_id`는 FK가 없다** — 참조 대상이 지워져도 원장 행의 값은
 * 그대로 남는다(V12 머리주석 3차 정정). **`workspace_id`만 여전히 `SET NULL`**이고,
 * `user_id`만 `CASCADE`다.
 *
 * `OwnershipPredicateGuardTest`·`EnvelopeColumnWriteGuardTest` 대상이 아니다 — 둘 다
 * [kr.easydoc.core.crypto.EncryptedField] 가 아는 표만 훑는데, `llm_calls` 는 암호화 대상
 * 열이 없어 그 목록에 없다(V12 머리주석, `JdbcLlmCallLedger` KDoc).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcLlmCallLedgerTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var ledger: JdbcLlmCallLedger

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("llm_call_ledger")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val dataSource: DataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        ledger = JdbcLlmCallLedger(jdbc)
    }

    @Test
    @DisplayName("여러 항목을 한 INSERT 로 쓴다")
    fun `여러 행을 한 번에 쓴다`() {
        val seeded = seed()

        ledger.append(
            listOf(
                entry(seeded.conversionId, seeded.documentId, seeded.workspaceId, seeded.owner, LlmCallPurpose.CONVERT),
                entry(seeded.conversionId, seeded.documentId, seeded.workspaceId, seeded.owner, LlmCallPurpose.REPAIR),
            ),
        )

        val rows = purposesOf(seeded.conversionId)
        assertThat(rows).containsExactlyInAnyOrder("convert", "repair")
    }

    @Test
    @DisplayName("빈 목록은 아무것도 쓰지 않는다")
    fun `빈 목록은 no-op이다`() {
        val seeded = seed()

        ledger.append(emptyList())

        // 전체 표 건수는 다른 테스트가 이미 심어 둔 행과 섞인다(같은 클래스가 데이터베이스
        // 하나를 공유한다) — 이 테스트가 심은 conversionId 로 좁혀서 본다.
        assertThat(purposesOf(seeded.conversionId)).isEmpty()
    }

    @Test
    @DisplayName("문서를 지워도 conversion_id·document_id 값은 그대로 남는다 — FK가 없다")
    fun `문서 삭제 후에도 참조값이 남는다`() {
        val seeded = seed()
        appendConvertRow(seeded)

        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", seeded.documentId).update()

        val row = singleRowOf(seeded.workspaceId)
        assertThat(row.conversionId).isEqualTo(seeded.conversionId)
        assertThat(row.documentId).isEqualTo(seeded.documentId)
    }

    @Test
    @DisplayName("사용자를 지우면 행도 함께 사라진다")
    fun `사용자 삭제는 행을 지운다`() {
        val seeded = seed()
        appendConvertRow(seeded)

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", seeded.owner).update()

        assertThat(purposesOf(seeded.conversionId)).isEmpty()
    }

    @Test
    @DisplayName("문서를 먼저 지운 뒤 워크스페이스를 지우면 workspace_id 만 NULL 이 되고 나머지 참조값은 남는다")
    fun `워크스페이스 삭제는 workspace_id 참조만 끊는다`() {
        val seeded = seed()
        appendConvertRow(seeded)
        // documents.workspace_id 는 NO ACTION 이라 참조 문서가 있으면 워크스페이스를
        // 지울 수 없다 — 원장 행이 워크스페이스 삭제에서도 살아남는지 재려면 문서부터 지운다.
        jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", seeded.documentId).update()

        jdbc.sql("DELETE FROM workspaces WHERE id = :id").param("id", seeded.workspaceId).update()

        // 이 시점엔 workspace_id 만 NULL 이라 그 열로는 행을 다시 찾을 수 없다 — CASCADE 로
        // 지워지지 않는 user_id 로 찾는다. conversion_id·document_id 는 FK가 없으므로 이
        // 워크스페이스 삭제와 무관하게 원래 값 그대로다.
        val row = singleRowByUser(seeded.owner)
        assertThat(row.workspaceId).isNull()
        assertThat(row.conversionId).isEqualTo(seeded.conversionId)
        assertThat(row.documentId).isEqualTo(seeded.documentId)
    }

    private fun appendConvertRow(seeded: Seeded) {
        val single =
            entry(seeded.conversionId, seeded.documentId, seeded.workspaceId, seeded.owner, LlmCallPurpose.CONVERT)
        ledger.append(listOf(single))
    }

    private fun entry(
        conversionId: UUID?,
        documentId: UUID?,
        workspaceId: UUID,
        userId: UUID,
        purpose: LlmCallPurpose,
    ): LlmCallEntry =
        LlmCallEntry(
            conversionId = conversionId,
            documentId = documentId,
            workspaceId = workspaceId,
            userId = userId,
            record =
                LlmCallRecord(
                    purpose = purpose,
                    provider = "anthropic",
                    model = "claude-sonnet-5",
                    inputTokens = 100,
                    outputTokens = 50,
                    latencyMs = 1_200,
                    estimatedCostUsd = BigDecimal("0.000700"),
                    pricingInputUsdPerMtok = BigDecimal("2.00"),
                    pricingOutputUsdPerMtok = BigDecimal("10.00"),
                    charCount = 40,
                    calledAt = Instant.now(),
                ),
            calledAt = Instant.now(),
            documentCharCount = DOCUMENT_CHAR_COUNT,
        )

    private fun seed(): Seeded {
        val owner = users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id
        val workspaceId = workspaces.create(owner, "공간-${UUID.randomUUID()}").id
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO documents (id, user_id, workspace_id, title, source_format,
                                       source_text_encrypted, char_count, encryption_scheme, key_version)
                VALUES (:id, :owner, :workspace, :title, :format, :bytes, 4, :scheme, 1)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", owner)
            .param("workspace", workspaceId)
            .param("title", "제목")
            .param("format", SourceFormat.TEXT.wireName)
            .param("bytes", byteArrayOf(1, 2, 3, 4))
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .update()
        conversions.insertPending(conversionId, documentId, EncryptionScheme.AES_256_GCM_V1, 1)
        return Seeded(owner, workspaceId, documentId, conversionId)
    }

    private fun purposesOf(conversionId: UUID): List<String> =
        jdbc
            .sql("SELECT purpose FROM llm_calls WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getString("purpose") }
            .list()

    private fun singleRowOf(workspaceId: UUID): LedgerRow =
        jdbc
            .sql("SELECT conversion_id, document_id, workspace_id FROM llm_calls WHERE workspace_id = :workspaceId")
            .param("workspaceId", workspaceId)
            .query { rs, _ -> toLedgerRow(rs) }
            .single()

    /** 워크스페이스도 지워진 뒤에 쓴다 — `user_id` 만 `CASCADE` 라 이 열로만 다시 찾을 수 있다. */
    private fun singleRowByUser(userId: UUID): LedgerRow =
        jdbc
            .sql("SELECT conversion_id, document_id, workspace_id FROM llm_calls WHERE user_id = :userId")
            .param("userId", userId)
            .query { rs, _ -> toLedgerRow(rs) }
            .single()

    private fun toLedgerRow(rs: ResultSet): LedgerRow =
        LedgerRow(
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
        )

    private data class Seeded(
        val owner: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    )

    private data class LedgerRow(
        val conversionId: UUID?,
        val documentId: UUID?,
        val workspaceId: UUID?,
    )

    private companion object {
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** `documents.char_count` 스냅샷 — 이 테스트는 그 값을 재지 않으므로 고정값이면 충분하다. */
        const val DOCUMENT_CHAR_COUNT: Int = 4
    }
}
