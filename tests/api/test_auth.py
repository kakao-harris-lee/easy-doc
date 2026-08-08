"""인증 API 테스트.

DB 없이 돈다 — 저장소 의존성만 fake로 갈아끼우고 라우터·서비스·예외 핸들러는
실제 코드를 그대로 태운다.
"""

import uuid
from collections.abc import Iterator
from contextlib import contextmanager

import jwt
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.api.deps import get_settings, get_user_repository, get_workspace_repository
from app.config import Settings
from app.main import app
from app.models.workspace import DEFAULT_WORKSPACE_NAME
from tests.fakes import FakeUserRepository, FakeWorkspaceStore

_SECRET = "test-secret-do-not-use-in-production"
_EMAIL = "user@example.com"
_PASSWORD = "sufficiently-long-password"


def _settings(jwt_secret: str | None = _SECRET, expire_minutes: int = 60) -> Settings:
    """테스트용 설정.

    생성자 인자는 .env·환경변수보다 우선하므로(None을 넘겨도 마찬가지) 개발자 로컬
    JWT_SECRET이 있어도 테스트 결과가 흔들리지 않는다.
    """
    return Settings(
        jwt_secret=SecretStr(jwt_secret) if jwt_secret is not None else None,
        jwt_expire_minutes=expire_minutes,
    )


@contextmanager
def _client_with(
    repository: FakeUserRepository,
    settings: Settings,
    workspaces: FakeWorkspaceStore | None = None,
) -> Iterator[TestClient]:
    """저장소·설정만 오버라이드한 테스트 클라이언트.

    가입이 기본 작업 공간까지 만들므로 작업 공간 저장소도 대역으로 갈아끼운다 —
    갈아끼우지 않으면 DB 세션을 찾다가 503이 된다.
    """
    app.dependency_overrides[get_user_repository] = lambda: repository
    # 요청마다 새로 만들면 가입이 남긴 작업 공간을 다음 요청이 보지 못한다 — 실제
    # 저장소가 같은 세션을 공유하는 것처럼 하나를 계속 쓴다.
    workspace_store = workspaces if workspaces is not None else FakeWorkspaceStore()
    app.dependency_overrides[get_workspace_repository] = lambda: workspace_store
    app.dependency_overrides[get_settings] = lambda: settings
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        app.dependency_overrides.clear()


@pytest.fixture
def repository() -> FakeUserRepository:
    """테스트마다 비어 있는 저장소."""
    return FakeUserRepository()


@pytest.fixture
def client(repository: FakeUserRepository) -> Iterator[TestClient]:
    """기본 설정(정상 JWT 비밀키)으로 구성한 클라이언트."""
    with _client_with(repository, _settings()) as test_client:
        yield test_client


def _signup(client: TestClient, email: str = _EMAIL, password: str = _PASSWORD) -> dict[str, str]:
    """가입 후 응답 본문을 돌려준다(성공을 전제로 하는 헬퍼)."""
    response = client.post("/auth/signup", json={"email": email, "password": password})
    assert response.status_code == 201, response.text
    body: dict[str, str] = response.json()
    return body


def _login(client: TestClient, email: str = _EMAIL, password: str = _PASSWORD) -> str:
    """로그인 후 액세스 토큰을 돌려준다(성공을 전제로 하는 헬퍼)."""
    response = client.post("/auth/login", json={"email": email, "password": password})
    assert response.status_code == 200, response.text
    token: str = response.json()["access_token"]
    return token


# --- 정상 흐름 -----------------------------------------------------------------


def test_가입하고_로그인해서_내_정보를_본다(client: TestClient) -> None:
    created = _signup(client)
    token = _login(client)

    response = client.get("/auth/me", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    assert response.json() == {"id": created["id"], "email": _EMAIL}


def test_로그인_응답은_bearer_토큰이다(client: TestClient) -> None:
    _signup(client)

    response = client.post("/auth/login", json={"email": _EMAIL, "password": _PASSWORD})

    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["access_token"]
    # 클라이언트가 토큰을 디코딩하지 않고도 재발급 시점을 알 수 있어야 한다.
    assert body["expires_in"] == 3600


def test_응답에_비밀번호와_해시가_없다(client: TestClient) -> None:
    """UserResponse가 ORM 객체를 그대로 직렬화하면 해시가 새어 나간다."""
    signup_response = client.post("/auth/signup", json={"email": _EMAIL, "password": _PASSWORD})

    assert set(signup_response.json()) == {"id", "email"}
    assert _PASSWORD not in signup_response.text
    assert "argon2" not in signup_response.text


def test_가입_직후_기본_작업_공간이_보인다(repository: FakeUserRepository) -> None:
    """새 사용자가 빈 상태로 시작하지 않아야 한다 — 첫 업로드가 갈 곳이 있어야 한다."""
    workspaces = FakeWorkspaceStore()

    with _client_with(repository, _settings(), workspaces) as client:
        _signup(client)
        token = _login(client)
        response = client.get("/workspaces", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200, response.text
    items = response.json()["items"]
    assert [item["name"] for item in items] == [DEFAULT_WORKSPACE_NAME]
    assert items[0]["document_count"] == 0


def test_이메일_대소문자가_달라도_같은_계정이다(client: TestClient) -> None:
    created = _signup(client, email="User@Example.com")

    token = _login(client, email="USER@EXAMPLE.COM")

    response = client.get("/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert response.json()["id"] == created["id"]


# --- 실패 응답 -----------------------------------------------------------------


def test_중복_가입은_409(client: TestClient) -> None:
    _signup(client)

    response = client.post("/auth/signup", json={"email": _EMAIL, "password": _PASSWORD})

    assert response.status_code == 409
    assert _EMAIL not in response.text


@pytest.mark.parametrize(
    ("email", "password"),
    [
        ("not-an-email", _PASSWORD),  # 이메일 형식 오류
        (_EMAIL, "short"),  # 비밀번호 8자 미만
    ],
)
def test_입력_규칙_위반은_422(client: TestClient, email: str, password: str) -> None:
    response = client.post("/auth/signup", json={"email": email, "password": password})

    assert response.status_code == 422
    assert password not in response.text
    assert email not in response.text


def test_본문_검증_실패_응답에_입력값이_담기지_않는다(client: TestClient) -> None:
    """FastAPI 기본 422 응답은 detail.input에 요청 본문을 그대로 실어 보낸다."""
    response = client.post("/auth/signup", json={"email": _EMAIL, "password": 12345678})

    assert response.status_code == 422
    assert _EMAIL not in response.text
    assert "12345678" not in response.text
    # 어디가 왜 틀렸는지는 남아야 고칠 수 있다.
    assert "password" in response.text


def test_없는_계정과_틀린_비밀번호는_같은_401을_준다(client: TestClient) -> None:
    """응답이 다르면 가입 여부를 알아낼 수 있다(계정 열거)."""
    _signup(client)

    unknown = client.post("/auth/login", json={"email": "ghost@example.com", "password": _PASSWORD})
    wrong = client.post("/auth/login", json={"email": _EMAIL, "password": "wrong-password-here"})

    assert unknown.status_code == wrong.status_code == 401
    assert unknown.json() == wrong.json()
    assert _EMAIL not in wrong.text


# --- 인증 헤더 ----------------------------------------------------------------


def test_인증_헤더가_없으면_401(client: TestClient) -> None:
    response = client.get("/auth/me")

    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"


@pytest.mark.parametrize(
    "header",
    ["Bearer garbage", "Bearer ", "Basic dXNlcjpwYXNz", "Bearer a.b.c", ""],
)
def test_잘못된_인증_헤더는_401(client: TestClient, header: str) -> None:
    response = client.get("/auth/me", headers={"Authorization": header})

    assert response.status_code == 401


def test_만료된_토큰은_401(repository: FakeUserRepository) -> None:
    with _client_with(repository, _settings(expire_minutes=-1)) as client:
        _signup(client)
        expired = _login(client)

        response = client.get("/auth/me", headers={"Authorization": f"Bearer {expired}"})

    assert response.status_code == 401


def test_서명이_유효해도_없는_사용자면_401(client: TestClient) -> None:
    """계정이 지워진 뒤에도 남아 있는 토큰으로 들어오는 경로."""
    orphan = jwt.encode({"sub": str(uuid.uuid4()), "exp": 1 << 40}, _SECRET, "HS256")

    response = client.get("/auth/me", headers={"Authorization": f"Bearer {orphan}"})

    assert response.status_code == 401


# --- 설정 누락 ----------------------------------------------------------------


def test_jwt_비밀키가_없으면_503(repository: FakeUserRepository) -> None:
    """운영 설정 누락은 사용자 잘못이 아니다 — 4xx가 아니라 5xx로 알린다."""
    with _client_with(repository, _settings(jwt_secret=None)) as client:
        response = client.post("/auth/login", json={"email": _EMAIL, "password": _PASSWORD})

    assert response.status_code == 503
    assert response.json()["detail"]


def test_비밀키가_없어도_헬스체크는_동작한다(repository: FakeUserRepository) -> None:
    """설정이 비어도 기동 자체는 되어야 배포 후 진단이 가능하다."""
    with _client_with(repository, _settings(jwt_secret=None)) as client:
        assert client.get("/health").status_code == 200
