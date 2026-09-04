package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.ConversionFeedbackService
import kr.easydoc.application.document.ConversionQueryService
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.FamilyRotationOutcome
import kr.easydoc.application.document.FeedbackSubmission
import kr.easydoc.application.document.KeyRotationBatch
import kr.easydoc.application.document.KeyRotationObserver
import kr.easydoc.application.document.KeyRotationPolicy
import kr.easydoc.application.document.OriginalReflection
import kr.easydoc.application.document.SealedStores
import kr.easydoc.application.document.StoredOriginal
import kr.easydoc.application.document.StoredOriginalReader
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.pilot.PublishIntent
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
 * `rotate-keys` 운영 진입점의 핵심 — [KeyRotationBatch] 가 실제 PostgreSQL 에서 가족 넷을
 * 전부 새 세대로 옮기고, 다시 돌리면 할 일이 없다는 것을 잰다. backlog §1.1 「키 회전에
 * 운영 진입점이 없음」의 회귀 고정판.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeyRotationBatchTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("key_rotation_batch")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        users = JdbcUserRepository(jdbc)
        workspaces = JdbcWorkspaceRepository(jdbc)
    }

    @Test
    @DisplayName("가족 넷 전부를 새 세대로 옮기고, 다시 돌리면 할 일이 없다")
    fun `배치가 옛 세대 행 전부를 회전하고 재실행은 no-op 이다`() {
        val owner = newUser()
        val workspaceId = workspaces.create(owner, "회전 배치 ${UUID.randomUUID()}").id
        val (documentIds, conversionIds) = seedOldGenerationRows(owner, workspaceId)

        val result = batchWith(cipherWith(NEW_GENERATION), batchSize = 2).run()

        assertThat(result.families).allSatisfy { family ->
            assertThat(family.rotated)
                .describedAs("가족 %s 가 %d건을 회전하지 못했다: %s", family.family, ROW_COUNT, family)
                .isEqualTo(ROW_COUNT)
            assertThat(family.skipped).isZero()
            assertThat(family.remaining).isZero()
        }
        assertAllAtGeneration(documentIds, conversionIds, NEW_GENERATION)
        assertDocumentRoundTrips(documentIds.first(), "옛 세대 원문 0")

        // 재실행 — 후보 질의가 `key_version < target` 을 SQL 에서 직접 거르므로, 이미 회전한
        // 행은 뽑히지도 않는다(rotateOne 자체가 불리지 않는다). 그래서 재실행의 집계는
        // rotated 뿐 아니라 skipped 도 0 이다 — 「할 일이 아예 없었다」와 「봤지만 할 일이
        // 없었다」가 이 경로에서는 같은 값(0)으로 보인다.
        val secondRun = batchWith(cipherWith(NEW_GENERATION), batchSize = 2).run()
        assertThat(secondRun.families).allSatisfy { family ->
            assertThat(family.rotated)
                .describedAs(
                    "재실행이 가족 %s 에서 다시 회전할 것을 찾았다 — idempotent 하지 않다",
                    family.family,
                ).isZero()
            assertThat(family.skipped).isZero()
            assertThat(family.remaining).isZero()
        }
    }

    @Test
    @DisplayName("동시 쓰기와 겹친 행은 remaining 으로 집계되고, 재실행이 그 행을 다시 회전한다")
    fun `배치 도중 경합한 행은 remaining 이고 재실행이 다시 회전한다`() {
        val owner = newUser()
        val workspaceId = workspaces.create(owner, "회전 경합 ${UUID.randomUUID()}").id
        val oldCipher = cipherWith(OLD_GENERATION)
        val accepted = serviceOn(oldCipher).createFromText(owner, "경합 원문", null, workspaceId.toString())
        completeConversion(oldCipher, accepted.conversionId, 0)

        // NoTransaction 이라 회전이 실 잠금 없이 SELECT 와 UPDATE 를 두 문장으로 낸다 —
        // `EnvelopeRotationConcurrencyTest` 「잠금이 서지 않으면 CONTENDED 다」와 같은 기법.
        // 그 사이에 낀 이 훅이 옛 세대인 채로 한 열(edited_text_encrypted)을 바꾼다.
        //
        // **제품 검수 저장 경로(`ConversionReviewService`)를 부르지 않는다.** 그 경로는
        // `ReviewedBody` 를 요구하는데, 그 타입은 사람이 검수 화면에서 제출한 본문을 읽는
        // 어댑터 한 곳에서만 만들 수 있다(privacy-gate X-5, `ProvenanceCreationSitesTest`
        // 의 허용목록). 대신 `EnvelopeRotationConcurrencyTest.rewriteSourceText` 와 같은
        // 기법을 쓴다 — `PlainBody` 로 직접 암호화하고 원시 SQL 로 쓴다. 재는 것은 「회전의
        // 낙관적 조건이 동시 쓰기를 CONTENDED 로 드러내는가」이지 검수 유스케이스 자체가
        // 아니라, 어떤 동시 쓰기든 같은 열을 바꾸기만 하면 같은 성질을 잰다.
        var interfered = false
        val client = JdbcClient.create(dataSource)
        val stores =
            SealedStores(
                documents = JdbcDocumentRepository(client),
                originals = JdbcDocumentOriginalRepository(client),
                conversions =
                    HookedConversions(JdbcConversionRepository(client)) {
                        if (!interfered) {
                            interfered = true
                            rewriteEditedTextConcurrently(oldCipher, accepted.conversionId, "동시 편집")
                        }
                    },
                feedback = JdbcConversionFeedbackRepository(client),
            )
        val batch =
            KeyRotationBatch(
                stores = stores,
                rotation =
                    EnvelopeRotation(stores = stores, cipher = cipherWith(NEW_GENERATION), transaction = NoTransaction),
                cipher = cipherWith(NEW_GENERATION),
                policy = KeyRotationPolicy(batchSize = 10),
                observer = KeyRotationObserver { _: FamilyRotationOutcome -> },
            )

        val result = batch.run()

        val conversionsOutcome = result.families.single { it.family == "conversions" }
        assertThat(conversionsOutcome.rotated)
            .describedAs("경합한 행이 그래도 회전됐다고 집계됐다 — 동시 편집을 삼켰다는 뜻이다")
            .isZero()
        assertThat(conversionsOutcome.remaining)
            .describedAs("경합이 remaining 으로 집계되지 않았다: %s", conversionsOutcome)
            .isEqualTo(1)
        assertThat(keyVersionOf("conversions", "id", accepted.conversionId))
            .describedAs("CONTENDED 인데 세대가 올라갔다 — 실은 갱신되지 않았어야 한다")
            .isEqualTo(OLD_GENERATION)

        // 재실행 — 별도 재시도 로직 없이, 세대가 그대로라 다음 실행이 처음부터 다시 훑으며
        // 이 행을 자연스럽게 다시 고른다(커서는 실행마다 새로 선다 — `KeyRotationBatch` KDoc).
        val secondResult = batchWith(cipherWith(NEW_GENERATION), batchSize = 10).run()
        val secondOutcome = secondResult.families.single { it.family == "conversions" }
        assertThat(secondOutcome.rotated)
            .describedAs("재실행이 경합했던 행을 다시 회전하지 못했다 — idempotent 재시도가 깨졌다")
            .isEqualTo(1)
        assertThat(secondOutcome.remaining).isZero()
        assertThat(keyVersionOf("conversions", "id", accepted.conversionId)).isEqualTo(NEW_GENERATION)
    }

    /** 읽기 직후에 [hook] 을 부르는 것 말고는 [delegate] 그대로다. */
    private class HookedConversions(
        private val delegate: ConversionRepository,
        private val hook: () -> Unit,
    ) : ConversionRepository by delegate {
        override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? =
            delegate.lockEnvelope(conversionId).also { hook() }
    }

    /** 트랜잭션을 열지 않는 실행기 — 「잠금 전제가 깨진 상태」의 재현용이다. */
    private object NoTransaction : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    /**
     * 회전의 SELECT 와 UPDATE 사이에 끼워 넣는 동시 쓰기. `edited_text_encrypted` 한 열만
     * 원시 SQL 로 바꾼다 — `EnvelopeRotationConcurrencyTest.rewriteSourceText` 와 같은 형태고
     * 같은 이유다: `ReviewedBody` 를 만들 수 있는 자리가 아니다(이 함수 주석 위 호출부 참고).
     *
     * 봉투 두 값(`encryption_scheme`·`key_version`)도 **같은 문장에서 함께** 쓴다 —
     * `EnvelopeColumnWriteGuardTest` 가 저장소 전체에 거는 불변식이고, 이 동시 쓰기도 예외가
     * 아니다: 옛 세대 그대로 쓰는 실제 동시 쓰기를 흉내 내므로 [cipher] 가 낸 세대를
     * 그대로 적으면 된다.
     */
    private fun rewriteEditedTextConcurrently(
        cipher: ContentCipher,
        conversionId: UUID,
        body: String,
    ) {
        val sealed = cipher.encrypt(PlainBody(body), conversionId, EncryptedField.CONVERSION_EDITED_TEXT)
        jdbc
            .sql(
                """
                UPDATE conversions
                SET edited_text_encrypted = :bytes, encryption_scheme = :scheme, key_version = :keyVersion
                WHERE id = :id
                """.trimIndent(),
            ).param("bytes", sealed.bytes)
            .param("scheme", sealed.scheme)
            .param("keyVersion", sealed.keyVersion)
            .param("id", conversionId)
            .update()
    }

    /** 가족 넷 모두에 옛 세대(v1) 행을 [ROW_COUNT] 건씩 심는다. */
    private fun seedOldGenerationRows(
        owner: UUID,
        workspaceId: UUID,
    ): Pair<List<UUID>, List<UUID>> {
        val oldCipher = cipherWith(OLD_GENERATION)
        val documentIds = mutableListOf<UUID>()
        val conversionIds = mutableListOf<UUID>()
        repeat(ROW_COUNT) { i ->
            val accepted =
                serviceOn(oldCipher).createFromText(owner, "옛 세대 원문 $i", null, workspaceId.toString())
            documentIds += accepted.documentId
            conversionIds += accepted.conversionId
            completeConversion(oldCipher, accepted.conversionId, i)
            seedOriginal(oldCipher, owner, accepted.documentId, i)
            submitFeedback(oldCipher, owner, accepted.conversionId, i)
        }
        return documentIds to conversionIds
    }

    private fun assertAllAtGeneration(
        documentIds: List<UUID>,
        conversionIds: List<UUID>,
        generation: Int,
    ) {
        documentIds.forEach { assertThat(keyVersionOf("documents", "id", it)).isEqualTo(generation) }
        documentIds.forEach {
            assertThat(keyVersionOf("document_originals", "document_id", it)).isEqualTo(generation)
        }
        conversionIds.forEach { assertThat(keyVersionOf("conversions", "id", it)).isEqualTo(generation) }
        conversionIds.forEach {
            assertThat(keyVersionOf("conversion_feedback", "conversion_id", it)).isEqualTo(generation)
        }
    }

    /**
     * 새 세대 키만으로도 왕복하는지 본다 — 회전이 「세대만 바꾸고 옛 키로 봉한 채로 남긴」
     * 것이 아니라 실제로 다시 암호화했다는 증거다.
     */
    private fun assertDocumentRoundTrips(
        documentId: UUID,
        expectedPlainText: String,
    ) {
        val reader = cipherWith(NEW_GENERATION)
        val storedText =
            jdbc
                .sql("SELECT source_text_encrypted, encryption_scheme, key_version FROM documents WHERE id = :id")
                .param("id", documentId)
                .query { rs, _ ->
                    EncryptedContent(
                        bytes = rs.getBytes("source_text_encrypted"),
                        scheme = rs.getString("encryption_scheme"),
                        keyVersion = rs.getInt("key_version"),
                    )
                }.single()
        assertThat(reader.decrypt(storedText, documentId, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
            .isEqualTo(expectedPlainText)
    }

    private fun completeConversion(
        cipher: ContentCipher,
        conversionId: UUID,
        i: Int,
    ) {
        val draft = cipher.encrypt(PlainBody("초안 $i"), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        val masked =
            cipher.encrypt(MaskedItemCodec().encode(emptyList()), conversionId, EncryptedField.CONVERSION_MASKED_ITEMS)
        jdbc
            .sql(
                """
                UPDATE conversions
                SET status = 'done', easy_text_encrypted = :easyText, masked_items_encrypted = :maskedItems,
                    encryption_scheme = :scheme, key_version = :keyVersion
                WHERE id = :id
                """.trimIndent(),
            ).param("easyText", draft.bytes)
            .param("maskedItems", masked.bytes)
            .param("scheme", EncryptionScheme.AES_256_GCM_V1)
            .param("keyVersion", OLD_GENERATION)
            .param("id", conversionId)
            .update()
    }

    private fun seedOriginal(
        cipher: ContentCipher,
        owner: UUID,
        documentId: UUID,
        i: Int,
    ) {
        val plain = "원본 바이트 $i".toByteArray(Charsets.UTF_8)
        val sealed = cipher.encryptBytes(PlainBytes(plain), documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES)
        JdbcDocumentOriginalRepository(jdbc).insert(owner, documentId, StoredOriginal(sealed, plain.size))
    }

    private fun submitFeedback(
        cipher: ContentCipher,
        owner: UUID,
        conversionId: UUID,
        i: Int,
    ) {
        feedbackServiceOn(cipher).save(
            owner,
            conversionId,
            FeedbackSubmission(
                publishIntent = PublishIntent.WITH_EDITS.wireName,
                qualityScore = QUALITY_SCORE,
                minutesSpent = MINUTES_SPENT,
                comment = "회전 배치 의견 $i",
            ),
        )
    }

    private fun keyVersionOf(
        table: String,
        column: String,
        id: UUID,
    ): Int =
        jdbc
            .sql("SELECT key_version FROM $table WHERE $column = :id")
            .param("id", id)
            .query { rs, _ -> rs.getInt("key_version") }
            .single()

    private fun batchWith(
        cipher: ContentCipher,
        batchSize: Int,
    ): KeyRotationBatch {
        val client = JdbcClient.create(dataSource)
        val stores =
            SealedStores(
                documents = JdbcDocumentRepository(client),
                originals = JdbcDocumentOriginalRepository(client),
                conversions = JdbcConversionRepository(client),
                feedback = JdbcConversionFeedbackRepository(client),
            )
        val runner = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
        return KeyRotationBatch(
            stores = stores,
            rotation = EnvelopeRotation(stores = stores, cipher = cipher, transaction = runner),
            cipher = cipher,
            policy = KeyRotationPolicy(batchSize = batchSize),
            observer = KeyRotationObserver { _: FamilyRotationOutcome -> },
        )
    }

    private fun serviceOn(cipher: ContentCipher): DocumentService {
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
            cipher = cipher,
            extractor = { _, _ -> error("이 테스트는 파일 경로를 쓰지 않는다") },
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
        )
    }

    private fun feedbackServiceOn(cipher: ContentCipher): ConversionFeedbackService {
        val client = JdbcClient.create(dataSource)
        val runner = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
        return ConversionFeedbackService(
            feedback = JdbcConversionFeedbackRepository(client),
            cipher = cipher,
            query =
                ConversionQueryService(
                    conversions = JdbcConversionRepository(client),
                    cipher = cipher,
                    maskedItems = MaskedItemCodec(),
                    original =
                        OriginalReflection(
                            StoredOriginalReader(JdbcDocumentOriginalRepository(client), cipher),
                            PackagedOriginalReflector(),
                        ),
                    transaction = runner,
                ),
            transaction = runner,
            editDistanceBudget = EditDistanceBudget(FeedbackProperties.DEFAULT_EDIT_DISTANCE_CELL_BUDGET),
        )
    }

    // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
    // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
    private fun newUser(): UUID =
        users.create("rot${UUID.randomUUID()}@example.test", PasswordHash(DUMMY_PHC)).id.also(users::markEmailVerified)

    /** 두 세대를 모두 실은 암호기. */
    private fun cipherWith(writeKeyVersion: Int): ContentCipher =
        AesGcmContentCipher(
            keyMaterial = mapOf(OLD_GENERATION to KEY_GEN_1, NEW_GENERATION to KEY_GEN_2),
            writeKeyVersion = writeKeyVersion,
            random = SecureRandom(),
        )

    private companion object {
        const val OLD_GENERATION = 1
        const val NEW_GENERATION = 2

        /** 커서 배치 크기(2)보다 크게 잡아 **여러 배치**에 걸치는 순회를 실제로 재게 한다. */
        const val ROW_COUNT = 5

        const val QUALITY_SCORE = 4
        const val MINUTES_SPENT = 12

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
