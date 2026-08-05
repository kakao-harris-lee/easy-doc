"""인증 API 통합 테스트 — 실제 PostgreSQL로 가입→로그인→조회 왕복.

fake 저장소 테스트(test_auth.py)가 검증하지 못하는 것을 본다: 실제 unique 제약이
중복 가입을 막는지, ORM 매핑과 세션 수명이 라우터를 통과해도 성립하는지.

TestClient 대신 ASGITransport를 쓰는 이유: TestClient는 별도 스레드에서 자체
이벤트 루프를 돌린다. 테스트 픽스처가 만든 asyncpg 커넥션은 생성한 루프에 묶여
있어 다른 루프에서 쓰면 깨진다.
"""

from collections.abc import AsyncIterator

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import SecretStr
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.api.deps import get_session, get_settings
from app.config import Settings
from app.main import app

pytestmark = pytest.mark.db

_SECRET = "test-secret-do-not-use-in-production"
_EMAIL = "integration@example.com"
_PASSWORD = "sufficiently-long-password"


@pytest.fixture
async def client(
    db_session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncClient]:
    """롤백 격리된 세션을 앱에 주입한 HTTP 클라이언트."""

    async def _override_get_session() -> AsyncIterator[AsyncSession]:
        async with db_session_factory() as session:
            yield session

    app.dependency_overrides[get_session] = _override_get_session
    # 생성자 인자가 .env·환경변수를 이긴다 — 로컬 설정에 좌우되지 않는다.
    app.dependency_overrides[get_settings] = lambda: Settings(
        jwt_secret=SecretStr(_SECRET), jwt_expire_minutes=60
    )
    try:
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as http_client:
            yield http_client
    finally:
        app.dependency_overrides.clear()


async def test_실제_DB로_가입하고_로그인해서_내_정보를_본다(client: AsyncClient) -> None:
    signup = await client.post("/auth/signup", json={"email": _EMAIL, "password": _PASSWORD})
    assert signup.status_code == 201, signup.text

    login = await client.post("/auth/login", json={"email": _EMAIL, "password": _PASSWORD})
    assert login.status_code == 200, login.text

    token = login.json()["access_token"]
    me = await client.get("/auth/me", headers={"Authorization": f"Bearer {token}"})

    assert me.status_code == 200
    assert me.json() == {"id": signup.json()["id"], "email": _EMAIL}


async def test_실제_unique_제약이_중복_가입을_막는다(client: AsyncClient) -> None:
    """fake 저장소가 아니라 DB 제약이 판정하는 경로."""
    body = {"email": _EMAIL, "password": _PASSWORD}
    first = await client.post("/auth/signup", json=body)
    assert first.status_code == 201, first.text

    duplicate = await client.post("/auth/signup", json=body)

    assert duplicate.status_code == 409
    assert _EMAIL not in duplicate.text
