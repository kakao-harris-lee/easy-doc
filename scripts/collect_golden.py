"""골든셋 수집 초안 생성기 — URL·파일 → 텍스트 추출 → 마스킹 → 초안 JSON.

실행 예:
    uv run python scripts/collect_golden.py https://example.go.kr/board/view.do?id=1 \
        --org "보건복지부" --license "공공누리 제1유형"
    uv run python scripts/collect_golden.py ./내려받은안내문.hwpx \
        --org "○○시" --license "공공누리 제1유형" --category "복지 안내문"

사람이 할 일은 자동화하지 않는다(docs/golden-collection-plan.md 4장): 공공누리 유형
판단·required_facts 큐레이션·마스킹 결과 확인은 초안이 나온 뒤 사람이 한다. 그래서
초안은 `tests/golden/documents/`가 아니라 `docs/golden-drafts/`에 쓴다.

보안: 표준출력에 문서 본문·마스킹 원문을 남기지 않는다 — 저장 경로·글자 수·마스킹
      건수까지만 출력한다(CLAUDE.md 보안·데이터 규칙). 초안 **파일**에는 마스킹을 마친
      본문이 들어가므로 검토를 마친 뒤 평가셋으로 옮긴다.
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
    GoldenDraft,
    build_draft,
    extract_source_text,
    fetch_url,
    next_document_id,
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


def collect(
    source: str,
    *,
    organization: str,
    license_name: str,
    title: str | None,
    category: str | None,
    output_dir: Path,
) -> GoldenDraft:
    """입력 하나를 초안 문서로 만든다 (저장은 하지 않는다)."""
    is_url = urlparse(source).scheme in _URL_SCHEMES
    fetched = fetch_url(source) if is_url else read_local_file(Path(source))
    text = extract_source_text(fetched)
    golden_source = GoldenSource(
        url=source if is_url else None,
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

    if draft.stats.source_chars > LONG_DOCUMENT_CHARS:
        print(
            f"경고: 본문이 {LONG_DOCUMENT_CHARS:,}자를 넘습니다 "
            "— 분할 변환 대상이라 골든셋 편입은 보류하세요"
        )

    steps = ["required_facts를 3~6개 채우기 (전화·이메일·계좌 등 마스킹 대상 패턴 금지)"]
    if draft.document.category == DRAFT_CATEGORY:
        # 사람이 --category로 이미 정했다면 안내하지 않는다 — 매번 붙는 문구는 읽히지 않는다.
        steps.append(f"category를 통제 어휘로 고치기 (현재: {DRAFT_CATEGORY})")
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
    parser.add_argument("--org", required=True, help="발행 기관명 (예: 보건복지부)")
    parser.add_argument(
        "--license",
        required=True,
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
        "--dry-run",
        action="store_true",
        help="파일을 저장하지 않고 통계만 출력한다",
    )
    return parser.parse_args(argv)


def main() -> int:
    # pypdf는 깨진 PDF를 만나면 헤더 조각(`invalid pdf header: b'not a'`)을 경고 로그로
    # 찍는다 — 이 스크립트에서 문서 내용이 터미널로 새는 유일한 경로라 막는다.
    # 우리 추출기 로그(형식·바이트 수·사유 코드)는 그대로 둔다.
    logging.getLogger("pypdf").setLevel(logging.ERROR)

    args = parse_args()
    output_dir: Path = args.output
    try:
        draft = collect(
            args.source,
            organization=args.org,
            license_name=args.license,
            title=args.title,
            category=args.category,
            output_dir=output_dir,
        )
        path = None if args.dry_run else write_draft(draft, output_dir)
    except EasyDocError as error:
        # 내려받기 실패·형식 미지원·빈 본문·추출 실패. 트레이스백 대신 한 줄로 알린다.
        print(f"오류: {error}")
        return 1
    report(draft, path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
