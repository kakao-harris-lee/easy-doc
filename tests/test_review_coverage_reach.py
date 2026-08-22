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

import re
import subprocess
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

#: 심판 산출물이 사는 곳. 커버리지 행이 주장하는 회차의 실물이 여기 있어야 한다.
_REVIEWS_DIR: Final = _REPO_ROOT / "docs" / "migration" / "_workspace" / "reviews"


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
      3. `회차` 칸이 백틱 문자열이고, **`reviews/<회차>_*.md` 산출물이 최소 2건 실재**한다.
         3단계 게이트는 두 레인 + 교차 종합이라 하나만 있는 회차는 완주하지 않은 것이다.

    ## 이 판정이 못 재는 것 (지우지 마라)

    산출물이 **실재하는지**만 본다 — 그 안의 리뷰가 참인지, 그 범위를 실제로 읽었는지는
    재지 않는다. `xx_harness` 회차가 그 반례다: 산출물 3건이 남았는데 codex 원문은 0줄이었고,
    그 사실은 `_cross.md` 머리 경고로만 남아 있다. **저자가 자기 회차를 서명하는 구조 자체는
    그대로다** — 좁힌 것은 「아무것도 없이 서명하는 것」뿐이다.
    """
    ranges: list[tuple[str, str]] = []
    for line in coverage.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if len(cells) != _COVERAGE_COLUMNS:
            continue
        range_match = _RANGE_IN_BACKTICKS.fullmatch(cells[1])
        if range_match is None:
            continue
        round_match = re.fullmatch(r"`([^`]+)`", cells[0])
        if round_match is None:
            continue
        stem = round_match.group(1).strip()
        artifacts = sorted(_REVIEWS_DIR.glob(f"{stem}_*.md")) if stem else []
        if len(artifacts) < 2:
            continue
        start, end = range_match.group(1), range_match.group(2)
        if not _commit_exists(start) or not _commit_exists(end):
            continue
        ranges.append((start, end))
    return tuple(ranges)


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

    표 밖 산문·구분선·머리행은 열 수나 SHA 형식에서 걸러진다. **이 함수가 「멈추고 장부에
    적는다」라는 선택지의 하한을 정한다** — 그래서 하한을 산문이 아니라 행에 둔다.
    """
    rows: list[LedgerRow] = []
    for line in ledger.splitlines():
        stripped = line.strip()
        if not stripped.startswith("|"):
            continue
        cells = [cell.strip() for cell in stripped.strip("|").split("|")]
        if len(cells) != _LEDGER_COLUMNS:
            continue
        sha_match = _SHA_IN_BACKTICKS.fullmatch(cells[_LEDGER_SHA_CELL])
        if sha_match is None:
            continue
        state = cells[_LEDGER_STATE_CELL].replace("*", "").strip()
        if state not in _LEDGER_STATES:
            continue
        round_ = cells[_LEDGER_ROUND_CELL]
        if round_ in _LEDGER_BLANK:
            continue
        rows.append(
            LedgerRow(
                sha=sha_match.group(1),
                state=state,
                round_=round_,
                closed=cells[_LEDGER_CLOSED_CELL],
            )
        )
    return tuple(rows)


def _recorded_shas(ledger: str) -> tuple[str, ...]:
    """장부에 「적힌」 SHA. 유효한 표 행의 첫 칸이다."""
    return tuple(row.sha for row in _ledger_rows(ledger))


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

    for column in ("회차", "범위", "산출물"):
        assert column in coverage, (
            f"「{_COVERAGE_HEADING}」 표에 `{column}` 열이 없다. 세 열이 있어야 어느 회차가\n"
            "  어느 범위를 무슨 산출물로 덮었는지 사후에 되짚을 수 있다."
        )

    for column in ("커밋", "상태", "리뷰할 회차"):
        assert column in ledger, (
            f"「{_LEDGER_HEADING}」 표에 `{column}` 열이 없다.\n"
            "  `상태` 는 `대기`(필수 축인데 미리뷰)와 `이연`(비필수라 묶음)을 가른다 —\n"
            "  칸이 없으면 둘이 한 덩어리가 되어 급한 것이 묻힌다."
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

    unclosed = [
        row
        for row in rows
        if row.state == _WAITING_STATE
        and row.closed in _LEDGER_BLANK
        and any(full.startswith(row.sha) for full in reviewed)
    ]
    assert not unclosed, (
        f"리뷰를 이미 받았는데 `닫힘` 칸이 빈 `{_WAITING_STATE}` 행 {len(unclosed)} 건:\n"
        + "\n".join(f"  `{row.sha}`  (리뷰할 회차: {row.round_})" for row in unclosed)
        + "\n\n그 커밋은 커버리지 표의 어느 범위에 들어 있다 — 즉 빚이 갚혔다.\n"
        "  `닫힘` 칸에 어느 회차가 언제 닫았는지 적어라. 적지 않으면 장부가\n"
        "  「갚은 빚」과 「안 갚은 빚」을 구분하지 못해 다시 리더의 기억에 의존한다."
    )
