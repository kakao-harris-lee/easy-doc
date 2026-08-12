#!/usr/bin/env python3
"""parity fixture 생성기 — 요구 성질(spec)을 굳힌다.

**기준은 Python 출력이 아니다.** Python 구현은 회귀가 잦아 Kotlin으로 옮기는 중이며,
사용자 결정(2026-08-12)은 "출력 결과를 Python과 동일하게 맞출 필요는 없다. 요구사항을
구현하고 이후에 개선한다"이다. 그래서 이 생성기가 만드는 fixture는 한 종류뿐이다.

    mode="spec"   판정 근거가 **요구사항이 요구하는 성질**이다. 케이스마다 `assert`
                  목록이 들어가고, 비교기가 Kotlin 산출물에 그 성질을 실행해 판정한다.
                  Python 실행 결과는 `reference`(참고값)로 함께 담기지만 **판정에 쓰지
                  않는다** — 다른 자리는 `참고 갈림 원장`에 기록될 뿐이다.

spec 도메인이라도 성질을 아직 적지 못했으면 `spec_status="pending"`으로 선언한다.
그 도메인은 "통과"가 아니라 **미검증**으로 집계된다(비교기 종료 코드 2). 성질을 적지
않은 채 값 비교로 때우면 폐기된 전제로 되돌아가는 것이므로 그 경로를 두지 않는다.

실행:
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
        --domain masking --domain style
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --list

입력 문자열은 전부 합성(synthetic)이다. 실제 사용자 문서·실제 개인정보를 fixture에
넣지 않는다 — fixture는 저장소에 커밋되어 영구히 남는다.

── 2026-08-12 재개발 전환으로 **없어진 것** (다시 넣기 전에 이 문단을 읽어라) ─────────
여기에는 `mode="compat"`(값 동일성이 곧 요구사항)과 `crypto`·`jwt`·`argon2` 도메인,
그리고 역방향 검증(`verify-crypto`/`verify-jwt`, `VERIFIERS`, `*.verified.json` 기록)이
있었다. 셋 다 **하나의 전제** 위에 서 있었다 — "절체 후 롤백 창에서 Python이 Kotlin의
암호문·토큰·해시를 읽어야 한다". 사용자 결정(2026-08-12)으로 Python 런타임을 폐기하고
롤백을 포기하면서 그 전제가 사라졌고(계획 `docs/plans/2026-08-11-kotlin-react-migration.md`
2026-08-12 2차 변경 이력 §4.3), 저장 암호화는 표준 AEAD를 처음부터 쓴다.

왜 도메인만 빼지 않고 mode까지 지웠나: `compat`의 기대값은 **다른 런타임이 낸 값**에서
권위를 얻는 방식이다. 그 다른 런타임이 없어졌으므로 어떤 도메인도 compat의 권위를
공급할 수 없다. 반면 "요구사항이 못박은 값과 같아야 한다"는 spec 모드의 `equals_field`가
이미 표현한다. 남겨 두면 `mode: compat` 한 줄로 `assert` 의무와 방향 가드를 건너뛰는
경로만 남는다 — 쓰는 곳 없이 우회로만 남는 셈이라 뺐다.

**암호 검증이 없어진 것이 아니다.** round-trip·변조 거부·다른 키 거부·키 회전은 여전히
요구사항이고, 받는 곳이 이 하네스에서 **Kotlin 자체 테스트 + `migration-safety-gate`
I-7/I-8/I-9 감사**로 옮겨졌다. 이 하네스로는 그것을 판정할 수 없다 — 암호문은 불투명해서
비교기가 열어 볼 수 없고, "변조를 거부했다"는 Kotlin 하네스의 자기 신고라 증거가 아니다.
독립 검증이 필요하면 Phase 4에서 AEAD 방식을 고정한 뒤 **표준 AEAD를 독립 구현으로
복호화하는** 검증기를 새로 짜는 것이 옳고, 그때 이 문단을 근거로 설계를 다시 판단하라.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

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
        import yaml
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


#: 판정 방식. 지금은 하나뿐이다 — 값 동일성으로 판정하던 `compat`은 2026-08-12에 지웠다
#: (모듈 docstring "없어진 것" 참고). 상수를 남겨 두는 이유는 fixture 헤더의 `mode` 필드가
#: 계속 그 값을 선언하고, 비교기가 **정본에서** 그것을 읽어 판정 방식을 정하기 때문이다.
MODE_SPEC = "spec"  # 요구 성질로 판정한다 (Python 출력은 참고값)

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
        if self.mode != MODE_SPEC:
            raise ValueError(f"알 수 없는 mode: {self.mode} (지금은 {MODE_SPEC} 하나뿐이다)")
        if self.spec_status not in (STATUS_READY, STATUS_PENDING):
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
            "spec_status": self.spec_status,
        }
        header["cases"] = [self._render(case) for case in self.cases]
        return header

    def _render(self, case: Case) -> Case:
        """Python 실행 결과의 이름을 `reference`로 바꾼다.

        이름이 곧 계약이다. `expected`라고 부르는 순간 "Python이 낸 값에 맞춰라"라는
        읽기가 되살아난다. 이 하네스에서 그 값의 지위는 **참고**뿐이다.
        """
        if "expected" not in case:
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


BUILDERS: dict[str, Builder] = {
    "masking": build_masking,
    "text": build_text,
    "style": build_style,
    "style-tables": build_style_tables,
    "prompts": build_prompts,
    "postprocess": build_postprocess,
    "repair-adoption": build_repair_adoption,
    "export": build_export,
}
# `crypto`·`jwt`·`argon2` 가 여기 없는 것은 빠뜨린 것이 아니라 2026-08-12에 **뺀** 것이다.
# 근거와 그 보장이 어디로 갔는지는 모듈 docstring "없어진 것" 문단에 있다. 되살리기 전에
# 읽어라 — 이 세 도메인의 판정은 이 하네스가 아니라 Kotlin 자체 테스트와
# `migration-safety-gate` I-7/I-8/I-9 감사가 맡는다.


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
        mark = f"{spec.mode}/{spec.spec_status}"
        print(f"[생성] {shown} — {len(spec.cases)}건 [{mark}] (source: {spec.source})")
        written += 1
    return 0 if written else 1


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--domain", action="append", default=[], help="생성할 도메인 (반복 가능, 기본 전체)"
    )
    parser.add_argument(
        "--out", type=Path, default=DEFAULT_OUT, help=f"출력 루트 (기본 {DEFAULT_OUT})"
    )
    parser.add_argument("--list", action="store_true", help="도메인 목록만 출력")
    args = parser.parse_args()

    if args.list:
        for name, builder in BUILDERS.items():
            print(f"{name:16} {summary(builder)}")
        return 0

    unknown = [d for d in args.domain if d not in BUILDERS]
    if unknown:
        parser.error(f"알 수 없는 도메인: {', '.join(unknown)} (가능: {', '.join(BUILDERS)})")
    return dump(args.domain or list(BUILDERS), args.out)


if __name__ == "__main__":
    raise SystemExit(main())
