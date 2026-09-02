package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import java.nio.charset.StandardCharsets

/** zip 이어야 할 자리에 **OLE2 복합 문서**가 온 이유를 가려낸다 (계획 §5 D-12). */
internal object Ole2Diagnosis {
    /** OLE2 복합 문서 매직. 암호가 걸린 OOXML 도, 구버전 `.doc`·`.hwp` 도 zip 이 아니라 OLE2 다. */
    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

    private val ENCRYPTED_STREAM = "EncryptedPackage".toByteArray(StandardCharsets.UTF_16LE)
    private val WORD_STREAM = "WordDocument".toByteArray(StandardCharsets.UTF_16LE)

    /**
     * HWP 5.x(OLE2) `FileHeader` 스트림의 서명 — ASCII 문자열 `"HWP Document File"`을
     * 32바이트로(널 패딩) 담는다. [WORD_STREAM]과 달리 스트림 **이름**이 아니라 스트림
     * **내용**의 선두를 찾는다 — 이 서명이 [WORD_STREAM]식 디렉터리 항목 이름보다
     * 형식을 더 구체적으로 특정하기 때문이다.
     *
     * 출처(둘 다 32바이트·같은 서명을 확인):
     * - 한글과컴퓨터 공개 명세 「한글 문서 파일 형식 5.0」 revision 1.3, 4.1절(FileHeader) —
     *   https://cdn.hancom.com/link/docs/한글문서파일형식_5.0_revision1.3.pdf
     * - `pyhwp`(오픈소스 HWP5 파서) `hwp5.filestructure`의
     *   `HWP5_SIGNATURE = b'HWP Document File' + b'\x00' * 15` —
     *   https://github.com/mete0r/pyhwp
     */
    private val HWP5_SIGNATURE = "HWP Document File".toByteArray(StandardCharsets.US_ASCII)

    /** 선두 매직으로 OLE2 인지 본다. */
    fun looksLikeOle2(data: ByteArray): Boolean {
        if (data.size < OLE2_MAGIC.size) return false
        return OLE2_MAGIC.indices.all { data[it] == OLE2_MAGIC[it] }
    }

    /** 네 갈래로 가른 거절 예외를 만든다. 로그에는 사유 코드만 남는다. */
    fun rejection(
        data: ByteArray,
        format: SourceFormat,
    ): DocumentExtractionException {
        val (reason, message) =
            when {
                contains(data, ENCRYPTED_STREAM) -> "encrypted_container" to ExtractionMessages.ENCRYPTED
                contains(data, WORD_STREAM) -> "legacy_ole2_document" to ExtractionMessages.LEGACY_DOC
                contains(data, HWP5_SIGNATURE) -> "legacy_hwp_document" to ExtractionMessages.LEGACY_HWP
                else -> "ole2_container" to ExtractionMessages.UNKNOWN_OLE2
            }
        ExtractionFailureLog.record(format, data.size, reason)
        return DocumentExtractionException(message)
    }

    /** 부분 바이트열 검색. */
    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
        }
    }
}
