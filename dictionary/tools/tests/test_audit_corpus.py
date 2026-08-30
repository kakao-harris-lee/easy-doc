"""`tools/audit_corpus.py`(층위 2 — 코퍼스 통과 검사)의 순수 로직 테스트.

**네트워크 호출 없음, 골든 코퍼스도 안 읽는다.** `docs/inspection-plan.md`/
`tools/audit_corpus.py`가 기록한 실패담 두 가지를 그대로 회귀로 고정한다.

1. `check_boundaries`가 처음엔 매칭 양옆 문자 종류만 봐서 `소득인정액이`의
   `이`(조사)까지 위반으로 잡아 **424건 오탐**을 냈다.
2. 그래서 `lookup._boundary_ok`를 그대로 재호출했더니 **검사가 절대 실패할
   수 없게** 됐다 — `EasyDict.find_all()`은 이미 그 함수를 통과시킨 매칭만
   돌려주므로, 같은 함수로 다시 거르면 항상 통과다.

그래서 이 테스트는 (a) `check_boundaries`가 인위 사례에서 **실제로 값을
잡는지**와 (b) 그 함수가 `lookup`의 경계 함수를 다시 호출하지 **않는지**를
직접 확인한다 — 두 실패 모두 재발하면 여기서 걸린다.

`Match`는 손으로 만든다(`EasyDict.find_all()`을 거치지 않는다) — 그래야
"검사 대상 함수가 이미 필터링된 입력만 받아 공허해지는" 사고를 재현하지
않고, 함수 자체의 판정 로직만 독립적으로 검증할 수 있다.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

_TOOLS_DIR = Path(__file__).resolve().parent.parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import audit_corpus as ac  # noqa: E402
from easydict.lookup import Match  # noqa: E402


def _match(**kwargs) -> Match:
    """테스트용 `Match` 기본값 채우기. 지정 안 한 필드는 검사와 무관한 값."""
    defaults = dict(
        start=0, end=0, surface="", entry_id=1, term="", easy_term="",
        strategy="substitute", risk="none", definition=None, priority=100,
        is_inflected=False, caution=None,
    )
    defaults.update(kwargs)
    return Match(**defaults)


class FakeEd:
    """`check_josa_agreement`/`check_inflected_substitution`이 쓰는
    `ed.annotate(text)`만 흉내 낸다 — `annotate()`가 실제로 만들어낼 수
    있는 (버그가 있는/없는) 출력을 그대로 손으로 지정해 각 검사가 그
    출력을 옳게 판정하는지만 본다."""

    def __init__(self, annotated: str) -> None:
        self._annotated = annotated

    def annotate(self, text: str) -> str:  # noqa: ARG002 - 인터페이스 맞추기용
        return self._annotated


class TestCheckBoundariesCatchesRealFailures(unittest.TestCase):
    """양성: 실제로 잡아야 하는 경계 위반."""

    def test_hangul_particle_swallowed_as_headword(self) -> None:
        # '대상자입니다'에서 '자'가 (엉뚱하게) 표제어로 매칭됐다고 가정.
        # 왼쪽 '상'이 한글이므로 '더 큰 한글 토큰의 일부'로 잘려 들어간 것.
        text = "대상자입니다"
        m = _match(start=2, end=3, surface="자", term="자", easy_term="사람")
        out = ac.check_boundaries({"id": "s", "text": text}, None, [m])
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["why"], "왼쪽에 한글이 붙음")

    def test_latin_substring_swallowed_inside_larger_token(self) -> None:
        # 'CCTV를 설치합니다'에서 'CT'가 'CCTV' 안쪽에 매칭된 실제 사고 재현.
        text = "CCTV를 설치합니다"
        m = _match(start=1, end=3, surface="CT", term="CT", easy_term="폐쇄회로텔레비전")
        out = ac.check_boundaries({"id": "s", "text": text}, None, [m])
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["why"], "왼쪽에 로마자·숫자가 붙음")


class TestCheckBoundariesDoesNotFalsePositive(unittest.TestCase):
    """음성: 1차 구현이 424건 오탐을 냈던 종류를 잡지 않는지 확인."""

    def test_latin_headword_followed_by_hangul_particle_is_fine(self) -> None:
        # 'MOU를 체결했다' — 로마자 표제어 뒤에 한글 조사가 붙는 정상 매칭.
        text = "MOU를 체결했다"
        m = _match(start=0, end=3, surface="MOU", term="MOU", easy_term="양해각서")
        out = ac.check_boundaries({"id": "s", "text": text}, None, [m])
        self.assertEqual(out, [])

    def test_hangul_headword_followed_by_josa_is_fine(self) -> None:
        # '소득인정액이 있다' — 실제로 424건 오탐을 냈던 그 사례.
        text = "소득인정액이 있다"
        m = _match(start=0, end=5, surface="소득인정액", term="소득인정액", easy_term="소득으로 보는 금액")
        out = ac.check_boundaries({"id": "s", "text": text}, None, [m])
        self.assertEqual(out, [])


class TestCheckBoundariesDoesNotDelegateToLookup(unittest.TestCase):
    """`check_boundaries`가 `lookup._boundary_ok`/`_left_boundary_ok`를 다시
    호출하면 안 된다 — 2차 실패(검사가 공허해짐)의 재발을 소스 검사로 막는다.
    """

    def test_source_has_no_boundary_ok_call(self) -> None:
        # docstring 산문에는 반성 삼아 '_boundary_ok'를 언급하므로(위 참고),
        # 텍스트 소스가 아니라 **컴파일된 코드가 실제로 참조하는 이름**만
        # 본다 — docstring은 바이트코드에 안 남는다.
        names = ac.check_boundaries.__code__.co_names
        self.assertNotIn("_boundary_ok", names)
        self.assertNotIn("_left_boundary_ok", names)


class TestCheckJosaAgreement(unittest.TestCase):
    def test_catches_uncorrected_batchim_josa(self) -> None:
        # '급여' -> '지원금'(받침 있음)인데 조사 '는'이 안 고쳐졌다고 가정.
        text = "급여는 많다"
        m = _match(start=0, end=2, surface="급여", term="급여", easy_term="지원금")
        # annotate()가 조사 교정을 빼먹은 것처럼(버그 재현) 원문 그대로 흉내낸다.
        ed = FakeEd(annotated="급여는 많다")
        out = ac.check_josa_agreement({"id": "s", "text": text}, ed, [m])
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["expected"], "지원금은")

    def test_passes_when_batchim_josa_correctly_applied(self) -> None:
        text = "급여는 많다"
        m = _match(start=0, end=2, surface="급여", term="급여", easy_term="지원금")
        ed = FakeEd(annotated="지원금은 많다")
        out = ac.check_josa_agreement({"id": "s", "text": text}, ed, [m])
        self.assertEqual(out, [])


class TestCheckInflectedSubstitution(unittest.TestCase):
    def test_catches_inflected_match_with_root_form_spliced_in(self) -> None:
        # '명기하여' -> '쓰다'가 활용 어미를 지우고 그대로 꽂힌 실제 사고
        # ('받음하실'류) 재현. 기대값은 'surface(easy_term)' 폴백인데,
        # annotate()가 원형만 남긴 것처럼 흉내낸다.
        text = "주소를 명기하여 주십시오."
        m = _match(
            start=4, end=8, surface="명기하여", term="명기", easy_term="쓰다",
            is_inflected=True,
        )
        ed = FakeEd(annotated="주소를 쓰다 주십시오.")
        out = ac.check_inflected_substitution({"id": "s", "text": text}, ed, [m])
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0]["surface"], "명기하여")

    def test_passes_when_inflected_match_falls_back_to_gloss_form(self) -> None:
        text = "주소를 명기하여 주십시오."
        m = _match(
            start=4, end=8, surface="명기하여", term="명기", easy_term="쓰다",
            is_inflected=True,
        )
        ed = FakeEd(annotated="주소를 명기하여(쓰다) 주십시오.")
        out = ac.check_inflected_substitution({"id": "s", "text": text}, ed, [m])
        self.assertEqual(out, [])


if __name__ == "__main__":
    unittest.main()
