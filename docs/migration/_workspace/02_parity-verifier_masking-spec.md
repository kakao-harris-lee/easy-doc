# masking 도메인 명세 (Phase 2) — 요구 성질 기준

작성: `parity-verifier` (2026-08-12)
1차 개정: 2026-08-12 — 유니코드 커버리지 공백을 닫음 (14 → 22 케이스, **값 동일성 전제**)
2차 개정: 2026-08-12 — 전제 전환 + 범주 축소로 전면 재작성 (22 → 23 케이스)
**3차 개정: 2026-08-12 — 게이트 자기승인 경로 차단 + 보이지 않는 문자 단언 전환 (23 → 31 케이스)**
수신: `kotlin-implementer`
fixture: `parity/fixtures/masking/masking.json` — **31 케이스·단언 114개**, 생성기 `dump_parity_fixtures.py::build_masking`
참고 Python: `app/privacy/masking.py::mask_text` — **정답이 아니라 참고값이다**

> **3차 개정 요약 (2026-08-12).** 교차 종합 `reviews/02_criteria-pivot_cross.md`가 지목한 게이트 결함을 닫으면서 이 문서의 **합격 기준도 함께 정정했다.** 2차 개정본의 §6은 "단언 89개"와 "`reference_divergence: expected` 3건 선언"을 합격 기준으로 적었는데 둘 다 사실이 아니었다(실제 81개 / 0건, X-13). 설명문이 아니라 **절차대로 밟으라고 만든 합격 기준**이라, 그대로 두면 밟은 사람이 숫자를 맞추려고 fixture를 손대게 된다. 지금 §6은 실측값으로 고쳐져 있고, 아래 §2.4·§2.5·§7도 3차 기준으로 갱신했다.
>
> 바뀐 것 넷: ① 범주 문자열의 정본이 계약(`contracts/easy-doc-v1.yaml`)이 되고 게이트가 그것을 직접 읽는다. ② 보이지 않는 문자 회피가 `known_gap`에서 `absent` 단언 8건 + 과잉 가드 1건으로 전환됐다. ③ `reference_divergence: "expected"`가 원장 기록을 면제하지 않는다. ④ `--record-reference`가 원장을 바꾸면 종료 코드 4로 끝난다(판정 아님).

---

## 0. 2차 개정에서 무엇이 바뀌었나

초판과 1차 개정은 **"Python과 같은 값을 내라"**는 문서였다. 그 전제가 폐기됐다(사용자 결정 2026-08-12, 상세는 `02_parity-rebase.md`). 이 문서는 이제 **무엇을 만족해야 하는가**를 적는다.

| | 1차까지 | 지금 |
|---|---|---|
| 범주 | 5종 (주민·카드·전화·이메일·계좌) | **2종 (주민등록번호·카드번호)** |
| 판정 | `mask_text` 출력과 값이 같은가 | 요구 성질을 만족하는가 (`assert` 실행) |
| Python 출력 | 기대값 | 참고값 — 갈리면 원장에 기록 |
| "더 잘 잡도록 고치지 마라" | 원칙이었다 | **폐기.** 개선은 허용되고 기록된다 |

**여전히 유효한 것**: §4의 `\d` 실측. 주민번호·카드번호가 숫자이므로 Java와 Python의 `\d` 차이는 그대로 걸린다.
**소멸한 것**: 1차 개정의 `\s` 29종 분석. PHONE 패턴(`[-.\s]`)이 범위에서 빠지면서 축 자체가 사라졌다. RRN의 구분자는 `[ \t]`, CARD는 `[- ]` 리터럴이다.

값의 정본은 fixture이고, 이 문서와 fixture가 어긋나면 fixture가 옳다. **Kotlin 테스트에 기대값을 손으로 옮겨 적지 마라** — 그러면 두 구현이 아니라 두 벌의 사람 해석을 비교하게 된다.

---

## 1. 요구사항

정본: `docs/master-plan.md` §3.2(마스킹 선행) + 사용자 결정(2026-08-12, 범주 축소) + `contracts/easy-doc-v1.yaml`.

> 문서 본문이 LLM으로 나가기 전에 **주민등록번호(외국인등록번호 포함)와 카드번호가 빠짐없이 가려지고**, 그 밖의 본문은 **한 글자도 잃지 않으며**, 자리표시자를 되돌리면 **원문이 정확히 복원된다.**

범주 축소의 근거: 공용 문서 번역이 주 용도라 개인정보 위험이 낮고, 포팅 비용을 줄여 개발 속도를 얻는다.

실패 등급:

| 등급 | 실패 형태 | 결과 |
|---|---|---|
| **즉시 중단** | 마스킹 누락 | 고유식별정보가 그대로 외부 LLM으로 나간다 (계획 §5 Phase 7) |
| **차단** | 자리표시자 번호·형태 어긋남 | 복원이 잘못된 원문을 꽂아 내보내기가 깨진다 |
| **차단** | 본문 손실 (과잉 마스킹) | 사용자는 성공 응답을 받고 팩트가 사라진 결과를 받는다 |

과잉 마스킹의 등급이 올라갔다. 5종 시절에는 "보안 사고는 아니다"였지만, 범주를 좁힌 지금 **범위 밖을 가리는 것은 곧 요구사항 위반**이다.

## 1.1 계약이 못박은 값 (`contracts/easy-doc-v1.yaml`)

계약 레인이 확정했다. fixture와 Kotlin 구현이 **그대로** 따라야 한다.

- `MaskedItemResponse.category` — `enum: ["주민등록번호", "카드번호"]`. **값이 한국어 문자열이다.** Kotlin enum 이름(`RRN`)이나 영문 코드(`"phone"`)를 직렬화하면 계약 위반이자 전건 불충족이다. **3차 개정부터 게이트가 이것을 실제로 읽어 대조한다** — 생성기도 비교기도 계약 파일에서 값을 가져오고, 계약을 읽지 못하면 통과가 아니라 불충족이다. 그전에는 fixture가 스스로 넘긴 값과만 대조해, 생성기가 영문으로 흘러가면 게이트는 통과하고 API는 계약을 위반했다(X-12/S-1).
- `placeholder`·`missing_placeholders[]` — `^\[\[(주민등록번호|카드번호)[0-9]+\]\]$`.
- **전화번호·이메일이 든 문서라도 `masked_items`가 비어 있는 것이 정상이다.**

범주 문자열이 자리표시자에 그대로 박히므로(`[[주민등록번호1]]`) 이 값은 표기가 아니라 **복원 키**다.

---

## 2. Kotlin이 만족해야 할 성질

fixture가 값으로 검사한다. **방법을 지시하지 않는다** — 어떻게 만족시킬지는 `kotlin-implementer`가 정한다.

### 2.1 모든 케이스에 걸리는 구조 불변식

| 검사 | 내용 |
|---|---|
| `restores_input` | `items`의 `placeholder`를 `original`로 되돌리면 **입력과 정확히 같아진다.** 마스킹이 본문을 잃거나 바꾸지 않았고 대응표가 실제로 복원 가능하다는 뜻이다. 내보내기(`restore_placeholders`)가 이 위에 서 있다 |
| `placeholder_scheme` | `[[{범주}{번호}]]` 형식, 범주는 2종 안, **번호는 범주별로 1부터 등장 순서**, `items`의 순서·범주·자리표시자가 본문 등장 순서와 짝이 맞는다 |

`placeholder_scheme`이 옛 문서의 "옮길 때 어긋나기 쉬운 지점" 넷 중 셋(번호 매김, `items` 순서, 형식)을 값으로 대신 검사한다. 남은 하나(겹침 판정)는 `restores_input`이 잡는다 — 구간이 겹치면 복원이 깨진다.

### 2.2 가려야 하는 것 (`absent`)

| 표기 | 케이스 |
|---|---|
| 하이픈 주민번호 `900101-1234567` | `masking-rrn-hyphen` |
| 구분자 없는 13자리 `9001011234567` | `masking-rrn-no-sep` |
| 하이픈 앞뒤 공백 `900101 - 1234567` | `masking-rrn-spaced` |
| 탭 구분 `900101\t-\t1234567` | `masking-rrn-tab` (표 붙여넣기에서 실제로 나온다) |
| 성별코드 5 (외국인등록번호) | `masking-rrn-foreigner` |
| 앞 6자리 아랍-인도 숫자 | `masking-rrn-unicode-digit-head` |
| 하이픈 카드번호 `1234-5678-9012-3456` | `masking-card-hyphen` |
| 공백 구분 카드번호 | `masking-card-spaced` |
| 무구분자 16자리 | `masking-card-no-sep` |
| 아랍-인도 숫자 섞인 카드번호 | `masking-card-unicode-digit-arabic` |
| 전각 숫자 카드번호 | `masking-card-unicode-digit-fullwidth` |

### 2.3 남아야 하는 것 (`present`) — 과잉 마스킹 가드

| 남아야 할 것 | 케이스 | 왜 |
|---|---|---|
| 날짜 `2026-01-01` | `masking-keeps-date` | 안내문의 핵심 팩트다 |
| 12자리·14자리 숫자열 | `masking-keeps-long-digits` | 주민번호도 카드번호도 아니다 |
| 이름·주소 줄 | `masking-newline` | 범위 밖이다 |
| **전화번호 `010-1234-5678`** | `masking-scope-out-phone` | 범주에서 뺐다 |
| **이메일 `kim@example.com`** | `masking-scope-out-email` | 범주에서 뺐다 |
| **계좌번호 `123-456-789012`** | `masking-scope-out-account` | 범주에서 뺐다 |
| 개인정보 없는 문장 전문 | `masking-plain` | 원문 그대로 |

**추가로, `absent`가 붙은 모든 케이스에는 남은 본문 조각에 대한 `present`가 자동으로 따라붙는다.** 생성기가 입력에서 가려야 할 조각을 뺀 나머지를 계산해 붙인다. 이유는 실측이다 — 이 자동 가드가 없을 때 **본문을 통째로 하나의 자리표시자로 가린 스탠드인이 RRN·CARD 케이스 대부분을 통과했다**(23건 중 6건만 실패). 가드 도입 후 22건에서 걸린다.

### 2.4 판정하지 않는 자리 (`known_gap`)

| 케이스 | 현재 Python | 왜 단언하지 않나 |
|---|---|---|
| `masking-known-gap-rrn-fullwidth` — 전부 전각 숫자 주민번호 | **못 잡는다** (성별코드 `[1-8]`이 ASCII 리터럴) | 요구사항으로는 가리는 편이 맞지만, 지금 단언하면 Kotlin에 Python보다 넓은 구현을 요구하게 된다. 개선하면 참고 갈림 원장에 찍힌다 |

~~`masking-known-gap-rrn-control-char` — 숫자 사이 NUL~~ → **3차 개정에서 단언으로 전환됐다.** `privacy-gate` 판정 (가)(`02_privacy-gate_control-char-verdict.md`)가 이것을 실제 위험으로 닫았고, 판정서 §5.4의 지시대로 `known_gap`을 해제했다. `known_gap`으로 두는 동안 이 케이스는 **제어문자가 낀 주민등록번호를 한 글자도 가리지 않은 산출물을 합격시켰다**(재현 확인: 종료 코드 3).

남은 `known_gap`은 **전각 표기 1건뿐**이며 구조 불변식만 진다. 어느 방향도 막지 않으므로 개선해도 되고 안 해도 된다 — 다만 개선하면 원장 갱신이 필요하다. 판정서가 이 건을 범위 밖으로 명시했다.

### 2.4a 보이지 않는 문자 회피 — 단언 9건 (3차 개정에서 추가)

막을 대상은 "제어문자"가 아니라 **숫자 사이에서 보이지 않는 문자 전체**다. C0 제어문자는 docx·hwpx에서 XML 1.0 위반으로 파일째 거부되지만 U+00AD·U+200B·U+FEFF는 XML에 합법이고, PDF와 JSON 붙여넣기 경로는 무방비다. 실문서 16건에서 제어문자 1,000건 이상·"숫자 사이 끼임" 4건이 실측됐다(판정서 M4·M5).

| 케이스 | 문자 | 비고 |
|---|---|---|
| `masking-rrn-soft-hyphen` | U+00AD | **실문서 근거가 있는 유일한 문자.** 우선순위 최상 |
| `masking-rrn-zwsp` | U+200B | 웹페이지 복사·붙여넣기 |
| `masking-rrn-bom` | U+FEFF | 파일 병합 흔적 |
| `masking-rrn-nul` | U+0000 | PDF 추출·JSON 붙여넣기 |
| `masking-rrn-fs` | U+001C | `splitlines()` 경계 |
| `masking-rrn-us` | U+001F | 표 붙여넣기 잔재 |
| `masking-rrn-zwsp-inside-tail` | U+200B | 뒤 7자리 **안쪽** — 앞뒤 경계만 훑는 구현이 걸린다 |
| `masking-card-zwsp` | U+200B | 카드번호도 같은 벡터다 (판정서 M11) |
| `masking-keeps-newline-split-digits` | — | **반대 방향 가드.** 개행으로 갈린 두 숫자열이 붙으면 안 된다 |

구현 제약(판정서 §5.1): **원문을 정규화해 마스킹에 넘기지 마라.** 정규화한 **뷰**에서 찾고, 매치 스팬을 대응 배열로 **원문 좌표**로 되돌려 자른다. 그래야 `original`이 낀 문자를 포함한 채로 잘려 `restores_input`이 유지된다. 대응은 **UTF-16 인덱스 기준**으로 만든다. `UNICODE_CHARACTER_CLASS`를 켜도 이 건은 해결되지 않는다 — 낀 문자는 숫자가 아니라 숫자 **사이**에 있다.

마지막 줄이 없으면 "보이지 않는 문자를 접는다"를 "공백을 전부 접는다"로 구현해도 통과하고, 그 구현은 서로 다른 줄의 숫자를 붙여 진짜 과잉 마스킹을 만든다. 스탠드인 `whitespace-fold`로 실증했다(종료 코드 1, 2건 지목).

### 2.5 의도한 갈림 — 현재 0건

`reference_divergence: "expected"` 선언은 **지금 fixture에 하나도 없다.** `masking-scope-out-*` 3건이 한때 선언돼 있었으나 Python이 2종으로 축소되면서 참고값이 요구사항과 일치하게 되어 선언이 낡았고 생성기에서 지웠다.

**이 필드의 의미도 3차 개정에서 바뀌었다.** 예전에는 "원장을 요구하지 않는다"는 면제였고, 그래서 그 선언이 붙은 케이스는 갈림의 **내용이 바뀌어도** 아무도 몰랐다(X-10/S-2). 지금 이 필드가 하는 일은 **더하기뿐**이다 — 갈림이 사라지면 막고, 원장 기록 요구는 그대로다. 선언이 붙거나 빠지면 원장 항목의 `declared` 값이 바뀌어 그 변경도 diff로 리뷰에 올라간다.

---

## 3. 마스킹 앞에 아무것도 넣지 마라

제어문자 제거(`strip_control_chars`)·유니코드 정규화(NFC/NFKC)·트림을 마스킹 **앞에** 넣으면 안 된다.

- NFKC는 전각 숫자를 ASCII로 접어 `masking-card-unicode-digit-fullwidth`의 `original` 값을 바꾸고 `restores_input`을 깨뜨린다.
- 어떤 정규화든 입력을 바꾸면 `restores_input`이 즉시 깨진다 — 복원 결과가 원문과 달라지기 때문이다.
- 입력 문자열은 정규식이 준 `start`/`end`로만 잘라야 한다. **코드포인트 오프셋으로 변환하지 마라** (§4.2 참고).

`nfc` 정규화는 fixture 비교 단계에만 적용되며 **구현이 호출할 것이 아니다.**

**§2.4a의 회피 차단은 이 규칙과 충돌하지 않는다.** 요구되는 것은 "정규화 **선행**"이 아니라 **"정규화된 뷰에서 찾고, 자르기는 원문 좌표로"**다 — 마스킹 함수 **내부**에서 탐색용 뷰를 만들 뿐 파이프라인 앞단에 단계를 추가하지 않는다. 그래서 마스킹 선행 불변식(`CLAUDE.md` 아키텍처 규칙 2)도, `restores_input`도 그대로 유지된다. 이 설계는 `privacy-gate`가 프로토타입으로 실증했다(fixture 전건 `restores_input` 위반 0건, 회피 6종 전부 차단, 실문서 16건 과잉 마스킹 0건).

---

## 4. JVM에서 갈리는 자리 — 실측

Python에서 실제로 돌려 확인한 값이다(2026-08-12 재확인).

### 4.1 `\d` — Python은 유니코드 십진 숫자를 포함한다

| 입력 | 현재 Python | 요구사항 |
|---|---|---|
| 앞 6자리 아랍-인도 주민번호 (`٩٠٠١٠١-1234567`) | **주민등록번호로 마스킹** | 가려야 한다 (fixture가 단언) |
| 전부 아랍-인도 / 전부 전각 주민번호 | 매칭 없음 | 판정 안 함 (`known_gap`) |
| 아랍-인도 4자리 섞인 카드번호 | **카드번호로 마스킹** | 가려야 한다 |
| 전부 전각 카드번호 | **카드번호로 마스킹** | 가려야 한다 |
| RRN 구분자 자리 NBSP·NUL | 매칭 없음 | 판정 안 함 |

Java 정규식의 `\d`는 기본값이 `[0-9]`다. `Pattern.UNICODE_CHARACTER_CLASS`(또는 `(?U)`)를 켜지 않으면 위 세 줄이 **조용한 마스킹 누락**이 된다 — 즉시 중단 등급이다. **실증**: `java-default` 스탠드인이 정확히 3건에서 종료 코드 1로 걸렸다.

동시에 `[1-8]`(성별코드)은 ASCII 범위로 남아야 한다. `\d`를 넓히느라 이 리터럴까지 넓히면 `known_gap` 케이스가 갈리기 시작해 원장 갱신을 요구받는다 — 막지는 않지만 의도한 변경인지 드러난다.

### 4.2 갈리지 않는 자리

- **UTF-16 인덱스**: 정규식이 준 `start`/`end`로만 슬라이싱하면 Java도 같은 단위를 쓴다. 이모지 인접 입력도 정상. **코드포인트 오프셋으로 변환하는 순간 이 안전성이 깨진다.**
- **lookbehind/lookahead** `(?<!\d)`·`(?!\d)`: 고정 폭이라 의미가 같다. 단 `\d` 범위는 4.1을 따른다.
- **매치 진행 방식**: `finditer`와 `Matcher.find()` 둘 다 왼쪽→오른쪽, 겹치지 않음. 이 도메인에 길이 0 매치는 없다.

---

## 5. Kotlin이 만들어야 할 산출물

### 5.1 경로와 형식

```
parity/actual/masking/masking.json
```

**파일명이 fixture 파일명과 같아야 한다** — 비교기가 `actual_root / fixture_path.relative_to(fixture_root)`로 짝짓는다. `kotlin.json` 같은 이름은 "Kotlin 결과 파일 없음"(종료 코드 1)이 된다.

```json
{
  "runtime": "kotlin",
  "cases": [
    { "id": "masking-plain", "actual": { "masked_text": "…", "items": [] } },
    { "id": "masking-rrn-hyphen", "actual": {
        "masked_text": "주민등록번호 [[주민등록번호1]] 를 확인합니다.",
        "items": [
          { "category": "주민등록번호", "placeholder": "[[주민등록번호1]]", "original": "900101-1234567" }
        ]
    } }
  ]
}
```

- `items`의 각 항목은 `category`·`placeholder`·`original` 세 필드다.
- `runtime`은 반드시 `"kotlin"` (실측: 다른 값이면 종료 코드 1).
- fixture의 케이스 **전부**(현재 31건) 있어야 한다. 하나라도 빠지면 "미실행"으로 종료 코드 1. 개수를 이 문서에서 옮겨 적지 말고 fixture를 읽어 만든다.
- 케이스 id 중복 금지.

### 5.2 하네스

`backend-kotlin/core/src/testFixtures/kotlin/kr/easydoc/core/parity/ParityActual.kt`의 `ParityActual.write(domain = "masking", fileName = "masking.json", cases = …)`.

- **fixture를 읽어서** 입력과 id를 가져와라. Kotlin 소스에 입력 문자열을 다시 적으면 "양쪽이 같은 입력을 받았다"는 전제가 검증되지 않는다.
- `@Tag("parity")`를 붙여야 `parityHarness` 태스크가 집는다.
- `backend-kotlin/parity-domains.txt`에 `masking` 한 줄을 **같은 커밋에서** 추가한다. 선언만 하고 산출물이 없거나 그 반대면 `parityManifestCheck`가 빌드를 깬다.

---

## 6. Definition of Done

1. `parity/actual/masking/masking.json`이 `parityHarness` 실행으로 생성된다 (`runtime: kotlin`, **31 케이스**).
2. `backend-kotlin/parity-domains.txt`에 `masking`이 **같은 커밋에** 들어간다.
3. 아래가 종료 코드 **3**(부분 검증 통과)으로 끝난다:
   ```bash
   uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
       --fixture parity/fixtures --actual parity/actual --only-domain masking
   ```
   마지막 줄이 `부분 검증 통과(게이트 아님): … 성질 판정 31건(단언 114개) … 불충족 0건`이어야 한다.
   **종료 코드 0이 나오면 통과가 아니라 비교기 계약 위반이다** — CI가 그 경우를 실패로 잡는다.

   > 케이스·단언 수는 **fixture에서 읽어 확인한다**. 이 줄의 숫자와 fixture가 어긋나면 fixture가 옳고 이 문서가 낡은 것이다 — 숫자를 맞추려고 fixture를 손대는 것이 정확히 2차 개정본이 유발했던 실패다. 확인 명령:
   > `uv run python -c "import json;d=json.load(open('parity/fixtures/masking/masking.json'));print(len(d['cases']),sum(len(c.get('assert',[])) for c in d['cases']))"`
4. **참고 갈림 0건이 현재 기대값이다.** 선언된 갈림(`reference_divergence`)은 지금 fixture에 **없다** — 2종 축소와 회피 차단이 Python에도 반영되면서 참고값이 요구사항과 일치하게 됐다. 갈림이 새로 남으면 **왜 갈렸는지 한 줄과 함께** `--record-reference`로 원장을 갱신하고 커밋한다. 그 갱신 실행은 종료 코드 4이고 판정이 아니다 — 판정은 플래그 없이 다시 돌린 결과로 한다.
5. Kotlin 테스트가 fixture 파일을 **읽어** 입력을 얻는다 (하드코딩 금지).
6. **자리표시자의 범주 문자열은 계약에서 온다.** Kotlin enum 이름이나 영문 코드를 직렬화하면 게이트가 계약 enum과 대조해 막는다(fail closed — 계약을 못 읽어도 막힌다).

Phase 2 종료 조건은 이 도메인 하나로 닫히지 않는다. 선언한 도메인 전부가 종료 코드 0인 전체 게이트 실행을 내야 한다.

---

## 7. 스탠드인 실증 (3차 개정 재측정, 2026-08-12 — 31 케이스·단언 114개)

Kotlin 구현이 없으므로 있을 법한 포팅을 Python 위에서 흉내 내 게이트에 넣었다. 명령은 전부
`--fixture parity/fixtures --actual <스탠드인> --only-domain masking`이다.

| 스탠드인 | 흉내 낸 것 | 종료 코드 | 지목 |
|---|---|---|---|
| `conformant` — 2범주 + 유니코드 `\d` + 뷰/좌표 대응 | **요구사항대로 포팅** | **3** (통과) | 0건 |
| `scope2` — 위와 같되 보이지 않는 문자 미처리 | 판정 전의 "충분해 보이는" 포팅 | 1 | **8건** |
| `java-default` — `\d`=`[0-9]` | 플래그 안 켠 포팅 | 1 | 3건 |
| `whitespace-fold` — 회피 차단을 공백 접기로 구현 | 과잉으로 넘어간 방어 | 1 | 2건 |
| `over` — 전문을 하나로 가림 | 과잉 마스킹 | 1 | 30건 |
| `nomask` — 아무것도 안 함 | 마스킹 누락 | 1 | 22건 |
| `python-verbatim` — Python 참고값 그대로 | 값 동일성 시대의 "정답" | 3 | 0건 |

두 번째 줄이 3차 개정의 요지다. **단언 전환 전에는 `scope2`가 통과했고, 그 산출물은 제어문자가 낀 주민등록번호를 평문으로 흘렸다.**

마지막 줄은 2차 개정본과 뒤집혔다(1 → 3). 그때는 Python이 5범주였고 지금은 2종 축소 + 회피 차단이 반영됐기 때문이다. **"Python이 정답"이라는 뜻이 아니라 "이 시점의 Python이 이 성질 집합을 전부 만족한다"는 뜻이다.** 이 표를 인용할 때는 측정 시점을 함께 적는다.
