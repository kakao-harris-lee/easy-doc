package kr.easydoc.application.auth

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 만료 인증 아티팩트(`email_verification_codes`·`password_reset_codes`·`oauth_states`)
 * 파기 한 번의 집계. **표별 삭제 건수만 담는다** — `code_hash`·`salt`·`state`·`nonce`는
 * 어디에도 남지 않는다(`SignupGrantRecordPurgeResult`·`UnverifiedAccountPurgeResult`와
 * 같은 판단).
 */
class ExpiredAuthArtifactPurgeResult(
    val enabled: Boolean,
    val emailVerificationCodesDeleted: Int,
    val passwordResetCodesDeleted: Int,
    val oauthStatesDeleted: Int,
) {
    override fun toString(): String =
        "ExpiredAuthArtifactPurgeResult(enabled=$enabled, " +
            "emailVerificationCodesDeleted=$emailVerificationCodesDeleted, " +
            "passwordResetCodesDeleted=$passwordResetCodesDeleted, " +
            "oauthStatesDeleted=$oauthStatesDeleted)"
}

/**
 * `email_verification_codes`·`password_reset_codes`·`oauth_states` 세 표에서 만료된
 * 행을 고르고 지운다(`docs/plans/2026-09-10-personal-data-inventory.md` §2.2 확정 결함,
 * 개인정보 보호법 §21 — 목적 달성 시 지체 없이 파기). 앞의 두 표는 `users` FK
 * `ON DELETE CASCADE`로 계정 삭제 시에만 사라진다 — 코드 자체는 10분이면 만료되는데
 * 행은 계정이 사는 동안 계속 쌓인다. `oauth_states`는 `user_id`가 NULL인 행(가입·로그인
 * 전 흐름이 채운다)은 CASCADE 경로가 아예 없어 영구 잔존·단조 증가한다 — 이 파기가 세
 * 표 모두의 유일한 소거 경로다.
 *
 * **파기 기준은 `created_at`이다 — `consumed_at`·`expires_at`을 쓰지 않는다.**
 * `JdbcOneTimeCodeStore.rejectIfWithinCooldown`(재발송 쿨다운)이 소비·만료 여부를 보지
 * 않고 `user_id`의 가장 최근 행 하나만 본다(`SELECT created_at ... ORDER BY created_at
 * DESC LIMIT 1`). `consumed_at IS NOT NULL`을 기준으로 지우면 인증을 마친 직후 그 행이
 * 사라져 쿨다운이 통째로 무력화된다 — 이 판단을 어기면 보안 회귀다.
 */
interface ExpiredAuthArtifactPurge {
    /** [createdBefore] 이전에 만들어진(`created_at < createdBefore`) 행을 표마다 [batchSize] 건까지 지운다. */
    fun purge(
        createdBefore: Instant,
        batchSize: Int,
    ): ExpiredAuthArtifactPurgeResult
}

/** 파기 결과를 감사·메트릭으로 남긴다. 해시·salt·state·nonce를 받지 않는다. */
fun interface ExpiredAuthArtifactPurgeObserver {
    fun record(result: ExpiredAuthArtifactPurgeResult)
}

/** worker 스케줄이 넘기는 정책. 값은 설정(`easydoc.auth.ephemeral-purge`)에서 온다. */
class ExpiredAuthArtifactPurgePolicy(
    val enabled: Boolean,
    val retention: Duration,
    val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "만료 인증 아티팩트 파기 배치 크기가 1보다 작다" }
        require(!retention.isNegative && !retention.isZero) { "만료 인증 아티팩트 파기 보존기간이 0 이하다" }
    }
}

/**
 * 기본 24시간이 지난 세 표의 행을 지운다. `PurgeUnverifiedAccounts`·`PurgeSignupGrantRecords`
 * 와 같은 배치 흐름이다 — 대상량이 배치를 넘으면 배치가 짧아질 때까지 트랜잭션을 반복한다.
 * 세 표를 한 저장소 호출(=한 트랜잭션)에서 함께 지운다 — 세 표 중 하나라도 이번 배치에서
 * 한도(`batchSize`)만큼 지웠으면 그 표에 아직 대상이 남아 있을 수 있으므로 반복한다.
 */
class PurgeExpiredAuthArtifacts(
    private val store: ExpiredAuthArtifactPurge,
    private val transaction: TransactionRunner,
    private val observer: ExpiredAuthArtifactPurgeObserver,
    private val policy: ExpiredAuthArtifactPurgePolicy,
    private val clock: Clock,
) {
    fun run(): ExpiredAuthArtifactPurgeResult {
        val result = if (policy.enabled) drainPurges() else inactiveResult()
        observer.record(result)
        return result
    }

    private fun oneBatch(createdBefore: Instant): ExpiredAuthArtifactPurgeResult =
        transaction.inTransaction {
            store.purge(createdBefore = createdBefore, batchSize = policy.batchSize)
        }

    /**
     * `createdBefore`는 이 실행에서 한 번만 계산한다 — `PurgeUnverifiedAccounts.drainPurges`
     * 와 같은 이유(배치를 반복하는 동안 시각이 흘러도 같은 실행 안에서는 같은 기준으로
     * 대상을 고른다).
     */
    private fun drainPurges(): ExpiredAuthArtifactPurgeResult {
        val createdBefore = Instant.now(clock).minus(policy.retention)
        var emailVerificationCodesDeleted = 0
        var passwordResetCodesDeleted = 0
        var oauthStatesDeleted = 0
        var rounds = 0
        do {
            rounds++
            check(rounds <= MAX_ROUNDS) { "만료 인증 아티팩트 파기 배치가 ${MAX_ROUNDS}회를 넘었다" }
            val batch = oneBatch(createdBefore)
            emailVerificationCodesDeleted += batch.emailVerificationCodesDeleted
            passwordResetCodesDeleted += batch.passwordResetCodesDeleted
            oauthStatesDeleted += batch.oauthStatesDeleted
        } while (
            batch.emailVerificationCodesDeleted >= policy.batchSize ||
            batch.passwordResetCodesDeleted >= policy.batchSize ||
            batch.oauthStatesDeleted >= policy.batchSize
        )
        return ExpiredAuthArtifactPurgeResult(
            enabled = true,
            emailVerificationCodesDeleted = emailVerificationCodesDeleted,
            passwordResetCodesDeleted = passwordResetCodesDeleted,
            oauthStatesDeleted = oauthStatesDeleted,
        )
    }

    private fun inactiveResult(): ExpiredAuthArtifactPurgeResult =
        ExpiredAuthArtifactPurgeResult(
            enabled = false,
            emailVerificationCodesDeleted = 0,
            passwordResetCodesDeleted = 0,
            oauthStatesDeleted = 0,
        )

    private companion object {
        const val MAX_ROUNDS: Int = 10_000
    }
}

/** 표별 건수만 남긴다. 해시·salt·state·nonce는 자리에 없다. */
class LoggingExpiredAuthArtifactPurgeObserver : ExpiredAuthArtifactPurgeObserver {
    private val log = LoggerFactory.getLogger(LoggingExpiredAuthArtifactPurgeObserver::class.java)

    override fun record(result: ExpiredAuthArtifactPurgeResult) {
        log.info(
            "만료 인증 아티팩트 파기: enabled={} emailVerificationCodesDeleted={} " +
                "passwordResetCodesDeleted={} oauthStatesDeleted={}",
            result.enabled,
            result.emailVerificationCodesDeleted,
            result.passwordResetCodesDeleted,
            result.oauthStatesDeleted,
        )
    }
}
