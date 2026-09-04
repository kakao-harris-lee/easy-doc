package kr.easydoc.worker

import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.PurgeFeedbackComments
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 보존 만료 문서와 피드백 자유 의견을 주기적으로 파기한다. 기본 시각은 매일 03:00 이다.
 *
 * 두 단계를 각각 독립된 예외 경계로 감싼다 — 한쪽이 실패해도 다른 쪽은 그대로 돈다. 문서
 * 파기가 던지면 그날 피드백 의견 파기가 함께 건너뛰는 일이 없어야 하고, 반대도 같다
 * (두 파기는 서로 다른 표를 건드리는 별개의 정책이라 한쪽의 실패가 다른 쪽 결과를 가리면
 * 안 된다).
 */
@Component
class RetentionPurgeScheduler(
    private val documentPurge: PurgeExpiredDocuments,
    private val feedbackCommentPurge: PurgeFeedbackComments,
) {
    private val log = LoggerFactory.getLogger(RetentionPurgeScheduler::class.java)

    @Scheduled(cron = "\${easydoc.retention.cron:0 0 3 * * *}")
    fun run() {
        runStep(DOCUMENT_STEP) { documentPurge.run() }
        runStep(FEEDBACK_COMMENT_STEP) { feedbackCommentPurge.run() }
    }

    /**
     * 단계 하나를 실행한다. 한 단계의 실패가 다음 단계 실행을 막으면 안 된다 — 문서 파기가
     * 죽어도 그날 피드백 의견 파기는 그대로 돌아야 한다(반대도 같다). 감사 로그는 각
     * 유스케이스의 observer 가 성공 시 이미 남기므로, 여기서는 실패만 남긴다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runStep(
        step: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: RuntimeException) {
            log.error("보존 파기 단계 실패: step={}", step, failure)
        }
    }

    private companion object {
        const val DOCUMENT_STEP = "document"
        const val FEEDBACK_COMMENT_STEP = "feedback-comment"
    }
}
