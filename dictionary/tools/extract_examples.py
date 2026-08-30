#!/usr/bin/env python3
"""골든 코퍼스에서 사전 표제어의 few-shot 예문 후보를 뽑는다.

측정(팀장 지시)에서 판단이 끝났다: 후보 A(골든 코퍼스 문장)가 후보 B(krdict
view API 예문)보다 이 제품에 맞다 — B는 실제로 작동하지만 일반 사전 뜻(예:
`급여`→"월급")을 준다. 이 스크립트는 후보 A를 실제로 추출한다.

**적재하지 않는다.** CSV로 결과를 낼 뿐, `dist/`·`data/raw/`는 건드리지
않는다 — 표본을 보고 사람이 판단한 뒤 적재는 별도로 지시받아 진행한다.

## §7.2.2 계약: 예문은 전략을 지켜야 한다

`easydict.build._finalize_examples()`를 **그대로** 통과시킨다(우회하지
않음) — `keep` 전략 엔트리는 예문이 만들어지지 않고, `gloss`는 원어를
보존한 채 뒤에 설명을 붙이고, `substitute`는 `substitute_with_josa()`로
조사까지 교정해 치환한다. 이 스크립트가 직접 문자열을 조작하지 않는다.

## 노이즈 필터 (실측 30건 표본에서 발견한 두 가지 실패 유형에 대한 대응)

1. **길이 상한** — 표 파싱 잔재는 압도적으로 길다.
2. **선두 목록/각주 기호** — `-`·`○`·`∙`·`※`·원문자 번호로 시작하면 제외
   (`normalize._CIRCLED_NUM_RE` 재사용 — 새로 안 만듦).
3. **공백 밀도 하한** — PDF 표 파싱이 깨지면 띄어쓰기가 통째로 사라진다
   (실측 사례: `되었다.제도개선이후평가유예제도를활용하는...`). 정상적인
   한국어 문장은 일정 간격으로 공백이 나온다는 게 관찰적 근거다.
4. `_finalize_examples()`의 "term 리터럴 포함" 체크가 1차 방어선으로 그대로
   유지된다 — 이 스크립트는 그 앞단(어떤 문장을 "before_text" 후보로 줄지)
   만 담당한다.

## 한 표제어에 문장이 여럿일 때 — 결정적 선택 기준

**문서 등장 순서에 의존하지 않는다.** 이번 세션에서 승자 결정 결함이
정확히 "적재 순서·처리 순서에 결과가 좌우된다"는 유형이었다(DESIGN.md
§5.5(1)). 그래서 후보 문장들을 **내용만으로** 점수 매겨 가장 높은 것을
고르고, 동점이면 문자열 자체의 사전식 정렬로 마지막 동점을 깬다(완전히
결정적) — 어떤 순서로 문서를 읽어도, 어떤 순서로 후보를 모아도 항상 같은
문장이 뽑힌다. 점수 기준(`_score_sentence`): 목표 길이(30~60자)에 가까울수록,
한국어 종결어미(`다./요./니다.`)로 끝날수록, 공백 밀도가 자연스러울수록
높은 점수.

재사용: `tools.extract_gaps.load_docs`(문서 로딩), `easydict.lookup.EasyDict`
(매칭), `easydict.normalize._CIRCLED_NUM_RE`(원문자 목록), `easydict.build.
_finalize_examples`(전략별 후처리). 정규화·매칭 로직을 새로 안 짰다.

네트워크 호출 없음. `easy-doc`·`dist/easy_dict.sqlite3`는 읽기만 한다.
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from easydict.lookup import EasyDict  # noqa: E402
from easydict.models import Entry, Example  # noqa: E402
from easydict.normalize import _CIRCLED_NUM_RE  # noqa: E402
from easydict.build import _finalize_examples  # noqa: E402

import extract_gaps  # noqa: E402  (같은 tools/ 디렉터리, 재사용)

DEFAULT_GOLDEN_DIR = extract_gaps.DEFAULT_GOLDEN_DIR
DEFAULT_DB_PATH = extract_gaps.DEFAULT_DB_PATH

# ---------------------------------------------------------------------------
# 노이즈 필터
# ---------------------------------------------------------------------------
MAX_SENTENCE_LEN = 100  # 표본으로 조정 가능(팀장 지시). 실측 근거는 README 참고.
MIN_SENTENCE_LEN = 6  # "1시간 소요" 같은 극단적으로 짧은 표 조각도 배제

_SENT_SPLIT_RE = re.compile(r"(?<=[.?!])\s+|\n+")
_LEADING_MARKER_RE = re.compile(r"^[-○∙※]|^[①-⑳]")
_SENT_ENDING_RE = re.compile(r"(다|요|니다|음|함)[.]?$")

# 공백 밀도: 정상적인 한글 문장은 어절마다 공백이 있다. 표 파싱이 깨지면
# 공백이 통째로 사라진다(실측: '되었다.제도개선이후평가유예제도를...').
# 15자당 공백 1개 미만이면 배제한다(20자 미만 문장은 우연히 낮게 나올 수
# 있어 검사 대상에서 뺀다).
MIN_WHITESPACE_RATIO = 1 / 15


def split_sentences(text: str) -> list[tuple[int, int, str]]:
    spans = []
    start = 0
    for m in _SENT_SPLIT_RE.finditer(text):
        end = m.start()
        if end > start:
            spans.append((start, end, text[start:end].strip()))
        start = m.end()
    if start < len(text):
        spans.append((start, len(text), text[start:].strip()))
    return spans


def passes_noise_filter(sentence: str) -> bool:
    n = len(sentence)
    if not (MIN_SENTENCE_LEN <= n <= MAX_SENTENCE_LEN):
        return False
    if _LEADING_MARKER_RE.match(sentence):
        return False
    if _CIRCLED_NUM_RE.search(sentence[:3]):  # 문두 근처 원문자도 목록 신호로 본다
        return False
    if n >= 20:
        space_ratio = sentence.count(" ") / n
        if space_ratio < MIN_WHITESPACE_RATIO:
            return False
    return True


def _score_sentence(sentence: str) -> tuple[float, str]:
    """내용만으로 계산되는 점수(문서 순서 무관, 완전히 결정적).

    (점수, 문장 자체)를 튜플로 반환해 `max()`가 동점이면 문자열 사전식
    정렬로 마지막까지 결정적으로 고르게 한다.
    """
    n = len(sentence)
    # 목표 길이 30~60자에 가까울수록 높은 점수(0~1).
    ideal_center = 45.0
    length_score = max(0.0, 1.0 - abs(n - ideal_center) / ideal_center)
    ending_score = 1.0 if _SENT_ENDING_RE.search(sentence) else 0.0
    space_ratio = sentence.count(" ") / n if n else 0.0
    space_score = min(space_ratio / 0.15, 1.0)  # 0.15 근처를 만점으로 본다
    score = length_score * 2 + ending_score * 2 + space_score
    return (score, sentence)


def collect_candidates(
    docs: list[dict], ed: EasyDict,
) -> dict[int, list[tuple[str, str]]]:
    """entry_id -> [(문장, doc_id), ...] (노이즈 필터 통과분만)."""
    by_entry: dict[int, list[tuple[str, str]]] = {}
    for doc in docs:
        text = doc["text"]
        matches = ed.find_all(text)
        if not matches:
            continue
        sentences = split_sentences(text)
        for m in matches:
            for s_start, s_end, s_text in sentences:
                if s_start <= m.start < s_end:
                    if passes_noise_filter(s_text):
                        by_entry.setdefault(m.entry_id, []).append((s_text, doc["id"]))
                    break
    return by_entry


def build_rows(ed: EasyDict, by_entry: dict[int, list[tuple[str, str]]]) -> list[dict]:
    rows: list[dict] = []
    for entry_id, candidates in by_entry.items():
        e = ed._entries[entry_id]
        term, easy_term, strategy = e["t"], e["e"], e["s"]

        if len(term) < 2:
            # 1음절 표제어 제외 — 실측 3건(자/한/소) 전부 표제어가 다른 말
            # 속에 우연히 낀 부분 문자열로 매치돼('대상자'→'대상사람') 문장을
            # 망가뜨렸다. lookup.py 경계 판정이 정확히 이런 걸 막는 부분인데
            # (§DESIGN 5.5(3), 다른 레인이 지금 마무리 중) 여기서는 그 판정을
            # 신뢰할 수 있을 때까지 보수적으로 1음절 자체를 배제한다. 2음절
            # 이상은 그런 사례가 없어서(199건 중 0건) 그대로 둔다 — 표본
            # 보고에 구체적 근거를 남긴다.
            continue

        # 결정적 선택: 내용 기반 점수 최댓값(동점이면 문자열 사전식).
        # key가 (점수, 문장) 튜플이라 점수가 같으면 문장 자체의 사전식
        # 비교로 넘어가 max()가 항상 같은 승자를 고른다 — 문서 순서 무관.
        best_sentence, best_doc = max(candidates, key=lambda sd: _score_sentence(sd[0]))

        entry = Entry(term=term, easy_term=easy_term, replace_strategy=strategy)
        entry.examples.append(Example(before_text=best_sentence, after_text=best_sentence))
        _finalize_examples(entry)  # §7.2.2 계약 그대로 통과 — 우회하지 않는다

        if not entry.examples:
            # keep이거나(예문 자체를 안 만듦), term이 문자 그대로 없어서
            # _finalize_examples가 버렸다(1차 방어선). 조용히 넘어간다.
            continue

        ex = entry.examples[0]
        rows.append({
            "term": term,
            "easy_term": easy_term,
            "replace_strategy": strategy,
            "before_text": ex.before_text,
            "after_text": ex.after_text,
            "source_doc": best_doc,
            "candidate_count": len(candidates),
            "selection_score": round(_score_sentence(best_sentence)[0], 3),
        })
    rows.sort(key=lambda r: (-r["candidate_count"], r["term"]))  # 정렬도 결정적
    return rows


FIELDNAMES = [
    "term", "easy_term", "replace_strategy", "before_text", "after_text",
    "source_doc", "candidate_count", "selection_score",
]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--golden-dir", default=DEFAULT_GOLDEN_DIR, help="easy-doc golden 코퍼스 경로(읽기 전용)")
    p.add_argument("--db", default=DEFAULT_DB_PATH, help="dist/easy_dict.sqlite3 경로(읽기 전용)")
    p.add_argument("--output", type=Path, default=None, help="출력 CSV 경로 (기본: stdout)")
    p.add_argument("--top", type=int, default=30, help="요약 출력 상위 N건 (기본 30)")
    args = p.parse_args(argv)

    docs = extract_gaps.load_docs(args.golden_dir)
    print(f"[info] golden docs: {len(docs)}", file=sys.stderr)

    ed = EasyDict.from_sqlite(args.db)
    total_entries = len(ed._entries)
    print(f"[info] dict entries: {total_entries}", file=sys.stderr)

    by_entry = collect_candidates(docs, ed)
    print(f"[info] 노이즈 필터 통과 후 후보 문장 있는 엔트리: {len(by_entry)}", file=sys.stderr)

    rows = build_rows(ed, by_entry)
    print(
        f"[RESULT] 예문 확보 엔트리: {len(rows)} / {total_entries} "
        f"({len(rows) / total_entries * 100:.1f}%)",
        file=sys.stderr,
    )
    by_strategy: dict[str, int] = {}
    for r in rows:
        by_strategy[r["replace_strategy"]] = by_strategy.get(r["replace_strategy"], 0) + 1
    print(f"[RESULT] 전략별: {by_strategy}", file=sys.stderr)

    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=FIELDNAMES)
            w.writeheader()
            w.writerows(rows)
        print(f"[info] -> {args.output}", file=sys.stderr)
    else:
        w = csv.DictWriter(sys.stdout, fieldnames=FIELDNAMES)
        w.writeheader()
        w.writerows(rows)

    if args.top > 0:
        print(f"\n=== 표본 {min(args.top, len(rows))}건 ===", file=sys.stderr)
        for r in rows[: args.top]:
            print(
                f"[{r['replace_strategy']:10s}] {r['term']} -> {r['easy_term']}\n"
                f"  전: {r['before_text']}\n"
                f"  후: {r['after_text']}",
                file=sys.stderr,
            )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
