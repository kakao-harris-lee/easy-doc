package kr.easydoc.application.auth

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidVerificationCodeException
import kr.easydoc.core.user.User
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * 이메일 인증 유스케이스 — 가입 직후 자동 발급 · 재발송 요청 · 확인.
 *
 * `AuthService` 와 갈라 세운 이유는 `SocialLoginService` KDoc 과 같다: 겹치는 협력자
 * ([UserRepository]) 는 있지만 나머지(코드 저장소·메일 발송·쿨다운 판정)는 이 클래스만의
 * 책임이라 한 서비스에 계속 붙이면 god service 가 된다.
 *
 * 생성자 매개변수 수는 협력자의 수다 — `PasswordResetService` 와 같은 근거로 억제한다.
 */
@Suppress("LongParameterList")
class EmailVerificationService(
    private val users: UserRepository,
    private val codes: VerificationCodeStore,
    private val mail: MailSender,
    private val transaction: TransactionRunner,
    private val codeTtl: Duration,
    private val resendCooldown: Duration,
    private val maxAttempts: Int,
) : PostSignupEmailVerification {
    private val log = LoggerFactory.getLogger(EmailVerificationService::class.java)

    /**
     * 가입 직후 호출한다 — **best-effort**. 발송(코드 발급 포함)이 실패해도 예외를 올리지
     * 않는다: 가입 자체는 이미 커밋됐고, 사용자는 `requestVerification` 으로 다시 받을 수
     * 있다(위임 지침 "a send failure does not fail signup").
     */
    override fun issueAfterSignup(userId: UUID) {
        try {
            val user = users.findById(userId) ?: return
            issueFor(user)
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            // 예외 객체를 넘기지 않는다 — `AuthService.rehashIfOutdated` 와 같은 규약.
            log.warn(
                "가입 후 이메일 인증 코드 발송에 실패했다(가입은 계속한다): userId={} 예외={}",
                userId,
                failure::class.java.simpleName,
            )
        }
    }

    /** `POST /auth/email-verification/request`. 이미 인증됐으면 409, 쿨다운 안이면 429. */
    fun requestVerification(userId: UUID) {
        val user = requireUnverified(userId)
        issueFor(user)
    }

    /**
     * `POST /auth/email-verification/confirm`.
     *
     * 미검증 계정 TTL 파기 배치(`PurgeUnverifiedAccounts`)와 경합한다 — 확인 자체가
     * 계정을 지우지 못하게 막을 수는 없으니(파기가 먼저 커밋되면 계정은 진짜로 없다),
     * **행을 먼저 잠근다**(`PasswordService.set`과 같은 순서 감각). 잠금 뒤에는 파기의
     * `FOR UPDATE SKIP LOCKED` 후보 선택이 이 행을 건너뛰므로, 코드 검증·`markEmailVerified`
     * 가 도는 동안 파기가 같은 행을 지우지 못한다. [users.markEmailVerified] 가 그래도
     * `false`(영향받은 행 없음)를 돌려주면 — 정상 경로에서는 일어나지 않아야 하지만 —
     * 성공을 돌려주지 않고 계정 소멸과 같은 예외로 던진다(방어 계층).
     *
     * 갈래마다 다른 판정(계정 소멸·이미 인증됨·코드 불일치·방어 계층의 계정 소멸)이
     * 서로 독립인 가드라 `ThrowsCount` 를 억제한다 — `PasswordService.set` 과 같은 판단이다.
     */
    @Suppress("ThrowsCount")
    fun confirm(
        userId: UUID,
        code: String,
    ) {
        transaction.inTransaction {
            val locked = users.lockForUpdate(userId) ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
            if (locked.emailVerifiedAt != null) {
                throw ConflictException(ALREADY_VERIFIED_MESSAGE)
            }
            if (!codes.attempt(locked.id, code, maxAttempts)) {
                throw InvalidVerificationCodeException(INVALID_CODE_MESSAGE)
            }
            if (!users.markEmailVerified(locked.id)) {
                throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
            }
        }
    }

    private fun requireUnverified(userId: UUID): User {
        val user = users.findById(userId) ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
        if (user.emailVerifiedAt != null) {
            throw ConflictException(ALREADY_VERIFIED_MESSAGE)
        }
        return user
    }

    private fun issueFor(user: User) {
        val code = codes.issue(user.id, codeTtl, resendCooldown)
        val outbound = OutboundMail(EmailAddress.of(user.email), SUBJECT, bodyOf(code))
        try {
            mail.send(outbound)
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            log.warn(
                "이메일 인증 코드 메일 발송에 실패했다(코드는 이미 저장됐다 — 재요청으로 회복): userId={} 예외={}",
                user.id,
                failure::class.java.simpleName,
            )
        }
    }

    private fun bodyOf(code: String): String = "인증 코드: $code\n\n이 코드는 발급 시점으로부터 ${codeTtl.toMinutes()}분간 유효합니다."

    private companion object {
        const val SUBJECT: String = "[쉬운 글] 이메일 인증 코드"

        /** 계약 `POST /auth/email-verification/confirm` 400 예시 — 사유를 구분하지 않는다. */
        const val INVALID_CODE_MESSAGE: String = "인증 코드가 올바르지 않거나 만료되었습니다"

        /** 계약 `POST /auth/email-verification/{request,confirm}` 409 예시. */
        const val ALREADY_VERIFIED_MESSAGE: String = "이미 인증된 이메일입니다"

        /** 인증된 요청인데 계정이 그 사이 지워진 경우 — `AuthService` 와 같은 문구로 통일한다. */
        const val ACCOUNT_GONE_MESSAGE: String = "이메일 또는 비밀번호가 올바르지 않습니다"
    }
}
