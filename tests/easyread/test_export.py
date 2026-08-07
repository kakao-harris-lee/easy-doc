"""내보내기 산출물 생성 테스트.

여기서 보려는 것은 세 가지다: 자리표시자가 원문으로 온전히 복원되는지, docx가 실제로
다시 열리는 파일인지(왕복), 그리고 한글 제목이 담긴 파일명 헤더가 규격을 지키는지.
"""

import io
from urllib.parse import quote

import docx
import pytest

from app.easyread.export import (
    ExportFormat,
    content_disposition,
    export_filename,
    render_export,
    restore_placeholders,
)


def _paragraphs(content: bytes) -> list[str]:
    """생성한 docx를 다시 열어 문단 텍스트를 뽑는다."""
    return [paragraph.text for paragraph in docx.Document(io.BytesIO(content)).paragraphs]


# --- 자리표시자 복원 -------------------------------------------------------------


def test_자리표시자를_원문으로_되돌린다() -> None:
    text = "문의는 [[전화번호1]] 또는 [[이메일1]]로 하세요."

    restored = restore_placeholders(
        text, {"[[전화번호1]]": "010-1234-5678", "[[이메일1]]": "help@example.kr"}
    )

    assert restored == "문의는 010-1234-5678 또는 help@example.kr로 하세요."
    assert "[[" not in restored


def test_같은_자리표시자가_여러_번_나와도_모두_되돌린다() -> None:
    text = "[[전화번호1]]로 전화하세요. 다시 적으면 [[전화번호1]]입니다."

    restored = restore_placeholders(text, {"[[전화번호1]]": "02-123-4567"})

    assert restored.count("02-123-4567") == 2


def test_번호가_이어지는_자리표시자를_섞지_않는다() -> None:
    """[[전화번호1]] 치환이 [[전화번호11]]을 갉아먹으면 안 된다 (닫는 괄호까지 통째로 본다)."""
    text = "[[전화번호1]]과 [[전화번호11]]"

    restored = restore_placeholders(
        text, {"[[전화번호1]]": "010-1111-1111", "[[전화번호11]]": "010-2222-2222"}
    )

    assert restored == "010-1111-1111과 010-2222-2222"


def test_가린_항목이_없으면_본문이_그대로다() -> None:
    assert restore_placeholders("가린 것이 없는 글", {}) == "가린 것이 없는 글"


# --- docx ---------------------------------------------------------------------


def test_docx는_제목과_문단으로_구성된다() -> None:
    file = render_export(
        export_format=ExportFormat.DOCX,
        title="재난지원금 안내",
        body="첫 문단입니다.\n\n둘째 문단입니다.",
    )

    # 왕복: 우리가 만든 파일을 python-docx가 다시 열 수 있어야 한다.
    assert _paragraphs(file.content) == ["재난지원금 안내", "첫 문단입니다.", "둘째 문단입니다."]
    assert file.filename == "재난지원금 안내.docx"
    assert file.media_type.endswith("wordprocessingml.document")


def test_docx에_꼬리말을_붙이지_않는다() -> None:
    """담당자가 그대로 배포할 산출물이다 — "AI 초안" 같은 문구가 남으면 지워야 한다."""
    file = render_export(export_format=ExportFormat.DOCX, title="안내문", body="본문입니다.")

    assert _paragraphs(file.content) == ["안내문", "본문입니다."]


def test_문단_안의_줄바꿈은_유지된다() -> None:
    """빈 줄만 문단 경계다 — 목록처럼 줄만 바꾼 곳이 한 줄로 뭉치면 안 된다."""
    file = render_export(
        export_format=ExportFormat.DOCX, title="안내문", body="첫째 줄\n둘째 줄\n\n다음 문단"
    )

    assert _paragraphs(file.content) == ["안내문", "첫째 줄\n둘째 줄", "다음 문단"]


def test_빈_줄이_여러_개여도_빈_문단을_만들지_않는다() -> None:
    file = render_export(
        export_format=ExportFormat.DOCX, title="안내문", body="\n\n앞\n\n\n\n뒤\n\n"
    )

    assert _paragraphs(file.content) == ["안내문", "앞", "뒤"]


def test_제어문자가_섞여도_docx를_만든다() -> None:
    """XML은 탭·개행·복귀를 뺀 제어문자를 담지 못해 lxml이 ValueError를 던진다.

    제목은 저장 시점에 이미 걸러지지만 초안 본문은 그렇지 않다 — 두 인자 모두
    렌더 진입부에서 정규화된다는 것을 함께 본다.
    """
    file = render_export(
        export_format=ExportFormat.DOCX, title="안내\x0b문", body="본문\x00입니다.\n\n둘째\x0c 문단"
    )

    assert _paragraphs(file.content) == ["안내문", "본문입니다.", "둘째 문단"]


# --- txt ----------------------------------------------------------------------


def test_txt에도_제어문자가_남지_않는다() -> None:
    """정규화는 형식 분기 앞에서 한다 — docx와 txt가 같은 본문을 내놓아야 한다."""
    file = render_export(
        export_format=ExportFormat.TXT, title="안내문", body="본문\x00입니다.\n\n둘째\x0c 문단"
    )

    assert file.content.decode("utf-8") == "본문입니다.\n\n둘째 문단"


def test_복원한_원문에_섞인_제어문자도_걷어낸다() -> None:
    """AI 초안·마스킹 원문은 저장 경로에서 정규화되지 않는다 — 여기가 유일한 방어다."""
    body = restore_placeholders(
        "문의는 [[전화번호1]]로 하세요.", {"[[전화번호1]]": "02-\x00123-4567"}
    )

    file = render_export(export_format=ExportFormat.TXT, title="안내문", body=body)

    assert file.content.decode("utf-8") == "문의는 02-123-4567로 하세요."


def test_txt는_BOM_없는_UTF_8이다() -> None:
    file = render_export(export_format=ExportFormat.TXT, title="안내문", body="본문입니다.")

    assert not file.content.startswith(b"\xef\xbb\xbf")
    assert file.content.decode("utf-8") == "본문입니다."
    assert file.filename == "안내문.txt"
    assert file.media_type == "text/plain; charset=utf-8"


# --- 파일명 -------------------------------------------------------------------


@pytest.mark.parametrize(
    ("title", "expected"),
    [
        ("재난지원금 안내", "재난지원금 안내.txt"),
        # 경로 구분자·제어문자는 제목에 들어올 수 있다(첫 줄에서 유도한 이름).
        # 앞뒤 점도 걷어낸다 — 숨김 파일(.name)이나 윈도우 금지 이름(name.)이 된다.
        ("../../etc/passwd", "etc passwd.txt"),
        ('따옴표"와\\역슬래시', "따옴표 와 역슬래시.txt"),
        ("줄바꿈\r\n섞임", "줄바꿈 섞임.txt"),
        # 전부 걸러지면 이름이 사라진다 — 대체 이름을 쓴다.
        ("///", "쉬운 글.txt"),
        ("   ", "쉬운 글.txt"),
    ],
)
def test_제목에서_안전한_파일명을_만든다(title: str, expected: str) -> None:
    assert export_filename(title, ExportFormat.TXT) == expected


def test_아주_긴_제목은_잘린다() -> None:
    """파일 시스템 이름 한계(대개 255바이트)를 넘기면 저장 자체가 실패한다."""
    name = export_filename("가" * 300, ExportFormat.DOCX)

    assert len(name.encode("utf-8")) < 255
    assert name.endswith(".docx")


# --- Content-Disposition -------------------------------------------------------


def test_한글_파일명은_RFC_5987로_인코딩한다() -> None:
    """HTTP 헤더는 latin-1만 담는다 — 한글을 그대로 넣으면 인코딩 오류가 난다."""
    value = content_disposition("재난지원금 안내.docx")

    assert value.startswith("attachment;")
    assert f"filename*=UTF-8''{quote('재난지원금 안내.docx', safe='')}" in value
    # ASCII만 아는 클라이언트를 위한 대체 이름이 함께 있어야 한다.
    assert 'filename="easy-read.docx"' in value
    # 헤더에 실을 수 있는 문자만 남는다(latin-1 인코딩이 통과해야 한다).
    value.encode("latin-1")
