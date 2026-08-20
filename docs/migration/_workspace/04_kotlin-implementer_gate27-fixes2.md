# 게이트 27 재판정 조치 — 파일 이름 갈래를 되돌린다 (2026-08-20)

**기준선**: `5e751f8` (계약 레인이 `x-title-policy` 를 커밋한 직후)
**판정 근거**: 2026-08-20 사용자 재판정. 직전 회차(`04_kotlin-implementer_gate27-fixes.md`
§2)가 *"계약은 생략 시 동작을 규정하지 않는다"* 는 전제를 **실측으로 반증**했고, 리더가
`contracts/easy-doc-v1.yaml:1848`·`:1850`·`:1872` 와 `migration-safety-gate` I-4 를 확인해
그 지적을 받아들였다.

사유·표·음성 대조의 정본은 계획 `04_kotlin-implementer_documents-plan.md`
**§9.2-quinquies**(신설)다. 여기에 값으로 옮겨 적지 않는다 — 이 파일은
「무엇을 어느 파일에서 고쳤는가」의 대응표와 검사 결과다.

---

## 1. 되돌린 범위 — 파일 이름이 **서비스까지 오지 않는다**

| 층 | 이전 회차가 열었던 것 | 지금 상태 |
|---|---|---|
| `core` | `resolveTitle(given, uploadFilename)` · `baseNameOf`(경로·확장자·제어문자 판정) | **`resolveTitle(given: String?)`**. `baseNameOf` **삭제** |
| `application` | `DocumentService.TitleSources(given, filename)` · `store(…, names: TitleSources, …)` | `TitleSources` **삭제**. `store(…, givenTitle: String?, …)` |
| `application` | `createFromFile` 이 `filename` 을 `store` 로 전달 | `filename` 은 `extractor.extract(filename, bytes)` 에서 **끝난다** |
| `api` | (해당 없음 — 컨트롤러 배선은 애초에 열지 않았다) | 그대로. C3 이 열 것도 없다 |

**본문 파생 갈래는 제거 상태 그대로다**(C-1). `AUTO_TITLE_TARGET_LENGTH`·`TITLE_ELLIPSIS`·
`shortenDerivedTitle` 은 직전 회차에 삭제됐고 되살리지 않았다 — 전수 grep 0건으로 확인했다.

**사용자가 준 제목은 현행 유지**다. 255 코드 포인트 자르기(거절하지 않음) · 제어문자 제거
(자르기보다 **먼저**) · 서로게이트 쌍 보존.

### 되돌리는 이유 (다음 사람이 「왜 열었다 닫았나」를 묻는다)

`TitleRules.kt` 머리말 ⑵ 가 정본이고 요지는 셋이다.

1. 계약 `:1850` — *"파일명은 제목으로 쓰지 않는다 — 파일명 자체가 개인정보일 수 있다."*
2. `migration-safety-gate` I-4(**차단 등급**) — *"파일명은 아예 저장하지 않는다."*
3. 「본문은 맡긴 내용, 파일 이름은 붙여 건넨 이름」이라는 구분은 **규정을 이기지 못하고**,
   실질 위험도 같은 종류다 — `홍길동_주민등록등본.docx` 는 이름만으로 *누가 어떤 서류를
   올렸는지*를 평문 열에 남긴다. 경로·확장자·제어문자를 떼도 남는 것이 바로 그 부분이다.

---

## 2. 고친 파일

| 파일 | 무엇을 |
|---|---|
| `core/…/document/TitleRules.kt` | 시그니처를 인자 하나로 되돌림. `baseNameOf` 삭제. 머리말에 **두 갈래가 닫힌 사유**(본문 = C-1 / 파일 이름 = 재판정)를 갈라 적음 |
| `application/…/document/DocumentService.kt` | `TitleSources` 삭제, `store` 인자 `givenTitle` 로 환원, `createFromFile` KDoc 을 *"형식 판별에만 쓰이고 버려진다"* 로 되돌림 |
| `core/src/test/…/TitleRulesTest.kt` | 파일 이름 케이스 7건 삭제. **시그니처 핀** 신설(`private val signaturePin: (String?) -> String = ::resolveTitle`) |
| `application/src/test/…/DocumentServiceTest.kt` | 제목 케이스 2건 교체 — 파일 모드에서 제목 생략 시 **대체 제목**이고 본문 조각·파일 이름 조각이 **둘 다** 없음을 단언 |
| `infrastructure/src/test/…/document/JdbcDocumentStoreTest.kt` | **`파일 이름 표식이 평문 열에 남지 않는다` 신설.** 분모·가드를 본문 표식과 공유하도록 `assertNoMarkerInDocumentRow` 로 추출. `serviceOn` 에 추출기 인자 추가 |
| `api/src/test/…/TitlePolicyContractTest.kt` (**신설**) | 계약 `x-title-policy` 를 **읽어** `FALLBACK_TITLE`·`MAX_TITLE_LENGTH`·허용 출처·금지 출처와 대조 (§4) |
| `tests/test_kotlin_gate_reach.py` | 새 클래스 등재 + `TEST_CLASS_COUNT` 88→89. `MIN_TEST_CLASSES`(85)·`FLOOR_TEST_CLASSES` 는 그대로 |
| `04_kotlin-implementer_documents-plan.md` | §9.2-quater 의 **D-o-1~D-o-3 을 취소선 처리**하고 §9.2-quinquies 신설 |

**하지 않은 것**: `contracts/easy-doc-v1.yaml`(계약 레인이 `5e751f8` 로 이미 개정) ·
`migration-safety-gate` SKILL.md(`privacy-gate` 몫) · 직전 배치의 B·C·D·E(커밋·검증 완료
상태 그대로 두었다).

---

## 3. 탐지형 회귀를 넓혔다 — 음성 대조 둘

축을 하나 더 세운 이유가 실측으로 나왔다. **두 축은 서로를 대신하지 못한다.**

| 대조 | 되살린 결함 | 결과 |
|---|---|---|
| **⒜ 본문 유도** | `store` 의 제목을 `resolveTitle(givenTitle ?: text.lineSequence().first().take(30))` 로 | **빨강** — `본문 표식이 평문 열에 남지 않는다` 가 `[title]` 지목 (22건 중 1건 실패) |
| **⒝ 파일 이름 유도** | `createFromFile` 이 `title ?: filename` 을 넘기도록 | **빨강** — `파일 이름 표식이 평문 열에 남지 않는다` 가 `[title]` 지목 (22건 중 1건 실패) |

**교차 관측**: ⒝ 에서 **본문 표식 탐지기는 초록으로 남았다.** 직전 회차가 세운 탐지기
하나만으로는 파일 이름 유입을 못 잡는다 — 새어 나간 것이 본문이 아니기 때문이다. ⒜ 에서
파일 이름 탐지기도 초록이었다. 그래서 I-4 를 **문장이 아니라 실행**으로 세우려면 축이
둘이어야 한다.

두 탐지기가 공유하는 세 가드는 그대로다 — 열 목록을 `information_schema` 에서 **파생**,
0건 거절(빈 분모는 통과가 아니다), 바닥 목록 `DOCUMENT_COLUMN_FLOOR`, 열 수 하한
`MIN_DOCUMENT_COLUMNS = 11`. 공유 함수로 묶었지만 **가드는 호출마다 돈다.**

### 복원 절차 (직전 회차의 사고를 되풀이하지 않았다)

바이트 백업 → `Path.write_bytes` 복원 → **sha256 대조**. 두 번 모두
`a3982ac9d4bfd7f0cac54deda416703142006c3517fcc377ae820d500b514029` 로 일치했다.
`git checkout --` 를 쓰지 않았다 — 미커밋 작업 위에서 그것을 쓴 것이 직전 회차의 사고였고,
규칙 5 는 이미 이 절차를 갖고 있었다(규칙이 없어서가 아니라 적용을 놓친 것이었다).

---

## 4. 계약 대조 — 빨간 곳은 **없었고**, 대신 **빈자리를 채웠다**

리더가 *"계약 대조 테스트가 개정 전 계약과 어긋나 빨개질 수 있다"* 고 예고했으나
**빨간 곳은 없었다.** 이유는 좋은 소식이 아니다 — 착수 시점 실측으로,

- 계약 파일을 읽는 테스트는 `ContractSpec` 경유 **17개 파일**,
- 그중 **`title` 관련 조항을 읽는 것은 0건**.
  `max_title_length` 는 `DocumentLimits.kt`·`TitleRules.kt`·`TitleRulesTest.kt` 에 **산문으로만**
  나오고 값은 손으로 적혀 있었다.

즉 코드와 계약이 서로 다른 것을 정본이라 주장해도 **아무것도 빨개지지 않는 상태**였고,
이번에 실제로 그 상태가 며칠 있었다.

작업 중 계약 레인이 `5e751f8` 로 `x-title-policy` 절을 커밋했고, 그 절이 값을 **기계가 읽을
수 있게** 두면서 주석으로 자리를 지정했다 — *"고정 문구. 계약 테스트는 이 값을 코드에
복제하지 말고 여기서 읽는다."* 그래서 **`api/…/TitlePolicyContractTest`** 를 세웠다
(`UploadFormatContractTest` 와 같은 형태·같은 모듈 — YAML 파서 배선을 둘로 만들지 않는다).

| 케이스 | 재는 것 |
|---|---|
| `FALLBACK_TITLE` ↔ `x-title-policy.fallback_title` | 사용자가 목록에서 보는 문구의 출처 |
| `MAX_TITLE_LENGTH` ↔ `x-input-limits.max_title_length` | 상한 값의 출처 |
| `sources` == `[given_title]` | 계약이 허용 출처를 늘리면 드러난다 |
| `forbidden_sources` == 탐지기 표의 키 집합 | **금지마다 실행 축이 있는가** |
| 없는 경로면 **실패한다** | 기대값의 출처 고정 |

넷째가 중심이다. 금지를 계약에만 적으면 **문장이지 게이트가 아니다** — 표가
`body_text` → 「본문 표식」, `upload_filename` → 「파일 이름 표식」으로 짝지어, 계약에 금지가
하나 늘면 *"그것을 재는 탐지기가 없다"* 가 빨개진다.

**N-26 형태(계약에서 원소를 빼면 검사가 줄어드는가)는 실행하지 않았다** — 계약 파일을
고쳐야 하고 이 레인은 그것을 하지 않는다(게다가 계약 레인이 같은 시각 그 파일을 쓰고
있었다). 다섯째 케이스가 기대값의 출처를 실행으로 대신 고정한다.

---

## 5. 검사 결과 (전건 실행, 종료 코드)

| 검사 | 명령 | 종료 코드 |
|---|---|---|
| Kotlin 전체 | `./gradlew build` (ktlint·detekt·단위·Testcontainers 포함) | **0** |
| 게이트 도달(로컬 모드) | `uv run pytest tests/test_kotlin_gate_reach.py` | **0** (96 passed) |
| 게이트 도달(**요구 모드**) | `KOTLIN_GATE_REACH_REQUIRE_REPORT=1 uv run pytest tests/test_kotlin_gate_reach.py` | **0** (96 passed) |
| Python lint | `uv run ruff check .` | **0** |
| Python 타입 | `uv run mypy . .claude` | **0** (139 files) |
| Python 테스트 | `uv run pytest` | **0** (1414 passed · 68 skipped · 5 deselected · 5 xfailed) |

전부 `run_gate.sh` 경유(파이프 포함, 종료 코드는 러너가 전파). `git stash` 미사용.

**미실행**: `uv run pytest tests/golden` 은 따로 돌리지 않았다 — 프롬프트·스타일 규칙·LLM
설정을 건드리지 않았고, 위 `uv run pytest` 전체 실행에 포함된다.

---

## 6. 남은 것

- **`migration-safety-gate` SKILL.md `:63`** — I-4 문장은 이제 구현과 **같은 방향**이라 개정이
  필요 없다. 다만 계약 `x-title-policy.rationale` 이 *"이 결정은 I-4 와 같은 계열이고 그
  조항을 강화한다"* 고 적었으므로, 게이트 문서에 **본문도 금지된다**는 사실을 덧붙일지는
  `privacy-gate` 판정 대상이다.
- **M-3**(`lockSourceText`/`lockEnvelope` 의 `ownerId`) — 마감 C6, `privacy-gate` 판정 대기.
- **CR-1**(원장 문언) · 표 18 TRACE(J-1) · §9 질문 ⑦ — 리더 판정 대기.
- 교차 종합 Minor 8건(행 16~23) 중 미처리분. **행 16(문자 수 단위 불일치)** 은 직전 회차가
  만진 `ExtractedTextBuilder` 와 같은 자리라 다음 회차 후보로 남는다.
- **N-26** — 계약 레인이 계약 파일을 고칠 때 함께 실행할 수 있는 형태다(계약에서 원소
  하나를 빼고 `TitlePolicyContractTest`·`UploadFormatContractTest` 가 빨개지는지).
