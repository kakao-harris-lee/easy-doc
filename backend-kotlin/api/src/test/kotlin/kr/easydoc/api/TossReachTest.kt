package kr.easydoc.api

import kr.easydoc.application.subscription.TossBillingService
import kr.easydoc.application.subscription.TossDeclined
import kr.easydoc.application.subscription.TossGateway
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.application.subscription.TossUncertain
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=toss-reach-test-secret-long-enough-for-jwt",
        "easydoc.payment.provider=toss_test",
        "easydoc.payment.toss-client-key=test_ck_fixture",
        "easydoc.payment.toss-secret-key=test_sk_fixture",
    ],
)
@ActiveProfiles("test")
class TossReachTest {
    @LocalServerPort private var port = 0

    @org.springframework.beans.factory.annotation.Autowired private lateinit var toss: TossBillingService

    @org.springframework.beans.factory.annotation.Autowired private lateinit var gateway: TestGateway
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()

    @Test
    fun `lost approval response recovers once through lookup then refund and cancellation stay idempotent`() {
        val token = account()
        val workspace = workspace(token)
        val base = "/workspaces/$workspace/subscription"
        val started = send("$base/billing", token, "POST", """{"plan_id":"starter"}""")
        assertThat(started.statusCode()).isEqualTo(200)
        val session = json.readTree(started.body())
        val id = UUID.fromString(session["session_id"].asString())
        val payload = """{"session_id":"$id",
                "customer_key":"${session["customer_key"].asString()}",
                "auth_key":"synthetic-auth"}"""
        gateway.loseResponse.add(id)
        assertThat(send("$base/billing/complete", token, "POST", payload).statusCode()).isEqualTo(200)
        assertThat(
            database.queryInt("SELECT count(*) FROM subscription_payments WHERE workspace_id='$workspace'"),
        ).isZero()
        toss.process(id)
        toss.process(id)
        assertThat(gateway.charges[id]).isEqualTo(1)
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(50)
        database.execute("UPDATE workspace_credit_accounts SET balance=17 WHERE workspace_id='$workspace'")
        // A forged webhook never changes credits directly, and server reconciliation cannot replenish them.
        val hook = """{"eventType":"PAYMENT_STATUS_CHANGED",
                "data":{"orderId":"$id",
                "status":"DONE",
                "totalAmount":999999}}"""
        assertThat(send("/payments/toss/webhook", null, "POST", hook).statusCode()).isEqualTo(204)
        toss.runDue()
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(17)
        assertThat(send("/workspaces/$workspace/payments/$id/receipt", token).statusCode()).isEqualTo(200)
        replaceCardPreservingUsage(token, workspace)
        val stranger = account()
        assertThat(send("$base/billing/complete", stranger, "POST", payload).statusCode()).isEqualTo(404)
        val refund = "/admin/workspaces/$workspace/payments/$id/refund"
        val operation = UUID.randomUUID()
        val refundBody = """{"operation_id":"$operation","amount":400}"""
        assertThat(send(refund, token, "POST", refundBody).statusCode()).isEqualTo(403)
        database.execute(
            "UPDATE users SET is_admin=true WHERE id=(SELECT user_id FROM workspaces WHERE id='$workspace')",
        )
        assertThat(send(refund, token, "POST", refundBody).statusCode()).isEqualTo(200)
        assertThat(send(refund, token, "POST", refundBody).statusCode()).isEqualTo(200)
        assertThat(gateway.refunds[operation]).isEqualTo(1)
        assertThat(database.queryInt("SELECT refunded_amount FROM subscription_payments WHERE id='$id'")).isEqualTo(400)
        assertThat(send(base, token, "DELETE").statusCode()).isEqualTo(200)
        assertThat(gateway.revocations).isGreaterThan(0)
        database.execute(
            "UPDATE workspace_subscriptions SET cycle_ends_at=now()-interval '1 day' WHERE workspace_id='$workspace'",
        )
        toss.runDue()
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isZero()
        assertThat(gateway.charges[id]).isEqualTo(1)
    }

    @Test
    fun `declined card never grants quota and callback cannot be rebound`() {
        val token = account()
        val workspace = workspace(token)
        val base = "/workspaces/$workspace/subscription"
        val started = json.readTree(send("$base/billing", token, "POST", """{"plan_id":"pro"}""").body())
        val id = started["session_id"].asString()
        val customer = started["customer_key"].asString()
        val bad = """{"session_id":"$id","customer_key":"${UUID.randomUUID()}","auth_key":"synthetic-auth"}"""
        assertThat(send("$base/billing/complete", token, "POST", bad).statusCode()).isEqualTo(409)
        val payload = """{"session_id":"$id",
                "customer_key":"$customer",
                "auth_key":"synthetic-auth",
                "simulate_failure":true}"""
        assertThat(send("$base/billing/complete", token, "POST", payload).statusCode()).isEqualTo(200)
        assertThat(
            database.queryInt("SELECT count(*) FROM workspace_subscriptions WHERE workspace_id='$workspace'"),
        ).isZero()
        assertThat(
            database.queryInt(
                "SELECT count(*) FROM subscription_payments WHERE workspace_id='$workspace' AND status='failed'",
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `worker recovers interrupted key issuance without needing the browser callback again`() {
        val token = account()
        val workspace = workspace(token)
        val base = "/workspaces/$workspace/subscription"
        val session = json.readTree(send("$base/billing", token, "POST", """{"plan_id":"starter"}""").body())
        val id = UUID.fromString(session["session_id"].asString())
        gateway.loseIssueResponse.add(id)
        val payload = """{"session_id":"$id","customer_key":"${session["customer_key"].asString()}",
            "auth_key":"synthetic-lost-issue"}"""
        assertThat(send("$base/billing/complete", token, "POST", payload).statusCode()).isEqualTo(409)
        assertThat(
            database.queryInt(
                "SELECT count(*) FROM toss_billing_sessions " +
                    "WHERE workspace_id='$workspace' AND state='issuing'",
            ),
        ).isEqualTo(1)
        toss.runDue()
        assertThat(database.queryInt("SELECT count(*) FROM subscription_payments WHERE workspace_id='$workspace'"))
            .isEqualTo(1)
        assertThat(gateway.issueIds.count { it == id }).isEqualTo(2)
        assertThat(gateway.charges[id]).isEqualTo(1)
    }

    @TestConfiguration
    class GatewayConfiguration {
        @Bean @Primary
        fun testGateway() = TestGateway()
    }

    class TestGateway : TossGateway {
        val payments = java.util.concurrent.ConcurrentHashMap<UUID, TossPayment>()
        val charges = java.util.concurrent.ConcurrentHashMap<UUID, Int>()
        val refunds = java.util.concurrent.ConcurrentHashMap<UUID, Int>()
        val loseResponse =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<UUID>()
        val loseIssueResponse =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<UUID>()
        val issueIds = java.util.concurrent.CopyOnWriteArrayList<UUID>()
        var revocations = 0

        override fun issue(
            authKey: Secret,
            customerKey: Secret,
            session: UUID,
        ): Secret {
            issueIds.add(session)
            if (loseIssueResponse.remove(session)) throw TossUncertain()
            return Secret("billing-$session")
        }

        override fun charge(
            billingKey: Secret,
            customerKey: Secret,
            order: UUID,
            amount: Int,
            plan: String,
            fail: Boolean,
        ): TossPayment {
            charges.merge(order, 1, Int::plus)
            if (fail) throw TossDeclined()
            val paid =
                TossPayment(
                    Secret("payment-$order"),
                    order,
                    "DONE",
                    amount,
                    amount,
                    Secret("https://example.test/receipt"),
                )
            payments[order] = paid
            if (loseResponse.remove(order)) throw TossUncertain()
            return paid
        }

        override fun find(order: UUID) = payments[order]

        override fun refund(
            paymentKey: Secret,
            amount: Int,
            operation: UUID,
        ): TossPayment {
            refunds.merge(operation, 1, Int::plus)
            val entry = payments.entries.single { it.value.key == paymentKey }
            return entry.value
                .copy(
                    status = "PARTIAL_CANCELED",
                    remainingAmount = entry.value.remainingAmount - amount,
                ).also {
                    payments[entry.key] =
                        it
                }
        }

        override fun revoke(billingKey: Secret) {
            revocations++
        }
    }

    private fun replaceCardPreservingUsage(
        token: String,
        workspace: String,
    ) {
        val base = "/workspaces/$workspace/subscription"
        // Replacing a card first revokes the previous key and resumes the same paid period without a new charge.
        assertThat(send(base, token, "DELETE").statusCode()).isEqualTo(200)
        val replacement = json.readTree(send("$base/billing", token, "POST", """{"plan_id":"starter"}""").body())
        val replacementBody = """{"session_id":"${replacement["session_id"].asString()}",
            "customer_key":"${replacement["customer_key"].asString()}","auth_key":"synthetic-replacement"}"""
        assertThat(send("$base/billing/complete", token, "POST", replacementBody).statusCode()).isEqualTo(200)
        assertThat(
            database.queryInt("SELECT count(*) FROM subscription_payments WHERE workspace_id='$workspace'"),
        ).isEqualTo(1)
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(17)
    }

    private fun account(): String {
        val email = "subscription-${UUID.randomUUID()}@example.test"
        val payload = """{"email":"$email","password":"correct horse battery"}"""
        assertThat(send("/auth/signup", null, "POST", payload).statusCode()).isEqualTo(201)
        database.execute("UPDATE users SET email_verified_at=now() WHERE email='$email'")
        return json.readTree(send("/auth/login", null, "POST", payload).body())["access_token"].asString()
    }

    private fun workspace(token: String): String =
        json.readTree(send("/workspaces", token).body())["items"][0]["id"].asString()

    private fun send(
        path: String,
        token: String?,
        method: String = "GET",
        body: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(
                    URI.create("http://localhost:$port$path"),
                ).header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("toss_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
