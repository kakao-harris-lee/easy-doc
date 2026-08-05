"""DB 기반(엔진·세션 팩토리·마이그레이션) 테스트.

`db` 마커가 붙은 통합 테스트는 DATABASE_URL이 없으면 conftest에서 자동 skip 된다.
"""

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.config import Settings
from app.db import NAMING_CONVENTION, Base, create_engine_and_factory


def _database_url() -> str:
    """접속하지 않는 테스트용 URL. 하드코딩 대신 설정에서 가져온다."""
    return Settings().database_url


async def test_세션_팩토리는_커밋_후에도_객체를_만료시키지_않는다() -> None:
    """expire_on_commit=False — 비동기에서는 만료 후 재조회가 지연 로딩 예외가 된다."""
    engine, session_factory = create_engine_and_factory(_database_url())
    try:
        assert session_factory.kw["expire_on_commit"] is False
    finally:
        await engine.dispose()


async def test_엔진은_pre_ping으로_끊긴_커넥션을_걸러낸다() -> None:
    """pool_pre_ping=True — 유휴 중 끊긴 커넥션이 첫 쿼리에서 터지지 않게 한다."""
    engine, _ = create_engine_and_factory(_database_url())
    try:
        # SQLAlchemy가 pre_ping 설정을 공개 속성으로 노출하지 않아 내부 속성을 본다.
        assert engine.pool._pre_ping is True
    finally:
        await engine.dispose()


async def test_호출마다_새_엔진을_만든다() -> None:
    """전역 싱글턴을 두지 않으므로 호출 결과가 공유되지 않는다."""
    first, _ = create_engine_and_factory(_database_url())
    second, _ = create_engine_and_factory(_database_url())
    try:
        assert first is not second
    finally:
        await first.dispose()
        await second.dispose()


def test_base가_제약_이름_규칙을_강제한다() -> None:
    """DB 자동 생성 이름에 의존하지 않도록 metadata에 naming convention이 걸려 있다."""
    assert dict(Base.metadata.naming_convention) == NAMING_CONVENTION


@pytest.mark.db
async def test_엔진으로_실제_접속한다(db_engine: AsyncEngine) -> None:
    """DATABASE_URL이 주어지면 엔진이 실제 DB에 연결된다."""
    async with db_engine.connect() as connection:
        result = await connection.execute(text("SELECT 1"))

    assert result.scalar_one() == 1


@pytest.mark.db
async def test_세션으로_간단한_쿼리를_실행한다(db_session: AsyncSession) -> None:
    """롤백 격리된 세션에서 쿼리가 동작한다."""
    result = await db_session.execute(text("SELECT 1"))

    assert result.scalar_one() == 1


@pytest.mark.db
async def test_픽스처가_마이그레이션을_적용해_둔다(db_session: AsyncSession) -> None:
    """migrated_database 픽스처가 alembic upgrade head를 적용하므로 버전 테이블이 있다."""
    result = await db_session.execute(text("SELECT to_regclass('public.alembic_version')"))

    assert result.scalar_one() is not None


@pytest.mark.db
async def test_세션_팩토리의_세션들은_같은_트랜잭션을_공유한다(
    db_session_factory: async_sessionmaker[AsyncSession],
) -> None:
    """한 세션이 커밋한 내용을 같은 팩토리의 다른 세션이 즉시 본다(롤백으로 사라진다)."""
    async with db_session_factory() as writer:
        await writer.execute(text("CREATE TABLE tmp_isolation_check (id integer)"))
        await writer.commit()

    async with db_session_factory() as reader:
        result = await reader.execute(text("SELECT to_regclass('public.tmp_isolation_check')"))

    assert result.scalar_one() is not None
