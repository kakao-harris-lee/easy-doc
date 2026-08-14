#!/usr/bin/env python3
"""Kotlin 산출물이 **요구사항이 요구하는 성질**을 만족하는지 판정한다.

기준은 Python 출력이 아니다. Python 구현은 회귀가 잦아 옮기는 중이고, 사용자 결정
(2026-08-12)은 "출력을 Python과 동일하게 맞출 필요 없다. 요구사항을 구현하고 이후에
개선한다"이다. 그래서 판정 방식은 하나뿐이다.

    spec 도메인   fixture의 `assert` 목록을 Kotlin 산출물에 **실행**해 판정한다.
                  "가려야 할 것이 남았는가", "남겨야 할 것이 지워졌는가", "자리표시자를
                  되돌리면 원문이 복원되는가" 같은 성질이며, Python 출력을 보지 않는다.
                  Python 실행 결과는 `reference`(참고값)로 함께 오고, 다른 자리는
                  **참고 갈림 원장**에 기록된다 — 판정이 아니라 기록이다.

값 동일성으로 판정하던 `compat` 모드(crypto·jwt·argon2)와 역방향 외부 검증은
2026-08-12에 **지웠다.** 근거는 생성기(`dump_parity_fixtures.py`) 모듈 docstring의
"없어진 것" 문단에 있다 — 되살리기 전에 그것을 읽어라. 요약하면, 그 장치들은 "롤백 창에서
Python이 Kotlin 산출물을 읽는다"는 전제 위에 있었고 롤백을 포기하면서 전제가 사라졌다.
암호 검증 자체가 없어진 것은 아니다(round-trip·변조 거부·키 회전은 여전히 요구사항이며
Kotlin 자체 테스트와 `migration-safety-gate` 감사가 받는다).

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

신뢰 경계 — 이 스크립트가 보장하는 것과 보장하지 못하는 것:

    보장한다 (1) fixture가 정본 생성기(`dump_parity_fixtures.py`)의 산출물이다.
        비교할 때마다 생성기를 다시 돌려 케이스 id 집합·개수·순서·source·요구사항 선언·
        정규화 선언·**단언 목록**·참고값을 대조한다. 단언을 손으로 지워 게이트를 무르게
        만드는 경로가 여기서 막힌다. 대조에서 빠지는 자리는 난수 입력뿐인데
        (`VOLATILE_INPUT_FIELDS`) **지금은 그런 도메인이 없어 목록이 비어 있다.**
        거기에 한 줄을 더하는 것이 곧 구멍을 하나 내는 것이다.
    보장한다 (2) 단언이 **양방향**이다. 성질 검증의 고전적 실패는 한쪽으로만
        재는 것이다 — "가려졌는가"만 재면 전문을 통째로 가린 구현이 만점을 받는다. 그래서
        도메인마다 `under`(덜 한 것을 잡는다)와 `over`(더 한 것을 잡는다) 방향의 검사가
        **둘 다** 있어야 하고, 없으면 구조 결함으로 막는다.
    보장한다 (3) 마스킹 범주 문자열이 **API 계약과 같다.** 범주는 자리표시자에 그대로 박히는
        복원 키이고 React가 화면에 그대로 렌더링하는 문구다. 정본은 이 저장소가 아니라
        `contracts/easy-doc-v1.yaml`의 `MaskedItemResponse` 이며, 비교기는 그 파일을 직접
        읽어 fixture 선언과 대조한다. 예전에는 fixture가 넘긴 `categories` 인자와만
        대조해서, 생성기가 `["RRN","CARD"]`로 흘러가면 게이트는 통과하고 API는 계약을
        위반했다. 계약을 못 읽으면 통과가 아니라 **불충족**이다(fail closed).
    보장한다 (4) 참고 갈림이 **선언 한 줄로 면제되지 않는다.** `reference_divergence:
        "expected"` 는 원장 기록을 면제하지 않고 검사를 **하나 더한다**(갈림이 사라지면
        실패). 예전에는 그 선언이 원장 기록과 대조를 통째로 건너뛰어, 갈림의 내용이 바뀌어도
        아무도 몰랐다. 원장에 남았지만 이번 관측 범위에 없는 **낡은 항목**도 함께 보고한다.

    보장하지 못한다 (a) **그 산출물을 정말 Kotlin이 만들었는가.** fixture의 입력이 전부
        공개돼 있으므로 같은 값을 Python으로도 만들 수 있다. `runtime` 필드는 선언일 뿐이고
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
    1 = 성질 불충족·누락·읽기 실패(금지된 정규화 규칙, fixture가 정본과 다름,
        단언 없는 케이스, 방향 가드가 한쪽뿐인 도메인, **기록되지 않은 참고 갈림**,
        runtime 미선언·불일치 포함)
        **도메인 누락도 1이다.** 근거: 이미 "Kotlin 결과 파일 없음"(파일 누락)과
        "미실행"(케이스 누락)이 1로 나간다. 같은 성격의 누락을 입도가 커졌다는 이유로
        (케이스 → 파일 → 도메인) 더 약한 코드로 내보내면 "많이 지울수록 종료 코드가 약해지는"
        유인이 생긴다 — 그것이 정확히 이 게이트를 무력화하는 경로다.
    2 = 불충족은 없으나 미검증 케이스가 남음 — "돌렸다"이지 "증명됐다"가 아니다.
        **spec_status=pending** 도메인이 여기로 온다 — 요구 성질을 아직 적지 못해 판정할
        근거가 없는 상태다. 값 비교로 때우지 않고 미검증으로 세는 것이 이 하네스의 전제다.
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
        **"바뀌었다"의 기준은 쓴 내용과 이전 내용의 차이다** — 지적 건수가 아니다.
        한동안 기준이 `reference_problems()` 의 지적 건수였는데, 그것은 "원장이 낡았다"의
        척도이지 "원장이 바뀐다"의 척도가 아니다. 원장이 아직 **없고** 관측이 전부 `agree`
        이면 지적이 0건이라, 31건을 담은 원장을 새로 만들고도 0/3(성공)으로 끝났다.
        CI는 3을 통과로 읽으므로 "원장을 쓰고서 게이트 통과"가 성립했다(X-12).
        비교에서 빼는 것은 `recorded_at` 뿐이다(`LEDGER_VOLATILE_FIELDS`). 그것을 넣으면
        정반대로 고장난다 — 매 실행 값이 달라 **항상** 4가 되고 기록 실행이 영원히
        아무것도 닫지 못한다. 없음 → 있음은 변경으로 센다.
        기록 모드라도 **바뀐 것이 없으면** 0/2/3의 정상 경로로 떨어진다. 그때는 관측이
        커밋된 원장과 정확히 같다는 뜻이라 플래그 없이 돌린 실행과 판정이 정의상 같고,
        원장 파일도 다시 쓰지 않는다(빈 diff를 리뷰에 올리지 않기 위해서다).
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
    MODE_SPEC,
    REPO_ROOT,
    STATUS_PENDING,
    ContractError,
    mask_contract,
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
#:
#: **지금은 비어 있다.** 난수를 쓰던 유일한 자리가 crypto(Fernet 키·토큰)와 argon2(솔트)
#: 였고, 두 도메인은 2026-08-12에 사라졌다(모듈 docstring "없어진 것" 참고). 빈 채로
#: 남겨 두는 이유는 이 목록에 한 줄을 더하는 것이 곧 정본 대조에 구멍을 하나 내는 일이기
#: 때문이다 — 채우려는 사람이 이 문단을 먼저 읽게 하려고 기계는 그대로 둔다.
VOLATILE_INPUT_FIELDS: dict[str, frozenset[str]] = {}

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

#: 케이스 **정체성** 하한. 도메인별 케이스 id 스냅샷이고 비대칭으로 본다 —
#: 추가는 자유, **삭제·개명은 차단**. `.github/parity-*-floor.txt` 두 파일의 전례를 따르되
#: 대상이 도메인 이름이 아니라 **케이스 id**다.
#:
#: 왜 개수가 아니라 id 인가: 이 저장소가 이미 한 번 판정한 자리다(`86c6a99` — "개수를
#: 정체성으로 바꾼다"). 총개수 하한은 **순소실만** 막는다. 케이스 하나를 지우고 아무거나
#: 하나 더하면 개수가 그대로라 통과하고, 지워진 것이 하필 유일한 과잉 가드일 수 있다.
#: 개수는 정체성의 대리 지표이고, 대리 지표로 실물을 판정하는 것이 이 하네스가 금지하는 그것이다.
CASE_FLOOR_PATH = REPO_ROOT / ".github" / "parity-case-floor.txt"

#: **전체 게이트 도달 표시.** 이 파일이 있으면 "선언이 정본 전부를 덮은 적이 있다"는 뜻이고,
#: 그 뒤로 부분 게이트로 내려가는 것은 **강등**이라 차단한다.
#:
#: 왜 필요한가 (게이트 12 #3): 전체/부분 판정이 `declared == canonical` 이라는 **오늘의 상태**로
#: 계산된다. 그래서 생성기에 builder 를 하나 더하면 그 순간 등식이 깨져 부분 게이트로 내려가고,
#: 부분 게이트의 사면(종료 코드 3 통과)이 되살아난다. 8/8 은 **강제된 성질이 아니라 오늘 참인
#: 상태**였다. 이 파일이 그 상태를 하한으로 고정한다.
#:
#: 자기 무장(self-arming): 아직 도달하지 않았으면 이 파일은 없고 아무것도 막지 않는다. 도달하는
#: **순간**(선언이 정본 전부를 덮는 실행) 비교기가 "하한을 고정하라"며 막으므로, 만들어 두는 것을
#: 잊을 수 없다. 도달 0 인 채로 잠들어 있는 장치를 만들지 않으려고 이렇게 했다.
FULL_GATE_PATH = REPO_ROOT / ".github" / "parity-full-gate.txt"

#: `verdict_pending` 이 반드시 담아야 하는 것. 없으면 **탐지되지 않는 보류**가 된다 —
#: 마커만 붙여 두고 아무도 읽지 않던 옛 `known_gap` 이 정확히 그 상태였다(R-4).
#: 넷은 각각 다른 질문에 답한다: 무엇이 열려 있나 / 누가 닫나 / 언제까지 / 누가 회부했나.
VERDICT_PENDING_FIELDS: tuple[str, ...] = ("reason", "owner", "deadline", "referred_by")

#: 케이스 하한에서 "이 케이스는 보류 상태다"를 표시하는 꼬리표.
DEFERRED_FLOOR_SUFFIX = " !deferred"

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


def check_max_length(call: CheckCall) -> list[str]:
    """지정 경로의 문자열이 길이 상한을 넘지 않아야 한다 (over 방향).

    파일명 상한은 **값이 아니라 경계**다 — 어떤 제목이 와도 넘지 않아야 하는 것이지 특정
    길이여야 하는 것이 아니다. `equals_field` 로 파일명을 통째로 못박으면 정제 규칙(공백
    접기·앞뒤 점 깎기)까지 값으로 고정돼, 규칙을 고치는 순간 개선이 회귀로 잡힌다.
    """
    path = str(call.arg("path", "$"))
    got = call.target()
    if got is _MISSING:
        return [f"경로 `{path}` 가 산출물에 없다"]
    if not isinstance(got, str):
        return [f"경로 `{path}` 가 문자열이 아니다"]
    limit = call.arg("limit")
    if not isinstance(limit, int):
        return [f"`limit` 인자가 정수가 아니다: {limit!r}"]
    if len(got) > limit:
        return [
            f"`{path}` 가 길이 상한 {limit} 을 넘었다: {len(got)}자 — 파일 시스템·헤더가 "
            "긴 이름에서 잘리거나 거부한다"
        ]
    return []


def check_ascii_only(call: CheckCall) -> list[str]:
    """지정 경로의 문자열이 US-ASCII 안에 있어야 한다 (under 방향).

    HTTP 헤더 값이 그렇다. RFC 5987 `ext-value` 는 비ASCII를 **퍼센트 인코딩**해 싣게 되어
    있고, 인코딩하지 않고 원문을 그대로 넣으면 서버·프록시가 latin-1 로 해석하거나 거부한다.
    "한글 파일명이 깨진다"가 아니라 **응답 자체가 나가지 않는** 자리다.
    """
    path = str(call.arg("path", "$"))
    got = call.target()
    if got is _MISSING:
        return [f"경로 `{path}` 가 산출물에 없다"]
    if not isinstance(got, str):
        return [f"경로 `{path}` 가 문자열이 아니다"]
    offenders = sorted({ch for ch in got if ord(ch) > 0x7F})
    if offenders:
        shown = ", ".join(f"U+{ord(ch):04X}" for ch in offenders[:MAX_REPORTED_CASE_DIFFS])
        return [
            f"`{path}` 에 US-ASCII 밖 문자가 있다: {shown} — RFC 5987 은 비ASCII를 "
            "퍼센트 인코딩해 싣게 되어 있다. 원문을 그대로 넣으면 헤더가 깨진다"
        ]
    return []


def check_contains_derived(call: CheckCall) -> list[str]:
    """산출물의 목록이 **입력에서 유도한 항목 전부를 담아야** 한다 (하한, under 방향).

    `contains_all` 과 다른 점은 요구 목록이 fixture 리터럴이 아니라 **비교기가 입력에서
    계산한 값**이라는 것이다. 그래서 생산자가 무엇을 보고하든 요구가 흔들리지 않는다.

    담김 판정은 **부분 문자열**이다. 유도된 조각은 종결부호를 뗀 형태라, 구현이 보고하는
    문장(종결부호·머리말 포함)과 글자 단위로 같지 않다. 같기를 요구하면 문장 분리 방식을
    판정하게 되는데 그것은 이 도메인이 명시적으로 판정하지 않기로 한 자리다.
    """
    rule = str(call.arg("rule", ""))
    if rule not in DERIVATIONS:
        return [f"알 수 없는 유도 규칙: {rule!r} (가능: {', '.join(DERIVATIONS)})"]
    required, problems = DERIVATIONS[rule](call)
    if problems:
        return problems
    if not isinstance(required, list):
        return [f"유도 규칙 `{rule}` 이 목록을 내지 않았다"]
    path = str(call.arg("path", "$"))
    got = call.target()
    if got is _MISSING:
        return [f"경로 `{path}` 가 산출물에 없다"]
    # 배열이면 원소 하나가 조각을 품으면 되고(문장 목록), 문자열이면 그 안에 있으면 된다
    # (파일명). 둘 다 **부분 문자열**로 본다 — 유도된 조각은 경계가 잘린 형태라 글자 단위로
    # 같기를 요구하면 정제·분리 방식을 판정하게 되고, 그것이 이 하한이 피하려는 자리다.
    if isinstance(got, str):
        reported = [call.text(got)]
    elif isinstance(got, list):
        reported = [call.text(str(item)) for item in got]
    else:
        return [f"경로 `{path}` 가 배열도 문자열도 아니다"]
    missing = [
        item for item in required if not any(call.text(str(item)) in one for one in reported)
    ]
    if missing:
        shown = ", ".join(repr(item[:40]) for item in missing[:MAX_REPORTED_CASE_DIFFS])
        return [
            f"`{path}` 가 규칙 `{rule}` 이 요구하는 {len(missing)}건을 담지 않았다: {shown}"
            " — 이 조각들은 **입력에서 유도한 하한**이다. 어떤 구현 방식으로도 사라질 수 "
            "없는 것만 요구하므로, 없다면 규칙 차이가 아니라 누락이다"
        ]
    return []


def check_contains_entries(call: CheckCall) -> list[str]:
    """사전의 **표제어와 값이 함께** 보존돼야 한다 (C-4, under 방향).

    `contains_all` 은 표제어만 본다. 그래서 뜻풀이를 통째로 바꿔도 통과했다 — 쉬운 말 사전은
    **값이 곧 자산**이고(246개 실측 큐레이션) 그 값이 프롬프트에 그대로 실려 모델에게 간다.
    표제어만 지키는 검사는 "사전이 있다"만 말할 뿐 "사전이 그 사전인가"를 말하지 못한다.

    추가는 허용한다(포함 관계) — 값을 **바꾸는 것**만 막는다.
    """
    path = str(call.arg("path", "$"))
    got = call.target()
    if got is _MISSING:
        return [f"경로 `{path}` 가 산출물에 없다"]
    if not isinstance(got, dict):
        return [f"경로 `{path}` 가 객체가 아니다"]
    required = call.arg("required", [])
    if not isinstance(required, list):
        return ["`required` 는 [표제어, 값] 쌍의 배열이어야 한다"]
    missing: list[str] = []
    changed: list[str] = []
    for entry in required:
        if not isinstance(entry, list) or len(entry) != 2:
            return [f"`required` 항목이 [표제어, 값] 쌍이 아니다: {entry!r}"]
        key, value = str(entry[0]), entry[1]
        if key not in got:
            missing.append(key)
        elif not equal(call.text(value), call.text(got[key]), DEFAULT_FLOAT_TOL):
            changed.append(f"{key}: {value!r} → {got[key]!r}")
    reasons: list[str] = []
    if missing:
        shown = ", ".join(repr(k) for k in missing[:MAX_REPORTED_CASE_DIFFS])
        reasons.append(f"`{path}` 에서 표제어 {len(missing)}건이 빠졌다: {shown}")
    if changed:
        shown = "; ".join(changed[:MAX_REPORTED_CASE_DIFFS])
        reasons.append(
            f"`{path}` 에서 뜻풀이 {len(changed)}건이 달라졌다: {shown} — 값은 프롬프트에 "
            "그대로 실려 모델에게 간다. 표제어만 지키면 사전이 바뀐 것을 못 본다"
        )
    return reasons


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


#: 정책 상수. **요구사항이 못박은 값**이라 여기 적는다 — `_XML_FORBIDDEN` 과 같은 지위다.
#: app/ 도 backend-kotlin/ 도 부르지 않는다. 구현에서 읽어 오면 구현이 자기 자신을 채점한다.
MAX_SENTENCE_CHARS = 50
MAX_COMMAS_PER_SENTENCE = 2
#: 반각·전각·모점. 셋 다 한국어 공문서에 실제로 쓰인다.
COMMA_CHARS = ",，、"


def _reported_sentences(call: CheckCall) -> tuple[list[str] | None, list[str]]:
    """산출물이 **스스로 보고한** 문장 목록.

    문장 분리 경계는 휴리스틱이라 요구사항으로 적히지 않는다 — 그래서 판정하지 않고,
    "그 문장들을 받았을 때 규칙을 같게 적용하는가"만 본다. `repair_policy` 가 위반 **건수**를
    산출물에서 받는 것과 같은 이유다. 두 질문을 섞으면 실패했을 때 어느 쪽이 원인인지 모른다.
    """
    sentences = at_path(call.actual, "sentences")
    if not isinstance(sentences, list) or any(not isinstance(s, str) for s in sentences):
        return (None, ["산출물이 `sentences` 문자열 배열을 보고해야 한다 — 규칙 판정의 입력이다"])
    return ([str(s) for s in sentences], [])


#: 문장 종결부호. 어떤 문장 분리 방식이든 **이 문자가 없는 구간은 더 쪼갤 수 없다** —
#: 그 성질이 아래 하한 유도의 근거다.
SENTENCE_TERMINATORS = ".!?\n"


def _indivisible_segments(call: CheckCall) -> tuple[list[str] | None, list[str]]:
    """**fixture 입력**에서 더 쪼갤 수 없는 구간을 뽑는다 (X-4 — 유도의 입력을 독립으로).

    예전에는 산출물이 보고한 `sentences` 를 유도의 입력으로 썼다. 유도 **로직**은 독립이었으나
    **입력**이 자기 보고라, 생산자가 문장을 통째로 버리면(`sentences: []`) 유도값도 비어
    양쪽이 사이좋게 0이 되어 통과했다. 리더 판정이 그 자리를 닫으라고 했다.

    그래서 여기서는 fixture 입력 텍스트를 **비교기가 직접** 종결부호로 가른다. 이 분리는
    구현의 분리와 같지 않아도 된다 — 목적이 "같은 문장을 얻는 것"이 아니라 **"어떤 분리
    방식으로도 상한 아래로 내려갈 수 없는 구간"**을 얻는 것이기 때문이다. 구간 안에 종결부호가
    없으므로 어떤 분리기도 이보다 잘게 쪼갤 수 없고, 따라서 이 구간이 상한을 넘으면 **반드시**
    위반으로 보고돼야 한다. 하한(lower bound)이지 정답이 아니다 — 문장 분리 경계는 여전히
    판정하지 않는다.
    """
    source = at_path(call.payload, "text")
    if not isinstance(source, str):
        return (None, ["fixture 입력에 `text` 문자열이 없다 — 하한 유도의 입력이다"])
    segments: list[str] = []
    current = ""
    for char in source:
        if char in SENTENCE_TERMINATORS:
            segments.append(current)
            current = ""
        else:
            current += char
    segments.append(current)
    return ([piece.strip() for piece in segments if piece.strip()], [])


def _derive_length_floor(call: CheckCall) -> tuple[Any, list[str]]:
    """더 쪼갤 수 없는데 길이 상한을 넘는 구간 — **반드시** 보고돼야 한다."""
    segments, problems = _indivisible_segments(call)
    if problems:
        return (None, problems)
    assert segments is not None
    return ([s for s in segments if len(s) > MAX_SENTENCE_CHARS], [])


def _derive_comma_floor(call: CheckCall) -> tuple[Any, list[str]]:
    """더 쪼갤 수 없는데 쉼표 상한을 넘는 구간 — **반드시** 보고돼야 한다."""
    segments, problems = _indivisible_segments(call)
    if problems:
        return (None, problems)
    assert segments is not None
    return (
        [s for s in segments if sum(s.count(ch) for ch in COMMA_CHARS) > MAX_COMMAS_PER_SENTENCE],
        [],
    )


#: 파일명에서 **반드시 제거돼야 하는** 문자. 요구사항이 지목한 집합이고 구현에서 읽지 않는다.
FILENAME_FORBIDDEN = (
    set('\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\x0c\r"\\/:*?<>|')
    | {chr(code) for code in range(0x0E, 0x20)}
    | {"\x7f"}
)

#: 제목에서 표지를 뽑을 때 보는 앞부분 길이. 정제는 **길이를 줄이기만** 하므로(금지 문자 제거·
#: 공백 접기·앞뒤 깎기) 제목 앞 40자는 줄기 상한 80자 안에 반드시 들어온다. 잘림 때문에
#: 사라질 수 있는 뒷부분은 하한에서 뺀다 — 하한은 **틀림없이 남아야 하는 것**만 요구한다.
TITLE_MARKER_WINDOW = 40
#: 표지 하나의 최대 길이. 앞부분이므로 잘림에 안전하다.
TITLE_MARKER_LENGTH = 8


def _derive_title_markers(call: CheckCall) -> tuple[Any, list[str]]:
    """**fixture 입력 제목**에서 파일명에 남아야 할 표지를 뽑는다 (N-01).

    파일명 축에는 `over` 방향(필수 정보 유지) 차단 단언이 **하나도 없었다.** `present` 니들이
    확장자뿐이라 파일명 21개를 전부 대체 이름으로 바꿔도 성질 불충족 0건이었다 — 사용자가
    올린 제목이 통째로 사라져도 게이트가 초록이다. 실측으로 재현했다.

    `equals_field` 로 정제 **결과**를 못박는 처방은 쓰지 않는다 — 공백 접기·앞뒤 점 깎기 같은
    규칙까지 값으로 고정돼 규칙 개선이 회귀로 잡힌다(§7.1). 대신 X-4에서 쓴 **입력 유도
    하한**을 그대로 적용한다: 제목에서 **어떤 정제 규칙으로도 사라질 수 없는 조각**을 뽑아
    그것이 파일명에 남아 있기만 요구한다.

    "사라질 수 없다"의 근거 둘.
      - 금지 문자·공백·점으로 **가른 뒤**의 조각이라, 어떤 정제도 그 조각 **안**을 건드리지
        않는다(정제 대상이 경계에만 있다).
      - 제목 앞 40자에서만 뽑고 조각도 8자로 자른다. 정제는 길이를 줄이기만 하므로 그 앞부분은
        줄기 상한 80자 안에 반드시 들어온다.

    제목이 전부 금지 문자면 표지가 없다(빈 목록) — 그 자리는 대체 이름 단언이 따로 받는다.
    """
    title = at_path(call.payload, "title")
    if not isinstance(title, str):
        return (None, ["fixture 입력에 `title` 문자열이 없다 — 표지 유도의 입력이다"])
    markers: list[str] = []
    current = ""
    for char in title[:TITLE_MARKER_WINDOW]:
        if char in FILENAME_FORBIDDEN or char.isspace() or char == ".":
            if len(current) >= 2:
                markers.append(current[:TITLE_MARKER_LENGTH])
            current = ""
        else:
            current += char
    if len(current) >= 2:
        markers.append(current[:TITLE_MARKER_LENGTH])
    return (markers, [])


def _derive_length_rule(call: CheckCall) -> tuple[Any, list[str]]:
    """길이 상한을 넘는 문장 전부, 그리고 그것만."""
    sentences, problems = _reported_sentences(call)
    if problems:
        return (None, problems)
    assert sentences is not None
    return ([s for s in sentences if len(s) > MAX_SENTENCE_CHARS], [])


def _derive_comma_rule(call: CheckCall) -> tuple[Any, list[str]]:
    """쉼표 상한을 넘는 문장 전부, 그리고 그것만."""
    sentences, problems = _reported_sentences(call)
    if problems:
        return (None, problems)
    assert sentences is not None
    return (
        [s for s in sentences if sum(s.count(ch) for ch in COMMA_CHARS) > MAX_COMMAS_PER_SENTENCE],
        [],
    )


#: `equals_derived`가 쓸 수 있는 규칙. **요구사항에서 유도**되며 app/ 구현을 부르지 않는다.
DERIVATIONS: dict[str, Callable[[CheckCall], tuple[Any, list[str]]]] = {
    "control_strip": _derive_control_strip,
    "repair_policy": _derive_repair_policy,
    "style_length_rule": _derive_length_rule,
    "style_comma_rule": _derive_comma_rule,
    "export_title_markers": _derive_title_markers,
    "style_length_floor": _derive_length_floor,
    "style_comma_floor": _derive_comma_floor,
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
    "contains_derived": Check(
        check_contains_derived,
        frozenset({"under"}),
        "입력에서 유도한 하한 항목을 전부 담는다 (추가 허용)",
    ),
    "contains_entries": Check(
        check_contains_entries,
        frozenset({"under"}),
        "사전의 표제어와 값이 함께 보존된다 (추가 허용)",
    ),
    "max_length": Check(check_max_length, frozenset({"over"}), "문자열이 길이 상한을 넘지 않는다"),
    "ascii_only": Check(
        check_ascii_only, frozenset({"under"}), "문자열이 US-ASCII 안에 있다 (HTTP 헤더)"
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
    정하는 근거가 될 수 없다 — 파일에 다른 `mode`·`spec_status`를 적어 성질 검사를 피해
    가는 경로를 막는다(파일과 정본이 다르면 정본 대조가 따로 잡는다).
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
    #: 성질을 실행해 판정한 케이스 수.
    judged: int = 0
    #: 실행한 단언 수. 케이스 수만 세면 단언 1개짜리 케이스가 늘어난 것을 못 본다.
    assertions: int = 0
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
    #: 방향 판정이 **보류된** 케이스 (`verdict_pending`). `judged` 와 **따로 센다** —
    #: 구조 불변식은 걸려 있지만 정작 물음이 된 방향은 아무도 단언하지 않았으므로,
    #: 이것을 `성질 판정 N건` 에 섞으면 판정된 적 없는 자리가 판정 수를 부풀린다.
    deferred: list[tuple[str, dict[str, Any], int]] = field(default_factory=list)


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


def verdict_pending_problems(case: dict[str, Any]) -> list[str]:
    """방향 보류 마커가 **읽히는 형태**인지 본다 (R-4).

    옛 `known_gap` 은 자유 문자열이었고 **어느 게이트도 읽지 않았다.** 마커를 붙이는 것이
    곧 "판정을 미뤘다"의 기록이었는데, 미룬 것을 누가 언제까지 닫는지가 어디에도 없어
    미룸이 영구가 됐다. 그래서 네 필드를 필수로 만든다 — 각각 다른 질문에 답한다.

    이 검사가 없으면 마커는 **은폐형 장치**가 된다(면제 조항). 필드를 강제하면 같은 마커가
    **탐지형**이 된다 — 리포트에 소유자와 기한이 찍히고, 그 줄이 비면 게이트가 막는다.
    """
    case_id = case.get("id")
    if "known_gap" in case:
        return [
            f"- `{case_id}` **옛 `known_gap` 마커** — 자유 문자열이라 어느 게이트도 읽지 "
            "않았다(R-4). `verdict_pending` 객체로 옮기고 "
            f"{'·'.join(f'`{name}`' for name in VERDICT_PENDING_FIELDS)} 를 채운다"
        ]
    marker = case.get("verdict_pending")
    if marker is None:
        return []
    if not isinstance(marker, dict):
        return [
            f"- `{case_id}` **`verdict_pending` 이 객체가 아니다** — 누가 언제까지 닫는지를 "
            "담을 수 없는 형태다. 문자열 하나로는 미룸이 영구가 되는 것을 막지 못한다"
        ]
    missing = [
        name
        for name in VERDICT_PENDING_FIELDS
        if not isinstance(marker.get(name), str) or not marker[name].strip()
    ]
    if missing:
        return [
            f"- `{case_id}` **`verdict_pending` 필수 항목 누락**: "
            f"{', '.join(f'`{name}`' for name in missing)} — 보류를 선언하려면 "
            "무엇이 열려 있고(`reason`) 누가 닫으며(`owner`) 언제까지인지(`deadline`) "
            "누가 회부했는지(`referred_by`)를 함께 적어야 한다. 셋 중 하나라도 비면 "
            "그것은 보류가 아니라 방치다"
        ]
    return []


def floor_scope(fixture_root: Path, pairs: list[Pair]) -> dict[str, set[str]]:
    """하한이 대조할 **fixture 실물** — 비교 범위가 아니라 디스크에 있는 전부다 (X-2).

    예전에는 `pairs`(= `--only-domain` 으로 좁혀진 비교 대상)를 그대로 썼다. 그래서 부분
    게이트에서 **선언 151건 중 139건만 도달**했다 — 선언하지 않은 도메인(`export` 12건)의
    케이스는 지워도 아무 데서도 걸리지 않았다. 하한은 "무엇을 비교했는가"가 아니라
    "무엇이 남아 있는가"의 검사이므로, 비교 범위와 **다른 축**으로 읽어야 한다.

    `pending` 도메인도 여기 들어온다. 생산자가 없어 값 비교는 못 해도 **케이스가 사라지는
    것**은 지금 막을 수 있고, 그것이 X-2 가 지적한 자리다.
    """
    if fixture_root.is_dir():
        present: dict[str, set[str]] = {}
        for path in sorted(fixture_root.rglob("*.json")):
            try:
                fixture = load(path)
            except SystemExit:
                continue
            domain = domain_of(path, fixture)
            ids = {
                str(case.get("id")) for case in fixture.get("cases", []) if isinstance(case, dict)
            }
            present.setdefault(domain, set()).update(ids)
        return present
    # 단일 파일 지정은 그 파일이 곧 범위 선언이다.
    return {pair.domain: set(pair.case_ids) for pair in pairs}


def full_gate_floor_problems(selected: list[str], scoped: bool) -> list[str]:
    """전체 게이트 상태를 **하한**으로 본다 (게이트 12 #3 — 차단②).

    `selected` 는 실행이 명시적으로 선언한 판정 범위(`--only-domain`)다. CI 는 Kotlin 이
    구현했다고 선언한 도메인을 그대로 넘기므로, 이 목록이 곧 "지금 무엇을 검증하는가"다.

    두 방향을 본다.
      - **도달했는데 고정하지 않았다** → 막는다. 선언이 정본 전부를 덮은 실행인데 표시 파일이
        없으면, 다음에 builder 가 하나 늘어날 때 조용히 부분 게이트로 내려간다.
      - **고정했는데 내려갔다** → 막는다. 정본이 늘었든 선언이 줄었든 결과는 같다 — 전체
        게이트였던 것이 부분 게이트가 되고 종료 코드 3 사면이 되살아난다.

    `--only` 로 케이스를 골라 돌린 실행과 `--only-domain` 없이 돌린 실행에서는 보지 않는다.
    앞은 범위 선언이 아니고, 뒤는 이미 정의상 전체 게이트다.
    """
    if scoped or not selected:
        return []
    scope = set(selected)
    canonical = set(BUILDERS)
    marked = FULL_GATE_PATH.exists()
    if scope >= canonical and not marked:
        return [
            f"- **전체 게이트에 도달했다 — 하한을 고정하라**: 선언 {len(scope)}개가 정본 "
            f"{len(canonical)}개를 전부 덮었다\n"
            f"  - `{FULL_GATE_PATH}` 를 만들어 이 상태를 하한으로 고정한다. 없으면 다음에 "
            "생성기에 builder 가 하나 늘어나는 순간 **조용히 부분 게이트로 내려가** 종료 "
            "코드 3 사면이 되살아난다 — 8/8 은 강제된 성질이 아니라 오늘 참인 상태일 뿐이다\n"
            "  - 그 파일을 만든 뒤에는 범위를 줄이는 편집이 여기서 막힌다"
        ]
    if marked and not scope >= canonical:
        missing = sorted(canonical - scope)
        return [
            f"- **전체 게이트에서 내려왔다**: {', '.join(missing)} 이(가) 판정 범위 밖이다\n"
            f"  - `{FULL_GATE_PATH.name}` 이 이 저장소가 전체 게이트에 **도달한 적 있음**을 "
            "기록하고 있다. 정본이 늘었거나 선언이 줄었고, 어느 쪽이든 결과는 같다 — "
            "부분 게이트가 되어 종료 코드 3 이 통과로 읽힌다\n"
            "  - 새 도메인을 더했다면 **같은 커밋에서 선언한다**. 선언할 수 없는 상태라면 "
            f"(구현 전) `{FULL_GATE_PATH.name}` 을 지우고 PR 에 근거를 적는다 — 그 diff 가 "
            "'전체 게이트를 내렸다'는 신호다"
        ]
    return []


def case_floor_problems(
    pairs: list[Pair], scoped: bool, fixture_root: Path | None = None
) -> list[str]:
    """케이스 **정체성** 하한 — 삭제·개명을 막는다 (J-1+J-3).

    개수가 아니라 id 로 본다. 총개수 하한은 순소실만 막아서, 케이스 하나를 지우고 아무거나
    하나 더하면 통과한다 — 지워진 것이 하필 유일한 과잉 가드일 수 있다. 이 저장소는 같은
    판정을 이미 한 번 내렸다(`86c6a99`).

    비대칭이다. **추가는 자유**(검증이 늘어난 것이라 무해하고, 하한에 없으면 이름만 찍는다),
    **삭제·개명은 차단**(그 케이스가 지키던 성질이 조용히 사라진다). 개명이 삭제로 잡히는
    것은 의도다 — id 는 리포트·원장·명세 문서가 함께 쓰는 키라 바꾸면 그것들이 전부 어긋난다.

    대조 대상은 **fixture 실물 전체**다(`floor_scope`). 비교 범위로 좁히면 선언하지 않은
    도메인의 케이스가 하한 밖으로 빠진다 — X-2 가 그 상태였다.

    `scoped` 는 `--only` 로 케이스를 골라 돌린 실행이다. 그때는 관측 범위가 fixture 전체가
    아니므로 이 검사를 건너뛴다 — 켜 두면 정상적인 단일 케이스 재현이 매번 빨개진다.
    """
    if scoped or not pairs:
        return []
    if not CASE_FLOOR_PATH.exists():
        return [
            f"- **케이스 하한 파일 없음** — `{CASE_FLOOR_PATH}` 가 없다. 케이스가 사라지는 "
            "것을 감지할 기준점이 사라졌으므로 통과시키지 않는다"
        ]
    floor: dict[str, set[str]] = {}
    deferred_floor: dict[str, set[str]] = {}
    for raw in CASE_FLOOR_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        # `도메인/케이스id` 뒤에 `!deferred` 를 붙이면 "이 케이스는 방향이 보류된 상태"까지
        # 하한이 기억한다. 마커만 떼면 보류 케이스가 조용히 `성질 판정` 수로 넘어가는데,
        # 그것은 **판정된 적 없는 자리가 판정 수를 채우는 것**이라 id 만으로는 잡히지 않는다.
        marked = line.endswith(DEFERRED_FLOOR_SUFFIX)
        if marked:
            line = line[: -len(DEFERRED_FLOOR_SUFFIX)].strip()
        domain, _, case_id = line.partition("/")
        if not case_id:
            return [
                f"- **케이스 하한 형식 오류** — `{raw.strip()}` 은 `도메인/케이스id` 가 "
                "아니다. 형식이 깨지면 무엇을 요구하는지 알 수 없으므로 막는다"
            ]
        floor.setdefault(domain, set()).add(case_id)
        if marked:
            deferred_floor.setdefault(domain, set()).add(case_id)
    if not floor:
        return [
            f"- **케이스 하한이 비었다** — `{CASE_FLOOR_PATH}` 에 항목이 한 줄도 없다. "
            "하한이 비면 무엇을 지우든 통과한다. 파일을 지우는 것과 같은 취급이다"
        ]
    problems: list[str] = []
    scope = (
        floor_scope(fixture_root, pairs)
        if fixture_root is not None
        else {pair.domain: set(pair.case_ids) for pair in pairs}
    )
    # 하한이 **실물에 없는 도메인**을 선언하고 있으면 그 줄은 아무것도 지키지 않는다.
    # codex 가 연 사각이 정확히 이것이다 — `bogus/placeholder` 한 줄을 넣어도 지적 0건이라
    # "하한이 비어 있지 않다"가 "하한이 무언가를 지킨다"를 뜻하지 않았다. 빈 선언을 막는
    # 검사는 있는데(`floor_count == 0`) **무의미한 선언**을 막는 검사가 없었다.
    # **범위가 좁혀진 실행에서는 이 검사를 하지 않는다.** `--fixture parity/fixtures/export`
    # 처럼 도메인 디렉터리를 지목하면 `scope` 에 그 도메인만 들어와, 나머지 하한 줄이 전부
    # "실물 없음"으로 보인다. 실제로 그 오탐이 났다(도메인 7개 오지목). 정본 전체가 보이는
    # 실행에서만 대조한다 — 도메인이 통째로 사라진 경우는 전체 게이트의 `missing` 이 받는다.
    narrowed = set(scope) != set(BUILDERS)
    unknown_domains = [] if narrowed else sorted(set(floor) - set(scope))
    if unknown_domains:
        problems.append(
            f"- **하한이 없는 도메인을 선언한다**: {', '.join(unknown_domains)}\n"
            f"  - `{CASE_FLOOR_PATH.name}` 의 그 줄들은 fixture 실물에 대응이 없어 **아무것도 "
            "지키지 않는다**. 하한이 비어 있지 않다는 것이 무언가를 지킨다는 뜻이 되려면 "
            "선언이 실물과 대응해야 한다\n"
            "  - 도메인을 지웠다면 그 줄도 지우고, 오타라면 고친다"
        )
    for domain in sorted(scope):
        required = floor.get(domain)
        if not required:
            continue
        present = scope[domain]
        deferred_required = deferred_floor.get(domain, set())
        now_deferred = {
            str(case.get("id"))
            for pair in pairs
            if pair.domain == domain
            for case in pair.fixture.get("cases", [])
            if isinstance(case, dict) and isinstance(case.get("verdict_pending"), dict)
        }
        if not any(pair.domain == domain for pair in pairs):
            # 비교 범위 밖 도메인은 케이스 **존재**만 본다. 보류 마커 대조는 fixture 를
            # 이미 읽은 도메인에서만 하고, 여기서는 마커 상태를 판단할 근거가 없다.
            now_deferred = deferred_required & present
        closed = sorted((deferred_required & present) - now_deferred)
        if closed:
            problems.append(
                f"- `{domain}` **보류 마커가 사라졌다**: {', '.join(closed)}\n"
                f"  - 하한이 이 케이스를 `{DEFERRED_FLOOR_SUFFIX.strip()}` 로 기억하고 있는데 "
                "fixture 에 `verdict_pending` 이 없다. 마커만 떼면 그 케이스는 조용히 "
                "`성질 판정` 수로 넘어간다 — **판정된 적 없는 자리가 판정 수를 채운다**\n"
                "  - 보류가 실제로 닫혔다면(판정을 받았다면) 방향을 단언으로 걸고 하한에서 "
                f"`{DEFERRED_FLOOR_SUFFIX.strip()}` 를 지운다. 어느 판정이 닫았는지 PR 에 적는다"
            )
        vanished = sorted(required - present)
        if vanished:
            problems.append(
                f"- `{domain}` **케이스가 사라졌다**: {', '.join(vanished)}\n"
                f"  - 하한 `{CASE_FLOOR_PATH.name}` 은 이 도메인에서 {len(required)}건을 "
                f"요구하는데 fixture 에는 {len(present)}건이 있다. 개명도 여기서 삭제로 "
                "잡힌다 — id 는 리포트·원장·명세가 함께 쓰는 키다\n"
                "  - 되돌리거나, 삭제·개명이 의도라면 하한 파일을 함께 고치고 PR 에 근거를 "
                "적는다(그 케이스가 지키던 성질을 지금 무엇이 지키는가)"
            )
    return problems


def case_floor_additions(pairs: list[Pair], scoped: bool) -> list[str]:
    """하한에 아직 없는 케이스 id. **막지 않고 이름만 남긴다**(비대칭의 추가 쪽)."""
    if scoped or not pairs or not CASE_FLOOR_PATH.exists():
        return []
    known: set[str] = set()
    for raw in CASE_FLOOR_PATH.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            known.add(line)
    notes: list[str] = []
    for pair in pairs:
        fresh = sorted(cid for cid in pair.case_ids if f"{pair.domain}/{cid}" not in known)
        if fresh:
            notes.append(
                f"  {pair.domain}: {len(fresh)}건 — {', '.join(fresh[:6])}"
                + (" …" if len(fresh) > 6 else "")
            )
    return notes


def spec_shape_problems(pair: Pair) -> list[str]:
    """spec 도메인의 **단언 구조**를 본다 — 성질 검증이 형식만 남는 것을 막는다.

    세 가지를 막는다.
    1. 단언 없는 케이스. 값 비교를 뺐는데 단언도 없으면 그 케이스는 아무것도 판정하지 않는다.
    2. 한 방향뿐인 도메인. "가려졌는가"만 재면 전문을 통째로 가린 구현이 만점을 받고,
       "남았는가"만 재면 아무것도 안 하는 구현이 만점을 받는다. 성질 검증에서 가장 흔한
       실패라 도메인마다 양방향을 강제한다.
    3. **입력이 같은 케이스 둘**(M-08). 같은 입력은 같은 산출물을 내므로 두 케이스가 같은
       것을 두 번 잰다. 성질이 늘지 않는데 케이스 수는 늘고, 그 수가 리포트에서 커버리지의
       대리 지표로 쓰인다("성질 판정 83건"). 즉 **커버리지가 늘어난 것처럼 보이게 만드는
       가장 값싼 방법**이 중복 붙여넣기이고, 그것을 아무도 잡지 않았다.

       손편집으로 들어오는 경로는 정본 대조가 이미 막는다. 이 검사가 받는 것은 **생성기에
       중복이 들어오는 경로**다 — 그때 fixture는 자기 정본과 일치하므로 정본 대조는 조용하다.
    """
    if pair.mode != MODE_SPEC:
        return []
    cases = pair.fixture.get("cases")
    if not isinstance(cases, list) or not cases:
        return []
    # `pending` 도메인도 **형태 검증은 받는다**(X-3). 예전에는 여기서 통째로 빠져나가
    # `export` 12케이스가 하한·형태·값 어느 것도 안 받는 상태였다. 성질이 아직 안 적힌 것과
    # 케이스가 **깨진 것**은 다른 문제다 — 앞은 미검증으로 세면 되고 뒤는 지금 막을 수 있다.
    #
    # 다만 단언을 **전제로 하는** 두 검사는 빼야 한다. `pending` 은 정의상 단언이 없으므로
    # "단언 없는 케이스"와 "방향 가드 결손"을 걸면 pending 이라는 상태 자체가 결함이 된다.
    pending = pair.pending_spec
    problems: list[str] = []
    directions: set[str] = set()
    seen_inputs: dict[str, str] = {}
    for case in cases:
        if not isinstance(case, dict) or "input" not in case:
            continue
        key = json.dumps(case["input"], ensure_ascii=False, sort_keys=True)
        first = seen_inputs.get(key)
        if first is None:
            seen_inputs[key] = str(case.get("id"))
            continue
        problems.append(
            f"- `{case.get('id')}` **입력이 `{first}` 와 같다** — 같은 입력은 같은 산출물을 "
            "내므로 두 케이스가 같은 것을 두 번 잰다. 성질은 늘지 않는데 케이스 수만 늘어 "
            "커버리지를 실제보다 크게 보이게 한다. 단언을 나눌 의도였다면 한 케이스로 "
            "합치고, 다른 성질을 재려던 것이라면 입력을 다르게 한다"
        )
    for case in cases:
        if not isinstance(case, dict):
            continue
        problems += verdict_pending_problems(case)
        entries = case.get("assert")
        if not isinstance(entries, list) or not entries:
            if not pending:
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
        if pending:
            break
        if missing not in directions:
            problems.append(
                f"- **방향 가드 결손** — 이 도메인에 `{missing}` 방향 검사가 하나도 없다"
                f"({meaning}을 잡을 수단이 없다). 한 방향만 재는 성질 검증은 "
                "반대 방향으로 망가진 구현을 통과시킨다"
            )
    return problems


# ------------------------------------------------------------------ fixture 정본 대조

#: 도메인별 정본 생성 결과 캐시. 한 실행에서 같은 도메인을 두 번 만들지 않는다
#: (빌더가 Python 구현을 실제로 돌리므로 반복 생성은 그만큼 느리다).
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
    언제나 대조 가능하다. 난수 입력이 있는 도메인만 `VOLATILE_INPUT_FIELDS`로 빼 두는데
    **지금은 그런 도메인이 없어 목록이 비어 있다** — 빼는 자리를 늘리는 것이 곧 구멍이다.
    """
    canonical, failure = canonical_fixture(pair.domain)
    if canonical is None:
        return [
            f"- **정본 대조 불가** — {failure}. fixture가 생성기 산출물인지 확인할 수 없으므로 "
            "통과로 보고하지 않는다"
        ]
    problems: list[str] = []
    # 헤더 전량을 대조한다. `spec_status`가 특히 중요하다 — 파일에서 `pending`을 `ready`로
    # 바꾸면 단언 없는 도메인이 통과로 집계된다. `mode`도 같은 이유로 대조 범위에 둔다.
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


# ----------------------------------------------------------- 결과 파일의 런타임 선언
#
# 이 자리에는 역방향(external) 검증 — Kotlin이 만든 Fernet 토큰·JWT를 Python 검증기가
# 그 자리에서 읽어 판정하는 장치 — 도 함께 있었다. 2026-08-12에 지웠다(모듈 docstring
# "없어진 것" 참고). 역방향은 "롤백 창에서 Python이 Kotlin 산출물을 읽는다"를 증명하는
# 장치였고, 롤백을 포기하면서 읽을 쪽이 사라졌다.


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


LEDGER_NOTE = (
    "Kotlin 산출물과 Python 참고값이 갈린 자리의 기록이다. 판정 근거가 아니라 "
    "리뷰에 올리기 위한 원장이며, 본문 없이 경로와 SHA-256만 남긴다. "
    "갱신: compare_parity.py --record-reference"
)

#: 원장 파일에서 **내용이 아닌** 필드. 매 실행 값이 달라지므로 변경 판정에서 뺀다.
#: 이 목록에 무엇을 넣는지가 기록 실행의 종료 코드를 정한다 — 실제 내용을 여기 넣으면
#: 그 변경이 조용해지고, 시각을 여기서 빼면 기록 실행이 **항상** 4가 되어 아무것도 닫지 못한다.
LEDGER_VOLATILE_FIELDS = frozenset({"recorded_at"})


def ledger_body(domain: str, entries: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """원장 파일의 **내용**. 기록 시각은 여기 없다.

    이 함수가 따로 있는 이유는 "원장이 바뀌었는가"를 **쓸 내용과 이전 내용의 차이**로
    재기 위해서다. 예전 기준은 `reference_problems()` 의 지적 건수였는데, 그것은
    "원장이 **낡았다**"의 척도이지 "원장이 **바뀐다**"의 척도가 아니었다(X-12).
    """
    return {"domain": domain, "note": LEDGER_NOTE, "cases": dict(sorted(entries.items()))}


def stored_ledger_body(root: Path, domain: str) -> dict[str, Any] | None:
    """디스크에 있는 원장의 내용. 없거나 읽을 수 없으면 `None`.

    `None` 은 "변경 없음"이 아니라 **비교할 이전 내용이 없다**는 뜻이다. 없음 → 있음도
    변경이고, 그것이 X-12에서 새 파일 31건을 성공 코드로 통과시킨 자리다.
    """
    path = ledger_file(root, domain)
    if not path.exists():
        return None
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(loaded, dict):
        return None
    return {key: value for key, value in loaded.items() if key not in LEDGER_VOLATILE_FIELDS}


def ledger_write_problems(domain: str, root: Path, entries: dict[str, dict[str, Any]]) -> list[str]:
    """이번 기록이 원장을 **실제로** 바꾸는가. 바꾸지 않으면 빈 목록.

    반드시 `write_ledger()` **앞에서** 부른다 — 쓰고 나서 비교하면 항상 같다.
    """
    path = ledger_file(root, domain)
    before = stored_ledger_body(root, domain)
    if before is None:
        diverged = sum(1 for entry in entries.values() if entry.get("status") == "diverge")
        what = "덮어쓴다(이전 파일을 읽을 수 없다)" if path.exists() else "새로 만든다"
        return [
            f"- **원장을 {what}** — `{path}` (케이스 {len(entries)}건, 갈림 {diverged}건)\n"
            "  - 없음 → 있음도 변경이다. 지적이 0건이어도 이 실행은 게이트를 닫지 않는다"
        ]
    after = ledger_body(domain, entries)
    if before == after:
        return []
    old_cases = before.get("cases")
    old_cases = old_cases if isinstance(old_cases, dict) else {}
    new_cases = after["cases"]
    found: list[str] = []
    groups = (
        ("추가", sorted(set(new_cases) - set(old_cases))),
        ("삭제", sorted(set(old_cases) - set(new_cases))),
        (
            "내용 변경",
            sorted(
                key for key in set(new_cases) & set(old_cases) if new_cases[key] != old_cases[key]
            ),
        ),
    )
    for label, ids in groups:
        if not ids:
            continue
        shown = ", ".join(ids[:MAX_REPORTED_CASE_DIFFS])
        found.append(
            f"- **원장 항목 {label} {len(ids)}건** — `{path}`: {shown}"
            f"{' 외' if len(ids) > MAX_REPORTED_CASE_DIFFS else ''}"
        )
    if not found:
        # 케이스 목록은 같은데 파일이 달라졌다 — 머리말(`domain`·`note`)이 바뀐 경우다.
        found.append(f"- **원장 머리말이 바뀐다** — `{path}` (케이스 목록은 그대로)")
    return found


def write_ledger(root: Path, domain: str, entries: dict[str, dict[str, Any]]) -> Path:
    path = ledger_file(root, domain)
    path.parent.mkdir(parents=True, exist_ok=True)
    body = ledger_body(domain, entries)
    payload = {
        "domain": body["domain"],
        "note": body["note"],
        "recorded_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "cases": body["cases"],
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
        # ── 요구 성질을 실행해 판정한다 ──────────────────────────────────────
        entries = case.get("assert")
        entry_count = len(entries) if isinstance(entries, list) else 0
        marker = case.get("verdict_pending")
        if isinstance(marker, dict):
            # 방향이 보류된 케이스. 구조 불변식은 그대로 실행하되(아래 run_assertions)
            # **판정 수에는 넣지 않는다.** 넣으면 "아무도 방향을 정하지 않은 자리"가
            # `성질 판정 N건` 을 부풀려, 커버리지 지표가 판정되지 않은 것을 세게 된다.
            result.deferred.append((case_id, marker, entry_count))
        else:
            result.judged += 1
            result.assertions += entry_count
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

    파일 쪽 선언을 믿으면 `mode`를 바꿔 적는 것만으로 성질 검사를 건너뛸 수 있다. 정본을
    만들 수 없을 때만 파일 선언으로 물러서되, 그 상태는 정본 대조가 이미 결함으로
    보고한다(통과로 집계되지 않는다).

    **알 수 없는 mode는 `spec`으로 떨어진다.** 값 동일성으로 판정하던 `compat` 모드가
    2026-08-12에 사라지면서 fallback도 뒤집혔다 — 예전에는 모르는 값이 `compat`이 되어
    `assert` 의무와 방향 가드를 통째로 건너뛰었다. 지금은 가장 엄격한 쪽으로 떨어진다.
    """
    canonical, _ = canonical_fixture(domain) if domain in BUILDERS else (None, None)
    source = canonical if canonical is not None else fixture
    mode = source.get("mode")
    status = source.get("spec_status", "ready")
    return (
        mode if isinstance(mode, str) and mode == MODE_SPEC else MODE_SPEC,
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
    total_judged = 0
    total_assertions = 0
    total_considered = 0
    total_diverged = 0
    deferred_all: list[tuple[str, str, dict[str, Any], int]] = []
    recorded: dict[str, dict[str, dict[str, Any]]] = {}
    referenced: dict[str, set[str]] = {}
    #: 원장 대조 결과. 평소에는 불충족으로 합류하고, `--record-reference` 에서는 "원장이
    #: 이렇게 바뀐다"의 사유가 된다. **두 모드가 같은 계산을 쓴다** — 기록 모드에서만
    #: 조용해지는 자리를 만들지 않기 위해서다.
    ledger_findings: dict[str, list[str]] = {}
    #: 도메인 전체를 판정했는가(결과 파일이 다 있었는가). 낡은 원장 항목 판정의 전제다.
    fully_compared: dict[str, bool] = {}
    floor_problems = case_floor_problems(
        pairs, scoped=args.only is not None, fixture_root=args.fixture
    )
    floor_problems += full_gate_floor_problems(selected, scoped=args.only is not None)
    if floor_problems:
        total_problems += len(floor_problems)
        sections.append("## 케이스 정체성 하한\n\n" + "\n".join(floor_problems))
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
            total_judged += result.judged
            total_assertions += result.assertions
            total_considered += result.considered
            total_diverged += result.diverged
            total_pending += len(result.pendings)
            deferred_all += [
                (pair.domain, case_id, marker, count) for case_id, marker, count in result.deferred
            ]
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
                shown = f"성질 {result.judged}건/단언 {result.assertions}개"
                if result.deferred:
                    shown += f" · 판정 보류 {len(result.deferred)}건"
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

    # 두 척도를 구분한다. `ledger_stale` 은 "원장이 **낡았다**"(관측이 기록과 다르다)이고,
    # `ledger_writes` 는 "원장이 **바뀐다**"(쓸 내용이 디스크의 내용과 다르다)이다. 예전에는
    # 앞의 것 하나로 기록 실행을 판정해서, 원장이 **없는** 상태에서 전 케이스가 `agree` 면
    # 지적 0건 → 성공 코드로 끝나면서 파일은 새로 생겼다(X-12).
    ledger_stale = sum(len(found) for found in ledger_findings.values())
    ledger_writes: dict[str, list[str]] = {}
    if args.record_reference:
        for domain, entries in sorted(recorded.items()):
            # 쓰기 **전에** 비교한다. 쓰고 나서 비교하면 언제나 같다.
            changes = ledger_write_problems(domain, args.ledger, entries)
            diverged = sum(1 for entry in entries.values() if entry["status"] == "diverge")
            if not changes:
                # 내용이 같으면 쓰지 않는다. 다시 쓰면 `recorded_at` 만 흔들려 리뷰에
                # 내용 없는 diff가 올라가고, "바뀐 것이 없다"를 파일로도 말할 수 없게 된다.
                print(
                    f"[원장 유지] {ledger_file(args.ledger, domain)} — {len(entries)}건 중 "
                    f"갈림 {diverged}건, 내용 변경 없음"
                )
                continue
            ledger_writes[domain] = changes
            target = write_ledger(args.ledger, domain, entries)
            print(f"[원장 기록] {target} — {len(entries)}건 중 갈림 {diverged}건")
            for line in changes:
                print(f"  {line.splitlines()[0]}")
        for domain, found in sorted(ledger_findings.items()):
            print(f"[원장 지적] {domain} — {len(found)}건")
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
                "\n\n> 미검증은 통과가 아니다. 요구 성질이 적히지 않아"
                "(spec_status=pending) **판정할 근거가 없는** 상태이므로 "
                "게이트를 닫지 않는다.\n"
            )
        )
    if deferred_all:
        rows = "\n".join(
            f"| `{domain}` | `{case_id}` | {marker['owner']} | {marker['deadline']} | "
            f"{marker['referred_by']} | {count} | {marker['reason']} |"
            for domain, case_id, marker, count in deferred_all
        )
        report += (
            ("\n" if report else "")
            + f"# 판정 보류 ({len(deferred_all)}건) — 성질 판정 수에 **넣지 않았다**\n\n"
            + "방향이 아직 정해지지 않은 자리다. 구조 불변식은 그대로 걸려 있고 실행됐으나, "
            + "정작 물음이 된 방향은 아무도 단언하지 않았다. 이 표가 비어 보이면 "
            + "마커가 지워진 것이지 보류가 닫힌 것이 아니다.\n\n"
            + "| 도메인 | 케이스 | 소유자 | 기한 | 회부 | 실행 단언 | 무엇이 열려 있나 |\n"
            + "|---|---|---|---|---|---|---|\n"
            + rows
            + "\n"
        )
    additions = case_floor_additions(pairs, scoped=args.only is not None)
    if additions:
        report += (
            ("\n" if report else "")
            + "# 케이스 하한 미등재 (막지 않는다)\n\n"
            + "\n".join(additions)
            + f"\n\n> `{CASE_FLOOR_PATH.name}` 에 적어야 나중에 이 케이스가 지워질 때 걸린다. "
            + "추가는 게이트를 막지 않으므로 이름만 남긴다.\n"
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
        f"(단언 {total_assertions}개) / 판정 보류 {len(deferred_all)}건 / "
        f"참고 갈림 {total_diverged}건 / "
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
    written = sum(len(found) for found in ledger_writes.values())
    if args.record_reference and (written or ledger_stale):
        # 방금 원장을 바꾼 실행은 **판정이 아니다.** 예전에는 갈림 23건을 침묵시킨 실행과
        # 애초에 0건이던 실행이 둘 다 종료 코드 3을 냈다 — 이 파일이 스스로 세운 원칙
        # ("종료 코드는 자동화가 읽는 유일한 계약")을 자기가 어긴 자리였다(X-09).
        #
        # 그 뒤로도 한 자리가 남아 있었다(X-12): 판정 기준이 **지적 건수**였기 때문에,
        # 원장이 아직 **없고** 관측이 전부 `agree` 인 실행은 지적 0건이라 이 분기를 타지
        # 않았다 — 파일을 새로 만들고서 0/3(성공)으로 끝났고 CI는 3을 통과로 읽는다.
        # 지금 기준은 **쓴 내용과 이전 내용의 차이**(`ledger_writes`)이고, 없음 → 있음도
        # 변경이다. 지적 건수(`ledger_stale`)는 그 위에 얹은 안전망으로만 남는다.
        #
        # 바뀐 것이 없으면 아래 정상 경로로 떨어진다. 그때는 관측이 커밋된 원장과 정확히
        # 같다는 뜻이고, 플래그 없이 돌린 실행과 판정이 **정의상 동일**하다 — 파일도
        # 건드리지 않는다(`recorded_at` 을 변경 판정에서 뺀 이유이자, 뺐기 때문에 가능한 일).
        stale_note = f" / 원장 지적 {ledger_stale}건" if ledger_stale else ""
        detail = f"원장 변경 {written}건{stale_note}"
        print(
            f"원장 기록으로 종료(판정 아님): {summary} — {detail}. "
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
