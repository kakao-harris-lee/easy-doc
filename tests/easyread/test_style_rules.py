"""쉬운 글 스타일 규칙 단위 테스트."""

from app.easyread.style_rules import (
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


def test_동사_활용형은_어려운_표현이_아니다() -> None:
    """'신청하기'의 '하기'는 下記가 아니므로 검출하지 않는다(오탐 방지)."""
    assert find_difficult_words("온라인으로 신청하기 화면을 누르세요.") == []
    assert check_style("서류를 준비하기 바랍니다.").passed
    assert find_difficult_words("이상기후로 행사가 연기되었습니다.") == []


def test_행정용어로_쓰인_하기_상기는_검출된다() -> None:
    """어절 첫머리에 쓰인 下記·上記는 그대로 검출한다(미탐 방지)."""
    assert "하기" in find_difficult_words("하기와 같이 안내합니다.")
    assert "상기" in find_difficult_words("위 상기 내용을 확인하세요.")
