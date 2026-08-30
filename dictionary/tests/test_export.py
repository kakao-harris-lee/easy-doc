"""src/easydict/export.py 계약 테스트, 특히 `easy_dict.simple.jsonl`의 안전 계약 (§4.4).

배경: `simple.jsonl`은 `replace_strategy`가 없는 `{term, easy_term, category}`
평문 포맷이라, 순진하게 `{term: easy_term}` 딕셔너리로 읽어 `str.replace`를
돌려도(§2.1이 경고한 방식 그대로) 법령명·금액이 지워지거나 `과태료`가
`벌금`류로 둔갑하는 사고가 나면 안 된다. 이 계약:

  substitute -> easy_term 그대로
  gloss      -> "원어(easy_term)"로 합성 (원어가 결과에 항상 남음)
  keep       -> 행 자체를 아예 안 실음
  status='review' -> 행 자체를 아예 안 실음

export.py는 아직 이 계약대로 안 고쳐졌을 수 있다. import는 되지만(다른
E 라운드에서 이미 존재가 확인됨) simple.jsonl 안전 계약이 아직 안 들어갔을
수 있으므로, 이 테스트는 실패해도 정상일 수 있다 — 그 경우 실패 출력을
그대로 보고한다(고치지 않는다).

`dist/`는 절대 읽거나 쓰지 않는다 — 임시 SQLite/출력 파일만 tempfile로
만들고 tearDown에서 정리한다. data/, src/ 는 읽기만 한다.
"""

from __future__ import annotations

import json
import shutil
import sqlite3
import tempfile
import unittest
from pathlib import Path

try:
    from easydict import export as export_mod
    _IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover
    export_mod = None  # type: ignore[assignment]
    _IMPORT_ERROR = e

REPO_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"


def _insert_entry(
    conn: sqlite3.Connection,
    *,
    term: str,
    easy_term: str,
    replace_strategy: str,
    risk_level: str,
    status: str,
    readability: int,
    confidence: float,
    checksum: str,
    primary_tag: str | None = None,
    extra_tags: tuple[str, ...] = (),
) -> int:
    conn.execute(
        """
        INSERT INTO entries
            (term, term_norm, easy_term, replace_strategy, risk_level, status,
             readability, confidence, checksum)
        VALUES (?,?,?,?,?,?,?,?,?)
        """,
        (term, term, easy_term, replace_strategy, risk_level, status,
         readability, confidence, checksum),
    )
    entry_id = conn.execute("SELECT id FROM entries WHERE checksum = ?", (checksum,)).fetchone()[0]

    tags = list(extra_tags)
    if primary_tag and primary_tag not in tags:
        tags.append(primary_tag)
    for tag in tags:
        conn.execute(
            "INSERT INTO entry_tags (entry_id, tag_name, is_primary) VALUES (?, ?, ?)",
            (entry_id, tag, 1 if tag == primary_tag else 0),
        )
    return entry_id


@unittest.skipUnless(_IMPORT_ERROR is None, f"easydict.export import 실패: {_IMPORT_ERROR}")
class ExportTestCase(unittest.TestCase):
    """공통 픽스처: keep/gloss/substitute/review/deprecated 를 섞은 SQLite DB.

    법령명(keep, bracket) / 금액(keep) / 과태료(gloss, review — gloss와 review가
    동시에 겹칠 때 review 제외가 우선한다는 것을 확인) / 내방(substitute, active) /
    차상위(gloss, risk=low/active — gloss 자기보존 합성이 실제로 출력에
    나타나는지 확인) / 민감시범용어(substitute인데 status=review — strategy와
    무관하게 review는 걸러져야 함을 독립적으로 검증) / 가상제도법(keep인데
    status=active — keep 제외가 review 상태와 무관하게 그 자체로 독립 규칙임을
    검증. 현재 실 데이터의 keep 8건은 전부 review와 겹치지만, 그렇다고 keep
    규칙이 review에 얹혀가는 부수효과여서는 안 된다) / 폐지된용어(deprecated,
    기존 제외 규칙과의 회귀 확인용) 8개.
    """

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_export_test_"))
        self.conn = sqlite3.connect(":memory:")
        self.conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))

        self.law_id = _insert_entry(
            self.conn,
            term="「국민기초생활 보장법」", easy_term="법 이름이니 그대로 씀",
            replace_strategy="keep", risk_level="high", status="review",
            readability=3, confidence=0.5, checksum="exp0000000000001",
            primary_tag="law",
        )
        self.gwataeryo_id = _insert_entry(
            self.conn,
            term="과태료", easy_term="정해진 법을 안 지켜서 내는 돈",
            replace_strategy="gloss", risk_level="high", status="review",
            readability=2, confidence=0.5, checksum="exp0000000000002",
            primary_tag="law",
        )
        self.naebang_id = _insert_entry(
            self.conn,
            term="내방", easy_term="방문",
            replace_strategy="substitute", risk_level="none", status="active",
            readability=1, confidence=0.9, checksum="exp0000000000003",
            primary_tag="admin",
        )
        # 과태료(review)와 달리 risk_level='low' + status='active' 인 gloss 엔트리.
        # export_simple의 review 필터에 걸리지 않고 실제로 출력되므로, gloss
        # 자기보존 합성('원어(easy_term)')이 simple.jsonl에 실제로 나타나는지
        # 검증하려면 review로 걸러지지 않는 gloss 엔트리가 따로 필요하다.
        self.chasangwi_id = _insert_entry(
            self.conn,
            term="차상위", easy_term="형편이 조금 나은 사람",
            replace_strategy="gloss", risk_level="low", status="active",
            readability=2, confidence=0.85, checksum="exp0000000000007",
            primary_tag="welfare",
        )
        self.amount_id = _insert_entry(
            self.conn,
            term="월 30만 원", easy_term="금액이니 그대로 씀",
            replace_strategy="keep", risk_level="high", status="review",
            readability=3, confidence=0.5, checksum="exp0000000000004",
            primary_tag="admin",
        )
        # strategy는 substitute이지만 status=review인 엔트리: review 필터가
        # keep 필터와 별개로 독립적으로 동작하는지 확인하기 위한 엣지 케이스.
        self.pending_id = _insert_entry(
            self.conn,
            term="민감시범용어", easy_term="시범 순화어",
            replace_strategy="substitute", risk_level="high", status="review",
            readability=2, confidence=0.5, checksum="exp0000000000005",
            primary_tag="jargon",
        )
        self.deprecated_id = _insert_entry(
            self.conn,
            term="폐지된용어", easy_term="옛말",
            replace_strategy="substitute", risk_level="none", status="deprecated",
            readability=1, confidence=0.5, checksum="exp0000000000006",
            primary_tag="admin",
        )
        # keep + status=active 조합: 실 데이터엔 없지만(keep 8건이 전부 review),
        # keep 제외가 review 필터에 얹혀가는 부수효과가 아니라 그 자체로 독립
        # 규칙인지 검증하려면 review와 분리된 keep 엔트리가 필요하다.
        self.gasang_law_id = _insert_entry(
            self.conn,
            term="「가상제도법」", easy_term="법 이름이니 그대로 씀",
            replace_strategy="keep", risk_level="none", status="active",
            readability=3, confidence=0.9, checksum="exp0000000000008",
            primary_tag="law",
        )
        self.conn.commit()

    def tearDown(self) -> None:
        self.conn.close()
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _export_simple_lines(self) -> list[dict]:
        out = export_mod.export_simple(self.conn, self.tmpdir / "easy_dict.simple.jsonl")
        lines = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines() if line.strip()]
        return lines


class TestSimpleJsonlSafety(ExportTestCase):
    def test_keep_rows_absent(self) -> None:
        lines = self._export_simple_lines()
        terms = {line["term"] for line in lines}
        self.assertNotIn("「국민기초생활 보장법」", terms, "keep 대상(법령명)이 simple.jsonl에 실리면 안 된다")
        self.assertNotIn("월 30만 원", terms, "keep 대상(금액)이 simple.jsonl에 실리면 안 된다")

    def test_keep_excluded_independent_of_review_status(self) -> None:
        # keep 제외는 review 필터의 부수효과가 아니라 그 자체로 독립된 규칙이어야
        # 한다. 실 데이터의 keep 8건은 전부 review와 겹치지만, keep+active 조합이
        # 생겨도(예: 검수를 마쳐 status가 바뀌어도) keep은 여전히 제외돼야 한다.
        lines = self._export_simple_lines()
        terms = {line["term"] for line in lines}
        self.assertNotIn(
            "「가상제도법」", terms,
            "keep+status=active 조합도 keep이므로 simple.jsonl에 실리면 안 된다 (review 필터와 무관한 독립 규칙)",
        )

    def test_review_rows_absent(self) -> None:
        lines = self._export_simple_lines()
        terms = {line["term"] for line in lines}
        # strategy가 keep이 아닌데도 status=review인 엔트리가 걸러지는지 별도 확인.
        self.assertNotIn("민감시범용어", terms, "status=review 엔트리는 strategy와 무관하게 실리면 안 된다")

    def test_gloss_and_review_together_review_wins(self) -> None:
        # 명시적 우선순위 계약: gloss 자기보존 규칙과 review 제외 규칙이 동시에
        # 적용되는 엔트리(과태료: gloss + risk=high -> status=review)에서는
        # review 제외가 이긴다. "gloss니까 원어(설명) 형태로라도 나오겠지"가
        # 틀린 기대라는 것을 계약으로 못박는다 — 검수 전 데이터는 어떤 형태로도
        # 새어나가면 안 된다.
        lines = self._export_simple_lines()
        terms = {line["term"] for line in lines}
        self.assertNotIn(
            "과태료", terms,
            "gloss+review가 겹치면 review 제외가 우선한다 — '과태료(...)' 형태로도 실리면 안 된다",
        )

    def test_deprecated_rows_absent(self) -> None:
        lines = self._export_simple_lines()
        terms = {line["term"] for line in lines}
        self.assertNotIn("폐지된용어", terms)

    def test_gloss_self_preserving(self) -> None:
        # '차상위'는 risk_level=low/status=active인 gloss 엔트리라 review 필터에
        # 걸리지 않고 실제로 출력된다 (과태료는 review라 애초에 안 실림 -> 별도 테스트).
        lines = self._export_simple_lines()
        by_term = {line["term"]: line for line in lines}
        self.assertIn("차상위", by_term, "risk=low인 gloss 엔트리는 review 필터에 걸리지 않고 실려야 한다")
        self.assertIn(
            "차상위", by_term["차상위"]["easy_term"],
            "gloss 엔트리의 easy_term은 원어를 부분문자열로 포함해 자기보존이 되어야 한다",
        )

    def test_naive_replace_preserves_protected_substrings(self) -> None:
        """핵심 테스트: 파일을 dict로 읽어 순진하게 str.replace를 돌려도 안전해야 한다."""
        lines = self._export_simple_lines()
        replacements = {line["term"]: line["easy_term"] for line in lines}

        text = (
            "「국민기초생활 보장법」에 따라 과태료가 부과되며 "
            "차상위 가구는 월 30만 원을 받습니다."
        )
        for k, v in replacements.items():
            text = text.replace(k, v)

        self.assertIn("「국민기초생활 보장법」", text, "keep 대상(법령명)이 순진한 치환 후에도 남아있어야 한다")
        self.assertIn("월 30만 원", text, "keep 대상(금액)이 순진한 치환 후에도 남아있어야 한다")
        self.assertIn("과태료", text, "과태료는 review라 애초에 dict에 없으므로 순진한 치환에도 안 지워져야 한다")
        self.assertIn("차상위", text, "차상위는 gloss 자기보존 합성('차상위(...)')으로 순진한 치환 후에도 남아있어야 한다")

    def test_line_format_contract(self) -> None:
        lines = self._export_simple_lines()
        self.assertGreater(len(lines), 0, "테스트 픽스처에는 최소 substitute 엔트리(내방)가 남아있어야 한다")
        for line in lines:
            self.assertEqual(
                set(line.keys()), {"term", "easy_term", "category"},
                f"simple.jsonl 각 행은 term/easy_term/category 3개 키만 가져야 한다: {line}",
            )

        by_term = {line["term"]: line for line in lines}
        self.assertEqual(by_term["내방"]["category"], "행정", "category는 한국어 label(예: '행정')이어야 한다")
        self.assertEqual(by_term["차상위"]["category"], "복지")


class TestOtherExportsUnaffectedRegression(ExportTestCase):
    """simple.jsonl의 새 필터링이 export_full/export_index로 새어나가면 안 된다.

    특히 index.json에서 keep 엔트리가 빠지면 lookup.EasyDict.annotate()가
    법령명을 더 이상 보호하지 못하게 되는, simple.jsonl 누락보다 더 큰 사고다.
    """

    def test_export_full_still_includes_keep_and_review(self) -> None:
        out = export_mod.export_full(self.conn, self.tmpdir / "easy_dict.json")
        doc = json.loads(out.read_text(encoding="utf-8"))
        terms = {e["term"] for e in doc["entries"]}
        self.assertIn("「국민기초생활 보장법」", terms, "export_full은 keep 엔트리를 계속 포함해야 한다")
        self.assertIn("과태료", terms, "export_full은 review 엔트리를 계속 포함해야 한다")
        self.assertIn("민감시범용어", terms)
        self.assertIn("「가상제도법」", terms, "export_full은 keep+active 엔트리도 포함해야 한다")
        # 2026-08-28 계약 변경: export_full은 감사 추적이 목적이라 deprecated도
        # "포함"으로 뒤집혔다 (index.json/simple.jsonl은 여전히 제외 — 아래
        # TestDeprecatedHandlingAcrossExports 참고). 예전 계약("제외되어야
        # 한다")을 그대로 두면 새 계약과 정반대를 주장하게 되므로 뒤집는다.
        self.assertIn(
            "폐지된용어", terms,
            "export_full은 감사 추적 목적이라 deprecated도 포함해야 한다 (2026-08-28 계약 변경)",
        )

    def test_export_index_still_includes_keep_and_review(self) -> None:
        out = export_mod.export_index(self.conn, self.tmpdir / "easy_dict.index.json")
        doc = json.loads(out.read_text(encoding="utf-8"))

        self.assertIn(
            "「국민기초생활 보장법」", doc["surface_index"],
            "index.json에 keep 엔트리가 없으면 annotate가 법령명을 보호하지 못한다(더 큰 사고)",
        )
        law_ids = doc["surface_index"]["「국민기초생활 보장법」"]
        self.assertTrue(any(doc["entries"][str(i)]["s"] == "keep" for i in law_ids))

        gwataeryo_ids = doc["surface_index"].get("과태료", [])
        self.assertTrue(gwataeryo_ids, "index.json은 review 상태인 과태료도 포함해야 한다")

        all_terms = {e["t"] for e in doc["entries"].values()}
        self.assertNotIn("폐지된용어", all_terms, "deprecated는 기존과 동일하게 제외되어야 한다")


class TestDeprecatedHandlingAcrossExports(ExportTestCase):
    """2026-08-28 계약: `status='deprecated'` 엔트리가 산출물 3종에서 서로 다르게
    취급된다.

    | 산출물 | deprecated | 이유 |
    |---|---|---|
    | easy_dict.json (full) | 포함 | 감사 추적: "왜 사전에 없나"에 답하려면 남아야 함 |
    | easy_dict.index.json | 제외 | 미검수/폐기 내용이 LLM 프롬프트에 가면 안 됨 (가장 위험) |
    | easy_dict.simple.jsonl | 제외 | §4.4 안전 계약 |

    index.json 쪽이 훨씬 위험하다 — 실수로 새어 들어가면 검수 안 된 내용이
    그대로 문서 변환 프롬프트에 주입된다. 그래서 3, 5번을 가장 무겁게 본다.
    """

    def test_full_includes_deprecated_with_status_field_exposed(self) -> None:
        out = export_mod.export_full(self.conn, self.tmpdir / "easy_dict.json")
        doc = json.loads(out.read_text(encoding="utf-8"))
        by_term = {e["term"]: e for e in doc["entries"]}
        self.assertIn("폐지된용어", by_term, "easy_dict.json은 deprecated 엔트리를 포함해야 한다(감사 추적)")
        self.assertEqual(
            by_term["폐지된용어"]["status"], "deprecated",
            "easy_dict.json의 각 엔트리는 status 필드를 노출해야 소비자가 걸러낼 수 있다",
        )
        # 참고로 active 엔트리도 status가 제대로 노출되는지 같이 확인.
        self.assertEqual(by_term["내방"]["status"], "active")

    def test_full_counts_deprecated_matches_actual(self) -> None:
        actual_deprecated = self.conn.execute(
            "SELECT COUNT(*) FROM entries WHERE status = 'deprecated'"
        ).fetchone()[0]
        self.assertEqual(actual_deprecated, 1, "픽스처 전제 확인: deprecated는 '폐지된용어' 1건이어야 한다")

        out = export_mod.export_full(self.conn, self.tmpdir / "easy_dict.json")
        doc = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(
            doc["counts"]["deprecated"], actual_deprecated,
            "counts.deprecated가 DB의 실제 deprecated 건수와 일치해야 한다",
        )

    def test_index_json_excludes_deprecated(self) -> None:
        # 가장 위험한 케이스: index.json은 easy-doc(Kotlin)이 실제로 로드해
        # LLM 프롬프트 생성에 쓰는 파일이다. 여기 deprecated가 새면 미검수/
        # 폐기된 내용이 그대로 프롬프트에 들어간다.
        out = export_mod.export_index(self.conn, self.tmpdir / "easy_dict.index.json")
        doc = json.loads(out.read_text(encoding="utf-8"))

        self.assertNotIn(
            "폐지된용어", doc["surface_index"],
            "index.json의 surface_index에 deprecated 표제어가 있으면 안 된다 (가장 위험한 회귀)",
        )
        all_terms = {e["t"] for e in doc["entries"].values()}
        self.assertNotIn(
            "폐지된용어", all_terms,
            "index.json의 entries 딕셔너리에도 deprecated가 남아있으면 안 된다",
        )

    def test_simple_jsonl_excludes_deprecated(self) -> None:
        out = export_mod.export_simple(self.conn, self.tmpdir / "easy_dict.simple.jsonl")
        lines = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines() if line.strip()]
        terms = {line["term"] for line in lines}
        self.assertNotIn("폐지된용어", terms, "simple.jsonl은 §4.4 안전 계약에 따라 deprecated를 제외해야 한다")

    def test_full_entry_count_matches_db_total(self) -> None:
        # 회귀 방어: export_full의 필터(_ALL_ENTRIES_SQL)가 실수로 export_index/
        # export_simple 쪽 필터(_ACTIVE_ENTRIES_SQL, status != 'deprecated')와
        # 다시 섞이면 이 개수가 DB 총 엔트리 수보다 작아진다.
        db_total = self.conn.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
        out = export_mod.export_full(self.conn, self.tmpdir / "easy_dict.json")
        doc = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(
            len(doc["entries"]), db_total,
            "export_full의 엔트리 수는 상태 필터 없이 DB 전체 엔트리 수와 일치해야 한다",
        )
        self.assertEqual(doc["counts"]["entries"], db_total)


if __name__ == "__main__":
    unittest.main()
