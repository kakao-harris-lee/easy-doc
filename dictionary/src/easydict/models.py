"""사전 도메인 모델 (§6.1).

`Source` / `Variant` / `Example` / `Entry` 4개의 데이터클래스와, 그 필드가
가질 수 있는 유효값 상수, 태그 표준값(`TAG_CATALOG`)을 정의한다.

**의존성 없음**: 표준 라이브러리(`dataclasses`, `hashlib`, `typing`)만 사용한다.

**모듈 의존 방향**: 이 모듈은 [[normalize]] 를 최상위에서 import한다
(`Entry.__post_init__`이 `term_norm`을 채우기 위해 `normalize_key`가
필요하다). 반대 방향(normalize -> models)의 최상위 import는 순환
참조가 되므로 만들지 않는다 — normalize.py는 Variant가 필요한
`gen_variants()` 함수 본문에서만 지연 임포트한다. 자세한 설명은
normalize.py 모듈 docstring 참고.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field

from . import normalize

SCHEMA_VERSION = "1.0.0"

# ---------------------------------------------------------------------------
# 필드 유효값 상수 (§3.2, §6.1)
# ---------------------------------------------------------------------------
REPLACE_STRATEGIES: tuple[str, ...] = ("substitute", "gloss", "keep")
RISK_LEVELS: tuple[str, ...] = ("none", "low", "high")
STATUSES: tuple[str, ...] = ("active", "review", "deprecated")
POS_VALUES: tuple[str, ...] = ("noun", "verb", "adjective", "adverb", "determiner", "phrase")
VARIANT_KINDS: tuple[str, ...] = ("conjugation", "spacing", "hanja", "abbrev", "synonym", "typo")

# Entry.readability의 sentinel(§3.2: 유효 범위는 1~3). classify()가 채우기 전의
# "아직 정해지지 않음" 상태를 나타낸다. 0은 유효 범위 밖이라 confidence의
# 기본값(0.8, 명시값 신호는 1.0)과 같은 트릭을 쓴다 — 다만 confidence와 달리
# readability는 유효 범위(1~3) 안에 흔한 기본값(예: 2)을 놓을 수 없으므로
# 범위 밖의 0을 sentinel로 쓴다. schema.sql의 CHECK (readability BETWEEN 1 AND 3)
# 덕분에, classify()를 거치지 않은 Entry를 실수로 그대로 적재하면 이 sentinel이
# 제약에 걸려 빌드가 죽는다 — 조용히 잘못된 값이 나가는 것보다 훨씬 안전하다.
# build.py의 classify()가 이 상수를 import해서 "명시값인가 파생시킬 값인가"를
# 구분하는 데 쓴다.
READABILITY_UNSET = 0

# 태그 표준값 (§3.3): name -> (label, kind)
TAG_CATALOG: dict[str, tuple[str, str]] = {
    "admin": ("행정", "domain"),
    "law": ("법률", "domain"),
    "welfare": ("복지", "domain"),
    "medical": ("보건·의료", "domain"),
    "finance": ("금융·세무", "domain"),
    "form": ("서식·신청", "topic"),
    "hanja": ("한자어", "register"),
    "loanword": ("외래어", "register"),
    "jargon": ("전문용어", "register"),
    "needs_review": ("검수필요", "ops"),
}


@dataclass(slots=True)
class Source:
    """원천 데이터 출처 (§3.1 `sources` 테이블에 대응).

    >>> Source(code='data.go.kr:admin-terms', name='행정용어 순화어 대조표').code
    'data.go.kr:admin-terms'
    """

    code: str
    name: str
    organization: str | None = None
    license: str | None = None
    url: str | None = None
    version: str | None = None
    collected_at: str | None = None
    file_sha256: str | None = None


@dataclass(slots=True)
class Variant:
    """표제어의 활용형/띄어쓰기/한자/약어 변형 (§3.4 `variants` 테이블에 대응).

    >>> Variant(surface='명기하여').kind
    'conjugation'
    """

    surface: str
    kind: str = "conjugation"
    is_auto: bool = True


@dataclass(slots=True)
class Example:
    """before/after 예문 (`examples` 테이블에 대응, RAG few-shot 재료).

    >>> Example(before_text='내방하여 주시기 바랍니다.', after_text='방문해 주세요.').is_golden
    False
    """

    before_text: str
    after_text: str
    note: str | None = None
    is_golden: bool = False


@dataclass(slots=True)
class Entry:
    """표제어 본체 (§3.2 `entries` 테이블에 대응).

    `term_norm`을 비워 두고 생성하면 `__post_init__`에서
    `normalize.normalize_key(term)`으로 자동 채워진다.

    >>> e = Entry(term='내방', easy_term='방문')
    >>> e.term_norm
    '내방'
    >>> len(e.checksum())
    16
    """

    term: str
    easy_term: str
    term_norm: str = ""
    term_hanja: str | None = None
    pos: str | None = None
    definition: str | None = None
    replace_strategy: str = "substitute"  # substitute|gloss|keep
    risk_level: str = "low"  # none|low|high
    caution: str | None = None
    readability: int = READABILITY_UNSET  # 1~3 범위 밖(=0) = 아직 정해지지 않음, classify()가 채운다
    confidence: float = 0.8
    priority: int = 100
    # 원천 CSV 한 셀에 순화어를 여러 개 나열했을 때(예: "정보 통신 기술, 정보
    # 문화 기술") 그 셀 안에서 이 엔트리가 몇 번째로 쓰였는지(0-base). §6.8
    # 정렬 키 ④ — "앞에 쓴 것이 권장어"라는 원천의 저작 의도 신호를 보존한다.
    # 셀 하나에 순화어가 하나뿐이면(대부분의 경우) 0이 그대로 맞는 값이고,
    # cell_rank 개념이 없는 원천(예: 순화어가 CSV 행 자체로 나뉘는 원천)의
    # 엔트리도 전부 0이 된다 — 그러면 §6.8 키 ④에서 자동으로 동률 처리되어
    # 다른 원천을 부당하게 이기거나 지지 않고 키 ⑤(easy_term 사전순)로
    # 넘어간다. `build.py`의 `row_to_entries()`가 `split_multi()` 결과를
    # 순회하며 채운다(§5.2).
    cell_rank: int = 0
    frequency: int = 0
    status: str = "active"  # active|review|deprecated
    tags: list[str] = field(default_factory=list)
    primary_tag: str | None = None
    variants: list[Variant] = field(default_factory=list)
    examples: list[Example] = field(default_factory=list)
    source_code: str | None = None
    source_ref: str | None = None

    def __post_init__(self) -> None:
        if not self.term_norm:
            self.term_norm = normalize.normalize_key(self.term)

    def checksum(self) -> str:
        """중복 판정용 해시: sha256(term_norm|easy_term)의 hexdigest 앞 16자."""
        raw = f"{self.term_norm}|{self.easy_term}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]
