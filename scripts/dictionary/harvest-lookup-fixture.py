#!/usr/bin/env python3
"""P0-5 S1 조회 픽스처(lookup-fixture.json)의 golden 케이스 검증/제안 도구.

docs/plans/2026-09-04-p0-5-easy-word-dictionary-rag.md SS3.6/조각 Q, 그리고 2026-09-05 리뷰
(항목 5)가 요청한 harvest 스크립트다. lookup-fixture.json 의 `$comment` 가 이 파일을 가리킨다.

## 이 스크립트가 하는 것과 하지 않는 것

`--check` 모드는 결정적이고 완전히 검증됐다: `source: "golden:<id>"` 케이스마다

1. `query` 문자열이 실제로 그 골든 문서(`data/golden/documents/<id>-*.json`)의 `source_text`
   안에 등장하는지,
2. 실제로 커밋된 사전 색인(`easy_dict.index.json`)에 대해 `query` 를 조회했을 때(core
   `TermLookup.candidates` 와 같은 규칙 - 직접 일치 우선, 없으면 접두/접미 복합어 부분 일치)
   top-1 결과가 그 케이스의 `expected_term`·`expected_entry_id`·`expected_easy_term`·
   `expected_strategy`·`expected_match_kind`·`expected_applicable` 전부와 일치하는지

를 다시 계산해 대조한다. 이 파일의 `DictionaryIndex` 는 core
`kr.easydoc.core.dictionary.DictionaryIndex`(§6.5·§6.7 경계 규칙)를 Python 으로 그대로 옮긴
것이다 - 두 구현이 갈리면 `--check` 가 실패해 알려 준다.

`--check` 가 **아닌** 기본 모드(`--propose <docid>`)는 훨씬 약하다: 참고 파일
(`infrastructure/src/test/resources/dictionary/reference/<id>.txt`)이 나열한 `바꿔 쓰세요`/
`원래 말은 남기고` 목록에서, 실제 색인에 대해 아직 golden 케이스로 쓰이지 않은 term 을 문서
순서대로 하나씩 찾아 후보를 **제안**만 한다 - 자동으로 fixture 를 고치지 않는다.

**이미 커밋된 32건의 golden 케이스를 이 스크립트로 처음부터 재생성할 수는 없다.** 원래
큐레이션 절차(문서당 substitute/gloss 각 1건, 이미 쓰인 term 은 건너뜀 등 일부 규칙은 fixture
의 `note` 필드에 흔적이 남아 있다 - 예: "harvest 스크립트가 문서당 1건으로 제한해 스킵된
나머지를 손으로 보강")는 저장소에 커밋된 적이 없어 그 전문을 복원할 수 없다. 실측으로 확인한
반례 하나: 문서 007 과 010 은 참고 파일의 첫 substitute 후보로 "도래하다" 를 적어 뒀고 그 말은
실제 색인에서도(entry id 454, easy_term "이르다") 문제없이 매칭되는데, 커밋된 fixture 는 두
문서 모두에서 그 말을 쓰지 않고 다음 후보를 golden 케이스로 골랐다 - 그 판단이 어떤 규칙이었는지
알아낼 근거가 없다. 그래서 이 스크립트는 "재현"을 주장하지 않고, 대신 **다시 계산해도 지금 이
순간까지는 참으로 남아 있다**는 것만 보증한다. 그 보증이 `--check` 다.

## 사용법

    python3 scripts/dictionary/harvest-lookup-fixture.py --check
    python3 scripts/dictionary/harvest-lookup-fixture.py --propose 020

stdlib 만 쓴다(외부 패키지 설치가 필요 없다). 저장소 루트에서 실행한다고 가정한다 - 상대
경로는 모두 `--repo-root`(기본값 이 파일 기준 두 단계 위) 기준이다.
"""

from __future__ import annotations

import argparse
import glob
import json
import re
import sys
from pathlib import Path
from typing import Optional

REPO_ROOT_DEFAULT = Path(__file__).resolve().parents[2]
FIXTURE_RELATIVE = "backend-kotlin/core/src/test/resources/kr/easydoc/core/dictionary/lookup-fixture.json"
REFERENCE_DIR_RELATIVE = "backend-kotlin/infrastructure/src/test/resources/dictionary/reference"
GOLDEN_DOCS_DIR_RELATIVE = "data/golden/documents"
INDEX_JSON_RELATIVE = "backend-kotlin/infrastructure/src/main/resources/dictionary/easy_dict.index.json"


def _is_hangul(ch: str) -> bool:
    return "가" <= ch <= "힣"


def _is_latin_or_digit(ch: str) -> bool:
    return ch.isascii() and (ch.isalpha() or ch.isdigit())


class _TrieNode:
    __slots__ = ("children", "entry_ids")

    def __init__(self) -> None:
        self.children: dict[str, "_TrieNode"] = {}
        self.entry_ids: Optional[list[int]] = None


class DictionaryIndex:
    """`kr.easydoc.core.dictionary.DictionaryIndex` 의 Python 이식.

    §6.5(최장일치 + 겹침 해소)·§6.7(어절 경계·조사 연쇄·로마자/숫자 경계·길이 1 표제어
    오탐 방지) 규칙을 그대로 옮긴다. Kotlin 원본과 이름을 최대한 맞춰 대조하기 쉽게 했다.
    """

    LIST_MARKER_SUFFIXES = ".)"

    def __init__(self, entries: dict[str, dict], surface_index: dict[str, list[int]], josa: list[str]) -> None:
        self.entries = entries
        self.josa = [j for j in josa if j]
        self.root = _TrieNode()
        for surface, ids in surface_index.items():
            if not surface:
                continue
            dangling = [i for i in ids if str(i) not in entries]
            if dangling:
                raise ValueError(f"표면형 '{surface}' 이(가) 없는 엔트리를 가리킨다: {dangling}")
            node = self.root
            for ch in surface:
                node = node.children.setdefault(ch, _TrieNode())
            node.entry_ids = (node.entry_ids or []) + ids

    def find_all(self, text: str) -> list[dict]:
        raw = []
        for at in range(len(text)):
            match = self._longest_match_at(text, at)
            if match is not None:
                raw.append(match)
        raw.sort(key=lambda m: (-self.entries[str(m["entryId"])]["p"], -(m["end"] - m["start"]), m["start"]))
        accepted: list[dict] = []
        for match in raw:
            overlaps = any(match["start"] < a["end"] and a["start"] < match["end"] for a in accepted)
            if not overlaps:
                accepted.append(match)
        accepted.sort(key=lambda m: m["start"])
        return accepted

    def _candidates_at(self, text: str, at: int) -> list[tuple[int, list[int]]]:
        found: list[tuple[int, list[int]]] = []
        node = self.root
        cursor = at
        while cursor < len(text):
            node = node.children.get(text[cursor])
            if node is None:
                break
            cursor += 1
            if node.entry_ids is not None:
                found.append((cursor - at, node.entry_ids))
        return found

    def _longest_match_at(self, text: str, at: int) -> Optional[dict]:
        if not self._left_boundary_ok(text, at):
            return None
        for length, ids in reversed(self._candidates_at(text, at)):
            accepted = self._accept_candidate(text, at, length, ids)
            if accepted is not None:
                return accepted
        return None

    def _accept_candidate(self, text: str, at: int, length: int, ids: list[int]) -> Optional[dict]:
        end = at + length
        surface = text[at:end]
        if self._candidate_rejected(text, at, end, surface):
            return None
        exact_ids = [i for i in ids if self.entries[str(i)]["t"] == surface]
        pool = exact_ids if exact_ids else ids
        winner = max(pool, key=lambda i: self.entries[str(i)]["p"])
        return {"start": at, "end": end, "surface": surface, "entryId": winner}

    def _candidate_rejected(self, text: str, at: int, end: int, surface: str) -> bool:
        if not self._boundary_ok(text, end):
            return True
        single_hangul = len(surface) == 1 and _is_hangul(surface[0])
        return single_hangul and not self._single_hangul_headword_ok(text, at, end)

    def _boundary_ok(self, text: str, end: int) -> bool:
        latin_run = (
            0 < end < len(text) and _is_latin_or_digit(text[end - 1]) and _is_latin_or_digit(text[end])
        )
        return (not latin_run) and self._josa_chain_reaches_word_boundary(text, end)

    def _josa_chain_reaches_word_boundary(self, text: str, frm: int) -> bool:
        visited = {frm}
        pending = [frm]
        while pending:
            at = pending.pop(0)
            if at >= len(text) or not _is_hangul(text[at]):
                return True
            for particle in self.josa:
                nxt = at + len(particle)
                if text.startswith(particle, at) and nxt not in visited:
                    visited.add(nxt)
                    pending.append(nxt)
        return False

    def _left_boundary_ok(self, text: str, at: int) -> bool:
        if at == 0:
            return True
        if _is_hangul(text[at - 1]):
            return False
        return not (_is_latin_or_digit(text[at]) and _is_latin_or_digit(text[at - 1]))

    def _single_hangul_headword_ok(self, text: str, at: int, end: int) -> bool:
        after_digit = at > 0 and text[at - 1].isascii() and text[at - 1].isdigit()
        line_start = text.rfind("\n", 0, at) + 1
        stands_alone = all(text[i] in " \t" for i in range(line_start, at))
        list_marker = stands_alone and end < len(text) and text[end] in self.LIST_MARKER_SUFFIXES
        return not after_digit and not list_marker


def load_index(repo_root: Path) -> tuple[DictionaryIndex, dict]:
    index_path = repo_root / INDEX_JSON_RELATIVE
    payload = json.loads(index_path.read_text(encoding="utf-8"))
    entries = payload["entries"]
    return DictionaryIndex(entries, payload["surface_index"], payload["josa"]), entries


def candidates_for_query(index: DictionaryIndex, text: str) -> tuple[list[dict], str]:
    """core `TermLookup.candidates` 와 같은 순서: 직접 일치 우선, 없으면 접두/접미
    부분 문자열 중 가장 긴 일치(복합어 부분 일치, §3.1)."""
    direct = index.find_all(text)
    if direct:
        return direct, "direct"
    n = len(text)
    best: Optional[dict] = None

    def _consider(candidate_text: str) -> None:
        nonlocal best
        matches = index.find_all(candidate_text)
        if not matches:
            return
        candidate = matches[0]
        if best is None or (candidate["end"] - candidate["start"]) > (best["end"] - best["start"]):
            best = candidate

    for length in range(n - 1, 0, -1):
        _consider(text[:length])
    for length in range(n - 1, 0, -1):
        _consider(text[n - length :])
    return ([best] if best else []), "embedded"


def _match_kind_for(mode: str, surface: str, term: str) -> str:
    if mode == "embedded":
        return "compound_part"
    return "exact" if surface == term else "inflected"


def _load_fixture(repo_root: Path) -> dict:
    fixture_path = repo_root / FIXTURE_RELATIVE
    return json.loads(fixture_path.read_text(encoding="utf-8"))


def _golden_document_text(repo_root: Path, doc_id: str) -> str:
    matches = glob.glob(str(repo_root / GOLDEN_DOCS_DIR_RELATIVE / f"{doc_id}-*.json"))
    if not matches:
        raise FileNotFoundError(f"골든 문서를 찾지 못했다: {doc_id}")
    payload = json.loads(Path(matches[0]).read_text(encoding="utf-8"))
    return payload["source_text"]


def run_check(repo_root: Path) -> int:
    index, entries = load_index(repo_root)
    fixture = _load_fixture(repo_root)

    failures: list[str] = []
    checked = 0
    for case in fixture["cases"]:
        source = case["source"]
        if not source.startswith("golden:"):
            continue
        checked += 1
        doc_id = source.split(":", 1)[1]
        query = case["query"]

        try:
            text = _golden_document_text(repo_root, doc_id)
        except FileNotFoundError as exc:
            failures.append(f"[{source}] {query!r}: {exc}")
            continue

        if query not in text:
            failures.append(f"[{source}] {query!r}: query가 골든 문서 source_text에 없다")
            continue

        cands, mode = candidates_for_query(index, query)
        if not cands:
            failures.append(f"[{source}] {query!r}: 실제 색인에서 무결과 (기대 term={case['expected_term']!r})")
            continue

        top = cands[0]
        entry = entries[str(top["entryId"])]
        match_kind = _match_kind_for(mode, top["surface"], entry["t"])
        applicable = match_kind != "compound_part" and entry["s"] == "substitute"

        expected = {
            "expected_term": entry["t"],
            "expected_entry_id": top["entryId"],
            "expected_easy_term": entry["e"],
            "expected_strategy": entry["s"],
            "expected_match_kind": match_kind,
            "expected_applicable": applicable,
        }
        mismatches = [
            f"{field}(기대={case.get(field)!r}, 실측={actual!r})"
            for field, actual in expected.items()
            if case.get(field) != actual
        ]
        if mismatches:
            failures.append(f"[{source}] {query!r}: " + ", ".join(mismatches))

    print(f"golden 케이스 {checked}건 재검증")
    if failures:
        print(f"실패 {len(failures)}건:")
        for line in failures:
            print(f"  - {line}")
        return 1
    print("전부 일치한다.")
    return 0


def parse_reference(path: Path) -> tuple[list[str], list[str]]:
    text = path.read_text(encoding="utf-8")
    sub_section = re.search(r"### 바꿔 쓰세요\n(.*?)(?:\n### |\Z)", text, re.S)
    gloss_section = re.search(r"### 원래 말은 남기고.*?\n(.*?)(?:\n### |\Z)", text, re.S)
    subs = []
    if sub_section:
        for line in sub_section.group(1).splitlines():
            m = re.match(r"- (.+?) → (.+)", line.strip())
            if m:
                subs.append(m.group(1))
    glosses = []
    if gloss_section:
        for line in gloss_section.group(1).splitlines():
            m = re.match(r"- (.+?) — 뜻: (.+)", line.strip())
            if m:
                glosses.append(m.group(1))
    return subs, glosses


def _extend_query(text: str, start: int, end: int) -> str:
    e = end
    while e < len(text) and _is_hangul(text[e]):
        e += 1
    return text[start:e]


def run_propose(repo_root: Path, doc_id: str) -> int:
    index, entries = load_index(repo_root)
    fixture = _load_fixture(repo_root)
    already_used = {c["expected_term"] for c in fixture["cases"] if c.get("expected_term")}

    ref_path = repo_root / REFERENCE_DIR_RELATIVE / f"{doc_id}.txt"
    if not ref_path.is_file():
        print(f"참고 파일이 없다: {ref_path}", file=sys.stderr)
        return 1
    text = _golden_document_text(repo_root, doc_id)
    subs, glosses = parse_reference(ref_path)

    matches = index.find_all(text)
    by_term: dict[str, list[dict]] = {}
    for m in matches:
        by_term.setdefault(entries[str(m["entryId"])]["t"], []).append(m)

    def pick(term_list: list[str]) -> Optional[dict]:
        for term in term_list:
            if term in by_term and term not in already_used:
                match = by_term[term][0]
                entry = entries[str(match["entryId"])]
                query = _extend_query(text, match["start"], match["end"])
                match_kind = "exact" if match["surface"] == entry["t"] else "inflected"
                return {
                    "query": query,
                    "source": f"golden:{doc_id}",
                    "expected_term": entry["t"],
                    "expected_entry_id": match["entryId"],
                    "expected_easy_term": entry["e"],
                    "expected_strategy": entry["s"],
                    "expected_match_kind": match_kind,
                    "expected_applicable": match_kind != "compound_part" and entry["s"] == "substitute",
                }
        return None

    proposals = []
    sub_case = pick(subs)
    if sub_case:
        proposals.append(sub_case)
        already_used.add(sub_case["expected_term"])
    gloss_case = pick(glosses)
    if gloss_case:
        proposals.append(gloss_case)

    if not proposals:
        print(f"문서 {doc_id}: 제안할 후보가 없다(참고 목록 전부가 이미 쓰였거나 실제 색인에서 안 잡힌다).")
        return 0

    print(f"문서 {doc_id} 제안 - 검토 후 손으로 fixture에 추가할 것 (자동 반영 아님):")
    for case in proposals:
        print(json.dumps(case, ensure_ascii=False, indent=2))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=REPO_ROOT_DEFAULT,
        help="저장소 루트 (기본값: 이 스크립트 기준 두 단계 위)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="커밋된 golden 케이스를 실제 색인·골든 문서로 재검증한다",
    )
    parser.add_argument(
        "--propose",
        metavar="DOC_ID",
        help="아직 골든 케이스가 없는 문서 id(예: 020)의 후보를 제안만 한다",
    )
    args = parser.parse_args()

    if args.check:
        return run_check(args.repo_root)
    if args.propose:
        return run_propose(args.repo_root, args.propose)

    parser.print_help()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
