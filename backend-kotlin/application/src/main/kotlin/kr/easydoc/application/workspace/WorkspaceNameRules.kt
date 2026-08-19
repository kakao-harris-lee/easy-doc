package kr.easydoc.application.workspace

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.text.stripControlChars

// 작업 공간 이름 규칙 — **서비스 층이 판정한다**(계약 `x-request-field-constraints`, F3 판정).
//
// ## 왜 DTO 애너테이션이 아닌가
//
// 계약이 다섯 요청 필드에 `@Size`·`@NotBlank` 를 금지했고 `WorkspaceNameRequest.name` 이
// 그중 하나다. 이 필드는 **양방향으로** 갈렸던 자리라 금지의 근거가 둘이다.
//
// 1. `maxLength: 50` 은 **원시 값**에 걸리므로, 제어문자를 포함한 원시 55자(정규화하면
//    45자)를 잘못 거절한다. 계약이 재라고 한 것은 정규화한 **뒤**의 길이다.
// 2. `minLength: 1` 은 `"   "`(공백만)를 **통과**시키는데 서비스는 거절한다.
//
// 그리고 위반 응답이 422 **배열** + 영문 문구가 되어 계약(422 **문자열** + 한국어)과
// 어긋난다. 상태 코드가 같아 눈으로는 구분되지 않는다.
//
// ## 제어문자를 먼저 걷어내는 이유
//
// 계약이 적었다 — 남겨 두면 docx 내보내기 시점에 터져 사용자가 원인을 알 수 없는 500 을
// 받는다. 그래서 「지우고 나서 잰다」이지 「있으면 거절한다」가 아니다.
//
// ## 상한을 넘는 이름은 자르지 않고 거절한다
//
// 계약: *"사용자가 직접 적은 이름을 말없이 자르면 그 사람이 담은 뜻이 사라진다(문서 제목
// 자동 유도와 다른 자리)."*

/** 정규화 후 이름 길이 상한. 계약 `x-request-field-constraints.fields[4].limit`. */
private const val MAX_WORKSPACE_NAME_LENGTH = 50

/**
 * 빈 이름 문구. 계약 `fields[4].detail` 의 **첫 번째** 값이자
 * `paths./workspaces.post.responses.422.examples.empty` 다.
 */
private const val EMPTY_NAME_MESSAGE = "작업 공간 이름을 입력해 주세요"

/** 상한 초과 문구. 같은 자리의 **두 번째** 값이자 `examples.too_long` 이다. */
private const val NAME_TOO_LONG_MESSAGE = "작업 공간 이름은 50자 이하여야 합니다"

/**
 * 이름 정규화 — **제어문자 제거 + 앞뒤 공백 제거**. 계약 `fields[4].measured_on` 그대로다.
 *
 * 순서가 뒤집히면 안 된다. 공백을 먼저 털면 `" 이름 "` 같은 입력에서 제어문자가
 * 바깥을 막아 공백이 남고, 그 공백이 저장된다.
 */
fun normalizeWorkspaceName(raw: String): String = stripControlChars(raw).trim()

/**
 * 정규화된 이름이 규칙을 만족하는지 본다. 만족하면 그 값을 그대로 돌려준다.
 *
 * **길이를 코드 포인트로 잰다.** `String.length` 는 UTF-16 단위라 보조 평면 문자(이모지 등)를
 * 2로 세는데 `varchar(50)` 은 문자 수를 센다. 두 단위가 갈리면 규칙을 통과한 이름이 DB 에서
 * 잘리거나 거절되고, 그것은 사용자가 원인을 알 수 없는 실패다. 이름에는 이모지가 흔히
 * 들어오므로 이 자리는 이론이 아니다.
 */
fun requireValidWorkspaceName(normalizedName: String): String {
    if (normalizedName.isEmpty()) {
        // 입력값을 메시지에 넣지 않는다 — 이 문자열이 그대로 응답 detail 이 된다.
        throw InvalidInputException(EMPTY_NAME_MESSAGE)
    }
    if (normalizedName.codePointCount(0, normalizedName.length) > MAX_WORKSPACE_NAME_LENGTH) {
        throw InvalidInputException(NAME_TOO_LONG_MESSAGE)
    }
    return normalizedName
}
