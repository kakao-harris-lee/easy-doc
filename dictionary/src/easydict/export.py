"""SQLite 정본에서 JSON 산출물 3종을 만든다 (§4, §6.4).

`easy_dict.sqlite3`를 정본으로 두고(§3), 이 모듈은 그로부터 파생되는
읽기 전용 뷰만 만든다 — 이 모듈은 DB에 아무것도 쓰지 않는다.

- `export_full`   : 전체 덤프. 벡터화·재배포·사람이 훑어보는 용도 (§4.2)
- `export_index`  : 런타임 조회 최적화 색인. easy-doc이 실제로 로드하는 파일 (§4.3)
- `export_simple` : 기획 초안 호환용 최소 JSONL (§4.4)
- `export_all`    : 위 3개를 한 번에

**의존성 없음**: 표준 라이브러리(`json`, `sqlite3`, `pathlib`, `datetime`)만 사용한다.

`status='deprecated'`인 엔트리는 `export_index`/`export_simple`에서는 제외한다
— deprecated는 "한때 있었지만 지금은 쓰지 않는 표제어"이므로 런타임 조회·
LLM 프롬프트·순진한 치환 대상이 아니다. 다만 `export_full`(전체 덤프)만은
**예외적으로 deprecated까지 포함한다** — §4.2 참고. B2G 납품 시 "이 용어는
왜 사전에 없나"에 답하려면 제외된 항목과 그 사유(예: `caution`의
`[확인 필요]`)가 감사 추적으로 남아 있어야 하기 때문이다.
"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .models import SCHEMA_VERSION, TAG_CATALOG
from .normalize import JOSA

# index.json의 엔트리당 예문(x) 개수 상한. lookup.py의 from_sqlite 로더도
# 라운드트립 동등성(§8)을 위해 같은 값으로 캡을 맞춘다.
_MAX_EXAMPLES_PER_ENTRY = 3

# ---------------------------------------------------------------------------
# §6.8 동일 표면형 승자 결정 정렬 키.
#
# 한 표제어(또는 변형형)에 엔트리가 여럿이면(467개 표제어가 이미 그렇다),
# export_index()가 이 키로 후보를 정렬해 surface_index 리스트에 담는다 —
# "승자는 export 시점에 구워 넣는다"(§6.8 결정). 정렬된 리스트의 첫 원소가
# 항상 승자이고, lookup.py는 조회 시점에 이 규칙을 다시 계산하지 않고 그
# 순서를 그대로 믿는다. 목적은 easy-doc(Kotlin)이 이 규칙을 재구현하지
# 않아도 되게 하는 것이다 — 조회 시점 비교자를 Python/Kotlin 두 곳에 두면
# 언젠가 갈라진다.
#
# lookup.py의 EasyDict.from_sqlite()(정본 SQLite를 직접 읽는 어드민/검수
# 도구 전용 로더, §7.1에 따라 Kotlin은 건드리지 않는 경로)도 이 함수를
# 그대로 재사용한다 — 그래야 from_index_json과 from_sqlite 두 로더가 같은
# 표제어에 대해 항상 같은 승자를 고른다(§8 라운드트립 동등성). 이건 "조회
# 시점 비교자를 새로 만들지 마라"는 원칙이 막으려는 것(Kotlin 쪽 재구현
# 분기)과는 다른 문제라 재사용해도 그 원칙과 충돌하지 않는다.
# ---------------------------------------------------------------------------

# 위험도(키 ①). high는 어느 원천에서 왔든 "이 말은 법적으로 위험하다"는
# 세상에 대한 사실 주장이므로 항상 최우선이다(§6.8). 숫자가 작을수록
# 정렬에서 먼저 오고(=승자에 가깝다).
_RISK_WINNER_RANK: dict[str, int] = {"high": 0, "low": 1, "none": 2}

# 전략 보수성(키 ③). keep(원어 유지) > gloss(원어 보존+설명) > substitute(원어
# 삭제) 순으로 보수적일수록 승자에 가깝다. 같은 원천 안에서 전략이 갈리면
# 그 불일치 자체가 불확실성의 신호이므로 보수적인 쪽을 택한다(§6.8).
_STRATEGY_WINNER_RANK: dict[str, int] = {"keep": 0, "gloss": 1, "substitute": 2}

# 원천이 제시한 순서(키 ④). `entries.cell_rank`(models.Entry.cell_rank, §6.8)를
# 그대로 쓴다 — 원천 CSV가 한 셀에 순화어를 여러 개 나열할 때 앞에 쓴 것이
# 대개 권장어라는 저작 의도 신호다(예: "정보 통신 기술, 정보 문화 기술" ->
# ICT의 올바른 답은 앞의 것). 값이 이미 "작을수록 먼저(=승자에 가깝다)"로
# 저장되어 있으므로 별도 순위표 없이 그대로 사용한다. cell_rank 개념이 없는
# 원천/셀은 전부 0이라 이 키에서 자동으로 동률 처리되고 키 ⑤로 넘어간다 —
# 신호가 없는 원천이 부당하게 이기거나 지지 않는다.
#
# 적재 순서(--input 순서, 파일 내 행 순서)와 혼동하지 말 것: cell_rank는
# "그 셀 안"의 내용값이라 순서 독립성 계약(§5.4)과 충돌하지 않는다.

# 원천 신뢰도(키 ②). 숫자가 클수록 신뢰도가 높다(승자에 가깝다).
#
# 현행 5원천의 등급과 근거:
#   - easydict:welfare-seed1 / welfare-seed2 : 우리가 직접 만들고 손으로
#     검수한 복지 용어 시드 데이터. 사람 검수를 거쳤으므로 최고 신뢰도.
#   - krdict:advanced / krdict:advanced-v2   : 국립국어원 한국어기초사전
#     API에서 가져온 고급어휘. 사전 편찬 기관의 정제 데이터이지만 우리가
#     직접 검수하지는 않았으므로 시드 데이터보다 한 단계 아래.
#   - nikl:admin2018 : 국립국어원 「알기 쉬운 행정용어」(2018)의 대량 자동
#     변환. 사람 검수 없이 규칙 기반으로 대량 처리되어 최하위.
#
# 새 원천을 추가할 때는 이 표에 등급을 명시적으로 적어 넣어야 한다. 표에
# 없는 원천 코드는 항상 _UNREGISTERED_SOURCE_TIER(등록된 어떤 등급보다
# 낮음)로 취급한다 — 등록을 깜빡한 새 원천이 조용히 최상위 승자가 되는
# 사고를 막기 위해서다("표에 없으면 가장 의심하고 본다"는 안전한 실패
# 방향).
SOURCE_TRUST_TIER: dict[str, int] = {
    "easydict:welfare-seed1": 3,
    "easydict:welfare-seed2": 3,
    # 복지 핵심어 3차 — RAG 관점으로 다시 쓴 배치. `급여`처럼 krdict(tier 2)가
    # 일상 뜻('일한 대가로 받는 돈')을 들고 있어 LLM에 틀린 뜻을 가르치던
    # 항목을 도메인 뜻으로 덮는 것이 목적이라, 사전 API보다 위여야 한다.
    "easydict:welfare-seed3": 3,
    # 복지 핵심어 4차 — 행정 처분·혼동 쌍(환급/환수, 부과/부여, 상실/분실) 중심.
    "easydict:welfare-seed4": 3,
    # 복지 핵심어 5차 — 절차(반려·보완·소급·유예·기산)와 장애 정도 분류.
    # `중증`은 krdict(tier 2)가 '몹시 심한 병의 증세'라는 질병 뜻을 들고 있어
    # 덮어야 한다 — 복지 문서의 중증은 지원 대상을 가르는 법적 분류다.
    "easydict:welfare-seed5": 3,
    "krdict:advanced": 2,
    "krdict:advanced-v2": 2,
    "nikl:admin2018": 1,
}
_UNREGISTERED_SOURCE_TIER = 0  # 표에 없는 원천은 항상 최하위.


def _source_trust_rank(source_code: str | None) -> tuple[int, str]:
    """원천 코드 -> `(-신뢰도, 코드)` 튜플.

    오름차순 정렬에서 신뢰도가 높을수록 먼저 오도록 부호를 뒤집었다.
    신뢰도가 같은 값으로 묶이면(특히 서로 다른 미등록 원천끼리
    `_UNREGISTERED_SOURCE_TIER`로 묶일 때) 코드 사전순으로 갈라 그
    안에서도 결정적인 순서를 만든다.
    """
    code = source_code or ""
    tier = SOURCE_TRUST_TIER.get(code, _UNREGISTERED_SOURCE_TIER)
    return (-tier, code)


def winner_sort_key(row: dict[str, Any]) -> tuple[int, tuple[int, str], int, int, str]:
    """§6.8 정렬 키 본체. 오름차순으로 정렬하면 승자가 맨 앞에 온다.

    `row`는 `v_entry_full`의 컬럼명을 키로 갖는 dict를 기대한다 — 이 모듈의
    `_rows()` 출력이거나, `lookup.EasyDict.from_sqlite()`가 같은 컬럼 이름으로
    구성한 dict다. 필요한 키: `risk_level`, `source_code`, `replace_strategy`,
    `cell_rank`(없으면 0으로 취급), `easy_term`.

    ①risk_level -> ②원천 신뢰도 -> ③전략 보수성 -> ④원천이 제시한 순서
    (cell_rank) -> ⑤easy_term 사전순. `entries` 테이블의
    `UNIQUE(term_norm, easy_term)` 제약 덕분에 같은 term_norm을 공유하는
    후보는 ⑤에서 반드시 갈라지므로 이 키로 항상 전순서(total order)가
    완성된다(§6.8).
    """
    return (
        _RISK_WINNER_RANK.get(row["risk_level"], len(_RISK_WINNER_RANK)),
        _source_trust_rank(row.get("source_code")),
        _STRATEGY_WINNER_RANK.get(row["replace_strategy"], len(_STRATEGY_WINNER_RANK)),
        row.get("cell_rank", 0) or 0,  # NULL(레거시 데이터)도 0으로 방어
        row["easy_term"],
    )


# v_entry_full(schema.sql) + entries.status != 'deprecated' 조합을 export_index/
# export_simple 둘이서만 공유한다(둘 다 "미검수·폐기 내용이 새어나가면 안 된다"는
# 같은 제약을 지므로). export_full은 감사 추적이 목적이라 이 필터를 쓰지 않고
# 아래 _ALL_ENTRIES_SQL로 deprecated까지 전부 담는다 — export_full까지 여기
# 끼워 넣으면 그 차이가 안 보이게 되므로 의도적으로 상수를 분리했다.
# 정렬은 둘 다 id 오름차순으로 고정해 재실행 시 산출물 diff를 안정시킨다.
# (예외: export_index는 surface_index를 채울 때 이 id 순서 대신 아래
# `winner_sort_key`로 다시 정렬한다 — §6.8 참고. entries_out 딕셔너리 자체의
# 키 순서는 JSON 객체라 의미가 없으므로 영향받지 않는다.)
_ACTIVE_ENTRIES_SQL = """
    SELECT * FROM v_entry_full
     WHERE status != 'deprecated'
     ORDER BY id
"""

# export_full 전용: 상태 필터 없이 전부(active/review/deprecated) 담는다.
_ALL_ENTRIES_SQL = """
    SELECT * FROM v_entry_full
     ORDER BY id
"""


def _rows(conn: sqlite3.Connection, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    """커서 결과를 컬럼명 dict 리스트로 변환한다.

    호출자의 `conn.row_factory`에 의존하지 않기 위해 `cursor.description`으로
    직접 매핑한다(익스포트 함수는 어떤 row_factory가 설정된 커넥션이 와도
    동작해야 한다).
    """
    cur = conn.execute(sql, params)
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


def export_full(conn: sqlite3.Connection, out: Path) -> Path:
    """전체 덤프(`easy_dict.json`)를 만든다 (§4.2) — 감사 추적용이라 deprecated도 포함한다.

    `v_entry_full` 뷰로 엔트리 본체를, `variants`/`examples` 테이블로 중첩
    항목을 각각 조회해 하나의 JSON 문서로 합친다. 사람이 읽거나 벡터화
    파이프라인에 넣을 용도라 `ensure_ascii=False`, `indent=2`로 가독성을
    우선한다(§4.3의 `index.json`과 반대로 크기 최적화를 하지 않는다).

    ### `export_index`/`export_simple`과 다르게 `status='deprecated'`를 포함한다

    이 파일은 "전체 덤프"다. `status`(active/review/deprecated)를 그대로
    노출해 소비자가 원하면 걸러낼 수 있게 하고, `caution`도 그대로 실어
    제외 사유(예: 법조문 대조가 필요해 `[확인 필요]`가 붙은 채 deprecated로
    강제된 경우)가 함께 남게 한다. B2G 납품에서 "이 용어는 왜 사전에
    없나"에 답하려면 빠진 항목과 그 이유가 어딘가엔 남아 있어야 한다 —
    그 "어딘가"가 `export_index`(런타임 조회, 미검수 내용이 프롬프트에
    가면 안 됨)나 `export_simple`(§4.4 안전 계약)일 수는 없으므로 이
    "전체 덤프"가 그 역할을 진다. `counts.deprecated`에 별도로 건수를
    표시해 감사자가 필터 없이도 규모를 바로 알 수 있게 했다.

    사용 예:
        >>> import sqlite3
        >>> conn = sqlite3.connect("dist/easy_dict.sqlite3")
        >>> export_full(conn, Path("dist/easy_dict.json"))
        PosixPath('dist/easy_dict.json')
    """
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)

    entries: list[dict[str, Any]] = []
    for row in _rows(conn, _ALL_ENTRIES_SQL):
        entry_id = row["id"]
        tags = row["tags"].split(",") if row["tags"] else []

        variants = _rows(
            conn,
            "SELECT surface, kind FROM variants WHERE entry_id = ? ORDER BY id",
            (entry_id,),
        )

        examples = _rows(
            conn,
            """
            SELECT before_text AS before, after_text AS after, is_golden
              FROM examples WHERE entry_id = ? ORDER BY id
            """,
            (entry_id,),
        )
        for ex in examples:
            ex["is_golden"] = bool(ex["is_golden"])

        source = None
        if row["source_code"]:
            source = {"code": row["source_code"], "ref": row["source_ref"]}

        entries.append(
            {
                "id": entry_id,
                "term": row["term"],
                "term_hanja": row["term_hanja"],
                "pos": row["pos"],
                "easy_term": row["easy_term"],
                "definition": row["definition"],
                "replace_strategy": row["replace_strategy"],
                "risk_level": row["risk_level"],
                "caution": row["caution"],
                "readability": row["readability"],
                "confidence": row["confidence"],
                "priority": row["priority"],
                "status": row["status"],
                "tags": tags,
                "primary_tag": row["primary_tag"],
                "variants": variants,
                "examples": examples,
                "source": source,
            }
        )

    counts = {
        "entries": len(entries),
        "variants": sum(len(e["variants"]) for e in entries),
        "examples": sum(len(e["examples"]) for e in entries),
        "deprecated": sum(1 for e in entries if e["status"] == "deprecated"),
    }

    sources = _rows(conn, "SELECT code, name, organization, license, url FROM sources ORDER BY id")
    tags_out = _rows(conn, "SELECT name, label, kind FROM tags ORDER BY name")

    doc = {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "counts": counts,
        "sources": sources,
        "tags": tags_out,
        "entries": entries,
    }

    with out.open("w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    return out


def export_index(conn: sqlite3.Connection, out: Path) -> Path:
    """런타임 조회용 색인(`easy_dict.index.json`)을 만든다 (§4.3).

    이 파일은 easy-doc(Kotlin 백엔드)이 메모리에 상주시켜 문서 매칭마다
    읽는 파일이라 크기가 곧 지연으로 이어진다. 그래서:

    - 표제어 + 모든 변형형(`variants.surface`)을 `surface_index`에 평탄화한다.
      Kotlin 쪽이 파이썬 `EasyDict`와 동일한 트라이를 구성할 수 있도록
      `entries` 딕셔너리는 `t/e/d/s/r/p/g/c/x` 1글자 키로 축약한다.
    - `indent` 없이(`separators=(",", ":")`) 최소 크기로 쓴다.
    - `josa` 목록을 동봉해 Kotlin처럼 `normalize.josa_pattern()`이 없는
      언어에서도 조사 경계 판정을 재현할 수 있게 한다.

    ### 동일 표면형 승자 순서 (§6.8)

    한 표면형(표제어 또는 변형형)에 후보 엔트리가 여럿이면, `surface_index`에
    담기 전에 `winner_sort_key()`(§6.8 정렬 키)로 후보를 정렬한다. 정렬된
    리스트의 **첫 원소가 승자**다 — `lookup.py`(Python)도 Kotlin도 조회
    시점에 이 규칙을 다시 계산하지 않고 리스트의 첫 원소를 그대로 쓴다.
    조회 시점 비교자를 두지 않는 이유는 Kotlin 소비자가 이 규칙을
    재구현하지 않아도 되게 하기 위해서다(§6.5 "규칙"과 같은 원칙).

    ### `c`(caution)/`x`(examples) 추가 근거 (D-4, lookup.build_prompt_context 재설계)

    `lookup.EasyDict.build_prompt_context()`가 이 프로젝트의 실제 목적(RAG용
    프롬프트 컨텍스트 생성)으로 격상되면서, 고위험/`keep` 용어에는 `caution`과
    예문(`examples`)까지 실어야 LLM이 오변환하지 않는다(§2.1). 이 둘을
    `easy_dict.sqlite3`에만 두고 `index.json`에서 빼는 방안(sqlite 전용
    고급 조회)도 검토했지만 기각했다 — easy-doc은 Kotlin이고 §7.1대로
    `index.json`만 읽는 구조라, sqlite 전용으로 빼면 **정작 이 기능이
    필요한 실제 운영 경로(Kotlin)에서 영영 못 쓰게 된다.** JDBC/sqlite는
    §4.1에 명시된 대로 어드민·검수 도구 전용이지 운영 경로가 아니다.
    크기 부담은 제한적이다 — `caution`/`examples`는 대부분 `None`/빈
    배열이고(저위험 substitute 엔트리는 거의 없음), 예문은 엔트리당
    최대 3개로 캡을 씌워 상한을 둔다(`_MAX_EXAMPLES_PER_ENTRY`). 실측은
    `export_index` 실행 후 파일 크기로 확인하라.

    사용 예:
        >>> export_index(conn, Path("dist/easy_dict.index.json"))
        PosixPath('dist/easy_dict.index.json')
    """
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)

    surface_index: dict[str, list[int]] = {}
    entries_out: dict[str, dict[str, Any]] = {}

    def _add_surface(surface: str, entry_id: int) -> None:
        # 호출 순서가 곧 surface_index[surface] 리스트의 순서가 된다. 아래
        # 루프가 이미 winner_sort_key()로 정렬된 rows를 순회하므로, 같은
        # 표면형에 여러 entry_id가 쌓여도 항상 승자(§6.8)부터 쌓인다 —
        # 여기서 다시 비교하지 않는다.
        ids = surface_index.setdefault(surface, [])
        if entry_id not in ids:
            ids.append(entry_id)

    # §6.8: id 순서(ORDER BY id, 적재 순서)가 아니라 정렬 키로 다시 정렬한
    # 순서로 순회해야 승자가 결정적으로 정해진다. sorted()는 안정 정렬이라
    # 정렬 키가 완전히 같은 경우(이론상 UNIQUE(term_norm, easy_term) 덕분에
    # 거의 발생하지 않는다)에는 원래의 id 오름차순이 그대로 유지된다.
    for row in sorted(_rows(conn, _ACTIVE_ENTRIES_SQL), key=winner_sort_key):
        entry_id = row["id"]
        tags = row["tags"].split(",") if row["tags"] else []

        examples = [
            {"b": ex["before_text"], "a": ex["after_text"], "y": bool(ex["is_golden"])}
            for ex in _rows(
                conn,
                """
                SELECT before_text, after_text, is_golden FROM examples
                 WHERE entry_id = ?
                 ORDER BY is_golden DESC, id ASC
                 LIMIT ?
                """,
                (entry_id, _MAX_EXAMPLES_PER_ENTRY),
            )
        ]

        entries_out[str(entry_id)] = {
            "t": row["term"],
            "e": row["easy_term"],
            "d": row["definition"],
            "s": row["replace_strategy"],
            "r": row["risk_level"],
            "p": row["priority"],
            "g": tags,
            "c": row["caution"],
            "x": examples,
        }

        _add_surface(row["term"], entry_id)
        for vrow in conn.execute(
            "SELECT surface FROM variants WHERE entry_id = ?", (entry_id,)
        ):
            _add_surface(vrow[0], entry_id)

    doc = {
        "schema_version": SCHEMA_VERSION,
        "josa": list(JOSA),
        "surface_index": surface_index,
        "entries": entries_out,
    }

    with out.open("w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    return out


def export_simple(conn: sqlite3.Connection, out: Path) -> Path:
    """기획 초안 호환용 JSONL(`easy_dict.simple.jsonl`)을 만든다 (§4.4).

    ### 안전 계약: "순진하게 `{term: easy_term}`로 읽어 `str.replace`를 돌려도
    사고가 나지 않는 파일"

    이 파일에는 `replace_strategy` 필드가 없다. 기획서 초안이 정의한
    형태 그대로 `{"term", "easy_term", "category"}` 세 필드만 남기기로
    한 것이지만, 그렇다고 §2.1이 경고한 "단순 치환의 위험"(과태료→벌금처럼
    법적으로 다른 개념이 되거나, 법령명·금액 같은 사실관계가 지워지는
    사고)까지 이 파일에서 되풀이해서는 안 된다. 그래서 `replace_strategy`
    필드를 실어 나르는 대신, **행 자체를 전략에 맞게 미리 가공**해서
    치환 로직이 없는 순진한 소비자도 안전하게 만든다.

    - `substitute` : `easy_term` 그대로 싣는다. 지워도 안전한 말이라서다.
    - `gloss`      : `easy_term`을 `원어(easy_term)` 형태로 합성해서 싣는다
                     (예: `과태료` → `"과태료(정해진 법을 안 지켜서 더 내는 돈)"`).
                     그냥 문자열 치환을 해도 원어가 결과에 그대로 남으므로
                     easy-doc 골든셋의 `required_facts.canonical` 검증을
                     통과한다(§7.3) — `lookup.annotate()`의 gloss 처리와
                     같은 발상이다.
    - `keep`       : **행 자체를 아예 내보내지 않는다.** 법령명·금액·기한처럼
                     원어를 절대 지우면 안 되는 용어를 `{term: easy_term}`
                     딕셔너리 형태로 노출하는 것 자체가 위험 신호이기
                     때문이다(딕셔너리에 들어있는 이상 언젠가 치환에 쓰인다).

    추가로 `status='review'`인 엔트리도 제외한다. `risk_level='high'`라서
    사람 검수 큐에 있는(§5.2) 데이터가 검수 전에 프로토타입으로 새어나가면
    안 되기 때문이다(`status='deprecated'` 제외는 기존과 동일).

    파일 첫 줄에 안내 주석을 넣지 않는다 — JSONL은 매 줄이 독립된 JSON
    값이어야 하는 포맷이라 주석 줄을 넣으면 표준 JSONL 파서가 깨진다.
    이 안전 계약은 (파일이 아니라) 이 docstring에 남긴다.

    사용 예:
        >>> export_simple(conn, Path("dist/easy_dict.simple.jsonl"))
        PosixPath('dist/easy_dict.simple.jsonl')
    """
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)

    label_by_name = {name: label for name, (label, _kind) in TAG_CATALOG.items()}

    with out.open("w", encoding="utf-8") as f:
        for row in _rows(
            conn,
            """
            SELECT term, easy_term, replace_strategy, primary_tag FROM v_entry_full
             WHERE status NOT IN ('deprecated', 'review')
             ORDER BY id
            """,
        ):
            if row["replace_strategy"] == "keep":
                continue  # 원어를 절대 지우면 안 되는 용어는 이 파일에 아예 싣지 않는다.

            easy_term = row["easy_term"]
            if row["replace_strategy"] == "gloss":
                # 순진한 치환에도 원어가 결과에 남도록 '원어(easy_term)'로 합성한다.
                easy_term = f"{row['term']}({easy_term})"

            category = label_by_name.get(row["primary_tag"], row["primary_tag"] or "")
            line = {"term": row["term"], "easy_term": easy_term, "category": category}
            f.write(json.dumps(line, ensure_ascii=False))
            f.write("\n")
    return out


def export_all(conn: sqlite3.Connection, out_dir: Path) -> list[Path]:
    """JSON 3종을 모두 만들고 생성된 경로 리스트를 반환한다.

    출력 디렉터리가 없으면 생성한다.

    사용 예:
        >>> export_all(conn, Path("dist"))
        [PosixPath('dist/easy_dict.json'), PosixPath('dist/easy_dict.index.json'), PosixPath('dist/easy_dict.simple.jsonl')]
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    return [
        export_full(conn, out_dir / "easy_dict.json"),
        export_index(conn, out_dir / "easy_dict.index.json"),
        export_simple(conn, out_dir / "easy_dict.simple.jsonl"),
    ]


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="easy_dict.sqlite3 -> JSON 3종 익스포트 데모")
    parser.add_argument("--db", type=Path, default=Path("dist/easy_dict.sqlite3"))
    parser.add_argument("--out", type=Path, default=Path("dist"))
    args = parser.parse_args()

    if not args.db.exists():
        raise SystemExit(f"DB가 없습니다: {args.db} (먼저 build.py를 실행하세요)")

    _conn = sqlite3.connect(args.db)
    try:
        paths = export_all(_conn, args.out)
        for p in paths:
            size = p.stat().st_size
            print(f"{p}  ({size:,} bytes)")
    finally:
        _conn.close()
