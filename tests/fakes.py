"""테스트 대역 모음.

서비스·API 테스트가 DB 없이 돌 수 있게 하는 최소 구현이다. 실제 저장소와 같은
계약(`app.services.auth.UserStore`)을 만족해야 하며, 계약이 어긋나면 AuthService에
넘기는 지점에서 mypy가 잡는다.
"""

import uuid
from datetime import UTC, datetime

from app.exceptions import EmailAlreadyRegisteredError
from app.models.user import User


class FakeUserRepository:
    """딕셔너리 기반 사용자 저장소 대역.

    실제 저장소처럼 create는 확정하지 않고 commit이 따로 호출된다. 트랜잭션 경계를
    서비스가 제대로 소유하는지 보려고 commit 호출 횟수를 기록한다.
    """

    def __init__(self) -> None:
        self._users: dict[str, User] = {}
        self.commits = 0

    async def create(self, *, email: str, password_hash: str) -> User:
        """저장한다. 같은 이메일이면 실제 저장소와 같은 도메인 예외를 던진다."""
        if email in self._users:
            raise EmailAlreadyRegisteredError("이미 가입된 이메일입니다")
        user = User(
            id=uuid.uuid4(),
            email=email,
            password_hash=password_hash,
            created_at=datetime.now(UTC),
        )
        # flush 이후 같은 트랜잭션 안에서는 조회되는 상태를 흉내 낸다.
        self._users[email] = user
        return user

    async def commit(self) -> None:
        """커밋 호출을 기록한다."""
        self.commits += 1

    async def get_by_email(self, email: str) -> User | None:
        """이메일로 찾는다. 없으면 None."""
        return self._users.get(email)

    async def get_by_id(self, user_id: uuid.UUID) -> User | None:
        """식별자로 찾는다. 없으면 None."""
        return next((user for user in self._users.values() if user.id == user_id), None)
