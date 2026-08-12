# 문서 spike 합성 fixture (구조 편입, 2026-08-12)

Phase 0 문서 라이브러리 spike(`docs/migration/_workspace/00_kotlin-implementer_doc-spike.md`)가
만든 합성 fixture다. **저장소 밖 세션 스크래치패드에만 있었고, tmp 정리로 사라질 상태였다.**

## 왜 급했나

- 이 fixture가 덮는 케이스(SDT · 도형 텍스트 `a:t` · 수식 `m:t` · 다단 PDF ·
  **선언 크기 위조 zip**)는 `tests/ingest/fixtures/` 의 기존 6개에 **없다.**
  DOC-01 정확성과 I-10 zip 방어의 **유일한 실증물**이다.
- 생성기가 `app.ingest.extractors` 를 import 한다 → **Python 삭제 후에는 실행 불가.**
- 생성기 소스는 spike 문서 본문에 실려 있지 않다. 이 파일이 유일본이다.

## 담긴 것

| 파일 | 내용 |
|---|---|
| `sdt_shape_math.docx` | SDT(구조화 문서 태그) · 도형 텍스트(`a:t`) · 수식(`m:t`) |
| `layout.pdf` | 다단 레이아웃 |
| `oversized.zip` | **압축 예산 초과 zip**(위조 없음) — I-10 검증 3의 실증물. `reason=uncompressed_too_large` |
| `forged_size.zip` | 선언 크기를 1KB로 **위조한** zip. `reason=BadZipFile` — 아래 단서를 읽어라 |
| `spike-oracle.json` | **위 셋의** 기대 결과. `sdt_shape_math.docx`(+`::blocks`) · `layout.pdf` · `forged_size` |
| `repo-fixtures-oracle.json` | 기존 6개 fixture 의 기대 결과 + `_raw_docx_blocks` · `_hwpx_roundtrip` |
| `make_doc_spike_fixtures.py` | 위 세 fixture 와 `spike-oracle.json` 의 생성기 |

## 두 zip 은 서로 다른 것을 시험한다 (2026-08-12, 두 번 정정한 끝)

**대조 실험으로 확정했다.** "헤더 선언 크기를 그대로 믿는 순진한 구현"을 흉내 내
두 파일을 통과시켜 봤다:

| fixture | 헤더 신뢰 구현 | 실제 구현 | 판별력 |
|---|---|---|---|
| `forged_size.zip` | **통과** (선언 1,024B 를 믿는다) | 거부(`BadZipFile`) | **있다** |
| `oversized.zip` | 거부(예산 초과) | 거부(`uncompressed_too_large`) | **없다** |

**`forged_size.zip` 이 I-10 검증 3(`ZipEntry.getSize()` 를 믿지 않는다)의 실증물이
맞다.** 헤더를 믿는 구현이 이 파일을 통과시키기 때문이다.

내가 두 번 틀렸다. ⑴ 처음엔 근거 없이 "유일한 실증물"이라 적었고, ⑵ 그다음엔
거부 **사유**가 `BadZipFile`(예산 아님)인 것만 보고 판별력이 없다고 단정해 이
파일을 강등했다. **판별의 기준은 거부 사유가 아니라 "헤더를 믿는 구현이
통과하는가"다.** 사유가 무엇이든 뚫리지 않으면 요구는 충족된다.

`oversized.zip` 도 남긴다 — 판별력은 없지만 **예산 경로 자체가 발화하는 것**을
보인다(`uncompressed_too_large`). 둘의 역할이 다르다:

- `forged_size.zip` → **취약점 시험**. 헤더를 믿으면 뚫린다
- `oversized.zip` → **기제 시험**. 예산 검사가 실제로 도는가

**남는 한계(주장하지 않는다):** `forged_size.zip` 은 **이 파일 하나가** 처리됨을
보일 뿐, 깨끗하게 파싱되면서 선언만 거짓인 zip 까지 막힌다는 증명은 아니다. 지금
이 파일은 위조가 구조를 깨 파서 단계에서 걸린다. 그런 zip 을 만들어 예산 경로가
직접 잡는 것을 보이는 것은 **아직 못 했다** — Phase 4 구현자가 닫을 자리다.

**두 oracle 을 헷갈리지 마라.** 처음 구조할 때 `oracle.json` 하나만 가져왔는데
그것은 **기존 6개의 것**이었고 spike 자신의 기대값(`extra_oracle.json`)은 빠져
있었다. 이름을 `repo-fixtures-oracle.json` / `spike-oracle.json` 으로 갈랐다.

## 무엇이 자기완결이고 무엇이 아닌가

**자기완결이다 — 자산 4개(fixture 3 + `spike-oracle.json`).** Python 이 없어도
파일과 기대값이 그대로 선다. Kotlin 추출기를 이 셋에 물려 `spike-oracle.json` 과
대조하면 된다.

**자기완결이 아니다 — 생성기.** `app.ingest.extractors` 와 `python-docx` 에
의존하므로 Python 삭제 후에는 못 돈다. 그래서 **산출물을 전부 저장소에 넣었다** —
재생성 능력이 아니라 결과물이 자산이다. 구조 시점에 `forged_size.zip` 이 없어
`app/` 이 살아 있는 동안 생성기를 다시 돌려 만들었다.

세션 스크래치패드 절대경로가 두 곳에 박혀 있어 다른 곳에서 실행할 수 없었다.
산출물을 스크립트 옆에 쓰도록 고쳤고, 저장소 안에서 실행되는 것을 확인했다.
`sdt_shape_math.docx` 는 재실행하면 zip 내부 타임스탬프 때문에 바이트가 달라진다
— **바이트 재현성은 없다.** 대조는 파일 해시가 아니라 `spike-oracle.json` 의
추출 결과로 한다.

## 앞으로

이 디렉터리는 **P1 반출 대상**이다(`docs/migration/_workspace/03_rebuild-extraction-list.md`).
`tests/**` 는 Phase 8 삭제 구역이므로 Kotlin test resources 로 옮기기 전까지
지우지 않는다.

옮길 때 두 oracle 의 기대값은 **Python 이 만든 값이므로 정답이 아니라 참고**다 —
Kotlin 추출기가 다른 값을 내면 어느 쪽이 요구사항(DOC-01)에 맞는지 판단해
기록한다(master-plan §6.2). `forged_size` 항목만은 성격이 다르다: 기대 동작이
"거부"이고 그것은 값 일치가 아니라 **보안 성질**이라 양쪽 런타임에 똑같이 요구된다.
