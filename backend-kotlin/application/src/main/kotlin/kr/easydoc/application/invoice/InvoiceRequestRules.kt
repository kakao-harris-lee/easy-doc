package kr.easydoc.application.invoice

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.text.stripControlChars
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// 세금계산서 요청 필드의 길이·형식 규칙 — **서비스 층이 판정한다**(계약
// `x-request-field-constraints`, `WorkspaceNameRules`와 같은 이유). DTO 애너테이션이 아니라
// 여기서 정규화 후 길이를 재고, 위반은 422 **문자열** detail이다.

private const val MAX_COMPANY_NAME_LENGTH = 100
private const val MAX_REPRESENTATIVE_NAME_LENGTH = 50
private const val MAX_ADDRESS_LENGTH = 200
private const val MAX_OPERATOR_NOTE_LENGTH = 500
private const val MAX_PERIOD_RANGE_DAYS = 366L

internal const val EMPTY_COMPANY_NAME_MESSAGE = "상호를 입력해 주세요"
internal const val COMPANY_NAME_TOO_LONG_MESSAGE = "상호는 100자 이하여야 합니다"
internal const val REPRESENTATIVE_NAME_TOO_LONG_MESSAGE = "대표자명은 50자 이하여야 합니다"
internal const val ADDRESS_TOO_LONG_MESSAGE = "주소는 200자 이하여야 합니다"
internal const val OPERATOR_NOTE_TOO_LONG_MESSAGE = "운영자 메모는 500자 이하여야 합니다"
internal const val MALFORMED_PERIOD_DATE_MESSAGE = "period_from·period_to는 YYYY-MM-DD 형식이어야 합니다"
internal const val PERIOD_TO_BEFORE_FROM_MESSAGE = "period_to는 period_from보다 앞일 수 없습니다"
internal const val PERIOD_RANGE_TOO_WIDE_MESSAGE = "조회 기간은 366일을 넘을 수 없습니다"

/** 제어문자 제거 + 앞뒤 공백 제거 — `normalizeWorkspaceName`과 같은 규칙. */
private fun normalizeInvoiceText(raw: String): String = stripControlChars(raw).trim()

/** 상호(필수) — 비어 있으면 422, 100자를 넘으면 422이며 **자르지 않고 거절한다**. */
fun requireValidCompanyName(raw: String): String {
    val normalized = normalizeInvoiceText(raw)
    if (normalized.isEmpty()) {
        throw InvalidInputException(EMPTY_COMPANY_NAME_MESSAGE)
    }
    if (normalized.codePointCount(0, normalized.length) > MAX_COMPANY_NAME_LENGTH) {
        throw InvalidInputException(COMPANY_NAME_TOO_LONG_MESSAGE)
    }
    return normalized
}

/** 정규화 후 비어 있으면 `null`(선택 필드는 공백만 보내도 "안 적음"으로 다룬다). */
private fun normalizeOptionalInvoiceText(raw: String?): String? {
    if (raw == null) return null
    val normalized = normalizeInvoiceText(raw)
    return normalized.ifEmpty { null }
}

/** 대표자명(선택) — 있으면 50자 이하여야 한다. */
fun requireValidRepresentativeName(raw: String?): String? {
    val normalized = normalizeOptionalInvoiceText(raw) ?: return null
    if (normalized.codePointCount(0, normalized.length) > MAX_REPRESENTATIVE_NAME_LENGTH) {
        throw InvalidInputException(REPRESENTATIVE_NAME_TOO_LONG_MESSAGE)
    }
    return normalized
}

/** 주소(선택) — 있으면 200자 이하여야 한다. */
fun requireValidAddress(raw: String?): String? {
    val normalized = normalizeOptionalInvoiceText(raw) ?: return null
    if (normalized.codePointCount(0, normalized.length) > MAX_ADDRESS_LENGTH) {
        throw InvalidInputException(ADDRESS_TOO_LONG_MESSAGE)
    }
    return normalized
}

/**
 * 운영자 메모(선택, `invoice-handle --note`) — 다른 선택 필드(`representative_name`·
 * `address`)와 같은 규칙(제어문자 제거 + 앞뒤 공백 제거)으로 정규화한 뒤 500자 이하인지
 * 잰다. 운영자가 터미널에 붙여넣는 값이라 줄바꿈이 아닌 제어문자가 섞여 들어올 수 있고,
 * 그 값이 그대로 요청자 상태 안내 메일 본문에 실리므로 다른 필드와 같은 이유(정규화하지
 * 않으면 나중에 렌더링·저장 경계에서 깨질 수 있다)로 걷어낸다.
 */
fun requireValidOperatorNote(raw: String?): String? {
    val normalized = normalizeOptionalInvoiceText(raw) ?: return null
    if (normalized.codePointCount(0, normalized.length) > MAX_OPERATOR_NOTE_LENGTH) {
        throw InvalidInputException(OPERATOR_NOTE_TOO_LONG_MESSAGE)
    }
    return normalized
}

/** `DateTimeFormatter.ISO_LOCAL_DATE`는 엄격하다 — 자리 수·구분자·달력상 없는 날짜를 전부 거절한다. */
fun parseInvoicePeriodDate(raw: String): LocalDate =
    try {
        LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: DateTimeParseException) {
        throw InvalidInputException(MALFORMED_PERIOD_DATE_MESSAGE)
    }

/** `period_to`가 `period_from`보다 앞이거나 구간이 366일을 넘으면 422(`UsagePeriodResolver`와 같은 규칙). */
fun requireValidInvoicePeriod(
    periodFrom: LocalDate,
    periodTo: LocalDate,
) {
    if (periodTo.isBefore(periodFrom)) {
        throw InvalidInputException(PERIOD_TO_BEFORE_FROM_MESSAGE)
    }
    val inclusiveDays = ChronoUnit.DAYS.between(periodFrom, periodTo) + 1
    if (inclusiveDays > MAX_PERIOD_RANGE_DAYS) {
        throw InvalidInputException(PERIOD_RANGE_TOO_WIDE_MESSAGE)
    }
}
