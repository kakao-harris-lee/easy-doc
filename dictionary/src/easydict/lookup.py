"""easy-doc이 실제로 호출하는 조회 표면 (§6.5) — 이 저장소의 최종 산출 API.

`EasyDict`는 표면형(표제어 + 변형형)을 중첩 dict 트라이로 올려 텍스트 위에서
**최장일치 + 조사 경계 검사**로 용어를 찾는다. 외부 의존성은 쓰지 않는다
(`re`, `json`, `sqlite3`, `threading`, `dataclasses`, `pathlib`만 사용).

두 가지 소스에서 로드할 수 있다:

- `EasyDict.from_index_json` : easy-doc이 실제로 배포에 쓰는 산출물(§4.3).
  DB 의존이 없어 Kotlin 이식의 파이썬 참조 구현이기도 하다.
- `EasyDict.from_sqlite`     : 정본(§4.1). 검수 도구용 FTS5 `search()`까지 쓸 수 있다.

로드가 끝나면 두 방식 모두 같은 메모리 트라이(`_entries`/`_trie`)로 수렴하므로
`find_all`/`annotate`/`build_prompt_context`는 소스와 무관하게 동일하게 동작하고,
로드 이후 상태를 바꾸지 않는 읽기 전용 구조라 여러 스레드가 동시에 호출해도
안전하다(§6.5 "스레드 안전하게 읽기 전용으로 동작").
"""
from __future__ import annotations

import json
import re
import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .export import winner_sort_key  # §6.8 승자 결정 정렬 키 — from_sqlite()가 재사용한다.
from .normalize import correct_josa_form, find_josa_after, josa_pattern

# 트라이 terminal 노드를 표시하는 sentinel 키.
# 실제 한글 표면형 문자와 절대 충돌하지 않도록 문자열이 아닌 object()를 쓴다.
_TERMINAL: object = object()

# 왼쪽 경계 검사용 한글 음절 범위 (가~힣). _boundary_ok의 오른쪽 경계와
# 대칭이지만, 왼쪽에는 조사가 올 수 없으므로(조사는 항상 체언 뒤에만
# 붙는다) josa_pattern()의 조사 연쇄 허용은 적용하지 않는다 — 단순히
# "매칭 시작 직전이 한글 음절이 아니어야 한다"만 검사한다.
_HANGUL_SYLLABLE_RE = re.compile(r"[가-힣]")

# 로마자·숫자 경계 검사용(실측 결함: 'CCTV'에서 'CT'가, 'TFT'에서 'TF'가
# 매칭됨). 위 한글 경계 검사와 원칙은 같지만 문자 종류가 다르다 — 표제어가
# 로마자/숫자로 시작·끝나면 그 경계 바로 바깥도 로마자/숫자여서는 안 된다.
# 한글 표제어에는 이 검사를 걸지 않는다('3개월'의 '개월'이 숫자 뒤에서
# 정상 매칭돼야 하므로) — `_left_boundary_ok`/`_boundary_ok`가 "표제어(또는
# 매칭된 표면형) 자신의 경계 문자가 로마자/숫자일 때만" 이 정규식으로
# 판정한다.
_LATIN_DIGIT_RE = re.compile(r"[A-Za-z0-9]")

# 길이 1 한글 표제어 전용 오탐 방지(§6.7 (5), 실측 결함). `_left_boundary_ok`/
# `_boundary_ok`의 일반 어절 경계 검사는 앞뒤가 공백·문장부호이기만 하면
# 통과시키므로, 아래 두 형태는 정상적인 어절 경계인데도 오탐이다 — 그래서
# "길이 1"일 때만 도는 별도 규칙이 필요하다(`3개월`의 `개월`처럼 길이 2
# 이상인 한글 표제어에는 걸지 않는다).
#   ① 수량 단위: `200자 이내`의 `자` — 바로 앞이 ASCII 숫자(전각 숫자는
#      실측 코퍼스에 사례가 없어 다루지 않는다).
#   ② 가나다 목록 기호: 줄머리(문서 시작 또는 개행 직후, 공백/탭만 허용)에
#      단독으로 나오고 바로 뒤가 `.`/`)`인 경우 (`자. 「학교 밖 청소년…`).
# `부정한 방법으로 교부받은 자는`(법률문투 `자`, 앞이 공백)처럼 정당한
# 매칭은 두 조건 다 해당하지 않으므로 통과해야 한다.
_ASCII_DIGIT_RE = re.compile(r"[0-9]")

# 위험도 정렬 가중치. build_prompt_context가 max_terms 초과 시 이 순서로 자른다.
_RISK_ORDER: dict[str, int] = {"high": 2, "low": 1, "none": 0}

# ---------------------------------------------------------------------------
# build_prompt_context()의 gloss_style별 섹션 제목 (실측: easy-doc A/B 56건).
#
# "paren"(기존)은 섹션 제목 자체가 "괄호로 설명하세요"라서, easy-doc 변환
# 프롬프트의 별도 스타일 규칙("괄호는 풀어 쓰라")과 충돌한다. gloss 항목의
# head도 `term(easy_term)` 괄호 템플릿을 그대로 few-shot으로 보여주므로
# LLM이 그 형태를 모방해 스타일 통과율이 83.9% -> 51.8%로 무너지고 보정
# 패스가 56/56 발동했다. "sentence"(신규 기본값)는 괄호 템플릿 자체를
# 보여주지 않고 "다음 문장에서 풀어 쓰라"는 지시로 바꿔 이 충돌을 없앤다.
# ---------------------------------------------------------------------------
_GLOSS_SECTION_TITLE: dict[str, str] = {
    "paren": "### 원래 말을 남기고 괄호로 설명하세요 (지우면 안 됩니다)",
    "sentence": "### 원래 말은 남기고, 바로 다음 문장에서 쉽게 풀어 설명하세요 (원래 말을 지우거나 괄호로 붙이지 마세요)",
}

# ---------------------------------------------------------------------------
# build_prompt_context()의 계층적 상세도 (D-4).
#
# 전략별 기본 상세도: substitute(안전, 설명 불필요)=min, gloss(중간 설명)=mid,
# keep(가장 위험, 최대 설명)=max. 여기에 risk='high'는 전략과 무관하게 항상
# max로 끌어올린다 — 위험한 용어는 gloss든(예: 과태료) substitute든 근거와
# 주의사항이 빠지면 안 된다는 판단이다(§2.1). 반대로 substitute+risk='low'는
# 그대로 min에 둔다 — substitute 자체가 "지워도 안전"이라는 판정이므로,
# risk='high'만큼의 명백한 위험 신호가 아니면 최소 상세를 유지해 토큰을
# 아낀다(요청받지 않은 low/none substitute 세분화는 하지 않기로 판단).
# ---------------------------------------------------------------------------
_BASE_DETAIL_TIER: dict[str, str] = {"substitute": "min", "gloss": "mid", "keep": "max"}


def _detail_tier_for(strategy: str, risk: str) -> str:
    """전략·위험도로 `min`/`mid`/`max` 상세도 등급을 정한다."""
    tier = _BASE_DETAIL_TIER.get(strategy, "mid")
    if risk == "high" and tier != "max":
        return "max"
    return tier


# "이유:" 줄 중복 방지용 정규화. 공백·마침표 차이만 있는 문자열도 같다고
# 본다(한국어기초사전 유래 엔트리는 definition에 문장부호가 붙어 있곤 하다).
_DEDUP_STRIP_RE = re.compile(r"[\s.]+")


def _normalize_for_dedup(s: str) -> str:
    return _DEDUP_STRIP_RE.sub("", s)

# ---------------------------------------------------------------------------
# 조사 이형태 교정 (annotate()의 substitute 경로 전용).
#
# (D-5) 이 로직은 원래 여기 있었지만 `normalize.py`(JOSA/josa_pattern()이
# 이미 있는 곳)의 공개 유틸로 승격했다 — `build.py`가 예문(before/after)을
# 합성할 때 `.replace()`만 써서 같은 조사 호응 버그를 독립적으로 재발시켰기
# 때문이다(§5). `correct_josa_form`/`find_josa_after`는 이제
# `normalize.py`에서 import해서 쓴다. 판정 규칙·오탐 방지책의 상세
# 설명은 그쪽 docstring 참고.
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class Match:
    """텍스트 한 구간에서 발견된 용어 매칭 결과 (§6.5).

    >>> m = Match(start=0, end=2, surface='내방', entry_id=1, term='내방',
    ...           easy_term='방문', strategy='substitute', risk='none',
    ...           definition=None, priority=110)
    >>> m.surface
    '내방'

    `is_inflected`은 §6.5 원 계약에는 없던 확장 필드다. `surface`가 표제어
    원형(`term`)과 다르면(주로 `~하여/~한/~했습니다` 같은 활용형이나
    `차상위 계층`류 띄어쓰기 변형이 매칭됐을 때) True가 된다. `annotate()`가
    활용형을 원형 그대로 잘라내 비문을 만드는 사고(예: '명기하여' -> '쓰다')를
    막기 위한 신호로 쓰인다 — 자세한 이유는 `annotate()` docstring 참고.
    기본값 `False`를 두어 기존 위치 인자 호출부와의 호환을 깨지 않는다.

    `caution`도 마찬가지로 원 계약엔 없던 확장 필드다(D-4). 엔트리의
    `caution`(치환 시 주의사항, entries 테이블 컬럼)을 그대로 옮겨 담는다.
    `build_prompt_context()`가 고위험/`keep` 용어에 이 값을 노출해 LLM이
    오변환하지 않도록 하는 데 쓴다.
    """

    start: int
    end: int
    surface: str
    entry_id: int
    term: str
    easy_term: str
    strategy: str
    risk: str
    definition: str | None
    priority: int
    is_inflected: bool = False
    caution: str | None = None


class EasyDict:
    """조회 전용 한국어 순화어 사전.

    `from_index_json` 또는 `from_sqlite`로만 생성한다(`EasyDict()` 직접
    호출은 빈 사전을 만들 뿐이며 정상적인 사용 경로가 아니다).

    사용 예:
        >>> d = EasyDict.from_index_json("dist/easy_dict.index.json")
        >>> d.find_all("내방을 하실 때 증빙서류를 지참하여 주시기 바랍니다.")
        [Match(start=0, end=2, surface='내방', ...), ...]
    """

    def __init__(self) -> None:
        self._mode: str = "json"
        self._conn: sqlite3.Connection | None = None
        self._entries: dict[int, dict[str, Any]] = {}
        self._trie: dict[Any, Any] = {}
        # josa_pattern()은 "(?=...)"로 감싸지 않은 조각만 반환하므로 여기서 감싼다.
        # zero-width lookahead라 pattern.match(text, pos)로 슬라이싱 없이 경계만 검사할 수 있다.
        self._boundary_re = re.compile("(?=" + josa_pattern() + ")")
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # 로더
    # ------------------------------------------------------------------

    @classmethod
    def from_index_json(cls, path: str | Path) -> "EasyDict":
        """`easy_dict.index.json`(§4.3)에서 사전을 읽는다.

        easy-doc이 실제로 배포에 쓰는 산출물이며, SQLite 의존이 없다.
        `entries` 값의 `t/e/d/s/r/p/g` 축약 키를 그대로 내부 표현으로 쓴다
        (`from_sqlite`도 동일한 키로 맞춰 두 로더의 조회 결과가 같아지게 한다).

        사용 예:
            >>> d = EasyDict.from_index_json("dist/easy_dict.index.json")
            >>> d.build_prompt_context("차상위계층 지원 안내문입니다.")
        """
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)

        obj = cls()
        obj._mode = "json"
        obj._entries = {int(k): dict(v) for k, v in doc.get("entries", {}).items()}
        surface_to_ids = {k: list(v) for k, v in doc.get("surface_index", {}).items()}
        obj._trie = obj._build_trie(surface_to_ids)
        return obj

    @classmethod
    def from_sqlite(cls, path: str | Path) -> "EasyDict":
        """`easy_dict.sqlite3` 정본(§4.1)에서 사전을 읽는다.

        `v_entry_full` 뷰 + `variants` 테이블을 한 번 읽어 메모리 트라이로
        올린 뒤에는 커넥션을 다시 건드리지 않는다(`search()`의 FTS5 질의만
        예외). 그래서 로드가 끝나면 `from_index_json`과 완전히 동일하게
        동작한다 — §8 "라운드트립" 검증이 이 동등성을 확인한다.

        사용 예:
            >>> d = EasyDict.from_sqlite("dist/easy_dict.sqlite3")
            >>> d.find_all("내방을 하실 때")
        """
        conn = sqlite3.connect(str(path), check_same_thread=False)

        obj = cls()
        obj._mode = "sqlite"
        obj._conn = conn

        # examples는 엔트리별로 따로 질의하면 N+1이 되므로, variants처럼
        # 한 번에 다 읽어 entry_id로 묶어 둔다. export.py의 _MAX_EXAMPLES_PER_ENTRY(3)와
        # 같은 캡을 여기서도 적용해 sqlite/json 두 로더의 라운드트립(§8)이
        # 어긋나지 않게 한다 — export.py는 SQL LIMIT으로 캡하지만 여기서는
        # 파이썬 슬라이싱으로 캡한다(둘 다 is_golden DESC, id ASC 정렬 후 상위
        # N개라 결과는 동일).
        _MAX_EXAMPLES_PER_ENTRY = 3
        examples_by_entry: dict[int, list[dict[str, Any]]] = {}
        for entry_id, before, after, is_golden in conn.execute(
            """
            SELECT entry_id, before_text, after_text, is_golden FROM examples
             ORDER BY entry_id, is_golden DESC, id ASC
            """
        ):
            bucket = examples_by_entry.setdefault(entry_id, [])
            if len(bucket) < _MAX_EXAMPLES_PER_ENTRY:
                bucket.append({"b": before, "a": after, "y": bool(is_golden)})

        # source_code/cell_rank까지 같이 읽어야 winner_sort_key()의 원천 신뢰도
        # 키(§6.8 키 ②)와 원천이 제시한 순서 키(§6.8 키 ④)를 계산할 수 있다 —
        # export_index()가 읽는 v_entry_full 컬럼과 동일하게 맞춘다.
        cur = conn.execute(
            """
            SELECT id, term, easy_term, definition, replace_strategy,
                   risk_level, priority, cell_rank, tags, caution, source_code
              FROM v_entry_full
             WHERE status != 'deprecated'
            """
        )
        cols = [d[0] for d in cur.description]
        rows = [dict(zip(cols, raw)) for raw in cur.fetchall()]

        # variants도 examples처럼 한 번에 다 읽어 entry_id로 묶어 둔다(N+1 방지).
        # 아래에서 이 매핑을 "정렬된 rows 순회 중 조회"로만 쓰고, variants 테이블의
        # 원래 행 순서로 surface_to_ids를 채우지는 않는다 — 그러면 §6.8 승자 순서와
        # 무관한 순서가 섞여 들어간다(term 표면형은 정렬 순서를 지키는데 변형형
        # 표면형만 다른 순서를 따르는 결과가 됐을 것이다).
        variants_by_entry: dict[int, list[str]] = {}
        for entry_id, surface in conn.execute("SELECT entry_id, surface FROM variants"):
            variants_by_entry.setdefault(entry_id, []).append(surface)

        surface_to_ids: dict[str, list[int]] = {}

        def _add_surface(surface: str, entry_id: int) -> None:
            ids = surface_to_ids.setdefault(surface, [])
            if entry_id not in ids:
                ids.append(entry_id)

        # §6.8: id 순서가 아니라 winner_sort_key()로 정렬한 순서로 순회해야
        # export_index()(index.json)와 동일한 승자가 나온다 — 이 로더(from_sqlite)는
        # 정본 SQLite를 직접 읽는 어드민/검수 도구 전용 경로라 Kotlin은 건드리지
        # 않지만(§7.1), from_index_json과의 라운드트립 동등성(§8)을 지키려면
        # 여기서도 같은 규칙을 적용해야 한다.
        for row in sorted(rows, key=winner_sort_key):
            entry_id = row["id"]
            tags = row["tags"].split(",") if row["tags"] else []
            obj._entries[entry_id] = {
                "t": row["term"],
                "e": row["easy_term"],
                "d": row["definition"],
                "s": row["replace_strategy"],
                "r": row["risk_level"],
                "p": row["priority"],
                "g": tags,
                "c": row["caution"],
                "x": examples_by_entry.get(entry_id, []),
            }
            _add_surface(row["term"], entry_id)
            for surface in variants_by_entry.get(entry_id, ()):
                _add_surface(surface, entry_id)

        obj._trie = obj._build_trie(surface_to_ids)
        return obj

    # ------------------------------------------------------------------
    # 내부: 트라이 구성 / 매칭
    # ------------------------------------------------------------------

    @staticmethod
    def _build_trie(surface_to_ids: dict[str, list[int]]) -> dict[Any, Any]:
        """표면형 -> entry id 목록 매핑을 문자 단위 중첩 dict 트라이로 올린다."""
        root: dict[Any, Any] = {}
        for surface, ids in surface_to_ids.items():
            if not surface:
                continue
            node = root
            for ch in surface:
                node = node.setdefault(ch, {})
            node.setdefault(_TERMINAL, []).extend(ids)
        return root

    def _boundary_ok(self, text: str, end: int) -> bool:
        """매칭 직후 위치가 어절 경계인지 검사한다 (§6.5, §3.4).

        `_boundary_re`는 zero-width lookahead라 문자열을 자르지 않고
        `pattern.match(text, end)`로 그 위치에서만 조건을 확인할 수 있다.

        ### 로마자·숫자 경계 (실측 결함: `CCTV`의 `CT`, `TFT`의 `TF`)

        `josa_pattern()` 기반의 원래 검사는 "다음이 한글 음절이 아니면
        통과"라서 로마자·숫자끼리 이어 붙는 경우를 전혀 못 걸렀다 —
        `CCTV`에서 `CT`를 매칭하면 다음 글자 `V`가 한글이 아니므로 그냥
        통과해 버렸다(§5.5(3) "경계 검사가 한쪽만 되어 있다"의 로마자
        판본). 매칭된 표면형의 **마지막 글자**가 로마자/숫자면, 바로 다음
        글자도 로마자/숫자여서는 안 된다 — 그 외(한글·공백·문장부호·
        문자열 끝)는 기존 josa 경계 검사로 넘긴다. `TF팀`처럼 로마자
        표제어 뒤에 한글이 붙는 정상 결합은 이 검사에 걸리지 않는다(다음
        글자가 로마자/숫자가 아니므로).
        """
        if end > 0 and end < len(text) and _LATIN_DIGIT_RE.match(text[end - 1]) and _LATIN_DIGIT_RE.match(text[end]):
            return False
        return self._boundary_re.match(text, end) is not None

    def _left_boundary_ok(self, text: str, i: int) -> bool:
        """매칭 시작 위치 `i`의 왼쪽 경계를 검사한다 (버그 수정).

        시작 직전 문자가 한글 음절이면 실패한다 — 단어 중간부터 매칭이
        시작되는 것을 막는다. 문자열 시작이거나 비한글(공백·문장부호·
        숫자·영문·한자)이면 통과한다.

        오른쪽 경계(`_boundary_ok`)와 원칙은 같지만(한글 음절이 그냥
        이어지면 실패), 조사는 체언 **뒤**에만 붙지 **앞**에는 오지
        않으므로 `josa_pattern()`의 조사 연쇄 허용을 적용하지 않는다 —
        왼쪽은 "한글 음절이냐 아니냐"만 보면 된다.

        이 검사가 없으면 `거주자`의 `자`(표제어 `자`가 사전에 있을 때),
        `신청자`/`대상자`/`이용자`의 `자`처럼 **복합어 중간의 한 글자가
        단어 경계 없이 매칭돼 버린다** — 오른쪽 경계(뒤가 공백/조사)만
        통과하면 걸러낼 방법이 없었다. 1글자 표제어(`갭`/`팁`/`폼` 등
        외래어)를 배제하지 않고도 이 검사만으로 충분히 막을 수 있다.

        ### 로마자·숫자 경계 (`_boundary_ok`와 대칭, 실측 결함: `CCTV`의 `CT`)

        `text[i]`(이 위치에서 시작할 매칭의 첫 글자)가 로마자/숫자이면,
        바로 앞 글자도 로마자/숫자여서는 안 된다. 한글로 시작하는 표제어에는
        이 검사를 걸지 않는다 — `3개월`의 `개월`처럼 숫자 뒤에 한글 표제어가
        오는 건 정상이다(`_LATIN_DIGIT_RE` 모듈 상수 주석 참고).
        """
        if i == 0:
            return True
        if _HANGUL_SYLLABLE_RE.match(text[i - 1]) is not None:
            return False
        if _LATIN_DIGIT_RE.match(text[i]) and _LATIN_DIGIT_RE.match(text[i - 1]):
            return False
        return True

    def _single_hangul_headword_ok(self, text: str, i: int, end: int) -> bool:
        """길이 1 한글 표제어 매칭에서만 도는 추가 오탐 방지 (§6.7 (5)).

        `_left_boundary_ok`/`_boundary_ok`는 "매칭 앞뒤가 어절 경계인가"만
        보므로, 다음 두 형태는 그 검사를 통과하고도 실제로는 표제어가 아닌
        다른 것을 가리킨다 — 실측(문서 051 등)에서 확인된 결함이다.

        ① 수량 단위 뒤: `200자 이내`의 `자`(글자 수 단위)가 표제어
           `자`(사람)로 오매칭됐다. 바로 앞 글자가 ASCII 숫자면 거부한다.
        ② 가나다 목록 기호: `자. 「학교 밖 청소년…`처럼 줄머리에 단독으로
           나오고 바로 뒤가 `.`/`)`이면 목록 항목 번호이지 표제어가 아니다.

        `부정한 방법으로 교부받은 자는`(법률문투 `자`, 앞이 공백이고 줄머리도
        아님)처럼 정당한 매칭은 두 조건 다 걸리지 않으므로 계속 허용된다.
        길이 2 이상인 한글 표제어(`3개월`의 `개월`)에는 이 검사를 걸지 않는다
        — 호출부(`_longest_match_at`)가 `length == 1`일 때만 호출한다.
        """
        if i > 0 and _ASCII_DIGIT_RE.match(text[i - 1]):
            return False
        line_start = text.rfind("\n", 0, i) + 1
        if all(c in " \t" for c in text[line_start:i]) and end < len(text) and text[end] in ".)":
            return False
        return True

    def _longest_match_at(self, text: str, i: int) -> Match | None:
        """위치 `i`에서 시작하는 최장일치 매칭 하나를 찾는다.

        먼저 왼쪽 경계(`_left_boundary_ok`)부터 확인한다 — 실패하면 트라이를
        걸을 필요도 없이 이 위치에서는 어떤 길이의 후보도 무효다(단어
        중간에서 매칭을 시작할 수 없으므로).

        왼쪽 경계를 통과했으면 트라이를 끝까지 걸으며 지나친 모든
        terminal(=사전에 있는 표면형)의 길이를 기록해 두고, 가장 긴 것부터
        조사 경계 검사를 통과하는지 확인한다. 가장 긴 후보가 경계 검사에서
        실패해도(예: '내방객'의 '내방') 그보다 짧은 후보가 트라이 경로상에
        있으면 시도한다 — 그래야 최장일치 원칙과 경계 검사를 동시에
        만족시킬 수 있다.

        ### 정확 일치 우선 규칙 (§6.8, 실측 결함에서 도출)

        같은 표면형에 엔트리가 여럿이면(동형이의) 우선 **그 표면형이 자기
        표제어(`term`)와 정확히 일치하는 엔트리**부터 추린다. 그런 엔트리가
        하나 이상 있으면 그 안에서만 `priority`로 승자를 고르고, 하나도
        없으면(전부 변형형으로만 걸렸으면) 기존대로 전체 후보에서
        `priority`로 고른다.

        이 규칙이 필요한 이유: 명사 '가설'(假設, 가설을 세우다)과 그 명사의
        하다-파생 동사 '가설하다'(전선을 가설하다, 전혀 다른 뜻)가 활용형
        '가설'(동사 '가설하다'의 어간 자체가 명사와 같은 문자열)에서 우연히
        충돌하면, 명사 '가설'은 자기 표면형에서 **priority(길이)만으로는
        영원히 못 이긴다** — 표제어 길이가 더 짧기 때문이다(§6.8). 그 결과
        명사 엔트리가 사전에 존재하는데도 `find_all()`로는 절대 뽑히지 않는
        죽은 데이터가 됐고, "가설을 세워 검증하였다"(hypothesis) 같은 명사
        문장이 "가설하다"(설치하다) 동사 뜻으로 오표시됐다. 소유권(누가 이
        표면형의 진짜 주인인가)이 길이보다 우선해야 한다 — 표면형이 정확히
        자기 표제어인 엔트리가 그 표면형의 주인이다.

        길이 기반 최장일치와는 충돌하지 않는다: 최장일치는 이 함수 위쪽에서
        `candidates`(표면형 길이별 후보)로 이미 처리했고, 여기서 다투는
        `ids`는 **전부 같은 길이의 같은 표면형**을 공유하는 엔트리들이다 —
        그 시점엔 표제어 길이(=priority)가 "어느 게 더 긴 매치인가"를 말해
        주지 못한다(길이가 이미 같으므로). 정확 일치 우선이 그 다음 기준이고,
        그래도 갈리지 않으면(예: '가설'의 두 명사 후보 21,22처럼 둘 다 정확
        일치) `priority`(동률이라 `max()`가 리스트 첫 원소 반환)로 넘어간다.

        같은 표제어에 순화어 후보가 여럿이라 `priority`가 항상 동률인
        경우(§6.8이 다루는 문제, 예: '독거노인'의 두 후보)는 `max()`가
        동률에서 리스트의 첫 원소를 반환하는 특성을 이용한다 —
        export.py(`from_index_json`)와 위 `from_sqlite`가 이미
        `winner_sort_key()`(§6.8)로 candidate id 리스트를 정렬해 두므로,
        동률일 때 "리스트에서 먼저 오는 원소"가 곧 §6.8 승자다. 승자
        판정 자체는 적재 시점에 한 번만 계산되고(§6.8 "결정" 절) 여기서는
        `priority` 비교 코드를 그대로 재사용할 뿐이다.

        정확 일치 우선 규칙과 `priority` 값/계산식은 별개다 — `priority`는
        여전히 안 건드린다. 이 규칙은 그 앞에 "소유권" 필터 하나를 끼워
        넣을 뿐이다. index.json에 새 필드도 필요 없다 — 각 엔트리가 이미
        원형 `t`(term)를 담고 있으므로 `surface == entries[eid]["t"]`만
        비교하면 된다.
        """
        if not self._left_boundary_ok(text, i):
            return None

        node = self._trie
        n = len(text)
        candidates: list[tuple[int, list[int]]] = []  # (length, entry_ids), 짧은 것부터
        j = i
        while j < n and text[j] in node:
            node = node[text[j]]
            j += 1
            if _TERMINAL in node:
                candidates.append((j - i, node[_TERMINAL]))

        for length, ids in reversed(candidates):
            end = i + length
            if not self._boundary_ok(text, end):
                continue
            surface = text[i:end]
            if length == 1 and _HANGUL_SYLLABLE_RE.match(surface) and not self._single_hangul_headword_ok(text, i, end):
                continue
            # 정확 일치 우선(위 docstring 참고): 이 표면형이 자기 표제어인
            # 엔트리가 있으면 그 안에서만, 없으면 전체 후보에서 priority로
            # 고른다. priority 값/계산식은 그대로다 — 앞에 소유권 필터만 둔다.
            exact_ids = [eid for eid in ids if self._entries[eid]["t"] == surface]
            candidate_ids = exact_ids or ids
            entry_id = max(candidate_ids, key=lambda eid: self._entries[eid]["p"])
            info = self._entries[entry_id]
            return Match(
                start=i,
                end=end,
                surface=surface,
                entry_id=entry_id,
                term=info["t"],
                easy_term=info["e"],
                strategy=info["s"],
                risk=info["r"],
                definition=info.get("d"),
                priority=info["p"],
                is_inflected=(surface != info["t"]),
                caution=info.get("c"),
            )
        return None

    # ------------------------------------------------------------------
    # 공개 API
    # ------------------------------------------------------------------

    def find_all(self, text: str) -> list[Match]:
        """텍스트 전체에서 사전 용어를 모두 찾는다 (§6.5).

        각 시작 위치에서 최장일치 후보를 모은 뒤, 겹치는 매칭은
        `priority`가 큰 쪽이, 같으면 더 긴 쪽이 이기도록 정리한다(§6.5).
        반환값은 문서 등장 순서(시작 위치 오름차순)로 정렬된다.

        >>> d.find_all("내방객이 많습니다")
        []
        >>> [m.term for m in d.find_all("내방을 하실 때 증빙서류를 지참하여 주시기 바랍니다.")]
        ['내방', '지참']
        >>> [m.term for m in d.find_all("차상위계층 지원")]
        ['차상위계층']
        """
        n = len(text)
        raw: list[Match] = []
        i = 0
        while i < n:
            m = self._longest_match_at(text, i)
            if m is not None:
                raw.append(m)
            i += 1

        # 우선순위 큰 것 -> 긴 것 -> 앞선 것 순으로 정렬해 그리디하게 겹치지 않는 것만 채택.
        raw.sort(key=lambda m: (-m.priority, -(m.end - m.start), m.start))
        accepted: list[Match] = []
        occupied: list[tuple[int, int]] = []
        for m in raw:
            if any(m.start < e and s < m.end for s, e in occupied):
                continue
            accepted.append(m)
            occupied.append((m.start, m.end))

        accepted.sort(key=lambda m: m.start)
        return accepted

    def annotate(self, text: str, strategies: tuple[str, ...] = ("substitute", "gloss")) -> str:
        """텍스트 안의 사전 용어를 치환 전략대로 바꾼 문자열을 만든다 (§6.5, §2.1).

        - `substitute`: **원형(`surface == term`)으로 매칭됐을 때만** 원어를
          지우고 `easy_term`으로 교체한다.
        - `substitute` (활용형 매칭, `surface != term`): 아래 "왜 자동 활용을
          하지 않는가" 참고. `gloss`와 동일하게 `surface(easy_term)`로 폴백한다.
        - `gloss`     : `surface(easy_term)` 형태로 원어(정확히는 매칭된
          표면형)를 보존한다.
        - `keep`      : 원문 그대로 둔다 (기본 `strategies`에는 포함되지 않는다)

        치환하며 인덱스가 밀리는 것을 막기 위해 **뒤에서부터** 치환한다.

        ### 왜 활용형에 자동 활용(어미 재결합)을 하지 않는가

        `substitute` 전략은 표제어 원형 매칭만 안전하다. 문서에는 표제어가
        아니라 활용형(`명기하여`, `명기한`...)으로 나오는데, 이때 원형을
        표제어 자리에 그대로 끼워 넣으면(`명기하여` -> `쓰다`) 활용 어미가
        통째로 사라져 비문이 된다("주소를 쓰다 주십시오"). 한국어 활용은
        불규칙이 많아(`쓰다`+`-어서` -> `써서`처럼 어간이 변하는 경우도 있다)
        `easy_term`에 원래 문장의 어미를 기계적으로 재결합하는 것은 신뢰할 수
        없다 — 잘못된 활용형을 만들어내는 사고가 원문을 그대로 두는 것보다
        더 나쁘다. 그래서 활용형 매칭은 **치환을 시도하지 않고** `gloss`와
        같은 안전한 형태(`surface(easy_term)`, 예: `명기하여(쓰다)`)로
        폴백한다 — 문법이 깨지지 않으면서 쉬운 말도 함께 전달된다.
        이 판단 신호가 `Match.is_inflected`다.

        ### 조사 이형태 교정 (`normalize.JOSA_PAIRS`)

        원형 매칭 `substitute`(`은/는`, `이/가`, `을/를`... 등)는 원어를
        지우고 새 단어를 끼워 넣으므로 받침 유무가 바뀔 수 있다
        (`급여`(받침없음)+`는` -> `지원금`(받침있음)인데 `는`이 그대로 남으면
        "지원금는"이라는 비문이 된다). 그래서 치환 직후에 이어지는 조사를
        `easy_term`의 마지막 글자 받침에 맞춰 다시 고른다 — 판정 규칙과
        오탐 방지책은 `normalize.find_josa_after`/`normalize.correct_josa_form`의
        docstring 참고(D-5로 `normalize.py`에 승격되어 `build.py`의 예문
        합성과 공유한다). `easy_term`이 한글로 끝나지 않으면(숫자·기호)
        교정을 포기하고 원문의 조사를 그대로 둔다. `gloss`/`keep`/굴절형
        폴백은 원어 자체가 문장에 그대로 남아 조사와 이미 맞물려 있으므로
        교정 대상이 아니다.

        >>> d.annotate("과태료를 내지 않으면 이의신청을 할 수 있습니다.", strategies=("gloss",))
        '과태료(정해진 날짜보다 늦어서 더 내는 돈)를 내지 않으면 ...'
        >>> d.annotate("신청서에 이름과 주소를 명기하여 주십시오.")
        '신청서에 이름과 주소를 명기하여(쓰다) 주십시오.'
        >>> d.annotate("급여는 월 30만 원입니다.")
        '지원금은 월 30만 원입니다.'
        """
        matches = [m for m in self.find_all(text) if m.strategy in strategies]
        # find_all()은 이미 시작 위치 오름차순이므로, 각 매칭의 "다음 매칭 시작
        # 위치(없으면 문자열 끝)"를 조사 탐지 상한(limit)으로 미리 계산해 둔다.
        # 이 gap 구간(match.end ~ limit)은 어떤 치환으로도 건드려지지 않으므로
        # 아래에서 뒤에서부터(오른쪽 먼저) 치환하더라도 항상 원문 그대로다.
        limits = [
            matches[i + 1].start if i + 1 < len(matches) else len(text)
            for i in range(len(matches))
        ]

        order = sorted(range(len(matches)), key=lambda i: matches[i].start, reverse=True)
        result = text
        for i in order:
            m = matches[i]
            if m.strategy == "substitute" and not m.is_inflected:
                replacement = m.easy_term
                cut_end = m.end
                found = find_josa_after(text, m.end, limits[i])
                if found is not None and m.easy_term:
                    current_form, pair = found
                    correct_form = correct_josa_form(pair, m.easy_term[-1])
                    if correct_form is not None and correct_form != current_form:
                        replacement += correct_form
                        cut_end = m.end + len(current_form)
                result = result[: m.start] + replacement + result[cut_end:]
                continue
            if m.strategy in ("substitute", "gloss"):
                # substitute + 활용형(is_inflected)은 자동 활용을 하지 않고
                # gloss와 동일하게 원문을 보존한 채 쉬운 말을 괄호로 덧붙인다.
                # 원어 자체가 남으므로 조사 교정은 필요 없다.
                replacement = f"{m.surface}({m.easy_term})"
            else:  # keep: 원문 유지, 자를 것이 없다
                continue
            result = result[: m.start] + replacement + result[m.end :]
        return result

    def _render_term_line(self, m: Match, tier: str, gloss_style: str = "sentence") -> str:
        """한 매칭을 상세도 `tier`(`min`/`mid`/`max`)에 맞춰 한 항목으로 렌더링한다.

        모든 tier가 공유하는 첫 줄(head)은 전략별 지시문 형태를 그대로
        유지한다(`substitute`: 화살표 치환, `gloss`: `gloss_style`에 따른
        병기 형태(아래 참고), `keep`: 원어만). `mid` 이상이면 `definition`이
        있을 때 "이유:" 줄을, `max`면 추가로 `caution`이 있을 때 "주의:" 줄을
        덧붙인다. 값이 없으면 해당 줄 자체를 만들지 않는다(빈 "이유:"/"주의:"
        줄로 토큰을 낭비하지 않는다).

        ### `gloss_style` (실측: easy-doc A/B 56건, §7.2)

        - `"paren"`(기존): `{term} → {term}({easy_term})` — 괄호 병기 템플릿을
          그대로 head에 노출한다.
        - `"sentence"`(신규 기본값): `{term} — 뜻: {easy_term}` — 괄호 삽입을
          유도하지 않는다. `paren`의 괄호 템플릿이 easy-doc의 "괄호는 풀어
          쓰라"는 스타일 규칙과 충돌해 스타일 통과율이 83.9% -> 51.8%로
          무너졌기 때문이다(보정 패스 56/56 발동).

        **`definition`이 `easy_term`과 실질적으로 같으면 "이유:" 줄을
        생략한다** — 한국어기초사전 유래 엔트리처럼 뜻풀이 하나를
        `easy_term`과 `definition`에 그대로 중복 저장한 경우, head에 이미
        `easy_term`이 나와 있는데 바로 아래 "이유:"에 똑같은 문장을 또
        보여주면 토큰 낭비고 LLM에게도 같은 말의 반복이라 혼란만 준다.
        비교는 공백·마침표를 무시하고 정규화한 뒤 한다(`_normalize_for_dedup`)
        — 문장부호 유무 차이만으로 "다른 문장"이라 오판하지 않기 위해서다.
        """
        if m.strategy == "substitute":
            head = f"- {m.term} → {m.easy_term}"
        elif m.strategy == "gloss":
            if gloss_style == "paren":
                head = f"- {m.term} → {m.term}({m.easy_term})"
            else:  # "sentence"
                head = f"- {m.term} — 뜻: {m.easy_term}"
        else:  # keep
            head = f"- {m.term}"

        if tier == "min":
            return head

        extra: list[str] = []
        if m.definition and _normalize_for_dedup(m.definition) != _normalize_for_dedup(m.easy_term):
            extra.append(f"  이유: {m.definition}")
        if tier == "max" and m.caution:
            extra.append(f"  주의: {m.caution}")
        if not extra:
            return head
        return "\n".join([head, *extra])

    def _collect_examples(
        self,
        matches: list[Match],
        limit: int,
        exclude_strategies: frozenset[str] = frozenset(),
    ) -> list[dict[str, Any]]:
        """`matches`가 가리키는 엔트리들의 예문 중 `limit`개를 골라 모은다.

        `is_golden`(사람 검수 완료)을 우선하고, 같은 우선순위 안에서는
        엔트리 `priority`가 높은 쪽을 먼저 채택한다. 엔트리별 예문 자체는
        로더가 이미 `_MAX_EXAMPLES_PER_ENTRY`(3)로 캡을 씌워 두었으므로
        여기서는 그중 상위 `limit`개만 고르면 된다.

        `exclude_strategies`에 담긴 전략의 엔트리는 예문 풀에서 아예 제외한다
        (§7.2.2, gloss_style="sentence" 전용). gloss 전략 엔트리의 예문은
        `원어(easy_term)` 괄호 병기 형식으로 합성돼 있는데(build.py), 이는
        "sentence" 스타일의 "괄호로 붙이지 마세요" 지시와 정반대인 few-shot이
        된다 — 지시문보다 강한 예문이 지시문과 모순되면 예문이 이긴다(실측:
        스타일 통과율 붕괴). 그래서 `build_prompt_context`가 sentence 스타일일
        때 gloss 전략을 넘겨 제외시킨다. substitute 전략의 예문은 형식이
        문제되지 않으므로(§7.2.2 표) 그대로 유지한다.
        """
        if limit <= 0:
            return []
        pool: list[tuple[bool, int, dict[str, Any]]] = []
        seen_entries: set[int] = set()
        for m in matches:
            if m.entry_id in seen_entries:
                continue
            seen_entries.add(m.entry_id)
            if m.strategy in exclude_strategies:
                continue
            for ex in self._entries[m.entry_id].get("x", []):
                pool.append((not ex.get("y", False), -m.priority, ex))
        pool.sort(key=lambda item: (item[0], item[1]))
        return [ex for _, _, ex in pool[:limit]]

    def build_prompt_context(
        self,
        text: str,
        max_terms: int = 30,
        max_chars: int | None = None,
        max_examples: int = 3,
        gloss_style: str = "sentence",
        max_chars_ratio: float | None = None,
        min_substitute: int = 5,
    ) -> str:
        """LLM 프롬프트에 주입할 RAG 컨텍스트 블록을 만든다 (§7.2, D-4).

        **이 함수가 이 프로젝트의 실제 목적이다.** easy-doc은 이 사전으로
        순진한 문자열 치환을 하지 않는다 — 이 블록을 LLM에게 컨텍스트로
        주고 변환 판단 자체는 LLM이 한다(`annotate()`는 그 아래에서
        QA·미리보기용으로 쓰인다). 그래서 단순 치환 지시 목록이 아니라
        **판단에 필요한 근거까지** 실어야 한다.

        전체 사전이 아니라 **이 문서에 실제로 등장한 용어만** 담고,
        `replace_strategy`별로 3개 섹션(바꿔 쓰세요/괄호로 설명/절대 바꾸지
        마세요)으로 나누며, 같은 엔트리가 여러 번 나와도 한 번만 싣는다 —
        여기까지는 기존과 동일하다.

        ### 계층적 상세도 (D-4)

        모든 용어를 동일한 상세도로 실으면 토큰이 낭비된다. 오변환 시
        피해가 큰 용어에 설명을 몰아준다(`_detail_tier_for` 참고):

        - `keep` 또는 `risk='high'` → **최대**: 지시문 + `definition`("이유:")
          + `caution`("주의:")
        - `gloss`(그 외) → **중간**: 지시문 + `definition`(있으면)
        - `substitute`(그 외) → **최소**: 지시문 한 줄

        ### 예문 섹션

        매칭된 엔트리 중 예문(`examples`)을 가진 것에서 `is_golden`(사람
        검수 완료) 우선으로 최대 `max_examples`개를 뽑아 "### 참고 예문"
        섹션에 before/after 쌍으로 싣는다. few-shot이 지시문보다 강력하다
        — 특히 문장 구조를 바꾸는 법은 지시문으로는 전달하기 어렵다.

        ### 예산 제어

        - `max_terms`(기존과 동일): 매칭된 고유 용어 수가 넘으면
          `risk` -> `priority` 내림차순으로 상위 N개만 남긴다.
        - `max_chars`(신규, 기본 `None`=무제한): 렌더링된 문자열 길이
          상한. 넘으면 **①예문 개수부터 줄이고, ②그래도 넘치면
          `risk`/`priority`가 낮은 항목부터 통째로 제거**한다. **항목의
          상세도를 낮추는 방식은 택하지 않았다** — 상세도를 낮추면
          고위험 항목일수록 `caution`처럼 꼭 필요한 정보가 먼저 사라져,
          이 기능을 만든 이유(§2.1 안전장치)를 예산 부족 상황에서 스스로
          무너뜨리기 때문이다. 대신 "화면에 보이는 항목은 항상 자기
          위험도에 맞는 완전한 설명을 갖는다"는 불변식을 지키고, 그 대가로
          항목 수를 줄인다. 예문을 먼저 줄이는 이유는 예문이 보조 자료라
          핵심 지시문(3개 섹션)보다 덜 중요해서다.
        - 무엇이든 잘렸으면(`max_terms` 또는 `max_chars`) 마지막 줄에
          **조용히 자르지 않고** 그 사실을 명시한다 — LLM이 사전이
          완전하다고 착각하는 것을 막기 위해서다.

        ### gloss_style (신규, 실측: easy-doc A/B 56건)

        easy-doc 변환 프롬프트에 이 컨텍스트를 주입해 A/B 측정한 결과,
        `gloss`의 `원문(설명)` 괄호 병기 head가 easy-doc의 별도 스타일 규칙
        ("괄호는 풀어 쓰라")과 충돌해 스타일 통과율이 83.9% -> 51.8%로
        무너지고 보정 패스 56/56이 발동했다 — 프롬프트가 보여주는 형식을
        LLM이 그대로 모방한 것이다. 그래서 두 형식을 다 지원하고 괄호를
        유도하지 않는 쪽을 기본값으로 바꿨다.

        - `"sentence"`(기본값): 섹션 제목이 "다음 문장에서 쉽게 풀어
          설명하세요"이고 head는 `{term} — 뜻: {easy_term}`이다(괄호 템플릿을
          아예 보여주지 않는다). `gloss` 전략 엔트리의 예문은 `원어(easy_term)`
          괄호 병기 형식으로 합성돼 있어(§7.2.2) 이 지시와 모순되는 few-shot이
          되므로 예문 풀에서 제외한다(`substitute` 예문은 유지 — 형식이
          문제되지 않는다).
        - `"paren"`(기존 동작 보존): 섹션 제목·head 모두 괄호 병기 템플릿을
          그대로 노출한다. 위 실측 결함의 원인이었던 형태이지만, 괄호 병기를
          실제로 원하는 호출부를 위해 남겨 둔다.
        - 그 외 값은 `ValueError`.

        ### 예산 제어 (확장)

        - `max_chars_ratio`(신규, 기본 `None`): 지정하면
          `int(len(text) * max_chars_ratio)`도 문자 상한 후보로 계산해
          `max_chars`와 함께 지정됐으면 **둘 중 작은 값**을 쓴다. 실측에서
          문서 38/56이 컨텍스트가 원문보다 긴 상황이었다(최대 4.61배) —
          짧은 문서일수록 고정 `max_chars`만으로는 이 역전을 못 막으므로
          원문 길이에 비례하는 상한이 필요하다. 이후 처리는 기존 `max_chars`
          파이프라인을 그대로 재사용한다(예문 감축 -> 항목 제거 -> 물리적
          하한 시 최선 반환). 기본 `None`이라 기존 호출은 무영향이다.
        - `min_substitute`(신규, 기본 `5`): `substitute`는 대개
          `risk='none'`이라 risk -> priority 내림차순 잘림에서 항상 가장
          먼저 통째로 제거된다(실측: 문서 051에서 매칭된 substitute 4건이
          전부 잘려 "바꿔 쓰세요" 구역이 빈 채로 나갔다). 이를 상쇄하기
          위해 문서에서 매칭된 `substitute` 중 `priority` 상위
          `min(min_substitute, 개수)`건을 "예약석"으로 두고, `max_terms`
          잘림과 `max_chars` 항목 제거 모두에서 예약석이 아닌 항목이 전부
          제거된 뒤에야 예약석을 건드린다. `max_terms` 상한 자체는 여전히
          지킨다 — 예약석을 먼저 채우고 남는 슬롯을 기존 risk -> priority
          순으로 채운다. `0`이면 기존 동작(예약 없음).

        ### 기존 호출과의 호환

        `max_chars`/`max_examples`/`gloss_style`/`max_chars_ratio`/
        `min_substitute`에 기본값을 두어 `build_prompt_context(text)`,
        `build_prompt_context(text, max_terms=N)` 같은 기존 호출은 그대로
        동작한다(문자 수 무제한, 예문 최대 3개, gloss는 sentence 스타일,
        substitute 예약석 5건).

        >>> print(d.build_prompt_context("내방을 하실 때 과태료가 부과됩니다."))
        ## 이 문서에 나온 어려운 말 (반드시 아래 지침대로 처리하세요)
        <BLANKLINE>
        ### 바꿔 쓰세요
        - 내방 → 방문
        <BLANKLINE>
        ### 원래 말은 남기고, 바로 다음 문장에서 쉽게 풀어 설명하세요 (원래 말을 지우거나 괄호로 붙이지 마세요)
        - 과태료 — 뜻: 정해진 날짜보다 늦어서 더 내는 돈
          이유: 정해진 날짜를 넘겨서 더 내게 되는 돈입니다.
          주의: 벌금과는 법적으로 다른 개념입니다. 바꾸지 말고 그대로 쓰세요.
        <BLANKLINE>
        ### 절대 바꾸지 마세요
        <BLANKLINE>
        """
        if gloss_style not in ("paren", "sentence"):
            raise ValueError(f"gloss_style must be 'paren' or 'sentence', got {gloss_style!r}")

        # max_chars_ratio는 max_chars와 같은 파이프라인을 타는 "또 하나의
        # 후보 상한"일 뿐이다 — 실제 상한은 지정된 것들 중 가장 작은 값.
        effective_max_chars = max_chars
        if max_chars_ratio is not None:
            ratio_chars = int(len(text) * max_chars_ratio)
            effective_max_chars = (
                ratio_chars if effective_max_chars is None else min(effective_max_chars, ratio_chars)
            )

        matches = self.find_all(text)

        seen: dict[int, Match] = {}
        for m in matches:
            seen.setdefault(m.entry_id, m)  # 최초 등장분만 유지 (문서 등장 순서)
        unique = list(seen.values())
        total_found = len(unique)  # max_terms 자르기 전, 문서에 실제로 등장한 고유 용어 수

        # substitute 예약석(min_substitute 확장): 문서에서 매칭된 substitute
        # 중 priority 상위 min(min_substitute, 개수)건의 entry_id 집합. 아래
        # max_terms 잘림과 max_chars 항목 제거 둘 다 이 집합을 마지막까지
        # 보호한다 — substitute는 risk='none'이 대부분이라 보호가 없으면
        # 항상 가장 먼저 통째로 잘려나간다(위 docstring 실측 참고).
        reserved_ids: frozenset[int] = frozenset()
        if min_substitute > 0:
            substitute_unique = [m for m in unique if m.strategy == "substitute"]
            if substitute_unique:
                top = sorted(substitute_unique, key=lambda m: m.priority, reverse=True)[:min_substitute]
                reserved_ids = frozenset(m.entry_id for m in top)

        def _importance_key(m: Match) -> tuple[int, int]:
            return (_RISK_ORDER.get(m.risk, 0), m.priority)

        term_truncated = False
        if len(unique) > max_terms:
            # 예약석부터 채우고, 남는 슬롯을 기존 risk -> priority 순으로 채운다.
            reserved_now = sorted(
                (m for m in unique if m.entry_id in reserved_ids), key=_importance_key, reverse=True
            )
            non_reserved_now = sorted(
                (m for m in unique if m.entry_id not in reserved_ids), key=_importance_key, reverse=True
            )
            if len(reserved_now) >= max_terms:
                unique = reserved_now[:max_terms]
            else:
                unique = reserved_now + non_reserved_now[: max_terms - len(reserved_now)]
            term_truncated = True

        # risk desc -> priority desc로 고정된 "중요도 순" 리스트지만, 예약석
        # 항목을 앞쪽에 몰아 둔다(reserved_ranked + non_reserved_ranked).
        # max_chars 예산 초과 시 아래 제거 루프가 이 리스트의 **끝**부터
        # 잘라내므로, 이렇게 구성해 두면 non-reserved가 먼저 다 소진된
        # 뒤에야 예약석에 닿는다.
        reserved_ranked = sorted(
            (m for m in unique if m.entry_id in reserved_ids), key=_importance_key, reverse=True
        )
        non_reserved_ranked = sorted(
            (m for m in unique if m.entry_id not in reserved_ids), key=_importance_key, reverse=True
        )
        ranked = reserved_ranked + non_reserved_ranked

        def render(selected: list[Match], examples_limit: int, show_notice: bool) -> str:
            ordered = sorted(selected, key=lambda m: m.start)
            substitute = [m for m in ordered if m.strategy == "substitute"]
            gloss = [m for m in ordered if m.strategy == "gloss"]
            keep = [m for m in ordered if m.strategy == "keep"]

            lines: list[str] = ["## 이 문서에 나온 어려운 말 (반드시 아래 지침대로 처리하세요)", ""]

            lines.append("### 바꿔 쓰세요")
            for m in substitute:
                lines.append(self._render_term_line(m, _detail_tier_for(m.strategy, m.risk), gloss_style))
            lines.append("")

            lines.append(_GLOSS_SECTION_TITLE[gloss_style])
            for m in gloss:
                lines.append(self._render_term_line(m, _detail_tier_for(m.strategy, m.risk), gloss_style))
            lines.append("")

            lines.append("### 절대 바꾸지 마세요")
            for m in keep:
                lines.append(self._render_term_line(m, _detail_tier_for(m.strategy, m.risk), gloss_style))
            lines.append("")

            # sentence 스타일에서는 gloss 예문(원어(easy_term) 괄호 병기 형식)이
            # "괄호로 붙이지 마세요" 지시와 모순되는 few-shot이라 예문 풀에서 뺀다.
            exclude_strategies = frozenset({"gloss"}) if gloss_style == "sentence" else frozenset()
            examples = self._collect_examples(selected, examples_limit, exclude_strategies=exclude_strategies)
            if examples:
                lines.append("### 참고 예문")
                for ex in examples:
                    lines.append(f"- 전: {ex['b']}")
                    lines.append(f"  후: {ex['a']}")
                lines.append("")

            if show_notice:
                lines.append(
                    f"(용어 {total_found}개 중 {len(selected)}개만 표시했습니다. "
                    "위험도·우선순위가 높은 항목을 우선했으며, 일부가 생략되었습니다.)"
                )

            return "\n".join(lines).rstrip("\n") + "\n"

        if effective_max_chars is None:
            return render(ranked, max_examples, term_truncated)

        selected = list(ranked)
        examples_limit = max_examples
        out = render(selected, examples_limit, term_truncated)
        if len(out) <= effective_max_chars:
            return out

        # 여기부터는 effective_max_chars 때문에 반드시 뭔가 잘려야 하므로 notice를 항상 켠다.
        # ① 예문 개수부터 줄인다(보조 자료라 핵심 지시문보다 덜 중요).
        while examples_limit > 0:
            examples_limit -= 1
            out = render(selected, examples_limit, True)
            if len(out) <= effective_max_chars:
                return out

        # ② 그래도 넘치면 중요도가 낮은 항목부터(ranked의 끝, 예약석은 보호) 통째로 제거한다.
        while len(selected) > 0:
            selected = selected[:-1]
            out = render(selected, 0, True)
            if len(out) <= effective_max_chars:
                return out

        return out  # 항목을 다 비워도 못 맞추면(헤더만으로도 초과) 그게 최선이다

    def search(self, query: str, limit: int = 10) -> list[dict[str, Any]]:
        """사전을 자유 검색한다 (§6.5).

        `from_sqlite`로 로드했으면 `entries_fts`(FTS5)를 쓰고, 그렇지 않으면
        (`from_index_json`) 표제어/순화어 부분일치 스캔으로 대체한다. FTS5
        질의 문법 오류(특수문자 포함 등)는 부분일치로 안전하게 폴백한다.

        >>> d.search("과태료", limit=5)
        [{'id': 3, 'term': '과태료', 'easy_term': '...', ...}]
        """
        if self._mode == "sqlite" and self._conn is not None:
            with self._lock:
                try:
                    cur = self._conn.execute(
                        """
                        SELECT e.id, e.term, e.easy_term, e.definition,
                               e.replace_strategy, e.risk_level, e.priority
                          FROM entries_fts f
                          JOIN entries e ON e.id = f.rowid
                         WHERE entries_fts MATCH ?
                           AND e.status != 'deprecated'
                         ORDER BY rank
                         LIMIT ?
                        """,
                        (query, limit),
                    )
                    cols = [d[0] for d in cur.description]
                    return [dict(zip(cols, row)) for row in cur.fetchall()]
                except sqlite3.OperationalError:
                    pass  # FTS5 질의 문법 오류 -> 아래 부분일치 폴백으로 이어짐

                cur = self._conn.execute(
                    """
                    SELECT id, term, easy_term, definition, replace_strategy,
                           risk_level, priority
                      FROM entries
                     WHERE status != 'deprecated'
                       AND (term LIKE ? OR easy_term LIKE ?)
                     ORDER BY priority DESC
                     LIMIT ?
                    """,
                    (f"%{query}%", f"%{query}%", limit),
                )
                cols = [d[0] for d in cur.description]
                return [dict(zip(cols, row)) for row in cur.fetchall()]

        results = []
        for entry_id, info in self._entries.items():
            if query in info["t"] or query in info["e"]:
                results.append(
                    {
                        "id": entry_id,
                        "term": info["t"],
                        "easy_term": info["e"],
                        "definition": info.get("d"),
                        "replace_strategy": info["s"],
                        "risk_level": info["r"],
                        "priority": info["p"],
                    }
                )
        results.sort(key=lambda r: r["priority"], reverse=True)
        return results[:limit]


if __name__ == "__main__":
    import tempfile

    # DB/색인 파일 없이도 동작을 확인할 수 있도록 손으로 작은 index.json을 만들어 데모한다.
    _demo_doc = {
        "schema_version": "1.0.0",
        "josa": [],
        "surface_index": {
            "내방": [1],
            "내방을": [1],
            "지참": [2],
            "지참하여": [2],
            "과태료": [3],
            "차상위계층": [4],
            "차상위": [5],
            "「국민기초생활 보장법」": [6],
        },
        "entries": {
            "1": {"t": "내방", "e": "방문", "d": "찾아옴", "s": "substitute", "r": "none", "p": 120, "g": [], "c": None, "x": []},
            "2": {"t": "지참", "e": "가져오기", "d": None, "s": "substitute", "r": "none", "p": 120, "g": [], "c": None, "x": []},
            "3": {
                "t": "과태료", "e": "정해진 날짜보다 늦어서 더 내는 돈",
                "d": "정해진 날짜를 넘겨서 더 내게 되는 돈입니다.",
                "s": "gloss", "r": "high", "p": 130, "g": ["law"],
                "c": "벌금과는 법적으로 다른 개념입니다. 바꾸지 말고 그대로 쓰세요.",
                "x": [{"b": "과태료를 납부하세요.", "a": "과태료(정해진 날짜보다 늦어서 더 내는 돈)를 내세요.", "y": True}],
            },
            "4": {
                "t": "차상위계층", "e": "기초생활수급자 바로 위의 저소득층", "d": None,
                "s": "gloss", "r": "high", "p": 150, "g": ["welfare"],
                "c": "제도 명칭이라 신청 창구에서 다시 써야 합니다. 원어를 지우지 마세요.",
                "x": [],
            },
            "5": {"t": "차상위", "e": "형편이 조금 나은", "d": None, "s": "gloss", "r": "low", "p": 130, "g": ["welfare"], "c": None, "x": []},
            "6": {
                "t": "「국민기초생활 보장법」", "e": "「국민기초생활 보장법」", "d": "법 이름입니다.",
                "s": "keep", "r": "high", "p": 200, "g": ["law"],
                "c": "법령명은 절대 바꾸지 않습니다.", "x": [],
            },
        },
    }

    with tempfile.NamedTemporaryFile("w", suffix=".index.json", delete=False, encoding="utf-8") as f:
        json.dump(_demo_doc, f, ensure_ascii=False)
        _demo_path = f.name

    d = EasyDict.from_index_json(_demo_path)

    sample = (
        "내방을 하실 때 증빙서류를 지참하여 주시기 바랍니다. "
        "내방객이 많으니 양해 바랍니다. "
        "과태료를 내지 않으면 「국민기초생활 보장법」에 따라 처리됩니다. "
        "차상위계층 지원 안내입니다."
    )

    print("=== find_all ===")
    for match in d.find_all(sample):
        print(f"  [{match.start}:{match.end}] {match.surface!r} -> {match.term} ({match.strategy}, p={match.priority})")

    print("\n=== annotate (substitute+gloss) ===")
    print(d.annotate(sample))

    print("\n=== build_prompt_context ===")
    print(d.build_prompt_context(sample))

    print("=== build_prompt_context (max_chars=200으로 예산 제한) ===")
    print(d.build_prompt_context(sample, max_chars=200))
