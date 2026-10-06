package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.user.User
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID

class BillingNotificationSchedulerTest {
    private lateinit var jdbc: JdbcClient
    private val workspace = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val accounts = Mockito.mock(CreditAccountRepository::class.java)
    private val users = Mockito.mock(UserRepository::class.java)
    private val messages = mutableListOf<OutboundMail>()

    @BeforeEach
    fun prepare() {
        val db = PostgresTestSupport.createEmptyDatabase("billing_notification")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        jdbc = JdbcClient.create(DriverManagerDataSource(db.jdbcUrl, db.username, db.password))
        jdbc
            .sql("INSERT INTO users(id,email,password_hash) VALUES(:id,:email,'test')")
            .param("id", owner)
            .param("email", "$owner@example.test")
            .update()
        jdbc
            .sql("INSERT INTO workspaces(id,user_id,name) VALUES(:id,:owner,'notification')")
            .param("id", workspace)
            .param("owner", owner)
            .update()
        Mockito.`when`(accounts.ownerOf(workspace)).thenReturn(owner)
        Mockito
            .`when`(
                users.findById(owner),
            ).thenReturn(User(owner, "$owner@example.test", Instant.now(), hasPassword = true))
    }

    @Test
    fun `live worker sends only live notifications and leaves other environments untouched`() {
        enqueue("live", "toss_live")
        enqueue("test", "toss_test")
        enqueue("unknown", null)
        scheduler("toss_live").run()
        assertThat(messages).hasSize(1)
        assertThat(messages.single().subject).doesNotContain("테스트")
        assertThat(state("live")).isEqualTo("sent")
        assertThat(state("test")).isEqualTo("pending")
        assertThat(state("unknown")).isEqualTo("pending")
        scheduler("stub").run()
        assertThat(messages).hasSize(1)
    }

    @Test
    fun `test worker explicitly labels subject and body without claiming live jobs`() {
        enqueue("live", "toss_live")
        enqueue("test", "toss_test")
        val scheduler = scheduler("toss_test")
        scheduler.run()
        scheduler.run()
        assertThat(messages).hasSize(1)
        assertThat(messages.single().subject).startsWith("[테스트 결제]")
        assertThat(messages.single().textBody).contains("테스트 환경", "실제 요금은 청구되지 않습니다")
        assertThat(state("live")).isEqualTo("pending")
        assertThat(state("test")).isEqualTo("sent")
    }

    @Test
    fun `uncertain send is not retried and stale sending recovery stays in its environment`() {
        enqueue("uncertain", "toss_live")
        enqueue("stale-test", "toss_test")
        jdbc
            .sql(
                "UPDATE billing_notifications SET state='sending',attempted_at=now()-interval '11 minutes' " +
                    "WHERE event_key='stale-test'",
            ).update()
        val scheduler = scheduler("toss_live", fail = true)
        scheduler.run()
        scheduler.run()
        assertThat(messages).hasSize(1)
        assertThat(state("uncertain")).isEqualTo("manual_review")
        assertThat(state("stale-test")).isEqualTo("sending")
        scheduler("toss_test").run()
        assertThat(state("stale-test")).isEqualTo("manual_review")
        assertThat(messages).hasSize(1)
    }

    @Test
    fun `upcoming reminder persists the environment and stays deduplicated`() {
        jdbc
            .sql(
                """
                INSERT INTO workspace_subscriptions(workspace_id,plan_id,allowance,monthly_price,
                    status,cycle_ends_at,provider)
                VALUES(:workspace,'start',50,99000,'active',now()+interval '6 days','toss_test')
                """.trimIndent(),
            ).param("workspace", workspace)
            .update()
        val scheduler = scheduler("toss_test", upcoming = true)
        scheduler.run()
        scheduler.run()
        assertThat(messages).hasSize(1)
        assertThat(
            jdbc
                .sql("SELECT environment FROM billing_notifications WHERE event_type='upcoming'")
                .query(String::class.java)
                .single(),
        ).isEqualTo("toss_test")
    }

    private fun scheduler(
        environment: String,
        fail: Boolean = false,
        upcoming: Boolean = false,
    ): BillingNotificationScheduler =
        BillingNotificationScheduler(
            jdbc,
            accounts,
            users,
            object : MailSender {
                override fun send(message: OutboundMail): MailDelivery {
                    messages.add(message)
                    check(!fail) { "Simulated ambiguous SMTP response" }
                    return MailDelivery.Sent()
                }
            },
            PaymentProperties(provider = environment, autoChargeEnabled = upcoming),
        )

    private fun enqueue(
        key: String,
        environment: String?,
    ) {
        jdbc
            .sql(
                "INSERT INTO billing_notifications(workspace_id,event_key,event_type,environment) " +
                    "VALUES(:workspace,:key,'charge_paid',:environment)",
            ).param("workspace", workspace)
            .param("key", key)
            .param("environment", environment)
            .update()
    }

    private fun state(key: String): String =
        jdbc
            .sql("SELECT state FROM billing_notifications WHERE event_key=:key")
            .param("key", key)
            .query { rs, _ -> rs.getString("state") }
            .single()
}
