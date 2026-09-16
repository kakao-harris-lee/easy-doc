package kr.easydoc.api

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "easydoc.auth.jwt-secret=subscription-reach-test-secret-long-enough",
        "easydoc.credits.enforced=true",
        "easydoc.payment.mock-enabled=true",
    ],
)
@ActiveProfiles("test")
class SubscriptionReachTest {
    @LocalServerPort
    private var port = 0

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var jdbc: org.springframework.jdbc.core.simple.JdbcClient

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var transaction: kr.easydoc.application.auth.TransactionRunner

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var subscriptions: kr.easydoc.application.subscription.SubscriptionService
    private val json = ObjectMapper()
    private val client = HttpClient.newHttpClient()

    @Test
    fun `HTTP concurrent retry charges once and sets allowance rather than adding`() {
        val token = account()
        val workspace = workspace(token)
        database.execute("UPDATE workspace_credit_accounts SET balance=12, reserved=3 WHERE workspace_id='$workspace'")
        val path = "/workspaces/$workspace/subscription/checkout"
        val payload = """{"plan_id":"start","order_id":"${UUID.randomUUID()}"}"""
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { pool.submit<HttpResponse<String>> { send(path, token, "POST", payload) } }
            jobs.forEach { assertThat(it.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(
            database.queryInt("SELECT count(*) FROM subscription_payments WHERE workspace_id='$workspace'"),
        ).isEqualTo(1)
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(50)
        assertThat(
            database.queryInt("SELECT reserved FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(3)
        val response = send("/workspaces/$workspace/subscription", token)
        assertThat(json.readTree(response.body())["subscription"]["plan_id"].asString()).isEqualTo("start")
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store")
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        val cancel = send("/workspaces/$workspace/subscription", token, "DELETE")
        assertThat(json.readTree(cancel.body())["subscription"]["status"].asString()).isEqualTo("canceling")
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(50)
    }

    @Test
    fun `declined checkout leaves credit account and subscription untouched`() {
        val token = account()
        val workspace = workspace(token)
        val response =
            send(
                "/workspaces/$workspace/subscription/checkout",
                token,
                "POST",
                """{"plan_id":"start","order_id":"${UUID.randomUUID()}","simulate_failure":true}""",
            )
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(json.readTree(response.body())["payments"][0]["status"].asString()).isEqualTo("failed")
        assertThat(
            database.queryInt("SELECT count(*) FROM workspace_subscriptions WHERE workspace_id='$workspace'"),
        ).isZero()
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isZero()
    }

    @Test
    fun `Basic and Pro are not available through test checkout`() {
        val token = account()
        val workspace = workspace(token)
        listOf("basic", "pro").forEach { plan ->
            val response =
                send(
                    "/workspaces/$workspace/subscription/checkout",
                    token,
                    "POST",
                    """{"plan_id":"$plan","order_id":"${UUID.randomUUID()}"}""",
                )
            assertThat(response.statusCode()).isEqualTo(422)
        }
    }

    @Test
    fun `admin must buy Start before conversion and receives the same 50 credits`() {
        val token = account()
        val workspace = workspace(token)
        val userId =
            kr.easydoc.api.support.TestJwt
                .payload(token)["sub"]
                .toString()
        database.execute("UPDATE users SET is_admin=true WHERE id='$userId'")

        val beforePayment = send("/documents", token, "POST", """{"text":"관리자도 결제가 필요합니다"}""")
        assertThat(beforePayment.statusCode()).isEqualTo(402)
        assertThat(beforePayment.headers().firstValue("X-Credit-Balance")).hasValue("0")

        val checkout =
            send(
                "/workspaces/$workspace/subscription/checkout",
                token,
                "POST",
                """{"plan_id":"start","order_id":"${UUID.randomUUID()}"}""",
            )
        assertThat(checkout.statusCode()).isEqualTo(200)

        val credits = json.readTree(send("/workspaces/$workspace/credits", token).body())
        assertThat(credits["enforced"].asBoolean()).isTrue()
        assertThat(credits["allowance"].asInt()).isEqualTo(50)
        assertThat(credits["available"].asInt()).isEqualTo(50)

        val afterPayment = send("/documents", token, "POST", """{"text":"${"가".repeat(1_000)}"}""")
        assertThat(afterPayment.statusCode()).isEqualTo(202)
        assertThat(afterPayment.headers().firstValue("X-Credit-Balance")).hasValue("49")
    }

    @Test
    fun `authentication ownership and admin guards cover every subscription route`() {
        val owner = account()
        val other = account()
        val workspace = workspace(owner)
        val payload = """{"plan_id":"start","order_id":"${UUID.randomUUID()}"}"""
        val base = "/workspaces/$workspace/subscription"
        assertThat(send(base, null).statusCode()).isEqualTo(401)
        assertThat(send("$base/checkout", null, "POST", "{}").statusCode()).isEqualTo(401)
        assertThat(send(base, null, "DELETE").statusCode()).isEqualTo(401)
        assertThat(send(base, other).statusCode()).isEqualTo(404)
        assertThat(send("$base/checkout", other, "POST", payload).statusCode()).isEqualTo(404)
        assertThat(send(base, other, "DELETE").statusCode()).isEqualTo(404)
        assertThat(send("/admin$base", owner).statusCode()).isEqualTo(403)
        val ownerId =
            kr.easydoc.api.support.TestJwt
                .payload(owner)["sub"]
                .toString()
        database.execute("UPDATE users SET is_admin=true WHERE id='$ownerId'")
        assertThat(send("/admin$base", owner).statusCode()).isEqualTo(200)
    }

    @Test
    fun `legacy reset excludes subscriptions and subscription worker renews then expires canceled period`() {
        val token = account()
        val workspace = workspace(token)
        val base = "/workspaces/$workspace/subscription"
        val payload = """{"plan_id":"start","order_id":"${UUID.randomUUID()}"}"""
        assertThat(send("$base/checkout", token, "POST", payload).statusCode()).isEqualTo(200)
        database.execute(
            "UPDATE workspace_subscriptions SET cycle_ends_at=now()-interval '1 day' WHERE workspace_id='$workspace'",
        )
        database.execute(
            "UPDATE workspace_credit_accounts SET balance=7, cycle_ends_at=now()-interval '1 day' " +
                "WHERE workspace_id='$workspace'",
        )
        transaction.inTransaction {
            kr.easydoc.infrastructure.credit
                .JdbcCreditCycleReset(jdbc, java.time.ZoneId.of("Asia/Seoul"))
                .reset(java.time.Instant.now(), 100)
        }
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(7)
        subscriptions.renewDue()
        subscriptions.renewDue()
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isEqualTo(50)
        assertThat(
            database.queryInt("SELECT count(*) FROM subscription_payments WHERE workspace_id='$workspace'"),
        ).isEqualTo(2)
        send(base, token, "DELETE")
        database.execute(
            "UPDATE workspace_subscriptions SET cycle_ends_at=now()-interval '1 day' WHERE workspace_id='$workspace'",
        )
        subscriptions.renewDue()
        assertThat(
            database.queryInt("SELECT balance FROM workspace_credit_accounts WHERE workspace_id='$workspace'"),
        ).isZero()
        assertThat(
            database.queryInt("SELECT count(*) FROM subscription_payments WHERE workspace_id='$workspace'"),
        ).isEqualTo(2)
    }

    private fun account(): String {
        val email = "subscription-${UUID.randomUUID()}@example.test"
        val payload = """{"email":"$email","password":"correct horse battery"}"""
        assertThat(send("/auth/signup", null, "POST", payload).statusCode()).isEqualTo(201)
        database.execute("UPDATE users SET email_verified_at=now(), phone_verified_at=now() WHERE email='$email'")
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
        val database: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("subscription_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
