package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * 업로드된 평문(`.txt`) 디코딩 — **UTF-8 우선, 실패하면 CP949(EUC-KR)로 재시도한다.**
 *
 * UTF-8 은 자기 검증이 되므로(잘못된 바이트열은 유효한 UTF-8 이 아니다) "유효한 UTF-8 이
 * 아니면 CP949" 는 추측이 아니라 결정적 규칙이다 — 두 인코딩이 같은 바이트열을 각자 다른
 * 그럴듯한 문자열로 읽어 조용히 잘못 판정하는 경우가 실무에서 없다. 한국 공공기관 문서는
 * Windows 메모장이 "ANSI"로 저장한 CP949(MS949, EUC-KR 상위 집합) `.txt` 가 흔해 두 번째
 * 시도로 둔다.
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

    private fun decode(data: ByteArray): String? = strictDecode(Charsets.UTF_8, data) ?: strictDecode(CP949, data)

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
    }
}
