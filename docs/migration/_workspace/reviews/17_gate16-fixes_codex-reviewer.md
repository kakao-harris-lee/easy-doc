# 게이트 17 · 1단계 codex 독립 리뷰 — `17_gate16-fixes`

> **이 파일은 codex 원문 보존이 목적이다.** §3 은 무편집이고, §4 는 Claude 가 만든 색인이다.
> 옳고 그름·심각도 재판정·중복 병합은 하지 않는다 — 그것은 `migration-reviewer` 의 2차 교차
> 종합(`17_gate16-fixes_cross.md`)이 할 일이다.
>
> 어간 `17_gate16-fixes` 는 **리더가 1단계 호출에서 지정한 값**을 그대로 썼다(임의 슬러그 아님).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-18 23:39:11 ~ 23:53:46 (KST) · 소요 **14분 15초** |
| 모드 | `adversarial` (헬퍼 `adversarial-review`) |
| base / scope | `--base 1cb7bdf` (scope 무시 — branch diff) |
| 리뷰 대상 | `merge-base(HEAD,1cb7bdf)..HEAD` · **변경 파일 22개** |
| 스크립트 종료 코드 | **`0`** — 리뷰 근거가 되는 유일한 값 |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택) · 버전 **1.0.6** |
| codex CLI | `codex-cli 0.147.0` |
| job id | **`review-msyrqx2m-47bh4u`** |
| codex session ID | `01a01550-32ca-7710-bd82-8e03baad4e19` (`codex resume 01a01550-...` 로 재조회 가능) |
| verdict | **`needs-attention`** |
| codex 가 실행한 셸 명령 수 | **98건** (§3.3) |
| 원문 md5 | `63baf0bb110cef0f8bae73343897dca3` (4,694 bytes) |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 1cb7bdf
codex-review: 대상 판정 = non-empty (merge-base=1cb7bdf1a2a8, 변경 파일 22개 (branch 모드는 커밋된 변경만 센다))
```

`non-empty` 이므로 exit 7(리뷰 대상 0건)이 아니다 — 실제로 무언가를 리뷰했다.

### 1.2 실행 명령 전문

```bash
# 1) --dry-run 으로 헬퍼·대상·명령 확인 (exit 6)
FOCUS="$(cat <focus_17.txt>)"
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 1cb7bdf --focus "$FOCUS" --dry-run

# 2) 실제 실행 (exit 0)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 1cb7bdf --focus "$FOCUS"

# 스크립트가 헬퍼로 내보낸 실제 명령:
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
     adversarial-review --base 1cb7bdf '<focus text 전문 — §2>'

# 사후 회수 (재실행 아님):
node <헬퍼> status --all
node <헬퍼> result review-msyrqx2m-47bh4u
```

`result` 회수본과 캡처한 stdout 은 **session ID 꼬리 3줄을 빼면 완전 동일**했다(`diff` 확인) —
출력 잘림 없음.

### 1.3 제공한 맥락 목록

codex 에 **전사하지 않고 지목만** 했다(리더 지시 — 전사 금지). codex 가 직접 열어 읽었다.

- `docs/migration/_workspace/reviews/16_gate15-fixes_cross.md` — §3 교차 대조표(70~94행)의 행
  A·B·C·D·E·F·G·I·N·P·Q·T·W, §5(147행~), §6(270행~), §8(351행~)
- 커밋 해시 4개: `35e2d48` / `d0a5255` / `48a791c` / `dec9229`
- 옛 판 회수 경로: `git show 65a7eb6:<path>`
- 파일·라인 지목: `run_gate.sh`, `tests/test_run_gate.py`, `tests/test_parity_ci_gate.py:592`·`:475`·`:906~`·`:934`,
  `dump_python_snapshots.py`, `tests/test_python_snapshot_guard.py`, `.github/workflows/ci.yml:165`,
  `tests/test_harness_scope_reach.py`, `.claude/skills/kotlin-migration/SKILL.md` 규칙 5,
  `compare_parity.py` 호출선 9자리

### 1.4 민감 데이터

focus text 와 리뷰 대상에 **사용자 문서 본문·실제 암호문·키·개인정보 없음.** 대상은 전부 하네스
스크립트·테스트·CI 설정·리뷰 산출물이다. 합성 대체가 필요한 값도 없었다.

### 1.5 ⚠ 실행 중 HEAD 가 움직였다 (사실 기록 — 판정하지 않는다)

리뷰 착수 시각(23:39) HEAD 는 `48a791c` 였고, 종료 시각(23:53) HEAD 는 **`e577838`** 이다.
그 사이 다른 레인이 `e577838`(23:46:40)을 커밋했다.

- 헬퍼가 받은 `--base 1cb7bdf` 대상 판정은 **착수 시점**(HEAD=`48a791c`, 22개 파일)에 확정됐다.
- 그러나 codex 자신이 돌린 셸 명령 다수가 `1cb7bdf..HEAD` 형태였다(§3.3) — 23:46 이후 실행분은
  `e577838` 을 HEAD 로 봤을 수 있다.
- `48a791c..e577838` 의 변경 파일은 **`docs/migration/_workspace/00_progress.md` 하나**다.

이 사실을 그대로 남긴다. 영향 판정은 `migration-reviewer` 몫이다(§4.3 전제 확인 필요 3번).

---

## 2. 전달한 프롬프트 전문 (focus text)

축은 리더 지시대로 **5개로 제한**했다. Claude 가 이미 의심하는 지점은 넣지 않았고, 채점 기준
(범위 선언형·탐지형·장치 제거 시 외부 파손·표기≤도달·대리 지표 금지)만 단정문으로 줬다.

````text
[배경]
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 전환 중이고, 이 diff(1cb7bdf..48a791c)에는 Kotlin 제품 코드가 사실상 없다. 대상은 **전환을 판정하는 하네스 자신**(게이트 스크립트·게이트 테스트·CI 배선)이다. 하네스가 무력하면 이후의 모든 "통과" 기록이 근거를 잃으므로, 이 리뷰는 "코드가 괜찮은가"가 아니라 "이 장치가 실제로 무엇을 잡는가, 선언한 만큼 도달하는가"를 본다.

[먼저 읽을 것 — 전사돼 있지 않으니 직접 열어라]
- docs/migration/_workspace/reviews/16_gate15-fixes_cross.md — §3 교차 대조표(파일 70~94행)의 행 A·B·C·D·E·F·G·I·N·P·Q·T·W, 그리고 §5(147행~)·§6(270행~)·§8(351행~). 이 diff 는 그 행들을 닫으려는 수정이다.
- 이 배치의 코드·하네스 커밋은 넷이다: 35e2d48(Kotlin kdoc 서술 축소, 로직 변경 0) / d0a5255(run_gate.sh + tests/test_run_gate.py + SKILL.md 1줄 + ci.yml) / 48a791c(dump_python_snapshots.py + tests/test_python_snapshot_guard.py + tests/test_parity_ci_gate.py) / dec9229 의 tests/test_harness_scope_reach.py 상수 1개. 나머지 커밋은 문서다.
- 옛 판 비교가 필요하면 `git show <hash>:<path>` 로 읽어라 — 예: `git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh`.

[지켜야 하는 조건 — 채점 기준]
1. 범위 선언형 장치는 **빈 선언에서 통과하면 안 된다**. 하한·목록·기준이 비었을 때 "위반 0건"으로 초록이 되는 구조는 결함이다.
2. 장치는 **탐지형**이어야 한다. 면제 목록·무시 패턴·억제 조항으로 자라는 은폐형은 같은 사고를 보이지 않게 만든다.
3. 장치를 떼면 **장치 밖에서 무언가 깨져야 한다**. 자기 파일 안의 자기 단언은 파일과 함께 사라진다.
4. "전역/모든/항상" 표기는 **실제 도달을 넘지 못한다**. 표기가 장치보다 크면 그 자체가 결함이다.
5. 성공/실패를 **대리 지표**로 읽으면 안 된다 — 종료 코드 0 을 "검토했다"로, 테스트 통과를 "그 경로가 돌았다"로 바꿔 읽는 자리.

[축 — 다섯 개만 본다. 다른 것을 발견해도 이 다섯을 먼저 소진하라]

① 음성 대조 재현 (가능하면 **직접 실행**하라)
직전 회차에서는 샌드박스가 `git worktree` 를 거부해 이 축이 미수행으로 끝났다. 이번엔 우회하라 — (a) `git show <hash>:<path>` 로 옛 판 내용을 읽어 저장소 밖 쓰기 가능 경로에 놓고 돌리거나, (b) 파이썬으로 모듈 소스를 메모리에서 변형해 import 하거나(importlib), (c) 그마저 막히면 **어떤 명령이 어떤 오류로 막혔는지 원문 그대로** 적어라. 추정으로 "재현했다"고 쓰지 마라.
재현 대상 셋:
(1) run_gate.sh 옛 판(65a7eb6)에 대해 tests/test_run_gate.py 를 돌리면 4건이 실패하고, 새 판(48a791c)에서는 12건이 통과한다 — 사실인가? 옛 판이 통과해 버리는 케이스가 섞여 있지 않은가?
(2) .claude/skills/python-kotlin-parity/scripts/compare_parity.py 의 본류 helper 호출선 9자리(:1984, :2282, :2279, :1207, :2269, :2274, :2329, :2344, :2409)를 **각각 한 줄씩 제거**했을 때 tests/test_parity_ci_gate.py 의 대응 테스트가 실제로 빨개지는가. 최소한 :2274 와 :2279 두 자리는 확인하라 — 전자는 직전 회차에 "지워도 전체 스위트가 1191 passed 로 초록이었다"가 실측된 자리고, 후자는 이번 자동 열거가 새로 잡은 아홉째다.
(3) .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py 의 PROMPT_CASE_FLOOR 에서 임의 섹션의 하한을 **비우면** `--check` 가 exit 2 로 끝나는가. 섹션 키째로 지우면 어떻게 되는가 — 그때도 무언가 빨개지는가, 아니면 조용히 통과하는가?

② run_gate.sh 새 판의 표기 정직성 (이 축은 직전 회차에 독립 관점이 없었다)
.claude/skills/kotlin-migration/scripts/run_gate.sh — 빈/공백 인자 exit 2, 인자 정확히 1개 계약(`$# -ne 1` → exit 2), 그리고 머리 주석이 자기 도달을 (1)(2)(3) 세 상태로 적은 것(특히 (3) "러너 밖 파이프는 못 잡는다")이 **정직한가 — 표기가 장치보다 크지 않은가**. 우회 경로를 찾아라: 인자가 비지 않았는데 아무것도 실행되지 않는 입력, exit 2 가 아닌 코드로 새는 입력, 종료 코드가 명령의 것이 아니게 되는 입력, `set -u`·case 패턴 `*[![:space:]]*`·`bash -o pipefail -c` 조합이 셸/로케일/bash 판이 다를 때 달라지는 동작(zsh 호출·sh 호출·bash 3.2 vs 5.x). tests/test_run_gate.py 가 그 계약 전부를 고정하는가, 아니면 통과하는 것만 고정하는가.

③ _MAINLINE_HELPERS 자동 열거의 계약
tests/test_parity_ci_gate.py:592(표)와 :906~(완전성 테스트). 물을 것:
- 이 자동 열거가 **빈 선언에서 통과**하는가? 비교기 모듈에 `_problem(s)`/`_additions` 이름의 함수가 0 개가 되면(개명·삭제·모듈 교체·import 실패) 이 테스트는 빨개지는가, 아니면 "발견 0 == 누락 0" 으로 초록인가?
- 이름 규약 수집(endswith)이 규약을 벗어난 helper 를 놓치는 것을 테스트가 스스로 인정하는데, 그 인정이 **면제로 자라는 구조**인가? 특히 중첩 helper 제외 목록 `nested = {"reference_problems", "verdict_pending_problems"}`(:934 부근)이 **탐지형**인가 **은폐형(면제 목록)**인가 — 새 중첩 helper 가 생겼을 때 이 목록에 한 줄 더하는 것으로 조용히 회귀를 면제받는 경로가 있는가?
- `_mainline_tree`(:475)의 상태 인자화(case_floor, full_gate_mark, declared_floor, ledger, actual_from_reference 등)가 직전 결함(전체 게이트 하한이 **항상 통과**해 full_gate_floor_problems 호출선이 영구히 보이지 않던 것)을 실제로 걷었는가? 여전히 어떤 helper 의 호출선이 **어떤 인자 조합에서도** 드러나지 않는 자리가 있는가?
- 표의 판정 문구(_MAINLINE_HELPERS 값의 두 번째 원소)가 실제 판정 문구와 갈리면 테스트가 잡는가?

④ 하한 비대칭 설계
dump_python_snapshots.py 의 `--check` 는 **하한 ⊆ 실물**만 강제한다(하한에 적힌 케이스가 실물에 없으면 실패). 반대 방향 **실물 ⊆ 하한**(실물에 새 케이스가 생겼는데 하한에 없음)은 `--check` 가 exit 0 이고 tests/test_python_snapshot_guard.py 만 잡는다. 물을 것: 이 비대칭이 CI 도달에서 구멍이 되는가? tests/test_python_snapshot_guard.py 는 .github/workflows/ci.yml:165 경로 명시 스텝에 실려 있다 — **실재를 확인하고**, 그 스텝이 지워지거나 파일이 사라졌을 때 무엇이 깨지는지 짚어라. 하한을 늘리지 않고 케이스를 추가하는 워크플로가 실제로 통과하는지 확인하라.

⑤ 도달 범위 대조
이 배치가 새로 선언한 "전역/모든/항상" 류 표기를 찾아 실제 도달과 대조하라. 최소한: ci.yml 의 "경로 명시" 스텝 **총수**와 각 스텝이 가리키는 파일의 실재(현재 파일에서 직접 세라 — 전달받은 숫자를 믿지 마라), tests/test_harness_scope_reach.py 의 EXPECTED_REACH_TOKENS 52→54 변경이 근거를 넘지 않는지, .claude/skills/kotlin-migration/SKILL.md 규칙 5 의 새 문장이 run_gate.sh 머리 주석과 **갈리지 않는지**(두 곳이 같은 계약을 다르게 적으면 그 자체가 드리프트다), 그리고 이 배치가 **은폐형(면제 목록·무시 패턴·억제)**을 새로 들인 자리가 있는지.

[출력 요구]
- 각 지적에 **파일:라인**과 심각도(critical/high/medium/low)를 붙여라.
- 실행해서 확인한 것과 코드를 읽어 추론한 것을 **명시적으로 구분**하라. 실행했으면 명령과 출력 요지를 적어라.
- 축 ① 에서 재현하지 못한 것이 있으면 "미수행 — 막힌 명령과 오류"로 남겨라. 재현하지 않은 것을 재현한 것처럼 쓰지 마라.
- 지적할 것이 없는 축은 "지적 없음"이라고 적어라. 채우지 마라.
````

---

## 3. codex 원문 출력 (무편집)

> **아래 §3.1 은 codex 가 낸 최종 출력 전문이다. 한 글자도 고치지 않았다.**
> Claude 가 쓴 문장은 이 구획에 하나도 없다.

### 3.1 최종 출력 전문

````text
# Codex Adversarial Review

Target: branch diff against 1cb7bdf
Verdict: needs-attention

No-ship: 하네스에 false-green 경로 3건이 남아 있다. 실행 확인: 새 run_gate 테스트는 12 passed. 구판은 /dev/stdin 우회로 4개 계약 테스트가 실패했고, 별도 git cat-file/bash -n은 성공해 경로 우회로 인한 추가 실패를 배제했다. compare_parity 호출선 9개(:1207, :1984, :2269, :2274, :2279, :2282, :2329, :2344, :2409)는 메모리 변이에서 각각 대응 문구가 사라져 모두 red였다. PROMPT_CASE_FLOOR의 빈 값과 키 삭제는 각각 exit 2였고, 미등재 고유 이름은 생성기 exit 0이나 가드가 red였다. 도달 대조는 경로 명시 스텝 5개와 대상 파일 5개가 모두 실재했고 test_harness_scope_reach.py는 37 passed였으며, 52→54와 SKILL/run_gate 범위 표기는 근거와 일치해 추가 지적 없음.

Findings:
- [high] 원문이 비어 있지 않아도 확장 후 명령 0건이면 성공한다 (.claude/skills/kotlin-migration/scripts/run_gate.sh:63-75)
  실행 확인: `env -u GATE_CMD bash .../run_gate.sh '$GATE_CMD'`, 주석만 있는 문자열, 백슬래시-개행 문자열이 모두 `[run_gate] exit: 0`으로 끝났다. bash·sh·zsh의 주석 입력도 동일했다. 현재 case 검사는 bash가 해석하기 전 원문의 공백 여부만 보므로, 미설정 변수처럼 실행 시 빈 명령으로 축약되는 입력은 게이트를 한 건도 돌리지 않고 성공한다. 반면 공백 입력은 2, `exit 4`는 4, 내부 실패 파이프는 1로 정상 전파됐다. 따라서 이 false-green은 종료 코드 전파가 아니라 zero-work 탐지 누락이다.
  Recommendation: 실행된 유효 명령이 최소 1개였다는 witness를 자식 bash에서 수집해 없으면 실패시키거나, 전체 명령을 변수 확장에 위임하는 형태와 주석·line-continuation-only 입력을 명시적으로 거부하라. 세 입력을 tests/test_run_gate.py에 음성 회귀로 추가하라.
- [high] helper 완전성 검사가 기존 테스트 재사용과 한 줄 면제를 승인한다 (tests/test_parity_ci_gate.py:932-953)
  실행 확인: 비교기 모듈에 `future_problems`를 추가하고 표에서 기존 runtime 테스트를 재사용하자 완전성 검사가 PASS했다. `nested` 집합에 그 이름을 한 줄 추가한 변이도 표와 테스트가 전혀 없이 PASS했다. 판정 문구를 bogus 값으로 바꿔도 완전성 검사는 PASS했다. 원인은 이 검사가 테스트 이름의 존재만 확인하고 helper와 테스트·판정 문구의 결속이나 테스트 이름의 유일성을 검사하지 않으며, `nested`를 검증 없는 제외 목록으로 빼기 때문이다. 따라서 이후 본류 helper가 실제 호출돼도 기존 테스트 이름을 복사하거나 제외 목록을 늘려 회귀 없이 '전부를 덮는다'고 만들 수 있다.
  Recommendation: `nested` 면제를 제거하고 중첩 호출선도 명시적 회귀 대상으로 만들라. helper별 고유 테스트를 강제하고, 표의 문구를 해당 양성 단언에서 직접 소비하라. 가장 강한 방법은 AST로 본류 호출선을 열거해 각 호출 제거 변이가 지정 테스트를 실패시키는 매개변수화 검사를 두는 것이다.
- [high] 실물 케이스 이름 중복이 하한 동등 검사를 통과한다 (tests/test_python_snapshot_guard.py:204-221)
  실행 확인: postprocess에 기존 이름을 재사용하되 `raw`와 `expected`가 다른 새 케이스를 메모리로 추가하자 `dump_python_snapshots.py --check`가 exit 0이었고 `test_하한이_실물_케이스_이름과_섹션마다_같다`도 PASS했다. 검사는 하한 튜플의 중복만 거부하고 실물 이름을 set으로 접어 비교한다. 따라서 복사·추가된 고유 동작 케이스가 기존 이름을 재사용하면 보호되는 것처럼 보이지만, 두 케이스 중 하나를 나중에 삭제해도 같은 이름 하나가 남아 하한과 가드가 모두 조용하다. 케이스 수를 부풀리면서 큐레이션 입력을 영구 손실할 수 있는 정체성 우회다.
  Recommendation: 각 섹션에서 실물 이름의 유일성을 먼저 강제하고 `len(actual_names) == len(document[section])`를 검사하라. 생성기의 `_floor_checked_cases`도 set을 만들기 전에 중복 이름을 SnapshotError로 거부하고, 서로 다른 입력이 같은 이름을 쓰는 음성 회귀를 추가하라.

Next steps:
- 세 blocker를 고친 뒤 동일한 zero-work, fake-helper/exemption, duplicate-name 변이를 다시 실행한다.
- CI의 경로 명시 5개와 전체 pytest를 함께 재검증한다.
````

### 3.2 codex 중간 진행 메시지 (무편집 — **헬퍼가 잘라서 기록했다**)

헬퍼가 stderr 에 남긴 진행 메시지는 **헬퍼 자신이 `...` 로 절단**한다. 잘린 상태 그대로 옮긴다 —
추측으로 이어붙이지 않는다. 전문 복구는 시도했으나 실패했다(§5.2).

````text
[codex] Starting Codex task thread.
[codex] Thread ready (01a01550-32ca-7710-bd82-8e03baad4e19).
[codex] Turn started (01a01550-3436-7fc0-9c1e-c0eb86c4f6e4).

[1] {"verdict":"needs-attention","summary":"리뷰를 시작합니다. easy-doc 패리티 스톱게이트의 기존 검증 규약이 이번 하네스 리뷰와 직...

[2] {"verdict":"needs-attention","summary":"정적 대조에서 핵심 위험 후보가 두 군데로 좁혀졌습니다: `_MAINLINE_HELPERS`의 ...

[3] {"verdict":"needs-attention","summary":"`run_gate.sh`의 새 판은 12/12 통과했지만, 실측상 `'$GATE_CMD'`가 미...

[4] {"verdict":"needs-attention","summary":"No-ship: 하네스에 false-green 경로 3건이 남아 있다. 실행 확인: 새 run_...
````

### 3.3 codex 가 실행한 셸 명령 98건 (무편집 — **헬퍼가 잘라서 기록했다**)

이 목록이 축 ① 의 **실행 증거**다. 헬퍼가 각 줄을 절단해 기록하므로 잘린 채로 옮긴다.
codex 는 저장소에 쓰기를 하지 않았다 — 리뷰 종료 후 `git status --porcelain` 는 리뷰 착수
전과 동일(untracked 3건뿐), `git worktree list` 는 본 저장소 하나뿐이었다.

````text
/bin/zsh -lc 'rg -n "easy-doc.*parity|parity-stop-gate|python snapshot|run_gate|16_gate15|_MA...
/bin/zsh -lc "sed -n '243,338p' /Users/harris/.codex/memories/MEMORY.md && wc -l /Users/harri...
/bin/zsh -lc 'git status --short && git rev-parse HEAD && git diff --name-status 1cb7bdf..HEA...
/bin/zsh -lc "nl -ba docs/migration/_workspace/reviews/16_gate15-fixes_cross.md | sed -n '60,...
/bin/zsh -lc 'git diff --unified=60 1cb7bdf..HEAD -- .claude/skills/migration-safety-gate/scr...
/bin/zsh -lc 'git diff --unified=80 1cb7bdf..HEAD -- .claude/skills/kotlin-migration/scripts/...
/bin/zsh -lc "nl -ba .claude/skills/kotlin-migration/scripts/run_gate.sh && nl -ba tests/test...
/bin/zsh -lc 'git show --stat --oneline dec9229 && git show --unified=30 dec9229 -- tests/tes...
/bin/zsh -lc "nl -ba docs/migration/_workspace/reviews/16_gate15-fixes_cross.md | sed -n '351...
/bin/zsh -lc "nl -ba docs/migration/_workspace/reviews/16_gate15-fixes_cross.md | sed -n '147...
/bin/zsh -lc "nl -ba tests/test_python_snapshot_guard.py | sed -n '1,310p'"
/bin/zsh -lc "nl -ba .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py | ...
/bin/zsh -lc 'rg -n -C 6 "규칙 5|run_gate|파이프" .claude/skills/kotlin-migration/SKILL.md'
/bin/zsh -lc "nl -ba .github/workflows/ci.yml | sed -n '1,260p'"
/bin/zsh -lc "rg -n '"'^def .*(_problem|_problems|_additions)'"\\(' .claude/skills/python-kot...
/bin/zsh -lc "nl -ba tests/test_parity_ci_gate.py | sed -n '430,1020p'"
/bin/zsh -lc 'for n in 1207 1984 2269 2274 2279 2282 2329 2344 2409; do sed -n "$((n-8)),$((n...
/bin/zsh -lc "git diff --unified=20 1cb7bdf..HEAD -- tests/test_parity_ci_gate.py | sed -n '1...
/bin/zsh -lc "nl -ba tests/test_parity_ci_gate.py | sed -n '860,1000p'"
/bin/zsh -lc "nl -ba tests/test_parity_ci_gate.py | sed -n '584,860p'"
/bin/zsh -lc "nl -ba .claude/skills/python-kotlin-parity/scripts/compare_parity.py | sed -n '...
/bin/zsh -lc 'bash --version | head -1 && zsh --version && locale'
/bin/zsh -lc 'uv run pytest -q -p no:cacheprovider tests/test_python_snapshot_guard.py'
/bin/zsh -lc 'uv run pytest -q -p no:cacheprovider tests/test_run_gate.py'
/bin/zsh -lc 'uv run pytest -q -p no:cacheprovider tests/test_parity_ci_gate.py'
/bin/zsh -lc "ls -l .venv/bin/python .venv/bin/pytest .venv/bin/ruff 2>/dev/null || true && ....
/bin/zsh -lc '.venv/bin/pytest -q -p no:cacheprovider tests/test_python_snapshot_guard.py'
/bin/zsh -lc '.venv/bin/pytest -q -p no:cacheprovider tests/test_run_gate.py'
/bin/zsh -lc '.venv/bin/pytest -q -p no:cacheprovider tests/test_parity_ci_gate.py'
/bin/zsh -lc 'git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh && git sho...
/bin/zsh -lc '.venv/bin/pytest -s -q -p no:cacheprovider tests/test_run_gate.py'
/bin/zsh -lc ".venv/bin/pytest -s -q -p no:cacheprovider 'tests/test_parity_ci_gate.py::test_...
/bin/zsh -lc ".venv/bin/pytest -s -q -p no:cacheprovider 'tests/test_python_snapshot_guard.py...
/bin/zsh -lc "RUN_GATE_PATH=<(git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_ga...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc "git show 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh | RUN_GATE...
/bin/zsh -lc 'git cat-file -e 65a7eb6:.claude/skills/kotlin-migration/scripts/run_gate.sh && ...
/bin/bash -c 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/bash -c 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/bash -c 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/bash -c 'sh .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/bash -c 'zsh .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/bash -c 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/bash -c 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/bash -c 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh "$1"'
/bin/zsh -lc "bash .claude/skills/kotlin-migration/scripts/run_gate.sh '# only a comment'"
/bin/zsh -lc "zsh .claude/skills/kotlin-migration/scripts/run_gate.sh '# only a comment'"
/bin/zsh -lc "bash .claude/skills/kotlin-migration/scripts/run_gate.sh ':'"
/bin/zsh -lc "bash .claude/skills/kotlin-migration/scripts/run_gate.sh 'exit 4'"
/bin/zsh -lc "bash .claude/skills/kotlin-migration/scripts/run_gate.sh 'false | tail -1'"
/bin/zsh -lc 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh $'"'\\\\\\n'"
/bin/zsh -lc "sh .claude/skills/kotlin-migration/scripts/run_gate.sh '# only a comment'"
/bin/zsh -lc 'bash .claude/skills/kotlin-migration/scripts/run_gate.sh $'"' \\t\\n '"
/bin/zsh -lc "env -u GATE_CMD bash .claude/skills/kotlin-migration/scripts/run_gate.sh '"'$GA...
/bin/zsh -lc "GATE_CMD='false' bash .claude/skills/kotlin-migration/scripts/run_gate.sh '"'$G...
/bin/zsh -lc 'LC_ALL=ko_KR.UTF-8 bash .claude/skills/kotlin-migration/scripts/run_gate.sh $'"...
/bin/zsh -lc 'LC_ALL=C bash .claude/skills/kotlin-migration/scripts/run_gate.sh $'"'\\f'"
/bin/zsh -lc ".venv/bin/python -c 'import importlib.util; print(importlib.util.find_spec(\"py...
/bin/zsh -lc "rg -n '"'^def (main|discover|load|ledger_file|load_ledger|write_ledger|read_flo...
/bin/zsh -lc "nl -ba tests/test_parity_ci_gate.py | sed -n '1,180p;360,440p' && nl -ba .claud...
/bin/zsh -lc "rg -n '\\.(parts|relative_to|is_relative_to|suffix|stem|with_|rglob|glob|read_t...
/bin/zsh -lc "nl -ba .claude/skills/python-kotlin-parity/scripts/compare_parity.py | sed -n '...
/bin/bash -c ".venv/bin/python -c 'from __future__ import annotations import contextlib, copy...
/bin/bash -c ".venv/bin/python -c 'from __future__ import annotations import contextlib, copy...
/bin/zsh -lc "rg -n '"'^def _load|@pytest.fixture.*comparer|def comparer'"' tests/test_parity...
/bin/zsh -lc "nl -ba tests/test_parity_ci_gate.py | sed -n '220,260p'"
/bin/bash -c ".venv/bin/python -c 'import importlib.util, sys, types from pathlib import Path...
/bin/bash -c ".venv/bin/python -c 'import importlib.util, sys, types from pathlib import Path...
/bin/bash -c ".venv/bin/python -c 'import sys, types from pathlib import Path path = Path('\"...
/bin/zsh -lc "nl -ba .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py | ...
/bin/bash -c ".venv/bin/python -c 'import contextlib, copy, importlib.util, io, json, sys fro...
/bin/zsh -lc "rg -n 'EXPECTED_REACH_TOKENS|ci:quality|프롬프트 렌더링|스타일 규칙 포팅' tests/test_harness_...
/bin/zsh -lc "rg -n 'name: .*경로 명시|run: uv run pytest tests/' .github/workflows/ci.yml"
/bin/zsh -lc 'git diff --unified=15 1cb7bdf..HEAD -- .github/workflows/ci.yml tests/test_harn...
/bin/zsh -lc "rg -n 'test_python_snapshot_guard|스냅샷 가드 회귀 실재 확인|경로 명시' --glob '"'!docs/migrat...
/bin/bash -c ".venv/bin/python -c 'import contextlib, copy, importlib.util, io, json, sys fro...
/bin/bash -c ".venv/bin/python -c 'import contextlib, copy, importlib.util, io, json, sys fro...
/bin/zsh -lc "nl -ba tests/test_harness_scope_reach.py | sed -n '1030,1080p;1740,1800p' && .v...
/bin/zsh -lc 'for p in tests/test_harness_scope_reach.py tests/test_privacy_scanner.py tests/...
/bin/zsh -lc "git diff --unified=3 1cb7bdf..HEAD -- .claude/skills/kotlin-migration/SKILL.md ...
/bin/zsh -lc "git diff --unified=0 1cb7bdf..HEAD -- . ':"'!docs/migration/_workspace/reviews/...
/bin/zsh -lc '.venv/bin/pytest -s -q --tb=no -p no:cacheprovider tests/definitely_missing_sna...
/bin/zsh -lc "nl -ba backend-kotlin/api/src/test/kotlin/kr/easydoc/api/FrameworkErrorContract...
/bin/zsh -lc "nl -ba backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/...
/bin/zsh -lc 'git diff --unified=20 1cb7bdf..HEAD -- backend-kotlin/api/src/test/kotlin/kr/ea...
/bin/zsh -lc 'git show --stat --oneline 35e2d48 d0a5255 48a791c && git show --name-status --o...
/bin/zsh -lc "nl -ba /Users/harris/.codex/memories/MEMORY.md | sed -n '300,338p' && nl -ba /U...
````

---

## 4. 정리 (가공) — 색인

> **여기서부터는 Claude 가 만든 색인이다.** 옳고 그름은 판정하지 않는다. codex 가 무엇을
> 어디에 지적했는지 찾기 쉽게 늘어놓기만 한다. 심각도는 **codex 가 붙인 값 그대로**다.

### 4.1 지적 3건 색인

| # | codex 심각도 | 지적 (codex 표현 요약 — 원문은 §3.1) | 근거 위치 (codex 표기 그대로) | 실행 확인 여부 (codex 자기 표기) |
|---|---|---|---|---|
| **X17-1** | `high` | 원문이 비어 있지 않아도 **확장 후 명령 0건이면 성공**한다 | `.claude/skills/kotlin-migration/scripts/run_gate.sh:63-75` | 실행 확인 — `env -u GATE_CMD ... '$GATE_CMD'`, 주석만 있는 문자열, 백슬래시-개행 문자열이 모두 `[run_gate] exit: 0` |
| **X17-2** | `high` | helper 완전성 검사가 **기존 테스트 재사용과 한 줄 면제를 승인**한다 | `tests/test_parity_ci_gate.py:932-953` | 실행 확인 — `future_problems` 추가 후 기존 테스트 재사용 시 PASS / `nested` 한 줄 추가 변이도 PASS / 판정 문구 bogus 변경도 PASS |
| **X17-3** | `high` | 실물 **케이스 이름 중복**이 하한 동등 검사를 통과한다 | `tests/test_python_snapshot_guard.py:204-221` | 실행 확인 — 이름 재사용·내용 다른 케이스 추가 시 `--check` exit 0 이고 `test_하한이_실물_케이스_이름과_섹션마다_같다` PASS |

codex 가 각 항목에 붙인 `Recommendation` 은 §3.1 원문에 있다. **여기 옮겨 적지 않는다** —
권고를 색인에 이중 기재하면 원문과 갈릴 여지가 생긴다.

### 4.2 리뷰 축별 도달 — 무엇에 답이 왔고 무엇에 안 왔는가

| 축 | 요청한 것 | codex 응답 상태 |
|---|---|---|
| **①** 음성 대조 재현 | 3종 재현 (run_gate 옛/새 판 · compare_parity 9자리 · 하한 비우기) | **전부 실행됨** — §4.4 |
| **②** T-E / `run_gate.sh` 표기 정직성 | 빈·공백 인자 exit 2, `$# -ne 1`, ⑶ 표기 정직성 | **응답 — 지적 1건(X17-1)**. codex 는 공백 입력 `2`, `exit 4` → `4`, 내부 실패 파이프 → `1` 이 "정상 전파"됐다고 적고, 남은 구멍을 "종료 코드 전파가 아니라 **zero-work 탐지 누락**"으로 규정했다 |
| **③** `_MAINLINE_HELPERS` 자동 열거 계약 | 빈 선언 통과 여부 · `nested` 면제 성격 · `_mainline_tree` 상태 인자화 | **응답 — 지적 1건(X17-2)**. `nested` 를 "검증 없는 제외 목록"으로 규정. **`_mainline_tree` 상태 인자화가 Z-b 를 걷었는지에 대한 명시적 판정 문장은 최종 출력에 없다** — 다만 9자리 변이가 전부 red 였다는 실측은 요약에 있다 |
| **④** 하한 비대칭 설계 | 실물⊆하한 방향의 CI 도달 구멍 | **응답 — 지적 1건(X17-3)**. 요약에 "미등재 고유 이름은 생성기 exit 0 이나 **가드가 red**" — 즉 요청한 비대칭 자체는 가드가 덮는다고 적었고, 그와 별개로 **이름 중복** 우회를 새로 냈다 |
| **⑤** 도달 범위 대조 | "경로 명시" 스텝 총수·실재 · `EXPECTED_REACH_TOKENS` 52→54 · SKILL↔run_gate 드리프트 · 은폐형 신규 유입 | **응답 — 지적 없음.** codex 표현: "경로 명시 스텝 5개와 대상 파일 5개가 모두 실재했고 `test_harness_scope_reach.py` 는 37 passed 였으며, 52→54와 SKILL/run_gate 범위 표기는 근거와 일치해 **추가 지적 없음**" |

**축 ⑤ 는 "지적 없음"으로 그대로 기록한다.** Claude 가 대신 지적을 만들어 채우지 않는다(스킬 §7).

### 4.3 전제 확인 필요 — `migration-reviewer` 가 코드로 대조할 것

> codex 출력이 사실과 다른 전제에 기반한 것으로 **보여도 삭제하지 않는다**(스킬 §7 / 에이전트
> 에러 핸들링). 아래는 "지워야 할 것" 목록이 아니라 **"대조가 필요한 것"** 목록이다.

1. **X17-1 의 `'$GATE_CMD'` 입력이 이 저장소의 실제 사용 형태인가.** codex 는 미설정 변수·주석
   전용·백슬래시-개행 세 입력을 들었다. SKILL.md 규칙 5 가 규정한 호출 형태와 이 셋이 겹치는
   범위를 대조할 것.
2. **X17-2 의 "기존 테스트 재사용 시 PASS"** — 완전성 테스트가 `test_name in module_tests` 만
   보는지, 그리고 `nested` 집합 한 줄 추가가 실제로 회귀 없이 통과하는지 코드로 확인할 것.
3. **HEAD 이동(§1.5).** codex 의 `1cb7bdf..HEAD` 명령 일부가 `e577838` 기준으로 돌았을 수 있다.
   `48a791c..e577838` 의 변경은 `00_progress.md` 하나이며, codex 지적 3건의 대상 파일
   (`run_gate.sh` · `test_parity_ci_gate.py` · `test_python_snapshot_guard.py`)은 그 범위에
   포함되지 않는다 — **이 대조 결과 자체는 사실 기록이고, 영향 판정은 하지 않았다.**
4. **codex 요약의 "구판은 `/dev/stdin` 우회로 4개 계약 테스트가 실패했다"** — 리더가 준 전제는
   "옛 판 4 failed / 새 판 12 passed" 였다. codex 는 옛 판을 파일로 놓지 못해 `/dev/stdin` 경유로
   돌렸다고 적었고, "별도 `git cat-file`/`bash -n` 은 성공해 경로 우회로 인한 추가 실패를
   배제했다"고 덧붙였다. **우회 실행이 4건 실패의 원인이 아님을 codex 스스로 배제했다고 적은
   것**이므로, 그 배제가 충분한지는 대조 대상이다.
5. **codex 가 `.venv/bin/pytest` 와 `uv run pytest` 를 모두 시도했다**(§3.3). 두 경로의 결과가
   갈렸는지 최종 출력에 언급이 없다.
6. **`tests/definitely_missing_sna...`** 를 대상으로 한 pytest 호출이 §3.3 에 있다(존재하지 않는
   경로로 보인다 — 아마 "수집 0건일 때 어떻게 끝나는가" 프로브). 최종 출력에 이 실험의 결과
   서술이 없다.

### 4.4 축 ① 재현 결과 — codex 자기 보고 (원문 §3.1 요약문에서 발췌, 판정 없음)

| 재현 대상 | codex 가 적은 결과 |
|---|---|
| `run_gate.sh` 새 판 + `tests/test_run_gate.py` | **12 passed** |
| `run_gate.sh` 옛 판(`65a7eb6`) + 같은 테스트 | **4개 계약 테스트 실패** (`/dev/stdin` 우회 실행) |
| `compare_parity.py` 호출선 9자리 (`:1207 :1984 :2269 :2274 :2279 :2282 :2329 :2344 :2409`) | **메모리 변이에서 각각 대응 문구가 사라져 모두 red** |
| `PROMPT_CASE_FLOOR` 빈 값 / 키 삭제 | **각각 exit 2** |
| 하한 미등재 고유 이름 케이스 추가 | **생성기 exit 0 이나 가드가 red** |
| `ci.yml` 경로 명시 스텝 | **5개 · 대상 파일 5개 모두 실재** |
| `tests/test_harness_scope_reach.py` | **37 passed** |

---

## 5. 미실행·실패 항목

### 5.1 codex 호출 실패·재시도

**없음.** 1회 호출로 exit 0. 재시도하지 않았다. ⚠ "codex 리뷰 누락" 해당 없음.

### 5.2 전문을 회수하지 못한 것 — 중간 진행 메시지 4건

헬퍼가 stderr 에 진행 메시지를 `...` 로 **절단해** 기록한다(§3.2). 전문 복구를 시도했으나 실패:

- `find ~/.codex/sessions -name "*01a01550-32ca-7710-bd82-8e03baad4e19*"` → **결과 없음**
- `grep -rl "01a01550-32ca-7710" ~/.codex/` → **결과 없음** (세션이 sqlite 에 들어간 것으로 보인다)

**최종 출력(§3.1)은 잘리지 않았다** — `result <job-id>` 회수본과 byte 단위로 일치(§1.2). 잘린 것은
중간 진행 메시지뿐이다.

### 5.3 codex 가 답하지 않은 것

- 축 ③ 의 네 번째 질문(**표의 판정 문구가 실제 판정 문구와 갈리면 테스트가 잡는가**)에 대해
  codex 는 X17-2 안에서 "판정 문구를 bogus 값으로 바꿔도 완전성 검사는 PASS했다"고 적었다.
  이는 답변으로 볼 수 있으나 **독립 항목으로 세우지는 않았다.**
- 축 ③ 의 `_mainline_tree` 상태 인자화가 **Z-b(전체 게이트 하한 항상 통과)를 실제로 걷었는가**에
  대한 명시적 판정 문장은 최종 출력에 없다(§4.2).
- 축 ⑤ 의 **은폐형 신규 유입** 질문에 대해 별도 문장 없이 "추가 지적 없음"에 포함됐다.

### 5.4 이 회차에서 하지 않은 것 (경계 준수)

- codex 지적의 **옳고 그름 판정·심각도 재부여·중복 병합·표현 다듬기**를 하지 않았다.
- `migration-reviewer` 산출물(`..._migration-reviewer.md`)과 교차 종합본(`..._cross.md`)을
  만들지 않았다 — 리더 지시대로 1단계만 수행했다.
- **커밋하지 않았다.** `docs/migration/_workspace/00_progress.md` 를 **읽지도 쓰지도 않았다**
  (다른 레인이 갱신 중 — 실제로 리뷰 도중 `e577838` 로 갱신됐다, §1.5).
- 저장소를 변조하지 않았다. 실험은 codex 가 자기 프로세스에서 메모리 변이·`git show` 로 돌렸고,
  리뷰 종료 후 `git status --porcelain` 은 착수 전과 동일(untracked 3건뿐), `git worktree list` 는
  본 저장소 하나뿐이었다. `cp` 복원 없음.

---

## 6. `migration-reviewer` 에게

- 이 파일이 **codex 원본**이다. §3 은 무편집이고 §4·§5 는 Claude 색인이다 — 구획이 섞이지 않았다.
- 2차 교차 종합은 같은 어간 `17_gate16-fixes` 로 `..._cross.md` 를 만든다.
- 상충이 생기면 **어느 쪽도 지우지 말고** 양쪽 근거를 병기할 것.
- §4.3 의 6건은 **기존 지적의 근거 검증**이지 새 지적이 아니다.
