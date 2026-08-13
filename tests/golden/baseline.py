"""품질 기준선 — 코퍼스 지문과 상대 하한선 판정.

**합격 기준은 절대 수치가 아니라 "직전에 기록된 측정치보다 낮아지지 않는다"이다**
(2026-08-12 사용자 결정). master-plan §7의 KPI 0.90은 **목표선으로 남고 차단하지 않는다** —
현재 실측이 0.446~0.643이라(`docs/migration/_workspace/02_quality-baseline.md` §5.3)
0.90을 차단선으로 두면 게이트가 상시 빨간불이 되어 아무도 읽지 않게 된다.

**차단축은 `전체` 하나이고, 허용 폭이 있다**(2026-08-13 사용자 결정 — 5회 실측 근거는
`OVERALL_FLOOR_TOLERANCE`). 합성·실수집은 **기록·경고만** 한다. 그 전까지는 세 축이
각각 차단이고 폭이 0이었는데, 코드를 한 줄도 바꾸지 않은 5회 실행 중 **4회가 판정 대상이고
그중 3회가 차단**됐다. 상시 빨간불은 0.90을 차단선으로 두었을 때와 같은 고장이다 — 게이트가
품질이 아니라 잡음에 반응하면 사람이 게이트를 끈다. **이 완화가 못 잡게 된 것은 숨기지 않고
`TOLERANCE_BLIND_SPOTS`에 적어 차단·통과 메시지 양쪽에 싣는다.**

상대 기준에는 절대 기준에 없는 함정이 하나 있다. **분모를 바꾸면 수치가 저절로 오른다.**
실측으로 확인된 경로다 — 25→36건 편입만으로 통과율이 0.51→0.446으로 움직였고
(`2026-08-08-golden-baseline-56.md`), 반대 방향으로 문서 063·064는 **세 차례** 제외
후보로 지목돼 있다(추출 아티팩트 의심). 그 둘을 빼면 통과율은 실력과 무관하게 오르고,
"직전보다 낮지 않다"는 조건은 조용히 충족된다. 그래서 측정치만 기록하는 것으로는 부족하고
**무엇을 재서 얻은 수치인지**를 함께 못박아야 한다. 그것이 여기서 말하는 지문이다.

지문이 잡는 것과 일부러 잡지 않는 것 — 이 선이 이 파일의 핵심 판단이다.

**넣는다 ① 코퍼스** (문서 id 집합·본문·required_facts·합성 여부).
    재는 대상이 바뀌면 수치가 비교 불가다. 문서를 빼서 하한선을 통과하는 경로가 여기다.

**넣는다 ② 판정 기준** (규칙 정의·팩트 잔존 판정·통과율 정의의 **값과 코드 전량**).
    자[尺]가 바뀌면 같은 변환문도 다른 점수를 받는다. 실제로 2026-08-09에 '뜻풀이 축자
    삽입' 축(패턴 123종)이 **신설**되어 그 이전 수치와의 비교가 깨졌다
    (`02_quality-baseline.md` §5.2). 문장 길이 상한을 50→80으로 풀면 통과율이 뛰는데,
    이것은 문서를 빼는 것과 **구조가 같은 우회**다. 한쪽만 막으면 다른 쪽으로 나간다.

    자는 상수만이 아니다. 2026-08-12 교차 리뷰가 실측으로 보였다 — 대문자 상수만 걷던
    지문은 `_SENTENCE_SPLIT`(문장 분리 정규식) 교체로 문장 길이 위반이 375→20건이 되어도,
    `check_style` 본문에서 길이 검사를 빼 375→0건이 되어도 **바이트 동일**했다. 그래서 지금은
    ⓐ 모듈 전역의 값 전량(이름 규칙 없음)과 ⓑ 판정 모듈 소스의 정규화 digest를 함께 걷는다.
    통과율의 정의(`tests/golden/evaluation.py`)도 같은 이유로 여기 들어온다 — 분모를 지문으로
    막아 놓고 분자의 정의를 열어 두면 같은 출력이 더 높은 통과율을 받는다.

**넣는다 ③ producer** (변환 provider·**관측된** 모델·effort). 2026-08-13 사용자 결정.
    앞의 둘이 "무엇을" "어떤 자로" 쟀는가라면 이쪽은 **누가 낸 수치인가**다. 넣기 전에는
    provider·모델이 `RunContext`에만 있어 비교 가능성 판정에 들어가지 않았고, 그래서
    anthropic으로 기록한 기준선과 openai 실행이 **비교 가능**으로 읽혀 수치 판정이 나왔다.
    다른 모델의 통과율끼리 비교하는 것은 자를 바꾸고 비교하는 것과 같은 종류의 무의미다.
    effort를 함께 담는 근거도 짐작이 아니라 이 저장소의 기록이다 — 9%p 하락의 후보로
    `.env`의 `LLM_EFFORT` 차이를 스스로 지목했다(`04_goldenset-first-run.md`). 모델과
    같은 부류다.

    **해시가 아니라 값으로 담는다.** 앞의 두 축은 원재료가 커서 접었지만 producer는 짧은
    문자열이라 접을 이유가 없고, 접으면 drift 메시지가 "무언가 달라졌다"까지만 말한다.
    비교 불가를 만난 사람이 가장 먼저 알아야 하는 것은 **무엇이** 달라졌는가다
    (`anthropic/claude-sonnet-5 → openai/gpt-4.1`).

    `criteria_sha256`에 접어 넣지 않은 이유는 코퍼스와 판정 기준을 따로 둔 이유와 같다
    (`Fingerprint` docstring) — 비교 불가일 때 재는 대상이 바뀐 건지, 자가 바뀐 건지,
    만든 쪽이 바뀐 건지가 곧 다음 행동을 가른다.

**넣지 않는다 ④ 프롬프트**.
    이쪽은 *재는 대상*이 아니라 **재어지는 것**이다. 프롬프트를 고쳐 통과율이 오르는 것은
    우회가 아니라 목적이며, 지문에 넣으면 개선할 때마다 "비교 불가"가 되어 하한선이
    영원히 축적되지 않는다.

정리하면 **자와 재는 대상과 만든 쪽은 고정하고, 재어지는 것(프롬프트)만 움직이게 둔다.**
judge 정보와 설정값 `model`은 지문이 아니라 `RunContext`로 **기록만** 한다 — judge는
비차단축이라 하한선의 구성요소가 아니고, 설정값 `model`은 별칭 해석·폴백이 있으면 실제와
갈리는 *주장*이라 증거인 **관측 모델**이 지문을 맡는다.

기록 실행의 규약은 참고 갈림 원장(`.claude/skills/python-kotlin-parity/scripts/
compare_parity.py`)에서 실측으로 얻은 교훈을 그대로 따른다. 그 장치는 구조가 같고,
같은 결함을 네 가지 형태로 겪었다.

1. **기록 실행은 게이트를 닫지 않는다.** 기준선을 방금 갱신한 실행과 애초에 문제없던
   실행이 같은 판정을 내면 자동화가 둘을 구분하지 못한다.
2. **"지적 건수"를 "변경 여부"의 대리 지표로 쓰지 않는다.** 원장이 그 착각으로 "파일을
   새로 만들고도 성공 코드"를 냈다(X-12). 여기서 변경 여부는 **쓸 내용과 디스크 내용의
   차이**로만 판정한다(`baseline_changes`).
3. **없다가 생기는 것도 변경이다.** 파일 부재는 "변경 없음"이 아니라 "비교할 이전 내용이
   없음"이다(`stored_body`가 `None`을 그 뜻으로 돌려준다).
4. **타임스탬프는 비교 대상이 아니다.** 넣으면 매 실행 값이 달라 기록 실행이 **항상**
   차단되는 정반대 고장이 난다(`BASELINE_VOLATILE_FIELDS`).
"""

import ast
import hashlib
import json
import os
import re
from collections.abc import Mapping
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from types import ModuleType
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, model_validator

from app.easyread import goldenset, style_rules
from app.easyread.goldenset import GoldenDocument

#: 기준선 파일. **버전 관리 대상이다** — 커밋되어 diff가 리뷰에 올라가는 것이 존재 이유다.
BASELINE_PATH = Path(__file__).parent / "baseline.json"

#: 기록 모드 스위치. 이 값이 켜진 실행은 **판정이 아니다**(모듈 docstring 1번).
RECORD_ENV = "GOLDEN_RECORD_BASELINE"

#: 기준선 파일에서 **내용이 아닌** 필드. 변경 판정에서 뺀다.
#: 여기에 실제 내용을 넣으면 그 변경이 조용해지고, 시각을 빼지 않으면 기록 실행이 **항상**
#: 변경으로 잡혀 아무것도 닫지 못한다 — 양쪽 다 게이트를 무력화한다(모듈 docstring 4번).
BASELINE_VOLATILE_FIELDS = frozenset({"recorded_at"})

#: 소스를 읽을 수 없을 때 판정 기준 지문에 남기는 값. **없음도 값이라 지문이 달라진다**
#: (fail-closed) — 축이 조용히 사라지는 경로를 두지 않는다.
_NO_SOURCE = "소스 없음"

#: diff에 적을 문서 id 최대 개수. 전부 적으면 실패 메시지가 수십 줄이 된다.
MAX_REPORTED_IDS = 8

#: **차단축의 이름.** 세 집단 중 이 축만 차단한다(2026-08-13 사용자 결정). 나머지 둘
#: (합성·실수집)은 판정에 참여하지 않고 리포트에 델타와 경고만 남긴다.
#:
#: `Measurement.groups()`가 이 상수를 쓴다 — 라벨이 두 벌이 되면 "차단하는 축"과 "리포트에
#: 전체라고 적히는 축"이 갈릴 수 있고, 갈리면 어느 쪽이 진짜 차단축인지 코드로 말할 수 없다.
BLOCKING_GROUP_LABEL: Final = "전체"

#: 차단축(`전체`)의 **허용 폭** — 기준선보다 이 건수까지 낮아도 차단하지 않는다.
#: 판정식은 `현재 전체 통과 < 기준선 전체 통과 - OVERALL_FLOOR_TOLERANCE`일 때만 차단이다.
#: 기준선 33이면 31·32·33은 통과하고 **30부터 막힌다.**
#:
#: **값의 근거는 실측이다.** 2026-08-13, **같은 커밋·같은 코퍼스(56건)·같은 판정 기준·같은
#: producer**(anthropic / claude-sonnet-5 / effort low)로 **코드를 한 줄도 바꾸지 않고**
#: 5회 돌린 표본이다.
#:
#: | 축 | 표본 (5회) | 평균 | 표준편차 | 폭 | 이항 sd |
#: |---|---|---|---|---|---|
#: | 전체 (n=56) | 33·31·32·33·33 | 32.4 | 0.89 | **2** | 3.70 |
#: | 합성 (n=20) | 16·16·17·14·16 | 15.8 | 1.10 | **3** | 1.82 |
#: | 실수집 (n=36) | 17·15·15·19·17 | 16.6 | 1.67 | **4** | 2.99 |
#:
#: 두 가지가 이 표에서 나왔다.
#:
#: - **폭 0(직전 기록 대비 하락 0)에서는 코드 무변경 4회 중 3회가 차단됐다**(첫 실행 33이
#:   기준선이므로 판정 대상은 4회다). 잡음이 매번 게이트를 막으면 사람이 게이트를 끈다.
#: - **부분이 전체보다 더 흔들린다** — 폭이 전체 2 < 합성 3 < 실수집 4다. 두 하위 축이 반대로
#:   움직여 상쇄되기 때문이다. run 4가 실물이다: 합성 14(최저)·실수집 19(최고)인데 전체는
#:   33으로 기준선과 **같았고**, 그런데도 합성 하나 때문에 차단됐다. 그래서 차단축을 전체
#:   하나로 좁혔다.
#: - 관측 sd가 세 축 모두 **이항 sd보다 작다**. 즉 이 변동은 품질 신호가 아니라 표본 잡음이다.
#:
#: **이 상수를 고치면 그 diff가 "게이트를 얼마나 열었는지"라는 신호로 리뷰에 올라가는 것이
#: 이 상수의 값어치다.** 값만 있고 근거가 없으면 다음 사람이 마음대로 올린다 — 그래서 표를
#: 여기 그대로 둔다. 올리려면 **새 표본을 실측해 이 표를 교체**하고, 그 실행 조건(커밋·코퍼스·
#: 판정 기준·producer)이 같았음을 함께 적어라. 표 없이 숫자만 올리는 diff는 근거가 없다.
OVERALL_FLOOR_TOLERANCE: Final = 2

#: **허용 폭이 못 잡게 된 것.** 차단 메시지와 **통과 메시지 양쪽에** 같은 문장을 싣는다 —
#: 게이트의 사각은 막힌 사람보다 **통과한 사람**이 알아야 한다. 완화를 코드에만 적고 출력에
#: 적지 않으면, 읽는 사람은 초록불을 "회귀가 없다"로 읽는다. 실제 뜻은 "차단축이 허용 폭
#: 안이다"이고 그 둘은 다르다.
TOLERANCE_BLIND_SPOTS: Final[tuple[str, ...]] = (
    f"  - ⚠ **이 게이트는 약하다(2026-08-13 완화).** 차단축은 `{BLOCKING_GROUP_LABEL}` 하나이고 "
    f"허용 폭은 {OVERALL_FLOOR_TOLERANCE}건이다. 아래 셋은 **통과한다**",
    f"    ① 전체가 {OVERALL_FLOOR_TOLERANCE}건 이하로 **진짜 회귀해도** 통과한다 — 33→31을 "
    "잡음과 회귀로 가르지 못한다. 기준선이 갱신되지 않으므로 31이 계속 나와도 매번 통과한다",
    "    ② **하위 축에만 갇힌 회귀는 차단하지 않는다** — 합성이 20/20 → 14/20으로 무너져도 "
    "실수집이 그만큼 오르면 전체가 유지되어 통과한다(2026-08-13 run 4가 그 형태였다: "
    "합성 14 최저 · 실수집 19 최고 · 전체 33 = 기준선)",
    f"    ③ 허용 폭 {OVERALL_FLOOR_TOLERANCE}는 **표본 5회의 관측 폭**이지 분포 추정이 아니다 "
    "— 6회째가 폭을 넓힐 수 있다",
    "  - 그래서 하위 축 델타는 차단하지 않아도 **항상 적는다**. 차단하지 않는 것과 안 보이게 "
    "하는 것은 다르다 — 판단은 사람이 한다",
)


class Verdict(StrEnum):
    """상대 하한선 판정 결과."""

    HELD = "유지"
    IMPROVED = "개선"
    REGRESSED = "하락"
    #: 낮아진 집단이 있지만 **차단하지 않았다** — 차단축(`전체`)이 허용 폭 안이거나,
    #: 내려간 것이 비차단 하위 축(합성·실수집)뿐이다. 2026-08-13 신설.
    #:
    #: `HELD`(직전 기록과 **같다**)와 섞지 않는다. 섞으면 "아무것도 안 내려갔다"와 "내려갔지만
    #: 봐줬다"가 한 값이 되어, 과거 기록의 `유지`가 무엇을 뜻했는지 알 수 없게 된다. 기존 값의
    #: 뜻을 바꾸는 대신 값을 **더한** 이유가 이것이다.
    TOLERATED = "하락 허용"
    INCOMPARABLE = "비교 불가"
    #: 변환에 실패해 판정하지 못한 문서가 있다 — **수치가 나왔어도 측정이 아니다.**
    UNMEASURED = "측정 미완"
    ABSENT = "기준선 없음"
    RECORDED = "기록 실행"


# --------------------------------------------------------------------------- 지문


def _canonical(value: object) -> Any:
    """해시 대상을 결정적인 JSON 값으로 편다.

    집합은 정렬하고(순서가 의미 없다), 정규식은 패턴 문자열과 플래그로 편다.
    편법으로 `repr`에 기대는 자리를 남기지 않기 위해 마지막에만 `repr`로 떨어뜨린다 —
    거기 떨어지는 타입이 생기면 해시가 파이썬 구현 세부에 묶이므로 위쪽에 규칙을 더한다.
    """
    if isinstance(value, re.Pattern):
        return {"pattern": value.pattern, "flags": int(value.flags)}
    if isinstance(value, Mapping):
        return {str(key): _canonical(item) for key, item in sorted(value.items(), key=_sort_key)}
    if isinstance(value, frozenset | set):
        return sorted((_canonical(item) for item in value), key=_json_key)
    if isinstance(value, list | tuple):
        return [_canonical(item) for item in value]
    if isinstance(value, str | int | float | bool) or value is None:
        return value
    return repr(value)


def _sort_key(pair: tuple[Any, Any]) -> str:
    return str(pair[0])


def _json_key(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def sha256_of(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()


def _text_digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def corpus_payload(documents: list[GoldenDocument]) -> list[dict[str, Any]]:
    """코퍼스 지문의 원재료. **본문·팩트 리터럴을 그대로 담지 않는다**(해시만 남긴다).

    담는 것: 문서 id, 합성 여부, 본문 해시, required_facts 해시.
    - 문서를 **빼거나 넣으면** id 집합이 바뀐다 — 이것이 막으려는 주 경로다.
    - 본문이 바뀌면 난도가 바뀌므로 수치가 비교 불가다.
    - required_facts가 바뀌면 필수 정보 보존 게이트의 판정 대상 자체가 바뀐다.
    - 합성/실수집 구분이 바뀌면 집단별 수치의 분모가 바뀐다.

    제목·카테고리는 넣지 않는다 — 채점에 쓰이지 않아서, 오타 수정 한 번에 "비교 불가"가
    되는 대가만 치르게 된다.
    """
    return [
        {
            "id": document.id,
            "synthetic": document.synthetic,
            "source_sha256": _text_digest(document.source_text),
            # 구분자 문자를 고르는 대신 JSON 배열로 직렬화한다 — 팩트 리터럴에 어떤
            # 구분자가 들어 있어도 경계가 흐려지지 않고, 손으로 해시 충돌을 만들 수 없다.
            "facts_sha256": _text_digest(
                json.dumps(
                    [[fact.canonical, *fact.accept] for fact in document.required_facts],
                    ensure_ascii=False,
                )
            ),
        }
        for document in sorted(documents, key=lambda document: document.id)
    ]


def _criteria_modules(module: ModuleType | None = None) -> list[ModuleType]:
    """자[尺]에 참여하는 모듈 전량. **여기 없는 것은 지문이 보지 않는다.**

    셋을 걷는다.

    1. `style_rules` — 규칙 상수·정규식·검사 함수. 자의 본체다.
    2. `goldenset` — 팩트 잔존 판정(`RequiredFact.retained_in`)과 문서 로딩 규칙. 통과율의
       세 조건 중 하나가 이 판정을 부르므로 자의 일부다.
    3. `evaluation` — 통과율의 정의(무엇을 실패로 세고 무엇을 분모에 넣는가).

    `evaluation`은 **호출 시점에** 들여온다. 그 모듈이 이 모듈의 `Measurement`를 쓰기 때문에
    모듈 최상단에서 들여오면 순환이 된다.

    `module`은 `style_rules` 자리를 갈아 끼우는 시험용 손잡이다. 기본 인자로 모듈을 직접
    적지 않고 호출 시점에 찾는 이유도 같다 — 참조가 정의 시점에 굳으면 규칙이 바뀐 상황을
    테스트로 재현할 수단이 사라지고, 재현할 수 없는 게이트는 있는지 없는지 확인할 수 없다.
    """
    from tests.golden import evaluation

    return [module if module is not None else style_rules, goldenset, evaluation]


def criteria_payload(module: ModuleType | None = None) -> dict[str, Any]:
    """판정 기준 지문의 원재료 — 자에 참여하는 모듈의 **값과 코드 전량**.

    **목록을 손으로 적지 않는다.** 모듈 단위로 걷으므로 규칙 축이 새로 생기면(2026-08-09의
    뜻풀이 축자 삽입처럼) 자동으로 지문에 들어온다. 손으로 적은 목록은 바로 그때 갱신되지
    않아 "자가 바뀌었는데 비교는 성립"을 만든다.

    두 축으로 걷는다. **경계는 아래 두 함수의 docstring에 적어 둔다** — 무엇을 걷지 않는지가
    곧 이 게이트의 사각이므로, 읽는 사람이 코드에서 바로 확인할 수 있어야 한다.

    - **값 축**(`_module_values`) — 모듈 전역의 *값*.
    - **코드 축**(`_module_source`) — 값으로 노출되지 않는 판정(함수 본문·인라인 임계값).

    2026-08-12 교차 리뷰 전에는 값 축만, 그것도 `[A-Z][A-Z0-9_]*` 이름만 걸었다.
    `style_rules` 전역 28개 중 12개만 지문에 들어왔고, `_SENTENCE_SPLIT` 하나만 바꿔 56문서의
    문장 길이 위반을 375→20건(-94.7%)으로 몰아도 `criteria_sha256`가 바이트 동일했다.
    `check_style` 본문에서 길이 검사를 빼면 375→0건이 되는데 역시 동일했다. **이름 규칙은
    자의 경계가 아니다.**
    """
    return {
        _module_key(target): {
            "values": _module_values(target),
            "source_sha256": _module_source(target),
        }
        for target in _criteria_modules(module)
    }


def _module_key(module: ModuleType) -> str:
    name = getattr(module, "__name__", None)
    return name if isinstance(name, str) else "<이름 없는 모듈>"


def _module_values(module: ModuleType) -> dict[str, Any]:
    """모듈 전역의 **값**. 이름 규칙으로 거르지 않는다 — `_` 접두도 자의 일부다.

    걷지 않는 것 셋.

    - **던더** — `__file__`은 체크아웃 경로라 지문이 기계마다 갈리고, `__doc__`은 문서를
      고칠 때마다 "비교 불가"를 만든다. 둘 다 판정을 움직이지 않는다.
    - **모듈 객체** — 그 모듈의 값이 아니라 남의 이름공간이다.
    - **호출 가능 객체** — 함수·클래스는 값이 아니라 코드라 아래 소스 축이 받는다. 수입한
      함수·클래스(`re.compile`, `BaseModel`)가 여기서 함께 빠지는 것은 의도다. 그것들은
      이 저장소의 자가 아니며, 넣으면 라이브러리 버전이 지문을 흔든다.
    """
    values: dict[str, Any] = {}
    for name in sorted(vars(module)):
        if name.startswith("__"):
            continue
        value = getattr(module, name)
        if isinstance(value, ModuleType) or callable(value):
            continue
        values[name] = _canonical(value)
    return values


def _module_source(module: ModuleType) -> str:
    """모듈 소스를 AST로 정규화한 digest — 값으로 노출되지 않는 판정이 여기 들어온다.

    `check_style`의 인라인 임계값, `evaluate_rules`의 조건 목록, `retained_in`의 비교식처럼
    **상수를 건드리지 않고 판정을 바꾸는 편집**이 이 축에 걸린다.

    **주석·서식·docstring은 지문을 움직이지 않는다**(`ast.unparse` + docstring 제거).
    이 저장소의 판정 코드는 근거를 긴 docstring으로 남기는 규약이라, 문서를 고칠 때마다
    기준선을 다시 기록해야 하면 게이트를 끄게 된다 — 과민한 지문은 '항상 비교 불가'라는
    반대 방향 고장이다.
    """
    path = getattr(module, "__file__", None)
    if not isinstance(path, str):
        return _NO_SOURCE
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return _NO_SOURCE
    return _text_digest(_normalized_source(text))


_Documented = ast.Module | ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef


def _normalized_source(text: str) -> str:
    tree = ast.parse(text)
    for node in ast.walk(tree):
        if isinstance(node, _Documented):
            _strip_docstring(node)
    return ast.unparse(tree)


def _strip_docstring(node: _Documented) -> None:
    first = node.body[0] if node.body else None
    if (
        isinstance(first, ast.Expr)
        and isinstance(first.value, ast.Constant)
        and isinstance(first.value.value, str)
    ):
        node.body = node.body[1:] or [ast.Pass()]


class RunContext(BaseModel):
    """비교의 조건.

    2026-08-13 이전에는 이 객체 **전체**가 "판정에 쓰지 않는다"였다. 그 상태에서 anthropic으로
    기록한 기준선과 openai 실행이 비교 가능으로 읽혀 수치 판정이 났고, 사용자 결정으로
    provider·관측 모델·effort가 지문의 producer 축이 됐다(`Producer`). 남은 필드
    (`judge_provider`·설정값 `model`)는 여전히 **기록만** 한다 — judge는 비차단축이라 하한선의
    구성요소가 아니고, 설정값은 주장이라 증거가 아니다.

    이 클래스가 `Fingerprint` **위**에 있는 것은 그래서다. 지문이 이 값을 원재료로 받는다.
    """

    model_config = ConfigDict(extra="forbid")

    provider: str
    judge_provider: str | None = None
    #: 설정값(`settings.llm_model`). **주장이지 증거가 아니다** — 별칭 해석·폴백이
    #: 있으면 실제로 쓴 모델과 다를 수 있다. 그래서 지문에는 이 값이 아니라 아래 관측값이 간다.
    model: str | None = None
    #: 변환 응답이 **실제로 보고한** 모델(`LLMResponse.model`). 이쪽이 증거다.
    observed_models: list[str] = []
    #: 같은 모델이라도 결과를 크게 움직인다(Anthropic 전용). 값이 비어도 기록은 남기지만
    #: **기본값은 두지 않는다** — 값의 부재(`None`)와 키의 부재는 다르고, 기본값이 있으면
    #: 디스크에서 읽을 때 그 구분이 조용히 사라진다. `write_baseline`이 쓰기 경계에서
    #: 키 부재를 거부하는데(아래 G4), 기본값을 두면 **읽기 경계에는 같은 불변식이 없어서**
    #: 키가 빠진 파일이 `effort=None`인 정상 기준선으로 복구된다(2026-08-13 독립 검증 A-3,
    #: 실측 확인). 기본값을 없애면 그 파일은 `load_baseline`에서 검증 실패 → `None` →
    #: "기준선 없음"(차단)이 된다. 쓰기에서 요구하는 것을 읽기에서도 요구하게 만드는 한 글자다.
    effort: str | None


#: 관측 모델이 하나도 없을 때 producer 라벨에 적는 값. **없음도 값이다** — 빈 문자열로
#: 두면 라벨이 `anthropic//effort=...` 가 되어 사람이 읽을 때 사라진 것처럼 보인다.
NO_MODEL_LABEL = "모델 없음"

#: effort가 설정되지 않았을 때 라벨에 적는 값. 같은 이유로 빈 자리를 남기지 않는다.
NO_EFFORT_LABEL = "없음"


def _models_label(models: list[str]) -> str:
    """관측 모델 목록의 표시형. 정상 실행에서는 한 개다."""
    return "+".join(models) if models else NO_MODEL_LABEL


class Producer(BaseModel):
    """이 수치를 **만든 것** — 변환 provider·관측 모델·effort.

    **해시가 아니라 값이다.** 다른 두 축은 원재료(56문서 본문·모듈 소스 전량)가 커서 접었을
    뿐이고, 여기서 접으면 얻는 것 없이 drift 메시지만 벙어리가 된다. 이 저장소는 "해시는
    아무것도 말해 주지 않는다"를 이미 교훈으로 적었다.

    `RunContext`의 세 필드를 **복사해** 담는다. 같은 값이 기준선 파일에 두 번(지문과 context)
    실리는 것은 의도다 — 지문은 기계가 비교하는 면이고 context는 사람이 읽는 기록이라, 둘을
    합치면 "비교에 쓰이는 값"과 "참고로 적는 값"의 경계가 흐려진다.

    **둘이 갈리지 않는 근거는 호출 규약이 아니라 `Baseline`의 불변식이다**(2026-08-13 수정).
    도입 당시에는 "한 `RunContext`에서 둘 다 유도하니 갈리지 않는다"로 두고 가드를 더하지
    않았고, 갈려도 "비교는 지문이 하므로 판정은 안전하고 사람이 읽는 기록만 틀어진다"고 봤다.
    **그 평가가 틀렸다** — 독립 검증이 재현했다. 지문 `anthropic/model-a`, context
    `openai/model-b`인 본문이 writer를 통과했고, 다음 model-a 실행이 지문만 대조해 그 파일을
    비교 가능으로 읽었다. **틀어진 것은 기록이 아니라 하한선 자체다** — model-b로 잰 낮은
    수치가 model-a 실행의 하한선이 된다. 지문도 context도 "이 수치를 누가 냈는가"를 스스로
    증명하지 못한다. 그 결속은 *기록하는 호출*이 만드는데, 두 자리가 갈린 파일은 그 호출이
    무엇을 결속했는지 말할 수 없다.

    그래서 검사를 얹지 않고 **그 상태를 표현할 수 없게** 했다 —
    `Baseline._producer는_context와_같아야_한다`가 쓰기(모델 조립)와 읽기(디스크 검증) 양쪽에서
    같은 불변식을 건다. 손으로 조립한 dict는 모델을 거치지 않으므로 `write_baseline`의 G5가
    같은 것을 한 번 더 본다.
    """

    model_config = ConfigDict(extra="forbid")

    provider: str
    #: 변환 응답이 실제로 보고한 모델 **전량**. 정상 실행에서는 1건이다 — `write_baseline`의
    #: fail-closed 가드가 빈 목록과 섞인 목록을 거부한다. 다만 **그 가드는 기록 경로에만
    #: 있고 판정 경로에는 없다.** 그래서 여기서는 단일 값으로 접지 않고 목록을 사실대로
    #: 담는다. 전건 변환 실패(빈 목록)나 모델이 섞인 실행은 어떤 단일 모델 기준선과도 값이
    #: 달라 비교 불가로 떨어지는데, 그것이 맞는 결과다 — 없는 모델을 있는 것처럼 접거나
    #: 섞인 것 중 하나를 고르면 그 실행이 남의 하한선을 통과해 버린다.
    observed_models: list[str]
    effort: str | None

    @classmethod
    def of(cls, context: RunContext) -> "Producer":
        return cls(
            provider=context.provider,
            observed_models=list(context.observed_models),
            effort=context.effort,
        )

    def label(self) -> str:
        return (
            f"{self.provider}/{_models_label(self.observed_models)}"
            f"(effort={self.effort or NO_EFFORT_LABEL})"
        )


def _producer_gaps(fingerprint: Producer, context: Producer) -> list[str]:
    """지문 쪽 producer와 context 쪽 producer가 **어느 성분에서** 갈렸는지.

    빈 목록이면 결속돼 있다. `Fingerprint._producer_difference`와 방향이 다르다 — 저쪽은
    *두 실행*(기준선 대 현재)을 비교하고, 이쪽은 *한 파일 안의 두 자리*를 비교한다.
    """
    gaps: list[str] = []
    if fingerprint.provider != context.provider:
        gaps.append(f"  - provider: 지문 {fingerprint.provider} / context {context.provider}")
    if fingerprint.observed_models != context.observed_models:
        gaps.append(
            f"  - 관측 모델: 지문 {_models_label(fingerprint.observed_models)} / "
            f"context {_models_label(context.observed_models)}"
        )
    if fingerprint.effort != context.effort:
        gaps.append(
            f"  - effort: 지문 {fingerprint.effort or NO_EFFORT_LABEL} / "
            f"context {context.effort or NO_EFFORT_LABEL}"
        )
    return gaps


#: producer 결속이 깨졌을 때의 설명. 쓰기·읽기·writer 세 자리가 같은 문장을 쓴다 —
#: 어디서 걸렸든 사람이 해야 할 일이 같기 때문이다.
PRODUCER_BOND_NOTE = (
    "  - 지문과 context는 **같은 실행의 조건**이어야 한다. 갈리면 이 수치를 누가 냈는지 "
    "말할 수 없다 — 비교는 지문이 하므로, 갈린 파일은 *한쪽 producer의 수치를 다른 쪽 "
    "producer의 하한선으로* 세운다(2026-08-13 독립 검증이 재현했다). 사람이 읽는 기록만 "
    "틀어지는 문제가 아니다\n"
    "  - 닫는 방법: 측정치와 **같은 실행의** `RunContext` 하나에서 지문과 context를 둘 다 "
    "유도한다(`Fingerprint.of(documents, context)`와 `baseline_body(..., context)`에 같은 "
    "객체를 넘긴다). 값을 맞춰 덮어쓰지 마라 — 어느 쪽이 진짜 조건인지는 호출자만 안다"
)


class Fingerprint(BaseModel):
    """ "이 수치가 무엇을 재서 나온 것인가"의 지문.

    세 축을 **따로** 둔다. 합쳐 하나로 만들면 비교 불가일 때 코퍼스가 바뀐 것인지 규칙이
    바뀐 것인지 만든 쪽이 바뀐 것인지 알 수 없고, 그 구분이 곧 다음 행동(문서 편입 절차 /
    규칙 변경 리뷰 / 같은 provider·모델로 재실행)을 가른다.
    """

    model_config = ConfigDict(extra="forbid")

    corpus_sha256: str
    criteria_sha256: str
    #: 세 번째 축 — **값 그대로** 담는다(해시하지 않는다). 근거는 모듈 docstring ③.
    producer: Producer
    document_count: int
    synthetic_count: int
    collected_count: int
    #: 사람이 diff로 읽을 수 있게 남긴다 — 해시만으로는 "무엇이 빠졌는지"를 알 수 없다.
    document_ids: list[str]

    @classmethod
    def of(cls, documents: list[GoldenDocument], context: RunContext) -> "Fingerprint":
        """지문을 만든다. `context`는 **선택 인자가 아니다.**

        기본값을 주면 producer 축이 비거나 고정값으로 채워진 지문을 만들 수 있고, 그런 지문은
        어떤 실행과도 producer가 맞아떨어져 축이 조용히 죽는다 — 축을 추가하기 전 상태로
        되돌아가는 데 인자 하나를 빠뜨리면 충분해진다. 호출자는 이미 측정치와 같은 실행의
        `RunContext`를 들고 있다.
        """
        return cls(
            corpus_sha256=sha256_of(corpus_payload(documents)),
            criteria_sha256=sha256_of(criteria_payload()),
            producer=Producer.of(context),
            document_count=len(documents),
            synthetic_count=sum(document.synthetic for document in documents),
            collected_count=sum(not document.synthetic for document in documents),
            document_ids=sorted(document.id for document in documents),
        )

    def differences(self, other: "Fingerprint") -> list[str]:
        """self(현재)가 other(기준선)와 어디서 갈리는지. 빈 목록이면 비교 가능하다."""
        found: list[str] = []
        if self.corpus_sha256 != other.corpus_sha256:
            found.append(self._corpus_difference(other))
        if self.criteria_sha256 != other.criteria_sha256:
            found.append(
                "- **판정 기준이 바뀌었다** — 규칙 정의(`app/easyread/style_rules.py`)·팩트 "
                "잔존 판정(`app/easyread/goldenset.py`)·통과율 정의"
                "(`tests/golden/evaluation.py`) 중 하나가 "
                f"기준선 기록과 다르다 (기준선 {other.criteria_sha256[:12]} / 현재 "
                f"{self.criteria_sha256[:12]}). 자가 바뀌면 같은 변환문도 다른 점수를 받는다 "
                "— 문장 길이 상한을 풀거나 사전을 넓히는 것은 문서를 빼는 것과 구조가 같은 "
                "우회다. 규칙 변경 자체는 정상 작업이며, 필요한 것은 재기록뿐이다"
            )
        if self.producer != other.producer:
            found.append(self._producer_difference(other))
        return found

    def _producer_difference(self, other: "Fingerprint") -> str:
        """**무엇이** 달라졌는지 이름으로 적는다 — 해시를 쓰지 않은 이유가 이 메시지다."""
        before, now = other.producer, self.producer
        parts = [
            "- **수치를 만든 것(producer)이 바뀌었다** — 기준선 "
            f"`{before.label()}` / 현재 `{now.label()}`"
        ]
        if before.provider != now.provider:
            parts.append(f"  - provider: {before.provider} → {now.provider}")
        if before.observed_models != now.observed_models:
            parts.append(
                f"  - 관측 모델: {_models_label(before.observed_models)} → "
                f"{_models_label(now.observed_models)}"
            )
        if before.effort != now.effort:
            parts.append(
                f"  - effort: {before.effort or NO_EFFORT_LABEL} → {now.effort or NO_EFFORT_LABEL}"
            )
        parts.append(
            "  - 다른 provider·모델·effort로 낸 통과율끼리는 비교가 성립하지 않는다. 우리 코드를 "
            "한 줄도 고치지 않아도 값이 움직이므로, 그 비교로 나온 '유지'도 '하락'도 판정이 "
            "아니다 — 같은 조건으로 다시 돌리거나, 조건을 바꾼 것이 의도라면 새 조건의 "
            "기준선을 재기록한다"
        )
        return "\n".join(parts)

    def _corpus_difference(self, other: "Fingerprint") -> str:
        removed = [name for name in other.document_ids if name not in set(self.document_ids)]
        added = [name for name in self.document_ids if name not in set(other.document_ids)]
        parts = [
            "- **코퍼스 구성이 바뀌었다** — 기준선 "
            f"{other.document_count}건(합성 {other.synthetic_count}/실수집 "
            f"{other.collected_count}) / 현재 {self.document_count}건(합성 "
            f"{self.synthetic_count}/실수집 {self.collected_count})"
        ]
        if removed:
            parts.append(f"  - 빠진 문서 {len(removed)}건: {_shown(removed)}")
        if added:
            parts.append(f"  - 들어온 문서 {len(added)}건: {_shown(added)}")
        if not removed and not added:
            parts.append(
                "  - id 집합은 같고 **내용**이 다르다 (본문 또는 required_facts가 바뀌었다)"
            )
        parts.append(
            "  - 문서를 빼면 통과율은 실력과 무관하게 오른다. 그 상태로 '직전보다 낮지 않다'를 "
            "충족해도 판정이 아니다 — 재기록해서 새 구성의 기준선을 세운다"
        )
        return "\n".join(parts)


def producer_bond_error(fingerprint: Fingerprint, context: RunContext) -> str | None:
    """지문의 producer와 `context`가 갈렸으면 설명, 결속돼 있으면 `None`.

    두 곳이 같은 규칙을 쓴다 — 커밋되는 기준선(`Baseline`)과 사람이 읽는 실행 리포트
    (`tests/golden/report.py`의 `GoldenRunReport`). 둘 다 같은 값을 두 자리(기계가 비교하는
    지문 / 사람이 읽는 기록)에 싣는 **같은 모양의 이중 기록**이라, 규칙도 하나여야 한다.
    한쪽에만 적으면 다른 쪽이 규칙 밖에 남고, 그 상태가 무엇을 뜻하는지는 이미 실측으로
    확인됐다(`Producer` docstring).
    """
    recorded = Producer.of(context)
    gaps = _producer_gaps(fingerprint.producer, recorded)
    if not gaps:
        return None
    return "\n".join(
        [
            "지문의 producer와 context가 갈렸다: "
            f"지문 `{fingerprint.producer.label()}` / context `{recorded.label()}`",
            *gaps,
            PRODUCER_BOND_NOTE,
        ]
    )


def _shown(ids: list[str]) -> str:
    head = ", ".join(ids[:MAX_REPORTED_IDS])
    return f"{head} 외 {len(ids) - MAX_REPORTED_IDS}건" if len(ids) > MAX_REPORTED_IDS else head


# --------------------------------------------------------------------------- 측정치


class GroupMeasurement(BaseModel):
    """집단 하나의 규칙 기반 통과 측정치."""

    model_config = ConfigDict(extra="forbid")

    documents: int
    passed: int
    #: 변환에 실패해 **판정하지 못한** 문서 수. 분모(`documents`)에는 그대로 남아 있고
    #: 분자(`passed`)에는 들어가지 않는다 — 즉 이 수만큼의 0이 수치 안에 섞여 있다.
    #:
    #: **기본값을 두지 않는다.** 키가 빠진 기준선 파일이 `unmeasured=0`인 정상 기준선으로
    #: 복구되면, 쓰기에서 막은 것을 읽기가 만들어 주는 상태가 된다 — `RunContext.effort`가
    #: 정확히 그 형태로 열려 있었다(2026-08-13 독립 검증 A-3). 필수 필드면 그 파일은
    #: `load_baseline`에서 검증 실패 → `None` → "기준선 없음"(차단)이 된다.
    unmeasured: int

    @property
    def pass_rate(self) -> float:
        return self.passed / self.documents if self.documents else 0.0

    def dropped_from(self, baseline: "GroupMeasurement") -> bool:
        """조금이라도 낮아졌는가. **차단이 아니라 기록·경고용이다**(2026-08-13부터).

        분모가 달라도 성립하도록 교차 곱으로 비교한다(분모가 같은 것은 지문이 보장한다).
        """
        return self.passed * baseline.documents < baseline.passed * self.documents

    def dropped_beyond(self, baseline: "GroupMeasurement", tolerance: int) -> bool:
        """**허용 폭을 넘겨** 낮아졌는가 — 차단 판정은 이쪽만 본다.

        분모가 같을 때(지문이 보장한다) `passed < baseline.passed - tolerance`와 같다.
        허용 폭은 **건수**이므로 분모가 다르면 뜻을 잃는데, 그 상태는 지문이 먼저 비교 불가로
        떨어뜨린다. 그래도 교차 곱을 유지하는 이유는 `dropped_from`과 같은 식 위에 서 있어야
        `tolerance=0`이 정확히 `dropped_from`이 되기 때문이다 — 허용 폭을 0으로 되돌리면
        완화 이전 동작으로 돌아간다는 것이 식에서 바로 읽힌다.
        """
        return self.passed * baseline.documents < (baseline.passed - tolerance) * self.documents


class Measurement(BaseModel):
    """한 실행의 규칙 기반 통과 측정치.

    **합성과 실수집을 나눠 담는다.** 두 집단은 분포가 달라(합성 스타일 위반 0/20 대 실수집
    11/36, `02_quality-baseline.md` §5.3) 합친 평균이 어느 집단도 대표하지 않는다.

    **차단은 그중 `전체` 하나만 한다**(2026-08-13 사용자 결정, `OVERALL_FLOOR_TOLERANCE`).
    셋을 각각 차단하던 이유("전체만 보면 합성이 실수집의 하락을 상쇄해 회귀가 숨는다")는
    사실이고 지금도 사실이다 — 그 회귀는 이제 **차단되지 않고 경고로만 남는다**
    (`TOLERANCE_BLIND_SPOTS` ②). 바꾼 근거는 그 논리가 틀려서가 아니라 실측이다: 5회 표본에서
    폭이 전체 2 < 합성 3 < 실수집 4로, **하위 축이 전체보다 더 흔들려** 상쇄가 아니라 잡음이
    먼저 잡혔다. 셋을 나눠 담는 것 자체는 그대로다 — 판정에서 내렸을 뿐 리포트에는 계속 싣는다.
    """

    model_config = ConfigDict(extra="forbid")

    overall: GroupMeasurement
    synthetic: GroupMeasurement
    collected: GroupMeasurement

    def groups(self) -> list[tuple[str, GroupMeasurement]]:
        return [
            (BLOCKING_GROUP_LABEL, self.overall),
            ("합성", self.synthetic),
            ("실수집", self.collected),
        ]

    def unmeasured_gaps(self) -> list[str]:
        """판정하지 못한 문서가 있는 집단. 빈 목록이면 **전건을 실제로 쟀다.**

        집단별로 적는 이유는 2026-08-13 사고의 모양이 그것이었기 때문이다 — 합성 20건은
        전부 변환됐고 실수집 36건만 전멸했다. 전체 수 하나로 접으면 "36건이 안 됐다"까지만
        보이고, **어느 축이 통째로 비었는가**가 사라진다. 그 구분이 곧 다음 행동을 가른다.
        """
        return [
            f"  - {label}: {group.unmeasured}/{group.documents}건을 변환하지 못해 판정하지 못했다"
            for label, group in self.groups()
            if group.unmeasured
        ]


#: 측정하지 못한 문서가 섞였을 때의 설명. 쓰기(모델·writer)와 판정 세 자리가 같은 문장을
#: 쓴다 — 어디서 걸렸든 사람이 해야 할 일이 같기 때문이다.
NOT_A_MEASUREMENT_NOTE = (
    "  - **실패는 측정이 아니다.** 변환하지 못한 문서는 통과 0으로 분모에 남지만 그 0은 "
    "품질이 아니라 부재다. 그 수치를 하한선으로 세우면 두 방향이 함께 고장난다 — ① 전건을 "
    "변환한 정상 실행이 **거짓 차단**되고(부재로 눌린 집단이 회복되면 다른 집단이 상대적으로 "
    "내려간다) ② 같은 전멸이 재발해도 0 이상이라 **'유지'로 통과**한다. 그 축은 영구히 열린다\n"
    "  - 2026-08-13 실측이 정확히 이 형태였다: 합성 20건 변환 성공 · 실수집 36건 전건 "
    "HTTP 400. 지문은 건강한 실행과 한 글자도 다르지 않았고 producer 축 가드 4개가 전부 "
    "통과했다 — 관측 모델은 성공한 20건에서만 모이기 때문이다\n"
    "  - 닫는 방법: 변환이 왜 실패했는지(크레딧·키·네트워크)를 고친 뒤 **전건 변환에 성공한 "
    "실행**으로 다시 돌린다. 실패한 문서를 코퍼스에서 빼는 것은 분모 우회이지 해결이 아니다"
)


class JudgeObservation(BaseModel):
    """judge 관측치 — **기록만 하고 비교하지 않는다.**

    채점 모델을 고정할 수단이 없어(`GOLDEN_JUDGE_PROVIDER`는 벤더만 고르고 모델은
    `settings.llm_model`을 따른다) 실행 간 절대 비교가 성립하지 않는다
    (`02_quality-baseline.md` §6.5, `2026-08-08-golden-reeval-gpt41.md` §6-2).
    그래도 파일에 남기는 이유는 git 이력으로 추세를 볼 수 있게 하기 위해서다.
    """

    model_config = ConfigDict(extra="forbid")

    scored: int
    documents: int
    fidelity_mean: float
    readability_mean: float
    low_fidelity_ids: list[str]


class Baseline(BaseModel):
    """기준선 파일의 내용.

    **judge 관측은 여기 없다**(2026-08-13 제거). judge 는 비차단축이라(2026-08-12 결정)
    애초에 **하한선의 구성요소가 아니다** — 채점 모델을 고정할 수단이 없어
    (`GOLDEN_JUDGE_PROVIDER`는 벤더만 고르고 모델은 `settings.llm_model`을 따른다)
    우리 코드를 한 줄도 고치지 않아도 모델이 바뀌면 값이 움직인다. 하한선은 **우리가
    고정할 수 있는 것**으로만 서야 한다.

    하한선에 있을 이유가 없는 값을 넣은 탓에 "이 judge 수치가 어느 실행에서 나왔는가"라는
    출처 문제가 생겼고, 그 자리에 검사를 **여섯 번** 얹었다. 그릇(리포트)은 구조로 묶을 수
    있어도 **나중에 대입되는 내용물(`report.judge`)의 출처는 구조로 묶이지 않는다** —
    리포트는 세워진 뒤에 judge 테스트가 관측을 대입하므로, 세울 때 건 결속이 나중에 들어온
    값까지 보증하지 못한다. 그래서 일곱 번째 검사를 얹지 않고 **값 자체를 뺐다.**

    **정보는 잃지 않는다** — judge 관측은 실행 리포트(`tests/golden/report.py`)에 그대로
    남는다. 평균·커버리지·저충실성 지목이 매 실행 렌더에 찍히고 경고로도 올라간다.
    사라진 것은 *커밋되는 하한선 파일의 한 필드*이지 관측 자체가 아니다.

    **여기에 judge 를 다시 넣지 마라.** 넣는 순간 출처 문제가 되살아나고, 우리가 고정할 수
    없는 값이 커밋되는 하한선 파일의 무결성을 좌우하게 된다.
    """

    model_config = ConfigDict(extra="forbid")

    fingerprint: Fingerprint
    measurement: Measurement
    context: RunContext
    note: str = ""

    @model_validator(mode="after")
    def _producer는_context와_같아야_한다(self) -> "Baseline":
        """지문의 producer와 `context`의 producer 성분이 갈린 기준선은 **만들어지지 않는다.**

        불변식을 이 자리에 둔 이유는 **두 경계가 한 규칙으로 닫히기 때문**이다.

        - **쓰기**: `baseline_body`가 하는 첫 일이 이 모델을 조립하는 것이라, 갈린 조합은
          본문 dict가 생기기 **전에** 거부된다. 본문을 만든 뒤에 막으면 "무엇을 쓰려 했는가"가
          이미 남의 조건으로 조립된 뒤라, 가드 한 겹을 걷어내는 편집에 그대로 노출된다.
        - **읽기**: `load_baseline`이 디스크 본문을 이 모델로 검증하므로, 갈린 파일은
          `None`(=기준선 없음, 차단)이 된다. 쓰기만 막으면 이전 코드가 남긴 파일이나 손으로
          고친 파일이 그대로 하한선이 된다.

        **값을 맞춰 조용히 덮어쓰지 않는다.** `fingerprint.producer`로 `context`를 갈아
        끼우면 호출자의 실수가 가려지고, 그 실수는 "측정치가 어느 실행의 것인가"에 대한
        것이라 가릴수록 나빠진다. 어긋남은 드러나야 한다.

        `judge_provider`·설정값 `model`은 보지 않는다 — producer 축에 없는 필드라
        지문에 실릴 이유가 없고, 넣으면 판정과 무관한 변경이 기록을 거부하게 된다.
        """
        error = producer_bond_error(self.fingerprint, self.context)
        if error:
            raise ValueError(f"기준선이 자기모순이다 — {error}")
        return self

    @model_validator(mode="after")
    def _측정하지_못한_실행은_기준선이_아니다(self) -> "Baseline":
        """변환에 실패한 문서가 하나라도 있으면 그 실행의 수치는 **기준선이 되지 못한다.**

        2026-08-13 사고가 이 자리를 지나갔다. 합성 20건은 변환에 성공하고 실수집 36건이
        전부 HTTP 400(크레딧 소진)으로 실패한 실행이, 기존 가드 넷(provider 비어 있음·관측
        모델 없음·모델 섞임·effort 키 부재)을 **전부 통과**했다. 관측 모델은 성공한 20건
        에서만 모이므로 지문이 건강한 실행과 한 글자도 다르지 않았기 때문이다. 그날 그
        파일이 하한선이 되는 것을 막은 것은 **사람이 손으로 되돌린 것뿐**이었다.

        불변식을 이 자리에 둔 이유는 producer 결속과 같다 — **두 경계가 한 규칙으로 닫힌다.**

        - **쓰기**: `baseline_body`가 하는 첫 일이 이 모델을 조립하는 것이라, 미측정이 섞인
          측정치는 본문 dict가 생기기 **전에** 거부된다.
        - **읽기**: `load_baseline`이 디스크 본문을 이 모델로 검증하므로, 이 가드가 없던
          시절에 기록됐거나 손으로 고친 파일은 `None`(=기준선 없음, 차단)이 된다.

        **검사가 아니라 구조인 이유는 값이 어디서 오는가에 있다.** 판정에 쓰는 수는
        `measurement` 안에 있다 — 하한선으로 쓰이는 바로 그 필드다. 미측정 건수를 별도
        필드로 두고 대조했다면 그 필드는 기준선 파일에 실리지 않았을 것이고(실제로
        `conversion_failures`가 그렇다), 실리지 않은 값은 다음 실행이 확인할 수 없다.

        **`fact_losses`·`conversion_failures` 같은 다른 축은 보지 않는다.** 여기서 막는
        것은 "이 수치가 측정인가"이지 "이 수치가 좋은가"가 아니다. 품질이 나쁜 실행의
        기준선은 정상적으로 기록돼야 한다 — 하한선은 현재 위치에서 출발한다.
        """
        gaps = self.measurement.unmeasured_gaps()
        if gaps:
            raise ValueError(
                "\n".join(
                    [
                        "기준선이 될 수 없다 — 측정하지 못한 문서가 있다",
                        *gaps,
                        NOT_A_MEASUREMENT_NOTE,
                    ]
                )
            )
        return self


BASELINE_NOTE = (
    "골든셋 규칙 기반 통과 측정치의 직전 기록이다. 합격 기준은 절대 수치가 아니라 "
    "'이 수치보다 낮아지지 않는다'이며, fingerprint가 다르면 비교 자체가 성립하지 않는다. "
    "갱신: GOLDEN_RECORD_BASELINE=1 로 tests/golden 을 -m llm 으로 실행한 뒤 diff를 커밋한다."
)


def baseline_body(
    fingerprint: Fingerprint,
    measurement: Measurement,
    context: RunContext,
) -> dict[str, Any]:
    """기준선 파일의 **내용**. 기록 시각은 여기 없다.

    이 함수가 따로 있는 이유는 "기준선이 바뀌었는가"를 **쓸 내용과 이전 내용의 차이**로
    재기 위해서다(모듈 docstring 2번). 지적 건수 같은 대리 지표를 쓰면 "파일을 새로
    만들고도 통과"가 성립한다.

    judge 인자는 2026-08-13에 없앴다 — 이유는 `Baseline` docstring에 있다.

    **`fingerprint`와 `context`가 같은 실행의 것이 아니면 여기서 끝난다.** 이 함수가 하는
    첫 일이 `Baseline` 조립이고 그 모델이 producer 결속을 불변식으로 걸고 있어(
    `Baseline._producer는_context와_같아야_한다`), 갈린 조합에서는 **본문 dict 자체가 생기지
    않는다.** 검사를 이 함수 안에 따로 적지 않은 것은 빠뜨려서가 아니라, 모델에 걸면
    `Baseline.model_validate`로 만드는 경로(테스트 헬퍼·`load_baseline`)까지 같은 규칙을
    받기 때문이다 — 여기에만 적으면 그 경로들이 규칙 밖에 남는다.
    """
    model = Baseline(
        fingerprint=fingerprint,
        measurement=measurement,
        context=context,
        note=BASELINE_NOTE,
    )
    body: dict[str, Any] = json.loads(model.model_dump_json())
    return body


def stored_body(path: Path = BASELINE_PATH) -> dict[str, Any] | None:
    """디스크에 있는 기준선의 내용. 없거나 읽을 수 없으면 `None`.

    `None`은 "변경 없음"이 **아니라** "비교할 이전 내용이 없다"는 뜻이다. 없음 → 있음도
    변경이고, 그 구분이 무너진 자리가 원장에서 첫 기록이 조용히 통과한 지점이다.
    """
    if not path.exists():
        return None
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(loaded, dict):
        return None
    return {key: value for key, value in loaded.items() if key not in BASELINE_VOLATILE_FIELDS}


def load_baseline(path: Path = BASELINE_PATH) -> Baseline | None:
    """기준선을 모델로 읽는다. 없거나 형식이 깨졌으면 `None`.

    형식이 깨진 것을 `None`으로 돌려주는 것은 관대함이 아니다 — 호출자는 `None`을
    "기준선 없음"(차단)으로 다루므로, 깨진 파일은 통과가 아니라 재기록 요구가 된다.
    """
    body = stored_body(path)
    if body is None:
        return None
    try:
        return Baseline.model_validate(body)
    except ValueError:
        return None


def baseline_changes(body: dict[str, Any], path: Path = BASELINE_PATH) -> list[str]:
    """이번 기록이 기준선을 **실제로** 바꾸는가. 바꾸지 않으면 빈 목록.

    반드시 `write_baseline()` **앞에서** 부른다 — 쓰고 나서 비교하면 언제나 같다.
    """
    before = stored_body(path)
    if before is None:
        what = "덮어쓴다(이전 파일을 읽을 수 없다)" if path.exists() else "새로 만든다"
        return [
            f"- **기준선을 {what}** — `{path}`",
            "  - 없음 → 있음도 변경이다. 지적이 0건이어도 이 실행은 게이트를 닫지 않는다",
        ]
    if before == body:
        return []
    return [
        f"- **`{field}` 가 바뀐다** — `{path}`"
        for field in sorted(set(before) | set(body))
        if before.get(field) != body.get(field)
    ]


def write_baseline(body: dict[str, Any], path: Path = BASELINE_PATH) -> Path:
    """기준선을 쓴다. `recorded_at`은 여기서만 붙는다(비교 대상이 아니다).

    **실행 조건이 비어 있으면 쓰지 않는다.** 2026-08-12에 `context.model`이 `None`인
    채로 기준선이 기록됐고(`settings.llm_model` 미설정), 그 값은 직전 저장 실행보다
    9%p 낮았다. 무엇으로 쟀는지 모르는 수치가 하한선이 되면 **그 하락이 정상이 된다** —
    다음 실행은 낮아진 값과 비교해 통과한다. `RunContext`가 "판정에 쓰지 않는다"인 것은
    맞지만, 그것은 *비교식*에 넣지 않는다는 뜻이지 *비어도 된다*는 뜻이 아니다.

    **G5(producer 결속)는 앞의 넷과 같은 층위다.** 이 함수는 모델이 아니라 dict를 받으므로
    `Baseline`의 불변식이 닿지 않는다 — `baseline_body`를 거치지 않고 손으로 조립한 body가
    실제로 여기 들어온다(바로 위 G1~G4를 붙잡는 음성 대조 넷이 그 형태다). 그 통로가 있는
    한 모델 쪽 불변식만으로는 "갈린 파일이 디스크에 생기지 않는다"가 성립하지 않는다.
    """
    context = body.get("context") if isinstance(body.get("context"), dict) else {}
    assert isinstance(context, dict)
    if not context.get("provider"):
        raise AssertionError("기준선을 쓰지 않는다 — `provider`가 비어 있다.")
    observed = context.get("observed_models") or []
    if not observed:
        raise AssertionError(
            "기준선을 쓰지 않는다 — **관측된 모델이 없다**. `settings.llm_model`은 설정값일 뿐 "
            "실제로 무엇이 응답했는지의 증거가 아니다. 변환 응답의 `LLMResponse.model`을 "
            "실어야 한다 — 그것이 없으면 이 수치가 무엇의 하한선인지 말할 수 없다."
        )
    if len(set(observed)) > 1:
        raise AssertionError(
            f"기준선을 쓰지 않는다 — 한 실행에서 **모델이 섞였다**: {sorted(set(observed))}. "
            "섞인 실행의 수치는 어느 모델의 하한선도 아니다."
        )
    if "effort" not in context:
        raise AssertionError(
            "기준선을 쓰지 않는다 — `effort`가 기록되지 않았다. 같은 모델이라도 effort가 "
            "결과를 크게 움직인다(2026-08-12 9%p 하락의 유력 원인). 값이 없으면 `null`로 "
            "**명시**하라 — 키의 부재와 값의 부재는 다르다."
        )
    fingerprint = body.get("fingerprint")
    stamped = fingerprint.get("producer") if isinstance(fingerprint, dict) else None
    claimed = {
        "provider": context.get("provider"),
        "observed_models": context.get("observed_models"),
        "effort": context.get("effort"),
    }
    if stamped != claimed:
        raise AssertionError(
            "기준선을 쓰지 않는다 — **지문의 producer와 context가 갈렸다**: "
            f"지문 {stamped} / context {claimed}.\n" + PRODUCER_BOND_NOTE
        )
    _write할_수_있는_측정치인가(body)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {**body, "recorded_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")}
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    return path


def _write할_수_있는_측정치인가(body: dict[str, Any]) -> None:
    """**G6** — 미측정이 섞인 본문은 쓰지 않는다. `Baseline` 불변식과 같은 규칙이다.

    G5(producer 결속)를 dict 경로에도 둔 이유가 그대로 적용된다. 이 함수는 모델이 아니라
    dict를 받으므로 `Baseline._측정하지_못한_실행은_기준선이_아니다`가 닿지 않고,
    `baseline_body`를 거치지 않고 손으로 조립한 body가 실제로 여기 들어온다(G1~G5를
    붙잡는 음성 대조들이 정확히 그 형태다).

    **키가 없는 것도 거부한다.** 없음을 0으로 읽으면 필드 하나를 지우는 것이 가드를 지나는
    방법이 된다 — G4(`effort`)와 "지문 없는 본문"에서 이미 같은 판단을 했다.

    가드 순서에서 **마지막**이다. 앞의 다섯은 `context`·`fingerprint`만 보는데, 그것들을
    붙잡는 음성 대조 본문에는 `measurement`가 아예 없다. 이 검사가 먼저 서면 그 본문들이
    전부 여기서 막혀 앞선 가드가 무엇을 막는지 확인할 수 없게 된다.
    """
    measurement = body.get("measurement")
    if not isinstance(measurement, dict):
        raise AssertionError(
            "기준선을 쓰지 않는다 — `measurement`가 없다. 하한선으로 세울 수치가 없으면 "
            "이 파일이 무엇의 기준선인지 말할 수 없고, 미측정 대조의 상대도 사라진다."
        )
    for label in ("overall", "synthetic", "collected"):
        group = measurement.get(label)
        count = group.get("unmeasured") if isinstance(group, dict) else None
        if not isinstance(count, int):
            raise AssertionError(
                f"기준선을 쓰지 않는다 — `measurement.{label}.unmeasured`가 없다. "
                "키의 부재는 0이 아니다 — 없으면 그 실행이 전건을 실제로 쟀는지 말할 수 없다."
            )
        if count:
            raise AssertionError(
                "기준선을 쓰지 않는다 — **측정하지 못한 문서가 있다**: "
                f"{label} {count}/{group.get('documents') if isinstance(group, dict) else '?'}건.\n"
                + NOT_A_MEASUREMENT_NOTE
            )


def recording_requested() -> bool:
    """기록 모드인가. 켜진 실행은 판정이 아니다."""
    return os.environ.get(RECORD_ENV, "").strip().lower() in {"1", "true", "yes", "on"}


# --------------------------------------------------------------------------- 판정


class FloorJudgement(BaseModel):
    """상대 하한선 판정.

    `blocking`과 `requires_record`를 나눈다 — 하락은 **차단**이고 개선은 **재기록 요구**다.
    개선을 차단으로 묶으면 LLM 잡음으로 오르내리는 수치가 매 실행 게이트를 막아, 결국
    게이트를 끄게 된다. 반대로 개선을 조용히 통과시키면 하한선이 영영 오르지 않는다.

    셋째 상태가 `Verdict.TOLERATED`다 — **하락인데 차단도 재기록도 아니다.** 차단하면 잡음이
    게이트를 막고(위와 같은 고장), 재기록하면 낮아진 수치가 다음 하한선이 되어 폭만큼씩
    사다리를 타고 내려간다. 남는 수단은 **경고**뿐이라 `reasons`에 델타를 싣는다.
    """

    model_config = ConfigDict(extra="forbid")

    verdict: Verdict
    blocking: bool
    requires_record: bool
    reasons: list[str]

    def summary(self) -> str:
        head = f"[하한선 {self.verdict.value}]"
        if not self.reasons:
            return head
        return head + "\n" + "\n".join(self.reasons)


def compare(
    baseline: Baseline | None, fingerprint: Fingerprint, current: Measurement
) -> "FloorJudgement":
    """현재 측정치를 기준선과 대조한다.

    **차단 조건은 하나다** — `현재 전체 통과 < 기준선 전체 통과 - OVERALL_FLOOR_TOLERANCE`.
    합성·실수집은 차단하지 않고 델타와 경고만 남긴다(2026-08-13 사용자 결정, 5회 실측 근거는
    `OVERALL_FLOOR_TOLERANCE`).

    **이 판정은 약해진 판정이다.** 못 잡는 것 셋을 여기에도 적어 둔다 —
    `TOLERANCE_BLIND_SPOTS`가 같은 내용을 실행 메시지로 내보내지만, 코드를 읽는 사람이
    출력을 보고 있으리라는 보장은 없다.

    1. 전체가 `OVERALL_FLOOR_TOLERANCE`건 이하로 **진짜 회귀해도 통과한다.** 33→31을 잡음과
       회귀로 가를 수단이 없다. 통과 실행은 기준선을 갱신하지 않으므로 31이 계속 나와도
       매번 통과한다 — 허용 폭 안의 **드리프트**는 이 게이트가 보지 못한다.
    2. **하위 축에만 갇힌 회귀는 차단하지 않는다.** 합성이 20/20 → 14/20으로 무너져도
       실수집이 그만큼 오르면 전체가 유지되어 통과한다. 2026-08-13 run 4가 정확히 그
       형태였고(합성 14 최저 · 실수집 19 최고 · 전체 33 = 기준선), 그것이 차단축을 전체
       하나로 좁힌 근거이자 **이 완화가 실제로 뚫어 준 구멍**이다. 근거와 구멍이 같은 실행에서
       나왔다는 사실을 지우지 마라.
    3. 허용 폭 2는 **표본 5회의 관측 폭**이지 분포 추정이 아니다.

    되돌리려면 `OVERALL_FLOOR_TOLERANCE`를 0으로 두면 차단축의 폭이 닫히고
    (`dropped_beyond`가 `dropped_from`과 같은 식이 된다), 하위 축까지 되살리려면 아래
    차단 분기가 `current.overall` 대신 `dropped`를 보게 하면 된다 — **두 축은 별개의 완화**라
    한쪽만 되돌릴 수도 있다.

    순서가 중요하다. **미측정을 가장 먼저 본다** — 판정하지 못한 문서가 섞인 수치는
    비교의 대상이 아니라 측정의 실패다. 그다음이 **지문**이다. 코퍼스가 바뀐 상태에서 수치만
    비교하면 "문서를 빼서 오른 통과율"이 개선으로 보이므로, 지문이 다르면 수치는 아예 읽지
    않는다.

    미측정 검사가 여기 있어야 하는 이유는 기록 경로만으로는 반쪽이기 때문이다. 기록 모드가
    아닌 평범한 게이트 실행에서도 부분 실패는 일어나는데, 실패한 문서가 **원래도 실패하던
    문서**라면 수치가 기준선과 같아 `유지`(비차단)로 통과한다 — 36건을 모델에 보내지도
    못한 실행이 초록으로 끝난다. 기록을 막는 것과 판정을 막는 것은 다른 사건이다.
    """
    unmeasured = current.unmeasured_gaps()
    if unmeasured:
        return FloorJudgement(
            verdict=Verdict.UNMEASURED,
            blocking=True,
            # 재기록을 요구하지 않는다 — 요구해 봐야 `Baseline` 불변식이 그 기록을 거부한다.
            # 사람이 할 일은 기준선을 갱신하는 것이 아니라 변환이 실패한 원인을 고치는 것이다.
            requires_record=False,
            reasons=[
                "- **측정하지 못한 문서가 있다** — 이 실행은 판정도 기록도 하지 못한다",
                *unmeasured,
                NOT_A_MEASUREMENT_NOTE,
            ],
        )
    if baseline is None:
        return FloorJudgement(
            verdict=Verdict.ABSENT,
            blocking=True,
            requires_record=True,
            reasons=[
                f"- **기준선이 없다** — `{BASELINE_PATH}`",
                "  - 비교할 직전 측정치가 없으면 '낮아지지 않았다'를 판정할 수 없다. "
                "없는 것을 통과로 처리하면 첫 실행이 조용히 지나가고 하한선은 영영 서지 않는다",
                f"  - 닫는 방법: `{RECORD_ENV}=1` 로 다시 돌려 기준선을 만들고 그 diff를 "
                "커밋한 뒤, 플래그 **없이** 다시 돌린 결과로 판정한다",
            ],
        )
    drift = fingerprint.differences(baseline.fingerprint)
    if drift:
        return FloorJudgement(
            verdict=Verdict.INCOMPARABLE,
            blocking=True,
            requires_record=True,
            reasons=[
                *drift,
                f"  - 닫는 방법: `{RECORD_ENV}=1` 로 새 구성의 기준선을 기록하고 diff를 "
                "리뷰에 올린다. **수치 비교는 하지 않았다** — 통과도 실패도 아닌 비교 불가다",
            ],
        )
    before_groups = dict(baseline.measurement.groups())
    dropped: list[str] = []
    gained: list[str] = []
    for label, group in current.groups():
        before = before_groups[label]
        line = (
            f"{label}: 기준선 {before.passed}/{before.documents}"
            f"({before.pass_rate:.3f}) → 현재 {group.passed}/{group.documents}"
            f"({group.pass_rate:.3f})"
        )
        if group.dropped_from(before):
            dropped.append(line)
        elif before.dropped_from(group):
            gained.append(line)
    # **차단 판정은 전체 축 하나만 본다.** 위 순회는 세 축의 델타를 *적기* 위한 것이고,
    # 여기서 읽는 것은 `current.overall` 하나다. 두 일을 한 루프에서 하면 "적는 축"과
    # "막는 축"이 다시 붙어, 하위 축 하락이 차단으로 새는 예전 상태로 돌아간다.
    if current.overall.dropped_beyond(baseline.measurement.overall, OVERALL_FLOOR_TOLERANCE):
        return FloorJudgement(
            verdict=Verdict.REGRESSED,
            blocking=True,
            requires_record=False,
            reasons=[
                f"- **차단축(`{BLOCKING_GROUP_LABEL}`)이 허용 폭 {OVERALL_FLOOR_TOLERANCE}건을 "
                "넘겨 낮아졌다** — 이것이 차단 기준이다",
                *(f"  - {line}" for line in dropped),
                *(["- 함께 오른 집단:", *(f"  - {line}" for line in gained)] if gained else []),
                *TOLERANCE_BLIND_SPOTS,
                "  - n=20~56 단일 실행이라 경계에서는 통계 변동이 크다(master-plan §7). "
                "재실행·표본 확대 판단은 사람이 한다 — 자동 재시도로 숨기지 않는다",
            ],
        )
    if dropped:
        # 하락이 있는데 차단하지 않은 실행. **재기록을 요구하지 않는다** — 요구하면 낮아진
        # 수치가 다음 하한선이 되어, 허용 폭 안의 하락이 매번 기준선을 끌어내리는 사다리가
        # 된다(폭 2씩 무한히 내려갈 수 있다). 기준선은 그대로 두고 경고만 남긴다.
        return FloorJudgement(
            verdict=Verdict.TOLERATED,
            blocking=False,
            requires_record=False,
            reasons=[
                f"- ⚠ **낮아진 집단이 있다 — 차단하지 않았다.** 차단축(`{BLOCKING_GROUP_LABEL}`)이 "
                f"허용 폭 {OVERALL_FLOOR_TOLERANCE}건 안이다. **통과는 '회귀가 없다'는 뜻이 "
                "아니다**",
                *(f"  ⚠ {line}" for line in dropped),
                *(["- 함께 오른 집단:", *(f"  - {line}" for line in gained)] if gained else []),
                *TOLERANCE_BLIND_SPOTS,
                "  - 기준선은 갱신하지 않는다 — 허용 폭 안의 하락을 기록하면 그 수치가 다음 "
                "하한선이 되어 폭만큼씩 계속 내려간다",
            ],
        )
    if gained:
        return FloorJudgement(
            verdict=Verdict.IMPROVED,
            blocking=False,
            requires_record=True,
            reasons=[
                "- **직전 기록보다 올랐다** — 차단하지 않는다. 다만 기준선을 갱신하지 않으면 "
                "이 개선은 다음 실행의 하한선이 되지 못하고 사라진다",
                *(f"  - {line}" for line in gained),
                f"  - 갱신: `{RECORD_ENV}=1` 로 다시 돌려 diff를 커밋한다",
            ],
        )
    return FloorJudgement(
        verdict=Verdict.HELD,
        blocking=False,
        requires_record=False,
        reasons=[
            "- 직전 기록과 같다 — 하한선 유지",
            f"  - 차단축은 `{BLOCKING_GROUP_LABEL}` 하나이고 허용 폭은 "
            f"{OVERALL_FLOOR_TOLERANCE}건이다(2026-08-13 완화). 이번 실행은 세 축 모두 "
            "기준선과 같아 그 폭을 쓰지 않았다",
        ],
    )
