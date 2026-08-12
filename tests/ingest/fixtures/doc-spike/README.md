# 문서 spike 합성 fixture (구조 편입, 2026-08-12)

Phase 0 문서 라이브러리 spike(`docs/migration/_workspace/00_kotlin-implementer_doc-spike.md`)가
만든 합성 fixture다. **저장소 밖 스크래치패드에만 있었고, tmp 정리로 사라질 상태였다.**

## 왜 급했나

- 이 fixture가 덮는 케이스(SDT · `a:t` · `m:t` · 다단 PDF · 선언 크기 위조 zip)는
  `tests/ingest/fixtures/` 의 기존 6개에 **없다.** DOC-01 정확성과 I-10 zip 방어의
  **유일한 실증물**이다.
- `oracle.json` 을 만드는 `make_doc_spike_fixtures.py` 가 `app.ingest.extractors` 를
  import 한다 → **Python 삭제 후에는 재생성이 불가능하다.**
- 생성기 소스는 spike 문서 본문에 실려 있지 않다. 이 파일이 유일본이다.

## 담긴 것

| 파일 | 내용 |
|---|---|
| `sdt_shape_math.docx` | SDT(구조화 문서 태그) · 도형 텍스트(`a:t`) · 수식(`m:t`) |
| `layout.pdf` | 다단 레이아웃 |
| `make_doc_spike_fixtures.py` | 위 둘과 `forged_size.zip` 의 생성기 |
| `oracle.json` | 기대 추출 결과 |

**`forged_size.zip`(선언 크기 위조) 은 구조 시점에 이미 없었다.** 생성기가 만들 수
있으나 그 실행은 Python 런타임에 의존한다 — I-10 검증 3(`ZipEntry.getSize()` 를
믿지 않는다)의 실증물이 필요하면 **Python 을 지우기 전에** 생성해 함께 둔다.

## 앞으로

이 디렉터리는 **P1 반출 대상**이다(`docs/migration/_workspace/03_rebuild-extraction-list.md`).
`tests/**` 는 Phase 8 삭제 구역이므로, Kotlin test resources 로 옮기기 전까지
지우지 않는다. 옮길 때 `oracle.json` 의 기대값은 **Python 이 만든 값이므로 정답이
아니라 참고**다 — Kotlin 추출기가 다른 값을 내면 어느 쪽이 요구사항(DOC-01)에
맞는지 판단해 기록한다(master-plan §6.2).
