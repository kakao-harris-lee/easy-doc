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

import json
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
from collections.abc import Callable  # noqa: E402
from types import ModuleType  # noqa: E402
from typing import Any  # noqa: E402

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

    ## 「선언 경로」 짝 테스트를 지웠다 (게이트 14 F-8)

    옆에 `test_선언_경로에서_표시가_없으면_막는다` 가 있었고, 이름은 `--only-domain` 으로
    도는 경로를 따로 잰다고 말했다. 그런데 **호출도 단언도 이 테스트와 같았다** — 서명에서
    `selected` 가 빠지면서 두 경로의 구분이 사라졌기 때문이다(그 제거가 곧 X-1 수정이다).
    남겨 두면 「음성 7종」이라는 수가 실제로 재는 축의 **대리 지표**가 된다. 다시 넣지 마라 —
    두 경로를 정말로 가르려면 `scoped` 인자를 달리 주어야 하고, 그 축은 아래
    `--only` 관련 테스트가 이미 덮는다.
    """
    assert not marker.exists()

    problems = comparer.full_gate_floor_problems(scoped=False)

    assert problems, "전체 경로에서 표시가 없는데 아무 말도 하지 않았다 — X-1 재발이다"
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


# ---------------------------------------------------------------------------
# 게이트 우회 시나리오 (codex #2 / X-3 별건 — Phase 3 착수 전 마감)
#
# 하네스를 세울 때 손으로 여섯 가지 우회를 뚫어 보고 "막힌다"를 확인했다. 그 확인은
# **일회성**이라 가드가 다시 무력해져도 아무 데서도 빨개지지 않았다. 여기서 상시화한다.
#
# 여섯 중 하나(역방향 패딩)는 **대상 자체가 없어졌다** — 역방향 외부 검증(Kotlin 이 만든
# 토큰을 Python 검증기가 읽는 경로)은 2026-08-12 재개발 전환으로 crypto·jwt·argon2 도메인과
# 함께 제거됐다. 없는 가드에 테스트를 붙이면 "검사하는 척"이 되므로 만들지 않고 여기 적는다.
#
# 시나리오 → 가드 대응표는 산출물 문서에 있다.
# ---------------------------------------------------------------------------


def _fixture_document(comparer: ModuleType, domain: str) -> dict[str, Any]:
    path = REPO / f"parity/fixtures/{domain}/{domain}.json"
    assert path.exists(), f"{path} 가 없다 — fixture 가 옮겨졌는지 확인하라"
    document = json.loads(path.read_text(encoding="utf-8"))
    # `json.loads` 는 `Any` 를 준다. 객체가 아니면 아래 케이스 조작이 조용히 엉뚱한 것을
    # 만지므로 여기서 형태를 확인하고 넘긴다.
    assert isinstance(document, dict), f"{path} 가 JSON 객체가 아니다"
    return document


def test_runtime이_kotlin이_아니면_막는다(comparer: ModuleType) -> None:
    """`runtime` 위조. 이 필드는 손으로 적을 수 있어 **단독 방어선이 아니지만**, 예전 비교기가
    그것을 **한 번도 읽지 않아** `not-kotlin` 결과가 그대로 통과했다(X-11)."""
    problem = comparer.runtime_problem({"runtime": "python", "cases": []}, Path("x.json"))

    assert problem is not None
    assert "kotlin" in problem


def test_runtime_선언이_없으면_막는다(comparer: ModuleType) -> None:
    """손으로 쓴 산출물의 가장 흔한 형태 — 케이스만 적고 런타임 선언을 빠뜨린 파일."""
    problem = comparer.runtime_problem({"cases": []}, Path("x.json"))

    assert problem is not None
    assert "미선언" in problem


def test_정상_산출물은_runtime에서_막히지_않는다(comparer: ModuleType) -> None:
    """대조군. 없으면 위 둘이 **무엇 때문에** 걸렸는지 알 수 없다."""
    assert comparer.runtime_problem({"runtime": "kotlin", "cases": []}, Path("x.json")) is None


def test_축소된_fixture는_정본_대조에서_막힌다(comparer: ModuleType) -> None:
    """케이스를 덜어 낸 fixture. 정본 생성기를 다시 돌려 대조하므로 손편집이 드러난다.

    이것이 이 하네스의 **가장 값싼 우회**였다 — 빨간 케이스를 지우면 초록이 된다.
    """
    domain = "text"
    document = _fixture_document(comparer, domain)
    document["cases"] = document["cases"][:2]
    pair = comparer.Pair(
        fixture_path=REPO / f"parity/fixtures/{domain}/{domain}.json",
        actual_path=REPO / f"parity/actual/{domain}/{domain}.json",
        domain=domain,
        fixture=document,
        mode=comparer.MODE_SPEC,
        spec_status="ready",
    )

    problems = comparer.provenance_problems(pair)

    assert problems, "케이스를 덜어 냈는데 정본 대조가 조용하다"
    assert any("정본" in problem for problem in problems)


def test_손대지_않은_fixture는_정본_대조를_통과한다(comparer: ModuleType) -> None:
    """대조군. 정본 대조가 **아무 fixture나** 빨갛게 만드는 검사가 아님을 고정한다."""
    domain = "text"
    pair = comparer.Pair(
        fixture_path=REPO / f"parity/fixtures/{domain}/{domain}.json",
        actual_path=REPO / f"parity/actual/{domain}/{domain}.json",
        domain=domain,
        fixture=_fixture_document(comparer, domain),
        mode=comparer.MODE_SPEC,
        spec_status="ready",
    )

    assert comparer.provenance_problems(pair) == []


# ---------------------------------------------------------------------------
# 게이트 우회 회귀 — **본류 배선** (게이트 15 X2 / codex C15-6 → 게이트 16 Q / T-D⑵)
#
# 위 헬퍼 직접 호출 테스트들은 helper **내부의 탐지력**만 잰다. helper 가 본류에
# 배선돼 있는지는 재지 않았다 — `runtime_problem` 의 본류 호출부는 `compare_file` 에,
# `provenance_problems` 의 본류 호출부는 `main` 에 **각각 정확히 한 곳씩**이고, 그
# 한 줄을 지우면 비교기가 위조 runtime·축소 fixture 를 다시 승인하는데 헬퍼 테스트
# 여섯은 전부 초록이었다(게이트 15 교차 §2 전제 ④ 실측).
#
# 그래서 아래는 `main()` 을 그대로 실행한다 — CI 셸이 부르는 바로 그 경로다.
# tmp_path 합성 트리에 위조/축소 산출물을 두고, 본류가 낸 **리포트 문구**로 단언한다.
# 종료 코드만 보면 안 된다 — 합성 트리는 도메인 하나뿐이라 도메인 누락만으로도 1이
# 나온다(아래 대조군이 그 사실을 고정한다). 문구는 각 helper 만 낼 수 있으므로,
# 호출부를 지우면 문구가 사라져 이 테스트가 빨개진다.
#
# ## 종류로 닫는다 (게이트 16 리더 판정 — T-D⑵)
#
# 게이트 15 는 두 helper(runtime·provenance)에만 이것을 걸었다. 그런데 "본류 호출부가
# 한 곳뿐인 게이트 helper" 는 **종류로 댈 수 있는 자리**이고 여덟이며, 그중 하나
# (`full_gate_floor_problems`, `main` 의 `floor_problems += ...` 한 줄)는 게이트 14 가
# **지워도 25 passed / 1191 passed** 를 변이로 실측한 실사 결함이었다(R-4⑴, Y-7). 규칙 4 —
# 판정 기준은 횟수가 아니라 결함의 구조 — 에 따라 여덟 자리 전부에 건다. 아래
# `_MAINLINE_HELPERS` 가 그 여덟의 목록이고, 끝의 완전성 테스트가 이 목록을 비교기 코드와
# 대조한다 — 아홉째 helper 가 생기면 회귀 없이는 통과하지 못하게.
#
# ## Z-b — 자기 눈을 가리지 않는다 (게이트 14 cross §R.9)
#
# 처음 판의 `_mainline_tree` 는 전체 게이트 하한(표시 파일·선언 하한)을 **항상 통과하는
# 값**으로 격리했다. 격리 자체는 옳지만(실물 오염 방지) 그 결과 본류 배선 테스트를 아무리
# 늘려도 `full_gate_floor_problems` 호출선만은 영구히 보이지 않았다. 그래서 격리는
# 유지하되 **상태를 인자로 받는다** — 기본은 통과 상태(다른 helper 를 잴 때 겹치지 않도록),
# 그 helper 를 잴 때만 표시를 없애거나 선언을 줄인다.
# ---------------------------------------------------------------------------


def _mainline_tree(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    mutate: Any = None,
    actual: dict[str, Any] | None = None,
    actual_from_reference: bool = False,
    case_floor: str = "present",
    full_gate_mark: str = "valid",
    declared_floor: str = "full",
    ledger: dict[str, Any] | None = None,
) -> Path:
    """본류 실행용 합성 트리. 하한·표시 파일 3종과 원장을 함께 격리한다.

    격리 이유는 `marker` fixture 와 같다 — 실물을 건드리지 않기 위해서이자, 실물
    하한의 상태 변화가 이 테스트의 판정에 섞이지 않게 하기 위해서다. 케이스 하한은
    **트리에 실제로 있는 케이스**로 쓴다. 축소 시나리오에서 실물 하한을 그대로 쓰면
    케이스 하한 검사도 함께 빨개져서, 본류 정본 대조(`:2282`)를 지워도 종료 코드가
    빨간 채 남는다 — 그 겹침을 여기서 미리 걷어낸다.

    격리 파일의 **상태**는 인자다(Z-b). 기본값은 전부 "통과" 상태라 다른 helper 를 잴 때
    이 파일들이 실패 사유에 섞이지 않고, 그 파일을 읽는 helper 를 잴 때만 값을 바꾼다.

    - `case_floor`: `present`(트리 케이스 전부) / `partial`(마지막 케이스 미등재) / `absent`
    - `full_gate_mark`: `valid`(reached+domains) / `absent`
    - `declared_floor`: `full`(BUILDERS 전부) / `short`(하나 부족)
    - `actual_from_reference`: fixture 의 `reference` 값을 그대로 Kotlin 산출물로 쓴다 —
      성질을 지키고 원장이 `agree` 가 되는 **정상 산출물**이다. `parity/actual/` 실물을
      읽지 않는다(그것은 .gitignore 대상이라 있는지 여부로 결과가 갈리면 회귀로 못 쓴다).
    - `ledger`: 도메인 원장 파일 내용(없으면 원장 없음).
    """
    root = tmp_path / "mainline"
    fixture_dir = root / "fixtures/text"
    fixture_dir.mkdir(parents=True)
    document = _fixture_document(comparer, "text")
    original_ids = [str(case["id"]) for case in document["cases"]]
    if mutate is not None:
        mutate(document)
    (fixture_dir / "text.json").write_text(
        json.dumps(document, ensure_ascii=False), encoding="utf-8"
    )
    actual_dir = root / "actual/text"
    actual_dir.mkdir(parents=True)
    if actual_from_reference:
        actual = {
            "runtime": "kotlin",
            "cases": [
                {"id": case["id"], "actual": case["reference"]} for case in document["cases"]
            ],
        }
    if actual is not None:
        (actual_dir / "text.json").write_text(
            json.dumps(actual, ensure_ascii=False), encoding="utf-8"
        )
    case_floor_path = tmp_path / "parity-case-floor.txt"
    if case_floor == "present":
        _write_lines(case_floor_path, [f"text/{cid}" for cid in original_ids])
    elif case_floor == "partial":
        _write_lines(case_floor_path, [f"text/{cid}" for cid in original_ids[:-1]])
    else:
        assert case_floor == "absent", case_floor
    monkeypatch.setattr(comparer, "CASE_FLOOR_PATH", case_floor_path)
    declared = tmp_path / "parity-declared-floor.txt"
    domains = sorted(comparer.BUILDERS)
    _write_lines(declared, domains if declared_floor == "full" else domains[:-1])
    monkeypatch.setattr(comparer, "DECLARED_FLOOR_PATH", declared)
    mark = tmp_path / "parity-full-gate.txt"
    if full_gate_mark == "valid":
        mark.write_text(f"reached: 2026-08-15\ndomains: {len(domains)}\n", encoding="utf-8")
    else:
        assert full_gate_mark == "absent", full_gate_mark
    monkeypatch.setattr(comparer, "FULL_GATE_PATH", mark)
    ledger_dir = root / "ledger"
    ledger_dir.mkdir()
    if ledger is not None:
        (ledger_dir / "text.json").write_text(
            json.dumps(ledger, ensure_ascii=False), encoding="utf-8"
        )
    return root


def _run_mainline(
    comparer: ModuleType,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    root: Path,
    *extra_args: str,
) -> tuple[int, str]:
    """`main()` 을 CI 와 같은 인자 형태(--fixture/--actual 루트)로 실행한다."""
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "compare_parity.py",
            "--fixture",
            str(root / "fixtures"),
            "--actual",
            str(root / "actual"),
            "--ledger",
            str(root / "ledger"),
            *extra_args,
        ],
    )
    code = comparer.main()
    captured = capsys.readouterr()
    return code, captured.out + captured.err


#: 본류 호출부가 **한 곳뿐**인 게이트 helper 와, 각각을 본류에서 재는 테스트 이름.
#: 이 표가 "종류"의 열거다 — 끝의 완전성 테스트가 비교기 코드에서 `*_problem(s)`·
#: `*_additions` 이름을 수집해 이 표와 대조하므로, 새 helper 는 여기 한 줄 + 본류 테스트
#: 하나 없이는 통과하지 못한다. 값은 그 helper **만** 낼 수 있는 리포트 문구다.
#:
#: 게이트 16 §6.1 표는 8자리였다. 아래 완전성 테스트를 처음 돌리자 **아홉째**가 바로
#: 나왔다 — `structural_problems`(`main` 의 `problems = structural_problems(...)`, 유일
#: 호출부). 게이트 17 이 그 완전성 테스트의 면제 목록(`nested`)을 은폐형으로 지적해
#: 탐지형(AST 호출부 대조)으로 갈아타자 **열째**가 나왔다 — `reference_problems` 는 내가
#: "다른 helper 안에서만 불린다"고 면제했지만 실제 호출부는 `compare_file`(본류 함수)이고,
#: 그것은 `runtime_problem` 과 구조가 같다. 면제 목록이 가린 것을 탐지가 드러냈다.
#: 표는 10자리이고, 그 수를 아래 `EXPECTED_MAINLINE_HELPERS` 가 **정확 일치**로 고정한다.
_MAINLINE_HELPERS: dict[str, tuple[str, str]] = {
    "runtime_problem": (
        "test_본류가_위조_runtime을_막는다",
        "Kotlin이 낸 결과가 아니면 비교 대상이 아니다",
    ),
    "provenance_problems": ("test_본류가_축소_fixture를_막는다", "정본에서 사라졌다"),
    "structural_problems": ("test_본류가_빈_fixture를_막는다", "빈 fixture"),
    "reference_problems": ("test_본류가_낡은_원장_기록을_막는다", "원장이 낡았다"),
    "spec_shape_problems": ("test_본류가_단언_없는_케이스를_막는다", "단언 없는 spec 케이스"),
    "case_floor_problems": ("test_본류가_케이스_하한_부재를_막는다", "케이스 하한 파일 없음"),
    "full_gate_floor_problems": ("test_본류가_전체_게이트_미고정을_막는다", "하한을 고정하라"),
    "stale_ledger_problems": ("test_본류가_낡은_원장_항목을_막는다", "낡은 원장 항목"),
    "ledger_write_problems": ("test_본류가_원장_기록_변경을_알린다", "**원장을 "),
    "case_floor_additions": ("test_본류가_하한_미등재_케이스를_알린다", "케이스 하한 미등재"),
}

#: 표의 행 수 — **하한이 아니라 정확 일치다** (게이트 17 T17-4 · `test_harness_scope_reach.py`
#: `EXPECTED_ROW_COUNT` 전례). 완전성 테스트가 검사 대상 0건(빈 표)에서 통과하는 것을 막는다:
#: 비교기 적재 실패·대량 개명으로 발견 집합이 비면 `missing`·`stale` 이 둘 다 공집합이라
#: 아래 대조는 전부 참이 되는데, 이 수는 그때 홀로 빨개진다. 열한째 helper 가 생기면 이
#: 수를 올려야 통과한다 — 그것이 의도다(표를 고치는 diff 가 리뷰에 올라간다).
EXPECTED_MAINLINE_HELPERS = 10

#: 비교기의 **본류 함수** — CI 셸이 부르는 `main` 과 그것이 파일마다 부르는 `compare_file`.
#: "본류 호출부"의 정의는 이 두 함수 또는 표의 helper 안에서 불리는 것이다.
_MAINLINE_ROOTS: tuple[str, ...] = ("main", "compare_file")

#: 본류에서 helper 를 통해서만 나오는 문구 — 대조군은 이 중 어느 것도 내면 안 된다.
_MAINLINE_PHRASES: tuple[str, ...] = tuple(
    phrase for _test, phrase in _MAINLINE_HELPERS.values()
) + (
    "runtime 미선언",
    "runtime 이 `kotlin` 이 아니다",
    "원장을 새로 만든다",
    "위조 의심",
    "내려왔다",
)

#: 위 문구 목록의 길이 — 표 파생 10 + 하드코딩 5. **정확 일치**(게이트 18 X1). 이 상수는
#: 소비처가 대조군 두 곳뿐이고 어떤 단언도 붙어 있지 않아, `= ()` 로 직접 편집하면 대조군이
#: 아무것도 부정하지 않은 채 36/37 passed 였다(codex 경로 — 표가 온전한 채 파생 상수만 빈다).
#: `EXPECTED_MAINLINE_HELPERS` 와 같은 방식이다: 편집하면 통과하지만 그 편집이 diff 로 드러난다.
EXPECTED_MAINLINE_PHRASES = EXPECTED_MAINLINE_HELPERS + 5


def test_본류가_위조_runtime을_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`runtime: python` 결과 파일을 **본류가** 잡는지. helper 가 아니라 배선을 잰다."""
    root = _mainline_tree(
        comparer, tmp_path, monkeypatch, actual={"runtime": "python", "cases": []}
    )

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "runtime 이 `kotlin` 이 아니다" in output, output
    # 표(`_MAINLINE_HELPERS`)와 결속되는 문구는 아래 것이다 — 위 줄의 문구는 비교기
    # 소스에서 f-string(`{KOTLIN_RUNTIME}`)이라 정적 대조가 안 되고, 이 줄은 리터럴이다.
    assert "Kotlin이 낸 결과가 아니면 비교 대상이 아니다" in output, (
        "위조 runtime 이 본류에서 잡히지 않았다 — compare_file 의 runtime_problem "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )


def test_본류가_runtime_미선언을_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """런타임 선언을 빠뜨린 손편집 결과 파일을 **본류가** 잡는지."""
    root = _mainline_tree(comparer, tmp_path, monkeypatch, actual={"cases": []})

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "runtime 미선언" in output, (
        "runtime 미선언이 본류에서 잡히지 않았다 — compare_file 의 runtime_problem "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )


def test_본류가_축소_fixture를_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """케이스를 덜어 낸 fixture 를 **본류가** 정본 대조로 잡는지.

    actual 을 두지 않는다 — 정본 대조는 actual 유무와 무관하게 돌아야 한다
    (`main` 의 해당 주석: "fixture가 위조됐다면 actual이 없어도 결함이다").
    """

    def truncate(document: dict[str, Any]) -> None:
        document["cases"] = document["cases"][:2]

    root = _mainline_tree(comparer, tmp_path, monkeypatch, mutate=truncate)

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "정본에서 사라졌다" in output, (
        "축소된 fixture 가 본류에서 잡히지 않았다 — main 의 provenance_problems "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )


def test_본류_대조군은_위조_문구_없이_실패한다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """대조군 — 위 세 문구가 조작 **때문에** 나왔음을 고정한다.

    손대지 않은 fixture + actual 없음이면 실패 사유는 '결과 파일 없음'과 도메인
    누락뿐이어야 한다. 종료 코드는 여기서도 0이 아니다 — 그래서 위 테스트들이
    종료 코드가 아니라 문구로 단언하는 것이고, 이 대조군이 그 귀속을 증명한다.
    """
    root = _mainline_tree(comparer, tmp_path, monkeypatch)

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "결과 파일 없음" in output
    for phrase in _MAINLINE_PHRASES:
        assert phrase not in output, f"대조군에서 helper 문구가 나왔다: {phrase!r}\n{output}"


def test_본류_정상_산출물_대조군은_helper_문구를_내지_않는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """둘째 대조군 — **정상 산출물**(reference 그대로)이 있어도 helper 문구가 없음을 고정한다.

    아래 원장 두 테스트(낡은 항목·기록 변경)는 actual 이 있어야 원장 경로에 들어가므로,
    "그 문구가 actual 을 뒀기 때문에 나온 것이 아님"을 이 대조군이 증명한다.
    """
    root = _mainline_tree(comparer, tmp_path, monkeypatch, actual_from_reference=True)

    _code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert "[충족] text" in output, output
    for phrase in _MAINLINE_PHRASES:
        assert phrase not in output, f"정상 대조군에서 helper 문구가 나왔다: {phrase!r}\n{output}"


def test_본류가_빈_fixture를_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`structural_problems` — 유일 호출부는 `main` 의 `problems = structural_problems(...)` 다.

    게이트 16 §6.1 표의 8자리에 없던 **아홉째** — 아래 완전성 테스트가 첫 실행에서 잡았다.
    케이스를 전부 비우면 "빈 fixture" 는 이 helper 만 낸다(케이스 하한은 "케이스가
    사라졌다", 정본 대조는 "정본에서 사라졌다" 로 각각 다른 문구를 낸다).
    """

    def empty(document: dict[str, Any]) -> None:
        document["cases"] = []

    root = _mainline_tree(comparer, tmp_path, monkeypatch, mutate=empty)

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "빈 fixture" in output, (
        "빈 fixture 가 본류에서 잡히지 않았다 — main 의 structural_problems "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )


def test_본류가_단언_없는_케이스를_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`spec_shape_problems` — 유일 호출부는 `structural_problems` 안이다.

    케이스 하나에서 `assert` 를 떼면 "단언 없는 spec 케이스"는 이 helper 만 낸다
    (정본 대조는 같은 케이스를 "정본과 다르다"로 따로 잡는다 — 문구가 다르다).
    """

    def drop_assert(document: dict[str, Any]) -> None:
        document["cases"][0].pop("assert")

    root = _mainline_tree(comparer, tmp_path, monkeypatch, mutate=drop_assert)

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "단언 없는 spec 케이스" in output, (
        "단언 없는 케이스가 본류에서 잡히지 않았다 — structural_problems 의 "
        f"spec_shape_problems 호출부가 사라졌는지 확인하라\n{output}"
    )


def test_본류가_케이스_하한_부재를_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`case_floor_problems` — 유일 호출부는 `main` 의 `floor_problems = ...` 다."""
    root = _mainline_tree(comparer, tmp_path, monkeypatch, case_floor="absent")

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "케이스 하한 파일 없음" in output, (
        "케이스 하한 부재가 본류에서 잡히지 않았다 — main 의 case_floor_problems "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )


def test_본류가_전체_게이트_미고정을_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`full_gate_floor_problems` — 유일 호출부는 `main` 의 `floor_problems += ...` 다.

    **게이트 14 R-4⑴ / Y-7 이 변이로 증명한 자리다** — 그 한 줄을 지워도 이 파일
    25 passed, 전체 1191 passed 였다. 이 저장소의 그 helper 테스트 6건이 전부 직접
    호출이었고, 본류 테스트는 표시 파일을 항상 유효하게 써 둬서(Z-b) 이 줄을 영영
    보지 못했다. 여기서는 표시를 **없앤다** — 선언 하한이 정본을 덮는데 표시가 없으면
    "하한을 고정하라"가 나와야 하고, 그 문구는 이 helper 만 낸다.
    """
    root = _mainline_tree(comparer, tmp_path, monkeypatch, full_gate_mark="absent")

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "하한을 고정하라" in output, (
        "전체 게이트 미고정이 본류에서 잡히지 않았다 — main 의 full_gate_floor_problems "
        f"호출부가 사라졌는지 확인하라 (게이트 14 R-4⑴ 재발)\n{output}"
    )


def test_본류가_전체_게이트_강등을_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """같은 helper 의 반대 방향 — 표시는 있는데 선언 하한이 줄었다."""
    root = _mainline_tree(comparer, tmp_path, monkeypatch, declared_floor="short")

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "내려왔다" in output, output


def test_본류가_낡은_원장_항목을_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`stale_ledger_problems` — 유일 호출부는 `main` 의 도메인 단위 루프다.

    fixture 에 없는 케이스를 원장이 들고 있으면 "낡은 원장 항목"이 나와야 한다. 이
    helper 는 도메인을 **완전히 대조한 실행**에서만 불리므로 정상 산출물을 함께 둔다.
    """
    ghost = {
        "domain": "text",
        "note": "합성",
        "recorded_at": "2026-08-15T00:00:00Z",
        "cases": {
            "ghost-case": {
                "status": "agree",
                "declared": False,
                "first_diff_path": "",
                "reference_sha256": "0",
                "actual_sha256": "0",
            }
        },
    }
    root = _mainline_tree(comparer, tmp_path, monkeypatch, actual_from_reference=True, ledger=ghost)

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "낡은 원장 항목" in output, (
        "낡은 원장 항목이 본류에서 잡히지 않았다 — main 의 stale_ledger_problems "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )


def test_본류가_원장_기록_변경을_알린다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`ledger_write_problems` — 유일 호출부는 `main` 의 `--record-reference` 분기다.

    원장이 없는 상태에서 기록 실행을 하면 "원장을 새로 만든다"가 나와야 한다 — 없음 →
    있음도 변경이다(X-12). 원장은 tmp 트리 안에만 쓰인다.
    """
    root = _mainline_tree(comparer, tmp_path, monkeypatch, actual_from_reference=True)

    _code, output = _run_mainline(comparer, monkeypatch, capsys, root, "--record-reference")

    assert "원장을 새로 만든다" in output, output
    # 표와 결속되는 리터럴 접두(위 줄 전체는 f-string `**원장을 {what}**` 이라 정적 대조가
    # 안 되고, 둘째 줄은 본류가 `splitlines()[0]` 만 찍어 출력에 없다).
    assert "**원장을 " in output, (
        "원장 기록 변경이 본류에서 보고되지 않았다 — main 의 ledger_write_problems "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )
    assert (root / "ledger/text.json").exists(), "원장이 tmp 트리에 쓰이지 않았다"


def test_본류가_하한_미등재_케이스를_알린다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`case_floor_additions` — 유일 호출부는 `main` 의 리포트 조립부다.

    막지 않고 이름만 남기는 helper 라 종료 코드로는 잴 수 없다 — 문구로만 잰다.
    """
    root = _mainline_tree(comparer, tmp_path, monkeypatch, case_floor="partial")

    _code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert "케이스 하한 미등재" in output, (
        "하한 미등재 케이스가 본류에서 보고되지 않았다 — main 의 case_floor_additions "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )
    assert "text: 1건" in output, output


def test_본류가_낡은_원장_기록을_막는다(
    comparer: ModuleType,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`reference_problems` — 유일 호출부는 `compare_file` 의 참고 대조 블록이다.

    게이트 16 완전성 테스트가 이 helper 를 "다른 helper 안에서만 불린다"고 **면제**했는데
    틀렸다 — 호출부는 `compare_file`(본류 함수)이고 `runtime_problem` 과 같은 자리다.
    면제 목록을 탐지형으로 바꾸자 드러났다(게이트 17 ③). 원장이 어떤 케이스를 `diverge` 로
    기록했는데 관측이 `agree` 면 "원장이 낡았다"가 나와야 한다 — 그 문구는 이 helper 만 낸다.
    """
    document = _fixture_document(comparer, "text")
    first_case = str(document["cases"][0]["id"])
    stale_status = {
        "domain": "text",
        "note": "합성",
        "recorded_at": "2026-08-15T00:00:00Z",
        "cases": {
            first_case: {
                "status": "diverge",
                "declared": False,
                "first_diff_path": "$.text",
                "reference_sha256": "0",
                "actual_sha256": "0",
            }
        },
    }
    root = _mainline_tree(
        comparer, tmp_path, monkeypatch, actual_from_reference=True, ledger=stale_status
    )

    code, output = _run_mainline(comparer, monkeypatch, capsys, root)

    assert code != 0
    assert "원장이 낡았다" in output, (
        "낡은 원장 기록이 본류에서 잡히지 않았다 — compare_file 의 reference_problems "
        f"호출부가 사라졌는지 확인하라\n{output}"
    )


def _call_sites(comparer: ModuleType, names: set[str]) -> dict[str, list[str]]:
    """비교기 소스를 AST 로 읽어 `names` 의 각 함수가 **어느 최상위 함수 안에서** 불리는지
    돌려준다. 문자열 grep 이 아니다 — 주석·docstring 의 `reference_problems()` 같은 언급은
    호출이 아니고, 여기서는 세지 않는다."""
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(comparer))
    sites: dict[str, list[str]] = {name: [] for name in names}
    for node in tree.body:
        if not isinstance(node, ast.FunctionDef):
            continue
        for call in ast.walk(node):
            if (
                isinstance(call, ast.Call)
                and isinstance(call.func, ast.Name)
                and call.func.id in sites
            ):
                sites[call.func.id].append(node.name)
    return sites


_HELPER_SUFFIXES: tuple[str, ...] = ("_problem", "_problems", "_additions")

#: 본류 함수 안에서 helper 를 **이름이 아닌 경로**로 얻는 형태 — 있으면 위 AST 대조가
#: 그 helper 를 못 보므로, 없음을 단언한다(탐지형 · 게이트 18 X2a).
_DYNAMIC_LOOKUP_NAMES: frozenset[str] = frozenset(
    {"getattr", "__import__", "globals", "locals", "vars", "eval", "exec"}
)


def _root_helper_calls(comparer: ModuleType) -> tuple[dict[str, list[str]], list[str]]:
    """`_call_sites` 를 **호출 측**에서 뒤집는다 (게이트 18 X2a).

    `_call_sites` 는 "이 정의가 어디서 불리나"를 본다 — 정의를 비교기 모듈에서 찾으므로
    **다른 모듈에 정의한 helper** 를 `main` 에 배선하면 보이지 않았다(codex 재현: 36 passed).
    여기서는 본류 함수(`_MAINLINE_ROOTS`) 안의 **모든** `ast.Call` 을 훑어, 이름이 helper 규약
    (`*_problem`·`*_problems`·`*_additions`)에 맞는 호출을 정의 모듈과 무관하게 모은다 —
    `name(...)` 과 `module.name(...)` 둘 다.

    함께 돌려주는 둘째 값은 본류 함수 안의 **동적 조회**(`getattr`·`__import__`·`importlib.
    import_module`·함수 안 `import` 문)다. 그런 형태가 있으면 이 AST 대조 자체가 helper 를
    못 보므로, 호출자는 그 목록이 비어 있음을 단언한다.
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(comparer))
    calls: dict[str, list[str]] = {}
    dynamic: list[str] = []
    for node in tree.body:
        if not isinstance(node, ast.FunctionDef) or node.name not in _MAINLINE_ROOTS:
            continue
        for sub in ast.walk(node):
            if isinstance(sub, (ast.Import, ast.ImportFrom)):
                dynamic.append(f"{node.name}: 함수 안 import 문")
                continue
            if not isinstance(sub, ast.Call):
                continue
            func = sub.func
            if isinstance(func, ast.Name):
                called = func.id
                if called in _DYNAMIC_LOOKUP_NAMES:
                    dynamic.append(f"{node.name}: {called}(...)")
            elif isinstance(func, ast.Attribute):
                called = func.attr
                if called == "import_module":
                    dynamic.append(f"{node.name}: importlib.import_module(...)")
            else:
                continue
            if called.endswith(_HELPER_SUFFIXES):
                calls.setdefault(called, []).append(node.name)
    return calls, dynamic


def _asserted_output_phrases(test_fn: Callable[..., object]) -> set[str]:
    """테스트 함수 AST 에서 `assert "<문구>" in output` 형태의 **문자열 상수**만 모은다.

    게이트 17 ④ 의 결속은 문자열 grep 이라 주석 한 줄(`# assert "…" in output`)로 충족됐다
    (게이트 18 T1). AST 에는 주석이 없으므로 그 경로가 자동으로 닫힌다.
    """
    import ast
    import inspect
    import textwrap

    tree = ast.parse(textwrap.dedent(inspect.getsource(test_fn)))
    phrases: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assert) or not isinstance(node.test, ast.Compare):
            continue
        compare = node.test
        if (
            isinstance(compare.left, ast.Constant)
            and isinstance(compare.left.value, str)
            and len(compare.ops) == 1
            and isinstance(compare.ops[0], ast.In)
            and isinstance(compare.comparators[0], ast.Name)
            and compare.comparators[0].id == "output"
        ):
            phrases.add(compare.left.value)
    return phrases


def _string_constants(source: str) -> list[str]:
    """소스 AST 의 문자열 상수 전부 — **docstring 은 뺀다**(모듈·함수·클래스 본문의 첫 문장).
    f-string 조각(`JoinedStr` 안의 `Constant`)은 들어간다. 주석은 AST 에 없어 자동으로 빠진다."""
    import ast

    tree = ast.parse(source)
    docstrings: set[int] = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            body = node.body
            if (
                body
                and isinstance(body[0], ast.Expr)
                and isinstance(body[0].value, ast.Constant)
                and isinstance(body[0].value.value, str)
            ):
                docstrings.add(id(body[0].value))
    return [
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant)
        and isinstance(node.value, str)
        and id(node) not in docstrings
    ]


def test_본류_회귀_목록이_비교기의_게이트_helper_전부를_덮는다(comparer: ModuleType) -> None:
    """완전성 — `_MAINLINE_HELPERS` 를 비교기 코드에서 **자동 열거**한 이름과 대조한다.

    "게이트 helper" 의 기계적 정의: 비교기 모듈의 최상위 함수 중 이름이 `_problem`·
    `_problems`·`_additions` 로 끝나는 것. 이 저장소가 게이트 판정 문구를 내는 함수에
    붙여 온 이름 규약이다. 이 정의가 잡지 못하는 것 — 규약을 따르지 않는 이름의 helper.
    그것은 이름 규약 자체가 막을 일이라 여기서 더 넓히지 않는다(넓히면 `_rules`·`load`
    같은 비판정 함수가 섞여 표가 의미를 잃는다).

    ## 게이트 17 이 잡은 이 테스트 자신의 빈자리 4건 — 각각 어디서 닫히는가

    ⑤ **빈 표에서 통과** → 표 크기 **정확 일치**(`EXPECTED_MAINLINE_HELPERS`). 발견 집합이
       비어도(비교기 적재 실패·대량 개명) 이 단언 하나가 빨개진다.
    ③ **`nested` 면제 목록(은폐형)** → 탐지형. 표에 없는 helper 는 "본류 함수(`main`·
       `compare_file`)에서 불리지 않고, 표의 helper 안에서만 불린다"를 **AST 로 단언**한다.
       면제 한 줄로는 통과하지 못한다 — 그 성질을 실제로 만족해야 한다. 이 전환이 곧
       `reference_problems` 를 열째로 드러냈다(그것은 `compare_file` 에서 불린다).
    ② **테스트 이름 재사용 승인** → 표의 테스트 이름이 서로 다르고(유일성), 각 테스트의
       **문자열 상수**가 그 helper 이름을 담는다(결속).
    ④ **판정 문구 미대조** → 표의 문구가 그 테스트의 `assert "…" in output` **단언 노드**에
       있고, 비교기의 **문자열 상수**에도 있어야 한다(가짜 문구·낡은 문구 둘 다 막는다).

    또 표의 각 helper 가 **정말 본류 호출부가 한 곳뿐인지**를 AST 로 센다 — 이 파일 머리
    주석이 그렇게 선언하고 있고, 선언은 실행으로 대조한다(규칙 4).

    ## 게이트 18 이 잡은 빈자리 3건 — 각각 어디서 닫히는가

    **X1** 파생 상수 `_MAINLINE_PHRASES` 를 `= ()` 로 직접 편집하면 대조군 두 곳이 아무것도
       부정하지 않은 채 통과했다(표는 온전해서 ⑤ 가 막지 못한다) → 개수 **정확 일치**
       (`EXPECTED_MAINLINE_PHRASES`). ⑤ 와 같은 방식이다.
    **X2a** 위 `discovered` 는 **비교기 모듈에 정의된** 함수만 훑으므로, 외부 모듈에 정의한
       helper 를 `main` 에 배선하면 어떤 단언도 그것을 보지 못했다 → 같은 AST 대조를 **호출
       측**에서 뒤집어(`_root_helper_calls`) 본류 함수 안의 helper 규약 호출은 **정의 모듈과
       무관하게** 표에 있어야 한다. 이 대조를 무력화하는 동적 조회는 **없음을 단언**한다(탐지형).
    **T1** ②④ 의 결속이 문자열 grep 이라 단언을 **주석 한 줄**로 바꿔도 통과했다 — ③ 에서
       이미 버린 방법이 여기 남아 있었다 → AST 로 교체. 주석은 AST 에 없어 자동으로 빠진다.
    """
    import inspect
    import textwrap

    discovered = {
        name
        for name, member in vars(comparer).items()
        if inspect.isfunction(member)
        and member.__module__ == comparer.__name__
        and not name.startswith("_")
        and name.endswith(_HELPER_SUFFIXES)
    }
    # ⑤ 정확 일치. 하한이 아니다 — 여유는 그만큼 조용히 줄어들 수 있는 폭이다.
    assert len(_MAINLINE_HELPERS) == EXPECTED_MAINLINE_HELPERS, (
        f"본류 회귀 표가 {len(_MAINLINE_HELPERS)}행인데 기대는 {EXPECTED_MAINLINE_HELPERS}행이다 — "
        "helper 를 더했거나 뺐다면 이 상수도 같은 커밋에서 고쳐 diff 를 리뷰에 올린다"
    )
    # X1 정확 일치. 대조군 두 곳이 재는 문구 수는 표에서 파생되므로 표가 줄면 같이 줄지만,
    # `_MAINLINE_PHRASES` 를 **직접** 비우거나 깎으면 여기서 홀로 빨개진다.
    assert len(_MAINLINE_PHRASES) == EXPECTED_MAINLINE_PHRASES, (
        f"대조군 문구 목록이 {len(_MAINLINE_PHRASES)}개인데 기대는 "
        f"{EXPECTED_MAINLINE_PHRASES}개다 — 문구를 더했거나 뺐다면 이 상수도 같은 커밋에서 "
        "고쳐 diff 를 리뷰에 올린다"
    )
    assert discovered, "비교기에서 게이트 helper 를 하나도 찾지 못했다 — 적재·이름 규약을 확인하라"

    sites = _call_sites(comparer, discovered)
    table = set(_MAINLINE_HELPERS)
    stale = sorted(table - discovered)
    assert not stale, f"_MAINLINE_HELPERS 가 비교기에 없는 이름을 가리킨다: {stale}"

    # X2a 같은 AST 대조를 **호출 측**에서 뒤집는다. 위 `discovered` 는 정의가 비교기 모듈에
    # 있는 것만 보므로 외부 모듈 helper 를 본류에 배선하면 통과했다(게이트 18 X2a).
    root_calls, dynamic_lookups = _root_helper_calls(comparer)
    assert root_calls, (
        "본류 함수에서 helper 규약 호출을 하나도 찾지 못했다 — `_MAINLINE_ROOTS` 가 비었거나 "
        "본류 함수 이름이 바뀌었거나 배선이 통째로 사라졌다"
    )
    assert not dynamic_lookups, (
        f"본류 함수가 helper 를 이름 아닌 경로로 얻을 수 있는 형태를 쓴다: {dynamic_lookups} — "
        "그 경로는 이 AST 대조가 보지 못하므로 정적 호출로 되돌려라"
    )
    off_table = sorted(set(root_calls) - table)
    assert not off_table, (
        f"본류 함수가 표에 없는 게이트 helper 를 부른다: "
        f"{[(name, root_calls[name]) for name in off_table]} — 정의가 다른 모듈에 있어도 "
        "_MAINLINE_HELPERS 에 한 줄 더하고 본류 테스트를 하나 붙여라"
    )

    # ③ 표에 없는 helper 는 **중첩 전용**이어야 한다 — 이름을 대고 빼지 않고 성질로 판정한다.
    for name in sorted(discovered - table):
        callers = sites[name]
        assert callers, (
            f"`{name}` 은 어디서도 불리지 않는다 — 죽은 helper 거나 호출 형태가 바뀌었다"
        )
        in_roots = [caller for caller in callers if caller in _MAINLINE_ROOTS]
        assert not in_roots, (
            f"`{name}` 은 본류 함수 {in_roots} 에서 불리는데 본류 회귀 표에 없다 — "
            "_MAINLINE_HELPERS 에 한 줄 더하고 본류 테스트를 하나 붙여라"
        )
        outside = [caller for caller in callers if caller not in table]
        assert not outside, (
            f"`{name}` 이 표의 helper 가 아닌 {outside} 에서 불린다 — 중첩 전용이 아니므로 "
            "본류 회귀가 필요하다"
        )

    # 표의 helper 는 **본류 호출부가 정확히 한 곳**이어야 한다(이 파일의 선언). 호출부는
    # 본류 함수이거나 표의 다른 helper 여야 한다(spec_shape ← structural 처럼).
    for name in sorted(table):
        callers = sites[name]
        assert len(callers) == 1, (
            f"`{name}` 의 호출부가 {len(callers)}곳이다({callers}) — '한 곳뿐' 선언이 낡았다. "
            "호출부가 늘었다면 회귀 설계를 다시 보라(한 줄 제거 실험이 더 이상 그 helper 를 "
            "덮지 않는다)"
        )
        assert callers[0] in _MAINLINE_ROOTS or callers[0] in table, (
            f"`{name}` 의 호출부 `{callers[0]}` 는 본류 함수도 표의 helper 도 아니다"
        )

    # ② 유일성 + 결속 / ④ 문구 결속.
    test_names = [test_name for test_name, _phrase in _MAINLINE_HELPERS.values()]
    assert len(set(test_names)) == len(test_names), (
        f"표의 테스트 이름이 겹친다 — 한 테스트가 두 helper 를 대표할 수 없다: {test_names}"
    )
    # T1 — 아래 세 결속은 **AST 의 문자열 상수**만 본다. 소스 문자열 grep 이던 시절에는
    # 단언을 주석 처리해도 텍스트가 남아 통과했다(게이트 18 T1).
    comparer_constants = _string_constants(inspect.getsource(comparer))
    for helper, (test_name, phrase) in _MAINLINE_HELPERS.items():
        test_fn = globals().get(test_name)
        assert callable(test_fn), (
            f"{helper} 의 본류 테스트 `{test_name}` 이 이 모듈에 없다 — 표가 문장이 됐다"
        )
        test_constants = _string_constants(textwrap.dedent(inspect.getsource(test_fn)))
        assert any(helper in constant for constant in test_constants), (
            f"`{test_name}` 의 **문자열 상수**에 `{helper}` 가 없다 — 이 테스트가 그 helper 를 "
            "겨냥한다는 결속이 없다(실패 메시지에 helper 이름을 적어라). "
            "주석·docstring 은 세지 않는다"
        )
        assert phrase in _asserted_output_phrases(test_fn), (
            f'`{test_name}` 이 표의 문구 {phrase!r} 를 `assert "…" in output` 으로 단언하지 '
            "않는다 — 표의 문구와 실제 단언이 갈렸다. 주석 처리된 단언은 AST 에 없어 세지 않는다"
        )
        assert any(phrase in constant for constant in comparer_constants), (
            f"표의 문구 {phrase!r} 가 비교기의 **문자열 상수**에 없다 — 비교기가 낼 수 없는 "
            "문구를 표가 적고 있다(주석·docstring 은 세지 않는다)"
        )


# --- 정본(BUILDERS) 쪽 우회는 CI 셸이 막는다 -------------------------------
#
# 위 넷은 비교기 함수를 직접 부르지만, 정본 개수는 셸이 `--list` 출력으로 센다. 그래서
# 이 둘만 셸 경로로 검증한다 — 생성기를 **출력만 바꾼 대역**으로 갈아 끼워 재현한다.


def _stub_generator(root: Path, domains: list[str]) -> None:
    """`--list` 가 주어진 도메인만 출력하는 대역으로 생성기를 갈아 끼운다."""
    target = root / ".claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py"
    listing = "".join(f"{name} 설명\n" for name in domains)
    target.write_text(
        f"import sys\nif '--list' in sys.argv:\n    sys.stdout.write({listing!r})\nsys.exit(0)\n",
        encoding="utf-8",
    )


def test_정본이_0개면_실패한다(tmp_path: Path, script: str) -> None:
    """정본이 비면 "몇 개를 안 봤다"를 셀 수 없어 경고가 스스로 무의미해진다.

    이 검사가 없으면 무검증이 **더 자랑스러운 로그**와 함께 통과한다 — "정본 0개 /
    검증하지 않음 0개".
    """
    root = _tree(tmp_path)
    _stub_generator(root, [])

    result = _run(root, script)

    assert result.returncode != 0
    assert "정본 도메인이 0개다" in result.stdout


def test_정본이_부분_삭제되면_실패한다(tmp_path: Path, script: str) -> None:
    """0개까지 가지 않는 축소. 정본 하한(`parity-canonical-floor.txt`)이 그 자리를 받는다.

    `canonical_count == 0` 검사만 있던 시절에는 8개를 3개로 줄이는 편집이 그대로 통과했고,
    선언까지 3개로 맞추면 **축소가 "전체 게이트 통과"로 위장**됐다.
    """
    root = _tree(tmp_path)
    _stub_generator(root, ["masking", "text", "style"])

    result = _run(root, script)

    assert result.returncode != 0
    assert "정본에서 도메인이 사라졌다" in result.stdout


def test_정본이_온전하면_셸_가드를_통과한다(tmp_path: Path, script: str) -> None:
    """대조군. 대역 생성기 자체가 셸을 깨뜨리는 것이 아님을 고정한다."""
    root = _tree(tmp_path)
    floor = (root / ".github/parity-canonical-floor.txt").read_text(encoding="utf-8")
    domains = [
        line.split("#", 1)[0].strip()
        for line in floor.splitlines()
        if line.split("#", 1)[0].strip()
    ]
    _stub_generator(root, domains)

    result = _run(root, script)

    assert BANNER in result.stdout, result.stdout
    assert "정본 도메인이 0개다" not in result.stdout
    assert "정본에서 도메인이 사라졌다" not in result.stdout
