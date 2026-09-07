package kr.easydoc.core.invoice

import kr.easydoc.core.exceptions.InvalidInputException

/** `invoice_requests.status`(V16) 와 같은 값 — 요청됨 · 발급됨 · 거절됨. */
enum class InvoiceRequestStatus(val wireName: String) {
    /** 요청 접수됨 — 아직 운영자가 처리하지 않았다. */
    REQUESTED("requested"),

    /** 운영자가 홈택스에서 수동 발급했다 — `invoice-handle --status=issued`. */
    ISSUED("issued"),

    /** 운영자가 처리할 수 없다고 판단했다 — `invoice-handle --status=rejected`. */
    REJECTED("rejected"),
    ;

    companion object {
        fun ofWireName(value: String): InvoiceRequestStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw InvalidInputException("알 수 없는 세금계산서 요청 상태입니다: $value")
    }
}
