"""게이트 러너 `run_gate.sh` 의 **계약**을 상시 회귀로 고정한다 (게이트 16 A / T-G · K16-1 후단).

## 왜 이 파일이 있는가

러너는 게이트 판정의 근거("게이트를 돌렸고 exit 0")를 정직하게 만들려고 생겼는데(게이트 15 X3),
첫 판에는 그 러너의 계약을 고정하는 테스트가 0건이었다. 그래서 세 결함이 아무 데서도
빨개지지 않았다 — 빈/공백 인자가 아무것도 실행하지 않고 exit 0(C, 차단②), argv 재조립으로
인용 경계 소실(D), 러너 밖 파이프가 실패를 다시 삼킴(B). 셋 다 3줄 테스트로 잡혔을 결함이다.
그 다음 판에서도 같은 형태가 하나 더 나왔다 — 인자는 비지 않았는데 확장 후 실행 명령이 0건이면
exit 0(게이트 17 X17-1 zero-work). 여기서는 실제 스크립트를 subprocess 로 불러 그 계약을 못박는다.

## 계약 문장 ↔ 테스트 대응 (게이트 17 자기 감사 — 머리 주석의 문장마다 검사가 있어야 한다)

- 인자는 정확히 하나 — 0개·2개 이상 exit 2
  → `test_no_args_exits_2` · `test_two_args_exit_2`
- argv 나열형은 받지 않는다 / 안쪽 인용 보존
  → `test_argv_nested_form_is_rejected_not_mangled` · `test_single_string_preserves_inner_quoting`
- ⒜ 비었거나 공백뿐이면 exit 2
  → `test_empty_string_arg_exits_2` · `test_whitespace_only_arg_exits_2`
- ⒝ 해석 후 실행 명령 0건이면 exit 2 (주석 전용·백슬래시-개행)
  → `test_comment_only_arg_exits_2` · `test_backslash_newline_only_arg_exits_2`
- ⒝ functrace — 서브셸 안 명령은 zero-work 가 아니다
  → `test_subshell_command_is_work_not_zero_work`
- ⒝ 잔여 — 설정됐지만 빈 값인 변수 하나(`'$V'`, V="")는 못 잡는다 / `${V:?}` 권장
  → `test_LIMIT_set_but_empty_variable_expansion_is_not_caught_rc_is_0`
  · `test_recommended_required_expansion_fails_on_empty_value`
- ⒞ 미설정 변수 참조는 비-0 / `${VAR:-}` 는 통과
  → `test_unset_variable_reference_is_non_zero` · `test_default_expansion_of_unset_var_passes`
- 종료 코드 무가공 전파 · 파이프 안 실패 비-0
  → `test_exit_code_is_propagated_verbatim` · `test_pipe_failure_inside_runner_is_non_zero`
  · `test_passing_pipe_is_zero`
- cmd 전문·exit 가 stdout 에 기록된다 (비밀값 전제의 근거)
  → `test_command_text_and_exit_are_logged_to_stdout`
- 한계 ⑶ 러너 밖 파이프는 못 잡는다 (bash·zsh·sh)
  → `test_LIMIT_pipe_outside_runner_is_not_caught_outer_status_is_0`
- 실행 경로 local · 러너 자신의 CI 배선 0 · 계약 테스트는 ci:quality
  → `test_runner_itself_has_no_ci_wiring_but_this_file_does`
- (테스트 손잡이) 기본 검사 대상은 저장소의 추적 실물이다
  → `test_default_target_is_the_tracked_repo_file`

## 지금 어디서 도는가

`ci:quality` — `.github/workflows/ci.yml` 의 quality 잡이 이 파일을 **경로 명시**로 호출한다
(전체 수집만 있으면 파일을 지워도 exit 0 이라 자기 안의 단언은 파일과 함께 사라진다).

## 음성 대조용 손잡이

`RUN_GATE_PATH` 환경변수로 검사 대상 스크립트를 바꿀 수 있다. 목적은 하나 — 수정 전 판
(`git show <옛 커밋>:...`)에 같은 테스트를 돌려 결함이 실제로 빨개지는지 실측하는 것.
기본값은 저장소의 실물이며, CI 는 기본값으로 돈다(`test_default_target_is_the_tracked_repo_file`).
"""

import hashlib
import os
import shutil
import subprocess
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
_RUNNER_REL = Path(".claude/skills/kotlin-migration/scripts/run_gate.sh")
_DEFAULT_RUNNER = REPO / _RUNNER_REL


def _runner() -> Path:
    override = os.environ.get("RUN_GATE_PATH")
    return Path(override).resolve() if override else _DEFAULT_RUNNER


def _run(*args: str, unset: tuple[str, ...] = ()) -> subprocess.CompletedProcess[str]:
    """러너를 argv 그대로 호출한다 — 셸을 거치지 않으므로 인용은 여기서 안 깨진다."""
    env = {k: v for k, v in os.environ.items() if k not in unset}
    return subprocess.run(
        ["bash", str(_runner()), *args],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
        env=env,
    )


def test_runner_exists_and_parses() -> None:
    """실물이 있고 `bash -n` 을 통과한다 — 셸 린트 0 이던 자리를 최소로 메운다."""
    runner = _runner()
    assert runner.is_file(), runner
    syntax = subprocess.run(
        ["bash", "-n", str(runner)], capture_output=True, text=True, check=False
    )
    assert syntax.returncode == 0, syntax.stderr


def test_default_target_is_the_tracked_repo_file(monkeypatch: pytest.MonkeyPatch) -> None:
    """(T17-7) 손잡이가 없을 때 검사 대상은 저장소의 **추적 실물**이다.

    `RUN_GATE_PATH` 는 음성 대조용이지 기본 경로가 아니다. 이 단언이 없으면 환경에 그 변수가
    남아 있는 채 CI 가 다른 파일을 통과시켜도 아무도 모른다. sha256 은 기본 경로의 바이트와
    추적 상대 경로로 다시 읽은 바이트가 같은지(심볼릭 링크·경로 치환이 없는지)를 잰다.
    """
    monkeypatch.delenv("RUN_GATE_PATH", raising=False)
    target = _runner()
    assert target == _DEFAULT_RUNNER
    assert REPO in target.parents
    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", str(_RUNNER_REL)],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
    )
    assert tracked.returncode == 0, f"추적 파일이 아니다: {tracked.stderr}"
    sha_default = hashlib.sha256(target.read_bytes()).hexdigest()
    sha_tracked_path = hashlib.sha256((REPO / _RUNNER_REL).read_bytes()).hexdigest()
    assert sha_default == sha_tracked_path, (sha_default, sha_tracked_path)


# ── C (T-E, 차단②): 빈 호출은 통과가 아니라 실패다 — ⒜ 원문이 비었거나 공백뿐 ──────────


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


# ── X17-1 (게이트 17 ①): 빈 호출은 통과가 아니라 실패다 — ⒝⒞ 확장 후 명령 0건 ────────


def test_comment_only_arg_exits_2() -> None:
    """`'# only a comment'` — 원문은 비지 않았지만 자식 bash 가 실행할 명령이 0건이다.

    옛 판(d0a5255)은 원문 공백만 검사해 이것을 exit 0 으로 통과시켰다.
    """
    result = _run("# only a comment")
    assert result.returncode == 2, result
    assert "0건" in result.stderr, result.stderr


def test_backslash_newline_only_arg_exits_2() -> None:
    """백슬래시-개행만 — 줄 이음 뒤 남는 명령이 없다. 옛 판 exit 0."""
    result = _run("\\\n")
    assert result.returncode == 2, result
    assert "0건" in result.stderr, result.stderr


def test_LIMIT_set_but_empty_variable_expansion_is_not_caught_rc_is_0() -> None:
    """잔여 문서화 — `'$GATE_CMD'` 인데 값이 **빈 문자열로 설정**된 경우는 못 잡는다(rc 0).

    문법상 단순 명령이라 DEBUG trap 이 발화한 뒤 확장 결과가 비어 0건 실행·rc 0 이 된다.
    이 기제로는 구조적으로 못 본다 — 머리 주석 ⒝ 의 잔여 항목. 이 단언이 어느 날 깨지면
    (rc 가 비-0 이 되면) 러너가 이 잔여를 닫은 것이므로 그때 문서화를 갱신한다. 이름의 LIMIT 이
    그 뜻이다. 미설정은 nounset 이 잡고(아래), 권장 형태 `${V:?}` 는 그 다음 테스트가 고정한다.
    """
    result = subprocess.run(
        ["bash", str(_runner()), "$GATE_CMD"],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
        env={**os.environ, "GATE_CMD": ""},
    )
    assert result.returncode == 0, result
    assert "[run_gate] exit: 0" in result.stdout, result.stdout


def test_recommended_required_expansion_fails_on_empty_value() -> None:
    """머리 주석이 권장한 `${V:?}` — 값이 비면 자식이 비-0 을 낸다. 잔여를 호출 측이 닫는 길."""
    result = subprocess.run(
        ["bash", str(_runner()), "${GATE_CMD:?}"],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
        env={**os.environ, "GATE_CMD": ""},
    )
    assert result.returncode != 0, result


def test_unset_variable_reference_is_non_zero() -> None:
    """`'$GATE_CMD'` 인데 **미설정** — nounset 으로 자식이 오류를 낸다. 옛 판은 빈 확장 → exit 0.

    값은 bash 판에 따라 다르므로(3.2 는 127) 계약은 "비-0" 이다.
    """
    result = _run("$GATE_CMD", unset=("GATE_CMD",))
    assert result.returncode != 0, result
    assert "unbound variable" in result.stderr, result.stderr


def test_default_expansion_of_unset_var_passes() -> None:
    """nounset 이 과하게 닿지 않는다 — `${VAR:-d}` 는 미설정이어도 통과다(머리 주석의 약속)."""
    result = _run('echo "${RUN_GATE_UNSET_PROBE:-d}"', unset=("RUN_GATE_UNSET_PROBE",))
    assert result.returncode == 0, result
    assert "d\n" in result.stdout, result.stdout


def test_subshell_command_is_work_not_zero_work() -> None:
    """functrace 가 없으면 `(echo sub)` 의 명령이 DEBUG trap 에 안 잡혀 zero-work 오탐이 난다(실측).

    이 테스트가 빨개지면 누군가 `-o functrace` 를 뗀 것이다.
    """
    result = _run("(echo sub)")
    assert result.returncode == 0, result
    assert "sub\n" in result.stdout, result.stdout


# ── D (T-F): 계약은 단일 문자열 인자 하나 — argv 나열형은 인용 경계를 잃는다 ─────────


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

    옛 판은 이 입력에서 `<%s>` 를 리다이렉트로 재해석하기까지 했다 — 의도와 다른 명령이 도는데
    종료 코드는 그 엉뚱한 명령의 것이었다. 거부가 정답이다.
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


def test_command_text_and_exit_are_logged_to_stdout() -> None:
    """명령 전문과 종료 코드가 stdout 에 남는다 — 그래서 인자에 비밀값을 넣지 말라는 전제(T)."""
    result = _run("true")
    assert "[run_gate] cmd: true" in result.stdout, result.stdout
    assert "[run_gate] exit: 0" in result.stdout, result.stdout


# ── 자기 도달: 러너 자신은 CI 에서 안 돈다(local) — 이 파일이 ci:quality 로 돈다 ─────────


def test_runner_itself_has_no_ci_wiring_but_this_file_does() -> None:
    """머리 주석 "러너 자신을 부르는 CI 배선은 0 · 계약 테스트는 ci:quality" 를 ci.yml 로 잰다.

    러너가 어느 날 CI 에 배선되면 그건 좋은 일이지만 머리 주석이 낡는다 — 그때 이 테스트가
    그 사실을 드러낸다. 반대로 이 파일의 경로 명시 스텝이 사라지면 "ci:quality" 선언이 거짓이 된다.
    """
    ci = (REPO / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    run_lines = [line for line in ci.splitlines() if line.lstrip().startswith("run:")]
    assert not any("run_gate.sh" in line for line in run_lines), (
        "러너가 CI 에 배선됐다 — 머리 주석 갱신 필요"
    )
    assert any("tests/test_run_gate.py" in line for line in run_lines), (
        "이 파일의 경로 명시 스텝이 없다"
    )


# ── B (K16-1): 러너 밖 파이프는 못 잡는다 — 고친 것이 아니라 한계를 문서화한다 ─────


@pytest.mark.parametrize("shell", ["bash", "sh", "zsh"])
def test_LIMIT_pipe_outside_runner_is_not_caught_outer_status_is_0(shell: str) -> None:
    """상태 ⑶ 문서화 — `run_gate.sh 'false' | tail -1` 은 outer 0 이다. **러너가 못 잡는다.**

    호출 측 셸이 러너보다 먼저 파이프를 만들어 outer 종료 코드는 tail 의 것이 된다.
    이 테스트는 그 사실을 **단언**한다 — 어느 날 이것이 비-0 이 되면 러너 밖의 무언가가
    바뀐 것이므로 그때 이 문서화도 갱신해야 한다. 이름의 LIMIT 이 그 뜻이다.
    원 사고의 셸은 zsh 였으므로(T17-6) bash 만으로는 부족하다 — 없는 셸은 건너뛴다.
    (안쪽 러너는 실패를 봤다 — `[run_gate] exit: 1` 이 stdout 에 있다. 삼킨 것은 밖이다.)
    """
    if shutil.which(shell) is None:
        pytest.skip(f"{shell} 이 이 환경에 없다")
    outer = subprocess.run(
        [shell, "-c", f"bash {_runner()} 'false' | tail -1"],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
    )
    assert outer.returncode == 0, outer
    assert "[run_gate] exit: 1" in outer.stdout, outer.stdout
