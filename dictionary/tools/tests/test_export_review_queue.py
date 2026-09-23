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

import contextlib
import io
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
                "id": str(entry_id),
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

    def test_id_is_first_column_and_identifies_the_row(self) -> None:
        """같은 term에 엔트리가 여럿이면 검수 SQL이 `WHERE id IN (...)`으로 좁혀야
        한다(README·템플릿 참고) — CSV에 id가 없으면 그 행을 되짚을 수 없다."""
        id1 = _insert(self.conn, term="수리", easy_term="받음", checksum="q211",
                      definition="받는 일입니다.")
        id2 = _insert(self.conn, term="수리", easy_term="받아들임", checksum="q212",
                      definition="받아들이는 일입니다.")
        self.assertNotEqual(id1, id2)

        rows = erq.queue_rows(self.conn)
        self.assertEqual(erq.COLUMNS[0], "id")
        self.assertEqual({row["id"] for row in rows}, {str(id1), str(id2)})

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

    def test_formula_injection_prefix_is_added_to_dangerous_cells(self) -> None:
        """`=`·`+`·`-`·`@`·탭으로 시작하는 칸은 스프레드시트가 수식으로 해석할 수
        있다(CSV 인젝션) — backend-kotlin의 CsvRfc4180.kt와 같은 트리거 집합으로
        작은따옴표를 앞세워 문자열로 고정한다."""
        _insert(self.conn, term="위험식", easy_term="=1+1", checksum="q501",
                definition="=1+1")
        out = self.tmpdir / "queue.csv"
        erq.write_queue_csv(erq.queue_rows(self.conn), out)
        text = out.read_text(encoding="utf-8-sig")

        # 원본 그대로("=1+1")는 어떤 필드에도 남아 있으면 안 되고, 앞에 '가 붙어야 한다.
        self.assertNotIn(",=1+1,", text)
        self.assertNotIn(",=1+1\r\n", text)
        self.assertIn("'=1+1", text)

    def test_formula_injection_guard_covers_full_trigger_set(self) -> None:
        cases = {
            "eq": "=SUM(A1)",
            "plus": "+1",
            "minus": "-1",
            "at": "@cmd",
            "tab": "\tterm",
        }
        for key, dangerous in cases.items():
            with self.subTest(key=key):
                escaped = erq._escape_formula_injection(dangerous)
                self.assertEqual(escaped, f"'{dangerous}")

    def test_formula_injection_guard_leaves_safe_cells_untouched(self) -> None:
        self.assertEqual(erq._escape_formula_injection("정상 문장입니다."), "정상 문장입니다.")
        self.assertEqual(erq._escape_formula_injection(""), "")


class ExportReviewQueueOnPrePrDbTestCase(unittest.TestCase):
    """실측 회귀(PR #147 독립 리뷰): 정의 검수 이력 컬럼(2026-09-23)이 없는 DB에
    이 도구를 돌리면 `v_entry_full`이 그 컬럼을 참조해 원시 `sqlite3.
    OperationalError`로 죽는다 — 검수자가 원인을 알 수 없다. 실제 파일 I/O가
    필요해(`main()`이 `mode=ro` URI로 연다) `:memory:`가 아닌 임시 파일을 쓴다.
    """

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_queue_premigration_test_"))

    def _make_pre_pr_db(self) -> Path:
        db_path = self.tmpdir / "pre_pr.sqlite3"
        conn = sqlite3.connect(str(db_path))
        try:
            conn.executescript(
                """
                CREATE TABLE entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    term TEXT NOT NULL,
                    term_norm TEXT NOT NULL,
                    easy_term TEXT NOT NULL,
                    definition TEXT,
                    replace_strategy TEXT NOT NULL,
                    risk_level TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active',
                    source_id INTEGER,
                    readability INTEGER NOT NULL,
                    confidence REAL NOT NULL,
                    checksum TEXT NOT NULL
                );
                """
            )
            conn.commit()
        finally:
            conn.close()
        return db_path

    def test_missing_columns_print_friendly_message_and_exit_1(self) -> None:
        db_path = self._make_pre_pr_db()
        out_path = self.tmpdir / "queue.csv"

        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            rc = erq.main(["--db", str(db_path), "--output", str(out_path)])

        self.assertEqual(rc, 1)
        stderr = buf_err.getvalue()
        self.assertIn("definition_reviewed_at", stderr)
        self.assertIn("definition_reviewed_by", stderr)
        self.assertIn("easydict.definition_review", stderr, "마이그레이션 CLI 실행법을 안내해야 한다")
        self.assertFalse(out_path.exists(), "실패 시 부분적인 CSV를 남기면 안 된다")


if __name__ == "__main__":
    unittest.main()
