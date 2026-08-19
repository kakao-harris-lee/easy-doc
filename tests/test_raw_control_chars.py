"""추적 파일에 **원시 제어문자**가 들어가는 것을 전수로 막는다.

## 왜 이 장치가 필요한가 — 이 결함은 부주의가 아니라 **기제**다

같은 사고가 게이트 2·12·13·25·26 에 걸쳐 반복됐고, 게이트 26 한 회차에만 **세 번**
났다(제품 테스트 · 보안 감사문 · 교차 종합문 **자신**). 형태가 전부 같다 —
**제어문자를 설명하거나 데이터로 적으면서 이스케이프 대신 그 글자를 붙여 넣었다.**

교차 종합 `reviews/04_gate25-fixes_cross.md` §7.2·§12.1 이 그 기제를 이렇게 적었다:
*"NUL 을 언급하려면 NUL 을 타이핑하게 되고, 아무 도구도 그것을 막지 않는다."*
세 번 다 **작성자 자신의 사후 검사로만** 발견됐고, 저장소의 어떤 자동 장치도 하나도
잡지 못했다. 개별 자리를 열거하는 방식으로는 셋 다 못 잡았을 것이다 —
`CLAUDE.md` 규칙 4 ⑴ 이 요구하는 **종류**를 댈 수 있다: **원시 제어문자를 든 추적 파일 전부.**

## 무엇이 피해인가

`AesGcmContentCipherTest.kt` 는 NUL 하나 때문에 git 이 **바이너리로 분류**했고, 그 결과
`git diff` 가 `Bin …` 한 줄만 냈다. 그 상태로 **연속 두 게이트 · 여섯 번의 레인 통과**
동안 아무도 그 파일의 본문을 diff 로 읽지 못했다 — I-7 회귀 전건을 드는 파일인데도.
저장소가 스스로 「최종 방어선」이라 적어 둔 리뷰 diff(`ci.yml:263-264`)가 **그 파일
하나에 대해 두 게이트 동안 거짓**이었다. 이것이 게이트 26 Critical② 의 세 번째 축이다.

## 이 장치의 분류와 성질 (`CLAUDE.md` 규칙 4)

**탐지형.** 어긋남을 드러내고 아무것도 가리지 않는다. 성질 넷을 지킨다.

1. **루트 목록이 아니라 `git ls-files` 전수.** 개인정보 스캐너의 `SCAN_ROOTS` 열거가
   바로 이 결함을 숨긴 기제였다 — 실측 8건 중 **5건이 그 루트 밖**에 있었다.
2. **면제 목록·억제 표기가 없다.** 모든 형식에 무손실 이스케이프가 있으므로 기존
   위반은 baseline 이 아니라 **전건 수정** 대상이다. 면제가 붙는 순간 이 장치는
   은폐형으로 미끄러지고, 규칙 4 ⑵ 가 그것을 금지한다.
3. **훑은 파일이 0건이면 실패한다** (규칙 4 ⑶). 빈 선언에서 통과하는 것이 이 종류
   장치의 최대 위험이고, 이 저장소는 그 형태를 두 번 실측했다.
4. **판정 기준은 「원시 제어문자 보유」다 — 「git 이 바이너리라 부름」이 아니다**
   (교차 종합 §10-①). git 은 앞 8000바이트에서만 NUL 을 훑고 `0x01`·`0x1f`·`0x7f` 로는
   바이너리 판정을 내리지 않는다. 실측 당시 비바이너리 7건 중 **6건을 git 은 텍스트로
   분류**했다 — git 의 분류를 판정 기준으로 삼았다면 7건 중 1건만 잡았을 것이다.

## 진짜 바이너리는 어떻게 빼는가 — **우리가 목록을 들지 않는다**

확장자를 열거하지 않는다(그 열거가 바로 다음 게이트에서 빈다). 두 단을 쓴다.

1. **git 자신의 텍스트/바이너리 분류에 위임한다** — `git ls-files --eol` 의 `i/` 값이
   `-text` 면 git 이 바이너리로 본다는 뜻이다.
2. **그 분류가 이번 결함을 놓치는 자리를 성질로 되찾는다** — git 이 바이너리라 불러도
   **바이트 전체가 UTF-8 로 디코드되면 그것은 텍스트다.** 실측으로 이 두 단은 저장소를
   정확히 가른다: 진짜 바이너리 8건(jar·zip·docx·hwpx)은 UTF-8 디코드에 **실패**하고,
   제어문자를 실수로 품은 텍스트 7건은 **전부 성공**한다. `AesGcmContentCipherTest.kt`
   가 정확히 그 경계였다 — git 은 바이너리라 불렀지만 UTF-8 로 온전히 읽힌다.

즉 **git 이 바이너리라 부르는 것은 제외 사유가 아니라 가중 사유**다. 그런 파일은
`diff 가 이미 보이지 않는 상태`로 따로 표시한다 — 그것이 실제 피해 상태다.

## 채택하지 않은 것

- **`.gitattributes text` 강제.** codex 가 기각했고 교차 종합 §7.2 가 옳다고 확정했다 —
  **표현형** 장치라 git 의 렌더링만 바꾸고 디스크의 바이트는 그대로 둔다. `grep`·
  민짜 `diff`·`file`·다른 도구의 바이너리 스니핑은 **계속 속는다.**
- **개인정보 스캐너(`scan_privacy_invariants.py`)에 규칙 추가.** 그것은 개인정보 불변식
  탐지기이고 이것은 **심사 가능성** 축이다. 축을 섞으면 한쪽의 오탐이 다른 쪽 게이트의
  신뢰를 갉는다(게이트 25 판정 ①과 같은 이유). 그래서 `quality` 잡의 「경로 명시」
  계열에 둔다 — `test_run_gate.py`·`test_kotlin_gate_reach.py` 옆이다.

## 이 장치가 닫지 않는 것 (적어 둔다)

- **추적되지 않은 파일**은 보지 않는다. 피해(diff 불가·리뷰 불가)는 추적 파일에서만
  성립하므로 분모를 그렇게 뒀다. 커밋되는 순간 다음 실행에서 잡힌다.
- **UTF-8 로 디코드되는 진짜 바이너리**가 있다면 오탐이 된다. 면제 통로가 없으므로
  그때는 이 문단을 고치는 판정이 필요하다 — 실측 시점 저장소에는 0건이다.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path
from types import ModuleType

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"

#: `ci.yml` 이 명시해야 하는 이 파일의 경로. 파일을 옮기면 여기도 함께 고쳐야 한다.
THIS_TEST_PATH = "tests/test_raw_control_chars.py"

#: **원시 제어문자.** C0 전부에서 TAB·LF·CR 만 빼고, DEL(0x7F)을 더한다.
#: 실측된 여덟 사고의 문자가 전부 이 집합 안에 있다 — `0x00`·`0x01`·`0x1f`·`0x7f`.
CONTROL_BYTES = frozenset(set(range(0x00, 0x20)) - {0x09, 0x0A, 0x0D} | {0x7F})

#: git 이 파일 앞부분에서 NUL 을 훑는 범위. 이 안에 NUL 이 있으면 git 은 그 파일을
#: 바이너리로 잡고 `git diff` 가 본문을 내지 않는다 — **그것이 실제 피해 상태다.**
GIT_BINARY_SNIFF_BYTES = 8000


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=True
    ).stdout


def _tracked_files() -> list[str]:
    """`git ls-files` **전수**. 루트 목록을 들지 않는다 — 그 열거가 이 결함을 숨겼다."""
    return [path for path in _git("ls-files", "-z").split("\0") if path]


def _git_says_binary() -> dict[str, bool]:
    """경로 → git 이 바이너리로 보는가. `git ls-files --eol` 의 `i/` 값으로 판정한다.

    **우리가 확장자 목록을 들지 않는다** — 분류를 git 에게 위임하고, 그 분류가 이번
    결함을 놓치는 자리는 아래 UTF-8 디코드 가능성으로 되찾는다.
    """
    verdict: dict[str, bool] = {}
    for record in _git("ls-files", "--eol", "-z").split("\0"):
        if not record:
            continue
        meta, _, path = record.partition("\t")
        fields = meta.split()
        if not fields:
            continue
        verdict[path] = fields[0] == "i/-text"
    return verdict


class Offender:
    """원시 제어문자를 든 텍스트 파일 하나."""

    def __init__(self, path: str, data: bytes, git_binary: bool) -> None:
        self.path = path
        self.controls = sorted({byte for byte in data if byte in CONTROL_BYTES})
        self.count = sum(1 for byte in data if byte in CONTROL_BYTES)
        self.git_binary = git_binary
        self.nul_in_sniff_window = 0 in data[:GIT_BINARY_SNIFF_BYTES]

    def __str__(self) -> str:
        chars = ", ".join(f"0x{byte:02x}" for byte in self.controls)
        line = f"  - {self.path} — {self.count}개 [{chars}]"
        if self.git_binary or self.nul_in_sniff_window:
            line += (
                "\n      ** git 이 이미 이 파일을 바이너리로 잡아 diff 가 보이지 않는다 **"
                f" (첫 {GIT_BINARY_SNIFF_BYTES}바이트 안 NUL: "
                f"{'있음' if self.nul_in_sniff_window else '없음'})"
                "\n      리뷰가 본문을 읽지 못하는 상태다 — 이것이 게이트 26 Critical② 의 축이다."
            )
        return line


def _scan() -> tuple[int, list[Offender]]:
    """(훑은 텍스트 파일 수, 위반 목록). 진짜 바이너리는 훑은 수에서도 뺀다."""
    binary_verdict = _git_says_binary()
    scanned = 0
    offenders: list[Offender] = []
    for path in _tracked_files():
        try:
            data = (REPO_ROOT / path).read_bytes()
        except OSError:
            # 심볼릭 링크 등 읽을 수 없는 항목. 제어문자를 담을 수 없으므로 훑은 수에
            # 넣지 않는다 — "검사했는데 없음"과 "검사하지 못함"을 섞지 않기 위해서다.
            continue
        git_binary = binary_verdict.get(path, False)
        try:
            data.decode("utf-8")
        except UnicodeDecodeError:
            if git_binary:
                continue  # 진짜 바이너리 — git 도 그렇게 부르고 텍스트로 읽히지도 않는다
        scanned += 1
        offender = Offender(path, data, git_binary)
        if offender.controls:
            offenders.append(offender)
    return scanned, offenders


def test_훑은_텍스트_파일이_0건이_아니다() -> None:
    """**빈 스캔에서 통과하면 안 된다** (SKILL.md 규칙 4 ⑶).

    `git ls-files` 가 빈 값을 내거나(작업 트리가 git 저장소가 아님) 분류가 전부
    바이너리로 뒤집히면, 아래 대조는 0건 검사가 되고 **아무것도 재지 않은 채 초록**이
    된다. 이 저장소는 그 형태를 두 번 실측했다(parity 게이트 · 표 판정기).
    """
    tracked = _tracked_files()
    assert tracked, "git ls-files 가 아무것도 내지 않았다 — 분모가 비면 이 검사는 무의미하다."
    scanned, _ = _scan()
    assert scanned, (
        f"추적 파일 {len(tracked)}개 중 텍스트로 훑은 것이 0건이다 — 바이너리 판별이 "
        "전부를 삼켰다는 뜻이고, 그 상태에서 통과하면 이 장치는 존재만 하고 도달이 0 이다."
    )


def test_추적_파일에_원시_제어문자가_없다() -> None:
    """**전건 수정이 기준이다. baseline 도 면제 목록도 두지 않는다.**

    모든 형식에 무손실 이스케이프가 있다 — Kotlin·Python·TS 는 `\\u0000`, Markdown 은
    그 표기를 그대로 적으면 되고, JSON 은 `\\u007f` 가 규격이다. 고치는 비용이 글자
    하나이므로 「기존 위반은 남기고 신규만 막는다」는 절충이 성립하지 않는다.
    """
    scanned, offenders = _scan()
    assert not offenders, (
        f"추적 파일에 원시 제어문자가 있다 (텍스트 {scanned}개 훑음, 위반 {len(offenders)}개):\n"
        + "\n".join(str(offender) for offender in offenders)
        + "\n\n  고치는 법: 그 바이트를 **이스케이프 표기**로 바꿔라. 값이 필요하면 "
        "`\\u0000` 같은 표기를, 설명이라면 그 표기를 글자 그대로 적어라.\n"
        "  **면제 목록을 만들지 마라** — 이 장치가 은폐형으로 미끄러지는 유일한 통로이고, "
        "CLAUDE.md 규칙 4 ⑵ 가 그것을 금지한다."
    )


def test_CI_가_이_검사를_경로_명시로_배선했다() -> None:
    """**장치 밖에서 무언가 깨져야 한다** (SKILL.md 규칙 6).

    이 파일 안에만 단언을 두면 파일과 함께 사라진다. `ci.yml` 이 이 경로를 명시해
    돌리는지를 여기서 되짚는다 — 그러면 파일을 지웠을 때 그 스텝이 `exit 4` 로 죽는다.
    `uv run pytest` 전체 수집만 믿으면 삭제가 **수집 0 으로 조용히 통과**한다.

    YAML 로 파싱해 `run` 문자열만 본다. 문자열 분할로 세면 스텝 **앞 주석**이 배선을
    대신 증명하는 형태가 되고, 게이트 25 가 그 빈자리를 실측했다.
    """
    document = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
    jobs = [
        job_name
        for job_name, job in (document.get("jobs") or {}).items()
        for step in (job.get("steps") or [])
        if isinstance(step, dict) and THIS_TEST_PATH in str(step.get("run") or "")
    ]
    assert jobs, (
        f"ci.yml 이 {THIS_TEST_PATH} 를 경로로 명시해 돌리지 않는다 — 이 파일을 지우면 "
        "아무 데서도 신고되지 않는다. quality 잡의 「경로 명시」 계열에 두어라."
    )


# ─────────────────────────────────────────────────────────────────────────────
# 발생 차단 쪽 — **탐지만으로는 늦다**
#
# 위 검사는 **결과**를 본다. 원시 제어문자가 이미 파일에 들어간 뒤에 잡는다는 뜻이고,
# 그 시점이면 커밋·리뷰·재생성이 한 바퀴 돌았을 수 있다. 그래서 저장소에는 **발생을
# 막는 쪽**이 따로 있다 — 추적 파일에 JSON 을 쓰는 생성기들이 직렬화 시점에 치환한다.
#
# 왜 표준 `json.dumps` 로 부족한가: 파이썬 인코더는 C0(0x00~0x1F)만 이스케이프하고
# **DEL(0x7F)은 `ensure_ascii=True` 여도 원시 바이트로 흘려보낸다.** 즉 생성기가 정상
# 동작해도 그 자리는 반드시 원시 바이트가 된다 — 게이트 26 의
# `parity/fixtures/export/export.json`(DEL 21개)이 그 실측 사례다.
#
# ## 왜 공용 모듈로 빼지 않고 **복사본 + 결속 테스트** 인가 (게이트 26 판정)
#
# 이 저장소는 정본을 복제했다가 갈린 전례가 여럿이라 복사본이 기본 선택지는 아니다.
# 그런데도 이쪽을 고른 근거는 셋이다.
#
# 1. **런타임 결합이 수명을 거꾸로 잇는다.** 세 생성기는 각각 `python-kotlin-parity`
#    스킬(폐기 예정 하네스) · `migration-safety-gate` 스킬(차단 게이트) · `tests/golden`
#    에 산다. 어느 하나를 정본으로 삼아 나머지가 import 하면, 차단 게이트가 폐기 예정
#    하네스에 의존하거나 생성기가 테스트 트리에 의존하게 된다.
# 2. **위험한 방향의 드리프트가 조용하지 않다.** 치환표가 좁아지면 그 결과물을 위
#    전수 탐지가 잡는다. 그리고 아래 결속 테스트가 **좁아진 순간** 잡는다 —
#    `_403_TOKEN` 을 열거에서 파생으로 바꿀 때 이 저장소가 쓴 것과 같은 처방이다.
# 3. **공용 모듈은 「새 생성기가 그것을 쓰는가」를 보장하지 못한다.** 보장하는 것은
#    결과를 보는 전수 탐지뿐이고, 그것은 이미 위에 있다.
#
# **닫지 않는 것(적어 둔다):** 아래 선언 목록에 없는 **새 생성기**는 이 결속이 보지
# 못한다. 그 자리를 지키는 것은 위 전수 탐지 하나뿐이다 — 즉 새 생성기의 결함은
# **발생 시점이 아니라 결과 시점**에 드러난다.
#
# **`parity/actual/**` 는 분모 밖이다.** `.gitignore:36` 이 그 디렉터리를 무시하므로
# `git ls-files` 에 나오지 않는다(2026-08-20 실측: 추적 0개). 실행할 때마다 생기는
# 산출물이라 그 자체는 옳은 판단이지만, **추적으로 바뀌면 이 검사의 분모가 달라진다** —
# 그때 다시 봐야 한다.

#: 추적 파일에 JSON 을 쓰는 생성기의 **직렬화 함수** 선언. (표시 이름, 파일, 심볼)
#:
#: 여기 없는 생성기는 결속되지 않는다 — 새로 만들면 여기에 넣어라. 목록이 비면
#: 아래 테스트가 실패한다(빈 선언 통과 금지, SKILL.md 규칙 4 ⑶).
ESCAPING_SERIALIZERS: tuple[tuple[str, str, str], ...] = (
    (
        "parity fixture 생성기",
        ".claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py",
        "dump_json",
    ),
    (
        "Python 정본 스냅샷 생성기",
        ".claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py",
        "_dump",
    ),
    ("골든셋 기준선 기록기", "tests/golden/baseline.py", "dump_json"),
)


def _load_module(relative: str) -> ModuleType:
    """저장소 안의 파일 하나를 모듈로 적재한다.

    `sys.modules` 에 먼저 등록하는 이유: 모듈 안의 `@dataclass` 가 데코레이터 실행 중
    `sys.modules[cls.__module__]` 를 조회하므로, 등록하지 않으면 `AttributeError` 로 죽는다.
    스킬 스크립트는 같은 디렉터리의 형제를 import 하므로 그 디렉터리도 경로에 넣는다.
    """
    path = REPO_ROOT / relative
    name = f"_rawctl_{path.stem}"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:  # pragma: no cover - 경로가 맞으면 성립한다
        raise AssertionError(f"모듈을 적재할 수 없다: {relative}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    for extra in (str(REPO_ROOT), str(path.parent)):
        if extra not in sys.path:
            sys.path.insert(0, extra)
    spec.loader.exec_module(module)
    return module


#: 결속 탐침. **모든 제어문자를 데이터로** 담는다 — 치환표에서 하나만 빠져도 갈린다.
def _probe_payload() -> dict[str, object]:
    return {
        "설명": "제어문자를 데이터로 담는다",
        "문자들": ["".join(chr(code) for code in sorted(CONTROL_BYTES))],
        "중첩": {"값": "앞" + chr(0x7F) + "뒤"},
    }


def test_직렬화_선언이_비어_있지_않다() -> None:
    """**빈 선언에서 통과하면 안 된다** (SKILL.md 규칙 4 ⑶)."""
    assert ESCAPING_SERIALIZERS, (
        "직렬화 선언이 비었다 — 아래 결속이 전부 0건 검사가 된다. 발생 차단 쪽이 통째로 "
        "사라져도 아무도 신고하지 않는 상태다."
    )


@pytest.mark.parametrize(
    ("label", "relative", "symbol"),
    [pytest.param(*entry, id=entry[1]) for entry in ESCAPING_SERIALIZERS],
)
def test_생성기가_원시_제어문자를_남기지_않는다(label: str, relative: str, symbol: str) -> None:
    """선언한 생성기마다 **제어문자를 데이터로 넣어** 출력에 원시 바이트가 없는지 본다.

    상수만 대조하면 「이름은 같은데 쓰이지 않는」 상태를 통과시킨다. 그래서 함수를
    **실제로 부른다** — 치환이 배선에서 빠지면 여기서 빨개진다.
    """
    module = _load_module(relative)
    serializer = getattr(module, symbol, None)
    assert callable(serializer), (
        f"{label}({relative})에 `{symbol}` 이 없다 — 직렬화 경로가 사라졌거나 이름이 바뀌었다."
    )
    rendered = serializer(_probe_payload())
    residue = sorted({ord(char) for char in rendered if ord(char) in CONTROL_BYTES})
    assert not residue, (
        f"{label}({relative})의 직렬화가 원시 제어문자를 남긴다: "
        f"{', '.join(f'0x{code:02x}' for code in residue)}\n"
        "  파이썬 인코더는 DEL(0x7f)을 `ensure_ascii=True` 여도 원시로 흘려보낸다 — "
        "치환을 인코더 출력에 걸어라. 그러지 않으면 다음 재생성이 추적 파일에 원시 "
        "바이트를 심고, 위 전수 탐지가 그때서야 잡는다."
    )


def test_생성기들의_치환_결과가_서로_같다() -> None:
    """**세 벌이 갈리는 것이 이 저장소가 겪은 형태다.** 같은 입력에 같은 출력을 요구한다.

    상수 이름이 아니라 **결과**로 묶는다 — 이름을 맞춰도 배선이 다르면 갈리고, 이름이
    달라도 결과가 같으면 갈리지 않는다. 들여쓰기·키 순서까지 같아야 하므로 직렬화 옵션이
    한쪽만 바뀌어도 여기서 드러난다.
    """
    payload = _probe_payload()
    rendered = {
        relative: getattr(_load_module(relative), symbol)(payload)
        for _label, relative, symbol in ESCAPING_SERIALIZERS
    }
    distinct = set(rendered.values())
    assert len(distinct) == 1, (
        "선언한 생성기들의 직렬화 결과가 갈렸다:\n"
        + "\n".join(f"  - {relative}: {value!r}" for relative, value in sorted(rendered.items()))
        + "\n  한쪽만 고치면 조용해지는 쪽은 늘 안 고쳐진 쪽이다 — 세 벌을 함께 맞춰라."
    )


@pytest.mark.parametrize(
    ("label", "relative", "symbol"),
    [pytest.param(*entry, id=entry[1]) for entry in ESCAPING_SERIALIZERS],
)
def test_치환표가_좁아지면_생성기가_죽는다(
    monkeypatch: pytest.MonkeyPatch, label: str, relative: str, symbol: str
) -> None:
    """**되짚기가 장식이 아님을 보인다** (음성 대조를 상시 회귀로 고정한다).

    치환표를 비우면 파이썬 인코더가 C0 는 그대로 이스케이프하지만 DEL 은 원시로 남는다.
    그때 생성기가 조용히 쓰면 원시 바이트가 정본에 심기고, 그 상태는 `--check` 류의
    재생성 대조로도 드러나지 않는다(재생성이 같은 바이트를 다시 만든다). 그래서 각
    생성기는 자기 출력을 되짚어 죽어야 한다.
    """
    module = _load_module(relative)
    monkeypatch.setattr(module, "_CONTROL_ESCAPES", {}, raising=True)
    with pytest.raises(RuntimeError, match="원시 제어문자"):
        getattr(module, symbol)(_probe_payload())
