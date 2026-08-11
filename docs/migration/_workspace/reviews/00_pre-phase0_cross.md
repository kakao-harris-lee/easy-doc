# Phase 00 · pre-phase0 — 교차 종합 (정본)

**작성:** `migration-reviewer` **2차 호출 (교차 종합 전용)** / 2026-08-12
**이 회차의 규칙:** 대조만 한다. 새 지적을 만들지 않는다. 어느 쪽 지적도 삭제하지 않는다. 코드를 고치지 않는다.
**1차 산출물의 심각도를 재판정하지 않는다** — 양측 심각도를 병기하고, 척도가 갈리는 지점은 "판정 필요"로 올린다.

> **codex 리뷰 상태: 정상 수신.** "codex 리뷰 없음 — 교차 대조 미수행"에 해당하지 않는다.
> codex 2회차가 정상 완료(job `review-msos2nv7-lqf2mh`, EXIT=0, 8분 47초, 지적 7건, 잘림 없음)했다.

## 0. 대조한 두 산출물

| 항목 | Claude 측 | codex 측 |
|---|---|---|
| 경로 | `docs/migration/_workspace/reviews/00_pre-phase0_migration-reviewer.md` | `docs/migration/_workspace/reviews/00_pre-phase0_codex-reviewer.md` |
| 회차 | 1차 (독립 리뷰) | 2회차 (1회차는 산출물 없이 취소) |
| 분량 | 564줄 / 지적 36건 + 진행문서 정정 4건 | 226줄 / codex 원문 지적 7건 |
| 실행 조건 | 저장소 맥락·계획 문서 전체 참조. 일부 지적은 실행 재현 | `adversarial-review --base main`, focus text 5축(A~E) |
| 대상 범위 | 이번 세션 변경 전체(추적 5 + 당시 미추적 하네스 일체) | `main...HEAD` branch diff — 25 files / +6,386 −7 |
| 독립성 | codex 산출물 미참조 | focus text로 `docs/migration/_workspace/reviews/` 열람 금지 지시, job 로그에 제외 패턴 관측 |

**리뷰 범위 비대칭 (사실만 기록):**

- `main...HEAD` diff 25개 파일에 `app/api/workspaces.py`와 `tests/api/test_workspaces.py`가 **없다**(이번 회차 확인). Claude T-2·C-1의 근거 파일 일부가 codex의 리뷰 범위 밖이었다.
- `docs/plans/2026-08-11-kotlin-react-migration.md`는 diff에 **포함돼 있었다**(이번 회차 확인). 계획 문서는 양측 모두 접근 가능했다.
- codex focus text의 5개 축은 A(`compare_parity.py`) / B(`scan_privacy_invariants.py`) / C(`codex-review.sh`) / D(캐시 헤더·422) / E(회귀 테스트)다. Claude의 계약 스펙 정합성(C-*), parity fixture 설계(P-2~P-5), 에이전트·스킬 정의 정합성(A-*) 축은 focus에 포함되지 않았다.

## 1. 교차 대조표

출처: `양쪽` / `codex` / `Claude`. 심각도는 **각 리뷰가 붙인 값 그대로**이며 이 회차에서 재부여하지 않았다.
`Claude 심각도`의 척도는 계획 §5 Phase 7 즉시 중단 기준 해당 여부(차단/수정 필요/권고), `codex 심각도`는 codex 자체 척도(critical/high/medium)다. **두 척도는 정의가 다르다** — §3 상충-2 참조.

### 1.1 양쪽이 같은 지적 (신뢰도 높음 — 우선 조치)

| # | 지적 | 출처 | Claude | codex | 근거 위치 | 상충 | 권고 |
|---|---|---|---|---|---|---|---|
| X-1 | 역방향 검증 증거 파일(`*.verified.json`)이 위조 가능. `fixture_case`/`status`/`checked`만 읽고 `actual` 경로 존재·해시·신선도를 검사하지 않는다 | **양쪽** | 수정 필요 (H-1, 실행 재현) | critical (#2, 재현) | `compare_parity.py:204-254` (codex) / 동 `:204-254`, `:220-225` (Claude) | - | 수정 필요 |
| X-2 | fixture 출처가 검증되지 않는다. 손작성·축소 fixture가 통과한다 | **양쪽** | 수정 필요 (H-2) | critical (#1, 재현) | `compare_parity.py:292-405`(codex) / `:310`, `:312-313`, `:1043-1044`(Claude) | - | 수정 필요 |
| X-3 | privacy 스캔 규칙이 줄 단위라 멀티라인·변수 경유 위반을 놓친다 | **양쪽** | 수정 필요 (H-5, 실측 2규칙) | high (#5, 12규칙 전수) | `scan_privacy_invariants.py:73-270`(codex) / `:259-270`(Claude) | - | 수정 필요 |
| X-4 | privacy 전수 스캔이 정상 기준선을 오탐으로 BLOCK 처리해 exit 1 (`password: 'wrongpassword'`, 안전하게 감싼 ElementTree) | **양쪽** | 판정 필요 (S-4) | high (#5 오탐 방향) | `frontend/src/api/client.test.ts:85`, `app/easyread/bokjiro.py:21` | - | 근거 확인 필요 → `privacy-gate` 판정 |
| X-5 | `CACHE-HEADER` 규칙이 누락을 탐지하지 못한다 | **양쪽** | 수정 필요 (H-4) | high (#5 중 `return SensitiveResponse(email)`) | `scan_privacy_invariants.py:184-185`, 적중 1건 = `app/api/documents.py:50` | - | 수정 필요 |
| X-6 | `float_tol`에 유한·상한 검증이 없어 모든 수치 비교를 통과시킬 수 있다 | **양쪽** | 권고 (H-8, `1e309`) | medium (#7, `inf` 재현) | `compare_parity.py:104-116`(codex) / `:114-115`(Claude) | - | 수정 필요 |
| X-7 | `codex-review.sh`가 자식 출력을 캡처·검증하지 않고 `exec`해, 빈 출력·가짜 헬퍼의 exit 0이 "리뷰 성공"으로 전파된다 | **양쪽** | 수정 필요 (H-10, `--dry-run` 경로) | high (#6, `CODEX_COMPANION=/dev/null` 재현) | `codex-review.sh:148-273`(codex) / `:242-265`, `:267-270`, `:273`(Claude) | - | 수정 필요 |
| X-8 | 검사 대상 파일 0개인데 exit 0 | **양쪽**(부분) | 수정 필요 (H-3) | high (#4 권고문 중 "파일 0개는 비zero로 실패시켜라") | `scan_privacy_invariants.py:331-334` | - | 수정 필요 |
| X-9 | **[부정 합의]** Python 캐시 헤더 패치·422 입력값 제거·추가 회귀 테스트에서 차단 결함 없음 | **양쪽** | 검토함 — 지적 없음 (S-1, S-2, T-1. T-1은 변이 검사로 7 passed → 7 failed 확인) | 차단 결함 미발견 (원문 마지막 줄) | `app/api/auth.py`, `app/api/documents.py`, `tests/api/test_*.py` | - | 유지 |

**X-9의 의미**: 프로덕션 코드 변경 두 커밋(`0fafac7`, `e88db3e`) 중 Python 측 변경은 서로 다른 방법(codex=전수 대조 / Claude=마커 프로브 6종 + 변이 검사)으로 검증돼 **양쪽 모두 결함을 찾지 못했다.** 이번 회차 지적은 전부 **하네스**에 몰려 있다.

### 1.2 codex 단독

확인 없이 기각하는 것이 이 게이트에서 가장 흔한 실패이므로, 세 건 모두 이번 회차에 코드로 검증했다.

| # | 지적 | 출처 | codex | 근거 위치 | 이번 회차 확인 | 권고 |
|---|---|---|---|---|---|---|
| X-10 | `--changed`가 `git diff --name-only HEAD` + untracked만 보므로 **이미 커밋된 브랜치 변경을 전부 제외**한다. 구현을 커밋한 뒤 이 권장 명령을 돌리면 보안 코드를 한 줄도 읽지 않고 성공 | codex (#4) | high | `scan_privacy_invariants.py:205-334` | **실재 확인.** `:208-219`가 `git diff --name-only HEAD`와 `ls-files --others`만 조회한다. base ref 인자 없음 | 수정 필요 |
| X-11 | fixture 검사가 `actual`의 `runtime` 값을 **전혀 읽지 않는다** → `runtime: not-kotlin` 결과로도 통과 | codex (#1) | critical | `compare_parity.py:292-405` | **실재 확인.** `compare_parity.py` 전체에서 `runtime` 문자열은 19행 docstring 예시 1곳뿐이고 코드가 읽는 지점이 없다 | 수정 필요 |
| X-12 | 진짜 proof 파일도 신뢰할 수 없다 — 번들 verifier가 임의 actual의 **건수만** 세고 fixture가 요구한 키·평문·subject·고유 case 집합과 연결하지 않아, Python이 만든 토큰이나 중복 케이스로 proof를 만들 수 있다 | codex (#2 하위) | critical | `compare_parity.py:204-254` (생산자 측은 `dump_parity_fixtures.py`) | 미검증 — 이번 회차는 소비자 측(`check_external`)만 확인했다 | 근거 확인 필요 |
| X-13 | `backend-kotlin/infrastructure/` **디렉터리 전체**가 vendor SDK 규칙에서 면제된다 | codex (#5 하위) | high | `scan_privacy_invariants.py:112` | **실재 확인.** `sanctioned` 튜플에 `"backend-kotlin/infrastructure/"`가 있고 `:267`이 경로 부분 문자열로 매칭한다. `backend-kotlin/`은 아직 미생성이므로 **현재 코드가 아니라 규칙 설계에 대한 지적**이다 | 수정 필요 (Phase 1 전) |

X-13은 Claude H-6(`sanctioned`에 경로가 아닌 식별자 `LlmProvider`가 섞여 부분 문자열 매칭으로 과다 면제)과 **같은 필드의 다른 결함**이다. 한 행으로 묶지 않고 병기한다.

### 1.3 Claude 단독

codex가 왜 보지 않았는지는 추정하지 않는다. 리뷰 범위·focus 축에 대한 **검증 가능한 사실만** 적는다.

**축 1 — 계약 준수**

| # | 지적 | Claude | 근거 위치 | codex 범위 사실 | 권고 |
|---|---|---|---|---|---|
| X-14 (C-1) | `api-contract-freeze` §1 엔드포인트 표가 §2.5 헤더 목록(10개)보다 **2개 적다**. `GET /conversions/{id}/export`·`PATCH /workspaces/{workspace_id}`에 헤더 표기 없음. §1만 보고 Kotlin을 구현하면 두 곳이 빠진다 | 권고 | `.claude/skills/api-contract-freeze/SKILL.md:31,34` vs `:94-98`. 코드: `documents.py:381`, `workspaces.py:113` | 계약 스펙 내부 정합성은 focus 5축에 없음 | 수정 필요 |
| X-15 (C-2) | 계약이 **오류 응답**의 캐시 헤더를 규정하지 않는다. 401·422에 헤더가 없음을 실측. `(엔드포인트, 상태코드)` 쌍으로 동결돼 있지 않아 parity 판정이 불가능해진다 | 판정 필요 | `SKILL.md:94-98` (엔드포인트 단위 동결) | 동상 | 사용자 판단 → `contract-keeper` |
| X-16 (C-4) | 지시 근거가 된 "7 → 10"은 사실과 다르다. 실제는 **6 → 10** | 정정(기록) | `git show HEAD` 기준 이전 상태 documents 3 + workspaces 3 | codex는 개수 변화를 다루지 않음 | 기록 |
| — (C-3) | CORS 노출 헤더 — **검토함, 지적 없음** | 통과 | `app/main.py:59` | 축 D 인접이나 codex 언급 없음 | 유지 |

**축 2 — parity 위험**

| # | 지적 | Claude | 근거 위치 | codex 범위 사실 | 권고 |
|---|---|---|---|---|---|
| X-17 (P-1) | 오류 경로 헤더 거동이 Python과 Spring에서 **반대**다. Python은 `_make_handler`가 새 `JSONResponse`를 만들어 헤더를 버리지만, Spring MVC는 `HttpServletResponse`에 쓴 헤더가 `@ExceptionHandler` 응답에도 남는다. 순진한 포팅이 Python이 내지 않는 헤더를 낸다. Python 쪽 설정 위치가 호출 전/후로 제각각이라 포터가 두 의도로 읽는다 | 수정 필요 | `app/api/errors.py:65-70`; 설정 전 `auth.py:66,80,92`·`documents.py:282`·`workspaces.py:83,96,113` / 설정 후 `documents.py:321,343` | parity 위험 축은 focus 5축에 없음 | 수정 필요 → `kotlin-implementer` + `contract-keeper` |
| X-18 (P-2) | JWT `exp` 경계는 fixture로 고정돼 있으나(**통과**), 그것을 돌리려면 Kotlin `AuthService`가 주입 가능한 `Clock`을 받아야 한다. 그 요구가 `python-kotlin-parity/SKILL.md:181`에만 있고 **구현자가 따르는 `kotlin-spring-conventions`에는 한 줄도 없다** | 수정 필요 | `dump_parity_fixtures.py:679-692`, `:639-660`, `:1158-1160` / `kotlin-spring-conventions` grep 0건 | 동상 | 수정 필요 |
| X-19 (P-3) | argon2 도메인에 **역방향(external) 케이스와 검증 명령이 둘 다 없다.** 솔트 때문에 문자열 비교가 원천 불가하므로 "Kotlin이 만든 PHC를 Python이 읽는가"는 역방향 실행 외에 증명 수단이 없다. 롤백 시 관찰 기간 신규 가입자 전원 로그인 불가로 이어진다 | 수정 필요 | `dump_parity_fixtures.py:1062-1065`(PROOF_NAMES에 argon2 없음), `:833-1012`, `:847-848` | 동상 | 수정 필요 |
| X-20 (P-4) | `verify_crypto`에 `verify_jwt`와 달리 시각 고정 장치가 없다. **현재는 무해**(`crypto.py:61`이 `ttl` 없이 `decrypt`). Fernet TTL을 켜면 그때 함께 고쳐야 한다 | 검토함 — 현재 무해 | `dump_parity_fixtures.py:1112-1136` vs `:1158-1160` | 동상 | 조건부 |
| X-21 (P-5) | `except (StorageError, Exception)` — 튜플이 무의미하고 `KeyError`까지 "복호화 실패"로 기록된다. 입력 파일 결함과 암호 비호환이 증거 파일에 같은 문구로 남는다 | 권고 | `dump_parity_fixtures.py:1127` | 동상 | 수정 필요 |

**축 3 — 보안 불변식**

| # | 지적 | Claude | 근거 위치 | codex 범위 사실 | 권고 |
|---|---|---|---|---|---|
| X-22 (S-3/T-3) | 계약이 **"반드시 넣을 테스트"의 첫 항목**으로 지정한 "검증 실패 응답에 제출한 비밀번호 문자열이 없음" 단언 테스트가 없다. 현재 동작이 옳음은 실증(S-2)했으나 고정돼 있지 않다 | 수정 필요 | `api-contract-freeze/SKILL.md:306` | focus 축 E는 "추가된 회귀 테스트"만 대상. codex는 D에서 422 정제를 "유지한다"고만 적음 | 수정 필요 |
| — (S-1) | 헤더 없는 4개 핸들러 전부 정당함 — **검토함, 지적 없음** | 통과 | `documents.py:306`, `workspaces.py:133` 등 | X-9와 합의 | 유지 |
| — (S-2) | 6종 마커 프로브 전부 미노출 — **검토함, 지적 없음** | 통과 | `app/api/errors.py:125-138` | X-9와 합의 | 유지 |

**축 5 — 테스트 적정성**

| # | 지적 | Claude | 근거 위치 | codex 범위 사실 | 권고 |
|---|---|---|---|---|---|
| X-23 (T-2) | 동결된 10개 중 **8개만** 캐시 헤더 테스트가 있다. `POST /workspaces`·`PATCH /workspaces/{workspace_id}` 누락. 계약 §5는 "계약 테스트가 없는 항목은 동결된 것이 아니다"라고 못박는다 | 수정 필요 | `tests/api/test_workspaces.py`에 캐시 테스트 1건(`:136-140`, GET 목록)뿐 — **이번 회차 재확인** | **`tests/api/test_workspaces.py`와 `app/api/workspaces.py`는 `main...HEAD` diff 25개 파일에 없다** — codex의 리뷰 범위 밖 | 수정 필요 |

**축 6 — 에이전트/스킬 정의의 실행 가능성** (Claude 1차 고유 축. focus 5축에 대응 항목 없음)

| # | 지적 | Claude | 근거 위치 | 권고 |
|---|---|---|---|---|
| X-24 (A-3) | 리뷰 산출물 `{scope}` 슬러그가 **세 갈래**로 갈렸다. Phase 2에서 `codex-reviewer`는 `02_core-domain_*`를 쓰는데 오케스트레이터는 `02_domain_cross.md`를 만든다 — `..._cross.md`가 자기 입력과 어간을 공유하지 않아 **2차 교차 종합 호출이 입력 파일을 못 찾는 실패로 직결** | 수정 필요 | `codex-reviewer.md:27-36` vs `codex-review/SKILL.md:250` vs `kotlin-migration/SKILL.md:214`·`00_progress.md:46` | 수정 필요 |
| X-25 (A-4) | 오케스트레이터가 **존재하지 않는 도구**(`TaskCreate`/`TaskList`/`TaskUpdate`)를 지시한다 | 수정 필요 | `kotlin-migration/SKILL.md:29`, `:172` | 수정 필요 |
| X-26 (A-2) | Phase 종료 판정을 강제하는 도구가 **하나도 없다.** `..._codex-reviewer.md` 존재 여부, `..._cross.md` 존재 여부, `충족=예` 행의 근거 칸 비어 있음 — 어느 것도 스크립트가 검사하지 않는다. 전부 산문 규약이다 | 수정 필요 | 하네스 스크립트 3개 전수 | 수정 필요 |
| X-27 (A-8) | 리뷰 게이트 면제 목록이 두 문서에서 다르다. `kotlin-migration/SKILL.md:166`이 **"스킬/에이전트 정의 수정"을 면제에 추가** — 이번 세션 변경 대부분이 여기 해당해 **이 리뷰 자체가 면제 대상이 된다** | 권고 | `codex-review/SKILL.md:40-45` vs `kotlin-migration/SKILL.md:166` | 사용자 판단 |
| X-28 (A-9) | `codex-reviewer` 에이전트가 `tools:` 미지정으로 Edit/Write를 상속한다. 역할은 "가공하지 않은 원본 전달" | 권고 | `.claude/agents/codex-reviewer.md` | 수정 필요 |
| X-29 (H-9) | `codex-review.sh`가 **focus 없는 adversarial**을 막지 않는다. `review`+focus만 막고 역방향 검사가 없어, focus 없는 열화 리뷰가 "adversarial 리뷰를 돌렸다"는 기록을 남긴다 | 수정 필요 | `codex-review.sh:139-141` — **이번 회차 재확인**, 역방향 분기 없음 | 수정 필요 |
| X-30 (H-6) | `LLM-VENDOR-SDK`의 `sanctioned`에 경로가 아닌 **식별자** `LlmProvider`가 섞여 있어, 경로에 그 문자열이 든 파일은 어댑터 경계 밖이어도 전부 면제된다 | 권고 | `scan_privacy_invariants.py:112`, `:267` | 수정 필요 (X-13과 함께) |
| X-31 (H-7) | `LLM-RAW-INPUT`의 부정 전방탐색이 인자 어디에든 `mask`가 있으면 꺼진다(`maskingEnabled = false`로도). 변수명 목록도 좁다 | 권고 | `scan_privacy_invariants.py:119-121` | 수정 필요 |
| X-32 (A-5) | 3자 대조 보고서 파일명이 두 갈래(`00_contract-keeper_drift.md` vs `..._three-way-diff.md`) | 권고 | `contract-keeper.md:77,118` vs `api-contract-freeze/SKILL.md:261` | 수정 필요 |
| X-33 (A-6) | 계획 문서 §번호 오인용 3건 (§7 ↔ §5 Phase 7 혼동 2건, 자기 문서 §5 ↔ §4 1건) | 권고 | `api-contract-freeze/SKILL.md:354`, `:35`; `kotlin-spring-conventions/SKILL.md:262` | 수정 필요 |
| X-34 (A-7) | Phase 종료 조건 오기 2건 (Phase 0의 **작업 항목**을 종료 조건으로, Phase 4 종료 조건을 모듈 단위로) | 권고 | `contract-keeper.md:46`, `kotlin-implementer.md:71` | 수정 필요 |
| X-35 (A-10) | 에이전트↔스킬 산출물 목록이 서로를 덮지 않는다 (3건) | 권고 | `migration-safety-gate/SKILL.md:209`, `api-contract-freeze/SKILL.md:189`, `contract-keeper.md:122` | 수정 필요 |
| — (A-1) | 프론트매터·상호 참조·수치 주장 전수 확인 — **검토함, 지적 없음** | 통과 | 에이전트 6 / 스킬 6 / 스크립트 3 | 유지 |
| X-36 | `00_progress.md`의 4개 행이 사실과 어긋난다 (§2 참조) | 수정 필요 | `00_progress.md:51,71,72,73` + `/auth` 3개 누락 | 수정 필요 |

## 2. `00_progress.md` 정정 항목 (Claude 1차, codex 미대상)

| 행 | 현재 기록 | 사실 |
|---|---|---|
| `:71` | `PUT /conversions/{id}` 캐시 헤더 — 수정 진행 중 | **완료** (`documents.py:343` + `test_documents.py:935`) |
| `:73` | parity 게이트 우회 — 수정 진행 중 | **완료** (`compare_parity.py:75` `EXPECTED_DOMAINS`, `:605` 도메인 누락 exit 1) |
| — | `/auth` 3개 헤더 추가가 표에 **없다** | `:71`보다 나중이고 더 큰 변경 |
| `:51`, `:72` | "fixture 11 도메인은 **준비됨**" | **`parity/` 디렉터리가 존재하지 않는다.** 생성기(빌더)만 준비됨 |

`00_progress.md:8`이 정한 "근거가 비어 있는 `예`는 `아니오`로 취급한다"를 산출물 존재 주장에도 같이 적용해야 한다.

## 3. 상충 항목 — 어느 쪽도 삭제하지 않는다

### 상충-1 — 부분 parity 검증의 exit 0을 결함으로 볼 것인가

**codex #3 [high] 원문:**

> `--only`, `--only-domain`, 단일 fixture, 도메인 디렉터리는 10개 도메인을 생략해도 마지막에 0을 반환한다. 단일 masking 가짜 케이스로 실행했을 때 출력은 '기대 집합 11개 중 10개는 돌리지 않았다'고 경고하면서 실제 종료 코드는 0이었다. 종료 코드만 보는 CI·에이전트는 이를 전체 통과로 기록할 수 있고, 파일 상단의 '0은 전체 도메인 존재 시에만'이라는 계약과도 모순된다. (`compare_parity.py:614-621`)

**Claude 1차 H-1 원문 (같은 거동을 관측했으나 지적으로 올리지 않음):**

> (위 실행은 `--only-domain`이라 "게이트 아님"으로 정확히 표시됐다. 다만 그 라벨은 *범위*에 대한 것이고, `check_external`이 위조 증거를 인정한 것은 범위와 무관한 코드 경로다.)

**Claude 1차가 같은 블록을 반대 근거로 인용한 지점 (H-3):**

> 같은 하네스의 `compare_parity.py`는 **정확히 이 함정을 알고 막았다**: `if total_considered == 0: … return 1` (`:614-616`)

**이 회차의 제3 근거 (코드 확인, 판정 아님):** 종료 코드 분기의 실제 순서는 다음과 같다.

```
if missing and not total_problems: return 1     # 도메인 누락
if total_problems:                 return 1     # 불일치
if total_pending:                  return 2     # 미검증
if total_considered == 0:          return 1     # 비교 0건
if partial:  print("부분 검증 통과(게이트 아님): …"); return 0
print("전건 일치: …");                 return 0
```

- 두 리뷰는 **같은 블록의 서로 다른 분기**를 인용했다. `total_considered == 0` 검사가 `partial`보다 **앞**에 있으므로 "비교 0건"은 exit 1이 맞고(Claude 인용 정확), 케이스가 1건 이상인 부분 실행은 exit 0이 맞다(codex 인용 정확). **사실 관계에 상충은 없다.**
- 상충하는 것은 판단이다 — **"게이트 아님" 라벨이 stdout에만 있고 종료 코드가 0인 것으로 충분한가.**
- 참고: codex #1이 제시한 시나리오(11개 도메인 이름별 가짜 케이스 1건씩)에서는 `missing`이 비고 `partial`이 False가 되어 **"부분 검증 통과"가 아니라 "전건 일치" + exit 0**이 나온다. 즉 X-11/X-2 경로는 이 라벨 논쟁을 우회한다.

**→ 리더 판단 요청.** 판정 질문: 종료 코드로 부분 실행을 구분할 것인가(codex 권고: 별도 비zero, 예 3), 라벨 + 구조화 출력으로 충분한가.

### 상충-2 — 하네스 결함의 심각도 척도가 다르다 (판정 필요)

| | Claude 1차 | codex 2회차 |
|---|---|---|
| 총평 | **차단(Critical) 0건** | verdict `needs-attention` / "출하 불가" |
| 근거 | §5 Phase 7의 6개 즉시 중단 기준(Fernet 복호화 실패 / 타 사용자 노출·404 위반 / 마스킹 전 전송 / 중복 LLM 호출·유실 / 문서 fixture 불일치 / 최대 2회 호출 위반)에 **해당하는 사실이 하나도 확인되지 않았다** | X-1·X-2를 `critical`로, "핵심 하네스 세 곳 모두 실제 검증 없이 성공할 수 있다" |
| 척도 | 계획 §5 Phase 7 발생 여부 | codex 자체 척도 (계획 문서 기준 미명시) |

**양쪽 논거를 병기한다.**

- Claude 쪽: 마이그레이션 Kotlin 코드가 아직 한 줄도 없다. 즉시 중단 기준은 **사건**을 서술하며, 사건이 없으므로 차단 0건이다.
- codex 쪽: 하네스는 그 사건들을 **탐지하는 장치**다. X-1·X-2·X-11이 살아 있으면 Fernet 복호화 실패·fixture 불일치·최대 2회 호출 위반을 **탐지할 수 없는 채로** 통과 기록이 쌓인다.

**판정이 걸린 곳**: `codex-review` 스킬 §5 규칙 5 — "Critical이 하나라도 남으면 Phase 종료를 보고하지 않는다."
**현재의 실무 영향은 제한적이다** — 이번 회차는 Phase 0 **착수 전** 점검이고 Phase 0 종료 판정이 안건이 아니다. 다만 §5의 판정을 지금 내려 두지 않으면 Phase 0 종료 시점에 같은 논쟁이 반복된다.

**→ 리더 판단 요청.** 판정 질문: "탐지 장치의 무력화"를 §5 Phase 7 척도의 Critical로 승격할 것인가.

## 4. 종합 기준 Phase 종료 조건 대비 현황

**이번 회차는 Phase 0 착수 전 하네스 정비이므로 Phase 0 종료 조건을 움직이지 않는다.** `00_progress.md`의 10개 종료 조건은 전부 `아니오`이며, 두 리뷰 중 어느 쪽도 이를 `예`로 바꿀 근거를 제시하지 않았다.

| 항목 | 종합 판정 |
|---|---|
| Phase 0 종료 조건 (암호문을 Kotlin에서 안전하게 읽을 경로 + 문서 포팅 가능성 확인) | **미착수.** 양측 모두 이 조건에 닿는 증거를 제시하지 않았다 |
| §6 Contract 게이트 | **미충족.** `contracts/easy-doc-v1.yaml` 미작성. 추가로 X-14(§1 표 2개 누락)·X-15(오류 응답 미규정)·X-23(계약 테스트 8/10)이 미해결 |
| §6 Security 게이트 | **판정 불가.** X-4의 BLOCK 후보 2건이 미판정이고 `{phase}_privacy-gate_scan.md`가 존재하지 않는다 |
| §6 Parity 게이트 | **판정 불가.** `parity/` 디렉터리 미생성. 비교기에 X-1·X-2·X-11·X-6이 미해결 |
| §5 Phase 7 즉시 중단 기준 6개 | 발생 사실 **0건** (Claude) / 탐지 능력 **미확보** (codex) — 상충-2 참조 |
| 리뷰 게이트 3단계 | **이 문서로 닫힌다.** 1단계 병렬 독립 실행 완료, 2단계 두 산출물 존재 확인, 3단계 교차 종합 = 본 문서 |

## 5. 우선 조치 순서

판정 기준은 **"그 게이트가 언제 처음 실제로 쓰이는가"**다. 현 상태: 마이그레이션 Kotlin 코드 0줄, 하네스·캐시 헤더 수정이 커밋 2건(`0fafac7`, `e88db3e`)으로 존재, 다음 단계는 Phase 0(계약 동결 · crypto/문서 spike).

### Tier A — Phase 0 착수 **전**에 고친다

이 게이트들은 Phase 0의 첫 동작에서 곧바로 쓰이거나, **이미 지금 오답을 내고 있다.** 전부 비용이 작다.

| 순위 | 항목 | 출처 | 왜 착수 전인가 |
|---|---|---|---|
| **A1** | X-7 + X-29 — `codex-review.sh` 출력 미검증 / `--dry-run` exit 0 / focus 없는 adversarial 허용 | 양쪽 + Claude | **모든 Phase의 모든 리뷰 게이트가 이 한 스크립트를 지난다. 이번 회차의 codex 리뷰도 이미 지났다.** 첫 실사용 시점 = 지금. 이것이 깨진 채로는 이후 어떤 게이트 통과 기록도 신뢰할 수 없다 |
| **A2** | X-24 — `{scope}` 슬러그 3갈래 | Claude | Phase 0 리뷰 게이트에서 `00_contract-yaml_*` / `00_contract_*`로 갈린다. **2차 교차 종합이 입력 파일을 못 찾는 실패**로 직결 — 즉 이 문서와 같은 산출물이 다음 Phase에서 만들어지지 않는다. 비용: 문자열 통일 |
| **A3** | X-25 — `TaskCreate`/`TaskList`/`TaskUpdate` 부재 | Claude | 오케스트레이터가 Phase 0 착수 **첫 동작**에서 호출한다. 비용: 문서 수정 |
| **A4** | X-14 — 계약 스킬 §1 표가 §2.5보다 2개 적음 | Claude | **Phase 0의 작업 항목이 이 문서를 원본으로 `contracts/easy-doc-v1.yaml`을 쓴다.** §1만 보고 쓰면 `GET /conversions/{id}/export`·`PATCH /workspaces/{id}`가 헤더 요구 없이 동결된다. **동결된 계약을 되돌리는 비용이 지금 표 두 줄을 고치는 비용보다 훨씬 크다** |
| **A5** | X-4 — 전수 스캔 exit 1, BLOCK 후보 2건 미판정 | 양쪽 | privacy-gate가 Phase 0 종료 감사를 하려면 **깨끗한 기준선**이 필요하다. 기준선이 지금 빨간불이면 이후 모든 스캔 결과가 "원래 빨간불"로 무시된다 — 그때 진짜 위반이 지나간다. 비용: 판정 + `{phase}_privacy-gate_scan.md` 기록 |
| **A6** | X-36 — `00_progress.md` 4개 행 정정 | Claude | 리더가 **다음 단계를 결정할 때 읽는 문서**다. "fixture 준비됨"이 산출물 존재로 읽히면 Phase 0 계획이 틀어진다. 비용: 거의 0 |

### Tier B — Phase 0와 **병행** (Phase 0 종료 판정 전 필수)

| 순위 | 항목 | 출처 | 왜 Phase 0 종료 전인가 |
|---|---|---|---|
| **B1** | X-1 — proof 파일 위조 가능 | **양쪽 (둘 다 재현)** | **Phase 0 종료 조건이 "암호문을 Kotlin에서 안전하게 읽을 경로가 확인됨"이고, 그 확인이 `verify-crypto.verified.json` 경로를 처음 쓴다.** 즉 Phase 0 종료 판정의 증거가 바로 이 위조 가능한 파일이다. Phase 2가 아니라 **Phase 0 종료 전**이다 |
| **B2** | X-2 + X-11 — 축소·손작성 fixture 신뢰, `runtime` 미검사 | **양쪽 + codex** | B1과 같은 이유. Phase 0 crypto spike의 fixture가 첫 사용이다. X-11(`runtime` 미검사)은 "Kotlin이 실제로 돌았는가"를 묻는 유일한 필드를 아무도 읽지 않는다는 뜻이므로 B1과 한 묶음으로 고친다 |
| **B3** | X-15 + X-17 — 오류 응답 캐시 헤더 미규정 + Python/Spring 거동 반대 | Claude | **Phase 0의 작업 그 자체(계약 동결)다.** 지금 `(엔드포인트, 상태코드)` 쌍으로 고정하지 않으면 Phase 3~4에서 어느 쪽도 위반이 아니게 되어 parity 판정이 불가능해진다 |
| **B4** | X-23 + X-22 — 계약 테스트 8/10, 비밀번호 미에코 단언 부재 | Claude | Phase 0의 "계약을 contract test로 고정" 작업 항목. 계약 §5가 "테스트 없는 항목은 동결된 것이 아니다"라고 정했으므로 10개 동결에는 테스트 10개가 필요하다 |
| **B5** | X-26 — Phase 종료 판정 강제 도구 부재 | Claude | **Phase 0 종료 판정에서 처음 필요해진다.** 이번 회차 지적 대부분이 "검증 없이 통과"였다는 점에서, 게이트 조합 자체에 같은 계열 결함이 있다 |
| **B6** | X-18 — `Clock` 주입 요구가 `kotlin-spring-conventions`에 없음 | Claude | 실사용은 Phase 3이지만 **비용이 문서 한 줄**이고, 놓치면 `Instant.now()` 내부 호출 구현이 나와 Phase 3 이후 재작업이 된다. 싸게 지금 적는다 |
| **B7** | X-21 — `verify_crypto` 예외 뭉갬 | Claude | Phase 0 crypto spike에서 **바로 겪는다.** 입력 파일 결함과 암호 비호환이 같은 문구로 남으면 spike 실패 원인을 오진한다 |
| **B8** | X-12 — 진짜 proof도 fixture 요구 집합과 결합돼 있지 않음 | codex 단독 (미검증) | B1과 같은 자리를 고치므로 함께 확인한다. **근거 확인이 먼저다** — 이번 회차는 소비자 측만 확인했다 |

### Tier C — 나중 (첫 실사용 Phase에 맞춰)

| 순위 | 항목 | 출처 | 첫 실사용 시점 / 마감 |
|---|---|---|---|
| **C1** | X-10 — `--changed`가 커밋된 브랜치 변경 제외 | codex 단독 (실재 확인) | Kotlin 코드가 커밋되기 시작하는 **Phase 1~2**. 다만 지금도 오답(exit 0)을 내므로 **Phase 1 착수 전까지** |
| **C2** | X-8 — 0개 파일 exit 0 | 양쪽 | C1과 같은 자리. 함께 고친다 |
| **C3** | X-13 + X-30 + X-31 — `sanctioned` 과다 면제, `LLM-RAW-INPUT` 전방탐색 | codex + Claude | `backend-kotlin/` 생성 시점 = **Phase 1**. LLM 어댑터가 생기는 **Phase 5 전 필수** |
| **C4** | X-3 + X-5 — 12개 규칙 줄 단위 우회, `CACHE-HEADER` 무력 | **양쪽** | Kotlin 보안 코드 = **Phase 3~5**. AST 재작성은 비용이 크므로 **Phase 2 종료 전** 착수. 단 `LLM-RAW-INPUT`(BLOCK, 즉시 중단 기준 직결)의 멀티라인 우회는 **Phase 5 전 필수** |
| **C5** | 상충-1 (X-3 인접) — 부분 검증 exit 0 | **상충** | Phase 2 parity 판정. **리더 판정 후** 착수 |
| **C6** | X-6 — `float_tol` 무제한 | 양쪽 | 현재 fixture에 float 없음 → **잠재적**. Phase 2 전 |
| **C7** | X-19 — argon2 역방향 부재 | Claude | Phase 3 종료 판정 + 절체 롤백 안전성. **Phase 3 전**. 롤백 시 관찰 기간 신규 가입자 전원 로그인 불가로 이어지므로 Phase 3에서 미루지 않는다 |
| **C8** | X-20 — `verify_crypto` 시각 고정 | Claude | **조건부** — Fernet TTL을 켜는 변경이 들어올 때 |
| **C9** | X-27 — 리뷰 게이트 면제 목록 불일치 | Claude | **사용자 판단 필요.** 판정 전까지 보수적으로(면제 없이) 운용 |
| **C10** | X-28, X-32~X-35, X-16 — 권한·파일명·§번호·산출물 목록·"6→10" 정정 | Claude | 병행 가능. 비용 작음. 다음 문서 수정 회차에 묶어 처리 |

### 한 줄 요약

**Tier A 6건(전부 문서·스크립트 수정, 반나절 이하)을 먼저 끝내고 Phase 0에 착수한다.** Tier B 8건은 Phase 0 작업과 겹치므로 병행하되 **Phase 0 종료 판정 전에 닫는다** — 특히 B1·B2는 Phase 0 종료 조건의 증거를 만드는 바로 그 경로다. Tier C는 각 Phase 착수 전으로 배치하되, **잘못된 통과 기록이 쌓이기 전에** 고쳐야 하므로 해당 Phase의 첫 커밋보다 앞에 둔다.

## 6. 대조하지 못한 범위 · 종합 중 발견

### 6.1 codex 리뷰 부재·실패로 대조하지 못한 범위

| 범위 | 사유 |
|---|---|
| codex 1회차 지적 | codex 1회차는 산출물 없이 취소됐다(`00_pre-phase0_codex-reviewer.md` §1.1). **회차 간 지적 비교는 불가능하다.** codex 측 지적은 전부 2회차 산출이다 |
| `app/api/workspaces.py`, `tests/api/test_workspaces.py` | `main...HEAD` diff 25개 파일에 없다. X-23(계약 테스트 8/10)·X-14(§1 표) 근거의 일부가 codex 리뷰 범위 밖 |
| 축 D의 "422 본문에 업로드 **파일명**이 실리는가" | codex 원문에 개별 언급이 없다. codex 산출물 §5가 "원문만으로는 판별되지 않는다"고 기록. Claude S-2는 6종 프로브에 파일명 케이스를 포함하지 않았다 — **양측 미확인** |
| codex 실행 중 pytest 1회 실패의 원인 | job 로그에 exit 1이 1회 관측되나 원문에 설명이 없다. 양측 모두 추정하지 않음 |
| codex의 독립성 100% 보장 | focus text로 `reviews/` 열람을 금지했고 job 로그에 제외 패턴이 관측되나, 전 구간 미열람을 산출물만으로 보장하지는 못한다 |

### 6.2 종합 중 발견 — 미교차

**신규 결함 지적: 없음.** 이 회차에서 새로 발견한 결함은 없다.

아래 두 건은 **결함 지적이 아니라 기존 지적의 사실 관계를 좁힌 제3 근거**이므로 별건으로 올리지 않는다.

1. `compare_parity.py`의 종료 코드 분기 순서 — `total_considered == 0` 검사가 `partial` 분기보다 **앞**에 있다. 이 사실이 상충-1에서 두 리뷰의 인용이 모순되지 않음을 보인다 (§3 상충-1).
2. `scan_privacy_invariants.py:220-225` — `--changed`의 git 조회가 실패하면 **전수 검사로 폴백**한다(`return iter_files(False)`). X-10·X-8이 지적한 "0개 파일 exit 0"과 달리 이 경로는 안전 방향으로 설계돼 있다. 두 리뷰 모두 언급하지 않았으나, X-8을 고칠 때 **같은 파일 안에 이미 올바른 선례가 있다**는 근거가 된다.

### 6.3 이 회차에서 하지 않은 것 (프로토콜 확인)

- Claude 1차 지적의 심각도를 **재부여하지 않았다.** 양측 값을 병기했고, 척도가 갈리는 지점은 상충-2로 올렸다.
- codex 지적 중 **기각한 것이 없다.** codex 단독 3건(X-10·X-11·X-13)은 코드로 실재를 확인했고, X-12는 "근거 확인 필요"로 남겼다.
- 코드를 수정하지 않았다. 검증은 읽기 전용(`sed`/`grep`/`git`)으로만 수행했다.

---

**이 문서가 Phase 00 · pre-phase0 리뷰 게이트의 정본이다.** Phase 종료 여부의 **판정**은 오케스트레이터가 이 근거로 내린다.
미결 판단 2건(상충-1, 상충-2)과 사용자 판단 2건(X-15, X-27)이 열려 있다.
