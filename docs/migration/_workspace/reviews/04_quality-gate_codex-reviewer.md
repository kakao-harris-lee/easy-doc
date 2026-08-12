# 04_quality-gate — codex 독립 리뷰 (1회차)

> 이 문서는 `codex-reviewer` 가 작성한다. **3번 구획의 codex 원문은 무편집이다** — 요약·판정·심각도 부여·중복 병합·표현 다듬기를 하지 않았다. 판정과 종합은 `migration-reviewer` 의 2차 교차 대조(`04_quality-gate_cross.md`)와 리더의 몫이다.
>
> `{phase}_{scope}` 어간은 리더가 1단계 호출에서 지정한 `04_quality-gate` 를 그대로 썼다.

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 회차 | 1회차 |
| 실행 시각 | 2026-08-12 20:02:03 +0900 → 20:07:30 +0900 |
| 소요 | 327초 |
| **스크립트 종료 코드** | **0** (= 리뷰가 돌았고 출력이 비어 있지 않다. 리뷰 근거로 유효) |
| stdout / stderr 크기 | 5,953 바이트 / 16,966 바이트 |
| 리뷰 대상 | 커밋 `c43cae5ba44efa1d20355391b90cfa5ec7043b9d` **단 하나** (`feat: 품질 합격선 — 상대 하한선·코퍼스 지문·절대 팩트 게이트`) |
| 브랜치 | `feat/kotlin-migration-harness` |
| base | `ca2fc7b2b307757511c7ac9c0979180c23327b24` (`git rev-parse c43cae5^` 로 확인 = 직전 커밋) |
| diff 범위 | `merge-base(HEAD, base)..HEAD` = 해당 커밋 1개, 7개 파일 (+1822 / −86) |
| 모드 | `adversarial` (focus text 포함) |
| 스크립트 | `.claude/skills/codex-review/scripts/codex-review.sh` |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택) |
| codex CLI / node | `codex-cli 0.147.0` / `v22.21.1` |
| thread id | `019ff5a2-f680-72d0-b1ec-98f2cc02e2e7` |
| turn id | `019ff5a2-f7df-7b20-a80e-205d1210e036` |

### 스크립트가 stderr 에 남긴 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs ca2fc7b2b307757511c7ac9c0979180c23327b24
codex-review: 대상 판정 = non-empty (merge-base=ca2fc7b2b307, 변경 파일 7개 (branch 모드는 커밋된 변경만 센다))
```

### 실행 명령

스크립트 인자 그대로:

```bash
./.claude/skills/codex-review/scripts/codex-review.sh \
  adversarial \
  --base ca2fc7b2b307757511c7ac9c0979180c23327b24 \
  "<2번 구획의 focus text 전문>"
```

헬퍼로 전개된 실제 명령:

```bash
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base ca2fc7b2b307757511c7ac9c0979180c23327b24 '<focus text>'
```

파이프로 감싸지 않았다. 출력은 파일로 직접 리다이렉트했다.

### 리뷰 대상 파일 (7개)

```
 app/easyread/goldenset.py              |  42 +++
 tests/golden/baseline.py               | 548 +++++++++++++++++++++++++++++++++
 tests/golden/conftest.py               |  21 ++
 tests/golden/report.py                 | 183 +++++++++++
 tests/golden/test_baseline_gate.py     | 466 ++++++++++++++++++++++++++++
 tests/golden/test_floor_gate_wiring.py | 282 +++++++++++++++--
 tests/golden/test_golden_eval.py       | 366 ++++++++++++++++++----
 7 files changed, 1822 insertions(+), 86 deletions(-)
```

### 제공한 맥락

focus text 안에 세 축의 정의와 "지켜야 하는 조건" (a)(b)(c) 를 단정문으로 넣고, 판정 대상 심볼을 지목했다 — `find_fact_losses`, `RequiredFact.retained_in`, `corpus_sha256`, `criteria_sha256`, `corpus_payload`, `criteria_payload`, `GOLDEN_RECORD_BASELINE`, `DEFAULT_FIDELITY_FLOOR`, `tests/golden/baseline.json`. codex 는 저장소를 직접 읽고 명령을 실행할 수 있는 모드로 돌았다.

**Claude 쪽 원인 추정이나 의심 지점은 프롬프트에 넣지 않았다.** 조건과 질문만 넘겼다. 다른 리뷰어의 산출물(`04_quality-gate_*`)은 읽지 않았다 — 호출 시점에 해당 어간의 파일은 존재하지 않았다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
이 커밋은 골든셋 품질 게이트를 세 축으로 재구성한다. ① 필수 정보 보존 = 절대 차단 (`app/easyread/goldenset.py` 의 `find_fact_losses` / `RequiredFact.retained_in` 부분문자열 검사, 누락 0건이 아니면 실패, LLM-as-judge 미사용). ② 규칙 통과율 = 상대 차단 (절대 수치가 아니라 `tests/golden/baseline.json` 에 직전 기록된 측정치 대비 하락 0). ③ judge = 비차단 (점수는 재되 경고·리포트로만). 상대 기준의 우회는 코퍼스 지문 `corpus_sha256` 과 판정 기준 지문 `criteria_sha256` 이 막는다 — 지문이 다르면 수치를 읽지 않고 "비교 불가"로 차단한다.

지켜야 하는 조건: (a) 게이트를 통과시키는 모든 경로는 실제로 측정을 수행해야 한다 — 측정 대상 0건, 기록 모드, 기준선 파일 부재, 비교 불가 상태가 성공 종료 코드로 끝나면 안 된다. (b) 상대 하한선은 분모(코퍼스 구성)나 자[尺](판정 기준)를 바꿔 우회할 수 없어야 한다. (c) 절대 팩트 게이트는 결정적이어야 한다 — 모델도 난수도 개입하지 않아야 한다.

다음을 찾아라.

1. 상대 하한선을 우회하는 경로. 두 지문이 잡지 못하는 방식으로 통과율의 분모나 분자를 움직일 수 있는가. `corpus_payload` / `criteria_payload` 가 수집하지 않는 입력 중 통과율에 실제로 영향을 주는 것이 있는가. 문서를 제외·치환·합성하거나 판정을 건너뛰게 만들어 지문을 유지한 채 통과율만 올리는 경로가 있는가.

2. 기록 실행(`GOLDEN_RECORD_BASELINE=1`, `tests/golden/baseline.py`)이 게이트를 닫을 수 있는가. 기록 모드, 기준선 파일 부재, 지문 불일치로 인한 비교 불가 상태에서 pytest 가 성공 코드로 끝나 CI 가 초록으로 보이는 경로가 있는가. 기록과 검증이 같은 실행에서 일어나 방금 기록한 값을 자기 자신과 비교하는 자리가 있는가.

3. `RequiredFact.retained_in` 이 순수 부분문자열 검사다. 이 검사가 통과하지만 필수 정보가 실질적으로 손상·부정·왜곡된 경우가 있는가 (부정문 삽입, 수치의 자릿수·단위 변화, 다른 맥락에 우연히 포함, 문장 분절로 의미가 뒤집히는 경우 등). 반대로 정보는 온전히 보존됐는데 실패하는 경우가 있는가.

4. `criteria_sha256` 은 `style_rules` 모듈의 대문자 상수만 수집한다. 통과율 판정에 실제로 영향을 주는 기준 중 대문자 상수가 아닌 것(소문자 변수, 함수 본문에 인라인된 임계값, 다른 모듈의 상수, 정규식 리터럴, 기본 인자값)이 있는가. 있다면 그것을 바꿔 같은 출력이 더 높은 통과율을 받게 만들어도 지문은 그대로인가.

5. 재현 테스트(`tests/golden/test_baseline_gate.py` 30건, `test_floor_gate_wiring.py` 13건, `test_golden_eval.py`)가 위 기제를 실제로 붙잡는가. 각 장치(지문 비교, 하락 판정, 절대 팩트 게이트, 기록 모드 분기, 리포트 배선)를 하나씩 무력화했을 때 어떤 테스트도 실패하지 않는 장치가 있는가. 구현을 그대로 복사해 항상 통과하는 구조의 테스트, 또는 예외 메시지 문자열만 확인해 실제 차단 동작은 검증하지 않는 테스트가 있는가.

6. judge 를 비차단으로 내리면서(`DEFAULT_FIDELITY_FLOOR` 가 그 축이었다) 사라진 보장 중, 절대 팩트 게이트와 상대 통과율 두 축이 인수하지 못한 것이 있는가.

가능한 항목은 실제로 실행해 재현하라. 산출물에서 **직접 실행해 재현한 항목과 코드를 읽고 판단한 항목을 반드시 구분해 표시하라.**
```

---

## 3. codex 원문 출력 (무편집)

아래는 `codex_out.txt` 전문이다. 한 글자도 고치지 않았다. 잘림 없음(출력 끝의 `Next steps:` 목록까지 온전히 수신).

```text
# Codex Adversarial Review

Target: branch diff against ca2fc7b2b307757511c7ac9c0979180c23327b24
Verdict: needs-attention

NO-SHIP: 실제 CI 게이트는 측정 없이 초록이 되며, 명시 실행도 자격증명 부재와 기록 모드에서 fail-open이다. 기준 지문은 실제 판정 로직을 빠뜨리고, 절대 팩트 게이트는 부정·날조를 차단하지 못한다.

Findings:
- [high] CI가 실제 골든셋 평가를 전혀 실행하지 않는다 (tests/golden/test_golden_eval.py:78)
  [직접 재현] 기본 선택 조건으로 collect한 결과 66개 중 실제 평가 3개가 모두 deselect되고 나머지 63개 단위·배선 테스트만 선택됐다. [코드 판독] 모듈 전체가 `llm`으로 표시됐지만 현재 pytest 기본 옵션은 `not llm`이고 CI도 별도 `-m llm` 작업 없이 기본 pytest만 실행한다. HEAD에는 `tests/golden/baseline.json`도 없다. 따라서 PR CI는 실제 변환, 기준선 부재, 지문 비교, 팩트 손실을 한 건도 측정하지 않고 성공할 수 있다.
  Recommendation: 커밋된 실측 baseline과 자격증명을 사용하는 전용 필수 CI 작업을 추가하고, 실제 평가 문서 수와 세 차단축의 실행 여부를 종료 시 검증하라.
- [high] 명시적으로 게이트를 실행해도 API 키가 없으면 성공 종료한다 (tests/golden/test_golden_eval.py:145-155)
  [직접 재현] API 키를 제거한 환경에서 `pytest golden -m llm -s -p no:cacheprovider -q`는 `3 skipped, 63 deselected`와 종료 코드 0을 반환했다. provider fixture의 skip이 `outcomes`와 `evaluation`에 의존하는 팩트 및 하한선 테스트까지 모두 건너뛰므로 측정 0건이 초록으로 보인다. 이는 조건 (a)의 직접 위반이다.
  Recommendation: 게이트 실행에서는 provider 부재를 skip이 아닌 session-level 실패로 처리하고, 종료 전에 변환 시도 수·평가 수·차단축 실행 수가 기대 코퍼스 수와 같은지 fail-closed로 검사하라.
- [high] criteria_sha256가 실제 판정 기준 변경을 놓친다 (tests/golden/baseline.py:168-191)
  [직접 재현] `_SENTENCE_SPLIT`을 전체 입력을 제거하는 정규식으로 바꾸자 50자 초과 문장의 평가가 `스타일위반`에서 통과로 바뀌었지만 `criteria_sha256`은 동일했다. [코드 판독] 지문은 대문자 비호출 전역만 수집하므로 `_SENTENCE_SPLIT`, `_LIST_MARKER`, 함수 본문, 인라인 조건뿐 아니라 `evaluate_rules`, `DocumentEvaluation.passed`, `measure`의 분자·분모 정의도 제외된다. 기존 재현 테스트도 대문자 상수 변경만 검사한다. 같은 코퍼스와 출력이 더 높은 통과율을 받아도 비교 가능으로 남는 상대 하한선 우회다.
  Recommendation: 판정에 참여하는 모든 정규식·상수와 style/evaluator/measurement 로직의 버전 또는 소스 digest를 닫힌 criteria payload에 포함하고, 각 입력을 하나씩 바꿔 지문 드리프트를 검증하는 mutation 테스트를 추가하라.
- [high] 부분문자열 팩트 게이트가 의미가 뒤집힌 결과를 보존으로 판정한다 (app/easyread/goldenset.py:32-35)
  [직접 재현] canonical이 `34만 원`일 때 `지원 금액은 34만 원이 아닙니다`는 `retained_in=True`이고 `find_fact_losses=[]`였다. 반대로 같은 금액인 `340,000원`은 보존 실패였다. 검사는 결정적이지만 주체·관계·부정·단위 정규화를 보지 않아, 틀린 안내문을 절대 게이트가 승인하고 온전한 의역은 차단할 수 있다.
  Recommendation: 필수 사실을 주체·관계·정규화된 값/단위·극성으로 구조화해 결정적으로 비교하고, 최소한 부정 범위·숫자/단위 정규화·문맥 귀속에 대한 양방향 공격 테스트를 추가하라.
- [high] judge 비차단 전환으로 새 사실 날조에 대한 차단 보장이 사라졌다 (tests/golden/test_golden_eval.py:332-384)
  [직접 재현] 원문 사실 `34만 원`을 유지하면서 `누구나 99만 원을 더 받습니다`를 추가한 출력은 `evaluate_rules=[]`, `find_fact_losses=[]`로 두 차단축을 모두 통과했다. [코드 판독] judge는 낮은 fidelity를 계산해 warning과 report에만 기록하고 assert하지 않는다. 기존 `DEFAULT_FIDELITY_FLOOR`가 막던 추가 자격요건·금액·기한 날조와 모순은 절대 팩트 보존이나 상대 스타일 통과율이 인수하지 못했다.
  Recommendation: judge를 내리기 전에 출력의 신규 숫자·금액·날짜·자격 주장과 원문 모순을 차단하는 결정적 역방향 팩트 게이트를 추가하거나, 대체 장치가 준비될 때까지 fidelity floor를 차단 상태로 유지하라.
- [high] 변경이 없는 기록 모드는 성공해 게이트를 닫을 수 있다 (tests/golden/test_golden_eval.py:410-437)
  [직접 재현] 동일한 baseline을 반환하고 `recording_requested=True`, `baseline_changes=[]`로 실행하자 하한선 테스트가 예외 없이 반환했다. [코드 판독] 구현은 변경이 없으면 기록 분기에서 정상 판정으로 떨어지며 배선 테스트도 이 성공을 의도적으로 고정한다. 이는 기록 모드는 성공 종료하면 안 되고 반드시 플래그 없는 별도 검증 실행이 필요하다는 조건 (a)를 위반한다.
  Recommendation: `GOLDEN_RECORD_BASELINE`이 설정된 모든 실행은 기록 여부와 무관하게 최종적으로 비정상 종료시키고, 플래그 없는 후속 실행만 게이트를 닫도록 하라.

Next steps:
- 실측 baseline을 커밋하고 실제 LLM 평가를 필수 CI 작업으로 연결한다.
- 무자격증명·0건·skip·기록 모드를 모두 비정상 종료로 고정한다.
- criteria 지문 범위를 실제 판정 함수와 비공개 정규식까지 확장한다.
- 구조화된 양방향 팩트 검증으로 부정·왜곡·신규 날조를 차단한다.
```

---

## 4. 정리(가공)

> **이 구획은 Claude 가 목록화한 것이다.** 원문(3번)과 다른 구획이며, 여기서도 옳고 그름·심각도 재부여·오탐 여부를 판정하지 않는다. 심각도 `[high]` 는 codex 가 붙인 값 그대로이고, 파일·라인도 codex 가 준 것을 그대로 옮겼다(다시 세지 않았다).

codex 판정: **`Verdict: needs-attention`** / 머리말 `NO-SHIP`.

| # | codex 지적 (요지) | codex 심각도 | codex 근거 위치 | 재현 여부 |
|---|---|---|---|---|
| 1 | CI가 실제 골든셋 평가를 전혀 실행하지 않는다 | high | `tests/golden/test_golden_eval.py:78` | 직접 재현 + 코드 판독 |
| 2 | 명시적으로 게이트를 실행해도 API 키가 없으면 성공 종료한다 | high | `tests/golden/test_golden_eval.py:145-155` | 직접 재현 |
| 3 | `criteria_sha256` 가 실제 판정 기준 변경을 놓친다 | high | `tests/golden/baseline.py:168-191` | 직접 재현 + 코드 판독 |
| 4 | 부분문자열 팩트 게이트가 의미가 뒤집힌 결과를 보존으로 판정한다 | high | `app/easyread/goldenset.py:32-35` | 직접 재현 |
| 5 | judge 비차단 전환으로 새 사실 날조에 대한 차단 보장이 사라졌다 | high | `tests/golden/test_golden_eval.py:332-384` | 직접 재현 + 코드 판독 |
| 6 | 변경이 없는 기록 모드는 성공해 게이트를 닫을 수 있다 | high | `tests/golden/test_golden_eval.py:410-437` | 직접 재현 + 코드 판독 |

### 재현 방식 구분 (codex 가 스스로 표기한 것)

codex 는 focus text 의 요구대로 `[직접 재현]` 과 `[코드 판독]` 을 태그로 구분했다. 6건 전부가 `[직접 재현]` 을 포함하며, 그중 4건(#1·#3·#5·#6)은 `[코드 판독]` 근거를 함께 붙였다.

codex 가 **실행했다고 기술한** 재현 내용:

- #1 — 기본 선택 조건 collect 결과 `66개 중 실제 평가 3개가 모두 deselect`, 나머지 63개만 선택
- #2 — API 키 제거 환경에서 `pytest golden -m llm -s -p no:cacheprovider -q` → `3 skipped, 63 deselected`, 종료 코드 0
- #3 — `_SENTENCE_SPLIT` 을 전체 입력 제거 정규식으로 교체 → 50자 초과 문장 평가가 위반→통과로 바뀌었으나 `criteria_sha256` 동일
- #4 — canonical `34만 원` 에 대해 `지원 금액은 34만 원이 아닙니다` → `retained_in=True`, `find_fact_losses=[]` / 역방향으로 `340,000원` → 보존 실패
- #5 — `34만 원` 유지 + `누구나 99만 원을 더 받습니다` 추가 → `evaluate_rules=[]`, `find_fact_losses=[]` 로 두 축 통과
- #6 — 동일 baseline + `recording_requested=True`, `baseline_changes=[]` → 하한선 테스트가 예외 없이 반환

호출 로그(`codex_err.txt`)상 codex 는 `.venv/bin/pytest tests/golden -p no:cacheprovider -q` 및 `--collect-only` 를 실제로 실행했고, `style_rules.py`·`pyproject.toml`·`.github/` 워크플로·`tests/golden/documents/001-*.json` 까지 읽었다.

### 리더 질문 6개와의 대응 (누락 확인용, 판정 아님)

| 리더 질문 | codex 응답 위치 |
|---|---|
| 상대 하한선 우회 경로 | #3 (자[尺] 축). 코퍼스 축 단독 우회는 별도 항목으로 제기되지 않음 |
| 기록 실행이 게이트를 닫는가 | #6 |
| 절대 팩트 게이트가 놓치는 것 | #4 (부정문·단위 정규화 양방향) |
| 재현 테스트 40건이 기제를 붙잡는가 | #3 말미(“기존 재현 테스트도 대문자 상수 변경만 검사한다”), #6 말미(“배선 테스트도 이 성공을 의도적으로 고정한다”) |
| judge 비차단으로 잃은 보장 | #5 |
| `criteria_sha256` 대문자 상수 경계가 실제 위험인가 | #3 |

여섯 질문 모두에 대응하는 서술이 있다. 다만 "코퍼스를 빼서 분모를 줄이는" 축에 대한 독립 지적은 원문에 없다 — codex 가 그 축을 검토하고 문제없다고 본 것인지, 다루지 않은 것인지는 원문만으로 판별되지 않는다. **판별은 `migration-reviewer` 의 교차 대조에 맡긴다.**

### 전제 확인 필요 (삭제하지 않고 표시만 함)

원문을 그대로 두되, 다음 서술은 이 커밋 diff 밖의 저장소 상태에 의존하므로 종합 단계에서 사실 확인이 필요하다.

- #1 의 "현재 pytest 기본 옵션은 `not llm`", "CI도 별도 `-m llm` 작업 없이 기본 pytest만 실행", "HEAD에는 `tests/golden/baseline.json` 도 없다" — `pyproject.toml` 과 `.github/` 워크플로, HEAD 트리 상태에 대한 진술이다. (리더가 호출 시 "기준선은 `tests/golden/baseline.json`(아직 없음)"이라고 명시한 것과 마지막 항목은 일치한다.)
- #2 의 재현은 API 키가 없는 환경에서 이뤄졌다고 기술돼 있다. 실행 환경의 자격증명 상태는 원문에 더 적시돼 있지 않다.

이 표시는 codex 지적에 대한 반박이 아니다. 원문은 손대지 않았다.

---

## 5. 미실행·실패 항목

- **없다.** codex 호출은 1회에 성공했고(종료 코드 0), 재시도가 필요하지 않았다. ⚠ codex 리뷰 누락 사유 없음.
- `--dry-run` 사전 확인이 종료 코드 6으로 끝난 것은 정상이다(실행하지 않음을 뜻하며 성공 0과 구분된다). 대상 판정은 그 시점에 이미 `non-empty (변경 파일 7개)` 였다.
- 출력 잘림 없음. `Next steps:` 4개 항목까지 수신했고 stdout 5,953바이트 전체를 3번 구획에 실었다.
- 민감 데이터 미포함: focus text 에 사용자 문서 본문·실제 암호문·키·개인정보를 싣지 않았다. 심볼명·파일 경로·게이트 조건만 전달했다. codex 가 원문에 인용한 `34만 원` 등은 골든셋 공공 안내문서의 공개 수치이며 개인정보가 아니다.
- 이전 회차 없음(이 어간의 첫 리뷰). 이 파일을 덮어쓰지 말고, 재리뷰 시 회차 구획을 추가하거나 새 파일로 남길 것.

---

## 6. 전달

- **→ `migration-reviewer`**: 이 파일 경로를 그대로 전달한다. 요약본이 아니라 원문(3번 구획)이 입력이다.
- 교차 종합 대상 어간: `04_quality-gate` — 짝이 되는 파일은 `04_quality-gate_migration-reviewer.md`, 산출물은 `04_quality-gate_cross.md`.
- 이 에이전트는 codex 지적의 옳고 그름을 판정하지 않았고, Phase 종료 가부도 판단하지 않았다.
