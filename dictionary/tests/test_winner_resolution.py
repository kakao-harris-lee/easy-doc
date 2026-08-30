"""동일 표면형에 후보가 여럿일 때의 승자 결정 회귀 테스트 (DESIGN.md §6.8).

실측된 결함: 같은 표제어에 전략(`replace_strategy`)·위험도(`risk_level`)가
다른 엔트리가 여럿이면, 예전에는 `priority = 100 + len(term)*10`가 항상
동률이라 `max()`가 첫 원소(=CSV에서 먼저 등장한 행, `entries.id`가 작은
쪽)를 승자로 골랐다. 실데이터에서 이 규칙 때문에 `독거노인`은 '홀로
노인'(substitute, risk=none)이 이겨서 '홀로 사는 노인'(gloss, risk=low)이
문서에서 완전히 사라졌고, `카시트`도 같은 이유로 '아이 안전 의자'(gloss)가
사라졌다. 승자가 아닌 후보는 상충 지침으로 남지 않고 `find_all()`이 겹치는
위치를 하나로 정리하는 과정에서 그 문서에서 완전히 사라진다 — "무음 실종".

§6.8이 정한 새 규칙: risk_level(①) -> 원천 신뢰도(②) -> 전략 보수성(③) ->
easy_term 사전순(④) 내림차순(단, ④만 오름차순) 정렬 키로 export.py가
승자를 export 시점에 구워 넣는다(`winner_sort_key()`). 조회 시점(lookup.py)
비교자는 두지 않는다 — 이미 정렬된 리스트의 첫 원소를 그대로 쓴다.

### 이 파일의 테스트 설계가 파일 재정렬(`--input` 순서 뒤집기)로는 이 결함을
못 잡는 이유 (§6.8 "이 결함을 잡는 회귀 테스트는 파일 순서를 바꿔서는 만들
수 없다")

실측된 50건은 전부 `nikl:admin2018` **한 원천 내부**의 문제라 `--input` 파일
간 순서를 아무리 바꿔도 건드려지지 않는다 — 같은 파일 **안**의 행 순서가
원인이다. 그래서 아래 `TestBuildPipelineRowOrderIndependence`는 파일을
두 개로 쪼개지 않고, **한 CSV 파일 안에서 같은 표제어의 행 순서만 뒤바꾼
두 버전**을 만들어 같은 승자가 나오는지 확인한다. 그리고 승자를 값으로
직접 단언한다(`frozenset` 등 집합 비교로 순서 정보를 지우지 않는다) — 기존
`test_index_json_semantically_identical_ignoring_ids`(tests/test_reproducibility.py)는
후보 **집합**이 같은지만 보고 "누가 이기는가"는 보지 않으므로 이 결함을
원리상 검출할 수 없다.

`dist/`와 `data/raw/`는 읽지도 쓰지도 않는다 — 모든 픽스처는 임시 디렉터리에서
만들고 정리한다.
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
    from easydict import export as export_mod
    _EXPORT_IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover
    export_mod = None  # type: ignore[assignment]
    _EXPORT_IMPORT_ERROR = e

try:
    from easydict import build as build_mod
    _BUILD_IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover
    build_mod = None  # type: ignore[assignment]
    _BUILD_IMPORT_ERROR = e

try:
    from easydict.lookup import EasyDict
    _LOOKUP_IMPORT_ERROR: Exception | None = None
except ImportError as e:  # pragma: no cover
    EasyDict = None  # type: ignore[assignment]
    _LOOKUP_IMPORT_ERROR = e

REPO_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_SQL_PATH = REPO_ROOT / "schema" / "schema.sql"


# =============================================================================
# 1. winner_sort_key() 순수 함수 단위 테스트 — 키 ①②③④⑤가 각각 실제로
#    작동하는지 값으로 단언한다. DB/빌드 없이 dict만으로 구성해 빠르고 정확하다.
# =============================================================================
@unittest.skipUnless(_EXPORT_IMPORT_ERROR is None, f"easydict.export import 실패: {_EXPORT_IMPORT_ERROR}")
class TestWinnerSortKeyPureFunction(unittest.TestCase):
    """§6.8 정렬 키 5개를 각각 독립적으로 검증한다."""

    @staticmethod
    def _row(
        *,
        easy_term: str,
        replace_strategy: str,
        risk_level: str,
        source_code: str | None,
        cell_rank: int = 0,
    ) -> dict:
        return {
            "easy_term": easy_term,
            "replace_strategy": replace_strategy,
            "risk_level": risk_level,
            "source_code": source_code,
            "cell_rank": cell_rank,
        }

    def _winner(self, rows: list[dict]) -> dict:
        return min(rows, key=export_mod.winner_sort_key)

    # ------------------------------------------------------------------
    # 키 ① risk_level: high > low > none. 다른 키(전략·원천)가 반대를
    # 가리켜도 risk_level이 항상 이긴다 — "위험도는 세상에 대한 사실
    # 주장이라 항상 최우선"(§6.8).
    # ------------------------------------------------------------------
    def test_risk_level_beats_everything_else(self) -> None:
        # high+substitute+미등록 원천 vs none+keep+최고 신뢰도 원천.
        # 전략(③)·원천(②) 둘 다 후자를 편들어도 risk가 이긴다.
        high_risk = self._row(
            easy_term="위험함", replace_strategy="substitute", risk_level="high",
            source_code="unregistered:source",
        )
        safe_but_conservative = self._row(
            easy_term="보수적", replace_strategy="keep", risk_level="none",
            source_code="easydict:welfare-seed1",
        )
        winner = self._winner([high_risk, safe_but_conservative])
        self.assertEqual(winner["easy_term"], "위험함")

    def test_risk_low_beats_risk_none(self) -> None:
        # 실측 사례(독거노인/카시트)의 핵심: risk=low(gloss) vs risk=none(substitute).
        low = self._row(easy_term="홀로 사는 노인", replace_strategy="gloss",
                         risk_level="low", source_code="nikl:admin2018")
        none = self._row(easy_term="홀로 노인", replace_strategy="substitute",
                          risk_level="none", source_code="nikl:admin2018")
        winner = self._winner([none, low])  # none을 먼저 넣어도(구 규칙이면 이게 이겼다)
        self.assertEqual(winner["easy_term"], "홀로 사는 노인")

    # ------------------------------------------------------------------
    # 키 ② 원천 신뢰도: 수작업 검수(easydict) > 사전 API(krdict) > 대량
    # 자동(nikl). risk_level이 동률일 때만 이 키가 결정한다.
    # ------------------------------------------------------------------
    def test_source_trust_hand_reviewed_beats_dictionary_api(self) -> None:
        hand_reviewed = self._row(easy_term="손으로 검수함", replace_strategy="substitute",
                                   risk_level="none", source_code="easydict:welfare-seed1")
        dictionary_api = self._row(easy_term="사전 API", replace_strategy="substitute",
                                    risk_level="none", source_code="krdict:advanced")
        winner = self._winner([dictionary_api, hand_reviewed])
        self.assertEqual(winner["easy_term"], "손으로 검수함")

    def test_source_trust_dictionary_api_beats_mass_auto(self) -> None:
        dictionary_api = self._row(easy_term="사전 API", replace_strategy="substitute",
                                    risk_level="none", source_code="krdict:advanced-v2")
        mass_auto = self._row(easy_term="대량 자동", replace_strategy="substitute",
                               risk_level="none", source_code="nikl:admin2018")
        winner = self._winner([mass_auto, dictionary_api])
        self.assertEqual(winner["easy_term"], "사전 API")

    def test_source_trust_beats_strategy_when_risk_tied(self) -> None:
        # risk_level이 같으면 원천 신뢰도(②)가 전략 보수성(③)보다 먼저 결정한다.
        # 여기서는 신뢰도 낮은 원천이 더 보수적인 전략(keep)을 줘도 지도록 구성했다.
        trusted_but_liberal = self._row(
            easy_term="신뢰도 높음", replace_strategy="substitute", risk_level="none",
            source_code="easydict:welfare-seed2",
        )
        untrusted_but_conservative = self._row(
            easy_term="신뢰도 낮음", replace_strategy="keep", risk_level="none",
            source_code="nikl:admin2018",
        )
        winner = self._winner([untrusted_but_conservative, trusted_but_liberal])
        self.assertEqual(winner["easy_term"], "신뢰도 높음")

    def test_unregistered_source_is_lowest_tier(self) -> None:
        # 표에 없는 원천 코드는 nikl:admin2018(등록된 것 중 최하위)보다도 낮게
        # 취급되어야 한다 — "미등록 원천이 조용히 최상위가 되는 사고"를 막는 것.
        registered_lowest = self._row(easy_term="등록된 최하위", replace_strategy="substitute",
                                       risk_level="none", source_code="nikl:admin2018")
        unregistered = self._row(easy_term="미등록", replace_strategy="substitute",
                                  risk_level="none", source_code="totally-new-source:v1")
        winner = self._winner([unregistered, registered_lowest])
        self.assertEqual(winner["easy_term"], "등록된 최하위")

    def test_unregistered_sources_tiebreak_by_code_alphabetical(self) -> None:
        # 서로 다른 두 미등록 원천끼리는 같은(최하위) 등급으로 묶이므로
        # 코드 사전순으로 갈라야 결정적으로 동작한다.
        a = self._row(easy_term="A코드 소속", replace_strategy="substitute",
                       risk_level="none", source_code="aaa:unknown")
        z = self._row(easy_term="Z코드 소속", replace_strategy="substitute",
                       risk_level="none", source_code="zzz:unknown")
        winner = self._winner([z, a])
        self.assertEqual(winner["easy_term"], "A코드 소속", "미등록 원천끼리는 코드 사전순으로 갈라야 한다")

    def test_missing_source_code_does_not_crash_and_is_lowest(self) -> None:
        # source_code가 None(원천 미연결)이어도 예외 없이 최하위로 처리되어야 한다.
        no_source = self._row(easy_term="원천 없음", replace_strategy="substitute",
                               risk_level="none", source_code=None)
        registered = self._row(easy_term="원천 있음", replace_strategy="substitute",
                                risk_level="none", source_code="nikl:admin2018")
        winner = self._winner([no_source, registered])
        self.assertEqual(winner["easy_term"], "원천 있음")

    # ------------------------------------------------------------------
    # 키 ③ 전략 보수성: keep > gloss > substitute. risk_level·원천이
    # 동률일 때만 이 키가 결정한다.
    # ------------------------------------------------------------------
    def test_strategy_keep_beats_gloss_beats_substitute_when_risk_and_source_tied(self) -> None:
        same = dict(risk_level="none", source_code="nikl:admin2018")
        keep = self._row(easy_term="유지함", replace_strategy="keep", **same)
        gloss = self._row(easy_term="설명함", replace_strategy="gloss", **same)
        substitute = self._row(easy_term="교체함", replace_strategy="substitute", **same)

        self.assertEqual(self._winner([substitute, gloss, keep])["easy_term"], "유지함")
        self.assertEqual(self._winner([substitute, gloss])["easy_term"], "설명함")

    # ------------------------------------------------------------------
    # 키 ④ 원천이 제시한 순서(cell_rank): risk_level·원천 신뢰도·전략이
    # 전부 동률일 때, 원천 CSV가 한 셀에 순화어를 여러 개 나열한 순서 중
    # 앞에 쓴 것이 이긴다. 실측 사례: "아이시티, ICT(...)","정보 통신 기술,
    # 정보 문화 기술" — 앞의 '정보 통신 기술'이 맞는 답인데, 이 신호가
    # 없으면(옛 §6.8, 키 4개뿐) 키⑤(사전순) 하나로 '정보 문화 기술'이
    # 이겨서 오답이 났다(§6.8 개정 근거).
    # ------------------------------------------------------------------
    def test_cell_rank_prefers_first_listed_candidate_ict_regression(self) -> None:
        same = dict(replace_strategy="gloss", risk_level="low", source_code="nikl:admin2018")
        first_in_cell = self._row(easy_term="정보 통신 기술", cell_rank=0, **same)
        second_in_cell = self._row(easy_term="정보 문화 기술", cell_rank=1, **same)
        # 입력 순서를 뒤집어 넣어도(사전순이었다면 '정보 문화 기술'이 이겼을
        # 순서) cell_rank가 결정하므로 결과가 바뀌면 안 된다.
        winner = self._winner([second_in_cell, first_in_cell])
        self.assertEqual(
            winner["easy_term"], "정보 통신 기술",
            "원천이 셀에서 먼저 적은 순화어가 이겨야 한다(§6.8 키 ④). "
            f"실제 승자: {winner['easy_term']!r} — 사전순으로 잘못 갈렸다면 이 값이 된다",
        )

    def test_cell_rank_loses_to_earlier_keys(self) -> None:
        # cell_rank는 키①②③보다 뒤다 — risk가 다르면 cell_rank가 나중이어도 이긴다.
        earlier_in_cell_but_risky = self._row(
            easy_term="위험한 첫 후보", replace_strategy="substitute", risk_level="none",
            source_code="nikl:admin2018", cell_rank=0,
        )
        later_in_cell_but_safe = self._row(
            easy_term="안전한 둘째 후보", replace_strategy="gloss", risk_level="low",
            source_code="nikl:admin2018", cell_rank=1,
        )
        winner = self._winner([earlier_in_cell_but_risky, later_in_cell_but_safe])
        self.assertEqual(winner["easy_term"], "안전한 둘째 후보")

    # ------------------------------------------------------------------
    # 키 ⑤ easy_term 사전순: 나머지 네 키가 전부 동률일 때의 결정적 최종 키.
    # cell_rank 정보가 없는(또는 둘 다 0인) 원천끼리 붙었을 때가 정확히
    # 이 경우다 — "순번 정보가 없는 원천도 결정적으로 동작해야 한다"는
    # §6.8 요구사항을 검증한다.
    # ------------------------------------------------------------------
    def test_easy_term_alphabetical_is_final_tiebreak(self) -> None:
        same = dict(replace_strategy="gloss", risk_level="low", source_code="nikl:admin2018")
        b = self._row(easy_term="나중 순서 말", **same)
        a = self._row(easy_term="가나다 순서 말", **same)
        winner = self._winner([b, a])
        self.assertEqual(winner["easy_term"], "가나다 순서 말")

    def test_missing_cell_rank_defaults_to_zero_and_ties_deterministically(self) -> None:
        # cell_rank를 아예 안 준(과거 원천/외부 데이터를 흉내 낸) 두 후보.
        # winner_sort_key()가 .get("cell_rank", 0)으로 방어하므로 둘 다 0으로
        # 취급되어 키④에서 동률 -> 키⑤(easy_term 사전순)로 결정적으로 갈려야 한다.
        no_rank_b: dict = {
            "easy_term": "나중 순서 말", "replace_strategy": "gloss",
            "risk_level": "low", "source_code": "unregistered:legacy",
        }
        no_rank_a: dict = {
            "easy_term": "가나다 순서 말", "replace_strategy": "gloss",
            "risk_level": "low", "source_code": "unregistered:legacy",
        }
        self.assertNotIn("cell_rank", no_rank_a)  # 정말 없는 채로 테스트한다
        winner = self._winner([no_rank_b, no_rank_a])
        self.assertEqual(winner["easy_term"], "가나다 순서 말")

    def test_sort_key_order_is_independent_of_input_order(self) -> None:
        # sorted()로 전체 정렬했을 때도 min()과 같은 승자가 1번으로 온다 —
        # export.py/lookup.py 둘 다 이 방식(정렬 후 첫 원소)을 쓴다.
        rows = [
            self._row(easy_term="세번째", replace_strategy="substitute", risk_level="none", source_code="nikl:admin2018"),
            self._row(easy_term="첫번째", replace_strategy="gloss", risk_level="high", source_code="nikl:admin2018"),
            self._row(easy_term="두번째", replace_strategy="gloss", risk_level="low", source_code="nikl:admin2018"),
        ]
        ordered = sorted(rows, key=export_mod.winner_sort_key)
        self.assertEqual([r["easy_term"] for r in ordered], ["첫번째", "두번째", "세번째"])


# =============================================================================
# 2. export_index()가 실제로 SQLite -> surface_index 순서에 이 키를 적용하는지
#    (배선 자체를 검증). id 순서와 승자 순서가 어긋나도록 일부러 id를 뒤섞어
#    "예전 규칙(ORDER BY id)이었다면 이걸 골랐을 것"과 다른 승자가 나오는지 확인한다.
# =============================================================================
def _insert_source(conn: sqlite3.Connection, code: str, name: str = "테스트 원천") -> int:
    conn.execute("INSERT INTO sources (code, name) VALUES (?, ?)", (code, name))
    return conn.execute("SELECT id FROM sources WHERE code = ?", (code,)).fetchone()[0]


def _insert_entry_with_id(
    conn: sqlite3.Connection,
    *,
    entry_id: int,
    term: str,
    easy_term: str,
    replace_strategy: str,
    risk_level: str,
    source_id: int | None,
    checksum: str,
    priority: int | None = None,
    cell_rank: int = 0,
) -> None:
    # priority를 명시하지 않으면 schema.sql의 DEFAULT 100이 적용된다. 서로 다른
    # 길이의 표제어가 같은 표면형에서 충돌하는 시나리오(TestPriorityStillGovernsCrossHeadwordCollisions)를
    # 재현하려면 build.py의 100+len(term)*10 공식을 흉내 낸 값을 직접 넣어야 한다
    # — 이 테스트 픽스처는 build.py를 거치지 않고 SQLite에 직접 적재하기 때문이다.
    # cell_rank는 명시하지 않으면 schema.sql의 DEFAULT 0(=신호 없음)이 적용된다
    # — §6.8 키 ④(원천이 셀에서 제시한 순서) 테스트가 이 값을 직접 채워 넣는다.
    if priority is None:
        conn.execute(
            """
            INSERT INTO entries
                (id, term, term_norm, easy_term, replace_strategy, risk_level,
                 status, readability, confidence, cell_rank, source_id, checksum)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (entry_id, term, term, easy_term, replace_strategy, risk_level,
             "active", 1, 0.8, cell_rank, source_id, checksum),
        )
    else:
        conn.execute(
            """
            INSERT INTO entries
                (id, term, term_norm, easy_term, replace_strategy, risk_level,
                 status, readability, confidence, priority, cell_rank, source_id, checksum)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (entry_id, term, term, easy_term, replace_strategy, risk_level,
             "active", 1, 0.8, priority, cell_rank, source_id, checksum),
        )


def _insert_variant(conn: sqlite3.Connection, *, entry_id: int, surface: str, kind: str = "conjugation") -> None:
    conn.execute(
        "INSERT INTO variants (entry_id, surface, surface_norm, kind, is_auto) VALUES (?,?,?,?,1)",
        (entry_id, surface, surface, kind),
    )


@unittest.skipUnless(_EXPORT_IMPORT_ERROR is None, f"easydict.export import 실패: {_EXPORT_IMPORT_ERROR}")
class TestExportIndexBakesInWinnerOrder(unittest.TestCase):
    """export_index()가 만든 surface_index 리스트의 첫 원소가 §6.8 승자인지 확인한다.

    id를 일부러 "옛 규칙이면 이겼을 순서"로 심어 둔다 — id=1(옛 승자,
    substitute/risk=none)이 가장 작고, id=3(신 승자, gloss/risk=low)이
    가장 크다. `ORDER BY id`만 썼다면 id=1이 여전히 이겼을 것이므로, 이
    테스트가 통과하려면 실제로 winner_sort_key가 적용되어야 한다.
    """

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_winner_export_test_"))
        self.conn = sqlite3.connect(":memory:")
        self.conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))

    def tearDown(self) -> None:
        self.conn.close()
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_winner_by_risk_level_is_first_in_surface_index_despite_id_order(self) -> None:
        nikl_id = _insert_source(self.conn, "nikl:admin2018", "국립국어원 행정용어(2018)")
        # id=1: 옛 규칙의 승자(작은 id, substitute, risk=none) — 신 규칙에서는 져야 한다.
        _insert_entry_with_id(
            self.conn, entry_id=1, term="독거노인", easy_term="홀로 노인",
            replace_strategy="substitute", risk_level="none",
            source_id=nikl_id, checksum="win0000000000001",
        )
        # id=2: 신 규칙의 승자(risk=low가 substitute/none보다 우선한다).
        _insert_entry_with_id(
            self.conn, entry_id=2, term="독거노인", easy_term="홀로 사는 노인",
            replace_strategy="gloss", risk_level="low",
            source_id=nikl_id, checksum="win0000000000002",
        )
        out = export_mod.export_index(self.conn, self.tmpdir / "index.json")
        doc = json.loads(out.read_text(encoding="utf-8"))

        ids = doc["surface_index"]["독거노인"]
        self.assertEqual(set(ids), {1, 2}, "두 후보 모두 리스트에 남아 있어야 한다(패자가 사라지면 안 됨)")
        winner_id = ids[0]
        self.assertEqual(
            doc["entries"][str(winner_id)]["e"], "홀로 사는 노인",
            "첫 원소가 §6.8 규칙대로 risk=low(gloss) 쪽이어야 한다. "
            f"실제 순서: {[doc['entries'][str(i)]['e'] for i in ids]}",
        )

    def test_winner_by_source_trust_is_first_when_risk_tied(self) -> None:
        nikl_id = _insert_source(self.conn, "nikl:admin2018", "대량 자동")
        seed_id = _insert_source(self.conn, "easydict:welfare-seed1", "손으로 검수")
        # id=1: 신뢰도 낮은 원천(nikl)이지만 id가 작다 — 옛 규칙이면 이겼을 후보.
        _insert_entry_with_id(
            self.conn, entry_id=1, term="차상위계층", easy_term="대량 자동 순화어",
            replace_strategy="substitute", risk_level="none",
            source_id=nikl_id, checksum="win0000000000003",
        )
        # id=2: 신뢰도 높은 원천(easydict 시드) — id는 더 크지만 이겨야 한다.
        _insert_entry_with_id(
            self.conn, entry_id=2, term="차상위계층", easy_term="손으로 검수한 순화어",
            replace_strategy="substitute", risk_level="none",
            source_id=seed_id, checksum="win0000000000004",
        )
        out = export_mod.export_index(self.conn, self.tmpdir / "index.json")
        doc = json.loads(out.read_text(encoding="utf-8"))

        winner_id = doc["surface_index"]["차상위계층"][0]
        self.assertEqual(doc["entries"][str(winner_id)]["e"], "손으로 검수한 순화어")

    def test_winner_by_cell_rank_is_first_when_risk_source_strategy_tied_ict_regression(self) -> None:
        """실측 사례(ICT): risk·원천·전략이 전부 같으면 원천이 셀에서 먼저

        적은 순화어가 이겨야 한다. id는 일부러 "사전순이면 이겼을 후보"(정보
        문화 기술, id=1)를 작게 둬서 옛 규칙(사전순만 있던 §6.8)이면 이겼을
        순서로 심는다.
        """
        nikl_id = _insert_source(self.conn, "nikl:admin2018", "대량 자동")
        # id=1: 사전순이면 이겼을 후보('정보 문화 기술' < '정보 통신 기술'
        # 알파벳순 비교에서 지지만, id가 작다 — 그래도 지금은 지는 게 맞다).
        _insert_entry_with_id(
            self.conn, entry_id=1, term="ICT", easy_term="정보 문화 기술",
            replace_strategy="gloss", risk_level="low",
            source_id=nikl_id, checksum="win0000000000005",
            cell_rank=1,  # 원천 셀에서 두 번째로 적힌 것
        )
        # id=2: 원천이 셀에서 먼저 적은 순화어(cell_rank=0) — 이게 이겨야 한다.
        _insert_entry_with_id(
            self.conn, entry_id=2, term="ICT", easy_term="정보 통신 기술",
            replace_strategy="gloss", risk_level="low",
            source_id=nikl_id, checksum="win0000000000006",
            cell_rank=0,  # 원천 셀에서 첫 번째로 적힌 것("아이시티, ICT" 셀의 앞쪽 답)
        )
        out = export_mod.export_index(self.conn, self.tmpdir / "index.json")
        doc = json.loads(out.read_text(encoding="utf-8"))

        winner_id = doc["surface_index"]["ICT"][0]
        self.assertEqual(
            doc["entries"][str(winner_id)]["e"], "정보 통신 기술",
            "ICT는 '정보 통신 기술'이 맞다 — 사전순('정보 문화 기술')이 아니라 "
            "원천이 셀에서 먼저 적은 순화어가 이겨야 한다(§6.8 키 ④).",
        )


# =============================================================================
# 1-1. row_to_entries()가 실제로 cell_rank를 채우는지 — §6.8 키 ④의 데이터
#      출처(build.py)를 확인한다. `easydict.build`를 직접 건드리지 않고
#      `row_to_entries()`만 호출하는 소비자 테스트라 다른 레인(`_finalize_examples`
#      수정 중)과 겹치지 않는다.
# =============================================================================
@unittest.skipUnless(_BUILD_IMPORT_ERROR is None, f"easydict.build import 실패: {_BUILD_IMPORT_ERROR}")
class TestRowToEntriesPopulatesCellRank(unittest.TestCase):
    def test_multi_candidate_cell_gets_sequential_cell_rank(self) -> None:
        from easydict.models import Source

        row = {"원어": "아이시티, ICT(information and communication)", "순화어": "정보 통신 기술, 정보 문화 기술"}
        colmap = build_mod.resolve_columns(list(row.keys()))
        source = Source(code="test:cell-rank", name="테스트용 원천")
        entries = build_mod.row_to_entries(row, colmap, source, lineno=2)

        by_easy_term = {e.easy_term: e.cell_rank for e in entries}
        self.assertEqual(
            by_easy_term, {"정보 통신 기술": 0, "정보 문화 기술": 1},
            "셀에 나열된 순서대로 cell_rank가 0,1,...로 매겨져야 한다",
        )

    def test_single_candidate_cell_defaults_to_zero(self) -> None:
        from easydict.models import Source

        row = {"원어": "내방", "순화어": "방문"}
        colmap = build_mod.resolve_columns(list(row.keys()))
        source = Source(code="test:cell-rank", name="테스트용 원천")
        entries = build_mod.row_to_entries(row, colmap, source, lineno=3)

        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0].cell_rank, 0, "순화어가 하나뿐인 셀은 cell_rank=0이어야 한다")


# =============================================================================
# 2-1. priority는 §6.8과 무관한 별개 문제(서로 다른 원표제어의 최장일치)를 계속
#      맡는다 — winner_sort_key로 대체하면 안 된다는 회귀 테스트.
#
# 실측(2,142엔트리·32,908변형형 기준): 표면형 24,447개 중 258개가 "서로 다른
# 길이의 표제어"를 후보로 갖는다(예: 명사 '거주'와 그 명사에서 파생된 동사
# '거주하다'가 활용형 '거주하는'에서 충돌, VAN/브이에이엔, TF/태스크포스 등
# 원어-로마자 표기쌍도 같은 구조). `_longest_match_at()`는 이런 경우
# `priority`(표제어 길이 기반)로 "더 구체적인(긴) 원표제어"를 고른다 —
# §2.2/§6.5가 전제하는 최장일치 원칙이다. 이건 "같은 표제어에 순화어가
# 여럿"인 §6.8 문제와 다르다: 여기서는 애초에 표제어 자체가 다르다(각자
# term_norm 안에서는 후보가 1개뿐).
#
# 만약 `_longest_match_at()`가 `priority` 대신 `winner_sort_key`(risk 우선)만
# 보고 승자를 고르면, "위험도가 높다"는 이유만으로 짧고 상관없는 표제어가
# 더 구체적인 긴 표제어를 밀어낼 수 있다 — 최장일치가 깨진다. 아래 테스트는
# 일부러 짧은 표제어에 risk=high를 줘서 이 함정을 재현한다: `ids[0]`(정렬
# 순서만 보는 구현)라면 risk=high인 짧은 표제어가 이기고, `max(priority)`
# (현재 구현)라면 길이가 더 긴(더 구체적인) 표제어가 이긴다.
# =============================================================================
@unittest.skipUnless(_EXPORT_IMPORT_ERROR is None, f"easydict.export import 실패: {_EXPORT_IMPORT_ERROR}")
@unittest.skipUnless(_LOOKUP_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_LOOKUP_IMPORT_ERROR}")
class TestPriorityStillGovernsCrossHeadwordCollisions(unittest.TestCase):
    """서로 다른(길이가 다른) 원표제어가 같은 표면형에서 충돌하면 priority가
    가른다 — risk가 아무리 높아도 짧은 표제어가 긴 표제어를 이기면 안 된다.
    """

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_priority_collision_test_"))
        self.conn = sqlite3.connect(":memory:")
        self.conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
        nikl_id = _insert_source(self.conn, "nikl:admin2018", "테스트 원천")

        # 짧은 표제어('지원', 명사) — risk=high를 일부러 줘서 §6.8 규칙(risk
        # 최우선)만 적용하면 이 엔트리가 이기도록 함정을 판다.
        _insert_entry_with_id(
            self.conn, entry_id=1, term="지원", easy_term="도움",
            replace_strategy="gloss", risk_level="high",
            source_id=nikl_id, checksum="pri0000000000001",
            priority=100 + len("지원") * 10,  # 120
        )
        # 긴 표제어('지원하다', 동사) — risk=none으로 §6.8 규칙상으론 순위가
        # 밀리지만, priority(길이)로는 더 구체적인 원표제어라 이겨야 한다.
        _insert_entry_with_id(
            self.conn, entry_id=2, term="지원하다", easy_term="돕다",
            replace_strategy="substitute", risk_level="none",
            source_id=nikl_id, checksum="pri0000000000002",
            priority=100 + len("지원하다") * 10,  # 140
        )
        # '지원'(명사)의 명사+하다 파생 변형형이 '지원하다'라는 문자열 그대로
        # 등록되어(§3.5, §6.2) 표제어 2('지원하다')의 원형과 표면이 충돌하는
        # 실데이터 패턴(예: '거주'/'거주하다')을 재현한다.
        _insert_variant(self.conn, entry_id=1, surface="지원하다")

        out = export_mod.export_index(self.conn, self.tmpdir / "index.json")
        self.doc = json.loads(out.read_text(encoding="utf-8"))
        self.easy_dict = EasyDict.from_index_json(self.tmpdir / "index.json")

    def tearDown(self) -> None:
        self.conn.close()
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_surface_index_still_carries_both_candidates(self) -> None:
        ids = self.doc["surface_index"]["지원하다"]
        self.assertEqual(set(ids), {1, 2}, "두 후보 모두 리스트에 남아 있어야 한다")

    def test_longer_original_headword_wins_despite_lower_risk(self) -> None:
        matches = self.easy_dict.find_all("지원하다 절차를 안내합니다.")
        self.assertEqual(len(matches), 1)
        self.assertEqual(
            matches[0].term, "지원하다",
            "risk=high인 짧은 표제어('지원')가 아니라, 더 구체적인(긴) "
            f"원표제어 '지원하다'가 이겨야 한다. 실제 매칭: {matches[0].term!r}",
        )
        self.assertEqual(matches[0].easy_term, "돕다")


# =============================================================================
# 2-2. 정확 일치 우선 규칙 (§6.8, `가설`/`거치`/`내방` 실측 결함에서 도출).
#
# 어떤 표면형이 한 엔트리의 표제어(term)와 정확히 일치하면, 그 엔트리가
# 변형형으로만 걸린(다른 엔트리 소유의) 엔트리를 이긴다 — priority(길이)가
# 아니라 소유권이 가른다. 실측: 명사 '가설'(加設, id=21,22)이 자기 표면형
# '가설'에서 동사 '가설하다'(架設--, id=23,24, 변형형에 '가설' 포함)에게
# priority(길이)만으로는 영원히 졌다 — 그 결과 명사 엔트리가 사전에
# 존재하는데도 find_all()로는 절대 안 뽑히는 죽은 데이터였다.
#
# 최장일치와는 다른 층위다: 이 규칙이 적용되는 시점엔 이미 트라이가 같은
# "길이"의 후보들만 추린 뒤라(§6.5), 표제어 길이가 더는 "어느 게 더 긴
# 매치인가"를 말해주지 않는다.
# =============================================================================
@unittest.skipUnless(_EXPORT_IMPORT_ERROR is None, f"easydict.export import 실패: {_EXPORT_IMPORT_ERROR}")
@unittest.skipUnless(_LOOKUP_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_LOOKUP_IMPORT_ERROR}")
class TestExactMatchOwnershipBeatsPriority(unittest.TestCase):
    """'가설'/'가설하다' 패턴을 그대로 재현한다.

    - 짧은 명사 엔트리(id=1, '지원', priority=120)가 자기 표면형 '지원'의
      진짜 주인이다.
    - 긴 동사 엔트리(id=2, '지원하다', priority=140)가 '지원'을 변형형으로도
      등록해 버려서(명사+하다 파생, §3.5) 우연히 충돌한다.
    - '지원해'라는 제3의 표면형은 **둘 다** 변형형으로만 가지고 있다(어느
      쪽의 원형도 아니다) — 이 경우엔 정확 일치 후보가 없으므로 기존대로
      priority가 가른다(폴백 경로 검증).
    """

    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_exact_match_test_"))
        self.conn = sqlite3.connect(":memory:")
        self.conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))
        nikl_id = _insert_source(self.conn, "nikl:admin2018", "테스트 원천")

        _insert_entry_with_id(
            self.conn, entry_id=1, term="지원", easy_term="도움",
            replace_strategy="substitute", risk_level="none",
            source_id=nikl_id, checksum="ext0000000000001",
            priority=100 + len("지원") * 10,  # 120 — 더 짧다
        )
        _insert_entry_with_id(
            self.conn, entry_id=2, term="지원하다", easy_term="돕다",
            replace_strategy="substitute", risk_level="none",
            source_id=nikl_id, checksum="ext0000000000002",
            priority=100 + len("지원하다") * 10,  # 140 — 더 길다(옛 규칙이면 항상 이김)
        )
        # entry 2가 자기 원형(bare stem)을 변형형으로 등록해 entry 1의 표면형과
        # 충돌한다 — '가설하다'가 변형형 '가설'을 갖는 실데이터 패턴 그대로.
        _insert_variant(self.conn, entry_id=2, surface="지원")
        # '지원해'는 어느 쪽의 원형도 아니다(둘 다 활용형으로만 가짐) —
        # 정확 일치 후보가 없는 폴백 경로를 재현한다('내방하여'가 '내방'과
        # '내방하다' 양쪽의 활용형 목록에 다 있는 실데이터 패턴).
        _insert_variant(self.conn, entry_id=1, surface="지원해")
        _insert_variant(self.conn, entry_id=2, surface="지원해")

        out = export_mod.export_index(self.conn, self.tmpdir / "index.json")
        self.doc = json.loads(out.read_text(encoding="utf-8"))
        self.easy_dict = EasyDict.from_index_json(self.tmpdir / "index.json")

    def tearDown(self) -> None:
        self.conn.close()
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_short_entry_wins_its_own_exact_surface_despite_lower_priority(self) -> None:
        # 옛 규칙(priority만)이면 entry 2(140)가 항상 이겨서 명사 entry 1이
        # 죽은 데이터가 됐을 것이다.
        matches = self.easy_dict.find_all("지원을 받으세요.")
        self.assertEqual(len(matches), 1)
        self.assertEqual(
            matches[0].term, "지원",
            f"짧아도 자기 표면형의 진짜 주인이 이겨야 한다. 실제: {matches[0].term!r}",
        )
        self.assertEqual(matches[0].easy_term, "도움")
        self.assertFalse(matches[0].is_inflected, "원형 그대로 매칭됐으므로 굴절형이 아니다")

    def test_no_exact_owner_falls_back_to_priority(self) -> None:
        # '지원해'는 둘 다 변형형으로만 가지므로 정확 일치 후보가 없다 ->
        # 기존 priority 규칙(더 긴 원표제어)으로 폴백해야 한다.
        matches = self.easy_dict.find_all("지원해 드리겠습니다.")
        self.assertEqual(len(matches), 1)
        self.assertEqual(
            matches[0].term, "지원하다",
            f"정확 일치 후보가 없으면 priority(더 긴 원표제어)가 가려야 한다. 실제: {matches[0].term!r}",
        )
        self.assertTrue(matches[0].is_inflected)


# =============================================================================
# 3. 빌드 파이프라인 전체(build.py -> export.py) — 파일 순서가 아니라
#    **파일 내부 행 순서**를 뒤바꿔도 같은 승자가 나오는지 확인한다.
#    §6.8이 명시한 대로, 이게 기존 재현성 테스트(파일 간 순서만 바꿈)로는
#    잡을 수 없는 결함이다.
# =============================================================================
_CSV_HEADER = ["term", "easy_term", "definition", "category", "replace_strategy", "risk_level"]

# 한 표제어('돌봄지원금')에 대해 risk_level/replace_strategy가 다른 두 행.
# _ROW_LOW_RISK(gloss/low)가 §6.8 규칙상 승자여야 한다. _ROW_NONE_RISK가
# 파일에서 먼저 나오면(원래 결함 그대로) 예전 규칙은 이걸 승자로 골랐다.
_ROW_NONE_RISK = ["돌봄지원금", "지원금", "돌봄에 필요한 돈", "복지", "substitute", "none"]
_ROW_LOW_RISK = ["돌봄지원금", "돌봄에 필요한 돈으로 나라가 주는 지원금", "돌봄에 필요한 돈", "복지", "gloss", "low"]


def _write_csv(path: Path, rows: list[list[str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(_CSV_HEADER)
        writer.writerows(rows)


def _run_build(csv_path: Path, workdir: Path) -> tuple[Path, Path]:
    db_path = workdir / "easy_dict.sqlite3"
    export_dir = workdir / "dist"
    argv = [
        "--input", str(csv_path),
        "--source-code", "test:winner-order",
        "--source-name", "승자 결정 회귀 테스트 원천",
        "--default-tag", "welfare",
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


@unittest.skipUnless(_BUILD_IMPORT_ERROR is None, f"easydict.build import 실패: {_BUILD_IMPORT_ERROR}")
@unittest.skipUnless(_EXPORT_IMPORT_ERROR is None, f"easydict.export import 실패: {_EXPORT_IMPORT_ERROR}")
class TestBuildPipelineRowOrderIndependence(unittest.TestCase):
    """같은 CSV 파일 안에서 행 순서만 뒤바꿔도 index.json의 승자가 같은지 확인한다."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="easydict_winner_pipeline_test_")
        self.tmp_root = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _winner_easy_term(self, export_dir: Path) -> str:
        doc = json.loads((export_dir / "easy_dict.index.json").read_text(encoding="utf-8"))
        ids = doc["surface_index"]["돌봄지원금"]
        self.assertEqual(len(ids), 2, "두 후보 모두 색인에 남아 있어야 한다")
        winner_id = ids[0]
        return doc["entries"][str(winner_id)]["e"]

    def test_same_winner_regardless_of_row_order_within_one_file(self) -> None:
        forward_dir = self.tmp_root / "forward"
        reverse_dir = self.tmp_root / "reverse"
        forward_dir.mkdir()
        reverse_dir.mkdir()

        forward_csv = forward_dir / "terms.csv"
        reverse_csv = reverse_dir / "terms.csv"
        _write_csv(forward_csv, [_ROW_NONE_RISK, _ROW_LOW_RISK])  # 옛 승자가 먼저 나오는 순서
        _write_csv(reverse_csv, [_ROW_LOW_RISK, _ROW_NONE_RISK])  # 신 승자가 먼저 나오는 순서

        _, forward_export = _run_build(forward_csv, forward_dir)
        _, reverse_export = _run_build(reverse_csv, reverse_dir)

        forward_winner = self._winner_easy_term(forward_export)
        reverse_winner = self._winner_easy_term(reverse_export)

        self.assertEqual(
            forward_winner, reverse_winner,
            "행 순서만 바꿨는데 승자가 달라졌다 — §6.8 순서 독립성 위반",
        )
        self.assertEqual(
            forward_winner, "돌봄에 필요한 돈으로 나라가 주는 지원금",
            "승자는 risk_level='low'(gloss) 쪽이어야 한다(§6.8 키 ①)",
        )


# =============================================================================
# 4. lookup.py 소비 테스트 — 승자가 실제로 find_all()에 나타나고, 패자는
#    "상충 지침"으로 남는 게 아니라 문서에서 완전히 빠진다는 것까지 확인한다
#    (원래 결함의 실제 증상). from_index_json과 from_sqlite 두 로더가 같은
#    승자를 고르는지도 함께 확인한다(§8 라운드트립 동등성).
# =============================================================================
@unittest.skipUnless(_LOOKUP_IMPORT_ERROR is None, f"easydict.lookup import 실패: {_LOOKUP_IMPORT_ERROR}")
@unittest.skipUnless(_EXPORT_IMPORT_ERROR is None, f"easydict.export import 실패: {_EXPORT_IMPORT_ERROR}")
class TestLookupOnlySurfacesTheWinner(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = Path(tempfile.mkdtemp(prefix="easydict_winner_lookup_test_"))
        self.db_path = self.tmpdir / "easy_dict.sqlite3"
        self.conn = sqlite3.connect(str(self.db_path))
        self.conn.executescript(SCHEMA_SQL_PATH.read_text(encoding="utf-8"))

        nikl_id = _insert_source(self.conn, "nikl:admin2018", "대량 자동")
        # 실측 사례를 그대로 재현: '카시트'가 substitute/risk=none(id가 작음, 옛 승자)과
        # gloss/risk=low(신 승자) 두 후보를 갖는다.
        _insert_entry_with_id(
            self.conn, entry_id=1, term="카시트", easy_term="안전 의자",
            replace_strategy="substitute", risk_level="none",
            source_id=nikl_id, checksum="win0000000000010",
        )
        _insert_entry_with_id(
            self.conn, entry_id=2, term="카시트", easy_term="아이 안전 의자",
            replace_strategy="gloss", risk_level="low",
            source_id=nikl_id, checksum="win0000000000011",
        )
        self.conn.commit()

        self.index_path = export_mod.export_index(self.conn, self.tmpdir / "easy_dict.index.json")

    def tearDown(self) -> None:
        self.conn.close()
        import shutil
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_from_index_json_find_all_returns_only_the_winner(self) -> None:
        d = EasyDict.from_index_json(self.index_path)
        matches = d.find_all("카시트를 장착해 주세요.")
        self.assertEqual(len(matches), 1, "겹치는 후보는 하나로 정리되어야 한다")
        self.assertEqual(matches[0].easy_term, "아이 안전 의자")
        self.assertEqual(matches[0].strategy, "gloss")
        # 패자('안전 의자')가 흔적조차 없는지 확인 — "무음 실종"이 아니라
        # 애초에 승자만 조회 결과로 나온다는 것을 재확인한다.
        self.assertNotEqual(matches[0].easy_term, "안전 의자")

    def test_from_sqlite_agrees_with_from_index_json(self) -> None:
        # from_sqlite()는 정본을 직접 읽는 별도 경로라 winner_sort_key를
        # 다시 적용해야 한다(§6.8) — 두 로더가 어긋나면 §8 라운드트립이 깨진다.
        d_json = EasyDict.from_index_json(self.index_path)
        d_sqlite = EasyDict.from_sqlite(self.db_path)

        text = "카시트를 장착해 주세요."
        json_matches = d_json.find_all(text)
        sqlite_matches = d_sqlite.find_all(text)

        self.assertEqual(len(json_matches), 1)
        self.assertEqual(len(sqlite_matches), 1)
        self.assertEqual(
            json_matches[0].easy_term, sqlite_matches[0].easy_term,
            "from_index_json과 from_sqlite가 서로 다른 승자를 골랐다",
        )
        self.assertEqual(sqlite_matches[0].easy_term, "아이 안전 의자")


if __name__ == "__main__":
    unittest.main()
