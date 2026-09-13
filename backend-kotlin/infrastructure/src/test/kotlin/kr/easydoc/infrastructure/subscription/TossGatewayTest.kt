package kr.easydoc.infrastructure.subscription

import com.sun.net.httpserver.HttpServer
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID

class TossGatewayTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val gateway = TossHttpGateway(Secret("test_sk_synthetic"), URI("http://127.0.0.1:${server.address.port}"))

    @AfterEach
    fun close() {
        server.stop(0)
    }

    @Test
    fun `billing uses secret basic auth and a stable idempotency key and checks payment response`() {
        val order = UUID.randomUUID()
        server.createContext("/v1/billing/billing-test") { exchange ->
            assertThat(exchange.requestHeaders.getFirst("Idempotency-Key")).isEqualTo(order.toString())
            assertThat(exchange.requestHeaders.getFirst("Authorization")).startsWith("Basic ")
            val body = String(exchange.requestBody.readAllBytes())
            assertThat(body).contains("\"amount\":1000", order.toString(), "synthetic-customer")
            val response =
                """{"paymentKey":"payment-test",
                "orderId":"$order",
                "status":"DONE",
                "type":"BILLING",
                "method":"카드",
                "currency":"KRW",
                "totalAmount":1000,
                "balanceAmount":1000,
                "receipt":{"url":"https://receipt.tosspayments.com/test"}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val payment =
            gateway.charge(
                Secret("billing-test"),
                Secret("synthetic-customer"),
                order,
                1000,
                "Start",
                false,
            )
        assertThat(payment.amount).isEqualTo(1000)
        assertThat(payment.status).isEqualTo("DONE")
        assertThat(payment.toString()).doesNotContain("payment-test", "receipt.tosspayments")
    }

    @Test
    fun `only a not found response can become missing payment`() {
        server.createContext("/v1/payments/orders/") { exchange ->
            val body = """{"code":"UNAUTHORIZED_KEY","message":"never echo provider content"}""".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertThatThrownBy { gateway.find(UUID.randomUUID()) }.hasMessageNotContaining("never echo")
    }

    @Test
    fun `card rejection is definitive even when provider uses HTTP 403`() {
        server.createContext("/v1/billing/") { exchange ->
            val body = """{"code":"REJECT_CARD_PAYMENT","message":"rejected"}""".toByteArray()
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertThatThrownBy {
            gateway.charge(Secret("billing-test"), Secret("customer-test"), UUID.randomUUID(), 1000, "start", true)
        }.isInstanceOf(kr.easydoc.application.subscription.TossDeclined::class.java)
    }
}
