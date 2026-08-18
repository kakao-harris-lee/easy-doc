#!/usr/bin/env python3
"""Kotlin 스냅샷 테스트가 대조하는 **Python 정본 스냅샷**을 다시 만든다.

## 왜 저장소에 있는가 (X-9 — `app/**` 삭제 전 마감)

`python-prompt-snapshot.json` 과 `python-style-rules-snapshot.json` 은 Kotlin 이 프롬프트
문면과 스타일 규칙 표를 옳게 옮겼는지 판정하는 **정본**이다. 그런데 그 두 파일을 만든
절차가 어디에도 없었다 — 값은 있는데 **그 값을 다시 만들 방법이 없는** 상태였다.

`app/**` 는 폐기 대상이다. 지금이 그 절차를 코드로 남길 수 있는 **마지막 창**이고, 창이
닫히면 스냅샷은 출처를 확인할 수 없는 상수 더미가 된다. 246개 어려운 말 사전과 123개 패턴
글로스는 실측 튜닝의 결과라 재도출이 비싸다.

## 무엇을 다시 만들고 무엇을 보존하는가

| 갈래 | 출처 |
|---|---|
| 상수 표(사전·글로스·원칙 등) | `app/easyread/style_rules.py` 에서 **이름으로 읽는다** |
| 프롬프트 문구 상수 | `app/easyread/prompts.py` 에서 이름으로 읽는다 |
| 케이스별 `expected` | Python 함수를 **실제로 호출해** 얻는다 |
| 케이스 **입력**(name·source_text·raw 등) | 기존 스냅샷에서 그대로 가져온다 |

마지막 줄이 중요하다. 케이스 입력은 Python 에서 유도되는 값이 아니라 **사람이 고른
큐레이션**이다. 그래서 이 생성기는 케이스 **입력값**을 기존 스냅샷에서 가져오고 출력만
다시 계산한다. 그 결과 재생성 diff 가 0 이면 "지금 Python 이 이 값을 낸다"가 증명된다.

단, **케이스 열거의 정본은 기존 스냅샷이 아니라 `PROMPT_CASE_FLOOR` 다** (게이트 15 X1).
예전에는 previous 를 케이스 원장으로 그대로 믿어서, 케이스를 덜어 낸 스냅샷(실측 45→4)을
넣으면 그 축소분을 그대로 재생산하고 diff 0 을 보고했다 — 검사기가 위조를 승인했다.
지금은 하한 튜플에 있는 케이스가 previous 에 없으면 `SnapshotError` 로 끝난다.

## 실패는 명시적이다 (N-13 전례)

모듈·이름·함수가 하나라도 없으면 **`SnapshotError` 로 끝난다.** 조용히 빠뜨리고 나머지만
쓰면 그 순간 스냅샷이 줄어들고, 줄어든 스냅샷은 Kotlin 검사를 통과시킨다.

## 본문을 출력하지 않는다

프로젝트 보안 규칙이 여기에도 적용된다. 표준 출력에 내는 것은 **키 이름과 개수**뿐이고,
프롬프트·문서 본문은 파일에만 쓴다(그 파일은 이미 저장소에 있는 정본이다).

사용:

    uv run python .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py --check
    uv run python .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py --write
"""

from __future__ import annotations

import argparse
import json
import secrets
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[4]
SNAPSHOT_DIR = REPO_ROOT / "backend-kotlin/core/src/test/resources/kr/easydoc/core/easyread"
PROMPT_SNAPSHOT = SNAPSHOT_DIR / "python-prompt-snapshot.json"
STYLE_SNAPSHOT = SNAPSHOT_DIR / "python-style-rules-snapshot.json"

#: `style_rules` 에서 이름으로 읽어 오는 상수. **순서까지 스냅샷의 일부**다 —
#: 사전 순서가 바뀌면 프롬프트의 낱말 나열 순서가 바뀐다.
STYLE_CONSTANTS = (
    "MAX_SENTENCE_CHARS",
    "MAX_COMMAS_PER_SENTENCE",
    # 스냅샷 키는 공개 이름인데 모듈 쪽은 밑줄 접두다. 별칭을 여기 한 곳에 적는다 —
    # 두 이름을 코드 여기저기서 갈아 끼우면 다음 사람이 어느 쪽이 정본인지 못 고른다.
    "COMMA_CHARS",
    "DOUBLE_PASSIVE_PATTERNS",
    "DIFFICULT_WORD_REPLACEMENTS",
    "PROMPT_ONLY_WORDS",
    "STYLE_PRINCIPLES",
    "LEXICALIZED_GLOSSES",
    "COMPOUND_TAIL_KEYS",
    "COMPOUND_HEAD_NOUNS",
    "NOMINAL_GLOSSES",
    "MODIFIER_CHECKED_GLOSSES",
)

#: `prompts` 에서 이름으로 읽어 오는 문구 상수.
PROMPT_CONSTANTS = (
    "DOCUMENT_TAG_NAME",
    "CONVERTED_TAG_NAME",
    "_DOCUMENT_ID_BYTES",
    "_ROLE",
    "_LENGTH_INSTRUCTION",
    "_SPLIT_EXAMPLES",
    "_REPLACEMENT_INSTRUCTION",
    "PLACEHOLDER_INSTRUCTION",
    "_SELF_CHECK_INSTRUCTION",
    "_CONDITIONAL_INSTRUCTION",
    "INJECTION_GUARD",
    "_OUTPUT_INSTRUCTION",
    "_REPAIR_ROLE",
    "_REPAIR_INSTRUCTION",
)


#: 스냅샷 키 → 모듈 안의 실제 이름. 비공개(밑줄) 상수를 공개 키로 싣는 자리다.
STYLE_NAME_ALIASES = {"COMMA_CHARS": "_COMMA_CHARS"}

#: 프롬프트 스냅샷의 **케이스 하한** — 케이스 열거의 정본은 previous 스냅샷이 아니라
#: 이 튜플이다 (게이트 15 X1 · P-A). 케이스 입력은 큐레이션이라 `app/**` 에서 재계산할
#: 수 없으므로 값은 previous 에서 가져오되, 어떤 케이스가 있어야 하는지는 여기가 정한다 —
#: `STYLE_CONSTANTS` 가 상수 이름 축을 끊는 것과 같은 층에서 케이스 축을 끊는다
#: (P-A: "상수 이름 축은 `SnapshotError` 로 끊었는데 케이스 축은 끊지 않았다").
#:
#: 비대칭 하한이다. **삭제·개명은 `SnapshotError`**(그 케이스가 지키던 성질이 조용히
#: 사라진다), **추가는 허용** — 스냅샷에 새 케이스를 넣으면 같은 커밋에서 여기에도 적는다.
#: 적는 것을 잊으면 그 케이스는 하한의 보호 밖이다(추가 자체는 막지 않는다).
PROMPT_CASE_FLOOR: dict[str, tuple[str, ...]] = {
    "system_prompts": (
        "empty",
        "no_difficult_words",
        "scattered_difficult_words",
        "prompt_only_word_present",
        "compound_interior_only",
        "masked_placeholder_survives",
    ),
    "user_prompts": (
        "plain",
        "empty",
        "multiline",
        "tag_injection_attempt",
        "masked_placeholder_survives",
    ),
    "repair_prompts": (
        "empty_violations",
        "single_sentence_multiple_reasons",
        "two_sentences_grouped",
        "word_not_in_dictionary",
        "gloss_sort_order",
    ),
    "postprocess": (
        "plain_body",
        "empty",
        "whitespace_only",
        "surrounding_whitespace",
        "nbsp_padding",
        "fence_plain",
        "fence_with_language",
        "fence_markdown",
        "fence_open_only",
        "fence_close_only",
        "fence_close_trailing_spaces",
        "fence_inner_preserved",
        "preamble_colon",
        "preamble_conversion_result",
        "preamble_changed_result",
        "preamble_easy_text",
        "preamble_colon_with_spaces",
        "preamble_then_fence",
        "fence_then_preamble",
        "keep_review_result",
        "keep_application_method",
        "keep_required_items",
        "keep_mid_line_colon",
        "keep_signal_without_start",
        "keep_start_without_signal",
        "keep_single_line_preamble",
        "keep_preamble_not_first",
        "keep_body_mentions_easy_text",
        "double_preamble_both_removed",
    ),
}


class SnapshotError(RuntimeError):
    """추출에 실패했다. **부분 결과를 쓰지 않는다.**"""


def _attr(module: Any, name: str) -> Any:
    if not hasattr(module, name):
        raise SnapshotError(
            f"{module.__name__} 에 `{name}` 이 없다 — 이름이 바뀌었거나 삭제됐다. "
            "스냅샷을 줄여서 통과시키지 않는다"
        )
    return getattr(module, name)


def _jsonable(value: Any) -> Any:
    """튜플·집합을 JSON 형태로. 사전은 `[키, 값]` 쌍 목록이 정본 형식이다."""
    # `MappingProxyType`(읽기 전용 사전)도 받는다 — `dict` 검사만 하면 그것이 통째로
    # 빠져나가 직렬화에서 죽는다(실측).
    if isinstance(value, Mapping):
        return [[key, item] for key, item in value.items()]
    if isinstance(value, (tuple, set, frozenset)):
        return sorted(value) if isinstance(value, (set, frozenset)) else list(value)
    return value


def build_style_snapshot(previous: dict[str, Any]) -> dict[str, Any]:
    """스타일 규칙 표. 파생 표(`GLOSS_COLLISION_PATTERN_GLOSSES`)도 함수로 다시 만든다.

    `previous` 는 더 이상 읽지 않는다 — 스타일 쪽은 전 키가 `app` 상수에서 재계산되므로
    previous 를 믿을 이유가 없다(믿었던 자리가 X1 의 파생 키 구멍이었다). 인자는 두
    빌더의 서명을 맞추기 위해서만 남아 있다(`main` 의 targets 루프가 같은 형태로 부른다).
    """
    from app.easyread import style_rules

    built: dict[str, Any] = {}
    for name in STYLE_CONSTANTS:
        built[name] = _jsonable(_attr(style_rules, STYLE_NAME_ALIASES.get(name, name)))

    # 파생 표는 **무조건** 다시 만든다. 예전에는 `if derived in previous:` 뒤에 있어서
    # 그 한 키를 지운 스냅샷이 재생성 diff 0 이었다(게이트 15 X1 — 123 항목이 조용히
    # 사라질 수 있었다). 지금은 항상 다시 만들므로 키를 지우면 재생성본에는 있고
    # 파일에는 없어 `--check` 가 [갈림]으로 잡는다.
    # `GLOSS_COLLISION_PATTERNS` 는 **(글로스, 컴파일된 정규식)** 쌍의 표다.
    # 스냅샷이 싣는 것은 글로스 문면이고, 그것이 Kotlin 쪽 대조 대상이다.
    # 정규식 객체는 직렬화되지 않으므로 여기서 반드시 걸러야 한다 — 순서를 반대로
    # 읽었더니 `Pattern is not JSON serializable` 로 죽었다(실측).
    derived = "GLOSS_COLLISION_PATTERN_GLOSSES"
    patterns = _attr(style_rules, "GLOSS_COLLISION_PATTERNS")
    built[derived] = [gloss for gloss, _regex in patterns]
    return built


def build_prompt_snapshot(previous: dict[str, Any]) -> dict[str, Any]:
    """프롬프트 문면. 케이스 입력은 이전 스냅샷에서, 출력은 Python 호출로."""
    from app.easyread import prompts as prompts_module
    from app.easyread.postprocess import postprocess
    from app.easyread.prompts import (
        build_repair_prompt,
        build_system_prompt,
        build_user_prompt,
    )

    built: dict[str, Any] = {
        "_note": previous["_note"],
        "_fixed_document_id": previous["_fixed_document_id"],
    }
    for name in PROMPT_CONSTANTS:
        built[name] = _jsonable(_attr(prompts_module, name))

    # **문서 id 를 고정한다.** `build_user_prompt`·`build_repair_prompt` 는 매 호출마다
    # `secrets.token_hex` 로 새 id 를 만들어 태그에 박는다. 고정하지 않으면 재생성 diff 가
    # 항상 0 이 아니고, 그러면 이 생성기는 무엇도 증명하지 못한다. 스냅샷이 `_fixed_document_id`
    # 를 따로 싣고 있는 이유가 이것이다 — 그 값을 그대로 되먹인다.
    fixed_id = previous["_fixed_document_id"]
    # `prompts` 는 `import secrets` 로 **모듈 객체를 공유**하므로 여기서 그 모듈을 직접
    # 고쳐도 같은 것을 본다. `prompts.secrets` 로 접근하면 재export 가 아니라고 잡힌다.
    original_token_hex = secrets.token_hex

    def _fixed_token_hex(nbytes: int | None = None) -> str:  # noqa: ARG001 — 서명 호환용
        return str(fixed_id)

    secrets.token_hex = _fixed_token_hex
    try:
        return _build_prompt_cases(
            built,
            previous,
            postprocess,
            build_system_prompt,
            build_user_prompt,
            build_repair_prompt,
        )
    finally:
        secrets.token_hex = original_token_hex


def _floor_checked_cases(previous: dict[str, Any], section: str) -> list[dict[str, Any]]:
    """케이스 목록을 `PROMPT_CASE_FLOOR` 와 대조해 넘긴다 — 축소된 previous 를 정본으로
    재사용하지 않는다 (게이트 15 X1).

    입력값은 previous 에서 오지만 열거의 정본은 하한 튜플이다. 하한에 있는 케이스가
    previous 에 없으면 그 입력은 큐레이션이라 재계산할 수 없으므로, 부분 결과를 쓰지
    않고 `SnapshotError` 로 끝난다(N-13 전례와 같은 원칙이다).
    """
    raw = previous.get(section)
    if not isinstance(raw, list):
        raise SnapshotError(
            f"`{section}` 케이스 배열이 스냅샷에 없다 — 스냅샷이 잘렸거나 형식이 바뀌었다. "
            "축소된 스냅샷을 정본으로 재사용하지 않는다"
        )
    # **빈 하한은 실패다** (게이트 16 E — 범위 선언형은 빈 선언에서 통과하면 안 된다).
    # 하한이 `()` 면 위 대조는 요구하는 것이 없어 어떤 축소도 통과했다(실측: postprocess
    # 29→1 / system_prompts 6→1 이 각각 exit 0). 하한을 비우는 편집은 하한을 지우는
    # 편집과 같은 일이므로 같은 방식으로 막는다 — 섹션을 정말 없앨 거면 `PROMPT_CASE_FLOOR`
    # 에서 키째로 지우고, 그 diff 를 리뷰에 올린다(가드 테스트가 그룹 대조로 잡는다).
    required = PROMPT_CASE_FLOOR.get(section)
    if not required:
        raise SnapshotError(
            f"`{section}` 의 케이스 하한이 비어 있다 — 하한이 비면 무엇을 지우든 통과한다. "
            "비우는 것은 지우는 것과 같은 일이므로 똑같이 실패로 판정한다"
        )
    cases = [case for case in raw if isinstance(case, dict)]
    present = {str(case.get("name")) for case in cases}
    missing = [name for name in required if name not in present]
    if missing:
        raise SnapshotError(
            f"`{section}` 에서 케이스 {len(missing)}건이 사라졌다: {', '.join(missing)} — "
            "케이스 입력은 큐레이션이라 재계산할 수 없다. 삭제·개명이 의도라면 "
            "`PROMPT_CASE_FLOOR` 를 같은 커밋에서 고치고 근거를 리뷰에 올린다"
        )
    return cases


def _build_prompt_cases(
    built: dict[str, Any],
    previous: dict[str, Any],
    postprocess: Any,
    build_system_prompt: Any,
    build_user_prompt: Any,
    build_repair_prompt: Any,
) -> dict[str, Any]:
    """케이스별 출력을 채운다. 문서 id 가 고정된 상태에서만 불린다."""
    built["system_prompts"] = [
        {**case, "expected": build_system_prompt(case["masked_text"])}
        for case in _floor_checked_cases(previous, "system_prompts")
    ]
    built["user_prompts"] = [
        {**case, "expected": build_user_prompt(case["masked_text"])}
        for case in _floor_checked_cases(previous, "user_prompts")
    ]
    built["repair_prompts"] = [
        _repair_case(case, build_repair_prompt)
        for case in _floor_checked_cases(previous, "repair_prompts")
    ]
    built["postprocess"] = [
        {**case, "expected": postprocess(case["raw"])}
        for case in _floor_checked_cases(previous, "postprocess")
    ]
    return built


def _repair_case(case: dict[str, Any], builder: Any) -> dict[str, Any]:
    """보정 프롬프트 한 건. 위반 목록을 `SentenceIssue` 로 되살려 넣는다."""
    from app.easyread.style_rules import SentenceIssue

    issues = [
        SentenceIssue(**{key: value for key, value in item.items()})
        for item in case.get("violations", [])
    ]
    system, user = builder(case["converted_text"], issues)
    rebuilt = {**case, "expected_system": system}
    if "expected_user" in case:
        rebuilt["expected_user"] = user
    return rebuilt


def _dump(value: dict[str, Any]) -> str:
    """정본과 **같은 직렬화**여야 diff 가 의미를 갖는다."""
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true", help="재생성 diff 가 0 인지만 본다")
    group.add_argument("--write", action="store_true", help="스냅샷 파일을 다시 쓴다")
    args = parser.parse_args(argv)

    sys.path.insert(0, str(REPO_ROOT))
    targets = (
        (PROMPT_SNAPSHOT, build_prompt_snapshot),
        (STYLE_SNAPSHOT, build_style_snapshot),
    )

    drifted: list[str] = []
    for path, builder in targets:
        if not path.is_file():
            print(f"정본 스냅샷이 없다: {path}", file=sys.stderr)
            return 2
        previous = json.loads(path.read_text(encoding="utf-8"))
        try:
            rebuilt = builder(previous)
        except SnapshotError as exc:
            print(f"추출 실패 ({path.name}): {exc}", file=sys.stderr)
            return 2
        except ImportError as exc:
            print(
                f"Python 런타임을 읽지 못했다 ({path.name}): {exc}\n"
                "app/** 가 이미 지워졌다면 이 생성기는 더 이상 돌 수 없다 — "
                "그것이 X-9 가 '되돌릴 수 없는 창'이라고 부른 상태다.",
                file=sys.stderr,
            )
            return 2

        rendered = _dump(rebuilt)
        if args.write:
            path.write_text(rendered, encoding="utf-8")
            print(f"[갱신] {path.name} — 키 {len(rebuilt)}개")
            continue
        if rendered != path.read_text(encoding="utf-8"):
            drifted.append(path.name)
            print(f"[갈림] {path.name} — 재생성 결과가 커밋된 정본과 다르다")
        else:
            print(f"[일치] {path.name} — 키 {len(rebuilt)}개")

    if drifted:
        print(
            "\n재생성 diff 가 0 이 아니다. **정본을 덮어쓰기 전에 왜 갈렸는지 먼저 찾는다** — "
            "Python 이 바뀐 것인지, 이 생성기가 정본의 형식을 못 맞춘 것인지 가른다.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
