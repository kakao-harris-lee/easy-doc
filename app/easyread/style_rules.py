"""쉬운 글 스타일 규칙 — 단일 정의(SSOT).

프롬프트 생성(app/easyread/prompts.py)과 골든셋 평가(tests/golden)가
반드시 이 모듈의 상수·함수를 공유한다 (CLAUDE.md 아키텍처 규칙 4).
근거: 국립국어원 쉬운 글쓰기 지침, 보건복지부 가이드라인,
서울시 읽기쉬운자료개발센터('알다') 제작 원칙.
"""

import re

from pydantic import BaseModel

MAX_SENTENCE_CHARS = 50
MAX_COMMAS_PER_SENTENCE = 2

# 이중 피동 등 피해야 할 서술 패턴
DOUBLE_PASSIVE_PATTERNS: tuple[str, ...] = ("되어지", "보여지", "쓰여지", "믿겨지", "잊혀지")

# 어려운 한자어·행정 용어 → 쉬운 표현 (시드 목록, 지속 확장)
DIFFICULT_WORD_REPLACEMENTS: dict[str, str] = {
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

STYLE_PRINCIPLES: tuple[str, ...] = (
    "한 문장에는 정보를 하나만 담는다.",
    f"문장은 {MAX_SENTENCE_CHARS}자를 넘기지 않는다.",
    "어려운 한자어·행정 용어는 쉬운 말로 바꾼다.",
    "능동태로 쓰고 이중 피동(예: '되어지다')을 쓰지 않는다.",
    "날짜·금액·연락처·신청 방법 등 중요한 정보는 빠뜨리지 않는다.",
    "존댓말로 부드럽게 설명한다.",
)

_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+")

# 앞에 한글이 붙으면 다른 낱말이 되는 표현(下記/上記).
# "신청하기"·"이상기후"처럼 흔한 어절을 어려운 표현으로 오탐하지 않도록
# 어절 첫머리(앞 글자가 한글이 아닐 때)에서만 검출한다.
_PREFIX_SENSITIVE_WORDS: frozenset[str] = frozenset({"상기", "하기"})
_HANGUL_SYLLABLE = re.compile(r"[가-힣]")


class SentenceIssue(BaseModel):
    """규칙 위반 문장과 사유."""

    sentence: str
    reason: str


class StyleCheckResult(BaseModel):
    """규칙 기반 검사 결과."""

    total_sentences: int
    issues: list[SentenceIssue]

    @property
    def passed(self) -> bool:
        return not self.issues


def split_sentences(text: str) -> list[str]:
    """마침표·물음표·느낌표·줄바꿈 기준의 단순 문장 분리."""
    return [s.strip() for s in _SENTENCE_SPLIT.split(text) if s.strip()]


def _contains_difficult_word(text: str, word: str) -> bool:
    """어려운 표현이 본문에 남아 있는지 판단한다.

    오탐 방지 대상(_PREFIX_SENSITIVE_WORDS)은 앞 글자가 한글이 아닌 위치,
    즉 어절 첫머리에 나타날 때만 검출한다.
    """
    if word not in _PREFIX_SENSITIVE_WORDS:
        return word in text
    start = text.find(word)
    while start != -1:
        if start == 0 or not _HANGUL_SYLLABLE.match(text[start - 1]):
            return True
        start = text.find(word, start + 1)
    return False


def find_difficult_words(text: str) -> list[str]:
    """치환 목록에 있는 어려운 표현 중 본문에 남아 있는 것을 찾는다."""
    return [word for word in DIFFICULT_WORD_REPLACEMENTS if _contains_difficult_word(text, word)]


def check_style(text: str) -> StyleCheckResult:
    """문장 길이·쉼표 수·이중 피동·어려운 표현을 검사한다."""
    sentences = split_sentences(text)
    issues: list[SentenceIssue] = []
    for sentence in sentences:
        if len(sentence) > MAX_SENTENCE_CHARS:
            issues.append(SentenceIssue(sentence=sentence, reason="문장 길이 초과"))
        if sentence.count(",") > MAX_COMMAS_PER_SENTENCE:
            issues.append(
                SentenceIssue(sentence=sentence, reason="쉼표 과다(한 문장 한 정보 위반 의심)")
            )
        for pattern in DOUBLE_PASSIVE_PATTERNS:
            if pattern in sentence:
                issues.append(SentenceIssue(sentence=sentence, reason=f"이중 피동 표현({pattern})"))
    for word in find_difficult_words(text):
        issues.append(SentenceIssue(sentence=word, reason=f"어려운 표현 잔존({word})"))
    return StyleCheckResult(total_sentences=len(sentences), issues=issues)
