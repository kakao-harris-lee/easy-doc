package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import org.slf4j.LoggerFactory

/**
 * 피드백 자유 의견 파기 한 번의 집계. **의견 내용도 식별자도 들지 않는다** — 건수만
 * 감사·메트릭에 남긴다(`RetentionPurgeResult`가 문서 id를 남기는 것과 다르다 — 이 파기는
 * 행을 지우지 않고 열만 비우므로 뒤에 남는 행을 가리킬 식별자를 굳이 실을 이유가
 * 없다).
 */
class FeedbackCommentPurgeResult(
    val dryRun: Boolean,
    val enabled: Boolean,
    val purgedComments: Int,
) {
    override fun toString(): String =
        "FeedbackCommentPurgeResult(enabled=$enabled, dryRun=$dryRun, purgedComments=$purgedComments)"
}

/**
 * 보존 기간이 지난 피드백 자유 의견의 봉투 세 열(`comment_encrypted`·
 * `encryption_scheme`·`key_version`)을 고르고 `NULL`로 만든다. 척도 숫자(배포 의향·
 * 품질 만족도·소요 시간·수정률 지표)는 건드리지 않는다 — `docs/pilot-runbook.md`
 * 「파일럿 종료 정리」 ⒜ 정책을 매일 배치로 자동화한 것이다.
 */
interface FeedbackCommentPurge {
    /**
     * 의견이 있고 [retentionDays]일보다 오래된 행을 [limit]건까지 고른다.
     * [dryRun]이면 비우지 않는다.
     */
    fun purge(
        dryRun: Boolean,
        limit: Int,
        retentionDays: Int,
    ): FeedbackCommentPurgeResult
}

/** 파기 결과를 감사·메트릭으로 남긴다. 의견 내용을 받지 않는다. */
fun interface FeedbackCommentPurgeObserver {
    fun record(result: FeedbackCommentPurgeResult)
}

/** worker 스케줄이 넘기는 정책. 값은 설정에서 온다(`easydoc.retention`·`easydoc.feedback`). */
class FeedbackCommentPurgePolicy(
    val enabled: Boolean,
    val dryRun: Boolean,
    val batchSize: Int,
    val retentionDays: Int,
) {
    init {
        require(batchSize >= 1) { "피드백 의견 파기 배치 크기가 1보다 작다" }
        require(retentionDays >= 1) { "피드백 의견 보존 일수가 1보다 작다" }
    }
}

/** 보존 기간이 지난 피드백 자유 의견을 비운다. 척도 숫자는 영구히 남는다. */
class PurgeFeedbackComments(
    private val store: FeedbackCommentPurge,
    private val transaction: TransactionRunner,
    private val observer: FeedbackCommentPurgeObserver,
    private val policy: FeedbackCommentPurgePolicy,
) {
    fun run(): FeedbackCommentPurgeResult {
        val result =
            when {
                !policy.enabled -> inactiveResult()
                policy.dryRun -> oneBatch(dryRun = true)
                else -> drainPurges()
            }
        return record(result)
    }

    private fun oneBatch(dryRun: Boolean): FeedbackCommentPurgeResult =
        transaction.inTransaction {
            store.purge(dryRun = dryRun, limit = policy.batchSize, retentionDays = policy.retentionDays)
        }

    /**
     * 실제 비우기는 배치가 [FeedbackCommentPurgePolicy.batchSize] 미만이 될 때까지 트랜잭션을
     * 반복한다 — `PurgeExpiredDocuments.drainDeletes`와 같은 판단이다.
     */
    private fun drainPurges(): FeedbackCommentPurgeResult {
        var purged = 0
        var rounds = 0
        do {
            rounds++
            check(rounds <= MAX_ROUNDS) { "피드백 의견 파기 배치가 ${MAX_ROUNDS}회를 넘었다" }
            val batch = oneBatch(dryRun = false)
            purged += batch.purgedComments
        } while (batch.purgedComments >= policy.batchSize)
        return FeedbackCommentPurgeResult(dryRun = false, enabled = true, purgedComments = purged)
    }

    private fun record(result: FeedbackCommentPurgeResult): FeedbackCommentPurgeResult {
        observer.record(result)
        return result
    }

    private fun inactiveResult(): FeedbackCommentPurgeResult =
        FeedbackCommentPurgeResult(dryRun = policy.dryRun, enabled = false, purgedComments = 0)

    private companion object {
        const val MAX_ROUNDS: Int = 10_000
    }
}

/** 건수만 남긴다. 의견 내용·식별자는 자리에 없다. */
class LoggingFeedbackCommentPurgeObserver : FeedbackCommentPurgeObserver {
    private val log = LoggerFactory.getLogger(LoggingFeedbackCommentPurgeObserver::class.java)

    override fun record(result: FeedbackCommentPurgeResult) {
        log.info(
            "피드백 의견 파기: enabled={} dryRun={} purgedComments={}",
            result.enabled,
            result.dryRun,
            result.purgedComments,
        )
    }
}
