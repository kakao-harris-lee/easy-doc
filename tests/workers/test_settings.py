"""워커 기동 설정 테스트 — 어떤 LLM 벤더를 쓸지가 Settings를 따르는지, 그리고 정기
작업(보존 만료 파기)이 실제로 등록되는지 본다.

외부 호출은 없다: provider는 만들기만 하고(키는 가짜 문자열), DB 엔진은 첫 쿼리
전까지 접속하지 않는다. `.env` 유무와 무관하게 돌도록 필요한 값은 환경변수로 덮어쓴다.
"""

from datetime import UTC, datetime
from typing import Any

import pytest
from cryptography.fernet import Fernet

from app.config import Settings
from app.queue import PURGE_EXPIRED_TASK
from app.workers.settings import KST, RETENTION_CRON_HOUR, WorkerSettings, shutdown, startup
from app.workers.tasks import purge_expired_documents


@pytest.fixture(autouse=True)
def _암호화_키(monkeypatch: pytest.MonkeyPatch) -> None:
    """startup이 없으면 기동을 멈추는 값 — 테스트마다 새 키로 채운다."""
    monkeypatch.setenv("FERNET_KEY", Fernet.generate_key().decode())


def test_기본_벤더는_anthropic이다() -> None:
    """기본값은 선택이 아니라 '벤더 미확정' 상태다 (master-plan 3.1 기록 표).

    선언 자체를 본다 — `.env`가 LLM_PROVIDER를 덮어쓴 환경에서도 판정이 흔들리지 않아야 한다.
    """
    assert Settings.model_fields["llm_provider"].default == "anthropic"


async def test_설정한_벤더로_provider를_만든다(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "openai")
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")
    ctx: dict[str, Any] = {}

    await startup(ctx)
    try:
        assert ctx["provider"] is not None
        assert ctx["provider"].name == "openai"
    finally:
        await shutdown(ctx)


async def test_벤더를_바꾸면_만들어지는_provider도_바뀐다(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "anthropic")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    ctx: dict[str, Any] = {}

    await startup(ctx)
    try:
        assert ctx["provider"] is not None
        assert ctx["provider"].name == "anthropic"
    finally:
        await shutdown(ctx)


async def test_기동하면_파기_잡이_쓸_저장소_스코프도_준비된다() -> None:
    """cron 잡은 변환과 다른 계약(RetentionStore)을 쓴다 — 배선이 빠지면 첫 실행에서
    KeyError로 죽고, 그때는 이미 파기 하루를 건너뛴 뒤다.
    """
    ctx: dict[str, Any] = {}

    await startup(ctx)
    try:
        assert "retention_scope" in ctx
        # 변환 스코프와 다른 객체다 — 파기 잡이 변환 저장소 권한을 갖지 않는다.
        assert ctx["retention_scope"] is not ctx["store_scope"]
    finally:
        await shutdown(ctx)


# --- 정기 작업(cron) 등록 --------------------------------------------------------


def test_보존_만료_파기_잡이_cron으로_등록된다() -> None:
    """master-plan 3.2 "기본 보존 30일 후 자동 삭제"를 이행하는 유일한 자동 경로다 —
    등록이 빠지면 코드는 멀쩡한데 아무것도 지워지지 않는다.
    """
    jobs = {job.name: job for job in WorkerSettings.cron_jobs}

    assert PURGE_EXPIRED_TASK in jobs
    assert jobs[PURGE_EXPIRED_TASK].coroutine is purge_expired_documents


def test_파기_잡은_매일_한_번_정해진_시각에_돈다() -> None:
    """월·일·요일을 묶지 않아야 '매일'이고, 시·분을 묶어야 '하루 한 번'이다."""
    job = next(job for job in WorkerSettings.cron_jobs if job.name == PURGE_EXPIRED_TASK)

    assert (job.hour, job.minute) == (RETENTION_CRON_HOUR, 0)
    assert (job.month, job.day, job.weekday) == (None, None, None)
    # 워커 여러 대를 띄워도 그 시각에 한 번만 돈다.
    assert job.unique is True


def test_cron_시각은_한국_시간을_기준으로_한다() -> None:
    """컨테이너 TZ는 UTC다 — 시간대를 명시하지 않으면 새벽 4시가 낮 1시가 된다."""
    assert WorkerSettings.timezone == KST
    # 잡이 도는 시각을 UTC로 옮겨 확인한다: 04:00 KST = 전날 19:00 UTC.
    scheduled = datetime(2026, 8, 8, RETENTION_CRON_HOUR, tzinfo=KST)
    assert scheduled.astimezone(UTC) == datetime(2026, 8, 7, 19, tzinfo=UTC)
