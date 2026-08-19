"""Kotlin **가드 성격 테스트 클래스**가 실재하고 CI 에서 실제로 돌았는지 대조한다.

## 왜 이 장치가 필요한가 (게이트 25 H4 — codex 단독 지적)

`./gradlew build` 는 **있는 테스트를 전부** 돌린다. 그래서 테스트 파일을 지우면
스위트는 그대로 초록이고, 사라진 것은 아무 데서도 신고되지 않는다. `ci.yml` 은 이
경계를 스스로 선언해 두었다(`:263-264` — *"목록을 손으로 적는 것 자체는 이 스텝이 막지
못한다"*), 그리고 그 선언대로 클래스별 `--tests` 스텝은 core 탐지기 **둘**에만 붙어
있었다. 그 사이 Phase 3~4 에서 가드 클래스가 여럿 들어왔고, 그것들은 **지워도 CI 가
통과하는 상태**로 늘었다.

이 파일은 그 자리를 Python 하네스로 닫는다 — Kotlin 을 한 줄도 건드리지 않고, `quality`
잡의 "경로 명시" 규약과 같은 방식으로. **자기 안의 단언은 파일과 함께 사라지므로**
존재 단언은 검사 대상 **밖**에 있어야 한다는 것이 그 규약의 요지다.

## 재는 것 넷

1. **선언 ↔ 발견 정확 일치.** 가드 이름 families 로 트리를 훑어 나온 집합이 아래
   `GUARD_CLASSES` 와 **양방향으로** 같아야 한다. 파일을 지우면 declared 쪽이 남고,
   가드를 새로 넣고 선언을 빠뜨리면 discovered 쪽이 남는다. **빈 선언은 통과할 수
   없다** — 트리가 비어 있지 않은 한 공집합은 즉시 불일치다(SKILL.md 규칙 4 ⑶).
2. **내용 결속.** 선언한 FQCN 마다 `package` 줄과 타입 선언이 그 이름 그대로 있는
   파일이 **정확히 하나** 있어야 한다. 이름만 바꿔치기하거나 사본을 늘리면 걸린다.
3. **개수 상수.** 파일과 선언을 **함께** 지우는 편집은 위 1·2 로 잡히지 않는다.
   그때 `GUARD_CLASS_COUNT` 를 같이 고쳐야 하므로 diff 가 두 자리에서 난다 —
   최종 방어선은 그 diff 가 리뷰에 올라가는 것이고, `ci.yml` 이 이미 같은 자리에서
   같은 문장을 적었다. **한 칸 더 옮기지 않는다.**
4. **실행 대조.** Gradle 테스트 리포트 XML 에 그 클래스가 **실행된 기록**(tests > 0)이
   있어야 한다. 파일만 있고 아무도 안 돌리는 상태를 가른다. 이 대조는 **전체 빌드
   직후에만** 뜻이 있어서(로컬 리포트는 마지막에 돌린 것만 남는다 — `:core:test
   --tests X` 한 번이면 그 모듈 리포트가 통째로 바뀐다) `KOTLIN_GATE_REACH_REQUIRE_REPORT`
   가 켜졌을 때만 켠다. 그 변수가 **실제로 켜진 스텝이 `ci.yml` 에 있는지**는 이 파일
   밖 축(아래 배선 확인)이 되짚는다 — 변수를 빼면 대조가 모든 잡에서 조용히 skip 이
   되는데, 그것이 이 종류의 전형적인 빈자리다.

## 이 파일이 닫지 않는 것 (적어 둔다)

- **가드인데 families 이름을 안 쓰는 클래스**는 발견되지 않는다. 선언에 손으로 넣어야
  하고, 넣지 않으면 조용하다. families 를 넓히는 것은 근거(그 이름의 가드가 실재함)가
  생겼을 때 한다.
- **파일과 선언과 개수를 한 커밋에서 함께 지우는 편집**은 리뷰가 최종 방어선이다.
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from xml.etree import ElementTree

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
BACKEND_KOTLIN = REPO_ROOT / "backend-kotlin"
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"

#: `ci.yml` 이 명시해야 하는 이 파일의 경로. 파일을 옮기면 여기도 함께 고쳐야 한다.
THIS_TEST_PATH = "tests/test_kotlin_gate_reach.py"

#: 리포트 부재를 **실패로** 바꾸는 환경 변수. CI 의 kotlin 잡이 켠다.
REQUIRE_REPORT_ENV = "KOTLIN_GATE_REACH_REQUIRE_REPORT"

#: 가드 성격을 드러내는 이름 families. 저장소가 실제로 쓰는 접미사에서 뽑았다 —
#: 도달(`Reach`)·가드(`Guard`)·커버리지 계약(`CoverageContract`)·누출(`Leak`)·
#: 생성 지점(`Sites`)·통로(`Gateway`)·모듈 경계(`Boundary`)·선언 대조(`Declaration`)·
#: 선언 동기화(`Sync`)·스냅샷(`Snapshot`)·스키마(`Schema`).
#:
#: **이 목록을 넓히는 것은 탐지형 넓힘이다** — 새 가드가 발견 집합에 들어오면 선언을
#: 강제받는다. 좁히는 것은 그 반대이므로 근거를 남겨야 한다.
GUARD_CLASS_FAMILIES: tuple[str, ...] = (
    "ReachTest",
    "GuardTest",
    "CoverageContractTest",
    "LeakTest",
    "SitesTest",
    "GatewayTest",
    "BoundaryTest",
    "DeclarationTest",
    "SyncTest",
    "SnapshotTest",
    "SchemaTest",
)

#: 가드 성격 테스트 클래스의 **정본 목록**. 코드에서 실제로 열거해 확정했다.
GUARD_CLASSES: tuple[str, ...] = (
    "kr.easydoc.api.AuthDtoLeakTest",
    "kr.easydoc.api.AuthEndpointReachTest",
    "kr.easydoc.api.AuthenticationCoverageContractTest",
    "kr.easydoc.api.ContainerRejectionCoverageContractTest",
    "kr.easydoc.api.ContractErrorBodyReachTest",
    "kr.easydoc.api.ContractHeaderDeclarationTest",
    "kr.easydoc.api.DeletedAccountTokenReachTest",
    "kr.easydoc.api.PasswordHashLogLeakReachTest",
    "kr.easydoc.api.PasswordHashingBackpressureReachTest",
    "kr.easydoc.api.PrivateResponseHeadersReachTest",
    "kr.easydoc.api.SensitiveToStringReachTest",
    "kr.easydoc.api.WorkspaceDtoLeakTest",
    "kr.easydoc.api.WorkspaceEndpointReachTest",
    "kr.easydoc.core.CoreModuleBoundaryTest",
    "kr.easydoc.core.ParityDeclarationSyncTest",
    "kr.easydoc.core.WorkspaceNameLeakTest",
    "kr.easydoc.core.easyread.PromptInjectionGuardTest",
    "kr.easydoc.core.easyread.PromptTextSnapshotTest",
    "kr.easydoc.core.easyread.StyleRuleDataSnapshotTest",
    "kr.easydoc.core.privacy.MaskedTextGatewayTest",
    "kr.easydoc.core.privacy.ProvenanceCreationSitesTest",
    "kr.easydoc.infrastructure.crypto.EncryptionSchemeSchemaTest",
    "kr.easydoc.infrastructure.db.FlywayBaselineGuardTest",
)

#: 선언 **개수**를 목록과 따로 적는다. 파일과 선언을 함께 지우는 편집이 두 자리에
#: 흔적을 남기게 하는 장치다. 목록을 고쳤으면 여기도 고쳐야 한다.
GUARD_CLASS_COUNT = 23


def _kotlin_test_sources() -> list[Path]:
    """`backend-kotlin/**/src/test/**` 의 Kotlin 소스. 빌드 산출물은 뺀다."""
    return [
        path
        for path in BACKEND_KOTLIN.rglob("*.kt")
        if "build" not in path.parts and "src" in path.parts and "test" in path.parts
    ]


def _discovered_guard_classes() -> dict[str, list[Path]]:
    """families 로 훑어 나온 가드 클래스 → 그 이름을 선언한 파일들.

    FQCN 은 파일 안의 `package` 선언에서 만든다 — 경로에서 유추하면 경로와 패키지가
    어긋난 파일을 조용히 다른 이름으로 등록한다.
    """
    found: dict[str, list[Path]] = {}
    for path in _kotlin_test_sources():
        simple = path.stem
        if not simple.endswith(GUARD_CLASS_FAMILIES):
            continue
        package = _declared_package(path)
        if package is None:
            continue
        found.setdefault(f"{package}.{simple}", []).append(path)
    return found


def _declared_package(path: Path) -> str | None:
    match = re.search(r"^package\s+([\w.]+)", path.read_text(encoding="utf-8"), re.MULTILINE)
    return match.group(1) if match else None


def test_가드_클래스_선언이_비어_있지_않다() -> None:
    """**빈 선언에서 통과하면 안 된다** (SKILL.md 규칙 4 ⑶).

    범위 선언형 장치의 최대 위험은 좁게 선언되는 것이 아니라 아무것도 선언되지 않은
    채 초록이 되는 것이다. 이 저장소는 그 형태를 두 번 실측했다(parity 게이트 · 표 판정기).
    """
    assert GUARD_CLASSES, "가드 클래스 선언이 비었다 — 아래 대조가 전부 0건 검사가 된다."
    assert len(set(GUARD_CLASSES)) == len(GUARD_CLASSES), (
        f"선언에 중복이 있다: {sorted({n for n in GUARD_CLASSES if GUARD_CLASSES.count(n) > 1})}"
    )
    assert len(GUARD_CLASSES) == GUARD_CLASS_COUNT, (
        f"선언 개수({len(GUARD_CLASSES)})가 상수({GUARD_CLASS_COUNT})와 다르다.\n"
        "  목록을 정당하게 고쳤다면 이 상수도 함께 고쳐라 — 두 자리에 나는 diff 가 "
        "'가드를 하나 뺐다'는 신고이고, 그것이 이 상수의 값어치다."
    )


def test_선언한_가드와_트리에서_발견한_가드가_정확히_일치한다() -> None:
    """**정확 일치**다. 하한도 부분집합도 아니다.

    - 파일을 지우면 → 선언에만 남아 불일치.
    - 새 가드를 넣고 선언을 빠뜨리면 → 발견에만 남아 불일치. 가드가 **조용히 늘지**
      않게 하는 쪽이고, 이쪽이 없으면 목록은 시간이 지날수록 실물보다 좁아진다.
    """
    discovered = set(_discovered_guard_classes())
    declared = set(GUARD_CLASSES)

    missing = sorted(declared - discovered)
    extra = sorted(discovered - declared)
    assert not missing and not extra, (
        "선언한 가드 클래스와 트리에서 발견한 가드 클래스가 다르다.\n"
        f"  선언에만 있다(파일이 사라졌다): {missing or '없음'}\n"
        f"  트리에만 있다(선언이 빠졌다): {extra or '없음'}\n"
        "  파일을 지웠다면 GUARD_CLASSES 와 GUARD_CLASS_COUNT 도 함께 고쳐야 하고, "
        "그 diff 가 '가드를 뺐다'는 신고로 리뷰에 올라간다."
    )


@pytest.mark.parametrize("fqcn", GUARD_CLASSES)
def test_가드_클래스마다_그_이름을_선언한_파일이_하나다(fqcn: str) -> None:
    """**내용 결속** — 파일이 있다는 것만으로는 부족하다.

    이름만 바꿔치기(다른 클래스를 같은 파일명으로) · 사본 늘리기(같은 FQCN 을 두 곳에)를
    가른다. 파일 안의 `package` 선언과 타입 선언이 FQCN 과 **글자 그대로** 같아야 한다.
    """
    package, simple = fqcn.rsplit(".", 1)
    candidates = _discovered_guard_classes().get(fqcn, [])

    assert candidates, f"{fqcn} 을 선언한 파일이 없다 — 가드가 사라졌거나 패키지가 바뀌었다."
    assert len(candidates) == 1, (
        f"{fqcn} 을 선언한 파일이 {len(candidates)}개다: {[str(p) for p in candidates]}\n"
        "  사본이 생기면 하나를 지워도 이 대조가 통과한다."
    )

    path = candidates[0]
    assert path.stem == simple, f"{fqcn} 의 파일 이름이 클래스 이름과 다르다: {path}"
    source = path.read_text(encoding="utf-8")
    assert re.search(
        rf"^\s*(?:internal\s+|private\s+|open\s+|abstract\s+)*(?:class|object)\s+{re.escape(simple)}\b",
        source,
        re.MULTILINE,
    ), f"{path} 안에 `{simple}` 타입 선언이 없다 — 파일만 남고 내용이 갈렸다."
    assert _declared_package(path) == package, f"{path} 의 package 선언이 {fqcn} 과 다르다."


def _test_report_dirs() -> list[Path]:
    return sorted(BACKEND_KOTLIN.glob("*/build/test-results/test"))


def test_CI_리포트가_선언한_가드를_실제로_실행했다() -> None:
    """파일이 있는 것과 **돌았다**는 것은 다르다.

    `--tests` 필터에서 제외되거나 태그로 빠지면 파일은 남고 실행은 0 이 된다.
    Gradle 리포트 XML 에 그 클래스의 실행 기록(tests > 0)이 있어야 한다.
    중첩 클래스(`@Nested`)만 있는 가드는 바깥 이름의 XML 이 없으므로 `FQCN$…` 도 센다.
    """
    if not os.environ.get(REQUIRE_REPORT_ENV):
        # **로컬에서는 재지 않는다 — 재는 척하면 안 되기 때문이다.** 로컬 리포트는
        # 마지막에 돌린 것만 남는다(`./gradlew :core:test --tests X` 한 번이면 그 모듈
        # 리포트가 통째로 그 하나로 바뀐다 — 실측). 그 상태를 "가드가 안 돌았다"로 읽으면
        # 상시 오경보가 되고, 상시 오경보는 결국 이 스텝을 꺼서 게이트를 잃게 한다.
        # 이 대조가 뜻을 갖는 곳은 **전체 빌드 직후**뿐이므로 거기서만 켠다.
        pytest.skip(
            f"{REQUIRE_REPORT_ENV} 가 꺼져 있다 — 실행 대조는 전체 Gradle 빌드 직후에만 "
            "뜻이 있다. CI 의 kotlin 잡이 build 뒤에 이 변수를 켜고 돌린다."
        )

    report_dirs = _test_report_dirs()
    assert report_dirs, (
        f"{REQUIRE_REPORT_ENV} 가 켜져 있는데 Gradle 테스트 리포트가 없다.\n"
        "  이 스텝은 Kotlin 테스트 뒤에 돌아야 한다 — 리포트가 없으면 실행 대조는 "
        "아무것도 재지 않는다. 조용히 초록이 되는 것이 이 종류의 빈자리다."
    )

    executed: dict[str, int] = {}
    for report_dir in report_dirs:
        for report in report_dir.glob("TEST-*.xml"):
            suite = ElementTree.parse(report).getroot()
            name = suite.get("name") or ""
            outer = name.split("$", 1)[0]
            executed[outer] = executed.get(outer, 0) + int(suite.get("tests") or 0)

    silent = sorted(fqcn for fqcn in GUARD_CLASSES if executed.get(fqcn, 0) <= 0)
    assert not silent, (
        f"선언한 가드가 리포트에 실행 기록이 없다: {silent}\n"
        f"  리포트 디렉터리: {[str(d) for d in report_dirs]}\n"
        "  파일은 있는데 아무도 돌리지 않는 상태다 — 게이트가 존재만 하고 도달이 0 이다."
    )


def _steps_running_this_file() -> list[tuple[str, dict[str, object]]]:
    """`ci.yml` 에서 이 파일을 **경로로 명시해 돌리는** 스텝을 (잡 이름, 스텝) 으로 낸다.

    **문자열 분할로 세지 않는다.** 첫 판이 그랬다가 곧바로 빈자리를 만들었다 —
    스텝 앞 주석 블록은 `- name:` 으로 자르면 **앞 스텝의 조각**에 붙는데, 그 주석이
    이 파일 경로를 언급하고 있어서 quality 잡의 스텝을 통째로 지워도 대조가 초록이었다
    (게이트 25 자체 음성 대조에서 검출). 주석이 배선을 대신 증명하는 형태이므로
    **YAML 로 파싱해 `run` 문자열만** 본다.
    """
    document = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
    found: list[tuple[str, dict[str, object]]] = []
    for job_name, job in (document.get("jobs") or {}).items():
        for step in job.get("steps") or []:
            if isinstance(step, dict) and THIS_TEST_PATH in str(step.get("run") or ""):
                found.append((job_name, step))
    return found


def _require_report_flag(step: dict[str, object]) -> str:
    """스텝의 `env` 에서 요구 모드 값을 읽는다. `env` 가 없거나 매핑이 아니면 빈 문자열."""
    env = step.get("env")
    if not isinstance(env, dict):
        return ""
    return str(env.get(REQUIRE_REPORT_ENV) or "")


def test_CI_가_이_대조를_경로_명시로_배선했다() -> None:
    """**장치 밖에서 무언가 깨져야 한다** (SKILL.md 규칙 6).

    이 파일 안에만 단언을 두면 파일과 함께 사라진다. `ci.yml` 이 이 경로를 명시해
    돌리는지, 그리고 실행 대조가 **요구 모드**로 켜지는지를 여기서 되짚는다.
    두 스텝 중 하나라도 빠지면 대조가 반쪽이 된다.
    """
    steps = _steps_running_this_file()

    assert steps, (
        f"ci.yml 이 {THIS_TEST_PATH} 를 경로로 명시해 돌리지 않는다 — `uv run pytest` "
        "전체 수집만 믿으면 파일이 지워졌을 때 수집 0 으로 조용히 통과한다."
    )

    requiring = [job for job, step in steps if _require_report_flag(step)]
    plain = [job for job, step in steps if not _require_report_flag(step)]

    # 실행 대조를 **실제로 켜는 스텝**이 있어야 한다. 변수 이름이 주석에만 적혀 있으면
    # 대조는 영원히 skip 이다 — 그래서 `env` 매핑에서 값을 읽는다.
    assert requiring, (
        f"{REQUIRE_REPORT_ENV} 를 env 로 켜고 {THIS_TEST_PATH} 를 돌리는 스텝이 없다 — "
        "실행 대조가 모든 잡에서 skip 으로 조용히 넘어간다. kotlin 잡의 build 뒤에 배선하라."
    )
    # 그리고 값싼 잡에서도 한 번 돌아야 한다 — 가드 파일 삭제는 Gradle 빌드를 기다리지
    # 않고 드러나는 편이 낫다.
    assert plain, (
        f"{THIS_TEST_PATH} 가 요구 모드 스텝에서만 돈다 — 선언 ↔ 트리 대조는 Gradle 없이도 "
        "되므로 quality 잡에도 경로 명시로 두어 삭제가 먼저 드러나게 하라."
    )
