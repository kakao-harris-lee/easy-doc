"""변환 서비스 테스트 — 마스킹 선행 보안 불변식 검증이 핵심."""

import pytest

from app.exceptions import LLMProviderError
from app.llm.fake import FakeProvider
from app.llm.provider import (
    DEFAULT_MAX_TOKENS,
    DEFAULT_TEMPERATURE,
    LLMProvider,
    LLMResponse,
)
from app.services.conversion import ConversionService


class _TruncatedProvider(LLMProvider):
    """절단(truncated=True) 응답만 재현하는 최소 대역.

    FakeProvider는 문자열 응답만 받아 truncated를 흉내 낼 수 없다.
    절단 정책 테스트를 위해 공용 대역을 넓히는 대신 이 테스트 안에 국한한다.
    """

    name = "truncated-fake"

    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        temperature: float = DEFAULT_TEMPERATURE,
    ) -> LLMResponse:
        return LLMResponse(text="쉬운 글이 도중에", model="fake", truncated=True)


async def test_마스킹_후에만_LLM에_전달된다() -> None:
    provider = FakeProvider(responses=["변환 결과"])
    service = ConversionService(provider=provider)
    await service.convert("홍길동 900101-1234567, 연락처 010-1234-5678")
    sent = provider.calls[0].user
    assert "900101-1234567" not in sent
    assert "010-1234-5678" not in sent
    assert "[[주민등록번호1]]" in sent


async def test_변환_결과와_마스킹_항목_반환() -> None:
    provider = FakeProvider(responses=["쉬운 글입니다."])
    service = ConversionService(provider=provider)
    outcome = await service.convert("문의 010-1234-5678")
    assert outcome.easy_text == "쉬운 글입니다."
    assert len(outcome.masked_items) == 1


async def test_스타일_규칙_시스템_프롬프트로_호출한다() -> None:
    """프롬프트 SSOT를 거치지 않고 임의 문자열을 보내지 않는지 확인한다."""
    provider = FakeProvider(responses=["쉬운 글입니다."])
    service = ConversionService(provider=provider)
    outcome = await service.convert("금일 중으로 제출하십시오.")
    assert "정보소외계층" in provider.calls[0].system
    assert outcome.model == "fake"


async def test_절단_응답은_예외로_막는다() -> None:
    """잘린 본문을 정상 결과로 내보내면 정보 누락 사고가 된다."""
    service = ConversionService(provider=_TruncatedProvider())
    with pytest.raises(LLMProviderError):
        await service.convert("안내문 본문")


async def test_provider_예외는_그대로_전파된다() -> None:
    provider = FakeProvider(responses=[LLMProviderError("호출 실패")])
    service = ConversionService(provider=provider)
    with pytest.raises(LLMProviderError):
        await service.convert("안내문 본문")


async def test_응답에_붙은_코드_펜스는_후처리로_제거된다() -> None:
    provider = FakeProvider(responses=["```text\n오늘 서류를 내세요.\n```"])
    service = ConversionService(provider=provider)
    outcome = await service.convert("금일 중으로 서류를 제출하십시오.")
    assert outcome.easy_text == "오늘 서류를 내세요."
