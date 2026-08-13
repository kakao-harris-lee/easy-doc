"""설정에서 LLMProvider를 만드는 팩토리.

골든셋 평가·벤치마크·변환 서비스가 provider 생성 규칙을 각자 복제하지 않도록 한 곳에 둔다.
"""

from typing import Final

from app.config import Settings
from app.llm.anthropic_provider import AnthropicProvider
from app.llm.openai_provider import OpenAIProvider
from app.llm.provider import LLMProvider

#: `settings.llm_effort`를 **실제로 받는** provider 이름.
#:
#: 목록을 따로 두는 이유는 이 값을 묻는 곳이 둘이기 때문이다 — provider를 만드는 쪽
#: (`create_provider`)과, 그 실행이 무엇으로 잰 수치인지 기록하는 쪽
#: (`tests/golden/test_golden_eval.py`의 `run_context` → 기준선 지문의 producer 축).
#: 두 곳이 각자 "anthropic이면"이라고 적으면 한쪽만 고쳐질 때 조용히 갈린다. 아래
#: `applied_effort` 하나만 보게 해서 그 갈림을 없앤다.
EFFORT_AWARE_PROVIDERS: Final[frozenset[str]] = frozenset({AnthropicProvider.name})


def applied_effort(name: str, settings: Settings) -> str | None:
    """`create_provider(name, settings)`가 만든 provider에 **실제로 적용되는** effort.

    설정값(`settings.llm_effort`)과 다르다. 설정값은 어느 벤더로 돌리든 존재하지만,
    effort를 파라미터로 받는 것은 Anthropic뿐이라 OpenAI 레인에서는 그 값이 모델에
    **닿지 않는다**(`OpenAIProvider.__init__`에 인자 자체가 없다).

    이 구분이 필요한 이유는 기준선 지문이다(2026-08-13 독립 검증 A-4 · Claude M-3, 교차
    종합 X-5). 지문의 producer 축은 "이 수치를 누가 냈는가"인데, 설정값을 그대로 실으면
    OpenAI 레인에서 `LLM_EFFORT`만 바꾼 두 실행이 **결과에 아무 영향이 없는데도 비교
    불가**가 된다. 과민한 지문은 하한선이 영영 축적되지 않는 반대 방향 고장이다.

    반대로 값을 지문에서 통째로 빼는 것도 틀리다. Anthropic 레인에서 effort는 진짜
    교란 변수이고, 이 저장소가 9%p 하락의 후보로 스스로 지목한 값이다
    (`04_goldenset-first-run.md`). 그래서 **적용된 경우에만** 싣는다 — 없앤 것은 값이
    아니라 *적용되지도 않은 값이 지문을 흔드는 경로*다.
    """
    return settings.llm_effort if name in EFFORT_AWARE_PROVIDERS else None


def create_provider(name: str, settings: Settings) -> LLMProvider | None:
    """이름에 해당하는 provider를 만든다.

    API 키가 설정되지 않았으면 None을 반환한다(호출 측에서 skip 처리).
    지원하지 않는 이름이면 ValueError.

    settings.llm_model이 있으면 모델명을 덮어쓴다 — 단, llm_provider로 고른 벤더에만
    적용한다. 벤치마크·골든셋 평가는 대상 벤더를 따로 지정하므로(--providers·GOLDEN_PROVIDER),
    다른 벤더에까지 적용하면 없는 모델명으로 호출해 전건 실패한다.

    settings.llm_effort는 Anthropic에만 넘긴다. llm_model처럼 llm_provider와 짝지어
    게이팅하지 않는 이유는 실패 양상이 다르기 때문이다 — 모델명은 벤더가 어긋나면 없는
    이름으로 호출해 전건 실패하지만, effort는 애초에 Anthropic만 받는 파라미터라 다른
    벤더로 새어 나갈 경로가 없다. OpenAI의 reasoning_effort는 별개 파라미터이며 여기서
    다루지 않는다.

    그 "Anthropic에만"을 여기서 직접 적지 않고 `applied_effort`에 묻는다. 같은 판단을
    기준선 지문 쪽도 필요로 하는데(무엇이 이 수치를 냈는가), 두 곳이 각자 적으면 갈린다.
    """
    model = settings.llm_model if name == settings.llm_provider else None
    effort = applied_effort(name, settings)
    if name == AnthropicProvider.name:
        anthropic_key = settings.anthropic_api_key
        if anthropic_key is None:
            return None
        if model is None:
            return AnthropicProvider(api_key=anthropic_key.get_secret_value(), effort=effort)
        return AnthropicProvider(
            api_key=anthropic_key.get_secret_value(), model=model, effort=effort
        )
    if name == OpenAIProvider.name:
        openai_key = settings.openai_api_key
        if openai_key is None:
            return None
        if model is None:
            return OpenAIProvider(api_key=openai_key.get_secret_value())
        return OpenAIProvider(api_key=openai_key.get_secret_value(), model=model)
    raise ValueError(f"지원하지 않는 provider 이름: {name}")
