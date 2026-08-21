package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.core.user.PasswordHash
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Argon2id 해시 정책. 값의 정본은 `easydoc.auth.argon2.*` 설정이고, 기본값은 계약
 * `x-auth.password_hash` 가 적은 조합이다.
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

// 정책·더미 원문 검증: `Argon2PasswordHasherTest`.

/** Argon2id 비밀번호 해시 — `migration-safety-gate` I-8 의 구현. */
class Argon2PasswordHasher(
    private val policy: Argon2Policy,
    maxConcurrentHashes: Int,
    private val maxWaitMillis: Long,
) : PasswordHasher {
    init {
        require(maxConcurrentHashes > 0) { "동시 해시 상한은 1 이상이어야 합니다" }
        require(maxWaitMillis > 0) { "해시 대기 상한은 1밀리초 이상이어야 합니다" }
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

    private val permits = // 공정 모드: 대기가 길어져도 굶는 요청이 없게 한다.
        Semaphore(maxConcurrentHashes, true)

    /** 계정 부재 경로가 쓰는 더미 PHC — **조립 시점에 현행 정책으로 한 번 만든다.** */
    private val dummy: PasswordHash =
        PasswordHash(
            checkNotNull(encoder.encode(randomDummySource())) { "더미 해시를 만들지 못했습니다" },
        )

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

    /** 파라미터 집합이 하나라도 다르면 `true`. */
    override fun needsRehash(stored: PasswordHash): Boolean {
        val parsed = Argon2Phc.parse(stored.reveal()) ?: return true
        return !policy.matches(parsed)
    }

    override fun dummyHash(): PasswordHash = dummy

    /** 세마포어를 쥐고 실행한다. */
    private fun <T> withPermit(block: () -> T): T {
        if (!permits.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS)) {
            throw PasswordHashingOverloadedException(maxWaitMillis)
        }
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

        /** 더미 원문의 길이. 추측 대상이 되지 않을 만큼이면 충분하고, 비용은 원문 길이와 무관하다. */
        const val DUMMY_SOURCE_BYTES = 32

        /** 더미 PHC 의 원문 — **기동마다 새로 뽑는 난수**다. */
        fun randomDummySource(): String {
            val bytes = ByteArray(DUMMY_SOURCE_BYTES)
            SecureRandom().nextBytes(bytes)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}

/** Argon2 대기 상한을 넘겼다 — **과부하 배압**이지 사용자 잘못이 아니다. */
class PasswordHashingOverloadedException(waitedMillis: Long) :
    IllegalStateException("비밀번호 해시 대기가 상한(${waitedMillis}ms)을 넘겼습니다")
