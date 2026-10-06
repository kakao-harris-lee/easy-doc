package kr.easydoc.infrastructure.admin

import kr.easydoc.application.actionguide.ActionGuideCreditReservation
import kr.easydoc.application.admin.AdminRefundCommand
import kr.easydoc.application.credit.ReservationResult
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionCreditReservation
import kr.easydoc.core.credit.CreditReason
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.actionguide.JdbcActionGuideCreditPort
import kr.easydoc.infrastructure.credit.JdbcCreditAccountRepository
import kr.easydoc.infrastructure.credit.JdbcCreditCycleReset
import kr.easydoc.infrastructure.illustration.suggestion.JdbcIllustrationSuggestionCreditPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAdminBillingStoreTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var store: JdbcAdminBillingStore
    private lateinit var transaction: TransactionTemplate

    @BeforeAll
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("admin_billing")
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(dataSource)
        store = JdbcAdminBillingStore(jdbc)
        transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `reservation reduces availability without changing balance and settlement preserves ledger`() {
        val command = command()
        transaction.executeWithoutResult {
            store.lock(command.workspaceId)
            store.reserve(command)
        }
        assertAccount(command.workspaceId, "10", "4")
        transaction.executeWithoutResult {
            val owner = command.actorUserId
            val workspace = command.workspaceId
            val amount = Credits(BigDecimal("7"))
            assertThat(JdbcCreditAccountRepository(jdbc).reserve(owner, workspace, UUID.randomUUID(), amount, false))
                .isInstanceOf(ReservationResult.Insufficient::class.java)
            assertThat(
                JdbcActionGuideCreditPort(
                    jdbc,
                    false,
                ).reserve(owner, workspace, UUID.randomUUID(), UUID.randomUUID(), amount),
            ).isInstanceOf(ActionGuideCreditReservation.Insufficient::class.java)
            assertThat(
                JdbcIllustrationSuggestionCreditPort(
                    jdbc,
                    false,
                ).reserve(owner, workspace, UUID.randomUUID(), UUID.randomUUID(), amount),
            ).isInstanceOf(IllustrationSuggestionCreditReservation.Insufficient::class.java)
        }
        val operation = checkNotNull(store.operation(command.operationId))
        assertThat(operation.status).isEqualTo("pending")
        transaction.executeWithoutResult {
            store.lock(command.workspaceId)
            store.settle(operation, true)
        }
        assertAccount(command.workspaceId, "6", "0")
        assertThat(store.operation(command.operationId)?.status).isEqualTo("completed")
        val delta =
            jdbc
                .sql(
                    "SELECT sum(balance_delta) FROM credit_transactions WHERE payment_id=:payment",
                ).param("payment", command.paymentId)
                .query(BigDecimal::class.java)
                .single()
        assertThat(delta).isEqualByComparingTo("-4")
    }

    @Test
    fun `definitive failure releases reserve and leaves balance intact`() {
        val command = command()
        transaction.executeWithoutResult {
            store.lock(command.workspaceId)
            store.reserve(command)
        }
        val operation = checkNotNull(store.operation(command.operationId))
        transaction.executeWithoutResult {
            store.lock(command.workspaceId)
            store.settle(operation, false)
        }
        assertAccount(command.workspaceId, "10", "0")
        assertThat(store.operation(command.operationId)?.status).isEqualTo("failed")
    }

    @Test
    fun `stale confirmation and unavailable credit reject without a durable operation`() {
        val command = command()
        assertThatThrownBy {
            transaction.executeWithoutResult {
                store.lock(command.workspaceId)
                store.reserve(command.copy(expectedRevision = 1))
            }
        }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy {
            transaction.executeWithoutResult {
                store.lock(command.workspaceId)
                store.reserve(command.copy(recoveryCredits = BigDecimal("10.1")))
            }
        }.isInstanceOf(ConflictException::class.java)
        assertThat(store.operation(command.operationId)).isNull()
        assertAccount(command.workspaceId, "10", "0")
    }

    @Test
    fun `admin action UUID prevents different payload and completed replay`() {
        val command = command()
        transaction.executeWithoutResult {
            store.lock(command.workspaceId)
            assertThat(
                store.audit(
                    command.workspaceId,
                    command.actorUserId,
                    command.operationId,
                    "stop_renewal",
                    "고객 요청",
                    null,
                ),
            ).isTrue()
        }
        assertThat(store.pendingActions().map { it.id }).contains(command.operationId)
        transaction.executeWithoutResult { store.finishAction(command.operationId) }
        transaction.executeWithoutResult {
            assertThat(
                store.audit(
                    command.workspaceId,
                    command.actorUserId,
                    command.operationId,
                    "stop_renewal",
                    "고객 요청",
                    null,
                ),
            ).isFalse()
        }
        assertThatThrownBy {
            transaction.executeWithoutResult {
                store.audit(command.workspaceId, command.actorUserId, command.operationId, "sync", "고객 요청", null)
            }
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `refund hold prevents paid allowance replacement and cycle expiration`() {
        val command = command()
        jdbc
            .sql("UPDATE workspace_credit_accounts SET cycle_ends_at=now()-interval '1 day' WHERE workspace_id=:id")
            .param("id", command.workspaceId)
            .update()
        transaction.executeWithoutResult {
            store.lock(command.workspaceId)
            store.reserve(command.copy(expectedRevision = 1))
        }
        val accounts = JdbcCreditAccountRepository(jdbc)
        assertThatThrownBy {
            transaction.executeWithoutResult {
                accounts.setPaidAllowance(
                    command.workspaceId,
                    command.actorUserId,
                    BigDecimal("50"),
                    Instant.now().plusSeconds(3600),
                    CreditReason.PLAN_MONTHLY,
                    "갱신",
                    UUID.randomUUID(),
                )
            }
        }.isInstanceOf(ConflictException::class.java)
        transaction.executeWithoutResult {
            JdbcCreditCycleReset(jdbc, ZoneId.of("Asia/Seoul")).reset(Instant.now(), 100)
        }
        assertAccount(command.workspaceId, "10", "4")
    }

    private fun command(): AdminRefundCommand {
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO users(id,email,password_hash) VALUES(:id,:email,'hash')",
            ).param("id", owner)
            .param("email", "$owner@example.test")
            .update()
        jdbc
            .sql(
                "INSERT INTO workspaces(id,user_id,name) VALUES(:id,:owner,'billing')",
            ).param("id", workspace)
            .param("owner", owner)
            .update()
        jdbc
            .sql(
                "INSERT INTO workspace_credit_accounts(workspace_id,balance,reserved,allowance) VALUES(:id,10,0,0)",
            ).param("id", workspace)
            .update()
        return AdminRefundCommand(
            UUID.randomUUID(),
            workspace,
            UUID.randomUUID(),
            owner,
            1000,
            BigDecimal("4.0"),
            false,
            "고객 환불",
            0,
        )
    }

    private fun assertAccount(
        workspace: UUID,
        balance: String,
        reserved: String,
    ) {
        val actual =
            jdbc
                .sql(
                    "SELECT balance,reserved FROM workspace_credit_accounts WHERE workspace_id=:id",
                ).param("id", workspace)
                .query { rs, _ -> rs.getBigDecimal("balance") to rs.getBigDecimal("reserved") }
                .single()
        assertThat(actual.first).isEqualByComparingTo(balance)
        assertThat(actual.second).isEqualByComparingTo(reserved)
    }
}
