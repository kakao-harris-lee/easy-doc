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

**결론: `forged_size.zip` 이 I-10 검증 3의 실증물이 맞다. 피해가 실측된다.**
네 번 고쳐 얻었으므로 측정 방법까지 남긴다 — 방법을 잘못 고르면 결론이 뒤집힌다.

**최대 메모리로 재야 한다.** 최종 결과(예외/거부)만 보면 안전해 보인다:

| 읽기 방식 | 결과 | 최대 메모리 |
|---|---|---|
| `z.read(name)` 무제한 | `BadZipFile` | **189.5 MB** |
| `f.read()` 인자 없음 | `BadZipFile` | **189.5 MB** |
| `f.read(1MB)` 청크 | `BadZipFile` | 2.2 MB |

81KB 파일이고 **선언 크기는 1,024B** 인데 무제한으로 읽으면 190MB 가 든다.
**예외는 메모리를 다 쓴 뒤에 난다** — 거부되는 것과 안전한 것은 다르다.

방어는 **경계 있는 읽기**다. `_ensure_zip_within_budget` 이
`read(min(_COUNT_CHUNK_BYTES, budget + 1))` 로 읽어 예산 밖으로 못 나간다.
표의 셋째 줄이 그 방어를 흉내 낸 것이고, 그래서 2.2MB 로 막힌다.

Kotlin 쪽도 같은 모양이다 — `ZipEntry.getSize()` 가 1,024 를 돌려주는데
스트림을 끝까지 읽으면 80MB 가 나온다. **이 fixture 는 그대로 쓸 수 있다.**

### 내가 네 번 틀린 경위 (측정 설계의 교훈)

⑴ 근거 없이 "유일한 실증물"이라 적었다.
⑵ 거부 **사유**가 예산이 아닌 것만 보고 강등했다 — 기제와 요구를 뒤바꿨다.
⑶ "선언 크기를 합산만 하는 스텁"이 통과하는 것을 판별력으로 착각해 복원했다 —
   그 스텁은 압축을 풀지 않아 **피해를 재지 않는다.**
⑷ 그다음 프로브에서 `f.read(1<<20)` **청크 읽기**를 썼다. 그것이 바로 시험하려던
   **방어 그 자체**다. 방어를 구현한 프로브로 재고 "아무도 안 다친다"고 결론냈다.

⑷가 가장 배울 만하다 — **취약점을 재려면 프로브가 방어를 갖고 있으면 안 된다.**
그리고 **"무엇이 거부되는가"가 아니라 "무엇이 소모되는가"** 를 물어야 했다.

### 두 파일의 역할

- `forged_size.zip` → **취약점 실증.** 무제한 읽기 구현이 190MB 를 쓴다.
  판정은 예외 발생 여부가 아니라 **최대 메모리**로 한다.
- `oversized.zip` → **기제 시험.** 예산 검사가 실제로 발화하는 것을 보인다
  (`uncompressed_too_large`). 선언이 정직해 판별력은 없다.

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
