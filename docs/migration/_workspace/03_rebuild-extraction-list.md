# 재개발 전 추출 목록 — 코드에만 있는 판단

**상태:** 1차본 · 조사 결과 (deep-reasoner, 2026-08-12)
**만든 이유:** 방향이 "전환(포팅)"에서 "재개발(rebuild)"로 바뀌었다(계획 변경 이력 2026-08-12). Python 런타임은 참고 구현도 아니고 **폐기 대상**이다. 그런데 지난 스프린트가 **실측으로 튜닝한 값**과 **코드에만 적힌 설계 의도**가 있고, Python을 지우면 그 판단이 조용히 사라진다. 이 문서는 "지우기 전에 명세로 옮겨야 할 것"의 목록이다.

## 이 문서의 무게 — 왜 지금 이게 실제 작업량인가

전환(포팅) 프레임에서는 빠뜨려도 회복 가능했다. Python이 남아 있어 차분 비교(계획 §1.1 방어 2)나 Python suite(방어 3)로 "달라졌다"를 뒤늦게라도 알 수 있었다. **재개발에서는 그 그물이 없다.** Python을 지운 뒤 명세에 없는 동작은 되찾을 방법이 없다 — 코드가 사라졌기 때문이다. 계획 §1.1이 "인벤토리에 오르지 못한 동작은 놓친다"고 적은 잔여 위험이, Python 폐기 시점에 **영구 손실**로 바뀐다.

따라서 두 가지가 이 전환의 실제 작업량이다.

1. **명세화(extract)** — 아래 목록의 각 항목을 인벤토리 상세·fixture·이 문서 중 하나로 옮긴다.
2. **폐기 게이트(delete-gate)** — Python 코드 삭제(계획 §5 Phase 8)는 이 목록의 전 항목이 "옮겨졌음"으로 닫힌 뒤에만 착수한다. 이것이 재개발 계획에서 Phase 8이 "결과"가 아니라 **선행 조건이 붙은 단계**인 이유다.

## 인벤토리·fixture와의 관계

- `00_requirements-inventory.md`는 **전역 요구사항(무엇을 보장하는가)**을 담는다. 이 문서는 그 요구사항의 **도메인 상세(정확한 상수·프롬프트 전문·튜닝된 판단)**를 담는다. 인벤토리 §읽는법이 "도메인별 상세 항목은 각 Phase 착수 시 추가한다"고 미룬 바로 그 상세다.
- **de facto 명세로서의 fixture.** `parity/fixtures/{도메인}/*.json`은 Python 출력에서 뽑혔다(`dump_parity_fixtures.py`). 재개발에서 "Python이 정답"은 폐기됐지만, **결정적 기계 동작** 도메인(마스킹 정규식·자리표시자 번호·치환 비문 검출·내보내기 바이트·텍스트 정규화)은 Python 출력이 곧 튜닝 결과이므로 그 `expected`를 **명세로 동결**해도 된다. 이때 fixture의 provenance를 "Python 출력"에서 "명세 앵커(Python이 씨앗)"로 다시 읽는다. **품질 도메인(골든셋 통과율·judge 점수)은 예외** — Python 수치를 동결하면 안 된다(현재 실패 상태를 합격으로 굳힌다, `02_quality-baseline.md` §9-2). 이 구분을 항목마다 표시했다.
- **실행 명세로서의 테스트.** 아래 여러 항목은 `tests/easyread/*`·`tests/golden/*`가 큐레이션 규칙을 **기계 검증**한다. 가장 값싼 충실한 추출은 종종 "테스트가 단언하는 불변식을 명세로 전사하거나, 같은 fixture로 Kotlin 테스트를 다시 세우는 것"이다. 단 `tests/**`도 재개발에서 지워지므로, 지우기 전에 **읽어서 전사**해야 한다(테스트 자체가 코드-온리 명세다).

---

## A. 프롬프트 전문 — `app/easyread/prompts.py` (299줄)

**지금 어디에만 있나:** 전량 코드-온리. 계획 §4.6이 "현재 문안에서 출발하되 필요하면 고친다"고 방식만 정했고 **문안 자체는 어디에도 옮겨진 적 없다**. 인벤토리 STY-01이 존재를 가리키지만 내용 없음. parity `prompts` 도메인은 미생성.

**어떤 형태로 명세화해야 하나:** 프롬프트 전문은 "스타일 규칙 SSOT를 순회해 생성"되므로(아키텍처 규칙 4) 순수 데이터가 아니라 **조립 로직 + 고정 문안**의 혼합이다. 명세는 (1) 고정 문안 블록 전문, (2) 조립 순서·헤더, (3) SSOT 순회 규칙을 함께 담아야 한다. 재개발에서 프롬프트를 **개선할 수 있으나**(계획 §4.6), 개선하려면 먼저 현재 문안이 무엇을 하는지 기록돼 있어야 한다.

| 블록 | 무엇 | 잃으면 |
|---|---|---|
| `_ROLE` / `_REPAIR_ROLE` | 역할 지정("발달장애인 등 정보소외계층", 보정=편집자) | 대상 독자 지정이 사라짐 |
| `_LENGTH_INSTRUCTION` | 문장 50자·쉼표 2개 재강조 + **괄호 풀어쓰기 규칙** + **숫자 앞뒤 낱말 원문 표기 보존** | "일정 금액"류 뭉뚱그림 방지 지시가 사라짐 |
| `_SPLIT_EXAMPLES` | 손으로 지은 분해 시범 2개 (**과적합 방지로 골든셋 본문 미사용**) | 모델이 긴 문장을 그대로 옮김 |
| `_REPLACEMENT_INSTRUCTION` | **"(뜻: …)" 비-화살표 설계** + 3단계 + 3개 실증 예(부여·접수·배정) | 치환 비문 재발의 근본 방어가 사라짐(아래 C와 직결) |
| `PLACEHOLDER_INSTRUCTION` | 자리표시자 보존(괄호 안 예시가 최다 실수 자리) | 검수 원문 복원이 깨짐(INV-03) |
| `_SELF_CHECK_INSTRUCTION` | 출력 전 6항목 자가 점검 | 확률적 위반이 그대로 출력됨 |
| `_CONDITIONAL_INSTRUCTION` | PROMPT_ONLY_WORDS 조건부 치환 지시 | 정상 동사 활용을 잘못 치환 |
| `INJECTION_GUARD` | 문서 내 지시문 무력화 | 프롬프트 인젝션 |
| `_OUTPUT_INSTRUCTION` | 머리말·코드펜스 금지 | 후처리(H) 부하 증가 |
| `_REPAIR_INSTRUCTION` | "지적된 문장만" 표적 수정 제약 | 전면 재작성이 통과 문장까지 흔듦 |
| 조립 함수 | `build_system/user/repair_prompt`의 **블록 순서·`[…]` 헤더·난수 id 구분자**(`secrets.token_hex(6)`, 문서/변환 양쪽) | 인젝션 방어와 재현성 |
| `_render_violations` | 문장 단위 묶기·사유 중복 접기·뜻풀이 한 줄·정렬 (보정 입력 토큰 절감) | 보정 호출 입력이 부풀어 비용 상한 압박 |

**받을 곳:** 인벤토리 STY-01 상세 + parity `prompts` 도메인(전문 대조). **실행 명세:** `tests/easyread/test_prompts.py`(319줄)가 조립·필터링을 단언한다 — 지우기 전 전사.

---

## B. 스타일 규칙 상수 — `app/easyread/style_rules.py` (677줄, 아키텍처 규칙 4의 SSOT)

**지금 어디에만 있나:** 전량 코드-온리. 계획 §4.6이 "246개 어려운 말 사전은 데이터를 그대로 옮긴다(자산 이전)"고 했으나, **사전 값에 박힌 큐레이션 판단은 주석에만 있다**.

| 항목 | 무엇 | 추출 난도 |
|---|---|---|
| `MAX_SENTENCE_CHARS=50`, `MAX_COMMAS_PER_SENTENCE=2`, `_COMMA_CHARS`(반각·전각·모점 3종) | 임계값 SSOT — 프롬프트에 f-string으로 박혀 채점과 갈라지지 않음 | 낮음(값) — 단, "왜 50인가"는 `02_quality-baseline.md` §7 선택2에 대가 분석 있음 |
| `DIFFICULT_WORD_REPLACEMENTS`(246키→뜻풀이) | 사전 자산 | 낮음(데이터) — 그대로 이전 |
| **사전 큐레이션 규칙 3개**(주석 27–51행) | ① 부분 문자열 오탐 회피 ② 자기 세탁 방지(치환값에 다른 키 금지, "-하기" 값 금지) ③ 키끼리 부분 문자열은 의도한 쌍만 | **높음** — 이걸 모르면 사전 확장 시 오탐/무한 보정이 재발 |
| **제외·감사 판단**(주석) | 오탐으로 뺀 15낱말(인지·경우·미만·이내·이상·세대·초기·수급·당해·상이·연기·상한·휴대·노후·전원·검진·이행) + 값 감사("적음"→동음이의로 뺌, "줌"→한 글자로 뺌) | **높음** — 실측으로 얻은 값, 순수 주석 |
| `PROMPT_ONLY_WORDS`(상기·하기·게시·반려·하자) | 자동 채점 제외 + 낱말별 사유 | 중간 |
| `STYLE_PRINCIPLES`(6개) | 원칙 문구 = **프롬프트 소스이기도 함** | 낮음(문안) |
| `_appears_at_word_start` | **낱말 시작 위치 근사**(앞 글자 한글이면 skip) + 과소/과잉 검출 트레이드오프 판단 | 높음 — 한국어 형태소 경계 없음을 다루는 핵심 근사 |
| `split_sentences`·`_SENTENCE_SPLIT`·`_LIST_MARKER` | 문장 분리 + 개조식 마커("1.","가.","①)") 제거 | 중간(정규식) |
| `DOUBLE_PASSIVE_PATTERNS`(되어지·보여지·쓰여지·믿겨지·잊혀지) | 이중 피동 5종 | 낮음 |

**de facto 명세:** parity `style`·`style-tables` 도메인이 상수 표 전체를 덤프 대조(결정적, 동결 가능). **실행 명세:** `tests/easyread/test_style_rules.py`(598줄)가 전 항목을 기계 검증. **받을 곳:** 인벤토리 STY-01·STY-02 상세 + `style`/`style-tables` fixture.

---

## C. 치환 비문(뜻풀이 축자 삽입) 검출 — 실측 튜닝의 핵심 (커밋 `eae75c7`·`0894854`·`a4c9fd9`)

**이것이 목록에서 가장 놓치기 쉽다.** 세 커밋이 문서 020 실측으로 오탐을 제거하고 복합어 앞자리 낱말을 확장했다. 규칙 자체가 "완벽 검출이 아니라 실측 3유형만"이라는 판단이라, **재현율보다 오탐 억제에 튜닝됐다** — 이 균형점이 코드에만 있다.

**지금 어디에만 있나:**
- **서사/스펙 기록:** `docs/plans/2026-08-08-sprint-4.md`(커밋 `5948810`이 55줄 추가 — 미션 GG 스펙·검증). **부분적**이다 — 스프린트 로그이지 durable 명세가 아니고, 2차·3차 튜닝(a4c9fd9·0894854)이 완전히 반영됐는지 미확인.
- **실측 근거:** `docs/quality/2026-08-09-doc020-fidelity-review.md`(오탐/비문 실례의 출처).
- **튜닝된 상수·패턴:** `app/easyread/style_rules.py` — **코드-온리**.
- **회귀 코퍼스:** `tests/easyread/converted_samples.py`(301줄, 0894854가 "회귀 코퍼스 교체") + `tests/easyread/test_style_rules.py` — **코드-온리**.

| 상수/함수 | 무엇 | 왜 코드에만 있으면 위험한가 |
|---|---|---|
| `COMPOUND_HEAD_NOUNS`(≈53낱말) | 복합어 앞자리 명사 열거 — **"이 패턴의 주 방어선"**, 명시적으로 **"사전 키에서 유도 불가"** | 부사·시간명사를 넣으면 정상 문장이 비문 판정(B-1 오탐). 열거가 곧 오탐 경계 |
| `LEXICALIZED_GLOSSES`(26) | 명사형이지만 낱말로 굳은 값(이름·밤·알림·돌봄…걸림·깨짐·높임·줄임) 제외 | 넣지 않으면 "돌봄 서비스"·"알림 문자"가 오탐 |
| `COMPOUND_TAIL_KEYS`{기한·기일·정액} | 복합어 뒷자리 키 | 관형구 뜻풀이가 앞 낱말과 붙어 "사용 정해진 날짜" 비문 |
| `NOMINAL_GLOSSES` | **파생**(종성 ㅁ − LEXICALIZED) — 사전에 -ㅁ 값 추가 시 자동 편입, 그래서 테스트가 스냅샷 고정 | 파생 규칙을 모르면 사전 확장이 검출 집합을 조용히 바꿈 |
| `MODIFIER_CHECKED_GLOSSES` | **파생**(한 낱말 NOMINAL 중 다른 뜻풀이의 꼬리가 아닌 것) | 사전 자기모순 방지 로직 |
| `GLOSS_COLLISION_PATTERNS` | 3패턴군(명사형+용언 / 한낱말+체언 / 복합어앞+관형구뜻풀이) | 검출 로직 본체 |
| `_LIGHT_VERB_CHAIN`·`_INLINE_SPACE`·`_NOT_AFTER_HANGUL` | 용언 어미 글자 열거, **줄바꿈 제외**(줄 바뀌면 다른 문장), 낱말 시작만 | 정규식 튜닝 — "이름 하나"의 "하"가 용언으로 안 걸리게 |
| `_is_nominalized`(한글 종성 ㅁ 수학: BASE·28·16) | -ㅁ/-음 종결 판정 | 파생 규칙의 기반 |
| `find_gloss_collisions` | **가장 긴 매치 하나만**(보정 채택 판정의 위반 건수 왜곡 방지) | 같은 결함을 중복 계수하면 `_accepts_repair`가 오판 |
| `SentenceIssue.word` 규약 | 어려운 표현 잔존엔 채우고, **치환 비문엔 비움**(처방이 사전 조회가 아니라 재서술) | 보정 프롬프트가 잘못된 처방을 냄 |

**받을 곳:** 인벤토리 STY-01 상세(치환 비문을 별도 하위 항목으로) + `style`/`style-tables` fixture(패턴·상수 덤프) + **회귀 코퍼스**(`converted_samples.py`의 비문/정상 쌍을 Kotlin fixture로). **주의:** 이 도메인은 결정적이므로 fixture 동결 가능하나, **오탐 억제 균형**이 핵심이라 정상 문장(비-비문) 케이스를 반드시 포함해야 한다(재현율만 보면 오탐이 되살아난다).

---

## D. 골든셋 코퍼스 56건 + 채점 기준

**대부분 이미 `02_quality-baseline.md`에 추출돼 있다.** 그 문서가 인벤토리 §9-A의 "합격 수치가 없다"를 정정했고, 코퍼스 상수·분포·게이트 로직을 실측했다. 남은 것은 **데이터 자체의 보존**과 **미확정 계측기**다.

| 항목 | 지금 위치 | 상태 |
|---|---|---|
| 코퍼스 56 JSON(합성20+실수집36, `facts`/`canonical`/`accept`/`synthetic` 필드) | `tests/golden/documents/*.json` | **데이터 — 그대로 보존.** 원본 문서는 `docs/golden/`(정부 PDF·HWPX). 지우면 안 됨 |
| 코퍼스 스키마 상수 | `tests/golden/test_schema.py`(368줄): MIN_DOCUMENTS=20, MIN_DIFFICULT_WORDS=2, MIN/MAX_FACTS=3/6, MIN_SOURCE_CHARS=500, MAX_SYNTHETIC=1500, MAX_COLLECTED=4000, `PII_BEARING={003:RRN,011:CARD}`, 카테고리 10종, 팩트 제약 5종 | `[기준·기존]` — 코드-온리, 전사 필요 |
| 게이트 임계값 | `tests/golden/test_golden_eval.py`: PASS_RATE=0.90, JUDGE_COVERAGE=0.90, JUDGE_SCORE=4.0 / `app/easyread/judge.py`: DEFAULT_FIDELITY_FLOOR=2 | **인벤토리 §9-A가 "없다"고 오기 — 정정 대상**(quality-baseline §2). 존재하고 강제됨 |
| judge 루브릭(충실성 1–5·이해용이성 1–5 전문), 바닥 게이트 순서(≤2 먼저), JUDGE_MAX_TOKENS=512, 인젝션 방어, 자리표시자 무감점 | `app/easyread/judge.py`(170줄) | 코드-온리 |
| AND 판정식(스타일0 ∧ 팩트0유실 ∧ 자리표시자0유실) | `test_golden_eval.evaluate_rules` | 코드-온리 |
| **미확정 계측기 4종**(축별 허용치·채점자 고정·코퍼스 고정·통과 시 기록) | 저장소 어디에도 없음 | `[미정]` — quality-baseline §2·§7. 재개발이 해결하는 게 아니라 **여전히 열린 채 인계** |

**중요(품질 문제의 소재):** 골든셋 통과율(실수집 52.8%·전체 64.3%)·judge·장문 절벽은 **언어 무관이며 프롬프트·긴 문서 전략의 문제다**(`02_quality-baseline.md`, master-plan §3.3). **이 재개발이 해결하지 않는다.** 재개발 계획은 이 문제를 "Phase 0의 품질 합격선 확정 + Phase 2 이후 프롬프트/긴문서 전략 작업"으로 **이월**할 뿐이다. Python↔Kotlin 어느 언어로 짜도 같은 수치가 나온다. **받을 곳:** 인벤토리 STY-03·STY-04 + `02_quality-baseline.md`(계측기 미확정은 그대로 인계).

---

## E. 마스킹 파이프라인 — `app/privacy/masking.py` (187줄) + `crypto.py`(65줄)

**가장 잘 커버된 도메인.** parity `masking` fixture(22케이스, 유니코드 8건 확장) 생성됨 + 명세 `02_parity-verifier_masking-spec.md` 존재.

| 항목 | 지금 위치 | 상태 |
|---|---|---|
| 2종 축소(주민+카드), 우선순위, 자리표시자 번호 `[[주민등록번호N]]`/`[[카드번호N]]`, 구간 겹침 | `masking.py` + fixture + spec doc | **부분 추출됨** — fixture `expected`를 명세로 동결(결정적). provenance를 "Python 출력"→"명세"로 재읽기 |
| 유니코드 공백 처리(Python `\s`=29종, Java 기본이 놓치는 23종, UCC로도 못 잡는 4종 U+001C~1F) | 명세 §6, fixture 5실증 | 추출됨 — Kotlin 재구현 시 정규식 flag 함정 경고 포함 |

**받을 곳:** 인벤토리 INV-02 상세(이미 있음) + `masking` fixture. 이 항목은 목록에서 **가장 위험이 낮다** — 이미 명세+fixture가 있다.

---

## F. 텍스트 정규화·제어문자 제거

**지금 어디에:** 코드 + `02_privacy-gate_control-char-verdict.md`(판정 기록). parity `text` 도메인 배정됨(미생성).
**무엇:** XML 1.0 비허용 문자만 제거, 탭·개행·복귀 유지. **형태:** 결정적 — fixture 동결 가능. **받을 곳:** `text` fixture + 인벤토리(신규 항목 필요 — 현재 인벤토리에 명시 항목 없음, 아래 §인벤토리 공백 참조).

---

## G. 변환 오케스트레이션 — `app/services/conversion.py` (156줄) — 코드-온리

**지금 어디에만 있나:** 전량 코드-온리. 인벤토리 CNV-01·02·04가 요구사항 수준으로 가리키나 **판정 로직 상세는 없다**.

| 항목 | 무엇 | 받을 곳 |
|---|---|---|
| 최대 2회 호출 강제(변환1+조건부 보정1, 루프 없음, 네트워크 재전송과 분리 계측) | CNV-01 | 인벤토리 CNV-01 상세 + `repair-adoption` fixture |
| 4대 예외 검출·처리(응답 절단·빈 결과·자리표시자 유실·보정 악화) → 정의된 변환 상태 | CNV-02 | 인벤토리 CNV-02 상세 |
| 보정 채택 판정 `_accepts_repair`(위반 건수 비교·자리표시자 유실 가드) | CNV-04 | `repair-adoption` fixture |
| `missing_placeholders` 산출(`conversion.py:115`) — **어느 parity 도메인에도 미배정**(progress Phase 2 표가 지목) | INV-03 관련 | 도메인 배정 판단 필요 |

---

## H. 후처리 — `app/easyread/postprocess.py` (41줄) — 코드-온리
코드 펜스·머리말 제거 시 **과잉 제거 금지**. 결정적 — `postprocess` fixture 동결 가능. **받을 곳:** 인벤토리 DOC-06(있음) + `postprocess` fixture.

## I. 내보내기 — `app/easyread/export.py` (217줄) + `hwpx.py`(223줄) — 코드-온리
파일명 정제, RFC 5987 `Content-Disposition`, 자리표시자 복원, TXT 바이트(BOM 없음·제어문자 제거), HWPX round-trip. **주의:** zip 컨테이너 바이트는 Python과 같아질 수 없음(progress 미결 원장) — **정규화 텍스트로 비교**(바이트 해시 금지). **받을 곳:** 인벤토리 DOC-05(있음) + `export` fixture.

## J. 동적 어려운말 필터링 (커밋 `85ca2f5`, 미션 EE) — 설계 의도
입력에 등장한 낱말만 프롬프트에 싣고, **출력 검사는 246개 전량 기준**. 계획 §4.6이 언급하나 상세 없음. **받을 곳:** 인벤토리 STY-01 상세(A 프롬프트와 함께).

---

## K. 오프라인 도구 — **명세화가 아니라 "이식 vs 폐기" 결정** (리더 판정 4 반영)

리더 판정 4: Python 오프라인 도구는 **최종 폐기**하되, **Kotlin 대체물이 같은 fixture로 검증될 때까지 한시 존치**한다. 영구 oracle로 두자는 기존 계획 §5 Phase 9의 논거는 약하다 — 아키텍처 규칙 4가 프롬프트 생성과 골든셋 평가에 **같은 스타일 정의를 공유**시키므로 채점자 독립성은 지금도 없다. 진짜 독립성은 fixture·명세에서 온다.

| 도구 | 줄수 | 역할 | 처리 |
|---|---|---|---|
| `app/easyread/collection.py` | 1071 | 골든셋 수집 | 한시 존치 → Kotlin 이식 or 폐기 결정 |
| `app/easyread/bokjiro.py` | 392 | 복지로 수집 | 한시 존치 → 결정 |
| `app/easyread/goldenset.py` | 116 | 골든셋 평가 하네스 | 한시 존치 → Kotlin 대체 후 폐기 |
| `app/easyread/judge.py` | 170 | LLM-as-judge | 한시 존치(루브릭은 D에서 명세화) → Kotlin 대체 |
| `scripts/benchmark.py`·`collect_*`·`pilot_report.py`(계획 §5 Phase 9) | — | 벤치마크·리포트 | 한시 존치 → 결정 |

이들은 "코드에만 있는 판단"이라기보다 **분량이 크고(1,000줄+) 재개발 곡선에 얹힌 꼬리 작업**이다. 명세로 옮길 대상이 아니라, Phase 9에서 "이식할지 버릴지"를 건별로 정할 대상이다. 단 judge **루브릭**(D)과 goldenset **판정식**(D)은 도구와 별개로 명세화한다 — 도구를 버려도 합격선 판정은 남아야 하기 때문이다.

---

## L. 문서 추출 보안·정확성 판단 — `app/ingest/extractors.py` (508줄) — 코드-온리 (codex 계획 심사 Q2)

**codex Q2가 지목한 누락.** 이 파일은 **신뢰할 수 없는 입력**을 다루는 보안 모듈이고, 방어의 대부분이 코드·주석에만 있다. 상당수는 `migration-safety-gate` I-10(파서 방어)이 이미 덮지만, **추출 정확성(중복·누락 방지) 판단은 어느 게이트에도 없다** — 지우면 정상 문서 호환이 조용히 깨진다. Kotlin 재구현은 계획 §5 Phase 4(문서 API)다. deep-reasoner가 파일 전문을 훑어 아래를 뽑았다(codex가 든 43-76·232-249·285-293 + 그 밖).

| 판단 | 무엇 | 받을 곳 / 덮는 게이트 |
|---|---|---|
| `MAX_UPLOAD_BYTES=10MB`·`MAX_EXTRACTED_CHARS=500,000`·`_MAX_UNCOMPRESSED_BYTES=5×상한` | 상한 3종. 추출 길이 상한은 **크기 상한만으로 부족**(마크업:본문 비율 극단 → 0.14MB가 900만 자)하고 전체·부분 추출에 **같은 함수로** 강제(`_ensure_extracted_length`) | I-10(값 3종 표에 있음) + DOC-03 |
| **헤더 선언 크기 불신** (`_ensure_zip_within_budget`) | ZipExtFile은 선언 크기·CRC를 **압축 해제 뒤** 검사 → 선언값 위조한 94KB가 힙 141MB를 먼저 먹는다(실측). 믿을 것은 **남은 예산까지 실제 읽은 바이트**뿐. python-docx가 스스로 압축 풀기 **전** 유일 방어선 | I-10 검증3 — **Kotlin `ZipEntry.getSize()` 신뢰가 정확히 이 함정** |
| `_COUNT_CHUNK_BYTES=64KiB` 계수 단위 | 검사는 **바이트 수만** 세면 되므로 조각을 안 들고 있는다 — 한 번에 예산만큼 읽으면 검사가 예산 크기 메모리를 쓴다 | DOC-03 상세(신규) |
| **owner-password 허용·user-password만 거부** (`iter_pdf_pages`:285-293) | `is_encrypted`로 미리 거르지 **않는다**. 인쇄·복사만 막은 소유자 암호 PDF는 `is_encrypted=True`인데 열람 자유(빈 user 암호)라 **공공기관 배포 문서에 흔하다**. 미리 막으면 정상 파일 거부. 진짜 암호 필요 파일만 `FileNotDecryptedError` | **신규 인벤토리 항목 필요** — DOC-03은 "스캔 PDF 거절"만 있고 이 판단 없음 |
| **linked header/footer 중복 방지** (`_docx_blocks`:232-249) | `is_linked_to_previous` 머리글·바닥글은 건너뛴다 — 물려받은 것까지 걷으면 같은 문구가 **구역 수만큼 반복**. 비공개 `_element` 순회(공개 API는 머리글 안 텍스트박스·SDT를 놓침), python-docx 상한 고정으로 업그레이드가 조용히 안 깨게 | **신규 인벤토리 항목 필요**(DOC-01 상세) |
| **`mc:Fallback` 가지치기** (`_element_blocks`:193-229) | Word 2010+는 텍스트박스를 `mc:Choice`(DrawingML)+`mc:Fallback`(VML) **두 벌**로 저장. 둘 다 걷으면 같은 문구가 **정확히 두 번** → 크레딧 2배·마스킹/프롬프트 오염. 스택 하강으로 Fallback에서 멈춤 | **신규 인벤토리 항목 필요**(DOC-01 상세, 비용·정확성 직결) |
| **변경추적 `w:ins` 포함·`w:delText` 제외** (동) | 삽입문은 본문, 삭제문은 `w:delText`라 태그 이름으로 자연히 갈린다. 로컬 이름 판별로 `a:t`(도형)·`m:t`(수식)까지, 문서 순서 순회로 **표가 본문 제자리**에 남음 | **신규 인벤토리 항목 필요**(DOC-01 상세) |
| **OLE2 진단** (`_diagnose_ole2`) | zip 자리에 OLE2가 오면 암호 OOXML(`EncryptedPackage`)·구버전 `.doc`(`WordDocument`)·불명을 UTF-16LE 스트림 이름 바이트 검색으로 갈라 **다른 안내**(olefile 의존 없이) | DOC-02(미지원 요소 명시 실패) 상세 |
| **DTD/XXE를 파서 수준 거부** (`_hwpx_blocks`) | ElementTree는 내부 엔티티를 펼침(billion laughs). `<!DOCTYPE` 바이트 스캔은 **UTF-16이면 뚫림(실측)** → expat `StartDoctypeDeclHandler`(인코딩 무관, 주석 오인 없음). defusedxml 의존 없음 | I-10 검증2(이미 경고) |
| **좁은 예외 포착·예외/로그 개인정보 위생** (`_ZIP_ERRORS`·`_broken`·`_log_failure`·모듈 docstring) | zip 계층 예외만 좁게 잡고 나머지(우리 버그)는 **500으로 드러나야**. 예외·로그에 파일명(그 자체가 개인정보: `홍길동_주민등록등본.pdf`)·본문·라이브러리 메시지 금지, `from None` 체인 끊기, `_broken`은 예외 **타입만** | I-3(로그) — Kotlin `data class toString()`·`logger.error(msg, exc)` 기본값이 이걸 되돌린다 |
| HWPX 구역 번호 정렬·이중 예산(`_read_hwpx_sections`), `_join_blocks` 정규화(빈 줄 제거·개행 하나), 확장자 판별·hwp 명시 거부·CPU 바운드 경고, PDF 쪽 범위(1-based 닫힘·시작>전체=오류·끝>전체=클램프)·스캔 PDF와 손상 구분 | 정확성·안전 세부 | DOC-01/02/03 상세 + fixture |

**주의:** "신규 인벤토리 항목 필요" 4건(owner-password·linked header·mc:Fallback·w:ins)은 **I-10에도 DOC-01~03에도 정확한 판단이 없다.** 지우기 전 인벤토리 DOC-01/03 상세로 올려야 폐기 게이트가 실제로 막는다. Phase 4 착수 시 작성.

---

## M. 인증 보안 판단 — `app/services/auth.py` (284줄) — 코드-온리 (codex 계획 심사 Q2)

**codex Q2가 지목한 누락.** 인증 모듈의 방어 대부분이 코드·주석에만 있다. Argon2·JWT의 **정확성**은 `migration-safety-gate` I-8·I-9가 덮지만(필수조치 A·B 포함), **계정 존재 timing oracle 방지와 몇몇 하드닝은 어느 게이트에도 정확히 없다**. Kotlin 재구현은 계획 §5 Phase 3(인증 API)다. deep-reasoner가 파일 전문을 훑었다(codex가 든 61-67·212-218 + 그 밖).

| 판단 | 무엇 | 받을 곳 / 덮는 게이트 |
|---|---|---|
| **계정 존재 timing oracle 방지** (`_dummy_password_hash`·`login`:211-222) | 사용자가 없어도 검증을 **건너뛰지 않고** 더미 해시로 같은 비용 — 조기 반환하면 응답 시간이 가입 여부를 누설. `@cache` 1회·첫 사용까지 지연(워커·CLI가 64MiB를 안 물게) | **신규 인벤토리 항목 필요** — I-8에 timing oracle 항목 없음(codex가 콕 집음) |
| **Argon2 동시성 메모리 상한** (`_HASH_LIMITER=CapacityLimiter(4)`) | 1건이 `memory_cost=64MiB`를 끝까지 붙듦 → anyio 기본 40스레드면 동시 40건≈2.5GiB→OOM. 실측 한도 4에서 256MiB·지연 안정 | I-8 검증5(이미 경고) |
| **Argon2 파라미터 명시 고정** (`PasswordHasher(3,65536,4)`) | 라이브러리 기본값은 업그레이드에서 **조용히 바뀜** → 검증 비용·저장 형식 변함 | I-8(기본값 의존 금지) |
| **재해시 전체 파라미터 동등성** (`check_needs_rehash`) | Python은 전체 동등, Spring `upgradeEncoding`은 memory·iterations "미만"만 — 파라미터 올리는 날 이관이 조용히 멈춤 | I-8 검증4 = **필수조치 A**(살아 있음) |
| **재해시 best-effort** (`_rehash_if_outdated`) | 재해시 실패가 로그인을 막지 않음 — 파라미터 올린 직후 DB가 흔들리면 재해시 대상 **전원**이 못 들어오는 것 방지. 예외 **타입만** 로깅 | I-8 검증3(이미 있음) |
| **JWT 서명 키 ≥32B 기동 강제** (`MIN_JWT_SECRET_BYTES`·`__init__`:167) | HS256은 ≥32B(RFC 7518). PyJWT는 짧아도 경고만 하고 서명 → 약한 키 조용히 통과. 기동 경로에서 **설정 오류로 끊음**(키 값은 메시지에 없음) | I-9 검증3(이미 있음) |
| **`typ=access` 페이로드 클레임** (`_TOKEN_TYPE`·`resolve_token`:240) | 같은 시크릿으로 서명될 이메일 인증·비번 재설정 토큰이 **액세스 토큰으로 오용되지 않게** 용도를 새기고 대조(JOSE 헤더 `typ`와 별개 네임스페이스) | I-9(‘typ 불일치 거부’는 있으나 **설계 의도**는 코드-온리) |
| **`exp` 필수·검증 예외 위생** (`options.require`·`resolve_token`:246) | `exp` 없으면 영구 토큰 → 필수. 토큰 값을 메시지에 안 담고(토큰=자격증명), `PyJWTError`·`TypeError`·`ValueError`를 **한 예외로** 정규화. `uuid.UUID(sub)`—문자열 아니면 우리 토큰 아님 | I-9 인접(신규 상세) |
| **verify 예외→불리언 정규화**·**가입 원자성·이메일 정규화·입력 에코 금지** | "불일치"vs"해시 깨짐"을 분기하면 사유 누설 → 불리언. 계정+기본 작업공간 **같은 트랜잭션**, 가입·로그인 같은 키(strip+lower), 검증 메시지에 입력값 되풀이 금지 | I-8 인접 + CNV-05/INV-09 인접(신규 상세) |

**주의:** "계정 존재 timing oracle 방지"와 "`typ=access` 설계 의도"는 인벤토리·safety-gate 어디에도 **정확한 항목이 없다.** timing oracle은 codex가 명시 지목했다. 지우기 전 인벤토리(§3 또는 신규 [보안] 항목)로 올린다. Phase 3 착수 시 작성.

---

## 폐기 게이트 요약 (Phase 8 선행 조건)

Python `app/**`·`tests/**` 삭제 전, 아래가 전부 "옮겨졌음"이어야 한다:

- [ ] A 프롬프트 전문 → 인벤토리 STY-01 상세 + `prompts` fixture (+ `test_prompts.py` 전사)
- [ ] B 스타일 상수·큐레이션·감사 판단 → `style`/`style-tables` fixture + 인벤토리 (+ `test_style_rules.py` 전사)
- [ ] C 치환 비문 상수·패턴·회귀 코퍼스 → fixture + `converted_samples.py` 전사 (정상 케이스 포함 확인)
- [ ] D 코퍼스 데이터 보존 + 스키마 상수·게이트 임계값·judge 루브릭 전사, 미확정 계측기 4종 인계
- [ ] E·F·H·I 결정적 도메인 → fixture 동결(provenance 재읽기)
- [ ] G 변환 오케스트레이션 판정 로직 → 인벤토리 상세 + `repair-adoption` fixture, `missing_placeholders` 도메인 배정
- [ ] K 오프라인 도구가 **Phase 8 삭제 대상에서 제외**되고 Phase 9까지 존치됨이 확인 + durable 지식(judge 루브릭·goldenset 판정식 = §D) 명세화. **건별 이식/폐기 결정은 Phase 9 작업이며 Phase 8 선행 조건이 아니다**(순환 의존 제거)
- [ ] L 문서 추출 보안·정확성 판단(`extractors.py`) → I-10에 덮인 것 확인 + **미덮임 4건(owner-password·linked header·mc:Fallback·w:ins)을 인벤토리 DOC-01/03 상세로** 올림
- [ ] M 인증 보안 판단(`auth.py`) → I-8·I-9에 덮인 것 확인 + **미덮임(계정 timing oracle·`typ` 설계 의도)을 인벤토리 [보안] 항목으로** 올림
- [ ] 위 fixture들의 `expected`가 **Python 실행이 아니라 명세**를 근거로 재확인됨(품질 도메인은 동결 금지)

이 체크리스트가 0으로 닫히기 전 Python을 지우면 계획 §1.1의 잔여 위험이 영구 손실로 실현된다.
