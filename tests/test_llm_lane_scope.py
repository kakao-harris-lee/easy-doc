"""`-m llm` 레인의 **선언 범위와 실제 도달 범위**를 대조한다 (API 호출 없음).

## 무엇을 막는가

`-m llm` 은 실제 API를 호출하므로 매 커밋마다 돌릴 수 없다. 그래서 CI 는
`.github/llm-lane-paths.txt` 에 적힌 경로가 바뀐 커밋에서만 그 레인을 돈다.
이런 장치는 `kotlin-migration` 스킬의 「선언한 범위와 실제 도달을 대조한다」 절이
말하는 **범위 선언형**이고, 그 절 규칙 4 ⑶ 이 그대로 적용된다 —
**빈 선언에서 통과하면 안 되고, 선언이 실제 도달보다 좁으면 게이트가 조용히 안 돈다.**

좁아지는 경로는 구체적이다. 누가 `app/easyread/style_rules.py` 에 새 의존을 붙이면
골든 레인의 판정이 달라지는데, 그 새 파일이 목록에 없으면 **레인이 안 돌고 CI 는
초록**이다. 근거 6번(품질 합격선이 CI 도달 0인 채로 "확정" 보고됨)이 같은 형태였다.

그래서 이 파일이 하는 일은 하나다 — **`-m llm` 대상 모듈에서 출발한 1st-party
import 이행 폐포를 매번 다시 계산해, 그 전부가 목록에 덮이는지 단언한다.**
안 덮이면 파일 이름을 지목하며 실패한다.

## 지금 어디서 도는가 (규칙 3)

- `ci:quality` — `uv run pytest` 가 이 파일을 수집한다.
- `ci:llm-lane` — 잡의 첫 두 스텝이 `python3 -m tests.test_llm_lane_scope` 를
  **경로로 명시 호출**한다. 하나는 `--self-check`(완전성 검사), 하나는
  `--match`(변경 파일 대조)다.

두 번째가 중요하다. 규칙 5·6 이 말하듯 **장치 안에 넣은 자기 단언은 장치와 함께
사라진다** — 이 파일을 지우면 여기 적힌 단언도 같이 지워지고 스위트는 초록이다.
그런데 `llm-lane` 잡은 이 모듈을 import 해서 변경 범위를 판정하므로, 파일이 사라지면
`No module named tests.test_llm_lane_scope` 로 **잡이 깨진다.** 장치 밖에서 깨지는
지점을 그렇게 만든다.

## 왜 pytest 를 import 하지 않는가

CI 의 판정 스텝은 `uv sync` 전에, 러너의 맨 python3 로 이 모듈을 돌린다. 의존이
없어야 그게 된다. 그래서 단언은 전부 평문 `assert` 다 — pytest 는 함수 이름만 보고
수집하므로 import 없이도 테스트로 돈다.

## 마커를 텍스트로 찾지 않는 이유 (실측)

`grep -rn 'mark\\.llm' tests/` 는 이 저장소에서 **오탐을 낸다** —
`tests/test_harness_scope_reach.py` 가 문서에서 `pytest.mark.llm` 을 *언급만* 한다.
그것을 대상으로 잡으면 폐포가 엉뚱하게 넓어지고, 넓어진 폐포는 "목록이 부족하다"는
거짓 실패를 만들어 결국 목록을 필요 이상으로 넓히게 된다. 그래서 AST 로 **실제 마커
적용**만 센다. 합성 입력으로 그 오탐이 안 나는 것을 아래에서 회귀로 고정했다.

교차 검증: `uv run pytest -m llm --collect-only -q` 의 실제 선택 결과와 이 AST
판별이 같은 모듈 집합을 내는지도 아래에서 확인한다(수집만 — 호출하지 않는다).
"""

from __future__ import annotations

import ast
import re
import subprocess
import sys
import tempfile
from collections.abc import Iterable, Sequence
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PATHS_FILE = ROOT / ".github" / "llm-lane-paths.txt"
WORKFLOW_FILE = ROOT / ".github" / "workflows" / "ci.yml"

#: 이행 폐포를 따라갈 최상위 패키지. 표준 라이브러리·서드파티는 파일이 저장소에 없어
#: 경로로 덮을 수도 없으므로 폐포에서 제외한다(그 구멍은 이 파일 맨 아래 "남는 우회").
FIRST_PARTY = ("app", "tests")


class LaneScopeError(RuntimeError):
    """선언 범위를 판정할 수 없는 상태 — 통과가 아니라 실패로 끝낸다."""


# --------------------------------------------------------------------------
# 경로 목록 읽기 / 패턴 매칭
# --------------------------------------------------------------------------


def parse_patterns(text: str) -> list[str]:
    """목록 본문에서 패턴만 추린다. `#` 주석과 빈 줄은 버린다.

    **빈 결과는 예외다**(규칙 4 ⑶). 목록이 비면 어떤 변경도 레인을 켜지 못하는데,
    그 상태로 CI 가 초록이면 "안 돌았다"와 "돌아서 통과했다"가 구분되지 않는다.
    """
    patterns: list[str] = []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            patterns.append(line)
    if not patterns:
        raise LaneScopeError(
            f"{PATHS_FILE.relative_to(ROOT)} 에 경로 패턴이 한 줄도 없다 — "
            "빈 선언은 '전부 무관'과 같아서 레인이 영원히 안 돈다. 통과시키지 않는다."
        )
    return patterns


def load_patterns() -> list[str]:
    """저장소의 목록 파일을 읽는다. 파일이 없으면 실패한다."""
    if not PATHS_FILE.is_file():
        raise LaneScopeError(
            f"{PATHS_FILE.relative_to(ROOT)} 가 없다 — 무엇이 레인을 켜는지의 선언이 사라졌다."
        )
    return parse_patterns(PATHS_FILE.read_text(encoding="utf-8"))


def _translate(pattern: str) -> str:
    """glob 패턴을 정규식으로 옮긴다. `**` = 0개 이상 세그먼트, `*` = 세그먼트 안."""
    out = ["^"]
    segments = pattern.split("/")
    for index, segment in enumerate(segments):
        last = index == len(segments) - 1
        if segment == "**":
            if last:
                out.append("(?:[^/]+(?:/[^/]+)*)?")
            else:
                out.append("(?:[^/]+/)*")
                continue
        else:
            piece = ""
            for char in segment:
                if char == "*":
                    piece += "[^/]*"
                elif char == "?":
                    piece += "[^/]"
                else:
                    piece += re.escape(char)
            out.append(piece)
        if not last:
            out.append("/")
    out.append("$")
    return "".join(out)


def _normalize(relpath: str) -> str:
    """저장소 루트 기준 상대 경로로 정규화한다.

    `lstrip("./")` 을 쓰지 않는다 — 문자 집합 제거라서 `.github/...` 의 앞 점까지
    먹는다. 실제로 그렇게 짜서 `.github/llm-lane-paths.txt` 가 자기 목록에
    안 덮이는 것으로 판정됐다(음성 대조에서 잡힘).
    """
    text = relpath.replace("\\", "/").strip()
    while text.startswith("./"):
        text = text[2:]
    return text.lstrip("/")


def covering_patterns(patterns: Sequence[str], relpath: str) -> list[str]:
    """`relpath` 를 덮는 패턴들. 비어 있으면 그 파일은 선언 밖이다."""
    normalized = _normalize(relpath)
    return [p for p in patterns if re.match(_translate(p), normalized)]


def is_covered(patterns: Sequence[str], relpath: str) -> bool:
    return bool(covering_patterns(patterns, relpath))


# --------------------------------------------------------------------------
# `-m llm` 대상 모듈 판별 (AST — 텍스트 grep 금지)
# --------------------------------------------------------------------------


def _has_llm_marker(tree: ast.AST) -> bool:
    """`*.mark.llm` 이 **표현식으로** 등장하는지 본다.

    데코레이터(`@pytest.mark.llm`)와 모듈 마커(`pytestmark = pytest.mark.llm`,
    리스트 형태 포함)를 함께 잡는다. 문자열 안의 언급은 `ast.Constant` 라서 절대
    걸리지 않는다 — 그것이 이 판별을 쓰는 이유다.
    """
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and node.attr == "llm":
            owner = node.value
            if isinstance(owner, ast.Attribute) and owner.attr == "mark":
                return True
    return False


def llm_marked_modules(tests_dir: Path | None = None) -> list[Path]:
    """`llm` 마커가 실제로 적용된 테스트 모듈. **0개면 실패한다.**"""
    base = tests_dir if tests_dir is not None else ROOT / "tests"
    found: list[Path] = []
    for path in sorted(base.rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        if _has_llm_marker(tree):
            found.append(path)
    if not found:
        raise LaneScopeError(
            "`llm` 마커가 붙은 테스트 모듈을 하나도 찾지 못했다 — 0건 검사는 통과가 아니다. "
            "마커가 사라졌다면 이 게이트와 CI 잡을 함께 걷어내야 한다."
        )
    return found


def conftest_chain(modules: Iterable[Path], root: Path | None = None) -> set[Path]:
    """pytest 가 그 모듈들에 적용하는 conftest 사슬.

    conftest 는 import 문에 안 적히지만 실제로 로드되어 픽스처·수집 훅을 바꾼다.
    폐포에 넣지 않으면 `tests/conftest.py` 변경이 레인을 안 켜는 구멍이 생긴다.
    """
    base = root if root is not None else ROOT
    chain: set[Path] = set()
    for module in modules:
        directory = module.parent
        while True:
            candidate = directory / "conftest.py"
            if candidate.is_file():
                chain.add(candidate)
            if directory == base:
                break
            directory = directory.parent
    return chain


# --------------------------------------------------------------------------
# 1st-party import 이행 폐포
# --------------------------------------------------------------------------


def _module_files(dotted: str, root: Path) -> list[Path]:
    """`a.b.c` 를 저장소 파일로 푼다. **부모 패키지의 `__init__.py` 도 포함**한다.

    `from tests.golden import X` 는 `tests/__init__.py` 도 실행시킨다 — 부모를 빼면
    폐포가 실제 실행 범위보다 좁아진다.
    """
    parts = dotted.split(".")
    files: list[Path] = []
    for depth in range(1, len(parts) + 1):
        prefix = parts[:depth]
        package_init = root.joinpath(*prefix, "__init__.py")
        if package_init.is_file():
            files.append(package_init)
        module_file = root.joinpath(*prefix).with_suffix(".py")
        if depth == len(parts) and module_file.is_file():
            files.append(module_file)
    return files


def _imported_modules(path: Path, root: Path) -> set[str]:
    """파일이 import 하는 점 표기 모듈 이름들(상대 import 는 절대 경로로 환원)."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    package_parts = path.relative_to(root).parts[:-1]
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                modules.add(alias.name)
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                kept = list(package_parts)[: len(package_parts) - (node.level - 1)]
                dotted = ".".join(kept + ([node.module] if node.module else []))
            else:
                dotted = node.module or ""
            if not dotted:
                continue
            modules.add(dotted)
            # `from app.llm import fake` 의 `fake` 도 모듈일 수 있다.
            for alias in node.names:
                modules.add(f"{dotted}.{alias.name}")
    return modules


def first_party_closure(roots: Iterable[Path], root: Path | None = None) -> set[Path]:
    """출발 모듈들에서 1st-party import 를 따라간 이행 폐포. **비면 실패한다.**"""
    base = root if root is not None else ROOT
    seen: set[Path] = set()
    stack = list(roots)
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        for dotted in _imported_modules(current, base):
            if dotted.split(".")[0] not in FIRST_PARTY:
                continue
            for resolved in _module_files(dotted, base):
                if resolved not in seen:
                    stack.append(resolved)
    if not seen:
        raise LaneScopeError(
            "폐포가 비었다 — 대상 모듈에서 아무 파일도 도달하지 못했다. 0건 검사는 통과가 아니다."
        )
    return seen


def lane_closure() -> list[str]:
    """이 저장소의 `-m llm` 레인 도달 범위(저장소 상대 경로, 정렬)."""
    modules = llm_marked_modules()
    roots = list(modules) + sorted(conftest_chain(modules))
    return sorted(str(p.relative_to(ROOT)) for p in first_party_closure(roots))


def uncovered_files(patterns: Sequence[str] | None = None) -> list[str]:
    """폐포 중 목록이 덮지 못한 파일들."""
    active = list(patterns) if patterns is not None else load_patterns()
    return [rel for rel in lane_closure() if not is_covered(active, rel)]


# --------------------------------------------------------------------------
# 테스트 (평문 assert — pytest import 없이 돈다)
# --------------------------------------------------------------------------


def test_llm_마커_모듈이_0개면_실패한다() -> None:
    """빈 선언 fail-closed ①. 대상이 0개면 폐포도 0개고, 그 통과는 아무 의미가 없다."""
    with tempfile.TemporaryDirectory() as tmp:
        empty = Path(tmp)
        (empty / "test_nothing.py").write_text(
            "def test_x() -> None:\n    pass\n", encoding="utf-8"
        )
        try:
            llm_marked_modules(empty)
        except LaneScopeError as exc:
            assert "하나도 찾지 못했다" in str(exc)
        else:  # pragma: no cover - 회귀 시에만 도달
            raise AssertionError("마커 모듈 0개인데 실패하지 않았다 — 0건 통과 경로가 열려 있다")


def test_문서에만_언급된_마커는_대상이_아니다() -> None:
    """grep 오탐 회귀 고정.

    이 저장소의 실제 오탐: `tests/test_harness_scope_reach.py` 가 문서에서
    `pytest.mark.llm` 을 언급만 하는데 텍스트 검색은 그것을 대상으로 잡는다.
    합성 입력으로 같은 형태를 만들어, AST 판별이 걸리지 않는 것을 고정한다.
    """
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        mention = base / "test_문서_언급.py"
        mention.write_text(
            '"""이 파일은 `pytest.mark.llm` 을 문서에서 언급만 한다."""\n\n'
            'NOTE = "pytestmark = pytest.mark.llm 로 전건 제외할 수 있다"\n\n\n'
            "def test_x() -> None:\n    pass\n",
            encoding="utf-8",
        )
        real = base / "test_실제_마커.py"
        real.write_text(
            "import pytest\n\n\n@pytest.mark.llm\ndef test_y() -> None:\n    pass\n",
            encoding="utf-8",
        )

        # 텍스트 검색이었다면 둘 다 걸린다 — 그 사실 자체를 단언해 둔다.
        assert "mark.llm" in mention.read_text(encoding="utf-8")

        detected = llm_marked_modules(base)
        assert real in detected, "실제 마커가 붙은 모듈을 놓쳤다"
        assert mention not in detected, "문서 언급만 하는 파일을 대상으로 잡았다(grep 오탐 재현)"


def test_ast_판별이_pytest_실제_선택과_일치한다() -> None:
    """규칙 2 — 대리 측정으로 대신하지 않는다. 실제 선택자와 대조한다.

    `--collect-only` 라서 **API 를 호출하지 않는다**(수집은 테스트 본문을 실행하지 않는다).
    """
    command = [
        sys.executable,
        "-m",
        "pytest",
        "-m",
        "llm",
        "--collect-only",  # 수집만 — 테스트 본문을 실행하지 않으므로 API 호출이 없다
        "-q",
        "-p",
        "no:cacheprovider",
    ]
    proc = subprocess.run(
        command,
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=300,
    )
    assert proc.returncode == 0, f"수집 실패:\n{proc.stdout[-2000:]}\n{proc.stderr[-2000:]}"

    selected: set[str] = set()
    for line in proc.stdout.splitlines():
        if "::" in line and line.strip().startswith("tests/"):
            selected.add(line.split("::", 1)[0].strip())
    assert selected, "pytest 가 `-m llm` 으로 아무것도 선택하지 않았다 — 0건은 통과가 아니다"

    detected = {str(p.relative_to(ROOT)) for p in llm_marked_modules()}
    assert detected == selected, (
        "AST 판별과 pytest 실제 선택이 다르다.\n"
        f"  AST 만 잡음   : {sorted(detected - selected)}\n"
        f"  pytest 만 잡음: {sorted(selected - detected)}"
    )


def test_폐포가_비어_있으면_실패한다() -> None:
    """빈 선언 fail-closed ②."""
    try:
        first_party_closure([])
    except LaneScopeError as exc:
        assert "폐포가 비었다" in str(exc)
    else:  # pragma: no cover - 회귀 시에만 도달
        raise AssertionError("폐포 0개인데 실패하지 않았다 — 0건 통과 경로가 열려 있다")


def test_빈_목록은_실패한다() -> None:
    """빈 선언 fail-closed ③ — 주석만 남기고 비워도 실패해야 한다."""
    for body in ("", "\n\n", "# 전부 주석\n#   app/config.py\n"):
        try:
            parse_patterns(body)
        except LaneScopeError as exc:
            assert "한 줄도 없다" in str(exc)
        else:  # pragma: no cover - 회귀 시에만 도달
            raise AssertionError(f"빈 목록({body!r})인데 통과했다 — 레인이 영원히 안 돈다")


def test_경로_목록이_폐포를_전부_덮는다() -> None:
    """본 단언. 안 덮인 파일이 있으면 **이름으로 지목**한다."""
    patterns = load_patterns()
    closure = lane_closure()
    assert closure, "폐포가 비었다"

    missing = [rel for rel in closure if not is_covered(patterns, rel)]
    assert not missing, (
        "`-m llm` 레인이 실제로 import 하는데 "
        f"{PATHS_FILE.relative_to(ROOT)} 가 덮지 않는 파일 {len(missing)}건:\n"
        + "\n".join(f"  - {rel}" for rel in missing)
        + "\n\n이 상태로 두면 그 파일만 고친 커밋에서 레인이 안 돌고 CI 는 초록이다."
    )


def test_목록에서_한_줄을_빼면_그_파일을_지목한다() -> None:
    """음성 대조(규칙 5). 장치를 **한 줄** 떼면 정확히 무엇이 깨지는지 확인한다.

    덮는 패턴이 둘 이상이면 한 줄을 빼도 아무 일이 없다 — 그런 목록은 "빼도
    안전하다"는 거짓 신호를 준다. 그래서 폐포 파일마다 덮는 패턴이 **정확히
    하나**일 것도 함께 요구한다.
    """
    patterns = load_patterns()
    closure = lane_closure()
    assert closure

    for target in closure:
        covering = covering_patterns(patterns, target)
        assert len(covering) == 1, (
            f"{target} 을 덮는 패턴이 {len(covering)}개다({covering}). "
            "중복 선언은 한 줄을 빼도 게이트가 반응하지 않게 만든다 — 하나로 줄여라."
        )
        weakened = [p for p in patterns if p != covering[0]]
        missing = [rel for rel in closure if not is_covered(weakened, rel)]
        assert target in missing, (
            f"`{covering[0]}` 한 줄을 뺐는데 {target} 이 여전히 덮인다 — "
            "이 패턴은 검증되지 않은 것이다"
        )


def test_단일_파일_패턴을_빼면_그_파일만_지목된다() -> None:
    """음성 대조 정밀도 — 지목이 '정확히 그 파일'인지 본다."""
    patterns = load_patterns()
    target = "app/privacy/masking.py"
    assert target in lane_closure(), "폐포 구성이 바뀌었다 — 이 음성 대조의 표적을 갱신하라"

    weakened = [p for p in patterns if p != target]
    assert len(weakened) == len(patterns) - 1, f"{target} 이 목록에 한 줄로 있어야 한다"
    missing = [rel for rel in lane_closure() if not is_covered(weakened, rel)]
    assert missing == [target], f"기대: [{target}] / 실제: {missing}"


def test_목록이_자기_자신과_ci_를_대상에_넣는다() -> None:
    """규칙 6 — 판정하는 장치일수록 자기를 검사 대상에 넣는다.

    목록이나 CI 잡을 고치는 커밋에서는 레인이 실제로 돌아야, 바뀐 판정이
    돌지 않은 채 초록으로 남지 않는다.
    """
    patterns = load_patterns()
    for own in (".github/llm-lane-paths.txt", ".github/workflows/ci.yml"):
        assert is_covered(patterns, own), f"{own} 이 목록에 없다 — 게이트가 자기 변경을 안 본다"


def test_ci_가_이_목록과_판정기를_실제로_읽는다() -> None:
    """도달 0 방지(규칙 3). 아무도 안 읽는 목록은 선언만 있는 것이다."""
    assert WORKFLOW_FILE.is_file(), f"{WORKFLOW_FILE} 가 없다"
    workflow = WORKFLOW_FILE.read_text(encoding="utf-8")
    assert ".github/llm-lane-paths.txt" in workflow, (
        "CI 가 경로 목록 파일을 읽지 않는다 — 목록이 어디에도 배선돼 있지 않다"
    )
    assert "tests.test_llm_lane_scope" in workflow, (
        "CI 가 이 판정기를 호출하지 않는다 — 매칭 규칙이 CI 쪽에 복제됐을 가능성이 있다"
    )
    # 주석은 걷어낸다 — CI 는 "이것을 세우지 않는다" 를 주석으로 설명하고 있고,
    # 그 설명까지 위반으로 세면 설명을 지우는 쪽이 통과하는 압력이 생긴다.
    effective = "\n".join(
        line for line in workflow.splitlines() if not line.lstrip().startswith("#")
    )
    assert "GOLDEN_RECORD_BASELINE" not in effective, (
        "CI 가 기준선 기록 모드를 켠다 — 기록 실행은 판정이 아니므로 CI 가 할 일이 아니다"
    )


def test_기준선_파일이_있다면_의도적으로_커밋된_것이다() -> None:
    """기준선이 **흘러나온 산출물**이 아니라 사람이 커밋한 것임을 고정한다.

    이 테스트는 원래 "파일이 없다"를 단언했고, docstring 스스로 그것을 *"CI 가 기준선을
    만들지 않는다는 사실의 **대응물**"* 이라 적었다 — **대리 지표**다. 2026-08-13 에
    첫 정식 기준선(33/56·미측정 0)을 **의도적으로** 커밋하자 그 대리 지표가 거짓이
    됐다. 진짜 불변식이 깨진 게 아니라 대리가 어긋난 것이다.

    진짜 불변식은 위 `test_ci_가_이_목록과_판정기를_실제로_읽는다` 가 **직접** 단언한다
    (`GOLDEN_RECORD_BASELINE` 이 워크플로 어디에도 없다 — 주석은 걷어내고 본다).
    이 저장소는 대리 지표로 다친 적이 있다 — "지적 건수"를 "변경 여부"의 대리로 써서
    원장을 새로 만들고도 성공 코드로 끝났다(`kotlin-migration` 스킬 근거 4번).

    그래서 부재 단언을 버리고 **남아 있던 탐지력만** 가져왔다: 로컬 기록 실행이 만든
    파일이 **미추적으로 굴러다니는** 상태를 잡는다. 그것은 리뷰를 거치지 않은 하한선이
    조용히 판정에 쓰이는 경로다.
    """
    path = ROOT / "tests" / "golden" / "baseline.json"
    if not path.exists():
        return  # 부재는 정상이다 — 첫 기록 전이거나 오염본을 내린 뒤다
    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "tests/golden/baseline.json"],
        cwd=ROOT,
        capture_output=True,
        check=False,
    )
    assert tracked.returncode == 0, (
        "tests/golden/baseline.json 이 있는데 git 이 추적하지 않는다 — "
        "기록 실행이 만든 파일이 미추적으로 남아 있다. 리뷰를 거치지 않은 하한선이 "
        "다음 판정의 기준이 된다. 의도한 기록이면 커밋하고, 아니면 지워라"
    )


def test_glob_번역이_경계를_지킨다() -> None:
    """`**` 가 세그먼트를 넘고 `*` 는 안 넘는 것, 접두사 오탐이 없는 것."""
    assert is_covered(["tests/golden/**"], "tests/golden/conftest.py")
    assert is_covered(["tests/golden/**"], "tests/golden/documents/001-기초연금-신청.json")
    assert not is_covered(["tests/golden/**"], "tests/goldenx/a.py")
    assert not is_covered(["tests/golden/**"], "tests/golden")
    assert is_covered(["app/llm/*.py"], "app/llm/factory.py")
    assert not is_covered(["app/llm/*.py"], "app/llm/sub/factory.py")
    assert is_covered(["app/config.py"], "app/config.py")
    assert not is_covered(["app/config.py"], "app/config.pyi")
    assert not is_covered(["app/config.py"], "backend/app/config.py")
    # 점으로 시작하는 경로 — `lstrip("./")` 로 짰다가 앞 점이 먹혔던 자리다.
    assert is_covered([".github/workflows/ci.yml"], ".github/workflows/ci.yml")
    assert is_covered([".github/workflows/ci.yml"], "./.github/workflows/ci.yml")
    assert not is_covered([".github/workflows/ci.yml"], "github/workflows/ci.yml")


# --------------------------------------------------------------------------
# CLI — CI 의 판정 스텝이 이 진입점을 쓴다 (매칭 규칙을 CI 쪽에 복제하지 않는다)
# --------------------------------------------------------------------------


_USAGE = "사용법: python3 -m tests.test_llm_lane_scope --self-check | --match <변경파일목록>"


def _self_check() -> int:
    """완전성 검사를 pytest 없이 돌린다. 실패하면 사유를 찍고 2를 낸다."""
    patterns = load_patterns()
    closure = lane_closure()
    missing = [rel for rel in closure if not is_covered(patterns, rel)]
    print(f"선언 패턴 {len(patterns)}개 / 레인 도달 파일 {len(closure)}개")
    if missing:
        for rel in missing:
            print(f"::error file={rel}::`-m llm` 레인이 import 하는데 경로 목록이 덮지 않는다")
        return 2
    print("완전성 OK — 레인 도달 파일이 전부 목록에 덮인다")
    return 0


def _match(listing: Path) -> int:
    """변경 파일 목록을 읽어 목록과 겹치는 경로만 표준출력에 찍는다."""
    patterns = load_patterns()
    changed = [
        line.strip() for line in listing.read_text(encoding="utf-8").splitlines() if line.strip()
    ]
    for path in changed:
        if is_covered(patterns, path):
            print(path)
    return 0


def main(argv: Sequence[str]) -> int:
    if len(argv) == 1 and argv[0] == "--self-check":
        return _self_check()
    if len(argv) == 2 and argv[0] == "--match":
        return _match(Path(argv[1]))
    print(_USAGE, file=sys.stderr)
    return 64


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except LaneScopeError as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(2)
