"""OpenAI provider 구현체.

상용 API 입력 데이터 학습 미사용(no-training) 조건 전제. 계약·약관 변경 시 재확인 필수.

벤더 SDK import은 provider 구현체 파일에서만 허용된다 (CLAUDE.md 아키텍처 규칙 1).
"""

from openai import APIStatusError, AsyncOpenAI, OpenAIError

from app.exceptions import LLMProviderError
from app.llm.provider import (
    DEFAULT_MAX_RETRIES,
    DEFAULT_MAX_TOKENS,
    DEFAULT_TEMPERATURE,
    DEFAULT_TIMEOUT_SECONDS,
    LLMProvider,
    LLMResponse,
)


class OpenAIProvider(LLMProvider):
    """OpenAI Chat Completions API 구현체."""

    name = "openai"

    def __init__(
        self,
        api_key: str,
        model: str = "gpt-4o",
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> None:
        self.model = model
        # 타임아웃·재시도는 SDK 기본값에 맡기지 않고 명시한다 (provider.py 상수 참조).
        self._client = AsyncOpenAI(
            api_key=api_key, timeout=timeout_seconds, max_retries=DEFAULT_MAX_RETRIES
        )

    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        temperature: float = DEFAULT_TEMPERATURE,
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
        # 원인 체인(from exc)은 유지한다. APM 도입 시 전송 범위를 다시 점검할 것.
        except APIStatusError as exc:
            raise LLMProviderError(f"openai 호출 실패 (HTTP {exc.status_code})") from exc
        except OpenAIError as exc:
            raise LLMProviderError(f"openai 호출 실패 ({type(exc).__name__})") from exc

        if not completion.choices:
            raise LLMProviderError("openai 호출 실패 (빈 choices)")
        choice = completion.choices[0]
        text = choice.message.content
        if not text:
            # 호출 측이 빈 문자열을 정상 변환 결과로 오인하지 않도록 실패로 처리한다.
            raise LLMProviderError("openai 호출 실패 (빈 응답)")

        usage = completion.usage
        return LLMResponse(
            text=text,
            model=completion.model,
            input_tokens=usage.prompt_tokens if usage is not None else 0,
            output_tokens=usage.completion_tokens if usage is not None else 0,
            truncated=choice.finish_reason == "length",
        )

    async def aclose(self) -> None:
        """SDK HTTP 클라이언트를 닫는다."""
        await self._client.close()
