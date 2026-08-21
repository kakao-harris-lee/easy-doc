"""로컬 검증 러너가 **CI 잡의 부분집합이 될 수 없음**을 고정한다 (β-01 의 장치 절반).

## 무엇이 났는가

리더가 「전건 초록」을 보고했는데 CI `quality` 잡이 빨갰다. 원인은 검증 집합이 그 잡의
**부분집합**이었던 것이다 — 빠진 것: `ruff format --check` · `dump_python_snapshots.py --check` ·
개별 경로 pytest 8종. 같은 형태가 이 세션에서 **세 번** 났다(L-㉕ #4 · Cβ-1 · codex C2-7).
인스턴스는 커밋 `94440d8` 로 닫혔지만 **재발 방지 장치가 0** 이었다 — 실측:
`ci.yml` 의 명령 집합을 로컬 검증과 대조하는 파일이 저장소에 하나도 없었고,
`run_gate.sh` 는 스스로 「CI 배선 0」을 자인한다.

## 처방이 둘이고 **다른 층**을 막는다

교차 종합이 두 레인의 처방을 나란히 남겼고 「하나만으로는 다른 쪽이 열린다」고 적었다.

1. **라벨을 명령 단위로 분해한다**(Claude) — 「ruff 0」처럼 뭉친 라벨은 빠진 절반을 문면에서
   지운다. 러너는 집계 라벨을 만들지 않고 명령마다 종료 코드를 찍는다.
2. **출하 근거를 정확한 HEAD SHA 에 결속한다**(codex) — 트리가 더럽거나 HEAD 가 움직이면
   그 결과는 어느 커밋의 것도 아니므로 「전건 초록」을 찍지 않는다.

이 파일은 그 둘을 **각각** 음성 대조로 고정한다. 그리고 가장 중요한 것 하나 — 러너의 명령
목록이 `ci.yml` 에서 **유도**되는지를 잰다. 손으로 적은 목록은 그것이 곧 부분집합을 만드는
경로이고, β-01 이 정확히 그 형태였다.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
RUNNER = REPO_ROOT / ".claude/skills/kotlin-migration/scripts/quality_gate_local.py"
CI_WORKFLOW = REPO_ROOT / ".github/workflows/ci.yml"
THIS_TEST_PATH = "tests/test_quality_gate_local.py"

#: 러너가 「전건 초록」을 찍는 유일한 문면. 부재로 거부를 판정한다.
ALL_GREEN = "판정: 전건 초록"


def _run(*args: str, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(RUNNER), *args],
        cwd=cwd or REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def _listed_commands(*args: str) -> list[str]:
    result = _run("--list", *args)
    assert result.returncode == 0, (
        f"--list 가 실패했다 (exit {result.returncode}):\n{result.stderr}"
    )
    return [
        line.split("\t", 4)[4] for line in result.stdout.splitlines() if line.startswith("RUN\t")
    ]


def _independent_parse(workflow: Path, job: str = "quality") -> list[str]:
    """러너와 **독립한** 파싱. 같은 코드를 부르면 대조가 자기 자신과의 일치가 된다."""
    document = yaml.safe_load(workflow.read_text(encoding="utf-8"))
    steps = document["jobs"][job]["steps"]
    return [str(step["run"]) for step in steps if isinstance(step, dict) and step.get("run")]


def test_러너가_실재한다() -> None:
    """**빈 선언에서 통과하면 안 된다** — 러너가 없으면 아래 대조 전부가 0건 검사가 된다."""
    assert RUNNER.is_file(), f"러너가 없다: {RUNNER}"


def test_명령_집합이_ci_yml_에서_유도된다() -> None:
    """러너의 목록이 CI `quality` 잡의 `run:` **전건**과 정확히 같아야 한다.

    부분집합이면 여기서 빨개진다 — 그것이 β-01 의 인스턴스가 통과했던 자리다.
    """
    assert _listed_commands() == _independent_parse(CI_WORKFLOW), (
        "러너의 명령 목록이 ci.yml quality 잡의 `run:` 집합과 다르다.\n"
        "  **부분집합은 「전건 초록」의 근거가 될 수 없다** — 빠진 명령이 CI 에서 빨개진다."
    )


def test_워크플로를_고치면_목록이_따라_바뀐다(tmp_path: Path) -> None:
    """**유도인지 하드코딩인지**를 가른다.

    목록을 손으로 적어 두고 위 대조만 통과시키는 길이 있다(그 순간에는 두 값이 같으니까).
    그러면 다음에 CI 에 스텝이 하나 늘 때 러너는 조용히 부분집합으로 되돌아간다.
    워크플로를 고쳐 목록이 **따라 바뀌는지** 보면 그 길이 닫힌다.
    """
    original = _independent_parse(CI_WORKFLOW)
    assert len(original) >= 2, "quality 잡의 명령이 2개 미만이면 이 대조가 성립하지 않는다."

    document = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
    steps = document["jobs"]["quality"]["steps"]
    dropped_index = next(
        index for index, step in enumerate(steps) if isinstance(step, dict) and step.get("run")
    )
    dropped = str(steps[dropped_index]["run"])
    del steps[dropped_index]

    workflow = tmp_path / "ci.yml"
    workflow.write_text(yaml.safe_dump(document, allow_unicode=True), encoding="utf-8")

    listed = _listed_commands("--workflow", str(workflow))
    assert dropped not in listed, (
        f"워크플로에서 스텝을 지웠는데 러너의 목록에 그 명령이 남아 있다: {dropped!r}\n"
        "  목록이 ci.yml 에서 유도되지 않고 **어딘가에 적혀 있다**는 뜻이다."
    )
    assert len(listed) == len(original) - 1, (
        f"명령 수가 {len(original)} → {len(listed)} 다 — 정확히 하나만 줄어야 한다."
    )


def test_ruff_두_명령이_각각_한_줄이다() -> None:
    """β-01 인스턴스의 **정확한 형태**를 고정한다.

    빠진 절반은 `ruff format --check` 였고, 그것이 보이지 않은 이유는 라벨이 「ruff 0」으로
    뭉쳐 있었기 때문이다. 러너는 두 명령을 **각각 한 줄**로 낸다 — 한 줄에 둘이 들어가면
    다시 하나의 라벨이 되고 절반이 숨을 수 있다.
    """
    listed = _listed_commands()
    check = [line for line in listed if "ruff check" in line]
    fmt = [line for line in listed if "ruff format" in line]
    assert check, "`ruff check` 명령이 목록에 없다."
    assert fmt, "`ruff format --check` 명령이 목록에 없다 — β-01 에서 빠져 있던 그 절반이다."
    assert not [line for line in listed if "ruff check" in line and "ruff format" in line], (
        "한 줄에 `ruff check` 와 `ruff format` 이 함께 있다 — 그것이 뭉친 라벨이고, "
        "뭉치면 절반이 빠진 것을 문면에서 볼 수 없다."
    )


def _temp_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "repo"
    repo.mkdir()
    for args in (
        ("init", "-q"),
        ("config", "user.email", "t@example.com"),
        ("config", "user.name", "t"),
    ):
        subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True)
    (repo / "seed.txt").write_text("seed\n", encoding="utf-8")
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-q", "-m", "seed"], cwd=repo, check=True, capture_output=True)
    return repo


def _temp_workflow(tmp_path: Path, commands: list[str], name: str = "ci.yml") -> Path:
    workflow = tmp_path / name
    workflow.write_text(
        yaml.safe_dump(
            {
                "jobs": {
                    "quality": {
                        "steps": [
                            {"name": f"명령 {index}", "run": command}
                            for index, command in enumerate(commands, 1)
                        ]
                    }
                }
            },
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    return workflow


def test_명령이_실패하면_전건_초록을_찍지_않는다(tmp_path: Path) -> None:
    repo = _temp_repo(tmp_path)
    workflow = _temp_workflow(tmp_path, ["true", "false", "true"])

    result = _run("--workflow", str(workflow), "--repo", str(repo))
    assert result.returncode == 1, f"명령 하나가 빨간데 exit {result.returncode} 다."
    assert ALL_GREEN not in result.stdout, "명령이 빨간데 「전건 초록」을 찍었다."
    assert "판정: 미충족" in result.stdout, f"거부 문면이 없다:\n{result.stdout}"
    assert "[2] 빨강" in result.stdout, (
        f"실패한 명령을 **번호로 지목**하지 않았다 — 판정 기준은 「빨개졌는가」가 아니라 "
        f"「짚었는가」다:\n{result.stdout}"
    )


def test_깨끗한_트리에서만_전건_초록을_찍는다(tmp_path: Path) -> None:
    repo = _temp_repo(tmp_path)
    workflow = _temp_workflow(tmp_path, ["true"])

    clean = _run("--workflow", str(workflow), "--repo", str(repo))
    assert clean.returncode == 0, (
        f"깨끗한 트리 · 전건 통과인데 exit {clean.returncode}:\n{clean.stdout}"
    )
    assert ALL_GREEN in clean.stdout, f"양성 대조가 성립하지 않는다:\n{clean.stdout}"

    (repo / "dirty.txt").write_text("x\n", encoding="utf-8")
    dirty = _run("--workflow", str(workflow), "--repo", str(repo))
    assert dirty.returncode == 3, (
        f"트리가 더러운데 exit {dirty.returncode} 다 — 명령 초록과 **결속**은 다른 판정이다."
    )
    assert ALL_GREEN not in dirty.stdout, "트리가 더러운데 「전건 초록」을 찍었다."
    assert "작업 트리가 더럽다" in dirty.stdout, f"거부 사유를 짚지 않았다:\n{dirty.stdout}"


def test_HEAD_가_움직이면_결속하지_않는다(tmp_path: Path) -> None:
    """명령이 도는 중에 HEAD 가 바뀌면 그 결과는 **어느 커밋의 것도 아니다**."""
    repo = _temp_repo(tmp_path)
    workflow = _temp_workflow(tmp_path, ["git commit -q --allow-empty -m moved"])

    result = _run("--workflow", str(workflow), "--repo", str(repo))
    assert result.returncode == 3, f"HEAD 가 움직였는데 exit {result.returncode} 다."
    assert ALL_GREEN not in result.stdout, "HEAD 가 움직였는데 「전건 초록」을 찍었다."
    assert "HEAD 가 움직였다" in result.stdout, f"거부 사유를 짚지 않았다:\n{result.stdout}"


def test_명령이_0개면_통과가_아니다(tmp_path: Path) -> None:
    """범위 선언형이 **빈 선언에서 초록이 되는 것**을 막는다 (CLAUDE.md 규칙 4 ⑶)."""
    repo = _temp_repo(tmp_path)
    workflow = tmp_path / "empty.yml"
    workflow.write_text(
        yaml.safe_dump({"jobs": {"quality": {"steps": [{"uses": "actions/checkout@v4"}]}}}),
        encoding="utf-8",
    )

    result = _run("--workflow", str(workflow), "--repo", str(repo))
    assert result.returncode == 2, f"명령이 0개인데 exit {result.returncode} 다."
    assert ALL_GREEN not in result.stdout


def test_실행하지_않는_것을_감추지_않는다() -> None:
    """`uses:` 액션과 `services:` 는 로컬에서 재현되지 않는다 — 그 차이를 **문면에 남긴다**.

    목록에서 빼 버리면 「CI 와 같은 집합을 돌렸다」가 조용히 거짓이 된다. 러너는 그것들을
    `USES`·`SERVICE` 줄로 찍어 차이를 보이게 한다.
    """
    result = _run("--list")
    assert result.returncode == 0
    assert [line for line in result.stdout.splitlines() if line.startswith("USES\t")], (
        "실행하지 않는 액션 스텝을 목록에 찍지 않는다 — 차이가 문면에서 사라진다."
    )
    assert [line for line in result.stdout.splitlines() if line.startswith("SERVICE\t")], (
        "CI 서비스 컨테이너를 목록에 찍지 않는다 — 로컬에 없는 전제가 감춰진다."
    )


def test_잡_환경변수가_명령에_전달된다() -> None:
    """`env:` 를 빼먹으면 로컬 실행이 CI 와 **다른 조건**으로 돌아 초록이 무의미해진다."""
    result = _run("--list")
    envs = [
        json.loads(line.split("\t", 4)[3])
        for line in result.stdout.splitlines()
        if line.startswith("RUN\t")
    ]
    assert envs and all("DATABASE_URL" in env for env in envs), (
        "quality 잡의 `env:`(DATABASE_URL 등)가 명령에 전달되지 않는다 — "
        f"실측된 env 키: {sorted(envs[0]) if envs else '없음'}"
    )


def test_CI_가_이_검사를_경로_명시로_배선했다() -> None:
    """**장치 밖에서 무언가 깨져야 한다** (SKILL.md 규칙 6).

    이 파일 안에만 단언을 두면 파일과 함께 사라진다. `uv run pytest` 전체 수집만 믿으면
    삭제가 수집 0 으로 조용히 통과한다 — 경로를 명시하면 `exit 4` 로 죽는다.
    """
    document = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
    jobs = [
        job_name
        for job_name, job in (document.get("jobs") or {}).items()
        for step in (job.get("steps") or [])
        if isinstance(step, dict) and THIS_TEST_PATH in str(step.get("run") or "")
    ]
    assert jobs, (
        f"ci.yml 이 {THIS_TEST_PATH} 를 경로로 명시해 돌리지 않는다 — 이 파일을 지우면 "
        "β-01 의 재발 방지 장치가 아무 데서도 신고되지 않는다."
    )


@pytest.mark.parametrize("needle", ["ruff format --check", "dump_python_snapshots.py --check"])
def test_β01_에서_빠져_있던_명령이_목록에_있다(needle: str) -> None:
    """빠졌던 것을 **이름으로** 고정한다 — 목록이 다시 줄면 여기가 먼저 빨개진다."""
    assert [line for line in _listed_commands() if needle in line], (
        f"`{needle}` 가 러너 목록에 없다. 이것은 β-01 에서 실제로 빠져 있던 명령이고, "
        "그 부재가 CI 를 빨갛게 만들었다."
    )
