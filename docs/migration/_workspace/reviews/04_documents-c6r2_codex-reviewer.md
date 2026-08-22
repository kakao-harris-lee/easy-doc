# 04_documents-c6r2: Codex 독립 리뷰

## 호출 메타데이터

| 항목 | 값 |
|---|---|
| **실행 시각** | 2026-08-22 00:00 (배경 작업 완료) |
| **대상 범위** | `git diff 318bd36..b4646ee` (커밋 7개, 변경 파일 29개) |
| **리뷰 모드** | adversarial-review |
| **--base** | 318bd36 (merge-base=318bd363e077) |
| **Codex 헬퍼** | 1.0.6 (plugins cache 자동 최신 선택) |
| **종료 코드** | 0 (유효한 리뷰) |
| **Job ID** | buwgg6ngf |

## 전달한 프롬프트

### 1. 배경
이 변경은 Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 마이그레이션의 Phase 4(문서 조회·변환 응답) 회차입니다. 제품 동작과 계약, 개인정보 보호 정책을 보존해야 합니다.

### 2. 지켜야 하는 조건

**계약 스펙 (contracts/easy-doc-v1.yaml)**:
- ConversionResponse의 `required` 열 13개 필드는 응답이 `null` 또는 `[]`인 경우에도 그 키가 직렬화되지 않아야 한다 (Jackson 설정과 모든 경로에서)
- 상태 코드: 가입 201 / 업로드 202 / 삭제 204 / 입력 오류 422 / 충돌 409 / 인증 401 / 소유권 404
- `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` 모든 응답

**계획 §4.2 동등 포팅**:
- 완료 전 응답은 결과 필드를 비워야 한다 (ConversionResponse 9개 필드 셋 → 아홉)
- 잘못된 값이 노출되는 우회 경로가 없어야 한다

**게이트·탐지기 자신**:
- `.claude/skills/kotlin-migration/SKILL.md` 리뷰 게이트 정본 표는 Phase 4 scope를 `documents`만 허용
- 표에 없는 값을 쓰지 않는 규칙

### 3. 대상 파일 및 테스트

**Kotlin 구현**:
- `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/ConversionQueryService.kt` (beforeDone 집행)
- `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/document/ConversionView.kt` (carriesResult 정의)
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/PrivateHeaderFloorCensusTest.kt` (K-1 하한선)
- `tests/test_kotlin_gate_reach.py` (라쳇·바닥·단언 하한)
- `tests/test_review_coverage_reach.py` (판정 표 강제자)

**대응 계약**:
- `contracts/easy-doc-v1.yaml` ConversionResponse 스키마

### 4. 리뷰 축 5개

**1. ConversionResponse 완료 전 노출 강제자의 실제 효과**
- 규칙 정의: ConversionStatus.exposesResult
- 단일 정의: ConversionView.carriesResult
- 집행: ConversionQueryService.beforeDone
- 되짚기: 매퍼 require
- 우회 경로(규칙 회피)와 잘못된 값을 넣는 경로가 있는가

**2. 계약 "required" 열셋을 null·[] 로 유지하는 보장**
- Jackson 설정에서 null 필드·빈 배열 직렬화 설정 여부
- 모든 직렬화 경로(응답 매퍼, 조회 쿼리, 매핑 로직)에서 이 규칙이 실제로 도달하는가
- 빈 선언에서 초록으로 끝나는 자리가 있는가

**3. 새 하한선 인구조사 테스트(K-1)와 라쳇·바닥·단언 하한 도달**
- 선언한 범위: Phase 4, documents 만
- 실제 사용: 04_documents-c6·04_documents-c6r2 (표에는 이 회차 접미가 없음)
- 그 표의 강제자가 실제로 도달하고 있는가
- 빈 선언에서 초록이 되는 자리가 있는가

**4. 회차 어간 규약 드리프트**
- .claude/skills/kotlin-migration/SKILL.md 의 정본 표: Phase 4 = documents 만
- 실제 사용: 04_documents-c6·04_documents-c6r2·xx_harness-fixes 등 접미 붙음
- 드리프트를 닫는 방법

**5. 원장의 판정 표 자신이 입력 위조를 막는가**
- 그 표를 읽는 강제자가 자기 입력 위조를 방지하는가
- 아카이브 이동 시 EXPECTED_TARGET_TABLES 검사가 빠진 표를 감지하는가

## Codex 원문 리뷰 결과

**Verdict: needs-attention**

**NO-SHIP.** ConversionResponse의 현재 제품 경로는 완료 전 9개 결과 필드를 비우고 13개 required 키를 null·[]로 직렬화하지만, K-1의 분모와 리뷰 원장 강제자는 같은 변경 단위에서 축소·위조할 수 있다.

### Findings

#### 1. [High] K-1은 고위험 10곳의 정체성을 독립적으로 고정하지 않는다
**파일·라인**: `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/PrivateHeaderFloorCensusTest.kt:54-57`

**지적**: 인구조사의 분모가 변경 가능한 계약 `applies_to` 자체이고 검사는 `implementedTargets()`가 비어 있지 않은지만 확인한다. 현재 목록은 10건이지만, 계약에서 documents/conversion/workspaces의 구현 대상 5건을 삭제하면 검사는 남은 auth 3건만 실행하고 계속 통과할 수 있다. 별도 테스트가 auth 3건만 고정하고 있어 '좁힐 수 없는 10곳 하한선'의 절반이 자동 신호 없이 사라질 수 있다. 그 결과 no-store/nosniff 회귀가 개인정보 응답에서 검출되지 않는다.

**권고**: 독립된 정확한 10개 대상 정체성 집합을 고정하고 계약 집합과 양방향 일치를 단언하라. 개수만 고정하면 동일 개수 치환이 가능하므로 메서드+경로 집합을 고정하고, 항목 삭제·치환 변이를 추가하라.

#### 2. [High] 기존 리뷰 파일로 미래 SHA 범위를 '리뷰됨'으로 위조할 수 있다
**파일·라인**: `tests/test_review_coverage_reach.py:175-185`

**지적**: 커버리지 파서는 표의 산출물 칸을 읽지 않고 회차 stem으로 `reviews/<stem>_*.md`가 2개 이상인지만 확인한다. 산출물 내용의 base/end SHA와 표 범위도 결속하지 않는다. 실제로 기존 `04_documents-c6_*` 파일을 둔 채 산출물 칸을 존재하지 않는 `없는-증거.md`, 범위를 `318bd36..b4646ee`로 만든 합성 행이 `_coverage_ranges()`에서 그대로 승인됐다. 따라서 과거 산출물을 재사용해 리뷰 뒤의 커밋까지 승인할 수 있다.

**권고**: 정확히 codex-reviewer, migration-reviewer, cross 3개를 요구하고 각 산출물 또는 별도 서명 manifest에 동일한 canonical review ID, start SHA, end SHA, diff digest를 기록해 표와 대조하라. 표의 산출물 칸도 실제 경로로 파싱하고 범위 불일치·오래된 산출물 재사용 변이를 실패시켜라.

#### 3. [High] 필수 리뷰가 '대기'인 커밋도 출하 게이트에서 완료된 것처럼 통과한다
**파일·라인**: `tests/test_review_coverage_reach.py:207-256`

**지적**: 장부 파서는 `대기`와 `이연`을 모두 유효 상태로 반환하고, 주 검사는 해당 SHA를 즉시 계상한다. 현재 원장에는 b4c2fda, 6a9ca8b, 6c970b6, 4ac13ec가 보안·HTTP 계약·게이트 필수 축에 닿았다고 명시하면서도 모두 `대기`다. 따라서 리뷰를 아직 받지 않았다는 원장 자체의 판정과 무관하게 테스트는 green이 될 수 있다. 이는 회계 검사는 될 수 있어도 ship gate는 아니다.

**권고**: 보호 브랜치/출하 모드에서는 `대기` 행이 하나라도 있으면 실패시키고, `이연`만 임시 계상하라. `대기`를 닫으려면 검증된 커버리지 범위와 canonical review ID가 `닫힘` 칸에 결속돼야 하며 해당 SHA가 실제 리뷰 범위에 포함됨을 확인해야 한다.

#### 4. [Medium] 회차 접미사가 유일한 scope 정본을 이미 위반한다
**파일·라인**: `docs/migration/_workspace/00_progress.md:1587-1588`

**지적**: 현재 커버리지 표는 `04_documents-c6`를 사용하지만 SKILL 정본은 Phase 4 scope를 `documents`로만 허용하고 표에 없는 값을 금지한다. 저장소에는 `04_documents-c3`, `04_documents-c4c5`, `xx_harness-fixes`도 존재한다. scope와 회차가 한 문자열에 섞였고 이를 정본 표와 대조하는 강제자가 없어 파일 탐색·교차 종합·커버리지 증거 재사용의 의미가 계속 드리프트한다.

**권고**: scope를 `documents`/`harness`로 유지하고 별도 `round` 필드를 도입하라. 예를 들어 canonical review ID를 `04_documents--r06`처럼 명시적 문법으로 정의하고, 세 산출물·원장·강제자가 phase, scope, round를 각각 파싱해 정확히 일치시키는 방식을 권장한다. 접미사를 scope 표에 매번 추가하는 방식은 도메인 정본을 회차 목록으로 변질시키므로 피하라.

## 정리 (없음)

codex 원문이 이미 명확하므로 별도 정리는 불필요합니다. 4개 지적이 모두 하네스·게이트·원장 강제자를 대상으로 하고 있으며, Kotlin 제품 코드 자체는 문제가 없다는 판정입니다.

## 미실행·실패 항목

- Python 환경에서 test_review_coverage_reach 자체 실행 시도 중 1건 실패 (exit 1) — 그러나 static 분석(rg, sed로 코드 읽기)은 모두 성공. 지적에 영향 없음.
- RequestFieldConstraintTest.kt 파일 탐색 실패 (exit 1) — 파일이 없거나 경로 변경. 그러나 Jackson 직렬화 경로는 다른 경로들로 검증됨.

---

**결론**: NO-SHIP. 하네스 강제자 3건(K-1 분모 위조, 산출물 위조, 대기 상태 통과)과 정본 표 규약 위반(회차 접미사)으로 리뷰 게이트 자체가 무력화될 수 있습니다. Kotlin 제품 코드는 계약을 만족하나, 이 회차의 메타 변경(테스트·원장·강제자)이 향후 회귀를 감지하지 못하게 할 위험이 있습니다.
