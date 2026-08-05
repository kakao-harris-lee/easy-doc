"""파일 텍스트 추출 단위 테스트.

픽스처는 `tests/ingest/fixtures/`에 커밋되어 있고 `make_fixtures.py`로 재생성한다.
깨진 파일·압축 폭탄·암호 파일은 정상 픽스처를 변형해 그 자리에서 만든다 — 손상되거나
공격용인 바이너리를 커밋하지 않기 위해서.
"""

import io
import logging
import struct
import tracemalloc
import zipfile
import zlib
from pathlib import Path

import pytest
from pypdf import PdfReader, PdfWriter

from app.exceptions import DocumentExtractionError, UnsupportedFormatError
from app.ingest import extractors
from app.ingest.extractors import MAX_EXTRACTED_CHARS, MAX_UPLOAD_BYTES, extract_text

FIXTURES = Path(__file__).parent / "fixtures"
_WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def _read(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def _zip_of(entries: dict[str, bytes]) -> bytes:
    """항목 이름 → 내용으로 zip(hwpx·docx 컨테이너)을 즉석에서 만든다."""
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, body in entries.items():
            archive.writestr(name, body)
    return payload.getvalue()


def _section_xml(text: str) -> bytes:
    return (
        '<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'
        ' xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">'
        f"<hp:p><hp:run><hp:t>{text}</hp:t></hp:run></hp:p></hs:sec>"
    ).encode()


def _forge_uncompressed_size(archive: bytes, target: str, fake_size: int) -> bytes:
    """zip 헤더의 '해제 후 크기'를 위조한다 (로컬 헤더·중앙 디렉터리 양쪽).

    선언 크기를 믿는 방어가 왜 무력한지 재현하기 위한 도구다. zipfile은 선언값을
    다 풀고 **나서** 검사하므로, 위조된 작은 값은 사전 차단만 무력화하고 메모리는
    이미 소모된 뒤다.
    """
    raw = bytearray(archive)
    end = raw.rfind(b"PK\x05\x06")
    position = struct.unpack_from("<I", raw, end + 16)[0]
    while raw[position : position + 4] == b"PK\x01\x02":
        name_length, extra_length, comment_length = struct.unpack_from("<HHH", raw, position + 28)
        name = bytes(raw[position + 46 : position + 46 + name_length]).decode()
        local_offset = struct.unpack_from("<I", raw, position + 42)[0]
        if name == target:
            struct.pack_into("<I", raw, position + 24, fake_size)
            struct.pack_into("<I", raw, local_offset + 22, fake_size)
        position += 46 + name_length + extra_length + comment_length
    return bytes(raw)


def _docx_bomb(body_bytes: int, *, fake_size: int | None = None) -> bytes:
    """정상 docx를 복제하되 본문 XML만 거대하게 바꾼 압축 폭탄.

    **껍데기 zip으로는 이 방어를 시험할 수 없다.** 항목 하나짜리 가짜 zip을 주면
    python-docx가 패키지 구조를 못 찾고 즉시 실패해서, 정작 위험한 '본문 XML을 통째로
    해제하는' 경로에 닿지 않는다. 그래서 실제 패키지 구성을 그대로 두고 본문만 바꾼다.
    """
    payload = (
        f'<?xml version="1.0"?><w:document xmlns:w="{_WORD_NS}"><w:body><w:p><w:r><w:t>'.encode()
        + b"A" * body_bytes
        + b"</w:t></w:r></w:p></w:body></w:document>"
    )
    out = io.BytesIO()
    with (
        zipfile.ZipFile(io.BytesIO(_read("sample.docx"))) as source,
        zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target,
    ):
        target.writestr("word/document.xml", payload)
        for info in source.infolist():
            if info.filename != "word/document.xml":
                target.writestr(info, source.read(info.filename))
    archive = out.getvalue()
    if fake_size is not None:
        archive = _forge_uncompressed_size(archive, "word/document.xml", fake_size)
    return archive


# --------------------------------------------------------------------------- docx


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


def test_docx_표는_문서_순서대로_제자리에_남는다() -> None:
    """문단을 먼저 모으고 표를 나중에 붙이면 표가 문서 끝으로 밀린다."""
    text = extract_text("sample_rich.docx", _read("sample_rich.docx"))
    assert text.index("첫 문단입니다.") < text.index("바깥 표 셀")
    assert text.index("바깥 표 셀") < text.index("표 뒤에 오는 문단입니다.")


def test_docx_중첩_표와_텍스트박스도_추출한다() -> None:
    """python-docx의 paragraphs/tables 순회로는 보이지 않는 자리들."""
    text = extract_text("sample_rich.docx", _read("sample_rich.docx"))
    assert "중첩 표 셀" in text
    assert "텍스트 상자 안 문장입니다." in text


def test_docx_텍스트박스는_한_번만_추출된다() -> None:
    """워드 2010+는 텍스트박스를 mc:Choice(DrawingML)와 mc:Fallback(VML) 두 벌로 저장한다.

    양쪽을 다 걷으면 같은 문구가 정확히 두 번 나온다 — 크레딧이 두 배로 청구되고,
    프롬프트와 마스킹 결과까지 오염된다. 픽스처는 실제 워드 구조를 그대로 재현한다.
    """
    text = extract_text("sample_rich.docx", _read("sample_rich.docx"))
    assert text.count("텍스트 상자 안 문장입니다.") == 1


def test_docx_머리글과_바닥글도_추출한다() -> None:
    text = extract_text("sample_rich.docx", _read("sample_rich.docx"))
    assert "머리글 문구" in text
    assert "바닥글 문구" in text


def test_docx_변경추적_삽입문은_남고_삭제문은_빠진다() -> None:
    """삭제된 글자는 w:t가 아니라 w:delText에 담긴다 — 되살아나면 오히려 오정보다."""
    text = extract_text("sample_rich.docx", _read("sample_rich.docx"))
    assert "변경 추적으로 삽입된 문장입니다." in text
    assert "변경 추적으로 삭제된 문장입니다." not in text
    assert "삭제된" not in text


def test_docx_머리글은_구역_수만큼_반복되지_않는다() -> None:
    """픽스처는 구역이 둘이고 둘째 구역이 머리글·바닥글을 물려받는다.

    물려받은 쪽까지 걷으면 같은 문구가 구역 수만큼 반복된다.
    """
    text = extract_text("sample_rich.docx", _read("sample_rich.docx"))
    assert "둘째 구역 본문입니다." in text, "픽스처에 구역이 둘 있어야 이 테스트가 의미를 가진다"
    assert text.count("머리글 문구") == 1
    assert text.count("바닥글 문구") == 1


# --------------------------------------------------------------------------- pdf


def test_pdf_페이지_텍스트를_결합한다() -> None:
    text = extract_text("sample.pdf", _read("sample.pdf"))
    assert "첫째 쪽 안내문입니다." in text
    assert "둘째 쪽 안내문입니다." in text
    assert text.index("첫째") < text.index("둘째")


def test_텍스트_없는_pdf는_스캔_안내와_함께_실패한다() -> None:
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("empty.pdf", _read("empty.pdf"))
    assert str(error.value) == "텍스트를 추출할 수 없습니다 (스캔 PDF는 지원 예정)"


def test_페이지가_없는_pdf는_스캔_안내와_구분된다() -> None:
    """0페이지는 스캔본이 아니라 빈 파일이다 — 안내가 다르면 사용자가 덜 헤맨다."""
    payload = io.BytesIO()
    PdfWriter().write(payload)
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("빈.pdf", payload.getvalue())
    assert str(error.value) == "페이지가 없는 PDF입니다"


def _encrypted_pdf(*, user_password: str, owner_password: str | None = None) -> bytes:
    """암호를 건 PDF를 만든다. 사용자 암호가 빈 문자열이면 열람은 자유롭다."""
    writer = PdfWriter(clone_from=io.BytesIO(_read("sample.pdf")))
    writer.encrypt(user_password=user_password, owner_password=owner_password)
    payload = io.BytesIO()
    writer.write(payload)
    return payload.getvalue()


def test_사용자_암호가_걸린_pdf는_암호_안내와_함께_실패한다() -> None:
    """열람 자체가 막힌 파일 — 사용자가 암호를 풀어 다시 올려야 한다."""
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("암호.pdf", _encrypted_pdf(user_password="비밀번호"))
    assert "암호가 설정된 파일입니다" in str(error.value)


def test_소유자_암호만_걸린_pdf는_정상_추출된다() -> None:
    """인쇄·복사만 제한한 PDF는 열람이 자유롭고 실제로 읽힌다.

    `is_encrypted`는 이런 파일에도 True라, 그걸로 미리 거르면 사용자가 풀 암호가
    존재하지도 않는 정상 파일을 거부하게 된다. 공공기관 배포 문서에 흔한 형태다.
    """
    data = _encrypted_pdf(user_password="", owner_password="소유자암호")
    assert PdfReader(io.BytesIO(data)).is_encrypted, "이 파일도 is_encrypted는 True다"
    text = extract_text("공고문.pdf", data)
    assert "첫째 쪽 안내문입니다." in text
    assert "둘째 쪽 안내문입니다." in text


# --------------------------------------------------------------------------- hwpx


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


@pytest.mark.parametrize(
    "entry",
    [
        "Contents/section0.xml.bak",  # 접두사만 일치 — re.match는 통과시킨다
        "backup/Contents/section0.xml",  # 접미사만 일치 — re.search는 통과시킨다
    ],
)
def test_구역_이름은_정확히_일치할_때만_구역이다(entry: str) -> None:
    """부분 일치를 허용하면 백업본·임시본이 본문으로 섞여 들어온다."""
    with pytest.raises(DocumentExtractionError):
        extract_text("가짜.hwpx", _zip_of({entry: _section_xml("섞이면 안 되는 글")}))


def test_구역_xml이_없는_hwpx는_추출_실패다() -> None:
    with pytest.raises(DocumentExtractionError):
        extract_text("빈.hwpx", _zip_of({"mimetype": b"application/hwp+zip"}))


# ------------------------------------------------------------------- 압축 폭탄 방어


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


def test_zip_항목은_남은_예산까지만_읽는다(monkeypatch: pytest.MonkeyPatch) -> None:
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


@pytest.mark.parametrize(
    ("filename", "entry"),
    [("bomb.docx", "word/document.xml"), ("bomb.hwpx", "Contents/section0.xml")],
)
def test_해제_크기가_상한을_넘으면_거부한다(
    monkeypatch: pytest.MonkeyPatch, filename: str, entry: str
) -> None:
    """docx·hwpx 모두 zip이다 — 디스패치 지점 방어가 두 형식에 함께 걸려야 한다."""
    monkeypatch.setattr(extractors, "_MAX_UNCOMPRESSED_BYTES", 1024)
    bomb = _zip_of({entry: b"0" * 5000})
    with pytest.raises(DocumentExtractionError) as error:
        extract_text(filename, bomb)
    assert "너무 큽니다" in str(error.value)


def _peak_heap_on_rejection(filename: str, data: bytes) -> tuple[str, int]:
    """추출이 거부되기까지의 파이썬 힙 최대치를 잰다."""
    tracemalloc.start()
    try:
        with pytest.raises(DocumentExtractionError) as error:
            extract_text(filename, data)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    return str(error.value), peak


# 실제 상한(50MB)으로 돌린다. 상한을 낮추면 방어가 통하는 것처럼 보이지만, 문제는
# "검사 자체가 상한만큼 메모리를 쓰는가"라서 실제 값으로 재야 의미가 있다.
_HEAP_ALLOWANCE = 8 * 1024 * 1024


def test_선언_크기를_위조한_폭탄이_힙을_먹지_못한다() -> None:
    """zip 헤더의 선언 크기는 상한으로 쓸 수 없다.

    ZipExtFile은 최대 2GB 청크로 먼저 압축을 풀고 선언 크기·CRC는 **그 뒤에** 검사한다.
    그래서 '선언값 합계'로 거르는 사전 차단은 위조 한 번에 뚫리고, 뚫릴 때는 이미
    메모리가 소모된 뒤다(58KB 업로드 → 힙 141MB).

    믿을 수 있는 건 우리가 읽은 바이트뿐이다. 위조된 파일은 선언값까지만 풀린 뒤
    CRC 불일치로 걸리므로 안내는 '손상'이 된다 — 실제로 헤더가 조작된 파일이라 맞는
    설명이다. 핵심은 메시지가 아니라 **힙이 늘지 않는 것**이다.
    """
    bomb = _docx_bomb(60_000_000, fake_size=1000)
    assert len(bomb) < MAX_UPLOAD_BYTES, "폭탄 자체는 업로드 상한 안이어야 의미가 있다"
    with zipfile.ZipFile(io.BytesIO(bomb)) as archive:
        declared = sum(info.file_size for info in archive.infolist())
    assert declared < extractors._MAX_UNCOMPRESSED_BYTES, "선언값 합계는 사전 차단을 통과한다"

    message, peak = _peak_heap_on_rejection("bomb.docx", bomb)
    assert "읽을 수 없습니다" in message
    assert peak < _HEAP_ALLOWANCE, f"힙 최대 {peak:,}바이트 — 폭탄이 통째로 올라왔다"


def test_정직하게_큰_해제_크기도_힙을_먹지_못한다() -> None:
    """선언값을 위조하지 않은 폭탄은 상한 초과로 걸린다. 이때도 힙은 늘지 않아야 한다.

    검사 단계는 바이트 수만 세면 되므로 조각을 들고 있지 않는다 — 한 번에 예산만큼
    읽으면 검사가 스스로 수십 MB를 쓴다.
    """
    bomb = _docx_bomb(60_000_000)
    assert len(bomb) < MAX_UPLOAD_BYTES
    message, peak = _peak_heap_on_rejection("bomb.docx", bomb)
    assert "너무 큽니다" in message
    assert peak < _HEAP_ALLOWANCE, f"힙 최대 {peak:,}바이트 — 검사가 예산만큼 메모리를 썼다"


def test_읽기_예산은_구역_읽기_단계에도_따로_걸린다(monkeypatch: pytest.MonkeyPatch) -> None:
    """디스패치 방어와 별개로 `_read_hwpx_sections` 단독 호출도 안전해야 한다."""
    monkeypatch.setattr(extractors, "_MAX_UNCOMPRESSED_BYTES", 1024)
    bomb = _zip_of({"Contents/section0.xml": b"0" * 5000})
    with pytest.raises(DocumentExtractionError) as error:
        extractors._read_hwpx_sections(bomb)
    assert "너무 큽니다" in str(error.value)


# ------------------------------------------------------------------- XML 엔티티 방어


def _dtd_section(encoding: str) -> bytes:
    """엔티티 폭탄 구역 XML. 인코딩을 바꿔 바이트 스캔 방어를 우회한다."""
    return (
        '<?xml version="1.0"?>'
        '<!DOCTYPE hs:sec [<!ENTITY a "AAAAAAAAAA">'
        '<!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>'
        '<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'
        ' xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">'
        "<hp:p><hp:run><hp:t>&b;</hp:t></hp:run></hp:p></hs:sec>"
    ).encode(encoding)


@pytest.mark.parametrize("encoding", ["utf-8", "utf-16"])
def test_hwpx_dtd_선언은_인코딩과_무관하게_거부한다(encoding: str) -> None:
    """XML 파서는 내부 엔티티를 그대로 펼친다 — billion laughs 차단.

    본문을 b"<!DOCTYPE"으로 훑는 방어는 UTF-16으로 인코딩하면 그대로 뚫린다.
    파서 수준(expat StartDoctypeDeclHandler)에서 막아야 인코딩과 무관하게 걸린다.
    """
    if encoding == "utf-16":
        assert b"<!DOCTYPE" not in _dtd_section(encoding), "바이트 스캔이 통하지 않는 형태여야 한다"
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("bomb.hwpx", _zip_of({"Contents/section0.xml": _dtd_section(encoding)}))
    assert "DTD" in str(error.value)


def test_주석_안의_doctype_문자열은_오탐하지_않는다() -> None:
    """바이트 스캔 방식이 정상 문서를 거부하던 오탐 경로."""
    section = (
        '<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section"'
        ' xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">'
        "<!-- <!DOCTYPE 를 설명하는 주석 -->"
        "<hp:p><hp:run><hp:t>정상 문서</hp:t></hp:run></hp:p></hs:sec>"
    ).encode()
    assert extract_text("주석.hwpx", _zip_of({"Contents/section0.xml": section})) == "정상 문서"


# ------------------------------------------------------------------- 길이·형식 상한


def test_추출_길이가_상한을_넘으면_거부한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """크기 상한만으로는 부족하다 — 마크업 대비 본문 비율을 키우면 작은 파일이 수백만 자가 된다."""
    monkeypatch.setattr(extractors, "MAX_EXTRACTED_CHARS", 100)
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("긴.hwpx", _zip_of({"Contents/section0.xml": _section_xml("가" * 500)}))
    assert "너무 깁니다" in str(error.value)


def test_작은_업로드가_수백만_자로_늘어나는_경우를_막는다() -> None:
    """상한 상수를 건드리지 않고 실제 비율 공격을 재현한다."""
    bomb = _zip_of({"Contents/section0.xml": _section_xml("가" * (MAX_EXTRACTED_CHARS + 1))})
    assert len(bomb) < MAX_UPLOAD_BYTES
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("긴.hwpx", bomb)
    assert "너무 깁니다" in str(error.value)


def test_상한_상수값() -> None:
    assert MAX_UPLOAD_BYTES == 10 * 1024 * 1024
    assert MAX_EXTRACTED_CHARS == 500_000


# ------------------------------------------------------------------- 확장자 분기


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


# ------------------------------------------------------------------- 손상·암호 파일


@pytest.mark.parametrize("fixture", ["sample.docx", "sample.pdf", "sample.hwpx"])
def test_깨진_파일은_추출_실패로_변환된다(fixture: str) -> None:
    """라이브러리 예외가 그대로 새어 나가면 라우터가 500으로 처리한다."""
    with pytest.raises(DocumentExtractionError):
        extract_text(fixture, _read(fixture)[:200])


@pytest.mark.parametrize("fixture", ["sample.docx", "sample.pdf", "sample.hwpx"])
def test_빈_바이트도_추출_실패로_변환된다(fixture: str) -> None:
    with pytest.raises(DocumentExtractionError):
        extract_text(fixture, b"")


def _ole2_with_stream(stream_name: str) -> bytes:
    """지정한 스트림 이름을 담은 OLE2 복합 문서 흉내.

    OLE2 디렉터리는 스트림 이름을 UTF-16LE로 저장한다.
    """
    return b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + bytes(64) + stream_name.encode("utf-16-le")


def test_암호가_걸린_docx는_암호_안내와_함께_실패한다() -> None:
    """암호가 걸린 OOXML은 zip이 아니라 OLE2 복합 문서이고, 본문이 EncryptedPackage에 들어간다."""
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("암호.docx", _ole2_with_stream("EncryptedPackage"))
    assert "암호가 설정된 파일입니다" in str(error.value)


def test_구버전_doc를_docx로_개명한_파일은_형식_안내를_받는다() -> None:
    """암호 안내를 하면 있지도 않은 암호를 찾아 헤매게 된다."""
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("공고문.docx", _ole2_with_stream("WordDocument"))
    message = str(error.value)
    assert "구버전 doc 형식은 지원하지 않습니다" in message
    assert "암호" not in message


def test_정체를_알_수_없는_ole2는_단정하지_않고_안내한다() -> None:
    with pytest.raises(DocumentExtractionError) as error:
        extract_text("수수께끼.docx", b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + bytes(512))
    assert str(error.value) == "암호가 설정되었거나 지원하지 않는 구형식 파일입니다"


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


def test_실패_로그에_파일명이나_본문이_남지_않는다(caplog: pytest.LogCaptureFixture) -> None:
    """로그에 남길 수 있는 것은 형식명·바이트 길이·사유 코드뿐이다."""
    caplog.set_level(logging.WARNING, logger="app.ingest.extractors")
    corrupted = zlib.compress(b"\x00" * 64)  # 본문으로 오인될 내용 없이 깨진 파일
    with pytest.raises(DocumentExtractionError):
        extract_text("주민등록등본_홍길동.docx", corrupted)

    assert caplog.records, "실패가 관측되지 않으면 자체 버그가 조용히 묻힌다"
    for record in caplog.records:
        message = record.getMessage()
        assert "홍길동" not in message
        assert "주민등록등본" not in message
        assert ".docx" not in message
        assert "format=docx" in message
        assert "bytes=" in message


def test_스캔_pdf도_사유와_함께_로깅된다(caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.WARNING, logger="app.ingest.extractors")
    with pytest.raises(DocumentExtractionError):
        extract_text("스캔본.pdf", _read("empty.pdf"))
    assert any("reason=no_text_layer" in record.getMessage() for record in caplog.records)
