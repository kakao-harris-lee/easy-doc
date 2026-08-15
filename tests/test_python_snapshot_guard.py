"""Python 정본 스냅샷 생성기의 **삭제 탐지력**을 상시 회귀로 고정한다 (게이트 15 X1).

## 왜 이 파일이 있는가

`dump_python_snapshots.py --check` 는 재생성 diff 0 을 "지금 Python 이 이 값을 낸다"의
증명으로 쓰고, 그 증명이 L344·L345 승격의 근거였다. 그런데 예전 생성기는 previous
스냅샷을 케이스 원장으로 그대로 믿어서, 케이스를 45→4 로 덜어 낸 스냅샷도, 파생 키
(`GLOSS_COLLISION_PATTERN_GLOSSES`, 123 항목)를 지운 스냅샷도 재생성 diff 0 이었다 —
검사기가 위조를 승인했다(교차 종합 X1, P-A·C15-5 양쪽 독립 실측). 그 수정(케이스 하한
`PROMPT_CASE_FLOOR` + 파생 키 무조건 재생성)이 다시 무력해지면 여기가 빨개진다.

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
