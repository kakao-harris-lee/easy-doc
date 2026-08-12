#!/usr/bin/env python3
"""Kotlin 산출물이 **요구사항이 요구하는 성질**을 만족하는지 판정한다.

기준은 Python 출력이 아니다. Python 구현은 회귀가 잦아 옮기는 중이고, 사용자 결정
(2026-08-12)은 "출력을 Python과 동일하게 맞출 필요 없다. 요구사항을 구현하고 이후에
개선한다"이다. 그래서 이 스크립트는 두 가지를 서로 다른 방식으로 판정한다.

    spec 도메인   fixture의 `assert` 목록을 Kotlin 산출물에 **실행**해 판정한다.
                  "가려야 할 것이 남았는가", "남겨야 할 것이 지워졌는가", "자리표시자를
                  되돌리면 원문이 복원되는가" 같은 성질이며, Python 출력을 보지 않는다.
                  Python 실행 결과는 `reference`(참고값)로 함께 오고, 다른 자리는
                  **참고 갈림 원장**에 기록된다 — 판정이 아니라 기록이다.
    compat 도메인 값이 같은 것이 곧 요구사항인 자리(crypto·jwt·argon2). 롤백 창에서
                  Python이 Kotlin 산출물을 읽어야 하므로 값 동일성을 그대로 요구한다.

참고 갈림을 기록으로 남기는 이유: 값이 다르다는 것은 둘 중 하나가 틀렸다는 신호라
정보 가치가 있는데, 그것을 차단 사유로 쓰면 폐기된 전제("Python이 정답")로 되돌아간다.
그래서 **막지 않되 조용할 수는 없게** 한다 — 기록되지 않은 갈림은 종료 코드 1이고,
닫는 방법은 구현 수정이 아니라 `--record-reference`로 원장을 갱신해 커밋하는 것이다.
그 diff가 리뷰에 올라가는 것이 이 장치의 전부이자 목적이다.

실행:
    uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
        --fixture parity/fixtures/masking/masking.json \
        --actual  parity/actual/masking/kotlin.json
    # 전체 게이트 — 기대 도메인 전부를 요구한다
    uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
        --fixture parity/fixtures --actual parity/actual --report-md report.md
    # 개발 중 한 도메인만 (부분 검증 — 게이트를 닫는 근거가 아니다. 통과해도 종료 코드 3)
    uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
        --fixture parity/fixtures --actual parity/actual --only-domain masking

실제값 파일 형식:
    {"runtime": "kotlin", "cases": [{"id": "masking-rrn-hyphen", "actual": {...}}]}
    `runtime`은 반드시 "kotlin"이어야 한다. 예전에는 이 필드를 읽는 코드가 없어
    `runtime: not-kotlin` 결과도 그대로 통과했다.

역방향 케이스(`verification.mode == "external"`, 예: Fernet·JWT를 **Kotlin이 만들고
Python이 읽는** 방향)는 값 비교로 닫지 않는다. Kotlin이 기대값을 그대로 되받아 적으면
아무것도 실행하지 않고 "일치"가 나오기 때문이다. 이런 케이스는 **비교기가 그 자리에서
Python 검증기를 돌려** 판정한다. `verification.actual_file`(Kotlin 산출물)이 없으면
통과가 아니라 **미검증(pending)** 으로 센다.

신뢰 경계 — 이 스크립트가 보장하는 것과 보장하지 못하는 것:

    보장한다 (1) fixture가 정본 생성기(`dump_parity_fixtures.py`)의 산출물이다.
        비교할 때마다 생성기를 다시 돌려 케이스 id 집합·개수·순서·source·요구사항 선언·
        정규화 선언·**단언 목록**·참고값을 대조한다. 단언을 손으로 지워 게이트를 무르게
        만드는 경로가 여기서 막힌다. 대조할 수 없는 자리는 난수뿐이다 — crypto의
        키/토큰, argon2의 PHC(솔트). 그 목록이 `VOLATILE_INPUT_FIELDS`이고, **그 목록을
        늘리는 것이 곧 구멍을 늘리는 것**이다.
    보장한다 (2) 역방향 케이스의 판정이 실제 실행에서 나온다. 증거 파일을 읽지 않고
        검증기를 직접 돌린다. `*.verified.json`은 그 실행의 **기록**으로 덮어써진다.
    보장한다 (3) 역방향 산출물이 fixture의 요청과 결합돼 있다 — 요청한 키/시크릿을 썼고,
        요청한 평문·subject를 하나도 빠짐없이 덮었으며, 중복 id로 표본을 채우지 않았다.
    보장한다 (4) spec 도메인의 단언이 **양방향**이다. 성질 검증의 고전적 실패는 한쪽으로만
        재는 것이다 — "가려졌는가"만 재면 전문을 통째로 가린 구현이 만점을 받는다. 그래서
        도메인마다 `under`(덜 한 것을 잡는다)와 `over`(더 한 것을 잡는다) 방향의 검사가
        **둘 다** 있어야 하고, 없으면 구조 결함으로 막는다.
    보장한다 (5) 마스킹 범주 문자열이 **API 계약과 같다.** 범주는 자리표시자에 그대로 박히는
        복원 키이고 React가 화면에 그대로 렌더링하는 문구다. 정본은 이 저장소가 아니라
        `contracts/easy-doc-v1.yaml`의 `MaskedItemResponse` 이며, 비교기는 그 파일을 직접
        읽어 fixture 선언과 대조한다. 예전에는 fixture가 넘긴 `categories` 인자와만
        대조해서, 생성기가 `["RRN","CARD"]`로 흘러가면 게이트는 통과하고 API는 계약을
        위반했다. 계약을 못 읽으면 통과가 아니라 **불충족**이다(fail closed).
    보장한다 (6) 참고 갈림이 **선언 한 줄로 면제되지 않는다.** `reference_divergence:
        "expected"` 는 원장 기록을 면제하지 않고 검사를 **하나 더한다**(갈림이 사라지면
        실패). 예전에는 그 선언이 원장 기록과 대조를 통째로 건너뛰어, 갈림의 내용이 바뀌어도
        아무도 몰랐다. 원장에 남았지만 이번 관측 범위에 없는 **낡은 항목**도 함께 보고한다.

    보장하지 못한다 (a) **그 산출물을 정말 Kotlin이 만들었는가.** fixture가 키와 시크릿을
        공개하므로 같은 값을 Python으로도 만들 수 있다. `runtime` 필드는 선언일 뿐이고
        손으로 적을 수 있다. 이 경계는 코드로 막히지 않는다 — Kotlin 테스트 하네스가
        그 파일을 쓰도록 CI에 배선하는 것이 유일한 방어다.
    보장하지 못한다 (b) **정본 생성기 자체의 위조.** 생성기를 고치면 "정본"이 따라 바뀐다.
        생성기와 이 비교기는 같은 리뷰·같은 커밋 게이트를 지나야 한다.
    보장하지 못한다 (c) **단언이 요구사항을 다 덮는가.** 성질로 적히지 않은 것은 판정되지
        않는다. 값 동일성 시절에는 "전부"를 재는 대신 무엇을 재는지 몰랐고, 지금은 무엇을
        재는지 알되 그것이 전부가 아니다. 덮이지 않은 자리는 참고 갈림 원장과 골든셋
        게이트가 받는다. 커버리지는 사람이 리뷰해야 한다.
    보장하지 못한다 (d) 품질. 통과율·judge 점수·충실성 바닥은 `tests/golden`의 몫이다.

판정 범위 — 전체 게이트와 부분 검증을 구분한다:
    비교기는 **주어진 파일만** 본다. 그래서 도메인 디렉터리를 통째로 빼면 그 도메인이 한 건도
    검증되지 않은 채 "전건 일치"가 나올 수 있었다. 이제 디렉터리 비교는 **어떤 도메인이 있어야
    하는지**를 알고 빠진 도메인을 누락으로 판정한다.

    - 기대 도메인 집합의 정본은 `dump_parity_fixtures.py`의 `BUILDERS` 키다. 이 스크립트는
      그 키를 import해서 쓴다 — 목록을 복제하지 않는다(복제가 곧 드리프트다). 도메인을
      추가할 때 고칠 곳은 생성기 한 곳뿐이다.
    - `--fixture`에 디렉터리를 주고 도메인을 지정하지 않으면 **전체 게이트**다: 기대 집합
      전부가 있어야 한다.
    - `--only-domain` / `--only` / 단일 fixture 파일 지정은 **부분 검증**이다. 지정한 범위만
      판정하고, 통과해도 출력에 "게이트를 닫는 근거가 아니다"를 명시하며 마지막 줄을
      `전건 일치:`로 시작하지 않는다. **종료 코드도 0이 아니라 3이다**(아래 참고).

종료 코드:
    0 = 요구 성질 전건 충족 + 미검증 0건 + 기대 도메인 전부 존재
        (게이트를 닫아도 되는 유일한 상태)
    1 = 성질 불충족·값 불일치·누락·읽기 실패(금지된 정규화 규칙, fixture가 정본과 다름,
        단언 없는 spec 케이스, 방향 가드가 한쪽뿐인 도메인, **기록되지 않은 참고 갈림**,
        runtime 미선언·불일치, 역방향 검증 실패·표본 부족 포함)
        **도메인 누락도 1이다.** 근거: 이미 "Kotlin 결과 파일 없음"(파일 누락)과
        "미실행"(케이스 누락)이 1로 나간다. 같은 성격의 누락을 입도가 커졌다는 이유로
        (케이스 → 파일 → 도메인) 더 약한 코드로 내보내면 "많이 지울수록 종료 코드가 약해지는"
        유인이 생긴다 — 그것이 정확히 이 게이트를 무력화하는 경로다.
    2 = 불충족은 없으나 미검증 케이스가 남음 — "돌렸다"이지 "증명됐다"가 아니다.
        두 갈래가 여기로 온다. (i) 역방향 산출물 미생성. (ii) **spec_status=pending**
        도메인 — 요구 성질을 아직 적지 못해 판정할 근거가 없는 상태다. 값 비교로 때우지
        않고 미검증으로 세는 것이 이 하네스의 전제다.
        도메인이 통째로 없으면 정의 자체가 없으므로 2의 의미에 해당하지 않는다(1이다).
    3 = **부분 검증**이 지정한 범위 안에서 통과 — 게이트를 닫는 근거가 아니다.
        (`--only`, `--only-domain`, 단일 fixture 파일, 도메인 디렉터리 지정)
        **왜 0이 아닌가.** 종료 코드는 자동화가 읽는 유일한 계약이다. stdout에 찍히는
        "게이트를 닫는 근거가 아니다"는 사람이 읽을 때만 유효하고, CI·에이전트는 exit
        code로 판정한다. 부분 검증이 0으로 나가면 10개 도메인을 건너뛴 실행이 "전체
        통과"로 기록된다. 게다가 이 파일이 바로 위에서 "0은 기대 도메인 전부가 있을
        때만"이라고 계약해 놓았으니, 0을 돌려주는 것은 코드가 자기 계약을 어기는 것이다.
        **왜 1이 아닌가.** 부분 검증 자체는 정상적인 개발 중 작업이다 — 모듈 하나가
        끝날 때마다 그 도메인만 돌리는 것이 이 하네스의 사용법이다. 불일치·누락과 같은
        코드로 묶으면 "고쳐야 할 문제가 있다"와 "범위를 좁혀 돌렸다"를 호출자가 구분할
        수 없다. 3은 "이 범위에서는 문제 없음, 그러나 게이트는 열린 채"라는 뜻이다.
        부분 검증이라도 불일치가 있으면 1, 미검증이 남으면 2가 그대로 나간다 —
        3은 그 두 검사를 모두 통과한 뒤에만 도달한다.
    4 = `--record-reference` 가 **원장을 바꾼** 실행. 판정이 아니라 기록이다.
        **왜 새로 만들었나.** 갈림 23건을 방금 침묵시킨 실행과 애초에 0건이던 실행이 둘 다
        3을 냈다. 자동화는 종료 코드만 읽으므로 두 실행이 구분되지 않았고, "무엇을
        침묵시켰는지"는 stdout에만 남았다 — 이 파일이 3을 정의하며 세운 원칙("종료 코드는
        자동화가 읽는 유일한 계약이고 stdout 문구는 사람에게만 유효하다")을 자기가 어긴
        자리다. 이제 원장이 바뀌면 4로 끝나고 그 실행으로는 어떤 게이트도 닫지 못한다.
        기록 모드라도 **바뀐 것이 없으면** 0/2/3의 정상 경로로 떨어진다. 그때는 관측이
        커밋된 원장과 정확히 같다는 뜻이라 플래그 없이 돌린 실행과 판정이 정의상 같다.
        **CI 영향 없음** — `.github/workflows/ci.yml` 은 이 플래그를 주지 않고(원장 갱신은
        사람이 커밋해 리뷰에 올려야 한다), 0과 3만 통과로 읽고 나머지는 실패로 본다.
    사용법 오류(인자 누락·알 수 없는 도메인)도 1이다. argparse 기본값 2를 쓰면 "인자를
    잘못 줬다"와 "미검증이 남았다"가 같은 코드로 나가 호출자가 구분할 수 없다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, NoReturn

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from dump_parity_fixtures import (  # noqa: E402 — sys.path 주입 뒤에만 import된다
    BUILDERS,
    MODE_COMPAT,
    MODE_SPEC,
    REPO_ROOT,
    STATUS_PENDING,
    VERIFIERS,
    ContractError,
    VerificationOutcome,
    mask_contract,
    write_proof_record,
)

#: 기대 도메인 집합. **정본은 생성기의 BUILDERS 키 하나뿐이다.** 여기에 목록을 다시 적지
#: 않는다 — 두 벌이 되는 순간 도메인을 추가할 때 한쪽만 고쳐지고, 그 도메인은 검증되지
#: 않은 채 게이트를 통과한다.
EXPECTED_DOMAINS: tuple[str, ...] = tuple(BUILDERS)

#: Kotlin 결과 파일이 선언해야 하는 런타임. 이 값이 아니면 비교 대상이 아니다.
#: **단독 방어선이 아니다** — 손으로 적을 수 있는 문자열이다. 정본 대조·역방향 실행과
#: 함께 쓸 때만 의미가 있다(모듈 docstring "신뢰 경계" 참고).
KOTLIN_RUNTIME = "kotlin"

#: 정본 대조에서 **값을 비교할 수 없는** 입력 필드. 생성기가 매 실행 난수를 쓰는 자리다.
#: 여기 없는 필드는 전부 대조 대상이다 — 목록을 늘릴 때는 왜 난수인지 근거를 함께 적는다.
#: (crypto: Fernet 키를 매번 새로 만든다 / argon2: 솔트가 매번 다르다)
VOLATILE_INPUT_FIELDS: dict[str, frozenset[str]] = {
    "crypto": frozenset({"key", "token"}),
    "argon2": frozenset({"phc"}),
}

#: 정본과 다른 케이스를 몇 건까지 본문에 적을지. 전부 적으면 리포트가 수백 줄이 된다.
MAX_REPORTED_CASE_DIFFS = 5

#: 허용 정규화 규칙. 표기 차이만 흡수하고 의미는 건드리지 않는다.
ALLOWED = {
    "nfc": "유니코드 NFC 정규화 (한글 조합/완성형 표기 차이)",
    "nfkc": "유니코드 NFKC 정규화 — 호환 문자까지 접으므로 꼭 필요한 도메인에서만",
    "lf": "CRLF·CR을 LF로 (플랫폼 개행 차이)",
    "trim": "문자열 앞뒤 공백 제거",
    "trim_line_ends": "각 줄의 끝 공백만 제거",
    "mask_document_id": 'prompt injection 방어용 난수 id(id="...")를 <ID>로 치환',
    "float_tol": (
        "부동소수 비교 허용 오차 1e-9 (float_tol:1e-6 형태로 지정 가능. "
        "유한·비음수·1e-3 이하만 허용 — inf/nan은 숫자 비교를 통째로 무력화한다)"
    ),
}

#: 절대 허용하지 않는 정규화. 이걸 켜는 순간 검증이 통과를 위한 의식이 된다.
FORBIDDEN = {
    "ignore_placeholders": "자리표시자가 달라지면 개인정보 복원이 깨진다 — 눈감아 줄 수 없다",
    "ignore_body": "문서 본문 차이는 곧 제품 동작 차이다",
    "ignore_status": "상태 코드·failure code는 외부 계약이다",
    "casefold": "대소문자 차이는 파일명·헤더에서 실제 동작 차이를 만든다",
    "collapse_all_space": "공백 접기는 문장 분리·길이 검사 결과를 통째로 바꾼다",
    "sort_lists": "목록 순서는 프롬프트 렌더링 순서이자 위반 보고 순서다",
}

_PLACEHOLDER = re.compile(r"\[\[[^\[\]]+\]\]")
_DOC_ID = re.compile(r'id="[0-9a-f]{4,}"')

#: XML 1.0이 담지 못하는 제어문자. `equals_derived`의 `control_strip` 규칙이 쓴다.
#: **app/text.py를 import하지 않고 여기서 직접 정의한다** — 판정 근거가 요구사항이지
#: Python 구현이 아니어야 하기 때문이다. 구현을 불러 쓰면 구현의 버그가 곧 기대값이 된다.
_XML_FORBIDDEN = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")

#: 참고 갈림 원장의 기본 위치. 저장소에 커밋되어 리뷰에 올라가는 것이 존재 이유다.
DEFAULT_LEDGER = REPO_ROOT / "parity" / "reference-ledger"

_DUMP = ".claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py"

#: `float_tol`의 기본 허용 오차. 표기·연산 순서 차이만 흡수하는 크기다.
DEFAULT_FLOAT_TOL = 1e-9

#: fixture가 선언할 수 있는 허용 오차의 상한.
#: 근거 — 이 규칙의 목적은 IEEE754 표기 차이와 연산 순서 차이를 흡수하는 것이지 값 차이를
#: 덮는 것이 아니다. 기본값이 1e-9, 문서화된 예시가 1e-6이므로 1e-3은 그보다 세 자리 더
#: 느슨하다. 그 위는 "다른 값을 같다고 부르는" 영역이고, 그렇게 넓은 오차가 필요하다면
#: 그것은 정규화 문제가 아니라 Kotlin 구현이나 fixture 설계 문제다.
MAX_FLOAT_TOL = 1e-3

#: 부분 검증 성공. 0(전체 게이트 통과)과 반드시 구분되는 별도 코드다 — 모듈 docstring의
#: "종료 코드" 절 참고. 자동화가 읽는 계약은 stdout 문구가 아니라 이 값 하나뿐이다.
EXIT_PARTIAL_OK = 3

#: `--record-reference` 가 원장을 **바꾼** 실행. 판정이 아니라 기록이다.
#: 왜 별도 코드인가 — 예전에는 갈림 23건을 방금 침묵시킨 실행과 애초에 0건이던 실행이 둘 다
#: 3(부분 검증 통과)을 냈다. 자동화는 종료 코드만 읽으므로 두 실행을 구분할 수 없었고,
#: "무엇을 침묵시켰는지"는 stdout에만 남았다. CI는 이 플래그를 주지 않으므로 4가 CI 계약을
#: 바꾸지 않는다(`.github/workflows/ci.yml`은 0과 3만 통과로 읽고 나머지를 실패로 본다).
EXIT_RECORDED = 4


def _float_tolerance(raw: str) -> float:
    """`float_tol:<값>`의 값을 읽는다. 유한·비음수·상한 이내일 때만 통과시킨다.

    `float_tol:inf`(또는 `float_tol:1e309`)는 `abs(a - b) <= inf`를 **항상 참**으로 만들어
    모든 숫자 불일치를 일치로 바꾼다. `nan`은 반대로 모든 비교를 거짓으로 만든다. 어느 쪽도
    정규화가 아니라 게이트 무력화다.

    거부할 때 조용히 기본값으로 되돌리지 않고 비교 자체를 중단한다. fixture가 허용 오차를
    **명시**했는데 그 의도를 무시하고 다른 값으로 돌리는 것은 또 다른 은폐다 — 사람은
    자기가 적은 값으로 통과했다고 믿게 된다.
    """
    try:
        value = float(raw)
    except ValueError:
        raise SystemExit(
            f"[중단] float_tol 값을 숫자로 읽을 수 없다: {raw!r} (예: float_tol:1e-6)"
        ) from None
    if math.isnan(value):
        raise SystemExit(
            f"[중단] float_tol 이 nan 이다 ({raw!r}). nan 허용 오차는 모든 숫자 비교를 "
            "거짓으로 만든다"
        )
    if not math.isfinite(value):
        raise SystemExit(
            f"[중단] float_tol 은 유한한 값이어야 한다 (받은 값: {raw!r}). "
            "inf 허용 오차는 모든 숫자 불일치를 일치로 바꾼다 — 정규화가 아니라 게이트 무력화다"
        )
    if value < 0:
        raise SystemExit(
            f"[중단] float_tol 은 음수일 수 없다 (받은 값: {raw!r}). "
            "음수 오차는 같은 값끼리도 불일치로 만든다"
        )
    if value > MAX_FLOAT_TOL:
        raise SystemExit(
            f"[중단] float_tol 이 상한 {MAX_FLOAT_TOL:g} 을 넘는다 (받은 값: {raw!r}). "
            "이 크기는 표기 차이 흡수가 아니라 값 차이 은폐다 — 오차가 이만큼 필요하다면 "
            "고칠 곳은 정규화 규칙이 아니라 구현이나 fixture다"
        )
    return value


def _rules(names: list[str]) -> tuple[set[str], float]:
    active: set[str] = set()
    tolerance = DEFAULT_FLOAT_TOL
    for name in names:
        head, sep, arg = name.partition(":")
        if head in FORBIDDEN:
            raise SystemExit(f"[중단] 금지된 정규화 규칙: {head} — {FORBIDDEN[head]}")
        if head not in ALLOWED:
            raise SystemExit(f"[중단] 알 수 없는 정규화 규칙: {head} (가능: {', '.join(ALLOWED)})")
        active.add(head)
        if head == "float_tol" and sep:
            # `float_tol`(콜론 없음)은 기본값을 쓰겠다는 뜻이지만, `float_tol:`처럼 콜론을
            # 찍고 값을 비워 둔 것은 값을 지정하려다 만 상태다 — 조용히 기본값으로 넘기지 않는다.
            if not arg.strip():
                raise SystemExit(
                    "[중단] float_tol: 뒤에 허용 오차 값이 없다. "
                    "기본값을 쓰려면 콜론 없이 `float_tol` 이라고 적어라"
                )
            tolerance = _float_tolerance(arg)
    return active, tolerance


def _norm_str(value: str, active: set[str]) -> str:
    if "lf" in active:
        value = value.replace("\r\n", "\n").replace("\r", "\n")
    if "mask_document_id" in active:
        value = _DOC_ID.sub('id="<ID>"', value)
    if "nfkc" in active:
        value = unicodedata.normalize("NFKC", value)
    elif "nfc" in active:
        value = unicodedata.normalize("NFC", value)
    if "trim_line_ends" in active:
        value = "\n".join(line.rstrip() for line in value.split("\n"))
    if "trim" in active:
        value = value.strip()
    return value


def normalize(value: Any, active: set[str]) -> Any:
    if isinstance(value, str):
        return _norm_str(value, active)
    if isinstance(value, dict):
        # JSON 객체 키 순서는 의미가 없다 — dict 비교가 순서를 보지 않는다.
        return {key: normalize(item, active) for key, item in value.items()}
    if isinstance(value, list):
        return [normalize(item, active) for item in value]
    return value


def placeholders_of(value: Any) -> list[str]:
    """값 안의 자리표시자를 모두 모은다 (정규화가 이것을 바꾸면 안 된다)."""
    if isinstance(value, str):
        return _PLACEHOLDER.findall(value)
    if isinstance(value, dict):
        return [p for item in value.values() for p in placeholders_of(item)]
    if isinstance(value, list):
        return [p for item in value for p in placeholders_of(item)]
    return []


def equal(expected: Any, actual: Any, tolerance: float) -> bool:
    if isinstance(expected, float) or isinstance(actual, float):
        try:
            return abs(float(expected) - float(actual)) <= tolerance
        except (TypeError, ValueError):
            return False
    if isinstance(expected, dict) and isinstance(actual, dict):
        return expected.keys() == actual.keys() and all(
            equal(expected[key], actual[key], tolerance) for key in expected
        )
    if isinstance(expected, list) and isinstance(actual, list):
        return len(expected) == len(actual) and all(
            equal(left, right, tolerance) for left, right in zip(expected, actual, strict=True)
        )
    return type(expected) is type(actual) and expected == actual


def first_difference(expected: Any, actual: Any, tolerance: float, path: str = "$") -> str:
    """최소 재현 지점 — 어느 필드가 어떻게 다른지 한 줄로 짚는다."""
    if isinstance(expected, dict) and isinstance(actual, dict):
        for key in expected:
            if key not in actual:
                return f"{path}.{key}: 실제값에 없음"
            if not equal(expected[key], actual[key], tolerance):
                return first_difference(expected[key], actual[key], tolerance, f"{path}.{key}")
        for key in actual:
            if key not in expected:
                return f"{path}.{key}: 기대값에 없는 필드가 추가됨"
    if isinstance(expected, list) and isinstance(actual, list):
        if len(expected) != len(actual):
            return f"{path}: 길이 {len(expected)} != {len(actual)}"
        for index, (left, right) in enumerate(zip(expected, actual, strict=True)):
            if not equal(left, right, tolerance):
                return first_difference(left, right, tolerance, f"{path}[{index}]")
    left = json.dumps(expected, ensure_ascii=False)[:200]
    right = json.dumps(actual, ensure_ascii=False)[:200]
    return f"{path}: 기대 {left} / 실제 {right}"


# ------------------------------------------------------------------ 성질 검사(spec 모드)
#
# 여기 있는 함수들이 "무엇을 요구사항으로 볼 것인가"의 정본이다. 전부 **Kotlin 산출물과
# fixture 입력만** 보고 판정한다 — Python 구현을 부르지 않는다. 부르는 순간 기준이 다시
# Python 동작이 되고, 그것이 이번에 폐기된 전제다.

_MISSING = object()


def at_path(value: Any, path: str) -> Any:
    """`"$"`(전체) 또는 점으로 이어진 키 경로. 없으면 `_MISSING`."""
    if path in ("", "$"):
        return value
    current = value
    for key in path.split("."):
        if not isinstance(current, dict) or key not in current:
            return _MISSING
        current = current[key]
    return current


def strings_of(value: Any) -> list[str]:
    """값 안의 모든 문자열 (검사 대상 범위를 넓게 잡을 때 쓴다)."""
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [text for item in value.values() for text in strings_of(item)]
    if isinstance(value, list):
        return [text for item in value for text in strings_of(item)]
    return []


@dataclass(frozen=True)
class CheckCall:
    """검사 1회에 주어지는 것 — 산출물, fixture 입력, 인자, 활성 정규화."""

    actual: Any
    payload: Any
    args: dict[str, Any]
    active: set[str]

    def arg(self, name: str, default: Any = None) -> Any:
        return self.args.get(name, default)

    def target(self, default_path: str = "$") -> Any:
        return at_path(self.actual, str(self.arg("path", default_path)))

    def text(self, value: Any) -> Any:
        return normalize(value, self.active)


def check_absent(call: CheckCall) -> list[str]:
    """지정한 문자열이 산출물에 **남아 있으면 안 된다** (누락 가드)."""
    target = call.target("$")
    if target is _MISSING:
        return [f"경로 `{call.arg('path', '$')}` 가 산출물에 없다"]
    haystack = [call.text(text) for text in strings_of(target)]
    return [
        f"가려지지 않았다: {needle!r} 가 `{call.arg('path', '$')}` 에 그대로 남아 있다"
        for needle in call.arg("needles", [])
        if any(call.text(needle) in text for text in haystack)
    ]


def check_present(call: CheckCall) -> list[str]:
    """지정한 문자열이 산출물에 **남아 있어야 한다** (과잉 가드)."""
    target = call.target("$")
    if target is _MISSING:
        return [f"경로 `{call.arg('path', '$')}` 가 산출물에 없다"]
    haystack = [call.text(text) for text in strings_of(target)]
    return [
        f"사라졌다: {needle!r} 가 `{call.arg('path', '$')}` 에 없다 — 지우면 안 되는 본문을 지웠다"
        for needle in call.arg("needles", [])
        if not any(call.text(needle) in text for text in haystack)
    ]


def check_restores_input(call: CheckCall) -> list[str]:
    """자리표시자를 원문으로 되돌리면 입력과 정확히 같아져야 한다.

    이 하나가 "본문을 잃지 않았다 + 대응표가 실제로 복원 가능하다 + 자리표시자가 본문에
    실재한다"를 함께 건다. 내보내기(`restore_placeholders`)가 이 성질 위에 서 있다.
    """
    text_field = str(call.arg("text_field", "masked_text"))
    items_field = str(call.arg("items_field", "items"))
    input_field = str(call.arg("input_field", "text"))
    masked = at_path(call.actual, text_field)
    items = at_path(call.actual, items_field)
    source = at_path(call.payload, input_field)
    if not isinstance(masked, str) or not isinstance(items, list) or not isinstance(source, str):
        return [f"산출물에 `{text_field}`(문자열)과 `{items_field}`(배열)이 있어야 한다"]
    failures: list[str] = []
    restored = masked
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            failures.append(f"items[{index}] 가 객체가 아니다")
            continue
        placeholder = str(item.get("placeholder", ""))
        original = str(item.get("original", ""))
        if placeholder not in restored:
            failures.append(
                f"items[{index}] 의 자리표시자 {placeholder!r} 가 본문에 없다 — 복원 불가"
            )
            continue
        restored = restored.replace(placeholder, original, 1)
    if failures:
        return failures
    want, got = call.text(source), call.text(restored)
    if want != got:
        offset = next(
            (i for i, (a, b) in enumerate(zip(want, got, strict=False)) if a != b),
            min(len(want), len(got)),
        )
        return [
            "복원 결과가 입력과 다르다 — 마스킹이 본문을 바꿨거나 대응표가 어긋났다 "
            f"(첫 차이 오프셋 {offset}, 길이 입력 {len(want)} / 복원 {len(got)})"
        ]
    return []


def check_placeholder_scheme(call: CheckCall) -> list[str]:
    """자리표시자 형식·범주·번호 규칙. **범주 집합의 정본은 API 계약이다.**

    `[[{범주}{번호}]]`이고, 범주는 계약이 못박은 enum 안이며, 번호는 **범주별로 1부터 등장
    순서**다. `items`의 순서·범주·자리표시자도 본문 등장 순서와 짝이 맞아야 한다. 번호가
    어긋나면 복원이 다른 원문을 꽂으므로 개인정보 축에서 가장 직접적인 결함이다.

    예전에는 범주를 **fixture가 스스로 넘긴 `categories` 인자**와만 대조했다. 그 인자는
    생성기가 써 넣으므로 생성기가 선언한 값을 생성기가 만든 fixture로 검사하는 구조였고,
    생성기가 `["RRN","CARD"]`로 흘러가면 게이트는 통과하고 API는 계약을 위반했다(X-12/S-1,
    재현으로 확인: 종료 코드 3 = 통과). 이제 계약을 직접 읽어 **fixture 선언과 계약을 먼저
    대조**하고, 판정에는 계약 쪽 값을 쓴다.
    """
    try:
        contract = mask_contract()
    except ContractError as exc:
        # 계약을 못 읽는 상태는 "범주를 검사하지 않는 상태"와 같다. 통과시키지 않는다.
        return [f"계약을 읽을 수 없어 범주를 판정할 수 없다 — {exc}"]
    categories = list(contract.categories)
    declared = [str(name) for name in call.arg("categories", [])]
    if declared and declared != categories:
        return [
            f"fixture가 선언한 범주 {declared} 가 계약({contract.source})의 enum "
            f"{categories} 와 다르다 — 범주 문자열은 표기가 아니라 자리표시자에 그대로 박히는 "
            "복원 키다. 계약을 고칠 일이면 contract-keeper가, 생성기가 흘러간 것이면 "
            "생성기가 계약을 읽도록 되돌린다"
        ]
    masked = at_path(call.actual, str(call.arg("text_field", "masked_text")))
    items = at_path(call.actual, str(call.arg("items_field", "items")))
    if not isinstance(masked, str) or not isinstance(items, list):
        return ["산출물에 `masked_text`(문자열)와 `items`(배열)가 있어야 한다"]
    found = _PLACEHOLDER.findall(masked)
    failures: list[str] = []
    counters: dict[str, int] = {}
    for index, token in enumerate(found):
        body = token[2:-2]
        category = next((name for name in categories if body.startswith(name)), None)
        if category is None:
            failures.append(
                f"{token!r} 의 범주가 계약 enum {categories} 에 없다 — "
                "범주 문자열이 곧 자리표시자이자 복원 키다"
            )
            continue
        if not re.fullmatch(contract.placeholder_pattern, token):
            # 계약은 범주뿐 아니라 자리표시자 **형태**도 못박았다. 형태가 갈리면 React가
            # 그대로 렌더링하는 문구와 내보내기 복원이 함께 어긋난다.
            failures.append(
                f"{token!r} 가 계약의 placeholder 패턴 "
                f"`{contract.placeholder_pattern}` 와 맞지 않는다"
            )
            continue
        number = body[len(category) :]
        if not number.isdigit():
            failures.append(f"{token!r} 의 번호 자리가 십진수가 아니다")
            continue
        counters[category] = counters.get(category, 0) + 1
        if int(number) != counters[category]:
            failures.append(
                f"{token!r} 의 번호가 {counters[category]} 여야 한다 — "
                "번호는 범주별로 1부터 등장 순서다"
            )
        if index < len(items) and isinstance(items[index], dict):
            item = items[index]
            if str(item.get("placeholder")) != token:
                failures.append(
                    f"items[{index}].placeholder 가 본문 {index}번째 자리표시자와 다르다 "
                    "— items 순서는 텍스트 등장 순서다"
                )
            if str(item.get("category")) != category:
                failures.append(f"items[{index}].category 가 {category!r} 가 아니다")
    if len(found) != len(items):
        failures.append(f"본문 자리표시자 {len(found)}개 / items {len(items)}개 — 개수가 다르다")
    return failures


def check_equals_field(call: CheckCall) -> list[str]:
    """지정 경로의 값이 요구사항이 못박은 값과 같아야 한다."""
    path = str(call.arg("path", "$"))
    got = call.target()
    if got is _MISSING:
        return [f"경로 `{path}` 가 산출물에 없다"]
    want = call.arg("value")
    if not equal(call.text(want), call.text(got), DEFAULT_FLOAT_TOL):
        return [f"`{path}` 가 {want!r} 여야 하는데 {got!r} 다"]
    return []


def check_at_most(call: CheckCall) -> list[str]:
    """지정 경로의 수가 상한을 넘지 않아야 한다 (호출 횟수·건수 계약)."""
    path = str(call.arg("path", "$"))
    got = call.target()
    limit = call.arg("limit")
    if not isinstance(got, int | float) or isinstance(got, bool):
        return [f"경로 `{path}` 에 수가 없다 (받은 값: {got!r})"]
    if not isinstance(limit, int | float):
        return [f"`limit` 인자가 수가 아니다: {limit!r}"]
    if got > limit:
        return [f"`{path}` 가 상한 {limit} 을 넘었다: {got}"]
    return []


def check_contains_all(call: CheckCall) -> list[str]:
    """지정 경로가 요구 항목을 **전부 포함**해야 한다 (추가는 허용 — 누락만 막는다)."""
    path = str(call.arg("path", "$"))
    got = call.target()
    if got is _MISSING:
        return [f"경로 `{path}` 가 산출물에 없다"]
    required = call.arg("required", [])
    if isinstance(got, str):
        missing = [item for item in required if call.text(str(item)) not in call.text(got)]
    elif isinstance(got, dict):
        missing = [item for item in required if str(item) not in got]
    elif isinstance(got, list):
        present = {json.dumps(call.text(item), ensure_ascii=False, sort_keys=True) for item in got}
        missing = [
            item
            for item in required
            if json.dumps(call.text(item), ensure_ascii=False, sort_keys=True) not in present
        ]
    else:
        return [f"경로 `{path}` 가 문자열·객체·배열이 아니다"]
    if missing:
        shown = ", ".join(repr(item) for item in missing[:MAX_REPORTED_CASE_DIFFS])
        return [f"`{path}` 에서 {len(missing)}건이 빠졌다: {shown}"]
    return []


def _derive_control_strip(call: CheckCall) -> tuple[Any, list[str]]:
    source = at_path(call.payload, str(call.arg("source", "text")))
    if not isinstance(source, str):
        return (None, [f"입력에 `{call.arg('source', 'text')}` 문자열이 없다"])
    return (_XML_FORBIDDEN.sub("", source), [])


def _derive_repair_policy(call: CheckCall) -> tuple[Any, list[str]]:
    """채택 = (자리표시자를 하나도 잃지 않았다) AND (위반 건수가 늘지 않았다).

    건수는 **산출물이 스스로 보고한 값**을 쓴다. 건수 자체가 옳은지는 `style` 도메인의
    질문이고, 여기서는 같은 건수를 받았을 때 같은 결정을 내리는지만 본다 — 두 질문을 섞으면
    실패했을 때 어느 쪽이 원인인지 알 수 없다.
    """
    original = at_path(call.payload, "original")
    candidate = at_path(call.payload, "candidate")
    placeholders = at_path(call.payload, "placeholders")
    before = at_path(call.actual, "original_issue_count")
    after = at_path(call.actual, "candidate_issue_count")
    if not isinstance(original, str) or not isinstance(candidate, str):
        return (None, ["입력에 `original`·`candidate` 문자열이 필요하다"])
    if not isinstance(before, int) or not isinstance(after, int):
        return (
            None,
            [
                "산출물이 `original_issue_count`·`candidate_issue_count` 를 보고해야 한다 — "
                "정책 판정의 입력이다"
            ],
        )
    lost = [
        token
        for token in (placeholders if isinstance(placeholders, list) else [])
        if str(token) in original and str(token) not in candidate
    ]
    return (not lost and after <= before, [])


#: `equals_derived`가 쓸 수 있는 규칙. **요구사항에서 유도**되며 app/ 구현을 부르지 않는다.
DERIVATIONS: dict[str, Callable[[CheckCall], tuple[Any, list[str]]]] = {
    "control_strip": _derive_control_strip,
    "repair_policy": _derive_repair_policy,
}


def check_equals_derived(call: CheckCall) -> list[str]:
    """산출물이 **입력에서 규칙으로 유도한 값**과 같아야 한다.

    요구사항이 규칙으로 완전히 적히는 도메인(제어문자 제거, 보정 채택 정책)에서 쓴다.
    유도는 이 스크립트가 직접 하므로 Python 구현이 틀려도 기대값이 따라 틀리지 않는다.
    """
    rule = str(call.arg("rule", ""))
    if rule not in DERIVATIONS:
        return [f"알 수 없는 유도 규칙: {rule!r} (가능: {', '.join(DERIVATIONS)})"]
    want, problems = DERIVATIONS[rule](call)
    if problems:
        return problems
    path = str(call.arg("path", "$"))
    got = call.target()
    if got is _MISSING:
        return [f"경로 `{path}` 가 산출물에 없다"]
    if not equal(call.text(want), call.text(got), DEFAULT_FLOAT_TOL):
        return [
            f"`{path}` 가 규칙 `{rule}` 의 유도값과 다르다 — "
            f"{first_difference(call.text(want), call.text(got), DEFAULT_FLOAT_TOL)}"
        ]
    return []


@dataclass(frozen=True)
class Check:
    run: Callable[[CheckCall], list[str]]
    #: 이 검사가 막는 방향. `under` = 덜 한 것, `over` = 더 한 것, `structural` = 형태.
    #: spec 도메인은 `under`와 `over`를 **둘 다** 갖춰야 한다(모듈 docstring 보장 (4)).
    directions: frozenset[str]
    doc: str


CHECKS: dict[str, Check] = {
    "absent": Check(check_absent, frozenset({"under"}), "가려야 할 문자열이 남지 않았다"),
    "present": Check(check_present, frozenset({"over"}), "남겨야 할 문자열이 지워지지 않았다"),
    "restores_input": Check(
        check_restores_input, frozenset({"structural"}), "자리표시자 역치환이 입력을 복원한다"
    ),
    "placeholder_scheme": Check(
        check_placeholder_scheme, frozenset({"structural"}), "자리표시자 형식·범주·번호 규칙"
    ),
    "equals_field": Check(
        check_equals_field, frozenset({"under", "over"}), "요구사항이 못박은 값과 같다"
    ),
    "at_most": Check(check_at_most, frozenset({"over"}), "수가 상한을 넘지 않는다"),
    "contains_all": Check(
        check_contains_all, frozenset({"under"}), "요구 항목을 전부 포함한다(추가는 허용)"
    ),
    "equals_derived": Check(
        check_equals_derived, frozenset({"under", "over"}), "입력에서 규칙으로 유도한 값과 같다"
    ),
}


def run_assertions(case: dict[str, Any], actual: Any, active: set[str]) -> list[str]:
    """케이스의 단언을 전부 실행한다. 반환은 실패 사유 목록(빈 목록 = 충족)."""
    failures: list[str] = []
    for entry in case.get("assert", []):
        if not isinstance(entry, dict):
            failures.append("단언 항목이 객체가 아니다")
            continue
        name = str(entry.get("check", ""))
        if name not in CHECKS:
            failures.append(f"알 수 없는 검사: {name!r} (가능: {', '.join(CHECKS)})")
            continue
        raw_args = entry.get("args")
        args: dict[str, Any] = raw_args if isinstance(raw_args, dict) else {}
        failures += [
            f"[{name}] {reason}"
            for reason in CHECKS[name].run(CheckCall(actual, case.get("input"), args, active))
        ]
    return failures


def load(path: Path) -> dict[str, Any]:
    try:
        loaded: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"[중단] {path} 를 읽을 수 없습니다: {type(exc).__name__}") from None
    return loaded


@dataclass(frozen=True)
class Pair:
    """비교할 fixture 한 벌 (fixture는 이미 읽어 둔다 — 도메인 판정에 필요하다).

    `mode`·`spec_status`는 **정본에서** 온다. 파일 쪽 선언은 위조 대상이라 판정 방식을
    정하는 근거가 될 수 없다 — 파일이 `mode: compat` 이라고 적어 성질 검사를 피해 가는
    경로를 막는다(파일과 정본이 다르면 정본 대조가 따로 잡는다).
    """

    fixture_path: Path
    actual_path: Path
    domain: str
    fixture: dict[str, Any]
    mode: str
    spec_status: str

    @property
    def case_ids(self) -> list[str]:
        cases = self.fixture.get("cases")
        if not isinstance(cases, list):
            return []
        return [str(case.get("id")) for case in cases if isinstance(case, dict) and case.get("id")]

    @property
    def pending_spec(self) -> bool:
        return self.mode == MODE_SPEC and self.spec_status == STATUS_PENDING


@dataclass
class FileResult:
    problems: list[str] = field(default_factory=list)
    pendings: list[str] = field(default_factory=list)
    #: compat 도메인에서 값으로 대조한 케이스 수.
    checked: int = 0
    #: spec 도메인에서 성질을 실행해 판정한 케이스 수.
    judged: int = 0
    #: 실행한 단언 수. 케이스 수만 세면 단언 1개짜리 케이스가 늘어난 것을 못 본다.
    assertions: int = 0
    external_ok: int = 0
    #: 참고값과 갈린 케이스 (원장에 기록됐거나 fixture가 의도를 선언한 것).
    diverged: int = 0
    #: `--only` 필터를 통과해 실제로 판정 대상이 된 케이스 수. 0이면 아무것도 검증하지 않은 것이다.
    considered: int = 0
    #: 이번 실행에서 관측한 참고 갈림 상태 (원장 갱신용).
    ledger: dict[str, dict[str, Any]] = field(default_factory=dict)
    #: 원장 대조에서 나온 문제. **`problems` 와 따로 담는다** — 평소에는 불충족으로 합류하지만
    #: `--record-reference` 실행에서는 "원장이 이렇게 바뀐다"의 사유 목록이 되기 때문이다.
    #: 두 모드가 같은 계산을 쓰므로 "기록 모드에서만 조용해지는" 자리가 생기지 않는다.
    ledger_problems: list[str] = field(default_factory=list)
    #: 참고값이 있어 원장 대조 범위에 든 케이스 id. 낡은 원장 항목 판정의 기준이다.
    referenced: set[str] = field(default_factory=set)


def domain_of(fixture_path: Path, fixture: dict[str, Any]) -> str:
    """fixture가 선언한 도메인. 선언이 없으면 디렉터리 이름으로 본다."""
    declared = fixture.get("domain")
    if isinstance(declared, str) and declared:
        return declared
    return fixture_path.parent.name


def structural_problems(pair: Pair, *, check_location: bool) -> list[str]:
    """fixture 자체의 결함 — 이 검사가 없으면 '빈 fixture'가 조용히 통과한다."""
    problems: list[str] = []
    cases = pair.fixture.get("cases")
    if not isinstance(cases, list):
        problems.append("- **fixture 결함** — `cases` 배열이 없다. 비교할 대상이 정의되지 않았다")
    elif not cases:
        problems.append(
            "- **빈 fixture** — 케이스가 0건이다. 0건 비교는 통과가 아니라 미검증이다. "
            f"`uv run python {_DUMP} --domain {pair.domain}` 로 다시 생성하라"
        )
    else:
        ids = pair.case_ids
        if len(ids) != len(cases):
            problems.append("- **fixture 결함** — id가 없는 케이스가 있다 (짝짓기가 불가능하다)")
        duplicates = sorted({name for name in ids if ids.count(name) > 1})
        if duplicates:
            problems.append(f"- **fixture 결함** — 중복 케이스 id: {', '.join(duplicates)}")
    if pair.domain not in BUILDERS:
        problems.append(
            f"- **알 수 없는 도메인** `{pair.domain}` — 생성기(`{_DUMP}`)의 BUILDERS에 없다. "
            "생성기 없이 손으로 만든 fixture는 Python 실행 결과라는 보장이 없다"
        )
    elif check_location and pair.fixture_path.parent.name != pair.domain:
        problems.append(
            f"- **fixture 위치 불일치** — domain은 `{pair.domain}`인데 디렉터리는 "
            f"`{pair.fixture_path.parent.name}`다. 도메인 존재 판정이 경로와 어긋난다"
        )
    problems += spec_shape_problems(pair)
    return problems


def spec_shape_problems(pair: Pair) -> list[str]:
    """spec 도메인의 **단언 구조**를 본다 — 성질 검증이 형식만 남는 것을 막는다.

    두 가지를 막는다.
    1. 단언 없는 케이스. 값 비교를 뺐는데 단언도 없으면 그 케이스는 아무것도 판정하지 않는다.
    2. 한 방향뿐인 도메인. "가려졌는가"만 재면 전문을 통째로 가린 구현이 만점을 받고,
       "남았는가"만 재면 아무것도 안 하는 구현이 만점을 받는다. 성질 검증에서 가장 흔한
       실패라 도메인마다 양방향을 강제한다.
    """
    if pair.mode != MODE_SPEC or pair.pending_spec:
        return []
    cases = pair.fixture.get("cases")
    if not isinstance(cases, list) or not cases:
        return []
    problems: list[str] = []
    directions: set[str] = set()
    for case in cases:
        if not isinstance(case, dict):
            continue
        entries = case.get("assert")
        if not isinstance(entries, list) or not entries:
            problems.append(
                f"- `{case.get('id')}` **단언 없는 spec 케이스** — 판정할 성질이 없다. "
                "값 비교도 하지 않으므로 이 케이스는 아무것도 검증하지 않는다"
            )
            continue
        for entry in entries:
            name = str(entry.get("check", "")) if isinstance(entry, dict) else ""
            if name in CHECKS:
                directions |= CHECKS[name].directions
    for missing, meaning in (("under", "덜 한 것(누락)"), ("over", "더 한 것(과잉)")):
        if missing not in directions:
            problems.append(
                f"- **방향 가드 결손** — 이 도메인에 `{missing}` 방향 검사가 하나도 없다"
                f"({meaning}을 잡을 수단이 없다). 한 방향만 재는 성질 검증은 "
                "반대 방향으로 망가진 구현을 통과시킨다"
            )
    return problems


# ------------------------------------------------------------------ fixture 정본 대조

#: 도메인별 정본 생성 결과 캐시. 한 실행에서 같은 도메인을 두 번 만들지 않는다
#: (argon2는 생성마다 해시를 여러 번 돌린다).
_CANONICAL: dict[str, tuple[dict[str, Any] | None, str | None]] = {}


def canonical_fixture(domain: str) -> tuple[dict[str, Any] | None, str | None]:
    """정본 생성기를 **다시 돌려** 이 도메인의 fixture를 만든다.

    반환: (정본, 실패 사유) — 둘 중 하나만 채워진다.

    왜 다시 돌리는가: 비교기는 주어진 fixture 파일을 그대로 믿었다. 그래서 도메인 이름별로
    기대값=actual인 가짜 케이스 하나씩만 남기면 수백 건의 경계·변조 케이스가 사라져도
    `전건 일치` + 종료 코드 0이 나왔다(교차 리뷰 X-2). 파일이 정본 생성기의 산출물인지는
    생성기를 돌려 대조하는 것 말고 확인할 방법이 없다.
    """
    if domain not in _CANONICAL:
        try:
            document = BUILDERS[domain]().document(domain)
        except Exception as exc:  # noqa: BLE001 - 생성 실패 원인은 가리지 않고 그대로 보고한다
            _CANONICAL[domain] = (None, f"정본 생성 실패 ({type(exc).__name__}: {exc})")
        else:
            _CANONICAL[domain] = (document, None)
    return _CANONICAL[domain]


def _comparable_case(case: dict[str, Any], volatile: frozenset[str], active: set[str]) -> Any:
    """대조 가능한 형태로 케이스를 다듬는다 — 난수 입력 필드만 뺀다.

    `expected`는 어느 도메인에서도 빼지 않는다. 난수가 섞이는 자리(prompts의 문서 id)는
    fixture가 선언한 정규화(`mask_document_id`)가 이미 접어 준다.
    """
    trimmed = {key: value for key, value in case.items() if key != "input"}
    payload = case.get("input")
    if isinstance(payload, dict):
        trimmed["input"] = {key: value for key, value in payload.items() if key not in volatile}
    elif payload is not None:
        trimmed["input"] = payload
    return normalize(trimmed, active)


def provenance_problems(pair: Pair) -> list[str]:
    """이 fixture가 **정본 생성기의 산출물인지** 확인한다.

    대조할 수 있는 것과 없는 것을 구분한다. 케이스 id 집합·개수·정규화 선언·source·기대값은
    언제나 대조 가능하다. crypto의 키/토큰과 argon2의 PHC는 매 생성 난수라 대조할 수 없고,
    그 자리만 `VOLATILE_INPUT_FIELDS`로 빼 둔다 — **빼는 자리를 늘리는 것이 곧 구멍이다.**
    """
    canonical, failure = canonical_fixture(pair.domain)
    if canonical is None:
        return [
            f"- **정본 대조 불가** — {failure}. fixture가 생성기 산출물인지 확인할 수 없으므로 "
            "통과로 보고하지 않는다"
        ]
    problems: list[str] = []
    # 헤더 전량을 대조한다. `mode`·`spec_status`가 특히 중요하다 — 파일에서 `spec`을
    # `compat`으로 바꿔 적으면 성질 검사를 건너뛰고 값 비교로 되돌아갈 수 있고,
    # `pending`을 `ready`로 바꾸면 단언 없는 도메인이 통과로 집계된다.
    for field_name in (key for key in canonical if key not in ("cases", "domain", "generated_at")):
        if pair.fixture.get(field_name) != canonical[field_name]:
            problems.append(
                f"- **fixture 위조 의심** — `{field_name}` 가 정본과 다르다 "
                f"(정본 {canonical[field_name]!r} / 파일 {pair.fixture.get(field_name)!r})"
            )
    canonical_ids = [str(case["id"]) for case in canonical["cases"]]
    fixture_ids = pair.case_ids
    missing = [name for name in canonical_ids if name not in set(fixture_ids)]
    extra = [name for name in fixture_ids if name not in set(canonical_ids)]
    if missing:
        problems.append(
            f"- **케이스 {len(missing)}건이 정본에서 사라졌다** (정본 {len(canonical_ids)}건 중) — "
            f"{', '.join(missing[:MAX_REPORTED_CASE_DIFFS])}"
            f"{' 외' if len(missing) > MAX_REPORTED_CASE_DIFFS else ''}. "
            "케이스를 지우고 돌린 결과는 '통과'가 아니라 '그만큼 검증하지 않음'이다"
        )
    if extra:
        problems.append(
            f"- **정본에 없는 케이스 {len(extra)}건** — "
            f"{', '.join(extra[:MAX_REPORTED_CASE_DIFFS])}"
            f"{' 외' if len(extra) > MAX_REPORTED_CASE_DIFFS else ''}. "
            f"손으로 만든 케이스는 Python 실행 결과라는 보장이 없다"
        )
    if not missing and not extra and fixture_ids != canonical_ids:
        problems.append("- **케이스 순서가 정본과 다르다** — fixture는 생성기 출력 그대로여야 한다")
    # 값 대조에는 **정본이 선언한** 정규화를 쓴다. 파일 쪽 선언은 위조 대상이므로 근거가 못 된다.
    active, _ = _rules(list(canonical["normalization"]))
    volatile = VOLATILE_INPUT_FIELDS.get(pair.domain, frozenset())
    canonical_by_id = {str(case["id"]): case for case in canonical["cases"]}
    raw_cases = pair.fixture.get("cases")
    differing = 0
    for case in raw_cases if isinstance(raw_cases, list) else []:
        if not isinstance(case, dict) or str(case.get("id")) not in canonical_by_id:
            continue
        case_id = str(case["id"])
        want = _comparable_case(canonical_by_id[case_id], volatile, active)
        got = _comparable_case(case, volatile, active)
        if equal(want, got, DEFAULT_FLOAT_TOL):
            continue
        differing += 1
        if differing <= MAX_REPORTED_CASE_DIFFS:
            difference = first_difference(want, got, DEFAULT_FLOAT_TOL)
            problems.append(f"- `{case_id}` **정본과 다르다** — {difference}")
    if differing > MAX_REPORTED_CASE_DIFFS:
        problems.append(
            f"- (정본과 다른 케이스 {differing}건 중 앞 {MAX_REPORTED_CASE_DIFFS}건만 적었다)"
        )
    if problems:
        problems.append(
            f"- 닫는 방법: `uv run python {_DUMP} --domain {pair.domain}` 로 다시 생성한다. "
            "Python 쪽 동작이 바뀌어서 난 차이라면 재생성 diff가 그 변경 목록이다"
        )
    return problems


# --------------------------------------------------------------- 역방향(external) 검증


def runtime_problem(document: dict[str, Any], path: Path) -> str | None:
    """결과 파일이 어느 런타임의 산출물이라고 **선언**했는지 본다.

    이 검사만으로는 아무것도 보장하지 못한다 — 문자열 한 줄이라 손으로 적을 수 있다. 그런데도
    보는 이유는 예전 비교기가 이 필드를 **한 번도 읽지 않아**(교차 리뷰 X-11) `runtime`이
    `not-kotlin`인 결과 파일도 그대로 통과했기 때문이다. 실수로 Python 하네스 출력을 넣은
    경우를 잡는 것이 이 검사의 현실적인 값어치다.
    """
    declared = document.get("runtime")
    if not isinstance(declared, str) or not declared.strip():
        return (
            f"- **runtime 미선언** ({path}) — 결과 파일 최상위에 "
            f'`"runtime": "{KOTLIN_RUNTIME}"` 이 필요하다'
        )
    if declared.strip().lower() != KOTLIN_RUNTIME:
        return (
            f"- **runtime 이 `{KOTLIN_RUNTIME}` 이 아니다**: {declared!r} ({path}) — "
            "Kotlin이 낸 결과가 아니면 비교 대상이 아니다"
        )
    return None


def _safe_load(path: Path) -> tuple[dict[str, Any] | None, str | None]:
    """읽기 실패로 실행 전체를 중단시키지 않는 로더 (역방향 산출물용)."""
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return (None, f"{path} 를 읽을 수 없다 ({type(exc).__name__})")
    if not isinstance(loaded, dict):
        return (None, f"{path} 의 최상위가 JSON 객체가 아니다")
    return (loaded, None)


def check_external(case: dict[str, Any], pair: Pair) -> tuple[str | None, str | None]:
    """역방향 케이스를 **검증기를 직접 돌려** 판정한다.

    반환: (문제, 미검증 사유) — 둘 중 하나만 채워지거나 둘 다 비어 있다(=검증됨).

    예전에는 `verify-*`가 남긴 증거 파일의 `fixture_case`·`status`·`checked` 세 값만 읽었다.
    그 세 값을 손으로 적은 6줄짜리 JSON이 "외부 검증 1건"으로 인정됐다(교차 리뷰 X-1).
    지금은 증거 파일을 **읽지 않는다.** Kotlin 산출물을 찾아 Python 검증기를 그 자리에서
    돌리고, 그 실행의 기록을 같은 경로에 덮어쓴다.
    """
    case_id = str(case["id"])
    verification = case.get("verification") or {}
    command = str(verification.get("command") or "")
    actual_file = verification.get("actual_file")
    proof_name = verification.get("proof")
    if command not in VERIFIERS or not actual_file or not proof_name:
        return (
            f"- `{case_id}` **fixture 결함** — verification 에 command·actual_file·proof 가 "
            f"모두 있어야 한다 (`uv run python {_DUMP} --domain {pair.domain}` 로 재생성)",
            None,
        )
    kotlin_path = pair.actual_path.parent / str(actual_file)
    proof_path = pair.actual_path.parent / str(proof_name)
    if not kotlin_path.exists():
        orphan = ""
        if proof_path.exists():
            orphan = (
                f"\n  - **증거 파일만 있고 산출물이 없다**: {proof_path}. 증거 파일은 이제 "
                "판정의 근거가 아니다(비교기가 검증기를 직접 돌린다) — 미검증으로 센다"
            )
        return (
            None,
            f"- `{case_id}` **미검증** — Kotlin 산출물 없음: {kotlin_path}\n"
            f"  - 닫는 방법: Kotlin이 `verification.actual_schema` 형식으로 그 파일을 만들면 "
            f"비교기가 `{command}` 검증기를 직접 돌려 판정한다\n"
            f"  - 이 케이스가 남아 있는 한 역방향(Kotlin → Python) 호환성은 미증명이다{orphan}",
        )
    document, read_failure = _safe_load(kotlin_path)
    if document is None:
        return (f"- `{case_id}` **역방향 산출물 읽기 실패** — {read_failure}", None)

    bad_runtime = runtime_problem(document, kotlin_path)
    if bad_runtime:
        outcome = VerificationOutcome(0, 1, [bad_runtime.lstrip("- ")], True)
    else:
        try:
            outcome = VERIFIERS[command](document, case.get("input"))
        except Exception as exc:  # noqa: BLE001 - 검증기가 죽으면 그 자체가 판정 불가다
            return (
                f"- `{case_id}` **검증기 실행 실패** — {command} ({type(exc).__name__}: {exc}). "
                "검증하지 못한 것을 통과로 세지 않는다",
                None,
            )
    target = write_proof_record(
        command=command,
        actual_path=kotlin_path,
        fixture_path=pair.fixture_path,
        proof_path=proof_path,
        outcome=outcome,
        produced_by="compare_parity.py",
        actual_runtime=document.get("runtime"),
    )
    required = max(int(verification.get("required_cases", 1)), outcome.required)
    if outcome.failures:
        detail = "; ".join(outcome.failures[:MAX_REPORTED_CASE_DIFFS])
        return (f"- `{case_id}` **역방향 검증 실패** — {detail} (기록: {target})", None)
    if outcome.checked < required:
        return (
            f"- `{case_id}` **역방향 검증 표본 부족** — {required}건이 필요한데 "
            f"{outcome.checked}건만 통과했다 (기록: {target})",
            None,
        )
    return (None, None)


# --------------------------------------------------------------------- 참고 갈림 원장
#
# 원장은 "Kotlin 산출물이 Python 참고값과 어디서 갈렸는가"의 **기록**이다. 판정이 아니다.
# 값이 갈렸다는 사실 자체는 잘못이 아니지만(둘 중 하나가 틀렸다는 신호일 뿐이다), 아무도
# 모르게 갈리는 것은 잘못이다. 그래서 기록되지 않은 갈림만 막는다.
#
# 본문·개인정보를 원장에 넣지 않는다 — 갈린 첫 경로와 양쪽 값의 SHA-256만 남긴다.


def digest(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def ledger_file(root: Path, domain: str) -> Path:
    return root / f"{domain}.json"


def load_ledger(root: Path, domain: str) -> dict[str, Any]:
    path = ledger_file(root, domain)
    if not path.exists():
        return {}
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    cases = loaded.get("cases") if isinstance(loaded, dict) else None
    return cases if isinstance(cases, dict) else {}


def write_ledger(root: Path, domain: str, entries: dict[str, dict[str, Any]]) -> Path:
    path = ledger_file(root, domain)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "domain": domain,
        "note": (
            "Kotlin 산출물과 Python 참고값이 갈린 자리의 기록이다. 판정 근거가 아니라 "
            "리뷰에 올리기 위한 원장이며, 본문 없이 경로와 SHA-256만 남긴다. "
            "갱신: compare_parity.py --record-reference"
        ),
        "recorded_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cases": dict(sorted(entries.items())),
    }
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    return path


def reference_problems(
    case_id: str,
    entry: dict[str, Any],
    recorded: Any,
    ledger_root: Path,
    domain: str,
) -> list[str]:
    """원장과 이번 관측을 대조한다. 닫는 방법은 언제나 `--record-reference`다.

    `reference_divergence: "expected"` 선언은 **면제가 아니다.** 예전에는 그 선언이 붙은
    케이스가 원장 기록과 이 대조를 통째로 건너뛰어(`continue`가 `ledger[...] = observed`
    앞에 있었다), 갈림의 **내용이 바뀌어도** 아무도 몰랐다 — 한 단어를 붙이면 불일치 보고가
    사라지는 자기 선언식 면제였다(X-10/S-2). 지금 그 선언이 하는 일은 **더하기뿐**이다:
    갈림이 사라지면 실패를 하나 추가한다. 원장 요구는 그대로 남는다.
    """
    closing = (
        f"  - 닫는 방법: `--record-reference` 로 `{ledger_file(ledger_root, domain)}` 를 "
        "갱신하고 그 diff를 리뷰에 올린다 (구현을 되돌리라는 뜻이 아니다)"
    )
    declared_note = (
        ' (fixture가 `reference_divergence: "expected"` 로 선언했더라도 원장 기록은 '
        "면제되지 않는다 — 선언은 갈림이 사라졌을 때의 가드일 뿐이다)"
        if entry.get("declared")
        else ""
    )
    if not isinstance(recorded, dict):
        if entry["status"] == "agree":
            return []
        return [
            f"- `{case_id}` **기록되지 않은 참고 갈림** — Python 참고값과 다르다 "
            f"(첫 차이 {entry['first_diff_path']}). 차이 자체는 차단 사유가 아니지만 "
            f"기록 없이 통과시키지 않는다{declared_note}\n{closing}"
        ]
    if recorded.get("status") != entry["status"]:
        return [
            f"- `{case_id}` **원장이 낡았다** — 기록 `{recorded.get('status')}` / 관측 "
            f"`{entry['status']}`. 갈림이 생겼거나 사라졌다\n{closing}"
        ]
    if bool(recorded.get("declared")) != bool(entry.get("declared")):
        return [
            f"- `{case_id}` **갈림 선언이 바뀌었다** — 기록 "
            f"`declared={bool(recorded.get('declared'))}` / 관측 "
            f"`declared={bool(entry.get('declared'))}`. fixture에 "
            '`reference_divergence: "expected"` 가 붙거나 빠졌다는 뜻이고, 그 변경은 '
            f"원장 diff로 리뷰에 올라가야 한다\n{closing}"
        ]
    if entry["status"] == "diverge" and recorded.get("actual_sha256") != entry["actual_sha256"]:
        return [
            f"- `{case_id}` **갈림의 내용이 바뀌었다** — 기록된 산출물 해시와 다르다 "
            f"(첫 차이 {entry['first_diff_path']}){declared_note}\n{closing}"
        ]
    return []


def stale_ledger_problems(
    domain: str, recorded: dict[str, Any], referenced: set[str], ledger_root: Path
) -> list[str]:
    """원장에는 있는데 이번 관측 범위에 **없는** 항목을 찾는다.

    대조 루프는 fixture 케이스만 돈다. 그래서 fixture에서 사라졌거나 `reference`를 잃은
    케이스의 원장 항목은 다시 방문되지 않고 조용히 남았다(X-11). 남은 항목은 "이 갈림은
    검토됐다"는 인상을 계속 주면서 실제로는 아무것도 가리키지 않는다.

    호출자는 **도메인 전체를 판정한 실행에서만** 이것을 부른다 — `--only` 로 범위를 좁힌
    실행에서 부르면 돌리지 않은 케이스가 전부 낡은 항목으로 보인다.
    """
    stale = sorted(set(recorded) - referenced)
    if not stale:
        return []
    shown = ", ".join(stale[:MAX_REPORTED_CASE_DIFFS])
    return [
        f"- **낡은 원장 항목 {len(stale)}건** — `{ledger_file(ledger_root, domain)}` 에 있는데 "
        f"이번 fixture의 참고 대조 범위에 없다: {shown}"
        f"{' 외' if len(stale) > MAX_REPORTED_CASE_DIFFS else ''}\n"
        "  - 케이스가 지워졌거나 `reference` 가 사라졌다는 뜻이다. 남겨 두면 '검토된 갈림'처럼 "
        "보이면서 아무것도 가리키지 않는다\n"
        f"  - 닫는 방법: `--record-reference` 로 원장을 다시 쓰고 그 diff를 리뷰에 올린다"
    ]


def compare_file(
    pair: Pair,
    only: str | None = None,
    *,
    ledger_cases: dict[str, Any] | None = None,
    ledger_root: Path = DEFAULT_LEDGER,
) -> FileResult:
    fixture = pair.fixture
    actual_doc = load(pair.actual_path)
    file_rules = list(fixture.get("normalization", []))

    result = FileResult()
    bad_runtime = runtime_problem(actual_doc, pair.actual_path)
    if bad_runtime:
        result.problems.append(bad_runtime)
    actual_cases: dict[str, Any] = {}
    for entry in actual_doc.get("cases", []):
        if not isinstance(entry, dict) or not entry.get("id"):
            result.problems.append("- **결과 파일 결함** — id 없는 케이스 항목이 있다")
            continue
        case_id = str(entry["id"])
        if case_id in actual_cases:
            result.problems.append(
                f"- `{case_id}` **결과 파일에 같은 id가 두 번** — 뒤엣것이 앞엣것을 덮어 "
                "한 건이 비교되지 않는다"
            )
        actual_cases[case_id] = entry.get("actual")

    raw_cases = fixture.get("cases")
    fixture_cases: list[dict[str, Any]] = raw_cases if isinstance(raw_cases, list) else []
    pending_spec = 0
    for case in fixture_cases:
        case_id = case["id"]
        if only is not None and case_id != only:
            continue
        result.considered += 1
        verification = case.get("verification") or {}
        if verification.get("mode") == "external":
            if case_id in actual_cases:
                # 그 자리에 기대값을 베껴 넣는 것이 정확히 이 게이트를 무력화하는 경로다.
                result.problems.append(
                    f"- `{case_id}` **역방향 케이스를 Kotlin 결과로 닫으려 했다** — 이 케이스는 "
                    "값 비교 대상이 아니다. "
                    f"`{verification.get('actual_file')}` 산출물을 만들어라"
                )
                continue
            problem, pending = check_external(case, pair)
            if problem:
                result.problems.append(problem)
            elif pending:
                result.pendings.append(pending)
            else:
                result.external_ok += 1
            continue
        active, tolerance = _rules(list(case.get("normalization", file_rules)))
        if pair.pending_spec:
            pending_spec += 1
            continue
        if case_id not in actual_cases:
            result.problems.append(f"- `{case_id}` **미실행** — Kotlin 결과에 이 케이스가 없다")
            continue
        got_raw = actual_cases[case_id]
        reproduce = (
            f"  - 재현: `uv run python .claude/skills/python-kotlin-parity/scripts/"
            f"compare_parity.py --fixture {pair.fixture_path} "
            f"--actual {pair.actual_path} --only {case_id}`\n"
            f"  - source: {case.get('source', fixture.get('source', '?'))}"
        )
        if pair.mode == MODE_COMPAT:
            # 값이 같은 것이 곧 요구사항인 자리다 — 롤백 창에서 Python이 이 산출물을 읽는다.
            result.checked += 1
            expected = normalize(case["expected"], active)
            got = normalize(got_raw, active)
            if sorted(placeholders_of(expected)) != sorted(placeholders_of(case["expected"])):
                result.problems.append(
                    f"- `{case_id}` **정규화 오류** — 정규화가 자리표시자를 바꿨다. 규칙을 고쳐라"
                )
                continue
            if not equal(expected, got, tolerance):
                result.problems.append(
                    f"- `{case_id}` **호환성 불일치** (compat 도메인 — 값이 같아야 한다)\n"
                    f"  - 최초 차이: {first_difference(expected, got, tolerance)}\n"
                    f"  - 입력: `{json.dumps(case['input'], ensure_ascii=False)[:200]}`\n"
                    f"  - 정규화: {', '.join(sorted(active)) or '없음'}\n" + reproduce
                )
            continue

        # ── spec 도메인: 요구 성질을 실행해 판정한다 ─────────────────────────
        result.judged += 1
        entries = case.get("assert")
        result.assertions += len(entries) if isinstance(entries, list) else 0
        failures = run_assertions(case, normalize(got_raw, active), active)
        if failures:
            detail = "\n".join(f"  - {reason}" for reason in failures)
            result.problems.append(
                f"- `{case_id}` **요구 성질 불충족** ({len(failures)}건)\n{detail}\n"
                f"  - 이 케이스가 지키는 것: {case.get('description', '(설명 없음)')}\n"
                f"  - 입력: `{json.dumps(case['input'], ensure_ascii=False)[:200]}`\n" + reproduce
            )
        if failures or "reference" not in case:
            # 성질을 못 지킨 케이스의 참고 갈림은 이미 설명된 차이다. 원장에 넣지도, 다시
            # 보고하지도 않는다 — 고장난 산출물을 원장에 굳히면 그것이 다음 기준이 된다.
            continue
        # 참고 대조 — 판정이 아니라 기록이다.
        want = normalize(case["reference"], active)
        got = normalize(got_raw, active)
        agrees = equal(want, got, tolerance)
        if not agrees:
            result.diverged += 1
        declared = case.get("reference_divergence") == "expected"
        if declared and agrees:
            result.problems.append(
                f"- `{case_id}` **의도한 갈림이 사라졌다** — fixture는 이 케이스가 Python "
                "참고값과 갈릴 것이라 선언했는데 같은 값이 나왔다. 요구사항이 바뀌었거나 "
                "선언이 낡았다(Python 쪽이 따라 바뀐 경우 포함)\n"
                f"  - 닫는 방법: fixture를 재생성하고 선언이 여전히 맞는지 확인한다\n" + reproduce
            )
        # **선언 여부와 무관하게** 원장에 기록하고 대조한다. 예전에는 여기서 `continue` 로
        # 빠져나가 선언된 케이스가 원장·`reference_problems()` 밖에 있었다(X-10/S-2).
        first_path = "" if agrees else first_difference(want, got, tolerance).split(":")[0]
        observed: dict[str, Any] = {
            "status": "agree" if agrees else "diverge",
            "declared": declared,
            "first_diff_path": first_path,
            "reference_sha256": digest(want),
            "actual_sha256": digest(got),
        }
        result.referenced.add(case_id)
        result.ledger[case_id] = observed
        result.ledger_problems += reference_problems(
            case_id, observed, (ledger_cases or {}).get(case_id), ledger_root, pair.domain
        )
    if pending_spec:
        result.pendings.append(
            f"- **{pending_spec}건 미검증** — 이 도메인은 `spec_status=pending` 이다. "
            "요구 성질이 아직 적히지 않아 판정할 근거가 없다\n"
            "  - 닫는 방법: 생성기의 이 도메인 빌더에 `assert` 목록을 넣고 "
            "`spec_status`를 ready로 바꾼다 (요구사항 문장은 fixture의 `requirement`에 있다)\n"
            "  - 값 비교로 대신하지 않는다 — 그것이 이번에 폐기된 전제다"
        )
    if only is None:
        for extra in set(actual_cases) - set(pair.case_ids):
            result.problems.append(
                f"- `{extra}` 기대값 없는 케이스 — fixture를 다시 생성했는지 확인"
            )
    return result


def collect_pairs(fixture_root: Path, actual_root: Path, domains: list[str]) -> list[Pair]:
    """fixture를 읽어 도메인까지 판정한 비교 쌍 목록. 디렉터리면 같은 상대 경로끼리 짝짓는다."""
    directory_mode = fixture_root.is_dir()
    paths = sorted(fixture_root.rglob("*.json")) if directory_mode else [fixture_root]
    pairs: list[Pair] = []
    for fixture_path in paths:
        fixture = load(fixture_path)
        domain = domain_of(fixture_path, fixture)
        if domains and domain not in domains:
            continue
        actual_path = (
            actual_root / fixture_path.relative_to(fixture_root) if directory_mode else actual_root
        )
        mode, status = resolve_mode(domain, fixture)
        pairs.append(Pair(fixture_path, actual_path, domain, fixture, mode, status))
    return pairs


def resolve_mode(domain: str, fixture: dict[str, Any]) -> tuple[str, str]:
    """판정 방식(mode·spec_status)을 **정본에서** 정한다.

    파일 쪽 선언을 믿으면 `mode: compat`으로 바꿔 적는 것만으로 성질 검사를 건너뛸 수 있다.
    정본을 만들 수 없을 때만 파일 선언으로 물러서되, 그 상태는 정본 대조가 이미 결함으로
    보고한다(통과로 집계되지 않는다).
    """
    canonical, _ = canonical_fixture(domain) if domain in BUILDERS else (None, None)
    source = canonical if canonical is not None else fixture
    mode = source.get("mode")
    status = source.get("spec_status", "ready")
    return (
        mode if isinstance(mode, str) and mode in (MODE_SPEC, MODE_COMPAT) else MODE_COMPAT,
        str(status),
    )


def missing_section(missing: list[str], fixture_root: Path) -> str:
    """무엇이 빠졌는지 **도메인 이름으로** 찍는다 — '파일 8개 비교'로는 아무도 못 알아챈다."""
    lines = [
        f"# parity 도메인 누락 리포트 ({len(missing)}개)",
        "",
        f"기대 집합의 정본은 `{_DUMP}`의 `BUILDERS` 키 {len(EXPECTED_DOMAINS)}개다. "
        "비교기에 주어지지 않은 도메인은 '통과'가 아니라 '검증하지 않음'이다.",
        "",
    ]
    lines += [
        f"- **{domain}** — fixture 없음: `{fixture_root / domain}/*.json`" for domain in missing
    ]
    lines += [
        "",
        "> 닫는 방법: `uv run python "
        + _DUMP
        + " "
        + " ".join(f"--domain {domain}" for domain in missing)
        + "` 로 fixture를 만들고, Kotlin 결과를 같은 상대 경로에 둔 뒤 다시 돌린다.",
        f"> 지금 한 도메인만 보고 싶다면 `--only-domain {missing[0]}` 으로 범위를 **명시**한다 — "
        "그 결과는 부분 검증이고 게이트를 닫는 근거가 아니다.",
    ]
    return "\n".join(lines)


class _Parser(argparse.ArgumentParser):
    """사용법 오류를 종료 코드 1로 끝낸다.

    argparse 기본값은 2인데 이 스크립트에서 2는 "미검증 케이스가 남았다"는 **판정 결과**다.
    인자를 잘못 준 것과 검증이 덜 끝난 것이 같은 코드로 나가면 호출자가 둘을 구분할 수 없다.
    """

    def error(self, message: str) -> NoReturn:
        self.print_usage(sys.stderr)
        raise SystemExit(f"[중단] {self.prog}: {message}")


def main() -> int:
    parser = _Parser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fixture", type=Path, required=True, help="fixture 파일 또는 디렉터리")
    parser.add_argument("--actual", type=Path, required=True, help="Kotlin 결과 파일 또는 디렉터리")
    parser.add_argument("--only", help="이 케이스 id만 비교 (부분 검증)")
    parser.add_argument(
        "--only-domain",
        action="append",
        default=[],
        metavar="도메인",
        help=(
            "이 도메인만 판정한다 (반복 가능). **부분 검증**이므로 게이트를 닫는 근거가 아니다. "
            f"가능: {', '.join(EXPECTED_DOMAINS)}"
        ),
    )
    parser.add_argument("--report-md", type=Path, help="불일치 리포트를 마크다운으로 저장")
    parser.add_argument(
        "--ledger",
        type=Path,
        default=DEFAULT_LEDGER,
        help=f"참고 갈림 원장 디렉터리 (기본 {DEFAULT_LEDGER})",
    )
    parser.add_argument(
        "--record-reference",
        action="store_true",
        help=(
            "이번 실행에서 관측한 참고 갈림을 원장에 기록한다. 갈림을 **승인**하는 것이 아니라 "
            "리뷰에 올릴 diff를 만드는 것이다. 원장이 바뀌면 종료 코드 4로 끝나고 그 실행으로는 "
            "게이트를 닫지 못한다 — 판정은 이 플래그 **없이** 다시 돌린 결과로 한다"
        ),
    )
    args = parser.parse_args()

    selected: list[str] = list(dict.fromkeys(args.only_domain))
    unknown = [domain for domain in selected if domain not in BUILDERS]
    if unknown:
        parser.error(
            f"알 수 없는 도메인: {', '.join(unknown)} (가능: {', '.join(EXPECTED_DOMAINS)})"
        )
    directory_mode = args.fixture.is_dir()
    if selected and not directory_mode:
        parser.error(
            "--only-domain 은 --fixture 가 디렉터리일 때만 쓴다 (단일 파일은 이미 부분 검증이다)"
        )

    pairs = collect_pairs(args.fixture, args.actual, selected)
    if args.only is not None:
        # `--only`는 그 케이스를 가진 fixture만 본다. 이 필터가 없으면 무관한 도메인의
        # "결과 파일 없음"이 쏟아지고, 반대로 필터를 사후에 걸면 존재하지 않는 id를 줬을 때
        # 모든 문제가 지워져 exit 0이 나온다(그 자체가 우회 경로였다).
        pairs = [pair for pair in pairs if args.only in pair.case_ids]
        if not pairs:
            raise SystemExit(
                f"[중단] `{args.only}` 케이스를 가진 fixture가 없다 — 검증한 것이 없다"
            )

    found_domains = {pair.domain for pair in pairs}
    #: `parity/fixtures/crypto`처럼 **도메인 디렉터리 자체**를 넘긴 것은 경로로 범위를 선언한
    #: 것이다. 전체 집합을 요구하지 않고 부분 검증으로 판정한다 — 다만 게이트를 닫지는 못한다.
    domain_dir = (
        directory_mode
        and not selected
        and args.fixture.name in BUILDERS
        and found_domains == {args.fixture.name}
    )
    if not directory_mode or domain_dir:
        # 파일 하나 또는 도메인 디렉터리 하나를 지목한 것 자체가 범위 선언이다.
        expected_domains = tuple(sorted(found_domains))
    elif selected:
        expected_domains = tuple(selected)
    else:
        expected_domains = EXPECTED_DOMAINS
    missing = [domain for domain in expected_domains if domain not in found_domains]

    scope_notes: list[str] = []
    if not directory_mode:
        scope_notes.append("단일 fixture 파일 지정")
    if domain_dir:
        scope_notes.append(f"도메인 디렉터리 지정 ({args.fixture.name})")
    if selected:
        scope_notes.append(f"--only-domain {' '.join(selected)}")
    if args.only:
        scope_notes.append(f"--only {args.only}")
    partial = bool(scope_notes)

    sections: list[str] = []
    pending_sections: list[str] = []
    total_problems = 0
    total_pending = 0
    total_checked = 0
    total_judged = 0
    total_assertions = 0
    total_external = 0
    total_considered = 0
    total_diverged = 0
    recorded: dict[str, dict[str, dict[str, Any]]] = {}
    referenced: dict[str, set[str]] = {}
    #: 원장 대조 결과. 평소에는 불충족으로 합류하고, `--record-reference` 에서는 "원장이
    #: 이렇게 바뀐다"의 사유가 된다. **두 모드가 같은 계산을 쓴다** — 기록 모드에서만
    #: 조용해지는 자리를 만들지 않기 위해서다.
    ledger_findings: dict[str, list[str]] = {}
    #: 도메인 전체를 판정했는가(결과 파일이 다 있었는가). 낡은 원장 항목 판정의 전제다.
    fully_compared: dict[str, bool] = {}
    for pair in pairs:
        problems = structural_problems(pair, check_location=directory_mode)
        if pair.domain in BUILDERS:
            # 정본 대조는 actual 유무와 무관하다 — fixture가 위조됐다면 actual이 없어도 결함이다.
            problems += provenance_problems(pair)
        if not pair.actual_path.exists():
            problems.append(f"- **Kotlin 결과 파일 없음**: {pair.actual_path}")
            fully_compared[pair.domain] = False
        else:
            result = compare_file(
                pair,
                args.only,
                ledger_cases=load_ledger(args.ledger, pair.domain),
                ledger_root=args.ledger,
            )
            problems += result.problems
            total_checked += result.checked
            total_judged += result.judged
            total_assertions += result.assertions
            total_external += result.external_ok
            total_considered += result.considered
            total_diverged += result.diverged
            total_pending += len(result.pendings)
            recorded.setdefault(pair.domain, {}).update(result.ledger)
            referenced.setdefault(pair.domain, set()).update(result.referenced)
            fully_compared.setdefault(pair.domain, True)
            if result.ledger_problems:
                ledger_findings.setdefault(pair.domain, []).extend(result.ledger_problems)
            if result.pendings:
                pending_sections.append(
                    f"## {pair.domain} · {pair.fixture_path.name}\n\n" + "\n".join(result.pendings)
                )
            if not problems and not result.ledger_problems and not result.pendings:
                judged = f"성질 {result.judged}건/단언 {result.assertions}개"
                shown = judged if pair.mode == MODE_SPEC else f"값 대조 {result.checked}건"
                print(f"[충족] {pair.domain} · {pair.fixture_path.name} — {shown}")
        if problems:
            total_problems += len(problems)
            sections.append(
                f"## {pair.domain} · {pair.fixture_path.name}\n\n" + "\n".join(problems)
            )

    # 낡은 원장 항목은 **도메인 단위**로 본다. 한 도메인에 fixture 파일이 여러 개일 수 있고
    # 원장은 도메인마다 하나이기 때문이다. 범위를 좁힌 실행(`--only`)이나 결과 파일이 없어
    # 대조하지 못한 도메인에서는 보지 않는다 — 돌리지 않은 케이스가 낡은 항목으로 보인다.
    if args.only is None:
        for domain in sorted(referenced):
            if not fully_compared.get(domain, False):
                continue
            stale = stale_ledger_problems(
                domain, load_ledger(args.ledger, domain), referenced[domain], args.ledger
            )
            if stale:
                ledger_findings.setdefault(domain, []).extend(stale)

    ledger_changed = sum(len(found) for found in ledger_findings.values())
    if args.record_reference:
        for domain, entries in sorted(recorded.items()):
            target = write_ledger(args.ledger, domain, entries)
            diverged = sum(1 for entry in entries.values() if entry["status"] == "diverge")
            print(f"[원장 기록] {target} — {len(entries)}건 중 갈림 {diverged}건")
        for domain, found in sorted(ledger_findings.items()):
            print(f"[원장 변경] {domain} — {len(found)}건")
            for line in found:
                print(f"  {line.splitlines()[0]}")
    else:
        for domain, found in sorted(ledger_findings.items()):
            sections.append(f"## {domain} · 참고 갈림 원장\n\n" + "\n".join(found))
            total_problems += len(found)

    report = ""
    if sections:
        report += (
            "# parity 불일치 리포트\n\n"
            + "\n\n".join(sections)
            + (
                "\n\n> 다음 행동: 위 최소 재현 입력으로 Kotlin 쪽을 고친다. "
                "fixture(Python 기대값)를 고쳐 통과시키는 것은 "
                "Python 쪽이 틀렸다는 근거가 있을 때뿐이다.\n"
            )
        )
    if missing:
        report += ("\n" if report else "") + missing_section(missing, args.fixture) + "\n"
    if pending_sections:
        report += (
            ("\n" if report else "")
            + f"# parity 미검증 리포트 ({total_pending}건)\n\n"
            + "\n\n".join(pending_sections)
            + (
                "\n\n> 미검증은 통과가 아니다. 요구 성질이 적히지 않았거나(spec_status=pending) "
                "역방향(Kotlin → Python) 산출물이 없어서 **판정할 근거가 없는** 상태이므로 "
                "게이트를 닫지 않는다.\n"
            )
        )
    if report:
        print(report, file=sys.stderr)
        if args.report_md:
            args.report_md.parent.mkdir(parents=True, exist_ok=True)
            args.report_md.write_text(report, encoding="utf-8")
            print(f"[리포트] {args.report_md}")

    covered = len(expected_domains) - len(missing)
    if partial:
        untouched = len(EXPECTED_DOMAINS) - len(found_domains)
        print(
            f"[부분 검증] {' · '.join(scope_notes)} — 판정한 도메인 "
            f"{', '.join(sorted(found_domains)) or '없음'} "
            f"(기대 집합 {len(EXPECTED_DOMAINS)}개 중 {untouched}개는 돌리지 않았다)"
        )
        print(
            "  이 결과는 게이트를 닫는 근거가 아니다. 전체 게이트는 fixture·actual 루트를 "
            "도메인 지정 없이 넘겨 종료 코드 0이 나온 결과로만 닫는다."
        )
    elif missing:
        print(
            f"[전체 게이트] 기대 도메인 {len(expected_domains)}개를 요구한다 — "
            f"{covered}개만 주어졌다 (정본: dump_parity_fixtures.py BUILDERS)"
        )
    else:
        print(
            f"[전체 게이트] 기대 도메인 {covered}/{len(expected_domains)}개 전부를 "
            "판정 범위에 넣었다 (정본: dump_parity_fixtures.py BUILDERS)"
        )

    summary = (
        f"도메인 {covered}/{len(expected_domains)} / 성질 판정 {total_judged}건"
        f"(단언 {total_assertions}개) / 호환 값 대조 {total_checked}건 / "
        f"외부 검증 {total_external}건 / 참고 갈림 {total_diverged}건 / "
        f"미검증 {total_pending}건 / 불충족 {total_problems}건 / "
        f"도메인 누락 {len(missing)}개 / 파일 {len(pairs)}개"
    )
    if missing and not total_problems:
        print(f"[도메인 누락] {summary} — 없는 도메인: {', '.join(missing)} (종료 코드 1)")
        return 1
    if total_problems:
        detail = f" — 없는 도메인: {', '.join(missing)}" if missing else ""
        print(f"[불충족] {summary}{detail}")
        return 1
    if args.record_reference and ledger_changed:
        # 방금 원장을 바꾼 실행은 **판정이 아니다.** 예전에는 갈림 23건을 침묵시킨 실행과
        # 애초에 0건이던 실행이 둘 다 종료 코드 3을 냈다 — 이 파일이 스스로 세운 원칙
        # ("종료 코드는 자동화가 읽는 유일한 계약")을 자기가 어긴 자리였다(X-09).
        # 바뀐 것이 없으면 아래 정상 경로로 떨어진다. 그때는 관측이 커밋된 원장과 정확히
        # 같다는 뜻이고, 플래그 없이 돌린 실행과 판정이 **정의상 동일**하다.
        print(
            f"원장 기록으로 종료(판정 아님): {summary} — 원장 변경 {ledger_changed}건. "
            "이 실행은 게이트를 닫지 않는다. 원장 diff를 커밋해 리뷰에 올린 뒤, "
            "`--record-reference` **없이** 다시 돌린 결과로 판정한다 (종료 코드 4)"
        )
        return EXIT_RECORDED
    if total_pending:
        print(f"[미검증] {summary} — 충족으로 보고하지 않는다 (종료 코드 2)")
        return 2
    if total_considered == 0:
        print(f"[검증 없음] {summary} — 판정한 케이스가 0건이다. 통과로 보고하지 않는다")
        return 1
    if partial:
        # 0이 아니라 3이다. 자동화가 읽는 계약은 위에 찍은 "게이트 아님" 문구가 아니라
        # 종료 코드 하나뿐이므로, 여기서 0을 돌려주면 10개 도메인을 건너뛴 실행이
        # 전체 통과로 기록된다 (모듈 docstring "종료 코드" 절 참고).
        print(f"부분 검증 통과(게이트 아님): {summary}")
        return EXIT_PARTIAL_OK
    print(f"요구 성질 충족: {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
