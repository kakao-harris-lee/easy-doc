package kr.easydoc.application.illustration

// 그림 카탈로그(ER-15) 유스케이스가 사용자에게 내보내는 문구.
//
// 문구를 값으로 두는 이유는 `DocumentMessages.kt` 와 같다 — 문구가 **응답 바이트**이고,
// 계약에 예시가 있는 것은 계약이 정본이다.

/** 계약 `GET /illustrations` 404 — 기능이 꺼져 있을 때. */
const val ILLUSTRATIONS_NOT_FOUND_MESSAGE: String = "그림 목록을 찾을 수 없습니다"

/**
 * 계약 `GET /illustrations/{asset_id}/image` 404 — 기능 OFF·미존재·미검수를 구분하지 않는
 * **단 하나의 문구**다(존재 은닉, `IllustrationsService` KDoc).
 */
const val ILLUSTRATION_NOT_FOUND_MESSAGE: String = "그림을 찾을 수 없습니다"
