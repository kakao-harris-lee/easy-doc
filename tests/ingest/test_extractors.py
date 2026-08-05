"""파일 텍스트 추출 단위 테스트.

픽스처는 `tests/ingest/fixtures/`에 커밋되어 있고 `make_fixtures.py`로 재생성한다.
깨진 파일은 정상 픽스처를 잘라 그 자리에서 만든다 — 손상 바이너리를 커밋하지 않기 위해서.
"""

import io
import zipfile
from pathlib import Path

import pytest

from app.exceptions import DocumentExtractionError, UnsupportedFormatError
from app.ingest import extractors
from app.ingest.extractors import MAX_UPLOAD_BYTES, extract_text

FIXTURES = Path(__file__).parent / "fixtures"


def _read(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def _zip_of(sections: dict[str, bytes]) -> bytes:
    """항목 이름 → 내용으로 zip(hwpx·docx 컨테이너)을 즉석에서 만든다."""
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, body in sections.items():
            archive.writestr(name, body)
    return payload.getvalue()


def _section_xml(text: str) -> bytes:
    return (
        '<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'
        ' xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">'
        f"<hp:p><hp:run><hp:t>{text}</hp:t></hp:run></hp:p></hs:sec>"
    ).encode()


def test_docx_문단을_추출한다() -> None:
    text = extract_text("sample.docx", _read("sample.docx"))
    assert "쉬운 글 변환 안내" in text
    assert "이 문서는 추출 테스트용 예시입니다." in text


def test_docx_공백뿐인_문단은_빈_줄로_남지_않는다() -> None:
    """문단 사이 구분은 개행 하나 — 빈 문단이 빈 줄을 만들면 후속 프롬프트가 지저분해진다."""
    text = extract_text("sample.docx", _read("sample.docx"))
    assert text == "쉬운 글 변환 안내\n이 문서는 추출 테스트용 예시입니다."


def test_docx_표_셀_텍스트도_추출한다() -> None:
    text = extract_text("sample_table.docx", _read("sample_table.docx"))
    assert "쉬운 글 변환 안내" in text
    for cell in ("구분", "내용", "접수 기간", "3월 1일부터 3월 31일까지"):
        assert cell in text


def test_pdf_페이지_텍스트를_결합한다() -> None:
    text = extract_text("sample.pdf", _read("sample.pdf"))
    assert "첫째 쪽 안내문입니다." in text
    assert "둘째 쪽 안내문입니다." in text
    assert text.index("첫째") < text.index("둘째")


def test_텍스트_없는_pdf는_스캔_안내와_함께_실패한다() -> None:
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("empty.pdf", _read("empty.pdf"))
    assert str(error.value) == "텍스트를 추출할 수 없습니다 (스캔 PDF는 지원 예정)"


def test_hwpx_문단을_추출한다() -> None:
    text = extract_text("sample.hwpx", _read("sample.hwpx"))
    assert "한글 문서 추출 확인" in text


def test_hwpx_한_문단의_여러_조각은_구분자_없이_이어진다() -> None:
    """OWPML은 한 문단을 여러 <hp:run>/<hp:t>로 쪼갠다. 조각 사이에 개행을 넣으면 안 된다."""
    text = extract_text("sample.hwpx", _read("sample.hwpx"))
    assert "문단 하나가 여러 조각으로 나뉘어도 이어 붙는다." in text


def test_hwpx_구역은_zip_기록_순서가_아니라_번호_순서로_이어진다() -> None:
    """픽스처는 section1을 section0보다 먼저 기록해 두었다."""
    text = extract_text("sample.hwpx", _read("sample.hwpx"))
    assert text.index("한글 문서 추출 확인") < text.index("둘째 구역의 문장입니다.")


def test_hwpx_문단_사이는_개행_하나다() -> None:
    text = extract_text("sample.hwpx", _read("sample.hwpx"))
    assert text == (
        "한글 문서 추출 확인\n"
        "문단 하나가 여러 조각으로 나뉘어도 이어 붙는다.\n"
        "둘째 구역의 문장입니다."
    )


@pytest.mark.parametrize(
    ("filename", "fixture"),
    [
        ("SAMPLE.DOCX", "sample.docx"),
        ("SAMPLE.PDF", "sample.pdf"),
        ("SAMPLE.HWPX", "sample.hwpx"),
        ("보고서.Docx", "sample.docx"),
    ],
)
def test_확장자는_대소문자를_가리지_않는다(filename: str, fixture: str) -> None:
    assert extract_text(filename, _read(fixture)).strip()


@pytest.mark.parametrize("filename", ["문서.hwp", "문서.txt", "문서.doc", "문서.pdf.exe", "문서"])
def test_지원하지_않는_확장자는_거부한다(filename: str) -> None:
    with pytest.raises(UnsupportedFormatError) as error:
        extract_text(filename, b"whatever")
    assert str(error.value) == "지원 형식: docx, pdf, hwpx"


@pytest.mark.parametrize("fixture", ["sample.docx", "sample.pdf", "sample.hwpx"])
def test_깨진_파일은_추출_실패로_변환된다(fixture: str) -> None:
    """라이브러리 예외가 그대로 새어 나가면 라우터가 500으로 처리한다."""
    truncated = _read(fixture)[:200]
    with pytest.raises(DocumentExtractionError):
        extract_text(fixture, truncated)


@pytest.mark.parametrize("fixture", ["sample.docx", "sample.pdf", "sample.hwpx"])
def test_빈_바이트도_추출_실패로_변환된다(fixture: str) -> None:
    with pytest.raises(DocumentExtractionError):
        extract_text(fixture, b"")


@pytest.mark.parametrize("fixture", ["sample.docx", "sample.pdf", "sample.hwpx"])
def test_실패_메시지에_파일명이나_내용이_새지_않는다(fixture: str) -> None:
    """파일명도 개인정보가 될 수 있다 — 예외 메시지는 형식명까지만 말한다."""
    with pytest.raises(DocumentExtractionError) as error:
        extract_text(f"민감한_이름_{fixture}", _read(fixture)[:200])
    message = str(error.value)
    assert "민감한_이름" not in message
    assert fixture not in message
    # `from None` — 원본 라이브러리 예외(임시 경로·본문 조각이 섞일 수 있다)가
    # traceback으로 따라 나오지 않는지까지 확인한다.
    assert error.value.__cause__ is None
    assert error.value.__suppress_context__


def test_hwpx_구역은_사전순이_아니라_번호_숫자순으로_이어진다() -> None:
    """구역이 10개를 넘으면 사전순 정렬은 section10을 section2 앞에 둔다."""
    text = extract_text(
        "many.hwpx",
        _zip_of(
            {
                "Contents/section10.xml": _section_xml("열째 구역"),
                "Contents/section2.xml": _section_xml("셋째 구역"),
            }
        ),
    )
    assert text == "셋째 구역\n열째 구역"


def test_docx_해제_크기가_상한을_넘으면_거부한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """docx도 zip 컨테이너다 — python-docx가 풀기 전에 선언 크기로 끊는다."""
    monkeypatch.setattr(extractors, "_MAX_UNCOMPRESSED_BYTES", 1024)
    bomb = _zip_of({"word/document.xml": b"0" * 5000})
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("bomb.docx", bomb)
    assert str(error.value) == "docx 파일이 너무 큽니다"


def test_hwpx_구역_xml이_상한을_넘으면_거부한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """압축 폭탄 방어: 10MB 업로드가 GB 단위 XML로 부풀어도 상한에서 끊긴다."""
    monkeypatch.setattr(extractors, "_MAX_UNCOMPRESSED_BYTES", 1024)
    bomb = _zip_of({"Contents/section0.xml": b"<a>" + b"0" * 5000 + b"</a>"})
    with pytest.raises(DocumentExtractionError):
        extract_text("bomb.hwpx", bomb)


class _RecordingMember:
    """`archive.open()`이 돌려주는 파일 객체 대역. read 호출 인자를 기록한다."""

    def __init__(self, data: bytes, calls: list[int | None]) -> None:
        self._buffer = io.BytesIO(data)
        self._calls = calls

    def __enter__(self) -> "_RecordingMember":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self._buffer.close()

    def read(self, size: int | None = None) -> bytes:
        self._calls.append(size)
        return self._buffer.read(size)


def test_hwpx_구역은_남은_예산까지만_읽는다(monkeypatch: pytest.MonkeyPatch) -> None:
    """압축 폭탄 방어의 핵심은 '읽기 자체를 상한으로 끊는 것'이다.

    다 읽고 나서 크기를 확인하면 확인 시점에 이미 수 GB가 메모리에 올라와 있다.
    상한 초과를 감지하려면 1바이트만 더 읽으면 되므로 예산+1이 정답이다.
    """
    monkeypatch.setattr(extractors, "_MAX_UNCOMPRESSED_BYTES", 1024)
    # zip 조립이 끝난 뒤에 패치한다 — writestr()도 내부적으로 open()을 쓴다.
    bomb = _zip_of({"Contents/section0.xml": b"<a/>"})
    calls: list[int | None] = []

    def fake_open(self: zipfile.ZipFile, name: str) -> _RecordingMember:
        return _RecordingMember(b"0" * 5000, calls)

    monkeypatch.setattr(zipfile.ZipFile, "open", fake_open)
    with pytest.raises(DocumentExtractionError):
        extract_text("bomb.hwpx", bomb)
    assert calls == [1025]


def test_hwpx_엔티티_선언은_거부한다() -> None:
    """ElementTree는 내부 엔티티를 그대로 펼친다 — billion laughs 차단."""
    section = (
        b'<?xml version="1.0"?>'
        b'<!DOCTYPE hs:sec [<!ENTITY a "AAAAAAAAAA">'
        b'<!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>'
        b'<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'
        b' xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">'
        b"<hp:p><hp:run><hp:t>&b;</hp:t></hp:run></hp:p></hs:sec>"
    )
    with pytest.raises(DocumentExtractionError):
        extract_text("bomb.hwpx", _zip_of({"Contents/section0.xml": section}))


def test_구역_xml이_없는_hwpx는_추출_실패다() -> None:
    with pytest.raises(DocumentExtractionError):
        extract_text("빈.hwpx", _zip_of({"mimetype": b"application/hwp+zip"}))


def test_업로드_상한은_10MB다() -> None:
    assert MAX_UPLOAD_BYTES == 10 * 1024 * 1024
