# 게이트 27 조치 — `kotlin-implementer` 산출물 (2026-08-20)

**기준선**: `d19175b`
**정본 리뷰**: `docs/migration/_workspace/reviews/04_documents_cross.md`
(1회차 둘: `04_documents_codex-reviewer.md` · `04_documents_migration-reviewer.md`).
심판문 세 파일은 **읽기만 했고 편집하지 않았다.**

계획 문서의 대응 기록은 `04_kotlin-implementer_documents-plan.md` **§9.2-quater**(신설)와
**§9.2-bis D-6′**(문면 정정)다. 여기에는 그것을 **값으로 옮겨 적지 않는다** — 사유·음성 대조
표·계약 레인 조항은 계획이 정본이다. 이 파일은 「무엇을 어느 파일에서 고쳤는가」의 대응표다.

---

## 1. 고친 것 — 파일 대응표

| 지시 | 파일 | 무엇을 |
|---|---|---|
| **A** | `core/…/document/TitleRules.kt` | 본문 유도 갈래 삭제. `resolveTitle(given, uploadFilename)` 로 시그니처 변경. `AUTO_TITLE_TARGET_LENGTH`·`TITLE_ELLIPSIS`·`shortenDerivedTitle` **삭제**. `baseNameOf` 신설(경로·확장자·제어문자 처리) |
| **A** | `application/…/document/DocumentService.kt` | `store` 가 파일 이름을 받도록 통로를 열었다(`TitleSources`). `createFromFile` → `store` 로 `filename` 전달. KDoc 3곳 갱신 |
| **A** | `core/src/test/…/TitleRulesTest.kt` | 본문 유도 케이스 6건 삭제, 파일 이름·대체 제목 케이스 7건 신설 |
| **A** | `application/src/test/…/DocumentServiceTest.kt` | 제목 케이스 3건 교체·신설 |
| **A(탐지)** | `infrastructure/src/test/…/document/JdbcDocumentStoreTest.kt` | **`본문 표식이 평문 열에 남지 않는다`** 신설 — 열 목록을 `information_schema` 에서 **파생**, 빈 분모 빨강, 바닥 목록 + 열 수 하한 |
| **B-1** | `tests/test_kotlin_gate_reach.py` | `MIN_TEST_CLASSES`(85) · `FLOOR_TEST_CLASSES`(18건) 신설, 테스트 함수 2건 추가, **실패 안내문의 축소 경로 안내 제거**, 모듈 docstring 갱신, 새 클래스 등재 + `TEST_CLASS_COUNT` 87→88 |
| **B-2** | `infrastructure/…/ingest/ExtractedTextBuilder.kt` | `BlockSink` 포트 + `ensureRoomFor` 신설, `BlockList`(대조용, **같은 예산**) 신설, 길이 계산을 `Long` 으로 |
| **B-2** | `infrastructure/…/ingest/HwpxExtractor.kt` | `SectionBlocks` 가 목록을 들지 않고 sink 로 흘려보낸다. `characters()` 가 **붙이기 전에** 예산 질의 |
| **B-2** | `infrastructure/…/ingest/DocxExtractor.kt` | `collectInto(data, sink)` 로 통합. `extract` 는 builder, `blocks` 는 `BlockList`. `elementBlocks` 가 sink 로 흘려보내고 예산 질의 |
| **B-2** | `infrastructure/src/test/…/ingest/{HwpxExtractorTest,ExtractedTextBuilderTest}.kt` | 「문서 끝에 닿기 전에 끊는다」 + sink 예산 케이스 3건 |
| **B-3** | `infrastructure/src/test/…/ingest/IngestDefensesTest.kt` | 값을 **어긋뜨린 뒤 제품 조립만** 부르고 복구를 확인. `@BeforeEach`/`@AfterEach` 로 JVM 전역 복원. 못 재는 것 2종을 구분해 기록 |
| **B-3** | `infrastructure/…/ingest/PoiZipDefenses.kt` | KDoc — 「행동 음성 대조 불성립」을 **축별로** 다시 적었다(폭탄 축은 불성립 / 설치 축은 성립) |
| **C** | `infrastructure/src/test/…/db/StatementCountingPremiseTest.kt` | 하한 `MIN_PORT_ADAPTERS=12` + 바닥 목록 `KNOWN_PORT_ADAPTERS`, 재귀 인터페이스 폐쇄, 금지 손잡이를 **할당 가능성**으로, 합성 probe 2종으로 판정 함수 실행 확인, KDoc 「막지 못하는 것」 2항 추가 |
| **D** | `04_kotlin-implementer_documents-plan.md` §9.2-bis | **D-6 개정문을 취소선으로 남기고 D-6′ 신설.** 실측 결과 첨부 |
| **E** | `api/src/test/…/UploadFormatContractTest.kt` (**신설**) | 계약 `x-input-limits.supported_upload_formats` 를 **읽어** `SourceFormat.UPLOAD_FORMATS` 와 대조 |
| **E** | `core/…/document/SourceFormat.kt` · `infrastructure/src/test/…/DocumentExtractorsTest.kt` | 문면 정정 — 계약 대조를 하는 테스트를 **이름으로** 가리키고, 손으로 적은 값과 대조하는 케이스는 그 사실을 `@DisplayName` 과 주석에 적는다 |
| **E** | `04_kotlin-implementer_improvement-backlog.md` | **B-9 해소 처리** + 「이 항목이 애초에 백로그에 있어서는 안 됐다」는 사유 |
| **(덤)** | `infrastructure/…/ingest/HwpxExtractor.kt` | `createXMLStreamReader` 를 `try` 안으로(codex C-4/C-9, 교차 종합 행 4). **지시 목록에 없었으나** 같은 메서드를 재작성 중이었고 교차 종합 §11 이 이 레인으로 보낸 항목이다 |

---

## 2. 리더가 준 전제 중 **실측과 다른 것** — 확인 필요

> 지시문: *"계약은 `title` 을 선택으로만 적고 **생략 시 동작을 규정하지 않는다**(리더 실측:
> `contracts/easy-doc-v1.yaml` 의 `title` 은 `max_title_length: 255` 와 스키마 `maxLength: 255` 뿐)."*

**그렇지 않다.** 계약은 생략 시 동작을 **두 자리에서 명시**하고, 파일명 사용을 **명시적으로
금지**한다.

| 자리 | 원문 |
|---|---|
| `contracts/easy-doc-v1.yaml:1848` | *"생략하면 본문 첫 줄에서 유도한다(30자 목표, 어절 경계에서 자르고 `…` 부착)."* |
| `:1850` | *"**파일명은 제목으로 쓰지 않는다** — 파일명 자체가 개인정보일 수 있다."* |
| `:1872` | *"폼 값은 전부 문자열이다. 생략하면 추출한 본문 첫 줄에서 유도한다."* |
| `.claude/skills/migration-safety-gate/SKILL.md:63` | **보장** — *"파일명은 아예 저장하지 않는다(파일명 자체가 개인정보일 수 있다)."* |

따라서 이번 변경은 **조항 추가가 아니라 개정 3건 + 게이트 보장 문장 1건의 개정**이다.
지시대로 **계약 파일과 게이트 문서는 건드리지 않았고**, 필요한 개정을 계획 §9.2-quater
「계약 레인에 올릴 조항」에 적었다.

**함께 올리는 판단**: 파일 이름을 평문 `title` 로 저장하는 것은 `홍길동_주민등록등본.docx`
같은 이름을 평문으로 남긴다. 사용자 결정이므로 구현했고, 줄일 수 있는 만큼(경로·확장자·
제어문자)만 줄였다. **이름 안의 개인정보 자체는 걸러내지 않는다.** `privacy-gate` 판정이
필요한 자리라고 본다 — C-1 이 닫히면서 **다른 평문 유입 경로가 하나 열렸다.**

---

## 3. 판단해서 정한 것 (리더가 위임한 자리)

**파일 이름 → 제목의 정의역** (계획 §9.2-quater D-o-2 가 사유의 정본, 규칙만 다시 적는다):
마지막 경로 조각만(`/`·`\` 둘 다) → 마지막 확장자 제거(맨 앞 점은 제외) → 제어문자 제거 →
255 코드 포인트에서 자르기 → 남는 것이 없으면 `제목 없음`.

**적어 준 제목이 제어문자뿐이면 파일 이름으로 넘어가지 않는다.** 곧바로 `제목 없음` 이다 —
「사용자가 무언가를 적었다는 사실을 다른 값으로 덮지 않는다」는 이전 판의 판단을 그대로 둔다.

---

## 4. 하지 않은 것

- `contracts/easy-doc-v1.yaml` · `migration-safety-gate` SKILL.md — 지시대로 건드리지 않았다.
- **M-3**(`lockSourceText`/`lockEnvelope` 의 `ownerId`) · **CR-1**(원장 문언) · 표 18 TRACE(J-1) ·
  §9 질문 ⑦ — 범위 밖.
- 교차 종합 Minor 8건(행 16~23) 중 지시에 없는 것 — 손대지 않았다. **행 16(문자 수 단위
  불일치)은 이번에 만진 `ExtractedTextBuilder` 와 같은 자리이므로 다음 회차에 함께 처분할
  후보로 남는다**(교차 종합 §7-2 가 같은 지적을 했다).
- **N-26**(계약에서 원소를 빼면 검사가 줄어드는지) — 계약 파일을 고쳐야 하므로 실행하지 않았다.

---

## 5. 절차 사고 1건 (숨기지 않고 적는다)

`tests/test_kotlin_gate_reach.py` 의 음성 대조를 마치고 `git checkout --` 로 되돌렸는데,
**그 파일의 이번 회차 수정이 아직 커밋되지 않은 상태**여서 작업분이 함께 사라졌다(재작성해
복구했고 최종 상태는 검사로 확인했다).

**교훈**: 「복원은 `cp` 가 아니라 git 경유 + sha256」 규칙은 **커밋된 기준선**을 전제한다.
미커밋 작업 위에서 음성 대조를 할 때는 **먼저 커밋하고** 되살리거나, 역패치 + sha256 대조로
되돌려야 한다. 나머지 다섯 건의 음성 대조는 역패치 + sha256 대조로 복원했고 전부 `OK` 였다.

---

## 6. 검사 결과 (전건 실행, 종료 코드)

| 검사 | 명령 | 종료 코드 |
|---|---|---|
| Kotlin 전체 | `./gradlew build` (ktlint·detekt·단위·Testcontainers 포함) | **0** |
| 게이트 도달(로컬 모드) | `uv run pytest tests/test_kotlin_gate_reach.py` | **0** (95 passed) |
| 게이트 도달(**요구 모드**) | `KOTLIN_GATE_REACH_REQUIRE_REPORT=1 uv run pytest tests/test_kotlin_gate_reach.py` | **0** (95 passed) |
| Python lint | `uv run ruff check .` | **0** |
| Python 타입 | `uv run mypy . .claude` | **0** (139 files) |
| Python 테스트 | `uv run pytest` | **0** (1413 passed · 68 skipped · 5 xfailed) |

전부 `run_gate.sh` 경유로 돌렸다(파이프 사용, 종료 코드는 러너가 전파). `git stash` 미사용.

**미실행**: `uv run pytest tests/golden` 은 별도로 돌리지 않았다 — 프롬프트·스타일 규칙·LLM
설정을 건드리지 않았고, 위 `uv run pytest` 전체 실행에 포함된다.
