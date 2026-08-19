package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException

/**
 * PDF 페이지별 텍스트를 뽑아 잇는다.
 *
 * 원본: `app/ingest/extractors.py::_extract_pdf`·`iter_pdf_pages`.
 *
 * ## 추출 설정을 **한 곳에 고정한다** (spike S-7 + 계획 §1.5 지점 3)
 *
 * [PDFTextStripper] 의 기본값에 기대지 않는다. 두 가지가 걸린다.
 *
 * - `sortByPosition` — 기본 `false` 이고 그대로 두어야 한다. `true` 로 켜면 다단 레이아웃
 *   결과가 갈린다(spike 실측). 기본값이라도 **명시**한다: 기본값 의존은 PDFBox 업그레이드
 *   때 조용히 깨진다.
 * - `lineSeparator`·`pageEnd` — 기본값이 **`System.lineSeparator()`** 라 플랫폼 의존이다.
 *   Linux CI 와 다른 OS 개발기가 서로 다른 텍스트를 낸다. 게다가 `stripControlChars` 는
 *   `\u000D`를 지우지 않으므로 그 문자가 저장·응답까지 그대로 간다. `"\n"` 으로 고정한다.
 *
 * ## 메모리 상한 API 가 **없다** (계획 §1.2 Q-3 — 나쁜 소식이다)
 *
 * PDFBox 2.x 의 `MemoryUsageSetting` 은 3.x 에서 제거됐고, 대체물
 * (`StreamCacheCreateFunction`)은 **쓰기·스트림 캐시**만 관장한다. **읽기 측 상한은
 * 라이브러리가 제공하지 않는다.** `Loader.loadPDF` 에는 `InputStream` 오버로드조차 없다
 * (`byte[]`·`File`·`RandomAccessRead`).
 *
 * 따라서 PDF 쪽 OOM 방어는 **전부 앱 책임**이며 실제 방어는 셋뿐이다 —
 * 업로드 바이트 상한(L1) · 추출 길이 상한([ExtractedTextBuilder]) · 동시 추출 제한
 * ([ConcurrencyLimitedTextExtractor]). 페이지 수 상한·추출 시간 상한은 요구가 아니므로
 * 지금 넣지 않고 개선 후보로 등재한다(계획 §1.5 지점 3 ⑷).
 *
 * ## 암호 PDF 를 **미리 거르지 않는다** (계획 §5 D-11)
 *
 * `isEncrypted()` 는 인쇄·복사만 제한한 **소유자 암호** PDF 에도 참이다. 그런 파일은 열람이
 * 자유롭고 공공기관 배포 문서에 흔하다. 미리 막으면 **정상 문서를 거절**한다. PDFBox 는
 * 빈 암호를 자동으로 시도하므로, 진짜로 사용자 암호가 필요한 파일만 [InvalidPasswordException]
 * 으로 걸린다.
 *
 * ## 한계
 *
 * 쪽 경계에서 잘린 문장은 이어 붙지 않는다. 머리글·바닥글·쪽 번호가 본문과 섞여 들어오는
 * PDF 특성상 "문장이 이어지는지"를 신뢰성 있게 판정하기 어려워, 잘못 이으면 오히려 원문을
 * 훼손한다.
 */
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

    /**
     * PDFBox 호출을 감싸 라이브러리 예외를 도메인 예외로 바꾼다.
     *
     * 잡는 범위를 [IOException]·[RuntimeException] 으로 좁힌 것은 원본이 `except Exception`
     * 으로 잡던 자리와 같은 뜻이다 — PDFBox 는 손상 입력에 비검사 예외도 던진다.
     * 나머지(우리 코드 버그)는 500 으로 드러나야지 사용자 입력 탓으로 위장되면 안 된다.
     */
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

    /**
     * 암호 PDF 와 손상 PDF 를 가른다.
     *
     * [InvalidPasswordException] 은 `IOException` 계열이라 위 catch 에 함께 걸린다.
     * **사유를 예외 메시지 문자열로 가르지 않는다** — 그 메시지는 로케일에 따라 번역된다.
     */
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
