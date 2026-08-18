package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.core.user.PasswordHash
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.concurrent.Semaphore

/**
 * Argon2id 해시 정책. 값의 정본은 `easydoc.auth.argon2.*` 설정이고, 기본값은 계약
 * `x-auth.password_hash` 가 적은 조합이다.
 *
 * **라이브러리 기본값에 기대지 않는다**(`migration-safety-gate` I-8). Spring Security 의
 * `defaultsForSpringSecurity_v5_8()` 은 memory 16MiB · parallelism 1 이라 계약과 다르다.
 */
data class Argon2Policy(
    val variant: String,
    val version: Int,
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val saltLength: Int,
    val hashLength: Int,
) {
    /** 저장된 PHC 가 이 정책과 **전부** 같은지. 하나라도 다르면 `false` 다. */
    internal fun matches(stored: Argon2Phc): Boolean =
        stored.variant == variant &&
            stored.version == version &&
            stored.memoryKib == memoryKib &&
            stored.iterations == iterations &&
            stored.parallelism == parallelism &&
            stored.saltLength == saltLength &&
            stored.hashLength == hashLength
}

/**
 * Argon2id 비밀번호 해시 — `migration-safety-gate` I-8 의 구현.
 *
 * ## 해시·검증은 라이브러리가 한다
 *
 * [Argon2PasswordEncoder] 가 BouncyCastle `Argon2BytesGenerator` 를 부르고 PHC 인코딩과
 * 상수 시간 비교까지 맡는다. **검증 파라미터를 하드코딩하지 않는다** — 인코더가 저장된
 * PHC 에서 읽어 쓰므로, 정책을 올린 뒤에도 옛 해시로 로그인이 된다(I-8 검증 1).
 *
 * ## 재해시 판정만 직접 한다
 *
 * [needsRehash] 는 [Argon2PasswordEncoder.upgradeEncoding] 을 **쓰지 않는다.** 그쪽은
 * memory·iterations 의 "미만"만 보므로 파라미터를 낮춘 경우와 `parallelism`·salt 길이·
 * hash 길이 변경을 놓친다(필수조치 A). 여기서는 [Argon2Phc] 로 파라미터 집합 전체를
 * 읽어 [Argon2Policy.matches] 로 판정한다.
 *
 * ## 동시 실행 수를 제한한다 (I-8 검증 5)
 *
 * `memory_cost = 65536` 은 1건당 64MiB 를 계산이 끝날 때까지 붙들고 있다. 상한이 없으면
 * 로그인 요청 수십 건이 동시에 들어올 때 컨테이너가 OOM 으로 죽는다 — **인증 엔드포인트가
 * 서비스 거부 벡터가 된다.** 세마포어로 동시 실행 수를 묶으면 초과분은 큐에서 기다린다.
 * 요청 스레드가 블로킹되지만, 그것이 프로세스가 죽는 것보다 낫다.
 */
class Argon2PasswordHasher(
    private val policy: Argon2Policy,
    maxConcurrentHashes: Int,
) : PasswordHasher {
    init {
        require(maxConcurrentHashes > 0) { "동시 해시 상한은 1 이상이어야 합니다" }
        require(policy.variant == ARGON2ID) { "argon2id 만 지원합니다" }
        // 인코더는 언제나 Argon2 v1.3(0x13 = 19)으로 해시한다 — 그 값을 고를 수 있는
        // 생성자 인자가 없다. 정책에 다른 버전을 적으면 **모든 로그인이 매번 재해시
        // 대상**이 되고 그 상태는 조용하다. 조립 시점에 끊는다.
        require(policy.version == ENCODER_VERSION) {
            "Argon2 버전은 $ENCODER_VERSION 만 지원합니다 (라이브러리가 그 값으로만 해시한다)"
        }
    }

    private val encoder =
        Argon2PasswordEncoder(
            policy.saltLength,
            policy.hashLength,
            policy.parallelism,
            policy.memoryKib,
            policy.iterations,
        )

    private val permits = Semaphore(maxConcurrentHashes, /* fair = */ true)

    override fun hash(rawPassword: String): PasswordHash {
        // `encode` 의 반환 타입은 nullable 로 선언돼 있다(Spring Security 7 의 JSpecify
        // 애너테이션). 실제로 null 이 나오는 경로는 없지만 `!!` 를 쓰지 않는다 — 거기서
        // NPE 가 나면 원인을 알 수 없다(kotlin-spring-conventions §7).
        val encoded = withPermit { encoder.encode(rawPassword) }
        return PasswordHash(checkNotNull(encoded) { "비밀번호 해시를 만들지 못했습니다" })
    }

    override fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean = withPermit { encoder.matches(rawPassword, stored.reveal()) }

    /**
     * 파라미터 집합이 하나라도 다르면 `true`.
     *
     * 읽지 못하는 형식도 `true` 다 — 현행 정책으로 만든 해시가 아니라는 뜻이므로 올린다.
     * 이 함수는 검증이 성공한 뒤에만 불리므로, 읽지 못하는 해시로 여기까지 오는 경로는
     * 실질적으로 없다.
     */
    override fun needsRehash(stored: PasswordHash): Boolean {
        val parsed = Argon2Phc.parse(stored.reveal()) ?: return true
        return !policy.matches(parsed)
    }

    /**
     * 세마포어를 쥐고 실행한다.
     *
     * `acquire()` 는 인터럽트에 반응한다 — 요청이 취소되면 대기를 풀어 준다. 인터럽트
     * 상태를 복원하지 않고 삼키면 상위 계층이 취소를 알지 못한다.
     */
    private fun <T> withPermit(block: () -> T): T {
        permits.acquire()
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    private companion object {
        const val ARGON2ID = "argon2id"

        /** `Argon2Parameters.ARGON2_VERSION_13`. 인코더가 만드는 PHC 의 `v=` 값이다. */
        const val ENCODER_VERSION = 19
    }
}
