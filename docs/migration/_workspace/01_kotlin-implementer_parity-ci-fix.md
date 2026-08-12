# Phase 1 후속 — parity CI 게이트 재배선 (X-1 · X-2)

**작성:** kotlin-implementer / 2026-08-12
**대상 결함:** `docs/migration/_workspace/reviews/01_skeleton_cross.md` §7.1 우선순위 1 (X-1 + X-2)
**수정 파일:** `.github/workflows/ci.yml`, `backend-kotlin/build.gradle.kts`,
`backend-kotlin/parity-domains.txt`(신규),
`backend-kotlin/core/src/testFixtures/kotlin/kr/easydoc/core/parity/ParityHarnessSelfCheck.kt`

---

## 1. 고치기 전 상태 — 실측으로 먼저 확정했다

교차 종합의 주장을 코드 읽기로만 받지 않고, ci.yml 의 해당 step 을 **파일에서 그대로 꺼내**
bash 로 돌려 종료 코드를 쟀다. 도구는 `scratchpad/run_ci_step.py` 로, `ci.yml` 을 파싱해
`jobs.kotlin.steps[name].run` 문자열을 손대지 않고 실행한다 — YAML 을 옮겨 적으면서 생기는
차이를 없애려는 것이다.

| 상태 | 명령 | 종료 코드 | 관찰 |
|---|---|---|---|
| A. `parity/fixtures` 없음 (당시 HEAD) | `run_ci_step.py "parity 비교"` | **0** | `::warning::parity/fixtures 가 아직 없다 … 비교를 건너뛴다` — 비교기를 **한 번도 부르지 않는다** |
| B. fixture 11개 전부 + Kotlin 산출물 0개 | 같음 | **1** | `[불일치] … 불일치 11건` — 전부 `Kotlin 결과 파일 없음` |
| C. Phase 2 의 8개만 fixture 생성 | 같음 | **1** | `[도메인 누락] … 없는 도메인: crypto, jwt, argon2` |

**세 줄 요약.** A 는 X-1(트리를 지우면 되살아나는 백도어)이고 B·C 는 X-2(Phase 2 에 초록이
되는 경로가 없음)다. 그리고 두 경우 모두 종료 코드가 **1** 이므로, `ci.yml:156` 의 `종료 코드
2` 사면은 **발생하지 않는 조건을 겨냥하고 있었다.** 교차 종합의 판정이 실측으로 그대로 확인됐다.

추가로, 사면이 겨냥했어야 할 exit 2 가 실제로 언제 나는지도 재 봤다.

| 상태 | 종료 코드 |
|---|---|
| G. `crypto` 를 판정 범위에 넣었고 값 케이스 산출물은 있으나 **역방향 산출물(`kotlin-encrypt.json`)이 없음** | **2** |

즉 exit 2 는 "아직 포팅하지 않았다"가 아니라 **"판정 범위에 넣어 놓고 역방향 산출물을 만들지
않았다"** 일 때만 난다. 사면해서는 안 되는 상태다. 이것이 아래 설계에서 **exit 2 사면을 통째로
없앤** 근거다.

---

## 2. 택한 설계

### 2.1 한 줄 요약

비교 범위를 **디렉터리 유무**가 아니라 **버전 관리되는 선언**에서 가져오고, 그 선언을
**실행 결과와 양방향으로 대조**한다. 사면은 "아직 포팅하지 않은 도메인"에만 닿고,
"포팅했다면서 산출물이 없는 도메인"에는 **구조적으로 닿을 수 없게** 다른 CI 단계에 둔다.

### 2.2 구성 요소 셋

**(1) 선언 — `backend-kotlin/parity-domains.txt`**

한 줄에 도메인 하나, `#` 주석 허용. Phase 1 현재 **0개**(주석만). Phase 2 에서 모듈 하나를
끝낼 때마다 한 줄씩 추가한다. 이 파일이 "Kotlin 이 어느 도메인을 포팅했다고 주장하는가"의
유일한 기계 판독 지점이다.

**(2) 선언 ↔ 실행 대조 — Gradle `parityManifestCheck`**

`parityHarness` 가 산출물을 쓴 **직후** 같은 Gradle 실행 안에서 돈다. 양방향으로 막는다.

| 상태 | 판정 | 왜 막는가 |
|---|---|---|
| 선언 O / 산출 X | **빌드 실패** | "포팅했다"고 적어 두고 산출물이 없다 = 미검증이 통과로 집계되는 상태 |
| 선언 X / 산출 O | **빌드 실패** | 비교가 선언 범위로 좁혀지므로, 산출물만 만들고 선언에서 빼면 값 비교를 조용히 건너뛸 수 있다 |
| 선언 O / 디렉터리 O / json 0건 | **빌드 실패** | 빈 디렉터리로 "산출했다"를 흉내 내는 경로 |

여기에 **`parityActualClean`** 을 앞에 붙였다. 매 실행 전 `parity/actual/` 을 비운다. 이것이
없으면 Kotlin parity 테스트를 지워도 지난 실행의 산출물이 남아 게이트가 계속 통과한다 —
"이번 실행이 실제로 무엇을 만들었는가"가 위 대조의 입력이므로 선행 조건이다.

**(3) 범위를 좁힌 비교 — `ci.yml` 의 `parity 비교 (선언한 도메인 범위)`**

`compare_parity.py` 에 이미 있는 부분 검증 계약(`--only-domain` → 통과 시 **exit 3**)을 쓴다.
새 종료 코드를 발명하지 않았다.

| 선언 상태 | 비교기 호출 | 통과 조건 | CI 결과 |
|---|---|---|---|
| 0개 | **호출 안 함** | — | 초록 + `::warning::parity 게이트 미가동` |
| 정본의 진부분집합 | `--only-domain …` | **3만** | 초록 + `::notice::부분 게이트 통과` + `::warning::검증 안 한 도메인 목록` |
| 정본 전체(11개) | 좁히지 않고 호출 | **0만** | 초록 + `전체 게이트 통과` |
| 그 외 종료 코드(1·2·…) | — | — | **빨강** |

### 2.3 이 설계가 요구 3개를 각각 어떻게 만족시키는가

**요구 1 — Phase 2 진행 중 CI 가 의미 있게 초록일 수 있어야 한다.**
선언한 도메인만 판정하므로, masking·text 만 포팅한 상태에서 나머지 9개 fixture 가 저장소에
있어도 초록이다(§3 테스트 2). 그리고 그 초록은 값 21건을 **실제로 대조한** 결과다.

**요구 2 — 검증하지 않은 것이 통과로 집계되면 안 된다.**
세 층으로 막았다. ①좁힌 범위는 로그에 도메인 이름으로 전부 찍힌다(`검증하지 않음 : 9개 —
argon2 crypto export …`). ②종료 코드가 0 이 아니라 **3** 이므로 자동화가 전체 통과와 구분할 수
있다. ③선언 자체가 실행과 대조되므로 "선언만 하고 안 만든" 상태는 애초에 성립하지 않는다.

**요구 3 — 사면 조건이 실제로 발생하는 상태를 겨냥해야 한다.**
새 사면 조건은 "선언하지 않은 도메인은 비교 범위에서 빠진다"이고, 이것은 Phase 2~4 내내
실제로 발생하는 상태다(실측 §3 테스트 1·2). 반대로 **exit 2 사면은 완전히 제거**했다 — §1 의
실측대로 exit 2 는 "선언한 것을 지키지 못한" 상태이기 때문이다.

**그리고 사면은 스스로 사라진다.** 선언이 정본 11개를 다 덮는 순간 코드가 `--only-domain` 을
붙이지 않으므로 exit 3 이 나올 수 없고, 그때부터 exit 3 은 **실패**로 처리된다(§3 테스트 6b 가
전체 게이트 exit 0 을 실증).

### 2.4 X-1 백도어가 닫힌 방식

판단 근거가 "디렉터리가 있는가"에서 "선언이 무엇을 요구하는가"로 옮겨졌다. 그래서
`parity/fixtures` 를 통째로 지워도 선언이 남아 있는 한 빨간불이다(§3 테스트 5). 초록으로
되돌리려면 선언 파일에서 도메인을 지워야 하고, 그러면 `parityManifestCheck` 가 "선언 X / 산출 O"
로 빌드를 깬다. 즉 **Kotlin parity 테스트까지 함께 지워야** 초록이 되며, 그것은 한 줄 수정이
아니라 눈에 띄는 코드 삭제 diff다.

---

## 3. 실증 결과

`run_ci_step.py` 로 ci.yml 의 step 을 그대로 실행한 결과다. Gradle 쪽은 `./gradlew` 직접 실행.

### CI 셸 단계

| # | 상태 | 종료 코드 | 결정적 로그 |
|---|---|---|---|
| 1 | **현재 상태** — 선언 0개, fixture 없음 | **0 (초록)** | `Kotlin 구현 선언 : 0개 — 없음` / `검증하지 않음 : 11개 — argon2 crypto export jwt masking …` / `::warning::parity 게이트 미가동` |
| 2 | **Phase 2 흉내** — 선언 masking·text, fixture 11개, 산출물 masking·text | **0 (초록)** | `[일치] masking — 14건` `[일치] text — 7건` / `::notice::부분 게이트 통과 — 선언한 2개 도메인만 값으로 대조했다` / `::warning::아직 검증하지 않은 도메인: argon2 crypto export jwt postprocess prompts repair-adoption style style-tables` |
| 3 | **선언했는데 산출물 없음** — masking·text 선언, text 산출물 삭제 | **1 (빨강)** | `::error::parity 부분 게이트 실패 (종료 코드 1)` |
| 4 | **값이 실제로 다를 때** — masking 산출물 한 건 변조 | **1 (빨강)** | `[불일치] … 불일치 1건` → `::error::` |
| 5 | **fixture 트리 삭제** (예전 백도어) | **1 (빨강)** | `::error::parity/fixtures 가 없는데 … 2개 도메인을 선언한다` |
| 6 | 11개 전부 선언, **역방향 산출물 없음** | **1 (빨강)** | 비교기 `(종료 코드 2)` → `::error::parity 전체 게이트 실패 (종료 코드 2)` |
| 6b | 11개 전부 선언 + 역방향 산출물까지 | **0 (초록)** | `[전체 게이트] 기대 도메인 11/11개 전부를 판정 범위에 넣었다` / `전건 일치: … 값 비교 101건 / 외부 검증 2건` / `parity 전체 게이트 통과` |
| 7 | 정본에 없는 도메인(`masking-v2`)을 선언 | **1 (빨강)** | `::error::… 정본에 없는 도메인이 있다: masking-v2` |
| 8 | 선언 파일 자체를 삭제 | **1 (빨강)** | `::error::… 선언이 사라졌다` |

테스트 3 과 6 이 요구 3의 핵심이다. **"구현했다고 선언한 도메인의 산출물이 없을 때" 두 형태
(값 파일 없음 → exit 1, 역방향 산출물 없음 → exit 2)가 모두 빨간불이다.** 사면이 삼키지 않는다.

테스트 2·6b 의 Kotlin 산출물은 Phase 2~4 구현이 아직 없으므로 **스탠드인**으로 만들었다
(`scratchpad/fake_kotlin_actual.py`, `fake_kotlin_external.py` — 저장소에 넣지 않았다).
값 동등성을 주장하는 것이 아니라 **CI 래퍼의 종료 코드 처리**를 재는 것이 목적이며, 역방향
산출물은 fixture 가 키·시크릿을 공개한다는 하네스 자신의 문서화된 구멍을 의도적으로 이용해
Python 으로 만들었다. 이 점은 §5 한계에 다시 적는다.

### Gradle 단계 (`parityManifestCheck`)

| # | 상태 | 결과 | 로그 |
|---|---|---|---|
| G1 | 선언 masking / 산출 없음 | **BUILD FAILED** | `[선언 O / 산출 X] masking — … parityHarness 가 parity/actual/masking 에 아무것도 쓰지 않았다` |
| G2 | 선언 없음 / masking 산출 | **BUILD FAILED** | `[선언 X / 산출 O] masking — … CI parity 비교가 이 도메인을 건너뛰므로 값이 달라도 드러나지 않는다` |
| G3 | 선언 masking / 산출 masking | **BUILD SUCCESSFUL** | `parity 선언 1개 전부 산출물 확인: masking` |
| G4 | 산출물 쓰는 테스트 삭제 후 재실행 | **BUILD FAILED** + `parity/actual` 비워짐 | `parityActualClean` 이 지난 산출물을 지웠음을 확인 |
| G5 | 선언 masking / 디렉터리만 있고 json 0건 | **BUILD FAILED** | `[산출물 0건] masking — 디렉터리는 있으나 json 이 하나도 없다` |
| G0 | **현재 상태** — 선언 0개, 산출 0개 | **BUILD SUCCESSFUL** | `parity 선언 0개 — … 게이트는 아무 값도 검증하지 않는다(= 통과가 아니라 '검증 대상 없음')` |

G1~G5 는 임시 `@Tag("parity")` 테스트를 넣어 재현한 뒤 삭제했다. 저장소에 남기지 않았다.

### 최종 상태 — CI 3단계 연속 실행 (실제 Phase 1 상태)

```
parity 산출물 생성 + 선언 대조 → BUILD SUCCESSFUL, exit 0
parity 하네스 배선 확인       → parity 하네스 배선 OK, exit 0
parity 비교 (선언한 도메인 범위) → 게이트 미가동 경고, exit 0
```

### 회귀

| 검사 | 결과 |
|---|---|
| `uv sync --locked` · `ruff check` · `ruff format --check` · `mypy` · `pytest` | **전부 통과** — 820 passed, 68 skipped, 4 deselected. `app/` 을 건드리지 않았다 |
| `./gradlew :core:build :infrastructure:build :worker:build :ktlintKotlinScriptCheck` | **BUILD SUCCESSFUL** (내가 고친 범위 전부) |
| `./gradlew test --rerun-tasks` | 전체 66건 중 `:infrastructure:test` 가 `PSQLException: SSL connection` 으로 실패 → **단독 재실행(`./gradlew :infrastructure:test --rerun-tasks`) BUILD SUCCESSFUL.** 병렬 실행 + Testcontainers 재사용 충돌로 판단. 내 변경과 무관(§5) |
| `./gradlew build` 전체 | **실패했으나 원인이 내 변경이 아니다** — `:api:detekt`(`support/ErrorProbeController.kt:114` CyclomaticComplexMethod)와 `:api:ktlintTestSourceSetCheck`(`FrameworkErrorContractTest.kt:159`). 둘 다 이번 지시에서 **열지 말라고 지정된** 타 에이전트 동시 작업 파일이다 |

**테스트 수가 48 → 66 으로 늘어난 것도 타 에이전트 작업이다.** 내 변경은 테스트를 추가·삭제하지
않았다(`ParityHarnessSelfCheck` 산출물의 필드 하나를 목록에서 포인터로 바꾼 것이 전부이며,
그 필드는 어떤 단언의 대상도 아니다).

---

## 4. 검토했으나 버린 대안

| 대안 | 버린 이유 |
|---|---|
| **exit 1 을 "결과 파일 없음"일 때만 사면** | 진짜 불일치와 같은 코드라 stdout 파싱으로만 구분된다. 종료 코드가 아닌 텍스트를 게이트 판정 근거로 삼는 순간, 비교기 메시지 한 줄만 바뀌어도 게이트가 조용히 열린다 |
| **`compare_parity.py` 에 `--allow-missing` 류 옵션 추가** | 사면 지식이 게이트 **안**으로 들어간다. 지금은 "무엇을 사면했는가"가 저장소 파일과 CI 로그에 남는데, 옵션이 되면 호출 한 줄로 숨는다. 게다가 이 지시의 수정 대상 밖 파일이다 |
| **`parityHarness` 산출물을 스캔해 그것만 비교** (선언 파일 없이) | 선언이 곧 산출이 되어 대조가 무의미해진다. Kotlin 쪽을 지우면 게이트가 조용히 좁아지는 X-1 과 같은 형태의 백도어가 한 층 아래에 다시 생긴다 |
| **선언 파일에 11개를 전부 적고 `implemented`/`pending` 상태를 붙임** | 도메인 목록이 두 벌이 된다. `compare_parity.py:114-116` 이 그 복제를 명시적으로 거부하고 있고, 무엇보다 **X-3(정본 manifest 를 BUILDERS 에서 분리할 것인가)이 리더 판정 대기 중**이라 그 판정을 앞질러 결정하게 된다. 그래서 선언은 **구현한 것만** 적는 부분집합으로 두고, 정본 전체 목록은 실행 시점에 `dump_parity_fixtures.py --list` 로 읽는다 |
| **선언을 Kotlin 상수로** (`ParityDomains.IMPLEMENTED`) | 선언과 산출이 같은 소스에서 나와 교차 확인이 공허해진다. Gradle 이 읽기도 어렵다 |
| **git 이력으로 선언 축소를 감지 (monotonicity 검사)** | base ref 확보가 트리거·fork PR 마다 달라 깨지기 쉽고, 정당한 축소(도메인 이름 변경 등)를 막을 방법이 없다. "축소하려면 Kotlin 테스트도 지워야 한다"는 구조적 결합으로 대신했다 |

---

## 5. 남은 한계

1. **GitHub Actions 러너 위에서 돌려 보지 않았다.** 로컬 재현은 `bash 3.2`(macOS)로 했고 러너는
   bash 5 다 — 배열·`mapfile` 을 쓰지 않아 양쪽에서 도는 코드로 썼지만, `::error::`/`::notice::`
   주석 렌더링과 `setup-uv` 캐시 상호작용은 **첫 push 에서 처음 검증된다.**
2. **테스트 2·6b 의 Kotlin 산출물은 Python 이 만든 스탠드인이다.** 하네스 자신이 문서화한
   "보장하지 못한다 (a)" 를 의도적으로 이용했다. 실제 Kotlin 구현으로 이 경로가 도는 것은
   Phase 2 첫 도메인에서 처음 확인된다. 다만 **이번에 검증하려던 것은 값이 아니라 종료 코드
   처리**이므로 이 대체는 목적에 부합한다.
3. **`parityManifestCheck` 는 도메인 입도까지만 본다.** "masking 산출물이 있다"는 보지만
   "masking 의 케이스 14건을 다 덮었다"는 보지 않는다 — 그것은 `compare_parity.py` 의 `미실행`
   판정이 하고, 실제로 잡는다(§1 상태 B). 따라서 X-5(§7.2)의 **모듈↔도메인 대응 단언**은
   여전히 열려 있다. 이번 수정은 X-5 의 도메인 층만 닫았다.
4. **`uv sync --locked` 를 kotlin 잡에 추가했다 (X-9).** 이번 지시의 대상 밖 항목이지만
   같은 파일 한 줄이고, 비교기가 Python 정본 생성기를 재실행하는 구조라 이 잡의 의존성이
   `quality` 잡과 다르면 "정본이 잡마다 달라진다". 별건으로 봐야 한다면 되돌리기 쉽다.
5. **`00_progress.md` 의 미결 원장 `P1-2` 행이 사실과 어긋나게 됐다.** "CI parity 비교 단계가
   종료 코드 2를 통과 처리한다 / 마감 Phase 4 종료 시"라고 적혀 있는데 그 완화는 이번에
   제거됐다. 지시가 "Phase 1 표의 담당 행만" 고치라고 했으므로 **손대지 않았다.** 리더가
   닫아 주기를 요청한다.
6. **Testcontainers 병렬 실행 flake 를 관찰했다.** `./gradlew test --rerun-tasks` 로 `:api:test`
   와 `:infrastructure:test` 가 동시에 돌면 `PSQLException: SSL connection` 으로 깨지고 단독
   실행은 통과한다. `org.gradle.parallel=true` + `testcontainers.reuse.enable=true` 조합이
   의심된다. **내 변경과 무관한 선재 현상**이며 CI(`--no-daemon`, 단일 실행)에서 재현될지는
   확인하지 못했다. 개선 후보로만 남긴다.

---

## 6. `compare_parity.py` 수정 제안 — **직접 고치지 않았다**

`.claude/skills/python-kotlin-parity/scripts/compare_parity.py` 는 이번 지시의 수정 대상이
아니므로 손대지 않았다. 이번 작업에서 **없어도 되지만 있으면 게이트가 더 정확해지는** 것 셋을
근거와 함께 올린다. 셋 다 지금 없다고 해서 위 설계가 성립하지 않는 것은 아니다.

**제안 1 (권고) — 부분 검증에서 "요청한 도메인의 fixture 가 없다"를 전용 메시지로.**
현재 `--only-domain X` 를 주고 `parity/fixtures` 가 디렉터리가 아니면 `parser.error` 로
`"--only-domain 은 --fixture 가 디렉터리일 때만 쓴다"` 가 나온다(실측 §3 테스트 5의 원래 형태).
종료 코드 1 은 맞지만 원인 진단이 어긋난다 — 실제 원인은 "정본 트리가 사라졌다"이다.
지금은 CI 쪽에서 `parity/fixtures` 존재를 먼저 확인해 이름을 붙여 막았으므로 **중복 방어**이며,
비교기 쪽이 고쳐지면 CI 의 그 가드는 지워도 된다.

**제안 2 (권고) — exit 3 을 낼 때 판정한 도메인 목록을 기계 판독 가능한 형태로.**
현재 부분 검증 요약은 사람이 읽는 한국어 문장이다. CI 는 지금 자기가 넘긴 `--only-domain`
목록을 알고 있으므로 문제가 없지만, 다른 호출자(에이전트·리포트 생성기)가 "무엇이 판정됐나"를
알려면 stdout 을 파싱해야 한다. `--report-json` 같은 출력이 있으면 §2.2 의 대조를 CI 셸이 아니라
데이터로 할 수 있다.

**제안 3 (판정 필요, X-3 과 묶임) — 정본 도메인 목록을 CLI 로 노출.**
지금 CI 는 정본 집합을 `dump_parity_fixtures.py --list | awk '{print $1}'` 로 읽는다. 공개 CLI 를
쓰므로 import 해킹은 아니지만, `--list` 는 사람이 읽는 표 형식이라 열 구성이 바뀌면 깨진다.
`--list --names-only` 처럼 이름만 내는 모드가 있으면 결합이 얇아진다. **다만 이것은 X-3
(정본 manifest 를 BUILDERS 에서 분리할 것인가) 판정에 영향을 받으므로**, 그 판정 전에 손대지
않기를 권한다.

---

## 7. Phase 2 착수자에게 — 한 줄 절차

도메인 하나를 포팅해 parity 산출물을 만들었으면, **`backend-kotlin/parity-domains.txt` 에 그
도메인 이름을 한 줄 추가한다.** 추가하지 않으면 `parityManifestCheck` 가 "선언 X / 산출 O" 로
빌드를 깨고, 먼저 추가하고 구현을 안 하면 "선언 O / 산출 X" 로 깬다. 두 방향 다 막혀 있으므로
**구현과 선언이 같은 커밋에 들어간다.**
