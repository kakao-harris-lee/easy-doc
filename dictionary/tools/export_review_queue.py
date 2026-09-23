#!/usr/bin/env python3
"""검수자용 뜻풀이 검수 큐 CSV를 뽑는다 (2026-09-23, K4 준비).

`easy_dict.index.json`의 `v="reviewed"`는 **사람이 그 뜻풀이를 실제로 읽었을
때만** 붙는다(`src/easydict/definition_review.py`). 그 "읽는 일"을 하려면 읽을
목록이 있어야 한다 — 이 도구가 그 목록을 만든다.

대상은 **배포되는 엔트리(`status != 'deprecated'`) 중 뜻풀이가 비어 있지 않은
것 전부**다. `export_index()`가 색인에 싣는 것과 같은 모집단이라, 여기 없는
뜻풀이는 애초에 생성 재료가 될 수 없다.

정렬은 **위험한 것부터**다. `replace_strategy='keep'`(바꾸지 않고 뜻풀이로만
설명하는 말)과 `risk_level='high'`(자동 치환에서 빠져 사람 검수 큐로 가는 말)가
앞 묶음으로 오고, 나머지가 뒤 묶음으로 온다. 두 묶음 안에서는 표제어 가나다순
(코드포인트 순)이다. 검수 시간이 한정돼 있을 때 **그 뜻풀이가 그대로 사용자
화면과 LLM 프롬프트에 들어가는 말**을 먼저 보게 하려는 것이다.

`reviewed_at`/`reviewed_by`는 현재 값을 그대로 싣는다 — 같은 큐를 다시 뽑아도
이미 누가 무엇을 봤는지 한눈에 보이고, 빈 칸이 남은 일이다.

Excel이 한글을 깨뜨리지 않게 UTF-8 **BOM**을 붙여 쓴다(`utf-8-sig`).

**읽기 전용이다** — `dist/`에도 `data/`에도 아무것도 쓰지 않는다(`--output`으로
지정한 CSV 하나만 만든다). 표준 라이브러리만 쓴다.

사용법:

    python3 tools/export_review_queue.py --output data/reviews/2026-09-23-definition-review-queue.csv

검수 절차 전체(큐 → 검수자 기입 → 검수 SQL → 재빌드 → 내보내기)는
README의 「검수된 정의(v=reviewed) 공급」 절에 있다.
"""

from __future__ import annotations

import argparse
import csv
import sqlite3
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB_PATH = REPO_ROOT / "dist" / "easy_dict.sqlite3"

# CSV 헤더. 검수자가 채우는 칸은 마지막 두 개다.
COLUMNS: tuple[str, ...] = (
    "term",
    "easy_term",
    "definition",
    "replace_strategy",
    "risk_level",
    "sources",
    "reviewed_at",
    "reviewed_by",
)

# 뜻풀이가 그대로 사용자·LLM에게 설명으로 나가는 자리라 먼저 보는 묶음.
_URGENT_STRATEGY = "keep"
_URGENT_RISK = "high"

_QUEUE_SQL = """
    SELECT term, easy_term, definition, replace_strategy, risk_level,
           source_code, definition_reviewed_at, definition_reviewed_by, id
      FROM v_entry_full
     WHERE status != 'deprecated'
       AND definition IS NOT NULL
       AND trim(definition) != ''
"""


def _text(value: Any) -> str:
    """NULL을 빈 칸으로 — CSV에 'None'이 찍히면 검수자가 그걸 값으로 읽는다."""
    return "" if value is None else str(value)


def _sort_key(row: dict[str, str]) -> tuple[int, str, str]:
    urgent = row["replace_strategy"] == _URGENT_STRATEGY or row["risk_level"] == _URGENT_RISK
    return (0 if urgent else 1, row["term"], row["easy_term"])


def queue_rows(conn: sqlite3.Connection) -> list[dict[str, str]]:
    """검수 큐에 실릴 행을 정렬된 채로 돌려준다 (모듈 docstring의 계약)."""
    rows = [
        {
            "term": _text(term),
            "easy_term": _text(easy_term),
            "definition": _text(definition),
            "replace_strategy": _text(replace_strategy),
            "risk_level": _text(risk_level),
            "sources": _text(source_code),
            "reviewed_at": _text(reviewed_at),
            "reviewed_by": _text(reviewed_by),
        }
        for (term, easy_term, definition, replace_strategy, risk_level,
             source_code, reviewed_at, reviewed_by, _id) in conn.execute(_QUEUE_SQL)
    ]
    rows.sort(key=_sort_key)
    return rows


def write_queue_csv(rows: list[dict[str, str]], out: Path) -> int:
    """행을 CSV로 쓰고 쓴 행 수를 돌려준다. Excel용 UTF-8 BOM(`utf-8-sig`)."""
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(COLUMNS))
        writer.writeheader()
        writer.writerows(rows)
    return len(rows)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db", default=str(DEFAULT_DB_PATH), help="정본 SQLite 경로 (읽기 전용)")
    p.add_argument("--output", required=True, help="쓸 CSV 경로")
    args = p.parse_args(argv)

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"오류: 정본이 없습니다: {db_path} (먼저 build.py를 실행했는지 확인)", file=sys.stderr)
        return 2

    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        rows = queue_rows(conn)
    finally:
        conn.close()

    written = write_queue_csv(rows, Path(args.output))
    urgent = sum(
        1 for r in rows
        if r["replace_strategy"] == _URGENT_STRATEGY or r["risk_level"] == _URGENT_RISK
    )
    already = sum(1 for r in rows if r["reviewed_at"] and r["reviewed_by"])
    print(
        f"{args.output}: {written}행 (먼저 볼 것 {urgent}행, 이미 검수됨 {already}행)",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
