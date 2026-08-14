# Phase 2 · export 도메인 포팅 (kotlin-implementer)

작성 2026-08-14 · 대상 커밋: 이 문서와 같은 커밋

---

## 1. 옮긴 것

| Kotlin | Python 원본 | 비고 |
|---|---|---|
| `core/.../easyread/Export.kt::ExportFormat` | `app/easyread/export.py::ExportFormat` | 확장자 + 미디어 타입 |
| `Export.kt::exportFilename` | `export_filename` | 금지 문자 → 공백, 공백 접기, 앞뒤 `. ` 깎기, 80자 |
| `Export.kt::contentDisposition` | `content_disposition` | RFC 5987 `ext-value` |
| `Export.kt::renderTxt` / `ExportFile` | `render_export` 의 TXT 갈래 | BOM 없음, 제어문자 제거 |
| `core/src/test/.../easyread/ExportTest.kt` | — | 단위 |
| `core/src/test/.../ExportParityTest.kt` | — | 생산자 (아직 `@Tag("parity")` 없음 — §4) |
| `core/src/test/.../ParityDeclarationSyncTest.kt` | — | 신설 탐지기 (§5) |

## 2. 일부러 옮기지 않은 것

- **DOCX·HWPX 렌더링** — Phase 4. POI/ZIP 의존이라 `core` 의 조건("fixture 하나와 순수 함수만으로
  검증 가능한가")을 만족하지 않는다. 지시에도 명시돼 있었다.
- **`export.py::restore_placeholders`** — 이미 `privacy/Masking.kt::restoreForExport` 에 있다.
  두 벌로 두면 한쪽만 고쳐지는 날이 온다. 복원 규칙(정확히 1회일 때만 · 검수본 없으면 보류)은
  마스킹 쪽 결정이므로 그쪽이 정본이다. **Python 파일 배치를 따르지 않은 유일한 자리**다.
- **바이트 해시 비교를 형식 전체로 넓히지 않았다.** 정본이 해시를 요구하는 것은 TXT 하나이고
  (`content_sha256_hex`), 그것은 zip 컨테이너가 없어 "본문 UTF-8 바이트 = 파일"이라 성질이 된다.
  docx·hwpx 는 타임스탬프·엔트리 순서·압축 수준 때문에 같아질 수 없고 같을 필요도 없다(미결 원장).

## 3. Python 과의 갈림: **없음**

생산자 산출물 12건을 정본의 `reference` 와 값 단위로 대조했다 — **12/12 동일**(TXT 해시 포함).
`export-filename-control-chars` 도 같다: Python 도 제어문자를 지우지 않고 **공백으로 바꾼다**.

> 기록용 주의 하나. `renderTxt` 와 `exportFilename` 은 제어문자를 다르게 다루고 **그것이 의도다** —
> `renderTxt` 는 `stripControlChars` 로 **지운 뒤** 파일명을 만들고(`"제목"` → `"제목"`),
> `exportFilename` 을 직접 부르면 **공백으로 바뀐다**(`"제 목"`). 정본의 filename 케이스는
> `exportFilename` 을 직접 부르므로 후자와 대조되며, 그래서 12/12 가 성립한다. 두 경로가 다른
> 값을 낸다는 사실은 `ExportTest` 가 **양쪽 다** 단언해 고정해 두었다.

## 4. 막힌 곳 — 정본이 `pending` 이라 선언할 수 없다 (parity-verifier 인계)

지시는 "생산자 + `parity-domains.txt` 8번째 줄 + declared-floor — 같은 커밋"이었다. **못 한다.**

`parity/fixtures/export/export.json` 은 `spec_status: "pending"` 이고 **12건 전부 `assert` 가 없다**
(`reference` 만 있다). `dump_parity_fixtures.py::build_export` 가 `spec_status=STATUS_PENDING` 으로
만든다. 그 상태에서 선언하면 비교기가 **종료 코드 2(미검증)** 를 낸다 — 추정이 아니라 실측이다.

```
# 8도메인 선언 시 (실측)
[미검증] 도메인 8/8 / 성질 판정 137건(단언 458개) / 판정 보류 2건
         / 미검증 1건 / 불충족 0건 / 도메인 누락 0개 / 파일 8개 (종료 코드 2)
```

CI 는 이것을 사면하지 않는다(`ci.yml`: "2 = 선언한 도메인이 spec_status=pending 이라 미검증 …
둘 다 '아직 포팅하지 않았다'가 아니라 '선언한 것을 지키지 못했다'이므로 사면하지 않는다").

**정본 개수도 확인해 둔다.** `.github/parity-canonical-floor.txt` 는 **8개**다(11개가 아니다).
따라서 export 를 선언하는 순간 `declared_count == canonical_count` 가 되어 CI 는 부분 게이트가
아니라 **전체 게이트**로 돈다 — 통과 조건이 **종료 코드 0**이고 3은 그때부터 실패다.
지시의 "전체 게이트 8/8 exit 3" 은 성립하지 않는 조합이다. 목표값은 **exit 0** 이어야 한다.

### 인계 요청 (`dump_parity_fixtures.py` 는 내 소유가 아니다)

`build_export` 의 12건에 `assert` 를 적고 `spec_status=STATUS_READY` 로 올려 달라. 요구사항 문장은
이미 `FixtureSpec.requirement` 에 쓰여 있으므로 성질 후보는 그것을 그대로 쪼개면 된다:

- 파일명 7건 — 경로 구분자·제어문자 **부재**, 길이 상한(확장자 제외 80자) 준수, 빈 제목의 대체 이름
- `Content-Disposition` — `filename*=UTF-8''` 접두, 값이 latin-1 로 인코딩 가능, 퍼센트 표기 왕복
- 복원 4건 — 자리표시자 **잔존 0**, 미지의 자리표시자는 그대로 남음
- TXT 1건 — BOM 부재, 제어문자 부재, `content_sha256_hex` 일치(컨테이너 없는 형식이라 성질이 된다)

값이 갈릴 위험은 낮다 — §3 대로 지금 12/12 가 동일하다.

## 5. 그동안 조용하지 않게 만든 것 (신설 탐지기)

정본이 `ready` 로 바뀌었는데 아무도 선언하지 않으면 **CI 는 조용히 초록**이다. 기존 세 장치는
그 축을 보지 않는다 — `parityManifestCheck` 는 선언↔산출물, `canonical-floor` 는 정본에 도메인이
있는지, `declared-floor` 는 선언이 줄었는지.

`ParityDeclarationSyncTest` 가 그 자리를 받는다. 양방향이다.

- `ready` 인데 미선언 → 실패(조용한 미가동)
- `pending` 인데 선언 → 실패(커밋 전에 안다. CI 는 이미 잡지만 그때는 CI 에서 안다)

**범위를 한 도메인이 아니라 전 도메인으로 잡은 근거**: 빈자리의 종류가 재발형이다(정본에 도메인이
추가될 때마다 `pending` 으로 들어왔다가 `ready` 로 바뀌고, 그 전환을 알리는 것이 없었다).
넓혀도 오경보가 없다는 것을 **선언 전에 실측**했다 — 오늘 `ready` 7개 = 선언 7개, `pending` 1개
= 미선언 1개. 그리고 탐지형이지 은폐형이 아니다.

**음성 대조**를 붙였다. 정본은 읽기 전용이라 실제 파일을 뒤집어 볼 수 없으므로 판정부를
순수 함수(`mismatches`)로 갈라 합성 입력 3건으로 잰다 — ready 전환 누락 검출 / 성급한 선언 검출 /
어긋남 없을 때 무검출.

생산자는 그래서 **`@Tag("parity")` 없이** 커밋한다. 태그가 없으면 `parityHarness` 가 저장소 루트에
쓰지 않아 `parityManifestCheck` 의 "산출 O / 선언 X" 가 나지 않고, 일반 `test` 에서는 그대로 돌아
12건을 실제로 만들어 낸다는 것이 매 실행 확인된다. 정본이 `ready` 가 되는 날 위 탐지기가 빨개져
세 줄(`@Tag`, `parity-domains.txt`, `declared-floor`)을 요구한다. 주석이 아니라 탐지기로 둔 이유는
주석은 아무 날에도 알리지 않기 때문이다.

## 6. 곁가지 변경

`ProvenanceCreationSitesTest` 허용목록에 `ExportParityTest.kt` 를 `ModelDraft` 1 · `ReviewedBody` 1 로
추가했다. `restoreForExport` 는 검수본이 없으면 복원을 **보류**하므로(사람이 위치를 확증하지 않은
본문에 개인정보를 꽂지 않는다), 정본이 요구하는 "자리표시자가 남김없이 복원된다"를 재려면 검수
제출을 표현할 수밖에 없다. 값의 출처는 fixture 이고 프로덕션 경로가 아니다 — `MaskingTest` 가 같은
이유로 이미 그 목록에 있다.

## 7. 검사 결과

| 검사 | 결과 |
|---|---|
| `./gradlew build` (ktlint·detekt·전체 테스트 포함) | **BUILD SUCCESSFUL** |
| `./gradlew parityHarness` + `parityManifestCheck` | **BUILD SUCCESSFUL** (산출 7 / 선언 7) |
| `compare_parity.py` 부분 게이트(선언 7) | **종료 코드 3** · 불충족 0 · 미검증 0 |
| `scan_privacy_invariants.py` | **종료 코드 0** |
| export 12건 vs 정본 `reference` | **12/12 동일** |
| Python 게이트(ruff·mypy·pytest) | **미실행 — Python 파일 변경 0건.** `app/**` 도 스캐너도 건드리지 않았다 |

`git status` 상 이 조각의 변경은 Kotlin 5개 파일뿐이다. 스캐너 동결(§4-novies 대기)과
parity 소유 파일 금지(`compare_parity.py` · `dump_parity_fixtures.py` · `.github/parity-*` ·
`parity/fixtures/**`)를 지켰다 — fixture 는 **읽기만** 했다.
