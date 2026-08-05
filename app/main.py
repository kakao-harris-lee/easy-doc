"""FastAPI 애플리케이션 진입점."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel

from app.api.auth import router as auth_router
from app.api.errors import register_exception_handlers
from app.config import Settings
from app.db import create_engine_and_factory


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """DB 엔진·세션 팩토리를 앱 수명에 맞춰 만들고 종료 시 정리한다.

    엔진 생성은 접속을 시도하지 않는다(첫 쿼리에서 연결) — DB가 떠 있지 않아도
    기동은 되고 /health로 진단할 수 있다.
    """
    settings = Settings()
    engine, session_factory = create_engine_and_factory(settings.database_url)
    app.state.settings = settings
    app.state.session_factory = session_factory
    try:
        yield
    finally:
        await engine.dispose()


app = FastAPI(title="Easy-Read AI", lifespan=lifespan)
register_exception_handlers(app)
app.include_router(auth_router)


class HealthResponse(BaseModel):
    """헬스 체크 응답."""

    status: str


@app.get("/health")
async def health() -> HealthResponse:
    """서비스 생존 확인."""
    return HealthResponse(status="ok")
