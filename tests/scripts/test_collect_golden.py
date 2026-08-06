"""골든셋 수집 초안 생성(scripts/collect_golden.py) 단위 테스트.

스크립트는 CLI 껍데기이고 판단 로직은 `app/easyread/collection.py`에 있으므로
여기서는 그 모듈을 직접 검증한다. 네트워크는 쓰지 않는다 — 수집 결과의 모양
(`FetchedDocument`)을 직접 만들어 `fetch_url` 뒤의 전 구간을 돌린다.

문서 본문은 전부 이 파일에서 만든 가짜 안내문이며, 전화번호·이메일도 실제로 쓰이지
않는 예시값(예: example.go.kr)이다.
"""

import json
from pathlib import Path

import pytest

from app.easyread.collection import (
    DRAFT_CATEGORY,
    FetchedDocument,
    build_draft,
    extract_source_text,
    html_to_text,
    next_document_id,
    normalize_text,
    slugify,
    write_draft,
)
from app.easyread.goldenset import GoldenSource, load_documents
from app.exceptions import GoldenCollectionError
from app.privacy.masking import MaskCategory

SOURCE = GoldenSource(
    url="https://example.go.kr/notice/1",
    organization="○○구청",
    license="공공누리 제1유형",
    collected_at="2026-08-06",
)

PAGE = """<!doctype html>
<html><head><title>안내</title>
<style>.a{color:red}</style>
<script>var tracker = "본문 아님";</script>
</head>
<body>
<header><h1>사이트 대문</h1></header>
<nav><ul><li>메뉴 하나</li><li>메뉴 둘</li></ul></nav>
<main>
<h2>기초연금 신청 안내</h2>
<p>만 <strong>65세</strong> 이상 어르신이 <em>대상</em>입니다.</p>
<p>문의: 02-2100-3456 &amp; elder@example.go.kr</p>
<table><tr><td>신청 기간</td><td>3월&nbsp;2일부터</td></tr></table>
</main>
<footer><p>ⓒ ○○구청</p></footer>
</body></html>
"""


def _fetched(
    body: bytes, *, filename: str = "view.do", content_type: str = "text/html"
) -> FetchedDocument:
    """fetch_url이 돌려주는 모양을 네트워크 없이 흉내 낸다."""
    return FetchedDocument(filename=filename, data=body, content_type=content_type)


def test_본문이_아닌_태그의_텍스트는_빠진다() -> None:
    텍스트 = html_to_text(PAGE)
    for 잡음 in ("tracker", "color:red", "사이트 대문", "메뉴 하나", "ⓒ ○○구청"):
        assert 잡음 not in 텍스트
    assert "기초연금 신청 안내" in 텍스트


def test_블록_요소마다_줄이_바뀌고_인라인은_이어진다() -> None:
    페이지 = "<div><p>첫 문단</p><p>둘째 <strong>문단</strong>입니다</p></div>"
    assert html_to_text(페이지).split("\n") == ["첫 문단", "둘째 문단입니다"]


def test_줄바꿈_태그도_줄을_나눈다() -> None:
    assert html_to_text("<p>첫 줄<br>둘째 줄<br/>셋째 줄</p>") == "첫 줄\n둘째 줄\n셋째 줄"


def test_문자_참조를_풀어_준다() -> None:
    """&amp;를 그대로 두면 본문에 마크업 잔해가 남고, &nbsp;는 공백으로 정리돼야 한다."""
    텍스트 = html_to_text(PAGE)
    assert "&amp;" not in 텍스트 and "&nbsp;" not in 텍스트
    assert "02-2100-3456 & elder@example.go.kr" in 텍스트
    assert "3월 2일부터" in 텍스트


def test_닫히지_않은_스킵_태그가_본문을_삼키지_않는다() -> None:
    """실제 웹 문서는 닫는 태그가 자주 빠진다 — 그 뒤 본문까지 잃으면 안 된다."""
    assert html_to_text("<nav><ul><li>메뉴</nav><p>본문</p>") == "본문"


def test_공백과_빈_줄을_정리한다() -> None:
    assert normalize_text("  첫   줄  \n\n\n\n  둘째 줄 \n\n") == "첫 줄\n\n둘째 줄"


def test_초안_본문이_마스킹을_통과한다() -> None:
    초안 = build_draft(html_to_text(PAGE), document_id="021", source=SOURCE)
    assert "02-2100-3456" not in 초안.document.source_text
    assert "elder@example.go.kr" not in 초안.document.source_text
    assert "[[전화번호1]]" in 초안.document.source_text
    assert 초안.stats.masked_counts == {MaskCategory.PHONE: 1, MaskCategory.EMAIL: 1}
    assert 초안.stats.masked_total == 2


def test_초안은_사람이_채울_자리를_비워_둔다() -> None:
    초안 = build_draft(html_to_text(PAGE), document_id="021", source=SOURCE)
    assert 초안.document.required_facts == []
    assert 초안.document.category == DRAFT_CATEGORY
    assert 초안.document.synthetic is False
    assert 초안.document.title == "기초연금 신청 안내"  # 지정하지 않으면 첫 줄


def test_제목과_분류를_지정할_수_있다() -> None:
    초안 = build_draft(
        html_to_text(PAGE),
        document_id="021",
        source=SOURCE,
        title="기초연금 안내(수집본)",
        category="복지 안내문",
    )
    assert 초안.document.title == "기초연금 안내(수집본)"
    assert 초안.document.category == "복지 안내문"
    assert 초안.filename == "021-기초연금-안내수집본.json"


def test_저장한_초안이_GoldenDocument로_다시_읽힌다(tmp_path: Path) -> None:
    """초안이 스키마를 만족하지 못하면 사람이 손볼 수조차 없다."""
    초안 = build_draft(html_to_text(PAGE), document_id="021", source=SOURCE)
    경로 = write_draft(초안, tmp_path)
    문서들 = load_documents(경로.parent)
    assert [문서.id for 문서 in 문서들] == ["021"]
    문서 = 문서들[0]
    assert 문서.source is not None
    assert 문서.source.organization == "○○구청"
    assert 문서.source.license == "공공누리 제1유형"


def test_로컬_파일_수집이면_url_필드가_빠진다(tmp_path: Path) -> None:
    파일_출처 = GoldenSource(
        organization="파일럿 기관", license="파일럿 기관 제공", collected_at="2026-08-06"
    )
    초안 = build_draft("안내문입니다.\n문의 02-2100-3456", document_id="021", source=파일_출처)
    경로 = write_draft(초안, tmp_path)
    payload = json.loads(경로.read_text(encoding="utf-8"))
    assert "url" not in payload["source"]
    assert payload["synthetic"] is False


def test_id는_기존_문서_다음_번호로_채번된다(tmp_path: Path) -> None:
    문서_디렉터리 = tmp_path / "documents"
    초안_디렉터리 = tmp_path / "drafts"
    문서_디렉터리.mkdir()
    초안_디렉터리.mkdir()
    (문서_디렉터리 / "003-가.json").write_text('{"id": "003"}', encoding="utf-8")
    (초안_디렉터리 / "021-나.json").write_text('{"id": "021"}', encoding="utf-8")
    assert next_document_id([문서_디렉터리, 초안_디렉터리]) == "022"


def test_깨진_초안의_번호도_건너뛰지_않는다(tmp_path: Path) -> None:
    """편집 중이라 JSON이 깨진 초안의 id를 놓치면 다음 수집이 같은 번호를 다시 쓴다."""
    디렉터리 = tmp_path / "drafts"
    디렉터리.mkdir()
    (디렉터리 / "030-편집중.json").write_text("{깨진 JSON", encoding="utf-8")
    assert next_document_id([디렉터리]) == "031"


def test_빈_디렉터리면_첫_번호를_준다(tmp_path: Path) -> None:
    assert next_document_id([tmp_path]) == "001"


def test_본문이_비면_오류다() -> None:
    with pytest.raises(GoldenCollectionError):
        build_draft("   \n\n  ", document_id="021", source=SOURCE)


def test_html_응답이_초안까지_처리된다() -> None:
    """URL에 확장자가 없어도(view.do) Content-Type으로 형식을 가른다."""
    텍스트 = extract_source_text(_fetched(PAGE.encode()))
    초안 = build_draft(텍스트, document_id="021", source=SOURCE)
    assert "기초연금 신청 안내" in 초안.document.source_text
    assert 초안.stats.source_chars == len(초안.document.source_text)


def test_cp949_페이지도_읽는다() -> None:
    """국내 공공기관 페이지에는 EUC-KR 배포가 남아 있다."""
    본문 = extract_source_text(_fetched("<p>주민센터 안내</p>".encode("cp949")))
    assert 본문 == "주민센터 안내"


def test_텍스트_파일은_그대로_읽는다() -> None:
    본문 = extract_source_text(
        _fetched("첫 줄\n\n\n둘째 줄".encode(), filename="안내.txt", content_type="")
    )
    assert 본문 == "첫 줄\n\n둘째 줄"


def test_빈_페이지는_오류다() -> None:
    with pytest.raises(GoldenCollectionError):
        extract_source_text(_fetched(b"<html><body><nav>menu</nav></body></html>"))


def test_지원하지_않는_형식은_오류다() -> None:
    with pytest.raises(GoldenCollectionError):
        extract_source_text(_fetched(b"\x00\x01", filename="scan.png", content_type="image/png"))


def test_슬러그가_파일명에_쓸_수_없는_문자를_지운다() -> None:
    assert slugify("2026년 기초연금/신청 안내!") == "2026년-기초연금신청-안내"
    assert slugify("///") == "초안"
