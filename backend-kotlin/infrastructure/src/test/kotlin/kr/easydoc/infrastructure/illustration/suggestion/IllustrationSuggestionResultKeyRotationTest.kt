package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionJob
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionResult
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionJobStatus
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * 봉인 열에 회전 경로가 있는가 — 옛 세대를 설정에서 내리는 순간 열리지 않는 행이 생기지 않게
 * (`EnvelopeRotationTest` 「봉인된 열 전부가 회전 경로를 가진다」가 이 파일을 가리킨다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IllustrationSuggestionResultKeyRotationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var results: JdbcIllustrationSuggestionResultRepository

    private val writeCipher = cipherWith(writeKeyVersion = 1)

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("illustration_suggestion_rotation")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
        results = JdbcIllustrationSuggestionResultRepository(jdbc)
    }

    @Test
    @DisplayName("회전이 결과 payload 를 새 세대로 재봉인하고 재실행은 no-op 이다")
    fun `회전이 결과를 재봉인한다`() {
        val fixture = seed()
        val resultId = UUID.randomUUID()
        val sealed = writeCipher.encrypt(PlainBody(PLAINTEXT), resultId, EncryptedField.ILLUSTRATION_SUGGESTION_RESULT)
        assertThat(
            results.insertResult(
                fixture.job,
                StoredIllustrationSuggestionResult(
                    resultId,
                    fixture.job.jobId,
                    fixture.job.conversionId,
                    fixture.job.basedOnContentRevision,
                    sealed,
                    NOW,
                ),
            ),
        ).isTrue()

        val rotatedCipher = cipherWith(writeKeyVersion = 2)
        val rotation =
            IllustrationSuggestionResultKeyRotation(
                jdbc,
                rotatedCipher,
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                batchSize = 1,
            )

        assertThat(rotation.run()).isEqualTo(1)
        assertThat(rotation.run()).isZero()

        val stored = results.findLatestOwned(fixture.ownerId, fixture.job.conversionId)!!
        assertThat(stored.payload.keyVersion).isEqualTo(2)
        assertThat(
            rotatedCipher.decrypt(stored.payload, resultId, EncryptedField.ILLUSTRATION_SUGGESTION_RESULT).value,
        ).isEqualTo(PLAINTEXT)
        // 결속은 행 id + 열 이름이다 — 다른 열 이름으로는 열리지 않는다.
        assertThatThrownBy {
            rotatedCipher.decrypt(stored.payload, resultId, EncryptedField.ACTION_GUIDE_CANDIDATE_PAYLOAD)
        }.isInstanceOf(RuntimeException::class.java)
    }

    private fun cipherWith(writeKeyVersion: Int) =
        AesGcmContentCipher(
            mapOf(
                1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })),
                2 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 8 })),
            ),
            writeKeyVersion = writeKeyVersion,
        )

    private fun seed(): Fixture {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        seedOwner(ownerId, workspaceId)
        seedDocument(ownerId, workspaceId, documentId, conversionId)
        seedJob(JobRow(jobId, ownerId, workspaceId, documentId, conversionId))
        return Fixture(
            ownerId,
            StoredIllustrationSuggestionJob(
                jobId,
                ownerId,
                workspaceId,
                documentId,
                conversionId,
                UUID.randomUUID(),
                basedOnContentRevision = 1,
                reservedCredits = BigDecimal.ONE,
                status = IllustrationSuggestionJobStatus.SUCCEEDED,
                failureCode = null,
                executionId = null,
                providerStartedAt = NOW,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
    }

    private fun seedOwner(
        ownerId: UUID,
        workspaceId: UUID,
    ) {
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,:password)")
            .param("id", ownerId)
            .param("email", "rot-$ownerId@example.test")
            .param("password", DUMMY_PHC)
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", workspaceId)
            .param("owner", ownerId)
            .update()
    }

    private fun seedDocument(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO documents
                    (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                     encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:bytes,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", documentId)
            .param("owner", ownerId)
            .param("workspace", workspaceId)
            .param("bytes", byteArrayOf(1))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions
                    (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:bytes,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", conversionId)
            .param("document", documentId)
            .param("bytes", byteArrayOf(2))
            .update()
    }

    private fun seedJob(job: JobRow) {
        jdbc
            .sql(
                """
                INSERT INTO illustration_suggestion_jobs
                    (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                     expected_content_revision, based_on_content_revision, input_fingerprint,
                     status, settlement, reserved_credits, provider_attempts, provider_execution_id,
                     provider_started_at)
                VALUES (:id, :request, :owner, :workspace, :document, :conversion, 1, 1, :fingerprint,
                        'succeeded', 'consumed', 1.0, 1, :execution, now())
                """.trimIndent(),
            ).param("id", job.jobId)
            .param("request", UUID.randomUUID())
            .param("owner", job.ownerId)
            .param("workspace", job.workspaceId)
            .param("document", job.documentId)
            .param("conversion", job.conversionId)
            .param("fingerprint", FINGERPRINT)
            .param("execution", UUID.randomUUID())
            .update()
    }

    /** 작업 한 행이 매다는 축들. 인자 수를 줄이려고 묶는다. */
    private class JobRow(
        val jobId: UUID,
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    )

    private class Fixture(
        val ownerId: UUID,
        val job: StoredIllustrationSuggestionJob,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-24T00:00:00Z")
        const val PLAINTEXT = """{"schema_version":1,"analysis_version":"v","dropped_count":0,"suggestions":[]}"""
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
