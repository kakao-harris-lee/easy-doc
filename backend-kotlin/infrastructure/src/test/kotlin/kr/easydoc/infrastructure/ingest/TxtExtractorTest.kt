package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

/** 평문(`.txt`) 디코딩 — UTF-8 우선, CP949 재시도, 둘 다 실패하면 거절. */
class TxtExtractorTest {
    private val extractor = TxtExtractor()

    @Test
    @DisplayName("UTF-8 파일을 그대로 읽는다")
    fun `UTF-8 파일을 읽는다`() {
        val text = extractor.extract("안내문 첫 줄\n둘째 줄".toByteArray(Charsets.UTF_8))

        assertThat(text).isEqualTo("안내문 첫 줄\n둘째 줄")
    }

    @Test
    @DisplayName("UTF-8 로 실패하면 CP949(EUC-KR) 로 재시도한다")
    fun `CP949 파일을 읽는다`() {
        val bytes = "안내문 첫 줄\n둘째 줄".toByteArray(CP949)

        assertThat(extractor.extract(bytes)).isEqualTo("안내문 첫 줄\n둘째 줄")
    }

    @Test
    @DisplayName("UTF-8 도 CP949 도 아닌 바이트는 손상 파일과 같은 방식으로 거절한다")
    fun `둘 다 아니면 거절한다`() {
        val undecodable = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01)

        assertThatThrownBy { extractor.extract(undecodable) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(SourceFormat.TXT))
    }

    @Test
    @DisplayName("UTF-8 BOM 이 본문 첫 글자로 새지 않는다")
    fun `BOM 을 걷어낸다`() {
        val withBom = (BOM + "안내문 본문").toByteArray(Charsets.UTF_8)

        val text = extractor.extract(withBom)

        assertThat(text).isEqualTo("안내문 본문")
        assertThat(text).doesNotContain(BOM)
    }

    @Test
    @DisplayName("빈 줄은 세지 않고 줄 끝 공백은 튼다 — 다른 추출기와 같은 정규화를 지난다")
    fun `다른 추출기와 같은 정규화를 지난다`() {
        val text = extractor.extract("  첫 줄  \n\n  \n둘째 줄\n".toByteArray(Charsets.UTF_8))

        assertThat(text).isEqualTo("첫 줄\n둘째 줄")
    }

    @Test
    @DisplayName("추출 결과 상한을 그대로 지난다 — 다른 형식과 같은 상한이다")
    fun `추출 상한을 지난다`() {
        val oversized = "가".repeat(MAX_EXTRACTED_CHARS + 1).toByteArray(Charsets.UTF_8)

        assertThatThrownBy { extractor.extract(oversized) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.EXTRACTED_TOO_LONG)
    }

    private companion object {
        val CP949: Charset = Charset.forName("x-windows-949")
        const val BOM: String = "\uFEFF"
    }
}
