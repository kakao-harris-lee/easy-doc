# Phase 1 `skeleton` — codex 독립 리뷰 (1회차)

> 이 문서는 codex(GPT 계열)의 독립 리뷰 **원문 보존** 산출물이다. `codex-reviewer`는 codex 출력의 옳고 그름을 판정하지 않는다.
> 판정과 교차 종합은 `migration-reviewer`의 2차 호출(`01_skeleton_cross.md`)과 오케스트레이터의 몫이다.

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 회차 | 1회차 |
| 실행 시각 | 2026-08-12 09:39:19 ~ 09:45:20 KST (약 6분) |
| `{phase}_{scope}` 어간 | `01_skeleton` — 리더가 1단계 호출에서 지정한 값 |
| 모드 | `adversarial` (헬퍼 `adversarial-review`) |
| scope / base | `auto`(미지정), `--base 1f8f352` → base 지정이므로 scope 무시, branch diff |
| 리뷰 대상 | `merge-base(HEAD,1f8f352)..HEAD` = `1f8f352..2ed897d`, 변경 파일 67개 |
| **스크립트 종료 코드** | **`0`** (리뷰 근거로 유효) |
| codex verdict | `needs-attention` |
| job id | `review-mspd3fvf-mgh719` |
| codex session id | `019ff368-e9db-7662-8ad2-8c93498b18f6` |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택), 버전 `1.0.6` |

### 실행 명령 (스크립트 인자 그대로)

```bash
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 1f8f352 "<아래 §2 focus text 전문>"
```

스크립트가 헬퍼로 넘긴 명령:

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base 1f8f352 <focus text>
```

### 대상 판정 두 줄 (스크립트 stderr 원문)

```
codex-review: 리뷰 대상 = branch diff vs 1f8f352
codex-review: 대상 판정 = non-empty (merge-base=1f8f3525106d, 변경 파일 67개 (branch 모드는 커밋된 변경만 센다))
```

`--dry-run` 선행 확인에서도 동일하게 `non-empty / 67개`가 나왔다(종료 코드 6). 이전 회차에서 미추적 디렉터리 전수 탐색으로 취소된 이력이 있어, `--base`로 커밋된 범위만 잡아 그 경로를 피했다.

### 리뷰 대상에 들어온 커밋

| 커밋 | 내용 |
|---|---|
| `5314e0b` | Phase 0 — `contracts/easy-doc-v1.yaml`, spike 산출물, parity 게이트 Critical 2건 수정(`.claude/skills/python-kotlin-parity/scripts/`) |
| `2ed897d` | Phase 1 — `backend-kotlin/` 멀티모듈, Flyway, CI, docker-compose |

### 제공한 맥락

focus text 안에 직접 주입했다(codex는 이 저장소의 계획 문서 맥락을 갖고 있지 않다).

- 저장소 성격: 공공기관 문서 변환 SaaS, Python/FastAPI → Kotlin/Spring Boot 전환, 이 diff가 첫 Kotlin 코드
- 보안·데이터 불변식 5항목 (마스킹 선행, `no-store`/`nosniff`, 소유권 은닉 404, 로그 개인정보 금지, 기존 스키마 이름 불변 + `alembic_version` 불가침)
- 적대적 리뷰 축 5개 (모듈 경계 / Flyway baseline 가드 / `encryption_scheme` V2 배치 / CI 게이트 약화 / parity 게이트 우회)

민감 데이터(실제 암호문·키·사용자 문서 본문·개인정보)는 프롬프트에 포함하지 않았다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
이 저장소는 공공기관 문서 변환 SaaS다. Python/FastAPI 백엔드를 Kotlin/Spring Boot로 교체하는 전환 중이며, 이 diff가 이 저장소의 첫 Kotlin 코드다(Phase 0 계약 동결 + Phase 1 골격).

반드시 지켜져야 하는 불변식:
- 사용자 문서 원문은 마스킹 파이프라인을 통과한 뒤에만 LLM provider로 전달된다.
- 개인정보·자격증명이 실리는 성공 응답 10곳에는 Cache-Control: no-store 와 X-Content-Type-Options: nosniff 가 붙고, 오류 응답에는 붙지 않는다.
- 타인 소유 자원 접근은 403이 아니라 404로 자원 존재 자체를 은닉한다.
- 로그에 문서 본문·개인정보를 남기지 않는다.
- 기존 PostgreSQL 테이블·컬럼·제약 이름을 바꾸지 않는다. alembic_version 테이블은 Kotlin 경로에서 절대 수정되지 않는다.

아래 5개 축만 깊게 파라. 일반적인 Kotlin 코드 리뷰(널 안전성 스타일, 예외 처리 취향, 네이밍)는 하지 마라. 위반을 찾으면 파일 경로와 라인을 그대로 제시하라.

1. 모듈 경계가 실제로 강제되는가. backend-kotlin은 Gradle 멀티모듈(core / application / infrastructure / api / worker)이다. core는 Spring·DB·LLM SDK에 비의존이어야 하고, infrastructure는 api·worker에 runtimeOnly로만 연결돼 컴파일 시점에 JDBC·LLM SDK 타입이 보이지 않아야 한다. CoreModuleBoundaryTest가 이를 검사한다고 주장한다. 그 테스트를 우회해 경계를 깨는 방법을 찾아라: testFixtures 구성, 전이 의존(api 대 implementation 선택), compileOnly·annotationProcessor·kapt 구성, 테스트 소스셋, 플러그인이 자동으로 붙이는 의존, 버전 카탈로그의 BOM·플랫폼 의존. 그 테스트가 검사하지 '않는' 구성·소스셋·패키지가 무엇인지 명시하라.

2. Flyway baseline 가드에 우회 경로가 있는가. FlywayBaselineGuard가 기존 Alembic 스키마의 지문(fingerprint)을 계산해 일치할 때만 baseline을 찍고, 다르면 기동을 실패시킨다고 주장한다. baseline-on-migrate는 쓰지 않았다. 물을 것: 지문 계산이 실제 스키마 차이를 놓치는 경우(대소문자, search_path·스키마 한정, 컬럼 타입·nullable·기본값·인덱스·제약 누락, 행 정렬 불안정), 지문이 일치하는데 실제로는 호환되지 않는 DB에 baseline을 찍는 경로, 동시 기동 시 경합, 그리고 alembic_version 테이블이 Kotlin 경로(마이그레이션 SQL, 가드 코드, 테스트 유틸, docker-compose 초기화 SQL) 어디에서든 수정·삭제될 여지가 있는가. baseline-on-migrate를 안 쓴 것이 실제로 안전을 더하는가, 아니면 같은 위험이 다른 경로로 들어오는가.

3. encryption_scheme 컬럼을 V1이 아니라 V2 마이그레이션에 둔 판단이 옳은가. 근거는 "기존 Alembic DB에서는 baseline이 V1을 건너뛰므로 V1에 넣으면 그 컬럼이 영원히 생기지 않는다"이다. 빈 DB 경로(V1부터 전부 실행)와 기존 스냅샷 경로(baseline 후 V2부터 실행) 양쪽에서 이 컬럼이 정확히 한 번, 같은 타입·기본값·NOT NULL 여부로 생기는지 확인하라. 기존 행에 기본값 'fernet-v1'이 실제로 채워지는가. V1과 V2가 같은 대상을 두 번 만들거나 서로 어긋나는 지점은 없는가. 이 컬럼을 읽는 코드가 NULL을 만날 수 있는가.

4. CI 게이트가 약화됐는가. .github/workflows/ci.yml에 Kotlin 단계를 추가하면서 기존 Python·React 게이트가 조건부(if / continue-on-error / paths 필터)가 되거나 실패를 삼키게 되지 않았는지 확인하라. 특히 parity 비교 단계가 종료 코드 2(미검증)를 통과 처리한다 — 이 완화가 2 이외의 실패(1=불일치, 그 외 크래시·비정상 종료)까지 삼키는지, 파이프·서브셸·|| true·set -e 부재로 종료 코드가 유실되는지, 그리고 Kotlin 단계 실패가 워크플로 전체 실패로 이어지는지 보라.

5. parity 게이트의 "검증 없이 통과" 경로가 실제로 닫혔는가. parity 하네스(.claude/skills/python-kotlin-parity/scripts/)의 Critical 2건을 이 diff에서 고쳤다고 한다: (a) compare_parity.py가 더 이상 디스크의 proof 파일을 신뢰해 읽지 않고 검증기를 in-process로 직접 실행한다(check_external), (b) provenance_problems()가 매 비교마다 정본 생성기를 재실행해 fixture와 대조한다. 물을 것: 여전히 검증 없이 "통과"로 끝나는 경로가 있는가 — 비교 대상이 0건인데 성공으로 끝나는 경우, 예외를 삼켜 미검증을 통과로 바꾸는 경우, 검증기를 찾지 못하거나 임포트에 실패했을 때의 처리, 환경변수·CLI 인자·설정 파일·경로 조작으로 검사를 끄는 경로, fixture나 정본 생성기 자체를 바꿔 대조가 자동으로 맞아 떨어지게 만드는 경로, 그리고 종료 코드가 CI에서 실제로 실패로 이어지는지. 이 영역은 앞선 리뷰 회차에서 우회 경로가 세 차례 연속 발견된 곳이니 특히 집요하게 파라.
```

---

## 3. codex 원문 출력 (무편집)

아래는 스크립트 stdout 전문이다. 번역·요약·재배열·오탈자 수정을 일절 하지 않았다.

```text
# Codex Adversarial Review

Target: branch diff against 1f8f352
Verdict: needs-attention

No-ship: parity CI currently succeeds without running parity, its provenance check trusts the same mutable generator/verifiers, the module-boundary test has trivial classpath bypasses, and the Flyway baseline decision can validate the wrong schema or a stale snapshot. V2 encryption_scheme placement itself was not a blocker.

Findings:
- [high] Parity job succeeds when zero fixtures exist (.github/workflows/ci.yml:143-159)
  The job exits 0 before invoking compare_parity.py whenever parity/fixtures is absent. HEAD currently tracks no parity/fixtures files, so the new in-process external verification, provenance checks, empty-case rejection, and exit-code logic never execute in CI. Deleting or renaming the complete fixture tree later recreates the same green-with-zero-comparisons path. The subsequent shell correctly propagates code 1 and unexpected statuses, but it also deliberately converts code 2 (explicitly unverified) to success.
  Recommendation: Always invoke the comparator and make an absent fixture root fail. If Phase 1 needs a bootstrap exception, keep it in a separately named non-required job or enforce a versioned allowlist of exact pending cases; the required parity gate must not translate absence or exit 2 into success.
- [high] Provenance and external verification trust the same mutable source they validate (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:107-117)
  EXPECTED_DOMAINS, BUILDERS, and VERIFIERS are imported from dump_parity_fixtures.py. canonical_fixture() then re-executes those same BUILDERS (lines 400-423). A single change can remove a domain/case, alter an expected value, or weaken run_verify_crypto/run_verify_jwt, regenerate matching fixtures, and be accepted as canonical; check_external merely runs the newly weakened verifier in-process. Thus the two claimed Critical fixes detect stale/manual proof files but do not detect coordinated generator/verifier weakening in the same diff.
  Recommendation: Pin the required domain/case manifest independently of BUILDERS, and add adversarial tests that feed known-corrupt Crypto/JWT artifacts and assert exit 1. Treat generator/verifier changes as a separately protected review surface, with tests whose rejection inputs are not produced by those same builders.
- [high] The fingerprint can approve the wrong migration target and ignores the Alembic head (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/SchemaFingerprint.kt:20-68)
  The query hard-codes public and excludes alembic_version, while Flyway has no pinned default schema and V1/V2 use unqualified DDL. A JDBC currentSchema/search_path change can therefore fingerprint canonical public but baseline and migrate another first schema. Even in public, a schema-identical database with alembic_version='0005' passes: Kotlin records baseline 1 and V2, then Python later retries 0006 and collides with already-existing objects. The fingerprint also omits behavior-affecting state such as RLS/policies, triggers, partition flags, and grants, so those drifts can match exactly.
  Recommendation: Pin and schema-qualify the Flyway target (for example public), derive the fingerprint schema from that exact configuration, and reject any current_schema mismatch. Read alembic_version without modifying it and require exactly one approved head such as 0006. Include or explicitly reject behavior-affecting metadata such as RLS and triggers.
- [high] Fingerprint verification and baseline stamping have an unlocked TOCTOU window (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt:43-76)
  History inspection, table counting, fingerprinting, baseline(), and migrate() are separate operations using separate connections with no lock spanning the decision. Another Alembic deployment can alter the schema after the fingerprint matches but before Flyway records baseline 1; concurrent Kotlin starters can also both make the decision against a history-free snapshot. Flyway's migration-history locking cannot retroactively protect the preceding fingerprint decision or serialize it with Alembic, so the process can stamp a schema it never verified or fail unpredictably during cutover.
  Recommendation: Enforce a single migration owner during cutover and acquire a PostgreSQL advisory/deployment lock before inspecting history, retaining it through fingerprint, baseline, and migrate. Ensure the Alembic path participates in the same exclusion protocol, then add concurrent-start and concurrent-DDL integration tests.
- [medium] CoreModuleBoundaryTest can be bypassed with compileOnly or any unlisted framework type (backend-kotlin/core/src/test/kotlin/kr/easydoc/core/CoreModuleBoundaryTest.kt:23-45)
  The test only calls Class.forName for seven class names on testRuntimeClasspath. A compileOnly Spring dependency plus an ApplicationContext reference in core/src/main compiles, remains absent at test runtime, and makes loaded.isFailure true, so the test passes while core directly depends on Spring. annotationProcessor/kapt, generated or custom source sets, testFixtures, and plugin-added compile dependencies are not inspected; an alternate DB/LLM SDK also passes because no SDK classes are listed. Nothing here verifies that api/worker retain infrastructure as runtimeOnly or that their compile classpaths exclude infrastructure/JDBC/LLM types, regardless of package.
  Recommendation: Add Gradle configuration assertions over main and generated compile classpaths and outgoing variants, forbid Spring/DB/LLM coordinates across api, implementation, compileOnly, annotationProcessor, kapt, and plugin-added configurations, and bytecode-scan core main output. Separately assert that api/worker compileClasspath excludes infrastructure and its SDKs while runtimeClasspath contains it.

Next steps:
- Close the absent-fixture and exit-2 CI success paths, then add adversarial parity self-tests.
- Replace runtime class-name probing with Gradle configuration and bytecode boundary enforcement.
- Pin the Flyway schema, validate the Alembic head read-only, and serialize the complete baseline decision before reconsidering shipment.
```

---

## 4. 정리 (가공)

**이 구획은 `codex-reviewer`가 원문을 목록화한 것이다. 옳고 그름·심각도 재평가·중복 병합·오탐 판정을 하지 않는다.** 심각도 라벨은 codex가 붙인 값(`high`/`medium`)을 그대로 옮겼고, 이 하네스의 Critical/Major 척도로 환산하지 않았다 — 환산은 `migration-reviewer`의 교차 종합에서 이뤄진다.

codex 종합 판정: **`needs-attention`** (No-ship 문구 포함).

| # | codex 지적 (원문 제목) | codex 심각도 | codex가 제시한 근거 위치 (원문 그대로) | 대응 축 |
|---|---|---|---|---|
| 1 | Parity job succeeds when zero fixtures exist | high | `.github/workflows/ci.yml:143-159` | 축 4·5 |
| 2 | Provenance and external verification trust the same mutable source they validate | high | `.claude/skills/python-kotlin-parity/scripts/compare_parity.py:107-117` (본문에서 `lines 400-423` 추가 언급) | 축 5 |
| 3 | The fingerprint can approve the wrong migration target and ignores the Alembic head | high | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/SchemaFingerprint.kt:20-68` | 축 2 |
| 4 | Fingerprint verification and baseline stamping have an unlocked TOCTOU window | high | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt:43-76` | 축 2 |
| 5 | CoreModuleBoundaryTest can be bypassed with compileOnly or any unlisted framework type | medium | `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/CoreModuleBoundaryTest.kt:23-45` | 축 1 |

### 축별 응답 현황

| 축 | codex 응답 |
|---|---|
| 1. 모듈 경계 | 지적 1건(#5, medium). `compileOnly` 우회, 미검사 구성(`annotationProcessor`/`kapt`/생성 소스셋/`testFixtures`/플러그인 추가 의존), 미열거 SDK, api·worker의 `runtimeOnly` 미검증을 지목 |
| 2. Flyway baseline 가드 | 지적 2건(#3·#4, 둘 다 high). 스키마 미고정에 따른 대상 오인, `alembic_version` head 미확인, 잠금 없는 TOCTOU 창을 지목. **`alembic_version`을 Kotlin이 수정한다는 지적은 없었고**, #3에서 "Read alembic_version without modifying it"을 권고로 제시 |
| 3. `encryption_scheme` V2 배치 | **지적 없음.** 요약문에 `"V2 encryption_scheme placement itself was not a blocker."`로 명시. 빈 DB/기존 스냅샷 양 경로, 기본값 채움 여부에 대한 별도 서술은 출력에 포함되지 않음 |
| 4. CI 게이트 약화 | #1에 포함. fixture 부재 시 comparator 호출 전 exit 0, 종료 코드 2→성공 변환을 지목. 한편 `"The subsequent shell correctly propagates code 1 and unexpected statuses"`라고 서술. **기존 Python·React 게이트가 조건부화·실패 삼킴으로 바뀌었다는 지적은 나오지 않았다** |
| 5. parity 게이트 우회 | #1·#2. codex는 두 Critical 수정에 대해 `"detect stale/manual proof files but do not detect coordinated generator/verifier weakening in the same diff"`라고 서술 |

### 전제 확인 필요 (판정 아님)

다음은 codex 서술에 포함된 사실 주장으로, `migration-reviewer`가 저장소 실제 상태와 대조해야 할 항목이다. **이 문서는 그 대조를 수행하지 않는다.**

- `"HEAD currently tracks no parity/fixtures files"` — HEAD에 `parity/fixtures` 추적 파일이 실제로 0건인지
- `"EXPECTED_DOMAINS, BUILDERS, and VERIFIERS are imported from dump_parity_fixtures.py"` 및 `canonical_fixture()`의 `lines 400-423` 위치
- `"Flyway has no pinned default schema and V1/V2 use unqualified DDL"`
- `"The test only calls Class.forName for seven class names on testRuntimeClasspath"` — 클래스 이름 7개라는 수치
- 인용된 라인 범위 전부 (`ci.yml:143-159`, `compare_parity.py:107-117`, `SchemaFingerprint.kt:20-68`, `FlywayBaselineGuard.kt:43-76`, `CoreModuleBoundaryTest.kt:23-45`)

### codex가 제시한 권고 (채택 여부는 이 문서에서 판단하지 않음)

각 지적의 `Recommendation:` 줄과 `Next steps:` 3항목이 §3 원문에 그대로 있다. 프로젝트 규칙과의 정합성 검토는 교차 종합 단계의 몫이다.

---

## 5. 미실행·실패 항목

- **없음.** codex 리뷰는 1회 호출로 정상 완료했다(종료 코드 `0`, stdout 6,262바이트, verdict `needs-attention`). 재시도·타임아웃·부분 응답·출력 잘림은 발생하지 않았다.
- 리더가 지정한 시간 상한 20분 이내(약 6분)에 끝나 job 취소는 없었다.
- 이전 회차 맥락(`00_pre-phase0_codex-reviewer.md`)은 **리뷰 범위가 다르므로**(그 회차는 하네스 정의 대상, 이번은 Phase 0+1 커밋 diff) focus text에 이전 지적 목록을 싣지 않았다. 다만 축 5에 "이 영역은 앞선 리뷰 회차에서 우회 경로가 세 차례 연속 발견된 곳"이라는 이력 사실만 포함했다.
- 민감 데이터(실제 암호문·키·문서 본문·개인정보)를 프롬프트에 포함하지 않았다.

---

## 6. 다음 단계 (프로토콜상 위치)

이 문서는 `codex-review` 스킬 §2.1 리뷰 게이트의 **1단계(병렬·독립 실행)** 중 codex 측 산출물이다.

- 2단계: `01_skeleton_migration-reviewer.md`와 이 파일이 모두 존재하는지 확인
- 3단계: `migration-reviewer` **재호출**로 두 파일을 §5 표에 대조해 `01_skeleton_cross.md` 작성 (새 지적 생성 금지, 대조만)
- Phase 1 종료 **판정**은 오케스트레이터가 `..._cross.md`를 근거로 내린다. 이 문서는 판정하지 않는다.
