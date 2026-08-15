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
import inspect
import random
import re
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

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
    reported = [hit.line for hit in result.hits["LOG-BODY"]]
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
    number = result.hits["LLM-RAW-INPUT"][0].line
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
        f"(줄 {[hit.line for hit in found]}). "
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

# 정의는 **스캐너에 한 벌**만 있다(Z-1). 여기서 재구현하지 않는다 — 두 벌이 각자 살면
# 갈리고, 갈린 쪽이 조용한 것은 늘 도달 쪽이었다. `scanner.LOGGER_SHAPED` 는 "검사됐어야
# 할 것", `scanner.LOG_CALL` 은 "검사할 것"이고, 이 파일은 그 둘의 **차집합**을 잰다.


def _logger_call_lines(scanner: ModuleType) -> list[tuple[Path, int, str]]:
    """스캔 루트에서 로그 호출로 보이는 **논리 줄**을 전부 모은다.

    ## 물리 줄이 아니라 논리 줄인 이유 (Z-1 줄 모델 통합)

    탐지는 논리 줄에서 돈다. 도달을 물리 줄로 세면 두 층이 서로 다른 문자열을 보게 되고,
    "탐지가 보는 것"과 "도달이 세는 것"이 영원히 어긋난다 — 여러 줄로 갈린 호출은
    물리 줄 어디에서도 온전하지 않아 도달 검사가 **애초에 후보로 세지 않는다.**
    """
    found: list[tuple[Path, int, str]] = []
    files, _scope = scanner.iter_files(False)
    for path in files:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        for logical in scanner.logical_lines(lines, python_syntax=path.suffix == ".py"):
            text = logical.text
            if text.startswith(("#", "//", "*")):
                continue
            if scanner.LOGGER_SHAPED.search(text):
                found.append((path, logical.number, text))
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
        if scanner.LOGGER_SHAPED.search(line) and log_call.search(line)
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


def test_한_줄의_적중을_전부_판정한다(scanner: ModuleType) -> None:
    """호출마다 **따로** 판정한다.

    §4-octies 로 `LOG-BODY` 의 2차 판정이 사라진 뒤로는 이 규칙에서 "앞이 뒤를 가린다"가
    재현되지 않는다 — 누를 것이 없으면 첫 적중에서 이미 후보다. 그래서 R-1 의 성질은
    **2차 판정이 남아 있는 규칙**에서 잰다(아래 `test_2차_판정이_있는_규칙에서…`).
    여기서는 각 호출이 독립적으로 후보가 되는지만 본다.
    """
    result = scanner.scan([PROBE_MULTI_CALL], {"LOG-BODY"})
    found = [hit.line for hit in result.hits.get("LOG-BODY", [])]

    # 한 줄 분기 **2** + `.also` 두 줄 2 + 순서뒤집기 **2** = 6.
    #
    # 4가 아니라 6인 것이 §4-novies 의 변경이다. 앞선 판은 한 줄에 호출이 둘이어도 적중을
    # **하나만** 냈고, 그래서 억제도 줄 단위일 수밖에 없었다(X-1). 이제 호출마다 적중이
    # 나므로 표기도 호출마다 결속된다 — 아래 ⓐ 재현이 그 성질을 직접 잰다.
    assert len(found) == 6, f"6건이 잡혀야 하는데 {len(found)}건이다 (줄 {found})."
    assert found.count(13) == 2, "한 줄의 두 호출이 각각 적중으로 나와야 한다"


def test_2차_판정이_있는_규칙에서_앞이_뒤를_가리지_않는다(scanner: ModuleType) -> None:
    """**R-1 의 성질은 여기서 산다.**

    `SECRET-LITERAL` 은 판별식을 통과해 `refine` 을 유지한 규칙이다(§4-octies.7 —
    자기 완결적 캡처 그룹의 값만 보고 괄호·주석 경계를 보지 않는다). 한 줄에 리터럴이
    둘이고 **앞이 픽스처 낱말**이면, `search` 하나만 넘기던 시절에는 뒤의 진짜 난수 키가
    통째로 검사되지 않았다.
    """
    rule = _rule(scanner, "SECRET-LITERAL")
    line = 'password = "wrongpassword"; api_key = "aG9uZ2dpbGRvbmc5OTk5MTIzNDU2Nzg5MA=="'

    matches = list(rule.pattern.finditer(line))  # type: ignore[attr-defined]
    assert len(matches) == 2, "탐침이 두 적중을 내지 않는다 (전제 확인)"
    assert rule.refine(matches[0]) is False, "앞 리터럴이 픽스처 낱말이어야 한다"  # type: ignore[attr-defined]
    assert rule.refine(matches[1]) is True, "뒤 리터럴이 난수꼴이어야 한다"  # type: ignore[attr-defined]

    dropped: list[str] = []
    found = scanner._candidates(rule, line, [line], 1, lambda _r, reason: dropped.append(reason))
    assert found, "뒤의 진짜 키가 후보로 나오지 않았다"
    assert len(dropped) == 1, "앞 적중이 2차 판정으로 빠진 뒤 뒤 적중까지 봐야 한다"


# ── 게이트 10 R-2 — 블록 주석 중첩 ─────────────────────────────────────────────────


def test_중첩_블록_주석이_주석_본문을_코드로_흘리지_않는다(scanner: ModuleType) -> None:
    """Kotlin은 블록 주석 중첩을 허용한다. Boolean 상태는 첫 `*/`에서 닫혀
    바깥 주석 본문이 코드로 새고, 그 본문의 `)` 하나가 인자 구간을 끊는다.
    """
    result = scanner.scan([PROBE_NESTED_COMMENT], {"LOG-BODY"})
    found = [hit.line for hit in result.hits.get("LOG-BODY", [])]

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
    state, _code, _comment = scanner._advance("/* 바깥 /* 안쪽", state, False)

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


# ── privacy-gate §4-octies — 억제 층을 호출 지점 가시 표기로 갈아탔다 ───────────────
#
# 중앙 휴리스틱(`refine`)이 다섯 갈래를 냈다. 그 훅은 "경로가 아니라 값"이라는 원칙을
# 끝까지 지켰고 목록이 넓어져 샌 갈래는 하나도 없었다 — **고른 축이 틀렸다.**
# 위험을 가르는 축은 **"억제가 아래 층의 정확성에 의존하는가"**이고, 의존하면 그 층의
# 모든 결함을 **조용한 통과로 상속한다.**
#
# 아래 단언들은 표기가 새 면제 목록이 되지 않게 거는 방어 a~f를 각각 잰다.


def _scan_source(scanner: ModuleType, tmp_path: Path, source: str, suffix: str = ".kt") -> Any:
    """합성 소스 한 벌을 스캔한다.

    반환을 `Any`로 두는 이유: `ScanResult`는 스캐너 모듈 안의 dataclass라 이 파일에서
    이름으로 가져올 수 없다(모듈을 `importlib`으로 적재한다). 구조를 다시 선언하면
    그 사본이 원본과 갈린다.
    """
    probe = tmp_path / f"probe{suffix}"
    probe.write_text(source, encoding="utf-8")
    return scanner.scan([probe], set())


LOGGING_LEAK = 'fun f(draft: Any) { logger.info("본문 {}", draft.value) }'


def _digest_for(
    scanner: ModuleType,
    tmp_path: Path,
    source: str,
    rule_id: str = "LOG-BODY",
    which: int = 0,
) -> str:
    """표기 **없는** 소스를 한 번 스캔해 적중의 지문을 얻는다.

    테스트가 지문을 손으로 적을 수 없고, 적으면 지문 계산이 바뀔 때마다 테스트가 통째로
    갈린다. 스캐너가 낸 값을 그대로 되먹이면 재구현이 없다 — 도달 검사가 정의를 import
    하는 것과 같은 이유다(Z-1).
    """
    probe = tmp_path / "digest_probe.kt"
    probe.write_text(source, encoding="utf-8")
    digest = scanner.scan([probe], {rule_id}).hits[rule_id][which].digest
    assert isinstance(digest, str), "지문이 문자열이 아니다 — Hit 계약이 바뀌었는지 확인하라"
    return digest


def test_표기가_없으면_억제_층은_항등_함수다(scanner: ModuleType) -> None:
    """**불변량: `suppress(hits, ∅) == hits`.**

    앞선 판에서는 이 명제를 쓸 수조차 없었다 — 검출과 억제가 같은 루프에 있어 억제가
    검출을 **가로챘다**(첫 적중이 눌리면 나머지가 탐색되지 않았다. R-1의 직접 원인).
    층을 나눈 이유가 이것이다.
    """
    hits = {
        "LOG-BODY": [
            scanner.Hit(Path("a.kt"), 1, "c0", "d0", "x"),
            scanner.Hit(Path("b.kt"), 7, "c1", "d1", "y"),
        ],
        "EXC-BODY": [scanner.Hit(Path("c.py"), 3, "c2", "d2", "z")],
    }

    kept, removed = scanner.suppress(hits, {})

    assert kept == hits
    assert removed == []


@pytest.mark.parametrize("seed", range(12))
def test_속성_무작위_적중에도_항등이다(scanner: ModuleType, seed: int) -> None:
    """속성 테스트 — 어떤 모양의 적중 목록에도 빈 색인은 아무것도 바꾸지 않는다."""
    rng = random.Random(seed)
    hits = {
        rng.choice(["LOG-BODY", "EXC-BODY", "XML-DTD"]): [
            scanner.Hit(
                Path(f"f{rng.randrange(5)}.kt"),
                rng.randrange(1, 50),
                f"c{rng.randrange(9)}",
                f"{rng.randrange(16**8):08x}",
                "text",
            )
            for _ in range(rng.randrange(1, 6))
        ]
        for _ in range(rng.randrange(1, 4))
    }

    kept, removed = scanner.suppress(hits, {})

    assert kept == hits
    assert removed == []


def test_표기가_자기_줄_자기_규칙만_누른다(scanner: ModuleType, tmp_path: Path) -> None:
    """방어 d·e — 와일드카드도, 파일·블록 범위도 없다."""
    source = f"{LOGGING_LEAK}  // privacy-allow: EXC-BODY — 다른 규칙\n{LOGGING_LEAK}\n"
    result = _scan_source(scanner, tmp_path, source)

    # 첫 줄은 **다른 규칙** 표기라 눌리지 않고, 둘째 줄은 표기가 없어 눌리지 않는다.
    assert len(result.hits.get("LOG-BODY", [])) == 2
    assert result.marker_problems, "규칙이 어긋난 표기는 고아로 잡혀야 한다"


def test_사유가_비면_누르지_못하고_실패한다(scanner: ModuleType, tmp_path: Path) -> None:
    """방어 c — `type: ignore` 사유 주석 규약과 같은 형태."""
    result = _scan_source(scanner, tmp_path, f"{LOGGING_LEAK}  // privacy-allow: LOG-BODY —\n")

    assert result.hits.get("LOG-BODY"), "사유 없는 표기가 적중을 눌렀다"
    assert any("보이는 문자" in problem for problem in result.marker_problems)


def test_알_수_없는_규칙_id_는_실패한다(scanner: ModuleType, tmp_path: Path) -> None:
    result = _scan_source(scanner, tmp_path, f"{LOGGING_LEAK}  // privacy-allow: NOPE — 사유\n")

    assert any("알 수 없는 규칙" in problem for problem in result.marker_problems)


def test_고아_표기는_실패한다(scanner: ModuleType, tmp_path: Path) -> None:
    """방어 f — **휴리스틱에는 없던 자가 정리 기제.**

    코드가 바뀌어 위험이 사라졌는데 표기만 남는 것을 막는다. 중앙 목록은 죽은 항목을
    영원히 품는다 — 어느 이름이 지금 무엇을 누르는지 아무도 모른다.
    """
    result = _scan_source(
        scanner, tmp_path, "val x = 1  // privacy-allow: LOG-BODY @deadbeef — 적중 없음\n"
    )

    assert any("고아 표기" in problem for problem in result.marker_problems)


def test_markable_이_아닌_규칙은_표기로_눌리지_않는다(scanner: ModuleType, tmp_path: Path) -> None:
    """§4-octies.3 — 이 여섯에서 "오탐이니 눌러 달라"는 표기가 아니라 **판정 요청**이다."""
    source = (
        "fun f(t: String) { provider.complete(sourceText, opts) }"
        "  // privacy-allow: LLM-RAW-INPUT — 눌러 달라\n"
    )
    result = _scan_source(scanner, tmp_path, source)

    assert result.hits.get("LLM-RAW-INPUT"), "markable=False 규칙이 표기로 눌렸다"
    assert any("누를 수 없다" in problem for problem in result.marker_problems)


def test_바로_위_단독_주석_표기만_아래_줄에_닿는다(scanner: ModuleType, tmp_path: Path) -> None:
    """표기 위치는 **같은 줄 끝**이거나 **바로 위 단독 주석** 둘뿐이다."""
    mark = _digest_for(scanner, tmp_path, LOGGING_LEAK + "\n")
    above = f"// privacy-allow: LOG-BODY @{mark} — 위 줄 단독\n{LOGGING_LEAK}\n"
    two_above = f"// privacy-allow: LOG-BODY @{mark} — 두 줄 위\nval x = 1\n{LOGGING_LEAK}\n"

    assert not _scan_source(scanner, tmp_path, above).hits.get("LOG-BODY")
    assert _scan_source(scanner, tmp_path, two_above).hits.get("LOG-BODY"), "두 줄 위 표기가 닿았다"


def test_예산_상한을_넘으면_실패한다(
    scanner: ModuleType, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """방어 a — 상한을 올리는 것은 그 자체가 diff이고 리뷰 대상이다."""
    monkeypatch.setattr(scanner, "MARKER_BUDGET", 1)
    mark = _digest_for(scanner, tmp_path, LOGGING_LEAK + "\n")
    source = f"{LOGGING_LEAK}  // privacy-allow: LOG-BODY @{mark} — 하나\n" * 2
    result = _scan_source(scanner, tmp_path, source)

    assert any("상한" in problem for problem in result.marker_problems)


def test_눌린_적중이_리포트에_위치와_사유로_전건_실린다(
    scanner: ModuleType, tmp_path: Path
) -> None:
    """**불변량 1.** 앞선 판은 개수만 남겼고, 그래서 R-1이 리포트 *안에서* 보이지 않았다."""
    mark = _digest_for(scanner, tmp_path, LOGGING_LEAK + "\n")
    result = _scan_source(
        scanner, tmp_path, f"{LOGGING_LEAK}  // privacy-allow: LOG-BODY @{mark} — 집계만 보간\n"
    )
    report, _blocking = scanner.render(result, 1, "테스트")

    assert not result.hits.get("LOG-BODY")
    assert "집계만 보간" in report, "사유가 리포트에 없다"
    assert "LOG-BODY" in report
    assert ":1" in report, "위치(줄 번호)가 리포트에 없다"


def test_표기를_전부_지우면_이관된_적중이_되살아난다(scanner: ModuleType) -> None:
    """**표기가 하중을 지고 있음의 증명.**

    지지 않는다면 그것은 억제 층이 아니라 장식이다. 저장소의 실제 표기를 지운 사본으로 잰다.
    """
    files, _scope = scanner.iter_files(False)
    marked = [
        path
        for path in files
        if scanner.MARKER_PREFIX in path.read_text(encoding="utf-8", errors="replace")
    ]
    assert marked, "저장소에 표기가 하나도 없다 — 이 테스트가 0건을 검사한다"

    with_markers = scanner.scan(marked, {"LOG-BODY"})
    assert not with_markers.hits.get("LOG-BODY"), "표기가 있는데도 적중이 남는다"
    assert with_markers.suppressions, "표기가 아무것도 누르지 않았다"

    # 같은 파일에서 표기 줄만 지운 사본. 적중이 **되살아나야** 표기가 하중을 진 것이다.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        stripped_paths: list[Path] = []
        for path in marked:
            body = "\n".join(
                line
                for line in path.read_text(encoding="utf-8").splitlines()
                if scanner.MARKER_PREFIX not in line
            )
            twin = Path(tmp) / f"twin{len(stripped_paths)}{path.suffix}"
            twin.write_text(body, encoding="utf-8")
            stripped_paths.append(twin)
        without = scanner.scan(stripped_paths, {"LOG-BODY"})

    assert without.hits.get("LOG-BODY"), (
        "표기를 지웠는데도 적중이 없다 — 표기가 하중을 지고 있지 않다(장식이다)."
    )
    assert len(without.hits["LOG-BODY"]) == len(with_markers.suppressions), (
        "지운 표기 수와 되살아난 적중 수가 다르다"
    )


def test_판별식이_지켜진다_refine_은_SECRET_LITERAL_뿐(scanner: ModuleType) -> None:
    """§4-octies.7 — 어휘·구문 층의 정확성에 의존하는 `refine`은 두지 않는다.

    새 `refine`이 늘면 그 판별식을 통과했는지 사람이 봐야 하므로, 늘어나는 것 자체를
    신호로 만든다.
    """
    with_refine = [rule.id for rule in scanner.RULES if rule.refine is not None]

    assert with_refine == ["SECRET-LITERAL"], (
        f"refine 을 가진 규칙이 {with_refine} 다. 새로 더했다면 Rule.refine KDoc 의 "
        "판별식을 통과했는지 확인하라 — 인자 구간·괄호·주석 경계를 읽어야 판정되는 것은 "
        "호출 지점 표기로 처리한다."
    )


def test_markable_배정이_설계대로다(scanner: ModuleType) -> None:
    """§4-octies.3의 배정. 여섯은 표기로 누를 수 없어야 한다."""
    markable = {rule.id for rule in scanner.RULES if rule.markable}

    assert markable == {
        "LOG-BODY",
        "LOG-FSTRING",
        "EXC-BODY",
        "ZIP-NO-BUDGET",
        "CACHE-HEADER",
        "RETENTION-PURGE",
    }
    assert not (markable & scanner.UNMARKABLE_RULES)


# ── §4-novies — 표기 키 = 적중 키 (X-1) ────────────────────────────────────────────
#
# 앞선 판의 표기 키는 `(rule_id, path, physical_line)` 이었다. 표기가 **그 줄의 그 규칙
# 적중을 무엇이든** 눌렀으므로, 사람이 사유를 쓸 때 본 것과 실제로 눌리는 것이 같다는
# 보장이 없었다. 아래 다섯이 그 보장을 잰다 — 1·2 는 X-1 의 두 실측을 그대로 회귀로
# 고정한 것이고, 이 둘이 없으면 같은 설계 결함이 다시 난다.

SAFE_CALL = 'logger.info("건수 {}", draft.stats.count)'
LEAK_CALL = 'logger.info("본문 {}", draft.value)'


def test_a_같은_줄의_두_번째_호출은_눌리지_않는다(scanner: ModuleType, tmp_path: Path) -> None:
    """**X-1 실측 ⓐ의 회귀.**

    표기 단 호출과 같은 논리 줄에 두 번째 개인정보 호출을 더한다. 줄 단위 억제에서는
    그것도 함께 눌렸다 — 아무도 승인한 적 없는 호출이 조용히 통과했다.
    """
    one = f"fun f(draft: Any) {{ {LEAK_CALL} }}\n"
    mark = _digest_for(scanner, tmp_path, one)

    two = f'fun f(draft: Any) {{ {LEAK_CALL}; logger.info("제목 {{}}", draft.title) }}\n'
    marked = two.replace("}\n", f"}}  // privacy-allow: LOG-BODY @{mark} — 첫 호출만\n")
    result = _scan_source(scanner, tmp_path, marked)

    survivors = result.hits.get("LOG-BODY", [])
    assert len(survivors) == 1, (
        f"두 번째 호출이 함께 눌렸다 (남은 적중 {len(survivors)}건). "
        "표기가 줄이 아니라 호출에 결속돼야 한다."
    )
    assert "draft.title" in survivors[0].text, "눌린 쪽과 남은 쪽이 뒤바뀌었다"


def test_b_인자가_바뀌면_표기가_어긋난다(scanner: ModuleType, tmp_path: Path) -> None:
    """**X-1 실측 ⓑ의 회귀.**

    구판 `refine` 은 값을 다시 봤기 때문에 인자가 바뀌면 판정도 다시 했다. 표기로 옮기며
    그 성질을 잃었고 — 표기는 줄만 보므로 인자가 무엇으로 바뀌든 계속 눌렀다. 지문이
    그 성질을 되찾는다.
    """
    before = f"fun f(draft: Any) {{ {LEAK_CALL} }}\n"
    mark = _digest_for(scanner, tmp_path, before)

    after = (
        'fun f(draft: Any) { logger.info("본문 {} {}", draft.value, draft.title) }'
        f"  // privacy-allow: LOG-BODY @{mark} — 예전 인자에 대한 사유\n"
    )
    result = _scan_source(scanner, tmp_path, after)

    assert result.hits.get("LOG-BODY"), "인자가 바뀌었는데도 옛 표기가 계속 눌렀다"
    assert any("고아 표기" in problem for problem in result.marker_problems), (
        "어긋난 표기가 조용히 남았다 — 실패는 항상 닫히는 쪽이어야 한다"
    )


def test_표기는_같은_줄의_다른_호출을_누르지_않는다(scanner: ModuleType, tmp_path: Path) -> None:
    """다른 호출의 지문을 단 표기는 이 줄의 어떤 호출도 누르지 않는다.

    ⓐ가 "표기 단 호출 **말고 다른 것**이 눌리는가"를 재는 반면, 여기서는 표기가 가리키는
    호출이 **이 줄에 아예 없을 때** 줄 위치만 보고 아무거나 누르지 않는지를 잰다.
    앞선 판은 줄만 봤으므로 이 경우에도 첫 적중을 눌렀다.

    `logger.info("건수 {}", draft.stats.count)` 도 적중이라는 점에 주의한다 — `LOG-BODY`
    의 2차 판정은 §4-octies 에서 삭제됐고(잘린 조각을 보고 "안전"을 판정하던 훅이다),
    안전함의 판단은 이제 사람이 표기로 한다.
    """
    absent = _digest_for(scanner, tmp_path, f"fun f(draft: Any) {{ {LEAK_CALL} }}\n")
    mixed = (
        f'fun f(draft: Any) {{ {SAFE_CALL}; logger.info("제목 {{}}", draft.title) }}'
        f"  // privacy-allow: LOG-BODY @{absent} — 이 줄에 없는 호출의 지문\n"
    )
    result = _scan_source(scanner, tmp_path, mixed)

    assert len(result.hits.get("LOG-BODY", [])) == 2, (
        "이 줄에 없는 호출의 지문을 단 표기가 무언가를 눌렀다"
    )
    assert any("고아 표기" in problem for problem in result.marker_problems)


def test_지문이_같아도_규칙_id_가_다르면_눌리지_않는다(scanner: ModuleType, tmp_path: Path) -> None:
    """방어 d — 와일드카드가 없다. 지문은 규칙을 가로지르지 않는다."""
    source = f"fun f(draft: Any) {{ {LEAK_CALL} }}\n"
    mark = _digest_for(scanner, tmp_path, source)

    marked = source.replace("}\n", f"}}  // privacy-allow: LOG-FSTRING @{mark} — 다른 규칙\n")
    result = _scan_source(scanner, tmp_path, marked)

    assert result.hits.get("LOG-BODY"), "다른 규칙의 표기가 LOG-BODY 적중을 눌렀다"


def test_호출_순서를_바꿔도_같은_호출이_눌린다(scanner: ModuleType, tmp_path: Path) -> None:
    """**`call_ref` 가 순서 의존이면 여기서 깨진다.**

    표기가 "이 줄의 n번째 호출"에 결속되면, 두 호출을 맞바꿨을 때 승인은 그대로인데
    눌리는 대상이 **다른 호출로 옮겨 간다.** 사유가 심사받은 범위와 눌리는 범위가
    어긋나는 또 하나의 경로다.
    """
    mark = _digest_for(scanner, tmp_path, f"fun f(draft: Any) {{ {LEAK_CALL} }}\n")
    other = 'logger.info("제목 {}", draft.title)'
    tail = f"  // privacy-allow: LOG-BODY @{mark} — 본문 호출만\n"

    forward = _scan_source(scanner, tmp_path, f"fun f(draft: Any) {{ {LEAK_CALL}; {other} }}{tail}")
    backward = _scan_source(
        scanner, tmp_path, f"fun f(draft: Any) {{ {other}; {LEAK_CALL} }}{tail}"
    )

    for name, result in (("정순", forward), ("역순", backward)):
        survivors = result.hits.get("LOG-BODY", [])
        assert len(survivors) == 1, f"{name}: 남은 적중이 {len(survivors)}건이다"
        assert "draft.title" in survivors[0].text, (
            f"{name}: 표기가 다른 호출로 옮겨 붙었다 — call_ref 가 순서에서 나오는지 확인하라"
        )


def test_불변량4_표기_하나는_적중_하나만_누른다(scanner: ModuleType, tmp_path: Path) -> None:
    """**불변량 4** — `|suppressed_by(marker)| ≤ 1`.

    지문까지 같은 적중이 둘이면 사람이 무엇을 승인했는지 확정할 수 없다. 그때는 **아무것도
    누르지 않고** 실패로 알린다 — 하나를 골라 누르면 나머지 하나가 조용히 통과한다.
    """
    single = f"fun f(draft: Any) {{ {LEAK_CALL} }}\n"
    mark = _digest_for(scanner, tmp_path, single)

    twice = (
        f"fun f(draft: Any) {{ {LEAK_CALL}; {LEAK_CALL} }}"
        f"  // privacy-allow: LOG-BODY @{mark} — 어느 쪽인지 알 수 없다\n"
    )
    result = _scan_source(scanner, tmp_path, twice)

    assert len(result.hits.get("LOG-BODY", [])) == 2, "모호한 표기가 무언가를 눌렀다"
    assert not result.suppressions, "억제가 하나라도 일어났다"
    assert any("적중 2건에 닿는다" in problem for problem in result.marker_problems)


def test_속성_한_표기가_두_적중을_억제하지_않는다(scanner: ModuleType) -> None:
    """불변량 4를 합성 적중으로 전수 확인한다. 무작위 배치에서도 성립해야 한다."""
    import random

    rng = random.Random(20260814)
    for _ in range(200):
        digest = f"{rng.randrange(16**8):08x}"
        line = rng.randrange(1, 20)
        path = Path("probe.kt")
        count = rng.randrange(1, 4)
        hits = {
            "LOG-BODY": [
                scanner.Hit(path, line, f"c{index}", digest, "text") for index in range(count)
            ]
        }
        markers = {
            path: [
                scanner.Marker(
                    line=line, rule_id="LOG-BODY", digest=digest, reason="사유", standalone=False
                )
            ]
        }
        _kept, removed = scanner.suppress(hits, markers)

        assert len(removed) <= 1, f"표기 하나가 {len(removed)}건을 눌렀다 (적중 {count}건)"


def test_지문_없는_표기는_아무것도_누르지_않는다(scanner: ModuleType, tmp_path: Path) -> None:
    """**4′** — 지문을 산출할 수 없으면 억제 대상이 아니다(닫힘)."""
    source = f"fun f(draft: Any) {{ {LEAK_CALL} }}  // privacy-allow: LOG-BODY — 지문 없음\n"
    result = _scan_source(scanner, tmp_path, source)

    assert result.hits.get("LOG-BODY"), "지문 없는 표기가 적중을 눌렀다"
    assert any("지문이 없다" in problem for problem in result.marker_problems)


# ── §4-novies.5 — 재생성 도구에 건 셋 ──────────────────────────────────────────────


def test_update_markers_는_CI_에서_거부된다(scanner: ModuleType, monkeypatch: Any) -> None:
    """CI 가 지문을 고칠 수 있으면 게이트가 자기를 통과시킨다."""
    monkeypatch.setenv("CI", "true")

    assert scanner.main(["--update-markers"]) == 2, "CI 에서 지문 갱신이 통과했다"
    assert scanner.running_in_ci(), "CI 판정이 환경 변수를 보지 않는다"


def test_update_markers_는_지문만_바꾸고_사유를_남긴다(scanner: ModuleType, tmp_path: Path) -> None:
    """사유가 그대로 남아야 리뷰어가 '이 사유가 새 내용에도 맞나'를 판단할 수 있다."""
    probe = tmp_path / "probe.kt"
    probe.write_text(
        f"fun f(draft: Any) {{ {LEAK_CALL} }}"
        "  // privacy-allow: LOG-BODY @deadbeef — 원래 사유 그대로\n",
        encoding="utf-8",
    )

    scanner.update_markers([probe], set())
    updated = probe.read_text(encoding="utf-8")

    assert "원래 사유 그대로" in updated, "도구가 사유를 건드렸다"
    assert "@deadbeef" not in updated, "지문이 갱신되지 않았다"
    assert not _scan_source(scanner, tmp_path, updated).hits.get("LOG-BODY"), (
        "갱신한 지문으로도 억제가 안 된다"
    )


# ── §4-novies.6 · 6-bis — X-5 · Z-1: 가정을 표로 만들고 미도달을 선언한다 ────────────
#
# 두 정의(`LOG_CALL` 탐지 / `LOGGER_SHAPED` 도달)는 **같은 가정**을 공유했다 —
# "로그 호출에는 이름 붙은 수신자가 있다". 그래서 둘이 함께 놓쳤고, 도달 검사가 미도달을
# 못 알렸다. 그 가정을 판정하러 온 grep 까지 같은 가정을 써서 **셋이 함께 틀렸다**.
#
# 처분은 은폐가 아니라 선언이다. 못 잡는 형태를 `xfail(strict=True)` 로 적어 두면
# 조용한 0이 **선언된 0**이 되고, 누가 그 형태를 탐지에 넣는 순간 `xpass` 로 뒤집혀
# 시끄러워진다. X-5가 깨뜨린 "채택 순간 시끄러운 실패" 전제를 이 장치가 실제로 세운다.

CHAIN_CALL_FILE = REPO_ROOT / (
    "backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/ContractErrorReportValve.kt"
)

#: 형태 목록. `reached=False` 는 **알려진 미도달**이며 `xfail` 로 붙는다.
LOGGER_SHAPES: list[tuple[str, str, bool]] = [
    ("명명 수신자 logger", 'logger.info("본문 {}", draft.value)', True),
    ("명명 수신자 log", 'log.error("본문 {}", draft.value)', True),
    ("체인 팩터리", 'LoggerFactory.getLogger(X::class.java).debug("본문 {}", draft.value)', False),
    ("정적 import", 'getLogger().error("본문 {}", draft.value)', False),
    ("kotlin-logging 람다", 'logger.info { "본문 ${draft.value}" }', False),
    ("slf4j fluent", 'logger.atInfo().log("본문 {}", draft.value)', False),
]


@pytest.mark.parametrize(
    ("name", "source", "reached"),
    [
        pytest.param(
            name,
            source,
            reached,
            id=name,
            marks=[] if reached else [pytest.mark.xfail(strict=True, reason="알려진 미도달 (X-5)")],
        )
        for name, source, reached in LOGGER_SHAPES
    ],
)
def test_로그_호출_형태_목록(scanner: ModuleType, name: str, source: str, reached: bool) -> None:
    """탐지 정의가 각 형태에 닿는가. **닿지 않는 것은 xfail 로 적혀 있다.**

    `xfail(strict=True)` 라 미도달이 해소되면 `xpass` 로 **실패**한다 — 그때 이 표에서
    `reached=True` 로 옮기라는 신호다. 표가 코드보다 뒤처지는 것을 표 자신이 막는다.
    """
    # **규칙 패턴으로 잰다.** `LOG_CALL` 은 호출 **토큰**만 보므로 `logger.info { ... }`
    # 처럼 괄호가 없는 형태에도 걸린다 — 그것은 "탐지된다"가 아니다. 실제로 적중을 내는
    # 것은 규칙(`{LOG_CALL}\s*\(...`)이므로 도달을 그쪽에서 재야 표가 사실과 맞는다.
    pattern = _rule(scanner, "LOG-BODY").pattern  # type: ignore[attr-defined]  # 동적 적재 모듈

    assert pattern.search(source), f"{name} 형태가 LOG-BODY 규칙에 안 잡힌다"


#: 체인 호출이 걸쳐 있는 물리 줄 수. 실물이 3줄이다.
CHAIN_BLOCK_LINES = 3


def _chain_call_block(text: str) -> str:
    """실물 체인 호출 **한 덩어리**를 한 줄로 이어 돌려준다. 못 찾으면 빈 문자열.

    ## `split("LoggerFactory")[1]` 로는 왜 안 되나 (직접 겪었다)

    처음엔 그렇게 썼다. 그런데 파일 맨 위에 `import org.slf4j.LoggerFactory` 가 있어
    **첫 조각이 import 뒤 200자**였고, 검사 대상이 호출부가 아니라 import·클래스 선언
    구간이었다. 음성 대조(탐지 패턴을 체인까지 넓혀 보기)를 돌렸을 때 `xpass` 가 나지 않아
    드러났다 — 강제자가 **엉뚱한 곳을 가리키고 있으면 고쳐도 안 울린다.**

    존재 단언과 도달 단언이 **이 함수 하나**를 공유한다. 두 벌로 두면 "있다고 확인한 것"과
    "못 잡는다고 확인한 것"이 서로 다른 대상이 될 수 있다.
    """
    lines = text.splitlines()
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("import") or "LoggerFactory" not in stripped:
            continue
        block = lines[index : index + CHAIN_BLOCK_LINES]
        if any(".getLogger(" in item for item in block):
            return " ".join(item.strip() for item in block)
    return ""


def test_실물_체인_호출이_아직_거기_있다(scanner: ModuleType) -> None:
    """**결속 단언 — xfail 이 가리키는 실물이 사라지면 여기서 실패한다.**

    아래 xfail 테스트 안에 이 확인을 두면 안 된다. `xfail(strict=True)` 아래에서는 **어떤
    실패도 '예상된 실패'로 흡수**되므로, 대상이 사라져 0건을 검사하게 된 상태와 대상이
    있는데 못 잡는 상태가 **로그에서 구분되지 않는다.** 그것이 선언된 0을 다시 조용한 0으로
    되돌리는 경로다(게이트 12 N-08).

    앞선 판은 `pytest.xfail(...)` **명령형**이라 더 나빴다 — 호출 즉시 테스트가 중단되므로
    탐지가 체인을 보게 되어도 `xpass` 가 나지 않고 조용히 통과했다. "누가 그 형태를 도입하면
    시끄러워진다"는 이 장치의 존재 이유가 그 한 줄로 무력화돼 있었다.
    """
    assert CHAIN_CALL_FILE.is_file(), f"실물이 없다: {CHAIN_CALL_FILE} — 옮겼다면 경로를 고쳐라"
    block = _chain_call_block(CHAIN_CALL_FILE.read_text(encoding="utf-8"))

    assert block, (
        "실물 체인 호출이 사라졌다. 아래 xfail 이 0건을 검사하는 상태이므로 "
        "형태 목록의 `체인 팩터리` 항목이 근거를 잃었다 — 다른 실물을 가리키거나 항목을 지워라."
    )
    assert scanner.LOGGER_SHAPED.search(block) is None, (
        "도달 정의가 체인을 보게 됐다 — 형태 목록과 아래 xfail 을 함께 다시 보라"
    )


@pytest.mark.xfail(
    strict=True,
    reason="체인 형태 미도달 (X-5) — 탐지가 이름 붙은 수신자를 가정한다",
)
def test_실물_체인_호출이_탐지에_잡힌다(scanner: ModuleType) -> None:
    """**이 표의 유일한 실물 미도달** — `ContractErrorReportValve.kt:104-106`.

    합성 탐침이 아니라 저장소의 그 줄들을 읽는다. 지금 그 자리에 `exception.message` 를
    넣어도 아무것도 빨개지지 않는다 — 유출은 없지만(인자가 예외 **타입 이름**뿐이다)
    **그 줄이 탐지 밖**이라는 것이 결함이다.

    `strict=True` 라 탐지가 체인을 보게 되면 `xpass` 로 **실패**한다. 그때 형태 목록의
    `체인 팩터리` 를 `reached=True` 로 옮기고 이 표시를 떼라.
    """
    block = _chain_call_block(CHAIN_CALL_FILE.read_text(encoding="utf-8"))

    assert re.compile(scanner.LOG_CALL).search(block), "체인 형태가 탐지 밖이다 (X-5)"


def test_축1_논리_줄_결합만으로는_체인이_닫히지_않는다(scanner: ModuleType) -> None:
    """**두 축 중 첫째 — 줄 모델.**

    실물은 세 물리 줄에 걸친 체인이다. 그런데 첫 줄(`LoggerFactory`)에는 열린 괄호가 없어
    논리 줄이 **거기서 끝난다** — 결합조차 일어나지 않는다. 줄 모델만 고쳐도 이 형태는
    닫히지 않는다는 것을 실물로 고정한다.
    """
    lines = CHAIN_CALL_FILE.read_text(encoding="utf-8").splitlines()
    logical = [ll for ll in scanner.logical_lines(lines) if ".getLogger(" in ll.text]
    assert logical, "실물 체인이 사라졌다"

    assert not any("debug(" in ll.text for ll in logical), (
        "체인이 한 논리 줄로 묶였다 — 그렇다면 이 테스트의 전제가 바뀐 것이므로 "
        "축2 테스트와 형태 목록의 `체인 팩터리` 항목을 함께 다시 보라"
    )


def test_축2_한_줄로_붙여도_수신자_가정_때문에_안_잡힌다(scanner: ModuleType) -> None:
    r"""**두 축 중 둘째 — 수신자 가정.**

    줄 모델을 완전히 이겼다고 가정하고(손으로 한 줄에 붙인다) 재도 여전히 무적중이다.
    `LOG_CALL` 의 `_?log(?:ger)?\.` 는 `LoggerFactory` 뒤에 `.` 가 아니라 `Factory` 가
    오므로 걸리지 않는다. 두 축은 독립이며 **둘 다** 고쳐야 닫힌다.
    """
    one_line = 'LoggerFactory.getLogger(X::class.java).debug("본문 {}", draft.value)'

    assert not re.compile(scanner.LOG_CALL).search(one_line), (
        "탐지가 체인을 보게 됐다 — 형태 목록의 `체인 팩터리` 를 reached=True 로 옮겨라"
    )
    assert not scanner.LOGGER_SHAPED.search(one_line), (
        "도달 정의가 체인을 보게 됐다 — 도달만 넓히면 탐지 미도달이 **드러나는** 것이므로 "
        "형태 목록과 함께 다시 보라"
    )


def test_도달_정의는_스캐너에_한_벌뿐이다(scanner: ModuleType) -> None:
    """**Z-1 처분의 회귀** — 이 파일이 정의를 재구현하면 두 벌이 갈린다.

    앞선 판은 `LOGGER_SHAPED` 를 테스트 파일에 두었고, 그래서 탐지 정의와 도달 정의가
    각자 살았다. 갈린 쪽이 조용한 것은 늘 도달이었다.
    """
    source = Path(__file__).read_text(encoding="utf-8")

    # 바늘을 조각으로 만든다 — 통째로 적으면 **이 파일 자신이 걸린다**.
    needle = "LOGGER_SHAPED = " + "re.compile"

    assert needle not in source, (
        "도달 정의가 이 파일에 재구현됐다 — 정의는 스캐너에 두고 import 만 한다"
    )
    assert hasattr(scanner, "LOGGER_SHAPED"), "스캐너에 도달 정의가 없다"


def test_어떤_워크플로도_지문_갱신을_돌리지_않는다() -> None:
    """**선언과 도달의 대조.** `running_in_ci()` 가 막는다고 적었으니 실제로 부르는 곳이
    없는지도 본다 — 환경 변수 판정이 어떤 러너에서 빗나가도 호출 자체가 없으면 안전하다.

    두 장치는 상보다. 환경 판정은 **사람이 CI 에서 손으로 부르는 것**까지 막고,
    이 검사는 **워크플로에 배선되는 것**을 막는다.
    """
    workflows = sorted((REPO_ROOT / ".github/workflows").glob("*.yml"))
    assert workflows, "워크플로를 하나도 못 찾았다 — 이 검사가 0건을 보고 있다"

    offenders = [
        path.name for path in workflows if "--update-markers" in path.read_text(encoding="utf-8")
    ]

    assert not offenders, (
        f"{offenders} 가 지문 갱신을 돌린다. CI 가 지문을 고칠 수 있으면 "
        "게이트가 자기를 통과시킨다."
    )


# ── N-20 — 필수 사유 검사가 보이지 않는 문자를 사유로 받던 자리 ──────────────────────
#
# `str.strip()` 뒤 truthy 로 물으면 **형식 문자(카테고리 Cf)가 통과한다.** 파이썬의 strip
# 은 유니코드 **공백**은 지우지만 ZWSP·WORD JOINER·ZWNJ 는 지우지 않는다. 그래서 보이지
# 않는 사유 하나로 억제가 성립했다 — 표기 설계가 "사유를 사람이 쓴다"에 걸어 둔 하중이
# 통째로 빠지는 자리다. 게이트 11 에서 지적됐다가 회차 사이에서 소멸했던 항목이다.


@pytest.mark.parametrize(
    ("name", "reason", "accepted"),
    [
        ("ZWSP 단독", "\u200b", False),
        ("WORD JOINER 단독", "\u2060", False),
        ("ZWNJ 단독", "\u200c", False),
        ("공백 단독", "   ", False),
        ("NBSP 단독", "\u00a0", False),
        ("보이는 문자 1개", "x", False),
        # §4-quaterdecies — 부정 목록 시절 목록 밖이라 통과하던 네 갈래.
        ("Mc 간격 결합 표시", "\u0903\u0903", False),
        ("Po 문장부호만", "....", False),
        ("Sm 수학 기호만", "+=<>", False),
        ("So 기타 기호만", "\u2600\u2601", False),
        ("정상 사유", "집계만 보간", True),
        ("보이지 않는 문자가 섞인 정상 사유", "집계만\u200b보간", True),
    ],
)
def test_사유_유효성_3방향(
    scanner: ModuleType, tmp_path: Path, name: str, reason: str, accepted: bool
) -> None:
    """**보이는 문자로 센다.** 세 방향(보이지 않는 것 / 공백 / 정상)을 상시로 둔다.

    한 방향만 재면 반대쪽이 조용히 깨진다 — 거절만 재면 정상 사유까지 막는 회귀를 못 보고,
    수락만 재면 이 결함이 그대로 돌아온다.
    """
    mark = _digest_for(scanner, tmp_path, LOGGING_LEAK + "\n")
    source = f"{LOGGING_LEAK}  // privacy-allow: LOG-BODY @{mark} — {reason}\n"
    result = _scan_source(scanner, tmp_path, source)

    if accepted:
        assert not result.hits.get("LOG-BODY"), f"{name}: 정상 사유인데 억제되지 않았다"
        assert not result.marker_problems, f"{name}: 정상 사유가 문제로 잡혔다"
    else:
        assert result.hits.get("LOG-BODY"), f"{name}: 사유 없이 억제가 성립했다"
        assert any("보이는 문자" in problem for problem in result.marker_problems), (
            f"{name}: 조용히 통과했다 — 스캔이 실패로 알려야 한다"
        )


def test_보이는_문자_계수가_strip_과_다르다(scanner: ModuleType) -> None:
    """**결함의 기제 자체를 고정한다.** 이 차이가 사라지면 위 탐침의 근거가 사라진다."""
    zwsp = "\u200b"

    assert zwsp.strip() == zwsp, "파이썬 strip 이 ZWSP 를 지우게 바뀌었다 — 탐침을 다시 보라"
    assert bool(zwsp.strip()) is True, "strip 기반 truthy 검사가 통과시키던 값이다"
    assert scanner.visible_length(zwsp) == 0, "보이는 문자 계수가 ZWSP 를 세고 있다"
    assert scanner.visible_length("집계만 보간") == 5, "공백을 빼고 세야 한다"
    assert scanner.visible_length("건수2") == 3, "숫자도 내용이다(N*)"


def test_보이는_문자가_긍정_목록으로_정의된다(scanner: ModuleType) -> None:
    """**구조로 확인한다** (§4-quaterdecies 재검증 기준).

    부정 목록은 "목록에 없는 모든 카테고리"라는 다음 갈래를 남긴다 — 열거를 넓히는 것으로
    닫히지 않는 형태다. 값 탐침만 두면 목록이 부정형으로 되돌아가도 그때 통과하던 갈래만
    안 뚫릴 뿐 구조는 되돌아간다. 그래서 값이 아니라 **정의의 모양**을 본다.
    """
    source = inspect.getsource(scanner)

    assert "_INVISIBLE_CATEGORIES" not in source, (
        "부정 목록이 남아 있다 — 받을 것을 정하는 긍정 목록이어야 한다"
    )
    assert scanner._CONTENT_CATEGORY_PREFIXES == ("L", "N"), (
        "내용으로 치는 카테고리가 글자·숫자가 아니다"
    )
    # 유니코드 카테고리 30종 중 L*/N* 밖은 전부 거부돼야 한다. 열거하지 않고 전수로 본다.
    import unicodedata

    probes = "".join(
        chr(code) for code in range(0x21, 0x3000) if unicodedata.category(chr(code))[0] not in "LN"
    )
    assert scanner.visible_length(probes) == 0, "L*/N* 밖의 문자가 내용으로 세어졌다"


def test_사유_판정이_한_곳에서만_난다(scanner: ModuleType) -> None:
    """억제와 진단이 같은 함수를 쓰는가. 두 벌이면 조용한 쪽은 늘 억제다."""
    source = inspect.getsource(scanner)

    assert source.count("def has_visible_reason") == 1, "판정 함수가 하나가 아니다"
    for user in ("_marker_touches", "marker_problems"):
        body = source.split(f"def {user}(")[1].split("\ndef ")[0]
        assert "has_visible_reason" in body, f"{user} 가 자기 판정을 갖고 있다"
