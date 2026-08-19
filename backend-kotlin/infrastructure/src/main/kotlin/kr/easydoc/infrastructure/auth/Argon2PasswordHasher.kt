package kr.easydoc.infrastructure.auth

import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.core.user.PasswordHash
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
 *
 * ## 대기에 상한을 둔다
 *
 * 종전 `acquire()` 는 **무기한** 기다렸다. 그 상태에서 요청 스레드는 영원히 반납되지 않고,
 * 대기 줄이 스레드 풀보다 길어지면 다른 API 까지 함께 멈춘다. 계정이 없는 로그인도 더미
 * 해시로 같은 비용을 치르게 되면서(계약 `x-auth.failure_uniformity`) **무료였던 경로가
 * 세마포어를 소비**하므로 그 줄은 더 길어진다.
 *
 * 그래서 [tryAcquire] 로 바꾸고 상한을 넘기면 [PasswordHashingOverloadedException] 을
 * 던진다. 무한 대기가 지연 저하에서 **가용성 사고**로 넘어가는 자리를 끊는 것이고,
 * 여전히 OOM 은 막는다 — 동시 실행 수 자체는 그대로 묶여 있다.
 */
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

    /**
     * 계정 부재 경로가 쓰는 더미 PHC — **조립 시점에 현행 정책으로 한 번 만든다.**
     *
     * 지연 생성하지 않는 이유: 첫 「없는 이메일」 로그인이 해시 생성 + 검증으로 **두 배**를
     * 쓰게 되고, 그 한 건이 타이밍 회귀 측정의 첫 표본이 될 수 있다.
     *
     * 상수 문자열을 쓰지 않는 이유: 계약이 요구하는 것은 「같은 검증 비용」이고 비용은
     * PHC 안의 파라미터가 정한다. 정책을 올리면 이 값도 함께 올라가야 한다.
     */
    private val dummy: PasswordHash =
        PasswordHash(
            checkNotNull(encoder.encode(DUMMY_PHC_SOURCE)) { "더미 해시를 만들지 못했습니다" },
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

    override fun dummyHash(): PasswordHash = dummy

    /**
     * 세마포어를 쥐고 실행한다.
     *
     * `tryAcquire(timeout)` 는 인터럽트에 반응한다 — 요청이 취소되면 대기를 풀어 준다.
     * 인터럽트 상태를 복원하지 않고 삼키면 상위 계층이 취소를 알지 못한다.
     */
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

        /**
         * 더미 PHC 를 만들 때 넣는 입력. **비밀이 아니다** — 이 값으로 로그인할 수 있는
         * 계정은 없고(가입은 이 해시를 저장하지 않는다), 값이 알려져도 얻는 것이 없다.
         * 사람이 읽을 수 있는 낱말꼴로 둔다: 난수꼴이면 유출된 키로 오인된다.
         */
        const val DUMMY_PHC_SOURCE = "absent-account-uniform-cost"
    }
}

/**
 * Argon2 대기 상한을 넘겼다 — **과부하 배압**이지 사용자 잘못이 아니다.
 *
 * ## 왜 도메인 예외가 아닌가
 *
 * 계약이 `/auth/login` 에 선언한 상태 코드는 200·401·422·500·503 다. 이 상황에 맞는
 * 전용 코드나 `detail` 문구를 새로 만드는 것은 계약 개정이고 그 권한은 `contract-keeper`
 * 에게 있다. 그래서 **계약이 이미 든 것 안에서** 고른다 — 도메인 예외로 만들지 않으면
 * `GlobalExceptionHandler` 의 백스톱이 500 + 기존 고정 문구로 낸다.
 *
 * 401 로 내지 않는 이유: 그러면 과부하가 「비밀번호가 틀렸다」로 둔갑해 진단이 막히고,
 * 자격증명을 확인하지도 않은 채 실패를 자격증명 탓으로 돌리게 된다.
 *
 * 메시지에는 상한 값만 담는다 — 이메일·비밀번호는 이 지점에 오지 않는다.
 */
class PasswordHashingOverloadedException(waitedMillis: Long) :
    IllegalStateException("비밀번호 해시 대기가 상한(${waitedMillis}ms)을 넘겼습니다")
