"""게이트 러너 `run_gate.sh` 의 **계약**을 상시 회귀로 고정한다 (게이트 16 A / T-G · K16-1 후단).

## 왜 이 파일이 있는가

러너는 게이트 판정의 근거("게이트를 돌렸고 exit 0")를 정직하게 만들려고 생겼는데(게이트 15 X3),
첫 판에는 그 러너의 계약을 고정하는 테스트가 0건이었다. 그래서 세 결함이 아무 데서도
빨개지지 않았다 — 빈/공백 인자가 아무것도 실행하지 않고 exit 0(C, 차단②), argv 재조립으로
인용 경계 소실(D), 러너 밖 파이프가 실패를 다시 삼킴(B). 셋 다 3줄 테스트로 잡혔을 결함이다.
그 다음 판에서도 같은 형태가 하나 더 나왔다 — 인자는 비지 않았는데 어떤 명령도 실행 단계에
들어가지 않으면 exit 0(게이트 17 X17-1). 여기서는 실제 스크립트를 subprocess 로 불러 그 계약을
못박는다.

## 계약 문장 ↔ 테스트 대응 (게이트 17 자기 감사 — 머리 주석의 문장마다 검사가 있어야 한다)

- 인자는 정확히 하나 — 0개·2개 이상 exit 2
  → `test_no_args_exits_2` · `test_two_args_exit_2`
- argv 나열형은 받지 않는다 / 안쪽 인용 보존
  → `test_argv_nested_form_is_rejected_not_mangled` · `test_single_string_preserves_inner_quoting`
- ⒜ 비었거나 공백뿐이면 exit 2
  → `test_empty_string_arg_exits_2` · `test_whitespace_only_arg_exits_2`
- ⒝ 어떤 명령도 실행 단계에 안 들어가면 exit 2 (주석 전용·백슬래시-개행 — 잡는 두 형태)
  → `test_comment_only_arg_exits_2` · `test_backslash_newline_only_arg_exits_2`
- ⒝ functrace — 서브셸 안 명령은 진입으로 센다 / 명령 치환은 functrace 와 무관하게 진입
  → `test_subshell_command_is_work_not_zero_work`
  · `test_LIMIT_enters_execution_but_zero_external_work_rc_is_0[$(echo)]`
- ⒝ 잔여 (가) 진입은 하지만 외부 작업 0 — 종류의 대표 입력마다 rc 0 을 단언 (못 잡는다)
  → `test_LIMIT_enters_execution_but_zero_external_work_rc_is_0`
- ⒝ 잔여 (나) 호출자가 계약을 능동적으로 무력화 — 대표 입력마다 rc 0 을 단언 (못 잡는다)
  → `test_LIMIT_caller_neutralizes_contract_rc_is_0` · `test_LIMIT_bash_env_preempts_marker_rc_is_0`
- ⒝ `${V:?}` 권장 — 잔여 (가) 의 `'$V'`(V="") 를 호출 측이 닫는 길
  → `test_recommended_required_expansion_fails_on_empty_value`
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

## 지금 어디서 도는가

`ci:quality` — `.github/workflows/ci.yml` 의 quality 잡이 이 파일을 **경로 명시**로 호출한다
(전체 수집만 있으면 파일을 지워도 exit 0 이라 자기 안의 단언은 파일과 함께 사라진다).

## 검사 대상은 추적 실물뿐 — 손잡이 없음 (게이트 18 X4 리더 판정)

모든 테스트는 이 저장소의 추적 실물 `_RUNNER` 를 겨눈다. 환경변수로 대상을 바꾸는 손잡이는
없다 — 있던 `RUN_GATE_PATH` 는 추적 실물이 망가진 채로도 온전한 사본을 가리켜 전건 초록을
만들 수 있었다(codex X18-4, cross 제3 근거로 재현). 옛 판 음성 대조가 필요하면 **옛 커밋의
일회용 `git worktree` 안에 이 파일을 두고 그 안에서 pytest 를 돌려라** — 그러면 그 worktree 의
추적 실물이 대상이 된다. 프로세스를 띄우는 헬퍼 `_invoke` 는 경로를 명시 인자로 받지만, 이
파일 안의 호출은 전부 `_RUNNER` 다.
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

REPO = Path(__file__).resolve().parents[1]
_RUNNER_REL = Path(".claude/skills/kotlin-migration/scripts/run_gate.sh")
_RUNNER = REPO / _RUNNER_REL


def _invoke(
    runner: Path, *args: str, env: dict[str, str] | None = None
) -> subprocess.CompletedProcess[str]:
    """`runner` 를 argv 그대로 호출한다 — 셸을 거치지 않으므로 인용은 여기서 안 깨진다.

    경로는 명시 인자다(환경변수 아님). 이 파일의 테스트는 전부 `_RUNNER` 를 넘긴다.
    """
    return subprocess.run(
        ["bash", str(runner), *args],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
        env=os.environ.copy() if env is None else env,
    )


def _run(*args: str, unset: tuple[str, ...] = ()) -> subprocess.CompletedProcess[str]:
    """추적 실물 러너를 부른다. `unset` 의 변수는 자식 환경에서 뺀다."""
    env = {k: v for k, v in os.environ.items() if k not in unset}
    return _invoke(_RUNNER, *args, env=env)


def test_runner_exists_tracked_and_parses() -> None:
    """실물이 있고 git 추적 파일이며 `bash -n` 을 통과한다 — 셸 린트 0 이던 자리를 최소로 메운다."""
    assert _RUNNER.is_file(), _RUNNER
    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", str(_RUNNER_REL)],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
    )
    assert tracked.returncode == 0, f"추적 파일이 아니다: {tracked.stderr}"
    syntax = subprocess.run(
        ["bash", "-n", str(_RUNNER)], capture_output=True, text=True, check=False
    )
    assert syntax.returncode == 0, syntax.stderr


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
    assert "실행 단계에 들어가지 않았다" in result.stderr, result.stderr


def test_backslash_newline_only_arg_exits_2() -> None:
    """백슬래시-개행만 — 줄 이음 뒤 남는 명령이 없다. 옛 판 exit 0."""
    result = _run("\\\n")
    assert result.returncode == 2, result
    assert "실행 단계에 들어가지 않았다" in result.stderr, result.stderr


#: 잔여 (가) — "실행 단계에는 진입하지만 외부 작업이 0" 인 종류의 대표 입력. 마커가 재는 것은
#: 진입이지 작업이 아니라서 전부 rc 0 이다(게이트 18 X3 · 마커 행렬 15종의 rc 0 부분집합).
#: 열거는 종류의 **대표**이지 잔여의 전부가 아니다 — 같은 종류의 다른 형태도 같은 결과다.
_KIND_A_ENTERS_BUT_ZERO_WORK: tuple[tuple[str, str, dict[str, str]], ...] = (
    ("$(echo)", "빈 명령 치환", {}),
    ("`true`", "백틱 치환", {}),
    ("eval ''", "빈 eval", {}),
    (":", "null 명령", {}),
    ("if false; then echo x; fi", "몸체가 안 도는 if", {}),
    ("X=1", "대입만", {}),
    ('bash -c ""', "빈 자식 셸", {}),
    ("$GATE_CMD", "값이 빈 문자열로 설정된 변수만", {"GATE_CMD": ""}),
    (": <<EOF\nignored\nEOF", "here-doc 을 : 에 먹임", {}),
)


@pytest.mark.parametrize(
    ("cmd", "label", "env_extra"),
    _KIND_A_ENTERS_BUT_ZERO_WORK,
    ids=[cmd for cmd, _label, _env in _KIND_A_ENTERS_BUT_ZERO_WORK],
)
def test_LIMIT_enters_execution_but_zero_external_work_rc_is_0(
    cmd: str, label: str, env_extra: dict[str, str]
) -> None:
    """잔여 (가) 문서화 — 실행 단계에 진입만 하고 외부 작업이 0인 명령은 **못 잡는다**(rc 0).

    DEBUG trap 은 "명령이 실행 단계에 들어갔다"에 발화하고, 그 뒤 실제로 무엇을 했는지는
    묻지 않는다. 이 종류를 잡으려면 다른 기제가 필요하고, 그 기제는 세 회차 연속 자기
    빈자리를 낳았으므로 만들지 않는다(리더 제약). 이 단언이 어느 날 깨지면(rc 비-0) 러너가
    이 종류를 닫은 것이므로 그때 문서화를 갱신한다. 이름의 LIMIT 이 그 뜻이다.
    """
    result = _invoke(_RUNNER, cmd, env={**os.environ, **env_extra})
    assert result.returncode == 0, (label, result)
    assert "[run_gate] exit: 0" in result.stdout, (label, result.stdout)


#: 잔여 (나) — "호출자가 러너 계약을 능동적으로 무력화" 하는 종류의 대표 입력. 러너는 협조하는
#: 호출자를 위한 장치이지 적대적 호출자를 막는 장치가 아니다 — 전부 rc 0.
_KIND_B_CALLER_NEUTRALIZES: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("trap - DEBUG", "마커 trap 을 앞세워 해제(해제 명령 자체가 진입으로 찍힌다)", ()),
    (
        'set +u; echo "$RUN_GATE_UNSET_PROBE"',
        "nounset 해제 뒤 미설정 참조 — 옛 동작으로 되돌림",
        ("RUN_GATE_UNSET_PROBE",),
    ),
)


@pytest.mark.parametrize(
    ("cmd", "label", "unset"),
    _KIND_B_CALLER_NEUTRALIZES,
    ids=[cmd.split(";")[0] for cmd, _label, _unset in _KIND_B_CALLER_NEUTRALIZES],
)
def test_LIMIT_caller_neutralizes_contract_rc_is_0(
    cmd: str, label: str, unset: tuple[str, ...]
) -> None:
    """잔여 (나) 문서화 — 호출자가 계약(마커 trap·nounset)을 스스로 끄면 **못 잡는다**(rc 0)."""
    result = _run(cmd, unset=unset)
    assert result.returncode == 0, (label, result)
    assert "[run_gate] exit: 0" in result.stdout, (label, result.stdout)


def test_LIMIT_bash_env_preempts_marker_rc_is_0(tmp_path: Path) -> None:
    """잔여 (나) 문서화 — `BASH_ENV` 가 자식 기동 시 마커를 선점 기록하면 주석 전용 입력이 rc 0.

    (게이트 18 X5 · cross §1.1 ⑺ 완주 확인.) BASH_ENV 는 preamble 보다 먼저 source 되고
    `RUN_GATE_MARKER` 가 보인다. outer bash 도 같은 파일을 읽지만 그때는 마커 변수가 없어
    가드에 걸린다(Y-1 — 가드 없이 쓰면 stderr 에 흔적이 남는다). 대조군: BASH_ENV 없이는 rc 2.
    """
    hijack = tmp_path / "bash_env.sh"
    hijack.write_text(
        '[ -n "${RUN_GATE_MARKER:-}" ] && echo hijack >> "$RUN_GATE_MARKER"\n', encoding="utf-8"
    )
    control = _run("# only a comment")
    assert control.returncode == 2, control
    hijacked = _invoke(_RUNNER, "# only a comment", env={**os.environ, "BASH_ENV": str(hijack)})
    assert hijacked.returncode == 0, hijacked
    assert "[run_gate] exit: 0" in hijacked.stdout, hijacked.stdout


def test_recommended_required_expansion_fails_on_empty_value() -> None:
    """머리 주석이 권장한 `${V:?}` — 값이 비면 자식이 비-0. 잔여 (가) 를 호출 측이 닫는 길."""
    result = _invoke(_RUNNER, "${GATE_CMD:?}", env={**os.environ, "GATE_CMD": ""})
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
    """functrace 가 없으면 `(echo sub)` 의 명령이 DEBUG trap 에 안 잡혀 오탐(rc 2)이 난다(실측).

    서브셸만 그렇다 — 명령 치환 `$(…)` 은 functrace 유무와 무관하게 부모 단순 명령이 발화한다
    (게이트 18 R18-1 실측; 위 LIMIT (가) 의 `$(echo)` 가 그 형태). 이 테스트가 빨개지면 누군가
    `-o functrace` 를 뗀 것이다.
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

    문자열 grep 이 아니라 YAML 파싱이다(게이트 18 T2 — `run:` 로 시작하는 줄만 보던 옛 판은
    `- run:` 12줄과 블록 스칼라(`run: |`) 본문에 도달하지 못해, 블록 안에 러너를 심어도 통과했다).
    옆 `tests/test_harness_scope_reach.py` 의 `read_ci_job_names` 와 같은 방식 — 모든 잡의
    모든 스텝에서 `run` 값을 통째로 읽는다.
    """
    document = yaml.safe_load((REPO / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    assert isinstance(document, dict), "ci.yml 최상위가 매핑이 아니다"
    jobs = document.get("jobs")
    assert isinstance(jobs, dict) and jobs, "ci.yml 에 jobs 가 없다"
    run_values: list[str] = []
    for job in jobs.values():
        assert isinstance(job, dict), job
        for step in job.get("steps", []):
            assert isinstance(step, dict), step
            run = step.get("run")
            if run is not None:
                run_values.append(str(run))
    assert run_values, "run 스텝이 하나도 없다 — 워크플로 파일이 아니거나 파싱이 깨졌다"
    assert not any("run_gate.sh" in value for value in run_values), (
        "러너가 CI 에 배선됐다 — 머리 주석 갱신 필요"
    )
    assert any("tests/test_run_gate.py" in value for value in run_values), (
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
        [shell, "-c", f"bash {_RUNNER} 'false' | tail -1"],
        capture_output=True,
        text=True,
        check=False,
        cwd=REPO,
    )
    assert outer.returncode == 0, outer
    assert "[run_gate] exit: 1" in outer.stdout, outer.stdout
