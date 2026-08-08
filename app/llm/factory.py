"""설정에서 LLMProvider를 만드는 팩토리.

골든셋 평가·벤치마크·변환 서비스가 provider 생성 규칙을 각자 복제하지 않도록 한 곳에 둔다.
"""

from app.config import Settings
from app.llm.anthropic_provider import AnthropicProvider
from app.llm.openai_provider import OpenAIProvider
from app.llm.provider import LLMProvider


def create_provider(name: str, settings: Settings) -> LLMProvider | None:
    """이름에 해당하는 provider를 만든다.

    API 키가 설정되지 않았으면 None을 반환한다(호출 측에서 skip 처리).
    지원하지 않는 이름이면 ValueError.

    settings.llm_model이 있으면 모델명을 덮어쓴다 — 단, llm_provider로 고른 벤더에만
    적용한다. 벤치마크·골든셋 평가는 대상 벤더를 따로 지정하므로(--providers·GOLDEN_PROVIDER),
    다른 벤더에까지 적용하면 없는 모델명으로 호출해 전건 실패한다.
    """
    model = settings.llm_model if name == settings.llm_provider else None
    if name == AnthropicProvider.name:
        anthropic_key = settings.anthropic_api_key
        if anthropic_key is None:
            return None
        if model is None:
            return AnthropicProvider(api_key=anthropic_key.get_secret_value())
        return AnthropicProvider(api_key=anthropic_key.get_secret_value(), model=model)
    if name == OpenAIProvider.name:
        openai_key = settings.openai_api_key
        if openai_key is None:
            return None
        if model is None:
            return OpenAIProvider(api_key=openai_key.get_secret_value())
        return OpenAIProvider(api_key=openai_key.get_secret_value(), model=model)
    raise ValueError(f"지원하지 않는 provider 이름: {name}")
