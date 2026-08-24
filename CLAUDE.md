# CLAUDE.md — Easy-Read AI 개발 지침

공공기관용 '쉬운 글' 자동 변환 SaaS. 전체 기획·정책·우선순위는 `docs/master-plan.md`가 단일 기준 문서(SSOT)다. 기능 작업 전 반드시 해당 문서의 우선순위(4장)와 정책 결정(3장)을 확인할 것.

## 개발 단계

현재 단계: **Lean MVP (master-plan 4.0)**. Lean MVP 범위 밖 기능(PG 결제, RAG 사전, 어드민 등)은 사용자가 명시적으로 요청하지 않는 한 구현하지 않는다. 범위가 애매하면 구현 전에 물어볼 것.

## 구현 전 리서치·계획 (필수 — 2026-08-19 사용자 지시)

**모든 기능 구현은 리서치가 기본이다. 바퀴를 매번 새로 만들지 않는다.** 기능 단위(Phase의 `{scope}` 하나, 또는 그에 준하는 작업 덩어리)에 착수하기 전에 아래 세 가지를 **순서대로** 끝내고, 그 결과를 계획 문서로 남긴 뒤에만 구현 에이전트를 띄운다. 리더가 위임할 때 이 세 항목을 프롬프트에 포함하고, 구현 에이전트는 계획이 없으면 먼저 계획을 쓴다.

1. **라이브러리·프레임워크 리서치** — 표준·검증된 구현이 있으면 그것을 쓴다(Spring Boot·Spring Security·Jackson·Bean Validation·JCA·POI·PDFBox·Flyway·Testcontainers 등). 공식 문서(context7 / 공식 레퍼런스)로 **현재 버전의 API와 권장 방식**을 확인한다 — 학습 데이터 기억에 의존하지 않는다. 직접 구현은 ① 요구사항이 라이브러리가 제공하지 않는 성질을 요구하거나(예: 마스킹 순서 불변식·AAD 규약) ② 의존성 추가가 보안·라이선스·범위 정책에 어긋날 때만 하고, 그 사유를 계획에 적는다.
2. **기구현 확인** — 같은 저장소에 이미 있는 것을 다시 만들지 않는다. `backend-kotlin/` 모듈(core·application·infrastructure·api·worker)의 기존 포트·유틸·테스트 지원 클래스, `contracts/`를 먼저 찾아본다. 같은 기능이 이미 있으면 **재사용·확장**이 기본이고 중복 구현은 결함으로 취급한다.
3. **계획 작성** — 위 두 결과를 바탕으로 "무엇을 어떤 라이브러리로, 기존 무엇을 재사용해, 어떤 순서로, 어떤 테스트로 검증하는가"를 적는다. 위치는 `docs/plans/`이고 날짜 접두(`YYYY-MM-DD-`)를 쓴다. 계획 없이 시작한 구현은 리뷰에서 그 사실 자체를 지적 대상으로 본다.

이 규칙은 프론트엔드·도구 스크립트·하네스(`.claude/**`) 변경에도 똑같이 적용한다. **강제자는 현재 리더의 위임 프롬프트와 리뷰의 지적 대상 판정이며, 자동 탐지는 없다**(2026-08-19 게이트 26 codex C-1 — 전칭 선언에 강제자 0).

## Python 제거 완결 (2026-08-24)

2026-08-12 재개발 전환(Python→Kotlin 재구현) 결정을 끝까지 진행해, **Python 애플리케이션·실행 환경·Python↔Kotlin parity 하네스·마이그레이션 진행 문서(`docs/migration/**`)를 저장소에서 전부 제거했다.** 근거·범위·실행 기록은 `docs/plans/2026-08-24-python-removal-for-kotlin-redevelopment.md`. 제거 직전 상태는 로컬 태그 `pre-python-removal-20260824`로 보존돼 있다.

이 절 아래는 이제 **순수 Kotlin/Spring Boot + React 개발 지침**이다. 더 이상 존재하지 않는 것: Python 애플리케이션(`app/`), Python 테스트(`tests/`), Alembic, arq, `uv`/`ruff`/`mypy`/`pytest` 명령, Python-Kotlin parity 비교(`parity/`), 마이그레이션 Phase 0~9 진행 추적(`docs/migration/_workspace/`), `kotlin-migration`·`python-kotlin-parity`·`migration-safety-gate`·`codex-review` 스킬과 `codex-reviewer`·`migration-reviewer`·`parity-verifier`·`privacy-gate` 에이전트. 이 스킬·에이전트를 트리거하는 요청("코틀린 전환", "parity 검증", "Phase N 종료" 등)을 받으면 **이 절을 근거로 하네스가 없다는 사실을 안내**하고, 필요한 작업은 아래 일반 Kotlin 지침과 `kotlin-spring-conventions`·`api-contract-freeze` 스킬(둘은 존치, Python 의존이 없다)로 직접 수행한다.

**Kotlin 재개발 backlog(미구현 항목)와 이 저장소가 과거에 확정한 요구사항(마스킹 범주, 저장 암호화 AEAD 성질, Argon2/JWT 정확성 요건, DOCX/PDF/HWPX 파싱 방식 등)은 `docs/kotlin-redevelopment-backlog.md`에 옮겨 적었다** — 마이그레이션 문서가 지워지기 전에 그 문서들이 유일한 출처였던 결정을 이곳으로 이월했다. 새로 발견되는 요구사항은 이 문서에 추가한다.

**변경 이력 (2026-08-24 이전 — 지금은 없는 하네스에 대한 역사 기록. 옮기지 않는다):**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-08-11 | 초기 구성 (에이전트 6, 스킬 6) | 전체 | Kotlin 마이그레이션 착수 |
| 2026-08-11 | 독립 검증 결함 수정 (Critical 5·Major 10·Minor 7) | 에이전트 6, 스킬 6 | 병렬 작성으로 생긴 레인 간 규약 드리프트(리뷰 파일명·fixture 도메인명·mismatch 파일명), 리뷰 게이트가 병렬 호출만으로는 닫히지 않던 문제, 스킬 간 트리거 충돌 |
| 2026-08-11 | 계약 표 503 경로 보강 | skills/api-contract-freeze | codex stop-time 게이트 지적 — `POST /documents`의 "큐 미준비 → 503"(`app/api/deps.py`) 경로가 표에서 누락됨 |
| 2026-08-12 | 방향 전환 3건: ① 검증 기준을 "Python 출력 일치"에서 "요구사항 충족"으로, ② 마스킹 범주 5종 → 2종, ③ API 계약 v1을 동결이 아닌 개선 대상으로 | docs/master-plan.md(3.2·6.2·7·8), CLAUDE.md | 사용자 결정 — Python의 반복 회귀가 전환 계기이므로 Python을 정답으로 삼지 않는다. 마스킹은 공용 문서 중심 용도 판단으로 고위험 2종만 유지(전화·이메일·계좌 유입 시 무마스킹 전송 위험 감수). 하위 스킬·에이전트·`contracts/` 반영은 별건 |
| 2026-08-12 | 규칙 추가 — "선언한 범위와 실제 도달을 대조한다"(규칙 6개 + 실패 7건 근거) | skills/kotlin-migration(전문)·CLAUDE.md·skills/codex-review·agents/migration-reviewer·skills/migration-safety-gate·skills/api-contract-freeze·skills/python-kotlin-parity(포인터) | 같은 형태의 실패 7건 — 사적 헤더 필터 미도달, 계약이 "모든 응답"을 표현 못 함, parity 게이트가 계약 대신 자기 fixture와 대조, 원장 기록 실행이 성공 코드, `mypy .`가 점 디렉터리를 건너뜀, 품질 게이트 CI 도달 0, `.gitignore` 전역 패턴이 근거보다 넓어 이상 징후를 은폐 |
| 2026-08-13 | 규칙 4 판정 기준 교체(횟수 → 결함의 구조: 구조적 재발인가 / 탐지인가 은폐인가) + 근거 7번 재기술 + 규칙 3에 `실행 경로` 열·어휘 6종·강제 테스트 신설 | skills/kotlin-migration(전문)·CLAUDE.md·`docs/migration/_workspace/00_progress.md`(표 4개)·`tests/test_harness_scope_reach.py` | "두 번이면 전역, 한 번이면 그 자리만"은 세어서 답을 얻으려는 기준이라 기제를 묻지 않는다. 규칙 3은 문장뿐이라 **자기 도달이 0**이었다 |
| 2026-08-13 | 규칙 5에 복원 절차 추가 — 음성 대조 뒤 `cp` 로 되돌리지 않는다 | skills/kotlin-migration(규칙 5) | 같은 날 두 번 났다. `cp` 가 `cp -iv` 별칭이라 `-f` 를 줘도 프롬프트가 떠 무인 복원이 멈추고 **변조 파일이 디스크에 남는다**. 하나는 15시간 54분 대기(백업이 전날 것이라 완료됐다면 그날 작업 소실), 다른 하나는 47분 대기했는데 **그 파일이 가드 본체**였고 같은 시각 기준선 기록이 그것을 쓰고 있었다 |
| 2026-08-13 | mypy 도달 수정 — 개별 스크립트 경로 열거 → `.claude` 루트 명시 | CLAUDE.md·README.md·`.github/workflows/ci.yml`·agents/kotlin-implementer | 새 규칙 4를 첫 적용하다 발견. 주석은 `**/scripts/`로 **복수 선언**하는데 명령은 한 곳만 줘서 `migration-safety-gate/scripts/scan_privacy_invariants.py`가 **한 번도 타입 검사를 받지 않았다**(음성 대조: 오류 주입 시 옛 명령 `Success 126` / 새 명령 검출). 열거는 다음 스킬에서 또 벌어지므로 구조로 고쳤다 |
| 2026-08-19 | 절 신설 — 「구현 전 리서치·계획 (필수)」: 라이브러리·프레임워크 리서치(공식 문서/context7) → 기구현 확인 → 계획 작성 뒤에만 구현 착수, 바퀴 재발명 금지 | **완료:** CLAUDE.md(본문)·메모리(`research-first-no-reinvent`) / **미완료:** skills/kotlin-migration·agents/kotlin-implementer(하네스 반영 미착수 — 두 파일 적중 0건, 게이트 26 실측) | 사용자 지시 — "모든 기능을 구현할 때는 리서치가 기본이다. 라이브러리·프레임워크를 리서치하고 기구현을 확인하고 계획을 작성한다. 바퀴를 매번 새로 만들지 않는다" |
| 2026-08-14 | 규칙 5 복원 절차의 전제 갱신 — `cp -iv` 별칭 제거(사용자), 규칙은 유지 | skills/kotlin-migration(규칙 5) | 사고 3건은 **셋 다 별칭이 기제**였고(정지 2 + `-i` 비대화 거절로 백업 조용한 실패 1 — 초판이 셋째를 "별칭과 무관"으로 잘못 재분류했다가 stop-time 게이트에 잡혀 정정) 별칭 제거로 소멸. 규칙 유지의 별칭 무관 근거: `cp` 복원은 사본의 최신성·복원 내용을 **증명하지 않으며**, 민짜 `cp` 는 낡은 사본을 프롬프트 없이 덮는다(15시간 54분 사건의 전날 백업 — 그 낡음은 절차 결함이었고 별칭의 정지가 우연히 손실을 막았다). git 경유 + sha256 대조가 그 증명을 제공한다 |
| 2026-08-21 | **하네스 비용 구조 개조 4건** — ① 라쳇 상환(규칙 8 신설: 인상 시점 = Phase 경계) ② 게이트 깊이 상한(규칙 7 신설: 열거형은 3층, 4층 금지) ③ 원장 2단 분리(`00_progress.md` 3,123 → 1,276줄 + `00_progress-archive.md`) ④ 리뷰 회차 트리거를 커밋 기반 → **성질 기반**(4축에 닿으면 필수, 그 밖은 Phase·회차 단위로 묶음. 면제 목록 불변) | CLAUDE.md · skills/kotlin-migration(규칙 7·8, 작업 추적, 리뷰 게이트) · skills/codex-review(§2.1) · `tests/test_harness_scope_reach.py`(`read_progress_markdown` 2파일 합본 + `test_원장_파일이_전부_실재한다` 신설) · `tests/test_kotlin_gate_reach.py`(라쳇 주석 규약) · 계획 `docs/migration/_workspace/xx_harness_cost-restructure-plan.md` | 사용자 지시 — 사이클·토큰 과다 진단. 실측: 제품 Kotlin 15,375줄 대 메타 산출물 6:1(리뷰 43,157줄 / 하네스 가드 11,476줄 / 원장 3,123줄), 8/20→8/21 제품 파일 +4 에 메타 +3,001줄, `MIN_TEST_CLASSES` 라쳇이 하루에 5회 인상. **일정 자체는 계획(Phase 0~3 = 4.5~6주)보다 3배 빠르다** — 문제는 제품 1단위당 비용이고 기제는 규칙 4에 **탐지기 제거·상환 항이 없다**는 것 |
| 2026-08-21 | **위 ② 의 코드 제거분 철회** — `TEST_CLASS_COUNT` 를 없앴다가 되돌렸다. 규칙 7 의 「같은 것을 두 번 선언하지 않는다」 문장도 정정 | skills/kotlin-migration(규칙 7) · `tests/test_kotlin_gate_reach.py` · 계획 문서 §3-b·§5 | stop-time codex 게이트 지적 2건 — **가드 둘이 누락을 조용히 통과**하고 있었다. ⑴ 라쳇은 「함께 줄이기」를 막는 게 아니라 **하한 아래만** 막는다: 선언 111 · 하한 105 → **6개 창**, 유일한 backstop 이 오래된 Gradle 리포트(가드가 아니라 우연). 규칙 8 의 상환이 그 창을 **넓혀** 두 처방이 곱해졌다. ⑵ 아카이브를 지워도 검사기가 `EXIT=0` — 아카이브 대상 표가 0개라 `EXPECTED_TARGET_TABLES` 가 닿지 않았고 "그 축이 잡는다"는 주석이 거짓이었다. **직접 원인은 계획서가 음성 대조 V3·V4 를 적어 두고 실행하지 않은 채 읽기로 판정한 것**(규칙 2 가 금지하는 대리 측정) |
| 2026-08-24 | **Python 제거 완결** — 위 이력 전체가 관리하던 Python↔Kotlin parity 하네스(에이전트 6·스킬 6 중 `python-kotlin-parity`·`kotlin-migration`·`migration-safety-gate`·`codex-review`·해당 에이전트)와 `docs/migration/**`·`parity/**`·Python 애플리케이션을 저장소에서 제거했다 | 전체 | 사용자 지시 — `docs/plans/2026-08-24-python-removal-for-kotlin-redevelopment.md`. 재개발(Python 참고 구현 폐기) 방향의 최종 집행 |

## 기술 스택 (확정)

- **제품 런타임: Kotlin + Spring Boot / Gradle** — 유일한 런타임이다(2026-08-24 Python 실행 환경 제거로 "재개발 전환"이 완결됨)
- PostgreSQL + pgvector (단일 DB — ChromaDB 등 별도 벡터 DB 추가 금지)
- 비동기 작업: **PostgreSQL lease 기반 작업 큐** (arq + Redis 사용 금지 — 큐를 위해 두 번째 저장소를 운영하지 않는다). `infrastructure.queue.JdbcConversionQueue`는 있으나 `worker/` 모듈의 처리 루프는 미구현(backlog)
- Frontend: React + TypeScript (Vite)
- LLM: 자체 Provider 추상화 레이어 경유 (아래 '아키텍처 규칙')
- Python은 이 저장소에 없다. 새 도구·스크립트도 Python으로 작성하지 않는다 — 필요하면 Kotlin(Gradle 태스크) 또는 셸 스크립트를 쓴다.

## 명령어

```bash
cd backend-kotlin && ./gradlew build       # 컴파일 + ktlint + detekt + test
cd backend-kotlin && ./gradlew bootRun -p api   # API 개발 서버
cd frontend && npm run check               # tsc + eslint + prettier
cd frontend && npm run test -- --run       # frontend 테스트
docker compose up                          # 전체 스택(로컬)
```

커밋 전 필수 통과: `backend-kotlin`은 `./gradlew build`, `frontend`는 `npm run check && npm run test -- --run && npm run build`. CI(GitHub Actions)의 `kotlin`·`frontend`·`e2e` 잡이 같은 검증을 강제한다.

## 아키텍처 규칙

1. **LLM 추상화**: 모든 LLM 호출은 `core`의 `LlmProvider` 인터페이스를 통해서만 한다. 벤더 SDK를 서비스 코드에서 직접 import하지 않는다. 새 벤더는 `infrastructure`의 provider 구현체 추가로만 대응(현재 `AnthropicProvider`).
2. **마스킹 선행 (보안 불변식)**: 사용자 문서 텍스트는 `core`의 마스킹 파이프라인(`privacy` 패키지)을 통과한 후에만 `LlmProvider`에 전달될 수 있다. 이 순서를 우회하는 코드는 절대 작성하지 않는다. **마스킹 범주는 주민등록번호(외국인등록번호 포함)·카드번호 2종**(master-plan 3.2) — 전화번호·이메일·계좌번호는 마스킹되지 않고 그대로 LLM에 전달된다는 사실을 전제로 코드를 읽고 쓴다.
3. **레이어 분리**: `api`(컨트롤러, 요청/응답 DTO) → `application`(비즈니스 로직·유스케이스) → `infrastructure`(DB·외부 연동 어댑터) → `core`(순수 도메인). `api`·`worker`는 `infrastructure`를 런타임에만 붙인다(컴파일 시점 의존 금지 — `build.gradle.kts`의 `moduleBoundaryCheck`가 강제).
4. **쉬운 글 스타일 규칙**은 `core`에 상수/함수로 정의하고, 프롬프트 생성이 그 정의를 사용한다. 골든셋 평가 도구는 아직 Kotlin에 없다(backlog).

## 코딩 규칙

- **Kotlin 주석은 현재 코드만 설명한다.** 코드만으로 드러나지 않는 불변식·외부 계약·함정만 가장 가까운 선언에 짧게 남긴다. 리뷰 ID·날짜·커밋·실측 로그·이전 실패·기각한 대안·사건 이력은 `.kt`에 누적하지 말고 `docs/plans/`의 계획 산출물이나 커밋 메시지에 둔다. 기존 설명을 고칠 때는 새 절을 덧붙이지 말고 교체·압축한다. 테스트 의도는 우선 `@DisplayName`·테스트 이름·단언으로 표현한다.
- **테스트 `.kt`에도 같은 규약이 그대로 적용된다.** 주석 예산의 **분모만** 제품(`src/main/`)과 테스트(`src/test/`) 둘로 갈랐고, **무엇을 써도 되는가는 갈리지 않았다.** **허용**: 그 테스트가 고정하는 불변식, 음성 대조가 무엇을 재는지, 함정, `@DisplayName`. **금지**: 리뷰 ID·날짜·커밋 SHA·실측 로그·사건 이력·기각한 대안 — 제품과 **같다**.
- 주석·docstring·사용자 노출 문자열은 한국어, 코드 식별자는 영어.
- API 입출력은 반드시 데이터 클래스(요청/응답 DTO). `Map`·`Any` 반환 금지.
- 예외는 도메인 예외로 정의해 사용하고, 컨트롤러/전역 핸들러 레벨에서 HTTP 응답으로 변환.

## 테스트 규칙

- 새 기능 = 테스트 동반. 버그 수정 = 재현 테스트 먼저.
- 프롬프트·스타일 규칙·LLM 설정을 변경하면 관련 `core`/`infrastructure` 테스트를 실행해 결과를 보고한다.
- LLM 호출부 단위 테스트는 `FakeLlmProvider`(testFixtures)로 대체.
- 골든셋 자동 평가(스타일 규칙 검사 + LLM-as-judge)는 Kotlin에 아직 없다 — 재도입 시 이 절에 추가한다.

## 보안·데이터 규칙 (위반 금지)

- 로그에 문서 본문·개인정보를 절대 남기지 않는다. 로깅은 문서 ID·길이·처리 상태까지만.
- 비밀키는 `.env` + 환경변수만. 코드·커밋에 키 포함 금지.
- 업로드 원문은 암호화 저장, 기본 30일 후 자동 삭제 정책을 전제로 스키마를 설계한다 (master-plan 3.2).
- LLM 계약은 no-training 전제 — provider 구현체 추가 시 어댑터에 한 줄 계약 주석으로 명시.

## 하지 말 것

- Lean MVP 범위 밖 기능 선제 구현 (스코프 크리프 — v1 기획의 실패 요인)
- LangChain 체인으로 핵심 변환 로직 구성 (문서 로더 등 유틸만 선택적 사용; 변환 파이프라인은 직접 구현)
- 프론트에서 LLM 직접 호출, 벤더 SDK 직접 import
- '페이지' 용어 사용 — 과금·UI 용어는 '크레딧' (1,000자 = 1크레딧)
- 영업·안내 문구에 "의무화" 표현 (master-plan 1.2 화법 가이드 준수)
- 계약·처리방침·영업 문서에 마스킹 범주를 실제 구현(2종)보다 넓게 적기 (master-plan 3.2)
- Python으로 새 스크립트·도구 작성 (2026-08-24 Python 실행 환경 제거 — Kotlin/Gradle 태스크 또는 셸 스크립트를 쓴다)

## Definition of Done

기능 하나가 끝났다고 말하려면: `./gradlew build`(Kotlin 변경 시) 또는 `npm run check && npm run test -- --run && npm run build`(frontend 변경 시) 통과 + 로그에 개인정보 미포함 확인 + master-plan의 해당 기능 표와 어긋나는 점이 있으면 문서 갱신 제안까지.

판정 기준은 **"요구사항·정책(master-plan 3장·4장)을 충족하는가"**다. 대조할 Python 구현은 더 이상 없다(2026-08-24 제거). 정책 불변식(마스킹 선행·no-store·소유권 은닉 등)은 요구사항 자체가 그 형태를 지정한 것이므로 그대로 지킨다. 저장 암호화는 round-trip·변조 거부·키 회전·nonce 재사용 금지를 성질로 판정한다(값 동일성을 대조할 대상이 없다).
