"""FakeProvider 동작 검증 — 서비스 단위 테스트의 표준 대역."""

import pytest

from app.exceptions import LLMProviderError
from app.llm.fake import FakeProvider


async def test_준비된_응답을_순서대로_반환() -> None:
    provider = FakeProvider(responses=["쉬운 글 결과"])
    response = await provider.complete(system="시스템", user="본문")
    assert response.text == "쉬운 글 결과"
    assert provider.calls[0].user == "본문"


async def test_응답_소진_시_예외() -> None:
    provider = FakeProvider(responses=[])
    with pytest.raises(IndexError):
        await provider.complete(system="s", user="u")


async def test_Exception을_넣으면_그_차례에_던진다() -> None:
    """실패 경로 처리를 대역만으로 테스트할 수 있어야 한다."""
    provider = FakeProvider(responses=[LLMProviderError("호출 실패")])
    with pytest.raises(LLMProviderError):
        await provider.complete(system="시스템", user="본문")


async def test_호출_인자를_전부_기록한다() -> None:
    provider = FakeProvider(responses=["결과"])
    await provider.complete(system="시스템", user="본문", max_tokens=100, temperature=0.7)
    call = provider.calls[0]
    assert (call.system, call.user, call.max_tokens, call.temperature) == (
        "시스템",
        "본문",
        100,
        0.7,
    )
