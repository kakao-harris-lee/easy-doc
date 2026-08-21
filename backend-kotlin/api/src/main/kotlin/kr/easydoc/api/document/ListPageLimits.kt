package kr.easydoc.api.document

// `GET /documents` 의 페이지 파라미터 경계 — **계약 `x-input-limits.list_limit`·`list_offset`**.
//
// ## 왜 값이 코드에 복제되는가
//
// 이 저장소의 규율은 「계약 값을 코드에 옮겨 적지 않는다」다. 그런데 Bean Validation 의
// `@Min`·`@Max`·`@RequestParam(defaultValue = ...)` 는 **애너테이션 인자**라 컴파일 시점
// 상수만 받는다 — 계약 파일을 읽어 넣을 자리가 문법상 없다. 다른 상한들
// (`MAX_UPLOAD_BYTES`·`MAX_CONVERTIBLE_CHARS`)이 `core` 에 복제돼 있는 것과 같은 사정이고,
// 처방도 같다: **복제를 대조로 지킨다.**
//
// 대조는 셋이다.
//   ⑴ `DocumentContractNodeTest` 의 P-25 — 이 상수들이 계약 `x-input-limits` 와 같은지.
//   ⑵ 같은 파일의 이중 선언 대조 — 계약 안 `x-input-limits` 와 오퍼레이션 인라인
//      `parameters[].schema` 가 서로 같은지(한쪽만 고치는 편집을 잡는다).
//   ⑶ `DocumentListContractTest` 의 DL-5·DL-6·DL-7 — **나간 바이트**로 경계 양쪽과
//      기본값을 잰다. ⑴⑵ 가 통과해도 애너테이션을 안 달았으면 여기서 깨진다.
//
// ## 왜 `core` 가 아니라 `api` 인가
//
// 페이지네이션은 도메인 개념이 아니라 **HTTP 표면**이다. 포트
// (`DocumentRepository.listOwned`)는 경계 없는 `Int` 두 개를 받고, 그 경계를 정하는 것은
// 계약이 쿼리 파라미터에 건 스키마 제약이다. `core` 에 두면 도메인이 「한 번에 100건」이라는
// 전송 계층의 판단을 알게 된다.

/** `limit` 의 하한. 계약 `x-input-limits.list_limit.min`. */
const val LIST_LIMIT_MIN: Long = 1

/** `limit` 의 상한. 계약 `x-input-limits.list_limit.max`. */
const val LIST_LIMIT_MAX: Long = 100

/** `limit` 의 기본값. 계약 `x-input-limits.list_limit.default`. */
const val LIST_LIMIT_DEFAULT: String = "20"

/** `offset` 의 하한. 계약 `x-input-limits.list_offset.min`. **상한은 계약에 없다.** */
const val LIST_OFFSET_MIN: Long = 0

/** `offset` 의 기본값. 계약 `x-input-limits.list_offset.default`. 문자열인 사유는 [LIST_LIMIT_DEFAULT] 와 같다. */
const val LIST_OFFSET_DEFAULT: String = "0"
