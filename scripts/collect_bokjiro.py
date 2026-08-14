"""복지서비스 Open API(data.go.kr) 골든셋 초안 수집기.

`scripts/collect_golden.py`가 URL·파일 하나를 받는 도구라면, 이 스크립트는 복지서비스
데이터셋에서 **여러 건을 한 번에** 받아 온다. 목록 조회로 서비스ID를 얻고 상세를 하나씩
받아 "지원 대상 / 선정 기준 / …" 섹션으로 이어 붙인 뒤, 그 뒤 단계(마스킹→자동 분류→
팩트 후보→초안 저장)는 collect_golden.py와 똑같은 파이프라인을 지난다.

실행 예:
    uv run python scripts/collect_bokjiro.py --api local --limit 10
    uv run python scripts/collect_bokjiro.py --api central --serv-id WLF00001138

인증키는 `DATA_GO_KR_API_KEY`로 준다 — 리포 루트의 `.env`에 적어 두거나 실제 환경변수로
넘기면 되고, 둘 다 있으면 환경변수가 이긴다(`CollectionSettings`). 앱 Settings
(app/config.py)를 넓히지 않는 이유는 수집 도구가 서비스 런타임이 아니기 때문이다 —
서버가 뜨는 데 필요한 설정과 운영자가 가끔 돌리는 스크립트의 자격증명을 한 곳에 섞으면,
키가 없다는 이유로 서비스가 못 뜨거나 반대로 서버 설정에 수집용 키가 딸려 들어간다.

트래픽: 개발계정 일일 상한은 중앙부처 100건, 지자체 1,000건이다. 한 번 실행에
`--limit`만큼의 상세 조회 + 목록 조회 1회를 쓰므로, 상한을 넘기지 않도록 --limit에
상한(15)을 둔다.

보안: 표준출력에 문서 본문·인증키를 남기지 않는다 — 저장 경로·글자 수·마스킹 건수까지만
      출력한다(CLAUDE.md 보안·데이터 규칙).
"""

import argparse
import sys
from datetime import date
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
# collect_golden.py와 같은 이유 — 리포를 설치형 패키지로 만들지 않았다.
sys.path.insert(0, str(REPO_ROOT))

from app.easyread.bokjiro import (  # noqa: E402
    WELFARE_APIS,
    ServiceSummary,
    WelfareApi,
    WelfareApiClient,
    build_golden_source,
    build_source_text,
)
from app.easyread.collection import (  # noqa: E402
    DRAFT_CATEGORY,
    CollectionSettings,
    GoldenDraft,
    build_draft,
    next_document_id,
    write_draft,
)
from app.exceptions import EasyDocError  # noqa: E402

DOCUMENTS_DIR = REPO_ROOT / "tests" / "golden" / "documents"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "docs" / "golden-drafts"

#: 인증키 설정 이름. `.env` 또는 실제 환경변수 어느 쪽으로도 줄 수 있다
#: (`CollectionSettings`가 둘을 함께 읽는다). 값은 절대 출력하지 않는다.
API_KEY_ENV = "DATA_GO_KR_API_KEY"

#: 한 번 실행에서 받아 올 서비스 수의 상한. 일일 트래픽 상한(중앙 100건)에 비해
#: 넉넉히 작게 잡아 실수로 하루치를 태우는 일을 막는다.
MAX_LIMIT = 15
DEFAULT_LIMIT = 5

#: 골든셋 문서 길이 하한(수집 방안 1장의 500~4,000자). 이보다 짧은 서비스는 요약만 있고
#: 서술 필드가 비어 있는 레코드라 안내문 구실을 못 한다 — 사유를 남기고 건너뛴다.
DEFAULT_MIN_CHARS = 500


def collect_one(
    client: WelfareApiClient,
    api: WelfareApi,
    summary: ServiceSummary | None,
    serv_id: str,
    *,
    output_dir: Path,
) -> GoldenDraft:
    """서비스 하나의 상세를 받아 초안으로 만든다 (저장은 하지 않는다).

    상세 링크는 목록 응답에만 있으므로(지자체 API 실측) 목록에서 받은 값을 넘겨 준다.
    --serv-id로 직접 지정한 경우에는 summary가 없어 출처 URL이 비고, 대신 dataset·
    record_id가 재현의 근거로 남는다.
    """
    detail = client.get_detail(serv_id)
    source = build_golden_source(
        detail,
        api,
        collected_at=date.today().isoformat(),
        detail_link=summary.detail_link if summary is not None else None,
    )
    return build_draft(
        build_source_text(detail),
        document_id=next_document_id([DOCUMENTS_DIR, output_dir]),
        source=source,
        title=detail.serv_nm or None,
    )


def report(draft: GoldenDraft, serv_id: str, path: Path | None) -> None:
    """초안 한 건의 통계만 출력한다 (본문·마스킹 원문은 출력하지 않는다)."""
    print(f"  초안 저장: {path}" if path is not None else "  초안 저장 안 함 (--dry-run)")
    분류 = draft.document.category
    # 연락처 건수를 함께 낸다: 이 경로는 상세 응답의 '문의처' 필드가 기관 대표번호·부서
    # 메일을 그대로 실어 오므로 가려지는 자리가 가장 많다(수집 방안 4.2 실측). 값은 담지
    # 않고 건수만 낸다 — 초안 파일에서 갈래 표시를 보고 사람이 합성값으로 되돌린다.
    연락처 = sum(draft.stats.contact_counts.values())
    # privacy-allow: LOG-BODY — 문서 id·글자 수·건수·분류값만 보간한다. 본문은 담지 않는다.
    print(
        f"    id {draft.document.id} | servId {serv_id}"
        f" | 본문 {draft.stats.source_chars:,}자"
        f" | 분류 {분류}{' (규칙 기반 추정)' if draft.stats.auto_category else ''}"
        f" | 마스킹 {draft.stats.masked_total}건"
        f" | 연락처 가림 {연락처}건"
        f" | 팩트 후보 {draft.stats.suggested_facts}개"
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="복지서비스 Open API에서 골든셋 초안 JSON을 만든다"
        " (본문은 표준출력에 남기지 않음)"
    )
    parser.add_argument(
        "--api",
        choices=sorted(WELFARE_APIS),
        required=True,
        help="데이터셋 (central: 중앙부처복지서비스, local: 지자체복지서비스)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=DEFAULT_LIMIT,
        help=f"목록에서 받아 올 서비스 수 (기본 {DEFAULT_LIMIT}, 최대 {MAX_LIMIT})",
    )
    parser.add_argument("--page", type=int, default=1, help="목록 페이지 번호 (기본 1)")
    parser.add_argument(
        "--serv-id",
        dest="serv_ids",
        action="append",
        default=None,
        metavar="WLF00000000",
        help="서비스ID를 직접 지정한다 (여러 번 쓸 수 있다). 주면 목록 조회를 건너뛴다",
    )
    parser.add_argument(
        "--min-chars",
        type=int,
        default=DEFAULT_MIN_CHARS,
        help=f"이보다 짧은 본문은 건너뛴다 (기본 {DEFAULT_MIN_CHARS})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"초안을 저장할 디렉터리 (기본: {DEFAULT_OUTPUT_DIR})",
    )
    parser.add_argument("--dry-run", action="store_true", help="저장하지 않고 통계만 출력한다")
    args = parser.parse_args(argv)
    if args.serv_ids is None and not 1 <= args.limit <= MAX_LIMIT:
        # 일일 트래픽을 실수로 태우지 않도록 조용히 자르지 않고 거부한다.
        parser.error(f"--limit은 1 이상 {MAX_LIMIT} 이하여야 합니다 (일일 트래픽 상한 보호)")
    return args


def main() -> int:
    args = parse_args()
    secret = CollectionSettings().data_go_kr_api_key
    service_key = secret.get_secret_value().strip() if secret is not None else ""
    if not service_key:
        print(f"오류: {API_KEY_ENV}가 없습니다 (.env 또는 환경변수로 설정하세요)")
        return 1

    api = WELFARE_APIS[args.api]
    output_dir: Path = args.output
    만든_건수 = 0
    건너뛴_건수 = 0
    try:
        with WelfareApiClient(api, service_key) as client:
            if args.serv_ids:
                targets: list[tuple[ServiceSummary | None, str]] = [
                    (None, serv_id) for serv_id in args.serv_ids
                ]
            else:
                targets = [
                    (summary, summary.serv_id)
                    for summary in client.list_services(page=args.page, rows=args.limit)
                ]
            print(f"{api.label}: 대상 {len(targets)}건")

            for summary, serv_id in targets:
                draft = collect_one(client, api, summary, serv_id, output_dir=output_dir)
                if draft.stats.source_chars < args.min_chars:
                    # 사유를 남긴다: 서술 필드가 비어 있는 레코드는 API 쪽 데이터 품질
                    # 문제라 다시 받아도 같다 — 사람이 다른 서비스를 고르면 된다.
                    # privacy-allow: LOG-BODY — 글자 수만 보간한다(건너뛴 사유 안내).
                    print(
                        f"  건너뜀: servId {serv_id}"
                        f" | 본문 {draft.stats.source_chars:,}자 < 최소 {args.min_chars:,}자"
                        " (상세 서술 필드가 비어 있음)"
                    )
                    건너뛴_건수 += 1
                    continue
                path = None if args.dry_run else write_draft(draft, output_dir)
                report(draft, serv_id, path)
                만든_건수 += 1
    except EasyDocError as error:
        # API 오류·빈 본문. 트레이스백 대신 한 줄로 (메시지에 인증키는 담기지 않는다).
        print(f"오류: {error}")
        return 1

    print(f"완료: 초안 {만든_건수}건 생성, {건너뛴_건수}건 건너뜀")
    print("다음 단계:")
    print("  1. 가려진 연락처 자리를 지우거나 합성값으로 바꾸기 + 본문 육안 훑기")
    print("  2. 초안마다 required_facts를 3~6개로 확정하기 (연락처·마스킹 대상 패턴 금지)")
    print(f"  3. category 확인·수정하기 (자동 분류는 추정이고, 실패하면 {DRAFT_CATEGORY})")
    print("  4. 마스킹 결과 육안 검수 + 출처(source) 확인")
    print("  5. tests/golden/documents/로 옮긴 뒤 `uv run pytest tests/golden` 실행")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
