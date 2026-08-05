"""헬스 체크 엔드포인트 테스트."""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture
def client() -> Iterator[TestClient]:
    """lifespan(startup/shutdown)까지 실행하는 테스트 클라이언트."""
    with TestClient(app) as test_client:
        yield test_client


def test_health_returns_ok(client: TestClient) -> None:
    """/health는 200과 {"status": "ok"}를 반환한다."""
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
