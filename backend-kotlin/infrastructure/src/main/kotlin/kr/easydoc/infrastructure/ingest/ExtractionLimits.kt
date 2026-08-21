package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import org.slf4j.LoggerFactory
import java.util.Locale

// 추출 계층이 공유하는 **상한 · 사용자 문구 · 로그 규약**.
//
// 원본: `app/ingest/extractors.py` 의 모듈 상수와 `_log_failure`·`_broken`.

/** 추출 결과 길이 상한(공백 포함 문자 수). 계약 `x-input-limits.max_extracted_chars`. */
const val MAX_EXTRACTED_CHARS: Int = 500_000

/**
 * zip 컨테이너(docx·hwpx)를 풀었을 때 허용하는 **총** 바이트.
 * 계약 `x-input-limits.zip_uncompressed_budget_bytes` (업로드 상한의 5배).
 */
const val ZIP_UNCOMPRESSED_BUDGET_BYTES: Long = 52_428_800L

/** 압축 폭탄 검사의 읽기 단위. */
const val ZIP_READ_CHUNK_BYTES: Int = 64 * 1024

/** 추출 실패의 사용자 문구 — **입력값을 담지 않는다.** */
object ExtractionMessages {
    /** 암호가 걸린 컨테이너(OOXML·PDF 공통). */
    const val ENCRYPTED: String = "암호가 설정된 파일입니다 (암호를 풀고 다시 올려주세요)"

    /** 구버전 `.doc`(OLE2)을 확장자만 바꿔 올린 경우. 계약 `x-input-limits.legacy_doc_policy`. */
    const val LEGACY_DOC: String = "구버전 doc 형식은 지원하지 않습니다 (docx로 다시 저장해 올려주세요)"

    /** OLE2 이긴 한데 어느 쪽인지 단정할 수 없을 때. 두 가능성을 함께 안내한다. */
    const val UNKNOWN_OLE2: String = "암호가 설정되었거나 지원하지 않는 구형식 파일입니다"

    /** 텍스트 레이어가 없는 스캔 PDF. **페이지 0건과 다른 문구다** — 사용자가 취할 조치가 다르다. */
    const val PDF_NO_TEXT_LAYER: String = "텍스트를 추출할 수 없습니다 (스캔 PDF는 지원 예정)"

    /** 페이지가 하나도 없는 PDF. */
    const val PDF_NO_PAGES: String = "페이지가 없는 PDF입니다"

    /** 본문 구역이 없는 hwpx — 패키지가 아니거나 껍데기다. */
    const val HWPX_NO_SECTIONS: String = "hwpx 파일을 읽을 수 없습니다 (본문 구역이 없습니다)"

    /**
     * 지원하지 않는 확장자. 계약 `POST /documents` 422 예시 `unsupported_format` 과 같은 값이며,
     * **형식 목록을 손으로 적지 않고** [SourceFormat.UPLOAD_FORMATS] 에서 유도한다 —
     * 형식을 늘리면 안내가 따라 늘어야 한다.
     */
    val UNSUPPORTED_FORMAT: String =
        "지원 형식: " + SourceFormat.UPLOAD_FORMATS.joinToString(", ") { it.wireName }

    /** 추출 길이 상한 초과. */
    val EXTRACTED_TOO_LONG: String =
        "문서가 너무 깁니다 (최대 ${String.format(Locale.ROOT, "%,d", MAX_EXTRACTED_CHARS)}자)"

    /** 손상 파일. 형식명만 담는다. */
    fun broken(format: SourceFormat): String = "${format.wireName} 파일을 읽을 수 없습니다 (파일이 손상되었습니다)"

    /** 압축 해제량이 예산을 넘었다. */
    fun uncompressedTooLarge(format: SourceFormat): String = "${format.wireName} 파일이 너무 큽니다"
}

/** 추출 실패 **사실만** 남긴다 — 형식명 · 바이트 길이 · 사유 코드. */
object ExtractionFailureLog {
    private val logger = LoggerFactory.getLogger(ExtractionFailureLog::class.java)

    /** 우리가 정한 사유 코드로 남긴다. */
    fun record(
        format: SourceFormat,
        size: Int,
        reason: String,
    ) {
        logger.warn("문서 추출 실패: format={} bytes={} reason={}", format.wireName, size, reason)
    }

    /** 라이브러리 예외를 삼키는 자리 — **타입 이름만** 남긴다. */
    fun recordCause(
        format: SourceFormat,
        size: Int,
        cause: Throwable,
    ) {
        record(format, size, cause.javaClass.simpleName)
    }
}
