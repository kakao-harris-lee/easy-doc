package kr.easydoc.application.document

import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.MAX_UPLOAD_BYTES
import java.util.Locale

// 문서 유스케이스가 사용자에게 내보내는 문구.
//
// 문구를 값으로 두는 이유는 `WorkspaceMessages.kt` 와 같다 — 문구가 **응답 바이트**이고,
// 계약에 예시가 있는 것은 계약이 정본이다. 각 상수에 계약의 자리를 적어 둔다.
//
// **입력값을 담지 않는다.** 파일 이름·본문 조각·작업 공간 식별자 어느 것도 문구에 들어가지
// 않는다. 그 규약이 `GlobalExceptionHandler` 가 예외 메시지를 그대로 응답 `detail` 에
// 실어도 되는 근거다(`DomainExceptions.kt`).

/**
 * 계약 `POST /documents` 403 예시 `email_not_verified` — 이메일 인증 전에는 변환을
 * 요청할 수 없다(backlog §1.4 P0-1/P0-3). **이 오퍼레이션 하나만** 이 문구를 낸다 — 로그인
 * 을 포함한 나머지 오퍼레이션은 미인증 사용자에게도 열려 있다.
 */
const val EMAIL_VERIFICATION_REQUIRED_MESSAGE: String = "이메일 인증 후 문서를 변환할 수 있습니다"

/** 계약 `POST /documents` 422 예시 `empty_body`. 붙여넣기 본문이 공백뿐일 때. */
const val EMPTY_BODY_MESSAGE: String = "본문이 비어 있습니다"

/** 계약 `POST /documents` 422 예시 `too_long`. */
val BODY_TOO_LONG_MESSAGE: String =
    "현재는 ${String.format(Locale.ROOT, "%,d", MAX_CONVERTIBLE_CHARS)}자 이하 문서만 변환할 수 있습니다"

/**
 * 파일에서 뽑은 본문이 공백뿐일 때. 원본 `app/services/documents.py` 의
 * `DocumentExtractionError("문서에서 텍스트를 찾을 수 없습니다")` 와 같은 자리다.
 */
const val NO_TEXT_IN_DOCUMENT_MESSAGE: String = "문서에서 텍스트를 찾을 수 없습니다"

/** 계약 `components/responses/PayloadTooLarge` 예시 `too_large` — **413** 이다. */
val UPLOAD_TOO_LARGE_MESSAGE: String = "파일이 너무 큽니다 (최대 ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB)"

/** multipart 요청에 `file` 파트가 없거나 그 파트가 **파일이 아닐** 때. */
const val MISSING_FILE_PART_MESSAGE: String = "업로드할 파일(file)이 필요합니다"

/** `workspace_id` 가 UUID 형식이 아닐 때 — **두 입력 팔이 같은 문구를 낸다.** 값 자체는 담지 않는다. */
const val INVALID_WORKSPACE_ID_MESSAGE: String = "작업 공간 식별자 형식이 올바르지 않습니다"

/** 계약 `POST /documents` 404 예시 `workspace_not_found`. */
const val WORKSPACE_NOT_FOUND_FOR_DOCUMENT_MESSAGE: String = "작업 공간을 찾을 수 없습니다"

/** 계약 `DELETE /documents/{document_id}` 404 예시 `not_found`. */
const val DOCUMENT_NOT_FOUND_MESSAGE: String = "문서를 찾을 수 없습니다"

/** 계약 `GET /conversions/{conversion_id}` 404 예시 `not_found`. */
const val CONVERSION_NOT_FOUND_MESSAGE: String = "변환 결과를 찾을 수 없습니다"

/** 작업 공간이 **하나도 없다** — 사용자 입력 문제가 아니라 우리 불변식이 깨진 것이다. */
const val NO_WORKSPACE_MESSAGE: String = "요청을 처리하지 못했습니다"

/** 계약 PUT 409 예시 `not_done`. **404·422 아니다.** */
const val CONVERSION_NOT_DONE_MESSAGE: String = "변환이 끝난 뒤에 수정할 수 있습니다"

/** 계약 GET export 409 예시 `not_done`. 검수 저장 409 와 **문구가 다르다.** */
const val EXPORT_NOT_DONE_MESSAGE: String = "변환이 끝난 뒤에 내려받을 수 있습니다"

/** 계약 GET export 409 예시 `missing_placeholders`. */
const val EXPORT_MISSING_PLACEHOLDERS_MESSAGE: String =
    "변환에서 유실된 개인정보 표시가 있습니다 — 검수 화면에서 수정 후 내보내세요"

/**
 * 계약 GET export 409 예시 `format_mismatch` — 요청한 형식이 **이 변환의 원본**과 다르다.
 *
 * **옳은 형식을 문구에 싣지 않는다.** 그 값은 이미 조회 응답의 `export_format` 에 있고,
 * 오류 문구에 또 실으면 「무슨 형식으로 내려받는가」의 정본이 둘이 된다
 * (계약 `x-export-format-derivation` `rationale` ④).
 */
const val EXPORT_FORMAT_MISMATCH_MESSAGE: String = "원본과 같은 형식으로만 내려받을 수 있습니다"

/**
 * 계약 GET export 409 예시 `no_exportable_format` — 같은 형식으로 내보낼 수단도
 * 선택지도 없다(`ExportFormat.ofSource` 와 `ExportFormat.choicesFor` 가 **둘 다** 비었다).
 *
 * **오늘 이 갈래를 밟는 원본은 없다** — PDF는 2.6.0부터 [EXPORT_FORMAT_CHOICE_REQUIRED_MESSAGE]·
 * [EXPORT_FORMAT_CHOICE_MISMATCH_MESSAGE] 갈래로 옮겨 갔다(선택지가 생겼으므로). 문구가
 * 특정 형식을 이름으로 부르지 않는 것은 그래서다 — 이 갈래에 드는 원본이 무엇이 될지
 * 오늘은 알 수 없다. `x-export-format-derivation.choices` 의 정의역 불변식이 깨져
 * `mapping` 이 `null` 인데 `choices` 도 비는 형식이 생기면 그때 이 문구를 쓴다.
 */
const val EXPORT_FORMAT_UNAVAILABLE_MESSAGE: String = "이 문서는 내려받을 수 있는 형식이 없습니다"

/**
 * 계약 GET export 409 예시 `export_format_choice_required` — 선택지가 있는 원본(PDF)에서
 * `format` 을 생략했다.
 *
 * 서버가 대신 고르지 않는다(`enforcement.on_absent_with_choices`) — PDF가 아닌 원본과
 * 달리 생략이 기본 경로가 아니다. 오늘 이 갈래에 드는 선택지는 **PDF 하나뿐**이라
 * 문구가 그 값을 이름으로 부를 수 있고, 그 대응이 깨지는 날은
 * `ConversionFormatContractTest` 가 빨갛다.
 */
const val EXPORT_FORMAT_CHOICE_REQUIRED_MESSAGE: String = "내보낼 형식을 골라주세요 (docx·hwpx)"

/**
 * 계약 GET export 409 예시 `export_format_choice_mismatch` — 요청한 형식이 `ExportFormat`
 * 안이지만 **이 원본의 선택지 밖**이다(예: PDF에 `format=txt`).
 *
 * [EXPORT_FORMAT_MISMATCH_MESSAGE] 와 같은 이유로 **옳은 형식을 문구에 싣지 않는다** — 그
 * 값은 이미 조회 응답의 `export_format_choices` 에 있다.
 */
const val EXPORT_FORMAT_CHOICE_MISMATCH_MESSAGE: String = "고를 수 있는 형식으로만 내려받을 수 있습니다"

/** 계약 PUT 422 예시 `empty`. 제어문자만 담긴 수정본도 여기다. */
const val EMPTY_REVIEW_MESSAGE: String = "수정본이 비어 있습니다"

/** 계약 PUT 422 예시 `too_long`. 길이는 **정규화 후**다. */
val REVIEW_TOO_LONG_MESSAGE: String =
    "수정본은 ${String.format(Locale.ROOT, "%,d", MAX_CONVERTIBLE_CHARS)}자 이하여야 합니다"

/** 잠근 행에 저장이 닿지 않았다 — 입력 문제가 아니라 **우리 전제가 깨진 것**이다. */
const val REVIEW_NOT_SAVED_MESSAGE: String = "요청을 처리하지 못했습니다"

/**
 * 계약 `PUT /conversions/{conversion_id}/feedback` 422 예시 `comment_too_long`.
 * 길이는 **정규화 후**이고 상한의 값은 [MAX_FEEDBACK_COMMENT_LENGTH] 에서 끌어온다
 * (계약 `x-input-limits.max_feedback_comment_length` 와 같은 값).
 *
 * 범위 밖 값(품질 만족도·소요 시간)의 문구는 여기 없다 — 그 둘은 범위와 문구를 함께
 * `core/pilot/ConversionFeedback.kt` 가 든다(`QualityScore.OUT_OF_RANGE_MESSAGE`·
 * `MinutesSpent.OUT_OF_RANGE_MESSAGE`). 경계값에서 끌어 쓰는 문구를 경계값에서 떼어 놓으면
 * 두 벌이 되어 갈린다.
 */
val FEEDBACK_COMMENT_TOO_LONG_MESSAGE: String = "자유 의견은 ${MAX_FEEDBACK_COMMENT_LENGTH}자 이하여야 합니다"
