"""SQLAlchemy 비동기 엔진·세션 기반.

전역 싱글턴 엔진을 두지 않는다. 엔진과 세션 팩토리는 FastAPI lifespan(또는 워커
startup)에서 생성해 애플리케이션 상태에 보관하고, 종료 시 호출자가 정리한다 —
테스트가 자체 엔진을 쓸 수 있어야 하고, import 시점에 DB 접속 설정이 고정되면
안 되기 때문이다.
"""

from sqlalchemy import MetaData
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

#: 제약·인덱스 이름 규칙. DB가 자동으로 붙인 이름에 의존하면 나중에 제약을 지우거나
#: 바꾸는 마이그레이션에서 이름을 몰라 손으로 SQL을 쓰게 된다. 규칙을 고정해두면
#: alembic이 항상 같은 이름을 생성한다 — 첫 마이그레이션 전에 정해야 하는 값이다.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    """모든 ORM 모델의 공통 베이스. alembic autogenerate가 이 metadata를 본다."""

    metadata = MetaData(naming_convention=NAMING_CONVENTION)


def create_engine_and_factory(
    database_url: str,
) -> tuple[AsyncEngine, async_sessionmaker[AsyncSession]]:
    """비동기 엔진과 세션 팩토리를 만든다.

    Args:
        database_url: `postgresql+asyncpg://...` 형식의 접속 URL.

    Returns:
        (엔진, 세션 팩토리). 엔진 수명은 호출자가 관리한다(`await engine.dispose()`).
    """
    engine = create_async_engine(database_url, pool_pre_ping=True)
    # expire_on_commit=False: commit 이후에도 ORM 객체 속성 접근이 추가 쿼리를
    # 유발하지 않게 한다(비동기에서는 지연 로딩이 예외가 되므로 필수에 가깝다).
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    return engine, session_factory
