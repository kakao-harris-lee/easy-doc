package kr.easydoc.application.auth

import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.user.User
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * 이메일 코드로 비밀번호를 재설정하는 유스케이스 — backlog §1.4 후속.
 *
 * `AuthService`·`EmailVerificationService`와 갈라 세운 이유는 그 두 클래스 KDoc과
 * 같다: 겹치는 협력자([UserRepository]·[MailSender])는 있지만 나머지(재설정 코드
 * 저장소·토큰 발급 조합)는 이 클래스만의 책임이라 한 서비스에 계속 붙이면 god
 * service가 된다.
 *
 * **인증 없이 호출된다** — 비밀번호를 잊은 사용자는 로그인할 수 없다. 그래서 이
 * 오퍼레이션 둘(`request`·`confirm`) 모두 **존재를 흘리지 않는 것**이 설계의
 * 축이다: `request`는 이메일 존재·쿨다운 여부와 무관하게 항상 조용히 끝나고,
 * `confirm`은 이메일 부재·오답·만료·시도 소진을 전부 같은 401로 묶는다
 * (`AuthService.login`의 "계정 존재를 흘리지 않는다"는 원칙을 인증 없는 경로에도
 * 그대로 적용한다).
 *
 * `LongParameterList`를 억제한다 — `EmailVerificationService`(협력자 6개)와 같은
 * 근거에 `confirm`이 필요로 하는 협력자 둘([PasswordHasher]·[AccessTokens])이
 * 더해졌을 뿐이다. `EmailVerificationService`처럼 `codeTtl`·`resendCooldown`·
 * `maxAttempts` 셋을 값 객체로 묶지 않는 것도 같은 이유다(그 클래스 KDoc).
 */
class PasswordResetService
    @Suppress("LongParameterList")
    constructor(
        private val users: UserRepository,
        private val codes: PasswordResetCodeStore,
        private val mail: MailSender,
        private val passwords: PasswordHasher,
        private val accessTokens: AccessTokens,
        private val transaction: TransactionRunner,
        private val codeTtl: Duration,
        private val resendCooldown: Duration,
        private val maxAttempts: Int,
    ) {
        private val log = LoggerFactory.getLogger(PasswordResetService::class.java)

        /**
         * `POST /auth/password-reset/request`. **항상 조용히 끝난다** — 이메일이 없어도,
         * 쿨다운 안이어도, 메일 발송이 실패해도 예외를 던지지 않는다(전부 202). 계정
         * 존재·재요청 빈도가 응답으로 새지 않는다.
         */
        fun request(email: String) {
            try {
                val user = users.findByEmail(normalizeEmail(email))?.user ?: return
                val code = codes.issue(user.id, codeTtl, resendCooldown)
                mail.send(OutboundMail(EmailAddress.of(user.email), REQUEST_SUBJECT, requestBodyOf(code)))
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
            ) {
                // 쿨다운(RateLimitedException)도 메일 발송 실패도 같게 삼킨다 — 사유를 가리지
                // 않는다. 예외 객체를 넘기지 않는다(타입 이름만) — `EmailVerificationService`와
                // 같은 규약.
                log.warn(
                    "비밀번호 재설정 코드 발급·발송이 실패했다(요청자에게는 노출하지 않는다): 예외={}",
                    failure::class.java.simpleName,
                )
            }
        }

        /**
         * `POST /auth/password-reset/confirm`. 순서: 새 비밀번호 형식 확인(422) → 이메일로
         * 계정 조회 → **코드 확인·소비와 비밀번호 갱신·이메일 인증 완료 표시를 한
         * 트랜잭션으로 묶는다** → 토큰 발급.
         *
         * 이메일 미존재·오답·만료·시도 소진은 전부 [INVALID_RESET_CODE_MESSAGE] 하나로
         * 묶는다 — 사유를 가르면 공격자가 "이 이메일에 계정이 있다"거나 "이 코드가 한때
         * 존재했다"는 정보를 얻는다.
         *
         * **오답·만료 판정(`codes.attempt`가 `false`)은 트랜잭션을 롤백하지 않는다** —
         * 그 판정도 트랜잭션 블록 **안에서** 일어나지만 예외를 던지지 않고 `false`를
         * 그대로 반환해 블록을 정상 종료시킨다. 오답 시도 횟수 증가(`codes.attempt`의
         * 내부 상태 변화)가 커밋돼야 5회 오답 상한이 실제로 작동하기 때문이다 — 블록
         * 안에서 예외를 던지면(트랜잭션이 통째로 롤백되면) 매 오답마다 그 증가도 함께
         * 사라져 상한이 영영 걸리지 않는다. 판정이 끝난 **뒤**(트랜잭션 밖)에 `matched`
         * 값을 보고서야 예외로 옮긴다.
         *
         * **정답인데 이후 갱신이 실패하면** 그 실패 예외가 트랜잭션 블록 밖으로 그대로
         * 전파돼 트랜잭션 전체(코드 소비 포함)가 롤백된다 — 코드가 "소비됐는데 비밀번호는
         * 안 바뀐" 상태가 생기지 않는다. 실패한 재설정 뒤에도 같은 코드로 다시 시도할 수
         * 있다.
         */
        fun confirm(
            email: String,
            code: String,
            newPassword: String,
        ): IssuedAccessToken {
            accessTokens.ensureConfigured()
            requireValidPassword(newPassword)

            // Argon2 해시는 트랜잭션 **전에** 미리 계산한다 — `AuthService.signup`과 같은
            // 순서 감각(잠금·트랜잭션 시간을 짧게 유지한다). 코드가 틀린 경우엔 이 해시가
            // 버려지지만, `PasswordService.set`의 409 갈래와 같은 이유로 감수한다.
            val passwordHash = passwords.hash(newPassword)

            val user =
                users.findByEmail(normalizeEmail(email))?.user
                    ?: throw InvalidCredentialsException(INVALID_RESET_CODE_MESSAGE)

            val matched =
                transaction.inTransaction {
                    val ok = codes.attempt(user.id, code, maxAttempts)
                    if (ok) {
                        users.updatePasswordHash(user.id, passwordHash)
                        users.markEmailVerified(user.id)
                    }
                    ok
                }
            if (!matched) {
                throw InvalidCredentialsException(INVALID_RESET_CODE_MESSAGE)
            }

            val issued = accessTokens.issue(user.id)
            // 커밋 뒤에 보낸다 — best-effort. 실패해도 재설정 자체는 이미 끝났다
            // (`EmailVerificationService.issueFor`와 같은 규약).
            notifyPasswordChanged(user)
            return issued
        }

        private fun notifyPasswordChanged(user: User) {
            try {
                mail.send(OutboundMail(EmailAddress.of(user.email), CHANGED_SUBJECT, CHANGED_BODY))
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: RuntimeException,
            ) {
                log.warn(
                    "비밀번호 변경 알림 메일 발송에 실패했다(비밀번호는 이미 바뀌었다): userId={} 예외={}",
                    user.id,
                    failure::class.java.simpleName,
                )
            }
        }

        private fun requestBodyOf(code: String): String =
            "재설정 코드: $code\n\n이 코드는 발급 시점으로부터 ${codeTtl.toMinutes()}분간 유효합니다."

        companion object {
            const val REQUEST_SUBJECT: String = "[쉬운 글] 비밀번호 재설정 코드"

            /** 계약 `POST /auth/password-reset/confirm` 401 예시 — 사유를 구분하지 않는다. */
            const val INVALID_RESET_CODE_MESSAGE: String = "재설정 코드가 올바르지 않거나 만료되었습니다"

            const val CHANGED_SUBJECT: String = "[쉬운 글] 비밀번호가 바뀌었습니다"

            /** 고정 문구 — 입력값(비밀번호·코드·이메일)을 담지 않는다. */
            private const val CHANGED_BODY: String =
                "방금 이 계정의 비밀번호가 바뀌었습니다. 본인이 한 일이 아니라면 즉시 비밀번호를 다시 재설정해 주세요."
        }
    }
