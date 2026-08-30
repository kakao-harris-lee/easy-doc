#!/usr/bin/env python3
"""`dist/`의 `substitute` 전략 엔트리와 krdict(한국어기초사전) 검색 API의
`sup_no`(동형어 번호)를 대조해 "표제어는 맞는데 문서에서는 더 흔한 다른
뜻으로 쓰일 위험"을 찾는 **검수표**를 만든다.

**이 스크립트는 자동 판정 도구가 아니라 사람이 볼 목록을 만드는 도구다.**
플래그된 표제어를 자동으로 걸러내거나 사전을 고치지 않는다 — `dist/`도
`data/raw/`도 이 스크립트가 쓴 적이 없어야 한다.

## 배경 — 왜 이 방법인가 (스크래치 `homonym-check/`의 실패 기록 참고)

먼저 "다듬은말 풀(`~/korean-refined-words/`)과 우리 원천의 한자를 대조"하는
방법을 시험했는데 **재현율 0%**였다: 알려진 위험 5건(수리/가설/거치/한/소)
전부 놓쳤다. 이유는 구조적이다 — 그 풀과 우리 원천(nikl 행정용어) 둘 다
"국립국어원이 정비 대상으로 삼을 만한 전문·행정 용어" 목록이라, `修理`처럼
이미 쉬운 일상어인 뜻은 **정비할 필요가 없어서 어느 목록에도 없다.** 두
전문용어 목록끼리 교차검증하면 "전문용어가 아닌 흔한 뜻과의 충돌"은 정의상
못 본다.

krdict(한국어기초사전)는 **일반 사전**이라 고유어·일상어 뜻까지 표제어당
동형어 번호(`sup_no`)로 갖고 있다. 실측(2026-08-29, 실제 키로 확인):

```
수리 동형어 4개  修理(고장 수선) / 數理 / 水利 / 受理(우리 엔트리)
가설 동형어 3개  架設 / 假設(임시 설치, 관공서 문서에서 흔한 뜻) / 假說 / 加設(우리 엔트리)
한   동형어 3개  (고유어) 하나의 / 恨 / 限(우리 엔트리)
소   동형어 4개  (고유어) 소(牛) / (고유어) 만두 속 / 小 / 訴(우리 엔트리)
자   동형어 5개  (고유어) 자(길이 재는 도구) / (고유어) 권유 어미 / 字 / 者(우리 엔트리)
거치 동형어 1개  据置 — 이건 안 잡힌다(아래 "한계" 참고)
```

알려진 5건 중 4건이 잡힌다. **재현율이 0%에서 80%로 뛴 이유는 도구가 더
똑똑해서가 아니라, krdict가 다듬은말 풀과 달리 일상어 뜻을 갖고 있기
때문이다** — 방법의 근본 조건이 바뀐 것이다.

## 방법

1. `dist/`의 `substitute` 전략 엔트리를 표제어(`term`) 기준으로 중복
   제거한다(예: '가설'이 엔트리 2개(id 21,22)를 가져도 조회는 1번).
2. 표제어를 krdict 검색 API로 조회하고, **표제어 글자가 정확히 같은 항목만**
   후보로 삼는다(`fetch_krdict.py`의 관례와 동일 — 부분일치 제외).
3. 그 후보들의 `sup_no` 집합 크기가 2 이상이면 "동형어 있음"으로 플래그한다.
   우리 `term_hanja`와 `origin`이 일치하는 항목을 "우리 뜻"으로, 나머지를
   "다른 뜻"으로 나눠 보여주되 — **최종 판단은 사람에게 넘긴다.** 플래그
   자체를 걸러내거나 순위를 매겨 자동으로 처리하지 않는다.
4. 골든 코퍼스(easy-doc, 읽기 전용) 등장 빈도를 조인해 빈도순으로 정렬한다
   (빈도가 우선순위 — 문서에 안 나오는 표제어는 위험이 낮다).

## 한계 (반드시 읽을 것 — 이 도구가 못 잡는 것)

- **`거치`는 이 방법으로도 안 잡힌다.** krdict에 `据置` 동형어가 1개뿐이다.
  "동형어가 여럿"이 아니라 **"그 하나의 뜻 자체가 우리 대치어와 미묘하게
  다르다"**(据置=예치·보류인데 우리 엔트리 하나는 잘못 붙은 '설치하다'
  easy_term을 갖고 있었다 — DESIGN.md §6.8 "정확 일치 우선 규칙" 절 참고)는
  **별개 문제**다. 동형어 탐지 도구가 이걸 잡으려고 하면 안 된다 — 이건
  "다의어 검출"이 아니라 "번역 품질 검수"이고 훨씬 어려운 문제다.
- **한자 없는 substitute 엔트리(789건, 전체의 53%)에도 이 방법이 적용된다**
  — 이전(다듬은말 풀 대조) 방법과 다른 점이다. krdict는 고유어도 `sup_no`로
  센다(예: `한`의 동형어 중 하나가 한자 없는 고유어 '하나의'). 그래서 이
  도구는 `term_hanja`가 없는 엔트리도 조회 대상에 포함한다.
- **오탐이 있다.** `자(者)`도 동형어 5개로 잡히지만, `자`가 실사용 문맥에서
  `者`로 쓰일 정확도는 이미 높게 알려져 있다(참고용 수치일 뿐 이 스크립트가
  검증하지 않는다). 이 스크립트는 **플래그와 골든 코퍼스 빈도를 나란히
  보여줄 뿐, 우선순위 판단과 최종 처분은 사람 몫이다.**
- krdict 자체도 완전한 사전이 아니다 — 지역/전문 분야 뜻이나 신조어는
  안 실릴 수 있고, 없는 동형어를 놓칠 수 있다.
- **krdict에 표제어 자체가 없는 엔트리가 상당수다(실측 2026-08-29, 927건
  전수 조회: 573건, 62%).** 전문 행정용어라 일반 사전에 안 실린 것들이다.
  **"검사했는데 깨끗함"과 "검사 자체를 할 수 없었음"을 절대 섞지 마라** —
  이 도구는 `note` 컬럼에 `[검사 불가]` 접두사로 이 둘을 구분해서 표시하고,
  실행 완료 요약(stderr)에도 별도 항목으로 집계한다. 573건을 "동형어
  없음(안전)"으로 읽으면 안 된다 — "이 방법으로는 판단할 근거가 없다"는
  뜻이다.

## 실행 — API 키는 이 스크립트를 실행하는 사람만 쓴다

이 스크립트를 **작성**한 사람이 API를 호출하지 않는다(팀 지시). 실행 전
반드시 `--dry-run`으로 무엇을 몇 건 조회할지 확인해라.

```bash
# 1. 무엇을 몇 건 조회할지 미리 본다(호출 없음)
python3 tools/detect_homonym_risk.py --dry-run

# 2. 실제 조회(캐시에 없는 것만, --limit까지만)
KRDICT_API_KEY=... python3 tools/detect_homonym_risk.py \\
    --output /path/to/scratch/homonym_report.csv --limit 200

# 3. 캐시가 쌓인 채로 다시 돌리면 새 호출 없이 나머지를 이어서 조회한다
KRDICT_API_KEY=... python3 tools/detect_homonym_risk.py \\
    --output /path/to/scratch/homonym_report.csv --limit 200

# 4. 캐시가 다 찼으면(신규 호출 0건) 실제 검수 대상(동형어 플래그)만 뽑는다
python3 tools/detect_homonym_risk.py --flag-only \\
    --output /path/to/scratch/homonym_flagged.csv
```

`--cache-dir`(기본 `tools/.krdict_homonym_cache/`)는 표제어별 API 원본 응답을
JSON으로 저장한다. **파생 캐시이지 원천 데이터가 아니다** — `tools/README.md`의
갭 리스트와 같은 원칙으로 커밋하지 않는다. 지워도 다시 채워질 뿐이다.
930개 고유 표제어를 전부 채우려면 여러 번(예: 200건씩 5회) 실행해야 한다 —
`--limit`을 한 번에 크게 올리지 마라(팀 지시, 일일 한도 5만 건은 여유가
크지만 시험 없이 다 지르지 않는다).

## 재사용

URL 조립(`_build_search_url`)·HTTP 호출/재시도(`_http_get`/`_with_retry`)·
XML 파싱(`_parse_search_response`, `sup_no` 포함)·`is_pure_hanja`는 전부
`tools/fetch_krdict.py`에서 그대로 가져다 쓴다 — 같은 API를 두 번째로
호출하는 도구가 재시도/지연/에러 처리를 따로 구현하면 그 자체가 새로운
버그원이 된다. 골든 코퍼스 로딩은 `tools/extract_gaps.py`의 `load_docs()`를
재사용한다(문서 57건 — `documents/*.json` + 최상위 `*.json`).
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

_TOOLS_DIR = Path(__file__).resolve().parent
if str(_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(_TOOLS_DIR))

import extract_gaps  # noqa: E402
import fetch_krdict as fk  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
_SRC_DIR = REPO_ROOT / "src"
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))
from easydict.lookup import EasyDict  # noqa: E402 — golden_frequencies()의 경계 인식 카운트에 씀
DEFAULT_DB_PATH = str(REPO_ROOT / "dist" / "easy_dict.sqlite3")
DEFAULT_GOLDEN_DIR = "/Users/harris/Development/private/easy-doc/data/golden"
# 캐시는 파생물이다(원천 데이터 아님) — data/raw/ 밖, tools/ 안의 전용
# 디렉터리에 둔다. tools/README.md의 "커밋하지 않는다" 원칙과 동일.
DEFAULT_CACHE_DIR = str(_TOOLS_DIR / ".krdict_homonym_cache")

OUTPUT_COLUMNS = [
    "term", "entry_ids", "our_hanja", "our_easy_terms",
    "homonym_count", "flag", "matched_sense", "other_senses",
    "golden_freq", "note",
]


def load_substitute_terms(db_path: str) -> list[dict[str, Any]]:
    """`dist/`의 substitute 엔트리를 표제어별로 묶는다. 읽기 전용 쿼리."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        SELECT id, term, term_hanja, easy_term
          FROM v_entry_full
         WHERE replace_strategy = 'substitute' AND status != 'deprecated'
         ORDER BY term, id
        """
    ).fetchall()
    conn.close()

    by_term: dict[str, dict[str, Any]] = {}
    for r in rows:
        bucket = by_term.setdefault(
            r["term"], {"term": r["term"], "entry_ids": [], "hanja_options": [], "easy_terms": []}
        )
        bucket["entry_ids"].append(r["id"])
        if r["term_hanja"] and r["term_hanja"] not in bucket["hanja_options"]:
            bucket["hanja_options"].append(r["term_hanja"])
        bucket["easy_terms"].append(r["easy_term"])
    return list(by_term.values())


def golden_frequencies(golden_dir: str, db_path: str, terms: list[str]) -> dict[str, int]:
    """골든 코퍼스(easy-doc, 읽기 전용)에서 각 표제어의 **경계 규칙을 적용한** 등장 횟수.

    ### 버그 수정 이력 (2026-08-29, 팀장 실측 지적)

    최초 구현은 `text.count(term)`로 단순 부분 문자열 개수를 셌다. 그 결과
    `자`의 빈도가 875로 나왔는데, 실제로 `EasyDict.find_all()`(경계 규칙 +
    최장일치 적용)로 세면 53이다 — **16배 부풀려졌다.** `신청자`/`대상자`
    같은 복합어 안의 `자`까지 전부 세었기 때문이다(경계 검사가 막는 바로 그
    경우, §6.5). `golden_freq`가 검수 우선순위를 정하는 지표이므로, 부풀려진
    빈도는 순위 자체를 틀어지게 만든다.

    지금은 `EasyDict.from_sqlite(db_path)`로 이 도구가 검사 중인 바로 그
    `dist/`를 로드해 `find_all()`을 문서마다 한 번씩만 돌리고(문서 수만큼만
    실행 — 표제어 수만큼 반복하지 않는다), 각 매칭의 `term`이 우리가 찾는
    표제어와 같은 경우만 센다. `find_all()`은 겹치는 위치에서 최장일치
    하나만 남기므로, 이 빈도는 "실제 문서에서 이 표제어가 사전 조회로
    몇 번이나 걸릴 것인가"를 그대로 반영한다 — 단순 텍스트 검색보다 이
    도구의 목적(실사용 위험도 추정)에 더 맞는 지표이기도 하다.
    """
    docs = extract_gaps.load_docs(golden_dir)
    d = EasyDict.from_sqlite(db_path)
    counts: dict[str, int] = {t: 0 for t in terms}
    wanted = set(terms)
    for doc in docs:
        for m in d.find_all(doc["text"]):
            if m.term in wanted:
                counts[m.term] += 1
    return counts


# ---------------------------------------------------------------------------
# 캐시: 표제어별 krdict 검색 결과(파싱된 item 리스트)를 JSON으로 저장한다.
# fetch_krdict.py의 --dump-raw-xml과 같은 파일명 규칙(영숫자 아니면 치환)을
# 써서 두 캐시가 섞여도 충돌하지 않게 한다.
# ---------------------------------------------------------------------------

def _cache_path(cache_dir: Path, term: str) -> Path:
    safe = "".join(c if c.isalnum() else "_" for c in term) or "_"
    return cache_dir / f"{safe}.json"


def _load_cache(cache_dir: Path, term: str) -> dict[str, Any] | None:
    p = _cache_path(cache_dir, term)
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None  # 손상된 캐시 파일은 다시 조회하도록 무시한다.


def _save_cache(cache_dir: Path, term: str, payload: dict[str, Any]) -> None:
    cache_dir.mkdir(parents=True, exist_ok=True)
    _cache_path(cache_dir, term).write_text(
        json.dumps(payload, ensure_ascii=False, indent=None), encoding="utf-8"
    )


def _item_to_dict(it: fk.Item) -> dict[str, Any]:
    return {
        "word": it.word,
        "sup_no": it.sup_no,
        "origin": it.origin,
        "target_code": it.target_code,
        "definition": it.senses[0].definition if it.senses else None,
    }


def query_term(
    term: str,
    api_key: str | None,
    *,
    cache_dir: Path,
    delay: float,
    max_retries: int,
    timeout: float,
    num: int,
) -> tuple[list[dict[str, Any]], str | None, bool]:
    """krdict 검색. 캐시 우선. 반환: (item dict 리스트, 에러 메시지 또는 None, 캐시에서 왔는가)."""
    cached = _load_cache(cache_dir, term)
    if cached is not None:
        return cached.get("items", []), cached.get("error"), True

    if api_key is None:
        raise RuntimeError("캐시에 없고 API 키도 없음 — 호출 불가")

    url = fk._build_search_url(term, api_key, num=num, level=None, pos=None)
    raw, err = fk._with_retry(
        lambda: fk._http_get(url, timeout=timeout), delay=delay, max_retries=max_retries
    )
    if err is not None:
        _save_cache(cache_dir, term, {"items": [], "error": err})
        return [], err, False

    try:
        items = fk._parse_search_response(raw)
        item_dicts = [_item_to_dict(it) for it in items]
        _save_cache(cache_dir, term, {"items": item_dicts, "error": None})
        return item_dicts, None, False
    except (ET.ParseError, fk.KrdictError) as e:
        _save_cache(cache_dir, term, {"items": [], "error": str(e)})
        return [], str(e), False


# ---------------------------------------------------------------------------
# 검수표 행 생성
# ---------------------------------------------------------------------------

def _sense_label(item: dict[str, Any]) -> str:
    origin = item.get("origin") or "(한자 없음)"
    definition = item.get("definition") or "(뜻풀이 없음)"
    sup_no = item.get("sup_no")
    return f"[{sup_no}] {origin}={definition}"


# `~하다`/`~되다` 접미 불일치 (2026-08-29, 팀장 실측 지적).
#
# krdict의 `origin`은 `~하다`/`~되다` 표제어에 그 접미사까지 한글로 붙여서
# 준다(`날인하다`의 origin='捺印하다', `선정하다`의 origin='選定하다' —
# tools/tests/fixtures/krdict_seonjeong.xml로 확인됨). 반면 우리
# `term_hanja`는 어근만 담는다(`날인하다`의 term_hanja='捺印', 접미사 없음
# — `easydict.build`가 원천 CSV의 '명기하다(明記--)'류 표기에서 '--'
# 플레이스홀더를 한자 없이 처리하기 때문). 이 차이를 그대로 문자열 비교하면
# **완전히 같은 뜻인데도 "일치 항목 없음"으로 잘못 플래그**된다 — 이전
# 방법에서 NFKC 정규화 없이 37건 중 35건이 잡음이었던 것과 같은 종류의
# 실수다. 비교 전에 양쪽에서 이 접미사를 떼어 낸다(우리 쪽엔 원래 없지만
# 방어적으로 같이 처리한다 — idempotent).
_VERB_SUFFIX_RE = re.compile(r"(하다|되다)$")


def _strip_verb_suffix(s: str) -> str:
    return _VERB_SUFFIX_RE.sub("", s)


def make_row(bucket: dict[str, Any], items: list[dict[str, Any]], error: str | None, golden_freq: int) -> dict[str, str]:
    row = {col: "" for col in OUTPUT_COLUMNS}
    row["term"] = bucket["term"]
    row["entry_ids"] = ",".join(str(i) for i in bucket["entry_ids"])
    row["our_hanja"] = "; ".join(bucket["hanja_options"])
    row["our_easy_terms"] = "; ".join(bucket["easy_terms"])
    row["golden_freq"] = str(golden_freq)

    if error is not None:
        row["note"] = f"[조회 실패] {error}"
        return row

    # "검사했는데 깨끗함"과 "검사 자체를 못 함"을 섞지 않는다(팀장 지시) —
    # krdict에 표제어 자체가 없는 것(항목 0건)과, 항목은 있지만 정확히
    # 같은 글자의 표제어가 없는 것(드묾 — 예: 부분일치만 옴)을 구분한다.
    if not items:
        row["note"] = "[검사 불가] krdict에 표제어 자체가 없음(전문 행정용어라 일반 사전 미등재로 추정)"
        return row

    exact = [it for it in items if it["word"] == bucket["term"]]
    if not exact:
        row["note"] = "[검사 불가] krdict 결과는 있으나 정확히 같은 글자의 표제어가 없음(부분일치만 있음)"
        return row

    sup_nos = sorted({it["sup_no"] for it in exact if it["sup_no"] is not None}, key=lambda s: (len(s), s))
    homonym_count = len(sup_nos)
    row["homonym_count"] = str(homonym_count)
    row["flag"] = "동형어" if homonym_count >= 2 else ""

    our_hanja_set = {_strip_verb_suffix(h) for h in bucket["hanja_options"]}
    matched = [
        it for it in exact
        if it.get("origin") and _strip_verb_suffix(it["origin"]) in our_hanja_set
    ]
    others = [it for it in exact if it not in matched]

    row["matched_sense"] = "; ".join(_sense_label(it) for it in matched) if matched else (
        "(한자 불일치 또는 우리 엔트리에 한자 없음 — 아래 전체 참고)" if not our_hanja_set else "(일치하는 항목 없음)"
    )
    row["other_senses"] = "; ".join(_sense_label(it) for it in others)
    return row


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="dist/의 substitute 엔트리를 krdict sup_no(동형어 번호)와 대조해 검수표를 만든다.",
    )
    p.add_argument("--db", default=DEFAULT_DB_PATH, help="dist/easy_dict.sqlite3 경로(읽기 전용)")
    p.add_argument("--golden-dir", default=DEFAULT_GOLDEN_DIR, help="easy-doc golden 코퍼스 경로(읽기 전용)")
    p.add_argument("--cache-dir", type=Path, default=Path(DEFAULT_CACHE_DIR), help="krdict 응답 캐시 디렉터리(파생물, 커밋 금지)")
    p.add_argument("--output", type=Path, default=None, help="출력 CSV 경로(기본: stdout)")
    p.add_argument("--api-key", default=None, help="krdict 인증키. 생략하면 환경변수 KRDICT_API_KEY를 쓴다.")
    p.add_argument("--limit", type=int, default=200, help="이번 실행에서 허용할 최대 신규 API 호출 수(캐시 적중은 안 셈). 기본 200")
    p.add_argument("--delay", type=float, default=0.2, help="호출 사이 지연(초), 기본 0.2")
    p.add_argument("--max-retries", type=int, default=3, help="호출 실패 시 재시도 횟수 상한, 기본 3")
    p.add_argument("--timeout", type=float, default=10.0, help="HTTP 타임아웃(초), 기본 10")
    p.add_argument("--num", type=int, default=10, help="호출당 결과 건수(API의 num 파라미터), 기본 10")
    p.add_argument("--dry-run", action="store_true", help="실제 API를 호출하지 않고 무엇을 몇 건(캐시 적중/신규) 조회할지만 출력한다")
    p.add_argument(
        "--flag-only", action="store_true",
        help="동형어로 플래그된 표제어만 출력한다(927건 전부가 아니라 실제 검수 대상만 뽑고 싶을 때)",
    )
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    buckets = load_substitute_terms(args.db)
    terms = [b["term"] for b in buckets]
    freqs = golden_frequencies(args.golden_dir, args.db, terms)

    cache_hits = [t for t in terms if _load_cache(args.cache_dir, t) is not None]
    cache_misses = [t for t in terms if t not in set(cache_hits)]
    to_fetch_now = cache_misses[: args.limit]
    deferred = cache_misses[args.limit :]

    if args.dry_run:
        print(f"[dry-run] substitute 전략 고유 표제어 수: {len(terms)} (엔트리 {sum(len(b['entry_ids']) for b in buckets)}건)")
        print(f"[dry-run] 캐시 적중(호출 불필요): {len(cache_hits)}")
        print(f"[dry-run] 캐시 없음(신규 조회 필요): {len(cache_misses)}")
        print(f"[dry-run] --limit={args.limit} 적용 후 이번에 실제 호출할 건수: {len(to_fetch_now)}")
        if deferred:
            print(f"[dry-run] --limit 초과로 이번엔 안 부르는 건수: {len(deferred)} (다음 실행에서 캐시 이어서 채워짐)")
        print(f"[dry-run] 호출 간 지연: {args.delay}초, 예상 소요 시간(신규분만): {len(to_fetch_now) * args.delay:.1f}초 이상")
        print(
            "[dry-run] 호출 URL 형태(키는 가려서 표시): "
            f"{fk._build_search_url('<표제어>', '****', num=args.num, level=None, pos=None)}"
        )
        print("[dry-run] 이번에 새로 조회할 표제어 샘플(최대 10개, 골든 빈도순):")
        sample = sorted(to_fetch_now, key=lambda t: -freqs.get(t, 0))[:10]
        for t in sample:
            print(f"  - {t} (골든 빈도={freqs.get(t, 0)})")
        if len(to_fetch_now) > 10:
            print(f"  ... 외 {len(to_fetch_now) - 10}건")
        print(f"[dry-run] 캐시 디렉터리: {args.cache_dir}")
        return 0

    api_key = args.api_key or os.environ.get("KRDICT_API_KEY")
    if not api_key and cache_misses:
        print(
            f"오류: 캐시에 없는 표제어가 {len(cache_misses)}건 있는데 krdict 인증키가 없습니다. "
            "--api-key로 넘기거나 환경변수 KRDICT_API_KEY를 설정하세요. "
            "키 발급: https://krdict.korean.go.kr/openApi/openApiRegister",
            file=sys.stderr,
        )
        return 2

    out_f = args.output.open("w", encoding="utf-8", newline="") if args.output else sys.stdout
    fetched_this_run = 0
    # "검사했는데 깨끗함"(clean)과 "검사 자체를 못 함"(no_krdict_entry/no_exact_match)을
    # 섞지 않는다(팀장 지시) — 전문 행정용어가 krdict에 아예 없는 경우가
    # 실측 62%(573/927)였다. 이 구분을 요약에도 그대로 반영한다.
    stats = {"flagged": 0, "clean": 0, "no_krdict_entry": 0, "no_exact_match": 0, "error": 0}
    try:
        writer = csv.DictWriter(out_f, fieldnames=OUTPUT_COLUMNS)
        writer.writeheader()
        rows: list[dict[str, str]] = []
        total = len(buckets)
        for i, bucket in enumerate(buckets, start=1):
            term = bucket["term"]
            from_cache = _load_cache(args.cache_dir, term) is not None
            if not from_cache and fetched_this_run >= args.limit:
                print(f"[{i}/{total}] 건너뜀({term}): --limit 도달, 캐시 없음", file=sys.stderr)
                continue
            print(f"[{i}/{total}] 조회: {term}{' (캐시)' if from_cache else ''}", file=sys.stderr)
            items, error, was_cached = query_term(
                term, api_key, cache_dir=args.cache_dir,
                delay=args.delay, max_retries=args.max_retries, timeout=args.timeout, num=args.num,
            )
            if not was_cached:
                fetched_this_run += 1
            row = make_row(bucket, items, error, freqs.get(term, 0))
            rows.append(row)

            if error is not None:
                stats["error"] += 1
            elif row["note"].startswith("[검사 불가] krdict에 표제어 자체가 없음"):
                stats["no_krdict_entry"] += 1
            elif row["note"].startswith("[검사 불가]"):
                stats["no_exact_match"] += 1
            elif row["flag"]:
                stats["flagged"] += 1
            else:
                stats["clean"] += 1

        # 빈도 우선순위: 골든 코퍼스에 많이 나오는 표제어부터 보여준다.
        rows.sort(key=lambda r: (-int(r["golden_freq"]), r["term"]))
        output_rows = [r for r in rows if r["flag"] == "동형어"] if args.flag_only else rows
        for row in output_rows:
            writer.writerow(row)
    finally:
        if out_f is not sys.stdout:
            out_f.close()

    print(
        f"완료: 동형어 플래그(검수 대상) {stats['flagged']}건 / 검사 완료·정상(동형어 없음) {stats['clean']}건 / "
        f"[검사 불가] krdict에 표제어 자체 없음 {stats['no_krdict_entry']}건 / "
        f"[검사 불가] 정확 일치 없음(부분일치만) {stats['no_exact_match']}건 / 조회 실패 {stats['error']}건 "
        f"(이번 실행 신규 호출 {fetched_this_run}건, 캐시 적중 {len(rows) - fetched_this_run}건)",
        file=sys.stderr,
    )
    if args.flag_only:
        print(f"--flag-only: 출력에 {stats['flagged']}건만 실었다(전체 {len(rows)}건 중).", file=sys.stderr)
    _no_check = stats["no_krdict_entry"] + stats["no_exact_match"]
    if _no_check:
        print(
            f"주의: {_no_check}건은 '검사했는데 깨끗함'이 아니라 '검사 자체를 못 함'이다 — "
            "krdict가 전문 행정용어를 다 갖고 있지 않다(방법의 구조적 한계, 모듈 docstring 참고).",
            file=sys.stderr,
        )
    if len(rows) < len(buckets):
        print(
            f"경고: --limit={args.limit} 때문에 {len(buckets) - len(rows)}건은 이번 출력에서 빠졌다 "
            "— 다시 실행하면 캐시가 이어서 채워진다.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
