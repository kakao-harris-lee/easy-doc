package kr.easydoc.application.mail

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 메일 수신자 주소 — 가입 이메일 규칙([kr.easydoc.application.auth.requireValidEmail])을 그대로 쓴다. */
class EmailAddressTest {
    @Test
    @DisplayName("정규화 후 값을 담는다 — 앞뒤 공백 제거, 소문자화")
    fun `공백과 대소문자를 정규화한다`() {
        val address = EmailAddress.of("  User@Example.COM  ")

        assertThat(address.value).isEqualTo("user@example.com")
    }

    @Test
    @DisplayName("형식이 아니면 InvalidInputException")
    fun `형식이 아니면 거절한다`() {
        assertThatThrownBy { EmailAddress.of("not-an-email") }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("toString 은 값을 찍지 않는다")
    fun `toString 이 값을 가린다`() {
        val address = EmailAddress.of("user@example.com")

        assertThat(address.toString()).doesNotContain("user@example.com")
    }

    @Test
    @DisplayName("같은 정규화 값이면 동등하다")
    fun `동등성은 정규화된 값을 기준으로 한다`() {
        val a = EmailAddress.of("User@Example.com")
        val b = EmailAddress.of(" user@example.com ")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
