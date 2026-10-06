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
    private lateinit var transaction: org.springframework.transaction.support.TransactionTemplate
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
        val source = DriverManagerDataSource(db.jdbcUrl, db.username, db.password)
        jdbc = JdbcClient.create(source)
        transaction =
            org.springframework.transaction.support.TransactionTemplate(
                org.springframework.jdbc.datasource
                    .DataSourceTransactionManager(source),
            )
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

    @Test
    fun `administrator must confirm non delivery before uncertain retry and UUID replay cannot queue twice`() {
        enqueue("review", "toss_test")
        scheduler("toss_test", fail = true).run()
        val id =
            jdbc
                .sql(
                    "SELECT id FROM billing_notifications WHERE event_key='review'",
                ).query(Long::class.java)
                .single()
        val query =
            kr.easydoc.infrastructure.admin.JdbcAdminOperationsQuery(
                jdbc,
                PaymentProperties(provider = "toss_test"),
            )
        val revision =
            jdbc
                .sql(
                    "SELECT revision FROM billing_notifications WHERE id=:id",
                ).param("id", id)
                .query(Long::class.java)
                .single()
        val retry =
            kr.easydoc.application.admin
                .AdminNotificationCommand(UUID.randomUUID(), revision, "미전달 확인", null)
        org.assertj.core.api.Assertions
            .assertThatThrownBy { notificationAction(query, id, retry, true) }
            .isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
        val resolve = retry.copy(operationId = UUID.randomUUID(), resolution = "not_delivered")
        val resolved = notificationAction(query, id, resolve, false)
        val accepted = retry.copy(expectedRevision = resolved["revision"] as Long)
        notificationAction(query, id, accepted, true)
        scheduler("toss_test").run()
        val replay = notificationAction(query, id, accepted, true)
        assertThat(replay["state"]).isEqualTo("sent")
        assertThat(replay["recipient_email"]).isEqualTo("$owner@example.test")
        assertThat(replay["failure_code"]).isNull()
        assertThat(
            jdbc
                .sql("SELECT count(*) FROM billing_notification_attempts WHERE notification_id=:id")
                .param("id", id)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(2)
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                notificationAction(query, id, accepted.copy(reason = "다른 내용"), true)
            }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
    }

    @Test
    fun `operations count tasks and notification environment guard blocks retry`() {
        enqueue("failed-live", "toss_live")
        enqueue("failed-unknown", null)
        jdbc.sql("UPDATE billing_notifications SET state='failed'").update()
        val query =
            kr.easydoc.infrastructure.admin.JdbcAdminOperationsQuery(
                jdbc,
                PaymentProperties(provider = "toss_test"),
            )
        val page = query.operations(mapOf("kind" to "notification"), 1, 1)
        assertThat(page["total"]).isEqualTo(2L)
        assertThat(page["counts"]).isEqualTo(mapOf("notification" to 2L))
        assertThat(page["items"] as List<*>).hasSize(1)
        assertThat(query.operations(mapOf("kind" to "notification", "environment" to "unknown"), 1, 20)["total"])
            .isEqualTo(1L)
        assertThat(query.operations(mapOf("kind" to "notification", "state" to "sent"), 1, 20)["total"])
            .isEqualTo(0L)
        val second = query.operations(mapOf("kind" to "notification"), 2, 1)["items"] as List<*>
        assertThat(second).hasSize(1).doesNotContain((page["items"] as List<*>).single())
        val id =
            jdbc
                .sql(
                    "SELECT id FROM billing_notifications WHERE event_key='failed-live'",
                ).query(Long::class.java)
                .single()
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                query.notificationAction(
                    id,
                    owner,
                    kr.easydoc.application.admin
                        .AdminNotificationCommand(UUID.randomUUID(), 0, "재발송", null),
                    true,
                )
            }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
        assertThat(query.billingState(workspace)["audit"]).isEqualTo(emptyList<Any>())
        assertThat(query.errors(emptyMap(), 1, 20)["total"]).isEqualTo(0L)
    }

    @Test
    fun `concurrent retry with one UUID queues exactly once and stale revision rejects another operator`() {
        enqueue("concurrent", "toss_test")
        jdbc.sql("UPDATE billing_notifications SET state='failed'").update()
        val id =
            jdbc
                .sql(
                    "SELECT id FROM billing_notifications WHERE event_key='concurrent'",
                ).query(Long::class.java)
                .single()
        val query =
            kr.easydoc.infrastructure.admin.JdbcAdminOperationsQuery(
                jdbc,
                PaymentProperties(provider = "toss_test"),
            )
        val command =
            kr.easydoc.application.admin
                .AdminNotificationCommand(UUID.randomUUID(), 0, "확정 실패 재발송", null)
        val start = java.util.concurrent.CountDownLatch(1)
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val tasks =
                (1..2).map {
                    executor.submit<Map<String, Any?>> {
                        start.await()
                        notificationAction(query, id, command, true)
                    }
                }
            start.countDown()
            tasks.forEach {
                assertThat(
                    it.get(10, java.util.concurrent.TimeUnit.SECONDS)["state"],
                ).isEqualTo("pending")
            }
        } finally {
            executor.shutdownNow()
        }
        assertThat(
            jdbc
                .sql("SELECT count(*) FROM admin_notification_actions WHERE notification_id=:id")
                .param("id", id)
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1)
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                notificationAction(query, id, command.copy(operationId = UUID.randomUUID()), true)
            }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)
        scheduler("toss_test").run()
        assertThat(messages).hasSize(1)
        val attempts =
            jdbc
                .sql("SELECT state FROM billing_notification_attempts WHERE notification_id=:id")
                .param("id", id)
                .query(String::class.java)
                .list()
        assertThat(attempts).containsExactly("sent")
    }

    @Test
    fun `worker crash retains claimed attempt and restart quarantines without sending again`() {
        enqueue("crash", "toss_test")
        val crashing =
            BillingNotificationScheduler(
                jdbc,
                accounts,
                users,
                object : MailSender {
                    override fun send(message: OutboundMail): MailDelivery =
                        throw AssertionError("simulated termination after claim")
                },
                PaymentProperties(provider = "toss_test"),
            )
        org.assertj.core.api.Assertions
            .assertThatThrownBy { crashing.run() }
            .isInstanceOf(AssertionError::class.java)
        assertThat(state("crash")).isEqualTo("sending")
        assertThat(jdbc.sql("SELECT state FROM billing_notification_attempts").query(String::class.java).single())
            .isEqualTo("sending")
        jdbc
            .sql(
                "UPDATE billing_notifications SET attempted_at=now()-interval '11 minutes' WHERE event_key='crash'",
            ).update()
        scheduler("toss_test").run()
        assertThat(state("crash")).isEqualTo("manual_review")
        assertThat(messages).isEmpty()
        assertThat(jdbc.sql("SELECT state FROM billing_notification_attempts").query(String::class.java).single())
            .isEqualTo("manual_review")
        assertThat(
            jdbc.sql("SELECT failure_code FROM billing_notification_attempts").query(String::class.java).single(),
        ).isEqualTo("uncertain")
    }

    @Test
    fun `late SMTP completion racing expiry preserves quarantined attempt without another send`() {
        enqueue("late", "toss_test")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow =
            BillingNotificationScheduler(
                jdbc,
                accounts,
                users,
                object : MailSender {
                    override fun send(message: OutboundMail): MailDelivery {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        return MailDelivery.Sent()
                    }
                },
                PaymentProperties(provider = "toss_test"),
            )
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val sending = executor.submit { slow.run() }
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            jdbc
                .sql(
                    "UPDATE billing_notifications SET attempted_at=now()-interval '11 minutes' WHERE event_key='late'",
                ).update()
            scheduler("toss_test").run()
            release.countDown()
            sending.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(state("late")).isEqualTo("manual_review")
            assertThat(jdbc.sql("SELECT state FROM billing_notification_attempts").query(String::class.java).single())
                .isEqualTo("manual_review")
            assertThat(messages).isEmpty()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun notificationAction(
        query: kr.easydoc.infrastructure.admin.JdbcAdminOperationsQuery,
        id: Long,
        command: kr.easydoc.application.admin.AdminNotificationCommand,
        retry: Boolean,
    ): Map<String, Any?> = checkNotNull(transaction.execute { query.notificationAction(id, owner, command, retry) })

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
