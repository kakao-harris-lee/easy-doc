"""쉬운 말 사전 빌드 파이프라인 (DESIGN.md §5, §6.3).

data/raw/*.csv 를 읽어 정규화·분류·증강한 뒤 SQLite에 적재하고,
(가능하면) JSON 3종을 익스포트한다. 표준 라이브러리만 사용한다 (DESIGN.md §5.3).
"""

from __future__ import annotations

import argparse
import csv
import datetime
import hashlib
import re
import sqlite3
import sys
from pathlib import Path

from easydict.models import (
    Entry,
    Example,
    Source,
    Variant,
    READABILITY_UNSET,
    REPLACE_STRATEGIES,
    RISK_LEVELS,
    STATUSES,
    TAG_CATALOG,
    SCHEMA_VERSION,
)
from easydict.normalize import (
    clean,
    correct_josa_form,
    find_josa_after,
    gen_variants,
    guess_pos,
    josa_pattern,
    nfc,
    normalize_key,
    split_hanja,
    split_multi,
)

# ----------------------------------------------------------------------------
# 컬럼 별칭 (DESIGN.md §5.1) — 기관마다 헤더가 다르므로 하드코딩하지 않고 표로 해석한다.
# ----------------------------------------------------------------------------
COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    "term": ("순화대상어", "순화 대상어", "원어", "대상어", "어려운말", "어려운 말",
             "용어", "행정용어", "기존용어", "term", "source_term"),
    "easy_term": ("순화어", "다듬은말", "다듬은 말", "쉬운말", "쉬운 말",
                  "대체어", "순화표현", "권장어", "easy_term", "refined_term"),
    "definition": ("뜻", "의미", "설명", "뜻풀이", "정의", "definition", "meaning_examples"),
    "category": ("분야", "구분", "영역", "category"),
    "example": ("예문", "용례", "사용례", "example"),
    "hanja": ("한자", "한자표기"),
    "note": ("비고", "참고", "메모", "주의", "주의사항", "notes"),
    # 국립국어원 다듬은말(18,340건) 전용: 어원/어종을 별도 컬럼(original_term)
    # 으로 정확히 준다. "원어"는 이미 term 별칭에 있어서(다른 CSV에서 원어 =
    # 표제어) 여기 넣으면 그 파일들과 충돌한다 — 그래서 이 실데이터의 실제
    # 헤더 문자열(original_term)만 정확히 넣는다. 앞서 보고했던 "다듬을 말"
    # 파일의 원어(언어종류) 컬럼도 이 논리 필드를 재사용할 수 있을 것으로
    # 보이나, 그 파일 실물이 아직 없어 그쪽 헤더 별칭은 추가하지 않는다.
    "origin_language": ("original_term",),
    # 사람이 이미 판단해 CSV에 직접 써넣은 분류값(요청 3). 있으면 classify()의
    # 휴리스틱보다 우선한다 — §5.2 휴리스틱은 사람 판단이 없을 때의 대체재다.
    "replace_strategy": ("치환전략", "치환 전략", "전략", "replace_strategy"),
    "risk_level": ("위험도", "위험 등급", "위험등급", "리스크", "risk_level"),
    "status": ("상태", "검수상태", "검수 상태", "status"),
    # 한국어기초사전 API의 word_grade(초급/중급/고급)가 DESIGN.md §3.2의
    # readability(1~3)에 직접 대응한다 — 이 사전을 고른 핵심 이유다.
    "readability": ("readability", "난이도", "등급", "word_grade"),
}

# readability 명시값의 한국어 등급명 -> 숫자. 초급이 가장 쉬움(1)이라
# DESIGN.md §3.2("1=가장 쉬움")와 그대로 대응한다.
READABILITY_GRADE_NAMES: dict[str, int] = {"초급": 1, "중급": 2, "고급": 3}

# CSV의 분야/구분/영역 원문 값 -> §3.3 표준 태그명.
CATEGORY_TAG_ALIASES: dict[str, str] = {
    "행정": "admin", "행정용어": "admin",
    "법률": "law", "법령": "law",
    "복지": "welfare", "복지용어": "welfare",
    "보건": "medical", "의료": "medical", "보건복지": "medical", "보건·의료": "medical",
    "금융": "finance", "세무": "finance", "금융·세무": "finance",
    "서식": "form", "신청": "form", "서식·신청": "form",
    "외래어": "loanword", "외국어": "loanword",
}

# 작성자가 `caution`(주의) 컬럼에 이 표시를 남기면 그 뜻풀이는 법조문 원문
# 대조 없이는 신뢰할 수 없다는 뜻이다(사용자 지시: "법조문 대조까지 필요한
# 부분이면 비활용"). classify()가 이 표시를 보면 CSV의 명시 status를
# 포함해 다른 무엇보다 우선해 status='deprecated'로 강제한다.
NEEDS_CONFIRMATION_MARKER = "[확인 필요]"

# ----------------------------------------------------------------------------
# 위험도·치환전략 화이트리스트 (DESIGN.md §5.2)
# ----------------------------------------------------------------------------
KEEP_LIST: frozenset[str] = frozenset({
    "국민기초생활 보장법", "국민기초생활보장법",
    "장애인복지법", "사회보장기본법", "행정절차법", "개인정보 보호법",
    # 「」 없이도 창구에서 그대로 다시 말해야 하는 제도 고유명칭
    "국민기초생활보장제도", "장애인연금",
})

LEGAL_RISK_LIST: frozenset[str] = frozenset({
    "과태료", "벌금", "소명", "이의신청", "처분", "기각", "각하", "반려",
    "압류", "체납", "고지", "최고", "시효", "권리", "의무", "상속",
    "채권", "채무", "근저당", "위임", "대리", "추징", "환수",
    # 추가: 위 목록과 같은 급의 법적 절차/제재 용어
    "가압류", "가처분", "공탁", "고소", "고발", "항고", "항소", "상고",
    "과징금", "이행강제금", "청구", "소멸시효", "제척기간",
    # 실데이터(국립국어원 2018 행정용어 1,075행) 감사에서 발견: 위 위험어의
    # 활용형·복합어로, term 완전일치만 보는 이 목록에 원형만 있으면 놓친다.
    # 예: '가처분'은 있지만 '가처분하다'(용언 활용형)는 별개 문자열이라
    # 자동으로 안 걸린다. 부분일치로 바꾸는 대안도 검토했으나 '개각하다'가
    # '각하'를 부분 포함해 오탐되는 등 폭이 넓어 위험해서, 감사로 실제
    # 확인된 항목만 표제어로 추가하는 쪽을 택했다.
    "가처분하다", "복대리인", "고지 의무",
})

# KEEP_LIST에 등록된 제도 고유명칭 중 「」로 감싸이지 않아 법령명 접미사
# (법/법률/법령)로도 판별할 수 없는 것들 -> 실제로 속한 도메인 태그를 직접 지정한다.
# 둘 다 기초생활보장·장애인연금은 복지 급여 제도이므로 welfare.
KEEP_TAG_OVERRIDES: dict[str, str] = {
    "국민기초생활보장제도": "welfare",
    "장애인연금": "welfare",
}

# DESIGN.md §5.5.1: 골든 코퍼스 57건으로 1음절 표제어 15건을 전수 측정한 결과,
# "1음절 한자어가 위험하고 외래어는 덜하다"는 가설은 틀렸다 — 같은 한자어인데
# `자`는 92%(53건 중), `한`은 0%(11건 중)가 의도한 뜻이었다. 실제 갈림길은
# 어종이 아니라 "그 표면형이 초고빈도 고유어 문법 요소(관형사·활용어미)와
# 형태가 겹치는가"다. 그래서 1음절 표제어는 아래 classify()에서 기본값이
# gloss+검수이고, 사람이 코퍼스로 직접 확인해 이 목록에 올린 것만 substitute를
# 유지한다 — 목록에 없는 1음절 표제어가 새로 들어와도 자동으로 안전한 쪽
# (gloss)으로 떨어진다.
PROMOTED_SHORT_SUBSTITUTE: frozenset[str] = frozenset({
    "자",  # 53건 중 92%가 "~하는/~한 자"(person) 패턴 — 의도한 뜻과 일치
    "갭", "붐", "존", "팁", "폼",  # 외래어 5종 — 코퍼스 0건(검증 데이터
    # 자체가 없다는 뜻이지 "안전이 증명됐다"는 뜻은 아니다), 다만 고유어
    # 문법 요소와 형태가 겹치지 않아 `한`류 충돌 가능성이 낮다.
})

# DESIGN.md §5.5.1: 2음절 이상은 위 1음절 규칙으로 못 잡는다. `수리`가 그
# 사례다 — 엔트리는 受理(접수·수리) 기준으로 옳지만, 복지 문서에는 修理
# (고침)가 더 흔하게 나온다(§5.7이 「다듬은말」 원천에서 지적한 것과 같은
# 동형이의어 오염이 우리 사전 안에도 이미 있었다). 지금은 코퍼스 대조로
# 개별 적발하는 수밖에 없다 — 자동화는 후속 과제(§5.5.1 끝).
#
# 근거의 성격이 항목마다 다르다 — 섞어서 적지 않는다:
#   - `수리`는 골든 코퍼스 실측 숫자가 있다(위 주석).
#   - `가설`·`거치`는 다른 레인이 정확 일치 규칙(lookup.py)을 구현하며
#     드러난 사례로, **코퍼스 대조를 하지 않은 문서 통념 기반 판단**이다
#     (팀장 지시, 2026-08-29). §5.5.1 끝의 "사전에 있는데 다른 뜻으로
#     쓰이는 말" 대조 도구가 생기면 그때 숫자로 재확인해야 한다.
HOMOGRAPH_COLLISION_TERMS: frozenset[str] = frozenset({
    "수리",  # 受理(접수) vs 修理(고침) — 修理가 복지 문서에 더 흔하다(코퍼스 실측)
    "가설",  # 사전 등재는 加設(추가 설치)이지만, 행정 문서의 '가설'은 대개
    # 假設(임시 설치: '가설 건축물'·'가설 울타리') 또는 假設(가정, hypothesis)
    # 이다 — '가설 건축물'이 '덧설치 건축물'로 오역될 위험(문서 통념 판단,
    # 코퍼스 대조 안 함)
    "거치",  # 사전 등재는 据置(예치)이지만, 복지·금융 안내문의 '거치 기간'은
    # 원금 상환을 미루는 유예 기간이지 돈을 맡겨 두는 예치가 아니다 —
    # '예치'뿐 아니라 같은 표면형의 '맡김' 후보도 같은 위험을 공유해 함께
    # 내린다(문서 통념 판단, 코퍼스 대조 안 함)
})

# DESIGN.md §5.5.1: `한(限)` -> `까지`는 위 gloss+검수로도 부족하다 — 골든
# 코퍼스 11건 전부 의도한 뜻이 아니었다(전부 관형사 '한(하나의)' 또는 용언
# 활용형 '-한'). 의도한 용법이 코퍼스에 단 한 번도 없으므로 gloss로 원어
# 옆에 괄호를 붙이는 것조차 매 문장 오탐을 시범 보이는 셈이라 비활용
# (status='deprecated')으로 내린다. NEEDS_CONFIRMATION_MARKER와 같은 원칙
# (classify() 맨 끝)으로 status만 덮어쓰고 replace_strategy/risk_level은
# 그대로 남겨 감사 흔적을 보존한다.
DEPRECATED_HOMOGRAPH_TERMS: frozenset[str] = frozenset({
    "한",  # 限(까지/기한) — 코퍼스 11건 전부 관형사 '한' 또는 활용형 '-한'
    "개시",  # 開示(펴 보임/드러내 보임) — 골든 코퍼스 4건 전부 開始(시작)였다
    # ("지원을 개시합니다", "후견을 개시한다"). 게다가 krdict 동형어 목록에
    # 開示 자체가 없다(開市/開始만 있음) — 우리 한자가 일반 국어사전에
    # 없을 만큼 희귀하다는 뜻이라, '한'과 같은 근거로 비활용한다.
    # 근거: tools/detect_homonym_risk.py 검수표 + 코퍼스 문맥 전수 확인.
})

# 대표 태그(is_primary) 결정 순위 — 적재 순서(--input 순서)와 무관하게, 한 엔트리에
# 최종적으로 붙은 태그 "집합"만 보고 대표를 고르기 위한 고정 서열이다.
# 값이 앞설수록 우선순위가 높다. 도메인 태그(law~form)가 register/ops 태그보다
# 항상 우선한다 — needs_review/hanja/loanword/jargon은 "이 용어가 무엇인지"가
# 아니라 "어떻게 처리할지/어떤 표기인지"에 대한 메타 정보라 대표 태그로 부적합하다.
# law가 최우선인 이유는 §2.1의 안전장치와 같다 — 법률 개념은 다른 도메인으로
# 잘못 뭉뚱그려지면 사고로 이어진다(예: 과태료).
TAG_PRIORITY: tuple[str, ...] = (
    "law", "welfare", "medical", "finance", "admin", "form",
    "jargon", "loanword", "hanja", "needs_review",
)

# --default-tag 적용 여부를 판단할 때 "이미 태그가 있다"고 치지 않을 태그들.
# register 태그(hanja/loanword)만 붙어 있는 상태는 도메인 미상과 같다.
_REGISTER_ONLY_TAGS: frozenset[str] = frozenset({"hanja", "loanword", "jargon", "needs_review"})


def _pick_primary_tag(tags: set[str]) -> str | None:
    """태그 집합에서 대표 태그 하나를 고정 서열로 고른다 (순서 무관, 재현 가능)."""
    for tag in TAG_PRIORITY:
        if tag in tags:
            return tag
    # TAG_PRIORITY에 없는 이름 모를 태그만 있는 예외적 상황 대비 최후 수단.
    # 알파벳 순으로 정해서라도 적재 순서와 무관하게 만든다.
    return min(tags) if tags else None

_LEGAL_BRACKET_RE = re.compile(r"[「」]")
# 금액(원/만원/억원/천원, "30만 원"처럼 단위와 원 사이에 띄어쓰기가 있는 관공서 표기 포함)
# 또는 날짜/기한(년/월/일, ~일 이내 등) 표현
_AMOUNT_DATE_RE = re.compile(
    r"\d[\d,]*\s*(만|억|천)?\s*원"
    r"|\d{4}\s*년\s*\d{1,2}\s*월\s*\d{1,2}\s*일"
    r"|\d+\s*(일|개월|년)\s*(이내|이상|이하|간)"
)
_HANJA_CHAR_RE = re.compile(r"[一-鿿]")


def _norm_header(h: str) -> str:
    return h.strip().lower().replace(" ", "")


def detect_encoding(path: Path) -> str:
    """utf-8-sig -> utf-8 -> cp949 -> euc-kr 순으로 실제 디코드를 시도한다 (DESIGN.md §5)."""
    data = path.read_bytes()
    tried: list[str] = []
    for enc in ("utf-8-sig", "utf-8", "cp949", "euc-kr"):
        tried.append(enc)
        try:
            data.decode(enc)
            return enc
        except UnicodeDecodeError:
            continue
    raise ValueError(f"인코딩 판별 실패: {path} (시도한 인코딩: {', '.join(tried)})")


def resolve_columns(header: list[str]) -> dict[str, str]:
    """헤더 목록을 논리 필드 -> 실제 헤더로 매핑한다.

    term/easy_term 둘 중 하나라도 못 찾으면 발견된 헤더 전체를 담아 ValueError.
    """
    normalized = {_norm_header(h): h for h in header}
    colmap: dict[str, str] = {}
    for field, aliases in COLUMN_ALIASES.items():
        for alias in aliases:
            key = _norm_header(alias)
            if key in normalized:
                colmap[field] = normalized[key]
                break
    if "term" not in colmap or "easy_term" not in colmap:
        raise ValueError(
            "필수 컬럼(term/easy_term)을 헤더에서 찾을 수 없습니다. "
            f"발견된 헤더 목록: {header!r}"
        )
    return colmap


def read_csv_rows(path: Path) -> tuple[list[dict], dict[str, str]]:
    encoding = detect_encoding(path)
    with path.open("r", encoding=encoding, newline="") as f:
        reader = csv.DictReader(f)
        header = list(reader.fieldnames or [])
        colmap = resolve_columns(header)
        rows = list(reader)
    return rows, colmap


def _cell(row: dict, colmap: dict[str, str], field: str) -> str:
    col = colmap.get(field)
    if col is None:
        return ""
    val = row.get(col)
    if not val:
        return ""
    return clean(val)


def _category_to_tag(category: str) -> str | None:
    if not category:
        return None
    return CATEGORY_TAG_ALIASES.get(category.strip())


# 원어 컬럼 말미의 언어 출처 표기(예: '가이던스(guidance(영))')에서 실제로
# 관찰된 언어 코드 (감사 실측: 영 542 / 일 3 / 독 3 / 프 1 / 스 1). 여기 없는
# 코드가 나오면 인식 못 하고 안전하게 원문을 그대로 둔다(아래 _split_term_headwords).
_LANGUAGE_CODES: frozenset[str] = frozenset({"영", "일", "독", "프", "스", "중", "러"})


def _peel_trailing_paren(s: str) -> tuple[str, str | None]:
    """문자열 끝의 최상위 괄호 하나를 통째로 떼어낸다.

    괄호 깊이를 오른쪽부터 추적해서 뗀다 — `narration(영)`처럼 안쪽에 또
    괄호가 중첩돼 있어도(`...(narration(영))`) 바깥쪽 괄호 하나만 정확히
    떼어낸다. 끝이 괄호로 안 끝나거나 짝이 안 맞으면 (원문, None).

    >>> _peel_trailing_paren('가검물(可檢物)')
    ('가검물', '可檢物')
    >>> _peel_trailing_paren('가이던스(guidance(영))')
    ('가이던스', 'guidance(영)')
    >>> _peel_trailing_paren('내레이션')
    ('내레이션', None)
    """
    s = s.rstrip()
    if not s.endswith(")"):
        return s, None
    depth = 0
    for i in range(len(s) - 1, -1, -1):
        ch = s[i]
        if ch == ")":
            depth += 1
        elif ch == "(":
            depth -= 1
            if depth == 0:
                return s[:i].rstrip(), s[i + 1:-1]
    return s, None  # 괄호 짝이 안 맞음 — 못 뗀다


def _has_language_marker(note: str) -> bool:
    """원어 표기 괄호 내용(note)이 알려진 언어 코드 중첩 괄호로 끝나는지 본다.

    예: 'guidance(영)' -> True, 'label(프, 영)' -> True(둘 다 코드), '架設--' -> False.
    """
    _, marker = _peel_trailing_paren(note)
    if not marker:
        return False
    parts = [p.strip() for p in marker.split(",") if p.strip()]
    return bool(parts) and all(p in _LANGUAGE_CODES for p in parts)


def _split_term_headwords(term_raw: str) -> tuple[list[tuple[str, bool]], str | None, bool]:
    """원어 컬럼 원문을 표제어(들)로 분해한다.

    DESIGN.md §6.3 계약 밖 보강: 실데이터(국립국어원 2018 행정용어 1,075행)
    감사에서 발견한 패턴이다. 원어 컬럼의 쉼표는 순화어 컬럼의 쉼표와 의미가
    다르다 — 순화어의 쉼표는 서로 다른 대안(별개 Entry가 맞다)이지만, 원어의
    쉼표는 **같은 개념의 다른 표기**다(예: `내레이션, 나레이션*(narration(영))`은
    표준 표기 '내레이션'과 비표준 표기 '나레이션' 둘 다 같은 개념을 가리킨다).
    그래서 별개 Entry로 쪼개면 안 되고, 첫 번째를 표제어로 나머지는 variants로
    넣는다. `*`가 붙은 표기는 비표준(오표기) 표시로 보여 `typo` kind로,
    없으면 `synonym` kind로 나눈다 — DESIGN.md §3.4가 "수동 등록 전용"이라고
    적어둔 typo kind의 재료를 실데이터가 준 사례로 판단했다. `*`는 표면형이
    아니므로 반드시 제거하고 저장한다.

    말미 괄호 판정(원어 표기 vs 한글 뜻풀이, CJK 호환 한자, `架設--` 같은
    활용어미 자리표시자 처리)은 **normalize.split_hanja()에 위임**한다 —
    이미 실데이터로 검증된 로직을 build.py에서 다시 만들지 않는다. 이 함수가
    새로 보태는 건 딱 둘: (1) 원어 표기로 판정된 부분(prefix)의 콤마를
    "같은 개념의 다른 표기" 목록으로 분해하는 것, (2) 한자가 아닌 원어 표기가
    외래어(언어 코드 태그)인지 판별해 태그를 다는 것 — split_hanja는 "한자가
    아니다"까지만 알려주고 "그래서 외래어냐"는 구분해 주지 않는다.

    >>> _split_term_headwords('가검물(可檢物)')
    ([('가검물', False)], '可檢物', False)
    >>> _split_term_headwords('가이던스(guidance(영))')
    ([('가이던스', False)], None, True)
    >>> _split_term_headwords('내레이션, 나레이션*(narration(영))')
    ([('내레이션', False), ('나레이션', True)], None, True)
    >>> _split_term_headwords('과태료(늦게 내는 돈)')
    ([('과태료(늦게 내는 돈)', False)], None, False)
    """
    _, note = _peel_trailing_paren(term_raw)
    if note is None:
        return [(term_raw, False)], None, False

    prefix, term_hanja = split_hanja(term_raw)
    if prefix == nfc(term_raw):
        # split_hanja가 한글 뜻풀이형 괄호로 보고 안 뗐다 — 콤마도 안 쪼갠다.
        return [(term_raw, False)], None, False

    is_loanword = term_hanja is None and _has_language_marker(note)

    raw_variants = [v.strip() for v in prefix.split(",") if v.strip()]
    headwords: list[tuple[str, bool]] = []
    for v in raw_variants:
        is_typo = v.endswith("*")
        surface = v[:-1].strip() if is_typo else v
        if surface:
            headwords.append((surface, is_typo))
    if not headwords:
        headwords = [(term_raw, False)]

    return headwords, term_hanja, is_loanword


_HANJA_SUBSTR_RE = re.compile(r"[一-鿿]+")


_LATIN_OR_KANA_RE = re.compile(r"[A-Za-z぀-ヿ]")  # 로마자 또는 가나(히라가나·가타카나)


def _classify_origin(raw: str) -> tuple[str | None, bool]:
    """국립국어원 다듬은말의 `original_term`(어원) 컬럼을 판정한다.

    다른 실데이터는 표제어 말미 괄호를 파싱해 어종을 추정해야 했지만,
    이 원천은 어원을 별도 컬럼으로 정확히 준다 — 파싱 없이 정규식 하나로
    끝난다. 혼합(예: `lagging效果` = 영어 'lagging' + 한자 '效果')은
    드물지만 실데이터에 있다: 한자 부분만 뽑아 term_hanja로 보존하고,
    실제로 로마자·가나 성분이 함께 있으면 loanword 태그도 같이 붙인다
    — "둘 중 하나만" 고르면 어느 쪽을 골라도 나머지 절반의 정보를 잃는다.

    **외래어 신호는 "한자가 아닌 나머지 전부"가 아니라 로마자·가나가
    실제로 있는지로만 판정한다.** 처음엔 한자를 뺀 나머지가 하나라도
    남으면 외래어로 봤는데, 실데이터 감사에서 1,808건(한자 포함 행의
    16.7%)이 오분류로 드러났다 — `提供--`처럼 `架設--`(§split_hanja)와
    같은 활용어미 자리표시자(`--`)가 한자를 뺀 뒤 남아서 "비한자 성분
    있음"으로 잘못 잡혔다(한글이 아니라 대시가 원인이었다 — 처음
    가설은 한글이었지만 실측하니 자리표시자였다). 로마자·가나 존재
    여부를 직접 검사하면 대시든 한글이든 구두점이든 전부 무해하게
    무시되고, 진짜 외래어 신호(로마자·가나)만 남는다.

    **두 번째 결함(이번 수정)**: 그 로마자 검사를 원문 전체에 대고 하면,
    한자어 본체 뒤에 **영어 번역 주석**이 괄호로 붙은 행까지 외래어로
    오판한다 — `壓挫症候群(crush syndrome(영))`은 순수 한자어인데 괄호
    안 영어 gloss 때문에 loanword=True가 됐다(실데이터 19건 확인).
    `(언어코드)`로 끝나는 말미 괄호(`_has_language_marker`가 이미
    `_split_term_headwords`에서 쓰던 것과 같은 판정, §요청: "괄호 처리
    로직을 새로 짜지 마라")를 **주석**으로 보고, 그 괄호 블록만 어종
    판정 대상에서 뺀다. 말미 괄호가 없거나 언어 코드로 끝난다고 확신할
    수 없으면(예: `兩面石器(biface)` — "biface"는 언어 코드가 아니다)
    원문 전체를 그대로 스캔한다 — 애매하면 보수적으로 둔다.

    이 규칙이 안전한 이유는 실데이터 전수 감사로 확인했다: 말미 괄호를
    무조건 다 떼면 4건이 깨진다 — `(←hiphop+hoop) 籠球`(어원이 **선두**
    괄호에 있어 애초에 "말미" 규칙 대상이 아님, 그대로 보존), `踏(み)板
    (일)`·`蒲焼(き) (일)`(가나가 **말미 괄호 안이 아니라 그 앞 본문에**
    있어 말미 괄호만 떼도 가나는 그대로 남음). 셋 다 이 함수가 "말미
    괄호만" 떼기 때문에 저절로 안전하다 — 본문에 있는 진짜 어원 신호는
    건드리지 않는다.

    >>> _classify_origin('居住地')
    ('居住地', False)
    >>> _classify_origin('fabless')
    (None, True)
    >>> _classify_origin('lagging效果')
    ('效果', True)
    >>> _classify_origin('提供--')
    ('提供', False)
    >>> _classify_origin('壓挫症候群(crush syndrome(영))')
    ('壓挫症候群', False)
    >>> _classify_origin('服地, ふくじ(일)')
    ('服地', True)
    """
    if not raw:
        return None, False
    scan_target = raw
    base, note = _peel_trailing_paren(raw)
    if note is not None:
        is_bare_code = bool(
            [p.strip() for p in note.split(",") if p.strip()]
        ) and all(p.strip() in _LANGUAGE_CODES for p in note.split(",") if p.strip())
        if is_bare_code or _has_language_marker(note):
            # 말미 괄호가 언어 코드 자체이거나("...(일)") "설명(언어 코드)"
            # 형태의 주석으로 확실히 인식됐다 — 그 블록은 어종 판정 대상이
            # 아니다. base(주석 이전 본문)만 스캔한다.
            scan_target = base
    hanja_parts = _HANJA_SUBSTR_RE.findall(scan_target)
    term_hanja = "".join(hanja_parts) if hanja_parts else None
    is_loanword = bool(_LATIN_OR_KANA_RE.search(scan_target))
    return term_hanja, is_loanword


def _expand_slash_if_marked(surface: str, is_typo: bool) -> list[tuple[str, bool]]:
    """`*`가 있을 때만 슬래시를 이형태 구분자로 본다.

    실데이터(국립국어원 다듬은말)의 `source_term` 슬래시는 뜻이 갈린다 —
    `패블리스/*팹리스`처럼 이형태 나열(접두 `*`)일 때도 있지만, `TM/TC`·
    `T/S`·`C/S`처럼 **슬래시 자체가 약어의 일부**이거나, `인풋/아웃풋`처럼
    **서로 다른 두 개념을 하나로 묶은 표기**(순화어도 '입출력' 하나로
    합쳐져 있음 — 이형태가 아니라 결합 개념)인 경우도 섞여 있다. `*`
    없이는 어느 쪽인지 구별할 신뢰할 만한 신호가 없어서, `*`가 있을
    때만 쪼갠다 — 잘못 쪼개 약어·결합 개념을 깨느니 `*` 없는 이형태
    몇 건을 놓치는 쪽을 택했다(안전 우선, 근거는 빌드 보고에 남긴다).
    """
    if "/" not in surface or "*" not in surface:
        return [(surface, is_typo)]
    parts: list[tuple[str, bool]] = []
    for part in surface.split("/"):
        part = part.strip()
        if not part:
            continue
        part_is_typo = is_typo or part.startswith("*")
        if part.startswith("*"):
            part = part[1:].strip()
        if part:
            parts.append((part, part_is_typo))
    return parts if parts else [(surface, is_typo)]


def _expand_multi_headword_variants(headwords: list[tuple[str, bool]]) -> list[tuple[str, bool]]:
    """표제어 후보 안에 콤마 또는 슬래시로 나열된 이형태가 있으면 마저 펼친다.

    국립국어원 다듬은말(18,340건) 실데이터에서 확인된 두 가지 표기 관례:
    - 콤마(+접미 `*`): `비바리엄, 비바리움*` — 569행(3.1%)으로 슬래시보다
      훨씬 많다. `*`는 항상 접미(끝에 붙음).
    - 슬래시(+접두 `*`): `패블리스/*팹리스` — 28행. `*`는 항상 접두.
      `_expand_slash_if_marked`가 `*` 유무로 안전하게 가른다.

    `_split_term_headwords`가 다루는 콤마+괄호 조합(다른 실데이터, 원어
    한자/외래어 병기가 있는 파일)과는 별개의 표기 관례라 따로 처리한다 —
    이 파일의 `source_term`은 애초에 괄호를 안 쓴다.
    """
    expanded: list[tuple[str, bool]] = []
    for surface, is_typo in headwords:
        if "," not in surface:
            expanded.extend(_expand_slash_if_marked(surface, is_typo))
            continue
        for part in surface.split(","):
            part = part.strip()
            if not part:
                continue
            part_is_typo = is_typo or part.endswith("*")
            if part.endswith("*"):
                part = part[:-1].strip()
            if part:
                expanded.extend(_expand_slash_if_marked(part, part_is_typo))
    return expanded


_MEANING_EXAMPLE_MARKER_RE = re.compile(r"\n?<관련\s*예문>.*", re.DOTALL)


def _clean_definition_with_examples(raw: str) -> str:
    """`meaning_examples` 컬럼에서 `<관련 예문>` 표시 이후의 원문 인용·출처를
    잘라내고 정의문만 남긴다.

    판단 근거: 표시 이후의 예문은 신문기사 원문을 그대로 인용한 것이라
    이미 자체적으로 `원어(→순화어)` 식 괄호 표기·출처 표기(`《...》`)를
    포함하고 있다. 이걸 그대로 definition에 붙이면 정의문이 지저분해지고,
    examples 테이블에 넣으면 `_finalize_examples()`가 만드는 gloss 표기와
    겹쳐 괄호가 중복되는 등 노이즈가 커진다. 정의문 오염을 막는 게 우선이라
    이 부분은 버리고 examples에는 넣지 않는다 — 예문 0건인 다른 2열짜리
    실데이터 원천(nikl_admin_terms_2018.csv)과 같은 안전한 기본값이다.
    """
    return _MEANING_EXAMPLE_MARKER_RE.sub("", raw).strip()


# 폐기 사유 진단 로그(부작용 채널). row_to_entries()의 반환 시그니처는
# DESIGN.md §6.3에 고정된 계약이라(`-> list[Entry]`만) 버려진 행의 사유를
# 실어 보낼 자리가 없다. "조용한 삭제"가 45행짜리 데이터 손실을 숨긴 사고
# (§실데이터 감사)를 겪은 뒤, 계약을 안 건드리면서 그걸 없앨 수 있는
# 가장 작은 변경으로 이 모듈 전역 리스트를 택했다. 단일 스레드 CLI 빌드
# 도구라 전역 상태 위험이 낮다. main()이 빌드 시작 시 비우고 끝에서 읽는다.
_discard_log: list[dict[str, str]] = []


def _note_discard(reason: str, lineno: int, term: str, easy_term: str = "") -> None:
    _discard_log.append({"reason": reason, "line": str(lineno), "term": term, "easy_term": easy_term})


# readability 명시 여부 판정용 sentinel. models.py가 `Entry.readability`의
# 기본값과 동일하게 정의해 소유한다(근거는 그쪽 주석 참고) — build.py는
# id(entry) 전역 집합 추적을 시도했다가 GC 재사용 결함으로 폐기하고(테스트
# 3회 재현), 이 값 기반 sentinel로 바꿨다. 두 파일에 각각 정의하면 한쪽만
# 바뀔 때 조용히 어긋나므로 여기서는 import만 한다.


def _parse_readability(raw: str) -> int:
    """'초급'/'중급'/'고급' 또는 숫자 문자열을 1~3 정수로 해석한다.

    범위를 벗어나거나 해석 불가하면 ValueError — 호출부(row_to_entries)가
    행 번호를 붙여 다시 던진다.
    """
    raw = raw.strip()
    if raw in READABILITY_GRADE_NAMES:
        return READABILITY_GRADE_NAMES[raw]
    if raw.lstrip("-").isdigit():
        value = int(raw)
        if 1 <= value <= 3:
            return value
        raise ValueError(f"readability 값 {value}이(가) 유효 범위(1~3)를 벗어났습니다.")
    raise ValueError(
        f"readability 값 {raw!r}을(를) 해석할 수 없습니다. 허용값: 1~3 또는 초급/중급/고급"
    )


def row_to_entries(row: dict, colmap: dict[str, str], source: Source, lineno: int) -> list[Entry]:
    """한 CSV 행을 Entry 후보 목록으로 변환한다.

    순화어 셀에 복수 대안이 있으면 Entry를 여러 개 만든다.
    빈 값 / term == easy_term 자기참조 행은 버린다.
    """
    term_raw = _cell(row, colmap, "term")
    easy_raw = _cell(row, colmap, "easy_term")
    if not term_raw or not easy_raw:
        _note_discard("empty", lineno, term_raw, easy_raw)
        return []

    headwords, term_hanja_from_term, is_loanword = _split_term_headwords(term_raw)
    headwords = _expand_multi_headword_variants(headwords)  # '비바리엄, 비바리움*' / '패블리스/*팹리스'류
    term = clean(headwords[0][0])
    if not term:
        _note_discard("empty", lineno, term_raw, easy_raw)
        return []
    extra_headwords = headwords[1:]  # 같은 개념의 다른 표기 -> variants로 (별개 Entry 아님)

    definition = _cell(row, colmap, "definition") or None
    if definition:
        definition = _clean_definition_with_examples(definition) or None
    category = _cell(row, colmap, "category") or None
    example_text = _cell(row, colmap, "example") or None
    hanja_col = _cell(row, colmap, "hanja") or None
    note = _cell(row, colmap, "note") or None

    # 어원 전용 컬럼(국립국어원 다듬은말의 original_term) — 있으면 표제어
    # 괄호 파싱보다 우선해 한자/외래어를 정확히 판정한다.
    origin_raw = _cell(row, colmap, "origin_language") or None
    if origin_raw:
        origin_hanja, origin_is_loanword = _classify_origin(origin_raw)
    else:
        origin_hanja, origin_is_loanword = None, False
    is_loanword = is_loanword or origin_is_loanword

    # 사람이 CSV에 직접 써넣은 분류값(§요청 3). classify()의 휴리스틱은
    # 「」·법률 키워드 화이트리스트 기반이라 '수급권자'·'차상위계층'처럼
    # 목록에 없는 복지 제도 명칭은 놓친다 — 사람이 이미 판단한 값이 있으면
    # 그걸 신뢰한다. 유효 범위를 벗어나면 CHECK 제약에 죽기 전에 여기서
    # 명확한 에러로 빌드를 중단시킨다(호출부 main()이 ValueError를 잡는다).
    explicit_strategy = _cell(row, colmap, "replace_strategy") or None
    if explicit_strategy and explicit_strategy not in REPLACE_STRATEGIES:
        raise ValueError(
            f"{source.code} {lineno}행: replace_strategy 값 {explicit_strategy!r}이(가) "
            f"유효하지 않습니다. 허용값: {REPLACE_STRATEGIES}"
        )
    explicit_risk = _cell(row, colmap, "risk_level") or None
    if explicit_risk and explicit_risk not in RISK_LEVELS:
        raise ValueError(
            f"{source.code} {lineno}행: risk_level 값 {explicit_risk!r}이(가) "
            f"유효하지 않습니다. 허용값: {RISK_LEVELS}"
        )
    explicit_status = _cell(row, colmap, "status") or None
    if explicit_status and explicit_status not in STATUSES:
        raise ValueError(
            f"{source.code} {lineno}행: status 값 {explicit_status!r}이(가) "
            f"유효하지 않습니다. 허용값: {STATUSES}"
        )
    has_explicit = explicit_strategy is not None

    # readability는 replace_strategy와 독립적으로 명시될 수 있다(§word_grade).
    # 있으면 classify()의 휴리스틱 파생을 건너뛴다 — READABILITY_UNSET 참고.
    explicit_readability_raw = _cell(row, colmap, "readability") or None
    explicit_readability: int | None = None
    if explicit_readability_raw:
        try:
            explicit_readability = _parse_readability(explicit_readability_raw)
        except ValueError as e:
            raise ValueError(f"{source.code} {lineno}행: {e}") from e

    term_hanja = term_hanja_from_term or hanja_col or origin_hanja
    tag = _category_to_tag(category) if category else None
    term_norm = normalize_key(term)

    entries: list[Entry] = []
    # enumerate()로 원문 셀 안에서의 원래 위치(0-base)를 그대로 남긴다 — 뒤에서
    # 걸러지는(자기참조 등) 후보가 있어도 살아남은 후보들의 상대 순서는
    # 바뀌지 않으므로 인덱스에 구멍이 생겨도 무방하다(§6.8 키 ④).
    for cell_rank, easy_candidate in enumerate(split_multi(easy_raw)):
        easy_term = clean(easy_candidate)
        if not easy_term:
            _note_discard("empty", lineno, term, easy_candidate)
            continue
        if easy_term == term and explicit_strategy != "keep":
            # 자기참조 제거. 단 명시 전략이 'keep'이면 예외다 — keep은
            # 개념적으로 "원어=순화어"가 정의 그 자체다("이 말은 그대로
            # 두라"는 뜻). 원어 표기 하나를 다른 표기로 또 바꾸는 게 아니라
            # 원어를 원어 그대로 지키는 것이므로, term==easy_term은 데이터
            # 오류가 아니라 keep 엔트리의 정상 형태다. 명시가 없는데 우연히
            # term==easy_term인 행(예: 원본 CSV의 단순 오기)만 여전히 버린다.
            _note_discard("self_ref", lineno, term, easy_term)
            continue

        entry = Entry(
            term=term,
            easy_term=easy_term,
            term_norm=term_norm,
            term_hanja=term_hanja,
            definition=definition,
            caution=note,
            cell_rank=cell_rank,
            source_code=source.code,
            source_ref=f"row:{lineno}",
        )
        # 명시값이 없으면 READABILITY_UNSET(=Entry 기본값)을 명시적으로 다시
        # 써 둔다 — sentinel의 의미와 근거는 models.py 쪽 주석 참고.
        entry.readability = explicit_readability if explicit_readability is not None else READABILITY_UNSET
        if has_explicit:
            entry.replace_strategy = explicit_strategy
            # risk_level이 안 주어졌으면 전략에서 안전한 기본값을 유도한다:
            # gloss/keep은 원래도 review 큐로 보내는 게 기본(§5.2)이라 'high',
            # substitute는 위험 신호가 없다는 뜻이라 'none'. status도 같은
            # 방식(risk=high -> review)으로 유도하되, CSV가 직접 준 값이 있으면
            # 그게 최종값이다 — 사람이 'substitute'+'review'를 동시에 명시하는
            # 것도 여기서는 막지 않는다(막으면 조용한 자동교정이 된다). 그
            # 위험한 조합은 main()이 전체 엔트리에 대해 별도로 검사해 빌드를
            # 중단시킨다(불변식: 미검수 항목은 원문을 지울 수 없다).
            entry.risk_level = explicit_risk or ("high" if explicit_strategy in ("gloss", "keep") else "none")
            entry.status = explicit_status or ("review" if entry.risk_level == "high" else "active")
            entry.confidence = 1.0  # classify()에 "사람이 이미 정했다"를 알리는 신호
        if tag:
            entry.tags.append(tag)
        if is_loanword:
            # register 태그다. classify()가 primary_tag를 TAG_PRIORITY로 고르므로
            # 여기 삽입 순서는 대표 태그 선정에 영향을 주지 않는다(도메인 태그가
            # 항상 우선한다) — 순서를 신경 쓰지 않고 그냥 추가하면 된다.
            entry.tags.append("loanword")
        for surface, is_typo in extra_headwords:
            surface_clean = clean(surface)
            if not surface_clean or surface_clean == term:
                continue
            entry.variants.append(
                Variant(surface=surface_clean, kind="typo" if is_typo else "synonym", is_auto=False)
            )
        if example_text:
            # after_text는 여기서 확정하지 않는다. replace_strategy가 아직
            # 정해지지 않았기 때문이다(classify()가 나중에 결정한다) —
            # before=after 그대로인 자리표시자만 넣어 두고, _finalize_examples()가
            # classify() 안에서 전략에 맞게 다시 쓴다.
            entry.examples.append(Example(before_text=example_text, after_text=example_text))
        entries.append(entry)
    return entries


# 예문 문장 안에서 term이 활용형(예: '수령하실', '개시합니다')의 일부로 나타난
# 경우를 가려낸다. lookup.EasyDict._boundary_re와 같은 조각(normalize.josa_pattern())을
# 그대로 재사용한다 — 새 경계 규칙을 만들지 않는다. 표제어 뒤에 조사 연쇄가
# 0개 이상 이어진 다음 어절 경계(비한글)가 와야 "원형 그대로의 매치"로 본다.
# 이 경계를 통과하지 못하면(예: '수령' 뒤에 '하실'처럼 한글 음절이 그대로
# 이어지면) surface가 term과 달라진 활용형 매치라는 뜻이다(§6.5의
# Match.is_inflected와 같은 신호를 문자열 위에서 재현한 것).
_TERM_BOUNDARY_RE = re.compile("(?=" + josa_pattern() + ")")


def _substitute_term_respecting_inflection(before: str, term: str, easy: str) -> str:
    """substitute 전략의 예문 합성에서 활용형 매치를 gloss로 폴백한다.

    `normalize.substitute_with_josa()`는 `term`이 문장 어디에 있든 무조건
    `easy_term`으로 바꾼다. 그런데 '수령하실'처럼 term(`수령`) 바로 뒤에 활용
    어미가 붙어 있는 자리까지 그대로 바꾸면 어간만 잘려나가 '받음하실'
    같은 비문이 된다 — `lookup.EasyDict.annotate()`가 `Match.is_inflected`로
    이미 막아 둔 사고와 정확히 같다(그 함수 docstring의 "왜 활용형에 자동
    활용을 하지 않는가" 참고). 예문은 few-shot이라 비문을 그대로 보여주면
    LLM에게 비문을 가르치는 셈이라 여기서도 같은 원칙을 적용해야 한다.

    각 등장 위치에서 term 뒤가 어절 경계(조사 연쇄 다음 비한글)면 안전하게
    치환하고(조사도 easy_term 받침에 맞춰 교정 — substitute_with_josa와 동일한
    규칙, `find_josa_after`/`correct_josa_form`을 그대로 재사용한다), 아니면
    (뒤에 한글 음절이 그대로 이어지면=활용형) 원어를 지우지 않고 gloss와
    같은 `term(easy_term)` 형태로 보존한다.

    >>> _substitute_term_respecting_inflection('현금으로 수령하실 수 없습니다.', '수령', '받음')
    '현금으로 수령(받음)하실 수 없습니다.'
    >>> _substitute_term_respecting_inflection('급여는 월 30만 원입니다.', '급여', '지원금')
    '지원금은 월 30만 원입니다.'
    """
    positions: list[int] = []
    start = 0
    while True:
        idx = before.find(term, start)
        if idx < 0:
            break
        positions.append(idx)
        start = idx + len(term)
    if not positions:
        return before

    n = len(before)
    limits = [
        positions[i + 1] if i + 1 < len(positions) else n for i in range(len(positions))
    ]

    result = before
    for idx, limit in zip(reversed(positions), reversed(limits)):
        end = idx + len(term)
        if _TERM_BOUNDARY_RE.match(before, end) is None:
            # 활용형 매치: gloss와 동일하게 원어를 보존한다.
            replacement = f"{term}({easy})"
            cut_end = end
        else:
            replacement = easy
            cut_end = end
            found = find_josa_after(before, end, limit)
            if found is not None and easy:
                current_form, pair = found
                correct_form = correct_josa_form(pair, easy[-1])
                if correct_form is not None and correct_form != current_form:
                    replacement += correct_form
                    cut_end = end + len(current_form)
        result = result[:idx] + replacement + result[cut_end:]
    return result


def _finalize_examples(entry: Entry) -> None:
    """예문의 after_text를 확정된 replace_strategy에 맞게 다시 쓴다.

    few-shot 예문은 프롬프트 지시문보다 LLM에 더 강하게 작용한다. 원문
    치환 로직을 `lookup.EasyDict.annotate()`와 동일한 규칙으로 맞춘다:

    - `substitute`: 원어를 easy_term으로 교체한 문장을 보여준다 (원문 유지).
    - `gloss`     : 원어를 지우지 않고 `원어(easy_term)` 형태로 보존한다
                    (annotate()의 gloss 표기와 동일). 원어가 사라지면
                    "과태료 → 정해진 법을 안 지켜서 내는 돈"처럼 §2.1이 경고한
                    사고(법적 개념 소멸)를 예문이 그대로 LLM에 시범 보이게 된다.
    - `keep`      : 예문을 아예 만들지 않는다. "바꾸면 안 된다"는 엔트리에
                    치환 전/후가 동일한 예문을 넣어 봐야 정보가 없고 토큰만
                    먹는다. "왜 바꾸면 안 되는지"는 프롬프트의 caution/definition이
                    전달한다.

    원어(term)가 예문 문장에 문자 그대로 없으면(활용형이라 표제어 원형이
    그대로 나타나지 않는 경우 등) 잘못된 치환을 합성하느니 그 예문을 버린다.
    """
    if entry.replace_strategy == "keep":
        entry.examples = []
        return

    term = entry.term
    easy = entry.easy_term
    finalized: list[Example] = []
    for ex in entry.examples:
        before = ex.before_text
        if term not in before:
            continue
        if entry.replace_strategy == "gloss":
            # 원어를 지우지 않고 보존하므로 조사는 이미 원어에 맞물려 있다 —
            # 조사 교정이 필요 없다(정확히는, 필요 없어야 하는 게 아니라
            # 애초에 대상이 아니다: 뒤따르는 조사가 여전히 '원어'에 붙어 있음).
            after = before.replace(term, f"{term}({easy})")
        else:  # substitute
            # 원어가 지워지고 easy_term으로 바뀌므로 받침 유무가 달라질 수
            # 있다(예: '필증'(받침 없음)->'증명서'(받침 없음)은 괜찮지만
            # '말미'->'여유 시간'처럼 뒤 글자가 받침 유무를 바꾸면 조사가
            # 안 맞아 비문이 된다). 또한 term이 문장에서 활용형의 일부로
            # 나타난 경우(예: '수령하실')는 어간만 잘라 바꾸면 '받음하실'
            # 같은 비문이 된다 — _substitute_term_respecting_inflection()이
            # 등장 위치마다 어절 경계를 확인해, 원형 매치는 조사까지 교정해
            # 치환하고 활용형 매치는 gloss처럼 원어를 보존한다.
            after = _substitute_term_respecting_inflection(before, term, easy)
        finalized.append(Example(before_text=before, after_text=after, note=ex.note, is_golden=ex.is_golden))
    entry.examples = finalized


def classify(entry: Entry) -> Entry:
    """태그·위험도·치환전략·priority를 결정한다 (DESIGN.md §5.2).

    `entry.confidence == 1.0`이면 CSV가 replace_strategy를 사람이 직접
    지정한 것으로 본다(row_to_entries() 참고) — 이 함수는 그 값을 휴리스틱
    으로 덮어쓰지 않는다. 사람의 판단이 「」·법률 키워드 화이트리스트보다
    정확하다는 전제다(§요청 3). 태그·대표태그·품사·우선순위는 명시 여부와
    무관하게 항상 계산한다 — 사람은 전략만 정했지 태그까지 정하지 않았다.
    """
    term = entry.term
    easy = entry.easy_term
    has_explicit_strategy = entry.confidence == 1.0

    if not has_explicit_strategy:
        is_keep = (
            term in KEEP_LIST
            or _LEGAL_BRACKET_RE.search(term) is not None
            or _AMOUNT_DATE_RE.search(term) is not None
        )

        if is_keep:
            entry.replace_strategy = "keep"
            entry.risk_level = "high"
        elif term in LEGAL_RISK_LIST:
            entry.replace_strategy = "gloss"
            entry.risk_level = "high"
        elif (
            (len(term) == 1 and term not in PROMOTED_SHORT_SUBSTITUTE)
            or term in HOMOGRAPH_COLLISION_TERMS
        ):
            # DESIGN.md §5.5.1: 1음절 표제어는 초고빈도 고유어 문법 요소
            # (관형사·활용어미)와 형태가 겹칠 위험이 커서 휴리스틱만으로
            # substitute를 못 받는다 — PROMOTED_SHORT_SUBSTITUTE에 사람이
            # 코퍼스로 확인해 올린 것만 예외다. 2음절 이상 중 개별 감사로
            # 동형이의어 충돌이 확인된 것(HOMOGRAPH_COLLISION_TERMS)도 같이
            # 내린다 — 둘 다 원어를 지우지 않는 gloss가 기본값이다.
            entry.replace_strategy = "gloss"
            entry.risk_level = "high"
        elif "law" in entry.tags and len(easy) > 12:
            entry.replace_strategy = "gloss"
            entry.risk_level = "low"
        elif easy.count(" ") >= 2:
            # 순화어가 설명문 형태(공백 2개 이상) -> 짧은 대치어가 아니라 풀이문으로 본다.
            entry.replace_strategy = "gloss"
            entry.risk_level = "low"
        else:
            entry.replace_strategy = "substitute"
            entry.risk_level = "none"

        entry.status = "review" if entry.risk_level == "high" else "active"

    def _promote_tag(tag: str) -> None:
        """이 태그를 대표 태그(entry.tags[0])로 승격한다.

        원천 CSV가 준 분야 태그는 지우지 않고 그대로 목록에 남긴다(어느 출처에서
        왔는지도 정보다, DESIGN.md §2.4) — 다만 내용상 더 정확한 판정이 있으면
        그것을 대표로 세운다. 원천 태그를 맹목적으로 따라가면 「국민기초생활
        보장법」이 admin CSV에서 왔다는 이유만으로 '행정'이 대표 태그가 되는
        식의 오분류가 생긴다.
        """
        if tag in entry.tags:
            entry.tags.remove(tag)
        entry.tags.insert(0, tag)

    # 법령명 판정은 원천 태그보다 우선한다: 「」로 감싸여 있거나, LEGAL_RISK_LIST에
    # 속한 법적 절차·제재 용어이거나, KEEP_LIST에 등록된 표제어 중 법/법률/법령
    # 접미사로 끝나는 것. 이 판정 자체는 "이 용어가 무엇인가"라는 내용 판단이라
    # 어느 CSV에서 긁어왔는지와 무관하게 성립해야 한다.
    is_law_name = (
        _LEGAL_BRACKET_RE.search(term) is not None
        or term in LEGAL_RISK_LIST
        or (term in KEEP_LIST and term.endswith(("법", "법률", "법령")))
    )
    if is_law_name:
        _promote_tag("law")
    elif term in KEEP_TAG_OVERRIDES:
        # 「」도 없고 법령명 접미사도 아닌 제도 고유명칭(KEEP_TAG_OVERRIDES 참고).
        _promote_tag(KEEP_TAG_OVERRIDES[term])
    # 금액·기한 표현(§5.2, 예: '월 30만 원', '신청일부터 30일 이내')은 의도적으로
    # 도메인 태그를 강제하지 않는다 — 행정·법률·복지 문서 어디에나 똑같이 나오는
    # 범용 표현이라 특정 도메인으로 단정하면 오히려 부정확하다. 원천 CSV가 준
    # 태그(있다면)를 그대로 대표로 남겨 "이 표현이 어느 문서 맥락에서 나왔는지"
    # 정보만 유지한다.

    if (entry.term_hanja or _HANJA_CHAR_RE.search(term)) and "hanja" not in entry.tags:
        entry.tags.append("hanja")
    if entry.risk_level == "high" and "needs_review" not in entry.tags:
        entry.tags.append("needs_review")
    if not entry.tags:
        entry.tags.append("jargon")

    # 대표 태그는 삽입 순서가 아니라 TAG_PRIORITY 고정 서열로 고른다. 이렇게
    # 하면 row_to_entries()가 register 태그(loanword 등)를 도메인 태그보다
    # 먼저 넣거나, --default-tag가 뒤늦게 도메인 태그를 append하는 경우에도
    # (§ main()의 default_tag 처리 참고) 삽입 순서와 무관하게 항상 도메인
    # 태그가 대표로 뽑힌다 — 지난번 다출처 재현성 수정과 같은 원칙이다.
    entry.primary_tag = _pick_primary_tag(set(entry.tags))
    entry.pos = entry.pos or guess_pos(term)
    entry.priority = 100 + len(term) * 10
    if not has_explicit_strategy:
        entry.confidence = 0.5 if entry.risk_level == "high" else 0.85
    # has_explicit_strategy면 confidence는 이미 1.0(row_to_entries가 설정) — 그대로 둔다.
    if entry.readability == READABILITY_UNSET:
        # readability(§3.2: "결과물 난이도" — 순화 *결과*가 얼마나 쉬운가)와
        # replace_strategy(원어를 어떻게 다룰지)는 원래 다른 축이다. substitute/
        # gloss는 이 구분이 실질적으로 readability와도 맞물린다 — substitute는
        # 최종 문장에 원어가 아예 안 남고(가장 쉬움=1), gloss는 원어가 그대로
        # 남은 채 괄호 설명만 덧붙으므로(원어+설명을 다 읽어야 함=2) 이 정도
        # 단순화는 근거가 있다. 하지만 keep을 무조건 3으로 매기는 건 근거가
        # 약하다 — "바뀌지 않는다"는 사실 자체가 "그 결과가 어렵다"를 뜻하지
        # 않는다(사용자 지적). keep 안에서도 「」·금액·기한 표현(예: '월 30만
        # 원', '2026년 1월 15일')은 원어 자체가 평이한 사실 표기라 그대로 둬도
        # 안 어렵고(=1), 법령명·제도 고유명칭처럼 어휘 자체가 긴 한자어·전문
        # 용어인 keep만 실제로 어렵다(=3). 그래서 keep만 내용 기준으로 다시
        # 나눈다. gloss/substitute의 단순화는 그대로 둔다 — word_grade가
        # 곧 대부분의 실제 엔트리를 명시값으로 덮을 것이므로, 휴리스틱은
        # word_grade가 없는 소수 사례의 대체재일 뿐이라 이 이상 정교화할
        # 가치가 낮다고 판단했다.
        if entry.replace_strategy == "keep":
            entry.readability = 1 if _AMOUNT_DATE_RE.search(term) is not None else 3
        else:
            entry.readability = {"substitute": 1, "gloss": 2}[entry.replace_strategy]

    # 사용자 지시: "[확인 필요]"가 caution에 있으면 법조문 원문 대조 전이라
    # 신뢰할 수 없는 뜻풀이다 -> status='deprecated'로 강제한다. CSV가 status를
    # 명시했더라도(has_explicit_strategy) 이게 우선한다 — 작성자가 불확실성을
    # 직접 표시한 판단이 다른 어떤 값보다 정확하기 때문이다. deprecated는
    # index.json/simple.jsonl 익스포트에서 빠지므로(§4 계약, export.py의
    # status != 'deprecated' 필터) 이게 "비활용"의 실제 메커니즘이다. 여기서
    # 함부로 status만 바꾸고 replace_strategy/risk_level은 그대로 둔다 —
    # 감사 시 "무엇으로 분류됐었는지"가 SQLite(정본)에 남아 있어야 한다.
    if entry.caution and NEEDS_CONFIRMATION_MARKER in entry.caution:
        entry.status = "deprecated"

    # DESIGN.md §5.5.1: DEPRECATED_HOMOGRAPH_TERMS(예: `한`)는 gloss+검수로도
    # 부족하다고 코퍼스 실측으로 확인된 표제어다 — 위 NEEDS_CONFIRMATION_MARKER와
    # 같은 원칙으로 status만 덮어쓰고 replace_strategy/risk_level은 그대로
    # 둔다(감사 흔적 보존). CSV가 status를 명시했더라도 이게 우선한다.
    if term in DEPRECATED_HOMOGRAPH_TERMS:
        entry.status = "deprecated"

    _finalize_examples(entry)

    return entry


def dedupe(entries: list[Entry]) -> tuple[list[Entry], list[str]]:
    """(term_norm, easy_term) 유일화. 같은 term_norm의 다른 easy_term은 유지하되 경고를 남긴다."""
    seen_keys: set[tuple[str, str]] = set()
    seen_easy_by_term: dict[str, set[str]] = {}
    warnings: list[str] = []
    result: list[Entry] = []

    for entry in entries:
        key = (entry.term_norm, entry.easy_term)
        if key in seen_keys:
            warnings.append(
                f"중복 제거: term_norm={entry.term_norm!r} easy_term={entry.easy_term!r} "
                f"(source_ref={entry.source_ref})"
            )
            continue
        seen_keys.add(key)

        alts = seen_easy_by_term.setdefault(entry.term_norm, set())
        if alts and entry.easy_term not in alts:
            warnings.append(
                f"문맥별 대안 유지: term_norm={entry.term_norm!r} "
                f"기존 순화어={sorted(alts)!r} + 신규 순화어={entry.easy_term!r}"
            )
        alts.add(entry.easy_term)

        result.append(entry)

    return result, warnings


def find_unreviewed_substitutions(entries: list[Entry]) -> list[Entry]:
    """안전 불변식 위반 엔트리를 찾는다: **미검수 항목은 원문을 지울 수 없다.**

    지금까지 이 조합(`replace_strategy='substitute'` + `status='review'`)은
    휴리스틱 구조상 원천적으로 나올 수 없었다 — `risk_level='high'`일 때만
    `status='review'`가 되는데, `classify()`의 휴리스틱은 risk=high를 항상
    `gloss`나 `keep`에만 붙였기(§5.2) 때문이다(둘 다 원어를 보존한다). 즉
    "사람이 아직 확인 안 한 판정"은 지금까지 구조적으로 "원어를 지우지 않는
    판정"과 같았다.

    CSV가 replace_strategy/status를 직접 지정할 수 있게 되면서(row_to_entries의
    명시값 처리, §요청 3) 이 보장이 더는 자동으로 성립하지 않는다 — 사람이
    실수로(혹은 의도치 않게) `substitute`+`review`를 같이 써넣으면, 검수
    안 된 순화어가 그대로 원문을 대체해버린다. 자동으로 gloss로 바꾸는 등의
    교정은 하지 않는다 — 원 작성자의 의도를 알 수 없으므로 사람이 고쳐야 한다.
    """
    return [e for e in entries if e.replace_strategy == "substitute" and e.status == "review"]


def create_db(db_path: Path, schema_sql: Path, reset: bool) -> sqlite3.Connection:
    if reset and db_path.exists():
        db_path.unlink()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    if not schema_sql.exists():
        raise FileNotFoundError(
            f"스키마 파일을 찾을 수 없습니다: {schema_sql}. "
            "schema/schema.sql이 먼저 준비되어야 합니다 (작업 A)."
        )
    conn = sqlite3.connect(str(db_path))
    conn.executescript(schema_sql.read_text(encoding="utf-8"))
    return conn


def upsert(conn: sqlite3.Connection, source: Source, entries: list[Entry]) -> dict[str, int]:
    """entries + entry_tags + variants + examples 적재. 트랜잭션 1회."""
    counts = {"entries": 0, "variants": 0, "tags": 0, "examples": 0}
    cur = conn.cursor()

    with conn:
        cur.execute(
            """
            INSERT INTO sources (code, name, organization, license, url, version, collected_at, file_sha256)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                name = excluded.name,
                organization = excluded.organization,
                license = excluded.license,
                url = excluded.url,
                version = excluded.version,
                collected_at = excluded.collected_at,
                file_sha256 = excluded.file_sha256
            """,
            (source.code, source.name, source.organization, source.license,
             source.url, source.version, source.collected_at, source.file_sha256),
        )
        cur.execute("SELECT id FROM sources WHERE code = ?", (source.code,))
        source_id = cur.fetchone()[0]

        for entry in entries:
            cur.execute(
                """
                INSERT INTO entries (
                    term, term_norm, term_hanja, pos, easy_term, definition,
                    replace_strategy, risk_level, caution, readability, confidence,
                    priority, cell_rank, frequency, status, source_id, source_ref, checksum
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(term_norm, easy_term) DO UPDATE SET
                    term = excluded.term,
                    term_hanja = excluded.term_hanja,
                    pos = excluded.pos,
                    definition = excluded.definition,
                    replace_strategy = excluded.replace_strategy,
                    risk_level = excluded.risk_level,
                    caution = excluded.caution,
                    readability = excluded.readability,
                    confidence = excluded.confidence,
                    priority = excluded.priority,
                    cell_rank = excluded.cell_rank,
                    frequency = excluded.frequency,
                    status = excluded.status,
                    source_id = excluded.source_id,
                    source_ref = excluded.source_ref,
                    checksum = excluded.checksum
                """,
                (entry.term, entry.term_norm, entry.term_hanja, entry.pos,
                 entry.easy_term, entry.definition, entry.replace_strategy,
                 entry.risk_level, entry.caution, entry.readability,
                 entry.confidence, entry.priority, entry.cell_rank, entry.frequency,
                 entry.status, source_id, entry.source_ref, entry.checksum()),
            )
            cur.execute(
                "SELECT id FROM entries WHERE term_norm = ? AND easy_term = ?",
                (entry.term_norm, entry.easy_term),
            )
            entry_id = cur.fetchone()[0]
            counts["entries"] += 1

            # 태그는 원천별로 통째로 갈아끼우지 않고 누적 병합한다. 같은 엔트리가
            # 여러 원천 CSV에 중복 등장하면(예: '내방'이 admin/welfare 양쪽에 있음)
            # 나중에 적재된 원천이 이전 원천의 태그를 지워버리는 걸 막기 위해서다
            # — 그러면 --input 순서에 따라 결과가 달라져 빌드가 재현 불가능해진다.
            # 태그 "집합"은 순서와 무관한 합집합으로 모으고, 대표 태그는 그 집합에
            # TAG_PRIORITY 고정 서열을 적용해 결정한다.
            existing_tags = {
                row[0] for row in cur.execute(
                    "SELECT tag_name FROM entry_tags WHERE entry_id = ?", (entry_id,)
                )
            }
            new_tags = {tag for tag in entry.tags if tag in TAG_CATALOG}
            merged_tags = existing_tags | new_tags
            primary = _pick_primary_tag(merged_tags)

            cur.execute("DELETE FROM entry_tags WHERE entry_id = ?", (entry_id,))
            for tag in merged_tags:
                cur.execute(
                    "INSERT OR IGNORE INTO entry_tags (entry_id, tag_name, is_primary) VALUES (?,?,?)",
                    (entry_id, tag, 1 if tag == primary else 0),
                )
            # 리포트에 찍는 "태그 매핑" 카운트는 이번 호출에서 실제로 새로 늘어난
            # 태그 수만 센다. merged_tags 전체를 매번 세면 같은 엔트리가 여러
            # 원천에 걸쳐 재적재될 때 몇 번째로 닿았느냐(=적재 순서)에 따라
            # 누적 합계 자체가 달라져, DB 내용은 동일한데 빌드 리포트 숫자만
            # --input 순서에 따라 달라지는 모순이 생긴다.
            counts["tags"] += len(merged_tags - existing_tags)

            all_variants = list(gen_variants(entry.term, entry.pos or "noun"))
            all_variants.extend(entry.variants)
            cur.execute("DELETE FROM variants WHERE entry_id = ?", (entry_id,))
            # 중복/자기참조 판정을 원문(surface) 기준으로 한다 — 정규화 키
            # 기준이 아니다. normalize_key()는 계약(§6.2)대로 공백·중점·하이픈·
            # 괄호를 지우므로, kind='spacing' 변형형은 정의상 표면형이 term과
            # 다른데도 normalize_key(surface) == term_norm이 항상 성립한다.
            # 게다가 삽입형(공백 없는 5음절 이상 표제어의 공백 삽입 변형형)은
            # 표제어 하나당 여러 개가 나오는데, 삽입 위치만 다르지 공백을
            # 지우면 전부 같은 정규화 키로 수렴한다 — 정규화 키로 판정하면
            # 서로 다른 삽입 위치끼리도 중복으로 걸려 첫 번째만 남고 나머지가
            # 전부 버려진다. lookup.py의 surface_index는 원문 표면형을 키로
            # 쓰므로 이런 변형형이 그대로 사라지는 게 실데이터 결함이었다
            # (core-lib 감사로 발견). DB 쪽도 `variants.UNIQUE(surface,
            # entry_id)`로 원문 기준 유일성으로 바뀌었으니(schema-builder),
            # 여기 판정 기준도 맞춰 원문 기준으로 통일한다.
            seen_surface: set[str] = set()
            for variant in all_variants:
                surface_norm = normalize_key(variant.surface)
                if not surface_norm or variant.surface in seen_surface or variant.surface == entry.term:
                    continue
                seen_surface.add(variant.surface)
                cur.execute(
                    """
                    INSERT OR IGNORE INTO variants (entry_id, surface, surface_norm, kind, is_auto)
                    VALUES (?,?,?,?,?)
                    """,
                    (entry_id, variant.surface, surface_norm, variant.kind, int(variant.is_auto)),
                )
                counts["variants"] += 1

            # 태그와 같은 이유로, 이번 원천에 예문이 없다고 해서 무조건 지우지
            # 않는다. '내방'처럼 여러 원천에 걸쳐 중복 등장하는 엔트리는 예문
            # 있는 원천(admin)과 없는 원천(welfare)이 --input 순서에 따라
            # 어느 쪽이 나중에 오느냐가 갈리는데, 예문 없는 원천이 나중에 오면
            # 먼저 적재된 예문을 조용히 지워버려 결과가 순서에 의존하게 된다.
            # 이번 호출이 기여할 예문이 있을 때만 갈아끼운다.
            if entry.examples:
                cur.execute("DELETE FROM examples WHERE entry_id = ?", (entry_id,))
                for example in entry.examples:
                    cur.execute(
                        """
                        INSERT INTO examples (entry_id, before_text, after_text, note, is_golden)
                        VALUES (?,?,?,?,?)
                        """,
                        (entry_id, example.before_text, example.after_text,
                         example.note, int(example.is_golden)),
                    )
                    counts["examples"] += 1

    return counts


def ingest_examples_only(
    conn: sqlite3.Connection, rows: list[dict], colmap: dict[str, str], start_lineno: int = 2,
) -> dict[str, int]:
    """`--source-role examples` 전용 적재 경로 (DESIGN.md §5.5(7)).

    `upsert()`와 근본적으로 다르다 — **엔트리를 만들지도, 기존 엔트리의 어떤
    필드도 건드리지도 않는다.** 이미 있는 엔트리(`term_norm`+`easy_term`으로
    조회)에 예문만 얹는다. `source_id`를 포함해 `entries` 테이블에 UPDATE를
    전혀 하지 않으므로, 이 원천이 §6.8 원천 신뢰도(source_id로 조회)를
    가로채 승자를 뒤집는 사고가 구조적으로 불가능하다 — §5.5(1)이 태그/
    변형형/예문에 이미 적용한 "먼저 온 것을 안 지운다" 원칙을 엔트리 귀속
    자체로 확장한 것이다.

    조회에 실패한 행(표제어가 사전에 없음)은 §5.5(4) 원칙대로 **조용히
    버리지 않는다** — `_note_discard()`로 사유를 남겨 빌드 리포트에 집계되게
    한다. 예문 텍스트에 `term` 리터럴이 없어 `_finalize_examples()`가 버리는
    경우도 마찬가지다.

    같은 (entry_id, before_text, after_text) 쌍이 이미 있으면 다시 넣지
    않는다(재실행해도 중복이 안 쌓인다 — 이 함수를 여러 번 --input으로
    반복 지정해도 안전하다).
    """
    counts = {"matched": 0, "inserted": 0, "duplicate": 0}
    cur = conn.cursor()
    with conn:
        for lineno, row in enumerate(rows, start=start_lineno):
            term = _cell(row, colmap, "term")
            easy_term = _cell(row, colmap, "easy_term")
            example_text = _cell(row, colmap, "example")
            if not term or not easy_term:
                _note_discard("empty", lineno, term, easy_term)
                continue
            if not example_text:
                _note_discard("examples_no_text", lineno, term, easy_term)
                continue

            term_norm = normalize_key(term)
            cur.execute(
                "SELECT id, term, easy_term, replace_strategy FROM entries "
                "WHERE term_norm = ? AND easy_term = ?",
                (term_norm, easy_term),
            )
            found = cur.fetchone()
            if found is None:
                # 표제어가 사전에 없다 — 엔트리를 만들지 않는다(이 원천의
                # 역할이 아니다). 조용히 버리지 않고 사유를 남긴다.
                _note_discard("examples_no_match", lineno, term, easy_term)
                continue
            entry_id, db_term, db_easy_term, db_strategy = found
            counts["matched"] += 1

            # 판정된 replace_strategy는 DB의 것을 그대로 쓴다 — 이 원천은
            # 분류에 관여하지 않는다(그게 이 함수의 핵심 계약이다).
            temp = Entry(term=db_term, easy_term=db_easy_term, replace_strategy=db_strategy)
            temp.examples.append(Example(before_text=example_text, after_text=example_text))
            _finalize_examples(temp)  # §7.2.2 그대로 — 우회하지 않는다
            if not temp.examples:
                # term이 예문 문장에 문자 그대로 없거나(활용형 등) keep
                # 전략이라 _finalize_examples()가 버린 경우.
                _note_discard("examples_finalize_dropped", lineno, term, easy_term)
                continue

            ex = temp.examples[0]
            cur.execute(
                "SELECT 1 FROM examples WHERE entry_id = ? AND before_text = ? AND after_text = ?",
                (entry_id, ex.before_text, ex.after_text),
            )
            if cur.fetchone() is not None:
                counts["duplicate"] += 1
                continue
            cur.execute(
                "INSERT INTO examples (entry_id, before_text, after_text, note, is_golden) "
                "VALUES (?,?,?,?,?)",
                (entry_id, ex.before_text, ex.after_text, ex.note, int(ex.is_golden)),
            )
            counts["inserted"] += 1

    return counts


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def _now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m easydict.build", description="쉬운 말 사전 빌드 파이프라인")
    parser.add_argument("--input", action="append", required=True, dest="inputs",
                         help="원본 CSV 경로 (여러 번 지정 가능)")
    parser.add_argument("--source-code", action="append", required=True, dest="source_codes")
    parser.add_argument("--source-name", action="append", required=True, dest="source_names")
    parser.add_argument("--organization", action="append", default=[], dest="organizations")
    parser.add_argument("--license", action="append", default=[], dest="licenses")
    parser.add_argument(
        "--default-tag", action="append", default=[], dest="default_tags",
        help=(
            "이 --input 파일에 분야/구분/영역 컬럼이 없거나 값이 해석 안 될 때 "
            "적용할 기본 태그(§3.3 표준 태그명, 예: admin). --input과 짝을 맞춰 지정한다. "
            "지정하지 않으면 category 미해석 엔트리는 hanja/law 등 내용 휴리스틱만 적용되고, "
            "그마저도 없으면 최후 수단인 'jargon'으로 떨어진다 — 순수 (원어,순화어) 2열짜리 "
            "실데이터 CSV(예: 국립국어원 행정용어집)에서는 이 폴백이 대다수를 차지해버려 "
            "태그 축이 사실상 무의미해진다."
        ),
    )
    parser.add_argument(
        "--source-role", action="append", default=[], dest="source_roles",
        choices=("primary", "examples"),
        help=(
            "이 --input의 역할(DESIGN.md §5.5(7)). 'primary'(기본값)는 지금까지의 "
            "동작 그대로 엔트리를 만들고 분류한다. 'examples'는 원어/순화어/예문 "
            "3열만 보고 **이미 있는 엔트리에 예문만 붙인다** — 엔트리를 새로 만들지도, "
            "기존 엔트리의 어떤 필드도(특히 source_id) 건드리지도 않는다. 표제어가 "
            "사전에 없으면 그 행은 버리고 사유를 리포트에 남긴다(조용히 버리지 않는다). "
            "예문 원천(코퍼스 추출 등)이 §6.8 원천 신뢰도를 가로채 승자를 뒤집는 사고를 "
            "구조적으로 막는다 — --input과 짝을 맞춰 지정한다."
        ),
    )
    parser.add_argument("--db", required=True, dest="db_path")
    parser.add_argument("--export", dest="export_dir", default=None)
    parser.add_argument("--reset", action="store_true")
    parser.add_argument("--schema", dest="schema_path",
                         default=str(Path(__file__).resolve().parents[2] / "schema" / "schema.sql"))
    return parser


def _check_per_source_arg_counts(n: int, args: argparse.Namespace) -> list[str]:
    """원천별(`--input`과 짝을 맞추는) 인자들의 개수가 안전한지 확인한다.

    실측된 사고(docs/inspection-plan.md Phase 3 작업 1): `--source-role`을
    **마지막 원천에만** 줬더니 `(list(...) + [기본값] * n)[:n]` 패딩이 그
    값을 **인덱스 0**에 배정해 1,717건짜리 원천(`nikl:admin2018`)이 통째로
    예문 전용으로 처리됐다(엔트리 2,142 -> 449). 원인은 이 인자들이
    `--input`과 위치로 짝을 맞추는데, **일부만 지정해도 조용히 통과**했기
    때문이다 — 개수가 안 맞으면 어디에 배정할지 원리상 알 수 없다.

    그래서 원천별 인자는 **0개(전부 기본값) 또는 정확히 n개**만 허용한다.
    1개 이상 n개 미만은 무조건 오류다 — "일부만 지정"은 의도한 원천이
    아닌 다른 원천에 배정될 위험이 있으므로 조용히 그럴듯한 결과를 내느니
    크게 실패한다(§5.1이 컬럼 별칭 해석 실패에 대해 정한 것과 같은 원칙).

    `--source-code`/`--source-name`은 원래부터 `required=True`라 이미
    `main()` 앞머리에서 n개 일치를 강제하지만, 어느 인자가 몇 개인지
    한 화면에서 같이 보여주려고 여기서도 같이 검사한다. `--organization`/
    `--license`는 원천마다 값이 달라도 되고 아예 안 써도 되는(선택) 값이라
    "0개 또는 n개" 규칙이 그대로 맞는다 — 값 자체를 비우고 싶으면 빈
    문자열(`""`)로 n개를 채우면 된다.

    비어 있지 않은 오류 메시지 줄 목록을 반환한다(비어 있으면 통과).
    """
    specs = (
        ("--source-code", args.source_codes),
        ("--source-name", args.source_names),
        ("--organization", args.organizations),
        ("--license", args.licenses),
        ("--default-tag", args.default_tags),
        ("--source-role", args.source_roles),
    )
    bad = [(flag, len(vals)) for flag, vals in specs if len(vals) not in (0, n)]
    if not bad:
        return []
    lines = [
        f"오류: --input이 {n}개인데 원천별 인자 개수가 안 맞습니다 "
        f"(각 인자는 0개(전부 기본값) 또는 정확히 {n}개여야 합니다):",
    ]
    for flag, vals in specs:
        count = len(vals)
        mark = "" if count in (0, n) else "  ** 불일치 **"
        lines.append(f"  {flag:16s}: {count}개{mark}")
    lines.append(
        "일부만 지정하면 --input과 짝이 어긋나 엉뚱한 원천에 배정됩니다 "
        "— 값이 없는 원천도 빈 문자열(\"\")로 채워 n개를 맞추거나, 아예 생략하세요."
    )
    return lines


def _print_report(report: dict) -> None:
    line = "=" * 64
    print(line)
    print("easy-dictionary 빌드 리포트")
    print(line)
    for item in report["inputs"]:
        print(f"  - {item['path']} ({item['source_code']}): 입력 {item['rows']}행 -> 엔트리 {item['entries']}개")
    print("-" * 64)
    print(f"입력 행수 총합      : {report['total_rows']}")
    print(f"생성 엔트리 총합    : {report['total_entries']}")
    if "actual_entry_count" in report and report["actual_entry_count"] != report["total_entries"]:
        diff = report["total_entries"] - report["actual_entry_count"]
        print(
            f"  ※ DB 실제 유니크 행수: {report['actual_entry_count']} "
            f"(차이 {diff}건 — 다출처가 같은 term_norm+easy_term에 부딪혀 병합됐을 수 있음, 직접 확인 권장)"
        )
    if report.get("discard_total"):
        print(f"폐기 행수 합계      : {report['discard_total']}")
        for label, count in sorted(report["discard_by_reason"].items(), key=lambda kv: -kv[1]):
            print(f"  {label:8s}: {count}건")
            for s in report["discard_samples"].get(label, [])[:5]:
                print(f"      · {s}")
    print(f"변형형 총합         : {report['total_variants']}")
    print(f"태그 매핑 총합      : {report['total_tags']}")
    print(f"예문 총합           : {report['total_examples']}")
    print("-" * 64)
    print("전략별 분포:")
    for k in ("substitute", "gloss", "keep"):
        print(f"  {k:10s}: {report['by_strategy'].get(k, 0)}")
    print("-" * 64)
    print("readability 분포 (1=가장 쉬움 ~ 3=여전히 조금 어려움):")
    for k in (1, 2, 3):
        print(f"  {k}: {report['by_readability'].get(k, 0)}")
    print("-" * 64)
    print("태그별 분포:")
    for tag, count in sorted(report["by_tag"].items(), key=lambda kv: -kv[1]):
        print(f"  {tag:12s}: {count}")
    print("-" * 64)
    print(f"검수 큐(status=review) 개수: {report['review_queue']}")
    print(
        f"분류 판정: 명시값 적용 {report['explicit_strategy_count']}건 / "
        f"휴리스틱 판정 {report['heuristic_strategy_count']}건"
    )
    if report["deprecated_by_marker"]:
        print("-" * 64)
        print(
            f"[확인 필요] 비활용(deprecated) 처리: {len(report['deprecated_by_marker'])}건 "
            "— 법조문 대조 전이라 모든 익스포트에서 제외됨"
        )
        for t in report["deprecated_by_marker"]:
            print(f"  - {t}")
    if report.get("exported") is not None:
        print("-" * 64)
        print("익스포트 파일:")
        for p in report["exported"]:
            print(f"  {p}")
    if report["warnings"]:
        print("-" * 64)
        print(f"경고 ({len(report['warnings'])}건):")
        for w in report["warnings"][:50]:
            print(f"  - {w}")
        if len(report["warnings"]) > 50:
            print(f"  ... 외 {len(report['warnings']) - 50}건")
    print(line)


def main(argv: list[str] | None = None) -> int:
    _discard_log.clear()  # 이전 main() 호출(예: 테스트에서 반복 호출) 잔여물 제거

    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    inputs = args.inputs
    n = len(inputs)
    source_codes = args.source_codes
    source_names = args.source_names

    count_errors = _check_per_source_arg_counts(n, args)
    if count_errors:
        for line in count_errors:
            print(line, file=sys.stderr)
        return 2

    organizations = (list(args.organizations) + [None] * n)[:n]
    licenses = (list(args.licenses) + [None] * n)[:n]
    default_tags = (list(args.default_tags) + [None] * n)[:n]
    # 지정 안 하면 'primary'(지금까지의 기본 동작) — 기존 --input 사용자는
    # 아무것도 안 바뀐다(하위 호환).
    source_roles = (list(args.source_roles) + ["primary"] * n)[:n]
    for tag in default_tags:
        if tag and tag not in TAG_CATALOG:
            print(
                f"오류: --default-tag 값 {tag!r}은(는) 알 수 없는 태그입니다. "
                f"허용값: {sorted(TAG_CATALOG)}",
                file=sys.stderr,
            )
            return 2

    db_path = Path(args.db_path)
    schema_path = Path(args.schema_path)

    try:
        conn = create_db(db_path, schema_path, args.reset)
    except FileNotFoundError as e:
        print(f"오류: {e}", file=sys.stderr)
        return 2

    report: dict = {
        "inputs": [],
        "total_rows": 0,
        "total_entries": 0,
        "total_variants": 0,
        "total_tags": 0,
        "total_examples": 0,
        "by_tag": {},
        "by_strategy": {"substitute": 0, "gloss": 0, "keep": 0},
        "by_readability": {1: 0, 2: 0, 3: 0},
        "review_queue": 0,
        "explicit_strategy_count": 0,
        "heuristic_strategy_count": 0,
        "deprecated_by_marker": [],
        "warnings": [],
        "exported": None,
    }

    for i, input_path_str in enumerate(inputs):
        input_path = Path(input_path_str)
        if not input_path.exists():
            print(f"오류: 입력 파일이 없습니다: {input_path}", file=sys.stderr)
            conn.close()
            return 2

        source = Source(
            code=source_codes[i],
            name=source_names[i],
            organization=organizations[i],
            license=licenses[i],
            collected_at=_now_iso(),
            file_sha256=_sha256_file(input_path),
        )

        try:
            rows, colmap = read_csv_rows(input_path)
        except ValueError as e:
            print(f"오류: {input_path} 처리 중 컬럼 해석 실패: {e}", file=sys.stderr)
            conn.close()
            return 2

        if source_roles[i] == "examples":
            # DESIGN.md §5.5(7): 엔트리를 만들지도 고치지도 않는다. sources
            # 테이블에도 등록하지 않는다 — 이 원천이 소유하는 엔트리가
            # 하나도 없으므로(그게 이 역할의 전체 취지다) 등록해 봐야
            # 아무 entries.source_id도 이 원천을 가리키지 않는다.
            ex_counts = ingest_examples_only(conn, rows, colmap, start_lineno=2)
            report["inputs"].append({
                "path": str(input_path),
                "source_code": source_codes[i] + " (role=examples)",
                "rows": len(rows),
                "entries": 0,
            })
            report["total_rows"] += len(rows)
            report["total_examples"] += ex_counts["inserted"]
            report.setdefault("examples_source_stats", []).append({
                "path": str(input_path),
                "matched": ex_counts["matched"],
                "inserted": ex_counts["inserted"],
                "duplicate": ex_counts["duplicate"],
            })
            continue

        default_tag = default_tags[i]
        entries: list[Entry] = []
        for lineno, row in enumerate(rows, start=2):  # 1행은 헤더
            try:
                new_entries = row_to_entries(row, colmap, source, lineno)
            except ValueError as e:
                # 명시 replace_strategy/risk_level/status가 유효 범위를 벗어난
                # 경우(row_to_entries 참고). CHECK 제약에 걸려 죽는 것보다
                # 여기서 먼저 막는 게 원인을 바로 알 수 있어 낫다.
                print(f"오류: {input_path} 처리 중 값 검증 실패: {e}", file=sys.stderr)
                conn.close()
                return 2
            if default_tag:
                # row_to_entries()의 시그니처는 DESIGN.md §6.3에 고정된 계약이라
                # 여기서 건드리지 않는다. 대신 그 반환값에 한해, CSV 자체가
                # 분야 컬럼을 안 줘서 도메인 태그가 아직 없는 엔트리에만 이
                # --input 단위 기본 태그를 적용한다(실제 분야 컬럼 값이 있었다면
                # 이미 채워져 있으므로 덮어쓰지 않는다). register 태그(hanja/
                # loanword)만 있는 건 "도메인 태그가 있다"로 치지 않는다 —
                # 그것만 보고 건너뛰면 외래어 표기(loanword)만 붙은 행은 영영
                # admin 같은 도메인 태그를 못 받는다.
                for e in new_entries:
                    if not any(t not in _REGISTER_ONLY_TAGS for t in e.tags):
                        e.tags.append(default_tag)
            entries.extend(new_entries)

        entries = [classify(e) for e in entries]

        # 안전 불변식 검사: 미검수(status=review) 항목은 원문을 지울 수 없다.
        # 휴리스틱만 쓰던 동안은 구조적으로 불가능했던 조합인데, CSV 명시값이
        # 이 보장을 깰 수 있어(§요청 3) 여기서 다시 확인하고, 걸리면 자동
        # 교정 없이 빌드를 중단한다 — 작성자 의도를 알 수 없어 사람이 고쳐야 한다.
        violations = find_unreviewed_substitutions(entries)
        if violations:
            print(
                "오류: 미검수(status=review) 항목에 substitute 전략이 지정됐습니다 — "
                "사람이 확인하지 않은 순화어가 원문을 그대로 대체하게 됩니다.",
                file=sys.stderr,
            )
            print(f"위반 위치: {input_path} (안전 불변식: 미검수 항목은 원문을 지울 수 없다)", file=sys.stderr)
            for v in violations:
                print(f"  - {v.source_ref}: term={v.term!r} easy_term={v.easy_term!r}", file=sys.stderr)
            conn.close()
            return 2

        entries, warnings = dedupe(entries)

        counts = upsert(conn, source, entries)

        report["inputs"].append({
            "path": str(input_path),
            "source_code": source.code,
            "rows": len(rows),
            "entries": counts["entries"],
        })
        report["total_rows"] += len(rows)
        report["total_entries"] += counts["entries"]
        report["total_variants"] += counts["variants"]
        report["total_tags"] += counts["tags"]
        report["total_examples"] += counts["examples"]
        report["warnings"].extend(warnings)

        for e in entries:
            report["by_strategy"][e.replace_strategy] = report["by_strategy"].get(e.replace_strategy, 0) + 1
            report["by_readability"][e.readability] = report["by_readability"].get(e.readability, 0) + 1
            if e.status == "review":
                report["review_queue"] += 1
            if e.status == "deprecated" and e.caution and NEEDS_CONFIRMATION_MARKER in e.caution:
                # 조용히 빠지면 안 된다(§지난 45행 사고와 같은 원칙) — 비활용
                # 건수와 표제어를 리포트에 명시한다.
                report["deprecated_by_marker"].append(f"{e.term} (source_ref={e.source_ref})")
            if e.confidence == 1.0:
                report["explicit_strategy_count"] += 1
            else:
                report["heuristic_strategy_count"] += 1
            for tag in e.tags:
                report["by_tag"][tag] = report["by_tag"].get(tag, 0) + 1

    with conn:
        conn.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            ("schema_version", SCHEMA_VERSION),
        )
        conn.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            ("built_at", _now_iso()),
        )

    if args.export_dir:
        try:
            from easydict.export import export_all  # 지연 import: 아직 없을 수 있음
        except ImportError as e:
            report["warnings"].append(f"export 모듈을 찾을 수 없어 익스포트를 건너뜁니다: {e}")
        else:
            try:
                paths = export_all(conn, Path(args.export_dir))
                report["exported"] = [str(p) for p in paths]
            except Exception as e:  # noqa: BLE001 - 빌드 리포트에 경고로만 남기고 계속 진행
                report["warnings"].append(f"익스포트 중 오류 발생: {e}")

    # 생성 엔트리 총합(처리 건수)과 DB 실제 유니크 행수를 대조한다. 다출처
    # CSV가 같은 (term_norm, easy_term)에 여러 번 부딪히면 upsert()가 병합
    # (UPDATE)하므로 실제 행수가 더 적을 수 있다 — 정상일 수도 있지만
    # (예: 다출처 upsert 정합성), 그 판단은 숫자가 보여야 사람이 할 수 있다.
    actual_entry_count = conn.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
    report["actual_entry_count"] = actual_entry_count

    # 폐기 사유 집계 — "조용한 삭제"를 없앤다(실데이터 이관에서 법령명 45행이
    # 자기참조 필터에 걸려 사라졌는데 리포트에 흔적이 전혀 없었던 사고 이후
    # 추가했다). row_to_entries()가 부작용으로 남긴 _discard_log와, dedupe()가
    # 반환한 warnings 중 실제 폐기("중복 제거:")만 골라 합친다 — "문맥별
    # 대안 유지:"는 버리는 게 아니라 유지하는 것이므로 폐기 집계에서 뺀다.
    discard_by_reason: dict[str, int] = {}
    discard_samples: dict[str, list[str]] = {}
    _REASON_LABELS = {
        "self_ref": "자기참조", "empty": "빈 값", "dup": "중복",
        "examples_no_match": "예문 원천-표제어 없음", "examples_no_text": "예문 원천-예문 빈 값",
        "examples_finalize_dropped": "예문 원천-§7.2.2 불합격",
    }
    for d in _discard_log:
        discard_by_reason[d["reason"]] = discard_by_reason.get(d["reason"], 0) + 1
        samples = discard_samples.setdefault(d["reason"], [])
        if len(samples) < 5:
            samples.append(f"{d['line']}행: term={d['term']!r} easy_term={d['easy_term']!r}")

    dup_lines = [w for w in report["warnings"] if w.startswith("중복 제거:")]
    if dup_lines:
        discard_by_reason["dup"] = len(dup_lines)
        discard_samples["dup"] = [w[len("중복 제거: "):] for w in dup_lines[:5]]

    report["discard_by_reason"] = {_REASON_LABELS.get(k, k): v for k, v in discard_by_reason.items()}
    report["discard_samples"] = {_REASON_LABELS.get(k, k): v for k, v in discard_samples.items()}
    report["discard_total"] = sum(discard_by_reason.values())

    conn.close()
    _print_report(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
