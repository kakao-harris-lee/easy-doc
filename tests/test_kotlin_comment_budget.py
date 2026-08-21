"""Kotlin 소스 주석이 리뷰 이력 저장소로 다시 커지는 것을 막는 크기 래칫."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND_KOTLIN = ROOT / "backend-kotlin"
MAX_TOTAL_COMMENT_CHARS = 130_000
MAX_COMMENT_CHARS_PER_FILE = 5_500


def _kotlin_sources() -> list[Path]:
    return sorted(path for path in BACKEND_KOTLIN.rglob("*.kt") if "build" not in path.parts)


def _comment_text(text: str) -> str:
    comments: list[str] = []
    index = 0
    depth = 0
    start = 0
    while index < len(text):
        if depth:
            if text.startswith("/*", index):
                depth += 1
                index += 2
            elif text.startswith("*/", index):
                depth -= 1
                if depth == 0:
                    comments.append(text[start:index])
                index += 2
            else:
                index += 1
            continue
        if text.startswith("/*", index):
            depth = 1
            start = index + 2
            index += 2
        elif text.startswith("//", index):
            stop = text.find("\n", index)
            stop = len(text) if stop < 0 else stop
            comments.append(text[index + 2 : stop])
            index = stop
        elif text.startswith('"""', index):
            stop = text.find('"""', index + 3)
            index = len(text) if stop < 0 else stop + 3
        elif text[index] == '"':
            index += 1
            while index < len(text) and text[index] != '"':
                index += 2 if text[index] == "\\" else 1
            index += 1
        elif text[index] == "'":
            index += 1
            while index < len(text) and text[index] != "'":
                index += 2 if text[index] == "\\" else 1
            index += 1
        else:
            index += 1
    return "\n".join(comments)


def test_comment_lexer_ignores_kotlin_strings() -> None:
    source = '''
        val url = "https://example.test/path"
        val raw = """// 문자열이지 주석이 아니다"""
        val slash = '/'
        // 실제 줄 주석
        /* 실제 /* 중첩 */ 블록 주석 */
    '''
    comments = _comment_text(source)
    assert "문자열이지" not in comments
    assert "실제 줄 주석" in comments
    assert "중첩" in comments


def test_kotlin_comment_budget() -> None:
    sizes = {
        path.relative_to(ROOT): len(_comment_text(path.read_text(encoding="utf-8")))
        for path in _kotlin_sources()
    }
    assert sizes, "Kotlin 소스가 0개라 주석 예산 검사가 무효화됐다."

    total = sum(sizes.values())
    largest = sorted(sizes.items(), key=lambda item: item[1], reverse=True)[:10]
    report = "\n".join(f"  {size:>6}  {path}" for path, size in largest)
    assert total <= MAX_TOTAL_COMMENT_CHARS, (
        f"Kotlin 주석이 전체 예산을 넘었다: {total} > {MAX_TOTAL_COMMENT_CHARS}\n{report}\n"
        "리뷰 이력은 docs/migration/_workspace/로 옮기고 기존 KDoc을 교체·압축하라."
    )

    oversized = [(path, size) for path, size in largest if size > MAX_COMMENT_CHARS_PER_FILE]
    assert not oversized, (
        f"파일별 Kotlin 주석 예산({MAX_COMMENT_CHARS_PER_FILE})을 넘었다:\n"
        + "\n".join(f"  {size:>6}  {path}" for path, size in oversized)
    )
