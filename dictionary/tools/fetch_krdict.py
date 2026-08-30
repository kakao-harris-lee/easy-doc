#!/usr/bin/env python3
"""국립국어원 한국어기초사전(krdict) Open API에서 표제어 뜻풀이·예문을 모아
`easydict.build`가 바로 읽을 수 있는 CSV로 만드는 **데이터 확보 도구**.

**이 스크립트는 `src/easydict/` 빌드 파이프라인의 일부가 아니다.** 그 패키지는
"네트워크 없음, 외부 의존성 없음"이 설계 원칙(DESIGN.md §5.3)이라 로컬 CSV만
읽는다. 이 도구는 그 CSV를 미리 만들어 두는 별도 단계이고, 실행하지 않아도
`easydict` 패키지 자체는 아무 영향을 받지 않는다. 그래서 `src/easydict/`가
아니라 `tools/`에 둔다. 단, 한자 판정처럼 이미 검증된 로직은 새로 만들지 않고
`easydict.normalize`의 공개 함수(`is_pure_hanja`, `normalize_key`)를
가져다 쓴다(팀 지시) — 이건 순수 함수 재사용이지 네트워크·빌드 의존이 아니다.
아래 `_ensure_easydict_importable()`이 `src/`를 `sys.path`에 넣어 이 스크립트를
`PYTHONPATH` 설정 없이도 그냥 실행할 수 있게 한다.

## 라이선스
한국어기초사전은 CC BY-SA로 배포된다. 2차 저작물(이 프로젝트의 산출물)도
동일 라이선스로 공개해야 하고, 저작자 표시 의무가 있다. 이 스크립트가 만드는
CSV의 `출처` 컬럼에 사전명과 표제어 코드(target_code)를 남기는 것이 그 표시다.

## 네트워크 예절
- 호출 사이 지연(`--delay`, 기본 0.2초)
- 호출 수 상한(`--limit`, 기본 200 — 일일 한도 5만 건에 비해 보수적인 시험용
  기본값이다. 큰 배치를 돌리려면 명시적으로 올려야 한다)
- 실패 시 지수 백오프 + 재시도 상한(`--max-retries`, 기본 3) — 무한 재시도 없음
- `--dry-run`: 실제 호출 없이 무엇을 몇 건 조회할지만 출력
- `--min-doc-freq`: 갭 리스트의 `doc_freq`가 이 값 미만인 표제어는 아예 조회
  대상에서 뺀다 — 여러 문서에 반복 등장하지 않는 항목(활용형 잔재 등 잡음)에
  호출을 낭비하지 않는다.

## 원칙: 애매하면 채우지 말고 비워두고 기록한다
- 사전에 없는 말 -> `뜻`/`순화어`를 비우고 그 사실을 `주의`에 남긴다.
- 동음이의어·다의어로 뜻이 여럿 -> 어느 뜻인지 자동으로 고르지 않는다. 비우고
  몇 건이 걸렸는지 기록한다.
- 정의문이 너무 길거나(`--gloss-length-cap`) 순환 참조로 보이면(용어 자체가
  정의문 안에 그대로 들어있음) `순화어`를 채우지 않는다 — 어려운 말을 더
  어려운 말로 바꾸면 안 된다는 원칙(DESIGN.md §2.1) 그대로다. 다만 `뜻`은
  채운다(길이/순환과 무관하게 뜻풀이 자체는 유용한 참고 자료이므로) — 사람이
  검토해서 직접 짧게 다듬을 수 있게.
- 그 외의 "깔끔한 단일 매치"는 `gloss` 전략(원어 보존 + 설명 병기)으로
  내보낸다. `순화어`=`뜻`=정의문, `replace_strategy=gloss`,
  `risk_level=low`. 사람이 검토해서 짧은 대체어로 다듬으면 `substitute`로
  승격하면 된다.
- 이 도구가 만든 행은 전부 `status=review`로 표시한다.

## API 응답 필드 -> 우리 스키마 매핑
- `origin`(원어 표기) -> **순수 한자일 때만** `한자` 컬럼(→ `term_hanja`,
  `easydict.build.COLUMN_ALIASES`가 인식하는 헤더). 한자+로마자가 섞이면
  (예: `洞住民center`) 비운다 — `easydict.normalize.is_pure_hanja()` 재사용.
- `word_grade`(초급/중급/고급) -> `word_grade` 컬럼에 원문 그대로 남긴다.
  **주의**: `easydict.build.classify()`는 현재 `readability`를
  `replace_strategy`에서만 파생시키고(`{"substitute":1,"gloss":2,"keep":3}`),
  CSV에서 `readability`를 읽는 컬럼 별칭이 아예 없다(`COLUMN_ALIASES`에
  없음). 그래서 `word_grade`를 여기 적어 둬도 **지금 파이프라인에는
  반영되지 않는다** — 정보 보존 목적이고, 나중에 `build.py`가 이 값을
  읽게 확장되면 그때 값을 그대로 쓸 수 있다. `주의` 컬럼에도 이 사실을
  적어 헷갈리지 않게 했다.
- `pos` -> 정보로만 `주의`에 남긴다(`easydict.build`의 `pos` 컬럼 별칭이
  없어 이것도 아직 안 흘러간다 — 표제어 자체의 `pos`는 `guess_pos()`가
  빌드 타임에 다시 추정한다).
- `example`(용례) -> 검색(search) API 응답에는 없다. 아래 "view API" 참고.

## API 명세 — 확인 방법과 확인/추정 구분
공식 문서 페이지 2개(`eng`/`kor` `openApi/openApiInfo`)로 확인한 내용에
더해, **사용자가 실제 인증키로 3건을 조회한 원본 XML 덤프**
(`거주지`/`선정`/`주민센터`, `tools/tests/fixtures/krdict_*.xml`로 커밋)로
검증했다. 검색 API 구조는 이 덤프로 확인됐다. view(상세보기/예문) API는
공식 문서에 상세 파라미터가 없어서, 실제로 배포 중인 파이썬 래퍼
(`omarkmu/krdict.py`, GitHub) 소스코드(`request.py`)에서 확인한
`method=target_code&q=<target_code>` 조합을 그대로 썼다. 그래서 view API
호출은 `--fetch-examples`를 명시해야만 시도하는 별도 옵션으로 두었고,
실패해도 나머지 흐름(뜻/한자/등급/gloss 판정)에는 영향을 주지 않는다.

**2026-08-29 실측으로 확인됨 — view API는 작동한다.** 아래 "400 Request
Blocked" 기록은 헤더 없이 호출했을 때의 이야기이고, 이 스크립트가 쓰는
커스텀 `User-Agent`를 붙이면 `HTTP 200`에 예문이 온다. `급여`
(`target_code=41444`)로 확인: `<example>` 태그 15개. 파라미터 추정
(`method=target_code`, `q=<코드>`)도 맞았고, 오류 응답 구조 추정도 맞았다
(`num=3`으로 호출하니 `<error><error_code>103</error_code><message>Invalid
num value</message></error>` — `num`에 하한이 있다).

**그럼에도 이 프로젝트는 view API 예문을 쓰지 않기로 했다.** 경로가 막혀서가
아니라 **내용이 도메인에 안 맞아서**다. 받아본 15개 중 완결 문장은 하나뿐이고
나머지는 `공무원 급여.`, `급여 수준.` 같은 구 조각이었다. 그리고 그 유일한
문장이 `회사 사정이 안 좋아서 직원들의 급여가 몇 달째 밀렸다` — **「월급」
뜻의 급여**다. 이 사전이 다루는 생계급여·주거급여가 아니다. 일반 사전은
일반 뜻을 준다(같은 이유로 DESIGN.md §5.7에서 다듬은말 원천이 탈락했다).
예문은 easy-doc 골든 코퍼스에서 직접 뽑는다 — 도메인이 정확히 일치한다.

**확인**: 검색 엔드포인트 URL·파라미터·응답 태그 구조(`channel > item >
target_code/word/sup_no/origin/pronunciation/word_grade/pos/link/sense
(sense_order/definition)`) — 실제 XML 덤프로 확인. `sense`에 `example`이
있을 수 있다는 문서 설명은 **이번 3건 덤프엔 등장하지 않아 검증 못함**
(구조 자체는 파싱 코드에 남겨 뒀다 — 나오면 그대로 잡힌다).

**추정(미검증)**: view API의 정확한 파라미터(`omarkmu/krdict.py` 소스
기준 추정, 공식 문서로는 미확인), 오류 응답 구조(`<error><error_code>`/
`<message>`, 정부 오픈 API 관례 추정 — 이번 덤프엔 에러 사례가 없었다),
view API의 400 원인(아래 참고).

**view API 400 관련 참고**: `omarkmu/krdict.py`는 헤더를 따로 안 붙이고
`requests` 기본값으로 호출한다. 이 스크립트는 검색 API에 이미 커스텀
`User-Agent`를 쓰고 있고(그 쪽은 실제로 성공했다고 확인됨), 같은 헤더로
view도 시도하도록 만들었다 — 그래도 될지는 **실제로 키로 돌려봐야 안다.**
추측만으로 더 파고들지 않았다.
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

# src/ 를 sys.path에 넣어 PYTHONPATH 설정 없이도 easydict.normalize를 가져올
# 수 있게 한다. 모듈 상단 docstring의 경계 설명 참고 — 순수 함수 재사용이다.
_SRC_DIR = Path(__file__).resolve().parent.parent / "src"
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))

try:
    from easydict.normalize import is_pure_hanja, normalize_key
except ImportError:  # pragma: no cover - 저장소 구조가 바뀌었을 때 방어
    def is_pure_hanja(s: str) -> bool:
        return False

    def normalize_key(s: str) -> str:
        return s or ""

SEARCH_ENDPOINT = "https://krdict.korean.go.kr/api/search"
VIEW_ENDPOINT = "https://krdict.korean.go.kr/api/view"  # 확인/추정: 모듈 docstring 참고
DICT_NAME = "한국어기초사전"
DICT_HOME = "https://krdict.korean.go.kr"

# easydict.build가 읽는 CSV 컬럼. 앞 10개는 data/raw/welfare_seed_1.csv와
# 동일 순서(팀 지시). 뒤 2개는 이번에 추가한 정보 컬럼 — "한자"는
# easydict.build.COLUMN_ALIASES가 인식해서 실제로 term_hanja에 흘러가고,
# "word_grade"는 (모듈 docstring에 적었듯) 아직 안 흘러가는 정보 전용이다.
OUTPUT_COLUMNS = [
    "원어", "순화어", "분야", "뜻", "예문", "주의", "출처",
    "replace_strategy", "risk_level", "status",
    "한자", "word_grade",
]

# 갭 리스트의 suggested_tag -> 분야(한글 라벨). easydict.models.TAG_CATALOG의
# label과 맞춘다(그 모듈 자체를 import하지는 않는다 — 표 하나 복제가 이
# 스크립트를 models.py 변경에 안 엮이게 하는 더 단순한 선택이라 판단했다).
TAG_LABELS = {
    "admin": "행정", "law": "법률", "welfare": "복지", "medical": "보건·의료",
    "finance": "금융·세무", "form": "서식·신청",
}

# krdict word_grade -> DESIGN.md §3.2 readability(1~3). 현재는 정보 전용
# (모듈 docstring 참고) — build.py가 나중에 이 값을 읽게 되면 쓸 매핑이다.
WORD_GRADE_TO_READABILITY = {"초급": 1, "중급": 2, "고급": 3}

# gloss로 내보낼 정의문 길이 상한(문자 수). 근거: DESIGN.md §2.1/§4.2가 든
# gloss 예시("정해진 법을 안 지켜서 내는 돈" 15자, "기초생활수급자 바로 위의
# 저소득층" 17자)와 실제 krdict 덤프 3건(11/20/24자)이 전부 10~25자
# 구간이었다. 그 상한의 넉넉한 2배 정도를 잡아 정상적으로 조금 긴 정의문은
# 통과시키되, 문장 하나를 통째로 괄호에 욱여넣는 수준은 걸러낸다. 정확한
# "옳은" 값은 없다 — 실제 gloss 산출물을 보고 나중에 조정하면 된다.
DEFAULT_GLOSS_LENGTH_CAP = 40

USER_AGENT = "easy-dictionay/fetch_krdict.py (data-collection tool; stdlib urllib)"


@dataclass
class Sense:
    definition: str
    examples: list[str] = field(default_factory=list)


@dataclass
class Item:
    """검색 결과 한 건(하나의 표제어 항목 = 하나의 동음이의어)."""

    word: str
    pos: str | None
    origin: str | None
    word_grade: str | None
    target_code: str | None
    # 동형어 번호. 실제 응답(tools/tests/fixtures/krdict_seonjeong.xml)에서
    # '선정'이 sup_no=1/2 두 항목으로 온다 — 동형어가 없으면 0, 있으면
    # 1부터 매겨진다(0을 포함해 세지 않는다). tools/detect_homonym_risk.py가
    # 이 값으로 "표제어는 같은데 뜻이 여럿"을 판정한다.
    sup_no: str | None = None
    senses: list[Sense] = field(default_factory=list)


@dataclass
class FetchResult:
    term: str
    items: list[Item]
    error: str | None = None  # None이면 정상 응답(결과 0건 포함)


class KrdictError(Exception):
    """API가 명시적으로 에러를 반환했을 때(인증 실패, 한도 초과 등)."""


def _build_search_url(term: str, api_key: str, *, num: int, level: str | None, pos: str | None) -> str:
    params: dict[str, str] = {
        "key": api_key,
        "q": term,
        "part": "word",
        "num": str(num),
        "sort": "dict",
        "advanced": "n",
    }
    if level:
        params["level"] = level
    if pos:
        params["pos"] = pos
    return f"{SEARCH_ENDPOINT}?{urllib.parse.urlencode(params)}"


def _build_view_url(target_code: str, api_key: str) -> str:
    """상세보기(view) API URL. 파라미터는 미검증 추정이다(모듈 docstring 참고)."""
    params = {"key": api_key, "method": "target_code", "q": target_code}
    return f"{VIEW_ENDPOINT}?{urllib.parse.urlencode(params)}"


def _check_api_error(root: ET.Element) -> None:
    error_el = root if root.tag == "error" else root.find(".//error")
    if error_el is None:
        return
    code_el = error_el.find("error_code")
    msg_el = error_el.find("message")
    code = code_el.text if code_el is not None else "?"
    msg = msg_el.text if msg_el is not None else ET.tostring(error_el, encoding="unicode")
    raise KrdictError(f"API 에러 (code={code}): {msg}")


def _text(el: ET.Element | None) -> str | None:
    if el is None or not (el.text or "").strip():
        return None
    return el.text.strip()


def _parse_search_response(xml_bytes: bytes) -> list[Item]:
    """검색 응답 XML을 파싱한다.

    태그 구조(`channel > item > target_code/word/sup_no/origin/
    pronunciation/word_grade/pos/link/sense(sense_order/definition/
    example)`)는 실제 API 키로 받은 원본 응답 3건(`tools/tests/fixtures/
    krdict_*.xml`)으로 확인했다 — 모듈 docstring의 "확인/추정" 참고.
    `example`은 이번 3건 덤프에 없어서 태그만 대비해 뒀다(있으면 잡힌다).
    """
    root = ET.fromstring(xml_bytes)
    _check_api_error(root)

    items: list[Item] = []
    for item_el in root.findall(".//item"):
        word = _text(item_el.find("word"))
        if word is None:
            continue

        senses: list[Sense] = []
        for sense_el in item_el.findall("sense"):
            definition = _text(sense_el.find("definition"))
            if definition is None:
                continue
            examples = [
                ex for ex_el in sense_el.findall("example") if (ex := _text(ex_el)) is not None
            ]
            senses.append(Sense(definition=definition, examples=examples))

        items.append(
            Item(
                word=word,
                pos=_text(item_el.find("pos")),
                origin=_text(item_el.find("origin")),
                word_grade=_text(item_el.find("word_grade")),
                target_code=_text(item_el.find("target_code")),
                sup_no=_text(item_el.find("sup_no")),
                senses=senses,
            )
        )
    return items


def _parse_view_response(xml_bytes: bytes) -> list[str]:
    """view 응답에서 예문만 뽑는다. 구조는 검색 응답과 비슷하다고 가정한
    추정이며 실제 응답으로 검증되지 않았다(모듈 docstring 참고)."""
    root = ET.fromstring(xml_bytes)
    _check_api_error(root)
    return [
        ex
        for item_el in root.findall(".//item")
        for sense_el in item_el.findall("sense")
        for ex_el in sense_el.findall("example")
        if (ex := _text(ex_el)) is not None
    ]


def _http_get(url: str, *, timeout: float) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _with_retry(
    call, *, delay: float, max_retries: int,
) -> tuple[bytes | None, str | None]:
    """`call()`(인자 없는 HTTP 호출)을 재시도·백오프와 함께 실행한다.

    반환: (원시 바이트 또는 None, 에러 메시지 또는 None). 호출 예절(지연·
    재시도 상한)은 이 함수 하나로 모은다 — search/view 둘 다 재사용한다.
    """
    backoff = 1.0
    last_error: str | None = None
    for attempt in range(1, max_retries + 1):
        try:
            raw = call()
            time.sleep(delay)
            return raw, None
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
            last_error = f"{type(e).__name__}: {e}"
            if attempt >= max_retries:
                break
            time.sleep(backoff)
            backoff *= 2  # 지수 백오프. max_retries로 무한 재시도를 막는다.
    time.sleep(delay)
    return None, f"{max_retries}회 재시도 후 실패: {last_error}"


def fetch_term(
    term: str,
    api_key: str,
    *,
    num: int,
    level: str | None,
    pos: str | None,
    delay: float,
    max_retries: int,
    timeout: float,
    dump_raw_dir: Path | None,
) -> FetchResult:
    """표제어 하나를 검색 API로 조회한다."""
    url = _build_search_url(term, api_key, num=num, level=level, pos=pos)
    raw, err = _with_retry(lambda: _http_get(url, timeout=timeout), delay=delay, max_retries=max_retries)
    if err is not None:
        return FetchResult(term=term, items=[], error=err)
    if dump_raw_dir is not None:
        dump_raw_dir.mkdir(parents=True, exist_ok=True)
        safe_name = "".join(c if c.isalnum() else "_" for c in term)
        (dump_raw_dir / f"{safe_name}.xml").write_bytes(raw)
    try:
        items = _parse_search_response(raw)
    except (ET.ParseError, KrdictError) as e:
        return FetchResult(term=term, items=[], error=str(e))
    return FetchResult(term=term, items=items)


def fetch_examples_via_view(
    target_code: str, api_key: str, *, delay: float, max_retries: int, timeout: float,
) -> tuple[list[str], str | None]:
    """view API로 예문을 시도한다. **미검증** — 모듈 docstring 참고.

    실패해도 예외를 던지지 않는다(반환값의 에러 메시지로만 알린다) — 검색
    결과 흐름을 절대 막으면 안 되는, 순수 부가 기능이기 때문이다.
    """
    url = _build_view_url(target_code, api_key)
    raw, err = _with_retry(lambda: _http_get(url, timeout=timeout), delay=delay, max_retries=max_retries)
    if err is not None:
        return [], err
    try:
        return _parse_view_response(raw), None
    except (ET.ParseError, KrdictError) as e:
        return [], str(e)


def is_circular_definition(term: str, definition: str) -> bool:
    """정의문이 표제어 자체를 그대로 되풀이하는지(순환 참조) 대략 확인한다.

    `normalize_key()`로 공백을 지운 뒤 정의문 안에 표제어 전체가 그대로
    부분 문자열로 들어있는지 본다. 예: '수급자'의 정의 '수급 자격이 있는
    자'는 공백을 지우면 '수급자격이있는자'가 되고, 그 안에 '수급자'가
    그대로 들어있다 -> 순환으로 판정한다. 완벽한 판별은 아니다(형태소
    분석이 아니라 문자열 포함 검사다) — 애매하면 순환으로 보는 쪽이
    안전하다(어려운 말을 더 어려운 말로 정의하는 사고를 막는 게 우선).
    """
    key = normalize_key(term)
    return bool(key) and key in normalize_key(definition)


def _compact(s: str) -> str:
    """비교를 위해 내부 공백을 전부 제거한다(길이 비교 전용, 정규화 아님).

    `normalize_key()`를 쓰지 않은 이유: 그 함수는 하이픈·중점·괄호까지
    제거하고 casefold도 하는데, 여기서는 "몇 글자인가"만 필요하고
    한자/한글 구성 자체를 바꾸면 안 된다. 단순 공백 제거만 한다.
    """
    return "".join(s.split())


def is_truncated_fragment(word: str, origin: str) -> bool:
    """krdict `origin`(한자 병기)이 `word`(표제어)보다 길면 잘린 조각으로 본다.

    갭리스트 추출기가 복합어를 부분적으로만 뽑아 보내는 경우가 있다
    (`地下室`을 병기하는 항목인데 표제어로는 `하실`만 옴 — `지하실`에서
    `지`가 잘렸다). 원어(한자)가 표제어보다 길면 한자 정보가 가리키는
    실제 단어가 표제어보다 크다는 뜻이므로 표제어 쪽이 잘렸다고 볼 수
    있다.

    실측 근거: 두 차례 수집분(947+2,400건)에서 한자 병기가 있는 746행을
    전수 확인한 결과—
        한자 > 표제어 :  19건 (전부 실제로 잘린 조각, 오탐 0)
        한자 = 표제어 : 725건
        한자 < 표제어 :   2건 (`외에`/`외의` — 조사가 붙어 표제어가 더
                              길어진 정상 케이스)
    19/19 정확도로 판별됐고 반대 방향(한자가 더 짧음)은 표본이 2건뿐이라
    별도 규칙을 만들 근거가 없다 — 그대로 통과시킨다(요청사항).

    길이는 내부 공백을 지우고 비교한다(`_compact()`) — 표제어에 공백이
    있으면(`가스 마스크`) 그 공백은 음절이 아니라서 그대로 두면 길이
    비교 기준이 흔들린다. 다만 한자 병기가 있는 실데이터에는 애초에
    스페이스가 들어간 표제어가 없었다(외래어 origin은 순수 한자가
    아니므로 `is_pure_hanja()` 문턱에서 이미 걸러진다) — 그래도 원칙은
    분명히 해 둔다: **비교는 항상 공백을 제거한 글자 수로 한다.**

    호출 전 `is_pure_hanja(origin)`으로 순수 한자인지 반드시 먼저
    확인해야 한다 — `origin`에 로마자가 섞이면(`洞住民center`) 길이
    비교 자체가 무의미하다.

    >>> is_truncated_fragment('하실', '地下室')
    True
    >>> is_truncated_fragment('지하실', '地下室')
    False
    >>> is_truncated_fragment('외에', '外')
    False
    """
    return len(_compact(origin)) > len(_compact(word))


def _make_row(
    term: str, tag_label: str, result: FetchResult, *, gloss_length_cap: int,
) -> dict[str, str]:
    """조회 결과 하나를 출력 CSV 한 행으로 만든다. 애매하면 비워두고 기록한다."""
    row = {col: "" for col in OUTPUT_COLUMNS}
    row["원어"] = term
    row["분야"] = tag_label
    row["status"] = "review"  # 이 도구가 만든 행은 전부 사람 검수 전이다.

    if result.error is not None:
        row["주의"] = f"[검토 필요] API 호출 실패: {result.error}"
        return row

    if not result.items:
        row["주의"] = f"[검토 필요] {DICT_NAME}에서 검색 결과 없음"
        return row

    # 정확히 이 표제어와 글자가 같은 항목만 후보로 본다(부분일치 결과 제외).
    # 정확히 일치하는 게 하나도 없으면(예: '주민센터' 질의에 '동 주민 센터'만
    # 옴) 그래도 유일한 결과라면 그걸 후보로 쓰되, 표기가 다르다는 걸 남긴다.
    exact = [it for it in result.items if it.word == term]
    candidates = exact if exact else result.items
    word_mismatch = not exact and len(candidates) == 1 and candidates[0].word != term

    if len(candidates) > 1:
        codes = ",".join(c.target_code or "?" for c in candidates)
        row["주의"] = (
            f"[검토 필요] 동음이의어 {len(candidates)}건(target_code={codes}) — "
            f"자동으로 하나를 고르지 않음. 사람이 검토 후 뜻/예문을 채워야 함"
        )
        row["출처"] = f"{DICT_NAME}(국립국어원), target_code={codes}"
        return row

    item = candidates[0]

    if item.origin and is_pure_hanja(item.origin) and is_truncated_fragment(item.word, item.origin):
        row["주의"] = (
            f"[검토 필요] 갭리스트 추출 시 단어가 잘렸을 가능성 — 한자 병기 "
            f"'{item.origin}'이 표제어 '{item.word}'보다 깁니다. 순화어/뜻을 "
            f"채우지 않음. 사람이 원문을 확인해야 함."
        )
        row["출처"] = f"{DICT_NAME}(국립국어원), target_code={item.target_code or '?'}"
        return row

    if len(item.senses) != 1:
        n = len(item.senses)
        row["주의"] = (
            f"[검토 필요] 의미(sense) {n}건 — 어느 뜻인지 자동으로 고르지 않음. "
            f"사람이 검토 후 뜻/예문을 채워야 함"
        ) if n != 1 else "[검토 필요] 뜻풀이 없음"
        row["출처"] = f"{DICT_NAME}(국립국어원), target_code={item.target_code or '?'}"
        return row

    sense = item.senses[0]
    definition = sense.definition

    row["뜻"] = definition
    row["예문"] = sense.examples[0] if sense.examples else ""
    row["출처"] = f"{DICT_NAME}(국립국어원), target_code={item.target_code or '?'}"
    row["word_grade"] = item.word_grade or ""
    if item.origin and is_pure_hanja(item.origin):
        row["한자"] = item.origin

    notes = [f"[자동 수집, 검토 필요] {DICT_NAME} 정의를 그대로 옮김."]
    if word_mismatch:
        notes.append(f"질의어와 다른 표기('{item.word}')로 매칭됨 — 확인 필요.")
    if item.word_grade:
        readability = WORD_GRADE_TO_READABILITY.get(item.word_grade)
        if readability is not None:
            notes.append(
                f"word_grade={item.word_grade}(readability {readability}에 해당하나 "
                "현재 파이프라인은 readability를 replace_strategy에서만 파생시켜 "
                "이 값은 아직 반영되지 않음)."
            )
    if item.origin and not row["한자"]:
        notes.append(f"origin='{item.origin}'은 순수 한자가 아니라 한자 컬럼을 비움.")

    too_long = len(definition) > gloss_length_cap
    circular = is_circular_definition(term, definition)
    if too_long or circular:
        reason = []
        if too_long:
            reason.append(f"정의문 {len(definition)}자 > 상한 {gloss_length_cap}자")
        if circular:
            reason.append("정의문에 표제어 자체가 그대로 포함(순환 참조 의심)")
        notes.append(f"순화어는 채우지 않음({', '.join(reason)}) — 사람이 직접 다듬어야 함.")
    else:
        row["순화어"] = definition
        row["replace_strategy"] = "gloss"
        row["risk_level"] = "low"
        notes.append("gloss 초안으로 순화어=뜻으로 채움 — 사람이 검토 후 필요하면 substitute로 승격.")

    row["주의"] = " ".join(notes)
    return row


def read_gap_terms(path: Path | None, *, min_doc_freq: int | None) -> list[dict[str, str]]:
    """갭 리스트(term,doc_freq,total_freq,sample_context,suggested_tag)를 읽는다.

    `path`가 None이면 stdin에서 같은 형식의 CSV를 읽는다. `min_doc_freq`가
    주어지면 `doc_freq` 컬럼이 있어야 하고(없으면 바로 종료), 그 값보다
    작은 행은 걸러낸다. `doc_freq`가 숫자로 안 읽히는 행도 걸러낸다(안전
    쪽으로 — 필터를 통과시켰다고 잘못 우길 수 없으니).
    """
    f = sys.stdin if path is None else path.open("r", encoding="utf-8-sig", newline="")
    try:
        reader = csv.DictReader(f)
        if reader.fieldnames is None or "term" not in reader.fieldnames:
            raise SystemExit(
                f"입력 CSV에 'term' 컬럼이 없습니다. 발견된 헤더: {reader.fieldnames}"
            )
        rows = [r for r in reader if (r.get("term") or "").strip()]
    finally:
        if f is not sys.stdin:
            f.close()

    if min_doc_freq is None:
        return rows

    if reader.fieldnames is None or "doc_freq" not in reader.fieldnames:
        raise SystemExit(
            "--min-doc-freq를 지정했는데 입력 CSV에 'doc_freq' 컬럼이 없습니다."
        )
    filtered = []
    skipped_unparsable = 0
    for r in rows:
        raw = (r.get("doc_freq") or "").strip()
        try:
            freq = int(raw)
        except ValueError:
            skipped_unparsable += 1
            continue
        if freq >= min_doc_freq:
            filtered.append(r)
    if skipped_unparsable:
        print(
            f"경고: doc_freq를 숫자로 못 읽은 행 {skipped_unparsable}건은 "
            "--min-doc-freq 필터에서 제외했습니다.",
            file=sys.stderr,
        )
    return filtered


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=(
            f"{DICT_NAME}({DICT_HOME}) Open API에서 표제어 뜻풀이/예문을 모아 "
            "easydict.build용 CSV를 만든다."
        ),
    )
    p.add_argument(
        "--input", type=Path, default=None,
        help="갭 리스트 CSV 경로 (term,doc_freq,total_freq,sample_context,suggested_tag). "
             "생략하면 stdin에서 같은 형식으로 읽는다.",
    )
    p.add_argument("--output", type=Path, default=None, help="출력 CSV 경로 (기본: stdout)")
    p.add_argument(
        "--api-key", default=None,
        help="krdict 인증키. 생략하면 환경변수 KRDICT_API_KEY를 쓴다.",
    )
    p.add_argument("--top-n", type=int, default=None, help="입력 상위 N개 표제어만 처리(기본: 전체)")
    p.add_argument(
        "--min-doc-freq", type=int, default=None,
        help="갭 리스트의 doc_freq가 이 값 미만인 표제어는 조회하지 않는다(기본: 필터 없음)",
    )
    p.add_argument(
        "--limit", type=int, default=200,
        help="이번 실행에서 허용할 최대 API 호출 수 (기본 200 — 일일 한도 5만 건 대비 보수적인 시험값)",
    )
    p.add_argument("--delay", type=float, default=0.2, help="호출 사이 지연(초), 기본 0.2")
    p.add_argument("--max-retries", type=int, default=3, help="호출 실패 시 재시도 횟수 상한, 기본 3")
    p.add_argument("--timeout", type=float, default=10.0, help="HTTP 타임아웃(초), 기본 10")
    p.add_argument("--num", type=int, default=10, help="호출당 결과 건수(API의 num 파라미터), 기본 10")
    p.add_argument(
        "--level", default=None,
        help="krdict 급수 필터(level1=초급/level2=중급/level3=고급, 쉼표로 복수 지정 가능). 기본: 필터 없음",
    )
    p.add_argument("--pos", default=None, help="krdict 품사 코드 필터(문서 기준 0~15, 쉼표 구분). 기본: 필터 없음")
    p.add_argument(
        "--gloss-length-cap", type=int, default=DEFAULT_GLOSS_LENGTH_CAP,
        help=f"gloss로 내보낼 정의문 길이 상한(문자 수), 기본 {DEFAULT_GLOSS_LENGTH_CAP}",
    )
    p.add_argument(
        "--fetch-examples", action="store_true",
        help="깔끔한 단일 매치마다 view API로 예문도 시도한다(미검증 기능, 실패해도 나머지엔 영향 없음)",
    )
    p.add_argument(
        "--dump-raw-xml", type=Path, default=None, metavar="DIR",
        help="응답 원본 XML을 이 디렉터리에 표제어별로 저장한다(구조 검증/디버깅용)",
    )
    p.add_argument(
        "--dry-run", action="store_true",
        help="실제 API를 호출하지 않고 무엇을 몇 건 조회할지만 출력한다",
    )
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    all_terms = read_gap_terms(args.input, min_doc_freq=args.min_doc_freq)
    terms = all_terms[: args.top_n] if args.top_n is not None else all_terms
    planned = terms[: args.limit]

    if args.dry_run:
        print(f"[dry-run] --min-doc-freq={args.min_doc_freq} 적용 후 입력 표제어 수: {len(all_terms)}")
        print(f"[dry-run] --limit={args.limit} 적용 후 실제 조회할 건수: {len(planned)}")
        print(f"[dry-run] 호출 간 지연: {args.delay}초, 예상 소요 시간: {len(planned) * args.delay:.1f}초 이상")
        print(
            "[dry-run] 호출 URL 형태(키는 가려서 표시): "
            f"{_build_search_url('<표제어>', '****', num=args.num, level=args.level, pos=args.pos)}"
        )
        if args.fetch_examples:
            print(f"[dry-run] --fetch-examples 지정됨: 깔끔한 매치마다 view 호출이 추가로 발생(호출 수 최대 2배)")
        print("[dry-run] 조회 예정 표제어 샘플(최대 10개):")
        for row in planned[:10]:
            print(f"  - {row['term']} (doc_freq={row.get('doc_freq','?')}, tag={row.get('suggested_tag','?')})")
        if len(planned) > 10:
            print(f"  ... 외 {len(planned) - 10}건")
        return 0

    api_key = args.api_key or os.environ.get("KRDICT_API_KEY")
    if not api_key:
        print(
            "오류: krdict 인증키가 없습니다. --api-key로 넘기거나 환경변수 "
            "KRDICT_API_KEY를 설정하세요. 키 발급: "
            "https://krdict.korean.go.kr/openApi/openApiRegister",
            file=sys.stderr,
        )
        return 2

    out_f = args.output.open("w", encoding="utf-8", newline="") if args.output else sys.stdout
    stats = {"gloss": 0, "definition_only": 0, "not_found": 0, "ambiguous": 0, "error": 0}
    view_stats = {"tried": 0, "ok": 0, "failed": 0}
    try:
        writer = csv.DictWriter(out_f, fieldnames=OUTPUT_COLUMNS)
        writer.writeheader()
        total = len(planned)
        for i, gap_row in enumerate(planned, start=1):
            term = gap_row["term"].strip()
            tag_label = TAG_LABELS.get((gap_row.get("suggested_tag") or "").strip(), "")
            print(f"[{i}/{total}] 조회: {term}", file=sys.stderr)

            result = fetch_term(
                term, api_key,
                num=args.num, level=args.level, pos=args.pos,
                delay=args.delay, max_retries=args.max_retries, timeout=args.timeout,
                dump_raw_dir=args.dump_raw_xml,
            )
            out_row = _make_row(term, tag_label, result, gloss_length_cap=args.gloss_length_cap)

            if (
                args.fetch_examples
                and not out_row["예문"]
                and out_row["뜻"]
                and result.error is None
                and len(result.items) >= 1
            ):
                # 깔끔한 단일 매치(뜻이 채워졌다 = 동음이의어/다의어 아님)에만 시도한다.
                exact = [it for it in result.items if it.word == term]
                item = (exact or result.items)[0]
                if item.target_code:
                    view_stats["tried"] += 1
                    examples, verr = fetch_examples_via_view(
                        item.target_code, api_key,
                        delay=args.delay, max_retries=args.max_retries, timeout=args.timeout,
                    )
                    if examples:
                        out_row["예문"] = examples[0]
                        view_stats["ok"] += 1
                    else:
                        view_stats["failed"] += 1
                        print(f"  (view API 예문 조회 실패: {verr})", file=sys.stderr)

            writer.writerow(out_row)

            if result.error is not None:
                stats["error"] += 1
            elif not result.items:
                stats["not_found"] += 1
            elif not out_row["뜻"]:
                stats["ambiguous"] += 1
            elif out_row["순화어"]:
                stats["gloss"] += 1
            else:
                stats["definition_only"] += 1
    finally:
        if out_f is not sys.stdout:
            out_f.close()

    print(
        f"완료: gloss로 채움 {stats['gloss']}건 / 뜻만 채움(순화어 보류) {stats['definition_only']}건 / "
        f"검색결과없음 {stats['not_found']}건 / 동음이의어·의미 애매 {stats['ambiguous']}건 / "
        f"호출실패 {stats['error']}건 (총 {len(planned)}건 조회)",
        file=sys.stderr,
    )
    if args.fetch_examples:
        print(
            f"view API 예문 시도: {view_stats['tried']}건 중 성공 {view_stats['ok']}건, "
            f"실패 {view_stats['failed']}건 (미검증 기능 — tools/README.md 참고)",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
