# 3자 대조 — FastAPI OpenAPI × 계약 파일 × React 수기 타입

**작성:** contract-keeper / **일자:** 2026-08-12 / **Phase:** 0 (scope: contract)

| 축 | 대상 | 성격 |
|---|---|---|
| ① | `docs/migration/_workspace/00_contract-keeper_openapi-fastapi.yaml` (`app.openapi()` 산출물) | 현재 구현이 **선언한** 것 (기계 생성) |
| ② | `contracts/easy-doc-v1.yaml` | 우리가 **동결한 약속** (수기) |
| ③ | `frontend/src/api/types.ts` + `client.ts` + `auth.ts` | 클라이언트가 **믿고 있는** 것 (수기) |

**추출 상태:** OpenAPI 자동 추출 성공(재시도 없음). `확인필요` 표시 항목 없음.
경로·메서드 집합은 ①과 ②가 **완전 일치**(14개, 차집합 양쪽 공집합 — 검증 스크립트로 확인).
`openapi-spec-validator contracts/easy-doc-v1.yaml` → OK.

**실측 근거:** 아래 "실측"으로 표시한 값은 실제 앱(`TestClient(app)`)을 띄워 확인한 응답이다.
추측이 아니다.

일치 항목은 적지 않는다 — 나열하면 진짜 문제가 묻힌다. **불일치만** 아래에 남긴다.

---

## 1. 값 집합이 어긋난 곳 (Phase 6에서 타입 안전성이 걸린다)

| # | 항목 | ① FastAPI | ② 계약 파일 | ③ React | 판정 | 조치 |
|---|---|---|---|---|---|---|
| D-1 | `status` (`ConversionResponse`·`DocumentListItem`·`DocumentCreatedResponse`) | `string` (자유) | **enum 4값** `pending\|processing\|done\|failed` | 리터럴 union 4값 | **①이 느슨함** — 백엔드 Pydantic이 `str`로 선언했다 | ②를 기준으로 고정. Kotlin은 enum으로 구현. **Phase 6 타입 생성은 반드시 ②에서 한다** — ①에서 생성하면 `string`으로 넓어져 현재 React보다 타입 안전성이 후퇴한다 |
| D-2 | `ExportFormat` | enum 3값 (`ExportFormat` StrEnum이 그대로 노출) | enum 3값 | 리터럴 union 3값 | **셋 다 일치** (기록만 — D-1과 대비되는 대조군) | 없음 |

D-1이 D-2와 갈린 이유는 라우터 시그니처다. `format`은 `ExportFormat` 타입으로 선언돼 enum이 자동
추출되지만, 응답의 `status`는 `str`로 선언돼 있다(`app/api/documents.py`의 `ConversionResponse.status`).
**계약을 ②에서 고정하지 않으면 Phase 6에서 조용히 넓어지는 자리가 여기다.**

---

## 2. ①이 런타임과 **다른 값**을 말하는 곳 (자동 생성물을 믿으면 안 되는 증거)

| # | 항목 | ① FastAPI | ② 계약 파일 | ③ React | 판정 | 조치 |
|---|---|---|---|---|---|---|
| D-3 | 422 항목의 `input`·`ctx` | 스키마에 **포함**(`ValidationError.input`, `.ctx`) | **금지**(`additionalProperties: false`) | `msg`만 읽음 | **①이 거짓** — `app/api/errors.py::_handle_request_validation`이 걷어낸다. 실측(2026-08-12): `POST /auth/signup`에서 `password` 누락 → `{"detail":[{"loc":["body","password"],"msg":"Field required","type":"missing"}]}`, `input`·`ctx` 없음 | ②가 정본. **Kotlin이 `input`을 넣으면 제출한 비밀번호가 응답 본문과 액세스 로그에 남는다** — 계약 위반이자 개인정보 사고 |
| D-4 | `ValidationError.loc` 원소 타입 | `string \| integer` | `string`만 | 읽지 않음 | **①이 거짓** — 구현이 `[str(part) for part in error.get("loc", ())]`로 전부 문자열화한다 | ②가 정본. 배열 인덱스도 `"0"` 문자열로 나간다 |
| D-5 | `GET /conversions/{id}/export` 200의 미디어 타입 | 파일 3종 **+ `application/json`** | 파일 3종만 | `response.blob()` | **①이 거짓** — FastAPI가 기본 response_class의 content type을 병합한 아티팩트다. 이 엔드포인트는 JSON을 절대 내지 않는다 | ②가 정본. Kotlin이 이 경로에서 JSON을 낼 수 있게 두면 안 된다 |

---

## 3. ①에 **아예 없는** 것 (자동 생성물의 구조적 한계 — ②가 유일한 기록)

| # | 항목 | ① FastAPI | ② 계약 파일 | ③ React | 판정 | 조치 |
|---|---|---|---|---|---|---|
| D-6 | 오류 응답 401·404·409·413·502·503 | **없음** (200/201/202/204/422만 선언) | 14개 전 엔드포인트에 선언 | `ApiError.status`로 분기 | ①이 불완전 | ②가 정본. 특히 **413은 계획 §2.2 목록에도 없다**(아래 §6) |
| D-7 | `detail`의 문자열/배열 union | `HTTPValidationError.detail`은 **배열만** | `oneOf: [string, array]` | `readErrorMessage`가 두 분기를 나란히 둠 | ①이 불완전. **②와 ③이 일치** | ②가 정본. 한쪽만 구현하면 화면에 폴백 문구만 나오고 진짜 사유가 사라진다 |
| D-8 | 응답 헤더 전부 (`Location`·`Cache-Control`·`X-Content-Type-Options`·`Content-Disposition`·`WWW-Authenticate`) | **없음** | 전부 선언 (성공 응답 10곳 + 401) | `Content-Disposition`만 `parseFilename`으로 읽음 | ①이 불완전 | ②가 정본. 헤더는 본문과 동급 계약이며 §5의 contract test가 고정한다 |
| D-9 | `POST /documents` 요청 본문 | **없음** (`requestBody: null`) | JSON 모드 + multipart 모드 둘 다 수기 기술 | `createDocumentFromText`(JSON) / `createDocumentFromFile`(FormData) | ①이 불완전 — 라우터가 `Request`를 직접 읽어 파싱한다(한 엔드포인트가 두 Content-Type을 받으므로 선언적 파싱 불가) | ②가 정본 |
| D-10 | CORS 정책 | 표현 불가 | `x-cors` 확장 필드 | 브라우저가 소비 (JS가 `Content-Disposition`을 읽으려면 `expose_headers` 필수) | **의도된 차이** — OpenAPI 표현력 한계 | ②의 `x-cors`가 정본. 서버 테스트로는 잡히지 않으므로 브라우저 컨텍스트 검증 필요 |
| D-11 | 인증 실패와 입력 검증의 **우선순위** | 표현 불가 | `info.description`에 명시 | 표현 없음 | ②가 유일한 기록. 실측(2026-08-12): 위조 토큰으로 `GET /documents?limit=0`, `DELETE /documents/not-a-uuid`, `?format=pdf` → 셋 다 422가 아니라 **401** | **Kotlin에서 Bean Validation이 보안 필터보다 먼저 돌면 이 순서가 뒤집혀 계약 위반이 된다.** 놓치기 쉬운 자리 |

---

## 4. required 범위가 갈린 곳 (Kotlin이 필드를 생략하면 React가 깨진다)

| # | 항목 | ① FastAPI | ② 계약 파일 | ③ React | 판정 | 조치 |
|---|---|---|---|---|---|---|
| D-12 | `ConversionResponse`의 nullable 필드 9개 (`easy_text`·`edited_text`·`reviewed_at`·`model`·`provider_name`·`input_tokens`·`output_tokens`·`failure_code`, 그리고 `masked_items`·`missing_placeholders`) | `required`는 `[id, document_id, status]` **3개뿐** | 전 필드 required (값은 nullable) | 전 필드 필수, 타입은 `\| null` | **①이 느슨함. ②와 ③이 일치** | ②가 정본. **Kotlin이 Jackson `NON_NULL`로 null 필드를 생략하면 React가 `null` 대신 `undefined`를 받아 분기가 달라진다** |
| D-13 | `DocumentListItem`의 `conversion_id`·`status`·`reviewed_at` | required 아님 | required (nullable) | 필수, `\| null` | ①이 느슨함. ②·③ 일치 | 위와 같음 |
| D-14 | `TokenResponse.token_type` | 기본값 `"bearer"`가 있어 required 아님 | required + `const: "bearer"` | 필수 `string` | ①이 느슨함. 런타임은 항상 실린다 | ②가 정본 |
| D-15 | `masked_items`·`missing_placeholders` | 기본값 `[]`가 있어 required 아님 | required (빈 배열이지 null 아님) | 필수 배열 | ①이 느슨함. ②·③ 일치 | ②가 정본. **완료 전에도 `null`이 아니라 `[]`다** |

이 네 항목은 원인이 하나다: Pydantic이 "기본값 또는 nullable = optional"로 스키마를 만들지만
**런타임 응답은 항상 모든 키를 싣는다.** 계약은 "키는 항상 존재하고 값이 null일 수 있다"로 고정했다.

---

## 5. 의도된 차이 (사유 기록 — 고치지 않는다)

| # | 항목 | 상태 | 사유 | Phase 6 조치 |
|---|---|---|---|---|
| D-16 | `DELETE /workspaces/{workspace_id}` | ①·②에 있고 **③에 호출부 없음** | **의도적**이다. 삭제 화면이 이번 범위 밖이라 부를 곳이 없고, 되돌릴 수 없는 조작의 통로를 미리 열어 두면 확인 절차 없이 연결될 위험만 남는다 (`client.ts::renameWorkspace` 주석, `app/api/workspaces.py` 모듈 docstring) | 계약에는 포함. **타입 생성 후에도 호출 래퍼를 추가하지 않는다** |
| D-17 | `GET /health` | ①·②에 있고 ③에 없음 | 화면이 부르지 않는다 | 계약 유지, 래퍼 추가 없음 |
| D-18 | `SignupRequest` / `LoginRequest` | ①·②는 **별개 스키마 2개**(모양은 동일) | ③은 `CredentialsRequest` **하나**로 합쳤다 | 모양이 같아 React가 합친 것 | **Phase 6 주의 항목.** 생성 타입은 둘로 갈라지므로 `auth.ts`의 import 이름이 바뀐다. `client.ts`·`auth.ts` 변경 면적을 줄이려면 얇은 재수출 모듈을 먼저 검토한다 |
| D-19 | `Location` 헤더 | ①에 없음 / ②에 필수 선언 | ③은 **읽지 않는다** — `conversion_id`를 202 본문에서 가져온다 | 표준 202 규약이고 CORS `expose_headers`에도 들어 있다. 클라이언트가 아직 안 쓸 뿐 | 계약 유지. React 변경 없음 |
| D-20 | `token_type`·`expires_in` | ①·②·③ 모두 선언 | ③은 **런타임 코드가 읽지 않는다**(타입 선언과 테스트에만 등장) | 타입은 선언돼 있으므로 생성 타입에서도 유지돼야 한다 | 계약 유지 |
| D-21 | multipart의 `title` 파트 | ①에 없음 / ②에 선택 필드로 기술 | ③ `createDocumentFromFile`은 `file`·`workspace_id`만 보낸다 | 백엔드는 받는다. 클라이언트가 안 쓸 뿐 | 계약 유지(선택 필드) |

---

## 6. 계획 문서 §2.2와 코드가 어긋난 곳 (**코드가 이겼다**)

계획 문서는 계약의 의도를 적은 것이고, 지켜야 하는 값은 코드에 있다.

| # | 항목 | 계획 §2.2 | 코드 (=계약) | 조치 |
|---|---|---|---|---|
| P-1 | **413** (10MB 초과) | 상태 코드 목록에 **없다** ("입력 오류 422, 충돌 409, 인증 401, 소유권 404, 의존 서비스 5xx") | `UploadTooLargeError` → `413 CONTENT_TOO_LARGE` (`app/api/errors.py`). 회귀 테스트 `tests/api/test_documents.py::test_상한을_넘는_파일은_413` | 계약에 **포함**했다. 계획 §2.2에 413을 추가하도록 **문서 갱신 제안** |
| P-2 | `GET /health`의 "의존 서비스 상태 진단" (`api-contract-freeze` §1 표 비고) | 진단한다고 적혀 있다 | **상수 `{"status":"ok"}`만 돌려준다** (`app/main.py::health`). DB·Redis·설정이 전부 죽어도 200 ok | v1은 **현행대로 동결**. 진단 확장은 v2 후보 `V2-1`로 기록. 계획 문서 갱신 제안 |
| P-3 | 502 / 503 구분 | "의존 서비스 오류 5xx"로 뭉뚱그림 | 큐 **등록 실패** = 502(`QueueUnavailableError`), 큐·설정 **미배선** = 503(`ConfigurationError`) | 계약에 구분해 명시. 합치면 "재시도할 값어치가 있는가"라는 클라이언트 판단이 무너진다 |

---

## 7. 리더 판단이 필요한 미결 항목

| # | 항목 | 내용 | 왜 계약 소유자 혼자 정할 수 없나 |
|---|---|---|---|
| **U-1** | 미처리 500 응답의 CORS 헤더 | Python은 `ServerErrorMiddleware`가 CORS 미들웨어 **바깥**에 있어 500에 CORS 헤더가 구조적으로 붙지 않는다(`app/api/errors.py`에 한계로 명시). 브라우저는 상태 코드조차 못 읽고 네트워크 오류로 본다. **Kotlin에는 이 제약이 없다** | "동등하게 재현"과 "개선" 중 무엇을 택하느냐에 따라 **React 화면 분기가 달라진다.** 지금 네트워크 오류(`status = 0`, "서버에 연결하지 못했습니다")로 보이던 상황이 개선하면 500으로 바뀐다. React가 이미 그 동작에 의존하고 있으므로 계약 소유자 혼자 내릴 판단이 아니다. 계약 파일에는 `x-cors.x-known-limitation`과 v2 후보 `V2-2`로 **미결 상태 그대로** 기록했다 |

> **U-1이 정해지기 전까지 kotlin-implementer는 이 경로를 구현하지 않는다.**
> 어느 쪽이든 구현부터 하면 나중에 계약이 구현을 따라가게 된다.

---

## 8. 요약

- **경로·메서드는 3자 완전 일치** (14개). 계약 동결의 전제는 성립한다.
- **①(FastAPI 자동 생성물)은 계약으로 쓸 수 없다.** 거짓 3건(D-3·D-4·D-5), 누락 6건(D-6~D-11),
  느슨함 5건(D-1·D-12~D-15). 특히 D-3은 자동 생성물을 믿고 Kotlin을 구현하면
  **비밀번호가 응답 본문에 실리는** 결과가 된다.
- **②와 ③은 실질 계약에서 대체로 일치한다.** ③(React 수기 타입)이 ①보다 정확한 자리가 많다
  (D-1·D-7·D-12~D-15). Phase 6에서 ①로 타입을 생성하면 **퇴보**한다 — 반드시 ②에서 생성한다.
- **의도된 차이 6건**(D-16~D-21)은 고치지 않는다. 특히 `DELETE /workspaces/{id}`는
  Phase 6 타입 교체 후에도 호출 래퍼를 만들지 않는다.
- **미결 1건**(U-1)은 리더 판단 대기. 이것 때문에 Phase 0 종료를 막지는 않는다 —
  14개 엔드포인트의 계약 자체는 확정됐고, U-1은 500 경로의 **CORS 헤더 부착 여부**라는
  단일 항목이며 계약 파일에 미결로 명시돼 있다.
