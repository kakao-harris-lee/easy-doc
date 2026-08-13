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
    # ── 사슬 종단 미고정 탈출 3종 (privacy-gate 해제 심사 §4-sexies.3) ──────────
    # 안전 멤버 **뒤에** 위험 멤버를 이어 붙이면 통과했다. `re.match()` 가 시작만
    # 고정하고 끝을 고정하지 않아, 안전 멤버에서 매칭이 끝난 뒤의 사슬이 검사되지
    # 않았다. 한정자 뒤에 안전 멤버를 하나 끼워 넣는 것이 곧 통행증이었다.
    #
    # **셋 중 실제로 샌 것은 첫 줄뿐이다** — 음성 대조 실측(종단 고정을 걷어내면
    # 첫 줄만 실패). 나머지 둘은 꼬리(`original`·`body`)가 `BODY_NAMES` 에도 있어
    # **다른 경로로 이미 잡히고 있었다.** 셋을 다 남기는 이유는 두 방어선이 각각
    # 살아 있음을 고정하기 위해서다 — 언젠가 `BODY_NAMES` 에서 그 이름이 빠지면
    # 종단 고정만 남고, 그때 이 줄들이 진짜 회귀 감지기가 된다.
    #
    # 종단 고정 **자체**를 재는 것은 아래 전용 테스트다(꼬리를 `BODY_NAMES` 밖의
    # `value` 로 두어 다른 방어선이 겹치지 않게 했다).
    'logger.info("{}", draft.document.id.value)',
    'logger.info("{}", draft.stats.count.original)',
    'logger.info("{}", draft.document.category.body)',
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


def test_안전한_멤버_뒤에_위험한_멤버를_이어_붙일_수_없다(scanner: ModuleType) -> None:
    """접근 사슬 **전체**가 한정자 + 종단 안전 멤버로만 이뤄져야 한다.

    이 성질을 따로 이름 붙여 두는 이유: 위 `STILL_CAUGHT_LINES` 가 세 줄을 값으로
    잡아 주지만, **왜** 잡혀야 하는지는 목록에서 읽히지 않는다. 결손은 안전 멤버
    목록이 아니라 **패턴의 끝**에 있었다 — 목록을 아무리 좁혀도 그 뒤에 무엇이든
    붙일 수 있으면 소용이 없다.

    `document` 한정자 때문이 아니라는 것도 함께 고정한다 — 판정문이 표에 직접 적은
    `stats` 로도 똑같이 샜다. 그래서 두 한정자 모두에 대해 단언한다.
    """
    for qualifier in ("document", "stats"):
        안전한_종단 = f'logger.info("{{}}", draft.{qualifier}.count)'
        위험한_꼬리 = f'logger.info("{{}}", draft.{qualifier}.count.value)'

        assert _log_body_verdict(scanner, 안전한_종단) == "MISSED", (
            f"집계 멤버로 끝나는 접근이 후보로 남는다: {안전한_종단}"
        )
        assert _log_body_verdict(scanner, 위험한_꼬리) == "CAUGHT", (
            f"안전 멤버 뒤에 이어 붙인 위험 멤버가 빠져나간다: {위험한_꼬리} — "
            "패턴 끝의 사슬 종단 고정이 사라졌는지 확인하라."
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


# ── 판정 §4-quater.2 — 다중 줄 호출 (C-03) ─────────────────────────────────────────
#
# 합성 탐침은 `SCAN_ROOTS` 밖(`tests/fixtures/`)에 둔다. 전수 스캔이 자기 탐침에 걸려
# CI가 빨개지면 안 되기 때문이다.

PROBE_DIR = REPO_ROOT / "tests" / "fixtures" / "privacy-scanner-probes"
PROBE_KOTLIN = PROBE_DIR / "MultilineProbe.kt"

#: 다중 줄로 써야만 재현되는 위반 3종. 판정문이 지정한 그대로다.
MULTILINE_RULES = ["LOG-BODY", "LLM-RAW-INPUT", "PLAINTEXT-PERSIST"]


def test_합성_탐침이_스캔_루트_밖에_있다() -> None:
    """탐침이 `SCAN_ROOTS` 안으로 들어오면 전수 스캔이 자기 자신을 잡아 CI가 빨개진다."""
    scanner = _load_scanner()
    assert PROBE_KOTLIN.is_file(), f"합성 탐침이 없다: {PROBE_KOTLIN}"
    for root in scanner.SCAN_ROOTS:
        assert not PROBE_KOTLIN.is_relative_to(REPO_ROOT / root), (
            f"탐침이 스캔 루트 {root!r} 안에 있다 — 전수 스캔이 자기 탐침을 잡는다."
        )


@pytest.mark.parametrize("rule_id", MULTILINE_RULES)
def test_다중_줄_호출을_잡는다(scanner: ModuleType, rule_id: str) -> None:
    """ktlint가 강제하는 Kotlin 줄바꿈 스타일에서도 인자 목록을 본다."""
    result = scanner.scan([PROBE_KOTLIN], set())
    assert rule_id in result.hits, (
        f"{rule_id}가 다중 줄 호출을 놓쳤다 — 논리 줄 결합이 깨졌는지 확인하라. "
        f"적중한 규칙: {sorted(result.hits)}"
    )


@pytest.mark.parametrize("rule_id", MULTILINE_RULES)
def test_줄_단위로는_같은_위반이_보이지_않는다(scanner: ModuleType, rule_id: str) -> None:
    """**이 테스트가 위 테스트의 값어치를 증명한다.**

    물리 줄 하나씩 보면 세 위반 모두 무적중이다. 그것이 C-03이 재현한 상태이고, 논리 줄
    결합이 없으면 위 테스트도 통과하지 못한다. 두 단언을 함께 두는 이유는, 위 테스트만
    있으면 "원래부터 잡히던 것 아닌가"를 구분할 수 없기 때문이다.
    """
    rule = _rule(scanner, rule_id)
    lines = PROBE_KOTLIN.read_text(encoding="utf-8").splitlines()
    # 한 줄 대조용으로 일부러 남겨 둔 마지막 호출은 제외한다 — 그것은 줄 단위로도 잡힌다.
    multiline_only = [line for line in lines if not line.strip().startswith('logger.info("변환')]
    for line in multiline_only:
        assert rule.pattern.search(line) is None, (  # type: ignore[attr-defined]
            f"{rule_id}가 물리 줄 하나에서 이미 잡힌다: {line.strip()!r} — "
            "그렇다면 이 탐침은 다중 줄 맹점을 재현하지 못한다."
        )


def test_한_줄_호출도_계속_잡는다(scanner: ModuleType) -> None:
    """논리 줄 도입이 기존 한 줄 탐지를 되돌리지 않았는지 본다."""
    result = scanner.scan([PROBE_KOTLIN], {"LOG-BODY"})
    reported = [number for _path, number, _line in result.hits["LOG-BODY"]]
    assert len(reported) >= 2, f"한 줄 호출과 다중 줄 호출이 함께 잡혀야 한다: {reported}"


def test_논리_줄_결합에_상한이_있다(scanner: ModuleType) -> None:
    """깨진 괄호 하나가 파일 전체를 한 줄로 만들면 오탐이 폭발한다."""
    broken = ["logger.info("] + [f"    arg{i}," for i in range(200)]
    joined = scanner.logical_lines(broken)

    assert len(joined) > 1, "상한이 없어 파일 전체가 한 논리 줄이 됐다."
    first_span = joined[1][0] - joined[0][0]
    assert first_span <= scanner.MAX_LOGICAL_LINE_SPAN, (
        f"논리 줄 하나가 물리 줄 {first_span}개를 삼켰다 (상한 {scanner.MAX_LOGICAL_LINE_SPAN})"
    )


def test_문자열_안의_괄호는_깊이로_세지_않는다(scanner: ModuleType) -> None:
    """문자열 안의 `(`를 세면 논리 줄이 엉뚱한 데서 끊기거나 파일 끝까지 이어진다."""
    joined = scanner.logical_lines(['println("웃는 얼굴 :-) 입니다")', "다음줄()"])

    assert len(joined) == 2, f"문자열 안의 괄호를 세어 논리 줄이 어긋났다: {joined}"


def test_보고_위치는_논리_줄의_시작_물리_줄이다(scanner: ModuleType) -> None:
    """사람이 파일을 열어 찾아갈 수 있어야 한다."""
    result = scanner.scan([PROBE_KOTLIN], {"LLM-RAW-INPUT"})
    _path, number, _line = result.hits["LLM-RAW-INPUT"][0]
    opening = PROBE_KOTLIN.read_text(encoding="utf-8").splitlines()[number - 1]

    assert "provider.complete(" in opening, (
        f"{number}행이 호출 시작 줄이 아니다: {opening.strip()!r}"
    )


# ── 판정 §4-quater.3 — 범위 무결성 (C-04) ──────────────────────────────────────────
#
# "0건은 '위반 없음'이 아니라 '확인하지 않음'"은 스크립트가 `--changed`에 이미 갖고 있던
# 원칙이다. 전수 모드에는 그것이 없어 **루트가 통째로 사라져도 성공 종료**였다.


def test_선언한_루트가_없으면_실패한다(
    scanner: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """**존재하는 루트만으로는 이 분기가 한 번도 실행되지 않는다.**

    그래서 판정문이 "`SCAN_ROOTS`에 없는 이름을 넣어" 확인하라고 지정했다. 조용히
    건너뛰던 시절에는 루트를 전부 오타로 바꿔도 대상 0건에 성공 종료였다.
    """
    monkeypatch.setattr(scanner, "SCAN_ROOTS", ["app", "존재하지-않는-루트"])

    with pytest.raises(scanner.ScopeError) as caught:
        scanner.iter_files(False)

    assert "존재하지-않는-루트" in str(caught.value)


def test_루트에_대상_파일이_없으면_실패한다(
    scanner: ModuleType, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """디렉터리는 남았는데 내용이 빠진 경우."""
    empty = tmp_path / "빈루트"
    empty.mkdir()
    monkeypatch.setattr(scanner, "REPO_ROOT", tmp_path)
    monkeypatch.setattr(scanner, "SCAN_ROOTS", ["빈루트"])

    with pytest.raises(scanner.ScopeError) as caught:
        scanner.iter_files(False)

    assert "검사 대상 파일이 하나도 없다" in str(caught.value)


def test_전수_모드_0건은_비영_종료다(
    scanner: ModuleType,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`--changed`와 **같은 코드·같은 문구**를 쓴다. 자기 원칙을 절반만 적용하지 않는다."""
    monkeypatch.setattr(scanner, "iter_files", lambda *_args, **_kwargs: ([], scanner.FULL_SCOPE))

    assert scanner.main([]) == 3
    assert "확인하지 않음" in capsys.readouterr().err


def test_루트_부재는_입력_오류_코드로_끝난다(
    scanner: ModuleType, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """결과가 아니라 **입력**의 문제이므로 2다 — 0건(3)과 구분한다."""
    monkeypatch.setattr(scanner, "SCAN_ROOTS", ["존재하지-않는-루트"])

    assert scanner.main([]) == 2
    assert "검사 범위 오류" in capsys.readouterr().err


def test_allow_empty_는_0건만_눌러주고_루트_부재는_못_누른다(
    scanner: ModuleType, monkeypatch: pytest.MonkeyPatch
) -> None:
    """범위 결손을 `--allow-empty`로 덮을 수 있으면 이 가드는 장식이 된다."""
    monkeypatch.setattr(scanner, "iter_files", lambda *_args, **_kwargs: ([], scanner.FULL_SCOPE))
    assert scanner.main(["--allow-empty"]) == 0

    monkeypatch.undo()
    monkeypatch.setattr(scanner, "SCAN_ROOTS", ["존재하지-않는-루트"])
    assert scanner.main(["--allow-empty"]) == 2
