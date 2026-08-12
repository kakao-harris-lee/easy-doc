# Phase 1 리뷰 — Kotlin 골격과 CI (1회차 독립 리뷰)

**작성:** migration-reviewer (Claude) / 2026-08-12
**회차:** 1차 — Claude 단독 독립 리뷰. **교차 대조표 없음.**
**codex 산출물:** 이 회차에는 존재하지 않는 것이 정상이다(`codex-reviewer`가 병렬로 독립 리뷰 중). 실패로 기록하지 않는다. 교차 종합은 2차 재호출에서 `01_skeleton_cross.md`로 낸다.
**이 파일만으로 Phase 1 종료 조건 충족을 보고하지 않는다.**

---

## 1. 리뷰 범위와 참조 절

**대상:** `git diff 1f8f352..HEAD` (67 파일, +9192/-210)

| 커밋 | 내용 |
|---|---|
| `5314e0b` | Phase 0 — `contracts/easy-doc-v1.yaml`, spike 산출물, parity 게이트 Critical 2건 수정(`.claude/skills/python-kotlin-parity/scripts/`) |
| `2ed897d` | Phase 1 — `backend-kotlin/` 5모듈(51 파일), Flyway V1·V2, `.github/workflows/ci.yml`, `docker-compose.yml`, `docker/` |

**참조한 계획 문서 절:** §2.2(외부 계약), §2.3(내부 정책), §3.1(기술 선택), §3.2(Gradle 구조), §4.2(DB·Flyway 인수), §4.3(암호화 게이트), §4.5(문서 추출), §4.6(LLM), §5 Phase 1·7, §6(검증 매트릭스)
**참조한 스킬:** `codex-review`, `migration-safety-gate`(I-1~I-13), `kotlin-spring-conventions`, `python-kotlin-parity`
**참조한 배경 산출물:** `01_kotlin-implementer_skeleton.md`, `00_progress.md`, `00_privacy-gate_crypto-spike.md`, `00_kotlin-implementer_doc-spike.md`, `00_privacy-gate_scan-baseline.md`

**리뷰 축:** 리더가 지정한 6축을 그대로 쓴다. 정본 5축과의 대응은 ①계약 준수 ②parity 위험 ③보안 불변식 ④Kotlin/Spring 관용성 ⑤테스트 적정성이고, ⑥하네스는 ②·⑤의 **게이트 층**이라 별도 절로 둔다.

### 이번 리뷰에서 실제로 실행한 것 (추론이 아닌 근거)

| # | 실행 | 결과 |
|---|---|---|
| E1 | 살아 있는 두 런타임(Python 8000 / Kotlin 8100)에 동일 요청 대조 | **계약 이탈 4건 발견** (§2 C-1) |
| E2 | `docker logs easy-doc-kotlin-api-1` 예외 핸들러 로그 확인 | C-1의 근본 원인 확정 |
| E3 | 살아 있는 PostgreSQL에 `SchemaFingerprint.FINGERPRINT_SQL` 원문 실행 + 정렬 collation 대조 | 가설 1건 **기각**(§2 P-7) |
| E4 | 독립 서브에이전트가 새 컨테이너에서 V1 / `alembic upgrade head` 양쪽 적용 후 지문 diff + 18종 스키마 변형 주입 | **V1 ≡ Alembic 전건 일치 확인**, 지문 사각 20종 실증 |
| E5 | `scan_privacy_invariants.py --changed --base 1f8f352` | BLOCK 0건 / WARN 1건(오탐) |
| E6 | `./gradlew parityHarness --no-daemon` 재실행 | BUILD SUCCESSFUL, 산출물 재생성 확인 — 다만 §7 H-5 |
| E7 | `compare_parity.py` 를 fixture 없음·빈 디렉터리 두 조건으로 실행 | 둘 다 종료 코드 1 (도구는 엄격, CI 래퍼가 완화 — §7 H-4) |

**돌리지 않은 것:** `./gradlew build` 전체(48건 테스트)를 직접 재실행하지 않았다. 구현자 보고와 `parityHarness` 재실행만 확인했다.

---

## 2. 축 1 — 계약 준수

### 통과한 것 (검토함 — 지적 없음)

- **`GET /health`** — 살아 있는 두 런타임에서 직접 대조했다. 상태 200, 본문 `{"status":"ok"}` (바이트 동일), `Content-Type: application/json`, `Cache-Control`·`X-Content-Type-Options` 없음, 인증 불필요, 의존 서비스 미진단. `contracts/easy-doc-v1.yaml:816-837` 및 `HealthController.kt:34-47` 과 일치.
- **오류 매핑표** — `GlobalExceptionHandler.mappingFor()`(`GlobalExceptionHandler.kt:92-143`)가 `app/api/errors.py:36-57`의 `_MAPPINGS` 를 전건 재현한다. 413(`UploadTooLargeError`)·503(`ConfigurationError`)·502(`LLMProviderError`/`QueueUnavailableError`)·401+`WWW-Authenticate: Bearer` 까지 포함해 12개 매핑이 상태 코드·헤더 모두 일치한다. 하위 타입 우선 검사(`LlmTruncatedException` → 502)로 Starlette MRO 탐색과 같은 결과를 낸다.
- **`{"detail": ...}` 형식** — `ErrorResponse`(`GlobalExceptionHandler.kt:173`)는 필드가 `detail` 하나뿐이고 `application/json` 으로 고정된다. `spring.mvc.problemdetails.enabled: false`(`api/application.yml:31-33`)와 `server.error.include-*: never`(`:37-45`)로 `ProblemDetail`·스택트레이스 노출 경로를 이중으로 막았다.
- **Actuator 미도입** — `/actuator`·`/actuator/env` 가 열려 있지 않다(실측). 계약 14개 밖의 경로를 늘리지 않았다.

### C-1 [**차단(Critical) ① 사건 + ② 장치**] 프레임워크 예외가 전부 500으로 나간다 — 마감 **Phase 3 착수 전**

**근거:** `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt:86-90`

```kotlin
@ExceptionHandler(Exception::class)
fun handleUnexpected(exception: Exception): ResponseEntity<ErrorResponse> {
    logger.error("처리하지 못한 예외: {}", exception::class.java.simpleName)
    return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_MESSAGE)
}
```

`@RestControllerAdvice` 안의 `@ExceptionHandler(Exception::class)` 는 Spring MVC가 자체적으로 던지는 예외까지 전부 가로챈다. `ResponseEntityExceptionHandler` 를 상속하지 않았으므로 `NoResourceFoundException`(404)·`HttpRequestMethodNotSupportedException`(405)·`HttpMediaTypeNotAcceptableException`(406)이 모두 이 백스톱으로 떨어진다.

**실측 (살아 있는 두 컨테이너, 2026-08-12):**

| 요청 | Python (8000) | Kotlin (8100) |
|---|---|---|
| `GET /nope` | `404` `{"detail":"Not Found"}` | **`500`** `{"detail":"서버 오류가 발생했습니다"}` |
| `POST /health` | `405` + `allow: GET` | **`500`** (Allow 없음) |
| `GET /health` `Accept: application/xml` | `200` | **`500`** |
| `GET /health/` | `307` (redirect_slashes) | **`500`** |

**근본 원인 확정** — `docker logs easy-doc-kotlin-api-1`:

```
ERROR | kr.easydoc.api.error.GlobalExceptionHandler | 처리하지 못한 예외: NoResourceFoundException
```

**Phase 3에서 무엇이 깨지는가.** 지금은 엔드포인트가 `/health` 하나라 피해가 작아 보이지만, Phase 3에서 요청 본문을 받는 순간 `MethodArgumentNotValidException`·`HttpMessageNotReadableException` 이 같은 경로로 떨어져 **계약이 422 배열 `detail` 을 요구하는 자리에서 500 고정 문자열이 나간다**(`contracts/easy-doc-v1.yaml:42-54, 914-929`). React `client.ts` 의 `readErrorMessage` 가 두 모양을 분기 처리하는 전제(계약 파일 자체가 명시)가 무너져 화면에서 진짜 사유가 사라진다.

**왜 ① 사건이기도 한가.** Phase 7 관찰 기간의 롤백 판정은 실패율·5xx 지표로 내린다. 정상 404/405 트래픽(봇 스캔, 오래된 북마크, preflight 실패)이 전부 500으로 계상되면 **관찰 지표 자체가 오염**되어 진짜 장애와 구분되지 않는다.

**권고:** `ResponseEntityExceptionHandler` 상속으로 프레임워크 예외를 표준 매핑에 남기고 그 위에 `{"detail":...}` 본문만 덮거나, `handleUnexpected` 앞에 `ErrorResponseException`/`ServletException` 계열 핸들러를 명시적으로 둔다. 어느 쪽이든 **HTTP 경계 테스트와 함께** 넣어야 한다(C-2).

### C-2 [**차단(Critical) ② 장치**] 오류 계약 게이트가 HTTP 경계를 보지 않는다 — 마감 **Phase 3 착수 전**

**근거:** `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ErrorContractTest.kt:31-32`

```kotlin
class ErrorContractTest {
    private val handler = GlobalExceptionHandler()
```

10개 테스트 전부가 **핸들러 메서드를 직접 호출**한다. 그래서 C-1의 실제 이탈이 존재하는 채로 `ErrorContractTest` 10건이 전부 초록이다. 구현자도 이 한계를 §8-10에 "부분 검증"으로 적어 두었으나(정직한 기록이다), **심각도는 부분 검증이 아니라 차단**이다 — §6 Contract 게이트("status/body/header/error가 v1 spec과 일치")를 판정해야 할 게이트가 그 판정을 구조적으로 할 수 없는 상태이고, 그 맹점이 이미 실제 이탈 4건을 통과시켰기 때문이다. 이것이 "검증 없이 통과하는 경로"의 교과서적 형태다.

**권고:** `@WebMvcTest` 로 더미 컨트롤러 하나를 띄워 (a) 각 도메인 예외를 던지는 경로, (b) 없는 경로, (c) 허용되지 않는 메서드, (d) 깨진 JSON 본문을 **MockMvc 로** 통과시켜 상태·헤더·본문을 단언한다. `HealthContractTest` 가 이미 `@WebMvcTest` 를 쓰고 있으므로 배선 비용은 거의 없다.

### C-3 [수정 필요] CORS 미설정 — 마감 **Phase 3 착수 전**

**근거:** `GlobalExceptionHandler.kt:45-47`(의도적 보류 기록), 구현자 §8-9.

실측: Python `GET /health` + `Origin` → `access-control-allow-origin: http://localhost:5173`, `access-control-expose-headers: Content-Disposition, Location`, `vary: Origin`. Kotlin → **전부 없음**. preflight도 Python은 CORS 헤더 4종을 주고 Kotlin은 서블릿 기본 `Allow` 만 준다.

계획 §2.2가 "CORS: 허용 origin·메서드·헤더·**노출 헤더** 유지"를 계약으로 못박았고, `Content-Disposition`·`Location` 노출은 업로드·다운로드가 브라우저에서 동작하기 위한 필수 조건이다. U-1(미처리 500의 CORS 헤더) 리더 판단을 기다리느라 CORS 전체를 보류한 것은 이해되나, **U-1 판단과 CORS 기본 설정은 분리 가능하다.** 지금 결정을 못 받으면 Phase 3 API가 브라우저에서 검증 불가 상태로 쌓인다.

### C-4 [권고] `OPTIONS` 응답이 존재하지 않는 메서드를 광고한다 — 마감 **Phase 6**

실측 Kotlin `OPTIONS /health` → `Allow: GET, HEAD, POST, PUT, DELETE, OPTIONS, PATCH` (서블릿 `HttpServlet.doOptions` 기본). Python은 `allow: GET`. 계약 위반이자 소소한 정보 노출이다. `dispatchOptionsRequest` 를 켜면 Spring MVC 매핑 기준으로 정확해진다.

### 계약 파일(`contracts/easy-doc-v1.yaml`) 자체의 검토

이 파일이 Phase 3~6의 단일 기준이므로 별도 심층 감사를 수행했다. **전체 결과는 §8**에 있다 — 광범위한 대조를 통과했고 잔여 지적 10건(차단 2 / 수정 필요 3 / 권고 5)이 남았다.

현 시점에 직접 확인한 것:
- 파일 머리(`:11-14`)가 "지켜야 하는 값은 `app/api/*.py` 에 있고 계획 §2.2와 어긋나면 코드가 이겼다"를 명시하고, 어긋난 지점을 `00_contract-keeper_three-way-diff.md` 로 분리해 둔 것은 **옳은 구조**다. 계약이 의도가 아니라 실측을 동결한다는 원칙이 파일 안에 적혀 있다.
- `/health` 항목(`:816-837`)이 "의존 서비스를 진단하지 않고 상수만 돌려준다 … 코드가 이겼다"를 명시하고, Kotlin 구현이 그 서술을 KDoc으로 복사해 왔다(`HealthController.kt:11-20`). 계약↔구현이 같은 근거를 가리킨다.
- 캐시 금지 헤더 10곳 목록과 `does_not_apply_to`(`:155-175`)가 "빠지면 계약 위반, 넓히면 parity 불일치" 양방향을 모두 적었다. `HealthContractTest:44-54` 가 넓어지는 방향의 회귀를 실제로 잡는다.

---

## 3. 축 2 — parity 위험 (Flyway 스키마)

### 통과한 것 — **V1 ≡ Alembic 0001~0006 전건 일치 (독립 재검증)**

구현자 보고를 믿지 않고 **새 `pgvector/pgvector:pg16` 컨테이너에서 양쪽 경로를 다시 적용해** 대조했다.

- `V1__python_schema_baseline.sql` 적용본, `alembic upgrade head` 적용본, 커밋된 `python-schema-fingerprint.txt` — **세 지문이 61줄 바이트 동일**.
- `alembic check` → "No new upgrade operations detected" (SQLAlchemy 모델도 그 스키마와 일치).
- 컬럼 34개 전부를 이름·**서수**·타입(길이 포함)·NOT NULL·DEFAULT 식 단위로 대조 — 차이 0.
- 제약 11개 일치. FK `ON DELETE CASCADE` 2건, 의도적 NO ACTION인 `fk_documents_workspace_id_workspaces`(`0006:81-87` / `V1:92-93`), CHECK 본문 2건, `uq_workspaces_user_id_name` 전부 포함.
- 인덱스 11개 일치.
- **timestamp 컬럼 7개 전부 `timestamp with time zone`.** naive `timestamp` 0건. (§4.2 timezone 위험 해소)
- V2가 baseline 밖에 있고 additive 규칙을 지킨다(`documents` 서수 11, `conversions` 서수 17).

서수를 지문에 담은 판단(`SchemaFingerprint.kt:27-33`)은 옳다. `0004`·`0006`의 `ADD COLUMN` 때문에 서수가 뒤로 밀리는데, 서수를 빼면 V1이 컬럼을 다른 순서로 선언해도 지문이 같아진다. 이 함정을 미리 막았다.

### P-1 [수정 필요] 지문이 못 보는 스키마 차이 — 마감 **Phase 7 착수 전**

**근거:** `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/SchemaFingerprint.kt:34-69`

V1 DB에 **18종 스키마 변형을 실제로 주입한 뒤 지문을 다시 뽑았고, 전부 바이트 동일**했다. 이 전환에서 실제로 걸리는 것만 추린다.

| 사각 | 왜 이 전환에서 문제인가 |
|---|---|
| **컬럼 collation** (`attcollation` 미선택) | `users.email` 을 `COLLATE "C"` 로 바꿔도 지문 무변화. `ix_users_email` 의 유일성·정렬 의미가 collation에 달려 있고, `0002` 의 소문자 CHECK 제약과 짝을 이루는 자리다. 절체 대상 DB가 다른 collation이면 **이메일 중복 판정이 조용히 달라진다** |
| **`vector` 확장 버전·스키마** | 지문은 `'extension ' || extname` 뿐. 실측 DB는 `vector 0.8.6`. 확장 질의에 `nspname` 필터도 없다. 사전 RAG 도입 시 opclass 가용성이 버전에 달린다 |
| identity / generated 컬럼 | `GENERATED ALWAYS AS IDENTITY` 는 `default=-` 로 렌더돼 **기본값 없는 평범한 컬럼과 구분 불가** |
| 시퀀스·enum·다른 스키마·파티션 테이블·트리거·권한·RLS | 전부 비가시 |

**모든 오검출 방향은 "기동 실패"(안전)이지 "조용한 통과"가 아니다** — 지문이 다르면 `FlywayBaselineGuard` 가 던진다. 다만 위 사각들은 **반대로 "실제로는 다른데 같다고 판정"**하는 자리이므로, 절체 전에 최소한 collation과 확장 버전은 지문에 넣어야 한다. 나머지는 "우리 스키마에 없다"는 근거를 남기고 의도적으로 제외한다고 적으면 충분하다.

### P-2 [수정 필요] Alembic이 어느 Kotlin 테스트에서도 실행되지 않는다 — 마감 **Phase 7 착수 전 / Alembic 리비전 추가 즉시**

**근거:** `PythonSchemaBaselineTest.kt:38-48`, `ApiStartupWithDatabaseTest.kt:112-130`, `FlywayBaselineGuardTest.kt:95-112`

세 곳 모두 "Alembic이 만든 기존 스키마"를 **V1을 적용해서** 만든다.

```kotlin
// FlywayBaselineGuardTest.kt:95-102 — givenAlembicManagedSchema
Flyway.configure()...locations("classpath:db/migration").target("1").load().migrate()
```

그리고 V1의 정당성은 `PythonSchemaBaselineTest` 가 **커밋된 텍스트 파일**과 대조해 세운다. 그 파일은 사람이 1회 수동으로 Alembic을 돌려 뽑은 동결 산출물이다. 즉 대조되는 두 쪽(V1, 지문 파일)이 **둘 다 Kotlin 저장소 안에 있고**, 실제 Alembic은 회귀 경로에 없다. Alembic이 0007을 얻거나 `0003`이 다시 제자리 수정되면 **아무 테스트도 깨지지 않는다.**

계획 §4.2-2가 요구한 "파일만 믿지 않는다"는 커밋 시점에는 지켜졌다(구현자가 실제로 돌렸고, 나도 재검증했다). 지켜지지 않는 것은 **그 이후의 회귀**다.

**닫는 비용이 거의 없다.** `.github/workflows/ci.yml:52` 의 `quality` 잡이 이미 실 PostgreSQL에 `uv run alembic upgrade head` 를 돌린다. 그 DB에서 같은 지문 질의를 뽑아 `python-schema-fingerprint.txt` 와 대조하는 한 단계만 추가하면 고리가 닫힌다.

### P-3 [**확인 불가 — 리더 확인 필요**] §4.2-1 "실제 대상 DB의 schema-only dump" 미수행

계획 §4.2는 1→2→3 순서다: ①실제 대상 DB dump·`alembic_version` 수집 → ②기대 스키마와 실제 스키마 비교 → ③`V1` 작성. 구현자는 **①을 건너뛰고 ②의 "기대" 쪽(오늘자 마이그레이션 파일을 빈 DB에 적용)만 실행**했다. §4.2-2가 "README에 `0003` 제자리 수정 이력이 있으므로 파일만 믿지 않는다"고 경고한 대상이 정확히 이 경로다 — 운영 DB가 **옛 판본의 `0003`** 으로 만들어졌다면 오늘자 파일을 돌린 결과와 다를 수 있다.

**리더가 확인해 줘야 하는 것:** 절체 대상이 될 실제 DB(파일럿 운영본)가 존재하는가. 존재하지 않으면 이 항목은 공허하므로 그렇게 기록하고 닫는다. 존재하면 절체 전에 dump를 떠서 지문을 대조해야 하고, 그 결과가 다르면 `V1` 이 아니라 **baseline 기준선**을 실제 DB 기준으로 다시 정해야 한다.

### P-4 [수정 필요] `conversions.updated_at` 을 갱신하는 장치가 DB에 없다 — 마감 **Phase 3 종료 전**

SQLAlchemy `onupdate=func.now()`(`app/models/conversion.py:101-103`)는 **UPDATE 문에 값을 실어 보내는 클라이언트 측 동작**이고, `app/repositories/conversions.py:130,198` 도 명시적으로 세팅한다. V1에는 트리거도 `ON UPDATE` 도 없다(양쪽 다 없으므로 **지문 대조로는 절대 잡히지 않는다**).

Kotlin repository가 Phase 3~5에 들어올 때 UPDATE마다 `updated_at` 을 세우지 않으면 컬럼이 조용히 낡는다. `updated_at` 은 lease·재시도 판정(§4.4)과 KPI 시계열에 쓰이는 값이라 조용한 정지가 늦게 발견된다. 현재 `backend-kotlin` 전체에 `updated_at` 문자열이 0건이다.

### P-5 [권고] UUID PK 4개에 DB 기본값이 없다 — 마감 **Phase 3**

`users.id`·`documents.id`·`conversions.id`·`workspaces.id` 전부 SQLAlchemy `default=uuid.uuid4` (클라이언트 생성)이고 지문은 `default=-` 다. `gen_random_uuid()` 서버 기본값이 **없으므로** Kotlin은 애플리케이션에서 UUID를 만들어야 한다. 반대로 `created_at` 4개와 `retention_expires_at` 은 서버 기본값이므로 INSERT에서 **빼야** DB 시계 의미가 보존된다.

### P-6 [권고] `0006`의 백필 DML에 V1 대응물이 없다

`migrations/versions/0006_workspaces.py:58-73` 의 `INSERT INTO workspaces … FROM users` / `UPDATE documents SET workspace_id` 는 V1에 없다. 빈 DB에서는 no-op이라 V1의 용도(빈 DB 초기화)에는 문제가 없지만, **V1은 비어 있지 않은 DB에 대해서는 Alembic과 동등하지 않다.** V1 머리 주석에 한 줄 적어 두면 나중에 오해가 없다.

### P-7 [권고 / 가설 기각 기록] 지문 줄 정렬은 collation 안전하다 — 다만 **우연히** 그렇다

가설: `ORDER BY grp, line` 이 DB collation을 타면 동일한 스키마인데도 줄 순서가 달라져 `expected == actual`(`FlywayBaselineGuard.kt:76`) 이 거짓 실패한다.

**실측으로 기각했다.** 대상 DB는 `datcollate=en_US.utf8` 인데, 지문 질의 원문의 자연 정렬은 `column conversions 1 id` → `10 input_tokens` 순(바이트 순)이고, 같은 질의에 `COLLATE "en_US.utf8"` 를 강제하면 순서가 **뒤바뀐다**. 즉 자연 정렬은 DB 기본 collation이 아니라 `C` 다 — `pg_class.relname`·`pg_attribute.attname` 의 `name` 타입이 갖는 `C` collation이 `||` 연쇄를 타고 전파되기 때문이다. **배포 환경 locale과 무관하게 안정적이다.**

다만 그 안정성이 **선언되어 있지 않다.** 질의를 리팩터링해 `name` 입력이 빠지거나 텍스트 함수 결과만으로 줄을 만들면 정렬이 조용히 locale 의존으로 바뀌고, 그때 가드는 동일한 스키마에서 기동을 거부한다. `ORDER BY grp, line COLLATE "C"` 한 마디를 명시하는 것을 권고한다. (부수적으로: `describeDifference`(`:145-147`)는 "줄 집합은 같다 (차이는 줄 순서뿐)" 분기를 이미 갖고 있는데 호출자는 그래도 던진다 — 두 코드가 같은 전제를 공유하지 않는다.)

### P-8 [권고] ORM 모델은 컬럼 순서의 근거가 아니다

`app/models/document.py:51` 은 `workspace_id` 를 세 번째로 선언하지만 실제 서수는 10이다. `conversion.py:77,98` 도 마찬가지(선언 5·13 / 실제 15·16). 저장소에 `create_all` 이 없고 `tests/conftest.py:59-67` 이 `alembic upgrade head` 를 쓰므로 오늘은 무해하지만, **Kotlin으로 서수를 옮기는 사람은 모델이 아니라 V1을 읽어야 한다.** 산출물에 한 줄 남길 값어치가 있다.

---

## 4. 축 3 — 보안 불변식

> `privacy-gate` 가 이 축의 최종 차단 권한을 갖는다. 아래 판정이 `privacy-gate` 감사와 갈리면 **`privacy-gate` 판정을 따른다.**

### 통과한 것 (검토함 — 지적 없음)

- **기계 스캔** — `scan_privacy_invariants.py --changed --base 1f8f352` (검사 파일 29개): BLOCK 0건. WARN 1건은 `HealthContractTest.kt:53` 의 `assertThat(response.getHeader("X-Content-Type-Options")).isNull()` 로, 계약대로 헤더 **부재**를 단언하는 줄이다 — 오탐.
- **I-3(평문 로그) 대응 구조** — Python이 `SecretStr`·`raise … from None`·`type(exc).__name__` 로 얻던 보호를 Kotlin이 그대로 재현했다. `Secret`(`core/security/Secret.kt`)은 `toString()` 을 `**********` 로 고정하고 **길이도 알려주지 않으며**, `equals` 는 `MessageDigest.isEqual` 상수 시간 비교, `hashCode` 는 값에 의존하지 않는다. `value class` 를 피한 이유(인라인 시 `toString()` 우회)까지 근거로 적혀 있다. `SecretTest` 7건이 이를 지킨다. **이 축에서 가장 잘 된 부분이다.**
- **예외 로깅** — `GlobalExceptionHandler.kt:70,88` 은 `exception::class.java.simpleName` 만 남기고 메시지·스택트레이스를 넘기지 않는다. Python(`app/api/errors.py:90,111`)이 `_logger.exception` 으로 트레이스백을 남기는 것보다 **오히려 좁다**. (Python 쪽은 "도메인 예외 메시지에 입력값을 담지 않는다"는 규약에 기대고 있고, Kotlin은 그 기대 없이도 안전하다.)
- **프레임워크 기본 로그** — `logging.level.org.springframework.web: INFO` 에 "절대 DEBUG로 내리지 않는다" 근거 주석(`api/application.yml:57`). SQL 바인딩 로깅 미설정. actuator 미도입으로 `/actuator/env` 노출 경로 없음(실측 확인).
- **비밀값 출처** — 살아 있는 컨테이너 env 확인 결과 `FERNET_KEY`·`JWT_SECRET`·`ANTHROPIC_API_KEY`·`OPENAI_API_KEY` 전부 환경변수로만 존재한다. 코드·yml에 리터럴 없음. I-12 충족.
- **컨테이너** — non-root(uid 10001), 애플리케이션이 자기 JAR을 고칠 수 없다.
- **I-4/I-7 준비(`encryption_scheme`)** — V2 배치 판단이 옳다. §4.2-4의 baseline은 V1을 "이미 적용됨"으로 건너뛰므로 `encryption_scheme` 이 V1 안에 있으면 **기존 Alembic DB에서는 영원히 생성되지 않는다.** V2에 두어야 baseline 직후 적용된다. `NOT NULL DEFAULT 'fernet-v1'` + `CHECK IN ('fernet-v1')` 조합은 (a) Python의 기존 INSERT가 계속 동작하고(테스트로 확인됨, `PythonSchemaBaselineTest.kt:118-160`) (b) 새 방식 도입 시 제약을 먼저 늘리는 마이그레이션을 강제해 **스키마 이력에 전환 시점이 남는다.** §4.3-5와 일관된다.

### S-1 [권고] compose 주석과 실제 값이 어긋나고, `.env` 설정이 조용히 무시된다 — 마감 **Phase 7 착수 전**

**근거:** `docker-compose.yml:26-33`

```yaml
  # 비밀키는 .env에서만 온다 — compose 파일에는 값을 적지 않는다.
  env_file: .env
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/easydoc_kotlin
    SPRING_DATASOURCE_USERNAME: postgres
    SPRING_DATASOURCE_PASSWORD: postgres     # ← 주석 바로 아래에서 자격증명을 적고 있다
```

두 문제가 겹친다. ①주석이 금지한 일을 세 줄 아래에서 하고 있어, 나중에 실 DB를 붙이는 사람에게 "여기에 적어도 된다"는 신호를 준다. ②compose에서 `environment:` 는 `env_file:` 을 **덮어쓴다.** 운영자가 `.env` 에 `SPRING_DATASOURCE_PASSWORD` 를 넣으면 조용히 무시되고 `postgres` 가 쓰인다 — 접속 실패로 드러나면 다행이고, 개발 값과 운영 값이 같으면 드러나지도 않는다. Python 쪽 `x-app` 도 `DATABASE_URL` 에 같은 문제가 있으나 그건 기존 관행이고, **새로 들어온 Kotlin 블록은 `${SPRING_DATASOURCE_PASSWORD:-postgres}` 형태로 `.env` 우선을 살릴 수 있다.**

### S-2 [권고] Kotlin 컨테이너가 쓰지도 않는 모든 벤더 키를 주입받는다 — 마감 **Phase 3**

`env_file: .env`(`docker-compose.yml:27`) 때문에 `kotlin-api`·`kotlin-worker` 가 `ANTHROPIC_API_KEY`·`OPENAI_API_KEY`·`FERNET_KEY`·`JWT_SECRET`·`DATA_GO_KR_API_KEY` 를 전부 받는다(실측). Phase 1의 Kotlin은 이 중 **하나도 쓰지 않는다.** 환경변수라는 경로 자체는 I-12를 만족하므로 위반은 아니지만, 폭발 반경이 필요보다 넓다.

같은 자리에서 드러나는 실질 문제: **Python의 `.env` 키 이름과 Spring 완화 바인딩 사이에 다리가 없다.** `JWT_SECRET` 은 `easydoc.auth.jwt-secret` 으로 바인딩되지 않는다(그러려면 `EASYDOC_AUTH_JWTSECRET` 이어야 한다). Phase 3에서 이 다리를 놓을 때, 두 런타임이 **같은 JWT 시크릿과 같은 Fernet 키를 봐야 한다**는 것(§4.3, I-7·I-9 양방향 호환)이 절체의 전제이므로 이름 대응을 명시적으로 설계해야 한다.

### S-3 [권고] 런타임 이미지에 `curl` 을 설치한다 — 마감 필요해지면

`backend-kotlin/Dockerfile:46-48`. Python 이미지는 같은 상황에서 "slim 이미지에는 curl이 없다 — 이미 있는 python으로 확인한다"(`docker-compose.yml:98`)로 회피했는데 Kotlin은 반대로 갔다. healthcheck는 JVM 기동 없이도 `wget`(이미 있는 경우) 또는 compose의 `CMD-SHELL` + `/dev/tcp` 로 대체 가능하다. 판단은 운영 취향이나, 두 이미지가 다른 원칙을 쓰는 것은 기록해 둘 값어치가 있다.

### S-4 [권고] Gradle 배포본 체크섬이 고정돼 있지 않다 — 마감 **Phase 2**

`backend-kotlin/gradle/wrapper/gradle-wrapper.properties` 에 `distributionSha256Sum` 이 없다. `validateDistributionUrl=true` 는 URL 형식만 본다. 계획 §3.1이 "버전 조합을 lockfile과 version catalog에 고정한다"고 요구한 것의 마지막 한 칸이다 — 의존성은 락으로 고정했는데 **빌드 도구 자체는 고정되지 않았다.**

---

## 5. 축 4 — Kotlin/Spring 관용성

### 통과한 것 (검토함 — 지적 없음)

- **모듈 경계가 §3.2 그대로다.** 다섯 모듈뿐이고 새 모듈을 늘리지 않았다(`settings.gradle.kts:8`). parity 하네스와 Testcontainers 지원을 새 모듈이 아니라 `java-test-fixtures` 로 둔 판단이 옳다.
- **`core` 격리를 실행으로 강제한다.** `CoreModuleBoundaryTest` 가 7개 클래스의 **부재**를 `Class.forName` 으로 확인한다. Jackson 2(`com.fasterxml`)와 3(`tools.jackson`) 두 패키지를 모두 막은 것은 Boot 4에서 실제로 필요한 조치다. `core/build.gradle.kts:20` 이 Boot BOM을 `platform()` 으로만 쓰는 것(제약만 추가, jar 0개)도 정확하다.
- **`runtimeOnly(project(":infrastructure"))`** (`api/build.gradle.kts:16`, `worker/build.gradle.kts:14`) — 라우터가 JDBC·암호화·LLM SDK 타입을 컴파일 시점에 볼 수 없다. 문서가 아니라 **컴파일 에러**로 막는다. §3.2 의존 방향의 가장 강한 형태다.
- **`application` 이 `infrastructure` 를 모른다** — 포트는 `application` 이 선언하고 `infrastructure` 가 구현한다(`infrastructure/build.gradle.kts:16-17`). Python의 `Protocol` ↔ `repositories` 구조와 대응.
- **JPA 없음. `!!` 없음** — detekt `UnsafeCallOnNullableType` 활성 + `maxIssues: 0`(`detekt.yml:8,18`). `allWarningsAsErrors = true`(`build.gradle.kts:37`)가 실제로 deprecated API 사용을 빌드 실패로 잡아냈다는 기록도 있다.
- **의존성 락이 실제로 일을 했다.** `kotlinx-serialization-json 1.11.0` 이 **테스트 클래스패스의 stdlib만** 2.2.21→2.3.20으로 올린 드리프트를 락파일이 잡아냈고, 버전을 catalog에서 빼 BOM에 맡기는 것으로 되돌렸다(`libs.versions.toml:74-79`). 계획 §3.1이 금지한 상황을 게이트가 실제로 잡은 사례이므로 **락 배선이 장식이 아님을 증명한다.**
- **`FAIL_ON_PROJECT_REPOS`**(`settings.gradle.kts:20`), 락파일·wrapper 커밋, `build/` 미커밋 — 전부 확인.
- **`FlywayBaselineGuard`** — `baseline-on-migrate=true` 를 쓰지 않고 두 조건(Flyway 이력 없음 + 앱 테이블 존재, 그리고 지문 일치)을 모두 확인한 뒤에만 baseline 하는 설계가 §4.2-4를 정확히 옮겼다. 판정 근거를 `alembic_version` 의 존재가 아니라 **애플리케이션 테이블의 존재**로 삼아 §4.2-7("Kotlin이 `alembic_version` 을 읽지도 쓰지도 않는다")을 함께 지킨 것도 맞다.
- **`spring.main.keep-alive: true`**(`worker/application.yml:16`) — 기능 추가가 아니라 "worker는 상주 프로세스"라는 §3.2 의도에 런타임 형태를 맞춘 설정이다. compose 실행에서 worker가 exit 0로 즉시 죽는 것을 발견해 고친 과정이 기록돼 있고(§9.5), 살아 있는 컨테이너에서 `kotlin-worker Up` 을 확인했다.

### K-1 [수정 필요] `EasyDocProperties` 가 `worker` 에서 보이지 않는 모듈에 있다 — 마감 **Phase 5 착수 전**

**근거:** `api/src/main/kotlin/kr/easydoc/api/config/EasyDocProperties.kt:35` vs `worker/build.gradle.kts:11-18`

`worker` 는 `application`(implementation) + `infrastructure`(runtimeOnly)만 의존하고 **`api` 를 의존하지 않는다**(§3.2가 요구한 대로다). 그러므로 `kr.easydoc.api.config.EasyDocProperties` 는 worker 클래스패스에 아예 없다. `WorkerApplication` 의 `@ConfigurationPropertiesScan("kr.easydoc")`(`WorkerApplication.kt:19`)이 스캔할 대상이 하나도 없는 상태다.

같은 산출물이 `SecretConverter` 를 `infrastructure` 에 둔 이유를 이렇게 적었다(`SecretConverter.kt:14-15`):

> `api` 와 `worker` 둘 다 쓰므로 두 진입점이 공유하는 `infrastructure` 에 둔다. 각 진입점에 복제하면 한쪽만 고쳐지는 날이 온다.

**그 논거가 `EasyDocProperties` 에 더 강하게 적용되는데 따르지 않았다.** Phase 5 worker는 `easydoc.llm.*`(provider·model·effort·API 키)과 `easydoc.crypto.fernet-key` 가 반드시 필요하다 — 변환 worker가 LLM을 부르고 결과를 암호화해 저장하기 때문이다. 지금 옮기는 비용은 파일 한 개 이동이고, Phase 5에 가서 발견하면 "복제" 유혹이 붙는다. 그리고 설정 클래스가 두 벌이 되는 순간 **두 런타임이 다른 Fernet 키·다른 호출 상한을 볼 수 있다.**

### K-2 [수정 필요 / 판정 필요] `api` profile도 Flyway를 돌린다 — 선언된 불변식과 어긋난다 — 마감 **Phase 7 착수 전**

**근거:** `api/application.yml:21-27` (`spring.flyway.enabled: true`) vs `worker/application.yml:25-28` 및 `WorkerStartupTest.kt:20-22`

worker 쪽은 이렇게 못박았다.

> **Flyway 를 돌리지 않는다** — 마이그레이션 주체는 `migrate` profile 하나뿐이다. worker 도 migrate 하면 api·worker·migrate 셋이 동시에 기동될 때 Flyway 잠금 경합과 부분 적용 상태가 생긴다.

**그런데 `api` profile은 끄지 않았고, 실제로 돌고 있다.** 살아 있는 `kotlin-api` 로그:

```
org.flywaydb.core.internal.command.DbValidate | Successfully validated 2 migrations
org.flywaydb.core.internal.command.DbMigrate  | Current version of schema "public": 2
kr.easydoc.infrastructure.db.FlywayBaselineGuard | Flyway 마이그레이션 완료: applied=0 …
```

즉 "마이그레이션 주체는 하나뿐"이라는 선언이 api에 대해서는 성립하지 않는다. 오늘은 `kotlin-migrate` 가 먼저 끝나므로 무해하지만, **절체 시점에 api 복제본이 N개 뜨면 N개가 운영 DB에서 baseline 경로를 동시에 탄다.** Flyway가 잠금을 잡으므로 파손 가능성은 낮으나, 지문 불일치 시 N개가 동시에 기동 실패하고 원인 로그가 N배로 쌓인다.

**리더 판단이 필요한 지점:** api가 스스로 마이그레이션하는 것이 의도인가(그러면 worker의 주석과 `WorkerStartupTest` 의 서술을 고쳐야 한다), 아니면 `migrate` 전용으로 좁히는 것이 의도인가(그러면 api yml에 `flyway.enabled: false` 를 두고 migrate profile에서만 켜야 한다). 어느 쪽이든 **테스트가 그 결정을 고정해야 한다** — 지금 api 기동 테스트는 정반대(Flyway가 V1·V2를 적용했음)를 단언한다.

### K-3 [판정 필요] Spring Boot 4.0.7 vs 계획 문서의 "4.1 계열" — 마감 **Phase 2 착수 전**

구현자가 §2.1과 `libs.versions.toml:10-17` 에 근거를 갖춰 올린 항목이다. 리뷰어로서 판단하지 않고 그대로 리더에게 올린다. 다만 두 가지를 덧붙인다.

- 4.0.7 선택 근거(문서 spike가 Kotlin 2.2.0에서 통과했고 4.0.7의 2.2.21이 같은 마이너)는 §4.5의 "문서 파서 동등성이 깨졌을 때 원인 후보를 늘리지 않는다"와 일관되며, 이 전환의 목표("동작을 그대로 둔 채 런타임만 바꾸기")에 부합한다.
- 되돌리는 비용이 지금은 작다(catalog 2줄)는 구현자 평가에 동의한다. 다만 Phase 2에서 도메인 코드가 쌓이고 Phase 4에서 POI/PDFBox가 붙은 뒤에는 커진다. **판단 자체를 Phase 2 착수 전으로 미루지 않는 편이 좋다.**

### K-4 [권고] `CoreModuleBoundaryTest` 를 우회하는 방법 — 마감 **Phase 2 종료 전**

**근거:** `CoreModuleBoundaryTest.kt:24-34`

금지 목록이 **클래스 이름 7개**다. 다음은 잡히지 않는다.

- `jakarta.persistence.*` — JPA 애너테이션만 넣는 경우(`jakarta.persistence:jakarta.persistence-api` 단독). 규약이 명시적으로 금지한 JPA가 **이름 목록에 없다.**
- `org.springframework.data.*`, `org.springframework.core.*` 등 `ApplicationContext` 를 끌고 오지 않는 Spring 모듈
- PostgreSQL 외의 JDBC 드라이버
- 테스트의 KDoc이 스스로 인정하듯(`:17-20`) `implementation` → `api` 로 바꾸는 변경

테스트 자신이 한계를 적어 둔 것은 좋다. 더 튼튼한 형태는 **해석된 클래스패스를 검사**하는 것이다 — `core` 의 `runtimeClasspath` 에 `org.springframework:*`·`jakarta.persistence:*`·`*jdbc*` 좌표가 0개임을 Gradle 태스크나 테스트에서 단언하면 이름 목록을 유지보수할 필요가 없어진다.

### K-5 [권고] detekt `ForbiddenComment` 가 `TODO:` 를 허용하는데 주석은 반대로 말한다

`detekt.yml:40-48` 이 `comments` 목록을 `FIXME:`·`STOPSHIP:` 로 **교체**하므로 기본값에 있던 `TODO:` 가 빠진다. 바로 위 주석은 "미완성 표시는 산출물 문서에 적는다. 코드에 TODO 로 흘리면 추적되지 않는다"이다. 의도(Phase 경계를 명시한 것은 허용)는 이해되나, 규칙과 주석이 정반대를 말하고 있어 다음 사람이 어느 쪽을 믿을지 알 수 없다.

---

## 6. 축 5 — 테스트 적정성

### `tests=48` 이 실제로 무엇을 보장하는가

수치는 정확하다(7+7+5+4+4+4+10+2+2+3 = 48).

| 테스트 | 건수 | 실제 보장 | 평가 |
|---|---|---|---|
| `CoreModuleBoundaryTest` | 7 | core 테스트 런타임 클래스패스에 7개 클래스 부재 | 실행 검증. 단 K-4의 우회 여지 |
| `SecretTest` | 7 | `toString`/`equals`/`hashCode` 마스킹·상수시간 | **강함.** 이 축 최고 |
| `ParityActualTest` | 5 | 산출물 형식·경로·한글 비이스케이프·거부 조건 2종 | 형식 보장. 값 판정 아님(명시됨) |
| `PythonSchemaBaselineTest` | 4 | V1 ≡ 커밋된 지문, alembic_version 미생성, V2 additive, Python INSERT 생존 | **강함.** 단 P-2 |
| `FlywayBaselineGuardTest` | 4 | 빈 DB / 일치 / **불일치→기동 실패** / 멱등 | **강함.** 실패 경로 있음 |
| `HealthContractTest` | 4 | 200·본문·무인증·헤더 부재·의존 서비스 미진단 | 계약대로. `@WebMvcTest` 로 HTTP 경계 통과 |
| `ErrorContractTest` | 10 | 12개 예외 매핑 | **HTTP 경계 미통과 — C-2** |
| `ApiStartup*Test` ×2 | 4 | 실 서블릿 + 실 DataSource, JDK `HttpClient` 로 진짜 소켓 | 좋은 선택. 단 P-2 |
| `WorkerStartupTest` | 3 | 기동·비웹 컨텍스트·Flyway 미실행 | 좋음. 단 K-2와 서술 충돌 |

**강점으로 기록할 것:** 실패 경로 테스트가 실제로 있다. `FlywayBaselineGuardTest:56-73` 은 손으로 붙인 컬럼(`nickname`)에 대해 기동 실패 + Flyway 장부 미생성까지 단언하고, `ParityActualTest:78-90` 은 빈 케이스·비-json 파일명 거부를 확인한다. "성공 경로만 있는 모듈"이 아니다.

### T-1 [차단 ② 장치] = C-2

오류 계약 게이트가 HTTP 경계를 보지 않는다. §2 참조. 마감 **Phase 3 착수 전**.

### T-2 [수정 필요] = P-2

"기존 Python 스키마" 갈래 두 개가 모두 V1에서 파생된 대역이고, Alembic은 어느 테스트에서도 실행되지 않는다. §3 참조. 마감 **Phase 7 착수 전**.

### T-3 [수정 필요] 설정 바인딩 경로가 한 줄도 실행되지 않는다 — 마감 **Phase 3 착수 전**

**근거:** `EasyDocProperties`·`SecretConverter` 를 저장소 전체에서 grep한 결과 **정의 외 참조 0건**. 테스트 없음, 주입 지점 없음, yml에서 `easydoc.crypto`·`easydoc.llm` 키를 세팅하는 곳도 없음(`api/application.yml:47-51` 은 `cors-origins` 와 `jwt-expire-minutes` 만).

`EasyDocProperties` 의 KDoc은 "지금 세우는 것은 **바인딩 경로와 마스킹 보장**"이라고 적었다(`:28`). 마스킹 보장은 `SecretTest` 가 지키지만 **바인딩 경로는 한 번도 실행되지 않는다.** `SecretConverter` 가 없으면 `String` → `Secret` 바인딩이 실패하는데, 지금은 어떤 키도 세팅되지 않아 항상 기본값 `Secret.EMPTY` 로 가므로 변환기가 호출되지 않는다. 즉 이 Phase가 세웠다고 주장하는 것 중 하나는 검증되지 않았다.

**닫는 법이 싸다.** `@SpringBootTest(properties = ["easydoc.auth.jwt-secret=…"])` 로 바인딩된 값이 `Secret` 이 되고 `toString()` 이 마스킹되는지, 그리고 worker 컨텍스트에서도 같은지 단언하면 K-1도 함께 드러난다.

### T-4 [권고] `application` 모듈에 테스트 소스가 0개다

`application/src` 아래 파일이 없다(본 소스도 테스트도). Phase 1에서 경계만 세운다는 판단은 §3.2와 맞고 `README.md` 로 계약을 적어 둔 것도 좋다. 다만 `:application:test` 와 `:application:parityHarness` 가 NO-SOURCE로 지나가므로, 그 README의 계약("infrastructure를 의존하지 않는다")을 지키는 것은 빌드 스크립트 리뷰뿐이다. K-4의 클래스패스 단언을 만들 때 `application` 도 함께 넣으면 한 번에 해결된다.

---

## 7. 축 6 — 하네스 자체 (parity 게이트)

> 이 회차에서 가장 적대적으로 본 절이다. 이번 세션에서 codex가 이 영역을 세 번 연속 뚫었다는 사실을 전제로 읽었다.

### H-1 [통과] Critical #1 — `check_external` in-process 실행: **닫혔다**

`compare_parity.py` 의 옛 `check_external` 은 증거 파일의 `fixture_case`·`status`·`checked` 세 값만 읽었고, 그 세 값을 손으로 적은 6줄 JSON이 "외부 검증 1건"으로 인정됐다(X-1). 지금은 증거 파일을 **판정 근거로 읽지 않는다.**

```python
outcome = VERIFIERS[command](document, case.get("input"))
…
target = write_proof_record(…, produced_by="compare_parity.py", …)
```

`*.verified.json` 은 실행의 **기록**으로 덮어써지며, 산출물 없이 증거 파일만 있으면 "증거 파일만 있고 산출물이 없다 … 미검증으로 센다"로 명시 보고된다. 검증기 실행 자체가 예외를 던지면 "검증하지 못한 것을 통과로 세지 않는다"로 문제 처리된다. **설계가 옳다.**

부수적으로 X-11(`runtime` 필드를 아무도 읽지 않던 문제)도 닫혔다 — `runtime_problem()` 이 `compare_file` 과 `check_external` 양쪽에서 적용된다.

### H-2 [통과] Critical #2 — `provenance_problems()` 정본 재대조: **닫혔다**

`canonical_fixture(domain)` 이 `BUILDERS[domain]()` 를 **다시 돌려** `source`·`generator`·`normalization`·케이스 id 집합·개수·순서·기대값을 대조한다. 값 대조에 쓰는 정규화 규칙을 **정본이 선언한 것**으로 고르는 부분이 특히 정확하다.

```python
# 값 대조에는 **정본이 선언한** 정규화를 쓴다. 파일 쪽 선언은 위조 대상이므로 근거가 못 된다.
active, _ = _rules(list(canonical["normalization"]))
```

`main()` 에서 `actual` 유무와 무관하게 돌리는 것(`if pair.domain in BUILDERS: problems += provenance_problems(pair)`)도 맞다 — fixture가 위조됐다면 actual이 없어도 결함이다.

모듈 docstring이 보장하는 것 3가지·보장하지 못하는 것 3가지를 명시한 "신뢰 경계" 절은 이 하네스에서 가장 가치 있는 문서다.

### H-3 [**차단(Critical) ② 장치**] `VOLATILE_INPUT_FIELDS` 가 도메인 단위라 crypto 음성 케이스가 통째로 무방비다 — 마감 **Phase 4 종료 전**

**근거:** `compare_parity.py` `VOLATILE_INPUT_FIELDS` 와 `dump_parity_fixtures.py:524-597` (`build_crypto`)

```python
VOLATILE_INPUT_FIELDS: dict[str, frozenset[str]] = {
    "crypto": frozenset({"key", "token"}),
    "argon2": frozenset({"phc"}),
}
```

`crypto` 케이스의 `input` 은 **`{key, token}` 뿐**이다. 즉 이 도메인은 **입력 전체가 정본 대조에서 빠진다.** 남는 대조 대상은 `expected` 하나인데, 음성 케이스 3건의 `expected` 는 전부 동일하다.

| 케이스 | input (대조 안 됨) | expected (대조 됨) |
|---|---|---|
| `crypto-tampered` | `{key, token: 1바이트 flip}` | `{"outcome": "invalid_token"}` |
| `crypto-wrong-key` | `{key: other_key, token}` | `{"outcome": "invalid_token"}` |
| `crypto-garbage` | `{key, token: "not-a-fernet-token"}` | `{"outcome": "invalid_token"}` |

**결과:** `crypto-tampered.token` 을 임의의 쓰레기 문자열로 바꿔도 정본 대조를 통과하고, Kotlin은 그것을 디코드하지 못해 `invalid_token` 을 내며, 게이트는 **전건 일치로 닫힌다.** 그러면 이 케이스가 증명하려던 것 — "1바이트 변조가 **HMAC 검증에서** 거부된다" — 이 아무것도 증명되지 않은 채 통과한다.

이것은 `migration-safety-gate` I-7이 이름을 붙여 경고한 바로 그 실패다.

> **음성 케이스가 통과해 버리면 그것은 인증 암호화가 아니다** — AES-CBC만 구현하고 HMAC 검증을 빠뜨린 JVM 구현이 실제로 있다.

그리고 §5 Phase 7 즉시 중단 기준 1번("기존 Fernet 문서 복호화 실패")을 막는 유일한 장치다. **Kotlin crypto 코드가 아직 0줄이어도 장치가 무력하므로 차단으로 올린다.**

대조군으로 `argon2` 는 같은 구조인데 **덜 위험하다.** `expected.needs_rehash` 가 PHC의 파라미터 구획에서 파생되므로(`_HASHER.check_needs_rehash`), 파라미터가 약한 PHC로 바꿔치면 `needs_rehash` 가 `true`/`null` 로 바뀌어 대조에서 잡힌다. 즉 **"난수 필드라도 그 값에서 파생된 성질을 `expected` 에 담으면 대조가 산다"**는 해법이 이미 같은 파일 안에 실증돼 있다.

**권고(택1):**
- `VOLATILE_INPUT_FIELDS` 를 케이스 단위로 좁힌다 — `expected.outcome == "ok"` 인 케이스에서만 `token` 을 뺀다.
- 또는 값 대신 **파생 성질**을 대조한다: urlsafe-base64 유효성, 버전 바이트 `0x80`, 정본 토큰과의 바이트 거리 1.

### H-4 [수정 필요] CI parity 단계의 완화 두 개가 "미검증"을 "성공"으로 바꾼다 — 마감 **fixtures 가드 Phase 2 종료 전 / exit 2 사면 Phase 4 종료 전**

**근거:** `.github/workflows/ci.yml:143-160`

```bash
if [ ! -d parity/fixtures ]; then
  echo "::warning::parity/fixtures 가 아직 없다 — Phase 2에서 생성한다. 비교를 건너뛴다."
  exit 0
fi
…
if [ "$status" -eq 2 ]; then
  echo "::warning::parity 미검증 케이스가 남아 있다(종료 코드 2). Phase 3·4에서 닫는다."
  exit 0
fi
```

구현자는 **두 번째만** 미검증 항목 #2로 기록했다("Phase 4 종료 시 제거"). 첫 번째는 기록되지 않았다.

**도구 자체는 엄격하다.** 실측으로 확인했다.

```
$ compare_parity.py --fixture parity/fixtures --actual parity/actual   → EXIT=1
$ compare_parity.py --fixture <빈 디렉터리> --actual …                  → EXIT=1
  [도메인 누락] … 도메인 누락 11개 … (종료 코드 1)
```

즉 **비교기는 도메인 누락을 1로 내보내는데 CI 래퍼가 디렉터리 단위에서 그것을 0으로 되돌린다.** 비교기 docstring이 스스로 못박은 원칙("**도메인 누락도 1이다.** … 많이 지울수록 종료 코드가 약해지는 유인이 생긴다 — 그것이 정확히 이 게이트를 무력화하는 경로다")이 래퍼 한 줄에서 뒤집힌다. `parity/fixtures/` 를 통째로 지우면 CI는 초록이다.

**첫 번째 완화가 스스로 사라지지 않는다는 점이 핵심이다.** Phase 2에서 fixture가 생기면 조건이 거짓이 되어 조용히 무해해지는 것처럼 보이지만, 그 뒤로도 **디렉터리를 지우면 되살아나는 백도어**로 남는다.

### H-5 [수정 필요] `parityHarness` 는 산출물이 0건이어도 성공한다 — 마감 **Phase 4 종료 전**

**실측:** `./gradlew parityHarness --no-daemon` 재실행 결과 다섯 모듈의 태스크가 전부 실행되고 BUILD SUCCESSFUL.

```
> Task :core:parityHarness
> Task :infrastructure:parityHarness
> Task :worker:parityHarness
> Task :api:parityHarness
> Task :parityHarness
BUILD SUCCESSFUL
```

그런데 저장소 전체에 `@Tag("parity")` 는 **단 하나**뿐이다(`core/src/test/.../ParityActualTest.kt:100`). `api`·`worker`·`infrastructure` 는 테스트 클래스가 있는데도 parity 태그가 0건이고, 그래도 태스크가 성공한다.

**H-4와 합쳐지면 조용한 우회가 된다.** Phase 4에서 `infrastructure` 의 crypto parity 테스트가 태그를 잃거나 이름이 바뀌면:

1. `parityHarness` → 여전히 BUILD SUCCESSFUL (H-5)
2. 배선 확인 단계 → `_harness-selfcheck/kotlin.json` 은 `core` 가 쓰므로 여전히 존재 → 통과
3. `compare_parity.py` → crypto 산출물 없음 → **미검증(종료 코드 2)**
4. CI → 종료 코드 2 사면 (H-4) → **초록**

즉 "검사 대상 0건인데 성공으로 끝나는" 상태가 네 단계를 통과한다. 세 장치가 각각은 합리적인데 합성되면 게이트가 열린다.

**권고:** 각 모듈의 `parityHarness` 가 자기가 담당하는 도메인 산출물을 실제로 썼는지 단언하거나(모듈↔도메인 대응표), 배선 확인 단계를 "selfcheck 파일 존재"가 아니라 "**해당 Phase에서 기대되는 도메인 디렉터리가 `parity/actual/` 에 존재**"로 올린다.

### H-6 [권고] `outcome.bound` 를 계산해 놓고 판정에 쓰지 않는다 — 마감 **Phase 4**

`VerificationOutcome.bound` 는 "검증이 fixture 요청과 결합됐는가"를 뜻하고 `write_proof_record` 로 기록되지만, `check_external` 은 이 값을 **보지 않는다.** `case["input"]` 이 없으면 `wanted_key=""`·`wanted=[]` 가 되어 검증기가 "아무 키·아무 평문·1건이면 통과"로 퇴화한다. 지금은 정본 대조가 `input` 을 지키므로 실제 위험은 낮지만, **필드를 만든 이유가 이 상태를 표시하는 것이므로 `bound == False` 는 그 자체로 실패여야 한다.** 안 그러면 두 번째 방어선이 존재하지만 연결되지 않은 상태로 남는다.

### H-7 [권고] 정본에 없는 **도메인**은 아무도 보고하지 않는다

`provenance_problems` 는 도메인 **안의** "정본에 없는 케이스"를 잡지만, `main()` 은 `missing` 만 보고 `found - expected` 는 보지 않는다. 그리고 `provenance_problems` 자체가 `if pair.domain in BUILDERS` 조건이라 **BUILDERS에 없는 도메인 디렉터리는 정본 대조를 통째로 건너뛴다.** 필수 검사를 없애지는 못하므로 심각도는 낮으나, 케이스 단위에서 막은 것을 도메인 단위에서 열어 두는 비대칭이다.

### H-8 [권고] CI `kotlin` 잡의 `uv run` 이 잠금을 강제하지 않는다

`ci.yml:151` 은 `uv run python …` 을, `quality` 잡(`:48`)은 `uv sync --locked` 를 쓴다. 비교기가 **Python 정본 생성기를 다시 돌리는** 구조이므로(H-2), 이 잡의 Python 의존성이 잠금과 다르면 "정본"이 달라진다. `uv sync --locked` 를 먼저 두거나 `uv run --locked` 로 맞추는 것을 권고한다.

---

## 8. 계약 파일 자체의 감사 (`contracts/easy-doc-v1.yaml`, 1376줄)

이 파일이 Phase 3~6의 단일 기준이므로 `app/api/*.py`·`app/main.py`·`frontend/src/api/types.ts` 와 3자 대조를 별도로 수행했다. **살아 있는 FastAPI TestClient 실측을 포함한다.**

### 통과한 것 — 이 계약 파일의 정확도는 전반적으로 매우 높다

먼저 이것부터 적는다. 아래 지적 10건은 **광범위한 대조를 통과한 뒤 남은 잔여**다.

- **엔드포인트 14개** — 경로·메서드·경로 파라미터명(`document_id`·`conversion_id`·`workspace_id`)이 `app.openapi()` 와 1:1. `/api` 접두사 없음 주장도 정확(`app/api/auth.py:20` 만 prefix).
- **성공 상태 코드 14개 전부 일치** — 201 signup, 202 업로드, 204 삭제 2건, 201 작업 공간 생성 포함 데코레이터와 대조 완료.
- **경로별 오류 상태 코드** — 각 라우트에서 도달 가능한 예외를 `_MAPPINGS` 와 교차 확인. **선언됐는데 도달 불가한 코드 0건, 도달 가능한데 미선언인 코드 0건.** 502를 `POST /documents` 에만 단 것(요청 경로의 유일한 `QueueUnavailableError`; `LLMProviderError` 는 worker 전용)까지 정확하다.
- **응답 본문** — 11개 모델 전 필드의 이름(snake_case)·타입·nullable 일치. `types.ts` 와도 일치. FastAPI 생성 스키마보다 `required` 를 넓힌 판단(`:1194-1198`)이 옳다 — 라우트가 모든 필드를 명시 구성하고 `exclude_unset=False` 이므로 키가 항상 존재한다.
- **캐시 금지 헤더 10곳** — `PRIVATE_RESPONSE_HEADERS` grep 결과가 **정확히 10건**이고 `applies_to` 목록과 **정확히 일치**한다. 목록에 없는데 붙이는 곳 0건, 목록에 있는데 안 붙이는 곳 0건. I-6 관점에서 이 목록은 신뢰할 수 있다.
- **`Content-Disposition`** — 계약 `:617` 예시가 `export.py:127-138` 출력과 **바이트 단위로 일치**: `attachment; filename="easy-read.docx"; filename*=UTF-8''%EC%89%AC%EC%9A%B4%20%EA%B8%80.docx`.
- **`Location`**, **`WWW-Authenticate: Bearer`**, 422 배열에서 `input`·`ctx` 제거, `loc` 원소 `str()` 강제 — 전부 실측 확인.
- **인증** — HS256, 클레임 정확히 `sub/exp/typ`, `typ == "access"`, `require: [sub, exp, typ]`, 시크릿 32바이트 하한, 수명 3600, Argon2id `t=3,m=65536,p=4`, **로그인 성공 시에만 재해시**. I-8·I-9의 계약 쪽 근거로 그대로 쓸 수 있다. 인증이 검증보다 먼저라는 것도 실측(위조 토큰 + `limit=0` → 422가 아니라 401).
- **enum·입력 상한** — 변환 상태 4값, `ExportFormat`, 4,000자·10MB·500,000자·압축 예산·확장자 3종·limit 1~100·작업 공간 이름 50자·보존 30일 전부 코드와 일치.
- **인용된 오류 문자열 25개 전부** 원문과 축자 일치.
- **YAML 무결성** — PyYAML 파싱: 중복 키 0, 깨진 `$ref` 0(30여 개 전부 해석), 미사용 컴포넌트 0, `properties` 에 없는 필드를 `required` 로 지목한 곳 0, 본문을 내는데 content가 없는 응답 0.

### F1 [수정 필요] 세 번째 401 메시지가 존재하고, 계약의 "메시지는 두 가지" 서술이 사실과 다르다 — 마감 **Phase 3 착수 전**

- 계약 `:115-118` — "이메일 부재·비밀번호 불일치·토큰 만료·위조·**계정 삭제**를 모두 같은 401과 **같은 메시지**로 처리한다"
- 계약 `:876-878, 885-886` — "메시지는 **두 가지**가 나온다" (`인증이 필요합니다` / `이메일 또는 비밀번호가 올바르지 않습니다`)
- 코드 `app/api/deps.py:118` — `raise InvalidCredentialsError("인증 정보가 유효하지 않습니다")`

실측(유효 서명 토큰인데 `sub` 사용자가 DB에 없는 경우): `401 {"detail":"인증 정보가 유효하지 않습니다"}`. 이 문자열은 `contracts/` 에도 `docs/` 에도 **없다.**

**두 방향 모두 문제이므로 리더 결정이 필요하다.** (a) 계약이 사실과 다르다 — 계약대로 만든 Kotlin은 이 경로에서 다른 문자열을 내 parity가 깨진다. (b) 코드가 계약의 *의도*를 어긴다 — "유효한 토큰인데 계정이 없음"이 위조 토큰과 구분되므로 계약이 없다고 선언한 신호가 실재한다. 다만 이 신호를 보려면 **유효 서명 토큰이 먼저 있어야** 하므로 외부 공격자의 계정 열거 경로는 아니고, 실질 보안 영향은 낮다. 권고는 `deps.py:118` 이 `app/services/auth.py:69` 의 `_INVALID_CREDENTIALS_MESSAGE` 를 재사용하도록 고치고 계약을 그대로 두는 쪽이다 — 그러면 메시지가 두 가지로 실제로 줄어든다.

### F2 [**차단(Critical) ② 장치**] multipart `encoding.file.contentType` 제약이 구현에 없다 — 마감 **Phase 4 착수 전**

계약 `:324-329` 가 `file` 파트의 Content-Type을 `application/vnd.openxmlformats-…document` / `application/pdf` / `application/hwp+zip` 중 하나로 제약한다.

**구현에는 그런 검사가 없다.** `app/api/documents.py:230` 은 **요청 수준** Content-Type만 보고(JSON/multipart 분기), 파트 자체의 Content-Type은 한 번도 읽지 않는다. 형식 판정은 전적으로 확장자다(`app/ingest/extractors.py:497-500`, `_FORMATS` 가 `.docx/.pdf/.hwpx` 키).

**실제 클라이언트가 깨진다.** `frontend/src/api/client.ts:102-106` 은 브라우저가 multipart Content-Type을 정하게 두고 `:166-171` 은 `file`·`workspace_id` 만 붙인다. `.hwpx` 는 OS MIME 매핑이 없어 브라우저가 `application/octet-stream` 을 보낸다. 게다가 `application/hwp+zip` 은 우리 **내보내기** mimetype이지(`app/easyread/export.py:64`) 업로드 mimetype이 아니다.

**왜 차단 ②인가.** 이 계약은 §6 Contract 게이트의 채점 기준이다. 기준이 틀린 채로 Phase 4 Kotlin이 이것을 성실히 구현하면 `.hwpx` 업로드가 거절되고, 그 구현은 **contract test를 통과한다** — 게이트가 이탈을 이탈로 보지 못한다. Kotlin 코드가 0줄이어도 기준이 틀렸으므로 차단으로 올린다. 조치는 `encoding` 블록 삭제 또는 산문으로 강등.

### F3 [**차단(Critical) ② 장치**] 요청 본문의 길이 제약 5개가 계약 자신의 오류 형식 규칙과 충돌하고, 코드보다 엄격하다 — 마감 **Phase 3 착수 전**

계약 `:1052`(email `maxLength: 255`), `:1057`(password `minLength: 8`), `:1107`(text `maxLength: 4000`), `:1180`(edited_text `maxLength: 4000`), `:1293-1294`(name `minLength: 1, maxLength: 50`).

**코드에서 이 다섯은 전부 스키마 제약이 아니라 서비스 계층의 도메인 규칙**이다(요청 모델은 전부 맨 `str` — `app/api/auth.py:23-34`, `documents.py:53-60, 84-87`, `workspaces.py:28-31`). 즉 위반 시 `InvalidInputError` → 422 **문자열** `detail`.

**계약 자신이 `:910-919` 에서 스키마 계층 실패는 422 배열이라고 못박았다.** 그러므로 이 키워드를 지키는 구현은 **같은 위반에 대해 계약이 규정한 것과 다른 `detail` 모양**을 내고, 사용자에게 보이던 한국어 안내("현재는 4,000자 이하 문서만…")가 사라진다.

**세 개는 코드보다 엄격하기까지 하다** — 코드가 **정규화 후** 길이를 재기 때문이다.

| 필드 | 코드가 재는 것 | 계약 스키마가 거절하는데 Python은 통과하는 예 |
|---|---|---|
| `email` | `.strip().lower()` 후 (`services/auth.py:192-193`) | 후행 공백 포함 256자 |
| `edited_text` | `strip_control_chars` **후** (`services/documents.py:399-402`) | 제어문자 10개 포함 4005자 |
| `name` | 제어문자 제거 + `.strip()` 후 (`services/workspaces.py:136-140`) | 선행 공백 2개 포함 52자 |

역방향도 있다: `"   "` 는 `minLength: 1` 을 통과하지만 Python은 422다.

**F2와 같은 이유로 차단 ②다.** 설명문은 규칙을 정확히 적고 있으므로 키워드만 제거하면 된다.

### F4 [수정 필요] CORS `max-age` 가 계약에 없다 — 마감 **Phase 3 착수 전 (C-3과 함께)**

계약 `:79-96`(`x-cors`)에 `max_age` 항목이 없다. `app/main.py:46-60` 은 `max_age` 를 넘기지 않아 Starlette 기본 **600** 이 나가고, 실측 preflight도 `access-control-max-age: 600` 이다. **Spring `CorsConfiguration` 기본은 1800** 이므로 계약에 적지 않으면 조용히 달라진다.

### F5 [수정 필요] CORS `allow_headers` 가 설정값이지 전선값이 아니다 — 마감 **Phase 3 착수 전 (C-3과 함께)**

계약 `:84` 는 `[Authorization, Content-Type]`. Starlette는 설정 목록에 `SAFELISTED_HEADERS` 를 합집합한다. 실측 전선값은 **`Accept, Accept-Language, Authorization, Content-Language, Content-Type`** 다섯이다(내가 §2 C-3에서 직접 본 Python preflight 응답과도 일치). 둘만 허용하는 Kotlin은 `Accept` 를 요청하는 preflight에서 실패한다. 전선값 5개를 적어야 한다.

### F6 [권고] FastAPI 내장 문서 라우트 4개가 목록 밖에 있다 — 마감 **Phase 6**

계약 `:30-31` 은 "엔드포인트는 정확히 14개다". `app.openapi()` 는 실제로 14개지만, `app/main.py:41` 이 `docs_url=None`/`openapi_url=None` 을 주지 않아 `/openapi.json`·`/docs`·`/docs/oauth2-redirect`·`/redoc` 이 **인증 없이** 서빙된다(`include_in_schema=False`). 제품 엔드포인트 오류는 아니지만 springdoc 기본 경로는 `/v3/api-docs`·`/swagger-ui.html` 로 다르므로, 절체 시 노출면이 조용히 바뀐다. 계약에 명시하거나 양쪽 다 끄는 결정을 남겨야 한다.

### F7 [권고] `format: binary` 는 OpenAPI 3.1에서 유효하지 않다

계약 `:622, 624, 626, 1132`. 문서는 `:1` 에서 `openapi: 3.1.0` 을 선언한다. 3.1은 JSON Schema 2020-12를 쓰므로 `format` 은 주석일 뿐이고 `binary` 는 정의되지 않는다 — 이진 페이로드는 `contentMediaType`/`contentEncoding` 으로 적는다. **파일 전체에서 유일한 3.0 잔재다**(`nullable:` 0건, 단수 `example:` 0건 확인). Phase 6에서 이 파일로 React 타입을 생성할 때 생성기에 따라 결과가 갈릴 수 있다.

### F8 [권고] no-store `does_not_apply_to` 목록에 `POST /documents`(202)가 빠졌다

계약 `:159-163`. `POST /documents` 는 `Location` 만 세우고 `PRIVATE_RESPONSE_HEADERS` 를 세우지 않는다(`documents.py:256`, 실측 202 헤더에 `cache-control` 없음). **규범 부분은 정확하다** — `applies_to`(`:148-158`)에 없고 202 응답 정의(`:331-345`)도 `Location` 만 선언한다. 산문 제외 목록만 불완전하다.

### F9 [권고] 미디어 타입 키에 파라미터가 붙어 있다

계약 `:623` 의 `text/plain; charset=utf-8`. `app/easyread/export.py:57` 과 정확히 같고 전선에도 charset이 실제로 붙으므로 **계약이 옳다.** 다만 일부 OpenAPI 도구는 맨 미디어 타입으로만 매칭해 이 키를 해석하지 못한다. Phase 6 타입 생성 시 도구 위험으로 기록해 둔다.

### F10 [권고] `sub` 가 UUID로 파싱돼야 한다는 규칙이 서술뿐이다 — 마감 **Phase 3**

계약 `:106` 은 `claim_sub: "사용자 UUID 문자열"` 이라고 서술만 한다. 코드는 `app/services/auth.py:245` 에서 `uuid.UUID(claims["sub"])` 를 호출하고 `ValueError`/`TypeError` 를 401로 정규화한다(`:246-248`). `sub` 를 불투명 문자열로 다루는 Kotlin은 **서명이 올바른데 `sub` 가 UUID가 아닌 토큰에서 401을 내지 않는다.**

---

---

## 9. Phase 1 종료 조건 대비 현황

> **계획 §5 Phase 1 종료 조건**: 빈 DB와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함.

| 항목 | 상태 | 근거 |
|---|---|---|
| 빈 DB에서 기동 + `/health` 응답 | **충족** | `ApiStartupOnEmptyDatabaseTest` 2건 (실 서블릿·실 DataSource·진짜 소켓) + compose 실측(8100) |
| 기존 schema snapshot에서 기동 + `/health` 응답 | **조건부 충족** | `ApiStartupOnPythonSnapshotTest` 2건. 단 snapshot이 **V1 파생 대역**이고 Alembic 실행본이 아니다(P-2), 실제 대상 DB dump는 미수집(P-3) |
| `backend-kotlin` 5모듈 골격 | **충족** | §3.2 그대로. 경계를 실행으로 강제(K-4 유보) |
| Flyway V1 = Python 스키마 | **충족 (독립 재검증)** | 지문 61줄 바이트 동일, 컬럼 34·제약 11·인덱스 11 전건 일치 |
| CI에 Kotlin build/test 추가, 기존 게이트 유지 | **부분 충족** | 세 잡 구성은 맞고 Python/React 잡 무변경. 단 실제 Actions 실행 미확인, parity 단계 완화 2건(H-4) |
| Dockerfile·compose Kotlin profile | **충족** | 기존 서비스 무변경, `profiles: ["kotlin"]` 뒤로 분리. 두 스택 동시 기동 실측 |

### 종료 조건 밖이지만 이 Phase에서 닫혔어야 하는 것

| 항목 | 상태 |
|---|---|
| §6 Contract 게이트("status/body/header/error가 v1 spec과 일치")를 판정할 장치 | **미충족 — C-2** (게이트가 HTTP 경계를 보지 않음) |
| Phase 0 산출물 `contracts/easy-doc-v1.yaml` 의 정확도 | **조건부 충족** — 대조 범위 대부분 통과, 잔여 10건 중 **차단 2건(F2·F3)**. 기준 자체가 틀린 자리가 남아 있다 |
| Phase 0 필수 조치 E(parity CI 배선) | **부분 충족** — 배선은 돌지만 H-4·H-5로 우회 가능 |
| Phase 0 필수 조치 D(`encryption_scheme`) | **충족** — V2 배치 판단 옳음 |
| Phase 0 parity 게이트 Critical 2건 | **충족** — H-1·H-2 닫힘 확인. 다만 같은 종류 H-3 잔존 |

### 미해결 항목 요약 (심각도·마감)

| ID | 항목 | 심각도 | 마감 |
|---|---|---|---|
| C-1 | 프레임워크 예외가 전부 500 (404/405/406/307 실측 이탈) | **차단 ①+②** | Phase 3 착수 전 |
| C-2 / T-1 | 오류 계약 게이트가 HTTP 경계 미통과 | **차단 ②** | Phase 3 착수 전 |
| F3 | 계약의 요청 길이 제약 5개가 계약 자신의 422 형식 규칙과 충돌·코드보다 엄격 | **차단 ②** | Phase 3 착수 전 |
| F2 | 계약의 multipart `contentType` 제약이 구현에 없음 (`.hwpx` 업로드 파손) | **차단 ②** | Phase 4 착수 전 |
| H-3 | crypto 음성 케이스가 정본 대조에서 통째로 빠짐 | **차단 ②** | Phase 4 종료 전 |
| H-4 | CI parity 완화 2건이 미검증을 성공으로 바꿈 | 수정 필요 | Phase 2 / Phase 4 종료 전 |
| H-5 | `parityHarness` 가 산출물 0건에도 성공 (H-4와 합성) | 수정 필요 | Phase 4 종료 전 |
| C-3 + F4 + F5 | CORS 미설정 / `max-age` 누락 / `allow_headers` 가 전선값 아님 | 수정 필요 | Phase 3 착수 전 |
| F1 | 세 번째 401 메시지 — 계약 서술이 사실과 다름 | 수정 필요 / 판정 필요 | Phase 3 착수 전 |
| K-1 | `EasyDocProperties` 가 worker에서 안 보임 | 수정 필요 | Phase 5 착수 전 |
| K-2 | api profile도 Flyway 실행 — 선언과 충돌 | 수정 필요 / 판정 필요 | Phase 7 착수 전 |
| P-1 | 지문 사각(collation·확장 버전 등) | 수정 필요 | Phase 7 착수 전 |
| P-2 / T-2 | Alembic이 회귀 경로에 없음 | 수정 필요 | Phase 7 착수 전 |
| P-4 | `conversions.updated_at` 갱신 장치 부재 | 수정 필요 | Phase 3 종료 전 |
| T-3 | 설정 바인딩 경로 미실행 | 수정 필요 | Phase 3 착수 전 |
| K-3 | Boot 4.0.7 vs 계획의 "4.1 계열" | **판정 필요** | Phase 2 착수 전 |
| P-3 | §4.2-1 실제 DB dump 미수집 | **확인 불가 → 리더 확인** | Phase 7 착수 전 |
| C-4, P-5~P-8, S-1~S-4, K-4·K-5, T-4, H-6~H-8, F6~F10 | 위 본문 참조 | 권고 | 각 항목 |

---

## 10. 미실행 · 확인 불가 항목

**돌리지 않은 것을 됐다고 적지 않는다.**

| # | 항목 | 왜 |
|---|---|---|
| 1 | `./gradlew build` 전체(48건) 직접 재실행 | 구현자 보고와 `parityHarness` 재실행만 확인했다. 48건이 **무엇을 보장하는지**는 소스를 읽어 판정했고 그 결과가 §6이다 |
| 2 | GitHub Actions 실제 실행 | 구현자 §8-1과 동일. YAML 로직은 읽었고 parity 단계는 로컬 등가 명령으로 검증했으나, 러너 Docker 데몬 위 Testcontainers·`gradle/actions/setup-gradle@v4` 는 첫 push에서 처음 검증된다 |
| 3 | `compare_parity.py` 를 실제 fixture로 실행 | `parity/fixtures/` 가 없다. 없음·빈 디렉터리 두 조건의 종료 코드만 실측했다(둘 다 1). 비교기가 실제 fixture·산출물을 읽는 경로는 **Phase 2에서 처음 검증된다** |
| 4 | 역방향 검증기(`run_verify_crypto`/`run_verify_jwt`) 실행 | Kotlin 산출물이 없다. 코드 경로는 읽어 판정했다(H-1·H-3·H-6) |
| 5 | `migrate` profile의 컨테이너 exit 0 | 구현자 §9.1이 `docker inspect … exited exit=0` 로 기록했다. 재검증하지 않았다 |
| 6 | 계약 파일의 Kotlin 쪽 대조 | §8은 계약 ↔ **Python** 3자 대조다. Kotlin 구현이 계약을 지키는지는 `/health` 와 오류 매핑 외에는 대조할 대상이 없다(엔드포인트가 하나뿐) |
| 7 | 실제 절체 대상 DB 존재 여부 | P-3. 리더 확인 필요 |
| 8 | `privacy-gate` 의 Phase 1 감사 산출물 | `docs/migration/_workspace/` 에 `01_privacy-gate_*` 가 없다. 축 3 판정은 **잠정**이며, `privacy-gate` 결과와 갈리면 그쪽을 따른다 |

---

## 11. 리더에게

1. **2차(교차 종합) 재호출이 필요하다.** `codex-reviewer` 산출물(`01_skeleton_codex-reviewer.md`)과 이 파일을 입력으로 다시 불러 주면 `01_skeleton_cross.md` 를 낸다. 어간은 `01_skeleton` 으로 고정했다.
2. **차단 5건(C-1, C-2, F3, F2, H-3)** 이 남아 있으므로 이 산출물만으로 Phase 1 종료를 보고하지 않는다. 다만 심각도와 "Phase 2 착수를 막는가"는 별개 축이며, 다섯 건의 마감이 모두 Phase 3~4다 — **착수 차단 여부의 판정은 리더 몫**이다. 성격이 갈리므로 구분해 둔다.
   - **C-1** 은 살아 있는 서버에서 지금 재현되는 **현재형 이탈**이다(404·405·406·307 → 전부 500).
   - **C-2, H-3** 은 아직 쓰이지 않는 **게이트의 무력화**다.
   - **F2, F3** 은 **기준 문서 자체의 오류**다 — 성실히 구현할수록 Python과 달라지고, 그 구현이 contract test를 통과한다. 고치는 비용은 지금이 가장 싸고(계약 YAML 몇 줄), Phase 3·4 코드가 그 위에 쌓이면 되돌리는 비용이 커진다. **다섯 중 지금 닫기를 가장 권하는 두 건이다.**
3. **판정을 요청하는 항목 5건**: K-3(Boot 4.0.7 vs 4.1), K-2(api profile이 마이그레이션 주체인가), P-3(절체 대상 실 DB가 존재하는가), F1(계약을 사실에 맞출 것인가 `deps.py:118` 을 계약에 맞출 것인가), F6(FastAPI 문서 라우트를 계약에 넣을 것인가 양쪽 다 끌 것인가).
4. **범용 품질 리뷰는 이 리뷰에 포함되지 않았다.** 성능·유지보수성·일반 보안 관점이 필요하면 글로벌 `multi-review` 스킬을 별도로 돌리기를 권고한다.
