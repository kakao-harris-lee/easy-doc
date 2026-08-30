"""`tools/detect_consumer_overlap.py`의 순수 로직 테스트.

**네트워크 호출 없음, 실제 `dist/`도 읽지 않는다.** 사전 색인은 작은 인라인
dict(실제 `index.json`의 `entries`/`surface_index` 구조를 흉내 낸)로,
소비자 목록은 임시 파일(Kotlin/JSON/텍스트 3형식)로 대신한다.
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent.parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import detect_consumer_overlap as dco  # noqa: E402


def _entry(t: str, e: str, s: str, r: str = "none", p: int = 100) -> dict:
    return {"t": t, "e": e, "s": s, "r": r, "p": p}


class TestParseKotlin(unittest.TestCase):
    def test_extracts_pairs_from_map_block(self) -> None:
        text = (
            "package kr.easydoc.core.easyread\n\n"
            "val DIFFICULT_WORD_REPLACEMENTS: Map<String, String> =\n"
            "    linkedMapOf(\n"
            '        "금일" to "오늘",\n'
            '        "명일" to "내일",\n'
            "    )\n\n"
            "val PROMPT_ONLY_WORDS: Set<String> =\n"
            "    setOf(\n"
            '        "상기",\n'
            "    )\n"
        )
        result = dco.parse_kotlin(text)
        self.assertEqual(result, {"금일": "오늘", "명일": "내일"})

    def test_stops_before_next_val_declaration(self) -> None:
        # PROMPT_ONLY_WORDS 쪽에 우연히 "X" to "Y" 형태가 있어도(이 파일엔 없지만
        # 방어적으로) 다음 val 선언 이후는 절대 스캔하면 안 된다.
        text = (
            "val DIFFICULT_WORD_REPLACEMENTS = linkedMapOf(\n"
            '    "금일" to "오늘",\n'
            ")\n\n"
            "val OTHER_MAP = mapOf(\n"
            '    "가짜" to "함정",\n'
            ")\n"
        )
        result = dco.parse_kotlin(text)
        self.assertEqual(result, {"금일": "오늘"})
        self.assertNotIn("가짜", result)

    def test_custom_var_name(self) -> None:
        text = 'val MY_MAP = mapOf(\n    "가" to "나",\n)\n'
        result = dco.parse_kotlin(text, var_name="MY_MAP")
        self.assertEqual(result, {"가": "나"})

    def test_missing_var_name_raises(self) -> None:
        text = "val SOMETHING_ELSE = mapOf(\n)\n"
        with self.assertRaises(ValueError):
            dco.parse_kotlin(text)


class TestParseJsonMap(unittest.TestCase):
    def test_parses_flat_object(self) -> None:
        result = dco.parse_json_map('{"금일": "오늘", "명일": "내일"}')
        self.assertEqual(result, {"금일": "오늘", "명일": "내일"})

    def test_non_object_raises(self) -> None:
        with self.assertRaises(ValueError):
            dco.parse_json_map('["금일", "오늘"]')


class TestParseTextList(unittest.TestCase):
    def test_word_only_lines(self) -> None:
        result = dco.parse_text_list("금일\n명일\n")
        self.assertEqual(result, {"금일": "", "명일": ""})

    def test_tab_separated(self) -> None:
        result = dco.parse_text_list("금일\t오늘\n명일\t내일\n")
        self.assertEqual(result, {"금일": "오늘", "명일": "내일"})

    def test_arrow_separated(self) -> None:
        result = dco.parse_text_list("금일 -> 오늘\n")
        self.assertEqual(result, {"금일": "오늘"})

    def test_blank_lines_and_comments_skipped(self) -> None:
        result = dco.parse_text_list("# 주석\n\n금일\t오늘\n   \n")
        self.assertEqual(result, {"금일": "오늘"})


class TestDetectFormat(unittest.TestCase):
    def test_kt_extension(self) -> None:
        self.assertEqual(dco.detect_format(Path("x.kt"), None, ""), "kotlin")

    def test_json_extension(self) -> None:
        self.assertEqual(dco.detect_format(Path("x.json"), None, ""), "json")

    def test_sniffs_json_object_by_content(self) -> None:
        self.assertEqual(dco.detect_format(Path("x.txt"), None, '  {"a": "b"}'), "json")

    def test_falls_back_to_text(self) -> None:
        self.assertEqual(dco.detect_format(Path("x.txt"), None, "그냥 낱말들\n"), "text")

    def test_explicit_format_wins(self) -> None:
        self.assertEqual(dco.detect_format(Path("x.kt"), "text", ""), "text")

    def test_unknown_explicit_format_raises(self) -> None:
        with self.assertRaises(ValueError):
            dco.detect_format(Path("x.kt"), "xml", "")


class TestFindDictMatchesAndClassify(unittest.TestCase):
    """실제 `index.json` 구조(`entries`: dict[str,dict], `surface_index`:
    dict[str, list[int]])를 그대로 흉내 낸 작은 픽스처로 검증한다."""

    def setUp(self) -> None:
        self.entries = {
            "1": _entry("고등교육법", "고등교육법", "keep", "high"),
            "2": _entry("금일", "오늘날", "substitute", "none"),
            "3": _entry("받다", "받다", "gloss", "low"),
        }
        self.surface_index = {
            "고등교육법": [1],
            "고 등교육법": [1],  # 변형 표면형
            "금일": [2],
            "받다": [3],
            "받아": [3],  # '받다'의 활용형(변형 표면형)
        }
        self.term_index = dco.build_term_index(self.entries)

    def test_headword_exact_match(self) -> None:
        matches = dco.find_dict_matches("고등교육법", self.term_index, self.surface_index, self.entries)
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0].match_kind, "headword")
        self.assertEqual(matches[0].dict_term, "고등교육법")

    def test_surface_variant_match_only(self) -> None:
        # '받아'는 표제어가 아니라 '받다'의 활용형으로만 존재 — 표제어(t) 직접
        # 대조로는 절대 못 잡는다. surface_index를 봐야만 잡힌다.
        matches = dco.find_dict_matches("받아", self.term_index, self.surface_index, self.entries)
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0].match_kind, "surface_variant")
        self.assertEqual(matches[0].dict_term, "받다")

    def test_no_match_returns_empty(self) -> None:
        matches = dco.find_dict_matches("전혀없는말", self.term_index, self.surface_index, self.entries)
        self.assertEqual(matches, [])

    def test_headword_and_surface_index_dedupe_to_one_match(self) -> None:
        # '금일'은 표제어이면서 surface_index에도 자기 자신으로 등록돼 있다
        # (실제 index.json의 일반적 모양) — 두 번 잡히면 안 된다.
        matches = dco.find_dict_matches("금일", self.term_index, self.surface_index, self.entries)
        self.assertEqual(len(matches), 1)

    def test_classify_keep_is_conflict(self) -> None:
        match = dco.find_dict_matches("고등교육법", self.term_index, self.surface_index, self.entries)[0]
        self.assertEqual(dco.classify_conflict("아무거나", match), "CONFLICT")

    def test_classify_gloss_is_conflict(self) -> None:
        match = dco.find_dict_matches("받다", self.term_index, self.surface_index, self.entries)[0]
        self.assertEqual(dco.classify_conflict("아무거나", match), "CONFLICT")

    def test_classify_substitute_same_replacement_is_duplicate(self) -> None:
        match = dco.find_dict_matches("금일", self.term_index, self.surface_index, self.entries)[0]
        self.assertEqual(dco.classify_conflict("오늘날", match), "DUPLICATE")

    def test_classify_substitute_different_replacement_is_divergent(self) -> None:
        match = dco.find_dict_matches("금일", self.term_index, self.surface_index, self.entries)[0]
        self.assertEqual(dco.classify_conflict("오늘", match), "DIVERGENT")


class TestAnalyzeAndExitCode(unittest.TestCase):
    def setUp(self) -> None:
        self.index_doc = {
            "entries": {
                "1": _entry("고등교육법", "고등교육법", "keep", "high"),
                "2": _entry("금일", "오늘날", "substitute", "none"),
            },
            "surface_index": {
                "고등교육법": [1],
                "금일": [2],
            },
        }

    def test_analyze_produces_one_overlap_per_word_entry_pair(self) -> None:
        consumer = {"고등교육법": "대학 이상 교육에 관한 법", "금일": "오늘날", "전혀없는말": "몰라"}
        overlaps = dco.analyze(consumer, self.index_doc)
        self.assertEqual(len(overlaps), 2)
        verdicts = {ov.word: ov.verdict for ov in overlaps}
        self.assertEqual(verdicts["고등교육법"], "CONFLICT")
        self.assertEqual(verdicts["금일"], "DUPLICATE")

    def test_main_exit_code_1_when_conflict_present(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            index_path = Path(td) / "index.json"
            index_path.write_text(json.dumps(self.index_doc, ensure_ascii=False), encoding="utf-8")
            consumer_path = Path(td) / "consumer.json"
            consumer_path.write_text(
                json.dumps({"고등교육법": "대학 이상 교육에 관한 법"}, ensure_ascii=False), encoding="utf-8"
            )
            rc = dco.main(["--consumer", str(consumer_path), "--index", str(index_path)])
            self.assertEqual(rc, 1)

    def test_main_exit_code_0_when_only_duplicates(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            index_path = Path(td) / "index.json"
            index_path.write_text(json.dumps(self.index_doc, ensure_ascii=False), encoding="utf-8")
            consumer_path = Path(td) / "consumer.json"
            consumer_path.write_text(json.dumps({"금일": "오늘날"}, ensure_ascii=False), encoding="utf-8")
            rc = dco.main(["--consumer", str(consumer_path), "--index", str(index_path)])
            self.assertEqual(rc, 0)

    def test_main_exit_code_0_when_no_overlap(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            index_path = Path(td) / "index.json"
            index_path.write_text(json.dumps(self.index_doc, ensure_ascii=False), encoding="utf-8")
            consumer_path = Path(td) / "consumer.json"
            consumer_path.write_text(json.dumps({"전혀없는말": "몰라"}, ensure_ascii=False), encoding="utf-8")
            rc = dco.main(["--consumer", str(consumer_path), "--index", str(index_path)])
            self.assertEqual(rc, 0)

    def test_main_json_output_is_valid_json_array(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            index_path = Path(td) / "index.json"
            index_path.write_text(json.dumps(self.index_doc, ensure_ascii=False), encoding="utf-8")
            consumer_path = Path(td) / "consumer.json"
            consumer_path.write_text(
                json.dumps({"고등교육법": "대학 이상 교육에 관한 법"}, ensure_ascii=False), encoding="utf-8"
            )

            import contextlib
            import io

            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = dco.main(["--consumer", str(consumer_path), "--index", str(index_path), "--json"])
            self.assertEqual(rc, 1)
            parsed = json.loads(buf.getvalue())
            self.assertEqual(len(parsed), 1)
            self.assertEqual(parsed[0]["verdict"], "CONFLICT")
            self.assertEqual(parsed[0]["word"], "고등교육법")

    def test_main_missing_consumer_file_returns_2(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            index_path = Path(td) / "index.json"
            index_path.write_text(json.dumps(self.index_doc, ensure_ascii=False), encoding="utf-8")
            rc = dco.main(["--consumer", str(Path(td) / "없음.json"), "--index", str(index_path)])
            self.assertEqual(rc, 2)

    def test_main_missing_index_file_returns_2(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            consumer_path = Path(td) / "consumer.json"
            consumer_path.write_text(json.dumps({"금일": "오늘"}, ensure_ascii=False), encoding="utf-8")
            rc = dco.main([
                "--consumer", str(consumer_path),
                "--index", str(Path(td) / "없음.index.json"),
            ])
            self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
