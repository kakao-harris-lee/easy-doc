package kr.easydoc.core.document

// 문서 도메인의 **수치 상한**과 그 상한이 재는 단위.
//
// 값의 정본은 계약(`contracts/easy-doc-v1.yaml` `x-input-limits`)이고 여기는 그 값을 코드가
// 쓸 수 있게 옮겨 놓은 자리다. **키 경로를 함께 적는다** — 계약과 갈리면 어디를 봐야 하는지가
// 코드에 남아야 한다(계약 값을 코드에 옮겨 적은 자리가 갈렸던 실측이 이 저장소에 있다).
//
// 추출기 쪽 상한(`MAX_EXTRACTED_CHARS`·zip 예산)은 `infrastructure/ingest/ExtractionLimits.kt`
// 에 있다. 두 묶음을 합치지 않는 이유: 저쪽은 **파서를 지키는 방어선**이고 이쪽은
// **변환이 성공할 수 있는 범위**라 기준이 다르다(원본 `app/services/documents.py` 의
// `MAX_CONVERTIBLE_CHARS` 주석이 같은 구분을 적었다).

/** 한 번에 변환할 수 있는 문서 길이. 계약 `x-input-limits.max_convertible_chars`. */
const val MAX_CONVERTIBLE_CHARS: Int = 20_000

/** 업로드 파일 크기 상한(바이트). 계약 `x-input-limits.max_upload_bytes`. */
const val MAX_UPLOAD_BYTES: Long = 10L * 1024 * 1024

/**
 * 제목 컬럼 상한. `documents.title` 이 `character varying(255)` 다.
 * 계약 `x-input-limits.max_title_length` — **자르고 거절하지 않는다.**
 */
const val MAX_TITLE_LENGTH: Int = 255

/** 계약이 말하는 "문자 수" — **코드 포인트 수**다. */
fun charCountOf(text: String): Int = text.codePointCount(0, text.length)

/** 앞에서부터 [count] **코드 포인트**만 남긴다. 짧으면 그대로 돌려준다. */
fun takeCodePoints(
    text: String,
    count: Int,
): String {
    // 코드 단위 수가 상한 이하면 코드 포인트 수는 반드시 그 이하다 — 셀 필요가 없다.
    if (text.length <= count) return text
    val available = text.codePointCount(0, text.length)
    return if (available <= count) text else text.substring(0, text.offsetByCodePoints(0, count))
}
