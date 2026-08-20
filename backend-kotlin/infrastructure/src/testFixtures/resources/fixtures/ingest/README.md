# 문서 추출 fixture (Kotlin 이관본, 2026-08-20)

`tests/ingest/fixtures/` 와 `tests/ingest/fixtures/doc-spike/` 에서 **복사**해 왔다.
원본은 지우지 않는다 — `tests/**` 삭제는 Phase 8 의 게이트 뒤다.

## 출처와 sha256 (복사 시점 실측)

| 파일 | 원본 경로 | sha256 |
|---|---|---|
| `sample.docx` | `tests/ingest/fixtures/sample.docx` | `dd07999692cc85f478ccd5f6b44379182dece86f2789b1cf32bee9308a227aaf` |
| `sample_table.docx` | `tests/ingest/fixtures/sample_table.docx` | `e5aa07251e6f5a1d60c7269adfc0888da1aab57cbe6e7c6dbab154957de77d04` |
| `sample_rich.docx` | `tests/ingest/fixtures/sample_rich.docx` | `293826048a4ee9dbc69c26655797013a209503baa9aea4e054bb1708ef4c837b` |
| `sample.pdf` | `tests/ingest/fixtures/sample.pdf` | `6dea7c19101e5f119ac9b5730faaef9f81207242fc5521eca80fb8f3ccf38671` |
| `empty.pdf` | `tests/ingest/fixtures/empty.pdf` | `907783373c03ec7ae7a2f658db0c8681ae05b6ee129eb81373aeec585dc595a5` |
| `sample.hwpx` | `tests/ingest/fixtures/sample.hwpx` | `35a479055b56234a5e7796683c113330b6dea92772268935be2cf1788ee73442` |
| `sdt_shape_math.docx` | `tests/ingest/fixtures/doc-spike/sdt_shape_math.docx` | `4da9954cbdf261a2f210a9b327b218c8e883969d9297bfc3e2b7fafb96250058` |
| `layout.pdf` | `tests/ingest/fixtures/doc-spike/layout.pdf` | `97b25cb6dde3a5ceae25d51b6f055ceedc8b50eaf2ce5ae586e48cbfd9bf83cf` |
| `oversized.zip` | `tests/ingest/fixtures/doc-spike/oversized.zip` | `94a3602e6d50471b51df8520083dd3977d0ed97fa40392033d4a116fd64f4a66` |
| `forged_size.zip` | `tests/ingest/fixtures/doc-spike/forged_size.zip` | `91f258d9ad54949e7d27166d47a6cfaa4a2ad96579b3b0318ff654ecb0f6699c` |
| `repo-fixtures-oracle.json` | `tests/ingest/fixtures/doc-spike/repo-fixtures-oracle.json` | `3b1ebbc8cbd5848aa82759571eb1e1f7cdd44766ecd871cd798e01e736743e59` |
| `spike-oracle.json` | `tests/ingest/fixtures/doc-spike/spike-oracle.json` | `d28a272318ee034293fe12a2e2ae7774c4783e93082049d4e63ba130a30c25c4` |

## 두 oracle 은 **정답이 아니라 참고값**이다

값은 Python 이 만든 것이고, 2026-08-12 재개발 전환 이후 기준은 **요구사항**이다
(master-plan 6.2 · 프로젝트 `CLAUDE.md`). Kotlin 이 다른 값을 내면 그 자체를 결함으로
보지 말고 어느 쪽이 DOC-01(누락 없는 추출)에 맞는지 판단해 산출물에 기록한다.

**예외 하나** — `forged_size` 는 성격이 다르다. 기대 동작이 "거부"이고 그것은 값 일치가
아니라 **보안 성질**이라 양쪽 런타임에 똑같이 요구된다(`migration-safety-gate` I-10 검증 3).

## 두 zip 이 시험하는 것이 다르다

- `forged_size.zip` — **취약점 실증.** 선언 크기를 1KB 로 위조한 81KB 파일이고,
  경계 없이 읽는 구현은 힙 190MB 를 쓴다(원본 README 의 Python 실측).
  판정은 예외 발생 여부가 아니라 **소모한 메모리**로 한다.
- `oversized.zip` — **기제 시험.** 예산 검사가 실제로 발화하는 것을 보인다.
  선언이 정직해 판별력은 없다.

## 만들지 않고 즉석 생성하는 것

DTD 폭탄 hwpx(UTF-8·UTF-16) · DOCTYPE 주입 docx · 압축 폭탄 docx 는 **커밋하지 않는다.**
정상 fixture 를 변형해 테스트 안에서 만든다(원본 `tests/ingest/` 의 방식). 폭탄 파일을
저장소에 두면 보안 스캐너·백업·CI 아티팩트로 퍼진다.

## 아직 없는 것 (정직하게 적는다)

- **실제 한컴 오피스·MS Word 로 저장한 문서** — 여기 있는 것은 전부 합성물이다.
- **실제 공공기관 PDF** — pypdf ↔ PDFBox 동등성은 합성 다단에서만 확인됐다.
- **암호가 걸린 실제 DOCX/PDF** — OLE2 매직 판별은 합성 바이트로만 확인한다.

셋 다 spike §6·§7-5 가 "확보 권고"로 남긴 것이고, 없으면 절체 전에 "주요 fixture 불일치"
(계획 §5 Phase 7 즉시 중단 기준)를 검출할 방법이 없다. 계획 §9 질문 ⑧ 이 그 요청이다.
