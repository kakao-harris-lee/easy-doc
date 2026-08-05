"""API 의존성 — 요청 단위 세션·저장소·서비스 조립과 Bearer 인증.

여기서 조립만 하고 판단은 하지 않는다. 자격증명 검증 규칙은 AuthService에 있다.
"""

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings
from app.exceptions import ConfigurationError, InvalidCredentialsError
from app.models.user import User
from app.repositories.users import UserRepository
from app.services.auth import AuthService

# auto_error=False: 헤더가 없거나 스킴이 틀릴 때 FastAPI가 자체 형식의 403/401을
# 만들어 내지 않게 한다. 우리 도메인 예외로 통일해야 응답 모양이 하나로 유지된다.
_bearer_scheme = HTTPBearer(auto_error=False)


def get_settings(request: Request) -> Settings:
    """lifespan이 app.state에 넣어둔 설정을 꺼낸다."""
    settings: Settings = request.app.state.settings
    return settings


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    """요청 단위 DB 세션. lifespan이 만든 세션 팩토리를 쓴다."""
    session_factory = getattr(request.app.state, "session_factory", None)
    if session_factory is None:
        raise ConfigurationError("데이터베이스가 준비되지 않았습니다")
    async with session_factory() as session:
        yield session


def get_user_repository(session: Annotated[AsyncSession, Depends(get_session)]) -> UserRepository:
    """사용자 저장소. 테스트는 이 의존성을 fake로 갈아끼운다."""
    return UserRepository(session)


def get_auth_service(
    repository: Annotated[UserRepository, Depends(get_user_repository)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AuthService:
    """인증 서비스. JWT 비밀키가 없으면 운영 설정 문제로 503을 낸다.

    기동 자체는 막지 않는다 — 비밀키 없이도 /health로 배포 상태를 진단할 수 있어야 한다.
    """
    if settings.jwt_secret is None:
        raise ConfigurationError("인증이 설정되지 않았습니다")
    return AuthService(
        repository=repository,
        jwt_secret=settings.jwt_secret.get_secret_value(),
        expire_minutes=settings.jwt_expire_minutes,
    )


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer_scheme)],
    service: Annotated[AuthService, Depends(get_auth_service)],
    repository: Annotated[UserRepository, Depends(get_user_repository)],
) -> User:
    """Bearer 토큰으로 현재 사용자를 찾는다.

    헤더 누락·토큰 위조·계정 삭제를 모두 같은 401로 처리한다 — 어디서 실패했는지
    알려주면 유효한 토큰 형식을 탐색하는 단서가 된다.
    """
    if credentials is None:
        raise InvalidCredentialsError("인증이 필요합니다")
    user = await repository.get_by_id(service.resolve_token(credentials.credentials))
    if user is None:
        raise InvalidCredentialsError("인증 정보가 유효하지 않습니다")
    return user


AuthServiceDep = Annotated[AuthService, Depends(get_auth_service)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]
