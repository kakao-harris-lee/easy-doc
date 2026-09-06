"""`caution`에 섞여 들어간 내부 검수 메모를 `review_note`로 분리하는 마이그레이션 (2026-09-06).

## 배경

`entries.caution`은 원래 "치환 시 사용자에게 보여줄 주의사항" 한 가지 용도였다
(schema.sql, DESIGN.md §3.2). 그런데 실제로 적재된 474건 중 다수가 검수자가 빌드/검수
과정에서 남긴 내부 메모("2026-08-30 검수: ... 채택 (consumer-overlap-policy §3.2)")를
같은 컬럼에 섞어 썼다. `caution`은 프런트(`TermLookupPopover`)가 사용자에게 그대로
보여주고, `DictionaryContextLines`가 LLM 프롬프트에도 그대로 싣는 필드라서, 이 내부
메모가 그대로 사용자와 LLM에 노출되는 정보 유출이었다.

이 모듈의 `classify_caution()`은 두 곳에서 쓰인다 — ⑴ 기존 `dist/easy_dict.sqlite3`
(정본)에 쌓인 474건을 정리하는 **일회성 마이그레이션**(`migrate_existing_cautions()`,
이 파일 하단의 CLI), ⑵ `build.py`의 `row_to_entries()`가 CSV의 `caution` 원본값을
Entry로 담기 **직전에** 호출하는 **상시 파이프라인 불변식**. ⑵가 없으면 다음 빌드가
같은 CSV(`data/raw/welfare_seed_*.csv`, krdict 원천 등)를 다시 읽을 때 검수 메모가
그대로 `caution`에 재적재되어 유출이 되풀이된다 — 그래서 이 규칙은 "한 번 고치고
끝"이 아니라 매 빌드마다 도는 게이트다. `tools/check_invariants.py`가 산출물
(`easy_dict.index.json`)의 `caution`에 이 패턴이 다시 나타나는지도 확인해 회귀를
잡는다.

## 분류 규칙

`REVIEW_NOTE_PATTERN`에 매칭되면(날짜 `20\\d\\d-\\d\\d-\\d\\d`, 단어 검수/승격/비활성화/
채택, `§`, `docs/`, `consumer-overlap`, `골든 코퍼스`, `내장 목록`, 참고용/프롬프트에)
그 문자열은 내부 검수 메모로 판정한다. 판정된 caution은 세 갈래로 나뉜다:

- **kept**: 어디에도 매칭되지 않음 — 순수 사용자 안내문. 그대로 둔다.
- **moved**: 전체가 검수 메모임 — `review_note`로 옮기고 `caution`은 null이 된다.
  단일 문장이거나(자를 경계가 없음), 문장을 갈라도 매칭 안 되는 문장이 하나도
  없는 경우(=사용자 안내로 볼 문장이 없음)가 여기 해당한다.
- **split**: 매칭되는 문장(들)과 안 되는 문장(들)이 섞인 "혼합형" — **위치를
  가정하지 않는다.** 문장을 매칭 여부로 두 그룹으로 걸러 각 그룹을 원래
  순서대로 이어붙인다: 안 되는 문장들 -> `caution`, 되는 문장들 -> `review_note`.
  안내가 메모보다 먼저 오든(id 263 "동형이의 주의: ... 뜻이다. [메모]") 나중에
  오든(id 1926 "[메모] 돈이 아닌 의무의 면제(...)면 ...으로 푼다.") 둘 다 잡는다
  — 실측 결함(id 1926 '면제', 2024 '분기')이 메모-먼저형이었다: 예전 구현은
  "첫 문장이 매칭되면 안내가 없다"고 가정해 뒤에 오는 진짜 안내까지 통째로
  review_note로 넘겼다.

### "[자동 수집, 검토 필요]" 같은 파이프라인 보일러플레이트는 split하지 않는다

krdict 원천에서 자동 수집된 306건은 `"[자동 수집, 검토 필요] 한국어기초사전 정의를
그대로 옮김. word_grade=... (...). ... 승격."`처럼 마지막 문장에만 `승격` 같은 키워드가
있어, 매칭 안 되는 문장만 모으면 앞 문장들이 "사용자 안내"로 오분류된다. 그러나 그
안내 후보 자체가 `[...]` 로 시작하는 내부 파이프라인 태그라 사용자 안내가 아니다.
그래서 안내 후보가 `[`로 시작하면 split을 포기하고 전체를 `moved`로 처리한다(실측:
이 방어가 없으면 315건이 split되는데 그중 306건이 이 보일러플레이트였다 — 실제로
"안내+메모"가 섞인 진짜 혼합형은 9건 + 메모-먼저형 2건이었다).

### human pass 플래그

매칭 안 되는 문장이 하나도 없는 다중 문장 caution(예: "2026-08-30 검수: ... 반려 —
... gloss 유지 (docs/...)")은 진짜로 안내+메모 혼합인데 안내 문장에도 우연히
키워드가 섞여 걸러지지 않았을 수도 있다. 자동으로는 판단할 수 없으므로 `moved`로
처리하되 `flagged_for_human=True`로 표시해 사람이 한 번 더 훑어볼 수 있게 한다.
문장 경계가 아예 없는 단일 문장 caution(길이 <= 1)도 같은 이유로 항상 표시한다
— 문장 부호 경계 확장(§소괄호)으로도 못 잡는 "안내+메모가 한 문장 안에 붙어 있는"
경우가 남아 있을 수 있어, 자동 판단이 조용히 안내를 삼키는 사고를 막는다.
"""
from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

from .models import NEEDS_CONFIRMATION_MARKER

_SCHEMA_SQL_PATH = Path(__file__).resolve().parents[2] / "schema" / "schema.sql"

# 검수 메모 판정 규칙: 날짜, 검수/승격/비활성화/채택, §, docs/, consumer-overlap,
# 골든 코퍼스, 내장 목록, 그리고 참고용/프롬프트에(§4.3 프롬프트 제외 안내 — 예:
# id 1783 "...기관입니다(참고용, 뜻풀이는 프롬프트에 넣지 않음)").
REVIEW_NOTE_PATTERN = re.compile(
    r"20\d\d-\d\d-\d\d|검수|승격|비활성화|채택|§|docs/|consumer-overlap|골든 코퍼스|내장 목록"
    r"|참고용|프롬프트에"
)

# 마침표/느낌표/물음표 하나. 뒤에 공백이 오거나 문자열 끝이면 문장 경계로 본다.
_SENTENCE_END_CHARS = ".!?"


def _split_sentences(text: str) -> list[str]:
    """문장 경계로 나눈다. 마침표류 뒤 공백은 항상 경계지만, **닫는 괄호 ")" 뒤
    공백은 그 앞 조각 자체가 REVIEW_NOTE_PATTERN에 매칭될 때만** 경계로 인정한다.

    실측된 결함(id 1926 '면제', 2024 '분기'): "...채택 (docs/....md §3.2) 돈이
    아닌 의무의 면제(...)..."처럼 메모가 마침표 없이 ")"로 끝나고 바로 사용자
    안내 문장이 이어지는 경우가 있었다 -- [.!?] 경계만으로는 이 둘을 나누지
    못해 안내 문장까지 통째로 review_note로 넘어갔다.

    다만 ")" 뒤 공백을 **무조건** 경계로 인정하면 다른 사고가 난다(id 2185
    '공지'): "...공지(公知, 알리다)다. '빈 땅'(空地) 뜻의 두 항목은..."처럼
    문장 **중간**의 짧은 괄호주석("(空地)")도 잘라버려 안내 문장이 반토막
    난다. 그래서 ")"는 그 앞부분(직전 문장 경계부터 이 ")"까지)이 검수 메모
    신호를 이미 담고 있을 때만 -- 즉 그 ")"가 실제로 메모 절을 닫는 괄호일
    때만 -- 경계로 인정한다. "(空地)"처럼 순수 괄호주석은 REVIEW_NOTE_PATTERN에
    안 걸리므로 자동으로 경계에서 빠진다.
    """
    text = text.strip()
    if not text:
        return []

    sentences: list[str] = []
    start = 0
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in _SENTENCE_END_CHARS:
            j = i + 1
            if j == n or text[j].isspace():
                sentences.append(text[start:j].strip())
                while j < n and text[j].isspace():
                    j += 1
                start = j
                i = j
                continue
        elif ch == ")":
            j = i + 1
            if j < n and text[j].isspace() and REVIEW_NOTE_PATTERN.search(text[start:j]):
                sentences.append(text[start:j].strip())
                while j < n and text[j].isspace():
                    j += 1
                start = j
                i = j
                continue
        i += 1

    if start < n:
        sentences.append(text[start:].strip())

    return [s for s in sentences if s]


# split 후보의 안내 부분(=caution으로 남길 부분, 매칭 안 된 문장들을 원래 순서로
# 이어붙인 것)이 이 접두사로 시작하면, 그 자체가 이미 내부 파이프라인 태그이므로
# ("[자동 수집, 검토 필요]" 등) split을 포기하고 전체를 review_note로 옮긴다.
_PIPELINE_TAG_PREFIX = "["


def is_review_note(text: str) -> bool:
    """caution 문자열 어딘가에 검수 메모 신호가 있는지."""
    return bool(REVIEW_NOTE_PATTERN.search(text))


@dataclass(slots=True)
class CautionSplit:
    """caution 하나를 분류한 결과."""

    caution: str | None
    review_note: str | None
    action: str  # "kept" | "moved" | "split"
    flagged_for_human: bool = False


def classify_caution(caution: str | None) -> CautionSplit:
    """caution 하나를 (caution, review_note, action) 으로 분류한다.

    caution이 None이면 아무 판정도 하지 않는다(호출자가 "caution IS NOT NULL"로
    걸러서 넘기는 것을 기대하지만, 방어적으로 kept를 돌려준다).
    """
    if not caution:
        return CautionSplit(caution=caution, review_note=None, action="kept")

    if NEEDS_CONFIRMATION_MARKER in caution:
        # build.py의 classify()가 이 마커를 entry.caution에서 찾아 status를
        # 'deprecated'로 강제한다(§제어 신호). 이 함수가 마커를 review_note로
        # 옮겨 caution을 비워버리면 그 강제 로직이 신호를 잃는다 — 그래서
        # 이 마커가 있으면 다른 규칙보다 우선해 caution 전체를 그대로 둔다.
        return CautionSplit(caution=caution, review_note=None, action="kept")

    if not REVIEW_NOTE_PATTERN.search(caution):
        return CautionSplit(caution=caution, review_note=None, action="kept")

    sentences = _split_sentences(caution)

    if len(sentences) <= 1:
        # 자를 문장 경계가 없다 — 전체가 검수 메모다. 다만 문장 경계 판정이
        # 놓친 "안내+메모가 한 문장 안에 붙어 있는" 경우일 수도 있어(문장
        # 부호 경계 확장으로도 못 잡을 수 있다) 무조건 사람이 확인할 수
        # 있게 표시한다 — 자동 판단이 조용히 안내를 삼키는 사고를 막는다.
        return CautionSplit(caution=None, review_note=caution, action="moved", flagged_for_human=True)

    # 매칭되는 문장(검수 메모 후보)과 안 되는 문장(사용자 안내 후보)을 원래
    # 순서를 보존한 채로 나눈다 — **위치를 가정하지 않는다.** 실측 결함
    # (id 1926 '면제', 2024 '분기')은 메모가 **먼저** 오고 안내가 뒤에 온다
    # ("...승격 (...§3.2) 돈이 아닌 의무의 면제(...)면 ..."). 이전 구현은
    # "첫 문장이 매칭되면 안내가 없다"고 가정해 이런 메모-먼저형에서 뒤에
    # 오는 안내까지 통째로 review_note로 넘겼다. 이제는 매칭 여부만으로
    # 문장을 두 그룹으로 걸러서 순서와 무관하게 안내를 되살린다.
    guidance_sentences = [s for s in sentences if not REVIEW_NOTE_PATTERN.search(s)]
    memo_sentences = [s for s in sentences if REVIEW_NOTE_PATTERN.search(s)]

    if not guidance_sentences:
        # 모든 문장이 매칭됨 — 안내로 볼 만한 문장이 하나도 없다. 진짜 혼합형일
        # 가능성을 배제할 수 없으므로(예: 짧은 안내 문장이 우연히 키워드를
        # 포함) 사람 확인용으로 표시한다.
        return CautionSplit(caution=None, review_note=caution, action="moved", flagged_for_human=True)

    guidance = " ".join(guidance_sentences)

    if guidance.startswith(_PIPELINE_TAG_PREFIX):
        # 안내 후보 자체가 "[자동 수집, 검토 필요]" 같은 내부 파이프라인
        # 태그로 시작한다 — 사용자 안내가 아니므로 split하지 않고 전체를 옮긴다.
        return CautionSplit(caution=None, review_note=caution, action="moved")

    memo = " ".join(memo_sentences)
    return CautionSplit(caution=guidance, review_note=memo, action="split")


@dataclass(slots=True)
class MigrationReport:
    """마이그레이션 1회 실행 결과 — 최종 보고용 카운트와 id 목록."""

    total_with_caution: int = 0
    kept_ids: list[int] = field(default_factory=list)
    moved_ids: list[int] = field(default_factory=list)
    split_ids: list[int] = field(default_factory=list)
    human_pass_ids: list[int] = field(default_factory=list)

    def summary(self) -> str:
        return (
            f"caution 보유 {self.total_with_caution}건 -> "
            f"kept={len(self.kept_ids)}, moved={len(self.moved_ids)}, "
            f"split={len(self.split_ids)} (human_pass={len(self.human_pass_ids)})"
        )


def ensure_review_note_column(conn: sqlite3.Connection) -> bool:
    """entries.review_note 컬럼이 없으면 ALTER TABLE로 추가한다.

    schema.sql은 `CREATE TABLE IF NOT EXISTS`라 이미 만들어진 DB(이 컬럼이 생기기
    전에 빌드된 dist/easy_dict.sqlite3 포함)에는 새 컬럼이 자동으로 반영되지
    않는다. 이 함수가 그 간극을 메운다 — 재실행해도 안전하다(이미 있으면 아무
    것도 하지 않는다).

    Returns:
        컬럼을 새로 추가했으면 True, 이미 있었으면 False.
    """
    columns = {row[1] for row in conn.execute("PRAGMA table_info(entries)")}
    if "review_note" in columns:
        return False
    conn.execute("ALTER TABLE entries ADD COLUMN review_note TEXT")
    conn.commit()
    return True


def ensure_v_entry_full_view(conn: sqlite3.Connection, schema_sql: Path = _SCHEMA_SQL_PATH) -> None:
    """`v_entry_full` 뷰가 review_note를 포함한 최신 정의인지 보장한다.

    schema.sql은 이제 이 뷰를 `DROP VIEW IF EXISTS` + `CREATE VIEW`(IF NOT
    EXISTS 없이)로 선언한다 — 뷰는 테이블과 달리 데이터가 없는 파생 정의라
    다시 실행할 때마다 지우고 새로 만들어도 안전하다(schema.sql 주석 참고).
    그래서 이 함수는 schema.sql 전체를 그대로 재실행하기만 하면 된다 —
    `entries` 등 테이블 쪽 `CREATE TABLE IF NOT EXISTS`·`INSERT OR IGNORE`는
    이미 있으면 아무 것도 바꾸지 않으므로 재실행 안전성이 깨지지 않는다.

    review_note 컬럼이 없던 시절 만들어진 DB(이 컬럼 없이 뷰도 옛 컬럼
    목록으로 이미 존재)에 `ensure_review_note_column()`으로 컬럼만 추가해도
    뷰는 그대로라 `SELECT * FROM v_entry_full`에 review_note가 나오지
    않는다 — `ensure_review_note_column(conn)`을 먼저 호출해 컬럼이 있어야
    한다(없으면 뷰 재생성이 "no such column: e.review_note"로 실패한다).
    """
    conn.executescript(schema_sql.read_text(encoding="utf-8"))


def migrate_existing_cautions(conn: sqlite3.Connection) -> MigrationReport:
    """entries.caution 전체를 재분류해 review_note를 채운다.

    `ensure_review_note_column(conn)`을 먼저 호출한 뒤 이 함수를 불러야 한다
    (컬럼이 없으면 UPDATE가 실패한다). 트랜잭션 1회로 전부 반영한다.

    review_note는 **덮어쓰지 않고 이어붙인다**(`COALESCE(review_note ||
    char(10), '') || ?`) — 이미 review_note가 있는 엔트리(예: 이전 실행에서
    옮겨진 메모, 또는 build.py의 잉제스트 경로가 먼저 채운 값)에 다시 이
    마이그레이션을 돌리면, 기존 값을 지우지 않고 새로 분류된 메모를 줄바꿈
    으로 붙인다. caution이 이미 None인 엔트리는 애초 이 함수가 대상으로
    삼는 "caution IS NOT NULL" 쿼리에 걸리지 않으므로, 이 append 규칙은
    "caution이 남아 있는데 review_note도 이미 있는" 드문 경우(예: 사람이
    review_note를 수동으로 먼저 채워 둔 뒤 다시 분류를 돌리는 경우)를
    안전하게 만든다.
    """
    report = MigrationReport()
    rows = conn.execute(
        "SELECT id, caution FROM entries WHERE caution IS NOT NULL ORDER BY id"
    ).fetchall()
    report.total_with_caution = len(rows)

    with conn:
        for entry_id, caution in rows:
            result = classify_caution(caution)
            if result.action == "kept":
                report.kept_ids.append(entry_id)
                continue

            conn.execute(
                "UPDATE entries SET caution = ?, "
                "review_note = COALESCE(review_note || char(10), '') || ? "
                "WHERE id = ?",
                (result.caution, result.review_note, entry_id),
            )
            if result.action == "moved":
                report.moved_ids.append(entry_id)
            else:
                report.split_ids.append(entry_id)
            if result.flagged_for_human:
                report.human_pass_ids.append(entry_id)

    return report


if __name__ == "__main__":
    import argparse
    from pathlib import Path

    from . import export as export_mod

    parser = argparse.ArgumentParser(
        description="entries.caution 중 내부 검수 메모를 review_note로 분리하고 dist/*를 재생성한다."
    )
    parser.add_argument("--db", type=Path, default=Path("dist/easy_dict.sqlite3"))
    parser.add_argument("--out", type=Path, default=Path("dist"))
    parser.add_argument(
        "--no-export", action="store_true", help="SQLite만 갱신하고 dist/*.json은 다시 만들지 않는다."
    )
    args = parser.parse_args()

    if not args.db.exists():
        raise SystemExit(f"DB가 없습니다: {args.db}")

    _conn = sqlite3.connect(str(args.db))
    try:
        added = ensure_review_note_column(_conn)
        print(f"review_note 컬럼 {'추가함' if added else '이미 있음'}")
        ensure_v_entry_full_view(_conn)
        print("v_entry_full 뷰 재생성함")

        _report = migrate_existing_cautions(_conn)
        print(_report.summary())
        print(f"split ids ({len(_report.split_ids)}): {_report.split_ids}")
        print(f"human_pass ids ({len(_report.human_pass_ids)}): {_report.human_pass_ids}")

        if not args.no_export:
            paths = export_mod.export_all(_conn, args.out)
            for p in paths:
                print(f"{p}  ({p.stat().st_size:,} bytes)")
    finally:
        _conn.close()
