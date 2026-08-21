package kr.easydoc.api

import kr.easydoc.api.auth.UserResponse
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.user.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 이메일이 `toString()` 으로 새지 않고, 직렬화되는 값은 그대로임을 확인한다
 * (게이트 23 privacy-gate 3a 의 해제 조건).
 */
class AuthDtoLeakTest {
    private val email = "leak-probe@example.test"

    @Test
    @DisplayName("User·UserResponse 의 toString 이 이메일을 노출하지 않는다")
    fun `도메인·응답 타입이 이메일을 가린다`() {
        val user = User(UUID.randomUUID(), email, Instant.EPOCH)

        listOf(user.toString(), UserResponse.of(user).toString()).forEach { rendered ->
            assertThat(rendered).doesNotContain(email)
            assertThat(rendered).contains(CONTENT_MASK)
        }

        assertThat("$user").doesNotContain(email)
    }

    @Test
    @DisplayName("가리는 것은 toString 뿐이다 — 응답에 실리는 값은 그대로다")
    fun `응답 값 자체는 이메일을 그대로 담는다`() {
        val user = User(UUID.randomUUID(), email, Instant.EPOCH)

        assertThat(UserResponse.of(user).email).isEqualTo(email)
        assertThat(user.email).isEqualTo(email)
    }
}
