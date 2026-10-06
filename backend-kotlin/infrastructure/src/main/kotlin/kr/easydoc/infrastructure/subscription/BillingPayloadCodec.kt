package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.security.Secret
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Encodes and decodes the two encrypted billing payloads without changing their wire format.
 *
 * Session and order payloads deliberately use their own [EncryptedField] AAD. Keeping those
 * choices in this codec makes it difficult for a storage refactor to accidentally accept a
 * ciphertext copied from the other billing table.
 */
class BillingPayloadCodec(
    private val cipher: ContentCipher,
    private val json: ObjectMapper = ObjectMapper(),
) {
    fun encodeSession(session: BillingSession): EncryptedContent =
        seal(
            session.id,
            EncryptedField.BILLING_SESSION,
            mapOf(
                "auth" to session.authKey?.reveal(),
                "billing" to session.billingKey?.reveal(),
                "fail" to session.simulateFailure,
                "previousBilling" to session.previousBillingKey?.reveal(),
                "previousCustomer" to session.previousCustomer?.toString(),
                "cardLastFour" to session.cardLastFour,
                "previousCardLastFour" to session.previousCardLastFour,
            ),
        )

    fun decodeSession(
        encrypted: EncryptedContent,
        id: UUID,
    ): BillingSessionPayload {
        val data = payload(encrypted, id, EncryptedField.BILLING_SESSION)
        return BillingSessionPayload(
            authKey = secret(data, "auth"),
            billingKey = secret(data, "billing"),
            simulateFailure = data.path("fail").asBoolean(false),
            previousBillingKey = secret(data, "previousBilling"),
            previousCustomer = secret(data, "previousCustomer")?.reveal()?.let(UUID::fromString),
            cardLastFour = secret(data, "cardLastFour")?.reveal(),
            previousCardLastFour = secret(data, "previousCardLastFour")?.reveal(),
        )
    }

    fun encodeOrder(order: BillingOrder): EncryptedContent {
        val payment = order.payment
        return seal(
            order.id,
            EncryptedField.BILLING_ORDER,
            mapOf(
                "key" to payment?.key?.reveal(),
                "order" to payment?.orderId?.toString(),
                "status" to payment?.status,
                "amount" to payment?.amount,
                "remaining" to payment?.remainingAmount,
                "receipt" to payment?.receipt?.reveal(),
                "approvedAt" to payment?.approvedAt?.toString(),
                "canceledAt" to payment?.canceledAt?.toString(),
            ),
        )
    }

    fun decodeOrder(
        encrypted: EncryptedContent,
        id: UUID,
    ): TossPayment? {
        val data = payload(encrypted, id, EncryptedField.BILLING_ORDER)
        return secret(data, "key")?.let {
            TossPayment(
                it,
                UUID.fromString(data.path("order").asString()),
                data.path("status").asString(),
                data.path("amount").intValue(),
                data.path("remaining").intValue(),
                secret(data, "receipt") ?: Secret.EMPTY,
                secret(data, "approvedAt")?.reveal()?.let(java.time.Instant::parse),
                secret(data, "canceledAt")?.reveal()?.let(java.time.Instant::parse),
            )
        }
    }

    fun rotate(
        encrypted: EncryptedContent,
        id: UUID,
        field: EncryptedField,
    ): EncryptedContent = cipher.encrypt(cipher.decrypt(encrypted, id, field), id, field)

    private fun seal(
        id: UUID,
        field: EncryptedField,
        data: Map<String, Any?>,
    ): EncryptedContent = cipher.encrypt(PlainBody(json.writeValueAsString(data)), id, field)

    private fun payload(
        encrypted: EncryptedContent,
        id: UUID,
        field: EncryptedField,
    ): JsonNode = json.readTree(cipher.decrypt(encrypted, id, field).value)

    private fun secret(
        node: JsonNode,
        key: String,
    ): Secret? =
        node
            .get(key)
            ?.takeUnless { it.isNull }
            ?.asString()
            ?.let(::Secret)
}

data class BillingSessionPayload(
    val authKey: Secret?,
    val billingKey: Secret?,
    val simulateFailure: Boolean,
    val previousBillingKey: Secret? = null,
    val previousCustomer: UUID? = null,
    val cardLastFour: String? = null,
    val previousCardLastFour: String? = null,
)
