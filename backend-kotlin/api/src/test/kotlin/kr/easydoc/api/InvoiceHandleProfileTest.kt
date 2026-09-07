package kr.easydoc.api

import kr.easydoc.api.invoice.InvoiceHandleRunner
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.mail.FakeMailSender
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import javax.sql.DataSource

/**
 * `invoice-handle` profile 의 실 배선 — 계획
 * `docs/plans/2026-09-07-invoice-requests.md` §3의 프로필 층 회귀 고정판.
 * `CreditGrantProfileTest`와 같은 자리다 — 인자 검증 자체는 `InvoiceHandleArgsTest`(Spring
 * 없이)가 이미 재므로 여기서는 **프로필 배선**(발급·거절·이미 처리됨·알 수 없는 id)만 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=invoice-handle"],
    args = ["--id=00000000-0000-4000-8000-000000000101", "--status=issued"],
)
class InvoiceHandleProfileIssuedTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var mailSender: FakeMailSender

    @Test
    @DisplayName("발급 처리는 상태·handled_at을 반영하고 요청자에게 메일 1통을 보내며 종료 코드 0이다")
    fun `발급이 반영되고 종료 코드 0이다`() {
        val runner = context.getBean(InvoiceHandleRunner::class.java)

        assertThat(runner.exitCode).isZero()

        val jdbc = JdbcClient.create(dataSourceOf(database))
        val row =
            jdbc
                .sql("SELECT status, handled_at FROM invoice_requests WHERE id = :id")
                .param("id", REQUEST_ID)
                .query { rs, _ -> rs.getString("status") to (rs.getObject("handled_at") != null) }
                .single()
        assertThat(row.first).isEqualTo("issued")
        assertThat(row.second).isTrue()

        val mail = mailSender.sent.single { it.to.value == REQUESTER_EMAIL }
        assertThat(mail.subject).contains("발급")
    }

    companion object {
        private val REQUEST_ID = UUID.fromString("00000000-0000-4000-8000-000000000101")
        private const val REQUESTER_EMAIL = "invoice-issued@example.test"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("invoice_handle_issued")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            migrate(database)
            seedRequestedRow(database, REQUEST_ID, REQUESTER_EMAIL)
        }
    }
}

/** 거절 처리는 운영자 메모를 저장하고 메일 본문에 사유를 담는다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=invoice-handle"],
    args = ["--id=00000000-0000-4000-8000-000000000102", "--status=rejected", "--note=사업자번호 확인 불가"],
)
class InvoiceHandleProfileRejectedTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var mailSender: FakeMailSender

    @Test
    @DisplayName("거절 처리는 메모를 저장하고 메일 본문에 사유를 담으며 종료 코드 0이다")
    fun `거절이 반영되고 메일에 사유가 담긴다`() {
        val runner = context.getBean(InvoiceHandleRunner::class.java)

        assertThat(runner.exitCode).isZero()

        val jdbc = JdbcClient.create(dataSourceOf(database))
        val note =
            jdbc
                .sql("SELECT operator_note FROM invoice_requests WHERE id = :id")
                .param("id", REQUEST_ID)
                .query { rs, _ -> rs.getString("operator_note") }
                .single()
        assertThat(note).isEqualTo("사업자번호 확인 불가")

        val mail = mailSender.sent.single { it.to.value == REQUESTER_EMAIL }
        assertThat(mail.textBody).contains("사업자번호 확인 불가")
    }

    companion object {
        private val REQUEST_ID = UUID.fromString("00000000-0000-4000-8000-000000000102")
        private const val REQUESTER_EMAIL = "invoice-rejected@example.test"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("invoice_handle_rejected")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            migrate(database)
            seedRequestedRow(database, REQUEST_ID, REQUESTER_EMAIL)
        }
    }
}

/** 이미 처리된 요청(두 번째 실행)은 종료 코드 1이다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=invoice-handle"],
    args = ["--id=00000000-0000-4000-8000-000000000103", "--status=issued"],
)
class InvoiceHandleProfileAlreadyHandledTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("이미 처리된 요청은 종료 코드 1이다")
    fun `이미 처리된 요청은 실패로 끝난다`() {
        val runner = context.getBean(InvoiceHandleRunner::class.java)

        assertThat(runner.exitCode).isEqualTo(1)
    }

    companion object {
        private val REQUEST_ID = UUID.fromString("00000000-0000-4000-8000-000000000103")

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("invoice_handle_already")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            migrate(database)
            // 이미 issued로 끝난 행을 미리 심어 「두 번째 실행」을 흉내 낸다.
            seedRequestedRow(database, REQUEST_ID, "invoice-already@example.test", status = "issued")
        }
    }
}

/** 존재하지 않는 id는 종료 코드 1이다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=invoice-handle"],
    args = ["--id=00000000-0000-4000-8000-000000000199", "--status=issued"],
)
class InvoiceHandleProfileUnknownIdTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("알 수 없는 id는 종료 코드 1이다")
    fun `알 수 없는 id는 실패로 끝난다`() {
        val runner = context.getBean(InvoiceHandleRunner::class.java)

        assertThat(runner.exitCode).isEqualTo(1)
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("invoice_handle_unknown")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }

        @JvmStatic
        @BeforeAll
        fun migrateOnly() {
            migrate(database)
        }
    }
}

private const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

private fun migrate(database: DatabaseHandle) {
    Flyway
        .configure()
        .dataSource(database.jdbcUrl, database.username, database.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}

private fun dataSourceOf(database: DatabaseHandle): DataSource =
    DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

/** 소유자·워크스페이스·요청 행 하나를 심는다. 기본 상태는 `requested`다. */
private fun seedRequestedRow(
    database: DatabaseHandle,
    requestId: UUID,
    contactEmail: String,
    status: String = "requested",
) {
    val ownerId = UUID.randomUUID()
    val workspaceId = UUID.randomUUID()
    val jdbc = JdbcClient.create(dataSourceOf(database))
    jdbc
        .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
        .param("id", ownerId)
        .param("email", "owner-$ownerId@example.test")
        .param("hash", DUMMY_PHC)
        .update()
    jdbc
        .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
        .param("id", workspaceId)
        .param("userId", ownerId)
        .param("name", "세금계산서 대상 공간")
        .update()
    jdbc
        .sql(
            """
            INSERT INTO invoice_requests (
                id, workspace_id, owner_user_id, business_number, company_name, contact_email,
                period_from, period_to, status
            ) VALUES (
                :id, :workspaceId, :ownerId, '2208162517', '쉬운글 주식회사', :contactEmail,
                '2026-08-01', '2026-08-31', :status
            )
            """.trimIndent(),
        ).param("id", requestId)
        .param("workspaceId", workspaceId)
        .param("ownerId", ownerId)
        .param("contactEmail", contactEmail)
        .param("status", status)
        .update()
}
