package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.AdminMonthResolver
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAdminMonthlyReportRepositoryTest {
    private lateinit var jdbc: JdbcClient
    private lateinit var repository: JdbcAdminMonthlyReportRepository
    private val now = Instant.parse("2026-10-06T00:00:00Z")
    private val resolver = AdminMonthResolver(ZoneId.of("Asia/Seoul"), Clock.fixed(now, ZoneOffset.UTC))

    @BeforeAll
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("admin_monthly_report")
        val dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        jdbc = JdbcClient.create(dataSource)
        repository = JdbcAdminMonthlyReportRepository(jdbc, DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `cross month reservation is not consumption and illustration is included`() {
        val (owner, id) = workspace()
        transaction(owner, id, "grant", "signup", "100", "0", "2026-08-31T15:00:00Z")
        transaction(owner, id, "reserve", "illustration_suggestion", "0", "10", "2026-09-30T14:59:59Z")
        transaction(owner, id, "consume", "illustration_suggestion", "-10", "-10", "2026-09-30T15:00:00Z")
        setAccount(id, "90", "0")
        val september = repository.summary(id, resolver.resolve("2026-09"), "Asia/Seoul", now)
        assertThat(september.credits.consumed).isEqualByComparingTo("0")
        assertThat(september.credits.reserved).isEqualByComparingTo("10")
        assertThat(september.credits.available).isEqualByComparingTo("90")
        val october = repository.summary(id, resolver.resolve("2026-10"), "Asia/Seoul", now)
        assertThat(october.credits.opening).isEqualByComparingTo("100")
        assertThat(october.credits.consumed).isEqualByComparingTo("10")
        assertThat(october.credits.closing).isEqualByComparingTo("90")
        assertThat(october.usage.creditsByReason["illustration_suggestion"]).isEqualByComparingTo("10")
        assertThat(october.completeness.ledgerMatchesAccount).isTrue()
        assertThat(october.completeness.monthlyEquationMatches).isTrue()
        assertThat(
            JdbcAdminWorkspaceQueryRepository(
                jdbc,
            ).selectedMonthTotals(listOf(id), resolver.resolve("2026-10"))[id]!!.credits,
        ).isEqualByComparingTo(october.credits.consumed)
    }

    @Test
    fun `legacy cycle keeps net change and mismatched ledger is explicit`() {
        val (owner, id) = workspace()
        transaction(owner, id, "grant", "signup", "100", "0", "2026-09-01T00:00:00Z")
        transaction(owner, id, "cycle_reset", "plan_monthly", "20", "0", "2026-10-01T00:00:00Z")
        val report = repository.summary(id, resolver.resolve("2026-10"), "Asia/Seoul", now)
        assertThat(report.credits.cycleNet).isEqualByComparingTo("20")
        assertThat(report.credits.granted).isEqualByComparingTo("0")
        assertThat(report.credits.legacyCycleCount).isEqualTo(1)
        assertThat(report.completeness.monthlyEquationMatches).isTrue()
        assertThat(report.completeness.ledgerMatchesAccount).isFalse()
    }

    @Test
    fun `credit pages cover more than fifty rows with stable ordering and kind filter`() {
        val (owner, id) = workspace()
        repeat(53) { transaction(owner, id, "grant", "manual", "1", "0", "2026-10-01T00:00:00Z") }
        val first = repository.transactions(id, resolver.resolve("2026-10"), "grant", 1, 50)
        val second = repository.transactions(id, resolver.resolve("2026-10"), "grant", 2, 50)
        assertThat(first.total).isEqualTo(53)
        assertThat(second.items).hasSize(3)
        assertThat((first.items + second.items).map { it.id }.distinct()).hasSize(53)
        assertThat(repository.transactions(id, resolver.resolve("2026-10"), "consume", 1, 20).items).isEmpty()
    }

    @Test
    fun `refund month test money and undated legacy events are separate and failed attempts remain visible`() {
        val (_, id) = workspace()
        val real = payment(id, "toss", "paid", 10000)
        val test = payment(id, "stub", "paid", 5000)
        payment(id, "toss", "failed", 10000)
        jdbc
            .sql("UPDATE subscription_payment_events SET occurred_at=:at WHERE payment_id=:id")
            .param("id", real)
            .param("at", Instant.parse("2026-09-01T00:00:00Z").atOffset(ZoneOffset.UTC))
            .update()
        jdbc
            .sql(
                "UPDATE subscription_payment_events SET occurred_at=NULL WHERE payment_id=:id",
            ).param("id", test)
            .update()
        jdbc
            .sql(
                "UPDATE subscription_payments SET refunded_amount=2000,status='partially_refunded' WHERE id=:id",
            ).param("id", real)
            .update()
        jdbc
            .sql("UPDATE subscription_payment_events SET occurred_at=:at WHERE payment_id=:id AND kind='refund'")
            .param("id", real)
            .param("at", Instant.parse("2026-10-01T00:00:00Z").atOffset(ZoneOffset.UTC))
            .update()
        val report = repository.summary(id, resolver.resolve("2026-10"), "Asia/Seoul", now)
        assertThat(report.payments.paidKrw).isZero()
        assertThat(report.payments.refundedKrw).isEqualTo(2000)
        assertThat(report.payments.netKrw).isEqualTo(-2000)
        assertThat(report.payments.testUnknownDatePaidKrw).isEqualTo(5000)
        jdbc
            .sql(
                """
            INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,status,payload_encrypted,encryption_scheme,key_version,cycle_id)
            VALUES(:order,:workspace,'starter',10000,:at,:at,'charge','pending',decode('00','hex'),'aes256gcm-v1',1,:order)
        """,
            ).param("order", UUID.randomUUID())
            .param("workspace", id)
            .param("at", Instant.parse("2026-10-01T00:00:00Z").atOffset(ZoneOffset.UTC))
            .update()
        jdbc
            .sql(
                """
            INSERT INTO toss_billing_orders(id,workspace_id,plan_id,amount,created_at,cycle_ends_at,kind,original_id,status,
              payload_encrypted,encryption_scheme,key_version,cycle_id)
            SELECT :refund,workspace_id,plan_id,2000,created_at,cycle_ends_at,'refund',id,'manual_review',
              payload_encrypted,encryption_scheme,key_version,:refund FROM toss_billing_orders WHERE workspace_id=:workspace AND kind='charge'
        """,
            ).param("refund", UUID.randomUUID())
            .param("workspace", id)
            .update()
        val page = repository.payments(id, resolver.resolve("2026-10"), 1, 20)
        assertThat(page.items.map { it.kind }).contains("refund", "attempt", "payment")
        assertThat(page.items.single { it.paymentId == test }.occurredAt).isNull()
        assertThat(page.items.single { it.status == "pending" }.isTest).isTrue()
        assertThat(page.items.single { it.kind == "refund_attempt" }.status).isEqualTo("manual_review")
        assertThat(page.items.single { it.kind == "attempt" && it.status == "failed" }.status).isEqualTo("failed")
    }

    private fun workspace(): Pair<UUID, UUID> {
        val owner = UUID.randomUUID()
        val id = UUID.randomUUID()
        jdbc
            .sql(
                "INSERT INTO users(id,email,password_hash) VALUES(:id,:email,'hash')",
            ).param("id", owner)
            .param("email", "$owner@example.test")
            .update()
        jdbc
            .sql(
                "INSERT INTO workspaces(id,user_id,name) VALUES(:id,:owner,'monthly')",
            ).param("id", id)
            .param("owner", owner)
            .update()
        jdbc
            .sql(
                "INSERT INTO workspace_credit_accounts(workspace_id,balance,reserved,allowance) VALUES(:id,0,0,0)",
            ).param("id", id)
            .update()
        return owner to id
    }

    private fun setAccount(
        id: UUID,
        balance: String,
        reserved: String,
    ) {
        jdbc
            .sql("UPDATE workspace_credit_accounts SET balance=:balance,reserved=:reserved WHERE workspace_id=:id")
            .param("id", id)
            .param("balance", BigDecimal(balance))
            .param("reserved", BigDecimal(reserved))
            .update()
    }

    @Suppress("LongParameterList")
    private fun transaction(
        owner: UUID,
        id: UUID,
        kind: String,
        reason: String,
        balance: String,
        reserved: String,
        at: String,
    ) {
        jdbc
            .sql(
                """
            INSERT INTO credit_transactions(id,workspace_id,owner_user_id,kind,reason,balance_delta,reserved_delta,created_at)
            VALUES(:tx,:id,:owner,:kind,:reason,:balance,:reserved,:at)
        """,
            ).param(
                "tx",
                UUID.randomUUID(),
            ).param("id", id)
            .param("owner", owner)
            .param("kind", kind)
            .param("reason", reason)
            .param(
                "balance",
                BigDecimal(balance),
            ).param("reserved", BigDecimal(reserved))
            .param("at", Instant.parse(at).atOffset(ZoneOffset.UTC))
            .update()
    }

    private fun payment(
        id: UUID,
        provider: String,
        status: String,
        amount: Int,
    ): UUID {
        val paymentId = UUID.randomUUID()
        jdbc
            .sql(
                """
            INSERT INTO subscription_payments(workspace_id,id,plan_id,amount,status,created_at,simulated_failure,provider)
            VALUES(:id,:payment,'starter',:amount,:status,:at,false,:provider)
        """,
            ).param("id", id)
            .param("payment", paymentId)
            .param("amount", amount)
            .param("status", status)
            .param(
                "at",
                Instant.parse("2026-10-01T00:00:00Z").atOffset(ZoneOffset.UTC),
            ).param("provider", provider)
            .update()
        return paymentId
    }
}
