---
name: python-kotlin-parity
description: Python 구현과 Kotlin 구현이 같은 입력에 같은 출력을 내는지 fixture로 증명하는 방법론 — 값 동등성 증명 전용이다. 마스킹·텍스트 정규화·프롬프트 렌더링·스타일 규칙·보정 채택·문서 추출·내보내기·Fernet/JWT/Argon2·골든셋의 동등성을 검증할 때, Phase 2의 "parity suite가 동일 결과를 냄" 종료 조건을 증거로 판정할 때, `parity/fixtures/` fixture를 만들거나 갱신할 때, Kotlin 포팅 결과가 "동작한다"를 넘어 "같은 값을 낸다"를 보여야 할 때 사용한다. 후속 요청("다시 돌려", "재검증", "fixture 업데이트", "이 도메인만 보완", "불일치 다시 확인", "Phase N parity 결과")에도 같은 스킬을 쓴다. 범위 밖 — 품질을 올려 달라는 요청(골든셋 통과율·judge 점수 개선)은 다루지 않는다. 이 하네스의 기준은 현재 Python 동작이지 더 나은 결과가 아니다. Kotlin 구현·수정은 kotlin-spring-conventions, 계약 조항의 정의는 api-contract-freeze, 개인정보·보안 불변식 감사는 migration-safety-gate가 맡는다.
---

# python-kotlin-parity

## 이 스킬이 증명하는 것

`parity-verifier`는 **같은 입력에 같은 출력이 나오는가**를 값으로 증명한다. "Kotlin 쪽에 `maskText` 함수가 있다", "테스트가 초록색이다", "코드를 읽어 보니 로직이 같다"는 증명이 아니다. 포팅에서 실제로 깨지는 것은 함수의 유무가 아니라 경계 조건이다 — 정규식 lookbehind 의미 차이, 한국어 문자열 인덱싱 단위(Python은 코드포인트, JVM은 UTF-16 코드유닛), 사전 순회 순서, 정렬 안정성, 빈 문자열 처리. 이것들은 읽어서는 보이지 않고 실행해 비교해야 드러난다.

증명의 기본 형태는 **경계면 교차 비교**다. 한 쪽(Python)의 실행 결과를 fixture로 굳히고, 다른 쪽(Kotlin)이 같은 fixture를 읽어 자기 출력과 대조한다. 양쪽 산출물을 정규화 후 비교한 결과가 곧 판정이다.

## 핵심 원칙

**1. 기대값은 Python을 실행해서 뽑는다. 사람이 적지 않는다.**
Kotlin 테스트에 기대값을 손으로 쓰면 두 구현이 아니라 두 벌의 사람 해석을 비교하게 된다. `mask_text("9001011234567")`이 구분자 없는 표기를 잡는지, `_appears_at_word_start`가 "소득인정액"의 '정액'을 건너뛰는지 — 사람이 코드를 읽고 옮겨 적는 순간 미묘한 규칙이 사라진다. Python이 정답이라서가 아니라 **현재 운영 중인 동작이 기준**이기 때문이다(계획 §4.6: "품질 개선을 섞지 않는다").

**2. 양쪽 테스트가 같은 파일을 읽는다.**
fixture 파일이 하나여야 동등성 증명이 성립한다. Python 테스트도 같은 fixture를 읽어 "이 fixture가 여전히 현재 Python 동작과 일치한다"를 검증하게 만들어라. 그러지 않으면 Python 코드가 바뀌었는데 fixture가 낡은 것을 아무도 모르고, Kotlin은 옛 동작에 정확히 맞춰진 채 통과한다. 두 방향 모두 있어야 fixture가 살아 있는 계약이 된다.

**3. 모듈이 완성될 때마다 즉시 돌린다. 전체 완성 후 몰아서 하지 않는다.**
불일치는 원인이 하나일 때 고치기 쉽다. 마스킹·스타일 규칙·프롬프트를 전부 포팅한 뒤 한 번에 비교하면, 프롬프트 문자열 하나가 다를 때 그것이 프롬프트 렌더러 탓인지 스타일 규칙 상수 탓인지 문장 분리 탓인지 알 수 없다 — `build_system_prompt`는 `find_difficult_words`를 부르고 그것은 `DIFFICULT_WORD_REPLACEMENTS`를 훑기 때문이다. 게다가 몰아서 하면 통과시켜야 할 압박이 커져 "이 정도 차이는 무시"라는 정규화가 늘어난다. 그것이 검증을 통과 의식으로 만드는 경로다.

**4. 정규화는 늘리는 방향이 아니라 줄이는 방향으로 관리한다.**
정규화를 하나 추가할 때마다 "그 차이가 왜 제품 동작에 무해한가"를 fixture 파일에 근거로 남긴다. 근거를 못 대면 그것은 정규화가 아니라 은폐다.

## 공용 fixture 스키마

경로: `parity/fixtures/{도메인}/*.json`. 생성기가 있는 도메인 11개 = `masking`, `text`, `style`, `style-tables`, `prompts`, `postprocess`, `repair-adoption`, `export`, `crypto`, `jwt`, `argon2`. 계획이 요구하지만 아직 생성기가 없는 도메인 = `ingest`, `golden`.

```json
{
  "domain": "masking",
  "source": "app/privacy/masking.py::mask_text",
  "generator": "dump_parity_fixtures.py",
  "generated_at": "2026-08-11T00:00:00Z",
  "normalization": ["nfc", "lf"],
  "cases": [
    {
      "id": "masking-rrn-hyphen",
      "description": "mask_text 우선순위·번호 매김·구간 겹침 처리",
      "input": { "text": "주민등록번호 900101-1234567 를 확인합니다." },
      "expected": {
        "masked_text": "주민등록번호 [[주민등록번호1]] 를 확인합니다.",
        "items": [{ "category": "주민등록번호", "placeholder": "[[주민등록번호1]]", "original": "900101-1234567" }]
      }
    }
  ]
}
```

필수 필드와 그 이유:

| 필드 | 왜 필요한가 |
|---|---|
| `id` | 케이스 단위 식별자. 불일치 리포트·재현 명령·미실행 탐지가 전부 이 값에 걸린다. 파일 안 순서에 의존하면 케이스를 하나 추가할 때 모든 리포트가 어긋난다. |
| `description` | 이 케이스가 지키는 **행동**을 적는다. 6개월 뒤 이 케이스가 깨졌을 때 "고쳐도 되는 것인지"를 판단할 유일한 단서다. |
| `input` | 함수 인자를 이름 있는 객체로. 위치 인자로 적으면 시그니처가 다른 두 언어에서 짝이 어긋난다. |
| `expected` | Python 실행 결과. **손으로 쓰지 않는다.** |
| `normalization` | 비교 전 적용할 규칙. 케이스에 없으면 파일 수준 값을 상속하고, 케이스 값이 있으면 그것이 이긴다(케이스 우선). |
| `source` | 어느 Python 함수에서 나왔는지. 불일치가 났을 때 `kotlin-implementer`가 대조할 원본 위치이자, Python이 바뀌었을 때 어느 fixture를 다시 뽑아야 하는지의 색인이다. |
| `verification` | **역방향 케이스에만** 붙는다(`{"mode": "external", "script", "proof", "required_cases", "actual_schema"}`). 이 표시가 붙은 케이스는 값 비교로 닫히지 않고 `verify-*`가 남긴 실행 증거 파일로만 닫힌다. 붙이는 것을 잊으면 Kotlin이 기대값을 되받아 적는 것만으로 통과한다. |

**입력은 전부 합성(synthetic)이다.** fixture는 커밋되어 영구히 남는다. 실제 사용자 문서·실제 개인정보·실제 운영 키를 넣지 않는다. `crypto` 도메인의 키는 매 생성 시 새로 만든 테스트 전용 키다.

**바이너리는 fixture 본문에 넣지 않는다.** docx/pdf/hwpx는 `tests/ingest/fixtures/`의 실제 파일을 경로로 참조하고, `expected`에는 추출된 텍스트와 sha256만 담는다. base64로 부풀린 fixture는 diff가 불가능해 리뷰를 못 받는다.

## 정규화 규칙

동등으로 볼 것 — 표기 차이일 뿐 제품 동작이 같은 경우만:

- `nfc` — 유니코드 정규화 형태. 한글은 조합형/완성형이 갈리고, macOS 파일명은 NFD로 들어온다. **어느 쪽으로 통일하는지 fixture에 명시**한다. NFC로 굳히는 것을 기본으로 하되, `export_filename` 같은 파일명 도메인에서는 정규화 자체가 동작이므로 함부로 접지 않는다.
- `lf` — CRLF/CR을 LF로. 플랫폼 개행 차이는 제품 의미가 없다.
- `trim` / `trim_line_ends` — 앞뒤 공백, 줄 끝 공백. 단, **문장 길이 검사(`MAX_SENTENCE_CHARS = 50`)가 걸린 도메인에서는 쓰지 않는다** — 공백 한 칸이 위반 판정을 바꾼다.
- `mask_document_id` — `build_user_prompt`/`build_repair_prompt`가 요청마다 넣는 난수 문서 id(`secrets.token_hex(6)`). 이것은 prompt injection 방어 장치라 값이 같을 수 없다. **그 자리만** 가린다. 프롬프트 전체를 비교 대상에서 빼면 안 된다.
- `float_tol` — 부동소수 비교 허용 오차(기본 1e-9). 현재 도메인 로직에 float 비교가 거의 없지만 토큰 비용·통과율 집계에서 쓰인다.
- JSON 객체 키 순서 — 항상 무시한다. Jackson과 `json.dumps`의 키 순서가 같을 이유가 없고, 순서에 의존하는 소비자가 없다.

**절대 정규화로 눈감아 주면 안 되는 것** (스크립트가 규칙 이름으로 거부한다):

- **자리표시자** `[[전화번호1]]`. 형태·번호·개수가 다르면 내보내기 시 원문 복원(`restore_placeholders`)이 깨지고, `_accepts_repair`의 유실 검사와 `missing_placeholders` 경고가 어긋난다. 개인정보가 붙어 있는 유일한 축이므로 여기서 눈감으면 검증 전체가 무의미하다. 비교 스크립트는 정규화 전후의 자리표시자 목록이 달라지면 그 자체를 오류로 보고한다.
- **문서 본문 내용**. 한 글자 차이도 차이다. 문장 분리(`split_sentences`)와 낱말 시작 판정(`_appears_at_word_start`)이 문자 단위로 동작하므로, "사소한" 본문 차이가 위반 건수를 바꾸고 보정 채택 판정을 뒤집는다.
- **상태 코드·failure code·오류 분류**. 계약이다(§2.2). 404를 403으로, `LLMTruncatedError`를 `LLMEmptyResultError`로 접으면 재시도 정책과 소유권 은닉이 함께 무너진다.
- **목록 순서**. `DIFFICULT_WORD_REPLACEMENTS`의 정의 순서가 곧 프롬프트 렌더링 순서다(`_render_replacements` docstring: "출력 순서는 인자 순서가 아니라 사전 정의 순서"). `check_style`의 issue 순서는 보정 프롬프트의 `[고칠 곳]` 순서가 된다. 정렬해서 비교하면 프롬프트가 달라진 사실을 놓친다.
- **대소문자**. 파일명·확장자 판별(`.suffix.lower()`)·헤더 값에서 실제 동작 차이다.

## 도메인별 검증 절차

각 도메인은 ① fixture로 무엇을 뽑고 ② 어떤 실패가 치명적인지가 다르다.

### masking — `app/privacy/masking.py::mask_text`

뽑을 것: `masked_text`, `items[].category` / `.placeholder` / `.original`.

패턴 5종(`MaskCategory`: 주민등록번호·카드번호·전화번호·이메일·계좌번호)의 **우선순위**와 **구간 겹침 처리**가 핵심이다. `_PATTERNS`는 EMAIL이 최우선이고(지역부 숫자열이 부분 마스킹되어 도메인이 평문으로 남는 것을 막는다), 이미 다른 패턴이 차지한 구간은 건너뛴다. 케이스에 반드시 넣을 것: 구분자 없는 주민번호, 외국인등록번호 성별코드(5~8), 이메일 지역부에 숫자열, 인접한 두 전화번호, 같은 범주 복수(번호가 1,2로 매겨지는가), 빈 문자열.

치명적 실패: 마스킹 **누락**(개인정보가 그대로 LLM으로 나간다 — §5 Phase 7 즉시 중단). 그 다음이 자리표시자 번호 어긋남(복원이 깨진다). 과잉 마스킹은 품질 문제이지 보안 사고는 아니지만 팩트 유실로 이어지므로 함께 잡는다.

JVM 주의: Java 정규식의 lookbehind `(?<!\d)`는 Python과 의미가 같지만, `\d`가 기본으로 유니코드 숫자(아랍-인도 숫자 등)를 포함하는지는 플래그에 달렸다. Python `re`의 `\d`는 유니코드 숫자를 포함한다 — Kotlin에서 `UNICODE_CHARACTER_CLASS`를 켜지 않으면 조용히 갈린다.

### text — `app/text.py::strip_control_chars`

뽑을 것: 제어문자가 섞인 문자열 → 제거 결과. `_CONTROL_CHARS = [\x00-\x08\x0b\x0c\x0e-\x1f\x7f]`이고 탭·개행·복귀는 **남긴다**(문서 구조를 이룬다).

치명적 실패: 탭·개행을 함께 지우면 문단 구조가 무너져 docx/txt 산출물이 한 덩어리가 된다. 반대로 지워야 할 문자를 남기면 docx 내보내기가 lxml `ValueError`로 500이 된다(`app/text.py` 모듈 docstring).

### style — `app/easyread/style_rules.py`

**이 프로젝트에서 가장 자주 바뀌는 파일이다.** 상수 표와 함수 동작을 나눠 fixture로 굳힌다.

- `style-tables` 도메인: 상수 표 전량을 덤프한다. 현재 크기는 `DIFFICULT_WORD_REPLACEMENTS` 246, `PROMPT_ONLY_WORDS` 5, `STYLE_PRINCIPLES` 6, `DOUBLE_PASSIVE_PATTERNS` 5, `LEXICALIZED_GLOSSES` 21, `COMPOUND_TAIL_KEYS` 3, `COMPOUND_HEAD_NOUNS` 38, `NOMINAL_GLOSSES` 86, `MODIFIER_CHECKED_GLOSSES` 34, `MAX_SENTENCE_CHARS` 50, `MAX_COMMAS_PER_SENTENCE` 2. `GLOSS_COLLISION_PATTERNS`는 앞의 세 집합에서 **파생 생성**되므로(현재 123개) 패턴 문자열을 직접 비교하지 말고 생성 규칙과 입력 집합을 비교한 뒤 `find_gloss_collisions`의 결과로 검증한다 — 정규식 문자열은 이스케이프 표기가 언어마다 달라 비교가 무의미하다.
- `style` 도메인: `split_sentences`, `find_difficult_words`, `find_gloss_collisions`, `check_style` 각각의 결과. `check_style`은 `total_sentences`와 `issues[].sentence/.reason/.word`까지 전부 비교한다. `reason` 문자열은 그대로 보정 프롬프트의 지시가 되므로 문구 한 글자가 계약이다.

치명적 실패: 위반 **건수**가 다르면 `_accepts_repair`의 채택 판정이 뒤집혀 최종 산출물이 달라진다. `word` 필드가 비는지 채워지는지도 계약이다 — 치환 비문 위반은 `word`를 비우고(처방이 재서술이라), 어려운 표현 잔존은 채운다.

JVM 주의: `_appears_at_word_start`는 매치 시작 **직전 한 글자**가 한글 음절인지를 본다. `text[match.start() - 1]`은 Python에서 코드포인트 단위지만 JVM `String`은 UTF-16 코드유닛 단위다. 기본 다국어 평면 안의 한글은 문제없지만, 이모지·희귀 한자가 섞이면 인덱스가 어긋난다 — 반드시 그런 케이스를 fixture에 넣는다.

### prompts — `app/easyread/prompts.py`

뽑을 것: `build_system_prompt(masked_text)`, `build_user_prompt(masked_text)`, `build_repair_prompt(text, violations)`의 (system, user) 전문.

시스템 프롬프트는 **동적**이다. 치환 목록 전량이 아니라 `find_difficult_words(masked_text)`가 찾아낸 낱말만 싣고, `PROMPT_ONLY_WORDS`는 항상 싣는다. 따라서 이 fixture는 프롬프트 조립뿐 아니라 스타일 규칙 탐색까지 함께 검증한다 — 어려운 말이 0개인 입력, 1개인 입력, 여럿인 입력을 모두 넣어라.

치명적 실패: 프롬프트가 한 글자라도 다르면 LLM 출력 분포가 달라져 골든셋 결과를 비교할 근거가 사라진다. 계획 §4.6이 "byte-for-byte 또는 정규화 동등"을 요구한 이유다. 특히 렌더 형식 `- {어려운말} (뜻: {풀이})`는 화살표 형식으로 바꾸면 축자 치환 비문이 재발한다는 실측 근거가 있는 결정이다(`_render_replacements` docstring).

정규화: `mask_document_id`만 허용한다. 난수 id는 injection 방어 장치라 같을 수 없다.

### postprocess / repair-adoption

`postprocess`: 코드 펜스·머리말 제거. **과잉 제거가 과소 제거보다 위험**하다는 설계 원칙이 코드에 박혀 있다 — "다음은 심사 결과입니다."처럼 정상 본문이 머리말로 오인되지 않는 케이스를 반드시 넣는다. 펜스↔머리말 순서가 뒤바뀐 경우까지 2회 반복하는 동작도 fixture로 굳힌다.

`repair-adoption` (`app/services/conversion.py::_accepts_repair`): 자리표시자 유실 시 거부, 위반 건수 증가 시 거부, **같은 건수는 채택**. 경계값(같은 건수)이 가장 자주 틀리는 자리다. `original`에 없던 자리표시자는 유실로 보지 않는 것도 계약이다.

치명적 실패: 채택 판정이 뒤집히면 사용자에게 나가는 최종 텍스트가 달라진다. 이것은 "품질 차이"가 아니라 동등성 실패다.

### ingest — `app/ingest/extractors.py`

별도 절 참조: [문서 추출 동등성](#문서-추출-동등성--여기가-가장-위험하다).

### export — `app/easyread/export.py`

뽑을 것: `export_filename`, `content_disposition`, `restore_placeholders`, TXT 렌더 결과(UTF-8 바이트 + sha256).

`export_filename`은 경로 구분자·제어문자를 걷어내고(`_FORBIDDEN_IN_FILENAME`), 앞뒤 점을 제거하고, 80자로 자른다. **80자는 문자 수이지 바이트 수가 아니다** — 한글 한 글자를 3바이트로 세면 자르는 위치가 달라진다. `content_disposition`은 RFC 5987 퍼센트 인코딩이고 ASCII 대체 이름이 `easy-read`로 고정이다. 대문자/소문자 퍼센트 인코딩(`%EA` vs `%ea`)이 갈리기 쉬우니 fixture로 굳힌다.

docx·hwpx는 **바이트 동등이 기준이 아니다.** zip 타임스탬프·엔트리 순서·라이브러리 버전이 바이트를 흔든다. 기준은 (1) 자체 추출기로 다시 읽은 본문이 일치하고 (2) HWPX는 `mimetype` 엔트리가 무압축 첫 항목이며 `application/hwp+zip`이고 (3) 미디어 타입과 파일명이 계약과 같은 것이다. 계획 §2.3이 "HWPX는 최소한 생성 후 자체 추출기로 다시 읽어 본문이 일치해야 한다"고 못박았고, 한컴 오피스 실제 호환성은 사람 검증으로 남는다.

### 자격증명·암호 3개 도메인 공통 — 역방향은 실행 증거가 있어야 닫힌다

§6 Crypto 게이트의 통과 기준은 "Fernet/JWT/Argon2 **양방향** fixture와 tamper test 통과"다. 그래서 이 셋은 `crypto`·`jwt`·`argon2` **세 도메인으로 분리**되어 있다. 한 도메인 안에 접어 두면 Fernet 케이스만 전건 일치해도 "crypto 도메인 통과"로 읽혀 JWT·Argon2를 한 건도 검증하지 않은 채 게이트가 닫힌다 — 실제로 그렇게 닫힌 적이 있다.

**세 도메인이 다 있어야 한다는 것을 이제 스크립트가 강제한다.** 도메인을 통째로 빼고 돌리면 `compare_parity.py`가 종료 코드 1로 끝나며 없는 도메인 이름을 찍는다([기대 도메인 집합을 스크립트가 강제한다](#기대-도메인-집합을-스크립트가-강제한다) 참고). `--only-domain crypto`처럼 범위를 명시해 한 도메인만 돌리는 것은 허용되지만, 그 출력은 "부분 검증 — 게이트를 닫는 근거가 아니다"로 표시되고 마지막 줄이 `전건 일치:`로 시작하지 않는다.

역방향(Kotlin이 만들고 Python이 읽는 방향)은 **값 비교로 닫을 수 없다.** fixture의 `crypto-roundtrip-request`·`jwt-roundtrip-request`는 Kotlin에게 "이 평문/이 subject로 산출물을 만들어라"라고 지시하는 요청 케이스이고, Kotlin이 기대값을 그대로 되받아 적으면 아무것도 실행하지 않고 "일치"가 나온다. 그래서 이 케이스들은 `verification.mode = "external"`로 표시되어 있고, 비교기는 이 표시가 붙은 케이스의 `actual`을 **아예 보지 않는다.** 근거로 인정하는 것은 `verify-crypto`/`verify-jwt`가 남긴 실행 증거 파일 하나뿐이다.

- 증거 파일이 없으면 → **미검증(pending)**. `compare_parity.py`가 종료 코드 **2**로 끝나고 "전건 일치"라고 쓰지 않는다.
- 증거 파일의 `status`가 `pass`가 아니거나, 검증한 건수가 `verification.required_cases`보다 적으면 → **불일치**(종료 코드 1).
- Kotlin 결과 파일에 요청 케이스 id를 적어 넣으면 → **불일치**. 그 자리에 기대값을 베끼는 것이 정확히 이 게이트를 무력화하는 경로다.

미검증을 통과로 세지 않는 이유: 게이트의 목적은 "돌렸다"가 아니라 "호환성이 증명됐다"이다. 미실행을 침묵으로 두면 Phase 4 종료 조건이 거짓으로 닫히고, 그 거짓은 절체 당일 기존 문서를 못 읽는 형태로 드러난다.

### crypto — Fernet (`app/privacy/crypto.py::TextCipher`)

계획 §4.3이 가장 먼저 spike하라고 지목한 게이트다.

- 정방향: Python이 만든 토큰을 Kotlin이 복호화 → 한글·ASCII·빈 값·긴 값·제어문자 전부.
- 역방향: Kotlin이 만든 토큰을 Python이 복호화 → `dump_parity_fixtures.py verify-crypto`.
- 음성 케이스: 변조 토큰(1바이트 flip), 다른 키, Fernet 형식이 아닌 바이트 → **반드시 실패**해야 한다. 여기서 조용히 성공하면 그것은 인증 암호화가 아니고, 변조된 문서가 그대로 사용자에게 나간다.
- `encryption_scheme`·`key_version`을 함께 기록한다(현재 `CURRENT_KEY_VERSION = 1`). Fernet 토큰에는 키 식별자가 없어 이 컬럼이 재암호화 대상을 고를 유일한 단서다.

치명적 실패: 기존 Fernet 문서 복호화 실패는 §5 Phase 7의 첫 번째 즉시 중단 기준이다. 이 게이트를 통과하지 못하면 직접 암호를 조립하지 말고 계획 §4.3의 4항(유지보수 창 재암호화)으로 넘긴다.

### jwt — `app/services/auth.py::AuthService._issue_token` / `resolve_token`

`HS256`(`_ALGORITHM`), 클레임은 `sub`·`exp`·`typ` 셋뿐이다(개인정보 금지). `resolve_token`은 `options={"require": ["sub", "exp", "typ"]}`로 세 클레임을 강제하고, `typ`이 `_TOKEN_TYPE`("access")이 아니면 거부하며, `sub`를 `uuid.UUID`로 파싱한다. 실패 사유는 전부 하나의 `InvalidCredentialsError`로 정규화된다 — 그러므로 fixture의 기대값도 사유를 나누지 않고 `{"outcome": "invalid_credentials"}` 하나다.

**시간 의존성을 반드시 고정한다.** `exp` 검증은 실행 시각에 좌우되므로 벽시계로 돌리면 같은 토큰이 어제는 통과하고 오늘은 실패한다 — 그 순간 parity 검증은 의미가 없다. 그래서:

- 발급 기준 시각(`JWT_ISSUED_AT`)을 고정하고, 실제 `_issue_token`을 그 시각에 고정된 시계 아래에서 돌린다.
- 케이스마다 **`input.verify_at`(epoch 초, UTC)** 을 명시한다. 검증하는 쪽은 그 시각을 주입해 평가해야 한다. Kotlin 하네스도 `Clock.fixed(...)`로 같은 시각을 넣는다.
- 결과적으로 `jwt` fixture는 재생성해도 바이트가 같다(난수 요소 없음). 재생성 diff가 곧 "Python 동작이 바뀌었다"는 신호가 된다.

음성 케이스는 **반드시 거부**돼야 한다. 조용히 통과하면 인증이 뚫린다: 서명 위조(공격자 키), 다른 키, payload만 바꾸고 서명 유지, `alg: none`, 헤더만 RS256으로 바꾸고 HMAC 서명(알고리즘 혼동), `exp`/`typ`/`sub` 누락, `typ` 불일치, `sub`가 UUID가 아님, JWT 형식이 아닌 문자열.

**만료 경계가 가장 자주 갈리는 자리다.** PyJWT는 `exp <= now`를 만료로 본다(`_validate_exp`) — 즉 기준 시각이 `exp`와 정확히 같으면 **만료**다. JVM 라이브러리는 `exp < now`로 보는 것이 많아 여기서 1초가 어긋난다. fixture는 `exp - 1`(유효), `exp`(만료), `exp + 60`(만료) 세 지점을 모두 고정한다.

시크릿 하한도 동작이다. `MIN_JWT_SECRET_BYTES = 32`이고 미달이면 `AuthService.__init__`이 `ConfigurationError`로 기동 경로를 끊는다(PyJWT는 경고만 하고 서명해 준다). fixture는 정확히 32바이트(허용)와 31바이트(`{"outcome": "configuration_error"}`)를 경계로 고정한다.

### argon2 — `app/services/auth.py::hash_password` / `verify_password` / `check_needs_rehash`

현재 파라미터는 `_HASHER = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4)`이며 라이브러리 기본값에 기대지 않고 명시 고정한 값이다.

**salt가 매번 새로 생성되므로 출력 문자열을 비교할 수 없다.** 같은 비밀번호를 두 번 해싱해도 PHC 문자열이 다르다. 그러므로 이 도메인의 fixture는 예외 없이 **검증 방향**으로만 짠다 — "Python이 만든 이 PHC 문자열을 검증하면 어떤 결과가 나오는가". 해시 생성 결과를 기대값으로 넣으려는 시도는 설계 오류다.

케이스가 담는 것:

- Python이 만든 PHC를 Kotlin이 **그대로 검증** — ASCII·한글·긴 비밀번호·빈 값·제어문자 포함.
- 틀린 비밀번호 거부, 변조된 PHC 거부, PHC 형식이 아닌 문자열 거부. `verify_password`는 argon2 예외를 밖으로 흘리지 않고 불리언으로 정규화한다 — "불일치"와 "해시가 깨짐"이 응답으로 구분되면 실패 사유가 새기 때문이다.
- **재해시 판정**: 현재 파라미터 해시 → `needs_rehash = false`, 낮은 파라미터 해시 → `true`. 양쪽 판정이 같아야 한다. 형식이 깨져 파라미터를 읽을 수 없으면 `null`(참도 거짓도 아님)이다.
- 낮은 파라미터로 만든 **기존 해시도 그대로 검증**돼야 한다. 이것이 깨지면 기존 사용자가 전부 로그인하지 못한다.
- PHC 파라미터 파싱(형식·버전·비용·salt/hash 길이)과 양쪽 해셔의 설정값.

정책도 fixture 설명에 남긴다: 재해시는 로그인 **성공 시에만** 한다(`_rehash_if_outdated`) — 실패 시에도 재해시하면 오프라인 공격자에게 계산 자원을 태워 준다. 재해시 실패가 로그인을 실패시키지 않는 것(best-effort)도 동작이다. 길이 하한 `MIN_PASSWORD_LENGTH = 8`은 가입 경로(`AuthService.signup`)의 정책이지 해시 계층의 동작이 아니므로, 빈 비밀번호 케이스가 해싱되는 것은 정상이다.

**이 도메인에는 정규화를 걸지 않는다(`normalization: []`).** 기대값이 전부 ASCII라 접을 표기 차이가 없고, 무엇보다 비밀번호 바이트를 NFC로 접으면 해시가 통째로 달라진다. fixture에는 NFC로 해싱한 한글 비밀번호를 NFD 표기로 검증하면 **실패한다**는 케이스가 들어 있다 — 정규화가 이 케이스를 통과시키는 순간 검증이 은폐로 바뀐다. 같은 이유로 각 케이스는 `password_utf8_sha256`을 기대값에 담아, 양쪽이 정말 같은 바이트를 해싱했는지가 먼저 드러나게 한다. `jwt` 도메인도 같은 이유로 정규화를 쓰지 않는다.

## 골든셋 비교 절차

`tests/golden/` 실제 구성:

- `documents/` — 골든 문서 **56개** JSON. 스키마는 `app/easyread/goldenset.py::GoldenDocument` (`id`, `title`, `category`, `synthetic`, `source_text`, `required_facts`, `source`).
- `test_schema.py` — LLM을 부르지 않는 스키마·품질 불변식 검사(문서 수, id 중복, 본문 길이 범위, 어려운 표현 포함, 마스킹 범주 5종 전부 등장, `required_facts`의 canonical이 원문에 실재, 마스킹 대상 패턴이 `required_facts`에 없을 것).
- `test_floor_gate_wiring.py` — 충실성 바닥 게이트 배선 회귀 테스트(FakeProvider 사용).
- `test_golden_eval.py` — **`pytestmark = pytest.mark.llm`**. `pyproject.toml`의 `addopts = "-m 'not llm'"` 때문에 기본 실행에서 제외된다. 실행: `GOLDEN_PROVIDER=anthropic uv run pytest tests/golden -m llm`.

**규칙 기반을 먼저 통과시킨다.** 이유는 두 가지다. 첫째, LLM judge는 실제 API 호출이라 비용이 들고 계획 §4.6이 "전체 LLM 골든 평가는 별도 비용 승인 후 실행"을 요구한다. 둘째, judge 점수는 확률적이라 회귀의 원인을 짚어 주지 못한다 — 프롬프트 렌더링이 한 글자 다른 것이 원인인데 "충실성 평균 3.9"라는 결과만 보면 어디를 고칠지 알 수 없다. 규칙 기반 비교는 결정적이고 원인을 직접 가리킨다.

절차:

1. **결정적 계층부터**: 56개 문서의 `source_text`를 양쪽 `check_style`에 넣어 `total_sentences`와 issue 목록(순서 포함)을 비교한다. LLM 없이 돌고, 여기서 갈리면 이후 비교는 의미가 없다. `missing_facts`(`RequiredFact.retained_in` — canonical 또는 accept 변형 포함) 판정도 같은 방식으로 비교한다.
2. **고정 응답 계층**: 양쪽 FakeProvider에 동일한 고정 응답을 물리고 `ConversionService.convert`를 돌려 (a) LLM 호출 횟수, (b) 보정 호출 여부, (c) 채택 결과, (d) `missing_placeholders`, (e) 토큰 합산을 비교한다. 계획 §4.6의 게이트 3번이다. 호출 횟수 계약(`MAX_LLM_CALLS_PER_CONVERSION = 2`)은 여기서 증명된다.
3. **실제 provider 소량**: 모델, `DEFAULT_MAX_TOKENS = 16000`, `DEFAULT_TEMPERATURE = 0.2`, `DEFAULT_TIMEOUT_SECONDS = 60.0`, `DEFAULT_MAX_RETRIES = 2`, Anthropic effort 설정이 같은지 요청 페이로드 수준에서 확인한다. 출력 텍스트는 같을 수 없으므로 **요청이 같은지**를 본다.
4. **전체 LLM 평가**(비용 승인 후): 기준선과 비교한다.

**"나빠지지 않았음"을 수치로 보이는 법.** 절체 전 Python으로 같은 문서 집합·같은 provider·같은 파라미터로 돌려 기준선을 파일로 남기고(`docs/migration/_workspace/`), Kotlin 결과를 같은 형식으로 남겨 나란히 적는다. 비교 항목은 `test_golden_eval.py`가 이미 정의한 것들이다:

| 지표 | 현재 게이트 |
|---|---|
| 규칙 기반 통과율 | `PASS_RATE_THRESHOLD = 0.9` |
| judge 채점 커버리지 | `JUDGE_COVERAGE_THRESHOLD = 0.9` |
| 충실성 / 이해 용이성 평균 | 각 `JUDGE_SCORE_THRESHOLD = 4.0` |
| 충실성 바닥 | `DEFAULT_FIDELITY_FLOOR = 2` 이하 문서 0건 |

바닥 게이트를 평균보다 **먼저** 본다 — 날조(fidelity 1~2)는 평균으로 상쇄될 수 없다. 표본이 작아 통과율이 경계에서 흔들릴 수 있다는 통계 한계가 테스트 주석에 명시되어 있으니, 자동 재시도로 숨기지 말고 재실행·표본 확대 판단을 사람에게 넘겨라.

**리포트에 본문을 절대 싣지 않는다.** 기존 테스트가 문서 id와 사유 코드·건수만 출력하도록 설계되어 있고 `JudgeScore.comment`는 본문을 인용할 수 있어 출력 금지다. parity 리포트도 같은 규칙을 따른다.

## 문서 추출 동등성 — 여기가 가장 위험하다

계획 §4.5의 경고: **현재 Python docx 추출기는 비공개 XML 요소까지 직접 순회한다.** `_element_blocks`는 python-docx의 공개 API(`paragraphs`/`tables`)를 쓰지 않고 OOXML 트리를 스택으로 직접 내려간다. 그렇게 하는 이유가 코드 주석에 남아 있고, 각각이 그대로 동등성 요구사항이다:

- 표가 **본문 안 제자리**에 남는다(문단 먼저 모으면 표가 문서 끝으로 밀린다).
- 텍스트박스(`w:txbxContent`)·중첩 표·SDT 안의 문단도 딸려 온다.
- 변경 추적에서 **삽입문(`w:ins`)은 포함, 삭제문(`w:delText`)은 제외**.
- `mc:Fallback`에서 하강을 **멈춘다** — 멈추지 않으면 텍스트박스 하나가 두 번 나와 크레딧이 두 배로 청구되고 프롬프트·마스킹 결과까지 오염된다.
- 로컬 이름으로 판별해 `a:t`(도형)·`m:t`(수식)까지 걷는다.
- 머리글·바닥글은 `is_linked_to_previous`면 건너뛴다(구역 수만큼 반복 방지). 짝수 쪽·첫 쪽 전용 머리글과 각주·미주는 **의도적으로 걷지 않는다**.

Apache POI의 `XWPFWordExtractor` 같은 기성 텍스트 추출은 이 규칙들과 거의 확실히 다르다. 그러므로 **POI 텍스트 추출로 갈음하지 말고 위 규칙을 그대로 구현한 뒤 fixture로 비교한다.**

fixture 소스는 `tests/ingest/fixtures/`의 실제 파일 6개다: `sample.docx`, `sample_table.docx`, `sample_rich.docx`, `sample.pdf`, `empty.pdf`, `sample.hwpx`. `tests/ingest/make_fixtures.py`가 이들을 생성하므로, 새 케이스가 필요하면 손으로 만들지 말고 그 스크립트를 확장한다. 골든 문서 56건의 본문도 docx/hwpx로 말아 넣어 추가 표본으로 쓸 수 있다.

**포팅 불가능한 요소를 조용히 누락하지 않는다.** 어떤 요소를 JVM 쪽에서 걷을 수 없다면 선택지는 둘뿐이다 — (a) 지원 한계로 문서화하고 fixture에 `unsupported` 케이스로 명시, (b) 명시적 실패(`DocumentExtractionError`). 조용한 누락이 최악인 이유는 사용자가 문서를 올렸고 시스템이 성공을 응답했는데 본문 일부가 사라진 상태이고, 그 사실이 검수 화면에서도 드러나지 않기 때문이다. 크레딧은 청구되고 결과는 불완전하다.

함께 옮길 제한과 실패 분기 (전부 fixture로):

| 항목 | 현재 값·동작 |
|---|---|
| 업로드 상한 | `MAX_UPLOAD_BYTES = 10 * 1024 * 1024` |
| 추출 길이 상한 | `MAX_EXTRACTED_CHARS = 500_000` |
| 압축 해제 예산 | `_MAX_UNCOMPRESSED_BYTES = 5 * MAX_UPLOAD_BYTES`. **헤더 선언 크기를 믿지 않고 실제 읽은 바이트로 센다** — 선언값을 위조한 94KB 파일이 힙 141MB를 먹는다는 실측이 주석에 있다. |
| DTD 차단 | expat `StartDoctypeDeclHandler`. `<!DOCTYPE` 바이트 검색은 UTF-16 인코딩으로 우회된다(실측). JVM에서는 `XMLInputFactory`의 `SUPPORT_DTD=false`, `IS_SUPPORTING_EXTERNAL_ENTITIES=false`로 파서 수준에서 막는다. |
| 스캔 PDF | 텍스트 레이어 없음 → `DocumentExtractionError("텍스트를 추출할 수 없습니다 (스캔 PDF는 지원 예정)")`. 페이지 0건과 구분한다. |
| OLE2 진단 | 암호 OOXML(`EncryptedPackage`) / 구버전 `.doc`(`WordDocument`) / 판별 불가를 **다른 메시지**로 구분한다. 안내가 같으면 후자 사용자가 없는 암호를 찾아 헤맨다. |
| 형식 판별 | 확장자만 본다(내용 스니핑 없음), 대소문자 무시. 지원: `.docx`, `.pdf`, `.hwpx`. |
| 블록 결합 | `_join_blocks` — 줄 단위 strip, 빈 줄 제거, `\n` 하나로 연결. 이 정규화가 추출기 출력 형태를 결정하므로 반드시 동일해야 한다. |

## 불일치 리포트 형식

`kotlin-implementer`에게 넘길 리포트는 **그가 바로 재현할 수 있는 형태**여야 한다. "마스킹이 다릅니다"는 리포트가 아니다.

경로: `docs/migration/_workspace/{phase}_parity-verifier_{도메인}-mismatch.md`

```markdown
# parity 불일치 — masking (Phase 2)

## 요약
- 비교 71건 / 불일치 2건 / 미실행 1건 / 미검증 0건
- 판정: 차단 (자리표시자 번호 불일치는 원문 복원을 깬다)

## `masking-multi-same-category`
- source: `app/privacy/masking.py::mask_text`
- 최소 재현 입력: `{"text": "010-1234-5678 또는 010-8765-4321 로 연락 주세요."}`
- 기대: `items[1].placeholder == "[[전화번호2]]"`
- 실제: `items[1].placeholder == "[[전화번호1]]"`
- 최초 차이 경로: `$.items[1].placeholder`
- 적용 정규화: nfc, lf
- 재현 절차:
  1. `uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --domain masking`
  2. Kotlin: `./gradlew :core:test --tests '*MaskingParityTest*'`
  3. `uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
       --fixture parity/fixtures/masking/masking.json \
       --actual parity/actual/masking/kotlin.json --only masking-multi-same-category`
- 추정 원인(가설, 확정 아님): 범주별 카운터가 전역 카운터로 구현된 듯함
- 영향: 자리표시자 번호가 어긋나면 `restore_placeholders`가 잘못된 원문을 넣는다
```

규칙:

- **기대값과 실제값을 나란히 적는다.** 한쪽만 적으면 받는 쪽이 어느 방향으로 고쳐야 하는지 모른다.
- **최소 재현 입력**을 만든다. 5,000자 문서에서 났어도 20자로 줄여 재현되는지 확인하고 그것을 적는다. 줄이는 과정에서 원인이 드러나는 경우가 많다.
- **재현 명령을 그대로 복사할 수 있게** 적는다.
- **원인은 가설로만 적는다.** parity-verifier는 값을 비교하는 역할이고 구현 판단은 `kotlin-implementer`의 몫이다. 단정하면 그가 다른 가능성을 안 본다.
- **차단인지 기록인지 판정한다.** 자리표시자·마스킹 누락·상태 코드·암호 복호화는 차단. 프롬프트 공백 한 칸 같은 항목도 원칙적으로 차단이되, 근거를 붙여 정규화 후보로 올릴 수는 있다.
- **본문·개인정보를 싣지 않는다.** 합성 fixture 입력은 괜찮지만, 골든 문서나 실제 업로드에서 난 불일치는 문서 id와 사유 코드·오프셋만 적는다.
- **Python 기대값을 고쳐 통과시키지 않는다.** Python이 틀렸다는 별도 근거가 있을 때만 fixture를 바꾸고, 그때는 왜 바꿨는지를 리포트에 남기고 `contract-keeper`·리더에게 알린다.

## 번들 스크립트

둘 다 저장소 루트에서 `uv run`으로 실행한다.

### `scripts/dump_parity_fixtures.py`

Python을 실행해 fixture를 생성한다. 도메인 11개(`masking`, `text`, `style`, `style-tables`, `prompts`, `postprocess`, `repair-adoption`, `export`, `crypto`, `jwt`, `argon2`)를 지원하고, 인자 없이 돌리면 전부 생성한다.

```bash
uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py
uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --domain masking --domain style
uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --list
# 역방향 검증: Kotlin이 만든 산출물을 Python이 읽는다 (실행 증거 파일을 남긴다)
uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
    verify-crypto --actual parity/actual/crypto/kotlin-encrypt.json
uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
    verify-jwt --actual parity/actual/jwt/kotlin-issue.json
```

`verify-*`는 성공하든 실패하든 `--actual`과 **같은 디렉터리**에 실행 증거 파일(`verify-crypto.verified.json` / `verify-jwt.verified.json`)을 남긴다. `compare_parity.py`가 그 파일로 역방향 케이스를 닫으므로 경로를 옮기지 않는다(`--proof`로 바꿀 수는 있지만 fixture의 `verification.proof`와 어긋나면 미검증으로 남는다).

Kotlin이 내야 할 입력 형식은 fixture의 `verification.actual_schema`에 적혀 있다.

- `verify-crypto`: `{"cases": [{"id", "key", "token", "expected_plaintext"}]}`
- `verify-jwt`: `{"cases": [{"id", "secret", "token", "verify_at", "expected_subject", "expected_outcome"}]}`
  `verify_at`(epoch 초)은 **필수**다 — 없으면 그 케이스를 실패로 본다. 만료 동작까지 보이려면 `expected_outcome: "invalid_credentials"` 케이스를 함께 낸다.

도메인을 추가하려면 `BUILDERS` 딕셔너리에 `() -> (source, normalization, cases)` 함수를 등록한다. 역방향 요청 케이스를 만들 때는 `_external(...)`을 `verification=`으로 붙이고 대응하는 `verify-*` 서브커맨드를 함께 추가한다 — 붙이지 않으면 Kotlin이 기대값을 되받아 적는 것만으로 케이스가 닫힌다. `ingest`·`golden` 도메인은 아직 없다 — 바이너리 fixture 경로 참조 방식을 결정한 뒤 추가한다.

### `scripts/compare_parity.py`

fixture(기대값)와 Kotlin 결과(실제값)를 정규화 후 비교한다.

```bash
# 개발 중 — 한 도메인만 (부분 검증. 게이트를 닫는 근거가 아니다)
uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
    --fixture parity/fixtures --actual parity/actual --only-domain masking --report-md \
    docs/migration/_workspace/02_parity-verifier_masking-mismatch.md
# 같은 뜻 — 도메인 디렉터리를 경로로 지목해도 부분 검증으로 판정된다
uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
    --fixture parity/fixtures/masking --actual parity/actual/masking
# Phase 종료 판정 — 전체 게이트 (도메인 지정 없이 루트를 넘긴다)
uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
    --fixture parity/fixtures --actual parity/actual
```

**Phase 종료 조건은 마지막 형태로만 닫는다.** 앞의 두 형태는 통과해도 "부분 검증"으로 표시된다.

전 도메인을 한 번에 비교하더라도 **리포트는 언제나 도메인별로 나눠 쓴다** — 경로 규약은 위
[불일치 리포트 형식](#불일치-리포트-형식)의 `{phase}_parity-verifier_{도메인}-mismatch.md` 하나뿐이다.
한 파일에 합치면 `kotlin-implementer`가 자기가 고칠 도메인 항목을 먼저 골라내야 하고,
"모듈이 완성될 때마다 즉시 돌린다"(핵심 원칙 3)는 흐름이 깨진다.

Kotlin 결과 파일 형식: `{"runtime": "kotlin", "cases": [{"id": "...", "actual": {...}}]}` — fixture와 같은 상대 경로에 둔다.

동작: 케이스 id로 짝지어 정규화 후 비교하고, 미실행 케이스와 기대값 없는 케이스를 따로 보고한다. 금지된 정규화 규칙 이름이 fixture에 들어 있으면 비교 자체를 중단하고, 정규화가 자리표시자 목록을 바꾸면 그 케이스를 "정규화 오류"로 보고한다. `verification.mode == "external"`인 역방향 케이스는 값 비교 대신 실행 증거 파일로 판정한다(위 [자격증명·암호 3개 도메인 공통](#자격증명암호-3개-도메인-공통--역방향은-실행-증거가-있어야-닫힌다) 참고).

#### 기대 도메인 집합을 스크립트가 강제한다

비교기는 **주어진 파일만** 본다. 그래서 예전에는 도메인 디렉터리를 통째로 빼면 그 도메인이 한 건도 검증되지 않은 채 "전건 일치"가 나왔다 — `fixtures/crypto`·`fixtures/jwt`·`fixtures/argon2`를 지우고 돌리면 `전건 일치: 값 비교 62건 / 파일 8개`, 종료 코드 0이었다. §6 Crypto 게이트가 요구하는 세 도메인이 하나도 없는데 게이트가 닫힌 것이다. 문서(이 스킬과 `parity-verifier.md`)에는 "세 도메인이 다 필요하다"고 적혀 있었지만 문서에만 있는 규칙은 바쁠 때 지켜지지 않는다.

지금은 디렉터리 비교가 **어떤 도메인이 있어야 하는지**를 알고 빠진 도메인을 누락으로 판정한다.

- **기대 집합의 정본은 `dump_parity_fixtures.py`의 `BUILDERS` 키 하나뿐이다.** `compare_parity.py`가 그 키를 import해서 쓰므로 목록이 두 벌로 갈라지지 않는다. 도메인을 추가할 때 고칠 곳은 생성기 한 곳이다.
- 빠진 도메인은 **이름으로** 찍힌다: `없는 도메인: crypto, jwt, argon2`. "파일 8개 비교"로는 사람이 알아채지 못한다. 리포트(`--report-md`)에도 "parity 도메인 누락 리포트" 절이 붙는다.
- 같은 원리("검증하지 않은 것이 통과로 집계되면 안 된다")로 함께 막힌 것: **빈 fixture**(`cases: []` — 0건 비교는 통과가 아니다), `cases` 키가 없는 fixture, fixture의 중복 케이스 id, `BUILDERS`에 없는 손수 만든 도메인, fixture 위치(`{도메인}/`)와 `domain` 필드 불일치(디렉터리 이름만 바꿔 도메인을 숨기는 경로), Kotlin 결과 파일의 중복 id·id 없는 항목, 존재하지 않는 id를 `--only`로 준 경우(예전에는 모든 문제가 사후 필터에 지워져 종료 코드 0이 나왔다). fixture는 있는데 actual이 없는 경우와 actual의 케이스 수가 모자란 경우는 이전부터 `Kotlin 결과 파일 없음`·`미실행`으로 막혀 있다.

#### 전체 게이트와 부분 검증을 구분한다

개발 중 한 도메인만 돌리는 것은 정상이다(핵심 원칙 3). 그래서 범위를 **명시**하면 그 범위만 판정하되, 출력이 전체 게이트와 다르게 나온다.

| 실행 형태 | 판정 범위 | 마지막 줄 |
|---|---|---|
| `--fixture <fixtures 루트> --actual <actual 루트>` (도메인 지정 없음) | **전체 게이트** — `BUILDERS` 키 전부를 요구 | `전건 일치: ...` |
| `--only-domain <도메인>` (반복 가능) | 부분 검증 — 지정한 도메인만 | `부분 검증 통과(게이트 아님): ...` |
| `--only <케이스 id>` | 부분 검증 — 그 케이스만 | `부분 검증 통과(게이트 아님): ...` |
| `--fixture parity/fixtures/masking` — **도메인 디렉터리**를 직접 지정 | 부분 검증 — 경로가 곧 범위 선언이다 | `부분 검증 통과(게이트 아님): ...` |
| fixture 파일 하나를 직접 지정 | 부분 검증 — 그 파일의 도메인만 | `부분 검증 통과(게이트 아님): ...` |

부분 검증이 그 범위 안에서 통과하면 **종료 코드는 0이 아니라 3**이고, 출력에 다음이 함께 찍힌다:

```
[부분 검증] --only-domain masking — 판정한 도메인 masking (기대 집합 11개 중 10개는 돌리지 않았다)
  이 결과는 게이트를 닫는 근거가 아니다. 전체 게이트는 fixture·actual 루트를 도메인 지정 없이 넘겨 종료 코드 0이 나온 결과로만 닫는다.
```

**부분 검증은 종료 코드로도 구분된다(3).** 그래도 마지막 줄이 `전건 일치:`로 시작하는지까지 확인한다 — 사람이 읽는 판정과 자동화가 읽는 판정이 어긋나지 않는지 보는 이중 확인이다. 부분 검증은 절대 그 문구를 쓰지 않는다.

종료 코드:

| 코드 | 뜻 | 게이트 |
|---|---|---|
| 0 | 전건 일치 + 미검증 0건 + 기대 도메인 전부 존재 | 닫아도 된다 (마지막 줄이 `전건 일치:`일 때만) |
| 1 | 불일치·미실행·읽기 실패·역방향 검증 실패·**도메인 누락**·빈 fixture·사용법 오류 | 차단 |
| 2 | 불일치는 없으나 **미검증** 케이스가 남음 | 닫지 않는다 |
| 3 | **부분 검증**(`--only` / `--only-domain` / 단일 fixture / 도메인 디렉터리)이 그 범위 안에서 통과 | 닫지 않는다 |

**부분 검증을 0이 아니라 3에 둔 근거.** 종료 코드는 자동화가 읽는 유일한 계약이다. stdout에 찍히는 "이 결과는 게이트를 닫는 근거가 아니다"는 사람이 읽을 때만 유효하고, CI와 에이전트는 exit code로 판정한다. 예전에는 masking 한 도메인만 돌린 실행이 "기대 집합 11개 중 10개는 돌리지 않았다"고 **경고하면서 종료 코드는 0**이었다 — 종료 코드만 보는 호출자에게는 전체 통과와 구별되지 않았고, 스크립트 상단이 "0은 기대 도메인 전부가 있을 때만"이라고 계약해 놓은 것과도 모순됐다. 1(차단)로 묶지 않은 이유는 부분 검증이 정상적인 개발 중 작업이기 때문이다(핵심 원칙 3 — 모듈 하나가 끝날 때마다 그 도메인만 돌린다). "고쳐야 할 문제가 있다"(1)와 "범위를 좁혀 돌렸다"(3)가 같은 코드로 나가면 호출자가 둘을 구분할 수 없다. 3은 **"이 범위에서는 문제 없음, 그러나 게이트는 열린 채"**라는 뜻이며, 부분 검증이라도 불일치가 있으면 1, 미검증이 남으면 2가 그대로 나간다 — 3은 그 두 검사를 모두 통과한 뒤에만 도달한다.

**도메인 누락을 2가 아니라 1에 둔 근거.** 누락은 "돌리지 않은 것"이라 성격상 2에 가까워 보이지만, 이미 "Kotlin 결과 파일 없음"(파일 누락)과 "미실행"(케이스 누락)이 1로 나간다. 같은 성격의 누락을 입도가 커졌다는 이유로(케이스 → 파일 → 도메인) 더 약한 코드로 내보내면 **많이 지울수록 종료 코드가 약해지는** 유인이 생기고, 그것이 정확히 이 게이트를 무력화하는 경로다. 2는 **fixture가 그 케이스를 정의했고 남은 것이 외부 실행 증거뿐인** 좁은 상태에만 쓴다 — 도메인이 통째로 없으면 정의 자체가 없으므로 2의 의미에 해당하지 않는다. 같은 이유로 사용법 오류도 argparse 기본값 2가 아니라 1로 끝난다(인자를 잘못 준 것과 미검증이 남은 것이 같은 코드면 호출자가 구분할 수 없다).

**종료 코드 2를 0처럼 다루지 않는다.** 출력 마지막 줄이 `전건 일치:`로 시작할 때만 통과다. 미검증이 있으면 `[미검증] ... — 전건 일치로 보고하지 않는다 (종료 코드 2)`가 찍히고, 리포트에 별도의 "parity 미검증 리포트" 절이 붙는다.

## 재호출 지침

`docs/migration/_workspace/`의 이전 parity 산출물을 먼저 전부 읽는다.

- Python 쪽 코드가 바뀌었으면 **fixture부터 다시 생성**하고 diff를 본다. fixture diff가 곧 "Python 동작이 이렇게 바뀌었다"는 기록이고, 그 diff를 `kotlin-implementer`에게 전달할 변경 목록으로 쓴다. `style_rules.py`는 이 프로젝트에서 가장 자주 바뀌므로 재호출 시 항상 재생성 대상이다.
- 이전에 통과한 도메인도 관련 파일이 바뀌었으면 다시 돌린다. 통과 기록은 그 시점 코드에 대한 것이다.
- 이전 불일치가 해결되었다는 보고를 받으면 **말이 아니라 스크립트 출력으로** 확인한다.
- 정규화 규칙을 추가한 이력이 있으면 그 근거가 여전히 유효한지 재확인한다. 근거 없이 남은 정규화가 다음 회귀를 숨긴다.
