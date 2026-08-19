package kr.easydoc.infrastructure.auth

import kr.easydoc.core.user.PasswordHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier

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
        assertThatThrownBy { Argon2PasswordHasher(policy(), maxConcurrentHashes = 0, maxWaitMillis = WAIT_MILLIS) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("대기 상한이 0 이하면 조립에서 막힌다")
    fun `대기 상한이 있어야 한다`() {
        // 무기한 대기는 요청 스레드를 영원히 반납하지 않아 과부하가 가용성 사고가 된다.
        assertThatThrownBy { Argon2PasswordHasher(policy(), maxConcurrentHashes = 1, maxWaitMillis = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @DisplayName("대기 상한을 넘기면 무한 대기 대신 배압 예외를 던진다")
    fun `대기 상한을 넘기면 예외다`() {
        // 자리는 하나, 대기 상한은 1밀리초, 해시 1건은 그보다 훨씬 오래 걸리는 파라미터.
        // 네 스레드를 **같은 시점에** 풀어 놓으면 자리를 못 잡은 쪽은 상한에 걸린다.
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

        // 종전 `acquire()` 였다면 전부 성공하고 목록이 비어 있다.
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
        // 파라미터가 다르면 검증 비용이 달라져 응답 시간 격차가 그대로 남는다(계약 x-auth).
        assertThat(hasher.needsRehash(hasher.dummyHash()))
            .withFailMessage("더미 해시의 파라미터가 현행 정책과 다르다")
            .isFalse()
        // **조립 1회 생성**이어야 한다. 부를 때마다 새로 만들면 첫 「없는 이메일」 요청이
        // 생성+검증으로 두 배를 쓰고, 그 한 건이 타이밍 측정의 표본이 된다.
        assertThat(hasher.dummyHash().reveal()).isEqualTo(hasher.dummyHash().reveal())
    }

    /**
     * **privacy-gate M-1 — 선언된 불변식이 참인지 실제로 잰다.**
     *
     * 종전 회귀는 임의의 두 값(`"correct horse battery"`·`""`)만 넣었다. 그런데 그
     * 선언을 깨뜨리는 유일한 입력은 **20줄 옆 프로덕션 파일의 `const`** 였고
     * (`DUMMY_PHC_SOURCE`), 그 값을 넣으면 `verify` 가 `true` 였다. 이름이 주장하는
     * 성질에 대해 공허한 회귀였다.
     *
     * 그래서 추측을 늘리는 대신 **탐지 방식**을 바꾼다 — 프로덕션 클래스가 들고 있는
     * 문자열 상수 전부를 입력으로 넣는다. 원문을 상수로 되돌리는 순간 그 상수가 여기
     * 입력으로 들어와 빨개진다. 난수 원문이 유지되는 한 어떤 상수도 맞지 않는다.
     */
    @Test
    @DisplayName("더미 PHC 의 원문이 코드 상수가 아니다 — 아는 값으로 verify 가 통과하지 않는다")
    fun `더미 해시로는 통과하지 못한다`() {
        val hasher = hasher(policy())
        val dummy = hasher.dummyHash()

        assertThat(hasher.verify(PASSWORD, dummy)).isFalse()
        assertThat(hasher.verify("", dummy)).isFalse()

        val constants = stringConstantsOf(Argon2PasswordHasher::class.java)
        // 상수를 하나도 읽지 못하면 아래 반복이 0회라 이 탐지가 공허해진다.
        assertThat(constants)
            .withFailMessage("프로덕션 클래스에서 문자열 상수를 하나도 읽지 못했다 — 탐지가 공허하다")
            .isNotEmpty()
        constants.forEach { constant ->
            assertThat(hasher.verify(constant, dummy))
                .withFailMessage("더미 PHC 의 원문이 코드 상수(%s)다 — 아는 값으로 통과하는 해시다", constant)
                .isFalse()
        }
    }

    /** 클래스와 그 중첩 타입이 들고 있는 **정적 문자열 값** 전부. `private const` 도 읽는다. */
    private fun stringConstantsOf(type: Class<*>): List<String> =
        (type.declaredFields.asSequence() + type.declaredClasses.asSequence().flatMap { it.declaredFields.asSequence() })
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
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
         * 테스트 파라미터는 **운영 기본값보다 작다.** 여기서 재는 것은 파라미터의 세기가
         * 아니라 **판정 관계**이고, 1건당 64MiB 를 십수 번 돌리면 스위트가 초 단위로 느려진다.
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
