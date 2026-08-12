# Phase 1 `skeleton` — 교차 종합 (2회차, 정본)

**작성:** migration-reviewer (Claude) / 2026-08-12
**회차:** 2차 — **교차 종합 전용.** 새 지적을 만들지 않는다. 대조·판정·우선순위만 한다.
**이 파일이 Phase 1 게이트 판정의 정본이다.** 1차 산출물만으로 종료 조건 충족을 보고하지 않는다.

---

## 1. 대조한 산출물과 출처 표기

| 출처 라벨 | 산출물 | 회차 | 성격 |
|---|---|---|---|
| `Claude` | `docs/migration/_workspace/reviews/01_skeleton_migration-reviewer.md` | 1차 (2026-08-12) | Claude 단독 독립 리뷰. 6축, 실측 7건(E1~E7) |
| `codex` | `docs/migration/_workspace/reviews/01_skeleton_codex-reviewer.md` | 1차 (2026-08-12 09:39~09:45) | codex adversarial-review, verdict `needs-attention`, 종료 코드 0 |
| `codex(stop-gate)` | 세션 종료 시점 stop-time codex 게이트 (리더 전달) | — | **다른 프롬프트·다른 관점.** `codex`와 같은 모델이지만 별개 출처로 센다 |

**출처를 셋으로 나눈 이유.** `codex`와 `codex(stop-gate)`를 한 출처로 묶으면 같은 모델의 두 실행이 "두 명의 동의"로 계상되어 신뢰도가 과대평가된다. 아래 표에서 `양쪽`은 **Claude + codex 계열 중 하나 이상**이 같은 대상을 지목한 경우에만 쓴다.

**리뷰 범위 차이 (사실).** codex에게 준 focus text(`01_skeleton_codex-reviewer.md` §2)는 **5개 축만** 지정하고 "일반적인 Kotlin 코드 리뷰는 하지 마라"를 명시했다: ①모듈 경계 ②Flyway baseline 가드 ③`encryption_scheme` V2 배치 ④CI 게이트 약화 ⑤parity 게이트 우회. **계약 준수·보안 불변식·테스트 적정성 축은 focus text에 없다.** 아래 표에서 그 축의 행에 `범위 밖`으로 표기한 것은 이 사실을 가리키며, 짐작이 아니다.

---

## 2. codex "전제 확인 필요" 항목 판정 (§4 대조 결과)

`codex-reviewer`가 판정을 유보하고 넘긴 사실 주장 전부를 저장소와 대조했다.

| # | codex 주장 | 판정 | 근거 |
|---|---|---|---|
| 1 | `"HEAD currently tracks no parity/fixtures files"` | **참** | `git ls-files parity/` → **0건**. 작업 트리에도 `parity/_harness-selfcheck/` 하나뿐 |
| 2 | `"EXPECTED_DOMAINS, BUILDERS, and VERIFIERS are imported from dump_parity_fixtures.py"` | **부분 참 — 동사 부정확, 실질 정확** | `compare_parity.py:107-112` 가 import 하는 것은 `BUILDERS`·`VERIFIERS`·`VerificationOutcome`·`write_proof_record` 넷이다. `EXPECTED_DOMAINS` 는 import 가 아니라 **`:117` 에서 `tuple(BUILDERS)` 로 정의**된다. 즉 "정본이 BUILDERS 하나뿐"이라는 실질은 정확하고, 코드가 `:114-116` 주석에서 **그렇게 한 것이 의도임을 명시**한다 |
| 3 | `canonical_fixture()` 가 `lines 400-423` | **참** | `def canonical_fixture` = `:400`, `BUILDERS[domain]()` 재실행 = `:412`, 반환 = `:426`. 인용 범위가 본체를 정확히 덮는다 |
| 4 | `"Flyway has no pinned default schema"` | **참** | `api/src/main/resources/application.yml:21-27` 의 `spring.flyway` 블록에 `schemas`·`default-schema` 없음. JDBC URL(`:13`)에도 `currentSchema` 파라미터 없음. 저장소 전체 grep 0건 |
| 5 | `"V1/V2 use unqualified DDL"` | **참** | `V1__python_schema_baseline.sql`: `CREATE TABLE users`, `CREATE UNIQUE INDEX ix_users_email ON users`, `CREATE EXTENSION IF NOT EXISTS vector` — 전부 스키마 무한정. `V2__encryption_scheme.sql:31,34,39,43`: `ALTER TABLE documents` / `ALTER TABLE conversions` — 동일 |
| 6 | `"only ... seven class names on testRuntimeClasspath"` | **참** | `CoreModuleBoundaryTest.kt:26-33` 의 `@ValueSource(strings=[...])` 원소가 **정확히 7개**. `Class.forName` 은 `:38`, 테스트 런타임 클래스로더 기준 |
| 7 | `ci.yml:143-159` | **참(꼬리 1줄 누락)** | parity 비교 단계는 `:143`에서 시작해 `:160`(`exit "$status"`, 파일 마지막 줄)에서 끝난다. 인용 범위가 마지막 한 줄을 덜 잡았을 뿐 지적 대상(`:147-150` 부재 시 exit 0, `:156-159` 코드 2 사면)은 전부 범위 안 |
| 8 | `compare_parity.py:107-117` | **참** | 위 #2 참조 |
| 9 | `SchemaFingerprint.kt:20-68` | **참** | `nspname = 'public'` 하드코딩 = `:40`, `alembic_version` 제외 = `:42`, KDoc "제외 대상" = `:20-25`. 전부 범위 안 (SQL 상수는 `:34-69`) |
| 10 | `FlywayBaselineGuard.kt:43-76` | **참** | 파일 92줄. `:43-59` 전략 람다, `:62-68` `needsPythonBaseline`, `:71-76` `verifyMatchesPythonBaseline` 앞부분. 지적한 "분리된 연산들"이 전부 범위 안 |

**결론: 틀린 전제 위에 세워진 codex 지적은 없다.** #2의 동사 부정확(`imported` ↔ `derived`)은 지적의 실질을 바꾸지 않는다.

---

## 3. 교차 대조표

> 심각도 척도: **차단(Critical)** = ①사건(§5 Phase 7 즉시 중단 기준 경로) 또는 ②장치(탐지·차단 게이트의 무력화) / **수정 필요** = Phase 종료 조건 미충족 / **권고** / **판정 필요**.
> **마감** = 그 게이트가 처음 실제로 쓰이는 Phase. 심각도와 "Phase 2 착수를 막는가"는 별개 축이며 후자는 §7에서 따로 판정한다.

### 3.1 parity 게이트 / CI (축 ②⑤·하네스)

| # | 항목 | 근거 위치 | 축 | Claude | codex | codex(stop-gate) | 상태 | 심각도 | 마감 |
|---|---|---|---|---|---|---|---|---|---|
| X-1 | fixture 디렉터리가 없으면 비교기를 호출하기도 전에 exit 0 — 트리를 지우면 되살아나는 백도어 | `.github/workflows/ci.yml:147-150` | parity/테스트 | H-4 전반부 | #1 | (item 2와 같은 블록) | **합의** | **차단 ②** | **Phase 2 착수와 동시** |
| X-2 | Phase 2에서 fixture가 생기는 순간 parity CI가 **막힌다** — 종료 코드 2 사면이 덮지 못한다 | `ci.yml:151-160` ↔ `compare_parity.py:839-840, 918-924` | parity | 미지적 | 미지적 | **item 2** | **codex(stop-gate) 단독** | **차단 ②** | **Phase 2 착수 전** |
| X-3 | 정본 대조와 외부 검증이 **자기가 검증하는 대상과 같은 가변 소스**를 신뢰한다 (BUILDERS/VERIFIERS 동시 약화 미탐지) | `compare_parity.py:107-117, 400-426` | parity | 미지적 | #2 | 미지적 | **codex 단독 · 설계 충돌** | **판정 필요** (§4.1) | **Phase 2 착수 전(판정)** |
| X-4 | `crypto` 음성 케이스의 `input` 전체가 `VOLATILE_INPUT_FIELDS` 로 정본 대조에서 빠진다 — 변조 토큰을 쓰레기로 바꿔도 게이트가 닫힌다 | `compare_parity.py` `VOLATILE_INPUT_FIELDS`, `dump_parity_fixtures.py:524-597` | 보안/parity | **H-3** | 미지적 | 미지적 | **Claude 단독** | **차단 ②** | Phase 4 종료 전 |
| X-5 | `parityHarness` 가 산출물 0건에도 BUILD SUCCESSFUL (`@Tag("parity")` 가 저장소 전체에 1건) | `backend-kotlin/build.gradle.kts:110,126`, `ParityActualTest.kt:100` | 테스트 | **H-5** | 미지적 | 미지적 | **Claude 단독** | 수정 필요 | Phase 2 종료 전 |
| X-6 | Critical #1(`check_external` in-process)·#2(`provenance_problems` 정본 재대조) 는 **닫혔다** | `compare_parity.py` | parity | H-1·H-2 **통과 판정** | #2가 "닫힘"을 부정하지 않되 **범위가 좁다**고 서술 | — | **부분 충돌** (§4.1) | — | — |
| X-7 | `outcome.bound` 를 계산하고 판정에 쓰지 않는다 | `compare_parity.py` `check_external` | parity | H-6 | 미지적 | 미지적 | Claude 단독 | 권고 | Phase 4 |
| X-8 | 정본에 없는 **도메인**은 아무도 보고하지 않는다 (`found - expected` 미검사) | `compare_parity.py:836` | parity | H-7 | 미지적 | 미지적 | Claude 단독 | 권고 | Phase 4 |
| X-9 | CI `kotlin` 잡의 `uv run` 이 잠금을 강제하지 않는다 (정본 생성기의 의존성이 `quality` 잡과 다를 수 있음) | `ci.yml:151` vs `:48` | parity | H-8 | 미지적 | 미지적 | Claude 단독 | 권고 | Phase 2 |
| X-10 | 기존 Python·React CI 게이트가 조건부화·실패 삼킴으로 약화됐는가 | `ci.yml` | 하네스 | **검토함 — 지적 없음** | **검토함 — 지적 없음** (축 4에 명시) | — | **합의(무결)** | — | — |

### 3.2 Flyway baseline 가드 (축 ②)

| # | 항목 | 근거 위치 | 축 | Claude | codex | 상태 | 심각도 | 마감 |
|---|---|---|---|---|---|---|---|---|
| F-1 | **V1 ≡ Alembic `0001~0006`** — 새 컨테이너에서 양쪽 재적용, 지문 61줄 바이트 동일, 컬럼 34·제약 11·인덱스 11 전건 일치 | `V1__python_schema_baseline.sql`, `db/baseline/python-schema-fingerprint.txt` | parity | **검증함 — 일치** (E4) | 미검토 | **Claude 단독(긍정)** | — | — |
| F-2 | Flyway 대상 스키마가 고정돼 있지 않다 — `search_path`/`currentSchema` 변경 시 `public` 을 지문 뜨고 **다른 스키마에 baseline·migrate** 할 수 있다 | `application.yml:13,21-27` + `SchemaFingerprint.kt:40` + V1/V2 무한정 DDL | Kotlin 관용성/parity | 미지적 | **#3a** | **codex 단독** | **차단 ②** | **Phase 7 착수 전** (수정은 Phase 3 병행 권고) |
| F-3 | 가드가 `alembic_version` 을 **읽지 않아** 스키마는 같고 리비전만 어긋난 DB를 승인한다 | `FlywayBaselineGuard.kt:33-35, 62-68` | parity | **반대 견해** — 읽지 않는 것을 §4.2-7 준수로 **긍정 평가**함 | **#3b** | **충돌** (§4.2) | **판정 필요** | **Phase 7 착수 전** |
| F-4 | 지문이 RLS·트리거·파티션·권한·collation·확장 버전·identity 를 못 본다 | `SchemaFingerprint.kt:34-69` | parity | **P-1** (18종 변형 주입 실증) | **#3c** | **합의** | 수정 필요 | Phase 7 착수 전 |
| F-5 | 지문 검증과 baseline 스탬핑 사이에 **잠금 없는 TOCTOU 창** | `FlywayBaselineGuard.kt:44-52, 62-68, 71-76` | Kotlin 관용성 | **반대 견해** — K-2에서 "Flyway 가 잠금을 잡으므로 파손 가능성은 낮다"고 평가함 | **#4** | **충돌** (§4.3) | **판정 필요 → 차단 ② 상향 권고** | **Phase 7 착수 전** |
| F-6 | `api` profile 도 Flyway 를 돌린다 — "마이그레이션 주체는 migrate 하나뿐"이라는 선언과 어긋난다 | `api/application.yml:21-27` vs `worker/application.yml:25-28` | Kotlin 관용성 | **K-2** | 미지적 (F-5와 인접) | Claude 단독 | 수정 필요 / 판정 필요 | Phase 7 착수 전 |
| F-7 | Alembic 이 **어느 Kotlin 테스트에서도 실행되지 않는다** — 대조되는 두 쪽이 모두 V1 파생 | `PythonSchemaBaselineTest.kt:38-48`, `FlywayBaselineGuardTest.kt:95-102` | 테스트 | **P-2** | 미지적 | Claude 단독 | 수정 필요 | Phase 7 착수 전 / Alembic 리비전 추가 즉시 |
| F-8 | §4.2-1 "실제 대상 DB schema-only dump + `alembic_version` 수집" 미수행 | 계획 §4.2-1 (`:135`) | parity | **P-3** | 미지적 (#3b가 같은 공백의 다른 면) | Claude 단독 | **확인 불가 → 리더 확인** | Phase 7 착수 전 |
| F-9 | `alembic_version` 을 Kotlin 경로(마이그레이션 SQL·가드·테스트·compose 초기화)가 수정·삭제할 여지 | 전 경로 | 보안/parity | **검토함 — 지적 없음** | **검토함 — 지적 없음** (축 2에 명시, 오히려 "read-only 로 읽어라"를 권고) | **합의(무결)** | — | — |
| F-10 | `encryption_scheme` 을 V1 이 아니라 **V2** 에 둔 판단 | `V2__encryption_scheme.sql` | 보안 | **옳음** (I-4/I-7 준비 항목) | **옳음** — `"V2 encryption_scheme placement itself was not a blocker"` | **합의(무결)** | — | — |
| F-11 | `conversions.updated_at` 갱신 장치가 DB 에 없다 (트리거·`ON UPDATE` 부재, 지문 대조로도 안 잡힘) | `app/models/conversion.py:101-103`, V1 | parity | **P-4** | 미지적 | Claude 단독 | 수정 필요 | Phase 3 종료 전 |
| F-12 | UUID PK 4개에 DB 기본값 없음 / `0006` 백필 DML 에 V1 대응물 없음 / ORM 모델은 서수의 근거가 아님 / 지문 정렬이 우연히 `C` collation 안전 | — | parity | P-5·P-6·P-7·P-8 | 미지적 | Claude 단독 | 권고 | 각 항목 |

### 3.3 모듈 경계 (축 ①)

| # | 항목 | 근거 위치 | Claude | codex | 상태 | 심각도 | 마감 |
|---|---|---|---|---|---|---|---|
| M-1 | `CoreModuleBoundaryTest` 가 **클래스 이름 7개**만 보므로 미열거 타입(JPA·Spring Data·타 JDBC 드라이버·타 LLM SDK)은 통과한다 | `CoreModuleBoundaryTest.kt:23-45` | **K-4** | **#5** | **합의** | 권고 → **수정 필요 상향 권고** (§4.4) | Phase 2 종료 전 |
| M-2 | `compileOnly` 로 Spring 을 붙이면 main 은 컴파일되고 테스트 런타임에는 없어 **테스트가 통과한다** | `core/build.gradle.kts` (현재 `compileOnly` 0건) | 미지적 | **#5** | **codex 단독** | 권고 | Phase 2 종료 전 |
| M-3 | `annotationProcessor`·`kapt`·생성 소스셋·`testFixtures`·플러그인 추가 의존이 미검사 | 각 `build.gradle.kts` | 미지적 (T-4에서 `application` 무테스트만 지적) | **#5** | **codex 단독** | 권고 | Phase 2 종료 전 |
| M-4 | **`api`·`worker` 의 컴파일 클래스패스가 `infrastructure` 를 배제하는지 검증하는 테스트가 없다** | `api/build.gradle.kts:16`, `worker/build.gradle.kts:14` | **반대 견해** — `runtimeOnly` 선언 자체를 "컴파일 에러로 막는다"고 긍정 평가 | **#5** | **부분 충돌** (§4.4) | 수정 필요 | Phase 2 종료 전 |
| M-5 | 모듈 5개·의존 방향·`core` platform-only·`application` ↮ `infrastructure`·JPA 0건·`!!` 0건·의존성 락이 실제 드리프트를 잡음 | `settings.gradle.kts`, 각 `build.gradle.kts`, `libs.versions.toml:74-79` | **검토함 — 지적 없음** | 미검토(§3.2 구조 자체는 다루지 않음) | Claude 단독(긍정) | — | — |

### 3.4 계약 준수 (축 ①) — codex focus 범위 밖

| # | 항목 | 근거 위치 | Claude | codex | codex(stop-gate) | 상태 | 심각도 | 마감 |
|---|---|---|---|---|---|---|---|---|
| C-1 | **프레임워크 예외가 전부 500 으로 나간다** — 실측 404·405·406·307 → 전부 500 | `GlobalExceptionHandler.kt:86-90` | **C-1** | 범위 밖 | (기동·설정 item 3에 미포함) | **Claude 단독** | **차단 ①+②** | Phase 3 착수 전 |
| C-2 | `ErrorContractTest` 10건이 **핸들러를 직접 호출**해 HTTP 경계를 보지 않는다 — C-1 이 존재한 채 전부 초록 | `ErrorContractTest.kt:31-32` | **C-2 / T-1** | 범위 밖 | — | **Claude 단독** | **차단 ②** | Phase 3 착수 전 |
| C-3 | **CORS 가 구현돼 있지 않다** — `corsOrigins` 프로퍼티만 선언, `CorsConfigurationSource`·`addCorsMappings`·`@CrossOrigin` **0건** | `EasyDocProperties.kt:37`, `application.yml:48`, `GlobalExceptionHandler.kt:45-47` | **C-3** | 범위 밖 | **item 1** | **양쪽 (Claude + codex(stop-gate))** | **수정 필요 → 차단 ② 상향 권고** (§4.5) | Phase 3 착수 전 |
| C-4 | 계약의 `x-cors` 에 `max_age` 없음 (Starlette 600 vs Spring 기본 1800) / `allow_headers` 가 설정값이지 전선값(5개) 아님 | `contracts/easy-doc-v1.yaml:79-96, 84` | **F4·F5** | 범위 밖 | item 1과 같은 계약 조항 | Claude 단독 | 수정 필요 | Phase 3 착수 전 |
| C-5 | 계약의 요청 길이 제약 5개가 **계약 자신의 422 형식 규칙과 충돌**하고 코드보다 엄격하다 | `easy-doc-v1.yaml:1052,1057,1107,1180,1293-1294` vs `:910-919` | **F3** | 범위 밖 | — | **Claude 단독** | **차단 ②** | Phase 3 착수 전 |
| C-6 | 계약의 multipart `encoding.file.contentType` 제약이 구현에 없다 — 성실히 구현하면 `.hwpx` 업로드가 깨지고 그 구현이 contract test 를 통과한다 | `easy-doc-v1.yaml:324-329` vs `app/api/documents.py:230` | **F2** | 범위 밖 | — | **Claude 단독** | **차단 ②** | Phase 4 착수 전 |
| C-7 | 세 번째 401 메시지(`인증 정보가 유효하지 않습니다`)가 실재 — 계약의 "메시지는 두 가지" 서술이 사실과 다름 | `app/api/deps.py:118` vs 계약 `:876-886` | **F1** | 범위 밖 | — | Claude 단독 | 수정 필요 / 판정 필요 | Phase 3 착수 전 |
| C-8 | `OPTIONS` 가 없는 메서드를 광고 / FastAPI 문서 라우트 4개가 계약 밖 / `format: binary` 는 3.1 무효 / no-store 제외 목록 누락 / 미디어 타입 파라미터 / `sub` UUID 규칙이 서술뿐 | 각 항목 | C-4, F6~F10 | 범위 밖 | — | Claude 단독 | 권고 | 각 항목 |
| C-9 | `/health` 계약 일치, 오류 매핑표 12건 전건 일치, `{"detail":...}` 단일 필드, actuator 미도입, 엔드포인트 14개·성공 상태 코드·응답 본문·캐시 금지 헤더 10곳·`Content-Disposition` 바이트 일치 | 계약 파일 + 실측 | **검토함 — 지적 없음** | 범위 밖 | — | Claude 단독(긍정) | — | — |

### 3.5 보안 불변식 (축 ③) — codex focus 범위 밖

| # | 항목 | Claude | codex | 상태 | 심각도 |
|---|---|---|---|---|---|
| S-1 | 기계 스캔 BLOCK 0건 / `Secret` 마스킹·상수시간 비교 / 예외 로깅이 Python 보다 좁음 / 비밀값 환경변수 전용 / non-root 컨테이너 | **검토함 — 지적 없음** | 범위 밖 | Claude 단독(긍정) | — |
| S-2 | compose 주석과 실제 값 불일치 + `environment:` 가 `env_file:` 을 덮어써 `.env` 설정이 조용히 무시됨 | S-1 | 범위 밖 | Claude 단독 | 권고 |
| S-3 | Kotlin 컨테이너가 쓰지 않는 모든 벤더 키를 주입받음 + Python `.env` 키 이름 ↔ Spring 완화 바인딩 사이에 다리 없음 | S-2 | 범위 밖 | Claude 단독 | 권고 (Phase 3 에 필수 전제) |
| S-4 | 런타임 이미지에 `curl` 설치 / Gradle 배포본 `distributionSha256Sum` 미고정 | S-3·S-4 | 범위 밖 | Claude 단독 | 권고 |
| S-5 | **`privacy-gate` 의 Phase 1 감사 산출물이 없다** (`01_privacy-gate_*` 부재) — 축 ③ 판정은 **잠정** | 명시 | — | — | **확인 불가** |

### 3.6 테스트 적정성 (축 ⑤) — codex focus 범위 밖

| # | 항목 | Claude | codex | 상태 | 심각도 | 마감 |
|---|---|---|---|---|---|---|
| T-1 | 설정 바인딩 경로(`EasyDocProperties`·`SecretConverter`)가 **한 줄도 실행되지 않는다** — 정의 외 참조 0건 | T-3 | 범위 밖 | Claude 단독 | 수정 필요 | Phase 3 착수 전 |
| T-2 | `EasyDocProperties` 가 `worker` 에서 **보이지 않는 모듈**(`api`)에 있다 — `@ConfigurationPropertiesScan` 대상 0개 | K-1 | 범위 밖 | Claude 단독 | 수정 필요 | Phase 5 착수 전 |
| T-3 | `application` 모듈에 소스·테스트 0개 — README 계약을 지키는 것이 빌드 스크립트 리뷰뿐 | T-4 | 범위 밖 (M-3 과 인접) | Claude 단독 | 권고 | Phase 2 |
| T-4 | 실패 경로 테스트가 실제로 있다 (`FlywayBaselineGuardTest:56-73` 기동 실패 + 장부 미생성, `ParityActualTest:78-90` 거부 2종) | **검토함 — 강점으로 기록** | 범위 밖 | Claude 단독(긍정) | — | — |
| T-5 | Boot 4.0.7 vs 계획 문서 "4.1 계열" | K-3 | 범위 밖 | Claude 단독 | **판정 필요** | **Phase 2 착수 전** |
| T-6 | detekt `ForbiddenComment` 가 `TODO:` 를 허용하는데 바로 위 주석은 반대로 말함 | K-5 | 범위 밖 | Claude 단독 | 권고 | — |

---

## 4. 충돌 항목 — 양쪽 근거 전문 병기 (리더 판단 요청)

> 어느 쪽도 삭제하지 않는다. 자체 검증 결과는 **제3의 근거로 추가**할 뿐 상대 지적을 지우지 않는다.

### 4.1 [X-3 / X-6] 정본 manifest 를 BUILDERS 에서 분리할 것인가 — **설계 충돌**

**codex #2 (원문):**
> `EXPECTED_DOMAINS, BUILDERS, and VERIFIERS are imported from dump_parity_fixtures.py. canonical_fixture() then re-executes those same BUILDERS (lines 400-423). A single change can remove a domain/case, alter an expected value, or weaken run_verify_crypto/run_verify_jwt, regenerate matching fixtures, and be accepted as canonical; check_external merely runs the newly weakened verifier in-process. Thus the two claimed Critical fixes detect stale/manual proof files but do not detect coordinated generator/verifier weakening in the same diff.`
> 권고: `Pin the required domain/case manifest independently of BUILDERS...`

**Claude 1차 (H-1·H-2, 원문 요지):**
> Critical #1·#2 는 **닫혔다.** `check_external` 은 증거 파일을 판정 근거로 읽지 않고 검증기를 in-process 로 돌린다. `provenance_problems()` 는 `BUILDERS[domain]()` 를 다시 돌려 대조하며, **값 대조에 쓰는 정규화 규칙을 정본이 선언한 것으로 고르는** 부분이 특히 정확하다.

**제3의 근거 (2차에서 코드로 확인):** `compare_parity.py:114-116` 은 codex 가 권고하는 것을 **의도적으로 거부한다고 명시**한다.

```python
#: 기대 도메인 집합. **정본은 생성기의 BUILDERS 키 하나뿐이다.** 여기에 목록을 다시 적지
#: 않는다 — 두 벌이 되는 순간 도메인을 추가할 때 한쪽만 고쳐지고, 그 도메인은 검증되지
#: 않은 채 게이트를 통과한다.
EXPECTED_DOMAINS: tuple[str, ...] = tuple(BUILDERS)
```

**두 논거가 정확히 반대 방향을 가리킨다.**

| | 위험 | 실현 조건 |
|---|---|---|
| 코드의 논거 (목록 1벌) | 목록 2벌이면 드리프트로 **새 도메인이 검증 없이 통과** | 도메인 **추가** 시 한쪽만 갱신 |
| codex 의 논거 (목록 2벌) | 목록 1벌이면 BUILDERS 삭제가 **요구사항 삭제와 동시에 일어남** | 도메인 **삭제·약화** 시 |

**판정하지 않고 올린다.** 다만 판단 재료 셋을 덧붙인다. ①현재 코드는 "추가 시 누락"만 막고 "삭제 시 축소"는 못 막는 **비대칭 방어**다. ②codex 가 함께 제안한 `"adversarial tests that feed known-corrupt Crypto/JWT artifacts and assert exit 1"` 은 목록 2벌 없이도 **삭제 방향을 막는다** — 두 논거를 모두 만족시키는 제3안이다. ③이 판정은 **Phase 2 가 BUILDERS 를 실제로 늘리기 전에** 내려져야 한다. Phase 2 는 8개 도메인 fixture 를 만드는 단계이므로, 설계를 바꿀 거라면 그 전이 가장 싸다.

**X-6 부분 충돌 정리:** codex #2 는 "Critical #1·#2 가 닫히지 않았다"고 말하지 않는다. **닫힌 범위가 좁다**고 말한다. Claude 의 통과 판정(그 두 건에 한정)과 codex 의 범위 지적은 양립하며, 남는 쟁점은 위 설계 선택 하나다.

---

### 4.2 [F-3] `alembic_version` 을 읽을 것인가 — **충돌**

**codex #3b (원문):**
> `Even in public, a schema-identical database with alembic_version='0005' passes: Kotlin records baseline 1 and V2, then Python later retries 0006 and collides with already-existing objects.`
> 권고: `Read alembic_version without modifying it and require exactly one approved head such as 0006.`

**Claude 1차 (§5 "통과한 것", 원문):**
> 판정 근거를 `alembic_version` 의 존재가 아니라 **애플리케이션 테이블의 존재**로 삼아 §4.2-7("Kotlin 이 `alembic_version` 을 읽지도 쓰지도 않는다")을 함께 지킨 것도 맞다.

**제3의 근거 (2차에서 계획 원문 대조):** 계획 §4.2-7 의 실제 문구는 다음과 같다.

> `141: 7. Python 제거 전까지 alembic_version은 보존하고 Kotlin이 수정하지 않는다.`

**계획은 "수정 금지"만 요구한다. "읽기 금지"는 요구하지 않는다.** `FlywayBaselineGuard.kt:33-34` 의 `"이 클래스는 그 테이블을 읽지도 쓰지도 않는다"` 는 구현자가 **계획보다 엄격하게 스스로 부과한 제약**이며, Claude 1차는 그 자기부과 제약을 계획 준수로 읽었다. 따라서 codex 의 권고(read-only 조회 + head 승인)는 **§4.2-7 과 충돌하지 않는다.**

더 나아가 계획 §4.2-1 은 `alembic_version` 을 **판단 입력으로 수집하라고 명시적으로 요구**한다.

> `135: 1. 실제 대상 DB의 schema-only dump와 alembic_version을 수집한다.`

**반대 방향의 사실도 남긴다.** codex 시나리오("스키마는 동일한데 `alembic_version='0005'`")는 좁은 조건에서만 성립한다 — `0006` 은 `workspaces` 테이블과 `documents.workspace_id` 를 만들므로, 정상적으로 0005 에 머문 DB는 **지문이 이미 다르고 가드가 던진다**. 시나리오가 실현되려면 부분 복원·`alembic stamp` 오용·버전 테이블만의 롤백처럼 스키마와 장부가 어긋난 상태여야 한다. 그러나 §4.2-2 가 `"README에 0003 제자리 수정 이력이 있으므로 파일만 믿지 않는다"` 고 경고한 대상이 정확히 그런 상태이고, 그것을 확인할 유일한 근거가 `alembic_version` 이다. **F-8(P-3, 실제 대상 DB dump 미수집)이 닫히지 않는 한 이 위험은 추정으로 남는다.**

**리더 판단 요청:** (a) 가드가 `alembic_version` 을 read-only 로 읽고 승인 head(`0006`)를 요구하게 할 것인가, (b) 현재의 자기부과 제약을 유지하고 대신 절체 전 수동 절차로 확인할 것인가. (a)를 고르면 `FlywayBaselineGuard.kt:31-35` 의 KDoc 과 Claude 1차의 긍정 평가를 함께 정정해야 한다.

---

### 4.3 [F-5] TOCTOU 창이 실질 위험인가 — **충돌 (codex 근거가 더 강함)**

**codex #4 (원문):**
> `History inspection, table counting, fingerprinting, baseline(), and migrate() are separate operations using separate connections with no lock spanning the decision. ... Flyway's migration-history locking cannot retroactively protect the preceding fingerprint decision or serialize it with Alembic, so the process can stamp a schema it never verified or fail unpredictably during cutover.`

**Claude 1차 (K-2, 원문):**
> **절체 시점에 api 복제본이 N개 뜨면 N개가 운영 DB에서 baseline 경로를 동시에 탄다.** Flyway 가 잠금을 잡으므로 **파손 가능성은 낮으나**, 지문 불일치 시 N개가 동시에 기동 실패하고 원인 로그가 N배로 쌓인다.

**제3의 근거 (2차에서 코드로 확인):** `FlywayBaselineGuard.kt` 의 호출 순서를 실제로 따라가면 codex 의 기술적 주장이 성립한다.

```kotlin
44:  if (needsPythonBaseline(flyway)) {     // ← flyway.info() + 연결 #1 (userTableCount)
45:      verifyMatchesPythonBaseline(flyway) // ← 연결 #2 (지문)
50:      flyway.baseline()                   // ← 여기서 비로소 Flyway 자체 잠금
51:  }
52:  val result = flyway.migrate()           // ← 또 다른 잠금 구간
```

`needsPythonBaseline`(`:65`)과 `verifyMatchesPythonBaseline`(`:74`)이 **각각 별도 연결을 열고 닫으며**, Flyway 의 이력 잠금은 `baseline()` 시점에야 잡힌다. 즉 **지문 판정 구간은 어떤 잠금에도 덮이지 않는다.** Claude 1차의 "Flyway 가 잠금을 잡으므로 파손 가능성은 낮다"는 근거는 **잠금이 판정보다 뒤에 온다는 점을 놓쳤다.**

**어느 쪽도 삭제하지 않는다.** Claude 1차가 지적한 별개 사실(N개 복제본이 동시에 기동 실패해 로그가 N배로 쌓인다, F-6과 결합)은 그대로 유효하다. 다만 **"파손 가능성은 낮다"는 완화 판단은 근거를 잃었으므로**, F-5 를 codex 가 제시한 **차단 ②** 로 상향할 것을 권고한다 — 절체 시점에 "검증하지 않은 스키마를 baseline 으로 도장 찍는" 경로는 §5 Phase 7 즉시 중단 기준(타 사용자 노출·데이터 유실)의 상류에 있다.

**리더 판단 요청:** 심각도 상향(권고 → 차단 ②) 여부. 조치는 codex 권고(PostgreSQL advisory lock 을 이력 조회 전에 잡아 지문·baseline·migrate 까지 유지) + F-6(마이그레이션 주체를 `migrate` profile 하나로 좁힘)을 함께 적용하면 두 지적이 한 번에 닫힌다.

---

### 4.4 [M-1 / M-4] 모듈 경계 검사의 심각도와 범위 — **부분 충돌**

**codex #5 (원문 요지):** `medium`. `compileOnly` 우회, 미검사 구성(annotationProcessor/kapt/생성 소스셋/testFixtures/플러그인 추가), 미열거 SDK, 그리고 `"Nothing here verifies that api/worker retain infrastructure as runtimeOnly or that their compile classpaths exclude infrastructure/JDBC/LLM types"`.

**Claude 1차 (K-4, 권고):** 같은 "이름 목록 7개" 한계를 지적하되 `jakarta.persistence`(규약이 명시적으로 금지한 JPA 가 목록에 없음)·Spring Data·타 JDBC 드라이버·`implementation`→`api` 변경을 열거. 심각도 **권고**.

**Claude 1차 (§5 "통과한 것") — 여기가 충돌 지점:**
> **`runtimeOnly(project(":infrastructure"))`** — 라우터가 JDBC·암호화·LLM SDK 타입을 컴파일 시점에 볼 수 없다. 문서가 아니라 **컴파일 에러**로 막는다. §3.2 의존 방향의 가장 강한 형태다.

codex 는 같은 선언을 두고 **"그 선언이 유지되는지 검증하는 것이 아무것도 없다"** 고 말한다. 둘은 층위가 다르다 — Claude 는 *현재 선언의 강도*를, codex 는 *그 선언의 회귀 방어*를 봤다. **양쪽 다 유효하며 codex 쪽이 이 전환에서 더 중요하다**: Phase 3~5 에서 `api` 가 JDBC 타입을 직접 쓰고 싶은 유혹이 실제로 생기고, 그때 `runtimeOnly` 를 `implementation` 으로 한 글자 바꾸면 **아무 테스트도 깨지지 않는다.**

**제3의 근거 (2차 확인):** 저장소 전체에서 `runtimeClasspath`/`compileClasspath`/`configurations[...]` 를 참조하는 Kotlin·Gradle 파일은 `backend-kotlin/build.gradle.kts` 하나뿐이고, 그것은 `parityHarness` 태스크용이다. **경계를 구성(configuration) 수준에서 단언하는 코드는 0건이다.** codex 의 사실 주장은 참이다.

**심각도 권고:** M-1·M-4 를 **권고 → 수정 필요**로 상향. 근거는 Phase 2 종료 조건이 `"외부 API·DB 없이 실행하는 parity suite"`(계획 `:243`)이고, `core` 격리가 그 조건의 전제이기 때문이다. 다만 **차단으로 올리지는 않는다** — 현재 실제 위반은 0건이고(`core/build.gradle.kts` 에 `compileOnly` 0건), 무력화된 것은 회귀 방어이지 현재 상태가 아니다.

**M-2(compileOnly 우회)는 codex 단독으로 남긴다.** Claude 는 이 경로를 보지 않았다.

---

### 4.5 [C-3] CORS 심각도 — **양쪽 합의, 심각도 상향 권고**

Claude 1차는 **수정 필요**로, `codex(stop-gate)` 는 **Phase 1 계약 파손**으로 판정했다. 2차에서 코드로 확정한 사실은 다음과 같다.

- `backend-kotlin` 전체에 `CorsConfigurationSource`·`addCorsMappings`·`@CrossOrigin` **0건** (grep 확인).
- `EasyDocProperties.kt:37` 의 `corsOrigins` 와 `application.yml:48` 의 `cors-origins` 는 **선언만 존재하고 소비자가 없다.** 이는 T-1(설정 바인딩 경로 미실행)과 같은 뿌리다.
- `GlobalExceptionHandler.kt:45-47` 이 보류를 명시적으로 기록했다: `"이 Phase 에서는 CORS 를 설정하지 않는다."`

**상향 권고 근거:** 계약 파일 자신이 `expose_headers` 누락의 결과를 경고한다 — 서버 응답은 완전한데 **브라우저 JS 만 헤더를 못 읽는다.** 이 실패 양태는 서버 로그·contract test·`curl` 어디에도 나타나지 않고 브라우저에서만 드러난다. 즉 **탐지 장치가 닿지 않는 자리**이므로 ②장치 성격을 갖는다. Phase 3 종료 조건이 `"React를 Kotlin API에 연결한 로그인·작업 공간 E2E"`(계획 `:251`)이므로 Phase 3 착수와 동시에 필요하다.

**단, 착수 차단은 아니다.** Phase 2 는 순수 도메인 로직이라 HTTP 경계를 쓰지 않는다.

---

## 5. `codex(stop-gate)` 항목 실측 판정

리더가 코드로 확정한 2건은 재확인만 하고, **미확정 1건은 실측해 확정한다.**

### 5.1 item 1 — CORS 미구현: **확정 (리더 판정 유지)**

§4.5 참조. Claude 1차 C-3 와 같은 대상이므로 `양쪽` 으로 계상한다.

### 5.2 item 2 — Phase 2 에서 parity CI 가 막힘: **확정 · 기전은 리더 서술보다 넓다**

`compare_parity.py` 의 종료 코드 결정 지점을 실제로 읽어 확정했다.

```python
839:        if not pair.actual_path.exists():
840:            problems.append(f"- **Kotlin 결과 파일 없음**: {pair.actual_path}")
...
918:    if missing and not total_problems:
919:        print(f"[도메인 누락] {summary} — 없는 도메인: ... (종료 코드 1)")
920:        return 1
921:    if total_problems:
923:        print(f"[불일치] {summary}{detail}")
924:        return 1
925:    if total_pending:
927:        return 2
```

**"Kotlin 결과 파일 없음"은 `problems` 로 들어가 exit 1 이다. exit 2 가 아니다.** 그런데 `ci.yml:154-155` 의 주석은 정반대를 전제한다.

> `# 종료 코드 2 = 불일치는 없으나 미검증(역방향 산출물 미생성) 케이스가 남음.`
> `# Phase 3·4에서 crypto·jwt·argon2 산출물이 채워질 때까지는 정상 상태다.`

**사면 장치가 겨냥한 조건이 실제로는 발생하지 않는 조건이다.** exit 2(`total_pending`)는 산출물 **파일이 존재하면서** 그 안의 개별 케이스가 미검증일 때만 나온다(`:582`). 파일 자체가 없으면 exit 1 이고, `ci.yml:160` 의 `exit "$status"` 가 그대로 CI 를 빨갛게 만든다.

**두 갈래 모두 막힌다는 점이 핵심이다.** `BUILDERS` 는 도메인 11개(masking·text·style·style-tables·prompts·postprocess·repair-adoption·export·crypto·jwt·argon2)를 갖고, `parity/_harness-selfcheck/kotlin.json` 이 스스로 적었듯 앞 8개가 Phase 2, jwt·argon2 가 Phase 3, crypto 가 Phase 4 다.

| Phase 2 가 fixture 를 이렇게 만들면 | 결과 |
|---|---|
| **11개 전부** 덤프 | crypto·jwt·argon2 의 `parity/actual/*` 부재 → `problems` → **exit 1** |
| **Phase 2 의 8개만** 덤프 | `missing` 3개 → `:918-920` `[도메인 누락]` → **exit 1** |

**Phase 2 에 CI 를 초록으로 만드는 경로가 없다.** 지금 상태로는 Phase 4 에서 crypto 산출물이 채워질 때까지 parity 잡이 계속 빨갛고, 그 압박 아래에서 게이트를 완화하는 수정이 들어오기 쉽다 — 이 하네스가 존재하는 이유가 정확히 그 실패를 막는 것이다. **차단 ②로 올리고 마감을 Phase 2 착수 전으로 둔다.**

**X-1(codex #1 / Claude H-4 전반부)과의 관계.** 같은 래퍼의 **반대 방향 결함**이다. 디렉터리 부재 가드는 **너무 느슨**해서 트리를 지우면 초록이 되고, exit 2 사면은 **겨냥이 빗나가** Phase 2 를 빨갛게 만든다. 하나의 수정으로 둘 다 닫아야 하며, 조치는 "부재를 성공으로 바꾸지 말 것"과 "아직 포팅되지 않은 도메인을 **버전 관리되는 명시적 allowlist** 로만 사면할 것"이다(codex #1 의 권고와 같은 방향).

### 5.3 item 3 — 기동·설정: **실측 결과 두 의심 중 하나는 거짓, 하나는 참이나 영향이 좁다**

**(a) `spring.mvc.problemdetails.enabled` 가 Boot 4 에서 유효한가 → 유효하다. 의심 기각.**

Gradle 캐시의 Boot 4.0.7 아티팩트를 전수 스캔해 설정 메타데이터에서 직접 확인했다.

```json
{
  "name": "spring.mvc.problemdetails.enabled",
  "type": "java.lang.Boolean",
  "description": "Whether RFC 9457 Problem Details support should be enabled.",
  "sourceType": "org.springframework.boot.webmvc.autoconfigure.WebMvcProperties$Problemdetails",
  "defaultValue": false
}
```

출처는 `spring-boot-webmvc-4.0.7.jar`(Boot 4 에서 autoconfigure 가 모듈로 쪼개졌다). `deprecated` 표시 없음. **키 이름·위치 모두 유효하고 오타도 아니다.**

부수 사실 하나: `defaultValue` 가 이미 `false` 이므로 `api/application.yml:31-33` 의 설정은 **기본값을 다시 적은 것**이다. 방어로서 무해하고 의도를 명시하는 값이 있으나, 바로 위 주석(`:29-30`)의 `"Spring 기본 /error 경로가 ProblemDetail 이나 흰 화면을 내지 않도록 막는다"` 는 이 한 줄이 하는 일보다 넓게 읽힌다. 실제로 흰 화면을 막는 것은 `:44-45` 의 `whitelabel.enabled: false` 다. **문서 정확도 문제일 뿐 결함이 아니다.**

**(b) `server.port` 기본값 8000 → 참. 다만 컨테이너 경로는 안전하다.**

| 확인 항목 | 결과 |
|---|---|
| `api/application.yml:36` | `port: ${SERVER_PORT:8000}` — 기본값 **8000**, Python uvicorn 개발 서버와 같다 |
| Boot 4.0.7 프레임워크 기본값 | 8080 (`spring-boot-web-server-4.0.7.jar` 메타데이터) — 즉 8000 은 **의도적 선택** |
| `docker-compose.yml` | `SERVER_PORT` **미설정**. `.env`·`.env.example` 에도 없음 |
| `backend-kotlin/Dockerfile:60` | **`ENV SERVER_PORT=8100`** — 컨테이너 경로는 여기서 8100 으로 고정된다 |
| 살아 있는 컨테이너 실측 | `SERVER_PORT=8100`, 컨테이너 내부 `:8100/health` → **200**, `:8000` → 연결 없음. `easy-doc-kotlin-api-1` 상태 `Up (healthy)` |

**판정: 권고.** 컨테이너·CI·compose 경로에는 충돌이 없고 `/health` 도 정상이므로 **Phase 1 종료 조건을 해치지 않는다.** 남는 것은 호스트에서 JAR·`bootRun` 을 직접 띄울 때 `CLAUDE.md` 가 안내하는 `uv run uvicorn app.main:app --reload`(8000)와 부딪히는 개발 편의 문제다. 포트가 8100 이라는 사실이 `Dockerfile` 한 곳에만 있고 `application.yml` 기본값·`.env.example` 어디에도 반영되지 않은 점은 함께 기록해 둔다.

**(c) "기동"이 깨졌는가 → 아니다.** 1차 실측(E1·E2)과 2차 재확인 모두 `kotlin-api` 가 `Up (healthy)`, `/health` 200 `{"status":"ok"}`. `codex(stop-gate)` 요지의 "기동이 깨진다" 부분은 확인되지 않았다.

---

## 6. 종합 기준 Phase 1 종료 조건 대비 현황

> **계획 §5 Phase 1 종료 조건:** 빈 DB 와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함.

| 항목 | 종합 판정 | 근거 / 유보 |
|---|---|---|
| 빈 DB 기동 + `/health` | **충족** | `ApiStartupOnEmptyDatabaseTest` 2건 + compose 실측(8100, healthy). codex 이견 없음 |
| 기존 snapshot 기동 + `/health` | **조건부 충족** | `ApiStartupOnPythonSnapshotTest` 2건. snapshot 이 V1 파생 대역(F-7), 실제 대상 DB dump 미수집(F-8), 스키마 미고정(F-2), TOCTOU(F-5) |
| 5모듈 골격 (§3.2) | **충족** | 모듈·의존 방향·`runtimeOnly` 확인. 회귀 방어는 미비(M-1·M-4) |
| Flyway V1 = Python 스키마 | **충족 (독립 재검증)** | 지문 61줄 바이트 동일. codex 는 이 층위를 검토하지 않았고 **부정하지도 않았다**(§4.2 층위 판정) |
| CI 에 Kotlin build/test 추가, 기존 게이트 유지 | **미충족** | 기존 게이트 무결(X-10, 양쪽 합의). 그러나 parity 단계가 **양방향으로 고장**나 있다(X-1 느슨 / X-2 겨냥 오류) |
| Dockerfile·compose Kotlin profile | **충족** | 기존 서비스 무변경, `profiles: ["kotlin"]` 분리, 두 스택 동시 기동 실측 |

### Phase 0 에서 미룬 "리뷰 게이트 Critical 0건" 행

**미충족.** 종합 기준 차단(Critical) 항목은 다음과 같다.

| ID | 항목 | 출처 | 갈래 | 마감 |
|---|---|---|---|---|
| **X-2** | Phase 2 에서 parity CI 가 막히고 사면 장치가 겨냥을 빗나감 | codex(stop-gate) | ② 장치 | **Phase 2 착수 전** |
| **X-1** | fixture 트리 부재 → 비교기 미호출 exit 0 백도어 | 양쪽(Claude+codex) | ② 장치 | Phase 2 착수와 동시 |
| **C-1** | 프레임워크 예외 전부 500 (실측 404·405·406·307) | Claude | ① 사건 + ② 장치 | Phase 3 착수 전 |
| **C-2** | 오류 계약 게이트가 HTTP 경계 미통과 | Claude | ② 장치 | Phase 3 착수 전 |
| **C-5** | 계약의 길이 제약 5개가 계약 자신의 422 규칙과 충돌 | Claude | ② 장치 (기준 오류) | Phase 3 착수 전 |
| **C-6** | 계약의 multipart `contentType` 제약이 구현에 없음 | Claude | ② 장치 (기준 오류) | Phase 4 착수 전 |
| **X-4** | crypto 음성 케이스가 정본 대조에서 통째로 빠짐 | Claude | ② 장치 | Phase 4 종료 전 |
| **F-2** | Flyway 대상 스키마 미고정 → 다른 스키마에 baseline 가능 | codex | ② 장치 | Phase 7 착수 전 |
| **C-3** | CORS 미구현 (상향 권고, §4.5) | 양쪽(Claude+codex(stop-gate)) | ② 장치 | Phase 3 착수 전 |
| **F-5** | 잠금 없는 TOCTOU (상향 권고, §4.3) | codex | ② 장치 | Phase 7 착수 전 |

**차단 10건 중 Claude 단독 5, codex 단독 2, codex(stop-gate) 단독 1, 양쪽 2.** 교차 대조의 산출은 합의가 아니라 차이라는 원칙이 여기서 그대로 드러난다 — **차단 항목의 30%가 한쪽만 본 것이다.**

---

## 7. 우선 조치 순서 — Phase 2 착수 기준

판정 근거는 두 가지다. ①**그 결함이 언제 처음 실제 피해를 내는가** ②**지금 고치는 비용 대 나중 비용.** 심각도와 별개 축이다.

### 7.1 Phase 2 착수 **전** 필수 (4건)

| 순위 | 항목 | 왜 착수 전인가 |
|---|---|---|
| **1** | **X-2 + X-1 — parity CI 래퍼 재작성 (한 번의 수정)** | Phase 2 의 산출물 자체가 `parity/fixtures/` 다. 그것을 커밋하는 **첫 순간** CI 가 빨개지고(X-2), 그 압박 아래에서 가장 쉬운 해법은 게이트를 더 느슨하게 만드는 것이다. 같은 블록에 백도어(X-1)가 함께 있으므로 두 번 손대지 않는다. 비용: 지금 셸 블록 한 개. 나중: Phase 2 내내 빨간 CI + 완화 압력 |
| **2** | **X-3 판정 — 정본 manifest 를 BUILDERS 에서 분리할 것인가** (§4.1) | Phase 2 가 `BUILDERS` 를 8개 도메인으로 **실제로 늘리는** 단계다. 설계를 바꿀 거면 늘리기 전이 가장 싸고, 늘린 뒤에는 fixture·검증기·CI 가 모두 그 위에 얹힌다. **리더 판정만 필요하고 구현은 병행 가능** |
| **3** | **T-5 판정 — Boot 4.0.7 vs 계획의 "4.1 계열"** (K-3) | 되돌리는 비용이 지금은 catalog 2줄이다. Phase 2 에서 도메인 코드가 쌓이고 Phase 4 에서 POI/PDFBox 가 붙으면 커진다. 1차에서 이미 "Phase 2 착수 전"으로 마감을 잡았고 종합에서도 유지한다 |
| **4** | **F-8 확인 — 절체 대상 실 DB 가 존재하는가** (P-3) | 판정이 아니라 **사실 확인 1건**이고 비용이 0 에 가깝다. 존재하지 않으면 F-2·F-3·F-5·F-7 의 심각도 근거가 크게 달라지므로, 이 답이 없으면 Flyway 축 4건의 우선순위를 정할 수 없다 |

**착수를 막지 않는 근거(반대편도 적는다).** Phase 2 는 순수 도메인 로직 포팅이고 종료 조건이 `"외부 API·DB 없이 실행하는 parity suite"` 다(계획 `:243`). 따라서 **HTTP 경계(C-1·C-2·C-3)와 DB·Flyway 축(F-2·F-5)은 Phase 2 작업에 닿지 않는다.** 차단 10건 중 실제로 Phase 2 를 막는 것은 X-2 하나뿐이며, 나머지는 마감이 뒤에 있다. **심각도가 높다는 이유로 착수를 막지 말 것을 권고한다.**

### 7.2 Phase 2 **병행** (Phase 2 종료 전에 닫는다)

| 항목 | 근거 |
|---|---|
| **X-5** — `parityHarness` 산출물 0건 성공 | Phase 2 가 모듈별 parity 산출물을 처음 만드는 단계다. 지금 모듈↔도메인 대응을 단언해 두지 않으면 X-1·X-2 를 고쳐도 **한 층 아래에서 같은 우회가 남는다**(1차 H-5 의 4단계 합성 경로) |
| **M-1 · M-4** — 경계 검사를 구성(configuration) 수준으로 (§4.4) | `core` 격리가 Phase 2 종료 조건의 **전제**다. Phase 2 는 `core` 에 도메인 코드를 쌓는 단계이므로, 그 사이에 의존이 새면 "불일치가 도메인 문제인지 배선 문제인지" 가릴 수 없다 — `CoreModuleBoundaryTest` KDoc 이 스스로 적은 실패 양태다 |
| **X-4** — crypto 음성 케이스 정본 대조 | 마감은 Phase 4 지만 수정 위치가 `VOLATILE_INPUT_FIELDS`·`dump_parity_fixtures.py` 로, Phase 2 가 어차히 손대는 파일이다. 해법도 같은 파일 안(`argon2` 방식)에 이미 실증돼 있다 |
| **X-9** — CI `kotlin` 잡 `uv sync --locked` | 비교기가 Python 정본 생성기를 재실행하는 구조이므로, Phase 2 에서 정본이 실제로 쓰이기 시작하면 이 잡의 의존성 드리프트가 곧 "정본이 달라진다"가 된다. 한 줄 |
| **C-5 · C-6** — 계약 파일 자체의 오류 2건 | Phase 2 는 계약을 쓰지 않으므로 착수를 막지 않는다. 그러나 **고치는 비용이 지금 가장 싸고(YAML 몇 줄)**, Phase 3·4 코드가 틀린 기준 위에 쌓이면 되돌리는 비용이 코드 규모로 커진다. 1차에서 "다섯 중 지금 닫기를 가장 권하는 두 건"으로 꼽은 이유가 그대로 유효하다 |
| **F-2** — Flyway 대상 스키마 고정 | 마감은 Phase 7 이지만 조치가 `spring.flyway.schemas` + JDBC `currentSchema` **설정 2줄**이다. Phase 3 이 DB 를 본격적으로 쓰기 시작하면 그 위에 테스트가 쌓인다 |

### 7.3 더 뒤 (마감만 고정하고 지금은 손대지 않는다)

| 항목 | 마감 | 비고 |
|---|---|---|
| C-1 · C-2 · C-3 · C-4 · C-7 · T-1 | Phase 3 착수 전 | HTTP 경계·CORS·설정 바인딩. Phase 3 의 첫 작업으로 묶는 것이 자연스럽다 (C-2 를 먼저 만들면 C-1 이 그 테스트로 드러난다) |
| F-11 (`updated_at`) | Phase 3 종료 전 | Kotlin repository 가 들어오는 시점 |
| F-3 판정 (`alembic_version` 읽기) · F-5 (TOCTOU) · F-4 (지문 사각) · F-6 (api profile Flyway) · F-7 (Alembic 회귀) | Phase 7 착수 전 | **F-8 답에 따라 우선순위가 바뀐다.** 실 DB 가 존재하면 이 묶음이 Phase 3 로 당겨져야 한다 |
| T-2 (`EasyDocProperties` 모듈 위치) | Phase 5 착수 전 | 파일 한 개 이동. 다만 T-1 을 닫을 때 함께 드러나므로 Phase 3 에 묶어도 좋다 |
| C-6 (multipart) | Phase 4 착수 전 | 7.2 에 병행으로 올렸으나 늦어도 여기 |
| 나머지 권고 (C-8, F-12, M-2, M-3, S-2~S-4, T-3, T-6, X-7, X-8) | 각 항목 | 1차 산출물 본문 참조 |

---

## 8. Phase 1 종료 판정 권고

**권고: 조건부 종료 — Phase 1 자체 종료 조건은 닫고, "리뷰 게이트 Critical 0건" 행은 닫지 않는다.**

**① 계획 §5 Phase 1 의 명시적 종료 조건은 충족됐다.** "빈 DB 와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함" — 양쪽 다 테스트와 실측으로 확인했고, `codex` 도 기동 실패를 지적하지 않았으며, `codex(stop-gate)` 의 "기동이 깨진다" 주장은 §5.3 에서 확인되지 않았다. 이 조건을 억지로 열어 둘 근거가 없다.

**② 그러나 Phase 0 에서 미룬 "리뷰 게이트 Critical 0건" 행은 충족되지 않았다.** 종합 기준 차단 10건이 남아 있다(§6). 이 행을 지금 닫으면 **10건이 마감 없이 사라진다** — 저평가된 지적이 게이트를 그냥 통과하는 것이 이 역할에서 가장 흔한 실패다.

**③ 그렇다고 Phase 2 착수를 막는 것은 과잉이다.** 차단 10건 중 Phase 2 작업에 실제로 닿는 것은 X-2 하나뿐이고, 나머지 9건의 마감은 전부 Phase 3 이후다. Phase 2 는 순수 도메인 로직이라 HTTP·DB·CORS 경계를 쓰지 않는다.

**따라서 권고하는 판정 형태는 다음과 같다.**

> Phase 1 을 **종료**한다. "리뷰 게이트 Critical 0건" 행은 **"Phase 2 착수를 막는 Critical 0건"** 으로 범위를 좁혀 §7.1 의 4건(X-2+X-1 수정, X-3 판정, T-5 판정, F-8 확인)을 닫은 시점에 판정하고, 나머지 차단 8건은 **마감이 명시된 미결 원장**으로 이월해 각 Phase 착수 게이트에서 다시 센다.

이렇게 하면 (a) 충족된 종료 조건을 인위적으로 열어 두지 않고, (b) 차단 항목이 마감 없이 사라지지 않으며, (c) Phase 2 가 실제로 막히는 한 건만 선행 조건이 된다.

**최종 판정은 리더가 내린다.** 이 문서는 근거만 제공한다. 대안 판정 두 가지도 함께 적는다 — **더 보수적**: §7.1 4건 + §7.2 의 X-5·M-1·M-4 까지 닫고 종료(모듈 경계 회귀 방어가 Phase 2 의 전제라는 논리를 끝까지 밀 경우). **더 공격적**: X-2 만 닫고 종료(X-3·T-5 판정을 Phase 2 진행 중에 받는다) — 다만 T-5 는 되돌리는 비용이 시간에 비례해 커지므로 권하지 않는다.

### 리더 판단이 필요한 항목 (종합)

| # | 항목 | 유형 | 참조 |
|---|---|---|---|
| 1 | 정본 manifest 를 BUILDERS 에서 분리할 것인가 | **설계 충돌** | §4.1 |
| 2 | 가드가 `alembic_version` 을 read-only 로 읽을 것인가 | **충돌** | §4.2 |
| 3 | F-5(TOCTOU) 심각도 상향(권고→차단 ②) 여부 | **충돌 · 상향 권고** | §4.3 |
| 4 | M-1·M-4 심각도 상향(권고→수정 필요) 여부 | **부분 충돌 · 상향 권고** | §4.4 |
| 5 | C-3(CORS) 심각도 상향(수정 필요→차단 ②) 여부 | 합의 · 상향 권고 | §4.5 |
| 6 | Boot 4.0.7 vs 계획의 "4.1 계열" | 판정 필요 | T-5 |
| 7 | 절체 대상 실 DB 가 존재하는가 | **사실 확인** | F-8 |
| 8 | api profile 이 마이그레이션 주체인가 | 판정 필요 | F-6 |
| 9 | 계약을 사실에 맞출 것인가 `deps.py:118` 을 계약에 맞출 것인가 | 판정 필요 | C-7 |
| 10 | FastAPI 문서 라우트를 계약에 넣을 것인가 양쪽 다 끌 것인가 | 판정 필요 | C-8 |
| 11 | Phase 1 종료 판정과 "리뷰 게이트 Critical 0건" 행의 처리 | **게이트 판정** | §8 |

---

## 9. 종합 중 발견 — **미교차** (다음 회차 범위로 제안)

> 아래는 세 산출물 어디에도 없고 2차 종합 중에 눈에 띈 것이다. **교차 검증을 거치지 않았으므로 이번 게이트 판정의 근거로 쓰지 않는다.** 별건으로 다음 리뷰 회차에 넣기를 제안한다.

1. **`worker` 테스트 클래스패스가 `spring-boot-starter-web` 을 끌어온다** — `backend-kotlin/worker/build.gradle.kts:25`. `worker` 는 비웹 컨텍스트(`spring.main.web-application-type: none`)이고 `WorkerStartupTest` 가 "비웹 컨텍스트"를 단언하는데, 테스트 클래스패스에 웹 스타터가 있으면 그 단언이 "설정이 이겼다"를 보는 것인지 "의존이 없어서 그렇다"를 보는 것인지 구분되지 않는다. 심각도 추정: 권고. **미교차.**

2. **`api/application.yml:29-30` 주석이 그 아래 설정보다 넓게 읽힌다** — `spring.mvc.problemdetails.enabled: false` 는 Boot 4.0.7 의 **기본값과 같다**(§5.3-a 메타데이터 확인). 흰 화면을 실제로 막는 것은 `:44-45` 의 `whitelabel.enabled: false` 다. 결함이 아니라 문서 정확도 문제. 심각도 추정: 권고. **미교차.**

3. **컨테이너 포트 8100 이 `backend-kotlin/Dockerfile:60` 한 곳에만 있다** — `application.yml` 기본값은 8000, `docker-compose.yml`·`.env.example` 에는 `SERVER_PORT` 가 없다. 현재 동작에는 문제가 없으나(실측 healthy), 포트 사실이 세 곳에 흩어져 한 곳만 바뀌면 조용히 어긋난다. 심각도 추정: 권고. **미교차** (§5.3-b 확정 작업 중 부수 발견).

---

## 10. 대조하지 못한 범위 · 확인 불가

**codex 리뷰는 정상 도착했다. "codex 리뷰 없음 — 교차 대조 미수행"에 해당하지 않는다.**

| # | 항목 | 상태 |
|---|---|---|
| 1 | **계약 준수·보안 불변식·테스트 적정성 축 전체** | **교차 대조 불가.** codex focus text(§2)가 5개 축만 지정했고 "일반적인 Kotlin 코드 리뷰는 하지 마라"를 명시했다. 이 세 축의 Claude 지적 **17건(C-1~C-9, S-1~S-5, T-1~T-6)** 은 **단일 관점 판정**이며 교차 검증을 받지 않았다. `codex(stop-gate)` 가 그중 CORS 하나만 독립적으로 짚었다. **다음 회차 codex 호출의 focus 를 이 세 축으로 잡을 것을 리더에게 제안한다** |
| 2 | `privacy-gate` 의 Phase 1 감사 산출물 | **부재.** `docs/migration/_workspace/` 에 `01_privacy-gate_*` 없음. §3.5 보안 축 판정은 **잠정**이며, `privacy-gate` 결과와 갈리면 그쪽을 따른다 |
| 3 | `parity-verifier` 의 Phase 1 산출물 | **부재.** parity 축 판정은 코드 읽기와 로컬 실행에 의존했다 |
| 4 | GitHub Actions 실제 실행 | **미실행.** X-2 는 `compare_parity.py` 의 종료 코드 결정 지점을 코드로 읽어 확정했으나, 러너 위에서의 실제 실행은 Phase 2 첫 push 에서 처음 검증된다 |
| 5 | `./gradlew build` 전체(48건) 2차 재실행 | **미실행.** 1차와 동일 |
| 6 | 실제 fixture 로 `compare_parity.py` 실행 | **불가.** `parity/fixtures/` 가 HEAD·작업 트리 양쪽에 0건(§2-#1) |
| 7 | codex #3a(스키마 미고정)·#4(TOCTOU) 의 **실증** | **미실행.** 두 지적 모두 코드 구조로 성립을 확인했으나(§4.3), 실제 `search_path` 조작·동시 기동 재현은 하지 않았다. **F-2·F-5 는 코드 판정 근거이며 실증 근거가 아니다** |
| 8 | 이전 회차 충돌 항목의 리더 판단 | **해당 없음.** `01_skeleton` 은 첫 교차 종합 회차다 |

---

## 11. 리더에게 — 요약 4줄

1. **codex 의 사실 주장은 전부 참이다**(§2, 10항목 전수 대조). 틀린 전제 위의 지적은 없다.
2. **codex 가 Claude 보다 옳았던 자리가 둘 있다** — F-5(TOCTOU: "Flyway 잠금이 있으니 안전"이라는 Claude 의 완화 판단이 잠금 순서를 놓쳤다)와 F-3(`alembic_version` 읽기 금지는 계획이 아니라 구현자의 자기부과 제약이었다). **양쪽 지적을 모두 남기고 심각도 상향을 권고**하되 판정은 넘긴다.
3. **차단 10건 중 30%가 한쪽만 본 것이다.** Claude 단독 5(계약·하네스 축) / codex 단독 2(Flyway 축) / codex(stop-gate) 단독 1(parity CI) / 양쪽 2.
4. **Phase 2 를 실제로 막는 것은 X-2 하나다.** Phase 1 종료 자체는 권고하되 "Critical 0건" 행은 열어 두고, §7.1 의 4건을 착수 전 조건으로 삼기를 권고한다(§8).
