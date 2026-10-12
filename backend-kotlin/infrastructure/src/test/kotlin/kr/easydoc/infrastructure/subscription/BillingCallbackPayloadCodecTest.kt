package kr.easydoc.infrastructure.subscription

import kr.easydoc.application.subscription.TossBillingCallbackPayload
import kr.easydoc.core.exceptions.DecryptionFailedException
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.crypto.AesGcmContentCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class BillingCallbackPayloadCodecTest {
    private val codec =
        BillingCallbackPayloadCodec(
            AesGcmContentCipher(
                mapOf(1 to Secret(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))),
                1,
            ),
        )

    @Test
    fun `callback credentials and retry metadata round trip inside the callback envelope`() {
        val id = UUID.randomUUID()
        val payload = TossBillingCallbackPayload(Secret("auth-value"), "resume-value", null)

        val decoded = codec.decode(id, codec.encode(id, payload))

        assertThat(decoded.authKey?.reveal()).isEqualTo("auth-value")
        assertThat(decoded.resumeToken).isEqualTo("resume-value")
        assertThat(decoded.failureCode).isNull()
    }

    @Test
    fun `callback envelope is bound to its record and field`() {
        val id = UUID.randomUUID()
        val encrypted = codec.encode(id, TossBillingCallbackPayload(resumeToken = "resume-value"))

        assertThatThrownBy { codec.decode(UUID.randomUUID(), encrypted) }
            .isInstanceOf(DecryptionFailedException::class.java)
    }

    @Test
    fun `expired credentials can be scrubbed while preserving retry metadata`() {
        val id = UUID.randomUUID()
        val encrypted = codec.encode(id, TossBillingCallbackPayload(Secret("auth-value"), "resume-value"))

        val scrubbed = codec.encode(id, codec.decode(id, encrypted).copy(authKey = null))
        val decoded = codec.decode(id, scrubbed)

        assertThat(decoded.authKey).isNull()
        assertThat(decoded.resumeToken).isEqualTo("resume-value")
    }
}
