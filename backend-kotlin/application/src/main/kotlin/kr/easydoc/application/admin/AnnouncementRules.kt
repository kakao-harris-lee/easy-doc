package kr.easydoc.application.admin

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.text.stripControlChars

// 공지 본문 길이·정규화 규칙 — **서비스 층이 판정한다**(계약 `x-request-field-constraints`,
// `InvoiceRequestRules`와 같은 이유). DTO 애너테이션이 아니라 여기서 정규화 후 길이를
// 재고, 위반은 422 **문자열** detail이다.

private const val MAX_ANNOUNCEMENT_BODY_LENGTH = 500

internal const val EMPTY_ANNOUNCEMENT_BODY_MESSAGE = "공지 내용을 입력해 주세요"
internal const val ANNOUNCEMENT_BODY_TOO_LONG_MESSAGE = "공지 내용은 500자 이하여야 합니다"

/** 필수 — 비어 있으면 422, 500자를 넘으면 422이며 **자르지 않고 거절한다**. */
fun requireValidAnnouncementBody(raw: String): String {
    val normalized = stripControlChars(raw).trim()
    if (normalized.isEmpty()) {
        throw InvalidInputException(EMPTY_ANNOUNCEMENT_BODY_MESSAGE)
    }
    if (normalized.codePointCount(0, normalized.length) > MAX_ANNOUNCEMENT_BODY_LENGTH) {
        throw InvalidInputException(ANNOUNCEMENT_BODY_TOO_LONG_MESSAGE)
    }
    return normalized
}
