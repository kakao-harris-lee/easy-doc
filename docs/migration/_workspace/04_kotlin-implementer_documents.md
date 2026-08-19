# Phase 4 `documents` 작업 단위 — 구현 산출물

**작성:** kotlin-implementer / **일자:** 2026-08-20 / **상태:** **진행 중 — 계획 §7.2 의 C1 까지.**
**계획(정본):** `04_kotlin-implementer_documents-plan.md` (커밋 `5261cfe`, §9.2 는 이 배치에서 추가).

> 이 문서는 단위가 끝날 때까지 **이어 쓴다.** 계획 §7.2 의 C8 이 가리키는 파일이 이것이다 —
> 커밋마다 새 파일을 만들면 「무엇이 이 단위의 결론인가」가 흩어진다.

---

## 0. 이번 세션이 한 것 / 하지 않은 것 (한눈에)

| | |
|---|---|
| **끝난 것** | **C1 — 문서 추출기(docx·pdf·hwpx)와 파서 방어.** version catalog + 락파일 5개, `core/document`, `application/document` 포트, `infrastructure/ingest` 9개 파일, fixture 12개 이관 + README, 테스트 클래스 8개(케이스 70), 게이트 핀 갱신 |
| **하지 않은 것 (이 단위 잔여)** | C2~C8 전부. 그리고 **표 18 TRACE 카나리 회귀**(계획 §9.2 D-f — C1 이 아니라 C3 이전으로 옮겼다, **리더 확인 필요**) |
| **검사** | Kotlin build/ktlint/detekt/moduleBoundaryCheck **통과** · Python ruff/mypy/pytest **통과** · 개인정보 스캐너 **통과(BLOCK 0)** · parity harness **§6 참고** |
| **워킹 트리** | 커밋 뒤 clean (§7) |

---

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
