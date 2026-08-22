package kr.easydoc.core.document

import kr.easydoc.core.exceptions.StorageException

/**
 * 변환 한 건의 상태. [exposesResult] 는 계약이 규정한 노출 범위 규칙이고 그 대상 목록은
 * `ConversionView.carriesResult` 다. 항목마다 값을 주므로 상태를 더하는 사람이 판정을
 * 건너뛸 수 없다.
 */
enum class ConversionStatus(
    val wireName: String,
    val exposesResult: Boolean,
) {
    /** 접수됐고 아직 워커가 집지 않았다. 업로드가 만드는 유일한 상태다. */
    PENDING("pending", exposesResult = false),

    /** 워커가 집어 변환 중이다. */
    PROCESSING("processing", exposesResult = false),

    /** AI 변환이 끝났다. **검수 여부와는 무관하다** — 그것은 `reviewed_at` 이 말한다. */
    DONE("done", exposesResult = true),

    /** 실패로 확정됐다. 사유는 `failure_code`. 마스킹이 LLM 호출 앞이라 대응표를 들 수 있다. */
    FAILED("failed", exposesResult = false),

    ;

    companion object {
        /** 컬럼 값을 enum 으로 되읽는다. */
        fun ofWireName(value: String): ConversionStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw StorageException(UNKNOWN_STATUS_MESSAGE)

        /** 계약 `InternalError` 의 `storage` 갈래와 같은 문구. */
        const val UNKNOWN_STATUS_MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"
    }
}
