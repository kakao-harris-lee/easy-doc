# 06 baseline-guard — codex 독립 리뷰 (원문 보존)

> **이 파일은 codex 출력의 원문 기록이다.** 판정·심각도 조정·중복 병합·표현 다듬기를 하지
> 않는다. 종합과 판정은 `migration-reviewer`(2차 호출)와 리더의 몫이다.
> 교차 종합본은 `06_baseline-guard_cross.md`가 된다.

## 실행 이력 — 2회차 구성, 회차 A는 1차 유실 후 재실행으로 회수

이 게이트는 **두 회차로 나눠** 호출했다. 리뷰 대상 4건 중 커밋 `6cd5809`(가드·producer 축)가
가장 무거워, 축을 3~5개로 유지하라는 스킬 §3.5 상한을 지키려면 한 프롬프트에 열 개 질문을
담을 수 없었기 때문이다.

| 회차 | 대상 초점 | 상태 | 결과 |
|---|---|---|---|
| **회차 A** | `6cd5809` — 가드 도달·producer 축·테스트 무름 | **1차 유실 → 재실행 완료 (exit 0)** | 아래 §3 |
| **회차 B** | `c8bc9f8`·`958561a`·`f220682` — 조건부 CI 표기·`.gitignore`·문서 판정 | 완료 (exit 0) | 아래 §2 |

**결과적으로 리뷰 대상 4건 전부가 codex 독립 관점을 통과했다.** 아래 유실 기록은 회차 A의
**1차 시도**에 대한 것이며, 재실행이 성공해 §3에 원문이 실려 있다. 이 절을 남겨 두는 이유는
1차 유실이 "조용히 넘어간 누락"으로 오독되지 않게 하기 위해서다.

**회차 A의 1차 실행은 결과를 내지 못하고 유실됐다.** job `review-msqvjyef-lh7yxg`
(codex session `019ff8dc-a3c0-7cd3-afdf-b8615e3f50c3`)는 `verifying` 단계까지 갔으나,
리더가 진행 상황을 오판해 이 세션의 codex broker를 종료하면서 최종 메시지 생성 전에 죽었다.
복구를 시도했고 **불가능**을 확인했다:

- job JSON에 `result`·`output` 필드 없음 (`status: running` / `phase: verifying`에서 멈춤)
- `~/.codex/sessions/**`에 해당 session id의 rollout 파일 **부재** (broker 인메모리 세션이었다)
- job 로그에 남은 것은 **잘린 진행 하트비트 2건**뿐 — 최종 리뷰가 아니다. 원문 그대로:
  - `[02:04:07.200Z] {"verdict":"approve","summary":"리뷰를 시작합니다. 먼저 관련 메모의 기존 게이트 불변식을 확인한 뒤, 6cd5809의 실제 diff와 모든 ...`
  - `[02:04:50.964Z] {"verdict":"approve","summary":"현재까지 확인된 핵심은 provider 빈 값 테스트가 다른 두 테스트와 같은 수준의 하네스 배선 증거가 아니...`
  - 두 줄 다 **로거가 잘라 놓은 중간 보고**다. 이어붙이기 위해 추측으로 보완하지 않았다.
    특히 두 번째 줄의 `verdict: approve`는 **최종 판정이 아니다** — codex 헬퍼가 진행 중
    메시지에 붙이는 기본값이며, 같은 실행이 아직 `verifying` 중이었다. 이것을 승인으로 읽으면
    안 된다.

회차 A는 리더 승인 아래 **단 한 번** 재실행했다(§3). 재실행 프롬프트는 1차와 동일하되
"앞선 회차가 외부 요인으로 중단됐다 / A·B 축을 먼저 끝내라 / 못 다룬 축은 미검토로 명시하라"
문단 하나를 덧붙였다 — 전문은 §3.1에 그대로 싣는다.

---

## 1. 호출 메타데이터 (공통)

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-13 (UTC 02:0x~03:0x) |
| 저장소 | `/Users/harris/Development/private/easy-doc`, 브랜치 `feat/kotlin-migration-harness` |
| 리뷰 대상 | 커밋 4건 — `6cd5809`·`c8bc9f8`·`958561a`·`f220682` |
| 모드 | `adversarial` (게이트 장치 변경 = 위험 영역, 스킬 §3.2) |
| base | `64f1757` (= `6cd5809~1`) |
| 헬퍼 | `~/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` (plugins cache, 최신 버전 자동 선택) |
| `{phase}_{scope}` | `06_baseline-guard` — **리더 지정값을 그대로 사용** |

### 1.1 대상 판정 (스크립트 stderr 원문 — 두 회차 동일)

```
codex-review: 리뷰 대상 = branch diff vs 64f1757
codex-review: 대상 판정 = non-empty (merge-base=64f175795373, 변경 파일 11개 (branch 모드는 커밋된 변경만 센다))
```

11개 파일 = `git diff --stat 6cd5809~1..HEAD`의 11 files / 799 insertions / 113 deletions과 일치.

### 1.2 개인정보 격리 조치 (`privacy-gate` 감사 대상)

리더 경고대로 `docs/golden-drafts/` **48건이 미추적이고 전부 24KB 미만**이라, `--scope
working-tree`로 돌리면 헬퍼가 미추적 파일 *본문*을 컨텍스트에 실어 **공공기관 문서 본문이
외부 호출로 나간다.** 그래서 다음과 같이 격리했다.

```bash
git clone --no-local --branch feat/kotlin-migration-harness <repo> <scratchpad>/review-clone
ln -sfn <repo>/.venv <scratchpad>/review-clone/.venv
```

- 클론은 **추적 파일만** 담는다 → `docs/golden/`(76MB 바이너리 13건)·`docs/golden-drafts/`
  (48건) **둘 다 클론에 존재하지 않음**을 `ls docs/`로 확인했다.
- 작업 트리 clean → `--base`가 **커밋된 변경만** 본다. 미추적 파일이 대상에 섞일 경로가 없다.
- 리뷰는 이 클론 안에서 실행했다. 실제 암호문·키·사용자 문서는 프롬프트에 싣지 않았다.

### 1.3 실행 제약 (프롬프트에 명시해 codex에 전달)

- `tests/golden/baseline.json`을 **만들지 마라** — 의도적 부재이며 생기는 순간 하한선 활성화
- `pytest -m llm`을 **실행하지 마라** — Anthropic 크레딧 소진, HTTP 400
- **코드를 고치지 마라** — 리뷰만 한다

**세 제약 모두 지켜졌다.** 회차 B 종료 후 확인: `baseline.json` 부재 유지, 작업 트리 무변경,
codex가 돌린 허용 테스트는 `35 passed`.

### 1.4 다른 세션 보호

`kis_unified_sts`의 broker(pid 20763)는 **건드리지 않았다.** 회차 B 종료 시점에 생존 확인
(`ps -p 20763` → ALIVE). codex 프로세스 정리는 `--cwd`가 이 저장소이거나 격리 클론인 것만
대상으로 했다.

---

## 2. 회차 B — 조건부 CI 표기 · `.gitignore` · 문서 판정

### 2.1 실행 명령과 종료 코드

```bash
cd <scratchpad>/review-clone
bash .claude/skills/codex-review/scripts/codex-review.sh adversarial --base 64f1757 "$(cat focus2.txt)"
```

| 항목 | 값 |
|---|---|
| **스크립트 종료 코드** | **0** (= 리뷰 근거로 유효) |
| job id | `review-msqvkxxx` 계열 (state: `review-clone-972c09b021820dd8`) |
| codex verdict | `needs-attention` |
| 출력 크기 | 7,497 bytes |

### 2.2 전달한 프롬프트 전문 (focus text)

<details>
<summary>focus2.txt — 전문</summary>

```
## 배경

이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 전환 중이고, 전환 진행을 "종료 조건 표"로 관리한다. 그 표의 각 행에는 **그 게이트가 실제로 어디서 도는가**를 적는 칸이 있고, 그 칸의 표기 어휘를 기계 검사기(`tests/test_harness_scope_reach.py`)가 강제한다. 리뷰 대상 커밋 4건은 제품 코드가 아니라 **그 검사 장치와 저장소 규칙, 그리고 판정 기록 문서**를 고친 것이다. 판정 기준은 "동작하는가"가 아니라 "**동작하는 것처럼 보이면서 실제로는 아무것도 재지 않는 자리가 있는가**"다.

## 지켜야 하는 조건 (채점 기준)

1. 이 저장소의 규칙 원문: **"무시(`.gitignore`) 규칙은 탐지가 아니라 은폐로 작동하므로, 근거가 실제로 닿은 자리만 막는다."** 한 건의 사고를 막으려고 전역 패턴을 넣어 **앞으로 같은 사고가 보이지 않게 되는** 자리는 결함이다. **범위는 근거를 넘지 않는다.**
2. 게이트·표기·어휘를 새로 만들거나 넓힐 때는 **선언한 범위와 실제 도달 범위를 대조**한다. 새 형식이 **새 자유 통과 카드**가 되면 안 된다.
3. 성공/실패 판정을 **대리 지표**로 바꿔 읽는 자리를 찾는다 — 표기의 존재를 "그 게이트가 돈다"로, 문서의 단정문을 "근거가 있다"로 읽는 자리.
4. 문서에 적힌 **판정 문장은 인용한 근거로 실제로 지지돼야 한다.** 지지되지 않으면 지적하라.

## 실행 제약 (반드시 지켜라)

- **`tests/golden/baseline.json` 을 절대 만들지 마라.** 지금 의도적으로 부재이며, 생기는 순간 하한선으로 활성화된다.
- **`pytest -m llm` 을 절대 실행하지 마라.** API 크레딧이 소진돼 HTTP 400 을 낸다.
- 그 외 비-llm 테스트·git 명령은 실행해도 된다: `./.venv/bin/python -m pytest tests/test_harness_scope_reach.py -q`, `git log`, `git show` 등.
- **코드를 고치지 마라.** 리뷰만 한다.

## 대상

`git diff 64f1757..HEAD` (커밋 4건). 이번 회차는 그중 **`c8bc9f8` · `958561a` · `f220682`** 에 집중한다 — `.gitignore`, `.claude/skills/kotlin-migration/SKILL.md`, `tests/test_harness_scope_reach.py`, `docs/migration/_workspace/04_goldenset-first-run.md`, `docs/migration/_workspace/00_progress.md`, `docs/migration/_workspace/03_rebuild-extraction-list.md`.

## 저자가 밝힌 설명 (사실 확인 대상이지 전제가 아니다)

1. 게이트 도달 표의 어휘에 **조건부 CI 표기** `ci:<잡>(조건:<조건 정본 경로>)` 를 추가했다. 뜻은 "그 잡에 배선돼 있으나 조건이 맞을 때만 돈다"이고, 괄호 안은 조건을 정하는 **실재하는 추적 파일**을 가리켜야 한다. 검사기는 `tests/test_harness_scope_reach.py`.
2. 저자 스스로 한계를 적었다: "**조건부 표기를 쓸 의무가 없다** — 검사기가 `if:`·`paths` 를 읽지 않아 `ci:llm-lane` 단순형으로 **과장해도 통과**한다. 그러니까 이 형식이 막는 것은 '조건부라고 적었는데 조건 정본이 없는 것'이지 '조건부인데 단순형으로 적어 과장하는 것'이 아니다."
3. 깨진 형식(`ci:x(` · `ci:x()` · `ci:x(조건:)` 등)이 단순 `ci:` 로 **오독되지 않도록** 경계 9종을 테스트(`test_음성3d_깨진_조건부_표기는_단순_ci_로_읽히지_않는다`)로 고정했다고 한다.
4. `.gitignore` 에 `docs/golden/`(정부 PDF·HWPX 원본 13건, 76MB, 바이너리)만 넣고 `docs/golden-drafts/`(초안 JSON 48건, 미추적)는 **넣지 않았다.** 근거: "`app/easyread/collection.py` 가 초안을 **커밋 대상으로 전제**하고 그 위에 `redact_contacts` 라는 저장소 sink 안전장치를 걸어 뒀다 — 무시로 돌리면 그 안전장치의 근거가 사라진다." 경로를 못박고 `*.pdf`·`*.hwpx` 전역을 쓰지 않은 이유도 위 규칙 1이라고 적었다.
5. 문서 판정: "2026-08-08 실행(36/56)과 2026-08-12 실행(31/56)의 **9%p 차이는 규명 실패가 아니라 비교 불가**였다." 근거로 두 실행 사이에 `app/easyread/style_rules.py`·`app/easyread/goldenset.py` 가 **5커밋**(`85ca2f5`·`eae75c7`·`0894854`·`a4c9fd9`·`c43cae5`) 바뀐 것을 든다.
6. 문서 판정: "2026-08-13 실행의 실수집 **0/36 은 품질이 아니라 API 오류**다(HTTP 400 · `credit balance is too low`)." 정황 근거로 앞 번호(합성 001~020) 성공 · 뒷 번호 전건 실패 · 실행 시간이 직전의 절반 · effort/모델 4종 탐침이 전부 같은 400 을 든다. 그래서 기준선을 기록하지 않고 `tests/golden/baseline.json` 을 다시 내렸다.
7. `00_progress.md` 의 게이트 표 Quality 행 실행 경로를 `local:uv run pytest tests/golden -m llm` 에서 `ci:llm-lane(조건:.github/llm-lane-paths.txt)` 로 바꿨다.

## 질문 (네 축)

**A. 조건부 형식의 값어치와 부작용.** 설명 2가 참이라면(직접 확인하라) 이 형식이 **실제로 막는 것은 무엇인가?** 검사기 `tests/test_harness_scope_reach.py` 를 읽고, 어휘 확장 **전후로 통과 가능한 표기 집합이 어떻게 달라졌는지** 구체적으로 답하라. 형식을 더한 결과 **새로 생긴 통과 경로**는 없는가? 설명 7처럼 실제 표의 행이 단순형에서 조건부형으로 바뀐 것이 게이트의 강도를 올렸는지 내렸는지도 판정하라. 이 형식을 통째로 제거하면 정확히 무엇이 깨지는가?

**B. 경계가 실제로 닫혔는지 시험하라.** `_CI_CONDITIONAL_TOKEN`·`_CI_TOKEN` 정규식과 `_vocabulary_problem`·`_unknown_job_problem`·`_untracked_problem` 의 분기를 읽고, 테스트가 고정한 9종 **밖에서** 다음이 있는지 찾아라 — ⓐ 조건 정본(추적 파일) 검사를 우회하면서 통과하는 표기, ⓑ 조건부처럼 보이는데 단순형으로 읽혀 조용히 통과하는 표기, ⓒ 잡 실재 검사를 우회하는 표기, ⓓ 표를 파싱하는 앞단(셀 분리·백틱 제거·구분자 `·` 처리 등)에서 토큰이 통째로 사라지거나 다르게 잘리는 입력. 있으면 **정확한 문자열**과 **재현 명령**을 제시하라. 없으면 없다고 적어라.

**C. `.gitignore` 판단의 범위.** `app/easyread/collection.py` 를 읽어 설명 4의 근거(초안이 커밋 대상으로 전제됨 · `redact_contacts` 가 그 위에 걸린 sink 안전장치임)가 **참인지** 확인하라. 그리고 위 규칙 1에 비추어 `docs/golden/` 을 무시하는 범위가 근거에 맞는가? 이 무시로 인해 **앞으로 보이지 않게 되는 것**은 무엇인가(그 디렉터리에 다른 종류의 파일이 생겨도 `git status` 에 안 보인다는 점을 포함해 답하라)? `docs/golden/` 이 **한 번도 git 에 추적된 적이 없다**는 사실이 이 판단을 바꾸는가? 같은 커밋이 `03_rebuild-extraction-list.md` 에서 이 디렉터리의 "생존" 서술을 고쳤는데, 그 서술이 지금 사실과 맞는지도 확인하라.

**D. 문서 판정의 근거.** 설명 5에 대해: `git log --since=2026-08-08 -- app/easyread/style_rules.py app/easyread/goldenset.py` 와 **각 커밋의 실제 diff** 를 직접 확인하고, 그 변경들이 정말 **규칙 통과율의 판정 기준 또는 프롬프트를 바꿨는지** 판단하라(`tests/golden/baseline.py` 의 `_criteria_modules`·`criteria_payload` 가 무엇을 지문의 원재료로 넣는지 함께 보라). 5커밋 각각이 이 결론을 지지하는가, 아니면 일부는 무관한가? 근거가 약하면 어디가 약한지 지목하라. 설명 6에 대해: "0/36 은 품질이 아니라 API 오류"라는 판정이 문서가 든 정황 근거로 지지되는가? 그 판정이 **틀렸을 경우 무엇이 잘못되는가**, 그리고 문서에 남았어야 할 반증 자료가 있는가?
```

</details>

### 2.3 codex 원문 출력 (무편집)

```
# Codex Adversarial Review

Target: branch diff against 64f1757
Verdict: needs-attention

NO-SHIP. A: 기존 허용 집합은 6종이었고, 변경 후 기존 집합을 줄이지 않은 채 `ci:J(조건:P)`(J=실재 잡, P=임의의 추적 파일)를 추가했다. 두 표 치환은 사실 표기의 정확성만 높였고 게이트 실행·강도는 바꾸지 않았다. 형식을 제거하면 현재 표의 2개 토큰과 조건부 테스트·스킬 설명만 깨지며 CI 배선은 그대로다. B: 앵커 때문에 괄호가 남은 깨진 조건부가 단순형으로 읽히는 추가 사례는 없었지만, 임의 추적 파일 및 `**` 정규화 우회가 통과한다. C: drafts의 기본 저장 경로와 redaction·쓰기 직전 재검사는 실재하지만 ‘커밋 대상’은 의도일 뿐 추적 이력은 0건이다. `docs/golden/` 전체 무시는 근거보다 넓다. D: 3개 커밋은 실제 판정 기준을 바꿨지만, 5개 전부가 그렇다는 설명과 ‘설명할 하락이 없다’는 종결은 과장이다. API 크레딧 판정도 개연성은 높으나 감사 가능한 실행 증거가 없다. 허용 테스트는 35 passed였고 baseline.json은 계속 부재한다.

Findings:
- [high] 조건부 표기가 실제 CI 조건과 결속되지 않고 원문까지 다른 값으로 정규화한다 (tests/test_harness_scope_reach.py:558-564)
  새 분기는 잡 이름이 `jobs`에 있고 조건 문자열이 아무 추적 파일이면 통과시킨다. 따라서 실제 조건과 무관한 정확한 문자열 `ci:llm-lane(조건:README.md)`로 현재 두 셀을 바꿔도 `judge_tables` 위반 0건, census 위반 0건이다. 또한 `_normalize_cell`이 인라인 코드 안의 `**`까지 삭제하므로 원문상 미추적 경로 `ci:llm-lane(조건:.github/llm-lane-**paths.txt)`와 존재하지 않는 잡 `ci:llm-**lane(조건:.github/llm-lane-paths.txt)`도 각각 실재 값으로 변조되어 통과한다. 재현: `PYTHONDONTWRITEBYTECODE=1 ./.venv/bin/python -c 'from pathlib import Path; import tests.test_harness_scope_reach as h; r=Path(".").resolve(); m=(r/"docs/migration/_workspace/00_progress.md").read_text(); t=h.read_tracked_files(r); c=h.JudgeContext(h.read_ci_job_names((r/".github/workflows/ci.yml").read_text()),lambda p:p in t); old="`ci:llm-lane(조건:.github/llm-lane-paths.txt)`"; [print(s,len(h.judge_tables(h.select_target_tables(h.parse_tables(m.replace(old,s))),c))) for s in ("`ci:llm-lane(조건:README.md)`","`ci:llm-lane(조건:.github/llm-lane-**paths.txt)`","`ci:llm-**lane(조건:.github/llm-lane-paths.txt)`")]'`는 세 경우 모두 0을 출력한다. 이 형식은 선언상 ‘조건 정본’을 검사하는 것처럼 보이지만 실제로는 새 자기서명 통과 카드다.
  Recommendation: 잡별 조건 정본을 검사기 밖의 독립 매핑으로 고정하고 workflow가 그 파일을 실제 소비하는지 검증하라. 인라인 코드 원문에서는 `**`를 삭제하지 말고, 위 세 문자열과 임의 추적 파일을 음성 테스트로 추가하라.
- [high] 디렉터리 전체 ignore가 근거 없는 미래 파일까지 영구 은폐한다 (.gitignore:63-70)
  근거는 현재 13개 PDF/HWPX인데 규칙은 `docs/golden/` 아래 모든 이름·형식을 숨긴다. `git check-ignore -v docs/golden/manifest.json docs/golden/README.md docs/golden/checksums.sha256 docs/golden/recover.py`가 모두 이 한 줄에 매칭된다. 앞으로 생기는 인벤토리, 체크섬, 복구 코드, 다른 형식 원본, 잘못 저장된 파일도 `git status`와 working-tree 리뷰에서 보이지 않는다. 이 경로는 전체 git 이력에서 추적 0건이고 현재 review clone에도 없으므로, ‘한 번도 추적되지 않았다’는 사실은 범위를 정당화하지 않는다. 오히려 fresh clone에 원본이 없고 삭제·유실을 저장소가 감지하지 못한다는 위험을 확정한다. `03_rebuild-extraction-list.md`의 fresh-clone 부재 설명은 맞지만 ‘이 기계 작업 사본에서 생존’은 이 checkout에서 거짓이며 저장소 근거로 확인할 수 없다.
  Recommendation: 13개 실재 바이너리의 정확한 경로만 무시하거나 저장소 밖 보관소로 옮기고, 추적되는 manifest·checksum·복구 위치를 남겨라. working-tree 외부 전송 문제는 `.gitignore`가 아니라 리뷰 도구의 명시적 exclude로 해결하라.
- [medium] 9%p 하락을 ‘설명할 필요 없음’으로 종결한 결론이 증거보다 강하다 (docs/migration/_workspace/04_goldenset-first-run.md:167-175)
  실제 diff상 `eae75c7`, `0894854`, `a4c9fd9`는 `check_style` 판정 기준을 바꿔 과거 수치와의 baseline 비교를 깨므로 ‘비교 불가’ 자체는 지지된다. 하지만 `85ca2f5`의 `style_rules.py` 변경은 주석뿐이고 실제 동작 변경은 프롬프트 쪽이며, `c43cae5`는 합격선 기제와 별도 팩트 게이트를 추가했지만 기존 31/56 분자의 세 조건은 보존했다. 더구나 `baseline.py`는 프롬프트를 ‘재어지는 것’으로 정의해 변경 후에도 비교 대상으로 둔다. 판정 기준 변경은 두 숫자로 원인을 분해할 수 없다는 뜻이지, 프롬프트 회귀나 실제 품질 하락이 없었다는 증거가 아니다. 과거 출력이 없다는 사실은 ‘원인 불명’을 강화할 뿐 ‘설명할 하락 없음’을 증명하지 않는다. 이 종결 상태로 새 기준선을 기록하면 실제 회귀가 새 하한선으로 굳을 수 있다.
  Recommendation: 판정을 ‘baseline 비교 불가, 실제 회귀 원인은 미해결’로 고치고 재기록 조건 2를 닫지 마라. 동일 producer·현재 판정 기준에서 구/신 프롬프트를 반복 A/B하거나, 명시적 사용자 승인으로 기준선 리셋을 결정하라.
- [medium] 0/36을 전부 크레딧 오류로 판정할 실행 증거가 보존되지 않았다 (docs/migration/_workspace/04_goldenset-first-run.md:196-209)
  저장소에서 low-credit HTTP 400 원문, 19/20·0/36 분할, 4종 탐침 결과가 나타나는 곳은 이 문서뿐이며 958561a도 원시 리포트나 로그를 추가하지 않았다. 실행 코드의 `convert_all`은 모든 `LLMProviderError`를 `None`으로 축약하고 리포트에는 문서 ID만 남겨, 36건 각각이 같은 400이었는지 사후 확인할 수 없다. 합성 문서를 먼저 성공한 뒤 크레딧 오류가 난 순서와 후속 탐침은 개연성은 높이지만, 실수집 입력에만 발생하는 별도 오류가 먼저 또는 함께 있었음을 반증하지 못한다. 판정이 틀리면 데이터 의존 변환 실패가 크레딧 문제로 은폐되고 재기록 조건이 잘못 닫힌다.
  Recommendation: 본문·비밀 없이 문서 ID별 성공/실패, provider 오류 유형·HTTP 상태·안전한 오류 코드, 시각, 4개 탐침의 정확한 모델/effort와 결과를 기계가독 산출물로 보존하라. 그 전까지는 ‘API 오류로 추정, 검증 미완’으로 기록하고 baseline 부재를 유지하라.

Next steps:
- 조건부 토큰을 workflow의 실제 조건 소비와 결속한 뒤 임의 추적 파일·`**` 원문 변조 회귀 테스트를 추가한다.
- `docs/golden/` ignore를 정확한 13개 경로로 좁히고 추적되는 원본 manifest/보관 위치를 둔다.
- 9%p 및 0/36 판정 문서를 증거 수준에 맞게 낮추고, 크레딧 복구 후 동일 조건 실행의 감사 가능한 오류 요약을 남긴다.
```

---

## 3. 회차 A — 가드 도달 · producer 축 (재실행)

### 3.1 실행 명령과 종료 코드

```bash
cd <scratchpad>/review-clone
bash .claude/skills/codex-review/scripts/codex-review.sh adversarial --base 64f1757 "$(cat focus1b.txt)"
```

| 항목 | 값 |
|---|---|
| **스크립트 종료 코드** | **0** (= 리뷰 근거로 유효) |
| 1차 job (유실) | `review-msqvjyef-lh7yxg` / session `019ff8dc-a3c0-7cd3-afdf-b8615e3f50c3` |
| **재실행 job** | **`review-msqwmc90-apkg04`** (status `completed` / phase `done`, 약 250초) |
| codex verdict | `needs-attention` |
| 출력 크기 | 4,706 bytes |

### 3.2 전달한 프롬프트 전문 (focus text)

`focus1b.txt` = `focus1.txt`(1차와 동일) + 말미의 「진행 우선순위」 문단 하나.
**덧붙인 문단은 축의 내용이나 판정 기준을 바꾸지 않고, 시간 배분과 미검토 표기 규약만 지시한다.**

<details>
<summary>focus1b.txt — 전문</summary>

```
## 배경

이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 전환 중이고, 그 전환의 품질 게이트(골든셋 규칙 통과율의 상대 하한선)를 Python 테스트 하네스로 운영한다. 리뷰 대상 커밋 4건은 제품 코드가 아니라 **그 게이트 장치 자체**를 고친 것이다. 따라서 판정 기준은 "동작하는가"가 아니라 "**동작하는 것처럼 보이면서 실제로는 아무것도 재지 않는 자리가 있는가**"다.

## 지켜야 하는 조건 (채점 기준)

1. 게이트·불변식·규칙을 세우거나 넓힐 때는 **선언한 범위와 실제 도달 범위를 실행으로 대조**해야 한다. 도달 0("이 게이트가 지금 어디서 도는가")을 특히 의심한다. **함수를 직접 불러 확인한 것과 하네스(실행) 경로로 도달한 것은 다르다** — 이 저장소는 그 구분을 여러 번 놓쳐 게이트가 무력한 채 초록으로 남았다.
2. 테스트가 **보장 범위를 좁히는 방향**으로 바뀌면 안 된다. 기존 테스트를 통과시키려고 검사를 무르게 하는 편집은 결함이다.
3. 성공/실패 판정을 **대리 지표**로 바꿔 읽는 자리를 찾는다 — 테스트 통과를 "그 경로가 돌았다"로, 단언의 존재를 "그 경로가 태워졌다"로, 지적 0건을 "문제 없음"으로 읽는 자리.
4. 어떤 장치를 제거했을 때 **정확히 무엇이 깨지는가**를 물어라. 떼어도 아무 테스트가 깨지지 않는 장치는 지목하라.

## 실행 제약 (반드시 지켜라)

- **`tests/golden/baseline.json` 을 절대 만들지 마라.** 이 파일은 지금 의도적으로 부재이며, 저장소에 생기는 순간 하한선으로 활성화된다.
- **`pytest -m llm` 을 절대 실행하지 마라.** API 크레딧이 소진돼 HTTP 400을 낸다.
- 그 외 비-llm 테스트는 실행해도 된다: `./.venv/bin/python -m pytest tests/golden -q`, `./.venv/bin/python -m pytest tests/golden/test_floor_gate_wiring.py -q` 등.
- **코드를 고치지 마라.** 리뷰만 한다.

## 대상

`git diff 64f1757..HEAD` (커밋 4건). 이번 회차는 그중 **`6cd5809`** 에 집중한다 — `tests/golden/baseline.py`, `tests/golden/test_baseline_gate.py`, `tests/golden/test_floor_gate_wiring.py`, `tests/golden/test_golden_eval.py`, `tests/golden/report.py`.

## 저자가 밝힌 설명 (사실 확인 대상이지 전제가 아니다)

1. `write_baseline` 에는 fail-closed 가드 4종이 있다 — provider 빔 / 관측 모델 없음 / 모델 섞임 / `effort` 키 부재. 저자는 이 가드들이 **함수 직접 호출로만** 확인됐고 하네스 경로로는 한 번도 도달하지 않았다고 보아, 하네스 경로 거부 테스트 2건(관측 모델 없음·모델 섞임)을 `test_floor_gate_wiring.py` 에 추가했다.
2. provider 빔 가드에 대해서는 `test_baseline_gate.py` 에 `test_provider_없이는_기준선을_쓰지_않는다` 를 추가했다. 저자는 이 상태가 `GOLDEN_PROVIDER=`(빈 값) 환경변수로 하네스 경로에서 **도달 가능하다**고 docstring 에 적었다.
3. `effort` 키 부재 가드는 고치지 않았다. 근거: "`baseline_body` 가 pydantic `Baseline` 을 dump 하므로 하네스 경로로 만든 본문에는 `effort` 키가 언제나 있다 — 손으로 조립한 body 에 대한 방어이고 실행 조건으로 재현되지 않는다. 그래서 도달 0으로 묶어 고칠 대상이 아니다."
4. 지문(`Fingerprint`)에 세 번째 축 `producer`(provider · 관측 모델 목록 · effort)를 넣었다. 설계 결정 셋: ⑴ 기존 `criteria_sha256` 에 접어 넣지 않고 **별도 축** ⑵ 해시가 아니라 **값 그대로** ⑶ `observed_models` 를 단일 값으로 접지 않고 **목록 그대로**. ⑶의 근거는 "`write_baseline` 의 fail-closed 가드는 **기록 경로에만** 있고 **판정 경로에는 없어** 빈 목록·섞인 목록이 그대로 판정까지 도달한다".
5. 같은 값이 기준선 파일의 `fingerprint.producer` 와 `context` **두 자리**에 실린다. 저자는 "둘이 갈리지 않는 것은 한 `RunContext` 에서 둘 다 유도하기 때문"이라며 **호출 규약으로만** 막고 가드를 더하지 않았다. 저자의 위험 평가: "갈려도 비교는 producer 가 하므로 판정은 안전하고, 사람이 읽는 기록만 틀어진다."
6. `test_floor_gate_wiring.py` 의 `_baseline_at` 이 `RunContext(provider="fake")` 를 손으로 세우던 것을 `harness.run_context()` 를 쓰도록 바꿨다. 이유: producer 축 도입으로 기준선과 현재의 producer 가 갈려 배선 테스트들이 **전부 비교 불가로 떨어지게 됐기** 때문.

## 질문 (네 축)

**A. 가드의 도달.** 설명 2의 `test_provider_없이는_기준선을_쓰지_않는다` 는 `RunContext(provider="")` 를 **테스트가 직접 만들어** `write_baseline` 에 넘긴다. 그 상태가 실제 실행 경로에서 만들어진다는 것은 무엇이 보증하는가? `GOLDEN_PROVIDER=`(빈 값)로 레인을 돌렸을 때 `write_baseline` 까지 실제로 도달하는가, 아니면 그 전에 다른 지점(예: `tests/golden/test_golden_eval.py` 의 provider 픽스처·`require_provider`·`create_provider`)에서 먼저 실패해 도달하지 못하는가? 코드로 경로를 끝까지 따라가 답하라. 설명 1의 두 하네스 테스트와 설명 2의 테스트가 **같은 수준의 배선 확인인지** 판정하라.

**B. 설명 3의 근거를 코드로 검증하라.** `baseline_body` 가 만드는 본문에서 `context.effort` 키가 빠질 수 있는 경로가 정말 하나도 없는가? `RunContext`·`Baseline` 의 pydantic 설정(필드 기본값, `extra`, dump 옵션), `write_baseline` 의 **모든 호출자**, 그리고 디스크에서 읽어 들인 본문이 다시 쓰이거나 비교되는 경로(`stored_body`·`load_baseline`·`baseline_changes`)를 전부 확인하라. 도달 가능한 분기가 하나라도 있으면 저자는 **살아 있는 분기를 죽은 분기로 판정하고 방치**한 것이다. 반대로 정말 도달 불가라면 그 가드와 테스트가 무엇을 보장하는지 적어라.

**C. producer 축의 설계와 잔여 위험.** 설명 4의 ⑶ 근거("판정 경로에는 fail-closed 가드가 없다")가 코드로 참인가 — 비기록 실행에서 빈 `observed_models` 나 섞인 목록이 `compare()` 까지 도달하는 경로를 실제로 짚어라. 설명 4의 ⑴⑵ 결정이 만들어 낸 부작용(지문이 과민해져 하한선이 영영 축적되지 않는 반대 방향 고장, 값을 그대로 담아 생기는 문제)이 있는지도 보라. 설명 5에 대해서는: `fingerprint.producer` 와 `context` 가 **갈린 기준선 파일이 만들어질 수 있는가**(가능하다면 그 경로를 제시하라), 갈렸을 때 `compare()` 가 무엇을 읽는지 확인해 판정이 정말 안전한지, 그리고 그 파일이 **다음 실행에서 다시 읽히고 다시 기록될 때**도 안전한지 답하라. 저자의 위험 평가가 옳은지 판정하라.

**D. 설명 6이 검사를 무르게 한 것인가.** `_baseline_at` 이 `harness.run_context()` 를 쓰게 되면서, 이 함수를 쓰는 배선 테스트들이 이전에 잡던 것 중 **이제 못 잡는 것**이 있는가? 기준선 쪽과 현재 쪽의 producer 가 항상 같아진다면 그 테스트들 안에서 producer 축은 사실상 꺼진 것 아닌가 — 그렇다면 producer 축의 **배선**을 확인하는 테스트는 무엇이며 그것으로 충분한가? 새로 추가된 producer 테스트들(`_producer_case`, `test_producer_지문이_다르면_하네스가_비교_불가로_떨어진다` 등)이 **무엇을 확인하고 무엇을 확인하지 않는지** 구체적으로 적어라. 이 커밋에서 삭제해도 어떤 테스트도 깨지지 않는 장치가 있으면 지목하라.

## 진행 우선순위 (앞선 회차가 외부 요인으로 중단돼 재실행하는 것이다)

축 **A와 B를 먼저** 끝내고 C·D로 가라. 시간이 부족하면 C·D는 짧게 적되, **다루지 못한 축은 "미검토"라고 명시**하라 — 지적 0건과 미검토를 섞지 마라. 전체 골든 스위트(`pytest tests/golden`)를 통째로 돌릴 필요는 없다. 필요한 파일만 읽고, 확인이 필요하면 개별 테스트만 골라 돌려라.
```

</details>

### 3.3 codex 원문 출력 (무편집)

```
# Codex Adversarial Review

Target: branch diff against 64f1757
Verdict: needs-attention

출하 불가. A의 provider 테스트는 실제 하네스 배선에 도달하지 않으며, B의 effort 불변식은 읽기 경계에서 무력하다. C에서는 producer/context 불일치가 실제 하한선을 오염시킬 수 있고, 무관한 effort도 지문을 흔든다. D의 하네스 테스트는 관측 모델만 검증해 provider/effort 배선 제거를 잡지 못한다. 선택한 비-LLM 테스트 4건은 통과했고 baseline.json은 생성되지 않았다.

Findings:
- [high] producer와 context가 갈린 기준선이 다른 모델의 수치를 기존 하한선으로 위장한다 (tests/golden/baseline.py:680-710)
  `baseline_body`는 독립된 fingerprint/context를 받고 writer는 context만 검사한다. fingerprint가 anthropic/model-a이고 context가 openai/model-b인 본문이 writer를 통과했으며, 다음 model-a 실행은 fingerprint만 비교해 `유지`가 됐다. model-b의 낮은 측정치가 model-a 하한선으로 채택될 수 있으므로 사람용 기록만 틀린다는 위험 평가는 잘못됐다.
  Recommendation: 쓰기와 읽기 경계에서 `fingerprint.producer == Producer.of(context)`를 강제하고, 불일치 파일이 다음 판정에 사용되지 않는 통합 회귀 테스트를 추가하라.
- [medium] 빈 provider 테스트가 주장한 하네스 경로에 도달하지 않는다 (tests/golden/test_baseline_gate.py:543-565)
  `GOLDEN_PROVIDER=`이면 provider fixture의 `require_provider("")`가 `create_provider`에서 `ValueError`를 받아 evaluation과 writer 전에 종료된다. 이 테스트는 `RunContext(provider="")`를 직접 조립하므로, 실제 기록 경로를 태우는 empty/mixed-model 테스트와 같은 수준의 배선 증거가 아니다.
  Recommendation: 실제 fixture→evaluation→recording 경로의 기대 실패 지점을 검증하거나, 이 검사를 writer의 직접호출 계약으로 명시하고 하네스 도달 주장과 분리하라.
- [medium] 누락된 effort 키가 읽기 경로에서 정상 기준선으로 복구된다 (tests/golden/baseline.py:644-656)
  `baseline_body`의 정상 호출자는 항상 effort 키를 직렬화하지만, `RunContext.effort`의 기본값 때문에 `load_baseline()`은 디스크의 `context.effort`가 없어도 이를 None으로 보정한다. 재현 결과 해당 기준선은 동일 지문에 `유지`를 반환했다. 따라서 키 부재 가드는 직접 writer 호출만 보호하고 저장 기준선의 읽기/비교 경계에서는 불변식을 강제하지 않는다.
  Recommendation: 저장 기준선 검증 시 context.effort 키 존재를 명시적으로 요구하고, 누락 파일이 로드되지 않아 차단되는 회귀 테스트를 추가하라.
- [medium] 실제로 소비되지 않는 effort도 producer 지문을 무효화한다 (tests/golden/test_golden_eval.py:251-258)
  `run_context`는 provider와 무관하게 `settings.llm_effort`를 producer에 넣지만 factory는 이를 Anthropic에만 전달한다. OpenAI 레인에서는 결과에 영향 없는 LLM_EFFORT 변경만으로 비교 불가가 된다. Anthropic에서도 미설정은 API 기본 high인데 명시적 high와 다른 지문이 되어 동일한 실행 조건을 불필요하게 분리한다.
  Recommendation: 선택된 provider가 실제 소비하는 유효 effort를 정규화해 지문화하라. OpenAI에는 비적용 값을, Anthropic의 미설정에는 실제 기본값과 동일한 정규값을 사용하라.
- [medium] producer 배선 테스트가 provider와 effort 전달을 확인하지 않는다 (tests/golden/test_floor_gate_wiring.py:198-208)
  `_baseline_at`은 기준선과 현재 실행을 같은 `harness.run_context`로 만들고, 별도 하네스 테스트는 관측 모델만 변경한다. run_context의 provider를 하드코딩하고 effort를 항상 None으로 바꾼 재현에서도 producer 배선 테스트가 통과했다. `_producer_case`는 직접 만든 context로 compare만 검증하므로 실제 환경값 배선 누락을 잡지 못한다.
  Recommendation: 기준선 producer를 production helper와 독립된 명시값으로 만들고, GOLDEN_PROVIDER와 LLM_EFFORT 각각의 환경값이 현재 지문까지 전달되는 하네스 테스트를 추가하라.

Next steps:
- producer/context 일치와 저장 스키마를 쓰기·읽기 양쪽에서 fail-closed로 검증한다.
- provider 테스트를 실제 경계 수준에 맞게 수정하고 provider·effort 하네스 배선 테스트를 추가한다.
- 수정 후 금지된 LLM 레인을 제외한 golden focused suite와 불일치·누락 음성 테스트를 실행한다.
```

---

## 4. 정리(가공) — 지적 항목 목록

> **이 구획은 Claude가 만든 목록이다.** 원문(§2.3·§3.3)과 다른 구획이며, 여기서도
> 옳고 그름을 판정하지 않는다. codex가 준 경로·라인은 그대로 옮긴다.

### 4.1 회차 A 지적 5건 (커밋 `6cd5809`)

| # | codex 심각도 | 지적 요지 | 근거 파일·라인 (codex 표기 그대로) | 리더 질문 대응 |
|---|---|---|---|---|
| A-1 | **high** | **`fingerprint.producer`와 `context`가 갈린 기준선이 다른 모델의 수치를 기존 하한선으로 위장한다.** codex가 실제로 만들어 확인: fingerprint=anthropic/model-a, context=openai/model-b인 본문이 **writer를 통과**했고, 다음 model-a 실행이 fingerprint만 비교해 **`유지`** 를 받았다. → **저자의 위험 평가("사람이 읽는 기록만 틀어진다")는 잘못됐다** | `tests/golden/baseline.py:680-710` | **4** |
| A-2 | medium | **빈 provider 테스트가 주장한 하네스 경로에 도달하지 않는다.** `GOLDEN_PROVIDER=`이면 provider fixture의 `require_provider("")`가 `create_provider`에서 `ValueError`를 받아 **evaluation·writer 이전에 종료**된다. 이 테스트는 `RunContext(provider="")`를 직접 조립하므로 empty/mixed-model 테스트와 **같은 수준의 배선 증거가 아니다** | `tests/golden/test_baseline_gate.py:543-565` | **1** |
| A-3 | medium | **누락된 effort 키가 읽기 경로에서 정상 기준선으로 복구된다.** 쓰기 경로에 대한 저자 근거는 인정("정상 호출자는 항상 effort 키를 직렬화하지만"), 그러나 `RunContext.effort`의 **기본값 때문에 `load_baseline()`이 디스크에 키가 없어도 None으로 보정**한다. 재현 결과 그 기준선이 동일 지문에 `유지`를 반환했다 → 가드는 **직접 writer 호출만** 보호하고 저장 기준선의 읽기/비교 경계에는 불변식이 없다 | `tests/golden/baseline.py:644-656` | **2** |
| A-4 | medium | **실제로 소비되지 않는 effort도 producer 지문을 무효화한다.** `run_context`는 provider와 무관하게 `settings.llm_effort`를 producer에 넣지만 factory는 이를 **Anthropic에만** 전달한다 → OpenAI 레인에서 결과에 영향 없는 `LLM_EFFORT` 변경만으로 비교 불가. Anthropic에서도 미설정(API 기본 high)과 명시적 high가 다른 지문이 된다 | `tests/golden/test_golden_eval.py:251-258` | (질문 밖 신규) |
| A-5 | medium | **producer 배선 테스트가 provider와 effort 전달을 확인하지 않는다.** `_baseline_at`이 기준선과 현재를 **같은 `harness.run_context`** 로 만들고, 하네스 테스트는 관측 모델만 바꾼다. codex 재현: `run_context`의 **provider를 하드코딩하고 effort를 항상 None으로 바꿔도 producer 배선 테스트가 통과**했다. `_producer_case`는 직접 만든 context로 `compare`만 검증해 환경값 배선 누락을 못 잡는다 | `tests/golden/test_floor_gate_wiring.py:198-208` | **3·5** |

### 4.2 회차 B 지적 4건

| # | codex 심각도 | 지적 요지 | 근거 파일·라인 (codex 표기 그대로) | 리더 질문 대응 |
|---|---|---|---|---|
| B-1 | **high** | 조건부 표기가 실제 CI 조건과 결속되지 않는다. 잡 이름이 실재하고 조건 문자열이 **아무 추적 파일**이면 통과 → 무관한 파일로 바꿔도 위반 0건. 추가로 `_normalize_cell`이 인라인 코드 안의 `**`까지 지워, 원문상 **미추적 경로**·**없는 잡**이 실재 값으로 **변조되어** 통과한다 | `tests/test_harness_scope_reach.py:558-564` | 6·7 |
| B-2 | **high** | `docs/golden/` 디렉터리 전체 무시가 근거(현재 13개 PDF/HWPX)보다 넓어, 앞으로 생길 manifest·checksum·복구 코드·다른 형식 원본까지 영구 은폐한다. "한 번도 추적된 적 없다"는 사실은 범위를 정당화하지 않고 오히려 유실 미감지 위험을 확정한다 | `.gitignore:63-70` | 8 |
| B-3 | medium | "9%p는 비교 불가였다"의 **부분 지지**. 3커밋(`eae75c7`·`0894854`·`a4c9fd9`)은 판정 기준을 실제로 바꿔 비교 불가 자체는 성립하나, `85ca2f5`는 주석뿐이고 `c43cae5`는 기존 분자 조건을 보존 → "5커밋 전부" 서술과 "설명할 하락이 없다"는 **종결이 과장**이다 | `docs/migration/_workspace/04_goldenset-first-run.md:167-175` | 9 |
| B-4 | medium | "0/36은 API 오류" 판정에 **감사 가능한 실행 증거가 없다**. 문서 서술 외에 원시 로그·리포트가 없고, `convert_all`이 모든 `LLMProviderError`를 `None`으로 축약해 36건이 같은 400이었는지 사후 확인 불가. 개연성은 높으나 실수집 입력 전용 오류의 병존을 반증하지 못한다 | `docs/migration/_workspace/04_goldenset-first-run.md:196-209` | 10 |

### 4.3 codex가 **동의한** 부분 (원문에서 그대로 추출)

리더가 "동의하는 부분도 명시하라"고 요구했다. codex 원문이 명시적으로 지지·확인한 것:

**회차 A에서:**

- **저자의 G4(effort) 근거 — 쓰기 경로에 한해 지지**: "`baseline_body`의 **정상 호출자는
  항상 effort 키를 직렬화하지만**…" → 저자가 든 "pydantic dump라 키가 언제나 있다"는
  **쓰기 경로에서는 참**이다. codex가 뒤집은 것은 그 범위이지(읽기 경계) 근거 자체가 아니다.
- **producer 축 ⑶(목록 그대로) 근거 — 판정 경로에 가드 없음 지지**: A-1·A-3이 모두
  "writer는 context만 검사한다" / "가드는 직접 writer 호출만 보호한다"로 **기록 경로와
  판정·읽기 경로가 갈라져 있다는 저자의 관찰을 확인**한다. 저자가 ⑶의 근거로 든 사실은 참이고,
  codex는 그 사실이 **저자가 본 것보다 더 넓은 문제**임을 지적한 것이다.
- **실행 제약 준수 확인**: "선택한 비-LLM 테스트 **4건은 통과**했고 **baseline.json은
  생성되지 않았다**."

**회차 B에서:**

- **B 축 경계 시험 — 저자 주장 지지**: "앵커 때문에 괄호가 남은 깨진 조건부가 단순형으로
  읽히는 **추가 사례는 없었다**" → 리더 질문 7("우회가 남았는지")에 대해 **정규식 경계
  자체는 닫혀 있다**는 확인. 다만 다른 층위(임의 추적 파일·`**` 정규화)에서 뚫린다는 것이
  B-1이다.
- **C 축 — 저자 근거의 사실성 부분 인정**: "drafts의 기본 저장 경로와 redaction·쓰기 직전
  재검사는 **실재한다**" → `app/easyread/collection.py`가 `redact_contacts` 안전장치를
  걸어 뒀다는 저자 주장은 참. 다만 "커밋 대상"은 **의도일 뿐 추적 이력 0건**이라는 단서를
  붙였다. (즉 `docs/golden-drafts/`를 무시 대상에서 뺀 판단 자체를 뒤집지는 않았다.)
- **D 축 — 비교 불가 결론 자체는 지지**: "`eae75c7`, `0894854`, `a4c9fd9`는 `check_style`
  판정 기준을 바꿔 과거 수치와의 baseline 비교를 깨므로 **'비교 불가' 자체는 지지된다**."
- **실행 제약 준수 확인**: "허용 테스트는 **35 passed**였고 **baseline.json은 계속
  부재한다**."
- **표 치환(설명 7)의 성격**: "두 표 치환은 **사실 표기의 정확성만 높였고** 게이트 실행·강도는
  바꾸지 않았다" → `f220682`의 Quality 행 수정이 **정확성 개선**이라는 점은 인정하되,
  강도 상승은 아니라는 판정.

### 4.4 리더 질문 10개의 코드-대응 현황

codex가 **어느 질문을 실제로 다뤘는지**만 적는다. 답의 옳고 그름은 판정하지 않는다.

| 리더 질문 | 다룬 회차·지적 | codex가 답을 냈는가 |
|---|---|---|
| 1. G1이 자기 전제를 자기가 만든 것인가 | A-2 | 예 — 도달 실패 지점을 `create_provider` `ValueError`로 특정 |
| 2. G4를 안 고친 판단이 옳은가 | A-3 | 예 — 쓰기 경로는 지지, **읽기 경로에 살아 있는 분기** 지적 |
| 3. producer 축 ⑴⑵⑶ 설계 | A-4·A-5 (⑶ 근거는 A-1·A-3이 확인) | 예 — ⑶ 근거 참, 다만 ⑵의 부작용(A-4) 신규 |
| 4. `producer`/`context` 이중 기록 위험 평가 | **A-1 (high)** | 예 — **위험 평가가 잘못됐다고 판정**, 재현 경로 제시 |
| 5. 기존 테스트 수정이 정당한가 | A-5 | 예 — 배선 확인이 **무뎌졌다**고 판정, 재현 제시 |
| 6. 조건부 형식의 값어치 | B-1 | 예 — "새 자기서명 통과 카드" |
| 7. 깨진 형식의 단순형 오독 | B-1 (부분 동의) | 예 — 정규식 경계는 닫힘, **다른 층위**에서 우회 |
| 8. `.gitignore` 판단 | B-2 (high) | 예 — 범위가 근거보다 넓다 |
| 9. "9%p는 비교 불가" | B-3 | 예 — 결론은 지지, **5커밋 전부라는 서술은 과장** |
| 10. "0/36은 API 오류" | B-4 | 예 — 개연성 인정, **감사 증거 부재** |

**미검토 축 없음** — 두 회차가 리더 질문 10개를 전부 다뤘다.

### 4.5 전제 확인 필요

codex 원문의 사실 주장 중 **`migration-reviewer`가 확인해야 할 것**(여기서 판정하지 않는다):

- **A-1의 재현 경로** — "fingerprint=anthropic/model-a, context=openai/model-b인 본문이
  writer를 통과했고 다음 model-a 실행이 `유지`를 받았다". codex가 **명령을 원문에 남기지
  않았다** — 재현 절차를 직접 구성해 확인해야 한다. 이 항목이 A 회차 유일한 high다.
- **A-3의 재현** — `load_baseline()`이 `context.effort` 키 없는 디스크 파일을 None으로
  보정하는지. 마찬가지로 명령이 원문에 없다.
- **A-5의 재현** — "run_context의 provider를 하드코딩하고 effort를 항상 None으로 바꾼
  재현에서도 producer 배선 테스트가 통과했다". 명령 미제시.
- **A-4의 사실 주장** — "factory는 effort를 Anthropic에만 전달한다", "Anthropic 미설정은
  API 기본 high". 후자는 벤더 API 기본값에 대한 주장이라 별도 확인이 필요하다.
- B-1의 `_normalize_cell` `**` 삭제 경로 — codex가 제시한 재현 명령이 실제로 0을 출력하는지.
  **재현 명령이 원문에 통째로 실려 있어 그대로 실행 가능**하다.
- B-1이 지목한 라인 `tests/test_harness_scope_reach.py:558-564`가 이 커밋에서 실제로 바뀐
  범위인지 (diff상 `_CI_CONDITIONAL_TOKEN` 정의는 322행 부근, `_untracked_problem`은 537행
  부근이다 — codex 표기 라인은 **재실행 시점 파일 기준**일 수 있다).
- B-3의 "`85ca2f5`의 `style_rules.py` 변경은 주석뿐" — 실측상 이 커밋은 `style_rules.py`를
  `3 insertions / 3 deletions`로 바꿨다. 주석인지 동작인지 확인 필요.
- B-2의 `git check-ignore -v` 결과 4건.

### 4.6 미실행·실패 항목

- **회차 A 1차 실행 유실** — §0에 상세. 복구 불가 확인 완료. **재실행이 성공해 §3에 원문이
  있으므로, 이 게이트에 "codex 리뷰 누락"은 없다.** 다만 1차와 2차는 **다른 실행**이며,
  1차가 무엇을 지적하려 했는지는 영영 알 수 없다(잘린 하트비트 2건이 남긴 단서:
  "provider 빈 값 테스트가 다른 두 테스트와 같은 수준의 하네스 배선 증거가 아니…" — 2차의
  A-2와 같은 방향으로 보이나 **추정이며 근거로 쓰지 마라**).
- **codex 재현 명령 부재** — 회차 A의 5건 중 4건에 실행 가능한 재현 명령이 없다(§4.5).
  회차 B의 B-1은 재현 명령이 원문에 통째로 실려 있어 대비된다.
- **`-m llm` 레인 미실행** (금지 항목, 크레딧 소진). 따라서 이 리뷰는 정적 분석과 비-llm
  테스트 실행에 근거하며, 실제 변환 실행으로 확인한 것이 아니다.
- **`tests/golden/baseline.json` 미생성** (금지 항목). 부재 유지 확인.
