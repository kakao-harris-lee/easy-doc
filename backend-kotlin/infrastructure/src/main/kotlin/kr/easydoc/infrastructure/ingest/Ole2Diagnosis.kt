package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import java.nio.charset.StandardCharsets

/** zip 이어야 할 자리에 **OLE2 복합 문서**가 온 이유를 가려낸다 (계획 §5 D-12). */
internal object Ole2Diagnosis {
    /** OLE2 복합 문서 매직. 암호가 걸린 OOXML 도, 구버전 `.doc` 도 zip 이 아니라 OLE2 다. */
    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

    private val ENCRYPTED_STREAM = "EncryptedPackage".toByteArray(StandardCharsets.UTF_16LE)
    private val WORD_STREAM = "WordDocument".toByteArray(StandardCharsets.UTF_16LE)

    /** 선두 매직으로 OLE2 인지 본다. */
    fun looksLikeOle2(data: ByteArray): Boolean {
        if (data.size < OLE2_MAGIC.size) return false
        return OLE2_MAGIC.indices.all { data[it] == OLE2_MAGIC[it] }
    }

    /** 세 갈래로 가른 거절 예외를 만든다. 로그에는 사유 코드만 남는다. */
    fun rejection(
        data: ByteArray,
        format: SourceFormat,
    ): DocumentExtractionException {
        val (reason, message) =
            when {
                contains(data, ENCRYPTED_STREAM) -> "encrypted_container" to ExtractionMessages.ENCRYPTED
                contains(data, WORD_STREAM) -> "legacy_ole2_document" to ExtractionMessages.LEGACY_DOC
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
