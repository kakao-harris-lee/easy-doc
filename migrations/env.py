"""alembic 마이그레이션 실행 환경 (SQLAlchemy 2.0 비동기).

접속 URL은 alembic.ini가 아니라 애플리케이션 Settings(환경변수·.env)에서 읽는다 —
비밀 정보를 설정 파일에 커밋하지 않기 위함이다.
"""

import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy import Connection, pool
from sqlalchemy.ext.asyncio import create_async_engine

from app.config import Settings
from app.db import Base

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# autogenerate 기준 메타데이터. 모델을 추가하면 app.models를 import 해 등록한다.
target_metadata = Base.metadata

database_url = Settings().database_url


def run_migrations_offline() -> None:
    """DB에 접속하지 않고 SQL 스크립트만 생성한다."""
    context.configure(
        url=database_url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    """동기 커넥션 위에서 실제 마이그레이션을 실행한다(run_sync 대상)."""
    context.configure(connection=connection, target_metadata=target_metadata)

    with context.begin_transaction():
        context.run_migrations()


async def run_migrations_online() -> None:
    """비동기 엔진으로 접속해 마이그레이션을 적용한다."""
    connectable = create_async_engine(database_url, poolclass=pool.NullPool)
    try:
        async with connectable.connect() as connection:
            await connection.run_sync(do_run_migrations)
    finally:
        await connectable.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_migrations_online())
