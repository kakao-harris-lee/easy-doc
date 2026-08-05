# Easy-Read AI

공공기관의 행정·복지 문서를 발달장애인 등 정보소외계층을 위한 '쉬운 글'로 자동 변환하고, 담당자가 최종 검수하는 Human-in-the-Loop(HITL) SaaS.

현재 단계는 **Lean MVP**다. 전체 기획·정책·우선순위의 단일 기준 문서는 [`docs/master-plan.md`](docs/master-plan.md)이며, 기능 작업 전에 해당 문서의 우선순위(4장)와 정책 결정(3장)을 확인한다.

## 개발 환경

- Python 3.12 (`.python-version`에 고정 — 로컬·CI 동일)
- 패키지·가상환경 관리는 **uv**만 사용한다 (Poetry·`requirements.txt` 금지)
- 비밀키는 `.env`로만 주입한다. `.env.example`을 복사해 값을 채우고, `.env`는 커밋하지 않는다.

```bash
cp .env.example .env   # ANTHROPIC_API_KEY / OPENAI_API_KEY 입력
```

## 명령어

```bash
uv sync                      # 의존성 설치
uv run uvicorn app.main:app --reload   # 개발 서버
uv run pytest                # 테스트
uv run pytest tests/golden   # 프롬프트 골든셋 평가
uv run ruff check --fix . && uv run ruff format .
uv run mypy .
```

커밋 전 필수 통과 순서: **ruff → mypy → pytest**. GitHub Actions CI(`.github/workflows/ci.yml`)에서도 같은 순서로 강제된다.

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
  main.py       # FastAPI 진입점
  config.py     # pydantic-settings 기반 환경변수 설정
  exceptions.py # 도메인 예외
  easyread/     # 스타일 규칙(SSOT), 변환 프롬프트, 후처리, judge, 골든셋 로더
  llm/          # LLMProvider 인터페이스 + anthropic·openai 구현체 + 팩토리
  privacy/      # 개인정보 마스킹 파이프라인
  services/     # 변환 파이프라인 오케스트레이션
scripts/
  benchmark.py  # LLM provider 비교 벤치마크
tests/
  golden/       # 골든셋 문서 + 평가 하네스
```

레이어 규칙은 `api/`(라우터·스키마) → `services/`(비즈니스 로직) → `repositories/`(DB 접근)이며, 라우터에 비즈니스 로직을 두지 않는다.

## 지켜야 할 불변식

- **마스킹 선행**: 사용자 문서 텍스트는 `app/privacy/masking.py`를 통과한 후에만 `LLMProvider`로 전달된다. 이 순서를 우회하는 코드는 작성하지 않는다.
- **LLM 추상화**: 모든 LLM 호출은 `app/llm/provider.py`의 `LLMProvider` 인터페이스를 경유한다. 벤더 SDK는 provider 구현체 안에서만 import한다.
- **스타일 규칙 SSOT**: 쉬운 글 규칙은 `app/easyread/style_rules.py` 한 곳에 정의하고, 프롬프트 생성과 골든셋 평가가 같은 정의를 공유한다.
- **로그**: 문서 본문·개인정보를 로그와 예외 메시지에 남기지 않는다. 문서 ID·길이·처리 상태까지만 기록한다.

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/master-plan.md`](docs/master-plan.md) | 마스터 기획서 — 단일 기준 문서(SSOT) |
| [`docs/plans/`](docs/plans/) | 스프린트별 구현 계획서 |
| [`CLAUDE.md`](CLAUDE.md) | 개발 지침 (아키텍처·코딩·테스트·보안 규칙) |
