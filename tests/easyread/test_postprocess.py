"""LLM 변환 응답 후처리 테스트 — 과잉 제거 방지가 핵심."""

import pytest

from app.easyread.postprocess import postprocess


def test_앞뒤_공백을_제거한다() -> None:
    assert postprocess("  \n오늘 서류를 내세요.\n\n  ") == "오늘 서류를 내세요."


@pytest.mark.parametrize(
    "raw",
    [
        "```\n오늘 서류를 내세요.\n```",
        "```text\n오늘 서류를 내세요.\n```",
        "```markdown\n오늘 서류를 내세요.\n```",
    ],
)
def test_마크다운_코드_펜스를_제거한다(raw: str) -> None:
    assert postprocess(raw) == "오늘 서류를 내세요."


def test_변환_결과_머리말_한_줄을_제거한다() -> None:
    raw = "다음은 쉬운 글로 바꾼 결과입니다.\n\n오늘 서류를 내세요."
    assert postprocess(raw) == "오늘 서류를 내세요."


def test_콜론으로_끝나는_머리말도_제거한다() -> None:
    raw = "아래는 안내문입니다:\n오늘 서류를 내세요."
    assert postprocess(raw) == "오늘 서류를 내세요."


def test_머리말처럼_보이는_본문_첫_문장은_지우지_않는다() -> None:
    """'다음은'으로 시작해도 콜론·'변환'·'결과' 신호가 없으면 본문이다."""
    raw = "다음은 신청 방법입니다.\n주민센터에 방문하세요."
    assert postprocess(raw) == raw


def test_평범한_본문은_손대지_않는다() -> None:
    raw = "오늘 서류를 내세요.\n주민센터에 방문하세요."
    assert postprocess(raw) == raw


def test_머리말만_있고_본문이_없으면_지우지_않는다() -> None:
    """본문 전체를 날려 빈 문자열을 반환하는 사고를 막는다."""
    raw = "다음은 변환 결과입니다."
    assert postprocess(raw) == raw


def test_펜스와_머리말이_함께_있어도_둘_다_제거한다() -> None:
    raw = "```text\n다음은 변환 결과입니다:\n오늘 서류를 내세요.\n```"
    assert postprocess(raw) == "오늘 서류를 내세요."


@pytest.mark.parametrize(
    "raw",
    [
        "다음은 변환 결과입니다:\n```\n오늘 서류를 내세요.\n```",
        "다음은 변환 결과입니다:\n```text\n오늘 서류를 내세요.\n```",
    ],
)
def test_머리말이_펜스보다_앞서도_둘_다_제거한다(raw: str) -> None:
    """머리말→펜스 순서에서 여는 펜스가 남던 결함(I-1) 재현."""
    assert postprocess(raw) == "오늘 서류를 내세요."


def test_심사_결과_같은_정상_첫_문장은_지우지_않는다() -> None:
    """'결과' 부분 문자열만으로 본문을 삭제하던 과잉 제거(I-2) 재현."""
    raw = "다음은 심사 결과입니다.\n결과는 문자로 알려 드립니다."
    assert postprocess(raw) == raw
