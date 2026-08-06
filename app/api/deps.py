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
from app.privacy.crypto import TextCipher
from app.queue import TaskQueue
from app.repositories.conversions import ConversionRepository
from app.repositories.documents import DocumentRepository
from app.repositories.users import UserRepository
from app.services.auth import AuthService, UserStore
from app.services.documents import ConversionStore, DocumentService, DocumentStore

# auto_error=False: 헤더가 없거나 스킴이 틀릴 때 FastAPI가 자체 형식의 403/401을
# 만들어 내지 않게 한다. 우리 도메인 예외로 통일해야 응답 모양이 하나로 유지된다.
_bearer_scheme = HTTPBearer(auto_error=False)


def get_settings(request: Request) -> Settings:
    """lifespan이 app.state에 넣어둔 설정을 꺼낸다."""
    settings: Settings | None = getattr(request.app.state, "settings", None)
    if settings is None:
        # lifespan을 거치지 않고 앱이 조립된 상태 — 설정 문제이므로 5xx로 알린다.
        raise ConfigurationError("서버 설정이 준비되지 않았습니다")
    return settings


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    """요청 단위 DB 세션. lifespan이 만든 세션 팩토리를 쓴다."""
    session_factory = getattr(request.app.state, "session_factory", None)
    if session_factory is None:
        raise ConfigurationError("데이터베이스가 준비되지 않았습니다")
    async with session_factory() as session:
        yield session


def get_user_repository(session: Annotated[AsyncSession, Depends(get_session)]) -> UserStore:
    """사용자 저장소. 테스트는 이 의존성을 fake로 갈아끼운다.

    반환 타입을 Protocol로 선언해 두면 UserRepository가 계약을 만족하는지 mypy가
    이 return 문에서 검사한다 — 구현과 계약이 어긋나면 런타임 전에 잡힌다.
    """
    return UserRepository(session)


def get_auth_service(
    repository: Annotated[UserStore, Depends(get_user_repository)],
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
    repository: Annotated[UserStore, Depends(get_user_repository)],
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


def get_document_repository(
    session: Annotated[AsyncSession, Depends(get_session)],
) -> DocumentStore:
    """문서 저장소. 테스트는 이 의존성을 fake로 갈아끼운다."""
    return DocumentRepository(session)


def get_conversion_repository(
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ConversionStore:
    """변환 저장소. 문서 저장소와 **같은 요청 세션**을 받는다 — 커밋 하나로 둘이 함께
    확정되어야 문서만 남고 변환이 없는 상태가 생기지 않는다.
    """
    return ConversionRepository(session)


def get_text_cipher(settings: Annotated[Settings, Depends(get_settings)]) -> TextCipher:
    """문서 암호기. 키가 없으면 운영 설정 문제로 503을 낸다.

    기동 자체는 막지 않는다 — 키 없이도 /health로 배포 상태를 진단할 수 있어야 한다.
    """
    if settings.fernet_key is None:
        raise ConfigurationError("문서 저장이 설정되지 않았습니다")
    return TextCipher(settings.fernet_key.get_secret_value())


def get_task_queue(request: Request) -> TaskQueue:
    """작업 큐. lifespan이 Redis에 붙지 못했으면 503을 낸다."""
    queue: TaskQueue | None = getattr(request.app.state, "task_queue", None)
    if queue is None:
        raise ConfigurationError("작업 큐가 준비되지 않았습니다")
    return queue


def get_document_service(
    documents: Annotated[DocumentStore, Depends(get_document_repository)],
    conversions: Annotated[ConversionStore, Depends(get_conversion_repository)],
    cipher: Annotated[TextCipher, Depends(get_text_cipher)],
    queue: Annotated[TaskQueue, Depends(get_task_queue)],
) -> DocumentService:
    """문서 서비스 조립."""
    return DocumentService(documents=documents, conversions=conversions, cipher=cipher, queue=queue)


AuthServiceDep = Annotated[AuthService, Depends(get_auth_service)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]
DocumentServiceDep = Annotated[DocumentService, Depends(get_document_service)]
