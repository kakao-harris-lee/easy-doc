"""데이터 보호 불변식 스캐너(`.claude/skills/migration-safety-gate/scripts/`)의 상시 검증.

## 왜 저장소 안 테스트인가

이 스캐너는 CI `quality` 잡에서 도는 **BLOCK 게이트**다. 그런데 지금까지 그 동작을 확인한
것은 전부 **스크래치패드 1회성 실행**이었고, 그것이 실제로 결함을 낳았다 — 판정 3이
"배선하면 잡는다"고 선언한 뒤에도 다중 줄 호출을 통째로 놓치고 있었다(C-03). 1회성 실측은
그 시점의 사실이지 유지되는 성질이 아니다.

## 여기서 재는 것

1. **refine 훅이 지운 만큼**(privacy-gate 판정 §4-quater.1) — 오탐을 지우는 코드는 정의상
   탐지를 줄이는 코드이므로, 줄어든 범위를 재는 장치가 같은 자리에 있어야 한다.
2. **논리 줄 결합**(§4-quater.2) — 다중 줄 호출을 잡는가.
3. **범위 무결성**(§4-quater.3) — 루트가 사라지거나 대상이 0건이면 실패하는가.

숫자·문자열은 전부 이 파일에서 만든 합성값이다. 실제 문서·키를 넣지 않는다.
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path
from types import ModuleType

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
SCANNER_PATH = (
    REPO_ROOT
    / ".claude"
    / "skills"
    / "migration-safety-gate"
    / "scripts"
    / "scan_privacy_invariants.py"
)


def _load_scanner() -> ModuleType:
    """스캐너를 모듈로 적재한다.

    `sys.modules`에 먼저 등록하는 이유: 모듈 안의 `@dataclass`가 데코레이터 실행 중
    `sys.modules[cls.__module__]`를 조회하므로, 등록하지 않으면 `AttributeError`로 죽는다.
    """
    spec = importlib.util.spec_from_file_location("scan_privacy_invariants", SCANNER_PATH)
    if spec is None or spec.loader is None:  # pragma: no cover - 경로가 맞으면 성립한다
        raise AssertionError(f"스캐너를 적재할 수 없다: {SCANNER_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def scanner() -> ModuleType:
    return _load_scanner()


def _rule(scanner: ModuleType, rule_id: str) -> object:
    for rule in scanner.RULES:
        if rule.id == rule_id:
            return rule
    raise AssertionError(
        f"규칙 {rule_id!r}가 없다 — 이름이 바뀌었으면 이 테스트도 함께 고쳐야 한다."
    )


def _log_body_verdict(scanner: ModuleType, line: str) -> str:
    """한 줄을 `LOG-BODY`에 넣어 `CAUGHT`/`MISSED`를 돌려준다."""
    rule = _rule(scanner, "LOG-BODY")
    match = rule.pattern.search(line)  # type: ignore[attr-defined]  # Rule 은 이 모듈의 dataclass다
    if match is None:
        return "MISSED"
    refine = rule.refine  # type: ignore[attr-defined]
    if refine is not None and not refine(match):
        return "MISSED"
    return "CAUGHT"


# ── 판정 §4-quater.1 — refine 훅의 음성 대조 (없으면 privacy-gate 미승인) ────────────
#
# 통과해야 할 3줄과 **반드시 잡혀야 할 3줄**을 함께 둔다. 후자가 하나라도 통과하면
# 훅이 너무 넓은 것이다.

SUPPRESSED_LINES = [
    'print(f"마스킹: {detail} (총 {draft.stats.masked_total}건)")',
    'print(f"문서 id: {draft.document.id}")',
    'print(f"본문 {draft.stats.source_chars:,}자")',
]

STILL_CAUGHT_LINES = [
    'logger.info("변환 완료 {}", draft)',
    'logger.info("변환 완료 {}", draft.value)',
    'logger.info("변환 완료 {}", draft.text)',
]


@pytest.mark.parametrize("line", SUPPRESSED_LINES)
def test_refine_훅이_집계_멤버_보간을_거른다(scanner: ModuleType, line: str) -> None:
    assert _log_body_verdict(scanner, line) == "MISSED", (
        f"오탐으로 판정된 줄이 여전히 후보다: {line}"
    )


@pytest.mark.parametrize("line", STILL_CAUGHT_LINES)
def test_refine_훅이_본문_보간은_계속_잡는다(scanner: ModuleType, line: str) -> None:
    assert _log_body_verdict(scanner, line) == "CAUGHT", (
        f"훅이 너무 넓다 — 진짜 본문 보간이 빠져나간다: {line}"
    )


def test_안전_멤버_목록에_본문_이름이_없다(scanner: ModuleType) -> None:
    """`value`·`text`·`body` 등이 목록에 들어가면 훅이 규칙 자체를 무력화한다.

    모듈 적재 시점에도 같은 자기검사가 돌지만(그때는 `AssertionError`로 죽는다),
    여기서 한 번 더 보는 이유는 **그 자기검사가 지워지는 경우**를 잡기 위해서다.
    """
    for forbidden in ("value", "text", "body", "content", "original", "raw"):
        assert scanner._SAFE_ACCESS.fullmatch(f".{forbidden}") is None, (
            f"_SAFE_MEMBERS 에 {forbidden!r} 가 들어갔다 — LOG-BODY 가 아무것도 잡지 못하게 된다."
        )


def test_한_줄에_안전한_접근과_본문_접근이_섞이면_후보로_남는다(scanner: ModuleType) -> None:
    """정규식이 탐욕 매칭이라 **마지막** 본문 이름만 잡는다.

    적중 위치 하나만 판정하면 안전한 쪽을 보고 진짜 유출을 놓친다. 훅은 줄 안의 본문
    이름을 전부 본다.
    """
    본문먼저 = 'logger.info("{} {}", draft.value, draft.stats.count)'
    집계먼저 = 'logger.info("{} {}", draft.stats.count, draft.value)'

    assert _log_body_verdict(scanner, 본문먼저) == "CAUGHT"
    assert _log_body_verdict(scanner, 집계먼저) == "CAUGHT"


def test_한정자는_그_자체로_안전하지_않다(scanner: ModuleType) -> None:
    """`stats`·`document`는 뒤에 안전 멤버가 이어질 때만 통과한다."""
    assert _log_body_verdict(scanner, 'logger.info("{}", draft.stats)') == "CAUGHT"
    assert _log_body_verdict(scanner, 'logger.info("{}", draft.document.text)') == "CAUGHT"
    assert _log_body_verdict(scanner, 'logger.info("{}", draft[0])') == "CAUGHT"


def test_적중_위치_판정에_정규식_경계가_유지된다(scanner: ModuleType) -> None:
    """`review`가 있다고 `reviewed`가 잡히지는 않는다 — 낱말 단위다.

    이 성질을 잊으면 "비슷한 이름이 이미 있으니 잡히겠지"로 넘어가게 된다(판정 5 §4-bis.4).
    """
    assert re.search(rf"\b(?:{scanner.BODY_NAMES})\b", "reviewed") is not None
    assert re.search(rf"\b(?:{scanner.BODY_NAMES})\b", "혼자쓰는이름") is None
