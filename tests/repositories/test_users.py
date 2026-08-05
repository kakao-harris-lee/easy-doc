"""UserRepository 통합 테스트 — 실제 PostgreSQL이 필요하다.

DATABASE_URL이 없으면 conftest가 `db` 마커를 보고 자동으로 skip 한다.

create는 flush까지만 하므로, 저장을 확정하려면 commit을 따로 부른다 — 실제 호출자인
AuthService와 같은 순서다(트랜잭션 경계는 서비스가 소유한다).
"""

import logging
import uuid

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import EmailAlreadyRegisteredError, InvalidInputError, StorageError
from app.models.user import User
from app.repositories.users import UserRepository

pytestmark = pytest.mark.db

_HASH = "$argon2id$fake"


async def _create(repository: UserRepository, email: str, password_hash: str = _HASH) -> User:
    """가입 성공 경로 그대로 저장하고 확정한다."""
    user = await repository.create(email=email, password_hash=password_hash)
    await repository.commit()
    return user


async def test_사용자를_저장하고_이메일로_조회한다(db_session: AsyncSession) -> None:
    repository = UserRepository(db_session)

    created = await _create(repository, "user@example.com")
    found = await repository.get_by_email("user@example.com")

    assert found is not None
    assert found.id == created.id
    assert found.email == "user@example.com"
    assert found.password_hash == _HASH


async def test_created_at은_DB가_채운다(db_session: AsyncSession) -> None:
    """server_default=now() — 애플리케이션 시계가 아니라 DB 시계를 기준으로 한다."""
    repository = UserRepository(db_session)

    await _create(repository, "stamped@example.com")
    found = await repository.get_by_email("stamped@example.com")

    assert found is not None
    assert found.created_at is not None
    # timezone=True 컬럼이므로 오프셋이 붙은 값이어야 한다(naive면 보존기간 계산이 흔들린다).
    assert found.created_at.tzinfo is not None


async def test_중복_이메일은_도메인_예외가_된다(db_session: AsyncSession) -> None:
    """커밋 없이 flush 단계에서 unique 제약이 걸린다."""
    repository = UserRepository(db_session)
    await _create(repository, "dup@example.com")

    with pytest.raises(EmailAlreadyRegisteredError) as error:
        await repository.create(email="dup@example.com", password_hash=_HASH)

    # 예외 메시지에 이메일이 들어가면 로그·응답으로 개인정보가 새어 나간다.
    assert "dup@example.com" not in str(error.value)


async def test_중복_실패_후에도_세션을_계속_쓸_수_있다(db_session: AsyncSession) -> None:
    """IntegrityError 후 롤백하지 않으면 이후 모든 쿼리가 실패한다."""
    repository = UserRepository(db_session)
    await _create(repository, "recover@example.com", password_hash="$argon2id$first")

    with pytest.raises(EmailAlreadyRegisteredError):
        await repository.create(email="recover@example.com", password_hash="$argon2id$second")

    survivor = await repository.get_by_email("recover@example.com")
    assert survivor is not None
    assert survivor.password_hash == "$argon2id$first"


async def test_대문자_이메일은_중복이_아니라_입력_오류다(db_session: AsyncSession) -> None:
    """소문자 CHECK 위반을 "이미 가입됨"으로 둔갑시키면 409가 엉뚱하게 나간다.

    서비스가 정규화하므로 정상 경로에서는 닿지 않는다 — 정규화를 건너뛴 호출을 막는
    DB 차원의 안전망이 실제로 동작하는지 본다.
    """
    repository = UserRepository(db_session)

    with pytest.raises(InvalidInputError) as error:
        await repository.create(email="Upper@Example.com", password_hash=_HASH)

    assert "Upper@Example.com" not in str(error.value)


async def test_그_밖의_제약_위반은_5xx가_된다(
    db_session: AsyncSession, caplog: pytest.LogCaptureFixture
) -> None:
    """NOT NULL 위반 같은 것은 입력 문제가 아니라 우리 코드의 버그다.

    4xx로 감싸면 "사용자가 뭘 잘못했다"로 둔갑해 조용히 묻힌다. 진단은 로그의
    SQLSTATE·제약 이름으로 하고, 예외 메시지·DETAIL은 남기지 않는다.
    """
    repository = UserRepository(db_session)

    with (
        caplog.at_level(logging.ERROR, logger="app.repositories.users"),
        pytest.raises(StorageError) as error,
    ):
        # 아래 무시 지시자의 사유: 서버 버그 상황(해시 누락)을 일부러 만든다.
        # 타입 검사를 통과하는 코드로는 이 분기에 닿을 수 없다.
        await repository.create(
            email="notnull@example.com",
            password_hash=None,  # type: ignore[arg-type]
        )

    assert "sqlstate=23502" in caplog.text
    # PostgreSQL은 제약 위반 DETAIL에 실패한 행 전체를 담는다 — 예외에도 로그에도
    # 그 문자열이 흘러들면 안 된다.
    assert "notnull@example.com" not in str(error.value)
    assert "notnull@example.com" not in caplog.text
    assert error.value.__cause__ is None


async def test_중복_예외에_DB_원본이_매달리지_않는다(db_session: AsyncSession) -> None:
    """예외 체인이 붙으면 트레이스백을 찍는 모든 지점에서 이메일이 샌다."""
    repository = UserRepository(db_session)
    await _create(repository, "chain@example.com")

    with pytest.raises(EmailAlreadyRegisteredError) as error:
        await repository.create(email="chain@example.com", password_hash=_HASH)

    assert error.value.__cause__ is None
    assert "chain@example.com" not in str(error.value)


async def test_식별자로_조회한다(db_session: AsyncSession) -> None:
    repository = UserRepository(db_session)
    created = await _create(repository, "byid@example.com")

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


async def test_제약_이름이_naming_convention을_따른다(db_session: AsyncSession) -> None:
    """이름을 규칙으로 고정해야 나중에 제약을 바꾸는 마이그레이션을 손으로 안 쓴다."""
    result = await db_session.execute(
        text("SELECT conname FROM pg_constraint WHERE conrelid = 'users'::regclass")
    )

    assert {"pk_users", "ck_users_email_lowercase"} <= set(result.scalars())
