package kr.easydoc.infrastructure.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.application.document.RotationOutcome
import kr.easydoc.application.document.SealedStores
import kr.easydoc.application.document.StoredOriginal
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.StorageException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * 업로드 **원본 파일 바이트** 저장 — 실제 PostgreSQL 에서만 잴 수 있는 것들.
 *
 * `V3__document_originals.sql` 이 근거로 적은 성질들이 실제로 성립하는지를 여기서 확인한다:
 * 왕복 · 소유 술어 · 문서와 함께 사라짐 · 보존 만료와 함께 사라짐 · 봉투가 따로 도는 회전.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDocumentOriginalStoreTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var originals: JdbcDocumentOriginalRepository
    private lateinit var cipher: ContentCipher
    private lateinit var service: DocumentService
    private lateinit var rotation: EnvelopeRotation
    private lateinit var purge: JdbcExpiredDocumentPurge

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("document_originals")
        // **빈 DB 에 V1 → V2 → V3 가 순서대로 적용된다.** 이 클래스의 모든 케이스가 그 위에서 돈다.
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = dataSourceOf(database)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
        originals = JdbcDocumentOriginalRepository(jdbc)
        cipher = cipherWith(OLD_GENERATION)
        purge = JdbcExpiredDocumentPurge(jdbc)
        service =
            DocumentService(
                storage =
                    DocumentStorage(
                        documents = JdbcDocumentRepository(jdbc),
                        originals = originals,
                        conversions = JdbcConversionRepository(jdbc),
                        queue = JdbcConversionQueue(jdbc),
                    ),
                workspaces = JdbcWorkspaceLookup(jdbc),
                // 추출 결과는 고정이다 — 이 파일이 재는 것은 파서가 아니라 **원본 바이트의 왕복**이다.
                extractor = DocumentTextExtractor { _, _ -> ExtractedDocument(SourceFormat.DOCX, EXTRACTED_TEXT) },
                cipher = cipher,
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            )
        rotation =
            EnvelopeRotation(
                stores =
                    SealedStores(
                        documents = JdbcDocumentRepository(jdbc),
                        originals = originals,
                        conversions = JdbcConversionRepository(jdbc),
                        feedback = JdbcConversionFeedbackRepository(jdbc),
                    ),
                cipher = cipherWith(NEW_GENERATION),
                transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            )
    }

    @Test
    @DisplayName("업로드한 원본이 **한 바이트도 바뀌지 않고** 돌아온다 — 실제 AES-GCM 왕복")
    fun `원본이 그대로 돌아온다`() {
        val owner = newUser()
        val accepted = uploadFile(owner)

        val stored = originals.findOwned(owner, accepted.documentId)

        checkNotNull(stored) { "업로드가 원본 행을 남기지 않았다" }
        assertThat(stored.byteSize).isEqualTo(ORIGINAL_FILE.size)
        assertThat(cipher.decryptBytes(stored.bytes, accepted.documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES).value)
            .describedAs("UTF-8 로 해석되지 않는 바이트가 섞여 있다 — 문자열 짝을 타면 여기서 눌린다")
            .isEqualTo(ORIGINAL_FILE)
        assertThat(stored.bytes.bytes)
            .describedAs("행에 평문이 그대로 들어갔다 — 이 표를 직접 조회해도 파일이 보이면 안 된다")
            .isNotEqualTo(ORIGINAL_FILE)
    }

    @Test
    @DisplayName("**붙여넣기에는 행이 없다** — 「원본 없음」이 NULL 이 아니라 행의 부재다")
    fun `붙여넣기는 행을 남기지 않는다`() {
        val owner = newUser()
        val accepted = service.createFromText(owner, EXTRACTED_TEXT, null, workspaceOf(owner).toString())

        assertThat(originals.findOwned(owner, accepted.documentId)).isNull()
        assertThat(rowCount(accepted.documentId)).isZero()
    }

    @Test
    @DisplayName("**남의 원본은 없는 것과 같다** — 존재를 구분해 주지 않는다")
    fun `남의 원본을 읽지 못한다`() {
        val owner = newUser()
        val stranger = newUser()
        val accepted = uploadFile(owner)

        assertThat(originals.findOwned(stranger, accepted.documentId))
            .describedAs("소유 술어가 읽기 문장 자신에 걸려야 한다 — 없는 것과 남의 것이 같은 값이다")
            .isNull()
        assertThat(originals.findOwned(stranger, UUID.randomUUID())).isNull()
    }

    @Test
    @DisplayName("**남의 문서에는 원본을 붙이지 못한다** — 소유 술어가 쓰기 문장 자신에 걸린다")
    fun `남의 문서에 원본을 붙이지 못한다`() {
        val owner = newUser()
        val stranger = newUser()
        // 원본이 아직 없는 문서를 하나 만든다(붙여넣기 팔).
        val accepted = service.createFromText(owner, EXTRACTED_TEXT, null, workspaceOf(owner).toString())
        val sealed = sealedOriginalFor(accepted.documentId)

        assertThatThrownBy { originals.insert(stranger, accepted.documentId, sealed) }
            .describedAs("0행이 조용히 지나가면 소유 술어가 있으나 마나다")
            .isInstanceOf(StorageException::class.java)
        assertThat(rowCount(accepted.documentId)).isZero()

        // **양성 대조** — 같은 호출이 주인에게는 성공해야 한다.
        originals.insert(owner, accepted.documentId, sealed)
        assertThat(rowCount(accepted.documentId)).isEqualTo(1)
    }

    @Test
    @DisplayName("**문서를 지우면 원본도 사라진다** — 개인정보 삭제 요구")
    fun `문서 삭제가 원본을 데려간다`() {
        val owner = newUser()
        val accepted = uploadFile(owner)
        check(rowCount(accepted.documentId) == 1) { "삭제 전에 원본 행이 있어야 한다" }

        service.delete(owner, accepted.documentId)

        assertThat(rowCount(accepted.documentId))
            .describedAs("CASCADE 가 빠지면 사용자가 지운 문서의 원본 파일이 DB 에 남는다")
            .isZero()
    }

    @Test
    @DisplayName("**보존 만료 파기도 원본을 데려간다** — 원본만 30일보다 오래 남지 않는다")
    fun `보존 만료가 원본을 데려간다`() {
        val owner = newUser()
        val accepted = uploadFile(owner)
        expireNow(accepted.documentId)

        val result = purge.purge(dryRun = false, limit = PURGE_LIMIT)

        assertThat(result.documentIds).contains(accepted.documentId)
        assertThat(rowCount(accepted.documentId))
            .describedAs("보존 기한을 이 표에 복사해 두지 않은 근거다 — 문서가 지워지면 원본도 지워져야 한다")
            .isZero()
    }

    @Test
    @DisplayName("**계정을 지우면 원본도 사라진다** — users → documents → document_originals")
    fun `계정 삭제가 원본을 데려간다`() {
        val owner = newUser()
        val accepted = uploadFile(owner)

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", owner).update()

        assertThat(rowCount(accepted.documentId)).isZero()
    }

    @Test
    @DisplayName("회전이 **원본 바이트를 그대로 옮긴다** — v1 로 봉한 것이 v2 로 같은 파일이 된다")
    fun `원본을 회전한다`() {
        val owner = newUser()
        val accepted = uploadFile(owner)

        val outcome = rotation.rotateDocumentOriginal(accepted.documentId)

        assertThat(outcome).isEqualTo(RotationOutcome.ROTATED)
        val rotated = checkNotNull(originals.findOwned(owner, accepted.documentId))
        assertThat(rotated.bytes.keyVersion).isEqualTo(NEW_GENERATION)
        assertThat(rotated.byteSize)
            .describedAs("회전은 평문을 바꾸지 않는다 — 크기가 암호문 길이로 갈리면 안 된다")
            .isEqualTo(ORIGINAL_FILE.size)
        assertThat(
            cipherWith(NEW_GENERATION)
                .decryptBytes(rotated.bytes, accepted.documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES)
                .value,
        ).describedAs("회전이 파일을 조용히 망가뜨렸다 — 되돌릴 수 없다")
            .isEqualTo(ORIGINAL_FILE)
    }

    @Test
    @DisplayName("**원문 회전과 원본 회전이 서로를 옮기지 않는다** — 봉투를 공유하지 않는다(V3 ⑤)")
    fun `두 봉투가 따로 돈다`() {
        val owner = newUser()
        val accepted = uploadFile(owner)

        assertThat(rotation.rotateDocument(accepted.documentId)).isEqualTo(RotationOutcome.ROTATED)

        assertThat(originalKeyVersion(accepted.documentId))
            .describedAs("원문 회전이 원본 봉투까지 옮겼다 — 그러면 원본 암호문은 옛 세대인데 봉투는 새 세대다")
            .isEqualTo(OLD_GENERATION)
        assertThat(rotation.rotateDocumentOriginal(accepted.documentId)).isEqualTo(RotationOutcome.ROTATED)
        assertThat(originalKeyVersion(accepted.documentId)).isEqualTo(NEW_GENERATION)
    }

    @Test
    @DisplayName("원본이 없는 문서의 회전은 MISSING 이다 — 붙여넣기 문서가 배치에서 내는 값")
    fun `원본 없는 문서의 회전`() {
        val owner = newUser()
        val accepted = service.createFromText(owner, EXTRACTED_TEXT, null, workspaceOf(owner).toString())

        assertThat(rotation.rotateDocumentOriginal(accepted.documentId)).isEqualTo(RotationOutcome.MISSING)
        assertThat(rotation.rotateDocumentOriginal(UUID.randomUUID()))
            .describedAs("없는 문서와 원본 없는 문서는 회전에게 결과가 같다")
            .isEqualTo(RotationOutcome.MISSING)
    }

    @Test
    @DisplayName("스키마가 **떠 있는 원본**을 막는다 — 없는 문서를 가리키는 행은 들어가지 않는다")
    fun `떠 있는 원본을 거절한다`() {
        assertThatSqlFails(
            """
            INSERT INTO document_originals
                (document_id, file_bytes_encrypted, encryption_scheme, key_version, byte_size)
            VALUES ('${UUID.randomUUID()}', '\\x00'::bytea, 'aes256gcm-v1', 1, 1)
            """.trimIndent(),
        )
    }

    @Test
    @DisplayName("스키마가 **0바이트 원본과 잘못된 봉투**를 막는다")
    fun `도메인 밖 값을 거절한다`() {
        val owner = newUser()
        val accepted = uploadFile(owner)
        // 위 업로드가 이미 행을 만들었으므로, 아래 셋은 PK 충돌이 아니라 **각 CHECK** 로 끊겨야 한다.
        val other = uploadFile(newUser()).documentId
        jdbc.sql("DELETE FROM document_originals WHERE document_id = :id").param("id", other).update()

        listOf(
            "0바이트" to originalInsert(other, byteSize = 0),
            "세대 0" to originalInsert(other, keyVersion = 0),
            "폐기된 방식 이름" to originalInsert(other, scheme = "fernet-v1"),
        ).forEach { (label, sql) ->
            assertThatSqlFails(sql, label)
        }

        // **양성 대조.** 같은 문장 모양이 도메인 안의 값으로는 통과해야 한다 — 없으면 위 셋이
        // CHECK 가 아니라 오타로 실패해도 이 케이스가 통과한다.
        assertThat(jdbc.sql(originalInsert(other)).update())
            .describedAs("도메인 안의 값도 들어가지 않는다 — 위 세 실패가 CHECK 때문이라는 근거가 없다")
            .isEqualTo(1)
        assertThat(rowCount(accepted.documentId)).isEqualTo(1)
    }

    private fun assertThatSqlFails(
        sql: String,
        label: String = "제약",
    ) {
        val failure = runCatching { jdbc.sql(sql).update() }.exceptionOrNull()
        assertThat(failure)
            .describedAs("%s 를 막지 못했다 — 이 값이 조용히 저장된다", label)
            .isNotNull()
        assertThat(generateSequence(failure) { it.cause }.any { it is SQLException })
            .describedAs("%s 가 DB 제약이 아닌 다른 이유로 실패했다: %s", label, failure)
            .isTrue()
    }

    private fun originalInsert(
        documentId: UUID,
        byteSize: Int = 1,
        keyVersion: Int = 1,
        scheme: String = "aes256gcm-v1",
    ): String =
        """
        INSERT INTO document_originals
            (document_id, file_bytes_encrypted, encryption_scheme, key_version, byte_size)
        VALUES ('$documentId', '\\x00'::bytea, '$scheme', $keyVersion, $byteSize)
        """.trimIndent()

    private fun sealedOriginalFor(documentId: UUID) =
        StoredOriginal(
            bytes = cipher.encryptBytes(PlainBytes(ORIGINAL_FILE), documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES),
            byteSize = ORIGINAL_FILE.size,
        )

    private fun uploadFile(owner: UUID) =
        service.createFromFile(owner, "안내문.docx", ORIGINAL_FILE, null, workspaceOf(owner).toString())

    private fun workspaceOf(owner: UUID): UUID =
        JdbcWorkspaceLookup(jdbc).findDefaultId(owner) ?: workspaces.create(owner, "기본").id

    private fun newUser(): UUID =
        users
            .create("original-${UUID.randomUUID()}@example.kr", PasswordHash(DUMMY_PHC))
            .id
            .also { workspaces.create(it, "기본") }

    private fun rowCount(documentId: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM document_originals WHERE document_id = :id")
            .param("id", documentId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun originalKeyVersion(documentId: UUID): Int =
        jdbc
            .sql("SELECT key_version FROM document_originals WHERE document_id = :id")
            .param("id", documentId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun expireNow(documentId: UUID) {
        jdbc
            .sql("UPDATE documents SET retention_expires_at = now() - interval '1 day' WHERE id = :id")
            .param("id", documentId)
            .update()
    }

    private fun dataSourceOf(database: DatabaseHandle): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun cipherWith(writeKeyVersion: Int): ContentCipher =
        AesGcmContentCipher(
            keyMaterial = mapOf(OLD_GENERATION to KEY_V1, NEW_GENERATION to KEY_V2),
            writeKeyVersion = writeKeyVersion,
            random = SecureRandom(),
        )

    private companion object {
        const val OLD_GENERATION = 1
        const val NEW_GENERATION = 2

        const val PURGE_LIMIT = 100

        const val EXTRACTED_TEXT = "복지 급여 안내\n둘째 줄"

        /**
         * 원본 파일을 흉내 내는 바이트. **UTF-8 로 해석되지 않는 값을 일부러 섞는다** —
         * zip 머리(`PK`) 뒤에 단독 0x80·0xFF·0xC0 을 둔 모양이다. 어느 경로든
         * 문자열로 왕복하면 이 바이트들이 U+FFFD 로 눌려 되돌아오지 않는다.
         */
        val ORIGINAL_FILE: ByteArray =
            byteArrayOf(
                0x50,
                0x4B,
                0x03,
                0x04,
                0x80.toByte(),
                0xFF.toByte(),
                0x00,
                0xC0.toByte(),
                0xFE.toByte(),
                0x01,
                0x7F,
                0xED.toByte(),
            )

        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** AES-256. */
        const val KEY_BYTES = 32

        private val random = SecureRandom()

        private fun randomKey(): Secret {
            val material = ByteArray(KEY_BYTES)
            random.nextBytes(material)
            return Secret(Base64.getEncoder().encodeToString(material))
        }

        /** 두 세대를 **한 번만** 뽑는다 — 회전 전후를 같은 키로 열어야 왕복을 잴 수 있다. */
        val KEY_V1: Secret = randomKey()
        val KEY_V2: Secret = randomKey()
    }
}
