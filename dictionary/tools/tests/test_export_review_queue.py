"""`tools/export_review_queue.py` 계약 테스트 (2026-09-23).

**실제 `dist/`는 읽지 않는다.** `schema/schema.sql`로 인메모리 SQLite를 만들어
필요한 엔트리만 넣고 확인한다.

고정하는 계약:
- 대상은 **배포되는(= deprecated 아님) 엔트리 중 뜻풀이가 있는 것** 전부.
- 위험한 것부터 본다 — `replace_strategy='keep'` 또는 `risk_level='high'`가
  앞 묶음, 나머지가 뒤 묶음, 각 묶음 안은 표제어 가나다순.
- Excel이 UTF-8로 열 수 있게 BOM을 붙인다.
- 이미 검수된 행은 `reviewed_at`/`reviewed_by`가 채워진 채로 나온다(같은 큐를
  다시 뽑아도 누가 무엇을 이미 봤는지 보인다).
"""

from __future__ import annotations

import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent.parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import export_review_queue as erq  # noqa: E402

SCHEMA_SQL_PATH = _TOOLS_DIR.parent / "schema" / "schema.sql"


def _insert(
    conn: sqlite3.Connection,
    *,
    term: str,
    easy_term: str,
    checksum: str,
    definition: str | None,
    replace_strategy: str = "substitute",
    risk_level: str = "none",
    status: str = "active",
    reviewed_at: str | None = None,
    reviewed_by: str | None = None,
) -> int:
    cur = conn.execute(
        """
        INSERT INTO entries
            (term, term_norm, easy_term, definition, replace_strategy, risk_level,
             status, readability, confidence, checksum,
             definition_reviewed_at, definition_reviewed_by)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        (term, term, easy_term, definition, replace_strategy, risk_level,
         status, 1, 0.9, checksum, reviewed_at, reviewed_by),
    )
    return int(cur.lastrowid)


class ExportReviewQueueTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        self.conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_queue_test_"))

    def tearDown(self) -> None:
        self.conn.close()

    def test_only_shipped_entries_with_definitions(self) -> None:
        _insert(self.conn, term="내방", easy_term="방문", checksum="q001",
                definition="찾아오는 일입니다.")
        _insert(self.conn, term="거치", easy_term="미룸", checksum="q002", definition=None)
        _insert(self.conn, term="공백", easy_term="빔", checksum="q003", definition="   ")
        _insert(self.conn, term="링크", easy_term="경기장", checksum="q004",
                definition="얼음판입니다.", status="deprecated")

        terms = [row["term"] for row in erq.queue_rows(self.conn)]
        self.assertEqual(terms, ["내방"])

    def test_risky_entries_come_first_then_alphabetical(self) -> None:
        _insert(self.conn, term="하수", easy_term="버린 물", checksum="q101",
                definition="쓰고 버린 물입니다.")
        _insert(self.conn, term="가산", easy_term="더함", checksum="q102",
                definition="더하는 일입니다.")
        _insert(self.conn, term="과태료", easy_term="법을 안 지켜서 내는 돈", checksum="q103",
                definition="법을 안 지켜서 내는 돈입니다.",
                replace_strategy="gloss", risk_level="high", status="review")
        _insert(self.conn, term="국민기초생활 보장법", easy_term="법 이름이니 그대로 씀",
                checksum="q104", definition="복지 급여의 근거가 되는 법입니다.",
                replace_strategy="keep", risk_level="low", status="review")

        terms = [row["term"] for row in erq.queue_rows(self.conn)]
        self.assertEqual(terms, ["과태료", "국민기초생활 보장법", "가산", "하수"])

    def test_columns_and_existing_review_marks(self) -> None:
        self.conn.execute(
            "INSERT INTO sources (code, name) VALUES ('krdict:advanced', '한국어기초사전')"
        )
        source_id = self.conn.execute(
            "SELECT id FROM sources WHERE code = 'krdict:advanced'"
        ).fetchone()[0]
        entry_id = _insert(self.conn, term="내방", easy_term="방문", checksum="q201",
                           definition="찾아오는 일입니다.",
                           reviewed_at="2026-09-23", reviewed_by="검수자A")
        self.conn.execute("UPDATE entries SET source_id = ? WHERE id = ?", (source_id, entry_id))

        (row,) = erq.queue_rows(self.conn)
        self.assertEqual(list(row), list(erq.COLUMNS))
        self.assertEqual(
            row,
            {
                "term": "내방",
                "easy_term": "방문",
                "definition": "찾아오는 일입니다.",
                "replace_strategy": "substitute",
                "risk_level": "none",
                "sources": "krdict:advanced",
                "reviewed_at": "2026-09-23",
                "reviewed_by": "검수자A",
            },
        )

    def test_csv_has_bom_and_header(self) -> None:
        _insert(self.conn, term="내방", easy_term="방문", checksum="q301",
                definition="찾아오는 일입니다.")
        out = self.tmpdir / "queue.csv"
        written = erq.write_queue_csv(erq.queue_rows(self.conn), out)
        self.assertEqual(written, 1)

        raw = out.read_bytes()
        self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), "Excel용 UTF-8 BOM이 있어야 한다")
        first_line = raw.decode("utf-8-sig").splitlines()[0]
        self.assertEqual(first_line, ",".join(erq.COLUMNS))

    def test_empty_review_marks_are_empty_strings_not_none(self) -> None:
        """검수 전 행은 빈 칸으로 나와야 한다 — CSV에 'None'이 찍히면 안 된다."""
        _insert(self.conn, term="내방", easy_term="방문", checksum="q401",
                definition="찾아오는 일입니다.")
        (row,) = erq.queue_rows(self.conn)
        self.assertEqual(row["reviewed_at"], "")
        self.assertEqual(row["reviewed_by"], "")
        self.assertEqual(row["sources"], "")


if __name__ == "__main__":
    unittest.main()
