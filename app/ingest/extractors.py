"""업로드 파일 → 평문 텍스트 추출.

지원 형식은 docx·pdf·hwpx다. 구버전 hwp(바이너리 포맷)는 이번 범위 밖이라
`UnsupportedFormatError`로 명시적으로 거부한다 — 베스트에포트 파싱은 후속 과제.

**신뢰할 수 없는 입력을 다루는 모듈이라는 점**이 설계를 지배한다.

- 예외 메시지에 파일명·본문 조각을 넣지 않는다. 파일명 자체가 개인정보일 수 있고
  (예: `홍길동_주민등록등본.pdf`), 라이브러리 예외에는 임시 경로나 원문 일부가
  섞여 들어온다. 그래서 라이브러리 예외는 전부 `from None`으로 끊고 형식명만 담은
  고정 메시지로 바꾼다. `__suppress_context__`가 서므로 traceback 출력에도 원본
  메시지가 따라붙지 않는다(객체 참조는 `__context__`에 남지만 출력되지 않는다).
- 로그에는 형식명·바이트 길이·사유 코드만 남긴다. 파일명·본문·라이브러리 예외
  메시지는 남기지 않는다(`_log_failure` 참고).
- 압축 폭탄(docx·hwpx 둘 다 zip 컨테이너다)은 **디스패치 지점에서 예산 읽기로**
  막는다. XML 엔티티 폭탄은 파서 수준에서 DTD를 거부해 막는다.
  `_ensure_zip_within_budget`·`_hwpx_blocks` 주석 참고.

추출 결과의 모양은 형식과 무관하게 하나로 맞춘다: **공백뿐인 줄 없이, 문단·표 셀·
페이지가 개행 하나로 이어진 텍스트**. 후속 단계(마스킹·프롬프트)가 형식별 분기 없이
같은 입력을 받게 하기 위해서다.
"""

import io
import logging
import re
import zipfile
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass
from pathlib import PurePosixPath
from xml.parsers import expat

import docx
from docx.document import Document
from docx.oxml.xmlchemy import BaseOxmlElement
from pypdf import PdfReader
from pypdf.errors import FileNotDecryptedError

from app.exceptions import DocumentExtractionError, UnsupportedFormatError

_logger = logging.getLogger(__name__)

# 업로드 크기 상한. 실제 검사는 API 계층에서 한다 (여기서는 기준값만 정의).
MAX_UPLOAD_BYTES = 10 * 1024 * 1024

# 추출 결과 길이 상한 (공백 포함 문자 수). 500크레딧(1,000자=1크레딧) 상당으로,
# 실제 공공기관 안내문 대비 넉넉한 여유값이다. 크기 상한만으로는 부족하다 —
# 마크업 대비 본문 비율이 극단적인 문서를 만들면 0.14MB 업로드가 900만 자가 된다.
MAX_EXTRACTED_CHARS = 500_000

# zip 컨테이너(docx·hwpx)를 풀었을 때 허용하는 총 크기 상한. 압축 폭탄 방어 —
# deflate는 반복 바이트를 1000:1 이상으로 줄이므로, 상한 안의 업로드도 수 GB로 부풀어
# 메모리를 고갈시킬 수 있다. 업로드 상한의 5배: 마크업이 본문보다 훨씬 긴 오피스 포맷의
# 특성을 감안한 여유값이다.
_MAX_UNCOMPRESSED_BYTES = 5 * MAX_UPLOAD_BYTES

# OWPML 패키지에서 본문을 담는 항목. 번호가 구역 순서다.
_SECTION_NAME = re.compile(r"Contents/section(\d+)\.xml")

# OLE2 복합 문서 매직. 암호가 걸린 OOXML도, 구버전 .doc도 zip이 아니라 OLE2다.
_OLE2_MAGIC = b"\xd0\xcf\x11\xe0"

# OLE2 디렉터리의 스트림 이름(UTF-16LE 저장). 둘 중 무엇이 있는지로 원인을 가른다.
_OLE2_ENCRYPTED_STREAM = "EncryptedPackage".encode("utf-16-le")
_OLE2_WORD_STREAM = "WordDocument".encode("utf-16-le")

# expat 네임스페이스 구분자. XML 이름에 나올 수 없는 문자를 쓴다.
_NS_SEP = "\x01"

# 압축 폭탄 검사용 읽기 단위. 검사 단계는 **바이트 수만 세면 되므로** 내용을 들고
# 있지 않는다. 한 번에 예산만큼 읽으면 검사 자체가 예산 크기의 메모리를 쓰게 된다.
_COUNT_CHUNK_BYTES = 64 * 1024

# zip 계층에서 실제로 나올 수 있는 예외들. 여기만 좁혀 잡는다 — 나머지(AttributeError
# 등)는 우리 코드 버그이므로 500으로 드러나야지, 사용자 입력 탓으로 위장되면 안 된다.
_ZIP_ERRORS = (zipfile.BadZipFile, zipfile.LargeZipFile, OSError, EOFError, ValueError)

_ENCRYPTED_MESSAGE = "암호가 설정된 파일입니다 (암호를 풀고 다시 올려주세요)"


class _DtdNotAllowed(Exception):
    """XML에 DTD 선언이 있어 파싱을 중단했다 (모듈 내부 신호용)."""


def _log_failure(format_name: str, size: int, reason: str) -> None:
    """추출 실패 사실만 남긴다.

    남기는 것은 형식명·바이트 길이·사유 코드뿐이다. 파일명(개인정보 가능)·문서 본문·
    라이브러리 예외 메시지(임시 경로가 섞인다)는 절대 남기지 않는다.
    """
    _logger.warning("문서 추출 실패: format=%s bytes=%d reason=%s", format_name, size, reason)


def _broken(format_name: str, size: int, cause: BaseException) -> DocumentExtractionError:
    """손상 파일 예외를 만들고 예외 **타입**만 로깅한다.

    `size`는 항상 업로드 파일 전체 길이다 — 로그의 bytes 필드가 자리마다 다른 것을
    가리키면 집계할 수 없다.

    라이브러리 예외를 넓게 잡는 자리라 로그가 유일한 단서다 — 우리 코드 버그가 조용히
    "파일이 손상됐습니다"로 둔갑하는 것을 막으려면 타입이 기록에 남아야 한다.
    """
    _log_failure(format_name, size, type(cause).__name__)
    return DocumentExtractionError(f"{format_name} 파일을 읽을 수 없습니다 (파일이 손상되었습니다)")


def _diagnose_ole2(data: bytes, format_name: str) -> DocumentExtractionError:
    """zip이어야 할 자리에 OLE2 복합 문서가 온 이유를 가려낸다.

    두 가지가 섞여 들어온다: 암호가 걸린 OOXML(본문이 `EncryptedPackage` 스트림으로
    들어간다)과, 구버전 `.doc`를 확장자만 바꿔 올린 경우(`WordDocument` 스트림).
    안내가 같으면 후자의 사용자는 있지도 않은 암호를 찾아 헤맨다.

    olefile 의존성을 더하지 않고 스트림 이름을 바이트로 찾는다 — OLE2 디렉터리는 이름을
    UTF-16LE로 저장하므로 부분 문자열 검색으로 충분히 갈린다. 둘 다 못 찾으면 단정하지
    않고 두 가능성을 함께 안내한다.
    """
    if _OLE2_ENCRYPTED_STREAM in data:
        _log_failure(format_name, len(data), "encrypted_container")
        return DocumentExtractionError(_ENCRYPTED_MESSAGE)
    if _OLE2_WORD_STREAM in data:
        _log_failure(format_name, len(data), "legacy_ole2_document")
        return DocumentExtractionError(
            "구버전 doc 형식은 지원하지 않습니다 (docx로 다시 저장해 올려주세요)"
        )
    _log_failure(format_name, len(data), "ole2_container")
    return DocumentExtractionError("암호가 설정되었거나 지원하지 않는 구형식 파일입니다")


def _join_blocks(blocks: Iterable[str]) -> str:
    """블록(문단·표 셀·페이지) 텍스트를 정규화해 개행 하나로 잇는다.

    줄 단위로 좌우 공백을 털고 빈 줄을 버린다. 워드·PDF는 빈 문단과 줄 끝 공백을
    잔뜩 만들어내는데, 그대로 두면 프롬프트 토큰만 먹고 문단 경계도 흐려진다.
    """
    lines = [
        stripped for block in blocks for line in block.splitlines() if (stripped := line.strip())
    ]
    return "\n".join(lines)


def _ensure_zip_within_budget(data: bytes, format_name: str) -> None:
    """zip 항목 전체를 예산 안에서 실제로 풀어 보고, 초과하면 거부한다.

    **헤더의 선언 크기는 상한으로 쓸 수 없다.** ZipExtFile은 최대 2GB(`MAX_N`) 청크로
    먼저 압축을 풀고 선언 크기·CRC는 그 뒤에 검사한다. 그래서 선언값을 1KB로 위조한
    94KB짜리 파일이 힙 141MB를 먼저 할당시킨다(실측). 믿을 수 있는 건 **우리가 남은
    예산까지만 읽은 바이트**뿐이다 — `read(n)`은 압축 해제를 n바이트로 묶는다.

    python-docx처럼 압축 해제를 스스로 하는 라이브러리에 넘기기 전에 이 검사를 통과해야
    한다. 통과했다면 실제 해제량이 예산 안이라는 뜻이므로 이후 재파싱도 안전하다.

    검사는 바이트 **수**만 세면 되므로 조각을 들고 있지 않는다. 한 번에 예산만큼 읽으면
    검사가 예산 크기(수십 MB)의 메모리를 스스로 쓰게 된다.
    """
    if data.startswith(_OLE2_MAGIC):
        # zip이 아니라 OLE2다. "손상" 안내보다 원인을 짚어 주는 편이 훨씬 낫다.
        raise _diagnose_ole2(data, format_name)

    budget = _MAX_UNCOMPRESSED_BYTES
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                with archive.open(info) as member:
                    while chunk := member.read(min(_COUNT_CHUNK_BYTES, budget + 1)):
                        budget -= len(chunk)
                        if budget < 0:
                            break
                if budget < 0:
                    break
    except _ZIP_ERRORS as exc:
        raise _broken(format_name, len(data), exc) from None
    if budget < 0:
        _log_failure(format_name, len(data), "uncompressed_too_large")
        raise DocumentExtractionError(f"{format_name} 파일이 너무 큽니다")


def _element_blocks(root: BaseOxmlElement) -> Iterator[str]:
    """OOXML 조각을 **문서 순서대로** 훑어 문단 단위 텍스트를 흘린다.

    `w:p`를 만나면 블록을 끊고 `w:t`의 문자를 모은다. 문단·표를 각각 순회하는 대신
    XML을 직접 훑는 이유:

    - 표가 본문 안 제자리에 남는다 (문단을 먼저 모으면 표가 문서 끝으로 밀린다).
    - 텍스트박스(`w:txbxContent`)·중첩 표·SDT(콘텐츠 컨트롤) 안의 문단도 딸려 온다 —
      python-docx의 `paragraphs`/`tables`로는 보이지 않는 자리다.
    - 변경 추적에서 **삽입문(`w:ins`)은 포함되고 삭제문은 빠진다.** 삭제된 글자는
      `w:t`가 아니라 `w:delText`에 담기므로 태그 이름만으로 자연히 갈린다.

    네임스페이스 URI가 아니라 로컬 이름으로 판별해 `a:t`(도형 텍스트)·`m:t`(수식)까지
    함께 걷는다. 주석·처리 명령의 tag는 문자열이 아니라 함수라 자연히 걸러진다.

    `iter()`로 전부 훑지 않고 스택으로 내려가는 이유는 **`mc:Fallback`에서 하강을 멈추기**
    위해서다. 워드 2010+는 텍스트박스 하나를 `mc:AlternateContent` 아래 `mc:Choice`
    (DrawingML)와 `mc:Fallback`(VML) 두 벌로 저장한다. 양쪽을 다 걷으면 같은 문구가
    정확히 두 번 나와 크레딧이 두 배로 청구되고 프롬프트와 마스킹 결과까지 오염된다.
    OOXML 규격상 `mc:AlternateContent`에는 `mc:Choice`가 최소 하나 있으므로 Fallback을
    버려도 내용이 사라지지 않는다.
    """
    current: list[str] = []
    stack: list[BaseOxmlElement] = [root]
    while stack:
        element = stack.pop()
        local_name = str(element.tag).rpartition("}")[2]
        if local_name == "Fallback":
            continue
        if local_name == "p":
            yield "".join(current)
            current = []
        elif local_name == "t":
            current.append(element.text or "")
        # 자식을 역순으로 쌓아야 pop 순서가 문서 순서가 된다.
        stack.extend(reversed(list(element)))
    yield "".join(current)


def _docx_blocks(document: Document) -> Iterator[str]:
    """본문 → 구역별 머리글·바닥글 순서로 텍스트를 흘린다.

    머리글·바닥글이 앞 구역을 물려받는 경우(`is_linked_to_previous`)는 건너뛴다 —
    물려받은 쪽까지 걷으면 같은 문구가 구역 수만큼 반복된다.

    한계: 짝수 쪽·첫 쪽 전용 머리글(`even_page_header`·`first_page_header`)과 각주·
    미주·주석은 걷지 않는다. 쉬운 글 변환 입력으로는 본문이 핵심이라 감수한다.
    """
    yield from _element_blocks(document.element.body)
    for section in document.sections:
        for part in (section.header, section.footer):
            if part.is_linked_to_previous:
                continue
            # `_element`는 비공개지만 대안이 없다. 공개 API(`paragraphs`/`tables`)로는
            # 머리글 안의 텍스트박스·SDT를 걷지 못해 본문과 규칙이 갈린다. python-docx는
            # 상한을 걸어 고정했으므로(pyproject) 업그레이드가 조용히 이 접근을 깨지 않는다.
            yield from _element_blocks(part._element)


def _extract_docx(data: bytes) -> str:
    """python-docx로 본문·표·머리글·바닥글 텍스트를 문서 순서대로 뽑는다."""
    try:
        document = docx.Document(io.BytesIO(data))
        # 지연 파싱 구간을 try 안에서 소진한다 — 밖에서 터지면 예외가 그대로 새어 나간다.
        blocks = list(_docx_blocks(document))
    except Exception as exc:
        raise _broken("docx", len(data), exc) from None
    return _join_blocks(blocks)


def _extract_pdf(data: bytes) -> str:
    """pypdf로 페이지별 텍스트를 뽑아 잇는다.

    한계: 쪽 경계에서 잘린 문장은 이어 붙지 않는다. 앞 쪽 마지막 줄과 다음 쪽 첫 줄이
    한 문장이어도 개행으로 갈라진 채 남는다. 머리글·바닥글·쪽 번호가 본문과 섞여
    들어오는 PDF 특성상 '문장이 이어지는지'를 신뢰성 있게 판정하기 어려워, 잘못 이으면
    오히려 원문을 훼손한다. 레이아웃 분석이 필요한 별도 과제로 남긴다.
    """
    try:
        reader = PdfReader(io.BytesIO(data))
    except Exception as exc:
        raise _broken("pdf", len(data), exc) from None

    # `is_encrypted`로 미리 거르지 않는다. 인쇄·복사만 제한한 소유자 암호 PDF도 True인데,
    # 그런 파일은 열람이 자유롭고(사용자 암호가 빈 문자열) 실제로 잘 읽힌다. 공공기관
    # 배포 문서에 흔한 형태라 미리 막으면 정상 파일을 거부하게 된다. pypdf는 읽을 때 빈
    # 암호를 자동으로 시도하므로, 진짜로 암호가 필요한 파일만 여기서 걸린다.
    try:
        pages = [page.extract_text() for page in reader.pages]
    except FileNotDecryptedError:
        _log_failure("pdf", len(data), "encrypted")
        raise DocumentExtractionError(_ENCRYPTED_MESSAGE) from None
    except Exception as exc:
        raise _broken("pdf", len(data), exc) from None
    if not pages:
        _log_failure("pdf", len(data), "no_pages")
        raise DocumentExtractionError("페이지가 없는 PDF입니다")

    text = _join_blocks(pages)
    if not text:
        # 텍스트 레이어가 없는 스캔 PDF. 손상과 구분되는 상태이므로 안내를 달리한다.
        _log_failure("pdf", len(data), "no_text_layer")
        raise DocumentExtractionError("텍스트를 추출할 수 없습니다 (스캔 PDF는 지원 예정)")
    return text


def _read_hwpx_sections(data: bytes) -> list[bytes]:
    """hwpx(zip)에서 구역 XML을 번호 순서로 읽는다.

    zip 기록 순서는 믿지 않고 `section{N}.xml`의 N으로 정렬한다. 크기도 헤더 선언값이
    아니라 실제 읽은 바이트로 세고 남은 예산까지만 읽는다 — 디스패치에서 이미 전체
    예산을 검사했지만, 이 함수 단독으로도 안전하도록 남겨 둔 이중 방어다.
    """
    budget = _MAX_UNCOMPRESSED_BYTES
    sections: list[bytes] = []
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = sorted(
                (int(match.group(1)), match.group(0))
                for name in archive.namelist()
                if (match := _SECTION_NAME.fullmatch(name))
            )
            for _, name in names:
                with archive.open(name) as member:
                    raw = member.read(budget + 1)
                sections.append(raw)
                budget -= len(raw)
                if budget < 0:
                    break
    except _ZIP_ERRORS as exc:
        raise _broken("hwpx", len(data), exc) from None
    if budget < 0:
        _log_failure("hwpx", len(data), "uncompressed_too_large")
        raise DocumentExtractionError("hwpx 파일이 너무 큽니다")
    return sections


def _hwpx_blocks(section: bytes, file_size: int) -> list[str]:
    """OWPML 구역 XML에서 문단 단위 텍스트를 뽑는다.

    구조(KS X 6101): 구역 `<hs:sec>` > 문단 `<hp:p>` > 글자 조각 `<hp:run>/<hp:t>`.
    한 문단이 서식 때문에 여러 `<hp:t>`로 쪼개지므로 조각은 구분자 없이 잇고,
    `<hp:p>`를 만날 때만 블록을 끊는다. 네임스페이스 URI가 버전마다 달라 접두사가
    아니라 로컬 이름으로 판별한다.

    ElementTree 대신 expat을 직접 쓰는 이유는 **DTD를 파서 수준에서 거부**하기
    위해서다. ElementTree는 내부 엔티티를 그대로 펼치므로("billion laughs") 방어가
    필요한데, 본문을 `<!DOCTYPE` 바이트로 훑는 방식은 UTF-16으로 인코딩하면 그대로
    뚫린다(실측). expat의 `StartDoctypeDeclHandler`는 인코딩과 무관하게 걸리고,
    주석 안의 문자열을 DTD로 오인하지도 않는다. defusedxml 의존성은 추가하지 않는다.

    한계: 표 셀 안의 중첩 문단은 바깥 문단과 별도 블록이 되며(원하는 동작), 바깥 문단이
    중첩 문단 뒤에 텍스트를 더 가지면 그 텍스트는 중첩 블록에 붙는다. 실제 hwpx에서
    표를 담은 문단은 자체 텍스트가 없어 문제되지 않는다.
    """
    blocks: list[str] = []
    current: list[str] = []
    text_depth = 0

    def reject_doctype(name: str, sysid: str | None, pubid: str | None, subset: bool) -> None:
        raise _DtdNotAllowed

    def start_element(name: str, attrs: dict[str, str]) -> None:
        nonlocal text_depth
        local_name = name.rpartition(_NS_SEP)[2]
        if local_name == "p":
            blocks.append("".join(current))
            current.clear()
        elif local_name == "t":
            text_depth += 1

    def end_element(name: str) -> None:
        nonlocal text_depth
        if name.rpartition(_NS_SEP)[2] == "t":
            text_depth -= 1

    def characters(data: str) -> None:
        if text_depth:
            current.append(data)

    parser = expat.ParserCreate(namespace_separator=_NS_SEP)
    parser.StartDoctypeDeclHandler = reject_doctype
    parser.StartElementHandler = start_element
    parser.EndElementHandler = end_element
    parser.CharacterDataHandler = characters
    try:
        parser.Parse(section, True)
    except _DtdNotAllowed:
        _log_failure("hwpx", file_size, "dtd_declaration")
        raise DocumentExtractionError(
            "hwpx 파일을 읽을 수 없습니다 (DTD 선언은 허용하지 않습니다)"
        ) from None
    except expat.ExpatError as exc:
        raise _broken("hwpx", file_size, exc) from None

    blocks.append("".join(current))
    return blocks


def _extract_hwpx(data: bytes) -> str:
    """zip + expat으로 OWPML 본문 텍스트를 뽑는다."""
    sections = _read_hwpx_sections(data)
    if not sections:
        # 구역이 하나도 없으면 hwpx 패키지가 아니거나 껍데기다.
        _log_failure("hwpx", len(data), "no_sections")
        raise DocumentExtractionError("hwpx 파일을 읽을 수 없습니다 (본문 구역이 없습니다)")
    return _join_blocks(block for section in sections for block in _hwpx_blocks(section, len(data)))


@dataclass(frozen=True)
class _Format:
    """형식별 처리 정의.

    `is_zip`을 여기 두는 이유는 압축 폭탄 방어를 디스패치 지점에 모으기 위해서다 —
    새 zip 계열 형식(pptx·xlsx 등)을 등록하면 방어가 자동으로 적용된다.
    """

    name: str
    extract: Callable[[bytes], str]
    is_zip: bool


_FORMATS: dict[str, _Format] = {
    ".docx": _Format("docx", _extract_docx, is_zip=True),
    ".pdf": _Format("pdf", _extract_pdf, is_zip=False),
    ".hwpx": _Format("hwpx", _extract_hwpx, is_zip=True),
}


def extract_text(filename: str, data: bytes) -> str:
    """업로드 파일에서 본문 텍스트를 뽑는다.

    확장자로만 형식을 판별한다(내용 스니핑 없음). 대소문자는 가리지 않는다.

    **동기 CPU 바운드 호출이며 이벤트 루프를 블로킹한다.** 큰 문서는 수 초가 걸릴 수
    있으므로 async 컨텍스트에서 직접 부르지 말고, 스레드로 오프로드하거나
    (`asyncio.to_thread`) arq 워커에서 호출할 것.

    Raises:
        UnsupportedFormatError: 지원하지 않는 확장자 (구버전 hwp 포함).
        DocumentExtractionError: 파일이 손상·암호화됐거나, 추출 가능한 텍스트가
            없거나, 압축 해제량·추출 길이가 상한을 넘었다.
    """
    extension = PurePosixPath(filename).suffix.lower()
    document_format = _FORMATS.get(extension)
    if document_format is None:
        raise UnsupportedFormatError("지원 형식: docx, pdf, hwpx")

    if document_format.is_zip:
        # 파서에 넘기기 전에 압축 폭탄을 걸러낸다. 형식별 파서가 스스로 압축을 푸는
        # 경우(python-docx)에는 이 지점이 유일한 방어선이다.
        _ensure_zip_within_budget(data, document_format.name)

    text = document_format.extract(data)
    if len(text) > MAX_EXTRACTED_CHARS:
        _log_failure(document_format.name, len(data), "extracted_too_long")
        raise DocumentExtractionError(f"문서가 너무 깁니다 (최대 {MAX_EXTRACTED_CHARS:,}자)")
    return text
