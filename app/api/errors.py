"""도메인 예외 → HTTP 응답 매핑.

응답 본문은 항상 `{"detail": ...}` 한 가지 모양이고, detail에는 도메인 예외가 스스로
만든 메시지만 담는다. 예외 메시지에 입력값을 넣지 않는다는 규약(services 계층)과
짝을 이뤄, 이메일·비밀번호·문서 본문이 응답이나 액세스 로그로 새지 않게 한다.
"""

import logging
from collections.abc import Awaitable, Callable

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.exceptions import (
    ConfigurationError,
    DocumentExtractionError,
    EasyDocError,
    EmailAlreadyRegisteredError,
    InvalidCredentialsError,
    InvalidInputError,
    LLMProviderError,
    NotFoundError,
    QueueUnavailableError,
    StorageError,
    UnsupportedFormatError,
    UploadTooLargeError,
)

_logger = logging.getLogger(__name__)

# (예외, 상태 코드, 추가 헤더).
# Starlette가 예외의 MRO를 따라 핸들러를 찾으므로 상위 예외 하나만 등록하면 하위
# 예외(LLMTruncatedError 등)도 같은 응답을 받는다. 등록 순서는 영향을 주지 않는다.
_MAPPINGS: tuple[tuple[type[EasyDocError], int, dict[str, str]], ...] = (
    (InvalidInputError, status.HTTP_422_UNPROCESSABLE_CONTENT, {}),
    # 업로드 파일 문제도 사용자가 고칠 수 있는 입력 오류다 — 415가 아니라 422로 통일한다.
    # Content-Type이 아니라 본문(파일) 자체를 처리할 수 없는 상황이기 때문.
    (UnsupportedFormatError, status.HTTP_422_UNPROCESSABLE_CONTENT, {}),
    (DocumentExtractionError, status.HTTP_422_UNPROCESSABLE_CONTENT, {}),
    # 크기 초과만 413으로 가른다 — "파일을 나눠 올리라"는 안내가 형식 오류와 다르다.
    (UploadTooLargeError, status.HTTP_413_CONTENT_TOO_LARGE, {}),
    (EmailAlreadyRegisteredError, status.HTTP_409_CONFLICT, {}),
    # WWW-Authenticate: 401에 요구되는 표준 헤더. 클라이언트가 재인증 방식을 안다.
    (InvalidCredentialsError, status.HTTP_401_UNAUTHORIZED, {"WWW-Authenticate": "Bearer"}),
    (NotFoundError, status.HTTP_404_NOT_FOUND, {}),
    (LLMProviderError, status.HTTP_502_BAD_GATEWAY, {}),
    # 큐 장애도 하위 시스템 장애다 — 재시도하면 되는 상황임을 502로 알린다.
    (QueueUnavailableError, status.HTTP_502_BAD_GATEWAY, {}),
    (ConfigurationError, status.HTTP_503_SERVICE_UNAVAILABLE, {}),
    # 서버 버그. 메시지는 저장소가 만든 고정 문자열이라 그대로 내보내도 안전하다.
    (StorageError, status.HTTP_500_INTERNAL_SERVER_ERROR, {}),
)


def _make_handler(
    status_code: int, headers: dict[str, str]
) -> Callable[[Request, Exception], Awaitable[JSONResponse]]:
    """상태 코드·헤더를 고정한 핸들러를 만든다."""

    async def handler(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(
            status_code=status_code,
            content={"detail": str(exc)},
            headers=headers or None,
        )

    return handler


async def _handle_unmapped_domain_error(request: Request, exc: Exception) -> JSONResponse:
    """매핑되지 않은 도메인 예외의 백스톱.

    새 도메인 예외를 만들고 _MAPPINGS 등록을 잊어도 응답 모양이 유지된다. 메시지는
    고정 문자열이다 — 무엇이 담길지 모르는 예외를 그대로 노출하지 않는다.

    조용히 500만 내보내면 매핑 누락을 아무도 모른 채 지나간다. 로그에는 예외 **타입**만
    적는다 — str(exc)를 로그 인자로 넣지 않는 것은 도메인 예외 메시지에 무엇이 담길지
    이 지점에서는 알 수 없기 때문이다(개인정보 금지 규칙).
    """
    _logger.exception("매핑되지 않은 도메인 예외: %s", type(exc).__name__)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={"detail": "요청을 처리하지 못했습니다"},
    )


async def _handle_request_validation(request: Request, exc: Exception) -> JSONResponse:
    """Pydantic 검증 실패(422)에서 입력값 에코를 걷어낸다.

    FastAPI 기본 핸들러는 detail의 각 항목에 `input`(요청 본문 원본)과 `ctx`를 넣는다 —
    비밀번호가 응답 본문과 액세스 로그에 그대로 남는 경로다. 어디가(loc) 왜(msg, type)
    틀렸는지는 남기고 값만 버린다.
    """
    details: list[dict[str, object]] = []
    if isinstance(exc, RequestValidationError):
        details = [
            {
                "loc": [str(part) for part in error.get("loc", ())],
                "msg": str(error.get("msg", "")),
                "type": str(error.get("type", "")),
            }
            for error in exc.errors()
        ]
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
        content={"detail": details},
    )


def register_exception_handlers(app: FastAPI) -> None:
    """도메인 예외 핸들러와 검증 오류 핸들러를 앱에 등록한다."""
    # 최상위 도메인 예외를 먼저 등록한다. Starlette는 MRO 순서로 핸들러를 찾으므로
    # 구체적인 예외에 매핑이 있으면 그쪽이 이기고, 없을 때만 이 백스톱이 걸린다.
    app.add_exception_handler(EasyDocError, _handle_unmapped_domain_error)
    for exception_type, status_code, headers in _MAPPINGS:
        app.add_exception_handler(exception_type, _make_handler(status_code, headers))
    app.add_exception_handler(RequestValidationError, _handle_request_validation)
