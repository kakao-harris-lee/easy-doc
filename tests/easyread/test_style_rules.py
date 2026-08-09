"""쉬운 글 스타일 규칙 단위 테스트."""

from typing import cast

import pytest

from app.easyread.goldenset import load_documents
from app.easyread.style_rules import (
    COMPOUND_HEAD_NOUNS,
    COMPOUND_TAIL_KEYS,
    DIFFICULT_WORD_REPLACEMENTS,
    LEXICALIZED_GLOSSES,
    MAX_COMMAS_PER_SENTENCE,
    MAX_SENTENCE_CHARS,
    MODIFIER_CHECKED_GLOSSES,
    NOMINAL_GLOSSES,
    PROMPT_ONLY_WORDS,
    check_style,
    find_difficult_words,
    find_gloss_collisions,
    split_sentences,
)
from tests.easyread.converted_samples import CONVERTED_SAMPLES
from tests.golden import DOCUMENTS_DIR

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


# --- 치환 비문(뜻풀이 축자 삽입) 검출 ---

# 2026-08-09 문서 020에서 사람이 확인한 비문 3종. 이 셋은 반드시 잡혀야 한다.
# 문장은 관찰된 출력 그대로다(docs/quality/2026-08-09-doc020-fidelity-review.md) —
# 바꿔 쓰면 회귀 테스트가 실제 사건과 끊어진다.
OBSERVED_GLOSS_COLLISIONS = (
    # 명사형 뜻풀이 + 용언 (review:289)
    ("뽑히신 분은 바우처 카드를 내어 줌 받아 사용하시면 됩니다.", "내어 줌"),
    ("뽑음 결과", "뽑음"),  # 명사형 뜻풀이 + 체언(제목, review:287)
    # 복합어 자리 (review:294)
    ("· 사용 정해진 날짜가 지나면 남은 금액은 자동으로 없어집니다.", "정해진 날짜"),
)

# 오탐 회귀 코퍼스 — 모두 자연스러운 표현이라 한 건도 걸리면 안 된다.
# "~음 받"처럼 형태소로 일반화하면 여기 대부분이 걸린다(그래서 값 문자열에 앵커링한다).
NATURAL_TEXT_CORPUS = (
    "가까운 평생학습관에서 도움 받으실 수 있습니다.",
    "배움을 원하는 성인에게 지원합니다.",
    "노래 모음집을 나눠 드립니다.",
    "알림 문자를 받으실 수 있습니다.",
    "돌봄 서비스를 신청하세요.",
    "처음 하시는 분은 도움을 받으세요.",
    "지금 하는 신청은 무료입니다.",
    "이름 하나만 적으세요.",
    "그 남은 돈은 자동으로 사라집니다.",
    "올해 남은 돈을 돌려드립니다.",
    "제2조제10호에 해당하는 사람 중에서 뽑습니다.",
    "사용 방법과 정해진 날짜를 확인하세요.",
    "사용할 수 있는 정해진 날짜가 지났습니다.",
    "법으로 정해진 금액을 드립니다.",
    "우리가 지킴 활동을 합니다.",
    "선생님이 도움 주시는 시간입니다.",
    # 복합어 안쪽('줄바꿈'의 '바꿈')은 뜻풀이가 끼워진 자리가 아니다.
    "줄바꿈 기준으로 문장을 나눕니다.",
    "옷차림 단정하게 오세요.",
    # 관형구 뜻풀이 앞의 부사·시간명사 — 조사가 없어도 복합어 자리가 아니다.
    # 사전이 '정액 → 정해진 금액'을 지시하므로 이런 문장이 정상 변환 결과다.
    "매달 정해진 금액을 드립니다.",
    "매월 정해진 금액을 지급합니다.",
    "미리 정해진 날짜에 서류를 내세요.",
    "이미 정해진 날짜입니다.",
    "매년 정해진 금액을 지원합니다.",
    "아래 정해진 날짜를 보세요.",
    "다시 정해진 날짜를 알려 드립니다.",
    "올해 정해진 금액은 30만 원입니다.",
    "해마다 정해진 날짜에 신청하세요.",
    "따로 정해진 날짜는 없습니다.",
    # 끝 글자가 한 글자 조사 목록에 없는 다음절 조사 뒤
    "학생에게 정해진 금액을 드립니다.",
    "신청자마다 정해진 날짜가 다릅니다.",
    "3월부터 정해진 날짜에 받습니다.",
    "생각보다 정해진 날짜가 이릅니다.",
    "예상만큼 정해진 금액을 받습니다.",
    "3월까지 정해진 날짜에 내세요.",
    "주민센터에서 정해진 금액을 드립니다.",
    # 복합어 앞자리 낱말이 관형구 뜻풀이 **가까이** 오지만 붙지는 않는 정상 문장.
    # 화이트리스트를 넓힐 때 가장 먼저 깨질 자리라 항목마다 한 문장씩 둔다.
    "변경 없이 정해진 날짜에 진행합니다.",
    "심사를 거쳐 정해진 날짜에 발표합니다.",
    "결제는 정해진 날짜에 하시면 됩니다.",
    "신고 후 정해진 날짜에 결과를 받습니다.",
    "가입할 때 정해진 금액을 냅니다.",
    "입금하면 정해진 날짜에 처리됩니다.",
    "환불 절차는 정해진 날짜 안에 마칩니다.",
    "심의 결과에 따라 정해진 금액을 드립니다.",
    "회신을 받으면 정해진 날짜에 알려 드립니다.",
    "이행하지 않으면 정해진 금액을 내야 합니다.",
    # 사고·현상 이름으로 굳은 명사형 값
    "휠체어가 걸림 없이 지나갈 수 있습니다.",
    "유리 깨짐 사고가 나면 알려 주세요.",
    "일정이 겹침 없이 진행됩니다.",
    "높임 표현을 씁니다.",
    "줄임 표현을 쓰지 마세요.",
    "낙하 떨어짐 주의 표지판입니다.",
    "건물 무너짐 사고를 대비합니다.",
)


# NOMINAL_GLOSSES 스냅샷 — 사전에 -ㅁ으로 끝나는 값이 새로 들어오면 검출 대상이
# 조용히 늘어나므로(그 즉시 자연 표현을 잡기 시작한다) 여기서 못 박는다.
# 이 테스트가 깨지면 새 값을 검출 대상에 넣을지 LEXICALIZED_GLOSSES로 뺄지 판단할 것.
NOMINAL_GLOSSES_SNAPSHOT = frozenset(
    {
        "가까워짐",
        "가려 정함",
        "가지고 있음",
        "갖춤",
        "갖춰 둠",
        "거둬들임",
        "거슬러 올라가 적용함",
        "계산에 넣음",
        "계산함",
        "계산해 냄",
        "계산해서 맞춤",
        "고쳐서 채움",
        "고침",
        "기간을 늘림",
        "깎아 줌",
        "끊음",
        "끝남",
        "끝냄",
        "나눠 정함",
        "나눠 줌",
        "나이가 많음",
        "내붙임",
        "내어 줌",
        "내지 않고 밀림",
        "내지 않음",
        "널리 나눠 줌",
        "넘음",
        "눈 내림",
        "다 냄",
        "다 들음",
        "다가옴",
        "달라고 함",
        "대신하는 사람",
        "도로 거둠",
        "도와줌",
        "돌려보냄",
        "드리지 않음",
        "따르지 않음",
        "따름",
        "만 해당함",
        "망가뜨림",
        "맡은 사람",
        "먹지 않음",
        "물에 잠김",
        "미룸",
        "바꿈",
        "바뀜",
        "바로잡음",
        "받음",
        "병원에 옴",
        "부탁함",
        "북돋움",
        "분명히 밝힘",
        "비 내림",
        "빌려 씀",
        "빌려 줌",
        "뺌",
        "뽑음",
        "살펴봄",
        "새로 고침",
        "생각함",
        "소용없음",
        "시작함",
        "신청한 사람",
        "실제로 함",
        "쓸 수 있음",
        "안 내도 됨",
        "어김",
        "얼어붙음",
        "우편으로 보냄",
        "이사 감",
        "이사 옴",
        "이어 줌",
        "이유를 밝힘",
        "일자리를 잃음",
        "잃어버림",
        "재촉함",
        "조심할 점",
        "지남",
        "지키지 않음",
        "진행함",
        "집에서 나감",
        "찾아봄",
        "필요한 일을 함",
        "함께 넣음",
        "해당하는 사람",
    }
)


@pytest.mark.parametrize(("sentence", "gloss"), OBSERVED_GLOSS_COLLISIONS)
def test_관찰된_치환_비문을_검출한다(sentence: str, gloss: str) -> None:
    assert gloss in find_gloss_collisions(sentence)


@pytest.mark.parametrize("sentence", NATURAL_TEXT_CORPUS)
def test_자연스러운_표현은_치환_비문으로_보지_않는다(sentence: str) -> None:
    assert find_gloss_collisions(sentence) == []


@pytest.mark.parametrize(
    ("label", "text", "expected"),
    [pytest.param(*sample, id=sample[0]) for sample in CONVERTED_SAMPLES],
)
def test_실제_변환_결과에서_검출이_정확하다(
    label: str, text: str, expected: tuple[str, ...]
) -> None:
    """오탐 증거의 주 코퍼스 — 검사 대상 표면인 '변환 결과물'로 재현율·정밀도를 함께 고정한다.

    기대값보다 많이 잡으면 오탐, 적게 잡으면 미검출이다. 픽스처 출처와 기대값 근거는
    tests/easyread/converted_samples.py 참고.
    """
    assert sorted(find_gloss_collisions(text)) == sorted(expected)


def test_골든셋_원문_전수에서_치환_비문_오탐이_없다() -> None:
    """보조 증거 — 원문에는 검사 표면이 거의 없어 이것만으로는 오탐 0을 말할 수 없다.

    실측(2026-08-09): 골든 56건 원문에 사전의 관형구 뜻풀이는 한 번도 등장하지 않고,
    명사형 뜻풀이도 극소수만 나온다. 원문은 아직 변환되지 않은 '어려운 글'이라 뜻풀이가
    끼워질 자리 자체가 없기 때문이다 — 그래서 **주 증거는 위의 변환 결과물 코퍼스**이고,
    이 테스트는 "원문을 건드리지는 않는다"만 확인하는 보조 장치다.
    """
    false_positives = {
        document.id: find_gloss_collisions(document.source_text)
        for document in load_documents(DOCUMENTS_DIR)
        if find_gloss_collisions(document.source_text)
    }
    assert false_positives == {}


def test_치환_비문은_한_자리를_한_건으로_센다() -> None:
    """'정해진 날'과 '정해진 날짜'가 겹쳐 두 건이 되면 보정 채택 판정이 왜곡된다."""
    assert find_gloss_collisions("사용 정해진 날짜가 지나면 사라집니다.") == ["정해진 날짜"]


def test_치환_비문은_check_style_위반으로_보고된다() -> None:
    """새 검사가 기존 보정 패스를 발동시키는 경로 — 신규 LLM 호출은 없다."""
    result = check_style("뽑음 결과를 알려 드립니다.")
    issue = next(issue for issue in result.issues if "뜻풀이" in issue.reason)
    assert issue.sentence == "뽑음 결과를 알려 드립니다."
    assert "자연스럽게 다시 쓸 것" in issue.reason
    # 처방이 사전값 치환이 아니라 재서술이므로 word를 채우지 않는다.
    assert issue.word is None


def test_낱말로_굳은_명사형_값은_검출_대상에서_빠진다() -> None:
    """제외 목록이 실제 사전 값이어야 한다 — 값이 바뀌면 목록도 함께 갱신해야 한다."""
    assert set(DIFFICULT_WORD_REPLACEMENTS.values()) >= LEXICALIZED_GLOSSES


def test_복합어_꼬리_키는_모두_사전에_있다() -> None:
    assert DIFFICULT_WORD_REPLACEMENTS.keys() >= COMPOUND_TAIL_KEYS


# 화이트리스트에 절대 들어오면 안 되는 부류 — 관형구를 자연스럽게 앞에서 꾸미는
# 부사·시간명사다. B-1 오탐(2026-08-09 리뷰)이 정확히 이 부류에서 나왔다.
FORBIDDEN_HEAD_WORDS = frozenset(
    {
        "매달",
        "매월",
        "매년",
        "매주",
        "미리",
        "이미",
        "다시",
        "따로",
        "아래",
        "올해",
        "내년",
        "작년",
        "오늘",
        "내일",
        "어제",
        "이번",
        "다음",
        "지난",
        "현재",
        "최근",
        "해마다",
        "그때",
    }
)


def test_복합어_앞자리_낱말은_선정_원칙을_지킨다() -> None:
    """패턴 ③의 주 방어선 — 목록이 무분별하게 늘면 오탐 표면이 그대로 넓어진다.

    원칙(style_rules.COMPOUND_HEAD_NOUNS 주석): '기한·기일·정액' 앞에 복합어로 붙는
    동작성 한자어 명사만 넣는다. 부사·시간명사는 절대 넣지 않는다.
    """
    assert "사용" in COMPOUND_HEAD_NOUNS
    # 한자어 명사는 두세 글자 순한글 표기다(공백·기호가 섞이면 낱말이 아니다).
    assert all(2 <= len(noun) <= 3 for noun in COMPOUND_HEAD_NOUNS), COMPOUND_HEAD_NOUNS
    assert all(noun.isalpha() and not noun.isascii() for noun in COMPOUND_HEAD_NOUNS)
    assert COMPOUND_HEAD_NOUNS.isdisjoint(FORBIDDEN_HEAD_WORDS)


@pytest.mark.parametrize("noun", sorted(COMPOUND_HEAD_NOUNS))
def test_복합어_앞자리_낱말은_모두_검출에_기여한다(noun: str) -> None:
    """열거했는데 잡히지 않는 낱말은 오탐 표면만 넓히고 재현율은 못 늘린다."""
    assert find_gloss_collisions(f"{noun} 정해진 날짜가 지났습니다.") == ["정해진 날짜"]


# 2026-08-09 리뷰가 지목한 재현율 공백 — 공공 안내문에 흔한 복합어들이다.
MISSED_COMPOUND_SLOTS = (
    "결제 정해진 날짜가 지나면 이용할 수 없습니다.",
    "환불 정해진 날짜를 확인하세요.",
    "신고 정해진 날짜입니다.",
    "가입 정해진 날짜",
    "입금 정해진 날짜",
    "심사 정해진 날짜",
)


@pytest.mark.parametrize("sentence", MISSED_COMPOUND_SLOTS)
def test_흔한_복합어_자리도_검출한다(sentence: str) -> None:
    assert find_gloss_collisions(sentence) == ["정해진 날짜"]


def test_사전이_지시한_표현을_비문으로_잡지_않는다() -> None:
    """'갱신 → 새로 고침'을 따른 결과가 '정정 → 고침'에 걸리면 사전이 자기모순이다."""
    assert DIFFICULT_WORD_REPLACEMENTS["갱신"] == "새로 고침"
    assert DIFFICULT_WORD_REPLACEMENTS["정정"] == "고침"
    assert find_gloss_collisions("새로 고침 안내를 보내 드립니다.") == []


def test_다른_뜻풀이의_꼬리인_값은_체언_수식_검사에서_빠진다() -> None:
    """사전이 쓰라고 한 긴 값의 끝부분이 짧은 값과 같으면 그 자리는 비문이 아니다."""
    values = set(DIFFICULT_WORD_REPLACEMENTS.values())
    tails = {
        value
        for value in values
        if " " not in value and any(other != value and other.endswith(value) for other in values)
    }
    assert tails, "꼬리 관계가 하나도 없으면 이 방어는 의미가 없다"
    assert tails.isdisjoint(MODIFIER_CHECKED_GLOSSES)


def test_명사형_뜻풀이_집합은_스냅샷으로_고정된다() -> None:
    """사전에 -ㅁ으로 끝나는 값이 새로 들어오면 검출 대상이 조용히 늘어난다.

    '잠·봄·힘·마음·그림·사람' 같은 값이 추가되면 그 즉시 자연 표현을 잡기 시작하므로,
    사전을 넓힐 때 이 테스트가 빨간불을 켜서 제외 여부를 판단하게 강제한다.
    """
    assert NOMINAL_GLOSSES == NOMINAL_GLOSSES_SNAPSHOT


@pytest.mark.parametrize(
    "sentence",
    [
        "사용  정해진 날짜가 지났습니다.",  # 연속 공백
        "사용 정해진 날짜가 지났습니다.",  # NBSP
        "사용　정해진 날짜가 지났습니다.",  # 전각 공백
    ],
)
def test_공백_변형에서도_검출한다(sentence: str) -> None:
    """hwpx·pdf 추출본에 흔한 공백이다 — 후처리가 정규화하지 않아 검사에서 새면 안 된다."""
    assert find_gloss_collisions(sentence) == ["정해진 날짜"]


def test_줄이_바뀌면_이어_읽지_않는다() -> None:
    """다른 줄은 다른 문장·다른 항목이다 — 붙여 읽으면 오탐이 된다."""
    assert find_gloss_collisions("신청을 받음\n보조기기 안내") == []
