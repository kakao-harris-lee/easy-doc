"""개인정보 마스킹 파이프라인.

보안 불변식 (master-plan 3.2): 사용자 문서 텍스트는 이 모듈의 mask_text()를
통과한 뒤에만 LLMProvider로 전달할 수 있다. 이 순서를 우회하는 코드는 금지.
원문-플레이스홀더 대응은 검수 화면 표시용으로만 쓰고 외부로 내보내지 않는다.
"""

import re
from enum import StrEnum

from pydantic import BaseModel, SecretStr


class MaskCategory(StrEnum):
    """마스킹 대상 개인정보 분류."""

    RRN = "주민등록번호"
    CARD = "카드번호"
    PHONE = "전화번호"
    EMAIL = "이메일"
    ACCOUNT = "계좌번호"


# 우선순위 순서(먼저 매칭된 구간이 이후 패턴보다 우선).
# EMAIL은 PHONE보다 앞: 이메일 지역부 숫자열이 부분 마스킹되어 도메인이 남는 것을 방지.
# RRN 성별코드는 [1-8]: 5~8은 외국인등록번호(고유식별정보). 구분자 없는 표기도 커버.
_PATTERNS: tuple[tuple[MaskCategory, re.Pattern[str]], ...] = (
    (MaskCategory.RRN, re.compile(r"(?<!\d)\d{6}[ \t]*-?[ \t]*[1-8]\d{6}(?!\d)")),
    (MaskCategory.CARD, re.compile(r"(?<!\d)\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}(?!\d)")),
    (MaskCategory.EMAIL, re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")),
    (
        MaskCategory.PHONE,
        re.compile(
            r"(?<!\d)(?:01[016789]|02|0[3-6]\d|070|080|050\d)[-.\s]?\d{3,4}[-.\s]?\d{4}(?!\d)"
        ),
    ),
    (MaskCategory.ACCOUNT, re.compile(r"(?<!\d)\d{3,6}-\d{2,6}-\d{4,8}(?!\d)")),
)


class MaskedItem(BaseModel):
    """마스킹된 개별 항목 (검수 화면 표시용)."""

    category: MaskCategory
    placeholder: str
    # SecretStr: repr/model_dump에서 자동 마스킹되어 원문이 로그로 새는 경로를 차단한다
    # (app/config.py와 동일 패턴). 실제 값이 필요하면 .get_secret_value()로 꺼낸다.
    original: SecretStr


class MaskingResult(BaseModel):
    """마스킹 결과.

    items에 원문 개인정보가 담기므로 API response_model로 직접 사용하지 않는다.
    외부로 내보낼 때는 masked_text만 노출하는 별도 스키마를 만들 것.
    """

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
            MaskedItem(
                category=category,
                placeholder=placeholder,
                original=SecretStr(text[start:end]),
            )
        )
        result_parts.append(text[cursor:start])
        result_parts.append(placeholder)
        cursor = end
    result_parts.append(text[cursor:])
    return MaskingResult(masked_text="".join(result_parts), items=items)
