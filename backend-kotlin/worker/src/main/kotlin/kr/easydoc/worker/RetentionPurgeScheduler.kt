package kr.easydoc.worker

import kr.easydoc.application.auth.PurgeExpiredAuthArtifacts
import kr.easydoc.application.auth.PurgeUnverifiedAccounts
import kr.easydoc.application.credit.PurgeSignupGrantRecords
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.PurgeFeedbackComments
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 보존 만료 문서, 피드백 자유 의견, 미검증 계정, 가입 크레딧 원장, 만료 인증 아티팩트를
 * 주기적으로 파기한다. 기본 시각은 매일 03:00 이다.
 *
 * 미검증 계정 파기(`docs/kotlin-redevelopment-backlog.md` §1.4 ⑵ ⓐ, 2026-09-07 결정)는
 * 앞의 두 파기와 같은 스케줄에 세 번째 단계로 얹는다 — 가입 후 이메일을 검증하지 않은
 * 계정이 `ix_users_email`(V1)을 무기한 선점하는 문제를 같은 일일 배치로 닫는다.
 *
 * 가입 크레딧 원장(`signup_grant_records`, V20) 파기(로드맵 5-1c, 2026-09-10 사용자 확정
 * 「이메일 해시는 부여 시점 기준 2년이면 충분해」)는 네 번째 단계로 얹는다 — 그 표는
 * `users`에 FK 가 없어 계정 삭제의 부산물로 지워지지 않으므로, 이 배치가 유일한 소거
 * 경로다.
 *
 * 만료 인증 아티팩트(`email_verification_codes`·`password_reset_codes`·`oauth_states`)
 * 파기(`docs/plans/2026-09-10-personal-data-inventory.md` §2.2 확정 결함)는 다섯 번째
 * 단계로 얹는다 — 앞의 두 표는 `users` FK CASCADE 로만 사라져 10분이면 만료되는 코드가
 * 계정이 사는 동안 계속 쌓이고, `oauth_states`는 `user_id`가 NULL인 행(가입·로그인 전
 * 흐름)은 CASCADE 경로가 아예 없어 영구 잔존·단조 증가한다 — 이 배치가 세 표 모두의
 * 유일한 소거 경로다.
 *
 * 다섯 단계를 각각 독립된 예외 경계로 감싼다 — 한쪽이 실패해도 다른 쪽은 그대로 돈다. 한
 * 파기가 던지면 그날 다른 파기가 함께 건너뛰는 일이 없어야 한다(서로 다른 표를 건드리는
 * 별개의 정책이라 한쪽의 실패가 다른 쪽 결과를 가리면 안 된다).
 */
@Component
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
