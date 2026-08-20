# 게이트 28 — 1단계 Claude 독립 리뷰 (`04_documents-c3`)

- **역할**: `migration-reviewer` · **회차**: 1차(독립 리뷰). codex 산출물을 열지 않았다.
- **대상**: `66f008b..16df925` (11커밋) — 계약 v1.3.0 · 502 철거 · 소유 술어 탐지기 · 제목 서로게이트 · `POST /documents` · 원장 4건
- **고정 리비전**: `16df9252e557a047886e11d9b6a31d74d348723e`. 워킹 트리는 추적 파일 변경 0(미추적 3건은 이전부터 있던 것).
- **참조한 계획 문서 절**: §2.2 · §2.3 · §3.1 · §3.2 · §4.1 · §4.2 · §4.4 · §4.5 · §4.6 · §5 Phase 4·7 · §6 / `migration-safety-gate` I-4 · I-5 · I-7 · I-10 / `CLAUDE.md` 규칙 4(선언 범위 대 실제 도달)
- **codex 산출물**: **열지 않았다**(1차의 규율). 교차 종합은 3단계.

---

## 0. 이 회차에 실제로 실행한 것 / 실행하지 않은 것

**돌린 것처럼 적지 않는다.**

| 항목 | 상태 | 결과 |
|---|---|---|
| `./gradlew build --rerun-tasks` (HEAD, `run_gate.sh` 경유·파이프 없음) | **실행** | **exit 0 · BUILD SUCCESSFUL · 79 actionable tasks: 79 executed** |
| JUnit 리포트 XML 집계(5모듈) | **실행** | **tests 1012 · failures 0 · errors 0 · skipped 0** |
| `scan_privacy_invariants.py` 전수 (`run_gate.sh` 경유) | **실행** | **exit 0 · 검사 파일 327개 · BLOCK 0** |
| `uv run pytest tests/test_kotlin_gate_reach.py tests/test_raw_control_chars.py tests/test_harness_scope_reach.py` | **실행** | **exit 0 · 153 passed** (로컬 = 요구 모드 OFF) |
| **GitHub Actions 실측** (`gh run list` · `gh run view --log-failed`) | **실행** | **아래 C-1~C-3 의 근거. 이 회차의 최대 발견이다** |
| 저장소 전수 이름 대조(산문이 지목한 `*Test` 이름 ↔ 선언된 클래스) | **실행** | M-1 |
| 계약 파일 파싱(502 잔재·`x-private-response-headers` 목록) | **실행** | 검토함 |
| `frontend/` 실패 테스트의 코드 진단 | **미실행** | CI 로그만 읽었다 |
| multipart 쿼리 파라미터 경로(R-1) | **미실행** | 코드 판독만. 소켓으로 재지 않았다 |
| `parity/` 전체 게이트 · `uv run pytest` 전수 · `ruff` · `mypy` | **미실행** | 이 회차 범위 밖으로 두었다 |
| codex 산출물 대조 | **해당 없음** | 1차라 정상. 3단계 몫 |

---

## 1. 차단 (Critical)

세 건 전부 **② 장치** 갈래다 — 사건을 탐지·차단할 게이트가 무력화된 상태.
**마감은 셋 다 「게이트 28 판정 전(즉시)」**이며, 착수 차단 여부의 판정은 리더에게 넘긴다.

### C-1 (차단 ②) — CI 는 돌고 있고 **빨갛다**. 원장은 「미푸시라서 CI 도달 0」이라 적어 아무도 보지 않게 만들었다

**실측**

- `origin/feat/kotlin-migration-harness` = **`66f008b`** (원격 추적 ref 갱신 시각 2026-08-20 13:54 KST). 즉 **이 리뷰 범위의 기준 리비전까지는 푸시돼 있다.** 미푸시분은 이 세션의 **11커밋**뿐이다.
- draft **PR #1** 이 열려 있다(`feat/kotlin-migration-harness` → main, 2026-08-14 생성). `.github/workflows/ci.yml` 의 트리거는 `push: branches: [main]` **와 `pull_request`** 이므로, 이 브랜치로의 푸시는 **PR 경유로 CI 를 발화시킨다.**
- 최근 실행:

| run | headSha | 생성 | 결론 |
|---|---|---|---|
| 32333596159 | **`66f008b`** | 2026-08-20T04:54:36Z (13:54 KST) | **failure** |
| 32309434868 | `269fe28` | 2026-08-19T22:34:32Z | **failure** |

- run 32333596159 잡별 결론: **`e2e` failure · `kotlin` failure · `frontend` failure** · `quality` success · `llm-lane` cancelled.

**원장 문면과의 대조**

`docs/migration/_workspace/00_progress.md` 세 자리가 같은 말을 한다.

- `:1776` — *"**미푸시 323커밋** — 이 세션 변경분은 CI 에서…"*
- `:1874` — *"실제 GitHub Actions 관측 — **미푸시 323커밋**. 이 세션 변경분의 CI 도달은 **0**이다"*
- `:2017` (이번 회차 L-⑦) — *"실제 GitHub Actions 관측 0 — **미푸시 커밋이 계속 쌓인다**"*

셋 다 사실과 다르다.

1. **「미푸시」가 아니다.** 원격은 `66f008b` 를 들고 있다.
2. **「323」은 미푸시 수가 아니다.** `git rev-list --count origin/main..HEAD` = **335**(당시 323). 그것은 **main 과의 거리**이지 푸시 여부와 무관하다.
3. 그래서 **「CI 도달 0」이라는 결론이 근거를 잃는다.** 실제로는 도달했고, **빨갛다.**

**왜 차단 ② 인가**

이것은 「게이트가 아직 안 돈다」가 아니라 **「게이트가 돌아서 실패했는데, 원장이 그것을 볼 이유를 없앴다」**이다. 원장이 「도달 0」이라 적으면 다음 세션도 보지 않는다 — 실제로 두 회차 연속 빨간 채로 지나갔다. `codex-review` §5 ②의 「그 사건을 탐지·차단하는 게이트가 무력화된 상태」에 그대로 해당한다.

**「Kotlin 코드가 아직 0줄이어도 ② 는 차단으로 올린다」는 규약을 여기서도 적용한다** — 심각도를 「이 회차가 만든 것이 아니다」로 낮추지 않는다. 이 회차는 그 게이트가 재는 선언 목록을 **9건 더 늘렸다**(C-2).

---

### C-2 (차단 ②) — `kotlin` 잡의 「테스트 클래스 실행 대조」가 CI 배선 순서 때문에 **결정론적으로** 실패한다

**CI 실패 전문** (run 32333596159, `kotlin` 잡)

```
E   AssertionError: 선언한 테스트 클래스가 리포트에 **실행된** 기록이 없다:
    ['kr.easydoc.core.CoreDomainsParityTest', 'kr.easydoc.core.CoreModuleBoundaryTest',
     ... 'kr.easydoc.core.privacy.ProvenanceCreationSitesTest', ...]   (kr.easydoc.core.* 24건)
tests/test_kotlin_gate_reach.py:477: AssertionError
FAILED tests/test_kotlin_gate_reach.py::test_리포트가_선언한_클래스를_실제로_실행했다
========================= 1 failed, 95 passed in 3.28s =========================
##[error]Process completed with exit code 1.
```

**기제 — ci.yml 의 스텝 순서다**

| ci.yml | 스텝 | CI 실측 |
|---|---|---|
| `:265-266` | `./gradlew build --no-daemon` | **BUILD SUCCESSFUL · 80 actionable, 78 executed / 2 from cache** |
| `:286-287` | `./gradlew :core:test --tests kr.easydoc.core.privacy.ProvenanceCreationSitesTest` | BUILD SUCCESSFUL · 1 executed |
| `:288-289` | `./gradlew :core:test --tests kr.easydoc.core.privacy.MaskedTextGatewayTest` | BUILD SUCCESSFUL · 1 executed |
| `:307-310` | `KOTLIN_GATE_REACH_REQUIRE_REPORT=1` 로 실행 대조 | **FAILED** |

`:core:test --tests X` 는 `core/build/test-results/test/` 를 **그 한 클래스로 통째로 덮는다.** 그래서 요구 모드(전건 요구)가 core 모듈에서 `MaskedTextGatewayTest` 하나만 발견한다. **결정적 증거**: 미실행 목록에 `ProvenanceCreationSitesTest` **자신이 들어 있다** — 두 번째 스텝이 세 번째 스텝에 덮인 것이다.

**이것은 「선언한 범위 대 실제 도달」의 교과서 형태다**

`tests/test_kotlin_gate_reach.py` 머리말이 **이 성질을 이미 알고 적어 두었다**:

> *"로컬 리포트가 마지막에 돌린 것만 남는 성질(실측: `:core:test --tests X` 한 번이면 그 모듈 리포트가 통째로 그 하나로 바뀐다)이 오경보를 만들지 않는다."*

그런데 그 문장은 그 성질을 **요구 모드 OFF(로컬)** 쪽에만 귀속시켰다. **요구 모드 ON(CI)이 정확히 그 성질에 정면으로 걸린다는 것이 실측이다.** 같은 파일이 CI 배선을 「이 스텝이 `build` **뒤에** 있어야 하는 이유」로 설명하면서, **`--tests` 두 스텝 뒤라는 사실**은 보지 않았다.

**이 회차가 이 자리를 더 나쁘게 만들었다**

`TEST_CLASSES` 에 9건 추가(`DocumentBodyLogLeakReachTest`·`DocumentContractNodeTest`·`DocumentContractTest`·`DocumentDtoLeakTest`·`DocumentEndpointReachTest`·`DocumentEnqueueFailureReachTest`·`ParserNodeRegistryTest`·`SurrogatesTest`·`OwnershipPredicateGuardTest`), `TEST_CLASS_COUNT` 89 → 98, `MIN_TEST_CLASSES` 85 → 91. 그중 `kr.easydoc.core.text.SurrogatesTest`·`kr.easydoc.core.document.TitleRulesTest` 는 core 모듈이라 **다음 CI 실행에서 미실행 목록이 26건이 된다.**

**로컬이 초록인 이유**도 같은 기제다 — 로컬은 요구 모드 OFF 라 「리포트에 실재하는 클래스에 대해서만」 재고, 나는 `build --rerun-tasks` 만 돌려 리포트가 온전했다. **로컬 exit 0 은 이 축의 증거가 아니다.**

**처방(참고, 판단은 리더)**: 실행 대조 스텝을 `--tests` 두 스텝 **앞**으로 옮기거나, `--tests` 스텝을 별도 잡·별도 `--project-cache-dir` 로 격리하거나, 요구 모드 대조가 리포트를 스텝 사이에 보존한 사본에서 읽게 한다.

---

### C-3 (차단 ②) — `e2e` 잡이 「저장 암호화 기동 자기점검」에 막혀 앱을 못 띄운다. 게이트 25 F-2·F-3 판정 이후 CI 배선이 따라오지 않았다

**CI 실패 전문** (run 32333596159, `e2e` 잡)

```
BeanCreationException: Error creating bean with name 'contentCipher' defined in class path
resource [kr/easydoc/infrastructure/crypto/CryptoConfiguration.class]:
  Factory method 'contentCipher' threw exception with message:
  저장 암호화 설정이 기동 자기점검을 통과하지 못했다. 앱을 띄우지 않는다.
...
##[error]Kotlin API 가 60초 안에 /health 200 을 내지 않았다.
##[error]Process completed with exit code 1.
```

`ci.yml:642-652` 의 e2e `env:` 에는 `SPRING_DATASOURCE_*` · `SERVER_PORT` · `E2E_*` 만 있고 **`EASYDOC_ENCRYPTION_KEY_V1` · `EASYDOC_ENCRYPTION_KCV_V1` 이 없다.** `:api:bootJar` 는 성공하고(BUILD SUCCESSFUL · 11 tasks) 기동에서 끊긴다.

**이 회차의 계약 개정과 정면으로 맞물린다.** `dc9ef8e` 는 `ServiceUnavailable` 에서 「저장 암호화 키 미설정 → 문서 API 전체 503」을 내리면서 그 근거를 *"키가 없는데 서비스 중인 상태가 존재하지 않는다 — 기동 자기점검이 앱을 띄우지 않는다"* 로 적었다. **그 근거는 참이고, 참이라는 증거가 바로 이 e2e 실패다.** 계약 판단은 옳고, **그 판단의 귀결을 CI 배선이 한 번도 흡수하지 않았다.**

Phase 4 종료 조건 *"실 PostgreSQL 에서 업로드 → 조회 → 검수 → 3형식 다운로드 → 삭제 전건 통과"* 의 유일한 실행 경로 후보가 이 잡이다. 그 잡이 **기동 단계에서** 죽어 있으면 그 종료 조건은 배선이 있어도 도달 0이다.

---

## 2. 수정 필요

### M-1 — 새로 들어온 두 문면이 **존재하지 않는 강제자 이름**을 지목한다 (구조적 재발 3건째)

| 자리 | 문면 | 저장소 전체 적중 | 실제 강제자 | 도입 |
|---|---|---|---|---|
| `application/.../document/DocumentPorts.kt:263` | *"폐기한 상태 코드가 어느 오퍼레이션에도 되살아나지 않는지는 `RetiredResponseContractTest` 가 계약 파일을 읽어 전역으로 잰다."* | **1건 — 그 문장 자신** | `DocumentContractNodeTest.폐기한 상태 코드가 되살아나지 않는다` | **`454d973`(이번 회차)** |
| `api/src/main/resources/application.yml:48` | *"두 값이 계약 `x-input-limits.max_upload_bytes` 이상인지는 손으로 지키지 않는다 — `MultipartLimitContractTest` 가 계약 파일을 읽어 대조한다."* | **1건 — 그 문장 자신** | `DocumentEndpointReachTest.컨테이너 상한이 계약 상한보다 넉넉하다` | **`454d973`(이번 회차)** |
| `api/src/test/.../support/AuthSliceBeans.kt:208` | *"…동시성은 … `WorkspaceRepositoryTest`(Testcontainers)가 맡는다"* | 1건 | `JdbcWorkspaceRepositoryTest` | `e4be6ff`(선행) |

전수 방법: `backend-kotlin/**` 의 `.kt`·`.kts`·`.yml` 에서 `[A-Z]\w*(Test|Guard|Probe|Valve|Config|Configuration)` 을 뽑아 `class|object|interface` 선언 집합과 차집합. 위 셋 외에 남은 것은 프레임워크 애너테이션(`@SpringBootTest`·`@WebMvcTest`·`@ParameterizedTest`·`@TestConfiguration`), 상위 클래스 이름(`ErrorReportValve`·`ErrorMvcAutoConfiguration`·`CorsConfiguration`), 파일 이름 참조(`ApiStartupWithDatabaseTest.kt` — 그 파일이 `ApiStartupOnEmptyDatabaseTest`·`ApiStartupOnPythonSnapshotTest` 를 담는다)뿐이다. **거짓 지목은 위 셋이 전부다.**

**강제는 실재한다** — 세 자리 모두 다른 이름으로 서 있다. 그래서 사건 축은 열리지 않는다. 문제는 **읽는 사람이 grep 하면 0건이 나오고, 그러면 「강제자가 없다」로 읽힌다**는 것이다. 이 저장소는 그 오독으로 M-3(게이트 27)을 한 번 겪었다.

**리더 판정이 필요하다 — L-③ 판정 1 의 재개봉 조건에 닿는다.**
그 판정문은 *"같은 형태의 거짓 전칭이 **다른 파일에서 한 번 더** 나오면(구조적 재발) 그때 종류째 승격한다"* 였다. 지금 있는 것은 「거짓 **전칭**」이 아니라 「**존재하지 않는 강제자 지목**」이다 — 둘 다 「문면이 실제 도달과 갈린다」의 갈래이지만 같은 종류인지는 리더가 정할 일이다. 같은 종류로 본다면 **세 파일·두 커밋**이므로 조건이 이미 충족된다.

- **분류**: 범위 선언형. 규칙 4 ⑵ 의 은폐형 거부권 대상이 아니다.
- **댈 수 있는 종류**: 「제품 소스·설정 파일의 산문이 강제자를 **이름으로** 지목하는 모든 자리」.
- **처방 후보(탐지형)**: `tests/test_kotlin_gate_reach.py` 가 이미 선언 클래스 정본 집합을 든다. 산문 지목 이름을 그 집합과 대조하는 축을 같은 파일에 붙이면 열거가 생기지 않는다.
- **마감**: C5 이전(리더 판정).

### M-2 — 표 18(TRACE 카나리)의 축이 조용히 좁아졌다: 원장이 이름 붙인 **강제 TRACE** 를 새 장치가 한 번도 지나지 않는다

**원장 조건 18** (`00_progress.md:1405`, 이번 회차에 갱신되지 않았다):
> 강제 TRACE 에서 프레임워크 로거 3종 미도달(`Http11InputBuffer`·`StatementCreatorUtils`·`QueryExecutorImpl`) — 마감 **Phase 4 문서 본문 진입 전**

`00_progress.md:1337` 기록 ④가 그 실측을 남겼다: *"**강제 TRACE 에서** 이름 10 · 이메일 9 · 평문 비번 2 · PHC 2 · 토큰 9 가 찍혔고 유출 로거는 3종이다. **기본·DEBUG 에서는 0**."*

**새 장치가 재는 것**: `DocumentBodyLogLeakReachTest` 는 클래스 KDoc 대로 **제품 기본 로그 구성**(`application.yml` — `root: INFO`)에서 돈다. 그 주장은 참이다 — `backend-kotlin/**/src/test/resources` 에 로깅 override 파일이 **0건**임을 확인했다. 그러나 **그 레벨에서 세 로거는 아무것도 찍지 않으므로 카나리가 그 축을 지날 수 없다.** 즉 조건 18 이 이름 붙인 상태는 여전히 **막는 장치도 재는 장치도 0**이다.

**장치 자체는 좋다** — 로거 이름을 열거하지 않고, 양성 대조(`T18-LOG-CAPTURE-ALIVE`)가 있고, 실패 경로 셋(상한 초과·손상 파일·저장 불가 문자)을 같은 캡처 구간에서 태우고, 축을 넷(본문·제목·비밀번호·토큰)으로 가른다. **지적은 「이 장치가 나쁘다」가 아니라 「이 장치가 조건 18 을 닫지 않는다」이다.**

**부수 — 분류에 판정이 필요하다.** 클래스 KDoc 이 *"그 셋의 레벨을 `application.yml` 에 못박는 처방은 **열거이자 은폐형**이다 … `CLAUDE.md` 규칙 4 ⑵ 가 그 방향을 금한다"* 로 적는다. **레벨 고정은 탐지를 숨기는 것이 아니라 유출을 막는 강제**이므로 은폐형(무시 패턴·억제·면제 조항) 분류가 맞는지 의문이다. 「열거라서 다음 라이브러리를 놓친다」는 논거는 옳지만, 그것은 **강제·표현형의 범위 문제**이지 은폐형의 거부권이 걸리는 자리가 아니다. 실제로 같은 `application.yml` 이 이미 세 로거에 그 강제를 걸어 두고 **자기 범위를 정직하게 좁게 적는다**(*"이 고정이 막는 것은 DEBUG/TRACE 가 기본값을 따라 내려오는 경로 하나다"*). 강제(레벨)와 탐지(카나리)를 **둘 다** 두는 선택지가 규칙 4 로 배제되지 않는다는 판정이 필요하다.

**원장은 아직 이 조건을 닫지 않았다 — 그것은 정직하다.** 다만 `L-⑨` 의 *"음성 대조 11건 전건 실측(… 표 18 카나리 …)"* 은 조건 18 이 닫힌 것으로 읽힐 수 있다. 어느 명제가 측정됐는지를 원장이 갈라 적어야 한다.

- **마감**: 리더가 조건 18 을 닫기 전.

### M-3 — `x-stored-text-domain` 의 「측정 대기 팔」 추적이 `println` 이다. 판정하지 않는다

`DocumentContractNodeTest:180`:

```kotlin
println("P-38 측정 대기 팔(마감 목록): ${domain.pendingArms().map { it.field }.ifEmpty { listOf("없음") }}")
```

명세 P-38 은 *"`applies_to` 의 **측정 상태 표식**을 읽어 … 표식을 안 읽으면 마감이 남았다는 사실이 테스트에서 사라진다"* 를 요구한다. `println` 은 종료 코드를 만들지 않고 CI 로그로 흘러가 아무도 읽지 않는다 — **「대리 지표」가 아니라 아예 지표가 아니다.**

같은 케이스의 살아 있는 단언은 `assertThat(domain.measuredArms()).isNotEmpty()` 하나인데, `measured` 팔이 **둘**(`DocumentTextRequest.text` · `DocumentFileRequest.file에서 추출한 본문`)이라 **어느 하나만 남아도 참**이다. 리더 L-⑧ 판정 1 이 확인한 대로 파일 모드 팔은 오늘 도달 0이므로, 붙여넣기 팔의 표식이 바뀌어도 이 단언은 초록으로 남을 수 있다.

- **마감**: C5(리더가 `measured`/`pending` 어휘를 고치기로 배치한 그 단위).

### M-4 — 소유 술어 핀의 식별자가 「파일 · 동사 · 테이블」뿐이라 **같은 파일 안 동형 두 문장의 맞바꿈**을 못 본다

`OwnershipPredicateGuardTest` 의 두 목록:

- `EXPECTED_STATEMENTS` — `…/JdbcWorkspaceRepository.kt | SELECT [documents]` 가 **두 줄**
- `EXPECTED_UNGUARDED` — 같은 문자열이 **한 줄**

핀 문자열에 문장 내 위치나 지문이 없다. 그래서 그 파일에서 **소유 술어가 있던 SELECT 가 그것을 잃고, 없던 SELECT 가 얻으면** `unguarded` 목록의 값이 글자 그대로 같아 **초록**이다. 판정 순서(파일 경로 정렬 → 파일 내 등장 순)도 그 맞바꿈을 구분하지 못한다.

클래스 KDoc 은 *"목록은 순서 있는 **리스트**라 같은 파일에 같은 모양을 하나 더 넣는 편집도 드러난다"* 로 적는다 — **추가**에는 참이고 **맞바꿈**에는 거짓이다. 그리고 이 갈래가 「막지 못하는 것」 목록에 없다.

**같은 목록에 빠진 것이 하나 더 있다** — `TABLE_REFERENCE` 가 `FROM|JOIN|UPDATE|INTO` 만 본다. PostgreSQL 의 `DELETE … USING t`, `UPDATE t SET … FROM a, b`, 콤마 조인(`FROM a, documents b`)은 **분모에 들어오지 않는다.** 오늘 그런 SQL 은 0건이다(핀 9건이 실측과 일치하고 빌드 초록). 그러나 `DELETE /documents/{id}`(C5)가 바로 `USING` 을 쓰기 좋은 자리다.

- 이 장치의 나머지는 옳다: 분모를 `EncryptedField` 에서 파생 · 빈 분모 실패를 합성·실제 훑기 두 방향으로 · `settings.gradle.kts` 모듈 대조 · `lock` 접두 제외 없음 · 억제 표기 없음. **`privacy-gate` §4.4 의 「정확 열거 핀 ↔ 패턴 면제」 경계선은 지켜졌다.**
- **마감**: C5(리더가 이 탐지기의 첫 실사용으로 지정한 자리).

### M-5 — X2 강제의 도달이 「주 생성자 파라미터의 선언 타입」뿐인데, 그것을 근거로 든 문면은 「하나도 담지 않는다」다

`DocumentDtoLeakTest.parameterTypesOf` = `type.primaryConstructor?.parameters.mapNotNull { it.type.classifier as? KClass<*> }`. 따라서 판정 밖에 남는 것:

- **본문에 선언한 프로퍼티** — `class Foo { val body: PlainBody }`. Jackson 은 생성자가 아니라 프로퍼티를 직렬화한다.
- **제네릭 인자** — `List<PlainBody>`·`ResponseEntity<PlainBody>` 는 classifier 가 `List`/`ResponseEntity` 다.
- **컨트롤러 메서드의 반환 타입·파라미터.**

`DocumentDtos.kt` KDoc 은 *"## 저장·평문 타입을 **하나도 담지 않는다** (X2) … 강제는 `DocumentDtoLeakTest` 가 **타입 부재**로 한다"* 로 적는다. 테스트의 `@DisplayName` 은 *"주 생성자에"* 로 정직하게 좁은데 **KDoc 이 넓다.**

**대조가 의미 있는 지점**: 같은 배치의 두 SQL 탐지기(`OwnershipPredicateGuardTest`·`SqlComments`)는 「막지 못하는 것 (정직하게 적는다)」 절을 각각 갖는다. **X2 탐지기만 그 절이 없다.**

오늘 위반은 0이다(빌드 초록 · `api` 패키지 타입 전수 · 합성 표본 `ForbiddenProbe` 가 `TEST_OUTPUT_MARKERS` 로 분모에서 제외되는 것도 확인).

- **마감**: C6(복호화 조회가 평문을 응답 경로에 처음 데려오는 단위).

### M-6 — `ErrorContractTest` 가 「정본은 계약이다」로 문면을 바꿨는데 계약을 한 줄도 읽지 않는다

`cd127ea` 가 KDoc·실패 메시지·주석을 *"`app/api/errors.py` 의 `_MAPPINGS`"* → *"`contracts/easy-doc-v1.yaml`"* 으로 바꿨다. 그런데 기대 상태 코드는 여전히 `@CsvSource` 리터럴이다. **계약이 상태 코드를 바꿔도 이 테스트는 자기 사본과 대조해 초록이다** — 이 저장소가 `ContractSpec` 을 만든 바로 그 이유(명세 §4 서문·P-* 규약).

같은 파일 주석 *"502 매핑을 되살리면 여기가 빨개진다"* 는 **`LlmProviderException` 계열을 되살릴 때만** 참이다. 다른 도메인 예외에 502 를 새로 매핑하고 프로브를 더하지 않으면 아무 데서도 걸리지 않는다.

계약 **쪽** 되살림은 `DocumentContractNodeTest` P-39 가 전역으로 잡는다(확인: `ContractSpec.operations()` 가 `paths` 전건 × HTTP 메서드 8종을 편다 — 도달 실재). **구현 쪽 전역 단언은 없다.**

- **마감**: 제안 C5. 심각도 확정은 리더.

### M-7 — 원장 L-⑦(미실행)과 L-⑨(실측)이 **같은 음성 대조 번호**를 서로 다르게 적는다

- `L-⑦`: *"계약 음성 대조 **N-31~N-34 는 전부 설계이고 실측이 아니다**(계약 레인 자기 신고)"*
- `L-⑨`: *"음성 대조 **11건 전건 실측**(N-23·N-25·N-28·**N-31·N-32·N-33**·R-3·R-5·표 18 카나리·N-R2·N-R4)"*

N-31·N-32·N-33 이 두 목록에 함께 있다. 계약 레인 시점(`dc9ef8e`)과 구현 레인 시점(`454d973`)이 다르다고 읽으면 모순은 아니지만, **L-⑦ 은 「이 세션의 미실행」 목록이고 같은 절 안에서 L-⑨ 가 그것을 뒤집는다.** 시점을 명시하지 않으면 다음 세션이 어느 쪽을 믿을지 알 수 없다.

**그리고 N-34 는 L-⑨ 의 11건에 없다.** 다만 그 축의 실질은 `TitleRulesTest:112-116` 이 자기 실측으로 닫았다 — *"`sanitizeName` 에서 `stripUnpairedSurrogates` 호출만 걷어내고 `:core:test` 를 돌렸더니 **exit 0** 이었다"* 를 발견해 다섯 케이스를 신설했다. **이 자리는 이번 회차에서 가장 잘한 도달 대조다** — 「판정 함수를 직접 재는 테스트는 호출자가 그것을 *쓰는지* 를 재지 않는다」를 실행으로 잡아냈다. N-34 가 목록에 없는 것이 미실행인지 다른 이름으로 실행된 것인지만 정리하면 된다.

- **마감**: 리더의 게이트 28 후속 원장 기재.

### M-8 — `frontend` 잡이 CI 에서 빨갛다 (제품 결함 1건)

run 32333596159 `frontend`: `src/workspace/WorkspaceProvider.test.tsx > 작업 공간 상태 > 기억해 둔 선택이 목록에 없으면 기본 작업 공간으로 되돌아간다`
`expect(element).toHaveTextContent()` — 기대 `w1`, 실제 `없음`. `1 failed | 59 passed`.

이 범위의 변경분이 아니고 Kotlin 축도 아니지만, **CI 가 빨간 세 잡 중 하나이므로 여기 적는다.** 코드로 진단하지 않았다(**미실행**).

- **마감**: 리더 지정.

---

## 3. 권고

**R-1 — multipart 모드가 `title`·`workspace_id` 를 쿼리 문자열에서도 받는다.**
`DocumentController.createFromFile` 이 `request.getParameter(TITLE_PART)`·`getParameter(WORKSPACE_ID_PART)` 를 쓴다. 서블릿 `getParameter` 는 **쿼리 파라미터와 폼 파라미터를 합친다.** 계약은 두 값을 `multipart/form-data` **파트**로만 선언한다(`DocumentFileRequest.properties`). 즉 `POST /documents?title=X` 가 선언되지 않은 입력 통로가 된다. 어느 방향의 테스트도 없다. **소켓으로 확인하지 않았다 — 코드 판독이다.**

**R-2 — `NO_WORKSPACE_MESSAGE` KDoc 의 계약 인용이 어긋난다.**
값은 `"요청을 처리하지 못했습니다"`(= `InternalError` 의 `unmapped_domain` 예시)인데 KDoc 은 *"`InternalError` 의 `storage` 갈래 문구를 쓴다"* 로 적는다. `storage` 예시는 `"저장된 변환 결과를 읽을 수 없습니다"` 다. 나가는 바이트는 `InternalError` description 셋째 줄(*"`StorageError`(코드 버그) → 저장소가 만든 고정 문자열"*)에 부합하므로 **계약 위반은 아니다.** 이번 회차가 만든 줄은 아니다(선행 커밋).

**R-3 — `x-retired-responses` 대조가 `paths` 만 본다.**
계약 머리말은 *"여기 적힌 상태 코드는 **어느 오퍼레이션의 `responses`에도** 나타나서는 안 된다"* 이고 `DocumentContractNodeTest` 가 그것을 잰다. 「참조 없는 고아 컴포넌트」는 재지 않는다 — 이번에는 자체 스크립트로 한 번 확인했지만(L-⑦) 그것은 **회귀가 아니다.** 오늘 `BadGateway` 가 삭제돼 대상이 0이므로 사건 축은 닫혀 있다.

**R-4 — `ParserNodeRegistryTest` 의 총수 상수 셋(39 · 1 · 40)이 명세 문서와 양쪽 다 수기다.**
명세 §4 서문도 같은 수를 적는다. 두 자리가 함께 갱신되므로 함께 틀릴 수 있다. 다만 정의 행은 실제 파싱이고 「미등재 라벨 0 · 번호 연속」 축은 살아 있다.

**R-5 — 로컬 `build --rerun-tasks` 79 vs CI(`66f008b`) 80 actionable.**
차이는 캐시/`UP-TO-DATE` 집계이고 리더의 L-⑤ 「80」(`5038968` 기준)과도 정합한다. **리더의 79/79 주장은 내 독립 재실행과 바이트 일치한다.** 다만 이 수치를 「재측정 증거」로 계속 인용할 것이라면 무엇을 세는 값인지 원장이 한 번 적어 두는 편이 낫다.

---

## 4. 도달 범위 점검 (다섯 축을 가로지르는 필수 구획)

> 이 회차의 지시가 「선언과 도달이 갈린 자리 다섯 번째를 찾아 달라」였다. **찾았고, 하나가 아니라 넷이다** — C-1(원장의 CI 도달 선언) · C-2(요구 모드의 자기 전제) · M-1(강제자 이름 3건) · M-2(표 18 축 축소). 그중 **C-1·C-2 가 가장 무겁다** — 둘 다 「이 게이트가 지금 어디서 도는가」를 잘못 알고 있었다.

| 점검 항목 | 결과 |
|---|---|
| 「전역·모든 응답·항상」이라 선언한 강제 수단이 **닿지 않는 경로** | **지적 있음** — M-5(X2: 「하나도 담지 않는다」 vs 주 생성자만) · M-4(핀 식별자의 해상도) · M-1(지목한 강제자 부재) |
| 그 게이트가 **지금 어디서 도는가** — 로컬인가 CI 인가 아무 데도 아닌가 | **지적 있음, 차단** — **C-1**. 원장이 「CI 도달 0」이라 적은 게이트가 실제로는 돌고 **빨갛다.** 「도달 0을 특히 의심한다」의 반대 방향 실패 — **도달 0이라는 선언 자체가 틀렸다** |
| 측정이 **대리 경로**에서 이뤄지지 않았는가 | **지적 있음, 차단** — **C-2**. 로컬 `build --rerun-tasks` 초록은 요구 모드 OFF 경로이고, CI 의 요구 모드 ON 경로는 실패한다. **로컬 exit 0 을 CI 초록의 대리로 읽으면 안 된다** |
| 검사의 **기준이 검사 대상 자신에게서 나오지 않는가** | **지적 있음** — M-6(`ErrorContractTest` 가 「정본은 계약」이라 적고 리터럴과 대조). 반대로 `ContractSpec` 경유 케이스(DC-1~DC-25 대부분)는 **계약 파일에서 읽는다 — 이 축에서 이번 배치는 대체로 좋다.** `UploadFixtures.docxOfExactSize` 는 자기 산출물 크기를 `check` 로 재확인해 자기 참조를 끊는다 |
| 판정이 **대리 지표**로 이뤄지지 않는가 | **지적 있음** — M-3(`println` 을 마감 추적으로) · C-1(「미푸시 커밋 수」를 「CI 도달」의 대리로) |
| 규칙·패턴의 **범위가 근거보다 넓지 않은가** (은폐형) | **검토함 — 지적 없음(오히려 반대 방향에 M-2 판정 필요)**. 두 SQL 가드는 `lock` 접두 제외도 억제 표기도 쓰지 않고 정확 열거 핀을 썼다. `x-retired-responses` 는 삭제 대신 **이름을 남기는** 형태라 은폐가 아니다. M-2 는 「은폐형이라며 강제를 배제한 것이 맞는가」라는 **반대 방향** 의문이다 |
| **음성 대조**가 붙어 있는가 — 떼면 무엇이 깨지는가 | **대체로 있음.** `5038968` 이 **제품 소스 변조**로 N-1b·N-2·N-4 를 실행하고 sha256 복원 대조까지 남겼다(`git checkout` 만 사용, `cp`·`git stash` 미사용 — 규칙 5 준수). `TitleRulesTest` 는 자기 호출 제거로 exit 0 을 발견해 다섯 케이스를 신설했다. `DocumentDtoLeakTest` 는 합성 표본으로 판정 함수의 지목을 확인한다. **빈 자리**: M-3(`println`) · M-7(N-34 의 실행 여부) |
| 판정하는 코드가 **자기 자신을 검사 대상에 넣었는가** | **검토함 — 지적 없음.** `OwnershipPredicateGuardTest` 의 probe 는 문자열 조립 SQL 이라 실제 분모를 오염시키지 않고, 그 사실을 KDoc 이 적는다. `ProductClasses` 는 `TEST_OUTPUT_MARKERS` 로 자기 테스트 산출물을 뺀다. 세 새 테스트는 ktlint·detekt·`test_kotlin_gate_reach` 선언 목록 안에 들어 있다(빌드 초록으로 확인) |

---

## 5. 다섯 축별 정리

### 5.1 계약 준수

- **검사 순서 (리더 지목 1)** — **검토함, 지적 없음.** 계약 `POST /documents` description 은 *"파일 크기(413) → 추출(422) → 본문 길이(422) → 작업 공간 소유권(404) → **저장 정의역(422)** → **저장과 큐 등록(한 트랜잭션)** → 커밋"*. 코드는 `DocumentController.readBounded` → `DocumentService.createFromFile`(`bytes.size > MAX_UPLOAD_BYTES` → `extractor.extract` → `extracted.text.isBlank()`) → `store`(`charCountOf` 상한 → `transaction.inTransaction { resolveWorkspaceId → resolveTitle → PlainBody(text) → cipher.encrypt → documents.insert → conversions.insertPending → queue.enqueue }`). **문면과 같다.** 저장 정의역이 트랜잭션 **안**이고 소유권 **뒤**라는 계약의 명시 조항까지 일치한다.
- **502 철거** — **검토함, 지적 없음.** 계약 `paths` 에 `'502'` 선언 0(파일 직접 확인) · `components/responses/BadGateway` 삭제 · `QueueUnavailableException` 삭제 · `ErrorProbeController` 의 `queue` 항목 삭제 · `mappingFor` 에 `BAD_GATEWAY` 없음. 남은 502 문자열은 전부 산문·폐기 기록이다.
- **503 무대 교체** — **검토함, 지적 없음.** `AuthUnavailableContractTest.DC-19` 가 짧은 서명 키 구성에서 `POST /documents` 503 을 실제 소켓으로 재고 401 이 아님을 단언하며, 같은 컨텍스트에서 `/health` 200 도 함께 재 「컨텍스트 조립 실패로 통과」를 막는다. `DocumentEnqueueFailureReachTest.DC-18` 이 반대 방향(500 이고 502·503 아님)을 실 DB 롤백까지 잰다. **두 팔이 서로에 대한 부정 단언을 갖는다.**
- **snake_case·응답 키 집합** — **검토함, 지적 없음.** 필드마다 `@JsonProperty` 명시 + `DocumentContractNodeTest` P-33/P-36 이 계약 `required`·`properties` 와 **정확 일치**로 대조.
- **`Location`·no-store** — **검토함, 지적 없음.** DC-2 가 경로 템플릿을 계약에서 읽어 조립하고, DC-1·DC-3 이 전역 헤더를 **부착 개수까지** 단언한다. `POST /documents` 가 `x-private-response-headers.applies_to` 10곳에 없는 것은 그 목록의 선정 규칙(「개인정보·자격증명이 실리는 응답」)과 정합하다 — 202 본문은 식별자·상태·문자 수뿐이다. `DocumentController` KDoc 의 그 설명은 **참이다.**
- **415 미선언** — 리더 L-⑧ 판정 3 이 이미 계약 레인·C5 로 배치했다. 여기서 새로 올리지 않는다.
- **지적**: M-6 · R-1 · R-3.

### 5.2 parity 위험

*기준은 「Python 과 같은 값」이 아니라 「요구 충족」이다(2026-08-12 전환).*

- **서로게이트 두 처분** — **검토함, 지적 없음.** 판정은 `core/text/Surrogates.kt` **한 곳**이고 처분만 둘이다(본문 거절 / 제목 정제). 두 공개 함수를 합치지 않은 이유를 KDoc 이 음성 대조 축(N-34·R-3)으로 설명하고, `SurrogatesTest` 가 두 함수의 **일관성**(`hasUnpaired == (strip != original)`)까지 잰다. 짝을 이룬 쌍(이모지)이 남는 것도 단언한다 — 「정제가 검열이 되는」 방향을 막았다.
- **`takeCodePoints`** — 코드 단위가 아니라 코드 포인트로 자른다. 자르기가 서로게이트 쌍을 끊어 **우리가 손상을 만드는** 갈래를 닫았다. 정제가 자르기보다 먼저라는 순서도 KDoc 과 코드가 일치한다.
- **PDF 유입 경로** — 구현 레인이 fixture(`SurrogatePdf`)를 만들어 **PDFBox 3.0.5 가 `U+FFFD` 로 치환한다**는 것을 실측하고, 그 사실을 회귀로 고정했다(`PdfExtractorTest`). 판올림이 치환을 그만두면 빨개진다. **도달을 탐지형으로 고정한 옳은 형태다.**
- **DOCTYPE** — `SUPPORT_DTD=false` 가 내부 서브셋 없는 DOCTYPE 을 거절하지 않는다는 것을 `HwpxExtractorTest` 가 케이스로 못박았다. 「이 모양으로 XXE 케이스를 쓰면 아무것도 재지 못한다」를 **공허 통과 방지 회귀**로 남긴 것이 좋다.
- **길이 축** — `charCountOf` = `codePointCount`, 원시 값. DC-9·DC-10·DC-11 이 경계 양쪽과 축(`measured_on`)을 **계약에서 읽어** 잰다. DC-11 이 처음에 「422」를 못박아 N-25 에서 깨지지 않았던 것을 구현 레인이 스스로 발견해 고친 것도 확인했다(L-⑨).
- **지적**: 없음(이 축).

### 5.3 보안 불변식

*판정 우선권은 `privacy-gate` 에 있다. 여기서는 이번 변경이 그 감사의 어느 항목에 닿는지만 지목한다.*

- **I-7 round-trip 정의역** — `PlainBody` 가 생성 시점에 짝 없는 서로게이트를 거부하므로 round-trip 불변식이 *"`PlainBody` 로 만들 수 있는 모든 값"* 에 대해 **전건으로** 참이 된다. 계약 `x-stored-text-domain` 이 그 성질에 오라클을 붙였다. **닫혔다.** 남은 것은 M-3(측정 대기 팔 추적).
- **I-4 평문 로그·저장** — `DocumentBodyLogLeakReachTest` 가 성공·실패 다섯 경로를 한 캡처 구간에서 태우고 양성 대조를 갖는다. `SensitiveToStringReachTest` 의 분모가 48 → 50 으로 늘어 **`text`·`title` 토큰이 처음으로 실제 대상 위에 선다**(그전에는 대상 0건인 채 「Phase 4 를 겨냥해 미리 둔다」로 적혀 있었다). **도달 0이던 선언이 닫힌 자리다.** 남은 것은 M-2(TRACE 축).
- **I-5 소유권 은닉** — `DocumentEndpointReachTest` DC-16·DC-17 이 실 DB 두 사용자로 404 와 **바이트 동일성**(본문·헤더 이름 집합)을 잰다. `OwnershipPredicateGuardTest` 가 소유 술어 없는 제품 SQL 을 전수로 붙든다.
- **M-3 해제 조건(privacy-gate §0)** — **⒝·⒞ 는 닫혔다고 본다.** ⒞: `DocumentPorts` 의 전칭 문장이 실제 도달로 고쳐졌고 새 전칭 낱말이 없으며, 「강제자가 없다」는 사실을 명시했다. ⒝: `5038968` 이 **제품 소스 변조**로 N-1b·N-2·N-4 를 실행했고(감사가 요구한 「제품 코드에서 성립」) 복원 sha256 을 남겼다. **⒜ 는 C6 몫이라 열려 있는 것이 맞다.** 다만 M-4 가 그 탐지기의 해상도 한 갈래를 남긴다.
- **AEAD 결속** — 이 회차는 `EncryptedField`·`ContentCipher` 규약을 건드리지 않았다. `StubContentCipher` 가 XOR 라도 **평문과 바이트가 같아지는 상태를 막는** 이유를 적어 둔 것은 옳다(그 대역을 실측 근거로 오독하는 것을 막는다).
- **`InvalidInputException : EasyDocException : RuntimeException`** — 확인함. Spring 기본 롤백 규칙에 걸리므로 트랜잭션 안의 정의역 거절이 롤백된다. DC-18 이 실제 롤백을 실 DB 로 잰다.
- **지적**: M-2(축 축소, 판정 필요) · M-3 · M-4 · M-5. **`privacy-gate` 판정이 갈리면 그쪽을 따른다.**

### 5.4 Kotlin/Spring 관용성

- **모듈 경계** — **검토함, 지적 없음.** 새로 들어온 `core/text/Surrogates.kt`·`core/crypto/StoredContent.kt`·`core/document/TitleRules.kt` 에 Spring·JDBC import 0. `application/document/*` 에 `infrastructure` import 0. LLM SDK 타입 유출 없음.
- **트랜잭션 경계** — 문서·변환·작업 세 행이 `transaction.inTransaction {}` 한 블록에서 확정된다(계획 §4.4). 암호화도 그 안이라 실패 시 평문 중간 상태가 남지 않는다.
- **`@Profile("!migrate")`** — 면제가 아니라 의존성으로 설명하고, 부정 목록을 쓴 이유(새 프로필의 기본값)를 적었다. `MigrateProfileWithoutEncryptionKeyTest` 가 그 경계를 붙든다.
- **두 `consumes` 매핑** — 대소문자 판정을 손으로 하지 않고 프레임워크 조건에 맡긴 판단이 옳다(두 번째 판정을 만들지 않는다). DC-5 가 `Multipart/Form-Data` 로 실측한다.
- **detekt 신호 처리** — `TooManyFunctions`·`LongParameterList` 를 **임계값 상향이 아니라 구조 변경**으로 받았고(핸들러 합침 · `DocumentStorage` 묶음), 그 과정에서 로그 줄이 줄어 스캐너 테스트에 걸린 것을 되살린 경위까지 원장에 있다. **좋다.**
- **`readBounded`** — 상한 +1 바이트만 읽어 거절할 파일에 힙을 쓰지 않는다. 경계 판정은 서비스가 진다.
- **지적**: 없음(이 축).

### 5.5 테스트 적정성

- **보장의 재배치** — DC-1~DC-25 가 계층별로 배치돼 있고(C-M `@WebMvcTest` / C-R 실 소켓 / C-I Testcontainers / C-P 구성 주입), 업로드·401·컨테이너 거절을 MockMvc 로 재지 않는다는 §5-1 규율이 지켜졌다. 25개 케이스 전부 `@DisplayName` 에 ID 를 달고 실재한다(전수 확인).
- **실패 경로** — 성공 경로만 있는 모듈이 없다. `DocumentEnqueueFailureReachTest` 는 실제 어댑터가 실제 PostgreSQL 오류를 내게 하려고 **전용 DB 의 테이블을 지운다** — 대역이 아니라 본류를 잰다.
- **분모 방어** — 새 탐지기 셋이 전부 「빈 분모는 통과가 아니다」를 갖는다(`requireNonEmpty` · `declared.isNotEmpty()` · `retired.isNotEmpty()` · `apiTypes.isNotEmpty()` · `definitions.isNotEmpty()`).
- **판정 불가를 통과로 세지 않는다** — `DocumentDtoLeakTest` 가 「주 생성자를 읽지 못한 타입」을 별도로 실패시킨다. `OwnershipPredicateGuardTest` 가 「SQL 동사를 못 찾은 문장」에서 끊는다. **이 배치의 좋은 습관이다.**
- **지적**: **C-2(가장 무겁다 — 이 축의 CI 강제자가 빨갛다)** · M-3 · M-5 · M-6.

---

## 6. Phase 종료 조건 대비 현황 (§5 Phase 4 · §6)

| 종료 조건 | 이 회차 이후 | 막는 것 |
|---|---|---|
| JSON/multipart 업로드와 제한 처리 | **상당히 진전.** DC-1~DC-25 전건 실재, 계약 노드 P-24~P-40 배선, 검사 순서 일치 | **C-2**(그 테스트들이 CI 에서 「실행됐다」로 세어지지 않는다) · M-6 |
| 암호화 저장·복호화 조회 | 저장 경로 + **저장 정의역**이 닫혔다. 복호화 조회는 C6 | M-5(X2 도달) — C6 마감 |
| 평문이 DB·로그에 없다 | 로그 축이 **처음으로 문서 본문 위에 섰다**(카나리 + `SensitiveToStringReachTest` 50) | **M-2**(조건 18 의 TRACE 축 미도달) |
| 문서 목록·삭제, 변환 조회·검수 저장 | 미착수(C4~C7) | M-4(C5 의 `DELETE` 가 이 탐지기의 첫 실사용) · `privacy-gate` ⒜(C6) |
| 실 PostgreSQL 전 흐름 통과 | 미착수 | **C-3**(그 흐름의 유일한 실행 경로 후보인 `e2e` 잡이 기동에서 죽는다) |
| §6 게이트 — 각 테스트의 보장이 어디로 갔는지 추적 | 추적표는 명세(§4·§6)와 `test_kotlin_gate_reach.py` 가 든다 | **C-2** — 그 추적표의 CI 강제자가 실패 중 |

**1차 산출물만으로 Phase 종료 조건 충족을 보고하지 않는다.** 판정 근거의 정본은 3단계 `..._cross.md` 다.

---

## 7. 리더에게 올리는 판정 요청

| # | 항목 | 요청 |
|---|---|---|
| J-1 | **C-1·C-2·C-3** | 게이트 28 의 착수 차단 여부. 셋 다 마감은 「즉시」로 올린다. 특히 **원장의 「미푸시 → CI 도달 0」 문면 정정**은 다음 세션이 같은 오독을 반복하지 않게 하는 최소 조치다 |
| J-2 | **M-1 이 L-③ 판정 1 의 재개봉 조건에 해당하는가** | 「거짓 전칭」과 「존재하지 않는 강제자 지목」이 **같은 종류**인가. 같다면 세 파일·두 커밋으로 구조적 재발이 성립하고 종류째 승격 대상이 된다 |
| J-3 | **M-2 의 분류** | 「로거 레벨 고정 = 은폐형」이 맞는가. 맞지 않다면 **강제(레벨) + 탐지(카나리)를 둘 다** 두는 선택지가 규칙 4 로 배제되지 않는다 |
| J-4 | **M-6 의 심각도** | `ErrorContractTest` 를 계약에서 읽게 고치는 것이 C5 마감인가, 더 이른가 |
| J-5 | **M-7 의 원장 정합** | L-⑦ 과 L-⑨ 의 N-31~N-33 시점 표기, N-34 의 실행 여부 |

---

## 8. 미실행·확인 불가

- **codex 리뷰 대조** — 1차라 수행하지 않았다. **실패로 기록하지 않는다.** 교차 종합은 3단계(`04_documents-c3_cross.md`).
- **`frontend` 실패의 코드 진단**(M-8) · **multipart 쿼리 파라미터 경로**(R-1) · **`llm-lane` cancelled 사유** · **e2e 잡의 Playwright 이후 스텝**.
- **`parity/` 전체 게이트 · `uv run pytest` 전수 · `ruff` · `mypy`** — 이 회차에서 돌리지 않았다.
- **compose 재스모크** — L-⑦ 이 미실행으로 적은 그대로다. C-3 이 그 자리와 같은 종류의 배선 문제를 CI 쪽에서 드러냈으므로 함께 보는 편이 낫다.
- **`x-stored-text-domain` 의 `edited_text` 팔(`pending`)** — `PUT /conversions/{id}` 가 없어 잴 표면이 없다. 「할 수 있었는데 안 했다」가 아니다.
- **`privacy-gate` 재감사** — 이 회차의 보안 축 지적(M-2·M-3·M-4·M-5)은 **판정 요청이지 판정이 아니다.** 갈리면 `privacy-gate` 판정을 따른다.
