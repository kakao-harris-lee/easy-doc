# 07_core-rebuild — 교차 종합 (2차 · 정본)

> **회차**: 2차 — 교차 종합 전용. **새 리뷰를 하지 않았고 새 지적을 만들지 않았다.**
> 종합 중 눈에 띈 것은 본문에 싣지 않고 §7 「별건 — 다음 게이트로」에 항목만 남겼다.
> **작성**: 2026-08-13 · **어간**: `07_core-rebuild`(리더 지정) · **대상**: `c11a404..f73879b` (5커밋)

---

## 1. 대조한 두 산출물 — 존재·어간 확인

| 회차 | 경로 | 존재 | 어간 | 크기 | 결론 |
|---|---|---|---|---|---|
| codex 독립 리뷰 (1차) | `docs/migration/_workspace/reviews/07_core-rebuild_codex-reviewer.md` | **있음** | `07_core-rebuild` | 22,727 B | verdict `needs-attention` · high 5 + medium 1 |
| Claude 독립 리뷰 (1차) | `docs/migration/_workspace/reviews/07_core-rebuild_migration-reviewer.md` | **있음** | `07_core-rebuild` | 42,976 B | 차단 3 · 수정 필요 4 · 판정 필요 1 · 권고 8 |
| 이 파일 (2차 정본) | `docs/migration/_workspace/reviews/07_core-rebuild_cross.md` | — | `07_core-rebuild` | — | 교차 종합 |

**어간 일치 확인**: 세 파일 모두 `07_core-rebuild`. 정본이 자기 입력 두 개와 정확히 짝지어진다.
**codex 리뷰 누락 없음** — 「codex 리뷰 없음 — 교차 대조 미수행」에 해당하지 않는다. 재요청 불필요.
**대상 범위 일치 확인**: 양측 모두 `c11a404..f73879b`, 변경 파일 37개로 같은 범위를 봤다.
codex는 `git merge-base HEAD c11a404` = `c11a404` 자신임을 확인했고, Claude는 5커밋을 열거했다. 범위 불일치 없음.

### 심각도 척도 대응에 관한 주의

두 리뷰의 척도는 **정렬돼 있지 않다.** codex는 `[high]`/`[medium]`, Claude는 `차단(①사건/②장치)` /
`수정 필요` / `권고` / `판정 필요`를 쓴다. **codex `[high]`는 `차단`의 동의어가 아니다.**
따라서 아래 표에서 심각도 차이는 **처분(무엇을 하라)이 갈릴 때만** 「충돌」로 표시했고,
눈금만 다른 경우는 양쪽 표기를 병기하고 합의로 뒀다.

---

## 2. codex 「전제 확인 필요」 3건 — 판정

codex 산출물 §4가 "이 에이전트는 독립 확인하지 않았다"고 표시해 넘긴 3건이다.
**이것은 새 지적이 아니라 기존 codex 지적의 전제 검증이므로 이 회차의 소관이다.**
아래는 이번 종합이 저장소에서 직접 확인한 결과다.

### 2.1 codex #5의 라인 범위 `backend-kotlin/build.gradle.kts:178-216`

**판정: 대응 정확. 지적의 근거 위치로 유효하다.**

| 라인 | 실제 내용 |
|---|---|
| 178 | `val declared = declaredParityDomains(declarationFile).toSet()` |
| 185-195 | `(declared - produced)` / `(produced - declared)` 문제 수집 |
| 202-211 | `if (problems.isNotEmpty()) { error(...) }` |
| **212** | `if (declared.isEmpty()) {` |
| 213-215 | `logger.lifecycle("parity 선언 0개 … 통과가 아니라 '검증 대상 없음'")` |
| 216 | `)` — `logger.lifecycle` 닫음 |

`178-216`은 `parityManifestCheck`의 `doLast` 본문 전체를 정확히 덮는다.
Claude P-1이 인용한 `:212-216`은 그 부분집합(빈 선언 분기)이다. **두 인용은 무모순이며,
codex가 태스크 전체를, Claude가 통과 원인 분기를 가리켰을 뿐이다.**
핵심 사실도 확인된다 — `declared.isEmpty()` 분기에 `error()`가 없고 `logger.lifecycle`만 있다.

### 2.2 codex #1의 "fixture가 `known_gap`으로 단언에서 제외한다"

**판정: 정확. 단 한 군데 정밀화가 필요하고, 그 정밀화는 codex 논지를 오히려 강화한다.**

`parity/fixtures/masking/masking.json` 실측:

- `known_gap` 필드는 **파일 전체에 1건**(:1420), case id `masking-known-gap-rrn-fullwidth`.
- 입력 `"번호 ９００１０１-１２３４５６７ 확인."`, `reference.items: []` (마스킹 결과 없음).
- **정밀화**: "단언에서 제외"는 **검출 방향 단언만** 제외라는 뜻이다. 이 케이스에도
  `restores_input`·`placeholder_scheme` 두 성질 단언은 그대로 걸려 있다. `absent`/`present`가 없을 뿐이다.
- **codex 논지를 뒷받침하는 실측**: fixture가 든 제외 사유가 문자 그대로 이렇다 —
  *"지금 그것을 단언하면 **Kotlin에 Python보다 넓은 구현을 요구하게 되므로** 어느 방향도 단언하지 않는다"*.
  이 사유는 **2026-08-12 재개발 전환(Python은 정답이 아니다)으로 효력을 잃었다.**
  즉 codex의 *"Python과 다르게 만들지 않겠다는 이유는 요구사항 기준이라는 판정 원칙과 정면 충돌한다"*는
  **fixture 원문 자신이 뒷받침한다.**
- **동시에 Claude 라우팅과 어긋나는 문장도 같은 case에 있다** — fixture는
  *"이 건은 `privacy-gate` 판정 §5.4의 범위 **밖**이다(별개 사안으로 명시)"*라고 적었는데,
  Claude P-2는 이 건을 `privacy-gate` 판정으로 올렸다. → §3 충돌 2에서 다룬다.

### 2.3 codex #4 — "이번 커밋 범위의 결함"인가 "아직 도래하지 않은 미구현"인가

**판정: 「예정된 미구현」이 맞다. 이번 커밋의 결함이 아니다.
단 CNV-01·CNV-04는 Phase 2 게이트 항목이므로, "미도래"가 "게이트 밖"을 뜻하지는 않는다.**

근거 넷:

**(a) `application` 모듈은 이미 빌드 그래프에 있고 비어 있다.**
`backend-kotlin/settings.gradle.kts:8` — `include("core", "application", "infrastructure", "api", "worker")`.
`backend-kotlin/application/` 에는 `build.gradle.kts`·`README.md`·lockfile만 있고 **`src/**` 산출이 0**이다
(나머지는 전부 `build/` 산출물). 즉 다음 조각이 채울 자리가 **선언된 채 비어 있는 상태**이며,
codex의 "application 변환 서비스가 없다"는 서술은 사실이다.

**(b) 어댑터는 절단 정보를 버리지 않는다 — codex 근거 줄의 함의만 조정된다.**
codex는 근거 위치로 `AnthropicProvider.kt:253-264`를 들며 "`max_tokens` 응답도 정상 `LlmCompletion`으로
반환"한다고 적었다. 사실이다. 그러나 실측하면:

- `LlmCompletion.kt:17-22` — `enum class LlmFinishReason { END_TURN, MAX_TOKENS, ... }`,
  `MAX_TOKENS`의 KDoc이 *"출력 상한에 걸려 잘렸다. **재시도·분할 판단은 변환 서비스 몫이다.**"*
- `AnthropicProvider.kt:253-264` — `parse()`가 `finishReason`을 계산해 `LlmCompletion`에 그대로 싣는다.

절단은 **1급 enum 값으로 노출되고 위임이 KDoc에 명시**돼 있다. 따라서 이 줄은 **이번 커밋의 결함이
아니라 문서화된 위임**이다. Claude T-2의 *"절단·빈 결과·거절은 provider가 드러냄"*과 모순되지 않는다 —
두 리뷰는 같은 코드를 각각 "정보가 나온다"(Claude)와 "승격하는 자가 없다"(codex)로 봤고 **둘 다 참**이다.

**(c) 그러나 게이트 밖은 아니다.**
CNV-01(호출 최대 2회)·CNV-04(보정 실패·악화 시 원본 채택)는
`docs/migration/_workspace/00_requirements-inventory.md:142`의 **Phase 2 게이트 항목**이다.
따라서 마감은 **「application 조각과 동시」**이고, 그 전에는 **Phase 2가 닫히지 않는다.**
양측 결론(codex `needs-attention`/NO-SHIP, Claude "Phase 2 종료 불가")은 이 점에서 일치한다.

**(d) codex가 든 잔여 위험은 현시점 사건 경로가 아니다.**
*"부분 응답 저장이나 향후 worker 재실행에 따른 상한 초과"* — 현재 provider를 호출하는 프로덕션 경로가
없다(worker 테스트 3건, 변환 유스케이스 0). 사건이 아니라 **application 조각 착수 시 함께 닫아야 할
요구사항**으로 넘긴다.

**심각도 제안(리더 확정 몫)**: codex `[high]` / Claude `수정 필요`. 처분은 양측 동일(구현하라)이므로
충돌이 아니다. **차단(②장치)으로는 올리지 않기를 제안한다** — 상한을 강제한다고 **선언한** 장치가
아직 없으므로 "무력화된 게이트"가 성립하지 않는다. 이는 제안이며, 최종 판정은 리더 몫이다.

---

## 3. 교차 대조표

각 행은 지적 하나다. 같은 대상을 다른 언어로 가리킨 지적은 한 행으로 병합하고 양쪽 근거를 병기했다.
`상태` 열의 **굵은 표기**가 이 종합의 핵심 산출이다 — 합의보다 **차이**가 값진 정보다.

| # | 항목 | 근거 위치 | 축 | Claude | codex | 상태 | 심각도 (Claude / codex) | 마감 | 관련 종료 조건 |
|---|---|---|---|---|---|---|---|---|---|
| X-1 | masking 도메인이 canonical fixture와 어디서도 대조되지 않는다 (선언 0 · 산출 0 · CI `exit 0`) | `build.gradle.kts:178-216`(codex) / `:212-216`+`ci.yml:319-325`+`parity-domains.txt`(Claude) | 도달 범위(parity) | **P-1 지적함** | **#5 지적함** | **합의** | 차단(②장치) / high | Phase 2 종료 전 | §6 parity 게이트 · INV-02 |
| X-2 | 전각 주민등록번호가 마스킹을 통과한다 (성별코드만 ASCII `[1-8]`) | `Masking.kt:155`(Claude 실측) / `:153-157`(codex) | parity · 보안 불변식 | **P-2 지적함** | **#1 지적함** | **합의(사실) / 충돌(처분)** | 수정 필요 / high | Phase 2 종료 전 | INV-02 |
| X-3 | 호출 상한 2회 · 4대 예외 · 보정 악화 시 원본 채택 파이프라인 부재 | `AnthropicProvider.kt:253-264`(codex) / `00_requirements-inventory.md:142`(Claude) | 계약 · 테스트 | **T-2 지적함** | **#4 지적함** | **합의** | 수정 필요 / high | application 조각과 동시 | Phase 2 게이트 CNV-01·CNV-04 |
| X-4 | `CoreModuleBoundaryTest`가 선언한 모듈 경계를 강제하지 못한다 | `CoreModuleBoundaryTest.kt:23-53`(codex) / `:18-20`·`:25-42`·`:44`(Claude) | 도달 범위(관용성) | **K-1 지적함** | **#6 지적함** | **합의** (양측이 서로 다른 증거를 보탬) | 권고 / medium | Phase 5 (Claude 제시) | §3.2 모듈 경계 |
| X-5 | `ModelDraft`·`ReviewedBody` 공개 생성자가 마스킹·복원 불변식을 우회시킨다 | `Masking.kt:447-464` | 보안 불변식 | 안 함 (S-1은 `MaskedText`만 다룸) | **#2 지적함** | **codex 단독 + 부분 충돌** | — / high | application 조각과 동시 | INV-01 |
| X-6 | `PlaceholderRestoration`이 data class라 복원된 원문이 기본 `toString`으로 노출된다 | `Masking.kt:119-125` | 보안 불변식 | 안 함 (S-5는 `LlmPrompt`·`LlmCompletion` 2종만 검사) | **#3 지적함** | **codex 단독** | — / high | 즉시(제안) | INV-01 로그 금지 |
| X-7 | `MaskedText` 생성 통로에 상시 탐지기가 없다 — 그 회귀는 `1ffaf93`에서 이미 발생했다 | `LlmPromptTest.kt:24-39`(대칭 사례) · 저장소 전수 | 보안 불변식 · 도달 범위 | **S-1 지적함** | 안 함 | **Claude 단독** | 차단(②장치) / — | Phase 2 종료 전 | INV-01 |
| X-8 | `scan_privacy_invariants.py`가 `backend-kotlin`을 스캔 범위로 선언하나 CI 도달 0 | `ci.yml` 전 580줄 · `detekt.yml` 73줄 | 도달 범위(보안) | **S-2 지적함** | 안 함 | **Claude 단독** | 차단(②장치) / — | Phase 3 | §6 보안 게이트 |
| X-9 | 큐레이션 스냅샷 2종의 생성기가 미커밋 — 저장소 안에서 출처 검증 불가 | `python-prompt-snapshot.json` · `python-style-rules-snapshot.json` | 테스트 적정성 · 도달 범위 | **T-1 지적함** (외부 실측으로 전건 일치 확인 첨부) | 안 함 (focus로 물었으나 무지적 — codex §4 자기 기록) | **Claude 단독** | 수정 필요 / — | **`app/**` 삭제 전** | P1-4 · STY-01·02 |
| X-10 | `ambiguous`가 계약 409 조건에 대응되지 않아 자리표시자가 박힌 문서가 200으로 나간다 | `Masking.kt:497-500`·`:540` / `contracts:1044-1049`·`:1097-1105` | 계약 준수 | **C-1 지적함** | 안 함 | **Claude 단독** | 수정 필요 / — | Phase 4 (등록은 즉시) | §2.2 내보내기 계약 |
| X-11 | `baseUrl`이 설정으로 열려 있고 응답 본문 크기 상한이 없다 | `AnthropicProvider.kt:128`·`:206-207` | 보안 불변식 | **S-4 지적함** | 안 함 | **Claude 단독** | 판정 필요 / — | Phase 5 | §2.3 |
| X-12 | 계약이 못박은 자리표시자 패턴을 실행 검사가 계약 파일에서 읽지 않는다 | `MaskingTest.kt:187-188` / `contracts:1680`·`:1740` | 계약 준수 · 도달 범위 | **C-2 지적함** | 안 함 | **Claude 단독** | 권고 / — | Phase 3 | §6 Contract 게이트 |
| X-13 | 탈출 표기가 담기는 `masked_text` 채널이 계약에 필드로 없다 | `contracts` 전수 0회 | 계약 준수 | **C-3 지적함** | 안 함 | **Claude 단독** | 권고 / — | Phase 4 | §2.2 |
| X-14 | "탈출 해제는 검수 여부와 무관"이 중첩 케이스에서 성립하지 않는다 | `Masking.kt:213`·`:507-509` / `MaskingTest.kt:215`·`:428` | parity 위험 | **P-3 지적함** | 안 함 | **Claude 단독** | 권고 / — | Phase 4 | §4.5 |
| X-15 | 프롬프트 스냅샷 테스트 안에 Kotlin↔Python 마스킹 값 동일성 단언이 숨어 있다 | `PromptTextSnapshotTest.kt:77-83` | 테스트 적정성 · parity | **P-4 지적함** | 안 함 | **Claude 단독** | 권고 / — | Phase 2 종료 전 | 재개발 판정 기준 |
| X-16 | "키가 새지 않는 다섯 겹" 중 cause 미부착만 단언이 없다 | `DomainExceptions.kt:19` | 보안 불변식 | **S-3 지적함** | 안 함 | **Claude 단독** | 권고 / — | — | §2.3 |
| X-17 | `core/build.gradle.kts:6`이 존재하지 않는 테스트 클래스를 가리킨다 | `core/build.gradle.kts:6` | 관용성 | **K-2 지적함** | 안 함 | **Claude 단독** | 권고 / — | — | — |
| X-18 | `00_progress.md` 원장이 실물과 어긋난다 (5커밋 미반영 · fixture 22↔31 · `parity/` 부재 서술 자기모순) | `00_progress.md:342-350`·`:366` | 테스트 적정성 · 도달 범위 | **T-3 지적함** | 안 함 | **Claude 단독** | 권고 / — | Phase 2 종료 전 | §6 추적표 |
| X-19 | 스타일 규칙이 `StyleRules.kt` 한 곳에 정의되고 `Prompts.kt`가 중복 정의하지 않는가 | `StyleRules.kt` · `Prompts.kt` | 계약 준수 | **검토함 — 지적 없음** (STY-01 충족 후보) | **검토함 — 지적 없음** (codex §4가 "두 파일 모두 읽었으나 지적 없음"으로 명시) | **합의(무지적)** | — | — | STY-01 |
| X-20 | 벤더 SDK를 core·서비스 코드에서 직접 import하지 않는가 | 전수 grep (양측) | 관용성 | **검토함 — 지적 없음** (K-3) | **검토함 — 지적 없음** (codex §4가 전용 grep 실행·무지적으로 명시) | **합의(무지적)** | — | — | CLAUDE.md 규칙 1 |
| X-21 | `AnthropicProvider` 실패 경로 · `StubAnthropicServer` 요청 본문·헤더 단언 | `AnthropicProviderResponseTest.kt:127-182` · `RequestTest.kt:159-173` | 테스트 적정성 | **검토함 — 지적 없음** (S-5, 실행 단언으로 확인) | **미답** (focus로 명시적으로 물었으나 출력에 지적·무지적 어느 쪽도 없음 — codex §4 자기 기록) | **한쪽만 검토** | — / — | — | §6 |

### 표 읽는 법 — 이 회차가 실제로 얻은 것

- **합의 4건**(X-1~X-4). 그중 **X-1은 두 모델이 독립적으로 같은 자리에 도달했다** — 「도달 0을 특히
  의심한다」가 서로 다른 계열의 모델에서 동시에 걸린 것이므로, 이 항목은 교차 대조가 **가장 강하게
  지지하는** 지적이다.
- **codex 단독 2건**(X-5·X-6). 둘 다 **Claude가 "검토함 — 지적 없음"으로 통과시킨 영역 안**에 있다
  (S-1은 `MaskedText`만, S-5는 `toString` 형제 2종만 봤다). **교차 리뷰를 붙인 값이 정확히 여기서 나왔다.**
- **Claude 단독 12건**(X-7~X-18). 그중 X-7·X-8은 차단(②장치)이고, X-9는 **되돌릴 수 없는 마감**
  (`app/**` 삭제 전)을 가진 유일한 항목이다.
- **무지적 합의 2건**(X-19·X-20)은 "아무도 안 봤다"가 아니라 **양쪽이 보고 통과시켰다**는 정보다.
- **X-21은 한쪽만 봤다** — codex가 focus로 지시받고 조사까지 했으나 지적·무지적 어느 쪽도 출력하지
  않았다. 단독 행에 왜 한쪽만 봤는지 짐작을 적지 않는다는 규약에 따라 **사실만 남긴다.**

---

## 4. 충돌 항목 — 어느 쪽도 삭제하지 않는다 (리더 판단 요청)

### 충돌 1 — 「검수 없는 복원 경로」: 고쳐졌는가, 위조 가능한가 (X-5)

**Claude 측 근거 (1차 §4 「이번 범위가 실제로 전진시킨 것」 ⑵, 원문)**

> Python에 있던 두 결함이 옮겨지지 않고 **고쳐졌다** — … ⑵ 검수 없는 본문에 개인정보를 복원하는
> 경로(`edited_text ?? easy_text` 무조건 복원). 둘 다 재개발 판정 기준("Python이 틀린 경우를
> 전제한다")의 올바른 적용이다.

**codex 측 근거 (#2, 원문)**

> `ModelDraft`와 `ReviewedBody` 생성자가 모두 공개다. 호출자는 `ModelDraft(사용자 원문)`을
> `LlmPrompt.forRepair`에 넘겨 미마스킹 주민번호를 provider로 전송할 수 있고, LLM 응답을
> `ReviewedBody`로 감싸 `restoreForExport`에 넘겨 모델이 고른 위치에 원문 개인정보를 복원할 수도 있다.
> 타입 이름은 provenance를 표현할 뿐 발급 주체를 강제하지 않는다.

**제3의 근거 — 이번 종합이 저장소에서 실측한 것 (어느 쪽 지적도 지우지 않고 근거로만 추가한다)**

1. `Masking.kt` — `@JvmInline value class ModelDraft(val value: String)` 및
   `@JvmInline value class ReviewedBody(val value: String)`. **둘 다 public 생성자·public 프로퍼티.**
   codex의 사실 주장은 **정확하다.**
2. `LlmPrompt.kt:85-86` — `fun forRepair(converted: ModelDraft, ...)`. `forConversion`은
   `maskedText: MaskedText`를 요구하지만 `forRepair`는 `ModelDraft`만 요구한다. **경로는 실재한다.**
3. **그런데 저자가 이미 문서화해 두었다.** `LlmPrompt.kt:31-34` KDoc 원문 —
   *"**[forRepair]는 [forConversion]보다 약하다.** [ModelDraft]는 생성자가 열려 있어 `ModelDraft(원문)`이
   컴파일된다(`Prompts.kt::buildRepairPrompt` KDoc에 이미 적힌 …). … [MaskedText]를 요구할 수 없다 —
   이미 자리표시자가 박힌 변환문을 다시 마스킹하면 …"*
   `Masking.kt` `ReviewedBody` KDoc 원문 — *"이 타입으로 감싸는 행위가 곧 '사람 검수를 거쳤다'는
   선언이다. 초안을 여기 감싸 넣으면 **통제가 무너진다** — 위 절의 '못 잡는 것' 참고."*
4. 커밋 `8412b89`의 제목이 **"단발 위조 차단"** 이다. 저자가 범위를 '단발'로 한정해 적었다.

**종합 — 두 진술은 서로 다른 대상을 가리키며 둘 다 참일 수 있다.**
기본·사고 경로(인자 순서를 뒤집어 넣는 실수, `edited_text ?? easy_text` 무조건 복원)는 **닫혔다**(Claude).
고의·명시 경로(`ReviewedBody(모델응답)`을 손으로 써넣기)는 **열려 있다**(codex).
남는 것은 사실 판정이 아니라 **"문서화된 한계를 수용할 것인가"라는 처분 결정**이다.

**리더 판단 요청 사항**
- (a) 문서화된 우회 경로를 수용 가능한 잔여 위험으로 볼 것인가, 타입으로 닫을 것인가.
- (b) 닫는다면 `forRepair`의 입력을 어떻게 정의할 것인가 — 저자가 적은 기술적 제약
  ("이미 자리표시자가 박힌 변환문을 다시 마스킹할 수 없다")은 실재하므로, 단순히 `MaskedText`를
  요구하는 방식으로는 닫히지 않는다.
- (c) 보안 축 판정이 갈리면 `privacy-gate` 판정이 우선한다(에이전트 규약). **`privacy-gate` 회부 필요.**

### 충돌 2 — 전각 주민등록번호의 처분 (X-2)

사실 자체는 **합의**다. 양측이 독립적으로 같은 코드·같은 실측 결과에 도달했다
(Claude는 JDK 21 단일 파일 실행으로 `match=false`를 실측, codex는 패턴 독해로 도달).
갈리는 것은 **처분**이다.

**Claude 측 근거 (P-2, 원문 요지)**

> 최소한 KDoc을 사실에 맞추고, 전각 RRN을 가릴지 말지는 **policy 결정**이므로 `privacy-gate` 판정으로
> 올린다(패턴을 넓히는 것은 마스킹 범주 확대가 아니라 표기 확대이므로 정책 위반은 아니지만,
> fixture가 일부러 방향을 비워 둔 자리다).

**codex 측 근거 (#1, 원문)**

> 실제 fixture도 이 요구사항 위반을 `known_gap`으로 인정하면서 단언에서 제외한다. **Python과 다르게
> 만들지 않겠다는 이유는 요구사항 기준이라는 이번 판정 원칙과 정면으로 충돌한다.**
> Recommendation: 성별코드도 Unicode 숫자의 값이 1~8인지 판정하도록 구현하고, 전각 주민번호가 실제
> Anthropic 와이어에 나타나지 않는 독립 보안 회귀 테스트를 추가한다.

**제3의 근거 — fixture 원문 실측 (§2.2 재인용)**

- fixture의 제외 사유: *"지금 그것을 단언하면 **Kotlin에 Python보다 넓은 구현을 요구하게 되므로**
  어느 방향도 단언하지 않는다"* → **이 사유는 2026-08-12 재개발 전환으로 효력을 잃었다.**
  codex 쪽 논지를 fixture 자신이 뒷받침한다.
- 동시에 fixture는 *"이 건은 `privacy-gate` 판정 §5.4의 범위 **밖**이다(별개 사안으로 명시)"* 라고 적어
  **Claude의 `privacy-gate` 라우팅과 어긋난다.**

**리더 판단 요청 사항**
- (a) **방향**: 전각 RRN을 검출하도록 구현을 넓힐 것인가. (표기 확대이지 범주 확대가 아니라는
  Claude의 정리와, 요구사항 기준상 이미 위반이라는 codex의 정리를 함께 놓고 판단할 것.)
- (b) **소관**: 그 판정을 누가 하는가. Claude는 `privacy-gate`로 올렸고, fixture는 스스로
  `privacy-gate` 범위 밖이라고 적었다. **소관이 정해지지 않으면 이 항목은 어느 레인에서도 처리되지
  않는다** — X-10(C-1)이 "커밋 메시지 안에서만 인계되고 아무도 받지 않았다"고 지적한 것과 같은 형태의
  실패 위험이 있다.
- (c) 어느 방향으로 정하든 **KDoc 수정은 독립적으로 필요하다** — `UnicodeRegex.kt:17-20`이 제공하지
  않는 보호를 약속하고 있고, 이 부분은 양측 어디도 반대하지 않는다.

### 충돌로 보지 않은 것 (명시)

- **X-3의 심각도 차이**(codex `[high]` vs Claude `수정 필요`): **처분이 동일하다**(구현하라, Phase 2를
  막는다). 눈금 차이일 뿐이므로 충돌로 표시하지 않았다. §2.3 참조.
- **X-4의 상반돼 보이는 서술**: Claude K-1은 *"`api`로 선언한 의존성은 `Class.forName`이 찾아낸다 —
  즉 잡힌다. 실제로 못 잡는 것은 `compileOnly`"*라고 적었고, codex #6도 *"`compileOnly` 의존성과 core
  main 참조는 런타임에 클래스가 없어 그대로 통과"*라고 적었다. **두 진술은 일치한다.**
  Claude는 여기에 "테스트가 적어 둔 한계 문장 자체가 틀렸다"를, codex는 "CI가 이 파일을 지목하지 않아
  제거해도 빌드가 성공한다"를 각각 보탰다. 서로 다른 증거를 보탠 **합의**다.
- **X-6이 Claude S-5와 모순되는가**: 아니다. S-5는 `LlmPrompt.toString`·`LlmCompletion.toString`
  **두 타입만** 실행 단언으로 확인했고 `PlaceholderRestoration`에 대해서는 아무 주장도 하지 않았다.
  모순이 아니라 **미검토 영역**이므로 codex 단독으로 분류했다.

---

## 5. 종합 기준 Phase 2 종료 조건 대비 현황

기준: `docs/migration/_workspace/00_requirements-inventory.md:142`
(`Phase 2 게이트 = INV-01·02, CNV-01·02·04, STY-01·02, DOC-05·06`) + §5 Phase 2 종료 조건.

| 게이트 항목 | 종합 판정 | 근거 (Claude / codex) | 교차로 달라진 점 |
|---|---|---|---|
| INV-01 마스킹 선행 타입 차단 | **미충족** | X-7 탐지기 결손(Claude) + X-5 provenance 공개 생성자(codex) + X-6 복원 결과 `toString` 노출(codex) | **codex가 결손 2건을 추가했다** — 1차 단독 판정보다 근거가 늘었다 |
| INV-02 마스킹 범주 2종 | **미충족** | X-1 fixture 대조 도달 0(양측) + X-2 전각 RRN(양측) + X-12 계약 대조 미참조(Claude) | 양측 독립 합의 |
| CNV-01 LLM 호출 최대 2회 | **미충족** | X-3 (양측) | 합의. §2.3에서 "예정된 미구현"으로 판정했으나 게이트는 여전히 열려 있다 |
| CNV-02 4대 예외 검출 | **부분** | Claude T-2(보정 악화 미구현) / codex #4(같은 취지) | 합의 |
| CNV-04 보정 악화 시 원본 채택 | **미충족** | X-3 (양측) | 합의 |
| STY-01 스타일 규칙 SSOT | **충족 후보** | X-19 **양측 무지적** + Claude 스냅샷 대조 | **교차로 강화됨** — codex가 같은 두 파일을 읽고 중복 정의를 찾지 못했다. 단 X-9(생성기) 해소 필요 |
| STY-02 사전 246 이전 | **충족 후보** | Claude 독립 실측 전건 일치 / codex 무지적 | 단 X-9 해소 필요 |
| DOC-05·06 | **미검토** | 양측 범위 밖 (사용자 지시로 TEXT 우선) | — |
| **종료 조건**: 외부 API·DB 없이 도는 parity suite가 양쪽에서 같은 결과 | **미충족** | X-1 — **parity 판정 건수 0** (양측) | 양측 독립 합의. 이 회차에서 가장 강하게 지지되는 항목 |

### → **Phase 2 종료 불가. 두 독립 리뷰가 교차 대조 후에도 뒤집히지 않는다.**

codex verdict `needs-attention` + `NO-SHIP`, Claude "Phase 2 종료 불가" — **결론이 일치한다.**
교차 대조로 **완화된 항목은 하나도 없고**, INV-01은 codex 지적 2건이 붙어 **오히려 무거워졌다.**

### Phase 2 게이트 해제 최소 조건 (종합)

| # | 조건 | 출처 | 상태 |
|---|---|---|---|
| a | `masking` 도메인 선언 + Kotlin parity 생산자 배선 (fixture 31건이 실제로 돌 것) | **합의** X-1 | 미해결 |
| b | `MaskedText` 생성 통로 상시 탐지기 | Claude 단독 X-7 | 미해결 |
| c | CNV-01·CNV-04 구현 | **합의** X-3 | 미해결 (application 조각) |
| d | 전각 RRN 처분 결정 | **충돌 2** | **리더 판정 대기** |
| e | provenance 래퍼 처분 결정 | **충돌 1** | **리더 판정 대기** |
| f | `PlaceholderRestoration` 본문 노출 차단 | codex 단독 X-6 | 미해결 |

---

## 6. 심각도 순 조치 목록 — 담당·마감 제안

**심각도와 「착수를 차단하는가」는 별개 축이다.** 마감은 「그 게이트가 처음 실제로 쓰이는 시점」이며,
**착수 차단 여부의 최종 판정은 리더 몫**이다. 아래 마감은 제안이다.

| 순위 | 항목 | 심각도 (종합) | 담당 | 마감 제안 | 비고 |
|---|---|---|---|---|---|
| 1 | X-1 masking parity 배선 (선언 + Kotlin 생산자) | **차단(②장치)** — 양측 최고 심각도 합의 | `parity-verifier` 주 · `kotlin-implementer` 보조 | **Phase 2 종료 전** | `00_progress.md:350`에 "선언 masking + 산출물 정상 → exit 0" 실측이 이미 있다 — **켤 수 있음이 측정돼 있고 남은 것은 Kotlin 생산자 한 조각**(Claude P-1) |
| 2 | X-7 `MaskedText` 상시 탐지기 | **차단(②장치)** | `kotlin-implementer` | **Phase 2 종료 전** | `LlmPromptTest.kt:24-39`를 그대로 본뜨면 닫힌다. 이 자리의 회귀는 `1ffaf93`에서 **이미 한 번 일어났다** |
| 3 | X-8 `scan_privacy_invariants.py` CI 배선 | **차단(②장치)** | `privacy-gate` 판정 · 리더(CI 배선 승인) | **Phase 3** (Claude 제시) | 이번 범위가 처음으로 **물리게** 만들었다 — 본문·개인정보를 다루는 파일 12개 유입 + `Secret.reveal()` 공개 통로. 마감을 당길지는 리더 판정 |
| 4 | **충돌 1** X-5 provenance 공개 생성자 | **판정 필요 → 리더** (codex high) | **리더 판정** → `privacy-gate` → `kotlin-implementer` | **application 조각과 동시** | 위험이 실제로 물리는 시점이 application이 이 타입들을 생성하기 시작할 때다 |
| 5 | **충돌 2** X-2 전각 RRN 처분 | **판정 필요 → 리더** (codex high / Claude 수정 필요) | **리더 판정**(소관 포함) → `kotlin-implementer` | **Phase 2 종료 전** | 방향과 무관하게 `UnicodeRegex.kt:17-20` KDoc 수정은 **즉시** 가능·필요 |
| 6 | X-6 `PlaceholderRestoration` 본문 노출 | codex high (Claude 미검토) | `kotlin-implementer` | **즉시** | 한 줄 수정(redacted `toString` 또는 일반 class). 형제 타입 2종이 이미 같은 처리를 받고 있어 비대칭만 해소하면 된다 |
| 7 | X-3 CNV-01·CNV-04 구현 | **수정 필요** (codex high) — §2.3 판정 반영 | `kotlin-implementer` | **application 조각과 동시** | 「예정된 미구현」으로 판정했으나 **Phase 2 게이트 항목**이므로 그 전에 Phase 2는 닫히지 않는다 |
| 8 | X-9 스냅샷 생성기 커밋 + CI 재생성 diff | **수정 필요** | `kotlin-implementer` · `parity-verifier` | **`app/**` 삭제 전 — 이 창은 영구히 닫힌다** | 유일하게 **되돌릴 수 없는** 마감. 데이터 자체는 Claude 독립 실측으로 전건 일치가 확인됐으므로 남은 것은 재현 경로다 |
| 9 | X-10 `ambiguous` 계약 항목 정식 등록 | **수정 필요** | `contract-keeper` | **등록 즉시 / 해결 Phase 4** | 지금 아무 레인도 이 건을 받지 않았다 — 커밋 메시지 안에서만 인계됐다 |
| 10 | X-11 `baseUrl` 노출 + 응답 크기 상한 | **판정 필요** | `privacy-gate` | Phase 5 | 심각도를 낮추지 않고 판정으로 올린 Claude의 처리를 유지 |
| 11 | X-4 모듈 경계 검사 강화 | 권고 / medium | `kotlin-implementer` | Phase 5 (Claude 제시) — **리더 판정** | codex가 보탠 "파일을 제거해도 빌드 성공"은 마감을 당길 근거가 될 수 있다. 허용목록형 전환 + `compileOnly` 동시 확인 |
| 12 | X-12 계약 파일 직접 참조 | 권고 | `contract-keeper` · `kotlin-implementer` | Phase 3 | |
| 13 | X-15 스냅샷 내 값 동일성 단언 표기 | 권고 | `parity-verifier` | Phase 2 종료 전 | 개선을 회귀로 잡을 수 있는 자리 |
| 14 | X-18 `00_progress.md` 원장 갱신 | 권고 | 리더 | Phase 2 종료 전 | 리더가 Phase 판정에 쓰는 표가 실물과 다르다 |
| 15 | X-13 `masked_text` 채널 계약화 | 권고 | `contract-keeper` | Phase 4 | |
| 16 | X-14 중첩 탈출 해제 | 권고 | `kotlin-implementer` | Phase 4 | |
| 17 | X-16 cause 미부착 단언 | 권고 | `kotlin-implementer` | — | 한 줄 |
| 18 | X-17 `core/build.gradle.kts:6` 주석 수정 | 권고 | `kotlin-implementer` | — | 한 줄 |

**요약**: 차단(②장치) 3 · 리더 판정 대기 2(충돌) · codex 단독 즉시 1 · 수정 필요 3 · 판정 필요 1 · 권고 8.
**세 차단은 모두 ②장치**(사건이 아니라 그 사건을 탐지·차단할 게이트의 무력화)이고, 셋 다 같은 형태다 —
**만들어 둔 장치가 있는데 그것을 실행하는 배선이 없다.**

---

## 7. 대조하지 못한 범위 · 「종합 중 발견 — 미교차」

### 7.1 codex 리뷰 부재·실패로 대조하지 못한 범위

**없음.** codex 산출물이 실재하고 종료 코드 0·verdict 정상·출력 잘림 없음으로 완주했다
(codex §5 「미실행·실패 항목: 없음」). **재요청 불필요.**

### 7.2 한쪽만 보아 교차 확인이 성립하지 않은 범위

| 범위 | 상태 |
|---|---|
| `AnthropicProvider` 실패 경로(타임아웃·429·5xx·잘린 스트림·잘못된 JSON) · `StubAnthropicServer` 요청 단언 | Claude만 검토(S-5, 무지적). codex는 focus로 지시받았으나 지적·무지적 어느 쪽도 출력하지 않았다 (X-21) |
| 마스킹 음성 케이스 중 전각 외(잘못된 체크섬·경계 인접 숫자·겹치는 매치·초장문) | Claude만 검토(S-5, 무지적). codex 무답 |
| 스냅샷 기준의 독립성 | Claude만 지적(X-9). codex는 같은 파일을 조사했으나 무지적 |
| 계약 준수 축 전반(X-10·X-12·X-13) | Claude만 검토. codex focus에 계약 축이 있었으나 자리표시자·`ambiguous`·`masked_text` 관련 출력 없음 |

### 7.3 양측 모두 수행하지 않아 교차 대조 대상이 아닌 것

- `user_prompts`·`repair_prompts` 스냅샷의 Python 대조 (Claude 1차 §5에 미실행 사유 기록)
- `privacy-gate` 신규 감사 산출물 · `parity-verifier` 리포트 · contract test — **이번 범위에 대해 존재하지 않는다**
- 골든셋 통과율 영향 (Kotlin 평가 경로 미존재)
- DOC-05·06 문서 추출·내보내기 (범위 밖)
- 트랜잭션 경계·Flyway·`JdbcClient`·Testcontainers — **대상 코드 없음**(미검토가 아니다)
- 범용 품질 축(성능·유지보수성): 양측 범위 밖. Claude가 `checkStyle`의 문장당 사전 246 순회 + 정규식
  123개 비용을 참고로 남겼으나 **판정은 `multi-review` 몫**이다 → 리더에게 별도 실행 권고

### 7.4 「종합 중 발견 — 미교차」

**본문에 싣지 않는다.** 아래는 교차 검증을 받지 않았으므로 정본의 지적이 아니며,
**다음 리뷰 회차의 범위로 리더에게 제안**한다.

1. **fixture `masking-known-gap-rrn-fullwidth`의 제외 사유 문장이 실효했다.**
   *"Kotlin에 Python보다 넓은 구현을 요구하게 되므로"*는 2026-08-12 재개발 전환(Python은 정답이 아니다)
   이후 성립하지 않는다. 충돌 2를 **어느 방향으로 결정하든 이 문구 자체는 갱신 대상**이다.
   (충돌 2의 판정 근거로는 §4에서 썼으나, "fixture 문구를 고쳐라"는 **양측 리뷰 어디에도 없는 지적**이다.)
2. **충돌 2의 소관이 문서 간에 어긋나 있다.** fixture는 이 건을 `privacy-gate` §5.4 **범위 밖**이라고
   적었고 Claude P-2는 `privacy-gate`로 라우팅했다. 소관 확정 자체가 별도 항목이다.
3. **`application` 모듈이 `settings.gradle.kts:8`에 include된 채 `src/**`가 0이다.**
   "선언은 있는데 산출이 없는" 상태가 빌드 그래프에 존재하는데, `parityManifestCheck`가 parity 도메인에
   대해 강제하는 종류의 대조가 **모듈 수준에는 없다.** 결함이라 단정하지 않는다 — 다음 조각이 채울
   자리이기 때문이다. 다만 「선언 범위 대 실제 도달」 축의 점검 대상으로 다음 회차에 올릴 것을 제안한다.

---

## 8. 리더에게

- **정본은 이 파일이다.** 1차 산출물 두 개만으로 Phase 종료 조건을 보고하지 않았다.
- **Phase 2 종료 불가** — 두 독립 리뷰가 교차 대조 후에도 같은 결론이고, 완화된 항목은 없다.
- **먼저 답이 필요한 것은 충돌 2건**(§4)이다. 둘 다 사실 판정이 아니라 **처분 결정**이라
  리뷰 레인에서 닫을 수 없다. 특히 충돌 2는 **소관 자체가 미정**이라 방치하면 X-10과 같은 형태
  ("인계는 적혔는데 아무도 받지 않음")로 사라진다.
- **레인 배분**: `parity-verifier` ← X-1·X-15 / `kotlin-implementer` ← X-2(방향 결정 후)·X-6·X-7·X-3·X-9·X-4·X-14·X-16·X-17 /
  `privacy-gate` ← X-5(충돌 1)·X-8·X-11 / `contract-keeper` ← X-10(정식 등록)·X-12·X-13 / 리더 ← X-18 원장 갱신.
- **다음 회차 focus 제안**: ⑴ 차단 3건의 배선이 **실행 로그로** 도는지, ⑵ application 조각의
  "최대 2회"가 메트릭에서 확인 가능한 형태인지, ⑶ §7.2의 한쪽만 본 범위(특히 계약 축)를 codex focus에
  명시적으로 다시 넣을 것, ⑷ 범용 품질 축이 필요하면 `multi-review` 별도 실행.
