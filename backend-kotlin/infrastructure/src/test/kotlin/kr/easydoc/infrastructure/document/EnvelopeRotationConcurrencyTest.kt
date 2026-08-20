package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.RotationOutcome
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **회전과 내용 쓰기가 실제로 겹칠 때** 무슨 일이 벌어지는가 — 게이트 27 지적 ①.
 *
 * ## 무엇이 잡히지 않고 있었나
 *
 * 회전은 `lockEnvelope`(읽기) → 복호 → 재암호 → `rewriteEnvelope`(쓰기) 로 두 문장이다.
 * 그 사이에 다른 트랜잭션이 암호문 열을 바꾸고 커밋해도, 낙관적 조건이
 * `WHERE key_version = :expected` 하나뿐이면 **조건이 여전히 참이다** — `key_version` 은
 * 회전만 바꾸는 열이라 내용 쓰기가 건드리지 않기 때문이다. PostgreSQL READ COMMITTED 는
 * 잠금을 얻은 뒤 `WHERE` 를 **갱신된 행 버전으로 다시 평가**하고, 조건이 여전히 맞으면
 * *"the second updater proceeds with its operation using the updated version of the row"*
 * (PostgreSQL 16 문서 13.2.1). 그래서 회전이 **자기가 1단계에서 읽은 낡은 값으로 세 열을
 * 통째로 덮고** `updated = true` 를 받아 `ROTATED` 를 돌려준다. 사용자의 쓰기가 아무 신호
 * 없이 사라진다.
 *
 * 기존 「낙관적 조건」 케이스(`JdbcDocumentStoreTest`)는 **회전 대 회전**만 잰다 —
 * 이미 회전된 행을 옛 세대로 다시 쓰면 0행이라는 것. 그것은 이 갈래를 밟지 않는다.
 *
 * ## 이 파일이 겹치게 만드는 방법
 *
 * 회전의 두 문장 **사이**에 다른 커넥션의 트랜잭션을 끼워 넣는다. 저장소 포트를 감싼
 * 대역([HookedConversions]·[HookedDocuments])이 읽기 직후에 방해 쓰기를 다른 스레드로
 * 띄우고 **[INTERFERENCE_GRACE_MILLIS] 만큼만** 기다린다 — 기다림이 무한이면 잠금이 선
 * 뒤에는 교착이고, 0이면 겹침이 확실하지 않다. 잠금이 서 있으면 방해 쓰기는 그 시간
 * 안에 끝나지 못하고, 회전이 커밋한 **뒤에** 풀려 자기 일을 마친다.
 *
 * ## 방해 쓰기는 **준수 쓰기**다
 *
 * 원시 SQL 로 쓰되 C7(검수 저장)·Phase 5 워커가 가져야 할 모양 그대로다 — 행을 잠근 채
 * 읽고, **그 행의 세대로** 봉인하고, 암호문 열과 봉투 두 값을 **한 문장에서** 함께 쓴다.
 * 잠그지 않는 쓰기를 대역으로 쓰면 이 파일이 재는 것이 「회전의 결함」인지 「쓰기의 결함」인지
 * 갈리지 않는다. 봉투를 함께 쓴다는 조건은 `EnvelopeColumnWriteGuardTest` 가 소스 전수로
 * 강제하고, 여기 세 문장도 그 분모 안에 있다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnvelopeRotationConcurrencyTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var service: DocumentService
    private lateinit var interference: ExecutorService

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("rotation_concurrency")
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
        service = serviceOn(dataSource)
        interference = Executors.newSingleThreadExecutor()
    }

    @AfterAll
    fun shutdown() {
        interference.shutdownNow()
    }

    @Test
    @DisplayName("회전 중에 들어온 **검수 저장이 사라지지 않는다** — 회전이 낡은 값으로 덮지 않는다")
    fun `회전이 동시 검수 저장을 삼키지 않는다`() {
        val conversionId = seededConversion()
        val writer = Holder()
        val rotation = rotationWith(conversionHook = { writer.start { saveEditedText(conversionId, EDITED_BODY) } })

        val outcome = rotation.rotateConversion(conversionId)
        writer.await()

        val row = readConversion(conversionId)
        assertThat(row.ciphertexts.editedText)
            .describedAs(
                "검수 저장이 흔적 없이 사라졌다 — 회전이 자기가 읽은 낡은 세 열로 행을 통째로 덮었다 " +
                    "(회전 결과 %s, 행 세대 v%d). 「경합에서 졌다」가 CONTENDED 로도 드러나지 않는다.",
                outcome,
                row.keyVersion,
            ).isNotNull()
        assertThat(openedEditedText(row)).isEqualTo(EDITED_BODY)
        // 세 열과 봉투가 어긋나면 행이 부분적으로만 열린다 — 초안 쪽도 함께 확인한다.
        assertThat(openedEasyText(row)).isEqualTo(DRAFT_BODY)
    }

    @Test
    @DisplayName("**잠금 전제가 깨지면 CONTENDED 로 드러난다** — 조용히 덮는 갈래가 남지 않는다")
    fun `잠금이 서지 않으면 CONTENDED 다`() {
        // 회전을 트랜잭션 없이(자동 커밋) 돌린다. 그러면 읽기가 얻은 행 잠금이 문장이
        // 끝나는 순간 풀려 방해 쓰기가 그대로 끼어든다 — 잠금 전제가 깨진 상태의 재현이다.
        // 그 상태에서도 **덮지 않고 지는 것**이 요구다. 낙관적 조건이 암호문까지 보지 않으면
        // 여기가 조용히 ROTATED 로 통과한다.
        val conversionId = seededConversion()
        val writer = Holder()
        val rotation =
            rotationWith(
                conversionHook = { writer.start { saveEditedText(conversionId, EDITED_BODY) } },
                transaction = NoTransaction,
            )

        val outcome = rotation.rotateConversion(conversionId)
        writer.await()

        val row = readConversion(conversionId)
        assertThat(outcome)
            .describedAs("잠금 없이 경합에서 이겼다고 보고했다 — 사라진 쓰기가 CONTENDED 로 드러나지 않는다")
            .isEqualTo(RotationOutcome.CONTENDED)
        assertThat(row.ciphertexts.editedText).isNotNull()
        assertThat(openedEditedText(row)).isEqualTo(EDITED_BODY)
    }

    @Test
    @DisplayName("문서 원문 회전도 같은 잠금을 든다 — 동시 원문 쓰기가 사라지지 않는다")
    fun `문서 회전이 동시 원문 쓰기를 삼키지 않는다`() {
        // `documents.source_text_encrypted` 를 다시 쓰는 제품 경로는 **아직 없다.** 그래도
        // 같은 형태를 재는 이유는 회전 코드가 두 테이블에 대칭으로 서 있기 때문이다 —
        // 한쪽만 고치면 다음에 그 경로가 생기는 순간 같은 결함이 되살아난다.
        val documentId = seededDocument()
        val writer = Holder()
        val rotation = rotationWith(documentHook = { writer.start { rewriteSourceText(documentId, REWRITTEN_BODY) } })

        rotation.rotateDocument(documentId)
        writer.await()

        val stored = readDocument(documentId)
        assertThat(reader.decrypt(stored, documentId, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .describedAs("회전이 동시 원문 쓰기를 낡은 값으로 덮었다")
            .isEqualTo(REWRITTEN_BODY)
    }

    // ================================================================ 방해 쓰기

    /**
     * C7(검수 저장)이 가질 모양 — **행을 잠근 채 읽고, 그 행의 세대로 봉인해, 봉투와 함께 쓴다.**
     *
     * 자기 커넥션에서 자기 트랜잭션으로 돈다. 회전이 행을 잠그고 있으면 첫 문장에서 멈춘다.
     */
    private fun saveEditedText(
        conversionId: UUID,
        body: String,
    ) = inOwnTransaction { connection ->
        val (scheme, keyVersion) = lockRow(connection, "conversions", conversionId)
        val field = EncryptedField.CONVERSION_EDITED_TEXT
        val sealed = cipherWith(keyVersion).encrypt(PlainBody(body), conversionId, field)
        check(sealed.scheme == scheme) { "봉인 방식이 행의 방식과 다르다" }
        connection
            .prepareStatement(
                """
                UPDATE conversions
                SET edited_text_encrypted = ?, encryption_scheme = ?, key_version = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, sealed.bytes)
                statement.setString(2, sealed.scheme)
                statement.setInt(3, sealed.keyVersion)
                statement.setObject(4, conversionId)
                statement.executeUpdate()
            }
    }

    /** 같은 모양의 문서 쪽 쓰기. */
    private fun rewriteSourceText(
        documentId: UUID,
        body: String,
    ) = inOwnTransaction { connection ->
        val (_, keyVersion) = lockRow(connection, "documents", documentId)
        val sealed = cipherWith(keyVersion).encrypt(PlainBody(body), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        connection
            .prepareStatement(
                """
                UPDATE documents
                SET source_text_encrypted = ?, encryption_scheme = ?, key_version = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, sealed.bytes)
                statement.setString(2, sealed.scheme)
                statement.setInt(3, sealed.keyVersion)
                statement.setObject(4, documentId)
                statement.executeUpdate()
            }
    }

    private fun lockRow(
        connection: Connection,
        table: String,
        id: UUID,
    ): Pair<String, Int> =
        // 테이블 이름은 이 파일 안의 상수 둘 중 하나이고 식별자는 파라미터다 — 주입면이 없다.
        connection
            .prepareStatement("SELECT encryption_scheme, key_version FROM $table WHERE id = ? FOR NO KEY UPDATE")
            .use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "$table 행이 없다: $id" }
                    rows.getString(1) to rows.getInt(2)
                }
            }

    private fun inOwnTransaction(block: (Connection) -> Unit) {
        database.connect().use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    // ================================================================ 조립

    /**
     * 회전 한 벌. [conversionHook]·[documentHook] 은 **저장소가 행을 읽어 온 직후** 불린다.
     *
     * 훅을 저장소 대역에 다는 이유: 두 문장 사이에 끼어들 수 있는 자리가 거기뿐이다.
     */
    private fun rotationWith(
        conversionHook: () -> Unit = {},
        documentHook: () -> Unit = {},
        transaction: TransactionRunner? = null,
    ): EnvelopeRotation {
        val dataSource = dataSource()
        val client = JdbcClient.create(dataSource)
        return EnvelopeRotation(
            documents = HookedDocuments(JdbcDocumentRepository(client), documentHook),
            conversions = HookedConversions(JdbcConversionRepository(client), conversionHook),
            cipher = cipherWith(NEW_GENERATION),
            transaction =
                transaction
                    ?: SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
        )
    }

    private fun serviceOn(dataSource: DataSource): DocumentService {
        val client = JdbcClient.create(dataSource)
        return DocumentService(
            storage =
                DocumentStorage(
                    documents = JdbcDocumentRepository(client),
                    conversions = JdbcConversionRepository(client),
                    queue = JdbcConversionQueue(client),
                ),
            workspaces = JdbcWorkspaceLookup(client),
            cipher = cipherWith(OLD_GENERATION),
            extractor = { _, _ -> error("이 테스트는 파일 경로를 쓰지 않는다") },
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
        )
    }

    // ================================================================ 픽스처

    /** 초안·대응표가 채워진 옛 세대 변환 하나. 검수본은 비어 있다. */
    private fun seededConversion(): UUID {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전 경합 ${UUID.randomUUID()}").id
        val accepted = service.createFromText(owner, "원문 본문", null, workspace)
        val writer = cipherWith(OLD_GENERATION)
        val conversionId = accepted.conversionId
        val draft = writer.encrypt(PlainBody(DRAFT_BODY), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        val masked =
            writer.encrypt(MaskedItemCodec().encode(emptyList()), conversionId, EncryptedField.CONVERSION_MASKED_ITEMS)
        jdbc
            .sql(
                """
                UPDATE conversions
                SET easy_text_encrypted = :easyText,
                    masked_items_encrypted = :maskedItems,
                    encryption_scheme = :scheme,
                    key_version = :keyVersion
                WHERE id = :id
                """.trimIndent(),
            ).param("easyText", draft.bytes)
            .param("maskedItems", masked.bytes)
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .param("keyVersion", OLD_GENERATION)
            .param("id", conversionId)
            .update()
        return conversionId
    }

    private fun seededDocument(): UUID {
        val owner = newUser()
        val workspace = workspaces.create(owner, "문서 경합 ${UUID.randomUUID()}").id
        return service.createFromText(owner, "원문 본문", null, workspace).documentId
    }

    private fun readConversion(conversionId: UUID): ConversionEnvelope =
        checkNotNull(JdbcConversionRepository(jdbc).lockEnvelope(conversionId)) { "변환 행이 없다" }

    private fun readDocument(documentId: UUID): EncryptedContent =
        checkNotNull(JdbcDocumentRepository(jdbc).lockSourceText(documentId)) { "문서 행이 없다" }

    private fun openedEditedText(row: ConversionEnvelope): String {
        val sealed = checkNotNull(row.ciphertexts.editedText) { "검수본이 비어 있다" }
        return reader.decrypt(sealed, row.conversionId, EncryptedField.CONVERSION_EDITED_TEXT).value
    }

    private fun openedEasyText(row: ConversionEnvelope): String {
        val sealed = checkNotNull(row.ciphertexts.easyText) { "초안이 비어 있다" }
        return reader.decrypt(sealed, row.conversionId, EncryptedField.CONVERSION_EASY_TEXT).value
    }

    private fun newUser(): UUID = users.create("rc${UUID.randomUUID()}@example.test", PasswordHash(DUMMY_PHC)).id

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    /** 두 세대를 모두 실은 암호기. 복호화는 암호문 자신의 세대를 쓰므로 읽기에도 이것을 쓴다. */
    private fun cipherWith(writeKeyVersion: Int): ContentCipher =
        AesGcmContentCipher(
            keyMaterial = mapOf(OLD_GENERATION to KEY_GEN_1, NEW_GENERATION to KEY_GEN_2),
            writeKeyVersion = writeKeyVersion,
            random = SecureRandom(),
        )

    private val reader: ContentCipher by lazy { cipherWith(NEW_GENERATION) }

    /**
     * 방해 쓰기 하나를 띄우고 **정해진 시간만** 기다린다.
     *
     * 무한히 기다리면 잠금이 선 뒤에는 회전 스레드와 서로를 물고 멈춘다. 기다리지 않으면
     * 잠금이 없을 때 겹침이 확실하지 않다. 그래서 「끝났으면 좋고, 안 끝났으면 두고 간다」다.
     */
    private inner class Holder {
        private var future: Future<*>? = null

        fun start(task: () -> Unit) {
            val submitted = interference.submit(task)
            future = submitted
            runCatching { submitted.get(INTERFERENCE_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
        }

        fun await() {
            checkNotNull(future) { "방해 쓰기가 시작되지 않았다 — 훅이 불리지 않았다" }
                .get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    // ================================================================ 대역

    /** 읽기 직후에 [hook] 을 부르는 것 말고는 [delegate] 그대로다. */
    private class HookedConversions(
        private val delegate: ConversionRepository,
        private val hook: () -> Unit,
    ) : ConversionRepository by delegate {
        override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? =
            delegate.lockEnvelope(conversionId).also { hook() }
    }

    private class HookedDocuments(
        private val delegate: DocumentRepository,
        private val hook: () -> Unit,
    ) : DocumentRepository by delegate {
        override fun lockSourceText(documentId: UUID): EncryptedContent? =
            delegate.lockSourceText(documentId).also { hook() }
    }

    /** 트랜잭션을 열지 않는 실행기 — 「잠금 전제가 깨진 상태」의 재현용이다. */
    private object NoTransaction : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private companion object {
        const val OLD_GENERATION = 1
        const val NEW_GENERATION = 2

        const val DRAFT_BODY = "쉬운 글 초안"
        const val EDITED_BODY = "담당자가 고친 검수본"
        const val REWRITTEN_BODY = "다시 쓴 원문"

        /** 방해 쓰기를 기다리는 시간. 잠금이 없으면 이 안에 끝나고, 있으면 끝나지 못한다. */
        const val INTERFERENCE_GRACE_MILLIS = 2_000L

        /** 회전이 커밋한 뒤 방해 쓰기가 마무리되기를 기다리는 상한. */
        const val TASK_TIMEOUT_SECONDS = 30L

        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        const val KEY_BYTES = 32

        private val random = SecureRandom()

        private fun randomKey(): Secret {
            val material = ByteArray(KEY_BYTES)
            random.nextBytes(material)
            return Secret(Base64.getEncoder().encodeToString(material))
        }

        val KEY_GEN_1: Secret = randomKey()
        val KEY_GEN_2: Secret = randomKey()
    }
}
