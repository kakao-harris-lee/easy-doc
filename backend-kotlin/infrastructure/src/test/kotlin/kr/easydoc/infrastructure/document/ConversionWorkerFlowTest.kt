package kr.easydoc.infrastructure.document

import kr.easydoc.application.conversion.ConversionCompletedNotifier
import kr.easydoc.application.conversion.ConversionJobHeartbeat
import kr.easydoc.application.conversion.ConversionJobLease
import kr.easydoc.application.conversion.ConversionJobOutcome
import kr.easydoc.application.conversion.ConversionWorkerPolicy
import kr.easydoc.application.conversion.ConversionWorkerRuntime
import kr.easydoc.application.conversion.ConversionWorkerStores
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.application.conversion.ProcessConversionJob
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.security.Secret
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcUserRepository
import kr.easydoc.infrastructure.auth.JdbcWorkspaceRepository
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import kr.easydoc.infrastructure.mail.FakeMailSender
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
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** pending → processing → done|failed 수직 흐름. LLM 은 대역이고 DB 는 실제 PostgreSQL 이다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversionWorkerFlowTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var users: JdbcUserRepository
    private lateinit var workspaces: JdbcWorkspaceRepository
    private lateinit var documents: JdbcDocumentRepository
    private lateinit var conversions: JdbcConversionRepository
    private lateinit var queue: JdbcConversionQueue
    private lateinit var work: JdbcConversionWorkStore
    private lateinit var cipher: AesGcmContentCipher
    private lateinit var transaction: SpringTransactionRunner
    private lateinit var service: DocumentService

    @BeforeAll
    fun prepare() {
        val database: DatabaseHandle = PostgresTestSupport.createEmptyDatabase("conversion_worker_flow")
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
        documents = JdbcDocumentRepository(jdbc)
        conversions = JdbcConversionRepository(jdbc)
        queue = JdbcConversionQueue(jdbc)
        work = JdbcConversionWorkStore(jdbc)
        cipher =
            AesGcmContentCipher(
                keyMaterial = mapOf(1 to KEY),
                writeKeyVersion = 1,
                random = SecureRandom(),
            )
        transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource)))
        service =
            DocumentService(
                storage = DocumentStorage(documents, JdbcDocumentOriginalRepository(jdbc), conversions, queue),
                workspaces = JdbcWorkspaceLookup(jdbc),
                cipher = cipher,
                extractor = DocumentTextExtractor { _, _ -> ExtractedDocument(SourceFormat.DOCX, "추출") },
                transaction = transaction,
                users = JdbcUserRepository(jdbc),
            )
    }

    @Test
    @DisplayName("업로드한 문서는 pending → processing → done 으로 끝난다")
    fun `완료 경로를 관찰한다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "공간").id
        val accepted = service.createFromText(owner, "복지 급여를 안내합니다.", null, workspace.toString())
        assertThat(statusOf(accepted.conversionId)).isEqualTo(ConversionStatus.PENDING.wireName)

        val seen = mutableListOf<String>()
        val observing =
            ObservingProvider(FakeLlmProvider.replying("오늘 서류를 내세요.")) {
                seen += statusOf(accepted.conversionId)
            }
        val jobs = processor(observing)

        assertThat(jobs.processNext()).isEqualTo(ConversionJobOutcome.COMPLETED)
        assertThat(seen).containsExactly(ConversionStatus.PROCESSING.wireName)
        assertThat(statusOf(accepted.conversionId)).isEqualTo(ConversionStatus.DONE.wireName)
        assertThat(jobState(accepted.conversionId)).isEqualTo(JdbcConversionQueue.DONE_STATE)

        val stored = checkNotNull(conversions.findOwnedResult(owner, accepted.conversionId))
        val sealedEasy = checkNotNull(stored.ciphertexts.easyText)
        val easy = cipher.decrypt(sealedEasy, accepted.conversionId, EncryptedField.CONVERSION_EASY_TEXT)
        assertThat(easy.value).isEqualTo("오늘 서류를 내세요.")
    }

    @Test
    @DisplayName("이미 끝난 변환은 늦게 도착한 완료 쓰기가 본문을 바꾸지 않는다")
    fun `끝난 결과는 CAS 로 막는다`() {
        val owner = newUser()
        val workspace = workspaces.create(owner, "공간").id
        val accepted = service.createFromText(owner, "복지 급여를 안내합니다.", null, workspace.toString())
        assertThat(processor(FakeLlmProvider.replying("오늘 서류를 내세요.")).processNext())
            .isEqualTo(ConversionJobOutcome.COMPLETED)

        val first = checkNotNull(conversions.findOwnedResult(owner, accepted.conversionId))
        val original =
            first.ciphertexts.easyText
                ?.bytes
                ?.copyOf()

        jdbc
            .sql(
                """
                UPDATE conversion_jobs
                SET state = :ready, attempts = 0, lease_owner = NULL, lease_until = NULL, next_attempt_at = now()
                WHERE conversion_id = :id
                """.trimIndent(),
            ).param("ready", JdbcConversionQueue.READY_STATE)
            .param("id", accepted.conversionId)
            .update()

        assertThat(processor(FakeLlmProvider.replying("다른 본문으로 덮으려 합니다.")).processNext())
            .isEqualTo(ConversionJobOutcome.DROPPED)
        val again = checkNotNull(conversions.findOwnedResult(owner, accepted.conversionId))
        assertThat(again.status).isEqualTo(ConversionStatus.DONE)
        assertThat(again.ciphertexts.easyText?.bytes).isEqualTo(original)
    }

    private fun processor(provider: LlmProvider): ProcessConversionJob =
        ProcessConversionJob(
            stores =
                ConversionWorkerStores(
                    leases = queue,
                    work = work,
                    cipher = cipher,
                    maskedItems = MaskedItemCodec(),
                ),
            convert = ConvertDocumentUseCase(provider),
            transaction = transaction,
            runtime =
                ConversionWorkerRuntime(
                    heartbeat = ImmediateHeartbeat(queue),
                    policy =
                        ConversionWorkerPolicy(
                            owner = "flow-worker",
                            leaseDuration = Duration.ofMinutes(2),
                            maxAttempts = 3,
                            retryBackoff = Duration.ofSeconds(1),
                        ),
                ),
            notifier =
                ConversionCompletedNotifier(
                    store = JdbcConversionNotificationStore(jdbc),
                    mailSender = FakeMailSender(),
                    publicBaseUrl = "http://localhost:5173",
                ),
        )

    // 이메일 인증 게이트는 `POST /documents` 앞이다 — 이 파일은 그 게이트를 재지 않으므로
    // 실물 인증 흐름 대신 저장소를 직접 인증 완료로 만든다.
    private fun newUser(): UUID =
        users.create("u${UUID.randomUUID()}@example.com", PasswordHash(DUMMY_PHC)).id.also(users::markEmailVerified)

    private fun statusOf(conversionId: UUID): String =
        jdbc
            .sql("SELECT status FROM conversions WHERE id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getString("status") }
            .single()

    private fun jobState(conversionId: UUID): String =
        jdbc
            .sql("SELECT state FROM conversion_jobs WHERE conversion_id = :id")
            .param("id", conversionId)
            .query { rs, _ -> rs.getString("state") }
            .single()

    private class ObservingProvider(
        private val delegate: FakeLlmProvider,
        private val onComplete: () -> Unit,
    ) : LlmProvider {
        override val name: String = delegate.name

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ) = onComplete().let { delegate.complete(prompt, options) }
    }

    private class ImmediateHeartbeat(private val leases: JdbcConversionQueue) : ConversionJobHeartbeat {
        override fun <T> whileHeld(
            lease: ConversionJobLease,
            block: () -> T,
        ): T {
            leases.renew(lease, Duration.ofMinutes(2))
            return block()
        }
    }

    private companion object {
        val KEY: Secret =
            Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))

        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"
    }
}
