"""쉬운 글 변환 프롬프트 생성.

규칙 목록·치환 목록은 style_rules.py(SSOT)를 순회해 만든다 — 프롬프트에
규칙을 하드코딩하면 골든셋 평가와 기준이 갈라진다 (CLAUDE.md 아키텍처 규칙 4).
입력은 이미 마스킹된 텍스트여야 한다 (app/privacy/masking.py 선행).
"""

from app.easyread.style_rules import DIFFICULT_WORD_REPLACEMENTS, STYLE_PRINCIPLES

# 원문 구간을 명확히 구분해 지시문과 섞이지 않게 한다(프롬프트 인젝션 완화 겸용).
DOCUMENT_OPEN_TAG = "<문서>"
DOCUMENT_CLOSE_TAG = "</문서>"

_ROLE = (
    "당신은 공공기관 안내문을 발달장애인 등 정보소외계층이 이해하기 쉬운 글로 바꾸는 전문가입니다."
)

# 마스킹 플레이스홀더가 변형되면 검수 화면에서 원문 복원이 깨진다.
PLACEHOLDER_INSTRUCTION = (
    "`[[`와 `]]`로 감싸인 표시(예: [[전화번호1]])는 개인정보 자리표시자입니다. "
    "글자 하나 바꾸지 말고 그대로 유지하세요. "
    "자리표시자를 지우거나 다른 말로 풀어 쓰면 안 됩니다."
)

_OUTPUT_INSTRUCTION = (
    "변환한 본문만 출력하세요. "
    "'다음은 ~입니다' 같은 머리말, 설명, 마크다운 코드 펜스(```)를 붙이지 마세요."
)


def build_system_prompt() -> str:
    """스타일 규칙 SSOT를 순회해 시스템 프롬프트를 생성한다."""
    rules = "\n".join(
        f"{index}. {principle}" for index, principle in enumerate(STYLE_PRINCIPLES, start=1)
    )
    replacements = "\n".join(
        f"- {difficult} → {easy}" for difficult, easy in DIFFICULT_WORD_REPLACEMENTS.items()
    )
    return (
        f"{_ROLE}\n\n"
        f"[변환 규칙]\n{rules}\n\n"
        f"[어려운 표현 바꾸기]\n왼쪽 표현이 나오면 오른쪽 표현으로 바꾸세요.\n{replacements}\n\n"
        f"[개인정보 표시]\n{PLACEHOLDER_INSTRUCTION}\n\n"
        f"[출력 형식]\n{_OUTPUT_INSTRUCTION}"
    )


def build_user_prompt(masked_text: str) -> str:
    """마스킹된 원문을 구분자로 감싸 변환을 지시한다."""
    return (
        f"{DOCUMENT_OPEN_TAG}\n{masked_text}\n{DOCUMENT_CLOSE_TAG}\n\n"
        "위 문서를 쉬운 글로 바꿔 주세요."
    )
