# `llm-lane` CI 완주 변경 · 1차 codex 독립 리뷰 (1회차)

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 리더 / `migration-reviewer` 의 몫이다.

**파일명 어간**: `2026-08-21_llm-lane-ci-timeout` — **리더가 1단계 호출에서 고정 지정한 값**을 그대로 썼다.
이 작업은 Kotlin Phase 의 `{scope}` 가 아니라 존치 중인 Python 골든 레인의 CI 문제라
`kotlin-migration` 스킬의 `{scope}` 정본 표에 대응 항목이 없다(임의 슬러그 생성 없음).

**회차**: 1회차. `docs/migration/_workspace/reviews/` 에 같은 어간의 이전 파일 없음 — 재호출이 아니라 신규다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-21 13:44:26 KST |
| 종료 시각 | 2026-08-21 13:55:16 KST |
| 소요 | **10분 50초** |
| 대상 범위 | `81ba9fa..HEAD` — 커밋 2건 (`8dd0550` · `6285118`), 변경 파일 **3개** |
| 변경 파일 | `.github/workflows/ci.yml` · `tests/golden/test_golden_eval.py` · `docs/plans/2026-08-21-llm-lane-ci-timeout.md` |
| 모드 | `adversarial` (focus text 필수 — 게이트가 판정을 못 하게 되는 자리를 찾는 축) |
| scope / base | `auto`(미지정) / **`--base 81ba9fa`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6**) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| codex CLI | `codex-cli 0.147.0` |
| codex thread ID | `01a022a2-9237-7433-85bf-6182beaf72b5` |
| **codex 판정** | **`needs-attention`** — "NO-SHIP." |
| codex 실행 셸 명령 | **33건 시작 / 30건 완료 / 3건 실패**(실패 3건은 codex 자신의 명령 문법·읽기전용 샌드박스 문제로 기록됨) |
| focus text 크기 | 9,135 바이트 |
| codex 출력 크기 | 3,820 바이트 |
| 지적 건수 | **2건 — high(Major) 1 · low(Minor) 1** |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 81ba9fa
codex-review: 대상 판정 = non-empty (merge-base=81ba9face155, 변경 파일 3개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 **사전 거부 단계에서** 확인됐다.

### 1.2 실행 명령 전문

```bash
SP=<스크래치패드>/llmlane
FOCUS="$(cat "$SP/focus.txt")"

# 1) --dry-run 으로 헬퍼·대상·명령 확인 (대상 판정 non-empty, 종료 코드 6)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 81ba9fa --focus "$FOCUS" --dry-run

# 2) 실제 실행 (종료 코드 0)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 81ba9fa --focus "$FOCUS"

# 스크립트가 헬퍼로 내보낸 실제 명령:
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
     adversarial-review --base 81ba9fa '<focus text 전문 — §2>'
```

### 1.3 제공한 맥락 목록

- 배경: Python/FastAPI → Kotlin/Spring Boot 전환 중이나 이번 변경은 **존치 중인 Python 골든 레인의 CI 문제**
- 채점 기준 7항: 로그 본문·개인정보 금지(CLAUDE.md 보안 규칙) / CI 가 자기 합격선을 쓰지 않음 / '도달 0인데 초록' 금지(CLAUDE.md 「범위 대조」) / 분모 축소 금지 / 필수 정보 보존 허용치 0 / 문서당 LLM 최대 2회 / 주석·커밋 메시지·계획서의 사실 주장이 실물과 일치
- 대상: 위 3개 파일 + 변경 요약(**작성자 주장이지 검증된 사실이 아니라고 명시**)
- 함께 읽을 파일: `tests/golden/baseline.json` · `app/config.py` · `app/llm/provider.py` · `app/llm/anthropic_provider.py` · `tests/golden/evaluation.py` · `tests/test_llm_lane_scope.py` · `pyproject.toml`
- 질문 5축: A 지문 고정이 게이트 의미를 훼손하는가 / B Actions 시맨틱이 전제대로 도는가 / C `--log-cli-level=INFO` 가 새로 흘리는 것 / D 사실 주장 대조 / E 무엇을 감추는가
- 실행 제약: **`pytest -m llm` 절대 금지**(35분·실과금), 코드 수정 금지, "테스트가 실패할 것 같다"는 지적이 아님

**민감 데이터 미포함 확인**: focus text 에 사용자 문서 본문·실제 암호문·키·개인정보를 싣지 않았다. 골든 코퍼스 본문도 인용하지 않았고 파일 경로와 계약 조항만 제시했다.

---

## 2. 전달한 프롬프트 전문 (focus text)

````text
## 배경

이 저장소는 공공기관용 '쉬운 글' 변환 SaaS다. Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 전환 중이지만, 이번 변경은 **존치 중인 Python 골든 품질 레인의 CI 문제**다. `llm-lane` 은 실제 Anthropic API 를 호출해 56건 골든 문서를 변환·채점하고 품질 회귀를 판정하는 유일한 게이트인데, 30분 잡 타임아웃에 걸려 **CI 에서 한 번도 완주한 적이 없다.** 이 커밋 2건은 그 레인이 완주해 판정을 내놓게 만들려는 것이다.

## 지켜야 하는 조건 (채점 기준 — 위반하면 결함이다)

이 저장소의 `CLAUDE.md` 와 하네스 규약이 정한 조건이다. 코드가 좋은가가 아니라 **아래를 만족하는가**로 채점하라.

1. **로그에 문서 본문·개인정보를 절대 남기지 않는다.** 로깅은 문서 ID·길이·처리 상태까지만. 비밀키·API 키는 값도 길이도 찍지 않는다. 이 레인은 실제 사용자 문서 성격의 골든 코퍼스를 다루므로 CI 공개 로그로 새는 경로가 하나라도 있으면 Critical 이다.

2. **CI 가 자기 합격선을 다시 쓰지 않는다.** 품질 기준선(`tests/golden/baseline.json`)을 갱신하는 경로는 사람이 로컬에서 돌려 diff 를 커밋하는 것 하나뿐이다. `GOLDEN_RECORD_BASELINE` 을 CI 가 세우면 회귀가 영원히 안 잡힌다.

3. **'도달 0인데 초록' 을 만들지 않는다.** 게이트가 실제로 비교·판정을 수행하지 않은 채 통과하는 상태는 이 저장소가 반복해서 결함으로 지목해 온 형태다. "회귀가 없다"와 "회귀를 보지 않았다"는 다르다. 선언한 범위와 실제 도달 범위가 같은지가 매 변경의 필수 점검 축이다.

4. **표본(분모)을 줄여 통과율을 올리지 않는다.** 문서 수를 줄이는 스위치는 의도적으로 없고, `measured == len(DOCUMENTS)` 단언이 그 우회를 막는다.

5. **필수 정보 보존 축의 허용치는 0이다** (`REQUIRED_FACT_LOSS_LIMIT = 0`). 완화 대상이 아니다.

6. **문서 한 건당 LLM 호출 상한은 변환 1회 + 조건부 보정 1회 = 최대 2회다.** 재시도 계층은 한 곳만 갖는다(SDK 레벨 `max_retries`).

7. **주석·커밋 메시지·계획 문서의 사실 주장은 실물과 일치해야 한다.** 인용한 파일 경로·줄번호·수치가 틀리면 그 자체가 결함이다(읽는 사람이 잘못 안다). 이 저장소는 낡은 주석이 사실과 갈린 것을 과거에 결함으로 판정한 이력이 있다.

## 대상

**리뷰 범위: `81ba9fa..HEAD` — 커밋 2건 (`8dd0550`, `6285118`), 변경 파일 3개.**

- `.github/workflows/ci.yml` — `llm-lane` 잡 (파일 내 876행 부근부터. 잡 `timeout-minutes`, 실행 스텝, 요약 스텝)
- `tests/golden/test_golden_eval.py` — `log_progress` 헬퍼 신설, `convert_all` 및 judge 루프에 진행 로그 삽입
- `docs/plans/2026-08-21-llm-lane-ci-timeout.md` — 신규 계획 문서 (§8 이 §6·§7 을 일부 대체한다고 스스로 밝힌다)

변경 내용 요약 (작성자 주장이지 검증된 사실이 아니다 — 실물로 확인하라):

- 잡 `timeout-minutes` 30 → 70, `-m llm 레인 실행` 스텝에 `timeout-minutes: 55` 신설
- `실행 결과 요약` 스텝 `if` 를 `steps.scope.outputs.run == 'true'` → `${{ !cancelled() && steps.scope.outputs.run == 'true' }}`
- 실행 스텝 env 에 `LLM_MODEL: claude-sonnet-5` · `LLM_EFFORT: low` 추가
- 명령 `uv run pytest -m llm` → `uv run pytest -m llm --log-cli-level=INFO`
- `ci.yml` 주석 1건 정정 (`baseline.json` 이 "저장소에 없다"고 적혀 있던 자리)

함께 읽어야 할 파일: `tests/golden/baseline.json`(기준선 지문의 producer 축), `app/config.py`(`llm_model`·`llm_effort` 기본값), `app/llm/provider.py` 및 `app/llm/anthropic_provider.py`(타임아웃·재시도), `tests/golden/evaluation.py` 와 `tests/golden/` 이하 지문 계산 코드, `tests/test_llm_lane_scope.py`(레인 경로 목록 완전성 검사), `pyproject.toml`(`[tool.pytest.ini_options]` 의 addopts·로깅 설정).

## 질문 (찾아야 할 것)

아래 다섯 축으로 위반·누락·놓친 경계 조건을 찾아라. 지적마다 **심각도(Critical/Major/Minor)** 와 **`파일:줄` 근거**를 달아라. 지적이 없는 축은 없다고 명시하라 — 없는 결함을 만들지 마라.

**A. `LLM_MODEL`·`LLM_EFFORT` 를 워크플로에 박은 것이 게이트의 의미를 훼손하는가.**
이것이 위 조건 2(CI 가 자기 합격선을 쓰지 않는다)와 충돌하는지, 아니면 다른 종류의 행위인지 판단하라. 지문(fingerprint)이 어떻게 계산되고 무엇과 비교되는지 코드를 따라가라. `observed_models` 는 실행 시 API 응답에서 수집되는 값으로 보이는데, 모델 별칭(`claude-sonnet-5`)이 구체 버전 문자열로 되돌아오면 지문이 다시 갈리는지 확인하라. 지문이 갈릴 때 게이트가 **실패하는지 조용해지는지**가 핵심이다 — 조용해지면 조건 3 위반이다. `LLM_PROVIDER` 등 지문에 들어가는 다른 축이 여전히 미설정으로 남아 있지 않은지도 보라.

**B. GitHub Actions 시맨틱이 작성자의 전제대로 도는가.**
- 스텝 `timeout-minutes: 55` 로 스텝이 끊길 때 잡 conclusion 이 `failure` 인가 `cancelled` 인가.
- `if: ${{ !cancelled() && ... }}` 가 (ㄱ) 앞 스텝이 스텝 타임아웃으로 실패했을 때 참인가, (ㄴ) 잡 타임아웃·`concurrency` 선점으로 잡이 취소됐을 때 거짓인가.
- "명시 `if` 를 쓰면 암묵 `success()` 가 적용되지 않는다"는 전제가 맞는가. `!cancelled()` 만으로 실패 시에도 도는 것이 보장되는가.
- 잡 70 / 스텝 55 의 간격 15분이 checkout(`fetch-depth: 0`)·`uv sync --locked`·완전성 검사·범위 판정·요약 스텝을 흡수하기에 충분한가. 잡 상한이 먼저 걸리는 시나리오가 남아 있는가.
- 이 잡이 `concurrency.cancel-in-progress: true` 를 쓰는 것과 새 상한이 상호작용해 생기는 문제가 있는가.

**C. `--log-cli-level=INFO` 가 CI 공개 로그에 새로 흘리는 것이 무엇인가.**
이 플래그는 우리 로거뿐 아니라 **모든 로거**의 INFO 를 켠다. `httpx`·`httpcore`·`anthropic` SDK·`urllib3` 등 서드파티 로거가 무엇을 INFO 로 찍는지, 그 경로에 문서 본문·프롬프트·요청 바디·헤더·API 키·URL 쿼리스트링이 실릴 수 있는지 **데이터 흐름을 직접 따라가서** 판단하라(조건 1). `pyproject.toml` 의 로깅 관련 설정이 이를 좁히는지 넓히는지도 보라. `log_progress` 가 찍는 값 자체(`document.id`, 순번, 경과 초, outcome 코드)에 본문이 섞일 경로가 있는지, 예외 메시지가 로그로 나가는 자리가 있는지 확인하라.

**D. 사실 주장이 실물과 맞는가.**
`ci.yml` 주석·두 커밋 메시지·`docs/plans/2026-08-21-llm-lane-ci-timeout.md` 가 인용한 **파일 경로·줄번호·수치·단언**을 표본으로 실물과 대조하라. 특히: 계획서 §1 표의 F1~F10 인용, `04_goldenset-first-run.md` 의 "35분 20초", `app/llm/provider.py` 의 타임아웃·재시도 수치(주석은 "2회 × 60초"라 적는다), `tests/golden/test_golden_eval.py` 의 줄번호 인용들, `docs/plans/2026-08-08-sprint-4.md:65` 인용. 계획서가 §6·§7 을 남긴 채 §8 로 덮어쓰는 구성이 **어느 것이 현행인지 혼동시키는지**도 판단하라(§6 표는 여전히 `60` 과 `-s` 를 현행처럼 적고 있다).

**E. 이 변경이 무엇을 감추는가 / 되돌아가야 할 자리가 있는가.**
상한을 올린 것이 **문제를 고친 게 아니라 미룬 것**인지 — 35분짜리가 러너 편차·429 재시도로 55분을 넘기면 무엇이 남는가. `log_progress` 가 루프 안에 들어가면서 예외 처리 흐름이 바뀐 자리가 있는지 확인하라(특히 judge 루프: `LLMProviderError` 를 잡은 뒤 `outcome_code` 가 어떻게 되는지, `continue` 하는 팔과 하지 않는 팔의 로그가 일관된지, 변수가 이전 반복의 값을 물려받는 경로가 있는지). 진행 로그가 실패 판정·통과율 계산에 영향을 주는 경로가 생겼는지, 새 상한이 조건 5(허용치 0)나 조건 4(분모)를 우회하게 만드는지 보라.

## 실행 제약 (반드시 지켜라)

- **`pytest -m llm` 을 절대 실행하지 마라.** 실제 API 를 호출해 약 35분이 걸리고 실과금된다. 정적 분석과 비-llm 테스트만 써라(`uv run pytest` 는 기본 addopts 가 `-m 'not llm'` 이라 안전하다).
- 코드를 수정하지 마라. 리뷰만 하라.
- 이 변경의 목표는 레인을 **완주**시키는 것이지 **초록으로 만드는 것이 아니다.** 작성자는 첫 완주가 빨간색으로 끝날 것을 예상된 결과라고 명시했다. "테스트가 실패할 것 같다"는 그 자체로는 지적이 아니다 — 게이트가 **판정을 못 하게 되는** 경로가 지적이다.
````

---

## 3. codex 원문 출력 (무편집)

> 아래 §3 구획은 codex 가 낸 출력 그대로다. Claude 가 쓴 문장은 한 줄도 섞이지 않았다.

````text
# Codex Adversarial Review

Target: branch diff against 81ba9fa
Verdict: needs-attention

NO-SHIP. B/E에는 판정 전에 다시 끊길 수 있는 시간 예산 결함이 있고, D에는 실물과 다른 근거가 남아 있다. A는 결함 없음: 모델·effort 고정은 기준선 기록이 아니며, API가 다른 모델 문자열을 반환하면 `비교 불가/blocking=true`로 실패한다. C도 결함 없음: 진행 로그와 현재 httpx/Anthropic INFO 경로에서 본문·헤더·키 노출을 찾지 못했다. B의 Actions 시맨틱 자체는 [GitHub 표현식 규약](https://docs.github.com/en/actions/reference/workflows-and-actions/expressions)과 runner 구현대로 동작한다. E의 루프 변수·분모·필수 정보 허용치·호출 상한 우회도 발견하지 못했다. `-m llm`은 실행하지 않았고, 범위 완전성 셀프체크는 통과했다.

Findings:
- [high] [Major] 70/55분 상한은 실제 재시도와 선행 스텝을 보장하지 않아 판정 전에 다시 종료될 수 있다 (.github/workflows/ci.yml:891-903)
  잡 시계는 checkout부터 시작하지만 70분과 LLM 스텝 55분 사이에는 15분밖에 없고, `fetch-depth: 0` checkout·setup-uv·`uv sync --locked`에는 개별 상한이 없다. 선행 작업과 요약이 15분을 넘기면 잡 상한이 먼저 발동해 job이 cancelled가 되고 `!cancelled()` 요약도 사라진다. 더구나 899-900행의 `2회 × 60초` 계산은 실제 레인과 다르다. Anthropic 구현은 120초 타임아웃을 쓰며 `max_retries=2`는 최초 요청을 포함해 최대 3회, 즉 논리 호출당 최대 6분이다. 변환은 문서당 최대 두 논리 호출이고 judge도 추가 호출한다. 35분 20초 실측의 19분 40초 여유는 네 번의 timeout-limited 호출만으로 소진될 수 있으며, 저장소에는 이미 31건의 rate-limit/overload 실패 기록도 있다. 결과는 거짓 초록은 아니지만 이 변경의 목표인 품질 비교·판정을 다시 내놓지 못한다.
  Recommendation: checkout/setup/sync/summary에 명시적 상한을 두고, 잡 상한을 그 합계와 55분보다 충분히 크게 산정하라. LLM 스텝 상한은 `120초 × (1+max_retries)`와 최대 논리 호출 수를 기준으로 다시 정하거나 SDK timeout/retry 예산 자체를 축소하라.
- [low] [Minor] 계획서의 ‘확정된 사실’ 인용과 시간 수치가 현재 실물과 맞지 않는다 (docs/plans/2026-08-21-llm-lane-ci-timeout.md:20-29)
  F5의 `test_golden_eval.py:283`은 현재 `log_progress` docstring이고 변환 루프는 347행, F6의 373행도 judge 루프가 아니라 필수 정보 테스트 docstring이며 실제 루프는 441행이다. F7의 ‘시간이 전부 outcomes fixture 안’이라는 단언도 judge API 호출이 테스트 본문에서 수행되므로 거짓이다. F8은 공용 provider 상수 60초를 인용하지만 실제 Anthropic 레인은 `anthropic_provider.py:31`의 120초를 사용한다. 같은 문서의 분모 단언과 필수 정보 한도 인용도 각각 현재 517행과 114행으로 이동했다. §8이 §6·§7의 후속임은 명시되어 있어 역사 보존 자체는 결함으로 보지 않았지만, §1의 현재 사실 표는 조건 7을 위반한다.
  Recommendation: 모든 인용을 현재 줄로 갱신하거나 `81ba9fa:path:line`처럼 대상 SHA에 결속하라. F7과 F8의 설명 및 workflow의 `2회 × 60초` 주석도 실제 호출 구조와 120초/최대 3시도 기준으로 고쳐라.

Next steps:
- 시간 예산과 사실 근거를 수정한 뒤, 쓰기 가능한 환경에서 기본 비-LLM 테스트와 범위 셀프체크를 다시 실행한다.
- 실제 API 레인은 로컬에서 돌리지 말고 수정된 CI에서 최초 완주 시간과 timeout/retry 진행 로그를 확인한다.
````

---

## 4. 정리(가공) — 지적 항목 목록

> 이 구획은 Claude 가 만든 **색인**이다. 옳고 그름·심각도 재부여·오탐 판정은 하지 않았다.
> 심각도 표기는 codex 가 붙인 것을 그대로 옮겼다(`[codex 등급] [codex 가 부여한 Major/Minor]`).

| # | codex 등급 | 축 | 요지 | codex 가 댄 근거 위치 |
|---|---|---|---|---|
| C-1 | `[high]` `[Major]` | B·E | 70/55분 상한이 실제 재시도와 선행 스텝을 보장하지 않아 판정 전에 다시 종료될 수 있다 | `.github/workflows/ci.yml:891-903` (재시도 계산은 `899-900` 행 주석) |
| C-2 | `[low]` `[Minor]` | D | 계획서 §1 '확정된 사실' 표의 인용 줄번호·시간 수치가 현재 실물과 맞지 않는다 | `docs/plans/2026-08-21-llm-lane-ci-timeout.md:20-29` |

### C-1 이 든 세부 근거 (codex 원문에서 발췌)

- 잡 시계는 checkout 부터 시작하는데 70분과 LLM 스텝 55분 사이 여유는 15분뿐이고, `fetch-depth: 0` checkout·setup-uv·`uv sync --locked` 에 개별 상한이 없다
- 선행 작업과 요약이 15분을 넘기면 **잡 상한이 먼저 발동**해 job 이 cancelled 가 되고 `!cancelled()` 요약도 사라진다
- `899-900` 행의 `2회 × 60초` 계산이 실제 레인과 다르다 — Anthropic 구현은 **120초** 타임아웃을 쓰며 `max_retries=2` 는 최초 요청 포함 **최대 3회**, 즉 논리 호출당 최대 **6분**
- 변환은 문서당 최대 두 논리 호출이고 judge 도 추가 호출한다. 35분 20초 실측의 19분 40초 여유는 **네 번의 timeout-limited 호출만으로 소진**될 수 있다
- 저장소에 이미 31건의 rate-limit/overload 실패 기록이 있다
- codex 가 명시한 성격: "**거짓 초록은 아니지만** 이 변경의 목표인 품질 비교·판정을 다시 내놓지 못한다"

### C-2 가 든 세부 근거 (codex 원문에서 발췌)

- F5 의 `test_golden_eval.py:283` 은 현재 `log_progress` docstring, 변환 루프는 **347행**
- F6 의 373행도 judge 루프가 아니라 필수 정보 테스트 docstring, 실제 루프는 **441행**
- F7 의 "시간이 전부 `outcomes` fixture 안" 단언은 **judge API 호출이 테스트 본문에서 수행되므로 거짓**
- F8 은 공용 provider 상수 60초를 인용하나 실제 Anthropic 레인은 `anthropic_provider.py:31` 의 **120초**를 사용
- 분모 단언과 필수 정보 한도 인용도 각각 현재 **517행**·**114행**으로 이동
- §8 이 §6·§7 의 후속임은 명시되어 있어 **역사 보존 자체는 결함으로 보지 않았다**. 다만 §1 의 현재 사실 표는 **조건 7 위반**

### codex 가 "결함 없음"이라고 명시한 축

지적을 만들어 내지 않은 자리도 그대로 옮긴다.

| 축 | codex 진술 |
|---|---|
| **A** (지문 고정) | "결함 없음: 모델·effort 고정은 기준선 기록이 아니며, API가 다른 모델 문자열을 반환하면 `비교 불가/blocking=true`로 실패한다" |
| **C** (로그 노출) | "결함 없음: 진행 로그와 현재 httpx/Anthropic INFO 경로에서 본문·헤더·키 노출을 찾지 못했다" |
| **B** (Actions 시맨틱 자체) | "GitHub 표현식 규약과 runner 구현대로 동작한다" |
| **E** (루프·분모·허용치·호출 상한) | "루프 변수·분모·필수 정보 허용치·호출 상한 우회도 발견하지 못했다" |

### codex 가 남긴 Next steps (원문)

- 시간 예산과 사실 근거를 수정한 뒤, 쓰기 가능한 환경에서 기본 비-LLM 테스트와 범위 셀프체크를 다시 실행한다
- 실제 API 레인은 로컬에서 돌리지 말고 수정된 CI에서 최초 완주 시간과 timeout/retry 진행 로그를 확인한다

### 전제 확인이 필요한 항목

원문을 지우지 않고 표시만 남긴다. 판정은 `migration-reviewer` / 리더의 몫이다.

- C-1 이 인용한 `.github/workflows/ci.yml:891-903` 과 `899-900` 의 줄번호가 현재 파일과 맞는지 **미확인**
- C-1 의 "Anthropic 구현은 120초 · `max_retries=2` 는 최초 요청 포함 최대 3회" 가 실물과 맞는지 **미확인**
- C-2 가 제시한 대체 줄번호(347·441·517·114·`anthropic_provider.py:31`)가 현재 파일과 맞는지 **미확인**

---

## 5. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| `-m llm` 레인 실행 | **의도적 미실행** — 리더 지시(35분·실과금). codex 도 원문에서 "`-m llm`은 실행하지 않았고"라고 자기 진술 |
| codex 셸 명령 3건 실패 | codex 자신의 명령 문법 오류와 **읽기전용 샌드박스에서 pytest 임시파일 생성 실패**로 기록됨. codex 는 캡처를 끈 방식으로 우회해 진행했고 "범위 완전성 셀프체크는 통과"라고 진술 |
| 재시도 | **불필요** — 1회 호출로 exit 0, 비어 있지 않은 출력 확보 |
| 출력 잘림 | **없음** — 3,820 바이트 전문 확보, 구조(Verdict / Findings / Next steps) 완결 |
