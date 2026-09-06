package kr.easydoc.application.auth

import kr.easydoc.application.document.EMAIL_VERIFICATION_REQUIRED_MESSAGE
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.user.User
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 비밀번호 없는 계정(소셜 로그인 전용)에 비밀번호를 만드는 유스케이스 —
 * `POST /auth/password`(backlog §1.4 후속).
 *
 * **이메일 인증을 요구한다.** 소셜 신원으로 로그인한 상태 자체는 *그 소셜 계정*의
 * 소유를 증명하지만, 네이버처럼 이메일이 검증되지 않은 채로도 계정을 만들 수 있는
 * 제공자가 있다(`emailVerifiedAt == null`) — 그 상태에서 비밀번호를 만들면 아무도
 * 소유를 증명하지 않은 이메일 주소로 "이메일+비밀번호" 로그인이 새로 열린다. 그래서
 * `POST /documents`가 쓰는 것과 **같은 게이트·같은 예외·같은 문구**(`createDocument`
 * 미검증 게이트, `EMAIL_VERIFICATION_REQUIRED_MESSAGE`)를 재사용한다 — 403.
 *
 * 비밀번호를 **바꾸는** 경로가 아니라 처음 **만드는** 경로다 — 이미 있으면
 * [ConflictException]으로 거절하고 `PasswordResetService`(이메일 코드 재설정)를 안내한다.
 */
class PasswordService(
    private val users: UserRepository,
    private val passwords: PasswordHasher,
    private val mail: MailSender,
    private val transaction: TransactionRunner,
) {
    private val log = LoggerFactory.getLogger(PasswordService::class.java)

    /**
     * `POST /auth/password`. 8자 미만이면 422, 이메일 미인증이면 403, 이미 비밀번호가
     * 있으면 409.
     *
     * 갈래마다 다른 판정(계정 소멸·미인증·이미 있음)이 서로 독립인 가드라 `ThrowsCount`를
     * 억제한다 — `SocialLoginService.unlink`와 같은 판단이다.
     */
    @Suppress("ThrowsCount")
    fun set(
        userId: UUID,
        rawPassword: String,
    ) {
        requireValidPassword(rawPassword)

        // Argon2 해시는 행 잠금 **전에** 미리 계산한다 — `AuthService.signup`과 같은 순서
        // 감각이다(잠금을 쥔 채로 비싼 계산을 돌리지 않는다). 403·409 갈래에서는 이
        // 해시가 버려지지만, 그 낭비보다 짧은 잠금 시간이 값어치 있다.
        val passwordHash = passwords.hash(rawPassword)

        val user =
            transaction.inTransaction {
                val locked =
                    users.lockForUpdate(userId)
                        ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
                if (locked.emailVerifiedAt == null) {
                    throw EmailNotVerifiedException(EMAIL_VERIFICATION_REQUIRED_MESSAGE)
                }
                if (locked.hasPassword) {
                    throw ConflictException(ALREADY_HAS_PASSWORD_MESSAGE)
                }
                users.updatePasswordHash(userId, passwordHash)
                locked
            }
        // 커밋 뒤에 보낸다 — best-effort. 실패해도 비밀번호 설정 자체는 이미 끝났다
        // (`EmailVerificationService.issueFor`와 같은 규약).
        notifyPasswordCreated(user)
    }

    private fun notifyPasswordCreated(user: User) {
        try {
            mail.send(OutboundMail(EmailAddress.of(user.email), CREATED_SUBJECT, CREATED_BODY))
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
        ) {
            // 예외 객체를 넘기지 않는다 — 타입 이름만(`EmailVerificationService`와 같은 규약).
            log.warn(
                "비밀번호 생성 알림 메일 발송에 실패했다(비밀번호는 이미 설정됐다): userId={} 예외={}",
                user.id,
                failure::class.java.simpleName,
            )
        }
    }

    companion object {
        /** 계약 `POST /auth/password` 409 예시. */
        const val ALREADY_HAS_PASSWORD_MESSAGE =
            "이미 비밀번호가 있는 계정입니다. 비밀번호를 바꾸려면 재설정을 이용하세요."

        /** 인증된 요청인데 계정이 그 사이 지워진 경우 — `AuthService`·`EmailVerificationService`와 같은 문구. */
        private const val ACCOUNT_GONE_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"

        const val CREATED_SUBJECT: String = "[쉬운 글] 비밀번호가 만들어졌습니다"

        /** 고정 문구 — 입력값(비밀번호·이메일)을 담지 않는다. */
        private const val CREATED_BODY: String =
            "방금 이 계정에 비밀번호가 만들어졌습니다. 본인이 한 일이 아니라면 즉시 비밀번호를 재설정해 주세요."
    }
}
