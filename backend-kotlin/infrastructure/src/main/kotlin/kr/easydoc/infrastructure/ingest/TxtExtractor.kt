package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * 업로드된 평문(`.txt`) 디코딩 — **한글 신호 우선**(사용자 결정, 2026-09-02 「한글 신호 우선」).
 *
 * **"유효한 UTF-8 이 아니면 CP949" 는 결정적 규칙이 아니다.** 두 인코딩은 서로 배타적이지
 * 않다 — 반례: 바이트 `C2 A1 C2 A1` 은 유효한 UTF-8(`¡¡`)이면서 동시에 유효한 CP949
 * (`징징`)다. `.txt` 원본 바이트는 저장하지 않으므로(`x-input-limits.txt_encoding_policy`)
 * 여기서 잘못 판정하면 되돌릴 수 없다.
 *
 * 그래서 두 인코딩이 모두 성공하면 **어느 쪽 결과에 한글이 있는지**로 가른다 — 이 제품은
 * 한국 공공기관 문서만 다루므로(같은 사용자 결정) 한글이 나오는 쪽이 실제 인코딩일
 * 가능성이 절대적으로 높다. UTF-8 로도 CP949 로도 유효한데 그 어느 쪽도 한글이 아닌
 * 텍스트(비한국어 라틴 문서가 우연히 CP949로도 읽히는 경우)는 이 제품 범위 밖이고, 그런
 * 경우도 규칙 3에 의해 UTF-8 로 처리된다 — UTF-8 이 이 바이트열을 만든 실제 인코딩일
 * 가능성이 여전히 CP949 보다 높기 때문이다.
 *
 * 판정 순서:
 * 1. UTF-8 BOM(`U+FEFF`)이 있으면 그 자체로 UTF-8 이 확정이다 — 한글 유무와 무관하게
 *    더 시도하지 않는다. BOM 없이 이 순서를 두면 BOM 붙은 비한국어 UTF-8 파일이 우연히
 *    한글을 포함한 CP949 로도 읽힐 때 잘못 CP949 로 샐 수 있다.
 * 2. UTF-8 엄격 디코딩이 성공하고 결과에 한글이 있으면 UTF-8.
 * 3. 그 외 CP949 엄격 디코딩이 성공하고 결과에 한글이 있으면 CP949.
 * 4. 그 외 UTF-8 엄격 디코딩이 성공했으면(순수 ASCII·비한국어 텍스트) UTF-8.
 * 5. 그 외(둘 다 실패, 또는 둘 다 성공했지만 어느 쪽도 한글이 없는 경우는 없음 — 2·3이
 *    이미 그 갈래를 처리한다) 손상 파일과 같은 방식으로 거절한다.
 *
 * 한국 공공기관 문서를 다루는 이 제품의 전제(사용자 결정)가 위 판정의 근거다 — 그 전제가
 * 없는 일반 텍스트 서비스라면 한글 유무로 인코딩을 정하는 것은 근거가 없다.
 *
 * Windows 메모장이 "ANSI"로 저장한 CP949(MS949, EUC-KR 상위 집합) `.txt` 가 한국 공공기관
 * 문서에 흔해 CP949 를 두 번째 인코딩으로 둔다.
 *
 * **엄격 디코딩만 쓴다.** [CodingErrorAction.REPORT] 로 어느 쪽도 못 읽으면 치환 문자(`�`)로
 * 조용히 뭉개지 않고 손상 파일과 같은 방식으로 거절한다 — 그래야 사용자가 실제로 읽을 수
 * 없는 파일과, 우리가 지원하지 않는 세 번째 인코딩을 섞어 부르지 않는다.
 */
internal class TxtExtractor {
    fun extract(data: ByteArray): String {
        val decoded = decode(data) ?: throw broken(data.size)
        val builder = ExtractedTextBuilder(SourceFormat.TXT, data.size)
        builder.add(stripBom(decoded))
        return builder.build()
    }

    /** 판정 순서는 클래스 KDoc의 5단계 그대로다. */
    private fun decode(data: ByteArray): String? {
        val utf8 = strictDecode(Charsets.UTF_8, data)
        // BOM 이나 한글로 이미 확정됐으면(규칙 1·2) CP949 는 아예 시도하지 않는다.
        val utf8Decisive = utf8 != null && (utf8.startsWith(BOM) || containsHangul(utf8))
        val cp949 = if (utf8Decisive) null else strictDecode(CP949, data)
        return when {
            utf8Decisive -> utf8
            cp949 != null && containsHangul(cp949) -> cp949
            else -> utf8
        }
    }

    /**
     * 한글 포함 여부 — 세 블록 중 하나라도 있으면 참이다(Unicode 표준 `Blocks.txt` 기준):
     * 한글 음절(Hangul Syllables, U+AC00–U+D7A3), 한글 자모(Hangul Jamo, U+1100–U+11FF),
     * 한글 호환 자모(Hangul Compatibility Jamo, U+3130–U+318F).
     */
    private fun containsHangul(text: String): Boolean =
        text.codePoints().anyMatch { cp ->
            cp in HANGUL_SYLLABLES || cp in HANGUL_JAMO || cp in HANGUL_COMPAT_JAMO
        }

    /**
     * REPORT 로 설정한 디코더로만 시도한다 — `Charset.decode` 편의 메서드는 항상 REPLACE 라 쓰지 않는다.
     *
     * 실패를 `null` 로 돌려 다음 인코딩을 시도하는 **정상 분기**다 — 두 인코딩 모두 실패했을 때만
     * [decode] 가 그것을 [broken] 으로 예외를 던진다. 그래서 원인을 삼킨다(detekt SwallowedException).
     */
    @Suppress("SwallowedException")
    private fun strictDecode(
        charset: Charset,
        data: ByteArray,
    ): String? {
        val decoder: CharsetDecoder =
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (cause: CharacterCodingException) {
            null
        }
    }

    /** UTF-8 BOM(`U+FEFF`)이 본문 첫 글자로 새지 않게 걷어낸다. */
    private fun stripBom(text: String): String = if (text.startsWith(BOM)) text.substring(1) else text

    private fun broken(uploadSize: Int): DocumentExtractionException {
        ExtractionFailureLog.record(SourceFormat.TXT, uploadSize, "undecodable")
        return DocumentExtractionException(ExtractionMessages.broken(SourceFormat.TXT))
    }

    private companion object {
        /** CP949(MS949) — JDK 표준 charset 이름은 "x-windows-949"(아래 리터럴). EUC-KR 의 상위 집합이다. */
        val CP949: Charset = Charset.forName("x-windows-949")
        const val BOM: Char = '\uFEFF'

        /** Unicode 블록 "Hangul Syllables" — 완성형 한글 음절. [containsHangul] 참고. */
        val HANGUL_SYLLABLES = 0xAC00..0xD7A3

        /** Unicode 블록 "Hangul Jamo" — 조합형 초·중·종성. [containsHangul] 참고. */
        val HANGUL_JAMO = 0x1100..0x11FF

        /** Unicode 블록 "Hangul Compatibility Jamo" — 옛 한글 호환 자모. [containsHangul] 참고. */
        val HANGUL_COMPAT_JAMO = 0x3130..0x318F
    }
}
