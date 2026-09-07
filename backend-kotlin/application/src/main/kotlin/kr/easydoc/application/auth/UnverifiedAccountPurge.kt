package kr.easydoc.application.auth

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 미검증 계정 파기 한 번의 집계. **이메일도 사용자 식별자도 담지 않는다** — 건수만
 * 감사·메트릭에 남긴다(`FeedbackCommentPurgeResult` 와 같은 판단, `application.document`).
 */
class UnverifiedAccountPurgeResult(
    val enabled: Boolean,
    val deleted: Int,
    val skippedWithDocuments: Int,
) {
    override fun toString(): String =
        "UnverifiedAccountPurgeResult(enabled=$enabled, deleted=$deleted, skippedWithDocuments=$skippedWithDocuments)"
}

/**
 * 가입 후 이메일을 검증하지 않은 계정을 고르고, 정책에 따라 지운다
 * (`docs/kotlin-redevelopment-backlog.md` §1.4 ⑵ ⓐ, 2026-09-07 결정). 비밀번호 가입
 * (`AuthService.signup`)과 이메일 미검증 소셜 가입(네이버) 모두가 대상이다 — 두 입구 모두
 * 검증 전 계정이 `ix_users_email`(V1)을 무기한 선점해, 진짜 이메일 소유자가 나중에
 * 가입하면 409를 보는 결함을 같은 배치로 닫는다.
 *
 * 문서를 가진 계정은 대상에서 제외하고 개수만 센다 — 미검증 계정은 문서를 만들 수 없다는
 * 전제(`DocumentService.requireVerifiedEmail`)가 있지만, 이 파기는 그 규칙에 기대지 않는다.
 */
interface UnverifiedAccountPurge {
    /**
     * [createdBefore] 이전에 만들어졌고 아직 이메일을 검증하지 않은 계정을 [batchSize] 건까지
     * 지운다. 계정 삭제는 `workspaces`·`documents`·`user_identities`·`oauth_states`·
     * `email_verification_codes`·`password_reset_codes` 로 FK CASCADE 된다
     * (`V1__initial_schema.sql`, `V6__user_identities.sql`, `V7__email_verification.sql`,
     * `V8__oauth_state_link_user.sql`, `V11__password_reset_codes.sql`).
     */
    fun purge(
        createdBefore: Instant,
        batchSize: Int,
    ): UnverifiedAccountPurgeResult
}

/** 파기 결과를 감사·메트릭으로 남긴다. 이메일·사용자 식별자를 받지 않는다. */
fun interface UnverifiedAccountPurgeObserver {
    fun record(result: UnverifiedAccountPurgeResult)
}

/** worker 스케줄이 넘기는 정책. 값은 설정(`easydoc.auth.unverified-purge`)에서 온다. */
class UnverifiedAccountPurgePolicy(
    val enabled: Boolean,
    val ttl: Duration,
    val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "미검증 계정 파기 배치 크기가 1보다 작다" }
        require(!ttl.isNegative && !ttl.isZero) { "미검증 계정 파기 TTL 이 0 이하다" }
    }
}

/**
 * 기본 24시간 안에 이메일을 검증하지 않은 계정을 지운다. `PurgeFeedbackComments`
 * (`application.document`)와 같은 배치 흐름이다 — 대상량이 배치를 넘으면 배치가 짧아질
 * 때까지 트랜잭션을 반복한다.
 */
class PurgeUnverifiedAccounts(
    private val store: UnverifiedAccountPurge,
    private val transaction: TransactionRunner,
    private val observer: UnverifiedAccountPurgeObserver,
    private val policy: UnverifiedAccountPurgePolicy,
    private val clock: Clock,
) {
    fun run(): UnverifiedAccountPurgeResult {
        val result = if (policy.enabled) drainPurges() else inactiveResult()
        observer.record(result)
        return result
    }

    private fun oneBatch(createdBefore: Instant): UnverifiedAccountPurgeResult =
        transaction.inTransaction {
            store.purge(createdBefore = createdBefore, batchSize = policy.batchSize)
        }

    /**
     * `createdBefore` 는 이 실행에서 한 번만 계산한다 — 배치를 반복하는 동안 시각이 흘러도
     * 같은 스케줄 실행 안에서는 같은 기준으로 대상을 고른다. `skippedWithDocuments` 는
     * 누적하지 않고 마지막 배치 값을 그대로 쓴다 — `PurgeExpiredDocuments.drainDeletes` 의
     * `skippedLeased` 와 같은 판단이다: 매 배치의 카운트 질의가 이미 전체를 다시 세므로
     * 더하면 중복 집계가 된다.
     */
    private fun drainPurges(): UnverifiedAccountPurgeResult {
        val createdBefore = Instant.now(clock).minus(policy.ttl)
        var deleted = 0
        var skipped = 0
        var rounds = 0
        do {
            rounds++
            check(rounds <= MAX_ROUNDS) { "미검증 계정 파기 배치가 ${MAX_ROUNDS}회를 넘었다" }
            val batch = oneBatch(createdBefore)
            deleted += batch.deleted
            skipped = batch.skippedWithDocuments
        } while (batch.deleted >= policy.batchSize)
        return UnverifiedAccountPurgeResult(enabled = true, deleted = deleted, skippedWithDocuments = skipped)
    }

    private fun inactiveResult(): UnverifiedAccountPurgeResult =
        UnverifiedAccountPurgeResult(enabled = false, deleted = 0, skippedWithDocuments = 0)

    private companion object {
        const val MAX_ROUNDS: Int = 10_000
    }
}

/** 건수만 남긴다. 이메일·사용자 식별자는 자리에 없다. */
class LoggingUnverifiedAccountPurgeObserver : UnverifiedAccountPurgeObserver {
    private val log = LoggerFactory.getLogger(LoggingUnverifiedAccountPurgeObserver::class.java)

    override fun record(result: UnverifiedAccountPurgeResult) {
        log.info(
            "미검증 계정 파기: enabled={} deleted={} skippedWithDocuments={}",
            result.enabled,
            result.deleted,
            result.skippedWithDocuments,
        )
    }
}
