"""쉬운 글 변환 서비스.

처리 순서(변경 금지): 마스킹(mask_text) → 프롬프트 생성 → LLMProvider 호출 → 후처리.
마스킹 전 원문을 LLMProvider에 전달하는 코드는 절대 작성하지 않는다 (master-plan 3.2).
"""

from pydantic import BaseModel

from app.easyread.postprocess import postprocess
from app.easyread.prompts import build_system_prompt, build_user_prompt
from app.exceptions import LLMProviderError
from app.llm.provider import LLMProvider
from app.privacy.masking import MaskedItem, mask_text


class ConversionOutcome(BaseModel):
    """변환 결과 + 검수 화면용 마스킹 항목.

    masked_items에 원문 개인정보가 담기므로 API response_model로 직접 쓰지 않는다
    (MaskingResult와 동일한 취급).
    """

    easy_text: str
    masked_items: list[MaskedItem]
    model: str


class ConversionService:
    """쉬운 글 변환 파이프라인 오케스트레이션."""

    def __init__(self, provider: LLMProvider) -> None:
        self._provider = provider

    async def convert(self, text: str) -> ConversionOutcome:
        """원문을 마스킹한 뒤 쉬운 글로 변환한다."""
        masking = mask_text(text)
        response = await self._provider.complete(
            system=build_system_prompt(),
            user=build_user_prompt(masking.masked_text),
        )
        if response.truncated:
            # 절단 감지는 provider가 사실(truncated)만 보고, 정책(예외/재시도)은 서비스가 결정한다.
            # 예외 메시지에 본문을 담지 않는다 (로그 유출 차단).
            raise LLMProviderError("변환 결과가 토큰 한도에서 잘렸습니다")
        return ConversionOutcome(
            easy_text=postprocess(response.text),
            masked_items=masking.items,
            model=response.model,
        )
