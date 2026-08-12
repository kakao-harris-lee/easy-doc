# Phase 1 — Kotlin 골격과 CI

**작성:** kotlin-implementer / 2026-08-12
**기준:** 계획 `docs/plans/2026-08-11-kotlin-react-migration.md` §3.1·§3.2·§4.2·§5 Phase 1
**규약:** `.claude/skills/kotlin-spring-conventions/SKILL.md`

---

## 1. 모듈 구조와 의존 방향

계획 §3.2가 지정한 다섯 모듈을 그대로 만들었다. 새 모듈을 늘리지 않았다.

```text
backend-kotlin/
  settings.gradle.kts        # 다섯 모듈 include, FAIL_ON_PROJECT_REPOS
  build.gradle.kts           # toolchain·locking·ktlint·detekt·test·parityHarness 공통 설정
  gradle.properties
  gradle/libs.versions.toml  # 의존성 좌표·버전의 유일한 선언 지점
  gradle/wrapper/            # Gradle 9.1.0 wrapper (커밋함)
  config/detekt/detekt.yml
  .editorconfig              # ktlint 규칙 정본
  .dockerignore
  Dockerfile                 # api·worker·migrate 공용 이미지
  core/ application/ infrastructure/ api/ worker/
```

의존 방향:

```text
api ──> application ──> core
  └(runtimeOnly)─> infrastructure ──> application, core

worker ──> application ──> core
  └(runtimeOnly)─> infrastructure
```

### 그렇게 나눈 근거

| 결정 | 근거 |
|---|---|
| `core` 에 Spring·DB·Jackson 의존성을 **넣지 않음** | 계획 §3.2. Phase 2 종료 조건이 "외부 API·DB 없이 실행하는 parity suite"라 `core` 가 Spring 컨텍스트를 요구하면 그 조건이 성립하지 않는다. 문서가 아니라 `CoreModuleBoundaryTest` 가 7개 클래스(`ApplicationContext`·`SpringApplication`·`JdbcClient`·`Flyway`·`org.postgresql.Driver`·Jackson 2/3 `ObjectMapper`)의 **부재를 실행으로 확인**한다 |
| `api`·`worker` 가 `infrastructure` 를 **`runtimeOnly`** 로 의존 | 구현체는 런타임에 필요하지만, 컴파일 시점에 JDBC·암호화·(Phase 5의) LLM SDK 타입이 보이면 라우터가 그것을 직접 쓰기 시작한다. `runtimeOnly` 는 그 경로를 컴파일 에러로 막는다 |
| `application` 이 `infrastructure` 를 의존하지 **않음** | 포트는 `application` 이 선언하고 `infrastructure` 가 구현한다. 현재 Python이 `app/services` 의 `Protocol` ↔ `app/repositories` 로 하는 것과 같은 구조 |
| `api` ↔ `worker` 상호 의존 **없음** | 계획 §3.2. 같은 코드베이스를 공유하되 profile(`api`/`worker`/`migrate`)로만 갈린다 |
| parity 하네스를 **새 모듈이 아니라 `core` 의 `testFixtures`** 로 | §3.2의 모듈은 다섯 개뿐이고 테스트 지원 코드는 제품 모듈이 아니다. `java-test-fixtures` 로 두면 `infrastructure`·`api` 테스트가 `testFixtures(project(":core"))` 로 같은 하네스를 쓴다 |
| Testcontainers 기동 코드를 `infrastructure` 의 `testFixtures` 로 | 같은 이유. `api`·`worker` 기동 테스트가 같은 컨테이너 준비 코드를 쓴다 |
| `application` 본 소스가 **비어 있음** | Phase 1은 경계만 세운다. 모듈 계약은 `application/README.md` 에 적었다. KDoc만 있는 빈 `.kt` 파일은 ktlint 가 거부한다(`File should not be empty`) |

### Phase 1에 실제로 들어간 파일

| 모듈 | 파일 | Python 원본 |
|---|---|---|
| `core` | `exceptions/DomainExceptions.kt` | `app/exceptions.py` |
| `core` | `security/Secret.kt` | `app/config.py` 의 `SecretStr` 사용 (대응 타입) |
| `core` (testFixtures) | `parity/ParityActual.kt`, `parity/ParityHarnessSelfCheck.kt` | 신규 (Phase 0 필수 조치 E) |
| `application` | (본 소스 없음) `README.md` | — |
| `infrastructure` | `db/V1__python_schema_baseline.sql` | `migrations/versions/0001~0006` |
| `infrastructure` | `db/V2__encryption_scheme.sql` | 신규 (필수 조치 D) |
| `infrastructure` | `db/SchemaFingerprint.kt`, `db/FlywayBaselineGuard.kt` | 신규 (계획 §4.2-2·4) |
| `infrastructure` | `db/SecretConverter.kt` | 신규 (설정 바인딩) |
| `infrastructure` (testFixtures) | `PostgresTestSupport.kt` | 신규 |
| `api` | `ApiApplication.kt`, `health/HealthController.kt` | `app/main.py` |
| `api` | `error/GlobalExceptionHandler.kt` | `app/api/errors.py` |
| `api` | `config/EasyDocProperties.kt` | `app/config.py` |
| `worker` | `WorkerApplication.kt` | `app/workers/settings.py` (진입점만) |

**의도적으로 옮기지 않은 것**: `app/exceptions.py` 의 `GoldenCollectionError`·`WelfareApiError`.
둘은 운영자용 오프라인 스크립트 전용이고 HTTP 매핑 대상이 아니다. §9-1 결정으로 그 도구들은
Python 독립 검증 oracle 로 존치하므로 Kotlin 런타임에 대응물이 필요 없다.

---

## 2. 확정한 버전 조합

`backend-kotlin/gradle/libs.versions.toml` 이 정본이다.

| 항목 | 값 | spike 조합에서 바뀐 것 |
|---|---|---|
| JDK | Temurin **21.0.4** | 동일 |
| Gradle | **9.1.0** (wrapper 고정) | 동일 |
| Kotlin | **2.2.21** | spike 2.2.0 → Boot 4.0.7 BOM 정렬 (**변경**) |
| Spring Boot | **4.0.7** | spike에 없던 항목 (**신규 확정**) |
| Spring Framework | 7.0.8 (BOM) | — |
| Jackson | 3.1.4 (BOM) | — |
| JUnit | 6.0.3 (BOM) | — |
| Testcontainers | 2.0.5 (BOM) | — |
| Flyway | 11.14.1 (BOM) | — |
| PostgreSQL JDBC | 42.7.11 (BOM) | — |
| kotlinx-serialization | 1.9.0 (BOM) | 처음 1.11.0을 박았다가 **되돌림** — 아래 §2.3 |
| ktlint (Gradle 플러그인 / CLI) | 14.2.0 / 1.8.0 | 신규 |
| detekt | 1.23.8 | 신규 |

### 2.1 Spring Boot 4.0.7 을 고른 이유 — **리더 확인 요청 항목**

계획 §3.1은 "Spring Boot **4.1** 계열 후보"라고 적었으나, 그것은 "현재 공식 안정 버전 기준으로
spike 후 고정"이라는 단서가 붙은 후보였다. Maven Central 실측 결과 4.1.0(GA)이 존재한다.

두 계열의 관리 버전을 직접 대조했다.

| | Boot 4.0.7 | Boot 4.1.0 |
|---|---|---|
| Kotlin | **2.2.21** | **2.3.21** |
| Flyway | **11.14.1** | **12.4.0** |
| Spring Framework | 7.0.8 | 7.0.8 (동일) |
| Jackson / JUnit / Testcontainers / PG 드라이버 | 3.1.4 / 6.0.3 / 2.0.5 / 42.7.11 | 전부 동일 |

**4.0.7을 골랐다.** 근거 둘:

1. Phase 0 문서 spike(`00_kotlin-implementer_doc-spike.md`)가 POI 5.4.1 / PDFBox 3.0.5 /
   commons-compress 1.27.1 을 **Kotlin 2.2.0** 위에서 통과시켰다. 4.0.7의 Kotlin 2.2.21은
   같은 마이너 계열이라 그 spike 결과를 그대로 승계할 수 있다. 4.1.0의 2.3.21은 메이저 아닌
   마이너 점프지만, Phase 4에서 문서 파서 동등성이 깨졌을 때 원인 후보를 하나 더 늘린다.
2. 4.0 계열은 패치가 7번 누적됐고 4.1.0은 GA 직후다. 이 전환의 목표가 "동작을 그대로 둔 채
   런타임만 바꾸기"이므로, 프레임워크 자체가 변수로 들어오는 것을 줄이는 편이 낫다.

**리더 판단이 필요한 지점**: 계획 문서의 "4.1 계열" 문구와 어긋난다. 4.1.0으로 올릴지,
계획 문서 문구를 4.0 계열로 정정할지 결정이 필요하다. 지금 되돌리는 비용은 작다
(catalog 두 줄 + 재빌드).

### 2.2 Boot 4가 spike 이후 바꾼 것들 (실측)

Boot 4는 autoconfigure 와 테스트 슬라이스를 **기술별 모듈로 쪼갰다.** 구현 중 실제로 부딪힌 것:

| 찾던 것 | Boot 3 위치 | Boot 4 실제 위치 |
|---|---|---|
| `FlywayMigrationStrategy` | `spring-boot-autoconfigure` / `...autoconfigure.flyway` | `spring-boot-starter-flyway` / `org.springframework.boot.flyway.autoconfigure` |
| `@WebMvcTest` | `spring-boot-starter-test` / `...autoconfigure.web.servlet` | `spring-boot-starter-webmvc-test` / `org.springframework.boot.webmvc.test.autoconfigure` |
| Testcontainers PostgreSQL | `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` (2.x에서 좌표 변경) |

또한 **Jackson 3.1.4**(패키지 `tools.jackson`)가 관리 버전이다. Phase 2 이후 JSON 처리를
쓸 때 `com.fasterxml.jackson` 예제를 그대로 옮기면 컴파일되지 않는다. `CoreModuleBoundaryTest`
는 두 패키지 모두를 금지 목록에 넣었다.

### 2.3 dependency locking 이 실제로 잡아낸 드리프트

catalog 에 `kotlinx-serialization-json = 1.11.0` 을 직접 박았더니 락파일이 이렇게 갈렸다.

```text
org.jetbrains.kotlin:kotlin-stdlib:2.2.21=compileClasspath,runtimeClasspath,...
org.jetbrains.kotlin:kotlin-stdlib:2.3.20=testCompileClasspath,testFixturesCompileClasspath,testRuntimeClasspath
```

`dependencyInsight` 로 원인을 확인했다 — kotlinx-serialization 1.11.0이 stdlib 2.3.20을
요구해 **테스트 클래스패스의 stdlib 만** 2.2.21 → 2.3.20으로 올라갔다. 컴파일과 테스트 실행이
서로 다른 stdlib 을 보는 상태이며, 계획 §3.1이 금지한 "각자 임의 버전으로 섞는" 바로 그 상황이다.

**조치**: 버전을 catalog 에서 빼고 Boot BOM(1.9.0)이 관리하게 했다. 재확인:

```text
org.jetbrains.kotlin:kotlin-stdlib:2.2.21=compileClasspath,testCompileClasspath,
    testFixturesCompileClasspath,testRuntimeClasspath,kotlinCompilerClasspath,...
```

(detekt 자체 classpath 의 stdlib 2.0.21은 도구 내부 classpath 라 우리 코드와 무관하다.)

이로써 **BOM 밖에서 버전을 고르는 것은 Kotlin 플러그인·ktlint·detekt 셋뿐**이다. 셋 다
빌드 도구라 BOM 관리 대상이 아니다.

### 2.4 품질 게이트 설정에서 기본값을 벗어난 두 곳

| 항목 | 값 | 이유 |
|---|---|---|
| `.editorconfig`: `ktlint_class_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than = 2` | 기본 1 → 2 | 기본값이면 파라미터 1개짜리 도메인 예외 15개가 전부 3줄로 펴져 `app/exceptions.py` 와 나란히 읽기 어려워진다 |
| `detekt.yml`: `performance.SpreadOperator.active = false` | 기본 true → false | `runApplication<App>(*args)` 는 Spring Boot Kotlin 진입점의 표준 관용구다. 켜 두면 진입점마다 `@Suppress` 를 달게 되고 그 억제가 다른 곳으로 번진다 |

그 밖에는 기본값이다. `!!` 금지(`UnsafeCallOnNullableType`)는 error 로 유지했고 위반 0건이다.
Kotlin 컴파일러는 `allWarningsAsErrors = true` 다 — 실제로 deprecated API 사용
(`MockMvc.content { json(..., strict = true) }`)을 빌드 실패로 잡아 `JsonCompareMode.STRICT` 로 고쳤다.

---

## 3. Flyway baseline 과 Alembic 스키마 대조

### 3.1 baseline 작성 방법 — 파일을 읽지 않고 **실제로 돌렸다**

계획 §4.2-2가 "README에 `0003` 제자리 수정 이력이 있으므로 마이그레이션 파일만 믿지 않는다"고
요구했으므로, 추론 대신 실행했다.

```bash
docker run -d --name edschema-alembic -e POSTGRES_DB=easydoc -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres -p 55432:5432 pgvector/pgvector:pg16
DATABASE_URL="postgresql+asyncpg://postgres:postgres@localhost:55432/easydoc" \
  uv run alembic upgrade head
```

```text
INFO  [alembic.runtime.migration] Running upgrade  -> 0001, users 테이블 + pgvector 확장
INFO  [alembic.runtime.migration] Running upgrade 0001 -> 0002, users 이메일 소문자 CHECK 제약
INFO  [alembic.runtime.migration] Running upgrade 0002 -> 0003, documents·conversions 테이블
INFO  [alembic.runtime.migration] Running upgrade 0003 -> 0004, conversions 검수 수정본
INFO  [alembic.runtime.migration] Running upgrade 0004 -> 0005, documents.retention_expires_at 인덱스
INFO  [alembic.runtime.migration] Running upgrade 0005 -> 0006, workspaces 테이블 + documents.workspace_id
```

`alembic_version = 0006`. 그 DB에서 `pg_dump --schema-only` 과 **구조 지문 질의**를 뽑아
`V1__python_schema_baseline.sql` 을 작성했다.

### 3.2 대조 방식 — 지문(fingerprint) 텍스트

해시 하나가 아니라 **여러 줄 텍스트**로 뽑는다(`SchemaFingerprint`). 해시는 "다르다"만
알려주지만 텍스트는 어디가 다른지 diff 로 보여준다. 지문에 담는 것:

- `extension` (plpgsql 제외)
- `table`
- `column` — 테이블 · **서수** · 이름 · 타입 · NULL 여부 · DEFAULT 식
- `constraint` — `pg_get_constraintdef` 원문
- `index` — `pg_get_indexdef` 원문

`alembic_version` 과 `flyway_schema_history` 는 **제외**한다. 둘은 마이그레이션 도구의
장부이고 두 적용 경로(Alembic / Flyway)에서 정확히 한쪽에만 존재하므로 넣으면 절대 일치할 수 없다.

**서수를 담는 것이 핵심이다.** Alembic `0004` 는 `edited_text_encrypted`·`reviewed_at` 을,
`0006` 은 `documents.workspace_id` 를 `ADD COLUMN` 으로 붙여 뒤쪽 서수를 갖는다. 서수를 빼면
V1이 컬럼을 다른 순서로 선언해도 지문이 같아져 `SELECT *` 순서와 COPY 형식이 조용히 달라진다.
V1의 `CREATE TABLE` 컬럼 순서를 Alembic 결과에 맞춰 적었다.

### 3.3 대조 결과 — **전건 일치**

기준선은 `infrastructure/src/main/resources/db/baseline/python-schema-fingerprint.txt`
(61줄, Alembic 실행 결과). 회귀는 `PythonSchemaBaselineTest` 가 지킨다.

| 항목 | Alembic 0006 | Flyway V1 | 결과 |
|---|---|---|---|
| extension | `vector` | `vector` | 일치 |
| table | users, workspaces, documents, conversions (4) | 동일 | 일치 |
| column | 32행 (서수 포함) | 동일 | 일치 |
| constraint | 11행 (pk 4 · fk 4 · check 2 · unique 1) | 동일 | 일치 |
| index | 11행 | 동일 | 일치 |
| `alembic_version` 생성 | 함 | **안 함** | 의도된 차이 |

`PythonSchemaBaselineTest::V1만 적용한 스키마가 Alembic 0006 결과와 같다` 통과.

추가로 확인한 것:
- `V1 은 alembic_version 을 만들지 않는다` — 계획 §4.2-7. Flyway 가 만든 빈 장부가 있으면
  Python 롤백 시 Alembic 이 "0006까지 적용됨"이 아니라 "미적용"으로 읽는다.
- `Python 컬럼만 지정한 INSERT 가 성공한다` — Phase 7 관찰 기간의 롤백 조건. SQLAlchemy
  모델에 없는 `encryption_scheme` 은 INSERT 문에 나타나지 않으므로 DEFAULT 가 채워야 한다.

### 3.4 baseline 가드 — `baseline-on-migrate=true` 를 쓰지 않은 이유

계획 §4.2-4는 "기존 DB는 schema checksum이 **일치할 때만** Flyway baseline version 1을
기록한다"이다. Spring 의 `spring.flyway.baseline-on-migrate=true` 는 그 조건을 지키지 않는다 —
Flyway 이력이 없는 **모든** 비어 있지 않은 스키마를 아무 확인 없이 baseline 한다. 스키마가
기준선과 달라도 "V1은 이미 적용된 것"으로 기록하고 V2부터 얹으므로, V1이 만들었어야 할
테이블·제약이 없는 채로 앱이 뜬다. 문제는 첫 요청이 아니라 그 테이블을 처음 건드리는 경로에서 터진다.

그래서 `FlywayBaselineGuard`(`FlywayMigrationStrategy`)가 두 조건을 모두 확인한다.

1. Flyway 이력이 없고 애플리케이션 테이블이 이미 있다 (판정 근거는 `alembic_version` 의
   존재가 아니라 **애플리케이션 테이블의 존재**다 — §4.2-7대로 그 테이블은 읽지 않는다)
2. 그 스키마의 지문이 기준선과 **정확히 같다**

둘 다 맞으면 baseline(1) 후 V2 적용, 1은 맞는데 2가 틀리면 **기동 실패**. 회귀 4건이
`FlywayBaselineGuardTest` 에 있다(빈 DB / 일치하는 기존 스키마 / 컬럼이 추가된 기존 스키마 /
두 번 실행).

---

## 4. `encryption_scheme` 배치 판단 — **V2**

필수 조치 D는 "Phase 1 첫 Flyway에 additive로 추가"를 요구하고 V1/V2 선택은 구현자 판단으로 남겼다.
**V2에 넣었다.** 근거 셋:

1. **V1의 정의가 깨진다.** V1은 "Python 스키마 재현"이다(계획 §4.2-3). Kotlin 신규 컬럼이
   들어가면 그 정의가 무너지고, §3.3의 지문 대조가 성립하지 않는다.
2. **결정적인 이유 — 기존 DB에서 컬럼이 영원히 안 생긴다.** §4.2-4의 baseline 은 V1이 이미
   적용된 것으로 간주하고 **건너뛴다.** `encryption_scheme` 이 V1 안에 있으면 Alembic 이
   만든 DB에서는 그 컬럼이 만들어지지 않는다. V2에 두면 baseline 직후 정상 적용된다.
   (`FlywayBaselineGuardTest::기존 Python 스키마 — baseline 을 기록하고 V2만 적용한다` 가
   이 경로를 실제로 확인한다.)
3. 계획 §4.2-5가 "Kotlin 전용 변경은 V2부터 추가한다"고 이미 명시했다.

내용:

```sql
ALTER TABLE documents   ADD COLUMN encryption_scheme varchar(16) DEFAULT 'fernet-v1' NOT NULL;
ALTER TABLE conversions ADD COLUMN encryption_scheme varchar(16) DEFAULT 'fernet-v1' NOT NULL;
ALTER TABLE documents   ADD CONSTRAINT ck_documents_encryption_scheme_valid   CHECK (encryption_scheme IN ('fernet-v1'));
ALTER TABLE conversions ADD CONSTRAINT ck_conversions_encryption_scheme_valid CHECK (encryption_scheme IN ('fernet-v1'));
```

- 대상은 `documents`·`conversions` 둘 (현재 `key_version` 만 있는 테이블과 같다)
- 기본값 `'fernet-v1'`, 관찰 기간 내내 고정. AEAD 전환은 Phase 8 이후 별건
- CHECK 제약을 함께 건 이유: 알 수 없는 방식 이름이 조용히 들어가는 것을 막고, 새 방식 도입 시
  이 제약을 먼저 늘리는 마이그레이션을 쓰게 되므로 **스키마 이력에 전환 시점이 남는다**
- additive 규칙 준수 확인: `PythonSchemaBaselineTest::V2 는 encryption_scheme 을 additive 로
  추가한다` 가 기준선 컬럼 32행이 하나도 사라지지 않았음을 확인한다

---

## 5. parity CI 배선 (Phase 0 필수 조치 E)

### 준비한 것

`python-kotlin-parity` 스킬이 스스로 적어 둔 구멍:

> "그 산출물을 정말 Kotlin이 만들었는가"는 증명되지 않는다. (…) **Kotlin 테스트 하네스가
> `actual_file`을 쓰도록 CI에 배선하는 것이 유일한 방어**이며, Phase 1에서 그 배선을 만들 때
> 함께 확인한다.

| 만든 것 | 위치 | 하는 일 |
|---|---|---|
| `ParityActual` | `core` testFixtures | `parity/actual/{도메인}/` 아래 `{"runtime":"kotlin","cases":[...]}` 산출물을 쓴다. 경로는 **시스템 프로퍼티 `parity.actual.dir` 로만** 받고, 없으면 **던진다** |
| `ParityHarnessSelfCheck` | `core` testFixtures | Phase 1 배선 증명 전용 산출물 (`parity/_harness-selfcheck/kotlin.json`) |
| `parityHarness` Gradle 태스크 | 루트 + 각 모듈 | `@Tag("parity")` 테스트만 골라 돌리고 `parity.actual.dir` 를 **저장소 루트** `parity/actual` 로 준다 |
| `test` 태스크 | 각 모듈 | 같은 프로퍼티를 **모듈 `build/parity-actual/`** 로 준다 — 일반 테스트가 게이트 디렉터리를 건드리지 않는다 |
| CI `kotlin` 잡 3단계 | `.github/workflows/ci.yml` | ① `./gradlew parityHarness` ② 산출물 존재·`runtime: kotlin` 확인(없으면 `::error` 로 실패) ③ fixture 가 있으면 `compare_parity.py` 실행 |
| `ParityActualTest` 4건 | `core` test | 산출물 형식(`runtime`/`cases`/`id`/`actual`), 경로 규약, 한글 비이스케이프, 빈 케이스·비-json 파일명 거부 |

**설계 판단 — 경로를 코드에 박지 않는다.** 경로를 하드코딩하면 사람이 IDE에서 테스트 하나만
돌려도 게이트 디렉터리가 갱신되고, "게이트에 있는 파일은 CI가 만든 것"이라는 전제가 깨진다.
프로퍼티가 없으면 조용히 넘어가지 않고 **던진다** — 산출물이 안 만들어진 것을 아무도 모른 채
비교기가 "미검증(종료 코드 2)"만 계속 찍는 상황을 막는다.

**자체 점검 산출물을 `parity/actual/` 바깥에 둔 이유.** Phase 1에는 진짜 도메인 산출물이
하나도 없다. 게이트 디렉터리에 도메인이 아닌 디렉터리를 만들면 `compare_parity.py` 의
`EXPECTED_DOMAINS` 검사와 섞이므로, 형제 디렉터리 `parity/_harness-selfcheck/` 에 쓴다.

### 남은 것 (이번 Phase에서 채우지 못함)

| 항목 | 언제 |
|---|---|
| `parity/fixtures/` 자체가 저장소에 없다 | **Phase 2** — `dump_parity_fixtures.py` 로 생성. 그전까지 CI의 비교 단계는 `::warning` 을 찍고 건너뛴다 |
| masking·text·style·style-tables·prompts·postprocess·repair-adoption·export 산출물 | **Phase 2** (도메인 구현과 함께) |
| jwt·argon2 역방향 산출물 (`kotlin-issue.json` 등) | **Phase 3** (JWT·Argon2 구현 시) |
| crypto 역방향 산출물 (`kotlin-encrypt.json`) | **Phase 4** (Fernet 구현 시) |
| CI 비교 단계가 **종료 코드 2를 통과 처리**한다 | Phase 3·4에서 역방향 산출물이 채워지면 `exit 0` 허용을 제거해야 한다. **지금은 구멍이다** — 아래 §8 미검증 항목 참고 |

---

## 6. 종료 조건 검증

> **계획 §5 Phase 1**: 빈 DB와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함.

### 6.1 자동 검증 (Testcontainers PostgreSQL, 실제 서블릿 컨테이너)

`@SpringBootTest(webEnvironment = RANDOM_PORT)` + JDK `HttpClient` 로 **진짜 HTTP**를 부른다
(Spring 테스트 클라이언트가 아니라 실제 소켓).

```text
kr.easydoc.api.ApiStartupOnEmptyDatabaseTest  (2 tests, 0.496s)
   - 빈 DB 에서 기동하고 /health 가 200 ok 를 돌려준다
   - Flyway 가 V1·V2 를 적용했다
kr.easydoc.api.ApiStartupOnPythonSnapshotTest  (2 tests, 0.071s)
   - 기존 Python 스키마 위에서 기동하고 /health 가 200 ok 를 돌려준다
   - baseline 이 기록되고 alembic_version 은 그대로다
```

두 테스트가 각각 단언하는 것:

- 갈래 1(빈 DB): 응답 `200` / 본문 `{"status":"ok"}` / `flyway_schema_history` = `[1, 2]`
- 갈래 2(기존 스냅샷): 응답 `200` / 본문 `{"status":"ok"}` / `flyway_schema_history` = `[1(BASELINE), 2(SQL)]`
  / `alembic_version` = `0006` (건드리지 않음)

"기존 schema snapshot" 은 V1 적용 → Flyway 장부 제거 → `alembic_version` 삽입으로 만든다.
`PythonSchemaBaselineTest` 가 V1 ≡ Alembic 을 증명하므로 이 대역이 성립한다.

### 6.2 전체 테스트 결과

```text
kr.easydoc.core.CoreModuleBoundaryTest          7 tests
kr.easydoc.core.SecretTest                      7 tests
kr.easydoc.core.ParityActualTest                5 tests
kr.easydoc.infrastructure.db.PythonSchemaBaselineTest   4 tests (1.272s)
kr.easydoc.infrastructure.db.FlywayBaselineGuardTest    4 tests (6.465s)
kr.easydoc.api.HealthContractTest               4 tests
kr.easydoc.api.ErrorContractTest               10 tests
kr.easydoc.api.ApiStartupOnEmptyDatabaseTest    2 tests
kr.easydoc.api.ApiStartupOnPythonSnapshotTest   2 tests
kr.easydoc.worker.WorkerStartupTest             3 tests

합계: tests=48 failures=0
```

### 6.3 compose 기동 (§7에 실행 로그)

---

## 7. 실행한 명령과 결과

| 명령 | 결과 |
|---|---|
| `gradle wrapper --gradle-version 9.1.0` | BUILD SUCCESSFUL — wrapper 커밋됨 |
| `./gradlew build --write-locks` | **BUILD SUCCESSFUL** (컴파일 + ktlintCheck + detekt + test 48건) |
| `./gradlew ktlintCheck` | **BUILD SUCCESSFUL** |
| `./gradlew detekt` | **BUILD SUCCESSFUL** (weighted issues 0) |
| `./gradlew test` | **48 tests, 0 failures** |
| `./gradlew parityHarness` | **BUILD SUCCESSFUL** — `parity/_harness-selfcheck/kotlin.json` 생성 |
| `uv run alembic upgrade head` (임시 컨테이너) | 0001→0006 적용, `alembic_version = 0006` |
| `uv run ruff check .` | All checks passed! |
| `uv run ruff format --check .` | 134 files already formatted |
| `uv run mypy .` | Success: no issues found in 116 source files |
| `uv run pytest -q` | **820 passed, 68 skipped, 4 deselected** |
| `docker compose config --quiet` | OK (기존 서비스 6 + Kotlin 3) |
| CI YAML 파싱 | jobs = `quality`(8 steps) · `frontend`(6) · `kotlin`(9) |

Python 게이트는 손대지 않았고 그대로 통과한다 — `app/` 을 수정하지 않았다는 증거이기도 하다.

### 락파일

```text
api/gradle.lockfile            218 lines
application/gradle.lockfile     53
core/gradle.lockfile           118
infrastructure/gradle.lockfile 196
worker/gradle.lockfile         207
합계                            792
```

### parity 하네스 산출물

```json
{
    "runtime": "kotlin",
    "purpose": "Phase 1 배선 증명 전용. 게이트 판정에 쓰지 않는다.",
    "jvm": {
        "version": "21.0.4",
        "vendor": "Eclipse Adoptium",
        "kotlinVersion": "2.2.21"
    },
    "domainsPending": "masking·text·style·style-tables·prompts·postprocess·repair-adoption·export 는 Phase 2, jwt·argon2 는 Phase 3, crypto 는 Phase 4에서 채운다."
}
```

### `.gitignore` 갱신 내용

```gitignore
# Kotlin / Gradle
# 빌드 산출물과 데몬 상태는 커밋하지 않는다. 단 **gradle.lockfile 과 wrapper 는 커밋한다** —
# 락파일이 없으면 전이 의존성이 조용히 올라가고, wrapper 가 없으면 CI가 로컬과 다른
# Gradle 로 빌드한다(계획 §3.1).
backend-kotlin/build/
backend-kotlin/*/build/
backend-kotlin/.gradle/
backend-kotlin/*/.gradle/
backend-kotlin/**/bin/
!backend-kotlin/gradle/wrapper/gradle-wrapper.jar

# parity 산출물은 실행할 때마다 생기는 결과물이라 커밋하지 않는다.
# fixture(parity/fixtures/)는 정본이므로 Phase 2에서 생기면 커밋한다.
parity/actual/
parity/_harness-selfcheck/
```

`backend-kotlin/.dockerignore` 도 새로 추가했다 — 이미지 빌드 컨텍스트가 `./backend-kotlin`
이라 저장소 루트의 `.dockerignore` 가 적용되지 않는다.

### Docker / compose

- `backend-kotlin/Dockerfile` — 멀티 스테이지(temurin 21.0.4 jdk → jre). `api`·`worker`
  bootJar 두 개를 한 이미지에 담고 command 로 갈린다. 이미지 빌드에서 테스트를 돌리지 않는다
  (Testcontainers 가 Docker 데몬을 요구해 docker-in-docker 가 된다).
- `docker-compose.yml` — **기존 Python 서비스를 하나도 건드리지 않았다.** `kotlin-migrate`
  ·`kotlin-api`(8100)·`kotlin-worker` 셋을 `profiles: ["kotlin"]` 뒤에 추가해 기본
  `docker compose up` 동작이 그대로다. Kotlin 스택은 `docker compose --profile kotlin up -d --build`.
- `docker/postgres-init/10-kotlin-database.sql` — `easydoc_kotlin` DB 생성. 계획 §4.2-6
  ("한 환경에서 Alembic과 Flyway를 함께 실행하지 않는다")대로 **DB를 갈라** 두 도구가 서로의
  스키마를 밟지 않게 한다. Python 은 `easydoc`, Kotlin 은 `easydoc_kotlin`.
  절체 시 Kotlin 접속 대상을 `easydoc` 으로 바꾸면 `FlywayBaselineGuard` 가 baseline 경로를
  탄다 — 그 경로는 `ApiStartupOnPythonSnapshotTest` 가 이미 검증한다.

---

## 8. 미검증 항목 / 남은 위험

**돌리지 않은 것을 됐다고 적지 않는다.**

| # | 항목 | 상태 | 닫는 시점 |
|---|---|---|---|
| 1 | **CI가 실제 GitHub Actions 에서 도는 것을 확인하지 못했다.** YAML 파싱과 로컬 동등 명령(`./gradlew build`, `parityHarness`)만 확인했다. `gradle/actions/setup-gradle@v4`·러너 Docker 데몬 위 Testcontainers 동작은 **첫 push 에서 처음 검증된다** | 미검증 | 첫 PR |
| 2 | **CI parity 비교 단계가 종료 코드 2를 통과 처리한다.** 지금은 역방향 산출물이 없으니 정상이지만, Phase 4가 끝나도 이 완화가 남아 있으면 미검증 케이스를 게이트가 못 잡는다 | 의도된 임시 구멍 | Phase 4 종료 시 제거 |
| 3 | **`parity/fixtures/` 가 없어 `compare_parity.py` 를 한 번도 돌리지 못했다.** 배선(경로·형식·`runtime` 필드)은 `ParityActualTest` 로 확인했지만, 비교기가 실제로 이 파일을 읽는 것은 확인 못 했다 | 미검증 | Phase 2 |
| 4 | Testcontainers 컨테이너가 **모듈마다 따로 뜬다**. `testcontainers.reuse.enable=true` 를 줬지만 `withReuse(true)` 를 붙이지 않았고 Gradle 이 모듈별로 JVM 을 포크한다. 로컬 전체 테스트 16초라 지금은 문제가 아니나 CI에서는 더 든다 | 알고 남김 | 필요해지면 |
| 5 | `migrate` profile 이 **컨테이너에서** 정상 종료(exit 0)하는지 — 로컬 테스트로는 확인하지 못했다. `ApiApplication` 이 profile 을 보고 컨텍스트를 닫도록 명시했다 | §9 참고 | — |
| 6 | `easydoc_kotlin` DB는 **기존 볼륨에서 자동 생성되지 않는다**. initdb 스크립트는 데이터 디렉터리가 빌 때만 돈다 | 문서화함 | — |
| 7 | Spring Boot 4.0.7 vs 4.1.0 선택이 계획 문서 문구와 어긋난다 | **리더 판단 필요** | Phase 2 착수 전 |
| 8 | 검증 실패(422) 응답의 `detail` **배열** 형태를 아직 구현하지 않았다. Phase 1에는 요청 본문을 받는 엔드포인트가 없다. 옮길 때 `rejectedValue` 를 반드시 걷어내야 한다(비밀번호 유출 경로) | 의도적 미구현 | Phase 3 |
| 9 | **U-1**(미처리 500의 CORS 헤더)에 손대지 않았다. 리더 판단 전까지 CORS 자체를 설정하지 않았다 | 지시대로 보류 | Phase 3 착수 전 |
| 10 | `GlobalExceptionHandler` 를 HTTP 경계에서 검증하지 못했다 — 도메인 예외를 던지는 엔드포인트가 없어 핸들러를 직접 호출했다 | 부분 검증 | Phase 3 contract test |
| 11 | detekt 1.23.8은 Kotlin 1.9 파서를 내장한다. 지금 코드는 통과하지만 Kotlin 2.x 전용 문법을 쓰면 깨질 수 있다. detekt 2.x 는 `2.0.0-alpha.6` 뿐이라 채택하지 않았다 | 알고 남김 | detekt 2.x GA |
| 12 | Gradle configuration cache 를 켜지 않았다(ktlint/detekt 플러그인 호환 미검증) | 별건 | — |

---

## 9. compose 기동 로그 (종료 조건 보강)

Testcontainers 테스트에 더해 **실제 컨테이너 스택**에서도 확인했다.

### 9.1 `kotlin-migrate` — 스키마 적용 후 exit 0

```json
{"log":{"level":"INFO","logger":"org.flywaydb.core.FlywayExecutor"},
 "message":"Database: jdbc:postgresql://postgres:5432/easydoc_kotlin (PostgreSQL 16.14)"}
{"log":{"level":"INFO","logger":"...DbValidate"},
 "message":"Successfully validated 2 migrations (execution time 00:00.006s)"}
{"log":{"level":"INFO","logger":"...DbMigrate"},
 "message":"Migrating schema \"public\" to version \"1 - python schema baseline\""}
{"log":{"level":"INFO","logger":"...DbMigrate"},
 "message":"Migrating schema \"public\" to version \"2 - encryption scheme\""}
{"log":{"level":"INFO","logger":"...DbMigrate"},
 "message":"Successfully applied 2 migrations to schema \"public\", now at version v2 (execution time 00:00.075s)"}
{"log":{"level":"INFO","logger":"kr.easydoc.infrastructure.db.FlywayBaselineGuard"},
 "message":"Flyway 마이그레이션 완료: applied=2 targetSchemaVersion=2"}
```

```text
docker inspect easy-doc-kotlin-migrate-1 → exited exit=0
```

로그가 ECS JSON 한 줄씩 나온다 — Dockerfile 의 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`.
`FlywayBaselineGuard` 가 남기는 것은 **개수와 버전뿐**이고 본문·개인정보가 실릴 자리가 없다.

### 9.2 `/health` — Kotlin(8100)과 Python(8000)이 나란히 응답

```text
$ curl -s -i http://localhost:8100/health          # Kotlin
HTTP/1.1 200
Content-Type: application/json
Content-Length: 15

{"status":"ok"}

$ curl -s -i http://localhost:8000/health          # Python (비교 기준, 그대로 살아 있음)
HTTP/1.1 200 OK
server: uvicorn
content-type: application/json

{"status":"ok"}
```

Kotlin 응답에 `Cache-Control`·`X-Content-Type-Options` 가 **없다** — 계약대로다
(`/health` 는 캐시 금지 헤더 대상 10곳에 들어 있지 않다). Content-Type 은
`application/problem+json` 이 아니라 `application/json` 이다.

### 9.3 두 스택 동시 기동

```text
SERVICE         STATUS
api             Up 2 minutes (healthy)      # Python
worker          Up 2 minutes                # Python
frontend        Up 3 days
kotlin-api      Up 2 minutes (healthy)      # Kotlin
kotlin-worker   Up 20 seconds               # Kotlin
postgres        Up 2 minutes (healthy)
redis           Up 3 days (healthy)
```

### 9.4 DB 분리 확인 — Alembic 과 Flyway 가 서로를 밟지 않는다

```text
# Kotlin DB (easydoc_kotlin)
$ psql -d easydoc_kotlin -c "SELECT version, type, description FROM flyway_schema_history"
1|SQL|python schema baseline
2|SQL|encryption scheme

$ psql -d easydoc_kotlin -c "\d+ documents"
 key_version        | smallint              | not null | 1
 workspace_id       | uuid                  | not null |
 encryption_scheme  | character varying(16) | not null | 'fernet-v1'::character varying
 "ck_documents_encryption_scheme_valid" CHECK (encryption_scheme::text = 'fernet-v1'::text)

# Python DB (easydoc) — 손대지 않았다
$ psql -d easydoc -tAc "SELECT version_num FROM alembic_version"
0006
$ psql -d easydoc -tAc "SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history'"
0
```

Python DB에 `flyway_schema_history` 가 **0개** — Kotlin 이 Python 스키마를 건드리지 않았다.

### 9.5 compose 실행 중 발견해 고친 것 — worker 즉시 종료

첫 실행에서 `kotlin-worker` 가 **exit 0 로 즉시 종료**했다.

```text
status=exited exit=0 started=...T00:28:15Z finished=...T00:28:19Z
```

원인: `web-application-type: none` 이라 non-daemon 스레드가 하나도 없어 `main` 이 반환하는
순간 JVM 이 끝난다. Phase 5에서 lease 폴링 루프가 붙으면 자연히 해소되지만, 그때까지
compose 의 worker 서비스가 계속 죽어 있으면 **배선 문제와 구분되지 않는다.**

`worker/src/main/resources/application.yml` 에 `spring.main.keep-alive: true` 를 추가했다.
기능 추가가 아니라 "worker 는 상주 프로세스"라는 §3.2의 의도에 런타임 형태를 맞춘 설정이다.
`:worker:test` 재실행에서 테스트가 매달리지 않는 것을 확인했고, 재기동 후:

```text
status=running exit=0
kotlin-worker   Up 20 seconds
```

### 9.6 재확인 (keep-alive 반영 후)

```text
$ ./gradlew clean build
BUILD SUCCESSFUL in 12s
tests=48 failures=0
```

락파일을 갱신하지 않고 `build` 가 성공한다 = 해석된 의존성이 커밋된 락과 일치한다.

### 9.7 커밋 대상 확인

```text
?? backend-kotlin/api/gradle.lockfile
?? backend-kotlin/application/gradle.lockfile
?? backend-kotlin/core/gradle.lockfile
?? backend-kotlin/infrastructure/gradle.lockfile
?? backend-kotlin/worker/gradle.lockfile
?? backend-kotlin/settings-gradle.lockfile
?? backend-kotlin/gradle/wrapper/gradle-wrapper.jar
?? backend-kotlin/gradle/wrapper/gradle-wrapper.properties

build/ 디렉터리 커밋 대상: 0개
```
