package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.TossDeclined
import kr.easydoc.application.subscription.TossGateway
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.application.subscription.TossUncertain
import kr.easydoc.core.security.Secret
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** No request/response logging; no redirects; production composition fixes the HTTPS host. */
@Suppress("TooManyFunctions") // Five provider operations plus shared HTTP decoding.
class TossHttpGateway(
    private val key: Secret,
    private val base: URI = URI("https://api.tosspayments.com"),
) : TossGateway {
    private val json = ObjectMapper()
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(
                Duration.ofSeconds(10),
            ).followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun issue(
        authKey: Secret,
        customerKey: Secret,
        session: UUID,
    ): Secret {
        val result =
            call(
                "POST",
                "/v1/billing/authorizations/issue",
                session,
                mapOf(
                    "authKey" to authKey.reveal(),
                    "customerKey" to customerKey.reveal(),
                ),
            )
                ?: throw TossUncertain()
        if (result.path("customerKey").asString("") != customerKey.reveal()) throw TossUncertain()
        return Secret(result.path("billingKey").asString("").also { if (it.isBlank()) throw TossUncertain() })
    }

    override fun charge(
        billingKey: Secret,
        customerKey: Secret,
        order: UUID,
        amount: Int,
        plan: String,
        fail: Boolean,
    ): TossPayment =
        payment(
            call(
                "POST",
                "/v1/billing/${encode(billingKey)}",
                order,
                mapOf(
                    "customerKey" to customerKey.reveal(),
                    "amount" to amount,
                    "orderId" to order.toString(),
                    "orderName" to "EASY-DOC $plan monthly",
                ),
                fail,
            )
                ?: throw TossUncertain(),
        )

    override fun find(order: UUID): TossPayment? =
        call("GET", "/v1/payments/orders/$order", missingAllowed = true)?.let(::payment)

    override fun refund(
        paymentKey: Secret,
        amount: Int,
        operation: UUID,
    ): TossPayment =
        payment(
            call(
                "POST",
                "/v1/payments/${encode(paymentKey)}/cancel",
                operation,
                mapOf("cancelReason" to "Customer support refund", "cancelAmount" to amount),
            )
                ?: throw TossUncertain(),
        )

    override fun revoke(billingKey: Secret) {
        call("DELETE", "/v1/billing/${encode(billingKey)}", missingAllowed = true)
    }

    @Suppress("LongParameterList")
    private fun call(
        method: String,
        path: String,
        id: UUID? = null,
        body: Map<String, Any>? = null,
        fail: Boolean = false,
        missingAllowed: Boolean = false,
    ): JsonNode? {
        val encoded = Base64.getEncoder().encodeToString("${key.reveal()}:".toByteArray(StandardCharsets.UTF_8))
        val request =
            HttpRequest
                .newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", "Basic $encoded")
                .header("Content-Type", "application/json")
        id?.let { request.header("Idempotency-Key", it.toString()) }
        if (fail) {
            check(key.reveal().startsWith("test_sk_"))
            request.header("TossPayments-Test-Code", "REJECT_CARD_PAYMENT")
        }
        request.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) }
                ?: HttpRequest.BodyPublishers.noBody(),
        )
        return decode(send(request.build()), method, missingAllowed)
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TossUncertain()
        } catch (_: IOException) {
            throw TossUncertain()
        }

    @Suppress("TooGenericExceptionCaught")
    private fun decode(
        response: HttpResponse<String>,
        method: String,
        missingAllowed: Boolean,
    ): JsonNode? {
        val status = response.statusCode()
        val node =
            try {
                if (response.body().isBlank()) null else json.readTree(response.body())
            } catch (
                _: RuntimeException,
            ) {
                throw TossUncertain()
            }
        if (status == NOT_FOUND && missingAllowed && node?.path("code")?.asString() in
            setOf("NOT_FOUND_PAYMENT", "NOT_FOUND_BILLING")
        ) {
            return null
        }
        checkStatus(status, method, node?.path("code")?.asString())
        return node
    }

    private fun checkStatus(
        status: Int,
        method: String,
        code: String?,
    ) {
        if (status in SUCCESS_CODES) return
        val temporary = status in RETRYABLE_CODES || status >= SERVER_ERROR
        if (method == "GET" || temporary || code in CONFIGURATION_ERRORS) {
            throw TossUncertain()
        }
        throw TossDeclined()
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 70L
        const val NOT_FOUND = 404
        const val SERVER_ERROR = 500
        val SUCCESS_CODES = 200..299
        val RETRYABLE_CODES = setOf(409, 429, 401)
        val CONFIGURATION_ERRORS = setOf("UNAUTHORIZED_KEY", "INCORRECT_BASIC_AUTH_FORMAT", "NOT_SUPPORTED_METHOD")
    }

    private fun payment(node: JsonNode): TossPayment {
        if (node.path("currency").asString("") != "KRW" || node.path("method").asString("") != "카드" ||
            node.path("type").asString("") != "BILLING"
        ) {
            throw TossUncertain()
        }
        val order =
            try {
                UUID.fromString(node.path("orderId").asString(""))
            } catch (
                _: IllegalArgumentException,
            ) {
                throw TossUncertain()
            }
        return TossPayment(
            Secret(node.path("paymentKey").asString("")),
            order,
            node.path("status").asString(""),
            node.path("totalAmount").intValue(),
            node.path("balanceAmount").intValue(),
            Secret(node.path("receipt").path("url").asString("")),
        )
    }

    private fun encode(value: Secret): String = URLEncoder.encode(value.reveal(), StandardCharsets.UTF_8)
}
