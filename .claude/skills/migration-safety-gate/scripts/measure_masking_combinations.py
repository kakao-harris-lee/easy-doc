#!/usr/bin/env python3
"""마스킹 후보를 **4조합**으로 세어 두 변경의 효과를 분해한다.

## 왜 4조합인가 (privacy-gate §4-terdecies P2)

같은 코퍼스의 같은 지표를 두 변경이 **반대 방향으로** 움직인다.

- **Luhn 체크디짓**(§4-decies.4) — 카드형 적중을 **줄인다.** 연도·금액 4열 표가 카드로
  잡히던 오탐 29건이 대상이었다.
- **거부 후 재탐색**(게이트 12 차단①) — 거부된 매치와 **겹친** 유효 카드를 되살려 적중을
  **늘린다.** Luhn 도입이 만든 회귀였다.

따로 재면 순변화만 보이고 어느 쪽이 얼마를 움직였는지 분해할 수 없다. 그래서 두 플래그의
2×2 를 **같은 코퍼스·같은 실행**에서 잰다.

## 왜 저장소에 있는가 (게이트 14 N-13)

이 측정은 스크래치패드에서 한 번 돌고 사라졌다. 수는 판정문에 남았지만 **그 수를 다시
만들 수단이 없었다** — 코퍼스가 바뀌거나 패턴이 바뀌면 재측정이 불가능하고, 재측정할 수
없는 수는 다음 회차에 인용만 되고 검증되지 않는다.

## 패턴을 손으로 옮겨 적지 않는다

`Masking.kt` 에서 **프로그램으로 추출**한다. 손으로 옮기면 그 사본이 원본과 갈리고, 갈린
뒤에는 이 스크립트가 "지금 코드"가 아니라 "옛날 코드"를 측정한다. 상수 이름이 바뀌거나
사라지면 추출이 **실패로 끝난다** — 조용히 옛 값을 쓰지 않는다.

## 본문을 출력하지 않는다

프로젝트 보안 규칙(로그에 문서 본문·개인정보 금지)이 여기에도 적용된다. 이 스크립트가
내는 것은 **개수와 파일 경로뿐**이고 매치된 문자열은 어떤 경로로도 나가지 않는다.

사용:

    uv run python .claude/skills/migration-safety-gate/scripts/measure_masking_combinations.py \\
        tests/golden --report-md /tmp/combos.md
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]

#: 패턴 정본. 이 파일이 움직이면 추출이 실패하고, 그 실패가 곧 신호다.
MASKING_KT = REPO_ROOT / "backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt"

#: 코퍼스에서 읽을 확장자. 텍스트로 읽히는 것만 본다.
CORPUS_SUFFIXES = frozenset({".txt", ".md", ".json", ".jsonl", ".csv", ".yaml", ".yml"})

#: 카드 자릿수와 Luhn 상수. `Masking.kt` 의 동명 상수와 같은 값이며, 이쪽은 **알고리즘**이라
#: 추출 대상이 아니다(패턴과 달리 표기 변형이 없다).
CARD_DIGITS = 16
LUHN_WRAP = 9
DECIMAL_RADIX = 10

#: 연도로 볼 4자리 범위. 오탐의 지배적 형태가 연도 4열 표였다(§4-decies.4 실측 29건 중 다수).
YEAR_MIN = 1900
YEAR_MAX = 2100


class ExtractionError(RuntimeError):
    """`Masking.kt` 에서 패턴을 뽑지 못했다. **옛 값으로 진행하지 않는다.**"""


def _unescape(value: str) -> str:
    """Kotlin 소스의 `\\uXXXX` 를 실제 문자로. 다른 이스케이프는 그대로 둔다."""
    return re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), value)


def _raw_string(source: str, name: str) -> str:
    """`private const val NAME` 의 raw string 본문(삼중 따옴표 안)."""
    match = re.search(
        rf'private const val {re.escape(name)}\s*=\s*(?:\n\s*(?://[^\n]*\n\s*)*)?"""(.*?)"""',
        source,
        re.DOTALL,
    )
    if match is None:
        raise ExtractionError(f"{name} 을(를) 찾지 못했다 — 상수 이름이 바뀌었는지 확인하라")
    return _unescape(match.group(1))


def _plain_string(source: str, name: str) -> str:
    """`private const val NAME = "..."` 의 본문. 보간(`$X`)은 그대로 남긴다."""
    match = re.search(rf'private const val {re.escape(name)}\s*=\s*"([^"\n]*)"', source)
    if match is None:
        raise ExtractionError(f"{name} 을(를) 찾지 못했다 — 상수 이름이 바뀌었는지 확인하라")
    return match.group(1)


def _interpolate(template: str, values: dict[str, str]) -> str:
    """Kotlin 문자열 보간 `$NAME` 을 푼다. 모르는 이름이 남으면 실패다."""
    resolved = re.sub(
        r"\$([A-Za-z_][A-Za-z0-9_]*)",
        lambda m: values.get(m.group(1), "\x00" + m.group(1)),
        template,
    )
    if "\x00" in resolved:
        unknown = resolved.split("\x00")[1].split("$")[0]
        raise ExtractionError(f"보간을 풀지 못한 이름이 있다: ${unknown}")
    return resolved


@dataclass(frozen=True)
class Patterns:
    """`Masking.kt` 에서 뽑아 파이썬 정규식으로 옮긴 것."""

    rrn: re.Pattern[str]
    card: re.Pattern[str]


def extract_patterns(source: str) -> Patterns:
    """`Masking.kt` 소스에서 RRN·CARD 패턴을 만든다.

    Kotlin 의 `unicodeRegex` 는 `UNICODE_CHARACTER_CLASS` 를 켜므로 `\\d` 가 유니코드 십진
    숫자 전체를 본다. 파이썬 `str` 정규식의 `\\d` 가 같은 범위라 별도 플래그가 필요 없다.
    """
    values: dict[str, str] = {
        "HYPHEN_CHARS": _raw_string(source, "HYPHEN_CHARS"),
        "SPACE_CHARS": _raw_string(source, "SPACE_CHARS"),
    }
    values["SPACE_CLASS"] = _interpolate(_plain_string(source, "SPACE_CLASS"), values)
    values["HYPHEN_CLASS"] = _interpolate(_plain_string(source, "HYPHEN_CLASS"), values)
    values["SEP"] = _interpolate(_plain_string(source, "SEP"), values)

    found = re.findall(r'regex = unicodeRegex\("""(.*?)"""\)', source)
    if len(found) != 2:
        raise ExtractionError(
            f"unicodeRegex 패턴이 2개여야 하는데 {len(found)}개다 — 범주가 늘었다면 "
            "이 스크립트의 지표도 함께 늘려야 한다(조용히 세지 않는 범주를 만들지 않는다)"
        )
    rrn_src, card_src = (_interpolate(item, values) for item in found)
    return Patterns(rrn=re.compile(rrn_src), card=re.compile(card_src))


def digits_of(text: str) -> list[int]:
    """구분자를 뺀 십진값. `Character.digit` 과 같은 계수 단위(코드포인트)다."""
    return [int(char) for char in text if char.isdigit()]


def passes_luhn(text: str) -> bool:
    """카드번호 체크디짓. `Masking.kt::acceptsLuhn` 과 같은 알고리즘이다."""
    digits = digits_of(text)
    if len(digits) != CARD_DIGITS:
        return False
    total = 0
    for position, value in enumerate(reversed(digits)):
        contribution = value
        if position % 2 == 1:
            contribution *= 2
            if contribution > LUHN_WRAP:
                contribution -= LUHN_WRAP
        total += contribution
    return total % DECIMAL_RADIX == 0


def looks_like_year_table(text: str) -> bool:
    """네 그룹이 전부 연도로 읽히는가. 오탐의 지배적 형태를 따로 세기 위한 것이다."""
    groups = re.findall(r"\d{4}", text)
    if len(groups) != 4:
        return False
    return all(YEAR_MIN <= int(group) <= YEAR_MAX for group in groups)


@dataclass
class Counts:
    """한 조합의 측정값. **본문은 담지 않는다.**"""

    rrn: int = 0
    card: int = 0
    card_year_table: int = 0

    def total(self) -> int:
        return self.rrn + self.card


def scan_text(text: str, patterns: Patterns, *, luhn: bool, rescan: bool) -> Counts:
    """한 문서를 한 조합으로 센다.

    `rescan=True` 는 게이트 12 차단① 수정과 같은 동작이다 — 거부된 매치는 구간을 점유하지
    않으므로 **시작 + 1** 부터 다시 찾는다. `False` 는 그 수정 전의 `findAll().filter()`,
    즉 거부된 매치도 커서를 끝까지 전진시키던 동작이다.
    """
    counts = Counts()
    counts.rrn = sum(1 for _ in patterns.rrn.finditer(text))

    position = 0
    while position <= len(text):
        match = patterns.card.search(text, position)
        if match is None:
            break
        accepted = passes_luhn(match.group(0)) if luhn else True
        if accepted:
            counts.card += 1
            if looks_like_year_table(match.group(0)):
                counts.card_year_table += 1
            position = max(match.end(), match.start() + 1)
        else:
            position = match.start() + 1 if rescan else max(match.end(), match.start() + 1)
    return counts


def iter_corpus(root: Path) -> list[Path]:
    """코퍼스 파일 목록. 파일 하나를 주면 그것만 본다."""
    if root.is_file():
        return [root]
    return sorted(
        path
        for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in CORPUS_SUFFIXES
    )


COMBINATIONS: tuple[tuple[str, bool, bool], ...] = (
    ("① Luhn 없음 · 재탐색 없음 (두 변경 전)", False, False),
    ("② Luhn 있음 · 재탐색 없음 (§4-decies.4 직후 — 회귀 상태)", True, False),
    ("③ Luhn 없음 · 재탐색 있음 (재탐색만)", False, True),
    ("④ Luhn 있음 · 재탐색 있음 (현재)", True, True),
)


def measure(files: list[Path], patterns: Patterns) -> dict[str, Counts]:
    """4조합 전부를 같은 파일 집합에 대해 잰다."""
    totals = {label: Counts() for label, _luhn, _rescan in COMBINATIONS}
    for path in files:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for label, luhn, rescan in COMBINATIONS:
            found = scan_text(text, patterns, luhn=luhn, rescan=rescan)
            totals[label].rrn += found.rrn
            totals[label].card += found.card
            totals[label].card_year_table += found.card_year_table
    return totals


def render(totals: dict[str, Counts], files: int, corpus: Path) -> str:
    """마크다운 표. **개수와 경로만 싣는다.**"""
    lines = [
        "# 마스킹 후보 4조합 차분",
        "",
        f"코퍼스: `{corpus}` · 파일 {files}개",
        "",
        "| 조합 | RRN | CARD | 그중 연도 4열 |",
        "|---|---:|---:|---:|",
    ]
    for label, _luhn, _rescan in COMBINATIONS:
        item = totals[label]
        lines.append(f"| {label} | {item.rrn} | {item.card} | {item.card_year_table} |")

    base = totals[COMBINATIONS[0][0]]
    regressed = totals[COMBINATIONS[1][0]]
    current = totals[COMBINATIONS[3][0]]
    lines += [
        "",
        "## 분해",
        "",
        f"- **Luhn 의 효과**(① → ②): CARD {base.card} → {regressed.card} "
        f"({regressed.card - base.card:+d}) · 연도 4열 {base.card_year_table} → "
        f"{regressed.card_year_table} ({regressed.card_year_table - base.card_year_table:+d})",
        f"- **재탐색의 효과**(② → ④): CARD {regressed.card} → {current.card} "
        f"({current.card - regressed.card:+d})",
        f"- **순변화**(① → ④): CARD {base.card} → {current.card} ({current.card - base.card:+d})",
        "",
        "> 재현율 조건: ④ 가 ② 보다 작으면 안 된다. 작다면 재탐색이 되레 줄인 것이므로 "
        "게이트 12 차단① 이 되돌아간 것이다.",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("corpus", type=Path, help="코퍼스 디렉터리 또는 파일")
    parser.add_argument("--report-md", type=Path, help="마크다운 리포트 저장 경로")
    parser.add_argument(
        "--masking-kt",
        type=Path,
        default=MASKING_KT,
        help="패턴 정본 경로 (기본: backend-kotlin 의 Masking.kt)",
    )
    args = parser.parse_args(argv)

    if not args.masking_kt.is_file():
        print(f"패턴 정본이 없다: {args.masking_kt}", file=sys.stderr)
        return 2
    try:
        patterns = extract_patterns(args.masking_kt.read_text(encoding="utf-8"))
    except ExtractionError as exc:
        print(f"패턴 추출 실패: {exc}", file=sys.stderr)
        return 2

    files = iter_corpus(args.corpus)
    if not files:
        # 0건은 "위반 없음"이 아니라 "확인하지 않음"이다 — 이 저장소의 다른 게이트와 같은 규율.
        print(f"코퍼스에서 읽을 파일이 없다: {args.corpus}", file=sys.stderr)
        return 3

    report = render(measure(files, patterns), len(files), args.corpus)
    print(report)
    if args.report_md:
        args.report_md.parent.mkdir(parents=True, exist_ok=True)
        args.report_md.write_text(report + "\n", encoding="utf-8")
        print(f"\n[리포트] {args.report_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
