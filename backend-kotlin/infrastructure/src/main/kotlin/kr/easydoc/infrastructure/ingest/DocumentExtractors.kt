package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.UnsupportedFormatException

/** 확장자로 형식을 가려 형식별 추출기로 넘기는 **디스패치 한 곳**. */
class DocumentExtractors internal constructor(
    private val docx: DocxExtractor,
    private val pdf: PdfExtractor,
    private val hwpx: HwpxExtractor,
) : DocumentTextExtractor {
    /**
     * 기본 조립. 형식별 추출기는 `internal` 이라 이 모듈 밖에서는 갈아끼울 수 없다 —
     * 파서 구현이 포트 뒤에 남는다는 것이 계획 §3.2 의 의존 방향이다.
     */
    constructor() : this(DocxExtractor(), PdfExtractor(), HwpxExtractor())

    override fun extract(
        filename: String?,
        bytes: ByteArray,
    ): ExtractedDocument {
        val format =
            SourceFormat.ofUploadFilename(filename)
                ?: throw UnsupportedFormatException(ExtractionMessages.UNSUPPORTED_FORMAT)

        if (format.isZipContainer) {
            // zip 이어야 할 자리에 OLE2 가 오면 "손상" 안내보다 원인을 짚어 주는 편이 낫다.
            if (Ole2Diagnosis.looksLikeOle2(bytes)) throw Ole2Diagnosis.rejection(bytes, format)
            ZipBudget.ensureWithinBudget(bytes, format)
        }

        val text =
            when (format) {
                SourceFormat.DOCX -> docx.extract(bytes)

                SourceFormat.PDF -> pdf.extract(bytes)

                SourceFormat.HWPX -> hwpx.extract(bytes)

                // 붙여넣기는 이 경로로 오지 않는다 — `ofUploadFilename` 이 업로드 형식만 돌려준다.
                SourceFormat.TEXT -> error("붙여넣기는 추출기를 지나지 않는다")
            }
        return ExtractedDocument(format, text)
    }
}
