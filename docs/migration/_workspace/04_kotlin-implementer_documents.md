# Phase 4 `documents` 작업 단위 — 구현 산출물

**작성:** kotlin-implementer / **일자:** 2026-08-20 / **상태:** **진행 중 — 계획 §7.2 의 C2 까지.**
**계획(정본):** `04_kotlin-implementer_documents-plan.md` (커밋 `5261cfe`, §9.2 는 이 배치에서 추가).

> 이 문서는 단위가 끝날 때까지 **이어 쓴다.** 계획 §7.2 의 C8 이 가리키는 파일이 이것이다 —
> 커밋마다 새 파일을 만들면 「무엇이 이 단위의 결론인가」가 흩어진다.

---

## 0. 지금까지 한 것 / 하지 않은 것 (한눈에)

| | |
|---|---|
| **끝난 것** | **C1 — 문서 추출기(docx·pdf·hwpx)와 파서 방어** (§1~§6) · **C2 — 문서·변환 저장 경로**(§A~§F, 아래) |
| **하지 않은 것 (이 단위 잔여)** | C3~C8. 그리고 **표 18 TRACE 카나리 회귀**(계획 §9.2 D-f — C3 이전, **리더 확인 필요**) |
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
