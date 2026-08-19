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
 * 이메일이 `toString()` 으로 새지 않고, **직렬화되는 값은 그대로**임을 확인한다
 * (게이트 23 privacy-gate 3a 의 해제 조건).
 *
 * `SensitiveToStringReachTest` 가 종류를 잡는 탐지기라면 이 파일은 **두 축의 구분**을
 * 잡는다. 가리는 축을 응답까지 밀면 계약(`UserResponse.required = [id, email]`)이 깨지므로,
 * 그 경계가 실제로 서 있는지는 값을 직접 봐야 한다 — `WorkspaceDtoLeakTest` 가 작업 공간
 * 이름에 대해 같은 자리를 지킨다.
 *
 * 실제 HTTP 응답 바이트에 이메일이 실리는지는 `AuthContractTest` 의 `/auth/me` 케이스가
 * 계약에서 읽은 키 집합으로 못박는다 — 여기서 그것을 되풀이하지 않는다.
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
        // 문자열 보간으로도 같은 경로를 탄다 — 로거 인자로 실리는 가장 흔한 형태다.
        assertThat("$user").doesNotContain(email)
    }

    @Test
    @DisplayName("가리는 것은 toString 뿐이다 — 응답에 실리는 값은 그대로다")
    fun `응답 값 자체는 이메일을 그대로 담는다`() {
        val user = User(UUID.randomUUID(), email, Instant.EPOCH)

        // 여기까지 가리면 계약 `UserResponse.required = [id, email]` 이 깨진다.
        assertThat(UserResponse.of(user).email).isEqualTo(email)
        assertThat(user.email).isEqualTo(email)
    }
}
