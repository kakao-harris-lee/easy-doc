package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException

/** PDF 페이지별 텍스트를 뽑아 잇는다. */
internal class PdfExtractor {
    fun extract(data: ByteArray): String =
        guarded(data.size) { Loader.loadPDF(data) }.use { opened -> readPages(opened, data.size) }

    private fun readPages(
        document: PDDocument,
        uploadSize: Int,
    ): String {
        val pageCount = document.numberOfPages
        if (pageCount == 0) {
            ExtractionFailureLog.record(SourceFormat.PDF, uploadSize, "no_pages")
            throw DocumentExtractionException(ExtractionMessages.PDF_NO_PAGES)
        }

        val builder = ExtractedTextBuilder(SourceFormat.PDF, uploadSize)
        val stripper = newStripper()
        for (page in 1..pageCount) {
            // 쪽 하나씩 뽑는다 — 전체를 한 문자열로 만들면 대형 자료에서 힙이 두 배가 된다.
            stripper.startPage = page
            stripper.endPage = page
            builder.add(guarded(uploadSize) { stripper.getText(document) })
        }

        val extracted = builder.build()
        if (extracted.isEmpty()) {
            // 텍스트 레이어가 없는 스캔 PDF. 손상과 구분되는 상태이므로 안내를 달리한다.
            ExtractionFailureLog.record(SourceFormat.PDF, uploadSize, "no_text_layer")
            throw DocumentExtractionException(ExtractionMessages.PDF_NO_TEXT_LAYER)
        }
        return extracted
    }

    /** PDFBox 호출을 감싸 라이브러리 예외를 도메인 예외로 바꾼다. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> guarded(
        uploadSize: Int,
        block: () -> T,
    ): T {
        val failure: Throwable =
            try {
                return block()
            } catch (cause: IOException) {
                cause
            } catch (cause: RuntimeException) {
                cause
            }
        throw translate(uploadSize, failure)
    }

    /** 암호 PDF 와 손상 PDF 를 가른다. */
    private fun translate(
        uploadSize: Int,
        cause: Throwable,
    ): DocumentExtractionException =
        if (cause is InvalidPasswordException) {
            ExtractionFailureLog.record(SourceFormat.PDF, uploadSize, "encrypted")
            DocumentExtractionException(ExtractionMessages.ENCRYPTED)
        } else {
            ExtractionFailureLog.recordCause(SourceFormat.PDF, uploadSize, cause)
            DocumentExtractionException(ExtractionMessages.broken(SourceFormat.PDF))
        }

    /** 재현성에 걸리는 설정을 전부 **명시**한 stripper. 기본값에 기대지 않는다. */
    private fun newStripper(): PDFTextStripper {
        val stripper = PDFTextStripper()
        stripper.sortByPosition = SORT_BY_POSITION
        stripper.lineSeparator = LINE_SEPARATOR
        stripper.pageEnd = LINE_SEPARATOR
        stripper.wordSeparator = WORD_SEPARATOR
        stripper.paragraphStart = ""
        stripper.paragraphEnd = ""
        return stripper
    }

    companion object {
        /**
         * **끄고 고정한다.** `true` 로 켜면 다단 PDF 의 줄 순서가 갈린다(spike 실측:
         * `왼쪽 단 첫 줄 오른쪽 단 첫 줄` ↔ `오른쪽 단 첫 줄왼쪽 단 첫 줄`).
         */
        const val SORT_BY_POSITION: Boolean = false

        /** 플랫폼 의존을 없앤다. PDFBox 기본값은 `System.lineSeparator()` 다. */
        const val LINE_SEPARATOR: String = "\n"

        /** PDFBox 기본값과 같지만 명시한다. */
        const val WORD_SEPARATOR: String = " "
    }
}
