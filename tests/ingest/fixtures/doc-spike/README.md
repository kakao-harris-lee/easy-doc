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

**결론부터: 두 파일 중 어느 것도 `getSize()` 신뢰 취약점을 실증하지 못한다.**
세 번 고쳐 얻은 결과이므로 경위를 남긴다.

순진한 구현(선언 크기를 믿고 통과시킨 뒤 **실제로 압축을 푸는** 형태)에 물렸다:

| fixture | 순진한 구현 | 결과 |
|---|---|---|
| `forged_size.zip` | `BadZipFile: Bad CRC-32` | **뚫리지 않는다** |
| `oversized.zip` | 거부(선언 83,886,080B > 예산) | **뚫리지 않는다** |

**아무도 피해를 입지 않으므로 취약점이 시연되지 않는다.**

원인은 구조적이다 — Python `zipfile` 은 **선언 크기만큼만 읽고 자른다.** 선언을
1KB 로 위조하면 1KB 만 읽히고 CRC 가 어긋나 거부된다. 즉 **Python 에서는 이
취약점이 구성상 재현되지 않는다.** `ZipEntry.getSize()` 를 믿고 스트림 끝까지
읽는 **Java/Kotlin 쪽 문제**이고, Python oracle 로는 검증할 수 없다.

세 번의 오류를 그대로 남긴다. ⑴ 근거 없이 "I-10 검증 3의 유일한 실증물"이라
적었다. ⑵ 거부 **사유**가 예산이 아닌 것만 보고 강등했다 — 기제와 요구를 뒤바꾼
오류다. ⑶ "선언 크기를 합산만 하는 스텁"이 통과하는 것을 판별력으로 착각해 복원
했다 — 그 스텁은 압축을 풀지 않으므로 **피해 여부를 재지 않는다.**

**남는 것**

- `oversized.zip` → **기제 시험**으로 유효하다. 예산 검사가 실제로 발화한다
  (`uncompressed_too_large`). 판별력은 없다(선언이 정직해 순진한 구현도 거부한다).
- `forged_size.zip` → **파서 견고성 시험**으로 유효하다. 선언이 거짓인 zip 을
  거부한다. `getSize()` 신뢰 취약점의 실증물은 **아니다.**
- **I-10 검증 3 은 미실증이다.** 닫으려면 CRC 가 맞으면서 선언만 작은 zip 이
  필요하고, 그 판정은 **Kotlin 추출기에 직접 물려서** 해야 한다. Phase 4 구현자의
  몫이며 Python oracle 은 이 항목에 근거를 줄 수 없다.

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
