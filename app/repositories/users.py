"""users 테이블 접근.

이 계층 밖으로 SQLAlchemy 예외를 흘리지 않는다 — 호출자가 IntegrityError 같은
구현 세부를 알아야 분기할 수 있다면 레이어 분리가 깨진 것이다.
"""

import uuid

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import EmailAlreadyRegisteredError
from app.models.user import User


class UserRepository:
    """사용자 저장소. 세션은 요청 단위로 주입받는다."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(self, *, email: str, password_hash: str) -> User:
        """사용자를 저장한다.

        중복 검사를 `SELECT` 선행이 아니라 unique 제약 위반 처리로 하는 이유:
        동시에 같은 이메일로 두 요청이 들어오면 조회-후-삽입 사이에 틈이 생긴다.
        DB 제약만이 유일한 진실이므로 위반을 잡아 도메인 예외로 바꾼다.

        Args:
            email: 정규화된 이메일 주소.
            password_hash: argon2 해시 문자열 (평문 금지).

        Returns:
            저장된 사용자.

        Raises:
            EmailAlreadyRegisteredError: 같은 이메일이 이미 있다.
        """
        user = User(email=email, password_hash=password_hash)
        self._session.add(user)
        try:
            # 커밋까지 여기서 끝낸다. 요청 종료 후(응답 전송 뒤) 커밋하면 실패해도
            # 이미 나간 201 응답을 되돌릴 수 없다.
            await self._session.commit()
        except IntegrityError as exc:
            # 롤백하지 않으면 세션이 무효 상태로 남아 다음 쿼리가 전부 실패한다.
            await self._session.rollback()
            # 예외 메시지에 이메일을 넣지 않는다 (로그·응답 유출 차단).
            raise EmailAlreadyRegisteredError("이미 가입된 이메일입니다") from exc
        return user

    async def get_by_email(self, email: str) -> User | None:
        """이메일로 사용자를 찾는다. 없으면 None."""
        result = await self._session.execute(select(User).where(User.email == email))
        return result.scalar_one_or_none()

    async def get_by_id(self, user_id: uuid.UUID) -> User | None:
        """식별자로 사용자를 찾는다. 없으면 None."""
        return await self._session.get(User, user_id)
