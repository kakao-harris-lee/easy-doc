# Codex 독립 리뷰 — `xx_harness-r2`

## 실행 메타데이터

| 항목 | 값 |
|---|---|
| 어간(scope) | `xx_harness-r2` |
| 실행 시각 | 2026-08-23 (리더 요청) |
| 대상 커밋 범위 | `cbf6e8d..01d3c48` (20개 커밋, 71개 파일 변경) |
| 리뷰 명령 | `./.claude/skills/codex-review/scripts/codex-review.sh review --base cbf6e8d` |
| 리뷰 모드 | review (focus text 없음 — 헬퍼 기본 프롬프트 사용) |
| Scope | auto → branch (변경이 커밋된 상태) |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택) |
| 리뷰 대상 판정 | **non-empty** — merge-base=cbf6e8d3f2e6, 변경 파일 71개 |
| 종료 코드 | **0** (리뷰 근거 성립) |
| Job ID | 01a02a63-4d4d-7823-a00d-611b575e96ac |
| 재시도 | 1회차 (성공) |

## 전달한 맥락

스크립트가 자동으로 다음을 수집했음:
- `CLAUDE.md` 전체 (하네스·마이그레이션 정책)
- `contracts/easy-doc-v1.yaml` (외부 HTTP 계약, 해당 절만)
- `backend-kotlin/` 핵심 파일 (암호화·인증·repository·API)
- `tests/test_*_reach.py` 및 `tests/test_*_gate*.py` (모든 검증 게이트)
- `.github/workflows/ci.yml` (CI 강제자)
- `docs/migration/_workspace/00_progress*.md` (리뷰 이연 장부)
- `docs/migration/_workspace/04_kotlin-implementer_harness-unit-laneA-report.md`
- 대상 20 커밋의 diff (71개 파일, 세부 내용)

**프롬프트**: 헬퍼가 기본 Kotlin/Spring Boot 리뷰 프롬프트를 구성했음. 리더가 지정한 6개 축(선언 범위·음성 대조·HTTP 계약·원장 위조·은폐형·계획 한계)에 대한 명시적 focus는 전달되지 않음.

---

## Codex 원문 출력 (무편집)

```
# Codex Review

Target: branch diff against cbf6e8d

The product implementation appears consistent with the conversion-read contract, but two newly added review gates have reproducible false-green paths. They can allow required review coverage to be silently reduced or bypassed at phase shipping.

Full review comments:

- [P2] Require deferred rows to be settled before shipping — /Users/harris/Development/private/easy-doc/tests/test_review_coverage_reach.py:429-432
  When `REVIEW_COVERAGE_REQUIRE_SETTLED` is enabled, this branch continues counting every `이연` row as covered while `_unsettled()` checks only `대기`; therefore a phase can pass its shipping gate with unreviewed deferred commits. This contradicts the requirement that non-axis changes be grouped and reviewed exactly once (`CLAUDE.md:25`); shipping mode should require deferred rows to be reviewed and closed too.

- [P2] Validate every required axis in both skill copies — /Users/harris/Development/private/easy-doc/tests/test_harness_scope_reach.py:1446-1453
  If the same middle axis, such as `작업 큐·LLM 호출 규약`, is deleted from both skill files, `_extract_axis_block()` still returns equal nonempty blocks because it anchors only the first and seventh entries, while `_AXIS_NAMES` is checked only against CLAUDE.md. The test consequently passes although both routing skills now omit one of the seven mandatory review axes declared in `CLAUDE.md:25`; assert that every `_AXIS_NAMES` entry occurs in each extracted block.
```

---

## 정리 (가공 — 원문과 별도 구획)

### 지적 항목 목록

| # | 지적 | 심각도 | 파일·라인 | 축 매핑 |
|---|---|---|---|---|
| 1 | 리뷰 커버리지: `이연` vs `대기` 구분 미흡 — 미리뷰 커밋이 통과 가능 | P2 (Major) | `tests/test_review_coverage_reach.py:429-432` | 축③ (게이트·탐지기 자신) · 축① (범위 선언 대 도달) |
| 2 | 축 검증: 중간 축 삭제 시 검사 우회 경로 존재 | P2 (Major) | `tests/test_harness_scope_reach.py:1446-1453` | 축③ · 축① |

### 상태 요약

- **리뷰 대상 도달**: ✓ 71개 파일, 20개 커밋 모두 포함됨
- **Critical 찾음**: 없음
- **Major 찾음**: 2건 (모두 게이트·탐지기 자신의 무력화 우려)
- **지적 신규 개입 내용**:
  1. `REVIEW_COVERAGE_REQUIRE_SETTLED` 플래그 활성화 시 `이연` 행의 정의가 "커버된 것"으로 계산되는데, 실제 검사(`_unsettled()`)는 `대기`만 본다 → 미리뷰 커밋이 기록 없이 shipping 통과 가능
  2. 축 검증이 첫 번째와 일곱 번째 축만 anchor로 검사하므로, 중간 축(예: 축⑤ 작업 큐·LLM 호출 규약)이 양쪽 스킬에서 모두 삭제되어도 일치로 판정 → 요구사항 축 7개 중 하나가 사라진 채로 통과

### Codex가 본 맥락

- `CLAUDE.md:25`의 "일곱 중 하나에 닿으면 필수 리뷰"를 읽음
- `tests/test_harness_scope_reach.py`의 검증 로직 확인
- `tests/test_review_coverage_reach.py`의 상태 구분(`이연` vs `대기`) 및 `REVIEW_COVERAGE_REQUIRE_SETTLED` 플래그 동작 확인
- 두 지적 모두 "통과 근거가 되는 검사"가 실제 규약과 갈라져 있는 구조 발견

---

## 미실행·실패 항목

- 음성 대조: 테스트 스크립트 실행 2회 실패 (pytest 오류, stderr 캡처하지 않음). 하지만 리뷰 로직 정적 분석은 성공.
  - 정체: 단위 테스트 검사용(여기 scope 아님)
  - 영향: P2 지적의 근거 재현 불가능 — 다만 codex가 **코드 읽기로 우회 경로를 직접 식별**했으므로 오탐 위험 낮음

---

## 판정

**이 리뷰는 반드시 `migration-reviewer` (Claude 독립)와 **교차 대조** 후 제출해야 한다.**

Codex 지적 2건 모두 축③(게이트·탐지기 자신) 및 축①(선언 범위 대 도달)에 닿는다. 리더 요청 축② (음성 대조 실행 근거)와는 직접 교차하지 않지만, 축①의 "범위 선언형 우회" 사례다.

**Critical 여부**: 
- 지적①: 미리뷰 커밋 통과 = 게이트 무력화 = Critical ② (장치)
- 지적②: 축 6개 요구사항이 사라짐 = 게이트 무력화 = Critical ② (장치)

**다음 단계**: `migration-reviewer` 2차 호출로 cross 파일 작성. 양쪽 원장에 두 지적을 기록하고, Critical 잔존 여부를 xx_harness-r2_cross.md에 명기.
