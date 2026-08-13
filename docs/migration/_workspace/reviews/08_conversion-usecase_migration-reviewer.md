# 08_conversion-usecase — Claude 독립 리뷰 (1차)

> **회차**: 1차 — **독립 리뷰 전용.** codex 산출물을 찾지도 읽지도 않았다(리더 지시대로 이 회차에서 codex 부재는 정상이며 실패로 기록하지 않는다). 교차 대조표는 이 파일에 없다 — 2차 `..._cross.md` 의 몫이다.
> **작성**: 2026-08-14 · **어간**: `08_conversion-usecase`(리더 지정)
> **대상 범위**: `f73879b..6d8e88c` (6커밋 · 변경 파일 36개)
> **판정 기준**: 재개발 — **요구사항 충족**이 기준이다(master-plan 6.2). Python 출력 일치는 기준이 아니며, 예외는 정책 불변식뿐이다.

---

## 0. 요약

| 심각도 | 건수 | 항목 |
|---|---|---|
| **차단(Critical)** | **0** | 이번 범위에서 ①사건·②장치 어느 쪽도 성립하지 않는다 — 근거 §5 |
| **수정 필요(Major)** | 5 | Y-1 · Y-3 · Y-5 · Y-8 · Y-16 |
| **판정 필요** | 2 | Y-2 · Y-4 |
| **권고(Minor)** | 8 | Y-6 · Y-7 · Y-9 · Y-10 · Y-11 · Y-12 · Y-13 · Y-14 |
| **검토함 — 지적 없음** | 6구획 | §4 도달 범위 표 참조 |

**회부 8건 처분**: 7건 닫힘 · 1건(X-2) **부분** — 구현·단언은 닫혔으나 privacy-gate 해제 조건 4개 중 2·4가 미충족인데 `00_progress.md` 행이 `충족 = 예`로 올라갔다(Y-1).

**이 조각의 성취(사실 확인)**: parity 게이트가 **처음으로 값을 판정했다.** 리더 직접 재현 가능 — `compare_parity.py --only-domain masking --only-domain repair-adoption` → **exit 3 · 성질 56건 · 단언 189개 · 참고 갈림 1건 · 불충족 0건**(본 리뷰가 독립 실행해 재현). 그리고 privacy-gate가 지목한 마스킹 표기 변형이 실제로 닫혔다 — **음성→양성 17/8 분할을 독립 재계산으로 확인**(§2.2 Y-검증 A).

---

## 1. 리뷰 범위와 참조 정본

| 참조 | 용도 |
|---|---|
| 계획 §2.2 · §2.3 | 계약 준수 축 · 보안 불변식 축 |
| 계획 §3.1 · §3.2 | 모듈 경계·의존 방향(Kotlin/Spring 관용성 축) |
| 계획 §4.5 · §4.6 | parity 위험 축(정규식·유니코드·프롬프트·보정 채택) |
| 계획 §5 Phase 2 · Phase 7 | 종료 조건 · 즉시 중단 기준 |
| 계획 §6 | Contract·Security·Parity·Quality 게이트 |
| `00_requirements-inventory.md` §1(INV-01·01-a·02·03·07) · §3.1 (가)~(마) · §9-E | 요구 정본 — CNV-01·02·04 판정식 |
| `02_parity-verifier_conversion-spec.md` §3(하네스 계약) · §5 · §6 · §7 | 하네스 계약과 갈림 후보 |
| `07_privacy-gate_masking-verdicts.md` §1(X-2) · §2(X-5) · §3(X-8) · §4(X-11) | 보안 축 채점 기준 — **보안 판정이 갈리면 이 문서가 우선한다** |
| `reviews/07_core-rebuild_cross.md` §6 | 회부 조치 8건 |
| `02_kotlin-implementer_conversion-usecase.md` | 구현자 자기보고 — **주장이지 근거가 아니다.** 아래는 전부 코드·실행으로 다시 확인한 것이다 |

**작업 트리 오염 주의(범위 판정에 영향)**: 리뷰 중 `git status`가 미커밋 변경 5건을 보였다 — `Masking.kt`·`dump_parity_fixtures.py`·`07_privacy-gate_masking-verdicts.md`·contract-keeper 산출물 2건. **다른 레인이 동시에 작업 중이다.** 이 리뷰의 판정은 전부 **커밋된 `6d8e88c` 기준**이며, 작업 트리에만 있는 변경은 범위 밖으로 표시했다(Y-5).

---

## 2. 다섯 축별 지적

### 2.1 계약 준수 (§2.2 · §6 Contract 게이트)

이 범위에 HTTP 계층 변경은 없다. 그러나 application 결과 타입이 계약 응답의 **원천**이 되므로 두 자리를 본다.

**[Y-8 · 수정 필요(Major)] `ConversionResult` 가 `model`·`provider_name` 을 버린다 — 계약이 요구하는 필드의 산출처가 사라진다.**
- 근거: `application/.../ConversionResult.kt:47-51`(`ConversionUsage` 는 `llmCalls`·`inputTokens`·`outputTokens` 셋뿐) · `ConvertDocumentUseCase.kt:158-163`(`usage()` 가 세 수만 채운다) · `ConvertDocumentUseCase.kt:145-156`(`complete()` 가 `LlmCompletion` 의 `provider`·`model` 을 읽지 않고 버린다).
- 계약: `contracts/easy-doc-v1.yaml:1699-1755` — `ConversionResponse.required` 에 **`model`·`provider_name` 이 들어 있다**(값은 nullable이나 키는 필수). 완료된 변환에서 둘이 계속 `null` 이면 계약은 형식상 통과하지만 운영 집계·벤더 확정(master-plan 3.1)의 근거가 사라진다.
- 덧붙는 미결: **호출이 2회일 때 어느 호출의 model 을 보고하는가**가 어디에도 없다. 지금 정하지 않으면 Phase 5에서 임의로 정해진다.
- 마감: **Phase 5**(worker가 결과를 저장하는 시점). 수신자 `kotlin-implementer` + `contract-keeper`.

**[Y-9 · 권고(Minor)] `failure_kind` ↔ 계약 `failure_code` 회부는 정확하다 — 다만 계약 문언이 아직 그대로다.**
- 실측: `contracts/easy-doc-v1.yaml:1749-1755` 가 여전히 *"실패 사유 코드 = 예외 클래스명"* 이다. 구현이 요구 수준 이름(`truncated`/`empty_result`/`provider_error`)을 쓰고 `ConversionFailureKind` KDoc(`ConversionResult.kt:13-21`)에 "계약의 `failure_code` 가 아니다"를 명시한 것은 **옳은 처리**다. 지적이 아니라 확인이며, 소유자 `contract-keeper`(인벤토리 §9-E)에게 여전히 열려 있음을 다시 올린다.

**검토함 — 지적 없음**: `missing_placeholders` 라벨 형태(계약 `^\[\[(주민등록번호|카드번호)[0-9]+\]\]$`)와 구현의 자리표시자 생성(`Masking.kt:500`)이 일치. `easy_text` 실패 시 `null` 규약을 하네스가 지킨다(`ConversionParityTest.kt:214-220`).

---

### 2.2 parity 위험 (§4.5 · §4.6)

#### 검증 A — X-2 "음성→양성 17/8" 주장은 **참이다**(독립 재계산)

리더가 검증을 지시한 항목. 구현자 보고를 신뢰하지 않고 **수정 전 패턴 두 개를 그대로 복원해** `MaskingTest.NotationVariants` 25 케이스를 전수 투입했다(스크래치패드, 저장소 미수정).

| 군 | 케이스 | 수정 전 결과 |
|---|---|---|
| 종류 A 검출(4) | 전각 RRN · 성별코드만 전각 · 앞6자리만 전각 · 아라비아-인도 성별코드 | **3건 FAIL** / 1건 PASS |
| 종류 A 과잉 거부(4) | 전각 `９`·`０` 성별코드 거부 | 4건 PASS |
| 종류 B RRN(9) | U+FF0D·2212·2013·2014·2010·00A0·3000·2007·202F | **9건 FAIL** |
| 종류 B CARD(5) | 전각 하이픈·NBSP·전각 공백·엔 대시·전각숫자+전각하이픈 | **5건 FAIL** |
| 개행·CR 미결합(3) | 과잉 마스킹 가드 | 3건 PASS |
| **합계** | 25 | **FAIL 17 / PASS 8** |

**17/8 분할이 정확히 재현된다.** 리더 프롬프트의 요약("통과한 8건은 과잉 마스킹 회귀 가드")은 **한 건 부정확**하다 — 8건 중 7건이 과잉 마스킹 가드이고 1건(`９００１０１-1234567`, 앞 6자리만 전각)은 **미검출 방향 회귀 가드**다. 구현자 보고 §4.2 표는 이것을 정확히 구분해 적었으므로(`앞 6자리만 전각 1건`) **구현자의 기술이 옳고 요약이 뭉갠 것**이다. 지적 아님.

**과잉 마스킹 방향 가드 실재 확인**: 전각 성별코드 `９`·`０` 거부 4건(`MaskingTest.kt` `전각 성별코드 9와 0은 가리지 않는다`)과 개행·CR 미결합 3건(`줄이 갈린 숫자열은 붙이지 않는다`) 모두 실재하고, 코드가 `\s` 를 쓰지 않는다(`Masking.kt:208-211` `SPACE_CHARS` 에 U+000A·U+000D 없음). **두 방향 다 실재한다.**

---

**[Y-2 · 판정 필요] 구분자 반복에 상한이 없어, 새로 넣은 공백 6종이 "표 정렬 텍스트"를 결합 마스킹한다.**

- 근거: `Masking.kt:267` — RRN 패턴이 `\d{6}$SPACE_CLASS*$HYPHEN_CLASS?$SPACE_CLASS*(\d)\d{6}`. `*` 라 **구분자가 몇 개든 통과**한다. `SPACE_CHARS`(`:208-211`)에 U+00A0·U+3000·U+2007·U+202F가 새로 들어왔다.
- 실측(스크래치패드, 새 패턴 그대로):

  | 입력 | RRN 매치 |
  |---|---|
  | `접수 900101` + U+3000×3 + `1234567 끝` | **매치** |
  | `접수 900101` + U+00A0×4 + `1234567 끝` | **매치** |
  | `구간 900101` + U+2013 + `1234567 참조`(엔 대시 범위 표기) | **매치** |
  | `900101` + U+3000 + `-` + U+3000 + `1234567` | **매치** |
  | `접수 900101` + 공백×5 + `1234567 끝`(구 패턴에도 있던 동작) | 매치 |

- **왜 이번 범위의 문제인가**: ASCII 다중 공백 결합은 이전부터 있었으나, 새로 들어온 문자들이 정확히 **hwpx·pdf 추출본이 열 정렬에 쓰는 문자**다 — 저장소 스스로 그렇게 적고 있다(`UnicodeRegex.kt:10-14`: *"hwpx·pdf 추출본에는 전각 숫자(`１２３`)와 NBSP 가 실제로 섞여 들어온다"*). 안내문의 표에서 접수번호 6자리와 관리번호 7자리가 인접 칸에 있으면 하나의 주민등록번호로 결합돼 **두 팩트가 동시에 사라진다.** 과잉 마스킹은 조용하고, STY-03의 절대 팩트축(누락 0건)에 그대로 걸린다.
- **과잉 마스킹 가드가 이 형태를 덮지 않는다** — 현재 가드는 개행·CR 3건뿐이고, "여러 칸 정렬"·"대시 범위 표기" 케이스는 fixture에도 단위 테스트에도 **0건**이다.
- **왜 내가 심각도를 정하지 않는가**: 구분자 확대는 `privacy-gate` 판정 §1.4가 **명시로 지시한 것**이고(하이픈 6종·공백 6종 열거), 반복 상한을 좁히면 그쪽 판정과 충돌한다. 누락 위험(가림 실패)과 과잉 위험(팩트 소실)의 교환은 `privacy-gate` 소관이다.
- 수신자: **`privacy-gate`**(재판정) + `parity-verifier`(가드 케이스). 마감: **Phase 2 종료 전** — 마스킹 fixture 집합이 이 조각에서 굳어지고 있다.

**[Y-1 · 수정 필요(Major)] masking parity 게이트는 켜졌으나, 이번 조각이 고친 바로 그 자리를 값으로 판정하지 않는다 — 그런데 진행표는 `충족 = 예`로 올라갔다.**

- 실측:
  - `parity/fixtures/masking/masking.json` 31건 중 `known_gap` 1건(`masking-known-gap-rrn-fullwidth`)은 `absent` 단언이 **없다**(`restores_input`·`placeholder_scheme` 둘뿐). 구분자 변형(전각 하이픈·NBSP·전각 공백) 케이스는 **0건** — privacy-gate 판정 §1.6이 요구한 신규 7종이 아직 없다.
  - 그 case의 `description`·`known_gap` 문장이 **여전히 실효한 사유를 담고 있다** — *"Kotlin에 Python보다 넓은 구현을 요구하게 되므로"*, *"`privacy-gate` 판정 §5.4의 범위 밖이다"*. 둘 다 2026-08-12 재개발 전환과 privacy-gate 판정으로 무효가 됐다.
  - 결과적으로 **X-2 회귀(패턴이 되돌아감)가 나도 masking parity는 exit 3으로 통과한다.** 회귀를 막는 것은 `MaskingTest` 단위 테스트뿐이다(그것은 CI `kotlin` 잡의 `./gradlew build` 에서 실제로 돈다 — 도달 0은 아니다. 그래서 **차단이 아니라 수정 필요**로 둔다).
- **판정 표기 문제**: `00_progress.md` 「개인정보 마스킹 포팅」 행이 `아니오` → **`예`** 로 바뀌었고, 근거 칸이 X-2 반영을 인용한다. 그러나 privacy-gate 해제 조건 4개 중 **조건 2(fixture 전환·신규 케이스) 미충족**, **조건 4(`docs/golden/` 실문서에서 마스킹 건수가 늘지 않는지 확인) 미실행**(구현자 보고 §9가 골든셋 미실행을 자인)이다. 미해결 칸에 fixture 후속을 적어 둔 것은 정직하나, **행의 `충족` 값이 게이트 판정에 그대로 쓰인다** — `tests/test_harness_scope_reach.py::EXPECTED_MET_YES_KEYS` 에 세 줄이 추가돼(`f73879b..6d8e88c` diff) 이 상태가 가드에 **고정**됐다.
- 수신자: `parity-verifier`(fixture 전환·신규 7종·사유 문장 삭제) · 리더(진행표 `충족` 값의 의미 확정). 마감: **Phase 2 종료 전**.

**[Y-12 · 권고(Minor)] 레거시 시나리오 2건은 Kotlin이 **자기 입력을 지어** 자기 기준으로 통과한다.**
- 근거: `ConversionParityTest.kt:54-61, 143-156` — `repair-call-budget-clean`·`-violations` 는 fixture가 `scenario` 이름만 주므로 하네스가 `LEGACY_SOURCE`·`LEGACY_CLEAN`·`LEGACY_DRAFT_WITH_ISSUE` 를 지어낸다. 게다가 `:70-75` 의 전제 확인이 **Kotlin `checkStyle`** 로 "위반 없음/있음"을 판정한다. 즉 이 두 케이스에서 **판정 기준이 검사 대상 자신에게서 나온다** — Kotlin이 "깨끗하다"고 본 문장을 골라 "호출 1회"를 만족시킨다.
- 완화 요인: 전제 확인이 `check(...)`라 사전이 바뀌면 소리 나게 깨진다. 그리고 명세 §3.1이 "기존 케이스 불변" 원칙으로 이 형태를 허용했다.
- 제안: 다음 fixture 회차에 두 건도 `provider_script` 로 대본화하면 이 자기참조가 사라진다. 수신자 `parity-verifier`. 마감 Phase 2 종료 전(선택).

**[Y-13 · 권고(Minor)] `repair-adoption` 시나리오 15건이 미선언 도메인 `style` 의 정확성에 암묵 의존한다.**
- 근거: `ConvertDocumentUseCase.kt:96`·`RepairDecision.kt:59-60` 이 `checkStyle` 건수로 보정 호출 여부와 채택을 정한다. `parity-domains.txt` 에 `style` 은 없다 — Kotlin `checkStyle` 은 아직 값으로 검증되지 않았다.
- 성격: **조용한 통과가 아니라 오귀속 위험**이다(갈리면 repair-adoption 케이스가 빨개지고 원인은 style에 있다). 명세 §8이 두 질문을 분리한 것과 정합하지만, 진행표·리뷰가 "repair-adoption 충족"을 읽을 때 이 의존을 함께 읽어야 한다.

**[Y-11 · 권고(Minor)] `checkStyle(original)` 이 한 변환에서 두 번 계산된다.**
- 근거: `ConvertDocumentUseCase.kt:96`(`checkStyle(draft)`)과 `RepairDecision.kt:59`(`checkStyle(original)`) — 같은 문자열에 대해 사전 246항 순회 + 정규식을 두 번 돈다. 정확성 문제는 아니다(같은 함수·같은 입력). 성능 판정은 `multi-review` 몫이라 여기서는 기록만 한다.

---

### 2.3 보안 불변식 (§2.3 · CLAUDE.md · `migration-safety-gate` I-항목)

> 판정이 갈리면 `privacy-gate` 가 우선한다. 아래는 그 판정의 **해제 조건 대조**와, 이번 범위가 새로 만든 표면에 대한 지적이다.

**[Y-5 · 수정 필요(Major)] 문서 본문을 감싸는 `@JvmInline value class` 셋 중 둘이 기본 `toString()` 으로 본문을 통째로 찍는다 — 구현자가 연 판정 목록이 불완전하다.**

리더가 검증을 지시한 항목 6("구현자가 스스로 연 판정 3건의 목록이 완전한가")의 답이다. **완전하지 않다.**

- 커밋 `6d8e88c` 기준 실측(`git show 6d8e88c:.../Masking.kt` — `override fun toString` 은 `PlaceholderRestoration`(`:139`) 하나뿐):

  | 타입 | 위치(6d8e88c) | 기본 `toString()` 이 찍는 것 | 구현자 목록 |
  |---|---|---|---|
  | `ModelDraft` | `Masking.kt:626` | 변환 본문 전문 | **올림**(§5.3·§8.1 ③) |
  | `ReviewedBody` | `Masking.kt:640` | 사람 검수본 전문 | **누락** |
  | `MaskedText` | `Masking.kt:54` | **마스킹본 전문** | **누락** |
  | `MaskingResult` | `Masking.kt:95` (data class, `MaskedText` 포함) | 위를 전이로 노출 | **누락** |

- **`MaskedText` 누락이 `ModelDraft` 보다 가볍지 않다.** 마스킹은 주민등록번호·카드번호 **2종만** 가린다 — 전화·이메일·계좌번호는 그대로 남는다(`Masking.kt:144-159`). 그 3종은 **LLM 전송을 감수한 것이지 로그 적재를 감수한 것이 아니다.** CLAUDE.md 보안 규칙은 *"로그에 문서 본문·개인정보를 절대 남기지 않는다"* 로 **본문과 개인정보를 따로** 열거한다(INV-07).
- **왜 이 조각의 지적인가**: 이 범위가 `ConversionResult.Converted.easyText: ModelDraft` 를 만들어 **application 계층에 본문 래퍼를 처음 내보냈고**, `ProvenanceCreationSitesTest` 허용목록에 프로덕션 생성 지점을 처음 올렸다. 표면이 여기서 생겼다.
- **범위 밖 사실(정직하게 기록)**: 리뷰 도중 확인한 **미커밋 작업 트리**에 이미 이 결함의 수정이 들어와 있다 — `MaskedText.toString()` 재정의 + 「value class 와 toString」 절(주석 날짜 `2026-08-14`, privacy-gate 판정 5). **다른 레인이 같은 자리를 이미 잡았다.** 커밋 범위 판정으로는 결함이 맞고, 처분은 그 미커밋 변경이 커밋되면 닫힌다. 2차 교차 종합에서 이 사실을 함께 실어야 codex 쪽 지적과 중복 처리되지 않는다.
- 수신자: `privacy-gate`(판정) · `kotlin-implementer`(커밋). 마감: **즉시**.

**[Y-3 · 수정 필요(Major)] 호출 상한 2가 강제되는 범위는 `Pass` 안뿐이다 — 예산을 타지 않는 경로를 막는 장치도, 탐지하는 장치도 없다.**

리더 검증 항목 2의 답. 우회 경로는 **application 모듈 안에서 실재한다.**

- `CompletionBudget` 은 `internal`(`CompletionBudget.kt:30`) — Kotlin `internal` 은 **Gradle 모듈 경계**이므로 `application` 안 어느 파일에서나 `CompletionBudget()` 을 새로 만들 수 있다. `MAX_LLM_CALLS_PER_CONVERSION` 은 public `const val`(`:12`).
- 더 근본적으로, 예산은 **`Pass` 가 자발적으로 통과시킬 때만** 작동한다(`ConvertDocumentUseCase.kt:148` `budget.spend { provider.complete(...) }`). `LlmProvider` 를 주입받은 다른 클래스가 `provider.complete(...)` 를 직접 부르면 예산은 아예 개입하지 않는다. `LlmProvider.complete` 를 부를 수 있는 지점을 열거·단언하는 상시 장치가 **없다.**
- **비대칭이 지적의 핵심이다.** 같은 조각에서 provenance 래퍼에는 정확히 그 형태의 허용목록 탐지기를 만들었다(`ProvenanceCreationSitesTest`, X-5 조건 2). 호출 상한은 §5 Phase 7의 **즉시 중단 기준**(변환 1건에 3회 이상)인데 그쪽에는 같은 장치가 없다. `ConvertDocumentUseCase` KDoc(`:27`)은 *"완성 요청은 최대 2회다"* 라고 **전역으로 선언**하지만 강제 도달은 이 클래스 하나다.
- 완화 요인: 현재 `provider.complete(` 프로덕션 호출 지점이 **1곳뿐**이고, `LlmProvider` 빈을 주입받는 프로덕션 코드가 아직 없다. 그래서 ②장치 무력화(차단)로 올리지 않는다.
- 제안: `ProvenanceCreationSitesTest` 와 같은 형태로 `provider.complete(`·`CompletionBudget(` 생성 지점 허용목록을 둔다. 마감: **Phase 5**(LLM provider가 프로덕션 경로에 배선되는 시점 — 그때가 이 게이트가 처음 실제로 쓰이는 Phase다). 수신자 `kotlin-implementer`.

**[Y-4 · 판정 필요] "문서 1건당 최대 2회"가 **재시도를 가로질러** 보장되지 않는다 — 누가 지키는지의 정본이 없다.**
- 근거: `ConvertDocumentUseCase.kt:56-59` — `convert()` 호출마다 새 `Pass`, 새 예산이다. KDoc `:32-36` 은 *"재시도 정책 전부를 작업 큐(worker)가 소유한다"* 고만 적는다. 요구는 `00_requirements-inventory.md` CNV-01 = "LLM 호출 **최대 2회**", 즉시 중단 기준은 "**변환 1건**에 3회 이상"(계획 §5 Phase 7 · 명세 §1 실패 등급 표).
- 문제: 일시 오류 backoff 재시도(JOB-03)가 걸리면 같은 문서에 2회×N이 나간다. `PROVIDER_ERROR` 로 1회 쓰고 실패한 작업을 재시도하면 누계가 상한을 넘는다. **"변환 1건"이 `convert()` 1회인지 문서 1건인지 정본이 정하지 않았다.**
- 왜 내가 정하지 않는가: JOB-03의 재시도 정책과 CNV-01의 상한이 만나는 자리이고, 둘 다 아직 미포팅이다. 지금 임의로 정하면 Phase 5에서 되짚는다.
- 수신자: **리더**(요구 해석) → `parity-verifier`(fixture 케이스). 마감: **Phase 5**.

**검토함 — 지적 없음(보안)**

| 항목 | 확인 방법 | 결과 |
|---|---|---|
| 마스킹 선행(INV-01) | `ConvertDocumentUseCase.kt:79` 가 함수의 **첫 문장**으로 `maskText(source)`. provider에 닿는 것은 `LlmPrompt.forConversion(masking.maskedText, ...)`(`:82`)·`forRepair(ModelDraft(draft), ...)`(`:132`)뿐. `ConvertDocumentUseCaseTest.kt:331-345` 가 **두 호출 모두**의 `prompt.user`·`prompt.system` 에 원문 RRN 부재를 단언 | **준수** |
| provenance 규약(INV-01-a) | `Masking.kt:583-613` 규약 절 + 인벤토리 §1 INV-01-a 등재 + `ProvenanceCreationSitesTest` 허용목록. **음성 대조가 구조적으로 내장**돼 있다 — 두 번째 테스트(`죽은 허용 줄이 없다`)가 스캔 0파일일 때 반드시 실패하므로 "경로를 못 찾아 0개 훑고 통과"가 불가능하다. `sourceRoot()` 는 프로퍼티 없으면 던진다(`:152-162`), Gradle이 `test`·`parityHarness` 양쪽에 주입(`build.gradle.kts:132, 163`) | **준수** |
| 실패 코드에 본문 미포함 | `ConversionResult.Failed` 는 enum + 수치뿐(`ConversionResult.kt:101-104`). `failureKind()` 가 **예외 타입만** 본다(`ConvertDocumentUseCase.kt:221-226`) — 메시지 파싱 없음 | **준수** |
| 예외 메시지에 본문 미포함 | `CompletionBudget.kt:42-46` 메시지에 수치·지시만. `AnthropicProviderResponseTest` 에 `hasNoCause()` 3건 추가(X-16) | **준수** |
| X-8 스캐너 CI 배선 | `ci.yml:103-104` — `quality` 잡, `mypy` 직후, `--changed`·`--no-fail` **없음**. 본 리뷰가 직접 재실행: **exit 0**, BLOCK 후보 0 | **준수** |
| X-11 `baseUrl` 해제 조건 1 | `LlmProperties`(`LlmProviderConfiguration.kt:56-61`)에 `baseUrl` 없음. `anthropicSettings()`(`:97-102`)가 넘기지 않음. `EasyDocProperties` 에서 제거됨 | **준수**(조건 3·4는 Phase 5) |

**[Y-14 · 권고(Minor)] `MaskingParityTest` 가 평문 원문을 `parity/actual/` 에 쓴다.**
- 근거: `MaskingParityTest.kt:85` — `"original" to JsonPrimitive(item.original.reveal())`. 현재 fixture 입력이 전부 합성값이고 `.gitignore` 가 `parity/actual/` 을 제외하며 테스트 KDoc(`:65-71`)이 이 사실을 자인한다. **지금은 문제가 아니다.** 다만 CI 실패 시 아티팩트 업로드 스텝이 추가되면 그 순간 유출 경로가 되고, 실문서를 fixture에 넣으면 즉시 평문 개인정보 저장소가 된다. 기록으로 남긴다.

---

### 2.4 Kotlin/Spring 관용성 (§3.1 · §3.2)

**리더 검증 항목 5의 답: 설정 소유권 결정은 모듈 경계와 양립한다 — 확인함.**

| 확인 | 실측 |
|---|---|
| `api`·`worker` 가 infrastructure를 `runtimeOnly` 로만 의존 | `api/build.gradle.kts:13` · `worker/build.gradle.kts:13` — 변경 없음 |
| 그런데 `@Configuration`·`@ConfigurationProperties` 가 스캔에 닿는가 | `ApiApplication.kt:18-19` · `WorkerApplication.kt:18-19` — `@SpringBootApplication(scanBasePackages = ["kr.easydoc"])` + `@ConfigurationPropertiesScan("kr.easydoc")`. **런타임 클래스패스에만 있으면 충분**하므로 양립한다 |
| 소유자 중복 없음 | `EasyDocProperties` 에서 `llm` 제거 확인(diff) — `easydoc.llm` 접두사를 쓰는 클래스가 하나뿐 |
| `application` 이 infrastructure 미의존 | `application/build.gradle.kts` — `api(project(":core"))` 뿐 |
| `core` 가 Spring·DB 미의존 | `core/build.gradle.kts` — `platform()` 은 제약만 추가. `CoreModuleBoundaryTest` 실재 확인(X-17 주석 정정이 가리키는 클래스가 진짜 있다) |
| LLM SDK 타입이 infrastructure 밖으로 안 샘 | `ConvertDocumentUseCase` 가 보는 것은 `LlmProvider`·`LlmPrompt`·`LlmCompletion`(전부 `core`) |

**[Y-7 · 권고(Minor)] `baseUrl` 가드가 **이름 기반 휴리스틱**이고 검사 대상이 클래스 하나다.**
- 근거: `LlmProviderConfigurationTest.kt:79-83` — `LlmProperties::class.java.declaredFields` 의 이름에 `url|uri|endpoint|host|baseurl` 이 있는지만 본다. `target`·`apiRoot`·`server`·`gateway`·`region` 은 통과한다. 그리고 `LlmProperties` 만 본다 — 다른 `@ConfigurationProperties` 클래스가 같은 값을 열면 걸리지 않는다.
- 성격: privacy-gate 해제 조건 1을 **주석에서 실행으로** 옮긴 것 자체는 옳은 방향이고 자바 리플렉션을 고른 사유(`:78`)도 타당하다. 지적은 "선언(설정 표면 전체) 대 도달(한 클래스의 이름 패턴)"의 차이뿐이다.
- 제안: Phase 5의 해제 조건 확인 때 `AnthropicSettings` 생성 지점 허용목록(현재 테스트 1곳)을 함께 단언하면 이름 우회가 닫힌다. 마감 **Phase 5**.

**[Y-16 · 수정 필요(Major)] `repair-adoption` 의 선언과 생산자가 **다른 커밋**이고, 중간 커밋 `ff4c323` 은 컴파일되지 않는다.**

리더 검증 항목 4의 답. **masking은 같은 커밋(23071f2), repair-adoption은 아니다.**

- 실측:
  - `ff4c323` 에 `ConversionParityTest.kt` 가 들어왔다. 그 파일은 `kr.easydoc.core.parity.ParityFixtures` 를 import 하는데, **`ff4c323` 트리에 `ParityFixtures.kt` 가 없다**(`git ls-tree -r ff4c323` 로 확인 — `ParityActual.kt`·`ParityHarnessSelfCheck.kt` 만 있다). `parity.fixtures.dir` 시스템 프로퍼티 배선도 `23071f2` 의 `build.gradle.kts` 에 있다. → **`ff4c323` 는 `:application:compileTestKotlin` 에서 깨진다.**
  - 같은 커밋의 `parity-domains.txt` 선언은 **0개**인데 `parityHarness` 는 `parity/actual/repair-adoption` 을 만든다 → `parityManifestCheck` 가 `[선언 X / 산출 O]` 로 빌드를 깬다.
- 명세가 이것을 명시로 금지했다: `02_parity-verifier_conversion-spec.md` §7-3 — *"`parity-domains.txt` 에 `repair-adoption` 을 적는 커밋과 산출물을 만드는 커밋은 **같은 커밋이어야 한다** — `parityManifestCheck` 가 양방향으로 깨뜨린다."*
- 실질 영향: HEAD(`6d8e88c`)는 정상이므로 런타임 결함이 아니다. 깨지는 것은 **`git bisect`·커밋 단위 되돌리기·커밋 단위 CI**다. 이 저장소는 "그 diff 가 리뷰에 올라가는 것이 최종 방어선"을 여러 게이트의 근거로 삼고 있어, 커밋 단위 무결성이 방어선의 전제다.
- 수신자: `kotlin-implementer`. 마감: **다음 커밋 작성 시 규약 준수**(과거 커밋 rewrite는 권하지 않는다 — 이미 푸시된 이력이면 비용이 이득보다 크다. 리더 판단).

---

### 2.5 테스트 적정성 (§6)

**검토함 — 지적 없음(보장 재배치)**: `repair-adoption` 25건이 값(파일에서 읽은 입력)으로, `ConvertDocumentUseCaseTest` 20여 건이 "왜"로 나뉘어 있고 중복이 아니다. 특히 **fixture가 못 재는 것을 단위 테스트가 맡는 분업이 명시적으로 설계돼 있다** — `루프가 아니다`(`:86-97`)가 응답 10건짜리 **관대한** provider로 `llmCalls==2 && unusedTurns==8` 을 단언하는 것은 엄격 fake(대본 소진 시 사망)로는 잴 수 없는 성질이다. 명세 §5.2의 `repair-loop` 두 변형 논거를 정확히 구현했다.

**검토함 — 지적 없음(실패 경로)**: 1차 4대 예외 4건 · 보정 위치 3건 · 예산 초과 1건 · 자리표시자 유실 3건 — 성공 경로만 있는 모듈이 아니다.

**[Y-6 · 권고(Minor)] `MaskedTextGatewayTest` 의 도달이 **companion 표면**에 한정된다 — 클래스 KDoc은 더 넓게 선언한다.**

리더 검증 항목 1의 답. **X-7은 닫혔다. 다만 도달이 선언보다 좁다.**

- **닫혔다는 근거(추론 + 실측)**:
  - `MaskedTextGatewayTest.kt:70-85` 는 `MaskedText.Companion` 의 비합성 메서드 이름을 `substringBefore('$')` 로 정리해 **`containsExactly("mask")`** 로 단언한다. 이름이 무엇으로 뭉개지든 **항목이 하나라도 늘면 실패**하므로, `internal fun wrap(masked: String): MaskedText` 재도입은 확실히 잡힌다(반환 타입이 value class라 `wrap-<hash>$core` 로 뭉개져도 목록 길이가 2가 된다). 구현자 음성 대조 기록(`[mask, wrap-sDY7kDk]`)과 일치한다.
  - `constructor-impl` 판정도 실측으로 확인했다 — 빌드 산출물에 `javap` 를 걸어 `private static java.lang.String constructor-impl(java.lang.String)` 를 확인했고, `declaredConstructors` 의 비합성 항목이 없어 `LlmPromptTest` 를 복사했다면 **대상 없는 빈 검사**가 됐을 것이라는 구현자 설명이 맞다. 못 찾으면 던지는 처리(`:50-53`)도 옳다.
- **좁은 자리**: 검사 대상이 `Companion` 뿐이다. private 생성자는 **클래스 본문 안에서도** 보이므로, `MaskedText` 자신에 멤버 팩터리를 두면(`internal fun rewrap(s: String): MaskedText = MaskedText(s)`) 두 단언 모두 통과한다. 클래스 KDoc(`Masking.kt:33-34`)은 *"클래스가 여는 유일한 통로가 `Companion.mask` 다 … 감싸기만 하는 통로가 존재하지 않는다"* 고 **클래스 전체**를 선언한다.
- 제안 한 줄: `MaskedText::class.java.declaredMethods` 의 비합성·비-ABI(`*-impl`, `box-impl`, `unbox-impl`, `access$*`) 이름도 허용목록으로 단언한다. 마감 **Phase 2 종료 전**.

**[Y-10 · 권고(Minor)] `ProvenanceCreationSitesTest` 의 회피 표면에 **import alias** 가 빠져 있다.**
- 근거: `:149` — `Regex("""(?<![A-Za-z0-9_])$type\(""")`. `import kr.easydoc.core.privacy.ReviewedBody as RB; RB(초안)` 은 검출되지 않는다. KDoc `:33` 의 "막지 못하는 것" 목록은 리플렉션·문자열 조립·자기 삭제 셋만 적는다.
- 제안: 한계 목록에 한 줄 추가하거나(가장 싸다), `import .* as` 줄을 함께 훑는다.

**[Y-15 · 권고(Minor)] `parity/_harness-selfcheck/kotlin.json` 의 `purpose` 문구가 낡았다.**
- 실측: 파일 내용이 여전히 *"Phase 1 배선 증명 전용. 게이트 판정에 쓰지 않는다."* 인데, `ci.yml:206-218` 이 이 파일의 존재와 `"runtime": "kotlin"` 을 **게이트로 검사**한다. 그리고 이 조각부터 산출물이 실제 판정에 쓰인다. 문구가 게이트의 현재 역할과 어긋난다.

---

## 3. 회부 조치 8건 — 실제로 닫혔는가 (코드·실행으로 확인)

리더 검증 항목 1의 전면 답. **구현자 주장이 아니라 저장소에서 다시 확인한 결과다.**

| # | 회부(cross §6) | 판정 | 확인 근거 |
|---|---|---|---|
| 1 | X-1 masking parity 배선 (차단②) | **닫힘** | `parity-domains.txt` 2줄 + 생산자 2개. 루트 `parityHarness` 가 `parityManifestCheck` 를 `dependsOn` 하므로 CI 스텝(`ci.yml:203-204`)이 **선언 대조까지 실제로 돈다**(`build.gradle.kts:237-242` 확인 — 도달 0 아님). 비교기 독립 재실행 **exit 3 / 56건 / 189단언** |
| 2 | X-7 `MaskedText` 상시 탐지기 (차단②) | **닫힘** (Y-6 좁힘 있음) | `javap` 실측 + `containsExactly` 의미론. `internal fun wrap` 재도입을 실제로 잡는다 |
| 3 | X-8 스캐너 CI 배선 (차단②) | **닫힘** | `ci.yml:103-104` 실측 + 직접 재실행 exit 0 |
| 4 | X-5 provenance (충돌 → 조건부 수용) | **닫힘** | 조건 1(규약 절 `Masking.kt:583-613` + 인벤토리 INV-01-a) · 조건 2(탐지기 + **죽은 줄 검사가 0파일 스캔의 음성 대조 역할**). 회피 표면 1건 미기재(Y-10) |
| 5 | X-2 전각 RRN (충돌 → privacy-gate 판정) | **부분** | 해제 조건 1(구현+가드) **충족** · 3(KDoc) **충족** · **2(fixture 전환·신규 7종) 미충족** · **4(골든 실문서 마스킹 건수 확인) 미실행**. 그런데 진행표는 `충족 = 예` → **Y-1** |
| 6 | X-6 `PlaceholderRestoration` 노출 | **닫힘** | `Masking.kt:139-141` |
| 7 | X-16 cause 미부착 | **닫힘** | `AnthropicProviderResponseTest` `hasNoCause()` 3건 |
| 8 | X-17 `core/build.gradle.kts` 주석 | **닫힘** | `CoreModuleBoundaryTest.kt` 실재 확인 |
| — | (X-3 CNV-01·04 구현, 순위 7) | **닫힘** | 유스케이스 4파일 + parity 25건 충족 |
| — | (X-11 `baseUrl`, privacy-gate 판정 4) | **해제 조건 1 선제 충족** | 3·4는 Phase 5 |

---

## 4. 도달 범위 점검 (다섯 축을 가로지르는 필수 구획)

> 기준 전문은 `kotlin-migration` 스킬 「선언한 범위와 실제 도달을 대조한다」. 지적이 없으면 **"검토함 — 지적 없음"**, 보지 못했으면 **"미검토(사유)"** 로 적는다.

| 점검 항목 | 결과 |
|---|---|
| "전역"·"모든"·"항상" 선언의 미도달 경로 | **지적 3건** — Y-3(호출 상한 "최대 2회" 선언 vs `Pass` 안에서만 강제) · Y-6(`MaskedText` "유일한 통로" 선언 vs companion만 검사) · Y-7(`baseUrl` 설정 표면 vs 한 클래스 이름 패턴) |
| 그 게이트가 **지금 어디서 도는가**(도달 0 의심) | **검토함 — 지적 없음.** 넷을 실행으로 확인: ⑴ `scan_privacy_invariants.py` → `ci.yml` quality 잡(직접 재실행 exit 0) ⑵ `parityManifestCheck` → 루트 `parityHarness` 의 `dependsOn` 으로 CI 스텝에 연결 ⑶ `ProvenanceCreationSitesTest`·`MaskedTextGatewayTest`·`MaskingTest` → `kotlin` 잡의 `./gradlew build` ⑷ parity 비교 → `kotlin` 잡 셸 스텝. **도달 0인 새 장치는 없다** |
| 측정이 **대리 경로**에서 이뤄지지 않았는가 | **검토함 — 지적 없음.** `parityHarness` 는 `test` 소스셋의 런타임 클래스패스를 그대로 쓰고(`build.gradle.kts:154-155`), 산출 경로만 저장소 루트로 바꾼다. 재현 실행에서 실제 산출물이 나온 것을 확인 |
| 검사 **기준이 검사 대상 자신에게서** 나오지 않는가 | **지적 1건 — Y-12**(레거시 시나리오 2건이 Kotlin `checkStyle` 로 자기 입력의 적합성을 판정). 반대로 **잘 막은 자리도 명시**한다 — `ParityFixtures` 가 `assert`·`reference` 를 **아예 노출하지 않아**(`ParityFixtures.kt:89-98`) 생산자가 기대값을 볼 수 없다. 정책 8건의 `accepted` 는 Python `_accepts_repair` 에서 오지만 **`reference`(참고값) 자리이고 판정은 `equals_derived`(비교기 독립 재계산)** 이라 자기참조가 아니다 |
| 판정이 **대리 지표**로 이뤄지지 않는가 | **지적 1건 — Y-1**(`00_progress.md` 행이 `충족 = 예`인데 해제 조건 2·4가 열려 있다. `EXPECTED_MET_YES_KEYS` 로 그 상태가 고정됐다). 반대 방향으로 **정확한 자리**도 기록 — exit 3을 "게이트 통과"로 쓰지 않고 구현자 보고·진행표·CI 배너가 모두 "부분 검증, 전체 통과 아님"으로 적는다 |
| 규칙·패턴의 **범위가 근거보다 넓지 않은가** (은폐형 특히) | **지적 1건 — Y-2**(구분자 집합 확대 자체는 근거가 있으나 **반복 상한이 없어** 근거보다 넓은 결합을 만든다). **은폐형(무시 패턴·억제·면제) 신설 0건** — `.gitignore`·detekt suppression·`type: ignore` 추가 없음(diff 확인). 범위 가드 상수를 무르게 하지 않고 **정체성 키 3줄을 추가**한 처리(`tests/test_harness_scope_reach.py`)는 옳은 방향이나 그 값이 Y-1과 얽힌다 |
| **음성 대조**가 붙어 있는가 | **검토함 — 지적 없음(+독립 재현 1건).** X-2는 본 리뷰가 **직접 재계산해 17/8을 재현**했다(구현자 회신을 신뢰하지 않았다). X-7·X-5는 구현자 음성 대조 기록이 있고 추론·`javap` 로 타당성을 확인했다. `ProvenanceCreationSitesTest` 는 **음성 대조가 구조에 내장**돼 있다(0파일 스캔 시 두 번째 테스트가 반드시 실패). X-8은 privacy-gate 합성 저장소 음성 대조 + 본 리뷰 재실행 |
| 판정하는 코드가 **자기 자신을 검사 대상에 넣었는가** | **검토함 — 지적 없음.** `dump_parity_fixtures.py`·`compare_parity.py`·`scan_privacy_invariants.py` 는 `uv run mypy . .claude` 대상(2026-08-13 이력의 루트 명시 수정 이후). Kotlin 가드 테스트들은 `ktlintCheck`·`detekt`·`test` 대상. `ProvenanceCreationSitesTest` 는 **자기 파일도 스캔한다**(테스트 소스 제외 안 함) |

---

## 5. 차단(Critical) 0건의 근거

심각도를 낮춰 잡지 않았다는 것을 보이기 위해 **①사건·②장치 양쪽을 명시로 기각한다.**

**① 사건** — §5 Phase 7 즉시 중단 기준 6종을 이 범위 코드에서 각각 확인했다.
- *AEAD round-trip/변조* — 대상 코드 없음(Phase 4).
- *타 사용자 노출·404 위반* — 대상 코드 없음(Phase 3).
- *마스킹 전 전송* — `ConvertDocumentUseCase.kt:79` 가 첫 문장이고 단위 테스트가 두 프롬프트 모두 단언. **경로 없음.**
- *중복 LLM 호출·작업 유실* — 큐 미구현. 현재 유일한 호출 경로는 예산을 탄다. 재시도 가로지르기는 **Phase 5의 열린 질문**(Y-4)이지 현재 사건이 아니다.
- *문서 추출·내보내기 필수 정보 누락* — 대상 코드 없음. (Y-2의 과잉 마스킹은 **잠재적 팩트 소실**이나 실문서 실측이 없어 사건으로 못 올린다 — 그래서 판정 필요로 올렸다.)
- *최대 2회 호출 위반* — parity 25건 + 단위 테스트가 값으로 확인. **현재 위반 없음.**

**② 장치** — 검증 없이 통과하는 경로·위조 가능한 증거·검사 0건 성공을 찾았고, **이번 범위에서 새로 만든 것은 없다.**
- 이 범위의 세 장치(`ProvenanceCreationSitesTest`·`MaskedTextGatewayTest`·`LlmProviderConfigurationTest`)는 모두 CI에서 돌고, 앞의 둘은 대상이 없을 때 **통과하지 않고 던지거나 실패한다**.
- 기존 차단 3건(X-1·X-7·X-8)은 §3대로 닫혔고 도달을 실행으로 확인했다.
- **Y-1이 차단에 가장 가깝다**(선언한 masking 검증이 표기 변형에 닿지 않는다). 차단으로 올리지 않은 이유는 하나뿐이다 — 그 회귀를 잡는 `MaskingTest` 가 **CI에서 실제로 돈다**(도달 0이 아니다). 도달이 0이었다면 ②장치로 올렸을 것이다. **리더가 이 판단을 뒤집는 것은 정당하며, 그 경우 마감은 Phase 2 종료 전이다.**

---

## 6. Phase 2 종료 조건 대비 현황

| 인벤토리 항목 | 현황 | 근거 |
|---|---|---|
| INV-01 마스킹 선행(타입 강제) | **충족**(Phase 2 몫) | 타입 강제 + `MaskedTextGatewayTest` + 호출부 단언 |
| INV-01-a provenance 규약 | **충족**(규약·탐지기) / `edited_text` null 유지는 Phase 4 | `Masking.kt` 규약 절 + 허용목록 |
| INV-02 마스킹 범주 2종 | **미충족** — privacy-gate 해제 조건 2·4 미달(Y-1). 범주 자체는 2종 유지 확인 | fixture `known_gap` 잔존 · 골든 실문서 미확인 |
| CNV-01 호출 상한 | **부분 충족** — Phase 2 몫(도메인 로직)은 충족. Phase 5 몫(호출부 강제·재시도 가로지르기)은 열림(Y-3·Y-4) | parity 25건 + 단위 테스트 |
| CNV-02 4대 예외 | **충족**(문자열 정본 §9-E 제외) | parity 시나리오 15건 |
| CNV-04 보정 채택 | **충족** — 단, 본문 손실 축 부재는 **리더 판정 대기**(갈림 후보 ①) | parity 정책 8건 |
| STY-01·02 스타일·사전 | **미충족** — `style`·`style-tables` 미선언 | `parity-domains.txt` |
| DOC-05·06 내보내기·후처리 | **미충족** — `export`·`postprocess` 미선언 | 같음 |
| **Phase 2 종료 조건**(8도메인 전부 값 비교, exit 0) | **미충족 — 2/8 선언, exit 3** | 재현 확인 |

**Phase 2를 닫을 수 없다.** 열려 있는 것: 도메인 6개 미선언 · INV-02(Y-1) · 갈림 후보 ①②(리더·contract-keeper 판정 대기) · §9-E 실패 코드 정본.

---

## 7. 미실행·확인 불가

| 항목 | 사유 |
|---|---|
| `./gradlew build` 재실행 | **미실행.** 작업 트리에 다른 레인의 미커밋 변경 5건이 있어(§1) 지금 빌드하면 **리뷰 범위가 아닌 상태**를 재는 것이 된다. 구현자 보고의 `BUILD SUCCESSFUL` 을 **근거로 채택하지 않았고**, 대신 컴파일 산출물(`core/build/classes`)에 `javap` 를 걸어 필요한 사실만 확인했다 |
| parity 산출물의 생성 주체 | 디스크의 `parity/actual/` 은 **구현자 실행분**이다. 본 리뷰가 비교기를 다시 돌려 수치를 재현했지만 "그 산출물을 Kotlin이 이번에 만들었다"를 독립 증명하지는 않았다(명세 §8이 지적한 한계 그대로). 다만 `MaskedText.toString()` 만 바뀐 미커밋 변경은 마스킹 **동작**을 바꾸지 않으므로 수치는 커밋 상태와 동일하다 |
| `tests/golden` 영향 | **미실행.** 구현자도 미실행이며 사유(프롬프트·스타일·사전 무변경)는 타당하다. 다만 **Y-2·X-2 해제 조건 4가 요구하는 "실문서 마스킹 건수 미증가" 확인은 여전히 열려 있다** — `docs/golden/` 실문서에 NBSP·전각 공백 정렬이 있으면 마스킹 건수가 는다 |
| `-m llm` 레인 | 범위 밖(키 없음) |
| contract test | **존재하지 않는다.** 계약 준수 축은 코드 대조로만 봤다 |
| 트랜잭션 경계·Flyway·`JdbcClient`·Testcontainers | **대상 코드 없음**(미검토가 아니다) |
| 범용 품질 축(성능·유지보수성) | **범위 밖.** `checkStyle` 이중 계산(Y-11) 등은 `multi-review` 를 별도로 돌릴 것을 리더에게 권고한다 |
| codex 리뷰 | **1차 회차이므로 부재가 정상.** 찾지도 읽지도 않았고 실패로 기록하지 않는다 |

---

## 8. 리더에게

1. **2차 교차 종합 재호출이 필요하다.** 이 파일과 `08_conversion-usecase_codex-reviewer.md` 를 입력으로 `..._cross.md` 를 만들어야 게이트 판정 근거가 선다. **1차 산출물만으로 Phase 종료를 보고하지 않는다.**
2. **판정을 요청하는 것 2건** — Y-2(과잉 마스킹 방향 재판정, `privacy-gate` 우선권) · Y-4(재시도를 가로지르는 상한의 요구 해석).
3. **이전 회차에서 넘어온 미결이 그대로 열려 있다** — 갈림 후보 ①(채택 판정식의 본문 손실 축, **리더**) · ②(`failure_code`, `contract-keeper`). 답이 오지 않았다는 이유로 닫지 않았다.
4. **Y-5는 다른 레인이 이미 작업 트리에서 잡았다.** 2차 종합에서 이 사실을 함께 실어야 codex 지적과 중복 처리되지 않는다.
5. **Y-1의 심각도를 차단으로 올릴지 판단해 달라.** 내 판단은 수정 필요(마감 Phase 2 종료 전)이고 근거는 §5에 적었다.
