"""이메일 계정 인증 — argon2 비밀번호 해싱과 JWT 발급·검증.

보안 원칙 두 가지를 코드로 강제한다.

1. **실패 사유를 밝히지 않는다.** 이메일 부재·비밀번호 불일치·토큰 만료·위조를
   모두 같은 예외와 같은 메시지로 처리한다. 응답 본문뿐 아니라 처리 시간으로도
   가입 여부가 새지 않도록, 사용자가 없을 때도 더미 해시로 같은 검증 비용을 치른다.
2. **자격증명을 밖으로 내보내지 않는다.** 예외 메시지·로그·JWT 클레임 어디에도
   비밀번호·이메일·토큰 값을 담지 않는다.
"""

import re
import uuid
from datetime import UTC, datetime, timedelta
from typing import Protocol

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError

from app.exceptions import InvalidCredentialsError, InvalidInputError
from app.models.user import User

#: 비밀번호 최소 길이. 상한은 두지 않는다 — argon2는 입력 길이에 비용이 좌우되지 않는다.
MIN_PASSWORD_LENGTH = 8
#: users.email 컬럼 상한과 같은 값. 넘기면 DB 오류(500) 대신 도메인 예외로 막는다.
MAX_EMAIL_LENGTH = 255

_ALGORITHM = "HS256"

# 완전한 RFC 5322 검증은 하지 않는다 — 정규식으로 걸러낼 수 있는 것은 오타 수준의
# 형식 오류뿐이고, 실제 도달 가능 여부는 발송 확인(P0-3 이메일 인증)으로 판단한다.
_EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$")

_HASHER = PasswordHasher()

# 사용자가 없을 때 검증할 더미 해시. 이것이 없으면 "이메일 없음" 경로만 해시 검증을
# 건너뛰어 눈에 띄게 빨라지고, 응답 시간 차이로 가입 여부를 알아낼 수 있다.
_DUMMY_PASSWORD_HASH = _HASHER.hash("dummy-password-for-constant-time-login")

_INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"


def hash_password(password: str) -> str:
    """비밀번호를 argon2 해시로 만든다 (솔트는 argon2가 자동 생성)."""
    return _HASHER.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    """해시와 비밀번호가 맞는지 확인한다.

    argon2 예외를 밖으로 흘리지 않고 불리언으로 정규화한다 — 호출부가 "불일치"와
    "해시 문자열이 깨졌음"을 구분해 분기하면 실패 사유가 응답으로 새기 쉽다.
    """
    try:
        return _HASHER.verify(password_hash, password)
    except (VerificationError, InvalidHashError):
        return False


class UserStore(Protocol):
    """AuthService가 사용자 저장소에 요구하는 계약.

    구현은 `app.repositories.users.UserRepository`이며, 서비스가 SQLAlchemy 세션에
    직접 묶이지 않게 하려고 소비자 쪽에 인터페이스를 둔다(테스트는 fake로 대체).
    """

    async def create(self, *, email: str, password_hash: str) -> User:
        """사용자를 저장한다. 중복이면 EmailAlreadyRegisteredError."""
        ...

    async def get_by_email(self, email: str) -> User | None:
        """이메일로 사용자를 찾는다. 없으면 None."""
        ...


class AuthService:
    """가입·로그인·토큰 검증."""

    def __init__(self, repository: UserStore, jwt_secret: str, expire_minutes: int) -> None:
        self._repository = repository
        self._jwt_secret = jwt_secret
        self._expire_minutes = expire_minutes

    async def signup(self, email: str, password: str) -> User:
        """새 계정을 만든다.

        Raises:
            InvalidInputError: 이메일 형식이 아니거나 비밀번호가 너무 짧다.
            EmailAlreadyRegisteredError: 이미 가입된 이메일이다 (저장소가 판정).
        """
        normalized = self._normalize_email(email)
        if len(normalized) > MAX_EMAIL_LENGTH or not _EMAIL_PATTERN.match(normalized):
            # 메시지에 입력값을 되풀이하지 않는다.
            raise InvalidInputError("이메일 형식이 올바르지 않습니다")
        if len(password) < MIN_PASSWORD_LENGTH:
            raise InvalidInputError(f"비밀번호는 {MIN_PASSWORD_LENGTH}자 이상이어야 합니다")
        return await self._repository.create(
            email=normalized, password_hash=hash_password(password)
        )

    async def login(self, email: str, password: str) -> str:
        """자격증명을 확인하고 액세스 토큰을 발급한다.

        Raises:
            InvalidCredentialsError: 이메일이 없거나 비밀번호가 다르다(구분하지 않음).
        """
        user = await self._repository.get_by_email(self._normalize_email(email))
        # 사용자가 없어도 검증을 건너뛰지 않는다 — 조기 반환하면 이 경로만 빨라져
        # 응답 시간이 가입 여부를 알려준다.
        stored_hash = user.password_hash if user is not None else _DUMMY_PASSWORD_HASH
        matched = verify_password(stored_hash, password)
        if user is None or not matched:
            raise InvalidCredentialsError(_INVALID_CREDENTIALS_MESSAGE)
        return self._issue_token(user.id)

    def resolve_token(self, token: str) -> uuid.UUID:
        """토큰을 검증하고 사용자 식별자를 꺼낸다.

        Raises:
            InvalidCredentialsError: 서명이 틀렸거나 만료됐거나 형식이 어긋난다.
        """
        try:
            claims = jwt.decode(
                token,
                self._jwt_secret,
                algorithms=[_ALGORITHM],
                # exp 없는 토큰은 만료되지 않는 영구 자격증명이 되므로 필수로 요구한다.
                options={"require": ["sub", "exp"]},
            )
            # str()로 감싸지 않는다 — sub가 문자열이 아니면 그 자체로 우리가 발급한
            # 토큰이 아니며, TypeError를 아래에서 같은 예외로 정규화한다.
            return uuid.UUID(claims["sub"])
        except (jwt.PyJWTError, TypeError, ValueError) as exc:
            # 토큰 값을 메시지에 담지 않는다 — 토큰 자체가 자격증명이다.
            raise InvalidCredentialsError(_INVALID_CREDENTIALS_MESSAGE) from exc

    def _issue_token(self, user_id: uuid.UUID) -> str:
        """sub·exp만 담은 HS256 토큰을 만든다 (이메일 등 개인정보 금지)."""
        expires_at = datetime.now(UTC) + timedelta(minutes=self._expire_minutes)
        claims: dict[str, str | datetime] = {"sub": str(user_id), "exp": expires_at}
        return jwt.encode(claims, self._jwt_secret, algorithm=_ALGORITHM)

    @staticmethod
    def _normalize_email(email: str) -> str:
        """가입과 로그인이 같은 키를 쓰도록 공백·대소문자를 정규화한다."""
        return email.strip().lower()
