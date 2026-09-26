package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.GuideDraftApplyCommand
import kr.easydoc.application.actionguide.GuideDraftApplyRepository
import kr.easydoc.application.actionguide.GuideDraftApplyService
import kr.easydoc.application.actionguide.GuideDraftApplySource
import kr.easydoc.application.actionguide.StoredGuideDraftApplication
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.document.JdbcConversionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Uses the existing PostgreSQL test support to verify atomic body replacement and mandatory recovery. */
class GuideDraftApplyIntegrationTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var transactions: TransactionTemplate
    private lateinit var repository: JdbcGuideDraftApplyRepository
    private val cipher = cipher(1)
    private val owner = UUID.randomUUID()
    private val workspace = UUID.randomUUID()
    private val document = UUID.randomUUID()
    private val conversion = UUID.randomUUID()
    private val command = GuideDraftApplyCommand(UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1, 1)
    private var draftReads = 0

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("guide_draft_apply")
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(dataSource)
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        repository = JdbcGuideDraftApplyRepository(jdbc)
        seed()
    }

    @Test
    fun `apply archives unreviewed previous text without R5 and retries never reapply or revalidate draft`() {
        val service = service()
        val result = service.apply(owner, conversion, command)
        assertThat(result.contentRevision).isEqualTo(2)
        assertThat(service.previousBody(owner, conversion, result.previousSnapshotId).value).isEqualTo("기존 편집 본문")
        assertThat(currentBody()).isEqualTo("보완된 전체 본문")
        assertThat(
            jdbc
                .sql("SELECT reviewed_at IS NULL FROM conversions WHERE id=:id")
                .param("id", conversion)
                .query { rs, _ -> rs.getBoolean(1) }
                .single(),
        ).isTrue()
        assertThat(
            jdbc
                .sql("SELECT status FROM action_guides WHERE conversion_id=:id")
                .param("id", conversion)
                .query { rs, _ -> rs.getString(1) }
                .single(),
        ).isEqualTo("stale")
        assertThat(result.replayed).isFalse()
        assertThat(service.apply(owner, conversion, command)).isEqualTo(result.copy(replayed = true))
        assertThat(
            service.listPreviousBodies(owner, conversion).single().snapshotId,
        ).isEqualTo(result.previousSnapshotId)
        assertThatThrownBy { service.listPreviousBodies(UUID.randomUUID(), conversion) }
            .isInstanceOf(NotFoundException::class.java)
        assertThat(draftReads).isEqualTo(1)
        assertThat(countSnapshots()).isEqualTo(1)
        assertThatThrownBy { service.apply(owner, conversion, command.copy(expectedDraftRevision = 2)) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.apply(owner, conversion, command.copy(requestId = UUID.randomUUID())) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `mandatory snapshot failure rolls back body and dependent guide state`() {
        val failing =
            object : GuideDraftApplyRepository by repository {
                override fun insertSnapshot(
                    ownerId: UUID,
                    application: StoredGuideDraftApplication,
                ): Boolean = false
            }
        assertThatThrownBy { service(failing).apply(owner, conversion, command) }
            .isInstanceOf(StorageException::class.java)
        assertThat(currentBody()).isEqualTo("기존 편집 본문")
        assertThat(countSnapshots()).isZero()
        assertThat(
            jdbc
                .sql("SELECT content_revision FROM conversions WHERE id=:id")
                .param("id", conversion)
                .query { rs, _ -> rs.getLong(1) }
                .single(),
        ).isEqualTo(1)
        assertThat(
            jdbc
                .sql("SELECT status FROM action_guides WHERE conversion_id=:id")
                .param("id", conversion)
                .query { rs, _ -> rs.getString(1) }
                .single(),
        ).isEqualTo("reviewed")
    }

    @Test
    fun `simultaneous retries serialize to one snapshot and one body revision`() {
        val service = service()
        Executors.newFixedThreadPool(2).use { executor ->
            val results =
                executor
                    .invokeAll(List(2) { Callable { service.apply(owner, conversion, command) } })
                    .map { it.get() }
            assertThat(results[0].copy(replayed = false)).isEqualTo(results[1].copy(replayed = false))
            assertThat(results.count { it.replayed }).isEqualTo(1)
            assertThat(results[0].contentRevision).isEqualTo(2)
        }
        assertThat(draftReads).isEqualTo(1)
        assertThat(countSnapshots()).isEqualTo(1)
    }

    @Test
    fun `body write failure rolls back inserted snapshot`() {
        val failing =
            object : GuideDraftApplyRepository by repository {
                override fun saveUnreviewed(
                    ownerId: UUID,
                    expected: kr.easydoc.application.document.ConversionEnvelope,
                    updated: kr.easydoc.application.document.ConversionEnvelope,
                    expectedRevision: Long,
                    updatedRevision: Long,
                ): Boolean = false
            }
        assertThatThrownBy { service(failing).apply(owner, conversion, command) }
            .isInstanceOf(StorageException::class.java)
        assertThat(countSnapshots()).isZero()
        assertThat(currentBody()).isEqualTo("기존 편집 본문")
    }

    @Test
    fun `identical text still creates an unreviewed version once and oversized body cannot apply`() {
        assertThatThrownBy { service(body = "가".repeat(20_001)).apply(owner, conversion, command) }
            .isInstanceOf(kr.easydoc.core.exceptions.InvalidInputException::class.java)
        assertThat(countSnapshots()).isZero()
        val service = service(body = "기존 편집 본문")
        val first = service.apply(owner, conversion, command)
        assertThat(first.contentRevision).isEqualTo(2)
        assertThat(service.apply(owner, conversion, command)).isEqualTo(first.copy(replayed = true))
        assertThat(currentBody()).isEqualTo("기존 편집 본문")
        assertThat(countSnapshots()).isEqualTo(1)
    }

    @Test
    fun `applying with a new encryption key reseals every body column and clears previous confirmation`() {
        jdbc.sql("UPDATE conversions SET reviewed_at=now() WHERE id=:id").param("id", conversion).update()
        val nextCipher = cipher(2)
        val result = service(writeCipher = nextCipher).apply(owner, conversion, command)
        val envelope = JdbcConversionRepository(jdbc).lockEnvelope(conversion)!!
        assertThat(envelope.keyVersion).isEqualTo(2)
        assertThat(
            nextCipher.decrypt(envelope.ciphertexts.easyText!!, conversion, EncryptedField.CONVERSION_EASY_TEXT).value,
        ).isEqualTo("AI 초안")
        assertThat(
            nextCipher
                .decrypt(
                    envelope.ciphertexts.editedText!!,
                    conversion,
                    EncryptedField.CONVERSION_EDITED_TEXT,
                ).value,
        ).isEqualTo("보완된 전체 본문")
        assertThat(service(writeCipher = nextCipher).previousBody(owner, conversion, result.previousSnapshotId).value)
            .isEqualTo("기존 편집 본문")
        assertThat(
            jdbc
                .sql("SELECT reviewed_at IS NULL FROM conversions WHERE id=:id")
                .param("id", conversion)
                .query { rs, _ -> rs.getBoolean(1) }
                .single(),
        ).isTrue()
    }

    @Test
    fun `ownership retention deletion and key rotation also protect large previous bodies`() {
        val large = "😀".repeat(17_000)
        jdbc
            .sql(
                """
                UPDATE conversions SET edited_text_encrypted=:body, encryption_scheme='aes256gcm-v1', key_version=1
                WHERE id=:id
                """.trimIndent(),
            ).param("id", conversion)
            .param("body", cipher.encrypt(PlainBody(large), conversion, EncryptedField.CONVERSION_EDITED_TEXT).bytes)
            .update()
        val service = service()
        val result = service.apply(owner, conversion, command)
        assertThat(service.previousBody(owner, conversion, result.previousSnapshotId).value).isEqualTo(large)
        assertThatThrownBy { service.previousBody(UUID.randomUUID(), conversion, result.previousSnapshotId) }
            .isInstanceOf(NotFoundException::class.java)
        val nextCipher = cipher(2)
        val rotation = GuideDraftApplyKeyRotation(jdbc, nextCipher, transactions, 10)
        assertThat(rotation.run()).isEqualTo(1)
        assertThat(rotation.run()).isZero()
        val stored = repository.findSnapshot(owner, conversion, result.previousSnapshotId)!!
        assertThat(
            nextCipher.decrypt(stored.previousBody, stored.snapshotId, EncryptedField.ACTION_GUIDE_PREVIOUS_BODY).value,
        ).isEqualTo(large)
        assertThatThrownBy {
            nextCipher.decrypt(stored.previousBody, UUID.randomUUID(), EncryptedField.ACTION_GUIDE_PREVIOUS_BODY)
        }.isInstanceOf(kr.easydoc.core.exceptions.DecryptionFailedException::class.java)
        assertThat(String(stored.previousBody.bytes, Charsets.UTF_8)).doesNotContain(large)
        jdbc
            .sql("UPDATE documents SET retention_expires_at=now()-interval '1 second' WHERE id=:id")
            .param("id", document)
            .update()
        assertThatThrownBy { service.apply(owner, conversion, command) }.isInstanceOf(NotFoundException::class.java)
        assertThat(repository.findSnapshot(owner, conversion, result.previousSnapshotId)).isNull()
        assertThatThrownBy { service.listPreviousBodies(owner, conversion) }.isInstanceOf(NotFoundException::class.java)
        jdbc.sql("DELETE FROM documents WHERE id=:id").param("id", document).update()
        assertThat(countSnapshots()).isZero()
    }

    @Test
    fun `draft payload participates in the same registered rotation adapter`() {
        val analysisId = UUID.randomUUID()
        val draftId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO action_guide_analyses
                    (id,conversion_id,based_on_content_revision,payload_encrypted,encryption_scheme,key_version)
                VALUES (:id,:conversion,1,:payload,'aes256gcm-v1',1)
                """.trimIndent(),
            ).param("id", analysisId)
            .param("conversion", conversion)
            .param(
                "payload",
                cipher.encrypt(PlainBody("분석"), analysisId, EncryptedField.ACTION_GUIDE_ANALYSIS_PAYLOAD).bytes,
            ).update()
        jdbc
            .sql(
                """
                INSERT INTO action_guide_drafts
                    (id,conversion_id,analysis_id,request_id,draft_revision,payload_encrypted,encryption_scheme,key_version)
                VALUES (:id,:conversion,:analysis,:request,1,:payload,'aes256gcm-v1',1)
                """.trimIndent(),
            ).param("id", draftId)
            .param("conversion", conversion)
            .param("analysis", analysisId)
            .param("request", UUID.randomUUID())
            .param(
                "payload",
                cipher.encrypt(PlainBody("보완 초안"), draftId, EncryptedField.ACTION_GUIDE_DRAFT_PAYLOAD).bytes,
            ).update()
        val nextCipher = cipher(2)
        val rotation = GuideDraftApplyKeyRotation(jdbc, nextCipher, transactions, 10)
        assertThat(rotation.run()).isEqualTo(1)
        assertThat(rotation.run()).isZero()
        val payload =
            jdbc
                .sql("SELECT payload_encrypted,encryption_scheme,key_version FROM action_guide_drafts WHERE id=:id")
                .param("id", draftId)
                .query { rs, _ ->
                    kr.easydoc.core.crypto.EncryptedContent(
                        rs.getBytes(1),
                        rs.getString(2),
                        rs.getInt(3),
                    )
                }.single()
        assertThat(payload.keyVersion).isEqualTo(2)
        assertThat(nextCipher.decrypt(payload, draftId, EncryptedField.ACTION_GUIDE_DRAFT_PAYLOAD).value)
            .isEqualTo("보완 초안")
    }

    private fun service(
        rows: GuideDraftApplyRepository = repository,
        writeCipher: AesGcmContentCipher = cipher,
        body: String = "보완된 전체 본문",
    ): GuideDraftApplyService =
        GuideDraftApplyService(
            JdbcConversionRepository(jdbc),
            rows,
            GuideDraftApplySource { _, _, _ ->
                draftReads++
                PlainBody(body)
            },
            writeCipher,
            SpringTransactionRunner(transactions),
        )

    private fun currentBody(): String {
        val result = JdbcConversionRepository(jdbc).findOwnedResult(owner, conversion)!!
        return cipher.decrypt(result.ciphertexts.editedText!!, conversion, EncryptedField.CONVERSION_EDITED_TEXT).value
    }

    private fun countSnapshots(): Int =
        jdbc
            .sql("SELECT count(*) FROM action_guide_body_snapshots")
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun seed() {
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,'stub')")
            .param("id", owner)
            .param("email", "$owner@example.test")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", workspace)
            .param("owner", owner)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO documents (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                    encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:body,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", document)
            .param("owner", owner)
            .param("workspace", workspace)
            .param(
                "body",
                cipher.encrypt(PlainBody("원문"), document, EncryptedField.DOCUMENT_SOURCE_TEXT).bytes,
            ).update()
        jdbc
            .sql(
                """
                INSERT INTO conversions (id,document_id,status,easy_text_encrypted,edited_text_encrypted,
                    encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:easy,:edited,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", conversion)
            .param("document", document)
            .param("easy", cipher.encrypt(PlainBody("AI 초안"), conversion, EncryptedField.CONVERSION_EASY_TEXT).bytes)
            .param(
                "edited",
                cipher.encrypt(PlainBody("기존 편집 본문"), conversion, EncryptedField.CONVERSION_EDITED_TEXT).bytes,
            ).update()
        val guideId = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO action_guides (id,conversion_id,based_on_content_revision,guide_revision,status,
                    payload_encrypted,encryption_scheme,key_version,reviewed_at,reviewed_by)
                VALUES (:id,:conversion,1,1,'reviewed',:body,'aes256gcm-v1',1,now(),:owner)
                """.trimIndent(),
            ).param("id", guideId)
            .param("conversion", conversion)
            .param("owner", owner)
            .param(
                "body",
                cipher.encrypt(PlainBody("기존 안내문"), guideId, EncryptedField.ACTION_GUIDE_PAYLOAD).bytes,
            ).update()
    }

    private fun cipher(version: Int): AesGcmContentCipher =
        AesGcmContentCipher(
            mapOf(
                1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })),
                2 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 9 })),
            ),
            version,
        )
}
