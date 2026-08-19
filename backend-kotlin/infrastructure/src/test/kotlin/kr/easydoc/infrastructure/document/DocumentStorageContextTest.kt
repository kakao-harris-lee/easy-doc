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

/**
 * **조립된 빈이 실제 키로 실제 DB 에 쓴다** — 게이트 25 X9 / privacy-gate F-6.
 *
 * ## 무엇이 비어 있었나
 *
 * 원장은 이 항목을 *"조립된 빈을 **실제 키**로 쓰는 통합 테스트 0"* 으로 열어 두었다.
 * 게이트 26 이 절반을 닫았고(자기점검 우회 스위치를 없애 모든 테스트 컨텍스트가 실제 키로
 * 자기점검을 **통과**하게 됐다), 남은 절반이 **실제 INSERT/SELECT** 다. 그 자리가 여기다.
 *
 * 이것이 없으면 §4.2·§4.3 의 모든 초록이 「503 을 잘 낸다」의 초록일 수 있다 — 저장 경로가
 * 조립된 적이 없으면 통합 테스트가 붙어도 오설정 갈래만 밟기 때문이다.
 *
 * ## 왜 `ApplicationContextRunner` 인가
 *
 * 기동 **실패 자체**를 재야 한다. `@SpringBootTest` 로는 컨텍스트 적재 실패가 곧 테스트
 * 오류라 「실패해야 한다」를 표현할 수 없다. 이 러너는 실패를 값으로 돌려준다
 * (`CryptoProfileExemptionTest` 와 같은 판단).
 *
 * `@DynamicPropertySource` 대신 [EncryptionProperties] 를 **빈으로 직접** 준다. 바인딩
 * (placeholder·`Secret` 변환)이 실제로 도는지는 `ConfigurationPropertiesBindingTest` 가
 * 이미 잰다 — 여기서 재려는 것은 **조립과 저장**이므로 축을 섞지 않는다. 키는 어느 쪽이든
 * **실행 시점 난수**이고 KCV 는 제품 코드 [KeyCheckValue] 로 계산한다(소스에 키 리터럴을
 * 적지 않는다 — 스캐너 `SECRET-LITERAL`).
 *
 * ## 「키를 빼면 503」은 **이 층에서 재지 않는다** (계획 §4.4-5 에서 바뀐 지점)
 *
 * 계획은 *"키를 빼면 업로드가 503 이 되는 케이스를 별도 컨텍스트(C-P)로 둔다"* 였다. 그
 * 갈래는 **게이트 26 이후 조립 경로에서 도달할 수 없다** — 자기점검이 기동을 끊으므로 키
 * 없는 컨텍스트는 애초에 뜨지 않고, 뜨지 않는 컨텍스트에는 업로드를 시킬 빈이 없다.
 * 그래서 여기서는 **「키 없는 컨텍스트는 기동을 거부한다」**를 재고(아래), 503 이 되는
 * 갈래 자체는 빈 층에서 잰다
 * (`JdbcDocumentStoreTest.쓰기 키가 없으면 아무것도 남지 않는다` — `ConfigurationException`
 * 이고 `GlobalExceptionHandler` 가 그것을 503 으로 옮긴다). 이 변경은 산출물에 기록했다.
 */
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

        // ⑴ 쓰기 세대 1 로 저장한다.
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

        // ⑵ 쓰기 세대를 2 로 올린 **다른 컨텍스트**가 그 행을 회전한다.
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

    // ---------------------------------------------------------------- 도구

    /**
     * 저장 경로 조립 — 제품 `@Configuration` 셋을 그대로 쓴다.
     *
     * `DataSource`·`JdbcClient`·`TransactionRunner` 는 실행 모듈(`api`·`worker`)이 Boot
     * 자동 설정으로 얻는 것이라 여기서 손으로 준다. 그 밖의 빈(저장소·큐·코덱·유스케이스·
     * 암호기)은 **제품 설정 클래스가 만든다** — 그것이 이 테스트가 재려는 대상이다.
     */
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
        checkNotNull(JdbcDocumentRepository(JdbcClient.create(dataSource())).loadSourceText(documentId)) {
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
