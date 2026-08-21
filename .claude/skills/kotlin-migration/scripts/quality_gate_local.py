#!/usr/bin/env python3
"""로컬 검증을 CI `quality` 잡과 **같은 집합**으로 돌리고, 그 결과를 HEAD SHA 에 결속한다.

## 왜 있는가 (β-01 — 이 세션에서 세 번째 재발)

리더가 「전건 초록」을 보고했는데 CI 가 빨갰다. 원인은 **검증 집합이 CI `quality` 잡의
부분집합**이었던 것이다 — 빠진 것: `ruff format --check` · `dump_python_snapshots.py --check` ·
개별 경로 pytest 8종. 그리고 **아무도 그 차이를 재지 않았다.** 라벨이 「ruff 0」처럼 뭉쳐
있으면 빠진 절반이 문면에 보이지 않는다.

두 처방을 **둘 다** 넣는다. 하나만으로는 다른 쪽이 열린다.

1. **라벨을 명령 단위로 분해한다.** 이 스크립트는 집계 라벨을 만들지 않는다. `--list` 는
   명령 하나에 줄 하나를 내고, 실행 결과도 명령마다 종료 코드를 따로 찍는다. 「전건 초록」은
   그 표의 요약이지 대체물이 아니다.
2. **출하 근거를 정확한 HEAD SHA 에 결속한다.** 시작·끝 HEAD 와 작업 트리 상태를 재고,
   ⑴ HEAD 가 움직였거나 ⑵ 트리가 더러우면 **어느 커밋의 결과도 아니므로** 「전건 초록」을
   찍지 않는다. 명령이 전부 통과해도 그렇다 — 그때는 종료 코드 3(미결속)이다.

## 명령 목록을 손으로 적지 않는다

목록은 `ci.yml` 의 해당 잡에서 **유도한다.** 손으로 적으면 그것이 곧 부분집합을 만드는
경로이고, β-01 이 정확히 그 형태였다. `tests/test_quality_gate_local.py` 가 「워크플로를
고치면 목록이 따라 바뀐다」를 음성 대조로 고정한다 — 하드코딩된 목록은 그 대조를 통과할 수
없다.

## 이 스크립트가 재현하지 못하는 것 (감추지 않고 찍는다)

`services:`(PostgreSQL·Redis 컨테이너)는 로컬에서 자동으로 뜨지 않는다. `--list` 가 그
서비스를 `SERVICE` 줄로 찍으므로 **차이가 문면에 남는다.** 그것을 근거로 명령을 빼지 않는다 —
서비스가 필요한 명령은 서비스 없이 실패하고, 그 실패가 「전건 초록이 아니다」의 정직한 답이다.
`uses:` 액션 스텝(체크아웃·uv 설치)도 실행하지 않고 `USES` 줄로 찍는다.

## 종료 코드

    0  명령 전건 초록 **이고** HEAD 에 결속됐다 (트리 깨끗 · HEAD 불변)
    1  명령 하나 이상이 실패했다
    2  사용법·워크플로 파싱 오류, 또는 대상 명령이 0개다 (빈 선언은 통과가 아니다)
    3  명령은 전건 초록이나 **결속되지 않았다** (트리 더러움 또는 HEAD 이동)

## 사용

    uv run python .claude/skills/kotlin-migration/scripts/quality_gate_local.py --list
    uv run python .claude/skills/kotlin-migration/scripts/quality_gate_local.py
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

DEFAULT_WORKFLOW = Path(".github/workflows/ci.yml")
DEFAULT_JOB = "quality"

#: 「전건 초록」을 찍는 유일한 문면. 테스트가 이 문자열의 부재로 거부를 판정한다.
ALL_GREEN = "판정: 전건 초록"


@dataclass(frozen=True)
class Command:
    """`run:` 스텝 하나. 라벨이 아니라 **명령**이 단위다."""

    index: int
    name: str
    script: str
    working_directory: str
    env: dict[str, str]


def _text(value: object) -> str:
    return "" if value is None else str(value)


def _mapping(value: object) -> dict[str, Any]:
    return dict(value) if isinstance(value, dict) else {}


def parse_job(workflow_text: str, job_name: str) -> tuple[list[Command], list[str], list[str]]:
    """(명령 목록, `uses:` 액션 목록, 서비스 이름 목록).

    액션과 서비스를 함께 돌려주는 이유는 **차이를 감추지 않기 위해서**다. 실행하지 않는
    것을 목록에서 빼 버리면 「CI 와 같은 집합을 돌렸다」가 조용히 거짓이 된다.
    """
    document = yaml.safe_load(workflow_text)
    if not isinstance(document, dict):
        raise ValueError("워크플로 최상위가 매핑이 아니다")
    jobs = _mapping(document.get("jobs"))
    if job_name not in jobs:
        raise ValueError(f"`{job_name}` 잡이 워크플로에 없다 (있는 잡: {sorted(jobs)})")
    job = _mapping(jobs[job_name])
    job_env = {str(k): _text(v) for k, v in _mapping(job.get("env")).items()}
    job_dir = (
        _text(_mapping(_mapping(job.get("defaults")).get("run")).get("working-directory")) or "."
    )

    commands: list[Command] = []
    actions: list[str] = []
    for position, raw_step in enumerate(job.get("steps") or [], start=1):
        step = _mapping(raw_step)
        script = _text(step.get("run"))
        if not script:
            uses = _text(step.get("uses"))
            if uses:
                actions.append(f"{position}: {uses}")
            continue
        env = dict(job_env)
        env.update({str(k): _text(v) for k, v in _mapping(step.get("env")).items()})
        commands.append(
            Command(
                index=position,
                name=_text(step.get("name")) or "(이름 없음)",
                script=script,
                working_directory=_text(step.get("working-directory")) or job_dir,
                env=env,
            )
        )
    services = sorted(_mapping(job.get("services")))
    return commands, actions, services


def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, check=False)
    return result.stdout.strip() if result.returncode == 0 else ""


def _dirty_files(repo: Path) -> list[str]:
    porcelain = _git(repo, "status", "--porcelain")
    return [line for line in porcelain.splitlines() if line.strip()]


def render_list(commands: list[Command], actions: list[str], services: list[str]) -> str:
    """`--list` 출력. 명령 하나에 줄 하나 — **집계 라벨을 만들지 않는다.**"""
    lines = [
        f"RUN\t{command.index}\t{command.working_directory}\t"
        f"{json.dumps(command.env, ensure_ascii=False, sort_keys=True)}\t{command.script}"
        for command in commands
    ]
    lines += [f"USES\t{action}" for action in actions]
    lines += [f"SERVICE\t{service}" for service in services]
    return "\n".join(lines)


def run_all(commands: list[Command], repo: Path) -> list[tuple[Command, int]]:
    results: list[tuple[Command, int]] = []
    for command in commands:
        cwd = (repo / command.working_directory).resolve()
        print(f"\n[{command.index}] {command.name}\n    $ {command.script}", flush=True)
        completed = subprocess.run(
            ["bash", "-o", "pipefail", "-c", command.script],
            cwd=cwd,
            env={**_inherited_env(), **command.env},
            check=False,
        )
        print(f"    exit={completed.returncode}", flush=True)
        results.append((command, completed.returncode))
    return results


def _inherited_env() -> dict[str, str]:
    return dict(os.environ)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="CI 잡과 같은 집합을 로컬에서 돌린다")
    parser.add_argument("--workflow", type=Path, default=None)
    parser.add_argument("--job", default=DEFAULT_JOB)
    parser.add_argument("--repo", type=Path, default=None)
    parser.add_argument("--list", action="store_true", help="명령만 출력하고 실행하지 않는다")
    args = parser.parse_args(argv)

    repo = (args.repo or Path(__file__).resolve().parents[4]).resolve()
    workflow = (args.workflow or (repo / DEFAULT_WORKFLOW)).resolve()

    try:
        commands, actions, services = parse_job(workflow.read_text(encoding="utf-8"), args.job)
    except (OSError, ValueError, yaml.YAMLError) as error:
        print(f"::error::워크플로를 읽지 못했다: {error}", file=sys.stderr)
        return 2

    if not commands:
        # 빈 선언은 통과가 아니다 (CLAUDE.md 규칙 4 ⑶). 명령이 0개면 「전건 초록」은
        # 0건 검사의 다른 이름이다 — 이 저장소가 parity 게이트에서 겪은 형태다.
        print(
            f"::error::`{args.job}` 잡에 `run:` 스텝이 0개다 — 검사 대상 0건인데 "
            "성공으로 끝나는 것을 허용하지 않는다.",
            file=sys.stderr,
        )
        return 2

    if args.list:
        print(render_list(commands, actions, services))
        return 0

    head_start = _git(repo, "rev-parse", "HEAD")
    dirty_start = _dirty_files(repo)
    print(f"저장소: {repo}\n워크플로: {workflow}\n잡: {args.job}")
    print(f"HEAD(시작): {head_start or '(알 수 없음)'}  더러운 파일: {len(dirty_start)}개")
    print(f"실행하지 않는 액션 스텝: {actions or '없음'}")
    print(f"로컬에 없는 CI 서비스: {services or '없음'}")

    results = run_all(commands, repo)
    head_end = _git(repo, "rev-parse", "HEAD")
    dirty_end = _dirty_files(repo)

    print("\n──────── 명령별 결과 (집계 라벨 없음 — 명령이 단위다) ────────")
    for command, code in results:
        mark = "초록" if code == 0 else f"빨강(exit {code})"
        print(f"  [{command.index}] {mark}  $ {command.script}")
    failed = [command for command, code in results if code != 0]

    reasons: list[str] = []
    if head_start != head_end:
        reasons.append(f"HEAD 가 움직였다: {head_start} → {head_end}")
    if dirty_start or dirty_end:
        reasons.append(f"작업 트리가 더럽다: 시작 {len(dirty_start)}개 · 끝 {len(dirty_end)}개")
    if not head_start:
        reasons.append("HEAD 를 읽지 못했다 (git 저장소가 아니다)")

    if failed:
        print(
            f"판정: 미충족 — 명령 {len(failed)}개가 빨갛다 "
            f"({', '.join(str(command.index) for command in failed)})"
        )
        return 1
    if reasons:
        print("판정: 명령은 전건 초록이나 **HEAD 에 결속되지 않았다**")
        for reason in reasons:
            print(f"  - {reason}")
        print(
            "  이 결과는 어느 커밋의 것도 아니다 — 출하 근거로 쓰려면 커밋한 뒤 "
            "그 SHA 에서 다시 돌려라."
        )
        return 3
    print(f"{ALL_GREEN} — 명령 {len(results)}개, HEAD {head_start}, 트리 깨끗")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
