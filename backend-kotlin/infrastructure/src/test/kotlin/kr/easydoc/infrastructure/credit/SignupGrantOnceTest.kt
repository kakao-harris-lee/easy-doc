package kr.easydoc.infrastructure.credit

import kr.easydoc.application.auth.normalizeEmail
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.SignupGrantEmailHasher
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import javax.sql.DataSource

/**
 * 가입 크레딧은 계정당(정확히는 이메일당) 한 번(계획
 * `docs/plans/2026-09-09-account-deletion.md` §7~§9, 후속 「가입 크레딧은 계정당 한 번」) —
 * 실제 PostgreSQL 위에서 [CreditAccountService]·[JdbcCreditAccountRepository]·
 * [JdbcSignupGrantLedger] 를 함께 세워 잰다. 수용 기준 1·2·5·6·8 이 실 행 수를 센다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignupGrantOnceTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var accountRepository: JdbcCreditAccountRepository
    private lateinit var ledger: JdbcSignupGrantLedger
    private lateinit var service: CreditAccountService
    private lateinit var hasher: SignupGrantEmailHasher

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("signup_grant_once")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbc = JdbcClient.create(dataSource())
        accountRepository = JdbcCreditAccountRepository(jdbc)
        ledger = JdbcSignupGrantLedger(jdbc)
        hasher = SignupGrantEmailHasher(Secret("integration-test-pepper"))
        service =
            CreditAccountService(
                repository = accountRepository,
                enforced = false,
                signupGrant = SIGNUP_GRANT,
                signupGrantLedger = ledger,
                emailHasher = hasher,
            )
    }

    @Test
    @DisplayName("AC1 — 처음 가입하면 signupGrant 만큼 부여되고 signup_grant_records 에 1건 남는다")
    fun `처음 가입은 부여되고 원장에 남는다`() {
        val email = uniqueEmail()
        val (ownerId, workspaceId) = newOwnedWorkspace(email)

        accountRepository.ensureAccount(workspaceId)
        service.grantSignupBonus(workspaceId, ownerId, normalizeEmail(email))

        val row = accountRepository.read(ownerId, workspaceId)!!
        assertThat(row.balance).isEqualTo(SIGNUP_GRANT)
        assertThat(row.signupGrantSkipped).isFalse()
        assertThat(countLedgerRows(hasher.hash(normalizeEmail(email)))).isEqualTo(1)
    }

    @Test
    @DisplayName("AC2 — 탈퇴 뒤 같은 이메일로 재가입하면 부여가 없다. 잔액 0, SIGNUP 거래 0건")
    fun `탈퇴 뒤 같은 이메일 재가입은 부여가 없다`() {
        val email = uniqueEmail()
        val (firstOwnerId, firstWorkspaceId) = newOwnedWorkspace(email)
        accountRepository.ensureAccount(firstWorkspaceId)
        service.grantSignupBonus(firstWorkspaceId, firstOwnerId, normalizeEmail(email))

        // 탈퇴를 흉내낸다 — users 행 삭제(CASCADE 로 workspaces·workspace_credit_accounts 도
        // 지워진다), signup_grant_records 는 FK 가 없어 남는다(V20 머리주석).
        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", firstOwnerId).update()

        val (secondOwnerId, secondWorkspaceId) = newOwnedWorkspace(email)
        accountRepository.ensureAccount(secondWorkspaceId)
        service.grantSignupBonus(secondWorkspaceId, secondOwnerId, normalizeEmail(email))

        val row = accountRepository.read(secondOwnerId, secondWorkspaceId)!!
        assertThat(row.balance).isEqualTo(0)
        assertThat(row.signupGrantSkipped).isTrue()
        assertThat(countSignupTransactions(secondWorkspaceId)).isEqualTo(0)
    }

    @Test
    @DisplayName("AC5 — 대소문자만 다른 이메일로 재가입해도 부여가 없다(정규화 일치)")
    fun `대소문자만 다른 이메일도 부여가 없다`() {
        val local = "case-${UUID.randomUUID()}"
        val lower = "$local@example.test"
        val upper = "${local.uppercase()}@EXAMPLE.TEST"

        val (firstOwnerId, firstWorkspaceId) = newOwnedWorkspace(normalizeEmail(lower))
        accountRepository.ensureAccount(firstWorkspaceId)
        service.grantSignupBonus(firstWorkspaceId, firstOwnerId, normalizeEmail(lower))

        // 탈퇴를 흉내낸다(AC2 와 같은 이유) — 대문자로 "재가입"하는 것은 users.email 이
        // 이미 정규화 저장이라 같은 정규화 결과를 두 번 넣을 수 없기 때문이다.
        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", firstOwnerId).update()

        val (secondOwnerId, secondWorkspaceId) = newOwnedWorkspace(normalizeEmail(upper))
        accountRepository.ensureAccount(secondWorkspaceId)
        // 실제 가입 경로(AuthService.signup)는 저장 전에 normalizeEmail 을 거치므로, 여기서도
        // 그 정규화를 통과한 값을 넘긴다 — 대문자 원문이 아니라 정규화된 결과가 같은지를 잰다.
        service.grantSignupBonus(secondWorkspaceId, secondOwnerId, normalizeEmail(upper))

        val row = accountRepository.read(secondOwnerId, secondWorkspaceId)!!
        assertThat(row.balance).isEqualTo(0)
        assertThat(row.signupGrantSkipped).isTrue()
    }

    @Test
    @DisplayName("AC6 — 다른 이메일로 가입하면 정상 부여된다")
    fun `다른 이메일은 정상 부여된다`() {
        val (ownerId, workspaceId) = newOwnedWorkspace(uniqueEmail())
        accountRepository.ensureAccount(workspaceId)

        service.grantSignupBonus(workspaceId, ownerId, normalizeEmail(uniqueEmail()))

        val row = accountRepository.read(ownerId, workspaceId)!!
        assertThat(row.balance).isEqualTo(SIGNUP_GRANT)
        assertThat(row.signupGrantSkipped).isFalse()
    }

    @Test
    @DisplayName("AC8 — 탈퇴해도 signup_grant_records 행은 남는다")
    fun `탈퇴해도 원장 행은 남는다`() {
        val email = uniqueEmail()
        val (ownerId, workspaceId) = newOwnedWorkspace(email)
        accountRepository.ensureAccount(workspaceId)
        service.grantSignupBonus(workspaceId, ownerId, normalizeEmail(email))
        val emailHash = hasher.hash(normalizeEmail(email))
        assertThat(countLedgerRows(emailHash)).isEqualTo(1)

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", ownerId).update()

        assertThat(countLedgerRows(emailHash)).isEqualTo(1)
    }

    private fun countLedgerRows(emailHash: String): Int =
        jdbc
            .sql("SELECT count(*) FROM signup_grant_records WHERE email_hash = :emailHash")
            .param("emailHash", emailHash)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun countSignupTransactions(workspaceId: UUID): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM credit_transactions WHERE workspace_id = :workspaceId AND reason = 'signup'",
            ).param("workspaceId", workspaceId)
            .query { rs, _ -> rs.getInt(1) }
            .single()

    private fun uniqueEmail(): String = "signup-once-${UUID.randomUUID()}@example.test"

    private fun newOwnedWorkspace(email: String): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        jdbc
            .sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
            .param("id", ownerId)
            .param("email", email)
            .param("hash", "\$argon2id\$v=19\$m=1,t=1,p=1\$c2FsdA\$aGFzaA")
            .update()
        jdbc
            .sql("INSERT INTO workspaces (id, user_id, name) VALUES (:id, :userId, :name)")
            .param("id", workspaceId)
            .param("userId", ownerId)
            .param("name", "signup-once-ws-${workspaceId.toString().take(8)}")
            .update()
        return ownerId to workspaceId
    }

    private fun dataSource(): DataSource =
        DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private companion object {
        const val SIGNUP_GRANT = 50
    }
}
