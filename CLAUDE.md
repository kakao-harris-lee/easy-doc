# CLAUDE.md — Easy-Read AI 개발 지침

공공기관용 '쉬운 글' 자동 변환 SaaS. 전체 기획·정책·우선순위는 `docs/master-plan.md`가 단일 기준 문서(SSOT)다. 기능 작업 전 반드시 해당 문서의 우선순위(4장)와 정책 결정(3장)을 확인할 것.

## 개발 단계

현재 단계: **Lean MVP (master-plan 4.0)**. Lean MVP 범위 밖 기능(PG 결제, RAG 사전, 어드민 등)은 사용자가 명시적으로 요청하지 않는 한 구현하지 않는다. 범위가 애매하면 구현 전에 물어볼 것.

## 하네스: Kotlin 마이그레이션

**목표:** 제품 동작과 개인정보 보호 정책을 보존한 채 Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체한다. 계획 기준 문서는 `docs/plans/2026-08-11-kotlin-react-migration.md`.

**트리거:** 코틀린 전환·Kotlin 포팅·`backend-kotlin/` 작업·API 계약 동결·Python↔Kotlin parity 검증·Fernet/JWT/Argon2 호환·Flyway 인수·작업 큐 전환·절체(cutover) 관련 요청이면 `kotlin-migration` 스킬을 사용하라. 후속 요청("이어서", "다시", "재검증", "Phase N만 다시")에도 같은 스킬을 쓴다. 단순 조회나 질문은 직접 응답해도 된다.

**리뷰 게이트:** Kotlin 코드 변경이 끝날 때마다 codex 독립 리뷰가 **필수**다. `codex-reviewer`와 `migration-reviewer`를 병렬·독립 실행한 뒤 교차 대조한다. 두 리뷰가 상충하면 어느 쪽도 삭제하지 않고 양쪽 근거를 병기해 사용자 판단을 받는다.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-08-11 | 초기 구성 (에이전트 6, 스킬 6) | 전체 | Kotlin 마이그레이션 착수 |
| 2026-08-11 | 독립 검증 결함 수정 (Critical 5·Major 10·Minor 7) | 에이전트 6, 스킬 6 | 병렬 작성으로 생긴 레인 간 규약 드리프트(리뷰 파일명·fixture 도메인명·mismatch 파일명), 리뷰 게이트가 병렬 호출만으로는 닫히지 않던 문제, 스킬 간 트리거 충돌 |
| 2026-08-11 | 계약 표 503 경로 보강 | skills/api-contract-freeze | codex stop-time 게이트 지적 — `POST /documents`의 "큐 미준비 → 503"(`app/api/deps.py`) 경로가 표에서 누락됨 |

## 기술 스택 (확정)

- Python 3.12+ / FastAPI / **uv** (패키지·가상환경 관리 — Poetry, pip requirements.txt 금지)
- PostgreSQL + pgvector (단일 DB — ChromaDB 등 별도 벡터 DB 추가 금지)
- 비동기 작업: arq + Redis
- Frontend: React + TypeScript (Vite)
- LLM: 자체 Provider 추상화 레이어 경유 (아래 '아키텍처 규칙')

## 명령어

```bash
uv sync                      # 의존성 설치
uv run uvicorn app.main:app --reload   # 개발 서버
uv run pytest                # 테스트
uv run pytest tests/golden   # 프롬프트 골든셋 평가
uv run ruff check --fix . && uv run ruff format .
uv run mypy .
```

커밋 전 필수 통과: ruff → mypy → pytest. CI(GitHub Actions)에서도 동일 순서로 강제된다.

## 아키텍처 규칙

1. **LLM 추상화**: 모든 LLM 호출은 `app/llm/provider.py`의 `LLMProvider` 인터페이스를 통해서만 한다. 벤더 SDK(anthropic, openai 등)를 서비스 코드에서 직접 import하지 않는다. 새 벤더는 provider 구현체 추가로만 대응.
2. **마스킹 선행 (보안 불변식)**: 사용자 문서 텍스트는 `app/privacy/masking.py` 파이프라인을 통과한 후에만 LLMProvider에 전달될 수 있다. 이 순서를 우회하는 코드는 절대 작성하지 않는다.
3. **레이어 분리**: `api/`(라우터, Pydantic 스키마) → `services/`(비즈니스 로직) → `repositories/`(DB 접근). 라우터에 비즈니스 로직 금지.
4. **쉬운 글 스타일 규칙**은 `app/easyread/style_rules.py` 한 곳에 상수/함수로 정의하고, 프롬프트 생성과 골든셋 평가가 같은 정의를 사용한다.

## 디렉터리 구조 (백엔드)

```
app/
  api/          # FastAPI 라우터 + 요청/응답 Pydantic 스키마
  services/     # 비즈니스 로직
  repositories/ # DB 접근 (SQLAlchemy)
  llm/          # LLMProvider 인터페이스 + 구현체
  privacy/      # 마스킹 파이프라인
  easyread/     # 변환 프롬프트, 스타일 규칙, 후처리
  ingest/       # 파일 텍스트 추출 (docx/pdf/hwpx)
  workers/      # arq 태스크
tests/
  golden/       # 골든셋 문서 + 자동 평가
```

## 코딩 규칙

- 모든 함수에 타입 힌트 필수. `mypy --strict` 통과 기준으로 작성. `Any`·`type: ignore`는 사유 주석 없이 금지.
- API 입출력은 반드시 Pydantic 모델. dict 반환 금지.
- 예외는 도메인 예외(`app/exceptions.py`)로 정의해 사용하고, 라우터 레벨에서 HTTP 응답으로 변환.
- 주석·docstring·사용자 노출 문자열은 한국어, 코드 식별자는 영어.

## 테스트 규칙

- 새 기능 = 테스트 동반. 버그 수정 = 재현 테스트 먼저.
- **프롬프트·스타일 규칙·LLM 설정을 변경하면 반드시 `uv run pytest tests/golden` 실행** 후 결과를 보고할 것. 통과율 하락 시 변경을 되돌리거나 사유를 명시.
- 골든셋 평가는 ① 규칙 기반 검사(문장 길이, 금지 표현, 필수 정보 유지)와 ② LLM-as-judge 채점으로 구성. 외부 API를 쓰는 judge 테스트는 `@pytest.mark.llm`으로 분리(CI에서만 전체 실행).
- LLM 호출부 단위 테스트는 FakeProvider로 대체.

## 보안·데이터 규칙 (위반 금지)

- 로그에 문서 본문·개인정보를 절대 남기지 않는다. 로깅은 문서 ID·길이·처리 상태까지만.
- 비밀키는 `.env` + 환경변수만. 코드·커밋에 키 포함 금지.
- 업로드 원문은 암호화 저장, 기본 30일 후 자동 삭제 정책을 전제로 스키마를 설계한다 (master-plan 3.2).
- LLM 계약은 no-training 전제 — provider 구현체 추가 시 이 조건을 주석으로 명시.

## 하지 말 것

- Lean MVP 범위 밖 기능 선제 구현 (스코프 크리프 — v1 기획의 실패 요인)
- LangChain 체인으로 핵심 변환 로직 구성 (문서 로더 등 유틸만 선택적 사용; 변환 파이프라인은 직접 구현)
- 프론트에서 LLM 직접 호출, 벤더 SDK 직접 import
- '페이지' 용어 사용 — 과금·UI 용어는 '크레딧' (1,000자 = 1크레딧)
- 영업·안내 문구에 "의무화" 표현 (master-plan 1.2 화법 가이드 준수)

## Definition of Done

기능 하나가 끝났다고 말하려면: 타입 검사·린트·테스트 전체 통과 + 골든셋 영향 확인(해당 시) + 로그에 개인정보 미포함 확인 + master-plan의 해당 기능 표와 어긋나는 점이 있으면 문서 갱신 제안까지.
