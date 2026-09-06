"""src/easydict/review_notes.py 계약 테스트 (2026-09-06 caution/review_note 분리).

배경: `entries.caution`이 사용자 노출용 주의사항과 내부 검수 메모를 섞어 담고
있었다 — 프런트(TermLookupPopover)와 LLM 프롬프트(DictionaryContextLines)가
`caution`을 그대로 노출하므로 검수 메모가 그대로 유출됐다. 이 테스트는 그
분류 규칙(`classify_caution`)과 마이그레이션(`migrate_existing_cautions`,
`ensure_review_note_column`)의 계약을 고정한다.

`dist/`는 읽지도 쓰지도 않는다 — 모든 DB는 in-memory로 만든다.
"""
from __future__ import annotations

import sqlite3
import unittest
from pathlib import Path

from easydict import review_notes

REPO_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"


def _minimal_entry_sql(checksum: str) -> str:
    return (
        "INSERT INTO entries "
        "(term, term_norm, easy_term, replace_strategy, risk_level, caution, readability, confidence, checksum) "
        f"VALUES ('용어{checksum}','용어{checksum}','쉬운말{checksum}','substitute','none',?,1,0.9,'{checksum}')"
    )


class TestClassifyCautionKept(unittest.TestCase):
    """규칙 어디에도 매칭되지 않는 순수 사용자 안내문은 그대로 둔다."""

    def test_legit_caution_untouched(self) -> None:
        caution = "법령·지침의 공식 명칭(고유명사)이므로 원어를 지우거나 다른 말로 바꾸지 않는다. 그대로 유지한다."
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "kept")
        self.assertEqual(result.caution, caution)
        self.assertIsNone(result.review_note)
        self.assertFalse(result.flagged_for_human)

    def test_none_caution_is_kept(self) -> None:
        result = review_notes.classify_caution(None)
        self.assertEqual(result.action, "kept")
        self.assertIsNone(result.caution)
        self.assertIsNone(result.review_note)

    def test_needs_confirmation_marker_bypasses_pattern_and_is_kept(self) -> None:
        """MEDIUM-3(독립 리뷰): NEEDS_CONFIRMATION_MARKER([확인 필요])는 build.py가
        status=deprecated를 강제하는 제어 신호라, REVIEW_NOTE_PATTERN에 우연히
        걸리는 날짜/키워드가 같이 있어도 caution을 그대로 보존해야 한다."""
        caution = "[확인 필요] 2026-08-30 검수 전이라 법조문 원문 대조가 필요합니다."
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "kept")
        self.assertEqual(result.caution, caution)
        self.assertIsNone(result.review_note)
        self.assertIn(review_notes.NEEDS_CONFIRMATION_MARKER, result.caution)


class TestClassifyCautionMoved(unittest.TestCase):
    """전체가 검수 메모인 경우 caution을 비우고 review_note로 전부 옮긴다."""

    def test_single_sentence_memo_moved_entirely_and_flagged_for_human(self) -> None:
        """HIGH-1(독립 리뷰): 문장 경계가 없는 단일 문장 이동은 항상 사람 확인용으로
        표시한다 — 문장 부호 경계로도 못 잡는 "안내+메모 한 문장" 사고를 막는다."""
        caution = "2026-08-30 검수: easy-doc 내장 목록 대치어 채택으로 substitute 승격 (docs/consumer-overlap-policy.md §3.2)"
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "moved")
        self.assertIsNone(result.caution)
        self.assertEqual(result.review_note, caution)
        self.assertTrue(result.flagged_for_human)

    def test_first_sentence_matches_moved_and_flagged_for_human(self) -> None:
        caution = (
            "2026-08-30 검수: substitute 승격 반려 — 독자가 직접 발급·제출해야 하는 서류의 "
            "공식 명칭 — 원어를 지우면 무엇을 준비할지 알 수 없음. gloss 유지 "
            "(docs/consumer-overlap-policy.md §3.2)"
        )
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "moved")
        self.assertIsNone(result.caution)
        self.assertEqual(result.review_note, caution)
        self.assertTrue(result.flagged_for_human)

    def test_pipeline_boilerplate_not_split_even_when_only_last_sentence_matches(self) -> None:
        """krdict 자동 수집 보일러플레이트 — 앞 문장이 '[...]'로 시작하는 내부
        파이프라인 태그라 사용자 안내로 볼 수 없으므로 split하지 않고 전체를 옮긴다."""
        caution = (
            "[자동 수집, 검토 필요] 한국어기초사전 정의를 그대로 옮김. "
            "word_grade=고급(readability 3에 해당하나 현재 파이프라인은 readability를 "
            "replace_strategy에서만 파생시켜 이 값은 아직 반영되지 않음). "
            "gloss 초안으로 순화어=뜻으로 채움 — 사람이 검토 후 필요하면 substitute로 승격."
        )
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "moved")
        self.assertIsNone(result.caution)
        self.assertEqual(result.review_note, caution)


class TestClassifyCautionSplit(unittest.TestCase):
    """앞 문장은 순수 안내, 뒤 문장부터 검수 메모인 진짜 혼합형은 분리한다."""

    def test_mixed_guidance_then_memo_is_split(self) -> None:
        caution = (
            "동형이의 주의: 이 항목은 空地(빈 땅) 뜻이다. "
            "골든 코퍼스 56건에 '공지'가 한 번도 등장하지 않고, 복지·행정 문서의 "
            "'공지'는 사실상 전부 公知(알림)라 이 뜻으로 매칭되면 오변환 위험이 있어 "
            "비활성화함. 알림 뜻의 公知 엔트리는 welfare_seed_5.csv에 별도 신규 집필함 "
            "(2026-08-30 검수, consumer-overlap-policy §3.3)"
        )
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "split")
        self.assertEqual(result.caution, "동형이의 주의: 이 항목은 空地(빈 땅) 뜻이다.")
        self.assertTrue(result.review_note.startswith("골든 코퍼스 56건에"))
        self.assertFalse(result.flagged_for_human)

    def test_memo_then_guidance_is_still_split_high1_regression(self) -> None:
        """HIGH-1 실측 결함(id 1926 '면제'): 메모가 **먼저** 오고 안내가 **나중에**
        오며, 메모가 마침표 없이 ')'로 끝난다. 예전 구현은 "첫 문장이 매칭되면
        안내가 없다"고 가정해 뒤에 오는 안내까지 통째로 review_note로 넘겼다."""
        caution = (
            "2026-08-30 검수: easy-doc 내장 목록 대치어 채택으로 substitute 승격 "
            "(docs/consumer-overlap-policy.md §3.2) 돈이 아닌 의무의 면제(교육·검사 등)면 "
            "'안 내도 됨' 대신 '하지 않아도 됨'으로 푼다."
        )
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "split")
        self.assertEqual(
            result.caution,
            "돈이 아닌 의무의 면제(교육·검사 등)면 '안 내도 됨' 대신 '하지 않아도 됨'으로 푼다.",
        )
        self.assertTrue(result.review_note.startswith("2026-08-30 검수"))

    def test_id_2024_bunki_memo_then_guidance_high1_regression(self) -> None:
        """HIGH-1 실측 결함(id 2024 '분기') — 위와 같은 형태의 두 번째 사례."""
        caution = (
            "2026-08-30 검수: easy-doc 내장 목록 대치어 채택으로 substitute 승격 "
            "(docs/consumer-overlap-policy.md §3.2) '1분기'·'3분기'처럼 순번을 세는 "
            "문맥에서는 '석 달'로 바꾸지 말고 '1~3월'처럼 기간으로 풀어 쓴다."
        )
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "split")
        self.assertEqual(
            result.caution,
            "'1분기'·'3분기'처럼 순번을 세는 문맥에서는 '석 달'로 바꾸지 말고 '1~3월'처럼 기간으로 풀어 쓴다.",
        )

    def test_reference_only_prompt_exclusion_note_is_split(self) -> None:
        """MEDIUM-2(독립 리뷰, id 1783 '보건복지부'): "(참고용, 뜻풀이는 프롬프트에
        넣지 않음)"도 내부 지침이라 review_note로 옮겨야 한다."""
        caution = (
            "정부 부처의 공식 명칭(고유명사)이므로 다른 말로 바꾸지 않는다. "
            "생활보호·자활지원·사회보장·아동·노인·장애인·보건위생 등에 관한 일을 맡는 "
            "중앙정부 기관입니다(참고용, 뜻풀이는 프롬프트에 넣지 않음)."
        )
        result = review_notes.classify_caution(caution)
        self.assertEqual(result.action, "split")
        self.assertEqual(result.caution, "정부 부처의 공식 명칭(고유명사)이므로 다른 말로 바꾸지 않는다.")
        self.assertIn("참고용", result.review_note)


class TestEnsureReviewNoteColumn(unittest.TestCase):
    def test_adds_column_when_missing_and_is_idempotent(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            # review_note 없이 만들어졌던 예전 스키마를 흉내낸다.
            conn.executescript(
                """
                CREATE TABLE entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    term TEXT NOT NULL,
                    term_norm TEXT NOT NULL,
                    easy_term TEXT NOT NULL,
                    replace_strategy TEXT NOT NULL,
                    risk_level TEXT NOT NULL,
                    caution TEXT,
                    readability INTEGER NOT NULL,
                    confidence REAL NOT NULL,
                    checksum TEXT NOT NULL
                );
                """
            )
            columns_before = {row[1] for row in conn.execute("PRAGMA table_info(entries)")}
            self.assertNotIn("review_note", columns_before)

            added_first = review_notes.ensure_review_note_column(conn)
            self.assertTrue(added_first)
            columns_after = {row[1] for row in conn.execute("PRAGMA table_info(entries)")}
            self.assertIn("review_note", columns_after)

            added_second = review_notes.ensure_review_note_column(conn)
            self.assertFalse(added_second, "이미 있는 컬럼을 재추가하려 하면 안 된다")
        finally:
            conn.close()

    def test_current_schema_sql_already_has_column(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
            added = review_notes.ensure_review_note_column(conn)
            self.assertFalse(added, "schema.sql이 이미 review_note를 선언하고 있어야 한다")
        finally:
            conn.close()


class TestEnsureVEntryFullView(unittest.TestCase):
    def test_recreates_view_so_review_note_is_selectable(self) -> None:
        conn = sqlite3.connect(":memory:")
        try:
            # review_note 컬럼도, 그걸 아는 뷰도 없던 옛 스키마를 흉내낸다:
            # 먼저 최신 schema.sql을 그대로 불러오고, 일부러 뷰를 review_note가
            # 없던 옛 정의로 덮어써 재현한다.
            conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
            conn.execute("DROP VIEW v_entry_full")
            conn.execute(
                """
                CREATE VIEW v_entry_full AS
                SELECT e.id, e.term, e.caution FROM entries e
                """
            )

            with self.assertRaises(sqlite3.OperationalError):
                conn.execute("SELECT review_note FROM v_entry_full").fetchall()

            review_notes.ensure_v_entry_full_view(conn, schema_sql=SCHEMA_SQL_PATH)

            # 예외 없이 review_note를 선택할 수 있어야 한다.
            conn.execute("SELECT review_note FROM v_entry_full").fetchall()
        finally:
            conn.close()


class TestMigrateExistingCautions(unittest.TestCase):
    def _make_db(self) -> sqlite3.Connection:
        conn = sqlite3.connect(":memory:")
        conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
        return conn

    def test_migration_reclassifies_all_cautions_and_reports_counts(self) -> None:
        conn = self._make_db()
        try:
            legit = "법령·지침의 공식 명칭(고유명사)이므로 원어를 지우거나 다른 말로 바꾸지 않는다. 그대로 유지한다."
            memo = "2026-08-30 검수: easy-doc 내장 목록 대치어 채택으로 substitute 승격 (docs/consumer-overlap-policy.md §3.2)"
            mixed = (
                "동형이의 주의: 이 항목은 空地(빈 땅) 뜻이다. 골든 코퍼스 56건에 등장하지 "
                "않아 비활성화함 (2026-08-30 검수, consumer-overlap-policy §3.3)"
            )

            conn.execute(_minimal_entry_sql("kept0001"), (legit,))
            conn.execute(_minimal_entry_sql("moved001"), (memo,))
            conn.execute(_minimal_entry_sql("split001"), (mixed,))
            # caution이 아예 없는 엔트리는 대상에서 빠져야 한다.
            conn.execute(
                "INSERT INTO entries "
                "(term, term_norm, easy_term, replace_strategy, risk_level, readability, confidence, checksum) "
                "VALUES ('무주의','무주의','무주의','substitute','none',1,0.9,'nocaution1')"
            )
            conn.commit()

            report = review_notes.migrate_existing_cautions(conn)

            self.assertEqual(report.total_with_caution, 3)
            self.assertEqual(len(report.kept_ids), 1)
            self.assertEqual(len(report.moved_ids), 1)
            self.assertEqual(len(report.split_ids), 1)

            kept_row = conn.execute(
                "SELECT caution, review_note FROM entries WHERE checksum='kept0001'"
            ).fetchone()
            self.assertEqual(kept_row[0], legit)
            self.assertIsNone(kept_row[1])

            moved_row = conn.execute(
                "SELECT caution, review_note FROM entries WHERE checksum='moved001'"
            ).fetchone()
            self.assertIsNone(moved_row[0])
            self.assertEqual(moved_row[1], memo)

            split_row = conn.execute(
                "SELECT caution, review_note FROM entries WHERE checksum='split001'"
            ).fetchone()
            self.assertEqual(split_row[0], "동형이의 주의: 이 항목은 空地(빈 땅) 뜻이다.")
            self.assertIn("골든 코퍼스", split_row[1])
        finally:
            conn.close()

    def test_migration_is_idempotent(self) -> None:
        """이미 분리된 데이터에 다시 돌려도 caution/review_note가 더 바뀌지 않는다."""
        conn = self._make_db()
        try:
            memo = "2026-08-30 검수: easy-doc 내장 목록 대치어 채택으로 substitute 승격 (docs/consumer-overlap-policy.md §3.2)"
            conn.execute(_minimal_entry_sql("idem0001"), (memo,))
            conn.commit()

            first = review_notes.migrate_existing_cautions(conn)
            self.assertEqual(len(first.moved_ids), 1)

            second = review_notes.migrate_existing_cautions(conn)
            self.assertEqual(second.total_with_caution, 0, "caution이 이미 비워져 재분류 대상이 없어야 한다")
        finally:
            conn.close()

    def test_rerun_appends_to_existing_review_note_instead_of_overwriting(self) -> None:
        """MEDIUM-1(독립 리뷰): caution이 남아 있는 채로 review_note도 이미 값이
        있는 엔트리에 다시 돌리면(예: build.py 잉제스트 경로가 먼저 채운 뒤 이
        마이그레이션을 또 돌리는 경우), 기존 review_note를 지우지 않고 이어붙인다."""
        conn = self._make_db()
        try:
            first_memo = "2026-08-30 검수: 1차 결정 (docs/consumer-overlap-policy.md §3.2)"
            second_memo = "2026-08-31 검수: 2차 결정 채택 (docs/consumer-overlap-policy.md §3.3)"

            conn.execute(_minimal_entry_sql("append01"), (second_memo,))
            conn.commit()
            entry_id = conn.execute(
                "SELECT id FROM entries WHERE checksum='append01'"
            ).fetchone()[0]
            # 이미 review_note가 채워져 있는데 caution에도 여전히 값이 남아 있는
            # (예: 수동 편집 등으로 생긴) 드문 상태를 흉내낸다.
            conn.execute(
                "UPDATE entries SET review_note = ? WHERE id = ?", (first_memo, entry_id)
            )
            conn.commit()

            report = review_notes.migrate_existing_cautions(conn)
            self.assertEqual(len(report.moved_ids), 1)

            row = conn.execute(
                "SELECT caution, review_note FROM entries WHERE id = ?", (entry_id,)
            ).fetchone()
            self.assertIsNone(row[0])
            self.assertEqual(row[1], f"{first_memo}\n{second_memo}", "기존 review_note를 지우지 않고 이어붙여야 한다")
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
