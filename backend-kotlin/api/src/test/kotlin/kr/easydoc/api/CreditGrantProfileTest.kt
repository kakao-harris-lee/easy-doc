package kr.easydoc.api

import kr.easydoc.api.credit.CreditGrantRunner
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
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
 * `credit-grant` profile 의 실 배선(C2) — 계획
 * `docs/plans/2026-09-07-credit-accounts.md` §3 C2의 프로필 층 회귀 고정판.
 * `UsageReportProfileTest`와 같은 자리다 — CLI 인자(`--workspace --credits --reason --note`)를
 * 읽어 실제로 `workspace_credit_accounts`·`credit_transactions`를 반영하는지 본다. 인자
 * 검증 자체(형식·범위)는 `CreditGrantArgsTest`(Spring 없이)가 이미 재므로 여기서는
 * **프로필 배선**(성공 1건·거절 2갈래)만 잰다.
 *
 * `@SpringBootTest`는 `ApiApplication.main()`을 부르지 않으므로 `SpringApplication.exit`가
 * 실행되지 않는다 — `ApplicationRunner`(둘 다 컨텍스트 초기화 중 실행)가 이미 돈 뒤에도
 * 컨텍스트가 열려 있어, 러너의 `exitCode`도 DB 반영 결과도 같은 컨텍스트에서 그대로 잰다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=credit-grant"],
    args = [
        "--workspace=00000000-0000-4000-8000-000000000001",
        "--credits=50",
        "--reason=manual",
        "--note=파일럿 충전",
    ],
)
class CreditGrantProfileTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("시딩된 워크스페이스에 부여하면 잔액과 거래 1건이 남고 종료 코드 0이다")
    fun `부여가 반영되고 종료 코드 0이다`() {
        val runner = context.getBean(CreditGrantRunner::class.java)

        assertThat(runner.exitCode).isZero()

        val jdbc = JdbcClient.create(dataSourceOf(database))
        val account =
            jdbc
                .sql("SELECT balance, reserved FROM workspace_credit_accounts WHERE workspace_id = :id")
                .param("id", WORKSPACE_ID)
                .query { rs, _ -> rs.getInt("balance") to rs.getInt("reserved") }
                .single()
        assertThat(account.first).isEqualTo(50)
        assertThat(account.second).isZero()

        val transactions =
            jdbc
                .sql("SELECT kind, balance_delta, reason, note FROM credit_transactions WHERE workspace_id = :id")
                .param("id", WORKSPACE_ID)
                .query { rs, _ ->
                    Triple(rs.getString("kind"), rs.getInt("balance_delta"), rs.getString("reason")) to
                        rs.getString("note")
                }.list()
        assertThat(transactions).hasSize(1)
        val (fields, note) = transactions.single()
        assertThat(fields).isEqualTo(Triple("grant", 50, "manual"))
        assertThat(note).isEqualTo("파일럿 충전")
    }

    companion object {
        private val WORKSPACE_ID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        private val OWNER_ID = UUID.randomUUID()

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("credit_grant_profile")
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
            val jdbc = JdbcClient.create(dataSourceOf(database))
            jdbc
                .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
                .param("id", OWNER_ID)
                .param("email", "owner-$OWNER_ID@example.com")
                .param("hash", DUMMY_PHC)
                .update()
            jdbc
                .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
                .param("id", WORKSPACE_ID)
                .param("userId", OWNER_ID)
                .param("name", "크레딧 대상 공간")
                .update()
            // V15 backfill 은 이 문장 이전에 이미 돌았으므로(마이그레이션이 먼저 적용된
            // 뒤에 이 워크스페이스를 심는다) 계정 행을 직접 만든다 — `ensureAccount`와
            // 같은 불변식을 시딩 시점에도 지킨다.
            jdbc
                .sql("INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved) VALUES (:id, 0, 0)")
                .param("id", WORKSPACE_ID)
                .update()
        }
    }
}

/**
 * `--workspace`가 UUID 형식이 아니면 [kr.easydoc.api.credit.CreditGrantArgs.parse] 가
 * `IllegalArgumentException`을 던지고, [CreditGrantRunner] 가 그것을 잡아 종료 코드 1로
 * 끝낸다는 것을 프로필 배선으로 확인한다 — 별도 컨텍스트(다른 `args`)라 클래스를 나눴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=credit-grant"],
    args = ["--workspace=not-a-uuid", "--credits=10", "--reason=manual"],
)
class CreditGrantProfileInvalidArgsTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("형식이 어긋난 --workspace는 종료 코드 1이다")
    fun `잘못된 workspace는 실패로 끝난다`() {
        val runner = context.getBean(CreditGrantRunner::class.java)

        assertThat(runner.exitCode).isEqualTo(1)
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("credit_grant_profile_invalid")
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

/**
 * 존재하지 않는 워크스페이스는 [kr.easydoc.application.credit.CreditAccountRepository.ownerOf]
 * 가 `null`을 돌려주고, [CreditGrantRunner] 가 `NotFoundException`으로 옮겨 종료 코드 1로
 * 끝난다는 것을 프로필 배선으로 확인한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=credit-grant"],
    args = ["--workspace=00000000-0000-4000-8000-000000000099", "--credits=10", "--reason=manual"],
)
class CreditGrantProfileUnknownWorkspaceTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    @DisplayName("알 수 없는 워크스페이스는 종료 코드 1이다")
    fun `알 수 없는 워크스페이스는 실패로 끝난다`() {
        val runner = context.getBean(CreditGrantRunner::class.java)

        assertThat(runner.exitCode).isEqualTo(1)
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("credit_grant_profile_unknown")
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
