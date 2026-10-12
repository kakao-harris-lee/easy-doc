package kr.easydoc.infrastructure.retention

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.retention.PurgeRetainedTransactionRecords
import kr.easydoc.application.retention.RetainedTransactionPurgePolicy
import kr.easydoc.infrastructure.document.RetentionProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset

/** 법정 원장과 탈퇴 복구 방지 tombstone을 기존 보존 파기 슬롯에서 매일 정리한다. */
@Component
@Profile("worker")
class RetainedTransactionPurgeScheduler(
    private val retainedPurge: PurgeRetainedTransactionRecords,
    private val policy: RetainedTransactionPurgePolicy,
    private val properties: RetentionProperties,
    private val jdbc: JdbcClient,
    private val transaction: TransactionRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${easydoc.retention.cron:0 0 3 * * *}")
    fun run() {
        runStep(RETAINED_STEP) { drainRetainedRecords() }
        runStep(TOMBSTONE_STEP) { purgeTombstones() }
    }

    private fun drainRetainedRecords() {
        var rounds = 0
        while (true) {
            val result = retainedPurge.run()
            rounds++
            if (!result.enabled || result.dryRun || result.deleted < policy.batchSize) return
            check(rounds < MAX_BATCHES) { "보존 원장 파기 배치가 ${MAX_BATCHES}회를 넘었습니다" }
        }
    }

    private fun purgeTombstones() {
        if (!properties.enabled || properties.dryRun) return
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        transaction.inTransaction {
            val accounts =
                jdbc
                    .sql("DELETE FROM account_deletion_tombstones WHERE expires_at < :now")
                    .param("now", now)
                    .update()
            val documents =
                jdbc
                    .sql("DELETE FROM document_deletion_tombstones WHERE expires_at < :now")
                    .param("now", now)
                    .update()
            log.info("탈퇴 tombstone 파기: account={} document={}", accounts, documents)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runStep(
        step: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: RuntimeException) {
            log.error("보존 파기 단계 실패: step={} message={}", step, failure.message)
        }
    }

    private companion object {
        const val MAX_BATCHES = 1_000
        const val RETAINED_STEP = "retained-transactions"
        const val TOMBSTONE_STEP = "deletion-tombstones"
    }
}
