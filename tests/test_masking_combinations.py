"""4조합 차분 측정기(`measure_masking_combinations.py`)가 실제로 무엇을 재는지 고정한다.

## 왜 측정기에 테스트가 붙는가 (게이트 14 N-13)

이 스크립트가 저장소에 들어온 이유는 **수를 다시 만들 수 있게** 하기 위해서다. 그런데
측정기 자신이 틀리면 재현 가능한 것은 틀린 수뿐이다. 특히 두 가지가 조용히 깨질 수 있다.

- **패턴 추출이 옛 값을 쓴다.** `Masking.kt` 의 상수 이름이 바뀌면 추출은 실패해야 하고,
  조용히 빈 값이나 기본값으로 진행하면 안 된다.
- **4조합이 실제로 갈리지 않는다.** 플래그가 배선되지 않아도 표는 그럴듯하게 출력된다.
  네 줄이 전부 같은 수를 내는 것은 "차이가 없다"가 아니라 **"재지 못했다"** 일 수 있다.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = REPO_ROOT / ".claude/skills/migration-safety-gate/scripts/measure_masking_combinations.py"


@pytest.fixture(scope="module")
def measurer() -> ModuleType:
    """스크립트를 모듈로 적재한다. 점 디렉터리라 일반 import 경로에 없다."""
    spec = importlib.util.spec_from_file_location("measure_masking_combinations", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


#: 네 갈래를 한 줄씩 담은 탐침. 각 줄이 어느 조합에서 어떻게 세어지는지가 아래 기대값이다.
PROBE = "\n".join(
    (
        "연도표 2021 2022 2023 2024 입니다",  # Luhn 실패 — 카드가 아니다
        "겹침 0000-4111-1111-1111-1111 확인",  # 앞이 거부되고 뒤에 유효 카드가 겹친다
        "정상 4111-1111-1111-1111 결제",  # 어느 조합에서도 카드다
        "주민 900101-1234567 신청",  # RRN — 두 플래그와 무관해야 한다
    )
)


def _counts(measurer: ModuleType, *, luhn: bool, rescan: bool) -> tuple[int, int]:
    patterns = measurer.extract_patterns(Path(measurer.MASKING_KT).read_text(encoding="utf-8"))
    found = measurer.scan_text(PROBE, patterns, luhn=luhn, rescan=rescan)
    return found.card, found.rrn


def test_패턴을_Masking_kt_에서_뽑는다(measurer: ModuleType) -> None:
    """손으로 옮겨 적은 사본이 아니라 정본에서 나온 것인지 본다."""
    patterns = measurer.extract_patterns(Path(measurer.MASKING_KT).read_text(encoding="utf-8"))

    # 표기 변형(전각 하이픈)까지 잡혀야 정본의 SEP 를 실제로 가져온 것이다.
    assert patterns.card.search("4111－1111－1111－1111")
    assert patterns.rrn.search("900101-1234567")


def test_상수가_사라지면_추출이_실패한다(measurer: ModuleType) -> None:
    """**조용히 옛 값을 쓰지 않는다.** 이름이 바뀌면 측정 자체가 서야 한다."""
    source = Path(measurer.MASKING_KT).read_text(encoding="utf-8")
    extra = 'regex = unicodeRegex("""(?<!\\d)\\d{5}"""),\n            '
    anchor = 'regex = unicodeRegex("""(?<!\\d)\\d{4}'
    tampered = source.replace(anchor, extra + anchor, 1)
    assert tampered != source

    with pytest.raises(measurer.ExtractionError, match="2개여야"):
        measurer.extract_patterns(tampered)


def test_패턴이_늘면_지표도_늘라고_알린다(measurer: ModuleType) -> None:
    """범주가 늘었는데 세는 축이 그대로면 조용히 안 세는 범주가 생긴다."""
    source = Path(measurer.MASKING_KT).read_text(encoding="utf-8")
    anchor = 'regex = unicodeRegex("""(?<!' + "\\" + "d)"
    extra = anchor + '{5}"""),\n            '
    tampered = source.replace(anchor, extra + anchor, 1)
    assert tampered != source, "패턴 줄을 찾지 못했다 — 이 탐침이 무엇도 재지 않는다"

    with pytest.raises(measurer.ExtractionError, match="2개여야"):
        measurer.extract_patterns(tampered)


def test_네_조합이_실제로_갈린다(measurer: ModuleType) -> None:
    """**전부 같은 수면 재지 못한 것이다.** 플래그가 배선됐는지 값으로 확인한다."""
    base = _counts(measurer, luhn=False, rescan=False)[0]
    luhn_only = _counts(measurer, luhn=True, rescan=False)[0]
    rescan_only = _counts(measurer, luhn=False, rescan=True)[0]
    both = _counts(measurer, luhn=True, rescan=True)[0]

    assert luhn_only < base, "Luhn 이 카드형 오탐을 줄이지 못했다"
    assert rescan_only == base, "Luhn 이 없으면 거부가 없으므로 재탐색은 아무것도 바꾸지 않는다"
    assert both > luhn_only, "재탐색이 겹친 유효 카드를 되살리지 못했다 — 게이트 12 차단①"
    assert both < base, "두 변경의 순효과는 감소여야 한다"


def test_RRN_은_두_플래그와_무관하다(measurer: ModuleType) -> None:
    """플래그는 CARD 축에만 걸린다. RRN 이 함께 움직이면 배선이 샌 것이다."""
    values = {
        _counts(measurer, luhn=luhn, rescan=rescan)[1]
        for luhn in (True, False)
        for rescan in (True, False)
    }

    assert len(values) == 1, f"RRN 개수가 조합마다 다르다: {values}"


def test_연도_4열을_따로_센다(measurer: ModuleType) -> None:
    """오탐의 지배적 형태를 별도 지표로 두지 않으면 순변화 뒤에 숨는다."""
    patterns = measurer.extract_patterns(Path(measurer.MASKING_KT).read_text(encoding="utf-8"))

    before = measurer.scan_text(PROBE, patterns, luhn=False, rescan=False)
    after = measurer.scan_text(PROBE, patterns, luhn=True, rescan=True)

    assert before.card_year_table > 0, "탐침에 연도 4열이 없다"
    assert after.card_year_table == 0, "Luhn 이 연도 4열을 걸러 내지 못했다"


def test_빈_코퍼스는_통과가_아니다(measurer: ModuleType, tmp_path: Path) -> None:
    """0건은 '위반 없음'이 아니라 '확인하지 않음'이다 — 다른 게이트와 같은 규율."""
    assert measurer.main([str(tmp_path)]) == 3
