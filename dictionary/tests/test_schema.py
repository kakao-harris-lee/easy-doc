"""schema/schema.sql 계약 테스트 (DESIGN.md §3, §8).

schema.sql은 다른 어떤 모듈에도 의존하지 않으므로(순수 SQLite DDL),
이 파일은 PYTHONPATH 없이도 항상 돌아간다. easydict 패키지를 import하지 않는다.
"""

from __future__ import annotations

import sqlite3
import unittest
from pathlib import Path

SCHEMA_SQL_PATH = Path(__file__).resolve().parent.parent / "schema" / "schema.sql"


def _load_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))


def _minimal_entry_sql(checksum: str = "deadbeef00000001") -> str:
    return (
        "INSERT INTO entries "
        "(term, term_norm, easy_term, replace_strategy, risk_level, readability, confidence, checksum) "
        f"VALUES ('내방','내방','방문','substitute','none',1,0.9,'{checksum}')"
    )


class TestSchemaFileExists(unittest.TestCase):
    def test_schema_sql_file_exists(self) -> None:
        self.assertTrue(SCHEMA_SQL_PATH.is_file(), f"schema.sql not found at {SCHEMA_SQL_PATH}")


class TestSchemaRerunSafety(unittest.TestCase):
    """재실행 안전성: 같은 스크립트를 2회 연속 실행해도 무오류여야 한다."""

    def test_schema_executes_twice_without_error(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            _load_schema(conn)  # 1회차
            _load_schema(conn)  # 2회차 - IF NOT EXISTS 로 재실행 안전해야 함
        finally:
            conn.close()

    def test_tag_seed_not_duplicated_on_rerun(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            _load_schema(conn)
            _load_schema(conn)
            count = conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0]
            self.assertEqual(count, 10, "태그 표준값 10개가 재실행 후에도 중복 없이 10개여야 한다")
        finally:
            conn.close()

    def test_expected_tables_and_view_present(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            _load_schema(conn)
            tables = {
                r[0]
                for r in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                )
            }
            expected = {
                "meta", "sources", "entries", "tags", "entry_tags",
                "variants", "examples", "relations", "embeddings",
            }
            self.assertTrue(expected.issubset(tables), f"누락된 테이블: {expected - tables}")

            views = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='view'")}
            self.assertIn("v_entry_full", views)
        finally:
            conn.close()


class TestEntriesCheckConstraints(unittest.TestCase):
    """entries의 replace_strategy/risk_level/readability/confidence/status CHECK 제약."""

    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        _load_schema(self.conn)

    def tearDown(self) -> None:
        self.conn.close()

    def _insert(self, **overrides) -> None:
        base = dict(
            term="x", term_norm="x", easy_term="y",
            replace_strategy="substitute", risk_level="none",
            readability=2, confidence=0.5, status="active",
            checksum="deadbeef00000099",
        )
        base.update(overrides)
        cols = ", ".join(base.keys())
        placeholders = ", ".join("?" for _ in base)
        self.conn.execute(
            f"INSERT INTO entries ({cols}) VALUES ({placeholders})",
            list(base.values()),
        )

    def test_valid_entry_insert_succeeds(self) -> None:
        self._insert()  # 예외 없이 통과해야 함

    def test_replace_strategy_rejects_invalid_value(self) -> None:
        # 안전장치 핵심 회귀 테스트: 과태료를 substitute로 잘못 넣는 실수를 흉내낸다.
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert(term="과태료", replace_strategy="wrong")

    def test_risk_level_rejects_invalid_value(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert(risk_level="extreme")

    def test_readability_rejects_out_of_range(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert(readability=5)
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert(readability=0)

    def test_confidence_rejects_out_of_range(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert(confidence=1.5)
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert(confidence=-0.1)

    def test_status_rejects_invalid_value(self) -> None:
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert(status="bogus")


class TestEntriesUniqueConstraint(unittest.TestCase):
    """UNIQUE(term_norm, easy_term): 같은 원어 + 다른 순화어는 허용, 완전중복만 차단 (§3.2)."""

    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        _load_schema(self.conn)

    def tearDown(self) -> None:
        self.conn.close()

    def test_same_term_norm_different_easy_term_allowed(self) -> None:
        self.conn.execute(
            "INSERT INTO entries (term, term_norm, easy_term, replace_strategy, risk_level, "
            "readability, confidence, checksum) VALUES "
            "('내방','내방','방문','substitute','none',1,0.9,'c1')"
        )
        # 같은 term_norm, 다른 easy_term -> 문맥별 대안으로 허용되어야 함
        self.conn.execute(
            "INSERT INTO entries (term, term_norm, easy_term, replace_strategy, risk_level, "
            "readability, confidence, checksum) VALUES "
            "('내방','내방','찾아옴','substitute','none',1,0.9,'c2')"
        )
        count = self.conn.execute("SELECT COUNT(*) FROM entries WHERE term_norm='내방'").fetchone()[0]
        self.assertEqual(count, 2)

    def test_exact_duplicate_term_norm_and_easy_term_rejected(self) -> None:
        self.conn.execute(
            "INSERT INTO entries (term, term_norm, easy_term, replace_strategy, risk_level, "
            "readability, confidence, checksum) VALUES "
            "('내방','내방','방문','substitute','none',1,0.9,'c1')"
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO entries (term, term_norm, easy_term, replace_strategy, risk_level, "
                "readability, confidence, checksum) VALUES "
                "('내방','내방','방문','gloss','high',2,0.5,'c2')"
            )


class TestVariantsUniqueConstraint(unittest.TestCase):
    """UNIQUE(surface, entry_id) (2026-08-27 변경, surface_norm 기준에서 교체).

    회귀 배경: normalize.py에 공백 삽입형 변형형이 추가됐다. 표제어의 각
    음절 사이에 공백을 하나씩 끼운 형태들('해 외이주법'/'해외 이주법'/
    '해외이 주법'/'해외이주 법')은 공백을 지우면 전부 같은 surface_norm으로
    수렴한다. export_index()가 색인 키로 쓰는 것은 surface_norm이 아니라
    surface(원문)이므로, 유일성 판정을 surface_norm 기준으로 하면 매칭에
    필요한 서로 다른 표면형이 "중복"으로 오인되어 삽입 순서에 따라 하나만
    남고 나머지가 조용히 버려진다(재현성도 없다). surface 기준으로 바꿔
    이 문제를 해소했다 — 이 테스트가 그 계약을 고정한다.
    """

    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        _load_schema(self.conn)
        self.conn.execute(
            "INSERT INTO entries (term, term_norm, easy_term, replace_strategy, risk_level, "
            "readability, confidence, checksum) VALUES "
            "('해외이주법','해외이주법','다른 나라로 이사가는 것에 관한 법','keep','high',3,0.9,'v1')"
        )
        self.entry_id = self.conn.execute(
            "SELECT id FROM entries WHERE term='해외이주법'"
        ).fetchone()[0]

    def tearDown(self) -> None:
        self.conn.close()

    def test_same_surface_norm_different_surface_both_inserted(self) -> None:
        # 공백 삽입 위치만 다른 두 변형형: surface_norm은 둘 다 '해외이주법'으로
        # 같지만 surface(원문)는 다르므로 둘 다 살아남아야 한다.
        self.conn.execute(
            "INSERT INTO variants (entry_id, surface, surface_norm, kind) VALUES "
            "(?, '해 외이주법', '해외이주법', 'spacing')",
            (self.entry_id,),
        )
        self.conn.execute(
            "INSERT INTO variants (entry_id, surface, surface_norm, kind) VALUES "
            "(?, '해외 이주법', '해외이주법', 'spacing')",
            (self.entry_id,),
        )
        rows = self.conn.execute(
            "SELECT surface FROM variants WHERE entry_id = ? AND surface_norm = '해외이주법' ORDER BY surface",
            (self.entry_id,),
        ).fetchall()
        self.assertEqual(
            [r[0] for r in rows], ["해 외이주법", "해외 이주법"],
            "surface_norm이 같아도 surface가 다르면 둘 다 적재되어야 한다",
        )

    def test_exact_duplicate_surface_still_rejected(self) -> None:
        self.conn.execute(
            "INSERT INTO variants (entry_id, surface, surface_norm, kind) VALUES "
            "(?, '해 외이주법', '해외이주법', 'spacing')",
            (self.entry_id,),
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(
                "INSERT INTO variants (entry_id, surface, surface_norm, kind) VALUES "
                "(?, '해 외이주법', '해외이주법', 'spacing')",
                (self.entry_id,),
            )


class TestFtsSync(unittest.TestCase):
    """entries_fts (external content FTS5) 가 INSERT/UPDATE/DELETE 트리거로 동기화되는지."""

    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        _load_schema(self.conn)

    def tearDown(self) -> None:
        self.conn.close()

    def _insert_chasangwi(self) -> int:
        self.conn.execute(
            "INSERT INTO entries (term, term_norm, easy_term, definition, replace_strategy, "
            "risk_level, readability, confidence, checksum) VALUES "
            "('차상위계층','차상위계층','기초생활수급자 바로 위의 저소득층',"
            "'정부 지원을 받는 사람보다 형편이 조금 나은 사람입니다','gloss','high',2,0.75,'c10')"
        )
        return self.conn.execute("SELECT id FROM entries WHERE term='차상위계층'").fetchone()[0]

    def test_insert_is_searchable_via_fts(self) -> None:
        eid = self._insert_chasangwi()
        rows = self.conn.execute(
            "SELECT rowid FROM entries_fts WHERE entries_fts MATCH '차상위계층'"
        ).fetchall()
        self.assertIn((eid,), rows)

    def test_update_refreshes_fts_content(self) -> None:
        eid = self._insert_chasangwi()
        self.conn.execute(
            "UPDATE entries SET definition = '완전히 새로운 정의문장' WHERE id = ?", (eid,)
        )
        rows = self.conn.execute(
            "SELECT rowid FROM entries_fts WHERE entries_fts MATCH '새로운'"
        ).fetchall()
        self.assertIn((eid,), rows)

    def test_delete_removes_from_fts(self) -> None:
        eid = self._insert_chasangwi()
        self.conn.execute("DELETE FROM entries WHERE id = ?", (eid,))
        rows = self.conn.execute(
            "SELECT rowid FROM entries_fts WHERE entries_fts MATCH '차상위계층'"
        ).fetchall()
        self.assertEqual(rows, [])

    def test_updated_at_auto_refreshes_without_recursion(self) -> None:
        eid = self._insert_chasangwi()
        before = self.conn.execute("SELECT created_at, updated_at FROM entries WHERE id=?", (eid,)).fetchone()
        self.conn.execute("UPDATE entries SET caution = '주의 메모' WHERE id = ?", (eid,))
        after = self.conn.execute("SELECT updated_at FROM entries WHERE id=?", (eid,)).fetchone()
        # 트리거가 무한 재귀를 일으키면 이 지점까지 도달하지 못하거나 매우 느려진다.
        self.assertIsNotNone(after[0])
        self.assertGreaterEqual(after[0], before[1])


class TestViewEntryFull(unittest.TestCase):
    """v_entry_full 뷰가 tags/primary_tag/variant_count/example_count 를 실제로 채우는지."""

    def setUp(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        _load_schema(self.conn)
        self.conn.execute(
            "INSERT INTO entries (term, term_norm, easy_term, replace_strategy, risk_level, "
            "readability, confidence, checksum) VALUES "
            "('차상위계층','차상위계층','기초생활수급자 바로 위의 저소득층','gloss','high',2,0.75,'c20')"
        )
        self.eid = self.conn.execute("SELECT id FROM entries WHERE term='차상위계층'").fetchone()[0]

        self.conn.execute(
            "INSERT INTO entry_tags (entry_id, tag_name, is_primary) VALUES (?, 'welfare', 1)",
            (self.eid,),
        )
        self.conn.execute(
            "INSERT INTO entry_tags (entry_id, tag_name, is_primary) VALUES (?, 'admin', 0)",
            (self.eid,),
        )
        self.conn.execute(
            "INSERT INTO variants (entry_id, surface, surface_norm, kind) VALUES (?, '차상위 계층', '차상위계층', 'spacing')",
            (self.eid,),
        )
        self.conn.execute(
            "INSERT INTO examples (entry_id, before_text, after_text, is_golden) VALUES "
            "(?, '차상위계층은 신청할 수 있습니다.', '차상위계층(기초생활수급자 바로 위의 저소득층)은 신청할 수 있어요.', 1)",
            (self.eid,),
        )

    def tearDown(self) -> None:
        self.conn.close()

    def test_view_fills_aggregate_columns(self) -> None:
        row = self.conn.execute(
            "SELECT tags, primary_tag, variant_count, example_count FROM v_entry_full WHERE id = ?",
            (self.eid,),
        ).fetchone()
        tags, primary_tag, variant_count, example_count = row
        self.assertIsNotNone(tags)
        self.assertIn("welfare", tags.split(","))
        self.assertIn("admin", tags.split(","))
        self.assertEqual(primary_tag, "welfare")
        self.assertEqual(variant_count, 1)
        self.assertEqual(example_count, 1)


if __name__ == "__main__":
    unittest.main()
