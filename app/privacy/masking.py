"""개인정보 마스킹 파이프라인.

보안 불변식 (master-plan 3.2): 사용자 문서 텍스트는 이 모듈의 mask_text()를
통과한 뒤에만 LLMProvider로 전달할 수 있다. 이 순서를 우회하는 코드는 금지.
원문-플레이스홀더 대응은 검수 화면 표시용으로만 쓰고 외부로 내보내지 않는다.
"""

import re
from enum import StrEnum

from pydantic import BaseModel


class MaskCategory(StrEnum):
    """마스킹 대상 개인정보 분류."""

    RRN = "주민등록번호"
    CARD = "카드번호"
    PHONE = "전화번호"
    EMAIL = "이메일"
    ACCOUNT = "계좌번호"


# 우선순위 순서(먼저 매칭된 구간이 이후 패턴보다 우선)
_PATTERNS: tuple[tuple[MaskCategory, re.Pattern[str]], ...] = (
    (MaskCategory.RRN, re.compile(r"(?<!\d)\d{6}\s*-\s*[1-4]\d{6}(?!\d)")),
    (MaskCategory.CARD, re.compile(r"(?<!\d)\d{4}[- ]\d{4}[- ]\d{4}[- ]\d{4}(?!\d)")),
    # EMAIL은 PHONE보다 먼저 검사한다: "hong01012345678@naver.com"처럼 지역부에
    # 전화번호가 섞인 주소에서 전화번호만 치환되면 도메인이 그대로 남기 때문이다.
    (MaskCategory.EMAIL, re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")),
    (
        MaskCategory.PHONE,
        re.compile(r"(?<!\d)(?:01[016789]|02|0[3-6]\d)[-.\s]?\d{3,4}[-.\s]?\d{4}(?!\d)"),
    ),
    (MaskCategory.ACCOUNT, re.compile(r"(?<!\d)\d{3,6}-\d{2,6}-\d{4,8}(?!\d)")),
)


class MaskedItem(BaseModel):
    """마스킹된 개별 항목 (검수 화면 표시용)."""

    category: MaskCategory
    placeholder: str
    original: str


class MaskingResult(BaseModel):
    """마스킹 결과."""

    masked_text: str
    items: list[MaskedItem]


def mask_text(text: str) -> MaskingResult:
    """우선순위 패턴 순서로 개인정보를 찾아 플레이스홀더로 치환한다."""
    spans: list[tuple[int, int, MaskCategory]] = []
    for category, pattern in _PATTERNS:
        for match in pattern.finditer(text):
            start, end = match.span()
            if any(start < e and s < end for s, e, _ in spans):
                continue  # 이미 다른 패턴이 차지한 구간
            spans.append((start, end, category))

    spans.sort()
    counters: dict[MaskCategory, int] = {}
    items: list[MaskedItem] = []
    result_parts: list[str] = []
    cursor = 0
    for start, end, category in spans:
        counters[category] = counters.get(category, 0) + 1
        placeholder = f"[[{category.value}{counters[category]}]]"
        items.append(
            MaskedItem(category=category, placeholder=placeholder, original=text[start:end])
        )
        result_parts.append(text[cursor:start])
        result_parts.append(placeholder)
        cursor = end
    result_parts.append(text[cursor:])
    return MaskingResult(masked_text="".join(result_parts), items=items)
