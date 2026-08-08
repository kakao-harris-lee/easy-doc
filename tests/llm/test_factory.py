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


def test_llm_model이_없으면_provider_기본_모델을_쓴다() -> None:
    """기본 모델은 코드(provider 구현체)에 남고, 근거는 docs/quality 모델 비교 보고서다."""
    settings = Settings(
        llm_provider="openai", llm_model=None, openai_api_key=SecretStr("오픈에이아이키")
    )
    provider = create_provider("openai", settings)
    assert isinstance(provider, OpenAIProvider)
    assert provider.model == "gpt-4.1"


def test_llm_model은_고른_벤더의_모델만_덮어쓴다() -> None:
    """모델 롤백을 .env로 할 수 있어야 하되, 다른 벤더에 남의 모델명이 새지 않아야 한다.

    골든셋 평가·벤치마크는 GOLDEN_PROVIDER·--providers로 벤더를 따로 지정하므로,
    llm_provider가 아닌 벤더까지 덮어쓰면 없는 모델명으로 호출해 전건 실패한다.
    """
    settings = Settings(
        llm_provider="openai",
        llm_model="gpt-4o",
        anthropic_api_key=SecretStr("앤트로픽키"),
        openai_api_key=SecretStr("오픈에이아이키"),
    )
    openai = create_provider("openai", settings)
    anthropic = create_provider("anthropic", settings)
    assert isinstance(openai, OpenAIProvider)
    assert isinstance(anthropic, AnthropicProvider)
    assert openai.model == "gpt-4o"
    assert anthropic.model == "claude-sonnet-5"


def test_llm_effort가_없으면_provider가_effort를_안_보낸다() -> None:
    settings = Settings(llm_effort=None, anthropic_api_key=SecretStr("앤트로픽키"))
    provider = create_provider("anthropic", settings)
    assert isinstance(provider, AnthropicProvider)
    assert provider.effort is None


def test_llm_effort는_anthropic에만_전달된다() -> None:
    """effort는 Anthropic 전용 파라미터다 — OpenAI 생성 경로에 새면 안 된다.

    OpenAI의 reasoning_effort는 이름만 비슷한 별개 파라미터이며, provider가 그 인자를
    받지도 않는다. 여기서는 anthropic만 값을 받는다는 계약을 고정한다.
    """
    settings = Settings(
        llm_effort="low",
        anthropic_api_key=SecretStr("앤트로픽키"),
        openai_api_key=SecretStr("오픈에이아이키"),
    )
    anthropic = create_provider("anthropic", settings)
    openai = create_provider("openai", settings)
    assert isinstance(anthropic, AnthropicProvider)
    assert isinstance(openai, OpenAIProvider)
    assert anthropic.effort == "low"
    assert not hasattr(openai, "effort")


def test_llm_effort가_잘못되면_생성에서_ValueError() -> None:
    """.env 오타를 앱·워커 기동 시점에 드러낸다 — 첫 변환 호출까지 미루지 않는다."""
    settings = Settings(llm_effort="빠름", anthropic_api_key=SecretStr("앤트로픽키"))
    with pytest.raises(ValueError, match="지원하지 않는 effort 값"):
        create_provider("anthropic", settings)
