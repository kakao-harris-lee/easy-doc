"""`tools/check_invariants.py`의 `check_caution_free_of_review_notes()` 테스트
(2026-09-06 caution/review_note 분리 회귀 방지).

**실제 `dist/`도 읽지 않는다.** `index.json`의 `entries` 구조를 흉내 낸 작은
inline dict만 쓴다.
"""
from __future__ import annotations

import sys
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent.parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import unittest

import check_invariants as ci  # noqa: E402


def _index_doc(entries: dict) -> dict:
    return {"schema_version": "1.0.0", "josa": [], "surface_index": {}, "entries": entries}


class TestCheckCautionFreeOfReviewNotes(unittest.TestCase):
    def test_legit_caution_passes(self) -> None:
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "c": "제도 명칭이라 원어를 지우지 마세요."}})
        self.assertEqual(ci.check_caution_free_of_review_notes(doc), [])

    def test_none_caution_passes(self) -> None:
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "c": None}})
        self.assertEqual(ci.check_caution_free_of_review_notes(doc), [])

    def test_review_note_leak_is_flagged(self) -> None:
        doc = _index_doc({
            "1": {
                "t": "납부", "e": "내기",
                "c": "2026-08-30 검수: easy-doc 내장 목록 대치어 채택으로 substitute 승격 (docs/consumer-overlap-policy.md §3.2)",
            }
        })
        violations = ci.check_caution_free_of_review_notes(doc)
        self.assertEqual(len(violations), 1)
        self.assertIn("1", violations[0].detail)

    def test_needs_confirmation_marker_is_exempt(self) -> None:
        """[확인 필요]는 build.py가 status=deprecated를 강제하는 제어 신호라
        REVIEW_NOTE_PATTERN에 우연히 걸리는 날짜/키워드가 같이 있어도 예외다."""
        doc = _index_doc({
            "1": {
                "t": "국가유공자", "e": "나라를 위해 다치거나 숨진 분",
                "c": "[확인 필요] 2026-08-30 검수 전 원문 대조가 더 필요합니다.",
            }
        })
        self.assertEqual(ci.check_caution_free_of_review_notes(doc), [])


class TestCheckDefinitionReviewMarks(unittest.TestCase):
    """`v`(뜻풀이 검수 이력, 2026-09-23) 산출물 불변식.

    Kotlin의 `DefinitionReviewStatus`는 `"reviewed"` 외의 값을 만나면 기동을
    거부하고, 뜻풀이가 빈 엔트리의 `v`는 "검수했다는데 검수할 내용이 없다"는
    모순이다. 둘 다 산출물에서 먼저 잡는다.
    """

    def test_no_v_key_passes(self) -> None:
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "d": "찾아오는 일입니다."}})
        self.assertEqual(ci.check_definition_review_marks(doc), [])

    def test_reviewed_with_definition_passes(self) -> None:
        doc = _index_doc({
            "1": {"t": "내방", "e": "방문", "d": "찾아오는 일입니다.", "v": "reviewed"}
        })
        self.assertEqual(ci.check_definition_review_marks(doc), [])

    def test_unknown_v_value_is_flagged(self) -> None:
        doc = _index_doc({
            "1": {"t": "내방", "e": "방문", "d": "찾아오는 일입니다.", "v": "unverified"}
        })
        violations = ci.check_definition_review_marks(doc)
        self.assertEqual(len(violations), 1)
        self.assertIn("unverified", violations[0].detail)

    def test_v_on_empty_definition_is_flagged(self) -> None:
        doc = _index_doc({
            "1": {"t": "내방", "e": "방문", "d": None, "v": "reviewed"},
            "2": {"t": "거치", "e": "미룸", "d": "   ", "v": "reviewed"},
        })
        violations = ci.check_definition_review_marks(doc)
        self.assertEqual(len(violations), 2)

    def test_null_v_is_flagged(self) -> None:
        """표시가 없으면 키 자체가 없어야 한다 — `v: null`도 규약 위반이다."""
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "d": "찾아오는 일입니다.", "v": None}})
        self.assertEqual(len(ci.check_definition_review_marks(doc)), 1)


if __name__ == "__main__":
    unittest.main()
