"""alembic 마이그레이션 실행 환경 (SQLAlchemy 2.0 비동기).

두 실행 경로를 모두 지원한다.

1. CLI (`uv run alembic upgrade head`): 접속 URL을 alembic.ini가 아니라 애플리케이션
   Settings(환경변수·.env)에서 읽어 엔진을 직접 만든다 — 비밀 정보 커밋 방지.
2. 프로그램적 호출(테스트 픽스처 등): 호출자가 `config.attributes["connection"]`에
   이미 열린 커넥션을 넣어주면 그것을 그대로 쓴다. 이 경로는 `asyncio.run`을 타지
   않으므로 러닝 이벤트 루프 안에서도 안전하다.
"""

import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy import Connection, pool
from sqlalchemy.ext.asyncio import create_async_engine

# import 부작용으로 모델이 Base.metadata에 등록된다. 이 줄이 없으면 autogenerate가
# 빈 metadata를 기준으로 삼아 기존 테이블을 전부 삭제하는 리비전을 만들어낸다.
import app.models  # noqa: F401  (등록이 목적이라 이름을 직접 쓰지 않는다)
from app.config import Settings
from app.db import Base

config = context.config

if config.config_file_name is not None:
    # disable_existing_loggers=False: 프로그램적으로 호출될 때 호출자(pytest 등)가
    # 이미 설정해 둔 로거를 꺼뜨리지 않는다.
    fileConfig(config.config_file_name, disable_existing_loggers=False)

# autogenerate 기준 메타데이터. 모델을 추가하면 app.models를 import 해 등록한다.
target_metadata = Base.metadata


def get_database_url() -> str:
    """접속 URL을 결정한다. 프로그램적으로 주입된 값(set_main_option)이 우선한다."""
    return config.get_main_option("sqlalchemy.url") or Settings().database_url


def run_migrations_offline() -> None:
    """DB에 접속하지 않고 SQL 스크립트만 생성한다."""
    context.configure(
        url=get_database_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_server_default=True,
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    """이미 열린 동기 커넥션 위에서 실제 마이그레이션을 실행한다."""
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        compare_server_default=True,
    )

    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    """비동기 엔진을 직접 만들어 접속한 뒤 마이그레이션을 적용한다."""
    connectable = create_async_engine(get_database_url(), poolclass=pool.NullPool)
    try:
        async with connectable.connect() as connection:
            await connection.run_sync(do_run_migrations)
    finally:
        await connectable.dispose()


def run_migrations_online() -> None:
    """온라인 모드 진입점. 주입된 커넥션이 있으면 엔진을 새로 만들지 않는다."""
    injected: Connection | None = config.attributes.get("connection")
    if injected is None:
        asyncio.run(run_async_migrations())
    else:
        do_run_migrations(injected)


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
