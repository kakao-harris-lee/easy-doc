# Kotlin 마이그레이션 진행 상태

**기준 문서:** `docs/plans/2026-08-11-kotlin-react-migration.md`
**실행 모드:** 초기 실행 (2026-08-11 착수)
**갱신 규칙:** 에이전트는 자기가 담당한 행만 고치고 `마지막 갱신 주체`에 자기 이름을 적는다. 다른 에이전트의 행은 건드리지 않는다.

> `충족`은 `예`/`아니오` 둘 중 하나만 쓴다. `진행 중`·`대체로`는 쓰지 않는다 — 게이트는 이분법이어야 판정된다.
> 근거가 비어 있는 `예`는 `아니오`로 취급한다.

---

## Phase 0 — 범위·계약 동결

계획 문서 §5 Phase 0. 종료 조건: **Kotlin이 기존 암호문을 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨.** 확인되지 않으면 일정 산정부터 다시 한다.

| 종료 조건 | 충족 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---|---|---|---|---|
| `contracts/easy-doc-v1.yaml` 작성 | 아니오 | - | 미착수 | contract-keeper | leader |
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 아니오 | - | 미착수 | contract-keeper | leader |
| FastAPI OpenAPI·계약 파일·React 타입 3자 대조 | 아니오 | - | 미착수 | contract-keeper | leader |
| 대상 DB와 보존할 파일럿 데이터 유무 확인 | 아니오 | - | 사용자 확인 필요 (계획 §9-2) | 사용자 | leader |
| 범위 승인: 런타임만 Kotlin화 vs 오프라인 도구까지 Python 제거 | 아니오 | - | 사용자 승인 필요 (계획 §9-1) | 사용자 | leader |
| Fernet JVM 호환 spike | 아니오 | - | 미착수. 실패 시 즉흥 암호 구현 금지, 재암호화 별건 분리 (§4.3) | privacy-gate | leader |
| Argon2 PHC 검증 spike | 아니오 | - | 미착수 | privacy-gate | leader |
| JWT 양방향 호환 spike | 아니오 | - | 미착수 | privacy-gate | leader |
| DOCX/PDF/HWPX 라이브러리 spike | 아니오 | - | 미착수. Python docx 추출기가 비공개 XML 요소까지 순회하므로 POI 단순 추출로는 부족할 수 있음 (§4.5) | kotlin-implementer | leader |
| 리뷰 게이트 Critical 0건 | 아니오 | 1회차 실행 완료 — `reviews/00_pre-phase0_{codex-reviewer,migration-reviewer,cross}.md` 3건 (정본은 `_cross.md`) | **Critical 2건이 열려 있다** (codex 척도 critical: X-1 proof 파일 위조 가능 · X-2 fixture 출처 미검증). 심각도 척도 상충(상충-2)도 리더 판단 대기. 1회차는 Phase 0 **착수 전** 점검이라 종료 리뷰는 별건이다 | migration-reviewer | migration-reviewer |

### Phase 0에서 사용자 승인이 필요한 다섯 결정 (계획 §9)

| # | 결정 | 상태 |
|---|---|---|
| 1 | 목표가 "제품 런타임 Kotlin화"인지 "오프라인 도구 포함 Python 완전 제거"인지 | 미승인 |
| 2 | 파일럿/보존 대상 DB가 있는지, 유지보수 창을 쓸 수 있는지 | 미승인 |
| 3 | Fernet JVM 호환 구현 승인 여부와 실패 시 재암호화 방식 | 미승인 |
| 4 | PostgreSQL 작업 큐로 전환하며 Redis를 최종 제거할지 | 미승인 |
| 5 | 시각 UI 개편을 이번 전환과 분리하는 원칙 승인 | 미승인 |

**승인 없이 Phase 1로 넘어가지 않는다.**

---

## 아직 돌리지 않은 검증 게이트 (계획 §6)

| 게이트 | 상태 |
|---|---|
| Build (Gradle, TypeScript) | 미실행 — `backend-kotlin/` 미생성 |
| Unit (core, application, React) | 미실행 |
| Contract (14 endpoints) | 미실행 — 계약 파일 미작성 |
| DB (Testcontainers) | 미실행 |
| Crypto (Python ↔ Kotlin) | 미실행 — fixture **생성기**가 11개 도메인을 지원할 뿐, **`parity/fixtures/` 산출물은 저장소에 존재하지 않는다**(`parity/` 디렉터리 자체가 없음). Kotlin 측도 부재 |
| Document (docx/pdf/hwpx/txt) | 미실행 |
| Worker (lease/retry/crash) | 미실행 |
| Quality (골든셋) | 미실행 |
| Security (소유권·로그·캐시) | 미실행 |
| E2E (compose + browser) | 미실행 |
| Ops (cutover/rollback) | 미실행 |

**돌리지 않은 게이트를 통과한 것처럼 보고하지 않는다.**

---

## 착수 전 정리된 선행 작업 (하네스 구축 중 확인·수정)

Phase 0 착수 전에 하네스 구축 과정에서 발견해 처리한 항목이다. 마이그레이션 Phase 자체는 아니지만 기준선에 영향을 주므로 남긴다.

| 항목 | 내용 | 상태 |
|---|---|---|
| 계약 사실 정정 | 계획 §2.2에 없는 **413**(10MB 초과) 실재. 오류 본문 `detail`이 문자열/객체배열 **union**. 401에 `WWW-Authenticate: Bearer`. 엔드포인트는 제품 13 + `/health` = 14 | 반영됨 (`api-contract-freeze`) |
| 502/503 구분 | 큐 **등록 실패** = 502(`QueueUnavailableError`), 큐/설정 **미배선** = 503(`ConfigurationError`, `app/api/deps.py`) | 반영됨 |
| `PUT /conversions/{id}` 캐시 헤더 누락 | GET과 같은 스키마라 `masked_items[].original`에 개인정보가 실리는데 `no-store`/`nosniff` 부재 | **완료** — 커밋 `0fafac7`. 회귀 테스트 `tests/api/test_documents.py::test_검수_저장_응답은_캐시하지_않는다` |
| `/auth` 3종 캐시 헤더 누락 | `POST /auth/signup`·`POST /auth/login`·`GET /auth/me`가 `PRIVATE_RESPONSE_HEADERS`를 import조차 하지 않았음. 로그인 응답 본문은 Bearer 토큰 자체, 나머지 둘은 이메일 | **완료** — 커밋 `0fafac7`(같은 커밋). 회귀 테스트 3건 `tests/api/test_auth.py::test_{가입,로그인,내_정보}_응답은_캐시하지_않는다` |
| 캐시 금지 헤더 대상 범위 | 위 두 건으로 대상이 **6개 → 10개**로 늘었다 (documents 4 · workspaces 3 · auth 3). 계약 스킬 §2.5가 정본이고 §1 표는 포인터 표시만 둔다 | **완료** — 코드 확인: `grep -rn "headers.update(PRIVATE_RESPONSE_HEADERS)\|\*\*PRIVATE_RESPONSE_HEADERS" app/api/ \| wc -l` = 10 |
| parity 게이트 공백 | crypto 도메인이 Fernet만 검증. JWT·Argon2 fixture 부재 상태로 Crypto 게이트가 닫혔음 | 수정됨 — 커밋 `e88db3e`. 생성기에 jwt 18건·argon2 14건 추가 (11 도메인). **fixture 산출물은 아직 없음** |
| parity 게이트 우회 | 도메인 디렉터리를 통째로 빼면 "전건 일치"로 통과 | **완료** — 커밋 `e88db3e`. `compare_parity.py`의 `EXPECTED_DOMAINS` 검사 + 도메인 누락 시 exit 1 |
| 리뷰 게이트 1회차 | Phase 0 착수 **전** 점검으로 codex·Claude 독립 리뷰 + 교차 종합을 실행 | **완료** — `reviews/00_pre-phase0_{codex-reviewer,migration-reviewer,cross}.md` 3건. 교차 결과 **합의 9건 · codex 단독 4건 · Claude 단독 23건 · 상충 2건**. 정본은 `_cross.md` |
| 계약 스킬 §1↔§2.5 불일치 | §2.5는 헤더 대상 10개인데 §1 표에는 8개만 표기(`GET /conversions/{id}/export`·`PATCH /workspaces/{id}` 누락). §1만 보고 계약을 쓰면 두 곳이 헤더 요구 없이 동결됨 (X-14) | **완료** — 코드 기준으로 §1을 §2.5에 맞추고, §2.5를 정본으로 선언 |
| 오류 응답 캐시 헤더 | 오류 경로(`app/api/errors.py`)에 헤더를 붙일지 미결 (X-15) | **완료 — 현행 유지 판정**(붙이지 않음). 근거: 오류 본문에 개인정보 없음을 실측 확인. `api-contract-freeze` §2.7 해결 3에 전제 파기 조건과 함께 기록 |
| `status` 필드 넓이 | 백엔드 Pydantic `str` vs React 4값 리터럴 union. OpenAPI 생성 타입으로 그냥 교체하면 타입 안전성 퇴보 | Phase 6 전까지 계약 파일에서 enum 고정 필요 |
