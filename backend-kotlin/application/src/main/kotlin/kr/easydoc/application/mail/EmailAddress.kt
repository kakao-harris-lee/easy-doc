package kr.easydoc.application.mail

import kr.easydoc.application.auth.normalizeEmail
import kr.easydoc.application.auth.requireValidEmail
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.privacy.UserContent

/**
 * 메일 수신자 주소. 검증·정규화는 가입 이메일과 **같은 규칙**을 쓴다
 * ([kr.easydoc.application.auth.normalizeEmail], [kr.easydoc.application.auth.requireValidEmail]) —
 * 규칙이 둘로 갈리면 가입은 받아 준 주소를 알림 발송이 거절하는 불일치가 생긴다.
 *
 * `@UserContent` — 필드 이름(`value`)만으로는 민감 판정 토큰에 걸리지 않는다.
 * `SensitiveToStringReachTest` 가 이 타입을 놓치지 않도록 명시로 넓힌다.
 */
@UserContent
class EmailAddress private constructor(val value: String) {
    /** **값을 찍지 않는다.** */
    override fun toString(): String = "EmailAddress($CONTENT_MASK)"

    override fun equals(other: Any?): Boolean = other is EmailAddress && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** 정규화 후 형식을 검증한다. 위반은 [kr.easydoc.core.exceptions.InvalidInputException]. */
        fun of(raw: String): EmailAddress {
            val normalized = normalizeEmail(raw)
            requireValidEmail(normalized)
            return EmailAddress(normalized)
        }
    }
}
