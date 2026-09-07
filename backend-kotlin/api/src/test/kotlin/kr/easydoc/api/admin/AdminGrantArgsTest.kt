package kr.easydoc.api.admin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

/**
 * [AdminGrantArgs.parse] 의 검증 분기 — Spring 컨텍스트도 DB도 없이,
 * [DefaultApplicationArguments] 를 직접 만들어 잰다(`InvoiceHandleArgsTest`와 같은 형태).
 */
class AdminGrantArgsTest {
    @Test
    @DisplayName("--email 만 주면 부여(revoke=false)로 파싱된다")
    fun `기본은 부여다`() {
        val args = AdminGrantArgs.parse(DefaultApplicationArguments("--email=admin@example.test"))

        assertThat(args.email).isEqualTo("admin@example.test")
        assertThat(args.revoke).isFalse()
    }

    @Test
    @DisplayName("--revoke 플래그가 있으면 회수로 파싱된다")
    fun `revoke 플래그`() {
        val args = AdminGrantArgs.parse(DefaultApplicationArguments("--email=admin@example.test", "--revoke"))

        assertThat(args.revoke).isTrue()
    }

    @Test
    @DisplayName("--email 이 없으면 거절된다")
    fun `email 필수`() {
        assertThatThrownBy { AdminGrantArgs.parse(DefaultApplicationArguments("--revoke")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("--email")
    }

    @Test
    @DisplayName("toString은 이메일을 찍지 않는다")
    fun `toString 은 이메일을 가린다`() {
        val args = AdminGrantArgs.parse(DefaultApplicationArguments("--email=admin@example.test"))

        assertThat(args.toString()).doesNotContain("admin@example.test")
    }
}
