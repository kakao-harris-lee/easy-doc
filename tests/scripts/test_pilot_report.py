"""파일럿 KPI 리포트(scripts/pilot_report.py)의 순수 로직 테스트.

DB에 붙지 않는다 — 기록(`ConversionRecord`)을 직접 만들어 수정률 계산·집계·표
렌더링만 본다. 여기서 지키려는 것이 하나 더 있다: **리포트 문자열에 본문이 실리지
않는 것**. 기록 구조체가 애초에 본문을 들고 있지 않다는 사실을, 사람이 아니라 기계가
지키게 한다.

본문·수정본은 전부 이 파일에서 만든 예시 문장이다.
"""

import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.models.conversion import ConversionStatus
from scripts.pilot_report import (
    MISSING,
    ConversionRecord,
    edit_ratio,
    render_report,
    summarize,
)

_CREATED = datetime(2026, 8, 8, 1, 0, tzinfo=UTC)


def _record(
    *,
    status: str = ConversionStatus.DONE,
    convert_seconds: float = 30,
    review_minutes: float | None = None,
    edit_ratio_value: float | None = None,
    failure_code: str | None = None,
    char_count: int = 1_200,
) -> ConversionRecord:
    """리포트 한 줄의 원천 값. 필요한 것만 덮어써서 의도를 드러낸다."""
    return ConversionRecord(
        document_id=uuid.uuid4(),
        char_count=char_count,
        status=status,
        created_at=_CREATED,
        updated_at=_CREATED + timedelta(seconds=convert_seconds),
        reviewed_at=(
            None if review_minutes is None else _CREATED + timedelta(minutes=review_minutes)
        ),
        edit_ratio=edit_ratio_value,
        failure_code=failure_code,
    )


# --- 수정률 --------------------------------------------------------------------


def test_손대지_않은_검수본의_수정률은_0이다() -> None:
    """초안을 그대로 쓴 경우다 — KPI 목표(추세 하락)의 도달점."""
    draft = "신청은 3월 2일부터 할 수 있어요."

    assert edit_ratio(draft, draft) == 0.0


def test_전부_새로_쓰면_수정률이_크게_오른다() -> None:
    """1.0에 정확히 닿지는 않는다 — 공백·조사처럼 어떤 한국어 문장에도 겹치는 문자가
    남기 때문이다. 잰 값이 절대 거리가 아니라 **추세**라는 것이 이 지표의 성격이다.
    """
    draft = "신청은 3월 2일부터 할 수 있어요."

    rewritten = edit_ratio(draft, "완전히 다른 문장을 새로 적었습니다")
    tweaked = edit_ratio(draft, "신청은 3월 2일부터 할 수 있습니다.")

    assert rewritten > 0.7
    # 많이 고친 쪽이 반드시 더 큰 값이어야 추세를 읽을 수 있다.
    assert rewritten > tweaked


def test_조금_고치면_수정률도_조금이다() -> None:
    """한국어는 조사·어미 한 글자만 바뀌어도 형태소가 갈린다 — 문자 단위로 잰다."""
    ratio = edit_ratio("신청은 3월 2일부터 할 수 있어요.", "신청은 3월 2일부터 할 수 있습니다.")

    assert 0.0 < ratio < 0.2


@pytest.mark.parametrize(
    ("draft", "edited", "expected"),
    [("", "", 0.0), ("", "사람이 처음부터 쓴 글", 1.0)],
)
def test_초안이_비어_있는_경우를_가른다(draft: str, edited: str, expected: float) -> None:
    """비교 기준이 없는 상태다 — 0으로 나누지 않고 수정본 유무로 가른다."""
    assert edit_ratio(draft, edited) == expected


def test_수정률은_0과_1_사이에_머문다() -> None:
    """리포트가 백분율로 찍으므로 범위를 벗어나면 표가 말이 되지 않는다."""
    ratio = edit_ratio("짧은 글", "아주 길게 늘여 쓴 완전히 다른 안내문입니다" * 10)

    assert 0.0 <= ratio <= 1.0


# --- 집계 ---------------------------------------------------------------------


def test_상태별_건수와_실패를_센다() -> None:
    records = [
        _record(),
        _record(status=ConversionStatus.FAILED, failure_code="LLMEmptyResultError"),
        _record(review_minutes=10, edit_ratio_value=0.2),
    ]

    summary = summarize(records)

    assert (summary.total, summary.done, summary.failed, summary.reviewed) == (3, 2, 1, 1)


def test_소요_시간은_중앙값으로_모은다() -> None:
    """재시도로 튄 한 건이 평균을 통째로 흔들지 않게 한다."""
    records = [
        _record(convert_seconds=10),
        _record(convert_seconds=20),
        _record(convert_seconds=6_000),
    ]

    assert summarize(records).median_convert_seconds == 20


def test_검수까지_걸린_시간은_업로드_시점부터_잰다() -> None:
    """담당자가 체감하는 "문서 1건 완성 시간"이 이 값이다 (master-plan 7장 효율 지표)."""
    records = [_record(review_minutes=30, edit_ratio_value=0.1)]

    assert summarize(records).median_review_seconds == 30 * 60


def test_평균_수정률은_검수한_문서만_센다() -> None:
    """검수 전 문서를 0%로 세면 "손대지 않았다"와 구분되지 않아 추세가 왜곡된다."""
    records = [
        _record(),
        _record(review_minutes=5, edit_ratio_value=0.2),
        _record(review_minutes=5, edit_ratio_value=0.4),
    ]

    summary = summarize(records)

    assert summary.mean_edit_ratio == pytest.approx(0.3)


def test_문서가_없으면_집계가_비어_있다() -> None:
    """0으로 나누지 않는다 — 파일럿 시작 전에 돌려도 리포트가 나와야 한다."""
    summary = summarize([])

    assert summary.total == 0
    assert summary.median_convert_seconds is None
    assert summary.mean_edit_ratio is None


# --- 표 렌더링 -----------------------------------------------------------------


def test_문서별_표에_수치가_담긴다() -> None:
    record = _record(
        convert_seconds=45, review_minutes=12, edit_ratio_value=0.153, char_count=2_400
    )

    report = render_report([record], summarize([record]))

    assert f"`{str(record.document_id)[:8]}`" in report
    assert "2,400" in report
    assert "45초" in report
    assert "12.0분" in report
    assert "15.3%" in report


def test_검수하지_않은_문서는_수정률_자리를_비운다() -> None:
    """0%로 적으면 "그대로 사용했다"는 뜻이 되어 KPI를 잘못 읽게 한다."""
    record = _record()

    report = render_report([record], summarize([record]))

    # 요약에도 백분율이 있으므로(실패율 0.0%) 문서 줄만 골라 본다.
    row = next(line for line in report.splitlines() if str(record.document_id)[:8] in line)
    assert row.endswith(f"| {MISSING} | {MISSING} |")
    assert "0.0%" not in row


def test_실패_사유_코드는_그대로_싣는다() -> None:
    """예외 클래스명이라 본문이 담기지 않는다 — 실패율의 원인을 보려면 필요하다."""
    record = _record(status=ConversionStatus.FAILED, failure_code="ProviderUnavailable")

    report = render_report([record], summarize([record]))

    assert "ProviderUnavailable" in report


def test_요약에_실패율이_들어간다() -> None:
    records = [_record(), _record(status=ConversionStatus.FAILED, failure_code="StorageError")]

    report = render_report(records, summarize(records))

    assert "실패율" in report
    assert "50.0%" in report


def test_리포트에는_본문이_실릴_수_없다() -> None:
    """기록 구조체가 본문을 들고 있지 않다 — 렌더러가 실수로 찍을 방법 자체가 없다."""
    assert "easy_text" not in ConversionRecord.__annotations__
    assert "edited_text" not in ConversionRecord.__annotations__
    assert "title" not in ConversionRecord.__annotations__
