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
    # 성별코드 한 자리만 다른 표기인 경우. 결함의 **위치**를 정확히 짚는다 — 앞 6자리만
    # 전각인 표기는 수정 전에도 잡혔고(끊기는 자리를 지나가지 않는다) 성별코드가 전각이면
    # 끊겼다. 두 케이스가 함께 있어야 "어디가 ASCII 전용이었나"가 fixture로 드러난다.
    rrn_fullwidth_gender = "900101-" + fullwidth[1] + "234567"
    rrn_arabic_gender = "900101-" + arabic_indic[5] + "234567"
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

    # ── 구분자 표기 변형 축 (2026-08-13) ──────────────────────────────────────
    # `07_privacy-gate_masking-verdicts.md` §1.2가 **종류 B**로 이름 붙인 결함이다.
    # 결함의 구조: 유니코드 인식 패턴 안에 남은 **ASCII 전용 구분자 리터럴**. 숫자 자리를
    # 유니코드로 열어도 구분자가 ASCII 하이픈·공백뿐이면 전각 하이픈 하나에 매치가 끊긴다.
    # 판정서 §1.1 실측이 RRN·CARD **양쪽**에서 뚫림을 확인했고, 이 fixture에는 그때까지
    # 구분자 변형 케이스가 **0건**이었다(판정서 §1.6이 지목).
    #
    # 왜 문자마다 케이스를 두는가: 이 결함은 **열거 집합의 누락**이라 한 문자씩 뚫린다.
    # 대표 한 문자만 두면 나머지 여덟 자리는 여전히 아무도 보지 않는다. RRN·CARD를 같은
    # 집합으로 대칭 배치하는 이유도 같다 — 판정서 §1.4가 "집합은 상수 하나로 두고 RRN·CARD가
    # 함께 참조한다"를 요구했는데, 그 요구를 **밖에서** 검증할 수 있는 유일한 방법이
    # 양쪽에 같은 문자 집합을 걸어 보는 것이다. 한쪽만 덮으면 집합이 두 벌로 갈라져
    # 한쪽만 늘어난 구현을 통과시킨다.
    #
    # **개행·캐리지리턴은 이 집합에 없다.** 넣으면 서로 다른 줄의 숫자열이 붙어 진짜 과잉
    # 마스킹이 된다(판정서 §1.4 — `\s`를 쓰지 말라는 지시). 그 반대 방향은 아래
    # `keeps-*-split-digits` 가드가 본다.
    separators = (
        ("fullwidth-hyphen", 0xFF0D, "전각 하이픈 U+FF0D — 한글 입력 환경에서 그대로 나온다"),
        ("minus", 0x2212, "수학 마이너스 U+2212 — 표 편집기가 하이픈 대신 넣는다"),
        ("en-dash", 0x2013, "엔 대시 U+2013 — 워드프로세서 자동 교정이 만든다"),
        ("em-dash", 0x2014, "엠 대시 U+2014"),
        ("hyphen", 0x2010, "유니코드 하이픈 U+2010 — ASCII 하이픈과 눈으로 구별되지 않는다"),
        ("nbsp", 0x00A0, "줄바꿈 없는 공백 U+00A0 — 웹페이지 복사·붙여넣기에 흔하다"),
        ("ideographic-space", 0x3000, "전각 공백 U+3000 — 한글 문서에서 자리 맞춤에 쓰인다"),
        ("figure-space", 0x2007, "숫자 폭 공백 U+2007 — 표에서 자릿수를 맞출 때 쓰인다"),
        ("narrow-nbsp", 0x202F, "좁은 줄바꿈 없는 공백 U+202F"),
    )

    #: 성별코드 값 판정의 **과잉 마스킹 가드**. 판정서 §1.4는 성별코드를 `\d`로 넓히되
    #: 매치 후 값이 1~8인지 보라고 했다. 값 판정으로 바꾸면 9·0 거부가 자동으로 성립하지만,
    #: 단언 없이 두면 다음 회차에 되돌아간다 — 그때 증상은 접수번호·관리번호가 통째로
    #: 가려지는 것이고, 사용자는 성공 응답을 받고 팩트가 사라진 결과를 받는다.
    gender_rejects = (
        ("gender-9", "900101-9234567", "성별코드 9"),
        ("gender-0", "900101-0234567", "성별코드 0"),
        ("gender-fullwidth-9", restyle("900101-9234567", fullwidth), "전각 성별코드 ９"),
        ("gender-fullwidth-0", restyle("900101-0234567", fullwidth), "전각 성별코드 ０"),
    )

    # ── 구분자 문법 `SEP` 동결 (2026-08-14, privacy-gate 판정 6 §4-ter) ────────
    # 앞선 확장(§구분자 표기 변형 축)은 구분자 **문자 집합**만 넓히고 **반복 상한**을
    # 지정하지 않았다. 그 누락이 반대 방향 결함을 만들었다(리뷰 08 Y-2 → C-10) —
    # NBSP·전각 공백이 열 맞춤에 쓰이는 hwpx·pdf 추출본에서 인접 칸의 접수번호 6자리와
    # 관리번호 7자리가 **하나의 주민등록번호로 결합**돼 두 팩트가 동시에 사라진다.
    #
    # 판정문이 준 문법 하나가 누락(C-01②)과 과잉(C-10)을 **동시에** 닫는다:
    #
    #     SEP := (?: SPACE? HYPHEN SPACE? | SPACE? )        최대 3문자. RRN·CARD 공유.
    #
    # 가르는 기준은 **문자 종류가 아니라 개수**다 — 자리당 공백 0~1개는 자릿수 그룹을
    # 가르는 구분자, 2개 이상은 열 맞춤(정렬)이라 두 값 사이의 여백이다. NBSP·전각 공백을
    # 집합에서 빼는 방식은 채택되지 않았다(그 문자로 적힌 **진짜** 주민등록번호를 다시
    # 놓친다). 아래 탐침 6·7·8이 그 경계를 값으로 고정한다.
    #
    # **fixture 표기 주의**: 판정문 §4-ter.4 조건 5는 이 방향을 *"자리당 공백 2개 이상은
    # `absent`, 0~1개는 `present`"*로 요약했는데, 이 하네스의 검사 이름 기준으로는 **반대**다
    # (`absent` = 그 문자열이 `masked_text`에 남지 않았다 = 가려졌다). 정본은 §4-ter.2의
    # 12탐침 표이고 그 표의 `기대` 열을 따랐다 — 2개 이상 = **안 가림** = `present`.
    # 명세 문서 §9.2에 이 불일치를 기록해 `privacy-gate`에 올린다.
    space, idsp, nbsp = chars(0x0020), chars(0x3000), chars(0x00A0)
    #: 보충 평면 십진 숫자. `\d`가 인정하는 십진 숫자 310개가 전부 서로게이트 쌍이라,
    #: 캡처를 UTF-16 `Char`로 후검증하는 가드는 그 전부를 거부한다(C-01① — 차단 ①사건).
    bold_digits = tuple(chars(0x1D7CE + n) for n in range(10))

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
        # ── 구분자 문법 `SEP` — 정당한 표기 쪽 (판정 6 §4-ter.2 탐침 2·5) ──────
        case(
            "rrn-space-one",
            f"번호 900101{space}1234567 확인.",
            f"하이픈 없이 공백 **한 개**로만 갈린 표기도 {hidden}. 자리당 공백 1개는 "
            "자릿수 그룹 구분이다 — 2개 이상(정렬)과 갈리는 경계가 여기서 시작한다",
            _assert("absent", path="masked_text", needles=[f"900101{space}1234567"]),
        ),
        case(
            "rrn-nbsp-around-hyphen",
            f"번호 900101{nbsp}-{nbsp}1234567 확인.",
            f"하이픈 양옆에 NBSP가 **한 개씩** 붙은 표기도 {hidden}. `SEP`이 허용하는 "
            "최대 형태(공백1+하이픈+공백1, 3문자)이고 hwpx 추출본에서 실제로 나온다",
            _assert("absent", path="masked_text", needles=[f"900101{nbsp}-{nbsp}1234567"]),
            reference_divergence="expected",
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
        # ── 종류 A: 성별코드가 ASCII가 아닌 표기 ──────────────────────────────
        # 2026-08-13 `known_gap` 해제. 옛 케이스 `known-gap-rrn-fullwidth`가 여기로 왔다.
        # 그 케이스는 어느 방향도 단언하지 않았고, 제외 사유가 *"Kotlin에 Python보다 넓은
        # 구현을 요구하게 되므로"*였다. 그 사유는 **2026-08-12 재개발 전환으로 실효**했다 —
        # CLAUDE.md가 "Python 출력을 정답으로 삼기"를 금지 목록에 올렸고, 기준은 요구사항이다.
        # `privacy-gate` 판정(`07_privacy-gate_masking-verdicts.md` §1.3)이 "전각 표기도
        # 범주 주민등록번호에 **포함된다**"로 닫았다: 범주를 좁힌 것은 2종 축소이고
        # (전화·이메일·계좌), **표기 체계를 넓히는 것은 범주를 넓히는 것이 아니다.**
        # 전각으로 적힌 고유식별정보는 여전히 고유식별정보다.
        #
        # 이 세 케이스는 **Python이 못 잡는다**(성별코드 `[1-8]`이 ASCII 리터럴). 그래서
        # 참고 갈림이 뜨는 것이 정상이고 `reference_divergence`로 선언한다 — 선언은 면제가
        # 아니라 검사 추가다. 원장 기록 의무는 그대로이고, Python이 고쳐져 갈림이 사라지면
        # 선언이 낡은 것이므로 게이트가 막는다.
        case(
            "rrn-unicode-digit-fullwidth",
            f"번호 {rrn_fullwidth} 확인.",
            f"전각 숫자로만 적은 주민등록번호도 {hidden}. 성별코드를 ASCII 리터럴 `[1-8]`로 "
            "두면 그 한 자리에서 매치가 끊긴다 — 값이 1~8인지 **십진값으로** 판정하면 "
            "전각뿐 아니라 모든 유니코드 십진 숫자 체계가 한 번에 덮인다",
            _assert("absent", path="masked_text", needles=[rrn_fullwidth]),
            reference_divergence="expected",
        ),
        case(
            "rrn-unicode-digit-gender",
            f"번호 {rrn_fullwidth_gender} 확인.",
            f"성별코드 **한 자리만** 전각이어도 {hidden}. 결함의 위치를 짚는 케이스다 — "
            "앞 6자리만 전각인 표기(`rrn-unicode-digit-head`)는 끊기는 자리를 지나가지 "
            "않아 전부터 잡혔다. 두 케이스의 차이가 곧 ASCII 전용 리터럴의 좌표다",
            _assert("absent", path="masked_text", needles=[rrn_fullwidth_gender]),
            reference_divergence="expected",
        ),
        case(
            "rrn-arabic-digit-gender",
            f"번호 {rrn_arabic_gender} 확인.",
            f"아라비아-인도 숫자 성별코드(외국인등록번호대)도 {hidden}. 전각만 열거해 "
            "고치면 여기서 다시 뚫린다 — 열거가 아니라 **십진값 판정**이어야 하는 이유다",
            _assert("absent", path="masked_text", needles=[rrn_arabic_gender]),
            reference_divergence="expected",
        ),
        # 보충 평면 숫자 (판정 6 §4-ter.1 — **차단 ①사건**). 결함은 커버리지가 아니라
        # **정합성**이다: 패턴은 코드포인트로 세고 그것을 지키는 판정 함수는 UTF-16 `Char`로
        # 센다. `\d`가 인정하는 십진 숫자 중 보충 평면 310개가 전부 서로게이트 쌍이라
        # `Char` 하나인지 보는 가드가 그 **전건을 거부**했다 — 정규식은 잡았는데 가드가
        # 되돌려 마스킹이 통째로 빠진다. 그래서 이 케이스가 고정하는 것은 "U+1D7CF를
        # 잡아라"가 아니라 **"판정 함수의 계수 단위가 패턴과 같은가"**다.
        case(
            "rrn-supplementary-digit-gender",
            f"번호 900101-{bold_digits[1]}234567 확인.",
            f"성별코드가 **보충 평면** 십진 숫자(서로게이트 쌍)여도 {hidden}. 캡처를 "
            "`Char` 하나로 후검증하면 보충 평면 십진 숫자 310개가 전건 거부되어 "
            "**정규식이 잡은 것을 가드가 되돌린다**",
            _assert(
                "absent",
                path="masked_text",
                needles=[f"900101-{bold_digits[1]}234567"],
            ),
            reference_divergence="expected",
        ),
        case(
            "keeps-rrn-gender-supplementary-9",
            f"번호 900101-{bold_digits[9]}234567 을 적으세요.",
            f"보충 평면으로 적힌 성별코드 **9**는 주민등록번호가 아니다. {kept} — "
            "계수 단위를 코드포인트로 고치면 수용이 늘어나는데, 그 확대가 **값 판정을 "
            "건너뛰는 방향으로** 새면 여기서 걸린다. 양성 케이스와 짝이다",
            _assert(
                "present",
                path="masked_text",
                needles=[f"900101-{bold_digits[9]}234567"],
            ),
        ),
        # ── 종류 B: 구분자가 ASCII 하이픈·공백이 아닌 표기 (RRN) ──────────────
        *[
            case(
                f"rrn-sep-{name}",
                f"번호 900101{chars(code)}1234567 확인.",
                f"주민등록번호 구분자가 {note}여도 {hidden}. 구분자를 `-`·공백·탭만 "
                "열거하면 여기서 끊긴다 — 사람 눈에는 하이픈으로 보이므로 담당자는 "
                "가려졌다고 믿는다",
                _assert(
                    "absent",
                    path="masked_text",
                    needles=[f"900101{chars(code)}1234567"],
                ),
                reference_divergence="expected",
            )
            for name, code, note in separators
        ],
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
        # ── 복합 구분자 (판정 6 §4-ter.2 탐침 9·10 — C-01② 누락분) ────────────
        # 구분자를 "한 문자"로만 두면 카드번호의 `1234 - 5678` 형태가 통째로 빠진다.
        # 같은 형태가 RRN에는 전부터 있었는데(`rrn-spaced`) 카드에는 없었다 — 두 패턴이
        # 구분자를 따로 적고 있었다는 증거이고, `SEP` 상수 공유가 닫는 자리다.
        case(
            "card-spaced-hyphen",
            f"카드 1234{space}-{space}5678{space}-{space}9012{space}-{space}3456 입력.",
            f"하이픈 양옆에 공백이 붙은 카드번호도 {hidden}. RRN에는 같은 형태가 전부터 "
            "있었는데(`masking-rrn-spaced`) 카드에는 없었다 — 구분자를 두 벌로 적으면 "
            "이렇게 한쪽만 좁아진다",
            _assert(
                "absent",
                path="masked_text",
                needles=[f"1234{space}-{space}5678{space}-{space}9012{space}-{space}3456"],
            ),
            reference_divergence="expected",
        ),
        case(
            "card-nbsp-around-hyphen",
            f"카드 1234{nbsp}-{nbsp}5678{nbsp}-{nbsp}9012{nbsp}-{nbsp}3456 입력.",
            f"NBSP가 하이픈 양옆에 붙은 카드번호도 {hidden}. 문자 변형(NBSP)과 복합 "
            "구분자(공백+하이픈+공백)가 **함께** 오는 형태이며, 둘 중 하나만 고친 구현은 "
            "여기서 걸린다",
            _assert(
                "absent",
                path="masked_text",
                needles=[f"1234{nbsp}-{nbsp}5678{nbsp}-{nbsp}9012{nbsp}-{nbsp}3456"],
            ),
            reference_divergence="expected",
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
        # ── 종류 B: 구분자 변형 (CARD) ────────────────────────────────────────
        # 카드번호에는 **종류 A가 없다** — 숫자 자리가 전부 `\d`라 전각·아라비아-인도가
        # 이미 잡힌다(위 두 케이스). 뚫려 있던 것은 구분자뿐이고, 두 리뷰 어디도 카드번호를
        # 보지 않아 `privacy-gate` 실측이 처음 찾은 자리다(판정서 §1.5, 교차 검증 미수).
        # RRN과 **같은 문자 집합**을 거는 것이 이 그룹의 요점이다 — 한쪽만 덮으면 구분자
        # 집합이 두 벌로 갈라져 한쪽만 늘어난 구현을 통과시킨다.
        *[
            case(
                f"card-sep-{name}",
                f"카드 1234{chars(code)}5678{chars(code)}9012{chars(code)}3456 입력.",
                f"카드번호 구분자가 {note}여도 {hidden}. RRN과 같은 집합이어야 한다 — "
                "두 패턴이 각자 구분자를 열거하면 다음 확장에서 한쪽만 늘어난다",
                _assert(
                    "absent",
                    path="masked_text",
                    needles=[
                        f"1234{chars(code)}5678{chars(code)}9012{chars(code)}3456",
                    ],
                ),
                reference_divergence="expected",
            )
            for name, code, note in separators
        ],
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
        # 성별코드 값 판정의 반대 방향. 종류 A를 "성별코드를 `\\d`로 넓힌다"까지만 하고 값
        # 판정을 빠뜨리면 여기가 전부 가려지고, 그 구현은 위 종류 A 케이스를 **전건 통과**한다.
        *[
            case(
                f"keeps-rrn-{name}",
                f"번호 {sample} 을 적으세요.",
                f"{note}는 주민등록번호가 아니다. {kept} — 성별코드를 `\\d`로 넓히면서 "
                "값이 1~8인지 보지 않으면 13자리 접수번호가 통째로 가려진다",
                _assert("present", path="masked_text", needles=[sample]),
            )
            for name, sample, note in gender_rejects
        ],
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
        # ── 탐색 뷰 접기의 **경계축** (판정 8 §4-septies.6) ────────────────────
        # 접기는 두 축에 다르게 작용한다. 이 구분이 이 절의 전부다.
        #
        #   분리축(`SEP` 개수 상한) — **흔들리지 않는다.** 개수가 재는 것은 **여백의 폭**이고
        #     폭 0인 문자는 몇 개가 와도 폭을 만들지 않는다(아래 교차 3건이 고정한다).
        #   경계축(lookaround) — **여기가 갈린다.** `(?<!\d)`·`(?!\d)`를 원문 경로는 "바로 옆
        #     문자"로, 뷰 경로는 "접은 뒤 옆 문자"로 본다. 합집합이라 **둘 중 하나만 성립해도
        #     가리므로**, 폭 0 문자 **한 개**가 "긴 숫자열의 일부"라는 거부 근거를 무효화한다.
        #
        # 여분은 열거로 확정됐다 — 1,120조합 중 합집합≠뷰전용이 90건이고 **90/90이 한 종류**,
        # 전부 같은 방향(합집합은 가리고 뷰 전용은 안 가린다)이다. 실문서 2,665,995자에서는
        # 결과가 7건으로 같아 이득도 비용도 측정되지 않았으나, 붙여넣기 경로
        # (`DocumentTextRequest`)는 추출기를 거치지 않아 웹 복사본의 U+200B가 그대로 들어온다
        # — "발생 0"을 근거로 방치하지 않고 여기서 못박는다.
        #
        # **양성과 음성 짝을 반드시 함께 둔다(판정서 요구).** 짝의 두 입력은 **폭 0 문자
        # 하나만 다르다.** 따로 두면 다음 사람이 음성 쪽만 보고 "경계 검사가 있다"고 읽어
        # 합집합을 지운다 — 그 순간 판정 8 §4-septies.5의 방향 선택이 조용히 뒤집힌다.
        *[
            entry
            for pos_id, pos_text, neg_id, neg_text, target, keep, note in (
                (
                    "boundary-fold-rrn-head",
                    f"번호 1{chars(0x200B)}900101-1234567 확인.",
                    "keeps-boundary-rrn-head",
                    "번호 1900101-1234567 확인.",
                    "900101-1234567",
                    "1900101-1234567",
                    "앞 경계 — 앞에 붙은 숫자와 주민등록번호 사이",
                ),
                (
                    "boundary-fold-rrn-tail",
                    f"번호 900101-1234567{chars(0x200B)}8 확인.",
                    "keeps-boundary-rrn-tail",
                    "번호 900101-12345678 확인.",
                    "900101-1234567",
                    "900101-12345678",
                    "뒤 경계 — 대칭 확인. 앞 경계만 보는 구현이 여기서 걸린다",
                ),
                (
                    "boundary-fold-card-head",
                    f"카드 1{chars(0x200B)}1234-5678-9012-3456 입력.",
                    "keeps-boundary-card-head",
                    "카드 11234-5678-9012-3456 입력.",
                    "1234-5678-9012-3456",
                    "11234-5678-9012-3456",
                    "카드 대칭 — 경계축도 RRN·CARD 양쪽에서 같아야 한다",
                ),
            )
            for entry in (
                case(
                    pos_id,
                    pos_text,
                    f"{note}에 **폭 0 문자 하나**가 끼면 가린다. {hidden} — 원문 경로에서는 "
                    "그 문자가 숫자가 아니라 경계가 성립하고, 합집합이라 한쪽만 성립해도 "
                    "가린다. **바로 아래 음성 짝과 폭 0 문자 하나만 다르다**",
                    _assert("absent", path="masked_text", needles=[target]),
                ),
                case(
                    neg_id,
                    neg_text,
                    f"{note}에 폭 0 문자가 **없으면** 그것은 더 긴 숫자열의 일부다. {kept} — "
                    "**바로 위 양성의 음성 짝이다.** 이 케이스만 보고 '경계 검사가 있다'고 "
                    "읽어 합집합을 지우면 위 양성이 조용히 뒤집힌다. 그래서 짝으로 둔다",
                    _assert("present", path="masked_text", needles=[keep]),
                ),
            )
        ],
        # 분리축 × 접기 교차 — K-1이 "접기가 개수 상한을 무너뜨린다"고 본 자리. 실측은 반대다.
        case(
            "fold-visible-gap-zero",
            f"번호 900101{chars(0x200B) * 5}1234567 확인.",
            f"폭 0 문자가 **다섯 개** 끼어도 가시 간격은 0칸이라 구분자다. {hidden} — "
            "개수 상한이 재는 것은 문자 수가 아니라 **여백의 폭**이다",
            _assert(
                "absent",
                path="masked_text",
                needles=[f"900101{chars(0x200B) * 5}1234567"],
            ),
        ),
        case(
            "fold-visible-gap-one",
            f"번호 900101{chars(0x200B)} {chars(0x200B)}1234567 확인.",
            f"폭 0 문자 사이에 공백 **한 칸**이면 가시 간격 1칸 — 구분자다. {hidden}",
            _assert(
                "absent",
                path="masked_text",
                needles=[f"900101{chars(0x200B)} {chars(0x200B)}1234567"],
            ),
        ),
        case(
            "keeps-fold-visible-gap-two",
            f"접수 900101 {chars(0x200B)} 1234567 끝.",
            f"폭 0 문자를 끼워도 **가시 간격이 2칸**이면 정렬이다. {kept} — 접기로 개수 "
            "상한을 우회할 수 없다는 것을 고정한다. 위 두 건과 함께 읽어야 '무엇을 세는가'가 "
            "드러난다",
            _assert(
                "present",
                path="masked_text",
                needles=[f"900101 {chars(0x200B)} 1234567"],
            ),
            reference_divergence="expected",
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
        # 구분자 집합을 넓히는 수정의 반대 방향 가드 2건. 위 `separators` 아홉 자를 넣으면서
        # `\s`로 뭉뚱그리면 개행·캐리지리턴이 딸려 들어오고, 그 순간 안내문 표에서 연달아
        # 적힌 접수번호와 관리번호가 하나로 붙는다. 이 셋(개행 RRN·복귀 RRN·개행 CARD)이
        # 그 구현을 막는 유일한 자리다.
        case(
            "keeps-cr-split-digits",
            "접수번호 900101\r1234567 을 적으세요.",
            f"캐리지리턴으로 갈린 두 숫자열도 하나의 주민등록번호가 아니다. {kept} — "
            "구분자 집합에 `\\s`를 쓰면 개행과 함께 여기도 뚫린다",
            _assert("present", path="masked_text", needles=["900101\r1234567"]),
        ),
        case(
            "keeps-card-newline-split-digits",
            "번호 1234\n5678\n9012\n3456 을 차례로 적으세요.",
            f"줄마다 적힌 네 숫자열은 하나의 카드번호가 아니다. {kept} — 구분자 집합을 "
            "RRN·CARD가 공유하므로 과잉 방향도 **양쪽에서** 봐야 한다",
            _assert("present", path="masked_text", needles=["1234\n5678\n9012\n3456"]),
        ),
        # ── 정렬 vs 구분 경계 (판정 6 §4-ter.2 탐침 6·7·8·12) ─────────────────
        # 구분자 문자 집합을 넓히면서 **반복 상한을 두지 않은** 결과가 이 자리다. 열 맞춤에
        # 쓰인 공백이 인접 칸의 두 숫자열을 하나로 잇는다. 안내문 표에서 접수번호 6자리와
        # 관리번호 7자리가 나란히 있으면 **두 팩트가 동시에 사라지고**, 과잉 마스킹은
        # 조용해서 아무도 실패로 보지 않는다(STY-03 절대 팩트축).
        #
        # 기준은 문자 종류가 아니라 **개수**다. `keeps-rrn-space-two`가 경계값이고,
        # 바로 위 `rrn-space-one`(가려야 한다)과 짝이다 — 둘을 함께 읽어야 경계가 보인다.
        case(
            "keeps-rrn-space-two",
            f"접수 900101{space * 2}1234567 끝.",
            f"자리당 공백이 **2개**면 구분이 아니라 정렬이다. {kept} — `rrn-space-one`과 "
            "짝을 이루는 **경계값**이다. 반복 상한이 없으면 여기서 두 숫자열이 결합된다",
            _assert("present", path="masked_text", needles=[f"900101{space * 2}1234567"]),
            reference_divergence="expected",
        ),
        case(
            "keeps-rrn-space-five",
            f"접수 900101{space * 5}1234567 끝.",
            f"열 맞춤용 공백 5개로 갈린 두 숫자열은 하나의 주민등록번호가 아니다. {kept}",
            _assert("present", path="masked_text", needles=[f"900101{space * 5}1234567"]),
            reference_divergence="expected",
        ),
        case(
            "keeps-rrn-ideographic-space-three",
            f"접수 900101{idsp * 3}1234567 끝.",
            f"전각 공백 3개로 갈린 두 숫자열도 마찬가지다. {kept} — 전각 공백은 hwpx·pdf "
            "추출본이 **열 정렬에 실제로 쓰는 문자**라 이 형태가 실문서에서 나온다",
            _assert("present", path="masked_text", needles=[f"900101{idsp * 3}1234567"]),
        ),
        case(
            "keeps-card-ideographic-space-three",
            f"표 1234{idsp * 3}5678{idsp * 3}9012{idsp * 3}3456 끝.",
            f"표의 네 칸에 나뉜 숫자열은 하나의 카드번호가 아니다. {kept} — 반복 상한을 "
            "RRN에만 두고 CARD에 빠뜨리면 여기서 걸린다",
            _assert(
                "present",
                path="masked_text",
                needles=[f"1234{idsp * 3}5678{idsp * 3}9012{idsp * 3}3456"],
            ),
        ),
        # ── TAB은 여백이 아니라 열 경계다 (판정 8 §4-septies.7 — M-06) ────────
        # 방향 전환이다. 옛 `masking-rrn-tab`은 TAB 구분자 표기를 **가려야 한다**고
        # 못박았고, 그것이 `SEP` 개수 상한과 어긋났다 — 상한은 **폭의 대리 지표**인데
        # (자리당 한 칸까지 구분, 둘 이상은 정렬) TAB은 다음 탭 스톱까지 밀어내는 문자라
        # **공백 2개 이상이 하는 일을 1개로 한다.** 대리 지표가 TAB에서만 성립하지 않는다.
        # §4-ter.1(패턴은 코드포인트·가드는 `Char`)·§4-ter.3(VT·FF 열거 누락)과 같은 종류의
        # **단위 불일치**이고, 새 기준을 만든 것이 아니라 §4-ter.2 표의 세 번째 행
        # (개행·CR·VT·FF = 종류로 가르는 행)에 빠져 있던 하나를 넣은 것이다.
        #
        # **이 전환은 누락 방향이고 그 대가를 여기서 명시한다** — `900101<TAB>-<TAB>1234567`은
        # 이제 가려지지 않는다. §4-ter가 개수 상한을 두면서 이미 감수한 누락과 같은 종류다
        # (`keeps-rrn-space-two`가 같은 자리다). TAB만 예외로 두면 **어느 조판 문자로 벌어졌는지에
        # 따라 결과가 갈리고**, 그것이 §4-ter가 닫은 "문자 종류로 갈린다"의 재발이다.
        case(
            "keeps-rrn-tab",
            "주민등록번호 900101\t-\t1234567 확인.",
            f"탭으로 벌어진 표기는 구분이 아니라 **열 정렬**이다. {kept} — "
            "`keeps-rrn-space-two`(공백 2칸)와 **같은 자리**이고, 둘의 결과가 같아야 "
            "조판 문자에 따라 판정이 갈리지 않는다. 2026-08-14 방향 전환(판정 8 §4-septies.7, "
            "리더 승인): 옛 `masking-rrn-tab`은 이 입력을 가려야 한다고 못박고 있었다",
            _assert("present", path="masked_text", needles=["900101\t-\t1234567"]),
            reference_divergence="expected",
        ),
        case(
            "keeps-rrn-tab-two-columns",
            "접수 900101\t1234567 끝.",
            f"탭 하나로 갈린 6자리·7자리는 **두 열**이지 하나의 주민등록번호가 아니다. {kept} — "
            "TAB을 여백으로 인정하면 표에서 접수번호와 관리번호가 하나로 붙는다",
            _assert("present", path="masked_text", needles=["900101\t1234567"]),
            reference_divergence="expected",
        ),
        case(
            "keeps-card-tab-four-columns",
            "표 1234\t5678\t9012\t3456 끝.",
            f"탭으로 갈린 네 칸은 카드번호가 아니다. {kept} — **표 4열이 통째로 카드번호가 "
            "되던 자리**이고, TAB이 구분자였을 때 실측으로 확인된 과잉이다",
            _assert("present", path="masked_text", needles=["1234\t5678\t9012\t3456"]),
        ),
        case(
            "keeps-amount-tab-four-columns",
            "금액 1200\t3400\t5600\t7800 원.",
            f"금액 4열도 같은 형태다. {kept} — 카드번호 표기와 자릿수가 같아서 구분자만으로는 "
            "갈리지 않는다. 열 경계를 구분자로 인정하지 않는 것이 유일한 방어다",
            _assert("present", path="masked_text", needles=["1200\t3400\t5600\t7800"]),
        ),
        # ── 줄·페이지 경계 4종 비결합 (판정 6 §4-ter.3 — C-11) ────────────────
        # 보이지 않는 문자를 접는 탐색 뷰에 VT(수직 탭)·FF(폼피드)가 들어 있었다. 둘은
        # LF·CR과 같은 **줄·페이지 경계**인데 C0 범위를 통짜로 열거하다 딸려 들어갔다.
        # 판정문이 "묶어서 한 케이스로 만들지 말라"고 못박았다 — LF·CR만 있던 탓에
        # 이 결함을 놓쳤으므로 네 문자를 **각각** 독립 케이스로 둔다.
        case(
            "keeps-vt-split-digits",
            f"접수번호 900101{chars(0x000B)}1234567 을 적으세요.",
            f"수직 탭 U+000B로 갈린 두 숫자열은 하나의 주민등록번호가 아니다. {kept} — "
            "VT는 줄 경계라 LF·CR과 같은 부류다. 탐색 뷰에서 접으면 두 줄이 붙는다",
            _assert(
                "present",
                path="masked_text",
                needles=[f"900101{chars(0x000B)}1234567"],
            ),
            reference_divergence="expected",
        ),
        case(
            "keeps-ff-split-digits",
            f"접수번호 900101{chars(0x000C)}1234567 을 적으세요.",
            f"폼피드 U+000C로 갈린 두 숫자열도 마찬가지다. {kept} — FF는 페이지 경계다. "
            "C0 범위를 통짜로 열거하면 줄 경계 문자가 조용히 딸려 들어온다",
            _assert(
                "present",
                path="masked_text",
                needles=[f"900101{chars(0x000C)}1234567"],
            ),
            reference_divergence="expected",
        ),
        # ── 감수한 과잉 표면 — 어느 방향도 단언하지 않는다 (판정 8 §4-septies.8) ─
        # 네 개의 4자리 숫자 그룹이 **한 칸 공백**으로 이어지면 카드번호로 가려진다. 연도·금액
        # 표가 그 형태다. **줄일 수 없는 모호성이다** — `1234 5678 9012 3456`이 카드번호의
        # 표준 표기라, 분리축에서 공백 한 칸을 빼면 진짜 누락이 된다. 두 요구가 같은 입력
        # 형태를 놓고 맞선다.
        #
        # `privacy-gate`가 **판정하지 않고** 관리 대상으로 남겼다(§4-septies.8). 가리는 쪽이
        # 정책 정합이라고 적었으나 — 누락은 조용하고 되돌릴 수 없는 반면 과잉은 검수 화면에
        # `original`과 나란히 보인다 — **교차 검증이 없다.** 두 리뷰 어디도 이 자리를 보지
        # 않았고 실측 1건이 근거의 전부다.
        #
        # 그래서 **어느 방향도 단언하지 않는다.** `absent`를 걸면 과잉을 요구사항으로 굳혀
        # 나중에 카드 패턴을 좁히는 개선이 회귀로 잡히고, `present`를 걸면 판정되지 않은
        # 방향을 이 레인이 대신 정하는 것이 된다. 존재와 참고값만 남겨 개선·악화가 원장에
        # 찍히게 한다.
        case(
            "deferred-four-groups-single-space",
            "연도 2021 2022 2023 2024 실적.",
            "네 개의 4자리 숫자가 한 칸 공백으로 이어지면 카드번호로 가려진다(연도 표). "
            "**줄일 수 없는 모호성** — `1234 5678 9012 3456`이 카드번호 표준 표기라 공백 "
            "한 칸을 빼면 진짜 누락이 된다. `privacy-gate`가 §4-septies.8에서 **판정하지 "
            "않고** 관리 대상으로 넘긴 자리이고 **교차 검증이 없다**(실측 1건). 어느 방향도 "
            "단언하지 않는다 — 개선·악화는 참고 갈림 원장에 찍혀 드러난다",
            verdict_pending={
                "reason": "한 칸 공백으로 이어진 4×4자리 숫자가 카드번호로 가려진다. "
                "`1234 5678 9012 3456`이 표준 표기라 분리축에서 공백 한 칸을 빼면 진짜 "
                "누락이 된다 — 줄일 수 없는 모호성이라 어느 방향도 단언하지 않는다",
                "owner": "privacy-gate",
                "deadline": "Phase 2 종료 전 재판정 (교차 검증 없는 단독 관측이라 리뷰 1회 필요)",
                "referred_by": "07_privacy-gate_masking-verdicts.md §4-septies.8",
            },
        ),
        case(
            "deferred-five-groups-single-space",
            "연도 2020 2021 2022 2023 2024 실적.",
            "같은 형태가 **다섯 열**이면 앞 네 개만 가려진다 — 후행 그룹 경계를 보지 않는다는 "
            '뜻이다. 판정서가 *"확정 시 함께 본다"*고 지목한 동작이며, 위 케이스와 같은 '
            "이유로 방향을 단언하지 않는다. 이 자리가 닫히면 두 케이스를 함께 전환한다",
            verdict_pending={
                "reason": "다섯 열이면 앞 네 개만 가려진다 — 후행 그룹 경계를 보지 않는다. "
                "위 케이스와 한 몸이라 함께 닫힌다",
                "owner": "privacy-gate",
                "deadline": "Phase 2 종료 전 재판정 (위 케이스와 동시)",
                "referred_by": "07_privacy-gate_masking-verdicts.md §4-septies.8",
            },
        ),
    ]
    return FixtureSpec(
        source="app/privacy/masking.py::mask_text",
        mode=MODE_SPEC,
        requirement=(
            "master-plan §3.2 마스킹 선행 + 사용자 결정(2026-08-12) 범위 축소 + "
            "privacy-gate 판정 (가)(02_privacy-gate_control-char-verdict.md) + "
            "privacy-gate 판정 §1(07_privacy-gate_masking-verdicts.md) — "
            "문서 본문이 LLM으로 나가기 전에 주민등록번호(외국인등록번호 포함)와 카드번호가 "
            "빠짐없이 가려지고(숫자 사이에 보이지 않는 문자가 끼어 있어도, 숫자·구분자가 "
            "ASCII가 아닌 표기로 적혀 있어도 가려진다), 그 밖의 본문은 한 글자도 잃지 "
            "않으며(줄이 갈린 숫자열·성별코드 9·0은 가리지 않는다), 자리표시자를 되돌리면 "
            f"원문이 정확히 복원된다. 범주 문자열의 정본은 계약({mask_contract().source})이다. "
            "**`assert`의 기준은 요구사항이고 참고값(Python)이 아니다** — 표기 변형 21건은 "
            "현행 Python이 못 잡아 참고값과 갈리며, 그 갈림은 차단 사유가 아니라 "
            '`reference_divergence: "expected"` 선언과 참고 갈림 원장에 남길 기록이다'
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
    from app.easyread import style_rules as sr
    from app.easyread.style_rules import (
        check_style,
        find_difficult_words,
        find_gloss_collisions,
        split_sentences,
    )

    # 판정하는 것과 하지 않는 것을 여기서 가른다.
    #   판정한다  — 길이·쉼표 두 규칙의 **건전성과 완전성**. 넘는 문장은 빠짐없이 잡히고
    #     넘지 않는 문장은 잡히지 않는다. `equals_derived`가 양방향을 한 번에 건다.
    #   판정하지 않는다 — **문장 분리 경계**. 휴리스틱이라 요구사항으로 적히지 않는다.
    #     그래서 비교기는 산출물이 **스스로 보고한** `sentences`를 입력으로 규칙만 다시
    #     적용한다(`repair_policy`가 위반 건수를 산출물에서 받는 것과 같은 구조다).
    #     두 질문을 섞으면 실패했을 때 분리가 틀린 건지 규칙이 틀린 건지 알 수 없다.
    def rule_violations(sentences: list[str], comma: bool) -> list[str]:
        if comma:
            return [s for s in sentences if sum(s.count(ch) for ch in sr._COMMA_CHARS) > 2]
        return [s for s in sentences if len(s) > 50]

    cases = []
    for name, text in _STYLE_SAMPLES:
        result = check_style(text)
        sentences = split_sentences(text)
        cases.append(
            _case(
                f"style-{name}",
                "길이 50자·쉼표 2개 규칙의 **건전성과 완전성** — 넘는 문장은 빠짐없이 "
                "보고되고 넘지 않는 문장은 보고되지 않는다. 문장 분리 경계는 판정하지 "
                "않는다(휴리스틱). 산출물이 보고한 문장에 규칙을 다시 적용해 대조한다",
                {"text": text},
                {
                    "sentences": sentences,
                    "difficult_words": find_difficult_words(text),
                    "gloss_collisions": find_gloss_collisions(text),
                    "check_style": {
                        "total_sentences": result.total_sentences,
                        "issues": [issue.model_dump() for issue in result.issues],
                    },
                    "length_violations": rule_violations(sentences, comma=False),
                    "comma_violations": rule_violations(sentences, comma=True),
                },
                **{
                    "assert": [
                        # 산출물이 보고한 문장에 규칙을 다시 적용한다 (양방향·정확 일치).
                        _assert(
                            "equals_derived", rule="style_length_rule", path="length_violations"
                        ),
                        _assert("equals_derived", rule="style_comma_rule", path="comma_violations"),
                        # **유도의 입력까지 독립으로**(X-4 리더 판정). 위 둘은 산출물이 보고한
                        # `sentences`를 입력으로 쓰므로, 생산자가 문장을 통째로 버리면 양쪽이
                        # 사이좋게 0이 되어 통과한다. 아래 둘은 **fixture 입력**에서 비교기가
                        # 직접 가른 "더 쪼갤 수 없는 구간"을 하한으로 요구하므로 그 경로가 막힌다.
                        _assert(
                            "contains_derived", rule="style_length_floor", path="length_violations"
                        ),
                        _assert(
                            "contains_derived", rule="style_comma_floor", path="comma_violations"
                        ),
                    ]
                },
            )
        )
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
    # 두 축을 다르게 판정한다.
    #   정책 상수 — **값이 같아야 한다**(`equals_field`). 프롬프트 문구와 채점 기준이 이
    #     숫자를 f-string 으로 박아 쓰므로, 갈리면 "지시한 것과 채점하는 것"이 어긋난다.
    #   큐레이션 표 — **누락만 막는다**(`contains_all`). 추가는 개선이라 허용한다. 값으로
    #     통째로 비교하면 사전에 한 항목을 더하는 순간 게이트가 빨개져 개선이 회귀가 된다.
    policy = [
        _assert("equals_field", path="MAX_SENTENCE_CHARS", value=sr.MAX_SENTENCE_CHARS),
        _assert("equals_field", path="MAX_COMMAS_PER_SENTENCE", value=sr.MAX_COMMAS_PER_SENTENCE),
    ]
    # C-4 — 표제어만 보면 뜻풀이를 통째로 바꿔도 통과한다. 사전은 **값이 곧 자산**이고
    # (246개 실측 큐레이션) 그 값이 프롬프트에 그대로 실려 모델에게 간다. 값 축을 따로 건다.
    curated = [
        _assert(
            "contains_entries",
            path="DIFFICULT_WORD_REPLACEMENTS",
            required=[
                [key, value] for key, value in sorted(sr.DIFFICULT_WORD_REPLACEMENTS.items())
            ],
        ),
    ] + [
        _assert("contains_all", path=key, required=required)
        for key, required in (
            ("DIFFICULT_WORD_REPLACEMENTS", sorted(sr.DIFFICULT_WORD_REPLACEMENTS)),
            ("STYLE_PRINCIPLES", list(sr.STYLE_PRINCIPLES)),
            ("DOUBLE_PASSIVE_PATTERNS", list(sr.DOUBLE_PASSIVE_PATTERNS)),
            ("PROMPT_ONLY_WORDS", sorted(sr.PROMPT_ONLY_WORDS)),
            ("COMPOUND_HEAD_NOUNS", sorted(sr.COMPOUND_HEAD_NOUNS)),
            ("LEXICALIZED_GLOSSES", sorted(sr.LEXICALIZED_GLOSSES)),
            ("COMPOUND_TAIL_KEYS", sorted(sr.COMPOUND_TAIL_KEYS)),
        )
    ]
    # **한 케이스다.** 처음에는 `counts` 와 `full` 로 나눠 두었는데 둘의 `input` 이 똑같이
    # 비어 있어(이 도메인은 상수 덤프라 입력이 없다) 같은 산출물을 두 번 재고 있었다 —
    # M-08 중복 검사가 실제로 그것을 잡았다. 케이스 수만 늘고 성질은 늘지 않는 자리였다.
    # 크기(`counts`)는 진단용으로 산출물에 함께 담되, 판정은 한 곳에서 한다.
    cases = [
        _case(
            "style-tables-snapshot",
            "정책 상수는 **값이 같아야 하고**(프롬프트가 이 숫자를 문구에 박아 쓰고 채점도 "
            "같은 숫자를 쓴다 — 갈리면 지시한 것과 채점하는 것이 달라진다), 큐레이션 표는 "
            "**표제어를 잃지 않아야 한다**(누락 금지·추가 허용). 표를 값으로 통째로 비교하면 "
            "사전에 한 항목을 더하는 순간 빨개져 **개선이 회귀로 잡힌다**. 오탐 경계인 "
            "`COMPOUND_HEAD_NOUNS`·`LEXICALIZED_GLOSSES` 는 사전 키에서 유도되지 않는 실측 "
            "산출물이라 특히 누락이 위험하다",
            {},
            {**tables, "counts": counts},
            **{"assert": [*policy, *curated]},
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
    )


# ---------------------------------------------------------------------------- 프롬프트


def build_prompts() -> FixtureSpec:
    """프롬프트 렌더링 — 시스템·사용자·보정 프롬프트 전문"""
    import re

    from app.easyread import style_rules as sr
    from app.easyread.prompts import (
        DOCUMENT_TAG_NAME,
        build_repair_prompt,
        build_system_prompt,
        build_user_prompt,
    )
    from app.easyread.style_rules import check_style, find_difficult_words

    placeholder_re = re.compile(r"\[\[[가-힣]+[0-9]+\]\]")
    # 난수 문서 id 를 **생성 시점에** 고정한다. 그대로 두면 fixture 가 매 덤프 달라져
    # 정본 대조(생성기 재실행 후 비교)가 **영구히 깨진다** — 재현성이 이 하네스의 전제다.
    # 비교기 쪽은 같은 자리를 `mask_document_id` 정규화로 덮으므로 판정은 영향받지 않는다.
    document_id_re = re.compile(r'id="[0-9a-f]{6,}"')

    def fix_document_id(text: str) -> str:
        return document_id_re.sub('id="<ID>"', text)

    masked_samples: list[tuple[str, str]] = [
        ("no-difficult-word", "신청은 9월에 합니다."),
        ("one-difficult-word", "감면을 받으려면 신청서를 제출하세요."),
        ("with-placeholder", "번호는 [[주민등록번호1]] 입니다. 감면 대상을 확인합니다."),
        (
            "many-difficult-words",
            "수급자는 부양의무자 기준을 충족해야 하며 소급 적용이 가능합니다.",
        ),
    ]
    # **문면이 한 글자까지 같아야 하는 도메인이 아니다**(§4.6이 개선을 허용한다). 판정하는
    # 것은 넷이다 — ① 스타일 원칙이 **전량** 실린다 ② 입력에 등장한 어려운 말이 **각각**
    # 뜻풀이와 함께 실린다(동적 필터링: 246개 전량이 아니라 등장한 것만) ③ 자리표시자가
    # 본문에 **그대로** 남는다 ④ 문서 경계 태그가 유지된다(prompt injection 방어).
    #
    # 프롬프트 **전문**의 정본은 이 fixture 가 아니라 `python-prompt-snapshot.json` 이다
    # (X-15 에서 선언한 경계와 같은 모양이다 — 두 장치가 다른 것을 본다). 여기서 전문을
    # 값으로 걸면 문안을 고치는 순간 두 곳이 함께 빨개지고, 그때 사람은 fixture 를 맞추려고
    # 문안을 되돌린다.
    cases: list[Case] = []
    for name, masked in masked_samples:
        issues = check_style(masked).issues
        repair_system, repair_user = build_repair_prompt(masked, issues)
        detected = find_difficult_words(masked)
        gloss_lines = [
            f"- {word} (뜻: {sr.DIFFICULT_WORD_REPLACEMENTS[word]})"
            for word in detected
            if word in sr.DIFFICULT_WORD_REPLACEMENTS
        ]
        placeholders = placeholder_re.findall(masked)
        asserts = [
            _assert("contains_all", path="system_prompt", required=list(sr.STYLE_PRINCIPLES)),
            _assert("contains_all", path="system_prompt", required=gloss_lines),
            _assert("present", path="user_prompt", needles=[masked] if masked else []),
            _assert(
                "present",
                path="user_prompt",
                needles=[f"<{DOCUMENT_TAG_NAME}", f"</{DOCUMENT_TAG_NAME}"],
            ),
        ]
        if placeholders:
            # 자리표시자가 프롬프트에서 깨지면 모델이 그대로 옮길 수 없고, 복원이 무너진다.
            asserts.append(_assert("present", path="user_prompt", needles=placeholders))
            asserts.append(_assert("present", path="repair_user_prompt", needles=placeholders))
        cases.append(
            _case(
                f"prompts-{name}",
                "스타일 원칙 전량 + 입력에 등장한 어려운 말 풀이 + 자리표시자 보존 + 문서 "
                "경계 태그. **문면 전문은 판정하지 않는다** — 그 정본은 프롬프트 스냅샷이고, "
                "여기서 전문을 걸면 문안 개선이 회귀로 잡힌다",
                {"masked_text": masked, "violations": [issue.model_dump() for issue in issues]},
                {
                    "system_prompt": build_system_prompt(masked),
                    "user_prompt": fix_document_id(build_user_prompt(masked)),
                    "repair_system_prompt": repair_system,
                    "repair_user_prompt": fix_document_id(repair_user),
                },
                **{"assert": asserts},
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
    # 이 도메인의 위험은 **비대칭**이다. 껍데기를 남기면 후처리 부하가 늘 뿐이지만, 본문을
    # 지우면 사용자는 **성공 응답을 받고 팩트가 사라진 결과**를 받는다. 그래서 케이스마다
    # "벗겨야 할 것"(`absent`)과 "남아야 할 것"(`present`)을 **함께** 건다 — 한쪽만 걸면
    # 전부 지우는 구현이나 아무것도 안 하는 구현이 통과한다.
    #
    # `preamble-without-body`·`preamble-lookalike` 둘이 과잉 제거 가드의 핵심이다. 앞은
    # 머리말 뒤에 본문이 없으면 **전부 날리지 않는다**를, 뒤는 '다음은'으로 시작해도 변환
    # 결과를 가리키는 어구가 없으면 **정상 본문**임을 고정한다.
    expectations: dict[str, tuple[list[str], list[str]]] = {
        # 이름: (벗겨져야 하는 조각, 남아야 하는 조각)
        "plain": ([], ["쉬운 글 본문입니다."]),
        "fence": (["```"], ["본문입니다."]),
        "fence-lang": (["```", "markdown"], ["본문입니다."]),
        "preamble": (["다음은 변환 결과입니다:"], ["본문입니다."]),
        "preamble-without-body": ([], ["다음은 변환 결과입니다:"]),
        "preamble-lookalike": ([], ["다음은 심사 결과입니다.", "본문입니다."]),
        "fence-then-preamble": (["```", "아래는 쉬운 글입니다:"], ["본문입니다."]),
        "only-whitespace": ([], []),
    }
    cases = []
    for name, raw in samples:
        stripped, kept_pieces = expectations[name]
        asserts = []
        if stripped:
            asserts.append(_assert("absent", path="text", needles=stripped))
        if kept_pieces:
            asserts.append(_assert("present", path="text", needles=kept_pieces))
        else:
            # 공백뿐인 응답은 빈 문자열이 된다. 빈 결과 판정은 변환 서비스가 하고
            # (`repair-adoption` 의 `conversion-empty-first-call-fails`), 여기서는
            # "무엇을 돌려주는가"만 못박는다.
            asserts.append(_assert("equals_field", path="text", value=""))
        cases.append(
            _case(
                f"postprocess-{name}",
                "껍데기만 벗기고 **본문은 한 글자도 잃지 않는다**. 과잉 제거가 과소 제거보다 "
                "위험하다 — 사용자는 성공 응답을 받고 본문 일부가 사라진 결과를 받는다",
                {"raw": raw},
                {"text": postprocess(raw)},
                **{"assert": asserts},
            )
        )
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
    )


# ------------------------------------------------- 변환 오케스트레이션 (보정 채택 포함)


def build_repair_adoption() -> FixtureSpec:
    """변환 오케스트레이션 — 호출 상한·4대 예외·보정 채택·자리표시자 유실 보고

    도메인 **이름은 `repair-adoption`이지만 범위는 변환 오케스트레이션 전체**다. 이름은
    `.github/parity-canonical-floor.txt`·`backend-kotlin/parity-domains.txt`가 참조하는
    키라 바꾸면 삭제+추가가 되므로 그대로 둔다(하한 파일 (3) 항). 범위의 정본은 이름이
    아니라 아래 `requirement` 한 줄이다.
    """
    from app.easyread.style_rules import check_style
    from app.services.conversion import MAX_LLM_CALLS_PER_CONVERSION, _accepts_repair

    # ── 1. 보정 채택 정책 (CNV-04) — 순수 판정 ────────────────────────────────
    # 이 도메인의 요구사항은 **정책**이라 규칙으로 완전히 적힌다:
    #   채택 = (자리표시자를 하나도 잃지 않았다) AND (위반 건수가 늘지 않았다)
    # 그래서 판정은 산출물이 **스스로 보고한 건수**를 입력으로 이 정책을 다시 계산해
    # 대조한다(`repair_policy`). 건수 자체가 맞는지는 `style` 도메인의 질문이고, 여기서는
    # "같은 건수를 받았을 때 같은 결정을 내리는가"만 본다 — 두 질문을 섞으면 어느 쪽이
    # 틀렸는지 알 수 없다.
    policy = (
        "보정 결과 채택 정책 — 자리표시자를 잃거나 위반이 늘면 거부하고, "
        "같은 건수는 채택한다(경계값)"
    )
    samples: list[tuple[str, str, str, list[str], str]] = [
        ("improves", "결과가 보여지고 있습니다.", "결과를 보여 드립니다.", [], policy),
        (
            "worsens",
            "감면을 받으세요.",
            "감면을 받으시고, 가, 나, 다, 라를 준비하세요.",
            [],
            policy,
        ),
        ("equal-count", "감면을 받으세요.", "제출을 하세요.", [], policy),
        (
            "loses-placeholder",
            "번호는 [[주민등록번호1]] 감면 대상입니다.",
            "번호를 확인해 주세요.",
            ["[[주민등록번호1]]"],
            policy,
        ),
        (
            "placeholder-absent-in-original",
            "감면 대상입니다.",
            "깎아 드립니다.",
            ["[[주민등록번호1]]"],
            policy,
        ),
        # ── 2026-08-13 확장 (추출 목록 §G 집행) ───────────────────────────────
        # 위 5건은 "잃음 vs 안 잃음"과 "건수 증가 vs 감소·동수"의 대각선만 짚어, 자리표시자
        # 가드를 **전부-아니면-전무**로 구현하거나 자리표시자가 있으면 보정을 통째로
        # 버리는 구현을 걸러내지 못했다. 아래 3건이 그 세 경로를 각각 막는다.
        (
            "partial-placeholder-loss",
            "금일 [[주민등록번호1]]과 [[카드번호1]]을 확인하세요.",
            "오늘 [[주민등록번호1]]을 확인하세요.",
            ["[[주민등록번호1]]", "[[카드번호1]]"],
            "여러 자리표시자 중 **하나만 잃어도** 거부한다 — 위반은 오히려 줄었지만 "
            "복원이 깨진 결과를 품질 개선으로 사들이지 않는다. 가드를 '전부 잃었을 때'로 "
            "구현하면 여기서 걸린다",
        ),
        (
            "keeps-placeholders-and-improves",
            "금일 [[주민등록번호1]]을 확인하세요.",
            "오늘 [[주민등록번호1]]을 확인하세요.",
            ["[[주민등록번호1]]"],
            "자리표시자를 모두 지키면서 위반이 줄면 채택한다. **과잉 거부 가드** — "
            "'자리표시자가 든 결과는 보정하지 않는다'로 구현하면 보정이 사실상 죽는데, "
            "유실 케이스만으로는 그 구현이 드러나지 않는다",
        ),
        (
            "placeholder-reordered",
            "금일 [[주민등록번호1]]과 [[카드번호1]]을 확인하세요.",
            "오늘 [[카드번호1]]과 [[주민등록번호1]]을 확인하세요.",
            ["[[주민등록번호1]]", "[[카드번호1]]"],
            "유실 판정은 **존재 여부**이지 위치가 아니다 — 문장을 다시 쓰면서 자리표시자 "
            "순서가 바뀌어도 잃은 것이 없으면 채택한다. 위치·인덱스로 대조하는 구현을 막는다",
        ),
    ]
    cases: list[Case] = [
        _case(
            f"repair-{name}",
            description,
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
        for name, original, candidate, placeholders, description in samples
    ]

    # ── 2. 변환 호출 상한 (CNV-01) — 런타임 동작 ──────────────────────────────
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

    # ── 3. 대본 있는 변환 시나리오 (CNV-01·CNV-02 + missing_placeholders) ──────
    # 위 두 케이스는 대본 없이 시나리오 이름만 주어 하네스가 문서를 알아서 짓게 한다.
    # 4대 예외와 자리표시자 유실 보고는 **어느 호출에서 무엇이 일어났는가**가 곧 성질이라
    # 그 자유도를 남기면 케이스가 무엇을 재는지 알 수 없다. 그래서 아래 케이스는
    # provider 응답을 대본으로 못박는다. 입력·산출물 형식은 명세 문서
    # `docs/migration/_workspace/02_parity-verifier_conversion-spec.md` §3 이 정본이다.
    #
    # 참고값을 싣지 않는 이유: 실패 경로의 결과는 Python이 **값이 아니라 예외**로 내므로
    # `reference`에 넣으려면 사람이 손으로 인코딩해야 하고, 그러면 그것은 참고값이 아니라
    # 두 번째 기대값이 된다. Python 현행 동작 실측은 명세 문서 §5에 표로 남긴다.
    src_plain = "금일 서류를 제출하십시오."
    src_rrn = "금일 등록번호 900101-1234567 을 확인하십시오."
    src_two = "금일 등록번호 900101-1234567 과 카드 4111-1111-1111-1111 을 확인하십시오."
    dirty = "금일 서류를 내세요."
    clean = "오늘 서류를 내세요."
    worse = "금일 서류를 제출하십시오."
    still_dirty = "명일 서류를 내세요."
    ph_dirty = "금일 [[주민등록번호1]]을 확인하세요."
    ph_clean = "오늘 [[주민등록번호1]]을 확인하세요."
    ph_lost = "오늘 번호를 확인하세요."
    ph_lost_dirty = "금일 번호를 확인하세요."
    ph2_partial = "오늘 [[주민등록번호1]]을 확인하세요."

    # 생성 시점 드리프트 가드. 아래 시나리오의 기대값(어느 쪽이 채택되는가·보정을 부르는가)은
    # 표본의 규칙 위반 건수에 달려 있는데, 그 건수는 `style_rules.py`가 바뀌면 함께 바뀐다.
    # 조용히 바뀌면 fixture는 "요구 성질"이라고 적힌 채 틀린 기대값을 굳힌다 — 여기서 깬다.
    intended_violations = {
        dirty: 1,
        clean: 0,
        worse: 2,
        still_dirty: 1,
        ph_dirty: 1,
        ph_clean: 0,
        ph_lost: 0,
        ph_lost_dirty: 1,
        ph2_partial: 0,
    }
    drifted = {
        text: (want, len(check_style(text).issues))
        for text, want in intended_violations.items()
        if len(check_style(text).issues) != want
    }
    if drifted:
        raise ValueError(
            "시나리오 표본의 규칙 위반 건수가 바뀌었다 — 스타일 규칙이 달라졌다는 뜻이므로 "
            "시나리오 기대값을 다시 정한 뒤 재생성해야 한다 (표본: 기대→실측): "
            f"{ {text[:20]: pair for text, pair in drifted.items()} }"
        )

    def says(
        text: str, *, truncated: bool = False, input_tokens: int = 0, output_tokens: int = 0
    ) -> dict[str, Any]:
        """provider가 이 호출에서 돌려줄 응답."""
        return {
            "text": text,
            "truncated": truncated,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
        }

    #: 이 호출은 응답 없이 실패한다 (전송 오류·서버 오류 등 provider 계층 실패).
    fails: dict[str, Any] = {"error": "provider"}

    def scenario(
        case_id: str,
        description: str,
        *,
        source_text: str,
        script: list[dict[str, Any]],
        asserts: list[dict[str, Any]],
        transport_attempts_per_call: int | None = None,
    ) -> Case:
        payload: dict[str, Any] = {
            "scenario": case_id,
            "source_text": source_text,
            "provider_script": script,
        }
        if transport_attempts_per_call is not None:
            payload["transport_attempts_per_call"] = transport_attempts_per_call
        return _unreferenced(case_id, description, payload, asserts)

    def fails_with(kind: str, *, calls: int) -> list[dict[str, Any]]:
        """변환이 실패로 끝나는 경우의 공통 단언 — 결과를 사용자에게 주지 않는다."""
        return [
            _assert("equals_field", path="outcome", value="error"),
            _assert("equals_field", path="failure_kind", value=kind),
            _assert("equals_field", path="llm_calls", value=calls),
            _assert("equals_field", path="easy_text", value=None),
        ]

    def keeps_original(text: str) -> list[dict[str, Any]]:
        """보정이 실패·기각된 경우의 공통 단언 — 1차 결과를 그대로 돌려준다."""
        return [
            _assert("equals_field", path="outcome", value="ok"),
            _assert("equals_field", path="easy_text", value=text),
            _assert("equals_field", path="repaired", value=False),
            _assert("equals_field", path="llm_calls", value=MAX_LLM_CALLS_PER_CONVERSION),
        ]

    cases += [
        # 4대 예외 ①·② — 1차 호출에서 나면 변환 실패다. 잘린 본문·빈 본문을 성공으로
        # 넘기면 사용자는 크레딧을 내고 일부가 사라진 결과를 받는다.
        scenario(
            "conversion-truncated-first-call-fails",
            "1차 변환 응답이 토큰 한도에서 잘리면 변환은 **실패**로 끝난다 — 잘린 본문을 "
            "성공 결과로 내보내면 조용한 정보 누락이 된다. 보정으로 덮지 않는다(호출 1회)",
            source_text=src_plain,
            script=[says("쉬운 글이 도중에", truncated=True)],
            asserts=fails_with("truncated", calls=1),
        ),
        scenario(
            "conversion-empty-first-call-fails",
            "후처리 뒤 본문이 남지 않으면 변환은 **실패**로 끝난다 — 빈 결과를 성공으로 "
            "넘기지 않는다. 껍데기만 온 응답(코드펜스뿐)도 같은 취급이다",
            source_text=src_plain,
            script=[says("```\n```")],
            asserts=fails_with("empty_result", calls=1),
        ),
        scenario(
            "conversion-provider-error-first-call-fails",
            "1차 호출 자체가 실패하면 변환은 실패로 끝난다. **보정 호출 실패와 대칭이 "
            "아니다** — 1차가 없으면 사용자에게 줄 결과가 아예 없다",
            source_text=src_plain,
            script=[fails],
            asserts=fails_with("provider_error", calls=1),
        ),
        # 4대 예외 ①·② (보정 위치) — 같은 사건이 보정 호출에서 나면 삼키고 1차를 채택한다.
        # 보정 실패가 변환 전체를 실패시키면 사용자는 받을 수 있었던 결과마저 잃는다.
        scenario(
            "conversion-repair-truncated-keeps-original",
            "보정 응답이 잘리면 보정을 버리고 **1차 결과를 채택**한다. 변환은 성공이고 "
            "`repaired`는 거짓이다 — 1차 위치와 정반대 처리라 같은 코드로 뭉뚱그릴 수 없다",
            source_text=src_plain,
            script=[says(dirty), says("오늘 서류를", truncated=True)],
            asserts=keeps_original(dirty),
        ),
        scenario(
            "conversion-repair-empty-keeps-original",
            "보정 응답이 후처리 뒤 비면 보정을 버리고 1차 결과를 채택한다",
            source_text=src_plain,
            script=[says(dirty), says("   ")],
            asserts=keeps_original(dirty),
        ),
        scenario(
            "conversion-repair-provider-error-keeps-original",
            "보정 호출이 실패해도 변환은 성공한다 — 1차 결과를 채택한다. 실패한 호출도 "
            "**호출 상한에는 셈한다**(다시 부르지 않는다)",
            source_text=src_plain,
            script=[says(dirty), fails],
            asserts=keeps_original(dirty),
        ),
        # 4대 예외 ③ 보정 악화 · ④ 자리표시자 유실 — 정책이 최종 산출물에 실제로 적용되는가.
        scenario(
            "conversion-repair-worsens-keeps-original",
            "보정이 위반을 늘리면 1차 결과를 채택한다. **토큰은 두 호출의 합**이다 — "
            "채택하지 않았어도 호출한 순간 비용은 발생했고, 그것을 빼면 원가가 실제보다 "
            "적게 잡힌다",
            source_text=src_plain,
            script=[
                says(dirty, input_tokens=120, output_tokens=45),
                says(worse, input_tokens=80, output_tokens=30),
            ],
            asserts=[
                *keeps_original(dirty),
                _assert("equals_field", path="input_tokens", value=200),
                _assert("equals_field", path="output_tokens", value=75),
            ],
        ),
        scenario(
            "conversion-repair-loses-placeholder-keeps-original",
            "보정이 자리표시자를 잃으면 1차 결과를 채택하고, 유실 목록은 **채택 결정 뒤 "
            "최종 본문 기준**으로 비어 있다. 1차 결과에 대고 산출하면 여기서 유실이 "
            "잘못 보고돼 내보내기가 409로 막힌다",
            source_text=src_rrn,
            script=[says(ph_dirty), says(ph_lost)],
            asserts=[
                *keeps_original(ph_dirty),
                _assert("equals_field", path="missing_placeholders", value=[]),
            ],
        ),
        # 과잉 거부 가드 — 위 다섯 케이스는 "보정을 절대 채택하지 않는" 구현도 통과시킨다.
        scenario(
            "conversion-repair-accepted",
            "보정이 자리표시자를 지키면서 위반을 줄이면 **보정문을 채택**하고 `repaired`가 "
            "참이다. 이 케이스가 없으면 보정을 항상 버리는 구현이 예외 케이스를 전부 "
            "통과한다 — 보정 호출 비용만 치르고 품질은 그대로인 상태",
            source_text=src_plain,
            script=[
                says(dirty, input_tokens=120, output_tokens=45),
                says(clean, input_tokens=80, output_tokens=30),
            ],
            asserts=[
                _assert("equals_field", path="outcome", value="ok"),
                _assert("equals_field", path="easy_text", value=clean),
                _assert("equals_field", path="repaired", value=True),
                _assert("equals_field", path="llm_calls", value=MAX_LLM_CALLS_PER_CONVERSION),
                _assert("equals_field", path="input_tokens", value=200),
                _assert("equals_field", path="output_tokens", value=75),
            ],
        ),
        # 호출 상한 — 루프 없음과 계측 지점.
        scenario(
            "conversion-no-repair-loop",
            f"보정 결과에 위반이 **남아 있어도 다시 부르지 않는다** — 호출은 정확히 "
            f"{MAX_LLM_CALLS_PER_CONVERSION}회다. '위반이 없어질 때까지' 루프는 상한이 "
            "아니라 지연·비용의 하한이 없다는 뜻이 된다. 대본은 2건뿐이라 3번째 호출을 "
            "요구하는 구현은 하네스에서 드러나야 한다",
            source_text=src_plain,
            script=[says(dirty), says(still_dirty)],
            asserts=[
                _assert("equals_field", path="outcome", value="ok"),
                _assert("equals_field", path="easy_text", value=still_dirty),
                _assert("equals_field", path="llm_calls", value=MAX_LLM_CALLS_PER_CONVERSION),
                _assert("at_most", path="llm_calls", limit=MAX_LLM_CALLS_PER_CONVERSION),
            ],
        ),
        scenario(
            "conversion-transport-retry-not-counted",
            "전송 계층 재전송은 호출 상한과 **분리 계측**한다(CNV-01). provider 어댑터가 "
            "같은 완성 요청을 3번 전송해도 논리 호출은 1회다. 계측 지점을 HTTP 요청으로 "
            "잡으면 상한이 재시도 설정에 따라 흔들리고, 실제로 몇 번 물어봤는지도 잃는다",
            source_text=src_plain,
            script=[says(clean)],
            transport_attempts_per_call=3,
            asserts=[
                _assert("equals_field", path="easy_text", value=clean),
                _assert("equals_field", path="llm_calls", value=1),
                _assert("at_most", path="llm_calls", limit=MAX_LLM_CALLS_PER_CONVERSION),
                _assert("equals_field", path="transport_attempts", value=3),
            ],
        ),
        # 자리표시자 유실 보고 (INV-03 인접) — 예외가 아니라 검수 화면 경고다.
        scenario(
            "missing-placeholders-preserved",
            "모델이 자리표시자를 지키면 유실 목록은 비어 있다. **과잉 보고 가드** — "
            "빈 목록을 낼 수 없는 구현은 모든 변환을 내보내기 409로 막는다",
            source_text=src_rrn,
            script=[says(ph_clean)],
            asserts=[
                _assert("equals_field", path="outcome", value="ok"),
                _assert("equals_field", path="easy_text", value=ph_clean),
                _assert("equals_field", path="llm_calls", value=1),
                _assert("equals_field", path="missing_placeholders", value=[]),
            ],
        ),
        scenario(
            "missing-placeholders-dropped-reported",
            "모델이 자리표시자를 지우면 그 라벨을 유실 목록에 담되 **예외로 막지 않는다** — "
            "개인정보가 새는 방향이 아니라 표시가 사라지는 방향이라 사람이 원문과 대조해 "
            "판단한다. 여기서 실패로 처리하면 쓸 만한 결과를 통째로 버린다",
            source_text=src_rrn,
            script=[says(ph_lost)],
            asserts=[
                _assert("equals_field", path="outcome", value="ok"),
                _assert("equals_field", path="easy_text", value=ph_lost),
                _assert("equals_field", path="llm_calls", value=1),
                _assert("equals_field", path="missing_placeholders", value=["[[주민등록번호1]]"]),
            ],
        ),
        scenario(
            "missing-placeholders-basis-is-adopted-text",
            "유실 목록의 **기준 본문은 채택된 최종 결과**다 — 1차 결과가 자리표시자를 "
            "잃었더라도 채택된 보정문이 그것을 되살렸으면 목록은 비어 있다. 1차 결과에 "
            "대고 산출하면 사용자가 받은 본문에 멀쩡히 있는 라벨을 유실로 신고하게 되고, "
            "내보내기가 409로 막혀 정상 결과를 못 받는다. **이 케이스가 없으면 1차 결과 "
            "기준으로 산출하는 구현이 다른 23건을 전부 통과한다**(실증: 변형 "
            "`missing-from-first-draft` 가 확장 전 24건에서 종료 코드 3)",
            source_text=src_rrn,
            script=[says(ph_lost_dirty), says(ph_clean)],
            asserts=[
                _assert("equals_field", path="outcome", value="ok"),
                _assert("equals_field", path="easy_text", value=ph_clean),
                _assert("equals_field", path="repaired", value=True),
                _assert("equals_field", path="llm_calls", value=MAX_LLM_CALLS_PER_CONVERSION),
                _assert("equals_field", path="missing_placeholders", value=[]),
            ],
        ),
        scenario(
            "missing-placeholders-partial-reports-only-lost",
            "자리표시자 둘 중 하나만 사라지면 **사라진 것만** 보고한다 — 남아 있는 라벨을 "
            "함께 실으면 검수자가 멀쩡한 자리를 찾아 헤맨다. 목록은 마스킹이 매긴 등장 "
            "순서를 따른다",
            source_text=src_two,
            script=[says(ph2_partial)],
            asserts=[
                _assert("equals_field", path="outcome", value="ok"),
                _assert("equals_field", path="easy_text", value=ph2_partial),
                _assert("equals_field", path="missing_placeholders", value=["[[카드번호1]]"]),
            ],
        ),
    ]
    return FixtureSpec(
        source=(
            "app/services/conversion.py::ConversionService.convert / _accepts_repair / "
            "MAX_LLM_CALLS_PER_CONVERSION"
        ),
        mode=MODE_SPEC,
        requirement=(
            "master-plan §3.3 변환 호출 계약 + 계획 §2.3·§4.6 (인벤토리 CNV-01·CNV-02·"
            "CNV-04) — 문서 1건 = LLM 호출 최대 2회(변환 1회 + 기계 검출된 위반이 있을 "
            "때만 표적 보정 1회, 루프 없음. 전송 계층 재전송은 이 수에 들어가지 않는다). "
            "보정 결과는 자리표시자를 잃거나 위반이 늘면 채택하지 않는다. 응답 절단·빈 "
            "결과·호출 실패는 1차 호출에서는 변환 실패로 끝내고 보정 호출에서는 삼켜 1차 "
            "결과를 채택한다. 결과에서 사라진 자리표시자는 예외로 막지 않고 채택 결정 뒤 "
            "최종 본문을 기준으로 유실 목록에 담아 검수 화면으로 넘긴다. "
            "**조건부 판정 주의(C-21)**: 채택 정책 8건은 산출물이 스스로 보고한 규칙 위반 "
            "건수를 입력으로 정책을 다시 계산해 대조한다 — 그 건수가 옳은지는 `style` "
            "도메인의 질문인데 `style`은 아직 선언되지 않았다(미포팅). 따라서 이 도메인의 "
            "'충족'은 **'같은 건수를 받았을 때 같은 결정을 내린다'까지만** 참이고, 건수 "
            "자체가 틀리면 채택 결정도 함께 틀린다. `style` 선언 전까지 이 조건은 열려 있다"
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
