package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.RotationOutcome
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.crypto.CryptoConfiguration
import kr.easydoc.infrastructure.crypto.EncryptionKeyProperties
import kr.easydoc.infrastructure.crypto.EncryptionProperties
import kr.easydoc.infrastructure.crypto.KeyCheckValue
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.ingest.IngestConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ContextConsumer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.function.Supplier
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/** 조립된 빈이 실제 키로 실제 DB 에 쓴다 — 게이트 25 X9 / privacy-gate F-6. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentStorageContextTest {
    private lateinit var database: DatabaseHandle
    private lateinit var owner: UUID
    private lateinit var workspace: UUID

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("document_storage_context")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val jdbc = JdbcClient.create(dataSource())
        owner = JdbcUserRepository(jdbc).create("context@example.test", FIXTURE_HASH).id
        workspace = JdbcWorkspaceRepository(jdbc).create(owner, "기본").id
    }

    @Test
    @DisplayName("자기점검을 **통과한** 컨텍스트가 실제 행을 쓰고 그 행을 다시 연다 (X9)")
    fun `조립된 빈이 실제 키로 저장하고 읽는다`() {
        runner(keys = listOf(entryFor(1, KEY_GEN_1)), writeKeyVersion = 1)
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).hasNotFailed()
                    val service = context.getBean(DocumentService::class.java)

                    val accepted = service.createFromText(owner, PROBE_BODY, null, workspace)

                    val stored = readSourceText(accepted.documentId)
                    assertThat(String(stored.bytes, Charsets.UTF_8))
                        .describedAs("원문이 평문으로 저장됐다")
                        .doesNotContain(PROBE_BODY)
                    assertThat(stored.keyVersion).isEqualTo(1)

                    val cipher = context.getBean(ContentCipher::class.java)
                    assertThat(
                        cipher
                            .decrypt(stored, accepted.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
                            .value,
                    ).isEqualTo(PROBE_BODY)
                    assertThat(sourceFormatOf(accepted.documentId)).isEqualTo(SourceFormat.TEXT.wireName)
                },
            )
    }

    @Test
    @DisplayName("자기점검이 **정말 돌고 있다** — 검사값이 틀린 키를 주면 컨텍스트가 뜨지 않는다")
    fun `자기점검이 켜져 있다`() {
        val wrongCheckValue = EncryptionKeyProperties(version = 1, value = KEY_GEN_1, kcv = WRONG_CHECK_VALUE)

        runner(keys = listOf(wrongCheckValue), writeKeyVersion = 1)
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context)
                        .describedAs("검사값이 틀렸는데 떴다 — 자기점검이 이 조립 경로에서 돌지 않는다")
                        .hasFailed()
                    assertThat(context.startupFailure)
                        .rootCause()
                        .isInstanceOf(ConfigurationException::class.java)
                },
            )
    }

    @Test
    @DisplayName("키가 없으면 컨텍스트가 **기동을 거부한다** — 조립 경로에 저장 빈이 아예 생기지 않는다 (C-P)")
    fun `키가 없으면 기동을 거부한다`() {
        runner(keys = emptyList(), writeKeyVersion = 1)
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure)
                        .rootCause()
                        .isInstanceOf(ConfigurationException::class.java)
                },
            )
    }

    @Test
    @DisplayName("**2세대 회전 왕복** — 조립된 빈이 v1 로 쓴 행을 조립된 빈이 v2 로 옮기고 다시 연다")
    fun `조립된 빈으로 회전 왕복한다`() {
        val bothGenerations = listOf(entryFor(1, KEY_GEN_1), entryFor(2, KEY_GEN_2))
        var documentId: UUID? = null
        var conversionId: UUID? = null

        runner(keys = bothGenerations, writeKeyVersion = 1)
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    val accepted =
                        context
                            .getBean(DocumentService::class.java)
                            .createFromText(owner, ROTATION_BODY, null, workspace)
                    documentId = accepted.documentId
                    conversionId = accepted.conversionId
                    assertThat(readSourceText(accepted.documentId).keyVersion).isEqualTo(1)
                },
            )

        val storedDocument = checkNotNull(documentId)
        val storedConversion = checkNotNull(conversionId)

        runner(keys = bothGenerations, writeKeyVersion = 2)
            .run(
                ContextConsumer { context: AssertableApplicationContext ->
                    assertThat(context).hasNotFailed()
                    val rotation = context.getBean(EnvelopeRotation::class.java)

                    assertThat(rotation.rotateDocument(storedDocument)).isEqualTo(RotationOutcome.ROTATED)
                    assertThat(rotation.rotateConversion(storedConversion)).isEqualTo(RotationOutcome.ROTATED)
                    assertThat(rotation.rotateDocument(storedDocument))
                        .describedAs("두 번째 회전은 할 일이 없어야 한다")
                        .isEqualTo(RotationOutcome.ALREADY_CURRENT)

                    val rotated = readSourceText(storedDocument)
                    assertThat(rotated.keyVersion).isEqualTo(2)
                    assertThat(
                        context
                            .getBean(ContentCipher::class.java)
                            .decrypt(rotated, storedDocument, EncryptedField.DOCUMENT_SOURCE_TEXT)
                            .value,
                    ).isEqualTo(ROTATION_BODY)
                },
            )
    }

    /** 저장 경로 조립 — 제품 `@Configuration` 셋을 그대로 쓴다. */
    private fun runner(
        keys: List<EncryptionKeyProperties>,
        writeKeyVersion: Int,
    ): ApplicationContextRunner {
        val dataSource = dataSource()
        return ApplicationContextRunner()
            .withUserConfiguration(
                CryptoConfiguration::class.java,
                DocumentConfiguration::class.java,
                IngestConfiguration::class.java,
            ).withBean(JdbcClient::class.java, Supplier { JdbcClient.create(dataSource) })
            .withBean(
                TransactionRunner::class.java,
                Supplier {
                    SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
                },
            ).withBean(
                EncryptionProperties::class.java,
                Supplier { EncryptionProperties(writeKeyVersion = writeKeyVersion, keys = keys) },
            )
    }

    private fun readSourceText(documentId: UUID) =
        checkNotNull(JdbcDocumentRepository(JdbcClient.create(dataSource())).lockSourceText(documentId)) {
            "문서 행이 없다 — 저장이 실제로 일어나지 않았다"
        }

    private fun sourceFormatOf(documentId: UUID): String =
        JdbcClient
            .create(dataSource())
            .sql("SELECT source_format FROM documents WHERE id = :id")
            .param("id", documentId)
            .query { rs, _ -> rs.getString("source_format") }
            .single()

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun entryFor(
        version: Int,
        key: Secret,
    ) = EncryptionKeyProperties(
        version = version,
        value = key,
        kcv = KeyCheckValue.of(SecretKeySpec(Base64.getDecoder().decode(key.reveal()), "AES")),
    )

    private companion object {
        const val PROBE_BODY = "행정복지센터 안내문 본문"
        const val ROTATION_BODY = "회전 대상 안내문"

        /** 12자리 hex 이되 어떤 키의 검사값도 아니다. 값의 모양만 맞춘다. */
        const val WRONG_CHECK_VALUE = "000000000000"

        /** AES-256. */
        const val KEY_BYTES = 32

        val FIXTURE_HASH = PasswordHash("\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")

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
