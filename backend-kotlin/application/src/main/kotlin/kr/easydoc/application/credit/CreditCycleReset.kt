package kr.easydoc.application.credit

import kr.easydoc.application.auth.TransactionRunner
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * 크레딧 주기 종료 초기화 한 번의 집계. **워크스페이스 식별자를 담지 않는다** — 건수만
 * 감사·메트릭에 남긴다([UnverifiedAccountPurgeResult] 와 같은 판단,
 * `kr.easydoc.application.auth`).
 */
class CreditCycleResetResult(
    val enabled: Boolean,
    val resetCount: Int,
) {
    override fun toString(): String = "CreditCycleResetResult(enabled=$enabled, resetCount=$resetCount)"
}

/**
 * `workspace_credit_accounts` 저장소 쪽 — 주기가 끝난(`cycle_ends_at <= now`) 계정을 고르고
 * 초기화한다. `cycle_ends_at IS NULL`인 계정(주기 없음)은 절대 건드리지 않는다.
 */
interface CreditCycleReset {
    /**
     * [now] 기준으로 주기가 끝난 계정을 [batchSize] 건까지 초기화한다 — 계정마다 한
     * 트랜잭션 안에서: `balance = allowance`로 다시 채우고(`reserved`는 그대로),
     * `kind = 'cycle_reset'` 거래 1건을 남기며(`balance_delta` = 초기화 전후 잔액 차),
     * `cycle_started_at`을 이전 `cycle_ends_at`으로, `cycle_ends_at`을 그 한 달 뒤로
     * 민다(말일은 그 달의 마지막 날로 clamp — 1/31 → 2/28).
     */
    fun reset(
        now: Instant,
        batchSize: Int,
    ): CreditCycleResetResult
}

/** 초기화 결과를 감사·메트릭으로 남긴다. 워크스페이스 식별자를 받지 않는다. */
fun interface CreditCycleResetObserver {
    fun record(result: CreditCycleResetResult)
}

/** worker 스케줄이 넘기는 정책. 값은 설정(`easydoc.credits.cycle-reset`)에서 온다. */
class CreditCycleResetPolicy(
    val enabled: Boolean,
    val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "크레딧 주기 초기화 배치 크기가 1보다 작다" }
    }
}

/**
 * 주기가 끝난 계정을 매일 초기화한다 — `PurgeUnverifiedAccounts`(`application.auth`)와 같은
 * 배치 흐름이다: 대상량이 배치를 넘으면 배치가 짧아질 때까지 트랜잭션을 반복한다.
 *
 * 크레딧을 「구독 주기에 포함된 이용량」으로 바꾼 사용자 결정(2026-09-10)의 구현 — 이월은
 * 없다. 남은 잔액을 버리고 그 주기의 이용량으로 다시 채운다.
 */
class ResetCreditCycles(
    private val store: CreditCycleReset,
    private val transaction: TransactionRunner,
    private val observer: CreditCycleResetObserver,
    private val policy: CreditCycleResetPolicy,
    private val clock: Clock,
) {
    fun run(): CreditCycleResetResult {
        val result = if (policy.enabled) drainResets() else inactiveResult()
        observer.record(result)
        return result
    }

    private fun oneBatch(now: Instant): CreditCycleResetResult =
        transaction.inTransaction {
            store.reset(now = now, batchSize = policy.batchSize)
        }

    /**
     * `now` 는 이 실행에서 한 번만 계산한다 — 배치를 반복하는 동안 시각이 흘러도 같은
     * 스케줄 실행 안에서는 같은 기준으로 대상을 고른다.
     */
    private fun drainResets(): CreditCycleResetResult {
        val now = Instant.now(clock)
        var total = 0
        var rounds = 0
        do {
            rounds++
            check(rounds <= MAX_ROUNDS) { "크레딧 주기 초기화 배치가 ${MAX_ROUNDS}회를 넘었다" }
            val batch = oneBatch(now)
            total += batch.resetCount
        } while (batch.resetCount >= policy.batchSize)
        return CreditCycleResetResult(enabled = true, resetCount = total)
    }

    private fun inactiveResult(): CreditCycleResetResult = CreditCycleResetResult(enabled = false, resetCount = 0)

    private companion object {
        const val MAX_ROUNDS: Int = 10_000
    }
}

/** 건수만 남긴다. 워크스페이스 식별자는 자리에 없다. */
class LoggingCreditCycleResetObserver : CreditCycleResetObserver {
    private val log = LoggerFactory.getLogger(LoggingCreditCycleResetObserver::class.java)

    override fun record(result: CreditCycleResetResult) {
        log.info("크레딧 주기 초기화: enabled={} resetCount={}", result.enabled, result.resetCount)
    }
}
