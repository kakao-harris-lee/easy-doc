# 하네스 비용 구조 개조 — 2차 codex 독립 리뷰

**호출 시각:** 2026-08-22 07:13-07:28 (약 15분)  
**리뷰어:** codex-reviewer (Haiku 모델)  
**리뷰 대상:** 하네스 2차 수정 (1회차 지적 5건 고침) — 5커밋, 7파일 변경  
**산출물 경로:** 정본은 이 파일. 교차 대조는 별도의 `xx_harness-fixes_cross.md` (migration-reviewer 담당)

---

## 호출 상태

| 항목 | 값 |
|---|---|
| 호출 도구 | `./.claude/skills/codex-review/scripts/codex-review.sh` |
| 모드 | `adversarial-review` (적대적 리뷰) |
| **명령** | `codex-review.sh adversarial --base cbf6e8d "1회차 지적 5건(선언한 범위보다 덜 막는 결함)의 수정이 완전한가. 네 번째 결함을 찾아라..."` |
| **결과** | **성공 (exit code 0)** |
| **대상** | branch diff `cbf6e8d..HEAD` |
| **merge-base** | `cbf6e8d3f2e6` |
| **변경 파일 수** | 7개 (.claude/skills/ 2, CLAUDE.md 1, docs/ 2, tests/ 2) |
| **헬퍼** | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| **출처** | plugins cache (최신 버전 자동 선택) |

---

## 전달한 프롬프트

```
1회차 지적 5건(선언한 범위보다 덜 막는 결함)의 수정이 완전한가. 
네 번째 결함을 찾아라. 
특히 리뷰 커버리지 강제자·7축 매핑·포인터 절 결속·클래스 정체성·장부 대기 어휘를 가장 세게 본다.
```

---

## codex 원문 리뷰

**전체 판정:** `needs-attention`

**핵심 요약 (codex 발췌):**

> NO-SHIP: 포인터 절 결속과 클래스 정체성 보강은 확인됐지만, 리뷰 커버리지와 7축 매핑에 세 가지 실제 우회가 남아 1회차 수정은 완전하지 않다.

### Finding 1 (high priority)

**문제:** 문서 전용 면제가 필수 Phase 판정 변경까지 우회시킨다  
**코드:** `tests/test_review_coverage_reach.py:169-171` (`_is_docs_only`)

**설명:**
```
_is_docs_only`는 `docs/` 아래의 모든 변경을 자동 면제한다. 따라서 즉시 중단 기준과 
Phase 종료·착수 조건을 정의하는 `docs/plans/2026-08-11-kotlin-react-migration.md`만 약화한 
커밋도 면제로 처리된다. 실제 호출 결과도 해당 계획 경로에 대해 `True`였다. 
이는 축④에 닿으면 면제 없이 즉시 리뷰한다는 선언과 정면으로 충돌하며, 
운영 중단 조건을 리뷰 없이 축소할 수 있다.
```

**Recommendation:**
```
`docs/` 전체 면제를 제거하고 실제 산출물 경로만 allowlist로 면제하라. 
최소한 계획, master-plan, 진행 원장의 판정 표와 리뷰 규약 문서는 비면제로 분류하고, 
계획의 중단 기준을 바꾼 docs-only 커밋이 미리뷰 상태에서 실패하는 변이 테스트를 추가하라.
```

### Finding 2 (high priority)

**문제:** 합쳐진 마지막 불릿이 7번째 중단 기준의 매핑 삭제를 숨긴다  
**코드:** `tests/test_harness_scope_reach.py:1386-1409`

**설명:**
```
검사는 계획의 불릿 하나가 매핑 키 중 하나라도 포함하면 전체 불릿을 매핑 완료로 본다. 
그러나 계획의 마지막 불릿에는 골든 품질 미달과 최대 2회 호출 위반이라는 독립 기준 두 개가 
함께 있다. `최대 2회 호출 요구 위반` 매핑을 삭제한 상태를 메모리에서 재현했을 때 
criteria=6, unmapped=0, stale=0으로 그대로 통과했다. 
따라서 문서당 호출 상한이 필수 리뷰 축에서 빠져도 이 강제자는 잡지 못한다.
```

**Recommendation:**
```
불릿이 아니라 원자적 중단 기준 ID를 파싱하거나, 각 불릿에 필요한 모든 키 집합이 존재하는지 
검증하라. 마지막 불릿의 두 매핑을 각각 삭제하는 음성 대조를 고정하라.
```

### Finding 3 (high priority)

**문제:** 장부 표 밖의 SHA 언급도 미리뷰 커밋을 처리된 것으로 만든다  
**코드:** `tests/test_review_coverage_reach.py:145-147` (`_recorded_shas`)

**설명:**
```
`_recorded_shas`는 이연 장부 절 전체의 백틱 SHA를 수집하며 실제 표 행, 
`상태`, `리뷰할 회차`, `닫힘`을 파싱하지 않는다. 실행 확인에서도 설명 문단의 
``deadbee``가 기록된 SHA로 반환됐다. 이후 커버리지 루프는 이 접두어만으로 커밋을 건너뛰므로, 
필수 축에 닿은 미리뷰 커밋을 산문에 한 번 언급하기만 해도 `대기` 행 없이 초록이 된다. 
선언한 `대기`/`이연` 어휘와 사후 추적성이 강제되지 않는다.
```

**Recommendation:**
```
Markdown 표를 구조적으로 파싱해 정확한 열을 가진 행의 SHA만 인정하라. 
상태는 `대기|이연`으로 제한하고, 리뷰 대상 회차를 필수화하며, 
표 밖 SHA·임의 상태·빈 회차가 실패하는 회귀 테스트를 추가하라.
```

### Next Steps (codex 권고)

```
- 세 우회 변이를 회귀 테스트로 고정한 뒤 집중 테스트를 재실행한다.
- 현재 집중 스위트는 46 passed였지만 위 변이들은 통과하므로 
  기존 초록을 출하 근거로 사용하지 않는다.
```

---

## 실행 정보

**codex 처리 과정:**
1. 범위 검사: git diff --stat, git log
2. 메모리/태스크 그룹 조회 (이전 관련 리뷰)
3. 신규 테스트 파일 전체 읽기 (test_review_coverage_reach.py, test_kotlin_class_snapshot_reach.py)
4. 변경된 SKILL.md, 계획 문서, progress.md 추출
5. 테스트 실행 시도 (exit 2, 0)
6. Python 중심 테스트로 변이 확인
7. 최종 verdict 도출

**종료:**
- Exit code: `0` (리뷰 완료)
- 번역기 판정: `needs-attention` (결함 발견)

---

**산출물 작성:** 2026-08-22 codex-reviewer  
**codex 호출:** 성공 (exit 0) — 대상 7파일, 변경 판정 명시적  
**완성도:** codex 원문 리뷰 완전 ✓ / 음성 대조 (2차에서 미실행)  
**파일:** `docs/migration/_workspace/reviews/xx_harness-fixes_codex-reviewer.md`  
**다음 단계:** `migration-reviewer` 2회차 (Claude 독립 리뷰 + 교차 종합)
