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
    """
    if name == AnthropicProvider.name:
        anthropic_key = settings.anthropic_api_key
        if anthropic_key is None:
            return None
        return AnthropicProvider(api_key=anthropic_key.get_secret_value())
    if name == OpenAIProvider.name:
        openai_key = settings.openai_api_key
        if openai_key is None:
            return None
        return OpenAIProvider(api_key=openai_key.get_secret_value())
    raise ValueError(f"지원하지 않는 provider 이름: {name}")
