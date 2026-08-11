#!/usr/bin/env python3
"""parity fixture(기대값)와 Kotlin 실행 결과(실제값)를 정규화 후 비교한다.

"Kotlin에 함수가 있다"는 동등성이 아니다. 같은 입력에 같은 출력이 나오는지를
값으로 증명하는 것이 이 스크립트의 유일한 목적이다.

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

역방향 케이스(`verification.mode == "external"`, 예: Fernet·JWT를 **Kotlin이 만들고
Python이 읽는** 방향)는 값 비교로 닫지 않는다. Kotlin이 기대값을 그대로 되받아 적으면
아무것도 실행하지 않고 "일치"가 나오기 때문이다. 이런 케이스는 `dump_parity_fixtures.py
verify-crypto` / `verify-jwt`가 남긴 **실행 증거 파일**만 근거로 인정하고, 증거가 없으면
통과가 아니라 **미검증(pending)** 으로 센다.

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
    0 = 전건 일치 + 미검증 0건 + 기대 도메인 전부 존재 (게이트를 닫아도 되는 유일한 상태)
    1 = 불일치·누락·읽기 실패(금지된 정규화 규칙 포함)
        **도메인 누락도 1이다.** 근거: 이미 "Kotlin 결과 파일 없음"(파일 누락)과
        "미실행"(케이스 누락)이 1로 나간다. 같은 성격의 누락을 입도가 커졌다는 이유로
        (케이스 → 파일 → 도메인) 더 약한 코드로 내보내면 "많이 지울수록 종료 코드가 약해지는"
        유인이 생긴다 — 그것이 정확히 이 게이트를 무력화하는 경로다.
    2 = 불일치는 없으나 미검증 케이스가 남음 — "돌렸다"이지 "증명됐다"가 아니다.
        2는 **fixture가 그 케이스를 정의했고 남은 것이 외부 실행 증거뿐인** 좁은 상태에만
        쓴다. 도메인이 통째로 없으면 정의 자체가 없으므로 2의 의미에 해당하지 않는다.
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
    사용법 오류(인자 누락·알 수 없는 도메인)도 1이다. argparse 기본값 2를 쓰면 "인자를
    잘못 줬다"와 "미검증이 남았다"가 같은 코드로 나가 호출자가 구분할 수 없다.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, NoReturn

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from dump_parity_fixtures import BUILDERS  # noqa: E402 — sys.path 주입 뒤에만 import된다

#: 기대 도메인 집합. **정본은 생성기의 BUILDERS 키 하나뿐이다.** 여기에 목록을 다시 적지
#: 않는다 — 두 벌이 되는 순간 도메인을 추가할 때 한쪽만 고쳐지고, 그 도메인은 검증되지
#: 않은 채 게이트를 통과한다.
EXPECTED_DOMAINS: tuple[str, ...] = tuple(BUILDERS)

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


def load(path: Path) -> dict[str, Any]:
    try:
        loaded: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"[중단] {path} 를 읽을 수 없습니다: {type(exc).__name__}") from None
    return loaded


def check_external(
    case_id: str, verification: dict[str, Any], actual_path: Path, ran_in_actual: bool
) -> tuple[str | None, str | None]:
    """역방향 케이스를 실행 증거 파일로 판정한다.

    반환: (문제, 미검증 사유) — 둘 중 하나만 채워지거나 둘 다 비어 있다(=검증됨).

    Kotlin 결과 파일에 이 케이스 id가 들어 있으면 그 자체를 문제로 본다. 그 자리에 기대값을
    베껴 넣는 것이 정확히 이 게이트를 무력화하는 경로이고, 값 비교로는 "실제로 암호화·발급을
    했는지"를 알 수 없기 때문이다.
    """
    script = verification.get("script", "dump_parity_fixtures.py verify-*")
    proof_name = verification.get("proof")
    if not proof_name:
        return (f"- `{case_id}` **fixture 결함** — verification.proof 가 없다", None)
    proof_path = actual_path.parent / str(proof_name)
    if ran_in_actual:
        return (
            f"- `{case_id}` **역방향 케이스를 Kotlin 결과로 닫으려 했다** — 이 케이스는 값 비교 "
            f"대상이 아니다. `{script}` 를 실제로 돌려 `{proof_path}` 를 남겨라",
            None,
        )
    if not proof_path.exists():
        return (
            None,
            f"- `{case_id}` **미검증** — 실행 증거 없음: {proof_path}\n"
            f"  - 닫는 방법: Kotlin 산출물을 만든 뒤 `uv run python "
            f".claude/skills/python-kotlin-parity/scripts/{script} --actual <kotlin 결과.json>`\n"
            f"  - 이 케이스가 남아 있는 한 역방향(Kotlin → Python) 호환성은 미증명이다",
        )
    try:
        proof: dict[str, Any] = json.loads(proof_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return (f"- `{case_id}` **증거 파일 읽기 실패** {proof_path} ({type(exc).__name__})", None)
    if proof.get("fixture_case") != case_id:
        return (
            f"- `{case_id}` **증거 파일이 다른 케이스의 것** — fixture_case="
            f"{proof.get('fixture_case')!r} ({proof_path})",
            None,
        )
    if proof.get("status") != "pass":
        failures = "; ".join(str(item) for item in proof.get("failures", [])) or "사유 미기록"
        return (f"- `{case_id}` **역방향 검증 실패** — {failures} ({proof_path})", None)
    required = int(verification.get("required_cases", 1))
    if int(proof.get("checked", 0)) < required:
        return (
            f"- `{case_id}` **역방향 검증 표본 부족** — {required}건이 필요한데 "
            f"{proof.get('checked')}건만 검증했다 ({proof_path})",
            None,
        )
    return (None, None)


@dataclass(frozen=True)
class Pair:
    """비교할 fixture 한 벌 (fixture는 이미 읽어 둔다 — 도메인 판정에 필요하다)."""

    fixture_path: Path
    actual_path: Path
    domain: str
    fixture: dict[str, Any]

    @property
    def case_ids(self) -> list[str]:
        cases = self.fixture.get("cases")
        if not isinstance(cases, list):
            return []
        return [str(case.get("id")) for case in cases if isinstance(case, dict) and case.get("id")]


@dataclass
class FileResult:
    problems: list[str] = field(default_factory=list)
    pendings: list[str] = field(default_factory=list)
    checked: int = 0
    external_ok: int = 0
    #: `--only` 필터를 통과해 실제로 판정 대상이 된 케이스 수. 0이면 아무것도 검증하지 않은 것이다.
    considered: int = 0


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
    return problems


def compare_file(pair: Pair, only: str | None = None) -> FileResult:
    fixture = pair.fixture
    actual_doc = load(pair.actual_path)
    file_rules = list(fixture.get("normalization", []))

    result = FileResult()
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
    for case in fixture_cases:
        case_id = case["id"]
        if only is not None and case_id != only:
            continue
        result.considered += 1
        verification = case.get("verification") or {}
        if verification.get("mode") == "external":
            problem, pending = check_external(
                case_id, verification, pair.actual_path, case_id in actual_cases
            )
            if problem:
                result.problems.append(problem)
            elif pending:
                result.pendings.append(pending)
            else:
                result.external_ok += 1
            continue
        active, tolerance = _rules(list(case.get("normalization", file_rules)))
        if case_id not in actual_cases:
            result.problems.append(f"- `{case_id}` **미실행** — Kotlin 결과에 이 케이스가 없다")
            continue
        result.checked += 1
        expected = normalize(case["expected"], active)
        got = normalize(actual_cases[case_id], active)
        if sorted(placeholders_of(expected)) != sorted(placeholders_of(case["expected"])):
            result.problems.append(
                f"- `{case_id}` **정규화 오류** — 정규화가 자리표시자를 바꿨다. 규칙을 고쳐라"
            )
            continue
        if not equal(expected, got, tolerance):
            result.problems.append(
                f"- `{case_id}` 불일치\n"
                f"  - 최초 차이: {first_difference(expected, got, tolerance)}\n"
                f"  - 입력: `{json.dumps(case['input'], ensure_ascii=False)[:200]}`\n"
                f"  - 정규화: {', '.join(sorted(active)) or '없음'}\n"
                f"  - 재현: `uv run python .claude/skills/python-kotlin-parity/scripts/"
                f"compare_parity.py --fixture {pair.fixture_path} "
                f"--actual {pair.actual_path} --only {case_id}`\n"
                f"  - source: {case.get('source', fixture.get('source', '?'))}"
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
        pairs.append(Pair(fixture_path, actual_path, domain, fixture))
    return pairs


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
    total_external = 0
    total_considered = 0
    for pair in pairs:
        problems = structural_problems(pair, check_location=directory_mode)
        if not pair.actual_path.exists():
            problems.append(f"- **Kotlin 결과 파일 없음**: {pair.actual_path}")
        else:
            result = compare_file(pair, args.only)
            problems += result.problems
            total_checked += result.checked
            total_external += result.external_ok
            total_considered += result.considered
            total_pending += len(result.pendings)
            if result.pendings:
                pending_sections.append(
                    f"## {pair.domain} · {pair.fixture_path.name}\n\n" + "\n".join(result.pendings)
                )
            if not problems and not result.pendings:
                print(f"[일치] {pair.domain} · {pair.fixture_path.name} — {result.checked}건")
        if problems:
            total_problems += len(problems)
            sections.append(
                f"## {pair.domain} · {pair.fixture_path.name}\n\n" + "\n".join(problems)
            )

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
                "\n\n> 미검증은 통과가 아니다. 이 목록이 비지 않으면 역방향(Kotlin → Python) "
                "호환성이 증명되지 않은 것이므로 게이트를 닫지 않는다.\n"
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
        f"도메인 {covered}/{len(expected_domains)} / 값 비교 {total_checked}건 / "
        f"외부 검증 {total_external}건 / 미검증 {total_pending}건 / "
        f"불일치 {total_problems}건 / 도메인 누락 {len(missing)}개 / 파일 {len(pairs)}개"
    )
    if missing and not total_problems:
        print(f"[도메인 누락] {summary} — 없는 도메인: {', '.join(missing)} (종료 코드 1)")
        return 1
    if total_problems:
        detail = f" — 없는 도메인: {', '.join(missing)}" if missing else ""
        print(f"[불일치] {summary}{detail}")
        return 1
    if total_pending:
        print(f"[미검증] {summary} — 전건 일치로 보고하지 않는다 (종료 코드 2)")
        return 2
    if total_considered == 0:
        print(f"[검증 없음] {summary} — 비교한 케이스가 0건이다. 통과로 보고하지 않는다")
        return 1
    if partial:
        # 0이 아니라 3이다. 자동화가 읽는 계약은 위에 찍은 "게이트 아님" 문구가 아니라
        # 종료 코드 하나뿐이므로, 여기서 0을 돌려주면 10개 도메인을 건너뛴 실행이
        # 전체 통과로 기록된다 (모듈 docstring "종료 코드" 절 참고).
        print(f"부분 검증 통과(게이트 아님): {summary}")
        return EXIT_PARTIAL_OK
    print(f"전건 일치: {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
