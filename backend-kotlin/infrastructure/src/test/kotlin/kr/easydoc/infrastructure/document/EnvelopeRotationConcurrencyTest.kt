package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionFeedbackRepository
import kr.easydoc.application.document.ConversionFeedbackService
import kr.easydoc.application.document.ConversionQueryService
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.ConversionReviewService
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.FeedbackSubmission
import kr.easydoc.application.document.LockedFeedbackComment
import kr.easydoc.application.document.OriginalReflection
import kr.easydoc.application.document.RotationOutcome
import kr.easydoc.application.document.SealedStores
import kr.easydoc.application.document.StoredOriginalReader
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.pilot.PublishIntent
import kr.easydoc.core.privacy.ReviewedBody
import kr.easydoc.core.security.Secret
import kr.easydoc.core.text.EditDistanceBudget
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.export.PackagedOriginalReflector
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

/** 회전과 내용 쓰기가 실제로 겹칠 때 무슨 일이 벌어지는가 — 게이트 27 지적 ①. */
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
        val (owner, conversionId) = seededConversion()
        val writer = Holder()
        val rotation =
            rotationWith(conversionHook = { writer.start { saveEditedText(owner, conversionId, EDITED_BODY) } })

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

        assertThat(openedEasyText(row)).isEqualTo(DRAFT_BODY)
    }

    @Test
    @DisplayName("**잠금 전제가 깨지면 CONTENDED 로 드러난다** — 조용히 덮는 갈래가 남지 않는다")
    fun `잠금이 서지 않으면 CONTENDED 다`() {
        val (owner, conversionId) = seededConversion()
        val writer = Holder()
        val rotation =
            rotationWith(
                conversionHook = { writer.start { saveEditedText(owner, conversionId, EDITED_BODY) } },
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

    @Test
    @DisplayName("피드백 회전도 같은 잠금을 든다 — 회전 중에 들어온 **의견 저장이 사라지지 않는다**")
    fun `피드백 회전이 동시 의견 저장을 삼키지 않는다`() {
        val (owner, conversionId) = seededFeedback()
        val writer = Holder()
        val rotation =
            rotationWith(feedbackHook = { writer.start { submitFeedback(owner, conversionId, LATER_COMMENT) } })

        val outcome = rotation.rotateFeedback(conversionId)
        writer.await()

        assertThat(writer.blockedWhileHeld())
            .describedAs(
                "회전이 잠근 행에 사용자 쓰기가 그대로 들어갔다 — `LOCK_COMMENT_SQL` 의 FOR NO KEY UPDATE 가 " +
                    "서지 않는다 (회전 결과 %s).",
                outcome,
            ).isTrue()

        val stored = storedComment(conversionId)
        assertThat(openedComment(conversionId, stored))
            .describedAs(
                "의견 저장이 흔적 없이 사라졌다 — 회전이 자기가 읽은 낡은 암호문으로 행을 덮었다 (회전 결과 %s).",
                outcome,
            ).isEqualTo(LATER_COMMENT)
        assertThat(stored.keyVersion)
            .describedAs("봉투는 새 세대인데 암호문은 나중에 들어온 옛 세대다 — 이렇게 찢어진 행은 영원히 열리지 않는다")
            .isEqualTo(OLD_GENERATION)
    }

    @Test
    @DisplayName("피드백 회전도 **잠금 전제가 깨지면 CONTENDED 로 드러난다** — 뒤진 쪽이 조용히 이기지 않는다")
    fun `피드백 잠금이 서지 않으면 CONTENDED 다`() {
        val (owner, conversionId) = seededFeedback()
        val writer = Holder()
        val rotation =
            rotationWith(
                feedbackHook = { writer.start { submitFeedback(owner, conversionId, LATER_COMMENT) } },
                transaction = NoTransaction,
            )

        val outcome = rotation.rotateFeedback(conversionId)
        writer.await()

        val stored = storedComment(conversionId)
        assertThat(outcome)
            .describedAs(
                "잠금 없이 경합에서 이겼다고 보고했다 — `REWRITE_COMMENT_SQL` 의 봉투·암호문 조건이 0행으로 드러나지 않는다",
            ).isEqualTo(RotationOutcome.CONTENDED)
        assertThat(openedComment(conversionId, stored))
            .describedAs("회전이 그사이 들어온 의견을 낡은 평문으로 덮었다")
            .isEqualTo(LATER_COMMENT)
        assertThat(stored.keyVersion)
            .describedAs("세대만 새것으로 올라갔다면 그사이 저장된 의견이 열리지 않는다")
            .isEqualTo(OLD_GENERATION)
    }

    /** **제품 경로 그대로** 저장한다 — 흉내는 제품과 갈려도 이 파일이 초록이라 쓰지 않는다. */
    private fun saveEditedText(
        owner: UUID,
        conversionId: UUID,
        body: String,
    ) {
        reviewServiceOn(dataSource()).save(owner, conversionId, ReviewedBody(body))
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

    /** 회전 한 벌. [conversionHook]·[documentHook]·[feedbackHook] 은 저장소가 행을 읽어 온 직후 불린다. */
    private fun rotationWith(
        conversionHook: () -> Unit = {},
        documentHook: () -> Unit = {},
        feedbackHook: () -> Unit = {},
        transaction: TransactionRunner? = null,
    ): EnvelopeRotation {
        val dataSource = dataSource()
        val client = JdbcClient.create(dataSource)
        return EnvelopeRotation(
            stores =
                SealedStores(
                    documents = HookedDocuments(JdbcDocumentRepository(client), documentHook),
                    originals = JdbcDocumentOriginalRepository(client),
                    conversions = HookedConversions(JdbcConversionRepository(client), conversionHook),
                    feedback = HookedFeedback(JdbcConversionFeedbackRepository(client), feedbackHook),
                ),
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
                    originals = JdbcDocumentOriginalRepository(client),
                    conversions = JdbcConversionRepository(client),
                    queue = JdbcConversionQueue(client),
                ),
            workspaces = JdbcWorkspaceLookup(client),
            users = JdbcUserRepository(client),
            cipher = cipherWith(OLD_GENERATION),
            extractor = { _, _ -> error("이 테스트는 파일 경로를 쓰지 않는다") },
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
        )
    }

    /** 초안·대응표가 찬 **완료** 상태의 옛 세대 변환. 검수본은 비어 있다. */
    private fun seededConversion(): Pair<UUID, UUID> {
        val owner = newUser()
        val workspace = workspaces.create(owner, "회전 경합 ${UUID.randomUUID()}").id
        val accepted = service.createFromText(owner, "원문 본문", null, workspace.toString())
        val writer = cipherWith(OLD_GENERATION)
        val conversionId = accepted.conversionId
        val draft = writer.encrypt(PlainBody(DRAFT_BODY), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        val masked =
            writer.encrypt(MaskedItemCodec().encode(emptyList()), conversionId, EncryptedField.CONVERSION_MASKED_ITEMS)
        jdbc
            .sql(
                """
                UPDATE conversions
                SET status = 'done',
                    easy_text_encrypted = :easyText,
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
        return owner to conversionId
    }

    /** 검수 저장 유스케이스 — 제품 조립과 같은 모양. */
    private fun reviewServiceOn(dataSource: DataSource): ConversionReviewService {
        val client = JdbcClient.create(dataSource)
        val conversions = JdbcConversionRepository(client)
        val runner = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
        val writer = cipherWith(OLD_GENERATION)
        return ConversionReviewService(
            conversions = conversions,
            cipher = writer,
            query =
                ConversionQueryService(
                    conversions = conversions,
                    cipher = writer,
                    maskedItems = MaskedItemCodec(),
                    original =
                        OriginalReflection(
                            StoredOriginalReader(JdbcDocumentOriginalRepository(client), writer),
                            PackagedOriginalReflector(),
                        ),
                    documents = JdbcDocumentRepository(client),
                    transaction = runner,
                ),
            transaction = runner,
        )
    }

    /** 옛 세대 의견이 봉해진 피드백 한 행. 대상 변환은 [seededConversion] 이 세운 완료 행이다. */
    private fun seededFeedback(): Pair<UUID, UUID> {
        val (owner, conversionId) = seededConversion()
        submitFeedback(owner, conversionId, FIRST_COMMENT)
        return owner to conversionId
    }

    /** **제품 경로 그대로** 피드백을 낸다 — 실경로 `upsert` 가 회전과 같은 행에서 겹치는 것이 요점이다. */
    private fun submitFeedback(
        owner: UUID,
        conversionId: UUID,
        comment: String,
    ) {
        feedbackServiceOn(dataSource()).save(
            owner,
            conversionId,
            FeedbackSubmission(
                publishIntent = PublishIntent.WITH_EDITS.wireName,
                qualityScore = QUALITY_SCORE,
                minutesSpent = MINUTES_SPENT,
                comment = comment,
            ),
        )
    }

    /** 피드백 저장 유스케이스 — 제품 조립과 같은 모양이고 [reviewServiceOn] 과 나란하다. */
    private fun feedbackServiceOn(dataSource: DataSource): ConversionFeedbackService {
        val client = JdbcClient.create(dataSource)
        val runner = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
        val writer = cipherWith(OLD_GENERATION)
        return ConversionFeedbackService(
            feedback = JdbcConversionFeedbackRepository(client),
            cipher = writer,
            query =
                ConversionQueryService(
                    conversions = JdbcConversionRepository(client),
                    cipher = writer,
                    maskedItems = MaskedItemCodec(),
                    original =
                        OriginalReflection(
                            StoredOriginalReader(JdbcDocumentOriginalRepository(client), writer),
                            PackagedOriginalReflector(),
                        ),
                    documents = JdbcDocumentRepository(client),
                    transaction = runner,
                ),
            transaction = runner,
            editDistanceBudget = EditDistanceBudget(FeedbackProperties.DEFAULT_EDIT_DISTANCE_CELL_BUDGET),
        )
    }

    private fun seededDocument(): UUID {
        val owner = newUser()
        val workspace = workspaces.create(owner, "문서 경합 ${UUID.randomUUID()}").id
        return service.createFromText(owner, "원문 본문", null, workspace.toString()).documentId
    }

    private fun readConversion(conversionId: UUID): ConversionEnvelope =
        checkNotNull(JdbcConversionRepository(jdbc).lockEnvelope(conversionId)) { "변환 행이 없다" }

    private fun readDocument(documentId: UUID): EncryptedContent =
        checkNotNull(JdbcDocumentRepository(jdbc).lockSourceText(documentId)) { "문서 행이 없다" }

    /** 그 행에 실제로 남은 봉인된 의견 한 열 — 봉투 두 값도 이 안에 함께 있다. */
    private fun storedComment(conversionId: UUID): EncryptedContent =
        checkNotNull(JdbcConversionFeedbackRepository(jdbc).lockComment(conversionId)?.comment) { "봉인된 의견이 없다" }

    /** 행이 든 봉투 그대로 연다 — 봉투와 암호문 세대가 갈린 행이면 여기서 열리지 않는다. */
    private fun openedComment(
        conversionId: UUID,
        sealed: EncryptedContent,
    ): String = reader.decrypt(sealed, conversionId, EncryptedField.CONVERSION_FEEDBACK_COMMENT).value

    private fun openedEditedText(row: ConversionEnvelope): String {
        val sealed = checkNotNull(row.ciphertexts.editedText) { "검수본이 비어 있다" }
        return reader.decrypt(sealed, row.conversionId, EncryptedField.CONVERSION_EDITED_TEXT).value
    }

    private fun openedEasyText(row: ConversionEnvelope): String {
        val sealed = checkNotNull(row.ciphertexts.easyText) { "초안이 비어 있다" }
        return reader.decrypt(sealed, row.conversionId, EncryptedField.CONVERSION_EASY_TEXT).value
    }

    // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
    // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
    private fun newUser(): UUID =
        users.create("rc${UUID.randomUUID()}@example.test", PasswordHash(DUMMY_PHC)).id.also(users::markEmailVerified)

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

    /** 방해 쓰기 하나를 띄우고 정해진 시간만 기다린다. */
    private inner class Holder {
        private var future: Future<*>? = null
        private var heldAtGrace = false

        fun start(task: () -> Unit) {
            val submitted = interference.submit(task)
            future = submitted
            runCatching { submitted.get(INTERFERENCE_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
            heldAtGrace = !submitted.isDone
        }

        /**
         * 회전이 아직 커밋하지 않은 동안 방해 쓰기가 **막혀 있었는가.** 훅은 회전 트랜잭션
         * 안에서 불리므로, 잠금이 실제로 서 있으면 유예가 끝날 때까지 끝날 수 없다 —
         * 「직렬화됐다」를 최종 상태가 아니라 이 값으로 곧장 잰다.
         */
        fun blockedWhileHeld(): Boolean = heldAtGrace

        fun await() {
            checkNotNull(future) { "방해 쓰기가 시작되지 않았다 — 훅이 불리지 않았다" }
                .get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

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

    private class HookedFeedback(
        private val delegate: ConversionFeedbackRepository,
        private val hook: () -> Unit,
    ) : ConversionFeedbackRepository by delegate {
        override fun lockComment(conversionId: UUID): LockedFeedbackComment? =
            delegate.lockComment(conversionId).also { hook() }
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
        const val FIRST_COMMENT = "검수자가 처음 남긴 의견"
        const val LATER_COMMENT = "회전 중에 다시 낸 의견"

        /** 피드백의 수기 값 둘. 범위의 정본은 `core/pilot/ConversionFeedback.kt` 이고 여기는 그 안의 한 점이다. */
        const val QUALITY_SCORE = 4
        const val MINUTES_SPENT = 12

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
