# 게이트 16 · 1단계 codex 독립 리뷰 — `16_gate15-fixes`

> 이 문서는 `codex-reviewer` 레인의 산출물이다. **§3 은 codex 출력 원문이며 무편집이다.**
> §4 는 Claude 가 만든 색인이고 옳고 그름을 판정하지 않는다 — 판정과 종합은
> `migration-reviewer` 2차 호출(`16_gate15-fixes_cross.md`)과 리더의 몫이다.
>
> **독립성**: 이 리뷰는 `16_gate15-fixes_migration-reviewer.md` 를 보지도 참조하지도 않고
> 작성됐다. 작성 시점에 그 파일을 열지 않았다.
>
> **어간**: `16_gate15-fixes` — 리더가 1단계 호출에서 지정한 값을 그대로 썼다
> (`kotlin-migration` 스킬 `{scope}` 정본 표에서 새로 고르지 않았다).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 (UTC) | 시작 `2026-08-18T13:51:45Z` / 종료 `2026-08-18T14:03:05Z` (11분 20초) |
| 모드 | `adversarial` (→ 헬퍼 `adversarial-review`) |
| 대상 범위 | `614afed..1cb7bdf` (6 커밋) |
| `--base` / `--scope` | `--base 614afed` / scope 미지정(base 지정 시 무시됨) |
| **스크립트 종료 코드** | **`0`** — 리뷰 근거로 유효 |
| verdict | `needs-attention` |
| findings | **3건, 전부 `high`** (confidence 1.0 / 0.99 / 0.99) |
| job id | `review-msyq1hzf-yndt7s` |
| Codex session ID | `01a01524-7a74-7391-8215-efa423b9928a` |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택, v1.0.6) |
| job 상태 디렉터리 | `~/.claude/plugins/data/codex-openai-codex/state/wt-gate16-ee86b7cd7a670715/jobs/` |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 614afed
codex-review: 대상 판정 = non-empty (merge-base=614afedfbeb1, 변경 파일 14개 (branch 모드는 커밋된 변경만 센다))
```

### 1.2 실행 명령 전문

```bash
# 일회용 detached worktree 를 1cb7bdf 에 만들어 그 안에서 실행했다.
git worktree add --detach <scratchpad>/wt-gate16 1cb7bdf

cd <scratchpad>/wt-gate16 \
  && FOCUS="$(cat <scratchpad>/focus16.txt)" \
  && /Users/harris/Development/private/easy-doc/.claude/skills/codex-review/scripts/codex-review.sh \
       adversarial --base 614afed "$FOCUS"

# 헬퍼로 나간 실제 명령 (스크립트가 출력한 것):
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
     adversarial-review --base 614afed <focus text>
```

**worktree 를 쓴 이유(범위 정합)**: 본 저장소 `HEAD` 는 이 리뷰 시점에 `c932b4f`("게이트 15 산출물
3종 + 게이트 14 codex 재실행분 보존")였다. 본 저장소에서 `--base 614afed` 를 주면 대상이
**변경 파일 20개**가 되어 리더 지정 범위(14개)를 넘고, 그 초과분에 **게이트 15 `migration-reviewer`
산출물이 포함**된다. `1cb7bdf` 에 고정한 worktree 에서 돌려 대상을 **14개**로 맞췄다
(§1.1 판정 줄이 그 수치다). 두 값은 `--dry-run` 으로 각각 실측해 확인했다.

### 1.3 제공한 맥락 목록

프롬프트에 판정 내용을 **전사하지 않았다.** 파일·행·커밋 해시로 지목하고 codex 가 직접 읽게 했다
(게이트 15 별건 1 규약).

| 자료 | 제공 방식 |
|---|---|
| `docs/migration/_workspace/reviews/15_phase3-preflight_cross.md` | 경로 + 절 지목(3.1·3.2 의 X1·X2·X3·X5·X6·X11 행, 4장 충돌 X2·X13, 5.2, 6장). **본문 전사 없음** |
| `.claude/skills/kotlin-migration/SKILL.md` 「선언한 범위와 실제 도달을 대조한다」 | 경로 + 절 지목(규칙 3 실행 경로, 규칙 4 장치 분류 4종). **규칙 본문 전사 없음** |
| `contracts/easy-doc-v1.yaml:386-424` | **행 범위만** 지목. F3 다섯 필드의 측정 축(원시/정규화)이 정본이라는 사실만 알리고 **2/5 가 원시라는 값은 내가 적지 않았다** — codex 가 직접 읽게 했다 |
| 대상 커밋 6개 | 해시로 지목 (`65a7eb6`·`526bfeb`·`f9d78e0`·`04ced00`·`4cba492`·`1cb7bdf`) |
| 대상 파일 | 경로+행으로 지목 (§2 프롬프트 전문 참조) |

**cross.md 의 worktree 배치**: `15_phase3-preflight_cross.md` 는 `1cb7bdf` **이후** 커밋(`c932b4f`)에
들어 있어 worktree 체크아웃에 없다. 이 한 파일만 worktree 에 untracked 로 복사했고, 그 사실을
프롬프트에 명시했다. **게이트 15 의 `migration-reviewer` 산출물과 `codex-reviewer` 산출물은
복사하지 않았다** — worktree 에 존재하지 않는다(확인함).

### 1.4 민감 데이터

프롬프트·대상 diff 에 사용자 문서 본문·실제 암호문·키·개인정보 없음. 대상은 하네스 스크립트·CI
설정·테스트·계약 문서뿐이다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 중이다. 이 배치(614afed..1cb7bdf, 6커밋)는 직전 리뷰 게이트가 낸 차단 항목을 닫겠다고 주장하는 수정이다. 판정 원문을 이 프롬프트에 옮겨 적지 않았다 — 아래 파일을 직접 읽고 그 원문을 채점 기준으로 삼아라. 내 요약을 받지 말고 파일을 열어라.

기준 문서(직접 읽어라):
- docs/migration/_workspace/reviews/15_phase3-preflight_cross.md — 3.1·3.2 표의 X1·X2·X3·X5·X6·X11 행, 4장 충돌 X2·X13, 5.2, 6장. 이 배치가 닫겠다는 대상이 정확히 그 행들이다. (이 파일은 이 커밋 이후에 커밋됐으므로 작업 트리에 untracked 로 놓아 두었다.)
- .claude/skills/kotlin-migration/SKILL.md 의 「선언한 범위와 실제 도달을 대조한다」 절 — 규칙 6개, 특히 규칙 3(실행 경로)과 규칙 4(장치 분류: 탐지형 / 은폐형 / 강제·표현형 / 범위 선언형)가 이 리뷰의 채점 기준이다.
- contracts/easy-doc-v1.yaml:386-424 — F3 다섯 필드의 측정 축(원시 값이냐 정규화 값이냐)은 이 파일이 정본이다. 직접 읽어 필드 단위로 확인한 뒤, f9d78e0 이 바꾼 FrameworkErrorContractTest.kt 의 단언과 526bfeb 이 고친 docs/migration/_workspace/00_contract-keeper_test-plan.md 가 그 정본과 필드마다 맞는지 대조하라.

찾아라 (다섯 축):

(1) 각 수정이 지목된 결함을 실제로 닫는가. 특히 이 배치가 근거로 내세운 음성 대조 주장 셋이 재현되는가.
  a. backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt 의 advisory lock 획득을 제거하면 FlywayBaselineGuardTest.kt 의 동시 기동 테스트가 실제로 빨개지는가.
  b. .claude/skills/python-kotlin-parity/scripts/compare_parity.py 의 본류 호출부(compare_file / main 에서 게이트를 부르는 자리)를 제거하면 tests/test_parity_ci_gate.py 가 빨개지는가. helper 만 검사하고 배선 제거를 놓치는 구조가 남아 있는가.
  c. .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py 를 이 배치 직전 판(614afed)으로 되돌리면 tests/test_python_snapshot_guard.py 가 4건 실패하는가.
  검증은 일회용 git worktree 를 새로 만들어 그 안에서 돌려라. 현재 작업 트리의 파일을 수정하지 말고, 되돌린 파일을 남기지 마라. 재현되지 않거나 부분적으로만 재현되면 그 자체가 결함이다.

(2) 새 장치 자신의 도달 범위.
  a. .claude/skills/kotlin-migration/scripts/run_gate.sh 는 자기 주석에서 CI 배선 0 이라고 스스로 적는다. 그 상태에서 이 러너가 실제로 무엇을 강제하는가. 65a7eb6 이 SKILL.md 에 추가한 규약 문장에 강제자가 존재하는가, 아니면 문장뿐인가.
  b. .github/workflows/ci.yml 에 이 배치가 넣은 새 스텝 4개가 실재하는가. 경로가 맞는가. 어느 잡에서 도는가(Python 환경이 필요한 스텝이 JDK 잡에 있거나 그 반대는 아닌가). 스텝이 실제로 실행될 조건인가(트리거, if, 이전 스텝 실패 시 도달).
  c. backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/MigrationStatementTracer.kt 가 Flyway 가 실제로 실행하는 SQL 을 가로채는가, 아니면 가로채지 못한 채 테스트만 초록인가. 어느 계층에 끼워졌고 Flyway 가 그 계층을 우회할 경로가 있는가.

(3) 새 테스트의 빈 통과 경로.
  a. FlywayBaselineGuardTest.kt 의 hasSize(3) 단언과 질의 수 하한 단언이 「추적이 죽어 0건 수집」과 「정상 동작」을 구분하는가. 추적이 아무것도 못 잡은 상태에서 통과하는 경로가 있는가.
  b. dump_python_snapshots.py 의 PROMPT_CASE_FLOOR 하한 검사가 목록이 비었을 때 실패하는가 통과하는가. 빈 섹션·빈 dict·키 누락 각각에서 어떻게 되는가.
  c. tests/test_parity_ci_gate.py 의 본류 회귀 4건이 종료 코드가 아니라 리포트 문구로 단언하는 설계다. 문구가 바뀌거나 다국어화되면 조용히 무력해지는가. 문구 매칭이 실패를 성공으로 읽는 경로가 있는가.

(4) 개별 결정의 대가.
  a. FlywayBaselineGuard.kt:204 의 MIGRATION_LOCK_KEY 를 private 에서 internal 로 연 것 — 테스트를 위해 가시성을 넓힌 대가가 무엇인가.
  b. 1cb7bdf 가 ktlint 를 맞추며 backend-kotlin/build.gradle.kts 주석의 //: 를 // + 빈 줄로 바꿨다. 주석 내용이 손실되거나 의미가 갈린 자리가 있는가.
  c. PROMPT_CASE_FLOOR 를 검사 대상 생성기 안의 상수로 둔 결정. 이것이 범위 선언형 장치가 자기 선언과 자기를 대조하는 구조는 아닌가(SKILL.md 규칙 4 (3)). 빈 선언에서 통과하는가.

(5) 이 배치가 새로 선언한 범위. 전역·모든·항상에 해당하는 선언이 새로 생겼는데 실제 도달이 그보다 좁은 자리를 찾아라. 그리고 은폐형(무시 패턴, 면제 조항, 억제, 예외 목록)을 새로 넣거나 넓힌 자리를 찾아라 — ci.yml 주석이 사면을 붙이지 않는다고 적은 자리가 실제로 그런지 포함한다. 각 새 장치에 대해 「이것을 떼면 정확히 무엇이 빨개지는가」에 답하고, 떼도 아무것도 빨개지지 않는 장치를 지목하라.

배경 한 가지 — 이 항목은 네가 처음 본다. run_gate.sh(65a7eb6)가 대응한다고 주장하는 결함은 cross.md 의 X3 행과 6장에 T-D 로 적힌 것이다. 그 진단은 직전 게이트에서 독립 리뷰를 받지 못한 단일 관점 판정이었다. 그러므로 (i) X3 의 진단 자체가 옳은지와 (ii) 65a7eb6 의 대응이 그 진단에 맞는 대응인지를 각각 따로 판정하라. 진단이 과장이거나 대응이 엉뚱하다고 보이면 그렇게 적어라.

깨지면 무엇이 새는가: 이 배치의 장치들은 전부 「게이트가 초록인데 실제로는 아무것도 재지 않는」 실패를 막으려고 세운 것이다. 하나라도 무력하면 이후 Phase 의 어떤 초록도 검증 근거가 되지 못한다.
```

---

## 3. codex 원문 출력 (무편집)

> 아래는 `codex-review.sh` stdout 전문이다. **한 글자도 고치지 않았다.**
> 헬퍼 `result <job-id>` 로 재조회한 결과와 대조했고, 차이는 말미의
> `Codex session ID` / `Resume in Codex` 두 줄뿐이었다(그 두 줄은 §1 표에 옮겨 적었다).
> **잘림 없음.**

```text
# Codex Adversarial Review

Target: branch diff against 614afed
Verdict: needs-attention

출하 차단. X3는 실제로 우회 가능하고, X1 하한은 자기 선언을 축소하면 다시 빈 측정이 되며, X6는 다섯 필드의 실행 가능한 강제자가 아니다. CI diff에는 새 스텝이 4개가 아니라 quality 잡의 3개뿐이고 run_gate 소비자는 없다. 요청한 worktree mutation 실행은 read-only sandbox의 EPERM으로 차단되어 커밋 메시지의 재현 주장은 독립 검증으로 인정하지 않았다.

Findings:
- [high] X3 러너를 경유해도 외부 파이프가 실패를 다시 삼킨다 (.claude/skills/kotlin-migration/scripts/run_gate.sh:18-21)
  X3의 진단 자체는 맞지만 대응은 닫히지 않았다. 인용된 내부 파이프는 `run_gate.sh "false | tail"`에서 status 1이지만, 자연스러운 `.claude/.../run_gate.sh false | tail`은 bash와 zsh 모두 outer status 0이었다. 셸이 파이프를 러너 밖에서 먼저 구성하기 때문이다. 또한 스크립트가 스스로 CI 배선 0·비경유 명령 탐지 불가라고 선언하고, 전체 검색에서도 SKILL 문장과 스크립트 자체 외 소비자·회귀 테스트가 없었다. 이 파일을 삭제해도 아무것도 빨개지지 않아 이후 게이트 증거가 다시 거짓 초록이 될 수 있다.
  Recommendation: 외부 파이프가 필요 없는 출력 캡처/절단 옵션을 러너에 제공하고, 게이트 증거의 원시 파이프 사용을 기계적으로 거부하라. 러너 존재·CI/하네스 소비와 인용·비인용 실패 전파를 별도 회귀 테스트로 고정하라.
- [high] PROMPT_CASE_FLOOR가 섹션 단위 자기 축소를 초록으로 승인한다 (.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py:264-287)
  검사는 `PROMPT_CASE_FLOOR[section]`에 열거된 이름만 요구하므로 범위 선언과 판정기가 같은 파일에 있다. `system_prompts` 하한을 빈 튜플로 바꾸고 스냅샷을 6건에서 1건으로 줄인 메모리 프로브에서 재생성 결과가 축소 스냅샷과 바이트 동일했다. 현 회귀는 섹션 키 집합과 스냅샷의 비어 있지 않음만 보며 system/user/repair의 독립 정체성은 고정하지 않는다. 빈 dict·키 누락은 KeyError로 실패하지만, 더 위험한 '키는 있고 선언만 빈 상태'는 통과한다. 따라서 X1의 45건 범위를 소수만 남기고도 게이트와 회귀를 함께 초록으로 유지할 수 있다.
  Recommendation: 생성기 밖의 독립 정본에 전체 케이스 정체성을 고정하고 각 섹션의 비어 있지 않은 하한과 정확한 필수 ID를 대조하라. 각 섹션 하한을 단독으로 비우거나 줄이는 mutation이 해당 섹션 전용 테스트를 실패시키게 하라.
- [high] F3의 실행 가능한 다섯 필드 강제자가 여전히 없다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/FrameworkErrorContractTest.kt:145-180)
  신규 테스트는 실제 F3 DTO나 경계값을 거치지 않고 합성 `InvalidInputException` 한 건의 직렬화만 검사한다. 향후 `SignupRequest.password` 등에 금지된 `@Size`/`@NotBlank`를 달아도 이 테스트는 그대로 초록이다. 실행 소스 검색에서도 X-F11~X-F13이나 해당 DTO 구현 테스트는 없고 Markdown 계획만 존재한다. 더구나 주석은 다섯 필드가 모두 정규화 후 측정된다고 적지만 정본은 password와 text를 raw, 나머지 셋을 normalized로 규정하며, test-plan도 text의 raw 측정 축을 명시적으로 미커버 상태로 남긴다. 따라서 X6의 '강제자 0'과 X13 측정축 공백은 닫히지 않았다.
  Recommendation: 실제 DTO·서비스·HTTP 경로를 대상으로 YAML 정본에서 필드별 축(email normalized, password raw, text raw, edited_text normalized, name normalized)을 읽는 계약 테스트를 추가하라. 정확한 다섯 속성에 Bean Validation 길이·형식 제약이 없음을 검사하는 구조 테스트도 함께 두고, Markdown 항목을 강제자라고 분류하지 마라.

Next steps:
- 쓰기 가능한 일회용 worktree에서 잠금 제거, parity 본류 호출 제거, 614afed 생성기 복원의 세 mutation을 직접 재실행해 종료 코드와 실패 테스트를 보존한다.
- X3의 비인용 파이프 우회와 X1의 섹션별 빈 하한 변형을 상시 회귀로 추가한다.
- F3 다섯 필드의 실제 구현 테스트가 생기기 전에는 Phase 3 게이트를 닫지 않는다.
```

### 3.1 codex 중간 진행 메시지 원문 (무편집)

> job 로그(`review-msyq1hzf-yndt7s.log`)에 남은 codex 자신의 진행 보고다. **최종 findings 에 실리지
> 않은 관측**(CI 스텝 개수 정정, 샌드박스 차단 시점과 사유)이 여기 있어 함께 보존한다.
> 요약이 아니라 각 메시지의 `summary`·`next_steps` 필드 원문이다.

**로그 8행 (착수)**
```text
검토를 시작합니다. easy-doc의 이전 게이트 메모리는 탐색 경로를 잡는 데만 쓰고, 모든 판정은 현재 worktree·대상 diff·실제 명령 출력으로 다시 검증하겠습니다. 요청한 음성 대조는 현재 트리를 건드리지 않는 임시 worktree에서 재현하겠습니다.
  * 기준 문서와 규칙 원문, F3 정본을 먼저 대조
  * 614afed..HEAD의 장치별 실행 경로와 CI 배선 추적
  * 격리 worktree에서 세 음성 대조 및 빈 통과 변형 실행
```

**로그 20행**
```text
메모리 레지스트리가 이 작업과 직접 겹치는 easy-doc parity stop-gate 지침을 가리켰습니다. 그 지침이 요구하는 독립 정본·실제 소비자·mutation 실패 기준을 적용하되, 과거 결론은 재사용하지 않고 현재 배치에서 다시 입증합니다.
  * 지침 원문 전체 읽기
  * cross.md·SKILL.md·F3 정본을 줄 단위로 대조
```

**로그 46행**
```text
기준 원문을 확인했습니다. 핵심은 장치가 존재하느냐가 아니라 실제 소비 경로에서 실패를 전파하고, 장치를 제거했을 때 장치 밖의 검증이 빨개지느냐입니다. 이제 각 장치의 본류·CI·빈 상태를 코드로 추적합니다.
  * run_gate의 실효 범위와 X3 진단 분리 평가
  * parity·snapshot 본류/빈 상태 추적
  * Flyway SQL 추적 계층과 우회 경로 확인
```

**로그 108행 — CI 스텝 개수 정정**
```text
정적 추적에서 두 가지가 이미 분리됩니다. CI에 새로 추가된 실행 스텝은 4개가 아니라 3개이며 모두 Python `quality` 잡에 있습니다. 반면 X3 러너는 CI·테스트 호출이 0이고, F3의 새 테스트는 실제 다섯 DTO가 아니라 합성 `InvalidInputException` 하나만 검사합니다. 이제 요청한 mutation을 격리 worktree에서 실측합니다.
  * 격리 worktree 생성 시도
  * 잠금 호출 제거·본류 호출 제거·614afed 생성기 복원 후 각 표적 테스트 실행
  * 러너의 인용/비인용 파이프 종료 코드 실측
```

**로그 156행 — 샌드박스 차단 보고**
```text
핵심 반증은 확보됐습니다. X3 러너는 인용된 파이프만 보호하고, 자연스러운 비인용 사용에서는 bash·zsh 모두 외부 종료 코드가 0이 됐습니다. 스냅샷 하한도 한 섹션의 선언을 비우고 6→1로 줄였을 때 재생성 결과가 그대로 같았습니다. 다만 격리 worktree 생성은 샌드박스가 `mktemp ... Operation not permitted`로 거부해, 요청한 세 파일 mutation의 실제 pytest/Gradle 재실행은 이 환경에서 완료할 수 없었습니다.
  * 남은 정적 대조를 마치고 차단 finding만 압축
  * 쓰기 가능한 환경에서 세 음성 대조를 그대로 재실행해야 함
```

---

## 4. 정리 (가공) — 색인

> **여기서도 옳고 그름은 판정하지 않는다.** 심각도는 codex 가 붙인 값 그대로다.
> 중복 병합·표현 다듬기·오탐 주석 삽입을 하지 않았다.

### 4.1 지적 3건 색인

| # | codex 제목 | 심각도(codex) | confidence | 근거 파일·행 (codex 인용 그대로) | 대응 리뷰 축 | cross15 항목 |
|---|---|---|---|---|---|---|
| K16-1 | X3 러너를 경유해도 외부 파이프가 실패를 다시 삼킨다 | `high` | 1.00 | `.claude/skills/kotlin-migration/scripts/run_gate.sh:18-21` | ①·②a·⑤ | X3 (`65a7eb6`) |
| K16-2 | `PROMPT_CASE_FLOOR` 가 섹션 단위 자기 축소를 초록으로 승인한다 | `high` | 0.99 | `.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py:264-287` | ③b·④c | X1 (`04ced00`) |
| K16-3 | F3 의 실행 가능한 다섯 필드 강제자가 여전히 없다 | `high` | 0.99 | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/FrameworkErrorContractTest.kt:145-180` | 계약/F3 | X6·X13 (`526bfeb`·`f9d78e0`) |

### 4.2 리뷰 축별 도달 — 무엇에 답이 왔고 무엇에 안 왔는가

| 축 | codex 응답 |
|---|---|
| ① 음성 대조 재현 (잠금 제거 / 본류 호출 제거 / 구판 생성기 4 failed) | **미실행** — 샌드박스가 worktree 생성을 거부(§5). codex 는 세 재현 주장을 "독립 검증으로 인정하지 않았다"고 적었다. 반증도 확증도 아니다 |
| ①-대체 | codex 가 실행한 것: `run_gate.sh` 인용/비인용 파이프 종료 코드 실측(bash·zsh), `PROMPT_CASE_FLOOR` 섹션 축소 **메모리 프로브** |
| ②a `run_gate.sh` 자기 도달 | 지적함 (K16-1) — 소비자·회귀 테스트 검색 0건 |
| ②b CI 새 스텝 | 지적함 — **개수 정정: 4개가 아니라 3개**, 모두 Python `quality` 잡 (§3.1 로그 108행). 최종 findings 의 개별 항목으로는 올라오지 않고 summary 에만 있다 |
| ②c `MigrationStatementTracer` 가 Flyway SQL 을 실제로 가로채는가 | **지적 없음** — 최종 findings·중간 메시지 어디에도 판정이 없다. codex 는 "Flyway SQL 추적 계층과 우회 경로 확인"을 계획(로그 46행)했으나 결과를 적지 않았다 |
| ③a `hasSize(3)`·질의 수 하한이 추적 사망을 잡는가 | **지적 없음** |
| ③b `PROMPT_CASE_FLOOR` 하한이 "비면 실패"인가 | 지적함 (K16-2) — codex 답: 빈 dict·키 누락은 `KeyError` 로 실패, **"키는 있고 선언만 빈 상태"는 통과** |
| ③c 본류 회귀 4건의 문구 단언 설계 | **지적 없음** |
| ④a `MIGRATION_LOCK_KEY` private→internal | **지적 없음** |
| ④b `1cb7bdf` 주석 `//:` → `//`+빈 줄 내용 보존 | **지적 없음** |
| ④c `PROMPT_CASE_FLOOR` 를 생성기 안 상수로 둔 결정 (규칙 4 ⑶) | 지적함 (K16-2) — "범위 선언과 판정기가 같은 파일에 있다" |
| ⑤ 새 "전역·모든" 선언 / 은폐형 확대 | **새 은폐형 지적 없음.** ⑤의 "떼면 무엇이 빨개지는가"에는 답함 — `run_gate.sh` 는 "삭제해도 아무것도 빨개지지 않는다"(K16-1) |
| ⑥ X3 진단 자체의 옳고 그름 (codex 첫 노출) | **답함** — "X3의 진단 자체는 맞지만 대응은 닫히지 않았다" (K16-1 본문 첫 문장) |

### 4.3 전제 확인 필요

> codex 출력이 근거로 삼은 전제 중 `migration-reviewer` 가 확인해야 할 것. **삭제하지 않고 그대로 둔다.**

1. **`run_gate.sh` 행 인용 `18-21`** — codex 가 지목한 행이 실제로 "CI 배선 0·비경유 명령 탐지 불가"
   자기 선언 구간인지 대조 필요. (K16-1)
2. **비인용 파이프 실측** — codex 는 `.claude/.../run_gate.sh false | tail` 이 bash·zsh 모두 outer
   status 0 이었다고 적는다. 이 실측이 재현되는지, 그리고 그 사용 형태가 하네스에서 실제로 쓰이는
   형태인지 확인 필요. (K16-1)
3. **`PROMPT_CASE_FLOOR` 메모리 프로브** — "`system_prompts` 하한을 빈 튜플로 바꾸고 스냅샷을
   6건에서 1건으로 줄였더니 재생성 결과가 축소 스냅샷과 바이트 동일"이라는 주장. 프로브 재현 필요.
   또한 codex 가 말하는 "X1의 45건"이 리더가 지정한 `PROMPT_CASE_FLOOR(45)` 하한과 같은 수치를
   가리키는지 확인 필요. (K16-2)
4. **`FrameworkErrorContractTest.kt:145-180` 행 인용** — 이 배치가 그 파일에 넣은 신규 테스트 구간과
   일치하는지 대조 필요. codex 는 이 구간이 "합성 `InvalidInputException` 한 건의 직렬화만" 본다고
   판정했다. (K16-3)
5. **F3 주석과 정본의 불일치 주장** — codex 는 "주석은 다섯 필드가 **모두 정규화 후 측정**된다고
   적지만 정본은 `password`·`text` 를 raw, 나머지 셋을 normalized 로 규정"한다고 적는다. 어느 파일의
   어느 주석인지 codex 가 특정하지 않았다 — 대상 특정과 대조가 필요하다. (K16-3)
6. **`X-F11~X-F13` 검색 0건** — codex 는 실행 소스에서 이 식별자를 찾지 못하고 "Markdown 계획만
   존재한다"고 적는다. 이 식별자가 이 하네스의 정본 어휘인지 확인 필요. (K16-3)
7. **CI 스텝 개수 3 vs 4** — codex 는 프롬프트가 준 "4개" 전제를 **정정**해 "3개, 모두 Python
   `quality` 잡"이라고 적었다. 프롬프트의 "4개"는 리더 지시문에서 온 값이며 내가 검증하지 않고
   전달했다. 어느 쪽이 맞는지 확인 필요.
8. **codex 의 메모리 사용** — codex 는 로그 20행에서 "메모리 레지스트리가 이 작업과 직접 겹치는
   easy-doc parity stop-gate 지침을 가리켰다"며 과거 게이트 지침을 참조했다고 스스로 적는다.
   같은 문장에서 "과거 결론은 재사용하지 않고 현재 배치에서 다시 입증한다"고 밝혔다. **완전한
   맥락 무지 상태가 아니었다는 사실을 기록으로 남긴다** — 독립성 해석은 종합 레인의 몫이다.

---

## 5. 미실행·실패 항목

### 5.1 ⚠ 리뷰 축 ①(음성 대조 재현)이 실행되지 못했다 — 샌드박스 차단

**codex 리뷰 자체는 누락되지 않았다** (exit 0, findings 3건). 그러나 **리더가 1순위로 요구한
음성 대조 재현은 수행되지 않았다.**

- 사유 (codex 원문): `격리 worktree 생성은 샌드박스가 mktemp ... Operation not permitted 로 거부해,
  요청한 세 파일 mutation 의 실제 pytest/Gradle 재실행은 이 환경에서 완료할 수 없었습니다.`
- 최종 summary 원문: `요청한 worktree mutation 실행은 read-only sandbox의 EPERM으로 차단되어
  커밋 메시지의 재현 주장은 독립 검증으로 인정하지 않았다.`
- 결과: 세 재현 주장(잠금 제거 시 빨강 / 본류 호출부 제거 시 빨강 / 구판 생성기에서 4 failed)은
  **확증도 반증도 되지 않은 상태**다. codex 의 `Next steps` 첫 항목이 이 재실행을 요구한다.
- **재시도하지 않은 이유**: 차단 원인이 codex 실행 환경의 쓰기 권한(read-only sandbox)이며, 같은
  인자로 다시 돌려도 같은 벽에 부딪힌다. 헬퍼의 샌드박스 정책을 이 레인이 임의로 바꾸지 않는다.
- **내가 대신 재현하지 않았다.** 그것은 codex 의 독립 관측을 Claude 관측으로 바꿔치기하는 것이고,
  이 레인의 존재 이유를 없앤다. 재현 필요 여부와 수행 주체는 리더가 판정한다.

### 5.2 codex 가 답하지 않은 축

§4.2 에서 **지적 없음**으로 표시한 5개 — ②c(`MigrationStatementTracer` 의 SQL 가로채기),
③a(`hasSize(3)`·질의 수 하한), ③c(본류 회귀 문구 단언), ④a(`MIGRATION_LOCK_KEY` 가시성),
④b(`1cb7bdf` 주석 보존), 그리고 ⑤의 은폐형 확대 여부.

**Claude 가 대신 채우지 않았다** (`codex-review` 스킬 §7). "지적 없음"은 "문제 없음"이 아니라
"codex 가 이 회차에 그 축에서 지적을 내지 않았다"는 사실의 기록이다. 축 개수가 5개 상한을 넘어
얕아졌을 가능성은 종합 레인이 판단할 사항이다.

### 5.3 실행 실패·재시도

없음. **1차 시도에서 exit 0.** 재시도 없음. 출력 잘림 없음(§3 머리 참조).

### 5.4 정리 작업

리뷰에 쓴 일회용 worktree(`<scratchpad>/wt-gate16`)와 복사해 둔 `15_phase3-preflight_cross.md` 사본은
scratchpad 안에만 있고 저장소 작업 트리를 건드리지 않았다. 커밋하지 않았고 `00_progress.md` 를
수정하지 않았다.
