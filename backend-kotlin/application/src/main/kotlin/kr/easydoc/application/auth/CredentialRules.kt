package kr.easydoc.application.auth

import kr.easydoc.core.exceptions.InvalidInputException

// 가입 입력 규칙 — **서비스 층이 판정한다**(계약 `x-request-field-constraints`, F3 판정).
//
// ## 왜 DTO 애너테이션이 아닌가
//
// 계약이 다섯 요청 필드에 `@Size`·`@NotBlank`·`@Email` 을 금지했다. 두 가지가 걸린다.
//
// 1. **재는 대상이 다르다.** `email` 은 *정규화한 뒤*의 길이를 재는데 스키마 층 제약은
// 원시 값에 걸린다. 앞에 공백이 붙어 원시로는 상한을 넘지만 정규화하면 이하인 입력을
// 스키마 층은 잘못 거절한다.
// 2. **오류 모양이 달라진다.** 스키마 층 실패는 422 **배열** `detail` 이고 문구가 영문이다.
// 이 다섯의 위반은 422 **문자열** `detail`(한국어)이다. 상태 코드가 같아 눈으로는
// 구분되지 않는다.
//
// 그래서 판정이 여기 있고, 위반은 [InvalidInputException] → 422 문자열이다.
//
// ## 문구를 여기 두는 이유
//
// 사용자에게 나가는 바이트를 만드는 곳은 구현이고, 그것이 계약과 같은지는 **계약 파일을
// 직접 읽는 계약 테스트**가 판정한다(OQ-3). 문구를 테스트에도 손으로 적으면 둘이 함께
// 틀려도 통과한다.

/** 정규화 후 이메일 길이 상한. 계약 `x-request-field-constraints.fields[0].limit`. */
private const val MAX_EMAIL_LENGTH = 255

/** 비밀번호 길이 하한. 계약 `fields[1].limit`. 상한은 없다 — argon2 비용은 입력 길이에 좌우되지 않는다. */
private const val MIN_PASSWORD_LENGTH = 8

/** 이메일 위반 문구. **길이 초과와 형식 오류가 같은 문구다.** */
private const val INVALID_EMAIL_MESSAGE = "이메일 형식이 올바르지 않습니다"

/** 비밀번호 하한 위반 문구. 계약 `fields[1].detail`. */
private const val PASSWORD_TOO_SHORT_MESSAGE = "비밀번호는 8자 이상이어야 합니다"

/** 이메일 형식. **ASCII 로 한정한다.** */
private val EMAIL_PATTERN =
    Regex(
        "^[A-Za-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#\$%&'*+/=?^_`{|}~-]+)*" +
            "@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?" +
            "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*" +
            "\\.[A-Za-z]{2,}$",
    )

/** 이메일 정규화 — **앞뒤 공백 제거 + 소문자**. */
fun normalizeEmail(raw: String): String = raw.trim().lowercase()

/** 정규화된 이메일이 상한과 형식을 만족하는지 본다. */
fun requireValidEmail(normalizedEmail: String) {
    val length = normalizedEmail.codePointCount(0, normalizedEmail.length)
    if (length > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matches(normalizedEmail)) {
        // 입력값을 메시지에 넣지 않는다 — 이 문자열이 그대로 응답 detail 이 된다.
        throw InvalidInputException(INVALID_EMAIL_MESSAGE)
    }
}

/** 비밀번호 하한을 본다. **원시 값으로 잰다** — 계약 `fields[1].measured_on: raw`. */
fun requireValidPassword(rawPassword: String) {
    val length = rawPassword.codePointCount(0, rawPassword.length)
    if (length < MIN_PASSWORD_LENGTH) {
        // 길이도 값도 메시지에 넣지 않는다.
        throw InvalidInputException(PASSWORD_TOO_SHORT_MESSAGE)
    }
}
