# 07_core-rebuild — migration-reviewer 1차(독립) 리뷰

> **회차**: 1차 — Claude 단독 독립 리뷰. codex 산출물을 읽지 않았고 교차 대조도 하지 않았다.
> 이 회차에서 codex 부재는 정상이며 실패가 아니다. 교차 종합은 2차 재호출에서
> `07_core-rebuild_cross.md` 로 낸다. **이 파일만으로 Phase 종료 조건 충족을 보고하지 않는다.**
>
> **작성**: 2026-08-13 · **어간**: `07_core-rebuild`(리더 지정) · **대상**: `c11a404..f73879b` (5커밋)

---

## 1. 리뷰 범위와 참조한 정본

### 1.1 대상 커밋

| 커밋 | 제목 |
|---|---|
| `0c377a5` | 텍스트 변환 순수 도메인 — 쉬운말 사전·마스킹·스타일 규칙 |
| `1ffaf93` | 마스킹 복원을 정확하게 만들고 불변식 통로를 좁힌다 |
| `8412b89` | 복원을 사람이 제출한 본문으로 한정한다 — 단발 위조 차단 |
| `81f1d84` | 프롬프트 생성·주입 방어·후처리 |
| `f73879b` | LLM 경계 — LlmProvider 인터페이스와 Anthropic 어댑터 |

37파일 / +6,798줄. `core/`(easyread 5 · privacy 1 · text 3 · llm 3 + FakeLlmProvider testFixture),
`infrastructure/llm/`(AnthropicProvider + 스텁 서버), 스냅샷 리소스 2종, version catalog.

### 1.2 참조한 정본

- `docs/plans/2026-08-11-kotlin-react-migration.md` §2.2 · §2.3 · §3.1 · §3.2 · §4.5 · §4.6 · §5 · §6
- `docs/migration/_workspace/00_requirements-inventory.md` — INV-01~09 · CNV-01~06 · STY-01~05, Phase 2 게이트 정의(§142)
- `docs/migration/_workspace/03_rebuild-extraction-list.md` P1-4(큐레이션 데이터 반출)
- `docs/migration/_workspace/00_progress.md` Phase 2 표(340~352행) · 게이트 표(360~372행)
- `contracts/easy-doc-v1.yaml` — `MaskedItemResponse`(1655~1686) · `ConversionResponse`(1699~1754) · 내보내기 409(1044~1049, 1097~1105)
- `parity/fixtures/masking/masking.json` — spec 모드, `spec_status: ready`, **31케이스**
- `CLAUDE.md` 아키텍처 규칙 1·2·4, 보안·데이터 규칙, 「선언한 범위와 실제 도달을 대조한다」

### 1.3 판정 기준

**재개발이다. Python 출력 일치는 기준이 아니다**(2026-08-12 전환, master-plan 6.2).
기준은 요구사항·정책 충족. 예외인 정책 불변식은 마스킹 선행(2종) · LlmProvider 경유 · 로그 금지.
**큐레이션 데이터(사전 246·프롬프트 문구)는 예외의 예외다** — 이것은 실측 튜닝의 산출물이라
Python 이 데이터의 출처이고, 여기서만 값 동일성이 옳은 기준이다. 이 구분을 커밋이 정확히 지켰다.

### 1.4 이번 리뷰가 직접 실행한 검증

주장을 그대로 옮기지 않기 위해 네 가지를 실행했다. 결과는 각 지적에 인용한다.

1. **스냅샷 독립 재추출** — `app/easyread/style_rules.py`·`prompts.py`·`postprocess.py`·`privacy/masking.py` 에서 내가 직접 뽑아 리소스 JSON 과 대조 (`uv run python`).
2. **정규식 실측** — JDK 21 `java` 단일 파일 실행으로 `UNICODE_CHARACTER_CLASS` 하의 RRN·CARD 매치와 탈출 해제 도달을 확인.
3. **parity 게이트 도달 추적** — `parity-domains.txt` → `build.gradle.kts` → `ci.yml` → `compare_parity.py` 경로 전수.
4. **CI 배선 전수** — `.github/workflows/ci.yml` 580줄의 모든 잡·스텝.

---

## 2. 다섯 축별 지적

심각도 척도는 `codex-review` 스킬 §5. **차단(Critical)** 은 ①사건 / ②장치(그 사건을 탐지·차단하는
게이트의 무력화)를 같은 무게로 센다. 「마감」은 그 게이트가 처음 실제로 쓰이는 Phase이며,
**착수 차단 여부의 판정은 리더에게 넘긴다.**

### 축 1 — 계약 준수

#### C-1 · 수정 필요 · 마감 Phase 4
**`ambiguous`(복제 자리표시자)가 계약의 어떤 실패 모드에도 대응되지 않아, 자리표시자가 글자
그대로 박힌 문서가 200 으로 내보내진다.**

`restoreForExport` 는 자리표시자가 2회 이상이면 **한 곳도 복원하지 않고** `ambiguous` 로 보고한다
(`backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:497-500, 540`).
그 판단 자체는 옳다(개인정보를 모델이 고른 자리에 심지 않는다). 문제는 **그 뒤가 비어 있다**는 것이다.

- 계약의 409 조건은 정확히 둘뿐이고 **둘 다 `missing_placeholders` 에 걸려 있다**
  (`contracts/easy-doc-v1.yaml:1044-1049`, `:1097-1105`).
- `ambiguous` 인 본문은 `missing` 이 비어 있다(자리표시자가 사라진 게 아니라 늘어난 것이므로).
  → 409 에 걸리지 않는다 → **복원되지 않은 채 200 으로 내보내진다.**
- 계약 파일 전체에 `ambiguous`·`withheld`·`foreign`·`탈출`·`[[!` **0회**.

현실적 유입 경로가 있다. 입력에 `[[주민등록번호1]]` 이 이미 있으면 마스킹이 `[[!주민등록번호1]]` 로
탈출시켜 내보내고(`Masking.kt:222-223`), 그 문자열이 검수 화면에 **그대로 보인다**. 검수자가 그 `!` 를
오타로 보고 지우면 그 순간 같은 토큰이 둘이 되어 `ambiguous` 가 되고, 계약상 아무도 막지 않는다.

커밋 `8412b89` 메시지는 이 건을 **contract-keeper 에 넘겼다**고 적었다. 확인 결과
`00_contract-keeper_changelog.md`·`00_contract-keeper_test-plan.md` 양쪽에 `ambiguous`·`withheld`·
`탈출` 모두 **0회** — 커밋 메시지 안에서만 넘겨졌고 **아무도 받지 않았다.** 열려 있는 유일한 기록은
OQ-1(헤더 전역 부착)이며 무관하다.

→ contract-keeper 에 정식 항목으로 등록할 것. 계약을 어떻게 고칠지는 이 리뷰의 권한 밖이다.

#### C-2 · 권고 · 마감 Phase 3
**계약이 못박은 자리표시자 패턴을 실행 검사가 계약 파일에서 읽지 않는다.**

계약은 `^\[\[(주민등록번호|카드번호)[0-9]+\]\]$` 로 앵커까지 못박았다
(`contracts/easy-doc-v1.yaml:1680`, `:1740`). Kotlin 쪽 대응 단언은
`backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt:187-188` 인데
**문자열 두 개를 하드코딩**한다. 그 위 :185 주석이 "계약이 못박았다"고 말하지만 계약 파일을 읽지 않는다.
`backend-kotlin/**` 어느 테스트도 `contracts/easy-doc-v1.yaml` 을 읽지 않는다(전수 확인).

계약을 실제로 파싱하는 코드는 존재한다 —
`.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py:68` 의 `CONTRACT_PATH` 와
`compare_parity.py::check_placeholder_scheme`(:511, :525, :555). **그런데 그 경로는 도달 0이다**(P-1).
즉 계약↔구현 대조는 지금 **주석으로만 존재한다.**

#### C-3 · 권고 · 마감 Phase 4
**탈출 표기가 담기는 채널이 계약에 필드로 존재하지 않는다.**

`grep masked_text contracts/easy-doc-v1.yaml` → **0회**. 마스킹된 본문은 계약이 기술하지 않는 채널로
LLM 과 검수 화면에 도달한다. `[[!주민등록번호1]]` 은 사용자가 보게 되는 문자열인데 계약 밖이다.
`easy_text` 에는 패턴 제약이 없으므로 현재 **계약 위반은 아니다.** contract-keeper 판정 대상으로 올린다.

---

### 축 2 — parity 위험

#### P-1 · **차단(②장치)** · 마감 Phase 2 종료 전
**masking 도메인은 포팅이 끝났고 spec fixture 31건이 `ready` 인데, 값·성질 대조가 어디서도 돌지 않는다.**

| 구성 요소 | 상태 |
|---|---|
| Kotlin 구현 | 완료 (`Masking.kt`, 549줄) |
| spec fixture | `parity/fixtures/masking/masking.json` — **31케이스**, `mode: "spec"`, `spec_status: "ready"`, 성질 단언 4종(`restores_input`·`placeholder_scheme`·`absent`·`present`) |
| 선언 | `backend-kotlin/parity-domains.txt` — 유효 줄 **0개**(전 줄이 주석) |
| `parityManifestCheck` | `build.gradle.kts:212-216` — `declared.isEmpty()` 는 `logger.lifecycle` 만, `error()` 없음 |
| CI | `ci.yml:319-325` — `declared_count -eq 0` → `::warning::` 2줄 → **`exit 0`** |
| `compare_parity.py` | 호출부는 `ci.yml:343`·`:361` 둘뿐이며 **둘 다 현재 도달 불가** |
| `parity/actual/` | **비어 있음**(`./gradlew parityHarness` 실행 후에도) |
| Kotlin 생산자 | **없음** — `backend-kotlin/**` 어느 파일도 `parity/fixtures` 를 읽지 않는다 |

`ParityActual.kt` 는 JSON writer 일 뿐이고 `maskText` 를 import 조차 하지 않는다. 저장소 유일의
`@Tag("parity")` 테스트는 `ParityActualTest.kt:100` 이며 JVM 버전 정보를 담은 selfcheck 파일만 쓴다
(그 파일 스스로 `"게이트 판정에 쓰지 않는다"` 라고 적혀 있다).

**왜 ②장치인가**: 이 fixture 가 판정하는 성질이 곧 INV-02 의 검증 방법이고
(`00_requirements-inventory.md` — `masking parity fixture 값 대조 + 계약 enum 2값`),
INV-02 는 Phase 2 게이트 항목이다. 게이트가 "검증 대상 없음"으로 통과하는 동안 그 게이트가 막기로 한
사건(마스킹 누락 → 개인정보 외부 전송)은 아무도 보지 않는다. `parity-domains.txt` 는 **범위 선언형
장치**이고, 규칙 4가 "**범위 선언형은 빈 선언에서 통과하면 안 된다**"고 명시한 바로 그 형태다.

**정상 참작 두 가지 — 그러나 심각도를 낮추지 않는다.**
- 구현자는 이 사실을 **은폐하지 않았다.** 커밋 `0c377a5` 가 `parity-domains.txt` 를 손대지 않았다고
  명시했고 progress 표도 `안 돎` 으로 적혀 있다. 지적은 은폐가 아니라 도달 0 자체다.
- `00_progress.md:350` 에 parity-verifier 가 이미 **CI 셸 4종 실측**을 남겼다 —
  `선언 masking + 산출물 정상 → exit 0(부분 게이트 notice)`. 즉 **켤 수 있다는 것이 이미 측정돼 있다.**
  남은 것은 Kotlin 생산자 한 조각뿐이다.

**부가 지적 2건**
- **(a) 대리 경로로 잰 증거.** 커밋 `0c377a5` 는 대신 "골든 56 + 합성 4 + 사전값 246×7틀 = 입력 1,782건,
  8필드 비교, 불일치 0" 을 제시했다. 그 하네스는 **저장소에 없다** — 재현·재실행·회귀 감시가 불가능하다.
  「측정이 대리 경로에서 이뤄지지 않았는가」에 정확히 해당한다. 커밋 자신이 "근거이지 parity 판정이
  아니다"라고 적은 것은 정직하지만, 그 결과 **판정은 여전히 0건**이다.
- **(b) 형식조차 어긋나 있다.** `ParityActualTest.kt:35` 의 마스킹 예시 값이 `"[주민등록번호]"` 인데
  실제 스킴은 `[[주민등록번호1]]` 이다. 지금 배선을 이어도 형식 층에서 한 번 더 걸린다.

#### P-2 · 수정 필요 · 마감 Phase 2 종료 전
**`UnicodeRegex.kt` 가 제공하지 않는 보호를 선언한다 — 전각 주민등록번호는 여전히 마스킹되지 않는다.**

`backend-kotlin/core/src/main/kotlin/kr/easydoc/core/text/UnicodeRegex.kt:17-20` 이 `unicodeRegex` 의
존재 이유 1번으로 이렇게 적었다.

> **마스킹** — 주민등록번호가 전각 숫자로 적혀 있으면 ASCII `\d` 는 통째로 놓친다.
> 놓친 개인정보는 그대로 외부 모델로 나간다.

**JDK 21 실측 결과 — 이 문장이 약속하는 보호는 성립하지 않는다.**

```
RRN  전각      "번호 ９００１０１-１２３４５６７ 확인."   → match=false   ← 여전히 안 잡힘
RRN  아랍인도숫자 "번호 ٩٠٠١٠١-1234567 확인."          → match=true
CARD 전각      "카드 １２３４-５６７８-９０１２-３４５６ 입력." → match=true
CARD 아랍인도숫자 "카드 ١٢٣٤-5678-9012-3456 입력."      → match=true
```

원인은 `Masking.kt:155` 의 RRN 패턴이 성별코드를 **ASCII 리터럴 범위 `[1-8]`** 으로 쓰기 때문이다.
`UNICODE_CHARACTER_CLASS` 는 축약 클래스(`\d`)만 넓히고 명시 범위는 건드리지 않는다 —
이 사실은 같은 파일 :27-29 이 스스로 정확히 적어 두었다. 즉 **같은 KDoc 안에서 앞 문단이 뒤 문단에
의해 반박된다.** 카드번호에는 리터럴 범위가 없어 전각이 잡히고, 주민등록번호만 안 잡힌다.

**fixture 는 이 건을 정확히 알고 있다.** `masking-known-gap-rrn-fullwidth` 가 `known_gap` 으로
등록돼 있고, 설명이 *"요구사항으로 보면 가려야 맞지만 … **어느 방향도 단언하지 않는다**"* 라고 적었다.
**Kotlin KDoc 만 반대로 적혀 있다.**

그리고 **유니코드 숫자 4케이스 전부가 Kotlin 테스트에 하나도 없다.** `MaskingTest.kt` 의 RRN·CARD
케이스는 전부 ASCII 다. 즉 `unicodeRegex` 의 존재 이유로 적힌 시나리오에 대한 커버리지가 0이다.

→ 최소한 KDoc 을 사실에 맞추고, 전각 RRN 을 가릴지 말지는 **policy 결정**이므로 `privacy-gate` 판정으로
올린다(패턴을 넓히는 것은 마스킹 범주 확대가 아니라 표기 확대이므로 정책 위반은 아니지만, fixture 가
일부러 방향을 비워 둔 자리다).

#### P-3 · 권고 · 마감 Phase 4
**"탈출 해제는 검수 여부와 무관하다"는 선언이 중첩 케이스에서 성립하지 않는다.**

`Masking.kt:507-509` 이 *"마지막에 탈출 표기를 한 겹 벗기는 것도 **검수 여부와 무관하다**"* 라고
단정한다. 실측 결과 검수본이 없는 경로에서 성립하지 않는 입력이 있다.

```
입력        : 자리표시자 안에 13자리가 든 경우 [[주민등록번호1234567890123]]
마스킹 결과  : 자리표시자 안에 13자리가 든 경우 [[!주민등록번호[[주민등록번호1]]]]
reviewed≠null: 자리표시자 안에 13자리가 든 경우 [[주민등록번호1234567890123]]   ← 정확히 복원 ✓
reviewed==null: 자리표시자 안에 13자리가 든 경우 [[!주민등록번호[[주민등록번호1]]]]  ← `!` 가 남는다 ✗
```

`ESCAPED_LOOKALIKE`(`Masking.kt:213`)는 `[[` + `!` + 라벨 + **숫자** + `]]` 를 요구하는데, 자리표시자로
치환되기 전에는 라벨 뒤가 `[` 라 매치가 성립하지 않는다. 개인정보 유출은 없고 사용자 본문이 훼손될
뿐이라 권고로 둔다.

**테스트가 정확히 이 사이에 낀다** — `MaskingTest.kt:215` 가 이 입력을 쓰지만 `restoreReviewed`(검수본
경로)로만 돌리고, 검수 없는 탈출 해제 테스트 `:428` 는 단순 입력만 쓴다. 두 테스트가 각자 절반씩 덮어
교집합이 비어 있다.

#### P-4 · 권고 · 마감 Phase 2 종료 전
**프롬프트 스냅샷 테스트 안에 Kotlin↔Python 마스킹 값 동일성 단언이 숨어 있다.**

`PromptTextSnapshotTest.kt:77-83` 이 Kotlin `maskText(sourceText)` 의 결과를 스냅샷의 `masked_text`
(= Python `mask_text` 출력)와 **값으로** 비교한다. 이음매 검증 의도는 타당하다. 다만 Kotlin 마스킹은
`1ffaf93` 이후 **의도적으로 Python 과 갈린다**(탈출 표기). 자리표시자 모양이 든 케이스를 하나라도
스냅샷에 추가하면 이 단언은 **개선을 회귀로 잡는다.** 현재 6+5 케이스에 그런 입력이 없어 통과 중이다.
프롬프트 스냅샷이 마스킹의 정본이 아님을 KDoc·`_note` 에 명시할 것.

---

### 축 3 — 보안 불변식

> 이 축의 최종 차단 권한은 `privacy-gate` 에 있다. 여기서는 새 코드가 어느 감사 항목에 닿는지 지목한다.

#### S-1 · **차단(②장치)** · 마감 Phase 2 종료 전
**`MaskedText` 의 생성 통로에 회귀 탐지기가 없다 — 그 회귀는 이미 한 번 일어났다.**

마스킹 선행(INV-01, CLAUDE.md 아키텍처 규칙 2)의 강제는 두 겹이다.

| 대상 | 강제 | **상시 탐지기** |
|---|---|---|
| `LlmPrompt` | 생성자 private, 통로 2개 | **있음** — `LlmPromptTest.kt:24-39` (리플렉션으로 `declaredConstructors` 개수 1 + private 확인) |
| `PromptsKt` 상위 함수 | 원문 `String` 오버로드 없음 | **있음** — `PromptsTest.kt:199-218` (JVM 이름 변형으로 확인) |
| **`MaskedText`** | 생성자 private, 통로 1개(`mask`) | **없음** |

저장소 전체에서 `declaredConstructors`·`Modifier.isPrivate` 는 `LlmPromptTest.kt` 에만 나온다(전수 확인).

**이것이 왜 무거운가**: 정확히 이 자리의 회귀가 `1ffaf93` 에서 실제로 일어났다. 그 커밋 메시지가
직접 이렇게 적었다 — *"`MaskedText` companion 이 `internal` 이었다 … `core` 어디서든
`MaskedText.wrap(임의_문자열)` 이 컴파일됐다. **선언한 범위와 실제 도달이 다르다** — 내가 이 세션 내내
잡아 온 그 형태를 새로 쓴 코드에서 또 냈다."* 그리고 고침의 근거로 든 것은 **1회성 음성 대조**
(이전 코드로 되돌려 `wrap` 이 컴파일됨을 확인)였다. 1회성 대조는 그 시점의 사실을 보일 뿐 다음 회귀를
막지 못한다. 지금 누군가 `internal fun wrap(masked: String)` 을 다시 넣으면 **228개 테스트가 전부 통과한다.**

파생 타입(`LlmPrompt`)에는 상시 탐지기를 붙이고 **뿌리 타입에는 붙이지 않은 비대칭**이 지적의 핵심이다.
`LlmPromptTest` 를 그대로 본떠 `MaskedText`(그리고 `MaskedText.Companion` 의 선언 메서드가 `mask` 하나뿐인지)
를 확인하면 닫힌다.

#### S-2 · **차단(②장치)** · 마감 Phase 3 · *이번 범위가 만든 결함은 아니다*
**`scan_privacy_invariants.py` 는 `backend-kotlin/` 을 스캔 범위로 선언하지만 어디서도 실행되지 않는다.**

- 선언: `SCAN_ROOTS = ["app", "backend-kotlin", "scripts", "frontend/src"]`,
  `SUFFIXES` 에 `.kt`·`.kts` 포함. `LOG_CALL` 이 `println`·`System.out.print` 를, `BODY_NAMES` 가
  `sourceText`·`easyText`·`maskedText` 같은 camelCase 를 이미 안다 — **JVM 을 겨냥해 만들어졌다.**
- 도달: `.github/workflows/ci.yml` 전 580줄에서 이 스크립트의 **호출 스텝이 없다.** ci.yml:70 에 언급이
  한 번 있는데, 그것은 "이 파일이 타입 검사를 못 받고 있었다"는 **주석**이다. git hook 없음,
  `.pre-commit-config.yaml` 없음.
- 대체물: detekt 커스텀 규칙 **없음**(`config/detekt/detekt.yml` 73줄에 PII·본문·로깅 규칙 0),
  ktlint 커스텀 규칙 없음, grep 기반 CI 스텝 없음.

**이번 범위가 이 공백을 처음으로 물리게 만들었다.** 지금까지 Kotlin 은 골격·CI·오류 계약뿐이었는데,
이번에 사용자 문서 본문과 개인정보를 실제로 다루는 파일 12개가 들어왔고 그중 `Secret.reveal()`
(`core/src/main/kotlin/kr/easydoc/core/security/Secret.kt:25`)은 **평문을 꺼내는 공개 통로**다.
`MaskedItem.original.reveal()` 을 로거 인자로 넘기는 코드는 컴파일되고, 그것을 잡기로 한 유일한 장치가
아무 데서도 돌지 않는다.

현재 Kotlin 로깅 표면이 작다는 점은 정상 참작 사유다(로거 3개, 실제 로그 호출 2줄). 그러나 그것은
"지금은 사고가 없다"이지 "탐지된다"가 아니다. **`privacy-gate` 판정 대상으로 올린다.**

#### S-3 · 권고
"키가 새지 않는 다섯 겹" 중 **cause 미부착만 단언이 없다**. `hasNoCause`·`.cause` 단언이 저장소에 0건.
다만 `EasyDocException(message: String)`(`DomainExceptions.kt:19`)에 cause 인자가 아예 없어 구조적으로
막혀 있으므로 위험은 낮다. `hasNoCause()` 한 줄이면 나머지 네 겹과 같은 수준이 된다.

#### S-4 · 판정 필요 · 마감 Phase 5
`AnthropicSettings.baseUrl`(`AnthropicProvider.kt:128`)이 설정으로 열려 있고, 응답 본문을 통째로
메모리에 읽어(`post()` :206-207) 크기 상한이 없다. 문서 본문이 나가는 **대상**이 설정 한 줄로 바뀐다.
테스트 편의(스텁 서버)를 위한 설계라 이해되지만, Phase 5 배선 때 운영 설정이 이 값을 노출하는지
`privacy-gate` 가 판정해야 한다. 심각도를 임의로 낮추지 않고 「판정 필요」로 올린다.

#### S-5 · 검토함 — 지적 없음
실행 단언으로 확인한 것들. 모두 통과한다.

- 마스킹 범주 정확히 2종, 범위 밖 3종(전화·이메일·계좌) 무마스킹 고정 — `MaskingTest.kt:160-189`
- 과잉 마스킹 방지(성별코드 9·0, 12/14자리, 15/17자리 카드) — `MaskingTest.kt:68-86, 110-116`
- `Secret` 이 `toString`·`hashCode`·상수시간 `equals` 로 평문 차단 — `Secret.kt:31-48`
- `LlmPrompt.toString`·`LlmCompletion.toString` 이 길이만 남김 — 실행 단언 `LlmPromptTest.kt:80-90`
- `AnthropicProvider` 로거 없음 · 예외 메시지가 **상태 코드까지만** · 벤더가 키와 주민번호를 되비추는
  응답을 심어도 메시지에 없음 — `AnthropicProviderResponseTest.kt:127-144`
- **API 키 출현이 요청 전체(요청라인+전 헤더+본문)에서 정확히 1회** — `AnthropicProviderRequestTest.kt:159`.
  이것은 "본문만 검사"라는 흔한 좁힘을 피한 좋은 형태다.
- 키 미설정 시 요청 자체가 나가지 않음 — `AnthropicProviderRequestTest.kt:163-173`
- 재시도 0(§4.6 겹침 차단) — `AnthropicProviderResponseTest.kt:169-182`
- 주입 방어의 난수원이 `SecureRandom` 임을 **출처로** 단언 — `PromptInjectionGuardTest.kt:37-44`.
  출력만 보는 검사로는 구별할 수 없는 것을 정확히 짚었다.

---

### 축 4 — Kotlin/Spring 관용성

#### K-1 · 권고 · 마감 Phase 5
**`CoreModuleBoundaryTest` 의 선언(범주)과 도달(열거 10개)이 다르고, 스스로 적은 한계가 사실과 다르다.**

`core/src/test/kotlin/kr/easydoc/core/CoreModuleBoundaryTest.kt:44` 이 *"core 클래스패스에
Spring·DB·Jackson·**벤더 SDK** 가 없다"* 라고 범주로 선언하는데, 실제 도달은 클래스 이름 **10개 열거**다
(:25-42). 새 벤더(`com.google.genai.*`, `dev.langchain4j.*`)는 잡지 못한다. 「규칙·패턴의 범위가 근거보다
넓지 않은가」의 반대 방향 — **선언이 도달보다 넓다.**

그리고 :18-20 이 적은 한계가 **틀렸다.**

> 빌드 스크립트가 의존성을 `implementation` 이 아니라 `api` 로 바꾸는 식의 변경은 여기서 잡히지 않는다.

`api` 로 선언한 의존성은 core 자신의 런타임 클래스패스에 올라오므로 `Class.forName` 이 **찾아낸다 —
즉 잡힌다.** 실제로 못 잡는 것은 `compileOnly`(컴파일은 되고 런타임 클래스패스에는 없다)다.
**적어 둔 한계가 실제 한계와 다르면, 그 문장을 믿고 다음 사람이 잘못된 자리를 안심한다.**

→ 열거형 탐지기를 **허용목록형**으로 바꾸면 종류째 닫힌다(core 런타임 클래스패스 jar 를 나열해
kotlin-stdlib·annotations·테스트 라이브러리 외 항목이 있으면 실패). `compileOnly` 도 함께 닫으려면
`configurations["compileOnly"].isEmpty` 를 함께 본다.

#### K-2 · 권고
`core/build.gradle.kts:6` 이 존재하지 않는 클래스 `CoreHasNoSpringOrDbDependencyTest` 를 가리킨다.
실제 이름은 `CoreModuleBoundaryTest` 다. 빌드 스크립트가 가리키는 보증인이 실재하지 않으면,
그 파일을 지워도 주석은 그대로 남는다.

#### K-3 · 검토함 — 지적 없음
- `core` 가 Spring·DB·Jackson·벤더 SDK 를 모른다 — `core/build.gradle.kts` 에 `platform()` 만(jar 0개),
  `CoreModuleBoundaryTest` 가 실행으로 확인(범위 한계는 K-1)
- 벤더 어휘(`end_turn`·`x-api-key`·`output_config`)가 `AnthropicProvider.kt` 밖으로 나가지 않는다.
  `LlmFinishReason` 이 벤더 문자열을 우리 어휘로 정규화하고 매핑 함수가 파일 private(:323-330)
- `starter-web` 이 아니라 `spring-web` 만 — `infrastructure/build.gradle.kts:25`.
  "받는 쪽이 아니라 보내는 쪽"이라는 근거가 정확하다
- `FakeLlmProvider` 가 `main` 이 아니라 `testFixtures` — 제품 클래스패스에 가짜가 올라오지 않는다
- 본문을 UTF-8 **바이트**로 직접 실어 `StringHttpMessageConverter` 기본 charset 의존을 제거
- `allWarningsAsErrors` · `dependencyLocking` · detekt `maxIssues: 0` · ktlint 전 소스셋 — 게이트를
  느슨하게 하지 않고 코드를 고쳐 detekt 3건을 없앴다는 커밋 주장은 빌드 설정과 일관된다
- 트랜잭션 경계·Flyway·`JdbcClient` — **이번 범위에 해당 코드 없음**(미검토 아님, 대상 없음)

---

### 축 5 — 테스트 적정성

#### T-1 · 수정 필요 · 마감 **`app/**` 삭제 전** (이 창은 영구히 닫힌다)
**큐레이션 스냅샷 2종에 생성기가 커밋되지 않아 저장소 안에서 출처를 검증할 수 없다.**

`python-prompt-snapshot.json` 의 `_note` 는 *"app/easyread/prompts.py 에서 **기계로 뽑은** 전문
스냅샷"* 이라고 적었으나, **그 프로그램이 저장소에 없다**(`python-prompt-snapshot`·
`python-style-rules-snapshot` 문자열이 `*.py`·`*.sh`·`*.kts`·`*.yml` 어디에도 없다).
스냅샷은 두 커밋에서 손으로 추가됐다. 따라서 저장소만으로는 "Python 에서 기계 추출"과 "같은 작성자가
방금 쓴 Kotlin 에서 옮겨 적음"을 **구별할 수 없다.** 「검사의 기준이 검사 대상 자신에게서 나오지
않는가」에 해당한다.

무게가 큰 이유는 P1-4 다. 커밋 `0c377a5` 이 *"이 이식이 P1-4 반출을 겸한다"* 고 선언했다 —
즉 이 JSON 이 `app/**` 삭제 후 남는 **유일한 큐레이션 데이터 사본**이다. 삭제 뒤에는 재검증이
영원히 불가능하다.

**제3의 근거 — 이번 리뷰가 직접 실측했다(지적을 지우지 않고 근거로 추가한다).**
`app/` 이 아직 살아 있으므로 내가 독립적으로 추출해 대조했다. 결과는 **전건 일치**다.

| 항목 | 개수 | 결과 |
|---|---|---|
| `DIFFICULT_WORD_REPLACEMENTS` | 246 | 값·**순서까지** 완전 일치 |
| `PROMPT_ONLY_WORDS` / `STYLE_PRINCIPLES` / `DOUBLE_PASSIVE_PATTERNS` / `_COMMA_CHARS` | 5 / 6 / 5 / 3 | 일치 |
| `LEXICALIZED_GLOSSES` / `COMPOUND_TAIL_KEYS` / `COMPOUND_HEAD_NOUNS` | 21 / 3 / 38 | 일치 |
| `NOMINAL_GLOSSES` / `MODIFIER_CHECKED_GLOSSES` | 86 / 34 | 일치 (파생 규칙까지 옳다) |
| `GLOSS_COLLISION_PATTERN_GLOSSES` | 123 | **순서 포함** 일치 |
| `MAX_SENTENCE_CHARS` / `MAX_COMMAS_PER_SENTENCE` | 50 / 2 | 일치 |
| 지시문 상수 | 11 | 전문 바이트 일치 |
| 조립 시스템 프롬프트 | 6 케이스 | `masked_text`·`expected` 모두 0 불일치 |
| 후처리 | 29 케이스 (`keep_*` 9건) | 0 불일치 |

**따라서 데이터는 옳다.** 결함은 값이 아니라 **"다음 사람이 같은 확인을 할 수 없다"** 이다.
1회성 추출 스크립트를 `.claude/skills/python-kotlin-parity/scripts/` 에 커밋하고 CI 가
재생성→diff 하는 스텝을 하나 두면 닫힌다 — 그리고 그것은 `app/**` 이 살아 있는 동안에만 가능하다.

> 검증하지 못한 것: `user_prompts`·`repair_prompts` 조립 케이스(Python `build_user_prompt` 시그니처가
> document_id 주입을 받지 않아 내 스크립트로는 고정 id 를 넣지 못했다). 미검토로 남긴다.

#### T-2 · 수정 필요 · 마감 Phase 2 종료 전
**Phase 2 게이트 항목 중 대응 코드가 아예 없는 것이 둘 있다.**

`00_requirements-inventory.md:142` — `Phase 2 게이트 = INV-01·02, CNV-01·02·04, STY-01·02, DOC-05·06`.

| 항목 | 이번 범위 상태 |
|---|---|
| INV-01 마스킹 선행 타입 차단 | 구조적으로 구현. **단 탐지기 결손**(S-1) |
| INV-02 범주 2종 | 구현 + 하드코딩 단언. **fixture 값 대조·계약 enum 대조 모두 도달 0**(P-1·C-2) |
| STY-01 스타일 규칙 SSOT | 구현 + 스냅샷 대조. `Prompts.kt` 가 `StyleRules` 를 순회해 만든다 ✓ |
| STY-02 사전 246 이전 | 구현 + 전건 대조 ✓ (T-1 에서 독립 실측) |
| **CNV-01 LLM 호출 최대 2회** | **대응 코드 없음.** 커밋이 "application 이 가져갈 것"으로 넘김 |
| CNV-02 4대 예외 검출 | **부분.** 절단·빈 결과·거절은 provider 가 드러냄, 자리표시자 유실은 `missing`. **보정 악화는 없음** |
| **CNV-04 보정 실패·악화 시 원본 채택** | **대응 코드 없음** — Python `_accepts_repair` 대응물 부재 |

`_accepts_repair`(보정 채택 판정)는 이 에이전트 정의가 계획 §4.5 parity 위험으로 **명시적으로 지목한**
자리다("테스트가 없으면 조용히 다른 값을 내는 자리"). 프롬프트·후처리는 왔는데 그 판정만 빠졌다.
`00_progress.md:346` 도 별도 행(`보정 채택 판정 포팅`)으로 두고 `안 돎` 이다.

→ **Phase 2 종료 조건은 이 두 항목만으로도 닫히지 않는다.**

#### T-3 · 권고
**게이트 판정이 읽는 원장이 실물과 어긋나 있다.**

- `00_progress.md` Phase 2 표(342~349행)가 masking·text·prompts·style·postprocess 전부
  `아니오 / 안 돎` 이다. 이번 5커밋이 정확히 그것들이다. **과소 신고라 안전한 방향**이지만,
  리더가 Phase 판정에 쓰는 표가 실물을 반영하지 않는다.
- 같은 표 350행이 fixture 를 **"22 케이스"** 라고 적었는데 실제는 **31건**이다
  (`49ea2eb` 23건 → `525b9a1` 31건). 그 행의 실측 서술("대조군이 실제 `mask_text` 와 **22건** 전건
  일치")도 같은 수에 묶여 있다.
- 366행이 *"`parity/` 디렉터리 자체가 없음"* 이라고 적었으나 존재한다(350행과 자기모순).

#### T-4 · 검토함 — 지적 없음 (좋은 형태로 기록)
이번 범위에는 **실제로 무는 음성 대조 장치**가 여럿 있다. 다음 회차에서 이것들이 약해지지 않았는지
확인할 기준으로 남긴다.

- `PostprocessTest.kt:53-60` — `keep_*` 음성 케이스 **하한 9건** 단언. 케이스 목록이 조용히 줄어드는
  사고를 잡는다. (독립 실측으로 실제 `keep_*` 9건 확인)
- `PostprocessTest.kt:62-69` — 신호 정규식의 **좁음 자체**를 고정. 스냅샷을 다시 뽑아도 사라지지 않는다.
  "넓히려면 이 테스트를 손대야 한다"는 설계 의도가 실제로 성립한다.
- `PromptsTest.kt:199-218` — JVM 이름 변형으로 원문 `String` 오버로드 부재를 확인. **못 잡는 것
  (이름이 다른 새 함수)을 KDoc 이 정확히 적어 두었다.**
- `StyleRuleDataSnapshotTest.kt:60-61` — 개수를 리터럴(246)로도 못박아 "스냅샷과 구현이 같이 줄어드는"
  사고를 막는다. 이것은 자기참조를 끊는 정확한 수법이다.
- `AnthropicProviderResponseTest.kt:130-144` — 벤더 응답에 키와 주민등록번호를 **심어 놓고** 예외
  메시지에 없는지 본다. 5개 상태 코드 전부.
- `FakeLlmProvider` 가 응답 소진 시 조용히 넘기지 않고 예외 — 호출 상한 계약 위반의 신호를 삼키지 않는다.
- core 228 / infrastructure 41 / api 75 / worker 3 실행 확인(`:core:test --rerun-tasks` 재실행 결과
  tests=228, failures=0, errors=0, skipped=0).

---

## 3. 도달 범위 점검 (다섯 축을 가로지르는 필수 점검)

> 기준 전문은 `kotlin-migration` 스킬의 「선언한 범위와 실제 도달을 대조한다」 절.
> **이 구획은 비워 두지 않는다.** 지적이 없으면 "검토함 — 지적 없음", 보지 못했으면 "미검토(사유)".

| # | 점검 항목 | 결과 | 근거 |
|---|---|---|---|
| 1 | "전역"·"모든"·"항상" 선언이 닿지 않는 경로 | **지적 3건** | P-2(전각 RRN — KDoc 이 약속한 보호 미도달) · P-3("검수 여부와 무관" 이 중첩에서 미성립) · K-1("벤더 SDK" 범주 선언 vs 10개 열거) |
| 2 | 그 게이트가 **지금 어디서 도는가** — 도달 0을 특히 의심 | **지적 2건 (둘 다 도달 0)** | P-1 parity 값 대조 = CI `exit 0`, `parity/actual/` 비어 있음 · S-2 `scan_privacy_invariants.py` = CI 호출 스텝 0건 |
| 3 | 측정이 **대리 경로**에서 이뤄지지 않았는가 | **지적 1건** | P-1(a) "1,782건 함수 차등 대조"가 저장소에 없다 — 재현 불가한 대체물로 재고 통과 근거로 삼았다 |
| 4 | 검사의 기준이 **검사 대상 자신에게서** 나오지 않는가 | **지적 1건 + 반증 1건** | T-1 스냅샷 생성기 미커밋 → 출처가 저장소 안에서 검증 불가. **단 이번 리뷰가 외부 실측으로 출처의 진위를 확인했고 전건 일치였다.** 반대로 `1ffaf93` 이 테스트 안의 `restorePlaceholders` 헬퍼를 제품 코드로 올린 것은 이 결함을 **스스로 고친** 사례다 |
| 5 | 판정이 **대리 지표**로 이뤄지지 않는가 | **지적 1건** | P-1 — `parityManifestCheck` 의 종료 코드 0이 "검증했다"로 읽힐 수 있다. 다만 `build.gradle.kts:213` 이 `"통과가 아니라 '검증 대상 없음'"` 이라고 **로그 문구로 구분해 둔 것**은 옳은 형태다(로그를 읽는 사람에게만 닿는다는 한계는 남는다) |
| 6 | 규칙·패턴의 **범위가 근거보다 넓지 않은가** (은폐형) | **검토함 — 지적 없음** | 이번 범위에서 전역 무시·억제·면제 조항 추가 없음. detekt `maxIssues: 0` 유지, 커밋이 "게이트를 느슨하게 하지 않고 코드를 고쳐 detekt 3건 제거"라고 적었고 `detekt.yml` 에 새 예외가 없다. `@Suppress` 는 테스트의 `UNUSED_PARAMETER` 3건뿐(JUnit 인자 요구사항) |
| 7 | **음성 대조가 붙어 있는가** (떼면 무엇이 깨지는가) | **혼재 — 지적 1건** | 상시 장치 있음: T-4 목록. **1회성에 그친 것**: `MaskedText` 통로 봉쇄(S-1) — `1ffaf93` 의 음성 대조는 그 시점 사실만 보였고 상시 탐지기가 없다. 커밋 `81f1d84` 의 "변조 8종 전부 의도한 테스트에서만 잡혔다"·`f73879b` 의 "temperature 를 넣으니 FAILED 로 잡혔다"는 **주장으로만 남아 있다**(재현 절차 미커밋) — 다만 해당 단언 자체는 코드에 실재하므로(`AnthropicProviderRequestTest.kt:75-79`) 장치는 있다 |
| 8 | 판정하는 코드가 **자기 자신을 검사 대상에 넣었는가** | **부분 — 지적 1건** | Kotlin 쪽: parity 하네스(`ParityActual.kt`·`ParityHarnessSelfCheck.kt`)가 `testFixtures` 라 ktlint·detekt·컴파일 대상 안에 있다 ✓. **Python 쪽 게이트 스크립트**: `uv run mypy . .claude` 로 타입 검사는 받으나(2026-08-13 수정 이력), `scan_privacy_invariants.py` 는 **자기가 검사할 코드가 늘어난 지금도 실행되지 않는다**(S-2) |

**추가로 확인한 것 — `실행 경로` 규약 자체의 도달**
`tests/test_harness_scope_reach.py`(35 테스트)는 `00_progress.md` 표의 **형식**만 강제하며, 스스로
*"`ci:quality` 라고 적힌 행이 정말 그 잡에서 도는지는 검사하지 않는다"* 고 적었다. 이 한계는 정직하게
선언돼 있고 이번 범위가 넓히지도 좁히지도 않았다. **Kotlin 소스는 이 검사의 대상이 아니다**
(문자열로만 등장). 그 자체는 결함이 아니지만, T-3 의 원장 표류를 이 장치가 잡지 못하는 이유다.

---

## 4. Phase 종료 조건 대비 현황

### Phase 2 (§5 · `00_requirements-inventory.md:142`)

| 게이트 항목 | 판정 | 사유 |
|---|---|---|
| INV-01 마스킹 선행 타입 차단 | **미충족** | 구현됨. 탐지기 결손(S-1) |
| INV-02 범주 2종 | **미충족** | 구현됨. 검증 3개 중 실행되는 것 1개(하드코딩 단언). fixture 대조·계약 대조 도달 0 (P-1·C-2) |
| CNV-01 LLM 호출 최대 2회 | **미충족** | 대응 코드 없음 |
| CNV-02 4대 예외 | **부분** | 보정 악화 미구현 |
| CNV-04 보정 악화 시 원본 채택 | **미충족** | 대응 코드 없음 |
| STY-01 스타일 규칙 SSOT | **충족 후보** | 구현 + 스냅샷 대조. T-1(생성기) 해소 필요 |
| STY-02 사전 246 이전 | **충족 후보** | 동상. 이번 리뷰 독립 실측 전건 일치 |
| DOC-05·06 | **미검토** | 문서 추출은 이번 범위 밖 — 사용자 지시로 TEXT 우선, PDF·HWP 후순위 |
| **종료 조건**: 외부 API·DB 없이 도는 parity suite 가 양쪽에서 같은 결과 | **미충족** | parity suite 판정 건수 **0** |

**→ Phase 2 종료 불가.** 최소 조건은 (a) `masking` 도메인 선언 + Kotlin 생산자 배선(P-1),
(b) `MaskedText` 상시 탐지기(S-1), (c) CNV-01·CNV-04 구현(T-2) 이다.

### 이번 범위가 실제로 전진시킨 것 (기록)

과소 평가하지 않기 위해 적는다. 도메인 로직이 0이던 상태에서 다음이 생겼다.

- 큐레이션 데이터 246 + 파생 집합 5종 + 프롬프트 전문이 Kotlin 에 이식됐고 **독립 실측으로 전건
  일치가 확인됐다**(T-1). P1-4 반출의 실질을 겸한다.
- 마스킹 선행이 주석이 아니라 **타입**으로 섰다(`MaskedText` → `LlmPrompt` → `LlmProvider`).
- Python 에 있던 두 결함이 옮겨지지 않고 **고쳐졌다** — ⑴ 자리표시자 충돌(정확 복원 파괴),
  ⑵ 검수 없는 본문에 개인정보를 복원하는 경로(`edited_text ?? easy_text` 무조건 복원).
  둘 다 재개발 판정 기준("Python 이 틀린 경우를 전제한다")의 올바른 적용이다.
- 재시도 겹침(§4.6)을 어댑터 층에서 끊었고 실행 단언으로 고정했다.

---

## 5. 미실행·확인 불가 항목

| 항목 | 사유 |
|---|---|
| codex 독립 리뷰와의 교차 대조 | **1차 회차이므로 정상.** 2차 재호출에서 `07_core-rebuild_cross.md` 로 수행 |
| `privacy-gate` 감사 결과 대조 | 이번 범위에 대한 신규 감사 산출물 없음. S-1·S-2·S-4 를 `privacy-gate` 판정 대상으로 올림 |
| `parity-verifier` 판정 | **판정 건수 0**(P-1). 리포트·mismatch·coverage 산출물이 이번 범위에 대해 존재하지 않는다 |
| `contract-keeper` 계약 준수 채점 기준 | contract test 미구현(Phase 3부터). C-1·C-2·C-3 을 contract-keeper 로 올림 |
| `user_prompts`·`repair_prompts` 스냅샷의 Python 대조 | Python `build_user_prompt(masked_text: str)` 가 document_id 주입을 받지 않아 내 검증 스크립트로 고정 id 를 넣지 못했다. 시스템 프롬프트 6건·후처리 29건·상수 11건은 대조 완료 |
| 골든셋 통과율 영향 | 프롬프트·스타일 규칙을 건드렸으나 Kotlin 쪽 골든셋 평가 경로가 아직 없다. Python 무변경이 확인돼 있어(`git status app tests` 비어 있음) 기존 통과율은 흔들리지 않는다 |
| 문서 추출·내보내기(DOC 계열) | 이번 범위 밖 — 사용자 지시로 TEXT 우선 |
| 트랜잭션 경계·Flyway·`JdbcClient`·Testcontainers 제약 검사 | 이번 범위에 해당 코드 없음 (미검토가 아니라 대상 없음) |
| 성능·유지보수성 등 범용 품질 축 | **이 에이전트의 범위 밖.** 필요하면 글로벌 `multi-review` 를 별도로 돌릴 것을 리더에게 권고한다. 참고로 `checkStyle` 이 문장마다 사전 246 순회 + 정규식 123개를 돌리는 구조라 장문에서 비용이 문서 길이의 제곱에 가까워질 수 있다 — 판정은 `multi-review` 몫 |

---

## 6. 지적 요약

| ID | 축 | 심각도 | 마감 | 한 줄 |
|---|---|---|---|---|
| P-1 | parity | **차단(②장치)** | Phase 2 종료 전 | masking 포팅 완료 + fixture 31건 ready 인데 값·성질 대조 도달 0 (CI `exit 0`) |
| S-1 | 보안 | **차단(②장치)** | Phase 2 종료 전 | `MaskedText` 생성 통로에 상시 탐지기 없음 — 그 회귀는 `1ffaf93` 에서 이미 발생 |
| S-2 | 보안 | **차단(②장치)** | Phase 3 | `scan_privacy_invariants.py` 가 `backend-kotlin` 을 선언하나 CI 도달 0 (이번 범위가 처음으로 물리게 만듦) |
| C-1 | 계약 | 수정 필요 | Phase 4 | `ambiguous` 가 계약 409 에 없어 자리표시자가 박힌 문서가 200 으로 나감 + 인계가 아무 데도 기록 안 됨 |
| P-2 | parity | 수정 필요 | Phase 2 종료 전 | `UnicodeRegex.kt` KDoc 이 제공하지 않는 보호를 선언 — 전각 RRN 실측 미검출 |
| T-1 | 테스트 | 수정 필요 | **`app/**` 삭제 전** | 스냅샷 생성기 미커밋 — 출처 검증 불가(이번 리뷰가 외부 실측으로 전건 일치 확인) |
| T-2 | 테스트 | 수정 필요 | Phase 2 종료 전 | Phase 2 게이트 항목 CNV-01·CNV-04 대응 코드 없음 |
| S-4 | 보안 | **판정 필요** | Phase 5 | `baseUrl` 설정 노출 + 응답 크기 상한 없음 |
| C-2 | 계약 | 권고 | Phase 3 | 계약 패턴을 실행 검사가 계약 파일에서 읽지 않음 |
| C-3 | 계약 | 권고 | Phase 4 | 탈출 표기가 담기는 `masked_text` 채널이 계약에 없음 |
| P-3 | parity | 권고 | Phase 4 | 검수 없는 경로에서 중첩 탈출이 해제되지 않음 |
| P-4 | parity | 권고 | Phase 2 종료 전 | 프롬프트 스냅샷 안에 Kotlin↔Python 마스킹 값 동일성 단언이 숨어 있음 |
| S-3 | 보안 | 권고 | — | "cause 미부착"만 단언 없음(구조적으로는 막혀 있음) |
| K-1 | 관용성 | 권고 | Phase 5 | 모듈 경계 테스트의 선언(범주) vs 도달(열거 10) + 적어 둔 한계가 사실과 다름 |
| K-2 | 관용성 | 권고 | — | `core/build.gradle.kts:6` 이 존재하지 않는 테스트 클래스를 가리킴 |
| T-3 | 테스트 | 권고 | Phase 2 종료 전 | `00_progress.md` Phase 2 표가 이번 5커밋 미반영, fixture 케이스 수 22↔31 불일치 |

**차단 3건 · 수정 필요 4건 · 판정 필요 1건 · 권고 8건.**

세 차단은 모두 **②장치**(사건이 아니라 그 사건을 탐지·차단하는 게이트의 무력화)다. 셋 다 공통 형태를
가진다 — 만들어 둔 장치가 있는데(fixture 31건 · 리플렉션 가드 패턴 · 스캐너 171파일 커버) **그것을
실행하는 배선이 없다.** 「도달 0을 특히 의심한다」가 세 번 연속 적중한 것이므로, 다음 회차에서
가장 먼저 확인할 것도 같은 질문이다.

---

## 7. 리더에게 — 다음 회차 제안

- **2차 교차 종합 재호출 필요.** `07_core-rebuild_codex-reviewer.md` 와 이 파일을 입력으로 주면
  `07_core-rebuild_cross.md` 를 낸다. 어간을 반드시 일치시킬 것.
- **다른 레인으로 넘길 것**: `privacy-gate` ← S-1·S-2·S-4 및 P-2 의 전각 RRN 정책 판정 /
  `contract-keeper` ← C-1(정식 등록)·C-2·C-3 / `parity-verifier` ← P-1 의 masking 도메인 배선과
  P-4 의 기준 출처 정리 / `kotlin-implementer` ← S-1·P-2·P-3·K-1·K-2·T-2.
- **다음 회차 focus 제안**: ⑴ 차단 3건의 배선이 실제로 도는지(선언이 아니라 실행 로그로),
  ⑵ CNV-01·CNV-04 구현 시 "최대 2회"가 **메트릭에서 확인 가능한 형태**인지,
  ⑶ 범용 품질 축이 필요하면 `multi-review` 별도 실행.
