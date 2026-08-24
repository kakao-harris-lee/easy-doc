package kr.easydoc.worker

import kr.easydoc.application.document.PurgeExpiredDocuments
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 보존 만료 문서를 주기적으로 파기한다. 기본 시각은 매일 03:00 이다. */
@Component
class RetentionPurgeScheduler(private val purge: PurgeExpiredDocuments) {
    @Scheduled(cron = "\${easydoc.retention.cron:0 0 3 * * *}")
    fun run() {
        purge.run()
    }
}
