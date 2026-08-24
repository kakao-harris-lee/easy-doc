package kr.easydoc.api.document

import kr.easydoc.core.easyread.ExportFormat
import org.springframework.core.convert.converter.Converter

/**
 * 쿼리 `format` 값을 계약 enum 으로 읽는다. Spring 기본 변환은 상수 이름(`DOCX`)을 보므로
 * 와이어 값(`docx`)을 여기서 연다. [WebMvcConfig] 가 등록한다.
 */
class ExportFormatConverter : Converter<String, ExportFormat> {
    override fun convert(source: String): ExportFormat =
        ExportFormat.ofWireName(source) ?: throw IllegalArgumentException(UNSUPPORTED)

    private companion object {
        const val UNSUPPORTED: String = "unsupported export format"
    }
}
