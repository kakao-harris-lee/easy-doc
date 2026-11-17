#!/usr/bin/env python3
"""토스페이먼츠 결제 계약 심사용 「결제경로」 PPT 생성 (운영 도구, 제품 코드 아님).

`capture.mjs`가 남긴 `out/manifest.json`을 읽어 16:9 슬라이드 6장을 만든다.
캡처가 없거나 실패·건너뜀이면 그 자리에 「최종 캡처 필요」 회색 자리표시자를 넣어,
운영자가 무엇을 더 찍어야 하는지 슬라이드 자체가 말하게 한다.

**지켜야 할 선**: 이 스크립트는 그림 위에 주소창·시계·작업표시줄을 그리지 않는다.
카드사는 캡처의 시각 정보로 조작 여부를 확인하므로, 가짜 브라우저 크롬을 합성하면
서류 전체의 신뢰가 깨진다. 스크립트가 그리는 것은 파란 강조 사각형과 캡션뿐이다.

운영자 교체본(`final/`)
----------------------
운영자가 자기 PC에서 전체 화면으로 다시 찍은 그림을 `final/`에 **manifest와 같은 파일
이름**으로 넣으면 그 그림이 우선한다. 좌표계가 달라지므로 자동 강조 사각형은 그리지
않는다. 강조가 필요하면 `final/highlights.json`에 좌표를 직접 적는다::

    {
      "02-footer.png": {
        "image_size": {"width": 1920, "height": 1080},
        "rects": [
          {"x": 40, "y": 820, "width": 1840, "height": 220, "label": "사업자 정보"}
        ]
      }
    }

``image_size``는 좌표를 잰 기준 크기다(생략하면 그림의 실제 픽셀 크기로 본다).
``rects``의 좌표 단위는 그 기준 크기 안의 픽셀이고 ``label``은 캡션에 덧붙는다.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Emu, Inches, Pt

SCRIPT_DIR = Path(__file__).resolve().parent

SLIDE_WIDTH = Inches(13.333)
SLIDE_HEIGHT = Inches(7.5)
MARGIN = Inches(0.55)
TITLE_TOP = Inches(0.33)
TITLE_HEIGHT = Inches(0.72)
CONTENT_TOP = Inches(1.28)
CONTENT_BOTTOM = Inches(0.45)
CAPTION_HEIGHT = Inches(0.52)
IMAGE_GAP = Inches(0.3)

KOREAN_FONT = "맑은 고딕"
HIGHLIGHT_COLOR = RGBColor(0x1E, 0x64, 0xFF)
HIGHLIGHT_WIDTH = Pt(3)
TITLE_COLOR = RGBColor(0x11, 0x18, 0x27)
CAPTION_COLOR = RGBColor(0x4B, 0x55, 0x63)
PLACEHOLDER_FILL = RGBColor(0xE5, 0xE7, 0xEB)
PLACEHOLDER_LINE = RGBColor(0x9C, 0xA3, 0xAF)
PLACEHOLDER_TEXT = RGBColor(0x37, 0x41, 0x51)

MERCHANT_ROWS = [
    ("상호명", "몬딱 솔루션"),
    ("사업자번호", "671-47-01204"),
    ("가맹점 URL", "https://easydoc.kr/usage"),
    ("테스트 ID", "toss-review@easydoc.kr"),
    ("테스트 PW", "PW는 메일 본문 참조"),
]


@dataclass(frozen=True)
class ExpectedStep:
    """manifest가 없어도 슬라이드 6장이 나오도록, 기대하는 캡처를 코드에 고정한다."""

    step_id: str
    slide: int
    url: str
    state: str


EXPECTED_STEPS: tuple[ExpectedStep, ...] = (
    ExpectedStep("02-footer", 2, "https://easydoc.kr/", "홈 화면 맨 아래(푸터 전체)"),
    ExpectedStep("03-refund", 3, "https://easydoc.kr/terms", "이용약관 제9조(유료 이용)"),
    ExpectedStep(
        "04a-login-redirect", 4, "https://easydoc.kr/usage", "비로그인 접근 → 로그인 화면"
    ),
    ExpectedStep("04b-login-filled", 4, "https://easydoc.kr/login", "아이디·비밀번호 입력 완료"),
    ExpectedStep("05a-plan-select", 5, "https://easydoc.kr/usage", "Start 플랜 선택 화면"),
    ExpectedStep("05b-plan-consent", 5, "https://easydoc.kr/usage", "결제 조건 동의 체크 완료"),
    ExpectedStep(
        "06-billing-window", 6, "https://easydoc.kr/usage", "토스페이먼츠 카드 등록창(미입력)"
    ),
)

SLIDE_TITLES = {
    1: "① 가맹점 정보",
    2: "② 하단 정보",
    3: "③ 환불 규정",
    4: "④ 로그인",
    5: "⑤ 상품 선택·구매",
    6: "⑥ 정기결제 카드 입력창",
}


@dataclass
class Panel:
    """슬라이드 한 칸 — 그림 하나 또는 자리표시자 하나."""

    step_id: str
    url: str
    state: str
    image_path: Path | None = None
    from_final: bool = False
    rects: list[dict] = field(default_factory=list)
    reference_size: tuple[int, int] | None = None
    note: str = ""


def style_run(run, *, size: Pt, bold: bool = False, color: RGBColor | None = None) -> None:
    run.font.size = size
    run.font.bold = bold
    run.font.name = KOREAN_FONT
    if color is not None:
        run.font.color.rgb = color


def add_title(slide, text: str) -> None:
    box = slide.shapes.add_textbox(
        MARGIN, TITLE_TOP, SLIDE_WIDTH - MARGIN * 2, TITLE_HEIGHT
    )
    frame = box.text_frame
    frame.word_wrap = True
    run = frame.paragraphs[0].add_run()
    run.text = text
    style_run(run, size=Pt(28), bold=True, color=TITLE_COLOR)


def image_size(path: Path) -> tuple[int, int]:
    with Image.open(path) as image:
        return image.size


def load_manifest(manifest_path: Path) -> dict:
    if not manifest_path.is_file():
        return {"steps": []}
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def load_final_highlights(final_dir: Path) -> dict:
    """`final/highlights.json`을 읽는다 — 손으로 쓰는 파일이라 오류를 친절히 알린다."""
    path = final_dir / "highlights.json"
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(
            f"{path}: JSON 형식이 아닙니다 ({error.lineno}행 {error.colno}열: {error.msg}). "
            "README의 형식 예시를 확인하세요."
        ) from error
    if not isinstance(data, dict):
        raise SystemExit(f"{path}: 최상위가 객체여야 합니다(파일 이름 → 좌표).")
    for name, override in data.items():
        if not isinstance(override, dict):
            raise SystemExit(f'{path}: "{name}" 값이 객체여야 합니다.')
        for index, rect in enumerate(override.get("rects", [])):
            missing = [key for key in ("x", "y", "width", "height") if key not in rect]
            if missing:
                raise SystemExit(
                    f'{path}: "{name}"의 {index + 1}번째 rect에 {", ".join(missing)} 값이 없습니다. '
                    "x·y·width·height 는 모두 있어야 합니다."
                )
    return data


def build_panels(manifest: dict, out_dir: Path, final_dir: Path) -> dict[int, list[Panel]]:
    steps_by_id = {step.get("id"): step for step in manifest.get("steps", [])}
    final_highlights = load_final_highlights(final_dir)
    panels: dict[int, list[Panel]] = {slide: [] for slide in SLIDE_TITLES}

    for expected in EXPECTED_STEPS:
        entry = steps_by_id.get(expected.step_id, {})
        panel = Panel(
            step_id=expected.step_id,
            url=entry.get("url") or expected.url,
            state=entry.get("state") or expected.state,
        )

        image_name = entry.get("image") or f"{expected.step_id}.png"
        final_image = final_dir / image_name
        auto_image = out_dir / image_name if entry.get("image") else None

        if final_image.is_file():
            panel.image_path = final_image
            panel.from_final = True
            override = final_highlights.get(image_name, {})
            panel.rects = list(override.get("rects", []))
            size = override.get("image_size")
            if size is not None:
                panel.reference_size = (int(size["width"]), int(size["height"]))
            panel.note = "운영자 최종 캡처(final/)"
        elif auto_image is not None and auto_image.is_file():
            panel.image_path = auto_image
            panel.rects = list(entry.get("highlights", []))
            viewport = entry.get("viewport") or manifest.get("viewport")
            if viewport is not None:
                panel.reference_size = (int(viewport["width"]), int(viewport["height"]))
            panel.note = "자동 캡처 초안 — 최종본으로 교체 필요"
        else:
            status = entry.get("status", "missing")
            reason = entry.get("reason") or "캡처가 없습니다"
            panel.note = f"{status}: {reason}"

        panels[expected.slide].append(panel)

    return panels


def add_merchant_table(slide) -> None:
    rows, cols = len(MERCHANT_ROWS), 2
    width = Inches(9.2)
    height = Inches(0.62) * rows
    left = Emu(int((SLIDE_WIDTH - width) / 2))
    table = slide.shapes.add_table(rows, cols, left, CONTENT_TOP + Inches(0.4), width, height).table
    table.columns[0].width = Inches(2.6)
    table.columns[1].width = width - Inches(2.6)

    for index, (label, value) in enumerate(MERCHANT_ROWS):
        for column, text in ((0, label), (1, value)):
            cell = table.cell(index, column)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            paragraph = cell.text_frame.paragraphs[0]
            run = paragraph.add_run()
            run.text = text
            style_run(run, size=Pt(15), bold=(column == 0))


def place_image(slide, panel: Panel, left: Emu, top: Emu, box_w: Emu, box_h: Emu) -> None:
    img_w, img_h = image_size(panel.image_path)
    scale = min(box_w / img_w, box_h / img_h)
    render_w, render_h = int(img_w * scale), int(img_h * scale)
    pic_left = Emu(int(left + (box_w - render_w) / 2))
    pic_top = Emu(int(top))
    slide.shapes.add_picture(
        str(panel.image_path), pic_left, pic_top, width=Emu(render_w), height=Emu(render_h)
    )

    reference = panel.reference_size or (img_w, img_h)
    # manifest 좌표는 뷰포트 CSS px, 그림은 그 뷰포트를 찍은 픽셀이다 — 두 크기가
    # 다를 수 있으므로(고해상도 캡처 등) 먼저 그림 픽셀로 환산한 뒤 EMU로 옮긴다.
    # 가로·세로 비가 다른 기준 크기(운영자가 다른 해상도에서 좌표를 잰 경우)도 있으므로
    # 두 축을 따로 환산한다.
    px_to_emu = (scale * (img_w / reference[0]), scale * (img_h / reference[1]))
    for rect in panel.rects:
        draw_highlight(slide, rect, pic_left, pic_top, px_to_emu, render_w, render_h)


def draw_highlight(
    slide,
    rect: dict,
    pic_left: Emu,
    pic_top: Emu,
    px_to_emu: tuple[float, float],
    render_w: int,
    render_h: int,
) -> None:
    scale_x, scale_y = px_to_emu
    left = int(pic_left + rect["x"] * scale_x)
    top = int(pic_top + rect["y"] * scale_y)
    width = int(rect["width"] * scale_x)
    height = int(rect["height"] * scale_y)
    # 그림 밖으로 나가는 테두리는 캡처에 없는 영역을 가리키게 되므로 잘라낸다.
    right = min(left + width, int(pic_left) + render_w)
    bottom = min(top + height, int(pic_top) + render_h)
    left = max(left, int(pic_left))
    top = max(top, int(pic_top))
    if right <= left or bottom <= top:
        return
    shape = slide.shapes.add_shape(
        MSO_SHAPE.RECTANGLE, Emu(left), Emu(top), Emu(right - left), Emu(bottom - top)
    )
    shape.fill.background()
    shape.line.color.rgb = HIGHLIGHT_COLOR
    shape.line.width = HIGHLIGHT_WIDTH
    shape.shadow.inherit = False


def place_placeholder(slide, panel: Panel, left: Emu, top: Emu, box_w: Emu, box_h: Emu) -> None:
    shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, box_w, box_h)
    shape.fill.solid()
    shape.fill.fore_color.rgb = PLACEHOLDER_FILL
    shape.line.color.rgb = PLACEHOLDER_LINE
    shape.line.width = Pt(1.5)
    shape.shadow.inherit = False
    frame = shape.text_frame
    frame.word_wrap = True
    frame.vertical_anchor = MSO_ANCHOR.MIDDLE
    paragraph = frame.paragraphs[0]
    paragraph.alignment = PP_ALIGN.CENTER
    run = paragraph.add_run()
    run.text = f"최종 캡처 필요: {panel.url}\n{panel.state}"
    style_run(run, size=Pt(16), bold=True, color=PLACEHOLDER_TEXT)


def add_caption(slide, panel: Panel, left: Emu, top: Emu, box_w: Emu) -> None:
    box = slide.shapes.add_textbox(left, top, box_w, CAPTION_HEIGHT)
    frame = box.text_frame
    frame.word_wrap = True
    labels = [rect["label"] for rect in panel.rects if rect.get("label")]
    lines = [f"{panel.url} — {panel.state}"]
    tail = panel.note
    if labels:
        tail = f"{tail} · 파란 표시: {', '.join(labels)}" if tail else f"파란 표시: {', '.join(labels)}"
    if tail:
        lines.append(tail)
    for index, line in enumerate(lines):
        paragraph = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
        run = paragraph.add_run()
        run.text = line
        style_run(run, size=Pt(10), color=CAPTION_COLOR)


def add_panels(slide, panels: list[Panel]) -> None:
    count = len(panels)
    usable = SLIDE_WIDTH - MARGIN * 2
    box_w = Emu(int((usable - IMAGE_GAP * (count - 1)) / count))
    box_h = Emu(int(SLIDE_HEIGHT - CONTENT_TOP - CONTENT_BOTTOM - CAPTION_HEIGHT))

    for index, panel in enumerate(panels):
        left = Emu(int(MARGIN + index * (box_w + IMAGE_GAP)))
        if panel.image_path is not None:
            place_image(slide, panel, left, CONTENT_TOP, box_w, box_h)
        else:
            place_placeholder(slide, panel, left, CONTENT_TOP, box_w, box_h)
        add_caption(slide, panel, left, Emu(int(CONTENT_TOP + box_h + Inches(0.08))), box_w)


def build(manifest_path: Path, out_dir: Path, final_dir: Path, output: Path) -> Path:
    manifest = load_manifest(manifest_path)
    panels = build_panels(manifest, out_dir, final_dir)

    presentation = Presentation()
    presentation.slide_width = SLIDE_WIDTH
    presentation.slide_height = SLIDE_HEIGHT
    blank_layout = presentation.slide_layouts[6]

    for number in sorted(SLIDE_TITLES):
        slide = presentation.slides.add_slide(blank_layout)
        add_title(slide, SLIDE_TITLES[number])
        if number == 1:
            add_merchant_table(slide)
        else:
            add_panels(slide, panels[number])

    output.parent.mkdir(parents=True, exist_ok=True)
    presentation.save(str(output))
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description="토스 결제경로 심사용 PPT를 만든다")
    parser.add_argument("--out-dir", type=Path, default=SCRIPT_DIR / "out")
    parser.add_argument("--final-dir", type=Path, default=SCRIPT_DIR / "final")
    parser.add_argument("--manifest", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    manifest_path = args.manifest or args.out_dir / "manifest.json"
    output = args.output or args.out_dir / "결제경로_몬딱솔루션_빌링.pptx"
    saved = build(manifest_path, args.out_dir, args.final_dir, output)
    print(f"pptx: {saved}")


if __name__ == "__main__":
    main()
