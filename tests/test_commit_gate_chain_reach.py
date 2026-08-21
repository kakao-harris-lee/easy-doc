"""**커밋 게이트 사슬의 첫 칸을 pytest 안으로 끌어온다** — `ruff` 를 건너뛴 실패의 처방.

## 무엇이 반복됐나 (실측, 2026-08-21~22)

`CLAUDE.md` 명령 절이 커밋 전 필수 통과를 **`ruff → mypy → pytest`** 로 규정한다. 그런데
하네스 개조 두 회차에서 리더가 **첫 칸을 빼고 뒤 두 칸만 돌린 뒤** 「트리 건강」을 보고했다.

결과가 사소하지 않았다 — `ruff check .` 가 E501 2건으로 빨간 상태였고, `ci.yml` 의 `quality`
잡은 그것을 5번째 스텝으로 돌리며 `continue-on-error` 도 `if: always()` 도 없다. 그래서 그
뒤의 `ruff format --check` · `mypy . .claude` · **경로 명시 pytest 8종** · blanket pytest 가
**전부 도달 0**이었다. 즉 같은 회차에 새로 세운 강제자 셋이 **CI 에서 한 번도 돌지 않았다.**
근거 #6(품질 게이트 CI 도달 0)과 같은 구조의 재발이다.

그리고 **두 회차 연속 같은 칸**이었다 — 1회차 교차 종합의 「충족」도 ruff 를 재지 않았고, 그때
이미 `ruff format --check` 가 빨갰다(F-17).

## 왜 여기서 재는가 — 「돌린 것」과 「돌렸다고 적은 것」의 간격

이 실패의 기제는 도구 부재가 아니다. `ruff` 는 있고 CI 도 그것을 돌린다. 기제는 **사람이 사슬의
일부만 돌리고 전체를 보고할 수 있다**는 것이고, 그 자리에 강제자가 0 이었다(로컬 커밋 전 실행은
규율뿐이고 CI 는 **사후에만** 신고한다).

그래서 사슬을 pytest 안으로 접는다. 리더가 이 세션에서 **한 번도 빠뜨리지 않은 명령**이
`uv run pytest` 였으므로, 그 안에 넣으면 **「pytest 를 돌렸다 ⇒ ruff 도 돌았다」** 가 성립한다.
CI 의 별도 `ruff` 스텝을 대체하지 않는다 — 그쪽이 먼저 죽으면 여전히 뒤가 안 돌므로, 이것은
**로컬·CI 양쪽에서 사슬이 끊긴 것을 pytest 하나로도 알 수 있게** 하는 두 번째 관측면이다.

## 한계 (지우지 마라)

  * **`mypy` 는 여기 넣지 않았다.** 실행이 수십 초라 매 pytest 에 얹으면 스위트가 느려지고,
    느려진 스위트는 사람이 덜 돌린다 — 그 방향이 이 파일이 겨눈 문제를 되살린다. mypy 의
    도달은 `ci.yml` 의 명시 스텝 하나뿐이고 **그 사실을 여기 적어 둔다.**
  * **「보고한 값이 커밋 후 측정인가」는 재지 않는다.** 이 세션에서 세 번 반복된 다른 실패다
    (`F-11` — 커밋이 판정 조건을 바꾸는데 커밋 **전** 값을 보고했다). 그 축의 강제자는 **0**이고,
    이 파일은 그것을 고치지 않는다. 장부에서 지우지 마라.
  * 도구가 없으면 **실패**한다. 조용히 건너뛰지 않는다(규칙 3 — 도달 0을 의심한다).
"""

from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Final

import pytest

_REPO_ROOT: Final = Path(__file__).resolve().parents[1]

#: pytest 안에서 도는 사슬 칸. `(표시 이름, 명령)`.
#:
#: `mypy` 는 의도적으로 빠져 있다 — 이유는 파일 머리 주석의 「한계」 첫 항목이다.
_CHAIN: Final = (
    ("ruff check", ("uv", "run", "ruff", "check", ".")),
    ("ruff format --check", ("uv", "run", "ruff", "format", "--check", ".")),
)


@pytest.mark.parametrize(("label", "command"), _CHAIN, ids=[label for label, _ in _CHAIN])
def test_커밋_게이트_사슬이_pytest_안에서_돈다(label: str, command: tuple[str, ...]) -> None:
    """`ruff` 를 빼고 pytest 만 돌리는 일이 **구조적으로 불가능**해진다.

    실패 메시지에 위반 목록을 그대로 실어, 이 테스트만 보고 고칠 수 있게 한다.
    """
    completed = subprocess.run(command, cwd=_REPO_ROOT, capture_output=True, check=False)
    stdout = completed.stdout.decode("utf-8", "replace").strip()
    stderr = completed.stderr.decode("utf-8", "replace").strip()

    assert completed.returncode == 0, (
        f"`{' '.join(command)}` 가 EXIT={completed.returncode} 로 끝났다 — 커밋 게이트 사슬의\n"
        f"  `{label}` 칸이 빨갛다.\n\n"
        f"{stdout or stderr}\n\n"
        "  이 칸이 빨간 상태로 CI 에 올라가면 `quality` 잡이 여기서 죽고,\n"
        "  그 뒤의 mypy·경로 명시 pytest 8종·blanket pytest 가 **전부 도달 0**이 된다.\n"
        "  실제로 그 일이 2026-08-21 에 일어났다 — 그 회차에 세운 강제자 셋이\n"
        "  CI 에서 한 번도 돌지 않았다."
    )


def test_사슬_선언이_비어_있지_않다() -> None:
    """**빈 선언에서 통과하지 않는다** (규칙 4 ⑶).

    [_CHAIN] 을 비우면 위 `parametrize` 가 케이스 0 개가 되어 **그 테스트가 아예 돌지 않는다.**
    이 저장소가 이미 두 번 실측한 형태다(parity 게이트 도메인 0개 → exit 0, 표 판정기 대상
    0개 → 위반 0건). 그래서 개수를 밖에서 되짚는다.
    """
    assert _CHAIN, "사슬 선언이 비었다 — 위 parametrize 가 0 건 검사가 된다."
    assert all(label and command for label, command in _CHAIN), (
        "사슬 항목에 빈 이름이나 빈 명령이 있다."
    )
