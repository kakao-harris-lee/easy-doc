"""FastAPI 애플리케이션 진입점."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.api.auth import router as auth_router
from app.api.documents import router as documents_router
from app.api.errors import register_exception_handlers
from app.config import Settings
from app.db import create_engine_and_factory
from app.queue import create_task_queue


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """DB 엔진·세션 팩토리와 작업 큐를 앱 수명에 맞춰 만들고 종료 시 정리한다.

    엔진 생성은 접속을 시도하지 않는다(첫 쿼리에서 연결) — DB가 떠 있지 않아도
    기동은 되고 /health로 진단할 수 있다. 큐는 연결을 시도하지만, 실패해도 기동을
    막지 않고 None으로 남긴다(큐가 필요한 요청만 503).
    """
    settings = Settings()
    engine, session_factory = create_engine_and_factory(settings.database_url)
    task_queue = await create_task_queue(settings.redis_url)
    app.state.settings = settings
    app.state.session_factory = session_factory
    app.state.task_queue = task_queue
    try:
        yield
    finally:
        if task_queue is not None:
            await task_queue.aclose()
        await engine.dispose()


app = FastAPI(title="Easy-Read AI", lifespan=lifespan)

# CORS는 lifespan이 아니라 조립 시점에 붙여야 한다 — 미들웨어 스택은 기동과 함께
# 굳어서, 뜬 뒤에는 추가할 수 없다. 그래서 설정을 여기서 한 번 더 읽는다.
_cors_settings = Settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_settings.cors_origins,
    # 인증은 Authorization Bearer 헤더로 한다 — 쿠키를 쓰지 않으므로 자격증명을
    # 허용할 이유가 없고, 켜면 CSRF 노출 면적만 넓어진다.
    allow_credentials=False,
    # 우리가 실제로 제공하는 메서드만 적는다(OPTIONS는 미들웨어가 스스로 처리하므로
    # 목록에 넣지 않는다). **메서드를 추가하면 이 목록도 함께 갱신해야 한다** —
    # 빠뜨리면 프리플라이트가 막혀 브라우저에서만 실패한다.
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
    # 안전 목록(Content-Type 등) 밖의 응답 헤더는 명시하지 않으면 브라우저 JS가 읽지
    # 못한다. 내려받기 파일명(Content-Disposition)과 접수 결과 주소(Location)가 그렇다.
    expose_headers=["Content-Disposition", "Location"],
)

register_exception_handlers(app)
app.include_router(auth_router)
app.include_router(documents_router)


class HealthResponse(BaseModel):
    """헬스 체크 응답."""

    status: str


@app.get("/health")
async def health() -> HealthResponse:
    """서비스 생존 확인."""
    return HealthResponse(status="ok")
