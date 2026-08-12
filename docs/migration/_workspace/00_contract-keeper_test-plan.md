# contract test 계획 — 무엇을 어느 계층에서 고정하는가

**작성:** contract-keeper / **일자:** 2026-08-12 / **Phase:** 0 (scope: contract)
**정본 계약:** `contracts/easy-doc-v1.yaml`

> **이 문서는 목록과 기준만 세운다. 테스트를 지금 구현하지 않는다.**
> Kotlin API는 Phase 3부터 생기므로 실행은 그때부터다. 그 전까지 Python suite가
> **비교 기준선**이다(계획 §6).
>
> 계약 조항 참조는 계약 파일의 위치로 적는다. 행 번호는 파일이 움직이면 곧 틀린 값이 되므로
> 키 경로(`x-private-response-headers`, `components/schemas/ErrorResponse` 등)로 가리킨다.

---

## 0. 설계 원칙 세 가지

### 0-1. 계약 테스트는 HTTP 경계에서 돈다

서비스 계층 단위 테스트로 대신할 수 없다. 상태 코드 매핑, 헤더 부착, 직렬화 이름은
**전부 경계에서 결정되기 때문**이다. 서비스가 `NotFoundError`를 던지는 것을 확인해도
그것이 404로 나가는지, 403으로 나가는지는 경계 테스트만 안다.

### 0-2. 본문만 검증하는 테스트는 이 API에서 절반짜리다

실제로 깨지기 쉬운 것이 대부분 본문이 아니다.

| 빠졌을 때 | 겉으로 보이는 증상 | 본문 검증으로 잡히나 |
|---|---|---|
| `Location` | 202 본문은 멀쩡한데 클라이언트가 폴링 주소를 못 만든다 | 아니오 |
| `Cache-Control: no-store` | 응답은 정상이고 **프록시가 개인정보를 캐시한다** | 아니오 — 200 OK로 나타나는 보안 사고다 |
| `Content-Disposition`의 `filename*` | 다운로드는 되고 파일명만 깨진다 | 아니오 |
| CORS `expose_headers` | 서버 응답은 완전한데 **브라우저 JS만** 못 읽는다 | 아니오 — 서버 테스트로도 안 잡힌다 |
| 422 대신 400 | 본문 모양이 같아도 React 분기가 달라진다 | 아니오 |
| 검증 응답의 `input` | 제출한 비밀번호가 응답과 액세스 로그에 남는다 | **역으로** — 없음을 단언해야 잡힌다 |

### 0-3. Python과 Kotlin이 **같은 표**를 읽게 만든다

요청/기대응답을 데이터로 두고 두 런타임이 같은 fixture를 읽는 구조를 우선한다.
두 벌의 테스트 코드가 서로 다른 것을 검증하기 시작하면 대조 자체가 무의미해진다.
데이터로 표현하기 어려운 것(멱등성, 동시성, 브라우저 컨텍스트)만 코드로 따로 쓴다.

---

## 1. 계층별 역할

| 계층 | 도구 (Python / Kotlin) | 검증 범위 | Phase |
|---|---|---|---|
| **C. 계약(경계)** | `TestClient` / `@SpringBootTest` + MockMvc | 상태 코드, **응답 헤더**, 오류 본문 모양, 필드 이름, enum 값 | 3~ |
| **I. 통합(DB)** | pytest + 실 DB / Testcontainers PostgreSQL | 소유권 404, 409 충돌, 트랜잭션 원자성 | 3~ |
| **X. 교차 런타임** | parity fixture (양방향) | JWT 상호 수용, Argon2 PHC 검증, Fernet 복호 | 0~2 (parity-verifier 소관) |
| **B. 브라우저** | compose + 실제 브라우저 | CORS 노출 헤더, 다운로드 파일명, 401 세션 만료 | 6~ |

**X 계층은 parity-verifier가 소유한다.** 이 문서는 "계약상 무엇이 같아야 하는지"의 범위만
정하고, 같은 값이 나오는지 실행해 증명하는 일은 그쪽이 한다.

---

## 2. 엔드포인트별 기본 세트

**엔드포인트 하나당 최소 4종**을 둔다. 인증 불필요 엔드포인트는 ②를 빼고, 소유권 개념이
없는 엔드포인트는 ③을 뺀다.

① 성공 경로 (상태 코드 + **필수 헤더** + 필드 이름) · ② 인증 없음 401 ·
③ 남의 자원 404 · ④ 대표 입력 오류 422

409·413이 있는 엔드포인트는 그것도 더한다.

| # | 엔드포인트 | ① 성공 | ② 401 | ③ 404 | ④ 422 | 추가 | 고정하는 계약 조항 |
|---|---|---|---|---|---|---|---|
| 1 | `POST /auth/signup` | 201 + no-store/nosniff + `{id,email}` | — (인증 불필요) | — | 이메일 형식(문자열 detail) · 필드 누락(배열 detail) | **409** 이메일 중복 | `paths./auth/signup`, `x-private-response-headers` |
| 2 | `POST /auth/login` | 200 + no-store/nosniff + `{access_token,token_type,expires_in}` | — | — | 필드 누락 | **401** 자격증명 실패 + `WWW-Authenticate` | `components/responses/Unauthorized` |
| 3 | `GET /auth/me` | 200 + no-store/nosniff + `{id,email}` | ✔ | — | — | — | `paths./auth/me` |
| 4 | `POST /documents` | **202** + **`Location`** + `{document_id,conversion_id,status,char_count}` | ✔ | 남의 `workspace_id` | 빈 본문·4,000자 초과·미지원 형식·추출 실패·잘못된 JSON | **413** 10MB 초과 · **502** 큐 등록 실패 · **503** 큐 미배선 · **multipart 경로 별도** | `paths./documents.post`, `x-input-limits`, `components/responses/PayloadTooLarge`, `.../BadGateway`, `.../ServiceUnavailable` |
| 5 | `GET /documents` | 200 + no-store/nosniff + `{items,limit,offset,has_more}` | ✔ | 남의 `workspace_id` | `limit=0`·`limit=101`·`offset=-1` | — | `x-input-limits.list_limit`, `.list_offset` |
| 6 | `DELETE /documents/{id}` | **204 + 본문 길이 0 + 캐시 헤더 있음**(2026-08-12 전역 부착으로 부호 반전) | ✔ | ✔ | UUID 형식 | — | `paths./documents/{document_id}.delete`, `x-global-response-headers` |
| 7 | `GET /conversions/{id}` | 200 + no-store/nosniff + 13필드 전부 존재 | ✔ | ✔ | UUID 형식 | 상태 4종별 응답 모양(`pending`/`processing`은 결과 null·배열 `[]`, `failed`는 `failure_code`) | `components/schemas/ConversionResponse`, `.../ConversionStatus` |
| 8 | `PUT /conversions/{id}` | 200 + **no-store/nosniff** + `easy_text`가 **보존됨** | ✔ | ✔ | 빈 값·4,000자 초과 | **409** `done` 아님 | `paths./conversions/{conversion_id}.put` (§2.7 해결 1) |
| 9 | `GET /conversions/{id}/export` | 200 + 미디어 타입 3종 + **`Content-Disposition` 양쪽 표기** + no-store/nosniff | ✔ | ✔ | `format` 누락·`pdf` 등 미지원 | **409** 미완료 · **409** 검수 없이 `missing_placeholders` 존재 | `paths..../export`, `components/schemas/ExportFormat` |
| 10 | `GET /workspaces` | 200 + no-store/nosniff + **첫 항목이 기본 작업 공간** | ✔ | — | — | — | `components/schemas/WorkspaceListResponse` |
| 11 | `POST /workspaces` | 201 + **no-store/nosniff** | ✔ | — | 빈 이름·50자 초과 | **409** 같은 이름 | `paths./workspaces.post` |
| 12 | `PATCH /workspaces/{id}` | 200 + **no-store/nosniff** + **PUT이 아님** | ✔ | ✔ | 빈 이름·50자 초과 | **409** 같은 이름 | `paths./workspaces/{workspace_id}.patch` |
| 13 | `DELETE /workspaces/{id}` | **204 + 본문 길이 0** | ✔ | ✔ | UUID 형식 | **409** 문서 있음 · **409** 마지막 하나 | `paths./workspaces/{workspace_id}.delete` |
| 14 | `GET /health` | 200 + **캐시 헤더 있음**(2026-08-12 전역 부착으로 부호 반전) + `status`가 `checks`와 일치(degraded여도 200) | — (인증 불필요) | — | — | 인증 없이 200임을 단언 | `paths./health`, `x-global-response-headers` |

---

## 3. 횡단 테스트 (엔드포인트별 세트로는 못 잡는 것)

| ID | 테스트 | 계층 | 고정하는 계약 조항 | 왜 필요한가 |
|---|---|---|---|---|
| **X-A1** | 401 응답 전체에 `WWW-Authenticate: Bearer`가 있다 | C | `components/headers/WWWAuthenticateBearer` | 헤더 하나가 빠져도 상태 코드는 정상이라 본문 검증으로는 안 잡힌다 |
| **X-A2** | 헤더 누락 · 위조 토큰 · 만료 토큰 · `typ` 불일치 · 삭제된 계정 → **모두 401**, 응답 본문이 실패 사유를 구분하지 않는다 | C | `x-auth.failure_uniformity` | 구분하면 계정 열거 공격의 단서가 된다 |
| **X-A3** | **인증이 입력 검증보다 먼저다** — 위조 토큰 + `limit=0` / 잘못된 UUID / `format=pdf` → 422가 아니라 **401** | C | `info.description`의 우선순위 절 | Kotlin에서 Bean Validation이 보안 필터보다 먼저 돌면 조용히 뒤집힌다. **놓치기 가장 쉬운 자리** |
| **X-A4** | `exp` 없는 토큰 · `sub` 없는 토큰 · `typ != "access"` 토큰을 거부한다 | C | `x-auth.required_claims`, `.claim_typ` | `exp` 없는 토큰은 영구 자격증명이 된다 |
| **X-A5** | 32바이트 미만 서명 키로 조립하면 **503**(기동은 막지 않는다) | C | `components/responses/ServiceUnavailable` | 약한 키가 경고만 남기고 조용히 통과하는 것을 막는다 |
| **X-B1** | **소유권 은닉: 남의 자원 접근이 403이 *아님*을 단언한다** (404여야 한다) | I | `info.description`의 소유권 절 | "404가 맞다"만 단언하면 구현이 403을 내도 다른 테스트가 안 잡을 수 있다. **아님**을 명시적으로 건다 |
| **X-B2** | 없는 자원과 남의 자원의 응답이 **완전히 동일하다**(상태 코드·본문·헤더) | I | 같음 | 미세한 차이(메시지 문구)가 존재 여부를 흘린다 |
| **X-C1** | **검증 실패 응답 본문에 제출한 비밀번호 문자열이 없다** | C | `components/schemas/ValidationErrorItem` (`input`·`ctx` 금지) | 회귀하면 즉시 잡힌다. Python 기준선에 이미 있다(`test_auth.py:173`) |
| **X-C2** | 422 응답 항목의 키가 정확히 `{loc, msg, type}` 셋뿐이다 | C | 같음 | `input`/`ctx`가 새 프레임워크 기본값으로 되살아나는 것을 막는다 |
| **X-C3** | `detail`이 문자열인 응답과 배열인 응답이 **둘 다** 나온다 (같은 422에서도) | C | `components/schemas/ErrorResponse` (`oneOf`) | 한쪽만 구현하면 React가 폴백 문구만 보여 준다 |
| **X-C4** | 오류 응답의 `Content-Type`이 `application/json`이다 (`application/problem+json` 아님) | C | 같음 | Spring 기본 `ProblemDetail`이 새는 자리 |
| **X-C5** | 미매핑 도메인 예외 → 500 `"요청을 처리하지 못했습니다"` / 도메인 밖 예외 → 500 `"서버 오류가 발생했습니다"` | C | `components/responses/InternalError` | 예외 내용이 그대로 노출되는 경로를 닫는다 |
| **X-C6** | 502(`QueueUnavailableError`)와 503(`ConfigurationError`)이 **다른 코드로** 나간다 | C | `components/responses/BadGateway`, `.../ServiceUnavailable` | 합치면 "재시도할 값어치가 있는가"라는 클라이언트 판단이 무너진다 |
| **X-D1** | 고위험 성공 응답 **10곳 각각**에 캐시 금지 헤더가 있다 (하한선 — 전역 규칙이 있어도 **삭제하지 않는다**) | C | `x-private-response-headers.applies_to` | 필터가 제거되거나 체인 순서가 어긋나도 고위험 경로에서 **먼저** 깨져야 한다(리더 판정 부수 결정 1) |
| **X-D2** | ~~DELETE 204 두 곳과 `/health`, 모든 오류 응답에는 캐시 헤더가 **없다**~~ → **2026-08-12 부호 반전.** DELETE 204 두 곳·`/health`·**모든 오류 응답**(401·404·409·413·422·500·502·503)·프리플라이트에 캐시 헤더가 **있다** | C | `x-global-response-headers` | 리더 판정으로 전역 부착이 요구가 됐다. **Python은 고치지 않으므로 이 단언은 Kotlin 전용**이고, Python이 통과하지 못하는 것이 정상이다 |
| **X-D2b** | 헤더가 **하나씩만** 있고 값이 정확히 `no-store`/`nosniff`다 (`header().stringValues(...)`로 **개수까지**) | C | `x-global-response-headers.enforcement` | 필터와 `ResponseEntity`가 둘 다 실으면 `no-store, no-store`가 나가 `const` 제약 위반. 값만 보는 단언은 통과해 버린다 |
| **X-D2c** | **컨테이너 레벨 응답**(malformed HTTP 400 · 핸들러 없는 404 · 413 · 415)에도 두 헤더가 있다 — ⚠️ **미실측. MockMvc 불가**, `@SpringBootTest(RANDOM_PORT)` + 원시 소켓 필요 | C | `x-global-response-headers.x-phase3-measurement` | 실패 양상이 "누가 빠뜨렸는가"에서 **"필터가 닿는가"**로 옮겨간 자리. 어긋나면 계약을 좁히지 말고 필터 배치를 고친 뒤 리더 재심 |
| ~~**X-D3**~~ | ~~라우터가 헤더를 적어 둔 **뒤에 예외를 던져도** 오류 응답에 헤더가 새지 않는다~~ | — | — | **폐기 (2026-08-12).** 전역 부착으로 "헤더가 새는 것"이 더는 위반이 아니다. Python 기준선의 `test_errors.py::_app_raising_after_private_headers`는 Python 쪽 기술로 남지만 Kotlin 계약 단언이 아니다 |
| **X-D4** | `POST /documents` 202의 `Location` 값이 **본문 `conversion_id`와 같다** | C | `paths./documents.post` 응답 헤더 | 두 값이 갈리면 폴링이 엉뚱한 자원을 본다 |
| **X-D5** | `Content-Disposition`에 ASCII `filename=`과 RFC 5987 `filename*=UTF-8''`가 **둘 다** 있고, 한글 제목이 퍼센트 인코딩돼 `filename*`에 실린다 | C | `paths..../export` 응답 헤더 | 헤더 값은 latin-1만 담는다. React `parseFilename`이 `filename*`만 읽는다 |
| **X-D6** | `txt` 내보내기의 미디어 타입에 `charset=utf-8`이 **있다** | C | `paths..../export` 200 content | 없으면 브라우저가 로캘 기본 인코딩으로 열어 한글이 깨진다 |
| **X-E1** | 응답 JSON의 모든 최상위·중첩 필드 이름이 **snake_case**다 (전 엔드포인트 스냅샷 대조) | C | `info.description`의 필드 이름 절 | **Jackson 기본 네이밍 전략이 camelCase로 바꾸는 사고가 잦다.** 개별 단언이 아니라 키 집합 전체를 계약과 대조한다 |
| **X-E2** | nullable 필드가 **키째 생략되지 않는다** — `ConversionResponse` 13필드·`DocumentListItem` 9필드가 완료 전 상태에서도 전부 존재한다 | C | `components/schemas/ConversionResponse` required | Jackson `NON_NULL`이면 React가 `null` 대신 `undefined`를 받아 분기가 달라진다 |
| **X-E3** | `masked_items`·`missing_placeholders`가 완료 전에 `null`이 아니라 `[]`다 | C | 같음 | 위와 같은 이유 |
| **X-E4** | `status` 값이 4개 집합 밖으로 나가지 않는다 (모든 상태 전이 경로에서) | C+I | `components/schemas/ConversionStatus` | DB CHECK 제약과 같은 집합이다 |
| **X-F1** | 4,000자 초과 본문 → 422 / 정확히 4,000자 → 통과 (**경계값 양쪽**) | C | `x-input-limits.max_convertible_chars` | 경계를 한쪽만 걸면 off-by-one이 남는다 |
| **X-F2** | 검수 수정본도 같은 4,000자 상한 | C | `.max_review_chars` | 상한이 갈리면 사용자에게 설명할 수 없다 |
| **X-F3** | 10MB 초과 파일 → **413**(422 아님) / 정확히 10MB → 통과 | C | `.max_upload_bytes` | 계획 §2.2에 없는 코드다. 놓치면 422로 구현된다 |
| **X-F4** | 확장자 `docx`·`pdf`·`hwpx` 통과(**대소문자 무시**), 그 밖은 422 | C | `.supported_upload_formats` | `.DOCX`가 거부되면 사용자 경로가 막힌다 |
| **X-F5** | 구버전 `.doc`(OLE2)은 **전용 안내 문구**와 함께 422 | C | `.legacy_doc_policy` | 안내가 같으면 사용자가 있지도 않은 암호를 찾아 헤맨다 |
| **X-F6** | 텍스트 레이어 없는 PDF · 암호화 PDF → 422 | C | `.rejected_pdf` | |
| **X-F7** | zip 압축 해제량이 예산(50MB)을 넘으면 422 (압축 폭탄) | C | `.zip_uncompressed_budget_bytes` | 헤더 선언 크기를 믿으면 뚫린다 |
| **X-F8** | hwpx의 DTD 선언 거부 (XXE) | C | `.rejected_pdf` 인접 조항 / migration-safety-gate 공동 | UTF-16 인코딩으로 우회 가능한 방어는 무효다 |
| **X-G1** | `POST /documents`가 JSON과 multipart를 **둘 다** 받고, `Content-Type` 비교가 **대소문자를 가리지 않는다**(`Multipart/Form-Data`) | C | `paths./documents.post` requestBody | RFC 9110. 대소문자를 가리면 일부 클라이언트가 JSON 경로로 샌다 |
| **X-G2** | multipart에서 `file` 파트가 없거나 파일이 아니면 422 | C | `components/schemas/DocumentFileRequest` | |
| **X-G3** | multipart의 `workspace_id`가 빈 문자열이면 미지정과 같게 다루고, 형식 오류면 422이며 **값이 메시지에 담기지 않는다** | C | 같음 | |
| **X-H1** | 허용 origin에 CORS 헤더가 붙고, 모르는 origin에는 붙지 않는다 | C | `x-cors.allow_origins` | |
| **X-H2** | `allow_credentials`가 **꺼져 있다** | C | `x-cors.allow_credentials` | 켜면 CSRF 노출 면적만 넓어진다 |
| **X-H3** | preflight가 `GET/POST/PUT/PATCH/DELETE`와 `Authorization`·`Content-Type`을 알린다 | C | `x-cors.allow_methods`, `.allow_headers` | 빠진 메서드는 **브라우저에서만** 막힌다 |
| **X-H4** | `expose_headers`에 `Content-Disposition`·`Location`이 있다 | C | `x-cors.expose_headers` | |
| **X-H5** | **브라우저에서 실제로** 다운로드 파일명을 읽는다 | **B** | 같음 | 서버가 보내는 것과 브라우저가 읽을 수 있는 것은 다른 문제다. C 계층으로는 증명되지 않는다 |
| **X-H6** | **브라우저에서** 401 수신 시 토큰 폐기 + 로그인 화면 이동 | **B** | `client.ts` 정책(계약 밖이지만 계약 401에 의존) | |
| **X-I1** | Python 발급 JWT를 Kotlin이 수용하고, **그 반대도** 된다 | **X** | `x-auth` 전체 | 한 방향만 되면 절체 중 롤백이 불가능해진다 |
| **X-I2** | 기존 Argon2 PHC 문자열이 Kotlin에서 그대로 검증되고, 로그인 **성공 시에만** 재해시된다 | **X** | `x-auth.rehash_policy` | |

---

## 4. Python 기준선 커버리지와 **공백**

Phase 3에서 Kotlin 테스트를 쓸 때 "Python에 이미 있는 것"과 "양쪽 다 새로 필요한 것"을
가른다. 현재 `tests/api/` 134건 기준.

### 4-1. 이미 Python이 덮는 주요 조항

| 조항 | 근거 |
|---|---|
| 413 (10MB 초과) | `test_documents.py::test_상한을_넘는_파일은_413` |
| 502 / 503 구분 | `test_documents.py::test_큐_등록에_실패하면_502이고_변환은_실패로_남는다`, `::test_큐가_준비되지_않았으면_503`, `::test_암호화_키가_없으면_503` |
| 401 + `WWW-Authenticate` | `test_auth.py:207`, `test_workspaces.py:327`, `test_documents.py:1277`, `test_errors.py:219` |
| 소유권 404(403 아님) | `test_documents.py:583,698,1126`, `test_workspaces.py:218` (docstring에 "403이 아니라 404"로 명시) |
| 비밀번호 에코 없음 | `test_auth.py:173` (`assert password not in response.text`) |
| 오류 응답에 캐시 헤더 없음 | `test_errors.py`의 `_app_raising_after_private_headers` — **Python 기준선의 기술이며 Kotlin 계약 단언이 아니다.** Kotlin은 2026-08-12 전역 부착으로 반대(있음)를 요구한다. Python은 고치지 않기로 판정됐으므로 이 차이는 결함이 아니라 **기록된 차이**다 |
| `Location` 값 = 본문 `conversion_id` | `test_documents.py:194` |
| `Content-Disposition`의 `filename*` | `test_documents.py:803,830` (docx·hwpx) |
| CORS 5종 | `tests/test_cors.py` 5건 |
| 캐시 헤더 8/10 | `test_auth.py` 3건 + `test_documents.py` 4건 + `test_workspaces.py` 1건 |

### 4-2. **Python 기준선에도 없는 공백** (Phase 3 전에 메울 후보)

| ID | 공백 | 심각도 | 사유 |
|---|---|---|---|
| **G-1** | `POST /workspaces`(201)와 `PATCH /workspaces/{id}`(200)의 캐시 헤더를 **어떤 테스트도 단언하지 않는다** | **높음** | 계약상 10곳 중 2곳이 테스트로 고정돼 있지 않다. 구현에는 있지만(`workspaces.py:96,113`) 회귀를 잡을 장치가 없다. X-D1이 이 공백을 메운다 |
| **G-2** | X-A3(인증이 입력 검증보다 먼저) | **높음** | Python은 우연히 그렇게 동작할 뿐 단언이 없다. Kotlin에서 뒤집히면 아무도 모른다 |
| **G-3** | X-E1(snake_case 전수 대조) | **높음** | 개별 필드 접근으로 간접 확인될 뿐 키 집합 전체를 계약과 대조하지 않는다. Jackson 사고를 잡을 장치가 없다 |
| **G-4** | X-E2·X-E3(nullable 필드의 키 존재, 빈 배열 대 null) | 중간 | 현재 Python은 값만 보고 키 존재를 단언하지 않는다 |
| **G-5** | X-C2(422 항목 키가 정확히 3개) | 중간 | X-C1이 비밀번호만 본다. `input` 키 자체의 부재는 단언되지 않는다 |
| **G-6** | X-F1·X-F3의 **하한 경계**(정확히 4,000자 / 정확히 10MB가 통과) | 낮음 | 4,000자 경계는 있으나(`test_documents.py:383`) 10MB 경계는 초과만 있다 |
| **G-7** | X-D6(txt의 `charset=utf-8`) | 낮음 | |
| **G-8** | X-H5·X-H6(브라우저 계층) | 중간 | Phase 6 이전에는 실행 환경이 없다 |

> **G-1은 이 대조에서 새로 드러난 것이다.** 계약을 문서로만 만들었으면 보이지 않았을 자리다.
> 다만 Python 테스트 추가는 이 에이전트의 범위 밖(`tests/`는 읽기 전용)이므로,
> **리더에게 판단을 올린다** — Phase 0에서 Python에 2건을 먼저 채울지, Phase 3에서
> Kotlin·Python 양쪽에 동시에 넣을지.

---

## 5. 실행 시점과 게이트 대응

| Phase | 무엇을 돌리나 | 계획 §6 게이트 |
|---|---|---|
| 0 (지금) | **아무것도 실행하지 않는다.** 계약 파일과 이 목록을 만든다 | — |
| 2 | X-I1·X-I2 (JWT·Argon2 교차 런타임) | Crypto |
| 3 | #1·#2·#3 + X-A*·X-B*·X-C*·X-D1·X-D2·X-D3·X-E1 | Contract, DB |
| 4 | #4~#9 + X-D4·X-D5·X-D6·X-E2~X-E4·X-F*·X-G* | Contract, Document, Security |
| 5 | #4의 502/503 경로 (큐 전환 후 재확인) | Worker |
| 6 | #10~#14 + X-H1~X-H4 + **X-H5·X-H6(브라우저)** | E2E |
| 7 | 전체 재실행 (절체 리허설) | Ops |

**Contract 게이트 통과 기준**(계획 §6): status / body / header / error가 v1 spec과 일치.
위 표의 C 계층 항목이 전건 통과해야 한다.

---

## 6. parity-verifier에게 넘기는 범위

계약상 **반드시 같아야 하는 것**의 목록이다. parity 검증이 "값이 같은지"를 다루므로
이쪽이 범위를 정해 준다.

- **응답 필드 이름 전체 집합** (엔드포인트별) — snake_case, 키 생략 없음
- **`status`의 4개 값**, **`ExportFormat`의 3개 값**
- **오류 `detail` 문자열 전문** — 한국어 사용자 문구가 그대로 같아야 한다.
  계획 §4.1의 "Kotlin 오류 응답을 현재 한국어 메시지로 바꾸는 어댑터"가 전제하는 값이고,
  React 테스트가 문구를 단언하고 있으면 문구가 바뀔 때 함께 깨진다.
  대상: `components/responses/*`와 각 `paths.*.responses.*.examples`의 `detail` 값
- **응답 헤더 값** — `no-store`, `nosniff`, `Bearer`, `Location` 형식, `Content-Disposition` 전문
- **미디어 타입 3종 문자열** (`charset` 포함)
- **`expires_in`의 값** (현재 3600) — 설정에서 오므로 같은 설정에서 같은 값이 나오는지

**parity 범위 밖**: 응답 순서가 정의되지 않은 것(`masked_items` 정렬), 시각 값 자체,
UUID 값. 이것들은 값이 아니라 **형식**만 같으면 된다.

---

## 7. 통보

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | 정본 계약은 `contracts/easy-doc-v1.yaml`. 특히 **X-A3(인증 우선순위)**, **X-C2(`input` 금지)**, **X-E1/E2(snake_case·키 생략 금지)** 셋이 Spring 기본값과 충돌한다. 헤더는 **X-D2로 부호가 반전됐다** — 이제 오류 응답에 캐시 헤더가 **있어야** 하고 수단은 서블릿 필터다(`add`가 아닌 `set`, X-D2b). **X-D2c(컨테이너 레벨 도달)는 미실측이며 MockMvc로 측정할 수 없다** |
| `parity-verifier` | §6의 범위. 오류 `detail` 문구는 형식이 아니라 **전문 일치**가 계약이다. **응답 헤더는 두 런타임이 의도적으로 다르다**(Kotlin 전역 / Python 10곳) — 불일치로 올리지 않는다 |
| 리더 | **G-1**(Python에도 없는 캐시 헤더 테스트 2건)의 처리 시점 판단. ~~**U-1**~~·~~**OQ-1**~~ 둘 다 2026-08-12 판정 완료. **X-D2c 실측이 어긋나면 재심 대상** |
