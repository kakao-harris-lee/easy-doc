"""인증 라우터 — 가입·로그인·내 정보.

입력 규칙(이메일 형식, 비밀번호 최소 길이)은 여기서 검사하지 않는다. AuthService가
단일 기준을 갖고, 라우터는 도메인 예외를 그대로 흘려보내 errors.py가 HTTP로 바꾼다.
"""

import uuid

from fastapi import APIRouter, status
from pydantic import BaseModel

from app.api.deps import AuthServiceDep, CurrentUserDep
from app.models.user import User

router = APIRouter(prefix="/auth", tags=["auth"])


class SignupRequest(BaseModel):
    """가입 요청."""

    email: str
    password: str


class LoginRequest(BaseModel):
    """로그인 요청."""

    email: str
    password: str


class UserResponse(BaseModel):
    """사용자 공개 표현. password_hash는 절대 포함하지 않는다."""

    id: uuid.UUID
    email: str


class TokenResponse(BaseModel):
    """액세스 토큰 응답."""

    access_token: str
    token_type: str = "bearer"
    #: 유효 기간(초). 클라이언트가 토큰을 디코딩해 exp를 읽지 않고도 재발급 시점을 안다.
    expires_in: int


def _to_response(user: User) -> UserResponse:
    """ORM 객체를 공개 필드만 남겨 옮긴다(해시 유출 차단)."""
    return UserResponse(id=user.id, email=user.email)


@router.post("/signup", status_code=status.HTTP_201_CREATED)
async def signup(payload: SignupRequest, service: AuthServiceDep) -> UserResponse:
    """이메일과 비밀번호로 계정을 만든다."""
    user = await service.signup(email=payload.email, password=payload.password)
    return _to_response(user)


@router.post("/login")
async def login(payload: LoginRequest, service: AuthServiceDep) -> TokenResponse:
    """자격증명을 확인하고 액세스 토큰을 발급한다."""
    token = await service.login(email=payload.email, password=payload.password)
    return TokenResponse(access_token=token, expires_in=service.access_token_lifetime_seconds)


@router.get("/me")
async def read_me(current_user: CurrentUserDep) -> UserResponse:
    """토큰이 가리키는 사용자 정보를 돌려준다."""
    return _to_response(current_user)
