# Easy-Read AI

공공기관의 행정·복지 문서를 발달장애인 등 정보소외계층을 위한 '쉬운 글'로 자동 변환하고, 담당자가 최종 검수하는 Human-in-the-Loop(HITL) SaaS.

현재 단계는 **Lean MVP**다. 전체 기획·정책·우선순위의 단일 기준 문서는 [`docs/master-plan.md`](docs/master-plan.md)이며, 기능 작업 전에 해당 문서의 우선순위(4장)와 정책 결정(3장)을 확인한다. 외부 HTTP 계약의 기준은 [`contracts/easy-doc-v1.yaml`](contracts/easy-doc-v1.yaml)이고, 지금 구현된 기능과 남은 backlog는 [`docs/kotlin-redevelopment-backlog.md`](docs/kotlin-redevelopment-backlog.md)에 있다.

## 기술 스택

- Backend: Kotlin, Spring Boot, 독립 Gradle 멀티모듈 프로젝트 (`backend-kotlin/` — `core` → `application` → `infrastructure` → `api`/`worker`)
- Database: PostgreSQL + pgvector, Flyway
- Frontend: React, TypeScript, Vite 독립 npm 프로젝트 (`frontend/`)
- Integration: OpenAPI 계약 (`contracts/`) + Docker Compose (`compose.yml`, `compose.ci.yml`)

## 개발 환경

- 비밀키는 `.env`로만 주입한다. `.env.example`을 복사해 값을 채우고, `.env`는 커밋하지 않는다.
- 로컬 인프라(PostgreSQL)는 `compose.yml`로 띄운다.

호스트에서 백엔드·프론트를 직접 돌리는 개발 경로다. 화면까지 한 줄로 띄우려면 [파일럿 데모 실행](#파일럿-데모-실행)을 본다.

### 1. 인프라 기동

```bash
docker compose up -d postgres   # 5432, 127.0.0.1에만 바인딩
```

### 2. `.env` 작성

```bash
cp .env.example .env
```

키를 채우는 법은 `.env.example`의 주석을 따른다. 최소한 `EASYDOC_AUTH_JWT_SECRET`, `EASYDOC_ENCRYPTION_KEY_V1`은 채워야 API가 기동한다(값이 비면 기동 자기점검이 실패한다). LLM 키(`OPENAI_API_KEY` 또는 `ANTHROPIC_API_KEY`)가 없어도 기동·업로드는 되지만 변환은 `failure_code: "ProviderUnavailable"`로 실패한다.

### 3. Kotlin API 실행

```bash
cd backend-kotlin
./gradlew :api:bootRun   # API 서버 (:8000). 기동 시 Flyway 마이그레이션을 자동 적용한다
```

worker 모듈(`./gradlew :worker:bootRun`)은 현재 Spring Boot 기동 골격뿐이며 작업을 처리하지 않는다(진행 중인 backlog — `docs/kotlin-redevelopment-backlog.md` 1절 참고).

### 4. 프론트 개발 서버

```bash
cd frontend
npm ci
npm run dev        # http://localhost:5173
```

이 경로는 화면(5173)과 API(8000)의 출처가 달라 **교차 출처**다 — 백엔드 CORS 허용 목록에 `http://localhost:5173`이 기본으로 들어 있다. 데모 경로(아래)는 nginx 한 호스트라 이 설정이 관여하지 않는다.

## 검증

```bash
cd backend-kotlin && ./gradlew build       # 컴파일 + 테스트 + ktlint + detekt
cd frontend && npm run check               # tsc + eslint + prettier
cd frontend && npm run test -- --run       # vitest
cd frontend && npm run build
docker compose -f compose.yml config
docker compose -f compose.yml -f compose.ci.yml --profile ci config
```

## 파일럿 데모 실행

파일럿 기관에 보여주는 경로다. 호스트에 JDK·Node가 없어도 되고, 마이그레이션·화면까지 한 줄로 끝난다.

```bash
cp .env.example .env     # 값을 채운다 (위 "2. .env 작성" 참고)
docker compose up -d --build
```

브라우저에서 **http://127.0.0.1:8080** 을 연다.

- `backend-migrate`가 Flyway 마이그레이션을 적용하고 정상 종료하면 `backend-api`와 `backend-worker`가 뜬다. `frontend`는 `backend-api`의 healthcheck 통과를 기다린다.
- **`--build`를 빼지 않는다**: 코드를 고친 뒤 `docker compose up -d`만 하면 옛 이미지로 만든 컨테이너가 그대로 뜬다.
- api·worker·migrate는 같은 이미지(`easy-doc-kotlin:local`)를 command(profile)만 달리해 쓴다. 프론트는 별도 이미지(`easy-doc-frontend:local`)다.
- 포트는 모두 `127.0.0.1`에만 바인딩된다(8080 화면, 8100 API). 외부 공개는 앞단 리버스 프록시(TLS·본문 크기 제한)를 두고 한다.

```bash
curl http://127.0.0.1:8100/health   # {"status":"ok"}
docker compose logs -f backend-api
docker compose ps
docker compose down                 # 중지 (-v를 붙이면 DB 데이터까지 삭제)
```

## 현재 구현 상태

지금 이 저장소는 Kotlin 재개발 진행 중이며, 계약(`contracts/easy-doc-v1.yaml`)에 있는 기능 중 일부는 아직 Kotlin에 없다. 구현된 API·미구현 기능·재구현 시 반드시 지킬 요구사항의 정본은 [`docs/kotlin-redevelopment-backlog.md`](docs/kotlin-redevelopment-backlog.md)다. 이 README는 그 목록을 다시 베끼지 않는다 — 최신 상태와 어긋날 수 있기 때문이다.

## 데이터

언어 독립 골든 문서는 `data/golden/documents/`에 있고, Kotlin 프롬프트·스타일 규칙·파서 fixture는 각 백엔드 모듈의 테스트 리소스에 있다. 현재 위치와 미구현 평가는 [`docs/kotlin-redevelopment-backlog.md`](docs/kotlin-redevelopment-backlog.md)에 기록한다.

## 프로젝트 경계

- 백엔드는 프런트 구현을 의존하지 않는다.
- 프런트는 백엔드 내부 클래스가 아니라 `contracts/easy-doc-v1.yaml`만 의존한다.
- 루트는 Compose, CI/CD, 계약, 공통 문서만 소유한다.
- 디렉터리별 AI Agent 범위는 각 `AGENTS.md`에 있다.

CI와 같은 컨테이너 검증은 다음 명령으로 재현한다.

```bash
docker compose -f compose.yml -f compose.ci.yml run --rm backend-check
docker compose -f compose.yml -f compose.ci.yml run --rm frontend-check
```

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/master-plan.md`](docs/master-plan.md) | 마스터 기획서 — 단일 기준 문서(SSOT) |
| [`contracts/easy-doc-v1.yaml`](contracts/easy-doc-v1.yaml) | 외부 HTTP API 계약 |
| [`docs/kotlin-redevelopment-backlog.md`](docs/kotlin-redevelopment-backlog.md) | Kotlin 미구현 기능·재구현 요구사항 backlog |
| [`docs/plans/`](docs/plans/) | 단계별 구현 계획서 |
| [`docs/pilot-runbook.md`](docs/pilot-runbook.md) | 파일럿 세션 진행 가이드 |
| [`CLAUDE.md`](CLAUDE.md) | 개발 지침 (아키텍처·코딩·테스트·보안 규칙) |
