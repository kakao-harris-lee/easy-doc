"""변환 서비스 테스트 — 마스킹 선행 보안 불변식 검증이 핵심."""

import re

import pytest

from app.easyread.prompts import build_system_prompt
from app.exceptions import LLMProviderError
from app.llm.fake import FakeProvider
from app.llm.provider import (
    DEFAULT_MAX_TOKENS,
    DEFAULT_TEMPERATURE,
    LLMProvider,
    LLMResponse,
)
from app.privacy.masking import mask_text
from app.services.conversion import ConversionService

# user 프롬프트는 난수 id 때문에 등가 비교가 불가 — 구조에서 본문만 뽑아 검증한다.
_DOCUMENT_BODY_RE = re.compile(
    r'\A<문서 id="([0-9a-f]{12})">\n(?P<body>.*)\n</문서 id="\1">', re.DOTALL
)


class _ResponseProvider(LLMProvider):
    """지정한 LLMResponse를 그대로 돌려주는 최소 대역.

    FakeProvider는 문자열 응답만 받아 truncated·토큰 수를 흉내 낼 수 없다.
    공용 대역을 넓히는 대신 이 테스트 모듈 안에 국한한다.
    """

    name = "stub"

    def __init__(self, response: LLMResponse) -> None:
        self._response = response

    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        temperature: float = DEFAULT_TEMPERATURE,
    ) -> LLMResponse:
        return self._response


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


async def test_프롬프트_SSOT를_그대로_전달한다() -> None:
    """임의 문자열이 아니라 프롬프트 모듈이 만든 결과가 그대로 나가야 한다."""
    text = "금일 중으로 제출하십시오. 문의 010-1234-5678"
    provider = FakeProvider(responses=["쉬운 글입니다."])
    service = ConversionService(provider=provider)
    await service.convert(text)
    call = provider.calls[0]
    assert call.system == build_system_prompt()
    match = _DOCUMENT_BODY_RE.match(call.user)
    assert match is not None
    assert match.group("body") == mask_text(text).masked_text


async def test_절단_응답은_예외로_막는다() -> None:
    """잘린 본문을 정상 결과로 내보내면 정보 누락 사고가 된다."""
    provider = _ResponseProvider(LLMResponse(text="쉬운 글이 도중에", model="fake", truncated=True))
    service = ConversionService(provider=provider)
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


@pytest.mark.parametrize("raw", ["   ", "```\n```"])
async def test_빈_변환_결과는_예외로_막는다(raw: str) -> None:
    """후처리 후 본문이 없으면 성공으로 넘기지 않는다."""
    service = ConversionService(provider=FakeProvider(responses=[raw]))
    with pytest.raises(LLMProviderError):
        await service.convert("안내문 본문")


async def test_유실된_플레이스홀더를_보고한다() -> None:
    """모델이 자리표시자를 지우면 검수 화면 경고용으로 목록에 담는다(예외 아님)."""
    provider = FakeProvider(responses=["문의는 전화로 해 주세요."])
    service = ConversionService(provider=provider)
    outcome = await service.convert("문의 010-1234-5678")
    assert outcome.missing_placeholders == ["[[전화번호1]]"]


async def test_플레이스홀더가_보존되면_유실_목록이_비어_있다() -> None:
    provider = FakeProvider(responses=["문의는 [[전화번호1]]로 해 주세요."])
    service = ConversionService(provider=provider)
    outcome = await service.convert("문의 010-1234-5678")
    assert outcome.missing_placeholders == []


async def test_벤치마크용_메타데이터를_담는다() -> None:
    provider = _ResponseProvider(
        LLMResponse(text="쉬운 글입니다.", model="claude-test", input_tokens=120, output_tokens=45)
    )
    service = ConversionService(provider=provider)
    outcome = await service.convert("안내문 본문")
    assert outcome.provider_name == "stub"
    assert outcome.model == "claude-test"
    assert (outcome.input_tokens, outcome.output_tokens) == (120, 45)
