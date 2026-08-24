# 단계 2. 언어 독립 데이터 대피 — 실행 기록

기준: `docs/plans/2026-08-24-python-removal-for-kotlin-redevelopment.md` §4.2·§5(단계 2)

## 1. 이동한 데이터

### golden JSON 56건 + `required_facts` 253개

- 이전 경로: `tests/golden/documents/*.json`
- 새 경로: `data/golden/documents/*.json`
- 방법: `git mv tests/golden/documents data/golden/documents` (이력 보존)
- 개수 검증: 이동 전후 56개 파일, `required_facts` 총 253개로 동일 (Python `json` 모듈로 재계산)
- SHA-256 전건 대조: 이동 전/후 해시 100% 일치. 전체 목록은 `02_golden-documents-sha256.txt`
- 근거: 각 문서의 `source_text`는 실제 공공기관 안내문 또는 그 문체를 본뜬 합성 문서이고,
  `required_facts`는 그 문서가 반드시 보존해야 하는 사실(날짜·금액·기관명 등)을 사람이
  큐레이션한 것이다. Python이나 LLM이 만든 "정답 변환문"이 아니라 **입력과 검증 기준**이므로
  이동 조건 1·2(제품 요구사항 기반, Python 출력이 아님)를 만족한다.
- 소비자: 이동 시점에 Kotlin은 이 데이터를 아직 쓰지 않는다(골든셋 평가 하네스는
  `master-plan.md` 108행 각주대로 재개발 backlog). `data/golden/`이 언어 독립 최종 위치이며,
  이후 Kotlin 평가 도구가 만들어지면 그 도구가 이 경로를 직접 읽는다.

## 2. 이미 Kotlin에 있어 이동하지 않은 데이터 (이동 조건 4 — 중복 생성 금지)

다음 항목은 조사 결과 **이미 Kotlin 쪽에 존재**하며 원본(Python)과 SHA-256 또는 값이
전건 일치함을 확인했다. 중복을 만들지 않고 위치를 그대로 둔다. Python 이름이 남아 있는
두 건(`python-style-rules-snapshot.json`, `python-prompt-snapshot.json`)의 중립 명칭 변경은
**단계 3(빌드/테스트의 Python 의존 제거)**의 범위다 — 이번 단계는 데이터 존재·동일성 확인까지다.

| 데이터 | Python 원본 | Kotlin 쪽 위치 | 확인 방법 | 결과 |
|---|---|---|---|---|
| DOCX/PDF/HWPX 샘플, 위조·과대 ZIP 보안 fixture | `tests/ingest/fixtures/**` (7건) | `backend-kotlin/infrastructure/src/testFixtures/resources/fixtures/ingest/*` | SHA-256 전건 대조 | 7/7 일치 |
| 독립 style rule 데이터 13개 키(어려운말 사전 246개 포함) | `app/easyread/style_rules.py` | `backend-kotlin/core/src/test/resources/kr/easydoc/core/easyread/python-style-rules-snapshot.json` + `StyleRuleDataSnapshotTest.kt` | 키 개수(13) 확인, Kotlin 상수와의 값 대조 테스트가 이미 존재·통과 | 13/13 키 존재, 246개 사전 전건 일치(기존 테스트) |
| easy-read 변환 예시 6개(문장 나누기 2 + 어려운말 뜻풀이 3 + 괄호 풀기 1) | `app/easyread/prompts.py`의 `_SPLIT_EXAMPLES`·`_REPLACEMENT_INSTRUCTION`·`_LENGTH_INSTRUCTION` 내 하드코딩 예시 | `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/Prompts.kt`에 이미 같은 문구로 포팅됨, `python-prompt-snapshot.json` + `PromptTextSnapshotTest.kt`가 전문 대조 | 소스 코드 직접 대조 | 이미 포팅·테스트로 고정됨 |

## 3. 이동하지 않고 명시적으로 폐기하는 데이터 (완료 판정 §7 "명시적 폐기 근거"에 해당)

### `tests/golden/baseline.json`

- **결정: 보존하지 않는다. 단계 4에서 `tests/`와 함께 삭제한다.**
- 근거: 이 파일은 요구사항이나 원본 문서에서 나온 데이터가 아니라, Python 골든셋 평가
  하네스(`tests/golden/evaluation.py` 등)가 **anthropic claude-sonnet-5로 실제 LLM 호출을
  실행한 결과**(규칙 기반 통과율 35/56)를 기록한 회귀 바닥값(floor)이다. 이동 조건 2
  "Python 실행 결과만을 정답으로 삼은 값은 보존하지 않는다"에 해당한다.
- 이 바닥값과 비교할 Kotlin 평가 도구가 아직 없다(재개발 backlog). 도구 없이 수치만
  남기면 다음에 이 수치를 근거 없이 "합격선"으로 오인할 위험이 더 크다.
- 갱신 방법(`GOLDEN_RECORD_BASELINE=1`로 재실행)은 Python 하네스에 종속되어 있어 하네스
  제거와 함께 무의미해진다.

### `tests/golden/041-2026년_국민기초생활보장_사업안내.jso` (git 미추적) — 단계 4 착수 시 판단 완료

- **결정: 삭제하지 않고 `data/golden/041-2026년_국민기초생활보장_사업안내.json`으로 보존한다
  (확장자만 `.json`으로 고쳐 `documents/` 밖에 둔다 — 아래 사유).**
- 내용 비교: `id`가 `documents/041-2025년-발달장애인지원-사업안내.json`과 우연히 같은 `041`이지만
  `title`("2026년 국민기초생활보장 사업안내" vs "2025년 발달장애인지원 사업안내")·`category`
  내 주제·`source_text`·`required_facts`가 전부 다르다 — **중복이 아니라 별개 문서**다.
- 출처: `source.organization`="복지로", `source.license`="공공누리 제1유형", `synthetic: false`,
  `required_facts` 6건 큐레이션 존재 — 이동 조건 1·2(요구사항·원본 문서 근거, Python 출력 아님)를
  만족한다.
- `.gitignore`의 자체 설명(수정 전 40번째 줄 부근): 확장자가 `.json`이 아니라 Python 로더가
  무시했고, 그 결과 golden set에 편입되지 않은 채 `git add`에 두 번 실수로 걸렸던 파일이다 —
  삭제해야 할 산출물이 아니라 **편입되지 못한 원본**이다.
- **`documents/`에 넣지 않은 이유**: id `041`이 이미 다른 문서로 쓰이고 있어, 그대로 넣으면
  56개 golden set의 id 체계와 충돌한다. id 재부여·golden set 편입 여부는 Python 제거와 무관한
  제품 데이터 결정이라 이 계획의 범위 밖이다 — Kotlin 재개발 backlog에서 판단한다.
- `.gitignore`의 해당 경로 무시 규칙은 파일이 이동해 더 이상 그 경로에 없으므로 함께 제거한다.

### `docs/golden/` (76MB, git 미추적 원본 13건)

- **결정: 이번 단계에서 손대지 않는다.** 계획 §4.2 조건 6에 따라 외부 보존 위치와 hash
  manifest를 확정하기 전까지 그대로 둔다.

## 4. 검증

```bash
git mv tests/golden/documents data/golden/documents
# 이동 전후 파일 수·SHA-256 전건 일치 확인(위 표)
```

- `data/golden/documents/*.json` 56개, `required_facts` 253개 — 이동 후 재계산 일치.
- 이동으로 Kotlin 테스트의 의미를 바꾸지 않았다 — 이번 단계에서 Kotlin 소스는 건드리지 않았다.
