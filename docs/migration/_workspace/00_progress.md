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
| 리뷰 게이트 Critical 0건 | 아니오 | - | 미착수 | migration-reviewer | leader |

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
| Crypto (Python ↔ Kotlin) | 미실행 — fixture 11 도메인은 준비됨, Kotlin 측 부재 |
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
| `PUT /conversions/{id}` 캐시 헤더 누락 | GET과 같은 스키마라 `masked_items[].original`에 개인정보가 실리는데 `no-store`/`nosniff` 부재 | **수정 진행 중** (사용자 승인) |
| parity 게이트 공백 | crypto 도메인이 Fernet만 검증. JWT·Argon2 fixture 부재 상태로 Crypto 게이트가 닫혔음 | 수정됨 — jwt 18건·argon2 14건 추가 (11 도메인) |
| parity 게이트 우회 | 도메인 디렉터리를 통째로 빼면 "전건 일치"로 통과 | **수정 진행 중** |
| `status` 필드 넓이 | 백엔드 Pydantic `str` vs React 4값 리터럴 union. OpenAPI 생성 타입으로 그냥 교체하면 타입 안전성 퇴보 | Phase 6 전까지 계약 파일에서 enum 고정 필요 |
