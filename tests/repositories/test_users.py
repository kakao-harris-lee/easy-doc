"""UserRepository 통합 테스트 — 실제 PostgreSQL이 필요하다.

DATABASE_URL이 없으면 conftest가 `db` 마커를 보고 자동으로 skip 한다.
"""

import uuid

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import EmailAlreadyRegisteredError
from app.repositories.users import UserRepository

pytestmark = pytest.mark.db


async def test_사용자를_저장하고_이메일로_조회한다(db_session: AsyncSession) -> None:
    repository = UserRepository(db_session)

    created = await repository.create(email="user@example.com", password_hash="$argon2id$fake")
    found = await repository.get_by_email("user@example.com")

    assert found is not None
    assert found.id == created.id
    assert found.email == "user@example.com"
    assert found.password_hash == "$argon2id$fake"


async def test_created_at은_DB가_채운다(db_session: AsyncSession) -> None:
    """server_default=now() — 애플리케이션 시계가 아니라 DB 시계를 기준으로 한다."""
    repository = UserRepository(db_session)

    await repository.create(email="stamped@example.com", password_hash="$argon2id$fake")
    found = await repository.get_by_email("stamped@example.com")

    assert found is not None
    assert found.created_at is not None
    # timezone=True 컬럼이므로 오프셋이 붙은 값이어야 한다(naive면 보존기간 계산이 흔들린다).
    assert found.created_at.tzinfo is not None


async def test_중복_이메일은_도메인_예외가_된다(db_session: AsyncSession) -> None:
    repository = UserRepository(db_session)
    await repository.create(email="dup@example.com", password_hash="$argon2id$first")

    with pytest.raises(EmailAlreadyRegisteredError) as error:
        await repository.create(email="dup@example.com", password_hash="$argon2id$second")

    # 예외 메시지에 이메일이 들어가면 로그·응답으로 개인정보가 새어 나간다.
    assert "dup@example.com" not in str(error.value)


async def test_중복_실패_후에도_세션을_계속_쓸_수_있다(db_session: AsyncSession) -> None:
    """IntegrityError 후 롤백하지 않으면 이후 모든 쿼리가 실패한다."""
    repository = UserRepository(db_session)
    await repository.create(email="recover@example.com", password_hash="$argon2id$first")

    with pytest.raises(EmailAlreadyRegisteredError):
        await repository.create(email="recover@example.com", password_hash="$argon2id$second")

    survivor = await repository.get_by_email("recover@example.com")
    assert survivor is not None
    assert survivor.password_hash == "$argon2id$first"


async def test_식별자로_조회한다(db_session: AsyncSession) -> None:
    repository = UserRepository(db_session)
    created = await repository.create(email="byid@example.com", password_hash="$argon2id$fake")

    found = await repository.get_by_id(created.id)

    assert found is not None
    assert found.email == "byid@example.com"


async def test_없는_사용자는_None을_반환한다(db_session: AsyncSession) -> None:
    repository = UserRepository(db_session)

    assert await repository.get_by_email("missing@example.com") is None
    assert await repository.get_by_id(uuid.uuid4()) is None


async def test_마이그레이션이_vector_확장을_설치한다(db_session: AsyncSession) -> None:
    """0001 리비전이 pgvector 확장을 깔아둔다 — 사전 RAG(P0-5) 도입 시 전제 조건."""
    result = await db_session.execute(
        text("SELECT extname FROM pg_extension WHERE extname = 'vector'")
    )

    assert result.scalar_one_or_none() == "vector"
