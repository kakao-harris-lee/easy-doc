# 02_criteria-pivot — codex 독립 리뷰 (1회차)

> 이 문서는 `codex-reviewer`가 작성했다. **3번 구획(codex 원문)은 무편집이다.**
> Claude의 판단·심각도 재부여·중복 병합은 하지 않았다. 정리·판정은 `migration-reviewer`의 교차 대조와 리더의 몫이다.

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 회차 | 1회차 |
| 실행 시작 | 2026-08-12T14:54:02+09:00 |
| 실행 종료 | 2026-08-12T15:06:23+09:00 |
| 소요 시간 | 741초 (12분 21초) |
| **스크립트 종료 코드** | **0** (= 리뷰 근거로 유효) |
| 대상 커밋 | `49ea2ebc0d999c5883105457e23f28a53bd4f226` (`feat/kotlin-migration-harness` 최신) |
| diff 범위 | `78fdc2eebf6fc1cfad00b73c9aa7132ae4436616..HEAD` — **커밋 1개만** |
| 변경 파일 수 | 38개 |
| 모드 | `adversarial` (→ 헬퍼 `adversarial-review`) |
| scope / base | `auto`(미지정) / `--base 78fdc2e` (base 지정 시 scope 무시) |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택), 플러그인 버전 `1.0.6` |
| codex CLI | `codex-cli 0.147.0` (`/Users/harris/.nvm/versions/node/v22.21.1/bin/codex`) |
| thread id | `019ff488-f5fb-7ff3-9109-785fe6cf268d` |
| turn id | `019ff488-f7ad-7290-bf13-5a46840965ff` |
| codex가 실행한 명령 수 | 90건 (`Running command` 카운트) |
| stdout / stderr 크기 | 6,586 B / 34,085 B |
| codex 최종 verdict | `needs-attention` |

### 스크립트 대상 판정 두 줄 (stderr 원문)

```
codex-review: 리뷰 대상 = branch diff vs 78fdc2e
codex-review: 대상 판정 = non-empty (merge-base=78fdc2eebf6f, 변경 파일 38개 (branch 모드는 커밋된 변경만 센다))
```

`merge-base(HEAD, 78fdc2e) = 78fdc2e` 임을 사전 확인했으므로, 이 범위는 브랜치 누적분이 아니라 커밋 `49ea2eb` 하나다.

### 실행 명령 전문

스크립트 호출 (`$FOCUS`는 아래 2번 구획의 전문):

```bash
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 78fdc2e "$FOCUS"
```

스크립트가 실제로 실행한 명령:

```bash
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base 78fdc2e '<focus text — 2번 구획 전문>'
```

### 제공한 맥락

codex는 저장소를 직접 읽을 수 있으므로 파일 본문을 프롬프트에 싣지 않고 **경로와 채점 기준만** 제시했다. 실제 사용자 문서·암호문·키·개인정보는 프롬프트에 포함하지 않았다 (예시는 모두 합성 값).

- 채점 기준: 마스킹 선행 불변식, 축소된 2종 범주와 감수된 3종 평문 전달, 로그·저장소 산출물 금지 조건, `{"detail": ...}`·snake_case·404 은닉·`no-store`/`nosniff`, parity 게이트 무력화 금지, 문서군 무모순
- 지목한 경로: `tests/golden/test_schema.py`, `.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py`, `compare_parity.py`, `app/easyread/collection.py`, `app/privacy/masking.py`, `contracts/easy-doc-v1.yaml`, 문서군 7종
- 보고 형식 요구: 지적마다 **직접 실행해 재현한 것**과 **읽고 판단한 것**을 구분 표시

### 유도 회피 확인

리더가 준 6개 질문은 일부가 답을 암시하는 형태였으므로(예: "약화를 강화로 서술한 것인가"), 프롬프트에서는 **판정을 요구하는 중립형**으로 바꿔 넘겼다 — "병합 전후 보장 집합을 실제로 대조해 넓어졌는지 좁아졌는지 판정하라". Claude 측 원인 추정이나 기대 결론은 프롬프트에 넣지 않았다. 병렬 실행 중인 `migration-reviewer`의 산출물(`02_criteria-pivot_migration-reviewer.md`)은 읽지 않았다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
배경: Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 마이그레이션이 진행 중이다. Kotlin 코드는 아직 0줄이고, 이 커밋은 검증 기준·개인정보 마스킹 정책·API 계약 세 가지를 문서·하네스·Python 코드에 동시에 반영한다.

이 저장소에서 지켜져야 하는 조건(채점 기준):
1. 마스킹 선행 불변식 — 사용자 문서 텍스트는 app/privacy/masking.py 파이프라인을 통과한 뒤에만 LLM provider로 전달된다. 마스킹 범주가 5종에서 2종(주민등록번호/외국인등록번호, 카드번호)으로 줄어도 이 순서 불변식은 그대로다.
2. 전화번호·이메일·계좌번호는 이제 마스킹되지 않고 평문으로 LLM에 전달된다. 이것은 명시적으로 감수하기로 한 결정이다. 다만 이 범주가 로그, 저장소에 커밋되는 산출물, API 응답에 평문으로 남는 것은 별개 문제다 — CLAUDE.md는 로그에 문서 본문·개인정보를 남기지 말 것을 요구한다.
3. API 오류 본문은 {"detail": ...} 형태, JSON 필드는 snake_case, 타 사용자 자원 접근은 403이 아니라 404(자원 존재 은닉), 응답에 Cache-Control: no-store 와 X-Content-Type-Options: nosniff.
4. parity 하네스(.claude/skills/python-kotlin-parity/scripts/compare_parity.py, dump_parity_fixtures.py)는 Python 구현과 Kotlin 구현의 동등성을 증명하는 게이트다. 불일치가 검증 없이 통과되는 경로가 생기면 안 된다.
5. 문서군(docs/master-plan.md, CLAUDE.md, docs/plans/2026-08-11-kotlin-react-migration.md, docs/golden-collection-plan.md, contracts/easy-doc-v1.yaml, .claude/skills/python-kotlin-parity/SKILL.md, .claude/skills/api-contract-freeze/SKILL.md)은 서로 모순되면 안 된다.

찾아야 할 것:

A. tests/golden/test_schema.py 에서 두 개의 테스트가 하나로 병합됐다. 병합 전 각 테스트가 보장하던 입력 집합과 병합 후 보장 집합을 실제로 대조해, 보장 범위가 넓어졌는지 좁아졌는지 판정하라. 원래 검사 중 하나는 합성 문서 20건 전부가 마스킹 가능한 개인정보를 갖는다는 것이었고, 그 20건의 자격 근거가 어떤 범주였는지 fixture를 열어 세어 보라. 코드가 실제로 하는 일과 커밋 메시지·주석의 서술이 일치하는지도 보라.

B. .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py 에서 masking-scope-out-* 3건의 reference_divergence: "expected" 선언이 제거됐다. 제거 전후로 compare_parity.py 가 어떤 불일치를 통과시키고 어떤 것을 실패시키는지 대조해, 게이트 강도가 어느 방향으로 변했는지 판정하라.

C. app/easyread/collection.py 는 골든셋 수집물을 저장소에 커밋하는 파이프라인이다. 전화·이메일·계좌 3범주가 마스킹 대상에서 빠진 뒤 이 파이프라인이 무엇을 저장소에 쓰게 되는지 데이터 흐름을 끝까지 추적하고, 이 커밋이 넣은 완화 조치가 실제로 무엇을 막고 무엇을 못 막는지 판정하라.

D. app/privacy/masking.py 의 범주 축소가 남은 2종(주민등록번호, 카드번호)의 탐지 능력·오탐률·경계 조건을 의도치 않게 바꾸었는지 diff를 라인 단위로 대조하라. 공유 정규식·헬퍼·복원(unmask) 로직·플레이스홀더 번호 매기기·중복 처리 순서에 부수 효과가 있는지 본다. 가능하면 실제로 함수를 실행해 경계 입력(자릿수 경계, 구분자 변형, 인접 숫자열, 유니코드)으로 확인하라.

E. contracts/easy-doc-v1.yaml 에 추가된 전역 응답 헤더 조항이 기계적으로 검사 가능한 형태인지, 아니면 검사 주체가 없는 선언문인지 판정하라. 이 조항을 위반하는 구현이 나왔을 때 무엇이 그것을 잡는지 구체적으로 지목하라. 잡는 것이 없으면 없다고 적어라.

F. 위 5번의 문서군이 마스킹 범주 수, 검증 기준(Python 출력 일치인가 요구사항 충족인가), 계약 개선 가능 여부에 대해 서로 다른 말을 하는 지점을 열거하라. 갱신이 누락된 문장을 파일:라인으로 지목하라.

보고 형식: 각 지적에 대해 (1) 파일:라인, (2) 그것이 네가 직접 명령을 실행해 재현한 것인지 아니면 코드를 읽고 판단한 것인지를 반드시 구분해 표시하라. 실행했다면 실행한 명령과 관측된 출력을 적어라. 확신이 없으면 확신 없음이라고 적어라. 지적할 것이 없는 항목은 없다고 적어라.
```

---

## 3. codex 원문 출력 (무편집)

아래 블록은 `codex-review.sh`의 stdout을 **바이트 그대로** 옮긴 것이다. Claude가 쓴 문장은 이 블록 안에 한 줄도 없다.

````text
# Codex Adversarial Review

Target: branch diff against 78fdc2e
Verdict: needs-attention

출하 불가. 저장소·stdout 개인정보 노출, 구현·테스트 없는 전역 보안 헤더 선언, 실제 식별자 누출을 허용하는 parity fixture가 확인됐다. 골든 테스트 병합도 문서별 보장을 20/20에서 2/20으로 축소한다. 반면 B의 reference_divergence 제거는 게이트를 강화했고, D의 남은 두 정규식·공유 로직에는 diff상 부수 변경이 없었다. 전체 pytest는 읽기 전용 환경의 임시 디렉터리 생성 실패로 실행하지 못했다.

Findings:
- [high] 골든 초안과 미리보기가 전화·이메일·계좌번호를 평문으로 저장·출력한다 (app/easyread/collection.py:867-903)
  [실행 재현] `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -c '<build_draft/preview_first_line 호출>'` 결과 `draft_source_text`와 직렬화된 `source_text`에 `010-1234-5678`, `kim@example.com`, `123-456-789012`가 그대로 남았고, preview도 `전화 010-1234-5678 메일 kim@example.com 계좌 ...`를 출력했다. [코드 판독] `mask_text()` 결과를 그대로 GoldenDocument에 넣고(886-903) JSON으로 쓰며(916-928), preview도 두 범주 마스킹 후 첫 줄을 stdout에 보낸다(647-670). `docs/golden-drafts`는 저장소 내부 기본 경로이고 `.gitignore` 대상도 아니다. 이 커밋의 완화책은 `docs/golden-collection-plan.md:147-169`의 수동 grep뿐이어서 변형 표기와 실수를 차단하지 못한다. 이는 LLM 평문 전송 허용과 별개인 로그·커밋 산출물 금지 조건을 직접 위반한다.
  Recommendation: preview와 write_draft 직전에 전화·이메일·계좌번호를 별도의 저장소-sink 정책으로 제거하거나 탐지 시 fail-closed하라. 초안은 저장소 밖에 두거나 ignore하고, CI/pre-commit에서 직렬화 산출물을 자동 검사하라.
- [high] 전역 응답 보안 헤더 계약이 실행 코드 없이 선언됐고 기존 Kotlin 테스트는 오히려 헤더 부재를 요구한다 (contracts/easy-doc-v1.yaml:296-350)
  [실행 재현] `git diff --name-only 78fdc2e..HEAD -- backend-kotlin` 출력은 비어 있다. `rg -n 'Cache-Control|X-Content-Type-Options' backend-kotlin` 결과 `ErrorContractTest.kt:125-141`, `FrameworkErrorContractTest.kt:143-153`, `HealthContractTest.kt:43-54`가 오류·프레임워크 오류·health 응답에 두 헤더가 없어야 한다고 명시적으로 검증한다. [코드 판독] 새 계약은 오류, 204, health, CORS preflight, 미매칭 요청을 포함한 모든 응답에 헤더를 요구하지만, 328-350행은 OpenAPI 자체로 검사할 수 없고 별도 테스트가 필요함을 인정한다. 그 별도 테스트는 추가되지 않았으며 현재 실행 가능한 테스트는 새 계약과 정반대다. 문서군도 `.claude/skills/api-contract-freeze/SKILL.md:259-263`에서 DELETE 204의 헤더 부재를 유지한다고 서술해 계약과 모순된다.
  Recommendation: 같은 변경 단위에서 최외곽 필터를 구현하고 기존 부재 assertion을 반전하라. MockMvc뿐 아니라 RANDOM_PORT와 raw-socket 테스트로 204, health, CORS, 미매칭 및 컨테이너 생성 오류까지 검증한 뒤 계약을 활성화하라.
- [high] parity fixture가 제어문자 삽입 주민번호의 평문 누출을 합격시킨다 (.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py:507-525)
  [실행 재현] 현재 `compare_file()`로 `masking-known-gap-nul-rrn`을 판정한 결과 `assert_checks=['restores_input','placeholder_scheme']`, `unmasked_actual_problems=[]`, `judged=1 assertions=2`였다. 즉 Kotlin이 NUL/ZWSP가 끼어 있는 주민번호를 전혀 마스킹하지 않아도 이 fixture는 통과한다. [코드 판독] 507-525행의 known-gap 두 사례에는 `absent` assertion이 없다. 같은 커밋의 `docs/migration/_workspace/02_privacy-gate_control-char-verdict.md:10-23,188-197`은 이를 실제 개인정보 누출 결함으로 판정하고 NUL·soft-hyphen·ZWSP 및 카드번호 assertion을 요구하지만 반영되지 않았다. `.claude/skills/python-kotlin-parity/SKILL.md:158`도 이미 내려진 판정을 여전히 pending으로 기록한다. B의 `reference_divergence` 제거 자체는 시뮬레이션상 개선이었다. 제거 후 `present` 검사는 과잉 마스킹을 계속 실패시키고, assertion 밖 Python/Kotlin 차이도 ledger 오류가 되지만, 이 known-gap에는 필요한 요구사항 assertion 자체가 없다.
  Recommendation: privacy-gate 판정대로 원문 좌표를 보존하는 정규화 탐지를 구현하고, NUL·soft-hyphen·ZWSP가 삽입된 주민번호와 카드번호에 `absent` assertion을 추가하라. known-gap 상태와 SKILL의 pending 문장도 제거하라.
- [medium] 병합된 테스트가 문서별 보장을 20건 전부에서 2건으로 축소하면서 반대로 서술한다 (tests/golden/test_schema.py:157-181)
  [실행 재현] 기준 구현으로 현재 합성 문서 20건을 검사한 결과 20/20이 마스킹 항목을 가졌고 범주별 문서 수는 `전화번호=17, 이메일=11, 주민등록번호=1, 카드번호=1, 계좌번호=1`이었다. 현재 구현에서는 `docs_with_current_mask_items=2`이고 문서 003의 주민등록번호와 011의 카드번호만 남았다. [코드 판독] 병합 전 첫 테스트는 각 문서가 최소 한 범주를 포함함을 보장했고 두 번째는 전체 범주 집합을 보장했다. 병합 후 코드는 합성 문서 집합 전체가 남은 두 범주를 한 번씩 포함하는지만 검사한다. 따라서 18개 문서에서 마스킹 경로가 사라져도 통과한다. 165-167행의 '옛 검사보다 좁아지지 않는다'는 주석은 실제 보장 집합과 반대다. D의 남은 주민번호·카드번호 정규식과 mask/placeholder 순서는 기준 커밋과 동일했고, old/new 경계 입력 비교 결과는 `remaining_category_output_diffs=[]`였다.
  Recommendation: 자연 문서의 개인정보를 늘릴 필요가 없다면 별도의 20건 parametrized masking 변형 fixture로 문서별 파이프라인 보장을 유지하라. 그렇지 않으면 보장 축소를 명시하고 오해를 유발하는 주석을 삭제하라.

Next steps:
- 평문 sink 차단과 전역 헤더 구현·실행 테스트를 우선 추가한다.
- control-character masking assertion을 parity 필수 게이트로 승격한다.
- 임시 디렉터리를 사용할 수 있는 환경에서 전체 Python/Kotlin 계약 테스트를 다시 실행한다.
````

---

## 4. 정리(가공)

> **이 구획은 Claude(`codex-reviewer`)가 원문을 목록화한 것이다.** 옳고 그름 판정, 심각도 재부여, 중복 병합, 오탐 주석은 하지 않았다. 심각도 칸은 codex가 붙인 값 그대로이며, 근거 파일·라인도 codex가 쓴 값을 그대로 옮겼다(다시 세지 않았다).

### 4.1 지적 항목

| # | codex 심각도 | 지적 요지 | codex가 적은 근거 위치 | 근거 유형 (codex 자기 표시) |
|---|---|---|---|---|
| 1 | high | 골든 초안과 미리보기가 전화·이메일·계좌번호를 평문으로 저장·출력한다 | `app/easyread/collection.py:867-903` (부수: `:886-903`, `:916-928`, `:647-670`, `docs/golden-collection-plan.md:147-169`) | **실행 재현 + 코드 판독** |
| 2 | high | 전역 응답 보안 헤더 계약이 실행 코드 없이 선언됐고, 기존 Kotlin 테스트는 오히려 헤더 부재를 요구한다 | `contracts/easy-doc-v1.yaml:296-350` (부수: `ErrorContractTest.kt:125-141`, `FrameworkErrorContractTest.kt:143-153`, `HealthContractTest.kt:43-54`, `.claude/skills/api-contract-freeze/SKILL.md:259-263`) | **실행 재현 + 코드 판독** |
| 3 | high | parity fixture가 제어문자 삽입 주민번호의 평문 누출을 합격시킨다 | `.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py:507-525` (부수: `docs/migration/_workspace/02_privacy-gate_control-char-verdict.md:10-23,188-197`, `.claude/skills/python-kotlin-parity/SKILL.md:158`) | **실행 재현 + 코드 판독** |
| 4 | medium | 병합된 테스트가 문서별 보장을 20건 전부에서 2건으로 축소하면서 반대로 서술한다 | `tests/golden/test_schema.py:157-181` (주석: `:165-167`) | **실행 재현 + 코드 판독** |

### 4.2 codex가 "문제 없음/개선됨"으로 보고한 항목 (부정 결과도 그대로 기록)

원문에서 지적으로 분리되지 않고 다른 항목 본문에 포함돼 있으나, 리더가 물은 질문에 대한 codex의 답이므로 누락 없이 옮긴다.

| 질문 | codex의 답 (원문 표현) | 근거 유형 |
|---|---|---|
| B. `reference_divergence: "expected"` 3건 제거가 게이트를 약화시키는가 | "B의 `reference_divergence` 제거 자체는 시뮬레이션상 개선이었다. 제거 후 `present` 검사는 과잉 마스킹을 계속 실패시키고, assertion 밖 Python/Kotlin 차이도 ledger 오류가 된다" — 다만 "이 known-gap에는 필요한 요구사항 assertion 자체가 없다"고 이어 적음(항목 3) | 실행 재현(시뮬레이션) |
| D. 축소가 남은 2종 탐지 능력을 바꿨는가 | "D의 남은 주민번호·카드번호 정규식과 mask/placeholder 순서는 기준 커밋과 동일했고, old/new 경계 입력 비교 결과는 `remaining_category_output_diffs=[]`였다" | 실행 재현 |

### 4.3 codex가 실행해 재현한 관측값 (원문에서 발췌, 수치 무변경)

- 항목 1: `draft_source_text`·직렬화된 `source_text`에 `010-1234-5678`, `kim@example.com`, `123-456-789012` 잔존. preview stdout에 `전화 010-1234-5678 메일 kim@example.com 계좌 ...` 출력.
- 항목 2: `git diff --name-only 78fdc2e..HEAD -- backend-kotlin` 출력이 **비어 있음**.
- 항목 3: `masking-known-gap-nul-rrn` 판정 결과 `assert_checks=['restores_input','placeholder_scheme']`, `unmasked_actual_problems=[]`, `judged=1 assertions=2`.
- 항목 4: 기준 구현 20/20이 마스킹 항목 보유, 범주별 문서 수 `전화번호=17, 이메일=11, 주민등록번호=1, 카드번호=1, 계좌번호=1`. 현재 구현 `docs_with_current_mask_items=2` (문서 003 주민등록번호, 011 카드번호).

### 4.4 codex가 제시한 Next steps (원문 그대로)

1. 평문 sink 차단과 전역 헤더 구현·실행 테스트를 우선 추가한다.
2. control-character masking assertion을 parity 필수 게이트로 승격한다.
3. 임시 디렉터리를 사용할 수 있는 환경에서 전체 Python/Kotlin 계약 테스트를 다시 실행한다.

---

## 5. 미실행·실패 항목

| 항목 | 상태 | 비고 |
|---|---|---|
| codex CLI 호출 | **성공 (1회, 재시도 없음)** | 종료 코드 0. ⚠ codex 리뷰 누락 없음 |
| 전체 pytest 스위트 | **codex가 실행하지 못함** | codex 원문: "전체 pytest는 읽기 전용 환경의 임시 디렉터리 생성 실패로 실행하지 못했다". codex의 Next steps 3번과 동일 사유 |
| codex 실행 명령 중 실패 1건 | 관측됨 | stderr: `git status --short && git rev-parse HEAD && git rev-parse 78fdc2e && git diff -...` (exit 2). 이후 codex가 명령을 나눠 재실행해 동일 정보를 확보함 |
| 문항 F(문서군 무모순)의 독립 항목화 | 별도 지적으로 분리되지 않음 | codex는 문서 모순을 항목 2(`api-contract-freeze/SKILL.md:259-263`)와 항목 3(`python-kotlin-parity/SKILL.md:158`) 안에 포함해 보고했고, 그 외 F 관련 독립 지적은 원문에 없다 |
| 이전 회차 맥락 제공 | 해당 없음 | 이 scope(`02_criteria-pivot`)의 codex 리뷰는 이번이 1회차. 이전 회차 파일 없음 |
| 병렬 Claude 리뷰 참조 | **의도적 미수행** | 교차 검증 독립성 유지를 위해 `02_criteria-pivot_migration-reviewer.md`를 읽지 않았다 |

---

## 6. 다음 단계 (게이트 절차상)

이 파일은 리뷰 게이트 **1단계(병렬·독립 실행)**의 codex 측 산출물이다.

- 2단계: `02_criteria-pivot_codex-reviewer.md`와 `02_criteria-pivot_migration-reviewer.md` 두 파일 존재 확인
- 3단계: `migration-reviewer` **재호출**로 두 파일을 교차 대조해 `02_criteria-pivot_cross.md` 작성 (새 지적 생성 금지)

Phase 종료 여부와 지적의 채택·기각 판정은 이 에이전트가 내리지 않는다.
