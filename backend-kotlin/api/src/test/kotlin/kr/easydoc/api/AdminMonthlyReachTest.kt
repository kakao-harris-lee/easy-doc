package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["easydoc.auth.jwt-secret=$ADMIN_REACH_TEST_SECRET"],
)
class AdminMonthlyReachTest {
    @LocalServerPort
    private var port: Int = 0
    private val json = ObjectMapper()

    @Suppress("LongMethod")
    @Test
    fun `monthly admin endpoints enforce access and serialize their full declared schemas`() {
        val admin = account(true)
        val owner = account(false)
        val workspace = ((body(get(owner, "/workspaces"))["items"] as List<*>).single() as Map<*, *>)["id"].toString()
        database.execute(
            """
            INSERT INTO credit_transactions(id,workspace_id,owner_user_id,kind,reason,balance_delta,reserved_delta)
            SELECT gen_random_uuid(),id,user_id,'grant','manual',1,0 FROM workspaces WHERE id='$workspace'
            """.trimIndent(),
        )
        database.execute("UPDATE workspace_credit_accounts SET balance=balance+1 WHERE workspace_id='$workspace'")
        val month = YearMonth.now(ZoneId.of("Asia/Seoul"))
        val base = "/admin/workspaces/$workspace"
        val endpoints =
            listOf(
                "monthly-summary?month=$month",
                "monthly-history?year=${month.year}",
                "credit-transactions?month=$month",
                "payments?month=$month",
            )
        endpoints.forEach { endpoint ->
            assertThat(get(null, "$base/$endpoint").statusCode()).isEqualTo(401)
            assertThat(get(owner, "$base/$endpoint").statusCode()).isEqualTo(403)
            val response = get(admin, "$base/$endpoint")
            assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
        }
        val summary = body(get(admin, "$base/monthly-summary?month=$month"))
        assertThat(
            summary.keys.map {
                it.toString()
            },
        ).containsExactlyInAnyOrderElementsOf(ContractSpec.schemaRequired("AdminMonthlySummary"))
        val current = summary["current"] as Map<*, *>
        assertThat(
            current.keys.map {
                it.toString()
            },
        ).containsExactlyInAnyOrderElementsOf(ContractSpec.schemaRequired("AdminCurrentCredits"))
        val usage = summary["usage"] as Map<*, *>
        assertThat(
            usage.keys.map {
                it.toString()
            },
        ).containsExactlyInAnyOrderElementsOf(ContractSpec.schemaRequired("AdminMonthlyUsage"))
        assertThat(usage["known_cost_usd"]).isNull()
        database.execute(
            """
            INSERT INTO llm_calls(id,workspace_id,user_id,document_id,purpose,provider,model,input_tokens,
                output_tokens,char_count,document_char_count,estimated_cost_usd,outcome,called_at)
            SELECT gen_random_uuid(),id,user_id,gen_random_uuid(),'convert','anthropic','test',10,
                5,10,10,0.001,'completed',now() FROM workspaces WHERE id='$workspace'
            """.trimIndent(),
        )
        val costUsage = body(get(admin, "$base/monthly-summary?month=$month"))["usage"] as Map<*, *>
        assertThat(costUsage["known_cost_usd"]).isInstanceOf(String::class.java)
        val transactions = body(get(admin, "$base/credit-transactions?month=$month"))["items"] as List<*>
        assertThat(transactions).isNotEmpty()
        assertThat((transactions.first() as Map<*, *>).keys.map { it.toString() })
            .containsExactlyInAnyOrderElementsOf(ContractSpec.schemaRequired("AdminCreditLedgerItem"))
        val history = body(get(admin, "$base/monthly-history?year=${month.year}"))["items"] as List<*>
        assertThat(history).hasSize(month.monthValue)
        val listing = body(get(admin, "/admin/workspaces?month=$month"))
        assertThat(listing["timezone"]).isEqualTo("Asia/Seoul")
        assertThat(listing["current_month"]).isEqualTo(month.toString())
        val selected = ((listing["items"] as List<*>).first() as Map<*, *>)["selected_month"] as Map<*, *>
        assertThat(selected["month"]).isEqualTo(month.toString())
        assertThat(get(admin, "$base/monthly-summary?month=${month.plusMonths(1)}").statusCode()).isEqualTo(422)
        assertThat(get(admin, "$base/monthly-summary?month=bad").statusCode()).isEqualTo(422)
        assertThat(get(admin, "$base/credit-transactions?size=101").statusCode()).isEqualTo(422)
        assertThat(get(admin, "$base/credit-transactions?kind=unknown").statusCode()).isEqualTo(422)
        assertThat(get(admin, "/admin/workspaces/${UUID.randomUUID()}/monthly-summary").statusCode()).isEqualTo(404)
    }

    @Test
    fun `operations APIs reject non administrators and return safe paged metadata`() {
        val admin = account(true)
        val owner = account(false)
        val workspace = ((body(get(owner, "/workspaces"))["items"] as List<*>).single() as Map<*, *>)["id"].toString()
        val endpoints =
            mapOf(
                "/admin/operations" to "AdminOperationsResponse",
                "/admin/notifications" to "AdminNotificationsResponse",
                "/admin/errors/events" to "AdminErrorEventsResponse",
            )
        endpoints.forEach { (path, schema) ->
            assertThat(get(null, path).statusCode()).isEqualTo(401)
            assertThat(get(owner, path).statusCode()).isEqualTo(403)
            val response = get(admin, path)
            assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
            assertThat(body(response).keys.map { it.toString() }).containsAll(ContractSpec.schemaRequired(schema))
            assertThat(get(admin, "$path?size=101").statusCode()).isEqualTo(422)
        }
        val operation = UUID.randomUUID()
        val requestPath = "/admin/workspaces/$workspace/billing/requests/$operation"
        assertThat(get(owner, requestPath).statusCode()).isEqualTo(403)
        assertThat(get(admin, requestPath).statusCode()).isEqualTo(404)
        val payload =
            json.writeValueAsString(
                mapOf(
                    "operation_id" to operation,
                    "expected_revision" to 0,
                    "reason" to "확인",
                    "resolution" to "not_delivered",
                ),
            )
        listOf("resolve", "retry").forEach { action ->
            assertThat(send("/admin/notifications/1/$action", null, payload).statusCode()).isEqualTo(401)
            assertThat(send("/admin/notifications/1/$action", owner, payload).statusCode()).isEqualTo(403)
        }
        val billing = get(admin, "/admin/workspaces/$workspace/billing")
        assertThat(billing.statusCode()).describedAs(billing.body()).isEqualTo(200)
        assertThat(body(billing).keys.map { it.toString() }).contains("revision", "allowed_actions", "audit")
        assertThat(billing.body()).doesNotContain("payload_encrypted", "billing_key", "provider_response")
    }

    @Test
    fun `admin notification resolution deserializes and preserves UUID replay and numeric identity`() {
        val admin = account(true)
        val workspace = ((body(get(admin, "/workspaces"))["items"] as List<*>).single() as Map<*, *>)["id"].toString()
        val key = "reach-${UUID.randomUUID()}"
        database.execute(
            """
            INSERT INTO billing_notifications(workspace_id,event_key,event_type,state,environment)
            VALUES ('$workspace','$key','charge_failed','manual_review','toss_test')
            """.trimIndent(),
        )
        val id =
            database
                .queryFirstColumn(
                    "SELECT id FROM billing_notifications WHERE event_key='$key'",
                ).single()
                .toLong()
        val request =
            mapOf(
                "operation_id" to UUID.randomUUID(),
                "expected_revision" to 0,
                "reason" to "미전달 확인",
                "resolution" to "not_delivered",
            )
        val payload = json.writeValueAsString(request)
        val response = send("/admin/notifications/$id/resolve", admin, payload)
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200)
        val notification = body(response)
        assertThat(
            notification.keys.map { it.toString() },
        ).containsAll(ContractSpec.schemaRequired("AdminNotification"))
        assertThat(notification["id"]).isInstanceOf(Number::class.java)
        assertThat(notification["resolution"]).isEqualTo("not_delivered")
        assertThat((notification["revision"] as Number).toLong()).isEqualTo(1)
        val replay = send("/admin/notifications/$id/resolve", admin, payload)
        assertThat(replay.statusCode()).describedAs(replay.body()).isEqualTo(200)
        assertThat(body(replay)["revision"]).isEqualTo(notification["revision"])
        val listed = body(get(admin, "/admin/notifications?id=$id"))["items"] as List<*>
        assertThat(listed).hasSize(1)
        assertThat(((listed.single() as Map<*, *>)["id"] as Number).toLong()).isEqualTo(id)
        val changed = json.writeValueAsString(request + ("reason" to "다른 내용"))
        assertThat(send("/admin/notifications/$id/resolve", admin, changed).statusCode()).isEqualTo(409)
        val retry =
            json.writeValueAsString(
                mapOf(
                    "operation_id" to UUID.randomUUID(),
                    "expected_revision" to 1,
                    "reason" to "재발송",
                ),
            )
        // This API fixture uses the disabled stub worker; the known test environment must not be mixed.
        assertThat(send("/admin/notifications/$id/retry", admin, retry).statusCode()).isEqualTo(409)
    }

    @Test
    fun `a billing change during projection cannot attach its newer revision to earlier rows`() {
        val workspace = UUID.randomUUID()
        val service = org.mockito.Mockito.mock(kr.easydoc.application.admin.AdminBillingService::class.java)
        val query = org.mockito.Mockito.mock(kr.easydoc.application.admin.AdminOperationsQuery::class.java)
        var revision = 1L
        org.mockito.Mockito
            .`when`(query.billingState(workspace))
            .thenAnswer { mapOf("revision" to revision) }
        org.mockito.Mockito.`when`(service.orders(workspace)).thenAnswer {
            revision = 2L // A worker commits while the multi-query projection is being assembled.
            emptyList<kr.easydoc.application.admin.AdminBillingOrder>()
        }
        org.mockito.Mockito
            .`when`(service.operations(workspace))
            .thenReturn(emptyList())
        org.mockito.Mockito
            .`when`(service.actions(workspace))
            .thenReturn(emptyList())
        val response =
            kr.easydoc.api.admin
                .AdminBillingController(service, query)
                .read(workspace)
        assertThat(revision).isEqualTo(2L)
        assertThat(response["revision"]).isEqualTo(1L)
    }

    private fun account(admin: Boolean): String {
        val email = "monthly-${UUID.randomUUID()}@example.test"
        val payload = json.writeValueAsString(mapOf("email" to email, "password" to "correct horse battery"))
        send("/auth/signup", null, payload)
        database.execute("UPDATE users SET email_verified_at=now(),is_admin=$admin WHERE email='$email'")
        return body(send("/auth/login", null, payload))["access_token"].toString()
    }

    private fun get(
        token: String?,
        path: String,
    ): HttpResponse<String> = send(path, token, null)

    private fun body(response: HttpResponse<String>): Map<*, *> = json.readValue(response.body(), Map::class.java)

    private fun send(
        path: String,
        token: String?,
        payload: String?,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(
                    URI.create("http://localhost:$port$path"),
                ).header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (payload == null) builder.GET() else builder.POST(HttpRequest.BodyPublishers.ofString(payload))
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val database by lazy { PostgresTestSupport.createEmptyDatabase("admin_monthly_reach") }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
