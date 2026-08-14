# 게이트 10 — codex 독립 리뷰 (1단계)

> 어간 `10_detector-redesign` 은 **리더가 1단계 호출에서 지정한 값**을 그대로 쓴다.
> 이 문서는 `codex-reviewer` 산출물이다. **codex 원문은 §3 에 무편집으로 수록**하며,
> Claude 의 판정·심각도 부여·중복 병합·표현 다듬기는 하지 않는다.
> 교차 대조와 종합은 `migration-reviewer` 2차 호출(`10_detector-redesign_cross.md`)의 몫이다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 회차 | **2회차** (1회차는 프로세스 사망으로 결과 없음 — §5.1) |
| 실행 시각 | 2026-08-14 11:15 KST 착수 / 소요 9m 13s |
| 대상 범위 | `c61c94e..HEAD` (branch diff, `--base c61c94e`) |
| **스크립트 종료 코드** | **`0`** — 리뷰 근거로 성립 |
| 리뷰 대상 (stderr 원문) | `codex-review: 리뷰 대상 = branch diff vs c61c94e` |
| 대상 판정 (stderr 원문) | `codex-review: 대상 판정 = non-empty (merge-base=c61c94e29d04, 변경 파일 26개 (branch 모드는 커밋된 변경만 센다))` |
| 모드 | `adversarial-review` |
| scope | `auto(미지정)` — `--base` 지정이므로 scope 무시 |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 버전 · 출처 | `1.0.6` / plugins cache (최신 버전 자동 선택) |
| job id | `review-mssbds06-5ep6dy` |
| codex session id | `019ffe0c-efee-7840-a809-6473ecce96f0` |
| codex 가 보고한 Target | `branch diff against c61c94e` (출력 3행) |

**실행 명령 전문**

```bash
FOCUS="$(cat <focus 파일>)" \
  && .claude/skills/codex-review/scripts/codex-review.sh adversarial --base c61c94e "$FOCUS"
```

헬퍼로 나간 실제 명령:

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base c61c94e '<focus 전문 — §2>'
```

`run_in_background` 로 띄웠다. 1회차가 포그라운드 도구 타임아웃에 SIGTERM 으로 잘려 죽었기 때문이다(§5.1).

### 1.1 대상 리비전 결속

리더가 지정한 리뷰 대상은 커밋 범위 `c61c94e..56a70c1` (12커밋)이다. 리뷰 실행 시점의 `HEAD` 는
**`525fc96`** 으로, `56a70c1` 위에 docs 커밋 1건이 얹힌 상태였다. 결속을 실측으로 확인했다.

```
$ git diff --name-only 56a70c1..525fc96
docs/migration/_workspace/00_progress.md

$ git diff --name-only 56a70c1..525fc96 | grep -Ev '^docs/' | wc -l
0

$ git diff --name-only c61c94e..56a70c1 | wc -l   → 26
$ git diff --name-only c61c94e..525fc96 | wc -l   → 26
```

**코드 대상 동일.** `525fc96` 은 `docs/migration/_workspace/00_progress.md` 한 건(+5/−1)만 건드리는
docs 전용 커밋이고, 그 파일은 이미 `c61c94e..56a70c1` 범위 안에 있었다. 두 범위의 변경 파일 집합이
26개로 같으므로, 리뷰가 실제로 본 코드·하네스·fixture 는 리더가 지정한 범위와 일치한다.

대상 커밋(리더 지정 6건):

| 커밋 | 내용 |
|---|---|
| `3570bdc` | 평문 로그 탐지기 재설계 — 사슬 파싱(정규식 종단 폐기)·상태 유지 lexer·균형 인자 (M-01·M-03·M-09) |
| `75bfb40` | provenance 탐지기를 끄는 두 방법 차단 — 클래스별 CI 스텝(M-02)·감시 집합 독립 근거+소스 대조(M-04) |
| `3727905` | parse() 반대 실패 모드 통합 — usage 조립 전 검증·누락 6종 거절/실려 온 0 수용 (M-10·M-11) |
| `c2255dc` | 인자 파서가 주석 안 괄호를 코드로 세던 누락 — 어휘 상태와 코드 텍스트 통합 (stop-gate 지적) |
| `ac0307e` | TAB 구분자 제거 + candidateSpans 합집합 근거 교체 + 경계축 회귀 3+3 (M-06·K-1) |
| `56a70c1` | fixture — TAB 방향 전환·경계축 12케이스 동결·감수 표면 known_gap 2건·M-08 중복 검출·Z-7 상시 테스트 분할 |

### 1.2 제공한 맥락

focus text 안에 다음을 **조건(채점 기준)** 으로 명시했다. 저장소는 codex 가 직접 읽을 수 있으므로
경로를 지목하는 방식으로 주입했다.

- 판정 정본: `docs/migration/_workspace/07_privacy-gate_masking-verdicts.md` §4-septies (접기 방향·TAB)
- 재개발 판정 기준: "Python 과 같은 값"이 아니라 "요구사항·정책 충족"
- 대상 파일: 스캐너 본체·테스트·프로브, `Masking.kt`·`MaskingTest.kt`·`ProvenanceCreationSitesTest.kt`,
  `parity/fixtures/masking/masking.json`·`parity/reference-ledger/masking.json`, parity 스크립트 2종,
  `tests/test_parity_ci_gate.py`, `.github/workflows/ci.yml`
- 불변량: "주석은 검출을 만들어서도 없애서도 안 된다"

**민감 데이터 미포함.** 사용자 문서 본문·실제 암호문·키·개인정보를 프롬프트에 싣지 않았다.
개인정보 형태가 필요한 자리는 합성 값 서술로만 언급했다.

### 1.3 focus 축 구성

리더가 지정한 4축에 `codex-review` 스킬 §4.6(선언 범위 대 실제 도달)을 5번째 축으로 더했다.
§4.6 은 "게이트·불변식·규칙을 세우거나 넓히는 변경이면 focus 에 **필수**"이고, 이번 변경이
정확히 그 종류(탐지기 재설계·CI 스텝 신설·감시 집합 강제)다. §3.5 의 "3~5축" 상한 안에 든다.

| 축 | 내용 |
|---|---|
| (1) | 재설계된 스캐너의 잔여 우회 — 사슬 파서·상태 유지 lexer·균형 인자 추출 적대적 돌파 |
| (2) | provenance 감시 집합의 소스 대조가 위조 가능한가 |
| (3) | TAB 제거·경계축·합집합 근거가 §4-septies 와 문자 단위로 정합한가 |
| (4) | fixture 동결의 방향 정확성 (`masking-keeps-rrn-tab`·경계축 3+3·known_gap 2건) |
| (5) | 선언 범위 대 실제 도달 (§4.6, 앞 4축을 가로지름) |

---

## 2. 전달한 프롬프트 전문

```
배경: Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 전환이다. 판정 기준은 "Python과 같은 값"이 아니라 "요구사항·정책 충족"이다. 이 변경은 (a) 평문 로그 탐지기 `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` 를 정규식 종단 매칭에서 사슬(chained call) 파싱·상태 유지 lexer·균형 괄호 인자 추출로 재설계했고, (b) provenance 탐지기의 감시 집합을 `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt` 소스 대조로 강제했고, (c) `SPACE_CHARS` 에서 TAB 을 제거하고 `candidateSpans` 의 근거를 교체했고, (d) parity fixture 와 테스트를 그 방향으로 동결했다. 판정 정본은 `docs/migration/_workspace/07_privacy-gate_masking-verdicts.md` 의 4-septies 절이다.

다음 5축으로 적대적으로 읽어라.

(1) 스캐너 잔여 우회. 대상: `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py`, 테스트 `tests/test_privacy_scanner.py`, 프로브 `tests/fixtures/privacy-scanner-probes/`. 불변량: 주석은 검출을 만들어서도 없애서도 안 된다 — 코드 텍스트에 주석을 넣거나 빼도 탐지 결과가 달라지면 안 된다. 사슬 파서·상태 유지 lexer·균형 인자 추출을 적대적으로 뚫어라. 구체적으로: 문자열 보간 `${...}` 안에 들어간 주석 기호·따옴표·괄호, 중첩 람다와 trailing lambda, 백틱 식별자, 세미콜론으로 이어붙인 다중 문장, raw string `"""` 과 escape 시퀀스, 문자 리터럴 `'"'`, KDoc 과 중첩 블록 주석, 줄 연속·개행 위치를 바꾼 사슬. 우회가 성립하면 마스킹 전 평문이 로그로 새어도 게이트는 초록불로 통과한다.

(2) provenance 감시 집합의 위조 가능성. 대상: `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt`, `Masking.kt`, `.github/workflows/ci.yml`. 불변량: 감시 집합은 소스와 독립 근거로 대조돼야 하며, 대조 기준을 검사 대상 자신이 바꿀 수 있으면 안 된다. 주석 안 선언·문자열 안 선언·다른 파일로 옮긴 선언·이름만 바꾼 선언이 세어지는가 또는 조용히 빠지는가. CI 의 클래스별 분리 스텝이 한쪽만 돌아도 초록이 되는 경로가 있는가.

(3) TAB 제거·경계축·합집합 근거의 문자 단위 정합. 대상: `Masking.kt`, `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt`. 판정은 `SPACE_CHARS` 에서 TAB 을 뺀다는 것이다. 등가성 단언(TAB 을 공백 2개와 같게 취급한다는 류)이 실제로 무는지 문자 단위로 확인하라. TAB 이 다른 경로 — 정규화, 접기(folding), `candidateSpans` 합집합 — 로 여전히 구분자처럼 동작해 판정이 무력화되는가. 경계축 양성/음성 짝이 실제로 서로 반대 방향을 검사하는가, 아니면 둘 다 같은 방향이라 한쪽이 무의미한가.

(4) fixture 동결의 방향 정확성. 대상: `parity/fixtures/masking/masking.json`, `parity/reference-ledger/masking.json`, `.claude/skills/python-kotlin-parity/scripts/compare_parity.py`, `dump_parity_fixtures.py`, `tests/test_parity_ci_gate.py`. 이름이 방향을 말하는 케이스(예: `masking-keeps-rrn-tab`)의 기대값이 판정과 반대로 동결되지 않았는가. known_gap 으로 표시된 항목이 "알려진 결함"이 아니라 "정답"으로 굳어 앞으로 회귀를 못 잡게 되지 않았는가. 비교기가 fixture 누락·빈 케이스 목록·0건 비교를 성공으로 통과시키는가.

(5) 선언한 범위 대 실제 도달 (앞 4축을 가로지른다). 이 변경이 세우거나 넓히는 게이트들이 실제로 어디서 도는가를 CI 설정 파일에서 실행 경로로 짚어라. 검사가 대조하는 기준이 검사 대상 자신에게서 나오는 자리(생성기가 만든 fixture 를 그 생성기의 선언과 대조하는 구조, 구현을 복사한 기대값), 성공 판정이 대리 지표로 이뤄지는 자리(종료 코드 0 을 "검토했다"로, 지적 0건을 "문제 없음"으로, 파일 존재를 "검사했다"로 바꿔 읽는 자리), 그리고 떼어내도 아무 테스트가 깨지지 않는 장치를 지목하라.
```

---

## 3. codex 원문 출력 (무편집)

> 아래 블록은 codex 가 낸 출력 전문이다. 문구·순서·경로·라인 번호를 일절 고치지 않았다.
> 마지막 `SCRIPT_EXIT=0` 한 줄만 codex 가 아니라 **호출 래퍼가 덧붙인 것**이며, 구분을 위해 블록 밖에 적는다.

```
# Codex Adversarial Review

Target: branch diff against c61c94e
Verdict: needs-attention

No-ship: privacy 스캐너와 provenance/parity 게이트에 재현 가능한 false-green 경로가 남아 있어, 평문 로그 유출이나 필수 경계 케이스 삭제가 CI 초록으로 통과할 수 있다.

Findings:
- [high] 한 논리 줄의 두 번째 로그 호출은 검사되지 않는다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:865-869)
  논리 줄마다 `pattern.search()`를 한 번만 호출하고 첫 적중의 `refine`이 안전하다고 판단하면 다음 호출을 찾지 않는다. 실제로 `logger.info("{}", draft.stats.count); logger.info("{}", draft.value)`와 `.also { logger.info("{}", draft.value) }`를 넣은 프로브가 모두 MISSED였다. 안전한 집계 로그를 앞세우면 뒤의 평문 본문 로그가 CI 스캐너를 통과한다.
  Recommendation: 각 논리 줄에서 모든 로그 호출을 순회해 호출별 인자 범위를 판정하라. 세미콜론, chained call, trailing lambda에서 안전/위험 호출 순서를 양방향으로 바꾼 회귀 프로브를 추가하라.
- [high] 중첩 블록 주석이 괄호 상태를 조기에 닫아 위험 인자를 숨긴다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:706-733)
  Kotlin 블록 주석은 중첩 가능하지만 lexer는 Boolean 상태만 유지해 첫 `*/`에서 전체 주석이 끝났다고 본다. `/* outer /* inner */ ) outer */`를 안전한 `draft.stats.count`와 위험한 `draft.value` 사이에 넣으면 주석의 `)`가 호출을 닫고 결과가 MISSED가 되며, 같은 코드에서 주석을 제거하면 CAUGHT가 됐다. 테스트의 참조 주석 제거기도 같은 Boolean 모델을 써 이 불변량 위반을 발견하지 못한다.
  Recommendation: 블록 주석을 nesting depth로 추적하고 Kotlin 문자열 템플릿의 `${...}` 코드 상태도 별도로 lex하라. 중첩 KDoc/블록 주석과 보간 내부 주석에서 주석 유무 쌍의 전체 ScanResult가 동일한지 검증하라.
- [high] provenance 감시 집합을 주석·raw string의 가짜 선언으로 위조할 수 있다 (backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt:193-209)
  대조는 `Masking.kt` 한 파일의 각 줄을 정규식으로만 읽으며 주석이나 문자열을 제거하지 않는다. 따라서 raw string 또는 블록 주석 안에 `value class ModelDraft(val value: String)` 같은 가짜 줄을 남기고 실제 래퍼를 이름 변경·이동해도 `declared == WATCHED_TYPES`를 만족시킬 수 있다. 또한 다른 파일에 추가한 세 번째 provenance 래퍼는 애초에 선언 대조 범위 밖이다. 이후 그 타입의 생성 지점은 전혀 감시되지 않는다.
  Recommendation: Kotlin PSI/컴파일 심볼로 전체 소스 트리의 실제 선언을 수집하고, 요구사항 소유의 독립 manifest와 대조하라. 주석·raw string 선언, 다른 파일 이동, 이름 변경, 새 래퍼 추가를 각각 false-green 음성 테스트로 고정하라.
- [high] 새 masking fixture 축은 같은 생성기를 정본으로 삼아 통째로 축소할 수 있다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:924-940)
  비교기의 정본은 변경 가능한 `BUILDERS[domain]()`을 다시 실행한 결과다. Kotlin 생산자도 fixture JSON의 현재 케이스를 동적으로 순회하고 CI 하한은 도메인 이름만 고정한다. 따라서 `build_masking`에서 경계/TAB 케이스를 삭제하고 fixture·ledger를 함께 재생성하면 `masking` 도메인은 남은 채 모든 대조가 다시 일치한다. 현재 83케이스·299단언이 실행된 사실은 확인됐지만, 그 특정 케이스 집합의 비감소를 보장하지 않는다.
  Recommendation: §4-septies가 요구하는 case ID와 방향을 생성기와 독립된 정책 manifest에 고정하고 CI에서 현재 집합이 이를 포함하는지 검사하라. 필수 case 하나를 생성기·fixture·ledger에서 함께 제거해도 반드시 실패하는 mutation test를 추가하라.
- [medium] 범위를 벗어난 정수 token usage가 작은 정상값으로 축소된다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt:370-374)
  `isIntegralNumber`는 Long/BigInteger도 허용하지만 `asInt()`는 범위 검증 없이 하위 32비트로 축소한다. 예를 들어 4294967296은 0이 되어 음수 검사도 통과한다. 벤더의 손상되거나 스키마가 변한 응답이 실패 대신 정상 completion으로 처리되어 사용량과 원가가 조용히 과소 집계될 수 있다.
  Recommendation: `canConvertToInt()`를 확인한 뒤 변환하고 합리적인 토큰 상한도 검증하라. Int 경계값과 2147483648, 4294967296 응답이 실패하는 테스트를 추가하라.

Next steps:
- 스캐너의 두 false-green 프로브를 정식 fixture로 추가하고 CI 스캐너/회귀 테스트를 재실행한다.
- provenance 선언 수집과 masking 필수 case 집합을 독립 근거에 결속한 뒤 삭제·이동·위조 mutation을 실행한다.
- 토큰 정수 범위를 닫고 Kotlin 테스트 및 전체 parity 게이트를 다시 실행한다.
```

래퍼가 덧붙인 줄: `SCRIPT_EXIT=0`

출력은 잘리지 않았다 — `Findings:` 5건과 `Next steps:` 3항목이 모두 닫혔고 문서가 완결된 형태로 끝난다.

---

## 4. 정리(가공)

> **이 구획은 Claude 가 목록화한 것이며 codex 의 말이 아니다.** 옳고 그름·심각도 재부여·중복 병합은 하지 않는다.
> 심각도 표기는 codex 가 붙인 `[high]`/`[medium]` 을 그대로 옮긴 것이다.
> 경로·라인 번호는 codex 가 준 값을 **다시 세지 않고 그대로** 옮겼다.

### 4.1 종합 판정 (codex 표기 그대로)

- `Verdict: needs-attention`
- `No-ship: privacy 스캐너와 provenance/parity 게이트에 재현 가능한 false-green 경로가 남아 있어, 평문 로그 유출이나 필수 경계 케이스 삭제가 CI 초록으로 통과할 수 있다.`

### 4.2 지적 항목 목록

| # | codex 심각도 | 요지 | codex 가 제시한 근거 위치 | 대응 focus 축 |
|---|---|---|---|---|
| C-1 | `[high]` | 한 논리 줄의 두 번째 로그 호출은 검사되지 않는다 | `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:865-869` | (1) |
| C-2 | `[high]` | 중첩 블록 주석이 괄호 상태를 조기에 닫아 위험 인자를 숨긴다 | `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:706-733` | (1) |
| C-3 | `[high]` | provenance 감시 집합을 주석·raw string 의 가짜 선언으로 위조할 수 있다 | `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt:193-209` | (2)·(5) |
| C-4 | `[high]` | 새 masking fixture 축은 같은 생성기를 정본으로 삼아 통째로 축소할 수 있다 | `.claude/skills/python-kotlin-parity/scripts/compare_parity.py:924-940` | (4)·(5) |
| C-5 | `[medium]` | 범위를 벗어난 정수 token usage 가 작은 정상값으로 축소된다 | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt:370-374` | 축 밖 (커밋 `3727905` 범위) |

### 4.3 codex 가 실행했다고 서술한 관측

원문에 "실제로 돌려 봤다"는 취지의 서술이 붙은 항목만 옮긴다. **이 관측의 재현·검증은 하지 않았다.**

- C-1: "실제로 `logger.info("{}", draft.stats.count); logger.info("{}", draft.value)`와
  `.also { logger.info("{}", draft.value) }`를 넣은 프로브가 모두 **MISSED**였다."
- C-2: "`/* outer /* inner */ ) outer */`를 … 넣으면 … 결과가 **MISSED**가 되며, 같은 코드에서
  주석을 제거하면 **CAUGHT**가 됐다." — focus 에 준 불변량("주석은 검출을 만들어서도 없애서도 안 된다")의
  반례로 서술돼 있다.
- C-4: "현재 **83케이스·299단언**이 실행된 사실은 확인됐지만, 그 특정 케이스 집합의 비감소를 보장하지 않는다."

### 4.4 전제 확인 필요

codex 가 저장소를 직접 읽었으므로 경로는 실재하나, 아래 두 가지는 종합 단계에서 확인할 여지가 있다.
**여기서 옳고 그름을 판정하지 않는다.**

- C-3 후반부의 "다른 파일에 추가한 세 번째 provenance 래퍼는 애초에 선언 대조 범위 밖이다"는
  현재 코드의 결함 서술이 아니라 **장래 확장 시나리오**에 대한 서술이다. 두 층위가 한 항목에 묶여 있다.
- C-5 는 리더가 준 4축 어디에도 대응하지 않는다. 대상 커밋 `3727905`(parse() 실패 모드) 범위 안이라
  diff 상 리뷰 대상이지만, focus 축이 유도한 지적은 아니다.

### 4.5 focus 축별 지적 유무 (있는 그대로)

**축 (3)에서 지적이 나오지 않았다.** codex 출력에 TAB 제거·경계축 짝·`candidateSpans` 합집합 근거의
문자 단위 정합에 대한 항목이 없다. 스킬 §7 에 따라 **이 사실을 그대로 기록하며, Claude 가 대신 지적을
만들어 채우지 않는다.** 축 (3)이 "문제 없음"이라는 뜻인지, codex 가 그 축을 얕게 봤다는 뜻인지는
이 문서가 판정하지 않는다 — 교차 대조에서 다룰 사항이다.

| 축 | 지적 |
|---|---|
| (1) 스캐너 잔여 우회 | C-1, C-2 |
| (2) provenance 위조 | C-3 |
| (3) TAB·경계축·합집합 문자 단위 정합 | **없음** |
| (4) fixture 동결 방향 | C-4 |
| (5) 선언 범위 대 실제 도달 | C-3, C-4 (교차) |
| 축 밖 | C-5 |

또한 축 (4)의 세부 질문 중 `masking-keeps-rrn-tab` 기대값 방향, known_gap 2건, M-08 중복 검출,
Z-7 상시 테스트 7종에 대한 **개별 판정은 출력에 없다.** C-4 는 케이스 집합의 **비감소 보장 부재**를
지적할 뿐 개별 케이스의 방향이 맞는지는 다루지 않는다.

---

## 5. 회차 이력 · 미실행/실패 항목

### 5.1 1회차 — 프로세스 사망, 결과 없음 (리뷰 근거 아님)

| 항목 | 값 |
|---|---|
| job id | `review-mss5xs9u-pbrmp8` |
| codex session id | `019ffd81-680b-7f43-9ced-980ac93db747` |
| 착수 | 2026-08-13T23:42:22Z (= 2026-08-14 08:42 KST) |
| 마지막 로그 활동 | 2026-08-13T23:50:26Z |
| 결과 | **없음** — `result review-mss5xs9u-pbrmp8` → `No job found` |
| 처리 | `cancel` 후 **동일 focus** 로 재디스패치 |

**사유.** 1회차는 Bash 도구 **포그라운드**로 띄웠고 10분 도구 타임아웃에서 `exit 143`(SIGTERM)으로
잘렸다. codex 프로세스가 그 셸의 자식이라 함께 죽었다. job 상태는 완료 기록을 쓸 주체가 사라져
`running` 에 멈춰 있었으나(조회 시점 누적 2h 30m), **마지막 로그 활동 이후 2시간 30분간 무출력**이었고
결과 조회도 실패해 살아 있지 않음을 확인했다.

동일 job 임을 확인한 근거: 1회차 잘린 출력의 마지막 명령들과 job 로그 말미가 일치한다
(`git show --stat --oneline 3570bdc c2255dc 75bfb40 ac0307e 56a70c1`,
`PYTHONDONTWRITEBYTECODE=1 .venv/bin/python .claude/skills/python-kotlin-parity/... (exit 3)`).

1회차와 2회차는 **같은 base·같은 focus** 이므로 회차 간 결과 변동 비교 대상이 아니다
(1회차는 결과 자체가 없다).

### 5.2 오인 회수 방지 기록 — `review-msrkckhy-dchb11` 은 이 게이트의 결과가 아니다

세션 재개 시 `status --all` 에 완료 상태로 보이던 `review-msrkckhy-dchb11`(9m 18s, NO-SHIP 요약)을
회수해 확인한 결과 **`Target: branch diff against c11a404`** 로, 이 게이트의 base(`c61c94e`)가 아니었다.
착수 시각도 2026-08-13T13:38:01Z 로 대상 커밋 `56a70c1`(2026-08-14 08:38 KST) **이전**이다.
즉 앞선 게이트(09)의 산출물이며 이 문서에 싣지 않는다.

같은 이유로 `review-mss2cqg6-meyu76`(2026-08-13T22:02Z), `review-msrnd77a-m3xqzx`·
`review-msrnco2w-6242np`(2026-08-13T15:02Z)도 대상 커밋 이전이라 이 게이트와 무관하다.

이 기록을 남기는 이유: job 요약만 보고 회수하면 **다른 base 의 리뷰가 이 게이트의 근거로 실릴 수 있었다.**
`status` 목록은 base 를 보여주지 않으므로 회수한 결과의 `Target:` 줄을 반드시 대조해야 한다.

### 5.3 미실행 항목

- **없음.** 2회차가 종료 코드 `0`, 대상 판정 `non-empty`(26개 파일)로 완주했고 출력이 잘리지 않았다.
- codex 관측(§4.3)의 **재현 검증은 이 역할의 범위가 아니다** — 수행하지 않았고, 종합 단계의 몫이다.
- 코드 수정은 하지 않았다.
