package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.IssuedBillingCard
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
    private val connectTimeout: Duration = TossBillingProperties.DEFAULT_CONNECT_TIMEOUT,
    private val requestTimeout: Duration = TossBillingProperties.DEFAULT_REQUEST_TIMEOUT,
) : TossGateway {
    private val json = ObjectMapper()
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun issue(
        authKey: Secret,
        customerKey: Secret,
        session: UUID,
    ): Secret = issueCard(authKey, customerKey, session).key

    override fun issueCard(
        authKey: Secret,
        customerKey: Secret,
        session: UUID,
    ): IssuedBillingCard {
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
        val key = Secret(result.path("billingKey").asString("").also { if (it.isBlank()) throw TossUncertain() })
        val lastFour =
            result
                .path("card")
                .path("number")
                .asString("")
                .takeLast(CARD_SUFFIX_LENGTH)
                .takeIf { it.matches(Regex("[0-9]{4}")) }
        return IssuedBillingCard(key, lastFour)
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
                .timeout(requestTimeout)
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
        if (method == "GET" || temporary || code in LOOKUP_REQUIRED_ERRORS) {
            throw TossUncertain()
        }
        throw TossDeclined(requiresCard = code in CARD_REGISTRATION_ERRORS)
    }

    private companion object {
        const val NOT_FOUND = 404
        const val SERVER_ERROR = 500
        const val CARD_SUFFIX_LENGTH = 4
        val SUCCESS_CODES = 200..299
        val RETRYABLE_CODES = setOf(409, 429, 401)
        val LOOKUP_REQUIRED_ERRORS =
            setOf(
                "UNAUTHORIZED_KEY",
                "INCORRECT_BASIC_AUTH_FORMAT",
                "NOT_SUPPORTED_METHOD",
                "DUPLICATED_ORDER_ID",
                "DUPLICATED_REQUEST",
                "INVALID_API_KEY",
            )
        val CARD_REGISTRATION_ERRORS =
            setOf(
                "INVALID_STOPPED_CARD",
                "INVALID_CARD_LOST_OR_STOLEN",
                "INVALID_CARD_EXPIRATION",
                "INVALID_CARD_NUMBER",
                "INVALID_BILL_KEY_REQUEST",
                "NOT_FOUND_BILLING",
            )
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
            timestamp(node.path("approvedAt")),
            node.path("cancels").mapNotNull { timestamp(it.path("canceledAt")) }.maxOrNull(),
        )
    }

    private fun timestamp(node: JsonNode): java.time.Instant? {
        val value = node.asString("")
        if (value.isBlank()) return null
        return try {
            java.time.OffsetDateTime
                .parse(value)
                .toInstant()
        } catch (_: java.time.format.DateTimeParseException) {
            throw TossUncertain()
        }
    }

    private fun encode(value: Secret): String = URLEncoder.encode(value.reveal(), StandardCharsets.UTF_8)
}
