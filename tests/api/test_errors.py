"""도메인 예외 → HTTP 매핑 테스트.

라우터가 아니라 매핑 자체를 본다. 실제 앱을 건드리지 않으려고 일회용 앱에 핸들러만
등록해 확인한다.
"""

import logging

import pytest
from fastapi import FastAPI, Response
from fastapi.testclient import TestClient

from app.api.documents import PRIVATE_RESPONSE_HEADERS
from app.api.errors import register_exception_handlers
from app.exceptions import (
    ConflictError,
    EasyDocError,
    InvalidCredentialsError,
    LLMTruncatedError,
    NotFoundError,
)

# 캐시 금지 헤더 이름(소문자). httpx의 헤더 조회는 대소문자를 구분하지 않는다.
_PRIVATE_HEADER_NAMES = ("cache-control", "x-content-type-options")


def _app_raising(error: Exception) -> TestClient:
    """지정한 예외를 던지는 엔드포인트 하나짜리 앱."""
    app = FastAPI()
    register_exception_handlers(app)

    @app.get("/boom")
    async def boom() -> None:
        raise error

    return TestClient(app)


def _app_raising_after_private_headers(error: Exception) -> TestClient:
    """캐시 금지 헤더를 응답 객체에 적어 둔 **뒤에** 예외를 던지는 앱.

    성공 응답 10개를 내는 실제 라우터(`app/api/documents.py` 등)는
    `response.headers.update(PRIVATE_RESPONSE_HEADERS)`로 헤더를 적는다. 그 라우터가
    도중에 예외를 던지는 상황을 그대로 재현해, 적어 둔 헤더가 오류 응답으로 새어
    나오는지 본다.
    """
    app = FastAPI()
    register_exception_handlers(app)

    @app.get("/boom")
    async def boom(response: Response) -> None:
        response.headers.update(PRIVATE_RESPONSE_HEADERS)
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


def test_백스톱은_예외_타입을_로그에_남긴다(caplog: pytest.LogCaptureFixture) -> None:
    """조용히 500만 내보내면 매핑 누락을 아무도 모른 채 지나간다.

    로그 메시지 자체에는 타입만 담는다 — 도메인 예외 메시지에 무엇이 들어올지
    이 지점에서는 알 수 없기 때문이다(개인정보 금지 규칙).
    """
    client = _app_raising(EasyDocError("내부 사정이 담긴 문자열"))

    with caplog.at_level(logging.ERROR, logger="app.api.errors"):
        client.get("/boom")

    record = next(record for record in caplog.records if record.name == "app.api.errors")
    assert record.getMessage() == "매핑되지 않은 도메인 예외: EasyDocError"
    assert "내부 사정이 담긴 문자열" not in record.getMessage()
    # 원인 추적을 위한 트레이스백은 남긴다.
    assert record.exc_info is not None


def test_도메인_밖_예외도_같은_모양의_500이_된다() -> None:
    """예상하지 못한 예외(라이브러리 오류 등)까지 응답 모양을 하나로 유지한다.

    raise_server_exceptions=False: 이 백스톱은 ServerErrorMiddleware가 부르고, 그
    미들웨어는 응답을 만든 뒤 예외를 다시 올린다(서버 로그용). 기본 설정의 TestClient는
    그 예외를 그대로 터뜨려서 응답을 볼 수 없다.
    """
    app = FastAPI()
    register_exception_handlers(app)

    @app.get("/boom")
    async def boom() -> None:
        raise ValueError("라이브러리가 만든 내부 문자열")

    client = TestClient(app, raise_server_exceptions=False)

    response = client.get("/boom")

    assert response.status_code == 500
    assert response.json() == {"detail": "서버 오류가 발생했습니다"}
    # 무엇이 담길지 모르는 예외 메시지를 응답으로 내보내지 않는다.
    assert "라이브러리가 만든 내부 문자열" not in response.text


def test_하위_예외는_상위_매핑을_물려받는다() -> None:
    """Starlette가 MRO를 따라 핸들러를 찾으므로 하위 예외를 일일이 등록하지 않아도 된다."""
    client = _app_raising(LLMTruncatedError("변환 결과가 토큰 한도에서 잘렸습니다"))

    response = client.get("/boom")

    # LLMProviderError만 등록돼 있지만 그 하위인 LLMTruncatedError도 502로 나간다.
    assert response.status_code == 502
    assert response.json() == {"detail": "변환 결과가 토큰 한도에서 잘렸습니다"}


# --- 오류 응답 캐시 정책 (계약 §2.5 / §2.7 해결 3) ---------------------------------
#
# 캐시 금지 헤더(`Cache-Control: no-store` + `X-Content-Type-Options: nosniff`)는
# 개인정보·자격증명이 실리는 **성공 응답 10개에만** 붙는다. 오류 응답에는 붙지 않는 것이
# 계약이므로 "있다"뿐 아니라 **"없다"도** 고정한다. 성공 쪽 단언은
# `tests/api/test_auth.py`·`test_documents.py`·`test_workspaces.py`에 있다.
#
# **이 테스트가 잡지 못하는 것 — 반드시 알고 읽을 것.**
# Python에서는 `app/api/errors.py`의 모든 핸들러가 **새 `JSONResponse`를 만들어**
# 돌려주므로, 라우터가 `response.headers`에 적어 둔 값은 구조적으로 남을 수 없다.
# 즉 아래 테스트들은 지금의 구현을 그대로 통과하며, "라우터가 적은 헤더가 오류 응답에
# 상속된다"는 종류의 회귀는 **Python에서는 애초에 일어나지 않는다.**
# 그 회귀는 Kotlin/Spring MVC에서만 일어난다 — `HttpServletResponse`에 쓴 헤더는
# `@ExceptionHandler` 응답에도 남으므로, 순진하게 포팅하면 Python이 내지 않는 헤더를
# 낸다. 그것을 잡는 것은 Phase 3에서 만들 **Kotlin 계약 테스트**의 몫이다(아직 없다).
#
# 그럼에도 이 테스트를 두는 이유는 둘이다.
#   ① 누군가 Python 오류 핸들러에 헤더를 붙이면(예: `_make_handler`의 기본 헤더 추가,
#      전역 미들웨어 도입) 계약 위반이 즉시 드러난다.
#   ② Kotlin이 같은 계약 테스트를 이식할 때 이 파일이 원본이 된다.
#
# 덮는 조합을 이렇게 고른 이유 — `app/api/errors.py`의 핸들러 갈래가 셋이라 각 갈래를
# 최소 한 번씩 지난다. 401·404·409는 `_make_handler` 팩토리(그중 401만 추가 헤더를 함께
# 내는 갈래), 422는 `_handle_request_validation`, 500은 `_handle_unmapped_domain_error`.
# 413·502·503은 401·404·409와 같은 팩토리의 같은 갈래라 중복이므로 뺐다.


@pytest.mark.parametrize(
    ("error", "expected_status"),
    [
        (InvalidCredentialsError("이메일 또는 비밀번호가 올바르지 않습니다"), 401),
        (NotFoundError("문서를 찾을 수 없습니다"), 404),
        (ConflictError("변환이 끝난 뒤에 저장할 수 있습니다"), 409),
    ],
    ids=["401", "404", "409"],
)
def test_도메인_예외_오류_응답에는_캐시_금지_헤더가_없다(
    error: Exception, expected_status: int
) -> None:
    """라우터가 캐시 금지 헤더를 적어 둔 뒤 실패해도 오류 응답에는 그 헤더가 없다."""
    client = _app_raising_after_private_headers(error)

    response = client.get("/boom")

    assert response.status_code == expected_status
    for name in _PRIVATE_HEADER_NAMES:
        assert name not in response.headers


def test_검증_실패_응답에도_캐시_금지_헤더가_없다() -> None:
    """422는 팩토리가 아니라 `_handle_request_validation`이 만든다 — 따로 덮는다.

    검증은 라우터 본문 실행 **전에** 걸리므로 헤더를 적을 기회조차 없다. 그래도
    (엔드포인트, 상태 코드) 쌍으로 계약을 고정하는 것이 목적이라 단언해 둔다.
    """
    app = FastAPI()
    register_exception_handlers(app)

    @app.get("/boom")
    async def boom(response: Response, count: int) -> None:
        response.headers.update(PRIVATE_RESPONSE_HEADERS)

    client = TestClient(app)

    response = client.get("/boom", params={"count": "숫자가 아님"})

    assert response.status_code == 422
    for name in _PRIVATE_HEADER_NAMES:
        assert name not in response.headers


def test_백스톱_500_응답에도_캐시_금지_헤더가_없다() -> None:
    """미매핑 도메인 예외의 백스톱도 팩토리를 지나지 않는 별도 갈래다."""
    client = _app_raising_after_private_headers(EasyDocError("내부 사정이 담긴 문자열"))

    response = client.get("/boom")

    assert response.status_code == 500
    for name in _PRIVATE_HEADER_NAMES:
        assert name not in response.headers


def test_오류_핸들러는_매핑에_적힌_헤더는_그대로_내보낸다() -> None:
    """위 부정 단언이 공허하지 않음을 보이는 대조군.

    핸들러가 헤더를 아예 못 붙이는 것이라면 "캐시 금지 헤더가 없다"는 단언은 아무것도
    말하지 못한다. 401이 `WWW-Authenticate: Bearer`를 실제로 내보내므로, 헤더를 붙일 수
    있는데도 캐시 금지 헤더만 없는 것 — 즉 계약대로임 — 이 확인된다.
    """
    client = _app_raising_after_private_headers(
        InvalidCredentialsError("이메일 또는 비밀번호가 올바르지 않습니다")
    )

    response = client.get("/boom")

    assert response.status_code == 401
    assert response.headers["WWW-Authenticate"] == "Bearer"
