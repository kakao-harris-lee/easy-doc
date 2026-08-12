# 재개발 전환 계획 — Codex 독립 계획 심사

> 이 문서는 **심사 기록**이다. 계획을 개정하지 않았고 개정안도 제시하지 않는다.
> Codex 출력은 가공·요약·발췌 없이 원문 그대로 싣는다.

## 판정

**verdict: `needs-attention`**

## 호출 메타데이터

| 항목 | 값 |
|------|-----|
| 실행 방식 | `adversarial-review --background --scope working-tree` (모드 A) |
| companion | `~/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| job id | `review-mspugl6y-k5xgtc` |
| thread id | `019ff525-f774-7951-a3a2-43aec5c1313b` |
| 시작 | 2026-08-12T08:45:32.432Z |
| 종료 | 2026-08-12T08:53:19.725Z |
| 소요 | 7m 47s |
| 종료 코드 | `0` (stderr 비어 있음) |
| write 모드 | `False` (read-only — 심판은 수정하지 않는다) |
| 대상 | {"mode": "working-tree", "label": "working tree diff", "explicit": true} |
| 리뷰 범위 | Reviewing 0 staged, 9 unstaged, and 76 untracked file(s). |
| 브랜치 | feat/kotlin-migration-harness |
| parseError | `None` |

### 심사 대상 리비전 (결속)

| 필드 | 값 |
|------|-----|
| `reviewed_at_head` | `96c1d7ea368125c8540947db3e2f809f28a5b537` |
| `reviewed_plan_paths` | `docs/plans/2026-08-11-kotlin-react-migration.md`, `docs/migration/_workspace/03_rebuild-extraction-list.md`, `docs/migration/_workspace/00_progress.md` |
| `reviewed_scope_digest` | `082d1638129a60a7be19116db18b9eb5e3b3bdb59d362020a37bdf868ca30e81` |
| 포착 시점 | Codex 디스패치 직전 / 완료 직후 재계산 결과 **동일** (심사 중 문서 변경 없음) |

배경 자료로 함께 제시한 문서: `docs/master-plan.md`, `docs/migration/_workspace/00_requirements-inventory.md`,
`docs/migration/_workspace/02_quality-baseline.md`.

### 호출 이력 (1차 실패 → 재시도)

| # | job id | 결과 |
|---|--------|------|
| 1 | `review-msptwdj5-871b28` | **실패(중단)**. 디스패치 명령을 `\| tail -20`으로 파이프해 셸이 EOF를 기다렸고, Bash 2분 타임아웃의 SIGTERM이 프로세스 그룹째 detached job을 죽였다. 로그 마지막 기록 08:31:39Z, 이후 pid 68127 소멸. 상태 파일에는 `running`으로 남아 있어 `cancel`로 정리했다. 검증 단계에서 잘려 findings 미산출(로그에 170자 요약 preview만 존재) — 채택하지 않았다. |
| 2 | `review-mspugl6y-k5xgtc` | **성공**. 파이프 없이 디스패치, 7m 47s, 종료 코드 0, findings 8건. 아래 원문은 이 실행의 산출물이다. |

## Codex 원문 (verbatim — stdout 그대로)

```json
{"verdict":"needs-attention","summary":"SHIP 금지. 일반 심사: 1 단계·의존성=실패, 2 숨은 가정=실패, 3 검증 가능성=실패, 4 실패·롤백=실패, 5 범위 이탈=해당 없음, 6 비협상 규칙=실패, 7 인접 영향=실패. Q1=재개발이 더 싸다는 근거가 없어 판단 불가; Phase 2 구현량은 대부분 남고 지식 추출이 추가되므로 현재 39개 미승인 인벤토리로의 전환은 검증력 퇴보다. Q2=추출 목록에 ingest·인증 등 코드/테스트 전용 불변식이 빠졌다. Q3=기존 DB 없음은 사용자 선언으로만 기록됐고, 첫 업로드 뒤 생길 파일럿 데이터의 복구 비용은 계산되지 않았다. Q4=번호 유지는 77개 파일의 798개 참조 때문에 타당하지만, 참조 의미를 갱신하지 않아 현재는 오히려 구버전 절차를 숨긴다. Q5=39항목 인벤토리는 미승인·상세 미완·호환성 항목 잔존 상태라 유일 명세가 될 수 없다. Q6=계획은 품질 문제가 언어와 무관함을 올바르게 명시하지만, 64.3%→90% 및 장문 6.7% 문제를 닫는 담당 단계가 없다. 실행 확인은 read-only git/rg/nl/wc로 수행했으며 pytest collect-only는 uv 캐시 접근 권한 때문에 실행되지 않았다.","findings":[{"severity":"high","title":"새 AEAD 방침과 반대되는 Fernet·롤백 게이트가 실행 단계에 남아 있다","body":"[실행확인] 계획 §4.3은 Fernet·양방향 호환을 무효라고 선언하지만, Phase 0은 여전히 Fernet 직접 구현과 양방향 fixture를 종료 조건으로 요구하고(348, 352행), Phase 4도 교차 런타임 암호 fixture를 요구한다(394행). `00_progress.md:37-45`도 Phase 0의 실질 종료 조건을 Python 롤백 호환으로 적는다. 현재 `CLAUDE.md:97`은 호환 무효, `CLAUDE.md:105`는 암호문·토큰 호환 필수라고 서로 충돌하고, SSOT인 `master-plan.md:193-197`도 Python/FastAPI·ARQ/Redis를 확정 스택으로 유지한다. [문서근거] 서로 배타적인 구현과 완료 조건이 모두 유효 문장으로 남아 있어 작업자가 Fernet과 AEAD 중 어느 쪽을 구현해도 다른 게이트를 위반한다. 이는 CLAUDE.md/master-plan 비협상 규칙과의 직접 충돌이다.","file":"docs/plans/2026-08-11-kotlin-react-migration.md","line_start":341,"line_end":352,"confidence":0.99,"recommendation":"착수 전에 실제 실행 단계·진행 게이트·CLAUDE.md·master-plan을 하나의 암호화·롤백 규칙으로 일치시켜라."},{"severity":"high","title":"Phase 번호는 유지됐지만 그 번호를 소비하는 실행 규칙은 구버전 의미를 유지한다","body":"[실행확인] 대상 계획과 master-plan을 제외하고도 `Phase N` 참조가 77개 파일에 798회 존재한다. `.claude/skills/kotlin-migration/SKILL.md:127-130`은 Phase 9를 여전히 선택 사항이자 독립 Python oracle 보존 단계로 규정하지만, 새 계획은 의무적 최종 폐기로 바꿨다. 다른 migration 문서와 Kotlin SQL에도 Phase 7 Python 롤백·Fernet 의미가 남아 있다. [문서근거] 번호 유지 자체는 참조 규모 때문에 합리적이지만, 안정된 키가 서로 다른 의미를 가리키면 에이전트가 구버전 범위와 게이트를 실행한다. 계획이 주장한 규약 드리프트 방지가 실제로 성립하지 않는다.","file":"docs/plans/2026-08-11-kotlin-react-migration.md","line_start":23,"line_end":39,"confidence":0.98,"recommendation":"번호는 유지하되 모든 실행 소비자의 Phase 의미가 새 정의와 일치한다는 참조 감사를 완료하라."},{"severity":"high","title":"Python 폐기 체크리스트가 중요한 코드·테스트 전용 판단을 누락한다","body":"[실행확인] 체크리스트는 A~K 일부 모듈만 열거한 채 `app/**`·`tests/**` 전체 삭제를 허용한다. 그러나 목록에 없는 `app/ingest/extractors.py:43-76`에는 500,000자 상한·압축 해제 5배 예산·64KiB 계수 단위·예외 축소 정책이, `:232-249`에는 linked header 중복 방지와 의도적 미지원 요소가, `:285-293`에는 공공 PDF의 owner-password는 허용하고 user-password만 거부하는 판단이 있다. `app/services/auth.py:61-67,212-218`에는 Argon2 동시성 메모리 상한과 계정 존재 timing oracle 방지가 있다. 관련 테스트는 변경추적 문서, 암호 PDF, 위조 ZIP 크기 같은 경계를 고정한다. [문서근거] 이 지식은 39개 전역 요구사항이나 현재 체크리스트의 목적지에 정확한 값·예외로 올라가 있지 않아 Python 삭제 시 보안 방어와 정상 문서 호환성이 영구 손실될 수 있다.","file":"docs/migration/_workspace/03_rebuild-extraction-list.md","line_start":172,"line_end":184,"confidence":0.99,"recommendation":"삭제 게이트가 app/tests 전 파일을 요구사항·fixture·의도적 폐기 중 하나에 빠짐없이 매핑하는지 증명하라."},{"severity":"high","title":"Phase 8이 Phase 9 검증 도구를 먼저 삭제하도록 지시한다","body":"[실행확인] Phase 8의 445행은 `app/` 제거를 지시하지만 451행은 `app/easyread/{judge,goldenset,collection,bokjiro}.py`를 Phase 9까지 존치한다고 한다. Phase 9는 바로 그 파일과 fixture로 Kotlin 대체물을 검증한 뒤 이식/폐기를 결정한다(457-461행). 반대로 추출 목록 182행은 K의 건별 결정을 Phase 8 선행 조건으로 요구한다. [문서근거] 동일 파일을 Phase 8에서 삭제·존치하고, Phase 9 결정을 Phase 8 전·후에 모두 하라는 순환 의존이다. 그대로 실행하면 대체물 검증 전에 oracle과 수집·평가 지식을 잃는다.","file":"docs/plans/2026-08-11-kotlin-react-migration.md","line_start":441,"line_end":461,"confidence":1,"recommendation":"Phase 8 삭제 대상과 Phase 9 선행 관계를 단일한 순서로 확정하라."},{"severity":"high","title":"운영 DB가 없다는 전제가 첫 파일럿 업로드 순간 깨지지만 데이터 복구 경로가 없다","body":"[실행확인] `00_progress.md:52`의 근거는 사용자 확인이라는 문서 기록뿐이고, master-plan은 파일럿 실무자 검증이 아직 미착수라고 적는다. Phase 7은 빈 DB를 만든 뒤 Kotlin에 파일럿 업로드를 열고(423-426행), AEAD 실패·작업 유실·문서 손상 시 신규 업로드 중단과 fix-forward만 규정한다(430-439행). [문서근거] 기존 Python DB가 없다는 사실은 Python 호환을 버릴 근거는 될 수 있지만, 첫 업로드 뒤 생성되는 계정·문서·암호문·작업 상태까지 복구 불필요하게 만들지는 않는다. 키·스키마·배포 결함이 발생하면 이미 받은 파일럿 데이터를 읽지 못하거나 장기간 정지할 수 있는데, 이전 Kotlin 배포·DB 상태·키·작업을 복구하는 증명은 없다.","file":"docs/plans/2026-08-11-kotlin-react-migration.md","line_start":419,"line_end":439,"confidence":0.97,"recommendation":"파일럿 업로드를 열기 전에 일방향 전환에서도 생성 데이터의 복구 가능성을 검증하라."},{"severity":"high","title":"유일한 회귀 그물로 삼은 인벤토리가 아직 승인·재개발 정합성을 갖추지 못했다","body":"[실행확인] 인벤토리는 정확히 39개 항목이며 `미승인` 상태다(`00_requirements-inventory.md:3`). 38개가 미충족 또는 확인 불가이고, 두 품질 항목은 확인 방법 미확정이다. 17행은 정확한 경계값·프롬프트·상수를 후속 Phase로 미루며, 37-44행은 이미 무효화된 Fernet/JWT 양방향 호환과 additive 스키마를 여전히 요구한다. `02_quality-baseline.md:42-62`도 인벤토리의 합격선 기술이 부정확하다고 정정한다. [문서근거] 이 상태에서 Python 제거 후 인벤토리를 유일한 그물로 삼으면 잘못된 호환 작업은 필수로 판정되고 누락된 도메인 상세는 검사 대상조차 되지 않는다. '미충족 0'은 신뢰할 수 있는 완료 증명이 아니다.","file":"docs/plans/2026-08-11-kotlin-react-migration.md","line_start":85,"line_end":95,"confidence":0.99,"recommendation":"인벤토리의 승인·재개발 재기준화·도메인 상세·계측기 공백이 닫히기 전에는 이를 완료 게이트로 사용하지 마라."},{"severity":"high","title":"알려진 90% 품질 실패를 실제로 개선하고 닫는 단계가 없다","body":"[실행확인] 품질 자료의 저장 실행은 전체 64.3%, 실수집 52.8%로 KPI 90%에 미달하고(`02_quality-baseline.md:305-318`), 2,000자 초과는 6.7%다(`:330-340`). 현행 프롬프트·규칙은 그 실행 후 변경돼 현재 값도 미측정이다(`:285-303,529-537`). 계획은 문제를 인정하지만 Phase 0은 합격선 확정만, Phase 2는 규칙 구현과 차분 분류만, Phase 5는 provider 소량 smoke만 종료 조건으로 삼고 품질 합격은 Phase 7 중단 기준에서야 다시 나타난다. [문서근거] 언어 전환이 해결하지 않는다는 설명은 맞지만, 해결 작업·현재 재측정·단계별 통과 증거가 없으므로 알려진 실패 상태로 배포 단계까지 진행할 수 있다.","file":"docs/plans/2026-08-11-kotlin-react-migration.md","line_start":327,"line_end":337,"confidence":0.98,"recommendation":"Phase 7 이전에 현재 코퍼스와 고정된 judge로 90%·장문 품질 충족을 증명하는 명시적 종료 게이트를 두어라."},{"severity":"medium","title":"재개발이 더 싸다는 1주 절감 추정은 현재 추출 범위를 비용에 반영하지 않았다","body":"[실행확인] 계획은 런타임 10~13 person-weeks로 초판보다 1주 낮추며 문서·도메인 동등성 제거에서 1주를 차감한다. 반면 새 추출 목록은 11개 영역의 명세화·fixture 재확인·테스트 전사를 추가하고, 현재 명명된 런타임 모듈만 3,648줄이며 별도로 누락된 고위험 4개 모듈이 1,756줄이다. 인벤토리는 아직 39개 1차본이다. 계획의 증가분은 전역 인벤토리 1~2일과 차분 분류만 계상하고 이 폐기 게이트의 항목별 산정이나 실측을 제시하지 않는다. [문서근거] Phase 2의 마스킹·정규화·프롬프트·스타일 구현 자체는 어느 접근에서도 필요하며, 재개발은 코드/테스트에서 지식을 먼저 추출하는 비용을 더 부담한다. 따라서 더 싸다는 결론은 현재 근거로 판단 불가이고, 39개 미승인 목록으로 800여 테스트의 실행 명세를 대신하는 것은 현시점에는 개선이 아니라 검증력 퇴보다.","file":"docs/plans/2026-08-11-kotlin-react-migration.md","line_start":487,"line_end":506,"confidence":0.96,"recommendation":"추출 게이트가 완전해진 뒤 항목별 작업량을 산정하기 전까지 절감 일정과 비용 우위를 미확정으로 취급하라."}],"next_steps":[]}
```

## 원문 (동일 내용, 가독용 정렬 — 값은 손대지 않음)

```json
{
  "verdict": "needs-attention",
  "summary": "SHIP 금지. 일반 심사: 1 단계·의존성=실패, 2 숨은 가정=실패, 3 검증 가능성=실패, 4 실패·롤백=실패, 5 범위 이탈=해당 없음, 6 비협상 규칙=실패, 7 인접 영향=실패. Q1=재개발이 더 싸다는 근거가 없어 판단 불가; Phase 2 구현량은 대부분 남고 지식 추출이 추가되므로 현재 39개 미승인 인벤토리로의 전환은 검증력 퇴보다. Q2=추출 목록에 ingest·인증 등 코드/테스트 전용 불변식이 빠졌다. Q3=기존 DB 없음은 사용자 선언으로만 기록됐고, 첫 업로드 뒤 생길 파일럿 데이터의 복구 비용은 계산되지 않았다. Q4=번호 유지는 77개 파일의 798개 참조 때문에 타당하지만, 참조 의미를 갱신하지 않아 현재는 오히려 구버전 절차를 숨긴다. Q5=39항목 인벤토리는 미승인·상세 미완·호환성 항목 잔존 상태라 유일 명세가 될 수 없다. Q6=계획은 품질 문제가 언어와 무관함을 올바르게 명시하지만, 64.3%→90% 및 장문 6.7% 문제를 닫는 담당 단계가 없다. 실행 확인은 read-only git/rg/nl/wc로 수행했으며 pytest collect-only는 uv 캐시 접근 권한 때문에 실행되지 않았다.",
  "findings": [
    {
      "severity": "high",
      "title": "새 AEAD 방침과 반대되는 Fernet·롤백 게이트가 실행 단계에 남아 있다",
      "body": "[실행확인] 계획 §4.3은 Fernet·양방향 호환을 무효라고 선언하지만, Phase 0은 여전히 Fernet 직접 구현과 양방향 fixture를 종료 조건으로 요구하고(348, 352행), Phase 4도 교차 런타임 암호 fixture를 요구한다(394행). `00_progress.md:37-45`도 Phase 0의 실질 종료 조건을 Python 롤백 호환으로 적는다. 현재 `CLAUDE.md:97`은 호환 무효, `CLAUDE.md:105`는 암호문·토큰 호환 필수라고 서로 충돌하고, SSOT인 `master-plan.md:193-197`도 Python/FastAPI·ARQ/Redis를 확정 스택으로 유지한다. [문서근거] 서로 배타적인 구현과 완료 조건이 모두 유효 문장으로 남아 있어 작업자가 Fernet과 AEAD 중 어느 쪽을 구현해도 다른 게이트를 위반한다. 이는 CLAUDE.md/master-plan 비협상 규칙과의 직접 충돌이다.",
      "file": "docs/plans/2026-08-11-kotlin-react-migration.md",
      "line_start": 341,
      "line_end": 352,
      "confidence": 0.99,
      "recommendation": "착수 전에 실제 실행 단계·진행 게이트·CLAUDE.md·master-plan을 하나의 암호화·롤백 규칙으로 일치시켜라."
    },
    {
      "severity": "high",
      "title": "Phase 번호는 유지됐지만 그 번호를 소비하는 실행 규칙은 구버전 의미를 유지한다",
      "body": "[실행확인] 대상 계획과 master-plan을 제외하고도 `Phase N` 참조가 77개 파일에 798회 존재한다. `.claude/skills/kotlin-migration/SKILL.md:127-130`은 Phase 9를 여전히 선택 사항이자 독립 Python oracle 보존 단계로 규정하지만, 새 계획은 의무적 최종 폐기로 바꿨다. 다른 migration 문서와 Kotlin SQL에도 Phase 7 Python 롤백·Fernet 의미가 남아 있다. [문서근거] 번호 유지 자체는 참조 규모 때문에 합리적이지만, 안정된 키가 서로 다른 의미를 가리키면 에이전트가 구버전 범위와 게이트를 실행한다. 계획이 주장한 규약 드리프트 방지가 실제로 성립하지 않는다.",
      "file": "docs/plans/2026-08-11-kotlin-react-migration.md",
      "line_start": 23,
      "line_end": 39,
      "confidence": 0.98,
      "recommendation": "번호는 유지하되 모든 실행 소비자의 Phase 의미가 새 정의와 일치한다는 참조 감사를 완료하라."
    },
    {
      "severity": "high",
      "title": "Python 폐기 체크리스트가 중요한 코드·테스트 전용 판단을 누락한다",
      "body": "[실행확인] 체크리스트는 A~K 일부 모듈만 열거한 채 `app/**`·`tests/**` 전체 삭제를 허용한다. 그러나 목록에 없는 `app/ingest/extractors.py:43-76`에는 500,000자 상한·압축 해제 5배 예산·64KiB 계수 단위·예외 축소 정책이, `:232-249`에는 linked header 중복 방지와 의도적 미지원 요소가, `:285-293`에는 공공 PDF의 owner-password는 허용하고 user-password만 거부하는 판단이 있다. `app/services/auth.py:61-67,212-218`에는 Argon2 동시성 메모리 상한과 계정 존재 timing oracle 방지가 있다. 관련 테스트는 변경추적 문서, 암호 PDF, 위조 ZIP 크기 같은 경계를 고정한다. [문서근거] 이 지식은 39개 전역 요구사항이나 현재 체크리스트의 목적지에 정확한 값·예외로 올라가 있지 않아 Python 삭제 시 보안 방어와 정상 문서 호환성이 영구 손실될 수 있다.",
      "file": "docs/migration/_workspace/03_rebuild-extraction-list.md",
      "line_start": 172,
      "line_end": 184,
      "confidence": 0.99,
      "recommendation": "삭제 게이트가 app/tests 전 파일을 요구사항·fixture·의도적 폐기 중 하나에 빠짐없이 매핑하는지 증명하라."
    },
    {
      "severity": "high",
      "title": "Phase 8이 Phase 9 검증 도구를 먼저 삭제하도록 지시한다",
      "body": "[실행확인] Phase 8의 445행은 `app/` 제거를 지시하지만 451행은 `app/easyread/{judge,goldenset,collection,bokjiro}.py`를 Phase 9까지 존치한다고 한다. Phase 9는 바로 그 파일과 fixture로 Kotlin 대체물을 검증한 뒤 이식/폐기를 결정한다(457-461행). 반대로 추출 목록 182행은 K의 건별 결정을 Phase 8 선행 조건으로 요구한다. [문서근거] 동일 파일을 Phase 8에서 삭제·존치하고, Phase 9 결정을 Phase 8 전·후에 모두 하라는 순환 의존이다. 그대로 실행하면 대체물 검증 전에 oracle과 수집·평가 지식을 잃는다.",
      "file": "docs/plans/2026-08-11-kotlin-react-migration.md",
      "line_start": 441,
      "line_end": 461,
      "confidence": 1,
      "recommendation": "Phase 8 삭제 대상과 Phase 9 선행 관계를 단일한 순서로 확정하라."
    },
    {
      "severity": "high",
      "title": "운영 DB가 없다는 전제가 첫 파일럿 업로드 순간 깨지지만 데이터 복구 경로가 없다",
      "body": "[실행확인] `00_progress.md:52`의 근거는 사용자 확인이라는 문서 기록뿐이고, master-plan은 파일럿 실무자 검증이 아직 미착수라고 적는다. Phase 7은 빈 DB를 만든 뒤 Kotlin에 파일럿 업로드를 열고(423-426행), AEAD 실패·작업 유실·문서 손상 시 신규 업로드 중단과 fix-forward만 규정한다(430-439행). [문서근거] 기존 Python DB가 없다는 사실은 Python 호환을 버릴 근거는 될 수 있지만, 첫 업로드 뒤 생성되는 계정·문서·암호문·작업 상태까지 복구 불필요하게 만들지는 않는다. 키·스키마·배포 결함이 발생하면 이미 받은 파일럿 데이터를 읽지 못하거나 장기간 정지할 수 있는데, 이전 Kotlin 배포·DB 상태·키·작업을 복구하는 증명은 없다.",
      "file": "docs/plans/2026-08-11-kotlin-react-migration.md",
      "line_start": 419,
      "line_end": 439,
      "confidence": 0.97,
      "recommendation": "파일럿 업로드를 열기 전에 일방향 전환에서도 생성 데이터의 복구 가능성을 검증하라."
    },
    {
      "severity": "high",
      "title": "유일한 회귀 그물로 삼은 인벤토리가 아직 승인·재개발 정합성을 갖추지 못했다",
      "body": "[실행확인] 인벤토리는 정확히 39개 항목이며 `미승인` 상태다(`00_requirements-inventory.md:3`). 38개가 미충족 또는 확인 불가이고, 두 품질 항목은 확인 방법 미확정이다. 17행은 정확한 경계값·프롬프트·상수를 후속 Phase로 미루며, 37-44행은 이미 무효화된 Fernet/JWT 양방향 호환과 additive 스키마를 여전히 요구한다. `02_quality-baseline.md:42-62`도 인벤토리의 합격선 기술이 부정확하다고 정정한다. [문서근거] 이 상태에서 Python 제거 후 인벤토리를 유일한 그물로 삼으면 잘못된 호환 작업은 필수로 판정되고 누락된 도메인 상세는 검사 대상조차 되지 않는다. '미충족 0'은 신뢰할 수 있는 완료 증명이 아니다.",
      "file": "docs/plans/2026-08-11-kotlin-react-migration.md",
      "line_start": 85,
      "line_end": 95,
      "confidence": 0.99,
      "recommendation": "인벤토리의 승인·재개발 재기준화·도메인 상세·계측기 공백이 닫히기 전에는 이를 완료 게이트로 사용하지 마라."
    },
    {
      "severity": "high",
      "title": "알려진 90% 품질 실패를 실제로 개선하고 닫는 단계가 없다",
      "body": "[실행확인] 품질 자료의 저장 실행은 전체 64.3%, 실수집 52.8%로 KPI 90%에 미달하고(`02_quality-baseline.md:305-318`), 2,000자 초과는 6.7%다(`:330-340`). 현행 프롬프트·규칙은 그 실행 후 변경돼 현재 값도 미측정이다(`:285-303,529-537`). 계획은 문제를 인정하지만 Phase 0은 합격선 확정만, Phase 2는 규칙 구현과 차분 분류만, Phase 5는 provider 소량 smoke만 종료 조건으로 삼고 품질 합격은 Phase 7 중단 기준에서야 다시 나타난다. [문서근거] 언어 전환이 해결하지 않는다는 설명은 맞지만, 해결 작업·현재 재측정·단계별 통과 증거가 없으므로 알려진 실패 상태로 배포 단계까지 진행할 수 있다.",
      "file": "docs/plans/2026-08-11-kotlin-react-migration.md",
      "line_start": 327,
      "line_end": 337,
      "confidence": 0.98,
      "recommendation": "Phase 7 이전에 현재 코퍼스와 고정된 judge로 90%·장문 품질 충족을 증명하는 명시적 종료 게이트를 두어라."
    },
    {
      "severity": "medium",
      "title": "재개발이 더 싸다는 1주 절감 추정은 현재 추출 범위를 비용에 반영하지 않았다",
      "body": "[실행확인] 계획은 런타임 10~13 person-weeks로 초판보다 1주 낮추며 문서·도메인 동등성 제거에서 1주를 차감한다. 반면 새 추출 목록은 11개 영역의 명세화·fixture 재확인·테스트 전사를 추가하고, 현재 명명된 런타임 모듈만 3,648줄이며 별도로 누락된 고위험 4개 모듈이 1,756줄이다. 인벤토리는 아직 39개 1차본이다. 계획의 증가분은 전역 인벤토리 1~2일과 차분 분류만 계상하고 이 폐기 게이트의 항목별 산정이나 실측을 제시하지 않는다. [문서근거] Phase 2의 마스킹·정규화·프롬프트·스타일 구현 자체는 어느 접근에서도 필요하며, 재개발은 코드/테스트에서 지식을 먼저 추출하는 비용을 더 부담한다. 따라서 더 싸다는 결론은 현재 근거로 판단 불가이고, 39개 미승인 목록으로 800여 테스트의 실행 명세를 대신하는 것은 현시점에는 개선이 아니라 검증력 퇴보다.",
      "file": "docs/plans/2026-08-11-kotlin-react-migration.md",
      "line_start": 487,
      "line_end": 506,
      "confidence": 0.96,
      "recommendation": "추출 게이트가 완전해진 뒤 항목별 작업량을 산정하기 전까지 절감 일정과 비용 우위를 미확정으로 취급하라."
    }
  ],
  "next_steps": []
}
```
