package kr.easydoc.application.auth

import java.time.Duration
import java.util.UUID

/**
 * `AuthService.signup` 이 커밋 뒤에 필요로 하는 것 딱 하나 — 인증 코드 발급·발송.
 *
 * `EmailVerificationService` 전체(재발송 요청·확인까지)를 의존하지 않고 이 좁은 인터페이스만
 * 쓴다 — `AuthServiceTest` 가 `VerificationCodeStore`·`MailSender` 없이도 "가입이 발급을
 * 부르는가"만 대역으로 잴 수 있게 한다(포트를 쓰는 쪽이 필요한 만큼만 안다 — 인터페이스
 * 분리 원칙, `PasswordHasher`·`AccessTokens` 와 같은 결의 경계).
 */
fun interface PostSignupEmailVerification {
    fun issueAfterSignup(userId: UUID)
}

/**
 * 이메일 인증 코드 저장소 — 발급 · 확인. 스키마는 `V7__email_verification.sql`
 * (`email_verification_codes`).
 *
 * 해싱·salt·쿨다운 판정 같은 구현 세부는 이 포트가 알지 못한다(`AuthPorts.kt` 의
 * `PasswordHasher` 와 같은 경계) — 그 결정은 `infrastructure` 어댑터
 * (`JdbcVerificationCodeStore`) 의 몫이다.
 */
interface VerificationCodeStore {
    /**
     * 새 코드를 발급한다. **활성 코드가 있으면 무효화하고 이번에 만든 것 하나만 남긴다**
     * ("at most one active code"). 직전 발급이 [cooldown] 안이면(성공·실패를 가리지
     * 않는다 — 발급 시각 기준) [kr.easydoc.core.exceptions.RateLimitedException].
     *
     * 평문 코드를 돌려준다 — 저장은 해시로 하지만 발신 메일 본문에는 평문이 실려야 한다.
     */
    fun issue(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): String

    /**
     * 코드를 확인한다. 일치하면 그 코드를 소비 처리하고 `true`. 불일치·만료·활성 코드
     * 없음·시도 횟수 소진은 전부 `false` — 서비스 층이 사유를 가르지 않고 같은 예외로
     * 묶는다([kr.easydoc.core.exceptions.InvalidVerificationCodeException]).
     *
     * 오답을 [maxAttempts] 만큼 기록한다 — 그 상한에 닿으면 이후 정답을 내도 `false`다
     * ("max 5 wrong attempts then the code is void").
     */
    fun attempt(
        userId: UUID,
        code: String,
        maxAttempts: Int,
    ): Boolean
}
