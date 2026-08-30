"""`tools/detect_homonym_risk.py`의 순수 로직 테스트. **네트워크 호출 없음** —

krdict API 응답은 손으로 만든 dict(실제 `_parse_search_response()` 출력
형태)로 흉내 낸다. 이 파일은 (1) 캐시 읽기/쓰기, (2) 검수표 행 생성
(`make_row`)의 플래그·매칭 로직, (3) `dist/`에서 substitute 엔트리를
표제어별로 묶는 로직을 검증한다. 실제 API 호출·`dist/`·`data/raw/` 쓰기는
전혀 하지 않는다(전부 임시 DB/디렉터리).
"""
from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent.parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import detect_homonym_risk as dhr  # noqa: E402

REPO_ROOT = _TOOLS_DIR.parent
SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"


def _item(word: str, sup_no: str | None, origin: str | None, definition: str | None) -> dict:
    return {"word": word, "sup_no": sup_no, "origin": origin, "target_code": "T", "definition": definition}


class TestCacheRoundtrip(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="homonym_cache_test_"))

    def tearDown(self) -> None:
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_save_then_load_roundtrips(self) -> None:
        payload = {"items": [_item("가설", "1", "架設", "놓다")], "error": None}
        dhr._save_cache(self.tmpdir, "가설", payload)
        loaded = dhr._load_cache(self.tmpdir, "가설")
        self.assertEqual(loaded, payload)

    def test_missing_cache_returns_none(self) -> None:
        self.assertIsNone(dhr._load_cache(self.tmpdir, "없는표제어"))

    def test_corrupted_cache_file_is_ignored_not_raised(self) -> None:
        # API 실행 중 프로세스가 죽어 캐시 파일이 반쯤 쓰였을 때를 흉내낸다.
        p = dhr._cache_path(self.tmpdir, "깨진표제어")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("{이건 유효한 JSON이 아님", encoding="utf-8")
        self.assertIsNone(dhr._load_cache(self.tmpdir, "깨진표제어"))

    def test_cache_filenames_differ_for_different_terms(self) -> None:
        # 특수문자가 섞인 표제어끼리 파일명이 겹치지 않는지(완전 방지는 아니지만
        # 흔한 사례는 갈라져야 한다).
        p1 = dhr._cache_path(self.tmpdir, "가스 마스크")
        p2 = dhr._cache_path(self.tmpdir, "가스마스크")
        self.assertNotEqual(p1, p2)


class TestMakeRow(unittest.TestCase):
    """실제 팀장 보고(2026-08-29 krdict 실측)를 흉내 낸 표본으로 검증한다."""

    def _bucket(self, term: str, hanja_options: list[str], easy_terms: list[str], entry_ids: list[int]) -> dict:
        return {"term": term, "hanja_options": hanja_options, "easy_terms": easy_terms, "entry_ids": entry_ids}

    def test_flags_when_two_or_more_sup_no_and_finds_matching_sense(self) -> None:
        # '자' 실측: 者(우리 엔트리) 포함 동형어 4개.
        bucket = self._bucket("자", ["者"], ["사람"], [1124])
        items = [
            _item("자", "1", None, "길이를 재는 데 쓰는 도구."),
            _item("자", "2", None, "손아랫사람에게 권유할 때 쓰는 말."),
            _item("자", "3", "字", "글자."),
            _item("자", "4", "者", "사람을 이르는 말."),
        ]
        row = dhr.make_row(bucket, items, error=None, golden_freq=875)
        self.assertEqual(row["homonym_count"], "4")
        self.assertEqual(row["flag"], "동형어")
        self.assertIn("者=사람을 이르는 말.", row["matched_sense"])
        self.assertIn("字=글자", row["other_senses"])
        self.assertNotIn("者", row["other_senses"])  # 우리 뜻이 other에 중복되면 안 된다
        self.assertEqual(row["golden_freq"], "875")

    def test_flags_but_no_matching_sense_found(self) -> None:
        # '가설' 실측: 架設/假設/假說 3개뿐이고 우리 한자(加設)는 그 안에 없다
        # — 이런 경우도 조용히 넘기지 않고 "일치하는 항목 없음"으로 표시해야 한다.
        bucket = self._bucket("가설", ["加設"], ["덧설치", "추가 설치"], [21, 22])
        items = [
            _item("가설", "1", "架設", "전깃줄을 가로질러 설치함."),
            _item("가설", "2", "假設", "임시로 설치함."),
            _item("가설", "3", "假說", "연구에서 임시로 세운 가정."),
        ]
        row = dhr.make_row(bucket, items, error=None, golden_freq=0)
        self.assertEqual(row["flag"], "동형어")
        self.assertEqual(row["matched_sense"], "(일치하는 항목 없음)")
        self.assertEqual(row["other_senses"].count("["), 3)

    def test_single_sense_is_not_flagged(self) -> None:
        # '거치' 실측: 据置 하나뿐 — 동형어 탐지 도구의 대상이 아니다(§6.8 별개 문제).
        bucket = self._bucket("거치", ["据置"], ["예치", "맡김"], [177, 178])
        items = [_item("거치", "0", "据置", "그대로 두어 미루어 놓음.")]
        row = dhr.make_row(bucket, items, error=None, golden_freq=2)
        self.assertEqual(row["homonym_count"], "1")
        self.assertEqual(row["flag"], "")

    def test_no_hanja_entry_still_gets_checked(self) -> None:
        # 한자 없는 substitute 엔트리(789건, §5.5.1 이전 실측)도 krdict가
        # 고유어 동형어로 잡을 수 있다 — 이전 방법(한자 문자열 대조)과 다른 점.
        bucket = self._bucket("가이드", [], ["안내자", "길잡이"], [40, 41])
        items = [
            _item("가이드", "0", "guide", "안내하는 사람이나 일."),
        ]
        row = dhr.make_row(bucket, items, error=None, golden_freq=2)
        self.assertEqual(row["our_hanja"], "")
        self.assertEqual(row["homonym_count"], "1")
        self.assertIn("한자 없음", row["matched_sense"])

    def test_krdict_error_short_circuits_with_note(self) -> None:
        bucket = self._bucket("아무말", [], ["뭐든"], [1])
        row = dhr.make_row(bucket, items=[], error="503 Service Unavailable", golden_freq=0)
        self.assertIn("조회 실패", row["note"])
        self.assertEqual(row["homonym_count"], "")

    def test_no_exact_match_in_krdict_results(self) -> None:
        # 검색은 됐지만(items 비어있지 않음) '표제어와 글자가 정확히 같은'
        # 항목이 없는 경우(예: 부분일치만 옴) — fetch_krdict.py의 exact-match
        # 관례와 동일해야 한다. "검사 불가" 두 종류 중 이건 드문 쪽이다.
        bucket = self._bucket("동주민센터", [], ["동 주민 센터"], [1])
        items = [_item("동 주민 센터", "0", None, "행정복지센터의 옛 이름.")]
        row = dhr.make_row(bucket, items, error=None, golden_freq=0)
        self.assertTrue(row["note"].startswith("[검사 불가]"))
        self.assertIn("부분일치만", row["note"])

    def test_empty_krdict_results_is_distinguished_from_no_exact_match(self) -> None:
        # 팀장 지시: "검사했는데 깨끗함"과 "검사 자체를 못 함"을 섞으면 안
        # 된다. krdict에 표제어 자체가 없는 것(items=[])은 위 "부분일치만"
        # 케이스와 다른 문구여야 한다 — 실측 927건 중 573건(62%)이 이 경우다.
        bucket = self._bucket("전문행정용어", [], ["쉬운말"], [1])
        row = dhr.make_row(bucket, items=[], error=None, golden_freq=0)
        self.assertTrue(row["note"].startswith("[검사 불가]"))
        self.assertIn("표제어 자체가 없음", row["note"])
        self.assertEqual(row["homonym_count"], "", "검사 불가 상태에서 homonym_count를 채우면 안 된다")

    def test_verb_suffix_mismatch_does_not_cause_false_no_match(self) -> None:
        # 팀장 실측: krdict origin은 '~하다/~되다' 표제어에 접미사까지 붙여서
        # 준다('捺印하다') 반면 우리 term_hanja는 어근만 담는다('捺印').
        # 접미사를 떼지 않고 비교하면 완전히 같은 뜻인데도 "일치 항목 없음"
        # 오탐이 난다 — 실측 95건 중 상당수가 이 버그였다.
        bucket = self._bucket("날인하다", ["捺印"], ["도장을 찍다"], [10])
        items = [_item("날인하다", "0", "捺印하다", "도장을 찍음.")]
        row = dhr.make_row(bucket, items, error=None, golden_freq=0)
        self.assertNotEqual(row["matched_sense"], "(일치하는 항목 없음)")
        self.assertIn("捺印하다=도장을 찍음.", row["matched_sense"])
        self.assertEqual(row["other_senses"], "")

    def test_doeda_suffix_mismatch_does_not_cause_false_no_match(self) -> None:
        bucket = self._bucket("판명되다", ["判明"], ["밝혀지다"], [11])
        items = [_item("판명되다", "0", "判明되다", "확실하게 밝혀짐.")]
        row = dhr.make_row(bucket, items, error=None, golden_freq=0)
        self.assertIn("判明되다=확실하게 밝혀짐.", row["matched_sense"])


class TestLoadSubstituteTerms(unittest.TestCase):
    """`dist/`가 아니라 임시 in-memory 유사 DB로 검증한다 — 실제 dist/는 안 건드린다."""

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="homonym_db_test_"))
        self.db_path = self.tmpdir / "fake.sqlite3"
        conn = sqlite3.connect(self.db_path)
        conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
        conn.execute(
            """
            INSERT INTO entries (id, term, term_norm, term_hanja, easy_term, replace_strategy,
                                  risk_level, status, readability, confidence, checksum)
            VALUES (1, '가설', '가설', '加設', '덧설치', 'substitute', 'none', 'active', 1, 0.8, 'h0000000000000001')
            """
        )
        conn.execute(
            """
            INSERT INTO entries (id, term, term_norm, term_hanja, easy_term, replace_strategy,
                                  risk_level, status, readability, confidence, checksum)
            VALUES (2, '가설', '가설', '加設', '추가 설치', 'substitute', 'none', 'active', 1, 0.8, 'h0000000000000002')
            """
        )
        # gloss 엔트리는 대상이 아니어야 한다.
        conn.execute(
            """
            INSERT INTO entries (id, term, term_norm, term_hanja, easy_term, replace_strategy,
                                  risk_level, status, readability, confidence, checksum)
            VALUES (3, '과태료', '과태료', NULL, '늦게 내는 돈', 'gloss', 'high', 'review', 2, 0.8, 'h0000000000000003')
            """
        )
        # deprecated 엔트리도 대상이 아니어야 한다.
        conn.execute(
            """
            INSERT INTO entries (id, term, term_norm, term_hanja, easy_term, replace_strategy,
                                  risk_level, status, readability, confidence, checksum)
            VALUES (4, '폐기어', '폐기어', NULL, '안 씀', 'substitute', 'none', 'deprecated', 1, 0.8, 'h0000000000000004')
            """
        )
        conn.commit()
        conn.close()

    def tearDown(self) -> None:
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_dedupes_by_term_and_collects_all_easy_terms(self) -> None:
        buckets = dhr.load_substitute_terms(str(self.db_path))
        self.assertEqual(len(buckets), 1, "같은 표제어의 substitute 엔트리 2개는 버킷 1개로 묶여야 한다")
        b = buckets[0]
        self.assertEqual(b["term"], "가설")
        self.assertEqual(sorted(b["entry_ids"]), [1, 2])
        self.assertEqual(b["easy_terms"], ["덧설치", "추가 설치"])
        self.assertEqual(b["hanja_options"], ["加設"])

    def test_gloss_and_deprecated_entries_excluded(self) -> None:
        buckets = dhr.load_substitute_terms(str(self.db_path))
        terms = {b["term"] for b in buckets}
        self.assertNotIn("과태료", terms, "gloss 전략은 이 도구의 대상이 아니다")
        self.assertNotIn("폐기어", terms, "deprecated는 이 도구의 대상이 아니다")


class TestGoldenFrequencies(unittest.TestCase):
    """§실측 버그(2026-08-29): 단순 `text.count(term)`는 경계를 안 봐서

    `자`의 빈도가 875(실제 53)로 16배 부풀려졌다. 지금은 `EasyDict.find_all()`
    (경계 규칙 + 최장일치)로 세야 하므로, 표제어가 실제로 `dist/`류 DB에
    등록돼 있어야 한다 — 그래서 이 테스트는 `TestLoadSubstituteTerms`와
    같은 schema.sql 기반 임시 DB를 쓴다.
    """

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="homonym_golden_test_"))
        self.db_path = self.tmpdir / "fake.sqlite3"
        conn = sqlite3.connect(self.db_path)
        conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
        conn.execute(
            """
            INSERT INTO entries (id, term, term_norm, term_hanja, easy_term, replace_strategy,
                                  risk_level, status, readability, confidence, checksum)
            VALUES (1, '자', '자', '者', '사람', 'substitute', 'none', 'active', 1, 0.8, 'h0000000000000001')
            """
        )
        conn.commit()
        conn.close()

    def tearDown(self) -> None:
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _write_golden_doc(self, golden_dir: Path, name: str, text: str, *, top_level: bool = False) -> None:
        target = golden_dir if top_level else golden_dir / "documents"
        target.mkdir(parents=True, exist_ok=True)
        (target / name).write_text(json.dumps({"id": name, "source_text": text}), encoding="utf-8")

    def test_counts_occurrences_across_documents_subdir_and_top_level(self) -> None:
        golden_dir = self.tmpdir / "golden"
        self._write_golden_doc(golden_dir, "001.json", "자를 신청하세요.")
        self._write_golden_doc(golden_dir, "099.json", "다른 문서에도 자가 나온다.", top_level=True)
        freqs = dhr.golden_frequencies(str(golden_dir), str(self.db_path), ["자", "없는말"])
        self.assertEqual(freqs["자"], 2)
        self.assertEqual(freqs["없는말"], 0)

    def test_boundary_rule_excludes_mid_word_occurrences(self) -> None:
        # 이게 핵심 버그였다: 단순 문자열 카운트는 '기자'의 '자'까지 센다.
        # '자'가 복합어 중간에서 시작하면 안 된다는 경계 규칙(§6.5)이
        # 여기서도 그대로 적용돼야 한다.
        golden_dir = self.tmpdir / "golden"
        self._write_golden_doc(golden_dir, "001.json", "기자가 취재했다. 자를 신청하세요.")
        freqs = dhr.golden_frequencies(str(golden_dir), str(self.db_path), ["자"])
        naive_count = "기자가 취재했다. 자를 신청하세요.".count("자")
        self.assertEqual(naive_count, 2, "이 문장은 단순 카운트로는 2가 나와야 함정이 성립한다")
        self.assertEqual(freqs["자"], 1, "'기자'의 '자'는 복합어 중간이라 세면 안 된다")


if __name__ == "__main__":
    unittest.main()
