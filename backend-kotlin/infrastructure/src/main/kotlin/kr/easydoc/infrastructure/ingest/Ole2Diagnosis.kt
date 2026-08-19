package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import java.nio.charset.StandardCharsets

/**
 * zip 이어야 할 자리에 **OLE2 복합 문서**가 온 이유를 가려낸다 (계획 §5 D-12).
 *
 * 원본: `app/ingest/extractors.py::_diagnose_ole2`.
 *
 * 두 가지가 섞여 들어온다 — 암호가 걸린 OOXML(본문이 `EncryptedPackage` 스트림으로
 * 들어간다)과, 구버전 `.doc` 를 확장자만 바꿔 올린 경우(`WordDocument` 스트림).
 * **안내가 같으면 후자의 사용자는 있지도 않은 암호를 찾아 헤맨다.** 계약
 * `x-input-limits.legacy_doc_policy` 가 후자에 전용 문구를 요구하는 이유가 그것이다.
 *
 * ## 왜 라이브러리를 들이지 않는가
 *
 * OLE2 디렉터리는 스트림 이름을 **UTF-16LE** 로 저장하므로, 판정에 필요한 정보가 매직
 * 4바이트와 부분 문자열 두 개뿐이다. olefile 대응 라이브러리(POI 의 `POIFSFileSystem`)를
 * 열면 그 자체가 신뢰할 수 없는 입력을 파싱하는 새 표면이 되고, 얻는 것은 이 세 갈래
 * 판정뿐이다. 둘 다 못 찾으면 **단정하지 않고** 두 가능성을 함께 안내한다.
 */
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

    /**
     * 부분 바이트열 검색.
     *
     * 정규식·문자열 변환을 쓰지 않는다 — 임의 바이트를 문자열로 만들면 인코딩에 따라
     * 손실이 생기고, 그 손실이 판정을 뒤집는다.
     */
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
