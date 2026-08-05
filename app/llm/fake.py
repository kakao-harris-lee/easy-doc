"""테스트용 FakeProvider — 서비스 단위 테스트에서 실제 LLM 호출을 대체한다."""

from pydantic import BaseModel

from app.llm.provider import LLMProvider, LLMResponse


class FakeCall(BaseModel):
    """FakeProvider가 기록한 호출 인자."""

    system: str
    user: str


class FakeProvider(LLMProvider):
    """준비된 응답을 순서대로 돌려주는 테스트 대역."""

    name = "fake"

    def __init__(self, responses: list[str]) -> None:
        self._responses = list(responses)
        self.calls: list[FakeCall] = []

    async def complete(
        self, *, system: str, user: str, max_tokens: int = 4096, temperature: float = 0.2
    ) -> LLMResponse:
        """큐 앞에서 응답을 하나 꺼내 반환한다. 소진되면 IndexError."""
        self.calls.append(FakeCall(system=system, user=user))
        # 소진 시 IndexError를 그대로 노출한다 — 테스트가 준비한 응답 수와
        # 실제 호출 수의 불일치를 조용히 넘기지 않기 위한 의도된 동작.
        text = self._responses.pop(0)
        return LLMResponse(text=text, model="fake")
