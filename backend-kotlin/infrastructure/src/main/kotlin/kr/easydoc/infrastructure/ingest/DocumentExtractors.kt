package kr.easydoc.infrastructure.ingest

import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExtractedDocument
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.UnsupportedFormatException

/**
 * 확장자로 형식을 가려 형식별 추출기로 넘기는 **디스패치 한 곳**.
 *
 * 원본: `app/ingest/extractors.py::extract_text` + `_FORMATS`.
 *
 * ## 압축 폭탄 방어가 여기 있는 이유
 *
 * 형식별 파서가 **스스로 압축을 푸는** 경우(POI)에는 파서에 넘기기 전이 유일한 방어선이다.
 * 그리고 방어를 디스패치에 모아 두면 새 zip 계열 형식을 [SourceFormat] 에 더하는 것만으로
 * 방어가 따라온다 — 형식마다 붙이면 하나를 빠뜨리는 날 그 형식만 무방비가 된다.
 *
 * ## 내용 스니핑을 하지 않는다
 *
 * 확장자로만 판별한다(원본과 같다). 확장자를 속인 파일은 파서가 손상으로 거절하거나,
 * zip 이어야 할 자리에 OLE2 가 오면 [Ole2Diagnosis] 가 세 갈래 안내를 낸다.
 */
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
