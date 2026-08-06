"""쉬운 글 스타일 규칙 단위 테스트."""

from typing import cast

import pytest

from app.easyread.style_rules import (
    DIFFICULT_WORD_REPLACEMENTS,
    MAX_COMMAS_PER_SENTENCE,
    MAX_SENTENCE_CHARS,
    PROMPT_ONLY_WORDS,
    check_style,
    find_difficult_words,
    split_sentences,
)

# 사전 확충의 최소 규모. 프롬프트 지시만으로는 어려운 낱말 잔존이 확률적으로 남아
# (통과율 0.75~0.85 플래토) 기계 검출로 잡을 수 있는 표면을 넓힌 것이 확충의 목적이다.
MIN_REPLACEMENTS = 200

# 확충 이전의 시드 25개. 골든셋 채점 연속성을 위해 항목과 값이 모두 유지돼야 한다 —
# 값이 바뀌면 과거 평가 결과와 같은 기준으로 비교할 수 없다.
SEED_REPLACEMENTS = {
    "금일": "오늘",
    "명일": "내일",
    "익일": "다음 날",
    "익월": "다음 달",
    "당월": "이번 달",
    "동절기": "겨울철",
    "하절기": "여름철",
    "상기": "위",
    "하기": "아래",
    "소정의": "정해진",
    "제반": "여러",
    "구비서류": "준비할 서류",
    "지참": "가지고 오기",
    "송부": "보내기",
    "기재": "적기",
    "수령": "받기",
    "납부": "내기",
    "미납": "내지 않음",
    "잔여": "남은",
    "경감": "줄임",
    "감면": "깎아 줌",
    "부득이한": "어쩔 수 없는",
    "해당자": "해당하는 사람",
    "미제출": "내지 않음",
    "도래": "다가옴",
}

# 의도적으로 남긴 키-키 부분 문자열 쌍 (좁은 키, 넓은 키).
# 둘 다 바꿔야 할 표현이라 지적이 겹칠 뿐 결과가 달라지지 않는다.
INTENDED_NESTED_KEYS = {
    ("구비", "구비서류"),
    ("제출", "미제출"),
    ("접수", "접수처"),
    ("지급", "미지급"),
    ("거주", "거주지"),
    ("사유", "결격사유"),
}

# 부분 문자열 오탐 회귀 코퍼스 — 모두 이미 '쉬운 글'이라 한 건도 걸리면 안 된다.
# 골든셋 필수 팩트(기관명·수치 표현)와 일상어를 함께 담아, 사전을 넓힐 때 채점이
# 실력과 무관하게 실패하는 것을 막는다.
EASY_TEXT_CORPUS = (
    "온라인으로 신청하기 화면을 누르세요.",
    "이상기후로 행사가 미뤄졌습니다.",
    "8세 미만 어린이는 무료입니다.",
    "30일 이내에 알려 주세요.",
    "6세 이상이면 신청할 수 있어요.",
    "120세대가 사는 아파트입니다.",
    "1종 수급자는 돈을 안 내도 됩니다.",
    "초기 상담은 20분 동안 합니다.",
    "상급종합병원에 가면 돈을 더 냅니다.",
    "차상위계층도 신청할 수 있습니다.",
    "한국전력에 물어보세요.",
    "국가건강검진을 받으세요.",
    "아이행복카드로 낼 수 있어요.",
    "불이 나면 연기를 피해 낮게 엎드려 나가세요.",
    "상한 음식은 먹지 마세요.",
    "휴대전화로 연락드립니다.",
    "반려동물과 함께 살 수 있어요.",
    "게시판에서 살펴보세요.",
    "이 일을 담당해 주셔서 고맙습니다.",
    "노후 준비를 도와드립니다.",
    "전원 참석해 주세요.",
    "주민센터에 가서 물어보세요.",
    "정부24 누리집에서도 신청할 수 있어요.",
    "국민연금공단에서 알려 줍니다.",
    "희망복지지원단이 함께 돕습니다.",
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


def test_복합어_안쪽에_박힌_표현은_검출하지_않는다() -> None:
    """'소득인정액'의 '정액'처럼 앞 글자가 한글이면 더 긴 낱말의 일부다."""
    assert find_difficult_words("소득인정액이 기준을 넘습니다.") == []
    assert find_difficult_words("통장사본을 챙기세요.") == []
    assert find_difficult_words("대지급금 제도입니다.") == []


def test_낱말_시작이면_조사가_붙어도_검출한다() -> None:
    """뒤에 붙는 조사·어미는 낱말 경계가 아니다 — 진짜 위반이라 잡아야 한다."""
    assert "감면" in find_difficult_words("요금을 감면을 받습니다.")
    assert "제출" in find_difficult_words("서류를 제출하십시오.")


def test_문장_첫머리_표현을_검출한다() -> None:
    """앞 글자가 아예 없는 자리(문장·줄 첫머리)도 낱말 시작이다."""
    assert "납부" in find_difficult_words("납부 기한을 지키세요.")
    assert "지급" in find_difficult_words("지급 대상은 다음과 같습니다.")


def test_사전이_최소_규모_이상이다() -> None:
    assert len(DIFFICULT_WORD_REPLACEMENTS) >= MIN_REPLACEMENTS


def test_시드_항목이_값까지_유지된다() -> None:
    """확충이 기존 항목을 덮어쓰면 골든셋 통과율을 과거와 비교할 수 없다."""
    kept = {word: DIFFICULT_WORD_REPLACEMENTS.get(word) for word in SEED_REPLACEMENTS}
    assert kept == SEED_REPLACEMENTS


def test_키는_두_글자_이상이다() -> None:
    """한 글자 키는 어떤 낱말에나 걸려 채점이 무의미해진다."""
    assert [word for word in DIFFICULT_WORD_REPLACEMENTS if len(word) < 2] == []


def test_치환값에_다른_키가_들어_있지_않다() -> None:
    """자기 세탁 방지 — 바꾼 결과가 다시 위반이면 보정 패스가 같은 지적을 반복한다.

    예: "사본 → 복사본", "납부 → 납입". 값에 "-하기"를 쓰는 것도 같은 이유로 막힌다
    ("하기"가 키다).
    """
    laundered = [
        (word, replacement, other)
        for word, replacement in DIFFICULT_WORD_REPLACEMENTS.items()
        for other in DIFFICULT_WORD_REPLACEMENTS
        if other in replacement
    ]
    assert laundered == []


def test_키끼리_부분_문자열_관계는_의도한_쌍뿐이다() -> None:
    """새 키가 기존 키를 삼키면 지적 사유가 조용히 겹친다 — 의도한 쌍만 남긴다."""
    nested = {
        (narrow, wide)
        for narrow in DIFFICULT_WORD_REPLACEMENTS
        for wide in DIFFICULT_WORD_REPLACEMENTS
        if narrow != wide and narrow in wide
    }
    assert nested == INTENDED_NESTED_KEYS


def test_문맥_판단_표현은_모두_사전에_있다() -> None:
    """PROMPT_ONLY_WORDS는 채점에서만 빠질 뿐 프롬프트 치환 지시에는 남아야 한다."""
    assert DIFFICULT_WORD_REPLACEMENTS.keys() >= PROMPT_ONLY_WORDS


@pytest.mark.parametrize("sentence", EASY_TEXT_CORPUS)
def test_쉬운_문장에서_부분_문자열_오탐이_없다(sentence: str) -> None:
    assert find_difficult_words(sentence) == []


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


def test_어려운_표현_이슈는_치환할_낱말을_담는다() -> None:
    """보정 프롬프트가 사유 문자열을 되파싱하지 않고 사전 키를 그대로 쓰게 한다."""
    result = check_style("금일 중으로 제출하십시오.")
    words = {issue.word for issue in result.issues}
    assert words == {"금일", "제출"}
    # 길이·쉼표·피동 위반은 치환할 낱말이 없다.
    assert check_style("가" * (MAX_SENTENCE_CHARS + 1) + ".").issues[0].word is None


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
