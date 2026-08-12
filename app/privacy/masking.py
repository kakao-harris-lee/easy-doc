"""개인정보 마스킹 파이프라인.

보안 불변식 (master-plan 3.2): 사용자 문서 텍스트는 이 모듈의 mask_text()를
통과한 뒤에만 LLMProvider로 전달할 수 있다. 이 순서를 우회하는 코드는 금지.
**범주가 2종으로 좁아졌다고 해서 이 순서가 느슨해지지는 않는다** — 두 종만 걸러도
반드시 마스킹 후 전달이다.
원문-플레이스홀더 대응은 검수 화면 표시용으로만 쓰고 외부로 내보내지 않는다.
담기는 값이 고유식별정보·카드번호로 좁아졌으므로 반출 금지의 강도는 오히려 높아졌다.
"""

import re
from enum import StrEnum

from pydantic import BaseModel, SecretStr


class MaskCategory(StrEnum):
    """마스킹 대상 개인정보 분류 — 주민등록번호(외국인등록번호 포함)·카드번호 2종.

    값 자체가 자리표시자에 그대로 박히므로(`[[주민등록번호1]]`) 표기가 아니라
    **복원 키**다. 계약(`contracts/easy-doc-v1.yaml`)이 이 한국어 문자열을
    `MaskedItemResponse.category`의 enum으로 못박았다 — 영문 코드로 바꾸면 계약 위반이다.
    """

    RRN = "주민등록번호"
    CARD = "카드번호"


# ── 범주에서 뺀 것: 전화번호·이메일·계좌번호 (2026-08-12, master-plan 3.2) ──────────
#
# **이 셋은 문서에 섞여 들어오면 마스킹 없이 그대로 LLM(국외 포함)으로 전송된다.**
# 나중에 채워 넣으려고 비워 둔 자리가 아니라, 명시적으로 감수하기로 한 대가다.
#
# 축소 근거: 주 용도가 공공기관 안내문 같은 공용 배포 문서의 변환이라 실제 개인정보
# 유입 확률이 낮다고 보고, 런타임 교체 국면에서 포팅·검증 비용을 줄여 개발 속도를 택했다.
# 다만 "확률이 낮다"는 것은 확률에 대한 판단이지 유입 가능성이 없다는 뜻이 아니다 —
# 담당자가 개인 연락처가 든 문서를 올리면 기관 대표번호든 개인 휴대폰 번호든, 부서
# 메일이든 개인 메일이든, 환급 계좌번호든 그대로 외부 모델에 전달된다.
#
# "누락보다 과잉 마스킹이 안전하다"는 옛 원칙은 남은 2종에만 적용된다. 뺀 3종을 다시
# 잡도록 패턴을 넓히면 그것은 개선이 아니라 정책 위반이고, parity 게이트의
# `masking-scope-out-*` 케이스가 막는다.
#
# 재확대 조건(master-plan 3.2): 파일럿·운영에서 이 셋의 실제 유입이 확인되거나
# B2G 계약·CSAP 심사에서 범주가 문제되면 즉시 재확대한다(4.1 P0-6). 문서만 넓게 적고
# 구현을 두는 방식은 금지다.
#
# 축소 전 정규식은 `docs/golden-collection-plan.md` §4.3이 골든셋 수집 단계의 연락처
# 육안 검출용으로 인라인 보존한다 — 코드에서 사라진 판정 기준을 절차 문서로 옮긴 것이다.
# ──────────────────────────────────────────────────────────────────────────────────

# 우선순위 순서(먼저 매칭된 구간이 이후 패턴보다 우선).
# RRN 성별코드는 [1-8]: 5~8은 외국인등록번호(고유식별정보). 구분자 없는 표기도 커버.
# RRN이 CARD보다 앞이다 — 13자리와 16자리는 lookaround로 갈리지만, 겹치는 표기가
# 생기면 더 민감한 고유식별정보 쪽으로 판정되는 편이 안전하다.
_PATTERNS: tuple[tuple[MaskCategory, re.Pattern[str]], ...] = (
    (MaskCategory.RRN, re.compile(r"(?<!\d)\d{6}[ \t]*-?[ \t]*[1-8]\d{6}(?!\d)")),
    (MaskCategory.CARD, re.compile(r"(?<!\d)\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}(?!\d)")),
)


# ── 보이지 않는 문자로 인한 마스킹 회피 차단 ────────────────────────────────────────
#
# 숫자 사이에 폭 없는 문자나 제어문자가 끼면 위 정규식이 그대로 뚫린다
# (`docs/migration/_workspace/02_privacy-gate_control-char-verdict.md`).
# 악의적 회피가 아니라 **사고성 유입**이 주 경로다 — 실제 정부 문서 코퍼스에서
# 소프트하이픈(U+00AD)·NUL이 하이픈 자리를 대신하고 있는 사례가 실측됐고, PDF 추출과
# JSON 붙여넣기 경로에는 이를 걸러 주는 것이 아무것도 없다. 피해자는 문서에 등장하는
# 제3자 시민이고, 누락은 조용해서 담당자도 알아채지 못한다.
#
# **파이프라인 앞단에 정규화 단계를 넣지 않는다.** 입력 자체를 정규화해 넘기면 복원이
# 깨져 내보내기가 잘못된 원문을 꽂는다(parity `restores_input`). 대신 이 함수 **내부**에서
# 탐색용 뷰를 만들어 거기서 찾고, 자르기는 원문 좌표로 한다 — 파이프라인 순서를 건드리지
# 않으므로 마스킹 선행 불변식(CLAUDE.md 아키텍처 규칙 2)도 그대로다.
#
# 대상은 "제어문자"가 아니라 **숫자 사이에서 보이지 않는 것 전체**다. 탭·개행·일반 공백은
# 뺀다 — 정규식이 `[ \t]`로 이미 정식 처리하고, 개행까지 접으면 서로 다른 줄의 숫자열이
# 붙어 진짜 과잉 마스킹이 된다.
_INVISIBLE_RANGES: tuple[tuple[int, int], ...] = (
    (0x0000, 0x0008),  # C0 (탭 U+0009·개행 U+000A 제외)
    (0x000B, 0x000C),
    (0x000E, 0x001F),  # 캐리지리턴 U+000D 제외
    (0x007F, 0x007F),  # DEL
    (0x00AD, 0x00AD),  # 소프트하이픈 — 실문서에서 실제로 검출된 것
    (0x200B, 0x200F),  # 폭 없는 공백·비연결자·방향 표시
    (0x202A, 0x202E),  # 방향 재정의
    (0x2060, 0x2060),  # word joiner
    (0xFEFF, 0xFEFF),  # BOM / zero-width no-break space
)
_INVISIBLE = frozenset(
    chr(code) for start, end in _INVISIBLE_RANGES for code in range(start, end + 1)
)
_INVISIBLE_RE = re.compile(
    "[" + "".join(f"\\u{start:04x}-\\u{end:04x}" for start, end in _INVISIBLE_RANGES) + "]"
)


def _search_view(text: str) -> tuple[str, list[int] | None]:
    """보이지 않는 문자를 뺀 탐색용 뷰와 뷰 인덱스 → 원문 인덱스 대응표를 만든다.

    뺄 것이 없으면 대응표를 만들지 않고 None을 돌려준다 — 흔한 경우의 비용을 없애고,
    호출부가 "뷰 경로를 탈 필요가 있는가"를 그 값으로 판정한다.
    """
    view = _INVISIBLE_RE.sub("", text)
    if len(view) == len(text):
        return text, None
    return view, [index for index, char in enumerate(text) if char not in _INVISIBLE]


def _candidate_spans(
    pattern: re.Pattern[str], text: str, view: str, offsets: list[int] | None
) -> list[tuple[int, int]]:
    """원문 직접 매칭 + 뷰 매칭(원문 좌표로 환원)의 합집합을 돌려준다.

    합집합인 이유: 뷰만 쓰면 지금 잡히는 것을 놓칠 수 있다. 예컨대 `1<ZWSP>900101-1234567`은
    원문에서 `(?<!\\d)`가 성립해 매칭되지만, 뷰에서는 앞 숫자가 붙어 버려 성립하지 않는다.
    합집합은 현행 적중을 보존한 채 회피 경로만 더한다.

    스팬은 시작 오름차순, 같은 시작이면 긴 것 우선으로 준다 — 낀 문자를 포함한 넓은 쪽이
    이겨야 원문에 조각이 남지 않는다.
    """
    spans = {match.span() for match in pattern.finditer(text)}
    if offsets is not None:
        for match in pattern.finditer(view):
            start, end = match.span()
            # 끝 좌표는 마지막으로 **매칭된 문자**의 원문 인덱스 + 1이다. offsets[end]를
            # 쓰면 매치 뒤에 붙은 보이지 않는 문자까지 삼켜 경계가 과잉 잠식된다.
            spans.add((offsets[start], offsets[end - 1] + 1))
    return sorted(spans, key=lambda span: (span[0], -span[1]))


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
    """우선순위 패턴 순서로 개인정보를 찾아 플레이스홀더로 치환한다.

    탐색은 보이지 않는 문자를 뺀 뷰에서도 함께 하지만(`_candidate_spans`), 잘라내는 것은
    언제나 원문이다. 따라서 `original`에는 낀 문자가 그대로 들어가고, 자리표시자를
    되돌리면 입력이 정확히 복원된다.
    """
    view, offsets = _search_view(text)
    spans: list[tuple[int, int, MaskCategory]] = []
    for category, pattern in _PATTERNS:
        for start, end in _candidate_spans(pattern, text, view, offsets):
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
