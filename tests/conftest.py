"""테스트 공통 픽스처.

DB 통합 테스트는 `@pytest.mark.db` + `requires_db`(DATABASE_URL 부재 시 skip)를
함께 붙인다. 기본 스위트는 Postgres·Redis 없이 통과해야 하므로 deselect가 아니라
skip 방식이며, CI는 서비스 컨테이너와 DATABASE_URL을 제공해 실제로 실행한다.
"""

import os
from collections.abc import AsyncIterator

import pytest
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.db import create_engine_and_factory

#: DB 통합 테스트에 붙이는 skip 마커. 수집 시점의 DATABASE_URL 유무로 판단한다.
requires_db = pytest.mark.skipif(
    os.environ.get("DATABASE_URL") is None,
    reason="DATABASE_URL 미설정 — DB 통합 테스트를 건너뛴다",
)


@pytest.fixture
async def db_engine() -> AsyncIterator[AsyncEngine]:
    """테스트용 비동기 엔진. DATABASE_URL이 없으면 해당 테스트를 skip 한다."""
    database_url = os.environ.get("DATABASE_URL")
    if database_url is None:
        pytest.skip("DATABASE_URL 미설정 — DB 통합 테스트를 건너뛴다")

    engine, _ = create_engine_and_factory(database_url)
    try:
        yield engine
    finally:
        await engine.dispose()


@pytest.fixture
async def db_session(db_engine: AsyncEngine) -> AsyncIterator[AsyncSession]:
    """테스트마다 외부 트랜잭션을 열고 끝나면 롤백해 DB 상태를 격리한다.

    세션은 이미 열린 커넥션에 바인딩하고 join_transaction_mode="create_savepoint"를
    쓰므로, 테스트 코드가 commit 해도 세이브포인트만 반영되고 최종 롤백으로 사라진다.
    """
    async with db_engine.connect() as connection:
        transaction = await connection.begin()
        session_factory = async_sessionmaker(
            bind=connection,
            expire_on_commit=False,
            join_transaction_mode="create_savepoint",
        )
        session = session_factory()
        try:
            yield session
        finally:
            await session.close()
            await transaction.rollback()
