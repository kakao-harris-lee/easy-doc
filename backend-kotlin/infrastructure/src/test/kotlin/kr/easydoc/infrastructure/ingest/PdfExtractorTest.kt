package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * PDF 추출의 참고값 대조와 **재현성 고정**.
 *
 * 재현성이 이 클래스의 핵심이다 — `PDFTextStripper` 의 줄 구분자 기본값이
 * `System.lineSeparator()` 라, 고정하지 않으면 Linux CI 와 다른 OS 개발기가 **서로 다른
 * 추출 텍스트**를 낸다. 그 갈림은 값 비교 테스트가 아니라 **문자 존재 단언**으로만 잡힌다.
 */
class PdfExtractorTest {
    private val extractor = PdfExtractor()

    @Test
    @DisplayName("페이지별 텍스트를 이어 붙인다")
    fun `본문이 참고값과 같다`() {
        assertThat(extractor.extract(IngestFixtures.bytes("sample.pdf")))
            .isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.pdf"))
    }

    @Test
    @DisplayName("다단 레이아웃 결과가 참고값과 같다 — sortByPosition=false 고정 (spike S-7)")
    fun `다단 레이아웃이 참고값과 같다`() {
        assertThat(extractor.extract(IngestFixtures.bytes("layout.pdf")))
            .isEqualTo(IngestFixtures.expectedText(IngestFixtures.spikeOracle, "layout.pdf"))
    }

    @Test
    @DisplayName("텍스트 레이어가 없으면 전용 문구로 거절한다 (계획 §5 D-10)")
    fun `스캔 PDF 를 거절한다`() {
        assertThatThrownBy { extractor.extract(IngestFixtures.bytes("empty.pdf")) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.PDF_NO_TEXT_LAYER)
    }

    @Test
    @DisplayName("추출 텍스트에 캐리지 리턴이 섞이지 않는다 — 줄 구분자 고정 (계획 §1.5 지점 3)")
    fun `줄 구분자가 플랫폼에 좌우되지 않는다`() {
        val text = extractor.extract(IngestFixtures.bytes("sample.pdf"))

        // 고정이 없으면 Windows 개발기에서 CRLF 가 나오고, `stripControlChars` 는
        // `\u000D` 를 지우지 않으므로 그 문자가 저장·응답까지 그대로 간다.
        assertThat(text)
            .withFailMessage {
                "추출 결과에 `\\u000D` 가 있다 — `PDFTextStripper.lineSeparator`/`pageEnd` 고정이 풀렸다."
            }.doesNotContain("\r")
    }

    @Test
    @DisplayName("재현성에 걸리는 설정이 상수로 고정돼 있다")
    fun `추출 설정이 고정돼 있다`() {
        // 이 단언이 막는 것은 "기본값이 마침 맞아서 통과하는" 상태다. 값을 바꾸면 여기가
        // 먼저 빨개져 그 변경이 리뷰에 드러난다.
        assertThat(PdfExtractor.SORT_BY_POSITION).isFalse()
        assertThat(PdfExtractor.LINE_SEPARATOR).isEqualTo("\n")
        assertThat(PdfExtractor.WORD_SEPARATOR).isEqualTo(" ")
    }

    @Test
    @DisplayName("손상 PDF 는 형식명만 담은 고정 문구로 거절한다")
    fun `손상 PDF 를 거절한다`() {
        val broken = "%PDF-1.7\n이것은 PDF 가 아니다".toByteArray()

        assertThatThrownBy { extractor.extract(broken) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(kr.easydoc.core.document.SourceFormat.PDF))
    }
}
