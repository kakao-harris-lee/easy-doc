# `xx_harness-r2` — Claude 독립 리뷰 (1차. 교차 종합 아님)

**회차 어간**: `xx_harness-r2` (리더 지정값 그대로)
**작성**: `migration-reviewer` 1차 호출. **codex 산출물을 읽지 않았다** — 1차는 독립 판단이 목적이므로 부재를 실패로 기록하지 않고 재요청도 하지 않는다. 교차 대조표는 2차(`xx_harness-r2_cross.md`)에서 만든다.

## 1. 리뷰 범위와 참조 절

**대상 20 커밋**: `ed3df31` `7efc7c4` `3ba7e04` `e7faccc` `87457e6` `73f9f4c` `0fda906` `80f6761` `f3398bc` `0265138` `d7cecfe` `7966ff5` `aff00c9` `a28ee47` `ee92455` `62b76d3` `df4d60c` `0272d3b` `95979f3` `329a964`.

**HEAD 가 리뷰 중 세 번 움직였다 — 기록해 둔다.** 리더 브리프 `01d3c48` → worktree 생성 시점 `aff6204` → 레인 A 변이 실행 시점 및 종료 시점 `e1be8df`. 아래 실측은 `aff6204`(레인 B 축) 및 `e1be8df`(레인 A 축)에서 났다. `aff6204`·`e1be8df` 는 리뷰 대상 20 커밋 밖의 문서 커밋이지만, **`aff6204` 가 장부 `리뷰할 회차` 칸 20행을 바꿨으므로 X-11 관련 판정의 전제가 브리프 시점과 다르다**(§4 R-5).

**참조**: `.claude/skills/kotlin-migration/SKILL.md` 「선언한 범위와 실제 도달을 대조한다」 규칙 1~8 · 「어간 문법」 절 · `{scope}` 정본 표 / `docs/migration/_workspace/04_kotlin-implementer_harness-unit-laneA-plan.md` §0·§3·§4·§5·§6 / `docs/migration/_workspace/xx_harness-unit-laneB-plan.md` §2·§4·§5·§6 / 계획 §5 Phase 7 즉시 중단 기준 / `codex-review` §5 심각도 척도.

**방법**: 저장소 밖 일회용 `git worktree add --detach` 두 개(`wt-r2` 직접, `wt-laneA` 위임). `cp` 복원·`git stash` 없음. 복원은 worktree 안 `git checkout --` 만. 두 worktree 모두 `git worktree remove --force` 로 제거했고 **본 저장소는 무변조**다(`git status` 로 확인 — 세션 시작 시점의 기존 수정 4건 외 변화 없음).

---

## 2. 다섯 축별 지적

### 2.1 계약 준수 — 검토함, 지적 없음

축③(제품 코드)은 `ee92455` 하나다. 실측:

- `DocumentController.kt` 변경은 **주석 한 줄 추가**뿐이다(`// 이 순서를 \`DocumentEndpointReachTest\` 가 잰다.`). 상태 코드·헤더·오류 본문·필드명에 닿는 코드 변경 0.
- `FrameworkErrorContractTest.kt` 변경은 **KDoc 4줄 삭제**뿐이다. 단언·`@DisplayName` 불변.
- `git log --oneline cbf6e8d..HEAD -- contracts/easy-doc-v1.yaml` → **빈 출력**. 리뷰 범위 전체에서 계약 파일 무변경.

계약 위반 없음. 다만 §2.2 M-3 이 계약 편집 **탐지**의 지목 결함을 지적한다.

### 2.2 parity 위험 / 게이트 무력화 — 차단 2 · 수정 필요 3

이 회차는 20 커밋 전부가 축③(게이트·탐지기 자신)이므로 parity 축은 「탐지기가 자기 선언만큼 도달하는가」로 읽는다.

#### [차단 ②장치] C-1 — X-2a·X-2b·X-2c 는 오늘 커버리지 4행 중 **1행에서만** 게이트를 빨갛게 만든다

`a28ee47`·`df4d60c` 가 세운 산출물 칸 판정(경로 규약·역할 구성·시점 결속)은 **행을 무효로 만들 뿐**이고, 무효가 된 행이 지탱하던 커밋이 없으면 게이트 판정은 바뀌지 않는다. 실측(`wt-r2`, `aff6204`):

| 커버리지 행 | rev-list | 판정 대상∩ | **이 행만이 지탱하는 커밋** |
|---|---|---|---|
| `5b226b4..cbf6e8d` (`xx_harness`) | 2 | 1 | **0** |
| `1fb5200..0075743` (`04_documents-c6`) | 4 | 0 | **0** |
| `1d42c5d..318bd36` (`04_documents-c6`) | 1 | 1 | **1** (`318bd363e`) |
| `318bd36..b4646ee` (`04_documents-c6r2`) | 7 | 7 | **0** |

무하중 행(`xx_harness`)에 네 가지 위조를 넣고 `uv run pytest -q tests/test_review_coverage_reach.py`:

| 변이 | 결과 |
|---|---|
| M-1 없는 산출물 경로(`…_ghost-reviewer.md`) | **4 passed**, exit 0 |
| M-2 다른 회차 어간의 실재 산출물(`04_documents-c6_*`) | **4 passed**, exit 0 |
| M-3 codex 산출물 없는 행(`{migration-reviewer,cross}`) | **4 passed**, exit 0 |
| M-4 과거 산출물로 미래 범위 승인(`01d3c48..aff6204` 행 추가) | **4 passed**, exit 0 |

하중 행(`1d42c5d..318bd36`)에 같은 위조를 넣으면 넷 다 exit 1 로 잡힌다(A1~A4). 그런데 **지목되는 것은 위조된 행이 아니다**:

```
E   AssertionError: 리뷰도 장부도 없는 커밋 1 건 (기준 `0d632f9` 이후):
E       318bd363e  feat(kotlin): GET /conversions/{id} — 상태·결과 조회 (C6)
FAILED tests/test_review_coverage_reach.py::test_모든_비면제_커밋이_리뷰되거나_장부에_적혀_있다
```

메시지는 위조된 산출물 칸도, 어느 행이 무효가 됐는지도 말하지 않는다. **읽는 사람에게 제시되는 자연스러운 조치는 「`318bd36` 을 이연 장부에 적는다」이고, 그렇게 하면 위조된 커버리지 행은 영구히 보이지 않게 된다.** 레인 A 계획 §4 가 스스로 세운 기준 — *"판정 기준은 「무언가 빨개졌는가」가 아니라 「겨눈 장치가 그 자리를 지목했는가」"* — 을 이 세 처방이 충족하지 못한다.

**저자의 음성 대조가 이것을 재지 못한 이유**: `a28ee47` 커밋 메시지는 *"처방 전 유효 범위 4건(무반응) / 처방 후 3건(그 행 탈락)"*, `df4d60c` 는 *"유효 범위 건수"* 로 적었다. 둘 다 **`_coverage_ranges()` 의 반환 길이**를 잰 것이고 게이트의 판정(exit code)을 재지 않았다. 규칙 2 가 금지하는 대리 측정이다 — 정직하게 적혀 있지만, 그 지표로는 「행이 탈락해도 게이트는 초록」이 보이지 않는다. 대조군으로 `aff00c9`(X-3a)·`d7cecfe`(X-3b)는 `exit 1` 과 지목 행을 적었다.

- **근거 위치**: `tests/test_review_coverage_reach.py:283-303`(`_coverage_ranges` 의 `continue` 경로) · `:519-540`
- **관련 종료 조건**: §6 리뷰 게이트 · SKILL.md 규칙 2·3
- **마감**: **이 회차**. 이 회차의 커버리지 행을 적는 순간이 이 판정의 첫 실사용이다.

#### [차단 ②장치] C-2 — X-3b 의 분모가 무방비다: 상태 칸 오타 하나로 공허하게 초록이 된다

`test_리뷰된_대기_행은_닫힘_칸이_적혀_있다` 의 빈 분모 방어는 `assert rows` 하나이고, 그 `rows` 는 **장부 표 전체**다. 이 검사가 실제로 판정하는 부분집합(= 리뷰된 `대기` 행, 오늘 4건)이 0 이 되어도 통과한다.

실측(D1) — 리뷰된 `대기` 4행의 `상태` 칸만 `**대기**` → `**대기**(미리뷰)` 로 바꿨다:

```
평시:        4 passed, 1 warning  (exit 0)   ← X-3b 공허 초록
출하 모드:   2 failed              ← 실패는 나머지 20행 때문이고, 훼손된 4행을 지목하지 않는다
```

`_ledger_rows` 가 `state not in _LEDGER_STATES` 인 행을 **조용히 `continue`** 하기 때문이다. 그 4 커밋은 커버리지 범위 `318bd36..b4646ee` 안에 있어 `test_모든_비면제…` 도 초록으로 남는다. 장부는 사람이 손으로 쓰는 마크다운이고 `대기`·`**대기**`·`대기(미리뷰)` 는 모두 자연스러운 인간 변형이다 — 악의 없이도 재현된다.

같은 기제의 두 번째 실측(B3): `리뷰할 회차`↔`닫힘` 두 열을 맞바꾸면 **출하 모드에서 `test_출하_모드에서는_미상환_대기가_0_이어야_한다` 가 통과**한다(다른 검사가 다른 사유로 실패할 뿐). `test_원장에_…` 의 열 헤더 단언 목록 `("커밋", "상태", "리뷰할 회차")` 에 **`닫힘` 이 빠져 있어** 열 구성 변경이 잡히지 않는다.

- **근거 위치**: `tests/test_review_coverage_reach.py:377-393`(조용한 `continue`) · `:565-573`(`assert rows`) · `:500`(헤더 목록에 `닫힘` 없음) · `:604-609`
- **관련 종료 조건**: §6 리뷰 게이트 · SKILL.md 규칙 4 ⑶
- **마감**: **이 회차**. X-3b 는 이 회차가 20행의 `닫힘` 을 채우는 시점에 처음 쓰인다.

#### [수정 필요] M-1 — 레인 A 계획 §5 의 검증 규약이 **존재하지 않는 환경변수**를 지정한다

계획 §5: *"`KOTLIN_GATE_REACH_REQUIRE_FRESH_REPORTS=1` 요구 모드 게이트"*. 실재하는 이름은 `KOTLIN_GATE_REACH_REQUIRE_REPORT` 다(`tests/test_kotlin_gate_reach.py:170`). 없는 변수를 설정하는 것은 **조용한 무동작**이다. 실측:

```
KOTLIN_GATE_REACH_REQUIRE_FRESH_REPORTS=1 …  → 304 passed, 5 warnings, exit 0
KOTLIN_GATE_REACH_REQUIRE_REPORT=1 …         → (본 저장소) 303 passed, 1 failed
```

5 warnings 는 판정되지 않은 축의 이름을 그대로 부른다(실행 대조 · 바닥 실행 대조 · 신선도 축 · 건너뜀 대조 · 역방향 대조). 즉 **§5 문면대로 검증하면 요구 모드 5축이 한 번도 판정되지 않은 채 「요구 모드 게이트 통과」가 성립한다.** 규칙 2 의 대리 측정이다.

코드 자체는 건강하다 — 본 저장소에서 진짜 이름으로 돌리면 남는 실패 1건은 `test_요구모드_리포트가_이번_실행에서_만들어졌다`(빌드 앞에 `KOTLIN_GATE_REACH_RUN_STARTED_AT` 표식을 박지 않은 절차 사유)뿐이다. **결함은 계획의 검증 규약이지 게이트가 아니다.**

- **근거 위치**: `04_kotlin-implementer_harness-unit-laneA-plan.md` §5 · `tests/test_kotlin_gate_reach.py:170,195`
- **마감**: 다음 레인 A 계획이 §5 를 인용하기 전.

#### [수정 필요] M-2 — `tests/test_kotlin_comment_budget.py` 는 ci.yml 배선이 0 이다 (형제 게이트 5개는 전부 있다)

`.github/workflows/ci.yml` 언급 수 실측: `test_kotlin_gate_reach` 6 · `test_harness_scope_reach` 2 · `test_review_coverage_reach` 1 · `test_commit_gate_chain_reach` 1 · `test_kotlin_class_snapshot_reach` 1 · **`test_kotlin_comment_budget` 0**.

파일을 지우고 확인(E1): `uv run pytest -q tests/test_harness_scope_reach.py tests/test_commit_gate_chain_reach.py` → **48 passed**. 장치 밖 탐지가 없다. `ci.yml:238` 의 blanket `uv run pytest` 는 파일이 사라지면 아무것도 수집하지 않는다 — 이 저장소가 `73f9f4c`(F-4)에서 신설 3파일에 경로 명시 스텝을 넣은 것이 바로 이 형태를 막기 위해서였다.

레인 B 계획 §5 는 *"`CLAUDE.md` 가 명령 절에서 이름으로 가리킨다(기존)"* 로 이 자리를 닫았다고 적었으나, **산문의 언급은 강제자가 아니다**(규칙 3 — 도달 0). 그리고 `329a964` 가 이 파일을 CLAUDE.md 새 규약(테스트 `.kt` 이력 표식 여유 0 라쳇)의 **유일한 강제자**로 만들었으므로 하중이 커진 상태다.

- **근거 위치**: `.github/workflows/ci.yml:232-238` · `xx_harness-unit-laneB-plan.md` §5 · `CLAUDE.md:98-99`
- **마감**: 다음 Kotlin 주석 변경이 있는 회차.

#### [수정 필요] M-3 — 계약 삭제 시 P-3 하한이 X-1b 정체성 지목을 가린다 (N-1)

레인 A 음성 대조 N-1 재실행 결과: `contracts/easy-doc-v1.yaml` 의 `x-private-response-headers.applies_to` 에서 구현 대상 5건을 지우면 `./gradlew :api:test --tests 'kr.easydoc.api.PrivateHeaderFloorCensusTest'` 가 exit 1 로 잡는다. 그러나 세 실패 전부가 **같은 메시지**다:

```
java.lang.IllegalArgumentException: 계약에서 읽은 열거가 5 개다 — 하한 10 아래다.
이 집합을 분모로 쓰는 대조가 전부 함께 좁아지고 그 감소를 재는 것이 없다
```

`atLeastFloor` 의 `require` 가 `privateResponseHeaderTargets()` 안에서 먼저 던지므로 X-1b 정체성 단언이 집합 차를 계산할 기회를 못 얻는다. **어느 5개 경로가 지워졌는지도, 어느 계약 노드인지도 메시지에 없다.** 계획 §3 X-1b 가 기대한 지목 주체가 P-3 에 가려졌다 — 두 처방이 같은 회차에 들어오면서 생긴 상호작용이고 어느 계획에도 적혀 있지 않다. 잡히기는 하므로 차단이 아니다. 동수 치환(N-2)은 하한을 통과해 X-1b 가 양방향으로 정확히 지목한다.

- **근거 위치**: `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractEnumerationFloors.kt` · `PrivateHeaderFloorCensusTest.kt`
- **마감**: 계약 `applies_to` 를 다시 편집하는 회차.

### 2.3 보안 불변식 — 미검토 (사유: 범위 밖)

이 20 커밋은 마스킹·암호화·인증·로깅·저장에 닿지 않는다. 제품 코드 변경은 `ee92455` 의 주석 한 줄이 전부다(§2.1). `privacy-gate` 감사 산출물도 이 회차에 대해 존재하지 않는다. 없는 근거를 추정으로 메우지 않는다 — **보안 축은 이 회차에서 판정하지 않았다.**

### 2.4 Kotlin/Spring 관용성 — 검토함, 지적 없음

- `95979f3` 이 신설한 `ContractEnumerationFloors.kt` 는 `api/src/test/…/support/` 안에 있고 `ContractSpec.kt` 의 접근자만 소비한다. 모듈 경계 위반 없음.
- `7966ff5` 의 `PrivateHeaderFloorCensusTest` 는 HTTP 프로브를 `RequestMappingHandlerMapping` 직접 질의로 갈았다(`ServedOperations.methodsOn`). `@WebMvcTest` 슬라이스 안이고 기존 선례(`AuthenticationCoverageContractTest`)를 재사용했다 — 계획 §1 의 재사용 표대로다.
- `ee92455` 의 `MIN_TESTS_BY_NAMED_ENFORCER`·`MIN_ASSERTIONS_BY_CLASS` 추가는 새 표·새 층 없이 기존 인구조사에 편입됐다. 규칙 7(열거형 3층) 위반 없음.
- `uv run mypy . .claude` 와 `uv run ruff check .` 가 **게이트 스크립트 자신을 포함**한다. 음성 대조 확인: `tests/test_kotlin_gate_reach.py` 와 `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` 양쪽에 타입 오류를 주입하면 mypy exit 1 로 두 파일을 **이름으로** 짚고, 바닥에 `import os` 를 붙이면 ruff 가 `E402 … tests/test_kotlin_gate_reach.py:3857` 로 짚는다. 「판정하는 코드가 자기 자신을 검사 대상에 넣었는가」 — 충족.

### 2.5 테스트 적정성 — 검토함, 권고 3

음성 대조 22건(레인 A 11 + 레인 B 4 + 커버리지 8 + 부수)을 직접 재실행했다. **레인 A 의 N-1~N-11 은 11건 전부 발화한다.** 8건은 변이 위치를 정확히 지목하고, 3건은 클래스·결손 수만 말한다(N-6·N-9 는 「테스트 개수가 17 개다 — 하한 19 아래다」로 **어느 테스트가 사라졌는지**는 말하지 않고, N-1 은 §2.2 M-3). 레인 B 의 ⓐⓑⓒ 도 전부 발화·지목한다(C1·C3 실측). X-3a·X-3b 도 발화·지목한다(B1·B2 실측).

즉 **음성 대조가 실행됐다는 근거는 커밋 메시지에 실재하고, 재실행으로 재현된다.** 축② 는 X-2a·X-2b·X-2c 세 항목을 제외하면 충족이다(그 셋은 §2.2 C-1).

권고는 §4 로 옮긴다.

---

## 3. 도달 범위 점검 결과 (다섯 축을 가로지르는 필수 구획)

| 점검 항목 | 결과 |
|---|---|
| 「전역·모든·항상」 선언이 닿지 않는 경로 | **지적 있음** — C-1(커버리지 4행 중 3행 무하중) · C-2(X-3b 부분집합 분모) |
| 그 게이트가 **지금 어디서 도는가** | **지적 있음** — §4 R-7 표. `test_kotlin_comment_budget` **배선 0**(M-2), `REVIEW_COVERAGE_REQUIRE_SETTLED` **CI 배선 0**(계획 §6-2 기재됨, 유지) |
| 측정이 대리 경로에서 이뤄졌는가 | **지적 있음** — `a28ee47`·`df4d60c` 가 게이트 판정 대신 `_coverage_ranges` 반환 길이로 측정(C-1). 레인 A §5 가 없는 환경변수로 요구 모드를 「돌렸다」(M-1) |
| 검사의 기준이 검사 대상 자신에게서 나오는가 | **검토함 — 지적 없음.** `_COVERAGE_AXIS_NAMES` 는 값이 아니라 이름만 옮기고 `importlib` 로 실물을 짚는다(C4 실측: 축 2개 제거 → 장치 **밖에서** 둘 다 지목). `scope_values()` 는 SKILL.md 표를 읽고 자기 사본을 두지 않는다 |
| 판정이 대리 지표로 이뤄지는가 | **지적 있음** — 위 3행과 같은 항목 |
| 규칙·패턴의 범위가 근거보다 넓은가 | **검토함 — 지적 없음.** 축⑤ 참조: `PRE_GRAMMAR_STEMS` 18종은 정확 일치·양방향(낡은 핀도 지목, C3 실측)이고 `phase-close` 한 값만 표에 더해 회차 열거를 피했다. 이력 표식 상한은 여유 0 실측 확인 |
| 음성 대조가 붙어 있는가 | **부분 충족** — 22건 재현. 미충족은 X-2a·X-2b·X-2c 셋(C-1) |
| 판정하는 코드가 자기를 검사 대상에 넣었는가 | **검토함 — 지적 없음** (§2.4 mypy·ruff 음성 대조) |

---

## 4. 권고 · 계획이 적지 않은 한계 (축⑥)

두 계획 모두 §6 을 갖고 있다. 아래는 **§6 에 없는** 것들이다.

- **R-1 [권고] 표 행의 조용한 탈락에 대조가 없다.** `_ledger_rows`·`_coverage_ranges` 는 형식이 어긋난 행을 `continue` 로 버리고, 「표처럼 보이는 행 수 = 파싱된 행 수」를 재는 단언이 없다. 오늘은 일치한다(장부 24/24 · 커버리지 4/4) — 그래서 지금이 넣기 좋은 시점이다. C-2 의 근본 기제이기도 하다.
- **R-2 [권고] `닫힘` 열이 헤더 단언 목록에서 빠졌다** (`test_review_coverage_reach.py:500`). X-3a·X-3b 가 그 열에 의존하는데 열 구성 변경이 잡히지 않는다(B3 실측).
- **R-3 [권고] 이력 표식 축은 주석만 본다.** `@DisplayName`·문자열 리터럴·코드의 날짜는 세지 않는다 — 실측 9건(`JwtAccessTokensTest.kt` 5 · `MaskingTest.kt`·`AnthropicProvider.kt`·`HwpxExtractorTest.kt`·`PdfExtractorTest.kt` 각 1). 대부분 정당한 fixture 값으로 보이지만, **여유 0 상한을 피하는 자리가 렉서 밖에 있다**는 사실이 계획 §6 에 없다.
- **R-4 [해소 가능] X-11 강제자 유보의 사유가 소멸했다.** 레인 B 계획 §6-4 는 *"오늘 원장의 그 칸이 산문이라 강제하면 즉시 빨개진다"* 를 유보 사유로 들었는데, `aff6204` 가 20행 전부를 백틱 어간 `` `xx_harness-r2` `` 로 바꿨다. 원장이 이미 문법을 지키므로 **지금은 강제자를 걸 수 있다.** 유보를 그대로 두면 그 도달 0 이 사유 없이 남는다.
- **R-5 [기재됨, 유지] X-9 의 새 이름 구멍은 실증됐다.** `MIN_FOO_THINGS = 3` 을 Kotlin 테스트에 넣고 `uv run pytest -q tests/test_kotlin_gate_reach.py` → **exit 0, 304 passed**(baseline과 동일). 전체 Python 스위트 → exit 0, 1510 passed. 같은 파일·같은 편집에 이름만 `MAX_TIMING_RATIO` 로 바꾸면 exit 1. 계획 §6-1 이 정확히 적은 그대로다 — 은폐형 면제표로 닫지 않은 판단에 동의한다.
- **R-6 [기재됨, 유지]** P-3 의 래퍼 타입 접근자(`storedTextDomain()`) · X-1b 의 「계약 자신이 규범인가」 · §4 래퍼 규약의 탐지자 0 — 셋 다 계획 §6 에 적혀 있다.
- **R-7 [권고] `f3398bc` 는 `80f6761`(Haiku 확정, 사용자 결정) 아래에서 충분하지 않다.** 추가된 두 문장은 규약으로서 정확하고, 실측 사고(`04_documents-c6r2` 축② 극성 역전)를 근거로 들며 *"이 래퍼가 Haiku 인 것이 그 위험을 키운다"* 까지 스스로 적었다. 그런데 **탐지자가 0이다**(계획 §6-5 가 인정). 리더 브리프의 문면은 텍스트로 존재하므로 **원문 대 산출물 「지켜야 하는 조건」 블록의 기계 대조는 가능한 종류다** — 분모 정의가 어렵다는 §4 의 판정은 브리프가 파일로 남지 않는다는 전제 위에 있고, 그 전제는 규약으로 바꿀 수 있다. 되돌리기 대상이 아니라는 리더 조건은 지킨다.

---

## 5. Phase 종료 조건 대비 현황 · 미실행 · 확인 불가

**Phase 4 종료 판정에 대한 이 회차의 기여**: 출하 모드(`REVIEW_COVERAGE_REQUIRE_SETTLED=1`)는 오늘 **미상환 `대기` 20건**으로 정상적으로 빨갛다. 그것이 이 축의 의도된 신호이며(레인 B 계획 §0 리더 판정 P-5), 이 회차가 20행의 `닫힘` 을 채우는 것이 정직한 해소다. **다만 C-2 가 열려 있는 동안은 그 `닫힘` 판정 자체를 신뢰할 수 없다** — 상태 칸 표기 하나로 X-3b 가 공허하게 초록이 되기 때문이다. C-1·C-2 의 마감을 「이 회차」로 잡은 이유가 이것이다.

**미실행 / 확인 불가**:

- **보안 불변식 축 전체** — 범위 밖이라 판정하지 않았다(§2.3).
- **`REVIEW_COVERAGE_REQUIRE_SETTLED` 의 CI 배선** — 0 이고, 이는 계획 §6-2 가 의도한 상태다. 「배선하라」는 지적이 아니라 **그 상태가 계속 기록돼 있어야 한다**는 확인이다.
- **N-5 의 다른 소비자** — `ServedOperations.methodsOn` 을 빈 집합으로 만드는 변이는 `PrivateHeaderFloorCensusTest` 에서만 확인했다. 그 헬퍼의 다른 소비자들이 같은 변이에서 어떻게 되는지는 재지 않았다.
- **Gradle 전건** — 리더 실측(`--no-build-cache --rerun-tasks build parityHarness` exit 0 @ `01d3c48`)을 그대로 인용한다. 나는 `:api:test` 부분 실행만 했다.
- **요구 모드 잔여 1건** — 본 저장소에서 `KOTLIN_GATE_REACH_REQUIRE_REPORT=1` 시 `test_요구모드_리포트가_이번_실행에서_만들어졌다` 가 실패한다. 표식(`KOTLIN_GATE_REACH_RUN_STARTED_AT`)을 빌드 앞에 박지 않은 절차 사유로 보이나 **확정하지 않았다.**
- **codex 리뷰와의 대조** — 1차라 수행하지 않았다. 정본은 2차 `xx_harness-r2_cross.md` 다. **이 산출물만으로 Phase 종료 조건 충족을 보고하지 않는다.**
