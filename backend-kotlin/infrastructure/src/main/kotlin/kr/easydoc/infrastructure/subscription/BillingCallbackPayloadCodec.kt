package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.subscription.TossBillingCallbackPayload
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.security.Secret
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Encrypts callback credentials and the retained opaque resume value with callback-specific AAD. */
class BillingCallbackPayloadCodec(
    private val cipher: ContentCipher,
    private val json: ObjectMapper = ObjectMapper(),
) {
    fun encode(
        id: UUID,
        payload: TossBillingCallbackPayload,
    ): EncryptedContent =
        cipher.encrypt(
            PlainBody(
                json.writeValueAsString(
                    mapOf(
                        "auth" to payload.authKey?.reveal(),
                        "resume" to payload.resumeToken,
                        "failure" to payload.failureCode,
                    ),
                ),
            ),
            id,
            EncryptedField.BILLING_CALLBACK,
        )

    fun decode(
        id: UUID,
        encrypted: EncryptedContent,
    ): TossBillingCallbackPayload {
        val node = json.readTree(cipher.decrypt(encrypted, id, EncryptedField.BILLING_CALLBACK).value)
        return TossBillingCallbackPayload(
            authKey = secret(node, "auth"),
            resumeToken = node.path("resume").asString(null),
            failureCode = node.path("failure").asString(null),
        )
    }

    fun rotate(
        id: UUID,
        encrypted: EncryptedContent,
    ): EncryptedContent =
        cipher.encrypt(
            cipher.decrypt(encrypted, id, EncryptedField.BILLING_CALLBACK),
            id,
            EncryptedField.BILLING_CALLBACK,
        )

    private fun secret(
        node: JsonNode,
        name: String,
    ): Secret? =
        node
            .get(name)
            ?.takeUnless { it.isNull }
            ?.asString()
            ?.let(::Secret)
}
