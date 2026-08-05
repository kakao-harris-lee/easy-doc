"""DB 기반(엔진·세션 팩토리) 테스트.

`db` 마커가 붙은 통합 테스트는 DATABASE_URL이 없으면 skip 된다.
"""

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession

from app.db import Base, create_engine_and_factory
from tests.conftest import requires_db

_DUMMY_URL = "postgresql+asyncpg://postgres:postgres@localhost:5432/easydoc"


async def test_엔진과_세션_팩토리를_함께_돌려준다() -> None:
    """접속 없이도 생성이 가능해야 한다(실제 연결은 첫 쿼리까지 지연된다)."""
    engine, session_factory = create_engine_and_factory(_DUMMY_URL)
    try:
        async with session_factory() as session:
            assert isinstance(session, AsyncSession)
        assert isinstance(engine, AsyncEngine)
    finally:
        await engine.dispose()


def test_호출마다_새_엔진을_만든다() -> None:
    """전역 싱글턴을 두지 않으므로 호출 결과가 공유되지 않는다."""
    first, _ = create_engine_and_factory(_DUMMY_URL)
    second, _ = create_engine_and_factory(_DUMMY_URL)

    assert first is not second


def test_base가_모델_메타데이터를_노출한다() -> None:
    """alembic autogenerate가 참조하는 metadata가 존재한다."""
    assert Base.metadata is not None


@requires_db
@pytest.mark.db
async def test_엔진으로_실제_접속한다(db_engine: AsyncEngine) -> None:
    """DATABASE_URL이 주어지면 엔진이 실제 DB에 연결된다."""
    async with db_engine.connect() as connection:
        result = await connection.execute(text("SELECT 1"))

    assert result.scalar_one() == 1


@requires_db
@pytest.mark.db
async def test_세션으로_간단한_쿼리를_실행한다(db_session: AsyncSession) -> None:
    """롤백 격리된 세션에서 쿼리가 동작한다."""
    result = await db_session.execute(text("SELECT 1"))

    assert result.scalar_one() == 1
