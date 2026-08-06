"""워커 기동 설정 테스트 — 어떤 LLM 벤더를 쓸지가 Settings를 따르는지 본다.

외부 호출은 없다: provider는 만들기만 하고(키는 가짜 문자열), DB 엔진은 첫 쿼리
전까지 접속하지 않는다. `.env` 유무와 무관하게 돌도록 필요한 값은 환경변수로 덮어쓴다.
"""

from typing import Any

import pytest
from cryptography.fernet import Fernet

from app.config import Settings
from app.workers.settings import shutdown, startup


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
