#!/usr/bin/env python3
"""소비자(easy-doc)의 자체 치환 목록과 이 사전의 표제어·표면형이 겹치는지 검출한다.

## 배경 — 왜 이 도구가 필요한가

`easy-doc`(소비자)의 프롬프트에는 자체 치환 목록
`DIFFICULT_WORD_REPLACEMENTS`(248건, `backend-kotlin/.../DifficultWords.kt`)가
내장돼 있고, "왼쪽 낱말은 한 글자도 남기지 마라"는 지시가 같이 실린다. 같은
낱말이 이 사전(`easy-dictionary`)에도 있으면 — 특히 이 사전의 전략이
`gloss`("원래 말을 남겨라") 또는 `keep`("절대 바꾸지 마라")이면 — 한 프롬프트
안에 정반대 지시가 동시에 실린다. 실측 A/B(팀 보고)에서 이 구성이 스타일
붕괴(83.9%→51.8%)와 judge 실패 증가(2건→4건)의 유력 원인 축으로 지목됐다.
통합 전에 중복 표제어를 기계적으로 찾는 게 이 도구의 목적이다.

**이 도구는 읽기만 한다.** 소비자 목록도 `dist/`도 고치지 않는다 — 사람이
검토할 목록을 만드는 검수 도구다(`tools/detect_homonym_risk.py`와 같은 성격).

## 방법

1. 소비자 치환 목록을 세 형식 중 하나로 읽는다(Kotlin 소스 / JSON / 플레인
   텍스트, 자동 감지 또는 `--format`으로 지정).
2. 사전 색인(`dist/easy_dict.index.json`)의 `entries`(표제어 `t`)와
   `surface_index`(표제어 + 활용형·변형형 표면형) 양쪽에서 소비자 낱말과
   겹치는 엔트리를 찾는다. **표제어만 보면 안 된다** — 소비자 낱말이 어떤
   표제어의 변형형(활용형)과 우연히 같은 문자열이면, 런타임에는 그 변형형이
   실제 매칭 대상이 되므로 여전히 같은 낱말을 두 지시가 다루게 된다.
3. 겹치는 각 (소비자 낱말, 사전 엔트리) 쌍마다 사전 전략으로 충돌 여부를
   판정한다:
   - `gloss`/`keep` — **CONFLICT** (치환 지시와 보존 지시가 정면으로 모순)
   - `substitute` — 대치어가 소비자 것과 같으면 **DUPLICATE**(무해 중복),
     다르면 **DIVERGENT**(같은 낱말을 서로 다른 말로 바꾸라는 지시가 공존)

## 한계

- 문자열 동일 여부만 본다. 형태소 분석 없이 "겹친다"만 판정하고, 실제 문서
  문맥에서 어느 쪽 지시가 이기는지는 보지 않는다(그건 LLM 프롬프트 안의 우선
  순위 문제이지 이 도구의 판정 대상이 아니다).
- 소비자 목록의 오타·변형 표기(예: 표제어 앞뒤 공백)는 그대로 실패한 매칭이
  된다 — 정규화하지 않는다.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INDEX_PATH = REPO_ROOT / "dist" / "easy_dict.index.json"
DEFAULT_KT_VAR_NAME = "DIFFICULT_WORD_REPLACEMENTS"

_KNOWN_FORMATS = ("kotlin", "json", "text")
_VERDICT_ORDER = {"CONFLICT": 0, "DIVERGENT": 1, "DUPLICATE": 2}

# Kotlin `"금일" to "오늘",` 패턴. 값 안에 큰따옴표 자체가 오는 사례는 실제
# DifficultWords.kt에 없다(확인함) — 그런 이스케이프까지 다루는 완전한
# Kotlin 파서가 아니라, 이 파일이 실제로 쓰는 단순한 리터럴 맵 형태만 다룬다.
_KOTLIN_PAIR_RE = re.compile(r'"([^"]+)"\s+to\s+"([^"]+)"')


# ---------------------------------------------------------------------------
# 소비자 치환 목록 파싱 (형식 3종)
# ---------------------------------------------------------------------------

def parse_kotlin(text: str, var_name: str = DEFAULT_KT_VAR_NAME) -> dict[str, str]:
    """`val <var_name>: Map<...> = linkedMapOf(... "금일" to "오늘", ...)` 블록만 뽑는다.

    다음 `val ` 선언(또는 파일 끝) 전까지만 스캔한다 — 같은 파일에 다른
    맵·집합(`PROMPT_ONLY_WORDS` 등)이 더 있어도 섞이지 않게.
    """
    marker = f"val {var_name}"
    idx = text.find(marker)
    if idx == -1:
        raise ValueError(f"Kotlin 소스에서 'val {var_name}' 선언을 찾을 수 없음")
    rest = text[idx + len(marker):]
    next_val = re.search(r"\nval\s+\w+", rest)
    section = rest[: next_val.start()] if next_val else rest
    pairs = _KOTLIN_PAIR_RE.findall(section)
    if not pairs:
        raise ValueError(f"'val {var_name}' 블록에서 \"X\" to \"Y\" 패턴을 하나도 못 찾음")
    return dict(pairs)


def parse_json_map(text: str) -> dict[str, str]:
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError("JSON 입력은 {\"낱말\": \"대치어\", ...} 형태의 오브젝트여야 함")
    return {str(k): str(v) for k, v in data.items()}


def parse_text_list(text: str) -> dict[str, str]:
    """한 줄에 표제어 하나. 탭 또는 ` -> ` 로 구분된 대치어는 있어도, 없어도 된다.

    대치어가 없는 줄은 값이 빈 문자열이다 — 그래도 표제어 자체가 사전과
    충돌하면(gloss/keep) CONFLICT로 잡힌다. `#`으로 시작하는 줄과 빈 줄은
    건너뛴다.
    """
    result: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "\t" in line:
            word, _, repl = line.partition("\t")
        elif " -> " in line:
            word, _, repl = line.partition(" -> ")
        else:
            word, repl = line, ""
        word = word.strip()
        if word:
            result[word] = repl.strip()
    return result


def detect_format(path: Path, explicit: str | None, text: str) -> str:
    if explicit:
        if explicit not in _KNOWN_FORMATS:
            raise ValueError(f"알 수 없는 --format: {explicit} (가능: {', '.join(_KNOWN_FORMATS)})")
        return explicit
    suffix = path.suffix.lower()
    if suffix == ".kt":
        return "kotlin"
    if suffix == ".json":
        return "json"
    stripped = text.lstrip()
    if stripped.startswith("{"):
        return "json"
    return "text"


def load_consumer_list(
    path: Path, *, fmt: str | None = None, kt_var_name: str = DEFAULT_KT_VAR_NAME,
) -> dict[str, str]:
    text = path.read_text(encoding="utf-8")
    resolved = detect_format(path, fmt, text)
    if resolved == "kotlin":
        return parse_kotlin(text, kt_var_name)
    if resolved == "json":
        return parse_json_map(text)
    return parse_text_list(text)


# ---------------------------------------------------------------------------
# 사전 색인과의 대조
# ---------------------------------------------------------------------------

@dataclass
class DictMatch:
    entry_id: str
    dict_term: str  # 이 엔트리의 표제어(t)
    dict_easy_term: str | None  # 이 엔트리의 쉬운말(e)
    strategy: str  # s
    risk: str  # r
    match_kind: str  # "headword" | "surface_variant"


@dataclass
class Overlap:
    word: str
    consumer_replacement: str
    match: DictMatch
    verdict: str  # CONFLICT | DIVERGENT | DUPLICATE


def build_term_index(entries: dict[str, dict[str, Any]]) -> dict[str, list[str]]:
    """표제어(t) -> 그 표제어를 가진 엔트리 id 목록."""
    idx: dict[str, list[str]] = {}
    for entry_id, entry in entries.items():
        idx.setdefault(entry["t"], []).append(entry_id)
    return idx


def find_dict_matches(
    word: str,
    term_index: dict[str, list[str]],
    surface_index: dict[str, list[int]],
    entries: dict[str, dict[str, Any]],
) -> list[DictMatch]:
    """`word`와 겹치는 사전 엔트리를 표제어 직접 일치 + 표면형 일치 양쪽에서 모은다.

    같은 엔트리가 두 경로(표제어 자신 == word 이면서 surface_index[word]에도
    있음, 실제로 흔한 경우) 모두로 잡히면 한 번만 보고한다.
    """
    out: list[DictMatch] = []
    seen_ids: set[str] = set()

    for entry_id in term_index.get(word, []):
        entry = entries[entry_id]
        out.append(DictMatch(
            entry_id=entry_id, dict_term=entry["t"], dict_easy_term=entry.get("e"),
            strategy=entry["s"], risk=entry["r"], match_kind="headword",
        ))
        seen_ids.add(entry_id)

    for raw_id in surface_index.get(word, []):
        entry_id = str(raw_id)
        if entry_id in seen_ids:
            continue
        entry = entries.get(entry_id)
        if entry is None:
            continue
        out.append(DictMatch(
            entry_id=entry_id, dict_term=entry["t"], dict_easy_term=entry.get("e"),
            strategy=entry["s"], risk=entry["r"], match_kind="surface_variant",
        ))
        seen_ids.add(entry_id)

    return out


def classify_conflict(consumer_replacement: str, match: DictMatch) -> str:
    """사전 전략을 기준으로 소비자 치환 지시와의 충돌 등급을 매긴다."""
    if match.strategy in ("gloss", "keep"):
        return "CONFLICT"
    if match.strategy == "substitute":
        return "DUPLICATE" if match.dict_easy_term == consumer_replacement else "DIVERGENT"
    # schema.sql상 replace_strategy는 substitute/gloss/keep 셋뿐이다(방어적 처리).
    return "CONFLICT"


def analyze(consumer: dict[str, str], index_doc: dict[str, Any]) -> list[Overlap]:
    entries: dict[str, dict[str, Any]] = index_doc["entries"]
    surface_index: dict[str, list[int]] = index_doc.get("surface_index", {})
    term_index = build_term_index(entries)

    overlaps: list[Overlap] = []
    for word, replacement in consumer.items():
        for match in find_dict_matches(word, term_index, surface_index, entries):
            overlaps.append(Overlap(
                word=word, consumer_replacement=replacement, match=match,
                verdict=classify_conflict(replacement, match),
            ))
    return overlaps


def _overlap_sort_key(ov: Overlap) -> tuple[int, str, str]:
    return (_VERDICT_ORDER.get(ov.verdict, 99), ov.word, ov.match.entry_id)


def overlap_to_dict(ov: Overlap) -> dict[str, Any]:
    return {
        "word": ov.word,
        "consumer_replacement": ov.consumer_replacement,
        "entry_id": ov.match.entry_id,
        "dict_term": ov.match.dict_term,
        "dict_easy_term": ov.match.dict_easy_term,
        "dict_strategy": ov.match.strategy,
        "dict_risk": ov.match.risk,
        "match_kind": ov.match.match_kind,
        "verdict": ov.verdict,
    }


# ---------------------------------------------------------------------------
# 출력
# ---------------------------------------------------------------------------

def print_report(overlaps: list[Overlap]) -> None:
    print("=" * 72)
    print("소비자 치환 목록 vs 사전 표제어·표면형 중복 검출")
    print("=" * 72)
    if not overlaps:
        print("중복 없음.")
        print("=" * 72)
        return

    by_verdict: dict[str, list[Overlap]] = {}
    for ov in sorted(overlaps, key=_overlap_sort_key):
        by_verdict.setdefault(ov.verdict, []).append(ov)

    for verdict in ("CONFLICT", "DIVERGENT", "DUPLICATE"):
        group = by_verdict.get(verdict, [])
        if not group:
            continue
        print(f"[{verdict}] {len(group)}건")
        for ov in group:
            m = ov.match
            where = m.dict_term if m.match_kind == "headword" else f"{m.dict_term}의 변형형('{ov.word}')"
            print(
                f"    · {ov.word!r} 소비자대치어={ov.consumer_replacement!r} "
                f"| 사전표제어={where!r} 전략={m.strategy} 위험도={m.risk} "
                f"(entry_id={m.entry_id})"
            )
    print("-" * 72)
    counts = Counter(ov.verdict for ov in overlaps)
    print(
        f"총 중복 {len(overlaps)}건: "
        f"CONFLICT {counts.get('CONFLICT', 0)}건, "
        f"DIVERGENT {counts.get('DIVERGENT', 0)}건, "
        f"DUPLICATE {counts.get('DUPLICATE', 0)}건"
    )
    print("=" * 72)


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--consumer", required=True, type=Path, help="소비자 치환 목록 경로(.kt/.json/텍스트)")
    p.add_argument("--index", type=Path, default=DEFAULT_INDEX_PATH, help="사전 색인 경로(기본: dist/easy_dict.index.json)")
    p.add_argument("--format", choices=_KNOWN_FORMATS, default=None, help="입력 형식 강제 지정(기본: 확장자/내용으로 자동 감지)")
    p.add_argument("--kt-var-name", default=DEFAULT_KT_VAR_NAME, help="Kotlin 입력일 때 읽을 val 이름(기본: DIFFICULT_WORD_REPLACEMENTS)")
    p.add_argument("--json", action="store_true", help="사람이 읽는 표 대신 기계 판독용 JSON 배열을 stdout에 출력")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    if not args.consumer.exists():
        print(f"오류: 소비자 목록 파일이 없습니다: {args.consumer}", file=sys.stderr)
        return 2
    if not args.index.exists():
        print(f"오류: 사전 색인이 없습니다: {args.index} (먼저 build.py를 실행했는지 확인)", file=sys.stderr)
        return 2

    try:
        consumer = load_consumer_list(args.consumer, fmt=args.format, kt_var_name=args.kt_var_name)
    except ValueError as e:
        print(f"오류: 소비자 목록 파싱 실패: {e}", file=sys.stderr)
        return 2

    index_doc = json.loads(args.index.read_text(encoding="utf-8"))
    overlaps = analyze(consumer, index_doc)

    if args.json:
        print(json.dumps([overlap_to_dict(ov) for ov in overlaps], ensure_ascii=False, indent=2))
    else:
        print_report(overlaps)

    blocking = sum(1 for ov in overlaps if ov.verdict in ("CONFLICT", "DIVERGENT"))
    return 1 if blocking else 0


if __name__ == "__main__":
    raise SystemExit(main())
