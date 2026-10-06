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
