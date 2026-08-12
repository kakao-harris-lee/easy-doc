#!/usr/bin/env python3
"""parity fixture 생성기 — 요구 성질(spec)과 호환 기대값(compat)을 함께 굳힌다.

**기준은 Python 출력이 아니다.** Python 구현은 회귀가 잦아 Kotlin으로 옮기는 중이며,
사용자 결정(2026-08-12)은 "출력 결과를 Python과 동일하게 맞출 필요는 없다. 요구사항을
구현하고 이후에 개선한다"이다. 그래서 이 생성기가 만드는 fixture는 두 종류다.

    mode="spec"   판정 근거가 **요구사항이 요구하는 성질**이다. 케이스마다 `assert`
                  목록이 들어가고, 비교기가 Kotlin 산출물에 그 성질을 실행해 판정한다.
                  Python 실행 결과는 `reference`(참고값)로 함께 담기지만 **판정에 쓰지
                  않는다** — 다른 자리는 `참고 갈림 원장`에 기록될 뿐이다.
    mode="compat" 판정 근거가 **값 동일성**이다. 롤백 창에서 Python이 Kotlin 산출물을
                  읽어야 하는 도메인(crypto·jwt·argon2)뿐이다. 여기서는 값이 같은 것이
                  곧 요구사항이므로 `expected`를 그대로 쓴다.

spec 도메인이라도 성질을 아직 적지 못했으면 `spec_status="pending"`으로 선언한다.
그 도메인은 "통과"가 아니라 **미검증**으로 집계된다(비교기 종료 코드 2). 성질을 적지
않은 채 값 비교로 때우면 폐기된 전제로 되돌아가는 것이므로 그 경로를 두지 않는다.

실행:
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
        --domain masking --domain style
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --list
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
        verify-crypto --actual parity/actual/crypto/kotlin-encrypt.json \
        --fixture parity/fixtures/crypto/crypto.json
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
        verify-jwt --actual parity/actual/jwt/kotlin-issue.json \
        --fixture parity/fixtures/jwt/jwt.json

`verify-*` 서브커맨드는 **역방향**(Kotlin 산출물을 Python이 읽는 방향) 전용이며 사람이
직접 돌려 보는 용도다. **게이트 판정은 이 명령의 산출물로 하지 않는다** — `compare_parity.py`가
같은 검증기를 자기가 다시 돌려 판정한다. 이 명령이 남기는 `*.verified.json`은 판정의
입력이 아니라 실행 **기록**이다(예전에는 입력이었고, 그래서 손으로 적으면 통과했다).

입력 문자열은 전부 합성(synthetic)이다. 실제 사용자 문서·실제 개인정보를 fixture에
넣지 않는다 — fixture는 저장소에 커밋되어 영구히 남는다.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import re
import sys
import unicodedata
import uuid
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, tzinfo
from pathlib import Path
from types import ModuleType
from typing import Any, cast
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parents[4]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

DEFAULT_OUT = REPO_ROOT / "parity" / "fixtures"

#: 외부 HTTP 계약. **마스킹 범주 문자열의 정본은 이 파일 하나다.**
#: 이 하네스가 소유하지 않는 파일이며 읽기만 한다(소유자는 `contract-keeper`).
CONTRACT_PATH = REPO_ROOT / "contracts" / "easy-doc-v1.yaml"

#: 기본 정규화. 두 런타임이 "같다"고 볼 표기 차이만 담는다.
#: 이 목록을 늘릴 때는 반드시 왜 그 차이가 무해한지 근거를 함께 남긴다.
BASE_NORMALIZATION = ["nfc", "lf"]

#: 자격증명 도메인(jwt·argon2)은 정규화를 쓰지 않는다. 기대값이 전부 ASCII(불리언·UUID·
#: 16진 해시·PHC 문자열)라 접을 표기 차이가 없고, 무엇보다 **비밀번호 바이트를 NFC로 접으면
#: argon2 해시가 달라진다**. 정규화는 줄이는 방향으로 관리한다(스킬 핵심 원칙 4).
NO_NORMALIZATION: list[str] = []

#: 역방향 검증기(`verify-*`)와 그 실행 기록 파일 이름. 기록 파일은 Kotlin 산출물(`--actual`)과
#: **같은 디렉터리**에 놓인다. 이 파일은 판정의 **입력이 아니라 출력**이다 — 비교기는 이것을
#: 읽어서 판정하지 않고, 검증기를 직접 돌린 뒤 결과를 여기에 덮어쓴다.
PROOF_FILE_NAMES: dict[str, str] = {
    "verify-crypto": "verify-crypto.verified.json",
    "verify-jwt": "verify-jwt.verified.json",
}

#: 각 검증기가 닫는 fixture 요청 케이스 id. 검증기는 이 케이스의 `input`을 요구 사항으로 읽는다.
PROOF_FIXTURE_CASES: dict[str, str] = {
    "verify-crypto": "crypto-roundtrip-request",
    "verify-jwt": "jwt-roundtrip-request",
}

Case = dict[str, Any]


# ------------------------------------------------------- 계약(단일 출처) 읽기
#
# 왜 계약을 읽는가: 예전에는 마스킹 범주 집합을 이 파일이 리터럴로 들고 있었고, 비교기는
# **fixture가 스스로 넘긴 `categories` 인자**와 자리표시자를 대조했다. 생성기가 선언한 값을
# 생성기가 만든 fixture로 검사하는 구조라, 생성기가 `["RRN","CARD"]`로 흘러가면 게이트는
# 통과하고 API는 계약을 위반한다(교차 종합 X-12 / stop-gate S-1, 재현: 종료 코드 3 = 통과).
#
# 이제 생성기도 비교기도 이 함수를 통해 계약 파일에서 값을 읽는다. 저장소 안의 파일이 저장소
# 자신에 대한 기준이 되는 자리를 하나 줄인 것이다 — 계약은 이 하네스가 소유하지 않는다.
#
# **읽지 못하면 닫는다(fail closed).** 계약을 못 읽는 상태는 "범주를 검사하지 않는 상태"와
# 같으므로 통과시키지 않는다.


class ContractError(RuntimeError):
    """계약을 단일 출처로 쓸 수 없는 상태. 통과가 아니라 중단이다."""


@dataclass(frozen=True)
class MaskContract:
    """계약이 못박은 마스킹 범주 계약."""

    #: `MaskedItemResponse.category` 의 enum. **순서까지** 계약 그대로다.
    categories: tuple[str, ...]
    #: `MaskedItemResponse.placeholder` 의 정규식. 자리표시자는 표기가 아니라 복원 키다.
    placeholder_pattern: str
    source: str


_MASK_CONTRACT: MaskContract | None = None


def mask_contract() -> MaskContract:
    """계약에서 마스킹 범주 enum과 자리표시자 패턴을 읽는다. 실패하면 `ContractError`."""
    global _MASK_CONTRACT
    if _MASK_CONTRACT is not None:
        return _MASK_CONTRACT
    try:
        # 아래 무시 주석의 사유 — PyYAML 은 현재 `uvicorn[standard]` 의 전이 의존이라 들어와
        # 있고 `types-PyYAML` 스텁은 설치돼 있지 않다. 스텁 추가는 `pyproject.toml` 소유자의
        # 일이므로 여기서는 사유만 적어 남긴다(리포트에 후속 항목으로 올린다).
        import yaml  # type: ignore[import-untyped]
    except ImportError as exc:  # pragma: no cover - 환경 결손
        raise ContractError(
            f"PyYAML 이 없어 {CONTRACT_PATH.name} 을 읽을 수 없다 ({exc}). "
            "계약을 읽지 못하는 상태는 범주를 검사하지 않는 상태와 같으므로 통과시키지 않는다"
        ) from None
    try:
        document = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        raise ContractError(
            f"{CONTRACT_PATH} 를 읽을 수 없다 ({type(exc).__name__}: {exc})"
        ) from None
    schema = (
        (document or {}).get("components", {}).get("schemas", {}).get("MaskedItemResponse")
        if isinstance(document, dict)
        else None
    )
    properties = schema.get("properties") if isinstance(schema, dict) else None
    if not isinstance(properties, dict):
        raise ContractError(
            f"{CONTRACT_PATH.name} 에 `components.schemas.MaskedItemResponse.properties` 가 없다"
        )
    enum = (properties.get("category") or {}).get("enum")
    pattern = (properties.get("placeholder") or {}).get("pattern")
    if not isinstance(enum, list) or not enum or not all(isinstance(name, str) for name in enum):
        raise ContractError(
            f"{CONTRACT_PATH.name} 의 `MaskedItemResponse.category.enum` 이 문자열 목록이 아니다"
        )
    if not isinstance(pattern, str) or not pattern:
        raise ContractError(
            f"{CONTRACT_PATH.name} 의 `MaskedItemResponse.placeholder.pattern` 이 없다"
        )
    try:
        compiled = re.compile(pattern)
    except re.error as exc:
        raise ContractError(
            f"{CONTRACT_PATH.name} 의 placeholder pattern 이 정규식이 아니다 ({exc})"
        ) from None
    # 계약 **안에서의** 정합. enum과 pattern은 같은 사실을 두 번 적는 자리라 한쪽만 고치는
    # 부분 수정이 실제로 일어난다. 그러면 "enum에는 있는데 자리표시자로는 쓸 수 없는 범주"가
    # 생기고, 게이트는 어느 쪽을 믿어야 할지 모른 채 통과시킨다. 여기서 막는다.
    # (이것은 값을 다시 적는 것이 아니라 계약이 자기 자신과 맞는지 보는 것이다 —
    #  범주 문자열의 정본은 여전히 계약 하나뿐이다.)
    unusable = [name for name in enum if not compiled.fullmatch(f"[[{name}1]]")]
    if unusable:
        raise ContractError(
            f"{CONTRACT_PATH.name} 의 category enum 과 placeholder pattern 이 어긋난다 — "
            f"{unusable} 는 enum에 있는데 자리표시자 패턴 `{pattern}` 이 받지 않는다. "
            "한쪽만 고친 부분 수정으로 보인다"
        )
    _MASK_CONTRACT = MaskContract(
        categories=tuple(enum),
        placeholder_pattern=pattern,
        source=f"{CONTRACT_PATH.name}::MaskedItemResponse",
    )
    return _MASK_CONTRACT


#: 판정 방식. 도메인마다 **하나**를 선언한다.
MODE_SPEC = "spec"  # 요구 성질로 판정한다 (Python 출력은 참고값)
MODE_COMPAT = "compat"  # 값 동일성이 곧 요구사항이다 (crypto·jwt·argon2)

#: spec 도메인의 성질 작성 상태. pending은 "아직 성질로 표현하지 못했다"이며
#: 비교기에서 **미검증**으로 집계된다 — 통과가 아니다.
STATUS_READY = "ready"
STATUS_PENDING = "pending"


@dataclass(frozen=True)
class FixtureSpec:
    """도메인 하나의 fixture 정본.

    `requirement`는 이 도메인이 무엇을 지켜야 하는지를 **문서 근거와 함께** 적는 자리다.
    폐기된 전제("Python과 같은 값")로 되돌아가는 것을 막는 첫 방어선이 이 한 줄이다 —
    여기에 근거를 못 적으면 그 도메인은 아직 성질로 판정할 준비가 안 된 것이다.
    """

    source: str
    mode: str
    requirement: str
    normalization: list[str]
    cases: list[Case]
    spec_status: str = STATUS_READY

    def __post_init__(self) -> None:
        if self.mode not in (MODE_SPEC, MODE_COMPAT):
            raise ValueError(f"알 수 없는 mode: {self.mode}")
        if self.mode == MODE_COMPAT and self.spec_status != STATUS_READY:
            raise ValueError("compat 도메인에는 spec_status 가 없다")
        if self.mode == MODE_SPEC and self.spec_status not in (STATUS_READY, STATUS_PENDING):
            raise ValueError(f"알 수 없는 spec_status: {self.spec_status}")

    def document(self, domain: str) -> dict[str, Any]:
        """fixture 파일에 쓰이는 형태. `generated_at`은 여기서 넣지 않는다.

        비교기가 정본 대조에 그대로 쓰는 값이므로 **결정적**이어야 한다.
        """
        header: dict[str, Any] = {
            "domain": domain,
            "mode": self.mode,
            "requirement": self.requirement,
            "source": self.source,
            "generator": "dump_parity_fixtures.py",
            "normalization": self.normalization,
        }
        if self.mode == MODE_SPEC:
            header["spec_status"] = self.spec_status
        header["cases"] = [self._render(case) for case in self.cases]
        return header

    def _render(self, case: Case) -> Case:
        """spec 도메인에서는 Python 실행 결과의 이름을 `reference`로 바꾼다.

        이름이 곧 계약이다. `expected`라고 부르는 순간 "Python이 낸 값에 맞춰라"라는
        읽기가 되살아난다. spec 도메인에서 그 값의 지위는 **참고**뿐이다.
        """
        if self.mode == MODE_COMPAT or "expected" not in case:
            return case
        rendered = {key: value for key, value in case.items() if key != "expected"}
        rendered["reference"] = case["expected"]
        return rendered


Builder = Callable[[], FixtureSpec]


def _case(case_id: str, description: str, payload: Any, expected: Any, **extra: Any) -> Case:
    case: Case = {
        "id": case_id,
        "description": description,
        "input": payload,
        "expected": expected,
    }
    case.update(extra)
    return case


def _assert(check: str, **args: Any) -> dict[str, Any]:
    """성질 단언 하나. `check` 이름의 정본은 `compare_parity.py`의 `CHECKS`다."""
    entry: dict[str, Any] = {"check": check}
    if args:
        entry["args"] = args
    return entry


def _unreferenced(
    case_id: str, description: str, payload: Any, asserts: list[dict[str, Any]], **extra: Any
) -> Case:
    """참고값 **없이** 성질만 있는 케이스.

    Python 쪽에 값싸게 돌릴 대응 실행이 없을 때 쓴다(예: 문서 1건 변환의 LLM 호출 횟수는
    서비스·저장소를 세워야 나온다). 참고값을 억지로 지어 넣으면 참고 갈림 원장이 매 실행
    갈림으로 차서 신호가 죽는다 — 없으면 없다고 두는 편이 정직하고 유용하다.
    """
    case: Case = {"id": case_id, "description": description, "input": payload}
    case["assert"] = asserts
    case.update(extra)
    return case


def _external(
    *,
    command: str,
    actual_file: str,
    required_cases: int,
    actual_schema: dict[str, str],
) -> dict[str, Any]:
    """역방향(Kotlin → Python) 케이스임을 표시한다.

    이런 케이스는 Kotlin 산출물을 값으로 비교해서는 닫을 수 없다 — Kotlin이 기대값을
    그대로 되받아 적으면 아무것도 실행하지 않고도 "일치"가 나온다.

    **증거 파일은 비교기의 입력이 아니다.** 예전에는 `verify-*`가 남긴 `*.verified.json`을
    비교기가 읽어 판정했는데, 그 파일은 손으로 6줄만 적으면 만들어졌다(교차 리뷰 X-1).
    지금 비교기는 `actual_file`(Kotlin 산출물)을 찾아 **자기가 검증기를 돌리고** 그 결과로
    판정한다. 증거 파일은 그 실행의 **기록**으로 새로 쓰인다.

    - `command` — 비교기가 호출할 검증기 이름(`VERIFIERS` 키). 문자열 파싱 없이 이 값으로 건다.
    - `actual_file` — Kotlin 산출물 파일 이름. `--actual` 디렉터리 기준 상대 이름이다.
      이 파일이 없으면 통과가 아니라 **미검증(pending)**이다.
    """
    return {
        "mode": "external",
        "command": command,
        "script": f"dump_parity_fixtures.py {command}",
        "actual_file": actual_file,
        "proof": PROOF_FILE_NAMES[command],
        "required_cases": required_cases,
        "actual_schema": actual_schema,
    }


# ------------------------------------------------------------------- 고정 시각 주입


class _FrozenClock:
    """`datetime.now()` 자리에 끼워 넣는 고정 시계.

    JWT의 `exp` 검증은 실행 시각에 좌우된다. 벽시계로 검증하면 fixture를 만든 날과 돌리는
    날이 다를 때 같은 토큰의 판정이 뒤집혀 parity 검증 자체가 무의미해진다. 그래서 fixture는
    케이스마다 기준 시각(`verify_at`, epoch 초)을 못박고 검증할 때 그 시각을 주입한다.
    """

    def __init__(self, moment: datetime) -> None:
        self._moment = moment

    def now(self, tz: tzinfo | None = None) -> datetime:
        return self._moment if tz is None else self._moment.astimezone(tz)


@contextmanager
def _frozen(module: ModuleType, moment: datetime) -> Iterator[None]:
    """`module.datetime.now()`가 `moment`를 반환하도록 잠시 바꾼다.

    발급은 `app.services.auth`의 시계를, 검증은 PyJWT(`jwt.api_jwt`)의 시계를 묶는다.
    두 곳을 동시에 묶지 않는다 — `jwt.encode`는 `datetime` 클래스로 `isinstance` 검사를
    하므로 발급 중에 PyJWT 쪽 시계를 바꾸면 인코딩이 깨진다.
    """
    with patch.object(module, "datetime", _FrozenClock(moment)):
        yield


#: `AuthService.resolve_token`·`_issue_token`은 저장소를 만지지 않는다. 이 자리에 실제
#: 저장소를 끼우면 fixture 생성이 DB에 묶이므로, 호출되면 그 자체가 결함인 자리표시자를 둔다.
_UNUSED_STORE = object()


# --------------------------------------------------------------------------- 마스킹


def build_masking() -> FixtureSpec:
    """개인정보 마스킹 — 주민등록번호·카드번호를 빠짐없이 가리고 나머지 본문은 보존한다"""
    from app.privacy.masking import mask_text

    #: 마스킹 범주. **리터럴로 적지 않고 계약에서 읽는다.** 사용자 결정(2026-08-12)으로
    #: 2종만 유지하지만, 그 사실을 여기에 다시 적으면 계약과 생성기가 두 벌이 되고 어긋난
    #: 쪽을 알려 줄 장치가 없어진다. 범주 문자열은 자리표시자에 그대로 박히므로
    #: (`[[주민등록번호1]]`) 값 자체가 복원 계약의 일부이고, 그 정본은 API 계약이다.
    categories = list(mask_contract().categories)

    # ── 자릿수 표기 축 ────────────────────────────────────────────────────────
    # 리터럴로 적지 않고 코드포인트로 조립한다. 편집기·도구 경로에서 조용히 변형되는
    # 문자들이고, 코드포인트가 곧 근거이기도 하다.
    def chars(*codepoints: int) -> str:
        return "".join(chr(code) for code in codepoints)

    arabic_indic = chars(*range(0x0660, 0x066A))  # U+0660~U+0669
    fullwidth = chars(*range(0xFF10, 0xFF1A))  # U+FF10~U+FF19

    def restyle(text: str, table: str) -> str:
        """ASCII 숫자만 다른 자릿수 표기로 바꾼다 (구분자는 ASCII 그대로 둔다)."""
        return "".join(table[int(ch)] if ch in "0123456789" else ch for ch in text)

    rrn_arabic_head = restyle("900101", arabic_indic) + "-1234567"
    rrn_fullwidth = restyle("900101-1234567", fullwidth)
    card_arabic_head = restyle("1234", arabic_indic) + "-5678-9012-3456"
    card_fullwidth = restyle("1234-5678-9012-3456", fullwidth)

    # ── 회피 문자 축 ──────────────────────────────────────────────────────────
    # `privacy-gate` 판정(`02_privacy-gate_control-char-verdict.md`, 2026-08-12)이 (가)
    # **실제 위험**으로 닫은 자리다. 판정 근거의 요지 셋:
    #   - 위협 모델은 "악의적 회피"가 아니라 **사고성 유입**이다. 업로더(기관 담당자)와
    #     개인정보 주체(문서에 등장하는 시민)가 다르므로 숨겨서 얻는 이득이 없다.
    #   - 그런데도 실재한다 — 실문서 16건(1,971,740자)에서 제어문자 1,000건 이상,
    #     그중 "숫자 사이 끼임" 4건, U+00AD가 하이픈 자리를 대신한 사례가 실측됐다(M4·M5).
    #   - 막아야 할 대상은 "제어문자"가 아니라 **숫자 사이에서 보이지 않는 문자 전체**다.
    #     C0 제어문자는 docx·hwpx에서 XML 1.0 위반으로 파일째 거부되지만, U+00AD·U+200B·
    #     U+FEFF는 XML에 합법이라 그대로 통과하고 PDF·JSON 붙여넣기 경로는 아예 무방비다.
    #
    # 그래서 `known_gap`(어느 방향도 단언하지 않음)을 걷어내고 `absent` 단언으로 전환한다.
    # 단언이 구현보다 앞서는 것이 옳은 의존 방향이다 — 성질을 먼저 못박고 구현이 따라온다.
    # 판정서 §5.4가 U+00AD를 최우선으로 지목했다(유일하게 실문서 근거가 있는 문자).
    evasive = (
        ("soft-hyphen", 0x00AD, "소프트하이픈 U+00AD — **실문서에서 실제로 검출된 문자**"),
        ("zwsp", 0x200B, "폭 없는 공백 U+200B — 웹페이지 복사·붙여넣기로 흔히 섞인다"),
        ("bom", 0xFEFF, "폭 없는 비분할 공백 U+FEFF — 파일 병합 흔적으로 본문 중간에 남는다"),
        ("nul", 0x0000, "NUL U+0000 — PDF 추출·JSON 붙여넣기 경로로 들어온다"),
        (
            "fs",
            0x001C,
            "파일 구분자 U+001C — `splitlines()` 경계라 개행으로 바뀌어도 매치가 깨진다",
        ),
        ("us", 0x001F, "단위 구분자 U+001F — 표 붙여넣기 잔재"),
    )

    def surroundings(text: str, asserts: tuple[Any, ...]) -> list[str]:
        """가려야 할 조각을 뺀 **나머지 본문 조각**을 모은다.

        케이스마다 반대 방향 가드를 자동으로 붙이기 위한 것이다. `absent`만 있는 케이스는
        본문을 통째로 가린 구현도 만족시킨다 — 실제로 스탠드인 실험에서 전문 마스킹 구현이
        RRN 케이스들을 전부 통과했다. 주변 본문이 남아 있는지를 함께 걸면 그 경로가 막힌다.
        """
        needles = [
            needle
            for entry in asserts
            if entry.get("check") == "absent"
            for needle in entry.get("args", {}).get("needles", [])
        ]
        if not needles:
            # 가릴 것을 지목하지 않은 케이스(알려진 공백)는 여기서 아무것도 만들지 않는다.
            # 만들면 "본문 전체가 그대로 남아야 한다"가 되어, 판정을 미루겠다고 적어 둔
            # 자리에서 **개선을 금지**하게 된다.
            return []
        remaining = [text]
        for needle in needles:
            remaining = [piece for chunk in remaining for piece in chunk.split(needle)]
        return [piece.strip() for piece in remaining if piece.strip()]

    def case(name: str, text: str, description: str, *asserts: Any, **extra: Any) -> Case:
        """케이스 하나. 모든 케이스가 두 구조 불변식을 공통으로 진다.

        `restores_input` — 자리표시자를 원문으로 되돌리면 입력과 정확히 같아진다.
            마스킹이 본문을 잃거나 바꾸지 않았고 대응표가 실제로 복원 가능하다는 뜻이다.
            내보내기(`restore_placeholders`)가 이 성질 위에 서 있다.
        `placeholder_scheme` — 자리표시자가 `[[{범주}{번호}]]` 형태이고, 범주가 위 2종
            안이며, 번호가 범주별로 1부터 등장 순서로 붙는다. `items` 순서·범주도 함께 본다.

        둘 다 **Python 출력을 보지 않고** 판정된다. 어느 쪽도 "값이 같은가"를 묻지 않는다.
        여기에 `absent`가 붙은 케이스는 남은 본문 조각에 대한 `present`가 자동으로 따라붙어
        모든 케이스가 양방향이 된다.
        """
        result = mask_text(text)
        kept = surroundings(text, asserts)
        if kept:
            asserts = (*asserts, _assert("present", path="masked_text", needles=kept))
        return _case(
            f"masking-{name}",
            description,
            {"text": text},
            {
                "masked_text": result.masked_text,
                "items": [
                    {
                        "category": item.category.value,
                        "placeholder": item.placeholder,
                        "original": item.original.get_secret_value(),
                    }
                    for item in result.items
                ],
            },
            **{
                "assert": [
                    _assert("restores_input"),
                    _assert("placeholder_scheme", categories=categories),
                    *asserts,
                ],
                **extra,
            },
        )

    hidden = "가려야 한다 — 남으면 개인정보가 그대로 외부 LLM으로 나간다"
    kept = "가리면 안 된다 — 범위 밖이거나 개인정보가 아니다. 과잉 마스킹은 팩트를 지운다"

    cases = [
        case(
            "plain",
            "이 안내문에는 개인정보가 없습니다.",
            f"개인정보가 없는 문장은 한 글자도 바뀌지 않는다. {kept}",
            _assert("present", path="masked_text", needles=["이 안내문에는 개인정보가 없습니다."]),
        ),
        case(
            "empty",
            "",
            "빈 입력에서 예외를 던지지 않고 빈 결과를 낸다",
        ),
        # ── 주민등록번호: 표기 변형을 빠짐없이 잡는다 ─────────────────────────
        case(
            "rrn-hyphen",
            "주민등록번호 900101-1234567 를 확인합니다.",
            f"하이픈 표기 주민등록번호를 {hidden}",
            _assert("absent", path="masked_text", needles=["900101-1234567"]),
        ),
        case(
            "rrn-no-sep",
            "번호는 9001011234567 입니다.",
            f"구분자 없는 13자리 표기도 {hidden}",
            _assert("absent", path="masked_text", needles=["9001011234567"]),
        ),
        case(
            "rrn-spaced",
            "주민등록번호 900101 - 1234567 확인.",
            f"하이픈 앞뒤에 공백이 있는 표기도 {hidden}",
            _assert("absent", path="masked_text", needles=["900101 - 1234567"]),
        ),
        case(
            "rrn-tab",
            "주민등록번호 900101\t-\t1234567 확인.",
            f"탭이 구분자로 쓰인 표기도 {hidden} (표 붙여넣기에서 실제로 나온다)",
            _assert("absent", path="masked_text", needles=["900101\t-\t1234567"]),
        ),
        case(
            "rrn-foreigner",
            "외국인등록번호 900101-5234567 확인.",
            f"성별코드 5~8(외국인등록번호)도 고유식별정보다. {hidden}",
            _assert("absent", path="masked_text", needles=["900101-5234567"]),
        ),
        case(
            "rrn-unicode-digit-head",
            f"번호 {rrn_arabic_head} 확인.",
            "앞 6자리가 아랍-인도 숫자여도 주민등록번호다. "
            f"{hidden}. Java 기본 `\\d`=`[0-9]`로 옮기면 여기서 조용히 누락된다",
            _assert("absent", path="masked_text", needles=[rrn_arabic_head]),
        ),
        # ── 카드번호 ──────────────────────────────────────────────────────────
        case(
            "card-hyphen",
            "카드번호 1234-5678-9012-3456 을 입력합니다.",
            f"하이픈 표기 16자리 카드번호를 {hidden}",
            _assert("absent", path="masked_text", needles=["1234-5678-9012-3456"]),
        ),
        case(
            "card-spaced",
            "카드번호 1234 5678 9012 3456 입력.",
            f"공백 구분 표기도 {hidden}",
            _assert("absent", path="masked_text", needles=["1234 5678 9012 3456"]),
        ),
        case(
            "card-no-sep",
            "카드번호 1234567890123456 입력.",
            f"구분자 없는 16자리도 {hidden}",
            _assert("absent", path="masked_text", needles=["1234567890123456"]),
        ),
        case(
            "card-unicode-digit-arabic",
            f"카드 {card_arabic_head} 입력.",
            f"아랍-인도 숫자가 섞인 카드번호도 {hidden}",
            _assert("absent", path="masked_text", needles=[card_arabic_head]),
        ),
        case(
            "card-unicode-digit-fullwidth",
            f"카드 {card_fullwidth} 입력.",
            f"전각 숫자로 적은 카드번호도 {hidden}",
            _assert("absent", path="masked_text", needles=[card_fullwidth]),
        ),
        # ── 번호 매김·복원 ────────────────────────────────────────────────────
        case(
            "multi-same-category",
            "900101-1234567 와 850505-2345678 두 건.",
            "같은 범주 2건은 등장 순서로 1·2번을 받는다. 전역 카운터 구현이 여기서 죽고, "
            "번호가 어긋나면 복원이 잘못된 원문을 꽂는다",
            _assert("absent", path="masked_text", needles=["900101-1234567", "850505-2345678"]),
        ),
        case(
            "multi-category",
            "주민 900101-1234567 카드 1234-5678-9012-3456 입니다.",
            "서로 다른 범주는 각각 1번부터 센다. `items` 순서는 텍스트 등장 순서다",
            _assert(
                "absent", path="masked_text", needles=["900101-1234567", "1234-5678-9012-3456"]
            ),
        ),
        case(
            "newline",
            "이름: 홍길동\n주민: 900101-1234567\n주소: 서울시 어딘가 1-2",
            f"개행과 마스킹 대상이 아닌 줄은 그대로 남는다. 이름·주소는 {kept}",
            _assert("absent", path="masked_text", needles=["900101-1234567"]),
            _assert(
                "present",
                path="masked_text",
                needles=["이름: 홍길동", "주소: 서울시 어딘가 1-2"],
            ),
        ),
        # ── 과잉 마스킹 가드 ──────────────────────────────────────────────────
        case(
            "keeps-date",
            "신청 기간은 2026-01-01 부터입니다.",
            f"날짜는 개인정보가 아니다. {kept} — 지우면 안내문의 핵심 팩트가 사라진다",
            _assert("present", path="masked_text", needles=["2026-01-01"]),
        ),
        case(
            "keeps-long-digits",
            "접수번호 123456789012 와 관리번호 12345678901234 를 적으세요.",
            f"12자리·14자리 숫자열은 주민번호도 카드번호도 아니다. {kept}",
            _assert("present", path="masked_text", needles=["123456789012", "12345678901234"]),
        ),
        # ── 범위 밖 — 가리지 않는 것이 요구사항이다 ───────────────────────────
        # 이 셋은 한때 `reference_divergence="expected"`였다. 그때는 Python이 아직
        # 5범주였고 요구사항과 갈리는 것이 정상이었기 때문이다. Python 구현이 2종으로
        # 축소되면서(`app/privacy/masking.py`) 참고값이 요구사항과 일치하게 되어 선언이
        # 낡았고, 남겨 두면 비교기가 "의도한 갈림이 사라졌다"로 막는다. 선언을 지운다고
        # 판정이 느슨해지지는 않는다 — `present` 단언이 그대로 남아, 누가 패턴을 다시
        # 넓히면 여기서 걸린다.
        case(
            "scope-out-phone",
            "연락처 010-1234-5678 로 전화 주세요.",
            f"전화번호는 마스킹 범주에서 뺐다(사용자 결정 2026-08-12). {kept}. "
            "가려지면 정책 위반이다 — 마스킹 없이 LLM으로 나가는 것이 감수한 대가다",
            _assert("present", path="masked_text", needles=["010-1234-5678"]),
        ),
        case(
            "scope-out-email",
            "문의는 kim@example.com 으로 주세요.",
            f"이메일은 마스킹 범주에서 뺐다. {kept}. 가려지면 정책 위반이다",
            _assert("present", path="masked_text", needles=["kim@example.com"]),
        ),
        case(
            "scope-out-account",
            "계좌 123-456-789012 로 입금하세요.",
            f"계좌번호는 마스킹 범주에서 뺐다. {kept}. 가려지면 정책 위반이다",
            _assert("present", path="masked_text", needles=["123-456-789012"]),
        ),
        # ── 보이지 않는 문자 회피 — privacy-gate 판정 (가)로 단언 전환 ────────
        *[
            case(
                f"rrn-{name}",
                f"번호 900101{chars(code)}-1234567 확인.",
                f"주민등록번호 숫자 사이에 {note}가 끼어 있어도 {hidden}. "
                "정규화한 뷰에서 찾고 자르기는 원문 좌표로 한다 — 원문을 정규화해 넘기면 "
                "`restores_input`이 깨지고 내보내기가 잘못된 원문을 꽂는다",
                _assert(
                    "absent",
                    path="masked_text",
                    needles=[f"900101{chars(code)}-1234567"],
                ),
            )
            for name, code, note in evasive
        ],
        case(
            "rrn-zwsp-inside-tail",
            f"번호 900101-123{chars(0x200B)}4567 확인.",
            f"뒤 7자리 **안쪽**에 폭 없는 공백이 끼어도 {hidden}. 앞뒤 경계만 훑는 구현은 "
            "여기서 걸린다",
            _assert("absent", path="masked_text", needles=[f"900101-123{chars(0x200B)}4567"]),
        ),
        case(
            "card-zwsp",
            f"카드 1234-5678-9012{chars(0x200B)}-3456 입력.",
            f"같은 회피가 카드번호에도 걸린다(판정서 M11 — 2종 축소본에서 6종 전부 누락). {hidden}",
            _assert("absent", path="masked_text", needles=[f"1234-5678-9012{chars(0x200B)}-3456"]),
        ),
        # 회피 차단의 반대 방향 가드. 이것이 없으면 "보이지 않는 문자를 접는다"를
        # "공백을 전부 접는다"로 구현해도 통과하고, 그 구현은 서로 다른 줄의 숫자를 붙여
        # 진짜 과잉 마스킹을 만든다(판정서 §5.2 — 탭·개행·일반 공백은 제외한다).
        case(
            "keeps-newline-split-digits",
            "접수번호 900101\n1234567 을 적으세요.",
            f"개행으로 갈린 두 숫자열은 하나의 주민등록번호가 아니다. {kept} — "
            "회피 차단을 공백 접기로 구현하면 여기서 두 줄이 붙어 오검출된다",
            _assert("present", path="masked_text", needles=["900101\n1234567"]),
        ),
        # ── 알려진 공백 — 어느 방향도 단언하지 않는다 ─────────────────────────
        case(
            "known-gap-rrn-fullwidth",
            f"번호 {rrn_fullwidth} 확인.",
            "전각 숫자로만 적은 주민등록번호는 **현재 Python이 가리지 못한다**(성별코드 "
            "`[1-8]`이 ASCII 리터럴이라 매치가 끊긴다). 요구사항으로 보면 가려야 맞지만, "
            "지금 그것을 단언하면 Kotlin에 Python보다 넓은 구현을 요구하게 되므로 "
            "**어느 방향도 단언하지 않는다**. 개선하면 참고 갈림 원장에 찍혀 드러난다. "
            "이 건은 `privacy-gate` 판정 §5.4의 범위 **밖**이다(별개 사안으로 명시)",
            known_gap="전각 표기 주민등록번호 미검출 (개선 후보 — 판정 대상 아님)",
        ),
    ]
    return FixtureSpec(
        source="app/privacy/masking.py::mask_text",
        mode=MODE_SPEC,
        requirement=(
            "master-plan §3.2 마스킹 선행 + 사용자 결정(2026-08-12) 범위 축소 + "
            "privacy-gate 판정 (가)(02_privacy-gate_control-char-verdict.md) — "
            "문서 본문이 LLM으로 나가기 전에 주민등록번호(외국인등록번호 포함)와 카드번호가 "
            "빠짐없이 가려지고(숫자 사이에 보이지 않는 문자가 끼어 있어도 가려진다), "
            "그 밖의 본문은 한 글자도 잃지 않으며, 자리표시자를 되돌리면 원문이 정확히 "
            f"복원된다. 범주 문자열의 정본은 계약({mask_contract().source})이다"
        ),
        normalization=BASE_NORMALIZATION,
        cases=cases,
    )


# ----------------------------------------------------------------------- 텍스트 정규화


def build_text() -> FixtureSpec:
    """제어문자 제거 — XML에 담을 수 없는 문자만 빼고 나머지 본문은 그대로 둔다"""
    from app.text import strip_control_chars

    samples: list[tuple[str, str]] = [
        ("keeps-tab-lf-cr", "가\t나\n다\r라"),
        ("drops-nul", "가\x00나"),
        ("drops-vt-ff", "가\x0b나\x0c다"),
        ("drops-del", "가\x7f나"),
        ("drops-c1-range", "가\x1f나\x0e다\x08라"),
        ("empty", ""),
        ("only-control", "\x00\x01\x02"),
        ("keeps-hangul-and-emoji", "가나다 😀 라마바\n둘째 줄"),
    ]
    # 이 도메인의 요구사항은 규칙으로 완전히 적힌다 — 제거 대상 집합이 정해져 있고
    # 나머지는 손대지 않는다. 그래서 판정도 Python 출력이 아니라 **입력에서 규칙으로
    # 유도한 값**과 비교한다(`control_strip`은 비교기가 자기 힘으로 계산한다).
    cases = [
        _case(
            f"text-{name}",
            "XML(docx)에 담을 수 없는 제어문자만 사라지고, 탭·개행·복귀와 나머지 문자는 "
            "순서까지 그대로 남는다. 지우면 문단 구조가 무너지고, 남기면 내보내기가 "
            "lxml ValueError로 500이 된다",
            {"text": text},
            {"text": strip_control_chars(text)},
            **{
                "assert": [
                    _assert("equals_derived", rule="control_strip", path="text", source="text")
                ]
            },
        )
        for name, text in samples
    ]
    return FixtureSpec(
        source="app/text.py::strip_control_chars",
        mode=MODE_SPEC,
        requirement=(
            "app/text.py 모듈 규약 — XML 1.0이 담지 못하는 제어문자"
            "(U+0000~U+0008, U+000B, U+000C, U+000E~U+001F, U+007F)만 제거하고 "
            "탭·개행·복귀를 포함한 나머지 문자는 순서까지 보존한다"
        ),
        normalization=BASE_NORMALIZATION,
        cases=cases,
    )


# ------------------------------------------------------------------------ 스타일 규칙


_STYLE_SAMPLES: list[tuple[str, str]] = [
    ("clean", "신청은 9월에 합니다. 서류를 내면 됩니다."),
    (
        "too-long",
        "이 사업은 소득이 적은 어르신께 매달 돈을 드리는 제도이며 신청은 주소지 "
        "행정복지센터에서 접수합니다.",
    ),
    ("too-many-commas", "가, 나, 다, 라를 준비하세요."),
    ("double-passive", "결과가 보여지고 있습니다."),
    ("difficult-word", "감면을 받으려면 신청서를 제출하세요."),
    ("difficult-word-inside-compound", "소득인정액을 확인합니다."),
    ("list-marker", "1.\n가.\n①\n신청서를 내세요."),
    ("gloss-collision", "내어 줌 받아 가세요."),
    ("multiline", "첫 문장입니다.\n두 번째 문장입니다.\n\n세 번째 문장입니다."),
    ("empty", ""),
]


def build_style() -> FixtureSpec:
    """스타일 검사 — 문장 분리, 어려운 표현, 뜻풀이 축자 삽입, 위반 목록"""
    from app.easyread.style_rules import (
        check_style,
        find_difficult_words,
        find_gloss_collisions,
        split_sentences,
    )

    cases = [
        _case(
            f"style-{name}",
            "split_sentences / find_difficult_words / find_gloss_collisions / check_style",
            {"text": text},
            {
                "sentences": split_sentences(text),
                "difficult_words": find_difficult_words(text),
                "gloss_collisions": find_gloss_collisions(text),
                "check_style": {
                    "total_sentences": (result := check_style(text)).total_sentences,
                    "issues": [issue.model_dump() for issue in result.issues],
                },
            },
        )
        for name, text in _STYLE_SAMPLES
    ]
    return FixtureSpec(
        source="app/easyread/style_rules.py::check_style 외",
        mode=MODE_SPEC,
        requirement=(
            "master-plan §3.3 쉬운 글 스타일 규칙 — 문장 길이 상한(MAX_SENTENCE_CHARS=50)과 "
            "한 문장 쉼표 상한(MAX_COMMAS_PER_SENTENCE=2)을 넘는 문장이 빠짐없이 위반으로 "
            "보고되고(누락 금지), 넘지 않는 문장이 위반으로 보고되지 않는다(오탐 금지). "
            "문장 분리 경계 자체는 휴리스틱이라 규칙으로 적히지 않는다 — 그 자리는 판정하지 "
            "않고 참고 갈림으로만 남긴다"
        ),
        normalization=BASE_NORMALIZATION,
        cases=cases,
        spec_status=STATUS_PENDING,
    )


def build_style_tables() -> FixtureSpec:
    """스타일 규칙 상수 표 전체를 그대로 덤프한다.

    상수 하나가 갈리면 프롬프트·검사·보정 채택이 동시에 어긋난다. 표를 통째로
    비교하면 "어느 낱말이 빠졌는지"까지 한 번에 드러난다.
    """
    from app.easyread import style_rules as sr

    tables = {
        "MAX_SENTENCE_CHARS": sr.MAX_SENTENCE_CHARS,
        "MAX_COMMAS_PER_SENTENCE": sr.MAX_COMMAS_PER_SENTENCE,
        "DOUBLE_PASSIVE_PATTERNS": list(sr.DOUBLE_PASSIVE_PATTERNS),
        "STYLE_PRINCIPLES": list(sr.STYLE_PRINCIPLES),
        "DIFFICULT_WORD_REPLACEMENTS": dict(sr.DIFFICULT_WORD_REPLACEMENTS),
        "PROMPT_ONLY_WORDS": sorted(sr.PROMPT_ONLY_WORDS),
        "LEXICALIZED_GLOSSES": sorted(sr.LEXICALIZED_GLOSSES),
        "COMPOUND_TAIL_KEYS": sorted(sr.COMPOUND_TAIL_KEYS),
        "COMPOUND_HEAD_NOUNS": sorted(sr.COMPOUND_HEAD_NOUNS),
        "NOMINAL_GLOSSES": sorted(sr.NOMINAL_GLOSSES),
        "MODIFIER_CHECKED_GLOSSES": sorted(sr.MODIFIER_CHECKED_GLOSSES),
    }
    counts = {
        key: (len(value) if isinstance(value, (list, dict)) else value)
        for key, value in tables.items()
    }
    cases = [
        _case(
            "style-tables-counts",
            "표 크기 — 낱말 하나가 조용히 빠지면 여기서 먼저 걸린다",
            {},
            counts,
        ),
        _case(
            "style-tables-full",
            "표 전문 — 순서까지 포함해 동일해야 한다(사전 정의 순서가 프롬프트 출력 순서다)",
            {},
            tables,
        ),
    ]
    return FixtureSpec(
        source="app/easyread/style_rules.py 상수",
        mode=MODE_SPEC,
        requirement=(
            "CLAUDE.md 아키텍처 규칙 4 — 스타일 규칙 정의가 한 곳이고 프롬프트 생성과 평가가 "
            "같은 정의를 쓴다. 정책 상수(MAX_SENTENCE_CHARS·MAX_COMMAS_PER_SENTENCE·"
            "STYLE_PRINCIPLES)는 값이 같아야 하고, 치환 사전은 **표제어를 잃지 않아야** 한다"
            "(추가는 개선이므로 허용하고 기록만 한다)"
        ),
        normalization=BASE_NORMALIZATION,
        cases=cases,
        spec_status=STATUS_PENDING,
    )


# ---------------------------------------------------------------------------- 프롬프트


def build_prompts() -> FixtureSpec:
    """프롬프트 렌더링 — 시스템·사용자·보정 프롬프트 전문"""
    from app.easyread.prompts import build_repair_prompt, build_system_prompt, build_user_prompt
    from app.easyread.style_rules import check_style

    masked_samples: list[tuple[str, str]] = [
        ("no-difficult-word", "신청은 9월에 합니다."),
        ("one-difficult-word", "감면을 받으려면 신청서를 제출하세요."),
        ("with-placeholder", "번호는 [[주민등록번호1]] 입니다. 감면 대상을 확인합니다."),
        (
            "many-difficult-words",
            "수급자는 부양의무자 기준을 충족해야 하며 소급 적용이 가능합니다.",
        ),
    ]
    cases: list[Case] = []
    for name, masked in masked_samples:
        issues = check_style(masked).issues
        repair_system, repair_user = build_repair_prompt(masked, issues)
        cases.append(
            _case(
                f"prompts-{name}",
                "build_system_prompt / build_user_prompt / build_repair_prompt",
                {"masked_text": masked, "violations": [issue.model_dump() for issue in issues]},
                {
                    "system_prompt": build_system_prompt(masked),
                    "user_prompt": build_user_prompt(masked),
                    "repair_system_prompt": repair_system,
                    "repair_user_prompt": repair_user,
                },
            )
        )
    # 사용자·보정 프롬프트는 요청마다 난수 문서 id를 넣는다(prompt injection 방어).
    # 그 자리만 가리고 비교한다 — 난수 자체가 같을 수는 없다.
    return FixtureSpec(
        source=(
            "app/easyread/prompts.py::build_system_prompt / build_user_prompt / build_repair_prompt"
        ),
        mode=MODE_SPEC,
        requirement=(
            "master-plan §3.3 — 프롬프트가 스타일 규칙 원칙 전량과 입력에서 검출된 어려운 말"
            "풀이를 싣고, 마스킹 자리표시자를 그대로 보존하며, prompt injection 방어용 문서 "
            "id 경계를 유지한다. 문면이 Python과 한 글자까지 같아야 하는 것은 아니다 — "
            "품질은 골든셋 게이트가 판정한다"
        ),
        normalization=[*BASE_NORMALIZATION, "mask_document_id"],
        cases=cases,
        spec_status=STATUS_PENDING,
    )


# --------------------------------------------------------------------------- 후처리


def build_postprocess() -> FixtureSpec:
    """LLM 응답 후처리 — 코드 펜스·머리말 제거(과잉 제거 금지)"""
    from app.easyread.postprocess import postprocess

    samples: list[tuple[str, str]] = [
        ("plain", "쉬운 글 본문입니다."),
        ("fence", "```\n본문입니다.\n```"),
        ("fence-lang", "```markdown\n본문입니다.\n```"),
        ("preamble", "다음은 변환 결과입니다:\n본문입니다."),
        ("preamble-without-body", "다음은 변환 결과입니다:"),
        ("preamble-lookalike", "다음은 심사 결과입니다.\n본문입니다."),
        ("fence-then-preamble", "```\n아래는 쉬운 글입니다:\n본문입니다.\n```"),
        ("only-whitespace", "   \n  "),
    ]
    cases = [
        _case(
            f"postprocess-{name}",
            "postprocess — 코드 펜스·머리말 제거 조건(과잉 제거 금지)",
            {"raw": raw},
            {"text": postprocess(raw)},
        )
        for name, raw in samples
    ]
    return FixtureSpec(
        source="app/easyread/postprocess.py::postprocess",
        mode=MODE_SPEC,
        requirement=(
            "LLM 응답에서 코드 펜스·머리말 같은 껍데기만 벗기고 **본문은 한 글자도 잃지 "
            "않는다**. 과잉 제거가 과소 제거보다 위험하다 — 사용자는 성공 응답을 받고 본문 "
            "일부가 사라진 결과를 받는다"
        ),
        normalization=BASE_NORMALIZATION,
        cases=cases,
        spec_status=STATUS_PENDING,
    )


# ------------------------------------------------------------------------ 보정 채택


def build_repair_adoption() -> FixtureSpec:
    """보정 채택 정책과 변환 호출 상한 — 자리표시자 유실·악화는 거부, 호출은 최대 2회"""
    from app.easyread.style_rules import check_style
    from app.services.conversion import MAX_LLM_CALLS_PER_CONVERSION, _accepts_repair

    samples: list[tuple[str, str, str, list[str]]] = [
        ("improves", "결과가 보여지고 있습니다.", "결과를 보여 드립니다.", []),
        ("worsens", "감면을 받으세요.", "감면을 받으시고, 가, 나, 다, 라를 준비하세요.", []),
        ("equal-count", "감면을 받으세요.", "제출을 하세요.", []),
        (
            "loses-placeholder",
            "번호는 [[주민등록번호1]] 감면 대상입니다.",
            "번호를 확인해 주세요.",
            ["[[주민등록번호1]]"],
        ),
        (
            "placeholder-absent-in-original",
            "감면 대상입니다.",
            "깎아 드립니다.",
            ["[[주민등록번호1]]"],
        ),
    ]
    # 이 도메인의 요구사항은 **정책**이라 규칙으로 완전히 적힌다:
    #   채택 = (자리표시자를 하나도 잃지 않았다) AND (위반 건수가 늘지 않았다)
    # 그래서 판정은 산출물이 **스스로 보고한 건수**를 입력으로 이 정책을 다시 계산해
    # 대조한다(`repair_policy`). 건수 자체가 맞는지는 `style` 도메인의 질문이고, 여기서는
    # "같은 건수를 받았을 때 같은 결정을 내리는가"만 본다 — 두 질문을 섞으면 어느 쪽이
    # 틀렸는지 알 수 없다.
    cases: list[Case] = [
        _case(
            f"repair-{name}",
            "보정 결과 채택 정책 — 자리표시자를 잃거나 위반이 늘면 거부하고, "
            "같은 건수는 채택한다(경계값)",
            {"original": original, "candidate": candidate, "placeholders": placeholders},
            {
                "accepted": _accepts_repair(
                    original=original,
                    candidate=candidate,
                    issues=check_style(original).issues,
                    placeholders=placeholders,
                ),
                "original_issue_count": len(check_style(original).issues),
                "candidate_issue_count": len(check_style(candidate).issues),
            },
            **{"assert": [_assert("equals_derived", rule="repair_policy", path="accepted")]},
        )
        for name, original, candidate, placeholders in samples
    ]
    # 변환 호출 상한은 master-plan §3.3의 계약이고 §5 Phase 7의 즉시 중단 기준이다.
    # 값이 아니라 **런타임 동작**이라 Python 쪽에 값싼 대응 실행이 없다 — 참고값 없이
    # 성질만 둔다. Kotlin 하네스는 fake provider로 변환 1건을 돌려 호출 횟수를 보고한다.
    cases += [
        _unreferenced(
            "repair-call-budget-clean",
            "위반이 기계 검출되지 않으면 보정을 부르지 않는다 — 변환 1건에 LLM 호출 1회. "
            "여기서 2회가 나오면 크레딧 원가 산정(master-plan 5장)이 무너진다",
            {"scenario": "no-style-violations"},
            [_assert("equals_field", path="llm_calls", value=1)],
        ),
        _unreferenced(
            "repair-call-budget-violations",
            f"위반이 있어 보정을 부르더라도 문서 1건당 LLM 호출은 "
            f"{MAX_LLM_CALLS_PER_CONVERSION}회를 넘지 않는다 — 루프 없음. 채택하든 "
            "거부하든 재보정하지 않는다",
            {"scenario": "style-violations-detected"},
            [_assert("at_most", path="llm_calls", limit=MAX_LLM_CALLS_PER_CONVERSION)],
        ),
    ]
    return FixtureSpec(
        source=("app/services/conversion.py::_accepts_repair / MAX_LLM_CALLS_PER_CONVERSION"),
        mode=MODE_SPEC,
        requirement=(
            "master-plan §3.3 변환 호출 계약 — 문서 1건 = LLM 호출 최대 2회(변환 1회 + "
            "기계 검출된 위반이 있을 때만 표적 보정 1회, 루프 없음). 보정 결과는 "
            "자리표시자를 잃거나 위반이 늘면 채택하지 않는다(보정 실패·악화 시 원본 채택)"
        ),
        normalization=BASE_NORMALIZATION,
        cases=cases,
    )


# -------------------------------------------------------------------------- 내보내기


def build_export() -> FixtureSpec:
    """내보내기 — 파일명 정제, RFC 5987 헤더, 자리표시자 복원, TXT 바이트"""
    from app.easyread.export import (
        ExportFormat,
        content_disposition,
        export_filename,
        render_export,
        restore_placeholders,
    )

    titles: list[tuple[str, str]] = [
        ("hangul", "기초연금 신청 안내"),
        ("path-chars", "../etc/passwd"),
        ("quotes", '보고서 "최종"'),
        ("dots", "...보고서..."),
        ("all-forbidden", "///"),
        ("long", "가" * 120),
        ("control-chars", "제\x00목\x1f입니다"),
    ]
    cases: list[Case] = [
        _case(
            f"export-filename-{name}",
            "export_filename / content_disposition — 파일명 정제와 RFC 5987 인코딩",
            {"title": title},
            {
                fmt.value: {
                    "filename": (filename := export_filename(title, fmt)),
                    "content_disposition": content_disposition(filename),
                }
                for fmt in ExportFormat
            },
        )
        for name, title in titles
    ]
    restore_samples: list[tuple[str, str, dict[str, str]]] = [
        ("basic", "번호는 [[주민등록번호1]] 입니다.", {"[[주민등록번호1]]": "900101-1234567"}),
        (
            "unknown-placeholder",
            "번호는 [[주민등록번호9]] 입니다.",
            {"[[주민등록번호1]]": "900101-1234567"},
        ),
        (
            "single-pass",
            "값은 [[카드번호1]] 입니다.",
            {"[[카드번호1]]": "[[주민등록번호1]]", "[[주민등록번호1]]": "900101-1234567"},
        ),
        ("none", "자리표시자가 없습니다.", {}),
    ]
    cases.extend(
        _case(
            f"export-restore-{name}",
            "restore_placeholders — 단일 패스 치환, 미등록 자리표시자는 보존",
            {"text": text, "originals": originals},
            {"text": restore_placeholders(text, originals)},
        )
        for name, text, originals in restore_samples
    )
    cases.append(
        _case(
            "export-txt-bytes",
            "TXT 산출물 — BOM 없는 UTF-8, 제목 줄 미첨부",
            {"title": "제목", "body": "본문 한 줄.\n두 번째 줄.\x00"},
            {
                "filename": (
                    file := render_export(
                        export_format=ExportFormat.TXT,
                        title="제목",
                        body="본문 한 줄.\n두 번째 줄.\x00",
                    )
                ).filename,
                "media_type": file.media_type,
                "content_utf8": file.content.decode("utf-8"),
                "content_sha256_hex": hashlib.sha256(file.content).hexdigest(),
            },
        )
    )
    # docx·hwpx는 바이트 동등이 기준이 아니다(§4.5 참고) — 자체 추출기 round-trip으로 본다.
    return FixtureSpec(
        source=(
            "app/easyread/export.py::export_filename / content_disposition / "
            "restore_placeholders / render_export(TXT)"
        ),
        mode=MODE_SPEC,
        requirement=(
            "내보내기 파일명에 경로 구분자·제어문자가 남지 않고 길이 상한을 지키며, "
            "Content-Disposition이 RFC 5987로 해석 가능하고, 자리표시자가 **하나도 남김없이** "
            "원문으로 복원된다(미복원 자리표시자는 개인정보 자리가 빈 채 배포되는 것이다)"
        ),
        normalization=BASE_NORMALIZATION,
        cases=cases,
        spec_status=STATUS_PENDING,
    )


# ------------------------------------------------------------------------------ 암호


def build_crypto() -> FixtureSpec:
    """Fernet 교차 런타임 — 정방향·역방향·변조·다른 키"""
    from cryptography.fernet import Fernet

    from app.privacy.crypto import CURRENT_KEY_VERSION, TextCipher

    key = Fernet.generate_key().decode()
    other_key = Fernet.generate_key().decode()
    cipher = TextCipher(key)

    plaintexts: list[tuple[str, str]] = [
        ("ascii", "hello world"),
        ("hangul", "기초연금 신청 안내입니다."),
        ("empty", ""),
        ("long", "가나다라마바사" * 2000),
        ("emoji-and-control", "줄바꿈\n탭\t끝"),
    ]
    cases: list[Case] = [
        _case(
            f"crypto-decrypt-{name}",
            "Python이 만든 Fernet 토큰을 Kotlin이 복호화한다",
            {"key": key, "token": cipher.encrypt(text).decode("ascii")},
            {"outcome": "ok", "plaintext": text, "key_version": CURRENT_KEY_VERSION},
        )
        for name, text in plaintexts
    ]
    good = cipher.encrypt("변조 대상").decode("ascii")
    tampered = base64.urlsafe_b64encode(
        bytes([base64.urlsafe_b64decode(good)[0] ^ 0x01, *base64.urlsafe_b64decode(good)[1:]])
    ).decode("ascii")
    cases.append(
        _case(
            "crypto-tampered",
            "변조된 토큰은 반드시 복호화 실패여야 한다 — 조용히 통과하면 인증 암호화가 아니다",
            {"key": key, "token": tampered},
            {"outcome": "invalid_token"},
        )
    )
    cases.append(
        _case(
            "crypto-wrong-key",
            "다른 키로는 복호화되지 않는다",
            {"key": other_key, "token": good},
            {"outcome": "invalid_token"},
        )
    )
    cases.append(
        _case(
            "crypto-garbage",
            "Fernet 토큰이 아닌 바이트",
            {"key": key, "token": "not-a-fernet-token"},
            {"outcome": "invalid_token"},
        )
    )
    cases.append(
        _case(
            "crypto-roundtrip-request",
            "역방향 검증용 — Kotlin이 이 평문들을 이 키로 암호화해 kotlin-encrypt.json 에 남긴다. "
            "Kotlin 결과 파일에 이 id를 적어도 닫히지 않는다 — 비교기가 그 산출물을 "
            "직접 복호화한다",
            {"key": key, "plaintexts": [text for _, text in plaintexts]},
            {"outcome": "verified_externally"},
            verification=_external(
                command="verify-crypto",
                actual_file="kotlin-encrypt.json",
                required_cases=len(plaintexts),
                actual_schema={
                    "key": "이 케이스의 key를 그대로",
                    "token": "Kotlin이 만든 Fernet 토큰(ASCII)",
                    "expected_plaintext": "암호화 전 평문",
                },
            ),
        )
    )
    return FixtureSpec(
        source="app/privacy/crypto.py::TextCipher",
        mode=MODE_COMPAT,
        requirement=(
            "롤백 창 호환성 — Python이 만든 Fernet 토큰을 Kotlin이 그대로 복호화하고 그 반대도 "
            "성립해야 한다. 값이 갈리면 기존 문서를 읽지 못한다(§5 Phase 7 즉시 중단 기준). "
            "변조 토큰·다른 키는 양쪽에서 똑같이 거부돼야 한다"
        ),
        normalization=["nfc"],
        cases=cases,
    )


# ------------------------------------------------------------------------------- JWT

#: fixture 전용 합성 시크릿. 운영 키를 넣지 않는다. 길이는 MIN_JWT_SECRET_BYTES(32) 이상.
JWT_SECRET = "parity-jwt-secret-" + "0" * 32
#: 공격자 키 역할. 이 키로 서명한 토큰은 반드시 거부돼야 한다.
JWT_OTHER_SECRET = "parity-jwt-other-secret-" + "1" * 32
#: 하한 경계 — 정확히 32바이트라 받아들여져야 한다.
JWT_MIN_SECRET = "m" * 32
#: 하한에서 1바이트 모자란다 — ConfigurationError로 기동이 끊겨야 한다.
JWT_TOO_SHORT_SECRET = "s" * 31

JWT_SUBJECT = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
JWT_OTHER_SUBJECT = "00000000-0000-4000-8000-000000000000"
#: 발급 기준 시각. 벽시계를 쓰지 않는다 — 아래 `verify_at`은 전부 이 값에서 파생된다.
JWT_ISSUED_AT = datetime(2026, 1, 2, 3, 4, 5, tzinfo=UTC)
JWT_EXPIRE_MINUTES = 30


def build_jwt() -> FixtureSpec:
    """JWT 교차 런타임 — 양방향·만료 경계·서명 위조·알고리즘 혼동·시크릿 하한"""
    import jwt as pyjwt
    from jwt import api_jwt

    from app.exceptions import ConfigurationError, InvalidCredentialsError
    from app.services import auth as auth_module
    from app.services.auth import (
        _ALGORITHM,
        _TOKEN_TYPE,
        MIN_JWT_SECRET_BYTES,
        AuthService,
        UserStore,
        WorkspaceCreator,
    )

    expires_at = JWT_ISSUED_AT + timedelta(minutes=JWT_EXPIRE_MINUTES)
    exp = int(expires_at.timestamp())
    issued_epoch = int(JWT_ISSUED_AT.timestamp())
    live_at = issued_epoch + 60

    def service(secret: str) -> AuthService:
        return AuthService(
            cast(UserStore, _UNUSED_STORE),
            cast(WorkspaceCreator, _UNUSED_STORE),
            secret,
            JWT_EXPIRE_MINUTES,
        )

    def issue(secret: str, subject: str) -> str:
        # 실제 발급 경로(`_issue_token`)를 고정 시계 아래에서 돌린다 — 클레임 구성을
        # 손으로 옮겨 적으면 두 구현이 아니라 두 벌의 사람 해석을 비교하게 된다.
        with _frozen(auth_module, JWT_ISSUED_AT):
            return service(secret)._issue_token(uuid.UUID(subject))

    def encode(claims: dict[str, Any], secret: str) -> str:
        return pyjwt.encode(claims, secret, algorithm=_ALGORITHM)

    def b64(raw: bytes) -> str:
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

    def raw_token(header: dict[str, str], claims: dict[str, Any], sign_with: str | None) -> str:
        """헤더를 직접 조립한 토큰 — PyJWT가 만들어 주지 않는 공격 형태를 만든다."""
        head = b64(json.dumps(header, separators=(",", ":"), sort_keys=True).encode("utf-8"))
        body = b64(json.dumps(claims, separators=(",", ":"), sort_keys=True).encode("utf-8"))
        if sign_with is None:
            return f"{head}.{body}."
        signature = hmac.new(
            sign_with.encode("utf-8"), f"{head}.{body}".encode("ascii"), hashlib.sha256
        ).digest()
        return f"{head}.{body}.{b64(signature)}"

    def outcome(secret: str, token: str, verify_at: int) -> dict[str, Any]:
        """`AuthService`를 그 기준 시각에 실제로 돌린 결과."""
        try:
            svc = service(secret)
        except ConfigurationError:
            return {"outcome": "configuration_error", "min_secret_bytes": MIN_JWT_SECRET_BYTES}
        with _frozen(api_jwt, datetime.fromtimestamp(verify_at, UTC)):
            try:
                subject = svc.resolve_token(token)
            except InvalidCredentialsError:
                return {"outcome": "invalid_credentials"}
            claims = pyjwt.decode(
                token,
                secret,
                algorithms=[_ALGORITHM],
                options={"require": ["sub", "exp", "typ"]},
            )
        return {
            "outcome": "ok",
            "subject": str(subject),
            "claims": {"sub": claims["sub"], "exp": claims["exp"], "typ": claims["typ"]},
        }

    valid = issue(JWT_SECRET, JWT_SUBJECT)
    head, _, signature = valid.split(".")
    base_claims: dict[str, Any] = {"sub": JWT_SUBJECT, "exp": exp, "typ": _TOKEN_TYPE}
    tampered_body = b64(
        json.dumps({**base_claims, "sub": JWT_OTHER_SUBJECT}, separators=(",", ":")).encode("utf-8")
    )

    #: (id, 설명, 시크릿, 토큰, 검증 기준 시각)
    samples: list[tuple[str, str, str, str, int]] = [
        (
            "valid",
            "Python이 발급한 토큰을 Kotlin이 읽는다 — sub·exp·typ 전부 대조",
            JWT_SECRET,
            valid,
            live_at,
        ),
        (
            "exp-boundary-one-second-before",
            "만료 1초 전은 유효하다 — 경계 판정이 갈리면 로그인이 조기에 끊긴다",
            JWT_SECRET,
            valid,
            exp - 1,
        ),
        (
            "exp-boundary-exact",
            "기준 시각이 exp와 같으면 만료다 (PyJWT: exp <= now). "
            "JVM 라이브러리는 exp < now로 보는 것이 많아 여기서 갈린다",
            JWT_SECRET,
            valid,
            exp,
        ),
        ("expired", "만료된 토큰은 거부된다", JWT_SECRET, valid, exp + 60),
        (
            "wrong-secret",
            "다른 키로는 검증되지 않는다",
            JWT_OTHER_SECRET,
            valid,
            live_at,
        ),
        (
            "forged-signature",
            "공격자 키로 서명한 토큰 — 조용히 통과하면 아무나 로그인한다",
            JWT_SECRET,
            issue(JWT_OTHER_SECRET, JWT_OTHER_SUBJECT),
            live_at,
        ),
        (
            "tampered-payload",
            "sub만 바꾸고 서명은 그대로 — 남의 계정으로 바뀌면 안 된다",
            JWT_SECRET,
            f"{head}.{tampered_body}.{signature}",
            live_at,
        ),
        (
            "alg-none",
            "alg=none·서명 없음 — 알고리즘 혼동의 고전. 반드시 거부",
            JWT_SECRET,
            raw_token({"alg": "none", "typ": "JWT"}, base_claims, None),
            live_at,
        ),
        (
            "alg-rs256-header",
            "헤더만 RS256으로 바꾸고 HMAC으로 서명 — 헤더의 alg를 믿으면 뚫린다",
            JWT_SECRET,
            raw_token({"alg": "RS256", "typ": "JWT"}, base_claims, JWT_SECRET),
            live_at,
        ),
        (
            "missing-exp",
            "exp 없는 토큰은 만료되지 않는 영구 자격증명이 된다 — require로 막는다",
            JWT_SECRET,
            encode({"sub": JWT_SUBJECT, "typ": _TOKEN_TYPE}, JWT_SECRET),
            live_at,
        ),
        (
            "missing-typ",
            "typ 없는 토큰 거부 (require)",
            JWT_SECRET,
            encode({"sub": JWT_SUBJECT, "exp": exp}, JWT_SECRET),
            live_at,
        ),
        (
            "missing-sub",
            "sub 없는 토큰 거부 (require)",
            JWT_SECRET,
            encode({"exp": exp, "typ": _TOKEN_TYPE}, JWT_SECRET),
            live_at,
        ),
        (
            "wrong-typ",
            "다른 용도로 발급한 토큰을 액세스 토큰으로 쓰려는 시도",
            JWT_SECRET,
            encode({"sub": JWT_SUBJECT, "exp": exp, "typ": "refresh"}, JWT_SECRET),
            live_at,
        ),
        (
            "non-uuid-subject",
            "sub가 UUID가 아니면 우리가 발급한 토큰이 아니다",
            JWT_SECRET,
            encode({"sub": "not-a-uuid", "exp": exp, "typ": _TOKEN_TYPE}, JWT_SECRET),
            live_at,
        ),
        ("garbage", "JWT 형식이 아닌 문자열", JWT_SECRET, "not-a-jwt", live_at),
        (
            "secret-exactly-min-bytes",
            f"시크릿 {MIN_JWT_SECRET_BYTES}바이트는 하한이라 받아들여진다",
            JWT_MIN_SECRET,
            issue(JWT_MIN_SECRET, JWT_SUBJECT),
            live_at,
        ),
        (
            "secret-one-byte-short",
            f"{MIN_JWT_SECRET_BYTES - 1}바이트 시크릿은 기동 경로에서 설정 오류로 끊는다 "
            "(PyJWT는 경고만 하고 서명해 준다)",
            JWT_TOO_SHORT_SECRET,
            valid,
            live_at,
        ),
    ]
    cases = [
        _case(
            f"jwt-{name}",
            description,
            {"secret": secret, "token": token, "verify_at": verify_at},
            outcome(secret, token, verify_at),
        )
        for name, description, secret, token, verify_at in samples
    ]
    cases.append(
        _case(
            "jwt-roundtrip-request",
            "역방향 검증용 — Kotlin이 이 subject들로 토큰을 발급해 kotlin-issue.json 에 남기고 "
            "Python이 그것을 읽는다. 만료 동작까지 보이려면 "
            "expected_outcome=invalid_credentials 케이스를 함께 낸다",
            {
                "secret": JWT_SECRET,
                "expire_minutes": JWT_EXPIRE_MINUTES,
                "algorithm": _ALGORITHM,
                "token_type": _TOKEN_TYPE,
                "subjects": [JWT_SUBJECT, JWT_OTHER_SUBJECT],
            },
            {"outcome": "verified_externally"},
            verification=_external(
                command="verify-jwt",
                actual_file="kotlin-issue.json",
                required_cases=2,
                actual_schema={
                    "secret": "이 케이스의 secret을 그대로",
                    "token": "Kotlin이 발급한 토큰",
                    "verify_at": "이 토큰을 평가할 기준 시각(epoch 초, UTC)",
                    "expected_subject": "토큰에 담은 sub (ok 케이스만)",
                    "expected_outcome": "ok | invalid_credentials (생략 시 ok)",
                },
            ),
        )
    )
    return FixtureSpec(
        source="app/services/auth.py::AuthService._issue_token / AuthService.resolve_token",
        mode=MODE_COMPAT,
        requirement=(
            "롤백 창 호환성 — 한쪽이 발급한 액세스 토큰을 다른 쪽이 그대로 받아들여야 한다. "
            "값이 갈리면 절체 순간 로그인 세션이 전부 끊긴다. 서명 위조·알고리즘 혼동·만료 "
            "경계(PyJWT는 exp <= now를 만료로 본다)는 양쪽에서 똑같이 거부돼야 한다"
        ),
        normalization=NO_NORMALIZATION,
        cases=cases,
    )


# ---------------------------------------------------------------------------- Argon2

#: 예전 파라미터로 만들어진 해시를 흉내 낸다. 현재 `_HASHER`(t=3, m=65536, p=4)보다
#: 모든 비용이 낮아 `check_needs_rehash`가 True를 내야 한다.
ARGON2_LEGACY_TIME_COST = 2
ARGON2_LEGACY_MEMORY_COST = 8192
ARGON2_LEGACY_PARALLELISM = 2


def build_argon2() -> FixtureSpec:
    """Argon2 PHC — 기존 해시 그대로 검증, 재해시 판정, 변조·오입력 거부"""
    return asyncio.run(_argon2_cases())


async def _argon2_cases() -> FixtureSpec:
    from argon2 import PasswordHasher, extract_parameters
    from argon2.exceptions import InvalidHashError

    from app.services.auth import _HASHER, MIN_PASSWORD_LENGTH, hash_password, verify_password

    async def outcome(phc: str, password: str) -> dict[str, Any]:
        """실제 검증 경로(`verify_password`)와 재해시 판정(`check_needs_rehash`)의 결과.

        Argon2 해시는 솔트가 매번 새로 생성되므로 **출력 문자열을 비교할 수 없다.**
        그래서 fixture는 언제나 "주어진 PHC 문자열을 검증하는" 방향으로만 짠다.
        """
        try:
            needs_rehash: bool | None = _HASHER.check_needs_rehash(phc)
        except InvalidHashError:
            # PHC 형식이 깨져 파라미터를 읽을 수 없다 — 참/거짓 어느 쪽도 아니다.
            needs_rehash = None
        return {
            "verified": await verify_password(phc, password),
            "needs_rehash": needs_rehash,
            # 파일을 읽는 과정에서 비밀번호가 NFC/NFD로 접히면 해시가 통째로 달라진다.
            # 양쪽이 같은 바이트를 넣었는지 여기서 먼저 드러난다.
            "password_utf8_sha256": hashlib.sha256(password.encode("utf-8")).hexdigest(),
        }

    hangul = "비밀번호-가나다-1234"
    passwords: list[tuple[str, str, str]] = [
        ("ascii", "correct-horse-battery-staple", "ASCII 비밀번호"),
        ("hangul", hangul, "한글 비밀번호 — UTF-8 바이트가 그대로 해싱된다"),
        (
            "long",
            "가나다라마바사" * 40,
            "긴 비밀번호 — argon2는 입력 길이에 비용이 좌우되지 않는다",
        ),
        (
            "empty",
            "",
            f"빈 비밀번호 — 해셔 자체는 막지 않는다. 길이 하한({MIN_PASSWORD_LENGTH}자)은 "
            "가입 경로(AuthService.signup)의 정책이지 해시 계층의 동작이 아니다",
        ),
        (
            "symbols",
            "p@ss\twith\nnewline & 공백 ",
            "제어문자·공백이 섞인 비밀번호도 그대로 해싱된다",
        ),
    ]

    cases: list[Case] = []
    for name, password, description in passwords:
        phc = await hash_password(password)
        cases.append(
            _case(
                f"argon2-verify-{name}",
                f"{description} — Python이 만든 PHC를 Kotlin이 그대로 검증한다",
                {"phc": phc, "password": password},
                await outcome(phc, password),
            )
        )

    ascii_phc = await hash_password("correct-horse-battery-staple")
    cases.append(
        _case(
            "argon2-wrong-password",
            "틀린 비밀번호는 거부된다",
            {"phc": ascii_phc, "password": "correct-horse-battery-stapl"},
            await outcome(ascii_phc, "correct-horse-battery-stapl"),
        )
    )
    hangul_phc = await hash_password(hangul)
    hangul_nfd = unicodedata.normalize("NFD", hangul)
    cases.append(
        _case(
            "argon2-hangul-nfd-mismatch",
            "NFC로 해싱한 비밀번호는 NFD 표기로 검증되지 않는다 — 이 도메인에 nfc 정규화를 "
            "걸면 안 되는 이유다. 정규화가 이 케이스를 통과시키면 검증이 은폐로 바뀐다",
            {"phc": hangul_phc, "password": hangul_nfd},
            await outcome(hangul_phc, hangul_nfd),
        )
    )
    # 해시 본문 마지막 글자만 바꾼다 — 파라미터 구획은 멀쩡하므로 재해시 판정은 살아 있고
    # 검증만 실패해야 한다.
    flipped = "A" if ascii_phc[-1] != "A" else "B"
    tampered_phc = ascii_phc[:-1] + flipped
    cases.append(
        _case(
            "argon2-tampered-phc",
            "변조된 PHC 문자열은 거부된다 (파라미터 구획은 온전하므로 재해시 판정은 살아 있다)",
            {"phc": tampered_phc, "password": "correct-horse-battery-staple"},
            await outcome(tampered_phc, "correct-horse-battery-staple"),
        )
    )
    cases.append(
        _case(
            "argon2-invalid-phc",
            "PHC 형식이 아닌 문자열 — 검증 실패이고 재해시 판정은 불가(null)다. "
            "예외를 밖으로 흘리면 '불일치'와 '해시가 깨짐'이 응답으로 구분돼 새어 나간다",
            {"phc": "not-a-phc-string", "password": "correct-horse-battery-staple"},
            await outcome("not-a-phc-string", "correct-horse-battery-staple"),
        )
    )

    legacy_hasher = PasswordHasher(
        time_cost=ARGON2_LEGACY_TIME_COST,
        memory_cost=ARGON2_LEGACY_MEMORY_COST,
        parallelism=ARGON2_LEGACY_PARALLELISM,
    )
    legacy_password = "legacy-parameter-password"
    legacy_phc = legacy_hasher.hash(legacy_password)
    cases.append(
        _case(
            "argon2-legacy-verify",
            "낮은 파라미터로 만든 기존 해시도 그대로 검증된다 — 이것이 깨지면 기존 사용자가 "
            "전부 로그인하지 못한다. 재해시는 **로그인 성공 시에만** 하고(_rehash_if_outdated), "
            "실패해도 로그인은 계속 진행한다(best-effort)",
            {"phc": legacy_phc, "password": legacy_password},
            await outcome(legacy_phc, legacy_password),
        )
    )
    cases.append(
        _case(
            "argon2-legacy-wrong-password",
            "낮은 파라미터 해시라도 틀린 비밀번호는 거부된다 (재해시 판정은 그대로 True)",
            {"phc": legacy_phc, "password": "wrong-password"},
            await outcome(legacy_phc, "wrong-password"),
        )
    )

    def parameters(phc: str) -> dict[str, Any]:
        params = extract_parameters(phc)
        return {
            "type": params.type.name,
            "version": params.version,
            "time_cost": params.time_cost,
            "memory_cost": params.memory_cost,
            "parallelism": params.parallelism,
            "salt_len": params.salt_len,
            "hash_len": params.hash_len,
            "phc_prefix": "$".join(phc.split("$")[:4]) + "$",
        }

    cases.append(
        _case(
            "argon2-phc-parameters",
            "PHC 문자열 파싱 — 형식·버전·비용 파라미터를 같은 값으로 읽어야 한다",
            {"phc": ascii_phc},
            parameters(ascii_phc),
        )
    )
    cases.append(
        _case(
            "argon2-legacy-phc-parameters",
            "임의 파라미터의 PHC도 읽을 수 있어야 한다 (현재 설정값을 가정해 파싱하면 안 된다)",
            {"phc": legacy_phc},
            parameters(legacy_phc),
        )
    )
    cases.append(
        _case(
            "argon2-current-parameters",
            "양쪽 해셔의 설정값 — 라이브러리 기본값에 기대지 않고 명시 고정한 값이다. "
            "여기가 갈리면 로그인마다 불필요한 재해시가 돌거나 반대로 이관이 멈춘다",
            {},
            {
                "time_cost": _HASHER.time_cost,
                "memory_cost": _HASHER.memory_cost,
                "parallelism": _HASHER.parallelism,
            },
        )
    )
    return FixtureSpec(
        source=(
            "app/services/auth.py::hash_password / verify_password / "
            "AuthService._rehash_if_outdated(_HASHER.check_needs_rehash)"
        ),
        mode=MODE_COMPAT,
        requirement=(
            "롤백 창 호환성 — Python이 저장한 Argon2 PHC 문자열을 Kotlin이 그대로 검증해야 "
            "한다. 값이 갈리면 기존 사용자가 전부 로그인하지 못한다. 낮은 파라미터로 만든 "
            "기존 해시도 검증되고, 재해시 판정이 양쪽에서 같아야 한다"
        ),
        normalization=NO_NORMALIZATION,
        cases=cases,
    )


BUILDERS: dict[str, Builder] = {
    "masking": build_masking,
    "text": build_text,
    "style": build_style,
    "style-tables": build_style_tables,
    "prompts": build_prompts,
    "postprocess": build_postprocess,
    "repair-adoption": build_repair_adoption,
    "export": build_export,
    "crypto": build_crypto,
    "jwt": build_jwt,
    "argon2": build_argon2,
}


def summary(builder: Builder) -> str:
    """빌더 docstring 첫 줄 (없으면 빈 문자열)."""
    doc = (builder.__doc__ or "").strip()
    return doc.splitlines()[0] if doc else ""


def dump(domains: list[str], out_root: Path) -> int:
    written = 0
    for domain in domains:
        spec = BUILDERS[domain]()
        payload = spec.document(domain)
        # `generated_at`만 비결정적이다. 정본 대조에서 제외되는 유일한 헤더 필드이므로
        # 여기서만 넣는다 — `document()`는 결정적으로 유지한다.
        payload["generated_at"] = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
        target = out_root / domain / f"{domain}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
            encoding="utf-8",
        )
        shown = target.relative_to(REPO_ROOT) if target.is_relative_to(REPO_ROOT) else target
        mark = spec.mode if spec.mode == MODE_COMPAT else f"{spec.mode}/{spec.spec_status}"
        print(f"[생성] {shown} — {len(spec.cases)}건 [{mark}] (source: {spec.source})")
        written += 1
    return 0 if written else 1


# ------------------------------------------------------- 역방향 검증 (Kotlin → Python)
#
# 신뢰 경계 — 이 절이 지키는 것과 지키지 못하는 것:
#   지킨다: "Kotlin 산출물 파일의 내용이 Python 구현으로 실제로 해독·검증된다."
#           검증은 매 실행마다 새로 돈다. 결과를 파일로 받아 믿지 않는다.
#   지키지 못한다: "그 산출물을 정말 Kotlin이 만들었는가." fixture가 키·시크릿을 공개하므로
#           같은 값을 Python으로도 만들 수 있다. 이 경계는 문서로만 막힌다(SKILL.md 참고).


@dataclass(frozen=True)
class VerificationOutcome:
    """역방향 검증 1회의 결과. 파일에 쓰기 전의 순수 값이다.

    `checked`는 **요구 사항을 만족한 채로 통과한 건수**이지 산출물의 케이스 수가 아니다.
    예전 구현은 입력 케이스 개수를 그대로 `checked`로 적어, 같은 값을 복사해 넣은 산출물이
    표본 수를 채울 수 있었다.
    """

    checked: int
    required: int
    failures: list[str]
    bound: bool

    @property
    def passed(self) -> bool:
        return not self.failures and self.checked >= self.required


Verifier = Callable[[dict[str, Any], dict[str, Any] | None], VerificationOutcome]


def _case_key(case: Any, index: int, seen: set[str]) -> tuple[str, str | None]:
    """산출물 케이스의 id를 꺼내며 형식·중복을 검사한다 (중복은 표본 부풀리기 경로다)."""
    if not isinstance(case, dict):
        return (f"#{index}", f"#{index}: 케이스가 객체가 아니다")
    case_id = str(case.get("id") or f"#{index}")
    if case_id in seen:
        return (case_id, f"{case_id}: 같은 id가 두 번 — 중복으로 표본 수를 채울 수 없다")
    seen.add(case_id)
    return (case_id, None)


def _requested(request: dict[str, Any] | None, field: str) -> list[str]:
    values = (request or {}).get(field)
    return [str(item) for item in values] if isinstance(values, list) else []


def _coverage_failure(wanted: list[str], covered: set[str], label: str) -> list[str]:
    """요청한 값 중 검증되지 않은 것을 **번호로만** 보고한다 (평문·subject 노출 금지)."""
    missing = [str(index) for index, value in enumerate(wanted) if value not in covered]
    if not missing:
        return []
    return [
        f"요청 {label} 미검증 {len(missing)}건 (index {', '.join(missing)}) — "
        f"fixture가 요청한 {label} 전부를 Kotlin 산출물이 덮어야 한다"
    ]


def run_verify_crypto(
    actual_doc: dict[str, Any], request: dict[str, Any] | None
) -> VerificationOutcome:
    """Kotlin이 만든 Fernet 토큰을 Python이 실제로 복호화한다.

    `request`는 fixture 요청 케이스(`crypto-roundtrip-request`)의 `input`이다. 이것을 주면
    검증이 **요청과 결합**된다 — 요청한 키를 썼는지, 요청한 평문을 하나도 빠짐없이 덮었는지까지
    본다. 건수만 세면 같은 평문 하나를 다섯 번 복사해 표본 수를 채울 수 있다.
    """
    from app.privacy.crypto import TextCipher

    cases = actual_doc.get("cases")
    bound = request is not None
    if not isinstance(cases, list):
        return VerificationOutcome(0, 1, ["cases 배열이 없다 — 검증할 산출물이 없다"], bound)
    wanted_key = str(request["key"]) if request and "key" in request else ""
    wanted = _requested(request, "plaintexts")
    failures: list[str] = []
    covered: set[str] = set()
    seen: set[str] = set()
    checked = 0
    for index, case in enumerate(cases):
        case_id, defect = _case_key(case, index, seen)
        if defect:
            failures.append(defect)
            continue
        key = case.get("key")
        token = case.get("token")
        expected = case.get("expected_plaintext")
        if not (isinstance(key, str) and isinstance(token, str) and isinstance(expected, str)):
            failures.append(
                f"{case_id}: 입력 결함 — key·token·expected_plaintext 가 모두 문자열이어야 한다"
            )
            continue
        if wanted_key and key != wanted_key:
            failures.append(f"{case_id}: fixture가 지정한 키가 아니다 — 요청과 무관한 토큰이다")
            continue
        if wanted and expected not in wanted:
            failures.append(f"{case_id}: fixture가 요청하지 않은 평문이다")
            continue
        try:
            got = TextCipher(key).decrypt(token.encode("ascii"))
        except Exception as exc:  # noqa: BLE001 - 복호화 실패는 사유를 가리지 않고 불일치다
            failures.append(f"{case_id}: 복호화 실패 ({type(exc).__name__})")
            continue
        if got != expected:
            failures.append(f"{case_id}: 평문 불일치 (길이 기대 {len(expected)} / 실제 {len(got)})")
            continue
        checked += 1
        covered.add(expected)
    failures += _coverage_failure(wanted, covered, "평문")
    return VerificationOutcome(checked, len(wanted) or 1, failures, bound)


def run_verify_jwt(
    actual_doc: dict[str, Any], request: dict[str, Any] | None
) -> VerificationOutcome:
    """Kotlin이 발급한 토큰을 Python이 실제로 검증한다.

    산출물 케이스: {"id", "secret", "token", "verify_at", "expected_subject", "expected_outcome"}
    `verify_at`(epoch 초)을 반드시 넣는다 — 벽시계로 판정하면 같은 산출물이 실행 시각에 따라
    통과와 실패를 오간다. `request`를 주면 fixture가 지정한 시크릿과 subject 집합에 결합한다.
    """
    from jwt import api_jwt

    from app.exceptions import ConfigurationError, InvalidCredentialsError
    from app.services.auth import AuthService, UserStore, WorkspaceCreator

    cases = actual_doc.get("cases")
    bound = request is not None
    if not isinstance(cases, list):
        return VerificationOutcome(0, 1, ["cases 배열이 없다 — 검증할 산출물이 없다"], bound)
    wanted_secret = str(request["secret"]) if request and "secret" in request else ""
    wanted = _requested(request, "subjects")
    failures: list[str] = []
    covered: set[str] = set()
    seen: set[str] = set()
    checked = 0
    for index, case in enumerate(cases):
        case_id, defect = _case_key(case, index, seen)
        if defect:
            failures.append(defect)
            continue
        secret = case.get("secret")
        token = case.get("token")
        if not (isinstance(secret, str) and isinstance(token, str)):
            failures.append(f"{case_id}: 입력 결함 — secret·token 이 모두 문자열이어야 한다")
            continue
        if wanted_secret and secret != wanted_secret:
            failures.append(f"{case_id}: fixture가 지정한 시크릿이 아니다")
            continue
        try:
            moment = datetime.fromtimestamp(int(case["verify_at"]), UTC)
        except (KeyError, TypeError, ValueError, OSError, OverflowError):
            failures.append(
                f"{case_id}: verify_at 누락·형식 오류 — 기준 시각 없이는 만료 판정이 무의미하다"
            )
            continue
        expected_outcome = str(case.get("expected_outcome", "ok"))
        try:
            service = AuthService(
                cast(UserStore, _UNUSED_STORE),
                cast(WorkspaceCreator, _UNUSED_STORE),
                secret,
                JWT_EXPIRE_MINUTES,
            )
        except ConfigurationError:
            failures.append(f"{case_id}: 시크릿이 최소 길이 미달")
            continue
        with _frozen(api_jwt, moment):
            try:
                subject: str | None = str(service.resolve_token(token))
            except InvalidCredentialsError:
                subject = None
        if subject is None:
            if expected_outcome != "invalid_credentials":
                failures.append(f"{case_id}: 검증 실패 (InvalidCredentialsError)")
                continue
            checked += 1
            continue
        if expected_outcome == "invalid_credentials":
            failures.append(f"{case_id}: 거부돼야 할 토큰이 통과했다")
            continue
        if subject != case.get("expected_subject"):
            # 토큰·sub 값을 메시지에 담지 않는다 — 토큰 자체가 자격증명이다.
            failures.append(f"{case_id}: sub 불일치")
            continue
        checked += 1
        covered.add(subject)
    failures += _coverage_failure(wanted, covered, "subject")
    return VerificationOutcome(checked, len(wanted) or 1, failures, bound)


VERIFIERS: dict[str, Verifier] = {
    "verify-crypto": run_verify_crypto,
    "verify-jwt": run_verify_jwt,
}

#: 이 스크립트 자신. 기록 파일에 해시를 남겨 "어느 버전의 검증기가 돌았는가"를 고정한다.
VERIFIER_SOURCE = Path(__file__).resolve()


def sha256_of(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return "unavailable"


def write_proof_record(
    *,
    command: str,
    actual_path: Path,
    fixture_path: Path | None,
    proof_path: Path | None,
    outcome: VerificationOutcome,
    produced_by: str,
    actual_runtime: Any = None,
) -> Path:
    """역방향 검증을 실제로 돌렸다는 **기록**을 남긴다.

    이 파일은 판정의 **입력이 아니라 출력**이다. 예전에는 `compare_parity.py`가 이 파일을
    읽어 역방향 케이스를 닫았고, 그래서 손으로 6줄 적으면 게이트가 열렸다(교차 리뷰 X-1).
    지금 비교기는 이 파일을 읽지 않고 검증기를 직접 돌린 뒤 결과를 여기에 **덮어쓴다** —
    손으로 적어 둔 내용은 그 순간 사라진다.

    입력과 결합할 수 있는 값(fixture·산출물·검증기의 SHA-256, 실행 nonce, 결합 여부)을 함께
    적는다. 사람이 이 기록을 볼 때 "무엇을 근거로 통과했는가"가 파일 안에서 닫히게 하기 위해서다.
    실패했을 때도 남긴다 — 실패를 침묵으로 두면 미검증과 구분되지 않는다.
    failures에는 케이스 id와 사유 분류만 담는다(평문·토큰·키 금지).
    """
    target = proof_path or actual_path.parent / PROOF_FILE_NAMES[command]
    target.parent.mkdir(parents=True, exist_ok=True)
    record: dict[str, Any] = {
        "script": f"dump_parity_fixtures.py {command}",
        "command": command,
        "fixture_case": PROOF_FIXTURE_CASES[command],
        "status": "pass" if outcome.passed else "fail",
        "checked": outcome.checked,
        "required": outcome.required,
        "bound_to_fixture": outcome.bound,
        "fixture": str(fixture_path) if fixture_path is not None else None,
        "fixture_sha256": sha256_of(fixture_path) if fixture_path is not None else None,
        "actual": str(actual_path),
        "actual_sha256": sha256_of(actual_path),
        "actual_runtime": actual_runtime,
        "verifier": VERIFIER_SOURCE.name,
        "verifier_sha256": sha256_of(VERIFIER_SOURCE),
        "run_nonce": uuid.uuid4().hex,
        "verified_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "produced_by": produced_by,
        "failures": outcome.failures,
        "note": "이 파일은 실행 기록이다. 게이트 판정의 입력이 아니다 — compare_parity.py 는 "
        "이것을 읽지 않고 검증기를 직접 돌린다",
    }
    target.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return target


def load_request(command: str, fixture_path: Path | None) -> dict[str, Any] | None:
    """fixture에서 이 검증기가 닫는 요청 케이스의 `input`을 읽는다."""
    if fixture_path is None:
        return None
    try:
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(
            f"[중단] {fixture_path} 를 읽을 수 없습니다: {type(exc).__name__}"
        ) from None
    wanted = PROOF_FIXTURE_CASES[command]
    for case in fixture.get("cases", []) if isinstance(fixture, dict) else []:
        if isinstance(case, dict) and case.get("id") == wanted:
            payload = case.get("input")
            if not isinstance(payload, dict):
                raise SystemExit(f"[중단] {fixture_path} 의 `{wanted}` 케이스에 input 이 없습니다")
            return payload
    raise SystemExit(f"[중단] {fixture_path} 에 `{wanted}` 케이스가 없습니다")


def run_verification(
    command: str, actual_path: Path, fixture_path: Path | None, proof_path: Path | None
) -> int:
    """CLI 진입점. 게이트 판정은 이 명령이 아니라 `compare_parity.py`가 내린다."""
    try:
        actual_doc = json.loads(actual_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(
            f"[중단] {actual_path} 를 읽을 수 없습니다: {type(exc).__name__}"
        ) from None
    if not isinstance(actual_doc, dict):
        raise SystemExit(f"[중단] {actual_path} 의 최상위가 JSON 객체가 아닙니다")
    request = load_request(command, fixture_path)
    if request is None:
        print(
            "[경고] --fixture 를 주지 않았다 — 요청(키·평문·subject)과 결합하지 않고 "
            "산출물 자체만 검증한다. 게이트 판정은 compare_parity.py 가 fixture와 결합해 "
            "다시 돌린 결과로만 한다"
        )
    outcome = VERIFIERS[command](actual_doc, request)
    target = write_proof_record(
        command=command,
        actual_path=actual_path,
        fixture_path=fixture_path,
        proof_path=proof_path,
        outcome=outcome,
        produced_by=f"dump_parity_fixtures.py {command}",
        actual_runtime=actual_doc.get("runtime"),
    )
    if not outcome.passed:
        print(f"역방향 검증 실패 ({command}):")
        for failure in outcome.failures:
            print(f"  - {failure}")
        if not outcome.failures:
            print(f"  - 표본 부족: {outcome.required}건이 필요한데 {outcome.checked}건만 통과했다")
        print(f"[기록] {target} (status: fail)")
        return 1
    print(f"역방향 검증 통과 ({command}): {outcome.checked}건")
    print(f"[기록] {target} (status: pass)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "command", nargs="?", default="dump", choices=["dump", "verify-crypto", "verify-jwt"]
    )
    parser.add_argument(
        "--domain", action="append", default=[], help="생성할 도메인 (반복 가능, 기본 전체)"
    )
    parser.add_argument(
        "--out", type=Path, default=DEFAULT_OUT, help=f"출력 루트 (기본 {DEFAULT_OUT})"
    )
    parser.add_argument("--actual", type=Path, help="verify-crypto / verify-jwt 입력 JSON")
    parser.add_argument(
        "--fixture",
        type=Path,
        help=(
            "요청 케이스를 읽을 fixture JSON. 주면 검증이 요청(키·평문·subject)과 결합된다. "
            "생략하면 산출물 자체만 본다"
        ),
    )
    parser.add_argument(
        "--proof",
        type=Path,
        help="실행 기록 파일 경로 (기본: --actual 과 같은 디렉터리의 verify-*.verified.json)",
    )
    parser.add_argument("--list", action="store_true", help="도메인 목록만 출력")
    args = parser.parse_args()

    if args.list:
        for name, builder in BUILDERS.items():
            print(f"{name:16} {summary(builder)}")
        return 0
    if args.command in VERIFIERS:
        if args.actual is None:
            parser.error(f"{args.command}에는 --actual 이 필요합니다")
        return run_verification(args.command, args.actual, args.fixture, args.proof)

    unknown = [d for d in args.domain if d not in BUILDERS]
    if unknown:
        parser.error(f"알 수 없는 도메인: {', '.join(unknown)} (가능: {', '.join(BUILDERS)})")
    return dump(args.domain or list(BUILDERS), args.out)


if __name__ == "__main__":
    raise SystemExit(main())
