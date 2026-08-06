# Easy-Read AI

공공기관의 행정·복지 문서를 발달장애인 등 정보소외계층을 위한 '쉬운 글'로 자동 변환하고, 담당자가 최종 검수하는 Human-in-the-Loop(HITL) SaaS.

현재 단계는 **Lean MVP**다. 전체 기획·정책·우선순위의 단일 기준 문서는 [`docs/master-plan.md`](docs/master-plan.md)이며, 기능 작업 전에 해당 문서의 우선순위(4장)와 정책 결정(3장)을 확인한다.

## 개발 환경

- Python 3.12 (`.python-version`에 고정 — 로컬·CI 동일)
- 패키지·가상환경 관리는 **uv**만 사용한다 (Poetry·`requirements.txt` 금지)
- 로컬 인프라는 PostgreSQL(pgvector 이미지) + Redis — `docker-compose.yml`로 띄운다
- 비밀키는 `.env`로만 주입한다. `.env.example`을 복사해 값을 채우고, `.env`는 커밋하지 않는다.

### 1. 인프라 기동

```bash
docker compose up -d     # postgres(5432) + redis(6379), 127.0.0.1에만 바인딩
```

### 2. `.env` 작성

```bash
cp .env.example .env
```

| 키 | 채우는 법 |
|---|---|
| `DATABASE_URL` | 기본값(`postgresql+asyncpg://postgres:postgres@localhost:5432/easydoc`)이 docker-compose 설정과 같다 — 로컬은 그대로 둔다 |
| `REDIS_URL` | 기본값 `redis://localhost:6379/0` 그대로 |
| `JWT_SECRET` | `openssl rand -hex 32` — **32바이트 이상 필수.** 짧으면 기동은 되지만 인증 요청이 503으로 거부된다(약한 HMAC 키를 조용히 통과시키지 않는다) |
| `FERNET_KEY` | `uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"` — 없으면 문서 저장·워커 기동 불가 |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | LLM 벤더 키. 없으면 변환 작업이 `ProviderUnavailable`로 실패 기록된다(앱·워커는 정상 기동) |

### 3. 마이그레이션 · 서버 · 워커

```bash
uv sync                                    # 의존성 설치
uv run alembic upgrade head                # 스키마 적용
uv run uvicorn app.main:app --reload       # API 서버 (:8000)
uv run arq app.workers.settings.WorkerSettings   # 변환 워커 (별도 터미널에서)
```

워커가 떠 있지 않으면 업로드는 202로 접수되지만 변환이 `pending`에 머문다. 큐에 쌓인 작업만 처리하고 끝내려면 `--burst`를 붙인다.

## 명령어

```bash
uv run pytest                # 테스트 (인프라 없으면 db 테스트는 자동 skip)
uv run pytest tests/golden   # 프롬프트 골든셋 평가
uv run ruff check --fix . && uv run ruff format .
uv run mypy .
```

커밋 전 필수 통과 순서: **ruff → mypy → pytest**. GitHub Actions CI(`.github/workflows/ci.yml`)에서도 같은 순서로 강제된다.

### DB 통합 테스트

DB·Redis가 필요한 테스트는 `DATABASE_URL` 환경변수가 없으면 스스로 skip한다 — 인프라 없이도 기본 스위트는 통과한다.

```bash
# 인프라 없이 (db 테스트 skip)
uv run pytest

# 실제 Postgres·Redis에 붙여 전체 실행
DATABASE_URL=postgresql+asyncpg://postgres:postgres@localhost:5432/easydoc \
REDIS_URL=redis://localhost:6379/0 \
  uv run pytest
```

DB 테스트는 테스트마다 트랜잭션을 롤백해 격리하지만, 실행 대상 DB의 스키마는 최신이어야 한다(`uv run alembic upgrade head` 선행). CI는 서비스 컨테이너를 띄우고 마이그레이션까지 적용한 뒤 전체를 실행한다.

## API 개요

모든 응답은 Pydantic 모델이며, 오류는 `{"detail": "..."}` 한 가지 모양이다. 인증은 `Authorization: Bearer <access_token>`.

| 메서드 | 경로 | 요약 | 인증 |
|---|---|---|---|
| POST | `/auth/signup` | 이메일·비밀번호(8자 이상) 가입 → 201 `{id, email}` | — |
| POST | `/auth/login` | 로그인 → 200 `{access_token, token_type, expires_in}` | — |
| GET | `/auth/me` | 현재 사용자 조회 | 필요 |
| POST | `/documents` | 붙여넣기(JSON `{text, title?}`) 또는 파일(multipart `file`) 업로드 → 202 `{document_id, conversion_id, status, char_count}`. 변환 작업은 큐에 등록된다 | 필요 |
| GET | `/documents` | 소유자 문서 목록 (최신 변환 상태 포함, `limit`/`offset`) | 필요 |
| GET | `/conversions/{id}` | 변환 상태·결과. `done`이면 `easy_text`·`masked_items`(복호화된 원문 대응표)·`missing_placeholders`, `failed`면 `failure_code` | 필요 |

`GET /health`는 인증 없이 서비스 생존만 알린다(DB·Redis 상태는 보지 않는다).

## 현재 제한 사항

- **문서당 4,000자**(공백 포함). 그보다 길면 업로드 단계에서 422로 거절한다 — LLM 출력 토큰 상한을 넘겨 절단 실패할 것이 사실상 확정이라 비용을 치르기 전에 막는다. 문단 단위 분할 변환은 준비 중이며, 그때 이 상한이 사라진다.
- **업로드 파일 10MB**, 지원 형식은 **docx · pdf · hwpx**. 구버전 `.hwp`와 텍스트가 없는 스캔 PDF(OCR)는 아직 지원하지 않는다.
- **LLM API 키가 필요**하다. 키 없이도 기동·업로드는 되지만 변환은 `failure_code: "ProviderUnavailable"`로 실패한다.
- 30일 보존은 **필드(`documents.retention_expires_at`)만 있고 자동 삭제 잡은 없다.** 크레딧 차감·이메일 알림·에디터 UI도 아직이다 (Lean MVP 범위 구분 — master-plan 4.0).

## 배포 주의

- **리버스 프록시 본문 크기 제한**: 업로드 상한(10MB)은 애플리케이션 레벨에만 있다. nginx라면 `client_max_body_size 10m;`을 함께 설정해, 거대한 본문이 앱까지 도달해 메모리를 먹기 전에 프록시에서 끊는다.
- **pgvector 확장**: 마이그레이션 `0001`이 `CREATE EXTENSION vector`를 실행한다. 관리형 PostgreSQL(RDS·Cloud SQL 등)에서는 확장 생성 권한이 필요하며, 권한이 없는 계정으로 마이그레이션하면 이 지점에서 실패한다. 확장을 미리 만들어 두거나 권한 있는 역할로 적용한다.
- **`0003` 리비전 제자리 수정**: 개발 중 `0003_documents_conversions`를 새 리비전이 아니라 파일 수정으로 고쳤다. 그 이전 버전의 `0003`을 이미 적용한 로컬 DB는 스키마가 어긋나므로, **DB를 drop하고 처음부터 다시 마이그레이션**해야 한다(운영 배포 이력은 아직 없다).

  ```bash
  docker compose down -v && docker compose up -d && uv run alembic upgrade head
  ```

## 골든셋·벤치마크

골든셋은 공공 안내문 어투의 합성 문서 20건(`tests/golden/documents/*.json`)이다. 문서마다 변환 후에도 남아야 할 리터럴(`required_facts`)과 합성 개인정보가 들어 있다.

```bash
# 스키마·로더 검증 (LLM 호출 없음, 기본 실행에 포함)
uv run pytest tests/golden

# 실제 변환 평가 + LLM-as-judge 채점 (API 키 필요)
uv run pytest tests/golden -m llm
```

`-m llm` 테스트는 `pytest` 기본 실행에서 제외된다(`addopts = "-m 'not llm'"`). 평가에 쓸 provider는 환경변수로 고른다 — 변환은 `GOLDEN_PROVIDER`, 채점은 `GOLDEN_JUDGE_PROVIDER`(둘 다 기본값 `anthropic`). 해당 키가 없으면 테스트는 skip된다.

벤더 비교 벤치마크는 provider별로 골든셋 전체를 변환해 규칙 통과율·팩트 잔존율·judge 평균·평균 지연을 비교 표로 저장한다.

```bash
uv run python scripts/benchmark.py --providers anthropic,openai --judge anthropic --output docs/benchmarks/
```

리포트는 `docs/benchmarks/YYYY-MM-DD-llm-benchmark.md`로 저장되며, 문서 ID와 점수만 남기고 문서 본문은 기록하지 않는다.

## 프로젝트 구조

```
app/
  main.py        # FastAPI 진입점 (lifespan에서 DB 엔진·작업 큐 생성)
  config.py      # pydantic-settings 기반 환경변수 설정
  exceptions.py  # 도메인 예외
  db.py          # 비동기 엔진·세션 팩토리
  queue.py       # 작업 큐 프로토콜 + arq 어댑터
  api/           # 라우터 + 요청/응답 스키마 + 의존성 + 예외→HTTP 매핑
  services/      # 비즈니스 로직 (인증, 문서·변환 오케스트레이션)
  repositories/  # DB 접근 (SQLAlchemy async)
  models/        # SQLAlchemy ORM 모델 (users, documents, conversions)
  easyread/      # 스타일 규칙(SSOT), 변환 프롬프트, 후처리, judge, 골든셋 로더
  llm/           # LLMProvider 인터페이스 + anthropic·openai 구현체 + 팩토리
  privacy/       # 개인정보 마스킹 파이프라인 + Fernet 암호기
  ingest/        # 파일 텍스트 추출 (docx/pdf/hwpx)
  workers/       # arq 워커 설정 + 변환 태스크
migrations/      # alembic 리비전
scripts/
  benchmark.py   # LLM provider 비교 벤치마크
tests/
  golden/        # 골든셋 문서 + 평가 하네스
```

레이어 규칙은 `api/`(라우터·스키마) → `services/`(비즈니스 로직) → `repositories/`(DB 접근)이며, 라우터에 비즈니스 로직을 두지 않는다.

## 지켜야 할 불변식

- **마스킹 선행**: 사용자 문서 텍스트는 `app/privacy/masking.py`를 통과한 후에만 `LLMProvider`로 전달된다. 이 순서를 우회하는 코드는 작성하지 않는다.
- **LLM 추상화**: 모든 LLM 호출은 `app/llm/provider.py`의 `LLMProvider` 인터페이스를 경유한다. 벤더 SDK는 provider 구현체 안에서만 import한다.
- **스타일 규칙 SSOT**: 쉬운 글 규칙은 `app/easyread/style_rules.py` 한 곳에 정의하고, 프롬프트 생성과 골든셋 평가가 같은 정의를 공유한다.
- **저장 시 암호화**: 업로드 원문·변환 결과·마스킹 대응표는 Fernet으로 암호화해 저장한다. 저장소 계층은 평문을 받는 시그니처를 두지 않아, 암호화를 빠뜨린 호출이 타입 검사를 통과하지 못한다.
- **로그**: 문서 본문·개인정보를 로그와 예외 메시지에 남기지 않는다. 문서 ID·길이·처리 상태까지만 기록한다. 실패 사유도 예외 클래스명(`failure_code`)까지만 남긴다.

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/master-plan.md`](docs/master-plan.md) | 마스터 기획서 — 단일 기준 문서(SSOT) |
| [`docs/plans/`](docs/plans/) | 스프린트별 구현 계획서 |
| [`CLAUDE.md`](CLAUDE.md) | 개발 지침 (아키텍처·코딩·테스트·보안 규칙) |
