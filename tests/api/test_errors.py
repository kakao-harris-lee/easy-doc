"""도메인 예외 → HTTP 매핑 테스트.

라우터가 아니라 매핑 자체를 본다. 실제 앱을 건드리지 않으려고 일회용 앱에 핸들러만
등록해 확인한다.
"""

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.errors import register_exception_handlers
from app.exceptions import EasyDocError, LLMTruncatedError


def _app_raising(error: Exception) -> TestClient:
    """지정한 예외를 던지는 엔드포인트 하나짜리 앱."""
    app = FastAPI()
    register_exception_handlers(app)

    @app.get("/boom")
    async def boom() -> None:
        raise error

    return TestClient(app)


def test_매핑되지_않은_도메인_예외는_고정_메시지로_500이_된다() -> None:
    """새 도메인 예외를 만들고 _MAPPINGS 등록을 잊어도 응답 모양이 유지된다."""
    client = _app_raising(EasyDocError("내부 사정이 담긴 문자열"))

    response = client.get("/boom")

    assert response.status_code == 500
    assert response.json() == {"detail": "요청을 처리하지 못했습니다"}
    # 무엇이 담길지 모르는 예외 메시지를 그대로 내보내지 않는다.
    assert "내부 사정이 담긴 문자열" not in response.text


def test_하위_예외는_상위_매핑을_물려받는다() -> None:
    """Starlette가 MRO를 따라 핸들러를 찾으므로 하위 예외를 일일이 등록하지 않아도 된다."""
    client = _app_raising(LLMTruncatedError("변환 결과가 토큰 한도에서 잘렸습니다"))

    response = client.get("/boom")

    # LLMProviderError만 등록돼 있지만 그 하위인 LLMTruncatedError도 502로 나간다.
    assert response.status_code == 502
    assert response.json() == {"detail": "변환 결과가 토큰 한도에서 잘렸습니다"}
