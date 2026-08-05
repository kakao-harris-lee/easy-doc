"""users 테이블 접근.

이 계층 밖으로 SQLAlchemy 예외를 흘리지 않는다 — 호출자가 IntegrityError 같은
구현 세부를 알아야 분기할 수 있다면 레이어 분리가 깨진 것이고, 원본 예외에는 SQL
파라미터(이메일)가 들어 있어 스택트레이스로 개인정보가 로그에 남는다.

커밋 시점은 정하지 않는다. 트랜잭션 경계는 서비스가 소유한다(app/services/auth.py).
"""

import logging
import uuid

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import EmailAlreadyRegisteredError, InvalidInputError, StorageError
from app.models.user import User

_logger = logging.getLogger(__name__)

#: PostgreSQL SQLSTATE. users에 제약이 둘이라(unique 인덱스, 이메일 소문자 CHECK)
#: 어떤 제약이 걸렸는지 구분해야 응답이 엉뚱해지지 않는다.
_UNIQUE_VIOLATION = "23505"
_CHECK_VIOLATION = "23514"


class UserRepository:
    """사용자 저장소. 세션은 요청 단위로 주입받는다."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(self, *, email: str, password_hash: str) -> User:
        """사용자를 INSERT 한다 (flush까지만 — 확정은 호출자가 commit으로 한다).

        중복 검사를 `SELECT` 선행이 아니라 unique 제약 위반 처리로 하는 이유:
        동시에 같은 이메일로 두 요청이 들어오면 조회-후-삽입 사이에 틈이 생긴다.
        DB 제약만이 유일한 진실이므로 위반을 잡아 도메인 예외로 바꾼다.

        Args:
            email: 정규화(소문자)된 이메일 주소.
            password_hash: argon2 해시 문자열 (평문 금지).

        Returns:
            식별자가 채워진 사용자 (아직 커밋되지 않았다).

        Raises:
            EmailAlreadyRegisteredError: 같은 이메일이 이미 있다.
            InvalidInputError: 그 밖의 제약을 위반했다.
        """
        user = User(email=email, password_hash=password_hash)
        self._session.add(user)
        try:
            # flush로 INSERT를 지금 보내 제약 위반을 이 자리에서 확인한다. 커밋까지
            # 미루면 서비스가 정한 커밋 지점에서 터져 원인을 짚기 어려워진다.
            await self._session.flush()
        except IntegrityError as exc:
            # 롤백하지 않으면 세션이 무효 상태로 남아 다음 쿼리가 전부 실패한다.
            await self._session.rollback()
            sqlstate = getattr(exc.orig, "sqlstate", None)
            # 어느 분기든 `from exc`로 원본을 매달지 않는다. PostgreSQL은 제약 위반
            # DETAIL에 "Failing row contains (…, user@example.com, …)"처럼 실패한 행
            # 전체를 담고, 이 문자열은 SQLAlchemy의 hide_parameters로 가려지지 않는다
            # (드라이버가 만든 메시지라서). 예외 체인이 붙으면 트레이스백을 남기는
            # 모든 지점에서 이메일이 로그로 샌다.
            if sqlstate == _UNIQUE_VIOLATION:
                raise EmailAlreadyRegisteredError("이미 가입된 이메일입니다") from None
            if sqlstate == _CHECK_VIOLATION:
                # 정규화를 건너뛴 호출 경로가 있다는 뜻이다(대문자가 섞인 이메일이
                # 소문자 CHECK에 걸림). "이미 가입됨"(409)으로 둔갑시키지 않는다.
                raise InvalidInputError("저장할 수 없는 사용자 정보입니다") from None
            # NOT NULL(23502)·FK(23503) 같은 나머지는 입력 문제가 아니라 우리 코드의
            # 버그다. 4xx로 감싸면 조용히 묻히므로 5xx가 되는 예외로 올린다. 진단에
            # 필요한 식별자만 로그에 남긴다 — SQLSTATE와 제약 이름은 사용자 데이터가
            # 아니지만, 예외 메시지·DETAIL은 위 이유로 절대 남기지 않는다.
            _logger.error(
                "users 저장 제약 위반: sqlstate=%s constraint=%s",
                sqlstate,
                getattr(exc.orig, "constraint_name", None),
            )
            raise StorageError("사용자 정보를 저장하지 못했습니다") from None
        return user

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다. 호출 시점은 서비스가 정한다."""
        await self._session.commit()

    async def get_by_email(self, email: str) -> User | None:
        """이메일로 사용자를 찾는다. 없으면 None."""
        result = await self._session.execute(select(User).where(User.email == email))
        return result.scalar_one_or_none()

    async def get_by_id(self, user_id: uuid.UUID) -> User | None:
        """식별자로 사용자를 찾는다. 없으면 None."""
        return await self._session.get(User, user_id)
