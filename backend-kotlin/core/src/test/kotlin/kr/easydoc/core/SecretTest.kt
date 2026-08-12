package kr.easydoc.core

import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 비밀값이 로그로 새지 않는지 확인한다.
 *
 * Python `app/config.py` 는 `SecretStr` 로 이 보호를 얻는다. Kotlin 기본 동작은
 * 정반대라(데이터 클래스 `toString()` 이 모든 필드를 찍는다) 명시적으로 막고
 * 그 사실을 테스트로 고정한다.
 */
class SecretTest {
    private val plain = "super-secret-jwt-key-0123456789abcdef"

    @Test
    fun `toString 은 평문을 노출하지 않는다`() {
        val secret = Secret(plain)

        assertThat(secret.toString()).isEqualTo(Secret.MASK)
        assertThat(secret.toString()).doesNotContain(plain)
    }

    @Test
    fun `문자열 템플릿에 실려도 평문이 나가지 않는다`() {
        val secret = Secret(plain)

        // 로거 인자·예외 메시지가 실제로 이 모양으로 만들어진다.
        val line = "설정 로드 완료: jwtSecret=$secret"

        assertThat(line).doesNotContain(plain)
        assertThat(line).isEqualTo("설정 로드 완료: jwtSecret=${Secret.MASK}")
    }

    @Test
    fun `데이터 클래스 필드로 들어가도 평문이 나가지 않는다`() {
        // 가장 흔한 유출 경로 — 설정 클래스를 통째로 로깅하는 경우.
        data class SampleSettings(
            val databaseUrl: String,
            val jwtSecret: Secret,
        )

        val rendered = SampleSettings("jdbc:postgresql://localhost/easydoc", Secret(plain)).toString()

        assertThat(rendered).doesNotContain(plain)
        assertThat(rendered).contains(Secret.MASK)
        // 비밀이 아닌 값은 그대로 보여야 진단이 된다.
        assertThat(rendered).contains("jdbc:postgresql://localhost/easydoc")
    }

    @Test
    fun `reveal 로만 평문을 꺼낼 수 있다`() {
        assertThat(Secret(plain).reveal()).isEqualTo(plain)
    }

    @Test
    fun `hashCode 는 값에 의존하지 않는다`() {
        assertThat(Secret(plain).hashCode()).isEqualTo(Secret("완전히 다른 값").hashCode())
    }

    @Test
    fun `같은 값끼리만 같다`() {
        assertThat(Secret(plain)).isEqualTo(Secret(plain))
        assertThat(Secret(plain)).isNotEqualTo(Secret(plain + "x"))
        assertThat(Secret(plain)).isNotEqualTo(null)
    }

    @Test
    fun `미설정 상태를 평문 노출 없이 판정한다`() {
        assertThat(Secret.EMPTY.isBlank()).isTrue()
        assertThat(Secret(plain).isBlank()).isFalse()
    }
}
