"""이메일 계정 인증 — argon2 비밀번호 해싱과 JWT 발급·검증.

보안 원칙 세 가지를 코드로 강제한다.

1. **실패 사유를 밝히지 않는다.** 이메일 부재·비밀번호 불일치·토큰 만료·위조를
   모두 같은 예외와 같은 메시지로 처리한다. 응답 본문뿐 아니라 처리 시간으로도
   가입 여부가 새지 않도록, 사용자가 없을 때도 더미 해시로 같은 검증 비용을 치른다.
2. **자격증명을 밖으로 내보내지 않는다.** 예외 메시지·로그·JWT 클레임 어디에도
   비밀번호·이메일·토큰 값을 담지 않는다.
3. **해싱은 이벤트 루프 밖에서 한다.** argon2는 설계상 느리다 — 루프에서 돌리면
   그동안 다른 모든 요청이 멈춘다(`hash_password` 참고).
"""

import logging
import re
import uuid
from datetime import UTC, datetime, timedelta
from functools import cache
from typing import Protocol

import jwt
from anyio import CapacityLimiter, to_thread
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError

from app.exceptions import ConfigurationError, InvalidCredentialsError, InvalidInputError
from app.models.user import User

_logger = logging.getLogger(__name__)

#: 비밀번호 최소 길이. 상한은 두지 않는다 — argon2는 입력 길이에 비용이 좌우되지 않는다.
MIN_PASSWORD_LENGTH = 8
#: users.email 컬럼 상한과 같은 값. 넘기면 DB 오류(500) 대신 도메인 예외로 막는다.
MAX_EMAIL_LENGTH = 255

#: JWT 서명 키 최소 길이(바이트). HS256의 해시 출력 크기와 같다 — 이보다 짧은 키는
#: 서명 강도를 떨어뜨린다(RFC 7518 3.2도 같은 하한을 요구한다).
#:
#: PyJWT 2.13은 짧은 키를 InsecureKeyLengthWarning으로만 알리고 서명은 그대로 해 준다.
#: 경고는 운영 로그에서 묻히기 쉬우므로, 약한 키가 조용히 통과하는 대신 기동 경로에서
#: 설정 오류로 끊는다. `.env.example`의 `openssl rand -hex 32`가 이 기준을 만족한다.
MIN_JWT_SECRET_BYTES = 32

_ALGORITHM = "HS256"

#: 토큰 용도 클레임 값. 이후 이메일 인증·비밀번호 재설정 토큰이 같은 시크릿으로
#: 서명되더라도 액세스 토큰으로 오용되지 않게, 발급할 때 용도를 새기고 검증할 때 대조한다.
#: (JOSE 헤더의 typ와는 별개 네임스페이스인 페이로드 클레임이다.)
_TOKEN_TYPE = "access"

# 완전한 RFC 5322 검증은 하지 않는다 — 정규식으로 걸러낼 수 있는 것은 오타 수준의
# 형식 오류뿐이고, 실제 도달 가능 여부는 발송 확인(P0-3 이메일 인증)으로 판단한다.
_EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$")

# 파라미터를 명시해 고정한다. 라이브러리 기본값은 업그레이드에서 조용히 바뀔 수 있고,
# 그러면 검증 비용과 저장 형식이 예고 없이 변한다. 값은 argon2-cffi의 현재 기본값과
# 같다(64MiB · 3회 반복 · 병렬 4). 나중에 올릴 때는 로그인 시 자동 재해시로 이관된다.
_HASHER = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4)

# argon2 1건은 memory_cost(64MiB)를 계산이 끝날 때까지 붙들고 있다. anyio 기본 스레드
# 한도는 40이라 동시 로그인 40건이면 약 2.5GiB — 컨테이너 메모리 상한을 넘겨 OOM으로
# 죽는다. 게다가 CPU 바운드라 코어 수를 넘겨 돌려도 처리량 이득이 없다.
# 실측(동시 20건, 3회): 한도 없이 총 193~269ms·루프 최대 지연 48~114ms(들쭉날쭉)
# → 한도 4에서 총 181~205ms·루프 최대 지연 3.4ms(안정). 처리량은 오히려 조금 낫고
# 동시 해싱 메모리가 1.28GiB에서 256MiB로 묶인다.
_HASH_LIMITER = CapacityLimiter(4)

_INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다"


@cache
def _dummy_password_hash() -> str:
    """사용자가 없을 때 검증할 더미 해시. 첫 호출에서만 실제로 계산한다.

    이것이 없으면 "이메일 없음" 경로만 해시 검증을 건너뛰어 눈에 띄게 빨라지고,
    응답 시간 차이로 가입 여부를 알아낼 수 있다. import 시점에 만들면 이 값을 쓰지도
    않을 프로세스(워커·CLI)까지 64MiB 해싱 비용을 치르므로 첫 사용까지 미룬다.
    """
    return _HASHER.hash("dummy-password-for-constant-time-login")


async def hash_password(password: str) -> str:
    """비밀번호를 argon2 해시로 만든다 (솔트는 argon2가 자동 생성).

    argon2는 의도적으로 느리다(위 파라미터로 약 23ms). 이벤트 루프에서 직접 돌리면
    그 시간 동안 다른 모든 요청이 멈춰 동시 로그인이 직렬화되고 /health까지 밀린다.
    argon2-cffi는 계산 중 GIL을 놓으므로 워커 스레드로 넘기면 실제로 병렬 처리된다.
    동시 실행 수는 _HASH_LIMITER로 묶는다(메모리 폭주 방지 — 위 주석 참고).
    """
    return await to_thread.run_sync(_HASHER.hash, password, limiter=_HASH_LIMITER)


async def verify_password(password_hash: str, password: str) -> bool:
    """해시와 비밀번호가 맞는지 확인한다 (해싱과 같은 이유로 스레드에서 수행).

    argon2 예외를 밖으로 흘리지 않고 불리언으로 정규화한다 — 호출부가 "불일치"와
    "해시 문자열이 깨졌음"을 구분해 분기하면 실패 사유가 응답으로 새기 쉽다.
    """
    try:
        return await to_thread.run_sync(
            _HASHER.verify, password_hash, password, limiter=_HASH_LIMITER
        )
    except (VerificationError, InvalidHashError):
        return False


class UserStore(Protocol):
    """AuthService가 사용자 저장소에 요구하는 계약.

    구현은 `app.repositories.users.UserRepository`이며, 서비스가 SQLAlchemy 세션에
    직접 묶이지 않게 하려고 소비자 쪽에 인터페이스를 둔다(테스트는 fake로 대체).
    `deps.get_user_repository`가 이 타입을 반환한다고 선언해, 실제 구현이 계약을
    만족하는지 mypy가 검사하게 했다.
    """

    async def create(self, *, email: str, password_hash: str) -> User:
        """사용자를 저장 대기 상태로 만든다(커밋하지 않는다). 중복이면 도메인 예외."""
        ...

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다."""
        ...

    async def get_by_email(self, email: str) -> User | None:
        """이메일로 사용자를 찾는다. 없으면 None."""
        ...

    async def get_by_id(self, user_id: uuid.UUID) -> User | None:
        """식별자로 사용자를 찾는다. 없으면 None."""
        ...


class AuthService:
    """가입·로그인·토큰 검증.

    트랜잭션 경계는 이 계층이 소유한다 — 저장소는 flush까지만 하고 커밋 시점은
    서비스가 정한다. 다음 미션의 "INSERT → commit → 큐에 작업 등록" 같은 순서 제어가
    저장소 안에 숨은 커밋에 좌우되면 안 되기 때문이다.
    """

    def __init__(self, repository: UserStore, jwt_secret: str, expire_minutes: int) -> None:
        """서비스를 조립한다.

        Raises:
            ConfigurationError: 서명 키가 MIN_JWT_SECRET_BYTES 미만이다. 키 값 자체는
                메시지에 담지 않는다 — 길이만으로도 진단에 충분하다.
        """
        if len(jwt_secret.encode()) < MIN_JWT_SECRET_BYTES:
            raise ConfigurationError(
                f"인증 서명 키는 {MIN_JWT_SECRET_BYTES}바이트 이상이어야 합니다"
            )
        self._repository = repository
        self._jwt_secret = jwt_secret
        self._expire_minutes = expire_minutes

    @property
    def access_token_lifetime_seconds(self) -> int:
        """발급되는 액세스 토큰의 유효 기간(초). 클라이언트 재발급 타이밍 계산용."""
        return self._expire_minutes * 60

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
        user = await self._repository.create(
            email=normalized, password_hash=await hash_password(password)
        )
        await self._repository.commit()
        return user

    async def login(self, email: str, password: str) -> str:
        """자격증명을 확인하고 액세스 토큰을 발급한다.

        Raises:
            InvalidCredentialsError: 이메일이 없거나 비밀번호가 다르다(구분하지 않음).
        """
        user = await self._repository.get_by_email(self._normalize_email(email))
        # 사용자가 없어도 검증을 건너뛰지 않는다 — 조기 반환하면 이 경로만 빨라져
        # 응답 시간이 가입 여부를 알려준다.
        stored_hash = (
            user.password_hash
            if user is not None
            # 캐시된 값이라 보통 즉시 반환되고, 첫 계산도 루프 밖에서 이뤄진다.
            else await to_thread.run_sync(_dummy_password_hash, limiter=_HASH_LIMITER)
        )
        matched = await verify_password(stored_hash, password)
        if user is None or not matched:
            raise InvalidCredentialsError(_INVALID_CREDENTIALS_MESSAGE)
        await self._rehash_if_outdated(user, password)
        return self._issue_token(user.id)

    def resolve_token(self, token: str) -> uuid.UUID:
        """액세스 토큰을 검증하고 사용자 식별자를 꺼낸다.

        Raises:
            InvalidCredentialsError: 서명이 틀렸거나 만료됐거나 용도·형식이 어긋난다.
        """
        try:
            claims = jwt.decode(
                token,
                self._jwt_secret,
                algorithms=[_ALGORITHM],
                # exp 없는 토큰은 만료되지 않는 영구 자격증명이 되므로 필수로 요구한다.
                options={"require": ["sub", "exp", "typ"]},
            )
            if claims["typ"] != _TOKEN_TYPE:
                # 다른 용도로 발급한 토큰(이메일 인증 등)을 액세스 토큰으로 쓰려는 시도.
                raise InvalidCredentialsError(_INVALID_CREDENTIALS_MESSAGE)
            # str()로 감싸지 않는다 — sub가 문자열이 아니면 그 자체로 우리가 발급한
            # 토큰이 아니며, TypeError를 아래에서 같은 예외로 정규화한다.
            return uuid.UUID(claims["sub"])
        except (jwt.PyJWTError, TypeError, ValueError) as exc:
            # 토큰 값을 메시지에 담지 않는다 — 토큰 자체가 자격증명이다.
            raise InvalidCredentialsError(_INVALID_CREDENTIALS_MESSAGE) from exc

    async def _rehash_if_outdated(self, user: User, password: str) -> None:
        """해시가 예전 파라미터로 만들어졌으면 지금 파라미터로 다시 저장한다.

        평문 비밀번호를 손에 쥐는 유일한 순간이 로그인 성공 직후다. 파라미터를 올린 뒤
        사용자가 로그인할 때마다 조금씩 이관되므로 별도 일괄 마이그레이션이 필요 없다.

        실패해도 로그인은 계속 진행한다(best-effort). 이관은 부가 작업인데, 파라미터를
        올린 직후 DB가 잠깐 흔들리면 재해시 대상인 **모든** 사용자가 로그인하지 못하는
        상황으로 번진다 — 자격증명 검증은 이미 끝났으므로 인증을 막을 이유가 없다.
        """
        if not _HASHER.check_needs_rehash(user.password_hash):
            return
        try:
            user.password_hash = await hash_password(password)
            await self._repository.commit()
        except Exception as exc:
            # 예외 타입만 남긴다. 메시지에는 SQL 파라미터(이메일)가 섞일 수 있고,
            # 트레이스백도 같은 이유로 남기지 않는다.
            _logger.warning("비밀번호 재해시 실패: %s", type(exc).__name__)

    def _issue_token(self, user_id: uuid.UUID) -> str:
        """sub·exp·typ만 담은 HS256 토큰을 만든다 (이메일 등 개인정보 금지)."""
        expires_at = datetime.now(UTC) + timedelta(minutes=self._expire_minutes)
        claims: dict[str, str | datetime] = {
            "sub": str(user_id),
            "exp": expires_at,
            "typ": _TOKEN_TYPE,
        }
        return jwt.encode(claims, self._jwt_secret, algorithm=_ALGORITHM)

    @staticmethod
    def _normalize_email(email: str) -> str:
        """가입과 로그인이 같은 키를 쓰도록 공백·대소문자를 정규화한다."""
        return email.strip().lower()
