"""Anthropic Claude provider 구현체.

상용 API 입력 데이터 학습 미사용(no-training) 조건 전제. 계약·약관 변경 시 재확인 필수.

벤더 SDK import은 provider 구현체 파일에서만 허용된다 (CLAUDE.md 아키텍처 규칙 1).
"""

from anthropic import AnthropicError, APIStatusError, AsyncAnthropic

from app.exceptions import LLMProviderError
from app.llm.provider import LLMProvider, LLMResponse


class AnthropicProvider(LLMProvider):
    """Anthropic Messages API 구현체."""

    name = "anthropic"

    def __init__(self, api_key: str, model: str = "claude-sonnet-5") -> None:
        self.model = model
        self._client = AsyncAnthropic(api_key=api_key)

    async def complete(
        self, *, system: str, user: str, max_tokens: int = 4096, temperature: float = 0.2
    ) -> LLMResponse:
        """단일 완성 요청. 실패 시 LLMProviderError를 던진다.

        temperature는 의도적으로 전송하지 않는다: 현재 Claude 모델(claude-sonnet-5 등)은
        샘플링 파라미터(temperature/top_p/top_k)를 지원하지 않아 기본값 외 값을 보내면
        400을 반환한다. 출력 성향은 프롬프트로 제어한다. 인자는 벤더 공통 인터페이스라
        시그니처에 유지한다.
        """
        try:
            message = await self._client.messages.create(
                model=self.model,
                max_tokens=max_tokens,
                system=system,
                messages=[{"role": "user", "content": user}],
            )
        # 예외 메시지에는 벤더명·상태코드/오류 유형까지만 남긴다 — 문서 본문 유출 금지.
        except APIStatusError as exc:
            raise LLMProviderError(f"anthropic 호출 실패 (HTTP {exc.status_code})") from exc
        except AnthropicError as exc:
            raise LLMProviderError(f"anthropic 호출 실패 ({type(exc).__name__})") from exc

        return LLMResponse(
            text="".join(block.text for block in message.content if block.type == "text"),
            model=message.model,
            input_tokens=message.usage.input_tokens,
            output_tokens=message.usage.output_tokens,
        )
