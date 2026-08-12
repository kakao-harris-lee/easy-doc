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
    CATEGORY_KEYWORDS,
    DRAFT_CATEGORY,
    FETCH_HEADERS,
    MAX_SUGGESTED_FACTS,
    ContactKind,
    FetchedDocument,
    GoldenDraft,
    apply_char_slice,
    build_draft,
    classify_category,
    contact_placeholder,
    extract_source_text,
    html_to_text,
    next_document_id,
    normalize_text,
    parse_char_slice,
    parse_page_range,
    preview_source,
    redact_contacts,
    rewrite_source_url,
    scan_contacts,
    slugify,
    suggest_facts,
    write_draft,
)
from app.easyread.goldenset import GoldenSource, load_documents
from app.exceptions import DocumentExtractionError, GoldenCollectionError
from app.privacy.masking import MaskCategory, mask_text
from tests.golden.test_schema import ALLOWED_CATEGORIES

FIXTURES = Path(__file__).resolve().parents[1] / "ingest" / "fixtures"

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
    """범주가 2종이라 주민등록번호는 `mask_text`가 가린다.

    연락처는 마스킹 범주 밖이라 `masked_counts`에 잡히지 않는다 — 그 자리는 수집 도구의
    sink 안전장치(`contact_counts`)가 따로 센다. 두 축을 섞지 않는 것이 요점이다:
    런타임이 LLM에 보내는 경로는 종전대로 2종만 가린다(master-plan 3.2).
    """
    초안 = build_draft(
        f"{html_to_text(PAGE)}\n담당자 확인용 900101-1234567",
        document_id="021",
        source=SOURCE,
    )
    assert "900101-1234567" not in 초안.document.source_text
    assert "[[주민등록번호1]]" in 초안.document.source_text
    assert 초안.stats.masked_counts == {MaskCategory.RRN: 1}
    assert 초안.stats.masked_total == 1
    # 연락처는 마스킹이 아니라 sink 안전장치가 걷어낸다 (별도 축).
    assert 초안.stats.contact_counts == {ContactKind.PHONE: 1, ContactKind.EMAIL: 1}


# ------------------------------------------------- 연락처 3종 (저장소 sink 안전장치)
#
# 마스킹 범주가 2종으로 줄어든 뒤(2026-08-12) `mask_text`는 전화·이메일·계좌를 가리지
# 않는다. 그 감수 범위는 **런타임이 LLM에 보내는 것**이고, 수집 도구가 저장소에 커밋되는
# 파일과 터미널에 남기는 것은 별개다(CLAUDE.md 보안·데이터 규칙). 아래 테스트는 그
# 경계를 지키는 장치를 고정한다 — 2종 축소 결정과는 무관하다.

CONTACT_TEXT = (
    "○○구 노인 일자리 안내\n"
    "문의는 02-2100-3456 또는 elder@example.go.kr로 하세요.\n"
    "자부담금은 123-456-789012 계좌로 넣으세요."
)

CONTACT_VALUES = ("02-2100-3456", "elder@example.go.kr", "123-456-789012")

#: 연락처가 하나도 없는 본문 (검출 장치가 오탐으로 막지 않아야 하는 쪽).
PLAIN_TEXT = "안내드립니다. 자세한 내용은 문의 바랍니다."


def test_초안_본문에_연락처가_평문으로_남지_않는다() -> None:
    """초안 파일은 저장소에 커밋된다 — 2종 마스킹만 거친 본문을 그대로 담으면 유출이다."""
    초안 = build_draft(CONTACT_TEXT, document_id="021", source=SOURCE)
    for 값 in CONTACT_VALUES:
        assert 값 not in 초안.document.source_text


def test_초안_제목에_연락처가_평문으로_남지_않는다() -> None:
    """--title을 주지 않으면 제목이 본문 첫 줄에서 온다 (수집 방안 3장 3-b)."""
    초안 = build_draft(
        f"문의 02-2100-3456 elder@example.go.kr\n{CONTACT_TEXT}",
        document_id="021",
        source=SOURCE,
    )
    for 값 in CONTACT_VALUES[:2]:
        assert 값 not in 초안.document.title


def test_저장한_초안_파일에_연락처가_평문으로_남지_않는다(tmp_path: Path) -> None:
    """검출은 자동이어야 한다 — 사람이 절차를 밟았다고 말하는 것으로 통과하면 안 된다."""
    초안 = build_draft(CONTACT_TEXT, document_id="021", source=SOURCE)
    본문 = write_draft(초안, tmp_path).read_text(encoding="utf-8")
    for 값 in CONTACT_VALUES:
        assert 값 not in 본문


def test_미리보기_첫_줄에_연락처가_평문으로_찍히지_않는다() -> None:
    """미리보기는 곧장 표준출력으로 나간다 — 화면·셸 히스토리에 남는다."""
    보고 = preview_source(
        _fetched(
            "문의 02-2100-3456 elder@example.go.kr 계좌 123-456-789012".encode(),
            filename="안내.txt",
        )
    )
    for 값 in CONTACT_VALUES:
        assert 값 not in 보고.units[0].first_line


@pytest.mark.parametrize(
    ("본문", "기대"),
    [
        ("문의 02-2100-3456", {ContactKind.PHONE: 1}),
        ("휴대전화 010-1234-5678", {ContactKind.PHONE: 1}),
        ("대표번호 044.202.3000", {ContactKind.PHONE: 1}),
        ("메일 elder@example.go.kr", {ContactKind.EMAIL: 1}),
        ("계좌 123-456-789012", {ContactKind.ACCOUNT: 1}),
        ("2026년 3월 2일부터 30만 원을 지원합니다.", {}),
    ],
)
def test_연락처_갈래를_건수로_검출한다(본문: str, 기대: dict[ContactKind, int]) -> None:
    """판정 기준은 축소 전 마스킹 정규식과 같다 (docs/golden-collection-plan.md §4.3)."""
    assert scan_contacts(본문) == 기대


def test_계좌_패턴이_전화번호를_겹쳐_집지_않는다() -> None:
    """계좌 패턴이 넓어 전화번호를 함께 문다(§4.3 실측) — 전화를 먼저 지워 한 번만 센다."""
    assert scan_contacts("문의 070-4567-8901") == {ContactKind.PHONE: 1}


def test_가린_자리에_갈래가_남는다() -> None:
    """사람이 무엇을 합성값으로 되돌려야 문서 뜻이 통하는지 판단할 수 있어야 한다."""
    가려진 = redact_contacts("문의는 02-2100-3456입니다.")
    assert 가려진 == f"문의는 {contact_placeholder(ContactKind.PHONE)}입니다."
    assert "[[" not in 가려진  # 마스킹 플레이스홀더와 모양이 겹치면 안 된다


def test_연락처가_없으면_본문을_건드리지_않는다() -> None:
    본문 = "2026년 3월 2일부터 신청하세요. 지원금은 30만 원입니다."
    assert redact_contacts(본문) == 본문


def test_통계는_연락처_건수만_담고_값은_담지_않는다() -> None:
    """통계는 그대로 표준출력으로 나간다 (CollectionStats docstring의 전제)."""
    초안 = build_draft(CONTACT_TEXT, document_id="021", source=SOURCE)
    assert 초안.stats.contact_counts == {
        ContactKind.PHONE: 1,
        ContactKind.EMAIL: 1,
        ContactKind.ACCOUNT: 1,
    }
    직렬화 = 초안.stats.model_dump_json()
    for 값 in CONTACT_VALUES:
        assert 값 not in 직렬화


def test_연락처가_남은_초안은_저장이_막힌다(tmp_path: Path) -> None:
    """`build_draft`의 가림이 나중에 깨져도 조용히 커밋되지는 않게 하는 최종 관문."""
    성한_초안 = build_draft(PLAIN_TEXT, document_id="021", source=SOURCE)
    샌_초안 = GoldenDraft(
        document=성한_초안.document.model_copy(update={"source_text": CONTACT_TEXT}),
        stats=성한_초안.stats,
    )
    with pytest.raises(GoldenCollectionError) as 오류:
        write_draft(샌_초안, tmp_path)
    메시지 = str(오류.value)
    assert "전화번호 1건" in 메시지 and "이메일 1건" in 메시지 and "계좌번호 1건" in 메시지
    for 값 in CONTACT_VALUES:
        assert 값 not in 메시지  # 오류 메시지도 화면에 남는다 — 건수까지만
    assert list(tmp_path.glob("*.json")) == []


def test_제목에_남은_연락처도_저장을_막는다(tmp_path: Path) -> None:
    """--title은 사람이 주는 값이라 `build_draft`의 본문 가림만으로는 닫히지 않는다."""
    성한_초안 = build_draft(PLAIN_TEXT, document_id="021", source=SOURCE)
    샌_초안 = GoldenDraft(
        document=성한_초안.document.model_copy(update={"title": "문의 02-2100-3456"}),
        stats=성한_초안.stats,
    )
    with pytest.raises(GoldenCollectionError):
        write_draft(샌_초안, tmp_path)


def test_출처_메타의_날짜는_저장을_막지_않는다(tmp_path: Path) -> None:
    """수집일(`2026-08-06`)·출처 URL은 문서 본문이 아니다 — 오탐으로 저장을 막으면 안 된다."""
    초안 = build_draft(PLAIN_TEXT, document_id="021", source=SOURCE)
    assert write_draft(초안, tmp_path).exists()


def test_자동_검출은_사람_검수를_대신하지_못한다() -> None:
    """미탐을 코드에도 남긴다 — 이 장치는 본문 육안 훑기를 없애지 않는다.

    괄호 국번과 `내선 1234`는 걸리지 않는다. `contact_counts`가 0건이라는 것이
    "연락처가 없다"는 뜻이 아니라는 근거이며, 정규식을 넓히려면 이 테스트를 **의도적으로**
    고쳐야 한다.
    """
    assert scan_contacts("문의 (044)202-3000") == {}
    assert scan_contacts("문의 내선 1234") == {}


def test_띄어쓴_번호와_붙은_숫자열은_실제로는_걸린다() -> None:
    """§4.3은 이 둘을 미탐으로 적었지만 실측은 다르다 — `[-.\\s]?`가 공백을 받는다.

    문서보다 코드가 실제 판정이므로 관측값을 여기 고정한다. 미탐 목록을 실제보다 넓게
    적어 두면 "어차피 못 잡는다"는 이유로 장치를 건너뛰는 근거가 된다.
    """
    assert scan_contacts("문의 044 202 3000") == {ContactKind.PHONE: 1}
    assert scan_contacts("문의 0442023000") == {ContactKind.PHONE: 1}


def test_초안은_수집본으로_표시되고_제목은_첫_줄에서_온다() -> None:
    초안 = build_draft(html_to_text(PAGE), document_id="021", source=SOURCE)
    assert 초안.document.synthetic is False
    assert 초안.document.title == "기초연금 신청 안내"  # 지정하지 않으면 첫 줄


def test_신호가_없는_본문은_사람이_채울_자리로_남는다() -> None:
    """분류도 팩트도 규칙에 걸리지 않으면 예전처럼 빈 채로 둔다 — 억지로 채우지 않는다."""
    초안 = build_draft(
        "안내드립니다.\n자세한 내용은 문의 바랍니다.", document_id="021", source=SOURCE
    )
    assert 초안.document.required_facts == []
    assert 초안.document.category == DRAFT_CATEGORY
    assert 초안.stats.auto_category is None
    assert 초안.stats.suggested_facts == 0


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


# --------------------------------------------------------------- 웹 호출 (네트워크 없음)


def test_요청_헤더_값은_ascii로_인코딩된다() -> None:
    """헤더에 한글이 섞이면 요청이 나가기도 전에 UnicodeEncodeError로 죽는다 (실사용 크래시).

    httpx는 헤더를 ascii로 인코딩하므로 값에 한글이 들어가면 어떤 URL을 넣어도 실패한다.
    """
    assert FETCH_HEADERS
    for 이름, 값 in FETCH_HEADERS.items():
        assert 이름.isascii() and 값.isascii()
        이름.encode("ascii")
        값.encode("ascii")


@pytest.mark.parametrize(
    "주소",
    [
        "https://blog.naver.com/mohw2016/224348814714",
        "https://m.blog.naver.com/mohw2016/224348814714",
        "https://blog.naver.com/mohw2016/224348814714/",
        "https://blog.naver.com/mohw2016/224348814714?from=postList",
    ],
)
def test_네이버_블로그_주소는_본문_주소로_바뀐다(주소: str) -> None:
    """본문이 iframe(PostView) 안에 있어 원 주소로는 외피 HTML만 받는다."""
    바뀐_주소 = rewrite_source_url(주소)
    assert 바뀐_주소.startswith("https://blog.naver.com/PostView.naver?")
    assert "blogId=mohw2016" in 바뀐_주소
    assert "logNo=224348814714" in 바뀐_주소


@pytest.mark.parametrize(
    "주소",
    [
        "https://example.go.kr/board/view.do?id=1",
        "https://blog.naver.com/mohw2016",  # 글이 아니라 블로그 대문
        "https://blog.naver.com/PostView.naver?blogId=mohw2016&logNo=1",  # 이미 본문 주소
    ],
)
def test_패턴에_맞지_않는_주소는_그대로_둔다(주소: str) -> None:
    assert rewrite_source_url(주소) == 주소


# ------------------------------------------------------------------------- 부분 추출


def _fixture(name: str, content_type: str) -> FetchedDocument:
    return FetchedDocument(
        filename=name, data=(FIXTURES / name).read_bytes(), content_type=content_type
    )


@pytest.mark.parametrize(("값", "기대"), [("12-30", (12, 30)), ("5", (5, 5)), (" 3 - 7 ", (3, 7))])
def test_페이지_범위를_해석한다(값: str, 기대: tuple[int, int]) -> None:
    assert parse_page_range(값) == 기대


@pytest.mark.parametrize("값", ["0-3", "7-3", "-1", "a-b", "3-", "1.5"])
def test_잘못된_페이지_범위는_오류다(값: str) -> None:
    with pytest.raises(GoldenCollectionError):
        parse_page_range(값)


@pytest.mark.parametrize(
    ("값", "기대"), [("0:1500", (0, 1500)), ("500:", (500, None)), (":200", (0, 200))]
)
def test_문자_범위를_해석한다(값: str, 기대: tuple[int, int | None]) -> None:
    assert parse_char_slice(값) == 기대


@pytest.mark.parametrize("값", ["-1:5", "200:100", "100:100", "abc", "100"])
def test_잘못된_문자_범위는_오류다(값: str) -> None:
    with pytest.raises(GoldenCollectionError):
        parse_char_slice(값)


def test_문자_범위로_본문을_잘라낸다() -> None:
    assert apply_char_slice("0123456789", (2, 5)) == "234"
    assert apply_char_slice("0123456789", (7, None)) == "789"


def test_본문_뒤를_가리킨_범위는_오류다() -> None:
    with pytest.raises(GoldenCollectionError):
        apply_char_slice("짧은 본문", (100, None))


def test_pdf_페이지_범위만_추출한다() -> None:
    본문 = extract_source_text(_fixture("sample.pdf", "application/pdf"), pages=(2, 2))
    assert "둘째 쪽 안내문입니다." in 본문
    assert "첫째" not in 본문


def test_페이지_범위를_넘겨도_있는_쪽까지만_뽑는다() -> None:
    """사람이 '뒤쪽 전부'를 넉넉히 적는 흔한 사용법을 오류로 막지 않는다."""
    본문 = extract_source_text(_fixture("sample.pdf", "application/pdf"), pages=(1, 999))
    assert "첫째 쪽 안내문입니다." in 본문 and "둘째 쪽 안내문입니다." in 본문


def test_시작_쪽이_문서_밖이면_오류다() -> None:
    with pytest.raises(DocumentExtractionError):
        extract_source_text(_fixture("sample.pdf", "application/pdf"), pages=(9, 10))


def test_pdf가_아닌_형식에_페이지_범위를_쓰면_오류다() -> None:
    """쪽은 조판 결과라 hwpx·docx에는 같은 단위가 없다."""
    with pytest.raises(GoldenCollectionError) as 오류:
        extract_source_text(_fixture("sample.hwpx", "application/hwpx"), pages=(1, 2))
    assert "--pages" in str(오류.value)


def test_pdf_미리보기는_쪽별_통계를_준다() -> None:
    보고 = preview_source(_fixture("sample.pdf", "application/pdf"))
    assert 보고.unit_name == "페이지"
    assert [단위.index for 단위 in 보고.units] == [1, 2]
    assert 보고.units[0].first_line == "첫째 쪽 안내문입니다."
    assert 보고.total_chars == sum(단위.chars for 단위 in 보고.units)
    assert 보고.units[1].cumulative_chars == 보고.total_chars


def test_hwpx_미리보기는_구역별_통계를_준다() -> None:
    보고 = preview_source(_fixture("sample.hwpx", "application/hwpx"))
    assert 보고.unit_name == "구역"
    assert [단위.index for 단위 in 보고.units] == [1, 2]
    assert 보고.units[1].first_line == "둘째 구역의 문장입니다."


def test_미리보기_첫_줄은_마스킹을_통과한다() -> None:
    """미리보기는 곧장 표준출력으로 나간다 — 마스킹(2종)도 연락처 가림(3종)도 지나야 한다."""
    보고 = preview_source(
        _fetched("등록번호 900101-1234567 (문의 02-2100-3456)".encode(), filename="안내.txt")
    )
    assert 보고.unit_name == "문서"
    assert "900101-1234567" not in 보고.units[0].first_line
    assert "[[주민등록번호1]]" in 보고.units[0].first_line
    assert "02-2100-3456" not in 보고.units[0].first_line
    assert contact_placeholder(ContactKind.PHONE) in 보고.units[0].first_line


def test_미리보기_첫_줄은_길이가_제한된다() -> None:
    보고 = preview_source(_fetched(("가" * 200).encode(), filename="안내.txt"))
    assert len(보고.units[0].first_line) == 40
    assert 보고.units[0].chars == 200


# ------------------------------------------------------------------------- 자동 분류


def test_자동_분류_카테고리는_통제_어휘_안이다() -> None:
    """통제 어휘 밖 값을 내놓으면 초안이 스키마 테스트에 걸려 사람 손을 두 번 타게 된다."""
    assert set(CATEGORY_KEYWORDS) <= ALLOWED_CATEGORIES


@pytest.mark.parametrize(
    ("본문", "기대"),
    [
        ("어르신 예방접종 안내입니다. 보건소에서 백신을 맞으세요.", "보건 안내문"),
        ("임대주택 입주자 모집입니다. 보증금과 월세, 청약 방법을 알려 드립니다.", "주거 안내문"),
        ("실업급여 안내입니다. 고용보험 가입자는 워크넷에서 구직 등록을 하세요.", "고용 안내문"),
        ("전기요금 감면 안내입니다. 도시가스 요금과 난방비를 확인하세요.", "생활요금 안내문"),
    ],
)
def test_대표_본문을_카테고리로_분류한다(본문: str, 기대: str) -> None:
    assert classify_category(본문) == 기대


@pytest.mark.parametrize(
    "본문",
    [
        "오늘 날씨가 참 좋습니다. 산책하기에 알맞은 하루입니다.",  # 무매칭
        "임대주택 입주 안내와 예방접종 보건소 백신 안내를 함께 드립니다.",  # 동점
    ],
)
def test_애매한_본문은_분류하지_않는다(본문: str) -> None:
    assert classify_category(본문) is None


def test_사람이_정한_분류가_자동_분류보다_우선한다() -> None:
    초안 = build_draft(
        "예방접종 안내입니다. 보건소에서 백신을 맞으세요.",
        document_id="021",
        source=SOURCE,
        category="복지 안내문",
    )
    assert 초안.document.category == "복지 안내문"
    assert 초안.stats.auto_category is None


def test_자동_분류_결과가_초안과_통계에_함께_남는다() -> None:
    초안 = build_draft(
        "예방접종 안내입니다. 보건소에서 백신을 맞으세요.", document_id="021", source=SOURCE
    )
    assert 초안.document.category == "보건 안내문"
    assert 초안.stats.auto_category == "보건 안내문"


# --------------------------------------------------------------------- 팩트 후보 추출

FACT_TEXT = (
    "2026년 3월 2일부터 신청합니다.\n"
    "지원금은 30만 원입니다. 자기부담금은 10%입니다.\n"
    "신청은 14일 이내에 해야 합니다.\n"
    "만 65세 이상 어르신은 주민센터로 오세요."
)


def test_날짜_금액_기한_대상_기관을_후보로_뽑는다() -> None:
    후보 = suggest_facts(FACT_TEXT)
    assert "2026년 3월 2일" in 후보
    assert "30만 원" in 후보
    assert "14일 이내" in 후보
    assert "만 65세 이상" in 후보
    assert "주민센터" in 후보


def test_후보는_갈래를_돌아가며_담는다() -> None:
    """앞머리에 날짜가 몰린 문서에서 날짜만 여섯 개 뽑히면 후보로서 쓸모가 없다."""
    본문 = "3월 1일 3월 2일 3월 3일 3월 4일 1만 원 2만 원"
    assert suggest_facts(본문) == ["3월 1일", "1만 원", "3월 2일", "2만 원", "3월 3일", "3월 4일"]


def test_후보는_최대_여섯_개다() -> None:
    본문 = " ".join(f"{달}월 {달}일 {달}만 원" for 달 in range(1, 10))
    assert len(suggest_facts(본문)) == MAX_SUGGESTED_FACTS


def test_후보에_마스킹_대상_패턴이_섞이지_않는다() -> None:
    """마스킹된 본문에서는 플레이스홀더로 바뀌므로 팩트 보존 검사가 영구히 실패한다."""
    마스킹된_본문 = mask_text(
        f"{FACT_TEXT}\n등록번호 900101-1234567, 카드 1234-5678-9012-3456"
    ).masked_text
    후보 = suggest_facts(마스킹된_본문)
    assert 후보
    assert all(not mask_text(항목).items for 항목 in 후보)
    assert all("[[" not in 항목 and "]]" not in 항목 for 항목 in 후보)


def test_후보는_줄바꿈을_넘어가지_않는다() -> None:
    """PDF는 한 구절을 줄바꿈으로 가른다. 개행을 품은 리터럴은 변환문에서 다시 나올 수
    없어 팩트 보존 검사가 항상 실패한다 (실측: "30인\\n이상")."""
    후보 = suggest_facts("정원이 30인\n이상인 시설은 2026년\n3월까지 신고하세요.")
    assert 후보
    assert all("\n" not in 항목 for 항목 in 후보)


def test_후보가_없으면_빈_배열이다() -> None:
    assert suggest_facts("안내드립니다. 자세한 내용은 문의 바랍니다.") == []


def test_후보가_초안의_required_facts에_채워진다() -> None:
    초안 = build_draft(FACT_TEXT, document_id="021", source=SOURCE)
    후보 = [팩트.canonical for 팩트 in 초안.document.required_facts]
    assert 후보 == suggest_facts(초안.document.source_text)
    assert 초안.stats.suggested_facts == len(후보)
    assert all(팩트.canonical in 초안.document.source_text for 팩트 in 초안.document.required_facts)
