"""쉬운 글 스타일 규칙 단위 테스트."""

from typing import cast

import pytest

from app.easyread.style_rules import (
    DIFFICULT_WORD_REPLACEMENTS,
    MAX_COMMAS_PER_SENTENCE,
    MAX_SENTENCE_CHARS,
    check_style,
    find_difficult_words,
    split_sentences,
)


def test_split_sentences_기본() -> None:
    text = "신청 기간은 3월 2일까지입니다. 주민센터에 방문하세요."
    assert split_sentences(text) == ["신청 기간은 3월 2일까지입니다.", "주민센터에 방문하세요."]


def test_긴_문장은_이슈로_보고된다() -> None:
    long_sentence = "가" * (MAX_SENTENCE_CHARS + 1) + "."
    result = check_style(long_sentence)
    assert result.issues and "길이" in result.issues[0].reason


def test_이중_피동_검출() -> None:
    result = check_style("이 제도는 개선되어지고 있습니다.")
    assert any("피동" in issue.reason for issue in result.issues)


def test_어려운_한자어_검출() -> None:
    assert "금일" in find_difficult_words("금일 중으로 서류를 제출하십시오.")


def test_쉬운_문장은_통과() -> None:
    result = check_style("오늘 서류를 내세요.")
    assert result.passed


def test_상기_하기는_자동_채점에서_제외된다() -> None:
    """'~하기 위해' 등 정상 활용과 기계적으로 구분 불가 → 채점 제외, 프롬프트용으로만 유지."""
    assert find_difficult_words("온라인으로 신청하기 화면을 누르세요.") == []
    assert find_difficult_words("이상기후로 행사가 연기되었습니다.") == []
    assert find_difficult_words("하기와 같이 안내합니다.") == []
    assert check_style("서류를 준비하기 바랍니다.").passed
    # 프롬프트 치환 지시용 SSOT에는 그대로 남아 있어야 한다
    assert DIFFICULT_WORD_REPLACEMENTS["하기"] == "아래"
    assert DIFFICULT_WORD_REPLACEMENTS["상기"] == "위"


def test_치환목록은_불변이다() -> None:
    """SSOT가 런타임에 오염되지 않도록 읽기 전용으로 노출한다."""
    mutable = cast(dict[str, str], DIFFICULT_WORD_REPLACEMENTS)
    with pytest.raises(TypeError):
        mutable["금일"] = "변조"


def test_어려운_표현_이슈는_해당_문장을_기록한다() -> None:
    """검수 화면에서 위치를 찾을 수 있도록 단어가 아니라 문장을 담는다."""
    result = check_style("오늘 서류를 내세요. 금일 중으로 제출하십시오.")
    issue = next(i for i in result.issues if "금일" in i.reason)
    assert issue.sentence == "금일 중으로 제출하십시오."


def test_개조식_항목_마커는_문장으로_세지_않는다() -> None:
    """'1.'·'가.' 같은 마커 조각이 문장 수를 부풀리지 않아야 한다."""
    result = check_style("1. 신청 대상 2. 신청 방법")
    assert result.total_sentences == 2
    assert split_sentences("가. 첫째 나. 둘째") == ["첫째 나.", "둘째"]


@pytest.mark.parametrize(
    ("sentence", "expected_pass"),
    [
        ("김치, 두부, 쌀을 삽니다.", True),  # 쉼표 2개 = 허용 경계
        ("김치, 두부, 쌀, 김을 삽니다.", False),  # 쉼표 3개 = 위반
        ("김치、두부、쌀、김을 삽니다.", False),  # 전각 쉼표도 동일 취급
    ],
)
def test_쉼표_개수_경계(sentence: str, expected_pass: bool) -> None:
    assert MAX_COMMAS_PER_SENTENCE == 2
    assert check_style(sentence).passed is expected_pass
