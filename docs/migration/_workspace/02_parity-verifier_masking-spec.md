# masking 도메인 명세 (Phase 2) — 요구 성질 기준

작성: `parity-verifier` (2026-08-12)
1차 개정: 2026-08-12 — 유니코드 커버리지 공백을 닫음 (14 → 22 케이스, **값 동일성 전제**)
**2차 개정: 2026-08-12 — 전제 전환 + 범주 축소로 전면 재작성 (22 → 23 케이스)**
수신: `kotlin-implementer`
fixture: `parity/fixtures/masking/masking.json` — 23 케이스, 생성기 `dump_parity_fixtures.py::build_masking`
참고 Python: `app/privacy/masking.py::mask_text` — **정답이 아니라 참고값이다**

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

- `MaskedItemResponse.category` — `enum: ["주민등록번호", "카드번호"]`. **값이 한국어 문자열이다.** Kotlin enum 이름(`RRN`)이나 영문 코드(`"phone"`)를 직렬화하면 계약 위반이자 전건 불충족이다.
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
| `masking-known-gap-rrn-control-char` — 숫자 사이 NUL | **못 잡는다** (마스킹 앞에 `strip_control_chars`가 없다) | 회피 벡터로 볼 수도, 편집기 흔적으로 볼 수도 있다. 판단은 `privacy-gate` 소관이고 결론이 나면 단언을 추가한다 |

두 케이스는 구조 불변식만 진다. **어느 방향도 막지 않으므로 개선해도 되고 안 해도 된다** — 다만 개선하면 원장 갱신이 필요하다.

### 2.5 의도한 갈림

`masking-scope-out-*` 3건은 `reference_divergence: "expected"`로 선언되어 있다. 현재 Python은 전화·이메일·계좌를 가리므로 참고값과 **갈리는 것이 정상**이다. 원장을 요구하지 않는다.

반대로 이 3건이 참고값과 **같아지면** 종료 코드 1이 난다 — 선언이 낡았다는 뜻이다(Kotlin이 5범주를 옮겼거나, Python이 따라 축소됐거나).

---

## 3. 마스킹 앞에 아무것도 넣지 마라

제어문자 제거(`strip_control_chars`)·유니코드 정규화(NFC/NFKC)·트림을 마스킹 **앞에** 넣으면 안 된다.

- NFKC는 전각 숫자를 ASCII로 접어 `masking-card-unicode-digit-fullwidth`의 `original` 값을 바꾸고 `restores_input`을 깨뜨린다.
- 어떤 정규화든 입력을 바꾸면 `restores_input`이 즉시 깨진다 — 복원 결과가 원문과 달라지기 때문이다.
- 입력 문자열은 정규식이 준 `start`/`end`로만 잘라야 한다. **코드포인트 오프셋으로 변환하지 마라** (§4.2 참고).

`nfc` 정규화는 fixture 비교 단계에만 적용되며 **구현이 호출할 것이 아니다.**

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
- 23 케이스 **전부** 있어야 한다. 하나라도 빠지면 "미실행"으로 종료 코드 1.
- 케이스 id 중복 금지.

### 5.2 하네스

`backend-kotlin/core/src/testFixtures/kotlin/kr/easydoc/core/parity/ParityActual.kt`의 `ParityActual.write(domain = "masking", fileName = "masking.json", cases = …)`.

- **fixture를 읽어서** 입력과 id를 가져와라. Kotlin 소스에 입력 문자열을 다시 적으면 "양쪽이 같은 입력을 받았다"는 전제가 검증되지 않는다.
- `@Tag("parity")`를 붙여야 `parityHarness` 태스크가 집는다.
- `backend-kotlin/parity-domains.txt`에 `masking` 한 줄을 **같은 커밋에서** 추가한다. 선언만 하고 산출물이 없거나 그 반대면 `parityManifestCheck`가 빌드를 깬다.

---

## 6. Definition of Done

1. `parity/actual/masking/masking.json`이 `parityHarness` 실행으로 생성된다 (`runtime: kotlin`, 23 케이스).
2. `backend-kotlin/parity-domains.txt`에 `masking`이 **같은 커밋에** 들어간다.
3. 아래가 종료 코드 **3**(부분 검증 통과)으로 끝난다:
   ```bash
   uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
       --fixture parity/fixtures --actual parity/actual --only-domain masking
   ```
   마지막 줄이 `부분 검증 통과(게이트 아님): … 성질 판정 23건(단언 89개) … 불충족 0건`이어야 한다.
   **종료 코드 0이 나오면 통과가 아니라 비교기 계약 위반이다** — CI가 그 경우를 실패로 잡는다.
4. `참고 갈림`이 선언된 3건(scope-out) 외에 남으면 **왜 갈렸는지 한 줄과 함께** `--record-reference`로 원장을 갱신하고 커밋한다.
5. Kotlin 테스트가 fixture 파일을 **읽어** 입력을 얻는다 (하드코딩 금지).

Phase 2 종료 조건은 이 도메인 하나로 닫히지 않는다. 선언한 도메인 전부가 종료 코드 0인 전체 게이트 실행을 내야 한다.

---

## 7. 스탠드인 실증 (2026-08-12, 23 케이스)

Kotlin 구현이 없으므로 있을 법한 포팅을 Python 위에서 흉내 내 게이트에 넣었다.

| 스탠드인 | 흉내 낸 것 | 종료 코드 | 지목 |
|---|---|---|---|
| `scope2` — RRN·CARD만, 유니코드 `\d` | 요구사항대로 포팅 | **3** (통과) | 0건 |
| `java-default` — `\d`=`[0-9]` | 플래그 안 켠 포팅 | 1 | 3건 |
| `python-verbatim` — 현재 Python 5범주 | **옛 기준의 "정답"** | 1 | 3건 (scope-out) |
| `over` — 전문을 하나로 가림 | 과잉 마스킹 | 1 | 22건 |
| `nomask` — 아무것도 안 함 | 마스킹 누락 | 1 | 12건 |
| `bad-numbering` — 전역 카운터 | 번호 매김 오류 | 1 | 1건 |

세 번째 줄이 이번 개정의 요지다. **Python을 그대로 옮기면 게이트가 막는다.**
