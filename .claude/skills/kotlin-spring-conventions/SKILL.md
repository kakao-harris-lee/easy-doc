---
name: kotlin-spring-conventions
description: Kotlin/Spring Boot 코드를 작성·수정·리팩터링할 때 반드시 사용. backend-kotlin/ Gradle 멀티모듈 경계와 의존 방향, Python app/ → Kotlin 모듈 매핑, Java 21·Spring MVC·JdbcClient·Flyway·Testcontainers 기술 고정, LLM provider 어댑터 격리, 마스킹 선행 불변식의 타입 강제, {"detail":...} 오류 계약, FOR UPDATE SKIP LOCKED lease 패턴, 트랜잭션 경계, ktlint/detekt 스타일, Gradle 명령을 정의한다. 새 모듈·엔티티·리포지터리·라우터·워커를 만들 때, 기존 Kotlin 구현을 다시 손보거나 수정·보완·업데이트할 때, 빌드 스크립트나 version catalog를 고칠 때, Kotlin 테스트 코드를 새로 쓰거나 테스트 배치·태그·계층 구조를 고칠 때 사용. 이 스킬은 코드를 쓸 때 쓴다 — 계약 조항이 무엇이어야 하는지의 정의는 api-contract-freeze, Python과 값이 같은지 실행해 증명하는 일은 python-kotlin-parity, 보안 불변식 감사와 차단 판정은 migration-safety-gate가 맡는다.
---

# Kotlin / Spring Boot 구현 규약 (easy-doc 전환)

이 규약은 `docs/plans/2026-08-11-kotlin-react-migration.md`의 §3.1~§3.2, §4.2, §4.4를
구현자가 매 파일에서 쓸 수 있는 형태로 옮긴 것이다. 계획 문서와 이 규약이 어긋나면
**계획 문서가 아니라 실제 Python 코드**를 기준으로 판단하고, 차이를 보고한다.

전환의 목표는 "더 나은 설계"가 아니라 **관측 가능한 동작을 그대로 둔 채 런타임만 바꾸는 것**이다.
포팅 중에 개선 아이디어가 떠오르면 코드에 넣지 말고 `docs/migration/_workspace/`에 메모로 남긴다.
품질 개선과 런타임 교체를 섞으면 회귀가 났을 때 원인을 가를 수 없다.

## 1. 모듈 구조와 의존 방향

```text
backend-kotlin/
  settings.gradle.kts
  gradle/libs.versions.toml
  core/             # 마스킹, 스타일 규칙, 프롬프트, 후처리, 도메인 타입·예외
  application/      # 인증, 문서, 작업 공간, 변환 유스케이스
  infrastructure/   # JDBC, 암호화, 문서 파서, LLM provider 구현, 작업 큐
  api/              # Spring MVC, 인증 필터, 오류·응답 계약
  worker/           # 변환 worker, 보존 만료 scheduler
```

의존 방향은 **한 방향으로만 흐른다**.

```text
api ─┐
     ├─> application ─> core
worker ─┘        └─> infrastructure ─> core
```

- `api`와 `worker`는 서로를 모른다. 두 실행 진입점은 같은 JAR을 공유하고 Spring profile
  (`api`, `worker`, `migrate`)로만 갈린다.
- `core`는 **Spring도 DB도 모른다**. `spring-*`, `jdbc`, `jackson-databind` 어느 것도
  `core`의 의존성에 넣지 않는다.
- `application`은 `infrastructure`의 구현 클래스를 import하지 않는다. 필요한 것은
  `application`이 선언한 포트 인터페이스이고, `infrastructure`가 그것을 구현한다.
  (현재 Python이 `app/services/*.py`에서 `Protocol`로 저장소 계약을 선언하고
  `app/repositories/*.py`가 그것을 만족시키는 구조와 같다.)

### `core`가 Spring·DB를 몰라야 하는 이유

이 프로젝트에서 가장 자주 바뀌고 가장 자주 검증되는 것은 마스킹 규칙, 어려운 말 사전,
스타일 규칙, 보정 채택 규칙이다. 이들은 **골든셋 56개 문서와 parity fixture로 수천 번
반복 실행**된다. 여기에 Spring 컨텍스트 기동이나 Testcontainers가 끼면 한 번 돌리는 데
수십 초가 붙고, 결국 실행 빈도가 떨어져 회귀를 늦게 발견한다.

더 중요한 이유는 **Python과의 동등성 증명**이다. Phase 2의 parity suite는 외부 API·DB
없이 JSON fixture만 읽어 두 런타임의 출력을 비교한다(계획 §5 Phase 2 종료 조건).
`core`가 Spring을 요구하는 순간 이 비교는 "Kotlin 앱이 뜬 상태"를 전제하게 되고,
불일치가 났을 때 도메인 로직 문제인지 배선 문제인지 가릴 수 없다.

판단 기준: **fixture 파일 하나와 순수 함수만으로 검증할 수 있는 로직인가?** 그렇다면 `core`다.

### version catalog와 dependency locking

- 모든 의존성 좌표와 버전은 `gradle/libs.versions.toml` **한 곳**에만 적는다.
  모듈 빌드 스크립트에는 `implementation(libs.xxx)` 형태만 쓴다.
- 이유: 이 프로젝트는 Spring Boot BOM, Kotlin, Jackson, LLM SDK, POI/PDFBox가 서로
  전이 의존성을 공유한다. 모듈마다 버전을 적으면 같은 라이브러리가 모듈별로 다른
  버전으로 해석되어, `api`에서는 되고 `worker`에서는 깨지는 상황이 생긴다.
- dependency locking(`dependencyLocking { lockAllConfigurations() }` + 커밋된
  `*.lockfile`)을 켠다. 이유: 전이 의존성이 조용히 올라가면 Fernet·JWT·문서 파서처럼
  **바이트 단위 호환이 걸린 지점**에서 재현 불가능한 차이가 난다. 락파일이 있으면
  "어제는 통과했는데 오늘 실패"의 원인 후보에서 의존성 드리프트를 제거할 수 있다.
- 버전은 **직접 고르지 않는다.** 계획 §3.1대로 구현 시작 시 호환성 spike를 돌려
  통과한 조합만 catalog에 적고, 근거(어떤 spike가 무엇을 확인했는지)를 catalog의
  주석이나 `docs/migration/_workspace/`에 남긴다. 임의로 최신 버전을 올려 쓰지 않는다.

## 2. Python → Kotlin 모듈 매핑

현재 Python 트리(2026-08-11 기준 실제 확인)를 아래처럼 옮긴다.

| Python 원본 | Kotlin 모듈 | 비고 |
|---|---|---|
| `app/api/auth.py`, `documents.py`, `workspaces.py` | `api` | 라우터 + 요청/응답 DTO. 비즈니스 판단 금지(현재도 서비스가 판단한다) |
| `app/api/errors.py` | `api` | 도메인 예외 → HTTP 매핑. §5 참고 |
| `app/api/deps.py` | `api` | Spring DI로 대체. 인증은 필터/argument resolver |
| `app/services/auth.py`, `documents.py`, `workspaces.py`, `conversion.py` | `application` | 유스케이스. 입력 규칙의 단일 기준 |
| `app/repositories/users.py`, `documents.py`, `conversions.py`, `workspaces.py` | `infrastructure` (포트 선언은 `application`) | |
| `app/db.py` | `infrastructure` | DataSource·트랜잭션 설정 |
| `app/models/user.py`, `document.py`, `conversion.py`, `workspace.py` | 도메인 타입·상태 enum → `core`, 행 매핑 → `infrastructure` | ORM 엔티티를 도메인 타입으로 그대로 쓰지 않는다 |
| `app/exceptions.py` | `core` | 도메인 예외 계층 |
| `app/text.py` | `core` | 제어문자 제거·정규화 |
| `app/privacy/masking.py` | `core` | 마스킹 규칙 — parity 대상 1순위 |
| `app/privacy/crypto.py` | `infrastructure` | Fernet 호환. 계획 §4.3 게이트 통과 전에는 손대지 않는다 |
| `app/easyread/style_rules.py`, `prompts.py`, `postprocess.py` | `core` | 프롬프트·스타일·보정 채택 |
| `app/easyread/export.py`의 파일명·`content_disposition` 생성 | `core` | 순수 문자열 로직이라 fixture로 검증 가능 |
| `app/easyread/export.py`의 바이트 생성, `hwpx.py` | `infrastructure` | POI/ZIP 의존 |
| `app/ingest/extractors.py` | `infrastructure` | POI/PDFBox/StAX |
| `app/llm/provider.py` (인터페이스), `fake.py` | `core` | 인터페이스와 fake는 Spring 없이 테스트 가능해야 한다 |
| `app/llm/openai_provider.py`, `anthropic_provider.py`, `factory.py` | `infrastructure` | 벤더 SDK는 **여기서만** import |
| `app/queue.py` | `infrastructure` | ARQ/Redis → PostgreSQL job table로 교체(§6) |
| `app/workers/tasks.py`, `purge.py`, `settings.py` | `worker` | |
| `app/config.py` | `api`/`worker` 각각의 `@ConfigurationProperties` | 비밀값은 로그·toString에 노출 금지 |
| `app/easyread/goldenset.py`, `judge.py`, `collection.py`, `bokjiro.py` | **이번 범위 밖** | 계획 §5 Phase 9. Python 오프라인 oracle로 남긴다 |

매핑에 없는 새 파일을 만들어야 한다면, 위 표의 어느 줄에 대응하는지 먼저 정하고
대응이 없으면 새 기능을 추가하려는 것이 아닌지 의심한다.

## 3. 기술 선택 (고정)

| 항목 | 고정값 | 바꾸지 않는 이유 |
|---|---|---|
| JVM | Java 21 toolchain | 장기 지원. `kotlin { jvmToolchain(21) }`로 로컬 JDK와 무관하게 고정 |
| 언어 | Kotlin 2.2 이상 | Spring Boot 공식 지원 범위. 실제 버전은 Boot BOM에 정렬 |
| 웹 | **Spring MVC** (WebFlux 아님) | 파이프라인의 무거운 구간이 전부 blocking이다 — JDBC, POI/PDFBox 파싱, Fernet 복호화, LLM SDK 동기 호출. WebFlux를 써도 이들 앞에서 스레드로 되돌아가야 하므로 non-blocking 이득은 없고, 스택 트레이스와 트랜잭션 전파만 어려워진다 |
| DB 접근 | **`JdbcClient` / Spring Data JDBC** (JPA 아님) | ① 기존 스키마·컬럼·제약 이름을 첫 절체에서 바꾸지 않는다(계획 §4.2) — JPA는 엔티티가 스키마를 주도하려 한다. ② 소유권 은닉 404는 `WHERE id = ? AND user_id = ?` 조건이 **모든 조회에 빠짐없이** 붙어야 성립하는데, 이 조건이 SQL에 눈에 보이는 편이 안전하다. ③ 워커 lease는 `FOR UPDATE SKIP LOCKED`가 필수인데 JPA 추상화 뒤에서는 실제 발행 SQL을 통제하기 어렵다. ④ 지연 로딩·1차 캐시·flush 타이밍이 만드는 암묵적 동작이 parity 검증의 변수를 늘린다 |
| 마이그레이션 | Flyway | §6 참고 |
| 테스트 | JUnit 5, (선택) Kotest, Testcontainers PostgreSQL, MockWebServer 또는 WireMock | §8 참고 |
| 문서 | Apache POI(XWPF), Apache PDFBox, namespace-aware StAX/JAXP | HWPX는 ZIP+XML 직접 조립. DTD·외부 엔터티 차단, 압축 해제 예산 유지 |
| 관측 | Micrometer + 구조화 로그 | 문서 ID·길이·상태·failure code만. 본문·개인정보 금지 |

**버전 문자열은 이 문서에 적지 않는다.** 정확한 버전은 구현 시작 시 호환성 spike를 통과한
조합을 `gradle/libs.versions.toml`에 고정하고, 이 문서는 "무엇을 쓰는가"만 정한다.
문서에 버전을 박으면 catalog와 어긋난 순간 어느 쪽이 진실인지 알 수 없게 된다.

## 4. 보안 불변식의 Kotlin 표현

### 4.1 LLM 추상화 (프로젝트 `CLAUDE.md` 아키텍처 규칙 1의 Kotlin판)

- `com.openai.*`, `com.anthropic.*` 같은 벤더 SDK import는 **`infrastructure` 모듈의
  provider 어댑터 파일 안에서만** 허용한다. `core`·`application`·`api`·`worker`의
  어느 파일에도 넣지 않는다.
- `core`에는 `LlmProvider` 인터페이스와 공통 응답 타입만 둔다. 공통 타입이 노출하는 것은
  계획 §4.6대로 text / provider·model / input·output token / finish reason과 truncated
  여부까지다. SDK 응답 객체를 그대로 반환하면 벤더 타입이 도메인으로 새어 들어간다.
- 벤더 SDK를 `core`/`application`이 못 보게 하는 가장 확실한 방법은 **의존성에 넣지 않는 것**이다.
  Gradle에서 SDK를 `infrastructure`의 `implementation`(`api`가 아니라)으로 선언하면
  컴파일 단계에서 강제된다. 규칙을 문서로만 두지 말고 빌드로 강제한다.
- provider 구현체를 새로 추가할 때는 no-training 계약 조건을 주석으로 명시한다(프로젝트 규칙).

### 4.2 마스킹 선행 — 런타임 검사가 아니라 타입으로 강제한다

프로젝트 `CLAUDE.md` 아키텍처 규칙 2는 "사용자 문서 텍스트는 마스킹 파이프라인을 통과한
후에만 LLM에 전달될 수 있다"이다. Kotlin에서는 이것을 **시그니처로 표현한다.**

```kotlin
// core
@JvmInline
value class MaskedText private constructor(val value: String) {
    companion object {
        // 생성자를 private으로 막아 마스킹 파이프라인만 이 타입을 만들 수 있게 한다.
        internal fun wrap(masked: String) = MaskedText(masked)
    }
}

interface LlmProvider {
    // 원문 String을 받는 오버로드를 두지 않는다.
    fun complete(prompt: MaskedText, options: LlmOptions): LlmResult
}
```

- `MaskedText`는 마스킹 파이프라인 패키지 안에서만 생성 가능해야 한다. 아무 데서나
  `MaskedText(rawText)`로 감쌀 수 있으면 타입은 주석과 같아진다.
- **왜 런타임 검사(`require(isMasked(text))`)보다 나은가**: 런타임 검사는 그 코드 경로가
  실제로 실행돼야 발동한다. 마스킹을 건너뛰는 새 경로는 대개 테스트가 없는 경로이므로,
  검사는 운영에서 처음 터진다. 타입 강제는 **컴파일이 안 된다** — 검토자가 놓쳐도,
  테스트가 없어도, PR이 병합될 수 없다. 보안 불변식은 실패 시점을 최대한 앞으로
  당길수록 가치가 있다.
- 같은 원리를 다른 불변식에도 적용한다. 예를 들어 자리표시자 복원(`restore_placeholders`
  대응 로직)은 내보내기 경로 전용이므로, 복원된 텍스트를 별도 타입으로 감싸 조회 응답
  DTO가 그 타입을 받을 수 없게 만든다. 현재 Python은 이것을 모듈 docstring의 규약으로만
  지키고 있는데(`app/easyread/export.py`), Kotlin에서는 타입으로 올릴 수 있다.
- 암호문도 마찬가지다. 복호화된 평문과 암호문을 같은 `String`으로 두면 저장 시점에 어느
  쪽을 넣었는지 타입이 말해주지 않는다.

## 5. 오류 계약 — `ProblemDetail`을 그대로 노출하지 않는다

Spring Boot는 기본적으로 RFC 9457 `ProblemDetail`(`{"type","title","status","detail","instance"}`)을
내보낸다. 우리 v1 계약은 **`{"detail": ...}`** 한 가지다(`app/api/errors.py`).
`ProblemDetail`도 `detail` 키를 갖기 때문에 겉보기에는 비슷해서 그냥 두기 쉬운데,
그대로 두면 다음이 깨진다.

1. 추가 키(`type`, `title`, `instance`)가 응답에 섞인다. React `client.ts`의
   `readErrorMessage`는 `detail`만 읽으므로 화면은 당장 안 깨지지만, 계약 테스트와
   OpenAPI 스키마는 어긋난다.
2. `Content-Type`이 `application/problem+json`이 된다. `application/json`을 기대하는
   프록시·클라이언트가 있으면 거기서 갈린다.
3. **422의 `detail`은 문자열이 아니라 배열이다.** 현재 검증 실패 응답은
   `{"detail": [{"loc": [...], "msg": "...", "type": "..."}]}` 형태이고, 도메인 예외 응답은
   `{"detail": "사람이 읽을 한국어 문장"}`이다. React가 두 모양을 모두 처리한다
   (`frontend/src/api/client.ts`의 **`readErrorMessage` 함수** — 행 번호가 아니라 함수
   이름으로 찾는다). Spring 기본 동작으로는 이 union을 재현할 수 없다.
4. Spring 기본 검증 실패는 400인데 우리 계약은 422다.

따라서 `@RestControllerAdvice` 전역 핸들러를 **직접** 둔다.

- 도메인 예외 → 상태 코드 매핑은 `app/api/errors.py`의 `_MAPPINGS`를 그대로 옮긴다.
  실제 값(2026-08-11 코드 확인): 입력 오류·지원하지 않는 형식·추출 실패 → 422,
  업로드 크기 초과 → **413**, 이메일 중복·상태 충돌 → 409, 자격증명 오류 → 401
  (+`WWW-Authenticate: Bearer`), 소유권/부재 → 404, LLM·큐 장애 → 502, 설정 오류 → 503,
  저장소 오류 → 500.
- 매핑되지 않은 도메인 예외와 도메인 밖 예외 각각에 백스톱을 둔다. Python은 각각
  `"요청을 처리하지 못했습니다"`, `"서버 오류가 발생했습니다"` 고정 문자열을 쓴다.
  **예외 메시지를 그대로 detail에 넣지 않는다** — 무엇이 담길지 알 수 없는 예외에서
  본문·개인정보가 새는 경로다.
- 검증 실패 응답에서 **입력값 에코를 걷어낸다.** Spring의 `BindingResult`는
  `rejectedValue`를 들고 있고, 이것을 그대로 직렬화하면 비밀번호가 응답 본문과
  액세스 로그에 남는다. `loc`/`msg`/`type`만 남기고 값은 버린다(Python이 하는 그대로).
- 로그에는 예외 **타입 이름**만 적는다. `logger.error("...", e.message)` 금지.

## 6. DB 접근

### 6.1 Flyway baseline 인수 (계획 §4.2 요점)

1. 대상 DB의 schema-only dump와 `alembic_version` 값을 먼저 수집한다.
2. Alembic `0001~0006`의 기대 스키마와 **실제** 스키마를 비교한다. README에 `0003`을
   제자리 수정한 이력이 있으므로 마이그레이션 파일만 믿지 않는다.
3. 빈 DB용 `V1__python_schema_baseline.sql`을 만든다.
4. 기존 DB는 schema checksum이 일치할 때만 Flyway baseline version 1을 기록한다.
5. Kotlin 전용 변경은 `V2`부터. **모든 초기 변경은 additive**로만 한다 — 컬럼 삭제·
   이름 변경·타입 축소는 Python 제거와 관찰 기간 종료 뒤 별도 마이그레이션이다.
6. 한 환경에서 Alembic과 Flyway를 함께 실행하지 않는다. `alembic_version` 테이블은
   Python 제거 전까지 보존하고 Kotlin이 읽지도 쓰지도 않는다.

### 6.2 트랜잭션 경계

- **문서·변환·작업 행은 같은 트랜잭션에서 저장한다.** 현재 Python은 문서 저장소와 변환
  저장소가 같은 요청 세션을 공유해 커밋 하나로 함께 확정된다(`app/api/deps.py`의
  `get_conversion_repository` 주석). 여기에 작업 큐 등록까지 같은 트랜잭션에 넣으면
  "DB 커밋 성공, 큐 등록 실패" 간극이 사라진다 — 이것이 ARQ/Redis를 PostgreSQL job
  table로 바꾸는 가장 큰 이유다(계획 §4.4).
- 가입은 사용자와 기본 작업 공간을 **원자적으로** 만든다(현재 `AuthService`가 같은 세션의
  사용자·작업 공간 저장소를 함께 받는 이유). 사용자만 있고 작업 공간이 없는 계정이
  생기면 이후 모든 업로드가 실패한다.
- 트랜잭션은 `application` 계층(유스케이스 하나 = 트랜잭션 하나)에서 연다. 라우터나
  리포지터리 메서드마다 `@Transactional`을 흩뿌리면 경계가 어디인지 알 수 없어진다.
- LLM 호출·문서 파싱처럼 **수 초가 걸리는 작업은 절대 트랜잭션 안에서 하지 않는다.**
  커넥션을 붙잡은 채 외부 호출을 기다리면 풀이 마른다.

### 6.3 작업 큐 lease 패턴 (계획 §4.4)

`conversion_jobs` 테이블 예시 필드: `conversion_id`(PK/FK), `state`, `attempts`,
`next_attempt_at`, `lease_owner`, `lease_until`, `created_at`, `updated_at`.

```sql
-- worker가 작업을 가져가는 질의. 핵심은 SKIP LOCKED다.
WITH claimed AS (
    SELECT conversion_id
    FROM conversion_jobs
    WHERE state = 'pending'
      AND next_attempt_at <= now()
    ORDER BY next_attempt_at
    LIMIT :batch
    FOR UPDATE SKIP LOCKED
)
UPDATE conversion_jobs j
SET state = 'processing', lease_owner = :owner, lease_until = now() + :lease, attempts = attempts + 1
FROM claimed
WHERE j.conversion_id = claimed.conversion_id
RETURNING j.conversion_id;
```

- `SKIP LOCKED`가 없으면 워커 여러 대가 같은 행에서 서로를 기다려 처리량이 워커 1대와
  같아진다. `FOR UPDATE`가 없으면 두 워커가 같은 작업을 집어 **LLM을 두 번 호출**한다 —
  이것은 계획 §7의 즉시 중단 기준이다.
- lease 만료(`lease_until < now()`) 행은 재처리 대상으로 되돌린다. 워커가 강제 종료돼도
  작업이 `processing`에 영원히 갇히지 않게 하는 유일한 장치다.
- 재시도 정책을 **실패 종류로 가른다**: 도메인 실패·잘린 결과·provider 설정 오류는
  `failed`로 확정하고 자동 재시도하지 않는다(같은 입력으로 다시 불러도 같은 결과이고,
  LLM 비용만 는다). DB·일시 네트워크 오류만 제한 횟수와 backoff로 재시도한다.
- 완료된 변환은 다시 LLM을 호출하지 않도록 기존 idempotency 검사를 유지한다.
- **LLM SDK 자체의 자동 retry와 워커 retry가 겹치지 않게 한 계층만 재시도 책임을 갖는다.**
  SDK의 내장 재시도는 끄거나 0으로 두고 워커가 통제하는 편이 관측 가능하다. "문서당 최대
  2회 호출"이라는 제품 계약은 네트워크 재전송과 구분되어 메트릭에 드러나야 한다.
- 보존 만료 파기는 04:00 KST scheduler + PostgreSQL advisory lock으로 다중 워커 중복
  실행을 막고 500건씩 커밋한다.

## 7. 코드 스타일

- ktlint(포매팅) + detekt(정적 분석)를 Gradle에 붙이고 CI에서 강제한다. 포매팅 논쟁에
  리뷰 시간을 쓰지 않는 것이 목적이므로 규칙은 기본값에서 크게 벗어나지 않게 둔다.
- **주석·KDoc·사용자 노출 문자열은 한국어, 코드 식별자는 영어**(프로젝트 `CLAUDE.md`).
  오류 메시지의 한국어 문구는 React가 그대로 화면에 뿌리므로(`client.ts`) Python 문구를
  그대로 옮긴다. 문구를 "개선"하면 사용자에게 보이는 계약이 바뀐다.
- **`!!` 금지.** 예외는 없다. null이 올 수 없음을 아는 자리라면 타입을 non-null로 바꾸고,
  모르는 자리라면 `?:`로 도메인 예외를 던진다. `!!`는 "여기서 NPE가 나면 원인을 알 수
  없다"는 선언과 같다. detekt에서 오류로 설정한다.
- 플랫폼 타입(Java 라이브러리 반환값)은 경계에서 즉시 nullable로 받아 처리한다. POI·PDFBox는
  `null`을 자주 돌려주는데 Kotlin이 non-null로 추론하면 NPE가 도메인 깊은 곳에서 터진다.
- `Any`에 해당하는 회피(`Any?` 남용, unchecked cast)는 사유 주석 없이 금지. Python 쪽
  `Any`·`type: ignore` 규칙과 같은 취지다.
- 데이터 클래스의 `toString()`에 평문·암호문·비밀키가 실리지 않게 한다. 로그 한 줄이
  보안 규칙 위반이 되는 가장 흔한 경로다. 민감 필드를 가진 클래스는 `toString()`을
  직접 재정의한다.
- 도메인 상태는 `String`이 아니라 enum으로 둔다(`pending|processing|done|failed`).
  단, **DB와 API 경계에서는 소문자 문자열 그대로** 직렬화해야 한다 — 현재 DB CHECK 제약
  (`status IN ('pending','processing','done','failed')`)과 React 타입이 그 값을 기대한다.

## 8. 테스트 전략

세 계층을 **디렉터리와 태스크로 분리**한다. 느린 테스트가 빠른 테스트에 섞이면 개발 중
전체 실행을 안 하게 되고, 그것이 회귀를 늦게 발견하는 실제 원인이다.

| 계층 | 위치 | 도구 | 무엇을 검증하나 |
|---|---|---|---|
| 단위 | `core`, `application` | JUnit 5 (+Kotest 선택) | 마스킹, 스타일 규칙, 프롬프트 렌더링, 보정 채택, 파일명 생성, 유스케이스 분기. **Spring 컨텍스트 금지** |
| parity | `core` | JUnit + `parity/fixtures/{도메인}/*.json` | Python과 같은 입력에 같은 출력. fixture는 Python이 생성하고 두 런타임이 함께 읽는다 |
| DB | `infrastructure` | Testcontainers PostgreSQL | 제약, 트랜잭션 경계, `SKIP LOCKED` 잠금 동작, cascade, timezone |
| 외부 API | `infrastructure` | MockWebServer 또는 WireMock | provider 어댑터의 요청 형태·응답 파싱·오류/타임아웃/truncated 판정 |
| 계약 | `api` | `@SpringBootTest` + MockMvc | 상태 코드·헤더·오류 본문. 상세 절차는 `api-contract-freeze` 스킬 |

- **실제 LLM API를 부르는 테스트는 기본 실행에서 제외한다.** Python의
  `@pytest.mark.llm`에 대응하는 JUnit `@Tag("llm")`을 붙이고 기본 태스크에서 제외한다.
  비용 승인 후에만 돌린다.
- DB 테스트는 Testcontainers 컨테이너를 **재사용**하도록 설정한다(테스트 클래스마다
  새 PostgreSQL을 띄우면 전체 실행이 분 단위로 늘어난다).
- 새 기능 = 테스트 동반, 버그 수정 = 재현 테스트 먼저(프로젝트 규칙). 포팅 작업에서
  "재현 테스트"는 대개 **Python이 내는 값을 fixture로 고정한 differential test**다.

## 9. 명령어

Gradle wrapper는 아직 저장소에 없다. **최초 1회만** 시스템 Gradle로 만들고 커밋한다.

```bash
# 최초 1회 (spike로 고정한 Gradle 버전을 넣는다)
cd backend-kotlin && gradle wrapper --gradle-version <고정 버전>
```

이후에는 항상 wrapper를 쓴다. 로컬에 설치된 Gradle 버전 차이가 빌드 결과를 바꾸면
안 되기 때문이다.

```bash
cd backend-kotlin

./gradlew build                 # 전체 컴파일 + 테스트 + 린트
./gradlew :core:test            # core 단위 테스트만 (가장 빠름 — 반복 실행용)
./gradlew :application:test
./gradlew :infrastructure:test  # Testcontainers 필요 (Docker 데몬 기동 상태여야 함)
./gradlew :api:test             # 계약 테스트
./gradlew test                  # 전 모듈 테스트

./gradlew ktlintCheck           # 포매팅 검사
./gradlew ktlintFormat          # 포매팅 자동 수정
./gradlew detekt                # 정적 분석

./gradlew dependencies --write-locks   # 의존성 변경 후 락파일 갱신 (반드시 커밋)

./gradlew :api:bootRun --args='--spring.profiles.active=api'
./gradlew :worker:bootRun --args='--spring.profiles.active=worker'
```

커밋 전 필수 통과 순서: **ktlintCheck → detekt → test**. Python 쪽의
`ruff → mypy → pytest`와 같은 자리이며, 절체 전까지는 **양쪽 모두** 통과해야 한다.

프롬프트·스타일 규칙·LLM 설정에 해당하는 `core` 코드를 고쳤다면 Kotlin 테스트만으로
끝내지 말고 **`uv run pytest tests/golden`도 함께 돌려** 기존 기준선과 비교하고 결과를
보고한다(프로젝트 `CLAUDE.md` 테스트 규칙). Kotlin 골든 평가가 아직 없는 동안 Python
골든셋이 유일한 품질 oracle이다.

## 10. 작업 산출물 위치

- Kotlin 소스: `backend-kotlin/`
- API 계약: `contracts/easy-doc-v1.yaml`
- parity fixture: `parity/fixtures/{도메인}/*.json`
- 중간 산출물·조사 메모: `docs/migration/_workspace/{phase}_{agent}_{artifact}.{ext}`

중간 산출물을 저장소 루트나 `docs/` 최상단에 흘리지 않는다. 절체가 끝나면 `_workspace`는
통째로 정리 대상이다.
