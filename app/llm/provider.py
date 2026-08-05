"""LLM Provider 추상화 — 모든 LLM 호출의 유일한 관문 (master-plan 3.1).

서비스 코드는 벤더 SDK를 직접 import하지 않고 이 인터페이스만 사용한다.
모든 구현체는 입력 데이터 학습 미사용(no-training) 계약을 전제로 한다.
"""

from abc import ABC, abstractmethod

from pydantic import BaseModel


class LLMResponse(BaseModel):
    """LLM 완성 응답."""

    text: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0


class LLMProvider(ABC):
    """LLM 벤더 공통 인터페이스."""

    name: str = "base"

    @abstractmethod
    async def complete(
        self, *, system: str, user: str, max_tokens: int = 4096, temperature: float = 0.2
    ) -> LLMResponse:
        """단일 완성 요청. 실패 시 LLMProviderError를 던진다."""
