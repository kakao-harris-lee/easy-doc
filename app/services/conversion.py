"""쉬운 글 변환 서비스.

처리 순서(변경 금지): 마스킹(mask_text) → 프롬프트 생성 → LLMProvider 호출 → 후처리.
마스킹 전 원문을 LLMProvider에 전달하는 코드는 절대 작성하지 않는다 (master-plan 3.2).
"""

from pydantic import BaseModel

from app.easyread.postprocess import postprocess
from app.easyread.prompts import build_system_prompt, build_user_prompt
from app.exceptions import LLMEmptyResultError, LLMTruncatedError
from app.llm.provider import LLMProvider
from app.privacy.masking import MaskedItem, mask_text


class ConversionOutcome(BaseModel):
    """변환 결과 + 검수 화면용 마스킹 항목.

    masked_items에 원문 개인정보가 담기므로 API response_model로 직접 쓰지 않는다
    (MaskingResult와 동일한 취급).

    missing_placeholders 정책: 모델이 자리표시자를 지우거나 변형해 결과에서 사라진 목록이다.
    개인정보가 새는 방향이 아니라 표시가 사라지는 방향(연락처 등 정보 누락)이므로
    예외로 막지 않고 HITL 검수 화면 경고로 넘긴다 — 원문 대조 판단은 사람이 한다.
    """

    easy_text: str
    masked_items: list[MaskedItem]
    missing_placeholders: list[str] = []
    model: str
    provider_name: str
    input_tokens: int = 0
    output_tokens: int = 0


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
            raise LLMTruncatedError("변환 결과가 토큰 한도에서 잘렸습니다")
        easy_text = postprocess(response.text)
        if not easy_text:
            # 후처리로 전부 벗겨졌거나 모델이 빈 응답을 준 경우 — 빈 결과를 성공으로 넘기지 않는다.
            raise LLMEmptyResultError("변환 결과가 비어 있습니다")
        missing = [item.placeholder for item in masking.items if item.placeholder not in easy_text]
        return ConversionOutcome(
            easy_text=easy_text,
            masked_items=masking.items,
            missing_placeholders=missing,
            model=response.model,
            provider_name=self._provider.name,
            input_tokens=response.input_tokens,
            output_tokens=response.output_tokens,
        )
