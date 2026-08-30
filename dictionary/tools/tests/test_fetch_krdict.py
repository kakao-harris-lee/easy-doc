"""`tools/fetch_krdict.py`의 검색 응답 파싱, 특히 `sup_no`(동형어 번호) 회귀 테스트.

`tools/detect_homonym_risk.py`(동음이의어 위험 검수표 도구)가 `sup_no`에
의존하므로, 그 값이 실제 API 응답 구조와 계속 맞는지 이 테스트가 지킨다.

픽스처는 실제 인증키로 받은 원본 XML 덤프 3건(`tools/tests/fixtures/
krdict_*.xml`, `fetch_krdict.py` 모듈 docstring의 "확인" 근거)이다. 네트워크
호출은 하지 않는다 — 커밋된 파일만 읽는다.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent.parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import fetch_krdict as fk  # noqa: E402

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"


class TestSupNoParsing(unittest.TestCase):
    """실제 XML 덤프 3건으로 sup_no 파싱을 검증한다."""

    def _parse(self, filename: str) -> list[fk.Item]:
        xml_bytes = (FIXTURES_DIR / filename).read_bytes()
        return fk._parse_search_response(xml_bytes)

    def test_seonjeong_has_two_distinct_sup_no_for_exact_word(self) -> None:
        # '선정'은 동형어 2개(sup_no=1 善政/좋은 정치, sup_no=2 選定/골라 정함)다.
        # 이게 이 테스트 파일의 핵심 사례 — 표제어가 같아도 sup_no로 뜻이
        # 갈리는 걸 실제 응답으로 확인한다.
        items = self._parse("krdict_seonjeong.xml")
        exact = [it for it in items if it.word == "선정"]
        self.assertEqual(len(exact), 2)
        sup_nos = {it.sup_no for it in exact}
        self.assertEqual(sup_nos, {"1", "2"})

    def test_seonjeong_sup_no_pairs_with_correct_origin(self) -> None:
        items = self._parse("krdict_seonjeong.xml")
        exact = {it.sup_no: it.origin for it in items if it.word == "선정"}
        self.assertEqual(exact, {"1": "善政", "2": "選定"})

    def test_seonjeong_non_exact_matches_have_sup_no_zero_or_distinct(self) -> None:
        # '선정하다'/'선정되다'/'선정성'은 표제어가 다르므로 exact 필터에서
        # 빠져야 한다 — 그리고 이들 자신은 동형어가 없어 sup_no='0'이다.
        items = self._parse("krdict_seonjeong.xml")
        by_word: dict[str, list[str | None]] = {}
        for it in items:
            by_word.setdefault(it.word, []).append(it.sup_no)
        self.assertEqual(by_word["선정하다"], ["0"])
        self.assertEqual(by_word["선정되다"], ["0"])
        self.assertEqual(by_word["선정성"], ["0"])
        # '선정적'은 그 자체로 동형어 2개(sup_no 1,2)를 갖는 별도 사례.
        self.assertEqual(set(by_word["선정적"]), {"1", "2"})

    def test_geojuji_single_sense_has_sup_no_zero(self) -> None:
        # '거주지'는 동형어 없이 항목 1개, sup_no='0' — "0"은 falsy 문자열이
        # 아니라 실제 값이므로 _text()가 None으로 잘못 지우면 안 된다
        # (파이썬에서 `not "0"` 은 False라 이 함정을 특히 조심해야 한다).
        items = self._parse("krdict_geojuji.xml")
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].word, "거주지")
        self.assertEqual(items[0].sup_no, "0")

    def test_jumincenter_single_sense_has_sup_no_zero(self) -> None:
        items = self._parse("krdict_jumincenter.xml")
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].sup_no, "0")

    def test_sup_no_defaults_to_none_when_tag_missing(self) -> None:
        # sup_no 태그 자체가 없는 예전/이형 응답에도 파서가 죽지 않아야 한다.
        xml = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            "<channel><item><word>테스트어</word></item></channel>"
        ).encode("utf-8")
        items = fk._parse_search_response(xml)
        self.assertEqual(len(items), 1)
        self.assertIsNone(items[0].sup_no)


if __name__ == "__main__":
    unittest.main()
