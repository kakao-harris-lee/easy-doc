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

/** 계약 `POST /documents` 422 예시 `empty_body`. 붙여넣기 본문이 공백뿐일 때. */
const val EMPTY_BODY_MESSAGE: String = "본문이 비어 있습니다"

/**
 * 계약 `POST /documents` 422 예시 `too_long`.
 *
 * 자릿점을 [Locale.ROOT] 로 찍는다 — 실행 로케일에 따라 구분 기호가 갈리면 같은 거절이
 * 사용자마다 다른 문구가 된다(`ExtractionMessages.EXTRACTED_TOO_LONG` 과 같은 규칙).
 */
val BODY_TOO_LONG_MESSAGE: String =
    "현재는 ${String.format(Locale.ROOT, "%,d", MAX_CONVERTIBLE_CHARS)}자 이하 문서만 변환할 수 있습니다" +
        " (긴 문서 분할 변환은 준비 중입니다)"

/**
 * 파일에서 뽑은 본문이 공백뿐일 때. 원본 `app/services/documents.py` 의
 * `DocumentExtractionError("문서에서 텍스트를 찾을 수 없습니다")` 와 같은 자리다.
 *
 * 추출기가 내는 문구(`ExtractionMessages`)와 **다른 자리**다 — 저쪽은 파서가 판정한 실패이고
 * 이쪽은 "파서는 성공했는데 결과가 비었다"이다. 빈 docx·hwpx 가 예외 없이 빈 문자열을
 * 돌려주므로 이 판정이 필요하다.
 */
const val NO_TEXT_IN_DOCUMENT_MESSAGE: String = "문서에서 텍스트를 찾을 수 없습니다"

/**
 * 계약 `components/responses/PayloadTooLarge` 예시 `too_large` — **413** 이다.
 *
 * MB 표기를 손으로 적지 않고 [MAX_UPLOAD_BYTES] 에서 유도한다. 상한을 바꾸면 안내가 따라
 * 바뀌어야 하는데, 두 자리에 적으면 한쪽만 고쳐지는 날이 온다.
 */
val UPLOAD_TOO_LARGE_MESSAGE: String = "파일이 너무 큽니다 (최대 ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB)"

/**
 * 계약 `POST /documents` 404 예시 `workspace_not_found`.
 *
 * `WorkspaceMessages.WORKSPACE_NOT_FOUND_MESSAGE` 와 **같은 문자열**이지만 상수를 공유하지
 * 않는다 — 두 계약 조항이 각자의 자리에서 이 값을 정하고 있고, 한쪽이 바뀔 때 다른 쪽이
 * 조용히 따라가면 그 변경이 어디까지 미치는지 알 수 없다. 값이 같다는 사실은
 * 계약 테스트가 각각 확인한다.
 */
const val WORKSPACE_NOT_FOUND_FOR_DOCUMENT_MESSAGE: String = "작업 공간을 찾을 수 없습니다"

/**
 * 작업 공간이 **하나도 없다** — 사용자 입력 문제가 아니라 우리 불변식이 깨진 것이다.
 *
 * 가입이 기본 작업 공간을 하나 만들고(`AuthService`) 마지막 하나는 지울 수 없으므로
 * (`WorkspaceService.delete`) 이 상태는 나올 수 없다. 나왔다면 코드·스키마 버그이므로
 * 5xx 로 올린다 — 4xx 로 감싸면 서버 버그가 "사용자가 뭘 잘못했다"로 둔갑해 묻힌다.
 *
 * 계약 `components/responses/InternalError` 의 `storage` 갈래 문구를 쓴다.
 */
const val NO_WORKSPACE_MESSAGE: String = "요청을 처리하지 못했습니다"
