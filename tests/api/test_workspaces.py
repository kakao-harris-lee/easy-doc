"""작업 공간 API 테스트.

DB 없이 돈다 — 저장소 의존성만 fake로 갈아끼우고 라우터·서비스·예외 핸들러는 실제
코드를 그대로 태운다.

여기서 지키려는 불변식은 셋이다: 소유자 격리(남의 공간은 404), 되돌릴 수 없는 삭제를
막는 규칙(문서가 남았거나 마지막 하나면 409), 그리고 이름 규칙(빈 값·길이 상한)이다.
"""

import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.api.deps import get_settings, get_user_repository, get_workspace_repository
from app.config import Settings
from app.main import app
from app.models.document import Document
from app.models.user import User
from app.models.workspace import DEFAULT_WORKSPACE_NAME, MAX_WORKSPACE_NAME_LENGTH
from tests.fakes import FakeDocumentStore, FakeUserRepository, FakeWorkspaceStore

_SECRET = "test-secret-do-not-use-in-production"


class Fixture:
    """테스트 한 건이 쓰는 대역 묶음 (사용자·작업 공간·문서)."""

    def __init__(self) -> None:
        self.documents = FakeDocumentStore()
        self.workspaces = FakeWorkspaceStore(self.documents)
        self.users = FakeUserRepository()
        self.user = self._register("owner@example.com")
        #: 가입이 만들어 주는 기본 작업 공간 (AuthService.signup과 같은 상태).
        self.default = self.workspaces.add(self.user.id, DEFAULT_WORKSPACE_NAME)

    def _register(self, email: str) -> User:
        """가입 흐름(argon2)을 거치지 않고 사용자를 넣는다."""
        user = User(
            id=uuid.uuid4(),
            email=email,
            password_hash="$argon2id$fake",
            created_at=datetime.now(UTC),
        )
        self.users.add(user)
        return user

    def other_user(self) -> User:
        """다른 소유자 — 격리 테스트용."""
        return self._register("stranger@example.com")

    def headers(self, user: User | None = None) -> dict[str, str]:
        """Bearer 인증 헤더. 토큰은 AuthService가 발급하는 것과 같은 모양이다."""
        claims = {
            "sub": str((user or self.user).id),
            "exp": datetime.now(UTC) + timedelta(minutes=10),
            "typ": "access",
        }
        return {"Authorization": f"Bearer {jwt.encode(claims, _SECRET, 'HS256')}"}

    def add_document(self, workspace_id: uuid.UUID) -> None:
        """작업 공간에 문서 한 건을 넣는다 (삭제 거절·문서 수 경로용)."""
        document = Document(
            id=uuid.uuid4(),
            user_id=self.user.id,
            workspace_id=workspace_id,
            title="재난지원금 안내",
            source_format="text",
            source_text_encrypted=b"gAAAAA-fake",
            char_count=12,
            created_at=datetime.now(UTC),
        )
        self.documents.documents[document.id] = document


@contextmanager
def _client_with(fixture: Fixture) -> Iterator[TestClient]:
    """대역을 주입한 테스트 클라이언트."""
    app.dependency_overrides[get_user_repository] = lambda: fixture.users
    app.dependency_overrides[get_workspace_repository] = lambda: fixture.workspaces
    app.dependency_overrides[get_settings] = lambda: Settings(jwt_secret=SecretStr(_SECRET))
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        app.dependency_overrides.clear()


@pytest.fixture
def fixture() -> Fixture:
    """테스트마다 비어 있는 대역 묶음."""
    return Fixture()


@pytest.fixture
def client(fixture: Fixture) -> Iterator[TestClient]:
    """정상 설정으로 구성한 클라이언트."""
    with _client_with(fixture) as test_client:
        yield test_client


def _create(client: TestClient, fixture: Fixture, name: str) -> Any:
    """작업 공간을 만든다(응답을 그대로 돌려준다 — 실패 경로도 본다)."""
    return client.post("/workspaces", json={"name": name}, headers=fixture.headers())


# --- 목록 ---------------------------------------------------------------------


def test_목록은_만든_순서대로_문서_수와_함께_나온다(client: TestClient, fixture: Fixture) -> None:
    created = _create(client, fixture, "민원 안내").json()
    fixture.add_document(fixture.default.id)

    body = client.get("/workspaces", headers=fixture.headers()).json()

    assert [item["name"] for item in body["items"]] == [DEFAULT_WORKSPACE_NAME, "민원 안내"]
    assert [item["document_count"] for item in body["items"]] == [1, 0]
    assert body["items"][1]["id"] == created["id"]


def test_목록에_남의_작업_공간은_섞이지_않는다(client: TestClient, fixture: Fixture) -> None:
    stranger = fixture.other_user()
    fixture.workspaces.add(stranger.id, "남의 공간")

    body = client.get("/workspaces", headers=fixture.headers()).json()

    assert [item["name"] for item in body["items"]] == [DEFAULT_WORKSPACE_NAME]


def test_목록_응답은_캐시하지_않는다(client: TestClient, fixture: Fixture) -> None:
    """이름은 사용자가 적은 콘텐츠다 — 중간 캐시에 남으면 소유자 검증이 무력해진다."""
    response = client.get("/workspaces", headers=fixture.headers())

    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-content-type-options"] == "nosniff"


# --- 만들기 -------------------------------------------------------------------


def test_작업_공간을_만들면_201이다(client: TestClient, fixture: Fixture) -> None:
    response = _create(client, fixture, "민원 안내")

    assert response.status_code == 201, response.text
    assert response.json()["name"] == "민원 안내"
    assert uuid.UUID(response.json()["id"])


def test_같은_이름은_409(client: TestClient, fixture: Fixture) -> None:
    _create(client, fixture, "민원 안내")

    response = _create(client, fixture, "민원 안내")

    assert response.status_code == 409
    assert "이미" in response.json()["detail"]


def test_다른_사용자는_같은_이름을_쓸_수_있다(client: TestClient, fixture: Fixture) -> None:
    """이름 유일성의 범위는 사용자 한 명이다."""
    stranger = fixture.other_user()
    _create(client, fixture, "민원 안내")

    response = client.post(
        "/workspaces", json={"name": "민원 안내"}, headers=fixture.headers(stranger)
    )

    assert response.status_code == 201, response.text


@pytest.mark.parametrize("name", ["", "   ", "\x00\x0b"])
def test_빈_이름은_422(client: TestClient, fixture: Fixture, name: str) -> None:
    """제어문자만 담긴 이름은 걷어내고 나면 빈 이름이다 — 같은 취급을 받아야 한다."""
    assert _create(client, fixture, name).status_code == 422


def test_상한을_넘는_이름은_422(client: TestClient, fixture: Fixture) -> None:
    """자르지 않고 거절한다 — 사용자가 직접 적은 이름을 말없이 줄이면 뜻이 사라진다."""
    response = _create(client, fixture, "가" * (MAX_WORKSPACE_NAME_LENGTH + 1))

    assert response.status_code == 422
    assert len(fixture.workspaces.workspaces) == 1


def test_상한_길이_이름은_통과한다(client: TestClient, fixture: Fixture) -> None:
    """경계값 — 50자 자체는 허용되어야 한다."""
    assert _create(client, fixture, "가" * MAX_WORKSPACE_NAME_LENGTH).status_code == 201


def test_이름_필드가_없으면_422(client: TestClient, fixture: Fixture) -> None:
    response = client.post("/workspaces", json={}, headers=fixture.headers())

    assert response.status_code == 422


# --- 이름 바꾸기 ---------------------------------------------------------------


def test_이름을_바꾸면_바뀐_값을_돌려준다(client: TestClient, fixture: Fixture) -> None:
    response = client.patch(
        f"/workspaces/{fixture.default.id}",
        json={"name": "복지 안내"},
        headers=fixture.headers(),
    )

    assert response.status_code == 200, response.text
    assert response.json()["name"] == "복지 안내"
    body = client.get("/workspaces", headers=fixture.headers()).json()
    assert [item["name"] for item in body["items"]] == ["복지 안내"]


def test_남의_작업_공간_이름은_바꿀_수_없다(client: TestClient, fixture: Fixture) -> None:
    """소유자 격리 — 있다는 사실 자체를 알리지 않는다(403이 아니라 404)."""
    stranger = fixture.other_user()

    response = client.patch(
        f"/workspaces/{fixture.default.id}",
        json={"name": "빼앗기"},
        headers=fixture.headers(stranger),
    )

    assert response.status_code == 404
    assert fixture.default.name == DEFAULT_WORKSPACE_NAME


def test_없는_작업_공간_이름_변경은_404(client: TestClient, fixture: Fixture) -> None:
    response = client.patch(
        f"/workspaces/{uuid.uuid4()}", json={"name": "없는 것"}, headers=fixture.headers()
    )

    assert response.status_code == 404


def test_이미_쓰는_이름으로는_바꿀_수_없다(client: TestClient, fixture: Fixture) -> None:
    target = _create(client, fixture, "민원 안내").json()

    response = client.patch(
        f"/workspaces/{target['id']}",
        json={"name": DEFAULT_WORKSPACE_NAME},
        headers=fixture.headers(),
    )

    assert response.status_code == 409


def test_빈_이름으로는_바꿀_수_없다(client: TestClient, fixture: Fixture) -> None:
    response = client.patch(
        f"/workspaces/{fixture.default.id}", json={"name": "   "}, headers=fixture.headers()
    )

    assert response.status_code == 422
    assert fixture.default.name == DEFAULT_WORKSPACE_NAME


# --- 삭제 ---------------------------------------------------------------------


def test_빈_작업_공간은_지워진다(client: TestClient, fixture: Fixture) -> None:
    created = _create(client, fixture, "민원 안내").json()

    response = client.delete(f"/workspaces/{created['id']}", headers=fixture.headers())

    assert response.status_code == 204, response.text
    assert response.content == b""
    body = client.get("/workspaces", headers=fixture.headers()).json()
    assert [item["name"] for item in body["items"]] == [DEFAULT_WORKSPACE_NAME]


def test_문서가_남은_작업_공간은_409(client: TestClient, fixture: Fixture) -> None:
    """삭제로 문서를 함께 잃는 경로를 만들지 않는다 — 무엇이 사라지는지 먼저 보게 한다."""
    created = _create(client, fixture, "민원 안내").json()
    fixture.add_document(uuid.UUID(created["id"]))

    response = client.delete(f"/workspaces/{created['id']}", headers=fixture.headers())

    assert response.status_code == 409
    assert "먼저 비운" in response.json()["detail"]
    assert uuid.UUID(created["id"]) in fixture.workspaces.workspaces


def test_마지막_작업_공간은_지울_수_없다(client: TestClient, fixture: Fixture) -> None:
    """전부 지우면 업로드가 갈 곳을 잃는다."""
    response = client.delete(f"/workspaces/{fixture.default.id}", headers=fixture.headers())

    assert response.status_code == 409
    assert "적어도 하나" in response.json()["detail"]


def test_남의_작업_공간은_지울_수_없다(client: TestClient, fixture: Fixture) -> None:
    stranger = fixture.other_user()
    fixture.workspaces.add(stranger.id, "남의 공간")
    created = _create(client, fixture, "민원 안내").json()

    response = client.delete(f"/workspaces/{created['id']}", headers=fixture.headers(stranger))

    assert response.status_code == 404
    assert uuid.UUID(created["id"]) in fixture.workspaces.workspaces


def test_없는_작업_공간_삭제는_404(client: TestClient, fixture: Fixture) -> None:
    response = client.delete(f"/workspaces/{uuid.uuid4()}", headers=fixture.headers())

    assert response.status_code == 404


# --- 인증 ---------------------------------------------------------------------


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("get", "/workspaces"),
        ("post", "/workspaces"),
        ("patch", f"/workspaces/{uuid.uuid4()}"),
        ("delete", f"/workspaces/{uuid.uuid4()}"),
    ],
)
def test_인증_없이는_401(client: TestClient, method: str, path: str) -> None:
    response = client.request(method, path, json={"name": "민원 안내"})

    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
