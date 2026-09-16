package kr.easydoc.core.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

/** [HmacSha256] — Spring 도 DB 도 없이 JDK `javax.crypto` 만 잰다. RFC 4231 테스트 케이스 2로 고정한다. */
class HmacSha256Test {
    @Test
    @DisplayName("RFC 4231 테스트 케이스 2 — hex 는 알려진 벡터와 일치한다")
    fun `RFC 4231 케이스 2`() {
        val key = Secret("Jefe")
        val message = "what do ya want for nothing?"

        val hex = HmacSha256.hex(key, message)

        assertThat(hex).isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    }

    @Test
    @DisplayName("base64 는 같은 바이트의 Base64 인코딩과 일치한다")
    fun `base64 는 같은 서명 바이트를 인코딩한다`() {
        val key = Secret("Jefe")
        val message = "what do ya want for nothing?"

        val base64 = HmacSha256.base64(key, message)

        assertThat(base64).isEqualTo(Base64.getEncoder().encodeToString(HmacSha256.sign(key, message)))
    }

    @Test
    @DisplayName("키가 다르면 결과가 다르다")
    fun `키가 다르면 다른 해시`() {
        val message = "same message"

        val hashA = HmacSha256.hex(Secret("key-a"), message)
        val hashB = HmacSha256.hex(Secret("key-b"), message)

        assertThat(hashA).isNotEqualTo(hashB)
    }
}
