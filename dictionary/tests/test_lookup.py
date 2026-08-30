"""src/easydict/lookup.py 계약 테스트 (DESIGN.md §6.5, §7.2, §8).

lookup.py는 아직 구현되지 않았을 수 있다(작업 D 진행 중). import가 실패하면
전체 클래스를 건너뛴다. 손으로 만든 소형 index.json 픽스처를 tests/ 안에
임시 생성해서 쓰고, 각 테스트 종료 후 정리한다.

E-2 (2026-08-27): team-lead가 실제 문장으로 발견한 비문 생성 결함에 대한
회귀 테스트 3종 추가 — 굴절형 치환, keep 경로 end-to-end, 명사+하다 파생 매칭.
"""

from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

try:
    from easydict.lookup import EasyDict
    _IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover
    EasyDict = None  # type: ignore[assignment]
    _IMPORT_ERROR = e

try:
    from easydict.normalize import JOSA, gen_variants
except ImportError:
    # normalize도 없으면 최소한의 조사 목록으로 대체 (테스트가 lookup 부재로 이미 스킵되므로
    # 이 경로가 실제로 실행될 일은 없지만, import 순서를 깨끗하게 하기 위해 방어적으로 둔다).
    JOSA = ("은", "는", "이", "가", "을", "를", "에서", "에게", "으로")
    gen_variants = None  # type: ignore[assignment]

TESTS_DIR = Path(__file__).resolve().parent


def _build_fixture() -> dict:
    """§4.3 형태의 소형 index.json.

    substitute/gloss/keep 및 최장일치 검증용 표제어에 더해(1~5), E-2 회귀
    테스트용 표제어를 추가한다(6~8): 굴절형 매칭이 있는 동사(6), keep 대상인
    법령명 괄호(7)와 금액(8).
    """
    return {
        "schema_version": "1.0.0",
        "josa": list(JOSA),
        "surface_index": {
            "내방": [1],
            "차상위": [2],
            "차상위계층": [3],
            "과태료": [4],
            "기초연금법": [5],
            "명기하다": [6],
            "명기하여": [6],  # gen_variants가 실제로 만들 굴절형을 흉내낸 것
            "「국민기초생활 보장법」": [7],
            "월 30만 원": [8],
        },
        "entries": {
            "1": {"t": "내방", "e": "방문", "d": None, "s": "substitute", "r": "none", "p": 110, "g": []},
            "2": {"t": "차상위", "e": "소득이 적은 편", "d": None, "s": "substitute", "r": "low", "p": 130, "g": []},
            "3": {
                "t": "차상위계층", "e": "기초생활수급자 바로 위의 저소득층", "d": None,
                "s": "gloss", "r": "high", "p": 150, "g": ["welfare"],
            },
            "4": {
                "t": "과태료", "e": "정해진 날짜보다 늦게 내서 더 내는 돈", "d": None,
                "s": "gloss", "r": "high", "p": 130, "g": ["law"],
            },
            "5": {"t": "기초연금법", "e": "기초연금법", "d": None, "s": "keep", "r": "high", "p": 150, "g": ["law"]},
            "6": {"t": "명기하다", "e": "쓰다", "d": None, "s": "substitute", "r": "none", "p": 140, "g": []},
            "7": {
                "t": "「국민기초생활 보장법」", "e": "「국민기초생활 보장법」", "d": None,
                "s": "keep", "r": "high", "p": 200, "g": ["law"],
            },
            "8": {"t": "월 30만 원", "e": "월 30만 원", "d": None, "s": "keep", "r": "high", "p": 200, "g": []},
        },
    }


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class LookupTestCase(unittest.TestCase):
    def setUp(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".index.json", dir=TESTS_DIR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(_build_fixture(), f, ensure_ascii=False)
        self.fixture_path = Path(path)
        self.dict = EasyDict.from_index_json(self.fixture_path)

    def tearDown(self) -> None:
        self.fixture_path.unlink(missing_ok=True)


# ============================================================================
# E-6 회귀: 경계 검사 통합 (DESIGN.md 최신 §8 "매칭 경계").
#
# 이 프로젝트에서 경계 검사는 지금까지 네 번 고쳐졌다 — 매번 실데이터에서
# 터졌고, 매번 다른 방향이었다: '내방객' 배제(오른쪽) -> '급여과장' 배제
# (조사 뒤 한글) -> 연쇄 조사 허용('급여에서는') -> 왼쪽 경계('신청자'류
# 복합어 중간 매칭 방지). 이 클래스 하나에 모든 방향을 모아서, 앞으로 어느
# 방향이 다시 깨져도 이 클래스 안에서 잡히게 한다.
#
# (정리 메모: 이전에 있던 TestMatchBoundary/TestLongestMatch 두 클래스를
# 이 클래스로 흡수·통합했다. 성격이 같은 "경계/최장일치" 테스트가 여러
# 클래스에 흩어져 있으면 팀장님이 지적한 "매번 다른 방향에서 터진다"는
# 문제를 테스트 스위트 구조 자체가 반복하게 된다.)
# ============================================================================
def _build_boundary_fixture() -> dict:
    """경계 검사 전용 소형 index.json.

    1글자 표제어('자')를 반드시 포함한다 — 실데이터(krdict_advanced.csv)에
    갭/붐/소/자/존/팁/폼/한 8종의 1글자 표제어가 있고, 왼쪽 경계 검사가
    없으면 이들이 거의 모든 복합어 안에서 오탐을 낸다.
    """
    return {
        "schema_version": "1.0.0",
        "josa": list(JOSA),
        "surface_index": {
            "내방": [1],
            "차상위": [2],
            "차상위계층": [3],
            "급여": [4],
            "자": [5],
        },
        "entries": {
            "1": {"t": "내방", "e": "방문", "d": None, "s": "substitute", "r": "none", "p": 110, "g": []},
            "2": {"t": "차상위", "e": "형편이 어려운", "d": None, "s": "substitute", "r": "low", "p": 130, "g": []},
            "3": {
                "t": "차상위계층", "e": "기초생활수급자 바로 위의 저소득층", "d": None,
                "s": "gloss", "r": "high", "p": 150, "g": ["welfare"],
            },
            "4": {"t": "급여", "e": "지원금", "d": None, "s": "substitute", "r": "none", "p": 120, "g": []},
            "5": {"t": "자", "e": "사람", "d": None, "s": "substitute", "r": "none", "p": 110, "g": []},
        },
    }


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class BoundaryTestCase(unittest.TestCase):
    def setUp(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".boundary.index.json", dir=TESTS_DIR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(_build_boundary_fixture(), f, ensure_ascii=False)
        self.fixture_path = Path(path)
        self.dict = EasyDict.from_index_json(self.fixture_path)

    def tearDown(self) -> None:
        self.fixture_path.unlink(missing_ok=True)


class TestMatchBoundaries(BoundaryTestCase):
    # ---- 왼쪽 경계 (2026-08-28 수정, 최우선) ----
    def test_left_boundary_blocks_mid_word_start(self) -> None:
        # 1글자 표제어 '자'가 복합어 중간에서 시작되면 안 된다.
        for text in ("신청자 명단", "대상자 선정", "이용자 수"):
            with self.subTest(text=text):
                matches = [m for m in self.dict.find_all(text) if m.term == "자"]
                self.assertEqual(matches, [], f"'{text}'에서 '자'가 단어 중간부터 매칭되면 안 된다")

    def test_left_boundary_allows_string_start(self) -> None:
        matches = [m for m in self.dict.find_all("자를 신청하세요.") if m.term == "자"]
        self.assertEqual(len(matches), 1, "문자열 시작 위치의 표제어는 매칭되어야 한다")
        self.assertEqual((matches[0].start, matches[0].end), (0, 1))

    def test_left_boundary_allows_after_space(self) -> None:
        matches = [m for m in self.dict.find_all("이 자는 학생입니다.") if m.term == "자"]
        self.assertEqual(len(matches), 1, "공백 뒤의 표제어는 매칭되어야 한다")

    def test_left_boundary_allows_after_punctuation(self) -> None:
        matches = [m for m in self.dict.find_all("확인.자를 제출하세요") if m.term == "자"]
        self.assertEqual(len(matches), 1, "문장부호 뒤의 표제어는 매칭되어야 한다")

    # ---- 오른쪽 경계 (이전 라운드 회귀 방지) ----
    def test_right_boundary_blocks_naebanggaek(self) -> None:
        matches = self.dict.find_all("내방객이 많습니다")
        self.assertEqual(
            [m for m in matches if m.term == "내방"], [],
            "'내방'이 '내방객'에 잘못 매칭되면 안 된다 (조사 경계 검사)",
        )

    def test_right_boundary_allows_naebang_eul(self) -> None:
        matches = self.dict.find_all("내방을 하실 때")
        naebang_matches = [m for m in matches if m.term == "내방"]
        self.assertEqual(len(naebang_matches), 1)
        m = naebang_matches[0]
        self.assertEqual(m.surface, "내방")
        self.assertEqual((m.start, m.end), (0, 2))

    def test_right_boundary_blocks_josa_like_char_followed_by_hangul(self) -> None:
        # '급여과장에게': '과'는 조사(과/와)와 형태가 같지만 뒤에 '장'(한글,
        # 조사 아님)이 이어지므로 '급여'가 매칭되면 안 된다.
        matches = [m for m in self.dict.find_all("급여과장에게 문의하세요.") if m.term == "급여"]
        self.assertEqual(matches, [], "'급여과장'의 '과'를 조사로 오인해 매칭되면 안 된다")

    def test_right_boundary_allows_chained_josa(self) -> None:
        # '급여에서는': '에서'+'는' 조사 연쇄가 허용되어야 한다.
        matches = [m for m in self.dict.find_all("급여에서는 신청이 필요합니다.") if m.term == "급여"]
        self.assertEqual(len(matches), 1, "연쇄 조사('에서는') 뒤의 표제어는 매칭되어야 한다")

    # ---- 최장일치 ----
    def test_longest_match_not_split(self) -> None:
        matches = self.dict.find_all("차상위계층 지원")
        terms_at_zero = [m.term for m in matches if m.start == 0]
        self.assertIn("차상위계층", terms_at_zero)
        self.assertNotIn("차상위", terms_at_zero, "최장일치여야 하므로 '차상위'로 쪼개지면 안 된다")


def _build_latin_boundary_fixture() -> dict:
    """로마자·숫자 경계 검사 전용 소형 index.json (2026-08-29 결함).

    실측: `CCTV를 설치합니다`에서 `CT`가, `TFT 구성`에서 `TF`가 원문을
    파괴하며 매칭됐다 — 기존 경계 검사(`_HANGUL_SYLLABLE_RE`, `josa_pattern()`)는
    "다음/이전이 한글 음절이 아니면 통과"라 로마자·숫자끼리 이어 붙는 경우를
    전혀 못 걸렀다. `TF`는 `TF팀`(한글 접미 결합, 정상)이 여전히 매칭돼야
    하므로 변형형으로 등록해 둔다. `개월`은 한글 표제어라 숫자 뒤에서도
    정상 매칭돼야 함을 확인하는 대조군이다(`3개월`의 `개월`).
    """
    return {
        "schema_version": "1.0.0",
        "josa": list(JOSA),
        "surface_index": {
            "CT": [1],
            "TF": [2],
            "TF팀": [2],
            "MOU": [3],
            "개월": [4],
        },
        "entries": {
            "1": {"t": "CT", "e": "전류 변성기", "d": None, "s": "substitute", "r": "none", "p": 120, "g": []},
            "2": {"t": "TF", "e": "특별 전담 조직", "d": None, "s": "gloss", "r": "low", "p": 120, "g": []},
            "3": {"t": "MOU", "e": "업무 협정", "d": None, "s": "substitute", "r": "none", "p": 130, "g": []},
            "4": {"t": "개월", "e": "달", "d": None, "s": "substitute", "r": "none", "p": 120, "g": []},
        },
    }


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class LatinBoundaryTestCase(unittest.TestCase):
    def setUp(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".latin_boundary.index.json", dir=TESTS_DIR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(_build_latin_boundary_fixture(), f, ensure_ascii=False)
        self.fixture_path = Path(path)
        self.dict = EasyDict.from_index_json(self.fixture_path)

    def tearDown(self) -> None:
        self.fixture_path.unlink(missing_ok=True)


class TestLatinDigitBoundaries(LatinBoundaryTestCase):
    """로마자·숫자 경계 검사 회귀 테스트 (2026-08-29, `CCTV`/`TFT` 실측 결함)."""

    def test_ct_does_not_match_inside_cctv(self) -> None:
        matches = [m for m in self.dict.find_all("CCTV를 설치합니다") if m.term == "CT"]
        self.assertEqual(matches, [], "'CCTV' 한가운데서 'CT'가 매칭되면 원문이 파괴된다")

    def test_annotate_leaves_cctv_untouched(self) -> None:
        text = "시설 안전을 위해 CCTV를 설치합니다"
        self.assertEqual(self.dict.annotate(text), text)

    def test_tf_does_not_match_inside_tft(self) -> None:
        # 왼쪽 경계는 문자열 시작이라 통과(old rule) — 이 케이스는 오른쪽
        # 경계(로마자 뒤에 로마자)만 단독으로 검증한다.
        matches = [m for m in self.dict.find_all("TFT 구성 후 운영합니다") if m.term == "TF"]
        self.assertEqual(matches, [], "'TFT'의 'TF'가 매칭되면 원문이 파괴된다")

    def test_annotate_leaves_tft_untouched(self) -> None:
        text = "TFT 구성 후 운영합니다"
        self.assertEqual(self.dict.annotate(text), text)

    def test_left_boundary_alone_blocks_latin_before_latin(self) -> None:
        # 'CT' 앞에 로마자('P')만 두고 뒤는 공백으로 둬서 왼쪽 경계 검사만
        # 단독으로 검증한다(오른쪽은 이미 통과할 상황).
        matches = [m for m in self.dict.find_all("PCT 협정 체결") if m.term == "CT"]
        self.assertEqual(matches, [], "로마자 뒤에서 시작하는 매칭은 왼쪽 경계에서 막혀야 한다")

    def test_mou_matches_before_hangul_josa(self) -> None:
        # 'MOU를' — 로마자 표제어 뒤에 한글 조사가 붙는 건 정상 결합이다.
        matches = [m for m in self.dict.find_all("MOU를 체결했습니다") if m.term == "MOU"]
        self.assertEqual(len(matches), 1, "'MOU를'의 'MOU'는 매칭되어야 한다(뒤가 한글 조사)")

    def test_tf_matches_as_whole_before_registered_hangul_suffix(self) -> None:
        # 'TF팀' 자체가 변형형으로 등록되어 있으므로 최장일치로 통째로 잡혀야
        # 한다 — 로마자 표제어 뒤에 한글이 결합하는 정상 형태.
        matches = self.dict.find_all("TF팀을 구성했습니다")
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0].surface, "TF팀")

    def test_digit_before_hangul_headword_is_not_blocked(self) -> None:
        # '3개월'의 '개월'은 한글 표제어라 숫자 경계 검사 대상이 아니다 —
        # 로마자/숫자 경계 규칙을 한글 표제어까지 확대하면 안 된다.
        matches = [m for m in self.dict.find_all("3개월 이내에 신청하세요") if m.term == "개월"]
        self.assertEqual(len(matches), 1, "숫자 뒤의 한글 표제어는 정상 매칭되어야 한다")
        self.assertEqual(matches[0].surface, "개월")


class TestAnnotate(LookupTestCase):
    def test_substitute_replaces_and_gloss_preserves_original(self) -> None:
        text = "내방을 하시고 과태료를 확인하세요."
        result = self.dict.annotate(text)
        self.assertIn("방문", result, "substitute 전략은 쉬운 말로 교체되어야 한다")
        self.assertNotIn("내방", result, "substitute 전략은 원어를 지워야 한다")
        self.assertIn("과태료(정해진 날짜보다 늦게 내서 더 내는 돈)", result, "gloss 전략은 원어(쉬운말) 형태여야 한다")

    def test_keep_leaves_text_unchanged(self) -> None:
        text = "기초연금법에 따라 지급됩니다."
        result = self.dict.annotate(text)
        self.assertIn("기초연금법", result)
        self.assertEqual(result, text, "keep 전략은 원문을 그대로 두어야 한다")

    def test_multiple_matches_no_index_drift(self) -> None:
        text = "내방을 하시고 과태료를 확인하세요."
        expected = "방문을 하시고 과태료(정해진 날짜보다 늦게 내서 더 내는 돈)를 확인하세요."
        result = self.dict.annotate(text)
        self.assertEqual(result, expected, "여러 매칭을 치환할 때 인덱스가 밀리면 안 된다(뒤에서부터 치환)")


class TestBuildPromptContext(LookupTestCase):
    def test_section_headers_present_for_matched_strategies(self) -> None:
        text = "내방을 하시고 과태료를 확인한 뒤 기초연금법을 참고하세요."
        ctx = self.dict.build_prompt_context(text)
        self.assertIn("### 바꿔 쓰세요", ctx)
        self.assertIn(
            "### 원래 말은 남기고, 바로 다음 문장에서 쉽게 풀어 설명하세요 (원래 말을 지우거나 괄호로 붙이지 마세요)",
            ctx,
        )
        self.assertIn("### 절대 바꾸지 마세요", ctx)

    def test_unmatched_dictionary_terms_are_excluded(self) -> None:
        # 이 문서에는 '차상위계층'이 등장하지 않으므로 프롬프트에도 들어가면 안 된다.
        text = "내방을 하시고 과태료를 확인하세요."
        ctx = self.dict.build_prompt_context(text)
        self.assertNotIn("차상위계층", ctx)
        self.assertNotIn("기초연금법", ctx)

    def test_max_terms_truncates(self) -> None:
        text = "내방을 하시고 차상위 지원과 과태료, 기초연금법을 모두 확인하세요."
        all_matches = self.dict.find_all(text)
        self.assertGreater(len(all_matches), 2, "이 테스트는 3개 이상의 매칭이 있어야 의미가 있다")

        ctx = self.dict.build_prompt_context(text, max_terms=2)
        bullet_lines = [line for line in ctx.splitlines() if line.strip().startswith("- ")]
        self.assertLessEqual(len(bullet_lines), 2, "max_terms를 넘는 항목 수가 그대로 나오면 안 된다")
        self.assertLess(
            len(bullet_lines), len(all_matches),
            "매칭된 용어 수보다 적게 나와야 잘림이 실제로 일어난 것이다",
        )


# ============================================================================
# E-6 회귀: "이유:" 줄 중복 출력 제거 (한국어기초사전 유래 엔트리, definition ≈ easy_term).
#
# 한국어기초사전 API로 가져온 엔트리(krdict_advanced.csv 114건)는 뜻풀이
# 하나를 easy_term과 definition 양쪽에 그대로 채워 넣는다. head 줄에 이미
# easy_term이 나와 있는데 "이유:"에 똑같은 문장을 또 보여주면 토큰 낭비다.
# 반대로 definition이 easy_term과 실질적으로 다르면(예: '수급자'처럼 뜻과
# 순화어가 다른 경우) "이유:" 줄이 유지되어야 한다 — 이쪽이 더 중요하다
# (생략 규칙이 과하게 적용되면 정말 필요한 근거까지 지워진다).
# ============================================================================
def _build_reason_dedup_fixture() -> dict:
    return {
        "schema_version": "1.0.0",
        "josa": list(JOSA),
        "surface_index": {
            "난임": [1],
            "수급자": [2],
        },
        "entries": {
            # definition이 easy_term과 공백/마침표 차이만 있음 -> "이유:" 생략되어야 함.
            "1": {
                "t": "난임", "e": "임신하기 어려움", "d": "임신하기 어려움.",
                "s": "gloss", "r": "low", "p": 120, "g": ["law"],
            },
            # definition이 easy_term과 실질적으로 다름 -> "이유:" 유지되어야 함.
            "2": {
                "t": "수급자", "e": "지원받는 사람", "d": "지금 실제로 도움을 받고 있는 사람입니다",
                "s": "gloss", "r": "low", "p": 130, "g": ["welfare"],
            },
        },
    }


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class ReasonDedupTestCase(unittest.TestCase):
    def setUp(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".reason.index.json", dir=TESTS_DIR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(_build_reason_dedup_fixture(), f, ensure_ascii=False)
        self.fixture_path = Path(path)
        self.dict = EasyDict.from_index_json(self.fixture_path)

    def tearDown(self) -> None:
        self.fixture_path.unlink(missing_ok=True)


class TestReasonLineDedup(ReasonDedupTestCase):
    def test_reason_line_omitted_when_definition_matches_easy_term(self) -> None:
        text = "난임 진단을 받았습니다."
        ctx = self.dict.build_prompt_context(text)
        self.assertIn("난임 — 뜻: 임신하기 어려움", ctx, "head 줄은 그대로 나와야 한다")
        self.assertNotIn(
            "이유: 임신하기 어려움", ctx,
            "definition이 easy_term과 실질적으로 같으면 '이유:' 줄을 생략해야 한다",
        )

    def test_reason_line_kept_when_definition_differs_from_easy_term(self) -> None:
        # 더 중요한 방향: 뜻과 순화어가 다른 경우 '이유:'가 사라지면 안 된다.
        text = "수급자 등록을 도와드립니다."
        ctx = self.dict.build_prompt_context(text)
        self.assertIn("수급자 — 뜻: 지원받는 사람", ctx, "head 줄은 그대로 나와야 한다")
        self.assertIn(
            "이유: 지금 실제로 도움을 받고 있는 사람입니다", ctx,
            "definition이 easy_term과 다르면 '이유:' 줄이 유지되어야 한다",
        )


# ============================================================================
# E-2 회귀 테스트 1: 굴절형 치환 (§6.5, §2.1)
#
# 실제 발견된 결함: '명기하여'가 활용형으로 매칭됐는데 원형('명기하다')의
# easy_term('쓰다')으로 그대로 치환해 '주소를 쓰다 주십시오' 같은 비문이
# 생겼다. lookup 담당이 수정한 현재 계약: 활용형 매칭(surface != term)은
# 원어를 지우지 않고 'surface(easy_term)' 형태로 안전하게 폴백한다.
# ============================================================================
class TestInflectedSubstitution(LookupTestCase):
    def test_inflected_match_is_flagged(self) -> None:
        matches = self.dict.find_all("신청서에 이름과 주소를 명기하여 주십시오.")
        target = next((m for m in matches if m.term == "명기하다"), None)
        self.assertIsNotNone(target, "'명기하여'가 '명기하다' 표제어에 매칭되어야 한다")
        self.assertEqual(target.surface, "명기하여")
        self.assertTrue(target.is_inflected, "굴절형 매칭은 Match.is_inflected=True 여야 한다")

    def test_original_form_match_is_not_flagged(self) -> None:
        matches = self.dict.find_all("내방을 하실 때")
        target = next(m for m in matches if m.term == "내방")
        self.assertFalse(target.is_inflected, "원형 그대로 매칭됐을 때는 is_inflected=False 여야 한다")

    def test_annotate_preserves_inflected_ending_no_mangled_grammar(self) -> None:
        text = "신청서에 이름과 주소를 명기하여 주십시오."
        result = self.dict.annotate(text)
        expected = "신청서에 이름과 주소를 명기하여(쓰다) 주십시오."
        self.assertEqual(result, expected, "굴절형은 원문 어미를 보존한 채 괄호로 쉬운 말을 덧붙여야 한다")
        self.assertNotIn("쓰다 주십시오", result, "활용 어미가 잘려나가 비문이 되면 안 된다 (회귀 버그)")
        self.assertIn("명기하여", result, "원문의 활용형 표면이 사라지면 안 된다")

    def test_original_form_still_substitutes_normally(self) -> None:
        # 회귀 방지: 굴절형 처리를 고치면서 원형 매칭 케이스가 깨지면 안 된다.
        result = self.dict.annotate("내방을 하실 때 절차를 안내합니다.")
        self.assertIn("방문을 하실 때", result)
        self.assertNotIn("내방", result)

    def test_build_prompt_context_shows_canonical_form_not_inflected_surface(self) -> None:
        # §6.6: build_prompt_context는 annotate의 굴절형 폴백과 무관하다.
        # LLM이 활용을 처리하므로 표제어 원형으로 표기한다('명기하여'가 아니라 '명기하다 → 쓰다').
        text = "신청서에 이름과 주소를 명기하여 주십시오."
        ctx = self.dict.build_prompt_context(text)
        self.assertIn("명기하다 → 쓰다", ctx)
        self.assertNotIn("명기하여 → 쓰다", ctx)


# ============================================================================
# E-2 회귀 테스트 2: keep 경로 end-to-end (§2.1, §7.3)
#
# 법령명·금액 같은 keep 대상이 실제로 한 글자도 안 바뀌는지, 그리고 이전엔
# keep 매칭이 0건이라 한 번도 실행된 적 없던 "### 절대 바꾸지 마세요" 섹션이
# 실제로 채워지는지 확인한다. easy-doc의 required_facts 사실보존 검증과
# 직결되는 안전장치다 (§7.3).
# ============================================================================
class TestKeepEndToEnd(LookupTestCase):
    def test_bracket_law_name_and_amount_untouched(self) -> None:
        text = "「국민기초생활 보장법」에 따라 월 30만 원을 지급합니다."
        result = self.dict.annotate(text)
        self.assertEqual(result, text, "keep 대상(법령명·금액)은 한 글자도 바뀌면 안 된다")

    def test_keep_section_actually_populated(self) -> None:
        text = "「국민기초생활 보장법」에 따라 월 30만 원을 지급합니다."
        ctx = self.dict.build_prompt_context(text)
        section = ctx.split("### 절대 바꾸지 마세요", 1)[1]
        self.assertIn("「국민기초생활 보장법」", section)
        self.assertIn("월 30만 원", section)


# ============================================================================
# E-2 회귀 테스트 3: 명사 표제어의 하다-파생 활용형 매칭 (§3.4, §6.5)
#
# core-lib(normalize.gen_variants)이 아직 명사+하다 파생을 생성하지 않으면
# 이 테스트는 실패한다 — 그것이 정상이다. 팀장 지시: 먼저 써 두고, 구현이
# 끝날 때까지 폴링하며 다시 돌린다.
#
# normalize.gen_variants가 실제로 만든 변형형만으로 index.json을 구성해
# EasyDict.find_all까지 이어지는지(진짜 end-to-end)를 검증한다.
# ============================================================================
@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class TestInflectedNounMatching(unittest.TestCase):
    def _build_dict_for_noun_entry(self, term: str, easy_term: str, entry_id: int) -> "EasyDict":
        surface_index: dict[str, list[int]] = {term: [entry_id]}
        for v in gen_variants(term, "noun"):
            surface_index.setdefault(v.surface, [])
            if entry_id not in surface_index[v.surface]:
                surface_index[v.surface].append(entry_id)
        doc = {
            "schema_version": "1.0.0",
            "josa": list(JOSA),
            "surface_index": surface_index,
            "entries": {
                str(entry_id): {
                    "t": term, "e": easy_term, "d": None,
                    "s": "substitute", "r": "none", "p": 100 + len(term) * 10, "g": [],
                },
            },
        }
        fd, path = tempfile.mkstemp(suffix=".index.json", dir=TESTS_DIR)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(doc, f, ensure_ascii=False)
            return EasyDict.from_index_json(path)
        finally:
            os.unlink(path)

    def test_jicham_hasigi_matches(self) -> None:
        d = self._build_dict_for_noun_entry("지참", "가져오기", 101)
        matches = d.find_all("증빙서류를 지참하시기 바랍니다.")
        self.assertTrue(
            any(m.term == "지참" for m in matches),
            "'지참하시기'가 명사 '지참' 표제어에 매칭되어야 한다 (gen_variants의 명사+하다 파생 필요)",
        )

    def test_naebang_hasimyeon_matches(self) -> None:
        d = self._build_dict_for_noun_entry("내방", "방문", 102)
        matches = d.find_all("고객님이 내방하시면 안내해 드립니다.")
        self.assertTrue(
            any(m.term == "내방" for m in matches),
            "'내방하시면'이 명사 '내방' 표제어에 매칭되어야 한다 (gen_variants의 명사+하다 파생 필요)",
        )


# ============================================================================
# E-3 회귀 테스트: 조사 이형태(받침 유무) 교정
#
# 실제 발견된 결함: '급여는 월 30만 원입니다.' 에서 '급여'(받침 없음)를
# '지원금'(받침 ㅁ)으로 치환하면서 뒤따르는 조사 '는'을 그대로 두어
# '지원금는 월 30만 원입니다.' 라는 비문이 생겼다. 올바른 결과는
# '지원금은 월 30만 원입니다.'(조사도 받침에 맞춰 함께 바뀜)이다.
#
# lookup 담당이 annotate()에 조사 교정을 구현 중이다. 이 시점(E-3 작성 시점)
# 에는 아직 구현되지 않았을 수 있고, 그 경우 아래 테스트는 실패해야 정상이다
# — 결함이 실재한다는 증거로 실패 출력을 그대로 보고한다.
# ============================================================================
def _build_josa_fixture() -> dict:
    """조사 이형태 교정 전용 소형 index.json.

    엔트리 설계:
      1. 급여(받침 없음)  -> 지원금(받침 ㅁ)   : 받침없음->받침있음, 5개 조사쌍
      2. 여권(받침 ㄴ)    -> 카드(받침 없음)   : 받침있음->받침없음 (역방향), 4개 조사쌍
      3. 이동수단(받침 ㄴ) -> 지하철(받침 ㄹ)   : 으로/로의 ㄹ받침 예외
      4. 수급권자(gloss)  -> 지원받을 자격이 있는 사람 : gloss는 조사 교정 대상 아님
      5. 통보하다/통보하여(굴절형) -> 알리다     : 굴절형 폴백도 조사 교정 대상 아님
      6. 문서(받침 없음)  -> PDF(비한글 끝)     : 한글로 안 끝나면 교정 시도 안 함
    """
    return {
        "schema_version": "1.0.0",
        "josa": list(JOSA),
        "surface_index": {
            "급여": [1],
            "여권": [2],
            "이동수단": [3],
            "수급권자": [4],
            "통보하다": [5],
            "통보하여": [5],
            "문서": [6],
        },
        "entries": {
            "1": {"t": "급여", "e": "지원금", "d": None, "s": "substitute", "r": "none", "p": 120, "g": []},
            "2": {"t": "여권", "e": "카드", "d": None, "s": "substitute", "r": "none", "p": 120, "g": []},
            "3": {"t": "이동수단", "e": "지하철", "d": None, "s": "substitute", "r": "none", "p": 140, "g": []},
            "4": {
                "t": "수급권자", "e": "지원받을 자격이 있는 사람", "d": None,
                "s": "gloss", "r": "high", "p": 140, "g": ["welfare"],
            },
            "5": {"t": "통보하다", "e": "알리다", "d": None, "s": "substitute", "r": "none", "p": 140, "g": []},
            "6": {"t": "문서", "e": "PDF", "d": None, "s": "substitute", "r": "none", "p": 120, "g": []},
        },
    }


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class JosaCorrectionTestCase(unittest.TestCase):
    def setUp(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".josa.index.json", dir=TESTS_DIR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(_build_josa_fixture(), f, ensure_ascii=False)
        self.fixture_path = Path(path)
        self.dict = EasyDict.from_index_json(self.fixture_path)

    def tearDown(self) -> None:
        self.fixture_path.unlink(missing_ok=True)


class TestJosaCorrectionNoBatchimToBatchim(JosaCorrectionTestCase):
    """항목 1: 받침 없는 원어 -> 받침 있는 easy_term. 5개 조사쌍 전부 확인."""

    def test_neun_to_eun(self) -> None:
        self.assertEqual(self.dict.annotate("급여는 얼마인가요?"), "지원금은 얼마인가요?")

    def test_ga_to_i(self) -> None:
        self.assertEqual(self.dict.annotate("급여가 늘었습니다."), "지원금이 늘었습니다.")

    def test_reul_to_eul(self) -> None:
        self.assertEqual(self.dict.annotate("급여를 신청하세요."), "지원금을 신청하세요.")

    def test_ro_to_euro(self) -> None:
        self.assertEqual(self.dict.annotate("급여로 생활합니다."), "지원금으로 생활합니다.")

    def test_wa_to_gwa(self) -> None:
        self.assertEqual(self.dict.annotate("급여와 수당을 받습니다."), "지원금과 수당을 받습니다.")


class TestJosaCorrectionBatchimToNoBatchim(JosaCorrectionTestCase):
    """항목 2: 받침 있는 원어 -> 받침 없는 easy_term (역방향). 4개 조사쌍."""

    def test_eun_to_neun(self) -> None:
        self.assertEqual(self.dict.annotate("여권은 필수입니다."), "카드는 필수입니다.")

    def test_i_to_ga(self) -> None:
        self.assertEqual(self.dict.annotate("여권이 있어야 합니다."), "카드가 있어야 합니다.")

    def test_eul_to_reul(self) -> None:
        self.assertEqual(self.dict.annotate("여권을 지참하세요."), "카드를 지참하세요.")

    def test_euro_to_ro(self) -> None:
        self.assertEqual(self.dict.annotate("여권으로 확인합니다."), "카드로 확인합니다.")


class TestJosaCorrectionRieulException(JosaCorrectionTestCase):
    """항목 3: 으로/로의 ㄹ받침 예외. '서울로'이지 '서울으로'가 아니다."""

    def test_euro_becomes_ro_for_rieul_batchim_easy_term(self) -> None:
        # '이동수단'(받침 ㄴ, 원문은 '으로') -> '지하철'(받침 ㄹ): 결과는 '지하철으로'가
        # 아니라 '지하철로' 여야 한다 (ㄹ받침은 '으로'가 아니라 '로'를 쓴다).
        self.assertEqual(self.dict.annotate("이동수단으로 오세요."), "지하철로 오세요.")


class TestJosaCorrectionNotApplied(JosaCorrectionTestCase):
    """항목 4: 조사 교정이 적용되면 안 되는 케이스들 (오작동 방지)."""

    def test_gloss_preserves_original_word_and_josa(self) -> None:
        # gloss는 원어를 보존하므로 조사가 원래 단어('수급권자')에 그대로 붙어 있어야 한다.
        text = "수급권자로 등록되었습니다."
        expected = "수급권자(지원받을 자격이 있는 사람)로 등록되었습니다."
        self.assertEqual(self.dict.annotate(text), expected)

    def test_inflected_fallback_leaves_trailing_text_untouched(self) -> None:
        # 굴절형 폴백(§6.6)도 원문을 보존하므로 매칭 뒤에 오는 조사를 손대면 안 된다.
        text = "통보하여로 처리됩니다."
        result = self.dict.annotate(text)
        self.assertTrue(result.endswith("로 처리됩니다."), "굴절형 폴백 뒤 텍스트가 원문과 달라지면 안 된다")
        self.assertIn("통보하여(알리다)", result)

    def test_non_hangul_easy_term_ending_is_not_corrected(self) -> None:
        # easy_term이 한글로 끝나지 않으면(영문/숫자/기호) 받침 판정 자체가 불가능하므로 손대지 않는다.
        text = "문서를 확인하세요."
        self.assertEqual(self.dict.annotate(text), "PDF를 확인하세요.")

    def test_josa_lookalike_word_is_not_corrupted(self) -> None:
        # '여권'(받침 ㄴ) -> '카드'(받침 없음)로 바뀌면 뒤 조사가 '이'->'가'로 바뀌어야
        # 하는 상황이지만, 뒤따르는 글자는 실제로는 별도 단어 '이야기'의 일부다(띄어쓰기
        # 없이 붙어 있어 '이'가 조사처럼 보인다). 조사 교정이 이런 경우까지 기계적으로
        # 글자를 바꾸면 '이야기'가 '가야기'로 훼손된다.
        text = "여권이야기를 들었습니다."
        result = self.dict.annotate(text)
        self.assertIn("이야기", result, "조사로 오인해 뒤 단어 '이야기'를 훼손하면 안 된다")


# ============================================================================
# easy-doc A/B 실측 보완 ① : gloss_style 재설계 (DESIGN.md §7.2, §7.2.2)
#
# 실측: easy-doc 변환 프롬프트에 build_prompt_context()를 주입해 A/B
# 측정한 결과, gloss의 `원문(설명)` 괄호 병기 head가 easy-doc의 "괄호는
# 풀어 쓰라"는 스타일 규칙과 충돌해 스타일 통과율이 83.9% -> 51.8%로
# 무너지고 보정 패스 56/56이 발동했다. gloss_style="sentence"(신규 기본값)가
# 이 충돌을 없앤다. gloss 예문(원어(easy_term) 괄호 병기 형식으로 합성됨)도
# sentence 지시와 모순되므로 sentence 스타일에서는 예문 풀에서 뺀다.
# ============================================================================
def _build_gloss_style_fixture() -> dict:
    return {
        "schema_version": "1.0.0",
        "josa": list(JOSA),
        "surface_index": {
            "내방": [1],
            "과태료": [2],
        },
        "entries": {
            "1": {
                "t": "내방", "e": "방문", "d": None, "s": "substitute", "r": "none", "p": 110, "g": [],
                "x": [{"b": "내방하세요.", "a": "방문하세요.", "y": True}],
            },
            "2": {
                "t": "과태료", "e": "정해진 날짜보다 늦어서 더 내는 돈",
                "d": "정해진 날짜를 넘겨서 더 내게 되는 돈입니다.",
                "s": "gloss", "r": "high", "p": 130, "g": ["law"],
                "c": "벌금과는 법적으로 다른 개념입니다.",
                "x": [{"b": "과태료를 냅니다.", "a": "과태료(정해진 날짜보다 늦어서 더 내는 돈)를 냅니다.", "y": True}],
            },
        },
    }


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class GlossStyleTestCase(unittest.TestCase):
    def setUp(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".gloss_style.index.json", dir=TESTS_DIR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(_build_gloss_style_fixture(), f, ensure_ascii=False)
        self.fixture_path = Path(path)
        self.dict = EasyDict.from_index_json(self.fixture_path)
        self.text = "내방을 하시고 과태료를 확인하세요."

    def tearDown(self) -> None:
        self.fixture_path.unlink(missing_ok=True)


class TestGlossStyle(GlossStyleTestCase):
    def test_sentence_is_the_default_style(self) -> None:
        ctx = self.dict.build_prompt_context(self.text)
        self.assertIn(
            "### 원래 말은 남기고, 바로 다음 문장에서 쉽게 풀어 설명하세요 (원래 말을 지우거나 괄호로 붙이지 마세요)",
            ctx,
        )
        self.assertIn("과태료 — 뜻: 정해진 날짜보다 늦어서 더 내는 돈", ctx)
        self.assertNotIn("과태료(정해진 날짜보다 늦어서 더 내는 돈)", ctx, "sentence 스타일은 괄호 템플릿을 보여주면 안 된다")

    def test_paren_style_preserves_legacy_format(self) -> None:
        ctx = self.dict.build_prompt_context(self.text, gloss_style="paren")
        self.assertIn("### 원래 말을 남기고 괄호로 설명하세요 (지우면 안 됩니다)", ctx)
        self.assertIn("과태료 → 과태료(정해진 날짜보다 늦어서 더 내는 돈)", ctx)

    def test_invalid_gloss_style_raises_value_error(self) -> None:
        with self.assertRaises(ValueError):
            self.dict.build_prompt_context(self.text, gloss_style="bogus")

    def test_reason_and_caution_lines_unaffected_by_gloss_style(self) -> None:
        # tier 처리("이유:"/"주의:" 줄)는 gloss_style과 무관하게 그대로여야 한다.
        for style in ("sentence", "paren"):
            with self.subTest(style=style):
                ctx = self.dict.build_prompt_context(self.text, gloss_style=style)
                self.assertIn("이유: 정해진 날짜를 넘겨서 더 내게 되는 돈입니다.", ctx)
                self.assertIn("주의: 벌금과는 법적으로 다른 개념입니다.", ctx)

    def test_sentence_style_excludes_gloss_examples_but_keeps_substitute_examples(self) -> None:
        ctx = self.dict.build_prompt_context(self.text)
        self.assertIn("전: 내방하세요.", ctx, "substitute 엔트리의 예문은 sentence 스타일에서도 유지되어야 한다")
        self.assertNotIn(
            "전: 과태료를 냅니다.", ctx,
            "gloss 엔트리의 예문(괄호 병기 형식)은 sentence 지시와 모순되므로 제외해야 한다",
        )

    def test_paren_style_keeps_gloss_examples(self) -> None:
        ctx = self.dict.build_prompt_context(self.text, gloss_style="paren")
        self.assertIn("전: 내방하세요.", ctx)
        self.assertIn("전: 과태료를 냅니다.", ctx, "paren 스타일은 예문 형식과 모순되지 않으므로 그대로 유지되어야 한다")


# ============================================================================
# easy-doc A/B 실측 보완 ② : 단일 글자 한글 표제어 경계 규칙 (DESIGN.md §6.7 (5))
#
# 실측: 문서 051에서 `200자 이내`의 `자`가 표제어 `자`(사람)에 오매칭됐고,
# 줄머리의 `자. 「학교 밖 청소년…`도 가나다 목록 기호가 표제어로 오인됐다.
# 반면 `부정한 방법으로 교부받은 자는`(법률문투 `자`)은 정당한 매칭이라
# 계속 허용되어야 한다. `BoundaryTestCase` 픽스처(§_build_boundary_fixture)의
# 표제어 `자`(entry 5, substitute, risk=none)를 그대로 재사용한다.
# ============================================================================
class TestSingleCharHeadwordBoundary(BoundaryTestCase):
    def test_rejects_quantity_unit_after_digit(self) -> None:
        matches = [m for m in self.dict.find_all("200자 이내로 작성하세요.") if m.term == "자"]
        self.assertEqual(matches, [], "'200자'의 '자'는 글자 수 단위이지 표제어 '자'(사람)가 아니다")

    def test_allows_headword_after_non_digit_start(self) -> None:
        # 대조군: 숫자가 아닌 문자 뒤라면 길이 1 표제어도 정상 매칭되어야 한다.
        matches = [m for m in self.dict.find_all("이 자는 학생입니다.") if m.term == "자"]
        self.assertEqual(len(matches), 1)

    def test_rejects_list_marker_at_document_start(self) -> None:
        matches = [m for m in self.dict.find_all("자. 「학교 밖 청소년」 지원 안내") if m.term == "자"]
        self.assertEqual(matches, [], "문서 시작의 '자.'는 가나다 목록 기호이지 표제어가 아니다")

    def test_rejects_list_marker_after_newline_with_leading_whitespace(self) -> None:
        text = "안내문\n  자) 학교 밖 청소년 지원"
        matches = [m for m in self.dict.find_all(text) if m.term == "자"]
        self.assertEqual(matches, [], "개행 직후(공백 허용) 단독으로 나오고 뒤가 ')'인 '자'는 목록 기호다")

    def test_allows_legitimate_legal_phrase_match(self) -> None:
        text = "부정한 방법으로 교부받은 자는 처벌됩니다."
        matches = [m for m in self.dict.find_all(text) if m.term == "자"]
        self.assertEqual(len(matches), 1, "법률문투 '교부받은 자는'의 '자'는 정당한 매칭이므로 허용되어야 한다")

    def test_two_char_headword_after_digit_still_matches(self) -> None:
        # 회귀 방지: 길이 1 전용 규칙이 길이 2 이상 한글 표제어까지 막으면 안 된다.
        matches = [m for m in self.dict.find_all("차상위 지원 대상은 200차상위 아닙니다.") if m.term == "차상위"]
        self.assertGreaterEqual(len(matches), 1)


# ============================================================================
# easy-doc A/B 실측 보완 ③·④ : max_chars_ratio / min_substitute 예산 규칙
#
# 실측: 컨텍스트가 원문보다 긴 문서 38/56(최대 4.61배) — 짧은 문서에는
# 원문 길이에 비례한 상한이 필요하다(③). 그리고 max_terms/max_chars 잘림이
# risk -> priority 순이라 risk='none'인 substitute가 항상 먼저 통째로
# 잘려(문서 051에서 매칭된 substitute 4건 전부) "바꿔 쓰세요" 구역이 빈다
# — min_substitute 예약석으로 상쇄한다(④).
# ============================================================================
def _build_budget_fixture() -> dict:
    """substitute 6건(전부 risk=none, priority만 다름) + risk=high gloss 1건.

    ④의 실측 결함(risk=none substitute가 항상 먼저 잘림)을 재현하는 최소
    픽스처다. priority는 100+i라 '어휘6'이 substitute 중 가장 높다.
    """
    surface_index = {f"어휘{i}": [i] for i in range(1, 7)}
    surface_index["위험어"] = [99]
    entries: dict[str, dict] = {
        str(i): {
            "t": f"어휘{i}", "e": f"쉬운말{i}", "d": None,
            "s": "substitute", "r": "none", "p": 100 + i, "g": [],
        }
        for i in range(1, 7)
    }
    entries["99"] = {
        "t": "위험어", "e": "위험쉬운말", "d": "위험한 말입니다.",
        "s": "gloss", "r": "high", "p": 200, "g": [],
    }
    return {
        "schema_version": "1.0.0",
        "josa": list(JOSA),
        "surface_index": surface_index,
        "entries": entries,
    }


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_IMPORT_ERROR}")
class BudgetTestCase(unittest.TestCase):
    def setUp(self) -> None:
        fd, path = tempfile.mkstemp(suffix=".budget.index.json", dir=TESTS_DIR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(_build_budget_fixture(), f, ensure_ascii=False)
        self.fixture_path = Path(path)
        self.dict = EasyDict.from_index_json(self.fixture_path)

    def tearDown(self) -> None:
        self.fixture_path.unlink(missing_ok=True)


class TestMaxCharsRatio(BudgetTestCase):
    def test_ratio_shrinks_output_for_a_short_document(self) -> None:
        short_text = "어휘1"
        unbounded = self.dict.build_prompt_context(short_text)
        ratio_bounded = self.dict.build_prompt_context(short_text, max_chars_ratio=1.0)
        self.assertIn("어휘1 → 쉬운말1", unbounded)
        # 원문(2자)의 1배는 헤더+섹션 제목만으로도 넘는 물리적 하한(§7.2.1)보다
        # 작으므로, 항목이 통째로 빠지고 잘림 안내만 남아야 한다(길이 비교가
        # 아니라 "항목이 실제로 빠졌는가"로 검증한다 — 잘림 안내 자체가 원문
        # 렌더보다 길 수 있어 단순 길이 비교는 신뢰할 수 없다).
        self.assertNotIn(
            "어휘1 → 쉬운말1", ratio_bounded,
            "원문 길이 비례 상한이 물리적 하한보다 작으면 항목이 통째로 빠져야 한다",
        )

    def test_smaller_of_max_chars_and_ratio_wins(self) -> None:
        text = "어휘1 어휘2 어휘3 어휘4 어휘5 어휘6"
        via_tight_max_chars = self.dict.build_prompt_context(text, max_chars=50, max_chars_ratio=100.0)
        via_only_max_chars = self.dict.build_prompt_context(text, max_chars=50)
        self.assertEqual(via_tight_max_chars, via_only_max_chars, "둘 중 더 작은 상한(max_chars)이 이겨야 한다")

    def test_default_none_ratio_does_not_change_existing_max_chars_behavior(self) -> None:
        text = "어휘1 어휘2 어휘3"
        self.assertEqual(
            self.dict.build_prompt_context(text, max_chars=500),
            self.dict.build_prompt_context(text, max_chars=500, max_chars_ratio=None),
        )


class TestMinSubstituteReservation(BudgetTestCase):
    def test_reserved_substitutes_survive_max_terms_truncation(self) -> None:
        text = "어휘1 어휘2 어휘3 어휘4 어휘5 어휘6 위험어"
        ctx = self.dict.build_prompt_context(text, max_terms=3, min_substitute=5)
        # 예약석 5건(어휘2~6) 중에서도 max_terms=3 한도 자체는 지켜야 하므로
        # priority가 가장 높은 3건(어휘4~6)만 남고, risk='high'인 위험어가
        # 예약석을 밀어내면 안 된다.
        self.assertIn("어휘6 → 쉬운말6", ctx)
        self.assertIn("어휘5 → 쉬운말5", ctx)
        self.assertIn("어휘4 → 쉬운말4", ctx)
        self.assertNotIn("위험어", ctx, "예약석이 다 차 있으면 risk='high' 항목도 그 슬롯을 뺏을 수 없다")

    def test_without_reservation_high_risk_displaces_substitute(self) -> None:
        # 회귀 대조: min_substitute=0(예약 없음)이면 기존처럼 risk='high'인
        # 위험어가 risk='none' substitute를 밀어낸다 — 실측에서 실제로 난 사고다.
        text = "어휘1 어휘2 어휘3 어휘4 어휘5 어휘6 위험어"
        ctx = self.dict.build_prompt_context(text, max_terms=3, min_substitute=0)
        self.assertIn("위험어", ctx)

    def test_reserved_substitutes_survive_max_chars_item_removal(self) -> None:
        text = "어휘1 어휘2 어휘3 어휘4 어휘5 어휘6"
        full = self.dict.build_prompt_context(text, min_substitute=2)
        # 전부 담을 예산은 안 되지만 예약석(어휘5·6) 중 priority가 가장 높은
        # 하나는 마지막까지 남을 정도로만 살짝 빠듯하게 준다.
        ctx = self.dict.build_prompt_context(text, max_chars=len(full) - 1, min_substitute=2)
        self.assertIn("어휘6 → 쉬운말6", ctx, "priority가 가장 높은 예약석 substitute는 항목 제거의 맨 마지막까지 남아야 한다")

    def test_min_substitute_zero_disables_reservation(self) -> None:
        text = "어휘1 어휘2 어휘3 어휘4 어휘5 어휘6 위험어"
        ctx_reserved = self.dict.build_prompt_context(text, max_terms=3, min_substitute=5)
        ctx_unreserved = self.dict.build_prompt_context(text, max_terms=3, min_substitute=0)
        self.assertNotEqual(ctx_reserved, ctx_unreserved)


if __name__ == "__main__":
    unittest.main()
