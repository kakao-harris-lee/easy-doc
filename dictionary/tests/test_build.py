"""src/easydict/build.py 계약 테스트 (DESIGN.md §5, §6.3).

build.py는 아직 구현되지 않았을 수 있다(작업 C 진행 중). import가 실패하면
전체 클래스를 건너뛰되, 몇 개가 스킵됐는지는 unittest 실행 결과의
'skipped=N' 으로 항상 드러난다 - 조용히 통과한 척하지 않는다.

data/raw/*.csv 는 읽기만 한다. 절대 수정하지 않는다.
"""

from __future__ import annotations

import contextlib
import io
import sqlite3
import tempfile
import unittest
from pathlib import Path

try:
    from easydict import build
    from easydict.models import Entry, Example
    _IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover
    build = None  # type: ignore[assignment]
    Entry = None  # type: ignore[assignment]
    _IMPORT_ERROR = e

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_RAW = REPO_ROOT / "data" / "raw"

# 가상 검증용 샘플 CSV 전용 디렉터리 (팀장이 실데이터와 분리하려고 여기로 옮김,
# 2026-08-27). 경로가 다시 바뀌어도 고칠 곳이 이 한 줄이 되도록 상수로 뽑는다
# — 아래 (b) 판단 참고: TestKeepClassificationEndToEnd처럼 "이 샘플 CSV 자체가
# 의도적으로 만들어진 픽스처"인 테스트만 파일 의존을 유지한다.
SAMPLE_CSV_DIR = DATA_RAW / "sample"


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestDetectEncoding(unittest.TestCase):
    """인코딩 판별은 실제 바이트가 있어야 의미가 있으므로 파일 의존을 유지한다(팀장 판단과 일치)."""

    def test_detects_cp949_sample(self) -> None:
        path = SAMPLE_CSV_DIR / "raw_terms_welfare_cp949.csv"
        self.assertTrue(path.is_file(), f"샘플 CSV 없음: {path}")
        # §5.1: utf-8-sig -> utf-8 -> cp949 -> euc-kr 순으로 시도.
        # 이 파일은 cp949/euc-kr 둘 다로 디코딩되지만 cp949가 먼저 시도되므로 cp949가 나와야 한다.
        self.assertEqual(build.detect_encoding(path), "cp949")

    def test_detects_utf8_sig_sample(self) -> None:
        path = SAMPLE_CSV_DIR / "raw_terms.csv"
        self.assertTrue(path.is_file(), f"샘플 CSV 없음: {path}")
        encoding = build.detect_encoding(path)
        self.assertIn(encoding, ("utf-8-sig", "utf-8"))


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestResolveColumns(unittest.TestCase):
    """§5.1 컬럼 별칭 해석.

    이 테스트가 검증하는 건 헤더 문자열 목록 -> 논리 필드 매핑이라는 순수
    함수 계약이지, CSV 파일 파싱 자체가 아니다. 그래서 실제 헤더 값을 파일이
    아니라 리터럴로 인라인했다 — data/raw 밑 어떤 파일이 옮겨지거나
    이름이 바뀌어도 이 테스트는 절대 안 깨진다(아래 (b) 판단 참고). 값 자체는
    실제 3종 샘플 CSV 헤더 그대로다(순화대상어/순화어, 용어/쉬운 말, 원어/다듬은 말).
    """

    def test_resolves_admin_terms_header(self) -> None:
        header = ["순화대상어", "순화어", "분야", "뜻", "예문"]
        colmap = build.resolve_columns(header)
        self.assertEqual(colmap["term"], "순화대상어")
        self.assertEqual(colmap["easy_term"], "순화어")

    def test_resolves_welfare_terms_header(self) -> None:
        header = ["용어", "쉬운 말", "영역", "설명"]
        colmap = build.resolve_columns(header)
        self.assertEqual(colmap["term"], "용어")
        self.assertEqual(colmap["easy_term"], "쉬운 말")

    def test_resolves_law_terms_header(self) -> None:
        header = ["원어", "다듬은 말", "구분", "비고"]
        colmap = build.resolve_columns(header)
        self.assertEqual(colmap["term"], "원어")
        self.assertEqual(colmap["easy_term"], "다듬은 말")

    def test_unresolvable_header_raises_value_error_with_headers_listed(self) -> None:
        bogus_header = ["foo_col", "bar_col", "baz_col"]
        with self.assertRaises(ValueError) as ctx:
            build.resolve_columns(bogus_header)
        message = str(ctx.exception)
        for h in bogus_header:
            self.assertIn(h, message, f"에러 메시지에 발견된 헤더 '{h}'가 없다: {message}")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestClassifySafety(unittest.TestCase):
    """§5.2 위험도·치환전략 자동 판정. 안전장치 회귀 테스트 (최우선)."""

    def test_gwataeryo_is_never_substitute(self) -> None:
        # 과태료 -> 벌금 처럼 오치환되면 법적 사고로 이어진다 (DESIGN.md §2.1).
        entry = Entry(term="과태료", easy_term="정해진 날짜보다 늦게 내서 더 내는 돈", tags=["law"])
        result = build.classify(entry)
        self.assertEqual(result.replace_strategy, "gloss")
        self.assertEqual(result.risk_level, "high")
        self.assertEqual(
            result.status, "review",
            "risk_level='high' 인 표제어는 사람 검수 전까지 status='review' 여야 한다 (§5.2)",
        )
        self.assertNotEqual(result.replace_strategy, "substitute")

    def test_naebang_common_admin_term_is_substitute(self) -> None:
        entry = Entry(term="내방", easy_term="방문")
        result = build.classify(entry)
        self.assertEqual(result.replace_strategy, "substitute")
        self.assertEqual(result.risk_level, "none")

    def test_legal_risk_list_contains_gwataeryo(self) -> None:
        self.assertIn("과태료", build.LEGAL_RISK_LIST)

    def test_priority_formula(self) -> None:
        # DESIGN.md §4.2 예시: term='차상위계층'(5자) -> priority=150
        entry = Entry(term="차상위계층", easy_term="기초생활수급자 바로 위의 저소득층", tags=["welfare"])
        result = build.classify(entry)
        self.assertEqual(result.priority, 100 + len("차상위계층") * 10)


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestKeepClassificationEndToEnd(unittest.TestCase):
    """E-2 회귀 (DESIGN.md §8 "keep 경로"): 법령명·금액·기한·제도 고유명칭은
    keep + risk=high + status=review 로 분류되어야 한다 (§2.1, §5.2).

    실제 샘플 CSV(data/raw/*.csv)에 추가된 keep 대상 행을 읽어 들여
    read_csv_rows -> row_to_entries -> classify 파이프라인을 그대로 통과시킨다
    (합성 픽스처가 아니라 실제 파이프라인 함수로 검증).
    """

    def _classified_entries_by_term(self, csv_path: Path) -> dict[str, list[Entry]]:
        from easydict.models import Source

        rows, colmap = build.read_csv_rows(csv_path)
        source = Source(code="test:src", name="테스트용 출처")
        by_term: dict[str, list[Entry]] = {}
        for lineno, row in enumerate(rows, start=2):
            for entry in build.row_to_entries(row, colmap, source, lineno):
                classified = build.classify(entry)
                by_term.setdefault(classified.term, []).append(classified)
        return by_term

    def _assert_keep(self, by_term: dict[str, list[Entry]], term: str) -> None:
        self.assertIn(term, by_term, f"'{term}' 행이 파싱되지 않았다")
        for e in by_term[term]:
            self.assertEqual(e.replace_strategy, "keep", f"'{term}'은 keep으로 분류되어야 한다")
            self.assertEqual(e.risk_level, "high", f"'{term}'은 risk_level=high 여야 한다")
            self.assertEqual(e.status, "review", f"'{term}'은 status=review 여야 한다")

    def test_admin_csv_keep_rows(self) -> None:
        by_term = self._classified_entries_by_term(SAMPLE_CSV_DIR / "raw_terms.csv")
        for term in ("「국민기초생활 보장법」", "2026년 1월 15일", "월 30만 원"):
            self._assert_keep(by_term, term)

    def test_law_csv_keep_rows(self) -> None:
        by_term = self._classified_entries_by_term(SAMPLE_CSV_DIR / "raw_terms_law.csv")
        for term in (
            "「장애인복지법」",
            "「사회보장급여의 이용·제공 및 수급권자 발굴에 관한 법률」",
            "신청일부터 30일 이내",
        ):
            self._assert_keep(by_term, term)

    def test_welfare_csv_keep_rows(self) -> None:
        by_term = self._classified_entries_by_term(SAMPLE_CSV_DIR / "raw_terms_welfare_cp949.csv")
        for term in ("국민기초생활보장제도", "장애인연금"):
            self._assert_keep(by_term, term)

    def test_ordinary_admin_term_is_not_a_keep_false_positive(self) -> None:
        # false positive 방지: 평범한 용어가 keep으로 잘못 잡히면 정상 치환 파이프라인이 죽는다.
        by_term = self._classified_entries_by_term(SAMPLE_CSV_DIR / "raw_terms.csv")
        for e in by_term.get("기재하다", []):
            self.assertNotEqual(e.replace_strategy, "keep", "'기재하다'가 keep으로 잘못 분류됨(false positive)")
            self.assertEqual(e.replace_strategy, "substitute")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestDedupe(unittest.TestCase):
    """§5⑥ (term_norm, easy_term) 유일화. 완전중복만 제거, 대안은 보존."""

    def test_same_term_different_easy_term_both_survive_with_warning(self) -> None:
        entries = [
            Entry(term="내방", easy_term="방문"),
            Entry(term="내방", easy_term="찾아옴"),
        ]
        deduped, warnings = build.dedupe(entries)
        self.assertEqual(len(deduped), 2, "같은 term, 다른 easy_term은 문맥별 대안으로 둘 다 살아남아야 한다")
        self.assertGreaterEqual(len(warnings), 1, "동일 term에 대한 복수 easy_term은 경고로 남아야 한다")

    def test_exact_duplicate_is_removed(self) -> None:
        entries = [
            Entry(term="내방", easy_term="방문"),
            Entry(term="내방", easy_term="방문"),  # 완전 중복
        ]
        deduped, _warnings = build.dedupe(entries)
        self.assertEqual(len(deduped), 1, "완전 중복(같은 term_norm + 같은 easy_term)은 하나로 합쳐져야 한다")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestRealDataRegression(unittest.TestCase):
    """2026-08-27 실데이터 투입(`data/raw/`) 회귀 테스트.

    `data/raw/`에는 이제 국립국어원 실데이터(nikl_admin_terms_2018.csv,
    1,075행)와 복지 시드 2종이 있다. 여기서는 `build.main()`으로 전체 빌드를
    돌리지 않는다(팀장 지시: 스위트가 느려짐) — `read_csv_rows`/`row_to_entries`/
    `classify` 같은 파싱·분류 단위 함수만 직접 호출해 가볍게 검증한다.
    `data/raw/`의 파일은 읽기만 하고 절대 수정하지 않는다.
    """

    def test_nikl_admin_header_resolved(self) -> None:
        # nikl_admin_terms_2018.csv 헤더: '원어,순화어' (분야/뜻 등 부가 컬럼 없음)
        _rows, colmap = build.read_csv_rows(DATA_RAW / "nikl_admin_terms_2018.csv")
        self.assertEqual(colmap["term"], "원어")
        self.assertEqual(colmap["easy_term"], "순화어")

    def test_nikl_admin_terms_no_stray_comma_or_asterisk(self) -> None:
        # 실데이터 감사에서 발견된 327건 매칭 불능 문제의 회귀 테스트: 원어
        # 컬럼에 '내레이션, 나레이션*(narration(영))'처럼 같은 개념의 다른
        # 표기가 쉼표로 나열되고 비표준 표기엔 '*'가 붙는다. 이를 그대로
        # entries.term에 남기면(쉼표/별표 포함) 문서 매칭에 절대 안 걸린다.
        from easydict.models import Source

        rows, colmap = build.read_csv_rows(DATA_RAW / "nikl_admin_terms_2018.csv")
        source = Source(code="test:nikl-admin-2018", name="테스트용 국립국어원 출처")

        bad: list[tuple[int, str]] = []
        total_entries = 0
        for lineno, row in enumerate(rows, start=2):
            for entry in build.row_to_entries(row, colmap, source, lineno):
                total_entries += 1
                if "," in entry.term or "*" in entry.term:
                    bad.append((lineno, entry.term))

        self.assertGreater(total_entries, 900, "1,075행 대부분이 엔트리로 파싱되어야 한다")
        self.assertEqual(
            bad, [],
            f"표제어에 쉼표/별표가 남은 행이 {len(bad)}건 있다 (앞 10건): {bad[:10]}",
        )

    def test_welfare_seed_explicit_strategy_columns_respected(self) -> None:
        # welfare_seed_1.csv는 replace_strategy/risk_level/status를 사람이 직접
        # 명시한 컬럼을 갖고 있다(§요청 3, row_to_entries의 has_explicit 분기).
        # '고등교육법'은 법령명 고유명사라 keep+high+review로 명시되어 있다.
        from easydict.models import Source

        rows, colmap = build.read_csv_rows(DATA_RAW / "welfare_seed_1.csv")
        self.assertIn("replace_strategy", colmap, "welfare_seed_1.csv는 replace_strategy 컬럼을 명시로 갖고 있어야 한다")

        source = Source(code="test:welfare-seed-1", name="테스트용 복지 시드 출처")
        by_term: dict[str, list[Entry]] = {}
        for lineno, row in enumerate(rows, start=2):
            for entry in build.row_to_entries(row, colmap, source, lineno):
                classified = build.classify(entry)
                by_term.setdefault(classified.term, []).append(classified)

        self.assertIn("고등교육법", by_term, "'고등교육법' 행이 파싱되지 않았다")
        entry = by_term["고등교육법"][0]
        self.assertEqual(entry.replace_strategy, "keep", "CSV가 명시한 replace_strategy=keep이 반영되어야 한다")
        self.assertEqual(entry.risk_level, "high", "CSV가 명시한 risk_level=high가 반영되어야 한다")
        self.assertEqual(entry.status, "review", "CSV가 명시한 status=review가 반영되어야 한다")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestReadabilityExplicit(unittest.TestCase):
    """word_grade(초급/중급/고급) 명시값이 readability(1~3)에 반영되는지 (§요청 4).

    한국어기초사전 API의 word_grade가 COLUMN_ALIASES에서 readability로
    해석된다(data/raw/krdict_advanced.csv). row_to_entries -> classify
    파이프라인만 단위 호출한다(build.main() 전체 빌드는 없음).
    """

    def test_grade_names_and_numeric_both_parse(self) -> None:
        self.assertEqual(build._parse_readability("초급"), 1)
        self.assertEqual(build._parse_readability("중급"), 2)
        self.assertEqual(build._parse_readability("고급"), 3)
        self.assertEqual(build._parse_readability("1"), 1)
        self.assertEqual(build._parse_readability("2"), 2)
        self.assertEqual(build._parse_readability("3"), 3)

    def test_out_of_range_numeric_raises(self) -> None:
        with self.assertRaises(ValueError):
            build._parse_readability("0")
        with self.assertRaises(ValueError):
            build._parse_readability("4")

    def test_unparseable_value_raises(self) -> None:
        with self.assertRaises(ValueError):
            build._parse_readability("보통")

    def test_row_to_entries_applies_explicit_readability_and_classify_preserves_it(self) -> None:
        from easydict.models import Source

        row = {"원어": "테스트어", "순화어": "쉬운말", "등급": "고급"}
        colmap = build.resolve_columns(list(row.keys()))
        source = Source(code="test:readability", name="테스트용 출처")
        entries = build.row_to_entries(row, colmap, source, lineno=2)
        self.assertEqual(len(entries), 1)
        entry = entries[0]
        self.assertEqual(entry.readability, 3, "word_grade='고급'이 readability=3으로 반영되어야 한다")

        # classify()가 이 명시값을 덮어쓰면 안 된다. explicit_strategy 컬럼이
        # 없으므로 replace_strategy는 휴리스틱(substitute)으로 정해지는데,
        # substitute의 휴리스틱 기본 readability는 1이다 — 만약 classify()가
        # 명시값을 무시하고 덮어썼다면 이 값이 1로 바뀌어 드러난다.
        classified = build.classify(entry)
        self.assertEqual(classified.replace_strategy, "substitute")
        self.assertEqual(classified.readability, 3, "classify()가 명시된 readability를 덮어쓰면 안 된다")

    def test_no_explicit_readability_falls_back_to_derived_value(self) -> None:
        entry = Entry(term="내방", easy_term="방문")
        result = build.classify(entry)
        self.assertEqual(result.replace_strategy, "substitute")
        self.assertEqual(result.readability, 1, "명시값이 없으면 기존처럼 전략에서 파생되어야 한다(substitute=1)")

    def test_keep_readability_splits_amount_date_vs_law_name(self) -> None:
        amount_entry = Entry(term="월 30만 원", easy_term="금액이니 그대로 씀")
        amount_result = build.classify(amount_entry)
        self.assertEqual(amount_result.replace_strategy, "keep")
        self.assertEqual(amount_result.readability, 1, "금액·기한 표현은 keep이어도 readability=1이어야 한다")

        law_entry = Entry(term="「국민기초생활 보장법」", easy_term="법 이름이니 그대로 씀")
        law_result = build.classify(law_entry)
        self.assertEqual(law_result.replace_strategy, "keep")
        self.assertEqual(law_result.readability, 3, "법령명·제도 고유명칭은 keep 중에서도 readability=3이어야 한다")

    def test_krdict_real_row_word_grade_applied(self) -> None:
        # 실데이터 확인: krdict_advanced.csv 전 행이 word_grade='고급'이다.
        from easydict.models import Source

        rows, colmap = build.read_csv_rows(DATA_RAW / "krdict_advanced.csv")
        self.assertIn(
            "readability", colmap,
            "krdict_advanced.csv의 word_grade 컬럼이 readability로 해석되어야 한다",
        )

        source = Source(code="test:krdict", name="테스트용 한국어기초사전 출처")
        by_term: dict[str, list[Entry]] = {}
        for lineno, row in enumerate(rows, start=2):
            for entry in build.row_to_entries(row, colmap, source, lineno):
                by_term.setdefault(entry.term, []).append(entry)

        self.assertIn("난임", by_term, "'난임' 행이 파싱되지 않았다")
        self.assertEqual(
            by_term["난임"][0].readability, 3,
            "실데이터 word_grade='고급'이 readability=3으로 반영되어야 한다",
        )


class TestClassifyOriginLanguage(unittest.TestCase):
    """`_classify_origin()` 어종 판정 회귀 테스트.

    실데이터(국립국어원 다듬은말, 원어 컬럼) 감사에서 발견된 결함: 한자를
    뺀 "나머지"가 하나라도 있으면 무조건 loanword로 판정하던 예전 로직은
    `提供--`처럼 활용어미 자리표시자(`--`, split_hanja의 架設-- 관례와 동일)가
    남는 경우까지 외래어로 오판했다(1,808건, 한자 포함 행의 16.7%). 처음
    가설은 "한글이 원인"이었지만 실측하니 대시가 원인이었다 — 로마자·가나
    존재 여부를 직접 검사하는 지금 방식은 원인이 무엇이든(대시·한글·구두점)
    무해하게 무시하고 진짜 외래어 신호만 남긴다.
    """

    def test_hanja_with_dash_placeholder_is_hanja_only(self) -> None:
        # '提供--' = 提供(한자) + 활용어미 자리표시자. 로마자·가나가 전혀
        # 없으므로 loanword가 아니다.
        term_hanja, is_loanword = build._classify_origin("提供--")
        self.assertEqual(term_hanja, "提供")
        self.assertFalse(is_loanword, "한자+대시 자리표시자만 있으면 loanword가 아니어야 한다")

    def test_mixed_latin_and_hanja_gets_both(self) -> None:
        # 'lagging效果' = 영어 'lagging' + 한자 '效果'. 둘 다 정보를 보존한다.
        term_hanja, is_loanword = build._classify_origin("lagging效果")
        self.assertEqual(term_hanja, "效果")
        self.assertTrue(is_loanword, "로마자 성분이 실제로 있으면 loanword여야 한다")

    def test_pure_latin_is_loanword_only(self) -> None:
        term_hanja, is_loanword = build._classify_origin("fabless")
        self.assertIsNone(term_hanja)
        self.assertTrue(is_loanword)

    def test_pure_hanja_is_not_loanword(self) -> None:
        term_hanja, is_loanword = build._classify_origin("居住地")
        self.assertEqual(term_hanja, "居住地")
        self.assertFalse(is_loanword)

    def test_hanja_with_kana_is_loanword(self) -> None:
        # 실데이터 '복지'(服地, ふくじ(일)): 한자+가나 혼합이라 loanword가 맞다
        # (양복감/양복천이라는 뜻이며, 복지=福祉 'welfare'와는 다른 동음이의어).
        term_hanja, is_loanword = build._classify_origin("服地, ふくじ(일)")
        self.assertEqual(term_hanja, "服地")
        self.assertTrue(is_loanword, "가나가 실제로 있으면 loanword여야 한다")

    def test_hanja_with_bracketed_gloss_annotation_is_not_loanword(self) -> None:
        # 실데이터(다듬은말 후보 풀) 감사에서 발견된 결함: 순수 한자어 뒤에
        # "(영어 gloss(언어코드))" 형태의 말미 주석이 붙으면 그 영어 gloss
        # 때문에 loanword=True로 오판됐다 — 이 한자어 자체는 외래어가
        # 아니라 그냥 한자어에 영어 번역 참고용 주석이 달렸을 뿐이다.
        # '(언어코드)'로 끝나는 말미 괄호만 주석으로 보고 제외한다
        # (_has_language_marker 재사용 — 괄호 처리 로직을 새로 안 짬).
        term_hanja, is_loanword = build._classify_origin("壓挫症候群(crush syndrome(영))")
        self.assertEqual(term_hanja, "壓挫症候群")
        self.assertFalse(is_loanword, "말미의 '설명(언어코드)' 주석은 어종 판정에서 제외해야 한다")

    def test_leading_paren_origin_marker_is_still_loanword(self) -> None:
        # 회귀 안전판: 어원 신호가 "말미" 괄호가 아니라 선두에 있는 경우
        # (예: '(←hiphop+hoop) 籠球')는 이 함수가 "말미 괄호만" 떼기
        # 때문에 저절로 안전하다 — 원문 전체를 스캔해 로마자를 그대로 잡는다.
        term_hanja, is_loanword = build._classify_origin("(←hiphop+hoop) 籠球")
        self.assertEqual(term_hanja, "籠球")
        self.assertTrue(is_loanword, "선두 괄호의 어원 신호는 그대로 보존돼야 한다")

    def test_kana_before_trailing_language_marker_is_still_loanword(self) -> None:
        # 회귀 안전판: 가나가 말미 괄호(언어 코드) *앞* 본문에 있는 경우
        # (일본어 사전 표기 관례, 예: '踏(み)板(일)')는 말미 '(일)'만 떼도
        # 가나 'み'는 본문에 그대로 남아 loanword=True가 유지돼야 한다.
        term_hanja, is_loanword = build._classify_origin("踏(み)板(일)")
        self.assertEqual(term_hanja, "踏板")
        self.assertTrue(is_loanword, "말미 괄호 앞 본문에 있는 가나는 보존돼야 한다")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestFinalizeExamplesInflection(unittest.TestCase):
    """`_finalize_examples()`의 substitute 분기 — 활용형 매치는 gloss로 폴백해야 한다.

    실제 코퍼스 예문 추출(tools/extract_examples.py) 표본을 눈으로 검토하다
    발견한 결함: '수령하실'처럼 term(`수령`) 바로 뒤에 활용 어미가 붙은
    문장을 substitute_with_josa()로 그대로 치환하면 어간만 잘려나가
    '받음하실'(비문)이 나왔다. lookup.EasyDict.annotate()는 이미
    Match.is_inflected로 같은 사고를 막아 두고 있었는데(활용형 매치는
    치환 대신 gloss 표기로 폴백), _finalize_examples()의 substitute 경로만
    그 보호가 없었다 — 이 클래스는 그 보호가 build.py 쪽에도 적용됐는지
    값으로 확인한다.
    """

    def _finalize(self, term: str, easy: str, before: str, strategy: str = "substitute") -> str:
        entry = Entry(term=term, easy_term=easy, replace_strategy=strategy)
        entry.examples.append(Example(before_text=before, after_text=""))
        build._finalize_examples(entry)
        self.assertEqual(len(entry.examples), 1, "term이 실제로 있는 문장이 버려지면 안 된다")
        return entry.examples[0].after_text

    def test_inflected_verb_suffix_does_not_produce_broken_word(self) -> None:
        after = self._finalize("수령", "받음", "현금으로 수령하실 수 없습니다.")
        self.assertNotIn("받음하실", after, "어간만 잘라 바꾸면 비문이 된다 — 활용형은 gloss로 보존해야 한다")
        self.assertEqual(after, "현금으로 수령(받음)하실 수 없습니다.")

    def test_inflected_verb_suffix_hamnida_form(self) -> None:
        after = self._finalize("개시", "드러내 보임", "지원을 개시합니다.")
        self.assertNotIn("드러내 보임합니다", after)
        self.assertEqual(after, "지원을 개시(드러내 보임)합니다.")

    def test_base_form_match_still_substitutes_with_josa_correction(self) -> None:
        # 회귀 안전판: 원형 매치(뒤가 진짜 어절 경계)는 여전히 그대로 치환되고,
        # 받침이 바뀌면 조사도 교정돼야 한다('는'->'은').
        after = self._finalize("급여", "지원금", "급여는 월 30만 원입니다.")
        self.assertEqual(after, "지원금은 월 30만 원입니다.")

    def test_base_form_match_at_sentence_boundary(self) -> None:
        # 문장 끝에서 term이 조사 없이 그대로 끝나는(원형 매치) 경우도
        # 활용형으로 오판되면 안 된다.
        after = self._finalize("상기", "위", "상기에 관한 설명을 모두 이해하였으며 이에 동의합니다.")
        self.assertEqual(after, "위에 관한 설명을 모두 이해하였으며 이에 동의합니다.")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestExamplesOnlySourceRole(unittest.TestCase):
    """`--source-role examples` 회귀 테스트 (DESIGN.md §5.5(7)).

    실측된 사고: 예문만 얹으려던 원천(corpus_examples_golden57.csv)이
    `upsert()`의 `ON CONFLICT DO UPDATE`를 그대로 타면서 기존 엔트리
    318개의 `source_id`까지 가로챘다. 그 원천이 §6.8 원천 신뢰도 표에
    없어(tier 0) 승자가 뒤집혔다('모니터링'->'정보 수집'이 지고 '점검'이
    이김). 이 클래스는 그 사고가 재발하지 않는지 값으로 확인한다 —
    "규칙을 일부러 빼서 실패하는지 확인하고 원복한다"는 이 세션의 원칙대로,
    `main()`의 `if source_roles[i] == "examples":` 분기를 주석 처리하면
    (원복 전에 실제로 확인함) `test_examples_source_does_not_change_source_id`가
    실패하는 것까지 확인했다.
    """

    SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"

    def _run(self, extra_input: tuple[str, str, str] | None = None) -> Path:
        """샘플 3종(raw_terms/raw_terms_law/raw_terms_welfare_cp949)으로 빌드한다.

        extra_input이 있으면 (csv_path, source_code, role) 튜플로 4번째
        --input을 추가한다.
        """
        db_path = Path(self._tmp.name) / "easy_dict.sqlite3"
        argv = [
            "--input", str(SAMPLE_CSV_DIR / "raw_terms.csv"),
            "--input", str(SAMPLE_CSV_DIR / "raw_terms_law.csv"),
            "--input", str(SAMPLE_CSV_DIR / "raw_terms_welfare_cp949.csv"),
            "--source-code", "data.go.kr:admin-terms",
            "--source-code", "moleg.go.kr:law-terms",
            "--source-code", "data.go.kr:welfare-terms",
            "--source-name", "행정용어 순화어 대조표",
            "--source-name", "법률용어 순화어 대조표",
            "--source-name", "복지용어 순화어 대조표",
        ]
        if extra_input is not None:
            csv_path, source_code, role = extra_input
            argv += ["--input", csv_path, "--source-code", source_code, "--source-name", "예문 전용 테스트 원천"]
            # 앞 3개는 role 미지정(기본값 primary), 마지막 1개만 examples.
            argv += ["--source-role", "primary", "--source-role", "primary",
                     "--source-role", "primary", "--source-role", role]
        argv += ["--db", str(db_path), "--reset", "--schema", str(self.SCHEMA_SQL_PATH)]

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = build.main(argv)
        if rc != 0:
            raise AssertionError(f"build.main() 실패(rc={rc}):\n{buf.getvalue()}")
        return db_path

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="easydict_examples_role_test_")
        self.addCleanup(self._tmp.cleanup)

    def _write_examples_csv(self, rows: list[tuple[str, str, str]]) -> str:
        """(원어, 순화어, 예문) 최소 3열짜리 examples-role CSV를 만든다."""
        path = Path(self._tmp.name) / "examples_only.csv"
        with path.open("w", encoding="utf-8-sig", newline="") as f:
            f.write("원어,순화어,예문\n")
            for term, easy, example in rows:
                f.write(f'"{term}","{easy}","{example}"\n')
        return str(path)

    def test_examples_source_does_not_change_source_id(self) -> None:
        # 베이스라인: examples 원천 없이 먼저 빌드해 '내방'의 원래 source_id를 기록한다.
        base_db = self._run()
        conn = sqlite3.connect(f"file:{base_db}?mode=ro", uri=True)
        row = conn.execute(
            "SELECT id, source_id FROM entries WHERE term='내방' AND easy_term='방문'"
        ).fetchone()
        self.assertIsNotNone(row, "샘플 CSV에 '내방'->'방문'이 있어야 한다")
        entry_id, original_source_id = row
        conn.close()

        # examples 원천을 4번째 --input으로 추가해 재빌드한다. '내방'의
        # 원형 매칭 예문을 하나 더 준다(기존 원천 예문과 다른 문장).
        csv_path = self._write_examples_csv([
            ("내방", "방문", "내방 목적을 미리 알려 주시기 바랍니다."),
        ])
        new_db = self._run(extra_input=(csv_path, "easydict:examples-test", "examples"))

        conn = sqlite3.connect(f"file:{new_db}?mode=ro", uri=True)
        row = conn.execute(
            "SELECT id, source_id FROM entries WHERE term='내방' AND easy_term='방문'"
        ).fetchone()
        self.assertIsNotNone(row)
        new_entry_id, new_source_id = row
        self.assertEqual(new_entry_id, entry_id, "엔트리 자체가 재생성되면 안 된다")
        self.assertEqual(
            new_source_id, original_source_id,
            "예문 전용 원천이 기존 엔트리의 source_id를 바꾸면 안 된다(DESIGN.md §5.5(7))",
        )

        examples = conn.execute(
            "SELECT before_text FROM examples WHERE entry_id = ?", (new_entry_id,)
        ).fetchall()
        example_texts = {e[0] for e in examples}
        self.assertIn(
            "내방 목적을 미리 알려 주시기 바랍니다.", example_texts,
            "examples 원천이 기여한 예문이 실제로 들어가야 한다",
        )
        conn.close()

    def test_examples_source_unmatched_term_is_discarded_not_created(self) -> None:
        entries_before = 0
        base_db = self._run()
        conn = sqlite3.connect(f"file:{base_db}?mode=ro", uri=True)
        entries_before = conn.execute("SELECT count(*) FROM entries").fetchone()[0]
        conn.close()

        csv_path = self._write_examples_csv([
            ("존재하지않는표제어", "없는말", "이 표제어는 사전에 없는 상태로 테스트한다."),
        ])
        new_db = self._run(extra_input=(csv_path, "easydict:examples-test2", "examples"))
        conn = sqlite3.connect(f"file:{new_db}?mode=ro", uri=True)
        entries_after = conn.execute("SELECT count(*) FROM entries").fetchone()[0]
        found = conn.execute(
            "SELECT count(*) FROM entries WHERE term='존재하지않는표제어'"
        ).fetchone()[0]
        conn.close()
        self.assertEqual(entries_before, entries_after, "사전에 없는 표제어라도 엔트리를 새로 만들면 안 된다")
        self.assertEqual(found, 0)

    def test_examples_source_row_count_unaffected_by_role(self) -> None:
        # 회귀 안전판: examples 원천을 얹어도 원래 3종 샘플에서 나오는
        # 엔트리 수 자체는 그대로여야 한다(§5.5(1) 재현성 원칙의 연장).
        base_db = self._run()
        csv_path = self._write_examples_csv([
            ("내방", "방문", "내방 목적을 미리 알려 주시기 바랍니다."),
        ])
        new_db = self._run(extra_input=(csv_path, "easydict:examples-test3", "examples"))
        conn1 = sqlite3.connect(f"file:{base_db}?mode=ro", uri=True)
        conn2 = sqlite3.connect(f"file:{new_db}?mode=ro", uri=True)
        n1 = conn1.execute("SELECT count(*) FROM entries").fetchone()[0]
        n2 = conn2.execute("SELECT count(*) FROM entries").fetchone()[0]
        conn1.close()
        conn2.close()
        self.assertEqual(n1, n2)


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestPerSourceArgCountValidation(unittest.TestCase):
    """원천별 인자(`--organization`/`--license`/`--default-tag`/`--source-role`)
    개수 검증 회귀 테스트 (docs/inspection-plan.md Phase 3 작업 1).

    실측된 사고: `--source-role`을 **마지막 원천에만**(1개) 줬는데 `--input`은
    4개였다. 옛 코드는 `(list(...) + ["primary"] * n)[:n]` 패딩으로 그 값을
    **인덱스 0**에 배정해 1,717건짜리 원천(`nikl:admin2018`)이 통째로 예문
    전용으로 처리됐다(엔트리 2,142 -> 449). 이 클래스는 그 명령을 그대로
    재현해 이제 조용히 통과하지 않고 **크게 실패**하는지 확인한다.

    검증 자체는 `main()`이 CSV를 읽기 전에(`create_db()` 호출보다도 전에)
    일어나므로, 개수가 안 맞아 **거부되는** 경우는 파일 I/O가 전혀 없다.
    다만 개수 검증을 **통과**하는 경우(0개 또는 n개)는 그 뒤 `create_db()`가
    실행돼 `--db` 경로에 실제로 SQLite 파일이 생긴다 — 그래서 `--db`는
    실재하지 않는 더미 이름이 아니라 매번 정리되는 임시 디렉터리 경로를
    쓴다(레포 루트에 `dummy.sqlite3`가 남는 사고를 겪고 고쳤다).
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="easydict_arg_count_test_")
        self.addCleanup(self._tmp.cleanup)

    def _argv(self, n: int, **per_source_overrides: list[str]) -> list[str]:
        """`--input`/`--source-code`/`--source-name`을 n개씩 채운 기본 argv에
        `per_source_overrides`(예: source_roles=["examples"])를 덧붙인다."""
        argv: list[str] = []
        for i in range(n):
            argv += ["--input", f"dummy_{i}.csv"]
            argv += ["--source-code", f"dummy:source-{i}"]
            argv += ["--source-name", f"더미 원천 {i}"]
        flag_by_dest = {
            "organizations": "--organization",
            "licenses": "--license",
            "default_tags": "--default-tag",
            "source_roles": "--source-role",
        }
        for dest, values in per_source_overrides.items():
            flag = flag_by_dest[dest]
            for v in values:
                argv += [flag, v]
        argv += ["--db", str(Path(self._tmp.name) / "dummy.sqlite3")]
        return argv

    def test_source_role_given_once_for_four_inputs_is_rejected(self) -> None:
        # 팀장이 실제로 당한 명령 그대로: --input 4개에 --source-role 1개.
        argv = self._argv(4, source_roles=["examples"])
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = build.main(argv)
        self.assertEqual(rc, 2, "개수가 안 맞는 --source-role은 빌드를 중단시켜야 한다")
        err = buf.getvalue()
        self.assertIn("--source-role", err)
        self.assertIn("1개", err, "몇 개가 주어졌는지 오류 메시지에 나와야 한다")

    def test_source_role_omitted_entirely_still_works_as_primary(self) -> None:
        # 하위 호환: --source-role을 아예 안 주면(0개) 전부 기본값 'primary'로
        # 통과해야 한다 — 존재하지 않는 더미 CSV라 read_csv_rows에서 실패하지만,
        # 그 실패는 인자 개수 검증(rc=2, 우리가 막으려는 것) 이후의 별개
        # 단계(rc도 2지만 메시지가 다르다)이므로 오류 메시지로 구분한다.
        argv = self._argv(4)
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            rc = build.main(argv)
        self.assertEqual(rc, 2)  # 더미 파일이 실재하지 않아 이후 단계에서 실패
        self.assertNotIn("원천별 인자 개수가 안 맞습니다", buf_err.getvalue())
        self.assertIn("입력 파일이 없습니다", buf_err.getvalue())

    def test_source_role_given_for_all_four_inputs_passes_count_check(self) -> None:
        argv = self._argv(4, source_roles=["primary", "primary", "primary", "examples"])
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            rc = build.main(argv)
        self.assertNotIn("원천별 인자 개수가 안 맞습니다", buf_err.getvalue())
        self.assertIn("입력 파일이 없습니다", buf_err.getvalue())  # 개수 검증은 통과하고 더 뒤에서 실패

    def test_partial_default_tag_is_rejected(self) -> None:
        argv = self._argv(3, default_tags=["welfare"])  # 3개 중 1개만
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = build.main(argv)
        self.assertEqual(rc, 2)
        self.assertIn("--default-tag", buf.getvalue())

    def test_partial_organization_is_rejected(self) -> None:
        argv = self._argv(3, organizations=["행정안전부", "법제처"])  # 3개 중 2개
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = build.main(argv)
        self.assertEqual(rc, 2)
        self.assertIn("--organization", buf.getvalue())

    def test_partial_license_is_rejected(self) -> None:
        argv = self._argv(2, licenses=["공공누리 제1유형"])  # 2개 중 1개
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = build.main(argv)
        self.assertEqual(rc, 2)
        self.assertIn("--license", buf.getvalue())

    def test_organization_omitted_entirely_still_works(self) -> None:
        # 0개(완전 생략)는 --organization/--license 원래 취지대로 여전히 허용.
        argv = self._argv(2)
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            rc = build.main(argv)
        self.assertNotIn("원천별 인자 개수가 안 맞습니다", buf_err.getvalue())

    def test_organization_given_for_all_inputs_passes_count_check(self) -> None:
        argv = self._argv(2, organizations=["행정안전부", "법제처"])
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            rc = build.main(argv)
        self.assertNotIn("원천별 인자 개수가 안 맞습니다", buf_err.getvalue())


if __name__ == "__main__":
    unittest.main()
