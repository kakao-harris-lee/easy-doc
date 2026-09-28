package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.BillingOrder
import kr.easydoc.application.subscription.BillingSession
import kr.easydoc.application.subscription.TossPayment
import kr.easydoc.core.exceptions.DecryptionFailedException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

class BillingPayloadCodecTest {
    private val codec =
        BillingPayloadCodec(
            AesGcmContentCipher(
                mapOf(1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))),
                1,
            ),
        )

    @Test
    fun `session and order payloads round trip in their existing format`() {
        val workspace = UUID.randomUUID()
        val session =
            BillingSession(
                workspace,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "start",
                Instant.parse("2026-01-01T00:00:00Z"),
                Secret("auth-value"),
                Secret("billing-value"),
                "active",
                true,
            )
        val orderId = UUID.randomUUID()
        val order =
            BillingOrder(
                orderId,
                workspace,
                "start",
                99_000,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                payment =
                    TossPayment(
                        Secret("payment-key"),
                        orderId,
                        "DONE",
                        99_000,
                        99_000,
                        Secret("receipt-url"),
                    ),
            )

        val decodedSession = codec.decodeSession(codec.encodeSession(session), session.id)
        val decodedPayment = codec.decodeOrder(codec.encodeOrder(order), orderId)

        assertThat(decodedSession.authKey?.reveal()).isEqualTo("auth-value")
        assertThat(decodedSession.billingKey?.reveal()).isEqualTo("billing-value")
        assertThat(decodedSession.simulateFailure).isTrue()
        assertThat(decodedPayment?.key?.reveal()).isEqualTo("payment-key")
        assertThat(decodedPayment?.orderId).isEqualTo(orderId)
        assertThat(decodedPayment?.receipt?.reveal()).isEqualTo("receipt-url")
    }

    @Test
    fun `session and order ciphertext cannot be exchanged`() {
        val session =
            BillingSession(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "start",
                Instant.EPOCH,
            )
        val encrypted = codec.encodeSession(session)

        assertThatThrownBy { codec.decodeOrder(encrypted, session.id) }
            .isInstanceOf(DecryptionFailedException::class.java)
    }
}
