"""Anthropic·OpenAI provider 구현체 단위 테스트.

기본 실행에서는 실제 API를 호출하지 않는다. SDK 클라이언트를 가짜로 갈아끼워
① system/user 페이로드 위치 ② 응답 → LLMResponse 변환 ③ SDK 예외 → LLMProviderError
변환(본문 미유출)을 검증한다.

SDK 예외 타입만 직접 import한다 — 아키텍처 규칙 1은 서비스 코드를 대상으로 하고,
이 파일은 벤더 경계(provider 구현체) 자체를 검증하는 테스트다. 서비스·라우터 계층은
여전히 LLMProvider 인터페이스만 사용한다.
"""

from types import SimpleNamespace

import httpx
import pytest
from anthropic import AnthropicError
from anthropic import APIStatusError as AnthropicAPIStatusError
from openai import APIStatusError as OpenAIAPIStatusError
from openai import OpenAIError

from app.config import Settings
from app.exceptions import LLMProviderError
from app.llm import anthropic_provider as anthropic_module
from app.llm import openai_provider as openai_module
from app.llm.anthropic_provider import AnthropicProvider
from app.llm.openai_provider import OpenAIProvider

_시스템 = "너는 쉬운 글 변환기다."
_본문 = "신청자는 관계 법령에 의거하여 서류를 제출하여야 한다."


class _가짜엔드포인트:
    """SDK 클라이언트의 create()를 대신해 호출 인자를 기록하는 대역."""

    def __init__(self, *, result: object = None, error: Exception | None = None) -> None:
        self._result = result
        self._error = error
        self.kwargs: dict[str, object] = {}

    async def create(self, **kwargs: object) -> object:
        self.kwargs = kwargs
        if self._error is not None:
            raise self._error
        return self._result


def _앤트로픽_응답() -> SimpleNamespace:
    """텍스트 블록과 비텍스트 블록이 섞인 실제 응답 형태."""
    return SimpleNamespace(
        model="claude-sonnet-5-테스트판",
        content=[
            SimpleNamespace(type="thinking", thinking="내부 추론"),
            SimpleNamespace(type="text", text="신청자는 서류를 내야 합니다."),
        ],
        usage=SimpleNamespace(input_tokens=120, output_tokens=45),
    )


def _오픈에이아이_응답() -> SimpleNamespace:
    return SimpleNamespace(
        model="gpt-4o-테스트판",
        choices=[SimpleNamespace(message=SimpleNamespace(content="신청자는 서류를 내야 합니다."))],
        usage=SimpleNamespace(prompt_tokens=120, completion_tokens=45),
    )


def _앤트로픽_대역(
    monkeypatch: pytest.MonkeyPatch, 엔드포인트: _가짜엔드포인트
) -> AnthropicProvider:
    클라이언트 = SimpleNamespace(messages=엔드포인트)
    monkeypatch.setattr(anthropic_module, "AsyncAnthropic", lambda **_: 클라이언트)
    return AnthropicProvider(api_key="테스트키")


def _오픈에이아이_대역(
    monkeypatch: pytest.MonkeyPatch, 엔드포인트: _가짜엔드포인트
) -> OpenAIProvider:
    클라이언트 = SimpleNamespace(chat=SimpleNamespace(completions=엔드포인트))
    monkeypatch.setattr(openai_module, "AsyncOpenAI", lambda **_: 클라이언트)
    return OpenAIProvider(api_key="테스트키")


def test_생성자_기본값과_name() -> None:
    앤트로픽 = AnthropicProvider(api_key="테스트키")
    오픈에이아이 = OpenAIProvider(api_key="테스트키")
    assert (앤트로픽.name, 앤트로픽.model) == ("anthropic", "claude-sonnet-5")
    assert (오픈에이아이.name, 오픈에이아이.model) == ("openai", "gpt-4o")


async def test_anthropic_system은_전용_필드로_user는_메시지로_전달된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    엔드포인트 = _가짜엔드포인트(result=_앤트로픽_응답())
    provider = _앤트로픽_대역(monkeypatch, 엔드포인트)

    await provider.complete(system=_시스템, user=_본문, max_tokens=1024)

    assert 엔드포인트.kwargs["system"] == _시스템
    assert 엔드포인트.kwargs["messages"] == [{"role": "user", "content": _본문}]
    assert 엔드포인트.kwargs["max_tokens"] == 1024
    assert 엔드포인트.kwargs["model"] == "claude-sonnet-5"


async def test_anthropic_응답이_LLMResponse로_변환된다(monkeypatch: pytest.MonkeyPatch) -> None:
    """텍스트 블록만 합치고, 모델·토큰 수는 응답에서 읽는다."""
    엔드포인트 = _가짜엔드포인트(result=_앤트로픽_응답())
    provider = _앤트로픽_대역(monkeypatch, 엔드포인트)

    response = await provider.complete(system=_시스템, user=_본문)

    assert response.text == "신청자는 서류를 내야 합니다."
    assert response.model == "claude-sonnet-5-테스트판"
    assert (response.input_tokens, response.output_tokens) == (120, 45)


async def test_anthropic_상태코드_예외는_본문_없이_LLMProviderError로_변환된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    요청 = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    오류 = AnthropicAPIStatusError(
        f"rate limited: {_본문}", response=httpx.Response(429, request=요청), body=None
    )
    provider = _앤트로픽_대역(monkeypatch, _가짜엔드포인트(error=오류))

    with pytest.raises(LLMProviderError) as excinfo:
        await provider.complete(system=_시스템, user=_본문)

    메시지 = str(excinfo.value)
    assert "anthropic" in 메시지
    assert "429" in 메시지
    assert _본문 not in 메시지  # 문서 본문은 예외 메시지에 남기지 않는다


async def test_anthropic_비상태_예외도_LLMProviderError로_변환된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    오류 = AnthropicError(_본문)
    provider = _앤트로픽_대역(monkeypatch, _가짜엔드포인트(error=오류))

    with pytest.raises(LLMProviderError) as excinfo:
        await provider.complete(system=_시스템, user=_본문)

    메시지 = str(excinfo.value)
    assert "anthropic" in 메시지
    assert "AnthropicError" in 메시지
    assert _본문 not in 메시지


async def test_openai_system과_user가_역할별_메시지로_전달된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    엔드포인트 = _가짜엔드포인트(result=_오픈에이아이_응답())
    provider = _오픈에이아이_대역(monkeypatch, 엔드포인트)

    await provider.complete(system=_시스템, user=_본문, max_tokens=1024, temperature=0.5)

    assert 엔드포인트.kwargs["messages"] == [
        {"role": "system", "content": _시스템},
        {"role": "user", "content": _본문},
    ]
    assert 엔드포인트.kwargs["max_completion_tokens"] == 1024
    assert 엔드포인트.kwargs["temperature"] == 0.5
    assert 엔드포인트.kwargs["model"] == "gpt-4o"


async def test_openai_응답이_LLMResponse로_변환된다(monkeypatch: pytest.MonkeyPatch) -> None:
    엔드포인트 = _가짜엔드포인트(result=_오픈에이아이_응답())
    provider = _오픈에이아이_대역(monkeypatch, 엔드포인트)

    response = await provider.complete(system=_시스템, user=_본문)

    assert response.text == "신청자는 서류를 내야 합니다."
    assert response.model == "gpt-4o-테스트판"
    assert (response.input_tokens, response.output_tokens) == (120, 45)


async def test_openai_상태코드_예외는_본문_없이_LLMProviderError로_변환된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    요청 = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
    오류 = OpenAIAPIStatusError(
        f"rate limited: {_본문}", response=httpx.Response(429, request=요청), body=None
    )
    provider = _오픈에이아이_대역(monkeypatch, _가짜엔드포인트(error=오류))

    with pytest.raises(LLMProviderError) as excinfo:
        await provider.complete(system=_시스템, user=_본문)

    메시지 = str(excinfo.value)
    assert "openai" in 메시지
    assert "429" in 메시지
    assert _본문 not in 메시지


async def test_openai_비상태_예외도_LLMProviderError로_변환된다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    오류 = OpenAIError(_본문)
    provider = _오픈에이아이_대역(monkeypatch, _가짜엔드포인트(error=오류))

    with pytest.raises(LLMProviderError) as excinfo:
        await provider.complete(system=_시스템, user=_본문)

    메시지 = str(excinfo.value)
    assert "openai" in 메시지
    assert "OpenAIError" in 메시지
    assert _본문 not in 메시지


@pytest.mark.llm
async def test_anthropic_실제_호출_스모크() -> None:
    """실제 API 1회 호출. 키가 없으면 건너뛴다."""
    api_key = Settings().anthropic_api_key
    if api_key is None:
        pytest.skip("ANTHROPIC_API_KEY 미설정")
    provider = AnthropicProvider(api_key=api_key.get_secret_value())
    # max_tokens는 사고(thinking) 토큰과 공유되므로 스모크에서도 여유 있게 잡는다.
    response = await provider.complete(
        system="한국어로 짧게 답하라.", user="1+1은?", max_tokens=512
    )
    assert response.text
    assert response.output_tokens > 0


@pytest.mark.llm
async def test_openai_실제_호출_스모크() -> None:
    """실제 API 1회 호출. 키가 없으면 건너뛴다."""
    api_key = Settings().openai_api_key
    if api_key is None:
        pytest.skip("OPENAI_API_KEY 미설정")
    provider = OpenAIProvider(api_key=api_key.get_secret_value())
    response = await provider.complete(
        system="한국어로 짧게 답하라.", user="1+1은?", max_tokens=128
    )
    assert response.text
    assert response.output_tokens > 0
