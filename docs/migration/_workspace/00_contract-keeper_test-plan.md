# contract test 계획 — 무엇을 어느 계층에서 고정하는가

**작성:** contract-keeper / **일자:** 2026-08-12 / **Phase:** 0 (scope: contract)
**정본 계약:** `contracts/easy-doc-v1.yaml`

> **이 문서는 목록과 기준만 세운다. 테스트를 지금 구현하지 않는다.**
> Kotlin API는 Phase 3부터 생기므로 실행은 그때부터다.
>
> **2026-08-12 2차 갱신**: Python을 폐기하고 재개발하기로 결정돼 **Python suite는 비교
> 기준선이 아니다.** 아래 항목이 고정하는 것은 전부 `contracts/easy-doc-v1.yaml`이며,
> Python 관련 서술은 **어떤 조항에 이미 선례가 있었는지**를 보여 주는 연혁으로만 읽는다.
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

### 0-3. 기대 응답을 **데이터로** 둔다

요청/기대응답을 fixture 데이터로 두고 테스트 코드는 그것을 읽기만 하게 한다.
데이터로 표현하기 어려운 것(멱등성, 동시성, 브라우저 컨텍스트)만 코드로 따로 쓴다.

> **2026-08-12 2차**: 종전 이 절의 제목은 "Python과 Kotlin이 **같은 표**를 읽게 만든다"였고
> 근거는 두 런타임 대조였다. Python 폐기·재개발로 대조 상대가 없어졌지만 **원칙 자체는
> 남는다** — 기대값이 코드에 흩어지면 계약이 무엇인지 테스트에서 읽어 낼 수 없다.

---

## 1. 계층별 역할

| 계층 | 도구 (Python / Kotlin) | 검증 범위 | Phase |
|---|---|---|---|
| **C. 계약(경계)** | `TestClient` / `@SpringBootTest` + MockMvc | 상태 코드, **응답 헤더**, 오류 본문 모양, 필드 이름, enum 값 | 3~ |
| **I. 통합(DB)** | pytest + 실 DB / Testcontainers PostgreSQL | 소유권 404, 409 충돌, 트랜잭션 원자성 | 3~ |
| **U. 단위** | Kotlin 단위 테스트 | HTTP 경계에서 보이지 않는 계약 규약 (Argon2 재해시 판정) | 3~ |
| **B. 브라우저** | compose + 실제 브라우저 | CORS 노출 헤더, 다운로드 파일명, 401 세션 만료 | 6~ |

**종전의 X(교차 런타임) 계층은 2026-08-12 2차 결정으로 없어졌다.** 그 계층이 존재한
이유는 롤백 창에서 Python과 Kotlin이 서로의 토큰·해시·암호문을 읽어야 한다는 것이었는데,
Python 폐기·재개발과 롤백 포기로 그 요구가 사라졌다(parity 하네스에서도 `compat` 판정
모드와 crypto·jwt·argon2 도메인이 삭제됐다). 그 자리에 있던 두 항목은 **계약 준수**
항목으로 바뀌어 C·U 계층으로 내려왔다 — X-I1·X-I2를 본다.

**성질 검증은 여전히 parity-verifier가 소유한다.** 이 문서는 "계약이 무엇을 요구하는지"의
범위만 정하고, 실행해 증명하는 일은 그쪽이 한다.

---

## 2. 엔드포인트별 기본 세트

**엔드포인트 하나당 최소 4종**을 둔다. 인증 불필요 엔드포인트는 ②를 빼고, 소유권 개념이
없는 엔드포인트는 ③을 뺀다.

① 성공 경로 (상태 코드 + **필수 헤더** + 필드 이름) · ② 인증 없음 401 ·
③ 남의 자원 404 · ④ 대표 입력 오류 422

409·413이 있는 엔드포인트는 그것도 더한다.

| # | 엔드포인트 | ① 성공 | ② 401 | ③ 404 | ④ 422 | 추가 | 고정하는 계약 조항 |
|---|---|---|---|---|---|---|---|
| 1 | `POST /auth/signup` | 201 + no-store/nosniff + `{id,email}` | — (인증 불필요) | — | 이메일 형식 · **이메일 255 초과(X-F13)** · **비밀번호 8자 미만(X-F11)** → 전부 **문자열 detail** / 필드 누락 → **배열 detail** | **409** 이메일 중복 | `paths./auth/signup`, `x-private-response-headers`, `x-request-field-constraints.fields[0]`·`[1]` |
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
| **X-C7** | **오류 본문이 경로를 가리지 않는다** — `sendError` → `/error` 디스패치로 만들어진 응답도 `{"detail": …}`이고 최상위 키 집합이 정확히 `{detail}` 하나다. `timestamp`·`status`·`error`·`path`·`trace`·`message`·`errors` **부재**를 명시 단언 | C | `x-error-body-universality` (E-1) | ⚠️ **2026-08-12 실측 — 현재 이 경로는 계약을 어긴다.** `{"timestamp":…,"status":503,"error":…,"path":…}`(Spring `BasicErrorController` 기본 본문). 지금은 운영 코드가 `sendError`를 안 불러 안 드러날 뿐이다. `detail` 존재만 보면 나머지 넷이 함께 실려도 통과한다 |
| **X-C8** | **인증 실패 401은 구현 수단을 가리지 않는다** — 어떤 방식으로 나가든 본문 `{"detail": …}` + `WWW-Authenticate: Bearer` | C | `x-error-body-universality` (E-2) | **Phase 3에서 인증 필터가 401을 `sendError`로 내는 것이 가장 흔한 구현**이다. 인증 필터 도입 커밋과 **같은 변경 단위**에 넣는다 — 나중에 넣으면 그사이 계약이 깨진 채 지나간다. 401은 React `client.ts`의 세션 만료 분기라 화면 동작까지 바뀐다 |
| **X-C9** | 컨테이너 생성 응답 7종(X-D2c)의 **본문 모양** — ⚠️ **미측정.** 이번 실측은 헤더만 봤다 | C | `x-error-body-universality.x-unmeasured` (E-4) | **"충족"으로도 "위반"으로도 적지 않는다.** 만족시킬 수 없는 자리가 남으면 계약을 좁히지 말고 리더 재심 — 헤더 쪽에서 밟은 순서와 같다 |
| **X-D1** | 고위험 성공 응답 **10곳 각각**에 캐시 금지 헤더가 있다 (하한선 — 전역 규칙이 있어도 **삭제하지 않는다**) | C | `x-private-response-headers.applies_to` | 필터가 제거되거나 체인 순서가 어긋나도 고위험 경로에서 **먼저** 깨져야 한다(리더 판정 부수 결정 1) |
| **X-D2** | ~~DELETE 204 두 곳과 `/health`, 모든 오류 응답에는 캐시 헤더가 **없다**~~ → **2026-08-12 부호 반전.** DELETE 204 두 곳·`/health`·**모든 오류 응답**(401·404·409·413·422·500·502·503)·프리플라이트에 캐시 헤더가 **있다** | C | `x-global-response-headers` | 리더 판정으로 전역 부착이 요구가 됐다. **Python은 고치지 않으므로 이 단언은 Kotlin 전용**이고, Python이 통과하지 못하는 것이 정상이다 |
| **X-D2b** | 헤더가 **하나씩만** 있고 값이 정확히 `no-store`/`nosniff`다 (`header().stringValues(...)`로 **개수까지**) | C | `x-global-response-headers.enforcement` | 필터와 `ResponseEntity`가 둘 다 실으면 `no-store, no-store`가 나가 `const` 제약 위반. 값만 보는 단언은 통과해 버린다 |
| **X-D2c** | **컨테이너 레벨 응답에도 두 헤더가 있다** — ✅ **2026-08-12 실측 완료.** 필터에 닿는 것: 핸들러 없는 404·415·413·프리플라이트 OPTIONS 200·`sendError`→`/error` 503. **필터에 못 닿는 것 7종**(요청 대상 금지 문자 400 · 콜론 없는 헤더 줄 400 · 요청 줄 파손 400 · 헤더 상한 초과 400 · `Host` 없음 400 · 알 수 없는 버전 505 · 알 수 없는 메서드 405)은 **Tomcat Engine 밸브**가 덮는다. 회귀는 `@SpringBootTest(RANDOM_PORT)` + **원시 소켓**으로 유지 — **MockMvc로 옮기면 근거가 사라진다**(측정한 것처럼 보이는 통과가 나온다) | C | `x-global-response-headers.x-phase3-measurement`, `.enforcement` | 실패 양상이 "누가 빠뜨렸는가"에서 **"강제 수단이 닿는가"**로 옮겨간 자리. **재 보니 실제로 비어 있었다** — 계약을 좁히지 않고 강제 수단을 넓혀 해결(리더 재심 불필요) |
| **X-D2d** | **밸브 음성 대조** — 밸브를 제거하면 malformed 계열 3건이 **정확히** 깨진다 | C | `x-phase3-measurement.negative_control` | 음성 대조가 없으면 "밸브가 없어도 통과하는 테스트"를 근거로 삼게 된다. 2층 강제는 어느 층이 일하는지 보이지 않아 특히 필요하다 |
| **X-D2e** | 밸브가 **Tomcat 결합**임을 아는 상태로 유지한다 — 컨테이너를 바꾸면 기동 시점에 깨진다(조용히 사라지지 않는다) | C | `x-global-response-headers.x-container-coupling` | 서블릿 표준 필터만으로는 이 조항이 만족되지 않는다. "표준이면 된다"는 오독이 이 자리에서 나온다 |
| ~~**X-D3**~~ | ~~라우터가 헤더를 적어 둔 **뒤에 예외를 던져도** 오류 응답에 헤더가 새지 않는다~~ | — | — | **폐기 (2026-08-12).** 전역 부착으로 "헤더가 새는 것"이 더는 위반이 아니다. Python 기준선의 `test_errors.py::_app_raising_after_private_headers`는 Python 쪽 기술로 남지만 Kotlin 계약 단언이 아니다 |
| **X-D4** | `POST /documents` 202의 `Location` 값이 **본문 `conversion_id`와 같다** | C | `paths./documents.post` 응답 헤더 | 두 값이 갈리면 폴링이 엉뚱한 자원을 본다 |
| **X-D5** | `Content-Disposition`에 ASCII `filename=`과 RFC 5987 `filename*=UTF-8''`가 **둘 다** 있고, 한글 제목이 퍼센트 인코딩돼 `filename*`에 실린다 | C | `paths..../export` 응답 헤더 | 헤더 값은 latin-1만 담는다. React `parseFilename`이 `filename*`만 읽는다 |
| **X-D6** | `txt` 내보내기의 미디어 타입에 `charset=utf-8`이 **있다** | C | `paths..../export` 200 content | 없으면 브라우저가 로캘 기본 인코딩으로 열어 한글이 깨진다 |
| **X-E1** | 응답 JSON의 모든 최상위·중첩 필드 이름이 **snake_case**다 (전 엔드포인트 스냅샷 대조) | C | `info.description`의 필드 이름 절 | **Jackson 기본 네이밍 전략이 camelCase로 바꾸는 사고가 잦다.** 개별 단언이 아니라 키 집합 전체를 계약과 대조한다 |
| **X-E2** | nullable 필드가 **키째 생략되지 않는다** — `ConversionResponse` 13필드·`DocumentListItem` 9필드가 완료 전 상태에서도 전부 존재한다 | C | `components/schemas/ConversionResponse` required | Jackson `NON_NULL`이면 React가 `null` 대신 `undefined`를 받아 분기가 달라진다 |
| **X-E3** | `masked_items`·`missing_placeholders`가 완료 전에 `null`이 아니라 `[]`다 | C | 같음 | 위와 같은 이유 |
| **X-E4** | `status` 값이 4개 집합 밖으로 나가지 않는다 (모든 상태 전이 경로에서) | C+I | `components/schemas/ConversionStatus` | DB CHECK 제약과 같은 집합이다 |
| **X-F1** | 4,000자 초과 본문 → 422 / 정확히 4,000자 → 통과 (**경계값 양쪽**) + **`detail`이 문자열이다** | C | `x-input-limits.max_convertible_chars`, `x-request-field-constraints` | 경계를 한쪽만 걸면 off-by-one이 남는다. **2026-08-15 F3 개정** — 상태 코드만 보면 `@Size`로 구현해 배열 `detail`을 내도 통과한다 |
| **X-F2** | 검수 수정본도 같은 4,000자 상한 + **`detail`이 문자열이다** | C | `.max_review_chars`, `x-request-field-constraints` | 상한이 갈리면 사용자에게 설명할 수 없다. **F3 개정** — 위와 같은 이유 |
| **X-F9** | **정규화 후 경계** — 원시 길이는 상한 초과이나 정규화 후에는 이하인 입력이 **통과한다**. 실측 3케이스: `email` 원시 260(앞 공백 10)→250 · `edited_text` 원시 4,010(제어문자 11)→3,999 · `name` 원시 55(제어문자 10)→45 | C | `x-request-field-constraints.fields[].measured_on` | **F3 신설.** 이 단언이 없으면 `@Size` 구현이 전부 통과한다 — 계약이 코드보다 엄격했던 자리가 그대로 재발한다. 세 케이스가 곧 F3의 실측 증거다 |
| **X-F10** | `WorkspaceNameRequest.name`이 `"   "`(공백만)를 **422 문자열** `"작업 공간 이름을 입력해 주세요"`로 거절한다 | C | 같음 (`non_empty: true`) | **F3 신설.** 옛 `minLength: 1`이 이것을 **통과**시켰다 — 한 필드가 양방향으로 갈렸던 자리의 반대쪽이다 |
| **X-F11** | **`SignupRequest.password` 하한 — 8자 미만 → 422이고 `detail`이 문자열 타입이다**(배열이 아님을 명시 단언) / 정확히 8자 → 통과 (**경계값 양쪽**) | C | `x-request-field-constraints.fields[1]`(`:392-396`) · `SignupRequest.password.x-service-constraint`(`:1674`) · `components/responses/ValidationFailed`(`:1516-1518`) | **게이트 15 X5 신설.** F3 판정 뒤에도 `password`를 덮는 행이 이 표에 **하나도 없었다** — 하한을 아예 구현하지 않아도, `@Size(min=8)`로 구현해 배열 `detail`을 내도 전건 통과한다. 계약은 그 구현을 **계약 위반**이라 부르고 "contract test가 `detail`의 **타입까지** 단언한다"고 스스로 적어 두었는데(`:1516-1518`) 그 단언의 자리가 없었다. `password`는 X-F9(정규화 후 3필드)에도 X-F1(`text`)에도 들어가지 않아 **다른 어느 항목도 대신 덮지 못한다** |
| **X-F12** | **원시 측정의 반대 축** — 앞 공백 7자 + 문자 1자(원시 8자 · trim 후 1자)인 비밀번호가 **통과한다** | C | `fields[1].measured_on`(`:395` — "원시 값 — 정규화하지 않는다") · `x-service-constraint.measured_on: raw`(`:1674`) | **게이트 15 X5 신설.** X-F9는 *정규화 후*를 재는 3필드를 고정하고 이 항목은 *원시*를 재는 필드를 고정한다. **한쪽만 있으면 구현이 다섯 필드에 같은 측정 기준을 일괄 적용해도 아무 데서도 안 걸린다** — 자리마다 실제 강제 지점을 확인해 갈라 놓은 F3의 판정(`x-contrast-case` `:377-384`)이 통째로 무너지는 경로다. ⚠️ 이 행의 기대값은 **게이트 15 X13(리더 판정 대기)에 종속**된다 — `measured_on`이 바뀌면 이 행도 함께 바뀐다. **바뀌기 전까지는 현행 조항이 기준이다** |
| **X-F13** | **`SignupRequest.email` 상한의 거절 쪽** — 정규화 후 255자 초과 → 422 + **문자열** `detail` / 정확히 255자 → 통과. `detail` 문구가 **형식 오류와 같은 문자열**임도 함께 단언한다 | C | `fields[0]`(`:387-391`, `detail` 주석: "길이 초과와 형식 오류가 같은 문구다(입력값 비반향)") · `SignupRequest.email`(`:1663-1670`) | **게이트 15 X5 · 1회차 C-B.** 현재 `email` 255는 **통과 쪽만**(X-F9의 원시 260→250) 덮여 있어 **상한을 아예 구현하지 않아도 전건 통과한다.** 문구를 갈라 두는 구현("이메일이 255자를 초과합니다")도 계약 위반이다 — 계약이 두 위반에 같은 문구를 지정한 것 자체가 조항이므로 **문구의 동일성까지** 본다 |
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
| **X-I1** | **JWT 계약 준수** — `sub`/`exp`/`typ`만 실린 HS256 토큰을 수용하고, `typ` 오값·`exp` 누락·서명 위조·**만료 직후**(`exp`+1초) 토큰을 401로 거부한다 | C | `x-auth.claims`, `.required_claims`, `.clock_skew_seconds` | **clock skew 0**. Nimbus·Spring 기본 60초를 그대로 두면 만료 토큰이 +59초까지 통과한다(Phase 0 실측 결함 — 필수조치 B) |
| **X-I2** | **Argon2 재해시 판정** — 저장된 PHC의 파라미터가 현재 설정과 **하나라도 다르면** 로그인 성공 시 재해시된다(변형·`time_cost`·`memory_cost`·`parallelism` 전체 비교) | U | `x-auth.rehash_policy` | HTTP 경계에서 안 보이므로(로그인 응답이 같다) 인코더 계층 단위 테스트다. Spring `upgradeEncoding()`의 "미만" 비교만 쓰면 **낮춘 경우와 `parallelism` 변경**을 놓친다(필수조치 A) |

> **X-I1·X-I2는 2026-08-12 2차 재작성이다.** 종전 두 항목은 "Python 발급 JWT를 Kotlin이
> 수용하고 **그 반대도** 된다"(양방향)와 "기존 PHC가 **그대로** 검증된다"(호환)였다.
> 두 요구 모두 **롤백 창**에서만 나왔고, Python 폐기·재개발과 롤백 포기로 근거가 사라졌다.
> **없어진 것은 "Python과 같은가"이지 "올바른가"가 아니다** — 그래서 지우지 않고
> 계약 준수 항목으로 바꿔 달았다. 계층도 **X(교차 런타임) → C·U**로 내려온다.
> 두 항목을 함께 지우면 Phase 0에서 실측으로 찾은 결함 둘이 검증 없이 되살아난다.

> **X-F11·X-F12·X-F13은 2026-08-15 신설이다 (게이트 15 X5 / 1회차 C-B).** F3 판정
> (2026-08-13)이 다섯 필드를 서비스 층으로 확정했는데, 그때 함께 만든 검증은 X-F9·X-F10과
> X-F1·X-F2의 `detail` 타입 단언까지였다. **다섯 중 `password`는 어느 행에도 들어가지
> 않았고 `email`은 통과 쪽만 들어갔다.** 아래가 F3 다섯 필드의 커버리지이고, 이 표가
> 비면 그 자리가 곧 회귀 통로다.
>
> | F3 필드 (`x-request-field-constraints.fields`) | 거절 쪽 | 통과 쪽 | 측정 축(원시/정규화) |
> |---|---|---|---|
> | `[0]` `SignupRequest.email` | **X-F13** (신설) | X-F13 · X-F9 | X-F9 (정규화 후) |
> | `[1]` `SignupRequest.password` | **X-F11** (신설) | **X-F11** (신설) | **X-F12** (신설, 원시) |
> | `[2]` `DocumentTextRequest.text` | X-F1 | X-F1 | — (원시 · X13 미결) |
> | `[3]` `ConversionReviewRequest.edited_text` | X-F2 | X-F9 | X-F9 (정규화 후) |
> | `[4]` `WorkspaceNameRequest.name` | X-F10 | X-F9 | X-F9 (정규화 후) |
>
> **근거 표기 규약** — 이 세 행의 「고정하는 계약 조항」 열은 **키 경로 + 행 번호(2026-08-15
> 실측)**를 함께 적었다. 키 경로만으로는 지금 당장 대조가 안 되고, 행 번호만 적으면 파일이
> 움직이는 순간 틀린 값이 된다(§0 머리). **계약 문구·상한 값·`detail` 전문은 여기에 옮겨
> 적지 않는다** — 게이트 15 별건 1이 실측한 그대로, 손으로 옮긴 계약 요약은 원본과 갈린다.
> 기대값은 **X-J2(계약 파일 직접 파싱)**로 계약에서 읽는다. X-F11의 `detail` 문자열/배열은
> **타입 단언**이라 값 복제가 필요 없고, X-F13의 문구 동일성은 계약에서 읽은 두 값을 서로
> 비교하는 형태로 쓴다.

---

## 3-1. 조항 미확정 항목 (2026-08-13 등록 — 아직 테스트를 쓸 수 없다)

**이 절의 넷은 §3의 다른 항목과 성격이 다르다.** 위 표의 항목들은 계약 조항이 있고 그것을
고정하는 테스트를 정의한 것이지만, **아래 넷은 고정할 조항 자체가 아직 없다.** 그래서 "무엇을
단언할 것인가"가 아니라 **"조항이 정해지면 무엇을 단언해야 하는가"**를 적어 둔다.

조항 없이 테스트를 먼저 쓰면 그 테스트가 사실상 계약이 되고, 그것은 계약 소유자가 아니라
테스트 작성자가 계약을 정하는 것이다. 반대로 아무것도 적지 않으면 조항이 확정될 때 검증이
따라오지 않는다. 그 사이에 두는 것이 이 절이다.

등록 근거·미결 질문 전문은 `00_contract-keeper_changelog.md`
「2026-08-13 · OQ-2 · OQ-3 · OQ-4 · OQ-5」. 출처는
`reviews/07_core-rebuild_cross.md`(X-10·X-12·X-13)와
`02_parity-verifier_conversion-spec.md` §6 갈림 후보 ②(+ `00_requirements-inventory.md` §9-E).

| ID | 조항이 정해지면 단언할 것 | 계층 | 미결 항목 | 확정 Phase | 지금 쓸 수 없는 이유 |
|---|---|---|---|---|---|
| **X-J1** | 내보내기에서 **`withheld`·`ambiguous`가 비어 있지 않을 때의 처분** — 409로 막는다면 상태 코드와 `detail` 문구, 200으로 내보낸다면 응답이 그 사실을 알리는 형태 | C | **OQ-2** | **4** | 계약의 409는 현재 `missing_placeholders` 한 갈래뿐이다. 기대값이 없다 |
| **X-J2** | **계약 값을 테스트가 계약 파일에서 읽는다** — 자리표시자 pattern·`category` enum·`status` enum을 하드코딩하지 않는다 (음성 대조: 계약 파일의 값을 바꾸면 테스트가 깨진다) | C | **OQ-3** | **3** | 조항이 아니라 **도달**의 문제다. 기제(직접 파싱 / 생성 산출물)가 정해지면 즉시 쓸 수 있다 |
| **X-J3** | **마스킹 본문 채널**(가칭 `masked_text` — 이름 미정) — 계약화하면 그 필드의 존재·타입·pattern, 계약 밖으로 두면 `easy_text`에 탈출 표기가 실려도 위반이 아님을 명시. `masked_items[].original`은 **이미 계약에 있다**(공백 아님) | C | **OQ-4** | **4** | 필드가 계약에 없다. 이름조차 정해지지 않았다 |
| **X-J4** | **`failure_code` 값 집합** — 닫힌 집합 밖 값이 나오지 않고, 집합의 각 값에 React `failureMessages.ts` 항목이 대응한다(양방향 — 계약에 있는데 React에 없는 값도 잡는다) | C+B | **OQ-5** | **5** | 현행 조항이 "예외 클래스명"이라는 규칙이라 **열거할 집합이 없다.** 지금 쓰면 Python 클래스 이름을 테스트에 굳히게 된다 |

> **X-J4의 양방향 단언이 요점이다.** React는 모르는 코드를 `UNKNOWN`으로 조용히 삼키므로
> (`frontend/src/conversion/failureMessages.ts:66-71`), 한쪽만 보는 단언은 **문구가 뭉개지는
> 회귀를 통과시킨다.** `LLMTruncatedError`의 `retryable: false`가 `UNKNOWN`의 `true`로
> 뒤집히면 사용자에게 "문서를 나눠 올리세요" 대신 "다시 시도하세요"가 나간다 — 크래시가
> 없어 기존 테스트로는 잡히지 않는다.

> **2026-08-15 현행화 — Phase 3 착수.** 넷 중 **X-J2만 지금 마감**이고 나머지 셋은 그대로다.
> X-J2의 미결 질문(직접 파싱 / 생성 산출물)은 **계약 소유자가 닫는다: 직접 파싱이다.**
> 생성 산출물 기제는 Phase 6(OpenAPI → React 타입 교체)에서야 생기는데 계약 값을 복제한
> Kotlin 테스트는 **Phase 3에서 지금 쓰인다** — 그때까지 기다리면 복제본이 세 Phase만큼
> 쌓이고, 그것을 되돌리는 비용이 지금 파서를 붙이는 비용보다 크다. **계약이 요구하는 것은
> "테스트가 계약 파일을 읽는다"까지이고, 파서·계층 선택은 구현 판단이다.**
> 음성 대조를 함께 요구한다 — 계약 파일의 값을 바꿨을 때 테스트가 **실제로 깨지는지**
> 확인하지 않으면 "읽기는 하는데 단언에 쓰지 않는" 배선이 통과한다(X-1이 그 형태였다).
> **Phase 3에서 새로 쓰는 테스트에 계약 값을 더 복제하지 않는 것**이 최소 요구다.

> **X-J1·X-J3은 함께 판정한다.** 탈출 표기(`[[!주민등록번호1]]`)를 계약 어휘로 승격하면
> 검수 화면이 그것을 설명할 수 있고, 그러면 X-J1이 막으려는 `ambiguous` 유입 경로 하나가
> 줄어든다. 따로 정하면 두 조항이 서로를 모른 채 결합한다 — 커밋 `8412b89`가 진단한
> Python 결함(`services`의 본문 선택과 `export`의 복원이 서로를 몰랐다)과 같은 형태다.

---

## 4. Python 선례 커버리지와 **공백**

Phase 3에서 Kotlin 테스트를 쓸 때 "이미 선례가 있어 조항이 확실한 것"과 "선례가 없어
계약 파일만이 근거인 것"을 가른다. 조사 시점 `tests/api/` 134건 기준.

> **2026-08-12 2차**: 종전 제목은 "Python **기준선** 커버리지"였다. Python 폐기·재개발로
> 그 suite는 남지 않으므로 **기준선이 아니라 연혁**이다. 이 절의 값어치는 "Kotlin이 Python
> 테스트를 따라가야 한다"가 아니라 **어느 조항이 실측 선례 없이 계약 파일에만 있는지**를
> 드러내는 데 있다 — 공백 쪽이 이 절의 본론이다.

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
> ~~다만 Python 테스트 추가는 이 에이전트의 범위 밖(`tests/`는 읽기 전용)이므로,
> **리더에게 판단을 올린다** — Phase 0에서 Python에 2건을 먼저 채울지, Phase 3에서
> Kotlin·Python 양쪽에 동시에 넣을지.~~
>
> **2026-08-15 현행화 (Phase 3 착수) — 리더 판단 불필요해졌다.** 두 선택지 중 하나가
> 사라졌다: Python은 **폐기 대상**이므로 거기에 테스트를 새로 넣지 않는다. G-1은
> **Kotlin 쪽 X-D1 한 갈래로만** 닫힌다. 대상 2곳(`POST /workspaces` 201 ·
> `PATCH /workspaces/{id}` 200)은 Phase 3에서 **작업 공간 엔드포인트를 구현하는 바로 그
> 커밋**에 단언이 함께 들어가야 한다 — 나중에 넣으면 그사이 회귀가 지나간다.
> 전역 부착(OQ-1)이 있어도 **X-D1은 삭제하지 않는다**(리더 판정 부수 결정 1):
> 필터가 빠지거나 체인 순서가 어긋났을 때 고위험 경로에서 **먼저** 깨져야 한다.

---

## 5. 실행 시점과 게이트 대응

| Phase | 무엇을 돌리나 | 계획 §6 게이트 |
|---|---|---|
| 0 (지금) | **아무것도 실행하지 않는다.** 계약 파일과 이 목록을 만든다 | — |
| 2 | **이 문서가 소유한 항목 없음** (2026-08-12 2차) — 종전 X-I1·X-I2가 여기 있었으나 계약 준수 항목이 되어 Phase 3으로 옮겼다. Crypto 게이트의 나머지 증거는 privacy-gate·parity-verifier 소관이다 | Crypto |
| 3 | #1·#2·#3 **+ #10~#13(작업 공간)** + X-A*·X-B*·X-C*·X-D1·X-D2·X-D3·X-E1 + **X-I1·X-I2** + **X-J2**(직접 파싱 — 위 현행화) + **X-F10**(작업 공간 이름 공백) + **X-F11·X-F12·X-F13**(signup 두 필드 — 2026-08-15 신설) + **X-F9의 `email` 케이스**(아래 단서) | Contract, DB |
| 4 | #4~#9 + X-D4·X-D5·X-D6·X-E2~X-E4·X-F*(**X-F9 포함**)·X-G* + **X-J1·X-J3**(OQ-2·OQ-4 조항 확정 후 — 함께 판정) | Contract, Document, Security |
| 5 | #4의 502/503 경로 (큐 전환 후 재확인) + **X-J4**(OQ-5 값 집합 확정 후) | Worker |
| 6 | #10~#14 + X-H1~X-H4 + **X-H5·X-H6(브라우저)** | E2E |
| 7 | 전체 재실행 (절체 리허설) | Ops |

**Contract 게이트 통과 기준**(계획 §6): status / body / header / error가 v1 spec과 일치.
위 표의 C 계층 항목이 전건 통과해야 한다.

> **X-J*는 "통과"로도 "실패"로도 세지 않는다** — 조항이 없으니 판정 대상이 없다.
> 대신 해당 Phase 게이트를 닫을 때 **조항이 확정됐는지를 먼저 묻는다.** 미확정인 채로
> Phase를 닫으면 그 조항은 영영 검증되지 않는다(X-C9에서 밟은 것과 같은 처리다).
> **X-J2는 2026-08-15에 조항이 확정됐으므로(직접 파싱) 이제 정상 판정 대상이다.**

> **2026-08-15 Phase 3 착수 현행화 — 이 표의 Phase 열을 고쳤다.** 작업 공간
> 엔드포인트(#10~#13)가 Phase 3 범위(인증·작업 공간)에 들어오면서 종전 Phase 6 배치가
> 실물과 어긋났다. `/health`(#14)는 이미 `HealthContractTest`로 덮여 있어 Phase 6에
> 남은 것은 브라우저 계층(X-H5·X-H6)과 CORS 실동작이다.

> **단서 — X-F9는 한 Phase에 다 실리지 않는다 (2026-08-15).** X-F9의 실측 3케이스는
> `email`(#1 signup) · `edited_text`(#8) · `name`(#11·#12)에 흩어져 있는데, signup·작업
> 공간은 **Phase 3**이고 검수 저장은 **Phase 4**다. `email` 케이스만 Phase 4로 미루면
> **X-F13(거절 쪽)과 X-F9(통과 쪽)가 갈라져** 한 Phase 동안 상한이 한쪽만 고정된다 —
> 경계를 한쪽만 거는 것을 금지하는 X-F1의 이유가 그대로 적용되는 자리다. 따라서 X-F9의
> `email`·`name` 케이스는 Phase 3에서, `edited_text` 케이스는 Phase 4에서 돈다.
> Phase 4 행의 "X-F9 포함"은 남은 `edited_text` 케이스를 가리킨다.

---

## 6. parity-verifier에게 넘기는 범위

계약상 **반드시 같아야 하는 것**의 목록이다. parity 검증이 "값이 같은지"를 다루므로
이쪽이 범위를 정해 준다.

- **응답 필드 이름 전체 집합** (엔드포인트별) — snake_case, 키 생략 없음
- **`status`의 4개 값**, **`ExportFormat`의 3개 값**
- **`detail`의 타입(문자열 / 배열)** — 2026-08-15 F3. 요청 본문 필드의 길이·형식·빈 값
  위반은 **문자열**, 스키마 층(쿼리 파라미터 범위·필드 누락·타입 불일치)은 **배열**이다.
  상태 코드가 같아 타입을 안 보면 갈린 것을 놓친다. 정본 `x-request-field-constraints`
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
| `kotlin-implementer` | 정본 계약은 `contracts/easy-doc-v1.yaml`. 특히 **X-A3(인증 우선순위)**, **X-C2(`input` 금지)**, **X-E1/E2(snake_case·키 생략 금지)** 셋이 Spring 기본값과 충돌한다. 헤더는 **X-D2로 부호가 반전됐다** — 오류 응답에 캐시 헤더가 **있어야** 하고 수단은 **필터 + Tomcat Engine 밸브 2층**이다(둘 다 `add`가 아닌 `set`, X-D2b). **X-D2c는 2026-08-12 실측 완료** — 회귀는 원시 소켓으로 유지한다. **신규: X-C7·X-C8(오류 본문이 경로를 가리지 않는다)** — Phase 3 인증 필터가 401을 `sendError`로 내면 계약이 깨진다. 계약이 정하는 것은 **나간 바이트**뿐이고 수단 선택은 구현 판단이다 |
| `parity-verifier` | §6의 범위. 오류 `detail` 문구는 형식이 아니라 **전문 일치**가 계약이다. **응답 헤더는 두 런타임이 의도적으로 다르다**(Kotlin 전역 / Python 10곳) — 불일치로 올리지 않는다. **반대로 오류 본문 모양(X-C7·X-C8)은 의도된 차이가 아니다** — Kotlin이 `{"detail": …}` 밖의 본문을 내면 그것은 기록 대상이 아니라 차단 대상이다 |
| 리더 | **G-1**(Python에도 없는 캐시 헤더 테스트 2건)의 처리 시점 판단. ~~**U-1**~~·~~**OQ-1**~~·~~**X-D2c**~~ 모두 2026-08-12 판정·실측 완료. **X-C9(컨테이너 생성 응답의 본문 모양)가 미측정이며, 측정 결과 만족시킬 수 없으면 재심 대상** |

**2026-08-13 추가 — §3-1(X-J1~X-J4) 관련 통보**

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | **X-J2 공동 담당**(OQ-3) — 계약 값을 테스트가 계약 파일에서 읽는 기제. 지금 새로 쓰는 Kotlin 테스트에 계약 값을 **더 복제하지 마라**(복제가 늘수록 기제 전환 비용이 는다). **X-J4 전제**(OQ-5) — `failure_code` 값 집합이 확정되기 전에는 Python 예외 클래스 이름(`LLMTruncatedError` 등)을 Kotlin 예외 이름이나 와이어 값으로 굳히지 않는다 |
| `parity-verifier` | **X-J4**: `failure_kind` 3종은 계약 확정 전까지의 임시 이름이다. 확정 시 fixture를 재생성하되 **실제 값은 다섯**(`ProviderUnavailable`·`EnqueueFailed` 포함)이라는 점을 함께 판정한다. **X-J3**: 마스킹 본문 채널은 현재 계약 밖이라 그 채널의 차이를 계약 위반으로 올릴 근거가 없다 |
| 리더 | **X-J1**(내보내기 409 추가는 사용자에게 보이는 동작 변경)과 **X-J4**(React 런타임 `failureMessages.ts` 의존)가 계약 소유자 단독 판정 범위 밖이다 |

**2026-08-15 추가 — X-F11·X-F12·X-F13(게이트 15 X5) 통보**

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | **다섯 필드에 `@Size`/`@NotBlank`/`@Email`을 쓰지 마라**(F3). 전파본은 `03_kotlin-implementer_phase3-preflight.md` §5 — 금지 내용·계약 근거(파일·행)·걸리는 검증이 거기 연결돼 있다. **Phase 3 첫 작업이 그 다섯 DTO 중 셋**(`SignupRequest.email`·`.password`, `WorkspaceNameRequest.name`)이라 착수 후 첫 커밋에서 걸린다. 신규 세 행은 signup DTO를 **구현하는 그 커밋**에 함께 들어간다 — 나중에 넣으면 그사이 배열 `detail`이 지나간다. 기대값은 하드코딩하지 말고 X-J2(계약 파일 직접 파싱)로 읽는다 |
| `parity-verifier` | `password`·`email`의 `detail` **전문 일치**가 계약이다(다섯 필드 공통). 추가로 `email`은 **길이 초과와 형식 오류의 문구가 서로 같아야** 한다(`fields[0].detail` 주석) — 두 값이 갈리면 불일치로 올린다 |
| 리더 | **X-F12의 기대값이 게이트 15 X13 판정에 종속된다.** `password`의 `measured_on: raw`를 유지하면 현행대로, 정규화 후로 바꾸면 X-F12와 계약 `:395`·`:1674`를 같은 변경 단위로 고친다. **계약 소유자는 현행 조항을 유지한 채 검증만 채웠다** — 조항을 바꾸는 판정은 X13에 걸려 있고, 그것을 검증 신설로 앞질러 확정하지 않았다 |
