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
    @Suppress("LongMethod") // Replay, snapshot and cross-namespace checks share one durable request fixture.
    fun `management revision rejects stale requests but accepted UUID replays and audit excludes raw snapshots`() {
        val command = command()
        val current =
            JdbcAdminOperationsQuery(
                jdbc,
                kr.easydoc.infrastructure.subscription
                    .PaymentProperties(),
            ).billingState(command.workspaceId)["revision"] as Long
        assertThatThrownBy {
            transaction.executeWithoutResult {
                store.lock(command.workspaceId)
                store.audit(
                    command.workspaceId,
                    command.actorUserId,
                    command.operationId,
                    "stop_renewal",
                    "고객 요청",
                    null,
                    current + 1,
                )
            }
        }.isInstanceOf(ConflictException::class.java)
        transaction.executeWithoutResult {
            store.lock(command.workspaceId)
            store.audit(
                command.workspaceId,
                command.actorUserId,
                command.operationId,
                "stop_renewal",
                "고객 요청",
                null,
                current,
            )
        }
        jdbc
            .sql(
                """
                UPDATE admin_billing_actions SET before_state=before_state ||
                    '{"provider_response":"secret","billing_key":"hidden"}'::jsonb WHERE operation_id=:id
                """.trimIndent(),
            ).param("id", command.operationId)
            .update()
        val query =
            JdbcAdminOperationsQuery(
                jdbc,
                kr.easydoc.infrastructure.subscription
                    .PaymentProperties(),
            )
        jdbc
            .sql(
                """
                INSERT INTO workspace_subscriptions(workspace_id,plan_id,allowance,monthly_price,status,cycle_ends_at,provider)
                VALUES (:id,'start',50,99000,'active',now()+interval '1 month','toss_test')
                """.trimIndent(),
            ).param("id", command.workspaceId)
            .update()
        assertThat(query.billingState(command.workspaceId)["revision"] as Long).isGreaterThan(current)
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
                    current,
                ),
            ).isTrue()
        }
        val result = query.billingState(command.workspaceId)
        assertThat(result["audit"].toString())
            .contains(command.actorUserId.toString(), command.operationId.toString())
            .doesNotContain("provider_response", "billing_key", "secret", "hidden")
        assertThat(query.request(command.workspaceId, command.operationId)["status"]).isEqualTo("pending")
        assertThatThrownBy { query.request(UUID.randomUUID(), command.operationId) }
            .isInstanceOf(kr.easydoc.core.exceptions.NotFoundException::class.java)
        jdbc
            .sql(
                """
                INSERT INTO admin_credit_adjustments(operation_id,workspace_id,actor_user_id,credits,reason,note,
                    expected_balance,expected_reserved,expected_revision)
                VALUES (:id,:workspace,:actor,1,'manual','같은 번호',10,0,0)
                """.trimIndent(),
            ).param("id", command.operationId)
            .param("workspace", command.workspaceId)
            .param("actor", command.actorUserId)
            .update()
        assertThatThrownBy {
            query.request(command.workspaceId, command.operationId)
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

    @Test
    @Suppress("LongMethod") // Coordinate two transactions then verify operational versus lease-only version changes.
    fun `row local billing revision never waits for a locked workspace and ignores lease churn`() {
        val command = command()
        val workspace = command.workspaceId
        jdbc
            .sql(
                """
                INSERT INTO workspace_subscriptions(workspace_id,plan_id,allowance,monthly_price,status,cycle_ends_at,provider)
                VALUES (:id,'start',50,99000,'active',now()+interval '1 month','toss_test')
                """.trimIndent(),
            ).param("id", workspace)
            .update()
        val locked = java.util.concurrent.CountDownLatch(1)
        val workerFinished = java.util.concurrent.CountDownLatch(1)
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val admin =
                executor.submit {
                    transaction.executeWithoutResult {
                        store.lock(workspace)
                        locked.countDown()
                        assertThat(workerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
                    }
                }
            val worker =
                executor.submit {
                    check(locked.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    jdbc
                        .sql(
                            "UPDATE workspace_subscriptions SET monthly_price=monthly_price+1 WHERE workspace_id=:id",
                        ).param("id", workspace)
                        .update()
                    workerFinished.countDown()
                }
            admin.get(10, java.util.concurrent.TimeUnit.SECONDS)
            worker.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        jdbc
            .sql(
                """
                INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,status,
                    payload_encrypted,encryption_scheme,key_version,cycle_id)
                VALUES (:order,:workspace,'start',100,now(),now()+interval '1 month','charge','pending',
                    decode('01','hex'),'aes256gcm-v1',1,:order)
                """.trimIndent(),
            ).param("order", command.paymentId)
            .param("workspace", workspace)
            .update()
        val query =
            JdbcAdminOperationsQuery(
                jdbc,
                kr.easydoc.infrastructure.subscription
                    .PaymentProperties(),
            )
        val revision = query.billingState(workspace)["revision"] as Long
        jdbc
            .sql(
                "UPDATE toss_billing_orders SET next_attempt_at=now(),lease_until=now()+interval '1 minute' " +
                    "WHERE id=:id",
            ).param("id", command.paymentId)
            .update()
        assertThat(query.billingState(workspace)["revision"]).isEqualTo(revision)
        jdbc
            .sql(
                "UPDATE toss_billing_orders SET status='manual_review' WHERE id=:id",
            ).param("id", command.paymentId)
            .update()
        assertThat(query.billingState(workspace)["revision"] as Long).isGreaterThan(revision)
    }

    @Test
    fun `card deletion start is set on entering pending and reset after completion and reentry`() {
        val command = command()
        jdbc
            .sql(
                """
                INSERT INTO toss_billing_sessions(workspace_id,id,customer,plan_id,state,expires_at,
                    payload_encrypted,encryption_scheme,key_version)
                VALUES (:workspace,:id,:id,'start','active',now()+interval '1 day',decode('01','hex'),'aes256gcm-v1',1)
                """.trimIndent(),
            ).param("workspace", command.workspaceId)
            .param("id", UUID.randomUUID())
            .update()

        fun pendingSince(): Instant? =
            jdbc
                .sql("SELECT deletion_pending_since FROM toss_billing_sessions WHERE workspace_id=:id")
                .param("id", command.workspaceId)
                .query { rs, _ -> rs.getTimestamp(1)?.toInstant() }
                .list()
                .single()
        assertThat(pendingSince()).isNull()
        jdbc
            .sql(
                "UPDATE toss_billing_sessions SET state='revoking' WHERE workspace_id=:id",
            ).param("id", command.workspaceId)
            .update()
        assertThat(pendingSince()).isNotNull()
        jdbc
            .sql(
                "UPDATE toss_billing_sessions SET state='revoked' WHERE workspace_id=:id",
            ).param("id", command.workspaceId)
            .update()
        assertThat(pendingSince()).isNull()
        val before = Instant.now().minusSeconds(1)
        jdbc
            .sql(
                "UPDATE toss_billing_sessions SET state='revoking' WHERE workspace_id=:id",
            ).param("id", command.workspaceId)
            .update()
        assertThat(pendingSince()).isAfter(before)
    }

    @Test
    @Suppress("LongMethod") // Queue classification, filters and pagination share the same refund fixtures.
    fun `refund queue promotes uncertain outcomes and excludes completed or failed operations`() {
        val requests =
            listOf("pending", "manual_review", "paid", "failed").associateWith { state ->
                val input = command()
                transaction.executeWithoutResult { store.reserve(input) }
                jdbc
                    .sql(
                        """
                        INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,status,
                            payload_encrypted,encryption_scheme,key_version,cycle_id,environment)
                        VALUES (:id,:workspace,'start',1000,now(),now()+interval '1 month','refund',:state,
                            decode('01','hex'),'aes256gcm-v1',1,:id,'toss_test')
                        """.trimIndent(),
                    ).param("id", input.operationId)
                    .param("workspace", input.workspaceId)
                    .param("state", state)
                    .update()
                if (state in setOf("paid", "failed")) {
                    jdbc
                        .sql("UPDATE admin_billing_operations SET status=:state WHERE operation_id=:id")
                        .param("state", if (state == "paid") "completed" else "failed")
                        .param("id", input.operationId)
                        .update()
                }
                input
            }
        val query =
            JdbcAdminOperationsQuery(
                jdbc,
                kr.easydoc.infrastructure.subscription
                    .PaymentProperties(),
            )
        val filters = mapOf("kind" to "refund", "environment" to "toss_test")
        val first = query.operations(filters, 1, 1)
        assertThat(first["total"]).isEqualTo(2L)
        assertThat(first["counts"]).isEqualTo(mapOf("refund" to 2L))
        val uncertain = (first["items"] as List<*>).single() as Map<*, *>
        assertThat(uncertain["id"]).isEqualTo(requests.getValue("manual_review").operationId.toString())
        assertThat(uncertain["state"]).isEqualTo("manual_review")
        assertThat(uncertain["severity"]).isEqualTo(2)
        assertThat(uncertain["next_action"]).isEqualTo("환불 결과 재조회·확인")
        val pending = (query.operations(filters, 2, 1)["items"] as List<*>).single() as Map<*, *>
        assertThat(pending["id"]).isEqualTo(requests.getValue("pending").operationId.toString())
        assertThat(pending["state"]).isEqualTo("pending")
        assertThat(pending["severity"]).isEqualTo(1)
        for (state in listOf("manual_review", "pending")) {
            val filtered = query.operations(filters + ("state" to state), 1, 20)
            assertThat(filtered["total"]).isEqualTo(1L)
            assertThat(filtered["counts"]).isEqualTo(mapOf("refund" to 1L))
            assertThat((filtered["items"] as List<*>).single()).isEqualTo(
                if (state ==
                    "pending"
                ) {
                    pending
                } else {
                    uncertain
                },
            )
        }
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
