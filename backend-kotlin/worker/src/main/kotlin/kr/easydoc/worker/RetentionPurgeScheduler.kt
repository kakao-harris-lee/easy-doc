package kr.easydoc.worker

import kr.easydoc.application.auth.PurgeExpiredAuthArtifacts
import kr.easydoc.application.auth.PurgeUnverifiedAccounts
import kr.easydoc.application.credit.PurgeSignupGrantRecords
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.PurgeFeedbackComments
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 문서, 피드백 의견, 미검증 계정, 무료 체험 중복 방지 원장(가입 크레딧·휴대폰 인증 체험)과
 * 인증 아티팩트(이메일 인증·비밀번호 재설정·OAuth state·휴대폰 인증)의 보존기간을 매일
 * 03:00에 적용한다. 각 단계는 독립된 예외 경계에서 실행해 한 단계의 실패가 다른 파기를
 * 막지 않게 한다.
 */
@Component
@Profile(WORKER_PROFILE)
class RetentionPurgeScheduler(
    private val documentPurge: PurgeExpiredDocuments,
    private val feedbackCommentPurge: PurgeFeedbackComments,
    private val unverifiedAccountPurge: PurgeUnverifiedAccounts,
    private val signupGrantRecordPurge: PurgeSignupGrantRecords,
    private val expiredAuthArtifactPurge: PurgeExpiredAuthArtifacts,
) {
    private val log = LoggerFactory.getLogger(RetentionPurgeScheduler::class.java)

    @Scheduled(cron = "\${easydoc.retention.cron:0 0 3 * * *}")
    fun run() {
        runStep(DOCUMENT_STEP) { documentPurge.run() }
        runStep(FEEDBACK_COMMENT_STEP) { feedbackCommentPurge.run() }
        runStep(UNVERIFIED_ACCOUNT_STEP) { unverifiedAccountPurge.run() }
        runStep(SIGNUP_GRANT_RECORD_STEP) { signupGrantRecordPurge.run() }
        runStep(AUTH_EPHEMERAL_STEP) { expiredAuthArtifactPurge.run() }
    }

    /**
     * 단계 하나를 실행한다. 한 단계의 실패가 다음 단계 실행을 막으면 안 된다 — 문서 파기가
     * 죽어도 그날 피드백 의견 파기·미검증 계정 파기는 그대로 돌아야 한다(어느 조합이든
     * 같다). 감사 로그는 각 유스케이스의 observer 가 성공 시 이미 남기므로, 여기서는
     * 실패만 남긴다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runStep(
        step: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: RuntimeException) {
            // 메시지만 남긴다 — Throwable 자체를 넘기지 않는다(`KeyRotationRunner` 와 같은
            // 판단). 예외 객체를 로거에 그대로 주면 스택트레이스가 함께 찍히고, 이 저장소는
            // 그 안에 무엇이 실릴지 이 메서드가 보장할 수 없다.
            log.error("보존 파기 단계 실패: step={} message={}", step, failure.message)
        }
    }

    private companion object {
        const val DOCUMENT_STEP = "document"
        const val FEEDBACK_COMMENT_STEP = "feedback-comment"
        const val UNVERIFIED_ACCOUNT_STEP = "unverified-account"
        const val SIGNUP_GRANT_RECORD_STEP = "signup-grant-record"
        const val AUTH_EPHEMERAL_STEP = "auth-ephemeral"
    }
}
