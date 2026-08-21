package kr.easydoc.infrastructure.auth

import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier

/** X-I2 — Argon2 재해시 판정 (`migration-safety-gate` I-8). */
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

    /** 필수조치 A 의 핵심. `upgradeEncoding()` 은 "미만"만 보므로 이 방향을 놓친다. */
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

        assertThat(hasher(policy(iterations = ITERATIONS + 1)).verify(PASSWORD, old)).isTrue()
    }

    @Test
    @DisplayName("라이브러리가 만들 수 없는 버전을 정책에 적으면 조립 시점에 끊는다")
    fun `만들 수 없는 버전은 조립에서 막힌다`() {
        assertThatThrownBy { hasher(policy().copy(version = LEGACY_VERSION)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("동시 실행 상한이 0 이하면 조립에서 막힌다")
    fun `동시 실행 상한이 있어야 한다`() {
        assertThatThrownBy { Argon2PasswordHasher(policy(), maxConcurrentHashes = 0, maxWaitMillis = WAIT_MILLIS) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("대기 상한이 0 이하면 조립에서 막힌다")
    fun `대기 상한이 있어야 한다`() {
        assertThatThrownBy { Argon2PasswordHasher(policy(), maxConcurrentHashes = 1, maxWaitMillis = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("대기 상한을 넘기면 무한 대기 대신 배압 예외를 던진다")
    fun `대기 상한을 넘기면 예외다`() {
        val blocking =
            Argon2PasswordHasher(
                policy(memoryKib = CONTENDED_MEMORY_KIB),
                maxConcurrentHashes = 1,
                maxWaitMillis = TINY_WAIT_MILLIS,
            )
        val start = CyclicBarrier(CONTENDERS)
        val failures = CopyOnWriteArrayList<Throwable>()
        val threads =
            (1..CONTENDERS).map {
                Thread {
                    start.await()
                    runCatching { blocking.hash(PASSWORD) }.onFailure { failure -> failures += failure }
                }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(failures)
            .withFailMessage("대기가 상한 없이 흘러갔다 — 무한 대기로 되돌아갔는지 확인한다")
            .isNotEmpty()
        assertThat(failures).allMatch { it is PasswordHashingOverloadedException }
    }

    @Test
    @DisplayName("더미 PHC 가 현행 정책 파라미터로 만들어진다 — 비용이 같아야 격차가 없어진다")
    fun `더미 해시가 현행 정책을 따른다`() {
        val policy = policy()
        val hasher = hasher(policy)

        val parsed = Argon2Phc.parse(hasher.dummyHash().reveal())

        assertThat(parsed)
            .withFailMessage("더미 해시가 PHC 형식이 아니다 — 상수 문자열을 돌려주면 비용이 0 이 된다")
            .isNotNull()

        assertThat(hasher.needsRehash(hasher.dummyHash()))
            .withFailMessage("더미 해시의 파라미터가 현행 정책과 다르다")
            .isFalse()

        assertThat(hasher.dummyHash().reveal()).isEqualTo(hasher.dummyHash().reveal())
    }

    /** privacy-gate M-1 — 선언된 불변식이 참인지 실제로 잰다. */
    @Test
    @DisplayName("더미 PHC 의 원문이 코드 상수가 아니다 — 아는 값으로 verify 가 통과하지 않는다")
    fun `더미 해시로는 통과하지 못한다`() {
        val hasher = hasher(policy())
        val dummy = hasher.dummyHash()

        assertThat(hasher.verify(PASSWORD, dummy)).isFalse()
        assertThat(hasher.verify("", dummy)).isFalse()

        val constants = stringConstantsOf(Argon2PasswordHasher::class.java)

        assertThat(constants)
            .withFailMessage("프로덕션 클래스에서 문자열 상수를 하나도 읽지 못했다 — 탐지가 공허하다")
            .isNotEmpty()
        constants.forEach { constant ->
            assertThat(hasher.verify(constant, dummy))
                .withFailMessage("더미 PHC 의 원문이 코드 상수(%s)다 — 아는 값으로 통과하는 해시다", constant)
                .isFalse()
        }
    }

    /** 클래스와 그 중첩 타입이 들고 있는 정적 문자열 값 전부. `private const` 도 읽는다. */
    private fun stringConstantsOf(type: Class<*>): List<String> =
        (
            type.declaredFields.asSequence() +
                type.declaredClasses.asSequence().flatMap { it.declaredFields.asSequence() }
        ).filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(null) as? String
                }.getOrNull()
            }.toList()

    private fun hasher(policy: Argon2Policy) =
        Argon2PasswordHasher(policy, maxConcurrentHashes = 2, maxWaitMillis = WAIT_MILLIS)

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
         * 테스트 파라미터는 운영 기본값보다 작다. 여기서 재는 것은 파라미터의 세기가
         * 아니라 판정 관계이고, 1건당 64MiB 를 십수 번 돌리면 스위트가 초 단위로 느려진다.
         * 운영 기본값 자체는 `AuthConfiguration` 이 들고 계약이 정한다.
         */
        const val MEMORY_KIB = 1024
        const val ITERATIONS = 2
        const val PARALLELISM = 1

        /** 정상 경로가 대기 상한에 걸리지 않을 만큼 넉넉한 값. 여기서 재는 것은 상한이 아니다. */
        const val WAIT_MILLIS = 30_000L

        /** 배압 케이스 전용 — 한 건이라도 앞에 있으면 반드시 넘긴다. */
        const val TINY_WAIT_MILLIS = 1L

        /** 배압 케이스에서 해시 1건이 대기 상한보다 확실히 오래 걸리게 하는 메모리. */
        const val CONTENDED_MEMORY_KIB = 8192

        /** 자리(1개)보다 많아야 대기가 생긴다. */
        const val CONTENDERS = 4
        const val SALT_LENGTH = 16
        const val HASH_LENGTH = 32

        const val ENCODER_VERSION = 19
        const val LEGACY_VERSION = 16
    }
}
