package kr.easydoc.core.document

import kr.easydoc.core.exceptions.StorageException

/**
 * 변환 한 건의 상태.
 *
 * 원본: `app/models/conversion.py::ConversionStatus`.
 * 값 집합의 정본은 셋이 **같아야 한다** — 계약 `components/schemas/ConversionStatus`,
 * DB CHECK `ck_conversions_status_valid`(V1 baseline), 그리고 이 enum.
 *
 * ## `wireName` 이 따로 있는 이유
 *
 * [SourceFormat] 과 같다 — 이 값은 `conversions.status` 컬럼과 계약 응답에 **그대로** 나간다.
 * `name.lowercase()` 로 유도하면 enum 이름을 바꾸는 순간 옛 행이 안 읽히는데 그 사실이 아무
 * 데도 안 적힌다. 갈래를 만들어 두고 **이 문자열이 스키마만큼 무겁다**는 것을 여기 적는다.
 */
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
        /**
         * 컬럼 값을 enum 으로 되읽는다.
         *
         * 모르는 값은 [StorageException] 이다 — 사용자 입력 문제가 아니라 **DB 에 우리가
         * 모르는 상태가 들어 있다**는 뜻이고, 그것을 조용히 [PENDING] 같은 값으로 접으면
         * 화면이 거짓을 보여 준다. 값 자체는 비밀이 아니므로 메시지에 넣지 않는 대신
         * (계약 `InternalError` 문구를 그대로 쓴다) 어느 값이었는지는 남기지 않는다 —
         * 응답 `detail` 로 그대로 나가는 문구라 입력·저장값을 담지 않는다는 규약을 따른다.
         */
        fun ofWireName(value: String): ConversionStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw StorageException(UNKNOWN_STATUS_MESSAGE)

        /** 계약 `InternalError` 의 `storage` 갈래와 같은 문구. */
        const val UNKNOWN_STATUS_MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"
    }
}
