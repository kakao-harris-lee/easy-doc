package kr.easydoc.core.document

import kr.easydoc.core.exceptions.StorageException

/** 변환 한 건의 상태. */
enum class ConversionStatus(val wireName: String) {
    /** 접수됐고 아직 워커가 집지 않았다. 업로드가 만드는 유일한 상태다. */
    PENDING("pending"),

    /** 워커가 집어 변환 중이다. */
    PROCESSING("processing"),

    /** AI 변환이 끝났다. **검수 여부와는 무관하다** — 그것은 `reviewed_at` 이 말한다. */
    DONE("done"),

    /** 실패로 확정됐다. 사유는 `failure_code` 다. */
    FAILED("failed"),

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
