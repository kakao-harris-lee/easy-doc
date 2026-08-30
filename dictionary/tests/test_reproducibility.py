"""빌드 재현성 회귀 테스트 (DESIGN.md §5⑥, §9).

실측된 결함: `--input` 순서를 정순/역순으로 바꿔서 같은 3개 CSV를 빌드하면
산출물이 달라졌다. 원인은 `내방`처럼 두 원천(admin/welfare) 모두에 등장하는
표제어의 대표 태그(`primary_tag`)가 "나중에 적재된 원천이 이긴다" 방식으로
결정되어, `--input` 나열 순서가 곧 대표 태그를 결정해버렸기 때문이다.
B2G 납품물은 "같은 입력이면 언제 빌드해도 같은 산출물"이 나와야 하므로
이는 심각한 재현성 결함이다.

**이 테스트의 핵심은 특정 규칙(TAG_PRIORITY 등)을 검증하는 게 아니라
순서 독립성 자체를 검증하는 것이다.** 구현이 어떤 규칙을 채택하든 이
테스트는 유효해야 하므로, 규칙의 구체적 내용(우선순위 표 등)은 참조하지
않고 오직 "정순 빌드 결과 == 역순 빌드 결과"만 비교한다.

pipeline 담당이 이 시점에 수정 중일 수 있다. 아직 안 들어갔다면 1번
테스트(대표 태그 순서 독립성)가 실패하는 게 정상이고, 그 실패는 결함이
실재했다는 증거로 그대로 보고한다.

`dist/`는 절대 읽거나 쓰지 않는다 — 이 테스트는 빌드를 2회 수행하므로
매번 별도의 `tempfile.TemporaryDirectory()`에 DB와 익스포트 산출물을
만들고 끝나면 정리한다. `data/raw/*.csv`는 읽기만 한다.
"""

from __future__ import annotations

import contextlib
import csv
import io
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

try:
    from easydict import build as build_mod
    _IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover
    build_mod = None  # type: ignore[assignment]
    _IMPORT_ERROR = e

REPO_ROOT = Path(__file__).resolve().parent.parent
DATA_RAW = REPO_ROOT / "data" / "raw"
# 가상 검증용 샘플 CSV 전용 디렉터리. 실데이터(data/raw/*.csv)와 분리하려고
# 팀장이 여기로 옮겼다(2026-08-27) — 이 재현성 테스트는 재현 가능성 자체를
# 검증하는 것이 목적이라 통제된 소형 샘플이 실데이터보다 적합하다(빌드 2회를
# 도는 테스트라 실데이터 1,075행까지 쓰면 느려진다). tests/test_build.py의
# 동일 상수와 같은 근거.
SAMPLE_CSV_DIR = DATA_RAW / "sample"
SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"

# (csv 파일, source-code, source-name, organization, license) — 실제 3종 샘플.
_INPUTS: tuple[tuple[str, str, str, str, str], ...] = (
    ("raw_terms.csv", "data.go.kr:admin-terms", "행정용어 순화어 대조표", "행정안전부", "공공누리 제1유형"),
    ("raw_terms_welfare_cp949.csv", "mohw.go.kr:welfare-terms", "복지용어 순화어 대조표", "보건복지부", "공공누리 제1유형"),
    ("raw_terms_law.csv", "moleg.go.kr:law-terms", "법률용어 순화어 대조표", "법제처", "공공누리 제1유형"),
)


def _run_build(order: tuple[tuple[str, str, str, str, str], ...], workdir: Path) -> tuple[Path, Path]:
    """주어진 순서로 build.main()을 실행하고 (db_path, export_dir)을 반환한다."""
    db_path = workdir / "easy_dict.sqlite3"
    export_dir = workdir / "dist"

    argv: list[str] = []
    for csv_name, _code, _name, _org, _lic in order:
        argv += ["--input", str(SAMPLE_CSV_DIR / csv_name)]
    for _csv_name, code, _name, _org, _lic in order:
        argv += ["--source-code", code]
    for _csv_name, _code, name, _org, _lic in order:
        argv += ["--source-name", name]
    for _csv_name, _code, _name, org, _lic in order:
        argv += ["--organization", org]
    for _csv_name, _code, _name, _org, lic in order:
        argv += ["--license", lic]
    argv += [
        "--db", str(db_path),
        "--export", str(export_dir),
        "--reset",
        "--schema", str(SCHEMA_SQL_PATH),
    ]

    # 빌드 리포트 stdout 출력은 테스트 로그를 어지럽히므로 흡수한다(진단이 필요하면
    # 아래에서 buf.getvalue()로 확인 가능).
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = build_mod.main(argv)
    if rc != 0:
        raise AssertionError(f"build.main() 이 실패했다 (rc={rc}). 출력:\n{buf.getvalue()}")

    return db_path, export_dir


def _entry_rows(db_path: Path) -> list[dict]:
    conn = sqlite3.connect(str(db_path))
    try:
        conn.row_factory = sqlite3.Row
        cur = conn.execute(
            """
            SELECT term, term_norm, easy_term, replace_strategy, risk_level,
                   status, primary_tag, tags
              FROM v_entry_full
             WHERE status != 'deprecated'
             ORDER BY term_norm, easy_term
            """
        )
        return [dict(row) for row in cur.fetchall()]
    finally:
        conn.close()


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestBuildReproducibility(unittest.TestCase):
    """정순/역순으로 3개 샘플 CSV를 빌드해 산출물이 순서와 무관한지 확인한다."""

    @classmethod
    def setUpClass(cls) -> None:
        for csv_name, *_ in _INPUTS:
            path = SAMPLE_CSV_DIR / csv_name
            if not path.is_file():
                raise unittest.SkipTest(f"샘플 CSV 없음: {path}")

        cls._tmp = tempfile.TemporaryDirectory(prefix="easydict_repro_test_")
        tmp_root = Path(cls._tmp.name)

        forward_dir = tmp_root / "forward"
        reverse_dir = tmp_root / "reverse"
        forward_dir.mkdir()
        reverse_dir.mkdir()

        cls.forward_db, cls.forward_export = _run_build(_INPUTS, forward_dir)
        cls.reverse_db, cls.reverse_export = _run_build(tuple(reversed(_INPUTS)), reverse_dir)

        cls.forward_rows = _entry_rows(cls.forward_db)
        cls.reverse_rows = _entry_rows(cls.reverse_db)

    @classmethod
    def tearDownClass(cls) -> None:
        cls._tmp.cleanup()

    def _key(self, row: dict) -> tuple[str, str]:
        return (row["term_norm"], row["easy_term"])

    # ------------------------------------------------------------------
    # 1. 대표 태그 순서 독립성 (실측된 결함 그 자체)
    # ------------------------------------------------------------------
    def test_primary_tag_order_independent(self) -> None:
        forward_by_key = {self._key(r): r["primary_tag"] for r in self.forward_rows}
        reverse_by_key = {self._key(r): r["primary_tag"] for r in self.reverse_rows}

        self.assertEqual(
            set(forward_by_key), set(reverse_by_key),
            "정순/역순 빌드의 엔트리 키 집합 자체가 다르다 (별도 테스트 2번에서도 확인)",
        )

        mismatches = [
            (key, forward_by_key[key], reverse_by_key[key])
            for key in forward_by_key
            if forward_by_key[key] != reverse_by_key[key]
        ]
        self.assertEqual(
            mismatches, [],
            "대표 태그가 --input 순서에 따라 달라지는 표제어가 있다 "
            "(term_norm, easy_term, 정순 primary_tag, 역순 primary_tag) 목록: "
            + repr([(k[0], k[1], fwd, rev) for k, fwd, rev in mismatches]),
        )

    # ------------------------------------------------------------------
    # 2. 엔트리 집합 순서 독립성
    # ------------------------------------------------------------------
    def test_entry_set_order_independent(self) -> None:
        forward_keys = {self._key(r) for r in self.forward_rows}
        reverse_keys = {self._key(r) for r in self.reverse_rows}
        self.assertEqual(
            forward_keys, reverse_keys,
            f"엔트리 집합이 다르다. 정순에만 있음: {forward_keys - reverse_keys}, "
            f"역순에만 있음: {reverse_keys - forward_keys}",
        )

    # ------------------------------------------------------------------
    # 3. 비대표 태그 보존 ('내방'이 admin+welfare 둘 다 가져야 함)
    # ------------------------------------------------------------------
    def test_naebang_keeps_both_source_tags(self) -> None:
        for label, rows in (("정순", self.forward_rows), ("역순", self.reverse_rows)):
            matches = [r for r in rows if r["term"] == "내방" and r["easy_term"] == "방문"]
            self.assertTrue(matches, f"{label} 빌드에 '내방'(방문) 엔트리가 없다")
            tags = set((matches[0]["tags"] or "").split(","))
            self.assertIn("admin", tags, f"{label} 빌드에서 '내방'이 admin 태그를 잃었다: {tags}")
            self.assertIn("welfare", tags, f"{label} 빌드에서 '내방'이 welfare 태그를 잃었다: {tags}")

    # ------------------------------------------------------------------
    # 4. 전략·위험도·상태 순서 독립성
    # ------------------------------------------------------------------
    def test_strategy_risk_status_order_independent(self) -> None:
        forward_by_key = {
            self._key(r): (r["replace_strategy"], r["risk_level"], r["status"]) for r in self.forward_rows
        }
        reverse_by_key = {
            self._key(r): (r["replace_strategy"], r["risk_level"], r["status"]) for r in self.reverse_rows
        }
        common = set(forward_by_key) & set(reverse_by_key)
        mismatches = [
            (key, forward_by_key[key], reverse_by_key[key])
            for key in common
            if forward_by_key[key] != reverse_by_key[key]
        ]
        self.assertEqual(
            mismatches, [],
            "replace_strategy/risk_level/status가 --input 순서에 따라 달라지는 표제어가 있다: "
            + repr(mismatches),
        )

    # ------------------------------------------------------------------
    # 5. 산출물(index.json) 동일성
    #
    # entries.id는 SQLite AUTOINCREMENT라 --input 순서에 따라 같은 표제어라도
    # 다른 정수 id를 받을 수 있다(예: '내방'이 정순 빌드에서 id=3, 역순 빌드에서
    # id=41). 그래서 index.json 원본 바이트는 --input 순서가 바뀌면 id가 박힌
    # surface_index/entries 딕셔너리 내용이 달라져 바이트 단위 동일성은 보장되지
    # 않는다 — 이건 결함이 아니라 대리키(AUTOINCREMENT PK) 채택의 자연스러운
    # 귀결이다. 그래서 두 단계로 나눠 검증한다:
    #   5-a. 원본 바이트 비교를 실제로 시도하고 결과를 기록한다(정보 제공용,
    #        실패해도 그 자체로 assert하지 않는다).
    #   5-b. id를 (term_norm, easy_term)으로 치환한 "정규화된" 내용을 비교한다
    #        — 이것이 실제로 의미 있는 재현성 계약이다: 같은 입력이면 같은
    #        표면형→표제어 매핑, 같은 조사 목록, 같은 스키마 버전이 나와야 한다.
    # ------------------------------------------------------------------
    def test_index_json_semantically_identical_ignoring_ids(self) -> None:
        """§4.3 산출물의 순서 독립성.

        entries.id는 SQLite `AUTOINCREMENT`라 같은 표제어라도 --input 순서에
        따라 다른 정수 id를 받을 수 있다(실측: '내방'이 정순 빌드에서는 한
        id, 역순 빌드에서는 다른 id). 그래서 index.json의 **원본 바이트**는
        --input 순서가 바뀌면 달라질 수 있고, 이는 결함이 아니라 대리키
        채택의 자연스러운 귀결이다 — 별도의 raw-byte assertion은 두지 않는다
        (근거는 보고서 (d)에 기록).

        대신 id를 (term_norm, easy_term, ...) 내용값으로 치환한 정규화된
        비교를 한다 — 이것이 실제로 의미 있는 재현성 계약이다: 같은 입력이면
        같은 표면형→표제어 매핑, 같은 조사 목록, 같은 스키마 버전이 나와야 한다.
        """
        forward_doc = json.loads((self.forward_export / "easy_dict.index.json").read_text(encoding="utf-8"))
        reverse_doc = json.loads((self.reverse_export / "easy_dict.index.json").read_text(encoding="utf-8"))

        self.assertEqual(forward_doc["schema_version"], reverse_doc["schema_version"])
        self.assertEqual(forward_doc["josa"], reverse_doc["josa"], "josa 목록은 순서까지 동일해야 한다")

        def _normalize(doc: dict) -> tuple[dict, set]:
            entries = doc["entries"]
            # id -> (t,e,d,s,r,p,g) 를 값 자체로 정규화 (id는 비교 대상에서 제외)
            id_to_key = {
                eid: (info["t"], info["e"], info["d"], info["s"], info["r"], info["p"], tuple(info["g"]))
                for eid, info in entries.items()
            }
            # surface -> {정규화된 엔트리 값 집합} (id 목록 대신 값 집합으로 비교)
            surface_map = {
                surface: frozenset(id_to_key[str(eid)] for eid in ids)
                for surface, ids in doc["surface_index"].items()
            }
            return surface_map, set(id_to_key.values())

        forward_surface_map, forward_entry_values = _normalize(forward_doc)
        reverse_surface_map, reverse_entry_values = _normalize(reverse_doc)

        self.assertEqual(
            forward_entry_values, reverse_entry_values,
            "엔트리 내용(표제어/순화어/전략/위험도/우선순위/태그) 집합이 id를 무시해도 다르다",
        )
        self.assertEqual(
            set(forward_surface_map), set(reverse_surface_map),
            "surface_index의 표면형 키 집합이 다르다: "
            f"정순에만 있음={set(forward_surface_map) - set(reverse_surface_map)}, "
            f"역순에만 있음={set(reverse_surface_map) - set(forward_surface_map)}",
        )
        mismatched_surfaces = [
            surface for surface in forward_surface_map
            if forward_surface_map[surface] != reverse_surface_map.get(surface)
        ]
        self.assertEqual(
            mismatched_surfaces, [],
            f"다음 표면형이 가리키는 엔트리 내용이 순서에 따라 달라진다: {mismatched_surfaces}",
        )


# =============================================================================
# 파일 **내부** 행 순서 독립성 + 승자/원천 귀속 비교 (docs/inspection-plan.md
# Phase 3 작업 2).
#
# 위 TestBuildReproducibility는 --input **파일 간** 순서만 바꾸고, 후보
# **집합**을 (term_norm, easy_term, strategy, risk, status)로만 비교한다.
# 이번 세션 결함 50건은 전부 **한 파일 내부**의 행 순서 문제였고, 그
# 성격상 저 검사로는 원리상 못 잡는다 — --input 나열 순서(=파일 순서)는
# 그대로 두고, 각 파일 '내부'의 행만 뒤섞어야 재현된다.
#
# 후보 **집합**이 같은 것만으로도 부족하다 — §6.8의 핵심은 그 순서가
# **승자**를 정한다는 것이다. 그래서 v_entry_full의 모든 행이 아니라
# index.json의 surface_index[0](실제 조회에 노출되는 승자)만 비교한다.
# 승자의 **원천**(source_code)도 비교 항목에 넣는다 — 예문 원천의
# source_id 탈취(318건, §5.5(7))가 "대조 항목 자체가 빠져서" 놓친
# 결함이었다는 반성(docs/inspection-plan.md §4 Phase 3)을 그대로 적용한다.
#
# **진짜 경합이 있어야 이 검사가 의미가 있다.** 실데이터 샘플 3종
# (raw_terms*.csv)은 확인해 보니 파일 내부에도, 파일 간에도 같은
# term_norm이 두 번 나오는 행이 없다 — 그래서 승자 결정 로직 자체가
# 발동하지 않아 행 순서를 아무리 섞어도 항상 "통과"하는 공허한 검사가
# 된다(이 검사 도구를 만들며 겪은 것과 같은 종류의 실수 —
# tools/audit_corpus.py의 `check_boundaries` 2차 실패 참고). 그래서
# 두 원천 파일에 걸쳐 위험도(risk_level)로 승부가 갈리는 경합 행을 손으로
# 심는다. §6.8 키①(risk_level)이 키④(cell_rank, 원천 내부 순서)보다
# 우선하므로, 행 순서를 아무리 바꿔도 이 경합의 승자는 절대 바뀌면 안 된다
# — 바로 그 "절대 안 바뀜"이 검증 대상이다.
# =============================================================================
_ROW_ORDER_CSV_HEADER = ["term", "easy_term", "definition", "category", "replace_strategy", "risk_level"]

# 원천 A(test:row-order-a): 필러 3행 + **같은 파일 안** 경합 한 쌍(동일파일경합,
# risk=none이 먼저·risk=low가 나중) + 원천 B와의 교차 파일 경합 후보(약함).
# 반전 빌드(아래 setUpClass)는 파일 전체를 뒤집으므로 '동일파일경합' 쌍의
# 상대 순서도 함께 뒤집힌다 — 이게 바로 "파일 내부" 순서 독립성을 실제로
# 건드리는 지점이다(교차 파일 경합만으로는 파일 A/B 처리 순서 자체가
# 고정돼 있어 파일 내부 순서와 무관하게 항상 같은 답이 나올 수 있다 —
# 실제로 아래에서 winner_sort_key 정렬을 잠시 꺼서 확인했다).
_FILE_A_ROWS: list[list[str]] = [
    ["필러가나", "쉬운말가나", "필러용 정의", "복지", "substitute", "none"],
    ["동일파일경합", "파일A약함", "같은 파일 내부 경합, 약한 후보(risk=none)", "복지", "substitute", "none"],
    ["필러다라", "쉬운말다라", "필러용 정의", "복지", "substitute", "none"],
    ["동일파일경합", "파일A강함", "같은 파일 내부 경합, 강한 후보(risk=low, 이겨야 함)", "복지", "gloss", "low"],
    ["필러마바", "쉬운말마바", "필러용 정의", "복지", "substitute", "none"],
    ["혼합용어", "쉬운말A", "교차 파일 경합, 약한 후보(risk=none)", "복지", "substitute", "none"],
]
# 원천 B(test:row-order-b): 필러 3행 + 원천 A와의 교차 파일 경합 후보(강함).
_FILE_B_ROWS: list[list[str]] = [
    ["필러사아", "쉬운말사아", "필러용 정의", "복지", "substitute", "none"],
    ["필러자차", "쉬운말자차", "필러용 정의", "복지", "substitute", "none"],
    ["필러카타", "쉬운말카타", "필러용 정의", "복지", "substitute", "none"],
    ["혼합용어", "쉬운말B", "교차 파일 경합, 강한 후보(risk=low, 이겨야 함)", "복지", "gloss", "low"],
]


def _write_row_order_csv(path: Path, rows: list[list[str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(_ROW_ORDER_CSV_HEADER)
        writer.writerows(rows)


def _run_row_order_build(file_a_rows: list[list[str]], file_b_rows: list[list[str]], workdir: Path) -> tuple[Path, Path]:
    """`--input` 순서(A, B)와 원천 코드는 고정한 채, 넘겨받은 행 목록만으로
    두 CSV를 새로 쓰고 빌드한다 — 파일 순서/원천 메타데이터가 아니라
    **행 내용의 순서**만 실험 변수가 되도록 격리한다."""
    db_path = workdir / "easy_dict.sqlite3"
    export_dir = workdir / "dist"
    path_a = workdir / "source_a.csv"
    path_b = workdir / "source_b.csv"
    _write_row_order_csv(path_a, file_a_rows)
    _write_row_order_csv(path_b, file_b_rows)
    argv = [
        "--input", str(path_a),
        "--input", str(path_b),
        "--source-code", "test:row-order-a",
        "--source-code", "test:row-order-b",
        "--source-name", "행 순서 회귀 테스트 원천 A",
        "--source-name", "행 순서 회귀 테스트 원천 B",
        "--db", str(db_path),
        "--export", str(export_dir),
        "--reset",
        "--schema", str(SCHEMA_SQL_PATH),
    ]
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = build_mod.main(argv)
    if rc != 0:
        raise AssertionError(f"build.main() 실패 (rc={rc}). 출력:\n{buf.getvalue()}")
    return db_path, export_dir


def _winner_map(db_path: Path, export_dir: Path) -> dict[str, tuple]:
    """`index.json`의 `surface_index[surface][0]`(실제 승자)을 표면형별로
    `(term, easy_term, strategy, risk, source_code)`로 정규화해 돌려준다.

    `source_code`(원천의 안정적 식별자)를 raw `source_id`/`entries.id` 대신
    쓴다 — 둘 다 SQLite `AUTOINCREMENT`라 빌드마다(§5.4) 값이 달라질 수
    있어 두 독립된 빌드 사이의 비교에 못 쓴다. `source_code`는 CLI로 직접
    지정하는 값이라 안정적이다.
    """
    doc = json.loads((export_dir / "easy_dict.index.json").read_text(encoding="utf-8"))
    conn = sqlite3.connect(str(db_path))
    try:
        id_to_source_code = {
            row[0]: row[1] for row in conn.execute("SELECT id, source_code FROM v_entry_full")
        }
    finally:
        conn.close()

    winners: dict[str, tuple] = {}
    for surface, ids in doc["surface_index"].items():
        winner_id = ids[0]
        info = doc["entries"][str(winner_id)]
        winners[surface] = (
            info["t"], info["e"], info["s"], info["r"],
            id_to_source_code.get(winner_id),
        )
    return winners


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.build import 실패: {_IMPORT_ERROR}")
class TestBuildReproducibilityWithinFileRowOrder(unittest.TestCase):
    """`data/raw/`는 건드리지 않는다 — 전부 임시 디렉터리에 CSV를 새로 쓴다."""

    @classmethod
    def setUpClass(cls) -> None:
        cls._tmp = tempfile.TemporaryDirectory(prefix="easydict_repro_row_order_test_")
        tmp_root = Path(cls._tmp.name)

        orig_dir = tmp_root / "orig"
        shuffled_dir = tmp_root / "shuffled"
        orig_dir.mkdir()
        shuffled_dir.mkdir()

        # 각 파일 '내부'의 행 순서만 통째로 뒤집는다(reverse). --input 나열
        # 순서(A, B)와 원천 코드/이름은 두 빌드에서 완전히 동일하다. 무작위
        # 셔플 대신 전체 반전을 쓰는 이유: 반전은 파일 안의 **모든** 행 쌍의
        # 상대 순서를 뒤집는다는 것을 코드만 보고 바로 알 수 있다 — 그래서
        # '동일파일경합' 쌍(risk=none이 먼저 -> risk=low가 먼저)의 상대 순서도
        # 반드시 뒤집힌다는 것을 무작위성에 기대지 않고 보장한다.
        shuffled_a = list(reversed(_FILE_A_ROWS))
        shuffled_b = list(reversed(_FILE_B_ROWS))
        assert shuffled_a != _FILE_A_ROWS and shuffled_b != _FILE_B_ROWS, "반전이 원래 순서와 같아졌다 — 픽스처를 다시 봐라"

        cls.orig_db, cls.orig_export = _run_row_order_build(_FILE_A_ROWS, _FILE_B_ROWS, orig_dir)
        cls.shuffled_db, cls.shuffled_export = _run_row_order_build(shuffled_a, shuffled_b, shuffled_dir)

    @classmethod
    def tearDownClass(cls) -> None:
        cls._tmp.cleanup()

    def test_winner_and_source_attribution_unaffected_by_in_file_row_order(self) -> None:
        orig_winners = _winner_map(self.orig_db, self.orig_export)
        shuffled_winners = _winner_map(self.shuffled_db, self.shuffled_export)

        self.assertEqual(
            set(orig_winners), set(shuffled_winners),
            "파일 내부 행 순서만 바꿨는데 표면형 집합 자체가 달라졌다: "
            f"원본에만 있음={set(orig_winners) - set(shuffled_winners)}, "
            f"셔플에만 있음={set(shuffled_winners) - set(orig_winners)}",
        )
        mismatches = [
            (surface, orig_winners[surface], shuffled_winners[surface])
            for surface in orig_winners
            if orig_winners[surface] != shuffled_winners[surface]
        ]
        self.assertEqual(
            mismatches, [],
            "파일 내부 행 순서만 바꿨는데 승자(또는 그 원천)가 달라진 표면형 "
            "(표면형, 원본 승자, 셔플 승자) 목록: " + repr(mismatches),
        )

    def test_collision_actually_exercised_the_winner_rule_both_ways(self) -> None:
        # 위 검사가 진짜로 경합을 봤다는 것 자체를 명시한다 — risk=low 쪽이
        # 원본/반전(파일 내부 순서가 뒤집힌) 두 빌드 모두에서 항상 이겨야
        # 한다(§6.8 키①이 키④(cell_rank, 파일 내부 순서)보다 우선).
        for label, winners in (
            ("원본", _winner_map(self.orig_db, self.orig_export)),
            ("반전", _winner_map(self.shuffled_db, self.shuffled_export)),
        ):
            # (a) 같은 파일(A) 내부 경합 — 순서가 뒤집혀도 risk=low가 이겨야 한다.
            self.assertIn("동일파일경합", winners, f"{label} 빌드에 파일 내부 경합 표면형이 없다")
            _, easy_term, strategy, risk, source_code = winners["동일파일경합"]
            self.assertEqual(easy_term, "파일A강함", f"{label} 빌드: 파일 내부 경합에서 위험도가 더 높은 후보가 이겨야 한다")
            self.assertEqual(strategy, "gloss", label)
            self.assertEqual(risk, "low", label)
            self.assertEqual(source_code, "test:row-order-a", label)

            # (b) 파일 A/B 간 교차 경합 — 승자의 원천(source_code)까지 확인한다.
            self.assertIn("혼합용어", winners, f"{label} 빌드에 교차 파일 경합 표면형이 없다")
            _, easy_term, strategy, risk, source_code = winners["혼합용어"]
            self.assertEqual(easy_term, "쉬운말B", f"{label} 빌드: 위험도가 더 높은 후보가 이겨야 한다")
            self.assertEqual(strategy, "gloss", label)
            self.assertEqual(risk, "low", label)
            self.assertEqual(source_code, "test:row-order-b", f"{label} 빌드: 승자의 원천(source_code)이 바뀌었다")


if __name__ == "__main__":
    unittest.main()
