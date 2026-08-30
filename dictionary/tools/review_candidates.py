#!/usr/bin/env python3
"""갭 리스트(§5.6) x 다듬은말 후보 풀(§5.7)을 교차해 사람이 판정할 검토표를 만든다.

DESIGN.md §5.7의 계약: "원천으로 쓰는 것과 후보 풀로 쓰는 것의 차이는 검수가
앞에 있느냐 뒤에 있느냐다." 그래서 이 도구는 **사전에 아무것도 넣지 않는다.**
`data/raw/`·`dist/`는 건드리지 않고, 사람이 열어서 판정=O/X를 치면 그대로
`welfare_seed` 형식 CSV로 넘어갈 수 있는 검토표만 만든다.

입력:
  - 갭 리스트: `tools/extract_gaps.py`를 그대로 재사용해 즉석에서 계산한다
    (골든 코퍼스 문서 x 현재 사전 `dist/easy_dict.sqlite3`). 별도 실행 없이
    이 스크립트 하나로 끝난다.
  - 후보 풀: `~/korean-refined-words/korean_refined_words.csv` (읽기 전용,
    18,340건, §5.7에서 "적재 대신 후보 풀로 남기기로" 한 그 데이터).

출력: CSV(기본 stdout). 커밋하지 않는다(§5.6과 같은 이유 — 사전이 자라면
검토표도 낡는다. 재생성이 싸면 파일로 묻어두지 않는다).

재사용 원칙(§3.4.2, "정규화 키와 표면형을 혼동한다"가 이 저장소에서 제일 자주
터진 실수): 정규화·표제어 분해·어종 판정 로직을 새로 짜지 않는다.
  - `easydict.normalize.normalize_key`         : 매칭 키
  - `easydict.build._split_term_headwords`,
    `easydict.build._expand_multi_headword_variants` : source_term 분해
    (콤마·슬래시 이형태, §요청 slash 패치는 미적용 상태 그대로 재사용 —
    이 스크립트가 그 패치를 대신 적용하는 게 아니다)
  - `easydict.build._classify_origin`          : 한자/외래어 판정(대시
    자리표시자 수정판)
  - `easydict.build._clean_definition_with_examples` : meaning_examples 정리
  - `tools.extract_gaps.load_docs`/`extract_gaps` : 갭 리스트 산출

네트워크 호출 없음. 표준 라이브러리 + 이 저장소 모듈만 쓴다.
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

from easydict.normalize import normalize_key  # noqa: E402
from easydict import build as ed_build  # noqa: E402

import extract_gaps  # noqa: E402  (같은 tools/ 디렉터리, 재사용)

DEFAULT_POOL_PATH = str(Path.home() / "korean-refined-words" / "korean_refined_words.csv")

# ---------------------------------------------------------------------------
# §5.7 규칙 4: 어원(original_term)에 한자와 로마자 주석이 함께 있으면
# `_classify_origin()`의 loanword 판정이 부정확할 수 있다(예: '壓挫症候群
# (crush syndrome(영))' — 한자 압좌증후군에 영어 번역 gloss가 붙은 것이지
# 그 단어 자체가 외래어라서가 아니다). 실데이터에 19건 확인됨(별도 보고) —
# 이 스크립트는 고치지 않고 검토표에 경고만 얹는다("직접 확인" 신호).
_HANJA_RE = re.compile(r"[一-鿿]")
_PAREN_LATIN_RE = re.compile(r"\([A-Za-z][^)]*\)")


def _origin_annotation_ambiguous(original_term: str) -> bool:
    if not original_term:
        return False
    return bool(_HANJA_RE.search(original_term) and _PAREN_LATIN_RE.search(original_term))


# ---------------------------------------------------------------------------
# §5.7 규칙 3: 1~2음절 표제어는 동형이의어 오염 위험이 크다(전체의 34%가
# 이 구간이었고, `substitute`로 잡힌 것 중 다수가 여기서 나왔다).
def _syllable_risk(term: str) -> str:
    n = len(term)
    if n <= 2:
        return "높음(1~2음절)"
    if n == 3:
        return "보통(3음절)"
    return ""


# ---------------------------------------------------------------------------
# §5.7 규칙 2: 대치어가 표제어보다 쉬운지는 완벽히 자동 판정할 수 없다.
# 관찰 가능한 프록시 하나만 쓴다 — 순화어가 표제어보다 음절이 길어지면
# "더 쉬워졌다"고 보기 어렵다는 신호로만 표시한다(`제공하다`(4)→`바치다`(3)
# 처럼 짧아져도 더 어려워지는 반례가 있으므로 이건 "위험 후보"를 놓치지
# 않으려는 참고 신호이지 확정 판정이 아니다).
#
# 명시적으로 못 하는 것 — "고유어 전환 여부": `기중기`처럼 한자어가 한글로
# *음차*됐을 뿐 고유어로 안 바뀐 경우, 문자열만 봐서는 한자 원문이 아예
# 안 남아 있어(한글로만 표기됨) 고유어인지 한자어의 한글 표기인지 구분할
# 방법이 없다 — 국어사전 어원 데이터베이스 같은 외부 자원이 있어야 하는데
# 이 저장소는 네트워크 호출도 외부 의존성도 금지라 여기서는 판정하지 않는다.
# 억지로 만들지 않고 이 사실 자체를 검토표 컬럼 설명(README)에 남긴다.
def _length_increase_signal(source_term: str, refined_term: str) -> str:
    if not source_term or not refined_term:
        return ""
    diff = len(refined_term) - len(source_term)
    if diff > 0:
        return f"순화어가 {diff}음절 더 김(더 쉬워졌는지 직접 확인)"
    return ""


# ---------------------------------------------------------------------------
# §5.7 규칙 4: "코퍼스 고빈도 매칭이 곧 가치는 아니다" — 문서 등장이 많을수록
# 오분류됐을 때 피해 규모가 크다는 뜻으로만 쓴다.
def _frequency_risk_label(doc_freq: int) -> str:
    if doc_freq >= 5:
        return f"높음(문서 {doc_freq}건 — 틀리면 크게 틀린다)"
    if doc_freq >= 2:
        return f"보통(문서 {doc_freq}건)"
    return f"낮음(문서 {doc_freq}건)"


def build_pool_index(pool_path: Path) -> dict[str, list[dict]]:
    """후보 풀 CSV를 `normalize_key(표제어) -> [행 정보, ...]` 로 색인한다.

    표제어 분해는 build.py의 기존(패치 미적용) 로직을 그대로 쓴다 — 콤마·
    슬래시 이형태 전부를 별도 키로도 색인해서, 갭 용어가 이형태 표기로
    나와도 찾을 수 있게 한다. 대표 표제어(canonical)는 항상 첫 번째
    headword다.
    """
    index: dict[str, list[dict]] = {}
    with pool_path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for lineno, row in enumerate(reader, start=2):
            source_term_raw = (row.get("source_term") or "").strip()
            if not source_term_raw:
                continue
            headwords, term_hanja_from_term, is_loanword_from_term = ed_build._split_term_headwords(
                source_term_raw
            )
            headwords = ed_build._expand_multi_headword_variants(headwords)
            if not headwords:
                continue
            canonical = headwords[0][0].strip()
            if not canonical:
                continue

            original_term = (row.get("original_term") or "").strip()
            origin_hanja, origin_is_loanword = (
                ed_build._classify_origin(original_term) if original_term else (None, False)
            )
            term_hanja = term_hanja_from_term or origin_hanja
            is_loanword = is_loanword_from_term or origin_is_loanword

            definition_raw = (row.get("meaning_examples") or "").strip()
            definition = ed_build._clean_definition_with_examples(definition_raw) if definition_raw else ""

            record = {
                "lineno": lineno,
                "source_term": canonical,
                "source_term_raw": source_term_raw,
                "refined_term": (row.get("refined_term") or "").strip(),
                "definition": definition,
                "notes": (row.get("notes") or "").strip(),
                "original_term": original_term,
                "detail_url": (row.get("detail_url") or "").strip(),
                "term_hanja": term_hanja or "",
                "is_loanword": is_loanword,
            }

            seen_keys: set[str] = set()
            for surface, _is_typo in headwords:
                key = normalize_key(surface)
                if not key or key in seen_keys:
                    continue
                seen_keys.add(key)
                index.setdefault(key, []).append(record)
    return index


def build_review_rows(
    gap_rows: list[dict],
    pool_index: dict[str, list[dict]],
) -> list[dict]:
    review_rows: list[dict] = []
    for gap in gap_rows:
        gap_term = gap["term"]
        key = normalize_key(gap_term)
        candidates = pool_index.get(key)
        if not candidates:
            continue
        for cand in candidates:
            review_rows.append({
                "gap_term": gap_term,
                "doc_freq": gap["doc_freq"],
                "total_freq": gap["total_freq"],
                "gap_context": gap["sample_context"],
                "risk_syllable": _syllable_risk(cand["source_term"]),
                "risk_frequency": _frequency_risk_label(gap["doc_freq"]),
                "risk_harder_signal": _length_increase_signal(cand["source_term"], cand["refined_term"]),
                "risk_origin_ambiguous": (
                    "확인 필요(한자+로마자 주석 혼재)"
                    if _origin_annotation_ambiguous(cand["original_term"])
                    else ""
                ),
                "candidate_hanja": cand["term_hanja"],
                "candidate_is_loanword": "loanword" if cand["is_loanword"] else "",
                "candidate_source_line": cand["lineno"],
                "판정(O/X)": "",
                "판정사유": "",
                # --- 여기부터 welfare_seed 컬럼 순서 그대로 (§요청: "그대로 넘어갈 수 있는 모양") ---
                "원어": cand["source_term"],
                "순화어": cand["refined_term"],
                "분야": "",
                "뜻": cand["definition"],
                "예문": "",
                "주의": cand["notes"],
                "출처": cand["detail_url"],
                "replace_strategy": "",
                "risk_level": "",
                "status": "",
            })
        # 갭 용어 하나에 후보가 여럿이면(동형이의어 포함) 문서 등장 빈도가
        # 높은 순으로 이미 정렬돼 있으니(extract_gaps 정렬) 그대로 둔다.
    return review_rows


FIELDNAMES = [
    "gap_term", "doc_freq", "total_freq", "gap_context",
    "risk_syllable", "risk_frequency", "risk_harder_signal", "risk_origin_ambiguous",
    "candidate_hanja", "candidate_is_loanword", "candidate_source_line",
    "판정(O/X)", "판정사유",
    "원어", "순화어", "분야", "뜻", "예문", "주의", "출처",
    "replace_strategy", "risk_level", "status",
]


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--golden-dir", default=extract_gaps.DEFAULT_GOLDEN_DIR, help="easy-doc golden 코퍼스 경로(읽기 전용)")
    p.add_argument("--db", default=extract_gaps.DEFAULT_DB_PATH, help="dist/easy_dict.sqlite3 경로(읽기 전용)")
    p.add_argument("--pool", default=DEFAULT_POOL_PATH, help="다듬은말 후보 풀 CSV 경로(읽기 전용)")
    p.add_argument("--output", type=Path, default=None, help="출력 CSV 경로 (기본: stdout)")
    p.add_argument("--top", type=int, default=0, help="갭 리스트 상위 N건만 대상으로(0=전부, 기본)")
    args = p.parse_args(argv)

    pool_path = Path(args.pool).expanduser()
    if not pool_path.exists():
        print(f"오류: 후보 풀 파일이 없습니다: {pool_path}", file=sys.stderr)
        return 2

    docs = extract_gaps.load_docs(args.golden_dir)
    gap_rows, gap_stats = extract_gaps.extract_gaps(docs, args.db)
    if args.top > 0:
        gap_rows = gap_rows[: args.top]
    print(f"[info] 갭 용어 {len(gap_rows)}건 (사전 {gap_stats['dict_entries']}건 기준)", file=sys.stderr)

    pool_index = build_pool_index(pool_path)
    print(f"[info] 후보 풀 표제어 색인 {len(pool_index)}개 키 (원본 18,340행 기준 파일: {pool_path})", file=sys.stderr)

    review_rows = build_review_rows(gap_rows, pool_index)
    matched_gap_terms = len({r["gap_term"] for r in review_rows})
    print(
        f"[info] 대치어 후보 있는 갭 용어: {matched_gap_terms} / {len(gap_rows)}건, "
        f"검토표 행수(동형이의어 포함): {len(review_rows)}",
        file=sys.stderr,
    )
    ambiguous = sum(1 for r in review_rows if r["risk_origin_ambiguous"])
    if ambiguous:
        print(f"[경고] 어원 한자+로마자 주석 혼재로 어종 판정이 불확실한 후보: {ambiguous}건 — 직접 확인 필요", file=sys.stderr)

    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=FIELDNAMES)
            w.writeheader()
            w.writerows(review_rows)
        print(f"[info] 검토표 -> {args.output}", file=sys.stderr)
    else:
        w = csv.DictWriter(sys.stdout, fieldnames=FIELDNAMES)
        w.writeheader()
        w.writerows(review_rows)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
