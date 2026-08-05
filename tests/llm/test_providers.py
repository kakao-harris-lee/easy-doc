"""Anthropic·OpenAI provider 구현체 단위 테스트.

기본 실행에서는 실제 API를 호출하지 않는다. 두 층위로 검증한다.
① 대역 클라이언트: system/user 페이로드 위치, 응답 변환, 예외 변환(본문 미유출), 가드.
② httpx.MockTransport 계약 테스트: SDK가 실제로 직렬화한 요청 본문과 실제 응답 스키마를
   통과시켜, 파라미터명·응답 필드가 SDK 버전과 어긋나면 바로 깨지게 한다.

SDK 타입만 직접 import한다 — 아키텍처 규칙 1은 서비스 코드를 대상으로 하고, 이 파일은
벤더 경계(provider 구현체) 자체를 검증하는 테스트다. 서비스·라우터 계층은 여전히
LLMProvider 인터페이스만 사용한다.
"""

import json
from collections.abc import Callable
from types import SimpleNamespace

import httpx
import pytest
from anthropic import AnthropicError, AsyncAnthropic
from anthropic import APIStatusError as AnthropicAPIStatusError
from openai import APIStatusError as OpenAIAPIStatusError
from openai import AsyncOpenAI, OpenAIError

from app.config import Settings
from app.exceptions import LLMProviderError
from app.llm import anthropic_provider as anthropic_module
from app.llm import openai_provider as openai_module
from app.llm.anthropic_provider import AnthropicProvider
from app.llm.openai_provider import OpenAIProvider
from app.llm.provider import DEFAULT_MAX_RETRIES, DEFAULT_TIMEOUT_SECONDS

_시스템 = "너는 쉬운 글 변환기다."
_본문 = "신청자는 관계 법령에 의거하여 서류를 제출하여야 한다."
_결과 = "신청자는 서류를 내야 합니다."
# 계약 테스트는 실제로 HTTP 헤더를 만들므로 ASCII 키를 쓴다(헤더는 ascii 인코딩).
_계약_키 = "test-api-key"


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


class _가짜클라이언트:
    """close() 호출 여부만 기록하는 SDK 클라이언트 대역."""

    def __init__(self) -> None:
        self.closed = False

    async def close(self) -> None:
        self.closed = True


def _앤트로픽_응답(*, stop_reason: str = "end_turn", 텍스트: str | None = _결과) -> SimpleNamespace:
    """텍스트 블록과 비텍스트 블록이 섞인 실제 응답 형태."""
    블록 = [SimpleNamespace(type="thinking", thinking="내부 추론")]
    if 텍스트 is not None:
        블록.append(SimpleNamespace(type="text", text=텍스트))
    return SimpleNamespace(
        model="claude-sonnet-5-테스트판",
        content=블록,
        stop_reason=stop_reason,
        usage=SimpleNamespace(input_tokens=120, output_tokens=45),
    )


def _오픈에이아이_응답(
    *, finish_reason: str = "stop", content: str | None = _결과
) -> SimpleNamespace:
    return SimpleNamespace(
        model="gpt-4o-테스트판",
        choices=[
            SimpleNamespace(message=SimpleNamespace(content=content), finish_reason=finish_reason)
        ],
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


def _계약_핸들러(
    포착: dict[str, object], 응답본문: dict[str, object]
) -> Callable[[httpx.Request], httpx.Response]:
    """직렬화된 요청 본문을 포착하고 실제 응답 스키마 JSON을 돌려준다."""

    def _handler(request: httpx.Request) -> httpx.Response:
        포착.update(json.loads(request.content))
        return httpx.Response(200, json=응답본문)

    return _handler


def test_생성자_기본값과_name() -> None:
    앤트로픽 = AnthropicProvider(api_key="테스트키")
    오픈에이아이 = OpenAIProvider(api_key="테스트키")
    assert (앤트로픽.name, 앤트로픽.model) == ("anthropic", "claude-sonnet-5")
    assert (오픈에이아이.name, 오픈에이아이.model) == ("openai", "gpt-4o")


def test_클라이언트에_타임아웃과_재시도가_명시된다(monkeypatch: pytest.MonkeyPatch) -> None:
    """SDK 기본값(read 600초 × 재시도 3회)에 기대지 않는다."""
    인자: dict[str, dict[str, object]] = {}

    def _앤트로픽(**kwargs: object) -> object:
        인자["anthropic"] = kwargs
        return SimpleNamespace()

    def _오픈에이아이(**kwargs: object) -> object:
        인자["openai"] = kwargs
        return SimpleNamespace()

    monkeypatch.setattr(anthropic_module, "AsyncAnthropic", _앤트로픽)
    monkeypatch.setattr(openai_module, "AsyncOpenAI", _오픈에이아이)

    AnthropicProvider(api_key="테스트키", timeout_seconds=5.0)
    OpenAIProvider(api_key="테스트키")

    assert 인자["anthropic"]["timeout"] == 5.0
    assert 인자["anthropic"]["max_retries"] == DEFAULT_MAX_RETRIES
    assert 인자["openai"]["timeout"] == DEFAULT_TIMEOUT_SECONDS
    assert 인자["openai"]["max_retries"] == DEFAULT_MAX_RETRIES


async def test_aclose는_SDK_클라이언트를_닫는다(monkeypatch: pytest.MonkeyPatch) -> None:
    앤트로픽_클라이언트 = _가짜클라이언트()
    오픈에이아이_클라이언트 = _가짜클라이언트()
    monkeypatch.setattr(anthropic_module, "AsyncAnthropic", lambda **_: 앤트로픽_클라이언트)
    monkeypatch.setattr(openai_module, "AsyncOpenAI", lambda **_: 오픈에이아이_클라이언트)

    await AnthropicProvider(api_key="테스트키").aclose()
    await OpenAIProvider(api_key="테스트키").aclose()

    assert (앤트로픽_클라이언트.closed, 오픈에이아이_클라이언트.closed) == (True, True)


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
    # 현재 Claude 모델은 샘플링 파라미터를 받으면 400 — 회귀 방지.
    assert "temperature" not in 엔드포인트.kwargs


async def test_anthropic_응답이_LLMResponse로_변환된다(monkeypatch: pytest.MonkeyPatch) -> None:
    """텍스트 블록만 합치고, 모델·토큰 수는 응답에서 읽는다."""
    엔드포인트 = _가짜엔드포인트(result=_앤트로픽_응답())
    provider = _앤트로픽_대역(monkeypatch, 엔드포인트)

    response = await provider.complete(system=_시스템, user=_본문)

    assert response.text == _결과
    assert response.model == "claude-sonnet-5-테스트판"
    assert (response.input_tokens, response.output_tokens) == (120, 45)
    assert response.truncated is False


async def test_anthropic_stop_reason이_max_tokens면_truncated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    엔드포인트 = _가짜엔드포인트(result=_앤트로픽_응답(stop_reason="max_tokens"))
    provider = _앤트로픽_대역(monkeypatch, 엔드포인트)

    response = await provider.complete(system=_시스템, user=_본문)

    assert response.truncated is True


async def test_anthropic_텍스트_블록이_없으면_LLMProviderError(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """사고 블록만 온 응답을 빈 변환 결과로 흘려보내지 않는다."""
    엔드포인트 = _가짜엔드포인트(result=_앤트로픽_응답(텍스트=None))
    provider = _앤트로픽_대역(monkeypatch, 엔드포인트)

    with pytest.raises(LLMProviderError, match="빈 응답"):
        await provider.complete(system=_시스템, user=_본문)


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

    assert response.text == _결과
    assert response.model == "gpt-4o-테스트판"
    assert (response.input_tokens, response.output_tokens) == (120, 45)
    assert response.truncated is False


async def test_openai_finish_reason이_length면_truncated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    엔드포인트 = _가짜엔드포인트(result=_오픈에이아이_응답(finish_reason="length"))
    provider = _오픈에이아이_대역(monkeypatch, 엔드포인트)

    response = await provider.complete(system=_시스템, user=_본문)

    assert response.truncated is True


async def test_openai_choices가_비면_LLMProviderError(monkeypatch: pytest.MonkeyPatch) -> None:
    빈_응답 = SimpleNamespace(model="gpt-4o-테스트판", choices=[], usage=None)
    provider = _오픈에이아이_대역(monkeypatch, _가짜엔드포인트(result=빈_응답))

    with pytest.raises(LLMProviderError, match="빈 choices"):
        await provider.complete(system=_시스템, user=_본문)


async def test_openai_본문이_비면_LLMProviderError(monkeypatch: pytest.MonkeyPatch) -> None:
    엔드포인트 = _가짜엔드포인트(result=_오픈에이아이_응답(content=None))
    provider = _오픈에이아이_대역(monkeypatch, 엔드포인트)

    with pytest.raises(LLMProviderError, match="빈 응답"):
        await provider.complete(system=_시스템, user=_본문)


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


async def test_anthropic_계약_직렬화된_요청과_실제_응답_스키마(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """SDK가 실제로 만든 HTTP 본문과 실제 응답 스키마로 왕복을 검증한다."""
    포착: dict[str, object] = {}
    응답본문: dict[str, object] = {
        "id": "msg_test",
        "type": "message",
        "role": "assistant",
        "model": "claude-sonnet-5-테스트판",
        "content": [{"type": "text", "text": _결과}],
        "stop_reason": "max_tokens",
        "stop_sequence": None,
        "usage": {"input_tokens": 120, "output_tokens": 45},
    }
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(_계약_핸들러(포착, 응답본문)))
    monkeypatch.setattr(
        anthropic_module,
        "AsyncAnthropic",
        lambda **_: AsyncAnthropic(api_key=_계약_키, http_client=http_client, max_retries=0),
    )
    provider = AnthropicProvider(api_key=_계약_키)

    response = await provider.complete(system=_시스템, user=_본문, max_tokens=1024)
    await provider.aclose()

    assert 포착["model"] == "claude-sonnet-5"
    assert 포착["max_tokens"] == 1024
    assert 포착["system"] == _시스템
    assert 포착["messages"] == [{"role": "user", "content": _본문}]
    assert "temperature" not in 포착
    assert response.text == _결과
    assert response.model == "claude-sonnet-5-테스트판"
    assert (response.input_tokens, response.output_tokens) == (120, 45)
    assert response.truncated is True


async def test_openai_계약_직렬화된_요청과_실제_응답_스키마(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """SDK가 실제로 만든 HTTP 본문과 실제 응답 스키마로 왕복을 검증한다."""
    포착: dict[str, object] = {}
    응답본문: dict[str, object] = {
        "id": "chatcmpl-test",
        "object": "chat.completion",
        "created": 0,
        "model": "gpt-4o-테스트판",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": _결과},
                "finish_reason": "length",
            }
        ],
        "usage": {"prompt_tokens": 120, "completion_tokens": 45, "total_tokens": 165},
    }
    http_client = httpx.AsyncClient(transport=httpx.MockTransport(_계약_핸들러(포착, 응답본문)))
    monkeypatch.setattr(
        openai_module,
        "AsyncOpenAI",
        lambda **_: AsyncOpenAI(api_key=_계약_키, http_client=http_client, max_retries=0),
    )
    provider = OpenAIProvider(api_key=_계약_키)

    response = await provider.complete(system=_시스템, user=_본문, max_tokens=1024, temperature=0.5)
    await provider.aclose()

    assert 포착["model"] == "gpt-4o"
    assert 포착["max_completion_tokens"] == 1024
    assert "max_tokens" not in 포착  # deprecated 파라미터로 되돌아가지 않았는지
    assert 포착["temperature"] == 0.5
    assert 포착["messages"] == [
        {"role": "system", "content": _시스템},
        {"role": "user", "content": _본문},
    ]
    assert response.text == _결과
    assert response.model == "gpt-4o-테스트판"
    assert (response.input_tokens, response.output_tokens) == (120, 45)
    assert response.truncated is True


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
    await provider.aclose()
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
    await provider.aclose()
    assert response.text
    assert response.output_tokens > 0
