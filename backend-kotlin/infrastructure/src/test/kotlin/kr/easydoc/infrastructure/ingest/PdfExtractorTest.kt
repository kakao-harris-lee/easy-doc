package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.text.hasUnpairedSurrogate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** PDF 추출의 참고값 대조와 재현성 고정. */
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
    @DisplayName("깨진 ToUnicode CMap 의 짝 없는 서로게이트를 PDFBox 가 U+FFFD 로 **치환한다** (2026-08-20 실측)")
    fun `PDF 는 짝 없는 서로게이트를 내지 않는다`() {
        val text = extractor.extract(SurrogatePdf.bytes())

        assertThat(text).isEqualTo(SurrogatePdf.SUBSTITUTED_TEXT)
        assertThat(hasUnpairedSurrogate(text))
            .withFailMessage(
                "PDFBox 가 더는 치환하지 않는다 — 저장 정의역의 **파일 모드 팔이 열렸다**. " +
                    "계약 x-stored-text-domain 의 그 팔과 DC-24 를 다시 판정해야 한다.",
            ).isFalse()
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

        assertThat(text)
            .withFailMessage {
                "추출 결과에 `\\u000D` 가 있다 — `PDFTextStripper.lineSeparator`/`pageEnd` 고정이 풀렸다."
            }.doesNotContain("\r")
    }

    @Test
    @DisplayName("재현성에 걸리는 설정이 상수로 고정돼 있다")
    fun `추출 설정이 고정돼 있다`() {
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
