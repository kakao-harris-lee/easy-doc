"""CORS 설정 테스트.

미들웨어는 앱 조립 시점에 붙으므로(의존성 오버라이드가 닿지 않는다) 실제 앱이 들고
있는 설정값을 기준으로 확인한다.
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import app

#: 미들웨어가 실제로 허용하는 오리진. 인스턴스가 아니라 선언된 기본값에서 가져온다 —
#: 실행 환경의 .env·환경변수가 값을 덮어써도 이 테스트가 흔들리지 않게 한다.
_ALLOWED: str = Settings.model_fields["cors_origins"].default[0]


@pytest.fixture
def client() -> Iterator[TestClient]:
    """lifespan까지 실행하는 테스트 클라이언트."""
    with TestClient(app) as test_client:
        yield test_client


def test_기본_허용_오리진은_Vite_개발_서버다() -> None:
    """파일럿 배포는 nginx가 프론트·API를 같은 호스트로 서빙해 cross-origin이 아니다 —
    다른 오리진이 되는 것은 개발용 Vite 서버뿐이다.

    인스턴스가 아니라 선언된 기본값을 본다 — 실행 환경의 .env·환경변수가 값을 덮어써도
    "기본이 무엇인가"라는 이 테스트의 질문은 달라지지 않는다.
    """
    assert Settings.model_fields["cors_origins"].default == ["http://localhost:5173"]


def test_허용_오리진에는_CORS_헤더가_붙는다(client: TestClient) -> None:
    response = client.get("/health", headers={"Origin": _ALLOWED})

    assert response.headers["access-control-allow-origin"] == _ALLOWED
    # 인증은 Authorization 헤더로 한다 — 쿠키를 쓰지 않으므로 자격증명 허용을 켜서
    # CSRF 노출 면적을 넓힐 이유가 없다.
    assert "access-control-allow-credentials" not in response.headers


def test_모르는_오리진에는_CORS_헤더를_주지_않는다(client: TestClient) -> None:
    response = client.get("/health", headers={"Origin": "https://not-ours.example"})

    assert "access-control-allow-origin" not in response.headers


def test_preflight에_필요한_메서드와_헤더를_알려준다(client: TestClient) -> None:
    response = client.options(
        "/documents",
        headers={
            "Origin": _ALLOWED,
            "Access-Control-Request-Method": "PUT",
            "Access-Control-Request-Headers": "authorization",
        },
    )

    assert response.status_code == 200
    assert "PUT" in response.headers["access-control-allow-methods"]
    assert "authorization" in response.headers["access-control-allow-headers"].lower()


def test_다운로드_파일명_헤더를_브라우저에_노출한다(client: TestClient) -> None:
    """기본 노출 목록에 없는 헤더는 JS가 읽지 못한다 — 파일명이 안 보이면 다운로드가
    이름 없는 파일이 된다.
    """
    response = client.get("/health", headers={"Origin": _ALLOWED})

    exposed = response.headers["access-control-expose-headers"].lower()
    assert "content-disposition" in exposed
    assert "location" in exposed
