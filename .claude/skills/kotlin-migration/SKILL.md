---
name: kotlin-migration
description: "Easy-Read AI의 Python/FastAPI → Kotlin/Spring Boot 마이그레이션을 여러 전문 에이전트로 나눠 조율하는 오케스트레이터. 코틀린 전환·Kotlin 포팅뿐 아니라 '자바로 전환', 'JVM 이관', 'Spring 이관', '백엔드 언어 교체', '백엔드 런타임 교체', '백엔드를 자바 진영으로' 같은 우회 표현, 그리고 '전환 어디까지 됐지', '다음에 뭘 해야 하지', 'Phase N 종료해도 되나', 절체(cutover) 조율 요청에 사용한다. 처음 착수할 때뿐 아니라 '이어서 진행', '다시 실행', '재검증', '업데이트', '보완', '이전 결과 기반으로', 'Phase N만 다시', '리뷰 지적 반영' 같은 후속 요청에도 사용한다. 단일 모듈·단일 도메인 작업은 해당 스킬을 직접 쓴다 — Kotlin 코드 작성·Gradle·Flyway 스크립트는 kotlin-spring-conventions, fixture 생성과 동등성 검증은 python-kotlin-parity, 계약 조항 확인은 api-contract-freeze, 보안·개인정보 감사는 migration-safety-gate, 독립 리뷰 수행은 codex-review. 이 스킬은 여러 에이전트를 조율해야 하거나 Phase 경계를 판정해야 할 때만 쓴다. 단일 파일 조회나 단순 질문은 이 스킬 없이 답해도 된다."
---

# Kotlin 마이그레이션 오케스트레이터

Easy-Read AI의 Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 작업을 6개 전문 에이전트로 나눠 조율한다.

**기준 문서:** `docs/plans/2026-08-11-kotlin-react-migration.md` (이하 "계획 문서"). Phase 정의·종료 조건·검증 매트릭스·즉시 중단 기준은 모두 이 문서를 따른다. 계획 문서와 실제 코드가 어긋나면 **코드를 기준으로 삼고 차이를 사용자에게 보고**한다 — 계획은 2026-08-11 시점 스냅샷이고 코드는 계속 움직이기 때문이다.

## Phase 0: 컨텍스트 확인 (모든 실행의 시작)

작업을 시작하기 전에 `docs/migration/_workspace/`를 확인해 실행 모드를 판별한다. 이 판별을 건너뛰면 이미 끝난 Phase를 다시 돌려 시간과 비용을 낭비하거나, 이전 산출물을 덮어써 근거를 잃는다.

| 상태 | 판별 | 행동 |
|---|---|---|
| `_workspace/` 없음 | 초기 실행 | 계획 문서 Phase 0부터 시작 |
| `_workspace/` 있음 + 부분 수정 요청 | 부분 재실행 | 해당 에이전트만 재호출, 나머지 산출물 보존 |
| `_workspace/` 있음 + 새 입력·범위 변경 | 새 실행 | 기존 `_workspace/`를 `_workspace_prev/`로 옮기고 새로 시작 |
| `_workspace/` 있음 + "이어서" | 이어하기 | 마지막 완료 Phase를 진행 로그에서 읽고 다음 Phase부터 |

진행 상태는 `docs/migration/_workspace/00_progress.md`에 기록한다. **이 파일이 리더와 6개 에이전트가 공유하는 유일한 진실**이다. 스키마와 갱신 규칙은 바로 아래 "작업 추적" 절에 있다.

## 실행 모드: 하이브리드

이 환경에 `TeamCreate`는 없다. 실제 가용 도구로 팀 협업 효과를 구성한다.

- **진행 추적(리더 전용)**: `TaskCreate` / `TaskList` / `TaskUpdate`는 메인 세션에만 있고 **서브에이전트로 실행되는 6개 에이전트에는 보이지 않는다.** 따라서 "공유" 작업 목록이 되지 못한다 — 리더만 읽고 쓸 수 있고 에이전트는 갱신할 수 없다. 리더가 자기 진행을 추적하는 보조 수단으로만 쓰고, **에이전트에게 이 도구 사용을 지시하지 않는다.** `addBlockedBy`로 표현한 의존성도 에이전트에게는 보이지 않으므로 강제력이 없다.
- **공유 상태**: `docs/migration/_workspace/00_progress.md` 파일. 리더와 에이전트가 함께 읽고 쓰는 단일 진실이며, 아래 스키마를 따른다. Phase 종료 판정과 다음 Phase 착수 가부는 **이 파일만 근거로 삼는다.**
- **병렬 실행**: `Agent` 도구 + `run_in_background: true`. 모델은 각 에이전트 정의 frontmatter의 `model: opus`가 **기본값**이고, `Agent` 도구의 `model` 파라미터는 **그 호출에 한해** 기본값을 재정의한다. 원칙은 기본값을 그대로 두는 것이다 — 마이그레이션 판단은 추론 품질에 직결된다. 특정 호출만 낮추려면 그 호출에만 파라미터를 주고 이유를 `00_progress.md`에 남긴다.
- **에이전트 간 통신**: `SendMessage`. 실행 중인 에이전트에게 추가 맥락이나 다른 에이전트의 발견을 전달할 때 쓴다.
- **산출물 전달**: 파일 기반. `docs/migration/_workspace/{phase}_{agent}_{artifact}.{ext}`

### 작업 추적: `00_progress.md` 스키마 (필수)

Phase마다 표를 하나씩 둔다. 계획 문서 §5의 해당 Phase 종료 조건을 **항목당 한 행**으로 옮겨 적는다. 종료 조건을 한 문장으로 요약하면 "대체로 됐다"로 넘어가므로 행으로 쪼갠다.

```markdown
## Phase 2 — 순수 도메인 로직 포팅

| 종료 조건 | 충족 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---|---|---|---|---|
| parity suite가 양쪽에서 같은 결과 | 예 | `_workspace/02_parity-verifier_report.md` | - | - | parity-verifier |
| 마스킹 fixture 전건 일치 | 아니오 | 3건 불일치 | 유니코드 결합 문자 3건 | kotlin-implementer | parity-verifier |
| 리뷰 게이트 Critical 0건 | 아니오 | `reviews/02_domain_cross.md` | Critical 1건 | kotlin-implementer | migration-reviewer |
```

컬럼 규칙:

- **충족**: `예` / `아니오` 둘 중 하나만 쓴다. `진행 중`·`대체로`는 금지 — 게이트는 이분법이어야 판정된다.
- **근거**: 파일 경로 또는 명령 출력 위치. 근거가 비어 있는 `예`는 `아니오`로 취급한다.
- **미해결 항목**: 이 행이 `아니오`인 구체적 이유. 개수와 종류를 적는다.
- **blocked-by**: 이 행을 닫으려면 먼저 끝나야 하는 에이전트 또는 Phase.
- **마지막 갱신 주체**: 이 행을 마지막으로 고친 에이전트 이름(또는 `leader`). 누가 무엇을 근거로 닫았는지 남아야 나중에 되짚을 수 있다.

표 아래에 검증 매트릭스(§6, 이 문서 "검증 매트릭스" 절) 중 아직 돌리지 않은 게이트 목록을 적는다.

**갱신 규칙**: 에이전트는 작업을 마칠 때 자기가 담당한 행만 고치고 `마지막 갱신 주체`에 자기 이름을 적는다. 다른 에이전트의 행은 건드리지 않는다 — 같은 파일을 동시에 고치면 마지막 쓰기가 이겨 남의 결과를 지운다.

**Phase 의존성 강제**: **이전 Phase의 종료 조건 행이 전부 `충족 = 예`가 아니면 다음 Phase 에이전트를 호출하지 않는다.** 도구가 이 의존성을 강제해 주지 않으므로(`addBlockedBy`는 에이전트에게 보이지 않는다) 리더가 매 Phase 호출 직전에 `00_progress.md`를 읽어 직접 확인한다. 미충족인데 진행해야 할 사정이 있으면 사용자 승인을 받고, 승인 사실과 미충족 행을 `00_progress.md`에 남긴다.

Phase마다 모드가 다르다:

| 구간 | 모드 | 이유 |
|---|---|---|
| 구현·검증 (contract-keeper, kotlin-implementer, parity-verifier, privacy-gate) | 병렬 + 상호 참조 | 발견을 공유해야 재작업이 준다 |
| 리뷰 게이트 (codex-reviewer, migration-reviewer) | **독립 실행** | 두 리뷰어가 서로의 결론을 보면 독립성이 사라져 교차 검증의 의미가 없어진다 |
| 교차 종합 | 단일 (`migration-reviewer` **재호출**) | 두 리뷰 산출물이 파일로 모두 나온 뒤에만 대조한다 |

리뷰어를 격리하는 것이 이 하네스의 핵심 설계다. codex 리뷰의 가치는 Claude와 다른 맹점을 갖는다는 데 있으므로, 리뷰 시작 전에 Claude 측 결론을 codex에 주입하면 안 된다.

## 팀 구성

| 에이전트 | 책임 | 스킬 |
|---|---|---|
| `contract-keeper` | 외부 계약 동결, contract test, OpenAPI→React 타입 | `api-contract-freeze` |
| `kotlin-implementer` | Kotlin/Spring 구현 (유일한 Kotlin 코드 작성자) | `kotlin-spring-conventions` |
| `parity-verifier` | Python↔Kotlin 동등성 증명 | `python-kotlin-parity` |
| `privacy-gate` | 보안·개인정보 불변식 감사 | `migration-safety-gate` |
| `codex-reviewer` | codex CLI 독립 리뷰 (필수 게이트) | `codex-review` |
| `migration-reviewer` | Claude 교차 리뷰 + 두 리뷰 종합 | `codex-review`, `migration-safety-gate` |

Kotlin 코드를 쓰는 에이전트는 `kotlin-implementer` 하나다. 여러 에이전트가 같은 모듈을 동시에 고치면 충돌하고, 누가 무엇을 바꿨는지 추적할 수 없다.

## Phase별 워크플로우

계획 문서의 Phase를 그대로 따르되, 각 Phase에 담당 에이전트와 게이트를 붙인다.

### Phase 0 — 범위·계약 동결
1. `contract-keeper`: `contracts/easy-doc-v1.yaml` 작성, FastAPI OpenAPI·React 타입 3자 대조
2. `privacy-gate`: Fernet·Argon2·JWT 호환 spike 요건 정리
3. `kotlin-implementer`: 문서 라이브러리(POI·PDFBox·HWPX) spike

**종료 조건**: Kotlin이 기존 암호문을 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨. 확인되지 않으면 일정 산정부터 다시 한다 — 여기서 실패한 채 진행하면 Phase 4에서 전체가 막힌다.

**사용자 승인 필요**: 계획 문서 §9의 다섯 결정(런타임만 Kotlin화 vs Python 완전 제거 / 대상 DB와 유지보수 창 / Fernet JVM 구현 승인 / Redis 최종 제거 / UI 개편 분리)을 Phase 0에서 고정한다. 승인 없이 Phase 1로 넘어가지 않는다.

### Phase 1 — Kotlin 골격과 CI
`kotlin-implementer` 주도. Gradle 멀티모듈, toolchain, dependency locking, ktlint/detekt, `/health`, Testcontainers, Flyway baseline, CI에 Kotlin gate 추가(기존 Python/React gate 유지).

### Phase 2 — 순수 도메인 로직 포팅
`kotlin-implementer`(포팅) ↔ `parity-verifier`(검증) 반복. 마스킹, 텍스트 정규화, 프롬프트 렌더링, 스타일 규칙, 보정 채택, placeholder 보존, 내보내기 파일명.

**종료 조건**: 외부 API·DB 없이 도는 parity suite가 양쪽에서 같은 결과를 낸다.

### Phase 3 — 데이터·인증·작업 공간 API
`kotlin-implementer` + `contract-keeper`(계약 준수) + `privacy-gate`(소유권 404, Argon2/JWT).

### Phase 4 — 문서 API·암호화·내보내기
`kotlin-implementer` + `parity-verifier`(문서 fixture 교차 비교) + `privacy-gate`(암호 호환, 평문 미노출).

**종료 조건**: 실제 PostgreSQL에서 업로드 → 조회 → 검수 → 3형식 다운로드 → 삭제가 통과하고 평문이 DB·로그에 없다.

### Phase 5 — LLM provider·worker·보존 파기
`kotlin-implementer` + `parity-verifier`(고정 응답 fixture로 호출 횟수·채택 결과 비교) + `privacy-gate`(마스킹 선행, 최대 2회 호출 계약).

### Phase 6 — React 통합·접근성·전체 E2E
`contract-keeper` 주도. OpenAPI 생성 타입 교체, polling·세션 만료·미저장 경고 검증, 키보드·focus·aria-live·색 대비, nginx `/api` 프록시.

### Phase 7 — 절체·관찰·롤백
전원 참여. 계획 문서 §5 Phase 7의 9단계 절차와 즉시 중단 기준을 그대로 집행한다. **이 Phase는 사용자 승인 없이 실행하지 않는다** — 운영 데이터와 되돌리기 어려운 상태 변경이 걸려 있다.

### Phase 8 — Python 런타임 제거
관찰 기간 종료 후에만. `app/`, ARQ, FastAPI, SQLAlchemy, Alembic 제거와 문서 동기화.

### Phase 9 — 오프라인 도구 Kotlin 전환 (선택)
런타임 밖 도구를 옮기는 단계: `scripts/benchmark.py`, `scripts/collect_*`, `scripts/pilot_report.py`, `app/easyread/goldenset.py`, `judge.py`, `collection.py`, `bokjiro.py`와 관련 fixture·CLI·리포트 형식.

**착수 조건**: 이 Python 골든 도구들은 Kotlin 구현을 채점하는 **독립 검증 oracle**이다 (계획 문서 §5 Phase 9). 옮기는 순간 "Kotlin이 Kotlin을 채점하는" 구조가 되어 교차 검증이 사라진다. 따라서 **리더와 사용자의 명시 승인 없이 착수하지 않는다.** 승인 없이 이 Phase 요청을 받으면 위 이유를 설명하고 승인을 요청한다. 계획 문서도 Kotlin 런타임이 안정되고 동일 결과가 확인된 뒤에 제거하라고 명시한다.

## 리뷰 게이트 (필수)

Kotlin 코드 변경이 한 덩어리 끝날 때마다 실행한다. Phase 종료 시점만 기다리지 않는다 — 늦게 발견한 parity 사고는 그 위에 쌓은 코드까지 되돌려야 한다.

게이트는 **3단계**다. 병렬 호출 하나로는 닫히지 않는다. `migration-reviewer`는 한 게이트에서 **2회 호출**된다 — 1회차는 독립 리뷰, 2회차는 교차 종합이다.

```
변경 완료
  │
  │ [1단계] 병렬·독립 실행 — 같은 메시지에서 동시 호출, run_in_background
  ├─ codex-reviewer                  → reviews/{phase}_{scope}_codex-reviewer.md
  └─ migration-reviewer  (1회차)     → reviews/{phase}_{scope}_migration-reviewer.md
  │
  │ [2단계] 두 산출물이 실제 파일로 존재하는지 확인
  ↓
  │ [3단계] migration-reviewer 재호출 (2회차) — 교차 종합 전용
  └─ 입력: 위 두 파일 경로          → reviews/{phase}_{scope}_cross.md
  ↓
상충 → 양쪽 근거 병기해 사용자에게 판단 요청
합치 → 심각도 순으로 조치 목록 제시
```

**1단계 — 병렬·독립.** 두 리뷰어를 같은 메시지에서 동시에 띄운다. 어느 쪽에도 상대의 결론을 주지 않는다.

**2단계 — 완료 확인.** 두 산출물 파일이 모두 존재하는지 확인한 뒤에만 3단계로 넘어간다. codex 쪽이 실패했으면 아래 "codex 실패 시"를 따른다.

**3단계 — 교차 종합.** `migration-reviewer`를 **다시 호출**한다. 1회차와 별개의 호출이며, 입력은 두 산출물의 경로다. **이 2차 호출에서는 새 지적을 만들지 않고 대조만 한다** — 2차 호출에서 새로 나온 지적은 교차 검증을 받지 않은 채 정본에 실리기 때문이다. 새로 발견한 것이 있으면 별건으로 다음 게이트에 올린다.

1회차와 3단계를 한 번의 호출로 합치지 않는다. 합치면 `migration-reviewer`가 시작 시점에 codex 산출물을 찾지 못해 매번 "codex 리뷰 없음"으로 종결된다 — 게이트가 형식만 남는다.

**산출물 경로**: `docs/migration/_workspace/reviews/{phase}_{scope}_{reviewer}.md`
`{reviewer}` = `codex-reviewer` | `migration-reviewer` | `privacy-gate` | `cross` — 작성한 에이전트 이름, 또는 교차 종합본이면 `cross`. 파일명만 보고 작성 주체를 알 수 있어야 한다.
예: `04_crypto_codex-reviewer.md`, `04_crypto_migration-reviewer.md`, `04_crypto_cross.md`

**게이트 면제**: 문서·주석만 수정, 스킬/에이전트 정의 수정, 포맷터 결과 반영. 이 외에는 면제하지 않는다. 면제 기준을 넓히면 게이트가 형식화된다.

**codex 실패 시**: 1회 재시도 후 재실패하면 codex 결과 없이 진행하되 종합 리포트에 **"codex 리뷰 누락"을 명시**한다. 조용히 통과시키면 필수 게이트가 무의미해진다.

## 데이터 전달 프로토콜

- **작업 상태**: `docs/migration/_workspace/00_progress.md` (스키마는 "실행 모드 → 작업 추적" 절). 에이전트와 리더가 공유하는 유일한 진실이고, Phase 의존성도 여기서 판정한다. 리더는 `TaskCreate`/`TaskUpdate`로 자기 진행을 따로 추적해도 되지만 그 상태는 에이전트에게 보이지 않으므로 **판정 근거로 쓰지 않는다.**
- **산출물**: `docs/migration/_workspace/{phase}_{agent}_{artifact}.{ext}` (예: `01_contract-keeper_endpoint-matrix.md`)
- **실시간 전달**: `SendMessage`. 예 — `parity-verifier`가 불일치를 발견하면 `kotlin-implementer`에게 즉시 보낸다.
- **중간 파일은 지우지 않는다.** 절체 후 문제가 생겼을 때 어느 단계에서 갈라졌는지 추적할 근거가 된다.

고정 경로:

| 대상 | 경로 |
|---|---|
| Kotlin 루트 | `backend-kotlin/{core,application,infrastructure,api,worker}` |
| API 계약 | `contracts/easy-doc-v1.yaml` |
| parity fixture | `parity/fixtures/{도메인}/*.json` |
| 중간 산출물 | `docs/migration/_workspace/` |
| 리뷰 | `docs/migration/_workspace/reviews/` |
| 기존 Python | `app/`, 기존 테스트 `tests/`, 프런트 `frontend/src/` |

## 에러 핸들링

| 상황 | 행동 |
|---|---|
| 에이전트 실패 | 1회 재시도. 재실패 시 그 결과 없이 진행하되 `00_progress.md`와 최종 보고에 **누락을 명시**한다 |
| 두 에이전트의 결론 상충 | 어느 쪽도 삭제하지 않는다. 양쪽 근거와 출처를 병기해 사용자에게 판단을 넘긴다 |
| parity 불일치 발견 | `kotlin-implementer`에게 최소 재현 입력과 함께 반환. 정규화로 덮지 않는다 |
| 보안 불변식 위반 | `privacy-gate`가 즉시 차단 통보. 다른 작업보다 우선 처리한다 |
| Phase 종료 조건 미충족 | 다음 Phase로 넘어가지 않는다. 미충족 항목을 사용자에게 보고하고 결정을 받는다 |
| 계획 문서와 코드 불일치 | 코드를 기준으로 진행하고 차이를 보고한다. 계획 문서 갱신을 제안한다 |

## 검증 매트릭스

계획 문서 §6을 게이트로 쓴다: Build / Unit / Contract / DB / Crypto / Document / Worker / Quality / Security / E2E / Ops. 어느 게이트를 아직 안 돌렸는지 `00_progress.md`에 남긴다. **돌리지 않은 게이트를 통과한 것처럼 보고하지 않는다.**

Python 테스트 878개를 줄 단위로 번역하는 것이 목표가 아니다. 각 테스트가 보장하던 행동을 계약·도메인·통합·E2E 계층에 재배치하고, 누락된 보장 목록이 0인지 추적표로 관리한다.

## 테스트 시나리오

**정상 흐름 — Phase 2 도메인 포팅**
1. 사용자: "마스킹 로직을 Kotlin으로 포팅해줘"
2. Phase 0 컨텍스트 확인 → `_workspace/` 없음 → 초기 실행
3. `parity-verifier`가 `app/privacy/masking.py`에서 `parity/fixtures/masking/*.json` 생성
4. `kotlin-implementer`가 `core` 모듈에 포팅, 같은 fixture를 읽는 Kotlin 테스트 작성
5. `parity-verifier`가 양쪽 실행 결과 비교 → 불일치 시 최소 재현 입력과 함께 반환
6. 일치 확인 후 `codex-reviewer`·`migration-reviewer` 병렬 독립 리뷰 (1단계)
7. 두 산출물 파일 존재 확인 (2단계) → `migration-reviewer` 재호출로 `02_domain_cross.md` 작성 (3단계)
8. `privacy-gate`가 마스킹 선행 불변식 확인
9. `00_progress.md`의 Phase 2 종료 조건 행을 담당 에이전트가 갱신

**에러 흐름 — codex 미인증**
1. 리뷰 게이트에서 `codex-reviewer`가 인증 오류로 실패
2. 1회 재시도 → 재실패
3. 2단계에서 codex 산출물 부재를 확인 → `migration-reviewer`를 재호출해 자기 1회차 리뷰만으로 `_cross.md`를 쓰되, 리포트 상단에 "codex 리뷰 누락 — 인증 필요"를 명시
4. 사용자에게 `codex` CLI 인증 방법을 안내하고, 인증 후 재리뷰를 제안한다
5. 누락 상태로 Phase 종료 조건을 충족했다고 보고하지 않는다

## 하네스 진화

실행이 끝나면 사용자에게 개선점을 묻는다. 피드백 유형별 반영 위치:

| 피드백 | 수정 대상 |
|---|---|
| 산출물 품질 | 해당 에이전트의 스킬 |
| 역할 경계 | 에이전트 정의 `.md` |
| Phase 순서·게이트 강도 | 이 오케스트레이터 스킬 |
| 트리거 누락 | 스킬 `description` |

모든 변경은 프로젝트 `CLAUDE.md`의 하네스 변경 이력 테이블에 기록한다.
