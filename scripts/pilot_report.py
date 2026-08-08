"""파일럿 KPI 측정 리포트 — 문서별 소요 시간·검수 여부·수정률·실패율.

master-plan 7장의 지표 중 **기계로 잴 수 있는 것**을 DB에서 뽑는다: 문서 1건 완성
소요 시간(효율), 담당자 수정률(AI 초안 대비 편집 비율, 품질), 변환 실패율. 나머지
(주관 평가·"배포하겠다" 응답)는 세션 진행자가 손으로 적는다 — `docs/pilot-runbook.md`.

실행::

    uv run python scripts/pilot_report.py
    uv run python scripts/pilot_report.py --output docs/pilot/2026-08-report.md
    uv run python scripts/pilot_report.py --since 2026-08-01

**운영자 전용 도구다.** API 경로가 아니라 DB에 직접 붙고, 소유자 격리도 거치지 않는다
(파일럿 기관 전체를 한 번에 보는 것이 목적이다). 서비스 코드가 import하지 않는다.

보안 규약 (CLAUDE.md · master-plan 3.2):

- **본문을 출력하지 않는다.** 수정률을 재려면 초안과 검수본을 복호화해야 하지만,
  복호화한 문자열은 유사도 계산에만 쓰고 즉시 버린다 — 표에는 비율(숫자)만 남는다.
- 문서 식별은 UUID 앞 8자리까지만 쓴다. 제목은 본문 첫 줄에서 유도한 사용자
  콘텐츠라 리포트에 싣지 않는다.
- 복호화 키(FERNET_KEY)는 다른 곳과 같이 환경변수·`.env`에서만 읽는다.
"""

import argparse
import asyncio
import sys
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from difflib import SequenceMatcher
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
# 스크립트를 직접 실행하면 sys.path[0]이 scripts/라 app 패키지를 찾지 못한다.
# 프로젝트를 설치형 패키지로 만들지 않았으므로 리포 루트를 직접 넣어 준다.
sys.path.insert(0, str(REPO_ROOT))

from sqlalchemy import select  # noqa: E402

from app.config import Settings  # noqa: E402
from app.db import create_engine_and_factory  # noqa: E402
from app.models.conversion import Conversion, ConversionStatus  # noqa: E402
from app.models.document import Document  # noqa: E402
from app.privacy.crypto import TextCipher  # noqa: E402

#: 표에 싣는 문서 식별자 길이. 사람이 세션 기록과 대조할 수 있으면 충분하고,
#: 짧게 자를수록 리포트가 유출돼도 특정 문서를 되짚기 어렵다.
ID_PREFIX_LENGTH = 8

#: 값이 없을 때 표에 적는 기호(검수 전 문서의 수정률 등).
MISSING = "—"


@dataclass(frozen=True)
class ConversionRecord:
    """리포트 한 줄의 원천 값. **본문은 담지 않는다.**

    복호화된 초안·검수본은 `edit_ratio`를 계산하는 순간에만 존재하고, 이 구조체에는
    비율만 남는다 — 리포트를 만드는 나머지 코드가 본문에 닿을 방법이 없어진다.
    """

    document_id: uuid.UUID
    char_count: int
    status: str
    created_at: datetime
    updated_at: datetime
    reviewed_at: datetime | None
    #: AI 초안 대비 편집 비율 (0.0 = 손대지 않음, 1.0 = 전부 새로 씀).
    #: 검수본이 없으면 None.
    edit_ratio: float | None
    failure_code: str | None


@dataclass(frozen=True)
class ReportSummary:
    """표 아래에 붙는 집계."""

    total: int
    done: int
    failed: int
    reviewed: int
    #: 변환 접수부터 완료까지 걸린 시간의 중앙값(초). 완료 건이 없으면 None.
    median_convert_seconds: float | None
    #: 검수까지 걸린 시간의 중앙값(초). 검수 건이 없으면 None.
    median_review_seconds: float | None
    #: 검수한 문서들의 평균 수정률. 검수 건이 없으면 None.
    mean_edit_ratio: float | None


def edit_ratio(draft: str, edited: str) -> float:
    """AI 초안 대비 편집 비율을 잰다 (0.0 = 그대로 씀, 1.0 = 전부 새로 씀).

    문자 단위 유사도(difflib)를 쓰는 이유: 수정률은 "사람이 얼마나 손댔는가"를 재는
    지표이고(master-plan 7장), 한국어는 조사·어미 한 글자만 바꿔도 형태소가 갈린다 —
    단어 단위로 세면 토크나이저 선택이 값을 좌우한다. 문자 단위는 도구에 기대지 않고
    같은 입력에 항상 같은 값을 준다.

    `SequenceMatcher.ratio`는 공통 부분 문자열의 비중이라, 1에서 빼면 "바뀐 비중"이
    된다. 절대적인 편집 거리가 아니라 **추세를 보는 값**이다(목표도 "추세 하락").

    Args:
        draft: AI 초안. 비어 있으면 비교 기준이 없다.
        edited: 담당자 검수 수정본.

    Returns:
        0.0 이상 1.0 이하. 초안이 비어 있으면 수정본 유무로 0.0/1.0을 준다.
    """
    if not draft:
        # 초안이 없는데 수정본이 있으면 전부 사람이 쓴 것이다.
        return 0.0 if not edited else 1.0
    # autojunk=False: 기본값은 긴 문자열에서 자주 나오는 문자를 '잡음'으로 보고 비교에서
    # 빼는데, 한국어 안내문은 조사·어미가 반복되어 본문의 상당 부분이 잡음으로 분류된다.
    return 1.0 - SequenceMatcher(None, draft, edited, autojunk=False).ratio()


def _elapsed_seconds(start: datetime, end: datetime | None) -> float | None:
    """두 시각의 차이(초). 끝 시각이 없거나 거꾸로면 None."""
    if end is None:
        return None
    seconds = (end - start).total_seconds()
    return seconds if seconds >= 0 else None


def _median(values: list[float]) -> float | None:
    """중앙값. 평균이 아닌 이유는 변환 한 건이 재시도로 튀면 평균이 통째로 흔들리기 때문."""
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2


def summarize(records: list[ConversionRecord]) -> ReportSummary:
    """문서별 기록을 파일럿 보고용 집계로 줄인다."""
    done = [record for record in records if record.status == ConversionStatus.DONE]
    failed = [record for record in records if record.status == ConversionStatus.FAILED]
    reviewed = [record for record in records if record.reviewed_at is not None]
    ratios = [record.edit_ratio for record in reviewed if record.edit_ratio is not None]
    convert_seconds = [
        seconds
        for record in done
        if (seconds := _elapsed_seconds(record.created_at, record.updated_at)) is not None
    ]
    review_seconds = [
        seconds
        for record in reviewed
        if (seconds := _elapsed_seconds(record.created_at, record.reviewed_at)) is not None
    ]
    return ReportSummary(
        total=len(records),
        done=len(done),
        failed=len(failed),
        reviewed=len(reviewed),
        median_convert_seconds=_median(convert_seconds),
        median_review_seconds=_median(review_seconds),
        mean_edit_ratio=sum(ratios) / len(ratios) if ratios else None,
    )


def _format_seconds(seconds: float | None) -> str:
    """소요 시간을 사람이 읽는 말로. 분 단위가 파일럿 기록표와 대조하기 쉽다."""
    if seconds is None:
        return MISSING
    if seconds < 60:
        return f"{seconds:.0f}초"
    return f"{seconds / 60:.1f}분"


def _format_ratio(ratio: float | None) -> str:
    """비율을 백분율로. 소수 한 자리면 추세를 읽기에 충분하다."""
    return MISSING if ratio is None else f"{ratio * 100:.1f}%"


def render_report(records: list[ConversionRecord], summary: ReportSummary) -> str:
    """마크다운 리포트를 만든다. **문서 ID(앞자리)와 수치만 담는다.**

    제목·본문·마스킹 항목은 어떤 경로로도 이 문자열에 들어오지 않는다 —
    `ConversionRecord`가 애초에 들고 있지 않기 때문이다.
    """
    lines = [
        "# 파일럿 KPI 리포트",
        "",
        f"생성 시각: {datetime.now(UTC).isoformat(timespec='seconds')}",
        f"대상 문서: {summary.total}건",
        "",
        "## 요약",
        "",
        "| 지표 | 값 |",
        "|---|---|",
        f"| 변환 완료 | {summary.done}건 |",
        f"| 변환 실패 | {summary.failed}건 |",
        f"| 실패율 | {_format_ratio(summary.failed / summary.total if summary.total else None)} |",
        f"| 검수 완료 | {summary.reviewed}건 |",
        f"| 변환 소요(중앙값) | {_format_seconds(summary.median_convert_seconds)} |",
        f"| 업로드→검수(중앙값) | {_format_seconds(summary.median_review_seconds)} |",
        f"| 평균 수정률 | {_format_ratio(summary.mean_edit_ratio)} |",
        "",
        "## 문서별",
        "",
        "| 문서 | 글자 수 | 상태 | 변환 소요 | 검수 | 검수까지 | 수정률 | 실패 사유 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for record in records:
        cells = [
            f"`{str(record.document_id)[:ID_PREFIX_LENGTH]}`",
            f"{record.char_count:,}",
            record.status,
            _format_seconds(_elapsed_seconds(record.created_at, record.updated_at)),
            "예" if record.reviewed_at is not None else "아니오",
            _format_seconds(_elapsed_seconds(record.created_at, record.reviewed_at)),
            _format_ratio(record.edit_ratio),
            record.failure_code or MISSING,
        ]
        lines.append(f"| {' | '.join(cells)} |")
    lines.extend(
        [
            "",
            "> 수정률은 AI 초안 대비 문자 단위 편집 비율이다(0%=그대로 사용).",
            "> 본문은 이 리포트 어디에도 담기지 않는다 — 비율 계산에만 쓰고 버린다.",
            "",
        ]
    )
    return "\n".join(lines)


async def collect_records(
    database_url: str, cipher: TextCipher, since: datetime | None
) -> list[ConversionRecord]:
    """DB에서 문서별 변환 기록을 읽는다. 본문은 수정률 계산 뒤 즉시 버린다."""
    engine, session_factory = create_engine_and_factory(database_url)
    try:
        async with session_factory() as session:
            statement = (
                select(Conversion, Document)
                .join(Document, Conversion.document_id == Document.id)
                .order_by(Document.created_at)
            )
            if since is not None:
                statement = statement.where(Document.created_at >= since)
            rows = (await session.execute(statement)).all()
            return [_to_record(conversion, document, cipher) for conversion, document in rows]
    finally:
        await engine.dispose()


def _to_record(conversion: Conversion, document: Document, cipher: TextCipher) -> ConversionRecord:
    """행 하나를 리포트 기록으로 옮긴다.

    복호화한 본문은 이 함수 안에서만 존재한다 — 돌려주는 값에는 비율만 남는다.
    """
    ratio: float | None = None
    if conversion.easy_text_encrypted is not None and conversion.edited_text_encrypted is not None:
        ratio = edit_ratio(
            cipher.decrypt(conversion.easy_text_encrypted),
            cipher.decrypt(conversion.edited_text_encrypted),
        )
    return ConversionRecord(
        document_id=document.id,
        char_count=document.char_count,
        status=conversion.status,
        created_at=document.created_at,
        updated_at=conversion.updated_at,
        reviewed_at=conversion.reviewed_at,
        edit_ratio=ratio,
        failure_code=conversion.failure_code,
    )


def parse_args() -> argparse.Namespace:
    """명령행 인자를 읽는다."""
    parser = argparse.ArgumentParser(
        description="파일럿 KPI 리포트를 만든다 (운영자 전용 — DB에 직접 붙는다)"
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="리포트를 쓸 파일 경로. 없으면 표준출력으로 낸다.",
    )
    parser.add_argument(
        "--since",
        default=None,
        help="이 날짜(YYYY-MM-DD) 이후에 올린 문서만 집계한다.",
    )
    return parser.parse_args()


def _parse_since(value: str | None) -> datetime | None:
    """--since 값을 UTC 자정 시각으로 바꾼다. 없으면 None(전체)."""
    if value is None:
        return None
    return datetime.strptime(value, "%Y-%m-%d").replace(tzinfo=UTC)


def main() -> int:
    """리포트를 만들어 파일 또는 표준출력에 낸다."""
    args = parse_args()
    settings = Settings()
    if settings.fernet_key is None:
        print("오류: FERNET_KEY가 설정되지 않아 수정률을 계산할 수 없습니다")
        return 1
    try:
        since = _parse_since(args.since)
    except ValueError:
        print("오류: --since는 YYYY-MM-DD 형식이어야 합니다")
        return 1

    cipher = TextCipher(settings.fernet_key.get_secret_value())
    records = asyncio.run(collect_records(settings.database_url, cipher, since))
    report = render_report(records, summarize(records))

    output: Path | None = args.output
    if output is None:
        print(report)
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(report, encoding="utf-8")
        # 경로와 건수만 알린다 — 리포트 내용을 터미널에 두 번 남기지 않는다.
        print(f"리포트를 썼습니다: {output} ({len(records)}건)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
