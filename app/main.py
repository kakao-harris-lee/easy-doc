"""FastAPI 애플리케이션 진입점."""

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="Easy-Read AI")


class HealthResponse(BaseModel):
    """헬스 체크 응답."""

    status: str


@app.get("/health")
async def health() -> HealthResponse:
    """서비스 생존 확인."""
    return HealthResponse(status="ok")
