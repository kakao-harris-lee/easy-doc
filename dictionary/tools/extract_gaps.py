#!/usr/bin/env python3
"""복지 코퍼스(easy-doc golden documents)에서 사전 미수록 용어 후보를 추출한다.

이 스크립트는 `src/easydict/` 빌드 파이프라인의 일부가 아니다("네트워크 없음,
외부 의존성 없음"이 원칙이지만 이 도구는 코퍼스 파일을 읽는 별도 단계다).
`easydict.normalize`/`easydict.lookup`의 이미 검증된 함수를 가져다 쓸 뿐,
조사·어미·매칭 로직을 새로 짜지 않는다 — `tools/fetch_krdict.py`와 같은
"코드 재사용은 경계 위반이 아니다" 원칙을 따른다(`tools/README.md` 참고).

읽기 전용: `easy-doc` 저장소, `dist/easy_dict.sqlite3`, `src/easydict/`는
전부 읽기만 한다. 산출물은 `--output`으로 지정한 경로에만 쓴다(기본: stdout).

## 왜 다시 짰나 (2차 개선, 팀장 지시)

1차 버전(3,657행)을 실측(krdict 947건 조회)했더니 "검색결과없음" 324건 중
178건(55%)이 진짜 신조어가 아니라 **추출 단계의 결함**이었다:

| 유형 | 건수 | 원인 |
|---|---|---|
| 활용형·조사 잔재 (`있도록`,`위하여`,`받고`) | 143 | 어미 목록이 `~하다/~되다` 파생형만 다뤄 일반 동사 활용을 못 걸렀음 |
| 잘린 조각 (`차상위`,`보장법`,`중등교육법`) | 21 | 후보 추출이 한글 연속 구간(공백·중점에서 끊김)만 보고, 사전에 이미 있는 복합 표제어를 몰랐음 |
| 생산적 접미사 파생형 (`유형별`,`가구당`) | 12 | `-별/-당/-상/-내` 같은 결합형 접미사를 어근과 분리하지 않았음 |
| 숫자+단위 (`억원`,`천만원`) | 2 | 숫자 뒤에 붙은 단위 명사를 걸러내지 않았음 |

**복합어(사전에 넣을 가치 있음) 110건 + 고유명사·제도명 36건**은 추출
문제가 아니라 krdict가 원래 없는 원천 부재 사례라 개선 대상이 아니다(그대로
남아 있는 게 맞다).
"""
from __future__ import annotations

import argparse
import csv
import glob
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "src"))

from easydict.normalize import (  # noqa: E402
    JOSA,
    _DOEDA_SUFFIXES,
    _HADA_SUFFIXES,
    _MIDDLE_DOT_CHARS,
)
from easydict.lookup import EasyDict  # noqa: E402

DEFAULT_GOLDEN_DIR = "/Users/harris/Development/private/easy-doc/data/golden"
DEFAULT_DB_PATH = str(REPO_ROOT / "dist" / "easy_dict.sqlite3")

# ---------------------------------------------------------------------------
# 개선 1: 활용형·조사 잔재 필터 — `_HADA_SUFFIXES`/`_DOEDA_SUFFIXES` 재사용
#
# 이 두 목록은 `gen_variants()`가 "표제어+어미"를 만드는 데 쓰지만, 그
# 어미 부분(선행 '하'/'되' 음절을 뗀 나머지)은 '~하다/~되다'뿐 아니라
# **한국어 동사 전반에 공통된 활용 어미**다(예: '하고'의 '고', '하도록'의
# '도록'은 '받고'/'있도록'에도 그대로 쓰인다). 그래서 이 어미 목록에서
# 선행 '하'/'되'를 떼어 "일반 동사 활용 꼬리표" 목록을 파생시켜 재사용한다
# — 새 어미 사전을 만드는 게 아니라 이미 검증된 걸 다른 관점으로 본다.
# 원본 문자열 자체도(예: '하며','하고' 그대로) 2글자 이상 꼬리표로 같이
# 쓴다 — '하며'처럼 어간이 1글자(있+하며는 성립 안 하지만 '~하며'가
# 붙는 표제어들)인 경우까지 커버하려면 파생형만으로는 부족하다.
def _derive_generic_eomi_tails() -> frozenset[str]:
    tails: set[str] = set()
    for suf in (*_HADA_SUFFIXES, *_DOEDA_SUFFIXES):
        prefix = suf[0]
        if prefix in ("하", "되") and len(suf) > 1:
            tails.add(suf[1:])
    return frozenset(tails)


_DERIVED_EOMI_TAILS = _derive_generic_eomi_tails() | frozenset(_HADA_SUFFIXES) | frozenset(_DOEDA_SUFFIXES)

# '지'는 파생 규칙상 '되지'에서 나오지만(1글자 꼬리표), 이 도메인에서는
# 장소를 뜻하는 진짜 명사 접미사로 훨씬 흔하다('거주지','주소지','소재지',
# '근무지','목적지' 등). `krdict_advanced.csv`(사람이 직접 고른 114개
# 표제어) 회귀 테스트에서 '거주지'/'주소지'가 이 규칙 하나 때문에 잘못
# 걸러지는 것을 발견해 예외로 뺐다 — 1글자 꼬리표라도 실제 충돌 사례가
# 확인되면 개별적으로 blocklist한다(이하 `_EOMI_TAIL_EXCEPTIONS`).
_EOMI_TAIL_EXCEPTIONS: frozenset[str] = frozenset({"지"})
_DERIVED_EOMI_TAILS -= _EOMI_TAIL_EXCEPTIONS

# 파생만으로는 못 잡는 관형사형 어미 조각('나타난'→'난', '남은'→'은',
# '이루어질'→'질', '지난'→'난'). `_HADA_SUFFIXES`/`_DOEDA_SUFFIXES`엔
# '하다/되다'가 이 어미들과 결합한 형태('한','된')가 이미 있지만 fused
# 음절(하+ㄴ='한')이라 문자열 슬라이싱으로 접두 '하'를 뗄 수 없어
# 파생되지 않는다. 여기서만 최소한으로 보강한다 — JOSA의 '은/는/을'과
# 형태는 같지만 여기서는 조사가 아니라 용언 활용 어미로 쓰인 걸 걸러내는
# 것이므로 별도 목록으로 둔다.
_SUPPLEMENTARY_EOMI_TAILS = frozenset({"은", "는", "을", "던", "릴", "난", "질"})

# fused(하+ㄴ다='한다', 되+ㄴ다='된다')라 파생/원본 어느 쪽으로도 안 잡히는
# 흔한 평서형 종결어미. 2음절이라 아래 '1글자 꼬리표만 엄격 가드' 규칙의
# 적용을 받지 않아 안전하게 추가할 수 있다.
_SUPPLEMENTARY_EOMI_TAILS |= {"한다", "된다"}

# `_HADA_SUFFIXES`/`_DOEDA_SUFFIXES`는 애초에 "표제어+하다/되다" 파생형
# 생성용이라 '있습니다'/'바랍니다'/'드립니다'/'없습니다'처럼 하다/되다가
# 아닌 동사에도 똑같이 붙는 **일반 존댓말 종결어미**('-습니다'/'-ㅂ니다')는
# 대상이 아니다. 이건 재사용할 대응 목록이 normalize.py에 따로 없어서
# (거기 목적이 다르다) 직접 보강한다. 전부 2글자 이상이라 안전 가드 조건을
# 그대로 만족한다.
_SUPPLEMENTARY_EOMI_TAILS |= {
    "습니다", "니다", "바랍니다", "드립니다", "습니다만",
}

EOMI_TAILS: frozenset[str] = _DERIVED_EOMI_TAILS | _SUPPLEMENTARY_EOMI_TAILS

# ---------------------------------------------------------------------------
# 개선 3: 생산적 접미사 — 거의 모든 명사 뒤에 결합하는 문법화된 의존명사/
# 접미사. 어근에서 떼어내고 어근을 후보로 삼는다(팀장 지시).
#
# 코퍼스 실측(easy-doc golden documents)으로 확인한 근거:
#   -별 : 유형별, 급여별, 가구원별
#   -당 : 가구당, 1인당
#   -상 : 주민등록상, 서류상
#   -내 : 범위내, 예산내
#   -전 : 개정전, 내방전
#   -후 : 개정후, 상안건선정후
#   -중 : 학기중, 취업중
# 후보에서 제외한 것: '-간'(가입기간·감사대상기간처럼 대부분 '기간'
# 자체가 어근이라 접미사가 아니라 단어의 일부), '-시'(광역시·대도시처럼
# '도시'의 일부로 쓰이는 경우와 '미개설시'처럼 진짜 접미사인 경우가
# 코퍼스에 섞여 있어 오분리 위험이 큼), '-외'(제외·용도외처럼 '외'가
# 이미 완결된 낱말의 일부인 경우와 구분이 안 됨). 어근 최소 길이(2음절)
# 가드가 대부분의 오분리를 막아 주지만(예: '특별'→'별'을 떼면 '특'
# 1음절이라 가드에 막혀 원형 유지), 그래도 애매한 것은 넣지 않았다.
PRODUCTIVE_SUFFIXES: tuple[str, ...] = ("별", "당", "상", "내", "전", "후", "중")

# '-상'(주민등록상=~에 있어서)과 '-내'(범위내=~ 안에)는 실제로는 거의 항상
# 접미사지만, 어간과 결합해 **그 자체로 완결된 명사**를 이루는 경우도
# 흔하다('대상','안내','이상','이내' 등 — 이때 '상'/'내'는 조사성 접미사가
# 아니라 낱말의 마지막 음절일 뿐이다). 실측 중 '지원대상'→'지원대',
# '사업안내'→'사업안'으로 잘못 잘리는 걸 발견해(코퍼스에 실제로 등장) 이
# 두 접미사에 한해 알려진 완결 명사로 끝나면 자르지 않는 차단 목록을 둔다.
# 목록이 코퍼스에서 실제로 확인된 것 위주라 완전하지 않을 수 있음을
# (e)에 밝힌다.
_SUFFIX_BLOCK_ENDINGS: dict[str, frozenset[str]] = {
    "상": frozenset({"대상", "이상", "일상", "통상", "절상", "전상", "공상",
                     "보상", "인상", "현상", "계상", "형상", "선상", "시상"}),
    "내": frozenset({"안내", "이내", "국내", "교내", "실내", "시내", "연내",
                     "기내", "체내", "관내", "구내", "가내"}),
}

# ---------------------------------------------------------------------------
# 개선 4: 숫자+단위 — 숫자 바로 뒤에 붙는 단위/수량 표현은 사전 후보가
# 아니다('34만원', '6개월'). 매칭 시작 위치 바로 앞 글자가 숫자(전각 포함)
# 이면 그 후보 전체를 제외한다.
_DIGIT_RE = re.compile(r"[0-9０-９]")

# ---------------------------------------------------------------------------
# 개선 2: 잘린 조각 방지 — 후보를 자를 때 중점(가운뎃점류)으로 이어진
# 인접 구간도 하나로 묶어 시도한다. `_MIDDLE_DOT_CHARS`(정규화 모듈이
# 이미 관리하는 중점 변이 문자 집합)를 재사용해 '초·중등교육법'처럼
# 아직 사전에 없는 새 중점 복합어도 통째로 후보가 되게 한다.
#
# 공백(스페이스) 결합은 일부러 시도하지 않는다 — '국민기초생활 보장법'
# 처럼 진짜 복합 제도명도 있지만, 문장 대부분이 공백으로 이어진 일반
# 어절 나열이라(예: '신청 방법 안내') 공백 기준으로 인접 어절을 무조건
# 묶으면 진짜 문장 전체가 하나의 "후보"가 되어 잡음이 폭증한다. 대신
# 이미 사전에 있는 공백 포함 복합 표제어는 `EasyDict.find_all()`이
# 그대로 인식하므로(§검증 항목 참고) **사전이 커질수록 이 유형의 잘린
# 조각은 자연히 줄어든다** — 이번 실측에서도 `국민기초생활 보장법`이
# 사전에 들어간 뒤로 `국민기초생활`/`보장법` 조각이 사라진 것을 확인했다.
# 남은 한계는 (e)에 정직하게 적는다.
#
# 안전장치: 중점으로 묶은 결과가 법령·규정류 접미사로 끝날 때만 후보로
# 채택한다. '생계·의료·주거·교육급여'처럼 열거형 중점(각 항목이 별개
# 명사)은 묶으면 '생계의료'처럼 존재하지 않는 말이 되므로 걸러야 한다 —
# 법령명류 접미사 검사가 그 필터 역할을 한다.
_LAW_SUFFIX_RE = re.compile(r"(법|법률|규칙|조례|지침|기준|령)$")

HANGUL_RUN_RE = re.compile(r"[가-힣]{1,}")
_MIDDLE_DOT_JOIN_RE = re.compile(
    r"[가-힣]+[" + re.escape("".join(_MIDDLE_DOT_CHARS)) + r"][가-힣]+"
)

STOPWORDS = {
    "그리고", "그러나", "하지만", "또는", "그러므로", "따라서", "때문에",
    "경우에", "기타사항", "다음과", "위해서", "대해서", "관련하여", "통해서",
    "대한", "의한", "따른", "같은", "모든", "각각", "해당하는", "다만",
    "만약", "만일", "그래서", "그런데", "이런", "저런", "어떤", "무엇",
    "여러", "항상", "반드시", "절대", "결코", "그밖에", "아울러", "또한",
    "이때", "이후", "이전", "당초", "상기", "전항", "별첨", "동상",
    "소정", "당해", "종전", "별도", "상당", "익일", "잔여",
    "신청", "접수", "제출", "방문", "문의", "담당자", "전화번호", "홈페이지",
    "누리집", "서류", "기간", "방법", "대상", "기준", "내용", "결과", "사항",
    "절차", "항목", "종류", "관련", "근거", "안내", "관한", "위한", "통한",
    "위해", "통해", "포함", "따로", "다음", "다른", "본인", "합니다", "이용",
    "사업", "요약", "결정", "지급", "금액", "확인", "여부", "자료", "사용",
    "직접", "제외", "함께", "운영", "세대", "교육", "활동", "기관", "기타",
    "지역", "사유", "미리", "예산", "인당", "주소", "범위", "적용", "매월",
    "실시", "지정", "가정", "비용", "통장", "관계없", "년도", "모두", "향상",
    "명의", "연계", "센터", "필요한", "가능한", "필요", "가능", "사람",
    "여러분", "이상", "이하", "미만", "초과", "구비", "심사", "대상자",
    "지원대상", "지원사업", "안내문", "신청서", "신청기간", "신청방법",
    "제공", "관리", "조사", "법령", "구청", "담당", "문의처", "구비서류",
    "제출서류", "해당하", "받으실", "주시기", "가구원", "가구주",
    "지원", "경우", "서비스", "따라", "있는", "상황", "보장", "이내",
    "에서", "만원", "일까지", "어려운", "최대", "부담", "주십시오",
    "자격", "천원", "백원", "일부터", "일반",
    # krdict 실측(947건 조회)에서 사람이 수동으로 걸러낸 순수 연결어/활용형.
    # '한'/'해'류 1글자 어미로 일반화하면 '기한'/'참고'/'복지' 같은 진짜
    # 후보까지 잘라먹으므로(strip_eomi 문서 참고) 개별 어휘로만 막는다.
    "대해", "이에", "부득이한",
}

_BOUND_NOUN_JOSA_FRAGMENTS = {
    p + j
    for p in ("등", "조", "항", "호", "목", "절", "장", "관", "편")
    for j in ("을", "를", "의", "에", "이", "가", "은", "는", "도", "만")
}
STOPWORDS |= _BOUND_NOUN_JOSA_FRAGMENTS

TAG_KEYWORDS = [
    ("law", ("법률", "법령", "조례", "시행령", "시행규칙", "처분", "이의신청",
              "심사청구", "불복", "벌금", "과태료", "소송", "재판", "고소",
              "고발", "압류", "체납", "환수", "시효", "위임", "대리")),
    ("medical", ("의료", "진료", "병원", "질병", "예방접종", "건강", "진단",
                 "요양", "간호", "약물", "감염", "백신")),
    ("finance", ("소득", "재산", "보험료", "대출", "환급", "세금", "이자",
                 "납부", "금리", "예금", "적금", "채권", "채무")),
    ("welfare", ("급여", "수급", "복지", "지원금", "돌봄", "보장", "차상위",
                 "기초생활", "장애", "한부모", "저소득", "바우처", "긴급지원")),
    ("admin", ("신청", "접수", "발급", "신고", "민원", "주민센터", "읍면동",
               "서류", "통보", "고지", "제출", "등록", "확인서")),
]


def load_docs(golden_dir: str) -> list[dict]:
    files = sorted(glob.glob(f"{golden_dir}/documents/*.json"))
    files += sorted(glob.glob(f"{golden_dir}/*.json"))  # 041 등 최상위 파일
    docs = []
    for fp in files:
        with open(fp, encoding="utf-8") as f:
            d = json.load(f)
        text = d.get("source_text") or ""
        if not text:
            continue
        docs.append({"path": fp, "id": d.get("id", fp), "text": text})
    return docs


def strip_josa(s: str) -> str:
    """말미 조사를 반복적으로 벗겨 어간 후보를 만든다. `JOSA`(easydict.normalize)
    재사용. 최소 2음절은 남긴다(§한계는 원본 로직과 동일)."""
    changed = True
    while changed and len(s) > 2:
        changed = False
        for j in JOSA:
            if len(j) < len(s) and s.endswith(j) and len(s) - len(j) >= 2:
                s = s[: -len(j)]
                changed = True
                break
    return s


def strip_eomi(s: str) -> str:
    """EOMI_TAILS(= `_HADA_SUFFIXES`/`_DOEDA_SUFFIXES` 파생 + 관형사형 보강)로
    끝나면 용언 활용 잔재로 보고 벗긴다. 벗겨졌는지 여부만 호출부가 판단에
    쓴다(벗긴 결과를 후보로 쓰지 않고 통째로 버린다 — 어간만 남아도 의미
    있는 명사인지 확신할 수 없어서다).

    가드를 어미 길이에 따라 다르게 준다:

    - **2글자 이상 꼬리표**('도록','으며','시길','한다'...): 남는 어간이
      1글자(때로는 0글자)여도 통과시킨다. '있도록'(어간 '있' 1글자),
      '한다'(어간 0글자) 같은 고유어 1음절 어간 용언이 매우 흔한데, 이런
      다음절 어미는 실제 한자어 명사 끝음절과 우연히 겹칠 위험이 사실상
      없다(2~6음절 한자어 명사가 '도록'/'으며'로 끝나는 사례는 없다).
    - **1글자 꼬리표**('고','은','지','며'...): 남는 어간이 최소 2글자여야
      한다('받고'→'받'(1글자)는 막힘, 채택 안 함). 1글자 꼬리표는 실제
      2음절 한자어 명사의 끝음절과 자주 겹친다 — '참고'(→'참'), '복지'
      (→'복'), '고지'(→'고')처럼 DESIGN.md가 명시적으로 다루는 진짜
      후보 단어까지 잘라먹을 위험이 크다. 그래서 '받고'/'남은'처럼 고유어
      1음절 어간+1글자 어미인 활용형은 **의도적으로 걸러내지 않는다** —
      재현율보다 정밀도를 택한 판단이며 한계로 (e)에 남긴다.
    """
    for tail in sorted(EOMI_TAILS, key=len, reverse=True):
        if not s.endswith(tail):
            continue
        remainder = len(s) - len(tail)
        min_remainder = 0 if len(tail) >= 2 else 2
        if remainder >= min_remainder:
            return s[: -len(tail)] if remainder > 0 else ""
    return s


def strip_productive_suffix(s: str) -> str:
    """생산적 접미사(-별/-당/-상/-내/-전/-후/-중)를 떼고 어근을 돌려준다.
    못 떼면 원형 그대로 돌려준다. 어근 최소 2음절 가드로 '특별'→'특' 같은
    오분리를 막고, `_SUFFIX_BLOCK_ENDINGS`로 '지원대상'→'지원대',
    '사업안내'→'사업안' 같은 완결 명사 오분리를 막는다."""
    for suf in PRODUCTIVE_SUFFIXES:
        if not s.endswith(suf) or len(s) - len(suf) < 2:
            continue
        blocked = _SUFFIX_BLOCK_ENDINGS.get(suf)
        if blocked and any(s.endswith(b) for b in blocked):
            continue
        return s[: -len(suf)]
    return s


def gen_middle_dot_candidates(text: str) -> list[tuple[int, int]]:
    """중점으로 이어진 인접 한글 구간을 묶어 (start, end) 구간 후보를 만든다.
    법령·규정류 접미사로 끝나는 것만 채택한다(위 안전장치 설명 참고)."""
    spans = []
    for m in _MIDDLE_DOT_JOIN_RE.finditer(text):
        if _LAW_SUFFIX_RE.search(m.group(0)):
            spans.append((m.start(), m.end()))
    return spans


def suggest_tag(term: str, context: str) -> str:
    hay = term + " " + context
    for tag, kws in TAG_KEYWORDS:
        if any(kw in hay for kw in kws):
            return tag
    return ""


def extract_gaps(docs: list[dict], db_path: str) -> tuple[list[dict], dict[str, int]]:
    ed = EasyDict.from_sqlite(db_path)

    agg: dict[str, dict] = defaultdict(lambda: {"docs": set(), "total": 0, "context": None})
    stats = {
        "candidates": 0, "excluded_digit": 0, "excluded_eomi": 0,
        "excluded_len": 0, "excluded_stop": 0, "excluded_covered": 0,
        "middle_dot_candidates": 0,
    }

    for doc in docs:
        text = doc["text"]
        matches = ed.find_all(text)
        covered = [(m.start, m.end) for m in matches]

        raw_spans = [(m.start(), m.end()) for m in HANGUL_RUN_RE.finditer(text) if m.end() - m.start() >= 2]
        dot_spans = gen_middle_dot_candidates(text)
        stats["middle_dot_candidates"] += len(dot_spans)

        for start, end in [*raw_spans, *dot_spans]:
            raw = text[start:end]

            # 개선 4: 숫자+단위 — 후보 시작 바로 앞이 숫자면 제외.
            if start > 0 and _DIGIT_RE.match(text[start - 1]):
                stats["excluded_digit"] += 1
                continue

            stem = strip_josa(raw)
            josa_len = len(raw) - len(stem)

            stem2 = strip_eomi(stem)
            if stem2 != stem:
                stats["excluded_eomi"] += 1
                continue
            if len(stem) >= 3 and stem[-1] in ("하", "되"):
                stats["excluded_eomi"] += 1
                continue

            term = strip_productive_suffix(stem)
            term_end = end - josa_len

            stats["candidates"] += 1

            if not (2 <= len(term) <= 8):  # 중점 복합어를 위해 상한을 6->8로 완화
                stats["excluded_len"] += 1
                continue
            if term in STOPWORDS:
                stats["excluded_stop"] += 1
                continue

            term_start = start
            if any(s <= term_start and term_end <= e for s, e in covered):
                stats["excluded_covered"] += 1
                continue

            ctx_start = max(0, start - 40)
            ctx_end = min(len(text), end + 40)
            context = text[ctx_start:ctx_end].replace("\n", " ").strip()

            entry = agg[term]
            entry["docs"].add(doc["id"])
            entry["total"] += 1
            if entry["context"] is None:
                entry["context"] = context

    rows = []
    for term, info in agg.items():
        rows.append({
            "term": term,
            "doc_freq": len(info["docs"]),
            "total_freq": info["total"],
            "sample_context": info["context"],
            "suggested_tag": suggest_tag(term, info["context"] or ""),
        })
    rows.sort(key=lambda r: (-r["doc_freq"], -r["total_freq"], r["term"]))
    stats["gap_rows"] = len(rows)
    stats["dict_entries"] = len(ed._entries)
    return rows, stats


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--golden-dir", default=DEFAULT_GOLDEN_DIR, help="easy-doc golden 코퍼스 경로(읽기 전용)")
    p.add_argument("--db", default=DEFAULT_DB_PATH, help="dist/easy_dict.sqlite3 경로(읽기 전용)")
    p.add_argument("--output", type=Path, default=None, help="출력 CSV 경로 (기본: stdout)")
    p.add_argument("--top", type=int, default=30, help="요약 출력 상위 N건 (기본 30)")
    args = p.parse_args(argv)

    docs = load_docs(args.golden_dir)
    rows, stats = extract_gaps(docs, args.db)

    if args.output:
        with open(args.output, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["term", "doc_freq", "total_freq", "sample_context", "suggested_tag"])
            w.writeheader()
            w.writerows(rows)
        dest = str(args.output)
    else:
        w = csv.DictWriter(sys.stdout, fieldnames=["term", "doc_freq", "total_freq", "sample_context", "suggested_tag"])
        w.writeheader()
        w.writerows(rows)
        dest = "stdout"

    print(f"[info] docs={len(docs)} dict_entries={stats['dict_entries']}", file=sys.stderr)
    print(f"[info] candidates={stats['candidates']} (중점 결합 후보 {stats['middle_dot_candidates']}건 포함)", file=sys.stderr)
    print(
        f"[info] excluded: digit={stats['excluded_digit']} eomi={stats['excluded_eomi']} "
        f"len={stats['excluded_len']} stopword={stats['excluded_stop']} covered={stats['excluded_covered']}",
        file=sys.stderr,
    )
    print(f"[info] gap_rows={stats['gap_rows']} -> {dest}", file=sys.stderr)

    if args.top > 0:
        print(f"\n=== TOP {args.top} by doc_freq ===", file=sys.stderr)
        for r in rows[: args.top]:
            print(
                f"{r['term']:14s} doc={r['doc_freq']:>3} total={r['total_freq']:>3} "
                f"tag={r['suggested_tag']:8s} {r['sample_context'][:45]}",
                file=sys.stderr,
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
