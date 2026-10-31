package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.illustration.generation.IllustrationGenerationJobAcquire
import kr.easydoc.application.illustration.generation.StoredGeneratedIllustrationAsset
import kr.easydoc.application.illustration.generation.StoredIllustrationGenerationJob
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.illustration.generation.IllustrationGenerationJobStatus
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IllustrationGenerationKeyRotationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: JdbcClient
    private val oldCipher = cipherWith(1)

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("illustration_generation_rotation")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
    }

    @Test
    fun `worker 모드가 접수 모드와 다르면 양방향 모두 provider 호출 전에 거부한다`() {
        val fixture = Fixture()
        val provider = FakeIllustrationGenerationProvider()
        val runner = ProviderIllustrationGenerationJobRunner(oldCipher, provider, BigDecimal.ZERO)
        val plaintext =
            """
            {"final_prompt":"p","execution_mode":"provider","metadata":{
            "suggestion_id":"${UUID.randomUUID()}","analysis_version":"v","configuration_revision":"rev",
            "purpose":"procedure","reason":"r","body_range":{"start":0,"end":1},
            "source_anchors":[],"scenes":["s"],"preserved_facts":[],"alt_text":"a"}}
            """.trimIndent()
        val input =
            oldCipher.encrypt(
                PlainBody(plaintext),
                fixture.jobId,
                EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT,
            )
        assertThatThrownBy { runner.prepare(fixture.job(input)) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(provider.calls).isEmpty()

        val otherProvider =
            object : kr.easydoc.application.illustration.generation.IllustrationGenerationProvider {
                override val name = "openai"

                override fun generate(
                    request: kr.easydoc.core.illustration.generation.IllustrationGenerationRequest,
                ): kr.easydoc.core.illustration.generation.GeneratedIllustration = error("호출되면 안 됨")
            }
        val fakeInput =
            oldCipher.encrypt(
                PlainBody(plaintext.replace("\"provider\"", "\"fake\"")),
                fixture.jobId,
                EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT,
            )
        assertThatThrownBy {
            ProviderIllustrationGenerationJobRunner(
                oldCipher,
                otherProvider,
            ).prepare(fixture.job(fakeInput))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Suppress("LongMethod")
    @Test
    fun `생성 입력과 자산 metadata PNG를 새 세대로 재봉인하고 AAD를 유지한다`() {
        val fixture = seed()
        val jobs = JdbcIllustrationGenerationJobRepository(jdbc)
        val assets = JdbcGeneratedIllustrationAssetRepository(jdbc)
        val input =
            oldCipher.encrypt(
                PlainBody(INPUT_TEXT),
                fixture.jobId,
                EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT,
            )
        jobs.insert(fixture.job(input))
        val lease =
            (
                jobs.acquire("rotation-worker", java.time.Duration.ofSeconds(120), 5)
                    as IllustrationGenerationJobAcquire.Held
            ).lease
        jobs.lockIfHeld(lease)
        jobs.markProviderStarted(lease, UUID.randomUUID(), NOW)
        val assetId = UUID.randomUUID()
        val metadata =
            oldCipher.encrypt(
                PlainBody(METADATA_TEXT),
                assetId,
                EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA,
            )
        val png =
            oldCipher.encryptBytes(
                PlainBytes(PNG_BYTES),
                assetId,
                EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG,
            )
        val held = jobs.lockIfHeld(lease)!!
        assets.insertAsset(
            held,
            StoredGeneratedIllustrationAsset(
                assetId,
                fixture.jobId,
                fixture.conversionId,
                1,
                metadata,
                png,
                PNG_BYTES.size,
                1024,
                1024,
                "fake",
            ),
        )
        jobs.markSucceeded(lease, NOW)

        val newCipher = cipherWith(2)
        val rotation =
            IllustrationGenerationKeyRotation(
                jdbc,
                newCipher,
                TransactionTemplate(DataSourceTransactionManager(dataSource)),
                batchSize = 1,
            )
        assertThat(rotation.runJobs()).isEqualTo(1)
        assertThat(rotation.runAssets()).isEqualTo(1)
        assertThat(rotation.runJobs()).isZero()
        assertThat(rotation.runAssets()).isZero()

        val storedJob = jobs.findOwned(fixture.ownerId, fixture.conversionId, fixture.jobId)!!
        assertThat(storedJob.inputPayload.keyVersion).isEqualTo(2)
        assertThat(
            newCipher
                .decrypt(
                    storedJob.inputPayload,
                    fixture.jobId,
                    EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT,
                ).value,
        ).isEqualTo(INPUT_TEXT)
        val storedAsset = assets.findOwned(fixture.ownerId, fixture.conversionId, assetId)!!
        assertThat(storedAsset.metadata.keyVersion).isEqualTo(2)
        assertThat(storedAsset.png.keyVersion).isEqualTo(2)
        assertThat(
            newCipher
                .decrypt(
                    storedAsset.metadata,
                    assetId,
                    EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA,
                ).value,
        ).isEqualTo(METADATA_TEXT)
        assertThat(
            newCipher
                .decryptBytes(
                    storedAsset.png,
                    assetId,
                    EncryptedField.ILLUSTRATION_GENERATION_ASSET_PNG,
                ).value,
        ).isEqualTo(PNG_BYTES)
        assertThatThrownBy {
            newCipher.decryptBytes(
                storedAsset.png,
                assetId,
                EncryptedField.ILLUSTRATION_GENERATION_ASSET_METADATA,
            )
        }.isInstanceOf(RuntimeException::class.java)
    }

    @Suppress("LongMethod")
    private fun seed(): Fixture {
        val fixture = Fixture()
        jdbc
            .sql("INSERT INTO users (id,email,password_hash) VALUES (:id,:email,'phc')")
            .param("id", fixture.ownerId)
            .param("email", "rotate-${fixture.ownerId}@example.test")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id,user_id,name) VALUES (:id,:owner,'공간')")
            .param("id", fixture.workspaceId)
            .param("owner", fixture.ownerId)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO documents (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                                       encryption_scheme,key_version,char_count)
                VALUES (:id,:owner,:workspace,'제목','txt',:bytes,'aes256gcm-v1',1,10)
                """.trimIndent(),
            ).param("id", fixture.documentId)
            .param("owner", fixture.ownerId)
            .param("workspace", fixture.workspaceId)
            .param("bytes", byteArrayOf(1))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO conversions (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES (:id,:document,'done',:bytes,'aes256gcm-v1',1,1)
                """.trimIndent(),
            ).param("id", fixture.conversionId)
            .param("document", fixture.documentId)
            .param("bytes", byteArrayOf(2))
            .update()
        jdbc
            .sql(
                """
                INSERT INTO illustration_suggestion_jobs
                    (id,request_id,owner_user_id,workspace_id,document_id,conversion_id,
                     expected_content_revision,based_on_content_revision,input_fingerprint,
                     status,reserved_credits,settlement)
                VALUES (:id,gen_random_uuid(),:owner,:workspace,:document,:conversion,1,1,:fingerprint,
                        'succeeded',0,'not_charged')
                """.trimIndent(),
            ).param("id", fixture.suggestionJobId)
            .param("owner", fixture.ownerId)
            .param("workspace", fixture.workspaceId)
            .param("document", fixture.documentId)
            .param("conversion", fixture.conversionId)
            .param("fingerprint", FINGERPRINT)
            .update()
        jdbc
            .sql(
                """
                INSERT INTO illustration_suggestion_results
                    (id,job_id,conversion_id,based_on_content_revision,payload_encrypted,encryption_scheme,key_version)
                VALUES (:id,:job,:conversion,1,:payload,'aes256gcm-v1',1)
                """.trimIndent(),
            ).param("id", fixture.suggestionResultId)
            .param("job", fixture.suggestionJobId)
            .param("conversion", fixture.conversionId)
            .param("payload", byteArrayOf(3))
            .update()
        return fixture
    }

    private fun cipherWith(version: Int) =
        AesGcmContentCipher(
            mapOf(
                1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })),
                2 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 8 })),
            ),
            writeKeyVersion = version,
        )

    private class Fixture {
        val ownerId: UUID = UUID.randomUUID()
        val workspaceId: UUID = UUID.randomUUID()
        val documentId: UUID = UUID.randomUUID()
        val conversionId: UUID = UUID.randomUUID()
        val jobId: UUID = UUID.randomUUID()
        val suggestionJobId: UUID = UUID.randomUUID()
        val suggestionResultId: UUID = UUID.randomUUID()

        fun job(input: kr.easydoc.core.crypto.EncryptedContent) =
            StoredIllustrationGenerationJob(
                jobId,
                ownerId,
                workspaceId,
                documentId,
                conversionId,
                UUID.randomUUID(),
                suggestionResultId,
                UUID.randomUUID(),
                "r7-illustration-suggestion-2",
                FINGERPRINT,
                1,
                FINGERPRINT,
                input,
                BigDecimal.ZERO,
                IllustrationGenerationJobStatus.QUEUED,
                null,
                null,
                null,
                NOW,
                NOW,
            )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-31T00:00:00Z")
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x80.toByte())
        const val INPUT_TEXT = "서버가 확정한 생성 입력"
        const val METADATA_TEXT = "근거와 대체텍스트"
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
