package kr.easydoc.application.credit

import kr.easydoc.core.security.Secret
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 가입 부여 중복 방지용 이메일 해시 — `HMAC-SHA256(pepper, 정규화된 이메일)`의 16진 64자
 * (가입 크레딧 후속 §7 결정 2). **단순 SHA-256 이 아니다** — 이메일은 사전 공격으로
 * 곧바로 역산되므로, 비밀 [pepper] 를 섞어 pepper 없이는 원문을 추측해도 해시를 맞출 수
 * 없게 한다. [pepper] 는 `EASYDOC_CREDITS_SIGNUP_GRANT_PEPPER` 하나로만 주입된다
 * (`kr.easydoc.infrastructure.credit.CreditsProperties.signupGrantPepper`).
 *
 * **입력은 이미 정규화된 이메일이어야 한다.** 이 클래스는 정규화를 하지 않는다 —
 * [kr.easydoc.application.auth.normalizeEmail] 을 다시 구현하면 두 규칙이 갈릴 수 있으므로
 * (가입 크레딧 후속 §7 결정 4), 호출자([CreditAccountService.grantSignupBonus] 를 부르는
 * `AuthService.signup`·`SocialLoginService.callback`)가 로그인과 같은 정규화 지점을
 * 이미 거친 값을 넘긴다.
 */
class SignupGrantEmailHasher(private val pepper: Secret) {
    /** [normalizedEmail] 의 HMAC-SHA256 을 소문자 16진 64자로 돌려준다. */
    fun hash(normalizedEmail: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(pepper.reveal().toByteArray(Charsets.UTF_8), ALGORITHM))
        val digest = mac.doFinal(normalizedEmail.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
