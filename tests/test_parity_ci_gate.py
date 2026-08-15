"""parity CI 게이트의 **범위 가드**를 상시 회귀로 고정한다 (Z-7).

## 왜 이 파일이 있는가

`.github/workflows/ci.yml` 의 parity 비교 step 은 "무엇을 검증하고 있는가"의 범위를 지키는
가드를 여럿 들고 있다 — 정본 하한, 선언 하한, 선언 0개 실패. 그 가드들이 실제로 무는지는
**한 번 손으로 확인하고 끝**이었다(C-02 집행 시 7종 음성 대조). 손으로 한 확인은 다음 사람이
가드를 지울 때 아무 말도 하지 않는다. 이 파일이 그 자리를 받는다.

## 무엇을 재는가 — 가드까지다

이 테스트는 step 의 **가드 구간**만 판정한다. 가드를 통과했는지는 "판정 범위" 배너가 찍혔는지로
보고, 그 뒤의 실제 비교(비교기 실행·종료 코드 0/3 해석)는 보지 않는다. 비교 구간은
`parity/actual/` 을 요구하는데 그것은 `.gitignore` 대상이라 Gradle 을 돌리기 전에는 없고,
있는지 여부에 따라 결과가 달라지는 테스트는 회귀로 쓸 수 없다.

## 대리 경로로 남는 것 (없앨 수 없다 — 명시한다)

1. **추출본을 돌린다.** GitHub Actions 가 돌리는 것은 워크플로 자체이고, 이 테스트가 돌리는
   것은 그 step 의 `run:` 블록을 뽑아낸 사본이다. `ci.yml` 을 **매 실행 다시 읽어** 뽑으므로
   블록 내용의 드리프트는 잡히지만, 워크플로 수준의 배선(step 이 어느 job 에 있는지, `if:`
   조건이 붙었는지, 그 job 이 실제로 도는지)은 이 테스트가 보지 못한다.
2. **러너 환경이 다르다.** ubuntu 러너의 bash·`uv` 설치 상태를 로컬에서 재현하지 않는다.
3. **`run:` 블록을 찾는 방식이 휴리스틱이다.** `declared_count=` 가 들어 있는 step 을 찾는다.
   그 문자열이 사라지면 이 테스트는 **건너뛰지 않고 실패**한다 — 조용히 통과하지 않게 하려고
   일부러 그렇게 두었다.

1·2 는 이 저장소 안에서 닫을 수 없다. 최종 방어선은 첫 push 의 실제 러너 실행이다.
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
WORKFLOW = REPO / ".github/workflows/ci.yml"
DECLARATION = "backend-kotlin/parity-domains.txt"
DECLARED_FLOOR = ".github/parity-declared-floor.txt"

#: 게이트 step 이 읽는 것만 복사한다. 저장소 전체 복사는 느리고 `build/` 가 크다.
COPIED = (
    ".github/parity-canonical-floor.txt",
    DECLARED_FLOOR,
    DECLARATION,
    ".claude/skills/python-kotlin-parity/scripts",
    "contracts",
    "pyproject.toml",
    "uv.lock",
)

#: 가드를 전부 통과했을 때만 찍히는 줄. "여기까지 왔다"의 표지로 쓴다.
BANNER = "parity 게이트 판정 범위"


def _step_script() -> str:
    """`ci.yml` 에서 parity 비교 step 의 `run:` 블록을 뽑는다.

    매 실행 다시 읽는다 — 사본을 저장소에 두면 그 사본이 드리프트해서, 고쳐진 적 없는
    게이트를 검증하는 테스트가 된다.
    """
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    anchors = [i for i, line in enumerate(lines) if "declared_count=" in line]
    assert anchors, (
        f"{WORKFLOW} 에서 parity 게이트 step 을 찾지 못했다 (`declared_count=` 없음). "
        "step 이 사라졌거나 변수명이 바뀌었다 — 어느 쪽이든 범위 가드가 그대로인지 "
        "사람이 확인해야 하므로 건너뛰지 않고 실패시킨다."
    )
    starts = [i for i in range(anchors[0]) if lines[i].strip().startswith("- name:")]
    run_index = next(
        i for i in range(starts[-1], len(lines)) if lines[i].strip() in ("run: |", "run: |-")
    )
    indent = len(lines[run_index]) - len(lines[run_index].lstrip())
    body: list[str] = []
    for line in lines[run_index + 1 :]:
        if line.strip() and (len(line) - len(line.lstrip())) <= indent:
            break
        body.append(line[indent + 2 :] if len(line) > indent + 2 else "")
    return "\n".join(body)


def _tree(tmp_path: Path) -> Path:
    """게이트가 읽는 파일만 담은 합성 저장소."""
    root = tmp_path / "tree"
    root.mkdir()
    for relative in COPIED:
        source = REPO / relative
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            shutil.copytree(source, target, ignore=shutil.ignore_patterns("__pycache__"))
        else:
            shutil.copyfile(source, target)
    return root


def _run(root: Path, script: str) -> subprocess.CompletedProcess[str]:
    environment = dict(os.environ)
    # 합성 트리에서 uv 가 새 가상환경을 만들지 않도록 저장소 것을 그대로 쓴다.
    environment.setdefault("UV_PROJECT_ENVIRONMENT", str(REPO / ".venv"))
    return subprocess.run(
        ["bash", "-c", script],
        cwd=root,
        capture_output=True,
        text=True,
        env=environment,
        timeout=300,
    )


def _write_lines(path: Path, names: list[str]) -> None:
    path.write_text("".join(f"{name}\n" for name in names), encoding="utf-8")


@pytest.fixture(scope="module")
def script() -> str:
    return _step_script()


def test_가드가_통과하면_판정_범위까지_간다(tmp_path: Path, script: str) -> None:
    """대조군. 이것이 없으면 아래 실패 단언들이 **무엇 때문에** 실패했는지 알 수 없다.

    가드 뒤 구간(실제 비교)은 `parity/actual/` 이 없어 실패할 수 있으므로 종료 코드를 보지
    않는다. 본 것은 "가드를 전부 지나 판정 범위를 찍었다"까지다.
    """
    root = _tree(tmp_path)

    result = _run(root, script)

    assert BANNER in result.stdout, (
        "선언과 하한이 정합한데도 가드에서 막혔다 — 아래 실패 케이스들이 "
        f"가드가 아니라 다른 이유로 실패하고 있을 수 있다.\n{result.stdout}\n{result.stderr}"
    )


def test_선언이_0개면_실패한다(tmp_path: Path, script: str) -> None:
    """Phase 1 에는 '아직 시작 안 했다'가 사실이라 경고 + 통과였다. 지금 0개가 되는 경로는
    '누군가 지웠다' 하나뿐이라 실패로 승격했다."""
    root = _tree(tmp_path)
    (root / DECLARATION).write_text("# 전부 지웠다\n", encoding="utf-8")

    result = _run(root, script)

    assert result.returncode != 0
    assert "포팅을 끝냈다고 선언한 도메인이 0개다" in result.stdout


def test_선언이_줄면_실패한다(tmp_path: Path, script: str) -> None:
    """0개까지 가지 않아도 막아야 한다 — 둘을 하나로 줄이면 남은 하나가 통과해 초록이 된다."""
    root = _tree(tmp_path)
    _write_lines(root / DECLARATION, ["masking"])

    result = _run(root, script)

    assert result.returncode != 0
    assert "구현 완료로 선언돼 있던 도메인이 선언에서 사라졌다" in result.stdout
    assert "repair-adoption" in result.stdout


def test_선언과_생산자를_함께_지워도_실패한다(tmp_path: Path, script: str) -> None:
    """C-02 가 요구한 음성 테스트. Gradle `parityManifestCheck` 는 선언과 산출물의 **불일치**만
    보므로 **둘을 같이** 지우면 거기서는 걸리지 않는다. 그 경로를 이 하한이 받는다."""
    root = _tree(tmp_path)
    _write_lines(root / DECLARATION, ["masking"])
    shutil.rmtree(root / "parity/actual/repair-adoption", ignore_errors=True)

    result = _run(root, script)

    assert result.returncode != 0
    assert "구현 완료로 선언돼 있던 도메인이 선언에서 사라졌다" in result.stdout


def test_선언_하한_파일이_없으면_실패한다(tmp_path: Path, script: str) -> None:
    root = _tree(tmp_path)
    (root / DECLARED_FLOOR).unlink()

    result = _run(root, script)

    assert result.returncode != 0
    assert "구현 완료 도메인이 줄어드는 것을 감지할 기준점이 사라졌다" in result.stdout


def test_선언_하한이_비면_실패한다(tmp_path: Path, script: str) -> None:
    """파일을 지우는 것과 비우는 것은 같은 일이다 — 하한이 비면 무엇을 지우든 통과한다."""
    root = _tree(tmp_path)
    (root / DECLARED_FLOOR).write_text("# 주석만 남겼다\n", encoding="utf-8")

    result = _run(root, script)

    assert result.returncode != 0
    assert "도메인이 한 줄도 없다" in result.stdout


def test_하한까지_함께_줄이면_가드를_통과한다(tmp_path: Path, script: str) -> None:
    """설계된 탈출구다. 정당한 삭제는 가능해야 하고, 다만 `.github/` 안의 파일을 고쳐야 하므로
    **조용할 수 없다** — 그 diff 가 리뷰에 올라가는 것이 이 하한의 값어치다.

    이 케이스가 없으면 하한은 "삭제를 불가능하게 만드는 장치"로 오해되고, 다음 사람이 정당한
    삭제를 하려다 하한 자체를 지우게 된다.
    """
    root = _tree(tmp_path)
    _write_lines(root / DECLARATION, ["masking"])
    _write_lines(root / DECLARED_FLOOR, ["masking"])

    result = _run(root, script)

    assert BANNER in result.stdout
    assert "선언에서 사라졌다" not in result.stdout


# ---------------------------------------------------------------------------
# 전체 게이트 하한 (게이트 13 X-1)
#
# 위 테스트들이 CI **셸**을 보는 것과 달리 아래는 **비교기 함수**를 직접 부른다. X-1 이
# 정확히 그 경계에서 났기 때문이다 — 셸은 8/8 일 때 `--only-domain` 을 빼고 부르고, 비교기는
# 그 빈 목록을 보고 하한을 통째로 건너뛰었다. 두 쪽 다 자기 몫은 했는데 이음매가 비어 있었다.
#
# 그래서 여기서는 fixture·산출물을 세우지 않는다. 세우면 느려지고, 무엇보다 **판정 범위와
# 표시 파일**이라는 이 하한의 두 입력만 보면 되는데 그 밖의 것이 실패에 섞인다.
# ---------------------------------------------------------------------------

import importlib.util  # noqa: E402
import sys  # noqa: E402
from types import ModuleType  # noqa: E402

_COMPARE_PATH = REPO / ".claude/skills/python-kotlin-parity/scripts/compare_parity.py"


def _load_comparer() -> ModuleType:
    """비교기를 모듈로 적재한다. 경로가 바뀌면 **건너뛰지 않고 실패**한다."""
    assert _COMPARE_PATH.exists(), (
        f"{_COMPARE_PATH} 가 없다 — 비교기가 옮겨졌거나 사라졌다. 어느 쪽이든 이 하한이 "
        "무엇을 지키는지 사람이 다시 확인해야 하므로 조용히 건너뛰지 않는다."
    )
    name = "compare_parity_under_test"
    spec = importlib.util.spec_from_file_location(name, _COMPARE_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    # `dataclass` 는 정의 시점에 `sys.modules[cls.__module__]` 를 되찾는다. 등록 전에
    # 실행하면 그 조회가 `None` 을 얻어 적재가 죽는다 — 실제로 그렇게 죽었다.
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def comparer() -> ModuleType:
    return _load_comparer()


@pytest.fixture
def marker(comparer: ModuleType, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """표시 파일과 **선언 하한**을 tmp 로 돌린다 — 저장소의 실물을 건드리지 않는다.

    선언 하한 격리를 빠뜨렸다가 강등 테스트가 **저장소의 실물 파일을 덮어썼다.** 테스트가
    제품 파일을 쓰는 것은 그 자체로 결함이라 둘 다 여기서 묶어 돌린다 — 하나만 격리하면
    다음 사람이 나머지를 그대로 쓴다.

    기본값은 **정본 전부**(= 도달 상태)이고, 좁혀야 하는 테스트만 이 파일을 다시 쓴다.
    """
    path = tmp_path / "parity-full-gate.txt"
    monkeypatch.setattr(comparer, "FULL_GATE_PATH", path)
    floor = tmp_path / "parity-declared-floor.txt"
    floor.write_text("".join(f"{name}\n" for name in sorted(comparer.BUILDERS)), encoding="utf-8")
    monkeypatch.setattr(comparer, "DECLARED_FLOOR_PATH", floor)
    return path


def _canonical(comparer: ModuleType) -> set[str]:
    return set(comparer.BUILDERS)


def test_전체_경로에서도_하한이_돈다(comparer: ModuleType, marker: Path) -> None:
    """X-1 본체. `--only-domain` 없이 도는 CI 의 8/8 경로에서 표시를 지우면 막아야 한다.

    옛 서명은 `selected`(`--only-domain` 목록)를 받아 비어 있으면 즉시 반환했다. CI 는
    선언이 정본을 덮으면 그 인자를 **주지 않으므로**, 하한이 서야 할 바로 그 경로에서
    통째로 잠들어 있었다. 표시 파일을 지워도 조용했다.
    """
    assert not marker.exists()

    problems = comparer.full_gate_floor_problems(scoped=False)

    assert problems, "전체 경로에서 표시가 없는데 아무 말도 하지 않았다 — X-1 재발이다"
    assert "하한을 고정하라" in problems[0]


def test_선언_경로에서_표시가_없으면_막는다(comparer: ModuleType, marker: Path) -> None:
    """같은 상태를 `--only-domain` 으로 선언해 도는 경로에서도 막는다."""
    problems = comparer.full_gate_floor_problems(scoped=False)

    assert problems
    assert "하한을 고정하라" in problems[0]


def test_표시가_있어도_내용이_없으면_막는다(comparer: ModuleType, marker: Path) -> None:
    """`exists()` 만 보던 시절에는 `touch` 한 빈 파일이 하한을 세웠다.

    선언 파일이 실물과 대조되는지 보는 것이 이 저장소의 규율인데(케이스 하한의 무의미 선언
    검사가 같은 자리다) 이 파일만 예외였다.
    """
    marker.write_text("# 주석만 있다\n", encoding="utf-8")

    problems = comparer.full_gate_floor_problems(scoped=False)

    assert problems
    assert "표시가 비어 있다" in problems[0]


def test_표시의_도메인_수가_정본과_다르면_막는다(comparer: ModuleType, marker: Path) -> None:
    """`domains` 는 도달 **시점의 정본 크기**다. 지금과 다르면 표시가 낡은 것이다."""
    marker.write_text("reached: 2026-08-15\ndomains: 3\n", encoding="utf-8")

    problems = comparer.full_gate_floor_problems(scoped=False)

    assert problems
    assert "표시가 낡았다" in problems[0]


def test_고정한_뒤_범위가_줄면_막는다(comparer: ModuleType, marker: Path) -> None:
    """강등. 정본이 늘었든 선언이 줄었든 결과는 같다 — 부분 게이트가 되어 종료 코드 3이
    통과로 읽힌다."""
    canonical = _canonical(comparer)
    marker.write_text(f"reached: 2026-08-15\ndomains: {len(canonical)}\n", encoding="utf-8")
    # 선언 하한에서 한 도메인을 뺀다 = 검증 범위가 내려갔다.
    comparer.DECLARED_FLOOR_PATH.write_text(
        "".join(f"{d}\n" for d in sorted(canonical)[:-1]), encoding="utf-8"
    )

    problems = comparer.full_gate_floor_problems(scoped=False)

    assert problems
    assert "내려왔다" in problems[0]


def test_정상_상태는_통과한다(comparer: ModuleType, marker: Path) -> None:
    """대조군. 이것이 없으면 위 다섯이 **무엇 때문에** 실패했는지 알 수 없다."""
    canonical = _canonical(comparer)
    marker.write_text(f"reached: 2026-08-15\ndomains: {len(canonical)}\n", encoding="utf-8")

    assert comparer.full_gate_floor_problems(scoped=False) == []


def test_케이스를_골라_돌린_실행에서는_보지_않는다(comparer: ModuleType, marker: Path) -> None:
    """`--only` 는 범위 선언이 아니다. 켜 두면 단일 케이스 재현이 매번 빨개진다."""
    assert not marker.exists()

    assert comparer.full_gate_floor_problems(scoped=True) == []
