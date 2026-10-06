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
                "approvedAt":"2026-01-31T23:30:00+09:00",
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
        assertThat(payment.approvedAt).isEqualTo(java.time.Instant.parse("2026-01-31T14:30:00Z"))
        assertThat(payment.toString()).doesNotContain("payment-test", "receipt.tosspayments")
    }

    @Test
    fun `live never sends a failure simulation request`() {
        val live = TossHttpGateway(Secret("live_sk_synthetic"), URI("http://127.0.0.1:${server.address.port}"))
        assertThatThrownBy {
            live.charge(Secret("billing"), Secret("customer"), UUID.randomUUID(), 99000, "start", true)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `card issuance exposes only final four digits`() {
        server.createContext("/v1/billing/authorizations/issue") { exchange ->
            val response =
                """{"customerKey":"customer","billingKey":"synthetic-billing",
                "card":{"number":"1234567890125678"}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val card = gateway.issueCard(Secret("auth"), Secret("customer"), UUID.randomUUID())
        assertThat(card.lastFour).isEqualTo("5678")
        assertThat(card.toString()).doesNotContain("synthetic-billing", "1234567890125678")
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

    @Test
    fun `duplicate order response is uncertain and must not start another charge`() {
        server.createContext("/v1/billing/") { exchange ->
            val body = """{"code":"DUPLICATED_ORDER_ID"}""".toByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        assertThatThrownBy {
            gateway.charge(Secret("billing"), Secret("customer"), UUID.randomUUID(), 99000, "start", false)
        }.isInstanceOf(kr.easydoc.application.subscription.TossUncertain::class.java)
    }

    @Test
    fun `expired card rejection requests card registration`() {
        server.createContext("/v1/billing/") { exchange ->
            val body = """{"code":"INVALID_CARD_EXPIRATION"}""".toByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        try {
            gateway.charge(Secret("billing"), Secret("customer"), UUID.randomUUID(), 99000, "start", false)
            error("Expected card rejection")
        } catch (failure: kr.easydoc.application.subscription.TossDeclined) {
            assertThat(failure.requiresCard).isTrue()
        }
    }
}
