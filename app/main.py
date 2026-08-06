"""FastAPI 애플리케이션 진입점."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
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
