package kr.easydoc.api.admin

import kr.easydoc.core.invoice.InvoiceRequestStatus
import org.springframework.core.convert.converter.Converter

/**
 * 쿼리 `status`(`GET /admin/invoice-requests`) 값을 계약 enum 으로 읽는다. Spring 기본
 * 변환은 상수 이름(`REQUESTED`)을 보므로 와이어 값(`requested`)을 여기서 연다 —
 * `ExportFormatConverter`와 같은 이유. [kr.easydoc.api.config.WebMvcConfig]가 등록한다.
 */
class InvoiceRequestStatusConverter : Converter<String, InvoiceRequestStatus> {
    override fun convert(source: String): InvoiceRequestStatus =
        runCatching { InvoiceRequestStatus.ofWireName(source) }.getOrElse { throw IllegalArgumentException(UNKNOWN) }

    private companion object {
        const val UNKNOWN: String = "unknown invoice request status"
    }
}
