"""**Kotlin 테스트 클래스 정체성 스냅샷** — 라쳇 간격 안의 조용한 삭제를 막는다.

## 왜 이 파일이 따로 있는가

`tests/test_kotlin_gate_reach.py` 가 같은 대상(`TEST_CLASSES`)을 세 축으로 잰다 —
정확 일치 개수(`TEST_CLASS_COUNT`) · 하한 라쳇(`MIN_TEST_CLASSES`) · 바닥 목록
(`FLOOR_TEST_CLASSES`). codex 독립 리뷰(2026-08-21, `xx_harness` 회차)가 **셋을 다 통과하는
삭제 경로**를 지적했고 옳았다:

  * **하한 라쳇**은 「하한 아래로 내려가는 것」만 막는다. 실측 당시 선언 111 · 하한 105 →
    **6 개까지는 조용히 지울 수 있었다.**
  * **정확 일치 개수**는 `.kt` 파일 · `TEST_CLASSES` 항목 · `TEST_CLASS_COUNT` **셋을 함께**
    줄이면 유지된다. 그것이 추가한 것은 「두 번째 diff 자리 + 필수 인간 리뷰」이지
    **자동 차단이 아니다.**
  * **바닥 목록**은 「다른 판정이 근거로 인용하는 탐지기」만 담는다 — 실측 당시 비바닥
    클래스가 82 개였고 그 삭제는 바닥에 걸리지 않는다.
  * 그리고 **규칙 8(라쳇 상환)이 그 간격을 Phase 안에서 자라게 한다** — 하한을 Phase 경계에서만
    올리므로, 그 사이 늘어난 클래스는 다음 경계까지 무보호다.

한때 잡아 준 것은 Gradle 리포트 축 하나뿐이었는데, 그것은 **오래된 빌드 산출물**이라
재빌드하면 조용해진다(그 축은 같은 날 기준선에서 실제로 트리와 어긋나 빨간 상태였다).
**가드가 아니라 우연이었다.**

## 무엇을 하는가 — 정체성 containment

기준 리비전의 `TEST_CLASSES` 를 읽어 **현재 목록이 그것을 전부 포함하는지** 본다.

    baseline ⊆ current

  * **추가는 자유롭게 통과한다** — 그래서 규칙 8(라쳇 상환)과 충돌하지 않는다. Phase 안에서
    이 파일을 고칠 일이 없다.
  * **삭제는 이름으로 걸린다** — 개수를 맞추는 어떤 조합으로도 통과하지 못한다. 세 자리를
    함께 줄여도 사라진 **이름**이 남는다.
  * 기준 저장소는 **git 자신**이다. 스냅샷을 별도 파일로 두면 그 사본이 또 갈릴 자리가 된다
    (이 하네스가 이미 겪은 실패 — 리뷰 파일명·fixture 도메인명 드리프트).

## 갱신 시점

**Phase 경계에서만** [_SNAPSHOT_REV] 를 그 시점 커밋으로 올린다. 클래스를 정당하게 지웠다면
그 커밋으로 올리고 **왜 지웠는지**를 아래 이력에 적는다 — 그 diff 가 "검사 범위를 줄였다"는
신고다. 이것이 규칙 8 이 말하는 상환이고, 방향(내려가지 않음)은 이 containment 가 강제한다.

## 이 장치 자신의 한계 (지우지 마라)

  * **이름이 같은 채 내용이 비는 것**은 못 잡는다 — 클래스는 남고 `@Test` 가 0 개가 되는 경우.
    그 축은 `test_kotlin_gate_reach.py` 의 `MIN_TESTS_IN_FLOOR_CLASS` 와 Gradle 리포트 축이
    담당하고, 바닥 밖 클래스에 대해서는 **여전히 공백**이다. 진짜 답은 변이 테스트(백로그 B-19).
  * **기준 리비전 자신이 틀린 경우**는 못 잡는다. 기준을 올리는 diff 가 사람 리뷰를 받는 것이
    유일한 방어다.
"""

from __future__ import annotations

import ast
import subprocess
from pathlib import Path
from typing import Final

_REPO_ROOT: Final = Path(__file__).resolve().parents[1]
_GATE_REACH_REL: Final = "tests/test_kotlin_gate_reach.py"

#: 클래스 정체성의 **기준 리비전**. Phase 경계에서만 올린다.
#:
#: `ed3df31` (2026-08-21, `guard(harness): 4축 사본의 동기화 강제자`): 이 장치를 세운 시점.
#: 그 리비전의 `TEST_CLASSES` 는 108 개다.
_SNAPSHOT_REV: Final = "ed3df31"


def _read_test_classes(source: str) -> tuple[str, ...]:
    """`TEST_CLASSES` 선언을 AST 로 읽는다. 못 찾으면 빈 튜플.

    `AnnAssign`(`TEST_CLASSES: tuple[str, ...] = (...)`)과 `Assign` 둘 다 받는다 — 한쪽만
    받으면 선언 형태를 바꾸는 편집에서 **조용히 빈 집합**이 되고, 그러면 containment 가
    공집합 ⊆ 무엇이든으로 항상 참이 된다(규칙 4 ⑶).
    """
    for node in ast.parse(source).body:
        if isinstance(node, ast.AnnAssign):
            name = getattr(node.target, "id", None)
        elif isinstance(node, ast.Assign):
            name = getattr(node.targets[0], "id", None) if node.targets else None
        else:
            continue
        if name != "TEST_CLASSES" or node.value is None:
            continue
        if not isinstance(node.value, (ast.Tuple, ast.List)):
            return ()
        return tuple(
            element.value
            for element in node.value.elts
            if isinstance(element, ast.Constant) and isinstance(element.value, str)
        )
    return ()


def _baseline_source() -> str:
    completed = subprocess.run(
        ["git", "show", f"{_SNAPSHOT_REV}:{_GATE_REACH_REL}"],
        cwd=_REPO_ROOT,
        capture_output=True,
        check=False,
    )
    assert completed.returncode == 0, (
        f"기준 리비전 {_SNAPSHOT_REV} 에서 {_GATE_REACH_REL} 를 읽지 못했다.\n"
        f"  stderr: {completed.stderr.decode('utf-8', 'replace').strip()}\n"
        "  얕은 클론이거나 리비전이 사라졌다 — 판정할 수 없으면 **실패**한다.\n"
        "  조용히 건너뛰면 이 검사가 있다는 사실만 남는다(규칙 3 — 도달 0을 의심한다)."
    )
    return completed.stdout.decode("utf-8")


def test_기준_스냅샷과_현재_선언이_모두_비어_있지_않다() -> None:
    """**빈 선언에서 통과하지 않는다** (규칙 4 ⑶).

    이 케이스가 없으면 `_read_test_classes` 가 어느 쪽에서든 빈 튜플을 돌려주는 순간
    containment 가 **항상 참**이 되어 아래 판정이 0건 검사로 바뀐다. 선언 형태를 바꾸는
    편집·파서 회귀·경로 오타가 전부 그 경로다.
    """
    baseline = _read_test_classes(_baseline_source())
    current = _read_test_classes((_REPO_ROOT / _GATE_REACH_REL).read_text(encoding="utf-8"))

    assert baseline, (
        f"기준 리비전 {_SNAPSHOT_REV} 에서 TEST_CLASSES 를 파싱하지 못했다.\n"
        "  선언 형태가 바뀌었다면 _read_test_classes 를 함께 고쳐라."
    )
    assert current, (
        f"현재 {_GATE_REACH_REL} 에서 TEST_CLASSES 를 파싱하지 못했다.\n"
        "  선언이 사라졌거나 형태가 바뀌었다 — 어느 쪽이든 아래 판정이 무력해진다."
    )


def test_기준_스냅샷의_클래스가_하나도_사라지지_않았다() -> None:
    """**정체성 containment** — 개수를 맞추는 어떤 조합으로도 삭제를 숨길 수 없다.

    라쳇(하한)·정확 일치 개수·바닥 목록 셋을 다 통과하는 삭제 경로를 막는 자리다. 근거는
    이 파일 머리 주석에 있다(codex 독립 리뷰 지적, 2026-08-21).
    """
    baseline = set(_read_test_classes(_baseline_source()))
    current = set(_read_test_classes((_REPO_ROOT / _GATE_REACH_REL).read_text(encoding="utf-8")))

    missing = sorted(baseline - current)
    assert not missing, (
        f"기준 스냅샷({_SNAPSHOT_REV})에 있던 테스트 클래스 {len(missing)} 개가 사라졌다:\n"
        + "\n".join(f"    {name}" for name in missing)
        + "\n  개수 축(TEST_CLASS_COUNT·MIN_TEST_CLASSES)은 세 자리를 함께 줄이면 통과한다 —\n"
        "  이 축은 **이름**을 보므로 그 조합으로 통과하지 못한다.\n"
        "  정당하게 지웠다면 _SNAPSHOT_REV 를 올리는 별도의 diff 와 사유가 필요하고,\n"
        "  그 diff 가 「검사 범위를 줄였다」는 신고다."
    )
