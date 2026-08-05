"""테스트 공통 픽스처.

DB 통합 테스트에는 `@pytest.mark.db`만 붙이면 된다 — DATABASE_URL이 없으면
`pytest_collection_modifyitems`가 자동으로 skip을 부착한다. 기본 스위트는
Postgres·Redis 없이 통과해야 하므로 deselect가 아니라 skip 방식이며, CI는 서비스
컨테이너와 DATABASE_URL을 제공해 실제로 실행한다.

DB 픽스처는 스위트 시작 시 `alembic upgrade head`를 스스로 적용하므로, 로컬에서
마이그레이션 명령을 잊어도 테스트가 자족적으로 돌아간다.
"""

import asyncio
import os
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config as AlembicConfig
from sqlalchemy import Connection
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.pool import NullPool

from app.db import create_engine_and_factory

_PROJECT_ROOT = Path(__file__).resolve().parent.parent
_ALEMBIC_INI = _PROJECT_ROOT / "alembic.ini"
_DATABASE_URL = os.environ.get("DATABASE_URL")
_SKIP_REASON = "DATABASE_URL 미설정 — DB 통합 테스트를 건너뛴다"


def pytest_collection_modifyitems(items: list[pytest.Item]) -> None:
    """DATABASE_URL이 없으면 `db` 마커가 붙은 테스트에 skip을 일괄 부착한다."""
    if _DATABASE_URL is not None:
        return

    skip_db = pytest.mark.skip(reason=_SKIP_REASON)
    for item in items:
        if "db" in item.keywords:
            item.add_marker(skip_db)


def _require_database_url() -> str:
    """DB 픽스처 진입점. 마커 없이 픽스처만 쓴 테스트도 안전하게 skip 된다."""
    if _DATABASE_URL is None:
        pytest.skip(_SKIP_REASON)
    return _DATABASE_URL


def _upgrade_with_connection(connection: Connection) -> None:
    """이미 열린 동기 커넥션 위에서 `alembic upgrade head`를 실행한다.

    migrations/env.py가 `config.attributes["connection"]`을 우선 사용하므로,
    러닝 이벤트 루프 안에서도 `asyncio.run` 중첩 없이 마이그레이션이 적용된다.
    """
    alembic_config = AlembicConfig(str(_ALEMBIC_INI))
    alembic_config.attributes["connection"] = connection
    command.upgrade(alembic_config, "head")


async def _upgrade_database(database_url: str) -> None:
    """스키마를 head 리비전까지 올린다."""
    engine = create_async_engine(database_url, poolclass=NullPool)
    try:
        async with engine.begin() as connection:
            await connection.run_sync(_upgrade_with_connection)
    finally:
        await engine.dispose()


@pytest.fixture(scope="session")
def migrated_database() -> str:
    """스위트 시작 시 스키마를 head로 맞추고 접속 URL을 돌려준다."""
    database_url = _require_database_url()
    asyncio.run(_upgrade_database(database_url))
    return database_url


@pytest.fixture
async def db_engine(migrated_database: str) -> AsyncIterator[AsyncEngine]:
    """마이그레이션이 적용된 DB에 연결하는 테스트용 비동기 엔진."""
    engine, _ = create_engine_and_factory(migrated_database)
    try:
        yield engine
    finally:
        await engine.dispose()


@pytest.fixture
async def db_session_factory(
    db_engine: AsyncEngine,
) -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """테스트용 외부 트랜잭션에 바인딩된 세션 팩토리. 끝나면 전부 롤백된다.

    이 팩토리가 만드는 세션은 모두 같은 커넥션·같은 트랜잭션을 공유하고,
    join_transaction_mode="create_savepoint" 덕분에 테스트 코드가 commit 해도
    세이브포인트만 반영되어 최종 롤백으로 사라진다.

    함정: **별도 엔진으로 만든 세션은 아직 커밋되지 않은 이 트랜잭션의 데이터를 보지
    못한다.** 따라서 FastAPI 테스트는 반드시 dependency override로 이 팩토리를 주입해야
    한다(앱이 자체 세션 팩토리를 쓰면 테스트가 넣은 데이터를 못 본다)::

        async def _override_get_session() -> AsyncIterator[AsyncSession]:
            async with db_session_factory() as session:
                yield session

        app.dependency_overrides[get_session] = _override_get_session
    """
    async with db_engine.connect() as connection:
        transaction = await connection.begin()
        session_factory = async_sessionmaker(
            bind=connection,
            expire_on_commit=False,
            join_transaction_mode="create_savepoint",
        )
        try:
            yield session_factory
        finally:
            await transaction.rollback()


@pytest.fixture
async def db_session(
    db_session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    """롤백 격리된 단일 세션. 여러 세션이 필요하면 db_session_factory를 직접 쓴다."""
    session = db_session_factory()
    try:
        yield session
    finally:
        await session.close()
