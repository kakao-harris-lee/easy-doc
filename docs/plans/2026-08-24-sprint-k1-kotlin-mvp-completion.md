# Sprint K1 — Kotlin Lean MVP 실행 경로 완성

- 기간: 2026-08-24 시작, 완료일은 검증 후 기록
- 상태: 진행 중
- 기준: `docs/master-plan.md`의 Lean MVP와 `contracts/easy-doc-v1.yaml`
- 원칙: Kotlin 백엔드와 React 프런트엔드는 독립 프로젝트로 개발하고 HTTP API 계약으로만 연결한다.

## 1. 시작 상태

### 완료되어 있는 기반

- Kotlin/Gradle 멀티모듈 경계: `core` → `application` → `infrastructure` → `api`/`worker`
- 가입·로그인, 작업 공간, 문서 등록·목록·삭제, 변환 조회·검수 저장 API
- PostgreSQL/Flyway, AES-GCM 저장 암호화, PostgreSQL lease 큐 자료구조
- DOCX/PDF/HWPX 텍스트 추출과 입력 방어
- 마스킹 선행 타입 경계와 최대 2회 LLM 호출 유스케이스
- OpenAI/Anthropic provider 전략과 메트릭 decorator
- React 가입·로그인·업로드·변환 폴링·검수·기록 화면
- API 계약 테스트, Kotlin 단위/통합 테스트, React 단위 테스트, Playwright E2E 경로

### 아직 Lean MVP 실행 흐름을 막는 항목

1. `worker`가 lease를 획득해 마스킹 → LLM 호출 → 결과 저장까지 실행하지 않는다.
2. docx/txt 내보내기 HTTP 엔드포인트가 없다. 계약의 pdf 내보내기는 제품 범위와 함께 재결정해야 한다.
3. 기본 30일 보존 만료를 실행하는 삭제 작업이 없다.
4. Kotlin 골든셋 평가기가 없어 모델/프롬프트 변경의 품질 게이트가 닫히지 않는다.
5. Compose 전체 스택 검증과 GitHub Actions의 실행 경로가 분리되어 있다.

## 2. 스프린트 목표

사용자가 React 화면에서 문서를 등록하고, Kotlin worker가 변환을 완료하며, 담당자가 수정본을 저장하고 결과를 내려받을 수 있는 한 개의 수직 흐름을 Docker Compose로 재현한다.

## 3. 작업 순서

### K1-1. 프로젝트·에이전트 경계 고정

- [x] 루트는 통합 계약·Compose·문서만 소유한다.
- [x] `backend-kotlin/`, `frontend/`, `contracts/`, `docs/`별 작업 지침을 둔다.
- [x] 프런트 Dockerfile을 프런트 프로젝트 안으로 이동한다.
- [x] Python 시대 스프린트를 archive로 이동한다.

### K1-2. Compose 기반 검증·배포 경로

- [x] 로컬 전체 스택과 CI 검증 서비스를 Compose 파일에서 한눈에 확인할 수 있게 한다.
- [x] 백엔드·프런트 이미지가 각 프로젝트 디렉터리만 build context로 사용하게 한다.
- [x] CI는 Compose 설정 검증 후 백엔드·프런트 독립 게이트를 실행한다.
- [ ] 이미지 registry push와 실제 환경 배포는 대상 registry/호스트가 정해진 뒤 별도 승인으로 연결한다.

### K1-3. Worker 수직 흐름

- [ ] 실패 테스트로 lease 획득·갱신·완료·실패·재시도 계약을 고정한다.
- [ ] 마스킹된 타입만 `LlmProvider`에 전달한다.
- [ ] LLM 호출을 DB 트랜잭션 밖에서 실행한다.
- [ ] 중복 실행에도 완료 결과가 덮어써지지 않도록 fencing/CAS를 적용한다.
- [ ] Compose E2E에서 `pending → processing → done|failed`를 관찰한다.

### K1-4. 내보내기

- [ ] 계약과 master plan의 형식을 먼저 `docx|txt`로 일치시키거나 pdf 지원을 구현하기로 결정한다.
- [ ] 소유자 은폐(타 사용자 404), 완료 상태, 마스킹 복원 조건을 테스트한다.
- [ ] React 다운로드 동작과 파일명을 계약 테스트로 묶는다.

### K1-5. 보존·삭제

- [ ] 만료 문서/변환/마스킹 대응표를 같은 경계에서 삭제한다.
- [ ] 활성 lease와 삭제가 충돌하지 않게 한다.
- [ ] dry-run/메트릭/감사 이벤트를 제공하고 본문은 로그에 남기지 않는다.

### K1-6. Kotlin 품질 게이트

- [ ] `data/golden/documents/`를 읽는 Kotlin 평가 입력 스키마를 만든다.
- [ ] 스타일 규칙 평가는 외부 API 없이 실행한다.
- [ ] LLM-as-judge는 별도 opt-in 레인으로 두고 비밀값이 없으면 명시적으로 skip한다.
- [ ] 기준선 기록과 승인 없는 기준선 갱신 금지 규칙을 정한다.

## 4. 완료 정의

- [ ] `docker compose -f compose.yml -f compose.ci.yml run --rm backend-check` 통과
- [ ] `docker compose -f compose.yml -f compose.ci.yml run --rm frontend-check` 통과
- [ ] `docker compose up -d --build --wait` 후 Playwright 핵심 흐름 통과
- [ ] `docker compose config`와 `docker compose -f compose.yml -f compose.ci.yml config` 통과
- [ ] 계약·Kotlin DTO/컨트롤러·React 타입/호출부가 같은 변경 단위로 검증됨
- [ ] 미완료 항목이 `docs/kotlin-redevelopment-backlog.md`와 일치함

## 5. 범위 밖

결제, 크레딧 자동 차감, RAG, 운영자 어드민, 랜딩 페이지, OCR, 실제 배포와 실제 LLM 호출은 Sprint K1 범위가 아니다.

