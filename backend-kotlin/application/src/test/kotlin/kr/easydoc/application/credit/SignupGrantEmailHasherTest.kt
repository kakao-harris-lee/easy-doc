package kr.easydoc.application.credit

import kr.easydoc.application.auth.normalizeEmail
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** [SignupGrantEmailHasher] — Spring 도 DB 도 없이 순수 함수만 잰다. */
class SignupGrantEmailHasherTest {
    @Test
    @DisplayName("같은 pepper·같은 정규화 이메일은 같은 16진 64자를 낸다")
    fun `결정적이고 64자 16진이다`() {
        val hasher = SignupGrantEmailHasher(Secret("pepper-value"))

        val hash = hasher.hash("user@example.com")

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}")
        assertThat(hasher.hash("user@example.com")).isEqualTo(hash)
    }

    @Test
    @DisplayName("대소문자만 다른 이메일도 기존 로그인 정규화(normalizeEmail)를 거치면 같은 해시가 된다")
    fun `정규화를 거치면 대소문자 차이가 사라진다`() {
        val hasher = SignupGrantEmailHasher(Secret("pepper-value"))

        val lower = hasher.hash(normalizeEmail("Foo@Example.com"))
        val upper = hasher.hash(normalizeEmail("foo@EXAMPLE.COM  "))

        assertThat(lower).isEqualTo(upper)
    }

    @Test
    @DisplayName("이메일이 다르면 해시도 다르다")
    fun `다른 이메일은 다른 해시`() {
        val hasher = SignupGrantEmailHasher(Secret("pepper-value"))

        assertThat(hasher.hash("a@example.com")).isNotEqualTo(hasher.hash("b@example.com"))
    }

    @Test
    @DisplayName("pepper 가 다르면 같은 이메일도 다른 해시가 된다 — 단순 SHA-256 이 아니다")
    fun `pepper 가 다르면 해시도 다르다`() {
        val hashA = SignupGrantEmailHasher(Secret("pepper-a")).hash("user@example.com")
        val hashB = SignupGrantEmailHasher(Secret("pepper-b")).hash("user@example.com")

        assertThat(hashA).isNotEqualTo(hashB)
    }
}
