"""src/easydict/normalize.py 계약 테스트 (DESIGN.md §3.4, §6.2).

easydict.normalize 는 이미 구현되어 있을 수 있지만, 이 파일은 병렬 작업 중인
다른 모듈과 마찬가지로 아직 없을 가능성을 대비해 import 실패 시 건너뛴다.
"""

from __future__ import annotations

import re
import unittest

try:
    from easydict import normalize
    _IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover - 모듈 부재 시에만 발동
    normalize = None  # type: ignore[assignment]
    _IMPORT_ERROR = e


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestSplitHanja(unittest.TestCase):
    def test_splits_hanja_gloss(self) -> None:
        self.assertEqual(normalize.split_hanja("내방(來訪)"), ("내방", "來訪"))

    def test_no_paren_returns_none(self) -> None:
        self.assertEqual(normalize.split_hanja("방문"), ("방문", None))

    def test_does_not_mistake_korean_gloss_paren_for_hanja(self) -> None:
        # '과태료(늦게 내는 돈)' 처럼 괄호 안이 한글 설명이면 한자 병기가 아니다.
        # 여기서 잘못 분리하면 뜻풀이가 통째로 날아간다 (§3.4 안전장치).
        term, hanja = normalize.split_hanja("과태료(늦게 내는 돈)")
        self.assertIsNone(hanja)
        self.assertEqual(term, "과태료(늦게 내는 돈)")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestGenVariants(unittest.TestCase):
    def test_hada_verb_conjugations(self) -> None:
        surfaces = [v.surface for v in normalize.gen_variants("명기하다", "verb")]
        for expected in ("명기하여", "명기한", "명기해야"):
            self.assertIn(expected, surfaces, f"'{expected}' 가 명기하다 변형형에 없음: {surfaces}")
        self.assertNotIn("명기하다", surfaces, "원형 자신은 변형형 목록에 포함되면 안 된다")
        # 2026-08-29(DESIGN.md §5.5.1 "같은 위험이 변형형에도 있다"): 어근
        # 명사형 자체는 더 이상 생성하지 않는다 — 실측(가하다/부상하다 등)
        # 골든 코퍼스에서 이 어간이 열거 기호·무관한 명사를 삼키는 결함으로
        # 이어졌다. 활용 어미가 붙은 형태는 계속 생성돼야 한다(위 assertIn).
        self.assertNotIn("명기", surfaces, "'~하다' 어간 자체는 더 이상 변형형으로 생성되면 안 된다")

    def test_doeda_verb_conjugations(self) -> None:
        surfaces = [v.surface for v in normalize.gen_variants("통보되다", "verb")]
        for expected in ("통보되어", "통보된", "통보됩니다"):
            self.assertIn(expected, surfaces, f"'{expected}' 가 통보되다 변형형에 없음: {surfaces}")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestGenVariantsNounHada(unittest.TestCase):
    """E-2 회귀 (DESIGN.md §8 "명사 하다-파생"): '지참'/'내방' 같은 명사도 실제 문서에서는
    '지참하시기'/'내방하시면'처럼 '~하다'가 결합한 활용형으로 나타난다.

    아직 core-lib에 구현되지 않았다면 이 테스트는 실패한다 — 그것이 정상이다
    (team-lead 지시: 구현 완료 전까지 폴링하며 재실행, 실패 시 고치지 말고 보고).

    동시에 모든 명사에 하다-파생을 씌우면 '구비서류하여' 같은 존재하지 않는 말이
    생긴다(과생성). '구비서류'는 하다와 결합할 수 없는 명사이므로 파생이 없어야 한다.
    """

    def test_jicham_noun_generates_hada_derived_forms(self) -> None:
        surfaces = [v.surface for v in normalize.gen_variants("지참", "noun")]
        self.assertIn(
            "지참하시기", surfaces,
            f"명사 '지참'에서 '지참하시기' 활용형이 생성되어야 한다: {surfaces}",
        )

    def test_naebang_noun_generates_hada_derived_forms(self) -> None:
        surfaces = [v.surface for v in normalize.gen_variants("내방", "noun")]
        self.assertIn(
            "내방하시면", surfaces,
            f"명사 '내방'에서 '내방하시면' 활용형이 생성되어야 한다: {surfaces}",
        )

    def test_non_verbal_noun_does_not_overgenerate(self) -> None:
        surfaces = [v.surface for v in normalize.gen_variants("구비서류", "noun")]
        bogus = [s for s in surfaces if s.startswith("구비서류하")]
        self.assertEqual(
            bogus, [],
            f"'구비서류'는 하다와 결합할 수 없는 명사인데 활용형이 생성됨(과생성): {bogus}",
        )


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestGenVariantsNounDoeda(unittest.TestCase):
    """doeda-fix 회귀: 서술성 명사는 능동(~하다)뿐 아니라 피동(~되다)으로도
    실제 문서에 나타난다. 행정 문서는 '반려되었으니', '지급됩니다'처럼
    처분·통지 문형에서 서술성 명사를 피동으로 훨씬 자주 쓰는데, 원래
    구현은 능동 어미(_HADA_SUFFIXES)만 붙이고 피동 어미(_DOEDA_SUFFIXES)를
    빠뜨려서 이런 문장에서 매칭이 실패했다.

    이 테스트는 새 상수가 아니라 `~되다` 동사 분기가 쓰는 것과 같은
    `_DOEDA_SUFFIXES`가 재사용되는지를 값으로 검증한다.
    """

    def test_banryeo_noun_generates_doeda_derived_forms(self) -> None:
        surfaces = [v.surface for v in normalize.gen_variants("반려", "noun")]
        self.assertIn(
            "반려되었으니", surfaces,
            f"명사 '반려'에서 '반려되었으니' 활용형이 생성되어야 한다: {surfaces}",
        )

    def test_jicham_noun_still_generates_hada_derived_forms(self) -> None:
        # 되다 파생 추가가 기존 하다 파생(B-3, 위 TestGenVariantsNounHada)을
        # 회귀시키지 않는지 확인한다.
        surfaces = [v.surface for v in normalize.gen_variants("지참", "noun")]
        self.assertIn(
            "지참하시기", surfaces,
            f"'지참하시기'는 되다 파생 추가 후에도 계속 생성되어야 한다: {surfaces}",
        )

    def test_doeda_suffixes_are_reused_not_duplicated(self) -> None:
        # 서술성 명사 되다-파생 표면형 집합이 정확히
        # {term + suf for suf in _DOEDA_SUFFIXES} 와 일치하는지 확인해,
        # 별도 상수를 새로 만들지 않고 기존 _DOEDA_SUFFIXES를 재사용했음을
        # 값으로 못박는다.
        surfaces = {v.surface for v in normalize.gen_variants("반려", "noun")}
        expected_doeda = {"반려" + suf for suf in normalize._DOEDA_SUFFIXES}
        self.assertTrue(
            expected_doeda.issubset(surfaces),
            f"_DOEDA_SUFFIXES의 모든 어미가 반려에 붙어야 한다: 누락 {expected_doeda - surfaces}",
        )


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestNormalizeKey(unittest.TestCase):
    def test_spacing_variants_normalize_equal(self) -> None:
        self.assertEqual(normalize.normalize_key("차상위 계층"), normalize.normalize_key("차상위계층"))


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestGuessPos(unittest.TestCase):
    def test_verb(self) -> None:
        self.assertEqual(normalize.guess_pos("명기하다"), "verb")

    def test_adjective(self) -> None:
        self.assertEqual(normalize.guess_pos("자유롭다"), "adjective")

    def test_adverb(self) -> None:
        self.assertEqual(normalize.guess_pos("조용히"), "adverb")

    def test_phrase(self) -> None:
        self.assertEqual(normalize.guess_pos("차상위 계층"), "phrase")

    def test_noun(self) -> None:
        self.assertEqual(normalize.guess_pos("내방"), "noun")


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestSplitMulti(unittest.TestCase):
    """B-6 회귀: 순화어 컬럼에 '나열'과 '정의문' 두 성격이 섞여 들어오게 됐다
    (한국어기초사전 API 유입, data/raw/krdict_advanced.csv). 판별 신호는
    마침표다 — 나열 실데이터(1,186행)는 단 한 건도 마침표로 끝나지 않고,
    정의문 실데이터(krdict 114행)는 전부 마침표로 끝난다.
    """

    def test_splits_short_enumeration(self) -> None:
        self.assertEqual(normalize.split_multi("방문, 찾아옴 / 오다"), ["방문", "찾아옴", "오다"])

    def test_enumeration_with_four_items_still_split(self) -> None:
        # 조각이 4개라 원문 전체 공백 수는 3을 넘지만, 조각 하나하나는 짧으므로
        # 여전히 나열로 쪼개져야 한다(예전 "공백 3개 초과면 서술문" 휴리스틱의 역효과 회귀).
        self.assertEqual(
            normalize.split_multi("허가증, 영업 허가증, 등록증, 영업 등록증"),
            ["허가증", "영업 허가증", "등록증", "영업 등록증"],
        )

    def test_circled_number_enumeration_split(self) -> None:
        self.assertEqual(normalize.split_multi("① 다침 ② 상처"), ["다침", "상처"])

    def test_descriptive_sentence_not_split(self) -> None:
        text = "주소지를 옮기는 것을 말하며, 전입신고와 함께 처리합니다"
        self.assertEqual(normalize.split_multi(text), [text])

    def test_definition_sentences_not_split(self) -> None:
        # data/raw/krdict_advanced.csv 실데이터 그대로 (수급/수립/입소/난임).
        # 쉼표가 있어도 마침표로 끝나면 정의문 한 문장으로 보고 쪼개지 않는다 —
        # 쪼개면 '수립 → 국가'처럼 틀린 매핑이 만들어진다(B-6).
        cases = [
            "급여나 연금, 배급 등을 받음.",              # 수급
            "국가, 정부나 제도, 계획 등을 세움.",          # 수립
            "훈련소, 연구소, 교도소 등에 들어감.",         # 입소
            "임신하기 어려움. 또는 그런 상태.",           # 난임 (마침표가 중간에도 있음)
        ]
        for text in cases:
            with self.subTest(text=text):
                self.assertEqual(normalize.split_multi(text), [text])


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.normalize import 실패: {_IMPORT_ERROR}")
class TestJosaPattern(unittest.TestCase):
    def test_boundary_excludes_naebanggaek(self) -> None:
        pat = re.compile(re.escape("내방") + "(?=" + normalize.josa_pattern() + ")")
        self.assertIsNone(pat.match("내방객"), "내방이 내방객에 잘못 매칭되면 안 된다")

    def test_boundary_allows_naebang_eul(self) -> None:
        pat = re.compile(re.escape("내방") + "(?=" + normalize.josa_pattern() + ")")
        self.assertIsNotNone(pat.match("내방을"))

    def test_boundary_allows_end_of_string(self) -> None:
        pat = re.compile(re.escape("내방") + "(?=" + normalize.josa_pattern() + ")")
        self.assertIsNotNone(pat.match("내방"))


if __name__ == "__main__":
    unittest.main()
