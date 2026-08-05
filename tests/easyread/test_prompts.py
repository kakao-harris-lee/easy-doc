"""변환 프롬프트 생성 테스트 — 스타일 규칙 SSOT 순회 생성 여부가 핵심."""

from app.easyread.prompts import (
    DOCUMENT_CLOSE_TAG,
    DOCUMENT_OPEN_TAG,
    build_system_prompt,
    build_user_prompt,
)
from app.easyread.style_rules import DIFFICULT_WORD_REPLACEMENTS, STYLE_PRINCIPLES


def test_역할_정의가_포함된다() -> None:
    prompt = build_system_prompt()
    assert "공공기관" in prompt
    assert "정보소외계층" in prompt


def test_스타일_원칙_전체가_포함된다() -> None:
    """SSOT를 순회 생성해야 한다 — 원칙을 추가하면 프롬프트에 자동 반영된다."""
    prompt = build_system_prompt()
    assert STYLE_PRINCIPLES[0] in prompt
    for principle in STYLE_PRINCIPLES:
        assert principle in prompt


def test_치환_목록_전체가_포함된다() -> None:
    """하드코딩 목록이 아니라 DIFFICULT_WORD_REPLACEMENTS 순회 결과여야 한다."""
    prompt = build_system_prompt()
    assert "금일" in prompt
    assert "오늘" in prompt
    for difficult, easy in DIFFICULT_WORD_REPLACEMENTS.items():
        assert f"{difficult} → {easy}" in prompt


def test_플레이스홀더_보존_지시가_포함된다() -> None:
    prompt = build_system_prompt()
    assert "[[" in prompt
    assert "]]" in prompt
    assert "[[전화번호1]]" in prompt
    assert "그대로 유지" in prompt


def test_출력_형식_지시가_포함된다() -> None:
    prompt = build_system_prompt()
    assert "본문만 출력" in prompt
    assert "```" in prompt


def test_유저_프롬프트는_원문을_구분자로_감싼다() -> None:
    masked = "신청 문의는 [[전화번호1]]로 해 주세요."
    prompt = build_user_prompt(masked)
    assert masked in prompt
    assert prompt.index(DOCUMENT_OPEN_TAG) < prompt.index(masked)
    assert prompt.index(masked) < prompt.index(DOCUMENT_CLOSE_TAG)
    assert "쉬운 글로 바꿔" in prompt
