# 06_baseline-guard — Claude 독립 리뷰 (1차)

- **회차**: 1차 (독립 리뷰). codex 산출물을 읽지 않았다 — 같은 디렉터리에 `06_baseline-guard_codex-reviewer.md` 가 존재하지만 열지 않았고, 교차 대조는 2차 호출에서 한다.
- **대상**: `git diff 6cd5809~1..f220682` (4커밋)
  - `6cd5809` fix: 가드의 거부 방향을 태우고 지문에 producer 축을 넣는다
  - `c8bc9f8` feat: 조건부 CI 표기를 어휘에 넣고, 골든 원본을 무시 대상으로 좁힌다
  - `958561a` docs: 9%p 질문을 종결하고 크레딧 소진 실행을 기록한다
  - `f220682` docs: 게이트 표 Quality 행의 실행 경로를 사실에 맞춘다
- **기준 판정 시점**: HEAD = `f220682`.
- **참조**: 계획 §4.6(게이트2·5) · §5 Phase 0 · §6 검증 매트릭스 · `kotlin-migration` 스킬 「선언한 범위와 실제 도달을 대조한다」 · `CLAUDE.md` 「범위 대조」 · master-plan 6.2·7
- **작업 트리 주의**: 리뷰 중 `tests/golden/baseline.py` · `test_baseline_gate.py` · `test_floor_gate_wiring.py` 에 **미커밋 변경 432줄**이 나타났다(다른 레인). 리뷰 대상으로 삼지 않았고, 아래 모든 행 번호는 `git show f220682:<파일>` 기준이다.
- **금지 준수**: `tests/golden/baseline.json` 을 만들지 않았다(모든 재현은 `tempfile` 경로로만 썼다). `-m llm` 을 실행하지 않았다. 저장소 파일을 수정하지 않았다 — 변이 시험은 스크래치패드의 pytest 플러그인으로 런타임 패치만 했다.

---

## 0. 요약

producer 축의 **세 필드 중 관측 모델 하나만** 실제로 배선돼 있다. `provider`·`effort` 는 배선 대조가 없어 하드코딩해도 스위트가 초록으로 남는다(변이 2건, 각 121 passed). 그보다 심각한 것은 축이 **답하겠다고 선언한 질문 자체**다 — "누가 낸 수치인가"는 2026-08-13 실행에서 실제로 갈렸는데(36건은 아무도 내지 않았다) 새 축도 기존 가드 4개도 그 실행을 **비교 가능·기록 가능**으로 판정한다. 그 상태로 기록된 하한선은 다음 실행을 실측으로 **잘못 차단**하고, 실수집 축은 영구히 열린다(§2.1 재현).

동의하는 부분도 적지 않다 — 관측 모델 축의 배선, G2·G3 의 하네스 경로 도달, 깨진 조건부 표기의 오독 차단, 9%p 종결의 근거는 전부 확인했고 반증을 찾지 못했다(§4).

| # | 지적 | 심각도 | 축 |
|---|---|---|---|
| C-1 | 부분 실패 실행이 하한선을 만든다 — 가드 4개·producer 축 전부 통과 | **Critical(장치)** | 도달 범위(테스트 적정성) |
| M-1 | producer 3필드 중 `provider`·`effort` 에 배선 대조가 없다 | Major | 도달 범위(테스트 적정성) |
| M-2 | `provider` 는 증거가 아니라 주장인데 차단축에 들어갔다 — 증거 필드가 이미 있다 | Major | parity 위험 |
| M-3 | `effort` 는 openai 실행에서 유령 축 — 안 쓰이는 값이 하한선을 리셋한다 | Major | parity 위험 |
| M-4 | G1 의 "하네스 경로로 도달한다" 주장이 거짓 — G4 와 같은 부류다 | Major | 도달 범위(테스트 적정성) |
| M-5 | 게이트 표가 **실행 0회**인 잡을 `ci:` 로 표기 — `local:` 보다 도달 정보가 줄었다 | Major | 도달 범위 |
| M-6 | `docs/golden/` 13건이 P1 반출 목록에 없는 채 `git status` 에서 사라졌다 | Major | 보안 불변식(데이터 보존) |
| m-1 | `.gitignore` 디렉터리 전체 무시가 근거보다 넓고, 두 번째 근거는 대상에 안 닿는다 | Minor | 보안 불변식 |
| m-2 | 조건부 CI 표기는 과장을 **표현 가능**하게 할 뿐 강제하지 않는다 | Minor | 도달 범위 |

---

## 1. 축별 판정 요약

| 축 | 판정 |
|---|---|
| 계약 준수 | **검토함 — 해당 없음.** 이번 4커밋은 HTTP 계약·`contracts/easy-doc-v1.yaml`·`frontend/src/api/` 어디에도 닿지 않는다 |
| parity 위험 | **검토함 — 지적 2건** (M-2·M-3). 이 변경은 Phase 5 종료 게이트가 쓸 품질 하한선의 **판정 규칙**이므로 parity 축으로 본다 |
| 보안 불변식 | **검토함 — 지적 2건** (M-6·m-1). 로그·키 노출 경로는 별도로 봤고 지적 없음(§1.1) |
| Kotlin/Spring 관용성 | **검토함 — 해당 없음.** Kotlin 코드 변경 0줄 |
| 테스트 적정성 | **검토함 — 지적 3건** (C-1·M-1·M-4) |
| **도달 범위** | **검토함 — 지적 5건** (C-1·M-1·M-4·M-5·m-2) |

### 1.1 보안 불변식 — 검토했으나 지적 없는 항목

- `llm-lane` 잡이 `ANTHROPIC_API_KEY` 를 로그에 찍지 않는다. 빈 값 검사도 `-z` 로만 하고 **길이조차 찍지 않는다**(`.github/workflows/ci.yml`, "키 값 자체는 절대 찍지 않는다").
- 스텝 출력을 `run:` 본문에 `${{ }}` 로 끼우지 않고 `env:` 로 넘긴다 — 셸 인젝션 경로를 닫았다.
- `format_report`(`test_golden_eval.py`)는 문서 id 와 사유 코드만 출력한다. producer 축이 추가한 라벨(`Producer.label()`, `baseline.py:379` 부근)은 provider·모델명·effort 뿐이라 본문·개인정보가 실리지 않는다.
- 마스킹 선행 불변식에 닿는 변경 없음.
- **확인 명령**: `grep -n "ANTHROPIC_API_KEY" .github/workflows/ci.yml` · `uv run pytest tests/golden/test_baseline_gate.py -k 리포트에_본문이 -q`

---

## 2. Critical

### C-1. 부분 실패 실행이 하한선 파일을 만든다 — 새 축도 가드 4개도 막지 않는다

**심각도**: Critical (**② 장치** — 사건을 탐지·차단할 게이트가 그 사건 앞에서 무력하다)
**축**: 도달 범위(테스트 적정성)
**마감**: **첫 정식 `tests/golden/baseline.json` 을 커밋하기 전.** 지금은 `tests/test_llm_lane_scope.py:492 test_기준선_파일은_저장소에_없다` 가 우연히 백스톱 역할을 하지만(§2.2), 그 테스트는 첫 기준선이 서는 순간 지워져야 하는 것이라 마감이 곧 그 시점이다.
**관련 종료 조건**: 계획 §4.6 게이트2·5 / §5 Phase 5 종료 게이트 / `00_progress.md` Phase 0 「품질 합격선 기제」 행의 유보 ⓐ

**무엇이 일어나는가.** 2026-08-13 실행은 합성 20건이 성공하고 실수집 36건이 HTTP 400 으로 죽었다. `convert_all`(`test_golden_eval.py:261`)이 `LLMProviderError` 를 `None` 으로 삼키고(:273), `evaluate_all` 이 그 36건을 **변환실패 = 불통과**로 센다(`evaluation.py:183`). 관측 모델은 **성공한 변환에서만** 모인다(`evaluation.py:190`). 따라서:

- 36건은 **어떤 producer 도 내지 않았는데** 그 0점이 `anthropic/claude-sonnet-5(effort=low)` 의 실적으로 기록된다.
- 지문의 producer 는 건강한 실행과 **한 글자도 다르지 않다** → `differences()` 가 빈 목록 → **비교 가능**.
- `write_baseline`(`baseline.py:680`)의 가드 4개가 전부 통과한다 — provider 있음, 관측 모델 있음(20건에서 왔다), 섞이지 않음, effort 키 있음.

**재현 1 — 지문·가드 통과**

```bash
uv run python - <<'PY'
import sys, tempfile; from pathlib import Path
sys.path.insert(0, "/Users/harris/Development/private/easy-doc")
import tests.golden.test_golden_eval as harness
from app.services.conversion import ConversionOutcome
from tests.golden.baseline import Fingerprint, baseline_body, write_baseline
from tests.golden.evaluation import evaluate_all
D = harness.DOCUMENTS
out = lambda d: ConversionOutcome(easy_text=d.source_text, masked_items=[],
                                  model="claude-sonnet-5", provider_name="anthropic")
broken  = evaluate_all({d.id: (out(d) if d.synthetic else None) for d in D}, D)
healthy = evaluate_all({d.id: out(d) for d in D}, D)
c = harness.run_context(broken.observed_models)
print("변환실패:", len(broken.conversion_failures), "건 / observed:", broken.observed_models)
print("두 지문 차이:", Fingerprint.of(broken.documents, c)
        .differences(Fingerprint.of(healthy.documents, harness.run_context(healthy.observed_models)))
      or "[] — 비교 가능")
with tempfile.TemporaryDirectory() as t:
    p = Path(t)/"b.json"
    write_baseline(baseline_body(Fingerprint.of(broken.documents, c), broken.measurement, c), p)
    print("write_baseline:", "기록됨 — 가드 4개 전부 통과")
PY
```

실측 출력:

```
변환실패: 36 건 / observed: ['claude-sonnet-5']
두 지문 차이: [] — 비교 가능
write_baseline: 기록됨 — 가드 4개 전부 통과
```

**재현 2 — 기록된 하한선이 이후 판정에 무엇을 하는가** (양쪽 다 문서에 기록된 실측치를 쓴다: 하한선 = 2026-08-13 의 19/56·합성 19/20·실수집 0/36, 비교 대상 = 2026-08-12 의 31/56·합성 16/20·실수집 15/36 — `04_goldenset-first-run.md` §11-12·§192-193)

```
2026-08-12 실측 31/56 (16/20·15/36)    -> 하락   blocking=True
실수집 전멸 재발 0/36                    -> 유지   blocking=False
실수집 1건 통과 1/36                     -> 개선   blocking=False
```

두 방향으로 동시에 고장 난다.

1. **거짓 차단** — 56건을 전부 변환해 31건을 통과시킨 정상 실행이, 36건을 모델에 보내지도 못한 실행이 세운 하한선 앞에서 `하락`으로 막힌다. 합성 집단의 수치가 부풀려져 있기 때문이다(실수집이 죽어 합성만 온전히 측정됐다).
2. **영구 개방** — 실수집 하한선이 `0/36` 이라 그 축은 다시 기록하기 전까지 **어떤 결과든 통과**한다. 이것이 저자 자신이 문서에 적은 위험("하한선이 0으로 굳어 다음 실행이 무엇을 해도 통과한다")이고, 그 위험을 **사람이 손으로 파일을 내려서** 막았다.

**왜 이것이 이번 커밋의 지적인가.** `6cd5809` 는 "가드의 거부 방향을 태우고 지문에 producer 축을 넣는다"이고, producer 축의 선언은 "**누가 낸 수치인가**"다(`baseline.py` 모듈 docstring ③). 같은 날 같은 저장소에서 그 질문이 실제로 갈린 실행이 있었는데, 그 실행을 새 축이 통과시킨다. 축의 **선언 범위**(누가 냈는가)와 **실제 도달**(성공한 변환이 보고한 모델명)이 어긋난 것이고, 어긋난 자리가 바로 사고가 난 자리다.

**신호는 이미 손에 있다.** `RuleEvaluation.conversion_failures` 에 36건이 담긴 채 기록 경로(`test_golden_eval.py:512`)를 지나간다. 기록 실행에서 변환 실패가 1건이라도 있으면 그 수치는 어느 producer 의 하한선도 아니다 — G2("관측 모델 없음")가 **전건 실패만** 잡는 것을 부분 실패까지 넓히는 것이 같은 모양의 가드다. 다만 이 판단은 리더/사용자 결정이 필요하다: 모델이 정당하게 거부한 것과 API 가 죽은 것을 `convert_all` 이 구분하지 못하므로(둘 다 `LLMProviderError` → `None`), "변환 실패 0건일 때만 기록"으로 잡을지 사유 코드를 남겨 구분할지는 설계 선택이다. **이 리뷰는 방법을 정하지 않고 장치의 부재만 지적한다.**

### 2.2 완화 요인 (같이 기록한다 — 마감이 여기서 나온다)

`tests/test_llm_lane_scope.py:492 test_기준선_파일은_저장소에_없다` 가 기본 스위트에서 돌고, 기록 실행이 파일을 만들면 이 테스트가 빨개진다. 2026-08-13 에 실제로 그랬을 것이다. 그러나 이것은 **"기준선이 아직 없다"는 상태를 지키는 테스트**이지 오염된 기준선을 가려내는 장치가 아니다 — 정식 기준선이 서면 이 테스트는 지워지고, 그 순간 백스톱이 사라진다. **확인**: `uv run pytest tests/test_llm_lane_scope.py -q`

---

## 3. Major / Minor

### M-1. producer 3필드 중 `provider`·`effort` 에 배선 대조가 없다 — 하드코딩해도 스위트가 초록이다

**축**: 도달 범위(테스트 적정성) / **마감**: 첫 정식 기준선 기록 전

배선 테스트 `test_producer_지문이_다르면_하네스가_비교_불가로_떨어진다`(`test_floor_gate_wiring.py:361`)는 기준선 쪽 조건을 `_baseline_context`(:198)로 만드는데, 그 함수가 하는 일은 `harness.run_context(observed_models)` 호출이다 — **검사 대상 자신에서 기대값을 유도한다.** 관측 모델은 리터럴(`["다른-실행의-모델"]`)로 주입되므로 축이 살아 있지만, `provider`·`effort` 는 양변이 같은 함수에서 오므로 그 함수가 무엇을 반환하든 두 값이 함께 움직여 대조가 성립하지 않는다.

**재현 (변이 시험 — 저장소 파일 미수정, 스크래치패드 플러그인으로 런타임 패치)**

```bash
export PYTHONPATH=/private/tmp/claude-503/-Users-harris-Development-private-easy-doc/6f3e0698-996a-4c4a-b701-ef39bb65da0a/scratchpad
MUTATION=provider_hardcoded  uv run pytest tests/golden -q -p mutate   # run_context 가 GOLDEN_PROVIDER 를 안 읽는다
MUTATION=effort_hardcoded    uv run pytest tests/golden -q -p mutate   # run_context 가 settings.llm_effort 를 안 읽는다
MUTATION=observed_ignored    uv run pytest tests/golden -q -p mutate   # run_context 가 인자를 무시한다
MUTATION=producer_axis_dead  uv run pytest tests/golden -q -p mutate   # Fingerprint.of 가 context 를 무시한다
```

| 변이 | 결과 | 판정 |
|---|---|---|
| `provider` 하드코딩 | **121 passed** | **잡지 못한다** |
| `effort` 하드코딩 | **121 passed** | **잡지 못한다** |
| `observed_models` 인자 무시 | 7 failed | 잡는다 |
| `Fingerprint.of` 가 context 무시 | 5 failed | 잡는다 |

(무변이 기준선: `uv run pytest tests/golden tests/test_harness_scope_reach.py -q` → **156 passed**)

`test_baseline_gate.py` 의 `test_provider가_바뀌면_비교_불가다`·`test_effort가_바뀌면_비교_불가다` 는 `_context(provider=…)` 로 조건을 **손으로 세워** `Fingerprint.differences` 만 확인한다. 판정 함수는 정상이다 — 없는 것은 "하네스가 **이번 실행의** provider·effort 를 담은 지문을 넘기는가"의 대조이며, 그 구분은 저자가 같은 커밋에서 직접 적은 것이다("함수 단위 확인과 배선 확인은 다른 것이다 — 이 파일 전체의 존재 이유가 그 구분이다", `test_floor_gate_wiring.py` §707 부근 주석).

축을 만든 계기가 "anthropic 기준선과 openai 실행이 비교 가능으로 읽혔다"인데, 그 사건을 직접 막는 필드가 `provider` 다. 실무상 벤더가 바뀌면 관측 모델도 같이 바뀌어 축이 걸리긴 하지만, 그 방어는 `provider` 필드가 아니라 `observed_models` 가 하는 것이다 — 즉 **`provider` 필드는 지금 아무것도 지키지 않으면서 지키는 것처럼 보인다.**

### M-2. `provider` 는 증거가 아니라 주장인데 차단축에 들어갔다 — 증거 필드가 이미 수집 경로에 있다

**축**: parity 위험 / **마감**: 첫 정식 기준선 기록 전

모듈 docstring 은 설정값 `model` 을 지문에서 뺀 근거를 명확히 적는다 — "별칭 해석·폴백이 있으면 실제와 갈리는 *주장*이라 증거인 관측 모델이 지문을 맡는다"(`baseline.py:314` `RunContext` docstring). 그런데 `provider` 는 `os.environ.get("GOLDEN_PROVIDER", DEFAULT_PROVIDER)`(`test_golden_eval.py:253`) — **같은 종류의 주장**이며 관측이 아니다. 그리고 증거는 이미 있다: `ConversionOutcome.provider_name`(`app/services/conversion.py:44`, `:121` 에서 `self._provider.name` 으로 채워짐)이 문서마다 실려 오는데 `evaluate_all` 은 `outcome.model` 만 모으고(`evaluation.py:190`) `provider_name` 은 버린다.

같은 문단이 세운 증거/주장 기준을 세 필드 중 한 필드에만 적용했다. **확인**: `grep -n "provider_name" app/services/conversion.py tests/golden/evaluation.py`

### M-3. `effort` 는 openai 실행에서 유령 축 — 적용되지 않는 값이 하한선을 리셋한다

**축**: parity 위험 / **마감**: 다른 벤더로 기준선을 잴 계획이 서기 전 (Phase 5 이전)

`create_provider`(`app/llm/factory.py`)는 `settings.llm_effort` 를 **Anthropic 에만** 넘긴다(문서화된 의도다). 그러나 `run_context`(`test_golden_eval.py:257`)는 벤더와 무관하게 `settings.llm_effort` 를 실어 지문에 넣는다.

```bash
uv run python -c "
from app.llm.factory import create_provider; from app.config import Settings
s = Settings(llm_provider='anthropic', llm_effort='high', openai_api_key='x')
p = create_provider('openai', s)
print(type(p).__name__, 'effort 속성:', getattr(p,'effort','<없음>'), '| 지문에 실릴 값:', s.llm_effort)"
# -> OpenAIProvider effort 속성: <없음> | 지문에 실릴 값: high
```

결과: `GOLDEN_PROVIDER=openai` 로 기준선을 잰 뒤 `.env` 의 `LLM_EFFORT` 만 고치면 — **호출도 결과도 한 글자 안 바뀌는데** 지문이 갈려 `INCOMPARABLE` 로 떨어지고 하한선이 리셋된다. 저자가 스스로 "반대 방향 고장"이라 부른 과민 지문이며, 그 방향을 잡으라고 둔 대조군(`test_기록만_하는_조건은_producer_를_흔들지_않는다`, `test_baseline_gate.py:687`)은 `judge_provider` 와 설정값 `model` 만 덮는다 — 실제로 무관해질 수 있는 필드는 덮지 않았다.

### M-4. G1 의 "하네스 경로로 도달한다" 주장이 거짓 — G4 와 같은 부류다

**축**: 도달 범위(테스트 적정성) / **마감**: 다음 리뷰 회차(문서 수정으로 닫힌다)

`test_provider_없이는_기준선을_쓰지_않는다`(`test_baseline_gate.py:543`)의 docstring 이 단정한다 — "**빈 provider는 손으로 조립한 body의 이야기가 아니다** — 하네스 경로로 도달한다. `GOLDEN_PROVIDER=`(빈 값)로 실행하면 … 그리고 그 순간이 **사람이 손으로 기준선을 기록하는 실행**이다."

실측은 반대다. `GOLDEN_PROVIDER=` 로 돌리면 `provider` fixture 가 `require_provider("")` → `create_provider("")` 에서 **먼저 죽는다**:

```bash
uv run python -c "
from app.llm.factory import create_provider; from app.config import Settings
try: print(create_provider('', Settings()))
except Exception as e: print(type(e).__name__, ':', e)"
# -> ValueError : 지원하지 않는 provider 이름:
```

모듈 스코프 fixture 가 터지므로 `write_baseline` 까지 가지 못한다. 즉 G1 은 G4(`effort` 키 부재)와 **정확히 같은 부류** — 손으로 조립한 body 에 대한 방어이고 하네스 경로로 재현되지 않는다. 그런데 저자는 G4 만 따로 떼어 "이 가드만 성격이 다르다 … 도달 0으로 묶어 고칠 대상이 아니다"(`test_baseline_gate.py:591` docstring)라고 분류하고 G1 은 반대로 적었다. 테스트 본문도 `RunContext(provider="")` 를 **손으로 세워** 검증하므로, 본문이 증명하는 것과 docstring 이 주장하는 것이 다르다.

지적의 요지는 "G1 을 없애라"가 아니다 — 가드 자체는 값이 있다. **도달 분류가 틀렸고, 틀린 방향이 하필 "도달한다"쪽**이라는 것이다. 이 저장소 규칙이 잡으려는 실패 형태 그대로다.

### M-5. 게이트 표가 **실행 0회**인 잡을 `ci:` 로 표기 — `local:` 보다 도달 정보가 줄었다

**축**: 도달 범위 / **마감**: 이 브랜치가 main 에 병합되거나 PR 이 열려 `llm-lane` 이 최초 1회 실제로 도는 시점

`c8bc9f8`·`f220682` 가 `00_progress.md` 두 행의 실행 경로를 `local:uv run pytest tests/golden -m llm` → `ci:llm-lane(조건:.github/llm-lane-paths.txt)` 로 바꿨다. 어휘 정의는 `ci:<잡>(조건:…)` = "그 잡에 **배선돼 있으나** 조건이 맞을 때만 돈다"이고, `local:` = "CI 도달 0이며, **그 사실이 표에 드러나는 것이 이 표기의 목적이다**"(`kotlin-migration` SKILL.md).

실측:

```bash
sed -n '3,6p' .github/workflows/ci.yml        # on: push: branches: [main]
gh run list --branch feat/kotlin-migration-harness --limit 10   # 출력 0건
gh run list --limit 3                          # 최근 실행은 전부 main, 최신 2026-08-09
git ls-remote --heads origin                   # 원격 브랜치는 64f1757 — 대상 4커밋 미푸시
```

- 워크플로 트리거는 `push: branches: [main]` + `pull_request` + `workflow_dispatch`. 이 브랜치는 main 이 아니고 **PR 이 열려 있지 않다.**
- `llm-lane` 잡은 `64f1757`(2026-08-13)에 신설됐고, **한 번도 실행된 적이 없다.** CI 최근 실행은 2026-08-09 main 이다.

즉 이 행의 실제 도달은 **0**이다. 표기를 바꾸기 전(`local:…`)에는 "CI 도달 0"이 표에 드러났고 명령은 실제로 돌 수 있었다. 바꾼 뒤에는 도달이 여전히 0인데 표에는 CI 배선으로 읽힌다 — **도달 정보가 늘어난 것이 아니라 줄었다.** 커밋 메시지("실행 경로를 사실에 맞춘다")가 뜻한 방향과 반대다.

공정하게 적자면: `ci.yml` 안의 배선은 실재하고(잡·조건 계산·`--self-check` 스텝·`ANTHROPIC_API_KEY` 시크릿이 2026-08-12자로 등록돼 있음 — `gh secret list`), main 병합 후에는 도달이 0을 벗어난다. 어휘에 "배선했으나 아직 한 번도 돌지 않았다"를 적을 자리가 없다는 것이 구조적 원인이다. 판정은 리더에게 넘긴다 — ⓐ 최초 1회 실행 전까지 `local:` 유지, ⓑ 어휘에 미실행 표기 추가, ⓒ 지금 PR 을 열어 실제로 돌린 뒤 표기 유지 중 하나다.

**부수 관측(차단 아님)**: `llm-lane` 이 처음 도는 날, `baseline.json` 부재 때문에 하한선 판정은 `ABSENT`(blocking) 로 떨어진다. 설계된 정상 상태라고 문서에 적혀 있으나, 그 결과 이 잡의 첫 초록은 크레딧 충전 + 기준선 기록 + 커밋을 모두 마친 뒤에야 가능하다.

### M-6. `docs/golden/` 13건이 P1 반출 목록에 없는 채 `git status` 에서 사라졌다

**축**: 보안 불변식(데이터 영구 손실) / **마감**: Phase 8 착수 전, 그러나 실질 위험은 지금부터다

`c8bc9f8` 이 `docs/golden/` 을 `.gitignore` 에 넣었다(`.gitignore:70`). 실측: **13파일 / 76MB / 한 번도 추적된 적 없음**(`git log --all -- docs/golden/` 출력 0건).

`03_rebuild-extraction-list.md` 는 같은 커밋에서 위험을 **산문으로** 정확히 적었다 — "**fresh clone 에는 없다.** 이 기계의 작업 사본에서만 생존한다". 그런데 같은 문서의 **P1 반출 표(P1-1 ~ P1-7)에는 행이 없다.** P1 은 이 프로젝트의 유일한 '영구 손실' 차단축이고(CLAUDE.md 「하지 말 것」), `docs/golden/` 은 골든셋 56건의 **출처 원본**이다. 저자 자신이 P1-7 주에서 같은 형태의 위험("삭제 구역보다 **불리한 위치** — Phase 8을 기다리지 않고 tmp 정리로 사라진다")을 인식하고 목록에 넣었는데, 이번 건은 인식만 하고 넣지 않았다.

무시 등록으로 위험이 실제로 **커졌다**: 이제 `git status` 가 이 13건을 보여주지 않으므로 부재를 알아챌 신호가 없고, `git clean -fdx` 는 조용히 지운다.

**확인**: `grep -n "P1-" docs/migration/_workspace/03_rebuild-extraction-list.md | head -20` (P1-1~P1-7 에 `docs/golden/` 행 없음)

### m-1. 디렉터리 전체 무시가 근거보다 넓고, 두 번째 근거는 무시 대상에 닿지 않는다

**축**: 보안 불변식 / **마감**: 없음(권고)

`.gitignore:65` 가 규칙을 스스로 적는다 — "무시 규칙은 **탐지가 아니라 은폐로 작동**하므로 근거가 실제로 닿은 자리만 막는다". 그 규칙을 `*.pdf`·`*.hwpx` **전역**을 거부하는 데는 적용했는데(옳다), **디렉터리 전체 무시**에는 적용하지 않았다. 근거는 파일 13개이고 범위는 디렉터리다 — 앞으로 이 디렉터리에 무엇이 생겨도 보이지 않는다. 바로 위 `tests/golden/041-…jso` 는 파일 하나를 못박은 선례다.

두 번째 근거는 대상에 닿지 않는다. 주석은 "**24KB 미만 파일은 본문을 그대로 외부 호출에 싣는다**(실측 — 미추적 74건)"를 무시의 실질적 이유로 든다. 그런데 무시 대상 13건은 **전부 24KB 이상**이다:

```bash
find docs/golden -type f -size -24k    # 출력 0건
ls -laS docs/golden | tail -4          # 최소 파일 73,724 B
```

즉 이 13건에 실제로 적용되는 효과는 "리뷰 대상 목록이 파일명으로 희석된다"뿐이고, "보낼 의도가 없던 **자료**가 나간다"는 성립하지 않는다. 반대로 그 근거가 정확히 적용되는 파일들(`docs/golden-drafts/*.json`, 24KB 미만, 미추적)은 **일부러 무시하지 않았다** — 그 판단 자체는 옳고 근거도 정확하다(§4 동의 항목). 근거와 범위가 서로 다른 파일 집합을 가리킨다.

### m-2. 조건부 CI 표기는 과장을 표현 가능하게 할 뿐 강제하지 않는다

**축**: 도달 범위 / **마감**: 없음(권고)

저자가 한계를 정확히 적었다(`tests/test_harness_scope_reach.py:65`) — "`ci:llm-lane` 단순형은 그 잡이 실제로 조건부여도 그대로 통과한다 … 형식은 그 과장을 **표현 가능하게** 할 뿐 강제하지 못한다". 이 자기 진단에 동의한다. 다만 "한계를 적었다"와 "값이 있다"는 별개이므로 값을 따로 셈한다.

- **있는 값**: 괄호 안 경로가 `1회성:` 과 동일한 git 추적 기준을 받아(`_untracked_problem`), `조건:나중에` 같은 산문으로 행을 닫을 수 없다. 두 형식이 잡 실재 검사를 **한 함수**(`_unknown_job_problem`)에서 공유해 한쪽만 느슨해지는 드리프트를 구조로 막았다. 둘 다 실질적이다.
- **없는 값**: 검사기는 이미 `ci.yml` 을 파싱해 `jobs:` 를 읽고 있다. 같은 자리에서 그 잡에 `if:` 스텝이나 조건 계산이 있는지 보고 **단순형을 거부**할 여지가 있는데 하지 않았다. 이 형식을 만든 계기가 정확히 그 과장이므로, 계기가 된 방향으로는 아무것도 막지 못한다.

**확인**: `uv run pytest tests/test_harness_scope_reach.py -q` (전건 통과 — 단순형 `ci:llm-lane` 을 표에 써도 통과한다)

---

## 4. 동의하는 부분 (반증을 찾으려 했고 찾지 못했다)

교차 대조 때 신호가 잡히도록, **확인했고 문제없던 것**을 명시한다.

1. **관측 모델 축은 실제로 배선돼 있다.** `run_context` 가 인자를 무시하게 만들면 7건, `Fingerprint.of` 가 context 를 무시하게 만들면 5건이 실패한다(§M-1 표). `test_producer_지문이_다르면…`(`:361`)의 마지막 단언(`"관측 모델: 다른-실행의-모델 → this-run" in str(caught.value)`)이 하네스가 **이번 실행의** 관측 모델을 넘긴다는 것을 실제로 붙잡는다 — 하네스가 고정값을 넘기면 이 줄이 깨진다. 확인함.
2. **G2·G3 의 하네스 경로 도달은 진짜다.** `test_관측_모델이_없으면_하네스_기록_경로가_막힌다`(`:739`)·`test_모델이_섞이면_하네스_기록_경로가_막힌다`(`:769`)는 `evaluate_all` 을 실제로 태우고 `attempts == 1 / written == []` 로 "본문까지 갔고 마지막에 가드가 막았다"를 구분한다. G2 를 런타임에서 걷어내는 변이(`MUTATION=guard_observed_removed`)를 걸면 **2 failed** — 음성 대조가 실제로 붙어 있다.
3. **깨진 조건부 표기의 오독 우회를 찾지 못했다.** `_CI_TOKEN` 의 문자 클래스에 `(` 가 없고 양끝이 앵커라 `ci:unit(` 류가 단순형으로 읽힐 경로가 없다. `test_음성3d` 가 9종을 실재 잡 이름으로 태워 "오독되면 침묵으로 통과"가 실패로 드러나게 했다 — 대조 설계가 정확하다. 추가 우회(중첩 괄호·공백·`조건=`)를 시도했으나 전부 어휘 밖으로 떨어진다.
4. **9%p 종결 근거가 전부 실재한다.** `git log --since=2026-08-08 -- app/easyread/{style_rules,goldenset}.py` 가 문서가 적은 **5커밋을 정확히** 낸다(`85ca2f5`·`eae75c7`·`0894854`·`a4c9fd9` = 2026-08-09, `c43cae5` = 2026-08-12). `claude-sonnet-5` 가 `AnthropicProvider.__init__` 기본값으로 실재한다. "비교 불가였다"는 결론에 동의한다 — 자와 만드는 쪽이 둘 다 바뀐 두 수치다.
5. **"앞 번호 성공 / 뒷 번호 전건 실패" 정황이 코드와 맞는다.** `load_documents`(`app/easyread/goldenset.py:143`)가 id 오름차순으로 정렬하고 `convert_all` 이 그 순서로 돈다 — 합성 001~020 이 먼저다. 크레딧 소진 시점 가설과 일관된다. (HTTP 400 자체는 재실행 금지라 **미검증** — §5)
6. **`docs/golden-drafts/` 를 무시하지 않은 판단이 옳고 근거도 정확하다.** `app/easyread/collection.py:210` 이 "`docs/golden-drafts/`는 저장소 안이고 `.gitignore` 대상이 아니라", `:970` 이 "초안이 `docs/golden-drafts/`에 커밋되기 때문에 거는 것"이라고 실제로 적혀 있다. 안전장치의 근거를 지우지 않은 판단에 동의한다.
7. **judge 를 기준선에서 뺀 것, producer 를 해시하지 않고 값으로 담은 것.** 전자는 "고정할 수 없는 값을 하한선에 두지 않는다"로 일관되고, 후자는 drift 메시지가 `provider: anthropic → openai` 처럼 **이름으로** 말하게 한다. 이 저장소가 이미 적어 둔 "해시는 아무것도 말해 주지 않는다" 교훈과 맞다.
8. **대조군을 넣은 것.** `test_producer가_같으면_정상_비교된다` 가 `HELD` 만이 아니라 `REGRESSED` 까지 확인해 "축을 넣어 전부 막았다"와 구분한다. 과민 방향을 스스로 의심한 설계다(그 의심이 `effort` 에는 못 닿은 것이 M-3).
9. **`observed_models` 목록 비교의 순서 민감성 — 확인했고 문제없다.** `evaluation.py:206` 이 `sorted(observed)` 로 만들어 결정적이다. 지적하지 않는다.
10. **`llm-lane` 잡의 내부 설계.** 조건 계산 불가 시 **건너뛰지 않고 돌린다**("알 수 없음은 무관이 아니다"), `--self-check` 는 조건 없이 매번 돈다, 경로 목록과 판정기가 한 모듈을 공유해 복사본 드리프트를 막았다, 건너뛴 초록에 경고를 남긴다, `GOLDEN_RECORD_BASELINE` 을 CI 에서 세우지 않는다. 설계 자체는 이 저장소 규칙에 충실하다 — M-5 는 설계가 아니라 **아직 한 번도 돌지 않았다**는 사실에 대한 지적이다.

---

## 5. 미실행·확인 불가

| 항목 | 사유 |
|---|---|
| `uv run pytest -m llm` 실제 실행 | **금지 지시** (Anthropic 크레딧 소진 HTTP 400). producer 축이 실제 API 응답에서 관측 모델을 제대로 뽑는지는 `LLMResponse.model` 경로 정적 확인까지만 했다 |
| "실수집 0/36 은 API 오류였다"의 **직접** 검증 | 위와 같은 이유. 문서의 근거는 ⓐ 사후 최소 호출 탐침의 HTTP 400 ⓑ 순서 정황 ⓒ 실행 시간 절반이며 ⓐ 는 그 실행 자체의 산출물이 아니다. **정황은 코드와 일관**하나(§4-5), 하네스가 실패 사유를 남기지 않으므로(`convert_all` 이 `LLMProviderError` 를 `None` 으로만 삼킨다) 그 실행에서 재구성할 방법이 없다 — 이 사실 자체가 C-1 의 근거이기도 하다 |
| `llm-lane` 잡의 실제 동작 | 실행 0회. 조건 계산 셸·`--match` 판정기 배선은 정적 확인만 |
| 2026-08-08 실행의 집단별 분해(합성/실수집) | 문서에 전체 36/56 만 있고 분해가 기록돼 있지 않다. C-1 재현 2에서는 **분해가 기록된** 2026-08-12 실측(31/56 · 16/20 · 15/36)을 비교 대상으로 썼다 |
| `docs/golden/` 무시가 codex 리뷰 도구 입력에 실제로 준 효과 | 도구의 `--scope working-tree` 동작을 실행해 보지 않았다. "미추적 74건"·"24KB 미만 인라인"은 저자의 실측 주장이고, 내가 확인한 것은 **무시 대상 13건이 그 임계 밖**이라는 사실뿐이다 |
| 범용 품질 축(성능·유지보수성 등) | 이 에이전트의 범위 밖. 필요하면 `multi-review` 를 별도로 돌릴 것을 리더에게 권고한다 |

---

## 6. Phase 종료 조건 대비 현황

**이 산출물만으로 종료 조건 충족을 보고하지 않는다.** 정본은 2차 교차 종합(`06_baseline-guard_cross.md`)이다.

| 종료 조건 | 이번 회차 판정 |
|---|---|
| §4.6 게이트2·5 「품질 합격선 기제」 (`00_progress.md` Phase 0 행, 충족=예) | **재검토 필요.** 이 행이 "예"로 닫힌 근거는 기제의 구현·검증이고 그 대부분은 유효하다. 그러나 C-1 은 그 기제가 **첫 실사용에서 잘못된 하한선을 만든다**는 것이고, 유보 ⓐ(`baseline.json` 미기록)가 열려 있는 동안은 드러나지 않는다. 행을 되돌릴지, 유보에 C-1 을 추가할지는 리더 판정 |
| 유보 ⓐ `baseline.json` 미기록 | **여전히 열림.** 2026-08-13 재기록 시도가 크레딧 소진으로 실패했고 파일은 내려졌다. 저장소 부재 확인: `uv run pytest tests/test_llm_lane_scope.py -k 기준선_파일 -q` |
| 유보 ⓒ 날조(fidelity=1) 결정적 차단 없음 | **여전히 열림** — 이번 변경 범위 밖 |
| 유보 ⓓ 독립 리뷰 미실시 | **진행 중.** 이 문서가 1차 Claude 리뷰. 2차 교차 종합이 남았다 |
| §6 검증 매트릭스 Quality 행 실행 경로 | **M-5 로 이의.** 표기는 배선을 주장하나 실행 0회 |
| P1(데이터 반출) | **M-6 로 이의.** `docs/golden/` 13건이 목록에 없다 |

---

## 7. 다음 회차로 넘기는 것

- **리더 판정 요청 1**: C-1 의 처방 방향 — "기록 실행은 변환 실패 0건일 때만"인가, `convert_all` 이 실패 사유를 남겨 API 오류와 모델 거부를 구분하게 하는 것인가. 이 리뷰는 방법을 정하지 않았다.
- **리더 판정 요청 2**: M-5 의 세 선택지(ⓐ `local:` 복귀 ⓑ 어휘에 미실행 표기 추가 ⓒ PR 을 열어 실제로 1회 돌림).
- **2차 교차 종합 재호출이 필요하다.** 입력: `06_baseline-guard_codex-reviewer.md` + 이 파일. 산출: `06_baseline-guard_cross.md`.
