package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 보존 만료 파기 한 번의 집계. **본문·제목은 들지 않는다** — 식별자와 건수만 감사·메트릭에 남긴다.
 */
class RetentionPurgeResult(
    val dryRun: Boolean,
    val enabled: Boolean,
    val purgedDocuments: Int,
    val purgedConversions: Int,
    val skippedLeased: Int,
    val documentIds: List<UUID>,
) {
    override fun toString(): String =
        "RetentionPurgeResult(enabled=$enabled, dryRun=$dryRun, purgedDocuments=$purgedDocuments, " +
            "purgedConversions=$purgedConversions, skippedLeased=$skippedLeased, ids=${documentIds.size})"
}

/** 만료 문서를 고르고, 정책에 따라 지우거나 세기만 한다. */
interface ExpiredDocumentPurge {
    /**
     * 만료됐고 활성 리스가 없는 문서를 [limit] 건까지 고른다.
     * [dryRun] 이면 지우지 않는다. 변환·마스킹 대응표·작업 행은 문서 삭제 CASCADE 로 함께 사라진다.
     */
    fun purge(
        dryRun: Boolean,
        limit: Int,
    ): RetentionPurgeResult
}

/** 파기 결과를 감사·메트릭으로 남긴다. 본문을 받지 않는다. */
fun interface RetentionPurgeObserver {
    fun record(result: RetentionPurgeResult)
}

/** worker 스케줄이 넘기는 정책. 값은 설정에서 온다. */
class RetentionPurgePolicy(
    val enabled: Boolean,
    val dryRun: Boolean,
    val batchSize: Int,
) {
    init {
        require(batchSize >= 1) { "보존 파기 배치 크기가 1보다 작다" }
    }
}

/** 기본 30일 보존이 끝난 문서·변환·마스킹 대응표를 같은 경계에서 지운다. */
class PurgeExpiredDocuments(
    private val store: ExpiredDocumentPurge,
    private val transaction: TransactionRunner,
    private val observer: RetentionPurgeObserver,
    private val policy: RetentionPurgePolicy,
) {
    fun run(): RetentionPurgeResult {
        val result =
            when {
                !policy.enabled -> inactiveResult()
                policy.dryRun -> oneBatch(dryRun = true)
                else -> drainDeletes()
            }
        return record(result)
    }

    private fun oneBatch(dryRun: Boolean): RetentionPurgeResult =
        transaction.inTransaction {
            store.purge(dryRun = dryRun, limit = policy.batchSize)
        }

    /**
     * 실제 삭제는 배치가 [RetentionPurgePolicy.batchSize] 미만이 될 때까지 트랜잭션을 반복한다.
     * 하루 한 스케줄이 한 배치만 지우면 만료량이 배치를 넘을 때 보존 기한을 지키지 못한다.
     */
    private fun drainDeletes(): RetentionPurgeResult {
        val ids = mutableListOf<UUID>()
        var conversions = 0
        var skipped = 0
        var rounds = 0
        do {
            rounds++
            check(rounds <= MAX_ROUNDS) { "보존 파기 배치가 ${MAX_ROUNDS}회를 넘었다" }
            val batch = oneBatch(dryRun = false)
            ids += batch.documentIds
            conversions += batch.purgedConversions
            skipped = batch.skippedLeased
        } while (batch.purgedDocuments >= policy.batchSize)
        return RetentionPurgeResult(
            dryRun = false,
            enabled = true,
            purgedDocuments = ids.size,
            purgedConversions = conversions,
            skippedLeased = skipped,
            documentIds = ids,
        )
    }

    private fun record(result: RetentionPurgeResult): RetentionPurgeResult {
        observer.record(result)
        return result
    }

    private fun inactiveResult(): RetentionPurgeResult =
        RetentionPurgeResult(
            dryRun = policy.dryRun,
            enabled = false,
            purgedDocuments = 0,
            purgedConversions = 0,
            skippedLeased = 0,
            documentIds = emptyList(),
        )

    private companion object {
        const val MAX_ROUNDS: Int = 10_000
    }
}

/** 식별자와 건수만 남긴다. 본문·제목·예외 메시지는 자리에 없다. */
class LoggingRetentionPurgeObserver : RetentionPurgeObserver {
    private val log = LoggerFactory.getLogger(LoggingRetentionPurgeObserver::class.java)

    override fun record(result: RetentionPurgeResult) {
        log.info(
            "보존 만료 파기: enabled={} dryRun={} purgedDocuments={} purgedConversions={} " +
                "skippedLeased={} documentIds={}",
            result.enabled,
            result.dryRun,
            result.purgedDocuments,
            result.purgedConversions,
            result.skippedLeased,
            result.documentIds,
        )
    }
}
