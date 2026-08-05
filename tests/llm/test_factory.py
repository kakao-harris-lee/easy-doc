"""provider 팩토리 단위 테스트. 실제 API 호출은 없다(클라이언트 생성까지만)."""

import pytest
from pydantic import SecretStr

from app.config import Settings
from app.llm.anthropic_provider import AnthropicProvider
from app.llm.factory import create_provider
from app.llm.openai_provider import OpenAIProvider


def test_키가_있으면_해당_provider를_만든다() -> None:
    settings = Settings(
        anthropic_api_key=SecretStr("앤트로픽키"), openai_api_key=SecretStr("오픈에이아이키")
    )
    assert isinstance(create_provider("anthropic", settings), AnthropicProvider)
    assert isinstance(create_provider("openai", settings), OpenAIProvider)


def test_키가_없으면_None() -> None:
    settings = Settings(anthropic_api_key=None, openai_api_key=None)
    assert create_provider("anthropic", settings) is None
    assert create_provider("openai", settings) is None


def test_지원하지_않는_이름이면_ValueError() -> None:
    settings = Settings(anthropic_api_key=SecretStr("앤트로픽키"), openai_api_key=None)
    with pytest.raises(ValueError, match="지원하지 않는 provider 이름"):
        create_provider("gemini", settings)
