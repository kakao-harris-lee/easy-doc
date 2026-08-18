package kr.easydoc.infrastructure.auth

import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **X-I2 — Argon2 재해시 판정** (`migration-safety-gate` I-8).
 *
 * HTTP 경계에서는 보이지 않는다(재해시가 일어나든 말든 로그인 응답이 같다). 그래서 단위
 * 테스트이며, 단언하는 것은 값이 아니라 **관계**다 — 저장된 PHC 의 파라미터 집합이 현재
 * 설정과 **하나라도 다르면** 재해시, 전부 같으면 재해시하지 않는다.
 *
 * ## 두 방향을 모두 건다
 *
 * Spring Security `Argon2PasswordEncoder.upgradeEncoding()` 은 memory·iterations 의
 * **"미만"만** 본다. 그래서 파라미터를 **낮춘** 경우와 `parallelism`·salt 길이·hash 길이만
 * 바뀐 경우를 "최신"으로 오판한다(Phase 0 탐침 7건 중 5건 불일치). 올린 방향만 걸면 그
 * 구현이 그대로 통과하므로 **낮춘 방향도 함께** 건다.
 */
class Argon2PasswordHasherTest {
    @Test
    @DisplayName("해시·검증 round-trip 이 성립하고 틀린 비밀번호는 거부한다")
    fun `해시와 검증이 맞물린다`() {
        val hasher = hasher(policy())

        val hash = hasher.hash(PASSWORD)

        assertThat(hasher.verify(PASSWORD, hash)).isTrue()
        assertThat(hasher.verify("${PASSWORD}x", hash)).isFalse()
    }

    @Test
    @DisplayName("같은 비밀번호도 매번 다른 해시가 된다 — salt 가 고정되지 않았다")
    fun `salt 가 매번 새로 만들어진다`() {
        val hasher = hasher(policy())

        // 같은 값이 나오면 salt 가 상수이고, 그러면 무지개 표 하나로 전 사용자가 뚫린다.
        assertThat(hasher.hash(PASSWORD).reveal()).isNotEqualTo(hasher.hash(PASSWORD).reveal())
    }

    @Test
    @DisplayName("파라미터가 전부 같으면 재해시하지 않는다")
    fun `현행 파라미터는 재해시 대상이 아니다`() {
        val hasher = hasher(policy())

        assertThat(hasher.needsRehash(hasher.hash(PASSWORD))).isFalse()
    }

    @Test
    @DisplayName("파라미터를 **올린** 정책은 옛 해시를 재해시 대상으로 본다")
    fun `올린 파라미터는 재해시 대상이다`() {
        val old = hasher(policy()).hash(PASSWORD)

        assertThat(hasher(policy(memoryKib = MEMORY_KIB * 2)).needsRehash(old)).isTrue()
        assertThat(hasher(policy(iterations = ITERATIONS + 1)).needsRehash(old)).isTrue()
    }

    /**
     * **필수조치 A 의 핵심.** `upgradeEncoding()` 은 "미만"만 보므로 이 방향을 놓친다.
     *
     * 파라미터를 낮추는 일이 실제로 있다 — 하드웨어를 줄이거나 로그인 지연을 줄일 때다.
     * 그때 옛 해시가 "최신"으로 남으면 이관이 조용히 멈춘다.
     */
    @Test
    @DisplayName("파라미터를 **낮춘** 정책도 옛 해시를 재해시 대상으로 본다")
    fun `낮춘 파라미터도 재해시 대상이다`() {
        val old = hasher(policy()).hash(PASSWORD)

        assertThat(hasher(policy(memoryKib = MEMORY_KIB / 2)).needsRehash(old)).isTrue()
        assertThat(hasher(policy(iterations = ITERATIONS - 1)).needsRehash(old)).isTrue()
    }

    /** `upgradeEncoding()` 이 아예 보지 않는 세 축. 여기가 비면 그 구현과 구분되지 않는다. */
    @Test
    @DisplayName("parallelism · salt 길이 · hash 길이만 달라도 재해시 대상이다")
    fun `비교 대상이 아닌 축까지 본다`() {
        val old = hasher(policy()).hash(PASSWORD)

        assertThat(hasher(policy(parallelism = PARALLELISM + 1)).needsRehash(old)).isTrue()
        assertThat(hasher(policy(saltLength = SALT_LENGTH + 8)).needsRehash(old)).isTrue()
        assertThat(hasher(policy(hashLength = HASH_LENGTH + 16)).needsRehash(old)).isTrue()
    }

    @Test
    @DisplayName("읽을 수 없는 해시는 재해시 대상이다 — 조용히 「최신」으로 두지 않는다")
    fun `형식이 아닌 해시는 재해시 대상이다`() {
        assertThat(hasher(policy()).needsRehash(PasswordHash("이건 PHC 가 아니다"))).isTrue()
    }

    @Test
    @DisplayName("옛 파라미터로 만든 해시도 검증된다 — 검증 파라미터를 PHC 에서 읽는다")
    fun `파라미터를 올려도 옛 해시로 로그인된다`() {
        val old = hasher(policy()).hash(PASSWORD)

        // 검증기가 자기 설정으로 해시하면 이 단언이 깨진다 = 정책을 올리는 날 전 사용자가 로그인 불가.
        assertThat(hasher(policy(iterations = ITERATIONS + 1)).verify(PASSWORD, old)).isTrue()
    }

    @Test
    @DisplayName("라이브러리가 만들 수 없는 버전을 정책에 적으면 조립 시점에 끊는다")
    fun `만들 수 없는 버전은 조립에서 막힌다`() {
        // 그러지 않으면 **모든 로그인이 매번 재해시 대상**이 되고 그 상태는 조용하다.
        assertThatThrownBy { hasher(policy().copy(version = LEGACY_VERSION)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("동시 실행 상한이 0 이하면 조립에서 막힌다")
    fun `동시 실행 상한이 있어야 한다`() {
        // 상한이 없으면 로그인 폭주가 그대로 OOM 이 된다(I-8 검증 5).
        assertThatThrownBy { Argon2PasswordHasher(policy(), maxConcurrentHashes = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun hasher(policy: Argon2Policy) = Argon2PasswordHasher(policy, maxConcurrentHashes = 2)

    private fun policy(
        memoryKib: Int = MEMORY_KIB,
        iterations: Int = ITERATIONS,
        parallelism: Int = PARALLELISM,
        saltLength: Int = SALT_LENGTH,
        hashLength: Int = HASH_LENGTH,
    ) = Argon2Policy("argon2id", ENCODER_VERSION, memoryKib, iterations, parallelism, saltLength, hashLength)

    private companion object {
        const val PASSWORD = "correct horse battery"

        /**
         * 테스트 파라미터는 **운영 기본값보다 작다.** 여기서 재는 것은 파라미터의 세기가
         * 아니라 **판정 관계**이고, 1건당 64MiB 를 십수 번 돌리면 스위트가 초 단위로 느려진다.
         * 운영 기본값 자체는 `AuthConfiguration` 이 들고 계약이 정한다.
         */
        const val MEMORY_KIB = 1024
        const val ITERATIONS = 2
        const val PARALLELISM = 1
        const val SALT_LENGTH = 16
        const val HASH_LENGTH = 32

        const val ENCODER_VERSION = 19
        const val LEGACY_VERSION = 16
    }
}
