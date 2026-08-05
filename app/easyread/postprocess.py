"""LLM 변환 응답 후처리 — 모델이 덧붙인 껍데기를 벗기고 본문만 남긴다.

프롬프트로 금지해도 모델이 코드 펜스·머리말을 붙이는 경우가 있어 방어적으로 제거한다.
본문을 잘못 지우는 쪽이 껍데기를 남기는 쪽보다 위험하므로 제거 조건을 좁게 잡는다.
"""

import re

# 앞뒤 마크다운 코드 펜스. 여는 쪽은 ```text·```markdown 등 언어 태그를 허용한다.
_FENCE_OPEN = re.compile(r"\A```[^\n]*\n?")
_FENCE_CLOSE = re.compile(r"\n?```[ \t]*\Z")

# 머리말 판정: '다음은/아래는'으로 시작 + 콜론으로 끝나거나 '변환'·'결과'를 포함.
# 본문 첫 문장이 우연히 '다음은 ~입니다'인 경우를 지우지 않기 위한 이중 조건이다.
_PREAMBLE_START = re.compile(r"^(?:다음은|아래는)")
_PREAMBLE_SIGNAL = re.compile(r":\s*$|변환|결과")


def _is_preamble(line: str) -> bool:
    """첫 줄이 변환 결과를 소개하는 머리말인지 판정한다."""
    return bool(_PREAMBLE_START.match(line) and _PREAMBLE_SIGNAL.search(line))


def postprocess(raw: str) -> str:
    """공백·코드 펜스·머리말을 제거한 본문을 돌려준다."""
    text = _FENCE_CLOSE.sub("", _FENCE_OPEN.sub("", raw.strip())).strip()
    first_line, separator, rest = text.partition("\n")
    # separator가 없으면 머리말 뒤에 본문이 없다는 뜻 — 전부 날리지 않고 원문을 유지한다.
    if separator and _is_preamble(first_line.strip()):
        return rest.strip()
    return text
