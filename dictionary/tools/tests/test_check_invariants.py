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
        """Kotlin `DefinitionReviewStatus.ofWire`가 실제로 예외를 던지는(=
        선언되지 않은) 값으로 예시를 든다 — "unverified"는 아래 별도 테스트가
        보여주듯 예외 없이 조용히 흡수되므로 여기서는 "모르는 값"의 예시가
        아니다."""
        doc = _index_doc({
            "1": {"t": "내방", "e": "방문", "d": "찾아오는 일입니다.", "v": "pending"}
        })
        violations = ci.check_definition_review_marks(doc)
        self.assertEqual(len(violations), 1)
        self.assertIn("pending", violations[0].detail)

    def test_unverified_value_is_flagged_despite_silent_kotlin_absorption(self) -> None:
        """`"unverified"`는 `DefinitionReviewStatus.UNVERIFIED`의 선언된 wire
        값이라 Kotlin은 기동을 거부하지 않고 조용히 UNVERIFIED로 흡수한다
        (`DictionaryEntry.kt`) — 그래도 이 저장소의 규약은 `v`가 `"reviewed"`
        하나뿐이어야 한다고 정하므로, 이 값도 여전히 위반으로 잡는다(조용한
        흡수를 조용히 두지 않는다)."""
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


class TestCheckDefinitionReviewDbIndexConsistency(unittest.TestCase):
    """DB(정본)의 `is_definition_reviewed()` 판정과 배포 색인의 `v` 존재 여부가
    서로 어긋나지 않는가 (2026-09-23, 항목 9). 수동 UPDATE·마이그레이션 누락·
    재익스포트 전 DB만 고친 경우 등으로 둘이 갈라질 수 있다 — 한쪽에만 있으면
    (양방향) 위반이다."""

    def _entry(
        self,
        entry_id: int = 1,
        *,
        definition: str | None = "뜻풀이입니다.",
        reviewed_at: str | None = "2026-09-23",
        reviewed_by: str | None = "검수자A",
        term: str = "내방",
        status: str = "active",
    ) -> dict:
        return {
            "id": entry_id, "term": term, "status": status,
            "definition": definition,
            "definition_reviewed_at": reviewed_at,
            "definition_reviewed_by": reviewed_by,
        }

    def test_consistent_reviewed_pair_passes(self) -> None:
        entries = [self._entry()]
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "d": "뜻풀이입니다.", "v": "reviewed"}})
        self.assertEqual(ci.check_definition_review_db_index_consistency(entries, doc), [])

    def test_consistent_unreviewed_pair_passes(self) -> None:
        entries = [self._entry(reviewed_at=None, reviewed_by=None)]
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "d": "뜻풀이입니다."}})
        self.assertEqual(ci.check_definition_review_db_index_consistency(entries, doc), [])

    def test_db_reviewed_but_index_missing_v_is_flagged(self) -> None:
        """DB는 검수 완료 조건을 만족하는데 색인엔 v가 없다 — 익스포트 누락 등."""
        entries = [self._entry()]
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "d": "뜻풀이입니다."}})
        violations = ci.check_definition_review_db_index_consistency(entries, doc)
        self.assertEqual(len(violations), 1)
        self.assertIn("1", violations[0].detail)

    def test_index_has_v_but_db_not_reviewed_is_flagged(self) -> None:
        """색인엔 v=reviewed가 있는데 DB는 조건을 만족하지 않는다 — 수동 JSON 편집 등."""
        entries = [self._entry(reviewed_at=None, reviewed_by=None)]
        doc = _index_doc({"1": {"t": "내방", "e": "방문", "d": "뜻풀이입니다.", "v": "reviewed"}})
        violations = ci.check_definition_review_db_index_consistency(entries, doc)
        self.assertEqual(len(violations), 1)

    def test_entry_missing_from_index_but_reviewed_in_db_is_flagged(self) -> None:
        """비폐기(active) 엔트리인데 색인에 그 entry_id 자체가 없다 — DB는 검수
        완료인데 색인 쪽엔 흔적조차 없으면 위반이다."""
        entries = [self._entry(entry_id=99, status="active")]
        doc = _index_doc({})
        violations = ci.check_definition_review_db_index_consistency(entries, doc)
        self.assertEqual(len(violations), 1)

    def test_deprecated_entry_missing_from_index_is_not_flagged(self) -> None:
        """deprecated 엔트리는 검수 완료 표시가 있어도 애초에 색인에서 빠진다
        (`export_index()`가 `status != 'deprecated'`만 싣는다, export.py
        `_ACTIVE_ENTRIES_SQL`) — 정상 동작이지 위반이 아니다."""
        entries = [self._entry(entry_id=5, status="deprecated")]
        doc = _index_doc({})
        self.assertEqual(ci.check_definition_review_db_index_consistency(entries, doc), [])


class TestCheckMalformedReviewedAt(unittest.TestCase):
    """definition_reviewed_at 형식 오류 검사 (2026-09-23, 항목 8).

    `definition_review.is_definition_reviewed()`가 ISO-8601이 아닌
    `reviewed_at`을 조용히 "미검수"로 fail-close하므로, 검수자가 값을 채웠는데
    (definition·reviewed_by가 있음) 형식이 틀려 조용히 묻히는 오타를 여기서
    드러낸다.
    """

    def _entry(
        self,
        entry_id: int = 1,
        *,
        definition: str | None = "뜻풀이입니다.",
        reviewed_at: str | None,
        reviewed_by: str | None = "검수자A",
        term: str = "내방",
    ) -> dict:
        return {
            "id": entry_id, "term": term,
            "definition": definition,
            "definition_reviewed_at": reviewed_at,
            "definition_reviewed_by": reviewed_by,
        }

    def test_valid_iso8601_passes(self) -> None:
        entries = [self._entry(reviewed_at="2026-09-23")]
        self.assertEqual(ci.check_malformed_reviewed_at(entries), [])

    def test_malformed_date_with_reviewed_by_present_is_flagged(self) -> None:
        entries = [self._entry(reviewed_at="2026/09/23")]
        violations = ci.check_malformed_reviewed_at(entries)
        self.assertEqual(len(violations), 1)
        self.assertIn("2026/09/23", violations[0].detail)

    def test_unattempted_review_is_not_flagged(self) -> None:
        """reviewed_at/reviewed_by가 둘 다 비어 있으면(검수를 시도조차 안 함)
        형식 검사 대상이 아니다."""
        entries = [self._entry(reviewed_at=None, reviewed_by=None)]
        self.assertEqual(ci.check_malformed_reviewed_at(entries), [])

    def test_reviewed_at_present_but_reviewed_by_missing_is_not_flagged(self) -> None:
        """reviewed_by가 없으면 애초에 검수 완료 조건 미달(다른 규칙)이라
        형식 오류로 이중 보고하지 않는다."""
        entries = [self._entry(reviewed_at="2026/09/23", reviewed_by=None)]
        self.assertEqual(ci.check_malformed_reviewed_at(entries), [])


if __name__ == "__main__":
    unittest.main()
