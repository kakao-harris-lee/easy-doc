# Phase 0 — 문서 라이브러리 spike (DOCX / PDF / HWPX / TXT)

**작성 주체:** kotlin-implementer
**대상:** 계획 문서 `docs/plans/2026-08-11-kotlin-react-migration.md` §4.5, §2.3, §5 Phase 0 종료 조건 중 "문서 포팅 가능성이 확인됨"
**성격:** spike. `backend-kotlin/` 정식 골격은 Phase 1 작업이며 이번에 만들지 않았다. 실험 코드는 스크래치패드에만 두고 저장소에는 이 문서만 남긴다.

---

## 0. 한 줄 결론

**문서 포팅은 가능하다.** 계획 §4.5가 경고한 "POI 단순 텍스트 추출로는 부족하다"는 예상은 맞았고, **POI를 usermodel(`XWPFParagraph`/`XWPFTable`)이 아니라 하부 OOXML DOM 순회로 쓰면** Python 추출기와 **블록 단위로 완전히 일치**한다. 기존 fixture 6개 + spike용 합성 fixture 4개 전부 Python 산출값과 일치했다.

다만 **내보내기(export) 쪽에는 조건이 붙는다** — POI 산출 DOCX는 python-docx 산출물과 패키지 구성이 다르고(17항목 → 7항목), zip 컨테이너 바이트는 두 런타임에서 절대 같아지지 않는다. 이 둘은 Phase 4 착수 전에 결정해야 한다(§7).

---

## 1. 형식별 판정

| 형식 | 방향 | 판정 | 조건 |
|---|---|---|---|
| DOCX | 입력 | **가능** | POI usermodel 텍스트 추출 금지. `XWPFDocument.getDocument().getBody().getDomNode()`에서 시작하는 스택 순회 + `w:sectPr`의 `headerReference/footerReference[@w:type="default"]` 직접 해석 필수 |
| DOCX | 출력 | **조건부 가능** | POI 빈 문서에는 `styles.xml`·`theme1.xml`·`fontTable.xml`·`numbering.xml`이 없다. 본문 텍스트는 동등하지만 **Word에서 열었을 때의 서식이 달라진다**. 템플릿 정책 결정 필요(§7-1) |
| PDF | 입력 | **조건부 가능** | `PDFTextStripper.sortByPosition = false`(기본값) 고정 필수. `true`로 켜면 pypdf와 결과가 갈린다(실측). **실제 공공기관 PDF에 대한 동등성은 미검증**(§6-2) |
| HWPX | 입력 | **가능** | StAX `SUPPORT_DTD=false` 등 3개 속성 명시 설정 필수(기본값은 안전하지 않다). zip은 commons-compress `ZipFile` + 실제 읽은 바이트로 예산 계산 |
| HWPX | 출력 | **가능** | 자체 round-trip·mimetype 무압축 첫 항목·생성 결정성 모두 통과. **Python 산출물과의 바이트 동일성은 불가**(§5-2) |
| TXT | 출력 | **가능** | JVM `String.toByteArray(Charsets.UTF_8)`은 BOM을 붙이지 않는다(실측: 선두 `EA B0 80`) |

---

## 2. DOCX 동등성 항목별 대조표

계획 §4.5가 경고한 6가지 + spike에서 추가로 확인한 3가지다. 근거는 전부 **Python 실행값과의 블록 단위 비교**이며, `_join_blocks` 이전의 raw 블록 리스트까지 대조했다(정규화가 차이를 덮지 않게 하기 위해서다).

| # | 지켜야 할 동작 | Python 원본 | POI로 되는가 | 근거 |
|---|---|---|---|---|
| 1 | 표가 본문 흐름 **제자리**에 유지 | `_element_blocks` 문서 순서 순회 | **예** | `sample_table.docx` 블록 일치. `sample_rich.docx`에서 표 뒤 문단(`표 뒤에 오는 문단입니다.`)이 표 **뒤** 위치 유지 |
| 2 | 텍스트박스(`w:txbxContent`) 내용 포함 | 동일 | **예** | `sample_rich.docx`의 `텍스트 상자 안 문장입니다.` 수집 |
| 3 | SDT(구조화 문서 태그) 내용 포함 | 동일 | **예** | spike 합성 fixture. `SDT 안의 문장입니다.` 수집 (기존 fixture에는 SDT가 **없어** 새로 만들어 확인) |
| 4 | `w:ins` 포함 / `w:delText` 제외 | 태그 이름으로 자연히 갈림 | **예** | `변경 추적으로 삽입된 문장입니다.` 포함, `변경 추적으로 삭제된 문장입니다.` 제외 |
| 5 | `mc:Fallback` 하강 중단(중복 방지) | `local_name == "Fallback"` → continue | **예** | `sample_rich.docx`의 텍스트박스 문구가 **정확히 1회**만 등장. 하강을 막지 않으면 2회가 된다 |
| 6 | 로컬 이름 판별로 `a:t`(도형)·`m:t`(수식) 수집 | `str(tag).rpartition("}")[2]` | **예** | spike 합성 fixture. `도형 텍스트입니다.`(`a:t`)·`x+1=2`(`m:t`) 수집. 기존 fixture에는 **둘 다 없어** 새로 만들어 확인 |
| 7 | `is_linked_to_previous` 머리글 건너뛰기 | `_docx_blocks` | **예** | `sample_rich.docx`는 구역 2개(section0 non-linked, section1 linked). `머리글 문구`·`바닥글 문구`가 각 1회만 등장. POI usermodel `getHeaderList()`는 순서가 달라져 쓸 수 없다(§3-1) |
| 8 | 머리글 → 바닥글 순서, 구역 순서 | `for section: for part in (header, footer)` | **예** | 블록 리스트 순서 완전 일치 |
| 9 | 빈 문단·공백 문단 정규화 | `_join_blocks` | **예** | `sample.docx`의 `"   "` 문단이 결과에서 사라짐 |

**대조 방식**: `docxBlocks()` 결과 리스트를 Python `_docx_blocks()` 결과 리스트와 `==` 비교. 예시(`sample_rich.docx`, 15개 블록 전부 일치):

```
["", "첫 문단입니다.", "바깥 표 셀", "중첩 표 셀", "", "표 뒤에 오는 문단입니다.", "",
 "텍스트 상자 안 문장입니다.", "변경 추적으로 삽입된 문장입니다.", "", "둘째 구역 본문입니다.", "",
 "머리글 문구", "", "바닥글 문구"]
```

---

## 3. 포팅에서 반드시 지켜야 할 구현 사항 (spike에서 확정)

### 3-1. POI usermodel을 쓰면 안 되는 두 자리

- **본문**: `XWPFDocument.getParagraphs()`/`getTables()`는 python-docx의 `paragraphs`/`tables`와 같은 한계를 갖는다(표가 뒤로 밀리고, 텍스트박스·SDT·중첩 표가 안 보인다). `getDocument().getBody().getDomNode()`부터 직접 순회한다.
- **머리글/바닥글**: `XWPFDocument.getHeaderList()`/`getFooterList()`는 **파트 목록**이라 "머리글 전부 → 바닥글 전부" 순서가 되어 Python의 "구역별 (머리글, 바닥글)" 순서와 어긋난다. `w:sectPr`을 문서 순서로 훑고 `headerReference[@w:type="default"]`의 `r:id`를 `getRelationById()`로 푸는 방식이어야 한다. `w:type`이 `even`/`first`인 것은 Python이 걷지 않으므로 제외한다(§4.5 한계 그대로).

### 3-2. `w:t` 텍스트는 lxml `.text` 의미로 읽어야 한다

Python은 `element.text`(= 첫 자식 **요소** 앞까지의 텍스트)를 쓴다. DOM의 `getTextContent()`는 자손 텍스트를 전부 모으므로 **의미가 다르다**. 첫 자식부터 텍스트 노드가 이어지는 동안만 모으고 요소를 만나면 멈추는 헬퍼가 필요하다. (현재 `w:t`에 자식 요소가 있는 실문서는 못 봤지만, 다르면 조용히 어긋난다.)

### 3-3. StAX 기본값은 안전하지 않다 — 3개 속성을 명시한다

```kotlin
factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
```

### 3-4. zip 예산은 "실제 읽은 바이트"로 세고, 중앙 디렉터리를 읽는 구현을 쓴다

- `java.util.zip.ZipInputStream`은 로컬 헤더만 보므로 Python `infolist()`(중앙 디렉터리)와 순회 대상이 갈릴 수 있다. **commons-compress `ZipFile` + `SeekableInMemoryByteChannel`**이 in-memory에서 중앙 디렉터리를 읽는 대응물이고, POI의 전이 의존성이라 새 의존성이 아니다.
- 선언 크기를 믿지 않고 `read(buf, 0, min(chunk, budget+1))`로 세는 Python 방식을 그대로 옮긴다.

---

## 4. 실제로 돌린 명령과 결과

모든 명령은 실행했고 출력은 아래가 실측값이다. 돌리지 않은 항목은 §6에 "미검증"으로 분리했다.

### 4-1. Python 기준값(oracle) 생성

```
uv run python <스크래치패드>/oracle.py          # extract_text + _docx_blocks + build_hwpx round-trip
uv run python <스크래치패드>/extra_fixtures.py  # SDT/a:t/m:t docx, 다단 pdf, 위조 크기 zip
```

기존 fixture 6개에 대한 Python 산출값:

| fixture | Python 결과 |
|---|---|
| `sample.docx` | `쉬운 글 변환 안내\n이 문서는 추출 테스트용 예시입니다.` |
| `sample_table.docx` | `쉬운 글 변환 안내\n구분\n내용\n접수 기간\n3월 1일부터 3월 31일까지` |
| `sample_rich.docx` | `첫 문단입니다.\n바깥 표 셀\n중첩 표 셀\n표 뒤에 오는 문단입니다.\n텍스트 상자 안 문장입니다.\n변경 추적으로 삽입된 문장입니다.\n둘째 구역 본문입니다.\n머리글 문구\n바닥글 문구` |
| `sample.pdf` | `첫째 쪽 안내문입니다.\n둘째 쪽 안내문입니다.` |
| `empty.pdf` | **거부** `DocumentExtractionError: 텍스트를 추출할 수 없습니다 (스캔 PDF는 지원 예정)` |
| `sample.hwpx` | `한글 문서 추출 확인\n문단 하나가 여러 조각으로 나뉘어도 이어 붙는다.\n둘째 구역의 문장입니다.` |

### 4-2. Kotlin spike 테스트

```
gradle --no-daemon --console=plain clean test   # BUILD SUCCESSFUL, 5 tasks, 테스트 JVM -Xmx256m
```

| 테스트 | 결과 |
|---|---|
| 기존 fixture 6개 추출 = Python 값 | **PASS** (6/6, 거부 메시지까지 문자열 동일) |
| docx 블록 순회 = Python `_docx_blocks` | **PASS** (3/3 파일, 리스트 완전 일치) |
| SDT·`a:t`·`m:t` 수집 = Python | **PASS** (블록 7개 일치) |
| `empty.pdf` 거절 | **PASS** (동일 메시지) |
| hwpx DTD(billion laughs) 거부 | **PASS** — `DTD 선언은 허용하지 않습니다` |
| hwpx **UTF-16** 인코딩 DTD 거부 | **PASS** — Python 주석이 지적한 "바이트 스캔은 UTF-16으로 뚫린다"는 함정을 StAX도 회피 |
| hwpx XXE(`file://` 외부 엔터티) | **PASS** — 파일 내용 유출 없음, DTD 단계에서 차단 |
| zip bomb 80MB | **PASS** — `hwpx 파일이 너무 큽니다` |
| zip bomb **1GiB / 힙 256MB** | **PASS** — 거부, 힙 증가량 0MB (스트리밍·경계 읽기 동작 확인) |
| hwpx 생성 → 자체 추출 round-trip | **PASS** (§2.3 요구 조건) |
| hwpx mimetype 첫 항목 · STORED | **PASS** (`method=0`) |
| hwpx 생성 결정성(같은 입력 → 같은 바이트) | **PASS** |
| **Python이 만든 hwpx → Kotlin 추출** | **PASS** (교차 런타임) |
| docx DOCTYPE(billion laughs) | **PASS** — POI가 `disallow-doctype-decl`로 차단. 대조군(정상 재포장)이 통과하므로 재포장 탓이 아님 |
| OLE2 매직 판별 | **PASS** |
| TXT BOM 없음 | **PASS** |

### 4-3. 교차 런타임 내보내기 대조

```
uv run python -c "... render_export(DOCX) ... extract_text(<POI 산출물>) ..."
```

| 경로 | 결과 |
|---|---|
| Python 생성 DOCX → Python 추출 | `쉬운 글 안내 & 정리\n첫째 문단입니다.둘째 줄입니다.\n다음 문단입니다.\n셋째 문단 <중요>.` |
| **POI 생성 DOCX → Python 추출** | **위와 동일** (style 유무 양쪽 다) |
| POI 생성 DOCX → Kotlin 추출 | 위와 동일 |
| Python 생성 HWPX → Kotlin 추출 | 일치 |

`<w:br/>`가 `w:t` 밖에 있어 줄이 붙는 성질(`첫째 문단입니다.둘째 줄입니다.`)까지 양쪽이 같다.

### 4-4. Python 기준선 무손상 확인

```
uv run pytest tests/ingest -q   →  57 passed in 0.42s
git status --porcelain          →  spike 산출물 없음 (§8)
```

---

## 5. 발견된 불일치·한계 (조용히 넘기지 않는다)

### 5-1. 위조 크기 zip의 **오류 메시지**가 갈린다 — 사용자 노출 문자열 차이

같은 입력(선언 크기를 1024로 위조한 80MB 압축 폭탄)에 대해:

- Python: `hwpx 파일을 읽을 수 없습니다 (파일이 손상되었습니다)` — `zipfile`이 CRC/크기 불일치를 먼저 잡아 `BadZipFile`
- Kotlin: `hwpx 파일이 너무 큽니다` — commons-compress가 실제 바이트를 세다 예산에 먼저 걸림

둘 다 **거부하므로 보안상 문제는 없다**. 그러나 이 문자열은 사용자에게 그대로 나가는 값이라 계약 표면이다. Phase 4에서 어느 쪽에 맞출지 정해야 한다.

### 5-2. zip 컨테이너 바이트는 Python과 절대 같아지지 않는다

같은 항목 이름·같은 내용·같은 고정 시각(1980-01-01)으로 만들어도:

```
ZIP_BYTE_IDENTICAL=false   java=434B  python=348B   첫 차이 offset=4 (java=0x0A, python=0x14)
```

- offset 4는 local file header의 *version needed to extract*. Java는 `0x000A`, Python `zipfile`은 `0x0014`.
- 크기 차이는 `java.util.zip.ZipOutputStream`이 DEFLATED 항목에 **data descriptor**를 붙이기 때문이다(Python은 seekable 버퍼라 로컬 헤더를 되돌아가 채운다).

`app/easyread/hwpx.py`가 고정 타임스탬프를 쓰는 목적("같은 내용 → 같은 바이트")은 **Kotlin 안에서는 유지된다**(결정성 테스트 PASS). 하지만 **Python 산출 바이트와의 동일성은 성립하지 않는다.** parity fixture를 바이트 해시로 잡으면 반드시 깨진다 — 정규화된 추출 텍스트로 비교해야 한다.

### 5-3. POI 산출 DOCX는 python-docx 산출물과 패키지 구성이 다르다

| 산출물 | 크기 | 항목 수 | 구성 |
|---|---|---|---|
| python-docx 기본 템플릿 | 36,734 B | 17 | `styles.xml`, `theme1.xml`, `fontTable.xml`, `numbering.xml`, `webSettings.xml`, `docProps/thumbnail.jpeg`, `customXml/*` 포함 |
| POI 빈 문서 (style 미생성) | 2,378 B | 7 | `document.xml`, `settings.xml`, `docProps` 2종, rels 2종, Content_Types |
| POI 빈 문서 (`createStyles()` 사용) | 2,697 B | 8 | 위 + `styles.xml` |

**본문 텍스트는 동등**하지만, `_render_docx`가 쓰는 `add_heading(title, level=1)`의 **Heading 1 스타일 정의 자체가 POI 빈 문서에는 없다.** 사용자가 Word에서 열면 제목이 제목처럼 보이지 않는다. 제품에 보이는 차이다.

### 5-4. StAX의 DTD 거부 판정을 **예외 메시지 문자열**에 의존하면 안 된다

spike 구현은 `SUPPORT_DTD=false`로 두고 `XMLStreamException`의 메시지에 `DTD`/`DOCTYPE`이 있는지로 사유를 갈랐다. 이 환경에서는 통했지만 **JDK 예외 메시지는 로케일에 따라 번역된다** — 실제로 이 실행에서 POI가 낸 메시지는 한국어였다:

> `DOCTYPE은 "http://apache.org/xml/features/disallow-doctype-decl" 기능이 true로 설정된 경우 허용되지 않습니다.`

지금은 `DOCTYPE`이라는 영문 토큰이 남아 매칭됐을 뿐이고, 완전 번역되는 로케일에서는 "DTD 선언은 허용하지 않습니다"가 아니라 "파일이 손상되었습니다"로 오분류된다.

**Phase 4 권장 구현**: `SUPPORT_DTD=true` + `IS_SUPPORTING_EXTERNAL_ENTITIES=false`로 두고 `XMLStreamConstants.DTD` **이벤트를 직접 받아 우리 예외를 던진다.** 이것이 Python의 `expat.StartDoctypeDeclHandler` → `_DtdNotAllowed`와 정확히 같은 구조이고 로케일에 의존하지 않는다. (DOCTYPE 선언 시점에 끊으므로 엔터티 확장은 일어나지 않는다.)

### 5-5. spike가 축약한 것 — 포팅 시 채워야 한다

- `_diagnose_ole2`의 **3분기**(`EncryptedPackage` → 암호 안내 / `WordDocument` → 구버전 doc 안내 / 그 외)를 spike는 1개로 합쳤다. 세 안내 문구가 다르므로 포팅 시 UTF-16LE 스트림 이름 검색을 그대로 옮겨야 한다.
- `iter_pdf_pages(page_range)`·`extract_pdf_range`·`iter_hwpx_sections`의 부분 추출 경로는 다루지 않았다.
- `_broken()`의 로깅 규약(형식명·바이트 길이·예외 **타입**만)은 spike에서 생략했다. 본문·파일명이 로그로 새지 않아야 하는 자리라 포팅 시 필수다.
- PDF 암호 분기(`FileNotDecryptedError` ↔ PDFBox `InvalidPasswordError`)는 코드에 자리만 두고 **실제 암호 PDF로 검증하지 않았다.**

---

## 6. 미검증 항목 (판정에 반영하지 않았다)

1. **실제 한컴 오피스·MS Word로 저장한 실문서**. 저장소 fixture는 전부 스크립트 합성물이고 spike용 추가 fixture도 마찬가지다. §2.3이 요구하는 "한컴 실제 호환성은 사람 검증"은 그대로 남는다.
2. **실제 공공기관 PDF의 pypdf ↔ PDFBox 동등성**. 합성 다단 PDF에서는 기본 설정이 일치했지만(`PDF_LAYOUT_EQUIVALENT=true`), 표·머리글·다단이 섞인 실제 안내문에서 두 라이브러리가 갈릴 위험은 **줄어들지 않았다**. `sortByPosition=true`로 켜면 이미 갈린다(`왼쪽 단 첫 줄 오른쪽 단 첫 줄` vs pypdf `오른쪽 단 첫 줄왼쪽 단 첫 줄`) — 설정 하나로 결과가 바뀐다는 사실 자체가 위험 신호다.
3. **10MB 업로드 상한·`MAX_EXTRACTED_CHARS` 경계**. 상한 상수만 옮겼고 경계값 테스트는 API 계층 몫이라 Phase 4로 미뤘다.
4. **암호 걸린 DOCX/PDF 실파일**. OLE2 매직은 합성 바이트로만 확인했다.
5. **Spring Boot BOM과의 버전 정렬**. 이번 spike는 Boot 없이 POI/PDFBox만 검증했다. Boot BOM이 commons-io·log4j-api 버전을 끌어당길 수 있으므로 Phase 1에서 재확인이 필요하다.

---

## 7. Phase 4 착수 전에 결정해야 할 사항

| # | 결정 사항 | 선택지 | 기본 권고 |
|---|---|---|---|
| 1 | **DOCX 내보내기 서식 정책** (§5-3) | (a) POI 빈 문서 + `createStyles()`로 Heading 1만 직접 정의 (b) python-docx 기본 템플릿과 동등한 `template.docx`를 리소스로 동봉하고 POI가 열어서 append (c) 서식 차이를 수용 | **(b)** — 사용자에게 보이는 산출물이라 "본문만 같으면 됨"으로 넘기기 어렵다. 다만 템플릿 파일을 저장소에 넣는 결정이라 리더 승인 필요 |
| 2 | **내보내기 바이트 동일성 계약의 범위** (§5-2) | (a) Kotlin 내부 결정성만 요구 (b) Python과 바이트 동일까지 요구 | **(a)** — (b)는 stock `java.util.zip`으로 달성 불가. parity fixture는 **정규화된 추출 텍스트**로 잡아야 한다. `parity-verifier`와 합의 필요 |
| 3 | **DTD 거부 판정 방식** (§5-4) | (a) `SUPPORT_DTD=false` + 메시지 매칭 (b) `SUPPORT_DTD=true` + `DTD` 이벤트 직접 처리 | **(b)** — 로케일 비의존. Python 구조와 1:1 |
| 4 | **오류 메시지 매핑표** (§5-1) | 위조/손상 zip의 사용자 문구를 Python에 맞출지, Kotlin 동작을 계약으로 승격할지 | Python 문구가 현행 계약이므로 **Python에 맞추는 쪽**. 단 예산 검사와 무결성 검사의 선후를 바꾸는 일이라 구현 비용 확인 필요 |
| 5 | **실문서 fixture 확보** (§6-1, §6-2) | 한컴/Word로 저장한 실제 파일과 실제 공공기관 PDF를 개인정보 없는 형태로 확보할지 | **확보 권고.** 없으면 "문서 추출·내보내기 주요 fixture 불일치"(§5 Phase 7 즉시 중단 기준)를 절체 전에 검출할 방법이 없다 |
| 6 | **PDF 추출 설정 고정** (§6-2) | `sortByPosition` 등 `PDFTextStripper` 파라미터를 version catalog처럼 한 곳에 고정 | **고정 권고.** 기본값 의존은 PDFBox 업그레이드 때 조용히 깨진다 |

---

## 8. 검증한 버전 조합

spike에서 **실제로 해석·실행된** 조합이다. Phase 1의 `gradle/libs.versions.toml`과 dependency locking의 출발점으로 쓴다.

| 항목 | 버전 | 비고 |
|---|---|---|
| JVM | Temurin **21.0.4+7 LTS** | 저장소 환경 기본값. `jvmToolchain(21)` |
| Gradle | **9.1.0** | 저장소 환경 기본값 |
| Kotlin (JVM plugin) | **2.2.0** | Gradle 9.1.0 임베디드 Kotlin과 동일 |
| Apache POI (`poi-ooxml`) | **5.4.1** | |
| ├ `poi` | 5.4.1 | |
| ├ `poi-ooxml-lite` | 5.4.1 | |
| ├ `xmlbeans` | 5.3.0 | DOM 순회의 실제 기반 |
| ├ `commons-io` | 2.18.0 | |
| ├ `commons-codec` | 1.18.0 | |
| ├ `commons-collections4` | 4.4 | |
| ├ `commons-math3` | 3.6.1 | |
| ├ `SparseBitSet` | 1.3 | |
| ├ `curvesapi` | 1.08 | |
| └ `log4j-api` | 2.24.3 | **API만**. 바인딩 없음 → 실행 시 `Log4j API could not find a logging provider` 경고. Phase 1에서 `log4j-to-slf4j` 연결 필요 |
| Apache PDFBox | **3.0.5** | `Loader.loadPDF(ByteArray)` (2.x의 `PDDocument.load`와 API가 다르다) |
| ├ `pdfbox-io` | 3.0.5 | |
| ├ `fontbox` | 3.0.5 | |
| └ `commons-logging` | 1.3.5 | 역시 바인딩 정리 필요 |
| commons-compress | **1.27.1** | POI 전이 의존성. 명시 선언해도 같은 버전 |
| └ `commons-lang3` | 3.16.0 | |

**주의**: 이 조합은 Spring Boot BOM **없이** 해석한 결과다. Boot를 붙이면 `commons-io`·`log4j-api`·`commons-logging` 버전이 BOM 쪽으로 끌려갈 수 있다. §3.1이 "각자 임의 버전으로 섞지 않는다"고 한 지점이므로 Phase 1에서 BOM 적용 후 다시 잠근다.

---

## 9. 저장소 정리 확인

- spike Gradle 프로젝트·빌드 캐시·`GRADLE_USER_HOME`·합성 fixture·oracle JSON은 **전부 스크래치패드**에 만들었다. 저장소 안에 `.gradle/`·`build/`·`backend-kotlin/`을 만들지 않았다.
- `git status --porcelain` 결과에 이 spike가 만든 항목은 없다(이 문서와 `00_progress.md` 갱신 제외).
- `app/`·`tests/`·`frontend/`·`.claude/`와 `tests/ingest/fixtures/`의 파일은 **읽기만** 했다. `uv run pytest tests/ingest -q` → **57 passed**로 무손상 확인.
- spike에 쓴 문서는 전부 합성물이다. 실제 사용자 문서는 쓰지 않았다.
