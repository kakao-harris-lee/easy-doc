"""뜻풀이(definition)의 사람 검수 이력 (2026-09-23).

## 왜 필요한가

`entries.definition`은 대부분 자동으로 채워졌다 — krdict 원천 306건은
`"[자동 수집, 검토 필요] 한국어기초사전 정의를 그대로 옮김"`처럼 사람이 읽지
않은 문장이다. 그런데 easy-doc(Kotlin)의 R3 생성 컨텍스트와 R6 설명 패널은 이
뜻풀이를 **LLM 생성 재료와 사용자 노출 문구**로 쓴다. 그래서 "이 뜻풀이는 사람이
읽고 써도 된다고 판단한 것인가"를 엔트리마다 따로 들고 있어야 한다.

`status='active'`는 그 답이 못 된다 — active는 "자동 치환에 써도 된다"는
분류일 뿐 "누가 이 문장을 읽었다"는 사실이 아니다. `risk_level`·
`replace_strategy`·원천(seed)·`review_note`도 마찬가지다. review_note는 빌드
이력이고, 실제로 2026-09-11/09-12 검수 SQL은 뜻풀이를 고치면서도 `"사람 검수
완료 표시는 변경하지 않음"`이라고 스스로 못 박았다. 이 신호들을 검수 완료로
자동 승격하면 **아무도 읽지 않은 문장이 LLM 프롬프트에 들어간다**
(docs/reports/2026-09-21-r3-implementation.md 「리뷰 리스크」).

## 규칙 (한 곳에만 있다)

`definition_reviewed_at`(시각)과 `definition_reviewed_by`(사람)가 **둘 다** 차
있고 `definition`이 비어 있지 않을 때만 검수된 뜻풀이다. 시각만 있고 사람이
없으면 출처가 아니고, 뜻풀이가 없으면 애초에 검수할 대상이 없다.
`export.export_index()`가 이 판정으로 `easy_dict.index.json`에 `v="reviewed"`
(`models.DEFINITION_REVIEW_MARK`)를 싣고, 표시가 없는 엔트리에는 `v` 키를
아예 만들지 않는다 — 표시가 하나도 없으면 산출물은 표시 도입 전과 바이트가
같다.

두 컬럼을 채우는 경로는 사람이 쓰는 검수 SQL(`data/reviews/*.sql`) 하나다.
빌드 파이프라인(`build.py`)은 이 컬럼에 아무 값도 쓰지 않는다.

**의존성 없음**: 표준 라이브러리(`sqlite3`, `re`)만 쓴다.
"""
from __future__ import annotations

import re
import sqlite3

# 사람이 채우는 컬럼. 순서는 ALTER TABLE 적용 순서이자 문서상의 (시각, 사람) 순서다.
DEFINITION_REVIEW_COLUMNS: tuple[str, ...] = (
    "definition_reviewed_at",
    "definition_reviewed_by",
)

# definition_reviewed_at의 정본 형식(ISO-8601): `YYYY-MM-DD` 또는
# `YYYY-MM-DDTHH:MM:SSZ` (schema.sql 컬럼 주석·검수 SQL 틀과 같은 규칙). 이
# 형식이 아니면 검수 완료로 보지 않는다(fail closed) — 따옴표 없이
# `2026-09-30`처럼 쓰면 SQLite가 이를 산술식(2026-9-30=1987)으로 평가해
# 정수를 저장하는데, 그 값은 애초에 문자열이 아니라서 여기서도 걸린다.
REVIEWED_AT_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2}Z)?$")


def is_definition_reviewed(
    definition: str | None,
    reviewed_at: str | None,
    reviewed_by: str | None,
) -> bool:
    """이 뜻풀이가 "사람이 읽고 생성 재료로 승인한" 것인가.

    세 값이 모두 공백이 아닌 문자열이고, `reviewed_at`이 ISO-8601
    (`REVIEWED_AT_PATTERN`)일 때만 참이다(모듈 docstring 「규칙」). 형식이
    어긋나면 조용히 "미검수"로 fail-close한다 — `tools/check_invariants.py`가
    형식은 있지만 이 패턴에 안 맞는 값을 별도로 잡아 오타가 조용히 묻히지
    않게 한다.
    """
    if not all(
        isinstance(value, str) and value.strip() != ""
        for value in (definition, reviewed_at, reviewed_by)
    ):
        return False
    return bool(REVIEWED_AT_PATTERN.match(reviewed_at.strip()))


def ensure_definition_review_columns(conn: sqlite3.Connection) -> bool:
    """`entries`에 두 컬럼이 없으면 `ALTER TABLE`로 추가한다.

    `schema.sql`은 `CREATE TABLE IF NOT EXISTS`라 이미 만들어진 DB(이 컬럼이
    생기기 전에 빌드된 `dist/easy_dict.sqlite3` 포함)에는 새 컬럼이 반영되지
    않는다 — `review_notes.ensure_review_note_column()`과 같은 처방으로 그
    간극을 메운다. 재실행해도 안전하다.

    뷰(`v_entry_full`)까지 갱신하려면 이 함수 다음에
    `review_notes.ensure_v_entry_full_view()`를 부른다 — 뷰는 `schema.sql`이
    매번 `DROP VIEW` + `CREATE VIEW`로 다시 만들므로 컬럼이 먼저 있어야 한다.

    Returns:
        컬럼을 하나라도 새로 추가했으면 True, 둘 다 이미 있었으면 False.
    """
    existing = {row[1] for row in conn.execute("PRAGMA table_info(entries)")}
    added = False
    for column in DEFINITION_REVIEW_COLUMNS:
        if column in existing:
            continue
        conn.execute(f"ALTER TABLE entries ADD COLUMN {column} TEXT")
        added = True
    if added:
        conn.commit()
    return added


if __name__ == "__main__":
    import argparse
    from pathlib import Path

    from . import review_notes

    parser = argparse.ArgumentParser(
        description=(
            "entries에 definition_reviewed_at/definition_reviewed_by 컬럼이 없으면 "
            "추가하고 v_entry_full 뷰를 재생성한다(review_notes.py의 __main__과 같은 "
            "처방, 재실행 안전). 이 컬럼이 없던 시절(2026-09-23 이전) 만들어진 DB를 "
            "메울 때 쓴다."
        )
    )
    parser.add_argument("--db", type=Path, default=Path("dist/easy_dict.sqlite3"))
    args = parser.parse_args()

    if not args.db.exists():
        raise SystemExit(f"DB가 없습니다: {args.db}")

    _conn = sqlite3.connect(str(args.db))
    try:
        _added = ensure_definition_review_columns(_conn)
        print(f"definition_reviewed_at/definition_reviewed_by 컬럼 {'추가함' if _added else '이미 있음'}")
        review_notes.ensure_v_entry_full_view(_conn)
        print("v_entry_full 뷰 재생성함")
    finally:
        _conn.close()
