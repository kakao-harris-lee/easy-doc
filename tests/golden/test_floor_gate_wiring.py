"""바닥 게이트가 골든셋 하네스에 실제로 연결되어 있는지 기본 스위트에서 고정한다.

tests/golden/test_golden_eval.py는 -m llm이라 기본 실행에서 제외된다. 그래서 판정 함수만
테스트하면 "하네스가 그 함수를 호출하는가"는 여전히 무방비다 — 호출을 지워도 기본
스위트는 초록색으로 남는다. 여기서는 API 키 없이 FakeProvider로 하네스의 judge 평가
함수를 직접 돌려 배선을 확인한다 (실제 LLM 호출 없음).
"""

import pytest

from app.llm.fake import FakeProvider
from app.services.conversion import ConversionOutcome
from tests.golden import test_golden_eval as harness

_EASY_TEXT = "오늘 서류를 내세요."
_HIGH = '{"fidelity": 5, "readability": 5, "comment": "-"}'
_FABRICATED = '{"fidelity": 1, "readability": 5, "comment": "-"}'
# 필요한 시나리오는 "평균 게이트는 통과하는데 바닥 위반이 섞여 있는 입력" 하나뿐이다.
# 평균이 임계값에 **정확히** 닿는 날조 건수(n/4)를 계산하려 들면 문서 수가 4의 배수가
# 아닌 순간 전제가 깨진다(실측: 39건에서 4.08). 날조를 1건만 섞으면 평균은 5 - 4/n이라
# n이 4 이상인 한 언제나 기준(4.0) 이상이고, 바닥 위반은 확실히 존재한다.
# 골든셋 하한은 20건이므로(tests/golden/test_schema.py) 조건은 항상 만족된다.
_FABRICATED_COUNT = 1


def _outcomes() -> dict[str, ConversionOutcome | None]:
    """전 문서가 정상 변환된 상황을 만든다(규칙 평가가 아니라 judge 경로만 본다)."""
    return {
        document.id: ConversionOutcome(
            easy_text=_EASY_TEXT, masked_items=[], model="fake", provider_name="fake"
        )
        for document in harness.DOCUMENTS
    }


def test_시나리오가_평균_게이트를_통과하는_입력이다() -> None:
    """전제 확인 — 두 조건이 함께 성립해야 아래 테스트가 바닥 게이트를 본 것이 된다.

    ① 바닥 위반이 실제로 섞여 있고, ② 그런데도 평균은 기준 이상이다. 평균이 기준
    미만이면 실패의 원인이 바닥 게이트인지 평균 게이트인지 구분되지 않는다.
    """
    assert 0 < _FABRICATED_COUNT < len(harness.DOCUMENTS)
    high_count = len(harness.DOCUMENTS) - _FABRICATED_COUNT
    average = (high_count * 5 + _FABRICATED_COUNT * 1) / len(harness.DOCUMENTS)
    assert average >= harness.JUDGE_SCORE_THRESHOLD


async def test_평균을_통과해도_날조가_섞이면_하네스가_실패한다() -> None:
    """하네스가 find_low_fidelity를 실제로 호출하는지 고정한다."""
    responses: list[str | Exception] = [_HIGH] * (len(harness.DOCUMENTS) - _FABRICATED_COUNT)
    responses += [_FABRICATED] * _FABRICATED_COUNT
    with pytest.raises(AssertionError, match="이하 문서"):
        await harness.test_골든셋_judge_점수(_outcomes(), FakeProvider(responses=responses))


async def test_전부_높은_점수면_하네스가_통과한다() -> None:
    """게이트가 무조건 실패하는 것이 아님을 함께 고정한다."""
    responses: list[str | Exception] = [_HIGH] * len(harness.DOCUMENTS)
    await harness.test_골든셋_judge_점수(_outcomes(), FakeProvider(responses=responses))
