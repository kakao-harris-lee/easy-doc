"""**리뷰 커버리지 강제자** — 「모든 변경은 정확히 한 번 리뷰를 받는다」를 처음으로 **잰다**.

## 왜 이 파일이 있는가

`.claude/skills/kotlin-migration/SKILL.md` 리뷰 게이트 절이 *"모든 변경은 여전히 정확히 한 번
리뷰를 받는다"* 고 선언하는데, 그 선언을 재는 장치는 **0 개**였다. codex 독립 리뷰
(2026-08-21, `xx_harness` 회차) 지적 high #2 전문:

> 변경을 축 밖으로 판정한 뒤 장부에도 쓰지 않으면 해당 커밋은 이후 묶음의 시작 SHA와 리뷰
> 대상에서 **영구히** 빠질 수 있으며, '모든 변경은 정확히 한 번 리뷰'라는 핵심 보장은
> **리더의 기억에만** 의존한다.

원장의 「리뷰 이연 장부」 절은 그 공백을 **적어만** 두고 있었다. 적는 것은 정직하지만
탐지가 아니다 — 이 파일이 그 적힌 것을 읽어 **커밋 전수와 대조**한다.

## 왜 기록 위치가 심판문이 아니라 원장인가

codex 권고는 「각 리뷰 산출물에 시작·종료 SHA 를 기록」이었으나 위치를 **원장으로 바꿨다**
(리더 판정, 2026-08-22). `docs/migration/_workspace/reviews/**` 는 **심판문**이고 규약상
**조치 레인(= 리뷰 대상 변경의 저자)이 편집하지 않는다.** 저자가 심판문에 커버리지 헤더를
써 넣으면 그 파일이 저자에게 열린다. 원장은 리더 소관이고 이미
`tests/test_harness_scope_reach.py` 의 판정 범위 안이다. 강제력은 같다.

## 무엇을 하는가

기준 커밋 이후의 모든 커밋이 아래 셋 중 **적어도 하나**에 들어가는지 본다. 어디에도 들지
않는 커밋이 하나라도 있으면 실패한다.

1. **리뷰됨** — 원장 「리뷰 커버리지」 절의 어느 범위(`a..b`)에 든다. `git rev-list` 로 펼쳐 본다.
2. **장부에 적힘** — 원장 「리뷰 이연 장부」 절에 그 커밋의 SHA 토큰이 있다(짧은 SHA 허용).
3. **면제** — 바꾼 경로가 **전부** `docs/` 아래다.

**「정확히 하나」가 아니라 「적어도 하나」인 이유.** 배타성을 강제하면 *리뷰까지 받은* 문서
전용 커밋이 「리뷰됨 + 면제」로 겹쳐 실패한다 — 실제로 이 장치의 첫 입력에서 `cbf6e8d`
(심판 산출물 3건 보존)가 그 형태였다. 그 실패는 리뷰를 **더** 요구하는 방향이 아니라
커버리지 표에서 커밋을 **빼도록** 압박하는 방향이라, 틀리는 방향이 거꾸로다. 그래서
판정은 위 순서대로 하고 **분류 불가만 실패**로 본다.

**면제는 보수적으로 판정한다.** 「주석·오타」와 「포매팅 전용」은 기계로 가릴 수 없으므로
면제로 세지 않는다. 바꾼 경로가 0 건으로 보이는 커밋(예: 병합)도 면제가 아니다 —
`all([])` 은 참이라 그냥 두면 병합 커밋이 조용히 빠진다. 틀리는 방향은 항상
**「리뷰를 더 요구한다」** 쪽이어야 한다.

## 이 장치 자신의 한계 (지우지 마라)

  * **범위가 실제로 리뷰됐는지는 재지 않는다.** 원장에 적으면 리뷰된 것으로 센다. 산출물
    파일의 실재는 방증일 뿐 증명이 아니다 — `xx_harness` 회차가 그 반례다(산출물 3건이
    있는데 codex 원문은 0 줄이었다).
  * **면제 판정이 거칠다.** `docs/` 밖 한 줄이면 비면제이고, 반대로 `docs/` 안에서 규약을
    바꾸는 변경(원장의 판정 표 자체)도 면제로 센다.
  * **`대기`(필수 축인데 아직 미리뷰)와 `이연`(비필수라 묶음)을 구분하지 않는다.** 둘 다
    「적혔다」로 센다. 그 구분은 장부의 `상태` 칸이 사람에게 보여 주는 것이고, 이 장치는
    적혔는지만 본다.
"""

from __future__ import annotations

import os
import re
import subprocess
import warnings
from pathlib import Path
from typing import Final, NamedTuple

_REPO_ROOT: Final = Path(__file__).resolve().parents[1]

#: 원장은 두 파일이다(2026-08-21 2단 분리). 두 절 중 하나가 아카이브로 옮겨져도 판정이
#: 조용히 줄지 않게 **합본으로 읽는다** — `test_harness_scope_reach.py` 의
#: `read_progress_markdown` 과 같은 규약이다.
_PROGRESS_PATHS: Final = (
    _REPO_ROOT / "docs" / "migration" / "_workspace" / "00_progress.md",
    _REPO_ROOT / "docs" / "migration" / "_workspace" / "00_progress-archive.md",
)

_COVERAGE_HEADING: Final = "## 리뷰 커버리지"
_LEDGER_HEADING: Final = "## 리뷰 이연 장부"

#: 판정 시작점. `0d632f9` (2026-08-21) = 이연 장부가 생긴 커밋이다.
#:
#: **그 앞 500 여 커밋은 소급하지 않는다.** 장부도 커버리지 표도 없던 구간을 판정하면 이
#: 검사는 첫날부터 빨간 채로 태어나고, 늘 빨간 검사는 아무도 읽지 않아 탐지 능력이 0 이 된다.
#: 이 상수를 올리는 것은 **판정 범위를 줄이는 것**이므로 그 diff 는 사람 리뷰를 받아야 한다.
_BASELINE_REV: Final = "0d632f9"

#: 커버리지 표의 범위 표기. 백틱 안만 읽는다 — 산문의 SHA 언급을 범위로 오독하지 않기 위해.
_RANGE_IN_BACKTICKS: Final = re.compile(r"`([0-9a-f]{7,40})\.\.([0-9a-f]{7,40})`")

#: 장부의 SHA 표기. 같은 이유로 백틱 안만 읽는다.
_SHA_IN_BACKTICKS: Final = re.compile(r"`([0-9a-f]{7,40})`")

_EXEMPT_PREFIX: Final = "docs/"


def _git(*args: str) -> str:
    """`git` 을 돌려 stdout 을 준다. **실패는 조용히 넘기지 않는다.**"""
    completed = subprocess.run(
        ["git", *args],
        cwd=_REPO_ROOT,
        capture_output=True,
        check=False,
    )
    assert completed.returncode == 0, (
        f"`git {' '.join(args)}` 가 실패했다 (exit {completed.returncode}).\n"
        f"  stderr: {completed.stderr.decode('utf-8', 'replace').strip()}\n"
        "  얕은 클론이거나 리비전이 사라졌다 — **판정할 수 없으면 실패한다**.\n"
        "  건너뛰면 이 검사가 있다는 사실만 남는다(SKILL.md 규칙 3 — 도달 0을 의심한다)."
    )
    return completed.stdout.decode("utf-8")


def _ledger_text() -> str:
    """원장 현행 + 역사를 이어 붙인다. 없는 파일은 건너뛴다(실재는 테스트가 따로 단언한다)."""
    return "\n\n".join(
        path.read_text(encoding="utf-8") for path in _PROGRESS_PATHS if path.exists()
    )


def _section(heading: str) -> str | None:
    """원장 합본에서 `heading` 으로 시작하는 절의 본문을 잘라 낸다. 없으면 `None`."""
    lines = _ledger_text().splitlines()
    for index, line in enumerate(lines):
        if line.startswith(heading):
            body = lines[index + 1 :]
            end = next(
                (offset for offset, row in enumerate(body) if row.startswith("## ")),
                len(body),
            )
            return "\n".join(body[:end])
    return None


#: 커버리지 표의 열 수(`회차 · 범위 · 산출물`).
_COVERAGE_COLUMNS: Final = 3

#: 커버리지 행에서 읽는 칸의 자리.
_COVERAGE_ROUND_CELL: Final = 0
_COVERAGE_RANGE_CELL: Final = 1
_COVERAGE_ARTIFACT_CELL: Final = 2

#: 커버리지 머리행이 **자리마다** 이고 있어야 하는 열 이름.
_COVERAGE_HEADER_CELLS: Final = {
    _COVERAGE_ROUND_CELL: "회차",
    _COVERAGE_RANGE_CELL: "범위",
    _COVERAGE_ARTIFACT_CELL: "산출물",
}

#: 산출물 칸이 쓰는 상대 경로의 기준 디렉터리(저장소 루트 기준).
_WORKSPACE_REL: Final = "docs/migration/_workspace"

#: 산출물 경로가 반드시 시작해야 하는 접두. 심판문은 여기에만 산다.
_ARTIFACT_PREFIX: Final = "reviews/"

#: 회차가 반드시 선언해야 하는 **역할**. 개수 하한(`>= 2`)을 대체한다 (X-2b).
#:
#: 개수는 **구성을 보지 않는다** — `migration-reviewer` + `cross` 두 건으로도 2 를 채우고,
#: 그것은 codex 독립 레인 없이 완주했다고 서명하는 것이다. 게이트의 값어치는 저작자와
#: 심판자가 다른 모델 계열이라는 데서 오므로, 그 레인의 산출물과 교차 종합을 **이름으로**
#: 요구한다. `migration-reviewer` 를 함께 요구하지 않는 이유는 근거를 넘지 않기 위해서다 —
#: 교차 종합(`cross`)은 정의상 두 레인을 입력으로 받으므로 그 자리를 이미 지고 있다.
_REQUIRED_ROLES: Final = frozenset({"codex-reviewer", "cross"})

#: 산출물 칸의 백틱 토큰. 중괄호 묶음(`{a,b,c}`)이 원장의 실제 표기다.
_ARTIFACT_TOKEN: Final = re.compile(r"`([^`]+)`")

#: 중괄호 묶음 하나. 가장 왼쪽 것부터 펼친다.
_BRACE_GROUP: Final = re.compile(r"\{([^{}]*)\}")


def _expand_braces(token: str) -> tuple[str, ...]:
    """`a_{x,y}.md` 를 `a_x.md` · `a_y.md` 로 펼친다. 묶음이 없으면 그대로 한 건."""
    match = _BRACE_GROUP.search(token)
    if match is None:
        return (token,)
    head, tail = token[: match.start()], token[match.end() :]
    expanded: list[str] = []
    for choice in match.group(1).split(","):
        expanded.extend(_expand_braces(f"{head}{choice.strip()}{tail}"))
    return tuple(expanded)


def _declared_artifacts(cell: str, stem: str) -> tuple[str, ...]:
    """산출물 칸이 **선언한** 경로들. 규약을 어긴 경로가 하나라도 있으면 빈 튜플.

    ## 왜 glob 이 아니라 선언된 칸에서 읽는가 (X-2a, 2026-08-23)

    종전 판은 `회차` 칸만 읽어 `reviews/<회차>_*.md` 를 **glob 으로 세고** 2건 이상이면
    행을 유효로 봤다. 그러면 **산출물 칸에 무엇을 적든 판정에 쓰이지 않는다** — 칸이
    문자 그대로 `없음` 이어도, 다른 회차의 산출물을 적어도, 존재하지 않는 파일을 적어도
    glob 이 대신 답을 냈다. 선언과 판정 근거가 서로 다른 곳에서 나오는 구조였다.

    이제 **칸이 선언한 것으로 판정한다.** 각 경로는 셋을 만족해야 한다:

      1. `reviews/` 아래다 — 심판문이 사는 곳 밖을 근거로 쓸 수 없다.
      2. 파일명이 `<회차>_<리뷰어>.md` 이고 **어간이 `회차` 칸과 같다** — 다른 회차의
         산출물로 이 행을 채울 수 없다.
      3. 리뷰어 조각이 비어 있지 않다.

    하나라도 어기면 **행 전체를 무효로 만든다.** 일부만 걸러 내면 나머지로 행이 서고,
    그것은 「거짓을 섞어 적어도 통과한다」와 같다 — 틀리는 방향은 늘 「리뷰를 더
    요구한다」 쪽이어야 한다.
    """
    if not stem:
        return ()
    declared: list[str] = []
    for token in _ARTIFACT_TOKEN.findall(cell):
        for path in _expand_braces(token.strip()):
            if not path.startswith(_ARTIFACT_PREFIX) or not path.endswith(".md"):
                return ()
            name = path[len(_ARTIFACT_PREFIX) : -len(".md")]
            if not name.startswith(f"{stem}_") or not name[len(stem) + 1 :]:
                return ()
            declared.append(path)
    return tuple(declared)


def _declared_roles(artifacts: tuple[str, ...], stem: str) -> frozenset[str]:
    """선언된 경로에서 리뷰어 이름만 뽑는다. 어간은 [_declared_artifacts] 가 이미 고정했다."""
    head = len(_ARTIFACT_PREFIX) + len(stem) + 1
    return frozenset(path[head : -len(".md")] for path in artifacts)


def _path_in_rev(rev: str, workspace_path: str) -> bool:
    """`rev` 의 트리에 그 경로가 **추적된 상태로** 있는가."""
    completed = subprocess.run(
        ["git", "cat-file", "-e", f"{rev}:{_WORKSPACE_REL}/{workspace_path}"],
        cwd=_REPO_ROOT,
        capture_output=True,
        check=False,
    )
    return completed.returncode == 0


#: 마크다운 표 구분선의 칸(`---` · `:--` · `--:`).
_SEPARATOR_CELL: Final = re.compile(r":?-{3,}:?")


def _table_data_lines(section: str) -> tuple[str, ...]:
    """절 안에서 **표의 데이터 행처럼 보이는 줄**을 전부 준다 (R-1, 2026-08-23).

    ## 왜 이 함수가 필요한가

    `_coverage_ranges` 와 `_ledger_rows` 는 형식이 어긋난 행을 **조용히 `continue`** 한다.
    그래서 「표에 24 행이 있는데 판정은 20 행만 했다」가 아무 신호도 내지 않는다 —
    실측(C-2): 리뷰된 `대기` 4행의 상태 칸을 `**대기**` → `**대기**(미리뷰)` 로 바꿨더니
    그 4행이 분모에서 증발하고 `4 passed` 였다. 장부는 사람이 손으로 쓰는 마크다운이고
    `대기` · `**대기**` · `대기(미리뷰)` 는 전부 자연스러운 인간 변형이다.

    **머리행과 구분선만 뺀다.** 표처럼 보이는 것의 하한을 「`|` 로 시작한다」에 두는 이유는,
    그보다 높은 하한(예: 「셀 수가 맞다」)을 쓰면 셀을 하나 지운 행이 다시 조용해지기
    때문이다. 판정을 좁히는 모든 조건은 **파서가 아니라 그 뒤의 대조**가 져야 한다.

    머리행의 정의는 「바로 다음 줄이 구분선인 `|` 줄」이다. 한 절에 표가 여럿이어도 각
    머리행이 자기 구분선으로 판별되므로 그대로 성립한다.
    """
    stripped = [line.strip() for line in section.splitlines()]
    is_separator = [
        line.startswith("|")
        and all(
            _SEPARATOR_CELL.fullmatch(cell.strip()) is not None
            for cell in line.strip("|").split("|")
        )
        for line in stripped
    ]
    return tuple(
        line
        for index, line in enumerate(stripped)
        if line.startswith("|")
        and not is_separator[index]
        and not (index + 1 < len(stripped) and is_separator[index + 1])
    )


def _table_header_cells(section: str) -> tuple[str, ...]:
    """절에서 **첫 표의 머리행** 칸을 준다. 못 찾으면 빈 튜플."""
    stripped = [line.strip() for line in section.splitlines()]
    for index, line in enumerate(stripped[:-1]):
        following = stripped[index + 1]
        if not line.startswith("|") or not following.startswith("|"):
            continue
        cells = [cell.strip() for cell in following.strip("|").split("|")]
        if cells and all(_SEPARATOR_CELL.fullmatch(cell) is not None for cell in cells):
            return tuple(cell.strip() for cell in line.strip("|").split("|"))
    return ()


def _header_drift(section: str, expected: dict[int, str]) -> list[str]:
    """머리행의 **자리마다** 기대한 열 이름이 있는지 본다.

    ## 왜 「절 안에 그 낱말이 있다」가 아니라 자리인가 (2026-08-23)

    종전 판은 `column in ledger` — 절 **본문 전체**에 대한 부분문자열 검사였다. 음성 대조로
    그 도달이 드러났다: 머리행의 `닫힘` 을 `닫은 회차` 로 개명해도 **exit 0** 이었다.
    데이터 칸이 `` `04_documents-c6r2` (닫힘, 2026-08-22) `` 처럼 같은 낱말을 품고 있어
    부분문자열이 언제나 참이었기 때문이다. 즉 그 단언은 열 구성을 재지 못했다.

    **자리를 함께 재는 이유**: 행을 읽는 코드가 위치 상수([_LEDGER_ROUND_CELL] 등)를 쓰므로
    두 열을 통째로 맞바꾸면 이름은 다 남은 채 **의미만 뒤바뀐다.** 실측: 그 맞바꾸기에서
    모든 행이 정상 파싱되고 `닫힘` 판정이 `리뷰할 회차` 값을 읽어 조용히 통과했다.
    """
    cells = _table_header_cells(section)
    return [
        f"자리 {index}: 기대 `{name}` · 실제 "
        + (f"`{cells[index]}`" if index < len(cells) else "(열 없음)")
        for index, name in sorted(expected.items())
        if index >= len(cells) or cells[index] != name
    ]


class CoverageVerdict(NamedTuple):
    """커버리지 표 한 행의 판정. `reason` 이 `None` 일 때만 `span` 이 있다."""

    line: str
    span: tuple[str, str] | None
    reason: str | None


def _coverage_verdicts(coverage: str) -> tuple[CoverageVerdict, ...]:
    """커버리지 표의 **데이터 행 전수**를 판정한다 — 무효 행도 사유와 함께 돌려준다.

    ## 왜 「탈락」이 아니라 「사유를 들고 나온다」인가 (C-1, 2026-08-23)

    종전 판은 무효 행을 `continue` 로 버렸다. 그러면 **그 행이 지탱하던 커밋이 없을 때
    게이트 판정이 전혀 바뀌지 않는다** — 실측: 오늘 커버리지 4행 중 자기만이 지탱하는
    커밋을 가진 행은 **1행뿐**이었고, 나머지 3행에 네 가지 위조(없는 산출물 경로 · 다른
    회차의 실재 산출물 · codex 산출물 없는 역할 구성 · 과거 산출물로 미래 범위 승인)를
    넣어도 전부 `4 passed, exit 0` 이었다.

    하중 있는 행에서 잡히더라도 **지목되는 것이 위조된 행이 아니었다.** 메시지는 「리뷰도
    장부도 없는 커밋 1건」이라 읽는 사람에게 제시되는 자연스러운 조치가 「그 커밋을 이연
    장부에 적는다」가 되고, 그러면 위조된 커버리지 행은 영구히 보이지 않게 된다.

    무효 행은 **선언이 규약을 어겼다는 사실 자체**다. 그것을 커밋 유무로 저울질하지 않는다.
    """
    verdicts: list[CoverageVerdict] = []
    for line in _table_data_lines(coverage):
        cells = [cell.strip() for cell in line.strip("|").split("|")]

        def rejected(reason: str, row: str = line) -> None:
            verdicts.append(CoverageVerdict(line=row, span=None, reason=reason))

        if len(cells) != _COVERAGE_COLUMNS:
            rejected(f"열이 {len(cells)} 개다 — {_COVERAGE_COLUMNS} 개여야 한다(회차·범위·산출물)")
            continue
        range_match = _RANGE_IN_BACKTICKS.fullmatch(cells[_COVERAGE_RANGE_CELL])
        if range_match is None:
            rejected("`범위` 칸이 백틱 `a..b` 가 아니다")
            continue
        round_match = re.fullmatch(r"`([^`]+)`", cells[_COVERAGE_ROUND_CELL])
        if round_match is None:
            rejected("`회차` 칸이 백틱 문자열 하나가 아니다")
            continue
        stem = round_match.group(1).strip()
        artifacts = _declared_artifacts(cells[_COVERAGE_ARTIFACT_CELL], stem)
        if not artifacts:
            rejected(
                f"`산출물` 칸이 규약을 어겼다 — 각 경로가 `{_ARTIFACT_PREFIX}{stem}_<리뷰어>.md` "
                "여야 한다(다른 회차의 산출물로 이 행을 채울 수 없다)"
            )
            continue
        missing_roles = _REQUIRED_ROLES - _declared_roles(artifacts, stem)
        if missing_roles:
            rejected(f"필수 역할이 선언되지 않았다: {sorted(missing_roles)}")
            continue
        start, end = range_match.group(1), range_match.group(2)
        absent = [rev for rev in (start, end) if not _commit_exists(rev)]
        if absent:
            rejected(f"범위 끝이 실재하는 커밋이 아니다: {absent}")
            continue
        uncommitted = [path for path in artifacts if not _path_in_rev("HEAD", path)]
        if uncommitted:
            rejected(f"산출물이 `HEAD` 에 커밋돼 있지 않다: {uncommitted}")
            continue
        preexisting = [path for path in artifacts if _path_in_rev(start, path)]
        if preexisting:
            rejected(
                f"산출물이 시작 커밋 `{start}` 에 이미 있었다 — "
                f"범위가 시작되기 전의 산출물은 그 범위를 승인할 수 없다: {preexisting}"
            )
            continue
        verdicts.append(CoverageVerdict(line=line, span=(start, end), reason=None))
    return tuple(verdicts)


def _coverage_ranges(coverage: str) -> tuple[tuple[str, str], ...]:
    """커버리지 **표 행**에서만 `a..b` 범위를 읽는다.

    ## 절 전체 정규식에서 구조적 표 파싱 + 산출물 실재 요구로 갈아탔다 (F-1, 2026-08-22)

    초판은 절 전체에서 `a..b` 를 정규식으로 긁었다. 두 레인이 같은 구멍을 지적했고
    Claude 레인이 실행으로 재현했다 — **`| 전부 | 0d632f9..HEAD | 없음 |` 한 줄을 적고
    `대기` 4줄을 지웠더니 `EXIT=0 · 44 passed`.** 리뷰 0건으로 전 구간이 「리뷰됨」이 됐고,
    산출물 칸이 문자 그대로 `없음` 인데 아무것도 반응하지 않았다.

    **F-2(장부 하한을 표 행으로 올림)만 닫는 것은 무의미하다** — 가짜 장부 행을 만들 필요
    없이 여기에 한 줄 적으면 되기 때문이다. 그래서 두 표를 같은 강도로 판정한다.

    행이 유효할 조건 (셋 다):

      1. 열이 [_COVERAGE_COLUMNS] 개다.
      2. `범위` 칸이 백틱 `a..b` 이고 **양 끝이 실재하는 커밋**이다(`git rev-parse --verify`).
      3. `회차` 칸이 백틱 문자열이고, **`산출물` 칸이 선언한 경로가 규약을 지킨다**
         ([_declared_artifacts]).
      4. 선언된 **역할**에 [_REQUIRED_ROLES] 가 전부 있다 — 개수가 아니라 구성이다.
      5. 선언된 경로가 **`HEAD` 에 커밋돼 있고**, **`시작` 커밋에는 없다** ([_path_in_rev]).

    ## 왜 시점 결속이 `<end>` 가 아니라 `HEAD` + `<start>` 인가 (X-2c, 2026-08-23)

    문면대로 `<end>` 에 산출물을 요구하면 **오늘 4행 중 3행이 무효가 된다**(실측). 기제는
    구조적이다 — **심판문은 심판 대상 범위가 끝난 뒤에 쓰인다.** 그러므로 `<end>` 결속은
    규약이 요구할 수 없는 것을 요구한다. 같은 명령·같은 층으로 방향만 바꿔 두 가지를 잰다:

      * `HEAD` 에 있는가 — **커밋됐는가.** `touch` 로 만든 미추적 파일은 여기서 죽는다.
      * `<start>` 에 **없어야** 한다 — 범위가 시작되기도 전에 있던 산출물은 그 범위를
        승인할 수 없다. 「과거 산출물로 미래 SHA 범위 승인」이 여기서 죽는다.

    ## 이 판정이 못 재는 것 (지우지 마라)

    산출물의 **내용이 참인지는 여전히 재지 않는다** — 그 안의 리뷰가 참인지, 그 범위를
    실제로 읽었는지는 보지 않는다. `xx_harness` 회차가 그 반례다: 산출물 3건이 남았는데
    codex 원문은 0줄이었고, 그 사실은 `_cross.md` 머리 경고로만 남아 있다. **저자가 자기
    회차를 서명하는 구조 자체는 그대로다** — 좁힌 것은 「아무것도 없이 서명하는 것」과
    「남의 회차 산출물로 서명하는 것」이다.

    판정 자체는 [_coverage_verdicts] 가 하고 여기서는 **유효한 행만 걸러 낸다.** 무효 행이
    조용히 사라지지 않게 하는 것은 [test_커버리지_표에_무효_행이_없다] 의 몫이다.
    """
    return tuple(verdict.span for verdict in _coverage_verdicts(coverage) if verdict.span)


def _commit_exists(rev: str) -> bool:
    completed = subprocess.run(
        ["git", "rev-parse", "--verify", f"{rev}^{{commit}}"],
        cwd=_REPO_ROOT,
        capture_output=True,
        check=False,
    )
    return completed.returncode == 0


def _reviewed_shas(coverage: str) -> frozenset[str]:
    """커버리지 범위를 `git rev-list` 로 펼친 전체 SHA 집합."""
    shas: set[str] = set()
    for start, end in _coverage_ranges(coverage):
        shas.update(_git("rev-list", f"{start}..{end}").split())
    return frozenset(shas)


#: 장부 `상태` 칸의 두 값.
#: `대기` = 필수 축에 닿는데 아직 리뷰를 못 받은 **빚**. `이연` = 비필수라 묶기로 판정한 것.
_WAITING_STATE: Final = "대기"
_DEFERRED_STATE: Final = "이연"

#: 장부 `상태` 칸에 허용되는 값. 그 밖의 값은 행을 무효로 만든다.
_LEDGER_STATES: Final = (_WAITING_STATE, _DEFERRED_STATE)

#: 장부 표의 열 수(`커밋 · 무엇을 바꿨나 · 상태 · 왜 4축이 아닌가 · 리뷰할 회차 · 닫힘`).
_LEDGER_COLUMNS: Final = 6

#: 장부 행에서 읽는 칸의 자리. 숫자를 코드 곳곳에 흩지 않는다 — 열이 늘면 여기만 고친다.
_LEDGER_SHA_CELL: Final = 0
_LEDGER_STATE_CELL: Final = 2
_LEDGER_ROUND_CELL: Final = 4
_LEDGER_CLOSED_CELL: Final = 5

#: 장부 머리행이 **자리마다** 이고 있어야 하는 열 이름. 읽는 코드가 자리로 읽으므로
#: 이름만 요구하면 두 열을 맞바꾸는 편집이 조용하다(실측 — [_header_drift] 참조).
_LEDGER_HEADER_CELLS: Final = {
    _LEDGER_SHA_CELL: "커밋",
    _LEDGER_STATE_CELL: "상태",
    _LEDGER_ROUND_CELL: "리뷰할 회차",
    _LEDGER_CLOSED_CELL: "닫힘",
}

#: 칸이 「안 적음」을 뜻하는 표기. `리뷰할 회차` 와 `닫힘` 이 같은 정의를 쓴다.
#: 대시 세 종을 받는 이유는 원장이 셋을 섞어 쓰기 때문이다(`test_harness_scope_reach.py`
#: 의 `_UNRESOLVED_EMPTY` 와 같은 근거). 양쪽 소비자 모두 「비었다 → 더 요구한다」 방향이라
#: 이 집합을 늘리는 것은 판정을 **좁히지 않고 조인다**. 그래도 근거 없이 늘리지 않는다.
_LEDGER_BLANK: Final = frozenset({"", "-", "—", "–"})


class LedgerRow(NamedTuple):
    """이연 장부의 유효한 표 행 하나."""

    sha: str
    state: str
    round_: str
    closed: str


class LedgerVerdict(NamedTuple):
    """이연 장부 한 행의 판정. `reason` 이 `None` 일 때만 `row` 가 있다."""

    line: str
    row: LedgerRow | None
    reason: str | None


def _ledger_verdicts(ledger: str) -> tuple[LedgerVerdict, ...]:
    """장부의 **데이터 행 전수**를 판정한다 — 버려진 행도 사유와 함께 돌려준다 (C-2).

    [_ledger_rows] 는 형식이 어긋난 행을 조용히 버린다. 그 조용함이 **X-3b 의 분모를
    무방비로** 만든다 — 실측: 리뷰된 `대기` 4행의 상태 칸만 `**대기**` → `**대기**(미리뷰)`
    로 바꿨더니 네 행이 분모에서 증발하고 `4 passed`(평시), 출하 모드에서도 **훼손된 4행을
    지목하지 않았다.** 그 4 커밋은 커버리지 범위 안이라 다른 검사도 초록으로 남았다.

    악의가 필요 없다는 것이 이 결함의 성질이다 — 장부는 사람이 손으로 쓰고
    `대기` · `**대기**` · `대기(미리뷰)` 는 전부 자연스러운 인간 변형이다.
    """
    verdicts: list[LedgerVerdict] = []
    for line in _table_data_lines(ledger):
        cells = [cell.strip() for cell in line.strip("|").split("|")]

        def rejected(reason: str, row: str = line) -> None:
            verdicts.append(LedgerVerdict(line=row, row=None, reason=reason))

        if len(cells) != _LEDGER_COLUMNS:
            rejected(f"열이 {len(cells)} 개다 — {_LEDGER_COLUMNS} 개여야 한다")
            continue
        sha_match = _SHA_IN_BACKTICKS.fullmatch(cells[_LEDGER_SHA_CELL])
        if sha_match is None:
            rejected("`커밋` 칸이 백틱 SHA 하나가 아니다")
            continue
        state = cells[_LEDGER_STATE_CELL].replace("*", "").strip()
        if state not in _LEDGER_STATES:
            rejected(f"`상태` 칸이 {list(_LEDGER_STATES)} 중 하나가 아니다: {state!r}")
            continue
        round_ = cells[_LEDGER_ROUND_CELL]
        if round_ in _LEDGER_BLANK:
            rejected("`리뷰할 회차` 칸이 비었다 — 「언제 갚는가」 없는 빚은 안 적힌 것과 같다")
            continue
        verdicts.append(
            LedgerVerdict(
                line=line,
                row=LedgerRow(
                    sha=sha_match.group(1),
                    state=state,
                    round_=round_,
                    closed=cells[_LEDGER_CLOSED_CELL],
                ),
                reason=None,
            )
        )
    return tuple(verdicts)


def _ledger_rows(ledger: str) -> tuple[LedgerRow, ...]:
    """이연 장부의 **표 행**만 구조적으로 읽는다.

    ## 절 전체 정규식에서 구조적 표 파싱으로 갈아탔다 (F-2 / codex Finding 3, 2026-08-22)

    초판은 절 전체에서 백틱 SHA 를 정규식으로 긁었다. 두 리뷰 레인이 **독립적으로** 같은
    자리에 닿았고 둘 다 실행으로 재현했다:

      * codex — 설명 문단의 ``deadbee`` 가 기록된 SHA 로 반환됐다.
      * Claude — 산문 한 줄 ``(여담: `bd4def464` …)`` 로 새 비면제 커밋이 초록이 됐고,
        **그 시점 HEAD 에서 이미 그 경로로 통과 중**이었다(`e7faccc` 가 표 행이 아니라
        산문 인용으로 계상됨).

    즉 「적혔다」의 하한이 **「절 어딘가에 SHA 가 언급됐다」**로 내려가 있었다. 그러면 `대기`/
    `이연` 어휘도, `리뷰할 회차` 도 강제되지 않는다 — 어휘를 갈라 적어 놓고 그것을 재는 코드가
    0 이었다.

    ## 행이 유효할 조건 (셋 다)

      1. 열이 [_LEDGER_COLUMNS] 개이고 첫 칸이 백틱 SHA 다.
      2. `상태` 칸이 [_LEDGER_STATES] 중 하나다(굵게 표시는 벗겨 낸다).
      3. `리뷰할 회차` 칸이 비어 있지 않다(`-` 도 빈 것으로 본다).

    표 밖 산문·구분선·머리행은 [_table_data_lines] 와 열 수·SHA 형식에서 걸러진다. **이
    함수가 「멈추고 장부에 적는다」라는 선택지의 하한을 정한다** — 그래서 하한을 산문이
    아니라 행에 둔다.

    판정은 [_ledger_verdicts] 가 하고 여기서는 유효한 행만 걸러 낸다. 버려진 행이 조용히
    사라지지 않게 하는 것은 [test_장부_표의_모든_행이_판정에_든다] 의 몫이다.
    """
    return tuple(verdict.row for verdict in _ledger_verdicts(ledger) if verdict.row is not None)


#: **출하 모드**를 켜는 환경 변수. 선례는 `KOTLIN_GATE_REACH_REQUIRE_REPORT` 다.
#:
#: 왜 모드인가: `대기` 는 정당한 중간 상태다(회차가 아직 안 돌았을 뿐). 그것을 상시 실패로
#: 만들면 이 검사는 늘 빨간 채로 살고, 늘 빨간 검사는 아무도 읽지 않아 탐지 능력이 0 이 된다.
#: 그렇다고 영원히 안 재면 「빚이 얼마나 쌓였는가」를 묻는 자리가 없다. 그래서 **Phase 종료·
#: 출하 판정 시점에 리더가 켜는** 축으로 갈랐다.
#:
#: **이 모드의 도달은 「리더가 켠다」 하나다 — CI 배선 0.** 오늘 미상환 `대기` 가 여러 건이라
#: 상시 배선은 모든 잡을 빨갛게 만든다. 이 사실을 지우지 마라(SKILL.md 규칙 3).
SHIPPABLE_MODE_ENV: Final = "REVIEW_COVERAGE_REQUIRE_SETTLED"


def _shippable_mode() -> bool:
    return bool(os.environ.get(SHIPPABLE_MODE_ENV))


def _unsettled(rows: tuple[LedgerRow, ...]) -> tuple[LedgerRow, ...]:
    """**미상환 빚** — `대기` 인데 `닫힘` 칸이 빈 행."""
    return tuple(row for row in rows if row.state == _WAITING_STATE and row.closed in _LEDGER_BLANK)


def _unsettled_deferred(
    rows: tuple[LedgerRow, ...], reviewed: frozenset[str]
) -> tuple[LedgerRow, ...]:
    """**상환되지 않은 `이연` 행** — `닫힘` 이 비었고 리뷰 범위에도 들지 않은 행."""
    return tuple(
        row
        for row in rows
        if row.state == _DEFERRED_STATE
        and row.closed in _LEDGER_BLANK
        and not any(full.startswith(row.sha) for full in reviewed)
    )


def _recorded_shas(ledger: str) -> tuple[str, ...]:
    """장부에 「적힌」 SHA. 유효한 표 행의 첫 칸이다.

    **출하 모드에서는 `이연` 행만 센다.** 평시에 `대기` 를 「적혔다」로 세는 것은 옳다 —
    빚을 진 사실이 기록됐다는 뜻이니까. 그런데 출하 판정에서까지 그렇게 세면 갚지 않은 빚이
    커버리지를 만족시키는 것이 되어, 「모든 변경은 정확히 한 번 리뷰를 받는다」가 **적기만
    하면 참**이 된다. 모드가 켜지면 그 관용을 거둔다.
    """
    rows = _ledger_rows(ledger)
    if _shippable_mode():
        rows = tuple(row for row in rows if row.state == _DEFERRED_STATE)
    return tuple(row.sha for row in rows)


def _judged_commits() -> tuple[tuple[str, str], ...]:
    """판정 대상 커밋 `(전체 SHA, 제목)` 을 오래된 것부터."""
    _git("rev-parse", "--verify", f"{_BASELINE_REV}^{{commit}}")
    raw = _git("log", "--reverse", "--format=%H%x09%s", f"{_BASELINE_REV}..HEAD")
    commits: list[tuple[str, str]] = []
    for line in raw.splitlines():
        if "\t" not in line:
            continue
        sha, subject = line.split("\t", 1)
        commits.append((sha, subject))
    return tuple(commits)


def _changed_paths(sha: str) -> tuple[str, ...]:
    """그 커밋이 바꾼 경로. 병합 커밋은 빈 튜플이 나올 수 있고, 그것은 면제가 아니다."""
    raw = _git("show", "--name-only", "--format=", sha)
    return tuple(line for line in raw.splitlines() if line.strip())


def _is_docs_only(paths: tuple[str, ...]) -> bool:
    """면제 판정. **경로가 0 건이면 면제가 아니다** — `all([])` 이 참이라 명시로 막는다."""
    return bool(paths) and all(path.startswith(_EXEMPT_PREFIX) for path in paths)


def test_원장에_리뷰_커버리지_절과_이연_장부가_있다() -> None:
    """판정의 두 입력이 실재하고, **빈 선언이 아닌지** 본다."""
    missing = [str(path) for path in _PROGRESS_PATHS if not path.exists()]
    assert not missing, (
        "원장 파일이 없다: " + ", ".join(missing) + "\n"
        "  두 파일 합본이 판정 입력이다 — 하나가 사라지면 커버리지 판정이 조용히 줄어든다."
    )

    coverage = _section(_COVERAGE_HEADING)
    assert coverage is not None, (
        f"원장에 「{_COVERAGE_HEADING}」 절이 없다.\n"
        "  이 절이 리뷰 회차가 덮은 커밋 범위의 유일한 기록이다. 없으면 「무엇이 리뷰됐는가」가\n"
        "  리더의 기억에만 남는다(codex 지적 high #2)."
    )

    ledger = _section(_LEDGER_HEADING)
    assert ledger is not None, (
        f"원장에 「{_LEDGER_HEADING}」 절이 없다.\n"
        "  묶기로 판정한 변경을 적을 자리가 사라지면\n"
        "  「이연됨」과 「끝내 리뷰 안 됨」이 구분되지 않는다."
    )

    ranges = _coverage_ranges(coverage)
    assert ranges, (
        f"「{_COVERAGE_HEADING}」 절에 `a..b` 범위가 한 건도 없다.\n"
        "  절 제목만 있고 표가 비면 이 검사는 아무 커밋도 「리뷰됨」으로 셀 수 없다 —\n"
        "  **빈 선언에서 통과하면 안 된다**(SKILL.md 규칙 4 ⑶)."
    )

    covered = _reviewed_shas(coverage)
    assert covered, (
        f"「{_COVERAGE_HEADING}」 절의 범위 {ranges} 를 펼쳤더니 커밋이 0 건이다.\n"
        "  범위가 뒤집혔거나(`b..a`) 같은 커밋을 가리킨다 — 적혀 있지만 아무것도 덮지 않는다."
    )

    coverage_drift = _header_drift(coverage, _COVERAGE_HEADER_CELLS)
    assert not coverage_drift, (
        f"「{_COVERAGE_HEADING}」 표의 머리행이 규약과 다르다:\n"
        + "\n".join(f"  {line}" for line in coverage_drift)
        + "\n\n세 열이 있어야 어느 회차가 어느 범위를 무슨 산출물로 덮었는지 되짚을 수 있고,\n"
        "  행을 읽는 코드가 **자리**로 읽으므로 순서가 바뀌면 값의 의미가 바뀐다."
    )

    ledger_drift = _header_drift(ledger, _LEDGER_HEADER_CELLS)
    assert not ledger_drift, (
        f"「{_LEDGER_HEADING}」 표의 머리행이 규약과 다르다:\n"
        + "\n".join(f"  {line}" for line in ledger_drift)
        + "\n\n  `상태` 는 `대기`(필수 축인데 미리뷰)와 `이연`(비필수라 묶음)을 가른다 —\n"
        "  칸이 없으면 둘이 한 덩어리가 되어 급한 것이 묻힌다.\n"
        "  `닫힘` 은 빚의 **출구**다 — 없거나 자리가 바뀌면 「갚은 빚」과 「안 갚은 빚」이 섞인다."
    )


def test_장부_표의_모든_행이_판정에_든다() -> None:
    """**표처럼 보이는 행 수 = 판정된 행 수** (C-2 / R-1).

    파서가 형식이 어긋난 행을 조용히 버리면, 상태 칸 표기 하나로 그 행이 **분모에서
    증발**하고 아무 검사도 발화하지 않는다. 이 검사가 그 증발을 소리 나게 만든다.

    커버리지 표에는 같은 대조를 두지 않는다 — [test_커버리지_표에_무효_행이_없다] 가
    같은 것을 **행과 사유까지** 지목하며 이미 진다. 같은 것을 두 번 선언하지 않는다(규칙 7).
    """
    ledger = _section(_LEDGER_HEADING)
    assert ledger is not None, (
        f"원장에 「{_LEDGER_HEADING}」 절이 없다 —\n"
        "  [test_원장에_리뷰_커버리지_절과_이연_장부가_있다] 를 먼저 보라."
    )

    verdicts = _ledger_verdicts(ledger)
    assert verdicts, (
        f"「{_LEDGER_HEADING}」 절에 표 데이터 행이 0 건이다.\n"
        "  빈 분모에서 통과하면 안 된다(SKILL.md 규칙 4 ⑶)."
    )

    dropped = [verdict for verdict in verdicts if verdict.reason is not None]
    assert not dropped, (
        f"장부 표에서 판정에 들지 못한 행 {len(dropped)} 건 "
        f"(표 데이터 행 {len(verdicts)} · 판정 {len(verdicts) - len(dropped)}):\n"
        + "\n".join(f"  {verdict.reason}\n    {verdict.line}" for verdict in dropped)
        + "\n\n버려진 행은 **적히지 않은 것과 같다** — 그 커밋은 커버리지 판정의 분모에서\n"
        "  빠지고, `닫힘`·출하 모드 판정에서도 빠진다. 칸 표기를 규약대로 되돌려라."
    )


def test_커버리지_표에_무효_행이_없다() -> None:
    """**무효 행을 이름으로 지목한다** (C-1).

    X-2a·X-2b·X-2c 가 세운 행 유효성 판정(경로 규약 · 역할 구성 · 시점 결속)은 종전에
    **행을 탈락시키기만** 했다. 탈락한 행이 지탱하던 커밋이 없으면 게이트 판정은 바뀌지
    않으므로, 커버리지 표의 위조는 **무하중 자리에서 영구히 조용하다.** 이 검사가 그
    저울질을 끊는다 — 무효 행은 커밋 유무와 무관하게 그 자체로 실패다.

    **못 재는 것**: 행이 규약을 지켰는지만 본다. 그 산출물 안의 리뷰가 참인지, 그 범위를
    실제로 읽었는지는 여전히 재지 않는다(`_coverage_ranges` 머리 주석의 한계 그대로).
    """
    coverage = _section(_COVERAGE_HEADING)
    assert coverage is not None, (
        f"원장에 「{_COVERAGE_HEADING}」 절이 없다 —\n"
        "  [test_원장에_리뷰_커버리지_절과_이연_장부가_있다] 를 먼저 보라."
    )

    verdicts = _coverage_verdicts(coverage)
    assert verdicts, (
        f"「{_COVERAGE_HEADING}」 절에 표 데이터 행이 0 건이다.\n"
        "  빈 분모에서 통과하면 안 된다(SKILL.md 규칙 4 ⑶) — 표를 통째로 지우는 편집이\n"
        "  여기서 죽는다."
    )

    invalid = [verdict for verdict in verdicts if verdict.reason is not None]
    assert not invalid, (
        f"커버리지 표에 무효 행 {len(invalid)} 건:\n"
        + "\n".join(f"  {verdict.reason}\n    {verdict.line}" for verdict in invalid)
        + "\n\n무효 행은 **그 행이 덮는다고 주장한 범위를 덮지 못한다.** 그런데 그 사실이\n"
        "  조용하면, 그 범위 안 커밋이 다른 이유로 이미 설명될 때 아무 신호도 나지 않는다.\n"
        "  행을 고치거나 지워라 — 지우면 그 범위의 커밋이 다시 미리뷰로 드러난다."
    )


def test_모든_비면제_커밋이_리뷰되거나_장부에_적혀_있다() -> None:
    """기준 커밋 이후 모든 커밋이 「리뷰됨 · 장부에 적힘 · 문서 전용」 중 하나에 드는지 본다."""
    coverage = _section(_COVERAGE_HEADING)
    ledger = _section(_LEDGER_HEADING)
    assert coverage is not None and ledger is not None, (
        "판정 입력이 되는 두 절이 원장에 없다 —\n"
        "  [test_원장에_리뷰_커버리지_절과_이연_장부가_있다] 를 먼저 보라."
    )

    reviewed = _reviewed_shas(coverage)
    recorded = _recorded_shas(ledger)

    unaccounted: list[str] = []
    for sha, subject in _judged_commits():
        if sha in reviewed:
            continue
        if any(sha.startswith(short) for short in recorded):
            continue
        if _is_docs_only(_changed_paths(sha)):
            continue
        unaccounted.append(f"  {sha[:9]}  {subject}")

    assert not unaccounted, (
        f"리뷰도 장부도 없는 커밋 {len(unaccounted)} 건 (기준 `{_BASELINE_REV}` 이후):\n"
        + "\n".join(unaccounted)
        + "\n\n조치는 둘 중 하나다 —\n"
        "  ⑴ 리뷰 회차를 돌리고 원장 「리뷰 커버리지」 표에 그 범위(`a..b`)와 산출물을 적는다\n"
        "  ⑵ 묶기로 판정했다면 원장 「리뷰 이연 장부」 표에\n"
        "     SHA·무엇을 바꿨나·상태·리뷰할 회차를 적는다\n"
        "\n적지 않고 묶는 것은 면제와 같다 — 그 커밋은 다음 묶음의 시작 SHA 에서도 빠져\n"
        "**영구히** 리뷰를 벗어난다(codex 지적 high #2)."
    )


def test_리뷰된_대기_행은_닫힘_칸이_적혀_있다() -> None:
    """**장부의 출구를 잰다** (X-3b).

    입구(「적혔다」)는 위 두 검사가 재는데 **출구를 재는 장치는 0 이었다.** `대기` 는
    「필수 축에 닿는데 아직 리뷰를 못 받았다」는 **빚**이고, 그 빚은 그 커밋을 덮는 회차가
    돌면 갚아진다. 그런데 회차가 돌아도 행은 `대기` 인 채로 남을 수 있었다 — 그러면 장부가
    영구히 부풀고 「무엇이 아직 안 갚혔는가」가 다시 리더의 기억으로 돌아간다.

    판정은 **커버리지 표와 장부를 맞대어** 한다: 어떤 `대기` 행의 SHA 가 이미 리뷰된 범위
    안에 들면 그 행의 `닫힘` 칸이 비어 있으면 안 된다. 리뷰가 돌지 않은 `대기` 행은
    건드리지 않는다 — 그것은 정상 상태이고, 그쪽을 재는 것은 출하 모드의 몫이다.

    **못 재는 것**: `닫힘` 칸에 무엇이 적혔는지는 보지 않는다. 아무 글자나 적으면 통과한다.
    이 검사가 좁히는 것은 「리뷰가 돌았는데 장부가 침묵하는 것」 하나다.
    """
    coverage = _section(_COVERAGE_HEADING)
    ledger = _section(_LEDGER_HEADING)
    assert coverage is not None and ledger is not None, (
        "판정 입력이 되는 두 절이 원장에 없다 —\n"
        "  [test_원장에_리뷰_커버리지_절과_이연_장부가_있다] 를 먼저 보라."
    )

    reviewed = _reviewed_shas(coverage)
    rows = _ledger_rows(ledger)
    assert rows, (
        f"「{_LEDGER_HEADING}」 절에 유효한 표 행이 0 건이다.\n"
        "  이 검사는 장부 행을 분모로 삼는다 — 빈 분모에서 통과하면 안 된다(SKILL.md 규칙 4 ⑶)."
    )

    # **판정 부분집합이 이 검사의 실질 분모다** (C-2 ⑶, 2026-08-23). 종전의 빈 분모 방어는
    # `assert rows`(장부 표 전체) 하나였고, 그것이 참인 채로 **실제로 판정하는 부분집합만
    # 0 이 되는** 경로가 있었다 — 상태 칸 표기를 훼손하면 리뷰된 `대기` 행이 통째로
    # 분모 밖으로 나가고 이 검사는 공허하게 초록이 된다(규칙 4 ⑶).
    judged = [
        row
        for row in rows
        if row.state == _WAITING_STATE and any(full.startswith(row.sha) for full in reviewed)
    ]
    assert judged, (
        f"리뷰 범위에 든 `{_WAITING_STATE}` 행이 0 건이다 — 이 검사가 아무것도 판정하지 않았다.\n"
        f"  장부 표 행은 {len(rows)} 건이고 리뷰된 커밋은 {len(reviewed)} 건인데 교집합이 비었다.\n"
        "  상태 칸 표기가 훼손됐거나 커버리지 표가 좁아졌다 —\n"
        "  둘 다 이 검사를 **공허하게 초록**으로 만드는 편집이다(SKILL.md 규칙 4 ⑶)."
    )

    unclosed = [row for row in judged if row.closed in _LEDGER_BLANK]
    assert not unclosed, (
        f"리뷰를 이미 받았는데 `닫힘` 칸이 빈 `{_WAITING_STATE}` 행 {len(unclosed)} 건:\n"
        + "\n".join(f"  `{row.sha}`  (리뷰할 회차: {row.round_})" for row in unclosed)
        + "\n\n그 커밋은 커버리지 표의 어느 범위에 들어 있다 — 즉 빚이 갚혔다.\n"
        "  `닫힘` 칸에 어느 회차가 언제 닫았는지 적어라. 적지 않으면 장부가\n"
        "  「갚은 빚」과 「안 갚은 빚」을 구분하지 못해 다시 리더의 기억에 의존한다."
    )


def test_출하_모드에서는_미상환_대기가_0_이어야_한다() -> None:
    """**장부의 입구를 잰다** (X-3a) — Phase 종료·출하 판정에서만 켜는 축.

    `대기` = 「필수 축에 닿는데 아직 리뷰를 못 받았다」이므로, 미상환 `대기` 가 남은 채로
    Phase 를 닫는 것은 **리뷰받지 않은 필수 변경을 안고 닫는 것**이다. 평시에는 그것이 정상
    중간 상태라 이 축을 판정하지 않고, [SHIPPABLE_MODE_ENV] 를 켤 때만 판정한다.

    **끈 실행에서 조용히 초록이 되지 않는다.** 모드가 꺼져 있으면 「이 축을 판정하지 않았다」를
    경고로 남긴다 — 통과 기록만 쌓이는 것이 이 종류의 빈자리다(SKILL.md 규칙 3).

    **오늘의 정상 결과는 빨강이다.** 남은 미상환 `대기` 는 아직 회차가 안 돈 빚이고, 그것이
    Phase 4 종료 판정을 막는 것이 이 축의 목적이다. 통과시키려고 상태를 `이연` 으로 내려
    적지 마라 — 그것은 거짓이고, 정직한 해소는 회차를 도는 것이다.
    """
    ledger = _section(_LEDGER_HEADING)
    assert ledger is not None, (
        f"원장에 「{_LEDGER_HEADING}」 절이 없다 —\n"
        "  [test_원장에_리뷰_커버리지_절과_이연_장부가_있다] 를 먼저 보라."
    )
    rows = _ledger_rows(ledger)
    assert rows, (
        f"「{_LEDGER_HEADING}」 절에 유효한 표 행이 0 건이다.\n"
        "  빈 분모에서 통과하면 안 된다(SKILL.md 규칙 4 ⑶) — 모드와 무관하게 실패한다."
    )

    unsettled = _unsettled(rows)
    if not _shippable_mode():
        warnings.warn(
            f"출하 축을 **판정하지 않았다** — `{SHIPPABLE_MODE_ENV}` 가 꺼져 있다."
            f" 현재 미상환 `{_WAITING_STATE}` {len(unsettled)} 건."
            " Phase 종료·출하 판정에서는 이 변수를 켜고 돌려라.",
            stacklevel=2,
        )
        return

    assert not unsettled, (
        f"미상환 `{_WAITING_STATE}` 행 {len(unsettled)} 건이 남은 채로 출하 판정을 시도했다:\n"
        + "\n".join(f"  `{row.sha}`  (리뷰할 회차: {row.round_})" for row in unsettled)
        + "\n\n이 행들은 **필수 축에 닿는데 리뷰를 못 받은 변경**이다 — 이연이 아니라 빚이다.\n"
        "  해소는 하나뿐이다: 적힌 회차를 돌리고 `닫힘` 칸을 채운다.\n"
        f"  상태를 `{_DEFERRED_STATE}` 으로 내려 적어 통과시키지 마라 — 그것은 거짓 기록이다."
    )


def test_출하_모드에서는_이연_행도_상환돼_있다() -> None:
    """**`이연` 면제를 닫는다** (codex ⑴) — 출하 판정에서만 켜는 축.

    출하 모드에서 [_recorded_shas] 는 `이연` 행만 센다. 그 관용은 「비필수라 묶었다」를
    존중하는 것인데, **`이연` 행의 `닫힘` 칸을 읽는 코드가 전 저장소에 0 이었다.** 그래서
    `닫힘` 이 빈 `이연` 행 한 줄로 미리뷰 비면제 커밋이 출하 판정을 통과했다 — 실측:

      * 합성 비면제 커밋을 `상태=이연` · `닫힘=-` 로 적고 출하 모드 → **`4 passed`, exit 0**.
      * 같은 행의 `상태` 만 `대기` 로 → `2 failed`. **한 낱말이 exit 0 과 exit 1 을 갈랐다.**
        커밋도, 미리뷰 사실도, 빈 `닫힘` 칸도 동일하다.

    그리고 그 갈림의 실패 쪽이 출력하는 문장이
    *"상태를 `이연` 으로 내려 적어 통과시키지 마라 — 그것은 거짓 기록이다"* 였다. 게이트가
    자기 문면으로 금지한 행동에 강제자가 없었다(규칙 3 — 산문은 강제자가 아니다).

    **상환의 두 형태를 다 받는다**: `닫힘` 칸이 찼거나, 그 커밋이 커버리지 범위에 들었거나.
    묶어서 나중에 보기로 한 것은 언젠가 **실제로 봐야** 상환된다.

    **오늘의 하중은 0 이다** — 장부에 `이연` 행이 한 건도 없다(전부 `대기`). 그것은
    「이연한 것이 없다」는 정직한 상태이므로 빈 부분집합을 실패로 보지 않는다. X-3b 의 빈
    부분집합(표기 훼손으로 **만들 수 있다**)과 성질이 다르다. 분모 방어는 장부 표 전체가
    비면 실패하는 `assert rows` 가 진다. 이 축의 도달은 **첫 `이연` 행이 적히는 순간**이다.
    """
    coverage = _section(_COVERAGE_HEADING)
    ledger = _section(_LEDGER_HEADING)
    assert coverage is not None and ledger is not None, (
        "판정 입력이 되는 두 절이 원장에 없다 —\n"
        "  [test_원장에_리뷰_커버리지_절과_이연_장부가_있다] 를 먼저 보라."
    )

    rows = _ledger_rows(ledger)
    assert rows, (
        f"「{_LEDGER_HEADING}」 절에 유효한 표 행이 0 건이다.\n"
        "  빈 분모에서 통과하면 안 된다(SKILL.md 규칙 4 ⑶) — 모드와 무관하게 실패한다."
    )

    unsettled = _unsettled_deferred(rows, _reviewed_shas(coverage))
    if not _shippable_mode():
        warnings.warn(
            f"`{_DEFERRED_STATE}` 상환 축을 **판정하지 않았다** — `{SHIPPABLE_MODE_ENV}` 가"
            f" 꺼져 있다. 현재 미상환 `{_DEFERRED_STATE}` {len(unsettled)} 건"
            f" (장부의 `{_DEFERRED_STATE}` 행 {sum(1 for r in rows if r.state == _DEFERRED_STATE)}"
            " 건). Phase 종료·출하 판정에서는 이 변수를 켜고 돌려라.",
            stacklevel=2,
        )
        return

    assert not unsettled, (
        f"상환되지 않은 `{_DEFERRED_STATE}` 행 {len(unsettled)} 건이 남은 채로"
        " 출하 판정을 시도했다:\n"
        + "\n".join(f"  `{row.sha}`  (리뷰할 회차: {row.round_})" for row in unsettled)
        + f"\n\n출하 모드는 `{_DEFERRED_STATE}` 행을 「적혔다」로 세어 커버리지를 만족시킨다.\n"
        "  그 관용에 상환 요구가 없으면 **묶기로 판정하는 것만으로 리뷰가 면제된다** —\n"
        f"  `{_WAITING_STATE}` 를 `{_DEFERRED_STATE}` 으로 내려 적는 한 낱말이 그 통로였다.\n"
        "  해소는 둘 중 하나다: 그 회차를 돌려 커버리지 표에 범위를 적거나,\n"
        "  이미 봤다면 `닫힘` 칸에 어느 회차가 언제 닫았는지 적는다."
    )
