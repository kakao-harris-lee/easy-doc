"""LLM 변환 응답 후처리 — 모델이 덧붙인 껍데기를 벗기고 본문만 남긴다.

프롬프트로 금지해도 모델이 코드 펜스·머리말을 붙이는 경우가 있어 방어적으로 제거한다.
본문을 잘못 지우는 쪽이 껍데기를 남기는 쪽보다 위험하므로 제거 조건을 좁게 잡는다.
꼬리말은 과잉 제거 위험이 커서 제거하지 않는다(HITL 검수에서 걸러짐).
"""

import re

# 앞뒤 마크다운 코드 펜스. 여는 쪽은 ```text·```markdown 등 언어 태그를 허용한다.
_FENCE_OPEN = re.compile(r"\A```[^\n]*\n?")
_FENCE_CLOSE = re.compile(r"\n?```[ \t]*\Z")

# 머리말 판정: '다음은/아래는'으로 시작 + 콜론으로 끝나거나 변환 결과를 가리키는 어구 포함.
# 신호를 '변환 결과'·'바꾼 결과'·'쉬운 글'로 좁힌다 — '결과' 부분 문자열만 보면
# "다음은 심사 결과입니다." 같은 정상 본문 첫 문장까지 지운다.
_PREAMBLE_START = re.compile(r"^(?:다음은|아래는)")
_PREAMBLE_SIGNAL = re.compile(r":\s*$|변환\s*결과|바꾼\s*결과|쉬운\s*글")


def _strip_fences(text: str) -> str:
    """앞뒤 마크다운 코드 펜스를 한 겹 벗긴다."""
    return _FENCE_CLOSE.sub("", _FENCE_OPEN.sub("", text)).strip()


def _strip_preamble(text: str) -> str:
    """첫 줄이 변환 결과를 소개하는 머리말이면 그 한 줄만 제거한다."""
    first_line, separator, rest = text.partition("\n")
    head = first_line.strip()
    # separator가 없으면 머리말 뒤에 본문이 없다는 뜻 — 전부 날리지 않고 원문을 유지한다.
    if separator and _PREAMBLE_START.match(head) and _PREAMBLE_SIGNAL.search(head):
        return rest.strip()
    return text


def postprocess(raw: str) -> str:
    """공백·코드 펜스·머리말을 제거한 본문을 돌려준다."""
    text = raw.strip()
    for _ in range(2):  # 펜스↔머리말 순서가 뒤바뀐 경우까지 흡수
        text = _strip_preamble(_strip_fences(text))
    return text
