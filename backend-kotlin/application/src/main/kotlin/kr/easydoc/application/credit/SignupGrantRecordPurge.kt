package kr.easydoc.application.credit

import kr.easydoc.application.auth.TransactionRunner
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneOffset

/**
 * 가입 크레딧 중복 방지 원장(`signup_grant_records`, V20) 파기 한 번의 집계.
 * **이메일 해시를 담지 않는다** — 건수만 감사·메트릭에 남긴다(`UnverifiedAccountPurgeResult`
 * 와 같은 판단). `email_hash`는 이메일에 대응하는 가명 식별자라 개인정보 보호법 §21에
 * 따른 파기 기록에도 건수 이상을 남길 이유가 없다.
 */
class SignupGrantRecordPurgeResult(
    val enabled: Boolean,
    val deleted: Int,
) {
    override fun toString(): String = "SignupGrantRecordPurgeResult(enabled=$enabled, deleted=$deleted)"
}

/**
 * `signup_grant_records`(V20) 보유기간이 지난 행을 고르고 지운다(로드맵 5-1c,
 * `docs/plans/2026-09-10-legal-tax-policy-final.md` §6, 개인정보 보호법 §21 — 보유기간이
 * 지나면 지체 없이 파기한다).
 *
 * 이 표는 `users`·`workspaces` 에 FK 가 없다(V20 머리주석 — 탈퇴해도 「이 이메일이 전에
 * 가입 부여를 받았는가」를 판정해야 하므로 일부러 남는다) — 다른 파기처럼 계정 삭제의
 * 부산물로 함께 지워지지 않는다. 이 파기가 유일한 소거 경로다.
 */
interface SignupGrantRecordPurge {
    /** [grantedBefore] 이전에 부여된(`granted_at < grantedBefore`) 행을 [batchSize] 건까지 지운다. */
    fun purge(
        grantedBefore: Instant,
        batchSize: Int,
    ): SignupGrantRecordPurgeResult
}

/** 파기 결과를 감사·메트릭으로 남긴다. 이메일 해시를 받지 않는다. */
fun interface SignupGrantRecordPurgeObserver {
    fun record(result: SignupGrantRecordPurgeResult)
}

/** worker 스케줄이 넘기는 정책. 값은 설정(`easydoc.credits`)에서 온다. */
class SignupGrantRecordPurgePolicy(
    val enabled: Boolean,
    val ttl: Period,
    val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "가입 크레딧 원장 파기 배치 크기가 1보다 작다" }
        require(!ttl.isNegative && !ttl.isZero) { "가입 크레딧 원장 파기 TTL 이 0 이하다" }
    }
}

/**
 * 부여 시점(`granted_at`) 기준 [SignupGrantRecordPurgePolicy.ttl](기본 2년) 이 지난
 * `signup_grant_records` 행을 지운다. `PurgeUnverifiedAccounts`(`application.auth`)와
 * 같은 배치 흐름이다 — 대상량이 배치를 넘으면 배치가 짧아질 때까지 트랜잭션을 반복한다.
 */
class PurgeSignupGrantRecords(
    private val store: SignupGrantRecordPurge,
    private val transaction: TransactionRunner,
    private val observer: SignupGrantRecordPurgeObserver,
    private val policy: SignupGrantRecordPurgePolicy,
    private val clock: Clock,
) {
    fun run(): SignupGrantRecordPurgeResult {
        val result = if (policy.enabled) drainPurges() else inactiveResult()
        observer.record(result)
        return result
    }

    private fun oneBatch(grantedBefore: Instant): SignupGrantRecordPurgeResult =
        transaction.inTransaction {
            store.purge(grantedBefore = grantedBefore, batchSize = policy.batchSize)
        }

    /**
     * `grantedBefore` 는 이 실행에서 한 번만 계산한다 — `PurgeUnverifiedAccounts.drainPurges`
     * 와 같은 이유(배치를 반복하는 동안 시각이 흘러도 같은 실행 안에서는 같은 기준으로
     * 대상을 고른다). UTC 고정 오프셋으로 [Period] 를 뺀다 — 사용자 노출 "한 달"
     * (`CreditAccountService.grantSignupBonus`)과 달리 이 계산은 사용자에게 보이지 않는
     * 순수 보존기간 판정이라 시간대(KST) 변환이 필요 없다.
     */
    private fun drainPurges(): SignupGrantRecordPurgeResult {
        val grantedBefore =
            OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC).minus(policy.ttl).toInstant()
        var deleted = 0
        var rounds = 0
        do {
            rounds++
            check(rounds <= MAX_ROUNDS) { "가입 크레딧 원장 파기 배치가 ${MAX_ROUNDS}회를 넘었다" }
            val batch = oneBatch(grantedBefore)
            deleted += batch.deleted
        } while (batch.deleted >= policy.batchSize)
        return SignupGrantRecordPurgeResult(enabled = true, deleted = deleted)
    }

    private fun inactiveResult(): SignupGrantRecordPurgeResult =
        SignupGrantRecordPurgeResult(enabled = false, deleted = 0)

    private companion object {
        const val MAX_ROUNDS: Int = 10_000
    }
}

/** 건수만 남긴다. 이메일 해시는 자리에 없다. */
class LoggingSignupGrantRecordPurgeObserver : SignupGrantRecordPurgeObserver {
    private val log = LoggerFactory.getLogger(LoggingSignupGrantRecordPurgeObserver::class.java)

    override fun record(result: SignupGrantRecordPurgeResult) {
        log.info("가입 크레딧 원장 파기: enabled={} deleted={}", result.enabled, result.deleted)
    }
}
