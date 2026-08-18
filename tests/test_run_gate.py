"""게이트 러너 `run_gate.sh` 의 **계약**을 상시 회귀로 고정한다 (게이트 16 A / T-G · K16-1 후단).

## 왜 이 파일이 있는가

러너는 게이트 판정의 근거("게이트를 돌렸고 exit 0")를 정직하게 만들려고 생겼는데(게이트 15 X3),
첫 판에는 그 러너의 계약을 고정하는 테스트가 0건이었다. 그래서 세 결함이 아무 데서도
빨개지지 않았다 — 빈/공백 인자가 아무것도 실행하지 않고 exit 0(C, 차단②), argv 재조립으로
인용 경계 소실(D), 러너 밖 파이프가 실패를 다시 삼킴(B). 셋 다 3줄 테스트로 잡혔을 결함이다.
여기서는 실제 스크립트를 subprocess 로 불러 그 계약을 못박는다.

## 지금 어디서 도는가

`ci:quality` — `.github/workflows/ci.yml` 의 quality 잡이 이 파일을 **경로 명시**로 호출한다
(전체 수집만 있으면 파일을 지워도 exit 0 이라 자기 안의 단언은 파일과 함께 사라진다).

## 음성 대조용 손잡이

`RUN_GATE_PATH` 환경변수로 검사 대상 스크립트를 바꿀 수 있다. 목적은 하나 — 수정 전 판
(`git show 65a7eb6:...`)에 같은 테스트를 돌려 C·D 가 실제로 빨개지는지 실측하는 것.
기본값은 저장소의 실물이며, CI 는 기본값으로 돈다.
"""

import os
import subprocess
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
_DEFAULT_RUNNER = REPO / ".claude/skills/kotlin-migration/scripts/run_gate.sh"


def _runner() -> Path:
    override = os.environ.get("RUN_GATE_PATH")
    return Path(override).resolve() if override else _DEFAULT_RUNNER


def _run(*args: str) -> subprocess.CompletedProcess[str]:
    """러너를 argv 그대로 호출한다 — 셸을 거치지 않으므로 인용은 여기서 안 깨진다."""
    return subprocess.run(
        ["bash", str(_runner()), *args],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
    )


def test_runner_exists_and_parses() -> None:
    """실물이 있고 `bash -n` 을 통과한다 — 셸 린트 0 이던 자리를 최소로 메운다."""
    runner = _runner()
    assert runner.is_file(), runner
    syntax = subprocess.run(
        ["bash", "-n", str(runner)], capture_output=True, text=True, check=False
    )
    assert syntax.returncode == 0, syntax.stderr


# ── C (T-E, 차단②): 빈 호출은 통과가 아니라 실패다 ─────────────────────────────


def test_no_args_exits_2() -> None:
    result = _run()
    assert result.returncode == 2, result
    assert "[run_gate]" in result.stderr


def test_empty_string_arg_exits_2() -> None:
    """⑴ `run_gate.sh ""` — 옛 판은 `$#` 만 봐서 아무것도 안 돌리고 exit 0 이었다."""
    result = _run("")
    assert result.returncode == 2, result
    assert "[run_gate] cmd:" not in result.stdout, "빈 명령을 실행 단계까지 보냈다"


def test_whitespace_only_arg_exits_2() -> None:
    """⑵ `run_gate.sh "   "` — 공백뿐인 내용도 빈 호출과 같은 취급이다."""
    result = _run(" \t \n ")
    assert result.returncode == 2, result
    assert "[run_gate] cmd:" not in result.stdout, "공백 명령을 실행 단계까지 보냈다"


# ── D (T-F): 계약은 단일 문자열 인자 하나 — argv 나열형은 인용 경계를 잃는다 ─────


def test_two_args_exit_2() -> None:
    """⑶ 인자 2개 — 옛 판은 `$*` 로 이어 붙여 재파싱했다. 이제는 거부한다."""
    result = _run("echo", "ok")
    assert result.returncode == 2, result
    assert "[run_gate] cmd:" not in result.stdout


def test_single_string_preserves_inner_quoting() -> None:
    """⑹-a 인용 경계 보존 — `-k 'a or b'` 가 한 덩어리로 명령에 닿는다.

    `printf '<%s>'` 로 각 argv 를 꺾쇠로 감싸 관찰한다: 보존되면 `<-k><a or b>`,
    재파싱으로 깨지면 `<-k><a><or><b>` 가 나온다.
    """
    result = _run("printf '<%s>' -k 'a or b'")
    assert result.returncode == 0, result
    assert "<-k><a or b>" in result.stdout, result.stdout
    assert "<a><or><b>" not in result.stdout, result.stdout


def test_argv_nested_form_is_rejected_not_mangled() -> None:
    """⑹-b 옛 사용례(argv 나열형)를 그대로 주면 **재파싱해 실행하지 않고** exit 2 로 거부한다.

    옛 판은 이 입력에서 `<-k><a><or><b>` 를 찍고 exit 0 이었다 — 의도와 다른 명령이 도는데
    초록이었다. 거부가 정답이다.
    """
    result = _run("printf", "<%s>", "-k", "a or b")
    assert result.returncode == 2, result
    assert "<a><or><b>" not in result.stdout, "인용이 깨진 명령이 실제로 실행됐다"


# ── 본래 목적: 파이프 안 실패가 비-0 으로 전파된다 (게이트 15 X3) ─────────────────


def test_pipe_failure_inside_runner_is_non_zero() -> None:
    """⑷ `'false | tail -1'` — 마지막 명령(tail)이 성공해도 pipefail 로 비-0 이다."""
    result = _run("false | tail -1")
    assert result.returncode != 0, result
    assert "[run_gate] exit: 1" in result.stdout, result.stdout


def test_passing_pipe_is_zero() -> None:
    """⑸ `'echo ok | tail -1'` — 통과 명령을 오탐하지 않는다."""
    result = _run("echo ok | tail -1")
    assert result.returncode == 0, result
    assert "[run_gate] exit: 0" in result.stdout, result.stdout


def test_exit_code_is_propagated_verbatim() -> None:
    """종료 코드는 가공 없이 전파된다 — 4 는 4 로."""
    result = _run("exit 4")
    assert result.returncode == 4, result
    assert "[run_gate] exit: 4" in result.stdout


def test_command_text_is_logged_to_stdout() -> None:
    """실행한 명령 전문이 stdout 에 남는다 — 그래서 인자에 비밀값을 넣지 말라는 전제가 붙는다(T)."""
    result = _run("true")
    assert "[run_gate] cmd: true" in result.stdout, result.stdout


# ── B (K16-1): 러너 밖 파이프는 못 잡는다 — 고친 것이 아니라 한계를 문서화한다 ─────


@pytest.mark.parametrize("shell", ["bash"])
def test_LIMIT_pipe_outside_runner_is_not_caught_outer_status_is_0(shell: str) -> None:
    """상태 ⑶ 문서화 — `run_gate.sh 'false' | tail -1` 은 outer 0 이다. **러너가 못 잡는다.**

    호출 측 셸이 러너보다 먼저 파이프를 만들어 outer 종료 코드는 tail 의 것이 된다.
    이 테스트는 그 사실을 **단언**한다 — 어느 날 이것이 비-0 이 되면 러너 밖의 무언가가
    바뀐 것이므로 그때 이 문서화도 갱신해야 한다. 이름의 LIMIT 이 그 뜻이다.
    (안쪽 러너는 실패를 봤다 — `[run_gate] exit: 1` 이 stdout 에 있다. 삼킨 것은 밖이다.)
    """
    outer = subprocess.run(
        [shell, "-c", f"bash {_runner()} 'false' | tail -1"],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
    )
    assert outer.returncode == 0, outer
    assert "[run_gate] exit: 1" in outer.stdout, outer.stdout
