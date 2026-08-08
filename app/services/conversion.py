"""쉬운 글 변환 서비스.

처리 순서(변경 금지): 마스킹(mask_text) → 프롬프트 생성 → LLMProvider 호출 → 후처리.
마스킹 전 원문을 LLMProvider에 전달하는 코드는 절대 작성하지 않는다 (master-plan 3.2).

**호출 수 계약**: 변환 1건당 LLMProvider 호출은 최대 2회다. 1차 변환 결과가 규칙
검사(check_style)를 통과하면 1회로 끝나고, 위반이 있을 때만 표적 보정을 1회 더 한다.
보정은 다시 검사해 또 부르는 루프가 아니다 — 실서비스 비용·지연의 상한이자, 프롬프트
문구로는 잡히지 않는 잔여 위반(어려운 낱말 잔존·쉼표 초과)에 대한 유일한 기계적 처방이다.
"""

from pydantic import BaseModel

from app.easyread.postprocess import postprocess
from app.easyread.prompts import build_repair_prompt, build_system_prompt, build_user_prompt
from app.easyread.style_rules import SentenceIssue, check_style
from app.exceptions import LLMEmptyResultError, LLMProviderError, LLMTruncatedError
from app.llm.provider import LLMProvider, LLMResponse
from app.privacy.masking import MaskedItem, mask_text

#: 변환 1건이 쓸 수 있는 LLM 호출 수 상한 (1차 변환 + 보정 1회).
MAX_LLM_CALLS_PER_CONVERSION = 2


class ConversionOutcome(BaseModel):
    """변환 결과 + 검수 화면용 마스킹 항목.

    masked_items에 원문 개인정보가 담기므로 API response_model로 직접 쓰지 않는다
    (MaskingResult와 동일한 취급).

    missing_placeholders 정책: 모델이 자리표시자를 지우거나 변형해 결과에서 사라진 목록이다.
    개인정보가 새는 방향이 아니라 표시가 사라지는 방향(연락처 등 정보 누락)이므로
    예외로 막지 않고 HITL 검수 화면 경고로 넘긴다 — 원문 대조 판단은 사람이 한다.

    repaired: 보정 패스 결과를 채택했는지. easy_text가 어느 호출에서 나왔는지를 가리키므로
    보정을 부르고도 원본을 채택했으면 False다. 토큰 수는 그 경우에도 두 호출의 합이다
    (비용은 이미 치렀다).
    """

    easy_text: str
    masked_items: list[MaskedItem]
    missing_placeholders: list[str] = []
    model: str
    provider_name: str
    input_tokens: int = 0
    output_tokens: int = 0
    repaired: bool = False


def _accepts_repair(
    *, original: str, candidate: str, issues: list[SentenceIssue], placeholders: list[str]
) -> bool:
    """보정 결과를 채택할지 판정한다 (악화 방지 가드).

    모델은 지적을 고치면서 멀쩡하던 다른 문장을 망가뜨리기도 한다. 보정은 품질을
    높이려는 시도일 뿐이므로, 나빠졌다면 1차 결과를 그대로 쓰는 쪽이 항상 낫다.

    두 가지를 본다.

    1. 원본에 남아 있던 자리표시자가 보정에서 사라졌으면 버린다 — 원문 복원이 깨진다.
    2. 규칙 위반 건수가 원본보다 늘었으면 버린다. 같은 건수는 채택한다(고친 자리와
       새로 생긴 자리가 맞바꿈된 경우인데, 지적받은 쪽을 고친 결과를 남긴다).
    """
    lost = [
        placeholder
        for placeholder in placeholders
        if placeholder in original and placeholder not in candidate
    ]
    if lost:
        return False
    return len(check_style(candidate).issues) <= len(issues)


class ConversionService:
    """쉬운 글 변환 파이프라인 오케스트레이션."""

    def __init__(self, provider: LLMProvider) -> None:
        self._provider = provider

    async def convert(self, text: str) -> ConversionOutcome:
        """원문을 마스킹한 뒤 쉬운 글로 변환한다.

        호출 수 계약은 모듈 docstring 참고: 규칙 검사를 통과하면 1회, 위반이 있으면
        보정까지 2회다. 보정 호출이 실패하거나 결과가 더 나쁘면 1차 결과를 채택한다.
        """
        masking = mask_text(text)
        easy_text, response = await self._complete(
            # 시스템 프롬프트의 치환 목록도 마스킹된 원문에서 뽑는다 — 이 문서에 실제
            # 등장하는 낱말만 실어 입력 토큰을 줄인다(마스킹 선행 순서는 그대로).
            system=build_system_prompt(masking.masked_text),
            user=build_user_prompt(masking.masked_text),
        )
        input_tokens = response.input_tokens
        output_tokens = response.output_tokens

        placeholders = [item.placeholder for item in masking.items]
        style = check_style(easy_text)
        repaired = False
        if not style.passed:
            attempt = await self._repair(easy_text, style.issues)
            if attempt is not None:
                candidate, repair_response = attempt
                # 채택 여부와 무관하게 합산한다 — 호출한 순간 비용은 발생했다.
                input_tokens += repair_response.input_tokens
                output_tokens += repair_response.output_tokens
                if _accepts_repair(
                    original=easy_text,
                    candidate=candidate,
                    issues=style.issues,
                    placeholders=placeholders,
                ):
                    easy_text = candidate
                    repaired = True

        missing = [placeholder for placeholder in placeholders if placeholder not in easy_text]
        return ConversionOutcome(
            easy_text=easy_text,
            masked_items=masking.items,
            missing_placeholders=missing,
            model=response.model,
            provider_name=self._provider.name,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            repaired=repaired,
        )

    async def _complete(self, *, system: str, user: str) -> tuple[str, LLMResponse]:
        """호출 → 절단·빈 결과 검사 → 후처리까지의 공통 경로.

        절단 감지는 provider가 사실(truncated)만 보고, 정책(예외/재시도)은 서비스가
        결정한다. 예외 메시지에 본문을 담지 않는다 (로그 유출 차단).
        """
        response = await self._provider.complete(system=system, user=user)
        if response.truncated:
            raise LLMTruncatedError("변환 결과가 토큰 한도에서 잘렸습니다")
        text = postprocess(response.text)
        if not text:
            # 후처리로 전부 벗겨졌거나 모델이 빈 응답을 준 경우 — 빈 결과를 성공으로 넘기지 않는다.
            raise LLMEmptyResultError("변환 결과가 비어 있습니다")
        return text, response

    async def _repair(
        self, converted: str, issues: list[SentenceIssue]
    ) -> tuple[str, LLMResponse] | None:
        """기계 검출된 위반만 표적 보정한다. 호출은 1회뿐이며 실패하면 None.

        검사는 1차 호출과 같은 경로(_complete)를 써서 절단·빈 결과 판정을 공유하되,
        여기서는 예외를 삼킨다. 보정은 이미 쓸 만한 결과를 더 낫게 만들려는 시도일
        뿐이라, 보정 실패가 변환 전체를 실패시키면 사용자는 받을 수 있었던 결과마저
        잃는다. 같은 이유로 provider 오류도 여기서 멈춘다(원본 채택).
        """
        system, user = build_repair_prompt(converted, issues)
        try:
            return await self._complete(system=system, user=user)
        except LLMProviderError:
            return None
