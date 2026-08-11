# 00 / pre-phase0 — 보안 스캐너 기준선 확정

**목적**: Phase 0 종료 감사에 앞서 `scan_privacy_invariants.py` 전수 실행을 초록불로 만든다. 기준선이 빨간불이면 이후 모든 스캔이 "원래 빨간불"로 읽히고, 그때 진짜 위반이 지나간다. 게이트의 값어치는 초록불이 의미를 갖는 데 있다.

**수정 범위**: `.claude/skills/migration-safety-gate/`(SKILL.md, scripts/)와 이 문서. 프로젝트 소스(`app/`, `tests/`, `scripts/`, `frontend/`)는 판정만 하고 **한 줄도 고치지 않았다**.

**결론**: BLOCK 후보 2건은 **둘 다 오탐**. 규칙을 정교화해 해소했고, 전수 스캔은 **exit 0**이다. 억지 통과(예외 경로 확장·규칙 비활성화)는 쓰지 않았다.

---

## 1. 착수 시점 기준선 (수정 전)

```
$ uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py
검사 파일 98개
## [BLOCK] SECRET-LITERAL (1건)
- `frontend/src/api/client.test.ts:85` — password: 'wrongpassword'
## [BLOCK] XML-DTD (1건)
- `app/easyread/bokjiro.py:21` — import xml.etree.ElementTree as ET
BLOCK 후보 2건 — …게이트를 통과시키지 않는다.
EXIT=1
```

---

## 2. BLOCK 후보 판정

### 2.1 `SECRET-LITERAL` @ `frontend/src/api/client.test.ts:85` → **오탐**

| 항목 | 내용 |
|---|---|
| 적중 리터럴 | `'wrongpassword'` (13자, 소문자 알파벳만) |
| 문맥 | `describe('401 처리')` 안, "로그인 실패(401)로는 저장된 토큰을 건드리지 않는다" 케이스. `fetchMock`이 401을 돌려주도록 미리 세워 둔 뒤 `login()`을 부른다 |
| 판정 근거 | ① 값이 **의도적으로 틀린** 비밀번호다 — 통과가 아니라 거부를 검증하는 입력이다. ② 네트워크는 `fetchMock`으로 대체돼 실제 인증 대상이 없다. ③ 리터럴이 난수꼴이 아니다(문자 클래스 1종, 엔트로피 3.09 bits/char) — 어떤 시스템의 자격증명도 아니다 |
| 불변식 대조 | 감사 항목 "비밀키는 환경변수만 쓴다"(§4.3 / `CLAUDE.md` 보안 규칙)가 막으려는 것은 **코드·커밋에 들어간 실제 키**다. 이 값은 그 범주가 아니다 |

**판정: 오탐. 실제 위반 아님.**

### 2.2 `XML-DTD` @ `app/easyread/bokjiro.py:21` → **오탐 (그리고 규칙의 방향이 반대였다)**

| 항목 | 내용 |
|---|---|
| 적중 줄 | `import xml.etree.ElementTree as ET` — **import 선언**이지 파싱 호출이 아니다 |
| 실제 파싱 경로 | `parse_xml()` (`app/easyread/bokjiro.py:163-194`). expat 파서를 만들고 `parser.StartDoctypeDeclHandler = reject_doctype`(:181)로 **DTD 선언을 파서 수준에서 거부**한 뒤, 핸들러 출력을 `ET.TreeBuilder`에 넣는다 |
| `ET`의 실제 용도 | 트리 자료구조(`ET.TreeBuilder`)와 타입 주석(`ET.Element`)뿐 |
| 확인 방법 | `grep -nE "ET\.(parse\|fromstring\|iterparse\|XMLParser)" app/easyread/bokjiro.py` → **호출 0건**. 유일한 문자열 적중은 :166의 docstring, 그것도 "`ET.fromstring`을 그대로 쓰지 않는 이유는…"이라는 **쓰지 않는다는 설명**이다 |
| 레포 전체 재확인 | `app/`, `scripts/` 전체에 `ElementTree`/`minidom` 계열 파싱 호출 **0건**. expat 생성 지점 2곳(`app/ingest/extractors.py:423`, `app/easyread/bokjiro.py:180`) 모두 **바로 다음 줄**에서 `StartDoctypeDeclHandler`로 DTD를 거부한다 |

**판정: 오탐. 실제 위반 아님.** DTD·외부 엔터티 방어는 §4.5가 요구한 대로 켜져 있다.

> **부수 발견(더 중요)**: 이 규칙은 오탐만 내는 게 아니라 **탐지 방향이 반대**였다. 안전한 `import` 줄은 잡으면서, 정작 위험한 `ET.fromstring(data)`(별칭 호출)은 **놓쳤다**. 아래 3.2에서 실증했다. 오탐 1건보다 이 미탐이 더 심각한 결함이다.

---

## 3. 규칙 수정

### 3.1 `SECRET-LITERAL` — 경로가 아니라 **값의 모양**으로 가른다

`tests/`를 통째로 면제하는 방식은 쓰지 않았다. 그렇게 하면 테스트 파일에 실제 Fernet 키를 넣어도 통과하고, 그 순간 규칙이 죽는다.

대신 정규식 적중 뒤에 **2차 판정**(`refine`)을 붙였다. 기준은 리터럴 자체의 성질이다 — 진짜 키는 base64·hex 난수라 문자 클래스가 섞이고 엔트로피가 높지만, 사람이 타이핑한 픽스처는 두 축에서 모두 떨어진다.

```
looks_like_real_secret(value):
  hex 32자 이상          → 키       (hex는 클래스가 2종뿐이라 별도 분기)
  토큰꼴 24자↑ & H≥3.8   → 키       (base64/토큰 — 클래스가 적어도 난수면 키)
  클래스≥3 & 길이≥12 & H≥3.2 → 키
  그 외                  → 대상 아님
```

**검증** (17건 전부 기대와 일치):

| 기대 | 사례 | 결과 |
|---|---|---|
| 제외 | `password: 'wrongpassword'` | 제외 |
| 제외 | `secret_key = "change-me-in-production"` | 제외 |
| 제외 | `password = 'not-a-real-password'` | 제외 |
| **후보** | `FERNET_KEY = "dGhpc19pc19hX3JlYWxfa2V5..."` | 후보 |
| **후보** | `jwt_secret = "a3f5b8c2d4e6f708192a3b4c5d6e7f80"` | 후보 |
| **후보** | `api_key = "sk-ant-api03-Xj7Qm2LpZ9vRt4Ke8Nw1"` | 후보 |
| **후보** | **테스트 파일 안의 진짜 Fernet 키** | **후보** ← 경로 면제가 아님을 증명 |

마지막 줄이 핵심이다. 테스트 코드에 진짜 키가 들어가면 **여전히 잡힌다**.

### 3.2 `XML-DTD` — import가 아니라 **파싱 호출**을 보고, 완화 조치를 인정한다

두 가지를 바꿨다.

1. **탐지 지점을 호출부로 이동**. `xml\.etree`(import 문자열)를 빼고, 별칭까지 훑는 호출 패턴으로 교체했다: `(ET|ElementTree|etree|minidom|objectify).(parse|fromstring|iterparse|XMLParser|XMLPullParser)(`, `expat.ParserCreate(`, `make_parser(`, `from xml.… import fromstring` 형태, 그리고 JVM 팩토리(`DocumentBuilderFactory`·`XMLInputFactory`·`SAXParserFactory`·`TransformerFactory`·`SchemaFactory`·`XMLReaderFactory`).
2. **`hardened` 창(window) 판정 추가**. 규칙의 `오탐 가능` 주석이 사람에게 시키던 "주변 줄을 확인하라"를 기계화했다. 적중 줄 앞 2줄·뒤 10줄 안에 DTD·외부 엔터티를 끄는 호출(`StartDoctypeDeclHandler`, `disallow-doctype-decl`, `SUPPORT_DTD`, `IS_SUPPORTING_EXTERNAL_ENTITIES`, `FEATURE_SECURE_PROCESSING`, `ACCESS_EXTERNAL_DTD` 등)이 있으면 후보에서 뺀다. 창 **밖**에서 완화하면 후보로 남는다 — 그 편이 안전한 방향이다.

**검증** (10건 전부 기대와 일치):

| 기대 | 사례 | 결과 |
|---|---|---|
| 제외 | `import xml.etree.ElementTree as ET` (import만) | 제외 |
| **후보** | `root = ET.fromstring(data)` ← **기존 규칙이 놓치던 것** | 후보 |
| **후보** | `tree = ElementTree.parse(path)` | 후보 |
| **후보** | `from xml.etree.ElementTree import fromstring` | 후보 |
| 제외 | `expat.ParserCreate()` + 다음 줄 `StartDoctypeDeclHandler` | 제외 |
| **후보** | `expat.ParserCreate()` + 완화 없음 | 후보 |
| 제외 | `DocumentBuilderFactory.newInstance()` + `disallow-doctype-decl` | 제외 |
| **후보** | `DocumentBuilderFactory.newInstance()` + 기본값 | 후보 |
| 제외 | `XMLInputFactory.newInstance()` + `SUPPORT_DTD=false` | 제외 |
| **후보** | `XMLInputFactory.newInstance()` + 기본값 | 후보 |

아래 4줄(JVM)이 Phase 4 HWPX 파서 포팅에서 실제로 쓰일 판정이다. §4.5의 "JAXP/StAX 기본값 의존은 위반"을 이제 기계가 본다.

### 3.3 제외 건수를 리포트에 노출

2차 판정으로 뺀 적중을 조용히 버리면, 규칙이 언제부터 아무것도 안 보는지 알 수 없다. 리포트 상단에 함께 찍는다:

```
2차 판정으로 제외한 적중(규칙이 눈감은 양을 드러내기 위해 함께 적는다):
- `SECRET-LITERAL` — 값의 모양이 불변식 대상이 아님 1건
- `XML-DTD` — 같은 창에서 완화 조치 확인 2건
```

---

## 4. 규칙 품질 — 우회 형태 실증 목록

codex가 제기한 "12개 규칙 전부 우회 가능"을 직접 재현해 확인했다. 스크립트의 `RULES`를 불러 문자열에 적용하는 방식이며, 프로젝트 트리는 건드리지 않았다.

| 규칙 | 우회 형태 | 결과 | 근본 원인 |
|---|---|---|---|
| `LOG-BODY` (BLOCK) | `logger.info("body=%s", body)` | 적중 | — |
| | 인자를 개행으로 분리 | **미적중** | 줄 단위 매칭 |
| | `msg = body` 후 `logger.info(msg)` | **미적중** | 변수 1회 경유 |
| | Kotlin 람다 `log.info { "text=$sourceText" }` | **미적중** | 호출 형태 미등록 |
| `LLM-RAW-INPUT` (BLOCK) | `provider.complete(source_text)` | 적중 | — |
| | `provider.complete(\n  user = source_text,` | **미적중** | 줄 단위 매칭 |
| | `provider.generate(source_text)` | **미적중** | 메서드명이 `.complete`로 고정 |
| | `provider.complete(source_text, unmasked=True)` | **미적중** | 부정 전방탐색이 `mask` 문자열만 보고 통과시킴 |
| `LLM-VENDOR-SDK` (BLOCK) | `import openai` | 적중 | — |
| | `__import__("openai")` | **미적중** | 동적 import |
| | `importlib.import_module("anthropic")` | **미적중** | 동적 import |
| `OWNERSHIP-403` (BLOCK) | `status_code=403` | 적중 | — |
| | `status(400 + 3)` | **미적중** | 상수 폴딩 |
| | `status(HttpStatus.FORBIDDEN_CODE)` | **미적중** | 간접 상수 |
| `PLAINTEXT-PERSIST` (BLOCK) | `INSERT INTO documents (source_text) …` | 적중 | — |
| | SQL을 여러 줄로 분리 | **미적중** | 줄 단위 매칭 |
| `SECRET-LITERAL` (BLOCK) | `api_key = "sk-" + "abcdef…"` | **미적중** | 문자열 연결 |

**공통 근본 원인은 줄 단위 정규식**이다. BLOCK 규칙 6개 중 6개가 "인자를 개행으로 나눈다"는 단일 수법에 뚫린다. 따라서 **스캔 0건은 "위반 없음"이 아니라 "이 표현형에서 안 걸림"이다.** 스킬의 수동 감사 절차를 스캔으로 대체하면 안 된다.

### 4.1 `CACHE-HEADER`는 접근 자체가 틀렸다 — 개선이 아니라 교체 대상

측정 능력이 없음을 수치로 확인했다.

| 측정 | 값 |
|---|---|
| `app/api/`의 라우트 수 | 13 (`auth` 3, `documents` 6, `workspaces` 4) |
| 헤더를 **실제로 붙이는 지점** | 8 (`response.headers.update(PRIVATE_RESPONSE_HEADERS)` 7 + 전개 1) |
| `CACHE-HEADER` 규칙 적중 | **1** — `app/api/documents.py:50`의 상수 정의 한 줄 |

호출부가 전부 명명 상수(`PRIVATE_RESPONSE_HEADERS`)를 쓰므로, 규칙이 찾는 문자열(`Cache-Control`/`no-store`/`nosniff`)은 **정의 지점에만** 나타난다. 엔드포인트가 3개든 30개든 적중은 항상 1이다.

그리고 이 규칙이 찾아야 하는 것은 헤더가 **붙은** 줄이 아니라 **안 붙은** 응답이다. 존재를 세는 방식으로는 누락을 영원히 못 찾는다. 실제로 최근 고친 `/auth` 3개 누락(커밋 `0fafac7`)은 사람이 라우트 전수 대조로 찾았고, 이 규칙은 전후 어느 쪽에서도 값이 1로 동일했다 — 즉 **누락이 있을 때와 없을 때를 구분하지 못했다**.

**판단**: 정규식으로 고칠 수 있는 종류가 아니다. 라우트 목록과의 대조가 본질이므로 **계약 테스트(`contract-keeper` 소관)로 옮기는 것이 맞다.** 스캐너 쪽은 "분포 확인용 WARN"이라는 현재 성격을 유지하되, 이 한계를 SKILL.md에 명시했다.

### 4.2 개선 시점 판단

기준은 **그 규칙이 처음으로 실제 Kotlin 코드를 검사하는 시점**이다.

| 규칙 | 첫 Kotlin 검사 시점 | 개선 마감 |
|---|---|---|
| `LOG-BODY`, `PLAINTEXT-PERSIST`, `OWNERSHIP-403` | Phase 3 (JDBC repository·인증 API) | **Phase 3 시작 전** |
| `LLM-RAW-INPUT`, `LLM-VENDOR-SDK` | Phase 5 (provider 어댑터·워커) | **Phase 5 시작 전** |
| `XML-DTD`, `ZIP-NO-BUDGET` | Phase 4 (문서 API·파서) | 3.2에서 **이번에 처리 완료** |
| `CACHE-HEADER` | Phase 3~4 | 계약 테스트로 이관 (스캐너 개선 대상 아님) |

**Phase 3 시작 전에 최소한 `LLM-RAW-INPUT`·`LOG-BODY`·`PLAINTEXT-PERSIST` 셋을 다중 줄 대응으로 올려야 한다.** 근거 둘: ① 이 셋이 §5 Phase 7 즉시 중단 기준("마스킹 전 본문이 LLM이나 로그로 전송됨", "평문 저장")에 직접 걸린다. ② Kotlin은 인자를 여러 줄로 늘어놓는 스타일이 관용이라, 지금 형태로는 Kotlin 본코드가 들어오는 순간 사실상 상시 0건이 된다 — **개선 전에 초록불이 나오면 그 초록불이 거짓말**이다.

다중 줄 대응은 전면 AST 재작성 없이도 가능하다(호출식 단위로 묶어 매칭). 이번 작업 범위 밖이라 착수하지 않았고, 위 시점 판단만 남긴다.

---

## 5. `--changed` 결함 — **이번에 수정함**

### 5.1 재현

수정 전 `iter_files()`는 `git diff --name-only HEAD`(작업 트리)와 미추적 파일만 봤다. 브랜치에 **커밋된** 변경은 전부 빠진다.

```
$ git branch --show-current
feat/kotlin-migration-harness
$ … scan_privacy_invariants.py --changed
검사 대상 파일이 없습니다.
EXIT=0        ← 보안 코드를 한 줄도 안 읽고 게이트 성공
```

이 브랜치는 `app/api/auth.py`·`app/api/documents.py`를 이미 고쳐 커밋한 상태다(커밋 `0fafac7` — 캐시 금지 헤더 추가). 즉 **가장 검사해야 할 파일 2개를 정확히 놓치면서 exit 0을 냈다.**

### 5.2 처리 판단: 기록만 하지 않고 **고쳤다**

기준선을 초록불로 만드는 것이 이번 목표인데, 이 결함은 정반대 방향의 거짓 초록불을 만든다. 기준선을 세워 놓고 "그런데 변경분 모드는 항상 통과합니다"를 남겨 두면 기준선의 의미가 사라진다. 수정 비용도 작았다.

### 5.3 수정 내용

- **`--base <ref>` 지원**, 기본값 `main`(`DEFAULT_BASE_REF`). 범위는 `<base>...HEAD`(merge-base 기준 커밋된 변경) + 작업 트리 + 미추적의 합집합.
- **파일 0개면 종료 코드 3으로 실패.** "검사하지 않음"과 "위반 없음"은 다르다. 정말 빈 것이 맞을 때만 `--allow-empty`.
- **base 해석 실패 시 전수 검사로 폴백** — 기존 :220-225의 git 조회 실패 폴백 선례를 따랐다. 게이트가 틀릴 때는 과검사 쪽으로 틀려야 한다.
- **리포트에 `검사 범위:` 줄 추가.** 범위 문자열을 `iter_files()`가 직접 돌려주게 바꿨다 — 호출자가 조립하면 폴백이 일어났을 때 "변경분"이라 적으면서 전수 파일 수를 싣는 리포트가 나온다(작업 중 실제로 이 버그를 만들었다가 잡았다).
- `--base`를 `--changed` 없이 주면 사용법 오류로 거부.

### 5.4 수정 후 동작 확인

| 명령 | exit | 검사 범위 | 파일 |
|---|---|---|---|
| (전수) | 0 | 전수 | 98 |
| `--changed` | 0 | 변경분 (main...HEAD + 작업 트리 + 미추적) | **2** |
| `--changed --base origin/main` | 0 | 변경분 (origin/main...HEAD + …) | 2 |
| `--changed --base HEAD` | **3** | (빈 범위 — 실패) | 0 |
| `--changed --base HEAD --allow-empty` | 0 | (빈 범위 — 허용) | 0 |
| `--changed --base no/such/ref` | 0 | **전수** (폴백, 범위 표기도 전수) | 98 |

`--changed`가 집어 온 2개는 `app/api/auth.py`, `app/api/documents.py` — 이전에 놓치던 바로 그 파일들이다.

---

## 6. 기준선 확정

```
$ uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py
검사 범위: 전수. 검사 파일 98개.

2차 판정으로 제외한 적중:
- `SECRET-LITERAL` — 값의 모양이 불변식 대상이 아님 1건
- `XML-DTD` — 같은 창에서 완화 조치 확인 2건

## [WARN] EXC-BODY        (1건)
## [WARN] ZIP-NO-BUDGET   (5건)
## [WARN] CACHE-HEADER    (1건)
## [WARN] RETENTION-PURGE (14건)

EXIT=0
```

**BLOCK 후보 0건. 종료 코드 0.** 남은 21건은 전부 WARN이며, 규칙 설계상 "위치 확인용"으로 늘 걸리는 항목들이다(`RETENTION-PURGE` 14건은 파기 로직이 있다는 뜻, `ZIP-NO-BUDGET` 5건은 예산 검사 통과 후 재파싱 지점). 이들을 0으로 만드는 것은 목표가 아니다 — WARN이 사라지면 해당 기능이 사라진 것이다.

게이트 3종도 통과했다.

```
$ uv run ruff check .        → All checks passed!
$ uv run ruff format --check . → 133 files already formatted
$ uv run mypy .              → Success: no issues found in 116 source files
```

---

## 7. 판정 요약

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | `SECRET-LITERAL` @ `client.test.ts:85` | **오탐** | 의도적 오답 입력, fetchMock 대체, 난수꼴 아님 (2.1) |
| 2 | `XML-DTD` @ `bokjiro.py:21` | **오탐** | import만 적중, 실제 파싱은 expat DTD 거부 (2.2) |
| 3 | 파서 DTD·외부 엔터티 차단 (감사 항목 10) | **준수** | expat 생성 2곳 모두 다음 줄에서 `StartDoctypeDeclHandler` |
| 4 | 비밀키 환경변수 관리 (감사 항목 13 인접) | **준수** | 전수 스캔에 난수꼴 하드코딩 리터럴 0건 |
| 5 | private 응답 헤더 범위 (감사 항목 6) | **준수** (최초 판정 정정 — 아래) | 아래 |

**#5 상세 — 최초 판정은 오탐이었다. 리더가 코드로 정정했다(2026-08-12).**

최초 감사는 "`app/api/workspaces.py`의 라우트 4개가 `PRIVATE_RESPONSE_HEADERS`를 붙이지 않는다"고 적고 **잠정 위반(보수적)** 으로 표시했다. **사실이 아니다.** 실제 코드는 이렇다:

| 라우트 | 헤더 | 근거 |
|---|---|---|
| `GET /workspaces` (`list_workspaces`) | 적용 | `app/api/workspaces.py:83` |
| `POST /workspaces` (`create_workspace`) | 적용 | `app/api/workspaces.py:96` |
| `PATCH /workspaces/{id}` (`rename_workspace`) | 적용 | `app/api/workspaces.py:113` |
| `DELETE /workspaces/{id}` (`delete_workspace`) | 없음 — **정당** | 204 무본문이라 실릴 내용이 없다 |

`app/api/workspaces.py:21`이 `PRIVATE_RESPONSE_HEADERS`를 import하고 있고, 저장소 전체 적용 지점은 10곳이다:

```
grep -rn "headers.update(PRIVATE_RESPONSE_HEADERS)\|\*\*PRIVATE_RESPONSE_HEADERS" app/api/ | wc -l  → 10
```

즉 `name`이 자유 텍스트라는 우려는 **이미 반영돼 있었다.** 리더 판단이 필요한 미결 항목이 아니므로 조치 요약표에서도 내린다.

**이 오탐에서 남길 교훈 둘.**

1. **감사 산출물의 오탐도 비용이다.** 이번 건은 보수적 방향(위반으로 기록)이라 데이터가 샐 위험은 없었지만, 잘못된 미결 항목은 리더의 판단 대기열을 늘리고 나중에 "이건 왜 열려 있지"를 되짚게 만든다. 판정 전에 `grep` 한 번이면 갈렸다.
2. **그럼에도 4.1의 실례라는 지적 자체는 유효하다.** `CACHE-HEADER` 규칙은 적용 지점이 10곳이든 0곳이든 항상 1건(상수 정의)만 보고하므로, **이 오탐을 규칙으로는 확인할 수도 반박할 수도 없었다.** 사람이 코드를 직접 읽어야 갈렸다는 사실이 규칙의 무력함을 오히려 잘 보여 준다. 이 종류의 "누락 탐지"는 정규식이 아니라 계약 테스트가 맡아야 한다.

---

## 8. 후속 조치

| 대상 | 내용 | 시점 |
|---|---|---|
| ~~리더~~ | ~~#5 workspaces 헤더 범위 결정~~ | **취소 — 오탐이었다. 3개 라우트에 이미 적용돼 있고 나머지 하나는 204 무본문** |
| `contract-keeper` | `contracts/easy-doc-v1.yaml`에 헤더 적용 범위를 **확정 사실 그대로** 명시한다 — 성공 응답 10곳(`documents` 4 · `workspaces` 3 · `auth` 3), `DELETE` 204 두 곳과 오류 응답에는 붙지 않음(`api-contract-freeze` §2.5·§2.7 해결 3). 대기할 리더 결정은 없다(#5는 오탐으로 종결) / `CACHE-HEADER` 누락 탐지를 계약 테스트로 이관 (4.1) | Phase 1 |
| `privacy-gate`(자기) | `LLM-RAW-INPUT`·`LOG-BODY`·`PLAINTEXT-PERSIST` 다중 줄 대응 | **Phase 3 시작 전** (4.2) |
| `privacy-gate`(자기) | `LLM-VENDOR-SDK` 동적 import 대응 | Phase 5 시작 전 |

차단 통보는 없다. 위반이 확인되지 않았으므로 `00_privacy-gate_blocking.md`는 만들지 않는다.
