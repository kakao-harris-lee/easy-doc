#!/usr/bin/env python3
"""층위 2 — 코퍼스 통과 검사 (`docs/inspection-plan.md` Phase 2).

**이 검사가 있는 이유**: 2026-08-29 세션에서 나온 결함 대부분이 "빌드도 되고
테스트 127개도 다 통과하는데 실제 문서를 넣으면 원문이 깨지는" 종류였다.

```
CCTV를 설치합니다      ->  C전류 변성기V를 설치합니다   (CT가 CCTV 안에서 매칭)
현금으로 수령하실       ->  현금으로 받음하실            (활용형에 원형을 꽂음)
가. 신청 방법          ->  가(더하다). 신청 방법        (어간이 열거 기호를 삼킴)
```

셋 다 사람이 예문 표본을 눈으로 보다가 우연히 찾았다. 층위 1(산출물 불변식,
`tools/check_invariants.py`)은 구조적 모순만 보므로 이런 것을 원리상 못 본다 —
**사전이 스스로 모순되지 않아도 문서를 만나면 깨질 수 있다.**

## 검사 항목

1. **경계 위반** — 매칭된 표면형의 양옆이 같은 문자 종류인가. 한글 표제어는
   한글 음절, 로마자·숫자 표제어는 로마자·숫자가 붙어 있으면 위반이다
   (`lookup._left_boundary_ok`/`_boundary_ok`가 막아야 하는 것을 산출물에서
   다시 확인한다 — 규칙을 재구현하지 않고 **결과를 검사**한다).
2. **원문 파괴** — `annotate()` 출력에서 사라진 글자가 전부 "매칭된 표면형
   전체"에 해당하는가. 토큰 일부만 잘려 나가면 위반이다.
3. **활용형 비문** — `is_inflected` 매칭에 `substitute`가 그대로 적용됐는가.
   §6.6 계약상 활용형에는 원형을 꽂지 않고 원어를 보존해야 한다.
4. **상충 지침** — `build_prompt_context()`가 같은 표제어를 서로 다른 지시
   구역(바꿔 쓰세요 / 괄호로 설명 / 절대 바꾸지 마세요)에 동시에 싣는가.

## 기준선

지금 남아 있는 알려진 사례(`조제`가 `제13조제3항`에서 매칭되는 형태소 경계
문제 등)를 0으로 만들 수 없다. 그래서 **0을 요구하지 않고 증가를 막는다.**
기준선 파일(`tools/audit_corpus.baseline.json`)은 **커밋한다** — 갭 리스트를
커밋하지 않는 규칙(§5.6)과 반대인데, 기준선은 재생성 대상이 아니라 **비교
대상**이라 고정돼야 의미가 있기 때문이다.

`--update-baseline`으로 갱신한다. 갱신은 **줄어들 때만** 무비판적으로 하고,
늘어날 때는 왜 늘었는지 확인하고 나서 해야 한다.

## 한계

- **골든 코퍼스 57건에만 답한다.** 여기 안 나오는 표현은 검사되지 않는다.
- 의미가 맞는지는 안 본다(층위 3 — `tools/detect_homonym_risk.py`).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(_ROOT / "src"))
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from easydict.lookup import EasyDict  # noqa: E402
from easydict.normalize import correct_josa_form, find_josa_after  # noqa: E402
from tools.extract_gaps import load_docs  # noqa: E402

DEFAULT_GOLDEN = "../data/golden"
DEFAULT_INDEX = str(_ROOT / "dist" / "easy_dict.index.json")
DEFAULT_BASELINE = str(_ROOT / "tools" / "audit_corpus.baseline.json")

_HANGUL = re.compile(r"[가-힣]")
_LATIN_DIGIT = re.compile(r"[A-Za-z0-9]")

# 프롬프트 구역 헤더 -> 짧은 이름. build_prompt_context() 출력(§7.2)을 파싱한다.
_SECTION_HEADS = {
    "### 바꿔 쓰세요": "substitute",
    "### 원래 말을 남기고 괄호로 설명하세요 (지우면 안 됩니다)": "gloss",
    "### 절대 바꾸지 마세요": "keep",
}


def _flank_class(ch: str) -> str | None:
    """경계 판정용 문자 종류. 한글/로마자·숫자만 구분하고 나머지는 None."""
    if _HANGUL.match(ch):
        return "hangul"
    if _LATIN_DIGIT.match(ch):
        return "latin"
    return None


def check_boundaries(doc, ed, matches) -> list[dict]:
    """매칭이 더 큰 같은-종류 토큰의 **일부**로 잘려 들어갔는지 독립 판정한다.

    ### 이 함수를 두 번 고쳐 쓴 이유 (남겨 둘 값이 있다)

    1차: 양옆 문자 종류만 봤다 -> `소득인정액이`의 `이`(조사)까지 위반으로 잡아
       **424건 오탐**. 한국어 오른쪽 경계는 조사 연쇄를 봐야 한다(§6.7 (3)).
    2차: 그래서 `lookup._boundary_ok`를 그대로 호출했다 -> **검사가 공허해졌다.**
       `find_all()`은 이미 그 함수를 통과한 매칭만 돌려주므로 재검사는 **절대
       실패할 수 없다.** 0건이 "안전"이 아니라 "아무것도 안 봄"이었다.

    그래서 **독립적으로 판정 가능한 부분만** 검사한다.

    - **왼쪽 경계**: 한글 표제어 앞에 한글 음절이 붙어 있으면 위반. 조사는
      뒤에만 붙으므로 왼쪽은 모호성이 없다(§5.5(3)의 `대상자` 속 `자` 결함).
    - **양쪽 로마자·숫자**: 로마자·숫자로 시작/끝나는 표제어의 해당 쪽에
      로마자·숫자가 붙어 있으면 위반(`CCTV` 속 `CT`).

    오른쪽 한글 경계는 조사 판정이 필요해 독립 구현이 불가능하므로 **일부러
    안 본다** — 못 보는 것을 안 보는 척하지 않는다(아래 요약에 명시된다).
    """
    text = doc["text"]
    out = []
    for m in matches:
        left = text[m.start - 1] if m.start > 0 else ""
        right = text[m.end] if m.end < len(text) else ""
        head, tail = _flank_class(m.surface[0]), _flank_class(m.surface[-1])
        bad = None
        if head == "hangul" and left and _flank_class(left) == "hangul":
            bad = "왼쪽에 한글이 붙음"
        elif head == "latin" and left and _flank_class(left) == "latin":
            bad = "왼쪽에 로마자·숫자가 붙음"
        elif tail == "latin" and right and _flank_class(right) == "latin":
            bad = "오른쪽에 로마자·숫자가 붙음"
        if bad is None:
            continue
        out.append({
            "doc": doc["id"], "surface": m.surface, "term": m.term,
            "easy_term": m.easy_term, "strategy": m.strategy, "why": bad,
            "context": text[max(0, m.start - 12):m.end + 12].replace("\n", " "),
        })
    return out


def check_josa_agreement(doc, ed, matches) -> list[dict]:
    """치환 뒤 조사 이형태가 새 단어에 맞게 교정됐는지 확인한다 (§6.7 (1)).

    원래 이 자리는 "원문 파괴" 검사였는데, 만들어 돌려 보니 **경계 검사와
    완전히 중복**이었다 — 경계가 옳으면 토큰은 쪼개지지 않는다. 게다가
    `당월에 -> 그달에`처럼 조사가 남는 정상 동작을 18건 오탐으로 잡았다.
    중복이면서 틀린 검사를 두느니 실제로 다른 것을 보는 검사로 바꿨다.

    실제 결함 사례: `급여는` -> `지원금는`(받침 판정 없이 조사를 그대로 옮김).
    `normalize.find_josa_after`/`correct_josa_form`을 **그대로 재사용**한다.
    """
    text = doc["text"]
    after = ed.annotate(text)
    out = []
    for m in matches:
        if m.strategy != "substitute" or m.is_inflected or not m.easy_term:
            continue
        hit = find_josa_after(text, m.end)
        if hit is None:
            continue
        original_josa, pair = hit
        wanted = correct_josa_form(pair, m.easy_term[-1])
        if wanted is None or f"{m.easy_term}{wanted}" in after:
            continue
        out.append({
            "doc": doc["id"], "surface": m.surface, "easy_term": m.easy_term,
            "josa": original_josa, "expected": f"{m.easy_term}{wanted}",
            "context": text[max(0, m.start - 12):m.end + 12].replace("\n", " "),
        })
    return out


def check_inflected_substitution(doc, ed, matches) -> list[dict]:
    """활용형 매칭에 원형이 꽂혔는지 확인한다 (§6.6).

    `is_inflected` 매칭은 `annotate()`가 `표면형(대치어)` 형태로 원어를
    보존해야 한다. 표면형이 사라지고 대치어만 남았다면 비문 위험이다.
    """
    text = doc["text"]
    after = ed.annotate(text)
    out = []
    for m in matches:
        if not m.is_inflected or m.strategy != "substitute":
            continue
        expected = f"{m.surface}({m.easy_term})"
        if expected in after:
            continue
        if m.surface not in after and m.easy_term and m.easy_term in after:
            out.append({
                "doc": doc["id"], "surface": m.surface, "term": m.term,
                "easy_term": m.easy_term,
                "context": text[max(0, m.start - 12):m.end + 12].replace("\n", " "),
            })
    return out


def check_conflicting_guidance(doc, ed) -> list[dict]:
    """같은 표제어가 프롬프트의 서로 다른 지시 구역에 동시에 실리는지 본다."""
    ctx = ed.build_prompt_context(doc["text"])
    section = None
    seen: dict[str, set[str]] = {}
    for line in ctx.splitlines():
        s = line.strip()
        if s in _SECTION_HEADS:
            section = _SECTION_HEADS[s]
            continue
        if not section or not s.startswith("- "):
            continue
        head = s[2:].split("→")[0].split("(")[0].strip()
        if head:
            seen.setdefault(head, set()).add(section)
    return [
        {"doc": doc["id"], "term": t, "sections": sorted(secs)}
        for t, secs in sorted(seen.items()) if len(secs) > 1
    ]


CHECKS = ("boundary", "josa", "inflected", "conflict")


def run(golden_dir: str, index_path: str) -> dict[str, list[dict]]:
    ed = EasyDict.from_index_json(index_path)
    docs = load_docs(golden_dir)
    if not docs:
        raise SystemExit(f"골든 문서를 못 찾았다: {golden_dir}")
    found: dict[str, list[dict]] = {k: [] for k in CHECKS}
    for doc in docs:
        matches = ed.find_all(doc["text"])
        found["boundary"] += check_boundaries(doc, ed, matches)
        found["josa"] += check_josa_agreement(doc, ed, matches)
        found["inflected"] += check_inflected_substitution(doc, ed, matches)
        found["conflict"] += check_conflicting_guidance(doc, ed)
    return found


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="층위 2 코퍼스 통과 검사")
    p.add_argument("--golden-dir", default=DEFAULT_GOLDEN)
    p.add_argument("--index", default=DEFAULT_INDEX)
    p.add_argument("--baseline", default=DEFAULT_BASELINE)
    p.add_argument("--update-baseline", action="store_true",
                   help="현재 결과를 기준선으로 덮어쓴다(늘었을 때는 이유를 확인하고 쓸 것)")
    p.add_argument("--show", type=int, default=5, help="항목별로 보여줄 표본 수")
    args = p.parse_args(argv)

    found = run(args.golden_dir, args.index)
    counts = {k: len(v) for k, v in found.items()}

    print("=" * 64)
    print("층위 2 코퍼스 통과 검사 (docs/inspection-plan.md Phase 2)")
    print("=" * 64)

    bl_path = Path(args.baseline)
    baseline = json.loads(bl_path.read_text(encoding="utf-8")) if bl_path.exists() else None

    if args.update_baseline:
        bl_path.write_text(
            json.dumps({"counts": counts}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        print(f"기준선 갱신: {counts}")
        return 0

    failed = False
    for k in CHECKS:
        base = baseline["counts"].get(k) if baseline else None
        now = counts[k]
        if base is None:
            mark = "(기준선 없음)"
        elif now > base:
            mark = f"증가 {base} -> {now}  ** 실패 **"
            failed = True
        elif now < base:
            mark = f"감소 {base} -> {now}  (기준선 갱신 권장)"
        else:
            mark = f"기준선 유지 ({base})"
        print(f"  {k:12} {now:>4}건  {mark}")
        for item in found[k][:args.show]:
            print(f"      · {item}")

    print("-" * 64)
    print("[한계] 골든 코퍼스 57건에만 답한다 — 여기 안 나오는 표현은 검사되지 않는다")
    print("[한계] 의미가 맞는지는 안 본다 — 층위 3(tools/detect_homonym_risk.py)")
    print("=" * 64)
    if baseline is None:
        print("기준선이 없다. --update-baseline 으로 만들어라.")
        return 0
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
