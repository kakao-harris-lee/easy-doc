"""OpenAI provider 구현체.

상용 API 입력 데이터 학습 미사용(no-training) 조건 전제. 계약·약관 변경 시 재확인 필수.

벤더 SDK import은 provider 구현체 파일에서만 허용된다 (CLAUDE.md 아키텍처 규칙 1).
"""

from openai import APIStatusError, AsyncOpenAI, OpenAIError

from app.exceptions import LLMProviderError
from app.llm.provider import LLMProvider, LLMResponse


class OpenAIProvider(LLMProvider):
    """OpenAI Chat Completions API 구현체."""

    name = "openai"

    def __init__(self, api_key: str, model: str = "gpt-4o") -> None:
        self.model = model
        self._client = AsyncOpenAI(api_key=api_key)

    async def complete(
        self, *, system: str, user: str, max_tokens: int = 4096, temperature: float = 0.2
    ) -> LLMResponse:
        """단일 완성 요청. 실패 시 LLMProviderError를 던진다.

        출력 상한은 max_tokens가 아니라 max_completion_tokens로 보낸다 — 설치된 SDK에서
        max_tokens는 deprecated이고 신형 모델에서는 지원되지 않는다.
        """
        try:
            completion = await self._client.chat.completions.create(
                model=self.model,
                max_completion_tokens=max_tokens,
                temperature=temperature,
                messages=[
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
            )
        # 예외 메시지에는 벤더명·상태코드/오류 유형까지만 남긴다 — 문서 본문 유출 금지.
        except APIStatusError as exc:
            raise LLMProviderError(f"openai 호출 실패 (HTTP {exc.status_code})") from exc
        except OpenAIError as exc:
            raise LLMProviderError(f"openai 호출 실패 ({type(exc).__name__})") from exc

        usage = completion.usage
        return LLMResponse(
            text=completion.choices[0].message.content or "",
            model=completion.model,
            input_tokens=usage.prompt_tokens if usage is not None else 0,
            output_tokens=usage.completion_tokens if usage is not None else 0,
        )
