"""FakeProvider 동작 검증 — 서비스 단위 테스트의 표준 대역."""

import pytest

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
