"""골든셋 수집 초안 생성기 — URL·파일 → 텍스트 추출 → 마스킹 → 초안 JSON.

실행 예:
    uv run python scripts/collect_golden.py https://example.go.kr/board/view.do?id=1 \
        --org "보건복지부" --license "공공누리 제1유형"
    uv run python scripts/collect_golden.py ./내려받은안내문.hwpx \
        --org "○○시" --license "공공누리 제1유형" --category "복지 안내문"

대형 자료(책자·사업안내)는 구조를 먼저 보고 안내문 단위로 발췌한다:
    uv run python scripts/collect_golden.py ./사업안내.pdf --preview
    uv run python scripts/collect_golden.py ./사업안내.pdf --pages 42-45 --slice 0:1500 \
        --org "보건복지부" --license "공공누리 제1유형"

사람이 할 일은 자동화하지 않는다(docs/golden-collection-plan.md 4장): 공공누리 유형
판단·required_facts 큐레이션·마스킹 결과 확인은 초안이 나온 뒤 사람이 한다. 자동 분류와
팩트 후보는 사람이 고칠 출발점일 뿐이다. 그래서 초안은 `tests/golden/documents/`가
아니라 `docs/golden-drafts/`에 쓴다.

보안: 표준출력에 문서 본문·마스킹 원문을 남기지 않는다 — 저장 경로·글자 수·건수까지만
      출력한다(CLAUDE.md 보안·데이터 규칙). 마스킹 범주는 2종(주민등록번호·카드번호)이라
      전화번호·이메일·계좌번호는 그것만으로 걸러지지 않는다. 초안이 저장소에 커밋되고
      미리보기가 곧장 화면에 찍히므로, 그 두 경로에는 수집 도구의 안전장치가 따로 걸린다
      (`app/easyread/collection.py`의 '연락처 sink 안전장치'). 런타임이 LLM에 보내는
      경로의 정책을 바꾸는 것이 아니다.
"""

import argparse
import logging
import sys
from datetime import date
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[1]
# 스크립트를 직접 실행하면 sys.path[0]이 scripts/라 app 패키지를 찾지 못한다.
# 프로젝트를 설치형 패키지로 만들지 않았으므로 리포 루트를 직접 넣어 준다.
sys.path.insert(0, str(REPO_ROOT))

from app.easyread.collection import (  # noqa: E402
    DRAFT_CATEGORY,
    FetchedDocument,
    GoldenDraft,
    PreviewReport,
    apply_char_slice,
    build_draft,
    extract_source_text,
    fetch_url,
    next_document_id,
    parse_char_slice,
    parse_page_range,
    preview_source,
    read_local_file,
    write_draft,
)
from app.easyread.goldenset import GoldenSource  # noqa: E402
from app.exceptions import EasyDocError  # noqa: E402

DOCUMENTS_DIR = REPO_ROOT / "tests" / "golden" / "documents"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "docs" / "golden-drafts"

# 분할 변환 전까지의 문서당 변환 상한(master-plan 3.3). 정책 원본은
# app/services/documents.py의 MAX_CONVERTIBLE_CHARS다 — 여기서 import하지 않는 이유는
# 수집 스크립트가 DB·큐 계층까지 끌어오지 않게 하기 위해서이고, 경고 문구용 기준값이라
# 어긋나도 수집 결과가 달라지지 않는다. 상한 정책이 바뀌면 함께 고칠 것.
LONG_DOCUMENT_CHARS = 4_000

_URL_SCHEMES = frozenset({"http", "https"})


def load_source(source: str) -> tuple[FetchedDocument, str | None]:
    """URL이면 받아 오고, 아니면 로컬 파일을 읽는다. (원본, 출처로 남길 URL)"""
    if urlparse(source).scheme in _URL_SCHEMES:
        return fetch_url(source), source
    return read_local_file(Path(source)), None


def collect(
    fetched: FetchedDocument,
    url: str | None,
    *,
    organization: str,
    license_name: str,
    title: str | None,
    category: str | None,
    output_dir: Path,
    pages: tuple[int, int] | None = None,
    span: tuple[int, int | None] | None = None,
) -> GoldenDraft:
    """받아 온 원본 하나를 초안 문서로 만든다 (저장은 하지 않는다)."""
    text = extract_source_text(fetched, pages=pages)
    if span is not None:
        text = apply_char_slice(text, span)
    golden_source = GoldenSource(
        url=url,
        organization=organization,
        license=license_name,
        collected_at=date.today().isoformat(),
    )
    return build_draft(
        text,
        document_id=next_document_id([DOCUMENTS_DIR, output_dir]),
        source=golden_source,
        title=title,
        category=category,
    )


def report_preview(preview: PreviewReport) -> None:
    """구조 통계를 표로 출력한다 (첫 줄은 이미 마스킹을 통과한 텍스트다)."""
    print(f"미리보기: {preview.unit_name} {len(preview.units)}개 | 합계 {preview.total_chars:,}자")
    for unit in preview.units:
        print(
            f"  {preview.unit_name} {unit.index:>4} | {unit.chars:>7,}자"
            f" | 누적 {unit.cumulative_chars:>9,}자 | {unit.first_line}"
        )
    print("첫 줄은 마스킹(2종)과 연락처 가림(3종)을 거친 텍스트입니다 (원문은 출력하지 않습니다).")
    print(
        "다음 단계: 필요한 구간을 골라 --pages(PDF 전용) 또는 --slice로 다시 실행하세요"
        " — 누적 글자 수는 이어 붙이는 과정에서 조금 어긋나는 근사치입니다."
    )


def report(draft: GoldenDraft, path: Path | None) -> None:
    """통계와 다음 단계만 출력한다 (본문·제목·마스킹 원문은 출력하지 않는다)."""
    print(f"초안 저장: {path}" if path is not None else "초안 저장 안 함 (--dry-run)")
    print(f"문서 id: {draft.document.id} | 마스킹 후 본문 {draft.stats.source_chars:,}자")

    if draft.stats.masked_counts:
        detail = ", ".join(
            f"{category.value} {count}건" for category, count in draft.stats.masked_counts.items()
        )
        print(f"마스킹: {detail} (총 {draft.stats.masked_total}건 — 초안에서 직접 확인하세요)")
    else:
        print("마스킹: 없음 (개인정보 패턴이 없거나 놓쳤을 수 있으니 초안을 확인하세요)")

    # 연락처는 마스킹 범주(2종) 밖이라 위 줄에 잡히지 않는다. 초안이 저장소에 커밋되므로
    # 수집 도구가 갈래 표시로 바꿔 두는데, 문서 뜻이 통하려면 합성값으로 되돌려야 하는
    # 자리가 있어 사람에게 알린다 (app/easyread/collection.py '연락처 sink 안전장치').
    if draft.stats.contact_counts:
        연락처 = ", ".join(
            f"{kind.value} {count}건" for kind, count in draft.stats.contact_counts.items()
        )
        print(
            f"연락처 가림: {연락처} — 초안에서 지우거나 합성값(044-000-0000 등)으로 바꾸세요"
            " (정규식이 놓치는 표기가 있으니 본문도 훑어보세요)"
        )

    if draft.stats.auto_category is not None:
        print(f"자동 분류: {draft.stats.auto_category} (규칙 기반 추정 — 사람 확인 필요)")
    if draft.stats.suggested_facts:
        print(
            f"required_facts 자동 추출 후보 {draft.stats.suggested_facts}개 "
            "— 반드시 사람이 검증·수정하세요 (정확성·중요도 판단은 사람 몫)"
        )

    if draft.stats.source_chars > LONG_DOCUMENT_CHARS:
        print(
            f"경고: 본문이 {LONG_DOCUMENT_CHARS:,}자를 넘습니다 "
            "— 분할 변환 대상이라 골든셋 편입은 보류하세요"
            " (--preview로 구조를 보고 --pages·--slice로 발췌하세요)"
        )

    steps = ["required_facts를 3~6개로 확정하기 (연락처·마스킹 대상 패턴 금지)"]
    if draft.stats.contact_counts:
        steps.append("가려진 연락처 자리를 지우거나 합성값으로 바꾸기 + 본문 육안 훑기")
    if draft.document.category == DRAFT_CATEGORY:
        # 사람이 --category로 이미 정했다면 안내하지 않는다 — 매번 붙는 문구는 읽히지 않는다.
        steps.append(f"category를 통제 어휘로 고치기 (현재: {DRAFT_CATEGORY})")
    elif draft.stats.auto_category is not None:
        steps.append(f"자동 분류가 맞는지 확인하기 (현재: {draft.stats.auto_category})")
    steps.append("공공누리 유형·출처(source) 확인, 마스킹 결과 육안 검수")
    steps.append("tests/golden/documents/로 옮긴 뒤 `uv run pytest tests/golden` 실행")
    print("다음 단계:")
    for number, step in enumerate(steps, start=1):
        print(f"  {number}. {step}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="URL·파일에서 골든셋 초안 JSON을 만든다 (본문은 표준출력에 남기지 않음)"
    )
    parser.add_argument("source", help="수집할 URL 또는 로컬 파일 경로 (html/txt/docx/pdf/hwpx)")
    parser.add_argument("--org", default=None, help="발행 기관명 (예: 보건복지부)")
    parser.add_argument(
        "--license",
        default=None,
        help='이용 조건 (예: "공공누리 제1유형", "파일럿 기관 제공")',
    )
    parser.add_argument("--title", default=None, help="문서 제목 (기본: 본문 첫 줄)")
    parser.add_argument("--category", default=None, help=f"주제 분류 (기본: {DRAFT_CATEGORY})")
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"초안을 저장할 디렉터리 (기본: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument(
        "--pages",
        default=None,
        metavar="A-B",
        help="PDF에서 그 쪽 범위만 추출한다 (1부터, 양끝 포함. 예: 12-30, 5). PDF 전용",
    )
    parser.add_argument(
        "--slice",
        dest="span",
        default=None,
        metavar="START:END",
        help="추출한 본문에서 글자 범위만 잘라낸다 (예: 0:1500, 1500:). 모든 형식",
    )
    parser.add_argument(
        "--preview",
        action="store_true",
        help="저장하지 않고 구조 통계만 출력한다 (PDF는 쪽별, hwpx는 구역별 글자 수)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="파일을 저장하지 않고 통계만 출력한다",
    )
    args = parser.parse_args(argv)
    if not args.preview and not (args.org and args.license):
        # 초안에는 출처 메타가 반드시 들어가야 한다(공공누리 표시 의무). 다만 --preview는
        # 어느 범위를 뽑을지 고르는 단계라 아직 출처를 적을 때가 아니다.
        parser.error("--org와 --license는 필수입니다 (--preview로 구조만 볼 때는 생략 가능)")
    return args


def main() -> int:
    # pypdf는 깨진 PDF를 만나면 헤더 조각(`invalid pdf header: b'not a'`)을 경고 로그로
    # 찍는다 — 이 스크립트에서 문서 내용이 터미널로 새는 유일한 경로라 막는다.
    # 우리 추출기 로그(형식·바이트 수·사유 코드)는 그대로 둔다.
    logging.getLogger("pypdf").setLevel(logging.ERROR)

    args = parse_args()
    output_dir: Path = args.output
    try:
        pages = parse_page_range(args.pages) if args.pages else None
        span = parse_char_slice(args.span) if args.span else None
        fetched, url = load_source(args.source)
        if args.preview:
            report_preview(preview_source(fetched))
            return 0
        draft = collect(
            fetched,
            url,
            organization=args.org,
            license_name=args.license,
            title=args.title,
            category=args.category,
            output_dir=output_dir,
            pages=pages,
            span=span,
        )
        path = None if args.dry_run else write_draft(draft, output_dir)
    except EasyDocError as error:
        # 내려받기 실패·형식 미지원·빈 본문·추출 실패·범위 오류. 트레이스백 대신 한 줄로.
        print(f"오류: {error}")
        return 1
    report(draft, path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
