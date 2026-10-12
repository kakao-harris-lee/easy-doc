package kr.easydoc.infrastructure.retention

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Keeps the legal-record envelopes separate from billing and callback envelopes. */
class RetainedTransactionPayloadCodec(
    private val cipher: ContentCipher,
    private val json: ObjectMapper = ObjectMapper(),
) {
    val writeKeyVersion: Int get() = cipher.writeKeyVersion

    fun encodeRecord(
        id: UUID,
        payload: Map<String, Any?>,
    ): EncryptedContent = seal(id, EncryptedField.RETAINED_TRANSACTION, payload)

    fun decodeRecord(
        id: UUID,
        encrypted: EncryptedContent,
    ): JsonNode = open(id, EncryptedField.RETAINED_TRANSACTION, encrypted)

    fun encodeDecision(
        id: UUID,
        payload: Map<String, Any?>,
    ): EncryptedContent = seal(id, EncryptedField.RETAINED_DECISION, payload)

    fun decodeDecision(
        id: UUID,
        encrypted: EncryptedContent,
    ): JsonNode = open(id, EncryptedField.RETAINED_DECISION, encrypted)

    fun rotate(
        id: UUID,
        encrypted: EncryptedContent,
        field: EncryptedField,
    ): EncryptedContent = cipher.encrypt(cipher.decrypt(encrypted, id, field), id, field)

    fun write(payload: Map<String, Any?>): String = json.writeValueAsString(payload)

    private fun seal(
        id: UUID,
        field: EncryptedField,
        payload: Map<String, Any?>,
    ): EncryptedContent = cipher.encrypt(PlainBody(write(payload)), id, field)

    private fun open(
        id: UUID,
        field: EncryptedField,
        encrypted: EncryptedContent,
    ): JsonNode = json.readTree(cipher.decrypt(encrypted, id, field).value)
}
