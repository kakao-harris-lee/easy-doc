"""hwpx(OWPML) 패키지 조립 — 내보내기 전용 최소 생성기.

hwpx는 OWPML(KS X 6101) XML 묶음을 담은 zip 패키지다. 라이브러리를 더하지 않고
`zipfile`만으로 조립하는 이유는 두 가지다: 우리가 넣을 내용이 **서식 없는 문단 목록**
하나뿐이라 필요한 스키마 조각이 매우 작고, 신규 의존성 하나가 검증되지 않은 벤더 포맷
전체를 우리 배포에 묶어 오기 때문이다.

**한계 — 반드시 함께 읽을 것.** 이 생성기는 스펙과 실제 hwpx 패키지 구조를 보고 조립한
것이며, **한컴 오피스에서 실제로 열리는지는 검증하지 못했다**(저장소·CI에 한컴 오피스가
없다). 자동으로 지키는 것은 "우리 ingest 추출기로 다시 읽힌다"까지이고, 한컴 호환성은
파일럿에서 사람이 확인해야 한다(README "내보내기 형식" 참고).

담지 않는 것: 미리보기(`Preview/`)·편집 설정(`settings.xml`)·메타데이터 RDF·줄 배치
캐시(`hp:linesegarray`)·각주/미주/쪽 테두리 설정. 전부 편집기가 다시 계산하거나 없어도
되는 값이라, 검증할 수 없는 XML을 늘리는 대신 뺐다.

`tests/ingest/make_fixtures.py`에도 비슷한 조립 코드가 있지만 **공용화하지 않는다**.
그쪽은 파서를 시험하려고 일부러 불규칙한 패키지를 만든다 — 한 문단을 여러 `<hp:t>`로
쪼개고, section1을 section0보다 먼저 기록하고, header.xml을 아예 빼는 식이다. 여기는
정반대로 **한 가지 규칙적인 패키지**만 만든다. 둘을 한 함수로 묶으면 제품 코드가 시험용
변형(쪼갬·순서 뒤집기)을 옵션으로 떠안게 되고, 픽스처는 제품이 바뀔 때마다 함께 흔들려
파서를 시험한다는 목적을 잃는다.
"""

import io
import zipfile
from collections.abc import Sequence
from xml.sax.saxutils import escape

#: 패키지 mimetype. 스펙상 첫 항목이자 무압축이어야 하고 뒤에 개행을 붙이지 않는다
#: (ODF/EPUB 패키지 규약과 같다). HTTP Content-Type도 같은 값을 쓴다 —
#: 근거는 app/easyread/export.py의 MEDIA_TYPES.
MIMETYPE = "application/hwp+zip"

#: zip 항목의 수정 시각. 같은 문서를 두 번 내려받으면 바이트도 같아야 한다 —
#: 현재 시각을 넣으면 내용이 같은 산출물이 매번 달라져 비교·캐시가 무의미해진다.
#: zip 형식이 담을 수 있는 가장 이른 시각(1980-01-01)을 고정값으로 쓴다.
_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)

_XML_DECLARATION = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'

#: 2011년판 hwpml 네임스페이스. 실제 한컴 산출물은 쓰지 않는 접두사까지 전부 선언하지만,
#: 여기서는 **실제로 쓰는 것만** 선언한다(XML 네임스페이스 규격상 동등하다). ingest 파서는
#: 접두사가 아니라 로컬 이름으로 판별하므로 읽기 쪽은 이 URI에 묶이지 않는다.
_NS_HEAD = "http://www.hancom.co.kr/hwpml/2011/head"
_NS_PARAGRAPH = "http://www.hancom.co.kr/hwpml/2011/paragraph"
_NS_SECTION = "http://www.hancom.co.kr/hwpml/2011/section"
_NS_VERSION = "http://www.hancom.co.kr/hwpml/2011/version"

#: 패키지 진입점. content.hpf가 어떤 항목을 어떤 순서로 읽을지 알려준다.
_CONTAINER_XML = (
    f"{_XML_DECLARATION}"
    '<ocf:container xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container">'
    '<ocf:rootfiles><ocf:rootfile full-path="Contents/content.hpf"'
    ' media-type="application/hwpml-package+xml"/></ocf:rootfiles></ocf:container>'
)

#: ODF 방식 항목 목록. 실제 한컴 산출물도 내용 없이 뿌리 요소만 남기므로 그대로 맞춘다.
_MANIFEST_XML = (
    f"{_XML_DECLARATION}"
    '<odf:manifest xmlns:odf="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"/>'
)

#: 파일 형식 버전. 속성명 `tagetApplication`의 철자 오류는 형식 원문 그대로다 —
#: 실제 한컴 산출물이 이 철자를 쓰므로, 고쳐 적으면 오히려 규격에서 벗어난다.
_VERSION_XML = (
    f"{_XML_DECLARATION}"
    f'<hv:HCFVersion xmlns:hv="{_NS_VERSION}" tagetApplication="WORDPROCESSOR"'
    ' major="5" minor="1" micro="1" buildNumber="0" os="1" xmlVersion="1.5"'
    ' application="Easy-Read" appVersion="1.0"/>'
)

#: 패키지 목록·읽기 순서(OPF 형식). 우리는 구역을 하나만 만든다.
#: 메타데이터에 문서 제목을 넣지 않는 이유: 제목은 본문 첫 줄에서 유도한 사용자 입력이라,
#: 굳이 파일 안 여러 자리에 복사해 두면 이스케이프해야 할 표면만 늘어난다(파일명에는 있다).
_CONTENT_HPF = (
    f"{_XML_DECLARATION}"
    '<opf:package xmlns:opf="http://www.idpf.org/2007/opf/" version="1.4"'
    ' unique-identifier="" id="">'
    "<opf:metadata><opf:title/><opf:language>ko</opf:language></opf:metadata>"
    "<opf:manifest>"
    '<opf:item id="header" href="Contents/header.xml" media-type="application/xml"/>'
    '<opf:item id="section0" href="Contents/section0.xml" media-type="application/xml"/>'
    "</opf:manifest>"
    "<opf:spine>"
    '<opf:itemref idref="header" linear="yes"/><opf:itemref idref="section0" linear="yes"/>'
    "</opf:spine>"
    "</opf:package>"
)

#: 문서 전역 설정. 본문이 참조하는 정의를 종류마다 하나씩만 둔다 — 우리 산출물에는 서식이
#: 없어 그 이상이 필요 없다. **규칙: 본문(section0.xml)의 모든 `*IDRef`는 여기 있는 `id`로
#: 풀려야 하고, 각 묶음의 `itemCnt`는 자식 수와 같아야 한다.** 그래서 참조하지 않는 정의는
#: 넣지 않고, 참조하는 정의는 빠뜨리지 않는다.
_HEADER_XML = (
    f"{_XML_DECLARATION}"
    f'<hh:head xmlns:hh="{_NS_HEAD}" version="1.5" secCnt="1">'
    '<hh:beginNum page="1" footnote="1" endnote="1" pic="1" tbl="1" equation="1"/>'
    "<hh:refList>"
    '<hh:fontfaces itemCnt="1">'
    '<hh:fontface lang="HANGUL" fontCnt="1">'
    # 함초롬바탕은 한컴 오피스 기본 글꼴이다. 없는 환경에서는 편집기가 대체 글꼴을 쓴다.
    '<hh:font id="0" face="함초롬바탕" type="TTF" isEmbedded="0"/>'
    "</hh:fontface>"
    "</hh:fontfaces>"
    '<hh:borderFills itemCnt="1">'
    # 글자 속성이 참조하는 "테두리 없음" 정의. 참조가 하나라 id도 하나면 된다.
    '<hh:borderFill id="1" threeD="0" shadow="0" centerLine="NONE" breakCellSeparateLine="0">'
    '<hh:slash type="NONE" Crooked="0" isCounter="0"/>'
    '<hh:backSlash type="NONE" Crooked="0" isCounter="0"/>'
    '<hh:leftBorder type="NONE" width="0.1 mm" color="#000000"/>'
    '<hh:rightBorder type="NONE" width="0.1 mm" color="#000000"/>'
    '<hh:topBorder type="NONE" width="0.1 mm" color="#000000"/>'
    '<hh:bottomBorder type="NONE" width="0.1 mm" color="#000000"/>'
    '<hh:diagonal type="SOLID" width="0.1 mm" color="#000000"/>'
    "</hh:borderFill>"
    "</hh:borderFills>"
    '<hh:charProperties itemCnt="1">'
    # height 단위는 1/100pt다 — 1000이면 10pt(공문서 본문 기본값).
    '<hh:charPr id="0" height="1000" textColor="#000000" shadeColor="none"'
    ' useFontSpace="0" useKerning="0" symMark="NONE" borderFillIDRef="1">'
    '<hh:fontRef hangul="0" latin="0" hanja="0" japanese="0" other="0" symbol="0" user="0"/>'
    '<hh:underline type="NONE" shape="SOLID" color="#000000"/>'
    '<hh:strikeout shape="NONE" color="#000000"/>'
    '<hh:outline type="NONE"/>'
    '<hh:shadow type="NONE" color="#C0C0C0" offsetX="10" offsetY="10"/>'
    "</hh:charPr>"
    "</hh:charProperties>"
    '<hh:tabProperties itemCnt="1"><hh:tabPr id="0" autoTabLeft="0" autoTabRight="0"/>'
    "</hh:tabProperties>"
    '<hh:paraProperties itemCnt="1">'
    '<hh:paraPr id="0" tabPrIDRef="0" condense="0" fontLineHeight="0" snapToGrid="1"'
    ' suppressLineNumbers="0" checked="0">'
    '<hh:align horizontal="JUSTIFY" vertical="BASELINE"/>'
    '<hh:heading type="NONE" idRef="0" level="0"/>'
    '<hh:lineSpacing type="PERCENT" value="160" unit="HWPUNIT"/>'
    "</hh:paraPr>"
    "</hh:paraProperties>"
    '<hh:styles itemCnt="1">'
    # 한컴의 기본 문단 스타일 이름(바탕글). 본문 문단이 이 하나만 쓴다.
    '<hh:style id="0" type="PARA" name="바탕글" engName="Normal" paraPrIDRef="0"'
    ' charPrIDRef="0" nextStyleIDRef="0" langID="1042" lockForm="0"/>'
    "</hh:styles>"
    "</hh:refList>"
    "</hh:head>"
)

#: 구역 설정(쪽 크기·여백). **첫 문단의 첫 run 안에** 놓는 것이 OWPML 규칙이라 문단 조립과
#: 떼어놓을 수 없다. 크기는 A4(HWPUNIT = 1/7200인치)다.
#: 실제 한컴 산출물에 있는 `outlineShapeIDRef`(개요 번호 정의 참조)는 넣지 않는다 —
#: 우리는 개요 번호 정의를 만들지 않으므로, 적으면 풀리지 않는 참조가 된다.
_SECTION_PROPERTIES = (
    '<hp:secPr id="" textDirection="HORIZONTAL" spaceColumns="1134" tabStop="8000"'
    ' tabStopVal="4000" tabStopUnit="HWPUNIT" memoShapeIDRef="0"'
    ' textVerticalWidthHead="0" masterPageCnt="0">'
    '<hp:grid lineGrid="0" charGrid="0" wonggojiFormat="0"/>'
    '<hp:startNum pageStartsOn="BOTH" page="0" pic="0" tbl="0" equation="0"/>'
    '<hp:visibility hideFirstHeader="0" hideFirstFooter="0" hideFirstMasterPage="0"'
    ' border="SHOW_ALL" fill="SHOW_ALL" hideFirstPageNum="0" hideFirstEmptyLine="0"'
    ' showLineNumber="0"/>'
    '<hp:pagePr landscape="WIDELY" width="59528" height="84186" gutterType="LEFT_ONLY">'
    '<hp:margin header="4252" footer="4252" gutter="0" left="8504" right="8504"'
    ' top="5668" bottom="4252"/>'
    "</hp:pagePr>"
    "</hp:secPr>"
)


def build_hwpx(paragraphs: Sequence[str]) -> bytes:
    """문단 목록으로 hwpx 패키지 바이트를 만든다.

    Args:
        paragraphs: 문단 텍스트. 제어문자는 이미 걸러진 상태로 들어와야 한다
            (`render_export` 진입부에서 한 번에 한다).

    구역(section)은 하나만 만든다. 구역은 쪽 설정이 바뀌는 자리를 나누는 단위이고,
    우리 산출물은 처음부터 끝까지 같은 설정이라 나눌 이유가 없다.
    """
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        # mimetype은 스펙상 **첫 항목·무압축**이다. 매직 바이트만 보고 형식을 알아보는
        # 도구가 압축을 풀지 않고도 읽을 수 있어야 하기 때문이다.
        _write(archive, "mimetype", MIMETYPE, compress=False)
        _write(archive, "version.xml", _VERSION_XML)
        _write(archive, "Contents/header.xml", _HEADER_XML)
        _write(archive, "Contents/section0.xml", _section_xml(paragraphs))
        _write(archive, "Contents/content.hpf", _CONTENT_HPF)
        _write(archive, "META-INF/container.xml", _CONTAINER_XML)
        _write(archive, "META-INF/manifest.xml", _MANIFEST_XML)
    return buffer.getvalue()


def _write(archive: zipfile.ZipFile, name: str, payload: str, *, compress: bool = True) -> None:
    """항목 하나를 고정 시각으로 기록한다 (같은 내용 → 같은 바이트)."""
    info = zipfile.ZipInfo(name, date_time=_ZIP_TIMESTAMP)
    archive.writestr(info, payload, zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED)


def _section_xml(paragraphs: Sequence[str]) -> str:
    """구역 XML을 만든다: `<hs:sec>` > `<hp:p>` > `<hp:run>` > `<hp:t>`.

    첫 문단의 첫 run에는 구역 설정(`<hp:secPr>`)이 들어간다 — OWPML은 쪽 설정을 그 자리에
    두고, 없으면 편집기가 구역을 배치하지 못한다. 그래서 문단이 하나도 없어도 **빈 문단
    하나는 반드시 만든다**(실제 한컴의 빈 문서도 같은 모양이다).

    `<hp:t>` 안의 텍스트는 이스케이프한다 — 본문에 `&`나 `<`가 있으면(공문서에서 흔하다)
    XML이 깨져 우리 추출기부터 열지 못한다. 속성에는 사용자 입력을 넣지 않으므로
    따옴표까지 바꿀 필요는 없다.
    """
    blocks: list[str] = []
    for index, text in enumerate(paragraphs or [""]):
        settings = f'<hp:run charPrIDRef="0">{_SECTION_PROPERTIES}</hp:run>' if index == 0 else ""
        blocks.append(
            f'<hp:p id="{index}" paraPrIDRef="0" styleIDRef="0" pageBreak="0"'
            f' columnBreak="0" merged="0">'
            f'{settings}<hp:run charPrIDRef="0"><hp:t>{escape(text)}</hp:t></hp:run>'
            f"</hp:p>"
        )
    body = "".join(blocks)
    return (
        f"{_XML_DECLARATION}"
        f'<hs:sec xmlns:hs="{_NS_SECTION}" xmlns:hp="{_NS_PARAGRAPH}">{body}</hs:sec>'
    )
