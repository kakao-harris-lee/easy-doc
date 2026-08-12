# Phase 1 리뷰 차단 항목 수정 — C-1(프레임워크 예외 500) · C-2(계약 테스트 경계) · C-3(CORS 미구현)

**작성:** kotlin-implementer / 2026-08-12
**입력:** `reviews/01_skeleton_cross.md`(정본, §3.4 C-1·C-2·C-3 / §4.5), `reviews/01_skeleton_migration-reviewer.md`(1차 §2)
**대상:** `backend-kotlin/api/` 만. `app/`·`tests/`·`frontend/`·`.claude/`·`contracts/` 는 읽기만 했고,
`.github/workflows/ci.yml`·parity 하네스 Gradle 태스크는 열지 않았다(동시 작업 중).

---

## 0. 한눈에

| 항목 | 상태 | 증거 |
|---|---|---|
| C-1 프레임워크 예외가 전부 500 | **닫힘** | 살아 있는 두 컨테이너 대조 §2, HTTP 경계 회귀 테스트 9건 §3 |
| C-2 계약 테스트가 HTTP 경계를 안 봄 | **닫힘** | `ErrorContractTest` 를 MockMvc 로 이전, 고치기 전 8건 실패 → 고친 뒤 전건 통과 §3 |
| C-3 CORS 미구현 | **닫힘** | `CorsConfig` + `CorsContractTest` 10건, 노출 헤더 preflight·실요청 양쪽 확인 §4 |
| U-1 미처리 500 의 CORS 헤더 | **미결 — 리더에게 넘김** | 측정값과 두 선택지의 비용 §5 |

**검사**: `./gradlew build` BUILD SUCCESSFUL, **tests=75 failures=0**(이전 48 + 신규 27, 기존 48건 전건 유지).
Python 게이트 무손상 — `uv run ruff check .` All checks passed / `uv run mypy .` no issues (116 files) /
`uv run pytest` **820 passed, 68 skipped, 4 deselected**.

---

## 1. C-1 — 무엇이 문제였고 어디까지 프레임워크에 위임했는가

### 1.1 원인

`GlobalExceptionHandler` 는 `@ExceptionHandler(Exception::class)` 백스톱 하나만 갖고 있었다.
`@RestControllerAdvice` 안의 그 백스톱은 **Spring MVC 가 스스로 던지는 예외까지 전부 가로챈다.**
`ResponseEntityExceptionHandler` 를 상속하지 않았으므로 `NoResourceFoundException`(404)·
`HttpRequestMethodNotSupportedException`(405)·`HttpMediaTypeNotAcceptableException`(406)이 모두
"도메인 밖 예외"로 분류돼 **500 + `{"detail":"서버 오류가 발생했습니다"}`** 가 됐다.

### 1.2 어디까지 프레임워크에 위임했는가 — 판단 기준

세 층으로 갈랐다. 기준은 **"Python 의 관측 가능한 동작을 재현하는 데 필요한 최소 개입"** 이다.

| 층 | 처리 | 왜 이 선택인가 |
|---|---|---|
| **상태 코드** | `ResponseEntityExceptionHandler` 에 **전부 위임** | 404·405·400 같은 값은 프레임워크가 라우팅 정보(허용 메서드 목록 등)를 갖고 있어야 정확히 낼 수 있다. 우리가 다시 만들면 `Allow` 헤더 같은 부수 정보가 빠진다 |
| **본문** | **전부 우리가 덮는다** (`createResponseEntity` 한 곳) | 상위 구현은 RFC 9457 `ProblemDetail`(`type`·`title`·`status`·`instance` + `application/problem+json`)을 만든다. 계약은 `{"detail": ...}` 하나 + `application/json` 이다 |
| **`detail` 문구** | 상태 코드의 **표준 사유 문구**만 쓴다 | `ProblemDetail.detail` 은 예외 메시지에서 유도되는 경우가 많아 **요청 본문 조각(파싱 실패 위치, 거절된 값)이 실릴 수 있다.** 사유 문구는 그런 경로가 없고, 동시에 Python(Starlette `HTTPException` 기본 detail)이 내는 `"Not Found"`·`"Method Not Allowed"` 와 **정확히 같은 값**이다 |

구현 지점은 `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt` 하나이며,
갈라지는 곳은 `createResponseEntity` 한 메서드다.

```kotlin
.body(body as? ContractErrorBody ?: ErrorResponse(reasonPhraseOf(statusCode)))
```

`ContractErrorBody` 는 `ErrorResponse`(문자열 detail)와 `ValidationErrorResponse`(배열 detail)의
sealed 표식이다. 우리가 만든 본문이면 그대로 두고, 상위가 만든 `ProblemDetail` 이면 계약 형태로 바꾼다.
**이 한 줄이 "상태 코드는 위임, 본문은 우리 것"의 경계다.**

### 1.3 위임하지 **않은** 것 두 가지

**(a) 내용 협상 자체를 끈다** — `WebMvcConfig`(신규)

`Accept: application/xml` 케이스는 예외 매핑만으로는 Python 과 같아지지 않는다.
`ResponseEntityExceptionHandler` 를 붙이면 500 → **406** 이 되는데, Python 은 **200** 이다.
FastAPI/Starlette 는 `Accept` 를 보고 표현을 고르지 않기 때문이다(`JSONResponse` 는 언제나 JSON).

```kotlin
configurer.ignoreAcceptHeader(true).defaultContentType(MediaType.APPLICATION_JSON)
```

Phase 4 내보내기(docx/pdf/hwpx)는 컨트롤러가 `ResponseEntity` 에 `Content-Type` 을 직접 적어 내보내므로
영향받지 않는다 — 명시된 Content-Type 은 협상보다 우선한다.

**(b) 검증 실패는 422 + 배열로 되돌린다**

Spring 기본값은 400 + `ProblemDetail` 이지만 계약(`easy-doc-v1.yaml` `ValidationFailed`)은
**422 + `[{loc, msg, type}]`** 이다. 리뷰 C-1 이 "Phase 3 에서 더 나빠진다"고 지목한 지점이라,
프레임워크 예외를 표준 매핑으로 되돌리는 김에 함께 맞췄다. 되돌리기만 하면 500 → 400 이 되어
**여전히 계약 위반**이고, 그 위에 Phase 3 컨트롤러가 쌓인다.

되돌린 예외 6종: `MethodArgumentNotValidException` / `HandlerMethodValidationException` /
`HttpMessageNotReadableException` / `MissingServletRequestParameterException` /
`MissingServletRequestPartException` / `TypeMismatchException`.

**입력값 에코는 전 경로에서 차단했다.** `FieldError.rejectedValue` 와
`MethodArgumentTypeMismatchException.message`(거절된 값 포함)를 읽지 않는다.
회귀 단언: `FrameworkErrorContractTest` 의 `쿼리 파라미터 형식 오류는 422 배열이다` 가
응답 본문에 입력값(`열개`)이 없음을 확인하고, `Bean Validation 실패는 422 배열이다` 가
`detail[].input`·`detail[].ctx` 부재를 확인한다.

### 1.4 `msg`·`type` 문자열은 Pydantic 과 바이트 동일할 수 없다 — 명시

| 케이스 | Python(pydantic) | Kotlin | 판단 |
|---|---|---|---|
| 필수 쿼리 누락 | `msg="Field required"`, `type="missing"` | **동일** | 맞춤 |
| 깨진 JSON | `msg="JSON decode error"`, `type="json_invalid"`, `loc=["body", 9]` | `msg`·`type` 동일, `loc=["body"]` | **문자 위치(9)는 재현하지 않는다** — 요청 본문의 오프셋이라 본문 구조를 노출하고, 파서가 달라 같은 수가 나오지 않는다 |
| 타입 불일치 | `type="int_parsing"`, `msg="Input should be a valid integer, unable to parse string as an integer"` | `type="int_parsing"`, `msg="Input should be a valid integer"` | 토큰은 맞추고 문구는 앞부분만 |
| Bean Validation | `type="string_too_long"` 등 pydantic 어휘 | `Size` → `size` 처럼 Bean Validation 코드의 snake_case | **검증 엔진이 달라 어휘가 대응하지 않는다.** 필수값 누락(`NotNull`/`NotBlank` → `missing`)만 명시 매핑 |

계약이 동결한 것은 **상태 코드 422 · 키 구성 `loc`/`msg`/`type` · `input`·`ctx` 부재**이고
문구는 그 아래다. 이 판단이 틀렸다면 `contract-keeper` 판정을 따르겠다.

---

## 2. Python ↔ Kotlin 응답 대조표 (살아 있는 두 컨테이너, 2026-08-12)

Python `easy-doc-api-1`(8000) / Kotlin `easy-doc-kotlin-api-1`(8100) 동시 기동.
재현 스크립트는 `curl` 16 케이스이며 상태 코드 · 본문 · 선별 헤더를 나란히 찍는다.

### 2.1 C-1 대상 (고치기 전 → 고친 뒤)

| 요청 | Python | Kotlin **고치기 전** | Kotlin **고친 뒤** | 판정 |
|---|---|---|---|---|
| `GET /nope` | `404` `{"detail":"Not Found"}` | `500` `{"detail":"서버 오류가 발생했습니다"}` | `404` `{"detail":"Not Found"}` | **일치** |
| `POST /health` | `405` + `allow: GET` `{"detail":"Method Not Allowed"}` | `500` (Allow 없음) | `405` + `Allow: GET` `{"detail":"Method Not Allowed"}` | **일치** |
| `GET /health` `Accept: application/xml` | `200` `{"status":"ok"}` | `500` | `200` `{"status":"ok"}` | **일치** |

### 2.2 CORS (고친 뒤)

| 요청 | Python | Kotlin | 판정 |
|---|---|---|---|
| `GET /health` + 허용 Origin | `200` / `access-control-allow-origin: http://localhost:5173` / `access-control-expose-headers: Content-Disposition, Location` / `vary: Origin` | `200` / `Access-Control-Allow-Origin: http://localhost:5173` / `Access-Control-Expose-Headers: Content-Disposition, Location` / `Vary: Origin, …` | **일치**(Vary 폭만 다름) |
| `OPTIONS /health` preflight | `200` / allow-methods `GET, POST, PUT, PATCH, DELETE` / max-age `600` / allow-headers 5개 / allow-origin | `200` / allow-methods `GET,POST,PUT,PATCH,DELETE` / max-age `600` / allow-headers `authorization, content-type` / allow-origin | **기능 일치**(§4.3) |
| `OPTIONS /auth/login` preflight | 위와 같음 | 위와 같음 | **기능 일치** |
| `GET /nope` + Origin | `404` + CORS 헤더 | `404` + CORS 헤더 | **일치** |
| `POST /health` + Origin | `405` + `allow: GET` + CORS 헤더 | `405` + `Allow: GET` + CORS 헤더 | **일치** |

### 2.3 남은 차이 (전부 의도적이거나 이번 범위 밖 — 아래에 근거)

| # | 요청 | Python | Kotlin | 성격 |
|---|---|---|---|---|
| D-1 | `GET /health/` (뒤 슬래시) | `307` → `/health` | `404` `{"detail":"Not Found"}` | **범위 밖 · 판단 넘김** (§6.1) |
| D-2 | `GET /health` + 비허용 Origin | `200` + 본문 (allow-origin 없음) | `403` `Invalid CORS request` | **의도적** (§4.4) |
| D-3 | `OPTIONS` preflight, 비허용 Origin/메서드 | `400` `Disallowed CORS origin|method` | `403` `Invalid CORS request` | **의도적** (§4.4) |
| D-4 | `OPTIONS /health` (Origin 없음) | `405` + `allow: GET` | `200` + `Allow: GET,HEAD,OPTIONS` | **범위 밖** — 리뷰 C-4(권고, 마감 Phase 6). 고치기 전에도 있던 차이이며 이번에 오히려 좁아졌다(전에는 `GET, HEAD, POST, PUT, DELETE, OPTIONS, PATCH`) |
| D-5 | `HEAD /health` | `405` + `allow: GET` | `200` | **범위 밖** — FastAPI 는 GET 라우트에 HEAD 를 자동 추가하지 않고 Spring 은 한다. 고치기 전부터 있던 차이 |
| D-6 | preflight 응답 본문 | `200` `OK` (`text/plain`) | `200` 빈 본문 | 무해 — 브라우저는 preflight 본문을 읽지 않는다 |
| D-7 | `Vary` | `Origin` | `Origin, Access-Control-Request-Method, Access-Control-Request-Headers` | 무해 — 캐시 정확도가 더 높은 방향 |

---

## 3. C-2 — 테스트를 HTTP 경계로 옮긴 방식과 실패 → 통과 증거

### 3.1 무엇이 문제였나

`ErrorContractTest` 는 `private val handler = GlobalExceptionHandler()` 로 **핸들러를 직접 호출**했다.
그래서 C-1 의 실제 이탈(프레임워크 예외 전부 500)이 살아 있는 채로 10건이 전건 초록이었다.
`api-contract-freeze` §5: *"계약 테스트는 **HTTP 경계에서** 돈다. 서비스 계층 단위 테스트로 대신할 수 없다 —
상태 코드 매핑, 헤더 부착, 직렬화 이름은 전부 경계에서 결정되기 때문이다."*

### 3.2 어떻게 옮겼나

핸들러 직접 호출을 없애고 `@WebMvcTest` + `MockMvc` 로 전건 이전했다.
Phase 1 에는 도메인 예외를 던지는 운영 엔드포인트가 없으므로 **프로브 컨트롤러**를 세웠다.

- `api/src/test/kotlin/kr/easydoc/api/support/ErrorProbeController.kt` — **테스트 소스셋 전용**(운영 JAR 에 없다).
  경로 접두사 `/__probe` 는 계약의 14개 엔드포인트와 겹치지 않는다.
  도메인 예외 13종 · 405 대상 · 깨진 JSON · 필수 쿼리 누락 · 타입 불일치 · Bean Validation 실패를 낸다.
- `ErrorContractTest`(18건) — 도메인 예외 12종 상태 코드(parameterized), `detail` 문자열, `WWW-Authenticate` 유무,
  백스톱 2종의 **예외 메시지 미노출**, 오류 응답의 **캐시 금지 헤더 부재**.
- `FrameworkErrorContractTest`(9건, 신규) — C-1 세 케이스 + 422 배열 4종 + 캐시 헤더 부재 + `ProblemDetail` 키 부재.

`spring-boot-starter-validation` 은 의존성에 넣지 않았다(Phase 3 항목).
Bean Validation 경로는 프로브가 `MethodArgumentNotValidException` 을 직접 만들어 던져 재현한다 —
예외가 **DispatcherServlet 의 예외 해결 경로를 그대로 통과**하므로 핸들러 직접 호출과 다르다.

### 3.3 실패 → 통과 (순서를 지킨 실측)

**① 고치기 전** — 새 테스트를 먼저 넣고 실행했다. `28 tests completed, 8 failed`:

```
### kr.easydoc.api.ErrorContractTest  tests=19 failures=1
  FAIL 컨트롤러가 적어 둔 캐시 금지 헤더가 오류 응답으로 새지 않는다
       AssertionError: 오류 응답에 Cache-Control 이 붙었다 — 계약은 성공 응답 10곳에만 붙인다
### kr.easydoc.api.FrameworkErrorContractTest  tests=9 failures=7
  FAIL 없는 경로 → 404 {"detail":"Not Found"}          : Status expected:<404> but was:<500>
  FAIL 허용되지 않는 메서드 → 405 + Allow                : Status expected:<405> but was:<500>
  FAIL Accept: application/xml 이어도 200 JSON 이다      : Status expected:<200> but was:<500>
  FAIL 깨진 JSON 본문 → 422 + detail 배열                : Status expected:<422> but was:<500>
  FAIL 필수 쿼리 파라미터 누락 → 422                     : Status expected:<422> but was:<500>
  FAIL 쿼리 파라미터 형식 오류 → 422                     : Status expected:<422> but was:<500>
  FAIL Bean Validation 실패 → 422                        : Status expected:<422> but was:<500>
```

**도메인 예외 매핑 12건은 이때도 통과했다.** 즉 이전 테스트가 검증하던 것은 그대로 살아 있고,
새로 드러난 것이 정확히 C-1 이 지적한 범위다 — 테스트가 공허하지 않음이 이 대비로 증명된다.

**② 고친 뒤** — `ErrorContractTest 18` · `FrameworkErrorContractTest 9` · `HealthContractTest 4`
= **31건 전건 통과**.

**③ 한 건은 테스트를 철회했다 (근거를 남긴다).**
`컨트롤러가 적어 둔 캐시 금지 헤더가 오류 응답으로 새지 않는다` 는 **고칠 수 없어 제거**했다.

- 사실: Spring MVC 는 컨트롤러가 `HttpServletResponse` 에 쓴 헤더를 `@ExceptionHandler` 응답에도 남긴다.
  Python 은 핸들러가 새 `JSONResponse` 를 만들어 돌려주므로 구조적으로 남지 않는다 — **거동이 반대다.**
- 서블릿 API 에는 **헤더 삭제가 없다**(`setHeader(name, null)` 은 Tomcat·MockHttpServletResponse 모두 무시).
- `response.reset()` 은 지울 수 있지만 **CORS 필터가 먼저 써 둔 헤더까지 지운다** — 계약에 정의된 오류 응답
  (404·405·401 …)이 브라우저에서 읽히지 않게 되어 더 나쁜 회귀가 된다.
- 따라서 **강제 가능한 규칙은 "쓰지 않는 것"뿐이다**: Phase 3 성공 응답은 `Cache-Control: no-store`/
  `X-Content-Type-Options: nosniff` 를 `HttpServletResponse` 가 아니라 **`ResponseEntity` 에 붙인다.**
  그러면 예외가 나도 그 헤더가 남지 않는다. 이 제약을 `GlobalExceptionHandler` KDoc 에 적어 두었다.
- **남아 있는 보호**: "핸들러 자신이 캐시 헤더를 붙이지 않는다"는 단언은 살아 있다
  (`ErrorContractTest.오류 응답에 캐시 금지 헤더가 없다` 401·404·409·500,
  `FrameworkErrorContractTest.프레임워크 오류 응답에 캐시 금지 헤더가 없다` 404·405·422).

> **`contract-keeper`·`migration-reviewer` 에게**: `api-contract-freeze` §2.7 해결 3 은
> "Spring MVC 헤더 상속 회귀를 잡는 것은 Kotlin 계약 테스트뿐"이라고 적었고 그것을 Phase 3 종료 조건에
> 걸었다. 위 사실에 비춰 **그 종료 조건은 "오류 응답에 헤더가 없음"이 아니라 "성공 응답의 사적 헤더를
> `ResponseEntity` 로만 붙임"으로 표현되어야 실행 가능하다.** 스킬 문구 조정 여부는 계약 소유자 판단이다.

---

## 4. CORS 구현과 계약 대조

`api/src/main/kotlin/kr/easydoc/api/config/CorsConfig.kt`(신규) + `CorsContractTest`(10건, 신규).

### 4.1 계약 대조 — `contracts/easy-doc-v1.yaml` `x-cors` / `api-contract-freeze` §2.5

| 계약 항목 | 계약 값 | 구현 | 회귀 테스트 |
|---|---|---|---|
| `allow_origins` | 설정값 (기본 `http://localhost:5173`) | `EasyDocProperties.corsOrigins` → `application.yml` `easydoc.cors-origins` | `허용 오리진의 단순 요청에…` / `허용하지 않은 오리진은 거절한다` |
| `allow_credentials` | **false** | `allowCredentials = false` (명시) | `Allow-Credentials 를 내보내지 않는다` (단순·preflight 양쪽) |
| 메서드 | `GET, POST, PUT, PATCH, DELETE` | 같음. OPTIONS 는 필터가 스스로 처리 | `preflight 가 계약의 메서드 5개를 허용한다` / `계약 밖 메서드의 preflight 는 거절한다` |
| 요청 헤더 | `Authorization, Content-Type` | 같음 | `preflight 가 Authorization·Content-Type 요청 헤더를 허용한다` |
| **노출 헤더** | **`Content-Disposition, Location`** | 같음 | `실제 요청에 노출 헤더가 있다` + `preflight 에 노출 헤더가 있다` |
| preflight 캐시 | (계약에 없음) Starlette 실측 600초 | `maxAge = 600` — Spring 기본 1800 을 덮었다 | 실측 대조 §2.2 |

노출 헤더는 **preflight 와 실제 요청 양쪽에서 확인**했다(요청 사항). 살아 있는 컨테이너에서도 확인:

```
CORS-1 GET /health + Origin   → Access-Control-Expose-Headers: Content-Disposition, Location
CORS-2 OPTIONS preflight(GET) → Access-Control-Expose-Headers: Content-Disposition, Location
```

(Python 은 preflight 응답에 노출 헤더를 넣지 않는다. 스펙상 실제 응답에만 의미가 있어 무해한 차이다.)

### 4.2 왜 `addCorsMappings` 가 아니라 서블릿 필터인가

Starlette 의 `CORSMiddleware` 는 **라우터 바깥**에 있다. 그래서 Python 은 `/nope`(404)와
메서드가 틀린 405 에도 CORS 헤더를 붙인다(실측). `WebMvcConfigurer.addCorsMappings` 는
`HandlerMapping` **안**에서 돌아 핸들러를 찾은 요청에만 헤더가 붙는다 — `/nope` 에서 헤더가 사라지고
브라우저는 진짜 사유(404)를 읽지 못한다. `CorsFilter`(order `HIGHEST_PRECEDENCE`)가 미들웨어와 같은 자리다.
실측으로 확인했다(§2.2 마지막 두 행).

필터를 맨 앞에 두는 두 번째 이유: Phase 3 에서 인증 필터가 붙으면 preflight 에는 `Authorization` 이
실리지 않으므로, CORS 가 뒤에 있으면 **모든 preflight 가 401** 이 된다.

### 4.3 `Allow-Headers` 문자열이 다른 이유 (기능 동일)

Starlette 는 설정값과 안전 목록의 합집합 5개(`Accept, Accept-Language, Authorization, Content-Language, Content-Type`)를
**고정으로** 내보내고, Spring 은 요청이 물어본 헤더 중 허용된 것만 되돌려준다.
브라우저 판정 결과는 같다(요청이 물어본 헤더가 모두 허용되면 통과). 안전 목록 헤더는 애초에
`Access-Control-Request-Headers` 에 실리지 않는다.

> 리뷰 C-4 가 지적한 계약 항목 두 개(`x-cors` 에 `max_age` 없음 / `allow_headers` 가 설정값이지 전선값 아님)는
> **`contract-keeper` 소관**이라 계약 파일을 건드리지 않았다. 구현은 계약이 적은 `[Authorization, Content-Type]` 을
> 따랐고, `max_age` 는 계약에 없으므로 **계약 파일이 스스로 정본이라 선언한 "현재 Python 구현의 실제 동작"**(600)에 맞췄다.

### 4.4 의도적으로 Python 과 다르게 둔 것 — 거절 경로

| 상황 | Python(Starlette) | Kotlin(Spring `DefaultCorsProcessor`) |
|---|---|---|
| 단순 요청 + 비허용 Origin | `200` + 실제 본문, `Allow-Origin` 없음 (**브라우저가 막는다**) | `403` `Invalid CORS request` (**핸들러를 돌리지 않는다**) |
| preflight + 비허용 Origin/메서드 | `400` + 사유 문구 | `403` `Invalid CORS request` |

**왜 맞추지 않았나.** 맞추려면 `DefaultCorsProcessor` 의 거절 처리를 우회하는 커스텀 필터를 써야 하는데,
보안 판정 경로에 손으로 쓴 분기를 넣는 비용이 얻는 것보다 크다고 판단했다. 근거 셋:

1. **브라우저에서 보이는 결과가 같다.** 양쪽 다 JS 가 응답을 읽지 못한다. React 는 어느 쪽이든
   CORS 오류(네트워크 실패)로 본다.
2. **Python 쪽이 오히려 위험한 방향이다.** 비허용 오리진의 요청에서도 핸들러를 끝까지 돌려 본문을 만든 뒤
   브라우저의 선의에 맡긴다. Spring 은 그 전에 끊는다.
3. **비브라우저 클라이언트에 영향이 없다.** 서버 간 호출·`curl` 은 `Origin` 헤더를 보내지 않아
   CORS 처리 자체가 시작되지 않는다(실측: Origin 없는 `GET /health` → Kotlin 200, CORS 헤더 없음).

**이 판단이 "포팅 우선" 원칙과 충돌한다고 보면 되돌릴 수 있다** — 되돌리는 비용은 커스텀 필터 하나이며,
`CorsContractTest` 의 `허용하지 않은 오리진은 거절한다` 는 두 구현 모두에서 통과한다(`Allow-Origin` 부재를 본다).

---

## 5. U-1 — 손대지 않았고, 판단을 넘긴다

### 5.1 지시대로 하지 않은 것

미처리 500 경로에 **CORS 헤더를 붙이는 코드도, 떼는 코드도 넣지 않았다.**
`CorsContractTest` 는 그 경로를 **단언하지 않는다**(테스트로 어느 쪽도 고정하지 않으려고 의도적으로 뺐다).
KDoc 에도 그 이유를 적어 두었다.

### 5.2 그럼에도 값은 정해진다 — 측정값

CORS 를 구현하는 순간 기계적으로 한쪽으로 결정된다. `CorsFilter` 는 체인을 부르기 **전에** 헤더를 쓰므로,
그 아래에서 나오는 모든 응답이 헤더를 갖는다. 일회성 프로브로 측정했다(측정만 하고 테스트로 커밋하지 않았다):

```
U1MEASURE|미처리 500|status=500|ACAO=http://localhost:5173|EXPOSE=Content-Disposition, Location
U1MEASURE|도메인 404|status=404|ACAO=http://localhost:5173|EXPOSE=Content-Disposition, Location
```

**현재 Kotlin 은 "개선" 쪽에 서 있다** — Python 이 구조적으로 못 붙이는 자리에 헤더가 붙는다.

### 5.3 두 선택지의 비용

| 선택 | 구현 | React 에서 보이는 것 | 비용 |
|---|---|---|---|
| **(개선) 현행 유지** | 추가 코드 0 | `ApiError(500, "서버 오류가 발생했습니다")` — 사용자가 진짜 사유를 본다 | `client.ts` 의 `NETWORK_ERROR_STATUS = 0` 분기가 이 상황에서 더 이상 타지 않는다. 그 분기에 의존하는 화면이 있으면 Phase 6 에서 갈린다 |
| **(재현) Python 과 같게** | 미처리 500 경로에서 CORS 헤더를 빼는 코드 필요. 서블릿에 헤더 삭제가 없어 `CorsFilter` 를 감싸는 별도 필터가 필요하다 | `ApiError(0, "서버에 연결하지 못했습니다…")` — 현행 Python 과 동일 | 보안 인접 경로에 손으로 쓴 필터가 하나 는다 |

**리더 판단 요청.** (a) 현행(개선)을 확정하고 계약 `x-cors.x-known-limitation` 을 Kotlin 기준으로 갱신할지,
(b) 재현으로 되돌릴지. (a)를 고르면 `contract-keeper` 가 계약 문구를, `parity-verifier` 가 대조 기준을 함께 갱신해야 한다.
**Phase 3 착수 전에 정해지면 추가 비용이 없다** — 지금 결정되면 (b)여도 필터 하나만 추가하면 된다.

---

## 6. 남긴 것 · 넘긴 것

### 6.1 판단이 필요한 항목

| # | 항목 | 내용 | 마감 제안 |
|---|---|---|---|
| U-1 | 미처리 500 의 CORS 헤더 | §5 | Phase 3 착수 전 |
| D-1 | `GET /health/` 뒤 슬래시 | Python 은 `307` 리다이렉트(Starlette `redirect_slashes`), Kotlin 은 `404`. Spring 6/7 은 뒤 슬래시 매칭을 **제거**해 재현하려면 리다이렉트 필터를 새로 써야 한다(잘못 쓰면 open redirect). **React 의 API 경로에는 뒤 슬래시가 하나도 없다**(`frontend/src/api/*.ts` 전수 확인) — 재현 가치가 낮다고 보고 넘긴다. 고치기 전 `500` 이었던 것이 지금은 정상 `404` 다 | Phase 6 |
| — | `msg`/`type` 어휘 | §1.4. 계약 소유자 확인 필요 | Phase 3 |
| — | `api-contract-freeze` §2.7 해결 3 문구 | §3.3 ③. 실행 가능한 형태로 재기술 제안 | Phase 3 |

### 6.2 이번에 하지 않은 것 (범위 밖 · 다른 담당)

- **`spring-boot-starter-validation` 미추가** — 의존성 추가는 `libs.versions.toml` 과 락파일을 건드려야 하고
  `backend-kotlin/` 루트 빌드 스크립트가 동시 작업 중이라 열지 않았다. 그래서
  `handleHandlerMethodValidationException` 은 **HTTP 경계 테스트가 없다**(검증기가 없으면 그 예외가 발생하지 않는다).
  Phase 3 에서 입력 상한을 붙일 때 의존성과 함께 회귀를 고정해야 한다. **미검증 상태로 남긴다.**
- 리뷰 C-4·C-5·C-6·C-7·C-8(계약 파일 자체의 결함) — `contract-keeper` 소관.
- 리뷰 T-1(설정 바인딩 미실행)은 **부분 해소** — `EasyDocProperties.corsOrigins` 가 실제 소비되면서
  바인딩 경로가 `CorsContractTest` 로 실행된다. `Secret` 필드(`jwtSecret`·`fernetKey`)의 바인딩과
  `SecretConverter` 는 여전히 미실행이다(Phase 3).
- 리뷰 T-2(`EasyDocProperties` 가 worker 에서 안 보임) — 클래스 이동은 모듈 경계 변경이라 손대지 않았다.
  다만 `CorsConfig` 에 `@EnableConfigurationProperties` 를 붙여 **`@WebMvcTest` 에서도 바인딩되도록** 했다
  (`@ConfigurationPropertiesScan` 은 슬라이스 테스트에서 적용되지 않는다).

### 6.3 바꾼 파일

| 파일 | 성격 |
|---|---|
| `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt` | 수정 — `ResponseEntityExceptionHandler` 상속, 422 배열, 본문 계약 유지 |
| `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/WebMvcConfig.kt` | 신규 — 내용 협상 비활성화 |
| `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/CorsConfig.kt` | 신규 — CORS 필터 |
| `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ErrorContractTest.kt` | 수정 — 핸들러 직접 호출 → MockMvc |
| `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/FrameworkErrorContractTest.kt` | 신규 |
| `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/CorsContractTest.kt` | 신규 |
| `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ErrorProbeController.kt` | 신규 — 테스트 소스셋 전용 |

`api/build.gradle.kts` · `libs.versions.toml` · 락파일 · CI · parity 하네스는 **건드리지 않았다.**

---

## 7. 검사 결과

| 검사 | 결과 |
|---|---|
| `./gradlew build` (컴파일 + ktlintCheck + detekt + test) | **BUILD SUCCESSFUL** — 경고 0 (`allWarningsAsErrors=true`, detekt `maxIssues: 0`) |
| Kotlin 테스트 | **75건 전건 통과** (이전 48 + 신규 27). 내역: core 19 · infrastructure 8 · api 45 · worker 3 |
| `uv run ruff check .` | All checks passed! |
| `uv run mypy .` | Success: no issues found in 116 source files |
| `uv run pytest` | **820 passed, 68 skipped, 4 deselected** (기준선과 동일) |
| compose 두 스택 동시 기동 | Python 8000 / Kotlin 8100 `Up (healthy)`, 16 케이스 대조 §2 |
| `uv run pytest tests/golden` | **미실행** — 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았다(`app/` 무수정, `core` 무수정) |
