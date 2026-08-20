# Phase 4 `documents` 작업 단위 — 구현 산출물

**작성:** kotlin-implementer / **일자:** 2026-08-20 / **상태:** **진행 중 — 계획 §7.2 의 C3 까지.**
**계획(정본):** `04_kotlin-implementer_documents-plan.md` (커밋 `5261cfe`, §9.2 는 이 배치에서 추가).

> 이 문서는 단위가 끝날 때까지 **이어 쓴다.** 계획 §7.2 의 C8 이 가리키는 파일이 이것이다 —
> 커밋마다 새 파일을 만들면 「무엇이 이 단위의 결론인가」가 흩어진다.

---

## 0. 지금까지 한 것 / 하지 않은 것 (한눈에)

| | |
|---|---|
| **끝난 것** | **C1 — 문서 추출기(docx·pdf·hwpx)와 파서 방어** (§1~§6) · **C2 — 문서·변환 저장 경로**(§A~§F) · **C3 — `POST /documents`**(아래 「C3」 절) |
| **하지 않은 것 (이 단위 잔여)** | ~~C3~~C4~C8. **표 18 TRACE 카나리 회귀**는 리더 판정 1 로 **C3 에 넣었고 실행했다**(아래 「C3」 §III-3) |
| **검사** | C1·C2 모두 Kotlin build/ktlint/detekt/moduleBoundaryCheck **통과** · Python ruff/mypy/pytest **통과** · 개인정보 스캐너 **통과(BLOCK 0)** · parity harness **통과** |
| **워킹 트리** | 커밋 뒤 clean |

## 1. C1 이 만든 것 — 모듈별

### 1.1 `core`

| 파일 | 내용 | Python 원본(참고) |
|---|---|---|
| `core/document/SourceFormat.kt` | 형식 enum(`text`·`docx`·`pdf`·`hwpx`) · `wireName` · `isZipContainer` · `ofUploadFilename` | `app/services/documents.py` 의 `TEXT_SOURCE_FORMAT`·`_source_format`, `app/ingest/extractors.py::_FORMATS` |

**계획과 다른 배치**: 계획 §2.2 는 이 파일을 C2 로 잡았다. C1 의 포트가 형식을 함께 돌려주므로
앞당겼다(계획 §9.2 **D-e**).

### 1.2 `application`

| 파일 | 내용 | Python 원본 |
|---|---|---|
| `application/document/DocumentTextExtractor.kt` | 포트 `fun interface` + `ExtractedDocument`(형식 + 본문) | `app/ingest/extractors.py::extract_text` |

`ExtractedDocument` 를 `data class` 로 만들지 않았다 — 컴파일러가 만드는 `toString()` 에
본문이 실린다. 손으로 쓴 `toString()` 이 길이만 남기고, `SensitiveToStringReachTest` 의
「일반 class 의 손으로 쓴 toString」 축이 그것을 실제로 시험한다.

### 1.3 `infrastructure/ingest`

| 파일 | 내용 | Python 원본 |
|---|---|---|
| `DocumentExtractors.kt` | 디스패치 · OLE2 선행 판정 · zip 예산 호출 | `extract_text` + `_FORMATS` |
| `DocxExtractor.kt` | POI **DOM 순회** · `w:sectPr` 머리글/바닥글 해석 · `mc:Fallback` 하강 중단 | `_extract_docx`·`_docx_blocks`·`_element_blocks` |
| `PdfExtractor.kt` | 쪽별 추출 · 재현성 설정 고정 · 암호/스캔 갈래 | `_extract_pdf`·`iter_pdf_pages` |
| `HwpxExtractor.kt` | 구역 정렬 · StAX 이벤트 상태 기계 | `_extract_hwpx`·`_read_hwpx_sections`·`_hwpx_blocks` |
| `ZipBudget.kt` | commons-compress 기반 **실제 읽은 바이트** 예산 | `_ensure_zip_within_budget` |
| `SecureXml.kt` | `SUPPORT_DTD=false` 외 3속성 명시 | `expat.StartDoctypeDeclHandler` 자리 |
| `Ole2Diagnosis.kt` | 매직 4바이트 + UTF-16LE 스트림 이름 **3분기** | `_diagnose_ole2` |
| `ExtractedTextBuilder.kt` | 블록 정규화 + **누적 길이 상한** | `_join_blocks` + `_ensure_extracted_length` |
| `ExtractionLimits.kt` | 상한 상수 · 사용자 문구 · 실패 로그 규약 | 모듈 상수 + `_log_failure`·`_broken` |
| `OoxmlDom.kt` | 자식 요소 · 로컬 이름 · **lxml `.text` 의미의 텍스트** | `_element_blocks` 의 DOM 읽기 규약 |
| `PoiZipDefenses.kt` | POI 전역 `ZipSecureFile` 값 | 대응 없음(신규) |
| `ConcurrencyLimitedTextExtractor.kt` | 동시 추출 제한 데코레이터 | `app/api/deps.py` 의 `CapacityLimiter(4)` |
| `IngestConfiguration.kt` | 빈 조립(제한을 두른 포트 하나만 노출) | 대응 없음(신규) |

### 1.4 version catalog · 락파일

POI 5.4.1 · PDFBox 3.0.5 · commons-compress **1.28.0** · commons-io **2.20.0** ·
xmlbeans 5.3.0 · log4j-to-slf4j(BOM). 다섯 모듈 락파일을 **같은 커밋에서** 갱신했다.

---

## 2. 원본과 **의도적으로 다르게** 구현한 지점 (전건)

> 기준은 Python 출력이 아니라 요구사항이다(master-plan 6.2 · `CLAUDE.md`).
> 아래는 전부 「어느 쪽이 요구에 맞는가」를 판단해 고른 것이고, 판단 근거를 함께 적는다.

| # | 자리 | Python | Kotlin | 왜 |
|---|---|---|---|---|
| **A-1** | hwpx DTD 거절 문구 | `hwpx 파일을 읽을 수 없습니다 (DTD 선언은 허용하지 않습니다)` — 전용 문구 | `... (파일이 손상되었습니다)` — 손상과 **같은 문구** | `SUPPORT_DTD=false`(OWASP 1차 통제)를 고르면 JDK 예외 메시지가 로케일에 따라 번역돼 사유를 가를 수 없다. **사유를 메시지로 가르지 않는** 것이 답이고, 계약은 이 구분을 요구하지 않는다. 계획 §1.5 지점 1 |
| **A-2** | 추출 길이 상한 발화 시점 | 다 이어 붙인 **뒤** 검사 | **누적 중** 검사 | 사후 검사는 이미 수백만 자가 힙에 올라온 뒤라 "거절"만 하고 "소모"는 못 막는다. 재는 대상(이어 붙인 결과 길이)이 같아 **같은 입력에 같은 판정** |
| **A-3** | hwpx 구역 읽기 | 디스패치는 64KB 청크인데 **구역 읽기만 `read(budget+1)` 한 번** | **두 자리 모두 청크** | 원본은 구역 하나가 수십 MB 를 단번에 할당할 수 있었다. I-10 이 요구하는 성질은 "실제 읽은 바이트로 센다"이지 "Python 과 같다"가 아니다. 계획 §9 질문 ⑪ |
| **A-4** | `mc:Fallback` 스킵 | 로컬 이름만 보아 **모든** `*:Fallback` 절단 | `mc` 네임스페이스일 때만 | 목적은 `mc:AlternateContent` 이중 수집 방지 하나이고 그 범위를 넘는 절단은 **조용한 누락**이다. 계획 §9 질문 ⑫ |
| **A-5** | 줄 나눔·공백 | `splitlines()` 는 `\u000B`·`\u000C`·`\u001C`~`\u001E`·`\u0085`·`\u2028`·`\u2029` 에서도 나눈다 / `strip()` 은 `\u00A0` 를 턴다 | Kotlin `lineSequence()`(`\r\n`·`\n`·`\r`) / `trim()`(`\u00A0` 를 **남긴다**) | 요구는 "공백뿐인 줄 없이 개행 하나로 이어진 텍스트"다. 줄바꿈이 아닌 제어문자에서 줄을 나누는 것도, 줄바꿈 없는 공백을 지우는 것도 그 요구가 시키는 일이 아니다. **오늘 fixture 로는 갈림이 관측되지 않는다**(전건 일치) — 실제 문서에서 갈릴 수 있어 기록해 둔다 |
| **A-6** | PDF 줄 구분자 | pypdf 는 `\n` | PDFBox 기본값이 `System.lineSeparator()` → **`"\n"` 으로 고정** | 고정하지 않으면 Linux CI 와 다른 OS 개발기가 서로 다른 텍스트를 낸다. `pageEnd` 도 같은 기본값이라 함께 고정. 계획 §1.5 지점 3 ⑴ |
| **A-7** | 위조 크기 zip 의 거절 문구 | `파일이 손상되었습니다`(zipfile 이 CRC 를 먼저 잡는다) | 구현이 정하지 않는다 — 예산 초과와 손상 중 먼저 걸리는 쪽 | spike §5-1 이 이미 기록한 갈림이다. **요구는 "거부"이고 둘 다 거부**다. 테스트도 문구가 아니라 예외 타입으로 단언한다 |

---

## 3. §5 파서 방어 D-1~D-17 — 이번에 선 것과 남은 것

| # | 상태 | 어디에 |
|---|---|---|
| D-1 업로드 바이트 상한 | **C3** (L0 multipart 설정 + L1 서비스 판정) | — |
| D-2 상한 초과 예외 매핑 | **C3** | — |
| D-3 초과분 삼키기 | **C3** | — |
| D-4 추출 길이 상한 | **섰다** | `ExtractedTextBuilder` · `ExtractedTextBuilderTest`(경계값·초과·블록 누적) |
| D-5 zip 예산 | **섰다** | `ZipBudget` · `ZipBudgetTest`(기제 시험 + **취약점 실증**) |
| D-6 DTD·외부 엔터티(hwpx) | **섰다**(계획 §9.2 D-b 로 처방 개정) | `SecureXml` · `HwpxExtractorTest`(UTF-8·UTF-16·XXE) |
| D-7 DOCTYPE(docx) | **섰다** | `DocxExtractorTest`(주입 + **대조군 재포장 통과**) |
| D-8 POI 자체 zip 방어 | **섰다 — 단 구조 단언이다**(§9.2 D-d) | `PoiZipDefenses` · `IngestDefensesTest` |
| D-9 PDF 메모리 상한 | **API 가 없다**(§9.2 D-c). 방어는 D-1·D-4·D-14 | `PdfExtractor` KDoc |
| D-10 스캔 PDF 거절 | **섰다** | `PdfExtractorTest`(`empty.pdf`) |
| D-11 암호 PDF | **섰다(코드)** / **실파일 미검증** | `PdfExtractor` — `isEncrypted()` 사전 거름 없음 |
| D-12 구버전 `.doc` 3분기 | **섰다** | `Ole2Diagnosis` · `DocumentExtractorsTest`(세 문구가 서로 다름까지 단언) |
| D-13 지원 확장자 집합 | **부분** — 상수와 안내 유도는 섰다. **계약에서 읽어 케이스를 유도하는 P-26 은 C3** | `SourceFormat` · `DocumentExtractorsTest` |
| D-14 동시 추출 제한 | **섰다** | `ConcurrencyLimitedTextExtractor` · `IngestDefensesTest`(동시 진입 최대치 계측 + 배선 단언) |
| D-15 스택트레이스 미유출 | 기존 회귀가 담당 | `application.yml` + `GlobalExceptionHandler` |
| D-16 로그 규약 | **섰다** | `ExtractionFailureLog` · `ExtractionLoggingTest` |
| D-17 파일 이름 미저장·미로깅 | **섰다** | `SourceFormat.ofUploadFilename`(쓰고 버린다) · `ExtractionLoggingTest` |

---

## 4. 이번 커밋이 **열지 못한** 도달 (정직하게)

- **X1(짝 없는 서로게이트)의 도달은 아직 0 이다.** `PlainBody` 를 만드는 제품 코드가
  여전히 `AesGcmContentCipher.decrypt` 한 곳뿐이다. 계획 §6 대로 **C2(저장 경로)** 와
  **C3(JSON 붙여넣기)** 이 그 도달을 연다. C1 은 추출기까지이고 저장을 하지 않는다.
- **Q-12(Jackson 3 의 짝 없는 서로게이트 이스케이프)는 여전히 미확인.** 계획이 「C3 착수
  전에 케이스 1건으로 실측」이라 적었고 그대로 남아 있다.
- **Q-1b(`setMaxFileCount` 가 `InputStream` 경로에서도 강제되는가)도 미확인.**
  우리 1차 방어가 항목 수가 아니라 **바이트 예산**이라, 남는 위험은 "항목이 아주 많고 전체
  크기는 작은 아카이브" 하나이고 그 입력은 예산을 넘지 않으므로 파싱 시간만 늘고 메모리는
  늘지 않는다. `PoiZipDefenses` KDoc 에 같은 내용을 적었다.
- **실문서 fixture 없음** — 한컴/Word 저장본, 실제 공공기관 PDF, 암호 걸린 실파일 셋 다
  없다(계획 §9 질문 ⑧). 지금 fixture 는 전부 합성물이다.

---

## 5. `parity-verifier` 에게 (모듈 완료 통보)

**완료 모듈**: `infrastructure/ingest`(문서 추출) · `core/document/SourceFormat` ·
`application/document/DocumentTextExtractor`.
**대응 Python 원본**: `app/ingest/extractors.py` 전체(단 `iter_pdf_pages(page_range)`·
`extract_pdf_range`·`iter_hwpx_sections` 는 **포팅하지 않았다** — 계획 §9.1 대로 documents
경로가 쓰지 않고 골든셋 수집 스크립트 전용이다).

**참고값 대조 결과**: `tests/ingest/fixtures/doc-spike/{repo-fixtures,spike}-oracle.json` 의
**전 항목이 일치**했다(docx 3종의 raw 블록 + 이어 붙인 본문, `sdt_shape_math.docx` 블록·본문,
`sample.pdf`, `layout.pdf`, `sample.hwpx`, `empty.pdf` 거절 문구). **갈림 0건.**

**요청**: 계획 §9 질문 ⑨ — `parity/fixtures/` 에 `ingest` 도메인을 신설할지. 지금은 신설하지
않고 oracle 을 **Kotlin test resources 안의 참고값**으로 썼다. 형식 변경은 합의 후에만 한다.

---

## 6. 검사 결과 (미실행은 「미실행」으로 적는다)

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 품질·빌드·테스트 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **통과** (`--rerun-tasks` 판; 그 전 `--continue` 판도 통과). 다섯 모듈 test 태스크 전부 실행됨(Docker 가용 → Testcontainers 포함) |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | **통과** |
| parity | `./gradlew parityHarness` | **통과** — C1 은 parity 도메인을 건드리지 않았고 선언·산출 대조가 그대로 선다 |
| 개인정보 스캐너 | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **통과 (exit 0, BLOCK 0)**. 새 코드는 `ZIP-NO-BUDGET` WARN 4건에 잡히는데 전부 예산을 실제로 거는 자리다(탐지기가 zip API 사용처를 표시하는 형태) |
| Python 무변경 확인 | `uv run ruff check .` · `uv run mypy . .claude` · `uv run pytest` | **통과** (1401 passed, 68 skipped, 5 xfailed) |
| Kotlin 게이트 도달 | `uv run pytest tests/test_kotlin_gate_reach.py tests/test_raw_control_chars.py` | **통과** (94 passed) |
| 골든셋 | `uv run pytest tests/golden` | **해당 없음** — 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았다 |
| 계약 음성 대조 N-23~N-28 | — | **미실행.** 전부 C3 이후 몫이다(계획 §8.5) |

**게이트 명령에 파이프를 쓰지 않았다** — `run_gate.sh` 경유로 돌려 종료 코드를 남겼다.

---

## 7. 다음 세션이 이어받을 것

### 7.1 어디까지 했나

계획 §7.2 의 **C1 만** 끝났다. 게이트 덩어리 **G-α 는 C1 + C2** 이므로 **첫 덩어리는 아직
닫히지 않았다.**

### 7.2 다음 커밋 — **C2**

`feat(kotlin): 문서·변환 저장 경로 — 단일 트랜잭션과 봉투`

- `core/document/**` 나머지(`ConversionStatus` · `Document` · `Conversion` · `MaskedItemView` ·
  `DocumentListing` · `TitleRules`)
- `application/document/DocumentPorts.kt`(`DocumentRepository` · `ConversionRepository` ·
  `ConversionQueue`) — **`DocumentTextExtractor` 는 이미 있다**(별도 파일). 합치지 말 것
- `infrastructure/document/**`(`JdbcDocumentRepository` · `JdbcConversionRepository` ·
  `MaskedItemCodec` · `DocumentConfiguration`)
- **§4.2 업로드 저장**(한 트랜잭션 · 봉투 2값 명시 INSERT)
- **§4.3 재암호화 4조건**(`rewriteEnvelope` — 단일 UPDATE · NULL 보존 · 전체 중단 · 낙관적 조건)
- **§4.4 X9/F-6** — `@DynamicPropertySource` 로 실행 시점 난수 키 + `KeyCheckValue.of()` KCV 를
  넣고 자기점검을 **켠 채** 뜨는 컨텍스트에서 실제 INSERT/SELECT. 키를 뺀 컨텍스트(C-P)의
  503 케이스를 **함께** 둔다
- **§4.5 X2** — 응답 DTO 는 C3 이지만, 타입 부재 단언은 DTO 가 생기는 커밋과 **같은 커밋**이다
- `V5__conversion_jobs.sql` + 큐 등록

**선결**: 계획 §7.1 **P4**(compose 기동 스모크)가 C2 를 막는다. 아직 **미실행**이다.

### 7.3 이 단위에서 **아직 안 한** 마감 항목

| 항목 | 원장 마감 | 지금 상태 | 어디로 |
|---|---|---|---|
| **X9/F-6** 실제 키 통합 테스트 | documents 단위 | 미착수 | **C2** |
| **X5/F-5** 재암호화 4조건 | documents 단위 | 미착수 | **C2** |
| **X2** `PlainBody` 웹 직렬화 fail-closed | 응답 DTO 신설과 동시 | 미착수 | **C3**(DTO 생기는 커밋) |
| **타이밍 X3 의 codex A-6 처방**(다중 실행·절대 격차·분포, M-3b 와 한 배치) | documents 단위 | **미착수** | 미배정 — **다음 세션이 커밋 하나로 잡아야 한다** |
| **표 18** TRACE 로거 3종 | Phase 4 문서 본문 진입 전 | **미착수** | **C3 이전** — 계획 §9.2 **D-f**, 리더 확인 필요 |
| **K-2** `CountingDataSource` 의 `JdbcClient` 전제 장치화 | Phase 4 | 미착수 | C2(문서 repository 가 `JdbcClient` 를 쓰는 커밋) |

### 7.4 판단이 걸린 자리 (리더/다른 레인)

1. **계획 §9.2 D-f — 표 18 마감 해석.** 「문서 본문 진입 전」을 C3 이전으로 읽고 C1 에서 뺐다.
   원장 조건 18 을 닫는 판정이 리더 몫이므로 **확인이 필요하다.**
2. **계획 §9.2 D-a — commons-compress/commons-io 를 spike 값이 아닌 그래프 합의값으로 올렸다.**
   버전 선택은 §9 승인 사항에 준하는 자리라 알린다(근거는 실측, 전문은 카탈로그 주석).
3. **계획 §9 질문 ⑨** — parity `ingest` 도메인 신설 여부(`parity-verifier` 합의 사항).
4. **계획 §9 질문 ⑧** — 실문서 fixture 확보. 지금 상태로는 "주요 fixture 불일치"를 절체 전에
   검출할 방법이 없다.
5. **리더 판정 L-1(계약 `EnqueueFailed`/502 를 lease 큐에 맞게 개정)** 이 계약 레인에 내려갔다.
   C2 의 큐 등록은 그 판정을 전제로 **같은 트랜잭션**에 둔다 — 계획 §9 질문 ② 의 잠정 전제
   (원자성을 일부러 버림)는 **쓰지 않는다.**


---

# C2 — 문서·변환 저장 경로 (2026-08-20)

> 계획 §7.2 의 두 번째 커밋. **HTTP 표면은 만들지 않는다** — 컨트롤러·DTO 는 C3 이다.
> 계획에서 벗어난 지점은 **계획 문서 §9.2-bis 에 먼저 적고** 코드가 그대로 들어갔다.

## A. C2 가 만든 것 — 모듈별

### A.1 `core/document`

| 파일 | 내용 | 원본(참고) |
|---|---|---|
| `DocumentLimits.kt` | `MAX_CONVERTIBLE_CHARS`·`MAX_UPLOAD_BYTES`·`MAX_TITLE_LENGTH` · `charCountOf` · `takeCodePoints` | `app/services/documents.py` 모듈 상수, `app/ingest/extractors.py::MAX_UPLOAD_BYTES` |
| `ConversionStatus.kt` | 상태 enum + `wireName` + `ofWireName`(모르는 값은 5xx) | `app/models/conversion.py::ConversionStatus` |
| `Document.kt` | `Document` · `DocumentListing` (둘 다 손으로 쓴 `toString`) | `app/models/document.py`, `app/repositories/documents.py::DocumentPage` |
| `Conversion.kt` | `Conversion`(6필드) · `MaskedItemView` | `app/models/conversion.py`, `app/services/documents.py::MaskedItemView` |
| `TitleRules.kt` | `resolveTitle` — 첫 줄 유도 · 어절 경계 · 말줄임 · 상한 자르기 · 대체 제목 | `_resolve_title`·`_shorten_derived_title` |

`SourceFormat.kt` 에 `ofWireName` 을 더했다(C1 이 만든 파일의 additive 확장).

### A.2 `application/document`

| 파일 | 내용 | 원본(참고) |
|---|---|---|
| `DocumentPorts.kt` | `DocumentDraft` · `DocumentRepository` · `ConversionCiphertexts` · `ConversionEnvelope` · `ConversionRepository` · `ConversionQueue` · `DocumentStorage` · `WorkspaceLookup` | `app/services/documents.py` 의 세 `Protocol`, `app/repositories/*` |
| `DocumentMessages.kt` | 사용자 문구 6종(계약 예시 자리를 각 상수에 적었다) | 같은 파일의 예외 문구 |
| `DocumentService.kt` | 업로드(붙여넣기·파일)·목록 · `AcceptedUpload` | `DocumentService.create_from_text`·`create_from_file`·`list_documents` |
| `EnvelopeRotation.kt` | 행 단위 재암호화 유스케이스 · `RotationOutcome` | 대응 없음 — Python 은 회전을 범위 밖으로 뒀다 |

### A.3 `infrastructure/document` · `infrastructure/queue`

| 파일 | 내용 |
|---|---|
| `JdbcDocumentRepository.kt` | INSERT(봉투 2값 명시) · `LEFT JOIN LATERAL` 목록 · 원문 읽기 · 단일 UPDATE 회전 |
| `JdbcConversionRepository.kt` | 대기 변환 INSERT(봉투 2값 명시) · 봉투 읽기 · **세 열 단일 UPDATE** 회전 |
| `JdbcWorkspaceLookup.kt` | 소유 조건을 WHERE 에 합친 작업 공간 읽기 둘 |
| `MaskedItemCodec.kt` | 마스킹 대응표 **저장 형식**(평문 JSON → 통째 AEAD) 인코더·디코더 |
| `DocumentStorageLog.kt` | 저장소가 남기는 **유일한** 로그 — SQLSTATE·형식 오류 사유 토큰만 |
| `DocumentConfiguration.kt` | 조립(`@Profile("!migrate")`) |
| `JdbcConversionQueue.kt` | `conversion_jobs` 등록(멱등 · 같은 트랜잭션) |
| `V5__conversion_jobs.sql` | lease 기반 큐 테이블 + 제약 4 + 부분 인덱스 2 |

## B. 원장 마감 항목의 처분

| 항목 | 처분 |
|---|---|
| **X9/F-6** 조립된 빈을 **실제 키**로 쓰는 통합 테스트 | **닫았다** — `DocumentStorageContextTest`. 실행 시점 난수 키 + `KeyCheckValue.of()` 로 계산한 KCV 를 넣은 컨텍스트가 자기점검을 **통과해** 뜨고, 그 `DocumentService` 빈이 실제 PostgreSQL 에 INSERT 하고 `ContentCipher` 빈이 그 행을 다시 연다. **2세대 키 회전 왕복**도 조립된 빈으로 돈다(쓰기 세대 1 컨텍스트가 쓴 행을 쓰기 세대 2 컨텍스트가 회전) |
| **자기점검이 정말 돌았는가** | 같은 파일에서 **검사값이 틀린 키를 주면 컨텍스트가 뜨지 않음**을 단언한다 — 이것이 없으면 위 초록이 「자기점검이 꺼진 채 통과」와 구분되지 않는다 |
| **X5/F-5** 재암호화 4조건 | **닫았다** — 포트 형태로 구조 강제 + `EnvelopeRotationTest`(유스케이스 축 11건) + `JdbcDocumentStoreTest`(문장 수·NULL 보존·낙관적 조건, 실제 DB) |
| **K-2** `CountingDataSource` 의 `JdbcClient` 전제 | **닫았다** — `StatementCountingPremiseTest` 가 `application` 포트를 구현한 `infrastructure` 구상 클래스를 **종류로** 훑어, 누구도 raw JDBC 손잡이를 들지 않음을 확인한다. 분모가 비면 빨개진다(0건 통과 방지). `CountingDataSource` KDoc 이 그 파일을 가리킨다 |
| **X2** `PlainBody` 웹 직렬화 fail-closed | **C3** (응답 DTO 가 생기는 커밋) — 지시대로 손대지 않았다 |
| **표 18** TRACE 로거 3종 | **C3 이전** — 계획 §9.2 D-f, 리더 확인 대기 |
| **타이밍 X3 의 codex A-6 처방** | **미배정** — 이 커밋 범위 밖 |

## C. 계획에서 벗어난 지점 (전건, 계획 §9.2-bis 와 같은 내용)

**D-g** D-1 의 L1(서비스 층 바이트 상한)만 C2 로 · **D-h** C-P 503 을 두 케이스로 분해(조립 경로에서 도달 불가) ·
**D-i** `Conversion` 6필드로 축소 · **D-j** `Document` 에서 `workspaceId` 제거 · **D-k** `DocumentStorage` 묶음 신설 ·
**D-l** `JdbcWorkspaceLookup` 별도 클래스 · **D-m** 큐 등록을 같은 트랜잭션(리더 지시, §9 질문 ② 잠정 전제 폐기) ·
**D-n** 문자 수·자르기를 코드 포인트 단위로.

사유는 계획 문서 §9.2-bis 표에 실측과 함께 있다. **여기 옮겨 적지 않는다** — 두 벌이 되면 갈린다.

## D. 원본과 **의도적으로 다르게** 구현한 지점 (C2 몫)

| # | 자리 | Python | Kotlin | 왜 |
|---|---|---|---|---|
| **B-1** | 저장 → 커밋 → 큐 등록 | `INSERT → commit → enqueue`. 등록 실패 시 `failure_code = "EnqueueFailed"` + 502 | **세 행이 한 트랜잭션** | 큐가 같은 PostgreSQL 이라 「커밋 전에 넣으면 워커가 없는 행을 읽는다」는 이유가 사라졌다. 계획 §4.4 가 정한 구조이고 리더 지시다. **계약 502 조항의 처분은 계약 레인 몫** |
| **B-2** | `key_version` | 컬럼 DEFAULT 로만 찍고 이후 UPDATE 가 갱신하지 않는다 — 회전이 그 사이에 일어나면 컬럼과 실제 키가 갈린다(실측: `app/repositories/**` 에 `key_version` 이 한 번도 안 나온다) | `EncryptedContent` 가 세 값을 묶고 **UPDATE 가 봉투를 함께 쓴다** | 포팅이 아니라 **다른 설계**다. `V3` 가 DEFAULT 를 없앤 것과 짝을 이룬다 |
| **B-3** | 마스킹 대응표 범주 | 저장 JSON 에 `MaskCategory.value`(한국어)를 그대로 적는다 | **안정된 저장 키**(`rrn`·`card`) ↔ 응답은 계약 enum 값 | 한국어 값이 **그대로 화면 문구**다(React 가 `<td>{item.category}</td>`). 저장 형식으로 쓰면 문구를 다듬는 날 옛 행이 안 읽힌다. 매핑은 `MaskedItemCodec.CATEGORY_KEYS` 한 곳 |
| **B-4** | 문자 수 | `len()` = 코드 포인트 | `charCountOf` = 코드 포인트(`String.length` 가 아니다) | 값은 Python 과 같고 **Kotlin 기본과 다르다**. 코드 단위로 세면 이모지 문서가 두 배로 환산되고, 자를 때 서로게이트 쌍이 쪼개진다 |
| **B-5** | 저장소 예외 로그 | 없음(예외를 그대로 올린다) | **SQLSTATE 다섯 글자만** 남긴다 | PostgreSQL 이 제약 위반 `DETAIL` 에 실패한 행 전체(암호문·제목)를 담는다. SQLSTATE 로 **갈래를 나누지도 않는다**(계획 §9.1) |
| **B-6** | 회전 시 `updated_at` | 대응 없음 | **건드리지 않는다** | 재암호화는 내용의 변경이 아니다. 회전 배치가 전 행의 `updated_at` 을 오늘로 밀면 그 컬럼의 뜻이 사라진다 |

## E. 이 커밋이 **열지 못한** 도달 / 미포팅 잔여

- **X1(짝 없는 서로게이트)의 도달은 여전히 0 이다.** 저장 경로가 `PlainBody` 를 지나게 됐지만
  (`DocumentService.store`), 그 경로에 **고아 서로게이트를 넣을 입력이 없다** — 붙여넣기 JSON 은
  C3 이고, C2 의 테스트는 전부 정상 문자열이다. 제목 쪽은 `takeCodePoints` 가 **쪼개지 않음**을
  단언하지만 그것은 「우리가 만들지 않는다」이지 「들어온 것을 거부한다」가 아니다.
  **계획 §6.4 가 요구한 「제목에도 같은 정의역 판정」은 아직 하지 않았다** — 사용자가 준 제목에
  고아 서로게이트가 있으면 그대로 JDBC 로 간다. C3(입력 표면)이 그 판정을 붙여야 한다.
- **Q-12(Jackson 3 의 짝 없는 서로게이트 이스케이프) 여전히 미확인.** 계획대로 C3 착수 전 실측이다.
- **`Conversion` 의 결과 필드 미포팅**(§C D-i) — `missing_placeholders`·`provider_name`·`model`·
  토큰 수·`reviewed_at`. C6 이 더한다. `DocumentListing.reviewedAt` 은 목록 질의가 직접 읽으므로
  영향이 없다.
- **`conversion_jobs` 소비 경로 0** — `FOR UPDATE SKIP LOCKED` 획득·lease 회수·backoff·재시도
  상한은 Phase 5 다. V5 는 **자리와 제약**만 만든다.
- **`EnvelopeRotation` 의 호출자 0** — 운영 CLI·스케줄 중 무엇인지가 계획 §9 질문 ⑦ 의 열린 판정이다.
  빈으로는 조립되므로 「조립조차 안 되는 코드」는 아니다.
- **`MaskedItemCodec` 의 쓰기 호출자 0** — 쓰는 쪽은 Phase 5 워커다. 그래서 **양방향**을 지금 못박았다.

## F. 검사 결과 (미실행은 「미실행」으로 적는다)

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 품질·빌드·테스트 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **통과 (exit 0)** · warning 0(`allWarningsAsErrors`) |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | **통과 (exit 0)** — api·worker 양쪽 |
| parity | `./gradlew parityHarness` | **통과 (exit 0)** — 선언 8개 전부 산출물 확인. C2 는 parity 도메인을 건드리지 않았다 |
| 개인정보 스캐너 | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **통과 (exit 0, BLOCK 0)** |
| Python 게이트 | `uv run ruff check .` | **통과 (exit 0)** |
| | `uv run mypy . .claude` | **통과 (exit 0)** — 139 files |
| | `uv run pytest` | **통과 (exit 0)** — 1408 passed, 68 skipped, 5 deselected, 5 xfailed |
| Kotlin 게이트 도달 | `uv run pytest tests/test_kotlin_gate_reach.py tests/test_raw_control_chars.py` | **통과 (exit 0)** — 101 passed |
| 골든셋 | — | **해당 없음** — 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았다 |
| 계약 음성 대조 N-23~N-28 | — | **미실행.** 전부 C3 이후 몫이다(계획 §8.5) |
| compose 스모크 | — | **미실행** (이 배치). 선결 P4 는 리더가 이번 회차에 닫았다 — `04_leader_compose-smoke.md` |

**게이트 명령에 파이프를 쓰지 않았다** — 출력은 파일로 받고 종료 코드를 별도로 읽었다.

**전체 Kotlin 테스트**: 115 클래스 / 910 케이스(C1 시점 대비 **+7 클래스 / +79 케이스**).
C2 가 더한 것 — `TitleRulesTest`(15) · `DocumentServiceTest`(18) · `EnvelopeRotationTest`(11) ·
`MaskedItemCodecTest`(9) · `JdbcDocumentStoreTest`(20) · `DocumentStorageContextTest`(4) ·
`StatementCountingPremiseTest`(2). `tests/test_kotlin_gate_reach.py` 의 핀을 78 → **85** 로 갱신했다.

### F.1 구현 중 **실측으로 뒤집힌 것 셋** (다음 사람이 같은 자리를 밟지 않도록)

1. **한 구상 클래스가 두 포트를 겸하면 Spring 주입이 모호해진다.** `JdbcWorkspaceRepository` 가
   `WorkspaceLookup` 을 함께 구현하게 하자 `api`·`worker` 기동 테스트가 전건 빨개졌다
   (`NoUniqueBeanDefinitionException: found 2: workspaceRepository, workspaceLookup`).
   → 포트 하나당 구상 클래스 하나(`JdbcWorkspaceLookup`).
2. **저장소와 트랜잭션 관리자가 같은 `DataSource` 인스턴스를 봐야 한다.** 다른 것을 주면 저장소가
   autocommit 커넥션을 잡아 **롤백이 아무것도 되돌리지 않는다** — 「등록 실패는 저장을 되돌린다」가
   처음에 초록이 아니라 **거짓 빨강**으로 드러났다(문서가 남아 있었다). 테스트 헬퍼가 인자를
   `DataSource` 하나로 좁혀 그 갈림을 구조적으로 막는다.
3. **Jackson 3 의 `JsonNode.map(Function)` 이 Kotlin `Iterable.map` 을 가린다.** 그대로 쓰면
   반환 타입이 조용히 갈린다(컴파일 오류로 드러났다). `values()` 로 원소 컬렉션을 먼저 꺼낸다.

## G. `parity-verifier` 에게 (모듈 완료 통보)

**완료 모듈**: `core/document`(제목 규칙·상한·상태) · `application/document`(업로드·목록·회전 유스케이스) ·
`infrastructure/document`·`infrastructure/queue`(JDBC 저장·큐 등록·대응표 코덱).

**대응 Python 원본**: `app/services/documents.py`(업로드·목록·제목) · `app/repositories/documents.py`·
`conversions.py` · `app/queue.py`. **포팅하지 않은 것**: `save_review`·`get_conversion`·`export_conversion`
(각각 C7·C6·`export` 단위) · `deserialize_masked_items` 의 응답 매핑(C6).

**요청**: `parity/fixtures/` 에 문서 저장 도메인을 신설할 필요는 없다고 본다 — 이 단위가 재는 것은
값이 아니라 **성질**(원자성·봉투·소유 범위·회전)이고 그것은 실제 DB 없이 재현되지 않는다.
계획 §9 질문 ⑨(`ingest` 도메인)와 함께 판단이 필요하면 알려 달라.

## H. 다음(C3)이 이어받을 것

- 컨트롤러·DTO · multipart 설정(L0) · **D-2 상한 초과 예외 매핑** · **D-3 초과분 삼키기**
- **X2** `PlainBody`·`MaskedText`·`ModelDraft`·`ReviewedBody`·`EncryptedContent` 가 응답 DTO 주 생성자에
  **없음**을 단언(계획 §4.5) — DTO 가 생기는 그 커밋이다
- **§6.4 제목의 정의역 판정**(위 §E) — 사용자가 준 제목에 고아 서로게이트가 있는 경로
- **Q-12 실측** 후 X1 첫 도달 배치
- **표 18 TRACE 카나리**(계획 §9.2 D-f, 리더 확인 뒤)
- `AuthenticatedEndpoints` 에 새 경로 등재 · **P-23~P-37 파서 노드** · **DC-1~DC-23**
- 계약 레인 **L-1 잔여 두 갈래**(502 매핑·DC-19)와 **L-2 tie-break** 를 같은 변경 단위로

---

# C3 — `POST /documents`: 두 입력 갈래와 접수 (2026-08-20)

**기준 HEAD:** `8e94847` (앞 배치의 K-14 커밋). **이 배치의 커밋은 하나다.**
**지시 정본:** 계획 §7.2 C3 행 · `04_contract-keeper_l1-residual-verdict.md` §4 의
**K-5·K-6·K-8·K-9·K-10·K-11·K-12·K-13·K-15** 아홉. (K-1~K-4·K-7 은 `cd127ea`, K-14 는 `8e94847` 에서 끝났다.)

## I. 한눈에

| | |
|---|---|
| **만든 것** | `api/document/**`(컨트롤러·DTO) · multipart 설정(L0) · D-2 상한 초과 매핑 · D-3 초과분 삼키기 · `AuthenticatedEndpoints` 등재 · **DC-1~DC-25**(DC-2·DC-3 포함) · **P-24·P-27·P-33·P-34·P-36·P-38·P-39** · **K-15 `ParserNodeRegistryTest` 신설** · **X2** · **표 18 TRACE 카나리** |
| **검사** | Kotlin `ktlintCheck detekt build moduleBoundaryCheck --rerun-tasks` **통과(경고 0)** · `parityHarness` 통과 · 개인정보 스캐너 **BLOCK 0** · Python `ruff`/`mypy . .claude`/`pytest` **통과** |
| **음성 대조** | **11건 실행, 전건 실측**(§VI). 복원은 전부 **바이트 백업 + `Path.write_bytes` + sha256 대조** — `cp` 도 `git checkout --` 도 쓰지 않았다 |
| **미실행/미측정** | §VII |

## II. 지시별 처분 (K-5·K-6·K-8~K-13·K-15)

| # | 무엇을 했나 | 자리 |
|---|---|---|
| **K-5** | `ConversionQueue` KDoc 의 *"계약 조항의 처분은 계약 레인의 판정 사항이다"* 를 **판정 결과를 가리키게** 고쳤다 — 502 폐기(`x-retired-responses`)와 그 대체(500 + 전량 롤백), 그리고 그것을 재는 장치 이름까지 적는다 | `application/document/DocumentPorts.kt` |
| **K-6** | `PROTECTED_PATH_PATTERNS` 에 **`/documents` 만** 더했다. 판단 근거는 §III-1 | `api/auth/AuthenticatedEndpoints.kt` |
| **K-8** | **P-39 전역 단언** — `x-retired-responses[].status` 전건이 `paths` 의 어느 `responses` 키에도 없다 + **목록이 비어 있지 않다** + **분모(`paths` 응답 선언)가 비어 있지 않다** + 항목이 실제 상태 코드 모양인지 | `DocumentContractNodeTest` |
| **K-9** | **P-38 배선** — `x-stored-text-domain` 의 `detail`·`detail_shape`·`status`·`applies_to`(측정 상태 표식 포함). `detail` 이 `POST /documents` 422 예시 `undecodable_text` 와 같은지, 그리고 구현 상수 `PlainBody.UNPAIRED_SURROGATE_MESSAGE` 와 같은지 **세 자리 대조** | 같음 + `ContractSpec.storedTextDomain()` |
| **K-10** | **DC-24** — JSON `\uD800` 이스케이프 본문 → 422 · `detail` **문자열**(모양도 계약에서 읽는다) · 값이 계약과 같음 · **저장되지 않음**. 「저장되지 않음」은 목록 API 가 아직 없어 **`documents` 행 수**로 잰다(§III-3) | `DocumentEndpointReachTest` |
| **K-11** | **DC-25** — 서로게이트가 든 제목 + 정상 본문 → **접수됨**(422 아님) · 저장된 제목에 그 문자 없음 · 정제 후 남는 것이 없으면 계약 `x-title-policy.fallback_title` | 같음 |
| **K-12** | **DC-18** — 큐 등록 실패 → **500**(502·503 아님) · 문자열 `detail`(값도 계약에서) · **문서·변환 모두 0건**. 빈 교체 대신 **작업 테이블을 지워 실제 어댑터가 실제 오류를 내게** 했다(§III-2) | `DocumentEnqueueFailureReachTest`(전용 DB) |
| **K-13** | **DC-19** — `AuthUnavailableContractTest`(짧은 서명 키)에 `POST /documents` 를 한 경로 더했다. 503 · 문자열 `detail` · 계약이 그 경로에 503 을 선언했는지 · **401 이 아님** | `AuthUnavailableContractTest` |
| **K-15** | **`ParserNodeRegistryTest` 신설.** 실제로 세어 확인한 값이 리더가 준 숫자와 **같다** — 정의 행 **39**(auth 15 · workspaces 6 · documents 18) · `ContractSpec.kt` 전용 등재 **1**(P-22) · 합집합 **40** · `P-1`~`P-40` 연속 | `ParserNodeRegistryTest` |

## III. 계획·지시에서 갈라진 판단 (D-v ~ D-z)

| # | 계획·지시가 적은 것 | 실제로 한 것 | 사유 |
|---|---|---|---|
| **D-v** | K-6 — 「문서·변환 경로를 더한다」 | **`/documents` 만 더했다.** `/conversions/{conversion_id}` 는 그 엔드포인트를 만드는 커밋(C6·C7)이 더한다 | 리더 판정 4 를 그대로 집행했다. 대조 테스트는 목록이 계약 보호 경로의 **부분집합**이면 통과하고, 서비스 중인 보호 경로가 전부 목록에 있는지는 **매핑 표에서 발견**해 본다. 즉 미구현 경로를 미리 넣어도 **아무것도 강제하지 않으면서** 「목록에 있으니 인증이 걸렸다」는 잘못된 신호만 남긴다. `AuthenticatedEndpoints` KDoc 자신의 규약(*엔드포인트를 만드는 그 커밋에서 자기 경로를 더한다*)과도 그쪽이 맞다 |
| **D-w** | K-12 — 「`ConversionQueue` 빈을 갈아 끼우고 `DataAccessException` 하위를 던진다」 | **전용 DB 에서 `conversion_jobs` 테이블을 지웠다.** 실제 어댑터가 실제 SQL 을 던지고 PostgreSQL 이 실제 오류를 낸다 | 흉내 낸 빈은 「우리가 만든 예외가 어떻게 매핑되는가」를 재고, 이 방식은 **어댑터·트랜잭션·매핑을 한 줄로 꿴 실제 경로**를 잰다. 대가(전용 DB 를 망가뜨린다)는 클래스 KDoc 에 적었다 |
| **D-x** | K-10 — 「후속 목록에서 그 문서 0건」 | **`documents` 행 수 0** 으로 잰다 | `GET /documents` 가 아직 없다(다음 커밋). DB 직접 확인은 목록 API 보다 **좁은 축**이다 — 목록 구현의 필터링이 끼어들지 않는다. 목록이 생기면 그쪽으로도 잰다 |
| **D-y** | 계획 §5.1 — fixture 를 Kotlin test resources 로 **복사**한다 | **복사하지 않고 `infrastructure/src/test/resources` → `src/testFixtures/resources` 로 옮겼다** | `api` 가 이미 `testFixtures(project(":infrastructure"))` 를 당기므로 **두 모듈이 같은 파일을 본다**. 복사하면 두 벌이 되고, 파서 테스트와 계약 테스트가 서로 다른 입력을 재는 날이 온다. 옮긴 뒤 `infrastructure` 의 추출기 테스트 전건이 그대로 통과하는 것을 확인했다 |
| **D-z** | (없음) | `DocumentController` 에 **`@Profile("!migrate")`** 를 달았다 | 면제가 아니라 의존성이다 — `DocumentConfiguration` 이 같은 조건으로 빠져 있어(게이트 26 조치 2) 그 프로필에서 컨트롤러가 남으면 **기동이 "DocumentService 빈이 없다"로 실패한다.** 실측으로 밟았다: `MigrateProfileWithoutEncryptionKeyTest` 2건이 그 자리에서 빨개졌다 |

### III-1. `GlobalExceptionHandler` 를 구조로 줄였다 (임계값을 올리지 않았다)

D-2(413 매핑)를 더하자 detekt `TooManyFunctions`(기본 임계값 11)가 울렸다. **임계값을 올리지 않았다** —
그 설정은 이 저장소에서 한 번도 손댄 적이 없어 신호가 진짜였다. 우리가 더한 두 `@ExceptionHandler`
(`EasyDocException` · `Exception`)를 **하나로 합쳤다**: 프레임워크 예외 20종은 상위 클래스가 명시
등록해 어느 쪽이든 그쪽이 이기므로, 남는 판정은 「도메인 예외인가」 한 갈래뿐이다.

**로그는 두 줄 그대로 둔다.** 합치면서 한 줄로 만들었더니 `tests/test_privacy_scanner.py` 의
「전역 예외 핸들러의 로그가 검사 대상이다」(로그 호출 ≥ 2)가 빨개졌다 — 그 핀이 재는 것은 스캐너의
**도달**이고, 동시에 「매핑 누락」과 「예상 못 한 예외」는 운영에서 **다른 사건**이다. 갈래별 로그를
되살려 둘 다 지켰다.

### III-2. multipart 설정과 그 값의 강제자

`spring.servlet.multipart.max-file-size: 11MB` · `max-request-size: 12MB` ·
`strict-servlet-compliance: true` · `resolve-lazily: false` · `server.tomcat.max-swallow-size: 16MB`.

- **두 상한이 계약 상한 이상인지를 손으로 지키지 않는다** — `DocumentEndpointReachTest` 가
  `MultipartProperties` 빈을 주입받아 계약 `x-input-limits.max_upload_bytes` 와 대조한다.
- **`strict-servlet-compliance` 는 DC-5 에 영향이 없다.** 바이트코드로 확인했다:
  `StandardServletMultipartResolver.isMultipart` 가 `startsWithIgnoreCase` 로 비교하고, 이 플래그는
  비교 **대상 문자열**만 `multipart/` → `multipart/form-data` 로 좁힌다.
- **`max-swallow-size: -1`(무제한)을 쓰지 않는다.** 무제한은 거절한 요청의 나머지를 끝까지 읽어 주는
  것이라 거절이 곧 방어가 되지 못한다. 유한하되 `max-request-size` 보다 큰 값이면 정상 초과 요청은
  본문을 받아 보고 악의적 무한 본문은 리셋된다.

### III-3. 표 18 TRACE 카나리 — **탐지형**으로 세웠다

`DocumentBodyLogLeakReachTest`. 로거 이름을 **열거하지 않는다**(원장이 지목한 3종을 `application.yml`
에 못박는 처방은 열거이자 은폐형이고, `CLAUDE.md` 규칙 4 ⑵ 가 그 방향을 금한다).

- **제품 기본 로그 구성 그대로** 앱을 띄우고 `POST /documents` 를 다섯 번 태운다 —
  성공(붙여넣기) · 파일 모드 · 상한 초과 · 손상 파일 · 저장 불가 문자. **오류 경로를 함께 태우는 것이
  요점이다**(유출은 예외 메시지·스택트레이스에서 난다).
- 캡처는 ROOT 로거의 `ListAppender` 이고, 메시지뿐 아니라 **예외 체인과 스택 프레임까지** 훑는다.
- 카나리는 **본문·제목·자격증명(비밀번호·액세스 토큰)** 네 값이다. 축을 나누지 않으면 실패 메시지에서
  「무엇이 샜는가」가 사라진다.
- **양성 대조** — 요청 전에 표식을 직접 찍고 그것이 캡처에 있는지 먼저 본다.
- **음성 대조 실측**: `application.yml` 의 로거 한 줄을 `org.apache.coyote: TRACE` 로 바꾸자
  이 케이스가 **빨개졌다**(§VI). 원장 기록 ③ 이 지목한 세 로거 중 하나이고, 곧 「누가 레벨을 내리면
  그 커밋에서 빨개진다」가 실행으로 성립한다.

## IV. 실측으로 확인한 사실 셋 — 문서·계약과 갈리는 자리

### IV-1. **PDF 는 짝 없는 서로게이트를 내지 않는다** (계약 `applies_to` 의 파일 팔이 미도달)

계약 `x-stored-text-domain.applies_to` 는 파일 모드를 *"PDF 가 가장 그럴듯한 유입 경로다 — 깨진
`ToUnicode` CMap 이 홀로 있는 상위 서로게이트를 그대로 내놓을 수 있다"* 로 적고 그 팔을
**`status: measured`** 로 두었다.

**확인하려고 fixture 를 만들었고(`SurrogatePdf`, `bfchar` 목적값 `D8 00`), 실측은 반대였다** —
PDFBox 3.0.5 는 **U+FFFD 로 치환한다**(추출 결과 코드 포인트를 그대로 찍어 확인: `["U+FFFD"]`).
즉 **오늘 조합에서 저장 정의역 위반이 도달하는 경로는 붙여넣기(JSON 이스케이프) 하나뿐**이다.

- fixture 와 회귀는 지우지 않고 **「라이브러리가 치환한다」는 사실을 붙드는 쪽으로** 돌렸다
  (`PdfExtractorTest` — 판올림이 치환을 그만두면 빨개지고, 그때 파일 팔이 실제로 열린다).
- **계약 레인에 올린다**: `applies_to` 의 파일 팔 `status` 가 `measured` 인 것은 오늘 사실과 다르다.
  docx·hwpx 는 well-formed UTF-8 XML 이라 **인코딩 자체로 불가능**하고 PDF 는 위와 같다.

### IV-2. **`SUPPORT_DTD = false` 는 「DOCTYPE 을 만나면 끊는다」가 아니다**

내부 서브셋이 **없는** DOCTYPE(외부 DTD 참조만)은 JDK StAX 가 **조용히 무시하고 문서를 그대로
파싱한다**. 거절되는 것은 내부 서브셋에 엔터티가 **선언·참조**된 경우다(billion laughs·XXE).

- **보안 성질은 그대로다** — `ACCESS_EXTERNAL_DTD=""`·`IS_SUPPORTING_EXTERNAL_ENTITIES=false` 라
  외부 DTD 를 가져오지 않으므로 펼칠 엔터티가 없다. I-10 검증 2 가 요구하는 「파서 수준에서 엔터티
  확장이 시작되지 않는다」는 만족된다.
- **그러나 계약 케이스의 입력 모양이 갈린다** — 처음에 DC-15 의 「외부 엔터티 선언」 갈래를 그 모양으로
  썼더니 **202 가 나왔다**(아무것도 재지 못했다). 실제 공격 모양(내부 서브셋 + 참조)으로 바꿨다.
- 사실 자체는 `HwpxExtractorTest` 의 「내부 서브셋 없는 DOCTYPE 은 그대로 파싱된다」가 회귀로 붙든다.
  **원본(Python `expat.StartDoctypeDeclHandler`)은 DOCTYPE 자체를 거절했으므로 여기서 동작이 갈린다** —
  기준은 요구사항이고 요구는 만족되므로 갈림으로 기록한다.

### IV-3. **DC-11 의 기대값이 계약에서 오지 않고 있었다** (음성 대조가 잡았다)

첫 판의 DC-11 은 「422」를 못박아 두어, 계약 `fields[?text].measured_on` 을 정규화 후로 바꿔도
**깨지지 않았다**(N-25 실측). 기대 자체를 `ContractSpec.requestFieldConstraint(...).measuresRaw` 에서
읽어 축이 뒤집히면 기대도 뒤집히게 고쳤다. 고친 뒤 N-25 에서 DC-11 이 빨개진다.

## V. 계약 레인·리더에게 올리는 것 (계약 파일은 **한 줄도 고치지 않았다**)

1. **`x-stored-text-domain.applies_to` 의 파일 모드 팔 `status: measured`** — 오늘 도달하지 않는다(§IV-1).
   `pending` 이 맞는지, 아니면 「도달 불가」를 뜻하는 표식이 필요한지는 계약 레인 판정이다.
2. **`POST /documents` 에 415 선언이 없다.** 두 `consumes` 매핑 밖의 `Content-Type`(예: `text/plain`)은
   Spring 이 415 로 끊는데 계약이 그 상태를 선언하지 않는다. **`POST`·`PATCH /workspaces` 도 같은
   모양이라 이 커밋이 만든 빈자리가 아니다** — 그래서 고치지 않고 올린다.
3. **DC-15 의 「XML 외부 엔터티 선언」 갈래**는 내부 서브셋이 있어야 실제로 거절된다(§IV-2).
   계약 산문이 그 구분을 적을 필요가 있는지 판정을 요청한다.

## VI. 음성 대조 — 11건 전건 실측 (2026-08-20)

복원은 전부 **바이트 백업 → `Path.write_bytes` → sha256 대조**다. `cp` 도 `git checkout --` 도 쓰지
않았다(후자는 이 트리에 미커밋 작업이 있어 게이트 27 회차의 사고를 되풀이할 수 있다).
**전건 복원 sha256 일치**를 확인했고, 실행 후 `contracts/**` 는 `git status` 에서 무변경이다.

| # | 변조 | **빨개진 것** | 판정 |
|---|---|---|---|
| **N-23** | `x-input-limits.max_upload_bytes` 값 변경 | `P-24 업로드 상한이 계약에서 온다` · `DC-12` | ✅ 상한이 코드에 복제돼 있지 않다. **DC-13 은 이 방향(상한을 낮춤)에서는 깨지지 않는다** — 그 방향은 P-24 가 먼저 잡는다 |
| **N-25** | `fields[?text].measured_on` → 정규화 후 | `P-34`(2건) · **`DC-11`** | ✅ 고친 뒤(§IV-3). **CU-6 은 아직 없다**(C7) |
| **N-28** | `/conversions/{conversion_id}` 경로 템플릿 변경 | **`DC-2` 만** | ✅ 과잉 결합 0 |
| **N-31** | `x-stored-text-domain.detail` 문구 변경 | `P-38`(2건) · `DC-24` | ✅ 문구가 코드에서 오지 않는다 |
| **N-32** | `detail_shape` → `array` | `P-38`(모양 축) · `DC-24` | ✅ **상태 코드 단언은 살아 있다** — 모양과 코드가 한 값으로 묶여 있지 않다 |
| **N-33** | `x-retired-responses` 에 아직 쓰는 코드(503) 추가 | **`P-39` 만** | ✅ 폐기가 문장이 아니라 실행이다 |
| **R-3** | K-14 되돌리기(제목 정제에서 서로게이트 제거를 뺀다) | **`DC-25` 2건만. `DC-24` 초록 유지** | ✅ **본문 거절과 제목 정제가 실제로 분리돼 있다** |
| **R-5** | K-6 되돌리기(`PROTECTED_PATH_PATTERNS` 에서 `/documents` 제거) | `DC-19`·`DC-20`·`DC-21` + `AuthenticationCoverageContractTest` + 업로드 케이스 대부분 | ✅ 지시가 예고한 셋을 포함해 훨씬 넓게 빨개진다(인증 인터셉터가 없으면 `AuthenticatedUser` 해석이 끊긴다) |
| **표 18** | `application.yml` 의 로거 한 줄을 `org.apache.coyote: TRACE` 로 | **카나리 케이스** | ✅ 「레벨을 내리면 빨개진다」가 실행으로 성립 |
| **N-R2** | `ContractSpec.kt` 에 미등재 라벨(`**P-99`) 주입 | 규칙 2·3·4 | ✅ **P-22 가 태어난 자리를 정확히 막는다** |
| **N-R4** | documents 명세에 정의 행 하나 추가 | **규칙 4 만** | ✅ 총수 핀이 diff 를 강제한다 |

## VII. 미실행·미측정 — 「미실행」으로 적는다

1. **`x-stored-text-domain` 의 `edited_text` 팔(`status: pending`)** — `PUT /conversions/{id}` 가 없다(C7).
   `P-38` 이 그 팔을 **목록으로 출력**해 마감이 남았다는 사실이 테스트에서 사라지지 않게 했다.
2. **DC-24 의 파일 모드 팔** — 오늘 도달하지 않는다(§IV-1). 재지 못한 것이 아니라 **무대가 없다**.
3. **DC-13 의 반대 방향 음성 대조**(계약 상한을 **올려** DC-13 을 깨뜨리는 변이) — 실행하지 않았다.
   P-24 가 그 방향을 먼저 잡는 것을 N-23 에서 확인했으므로 중복이라고 판단했으나, **재지 않았다는
   사실은 사실이다.**
4. **N-26·N-27**(마스킹 범주 enum·`ConversionResponse.required`) — C6·C7 몫이다.
5. **R-1·R-2·R-4** — R-1(계약에 502 를 되살린다)은 `x-retired-responses` 를 건드리지 않고 `paths` 를
   되살려야 해서 N-33 과 겹치는 축이라 대체했다. R-2·R-4 는 실행하지 않았다.
6. **compose 기동 스모크**(계획 P4) — 이 배치에서 돌리지 않았다.
7. **`GET /documents`·`DELETE /documents/{id}`·`GET·PUT /conversions/{id}`** — C4~C7.

## VIII. 검사 표 (전부 실행했다)

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin | `./gradlew ktlintCheck detekt build moduleBoundaryCheck --continue --rerun-tasks` | **BUILD SUCCESSFUL** (경고 0 — `allWarningsAsErrors`) |
| parity | `./gradlew parityHarness` | BUILD SUCCESSFUL |
| 개인정보 스캐너 | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | exit 0 (**BLOCK 0**) |
| Python 린트 | `uv run ruff check .` | All checks passed |
| Python 타입 | `uv run mypy . .claude` | Success: 139 source files |
| Python 테스트 | `uv run pytest` | 1434 passed, 68 skipped, 5 deselected, 5 xfailed |
| 골든셋 | — | **해당 없음** — 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았다 |

> **게이트가 한 번 잡아낸 것**: 개인정보 스캐너가 `OWNERSHIP-403` **BLOCK 1건**을 냈다. 내 실패
> 메시지 문자열에 든 `403` 리터럴이었다(부호 반전 단언 `isNotEqualTo(FORBIDDEN)` 의 안내문). 스캐너를
> 고치지 않고 **문면을 `WorkspaceEndpointReachTest` 와 같은 형태**(주석 + 단언)로 바꿔 해소했다 —
> 무시 패턴을 넓히는 것은 은폐형이다.

## IX. 다음(C4)이 이어받을 것

- `GET /documents` — `spring-boot-starter-validation` 도입은 **그 커밋**이다(명세 §6: DL-5 의 `detail`
  타입 단언이 의존성 도입과 같은 변경 단위여야 한다). 지금 미리 넣지 않았다.
- **DC-24 의 「저장되지 않음」을 목록 API 로도 재기**(§III D-x).
- `P-25`(오퍼레이션 수준 `limit`/`offset` 파라미터 접근자) · `P-35` · `N-24`.
- `AuthenticatedEndpoints` 는 그대로 — `/documents` 는 이미 있고 `/conversions/**` 는 C6·C7 이 더한다.

---

# C4 — `GET /documents` (목록과 페이지 파라미터)

**커밋:** `feat(kotlin): GET /documents — 목록과 페이지 파라미터` / **일자:** 2026-08-21 / **게이트 덩어리:** G-β
**기준 HEAD:** `81ba9fa` / **정본:** 계획 §7.2 C4 행 · §9.1 · 명세 DL-1~DL-11 · P-25 · N-24 · X-C2 · 계약 v1.4.0

## C4-I. 만든 것

| 파일 | 무엇 |
|---|---|
| `backend-kotlin/gradle/libs.versions.toml` | `spring-boot-starter-validation` 좌표(버전은 **BOM**). 주석에 「무엇을 켜는가 + F3 방벽 소멸」을 적었다 |
| `backend-kotlin/api/build.gradle.kts` | 위 좌표를 `implementation` 으로 |
| `backend-kotlin/api/gradle.lockfile` | 락 갱신 — **6줄 추가, 클래스패스 갈림 0** (아래 C4-II) |
| `api/.../document/ListPageLimits.kt` | **신규.** `limit`/`offset` 의 하한·상한·기본값 상수 + 「왜 값이 코드에 복제되는가」와 대조 셋 |
| `api/.../document/DocumentController.kt` | `GET /documents` 매핑 · `@Min`/`@Max` · 사적 헤더 개별 부착 · 클래스 KDoc 갱신(하한선이 **오퍼레이션마다 갈린다**) |
| `api/.../document/DocumentDtos.kt` | `DocumentListItemResponse`(제목 가린 `toString`) · `DocumentListResponse`(`of` 가 `limit+1` 을 잘라 `has_more` 판정) |
| `api/src/test/.../support/ContractSpec.kt` | **P-25** 접근자 `inputLimitRange`(min/max?/default) · `queryParameters(path, method)` + 두 값 타입 |
| `api/src/test/.../DocumentListContractTest.kt` | **신규 (C-M)** — DL-1 · DL-5 · DL-6 · DL-7 (9 케이스) |
| `api/src/test/.../DocumentListReachTest.kt` | **신규 (C-R·C-I)** — DL-2 · DL-3 · DL-4 · DL-8 · DL-9 · DL-10 · DL-11 (10 케이스) |
| `api/src/test/.../RequestFieldRejectionLayerTest.kt` | **신규 — F3 대체 강제자** (아래 C4-III) |
| `api/src/test/.../DocumentListHeaderFloorTest.kt` | **신규 — X-D1 하한선 탐지기** (아래 C4-VI) |
| `api/src/test/.../DocumentContractNodeTest.kt` | **P-25** 두 케이스(코드 상수 대조 + 계약 내부 이중 선언) · **P-33** 목록 두 스키마 |
| `api/src/test/.../DocumentEndpointReachTest.kt` | **DC-24 의 목록 축 마감** — C3 이 「목록이 생기면 그쪽으로도 잰다」로 남긴 것 |
| `api/src/test/.../SensitiveToStringReachTest.kt` | `EXPECTED_SOURCE_DECLARATIONS` 50 → **52**(사유 주석 포함) |
| `tests/test_kotlin_gate_reach.py` | `TEST_CLASSES` +4 · `TEST_CLASS_COUNT` 99 → **103** |

**계약 파일은 한 줄도 고치지 않았다.** N-24 음성 대조의 일시 변조만 있었고 복원 후 sha256 이 일치한다(C4-V).

## C4-II. 의존성 도입 — 무엇을 확인했나

**락파일 diff 6줄, 전부 BOM 관리 버전이고 compile/test 클래스패스가 갈리지 않았다.**

```
com.fasterxml:classmate:1.7.3                          = compile,production,runtime,testCompile,testRuntime
jakarta.validation:jakarta.validation-api:3.1.1        = (같음)
org.hibernate.validator:hibernate-validator:9.1.0.Final= (같음)
org.jboss.logging:jboss-logging:3.6.3.Final            = (같음)
org.springframework.boot:spring-boot-starter-validation:4.1.0 = (같음)
org.springframework.boot:spring-boot-validation:4.1.0  = (같음)
```

D-a(commons-compress/io)에서 겪은 **compileClasspath ↔ testClasspath 갈림이 여기서는 0** 이다. EL 구현체도 새로 들어오지 않았다 — `tomcat-embed-el:11.0.22` 가 `spring-boot-starter-web` 경유로 이미 있었다(실측: 락파일 104행).

**「무엇이 켜지는지」 확인 — 의도하지 않은 자리에 검증이 켜지지 않았다.** 공식 문서(Spring Framework reference, `web/webmvc/mvc-controller/ann-validation`)로 확인한 규칙은 둘이다.

1. **컨트롤러 메서드 파라미터에 제약 애너테이션이 있으면** 내장 메서드 검증이 돌고 `HandlerMethodValidationException` 이 난다. 그래서 `limit`/`offset` 에만 켜진다.
2. **요청 본문(`@RequestBody`)은 `@Valid`/`@Validated` 가 없으면 검증되지 않는다.** F3 다섯 필드가 사는 자리이고, 우리 컨트롤러 어디에도 `@Valid` 가 없다.

> **`@Validated` 를 클래스에 붙이지 않았다.** 붙이면 AOP 기반 메서드 검증이 대신 서고
> `ConstraintViolationException` 이 나가는데, 우리 매퍼가 계약 모양으로 옮기는 예외는
> `HandlerMethodValidationException` 이다. 이 갈림은 문서로 확인했고 실측(DL-5 초록)이 뒷받침한다.

`GlobalExceptionHandler.handleHandlerMethodValidationException` 은 **이미 있었다**(Phase 3에 오버라이드해 두고 「의존성이 붙어야 실제로 발생한다」고 적어 둔 것). 이 커밋이 그 경로에 처음 실제 요청을 흘렸고, `loc` 이 계약 `ValidationFailed.examples.query_range` 와 같은 모양(`["query","limit"]`)으로 나가는 것을 확인했다 — 파라미터 이름은 `kotlin-reflect` 경유로 해석된다(실측: DL-5 의 `loc` 단언 초록).

## C4-III. F3 대체 강제자 — 무엇을 골랐고 왜

### 고른 것

**`RequestFieldRejectionLayerTest` — 「그 다섯 필드의 거절이 어느 층에서 일어나는가」를 응답 `detail` 의 모양으로 재는 탐지기.** 케이스를 계약에서 유도한다: 필드 목록은 `x-request-field-constraints.fields[].field`, 경계는 그 필드의 `limit`, 기대 문구는 같은 항목의 `detail`.

프로브는 `limit` 하나에서 세 값을 만든다 — 길이 `limit`, `limit-1`, `limit+1`. 단언은 넷이다.

1. 길이 **정확히 `limit`** 은 통과한다(다섯 필드 전부 경계 포함이다 — 상한이면 「이하」, 하한이면 「이상」).
2. 이웃 두 값 중 **정확히 하나**가 거절된다. **방향을 코드에 적지 않는다** — `password` 만 하한이고 나머지 넷은 상한인데, 이 형태면 그 비대칭을 알 필요가 없다.
3. 그 거절은 422 이고 `detail` 이 **문자열**이며 값이 계약 선언 문구 중 하나다.
4. **어떤 프로브도 배열 `detail` 을 내지 않는다.**

더해서 ⑸ 이메일 형식 위반(`@Email`·`@Pattern` 이 옮겨 갈 축)도 문자열이어야 하고, ⑹ 도달 범위 대조 — 프로브 키 집합 == 「계약 필드 − DTO 없는 필드」, 그리고 **DTO 없는 필드 집합의 정확 열거 핀**(`ConversionReviewRequest.edited_text` 하나. C7 이 비운다).

### 왜 열거를 넓히지 않았나 — 규칙 4 분류

`RequestFieldConstraintLayerTest.FORBIDDEN_ANNOTATIONS`(9개)는 **범위 선언형**이고 규칙 ⑶ 이 걸린다(불완전한 선언에서 통과하면 안 된다). 그리고 그 목록은 **닫히지 않는다** — `@CodePointLength`·`@Range`·`@DecimalMin`·직접 만든 `ConstraintValidator`·중첩 빈의 `@Valid`·커스텀 인자 리졸버가 전부 같은 일을 한다. 열거를 넓히는 것은 **다음 항목이 생길 때까지만** 참인 조치다. 그래서 **탐지형으로 갈아탔다**: 나간 바이트를 재는 축은 「무엇이 앞단에서 거절했는지」를 묻지 않는다.

**두 강제자를 함께 둔다(대체가 아니라 추가).** 애너테이션 스캔은 엔드포인트가 없어도 클래스가 생기는 즉시 돈다(`edited_text` 가 그 상태다). 새 탐지기는 요청을 보내야 하므로 엔드포인트를 요구한다. 도달 범위가 달라서 어느 쪽도 다른 쪽을 덮지 못한다.

### 실측이 뒤집은 것 — **F3 의 실제 강제 지점은 애너테이션이 아니다**

음성 대조 NC-A 를 돌리다 확인했다. `DocumentTextRequest.text` 에 `@CodePointLength(max=4000)` 를 붙여도 **두 강제자 모두 초록**이었다. 원인은 위 C4-II 의 규칙 2 다 — `@RequestBody` 는 `@Valid` 가 없으면 검증되지 않으므로 애너테이션이 **무음**이다.

그러므로 F3 위반은 **`@Valid` + 제약 애너테이션의 합성**이고, 애너테이션 스캔은 그 합성의 한쪽만 본다. 새 탐지기는 합성 결과(나간 바이트)를 본다. 이 사실을 두 테스트의 KDoc과 아래 표에 남긴다.

| 변이 | `RequestFieldRejectionLayerTest`(새 탐지기) | `RequestFieldConstraintLayerTest`(애너테이션 스캔) |
|---|---|---|
| `@CodePointLength` 만 | 초록 | 초록 |
| **`@Valid` + `@CodePointLength`**(열거 **밖**) | **빨강** — 「배열 detail 로 나갔다」 | 초록 ← **열거의 구멍** |
| `@Valid` + `@Size`(열거 **안**) | **빨강** | **빨강** |

**둘째 줄이 이 커밋의 근거다.** 열거 스캔 단독으로는 그 편집이 통과한다.

## C4-IV. §9.1 이 못박은 것의 처분

| §9.1 항목 | 이 커밋의 처분 |
|---|---|
| 정렬 `created_at DESC, id DESC` | **C2 가 이미 SQL 에 넣었다**(`JdbcDocumentRepository.listSql`). 이 커밋은 HTTP 층이 그 순서를 흐트리지 않는지만 잰다(`목록이 최신순이다`) |
| `has_more` 를 `limit+1` 로, 전수 `COUNT` 금지 | `DocumentService.list` 가 `limit+1` 을 읽고 `DocumentListResponse.of` 가 자른다. DL-8 이 참·거짓 양쪽 + **요청 개수보다 많이 실리지 않음**을 잰다. `COUNT` 는 어느 SQL 에도 없다 |
| `workspace_id` 를 줘도 `user_id` 조건 유지 | 새 질의를 만들지 않고 **`listOwned` 를 그대로 쓴다**. 소유 술어는 그 SQL `WHERE` 안에 있고 `OwnershipPredicateGuardTest` 의 핀은 **건드리지 않았다**(핀 diff 0) |
| 목록의 검수 표시는 `status` 가 아니라 `reviewed_at` | `DocumentListItem.reviewed_at` 을 응답에 싣고 DL-2 가 **완료 전 항목에서도 키가 있는지**를 잰다 |

## C4-V. 음성 대조 (전건 실측 2026-08-21 · 복원은 `Path.write_bytes` + sha256)

| # | 변이 | 결과 | 겨눈 장치가 그 자리를 지목했나 |
|---|---|---|---|
| **NC-A** | `text` 에 `@CodePointLength` (열거 밖, `@Valid` 없음) | 두 강제자 **초록** | **아니다 — 그리고 그것이 정답이다.** 행동이 안 바뀐다(C4-III) |
| **NC-A′** | **`@Valid` + `@CodePointLength`** | 새 탐지기 **1건 빨강** / 스캔 초록 | **그렇다** — 「배열 detail 로 나갔다」로 필드를 지목 |
| **NC-B** | `@Valid` + `@Size`(열거 안) | **양쪽 빨강**(각 1건) | 그렇다 — 두 축이 겹치는 자리 확인 |
| **NC-C** | `limit` 의 `@Min`·`@Max` 제거 | `DocumentListContractTest` **3건 빨강** | 그렇다 — DL-5 하한·상한 두 케이스 + `loc` 케이스 |
| **NC-D↓** | 계약 `list_limit.max` 100 → **50** | 목록 1건 + 노드 2건 빨강 | 그렇다 — **DL-5 상한초과** + P-25 코드 상수 + P-25 계약 내부 이중 선언 |
| **NC-D↑** | 계약 `list_limit.max` 100 → **200** | 목록 1건 + 노드 2건 빨강 | 그렇다 — **DL-6** + P-25 두 대조 |
| **NC-E** | `DocumentListItemResponse` 에 `@JsonInclude(NON_NULL)` | `DocumentListReachTest` **1건 빨강** | 그렇다 — DL-2(키 생략 금지, X-E2) |
| **NC-F1** | 슬라이스에서 전역 헤더 장치 제거 | DL-1 **초록**(9/9) | **의도한 초록** — 컨트롤러 개별 부착이 응답을 든다는 관측 |
| **NC-F2** | 컨트롤러 개별 부착만 제거(전역 유지) | DL-1 **초록** | **아니다 — 빈자리였다.** 아래 C4-VI 로 닫았다 |
| **NC-F3** | 같은 변이, 새 탐지기 포함 | `DocumentListHeaderFloorTest` **빨강** / DL-1 초록 | 그렇다 |

> **N-24 는 방향마다 다른 케이스가 깨진다.** 상한을 **낮추면** DL-5(상한 초과 값이 통과)가, **올리면**
> DL-6(정확히 상한인 값이 거절)이 깨진다 — 명세가 「DL-5·DL-6」을 함께 적은 것은 **두 방향을 합친
> 서술**이고, 한 방향만 재면 하나는 초록이다. 그래서 양방향을 다 돌렸다.

**복원 확인** — 변조한 파일 전부 sha256 일치. 계약 파일: `5963dc5b89b13b91e44a9bb2da2b35edcd58692a60c9ae5588c739510a9576da`(양방향 변조 후 각각 일치), `git status contracts/` 무변경. `cp`·`git stash` 를 쓰지 않았다.

## C4-VI. 음성 대조가 만든 것 — X-D1 하한선 탐지기

NC-F2 가 드러낸 것: **전역 필터가 살아 있는 컨텍스트에서는 컨트롤러의 개별 부착을 지워도 아무것도 빨개지지 않는다.** 하한선의 근거는 「전역이 없을 때도 나간다」인데 그 성질이 무너지는 편집을 잡는 장치가 없었다 — C3 의 D-d(POI zip 설정)와 같은 형태, 「관측했는데 장치가 없다」다.

`DocumentListHeaderFloorTest` 로 닫았다. `@Import` 에서 `PrivateResponseHeadersConfig` 를 **빼고** 목록 응답에 두 헤더가 있는지 잰다. 케이스가 둘인 이유: 전제(「이 컨텍스트에 전역 장치가 실제로 없다」)가 조용히 깨지는 경로가 있어서다 — 누군가 그 설정을 슬라이스 기본 포함으로 옮기면 첫 케이스는 초록인 채 **전역을 재게 된다**. `/health` 에 헤더가 붙지 않는 것으로 그 전제를 단언한다.

**범위를 이 오퍼레이션 하나로 뒀다.** 같은 빈자리가 하한선의 다른 아홉 자리에도 있지만 **이 커밋이 만든 자리는 `GET /documents` 하나**다(범위는 근거를 넘지 않는다). 열 자리를 한 번에 덮는 형태(계약 `applies_to` 를 읽어 오퍼레이션을 유도)는 개선 백로그에 후보로 적었다.

## C4-VII. 계획·명세에서 갈라진 판단

| # | 계획·명세가 적은 것 | 실제로 한 것 | 사유 |
|---|---|---|---|
| **D-A** | 「계약 값을 코드에 옮겨 적지 않는다」 | `ListPageLimits.kt` 에 **다섯 값을 복제**했다 | Bean Validation 은 애너테이션 인자라 **컴파일 시점 상수만** 받는다 — 계약 파일을 읽을 자리가 문법상 없다. `MAX_UPLOAD_BYTES`(P-24)와 같은 사정·같은 처방(복제를 **셋의 대조**로 지킨다: P-25 코드 상수 · P-25 계약 내부 이중 선언 · DL-5·DL-6·DL-7 의 나간 바이트) |
| **D-B** | (§8.5) N-24 를 「값 변경」 한 번 | **양방향**으로 돌렸다 | 위 표의 주석 — 한 방향만 재면 DL-5·DL-6 중 하나는 초록이라 명세가 요구한 짝이 성립하지 않는다 |
| **D-C** | (C4 범위) DL 케이스 · P-25 · N-24 · F3 강화 | **DC-24 의 목록 축**을 함께 마감했다 | C3 산출물 §III D-x 가 C4 로 넘긴 항목이고 `DocumentEndpointReachTest` KDoc 이 「목록이 생기면 그쪽으로도 잰다」고 적어 두었다. 미루면 그 문장이 강제자 없는 선언으로 남는다 |
| **D-D** | (C4 범위) — | `DocumentListHeaderFloorTest` 를 **더했다** | C4-VI. 음성 대조가 이 커밋이 만든 자리의 빈 강제자를 드러냈다 |
| **D-E** | 명세 DL-5 는 「422 · 배열 · 항목 키 3」 | 거기에 **`loc` 이 파라미터를 지목하는지**를 더했다 | 계약 `ValidationFailed.examples.query_range` 가 `loc: ["query","limit"]` 을 선언한다. 항목 키만 재면 `loc` 이 `["query"]` 로 비어도 통과하고, 그러면 클라이언트가 어느 파라미터를 고쳐야 하는지 알 수 없다. 파라미터 **이름도 계약에서 읽는다** |

## C4-VIII. 이 커밋이 **닫지 못한** 자리 (「미측정」으로 적는다)

1. **`created_at` 동률의 `id DESC` 타이브레이크에는 장치가 없다.** §9.1 이 요구한 성질이고 SQL 에 들어 있지만(C2), 그것을 재는 테스트가 없다 — 오늘 `JdbcDocumentStoreTest` 의 정렬 케이스는 **동률을 만들지 않는다**(실측: 두 문서를 별 트랜잭션에서 만든다). 동률을 만들어 재는 갈래도 성립하지 않는다: 타이브레이크가 없어도 PostgreSQL 의 정렬은 같은 입력에서 **결정적**이라 페이지를 넘겨 읽어도 중복·누락이 나오지 않고, 그러면 그 테스트는 **재지 않은 초록**이 된다. 구조 단언(SQL `ORDER BY` 에 기본 키가 있는지)이 후보이며 개선 백로그에 적었다. **이 커밋은 그 성질을 「측정했다」고 주장하지 않는다.**
2. **X-D1 하한선의 나머지 아홉 자리**(auth 3 · workspaces 3 · 아직 없는 3)는 NC-F2 와 같은 빈자리를 그대로 갖는다. 이 커밋의 근거 밖이다(C4-VI).
3. **DC-24 의 목록 축에 음성 대조를 돌리지 않았다.** 「거절했는데 목록에 보인다」를 만들려면 거절 전 커밋 경로를 합성해야 하고, 그 변이는 저장 경로를 통째로 바꾼다. 단언은 **추가**이고 행 수 축이 이미 있다.
4. **DL-3 은 두 형식(`text`·`docx`)만 관측한다.** enum 넷 중 `pdf`·`hwpx` 는 이 케이스가 아니라 `UploadFormatContractTest`(P-26)가 형식 집합을 계약에서 유도해 덮는다.
5. **compose 기동 스모크**(계획 P4) — 이 배치에서도 돌리지 않았다.

## C4-IX. 검사 표

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin | `./gradlew ktlintCheck detekt build moduleBoundaryCheck parityHarness --continue --rerun-tasks` | **BUILD SUCCESSFUL** (경고 0 — `allWarningsAsErrors`) |
| 개인정보 스캐너 | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | exit 0 (**BLOCK 0**). 새 적중 3건은 전부 `DocumentController` 의 하한선 헤더 부착 줄 — `CACHE-HEADER` 규칙이 **분포 확인**용이라 의도한 적중이다 |
| 하네스 핀 | `uv run pytest tests/test_kotlin_gate_reach.py tests/test_harness_scope_reach.py` | 148 passed |
| Python 린트 | `uv run ruff check .` | All checks passed |
| Python 타입 | `uv run mypy . .claude` | Success: 139 source files |
| Python 테스트 | `uv run pytest` | 1440 passed, 68 skipped, 5 deselected, 5 xfailed |
| 골든셋 | — | **해당 없음** — 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았다 |

**`app/**` 는 한 줄도 건드리지 않았다.**

## C4-X. 계약 레인·리더에게 올리는 것

계약을 고쳐야 할 자리는 **없었다.** 아래는 사실 통보다.

1. **`ValidationFailed.examples.query_range` 의 `msg`·`type` 은 바이트 동일할 수 없다.** 계약 예시는 Pydantic 어휘(`"Input should be greater than or equal to 1"` / `greater_than_equal`)이고 Bean Validation 은 다른 문구·코드를 낸다. 계약이 동결한 것은 상태 코드·키 구성·입력값 미노출이므로(계약 `ValidationFailed` description) 이 커밋은 **`loc` 과 키 집합만** 단언한다. 조항 변경은 요청하지 않는다 — 지금 문면이 이미 그 한계를 적고 있다.
2. **F3 의 실제 강제 지점이 「`@Valid` + 제약 애너테이션」의 합성이라는 사실**(C4-III)은 계약 산문(`x-request-field-constraints.x-decision`)이 다루지 않는다. 계약이 정하는 것은 나간 바이트이므로 **조항 수정은 불필요**하다고 판단했다. 다만 구현 레인의 강제자 설계에 직결되므로 통보한다.

## C4-XI. 다음(C5)이 이어받을 것

- `DELETE /documents/{document_id}` — **DD-1~DD-7** · FK CASCADE 로 변환 동시 파기(그 SQL 은 C2 가 이미 세웠고 `JdbcDocumentStoreTest` 가 잰다).
- DD-3(X-B2) 은 **없는 것과 남의 것의 응답 바이트가 같다**를 요구한다 — 이 커밋의 DL-9 두 번째 케이스와 같은 형태를 쓰면 된다.
- `PINNED_WITHOUT_DTO`(F3 새 탐지기)는 **C7 이 비운다** — `ConversionReviewRequest` 가 생기는 커밋이 프로브를 배선하고 핀에서 지우면 F3 다섯 필드가 이 축에서 마감된다.
