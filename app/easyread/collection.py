"""골든셋 수집 초안 생성 — URL·파일 → 텍스트 추출 → 마스킹 → 초안 문서.

`scripts/collect_golden.py`는 이 모듈의 CLI 껍데기다. 판단이 들어가는 로직을 전부
여기 두는 이유는 두 가지다: 스크립트를 import하지 않고 순수 함수로 테스트할 수 있고,
mypy strict가 app/ 나머지와 같은 기준으로 검사한다. 네트워크를 쓰는 함수는
`fetch_url` 하나뿐이라 테스트는 `FetchedDocument`를 직접 만들어 나머지를 검증한다.

**사람이 할 일은 자동화하지 않는다** (docs/golden-collection-plan.md 4장):
공공누리 유형 판단·required_facts 큐레이션·마스킹 결과 확인은 초안 생성 대상이 아니다.
초안의 required_facts는 항상 비고, category 기본값도 통제 어휘 밖의 표시값이라
사람이 손대지 않은 초안은 스키마 테스트가 자동으로 막는다.

보안: 예외 메시지에 문서 본문·파일명을 담지 않는다(app/ingest/extractors.py와 같은
규칙). 초안 **파일**에는 마스킹을 마친 본문이 들어가지만, 호출자가 표준출력으로 옮기지
않도록 통계(`CollectionStats`)를 본문과 분리해 돌려준다.
"""

import json
import re
from collections.abc import Iterable
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

import httpx
from pydantic import BaseModel

from app.easyread.goldenset import GoldenDocument, GoldenSource
from app.exceptions import GoldenCollectionError
from app.ingest.extractors import MAX_UPLOAD_BYTES, extract_text
from app.privacy.masking import MaskCategory, mask_text

#: 사람이 카테고리를 고치기 전까지 붙는 표시값. 통제 어휘
#: (tests/golden/test_schema.py의 ALLOWED_CATEGORIES) 밖의 값이라 초안이 그대로
#: 평가셋에 섞이면 테스트가 실패한다 — 의도된 안전장치다.
DRAFT_CATEGORY = "미분류(사람 확인)"

#: 수집 주체를 상대 서버 로그에서 식별할 수 있게 하는 표시(공공기관 사이트 수집 예의).
USER_AGENT = "easy-doc-golden-collector/0.1 (골든셋 수집 스크립트)"

#: URL 응답 대기 상한(초). 공공기관 사이트는 응답이 느린 편이라 넉넉히 잡는다.
FETCH_TIMEOUT_SECONDS = 30.0

# 본문이 아닌 텍스트를 담는 태그. 걷어내지 않으면 스크립트 코드·메뉴 항목이 본문에
# 섞여 글자 수 통계와 마스킹 결과를 함께 오염시킨다. noscript·template은 화면에
# 그려지지 않는 대체/틀 내용이고, svg 안의 문자는 도형 라벨이라 같이 뺀다.
# head(와 그 안의 title)를 빼는 이유는 문서 제목이 본문 첫 줄로 잡히면 사이트 이름이
# 골든셋 제목이 되기 때문이다 — 첫 줄은 안내문 제목이어야 한다.
_SKIPPED_TAGS = frozenset(
    {"head", "title", "script", "style", "nav", "header", "footer", "noscript", "template", "svg"}
)

# 앞뒤로 줄을 바꿔야 하는 블록 요소. 여기 없는 태그(span·a·strong 등)는 글자를 그대로
# 잇는다 — 인라인 태그마다 줄을 바꾸면 한 문장이 토막 나 문장 길이 검사가 망가진다.
_BLOCK_TAGS = frozenset(
    {
        "address",
        "article",
        "aside",
        "blockquote",
        "br",
        "caption",
        "dd",
        "div",
        "dl",
        "dt",
        "figcaption",
        "figure",
        "form",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "hr",
        "li",
        "main",
        "ol",
        "p",
        "pre",
        "section",
        "table",
        "tbody",
        "td",
        "th",
        "thead",
        "tr",
        "ul",
    }
)

# 닫는 태그가 없는 요소. 스킵 스택에 쌓거나 종료 시 줄을 한 번 더 바꾸면 안 된다.
_VOID_TAGS = frozenset(
    {
        "area",
        "base",
        "br",
        "col",
        "embed",
        "hr",
        "img",
        "input",
        "link",
        "meta",
        "param",
        "source",
        "track",
        "wbr",
    }
)

_HTML_EXTENSIONS = frozenset({".html", ".htm", ".xhtml"})
_PLAIN_EXTENSIONS = frozenset({".txt", ".md"})
_DOCUMENT_EXTENSIONS = frozenset({".docx", ".pdf", ".hwpx"})
_KNOWN_EXTENSIONS = _HTML_EXTENSIONS | _PLAIN_EXTENSIONS | _DOCUMENT_EXTENSIONS

# Content-Type → 확장자. 게시판 첨부는 URL에 확장자가 없는 일이 흔해 보완 수단이 필요하다.
# hwpx는 표준 MIME이 없어 배포처마다 다른 값을 쓴다 — 실제로 관측되는 것들을 함께 둔다.
_CONTENT_TYPE_EXTENSIONS: dict[str, str] = {
    "text/html": ".html",
    "application/xhtml+xml": ".html",
    "text/plain": ".txt",
    "application/pdf": ".pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
    "application/hwpx": ".hwpx",
    "application/haansofthwpx": ".hwpx",
    "application/x-hwpx": ".hwpx",
}

# 줄바꿈을 제외한 공백. \s는 유니코드 공백(비줄바꿈 공백 \xa0 포함)을 모두 잡으므로
# &nbsp;가 풀린 문자도 여기서 보통 공백으로 정리된다.
_INLINE_SPACES = re.compile(r"[^\S\n]+")
_SLUG_SEPARATORS = re.compile(r"[\s_]+")
# \w는 파이썬 str 정규식에서 한글을 포함한다 — 한글 제목을 그대로 파일명에 쓸 수 있다.
_SLUG_DROP = re.compile(r"[^\w-]")
_SLUG_REPEATED_HYPHEN = re.compile(r"-{2,}")
_LEADING_DIGITS = re.compile(r"\d+")

_ID_DIGITS = 3
_MAX_TITLE_CHARS = 60
_MAX_SLUG_CHARS = 40


class _HtmlTextParser(HTMLParser):
    """HTML에서 본문 텍스트만 걷는다 (블록 요소 단위 개행).

    문자 참조는 `convert_charrefs`(기본값 True)에 맡긴다 — `&amp;lt;`처럼 이중으로
    인코딩된 입력에서 직접 치환과 결과가 갈리기 때문이다.

    스킵 태그는 깊이 카운터가 아니라 **이름 스택**으로 센다. 실제 웹 문서는 닫는 태그가
    빠지는 일이 흔해서, 카운터를 쓰면 짝이 안 맞는 `</div>` 하나에 스킵이 영구히 풀리거나
    반대로 문서 끝까지 갇힌다. 이름으로 되감으면 그 자리에서 복구된다.

    줄바꿈은 블록을 만난 자리에서 바로 넣지 않고 **다음 글자가 올 때** 넣는다
    (`_pending_break`). `</p><p>`처럼 블록이 맞닿거나 태그 사이에 들여쓰기 공백만 있는
    흔한 마크업에서 빈 줄이 겹겹이 생기는 것을 막기 위해서다. 결과 모양은 추출기
    (app/ingest/extractors.py)와 같아진다 — 빈 줄 없이 개행 하나로 이어진 텍스트.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._parts: list[str] = []
        self._skipped: list[str] = []
        self._pending_break = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in _VOID_TAGS:
            if not self._skipped and tag in _BLOCK_TAGS:
                self._pending_break = True
            return
        if tag in _SKIPPED_TAGS:
            self._skipped.append(tag)
            return
        if not self._skipped and tag in _BLOCK_TAGS:
            self._pending_break = True

    def handle_endtag(self, tag: str) -> None:
        if tag in _VOID_TAGS:
            return
        if tag in self._skipped:
            # 안쪽에서 닫히지 않은 스킵 태그가 있어도 여기서 함께 정리된다.
            del self._skipped[len(self._skipped) - 1 - self._skipped[::-1].index(tag) :]
            return
        if not self._skipped and tag in _BLOCK_TAGS:
            self._pending_break = True

    def handle_data(self, data: str) -> None:
        if self._skipped:
            return
        if self._pending_break:
            if not data.strip():
                return  # 블록 경계 사이의 공백·들여쓰기는 마크업 서식일 뿐이다
            self._pending_break = False
            if self._parts:
                self._parts.append("\n")
        self._parts.append(data)

    def text(self) -> str:
        return "".join(self._parts)


@dataclass(frozen=True)
class FetchedDocument:
    """수집한 원본 한 건 (URL 응답 또는 로컬 파일).

    filename은 형식 판별에만 쓰고 로그·오류 메시지에 넣지 않는다 — 파일명 자체가
    개인정보일 수 있다(`홍길동_주민등록등본.pdf`).
    """

    filename: str
    data: bytes
    content_type: str = ""
    #: 응답 헤더에 선언된 문자 인코딩. 추측값은 담지 않는다(없으면 None).
    encoding: str | None = None


class CollectionStats(BaseModel):
    """초안 통계.

    본문·마스킹 원문을 담지 않으므로 그대로 표준출력에 써도 안전하다
    (CLAUDE.md 보안·데이터 규칙).
    """

    source_chars: int
    masked_counts: dict[MaskCategory, int]

    @property
    def masked_total(self) -> int:
        return sum(self.masked_counts.values())


class GoldenDraft(BaseModel):
    """생성된 초안 한 건 (문서 + 통계)."""

    document: GoldenDocument
    stats: CollectionStats

    @property
    def filename(self) -> str:
        """`NNN-슬러그.json` — 기존 골든셋 문서 파일명 규칙과 같다."""
        return f"{self.document.id}-{slugify(self.document.title)}.json"


def normalize_text(text: str) -> str:
    """줄 단위 공백과 중복 빈 줄을 정리한다.

    줄 안의 연속 공백은 하나로 줄이고 줄 끝 공백을 턴다. 빈 줄이 이어지면 하나만
    남겨 문단 경계는 살린다 — HTML은 블록 요소마다 개행이 겹치고, 오피스 문서 추출은
    빈 문단을 잔뜩 만들어낸다.
    """
    unified = text.replace("\r\n", "\n").replace("\r", "\n")
    normalized: list[str] = []
    for raw_line in unified.split("\n"):
        line = _INLINE_SPACES.sub(" ", raw_line).strip()
        if line or (normalized and normalized[-1]):
            normalized.append(line)
    while normalized and not normalized[-1]:
        normalized.pop()
    return "\n".join(normalized)


def html_to_text(html: str) -> str:
    """HTML 문자열에서 본문 텍스트를 뽑는다.

    bs4 등 새 의존성 없이 표준 라이브러리 `html.parser`만 쓴다. 대신 시맨틱 판단
    (본문 영역 추정 등)은 하지 않으므로, 메뉴·푸터가 아닌 자리에 있는 잡음은 사람이
    초안에서 걷어낸다.
    """
    parser = _HtmlTextParser()
    parser.feed(html)
    parser.close()
    return normalize_text(parser.text())


def decode_text(data: bytes, encoding: str | None = None) -> str:
    """바이트를 문자열로 푼다: 선언된 인코딩 → UTF-8 → CP949 순.

    CP949를 남겨 두는 이유는 국내 공공기관 페이지·첨부에 EUC-KR 배포가 아직 남아
    있어서다. 셋 다 실패하면 대체 문자로 읽어 수집을 계속한다 — 여기서 멈추는 것보다
    사람이 초안에서 깨진 자리를 보고 판단하는 편이 낫다.
    """
    for candidate in (encoding, "utf-8", "cp949"):
        if candidate:
            try:
                return data.decode(candidate)
            except (UnicodeDecodeError, LookupError):
                continue
    return data.decode("utf-8", errors="replace")


def _resolve_extension(fetched: FetchedDocument) -> str:
    """처리 형식을 정한다: 파일명 확장자 우선, 아니면 Content-Type으로 보완.

    한쪽만 믿을 수 없다. 게시판 상세 페이지는 `view.do`처럼 확장자가 형식과 무관하고,
    첨부 다운로드는 Content-Type이 `application/octet-stream`인 일이 흔하다.
    """
    from_name = PurePosixPath(fetched.filename).suffix.lower()
    media_type = fetched.content_type.split(";")[0].strip().lower()
    from_type = _CONTENT_TYPE_EXTENSIONS.get(media_type, "")
    for candidate in (from_name, from_type):
        if candidate in _KNOWN_EXTENSIONS:
            return candidate
    return from_name or from_type


def extract_source_text(fetched: FetchedDocument) -> str:
    """수집한 원본에서 본문 텍스트를 뽑아 정규화한다.

    Raises:
        GoldenCollectionError: 지원하지 않는 형식이거나 본문이 비었다.
        DocumentExtractionError: 문서 파서가 거부했다 (손상·암호·크기 초과).
    """
    extension = _resolve_extension(fetched)
    if extension in _HTML_EXTENSIONS:
        text = html_to_text(decode_text(fetched.data, fetched.encoding))
    elif extension in _PLAIN_EXTENSIONS:
        text = normalize_text(decode_text(fetched.data, fetched.encoding))
    elif extension in _DOCUMENT_EXTENSIONS:
        # 실제 파일명 대신 형식만 넘긴다 — 추출기는 파일명을 확장자 판별에만 쓰는데,
        # 원본 파일명이 예외·로그 경로로 흘러갈 여지를 애초에 없앤다.
        text = normalize_text(extract_text(f"document{extension}", fetched.data))
    else:
        raise GoldenCollectionError("지원하지 않는 형식입니다 (html, txt, md, docx, pdf, hwpx)")
    if not text:
        raise GoldenCollectionError("본문 텍스트를 찾지 못했습니다")
    return text


def fetch_url(url: str, *, timeout: float = FETCH_TIMEOUT_SECONDS) -> FetchedDocument:
    """URL 하나를 받아 온다. **이 모듈에서 네트워크를 쓰는 유일한 함수다.**

    리다이렉트를 따라가는 이유는 공공기관 안내 페이지에 www·https 리다이렉트가 흔해서다.
    오류 메시지에는 응답 본문을 넣지 않고 예외 종류와 상태 코드만 남긴다.
    """
    try:
        response = httpx.get(
            url,
            follow_redirects=True,
            timeout=timeout,
            headers={"User-Agent": USER_AGENT},
        )
        response.raise_for_status()
    except httpx.HTTPStatusError as error:
        raise GoldenCollectionError(
            f"URL 응답이 오류입니다 (HTTP {error.response.status_code})"
        ) from None
    except httpx.HTTPError as error:
        raise GoldenCollectionError(f"URL을 가져오지 못했습니다 ({type(error).__name__})") from None

    if len(response.content) > MAX_UPLOAD_BYTES:
        raise GoldenCollectionError(
            f"내려받은 파일이 너무 큽니다 (최대 {MAX_UPLOAD_BYTES // (1024 * 1024)}MB)"
        )
    return FetchedDocument(
        filename=PurePosixPath(urlparse(str(response.url)).path).name,
        data=response.content,
        content_type=response.headers.get("content-type", ""),
        encoding=response.charset_encoding,
    )


def read_local_file(path: Path) -> FetchedDocument:
    """로컬 파일을 읽어 온다 (형식은 확장자로 판별한다)."""
    try:
        data = path.read_bytes()
    except OSError as error:
        # 경로에 개인정보가 섞일 수 있어 예외 종류만 남긴다.
        raise GoldenCollectionError(f"파일을 읽을 수 없습니다 ({type(error).__name__})") from None
    return FetchedDocument(filename=path.name, data=data)


def slugify(title: str) -> str:
    """제목을 파일명 조각으로 바꾼다 (공백 → `-`, 파일명에 못 쓰는 문자 제거)."""
    slug = _SLUG_SEPARATORS.sub("-", title.strip())
    slug = _SLUG_DROP.sub("", slug)
    slug = _SLUG_REPEATED_HYPHEN.sub("-", slug).strip("-")
    return slug[:_MAX_SLUG_CHARS].strip("-") or "초안"


def _document_number(path: Path) -> int:
    """문서 JSON에서 id 숫자를 읽는다. 읽을 수 없으면 파일명 앞 숫자로 대신한다.

    사람이 편집 중인 초안은 잠시 깨진 JSON일 수 있는데, 그 파일의 번호를 놓치면
    다음 수집이 같은 id를 다시 발급해 조용히 충돌한다.
    """
    try:
        payload: object = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        payload = None
    identifier = payload.get("id") if isinstance(payload, dict) else None
    for candidate in (identifier if isinstance(identifier, str) else "", path.name):
        match = _LEADING_DIGITS.match(candidate)
        if match:
            return int(match.group())
    return 0


def next_document_id(directories: Iterable[Path]) -> str:
    """주어진 디렉터리들을 통틀어 가장 큰 id + 1을 3자리로 돌려준다.

    편입 대기 중인 초안 디렉터리까지 함께 훑어야 연속 수집이 같은 번호를 두 번 쓰지 않는다.
    """
    highest = 0
    for directory in directories:
        if not directory.is_dir():
            continue
        for path in directory.glob("*.json"):
            highest = max(highest, _document_number(path))
    return f"{highest + 1:0{_ID_DIGITS}d}"


def _title_from_text(text: str) -> str:
    """첫 줄을 제목으로 쓴다 (안내문은 첫 줄이 제목인 경우가 대부분)."""
    first_line = next((line for line in text.split("\n") if line), "")
    return first_line[:_MAX_TITLE_CHARS] or "제목 미확인"


def build_draft(
    text: str,
    *,
    document_id: str,
    source: GoldenSource,
    title: str | None = None,
    category: str | None = None,
) -> GoldenDraft:
    """추출한 텍스트를 마스킹해 골든셋 초안 문서로 만든다.

    required_facts는 항상 비운다 — 어떤 정보가 반드시 남아야 하는지는 문서를 읽은
    사람이 판단할 몫이다(수집 방안 4장). 제목을 따로 주지 않으면 **마스킹 후** 본문의
    첫 줄에서 뽑는다: 원문에서 뽑으면 제목·파일명 경로로 개인정보가 새어 나갈 수 있다.

    Raises:
        GoldenCollectionError: 정규화 결과 본문이 비었다.
    """
    normalized = normalize_text(text)
    if not normalized:
        raise GoldenCollectionError("본문 텍스트가 비어 있습니다")

    masked = mask_text(normalized)
    counts: dict[MaskCategory, int] = {}
    for item in masked.items:
        counts[item.category] = counts.get(item.category, 0) + 1

    given_title = (title or "").strip()
    given_category = (category or "").strip()
    document = GoldenDocument(
        id=document_id,
        title=given_title or _title_from_text(masked.masked_text),
        category=given_category or DRAFT_CATEGORY,
        synthetic=False,
        source_text=masked.masked_text,
        required_facts=[],
        source=source,
    )
    return GoldenDraft(
        document=document,
        stats=CollectionStats(source_chars=len(document.source_text), masked_counts=counts),
    )


def write_draft(draft: GoldenDraft, directory: Path) -> Path:
    """초안을 `NNN-슬러그.json`으로 저장하고 경로를 돌려준다.

    tests/golden/documents가 아니라 초안 디렉터리에 쓴다 — required_facts 큐레이션과
    이용 조건 확인을 거치지 않은 문서가 평가셋에 섞이면 통과율이 사람 손을 타지 않은
    채로 움직인다.
    """
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / draft.filename
    # exclude_none: 로컬 파일 수집이면 source.url이 없다. null을 남기는 것보다
    # 필드를 빼는 편이 손으로 채워야 할 자리와 구분된다.
    payload = draft.document.model_dump(mode="json", exclude_none=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path
