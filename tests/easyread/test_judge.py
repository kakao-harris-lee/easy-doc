"""LLM-as-judge 채점기 테스트 — 파싱 견고성과 마스킹 선행 불변식이 핵심."""

import pytest

from app.easyread.judge import (
    JudgeScore,
    build_judge_prompt,
    judge_conversion,
    parse_judge_response,
)
from app.exceptions import LLMProviderError
from app.llm.fake import FakeProvider

_VALID = '{"fidelity": 4, "readability": 5, "comment": "날짜가 하나 빠졌습니다."}'


def test_정상_JSON_응답을_파싱한다() -> None:
    score = parse_judge_response(_VALID)
    assert score == JudgeScore(fidelity=4, readability=5, comment="날짜가 하나 빠졌습니다.")


def test_코드_펜스로_감싼_JSON도_파싱한다() -> None:
    """프롬프트로 금지해도 모델이 펜스를 붙이는 경우가 있다."""
    assert parse_judge_response(f"```json\n{_VALID}\n```").fidelity == 4


@pytest.mark.parametrize(
    "raw",
    [
        f"채점 결과입니다:\n{_VALID}",
        f"{_VALID}\n\n이상입니다.",
        f"설명이 앞에 붙고 {_VALID} 뒤에도 붙는 경우",
    ],
)
def test_앞뒤에_설명이_붙어도_JSON_구간을_찾아낸다(raw: str) -> None:
    """머리말·꼬리말을 붙이는 모델 때문에 채점 자체가 실패하지 않도록 한 번 더 시도한다."""
    score = parse_judge_response(raw)
    assert (score.fidelity, score.readability) == (4, 5)


@pytest.mark.parametrize(
    "raw",
    [
        "채점을 할 수 없습니다.",
        "",
        '{"fidelity": 4, "readability": 5',
        '{"fidelity": 4}',
    ],
)
def test_JSON으로_해석할_수_없으면_예외(raw: str) -> None:
    with pytest.raises(LLMProviderError):
        parse_judge_response(raw)


@pytest.mark.parametrize(
    "raw",
    [
        '{"fidelity": 0, "readability": 5, "comment": ""}',
        '{"fidelity": 3, "readability": 6, "comment": ""}',
        '{"fidelity": -1, "readability": 1, "comment": ""}',
    ],
)
def test_점수_범위를_벗어나면_예외(raw: str) -> None:
    """1~5 밖의 값을 그대로 평균에 넣으면 집계가 오염된다."""
    with pytest.raises(LLMProviderError):
        parse_judge_response(raw)


def test_예외_메시지에_응답_원문이_담기지_않는다() -> None:
    """채점 응답에는 문서 본문 일부가 섞일 수 있어 로그로 새면 안 된다."""
    raw = "이 안내문은 3월 2일 기초연금 신청을 다룹니다"
    with pytest.raises(LLMProviderError) as error:
        parse_judge_response(raw)
    assert raw not in str(error.value)
    assert error.value.__cause__ is None


def test_프롬프트에_원문과_변환문이_모두_들어간다() -> None:
    system, user = build_judge_prompt("금일까지 제출하십시오.", "오늘까지 내세요.")
    assert "금일까지 제출하십시오." in user
    assert "오늘까지 내세요." in user
    assert "금일까지 제출하십시오." not in system


def test_프롬프트에_채점_기준과_출력_형식이_명시된다() -> None:
    system, _ = build_judge_prompt("원문", "변환문")
    assert "충실성" in system
    assert "이해 용이성" in system
    assert "발달장애인" in system
    assert "5점" in system and "1점" in system
    assert '{"fidelity"' in system


def test_원문과_변환문_구간이_서로_다른_구분자로_감싸진다() -> None:
    """구분자가 같으면 본문에 심은 닫는 태그로 구간을 뒤섞을 수 있다."""
    _, user = build_judge_prompt("원문", "변환문")
    assert user.count("<원문 id=") == 1
    assert user.count("<변환문 id=") == 1


async def test_judge_conversion이_점수를_반환한다() -> None:
    provider = FakeProvider(responses=[_VALID])
    score = await judge_conversion(provider, source="원문입니다.", converted="쉬운 글입니다.")
    assert (score.fidelity, score.readability) == (4, 5)


async def test_judge_conversion은_원문을_마스킹한_뒤_전달한다() -> None:
    """채점도 LLM 호출이므로 마스킹 선행 불변식이 그대로 적용된다."""
    provider = FakeProvider(responses=[_VALID])
    await judge_conversion(
        provider,
        source="문의 010-1234-5678, 담당자 hong@example.go.kr",
        converted="문의는 [[전화번호1]]로 해 주세요.",
    )
    sent = provider.calls[0].user
    assert "010-1234-5678" not in sent
    assert "hong@example.go.kr" not in sent
    assert "[[전화번호1]]" in sent


async def test_판독_불가_응답은_LLMProviderError() -> None:
    provider = FakeProvider(responses=["채점 불가"])
    with pytest.raises(LLMProviderError):
        await judge_conversion(provider, source="원문", converted="변환문")
