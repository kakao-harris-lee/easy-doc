package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * 검수 저장 UPDATE 의 `WHERE` 조건들 — **실제 SQL 에서만 잴 수 있는 것**. 유스케이스가 먼저
 * 막으므로 대역에서는 조건을 지워도 초록이다. 여기가 그것을 보는 유일한 자리다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionReviewStorageTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var cipher: ContentCipher
    private lateinit var service: DocumentService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("conversion_review_storage")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = dataSource()
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        cipher = cipherWith(WRITE_GENERATION)
        service =
            DocumentService(
                storage =
                    DocumentStorage(
                        JdbcDocumentRepository(jdbc),
                        JdbcDocumentOriginalRepository(jdbc),
                        conversions,
                        JdbcConversionQueue(jdbc),
                    ),
                workspaces = JdbcWorkspaceLookup(jdbc),
                users = JdbcUserRepository(jdbc),
                cipher = cipher,
                extractor = { _, _ -> error("이 테스트는 파일 경로를 쓰지 않는다") },
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            )
    }

    @Test
    @DisplayName("`status` 조건이 SQL 에 실제로 걸려 있다 — 완료가 아닌 행은 **0행**이다")
    fun `완료가 아닌 행은 저장되지 않는다`() {
        val (owner, conversionId) = doneConversion()
        val locked = checkNotNull(conversions.lockOwnedForReview(owner, conversionId))
        forceStatus(conversionId, ConversionStatus.PROCESSING)

        val saved = conversions.saveReview(owner, locked.envelope, ConversionStatus.DONE, sealed(locked.envelope))

        assertThat(saved)
            .describedAs("완료가 아닌 행에 검수본이 저장됐다 — WHERE 의 status 조건이 실제로 걸려 있지 않다")
            .isFalse()
        assertThat(editedTextOf(conversionId)).isNull()
    }

    @Test
    @DisplayName("낙관적 조건이 SQL 에 실제로 걸려 있다 — 읽은 뒤 암호문이 바뀌면 **0행**이다")
    fun `읽은 뒤 바뀐 행은 저장되지 않는다`() {
        val (owner, conversionId) = doneConversion()
        val stale = checkNotNull(conversions.lockOwnedForReview(owner, conversionId)).envelope

        // 우리가 읽은 뒤 누군가 초안 열을 바꿨다 — 잠금이 서 있었다면 불가능한 상태다.
        rewriteDraft(conversionId, "그사이 바뀐 초안")

        val saved = conversions.saveReview(owner, stale, ConversionStatus.DONE, sealed(stale))

        assertThat(saved)
            .describedAs("읽은 행이 그대로가 아닌데 저장됐다 — 잠금 전제가 깨진 것이 0행으로 드러나지 않는다")
            .isFalse()
        assertThat(editedTextOf(conversionId)).isNull()
    }

    @Test
    @DisplayName("소유 술어가 SQL 에 실제로 걸려 있다 — 남의 소유자로는 **0행**이다")
    fun `남의 소유자로는 저장되지 않는다`() {
        val (owner, conversionId) = doneConversion()
        val locked = checkNotNull(conversions.lockOwnedForReview(owner, conversionId))
        val stranger = newUser()

        val saved = conversions.saveReview(stranger, locked.envelope, ConversionStatus.DONE, sealed(locked.envelope))

        assertThat(saved)
            .describedAs("남의 소유자로 검수본이 저장됐다 — UPDATE 자신에는 소유 술어가 없다")
            .isFalse()
        assertThat(editedTextOf(conversionId)).isNull()
    }

    @Test
    @DisplayName("조건이 전부 맞으면 저장된다 — 위 세 케이스가 「언제나 0행」으로 통과하지 않는다")
    fun `조건이 맞으면 저장된다`() {
        val (owner, conversionId) = doneConversion()
        val locked = checkNotNull(conversions.lockOwnedForReview(owner, conversionId))

        val saved = conversions.saveReview(owner, locked.envelope, ConversionStatus.DONE, sealed(locked.envelope))

        assertThat(saved).isTrue()
        assertThat(editedTextOf(conversionId)).isNotNull()
    }

    /** 검수본 열만 채운 **쓸 행 버전**. */
    private fun sealed(envelope: ConversionEnvelope): ConversionEnvelope =
        ConversionEnvelope(
            conversionId = envelope.conversionId,
            scheme = cipher.writeScheme,
            keyVersion = cipher.writeKeyVersion,
            ciphertexts =
                ConversionCiphertexts(
                    easyText = envelope.ciphertexts.easyText,
                    maskedItems = envelope.ciphertexts.maskedItems,
                    editedText =
                        cipher.encrypt(
                            PlainBody(EDITED_BODY),
                            envelope.conversionId,
                            EncryptedField.CONVERSION_EDITED_TEXT,
                        ),
                ),
        )

    private fun doneConversion(): Pair<UUID, UUID> {
        val owner = newUser()
        val workspace = workspaces.create(owner, "검수 저장 ${UUID.randomUUID()}").id
        val accepted = service.createFromText(owner, "원문 본문", null, workspace.toString())
        val draft = cipher.encrypt(PlainBody(DRAFT_BODY), accepted.conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        jdbc
            .sql(MARK_DONE_SQL)
            .param("easyText", draft.bytes)
            .param("scheme", draft.scheme)
            .param("keyVersion", draft.keyVersion)
            .param("id", accepted.conversionId)
            .update()
        return owner to accepted.conversionId
    }

    private fun forceStatus(
        conversionId: UUID,
        status: ConversionStatus,
    ) {
        jdbc
            .sql("UPDATE conversions SET status = :status WHERE id = :id")
            .param("status", status.wireName)
            .param("id", conversionId)
            .update()
    }

    private fun rewriteDraft(
        conversionId: UUID,
        body: String,
    ) {
        val sealed = cipher.encrypt(PlainBody(body), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        jdbc
            .sql(REWRITE_DRAFT_SQL)
            .param("easyText", sealed.bytes)
            .param("scheme", sealed.scheme)
            .param("keyVersion", sealed.keyVersion)
            .param("id", conversionId)
            .update()
    }

    private fun editedTextOf(conversionId: UUID): ByteArray? =
        jdbc
            .sql("SELECT edited_text_encrypted FROM conversions WHERE id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getBytes(1) }
            .optional()
            .orElse(null)

    // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
    // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
    private fun newUser(): UUID =
        users.create("crs${UUID.randomUUID()}@example.test", PasswordHash(DUMMY_PHC)).id.also(users::markEmailVerified)

    private fun cipherWith(writeKeyVersion: Int): ContentCipher =
        AesGcmContentCipher(
            keyMaterial = mapOf(WRITE_GENERATION to Secret(Base64.getEncoder().encodeToString(randomKey()))),
            writeKeyVersion = writeKeyVersion,
        )

    private fun randomKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private companion object {
        const val WRITE_GENERATION = 1
        const val KEY_BYTES = 32
        const val DRAFT_BODY = "쉬운 글 초안입니다."
        const val EDITED_BODY = "담당자가 다듬은 문장입니다."
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA"

        /** 봉투를 암호문과 **같은 문장에서** SET 한다(`EnvelopeColumnWriteGuardTest` 규약). */
        val MARK_DONE_SQL =
            """
            UPDATE conversions
            SET status = 'done',
                easy_text_encrypted = :easyText,
                encryption_scheme = :scheme,
                key_version = :keyVersion
            WHERE id = :id
            """.trimIndent()

        val REWRITE_DRAFT_SQL =
            """
            UPDATE conversions
            SET easy_text_encrypted = :easyText,
                encryption_scheme = :scheme,
                key_version = :keyVersion
            WHERE id = :id
            """.trimIndent()
    }
}
