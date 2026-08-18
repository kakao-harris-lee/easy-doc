"""Python 정본 스냅샷 생성기의 **삭제 탐지력**을 상시 회귀로 고정한다 (게이트 15 X1).

## 왜 이 파일이 있는가

`dump_python_snapshots.py --check` 는 재생성 diff 0 을 "지금 Python 이 이 값을 낸다"의
증명으로 쓰고, 그 증명이 L344·L345 승격의 근거였다. 그런데 예전 생성기는 previous
스냅샷을 케이스 원장으로 그대로 믿어서, 케이스를 45→4 로 덜어 낸 스냅샷도, 파생 키
(`GLOSS_COLLISION_PATTERN_GLOSSES`, 123 항목)를 지운 스냅샷도 재생성 diff 0 이었다 —
검사기가 위조를 승인했다(교차 종합 X1, P-A·C15-5 양쪽 독립 실측). 그 수정(케이스 하한
`PROMPT_CASE_FLOOR` + 파생 키 무조건 재생성)이 다시 무력해지면 여기가 빨개진다.

게이트 16(E·G)이 그 수정의 두 구멍을 실측했다 — 빈 섹션 하한이 통과하고, 하한 미등재
케이스를 아무도 탐지하지 않았다. 아래 「케이스 이름 입도」 절이 그 둘을 닫는다: 하한은
생성기 안에 두되(리더 판정 — 상류 없음, 제2 정본 금지) **이 파일이 생성기 밖에서**
하한과 실물 스냅샷의 케이스 이름 집합을 섹션마다 동등으로 대조한다.

## 파일 격리 (절대 규칙)

모든 테스트는 tmp_path 사본으로만 돈다. 실물 스냅샷·하한 파일에 쓰는 테스트를 만들지
마라 — 이 저장소에서 테스트가 실물 하한 파일을 덮어쓴 사고가 실제로 있었다
(`tests/test_parity_ci_gate.py` 의 `marker` fixture 주석이 그 사고의 기록이다).
`--write` 는 여기서 절대 부르지 않는다. 사본을 향해도 부르지 않는다 — 쓰기 경로가
테스트에 들어오는 순간 다음 사람이 경로 격리를 빠뜨렸을 때의 피해가 '오탐'에서
'정본 오염'으로 바뀐다.
"""

import importlib.util
import json
import shutil
import sys
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

REPO = Path(__file__).resolve().parents[1]
_GENERATOR = REPO / ".claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py"

#: 프롬프트 스냅샷에서 케이스 배열을 갖는 그룹. 생성기의 `PROMPT_CASE_FLOOR` 와 같은
#: 키여야 한다 — 어긋나면 아래 대조 테스트가 잡는다.
_SECTIONS = ("system_prompts", "user_prompts", "repair_prompts", "postprocess")


def _load_generator() -> ModuleType:
    """생성기를 모듈로 적재한다. 경로가 바뀌면 **건너뛰지 않고 실패**한다."""
    assert _GENERATOR.exists(), (
        f"{_GENERATOR} 가 없다 — 생성기가 옮겨졌거나 사라졌다. 이 하한이 무엇을 지키는지 "
        "사람이 다시 확인해야 하므로 조용히 건너뛰지 않는다."
    )
    name = "dump_python_snapshots_under_test"
    spec = importlib.util.spec_from_file_location(name, _GENERATOR)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def generator() -> ModuleType:
    return _load_generator()


@pytest.fixture
def snapshots(
    generator: ModuleType, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Path, Path]:
    """실물 스냅샷의 tmp 사본을 만들고 생성기의 경로 상수를 사본으로 돌린다."""
    prompt = tmp_path / generator.PROMPT_SNAPSHOT.name
    style = tmp_path / generator.STYLE_SNAPSHOT.name
    shutil.copyfile(generator.PROMPT_SNAPSHOT, prompt)
    shutil.copyfile(generator.STYLE_SNAPSHOT, style)
    monkeypatch.setattr(generator, "PROMPT_SNAPSHOT", prompt)
    monkeypatch.setattr(generator, "STYLE_SNAPSHOT", style)
    return prompt, style


def _rewrite(path: Path, mutate: Any) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    mutate(document)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def test_손대지_않은_스냅샷은_check를_통과한다(
    generator: ModuleType, snapshots: tuple[Path, Path]
) -> None:
    """대조군. 이것이 없으면 아래 실패 단언들이 **무엇 때문에** 실패했는지 알 수 없다."""
    assert generator.main(["--check"]) == 0


def test_케이스_축소_스냅샷은_check가_막는다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    capsys: pytest.CaptureFixture[str],
) -> None:
    """P-A 실측 시나리오 그대로 — 45 케이스를 4 로 줄인다(그룹마다 첫 하나만 남긴다).

    예전 생성기는 이 입력에 **diff 0** 을 보고했다. 축소분을 케이스 원장으로 그대로
    믿고 재생산했기 때문이다. 지금은 `PROMPT_CASE_FLOOR` 대조가 `SnapshotError` 로
    끊어야 한다 — 부분 결과를 쓰지 않는다(exit 2).
    """
    prompt, _style = snapshots

    def truncate(document: dict[str, Any]) -> None:
        for section in _SECTIONS:
            document[section] = document[section][:1]

    _rewrite(prompt, truncate)

    code = generator.main(["--check"])

    captured = capsys.readouterr()
    assert code != 0, "45→4 축소 스냅샷이 --check 를 통과했다 — X1 재발이다"
    assert "사라졌다" in captured.err, captured.err


def test_케이스_한_건_삭제도_막는다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    capsys: pytest.CaptureFixture[str],
) -> None:
    """전면 축소만 잡는 검사가 되지 않게 한다 — 한 건짜리 표적 삭제도 같은 축이다."""
    prompt, _style = snapshots

    def drop_one(document: dict[str, Any]) -> None:
        document["postprocess"] = [
            case
            for case in document["postprocess"]
            if case["name"] != "double_preamble_both_removed"
        ]

    _rewrite(prompt, drop_one)

    code = generator.main(["--check"])

    captured = capsys.readouterr()
    assert code != 0
    assert "double_preamble_both_removed" in captured.err, captured.err


def test_파생_키_단독_삭제를_check가_막는다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    capsys: pytest.CaptureFixture[str],
) -> None:
    """`GLOSS_COLLISION_PATTERN_GLOSSES` 만 지운 스냅샷 — 예전에는 diff 0 이었다.

    파생 키 재생성이 `if derived in previous:` 조건부였기 때문이다. 무조건 재생성으로
    바뀐 지금은 재생성본에는 있고 파일에는 없어 [갈림](exit 1)으로 잡혀야 한다.
    """
    _prompt, style = snapshots

    def drop_derived(document: dict[str, Any]) -> None:
        del document["GLOSS_COLLISION_PATTERN_GLOSSES"]

    _rewrite(style, drop_derived)

    code = generator.main(["--check"])

    captured = capsys.readouterr()
    assert code != 0, "파생 키를 지운 스냅샷이 --check 를 통과했다 — X1 재발이다"
    assert "[갈림]" in captured.out, captured.out + captured.err


def test_하한_그룹이_스냅샷_그룹과_같다(generator: ModuleType) -> None:
    """`PROMPT_CASE_FLOOR` 의 키가 이 테스트의 `_SECTIONS` 와, 그리고 실물 스냅샷의
    케이스 그룹과 어긋나지 않는지 고정한다. 하한이 없는 그룹은 보호 밖이다 — 그룹이
    늘었는데 하한이 안 늘면 여기서 걸린다."""
    assert set(generator.PROMPT_CASE_FLOOR) == set(_SECTIONS)
    document = json.loads(generator.PROMPT_SNAPSHOT.read_text(encoding="utf-8"))
    present_sections = {
        key
        for key, value in document.items()
        if isinstance(value, list) and value and isinstance(value[0], dict)
    }
    assert present_sections == set(_SECTIONS), (
        "실물 스냅샷의 케이스 그룹이 하한의 그룹과 다르다 — 새 그룹을 더했다면 "
        "PROMPT_CASE_FLOOR 와 이 테스트의 _SECTIONS 에도 같은 커밋에서 적어라"
    )


# ---------------------------------------------------------------------------
# 케이스 **이름** 입도 (게이트 16 E·G — 충돌 F 리더 판정: Claude 처방)
#
# 위 그룹 대조는 "섹션 키가 같다"까지만 봤다. 그래서 두 구멍이 남았다(양 관점 실측 합의):
#   E. 하한 한 섹션을 `()` 로 비우면 그 섹션은 요구하는 것이 없어 어떤 축소도 통과했다
#      (postprocess 29→1 / system_prompts 6→1 이 각각 --check exit 0).
#   G. 실물에 있는데 하한에 없는 케이스는 아무도 탐지하지 않았다 — 하한의 보호 밖에
#      조용히 살다가, 지워져도 아무 데서도 빨개지지 않는다.
#
# 리더 판정: `PROMPT_CASE_FLOOR` 는 생성기 안에 그대로 둔다(케이스 입력은 큐레이션이라
# 재계산할 상류가 없음이 두 관점에서 재현됨). 제2 정본 파일을 만들지 않는다 — 그것은
# 이중 기재 드리프트를 새로 여는 방향이다. 대신 **생성기 밖의 이 테스트**가 하한을 실물
# 스냅샷의 케이스 이름 집합과 **양방향 동등**으로 대조한다. 방향을 명시한다:
#   하한 ⊆ 실물 은 생성기 자신이 강제한다(`_floor_checked_cases` → SnapshotError).
#   실물 ⊆ 하한 은 **여기서** 강제한다 — 실물이 하한을 초과하면 그 케이스가 보호 밖이라는
#   뜻이고, 같은 커밋에서 하한을 갱신하라고 여기서 빨개진다.
# 둘을 합쳐 동등이다. 하한이 비면(E) 실물과 같을 수 없으니 여기서도 걸리고, 생성기 쪽도
# 빈 하한을 별도로 실패시킨다(범위 선언형 — 빈 선언에서 통과하면 안 된다).
# ---------------------------------------------------------------------------


def test_하한이_실물_케이스_이름과_섹션마다_같다(generator: ModuleType) -> None:
    """케이스 이름 입도의 정체성 고정 — 실물 스냅샷은 손대지 않고 읽기만 한다."""
    document = json.loads(generator.PROMPT_SNAPSHOT.read_text(encoding="utf-8"))
    for section in _SECTIONS:
        floor = generator.PROMPT_CASE_FLOOR[section]
        assert floor, f"`{section}` 의 하한이 비어 있다 — 비면 무엇을 지우든 통과한다"
        assert len(set(floor)) == len(floor), f"`{section}` 하한에 중복 이름이 있다: {floor}"
        # 실물도 **목록**으로 본다 — 집합으로 접으면 같은 이름 둘이 하나로 보여, 케이스를
        # 지우고 다른 케이스를 복제해 수를 맞춘 편집이 "동등"으로 통과한다(게이트 17 X17-3).
        # 하한⊆실물·실물⊆하한 이 이름 집합 수준에서만 성립하고 개수 수준에서는 아니었다.
        name_list = [str(case["name"]) for case in document[section]]
        duplicated = sorted({name for name in name_list if name_list.count(name) > 1})
        assert not duplicated, (
            f"`{section}` 실물에 같은 이름의 케이스가 둘 이상 있다: {duplicated} — 이름은 "
            "케이스의 정체성이다. 생성기 --check 도 같은 자리를 SnapshotError 로 막는다"
        )
        # 중복이 없고 아래 두 포함이 성립하면 개수 동등은 따라온다 — 따로 세지 않는다.
        actual_names = set(name_list)
        unprotected = sorted(actual_names - set(floor))
        assert not unprotected, (
            f"`{section}` 에 하한이 모르는 케이스가 있다: {unprotected} — 그 케이스는 보호 "
            "밖이라 지워져도 아무 데서도 빨개지지 않는다. PROMPT_CASE_FLOOR 에 같은 커밋에서 적어라"
        )
        vanished = sorted(set(floor) - actual_names)
        assert not vanished, (
            f"`{section}` 하한이 요구하는 케이스가 실물에 없다: {vanished} — "
            "생성기 --check 도 같은 자리를 SnapshotError 로 막는다"
        )


def test_빈_섹션_하한은_check가_막는다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """E 본체 — 하한 한 섹션을 `()` 로 비우고 그 섹션을 축소해도 예전에는 exit 0 이었다.

    하한 상수는 monkeypatch 로 **테스트 안에서만** 바꾼다. 스냅샷 사본의 postprocess 를
    29→1 로 줄인다(Claude 실측 시나리오 그대로).
    """
    prompt, _style = snapshots
    floor = dict(generator.PROMPT_CASE_FLOOR)
    floor["postprocess"] = ()
    monkeypatch.setattr(generator, "PROMPT_CASE_FLOOR", floor)

    def shrink_postprocess(document: dict[str, Any]) -> None:
        document["postprocess"] = document["postprocess"][:1]

    _rewrite(prompt, shrink_postprocess)

    code = generator.main(["--check"])

    captured = capsys.readouterr()
    assert code != 0, "빈 섹션 하한 + 29→1 축소가 --check 를 통과했다 — 게이트 16 E 재발이다"
    assert "하한이 비어 있다" in captured.err, captured.err


def test_빈_섹션_하한은_축소_없이도_check가_막는다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """빈 선언은 실물이 온전해도 실패다 — 범위 선언형은 빈 선언에서 통과하면 안 된다."""
    floor = dict(generator.PROMPT_CASE_FLOOR)
    floor["system_prompts"] = ()
    monkeypatch.setattr(generator, "PROMPT_CASE_FLOOR", floor)

    code = generator.main(["--check"])

    captured = capsys.readouterr()
    assert code != 0
    assert "하한이 비어 있다" in captured.err, captured.err


def test_하한에_없는_케이스가_실물에_생기면_가드가_막는다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """G 본체 — 실물이 하한을 초과하는 케이스를 **탐지**한다.

    실물 스냅샷을 건드리지 않으려고 `PROMPT_SNAPSHOT` 은 이미 사본을 가리킨다(`snapshots`
    fixture). 사본에 하한이 모르는 케이스를 하나 더한 뒤 위 동등 테스트의 본체를 그대로
    부른다 — 같은 함수가 실물에서는 초록이고 여기서는 빨개져야 그 함수가 실제로 재는 것이다.
    """
    prompt, _style = snapshots

    def add_unlisted(document: dict[str, Any]) -> None:
        document["postprocess"].append(
            {"name": "unlisted_case", "raw": "본문", "note": "하한 미등재", "expected": "본문"}
        )

    _rewrite(prompt, add_unlisted)

    with pytest.raises(AssertionError, match="unlisted_case"):
        test_하한이_실물_케이스_이름과_섹션마다_같다(generator)


# ---------------------------------------------------------------------------
# 케이스 이름 **중복** (게이트 17 X17-3) · 하한 키 누락 메시지 갈래 (P17-3)
#
# 위 동등 검사는 이름을 집합으로 접었다. 그래서 케이스 하나를 지우고 다른 케이스를 복제해
# 수를 맞추면 — 이름 집합은 그대로라 — 하한⊆실물·실물⊆하한 이 둘 다 성립했고 생성기 --check
# 도 재생성 diff 0 이었다. 이름은 케이스의 정체성이므로 겹치면 개수를 잃는다. 가드와 생성기
# 양쪽에서 실패로 만든다 — 어느 한쪽만 고치면 다음 사람이 나머지를 그대로 쓴다.
# ---------------------------------------------------------------------------


def _duplicate_case(document: dict[str, Any]) -> None:
    """postprocess 의 마지막 케이스를 지우고 첫 케이스를 복제한다 — 개수는 29 그대로다."""
    cases = document["postprocess"]
    cases.pop()
    cases.append(dict(cases[0]))


def test_실물_케이스_이름_중복을_가드가_막는다(
    generator: ModuleType, snapshots: tuple[Path, Path]
) -> None:
    prompt, _style = snapshots
    _rewrite(prompt, _duplicate_case)

    with pytest.raises(AssertionError, match="같은 이름의 케이스"):
        test_하한이_실물_케이스_이름과_섹션마다_같다(generator)


def test_실물_케이스_이름_중복을_check가_막는다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    capsys: pytest.CaptureFixture[str],
) -> None:
    """예전 생성기는 복제 케이스를 그대로 재생산해 diff 0·exit 0 이었다(codex 실행 확인)."""
    prompt, _style = snapshots
    _rewrite(prompt, _duplicate_case)

    code = generator.main(["--check"])

    captured = capsys.readouterr()
    assert code != 0, "복제 케이스로 수를 맞춘 스냅샷이 --check 를 통과했다 — X17-3 재발이다"
    assert "같은 이름의 케이스" in captured.err, captured.err


def test_하한_키_누락과_빈_하한은_메시지가_다르다(
    generator: ModuleType,
    snapshots: tuple[Path, Path],
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """P17-3 — 둘 다 exit 2 지만 원인이 다르다. 진단이 같으면 사람이 엉뚱한 자리를 고친다."""
    floor = dict(generator.PROMPT_CASE_FLOOR)
    del floor["repair_prompts"]
    monkeypatch.setattr(generator, "PROMPT_CASE_FLOOR", floor)

    code = generator.main(["--check"])

    captured = capsys.readouterr()
    assert code != 0
    assert "케이스 하한 `PROMPT_CASE_FLOOR` 에 없다" in captured.err, captured.err
    assert "하한이 비어 있다" not in captured.err, captured.err
