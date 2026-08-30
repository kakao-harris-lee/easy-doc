"""한국어 텍스트 정규화·변형형 생성 유틸리티.

이 모듈은 `easydict` 사전의 기술적 핵심이다. 원어(어려운 말)는 문서에
표제어 그대로 나오지 않고 조사·어미가 붙은 활용형으로 나오기 때문에,
빌드 타임에 변형형을 미리 생성해 색인하고(§3.4), 조회 시에는 조사를
경계 패턴으로만 처리해 조합 폭발을 막는다(§6.2).

**의존성 없음**: 표준 라이브러리(`re`, `unicodedata`, `hashlib`)만 사용한다.

**순환 import 회피**: 이 모듈은 최상위에서 `models`를 import하지 않는다.
`models.py`가 `normalize.normalize_key`를 최상위에서 import하므로
(models -> normalize 단방향), 이 모듈이 최상위에서 `models`를 다시
import하면 순환 참조가 된다. `gen_variants()`는 반환값으로 실제
`Variant` 인스턴스를 만들어야 하므로 타입 체크만으로는 부족한데,
`from __future__ import annotations` 덕분에 함수 시그니처의
`-> list[Variant]` 는 런타임에 평가되지 않는 문자열이라 문제가 없고,
함수 *본문* 안에서 `from .models import Variant`를 지연 임포트한다.
이 함수가 실제로 호출되는 시점에는 이미 두 모듈이 모두 완전히
로드되어 있으므로(호출자가 `import easydict`를 마친 뒤에 함수를
부르는 정상적인 사용 흐름에서는) 안전하다.
"""
from __future__ import annotations

import re
import unicodedata
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # 타입 체커 전용. 런타임에는 평가되지 않는다.
    from .models import Variant

# ---------------------------------------------------------------------------
# 조사 목록 (§3.4) — 매칭 시 경계 패턴으로만 쓰고 변형형으로 저장하지 않는다.
# 정규식 대안(|) 매칭이 최장일치가 되도록 길이 내림차순으로 정렬한다.
# (예: '이라는'과 '이'가 같이 있을 때 '이'가 먼저 걸려 '라는'을 놓치는 것을 방지)
# ---------------------------------------------------------------------------
_JOSA_RAW: tuple[str, ...] = (
    "은", "는", "이", "가", "을", "를", "에", "에서", "에게", "에겐", "께", "한테",
    "으로", "로", "로서", "으로서", "로써", "으로써", "와", "과", "의", "도", "만",
    "부터", "까지", "이나", "나", "보다", "처럼", "라도", "이라도", "조차", "마저",
    "밖에", "이란", "란", "이라는", "라는", "입니다", "이다", "이며", "이고", "인", "임",
)
JOSA: tuple[str, ...] = tuple(sorted(_JOSA_RAW, key=len, reverse=True))

# 한글 음절 범위 (가~힣). 이 범위의 문자가 뒤에 이어지면 조사 경계 매칭 실패로 본다.
_HANGUL_SYLLABLE_RANGE = r"가-힣"

# 한자 판정 범위 (§3.4, split_hanja 전용).
# - U+4E00–U+9FFF: CJK 통합 한자(기본 다국어 평면). 실데이터 절대다수가 여기 속한다.
# - U+3400–U+4DBF: CJK 확장-A. 이번에 확보한 실데이터(1,075행)에는 등장하지 않았지만
#   드문 인명·지명 한자가 이 블록에 있을 수 있어 방어적으로 포함한다(§근거는
#   split_hanja docstring).
# CJK 호환용 한자(U+F900–FAFF)는 이 범위에 넣지 않는다 — 그 블록은 유니코드 표준상
# **정준(canonical) 분해**를 갖고 있어 nfc()만 거치면 이미 기본 범위로 접힌다.
# 실데이터에 등장한 호환한자 22종을 전수 조사한 결과 전부 U+4E00–U+9FFF로
# 폴딩됨을 확인했다(예: U+F9B4 '領' -> NFC -> U+9818 '領').
_HANJA_CODEPOINT_RANGES: tuple[tuple[int, int], ...] = ((0x4E00, 0x9FFF), (0x3400, 0x4DBF))

_WS_RE = re.compile(r"\s+")
_FULLWIDTH_PAREN = {"（": "(", "）": ")"}
_NORM_STRIP_RE = re.compile(r"[\s·\-~()（）]")
# 말미의 괄호 하나를 분리한다. 안쪽에 괄호가 최대 1단계 중첩된 형태까지 허용한다
# ('가드레일(guardrail(영))'처럼 원어 표기에 언어 태그가 덧붙는 실데이터 패턴 때문).
_ORIGIN_PAREN_RE = re.compile(r"^(.*?)[(（]((?:[^()（）]|[(（][^()（）]*[)）])*)[)）]\s*$")
# 괄호 안에서 중첩된 (...) 조각만 떼어낼 때 쓴다 (언어 태그 '(영)' 판별용).
_INNER_PAREN_RE = re.compile(r"[(（][^()（）]*[)）]")
# '--'/'-'는 사전 표기 관례상 활용어미 자리표시자다('架設--' = 架設 + 하다).
# 한자 순수성 판정 전에 제거한다.
_DASH_RE = re.compile(r"-+")
_MULTI_SPLIT_RE = re.compile(r"\s*(?:,|/|;|·|또는)\s*")
# 원문자 번호(①②③...⑳). 실데이터에서 복수 순화어 나열 구분자로 쓰인다.
_CIRCLED_NUM_RE = re.compile(r"[①-⑳]")


def _is_hanja_char(ch: str) -> bool:
    """`ch` 한 글자가 한자 판정 범위(`_HANJA_CODEPOINT_RANGES`)에 속하는지."""
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in _HANJA_CODEPOINT_RANGES)


def _has_hangul_syllable(s: str) -> bool:
    """`s` 안에 한글 음절(가~힣)이 하나라도 있는지."""
    return any("가" <= ch <= "힣" for ch in s)


def is_pure_hanja(s: str) -> bool:
    """`s`가 (공백 제외) 전부 한자 판정 범위 문자로만 이루어졌는지 확인한다.

    `split_hanja()`가 내부적으로 쓰는 것과 같은 판정을 외부에 공개한 것이다.
    예: `tools/fetch_krdict.py`가 krdict API의 `origin`(원어 표기) 필드가
    순수 한자인지 판별할 때 재사용한다 — `居住地`는 True, 한자와 로마자가
    섞인 `洞住民center`는 False. 순수 한자일 때만 `term_hanja`에 채워야
    스키마 의미가 깨지지 않는다(§split_hanja docstring 4번 항목과 같은 이유).
    NFC 정규화는 호출자가 필요하면 먼저 해야 한다(이 함수는 하지 않는다).

    >>> is_pure_hanja('居住地')
    True
    >>> is_pure_hanja('洞住民center')
    False
    >>> is_pure_hanja('')
    False
    """
    return bool(s) and all(_is_hanja_char(ch) for ch in s if not ch.isspace())


def nfc(s: str) -> str:
    """유니코드 NFC(완성형) 정규화.

    공공데이터 CSV에는 한글 자모가 분리된 NFD 입력이 섞여 들어온다
    (예: 자모 분리 'ㄱㅏ' 형태). NFC로 통일해야 이후 문자열 비교·정규식
    매칭이 일관되게 동작한다.

    >>> nfc(unicodedata.normalize('NFD', '내방'))
    '내방'
    >>> nfc('')
    ''
    """
    if not s:
        return s or ""
    return unicodedata.normalize("NFC", s)


def clean(s: str) -> str:
    """제어문자 제거, 전각괄호→반각, 중복 공백 1칸, strip. None/빈문자 안전.

    >>> clean('  차상위   계층\\t\\n')
    '차상위 계층'
    >>> clean('내방（來訪）')
    '내방(來訪)'
    >>> clean(None)
    ''
    """
    if not s:
        return ""
    s = nfc(s)
    for full, half in _FULLWIDTH_PAREN.items():
        s = s.replace(full, half)
    # 개행/탭 등도 공백으로 취급해 한 칸으로 합친다.
    s = _WS_RE.sub(" ", s)
    # 남은 제어문자(Cc 카테고리) 제거. 공백은 이미 위에서 정리했으므로 여기서
    # 사라지는 것은 \x00-\x08, \x0e-\x1f 등 순수 제어문자뿐이다.
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Cc")
    return s.strip()


def normalize_key(s: str) -> str:
    """정규화 키 생성: NFC + 공백/·/-/~/괄호 제거 + casefold.

    `entries.term_norm` 및 매칭용 표면형 키로 쓰인다. 표기가 달라도
    같은 용어로 취급하기 위한 함수다.

    >>> normalize_key('차상위 계층') == normalize_key('차상위계층')
    True
    >>> normalize_key('내방(來訪)')
    '내방來訪'
    """
    if not s:
        return ""
    s = nfc(s)
    s = _NORM_STRIP_RE.sub("", s)
    return s.casefold()


def split_hanja(s: str) -> tuple[str, str | None]:
    """말미의 원어(어원) 표기 괄호를 분리한다.

    표제어 뒤에 붙는 말미 괄호는 크게 두 갈래로 나뉜다.

    1. **원어 표기**: 한자 병기(`來訪`), 한자+활용어미 자리표시자
       (`架設--` = '架設'+'하다'), 외래어 어원(`guardrail(영)`,
       `←きず(일)`). 문서에는 나타나지 않는 부가정보이므로 표제어에서
       떼어내야 매칭이 된다.
    2. **한글 뜻풀이 괄호**: `과태료(늦게 내는 돈)`처럼 한글로 쓰인
       설명. 이건 표제어의 일부처럼 취급해 절대 떼면 안 된다 — 잘못
       떼면 설명이 통째로 날아간다.

    판정 순서:

    1. 입력을 NFC 정규화한다. 공공데이터 PDF/구형 한글 문서에는 겉보기엔
       똑같은 한자라도 코드포인트가 다른 **CJK 호환용 한자**(예: U+F9B4
       '領')가 섞여 들어온다. 이 블록은 유니코드 정준(canonical) 분해를
       가지므로 NFC만으로 일반 한자(U+9818 '領')로 접힌다.
    2. 말미 괄호를 떼어낸다(안쪽에 괄호가 1단계까지 중첩된 형태도 허용 —
       `guardrail(영)`처럼 원어 표기에 언어 태그가 덧붙는 실데이터 패턴
       때문).
    3. 그 괄호 내용에서 중첩된 `(...)` 조각(언어 태그)만 떼어낸 나머지에
       한글 음절이 남아 있으면 → **한글 뜻풀이**로 보고 분리하지 않는다.
       (`과태료(늦게 내는 돈)`: 중첩 괄호가 없으니 나머지가 그대로
       '늦게 내는 돈'이고, 한글이 있으니 분리 안 함.)
    4. 여기까지 왔으면 원어 표기로 보고 표제어에서 뗀다. 이때 `-`(활용어미
       자리표시자)를 제거한 나머지가 한자 판정 범위(`_HANJA_CODEPOINT_RANGES`)
       로만 이루어졌으면 `term_hanja`로 채우고, 아니면(외래어 어원 등)
       `None`을 반환한다 — 영문·가나 문자열을 `term_hanja`(한자 전용
       컬럼)에 넣으면 스키마 의미가 깨지기 때문이다. 이 경우 원어정보
       자체는 보존하지 않고 버린다: 그 정보를 저장하려면 `models.Entry`에
       새 필드를 추가하고 `build.py` 호출부도 함께 고쳐야 하는데, 이번
       수정 범위는 표제어 오염(매칭 실패)을 고치는 데 한정했다 — 후속
       과제로 남긴다.

    >>> split_hanja('내방(來訪)')
    ('내방', '來訪')
    >>> split_hanja('방문')
    ('방문', None)
    >>> split_hanja('과태료(늦게 내는 돈)')
    ('과태료(늦게 내는 돈)', None)
    >>> split_hanja('가설하다(架設--)')
    ('가설하다', '架設')
    >>> split_hanja('가드레일(guardrail(영))')
    ('가드레일', None)
    """
    if not s:
        return (s or "", None)
    s = nfc(s)
    m = _ORIGIN_PAREN_RE.match(s)
    if not m:
        return (s, None)
    prefix, content = m.group(1), m.group(2)

    # 중첩 괄호(언어 태그)를 떼어낸 나머지에 한글이 남아 있으면 한글 뜻풀이다.
    remainder = _INNER_PAREN_RE.sub("", content)
    if _has_hangul_syllable(remainder):
        return (s, None)

    # 원어 표기로 판정 -> 표제어에서 뗀다. 한자 순수 여부만 별도 판정.
    core = _DASH_RE.sub("", content)
    core = _WS_RE.sub(" ", core).strip()
    return (prefix, core if is_pure_hanja(core) else None)


def split_multi(s: str) -> list[str]:
    """구분자로 나열된 복수 표현을 분리한다.

    구분자: `,` `/` `;` `·` `또는`, 그리고 원문자 번호(`①②③...⑳`).

    **원문자 번호가 있으면 최우선 구분자로 쓴다** — 실데이터(국립국어원
    행정용어 자료)에 `① 다침 ② 상처`처럼 항목을 원문자로 나열한 경우가
    있는데, 이건 나열이라는 신호가 명백해서 다른 휴리스틱을 볼 필요가
    없다.

    **다음으로, 마침표(`.`)로 끝나면 전체를 정의문 한 문장으로 보고
    쪼개지 않는다.** `easydict.build`는 `순화어` 컬럼에 원래 "짧은 대체어
    나열"(`허가증, 영업 허가증, 등록증`)만 들어온다고 가정했는데,
    `tools/fetch_krdict.py`가 한국어기초사전에서 가져온 **정의문**을 같은
    컬럼에 채우게 되면서 문제가 생겼다(B-6). `국가, 정부나 제도, 계획 등을
    세움.`(표제어 `수립`의 정의)을 나열로 오인해 `['국가', '정부나 제도',
    '계획 등을 세움.']`로 쪼개면 `수립 → 국가`라는 틀린 매핑이 만들어진다.
    실데이터로 검증한 결과 이 구분은 아주 깨끗하다: 실제 순화어 나열
    데이터(`nikl_admin_terms_2018.csv` 1,075행 + 기존 `welfare_seed_*.csv`
    66행 + 샘플 CSV 45행, 총 1,186행)는 **단 한 건도** 마침표로 끝나지
    않는 반면, krdict 정의문(`krdict_advanced.csv` 114행)은 **전부**
    마침표로 끝난다 — 한국어 사전 뜻풀이는 항상 문장으로 끝맺는 표기
    관례를 따르고, 순화어 나열은 짧은 단어/구를 나열할 뿐이라 마침표를
    안 찍기 때문으로 보인다. 이 신호 하나로 100%/0% 분리가 됐다는 뜻이라
    다른 휴리스틱보다 먼저, 확정적으로 적용한다. (마침표 하나로 완벽히
    설명되지 않는 새 데이터가 나오면 이 판단을 재검토해야 한다 — 지금은
    관찰된 실데이터 전부가 이 규칙을 지지한다.)

    원문자도 마침표도 없으면 일반 구분자로 후보를 쪼갠 뒤, **나열이냐
    서술문이냐를 "조각별 길이"로 판정한다** — 이전 버전은 "원문 전체 공백 수가 3개
    초과면 서술문"이라는 휴리스틱을 썼는데, 실데이터에서 역효과를 냈다.
    `허가증, 영업 허가증, 등록증, 영업 등록증`처럼 짧은 항목이 **4개**
    나열되면 전체 공백 수는 쉽게 3을 넘지만 명백히 나열이다. 반대로
    조각 하나하나가 몇 어절짜리 짧은 구(句)인지를 보면 나열과 서술문을
    더 잘 구분할 수 있다: 나열 항목은 보통 1~3어절이고, 서술문은 쉼표로
    나뉜 한쪽 절만 해도 그보다 길다. 그래서 "구분자로 쪼갠 조각이 모두
    공백 2개 이하(=3어절 이하)"일 때만 나열로 보고 쪼갠다. 조각 중
    하나라도 더 길면(`주소지를 옮기는 것을 말하며`처럼 공백 3개 이상)
    통째로 서술문이라고 보고 쪼개지 않는다. 완벽한 판별은 불가능하므로
    휴리스틱임을 명시한다.

    >>> split_multi('방문, 찾아옴 / 오다')
    ['방문', '찾아옴', '오다']
    >>> split_multi('주소지를 옮기는 것을 말하며, 전입신고와 함께 처리합니다')
    ['주소지를 옮기는 것을 말하며, 전입신고와 함께 처리합니다']
    >>> split_multi('허가증, 영업 허가증, 등록증, 영업 등록증')
    ['허가증', '영업 허가증', '등록증', '영업 등록증']
    >>> split_multi('① 다침 ② 상처')
    ['다침', '상처']
    >>> split_multi('국가, 정부나 제도, 계획 등을 세움.')
    ['국가, 정부나 제도, 계획 등을 세움.']
    >>> split_multi('임신하기 어려움. 또는 그런 상태.')
    ['임신하기 어려움. 또는 그런 상태.']
    """
    if not s:
        return []
    s = clean(s)
    if not s:
        return []

    if _CIRCLED_NUM_RE.search(s):
        parts = [p.strip() for p in _CIRCLED_NUM_RE.split(s)]
        return [p for p in parts if p]

    if s.endswith("."):
        return [s]

    candidates = [p.strip() for p in _MULTI_SPLIT_RE.split(s) if p.strip()]
    if len(candidates) <= 1:
        return [s]
    if all(p.count(" ") <= 2 for p in candidates):
        return candidates
    return [s]


def guess_pos(term: str) -> str:
    """품사 추정. 접미사 휴리스틱을 우선순위대로 적용한다.

    순서: `~하다/~되다/~시키다/~받다` → verb, `~스럽다/~롭다/~적이다` → adjective,
    `~히/~이/~로` 부사형 → adverb, 공백 포함 다어절 → phrase, 그 외 noun.

    한계: `~이`/`~로`로 끝나는 명사(예: '차이')도 adverb로 오판할 수 있다.
    이는 DESIGN.md §6.2가 명시한 규칙을 그대로 따른 결과이며, 완벽한
    형태소 분석기가 아닌 빠른 휴리스틱임을 감안해야 한다.

    >>> guess_pos('명기하다')
    'verb'
    >>> guess_pos('내방')
    'noun'
    >>> guess_pos('차상위 계층')
    'phrase'
    """
    if not term:
        return "noun"
    t = term.strip()
    if any(t.endswith(suf) for suf in ("하다", "되다", "시키다", "받다")):
        return "verb"
    if any(t.endswith(suf) for suf in ("스럽다", "롭다", "적이다")):
        return "adjective"
    if len(t) > 1 and any(t.endswith(suf) for suf in ("히", "이", "로")):
        return "adverb"
    if " " in t:
        return "phrase"
    return "noun"


_HADA_SUFFIXES: tuple[str, ...] = (
    # 기본 활용형 (DESIGN.md §3.4 원 목록)
    "하여", "해", "한", "하는", "할", "하고", "하면", "하며", "해야",
    "합니다", "했습니다", "하시기", "하십시오", "함", "하지",
    # 존대 조건형. DESIGN.md §3.4 원 목록엔 없었으나 '내방하시면'류 행정문서
    # 존대체 조건문이 매우 흔해 B-3 검증 중 빠진 것을 발견해 추가.
    "하시면",
    # --- B-4: 존대 어미 계열 보강 ---------------------------------------
    # 공공/복지 안내문은 사실상 전부 존대체로 쓰인다('내방하시어', '작성하시고').
    # 아래는 모두 '하다'/'하시기' 등 기존 어간의 단순 접두 부분 문자열이
    # 아니라서(예: 하시는≠하는+시, 하시길≠하시기+ㄹ) josa_pattern() 조사
    # 경계 매칭으로는 커버되지 않는, 그 자체로 다른 표면형이다. 그래서
    # 목록에 직접 추가해야 한다.
    "하시어", "하셔서", "하시고", "하신", "하실", "하시는", "하셔야",
    "하세요", "하십니다", "하시길",
    # 연결/문어체(법령·공문서에 흔한 서술형) 어미.
    "하려면", "하거나", "하도록", "해서", "했다", "하였다", "하여야", "한다",
    # 주의: '하시기를'은 넣지 않는다. '를'은 JOSA 목록에 있는 조사라
    # '하시기' 표면형 + josa_pattern() 경계 매칭만으로 이미 '하시기를'을
    # 잡아낸다 — 별도로 색인하면 순수 중복 비용만 생긴다.
    #
    # 골든 코퍼스 실측(2026-08-29)으로 보강. 목록에 없던 것 중 실제로 쓰이는
    # 것만 넣는다 — '하기' 16회(명사형), '하게' 13회, '하므로' 9회.
    # '하다고'(14회)는 넣지 않는다: 인용형이라 표제어 뒤가 아니라 서술어 뒤에
    # 붙는 자리가 대부분이고("~라고 하다고"), 표제어의 활용형으로 잡으면
    # 엉뚱한 구간을 무는 쪽이 더 크다고 봤다.
    "하기", "하게", "하므로",
)
_DOEDA_SUFFIXES: tuple[str, ...] = (
    "되어", "된", "되는", "될", "되고", "되면", "됩니다", "되었습니다", "됨", "되지",
    # B-4: 존대 어미 계열 보강 (근거는 _HADA_SUFFIXES 주석과 동일)
    "되시면", "되시어", "되신", "되실", "되어야", "되도록", "되거나", "되었다", "된다",
    # 되다-요청(doeda-fix): 과거+연결어미('-었으니'). 행정 문서의 반려·통보
    # 사유 안내문에 전형적으로 나오는 문형이다("서류가 미비하여 반려되었으니
    # 보완 후 제출하십시오"). '되었다'/'되었습니다'처럼 이미 과거시제 어미가
    # 결합된 형태를 개별로 나열하는 이 목록의 관례를 그대로 따른 것으로,
    # 형태소 일반화(과거시제 + 임의 연결어미 조합)를 하지 않는다.
    "되었으니",
    # 골든 코퍼스 실측(2026-08-29)으로 보강. `되다` 활용형 출현 빈도를 세어
    # 목록에 없던 것을 채웠다 — `되며` 14회(3위), `되므로` 3회, `되지는` 2회.
    # 행정 문서는 여러 절을 이어 붙이는 문형("부과되며 징수됩니다")이 흔해
    # 연결어미 계열이 특히 자주 빠진다. 이 목록은 형태소 일반화를 하지 않고
    # 관용형을 개별 나열하는 방식이라, 실데이터에서 확인될 때마다 이렇게
    # 보강한다. 추측으로 미리 채우지 않는다 — 안 쓰이는 형태는 색인만 늘린다.
    "되며", "되므로", "되지는",
)

# 공백 삽입형(역방향 spacing) 생성 최소 길이(음절). gen_variants() docstring의
# "역방향 설계 판단" 참고 — 짧은 표제어에 공백을 끼우면 '내방'->'내 방'처럼
# 실제로 흔히 쓰이는 무관한 구와 충돌해 오탐이 난다.
_MIN_LEN_FOR_SPACE_INSERT = 5

# 중점류(가운뎃점) 문자 변이. 육안으로는 구별이 안 되지만 코드포인트가 전부
# 다르고, 실제 공공문서(예: 법령명 '초·중등교육법')에 뒤섞여 나온다.
# CJK 호환 한자(U+F9B4 vs U+9818)와 달리 NFC로 접히지 않는다 — 이 문자들은
# 서로 독립된 문자라서 유니코드 정규화의 대상이 아니다.
#   · U+00B7 가운뎃점 (law.go.kr 공식 표기)
#   ㆍ U+318D 한글 letter 아래아점(호환용 자모) — 옛 문서·일부 IME에서 자주 씀
#   ･ U+FF65 반각 가운뎃점 — 일본어 환경 영향을 받은 문서에서 나옴
#   ‧ U+2027 hyphenation point
#   ・ U+30FB 가타카나 중점
_MIDDLE_DOT_CHARS: tuple[str, ...] = ("·", "ㆍ", "･", "‧", "・")

# 하이픈류는 중점류와 달리 취급한다: 실데이터(1,136개 고유 표제어)를 전수
# 조사한 결과 하이픈이 든 표제어는 'I-PIN'/'U-city'처럼 표기 자체의 일부인
# 로마자 약어·상표성 명칭 2건뿐이었고, 코드포인트가 다른 여러 "하이픈처럼
# 보이는 문자"가 뒤섞여 나오는 사례는 없었다(중점류처럼 여러 문자가 관용적으로
# 혼용되는 문서 관행이 확인되지 않음). 그래서 en-dash/em-dash 등으로의
# **교체형은 만들지 않는다** — 실제로 나타나지 않을 변형을 늘리는 것은
# 근거 없는 색인 비용이다. DESIGN.md §3.4의 "하이픈 제거형"만 충족하도록
# 제거형만 생성한다.
_HYPHEN_CHARS: tuple[str, ...] = ("-",)

# ---------------------------------------------------------------------------
# 서술성 명사(하다-파생 명사) 판별 (B-3)
#
# '지참'·'내방'·'기재'처럼 명사 표제어라도 '하다'가 바로 붙어 동사로 쓰이는
# 한자어가 매우 많다('지참하시기', '내방하시면' 등이 행정문서에 흔하게 등장).
# 형태소 사전 없이는 완벽한 판별이 불가능하므로 휴리스틱을 쓴다.
#
# 판단 근거:
# 1) 서술성(동작성) 한자어 명사는 압도적으로 **2음절**이다('지참','내방','기재',
#    '명기','통보','개시','산정','책정','신청','접수' 전부 2음절). 4음절 이상은
#    '구비서류'·'수급권자'처럼 그 자체가 완결된 사물/제도 명칭이거나
#    '차상위계층'처럼 복합명사인 경우가 많아 '하다'가 부자연스럽다. 그래서
#    2음절 한자어 명사로만 범위를 좁힌다.
# 2) 존재하지 않는 활용형을 만들어도 실제 문서에 그 문자열이 나타날 일이
#    없으므로 오탐(false positive)은 생기지 않는다 — 비용은 색인 크기뿐이다.
#    따라서 **정밀도보다 재현율 쪽으로 기울어도 안전**하며, 기본값은
#    "2음절 명사면 일단 생성한다"이다.
# 3) 다만 '잔여'(잔여 좌석)·'익일'(익일 배송)·'상기'(상기 내용)처럼 뒤에
#    오는 명사를 꾸미는 관형사적 용법이 주된 뜻이거나 시간/지시성 명사라
#    '하다'와 결합하지 않는 소수의 흔한 예외가 있다. 이런 단어에 대해서까지
#    무의미한 활용형을 늘릴 이유는 없으므로 작은 차단 목록으로 배제한다.
#    이 목록은 완전할 수 없고 새로운 예외가 발견되면 추가하면 된다.
# ---------------------------------------------------------------------------
_NON_PREDICATE_NOUN_BLOCKLIST: frozenset[str] = frozenset(
    {
        "잔여", "익일", "상기", "전항", "해당", "별첨", "동상", "기타",
        "소정", "당해", "종전", "별도", "상당", "이후", "이전", "당초",
    }
)


def _is_predicate_noun(term: str) -> bool:
    """서술성 명사(하다-파생 대상) 여부를 휴리스틱으로 판정한다.

    규칙: 정확히 2음절이고, 차단 목록에 없으면 서술성으로 간주한다.
    (근거는 이 함수 위쪽 모듈 상수 `_NON_PREDICATE_NOUN_BLOCKLIST` 주석 참고)
    """
    return len(term) == 2 and term not in _NON_PREDICATE_NOUN_BLOCKLIST


def gen_variants(term: str, pos: str) -> list[Variant]:
    """활용형/띄어쓰기 변형형을 생성한다 (§3.4).

    - `~하다`: 어간 + 활용 어미 목록. **어근 명사형 자체(`명기하다`→`명기`)는
      2026-08-29부터 생성하지 않는다** — 아래 "어간 억제" 설명 참고.
    - `~되다`: 어간 + 활용 어미 목록
    - 서술성 명사(`pos='noun'`, `_is_predicate_noun` 판정): 명사 자신을 어간
      삼아 `~하다`뿐 아니라 `~되다` 활용 어미도 그대로 붙인다
      (`지참`→`지참하여/지참하시기/...`, `반려`→`반려되어/반려되었으니/...`).
      행정문서에서 '지참하시기 바랍니다', '내방하시면'처럼 명사 표제어에
      바로 '하다'가 붙는 경우가 매우 흔한데, 원래 규칙은 표제어 자체가
      이미 `~하다`로 끝나는 경우만 다뤄서 이런 매칭을 놓쳤다(B-3).
      휴리스틱의 근거는 `_NON_PREDICATE_NOUN_BLOCKLIST` 위 주석 참고.
      **2026-08-29(doeda-fix) 추가**: 능동(`~하다`)만 있고 피동(`~되다`)이
      없었다 — 행정 문서는 처분·통지 문형('반려되었으니', '지급됩니다',
      '적용됩니다', '소급됩니다')에서 서술성 명사를 능동보다 피동으로 훨씬
      자주 쓰는데, 이 사전은 RAG 프롬프트 주입용이라 매칭 실패가 곧 지침
      누락으로 이어진다. 새 상수 없이 `~되다` 동사 분기가 쓰는
      `_DOEDA_SUFFIXES`를 그대로 재사용한다.
    - 공백 포함 표제어(`가스 마스크`): 공백 제거형(`가스마스크`)을
      `kind='spacing'`으로 추가.
    - **공백 없는 긴 표제어**(`국민기초생활보장법`, 길이 `_MIN_LEN_FOR_SPACE_INSERT`
      음절 이상): 공백을 한 칸씩 끼워 넣은 모든 삽입형(`국 민기초생활보장법`,
      `국민 기초생활보장법`, ...)을 `kind='spacing'`으로 추가. 아래 "역방향
      설계 판단" 참고.
    - **중점류**(`·`/`ㆍ`/`･`/`‧`/`・`, `_MIDDLE_DOT_CHARS`)가 포함된 표제어
      (`초·중등교육법`): 제거형(`초중등교육법`) + 다른 중점 문자로 바꾼
      치환형(`초ㆍ중등교육법`, `초･중등교육법`, ...)을 전부 `kind='spacing'`으로
      추가한다. 이 문자들은 육안으로 구별이 안 되지만 코드포인트가 달라서
      NFC로도 접히지 않고(호환 한자와 다른 점 — 아래 참고), 실제 공공문서에
      뒤섞여 나온다.
    - **하이픈**(`-`)이 포함된 표제어: 제거형만 추가한다(치환형은 만들지
      않음 — 근거는 `_HYPHEN_CHARS` 위 주석).
    - 원형(term 자신)은 결과에 포함하지 않는다 (entries.term이 이미 가짐).
    - 중복은 제거하고, 모든 변형형은 `is_auto=True`로 표시한다.

    `pos` 인자는 현재 어미 규칙을 어떤 활용 계열로 적용할지 힌트로만
    쓰고, `~하다`/`~되다` 분기는 `term`의 접미사로 직접 판단한다
    (`guess_pos`가 이미 이 접미사로 verb를 판정하므로 결과는 동일하다).
    서술성 명사 분기만 `pos == 'noun'`을 명시적으로 요구한다 —
    `phrase`(다어절 표제어)에는 적용하지 않는다.

    **어간 억제 설계 판단(2026-08-29, DESIGN.md §5.5.1)**: `~하다` 동사
    표제어의 어근 명사형(`날인하다`→`날인`)을 예전엔 변형형으로 생성했다.
    그런데 이 어간이 흔한 한글 열거 기호(`가./나./다.`)나 전혀 다른 뜻의
    명사(`부상`(負傷·다침) vs `부상하다`(浮上·떠오름))와 우연히 겹치면,
    `_longest_match_at()`가 무조건 이 동사 엔트리로 삼켜 버린다 — 골든
    코퍼스 실측(`가하다`): 어간 `가`가 매칭된 40건 **전부**가 加/可 동형어
    문제가 아니라 열거 기호였고, 진짜 加/可 용법은 0건이었다. 이제 `~하다`
    분기에서 어간 자체는 만들지 않는다(어미가 붙은 활용형은 그대로 만든다
    — `명기하여`는 여전히 매칭된다). **반대 방향은 그대로 둔다**: §3.5의
    서술성 명사 하다-파생(`지참`→`지참하시기`)은 명사 표제어가 자기
    활용형을 갖는 정상 동작이라 금지 대상이 아니다 — 금지하는 건 "동사
    표제어가 명사 자리를 차지하는 것"뿐이다. 대가는 있다: `날인하다`처럼
    어간 자체가 실제로 쓰일 만한 정당한 경우도 함께 잃는다(실측 98건).
    그래도 이쪽이 맞다 — 원문을 잘못 바꾸는 게 못 잡는 것보다 나쁘다.

    **역방향(공백 삽입) 설계 판단**: 문서에는 표제어에 없는 공백이 들어간
    형태로 나오는 경우가 있다(`국민기초생활보장법`(표제어, 붙여씀) vs
    `국민기초생활 보장법`(법령 공식 표기, 「」 안에 흔히 이 형태로 나옴)).
    세 방향을 검토했다.
    (A) 빌드 타임에 공백 삽입형을 전개(이 구현이 택한 방향).
    (B) 조회 엔진(`lookup.py`)에서 공백을 유연하게 처리 — 다른 담당 파일이라
        이번 범위에서 즉시 처리할 수 없고 조율이 필요하다.
    (C) 데이터 쪽에서 원천 CSV의 표제어를 공식 띄어쓰기로 고쳐 적는다 —
        엔진 변경이 필요 없고 가장 싸지만, ①이번에 확보한 실데이터의
        일부 행이 이미 붙여쓰기로 들어와 있어 즉시 통하지 않고(CSV 재정비가
        먼저 필요), ②앞으로 들어올 새 원천 CSV까지 전부 보장할 수는 없다.
    (A)를 택했다: 이 파일(`normalize.py`) 안에서 끝나고, `lookup.py`/CSV
    조율 없이 즉시 재현율을 고친다. **단, 아무 길이에나 적용하면 위험하다**
    — `내방`(來訪)에 공백을 끼우면 `내 방`("나의 방")이라는, 실제 문서에
    아주 흔하게 나오는 멀쩡한 구가 되어버려 대량 오탐을 만든다. 이 위험은
    표제어가 짧을수록 커진다(2음절 표제어에 공백을 넣으면 결정사+1음절
    명사라는, 자연어에서 가장 흔한 구 패턴과 정확히 겹친다). 그래서 길이
    하한을 뒀다: `_MIN_LEN_FOR_SPACE_INSERT`(5음절) 미만에는 적용하지
    않는다. 실데이터(1,141행) 기준 이 하한에서 대상 58건, 삽입형 302개로
    비용도 작다. 어느 지점에 공백이 들어가는 게 맞는지는 사전 없이
    판별할 수 없으므로(형태소 분석기가 없다) 모든 위치에 한 칸씩
    끼워보는 전수 생성으로 우회한다 — 진짜 위치가 아닌 나머지는 실제
    문서에 그대로 나타날 일이 거의 없는 무의미한 문자열이라 오탐
    비용은 낮다(B-3/B-4와 같은 논리).

    **중점 문자 변이**: CJK 호환 한자(U+F9B4 vs U+9818)는 `nfc()` 한 번으로
    같은 문자로 접혔지만(§결함1), 중점류 5종은 유니코드 정준 동치가 아닌
    **서로 다른 문자**라서 NFC로 절대 접히지 않는다. 그래서 여기서는
    "정규화"가 아니라 "그럴듯한 대안을 전부 색인해 두는" 접근을 쓴다 —
    공백 역방향 삽입과 같은 논리다. 실데이터(고유 표제어 1,136개)를
    전수 조사한 결과 중점류가 든 표제어는 `초·중등교육법` 1건뿐이라
    조합 폭발 위험이 없다(치환형 4개 + 제거형 1개 = 5개 추가).

    >>> [v.surface for v in gen_variants('명기하다', 'verb')][:4]
    ['명기하여', '명기해', '명기한', '명기하는']
    >>> '명기' in [v.surface for v in gen_variants('명기하다', 'verb')]
    False
    >>> '지참하시기' in [v.surface for v in gen_variants('지참', 'noun')]
    True
    >>> '반려되었으니' in [v.surface for v in gen_variants('반려', 'noun')]
    True
    >>> gen_variants('구비서류', 'noun')
    []
    >>> '가스마스크' in [v.surface for v in gen_variants('가스 마스크', 'noun')]
    True
    >>> '국민기초생활 보장법' in [v.surface for v in gen_variants('국민기초생활보장법', 'noun')]
    True
    >>> '내 방' in [v.surface for v in gen_variants('내방', 'noun')]
    False
    >>> surfaces = [v.surface for v in gen_variants('초·중등교육법', 'noun')]
    >>> '초중등교육법' in surfaces and '초ㆍ중등교육법' in surfaces and '초･중등교육법' in surfaces
    True
    >>> 'IPIN' in [v.surface for v in gen_variants('I-PIN', 'noun')]
    True
    """
    from .models import Variant  # 지연 임포트 (모듈 상단 docstring 참고)

    variants: list[Variant] = []
    seen: set[str] = {term}  # 원형은 결과에서 제외

    if term.endswith("하다") and len(term) > 2:
        stem = term[:-2]
        for suf in _HADA_SUFFIXES:
            surface = stem + suf
            if surface not in seen:
                variants.append(Variant(surface=surface, kind="conjugation", is_auto=True))
                seen.add(surface)
        # 어근 명사형 자체(`stem`)는 **더 이상 변형형으로 생성하지 않는다**
        # (2026-08-29, DESIGN.md §5.5.1 "같은 위험이 변형형에도 있다").
        #
        # 실측: 이 변형형이 있으면 `_longest_match_at()`가 어간과 우연히
        # 같은 문자열(흔한 열거 기호 '가.'/'나.', 또는 명사 '부상'·'배치'·
        # '도래'·'구제' 등)을 만날 때마다 그 동사 엔트리로 삼켜 버린다.
        # `가하다`(어간 '가')는 골든 코퍼스 실측 40건 전부가 加/可 동형어
        # 문제가 아니라 한글 열거 기호 '가.'와의 충돌이었고, `부상하다`
        # (어간 '부상')는 실제로는 負傷(다침) 뜻으로 쓰인 문장(浮上 뜻은
        # 0건)까지 전부 삼켰다 — §6.8 "정확 일치 우선 규칙"이 같은 표면형에
        # 그 명사를 자기 표제어로 둔 별도 엔트리가 있을 때만 구제해 주므로,
        # 그런 엔트리가 없는(예: '부상'이라는 명사 엔트리가 사전에 없는)
        # 다수의 경우엔 무방비였다.
        #
        # 반대 방향(§3.5 서술성 명사 하다-파생, 예: `지참`->`지참하시기`)은
        # 그대로 둔다 — 그건 명사 표제어가 자기 활용형을 갖는 정상 동작이고,
        # 여기서 금지하는 건 "동사 표제어가 명사 자리를 차지하는 것"뿐이다.
        #
        # 트레이드오프: `날인하다`(捺印)처럼 어간 자체가 실제로 쓰일 만한
        # 정당한 경우도 함께 잃는다(실측 98건, tools/ 분석 참고). 그래도
        # 이쪽이 맞다 — 원문을 잘못 바꾸는 것이 못 잡는 것보다 나쁘다.
        # 어간이 실제로 필요하면 별도 표제어로 등록하면 된다.
    elif term.endswith("되다") and len(term) > 2:
        stem = term[:-2]
        for suf in _DOEDA_SUFFIXES:
            surface = stem + suf
            if surface not in seen:
                variants.append(Variant(surface=surface, kind="conjugation", is_auto=True))
                seen.add(surface)
    elif pos == "noun" and _is_predicate_noun(term):
        # 서술성 명사: 명사 자신이 어간이다 (지참 -> 지참하여/지참하시기/...).
        for suf in _HADA_SUFFIXES:
            surface = term + suf
            if surface not in seen:
                variants.append(Variant(surface=surface, kind="conjugation", is_auto=True))
                seen.add(surface)
        # doeda-fix: 능동(~하다)뿐 아니라 피동(~되다)도 붙인다. 행정 문서는
        # 서술성 명사를 능동보다 피동으로 훨씬 자주 쓴다 — '반려되었으니',
        # '지급됩니다', '적용됩니다', '소급됩니다'처럼 처분·통지 문형은
        # 거의 전부 피동이다. 이 사전은 RAG 프롬프트 주입용이라 매칭에
        # 실패하면 해당 지침이 LLM에 아예 전달되지 않으므로(team-lead
        # 지시 근거), 능동만 만들고 피동을 빠뜨리면 이런 문서에서 체계적으로
        # 놓친다. 새 상수를 만들지 않고 위 `term.endswith('되다')` 분기가
        # 쓰는 `_DOEDA_SUFFIXES`를 그대로 재사용한다 — 서술성 명사가 자기
        # 활용형(능동/피동 모두)을 갖는 것은 §5.5.1 "어간 억제"가 금지하는
        # "동사 표제어가 명사 자리를 차지하는 것"과 무관한, 반대 방향의
        # 정상 동작이다(§5.5.1 문단 참고).
        for suf in _DOEDA_SUFFIXES:
            surface = term + suf
            if surface not in seen:
                variants.append(Variant(surface=surface, kind="conjugation", is_auto=True))
                seen.add(surface)

    if " " in term:
        spacing_surface = term.replace(" ", "")
        if spacing_surface and spacing_surface not in seen:
            variants.append(Variant(surface=spacing_surface, kind="spacing", is_auto=True))
            seen.add(spacing_surface)
    elif len(term) >= _MIN_LEN_FOR_SPACE_INSERT and not any(
        ch in term for ch in (*_MIDDLE_DOT_CHARS, *_HYPHEN_CHARS)
    ):
        # 역방향: 표제어는 붙여썼지만 문서엔 공식 띄어쓰기로 나오는 경우
        # ('국민기초생활보장법' -> '국민기초생활 보장법'). 위 docstring의
        # "역방향 설계 판단" 참고. 중점/하이픈이 이미 있는 표제어는 그
        # 문자 자체가 의미상 경계 표시라 공백까지 전수 삽입하면 아래
        # 중점/하이픈 블록과 조합 폭발만 늘고 얻는 게 없어 제외한다.
        for i in range(1, len(term)):
            spaced = term[:i] + " " + term[i:]
            if spaced not in seen:
                variants.append(Variant(surface=spaced, kind="spacing", is_auto=True))
                seen.add(spaced)

    # 중점류: 제거형 + 다른 중점 문자로 바꾼 치환형. 공백 유무와 무관하게
    # 독립적으로 검사한다(위 if/elif와 별개 블록).
    present_dots = [ch for ch in _MIDDLE_DOT_CHARS if ch in term]
    if present_dots:
        removed = term
        for ch in present_dots:
            removed = removed.replace(ch, "")
        if removed and removed not in seen:
            variants.append(Variant(surface=removed, kind="spacing", is_auto=True))
            seen.add(removed)
        for alt in _MIDDLE_DOT_CHARS:
            swapped = term
            for ch in present_dots:
                swapped = swapped.replace(ch, alt)
            if swapped != term and swapped not in seen:
                variants.append(Variant(surface=swapped, kind="spacing", is_auto=True))
                seen.add(swapped)

    # 하이픈: 제거형만 (치환형은 만들지 않는 이유는 _HYPHEN_CHARS 주석 참고).
    if any(ch in term for ch in _HYPHEN_CHARS):
        removed = term
        for ch in _HYPHEN_CHARS:
            removed = removed.replace(ch, "")
        if removed and removed not in seen:
            variants.append(Variant(surface=removed, kind="spacing", is_auto=True))
            seen.add(removed)

    return variants


def josa_pattern() -> str:
    """조사 경계용 정규식 조각을 반환한다.

    표제어/변형형 표면형 뒤에 lookahead로 붙여서 오른쪽 경계를 검사하는
    데 쓴다. 이 함수 자체는 `(?=...)`로 감싸지 않은 "조각"만 반환하므로,
    호출부에서 `'(?=' + josa_pattern() + ')'` 형태로 감싸서 쓴다.

    판정 형태: `표제어 + (조사)* + 어절경계`.

    1. 조사 목록(길이 내림차순)이 **0개 이상 연쇄**로 이어질 수 있다 —
       `에서는`(`에서`+`는`), `으로는`(`으로`+`는`), `까지도`(`까지`+`도`)처럼
       조사가 겹쳐 붙는 경우를 위해서다. 조사가 하나도 없어도(빈 반복)
       통과할 수 있다 — 표제어가 조사 없이 그대로 끝나는 경우.
    2. 그 조사 연쇄 바로 뒤가 한글 음절(가~힣)이 *아니어야* 한다 (부정
       전방탐색) — 문장부호, 공백, 숫자, 영문, 한자, 문자열 끝을 모두
       포괄한다.

    **버그 수정 이력**: 예전 구현은 "조사 목록에 있는 한 음절이 뒤따르는가"
    만 보고 그 뒤는 확인하지 않았다. 그래서 `급여과장`처럼 조사와 형태가
    같은 음절(`과`)로 시작하는 복합어가 `급여` 뒤에서 그냥 뚫렸다
    (`과` 자체는 JOSA 목록에 있는 조사라 통과해버림). 지금 구현은 조사
    연쇄를 다 소비한 뒤 그 다음이 진짜 어절 경계인지까지 확인하므로,
    `과` 뒤에 `장`(조사 아닌 한글 음절)이 이어지면 정규식 엔진이 조사
    0개 소비로 되돌아가 시도하고, 그마저도 `과`가 한글이라 실패한다 —
    결과적으로 매칭 자체가 실패한다.

    **남는 근본적 모호성**: `급여과 협력`처럼 조사 뒤에 공백이 오면
    이 함수는 통과시킨다 — `급여`+조사`과`+공백(어절경계)이라는 조건을
    문법적으로는 만족하기 때문이다. 하지만 실제로는 `급여과`(부서명)일
    수도 있다. 형태소·문맥 정보 없이는 이 모호성을 해소할 수 없다.
    이런 케이스가 실제로 문제가 되면 형태소 분석기 도입을 검토해야
    한다 — 지금은 "조사 뒤에 한글이 그냥 이어지는" 명백한 오탐만 막는다.

    >>> import re
    >>> pat = re.compile(re.escape('내방') + '(?=' + josa_pattern() + ')')
    >>> bool(pat.match('내방을'))
    True
    >>> bool(pat.match('내방객'))
    False
    >>> bool(pat.match('내방'))
    True
    >>> pat2 = re.compile(re.escape('급여') + '(?=' + josa_pattern() + ')')
    >>> bool(pat2.match('급여는'))
    True
    >>> bool(pat2.match('급여에서는'))
    True
    >>> bool(pat2.match('급여과장'))
    False
    """
    josa_alt = "|".join(re.escape(j) for j in JOSA)
    return rf"(?:{josa_alt})*(?![{_HANGUL_SYLLABLE_RANGE}])"


# ---------------------------------------------------------------------------
# 조사 이형태 교정 (D-5: lookup.py에만 있던 로직을 공유 유틸로 승격).
#
# 원어를 쉬운 말로 바꿔치기하면 받침 유무가 바뀔 수 있다("급여"(받침없음)
# +"는" -> "지원금"(받침있음)인데 뒤에는 원래 "는"이 남아 "지원금는"이라는
# 비문이 된다). 이 절은 그 조사를 새 단어의 받침에 맞춰 다시 고르는 데
# 쓰는 부품들이다.
#
# `lookup.EasyDict.annotate()`가 원래 주인이었지만(§6.5), `build.py`가
# 예문(before/after)을 합성할 때 똑같은 문제(단순 `.replace()`로 조사
# 호응이 깨짐 — "필증"→"증명서" 치환 후 "필증을"이 "증명서을"로 남는 등)를
# 독립적으로 재발시켰다. 두 모듈이 같은 버그를 따로 낼 표면을 만들지
# 않으려면 한 곳(normalize.py — 이미 `JOSA`/`josa_pattern()`이 있는 곳)에
# 모아야 한다는 게 원래 취지였다. `lookup.py`는 이 절의 함수들을 그대로
# import해서 쓰고, 자체 구현을 두지 않는다.
# ---------------------------------------------------------------------------

# 각 쌍은 (받침 있음, 받침 없음) 순서. ("으로", "로")만 예외: ㄹ 받침이면
# 받침이 있어도 "로"를 쓴다(`correct_josa_form` 참고).
JOSA_PAIRS: tuple[tuple[str, str], ...] = (
    ("은", "는"),
    ("이", "가"),
    ("을", "를"),
    ("과", "와"),
    ("으로", "로"),
    ("이나", "나"),
    ("이란", "란"),
    ("이라는", "라는"),
    ("이며", "며"),
    ("이랑", "랑"),
    ("아", "야"),
    ("이여", "여"),
)

# 텍스트 위에서 조사 후보를 탐지할 때 쓰는 (표면형, 소속 쌍) 목록.
# 길이 내림차순으로 정렬해 짧은 후보('이')가 긴 후보('이라는')보다 먼저
# 걸려 오탐하는 것을 막는다(§3.4 JOSA 정렬과 같은 이유).
_JOSA_PAIR_CANDIDATES: tuple[tuple[str, tuple[str, str]], ...] = tuple(
    sorted(
        ((form, pair) for pair in JOSA_PAIRS for form in pair),
        key=lambda item: len(item[0]),
        reverse=True,
    )
)

# find_josa_after()가 조사 뒤 어절 경계를 확인할 때 쓰는 lookahead.
# josa_pattern()과 같은 조각으로 만들되, 여기서는 텍스트를 자르지 않고
# pattern.match(text, pos)로 그 위치만 검사하는 용도라 별도로 컴파일해 둔다.
_JOSA_BOUNDARY_RE = re.compile("(?=" + josa_pattern() + ")")


def has_batchim(ch: str) -> bool:
    """한글 음절 `ch`에 받침이 있는지 판정한다. 한글 음절이 아니면 False.

    >>> has_batchim('금')
    True
    >>> has_batchim('여')
    False
    """
    if not ch:
        return False
    code = ord(ch)
    if not (0xAC00 <= code <= 0xD7A3):
        return False
    return (code - 0xAC00) % 28 != 0


def is_rieul_batchim(ch: str) -> bool:
    """받침이 정확히 'ㄹ'인지 판정한다(종성 인덱스 8).

    >>> is_rieul_batchim('울')
    True
    >>> is_rieul_batchim('금')
    False
    """
    if not ch:
        return False
    code = ord(ch)
    if not (0xAC00 <= code <= 0xD7A3):
        return False
    return (code - 0xAC00) % 28 == 8


def correct_josa_form(pair: tuple[str, str], last_char: str) -> str | None:
    """새 단어의 마지막 글자를 보고 `pair` 중 맞는 조사 형태를 고른다.

    `last_char`가 한글 음절이 아니면(숫자·영문·기호로 끝나는 easy_term)
    받침 판정 자체가 무의미하므로 `None`을 반환해 교정을 포기한다.

    ("으로", "로") 쌍은 예외: 받침이 있어도 그 받침이 'ㄹ'이면 "로"를 쓴다
    ('서울로', '물로').

    >>> correct_josa_form(('은', '는'), '금')
    '은'
    >>> correct_josa_form(('으로', '로'), '울')
    '로'
    >>> correct_josa_form(('은', '는'), '1')
    """
    if not last_char:
        return None
    code = ord(last_char)
    if not (0xAC00 <= code <= 0xD7A3):
        return None
    with_batchim, without_batchim = pair
    if pair == ("으로", "로"):
        if has_batchim(last_char) and not is_rieul_batchim(last_char):
            return with_batchim
        return without_batchim
    return with_batchim if has_batchim(last_char) else without_batchim


def find_josa_after(text: str, pos: int, limit: int | None = None) -> tuple[str, tuple[str, str]] | None:
    """`pos` 위치에서 시작하는 이형태 조사(`JOSA_PAIRS`)를 찾는다.

    호출부(주로 치환 로직)가 "이 자리에 조사가 있는가, 있다면 어느 쌍인가"를
    알고 싶을 때 쓴다. 두 가지 방어책으로 오탐(조사가 아닌 걸 조사로 착각)을
    막는다:

    1. `limit`(보통 다음 매칭의 시작 위치, 생략하면 문자열 끝)을 넘어서는
       후보는 보지 않는다 — 다른 매칭 구간을 침범하지 않는다.
    2. 후보 뒤가 실제로 어절 경계인지 확인한다(`josa_pattern()`과 같은
       규칙: 공백/문장부호/문자열끝이거나 다른 조사가 이어지는 경우).
       이걸로 `급여과장`의 `과`(과/와 쌍의 후보)가 뒤에 `장`(한글 음절,
       조사 아님)이 이어지므로 조사로 오인되지 않는다.

    `_JOSA_PAIR_CANDIDATES`는 길이 내림차순이라 첫 매치가 곧 올바른 후보다.

    >>> find_josa_after('지원금은 많다', 3)
    ('은', ('은', '는'))
    >>> find_josa_after('급여과장에게', 2) is None
    True
    """
    if limit is None:
        limit = len(text)
    for form, pair in _JOSA_PAIR_CANDIDATES:
        end = pos + len(form)
        if end > limit:
            continue
        if text[pos:end] != form:
            continue
        if _JOSA_BOUNDARY_RE.match(text, end) is None:
            continue
        return form, pair
    return None


def substitute_with_josa(text: str, term: str, easy_term: str) -> str:
    """`text` 안의 모든 `term`을 `easy_term`으로 바꾸고, 뒤따르는 조사를
    `easy_term`의 받침에 맞춰 교정한다.

    `EasyDict.find_all()`처럼 사전 트라이·최장일치·조사 경계로 "이 문서에
    어떤 사전 용어가 있는지"부터 찾는 함수가 아니다 — **"이 문장에 `term`이
    있다는 걸 이미 알고 있고, 그 자리를 `easy_term`으로 바꾸고 싶다"**는
    좁은 용도의 단순 부분 문자열 치환이다. `build.py`가 CSV의 `before`
    예문에서 `after` 예문을 합성할 때 쓰도록 만들었다(§5, 예문은 LLM에게
    주는 few-shot이라 비문이 섞이면 LLM에게 비문을 가르치는 사고가 된다).

    여러 번 나오면 전부 바꾸고, 인덱스가 밀리지 않도록 뒤에서부터
    치환한다(`annotate()`와 같은 방식). `easy_term`이 한글로 끝나지
    않으면(숫자·기호) 조사 교정은 하지 않고 원문의 조사를 그대로 둔다.

    사전 전체를 문서에서 찾아 치환하려면 이 함수가 아니라
    `lookup.EasyDict.annotate()`를 써야 한다 — 그쪽은 최장일치·조사
    경계·전략별 처리(gloss/keep 보존)까지 다 갖춘 완전한 구현이다.

    >>> substitute_with_josa('필증을 발급받으세요', '필증', '증명서')
    '증명서를 발급받으세요'
    >>> substitute_with_josa('말미를 주세요', '말미', '여유 시간')
    '여유 시간을 주세요'
    """
    if not term:
        return text

    positions: list[int] = []
    start = 0
    while True:
        idx = text.find(term, start)
        if idx < 0:
            break
        positions.append(idx)
        start = idx + len(term)
    if not positions:
        return text

    n = len(text)
    limits = [
        positions[i + 1] if i + 1 < len(positions) else n for i in range(len(positions))
    ]

    result = text
    for idx, limit in zip(reversed(positions), reversed(limits)):
        end = idx + len(term)
        replacement = easy_term
        cut_end = end
        found = find_josa_after(text, end, limit)
        if found is not None and easy_term:
            current_form, pair = found
            correct_form = correct_josa_form(pair, easy_term[-1])
            if correct_form is not None and correct_form != current_form:
                replacement += correct_form
                cut_end = end + len(current_form)
        result = result[:idx] + replacement + result[cut_end:]
    return result
