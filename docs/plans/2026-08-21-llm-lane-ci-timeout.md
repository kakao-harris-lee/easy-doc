# CI `llm-lane` 완주 계획 (2026-08-21)

**문제:** PR #1 의 `llm-lane` 잡이 CI 에서 **한 번도 완주한 적이 없다.** 30분 잡 타임아웃에 걸려 죽는다.

**목표:** 이 레인이 CI 에서 **끝까지 돌아 판정을 내놓게** 한다.

**목표가 아닌 것:** 그 판정을 **초록으로 만드는 것**. 아래 §5 에서 별건으로 분리한다.

위치 근거 — 이 작업은 Kotlin 마이그레이션 Phase 의 `{scope}` 가 아니라 존치 중인 Python
골든 레인의 CI 문제라, `.claude/skills/kotlin-migration/SKILL.md` 의 `{scope}` 정본 표에
대응 항목이 없다. 그래서 `docs/migration/_workspace/` 가 아니라 `docs/plans/` 의 날짜
접두 규약을 따른다(CLAUDE.md 「구현 전 리서치·계획」).

---

## 1. 확정된 사실 (전부 실측·기록 근거)

| # | 사실 | 근거 |
|---|---|---|
| F1 | `llm-lane` 잡 한도는 30분 | `.github/workflows/ci.yml:878` `timeout-minutes: 30` |
| F2 | 최신 실행이 정확히 30m14s 에서 죽음. conclusion 은 `failed` 가 아니라 **`cancelled`** | Actions job 96537327841 (18:30:49 → 19:01:03) |
| F3 | 죽기까지 **출력 0줄** — collection 이후 30분간 진행 신호 없음 | 잡 로그: `collected 1514 items / 1509 deselected / 5 selected` (18:31:05) → `##[error]The operation was canceled.` (19:01:02) |
| F4 | 이 레인의 **로컬 실측 소요는 35분 20초** | `docs/migration/_workspace/04_goldenset-first-run.md:4` |
| F5 | 변환은 56문서 **직렬 `await`** | `tests/golden/test_golden_eval.py:283` `for document in DOCUMENTS:` |
| F6 | judge 채점도 **직렬 `await`**, 최대 56회 추가 | `tests/golden/test_golden_eval.py:373` `for document in evaluation.documents:` |
| F7 | 시간이 전부 **module-scoped fixture(`outcomes`) 안**에서 소모됨 → 첫 테스트 결과가 찍히기 전에 한도 초과 | `tests/golden/test_golden_eval.py:170-180` |
| F8 | provider 호출당 타임아웃 60초 · SDK 재시도 2회 | `app/llm/provider.py:21-22` |
| F9 | `pytest-timeout` **미설치**, pytest 타임아웃 설정 **없음** | `pyproject.toml` `[tool.pytest.ini_options]`, `uv.lock` 무매치 |
| F10 | 브랜치의 방해받지 않은 실행 2건이 **둘 다** ~30분 타임아웃(`81ba9fa` 30m14s, `79447cd` 30m22s). 나머지는 다음 푸시가 `cancel-in-progress` 로 끊음 | Actions runs 32403598822 · 32387207214 외 |

**F4 가 이 문제의 전부다.** 35분짜리 작업에 30분 한도를 걸었다. 플래키가 아니라 구조다.

---

## 2. 기구현 확인 — 이미 있는 것 / 기록된 결정

바퀴를 다시 만들지 않기 위해 먼저 확인한 것들.

- **재시도는 이미 있고, 소유 계층이 정해져 있다.** SDK 레벨 `max_retries=2`
  (`app/llm/anthropic_provider.py:59`). `migration-safety-gate/SKILL.md:40` 이
  **재시도 책임은 한 계층만 갖는다**고 못박았다 → 이 계획은 새 재시도 계층을 **만들지 않는다.**
- **레이트리밋은 이 레인에서 실제로 터진 적이 있다.** `docs/plans/2026-08-08-sprint-4.md:65` —
  "56건 중 31건이 `LLMProviderError` 로 실패… API 레이트리밋/과부하로 인한 일시적 현상".
  **직렬 실행에서 난 일이다.** 동시성을 올리면 이 압력이 커진다 → §3 의 위험 판정 근거.
- **동시성 제한 유틸은 저장소에 없다.** 새로 쓴다면 표준 라이브러리 `asyncio.Semaphore` 로 족하다.
- **표본 축소 스위치는 없다.** `GOLDEN_PROVIDER`·`GOLDEN_JUDGE_PROVIDER`·`GOLDEN_RECORD_BASELINE`
  뿐이고, 문서 수를 줄이는 스위치는 **의도적으로 없다** — 분모를 줄이면 통과율이 실력과
  무관하게 오르고, 그 우회를 `test_규칙_기반_통과율이…` 가 `measured == len(DOCUMENTS)` 로
  직접 막는다(`tests/golden/test_golden_eval.py:441`). **표본 축소는 선택지가 아니다.**
- **직렬이 의도된 결정이라는 기록은 찾지 못했다.** 동시성을 금지하는 주석·원장 항목 없음.
  다만 위 레이트리밋 기록이 사실상의 제약으로 작동한다. (근거 없음을 근거 있음으로 쓰지 않는다.)

---

## 3. 선택지와 권고

### A. 잡 한도를 올린다 — **권고**

`.github/workflows/ci.yml:878` `timeout-minutes: 30` → **`60`**.

- **근거:** F4 의 35분 20초에 여유 70%. 러너 성능 편차·재시도를 흡수한다.
- **바꾸지 않는 것:** 호출 패턴·호출 수·측정 조건. 즉 **측정치의 의미가 안 변한다.**
  기준선(`tests/golden/baseline.json`, 33/56)과 비교 가능성이 그대로 유지된다.
- **비용:** 골든 경로를 건드린 커밋에서 잡이 ~35분. `concurrency.cancel-in-progress: true`
  (`ci.yml:883-885`)가 이미 붙어 있어 연속 푸시로 쌓이지는 않는다.
- **위험:** 낮음. 되돌리기 1줄.

### B. `convert_all`·judge 루프에 동시성 상한을 넣는다 — **지금은 권고하지 않음**

`asyncio.Semaphore(N)` + `gather(return_exceptions=True)`. 35분 → N=4 기준 ~10분.

- **매력:** 근본적으로 빠르다. CI 비용도 준다.
- **막는 이유 (하나면 충분하다):** §2 의 레이트리밋 기록이 **직렬에서** 난 일이다.
  동시성을 올려 429 가 늘면 `LLMProviderError` → 변환 실패가 늘고, 변환 실패는
  **차단축 1을 직격한다** — `assert not evaluation.conversion_failures`
  (`tests/golden/test_golden_eval.py:315`). 즉 **속도를 얻으려다 판정을 잃는 형태**다.
- **판단:** 성능 최적화를 **판정을 한 번도 못 받아 본 상태에서** 하지 않는다.
  A 로 완주시켜 CI 실측을 먼저 얻고, 그 숫자를 보고 B 를 별건으로 결정한다.

### C. 진행 신호를 남긴다 — **A 와 함께 권고 (작음)**

F3 이 진짜 문제다. 30분을 **아무 출력 없이** 죽어서, 이번 원인 규명에 CI 로그가 아니라
저장소 고고학이 필요했다. `convert_all`·judge 루프에 N건마다 진행 줄을 찍는다
(문서 **id 만** — CLAUDE.md 보안 규칙상 본문·개인정보 금지).

- 다음번에 이 레인이 매달리면 **어디서** 매달렸는지가 로그에 남는다.
- `pytest-timeout` 은 **도입하지 않는다.** F9 대로 지금 없고, 우리 문제는 개별 테스트가
  아니라 fixture setup 시간이라 마커 타임아웃이 겨냥하는 자리가 아니다. 의존성 추가의
  값어치가 없다.

---

## 4. 실행 순서와 검증

1. **A 적용** — `ci.yml` 1줄 (`30` → `60`).
2. **C 적용** — `tests/golden/test_golden_eval.py` 진행 로그. 문서 id 외 출력 금지.
3. **로컬 게이트** — `uv run ruff check . && uv run ruff format --check . && uv run mypy . .claude`
   (CLAUDE.md 커밋 전 필수 순서). `-m llm` 은 로컬에서 돌리지 않는다 — 35분·실과금이고,
   이 변경이 겨냥하는 것은 CI 실행이다.
4. **비-llm 회귀** — `uv run pytest` (기본 addopts 가 `-m 'not llm'` 이라 llm 레인 제외).
   특히 `tests/test_llm_lane_scope.py --self-check` 가 CI 스텝에서 불리므로 통과 확인.
5. **PR 푸시 → CI 실측이 판정이다.** 확인할 것:
   - 잡이 **완주**하는가(= 이 계획의 성공 판정).
   - 실제 소요 시간 — B 를 나중에 할지의 입력.
   - 진행 로그가 실제로 찍히는가(C 의 도달 확인 — 선언만 하고 안 찍히면 C 는 실패다).

**성공 판정:** `llm-lane` 잡이 타임아웃이 아니라 **테스트 결과로** 끝난다. 초록이든 빨강이든.

---

## 5. 별건으로 분리하는 것 — 완주해도 초록이 아닐 공산이 크다

이 계획은 레인을 **완주**시킨다. **통과**시키지 않는다. 완주 후 빨강이 나올 근거가 둘 있다.

1. **차단축 1(필수 정보 보존)에 여유가 0이다.** `REQUIRED_FACT_LOSS_LIMIT = 0`
   (`test_golden_eval.py:104`) — 절대 기준이고 완화 대상이 아니라고 코드가 명시한다.
   마지막으로 기록된 전건 실행은 **2 failed / 1 passed** 였다
   (`04_goldenset-first-run.md:5`).
2. **상대 하한선이 CI 에서 "비교 불가"로 떨어질 공산이 크다.** 기준선의 producer 축은
   `provider=anthropic · model=claude-sonnet-5 · effort=low` 인데
   (`tests/golden/baseline.json`), `llm-lane` 스텝은 `ANTHROPIC_API_KEY` 만 주고
   `LLM_MODEL`·`LLM_EFFORT` 를 **주지 않는다**(`ci.yml:1024-1029`). 두 설정의 기본값은
   `None` 이다(`app/config.py:44,51`). 지문이 갈리면 하한선이 판정을 **하지 않는다** —
   이 저장소가 반복해서 결함으로 지목해 온 "도달 0인데 초록" 형태다.

**②는 이번에 함께 고쳤다** (2026-08-21 운영자 판단 — "지문까지 한 번에").
`llm-lane` 실행 스텝 env 에 `LLM_MODEL: claude-sonnet-5` · `LLM_EFFORT: low` 를 명시했다.

음성/양성 대조로 확인했다 (API 호출 없이 지문만 계산):

| 조건 | producer 지문 | 기준선과 일치 |
|---|---|---|
| 옛 CI (두 값 미설정) | `effort: null` | **False** → 비교 불가 |
| 새 CI (이번 변경) | `effort: "low"` | **True** → 하한선이 실제로 비교한다 |

즉 이 레인의 상대 하한선은 **지금까지 판정을 한 적이 없다** — 완주하지 못해서만이 아니라,
완주했더라도 지문이 갈려 비교 불가로 떨어졌을 것이다. 그 자리가 닫혔다.

**①은 이 계획의 범위 밖이다.** 프롬프트·품질 문제이고 별도 작업으로 다뤄야 한다.
모듈 docstring 이 이미 스스로 경고하고 있다 — *"⚠ 이 절대 기준은 지금 통과하지 못할
가능성이 높다… 14개 문서에서 누락"*(`tests/golden/test_golden_eval.py`).
**따라서 첫 완주가 빨간색으로 끝나는 것은 이 계획의 실패가 아니라 예상된 결과다.**

---

## 6. 실제 변경 (적용 완료)

| 파일 | 변경 |
|---|---|
| `.github/workflows/ci.yml` | `llm-lane` `timeout-minutes: 30` → `60` |
| `.github/workflows/ci.yml` | 실행 스텝 env 에 `LLM_MODEL: claude-sonnet-5` · `LLM_EFFORT: low` (§5 ②) |
| `.github/workflows/ci.yml` | `uv run pytest -m llm` → `… -s` (아래 한 쌍 규약) |
| `.github/workflows/ci.yml` | 낡은 주석 정정 — "`baseline.json` 이 저장소에 없다"는 서술이 사실과 달랐다(2026-08-13 재기록 후 추적 중) |
| `tests/golden/test_golden_eval.py` | `log_progress` 헬퍼 + 변환·채점 루프 진행 로그 |

**`-s` 와 `log_progress` 는 한 쌍이다.** pytest 기본 캡처는 출력을 **실패한 테스트에만**
재생하는데 이 레인이 죽는 방식은 테스트 실패가 아니라 **타임아웃 kill** 이라 재생이
일어나지 않는다. 한쪽만 있으면 진행 로그는 다시 사라진다. 양쪽 주석에 서로를 적었다.

## 7. 검증 기록 (2026-08-21, 로컬 실측)

| 게이트 | 결과 |
|---|---|
| `uv run ruff check .` | All checks passed |
| `uv run ruff format --check .` | 156 files already formatted |
| `uv run mypy . .claude` | Success: no issues found in 139 source files |
| `uv run pytest` (기본 `-m 'not llm'`) | **1436 passed**, 68 skipped, 5 deselected, 5 xfailed |
| `python3 -m tests.test_llm_lane_scope --self-check` | 선언 28 / 도달 28 — 완전성 OK |
| `ci.yml` YAML 파싱 | `timeout-minutes: 60`, env 4키, 명령 `uv run pytest -m llm -s` |
| 지문 음성/양성 대조 | 옛 조건 불일치 / 새 조건 **일치** (§5 표) |
| `log_progress` 출력 형태 | `[골든 변환] 7/56 042 ok (누적 128초)` — **본문 미포함 확인** |

`-m llm` 은 로컬에서 돌리지 않았다 — 35분·실과금이고, 이 변경이 겨냥하는 것은 CI 실행이다.
**CI 실측이 이 계획의 판정이다.**

---

## 8. 후속 (2026-08-21, 같은 날 · §6~§7 을 일부 대체한다)

위 §6·§7 은 **그때의 기록으로 그대로 둔다.** 아래 두 자리가 그 뒤 실측으로 뒤집혔다.

### 8-1. `-s` → `--log-cli-level=INFO` (§6 의 「한 쌍 규약」 대체)

§6 이 `-s` 를 고른 근거는 *"캡처를 켜면 진행 줄이 버퍼에 갇혀 kill 때 사라진다"* 였는데
**그 전제가 틀렸다.** 근거였던 관찰이 tty 에서 난 것이고 CI 는 stdout 이 tty 가 아니다.
비-tty 리다이렉트(`> out.txt 2>&1`) 상태로 `kill -9` 한 뒤 파일에 남은 진행 줄을 셌다
(module-scoped async fixture 탐침, 0.5초 × 60회, 5초에 kill, 2회 반복):

| 조합 | kill 뒤 파일에 남은 진행 줄 |
|---|---|
| (A) `--log-cli-level=INFO` — 캡처 유지 | **8줄 / 9줄** |
| (B) `-s` — 캡처 끔 | 9줄 / 8줄 |

**둘 다 살아남는다.** live log 는 pytest 가 레코드마다 스트림을 명시적으로 flush 하므로
블록 버퍼링을 타지 않는다 — 남은 8줄이 약 440바이트로 4KB 블록 경계에 한참 못 미치는데도
파일에 있었다는 것이 그 증거다(버퍼가 차서 밀려 나온 것이 아니다).

생존이 안 갈리므로 판정은 **부수효과**로 넘어간다. `-s` 는 캡처를 통째로 꺼서 실패 리포트의
`Captured stdout/stderr` 섹션을 없앤다. 이 레인은 필수 정보 보존 축의 허용치가 **0** 이라
빨간색으로 끝날 공산이 크고(§ 위 경고), 그때 가장 필요한 것이 그 섹션이다. 즉 `-s` 는
진행 로그를 얻는 대가로 실패 진단을 버리는 거래인데, 실측이 그 대가를 **치를 필요가 없다**고
말한다. 그래서 `log_progress` 를 `print` → 모듈 로거로 바꾸고 CI 를 `--log-cli-level=INFO`
로 돌린다. **한 쌍 규약 자체는 그대로다 — 짝만 바뀐다**(플래그가 없으면 로거 출력은
아무 데도 가지 않는다. 위 (B) 열의 로그 줄이 0이었다).

### 8-2. 잡 타임아웃 → 스텝 타임아웃 (§6 의 `timeout-minutes: 60` 대체)

§6 은 상한을 잡 레벨에만 걸었는데, **잡 타임아웃은 잡을 «취소»한다.** 그래서 conclusion 이
`failure` 가 아니라 `cancelled` 가 되고 — 더 나쁘게는 — **후속 스텝이 통째로 skip 된다.**
실측: run 32403598822 에서 한도에 걸리자 `실행 결과 요약` 스텝이 `skipped` 로 끝났다.
정작 무엇이 돌았는지 알아야 할 그때 요약이 사라진 것이다.

| 자리 | §6 | 후속 |
|---|---|---|
| `llm-lane` 잡 `timeout-minutes` | 60 | **70** (스텝 상한을 감싸는 바깥 안전망) |
| `-m llm 레인 실행` 스텝 `timeout-minutes` | 없음 | **55** (실제 상한. 실측 35분 20초 대비 여유 56%) |
| `실행 결과 요약` 스텝 `if` | `steps.scope.outputs.run == 'true'` | `${{ !cancelled() && steps.scope.outputs.run == 'true' }}` |

스텝 타임아웃은 그 스텝만 실패시키므로 ⑴ 잡 conclusion 이 정직하게 `failure` 가 되고
⑵ 요약 스텝이 실제로 돈다. 두 `timeout-minutes` 는 한 쌍이라 한쪽만 고치면 안 된다 —
잡 상한이 스텝 상한보다 낮아지면 위의 skip 사고가 그대로 되살아난다.

### 8-3. 검증 기록 (2026-08-21 후속)

| 게이트 | 결과 |
|---|---|
| `uv run ruff check .` | All checks passed |
| `uv run ruff format --check .` | 156 files already formatted |
| `uv run mypy . .claude` | Success: no issues found in 139 source files |
| `uv run pytest` (기본 `-m 'not llm'`) | **1436 passed**, 68 skipped, 5 deselected, 5 xfailed |
| `python3 -m tests.test_llm_lane_scope --self-check` | 선언 28 / 도달 28 — 완전성 OK |
| `ci.yml` YAML 파싱 | 잡 `timeout-minutes: 70` · 실행 스텝 `timeout-minutes: 55` · 요약 스텝 `if: ${{ !cancelled() && steps.scope.outputs.run == 'true' }}` · 명령 `uv run pytest -m llm --log-cli-level=INFO` |
| `log_progress` 출력 형태 | `[골든 변환] 7/56 042 ok (누적 128초)` — **본문 미포함 확인**(형태 불변) |

`-m llm` 은 이번에도 돌리지 않았다(35분·실과금). **CI 실측이 여전히 이 계획의 판정이다.**
