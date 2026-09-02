package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 평문(`.txt`) 디코딩 — **한글 신호 우선**(사용자 결정, 2026-09-02). UTF-8 과 CP949 는
 * 서로 배타적이지 않아(반례: `C2 A1 C2 A1` 은 둘 다 유효하다) 둘 다 성공하면 한글을
 * 포함하는 쪽을 고른다. `x-input-limits.txt_encoding_policy` 가 계약 쪽 정본이다.
 */
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
    @DisplayName("UTF-8 과 CP949 가 둘 다 유효한 바이트열은 한글을 포함하는 쪽을 고른다 — CP949 승")
    fun `둘 다 유효하면 한글 쪽이 이긴다`() {
        // C2 A1 C2 A1 은 유효한 UTF-8("¡¡", 한글 없음)이면서 동시에 유효한 CP949("징징", 한글)다.
        // TxtExtractor KDoc 반례와 같은 바이트열이다 — 둘 중 하나를 결정적으로 고를 수 없다는
        // 근거가 바로 이 바이트열이므로, 여기서 한글 쪽(CP949)을 고르는 것이 정책의 핵심이다.
        val dualValid = byteArrayOf(0xC2.toByte(), 0xA1.toByte(), 0xC2.toByte(), 0xA1.toByte())

        assertThat(extractor.extract(dualValid)).isEqualTo("징징")
    }

    @Test
    @DisplayName("UTF-8 한글 텍스트가 CP949 로도 유효해도 UTF-8 이 이긴다 — UTF-8 쪽에 이미 한글이 있다")
    fun `UTF-8 에 한글이 있으면 CP949 를 시도하지 않는다`() {
        // "각각" 의 UTF-8 바이트(EA B0 81 EA B0 81)는 CP949 로도 유효하게 디코딩되지만
        // ("媛곴컖", 한자+한글 뒤섞임) UTF-8 디코딩 결과 자체에 이미 한글이 있으므로
        // CP949 는 아예 시도하지 않고 UTF-8 을 즉시 확정한다.
        val bytes = "각각".toByteArray(Charsets.UTF_8)
        check(strictCp949Decodes(bytes)) { "이 바이트열이 CP949 로도 유효해야 이 테스트가 이중 유효성을 잰다" }

        assertThat(extractor.extract(bytes)).isEqualTo("각각")
    }

    @Test
    @DisplayName("순수 ASCII 는 그대로 UTF-8 로 읽는다 — 한글이 없어도 CP949 로 넘어가지 않는다")
    fun `순수 ASCII 는 UTF-8 로 읽는다`() {
        val text = extractor.extract("Hello, world!\nSecond line.".toByteArray(Charsets.UTF_8))

        assertThat(text).isEqualTo("Hello, world!\nSecond line.")
    }

    @Test
    @DisplayName("한글 없는 UTF-8 라틴 텍스트가 CP949 로는 무효면 UTF-8 로 읽는다")
    fun `한글 없는 UTF-8 라틴 텍스트를 읽는다`() {
        // "À Paris" 의 UTF-8 바이트는 CP949 엄격 디코딩에서 실패한다(사전 프로브로 확인) —
        // 순수 라틴 문자라도 CP949 무효라는 사실 자체가 이 규칙 3 갈래를 잰다.
        val bytes = "À Paris".toByteArray(Charsets.UTF_8)
        check(!strictCp949Decodes(bytes)) { "이 바이트열이 CP949 로 무효해야 이 테스트가 규칙 3 을 잰다" }

        assertThat(extractor.extract(bytes)).isEqualTo("À Paris")
    }

    @Test
    @DisplayName("UTF-8 BOM 은 한글 유무와 무관하게 그 자체로 UTF-8 을 확정한다 — CP949 로 새지 않는다")
    fun `BOM 이 있으면 한글이 없어도 CP949 로 넘어가지 않는다`() {
        // BOM(EF BB BF) + "Hello notice" 의 전체 바이트열은 CP949 로도 유효하게 디코딩되고
        // 그 결과에 한글이 섞여 나온다(사전 프로브로 확인) — BOM 을 한글 규칙보다 먼저
        // 보지 않으면 이 케이스가 잘못 CP949 로 넘어간다. BOM 결정성이 바로 이것을 막는다.
        val withBom = (BOM + "Hello notice").toByteArray(Charsets.UTF_8)
        val cp949Decoded = strictCp949Decode(withBom)
        check(cp949Decoded != null && containsHangul(cp949Decoded)) {
            "이 바이트열이 CP949 로 유효하고 한글을 포함해야 BOM 결정성이 실제로 갈리는 테스트가 된다"
        }

        val text = extractor.extract(withBom)

        assertThat(text).isEqualTo("Hello notice")
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

    /** CP949 성공 여부만 본다 — 픽스처가 실제로 이중 유효/무효인지 확인하는 사전 프로브. */
    private fun strictCp949Decodes(bytes: ByteArray): Boolean = strictCp949Decode(bytes) != null

    /** [TxtExtractor] 와 같은 엄격 디코더로 CP949 디코딩을 시도한다. 실패하면 `null`. */
    @Suppress("SwallowedException")
    private fun strictCp949Decode(bytes: ByteArray): String? {
        val decoder =
            CP949
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (cause: CharacterCodingException) {
            null
        }
    }

    /** [TxtExtractor] 의 한글 판정과 같은 범위 — 사전 프로브가 실제로 갈리는지 확인한다. */
    private fun containsHangul(text: String): Boolean =
        text.codePoints().anyMatch { cp ->
            cp in HANGUL_SYLLABLES || cp in HANGUL_JAMO || cp in HANGUL_COMPAT_JAMO
        }

    private companion object {
        val CP949: Charset = Charset.forName("x-windows-949")
        const val BOM: String = "\uFEFF"

        val HANGUL_SYLLABLES = 0xAC00..0xD7A3
        val HANGUL_JAMO = 0x1100..0x11FF
        val HANGUL_COMPAT_JAMO = 0x3130..0x318F
    }
}
