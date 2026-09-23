#!/usr/bin/env python3
"""`dist/` 산출물이 스스로 모순되지 않는지 확인한다 (docs/inspection-plan.md 층위 1).

**읽기만 한다.** `dist/easy_dict.sqlite3`/`.index.json`/`.simple.jsonl`을 열어
검사할 뿐 절대 쓰지 않는다 — 검사가 대상을 바꾸면 검사가 아니다(팀장 지시).
`dist/`를 재빌드하지 않는다. `data/raw/*.csv`도 안 건드린다.

## 이미 있던 불변식 (진입점이 없어서 손으로 확인하던 것)
- `substitute`+`review` 공존 금지 (`build.find_unreviewed_substitutions()`와 같은
  계약이지만, 여기서는 **최종 산출물**을 대상으로 다시 확인한다 — 빌드 시점
  검사와 산출물 시점 검사가 같은 답을 내는지 자체가 신호다)
- `readability` 범위(1~3) — schema.sql의 CHECK 제약과 같은 계약을 산출물에서 재확인
- `deprecated` 유출 금지 — `index.json`/`simple.jsonl` 어디에도 `status='deprecated'`
  엔트리의 (term, easy_term)가 나오면 안 된다
- `simple.jsonl` 계약(§4.4) — `keep`/`review` 행 제외, `gloss`는 `원어(easy_term)`
  형태로 원어 보존

## 이번 세션 결함에서 새로 도출한 불변식
- **도달 가능성** — 폐기(deprecated)되지 않은 모든 엔트리가 `index.json`의
  `surface_index`에서 **자기 자신의 표제어(term)로 조회 가능**해야 한다
  (`가설`·`거치`·`내방`이 사전에 있는데 영영 안 나온 사고, DESIGN.md 참고)
- **caution의 검수 메모 잔존 금지** (2026-09-06) — 배포 색인(`index.json`)의
  `caution`(`c`)이 `review_notes.REVIEW_NOTE_PATTERN`에 매칭되면 안 된다.
  `caution`은 프런트(`TermLookupPopover`)와 LLM 프롬프트(`DictionaryContextLines`)에
  그대로 노출되므로, 내부 검수 메모가 여기 남으면 유출이다. `build.py`가
  잉제스트 시점에 `classify_caution()`으로 걸러내지만, 이 검사는 그 게이트를
  우회하는 경로(수동 UPDATE, 마이그레이션 재실행 누락 등)가 생겨도 산출물에서
  다시 잡는다. `NEEDS_CONFIRMATION_MARKER`(`[확인 필요]`)가 있는 caution은
  제어 신호라 예외로 둔다(review_notes.classify_caution()과 같은 규칙)
- **보호 엔트리 승리** — 같은 표면형에 후보가 여럿이고 그중 하나라도
  risk_level이 다른 후보보다 높으면, `surface_index`의 승자(첫 원소)는
  risk가 더 낮은 후보가 되면 안 된다(§6.8 키①, `export.winner_sort_key`가
  이미 구현한 규칙을 산출물에서 다시 확인한다 — 37건이 지던 사고)
- **`v`(뜻풀이 검수 이력) 규약** (2026-09-23) — 배포 색인의 `v`는 값이
  `"reviewed"`(`models.DEFINITION_REVIEW_MARK`) 하나뿐이고, 뜻풀이(`d`)가
  빈 엔트리에는 붙을 수 없다. 표시가 없다는 뜻으로 `v: null`을 쓰는 것도
  위반이다 — 키 자체가 없어야 한다. (Kotlin은 **모르는** 값이면 기동을
  거부하지만 `"unverified"`·null·빈 문자열은 조용히 UNVERIFIED로 흡수한다
  — 조용한 흡수도 이 저장소의 규약 위반이라 여기서 잡는다. `DictionaryEntry.kt`
  참고.)
- **DB-색인 `v` 일치** (2026-09-23) — DB(정본)의
  `definition_review.is_definition_reviewed()` 판정과 배포 색인의 `v`
  존재 여부가 entry_id 단위로 서로 어긋나면 안 된다(양방향). 두 산출물이
  같은 판정 규칙을 따라야 하는데 수동 UPDATE·마이그레이션 누락·재익스포트
  전 DB만 고친 경우 등으로 갈라질 수 있다 — deprecated 엔트리는 애초에
  색인에서 빠지므로(`export._ACTIVE_ENTRIES_SQL`) 이 검사에서 제외한다
- **definition_reviewed_at 형식** (2026-09-23) — `reviewed_at`/`reviewed_by`를
  둘 다 채웠는데(검수를 시도했는데) `reviewed_at`이 ISO-8601
  (`definition_review.REVIEWED_AT_PATTERN`)이 아니면
  `is_definition_reviewed()`가 조용히 "미검수"로 fail-close한다 — 조용히
  넘어가면 오타를 아무도 못 본다. 여기서 명시적으로 드러낸다
- **엔트리 귀속 불변** — `sources` 테이블의 모든 행은 최소 1개 이상의
  `entries.source_id`가 가리켜야 한다. 예문 전용 원천(`--source-role
  examples`)은 애초에 `sources`에 등록되지 않으므로(build.py 설계), 등록된
  소스인데 소유 엔트리가 0건이면 그 자체가 이상 신호다(318건 귀속 탈취 사고,
  DESIGN.md §5.5(7))

## 이 검사가 못 보는 것 (docs/inspection-plan.md §2 원칙 "겨냥"을 스스로 밝힌다)
층위 2(코퍼스 통과: 로마자 경계, 원문 파괴, 활용형 비문)와 층위 3(의미
검증: krdict 대조)은 여기서 다루지 않는다 — `main()` 끝에서 명시적으로
[미검사]로 출력한다. "통과"가 "안전"으로 읽히면 안 된다.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
_SRC_DIR = REPO_ROOT / "src"
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))

from easydict.definition_review import (  # noqa: E402  (판정 규칙 재사용 — 새 규칙을 만들지 않는다)
    REVIEWED_AT_PATTERN,
    is_definition_reviewed,
)
from easydict.export import _RISK_WINNER_RANK, SOURCE_TRUST_TIER  # noqa: E402  (읽기 전용 재사용 — 새 순위표를 만들지 않는다)
from easydict.lookup import EasyDict  # noqa: E402  (읽기 전용 — dist/를 조회만 한다)
from easydict.models import DEFINITION_REVIEW_MARK, NEEDS_CONFIRMATION_MARKER  # noqa: E402
from easydict.review_notes import REVIEW_NOTE_PATTERN  # noqa: E402  (판정 규칙 재사용 — 새 패턴을 만들지 않는다)

DEFAULT_DB_PATH = REPO_ROOT / "dist" / "easy_dict.sqlite3"
DEFAULT_INDEX_JSON_PATH = REPO_ROOT / "dist" / "easy_dict.index.json"
DEFAULT_SIMPLE_JSONL_PATH = REPO_ROOT / "dist" / "easy_dict.simple.jsonl"


class Violation:
    def __init__(self, check: str, detail: str) -> None:
        self.check = check
        self.detail = detail

    def __str__(self) -> str:
        return f"[{self.check}] {self.detail}"


def _load_entries(conn: sqlite3.Connection) -> list[dict]:
    conn.row_factory = sqlite3.Row
    return [dict(r) for r in conn.execute(
        "SELECT id, term, term_norm, easy_term, replace_strategy, risk_level, "
        "status, readability, source_id, definition, "
        "definition_reviewed_at, definition_reviewed_by FROM entries"
    )]


def check_substitute_review_coexistence(entries: list[dict]) -> list[Violation]:
    """§5.5 안전 불변식: 미검수(status=review) 항목은 원문을 지울 수 없다.

    build.py의 find_unreviewed_substitutions()와 같은 계약을 산출물에서
    다시 확인한다 — CSV 명시값이 이 보장을 깰 수 있어(§요청 3) 빌드 시점
    검사만으로는 부족하다.
    """
    out = []
    for e in entries:
        if e["replace_strategy"] == "substitute" and e["status"] == "review":
            out.append(Violation(
                "substitute+review 공존",
                f"id={e['id']} term={e['term']!r} easy_term={e['easy_term']!r}",
            ))
    return out


def check_readability_range(entries: list[dict]) -> list[Violation]:
    out = []
    for e in entries:
        r = e["readability"]
        if r is None or not (1 <= r <= 3):
            out.append(Violation(
                "readability 범위",
                f"id={e['id']} term={e['term']!r} readability={r!r} (1~3 이어야 함)",
            ))
    return out


def _index_json_pairs(index_doc: dict) -> set[tuple[str, str]]:
    return {(v["t"], v["e"]) for v in index_doc.get("entries", {}).values()}


def _simple_jsonl_lines(path: Path) -> list[dict]:
    lines = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                lines.append(json.loads(line))
    return lines


def check_deprecated_leakage(
    entries: list[dict], index_doc: dict, simple_lines: list[dict],
) -> list[Violation]:
    out = []
    deprecated = {(e["term"], e["easy_term"]) for e in entries if e["status"] == "deprecated"}
    if not deprecated:
        return out
    index_pairs = _index_json_pairs(index_doc)
    leaked_index = deprecated & index_pairs
    for term, easy in leaked_index:
        out.append(Violation("deprecated 유출(index.json)", f"term={term!r} easy_term={easy!r}"))

    # simple.jsonl은 §4.4 규칙대로 easy_term을 변형해서 싣는다(gloss는
    # `term(easy_term)` 합성, keep은 행 자체가 없음). "이 term이 하나라도
    # 있으면 유출"이라는 판정은 같은 term에 폐기되지 않은 다른 엔트리가
    # 정당하게 실려 있는 경우(§5.5.1 동형이의 처분 — 예: '공지' 空地는
    # 폐기, '공지' 公知는 활성)를 오탐한다. index.json과 같은 (term,
    # easy_term) 쌍 판정으로 맞추되, export_simple()이 실제로 내보내는
    # 형태로 변형한 뒤 비교한다.
    deprecated_simple_pairs = set()
    for e in entries:
        if e["status"] != "deprecated" or e["replace_strategy"] == "keep":
            continue
        easy = e["easy_term"]
        if e["replace_strategy"] == "gloss":
            easy = f"{e['term']}({easy})"
        deprecated_simple_pairs.add((e["term"], easy))

    simple_pairs = {(line["term"], line["easy_term"]) for line in simple_lines}
    leaked_simple = deprecated_simple_pairs & simple_pairs
    for term, easy in leaked_simple:
        out.append(Violation(
            "deprecated 유출(simple.jsonl)", f"term={term!r} easy_term={easy!r}",
        ))
    return out


def check_simple_jsonl_contract(entries: list[dict], simple_lines: list[dict]) -> list[Violation]:
    """§4.4 계약: keep/review 행 제외, gloss는 `원어(easy_term)` 형태로 원어 보존."""
    out = []
    by_key = {(e["term"], e["easy_term"]): e for e in entries}
    simple_pairs_seen: dict[str, list[dict]] = {}
    for line in simple_lines:
        simple_pairs_seen.setdefault(line["term"], []).append(line)

    for e in entries:
        if e["status"] in ("deprecated",):
            continue  # 별도 검사(check_deprecated_leakage)가 다룬다
        lines_for_term = simple_pairs_seen.get(e["term"], [])
        if e["replace_strategy"] == "keep":
            for line in lines_for_term:
                if line["easy_term"] == e["easy_term"] or line["term"] == e["term"]:
                    # keep 엔트리는 simple.jsonl 자체에 행이 없어야 한다. 같은
                    # term이 다른(keep 아닌) 엔트리로도 존재할 수 있으므로
                    # easy_term까지 맞아떨어지는 행만 위반으로 잡는다.
                    if line["easy_term"] == e["easy_term"]:
                        out.append(Violation(
                            "simple.jsonl-keep 유출",
                            f"term={e['term']!r} easy_term={e['easy_term']!r}",
                        ))
            continue
        if e["status"] == "review":
            for line in lines_for_term:
                if line["easy_term"] in (e["easy_term"], f"{e['term']}({e['easy_term']})"):
                    out.append(Violation(
                        "simple.jsonl-review 유출",
                        f"term={e['term']!r} easy_term={e['easy_term']!r}",
                    ))
            continue
        if e["replace_strategy"] == "gloss":
            expected = f"{e['term']}({e['easy_term']})"
            matches = [line for line in lines_for_term if line["easy_term"] == expected]
            if not matches and any(line["easy_term"] == e["easy_term"] for line in lines_for_term):
                out.append(Violation(
                    "simple.jsonl-gloss 원어 미보존",
                    f"term={e['term']!r} easy_term={e['easy_term']!r} "
                    f"(기대: {expected!r})",
                ))
    return out


def check_reachability(entries: list[dict], db_path: Path) -> list[Violation]:
    """폐기되지 않은 모든 엔트리가 **실제 조회**(`EasyDict.find_all()`)에서 나오는가.

    `index.json`의 `surface_index` 정적 목록만 봐서는 부족하다 — 표제어와
    변형형이 같은 표면형에서 우연히 만나면(`가설`이 다른 엔트리의 변형형과
    같은 문자열이 되는 경우 등) `surface_index`엔 둘 다 들어 있어도,
    `lookup.py`의 "정확 일치 우선"(exact_ids) 필터가 없으면 자기 표제어가
    아닌 후보가 `priority`만으로 이겨서 **조회에서는 영영 안 나오는** 사고가
    난다(DESIGN.md, `가설`·`거치`·`내방` 실측 사례). 그래서 `EasyDict.
    from_sqlite()`로 실제 트라이를 만들어 각 엔트리의 표제어 자체를
    `find_all()`에 넣어보고, 그 엔트리 id가 결과에 실제로 나오는지 확인한다.
    """
    out = []
    ed = EasyDict.from_sqlite(str(db_path))

    # 같은 term을 공유하는 동의어 후보(§6.8 "문맥별 대안 유지")는 여럿이어도
    # 정상이다 — find_all()은 그중 하나만 승자로 돌려준다. 그러니 "이 특정
    # entry_id가 이겼는가"가 아니라 "이 표제어 자체가 조회에서 조금이라도
    # 나오는가"만 term 단위로 묶어서 확인한다.
    ids_by_term: dict[str, list[int]] = {}
    for e in entries:
        if e["status"] == "deprecated":
            continue
        ids_by_term.setdefault(e["term"], []).append(e["id"])

    for term, ids in ids_by_term.items():
        matches = ed.find_all(term)
        found = any(m.surface == term and m.entry_id in ids for m in matches)
        if not found:
            out.append(Violation(
                "도달 가능성",
                f"term={term!r} (entry ids={ids}) — find_all({term!r})에서 "
                "이 표제어의 어떤 후보도 안 나옴(변형형 충돌 등으로 승자가 다른 엔트리에게 감)",
            ))
    return out


def check_protected_entry_wins(entries: list[dict], index_doc: dict) -> list[Violation]:
    """같은 표면형에서 risk가 낮은 후보가 risk가 높은 후보를 이기면 안 된다."""
    out = []
    by_id = {e["id"]: e for e in entries}
    surface_index: dict[str, list] = index_doc.get("surface_index", {})
    for surface, ids in surface_index.items():
        if len(ids) < 2:
            continue
        cands = [by_id[i] for i in ids if i in by_id]
        if len(cands) < 2:
            continue
        winner = cands[0]
        winner_rank = _RISK_WINNER_RANK.get(winner["risk_level"], len(_RISK_WINNER_RANK))
        for other in cands[1:]:
            other_rank = _RISK_WINNER_RANK.get(other["risk_level"], len(_RISK_WINNER_RANK))
            if other_rank < winner_rank:
                out.append(Violation(
                    "보호 엔트리 승리",
                    f"surface={surface!r}: 승자 id={winner['id']}(risk={winner['risk_level']}) "
                    f"< id={other['id']}(risk={other['risk_level']})가 더 보호 대상인데 짐",
                ))
    return out


def check_caution_free_of_review_notes(index_doc: dict) -> list[Violation]:
    """배포 색인(`index.json`)의 `caution`에 검수 메모 신호가 남아 있으면 안 된다.

    2026-09-06 이전에는 `entries.caution`이 사용자 노출용 안내문과 검수자
    내부 메모(예: "2026-08-30 검수: ... 승격 (docs/consumer-overlap-policy.md
    §3.2)")를 섞어 담고 있었다 — `caution`은 프런트(`TermLookupPopover`)와
    LLM 프롬프트(`DictionaryContextLines`)에 그대로 노출되므로 이는 정보
    유출이었다. `review_notes.classify_caution()`이 이 신호를 `review_note`로
    옮기고, `build.py`가 잉제스트 시점에 그 함수를 호출해 이 상태가 앞으로도
    유지되게 한다. 이 검사는 그 게이트가 실제로 지켜지는지 산출물에서 다시
    확인한다 — 우회 경로(수동 UPDATE, 마이그레이션 누락, `review_notes.py`
    수정 중 회귀 등)가 생겨도 여기서 잡는다.

    `NEEDS_CONFIRMATION_MARKER`(`[확인 필요]`)가 있는 caution은 예외다 —
    build.py가 이 마커를 보고 status=deprecated를 강제하는 제어 신호라
    `classify_caution()`도 그대로 보존하기 때문이다.
    """
    out = []
    for entry_id, entry in index_doc.get("entries", {}).items():
        caution = entry.get("c")
        if not caution or NEEDS_CONFIRMATION_MARKER in caution:
            continue
        if REVIEW_NOTE_PATTERN.search(caution):
            out.append(Violation(
                "caution에 검수 메모 잔존(index.json)",
                f"entry_id={entry_id} caution={caution!r}",
            ))
    return out


def check_definition_review_marks(index_doc: dict) -> list[Violation]:
    """배포 색인(`index.json`)의 `v`(뜻풀이 검수 이력) 규약 (2026-09-23).

    두 가지만 본다.

    - **값은 `"reviewed"` 하나뿐이다.** Kotlin의 `DefinitionReviewStatus`(core)는
      키가 없으면 UNVERIFIED, `"reviewed"`면 REVIEWED다. **모르는** 값이면
      기동을 거부하지만, `"unverified"`·null·빈 문자열은 조용히 UNVERIFIED로
      흡수한다(`DictionaryEntry.kt`의 `ofWire`/`optional` — `unverified`도
      선언된 wire 값이라 예외를 던지지 않는다) — 조용한 흡수도 이 저장소의
      규약 위반이라 여기서 잡는다. 표시가 없다는 뜻으로 `v: null`을 쓰는 것도
      규약 위반이다(키 자체가 없어야 한다).
    - **뜻풀이가 빈 엔트리에 `v`가 있으면 안 된다.** "이 뜻풀이를 사람이 읽었다"는
      표시인데 읽을 뜻풀이가 없다는 모순이고, R3 프롬프트가 빈 문자열을 생성
      재료로 집어 들 수 있다. `export.export_index()`가 애초에 이런 조합을 만들지
      않지만(`definition_review.is_definition_reviewed()`), 이 검사는 그 게이트를
      우회하는 경로(수동 JSON 편집 등)가 생겨도 산출물에서 다시 잡는다.
    """
    out = []
    for entry_id, entry in index_doc.get("entries", {}).items():
        if "v" not in entry:
            continue
        mark = entry["v"]
        if mark != DEFINITION_REVIEW_MARK:
            out.append(Violation(
                "v 값 규약(index.json)",
                f"entry_id={entry_id} v={mark!r} — 허용값은 {DEFINITION_REVIEW_MARK!r} 하나뿐이고, "
                "표시가 없으면 키 자체가 없어야 한다",
            ))
            continue
        definition = entry.get("d")
        if not isinstance(definition, str) or not definition.strip():
            out.append(Violation(
                "빈 뜻풀이에 v 표시(index.json)",
                f"entry_id={entry_id} d={definition!r} — 검수했다는 표시인데 검수할 뜻풀이가 없다",
            ))
    return out


def check_definition_review_db_index_consistency(
    entries: list[dict], index_doc: dict,
) -> list[Violation]:
    """DB(정본)와 배포 색인(`index.json`)의 `v` 판정이 서로 어긋나지 않는가 (2026-09-23).

    두 산출물은 같은 판정 규칙(`definition_review.is_definition_reviewed()`)을
    따라야 하는데, 수동 UPDATE·마이그레이션 누락·재익스포트 전 DB만 고친
    경우 등으로 둘이 갈라질 수 있다. entry_id 단위로 DB 쪽 판정과 색인 쪽
    `v` 존재 여부를 맞대본다 — 한쪽에만 있으면(양방향) 위반이다.

    deprecated 엔트리는 검수 완료 표시가 있어도 애초에 색인에서 빠지므로
    (`export.export_index()`의 `_ACTIVE_ENTRIES_SQL`이 `status != 'deprecated'`만
    싣는다) 이 검사 대상에서 제외한다 — 그렇지 않으면 정상적으로 폐기된
    엔트리를 매번 오탐으로 잡는다.
    """
    out = []
    index_entries = index_doc.get("entries", {})
    for e in entries:
        if e["status"] == "deprecated":
            continue
        entry_id = e["id"]
        db_reviewed = is_definition_reviewed(
            e.get("definition"), e.get("definition_reviewed_at"), e.get("definition_reviewed_by"),
        )
        index_entry = index_entries.get(str(entry_id))
        index_reviewed = bool(index_entry) and index_entry.get("v") == DEFINITION_REVIEW_MARK
        if db_reviewed and not index_reviewed:
            out.append(Violation(
                "DB-색인 v 불일치",
                f"entry_id={entry_id} term={e['term']!r}: DB는 검수 완료 조건을 만족하는데 "
                f"색인에 v={DEFINITION_REVIEW_MARK!r}가 없다",
            ))
        elif index_reviewed and not db_reviewed:
            out.append(Violation(
                "DB-색인 v 불일치",
                f"entry_id={entry_id} term={e['term']!r}: 색인은 v={DEFINITION_REVIEW_MARK!r}인데 "
                "DB는 검수 완료 조건을 만족하지 않는다",
            ))
    return out


def check_malformed_reviewed_at(entries: list[dict]) -> list[Violation]:
    """`definition_reviewed_at` 형식 오류 검사 (2026-09-23).

    `definition_review.is_definition_reviewed()`는 `reviewed_at`이 ISO-8601
    (`REVIEWED_AT_PATTERN`)이 아니면 조용히 "미검수"로 fail-close한다(따옴표
    없이 쓴 날짜가 SQLite 산술식으로 평가되는 사고 등) — 검수자가 실제로
    값을 채웠는데(`reviewed_at`/`reviewed_by`가 둘 다 있음) 형식이 틀려
    조용히 묻히면 아무도 그 오타를 못 본다. 여기서 명시적으로 드러낸다.

    `reviewed_by`가 없는 경우는 다루지 않는다 — 그 자체로 이미 다른 규칙
    (검수자 미기재)으로 검수 미완료이므로, 여기서까지 이중 보고하지 않는다.
    """
    out = []
    for e in entries:
        reviewed_at = e.get("definition_reviewed_at")
        reviewed_by = e.get("definition_reviewed_by")
        if not (isinstance(reviewed_at, str) and reviewed_at.strip()):
            continue
        if not (isinstance(reviewed_by, str) and reviewed_by.strip()):
            continue
        if not REVIEWED_AT_PATTERN.match(reviewed_at.strip()):
            out.append(Violation(
                "definition_reviewed_at 형식 오류",
                f"id={e['id']} term={e['term']!r} definition_reviewed_at={reviewed_at!r} "
                "— ISO-8601(YYYY-MM-DD 또는 YYYY-MM-DDTHH:MM:SSZ)이 아니라 "
                "검수 표시가 조용히 무시된다",
            ))
    return out


def check_source_attribution(conn: sqlite3.Connection) -> list[Violation]:
    """등록된 원천 중 `export.SOURCE_TRUST_TIER`에 없는 원천이 엔트리를 소유하는가.

    예문 전용 원천(`--source-role examples`)은 build.py 설계상 `sources`에
    아예 등록되지 않는다(DESIGN.md §5.5(7)) — 그래서 정상 동작하면 항상
    `sources` 테이블에는 `SOURCE_TRUST_TIER`에 등록된 5개 원천만 있어야
    한다. `role=examples` 분기가 사라지면 그 CSV가 일반(primary) 경로를
    타서 **새 원천으로 등록되고 엔트리까지 소유**하게 된다(그게 원래
    사고였다 — 318건의 source_id를 가져갔다). `SOURCE_TRUST_TIER`는
    `export.py`가 이미 "새 원천을 추가하면 반드시 이 표에 등급을 적어야
    한다"고 요구하는 표라, 그 표에 없는 원천이 엔트리를 소유한다는 것
    자체가 "등록 안 된 원천이 조용히 끼어들었다"는 신호로 재사용할 수
    있다 — 새 순위표를 만들지 않는다.

    ### 겨냥이 틀렸던 첫 버전 (기록으로 남긴다)

    이 함수의 첫 버전은 "모든 원천이 엔트리를 1건 이상 소유하는가"였다.
    말이 되는 것 같았지만 **실제 회귀를 못 잡았다** — `build.py`의
    `role=examples` 분기를 지우고 재빌드해서 확인해 보니 위반 0건이
    나왔다. 이유: 분기가 없으면 예문 CSV가 그냥 일반(primary) 경로를
    타서 **정상적으로 원천 등록되고 엔트리도 정상 소유**해버린다 —
    그게 바로 원래 사고인데, "소유는 하니까" 그 검사를 통과해 버린
    것이다. 즉 첫 버전은 "무언가 나쁜 일이 벌어졌을 때 참이 되는
    조건"이 아니라 "무언가 나쁜 일이 벌어져도 참으로 남는 조건"을
    검사하고 있었다. `SOURCE_TRUST_TIER`(등록된 원천의 화이트리스트)로
    바꾸고 나서야 정확히 잡혔다.

    **교훈**: 완료 조건("코드를 되돌려서 정말 실패하는지 확인하라")을
    안 지켰으면 이 결함 있는 첫 버전이 그대로 저장소에 남아 초록불을
    켜는 장식 검사가 됐을 것이다. 검사를 다 짠 뒤에도 반드시 **그
    검사가 막으려는 결함을 실제로 재현해서 빨간불이 뜨는지** 봐야 한다
    — 통과하는 검사를 보고 안심하는 것과, 실패해야 할 때 실제로
    실패하는 걸 본 것은 다르다.
    """
    out = []
    for row in conn.execute(
        "SELECT s.id, s.code, (SELECT count(*) FROM entries e WHERE e.source_id = s.id) as n "
        "FROM sources s"
    ):
        source_id, code, owned = row
        if code not in SOURCE_TRUST_TIER and owned > 0:
            out.append(Violation(
                "엔트리 귀속 불변",
                f"source_id={source_id} code={code!r}(SOURCE_TRUST_TIER 미등록)가 "
                f"엔트리 {owned}건을 소유 — 예문 전용 원천이 일반 경로로 새고 있을 수 있다",
            ))
    return out


def run(db_path: Path, index_json_path: Path, simple_jsonl_path: Path) -> list[Violation]:
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    entries = _load_entries(conn)
    with index_json_path.open("r", encoding="utf-8") as f:
        index_doc = json.load(f)
    simple_lines = _simple_jsonl_lines(simple_jsonl_path)

    violations: list[Violation] = []
    violations += check_substitute_review_coexistence(entries)
    violations += check_readability_range(entries)
    violations += check_deprecated_leakage(entries, index_doc, simple_lines)
    violations += check_simple_jsonl_contract(entries, simple_lines)
    violations += check_reachability(entries, db_path)
    violations += check_protected_entry_wins(entries, index_doc)
    violations += check_caution_free_of_review_notes(index_doc)
    violations += check_definition_review_marks(index_doc)
    violations += check_definition_review_db_index_consistency(entries, index_doc)
    violations += check_malformed_reviewed_at(entries)
    violations += check_source_attribution(conn)
    conn.close()
    return violations


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--db", default=str(DEFAULT_DB_PATH))
    p.add_argument("--index-json", default=str(DEFAULT_INDEX_JSON_PATH))
    p.add_argument("--simple-jsonl", default=str(DEFAULT_SIMPLE_JSONL_PATH))
    args = p.parse_args(argv)

    db_path = Path(args.db)
    index_json_path = Path(args.index_json)
    simple_jsonl_path = Path(args.simple_jsonl)
    for path in (db_path, index_json_path, simple_jsonl_path):
        if not path.exists():
            print(f"오류: 산출물이 없습니다: {path} (먼저 build.py를 실행했는지 확인)", file=sys.stderr)
            return 2

    violations = run(db_path, index_json_path, simple_jsonl_path)

    print("=" * 64)
    print("층위 1 불변식 검사 (docs/inspection-plan.md)")
    print("=" * 64)
    if not violations:
        print("불변식 위반 0건.")
    else:
        by_check: dict[str, list[Violation]] = {}
        for v in violations:
            by_check.setdefault(v.check, []).append(v)
        for check, vs in by_check.items():
            print(f"[실패] {check}: {len(vs)}건")
            for v in vs[:10]:
                print(f"    · {v.detail}")
            if len(vs) > 10:
                print(f"    ... 외 {len(vs) - 10}건")
    print("-" * 64)
    print("[미검사] 층위 2(코퍼스 통과)는 이 검사 밖 — tools/audit_corpus.py가 따로 본다 (check.sh 4/4 단계)")
    print("[미검사] 층위 3(의미 검증)은 별도 도구 — tools/detect_homonym_risk.py, krdict에 없는 573건은 검사 자체 불가")
    print("=" * 64)

    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
