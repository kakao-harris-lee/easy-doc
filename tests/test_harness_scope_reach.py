"""`00_progress.md` 의 `실행 경로` 열 규약을 **실행으로** 강제한다.

**이 파일은 답의 형식을 강제하지 답을 강제하지 않는다.** `ci:quality` 라고 적힌 행이
정말 `quality` 잡에서 도는지는 검사하지 않는다 — 잡 이름이 `ci.yml` 에 실재하는지만
본다. 형식이 맞으면서 내용이 거짓인 표는 이 검사를 통과한다. 그 층은 리뷰가 맡는다.

하네스 규칙 「선언한 범위와 실제 도달을 대조한다」의 **규칙 3**("이 게이트가 지금 어디서
도는가를 먼저 답한다. 도달 0을 특히 의심한다")은 오랫동안 문장으로만 있었고, 그동안
정확히 그 규칙이 잡아야 할 사고가 났다 — 「품질 합격선 확정·승인」 행이 `충족 = 예` 로
닫혔는데 그 합격선의 차단축은 전부 `-m llm` 마커라 `addopts = "-m 'not llm'"` 와 CI 잡
부재로 **CI 에서 0번 돌았다.** 표만 봐서는 그 사실을 알 수 없었다. 이 파일이 그 규칙에
붙인 실행 경로다 — 규칙이 스스로 "도달 0" 이던 상태를 닫는다.

**어휘 정본은 이 파일이 아니다.** `.claude/skills/kotlin-migration/SKILL.md` 의
「선언한 범위와 실제 도달을 대조한다」 절 → 「어디에 적용하는가」 → Phase 종료 판정
항목에 6종 표가 있다. 아래 상수·정규식은 그 명세의 **두 번째 표현**(명세↔테스트 관계)
이며, 둘이 갈리면 SKILL.md 가 이긴다. `00_progress.md` 는 정의를 복제하지 않고
포인터만 둔다.

강제하는 것:

1. 대상 표 4개 전부에 `실행 경로` 열이 있다.
2. **모든 행**이 비어 있지 않은 실행 경로를 갖는다 — `충족` 열의 유무·값과 무관하다.
   빈 칸·`-` 는 어디서든 위반이다.
3. `충족 = 예` 인 행의 실행 경로는 **실행을 가리킨다** (`안 돎`·`미배선` 불가).
   `충족` 셀은 `예`/`아니오` **정확 일치, 또는 그 낱말 뒤에 구분자**(공백·`—`·`(`)가
   올 때만 그 값으로 읽는다 — `아니오 — 1/11 생성` 같은 복합 표기가 이 문서의 관용이라
   완전 일치만으로는 부족하고, 그렇다고 **접두**로 읽으면 `예정`(뜻은 "아직")·`예외`·
   `예상`·`예비` 가 전부 충족으로 뒤집혀 **정상 문서를 거짓 고발한다.**
   어느 쪽으로도 읽히지 않으면 **조용히 건너뛰지 않고 위반**이다.
4. `ci:<잡>` 의 잡이 `.github/workflows/ci.yml` 의 `jobs:` 에 실재한다.
5. `1회성:<경로>` 가 **git 이 추적하는 파일**이다(디렉터리·미추적 파일 거부).
6. `local:<명령>` 의 첫 낱말이 실행 파일 이름 꼴이다(산문 약속 거부).
7. `결정:<날짜>` 가 실제 달력 날짜다.
8. 어휘 6종 밖의 표기가 없다.

## 이 검사가 못 잡는 것 (한계 — 닫지 않고 적는다)

전부 막으려 들면 이 파일이 브리틀해지고, 브리틀해지면 다음 사람이 규칙을 느슨하게
만든다. 그래서 아래는 **의도적으로 열어 둔다.**

다만 **적지 않은 공백은 열어 둔 것이 아니라 모르는 것**이다. 실제 공백보다 좁게
선언한 한계 절은 그 자체가 「범위는 근거를 넘지 않는다」 위반이고, 읽는 사람에게
"여기 적힌 것 말고는 막힌다"는 거짓 안심을 준다. 그래서 아래는 독립 검증 레인이
**실제로 뚫은 경로를 전부** 적는다 — 막는 대신 적는 쪽을 택한 것이다.

### A. 어휘 자체가 자유 통과 카드가 되는 경로

- **`결정:<오늘 날짜>` 는 아무 행이나 닫는다.** `| 예 | ci:kotlin |` 을
  `| 예 | 결정:2026-08-13 |` 로 바꾸는 **두 낱말 편집**이면 통과한다. 규칙의 사문은
  "실행 경로는 실행을 가리킨다"인데 구현이 죽은 표기로 보는 것은 `안 돎`·`미배선`
  **둘뿐**이라, 나머지 넷은 무엇이든 실행을 가리킨 것으로 친다.
- **`결정:` 날짜에 범위가 없다.** 달력에 있기만 하면 되므로 `결정:2099-12-31`(미래)도
  `결정:1970-01-01`(저장소 첫 커밋 이전)도 통과한다.
- **`local:-` 와 `local:...` 이 통과한다.** 규칙 2는 실행 경로 칸의 `-` 를 "안 적음"
  으로 거부하는데, 같은 `-` 에 `local:` 만 붙이면 첫 낱말이 ASCII 라 통과한다.
  빈칸 표식을 세탁하는 경로다.
- **`local:` 명령의 실재.** 첫 낱말의 **모양**만 본다. `local:TBD 예정` 처럼 첫 낱말이
  ASCII 면 통과한다 — 명령이 실제로 존재하는지 확인하려면 실행해야 하고, 그건 이
  검사의 범위 밖이다. 막는 것은 `local:언젠가 돌릴 예정` 같은 **한글 산문**뿐이다.
- **`1회성:` 이 자기참조를 막지 않는다.** `1회성:tests/test_harness_scope_reach.py`
  (검사기 자신)나 `1회성:.gitignore` 로 아무 행이나 닫을 수 있다. 검사기가 자기
  존재를 자기 규칙의 근거로 인정하는 셈이다.
- **`1회성:` 근거는 커밋 없이 생긴다.** 판정 기준이 `git ls-files` 라 **`git add`
  만으로** 방금 만든 파일이 즉시 산출물 근거가 된다. 커밋도 리뷰도 거치지 않는다.
- **`1회성:` 경로의 관련성.** 추적 파일이기만 하면 그 행과 무관한 파일이어도 통과한다.

### B. 규칙을 조용히 끄는 편집

- **`아니오` 로 시작하면 규칙 3이 통째로 건너뛴다.** `아니오 → 예 (2026-08-13 해소)`
  는 `아니오` 로 읽혀 실행 경로 판정을 지나친다. 하필 이것이 **역사 행을 갱신할 때
  가장 자연스러운 편집 형태**라, 악의 없이 도달한다.
- **(닫힘) 충족 강등과 행 삭제.** 예전엔 하한에 여유(행 5 · `충족 = 예` 3 · 표기 7)가
  있어 `충족 = 예` 3행을 `아니오` + `안 돎` 으로 내리거나 행 5개를 지워도 통과했다.
  하필 지워도 안 걸리는 것 중에 **품질 게이트 행**(근거 6번의 그 행)이 있었다.
  `EXPECTED_ROWS` 등을 **정확 일치**로 바꿔 닫았다.
- **표기의 진실.** 위 첫 문단대로다. 문서가 `상태 = 미실행` 이라 적은 행에 `ci:quality`
  를 달아도 통과한다. 이 검사는 "어디서 도는가에 답했는가" 만 본다.
- **`local:` 로 닫힌 행.** 근거가 된 사고(합격선의 CI 도달 0)를 다시 겪어도 이 검사는
  막지 못한다. `local:` 은 CI 도달 0을 **드러내는** 표기이지 금지 표기가 아니기
  때문이다. 원인은 한 행이 여러 축을 겹쳐 담고 있다는 것이고, 차단하려면 규칙이 아니라
  **행을 갈라야** 한다.

### C. 행과 파일이 검사기 눈에서 사라지는 경로

- **선행 파이프 한 글자를 빠뜨리면 그 행이 사라진다.** GitHub 렌더러는 그 행을 정상
  표시하므로 **문서엔 11행이 보이는데 검사기는 10행만 본다.** 행 삭제와 달리 diff 가
  **2글자**라 눈에 잘 띄지 않는다. 표 중간에 빈 줄을 하나 넣어도 같다 — 그 아래
  전부가 별개 블록이 되어 표에서 떨어져 나간다.
- **위조 행 추가는 여전히 안 잡는다.** 개수를 정확 일치로 바꿔 **삭제·강등은 닫혔지만**,
  근거 없는 행을 넣고 `EXPECTED_ROWS` 를 함께 올리면 통과한다. 기록 위조는 이 검사의
  threat model 이 아니라 **리뷰와 diff** 의 몫이다 — 다만 이제 그 diff 에 상수 변경이
  반드시 딸려 나오므로 눈에 띈다.
- **(닫힘) 이 파일을 스위트에서 통째로 빼는 것.** `pytestmark = pytest.mark.llm`
  한 줄이면 `addopts = "-m 'not llm'"` 때문에 전건 제외되는데 전체 수집은 **exit 0**
  이라 스위트가 초록이었다(근거 6번과 같은 기제다). 파일 삭제도 마찬가지였다.
  이 하나는 열어 두지 않고 **CI 에서 막았다** — `quality` 잡에 이 경로를 명시하는
  스텝이 따로 있어 삭제·경로 변경은 exit 4, 마커 전건 제외는 exit 5 로 빨개진다.
  자기 안의 "나는 여기서 돈다"는 단언은 파일과 함께 사라지므로, 단언을 파일 밖에
  두는 것 말고는 방법이 없다. **남은 구멍은 그 스텝 자체의 삭제**이고, 저장소 안의
  어떤 파일도 자기 자신에 대한 절대 기준이 될 수 없으므로 그 지점의 방어선은 리뷰다.

판정 로직은 저장소 상태에 의존하지 않는 **순수 함수**(`parse_tables` ·
`select_target_tables` · `judge_tables`)로 빼 두었고, 아래쪽에 **음성 대조**가 합성
입력으로 붙어 있다. 각 규칙이 어떤 입력에서 실패하는지 보이지 않으면 이 파일의 통과는
"입력이 애초에 무해해서" 와 구분되지 않는다(규칙 5).

지금 어디서 도는가: `tests/` 아래라 `uv run pytest` 가 수집한다 — CI `quality` 잡의
`uv run pytest` 단계에서 매 실행 돈다. `-m llm` 마커를 붙이지 않았고 네트워크·LLM·DB 를
쓰지 않으므로 `addopts = "-m 'not llm'"` 기본 스위트에서 제외되지 않는다. **그리고 같은
잡에 이 경로를 명시하는 스텝이 하나 더 있다** — 이 문장이 파일과 함께 사라져도 CI 가
빨개지게 하려는 것이다(위 C 마지막 항목). 자기 도달을 자기 문서에 적는 것이 규칙 6이고,
그 단언을 파일 밖에도 한 벌 두는 것이 규칙 5다.
"""

from __future__ import annotations

import re
import subprocess
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Final

import pytest
import yaml

_REPO_ROOT: Final = Path(__file__).resolve().parents[1]
_PROGRESS_PATH: Final = _REPO_ROOT / "docs" / "migration" / "_workspace" / "00_progress.md"
_CI_WORKFLOW_PATH: Final = _REPO_ROOT / ".github" / "workflows" / "ci.yml"

#: 규약이 걸린 표를 고르는 열 이름. `실행 경로` 가 아니라 이 셋으로 고르는 이유는,
#: 열이 **삭제됐을 때도 표를 찾아내야** 규칙 1이 실패로 드러나기 때문이다.
_GOAL_HEADER: Final = "종료 조건"
_MET_HEADER: Final = "충족"
_GATE_HEADER: Final = "게이트"
_REACH_HEADER: Final = "실행 경로"

_MET_YES: Final = "예"
_MET_NO: Final = "아니오"
_NEVER: Final = "안 돎"
_UNWIRED: Final = "미배선"

#: `충족` 셀을 읽는 낱말 경계. 낱말 **다음**에 구분자나 끝이 와야 그 값으로 읽는다.
#: 접두 판정이면 `예정`("아직"이라는 뜻)이 `예` 로 읽혀 정상 행을 거짓 고발하고,
#: 완전 일치면 이 문서의 관용인 `아니오 — 1/11 생성` 이 읽히지 않아 역시 뒤집힌다.
#: 구분자를 셋(공백·`—`·`(`)으로 좁게 잡은 이유는 근거가 그 셋뿐이기 때문이다 —
#: 넓히면 다음 `예정` 부류가 다시 새어 들어온다.
_MET_BOUNDARY: Final = r"(?=[\s—(]|$)"
_MET_YES_PATTERN: Final = re.compile(rf"^{_MET_YES}{_MET_BOUNDARY}")
_MET_NO_PATTERN: Final = re.compile(rf"^{_MET_NO}{_MET_BOUNDARY}")

#: 실행 경로 칸이 "아직 안 적음" 인 상태. 표기가 아니므로 어휘 검사 대상이 아니다.
_BLANK_MARKS: Final = frozenset({"", "-"})

#: 여러 경로가 함께 도는 행을 잇는 구분자.
_TOKEN_SEPARATOR: Final = "·"

#: 대상 표 개수 — Phase 0·1·2 종료 조건 표 + 「아직 돌리지 않은 검증 게이트」 표.
EXPECTED_TARGET_TABLES: Final = 4

#: 판정이 **0건 검사로 통과**하는 것을 막는 기대 개수. **하한이 아니라 정확 일치다.**
#:
#: 처음엔 여유를 둔 하한(40/15/45)이었는데, 그 여유만큼 **조용히 줄어들 수 있었다** —
#: 실측으로 `충족 = 예` 3행을 강등하거나 행 5개를 지워도 통과했다. 하필 지워도 안 걸리는
#: 것 중에 **품질 게이트 행**(근거 6번의 그 행)이 있었다. 이 파일은 범위를 스스로
#: 열거하는 장치이고, 그런 장치의 최대 위험은 **선언이 줄어든 채 초록이 되는 것**이다
#: (SKILL.md 규칙 4 ⑶). 여유를 두는 순간 그 위험을 여유 크기만큼 허용하게 된다.
#:
#: 그래서 정확 일치로 바꿨다. 표를 정당하게 고치면 이 상수도 함께 고쳐야 하고,
#: **그 diff 가 "판정 범위를 건드렸다"는 신호로 리뷰에 올라가는 것**이 이 상수의
#: 값어치다. 브리틀한 것이 아니라 마찰이 의도된 자리다.
EXPECTED_ROWS: Final = 45
EXPECTED_MET_YES: Final = 18
EXPECTED_REACH_TOKENS: Final = 52

_CI_TOKEN: Final = re.compile(r"^ci:([A-Za-z0-9][A-Za-z0-9_.-]*)$")
_LOCAL_TOKEN: Final = re.compile(r"^local:(\S.*)$")
_ONCE_TOKEN: Final = re.compile(r"^1회성:(\S+)$")
_DECISION_TOKEN: Final = re.compile(r"^결정:(\d{4}-\d{2}-\d{2})$")

#: `local:` 뒤 첫 낱말이 만족해야 하는 모양. 러너 이름을 화이트리스트로 못 박지 않는
#: 이유는 `make`·`npm`·`./gradlew` 같은 정당한 미래 값을 막기 때문이다. 막으려는 것은
#: `local:언젠가 돌릴 예정` 같은 **산문 약속** 하나다 — 그것이 통과하면 `local:` 은
#: 어떤 행이든 닫는 자유 통과 카드가 된다.
_LOCAL_COMMAND_HEAD: Final = re.compile(r"^[A-Za-z0-9._/-]+$")

#: `\|` 로 escape 된 파이프는 셀 구분자가 아니다 — 이 파일의 표 안에 실제로 있다.
_CELL_SPLIT: Final = re.compile(r"(?<!\\)\|")
_SEPARATOR_CELL: Final = re.compile(r"^:?-{2,}:?$")

_TITLE_CLIP: Final = 60


# --- 파싱 (순수 함수) ---------------------------------------------------------


@dataclass(frozen=True)
class Table:
    """마크다운 표 하나. 셀은 굵게 표시를 걷어 낸 정규화 문자열이다."""

    caption: str
    headers: tuple[str, ...]
    rows: tuple[tuple[str, ...], ...]
    line_number: int


@dataclass(frozen=True)
class JudgeContext:
    """판정에 필요한 바깥 사실. 합성 입력으로 갈아 끼울 수 있어야 음성 대조가 선다."""

    ci_jobs: frozenset[str]
    #: 존재가 아니라 **git 추적 여부**다. 존재만 보면 `1회성:.` (저장소 루트)로 어떤
    #: 행이든 닫을 수 있고, 미추적 스크래치 파일도 산출물 근거가 되어 버린다.
    is_tracked_file: Callable[[str], bool]


@dataclass(frozen=True)
class Violation:
    """규약 위반 하나. 표·행·사유를 담아 실패 메시지에서 바로 읽히게 한다."""

    table: str
    row: str
    reason: str

    def __str__(self) -> str:
        return f"[{self.table}] {self.row} — {self.reason}"


def _normalize_cell(raw: str) -> str:
    """셀에서 마크다운 굵게 표시를 걷어 비교 가능한 문자열로 만든다."""
    return raw.replace("**", "").strip()


def _split_row(line: str) -> tuple[str, ...]:
    """`| a | b |` 를 `("a", "b")` 로 자른다. 양끝의 빈 조각만 버린다."""
    parts = _CELL_SPLIT.split(line.strip())
    if parts and parts[0].strip() == "":
        parts = parts[1:]
    if parts and parts[-1].strip() == "":
        parts = parts[:-1]
    return tuple(_normalize_cell(part) for part in parts)


def _is_separator_row(cells: Sequence[str]) -> bool:
    return len(cells) > 0 and all(_SEPARATOR_CELL.match(cell) is not None for cell in cells)


def parse_tables(markdown: str) -> list[Table]:
    """마크다운에서 표를 전부 뽑는다. `caption` 은 직전에 나온 제목 줄이다."""
    lines = markdown.splitlines()
    tables: list[Table] = []
    caption = ""
    index = 0
    while index < len(lines):
        line = lines[index]
        if line.startswith("#"):
            caption = line.lstrip("#").strip()
            index += 1
            continue
        if not line.lstrip().startswith("|") or index + 1 >= len(lines):
            index += 1
            continue
        headers = _split_row(line)
        separator = lines[index + 1]
        if not separator.lstrip().startswith("|"):
            index += 1
            continue
        separator_cells = _split_row(separator)
        if len(separator_cells) != len(headers) or not _is_separator_row(separator_cells):
            index += 1
            continue
        rows: list[tuple[str, ...]] = []
        cursor = index + 2
        while cursor < len(lines) and lines[cursor].lstrip().startswith("|"):
            rows.append(_split_row(lines[cursor]))
            cursor += 1
        tables.append(
            Table(
                caption=caption,
                headers=headers,
                rows=tuple(rows),
                line_number=index + 1,
            )
        )
        index = cursor
    return tables


def select_target_tables(tables: Sequence[Table]) -> list[Table]:
    """규약이 걸린 표만 고른다 — 종료 조건 표(`종료 조건`+`충족`)와 검증 게이트 표."""
    selected: list[Table] = []
    for table in tables:
        is_goal_table = _GOAL_HEADER in table.headers and _MET_HEADER in table.headers
        is_gate_table = len(table.headers) > 0 and table.headers[0] == _GATE_HEADER
        if is_goal_table or is_gate_table:
            selected.append(table)
    return selected


def split_reach_tokens(cell: str) -> list[str]:
    """실행 경로 칸을 표기 단위로 자른다. 빈 칸·`-` 는 표기가 없는 것으로 본다."""
    if cell.strip() in _BLANK_MARKS:
        return []
    return [token.strip().strip("`").strip() for token in cell.split(_TOKEN_SEPARATOR)]


# --- 판정 (순수 함수) ---------------------------------------------------------


def _vocabulary_problem(token: str, context: JudgeContext) -> str | None:
    """표기 하나를 어휘 6종에 대조한다. 맞으면 None, 어긋나면 사유를 돌려준다."""
    if token in (_NEVER, _UNWIRED):
        return None

    ci_match = _CI_TOKEN.match(token)
    if ci_match is not None:
        job = ci_match.group(1)
        if job not in context.ci_jobs:
            known = ", ".join(sorted(context.ci_jobs)) or "없음"
            return f"`ci:{job}` 인데 ci.yml 의 jobs 에 `{job}` 이 없다 (실재하는 잡: {known})"
        return None

    once_match = _ONCE_TOKEN.match(token)
    if once_match is not None:
        target = once_match.group(1)
        if not context.is_tracked_file(target):
            return (
                f"`1회성:{target}` 이 git 이 추적하는 **파일**이 아니다 — "
                "디렉터리(`1회성:.` 같은)나 미추적 파일은 산출물 근거가 되지 못한다"
            )
        return None

    local_match = _LOCAL_TOKEN.match(token)
    if local_match is not None:
        head = local_match.group(1).split()[0]
        if _LOCAL_COMMAND_HEAD.match(head) is None:
            return (
                f"`local:` 의 첫 낱말 `{head}` 이 실행 파일 이름 꼴이 아니다 — "
                "산문 약속은 실행 경로가 아니다. 실제로 칠 수 있는 명령을 적어라"
            )
        return None

    decision_match = _DECISION_TOKEN.match(token)
    if decision_match is not None:
        stamp = decision_match.group(1)
        try:
            date.fromisoformat(stamp)
        except ValueError:
            return f"`결정:{stamp}` 이 실제 달력 날짜가 아니다 — 모양만 맞는 값은 근거가 아니다"
        return None

    return (
        f"어휘 밖 표기 `{token}` — 허용은 `ci:<잡>` · `local:<명령>` · `1회성:<경로>` · "
        f"`결정:<YYYY-MM-DD>` · `{_NEVER}` · `{_UNWIRED}` 여섯뿐이다"
    )


def _clip(title: str) -> str:
    return title if len(title) <= _TITLE_CLIP else title[:_TITLE_CLIP] + "…"


def met_verdict(cell: str) -> bool | None:
    """`충족` 셀을 예/아니오로 읽는다. 읽을 수 없으면 None.

    **낱말 경계 판정**이다 — `예`/`아니오` 와 정확히 같거나, 그 낱말 **뒤에 구분자**
    (공백·`—`·`(`)가 올 때만 그 값으로 읽는다.

    완전 일치만으로는 안 된다. 이 문서는 실제로 `아니오 — **1/11 생성**` 같은 복합
    표기를 쓰고(역사 행이라 고칠 수 없다), 완전 일치로 두면 그 행이 읽히지 않아
    **정상 행이 위반으로** 뒤집힌다.

    접두로도 안 된다. `예정` 은 접두가 `예` 라 **충족으로 읽히는데 뜻은 정반대**
    ("아직")다. 그러면 `예정` + `안 돎` 인 정상 행에 규칙 3이 발동해 "충족 = 예인데
    안 돎"이라고 **거짓 고발**한다. `예외 — 범위 밖`·`예상`·`예비` 도 같은 부류이고
    전부 이 문서의 관용 어휘권 안에 있다. 정상 문서를 고발하는 검사는 한계가 아니라
    버그이며, 몇 번 겪으면 다음 사람이 규칙째로 지운다.

    두 요구를 동시에 만족시키는 것이 낱말 경계다. `예정`·`예외`·`예상`·`예비` 는
    `예` 다음이 낱자라 탈락하고, `아니오 — 1/11 생성` 은 다음이 공백이라 통과한다.

    None 은 "건너뜀"이 아니라 **위반**으로 처리된다. `O`·`Y`·`완료`·`✅` 로 적으면
    규칙 3이 조용히 꺼지는데, 그것이 평범한 문서 편집으로 도달하는 경로이기 때문이다.
    """
    text = cell.strip()
    if _MET_NO_PATTERN.match(text) is not None:
        return False
    if _MET_YES_PATTERN.match(text) is not None:
        return True
    return None


def judge_tables(tables: Sequence[Table], context: JudgeContext) -> list[Violation]:
    """대상 표들을 모듈 docstring 의 규약으로 판정한다. 통과면 빈 목록이다."""
    violations: list[Violation] = []
    for table in tables:
        caption = table.caption or f"{table.line_number}행의 이름 없는 표"

        # 규칙 1 — 열 존재.
        if _REACH_HEADER not in table.headers:
            violations.append(
                Violation(
                    table=caption,
                    row="(표 전체)",
                    reason=(
                        f"`{_REACH_HEADER}` 열이 없다 — 이 표는 규약 대상인데 "
                        f"열이 사라졌다 (헤더: {' | '.join(table.headers)})"
                    ),
                )
            )
            continue

        reach_index = table.headers.index(_REACH_HEADER)
        met_index = table.headers.index(_MET_HEADER) if _MET_HEADER in table.headers else None

        for row in table.rows:
            if len(row) != len(table.headers):
                violations.append(
                    Violation(
                        table=caption,
                        row=_clip(row[0]) if row else "(빈 줄)",
                        reason=(
                            f"셀 수가 헤더와 다르다 (셀 {len(row)}개 / 헤더 "
                            f"{len(table.headers)}개) — 열이 밀려 판정할 수 없다"
                        ),
                    )
                )
                continue

            title = _clip(row[0])
            tokens = split_reach_tokens(row[reach_index])

            # 규칙 2 — 모든 행이 실행 경로를 갖는다. `충족` 열이 없는 게이트 표에도
            # 적용된다 — 그 표는 규칙 3이 구조적으로 닿지 않아 여기가 유일한 방어다.
            if not tokens:
                violations.append(
                    Violation(
                        table=caption,
                        row=title,
                        reason=(
                            f"`{_REACH_HEADER}` 가 비었다 — 어디서 도는지 적지 않으면 "
                            "도달 0을 구분할 수 없다 (`충족` 열의 유무·값과 무관하다)"
                        ),
                    )
                )

            # 규칙 4·5·6·7·8 — 표기 자체의 타당성. `충족` 값과 무관하게 본다.
            for token in tokens:
                problem = _vocabulary_problem(token, context)
                if problem is not None:
                    violations.append(Violation(table=caption, row=title, reason=problem))

            if met_index is None:
                continue

            # 규칙 3 — `충족` 을 읽고, `예` 인 행은 실행을 가리켜야 한다.
            verdict = met_verdict(row[met_index])
            if verdict is None:
                violations.append(
                    Violation(
                        table=caption,
                        row=title,
                        reason=(
                            f"`{_MET_HEADER}` 값 `{row[met_index]}` 을 "
                            f"`{_MET_YES}`/`{_MET_NO}` 로 읽을 수 없다 — "
                            "읽지 못하면 실행 경로 판정이 조용히 꺼진다"
                        ),
                    )
                )
                continue
            if not verdict:
                continue
            dead = [token for token in tokens if token in (_NEVER, _UNWIRED)]
            if dead:
                violations.append(
                    Violation(
                        table=caption,
                        row=title,
                        reason=(
                            f"`{_MET_HEADER} = {_MET_YES}` 인데 실행 경로가 "
                            f"`{'`·`'.join(dead)}` 다 — 돌지 않는 근거로 종료 조건을 "
                            "닫을 수 없다"
                        ),
                    )
                )
    return violations


def read_ci_job_names(workflow_yaml: str) -> frozenset[str]:
    """워크플로의 `jobs:` 이름을 읽는다.

    문자열 grep 이 아니라 YAML 파싱이다 — 주석이나 `run:` 본문에 잡 이름처럼 생긴
    문자열이 있으면 grep 은 없는 잡을 있다고 답하고, 그 순간 규칙 3이 무의미해진다.
    """
    document = yaml.safe_load(workflow_yaml)
    if not isinstance(document, dict):
        raise AssertionError(
            "ci.yml 최상위가 매핑이 아니다 — 워크플로 파일이 아니거나 파싱이 깨졌다."
        )
    jobs = document.get("jobs")
    if not isinstance(jobs, dict):
        raise AssertionError("ci.yml 에 `jobs:` 매핑이 없다 — 잡 이름을 대조할 근거가 사라졌다.")
    if len(jobs) == 0:
        raise AssertionError("ci.yml 의 `jobs:` 가 비었다 — 어떤 `ci:` 표기도 통과할 수 없다.")
    return frozenset(str(name) for name in jobs)


def read_tracked_files(repo_root: Path) -> frozenset[str]:
    """git 이 추적하는 파일 목록. `-z` 로 받는 이유는 한글 경로가 이 저장소에 실재하고,
    기본 출력은 그것을 따옴표로 감싸 escape 하기 때문이다(그러면 대조가 조용히 빗나간다).
    """
    completed = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=repo_root,
        capture_output=True,
        check=True,
    )
    names = completed.stdout.decode("utf-8").split("\0")
    return frozenset(name for name in names if name)


def _render(violations: Sequence[Violation]) -> str:
    return "\n".join(f"  {number}. {violation}" for number, violation in enumerate(violations, 1))


# --- 저장소 실물 판정 ---------------------------------------------------------


@pytest.fixture(scope="module")
def target_tables() -> list[Table]:
    """`00_progress.md` 에서 규약 대상 표만 골라 온다."""
    return select_target_tables(parse_tables(_PROGRESS_PATH.read_text(encoding="utf-8")))


@pytest.fixture(scope="module")
def repo_context() -> JudgeContext:
    """실제 ci.yml 의 잡 이름과 실제 git 추적 목록을 판정에 넣는다."""
    tracked = read_tracked_files(_REPO_ROOT)

    def is_tracked_file(target: str) -> bool:
        return target in tracked

    return JudgeContext(
        ci_jobs=read_ci_job_names(_CI_WORKFLOW_PATH.read_text(encoding="utf-8")),
        is_tracked_file=is_tracked_file,
    )


def test_규약_대상_표_네_개를_찾는다(target_tables: list[Table]) -> None:
    """표를 못 찾으면 아래 판정이 조용히 0건 검사로 바뀐다 — 그 경로를 먼저 막는다."""
    captions = [table.caption for table in target_tables]
    assert len(target_tables) == EXPECTED_TARGET_TABLES, (
        f"규약 대상 표가 {len(target_tables)}개다 (기대 {EXPECTED_TARGET_TABLES}개). "
        f"찾은 표: {captions}.\n"
        "  Phase 표를 **정당하게 늘렸다면** `EXPECTED_TARGET_TABLES` 를 올려라 — "
        "그 diff 가 '판정 범위를 건드렸다'는 신호로 리뷰에 올라가는 것이 이 상수의 값어치다.\n"
        "  줄었다면 헤더(`종료 조건`+`충족`, `게이트`)가 바뀐 것이고, 그때 이 파일은 "
        "아무것도 검사하지 않게 된다."
    )


def test_대상_표에_실행_경로_열이_있다(target_tables: list[Table]) -> None:
    """규칙 1. 열이 사라지면 나머지 네 규칙이 통째로 무력해지므로 따로 세운다."""
    missing = [table.caption for table in target_tables if _REACH_HEADER not in table.headers]
    assert not missing, (
        f"`{_REACH_HEADER}` 열이 없는 표: {missing}. "
        "어휘 정본은 .claude/skills/kotlin-migration/SKILL.md 의 "
        "「선언한 범위와 실제 도달을 대조한다」 절이다."
    )


def test_판정이_실제로_행을_보고_있다(target_tables: list[Table]) -> None:
    """아래 판정이 **0건 검사로 통과**하는 경로를 막는다.

    이 하네스가 이미 겪은 실패다 — "미충족 0" 이 항목 0개에서 참이 되던 구멍(리뷰
    A-1/X-08). 표를 못 읽거나 `충족 = 예` 행을 하나도 못 골라도 규칙 2는 조용히
    통과하므로, 기대 개수를 세워 그 상태를 실패로 만든다.

    **정확 일치이지 하한이 아니다.** 여유를 두면 그만큼 조용히 줄어들 수 있고, 실측에서
    지워도 안 걸리는 행 중에 **품질 게이트 행**(근거 6번)이 있었다 — 알려진 약한 자리의
    기록이 흔적 없이 사라지는 것이 이 검사가 막아야 할 바로 그 일이다.
    """
    rows = sum(len(table.rows) for table in target_tables)
    met_yes = 0
    tokens = 0
    for table in target_tables:
        if _REACH_HEADER not in table.headers:
            continue
        reach_index = table.headers.index(_REACH_HEADER)
        met_index = table.headers.index(_MET_HEADER) if _MET_HEADER in table.headers else None
        for row in table.rows:
            if len(row) != len(table.headers):
                continue
            tokens += len(split_reach_tokens(row[reach_index]))
            if met_index is not None and met_verdict(row[met_index]) is True:
                met_yes += 1

    _guide = (
        "표를 정당하게 고쳤다면 이 상수를 갱신하라 — 그 diff 가 "
        "'판정 범위를 건드렸다'는 신호로 리뷰에 올라가는 것이 이 상수의 값어치다."
    )
    assert rows == EXPECTED_ROWS, (
        f"대상 행이 {rows}개다 (기대 {EXPECTED_ROWS}). 표가 사라졌거나 파싱이 깨졌다. {_guide}"
    )
    assert met_yes == EXPECTED_MET_YES, (
        f"`충족 = 예` 행이 {met_yes}개다 (기대 {EXPECTED_MET_YES}). "
        f"규칙 2가 검사할 대상이 사라지면 그 규칙은 항상 통과한다. {_guide}"
    )
    assert tokens == EXPECTED_REACH_TOKENS, (
        f"실행 경로 표기가 {tokens}개다 (기대 {EXPECTED_REACH_TOKENS}). "
        f"규칙 3·4·5가 검사할 대상이 사라졌다. {_guide}"
    )


def test_진행상태표의_실행_경로가_규약을_지킨다(
    target_tables: list[Table], repo_context: JudgeContext
) -> None:
    """규칙 2~5를 실물에 적용한다.

    이 테스트가 실패한다면 표가 틀린 것이지 검사가 틀린 것이 아니다. **실패를 없애는
    올바른 방법은 값을 부풀리거나 규칙을 느슨하게 하는 것이 아니라, 그 행의 게이트를
    실제로 돌게 배선하거나 `충족` 판정을 되돌리는 것이다.**
    """
    violations = judge_tables(target_tables, repo_context)
    assert not violations, (
        f"실행 경로 규약 위반 {len(violations)}건:\n{_render(violations)}\n"
        "  (규칙 3 — 이 게이트가 지금 어디서 도는가. 도달 0을 특히 의심한다)"
    )


# --- 음성 대조 (합성 입력) -----------------------------------------------------
#
# 아래는 저장소 상태와 무관하다. 위 판정이 "입력이 무해해서" 통과한 것이 아님을
# 보이는 자리이며, 다섯 규칙이 각각 정확히 어떤 입력에서 실패하는지 고정한다.

#: **실제 CI 잡(`quality`·`frontend`·`kotlin`)과 일부러 다르게** 둔다. 같은 이름을 쓰면
#: 컨텍스트 치환이 실제로 먹는지, 아니면 어딘가에서 진짜 ci.yml 을 읽고 있는지 구분되지
#: 않는다 — 합성 입력의 값어치가 사라진다.
_FAKE_JOBS: Final = frozenset({"unit", "lint", "e2e"})
_FAKE_TRACKED_PATH: Final = "docs/추적되는-산출물.md"


def _fake_context() -> JudgeContext:
    def is_tracked_file(target: str) -> bool:
        return target == _FAKE_TRACKED_PATH

    return JudgeContext(ci_jobs=_FAKE_JOBS, is_tracked_file=is_tracked_file)


def _goal_table(met: str, reach: str) -> str:
    """종료 조건 표 한 줄짜리 합성 마크다운."""
    return (
        "## 합성 Phase 표\n"
        "\n"
        f"| 종료 조건 | 충족 | {_REACH_HEADER} | 근거 |\n"
        "|---|---|---|---|\n"
        f"| 어떤 종료 조건 | {met} | {reach} | 어떤 근거 |\n"
    )


def _judge_markdown(markdown: str) -> list[Violation]:
    return judge_tables(select_target_tables(parse_tables(markdown)), _fake_context())


def _sole_reason(markdown: str) -> str:
    violations = _judge_markdown(markdown)
    assert len(violations) == 1, f"위반이 정확히 1건이어야 한다: {[str(v) for v in violations]}"
    return violations[0].reason


def _gate_table(reach: str) -> str:
    """게이트 표 한 줄짜리 합성 마크다운. `충족` 열이 **없는** 모양이다."""
    return (
        "## 합성 게이트 표\n"
        "\n"
        f"| 게이트 | {_REACH_HEADER} | 상태 |\n"
        "|---|---|---|\n"
        f"| 어떤 게이트 | {reach} | 미실행 |\n"
    )


def test_대조군_정상_표는_통과한다() -> None:
    """어휘 6종을 모두 쓴 정상 표. 이게 실패하면 아래 음성 대조가 무의미하다."""
    markdown = (
        "## 합성 정상 표\n"
        "\n"
        f"| 종료 조건 | 충족 | {_REACH_HEADER} | 근거 |\n"
        "|---|---|---|---|\n"
        "| CI 로 도는 행 | 예 | `ci:unit` | 근거 |\n"
        "| 여러 경로가 도는 행 | 예 | `ci:lint` · `ci:e2e` | 근거 |\n"
        "| 로컬로만 도는 행 | 예 | `local:uv run pytest tests/golden -m llm` | 근거 |\n"
        f"| 한 번 재고 만 행 | 예 | `1회성:{_FAKE_TRACKED_PATH}` | 근거 |\n"
        "| 결정으로 닫은 행 | 예 | `결정:2026-08-12` | 근거 |\n"
        "| 복합 표기로 닫힌 행 | 아니오 — **1/11 생성** | `안 돎` | 근거 |\n"
        "| 배선이 없는 행 | 아니오 | `미배선` | 근거 |\n"
        "| 상대 경로 러너 | 예 | `local:./gradlew build` | 근거 |\n"
        "\n" + _gate_table("`ci:e2e`")
    )
    assert _judge_markdown(markdown) == []


def test_음성1_충족_예인데_안_돎이면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", "`안 돎`"))
    assert _NEVER in reason
    assert "충족 = 예" in reason
    # `미배선` 도 같다.
    assert _UNWIRED in _sole_reason(_goal_table("예", "`미배선`"))


def test_음성2_충족_예인데_실행_경로가_비면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", " "))
    assert "비었다" in reason
    # `-` 도 같은 취급이다 — 안 적은 것과 줄표를 그은 것은 같은 상태다.
    assert "비었다" in _sole_reason(_goal_table("예", "-"))


def test_음성3_존재하지_않는_CI_잡이면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", "`ci:golden`"))
    assert "ci.yml" in reason
    assert "golden" in reason
    # `충족 = 아니오` 인 행도 똑같이 걸린다 — 이 규칙은 충족 값과 무관하다.
    assert "ci.yml" in _sole_reason(_goal_table("아니오", "`ci:nightly`"))
    # 잡 이름 모양 자체가 GitHub 규칙(영숫자·`-`·`_`·`.`)을 벗어나면 어휘 밖으로 잡힌다.
    assert "어휘 밖 표기" in _sole_reason(_goal_table("예", "`ci:없는 잡`"))
    # 실제 CI 잡 이름은 합성 컨텍스트에서 **통과하면 안 된다** — 통과한다면 컨텍스트
    # 치환이 먹지 않고 어딘가에서 진짜 ci.yml 을 읽고 있다는 뜻이다.
    assert "ci.yml" in _sole_reason(_goal_table("예", "`ci:quality`"))


def test_음성4_존재하지_않는_1회성_경로면_실패한다() -> None:
    reason = _sole_reason(_goal_table("예", "`1회성:docs/없는-산출물.md`"))
    assert "git 이 추적하는" in reason


def test_음성5_어휘_밖_표기는_실패한다() -> None:
    for outside in ("`가끔 돎`", "`ci`", "`결정:2026년 8월`", "`1회성:`", "`local:`"):
        reason = _sole_reason(_goal_table("예", outside))
        assert "어휘 밖 표기" in reason, f"{outside} 이 어휘 밖으로 잡히지 않았다: {reason}"


def test_음성6_실행_경로_열이_없으면_실패한다() -> None:
    markdown = (
        "## 열이 사라진 합성 표\n"
        "\n"
        "| 종료 조건 | 충족 | 근거 |\n"
        "|---|---|---|\n"
        "| 어떤 종료 조건 | 예 | 어떤 근거 |\n"
    )
    reason = _sole_reason(markdown)
    assert f"`{_REACH_HEADER}` 열이 없다" in reason
    # 게이트 표에서도 같아야 한다 — 표 모양이 달라도 규칙은 하나다.
    gate_markdown = "## 게이트 표\n\n| 게이트 | 상태 |\n|---|---|\n| 어떤 게이트 | 미실행 |\n"
    assert f"`{_REACH_HEADER}` 열이 없다" in _sole_reason(gate_markdown)


# --- 음성 대조 F1~F5 (독립 검증 레인이 뚫은 통과 경로) --------------------------
#
# 아래 다섯은 적대적 조작이 아니라 **평범한 문서 편집으로 도달**하던 통과 경로다.
# 각각 정확히 한 건씩 실패하는 것을 고정한다.


def test_음성F1_충족을_예_아니오로_읽을_수_없으면_실패한다() -> None:
    """`O`·`Y`·`완료`·`✅` 로 적으면 예전에는 규칙 3이 **침묵 건너뛰었다.**"""
    for unreadable in ("O", "Y", "완료", "✅", "-", "충족"):
        reason = _sole_reason(_goal_table(unreadable, "`안 돎`"))
        assert "읽을 수 없다" in reason, f"`{unreadable}` 이 조용히 통과했다: {reason}"


def test_음성F1_낱말_경계라_복합_표기는_읽히고_다른_낱말은_안_읽힌다() -> None:
    """양쪽 오독을 동시에 고정한다 — 복합 표기는 읽히고, `예`로 시작하는 딴 낱말은 안 읽힌다.

    접두 판정이던 시절의 버그가 근거다. `예정` 은 접두가 `예` 라 **충족으로 읽혔고**,
    뜻이 "아직"인 행에 규칙 3이 발동해 "충족 = 예인데 안 돎"이라고 **거짓 고발**했다.
    정상 문서를 고발하는 검사는 한계가 아니라 버그다.
    """
    # (가) 읽혀야 하는 것 — 완전 일치와, 이 문서의 관용인 복합 표기.
    assert met_verdict("예") is True
    assert met_verdict("아니오") is False
    assert met_verdict("아니오 — 1/11 생성") is False, (
        "역사 행 `아니오 — **1/11 생성**` 이 읽히지 않으면 정상 행이 위반으로 뒤집힌다."
    )
    assert met_verdict("예 — 부분") is True
    assert met_verdict("예(부분)") is True

    # (나) 읽히면 안 되는 것 — `예` 로 시작하지만 뜻이 다른 낱말들. 전부 이 문서의
    #      관용 어휘권 안에 있고, 특히 `예정` 은 뜻이 정반대다.
    for other_word in ("예정", "예외 — 범위 밖", "예상", "예비", "예외"):
        assert met_verdict(other_word) is None, (
            f"`{other_word}` 이 충족으로 읽혔다 — 접두 판정의 거짓 고발 버그가 되돌아왔다."
        )
    assert met_verdict("완료") is None

    # (다) 거짓 고발이 실제로 사라졌는가. `예정` + `안 돎` 은 규칙 3이 발동할 행이
    #      아니다 — 나와야 하는 위반은 "읽을 수 없다" 하나뿐이다.
    reason = _sole_reason(_goal_table("예정", "`안 돎`"))
    assert "읽을 수 없다" in reason, f"`예정` 이 거짓 고발됐다: {reason}"
    assert "충족 = 예" not in reason, f"`예정` 을 충족으로 읽고 고발했다: {reason}"

    # (라) 그러면서 `예 — 부분` 은 여전히 `예` 이므로 규칙 3이 **적용된다**(건너뛰지 않는다).
    assert "충족 = 예" in _sole_reason(_goal_table("예 — 부분", "`안 돎`"))
    # (마) 그리고 복합 `아니오` 행은 규칙 3을 건너뛴 채 통과한다 — 역사 행 그대로.
    assert _judge_markdown(_goal_table("아니오 — 1/11 생성", "`안 돎`")) == []


def test_음성F2_게이트_표의_실행_경로를_지워도_실패한다() -> None:
    """게이트 표엔 `충족` 열이 없어 규칙 3이 구조적으로 안 닿는다 — 규칙 2가 유일한 방어다."""
    for erased in (" ", "-"):
        reason = _sole_reason(_gate_table(erased))
        assert "비었다" in reason, f"게이트 표의 빈 실행 경로가 통과했다: {reason}"


def test_음성F3_local_이_산문_약속이면_실패한다() -> None:
    """`local:` 이 임의 문자열 자유 통과 카드가 되던 경로."""
    for prose in ("`local:언젠가 돌릴 예정`", "`local:아직 안 정함`", "`local:나중에 배선`"):
        reason = _sole_reason(_goal_table("예", prose))
        assert "실행 파일 이름 꼴이 아니" in reason, f"산문 약속이 통과했다: {prose} → {reason}"

    # 한계 — 첫 낱말만 본다. `local:TBD 예정` 처럼 첫 낱말이 ASCII 면 통과한다.
    # 모든 산문을 막으려면 명령 실재를 확인해야 하고, 그건 이 검사의 범위 밖이다.
    assert _judge_markdown(_goal_table("예", "`local:TBD 예정`")) == []

    # 러너 이름을 화이트리스트로 못 박지 않는다 — 정당한 미래 값이 막히면 안 된다.
    for runner in (
        "`local:uv run pytest tests/golden -m llm`",
        "`local:make verify`",
        "`local:npm run test -- --run`",
        "`local:./gradlew build --no-daemon`",
        "`local:.claude/skills/x/scripts/run.sh`",
    ):
        assert _judge_markdown(_goal_table("예", runner)) == [], f"정당한 러너가 막혔다: {runner}"


def test_음성F4_1회성이_디렉터리나_미추적_파일이면_실패한다() -> None:
    """`1회성:.` 로 어떤 행이든 닫을 수 있던 경로. 존재가 아니라 **git 추적**을 본다."""
    for not_a_tracked_file in ("`1회성:.`", "`1회성:docs`", "`1회성:scratch.md`"):
        reason = _sole_reason(_goal_table("예", not_a_tracked_file))
        assert "git 이 추적하는" in reason, f"{not_a_tracked_file} 이 통과했다: {reason}"


def test_음성F5_결정_날짜가_달력에_없으면_실패한다() -> None:
    """모양만 맞는 `결정:9999-99-99` 가 통과하던 경로."""
    for impossible in ("`결정:9999-99-99`", "`결정:2026-02-30`", "`결정:2026-13-01`"):
        reason = _sole_reason(_goal_table("예", impossible))
        assert "실제 달력 날짜가 아니" in reason, f"{impossible} 이 통과했다: {reason}"

    # 윤년은 통과해야 한다 — 날짜 검사를 정규식으로 흉내 내지 않았다는 증거다.
    assert _judge_markdown(_goal_table("예", "`결정:2024-02-29`")) == []
    assert _sole_reason(_goal_table("예", "`결정:2026-02-29`"))


def test_git_추적_목록은_NUL_구분으로_읽는다() -> None:
    """한글 경로가 이 저장소에 실재한다 — 기본 출력은 그것을 escape 해 대조가 빗나간다."""
    tracked = read_tracked_files(_REPO_ROOT)
    assert "tests/test_harness_scope_reach.py" in tracked, (
        "이 테스트 파일이 git 추적 목록에 없다 — `git add` 를 하지 않았다면 "
        "이 검사의 CI 도달은 0이다."
    )
    assert not any(name.startswith('"') for name in tracked), (
        "따옴표로 감싸인 경로가 있다 — `-z` 가 빠져 한글 경로가 escape 됐다."
    )


def test_ci_잡_이름은_YAML_파싱으로_읽는다() -> None:
    """규칙 3의 근거가 grep 이 아님을 고정한다. 주석 속 문자열은 잡이 아니다."""
    workflow = (
        "name: CI\non:\n  push:\njobs:\n  quality:\n    steps: []\n  kotlin:\n    steps: []\n"
    )
    assert read_ci_job_names(workflow) == frozenset({"quality", "kotlin"})

    commented = (
        "name: CI\njobs:\n  quality:\n    # kotlin: 이건 주석이지 잡이 아니다\n    steps: []\n"
    )
    assert read_ci_job_names(commented) == frozenset({"quality"})

    for broken in ("[]\n", "name: CI\n", "name: CI\njobs: {}\n"):
        with pytest.raises(AssertionError):
            read_ci_job_names(broken)
