# API·워커·마이그레이션이 하나의 이미지를 공유하고 command로만 갈린다.
# 셋이 같은 코드·같은 의존성을 보는 것이 "워커만 옛 버전" 류의 배포 사고를 막는다.

# uv는 공식 배포 이미지에서 바이너리만 복사한다 — 설치 스크립트를 쓰면 빌드할 때마다
# 네트워크에 의존하고 버전이 조용히 올라간다. 태그를 고정해 재현 가능한 빌드로 둔다.
FROM ghcr.io/astral-sh/uv:0.9.22 AS uv

FROM python:3.12-slim

COPY --from=uv /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    # 마운트가 아닌 복사로 설치한다 — 캐시와 대상이 다른 파일시스템일 때 하드링크가 실패한다.
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    # 로그가 버퍼에 갇히면 컨테이너가 죽을 때 마지막 줄을 잃는다.
    PYTHONUNBUFFERED=1 \
    # non-root는 /app에 쓸 수 없다 — 런타임 .pyc 쓰기 시도를 아예 하지 않게 둔다.
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

# 의존성 레이어를 먼저 굳힌다. 소스만 바뀐 빌드는 이 층을 캐시에서 재사용한다.
# --locked: uv.lock과 어긋나면 락을 갱신하는 대신 빌드를 실패시킨다(이미지가 조용히
# 다른 버전을 담지 않게). --no-dev: 테스트·린트 의존성은 런타임 이미지에 넣지 않는다.
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev

# 이 프로젝트는 virtual 프로젝트(빌드 백엔드 없음)라 소스를 설치하지 않고 그대로 둔다.
# 실행 디렉터리가 /app이므로 `app` 패키지를 그대로 import한다.
COPY alembic.ini ./
COPY migrations ./migrations
COPY app ./app

# non-root 실행. 코드·가상환경 소유권은 root에 남긴다 — 애플리케이션이 자기 코드를
# 고칠 수 없어야 한다.
RUN useradd --system --create-home --uid 10001 easydoc
USER easydoc

# 기본은 API. 워커·마이그레이션은 compose에서 command로 덮어쓴다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
