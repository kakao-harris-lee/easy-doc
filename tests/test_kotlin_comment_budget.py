"""Kotlin 소스 주석이 리뷰 이력 저장소로 다시 커지는 것을 막는 크기 래칫.

## 축을 둘로 갈랐다 (X-16, 2026-08-23)

종전 판은 제품과 테스트를 **한 분모**로 묶어 전역 상한 하나만 봤다. 실측(2026-08-23):
제품 116 파일 73,700 자 · 테스트 137 파일 56,227 자 = 129,927 / 130,000 — **한 곳도
뚱뚱하지 않은데 총합이 99.9% 였다.** 그 상태에서는 어느 쪽의 정당한 한 줄도 반대쪽을
깎아야 들어가고, 압력이 「어디를 줄일 것인가」가 아니라 「상한을 올릴 것인가」로 향한다.

**그래서 분모를 갈랐고, 종전 전역 상한은 축별 상한이 대신한다.** 남겨 두면 축별 상한이
그보다 먼저 닿을 수 없어 **도달 0** 이 된다 — 갈라 놓고 아무것도 안 바뀌는 판이다.
축별 상한은 무한이 아니다: 테스트 축도 상한을 갖고, 파일별 상한은 두 축 공통이다.

## 예산의 *취지*를 재는 축 (같은 회차)

크기만 재면 「무엇을 쌓았는가」는 안 보인다. `CLAUDE.md` 가 금지한 것 중 **기계로 잴 수
있는 둘**(날짜 표기·커밋 SHA)을 세어 **여유 0 상한**에 건다. 줄이는 방향은 늘 통과하므로
라쳇이고, 늘리려면 상수를 함께 고쳐야 해서 그 diff 가 리뷰 신호가 된다.

**못 재는 것**: 리뷰 ID·실측 로그·사건 이력·기각한 대안은 형태가 없어 세지 못한다.
이 축이 덮는 것은 금지 목록의 **둘뿐**이다.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND_KOTLIN = ROOT / "backend-kotlin"

#: 테스트 축을 가르는 경로 조각. Gradle 규약이라 모듈이 늘어도 그대로다.
TEST_SOURCE_MARKER = "/src/test/"

#: 축별 주석 총량 상한. 값은 2026-08-23 실측 + 좁은 여유다.
#: 제품 73,700 → 76,000 (여유 3.1%) · 테스트 56,227 → 60,000 (여유 6.7%).
#: 테스트 쪽 여유가 더 넓은 이유는 압력이 그쪽에 있기 때문이고(X-16 의 전제),
#: 한 파일이 그 여유를 독식하는 것은 [MAX_COMMENT_CHARS_PER_FILE] 이 막는다.
MAX_PRODUCT_COMMENT_CHARS = 76_000
MAX_TEST_COMMENT_CHARS = 60_000

#: 파일 하나의 상한. 두 축 공통이다.
MAX_COMMENT_CHARS_PER_FILE = 5_500

#: 이력 표식 — `CLAUDE.md` 금지 목록 중 기계로 잴 수 있는 둘.
HISTORY_MARK_PATTERNS = (
    re.compile(r"20\d{2}-\d{2}-\d{2}"),
    re.compile(r"\b[0-9a-f]{7,40}\b"),
)

#: 축별 이력 표식 상한. **여유 0** — 2026-08-23 실측 그대로다.
#: 제품 13 = 날짜 12 + SHA 1(`FlywayBaselineGuard.kt`) · 테스트 5 = 날짜 5 + SHA 0.
MAX_PRODUCT_HISTORY_MARKS = 13
MAX_TEST_HISTORY_MARKS = 5


def _kotlin_sources() -> list[Path]:
    return sorted(path for path in BACKEND_KOTLIN.rglob("*.kt") if "build" not in path.parts)


def _is_test_source(path: Path) -> bool:
    return TEST_SOURCE_MARKER in path.as_posix()


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


def _history_marks(comments: str) -> list[str]:
    found: list[str] = []
    for pattern in HISTORY_MARK_PATTERNS:
        found.extend(pattern.findall(comments))
    return found


def _comment_sizes() -> dict[Path, tuple[int, bool, int]]:
    """파일별 `(주석 문자 수, 테스트 축인가, 이력 표식 수)`."""
    sizes: dict[Path, tuple[int, bool, int]] = {}
    for path in _kotlin_sources():
        comments = _comment_text(path.read_text(encoding="utf-8"))
        sizes[path.relative_to(ROOT)] = (
            len(comments),
            _is_test_source(path),
            len(_history_marks(comments)),
        )
    return sizes


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


def test_축_분류는_gradle_경로_규약을_따른다() -> None:
    """분모를 가르는 술어가 실제로 두 축을 만들어 내는지 본다.

    **한쪽이 0 이면 그 축의 상한은 아무것도 재지 않는다** — 갈라 놓고 조용히 한 덩어리로
    남는 판이 이 종류의 빈자리다(SKILL.md 규칙 4 ⑶).
    """
    assert _is_test_source(Path("backend-kotlin/core/src/test/kotlin/A.kt"))
    assert not _is_test_source(Path("backend-kotlin/core/src/main/kotlin/A.kt"))

    sizes = _comment_sizes()
    assert sizes, "Kotlin 소스가 0개라 주석 예산 검사가 무효화됐다."
    product = [path for path, (_, is_test, _) in sizes.items() if not is_test]
    tests = [path for path, (_, is_test, _) in sizes.items() if is_test]
    assert product and tests, (
        f"두 축 중 하나가 비었다 — 제품 {len(product)} · 테스트 {len(tests)}.\n"
        f"  경로 규약(`{TEST_SOURCE_MARKER}`)이 바뀌었을 수 있다."
    )


def test_kotlin_comment_budget() -> None:
    sizes = _comment_sizes()
    assert sizes, "Kotlin 소스가 0개라 주석 예산 검사가 무효화됐다."

    for label, is_test, limit in (
        ("제품", False, MAX_PRODUCT_COMMENT_CHARS),
        ("테스트", True, MAX_TEST_COMMENT_CHARS),
    ):
        axis = {path: size for path, (size, flag, _) in sizes.items() if flag is is_test}
        total = sum(axis.values())
        largest = sorted(axis.items(), key=lambda item: item[1], reverse=True)[:10]
        report = "\n".join(f"  {size:>6}  {path}" for path, size in largest)
        assert total <= limit, (
            f"{label} 축 Kotlin 주석이 예산을 넘었다: {total} > {limit}\n{report}\n"
            "리뷰 이력은 docs/migration/_workspace/로 옮기고 기존 KDoc을 교체·압축하라.\n"
            "반대쪽 축의 여유로 메우지 마라 — 축을 가른 이유가 그것이다."
        )

    oversized = [
        (path, size) for path, (size, _, _) in sizes.items() if size > MAX_COMMENT_CHARS_PER_FILE
    ]
    assert not oversized, (
        f"파일별 Kotlin 주석 예산({MAX_COMMENT_CHARS_PER_FILE})을 넘었다:\n"
        + "\n".join(f"  {size:>6}  {path}" for path, size in sorted(oversized))
    )


def test_주석에_이력_표식이_늘지_않는다() -> None:
    """예산의 **취지**를 재는 축 — 크기가 아니라 「무엇을 쌓았는가」다.

    날짜와 커밋 SHA 는 `CLAUDE.md` 가 `.kt` 에 쌓지 말라고 정한 것들이고, 둘은 형태가 있어
    셀 수 있다. 상한은 **여유 0** 이라 한 건만 늘어도 상수를 함께 고쳐야 하고, 그 diff 가
    리뷰 신호다. 줄이는 방향은 언제나 통과한다.

    **오탐 가능성을 숨기지 않는다**: SHA 패턴은 주석 안의 임의 16진 문자열도 잡는다. 그것이
    정당한 설명이면 상한을 올리는 diff 로 근거를 남기고 지나가는 것이 이 축의 사용법이다.
    """
    sizes = _comment_sizes()
    assert sizes, "Kotlin 소스가 0개라 이력 표식 검사가 무효화됐다."

    for label, is_test, limit in (
        ("제품", False, MAX_PRODUCT_HISTORY_MARKS),
        ("테스트", True, MAX_TEST_HISTORY_MARKS),
    ):
        marked = {
            path: count for path, (_, flag, count) in sizes.items() if flag is is_test and count
        }
        total = sum(marked.values())
        assert total <= limit, (
            f"{label} 축 주석의 이력 표식(날짜·커밋 SHA)이 {total} 건으로 상한 {limit} 을 "
            "넘었다:\n"
            + "\n".join(f"  {count:>3}  {path}" for path, count in sorted(marked.items()))
            + "\n\n날짜·커밋 SHA·리뷰 ID·사건 이력은 `.kt` 가 아니라\n"
            "  docs/migration/_workspace/ 의 계획·리뷰 산출물이나 커밋 메시지에 둔다.\n"
            "  정당한 예외라면 이 상수를 올리는 diff 로 근거를 남겨라 — 조용히 늘지 않는다."
        )
