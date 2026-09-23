"""정의(definition)의 사람 검수 이력 계약 (2026-09-23).

`entries.definition_reviewed_at`/`definition_reviewed_by`는 **사람이 그 뜻풀이를
실제로 읽고 "생성 재료로 써도 된다"고 판단했을 때만** 채워지는 컬럼이고,
`export_index`는 그 표시가 있는 엔트리에만 `easy_dict.index.json`의 `v` 키를
`"reviewed"`로 싣는다. Kotlin 쪽 `DefinitionReviewStatus`(core)가 이 키를 읽어
R3 생성 컨텍스트와 R6 설명 패널에 쓸 뜻풀이를 고른다.

이 테스트가 고정하는 것은 두 가지다.

1. **표시가 없으면 `v` 키 자체가 없다** — 오늘의 산출물과 바이트 동일해야
   한다(이 변경은 export-neutral이다). 검수자가 표시하기 전까지 `v=reviewed`
   엔트리는 0건이어야 한다.
2. **표시는 파생시키지 않는다** — `status='active'`, `risk_level`,
   `replace_strategy`, 원천(seed) 출처, `review_note` 어느 것도 "검수 완료"로
   승격되지 않는다(docs/reports/2026-09-21-r3-implementation.md 「리뷰 리스크」).
   사람이 두 컬럼을 직접 채운 엔트리만 `v`를 얻는다.

`dist/`는 읽지도 쓰지도 않는다 — 인메모리 SQLite와 tempfile만 쓴다.
"""

from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from easydict import definition_review
from easydict import export as export_mod
from easydict.models import DEFINITION_REVIEW_MARK

REPO_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"


def _insert(
    conn: sqlite3.Connection,
    *,
    term: str,
    easy_term: str,
    checksum: str,
    definition: str | None = None,
    replace_strategy: str = "substitute",
    risk_level: str = "none",
    status: str = "active",
    review_note: str | None = None,
    reviewed_at: str | None = None,
    reviewed_by: str | None = None,
) -> int:
    cur = conn.execute(
        """
        INSERT INTO entries
            (term, term_norm, easy_term, definition, replace_strategy, risk_level,
             status, review_note, readability, confidence, checksum,
             definition_reviewed_at, definition_reviewed_by)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        (term, term, easy_term, definition, replace_strategy, risk_level,
         status, review_note, 1, 0.9, checksum, reviewed_at, reviewed_by),
    )
    return int(cur.lastrowid)


class DefinitionReviewExportTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_defreview_test_"))
        self.conn = sqlite3.connect(":memory:")
        self.conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))

    def tearDown(self) -> None:
        self.conn.close()

    def _index(self) -> dict:
        out = self.tmpdir / "easy_dict.index.json"
        export_mod.export_index(self.conn, out)
        return json.loads(out.read_text(encoding="utf-8"))

    def test_reviewed_entry_gets_v_reviewed(self) -> None:
        entry_id = _insert(
            self.conn,
            term="차상위계층", easy_term="기초생활수급자 바로 위의 저소득층",
            definition="기초생활수급자 바로 위의 소득이 적은 사람들입니다.",
            checksum="defrev0000000001",
            reviewed_at="2026-09-23", reviewed_by="검수자A",
        )
        entry = self._index()["entries"][str(entry_id)]
        self.assertEqual(entry.get("v"), DEFINITION_REVIEW_MARK)
        self.assertEqual(DEFINITION_REVIEW_MARK, "reviewed")

    def test_unreviewed_entry_has_no_v_key_at_all(self) -> None:
        """`v: null`도 `v: "unverified"`도 아니다 — 키 자체가 없어야 한다."""
        entry_id = _insert(
            self.conn,
            term="내방", easy_term="방문",
            definition="찾아오는 일입니다.",
            checksum="defrev0000000002",
        )
        entry = self._index()["entries"][str(entry_id)]
        self.assertNotIn("v", entry)

    def test_status_and_risk_do_not_promote(self) -> None:
        """status/risk_level/replace_strategy/review_note는 검수 표시가 아니다."""
        entry_id = _insert(
            self.conn,
            term="과태료", easy_term="정해진 법을 안 지켜서 내는 돈",
            definition="법을 안 지켜서 내는 돈입니다.",
            replace_strategy="gloss", risk_level="high", status="review",
            review_note="2026-09-12: 뜻풀이 문장을 다듬음. 사람 검수 완료 표시는 변경하지 않음.",
            checksum="defrev0000000003",
        )
        entry = self._index()["entries"][str(entry_id)]
        self.assertNotIn("v", entry)

    def test_empty_definition_never_gets_v(self) -> None:
        """뜻풀이가 없으면(빈 문자열 포함) 검수 표시가 있어도 생성 재료가 못 된다."""
        no_def = _insert(
            self.conn,
            term="수리", easy_term="받음", definition=None,
            checksum="defrev0000000004",
            reviewed_at="2026-09-23", reviewed_by="검수자A",
        )
        blank_def = _insert(
            self.conn,
            term="거치", easy_term="미룸", definition="   ",
            checksum="defrev0000000005",
            reviewed_at="2026-09-23", reviewed_by="검수자A",
        )
        entries = self._index()["entries"]
        self.assertNotIn("v", entries[str(no_def)])
        self.assertNotIn("v", entries[str(blank_def)])

    def test_reviewer_name_is_required(self) -> None:
        """시각만 있고 사람이 없으면 출처가 아니다 — 두 컬럼이 다 차야 표시된다."""
        at_only = _insert(
            self.conn,
            term="납부", easy_term="내기", definition="돈을 내는 일입니다.",
            checksum="defrev0000000006", reviewed_at="2026-09-23",
        )
        by_only = _insert(
            self.conn,
            term="신청", easy_term="냄", definition="서류를 내는 일입니다.",
            checksum="defrev0000000007", reviewed_by="검수자A",
        )
        entries = self._index()["entries"]
        self.assertNotIn("v", entries[str(at_only)])
        self.assertNotIn("v", entries[str(by_only)])

    def test_other_index_keys_are_untouched(self) -> None:
        """검수 표시가 붙어도 기존 키(t/e/d/s/r/p/g/c/x)의 값은 그대로다."""
        entry_id = _insert(
            self.conn,
            term="거주", easy_term="살다", definition="사는 일입니다.",
            checksum="defrev0000000008",
            reviewed_at="2026-09-23T00:00:00Z", reviewed_by="검수자A",
        )
        entry = self._index()["entries"][str(entry_id)]
        self.assertEqual(entry["t"], "거주")
        self.assertEqual(entry["e"], "살다")
        self.assertEqual(entry["d"], "사는 일입니다.")
        self.assertEqual(set(entry) - {"v"}, {"t", "e", "d", "s", "r", "p", "g", "c", "x"})

    def test_review_note_still_never_leaks(self) -> None:
        """검수 표시가 붙은 엔트리도 내부 검수 메모는 색인에 싣지 않는다."""
        entry_id = _insert(
            self.conn,
            term="환수", easy_term="다시 거두어들임",
            definition="이미 준 돈을 다시 거두어들이는 일입니다.",
            review_note="2026-09-23 검수: 뜻풀이 확인함.",
            checksum="defrev0000000009",
            reviewed_at="2026-09-23", reviewed_by="검수자A",
        )
        entry = self._index()["entries"][str(entry_id)]
        self.assertEqual(entry.get("v"), DEFINITION_REVIEW_MARK)
        self.assertNotIn("review_note", entry)
        self.assertNotIn("검수", json.dumps(entry, ensure_ascii=False))


class EnsureDefinitionReviewColumnsTestCase(unittest.TestCase):
    """기존 `dist/easy_dict.sqlite3`처럼 이 컬럼이 없던 시절 만들어진 DB를 메운다
    (`review_notes.ensure_review_note_column()`과 같은 처방)."""

    def test_adds_columns_once_and_is_idempotent(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            conn.execute(
                """
                CREATE TABLE entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    term TEXT NOT NULL,
                    definition TEXT
                )
                """
            )
            before = {row[1] for row in conn.execute("PRAGMA table_info(entries)")}
            self.assertNotIn("definition_reviewed_at", before)

            self.assertTrue(definition_review.ensure_definition_review_columns(conn))
            after = {row[1] for row in conn.execute("PRAGMA table_info(entries)")}
            self.assertIn("definition_reviewed_at", after)
            self.assertIn("definition_reviewed_by", after)

            self.assertFalse(
                definition_review.ensure_definition_review_columns(conn),
                "이미 있는 컬럼을 재추가하려 하면 안 된다",
            )
        finally:
            conn.close()

    def test_current_schema_sql_already_declares_columns(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
            self.assertFalse(
                definition_review.ensure_definition_review_columns(conn),
                "schema.sql이 이미 두 컬럼을 선언하고 있어야 한다",
            )
            # v_entry_full(export가 읽는 뷰)에서도 선택 가능해야 한다.
            conn.execute(
                "SELECT definition_reviewed_at, definition_reviewed_by FROM v_entry_full"
            ).fetchall()
        finally:
            conn.close()


class IsDefinitionReviewedTestCase(unittest.TestCase):
    def test_rule(self) -> None:
        f = definition_review.is_definition_reviewed
        self.assertTrue(f("뜻풀이입니다.", "2026-09-23", "검수자A"))
        self.assertFalse(f("뜻풀이입니다.", None, None))
        self.assertFalse(f("뜻풀이입니다.", "2026-09-23", None))
        self.assertFalse(f("뜻풀이입니다.", None, "검수자A"))
        self.assertFalse(f(None, "2026-09-23", "검수자A"))
        self.assertFalse(f("  ", "2026-09-23", "검수자A"))
        self.assertFalse(f("뜻풀이입니다.", "  ", "검수자A"))


if __name__ == "__main__":
    unittest.main()
