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
    """한 줄을 `LOG-BODY`에 넣어 `CAUGHT`/`MISSED`를 돌려준다.

    **스캐너의 판정 함수를 그대로 부른다.** 예전에는 이 헬퍼가 `search` + `refine`을
    직접 조립했는데, 그것은 스캐너의 **옛 구현을 베낀 것**이라 스캐너가 R-1로 고쳐진 뒤에도
    헬퍼만 옛 의미로 남아 테스트가 거짓 실패를 냈다. 판정 로직을 두 벌 두면 어느 쪽이
    진실인지 알 수 없어진다 — 헬퍼는 **호출만** 한다.
    """
    rule = _rule(scanner, "LOG-BODY")
    if rule.pattern.search(line) is None:  # type: ignore[attr-defined]  # Rule 은 이 모듈의 dataclass다
        return "MISSED"
    candidate = scanner._is_candidate(rule, line, [line], 1, lambda _rule_id, _reason: None)
    return "CAUGHT" if candidate else "MISSED"


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
        assert scanner._SAFE_MEMBER_RE.match(forbidden) is None, (
            f"_SAFE_MEMBERS 에 {forbidden!r} 가 들어갔다 — LOG-BODY 가 아무것도 잡지 못하게 된다."
        )
        assert forbidden not in scanner._SAFE_QUALIFIERS, (
            f"_SAFE_QUALIFIERS 에 {forbidden!r} 가 들어갔다 — 한정자는 사슬을 이어 주므로 "
            "여기 들어가면 그 뒤 어떤 안전 멤버로도 종단할 수 있다."
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
    first_span = joined[1].number - joined[0].number
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


# ── 게이트 09 M-01 — 접근 사슬 전체 파싱 ────────────────────────────────────────────
#
# `_SAFE_ACCESS` 정규식의 종단 고정 `(?!\.)`은 **점이 곧바로 이어지는 경우만** 막았다.
# 아래 셋은 전부 SUPPRESSED로 샜다. 특히 셋째가 나쁘다 — 논리 줄 결합(`" ".join`)이
# ktlint 강제 다중 줄 체인을 정확히 그 ` .` 모양으로 만들므로, **이 저장소의 표준
# 표기가 안전 판정을 받고 있었다.**

CHAIN_ESCAPES = [
    'logger.info("{}", draft.id[0])',
    'logger.info("{}", draft.stats.count().value)',
    'logger.info("{}", draft.id . value)',
    'logger.info( "{}", draft .stats .value )',
]


@pytest.mark.parametrize("line", CHAIN_ESCAPES)
def test_사슬_중간에_끼워_넣은_우회를_잡는다(scanner: ModuleType, line: str) -> None:
    assert _log_body_verdict(scanner, line) == "CAUGHT", (
        f"접근 사슬 우회가 빠져나간다: {line} — 종결자 열거로 되돌아갔는지 확인하라."
    )


def test_다중_줄_체인의_안전한_종단은_계속_거른다(scanner: ModuleType) -> None:
    """우회를 막느라 정당한 표기까지 후보로 만들면 이 훅은 못 쓰게 된다.

    ` .` 모양 자체는 죄가 없다 — 죄는 그 뒤에 위험 멤버가 오는 것이다.
    """
    assert _log_body_verdict(scanner, 'logger.info( "{}", draft .stats .masked_total )') == "MISSED"


def test_사슬_판정은_종결자를_열거하지_않는다(scanner: ModuleType) -> None:
    """호출·첨자 뒤의 값은 **성질을 알 수 없으므로** 안전하다고 하지 않는다.

    이것을 "`[`와 `(`도 종결자 목록에 넣는다"로 고치면 다음 표기에서 또 빠진다.
    사슬을 끝까지 읽는 쪽이 열거보다 좁다.
    """
    assert scanner.safe_access_chain("draft.stats.masked_total", len("draft")) is True
    assert scanner.safe_access_chain("draft.stats.masked_total[0]", len("draft")) is False
    assert scanner.safe_access_chain("draft.stats.count()", len("draft")) is False
    assert scanner.safe_access_chain("draft", len("draft")) is False


# ── 게이트 09 M-09 — 중첩 괄호에서 인자가 잘리지 않는다 ─────────────────────────────


def test_메시지_안의_괄호가_인자_구간을_끊지_않는다(scanner: ModuleType) -> None:
    """`[^)]*`는 첫 `)`에서 끊겨 그 뒤 인자를 못 봤다."""
    assert _log_body_verdict(scanner, 'logger.info("완료(1단계)", draft.value)') == "CAUGHT"
    assert scanner.balanced_arguments('f("a(b)", x)', 1) == '"a(b)", x'


# ── 게이트 09 M-03 — 논리 줄 조립의 fail-closed·상태 유지 ───────────────────────────

PROBE_OVER_CAP = PROBE_DIR / "OverCapProbe.kt"
PROBE_RAW_STRING = PROBE_DIR / "RawStringProbe.kt"


def test_상한을_넘긴_호출은_조용히_넘어가지_않는다(scanner: ModuleType) -> None:
    """**fail-open이 fail-closed로 바뀌었다.**

    앞선 판은 상한에 닿으면 새 논리 줄을 시작해 그 호출을 어느 논리 줄에서도 온전히
    보지 못했고, 리포트에는 아무 흔적도 남지 않았다. "검사했는데 없음"과 "검사하지
    못함"이 구분되지 않는 상태 — 이 스크립트가 스캔 루트 부재와 `--changed` 0건에서
    이미 거부한 형태다.
    """
    result = scanner.scan([PROBE_OVER_CAP], set())

    assert result.unscanned, "상한을 넘긴 호출이 미검사로 보고되지 않는다"
    _report, blocking = scanner.render(result, 1, "테스트")
    assert blocking >= 1, "미검사 논리 줄이 BLOCK 으로 집계되지 않는다"


def test_상한_초과라도_규칙이_볼_호출이_없으면_막지_않는다(scanner: ModuleType) -> None:
    """246개짜리 사전 리터럴이나 긴 KDoc은 규칙이 읽을 호출이 아니다.

    이 구분이 없으면 fail-closed가 정당한 긴 리터럴에서 영구히 빨개져, 결국 누군가
    상한을 무한대로 올리거나 fail-closed 자체를 되돌린다.
    """
    long_map = ["val words = mapOf(", *[f'    "낱말{i}" to "뜻{i}",' for i in range(80)], ")"]
    result = scanner.scan([PROBE_OVER_CAP], set())  # 탐침은 그대로 두고
    joined = scanner.logical_lines(long_map)

    assert not joined[0].complete, "이 리터럴은 상한을 넘는다 (전제 확인)"
    assert result.unscanned, "로그 호출이 든 탐침은 여전히 미검사로 보고돼야 한다"
    # 사전 리터럴에는 어떤 규칙의 호출 시작 모양도 없다.
    assert not any(opener.search(joined[0].text) for opener in scanner.ARG_LIST_OPENERS)


def test_raw_string_안의_괄호가_호출을_조기에_닫지_않는다(scanner: ModuleType) -> None:
    """어휘 상태를 물리 줄마다 초기화하면 raw string 안의 `)`가 코드로 읽힌다."""
    result = scanner.scan([PROBE_RAW_STRING], {"LOG-BODY"})

    assert "LOG-BODY" in result.hits, (
        "raw string 안의 괄호에 호출이 조기에 닫혀 뒤 인자를 보지 못한다 — "
        "LexState 가 물리 줄 사이에 유지되는지 확인하라."
    )


def test_multiline_규칙은_모두_opener_를_갖는다(scanner: ModuleType) -> None:
    """없으면 끊긴 논리 줄에서 **그 규칙만** fail-closed 밖으로 빠진다."""
    missing = [rule.id for rule in scanner.RULES if rule.multiline and rule.opener is None]
    assert not missing, f"opener 없는 multiline 규칙: {missing}"


def test_파이썬이_아닌_파일에서_샵은_주석이_아니다(scanner: ModuleType) -> None:
    """`.ts`의 `#private` 필드를 줄 주석으로 읽으면 그 줄의 나머지가 사라진다.

    선언(「틀릴 때는 이어 붙이는 쪽」)과 **반대 방향**의 결함이라 함께 고쳤다.
    """
    source = ["class A { #secret = f(", '    "x"', ") }"]

    # `#` 를 주석으로 보지 않으면 `(` 가 세어져 세 줄이 한 논리 줄로 묶인다.
    kotlin_like = scanner.logical_lines(source, python_syntax=False)[0]
    assert '"x"' in kotlin_like.text, f"과소 결합 — 뒷줄을 보지 못한다: {kotlin_like.text!r}"

    # 파이썬에서는 `#` 뒤가 주석이므로 그 줄에서 끊기는 것이 옳다.
    python_like = scanner.logical_lines(source, python_syntax=True)[0]
    assert '"x"' not in python_like.text


# ── codex stop-time 게이트 (2026-08-14) — 인자 파서가 주석을 몰랐다 ─────────────────
#
# 재현 조건 셋이 **함께** 성립해야 샌다:
#   ① 주석 안에 짝 없는 `)` → 인자 구간이 거기서 조기에 닫힌다
#   ② 그 앞에 **안전한** 본문 접근 → 2차 판정이 "찾았고 전부 안전"으로 빠진다
#   ③ 진짜 위험한 접근이 그 뒤 → 잘린 구간 밖이라 아예 검사되지 않는다
#
# ②가 없으면 "본문 이름 없음"으로 보수적 CAUGHT 가 나므로 드러나지 않는다. 그래서
# "주석이 있으면 샌다"가 아니라 **"안전한 접근이 위험한 접근의 방패가 된다"**가 정확하다.

PROBE_COMMENT_KOTLIN = PROBE_DIR / "CommentProbe.kt"
PROBE_COMMENT_PYTHON = PROBE_DIR / "comment_probe.py"


@pytest.mark.parametrize(
    ("probe", "expected"),
    [(PROBE_COMMENT_KOTLIN, 3), (PROBE_COMMENT_PYTHON, 2)],
)
def test_주석이_인자_구간을_끊어_뒤를_가리지_못한다(
    scanner: ModuleType, probe: Path, expected: int
) -> None:
    """주석이 낀 호출과 주석 없는 대조군이 **모두** 잡혀야 한다.

    수정 전에는 대조군만 잡혔다 — 주석 안의 `)`가 인자 구간을 조기에 닫고, 잘린 구간에
    남은 안전한 접근이 판정을 통과시켰다.
    """
    result = scanner.scan([probe], {"LOG-BODY"})
    found = result.hits.get("LOG-BODY", [])

    assert len(found) == expected, (
        f"{probe.name}: {expected}건이 잡혀야 하는데 {len(found)}건이다 "
        f"(줄 {[number for _p, number, _t in found]}). "
        "인자 파서가 주석을 다시 코드로 읽는지 확인하라."
    )


def test_주석_유무가_검출을_바꾸지_않는다(scanner: ModuleType) -> None:
    """**주석 제거가 탐지를 줄이는 방향으로 오작동하지 않는지** 보는 단언이다.

    고친 방향(주석을 코드에서 뺀다)은 그 자체로 탐지를 줄일 수 있다 — 주석 안에 있던
    무언가를 이제 안 보게 되기 때문이다. 그래서 "주석 있는 원본"과 "주석만 없앤 사본"의
    검출이 **같아야 한다**로 재고, 이 단언은 두 방향을 동시에 막는다:
    주석이 검출을 **만들어서도**, **없애서도** 안 된다.
    """
    for probe in sorted(PROBE_DIR.iterdir()):
        source = probe.read_text(encoding="utf-8")
        stripped = _strip_comments(source, python_syntax=probe.suffix == ".py")
        if stripped == source:
            continue  # 주석이 없는 탐침 — 비교할 것이 없다

        twin = probe.with_name(f"twin{probe.suffix}")
        try:
            twin.write_text(stripped, encoding="utf-8")
            original = scanner.scan([probe], set())
            without = scanner.scan([twin], set())
        finally:
            twin.unlink(missing_ok=True)

        assert {k: len(v) for k, v in original.hits.items()} == {
            k: len(v) for k, v in without.hits.items()
        }, f"{probe.name}: 주석 유무로 검출이 갈린다"


def _strip_comments(source: str, python_syntax: bool) -> str:
    """대조용 **참조 구현**. 스캐너와 독립적으로 주석만 지운다.

    스캐너의 `_advance`를 재사용하지 않는 이유: 같은 코드로 만든 두 입력을 비교하면
    그 코드가 틀렸을 때 둘이 같이 틀려 단언이 통과한다.
    """
    out: list[str] = []
    quote: str | None = None
    # **깊이로 센다.** Kotlin은 블록 주석 중첩을 허용하므로 Boolean으로 들면 첫 `*/`에서
    # 닫혀 바깥 주석 본문이 코드로 샌다(게이트 10 R-2). 참조 구현이 스캐너와 다른 의미를
    # 갖고 있으면 이 대조는 아무것도 재지 못한다.
    block = 0
    index = 0
    while index < len(source):
        rest = source[index:]
        if block > 0:
            if not python_syntax and rest.startswith("/*"):
                block += 1
                index += 2
            elif rest.startswith("*/"):
                block -= 1
                index += 2
            else:
                out.append("\n" if source[index] == "\n" else " ")
                index += 1
            continue
        if quote is not None:
            if rest.startswith("\\"):
                out.append(source[index : index + 2])
                index += 2
            elif rest.startswith(quote):
                out.append(quote)
                index += len(quote)
                quote = None
            else:
                out.append(source[index])
                index += 1
            continue
        if rest.startswith("//") or (python_syntax and rest.startswith("#")):
            while index < len(source) and source[index] != "\n":
                index += 1
            continue
        if not python_syntax and rest.startswith("/*"):
            block += 1
            index += 2
            continue
        opened = next((q for q in ('"""', "'''", '"', "'") if rest.startswith(q)), None)
        if opened is not None:
            quote = opened
            out.append(opened)
            index += len(opened)
            continue
        out.append(source[index])
        index += 1
    return "".join(out)


def test_문자열_안의_주석_기호는_주석이_아니다(scanner: ModuleType) -> None:
    """`"https://example.com"`의 `//`를 주석으로 읽으면 그 줄의 코드가 사라진다.

    §4-bis에서 문자열 리터럴 제거 때 겪은 것과 같은 함정이다 — 지우는 규칙은 반드시
    따옴표 상태와 함께 가야 한다.
    """
    source = ["logger.info(", '    "경로 https://example.com/x",', "    draft.value,", ")"]
    joined = scanner.logical_lines(source)[0]

    assert "https://example.com/x" in joined.text, "문자열 안의 `//`를 주석으로 읽었다"
    assert "draft.value" in joined.text


def test_주석_제거가_토큰을_붙이지_않는다(scanner: ModuleType) -> None:
    """블록 주석 자리에 공백을 남기지 않으면 앞뒤 토큰이 한 낱말로 붙는다."""
    joined = scanner.logical_lines(["val x = a/* 설명 */b"])[0]

    assert "ab" not in joined.text, f"토큰이 붙었다: {joined.text!r}"


# ── 게이트 10 R-3 — LOG_CALL 이 저장소의 로거 호출에 실제로 닿는가 ──────────────────
#
# `_?logger?\.`는 읽으면 "logger에서 r이 선택적"처럼 보이지만 정규식은
# `_?` + `logge` + `r?` + `\.`로 읽는다 — `logge.`·`logger.`는 잡고 **`log.`는 못 잡았다.**
# 저장소 로거 호출 4곳 중 2곳이 `GlobalExceptionHandler`의 `log.error(...)`이고,
# 하필 **전역 예외 핸들러**가 탐지 밖이었다.

#: 로그 호출로 **보이는** 것을 찾는 독립 정의. `LOG_CALL`을 재사용하지 않는다 —
#: 같은 패턴으로 찾고 같은 패턴으로 검사하면 그 패턴이 틀렸을 때 둘이 같이 틀린다.
LOGGER_SHAPED = re.compile(
    r"(?<![A-Za-z0-9_])[A-Za-z_]\w*(?:log|logger|LOG|LOGGER)\s*\."
    r"\s*(?:debug|info|warn|warning|error|exception|trace)\s*\("
    r"|(?<![A-Za-z0-9_.])(?:log|logger|LOG|LOGGER)\s*\."
    r"\s*(?:debug|info|warn|warning|error|exception|trace)\s*\("
)


def _logger_call_lines(scanner: ModuleType) -> list[tuple[Path, int, str]]:
    """스캔 루트에서 로그 호출로 보이는 줄을 전부 모은다."""
    found: list[tuple[Path, int, str]] = []
    files, _scope = scanner.iter_files(False)
    for path in files:
        for number, line in enumerate(
            path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
        ):
            stripped = line.strip()
            if stripped.startswith(("#", "//", "*")):
                continue
            if LOGGER_SHAPED.search(line):
                found.append((path, number, stripped))
    return found


def test_저장소의_로거_호출이_전부_LOG_CALL_에_잡힌다(scanner: ModuleType) -> None:
    """**패턴만 고치고 도달을 재지 않으면 다음에 또 조용해진다.**

    합성 탐침이 아니라 저장소의 실제 줄을 읽는다 — R-3가 드러난 방식이 정확히
    "합성 입력으로는 통과하는데 실물 이름이 달랐다"이기 때문이다.
    """
    calls = _logger_call_lines(scanner)
    assert calls, "로그 호출을 한 줄도 찾지 못했다 — 이 테스트가 0건을 검사하고 있다"

    log_call = re.compile(scanner.LOG_CALL)
    missed = [(p, n, t) for p, n, t in calls if not log_call.search(t)]

    assert not missed, "LOG_CALL 이 못 보는 로그 호출이 있다:\n" + "\n".join(
        f"  {p.relative_to(REPO_ROOT)}:{n} — {t[:80]}" for p, n, t in missed
    )


def test_전역_예외_핸들러의_로그가_검사_대상이다(scanner: ModuleType) -> None:
    """R-3가 실제로 물던 자리를 **파일과 함께** 고정한다.

    예외 메시지는 5xx 응답과 스택트레이스 로그 양쪽으로 흘러가므로, 전역 예외 핸들러는
    이 규칙이 가장 필요한 자리다. 그곳이 탐지 밖이었다.
    """
    handler = (
        REPO_ROOT
        / "backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt"
    )
    assert handler.is_file(), f"전역 예외 핸들러가 없다: {handler} — 옮겼다면 이 경로를 고쳐라."

    log_call = re.compile(scanner.LOG_CALL)
    matched = [
        line.strip()
        for line in handler.read_text(encoding="utf-8").splitlines()
        if LOGGER_SHAPED.search(line) and log_call.search(line)
    ]

    assert len(matched) >= 2, (
        f"전역 예외 핸들러의 로그 호출이 LOG_CALL 에 잡히지 않는다 (잡힌 것 {len(matched)}건). "
        "패턴이 `_?logger?\\.` 로 되돌아갔는지 확인하라 — 그것은 `log.` 를 못 본다."
    )


# ── 게이트 10 R-1 — 한 줄에 호출이 둘일 때 앞이 뒤를 가리지 않는가 ──────────────────
#
# `search`는 첫 적중 하나만 2차 판정에 넘겼다. 그 하나가 안전하면 같은 줄의 나머지 호출이
# 통째로 후보에서 빠진다 — `_advance` KDoc이 이름 붙인 "안전한 접근이 방패"가 호출과
# 호출 **사이**에 남아 있었다. c2255dc가 한 호출 **안**을 닫았고 이것은 호출 **개수**다.

PROBE_MULTI_CALL = PROBE_DIR / "MultiCallProbe.kt"
PROBE_NESTED_COMMENT = PROBE_DIR / "NestedCommentProbe.kt"


def test_한_줄의_두_번째_호출도_판정한다(scanner: ModuleType) -> None:
    """세 함수 전부 잡혀야 한다 — 앞이 안전한 둘과, 순서를 뒤집은 대조군."""
    result = scanner.scan([PROBE_MULTI_CALL], {"LOG-BODY"})
    found = [number for _p, number, _t in result.hits.get("LOG-BODY", [])]

    assert len(found) == 3, (
        f"3건이 잡혀야 하는데 {len(found)}건이다 (줄 {found}). "
        "줄 안의 적중을 전부 판정하는지(finditer) 확인하라."
    )


def test_순서를_뒤집으면_잡히던_것이_결함의_증거다(scanner: ModuleType) -> None:
    """**이 단언이 위 테스트의 값어치를 증명한다.**

    "위험한 호출이 앞이면 잡히고 뒤면 안 잡힌다"가 곧 "첫 적중 하나만 본다"이다.
    이것이 없으면 위 3건이 원래부터 잡히던 것인지 구분할 수 없다.
    """
    safe_first = (
        'if (ok) logger.info("건수 {}", draft.stats.count) else logger.info("본문 {}", draft.value)'
    )
    risky_first = (
        'if (ok) logger.info("본문 {}", draft.value) else logger.info("건수 {}", draft.stats.count)'
    )

    assert _log_body_verdict(scanner, safe_first) == "CAUGHT"
    assert _log_body_verdict(scanner, risky_first) == "CAUGHT"


# ── 게이트 10 R-2 — 블록 주석 중첩 ─────────────────────────────────────────────────


def test_중첩_블록_주석이_주석_본문을_코드로_흘리지_않는다(scanner: ModuleType) -> None:
    """Kotlin은 블록 주석 중첩을 허용한다. Boolean 상태는 첫 `*/`에서 닫혀
    바깥 주석 본문이 코드로 새고, 그 본문의 `)` 하나가 인자 구간을 끊는다.
    """
    result = scanner.scan([PROBE_NESTED_COMMENT], {"LOG-BODY"})
    found = [number for _p, number, _t in result.hits.get("LOG-BODY", [])]

    assert len(found) == 2, (
        f"중첩 주석 케이스와 대조군 둘 다 잡혀야 하는데 {len(found)}건이다 (줄 {found})."
    )


def test_중첩_주석_안의_호출은_잡지_않는다(scanner: ModuleType) -> None:
    """주석 **안**의 호출은 실행되지 않으므로 잡지 않는 것이 옳다.

    깊이 계수가 양방향으로 도는지 본다 — 조기에 닫으면 이 호출이 코드로 새어 나와
    **잡히고**, 그것은 과검출이지만 어휘 분석이 틀렸다는 신호다.
    """
    source = [
        "fun f(draft: Any) {",
        "    /* 주석 /* 중첩 */",
        '    logger.info("본문 {}", draft.value)',
        "    */",
        "}",
    ]
    joined = " ".join(logical.text for logical in scanner.logical_lines(source))

    assert "draft.value" not in joined, f"주석 안의 호출이 코드로 샜다: {joined!r}"


def test_블록_주석_깊이가_상태로_유지된다(scanner: ModuleType) -> None:
    """`LexState.block_depth`가 Boolean이 아니라 수인지 직접 본다."""
    state = scanner.LexState()
    state, _code = scanner._advance("/* 바깥 /* 안쪽", state, False)

    assert state.block_depth == 2, f"중첩 깊이를 세지 않는다: {state}"
    assert state.open


# ── 게이트 10 M-02 보완 — 탐지기 존재 확인의 상시 음성 대조 ─────────────────────────
#
# CI 스텝 분리는 닫혔지만 "클래스 하나를 지우면 실패한다"를 재는 장치가 CI 실행에만
# 있었다. ci.yml에서 **선언된 클래스를 추출해** 로컬에서 상시 확인한다
# (`test_harness_scope_reach.py`가 진행표를 읽는 것과 같은 방식).

CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"
KOTLIN_TEST_ROOT = REPO_ROOT / "backend-kotlin"


def test_CI_가_명시한_탐지기_클래스가_전부_실재한다() -> None:
    """클래스 하나를 지우면 **여기서 먼저** 빨개진다.

    CI의 클래스별 스텝은 실행 시점에 `No tests found`로 막지만, 그 방어는 CI를 돌려야만
    보인다. 파일 삭제는 로컬에서 즉시 드러나야 한다 — 삭제와 CI 실행 사이에 리뷰가 있다.
    """
    workflow = CI_WORKFLOW.read_text(encoding="utf-8")
    declared = re.findall(r"--tests\s+(kr\.easydoc[\w.]+)", workflow)

    assert declared, "ci.yml에서 --tests로 명시한 탐지기 클래스를 찾지 못했다"

    missing: list[str] = []
    for fqcn in declared:
        simple = fqcn.rsplit(".", 1)[-1]
        if not list(KOTLIN_TEST_ROOT.rglob(f"{simple}.kt")):
            missing.append(fqcn)

    assert not missing, (
        f"ci.yml이 명시한 탐지기 클래스의 소스가 없다: {missing}\n"
        "  클래스를 지웠다면 ci.yml의 그 스텝도 함께 지워야 하고, 그 diff가 리뷰에 올라간다."
    )


def test_탐지기_클래스가_개별_스텝으로_선언돼_있다() -> None:
    """**`--tests`를 한 스텝에 여러 개 주면 집합 의미론이라 하나만 지워도 통과한다.**

    M-02가 그 형태였다(실측 RC=0). 스텝당 클래스 하나인지를 못박는다.
    """
    workflow = CI_WORKFLOW.read_text(encoding="utf-8")
    steps = [
        step
        for step in workflow.split("      - name:")
        if "--tests" in step and "kr.easydoc" in step
    ]

    assert steps, "탐지기 존재 확인 스텝을 찾지 못했다"
    for step in steps:
        count = len(re.findall(r"--tests\s+kr\.easydoc", step))
        assert count == 1, (
            f"한 스텝이 --tests를 {count}개 준다 — 집합 의미론이라 하나만 지워도 통과한다.\n"
            f"  스텝: {step.splitlines()[0].strip()}"
        )
