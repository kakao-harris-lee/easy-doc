#!/usr/bin/env python3
"""마이그레이션 데이터 보호 불변식 기계 스캔 (Python + Kotlin 소스 동시).

이 스크립트는 **판정하지 않는다.** 위반 후보를 모아 사람 앞에 놓을 뿐이다.
정규식은 문맥을 읽지 못하므로 오탐이 반드시 섞인다 — 자동 차단에 쓰면 곧
"어차피 오탐"이라며 전체를 무시하게 되고, 그때 진짜 유출이 지나간다.

실행:
    uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py
    # 변경분만 (Phase 진행 중 빠른 회전). 기본 base는 main — 브랜치에 **커밋된** 변경도 포함한다.
    uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py --changed
    uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py \
        --changed --base origin/main
    # 특정 규칙만 / 마크다운 리포트
    uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py \
        --rule LOG-BODY --report-md docs/migration/_workspace/07_privacy-gate_scan.md

종료 코드: 0 = BLOCK 후보 없음, 1 = BLOCK 후보 있음(사람 확인 필요),
          2 = 입력 오류(**선언한 스캔 루트 부재·빈 루트 포함**),
3 = `--changed` 범위가 비어 아무것도 검사하지 못함. "검사하지 않음"을 "통과"로 읽으면 게이트가
무의미해지므로 실패시킨다 — 정말 빈 것이 정상이면 `--allow-empty`.
`--no-fail`을 주면 BLOCK 후보가 있어도 0으로 끝난다(리포트 수집 용도).
"""

from __future__ import annotations

import argparse
import hashlib
import math
import os
import re
import subprocess
import sys
import unicodedata
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]

#: `--changed`의 기준 ref. 이 값이 없으면 브랜치에 **커밋된** 변경이 통째로 검사에서 빠진다.
DEFAULT_BASE_REF = "main"

SCAN_ROOTS = ["app", "backend-kotlin", "scripts", "frontend/src"]
SUFFIXES = {".py", ".kt", ".kts", ".ts", ".tsx", ".java"}
SKIP_PARTS = {
    "__pycache__",
    "node_modules",
    ".git",
    "build",
    "dist",
    "target",
    ".gradle",
    ".venv",
    "venv",
    "generated",
}

#: 본문·개인정보를 담을 법한 식별자. 로그·예외 메시지에 이 이름이 보간되면 후보다.
#:
#: **이름을 빼지 않는다 — 더하기만 한다.** 이 목록에서 이름을 지우는 것은 오탐을 줄이는 것이
#: 아니라 **탐지를 줄이는 것**이다(`SECRET_LITERAL` 쪽이 같은 원칙을 이미 적었다). 오탐은
#: 사람이 확인해 넘기면 되지만, 빠진 이름은 아무 신호도 내지 않는다.
#:
#: 2026-08-14 확장 (privacy-gate 판정 5 / §4-bis.4): Kotlin 쪽이 실제로 쓰는 식별자 넷이
#: 빠져 있어 탐침 7건 중 4건을 놓쳤다 — `draft`·`modelDraft`·`reviewed`·`result`.
#: `reviewed` 가 특히 함정이었다. 기존 목록에 `review` 가 있었지만 `\b` 경계 때문에
#: `reviewed` 에는 걸리지 않는다. **부분 문자열이 아니라 낱말 단위로 걸린다**는 것을 잊으면
#: "비슷한 이름이 이미 있으니 잡히겠지"로 넘어가게 된다.
#: 셋은 `Masking.kt` 의 provenance 래퍼가 감싸는 값의 이름이고(`ModelDraft`·`ReviewedBody`),
#: `result` 는 변환 유스케이스의 결과 타입(`ConversionResult`)이 본문을 들고 다니는 이름이다.
#: 래퍼의 `toString()` 은 가려 두었지만 `.value` 를 직접 꺼내 넘기는 줄은 타입으로 닫히지
#: 않으므로, 그 절반을 이 목록이 맡는다.
BODY_NAMES = (
    r"text|body|content|source_text|sourceText|easy_text|easyText|masked_text|maskedText|"
    r"original|plaintext|plain_text|raw|password|secret|token|email|payload|prompt|"
    r"converted|review|comment|title|filename|"
    # 2026-08-14 추가 — 위 주석의 사유 참고.
    r"draft|modelDraft|model_draft|reviewedBody|reviewed_body|reviewed|"
    r"edited_text|editedText|result"
)
#: 호출 지점 **가시 표기**. 억제는 이 표기로만 이뤄진다.
#:
#: ## 왜 중앙 휴리스틱을 걷어냈나 (privacy-gate 판정 §4-octies)
#:
#: 앞선 판은 `LOG-BODY`에 `refine` 훅을 두어 "값의 성질"로 오탐을 눌렀다. 그 훅은
#: **경로가 아니라 값을 본다**는 원칙을 끝까지 지켰고 목록이 넓어져 샌 갈래는 하나도
#: 없었다. **그런데 다섯 갈래가 났다 — 고른 축이 위험을 가르는 축이 아니었다.**
#:
#: 위험을 가르는 축은 이것이다:
#:
#: > **억제가 아래 층(어휘·구문)의 정확성에 의존하는가.** 의존하면 그 층의 모든 결함을
#: > **조용한 통과로 상속한다.** 인자 구간을 잘못 자르면 훅은 **잘린 조각을 보고**
#: > 정직하게 "안전"을 판정한다 — 판정은 옳게 수행됐고 결과는 거짓이다.
#:
#: 다섯 갈래 중 셋(M-09 메시지 안 괄호 · stop-gate 주석 안 `)` · R-2 중첩 주석)은
#: **훅의 결함이 아니라 lexer 결함**인데 훅이 그것을 조용한 통과로 바꿨다.
#: **탐지 실패는 원리적으로 시끄럽고**(언젠가 누가 못 잡힌 것을 발견한다)
#: **억제 실패는 구조적으로 조용하다**(리포트에 숫자 하나로만 남았다).
#:
#: 표기가 "한 칸 더 옮기기"가 아닌 이유 셋:
#: 1. **파서 버그가 표기를 만들어 낼 수 없다.** 표기는 사람이 타이핑해야만 생긴다.
#:    파싱 결함은 앞으로도 **미검출**을 낼 수 있지만 **조용한 억제는 더 이상 낼 수 없다.**
#: 2. **억제가 코드에 보인다.** diff에 뜨고 리뷰를 지난다.
#: 3. **고아가 되면 실패한다** — 휴리스틱에는 없던 자가 정리 기제.
MARKER_PREFIX = "privacy-allow:"

#: 지문 길이(hex 문자 수). SHA-256 앞 8자 — 표기 한 줄에 눈으로 담기고, 한 파일 안의
#: 호출 수(수십)에서 충돌이 실질적으로 일어나지 않는다. 충돌해도 방향은 닫히는 쪽이다
#: (같은 지문의 적중이 둘이면 불변량 4 위반으로 **아무것도 억제하지 않는다**).
DIGEST_LENGTH = 8

#: 표기 문법: `privacy-allow: <RULE-ID> @<지문8자> — <사유>`.
#: 규칙 id·지문·사유가 **모두 필수**다. 구분자는 em dash 또는 하이픈 둘 다 받는다
#: (편집기·키보드에 따라 갈리는 것을 규칙으로 만들지 않는다).
#: 사유에 **보이는 문자가 [MIN_REASON_VISIBLE] 개 미만**이면 억제하지 않고 스캔도
#: 실패한다(방어 c — 판정은 [has_visible_reason]).
#:
#: ## 지문이 왜 문법에 들어왔는가 (X-1 — §4-octies 설계의 결함)
#:
#: 앞선 판의 표기 키는 `(rule_id, path, physical_line)` 이었다. 그래서 표기는 **그 줄의
#: 그 규칙 적중을 무엇이든** 눌렀다 — 사람이 사유를 쓸 때 본 것과 실제로 눌리는 것이
#: 같다는 보장이 없었다. 두 가지가 조용히 통과했다:
#:
#: - ⓐ 표기 단 호출과 **같은 논리 줄에 두 번째 호출**을 더하면 그것도 눌렸다
#: - ⓑ 표기 단 호출에 **인자를 더해도** 표기는 그대로 눌렀다 (구판 `refine` 은 이것을
#:   잡았다 — 값을 다시 봤기 때문이다. 표기로 옮기며 그 성질을 잃었다)
#:
#: 지문이 그 성질을 되찾는다. 지문은 **그 호출의 인자 구간**에서 나오므로 인자가 바뀌면
#: 표기가 어긋나고, 어긋남은 **항상 닫히는 쪽**(적중 노출 + 고아 표기)으로 실패한다.
#: 사람이 손으로 계산할 수 없으므로 `--update-markers` 가 갱신하되, 그 갱신은 diff 에
#: 한 줄로 떠서 리뷰어가 "이 사유가 새 내용에도 맞나"를 다시 본다.
MARKER_RE = re.compile(
    rf"{re.escape(MARKER_PREFIX)}\s*(?P<rule>[A-Z][A-Z0-9-]*)"
    rf"\s*(?:@(?P<digest>[0-9a-f]{{{DIGEST_LENGTH}}}))?"
    rf"\s*(?:—|-{{1,2}}|:)?\s*(?P<reason>.*)$"
)

#: 저장소 전체 표기 수의 **하드 상한**. 초과하면 스캔이 실패한다.
#:
#: 초기값은 이관 시점 실제 개수 **+ 0** 이다 — 여유를 주지 않는다. 상한을 올리는 것은
#: 그 자체가 diff이고 리뷰 대상이다(방어 a). 이 수가 조용히 자라면 표기는 새 면제 목록이 된다.
#:
#: **설계문(§4-octies.6)은 초기값을 `3` 으로 적었으나 이관 시점 실측은 `7` 이다.**
#: 그 `3` 은 §4-quater.1 시점(훅 도입 직후)의 개수이고, 그 뒤 게이트 09 배치에서 안전 멤버
#: 목록이 넓어져(camelCase 대칭 · `suggested_facts` · `migrationsExecuted` ·
#: `targetSchemaVersion`) 억제 건수가 7로 늘었다 — 그 사실은 그때 리포트에 계속 찍히고
#: 있었다(`LOG-BODY — 값의 모양이 불변식 대상이 아님 7건`). 설계문의 숫자가 낡은 것이고
#: **원칙("실측 + 0")을 적용한 결과가 7**이다. 숫자가 아니라 원칙을 따랐다.
MARKER_BUDGET = 7


#: 사유의 **내용**으로 치는 유니코드 카테고리 접두. 글자(`L*`)와 숫자(`N*`)뿐이다.
#:
#: ## 부정 목록이 아니라 긍정 목록인 이유 (privacy-gate §4-quaterdecies)
#:
#: 앞선 판은 "보이지 않는 것 10종"을 열거해 빼는 **부정 목록**이었다. 유니코드 카테고리는
#: 30종이라 목록 밖 20종이 전부 통과했고, 그중 `Mc`(간격 결합 표시)·`Po`(문장부호)·
#: `Sm`(수학 기호)·`So`(기타 기호) 네 갈래가 **실측으로 뚫려 있었다.**
#:
#: 다음 갈래를 **종류로 댈 수 있다** — "목록에 없는 모든 카테고리". 그런 자리는 열거를
#: 넓히는 것으로 닫히지 않는다. `§4-ter.3` 의 VT/FF 열거 실패와 같은 형태이고, 그때와 같은
#: 처방을 쓴다: **뺄 것을 세지 말고 받을 것을 정한다.**
#:
#: 이것은 새 요구가 아니라 **명세 준수**다. `§4-terdecies.3` 은 처음부터 `L*`/`N*` ≥ 2 로
#: 적혀 있었고 구현이 부정 목록으로 갈라졌다.
_CONTENT_CATEGORY_PREFIXES = ("L", "N")

#: 사유로 인정할 **보이는 문자**의 최소 개수.
#:
#: ## 이 수를 왜 2로 두는가 (근거를 넘지 않는 자리)
#:
#: 실측한 결함은 **보이는 문자 0개**다(ZWSP 단독). 그러니 근거만 따르면 하한은 1이고,
#: 2는 한 칸의 여유다. 그 한 칸이 공짜인 것을 확인하고 골랐다 — 저장소의 실제 표기 7건은
#: 보이는 문자가 **최소 14개**이고, 가장 짧은 합성 사유(테스트)도 2개다. 즉 이 값이
#: 무엇도 건드리지 않으면서 "글자 하나"라는 다음 갈래를 함께 막는다.
#:
#: **더 올리는 것은 별개의 판단이고 근거가 없다.** "사유가 얼마나 길어야 하는가"는 이번에
#: 관측된 결함이 아니며, 올리면 정당한 짧은 사유를 막는 쪽으로 틀린다.
MIN_REASON_VISIBLE = 2


def visible_length(value: str) -> int:
    """사유의 **내용**으로 칠 수 있는 문자의 개수 — 글자(`L*`)와 숫자(`N*`)만 센다.

    `len(value.strip())` 이 아니다. 파이썬 `str.strip` 은 유니코드 **공백**을 지우지만
    **형식 문자(`Cf`)는 지우지 않는다** — ZWSP·WORD JOINER·ZWNJ 가 그대로 남아 길이 1이
    된다. 그래서 "사유가 비었는가"를 truthy 로 물으면 **보이지 않는 사유가 통과한다.**

    실측(2026-08-15):

        "\u200b".strip() -> 길이 1, truthy   (카테고리 Cf)
        "\u2060".strip() -> 길이 1, truthy   (카테고리 Cf)
        " ".strip()       -> 길이 0, falsy    (카테고리 Zs — 이쪽은 원래 걸렸다)
        "...."            -> 카테고리 Po      (부정 목록 시절 통과, 지금은 거부)

    ## 긍정 목록이라 "다음 갈래"가 없다

    앞선 판은 보이지 않는 카테고리 10종을 빼는 방식이었고, 목록 밖 20종이 전부 통과했다 —
    문장부호(`"...."`)·수학 기호·기타 기호·간격 결합 표시가 실측으로 뚫렸다. 지금은 받을
    것만 정하므로 유니코드에 카테고리가 늘어도 기본값이 **거부**다.

    부수적으로 문장부호만인 사유도 함께 막힌다. 그것은 별도 기제를 더한 결과가 아니라
    **긍정 목록의 당연한 귀결**이다.
    """
    return sum(
        1 for char in value if unicodedata.category(char).startswith(_CONTENT_CATEGORY_PREFIXES)
    )


def has_visible_reason(reason: str) -> bool:
    """사유가 사람이 읽을 수 있는 내용을 담았는가. **판정은 여기 한 곳뿐이다.**

    억제([_marker_touches])와 표기 진단([marker_problems])이 같은 함수를 쓴다. 두 벌로 두면
    "억제는 됐는데 진단은 문제없다고 한" 상태가 만들어지고, 그 갈림이 조용한 쪽은 늘 억제다.
    """
    return visible_length(reason) >= MIN_REASON_VISIBLE


@dataclass(frozen=True)
class Marker:
    """한 줄에 달린 억제 표기.

    @param line 표기가 **적힌** 물리 줄. 표기를 **찾을 자리**이지 키가 아니다(e′).
    @param rule_id 이 표기가 누르는 규칙. **그 규칙만** 누른다(방어 d) — 와일드카드가 없다.
    @param digest 누를 호출의 인자 구간 지문 8자. 비면 억제하지 않는다(4′ — 닫힘).
    @param reason 사람이 적은 사유. **보이는 문자**가 모자라면 억제하지 않는다
        (방어 c — [has_visible_reason]). 앞선 판은 이 자리에 *"비면 억제하지 않는다"* 라고
        적혀 있었고 검사도 `str.strip()` 뒤 truthy 였다. 그런데 파이썬의 `strip` 은
        **형식 문자(카테고리 `Cf`)를 지우지 않는다** — ZWSP(`U+200B`) 하나뿐인 사유가
        "비어 있지 않다"로 통과했다. 결함을 설계 의도처럼 적어 두면 다음 수정 때
        되돌려지므로(게이트 13 U-3) 규칙과 문장을 함께 고쳤다.
    @param standalone 그 줄에 코드가 없는가. `True` 면 **바로 아래 줄**의 적중을 누른다.
    """

    line: int
    rule_id: str
    digest: str
    reason: str
    standalone: bool


def parse_markers(comment: str, line: int, standalone: bool) -> list[Marker]:
    """주석 조각에서 표기를 뽑는다. **어휘 층(①)의 일이다.**

    억제 층이 자기 주석 파서를 가지면 중첩 주석 갈래(R-2)를 그대로 되산다 — 주석 판별은
    이미 이 층이 하고 있으므로 여기서 함께 뽑아 색인으로 넘긴다.
    """
    found: list[Marker] = []
    for piece in comment.split(MARKER_PREFIX)[1:]:
        match = MARKER_RE.match(MARKER_PREFIX + piece)
        if match is None:
            continue
        found.append(
            Marker(
                line=line,
                rule_id=match.group("rule"),
                digest=match.group("digest") or "",
                reason=match.group("reason").strip(),
                standalone=standalone,
            )
        )
    return found


#: 로그 호출의 시작 모양.
#:
#: **`_?log(?:ger)?\.` 이지 `_?logger?\.` 가 아니다.** 후자는 읽으면 "logger 에서 r 이
#: 선택적"처럼 보이지만 정규식은 `_?` + `logge` + `r?` + `\.` 로 읽는다 — 즉 `logge.` 와
#: `logger.` 는 잡고 **`log.` 는 못 잡는다.** 이 저장소의 로거 호출 4곳 중 2곳이
#: `GlobalExceptionHandler` 의 `log.error(...)` 이고, 하필 **전역 예외 핸들러**가 탐지
#: 밖이었다 — 예외 메시지가 본문을 실어 나르는 자리라 이 규칙이 가장 필요한 곳이다
#: (게이트 10 R-3).
#:
#: 실제 도달은 `LoggerCallReachTest` 가 **저장소의 그 줄들을 읽어** 상시 확인한다.
#: 패턴만 고치고 도달을 재지 않으면 다음에 이름이 또 갈렸을 때 같은 방식으로 조용해진다.
LOG_CALL = (
    r"(?:_?log(?:ger)?\.(?:debug|info|warning|warn|error|exception|trace)"
    r"|print|println|System\.out\.print)"
)

#: **로그 호출로 보이는 것**의 넓은 정의. 도달 검사가 "무엇을 찾아야 하는가"로 쓴다.
#:
#: ## 왜 탐지 정의(`LOG_CALL`)와 따로 있고, 왜 **여기** 있는가 (Z-1)
#:
#: 둘은 하는 일이 다르다 — [LOG_CALL] 은 **검사할 것**을 고르고 이것은 **검사됐어야 할
#: 것**을 고른다. 그래서 이쪽이 의도적으로 넓다. 같은 패턴으로 찾고 같은 패턴으로 검사하면
#: 그 패턴이 틀렸을 때 둘이 같이 틀려 도달 검사가 **자기 자신을 증명**한다.
#:
#: 그러나 앞선 판은 이 정의를 **테스트 파일에 재구현**해 두었다(`tests/test_privacy_scanner.py`).
#: 두 벌이 각자 살면 갈리고, 갈린 쪽이 조용한 것은 늘 도달 쪽이다. 그래서 **정의는 둘 다
#: 여기 한 파일에 두고 테스트는 import 만 한다.** 재구현 0 이 Z-1 의 처분이다.
#:
#: ## 이 정의가 못 보는 형태 (X-5 — 선언된 미도달)
#:
#: 이것도 **"로그 호출에는 이름 붙은 수신자가 있다"**는 가정을 쓴다. `LoggerFactory
#: .getLogger(X).debug(...)` 같은 체인은 수신자가 이름이 아니라 **식**이라 여기도 안 잡힌다.
#: 그 사실은 숨기지 않고 `tests/test_privacy_scanner.py` 의 형태 목록 표에 `xfail` 로
#: 적혀 있다 — 조용한 0이 아니라 **선언된 0**이다. 누가 그 형태를 탐지에 넣으면 `xpass` 로
#: 뒤집혀 시끄러워진다.
LOGGER_SHAPED = re.compile(
    r"(?<![A-Za-z0-9_])[A-Za-z_]\w*(?:log|logger|LOG|LOGGER)\s*\."
    r"\s*(?:debug|info|warn|warning|error|exception|trace)\s*\("
    r"|(?<![A-Za-z0-9_.])(?:log|logger|LOG|LOGGER)\s*\."
    r"\s*(?:debug|info|warn|warning|error|exception|trace)\s*\("
)


def shannon_bits_per_char(value: str) -> float:
    """문자당 섀넌 엔트로피. 난수 키와 사람이 타이핑한 낱말열을 가르는 축이다."""
    if not value:
        return 0.0
    total = len(value)
    return -sum((count / total) * math.log2(count / total) for count in Counter(value).values())


_HEX_KEY = re.compile(r"^[0-9a-fA-F]{32,}$")
_TOKEN_CHARS = re.compile(r"^[A-Za-z0-9+/=_.\-]{24,}$")


def looks_like_real_secret(value: str) -> bool:
    """리터럴이 **진짜 키의 꼴**인지 본다 — 위치(tests/ 여부)가 아니라 값의 모양으로 가른다.

    `tests/`를 통째로 면제하면 테스트 파일에 실제 암호화 키를 넣어도 통과한다. 그래서
    기준을 파일 경로가 아니라 리터럴 자체에 둔다: 진짜 키는 base64·hex 난수라 문자
    클래스가 섞이고 엔트로피가 높지만, `wrongpassword` 같은 픽스처는 소문자 낱말이라
    두 축 모두에서 떨어진다. 반대로 테스트 파일 안이라도 난수꼴 리터럴이면 그대로 잡힌다.
    """
    if _HEX_KEY.match(value):
        return True  # hex 키는 클래스가 2종뿐이라 아래 기준에 안 걸린다
    entropy = shannon_bits_per_char(value)
    if _TOKEN_CHARS.match(value) and entropy >= 3.8:
        return True  # base64/토큰꼴 — 클래스가 적어도 난수면 키다
    classes = sum(
        bool(re.search(pattern, value))
        for pattern in (r"[a-z]", r"[A-Z]", r"[0-9]", r"[^A-Za-z0-9]")
    )
    return classes >= 3 and len(value) >= 12 and entropy >= 3.2


@dataclass(frozen=True)
class Rule:
    id: str
    severity: str  # BLOCK | WARN
    invariant: str
    pattern: re.Pattern[str]
    why: str
    false_positive: str
    suffixes: frozenset[str] | None = None
    #: 이 불변식이 **설계상 허용된** 경로 조각. 매번 같은 오탐이 뜨면 사람이 규칙 전체를
    #: 무시하게 되므로, 승인된 예외는 여기 적어 리포트에서 뺀다. 이 목록 자체가 감사
    #: 대상이다 — 늘어날 때마다 왜 허용인지 근거를 남긴다.
    sanctioned: tuple[str, ...] = ()
    #: 적중한 줄을 2차로 판정한다. False를 주면 후보에서 뺀다. 경로 면제와 달리 **값의
    #: 성질**로 거르므로 예외 경로를 넓히지 않고도 오탐을 줄일 수 있다.
    #: 적중한 줄을 2차로 판정한다. False를 주면 후보에서 뺀다.
    #:
    #: ## 새 `refine` 을 더하기 전에 통과해야 하는 판별식 (§4-octies.7)
    #:
    #: > **이 `refine` 의 입력이 어휘·구문 층의 정확성에 의존하는가?**
    #: > - **의존하면 금지.** 그 층의 모든 결함을 **조용한 통과로 상속한다.**
    #: >   인자 구간·괄호·주석 경계를 읽어야 판정할 수 있다면 여기 두지 말고
    #: >   호출 지점 표기(`privacy-allow:`)로 처리한다.
    #: > - **의존하지 않으면 허용.** 자기 완결적 캡처 그룹의 **값 자체**만 본다.
    #:
    #: `LOG-BODY` 의 훅이 전자였고 다섯 갈래를 냈다 — 삭제했다.
    #: `SECRET-LITERAL` 의 `looks_like_real_secret` 은 후자다 — 캡처한 리터럴 하나를
    #: 엔트로피·문자 클래스로 판정하고 괄호·주석·인자 경계를 보지 않는다. 유지한다.
    #:
    #: **판별식을 여기 적어 두지 않으면 다음 사람이 `LOG-BODY` 와 같은 형태를 다시 만든다.**
    #:
    #: `OWNERSHIP-403` 의 훅도 후자다 — 자기 패턴이 직접 소비한 캡처 그룹(`inert`)이
    #: 참여했는지만 보고, 바깥 텍스트도 어휘 층 산출물도 읽지 않는다.
    refine: Callable[[re.Match[str]], bool] | None = None
    #: 같은 창(window) 안에 이 패턴이 있으면 "완화 조치가 붙어 있다"로 보고 후보에서 뺀다.
    #: 규칙의 `false_positive` 주석이 사람에게 시키던 "주변 줄 확인"을 기계화한 것이다.
    #: 창을 벗어난 곳에서 완화하면 여전히 후보로 남는다 — 그 편이 안전한 방향이다.
    hardened: re.Pattern[str] | None = None
    hardened_before: int = 2
    hardened_after: int = 10
    #: **논리 줄**(괄호가 닫힐 때까지 이어 붙인 물리 줄)에서 판정한다. 인자 목록을 보는
    #: 규칙만 켠다 — 한 줄에서 완결되는 규칙(`LLM-VENDOR-SDK`·`XML-DTD` 등)까지 켜면
    #: 이어 붙인 문맥이 오탐을 만든다. 규칙 **자신이 선언**하게 두는 이유는, 한 곳에서
    #: 목록으로 관리하면 새 규칙을 더할 때 그 목록을 잊기 때문이다.
    #:
    #: 배선 시점 실측(privacy-gate 판정 §4-quater.2): ktlint 가 강제하는 Kotlin 줄바꿈
    #: 스타일에서 `logger.info(` 호출이 여러 줄로 갈리면 줄 단위 판정은 **전 줄 무적중**이었다.
    multiline: bool = False
    #: 이 규칙이 읽는 **호출의 시작 모양**. `multiline` 규칙은 반드시 준다(아래 자기검사).
    #:
    #: 쓰이는 곳은 하나다 — 논리 줄이 상한에서 끊겼을 때 **그 구간에 이 규칙이 볼 호출이
    #: 있었는가**를 판정한다. 없으면 못 읽은 것이 없으므로 fail-closed 대상이 아니다.
    #: 246개짜리 사전 리터럴이나 긴 KDoc 은 규칙이 읽을 호출이 아니다.
    #:
    #: 규칙 목록에서 **파생**시키는 이유: 손으로 따로 적으면 규칙을 더할 때 그 목록을 잊고,
    #: 그때 fail-closed 가 조용히 그 규칙만 빠뜨린다.
    opener: re.Pattern[str] | None = None
    #: 호출 지점 표기(`privacy-allow:`)로 **누를 수 있는** 규칙인가.
    #:
    #: 정당한 오탐이 존재할 수 없는 규칙은 `False` 로 둔다 — 그 규칙에서 "오탐이니 눌러
    #: 달라"가 나오면 그것은 표기가 아니라 **판정 요청**이어야 한다(§4-octies.3).
    #: 기본값이 `False` 인 것은 의도다: 새 규칙은 **누를 수 없는 쪽**에서 시작한다.
    markable: bool = False
    #: `refine` 이 후보를 뺐을 때 리포트에 적는 사유. 규칙마다 빼는 근거가 다르므로
    #: 규칙이 스스로 말하게 한다 — 한 문장을 공용으로 쓰면 리포트가 "무엇을 눈감았는가"를
    #: 잘못 적고, 그 리포트가 다음 감사의 입력이 된다.
    refine_reason: str = "값의 모양이 불변식 대상이 아님"

    # ── 필드를 더할 때 ──────────────────────────────────────────────────────────
    # **끝에 더한다.** 아래 규칙들 중 일부가 위치 인자로 구성돼 있어(`XML-DTD` 의
    # `suffixes`·`sanctioned`·`refine`·`hardened`), 중간에 필드를 끼우면 그 인자들이
    # **한 칸씩 밀려 조용히 다른 필드에 박힌다.** 실제로 `markable` 을 `refine` 뒤에
    # 넣었다가 `XML-DTD` 의 `hardened` 정규식이 `markable` 로 들어가 자기검사가 터졌다.
    # 그 자기검사가 없었다면 XML-DTD 의 완화 창이 조용히 사라졌을 것이다.


#: 이 규칙이 **403 으로 읽는 토큰**. 탐지 패턴과 불활성 ③ 의 이름 관문이 **같은 조각을**
#: 쓴다 — 두 벌로 두면 갈리고, 갈린 순간 ③ 의 "무손실" 논증이 조용히 거짓이 된다(아래 정정).
#:
#: ## 밑줄 결합 상수 두 이름은 **명시 토큰**이다 (게이트 24 교차 종합 ⓐ · 리더 재판정)
#:
#: `\b` 는 `_` 앞뒤에서 서지 않는다. 그래서 `status.HTTP_403_FORBIDDEN`(FastAPI/Starlette)과
#: `response.sendError(HttpServletResponse.SC_FORBIDDEN)`(서블릿) — **진짜 403 을 내보내는 두
#: 줄**이 무적중이었다. 3관점이 같은 자리를 실측으로 확정했다(codex 게이트 23 C-1 · 게이트 24
#: X24-1 · Claude S-2 — 실제 Kotlin 프로덕션 파일에 넣고 CI 동일 명령 **exit 0**).
#:
#: **처방이 둘로 갈렸고 하나만 채택됐다.**
#:
#: | 갈래 | 얻는 것 | 대가 | 판정 |
#: |---|---|---|---|
#: | 경계 완화(`\b` 제거) | 밑줄 결합 전부 | `FORBIDDEN_IN_FILENAME`·`FORBIDDEN_ANNOTATIONS`
#:   처럼 **HTTP 와 무관한 이름**이 전부 BLOCK — 출구 없는 규칙(`UNMARKABLE_RULES`)에
#:   오탐 무리 | **기각 유지** |
#: | 명시 토큰 추가 | **실측된 두 이름만** | 없음 — 둘 다 HTTP 상태 상수 전용 이름이라
#:   다른 뜻으로 쓰이지 않는다 | **채택** |
#:
#: 후자는 `CLAUDE.md` 규칙 4 분류로 **탐지형 넓힘**이고, 기각 근거였던 오탐 무리가 이 갈래에서는
#: 발생하지 않는다. 회귀가 두 방향을 함께 고정한다 — N20~N25 가 채택을, P8·P9 가 기각을 잰다.
#:
#: **`sendError` 자체는 토큰이 아니다 — 범위는 근거를 넘지 않는다.** 리더가 든 두 관용구
#: `sendError(…403…)` · `sendError(SC_FORBIDDEN)` 는 이미 `403`·`SC_FORBIDDEN` 토큰으로 전건
#: 잡힌다(N5·N20·N22·N23). 맨 `sendError` 를 토큰으로 올리면 `sendError(404)`·`sendError(500)`
#: 처럼 **403 과 무관한 호출**까지 출구 없는 규칙의 후보가 되는데, 그 확장에는 실측 근거가 없다.
#: 남는 미도달은 상태 코드가 **계산값**인 `sendError(computed)` 하나이고, 그 자리는 계산의 출처인
#: `= 403` 선언이 ③ 의 이름 관문에 걸려 BLOCK 으로 남는다(N14~N16 이 재는 성질).
#:
#: 이름 관문(불활성 ③)이 **같은 조각에서 파생되므로** 두 이름은 선언 제외에도 함께 따라온다 —
#: `const val SC_FORBIDDEN = 403` 은 빠지고 사용처 `sendError(SC_FORBIDDEN)` 는 잡힌다(N25).
#: "이름이 토큰일 때만 뺀다"는 ③ 의 전제가 확장 뒤에도 그대로 성립한다.
#:
#: ## [2026-08-19 게이트 25] 이 조각은 **불활성 판정 전용**으로 물러났다
#:
#: 아래 `_403_NAME`(종류) + `_403_STATUS_SITE`(자리)가 식별자 갈래의 탐지를 맡는다.
#: 이 조각은 여전히 ①②③ 이 소비할 형태를 고르는 **이름 관문**이고, 숫자 리터럴
#: 갈래(`\b403\b`)의 근거이기도 하다. ③ 의 무손실 전제는 이제 **더 강하게** 성립한다 —
#: 이 조각에 잡히는 이름은 전부 `_403_NAME` 에도 잡히므로(아래 포함 관계 자기검사),
#: 선언을 빼도 응답 자리의 사용처는 반드시 잡힌다.
_403_TOKEN = r"(?:403|HTTP_403_FORBIDDEN|SC_FORBIDDEN|FORBIDDEN|Forbidden)"

#: **403 을 뜻하는 식별자 — 인스턴스가 아니라 종류로 받는다** (게이트 25 H6·H7, 리더 판정).
#:
#: ## 왜 열거를 버렸나
#:
#: 앞선 판은 `_403_TOKEN` 에 이름을 하나씩 더하는 방식이었다. 게이트 23 이 두 이름을
#: 더했고(`HTTP_403_FORBIDDEN`·`SC_FORBIDDEN`), 게이트 25 에서 **그 다음 라이브러리 상수**
#: (`HttpURLConnection.HTTP_FORBIDDEN`)가 곧바로 무적중으로 드러났다(Claude L-3 실측).
#: `CLAUDE.md` 규칙 4 로 보면 **구조적 재발**이다 — 빈자리를 만드는 기제(이름 열거)가
#: 지금 열거할 수 없는 자리(**앞으로 들어올 모든 HTTP 라이브러리의 403 상수 이름**)까지
#: 닿는다. 종류를 댈 수 있으므로 그 종류만큼 넓힌다.
#:
#: 같은 열거가 **반대 방향으로도** 샜다 — 열거된 이름이 문자열·후행 주석 안에 있어도
#: BLOCK 이었다(codex B-7: `val x = "HTTP_403_FORBIDDEN"` · `val x = 1 // SC_FORBIDDEN 설명`).
#: **두 결함은 같은 원인의 두 얼굴이다** — 어휘를 보지 않는 이름 열거는 열거 안에서
#: 과잉 적중하고 열거 밖에서 무적중한다. 두 관점(codex·Claude)이 서로를 읽지 않은 채
#: **같은 구조적 처방**에 도달했다: 이름을 넓히되 **자리로 값을 치른다.**
#:
#: 그래서 이 조각은 `FORBIDDEN`/`Forbidden` 을 품은 식별자 **전체**를 받는다.
#: `FORBIDDEN` · `SC_FORBIDDEN` · `HTTP_403_FORBIDDEN` · `HttpStatus.FORBIDDEN` ·
#: `HttpURLConnection.HTTP_FORBIDDEN` · `HttpResponseForbidden` 이 한 조각에 들어온다.
#: 대가는 아래 `_403_STATUS_SITE` 다 — **이 조각은 그 자리에서만 본다.**
_403_NAME = r"(?:[A-Za-z_]\w*\.)*\w*(?:FORBIDDEN|Forbidden)\w*"

#: **응답 상태가 만들어지는 자리** — `_403_NAME` 은 여기서만 읽는다.
#:
#: 두 갈래다.
#:
#: | 갈래 | 예 | 왜 응답 자리인가 |
#: |---|---|---|
#: | ⓐ 호출 인자 | `sendError(SC_FORBIDDEN)` · `status(HttpStatus.FORBIDDEN)` |
#:   그 인자가 곧 나가는 상태 코드다 |
#: | ⓑ 값 산출 | `return HttpStatus.FORBIDDEN` · `val deny = HttpStatus.FORBIDDEN` ·
#:   `throw ForbiddenException()` | 그 값이 상태로 흘러간다 |
#:
#: **ⓑ 가 없으면 좁아진다.** `fun deny(): HttpStatus = HttpStatus.FORBIDDEN` 처럼 호출을
#: 거치지 않고 값을 내는 형태가 있고, 앞선 판은 그것을 잡고 있었다. 자리 제한이
#: **순수한 넓힘**이 되려면 ⓑ 를 함께 둬야 한다. ⓑ 는 변수 경유 한 단계도 닫는다 —
#: `val deny = HttpStatus.FORBIDDEN` 이 선언에서 잡히므로 `sendError(deny)` 를 굳이
#: 추적하지 않아도 된다.
#:
#: ## 무엇을 보고 무엇을 안 보는가 (실측에 맞춘 문면 — 게이트 28)
#:
#: 앞선 판의 이 자리에는 다음 문장이 **무조건형**으로 적혀 있었다:
#:
#: > **자리 밖 식별자는 보지 않는다.** 그래서 `FORBIDDEN_IN_FILENAME`(파일명 정화) ·
#: > `FORBIDDEN_ANNOTATIONS`(계약 검사) · 문자열 안의 이름 · 후행 주석 안의 이름이
#: > **자연 해소**된다.
#:
#: **그 문장은 거짓이었다.** 같은 종류의 이름이 `ⓑ` 의 **자리 안에** 들어오면 잡혔다 —
#: 저장소의 실제 줄 `.filter { candidate -> FORBIDDEN_HANDLES.any { … } }` 가
#: `-> FORBIDDEN_HANDLES` 로 매치돼 BLOCK 후보가 됐다(게이트 28, 리더 판정: 오탐).
#: `FORBIDDEN_HANDLES` 는 raw JDBC 손잡이 목록이고 HTTP 와 아무 관계가 없다.
#: 자리 제한은 **자리 밖**을 걸렀을 뿐, 자리 안에서 이름이 무엇으로 쓰이는지는 안 봤다.
#:
#: 그래서 실측에 맞춰 다시 적는다.
#:
#: - **본다**: ⓐ 의 호출 인자 자리 · ⓑ 의 값 산출 자리에 나타난 `_403_NAME`.
#: - **안 본다**: 두 자리 **밖**의 이름(선언·`in` 연산·문자열·후행 주석 — Q1~Q4) ·
#:   ⓑ 자리 안이더라도 **뒤에 멤버 접근이나 첨자가 이어지는 이름**(`_403_RECEIVER_TAIL`).
#:
#: 둘째 항이 이번 회차에 붙은 것이다. 근거는 **ⓑ 자신의 계약**이다 — ⓑ 는 "이 이름이 곧
#: 나가는 값이다"를 재는데, 뒤에 `.any { … }` 나 `[k]` 가 이어지면 나가는 값은 그 이름이
#: 아니라 **체인의 결과**다. 즉 좁힘이 아니라 ⓑ 가 원래 선언한 것으로의 복귀다:
#: 옛 ⓑ 는 **식의 머리를 산출값으로 오인**하고 있었다.
#:
#: **대가를 숨기지 않는다.** 같은 오인이 우연히 맞히던 자리도 함께 빠진다 —
#: `return HttpStatus.FORBIDDEN.value()` 처럼 **체인을 거쳐 산출되는 403** 이다. 옛 판은
#: `.value()` 가 무엇을 돌려주는지 모른 채 머리만 보고 잡았다. 잔여는 아래 두 겹으로
#: 남는다: ⑴ 인자 자리의 같은 식(`sendError(HttpStatus.FORBIDDEN.value())`)은 ⓐ 가
#: 그대로 잡는다(N24 — 바깥 호출 이름이 자리를 보증하므로 ⓐ 에는 이 꼬리표를 붙이지
#: 않는다), ⑵ 값 자리의 잔여는 회귀 표의 **D3 행**이 `blocks=False` 로 **선언**한다 —
#: 누가 넓혀 잡히기 시작하면 그 행이 뒤집혀 빨개진다. 무시 패턴도 면제 조항도 아니다.
#:
#: 면제 목록도, 경로 예외도, 심각도 강등도 없다. 이것이 이 처방이 은폐형이 아니라
#: 탐지형인 이유다: 뺀 것은 "신호"가 아니라 **신호가 아닌 자리**다.
#:
#: 부호가 **양성**인 단언(`isEqualTo(FORBIDDEN)`)을 자리에 넣은 이유 — 그 줄은 제품이
#: 403 을 낸다고 **기대**하는 단언이고, 이 불변식에서는 진짜 위반 신호다(N6). 부호 반전
#: (`isNotEqualTo`)은 목록에 없으므로 자연히 빠진다 — ① 의 소비와 **두 겹**으로 닫힌다.
#: ⓐ 가 보는 **호출 이름**. 아래 자리 패턴과 `_403_CALL_OPENER` 가 **같은 조각**을 쓴다 —
#: 목록을 두 벌 적으면 사본이 갈리고, 갈린 사본은 늘 조용한 쪽이었다(게이트 23 ⓐ).
_403_CALL = (
    r"(?:sendError|send_error|setStatus|set_status|status|statusCode|status_code"
    r"|ResponseEntity|ResponseStatusException|ResponseStatus"
    r"|HTTPException|HttpResponse"
    r"|isEqualTo|isSameAs|isIn|assertEquals|assertSame)"
)

#: **수신자 꼬리표** — ⓑ 에서 "이름이 곧 나가는 값"이 **아닌** 자리를 떨어뜨린다.
#:
#: 매치된 이름 바로 뒤에 멤버 접근(`.식별자`)이나 첨자(`[`)가 이어지면 그 이름은
#: **수신자**이고, 나가는 값은 체인의 결과다. `-> FORBIDDEN_HANDLES.any { … }` 가 그것이고
#: `-> HttpStatus.FORBIDDEN` 은 아니다(뒤에 체인이 없다).
#:
#: **`(` 는 꼬리표에 넣지 않는다** — `throw ForbiddenException()`·
#: `return HttpResponseForbidden()` 은 이름 자신이 그 자리에서 값을 만드는 형태다(G6·G7).
#: `::` 도 넣지 않았다 — 근거가 될 실측이 없고, 좁힘은 근거를 넘지 않는다.
#:
#: 상수로 뽑아 두는 이유는 회귀 스위트가 **이 문자열을 떼어 보고**(음성 대조) 떼면
#: 오탐이 되살아나는지 재기 위해서다. 테스트에 사본을 적으면 그 사본이 갈리고, 갈린
#: 사본은 늘 조용한 쪽이었다(게이트 23 ⓐ 가 겪은 형태).
_403_RECEIVER_TAIL = r"(?!\s*(?:\.\s*[A-Za-z_]|\[))"

_403_STATUS_SITE = (
    # ⓐ 호출 인자 자리. 이름 바로 뒤가 `(` 여야 한다.
    #    왼쪽 경계는 이 갈래**에만** 붙인다 — ⓑ 의 `=` 앞은 식별자일 수 있어(`x=FORBIDDEN`)
    #    공통으로 걸면 그 자리가 조용히 빠진다.
    #
    #    **수신자 꼬리표는 여기에 붙이지 않는다.** 바깥 호출 이름(`sendError`·`status`)이
    #    이미 "이 인자가 나가는 상태 코드다"를 보증하므로, 인자가 체인이어도
    #    (`sendError(HttpStatus.FORBIDDEN.value())`, N24) 자리의 근거가 사라지지 않는다.
    r"(?<![A-Za-z0-9_])" + _403_CALL + rf"\s*\(\s*(?:[A-Za-z_]\w*\s*=\s*)?{_403_NAME}\b"
    # ⓑ 값 산출 자리. `return`/`throw`/`raise`/`->`/초기화 `=` 바로 뒤.
    #    `==`·`!=`·`<=`·`>=` 를 배제해 **비교**는 자리로 치지 않는다 — 비교는 값을
    #    내보내는 것이 아니라 읽는 것이다.
    #
    #    이 갈래에는 이름 자신 말고 자리를 보증하는 것이 없다. 그래서 이름이 수신자면
    #    (`-> FORBIDDEN_HANDLES.any { … }`) 자리의 전제가 깨진다 — `_403_RECEIVER_TAIL` 이
    #    그것을 떨어뜨린다(게이트 28).
    #
    #    꼬리표는 **이 갈래의 문자열 안에서** 끝난다. 밖에서 `+` 로 이어 붙이면 뒤에 갈래가
    #    하나 더 생기는 순간 꼬리표가 **그 새 갈래로 조용히 옮겨간다** — 아래
    #    `test_수신자_꼬리표를_떼면_오탐이_되살아난다` 가 그 이동도 잡지만, 붙는 자리를
    #    문면으로 못 박아 두는 편이 낫다.
    rf"|(?:\b(?:return|throw|raise)\b|->|(?<![=!<>+\-*/%])=(?!=))"
    rf"\s*{_403_NAME}\b{_403_RECEIVER_TAIL}"
)

#: 끊긴 논리 줄이 ⓐ 를 가렸는지 판정하는 **호출 시작 모양**(`Rule.opener` 계약).
#: `multiline` 규칙은 반드시 이것을 가져야 fail-closed 밖으로 빠지지 않는다.
_403_CALL_OPENER = re.compile(r"(?<![A-Za-z0-9_])" + _403_CALL + r"\s*\(")

#: 불활성 ③ 의 **무손실 전제를 패턴이 강제한다.**
#:
#: ③ 은 "선언된 이름 자신이 `_403_TOKEN` 일 때만" 선언을 뺀다. 그 제외가 무손실인 근거는
#: **사용처가 반드시 잡힌다**는 것인데, 게이트 25 부터 사용처 탐지는 `_403_NAME` +
#: 응답 자리로 옮겨 갔다. 두 조각이 갈리는 순간 "선언은 뺐는데 사용처도 안 잡힌다"가
#: **조용히** 성립한다 — 정확히 게이트 23 ⓐ 가 겪은 형태다.
#:
#: 그래서 이름 목록을 다시 적지 않고 `_403_TOKEN` 에서 **뽑아** 대조한다. 여기에 손으로
#: 목록을 적으면 그 사본이 갈리고, 갈린 사본은 늘 조용한 쪽이었다.
_403_TOKEN_NAMES = tuple(
    alternative
    for alternative in _403_TOKEN.removeprefix("(?:").removesuffix(")").split("|")
    if not alternative.isdigit()
)
if not _403_TOKEN_NAMES:
    raise AssertionError(
        "_403_TOKEN 에서 이름 대안을 뽑지 못했다 — 조각 문법이 바뀌었으면 이 자기검사도 "
        "함께 고쳐라. 빈 목록으로 통과하면 아래 포함 관계 검사가 0건 검사가 된다."
    )
for _403_name in _403_TOKEN_NAMES:
    if not re.search(_403_STATUS_SITE, f"sendError({_403_name})"):
        raise AssertionError(
            f"불활성 ③ 이 이름 `{_403_name}` 의 선언을 빼는데, 그 이름의 사용처가 응답 "
            "자리에서 잡히지 않는다 — 제외가 무손실이 아니다. `_403_NAME` 을 넓히거나 "
            "`_403_TOKEN` 에서 그 이름을 빼라."
        )
del _403_name

#: `OWNERSHIP-403` 이 만나는 자리 중 **403 을 만들어 낼 수 없는** 형태들.
#:
#: ## 왜 정밀화이고 은폐가 아닌가 (privacy-gate 판정, 2026-08-19 · CLAUDE.md 규칙 4)
#:
#: 앞선 판의 패턴은 `\b(?:403|FORBIDDEN|Forbidden)\b` 하나였다 — **토큰이 보이면 무조건**
#: BLOCK 이라, 불변식을 **집행하는 코드**(`isNotEqualTo(FORBIDDEN)`)와 그 코드가 쓰는 상수·
#: 테스트 이름까지 전부 차단했다. 규칙의 `false_positive` 주석이 *"테스트의 403 기대값이면
#: 오탐"* 이라고 이미 적고 있었는데 심각도는 BLOCK 이고 표기로 누를 수도 없으니
#: (`UNMARKABLE_RULES`), **정당한 오탐에 출구가 없는 게이트**였다. 그 상태에서 CI 가 빨개지면
#: 남는 선택지는 무시·면제뿐이고, 그것이 규칙 4 가 금지하는 은폐형이다.
#:
#: 그래서 **탐지 정확도**를 올린다. 세 형태를 뺀 근거는 셋 다 같은 문장이다:
#:
#: > **그 자리는 403 응답을 보낼 수 없고, 보낼 수 있는 자리는 여전히 전부 잡힌다.**
#:
#: | 형태 | 예 | 왜 보낼 수 없나 |
#: |---|---|---|
#: | ① 부호 반전 단언 | `isNotEqualTo(FORBIDDEN)` | **403 이 아님을 강제**한다. 부호가 반대다 |
#: | ② 테스트 이름 | `@DisplayName("… 403 아님")` · **`fun` 위치** 백틱 | 라벨·함수명이다 |
#: | ③ 상수 선언 | `const val FORBIDDEN = 403` | 묶기만 한다. **쓰는 자리는 그대로 잡힌다** |
#:
#: ① 은 인자 **첫 자리**가 403 토큰일 때만이라 `isEqualTo(FORBIDDEN)`
#: (403 을 **기대**하는 단언 — 진짜 위반 신호다)은 그대로 남는다.
#:
#: ## 정정 — ③ 의 "무손실"은 무조건 참이 아니었다 (게이트 23 ⓐ · codex C-1 ≡ Claude B-1)
#:
#: 앞선 판의 이 자리에는 다음 문장이 **무조건형**으로 적혀 있었다:
#:
#: > ③ 이 특히 무손실이다 — 선언을 빼도 `status(FORBIDDEN)` 같은 사용처는 `FORBIDDEN`
#: > 토큰으로 여전히 매치된다.
#:
#: 그런데 ③ 의 식별자 자리가 **`\w+`** 라 이름에 아무 제약이 없었다. 위 문장은 **상수 이름이
#: 그 자체로 403 토큰일 때만** 성립하는 성질이고, 이름이 토큰을 품지 않으면 **선언도 사용처도
#: 아무 데서도 안 잡힌다.** 실측으로 네 형태가 옛 `exit 1` → 새 `exit 0` 으로 뒤집혔다:
#: `const val OWNER_MISMATCH = 403` + 사용처 · 난수 이름 `val q7x9k2 = 403` + 사용처 ·
#: 타입 명시 `private val zk4m1p: Int = 403` + 사용처 · 백틱 식별자 `` status(`403`) ``.
#:
#: **처방은 제외의 전제를 명시적 조건으로 바꾸는 것이다.** ③ 은 이제
#: **선언된 이름 자신이 이 규칙의 토큰 패턴(`_403_TOKEN`)에 잡힐 때만** 제외한다. 그러면
#: "무손실"이 논증이 아니라 **패턴이 강제하는 성질**이 된다 — 이름이 토큰이면 사용처가 반드시
#: 잡히고, 아니면 선언이 BLOCK 으로 남는다. `HTTP_FORBIDDEN`·`FORBIDDEN_STATUS` 처럼 밑줄로
#: 이어 붙인 이름은 `\b` 경계가 서지 않아 사용처가 안 잡히므로 **BLOCK 쪽**에 남는다(옳다).
#:
#: ② 의 백틱도 같은 결함을 공유했다 — **아무 자리의** 백틱 조각을 삼켜서
#: `` ResponseEntity.status(`403`) `` 이라는 **진짜 반환**까지 지웠다. 이제 `fun` 바로 뒤의
#: 백틱, 즉 **함수 이름 자리**만 소비한다. KDoc 인라인 코드(`` `'403'` ``)는 이 좁힘의 영향을
#: 받지 않는다 — 그 줄들은 `*` 로 시작해 규칙에 닿기 전에 이미 빠진다(실측 확인).
#:
#: **이것이 "탐지를 되찾는" 방향의 수정임에 유의한다.** 정밀화는 유지하되 조건이 붙었고,
#: 붙은 조건은 전부 후보를 **늘리는** 쪽이다. 회귀는 네 형태를 `blocks=True` 로 고정한다.
#:
#: ## 기각한 더 넓은 갈래 (근거를 넘지 않는다)
#:
#: - **문자열 리터럴 전체 제외** — "상태 코드는 세 스택 모두에서 정수·열거값이지 문자열이
#:   아니다"는 참이지만, `@ApiResponse(responseCode = "403")`·`responses={"403": …}` 같은
#:   **403 응답 선언**을 조용히 삼킨다. 그것은 이 불변식이 봐야 할 신호다.
#: - **경로 면제(`tests/` 통째로)** — 은폐형. `SECRET_LITERAL` 이 같은 이유로 이미 거부했다.
#: - **심각도 강등(BLOCK → WARN)** — 은폐형. 탐지가 아니라 표현을 낮추는 조치다.
#: - **`hardened` 창** — 창 안의 다른 403 까지 통째로 눌러 줄 단위 부수 피해가 난다.
#:   소비형 대안은 **그 자리만** 뺀다.
#:
#: 세 형태 밖은 전부 후보로 남는다 — 모르는 형태에서 닫히는 쪽이다.
OWNERSHIP_403_INERT = (
    # ① 부호 반전 단언. 403 토큰이 **첫 인자**일 때만 — `isEqualTo` 는 걸리지 않는다.
    r"(?:isNotEqualTo|assertNotEquals|assertNotSame|isNotIn)"
    rf"\s*\(\s*(?:[\w.]+\.)?{_403_TOKEN}\s*[,)]"
    # ② 테스트 이름 — JUnit 이 이름을 담는 두 자리(라벨 애너테이션 · **함수 이름** 백틱)다.
    #    **토큰을 품은 것만** 매치시킨다. 품지 않은 라벨·백틱까지 소비하면 뺀 것이 없는데도
    #    "2차 판정으로 제외" 집계가 폭증해(실측 1446건) 규칙이 눈감은 양을 재는 그 숫자가
    #    거짓이 된다 — 리포트가 다음 감사의 입력이므로 그 거짓이 그대로 굴러간다.
    #
    #    백틱은 **`fun` 바로 뒤**여야 한다. 자리를 묻지 않으면 `` status(`403`) `` 이라는
    #    진짜 403 반환까지 삼킨다(게이트 23 ⓐ 실측 — 위 정정 절).
    rf"|@DisplayName\s*\(\s*\"[^\"\n]*{_403_TOKEN}[^\"\n]*\""
    rf"|\bfun\s+`[^`\n]*{_403_TOKEN}[^`\n]*`"
    # ③ 상수 선언. 키워드(`val`/`var`/`const`/`let`)를 요구하거나, Python 은 모듈·클래스
    #    수준의 상수만 받는다 — `response.status_code = 403` 같은 대입은 점·소문자라
    #    여기 걸리지 않고 후보로 남는다.
    #
    #    **이름 관문이 있다**: 선언된 식별자 자신이 `_403_TOKEN` 이어야 한다. 그때만
    #    "선언을 빼도 사용처가 잡힌다"가 참이기 때문이다. `OWNER_MISMATCH`·`q7x9k2` 처럼
    #    토큰 없는 이름은 사용처에도 토큰이 없어 **아무 데서도 안 잡히므로** 선언을 BLOCK 으로
    #    남긴다. 이름 뒤 `\b` 로 `HTTP_FORBIDDEN` 같은 부분 일치를 막는다 — 그 이름도
    #    사용처가 `\b` 경계 때문에 안 잡히므로 제외 대상이 아니다.
    rf"|^\s*(?:(?:private|internal|public|protected|open|companion)\s+)*"
    rf"(?:const\s+)?(?:va[lr]|let)\s+{_403_TOKEN}\b\s*(?::\s*[\w<>?.\[\]]+\s*)?=\s*403\b"
    rf"|^\s*{_403_TOKEN}\b\s*(?::\s*[\w\[\].]+\s*)?=\s*403\b"
)

RULES: tuple[Rule, ...] = (
    Rule(
        "LOG-BODY",
        "BLOCK",
        "로그에 문서 본문·개인정보가 없다",
        # `[^)]*` 를 쓰지 않는다 — 메시지 문자열 안의 괄호 하나에 인자 구간이 끊겨 그 뒤를
        # 못 보던 자리다(M-09). 논리 줄에는 개행이 없으므로 `[^\n]*` 가 줄 전체를 본다.
        #
        # **2차 판정(refine)이 없다.** 여기서 값의 성질로 오탐을 누르던 훅은 §4-octies 로
        # 삭제됐다 — 그 훅은 인자 구간 파싱의 정확성에 의존해서, 아래 층이 틀리면
        # **잘린 조각을 보고 정직하게 "안전"을 판정**했다. 정당한 오탐은 이제 호출 지점
        # 표기(`privacy-allow:`)로 누른다.
        re.compile(rf"{LOG_CALL}\s*\((?=[^\n]*\b(?:{BODY_NAMES})\b)"),
        "로그는 평문으로 수집·장기 보관된다. 한 줄만 새도 암호화 저장 정책 전체가 무의미해진다.",
        "변수명이 우연히 겹치거나(`title` 로그가 문서 ID인 경우) 길이·타입만 찍는 줄이면 오탐.",
        markable=True,
        multiline=True,
        opener=re.compile(rf"{LOG_CALL}\s*\("),
    ),
    Rule(
        "LOG-FSTRING",
        "WARN",
        "로그에 문서 본문·개인정보가 없다",
        re.compile(rf"{LOG_CALL}\s*\(\s*f?[\"'][^\"']*\{{[^}}]*\b(?:{BODY_NAMES})\b"),
        "f-string·템플릿 보간은 지연 포매팅을 우회해 값이 곧장 문자열이 된다.",
        "포매팅 인자가 이미 마스킹·요약된 값이면 오탐.",
        markable=True,
        multiline=True,
        opener=re.compile(rf"{LOG_CALL}\s*\("),
    ),
    Rule(
        "EXC-BODY",
        "WARN",
        "예외 메시지에 본문이 실리지 않는다",
        re.compile(rf"(?:raise|throw)\s+\w*(?:Error|Exception)\s*\([^)]*\b(?:{BODY_NAMES})\b"),
        "예외 메시지는 5xx 응답과 스택트레이스 로그 양쪽으로 흘러간다.",
        "메시지가 아니라 원인 예외를 넘기는 인자면 오탐.",
        markable=True,
        multiline=True,
        opener=re.compile(r"(?:raise|throw)\s"),
    ),
    Rule(
        "LLM-VENDOR-SDK",
        "BLOCK",
        "LLM 호출은 provider 추상화를 거친다",
        re.compile(
            r"^\s*(?:import|from)\s+(?:anthropic|openai)\b"
            r"|^\s*import\s+com\.(?:anthropic|openai)\b"
        ),
        "provider 밖에서 SDK를 직접 부르면 마스킹 선행·호출 수 상한·no-training 계약이 "
        "모두 우회된다.",
        "provider 어댑터 구현체는 sanctioned 경로로 이미 제외했다 — 여기 남은 건 진짜 우회 후보다.",
        None,
        # 어댑터가 SDK를 감싸는 것이 추상화의 정의다. 새 경로를 추가할 때는 그 파일이
        # LLMProvider 인터페이스만 노출하는지 확인한 뒤 적는다.
        ("app/llm/", "backend-kotlin/infrastructure/", "/llm/provider/", "LlmProvider"),
    ),
    Rule(
        "LLM-RAW-INPUT",
        "BLOCK",
        "원문은 마스킹을 거친 뒤에만 LLM에 도달한다",
        re.compile(
            r"\.complete\s*\(\s*(?![^)]*mask)[^)]*\b"
            r"(?:source_text|sourceText|raw_text|rawText|plain|original_text|originalText|document_text|documentText)\b"
        ),
        "마스킹 전 본문이 벤더로 나가면 §5 Phase 7의 즉시 중단 사유다.",
        "변수명이 이미 마스킹된 값을 담고 있으면 오탐 — 이름을 masked*로 바꿔 의도를 드러낼 것.",
        multiline=True,
        opener=re.compile(r"\.complete\s*\("),
    ),
    Rule(
        "OWNERSHIP-403",
        "BLOCK",
        "다른 사용자 자원은 404로 은닉한다",
        # **불활성 형태를 먼저 소비한다**(`OWNERSHIP_403_INERT`). 소비된 구간 안의 토큰은
        # 따로 매치되지 않으므로 그 자리만 빠지고, **같은 줄의 다른 403 은 그대로 잡힌다.**
        # 창(`hardened`)으로 눌렀다면 줄 전체가 통째로 빠졌을 자리다.
        #
        # 남은 두 갈래가 **비대칭**인 것이 게이트 25 의 처방이다:
        #   · 식별자(`_403_NAME`)는 **응답 자리**(`_403_STATUS_SITE`)에서만 본다 — 종류로
        #     넓히는 대가를 자리로 치른다.
        #   · 숫자 리터럴 `403` 은 **어디서든** 본다 — 이름이 403 을 안 품은 상수
        #     (`const val OWNER_MISMATCH = 403`)를 잡는 유일한 통로가 그 선언 줄이고,
        #     자리를 요구하면 그 갈래가 통째로 사라진다(N14~N17 이 재는 성질).
        re.compile(rf"(?P<inert>{OWNERSHIP_403_INERT})|{_403_STATUS_SITE}|\b403\b"),
        "403은 '있지만 네 것이 아니다'를 알린다 — 자원 존재 자체가 유출이다.",
        "CORS·인증 미들웨어·프런트 문구·테스트의 403 기대값이면 오탐. 소유권 분기인지만 확인. "
        "불변식을 **집행**하는 세 형태(부호 반전 단언·테스트 이름·상수 선언)는 "
        "`OWNERSHIP_403_INERT` 가 뺀다. 403 을 뜻하는 **식별자**는 응답 자리"
        "(`_403_STATUS_SITE`)에서만 본다 — 그 밖의 `FORBIDDEN` 이름은 후보가 아니고, "
        "값 산출 자리 안이더라도 뒤에 멤버 접근·첨자가 이어지면(`FORBIDDEN_X.any { … }`) "
        "그 이름은 수신자이지 나가는 값이 아니라 후보가 아니다.",
        None,
        (),
        lambda match: match.group("inert") is None,
        refine_reason="불변식을 집행·명명하는 형태(403 을 만들어 낼 수 없는 자리)",
        # **논리 줄에서 판정한다** (게이트 26 S-1, 리더 판정 = privacy-gate 해제 조건 ⓐ).
        #
        # 게이트 25 가 자리(`_403_STATUS_SITE`)를 도입하면서 두 갈래가 모두 **인접성**을
        # 요구하게 됐다 — ⓐ 는 호출 토큰 바로 뒤의 `(`, ⓑ 는 키워드 바로 뒤. 그런데 이
        # 저장소는 `ktlintCheck` 를 CI 에서 강제하고, 그 서식은 인자를 다음 줄로 내린다:
        #
        #     return ResponseEntity.status(
        #         HttpStatus.FORBIDDEN,
        #     ).build()
        #
        # 물리 줄 판정에서는 어느 줄에도 `status(` 와 이름이 함께 있지 않다. 옛 판
        # (`aad5ca5~1`)은 토큰만 보았기에 우연히 잡았고, 새 판은 **놓쳤다** — 즉
        # **이 저장소의 표준 서식으로 쓴 소유권 403 이 BLOCK 규칙을 조용히 지나갔다.**
        #
        # 해제 조건은 ⓐ(논리 줄) 또는 ⓑ(잔여를 `xfail(strict)` 로 선언) 였고 리더가 ⓐ 를
        # 골랐다 — codex B-3 이 그 `xfail` 을 **은폐형**으로 판정했고(떼어도 아무것도 안
        # 깨진다) `CLAUDE.md` 규칙 4 ⑵ 가 은폐형 확장을 금지한다.
        #
        # 대가는 없다. 논리 줄은 **주석을 뺀 코드만** 잇고(`_advance`), 한 줄로 끝나는
        # 문장은 그 자체로 논리 줄 하나다 — Q1·Q2·P1·P5·P7 이 잰 자리 제한은 그대로다.
        multiline=True,
        opener=_403_CALL_OPENER,
    ),
    Rule(
        "PLAINTEXT-PERSIST",
        "BLOCK",
        "원문·결과·마스킹 대응표를 평문으로 저장하지 않는다",
        re.compile(
            r"(?:INSERT|UPDATE)[^;]{0,200}\b(?:source_text|easy_text|masked_text|review_text|original)\b"
            r"|\b(?:setSourceText|setEasyText|set_original)\s*\(\s*(?!.*(?:encrypt|cipher))"
        ),
        "암호문 컬럼에 평문이 들어가면 DB 덤프 한 번으로 전량이 노출된다.",
        "이미 암호화된 bytes를 담는 줄이면 오탐 — encrypt/cipher 호출이 같은 줄에 "
        "없을 뿐일 수 있다.",
        multiline=True,
        opener=re.compile(r"(?:INSERT|UPDATE)|\b(?:setSourceText|setEasyText|set_original)\s*\("),
    ),
    Rule(
        "SECRET-LITERAL",
        "BLOCK",
        "비밀키는 환경변수만 쓴다",
        re.compile(
            # 저장 암호화 키의 설정 이름은 재개발에서 바뀐다(Fernet → 표준 AEAD,
            # 2026-08-12). 옛 이름을 지우지 않고 새 이름을 **더한다** — 전환 중에는 두
            # 이름이 함께 존재할 수 있고, 이 목록에서 이름을 빼는 것은 탐지를 줄이는 것이다.
            r"(?:fernet[_-]?key|encryption[_-]?key|aead[_-]?key|cipher[_-]?key"
            r"|jwt[_-]?secret|api[_-]?key|secret[_-]?key|password)"
            r"\s*[:=]\s*[\"'](?P<literal>[^\"'\s]{12,})[\"']",
            re.IGNORECASE,
        ),
        "코드·커밋에 들어간 키는 히스토리에서 지워지지 않는다.",
        "리터럴이 난수꼴일 때만 후보로 올린다(`looks_like_real_secret`). 낱말꼴 픽스처"
        "(`wrongpassword`)는 여기서 걸러지지만, 테스트 파일이라도 난수꼴이면 그대로 잡힌다 "
        "— 경로가 아니라 값의 모양이 기준이다.",
        None,
        (),
        lambda match: looks_like_real_secret(match.group("literal")),
    ),
    Rule(
        "XML-DTD",
        "BLOCK",
        "문서 파서는 DTD·외부 엔터티를 거부한다",
        re.compile(
            # JVM: 팩토리 생성이 위험 지점이다(기본값이 XXE 허용).
            r"(?:DocumentBuilderFactory|XMLInputFactory|SAXParserFactory|TransformerFactory"
            r"|SchemaFactory|XMLReaderFactory)\.(?:newInstance|createXMLReader)"
            # Python: **import가 아니라 파싱 호출**이 위험 지점이다. 별칭(`import ... as ET`)을
            # 쓰면 모듈 경로가 줄에 남지 않으므로 별칭까지 훑는다.
            r"|\b(?:ET|ElementTree|etree|minidom|objectify)"
            r"\.(?:parse|fromstring|iterparse|XMLParser|XMLPullParser)\s*\("
            r"|\bexpat\.ParserCreate\s*\(|\bmake_parser\s*\("
            # `from xml.etree.ElementTree import fromstring` 형태는 호출부에 모듈명이 없다.
            r"|^\s*from\s+xml\.(?:etree|dom|sax)\b.*\bimport\b.*\b(?:parse|fromstring|iterparse)\b"
        ),
        "기본 설정 XML 파서는 XXE·billion laughs에 열려 있다. "
        "Python 쪽은 expat DTD 핸들러로 막고 있다.",
        "같은 창 안에서 DTD를 끄면(`hardened`) 자동으로 빠진다. 창 밖에서 완화했거나 "
        "완화 호출 이름이 목록에 없으면 후보로 남으니, 그때는 실제 파싱 경로를 열어 확인한다.",
        suffixes=frozenset({".kt", ".kts", ".java", ".py"}),
        # DTD·외부 엔터티를 끄는 호출들. Python expat 핸들러와 JAXP/StAX 기능 플래그를 함께 본다.
        hardened=re.compile(
            r"StartDoctypeDeclHandler|disallow-doctype-decl|FEATURE_SECURE_PROCESSING"
            r"|SUPPORT_DTD|IS_SUPPORTING_EXTERNAL_ENTITIES|IS_REPLACING_ENTITY_REFERENCES"
            r"|external-general-entities|external-parameter-entities|load-external-dtd"
            r"|setEntityResolver|setXIncludeAware|ACCESS_EXTERNAL_(?:DTD|SCHEMA|STYLESHEET)"
            r"|resolve_entities\s*=\s*False|forbid_dtd|setExpandEntityReferences"
        ),
    ),
    Rule(
        "ZIP-NO-BUDGET",
        "WARN",
        "압축 해제량에 예산을 건다 (zip bomb)",
        re.compile(r"ZipFile|ZipInputStream|zipfile\.ZipFile"),
        "선언 크기는 위조 가능하다 — 실제로 읽은 바이트만 믿을 수 있다"
        "(app/ingest/extractors.py 주석).",
        "예산 검사를 이미 통과한 뒤의 재파싱이면 오탐.",
        markable=True,
    ),
    Rule(
        "CACHE-HEADER",
        "WARN",
        "개인정보 응답에 no-store·nosniff를 붙인다",
        re.compile(r"Cache-Control\s*[\"'=:,)]|no-store|nosniff|X-Content-Type-Options"),
        "누락 탐지가 아니라 **분포 확인**용이다. "
        "개인정보 응답 수 대비 헤더 지정 지점이 적으면 빠진 곳이 있다.",
        "여기 걸린 줄은 대부분 정상 — 걸리지 *않은* 개인정보 엔드포인트를 찾는 것이 목적이다.",
        markable=True,
    ),
    Rule(
        "RETENTION-PURGE",
        "WARN",
        "보존 만료 파기는 04:00 KST·500건 배치·중복 실행 방지",
        re.compile(
            r"delete_expired|deleteExpired|purge_expired|purgeExpired|RETENTION_BATCH|"
            r"advisory[_ ]?lock|pg_try_advisory|SKIP LOCKED|@Scheduled|cron\(",
            re.IGNORECASE,
        ),
        "파기 누락은 조용하다 — 30일 정책이 깨져도 아무도 실패 알림을 받지 않는다. "
        "다중 worker 동시 실행은 같은 행을 두 번 지우거나 트랜잭션을 길게 잠근다.",
        "위치 확인용 규칙이다. 걸린 지점이 배치 크기(500)·advisory lock·04:00 KST를 "
        "모두 갖췄는지 사람이 본다.",
        markable=True,
    ),
)


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def _resolves(ref: str) -> bool:
    try:
        _git("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")
    except (OSError, subprocess.CalledProcessError):
        return False
    return True


FULL_SCOPE = "전수"


class ScopeError(Exception):
    """검사 **범위**가 선언과 어긋난다. 결과가 아니라 입력의 문제이므로 종료 코드 2다.

    이 예외가 있는 이유는 하나다 — **범위 결손을 0건과 구분하기 위해서다.** 루트가 사라져
    0건이 된 것과 정말 위반이 없어 0건인 것은 리포트에서 똑같이 보이는데, 전자는
    "확인하지 않음"이고 후자만 "위반 없음"이다.
    """


def iter_files(changed_only: bool, base: str | None = None) -> tuple[list[Path], str]:
    """검사 대상 파일과 **실제로 적용된** 범위 설명을 함께 돌려준다.

    범위를 호출자가 따로 조립하면 폴백이 일어났을 때 리포트가 "변경분"이라고 적으면서
    실제로는 전수를 검사한 파일 수를 싣는다 — 사후에 이 리포트를 읽는 사람이 검사 범위를
    잘못 재구성하게 되므로, 범위 문자열을 결정한 곳에서 그대로 내보낸다.
    """
    if changed_only:
        try:
            # 작업 트리 변경(스테이지 포함)과 미추적 파일.
            out = _git("diff", "--name-only", "HEAD")
            untracked = _git("ls-files", "--others", "--exclude-standard")
            # **브랜치에 커밋된 변경**. 이게 빠지면 에이전트가 구현을 커밋한 순간
            # `--changed`가 0건이 되어, 보안 코드를 한 줄도 안 읽고 게이트가 통과한다.
            committed = ""
            ref = base or DEFAULT_BASE_REF
            if _resolves(ref):
                committed = _git("diff", "--name-only", f"{ref}...HEAD")
            else:
                # base를 못 잡으면 커밋된 변경을 통째로 놓친다. 좁은 범위로 조용히 진행하는
                # 대신 전수로 넓힌다 — 게이트가 틀릴 때는 과검사 쪽으로 틀려야 한다.
                reason = (
                    f"--base {base!r}를 해석할 수 없습니다"
                    if base is not None
                    else f"기본 base {ref!r}가 없어 커밋된 변경을 볼 수 없습니다"
                )
                print(f"[경고] {reason} — 전수 검사로 전환", file=sys.stderr)
                return iter_files(False)
        except (OSError, subprocess.CalledProcessError) as exc:
            print(
                f"[경고] git 변경분 조회 실패({type(exc).__name__}) — 전수 검사로 전환",
                file=sys.stderr,
            )
            return iter_files(False)
        merged = (out + untracked + committed).splitlines()
        names = sorted({line for line in merged if line.strip()})
        # 전수 검사와 같은 범위로 좁힌다 — 그러지 않으면 이 스크립트 자신(규칙 문자열)까지
        # 후보로 잡혀 리포트가 오탐으로 시작한다.
        changed = [
            path
            for name in names
            if (path := REPO_ROOT / name).is_file()
            and path.suffix in SUFFIXES
            and any(path.is_relative_to(REPO_ROOT / root) for root in SCAN_ROOTS)
            and not SKIP_PARTS & set(path.parts)
        ]
        return changed, f"변경분 ({ref}...HEAD + 작업 트리 + 미추적)"

    files: list[Path] = []
    for root in SCAN_ROOTS:
        root_path = REPO_ROOT / root
        # **조용히 건너뛰지 않는다.** 이전 판은 `if not root_path.exists(): continue` 였다 —
        # 디렉터리 하나가 이름이 바뀌면 그 트리 전체가 검사에서 빠지는데 리포트는 여전히
        # "전수"라고 적었다(privacy-gate 판정 §4-quater.3, codex B-4 실측: 루트를 전부
        # 오타로 바꿔도 대상 0건에 성공 종료).
        #
        # 루트가 사라지는 정상 경우(예: Phase 8 의 `app/` 삭제)는 **`SCAN_ROOTS` 에서 빼는
        # 커밋**으로 처리해야 하고, 그 diff 가 리뷰에 올라가는 것이 요점이다.
        if not root_path.exists():
            raise ScopeError(
                f"선언된 스캔 루트가 없다: {root} — 이름이 바뀌었거나 지워졌다면 "
                "SCAN_ROOTS 에서 빼는 커밋으로 처리하라. 조용히 건너뛰면 그 트리 전체가 "
                "검사에서 빠진 채 리포트는 '전수'라고 적는다."
            )
        found = [
            path
            for path in root_path.rglob("*")
            if path.is_file() and path.suffix in SUFFIXES and not SKIP_PARTS & set(path.parts)
        ]
        # 디렉터리는 남았는데 내용이 빠진 경우. 위와 같은 이유로 실패시킨다.
        if not found:
            raise ScopeError(
                f"스캔 루트 {root} 에 검사 대상 파일이 하나도 없다 (확장자: "
                f"{', '.join(sorted(SUFFIXES))}). 디렉터리는 남았는데 내용이 빠졌는지 확인하라."
            )
        files.extend(found)
    return sorted(files), FULL_SCOPE


@dataclass
class ScanResult:
    hits: dict[str, list[Hit]]
    #: 규칙별로 2차 판정에서 뺀 건수. 조용히 지우면 규칙이 언제부터 아무것도 안 보는지
    #: 알 수 없으므로 리포트에 함께 찍는다.
    suppressed: dict[str, dict[str, int]]
    #: **검사하지 못한** 논리 줄 (상한에 걸려 끊긴 호출). 빈 목록이 아니면 BLOCK 이다 —
    #: "검사했는데 없음"과 "검사하지 못함"은 다르다(게이트 09 M-03).
    unscanned: list[tuple[Path, int]] = field(default_factory=list)
    #: 호출 지점 표기로 눌린 적중. **전건**을 리포트에 싣는다 — 개수만 남기면 무엇이
    #: 눌렸는지 아무도 되짚지 않는다(그 결함이 R-1 을 리포트 안에서 보이지 않게 했다).
    suppressions: list[Suppression] = field(default_factory=list)
    #: 표기 자체의 결함(고아·사유 누락·알 수 없는 규칙·상한 초과). 있으면 스캔 실패다.
    marker_problems: list[str] = field(default_factory=list)


#: 논리 줄 하나가 삼킬 수 있는 물리 줄 상한. 없으면 **깨진 괄호 하나가 파일 전체를 한
#: 줄로 만들어** 오탐이 폭발한다. 실제 로그·예외 호출은 길어야 열 줄 남짓이다.
MAX_LOGICAL_LINE_SPAN = 40

#: 여는 따옴표 후보. 긴 것부터 본다 — `"""` 를 `"` 로 읽으면 즉시 닫힌 것으로 오인한다.
_QUOTES = ('"""', "'''", '"', "'")


@dataclass(frozen=True)
class LexState:
    """물리 줄 **사이에 유지되는** 어휘 상태.

    ## 줄마다 초기화하면 왜 새는가 (게이트 09 M-03 · codex K-2)

    앞선 판은 `_depth_after` 가 진입할 때마다 `quote = None` 으로 시작했다. 그래서 여러 줄에
    걸친 raw string(`\"\"\"` … `\"\"\"`) 안의 `)` 가 **코드로 읽혀** 호출이 조기에 닫혔고,
    그 뒤 인자는 다른 논리 줄로 밀려 규칙이 아예 발화하지 못했다. 은폐가 아니라
    **탐지형 fail-open** 이라 리포트에는 아무 흔적도 남지 않는다.
    """

    depth: int = 0
    quote: str | None = None
    #: 블록 주석 **깊이**. Boolean 이 아니다 — Kotlin 은 블록 주석 중첩을 허용하므로
    #: `/* 바깥 /* 안쪽 */ 아직 바깥 */` 에서 첫 `*/` 로 닫아 버리면 **주석 본문이 코드로
    #: 새어 나온다.** `c2255dc` 가 닫은 것(코드가 주석으로 새는 것)과 **상보**이고 같은
    #: 함수의 다른 입력 종류다(게이트 10 R-2).
    #:
    #: 파이썬에는 블록 주석이 없으므로 이 값은 `.py` 에서 늘 0이다.
    block_depth: int = 0

    @property
    def open(self) -> bool:
        """논리 줄이 아직 닫히지 않았는가."""
        return self.depth > 0 or self.quote is not None or self.block_depth > 0


@dataclass(frozen=True)
class LogicalLine:
    """이어 붙인 논리 줄 하나.

    @param number 시작 **물리** 줄 번호. 사람이 파일을 열어 찾아갈 수 있어야 한다.
    @param complete 괄호·따옴표가 닫힌 채로 끝났는가. `False` 는 상한에 걸려 **끊긴** 것이며,
        그 구간은 **검사되지 않았다** — 호출부가 fail-closed 로 다뤄야 한다.
    """

    number: int
    text: str
    complete: bool


def _advance(line: str, state: LexState, python_syntax: bool) -> tuple[LexState, str, str]:
    """물리 줄 하나를 지난 뒤의 어휘 상태와 **주석을 뺀 코드 부분**.

    문자열·주석 안의 괄호는 세지 않는다. `#` 는 **`.py` 에서만** 줄 주석이다 —
    `.ts` 의 `#private` 필드를 주석으로 읽으면 그 줄의 나머지가 사라져 **과소 결합**이
    되고, 그것은 이 함수가 선언한 "틀릴 때는 이어 붙이는 쪽" 과 반대 방향이다(M-23).

    ## 코드 부분을 함께 돌려주는 이유 (codex stop-time 게이트, 2026-08-14)

    논리 줄 텍스트에 주석이 섞여 있으면 그것을 읽는 **인자 파서가 주석 안의 괄호를 코드로
    센다.** 실측된 누락:

        logger.info(
            "완료 {} {}",
            draft.stats.count, // 건수) 설명     ← 이 `)` 가 인자 구간을 조기에 닫는다
            draft.value,                          ← 잘린 구간 밖 = **검사되지 않는다**
        )

    잘린 구간에 남은 것이 `draft.stats.count`(안전)뿐이라 2차 판정이 "찾았고 전부 안전"으로
    빠진다. **안전한 접근이 위험한 접근의 방패가 되는** 형태다. 주석이 없는 같은 코드는
    정상으로 잡히므로, 주석 유무가 검출을 바꾸고 있었다.

    깊이를 세는 곳과 텍스트를 만드는 곳이 **같은 순회**여야 한다. 따로 두면 둘이 서로 다른
    문자열을 보게 되고, 그 어긋남이 정확히 위 결함의 모양이다.
    """
    depth, quote, block = state.depth, state.quote, state.block_depth
    kept: list[str] = []
    # 주석 조각. 표기(`privacy-allow:`) 추출은 **이 층의 일이다** — 억제 층이 자기 주석
    # 파서를 가지면 중첩 주석 갈래(R-2)를 그대로 되산다(§4-octies.5).
    comment: list[str] = []
    index = 0
    length = len(line)
    while index < length:
        rest = line[index:]
        if block > 0:
            # **중첩을 센다.** 첫 `*/` 로 닫으면 바깥 주석 본문이 코드로 새어 나온다 —
            # 그 본문의 `)` 하나가 인자 구간을 끊어 뒤를 미검사로 만든다(R-2).
            if rest.startswith("/*"):
                block += 1
                index += 2
            elif rest.startswith("*/"):
                block -= 1
                index += 2
                if block == 0:
                    kept.append(" ")  # 토큰이 붙지 않게 자리만 남긴다
            else:
                comment.append(line[index])
                index += 1
            continue
        if quote is not None:
            # 문자열 **안**은 그대로 남긴다. f-string 보간(`{draft.value}`)이 여기 산다.
            if rest.startswith("\\"):
                kept.append(line[index : index + 2])
                index += 2
                continue
            if rest.startswith(quote):
                kept.append(quote)
                index += len(quote)
                quote = None
                continue
            kept.append(line[index])
            index += 1
            continue
        if rest.startswith("//") or (python_syntax and rest.startswith("#")):
            comment.append(rest)
            break  # 줄 주석 — 나머지는 코드가 아니다
        if not python_syntax and rest.startswith("/*"):
            block += 1
            index += 2
            continue
        for opener in _QUOTES:
            if rest.startswith(opener):
                quote = opener
                kept.append(opener)
                index += len(opener)
                break
        else:
            if rest[0] == "(":
                depth += 1
            elif rest[0] == ")":
                depth = max(0, depth - 1)
            kept.append(line[index])
            index += 1
    return LexState(depth=depth, quote=quote, block_depth=block), "".join(kept), "".join(comment)


def scan_markers(lines: list[str], python_syntax: bool = False) -> list[Marker]:
    """물리 줄을 훑어 억제 표기를 뽑는다. **①층이 소유한다.**

    `logical_lines` 와 같은 어휘 분석기(`_advance`)를 쓴다 — 표기가 문자열 리터럴 안에
    적혀 있으면 주석이 아니므로 잡히지 않아야 하고, 그 판별은 이미 여기 있다.

    상태는 물리 줄 사이에 유지된다. 여러 줄 주석 안의 표기도 그 줄 번호로 잡힌다.
    """
    found: list[Marker] = []
    state = LexState()
    for number, line in enumerate(lines, start=1):
        state, code, comment = _advance(line, state, python_syntax)
        if MARKER_PREFIX in comment:
            found.extend(parse_markers(comment, number, standalone=not code.strip()))
    return found


def logical_lines(lines: list[str], python_syntax: bool = False) -> list[LogicalLine]:
    """물리 줄을 **논리 줄**(괄호가 닫힐 때까지)로 묶는다.

    ## AST를 쓰지 않는 이유 (privacy-gate 판정 §4-quater.2)

    이 스캐너는 `.py`·`.kt`·`.kts`·`.ts`·`.tsx`·`.java` 6종을 본다. Kotlin AST를 파이썬에서
    얻으려면 외부 도구가 붙고, **그 도구가 없는 환경에서 스캐너가 조용히 0건**이 된다 —
    C-04와 같은 실패를 새로 만드는 셈이다. 대신 문자열·주석 상태를 물리 줄 사이에 유지하는
    작은 어휘 분석기를 둔다(`LexState`).

    ## 상한은 fail-closed 다 (게이트 09 M-03)

    앞선 판은 상한 40줄에 닿으면 **조용히 새 논리 줄을 시작**했다. 그러면 41줄짜리 호출은
    어느 논리 줄에서도 온전히 보이지 않고, 리포트에는 아무 흔적도 남지 않는다 —
    "검사했는데 없음"과 "검사하지 못함"이 구분되지 않는 상태다. 이 스크립트가 `--changed`
    0건과 스캔 루트 부재에서 이미 거부한 바로 그 형태다.

    지금은 끊긴 논리 줄에 `complete=False` 를 달아 호출부가 **후보로 승격**하게 한다.
    """
    joined: list[LogicalLine] = []
    index = 0
    while index < len(lines):
        start = index
        state = LexState()
        parts: list[str] = []
        while index < len(lines) and index - start < MAX_LOGICAL_LINE_SPAN:
            state, code, _comment = _advance(lines[index], state, python_syntax)
            # **주석을 뺀 코드만 잇는다.** 사유는 `_advance` KDoc — 주석 안의 괄호를 인자
            # 파서가 코드로 세면 인자 구간이 조기에 닫히고 그 뒤가 미검사로 남는다.
            stripped = code.strip()
            if stripped:
                parts.append(stripped)
            index += 1
            if not state.open:
                break
        joined.append(LogicalLine(number=start + 1, text=" ".join(parts), complete=not state.open))
    return joined


#: **표기로 누를 수 없어야 하는 규칙.** §4-octies.3 이 지정한 여섯이다 — 이들에서
#: "오탐이니 눌러 달라"가 나오면 그것은 표기가 아니라 **판정 요청**이어야 한다.
#: 목록을 주석으로만 두면 다음 사람이 한 줄을 `True` 로 바꾼다.
UNMARKABLE_RULES = frozenset(
    {
        "LLM-RAW-INPUT",
        "OWNERSHIP-403",
        "PLAINTEXT-PERSIST",
        "SECRET-LITERAL",
        "LLM-VENDOR-SDK",
        "XML-DTD",
    }
)

for _rule in RULES:
    if _rule.id in UNMARKABLE_RULES and _rule.markable:
        raise AssertionError(
            f"{_rule.id} 는 표기로 누를 수 없어야 한다(§4-octies.3) — markable=True 가 붙었다."
        )

#: `multiline` 규칙은 반드시 `opener` 를 갖는다 — 없으면 그 규칙만 fail-closed 밖으로 빠진다.
for _rule in RULES:
    if _rule.multiline and _rule.opener is None:
        raise AssertionError(
            f"multiline 규칙 {_rule.id} 에 opener 가 없다 — 끊긴 논리 줄에서 이 규칙이 "
            "볼 호출이 있었는지 판정할 수 없어 fail-closed 가 그 규칙만 놓친다."
        )

#: 끊긴 논리 줄이 **실제로 무언가를 가렸는가**를 판정할 때 쓰는 호출 시작 모양 전부.
ARG_LIST_OPENERS = tuple(rule.opener for rule in RULES if rule.multiline and rule.opener)


@dataclass(frozen=True)
class Hit:
    """적중 하나. **호출 하나에 하나씩** 난다(줄 하나에 하나가 아니다).

    ## 왜 줄이 아니라 호출인가 (§4-novies.3 — X-1)

    앞선 판의 적중은 `(path, physical_line, text)` 였고 억제 키도 그 셋이었다. 그래서 표기
    하나가 **그 줄의 그 규칙 적중 전부**를 눌렀다. 한 논리 줄에 호출이 둘이면 안전한 쪽에
    붙인 표기가 위험한 쪽까지 눌렀다 — `_advance`·`_is_candidate` KDoc 이 두 번 이름 붙인
    **"안전한 접근이 위험한 접근의 방패가 된다"** 가 세 번째 층(억제)에 남아 있었다.

    입도를 잘게 내는 것은 **검출 층의 일**이다. 억제 층은 더 풍부한 적중을 받을 뿐 여전히
    아무것도 파싱하지 않는다(금지 1 유지).

    @param line 물리 줄. 표기를 **찾을 자리**이자 리포트 위치. **키가 아니다**.
    @param call_ref 파일 안에서 이 호출을 유일하게 지목한다. **내용에서 나온다** —
        위치나 순서에서 나오면 같은 줄의 호출 순서를 바꿨을 때 판정이 다른 호출로
        옮겨 붙는다(음성 대조 5가 그것을 잡는다).

        **억제 키가 아니다.** 표기가 이 값을 담지 않으므로([MARKER_RE] 의 필드는 셋뿐이다)
        [suppress] 는 이것을 비교하지 않는다. 쓰이는 곳은 둘이다 — 리포트에 어느 호출이
        눌렸는지 적는 것, 그리고 한 표기가 닿은 적중들이 **서로 다른 호출인지**를 불변량 4
        진단에서 보여 주는 것. 이 필드를 억제 조건으로 승격하려면 표기 문법부터 바꿔야
        하고, 그 갈래는 §4-undecies.1 에서 기각됐다(사람이 해시를 둘씩 적게 된다).
    @param digest 인자 구간 지문 8자. 표기가 결속하는 값이다. 산출할 수 없으면 빈 문자열
        이고, 그때 이 적중은 **억제 대상이 아니다**(4′ — 닫힘).
    @param text 리포트용. 억제 판정에 쓰지 않는다.
    """

    path: Path
    line: int
    call_ref: str
    digest: str
    text: str


#: 파일별 표기 색인. **어휘 층(①)이 만든다.**
MarkerIndex = dict[Path, list[Marker]]

#: 인자 구간을 훑을 때 넘어갈 문자열 여는 기호. `_QUOTES` 와 같은 이유로 긴 것이 먼저다.
_ARG_QUOTES = ('"""', "'''", '"', "'")


def _argument_span(text: str, start: int) -> tuple[str, int] | None:
    """`start` 이후 첫 여는 괄호부터 **짝 괄호 직전**까지의 인자 구간과 닫는 괄호 위치.

    닫는 괄호를 찾지 못하거나 여는 괄호가 없으면 `None` — 그 적중은 지문을 가질 수 없고
    따라서 **억제 대상이 아니다**(4′). 상한 초과 구간이 이미 BLOCK 인 것과 같은 성질이다.

    문자열 리터럴 안의 괄호는 세지 않는다. 주석은 셀 필요가 없다 — 이 함수에 오는 텍스트는
    어휘 층이 이미 주석을 뺀 것이다(`_advance`). **여기서 주석을 다시 다루지 않는 것이
    금지 2 를 지키는 방식이다.**
    """
    open_at = text.find("(", start)
    if open_at < 0:
        return None
    depth = 0
    index = open_at
    quote: str | None = None
    while index < len(text):
        rest = text[index:]
        if quote is not None:
            if rest.startswith("\\"):
                index += 2
                continue
            if rest.startswith(quote):
                index += len(quote)
                quote = None
                continue
            index += 1
            continue
        for opener in _ARG_QUOTES:
            if rest.startswith(opener):
                quote = opener
                index += len(opener)
                break
        else:
            if rest[0] == "(":
                depth += 1
            elif rest[0] == ")":
                depth -= 1
                if depth == 0:
                    return text[open_at + 1 : index], index
            index += 1
    return None


def _digest(value: str) -> str:
    """정규화한 문자열의 SHA-256 앞 [DIGEST_LENGTH] 자.

    **정규화는 공백 접기 하나뿐이다.** 그 이상 하면(따옴표 통일·인자 정렬 등) 지문이
    실제 내용보다 거칠어지고, 거칠어진 만큼 ⓑ(인자 추가·교체)를 놓친다. 지문이 빡빡해서
    무관한 편집에 깨지는 쪽은 **닫히는 방향**의 실패라 그쪽을 택한다(§4-novies.5).
    """
    return hashlib.sha256(" ".join(value.split()).encode("utf-8")).hexdigest()[:DIGEST_LENGTH]


@dataclass(frozen=True)
class Suppression:
    """억제된 적중 하나. 리포트에 **전건** 실린다."""

    rule_id: str
    path: Path
    line: int
    call_ref: str
    digest: str
    reason: str


def suppress(
    hits: dict[str, list[Hit]],
    markers: MarkerIndex,
    markable_rules: frozenset[str] | None = None,
) -> tuple[dict[str, list[Hit]], list[Suppression]]:
    """표기로 눌린 적중을 걷어낸다. **③층 — 재파싱하지 않는다.**

    ## 불변량: `suppress(hits, ∅) == hits`

    표기가 하나도 없으면 이 함수는 **항등 함수**다. 앞선 판에서는 이 명제를 쓸 수조차
    없었다 — 검출과 억제가 같은 루프에 있어 억제가 검출을 **가로챘다**(첫 적중이 눌리면
    나머지가 탐색되지 않았다. R-1 의 직접 원인이다). 층을 나눈 이유가 이것이고,
    속성 테스트가 이 명제를 고정한다.

    ## 이 함수가 하지 않는 것 (넷)

    - **재파싱 금지.** 표현식·괄호·주석을 다시 읽지 않는다. 다섯 갈래 중 셋이 "아래 층이
      틀렸는데 억제가 그것을 조용한 통과로 바꾼" 형태였다 — 그 표면 자체를 없앤다.
    - **표기 추출을 스스로 하지 않는다.** 주석 판별은 ①층의 일이다. 여기서 자기 주석
      파서를 가지면 중첩 주석 갈래(R-2)를 그대로 되산다.
    - **검출 루프 안에서 불리지 않는다.** ②층이 전량을 낸 **뒤에** 한 번 돈다.
    - **물리 줄을 특정할 수 없는 적중은 억제 대상이 아니다**(닫힘).

    ## 표기가 적중에 닿는 규칙 (e′ 정정문 — privacy-gate §4-undecies.1)

    표기는 `(path, rule_id, digest)` 가 일치하고 **물리 줄이 근접한**(같은 줄, 또는 단독
    주석이면 바로 아래 줄) 적중 **하나**를 억제한다. 어떤 경우에도 구간·줄·영역을 누르지
    않는다.

    **`call_ref` 는 결속 대상이 아니다 — 표기가 그것을 담지 않기 때문이다.** 표기 문법
    ([MARKER_RE])의 필드는 규칙 id·지문·사유 셋뿐이고, 사람이 손으로 적는 주석이라 내용
    해시를 둘씩 적게 할 수 없다. 같은 지문·같은 줄에 적중이 둘 이상이면 아래 불변량 4가
    발화해 **아무것도 누르지 않는다.**

    ### 이 문단이 한 번 거짓이었다 (같은 종류의 재발이라 남긴다)

    앞선 판은 여기에 *"`(path, call_ref, rule_id, digest)` **넷 모두**"* 라고 적혀 있었다.
    구현은 셋만 비교하는데 **문서가 실물보다 강한 보장을 선언**하고 있었다 — 이 저장소가
    반복해 겪은 "선언한 범위 ≠ 실제 도달"의 그 형태다. 근원은 설계문이 표기 문법과 억제
    키를 **같은 절에서** 정하면서 서로 맞는지 확인하지 않은 것이고(§4-novies.4 자기모순),
    구현자가 그 문장을 그대로 옮겨 적었다. **명세를 옮겨 적을 때 실물과 대조한다.**

    ### 잔여 위험과 그 처분 (위협 모델 **밖**)

    커밋 권한자가 32비트 지문 충돌을 **의도적으로 제작**하면 적중 1건인 채 내용만 교체되는
    경로가 열린다. 위협 모델에 넣지 않는다 — privacy-gate §4-undecies.1 판정 요약:

    > 충돌을 제작할 수 있는 사람에게는 **더 싼 경로가 여럿 있고**(표기를 지우고 유출을 다른
    > 줄에 쓰기, 새 표기를 그럴듯한 사유로 달기, CI 스텝 삭제, 스캐너 자체 수정),
    > 충돌이 사는 것은 "CI 가 빨개지지 않는 것" 하나뿐이다. 악성 인자는 여전히 diff 에
    > 보인다 — **자동 게이트는 피하면서 사람 게이트는 피하지 못하는** 공격이다.
    > 따라서 방어는 코드가 아니라 리뷰다(X-3/C-09 전례).

    지문을 12자로 늘리는 것도 기각됐다. 사고 검출에 32비트로 충분하고(편집이 같은 지문을
    낼 확률 ≈ 2⁻³²), 늘리는 이유는 위협 모델 밖에만 있다 — 모든 표기를 churn 시키는
    **이동**이지 방어가 아니다.

    ## 불변량 4 — `|suppressed_by(marker)| ≤ 1`

    한 표기가 둘 이상의 적중에 닿으면 **아무것도 누르지 않는다.** 사람이 사유를 쓸 때 본
    것과 눌리는 것이 같다는 보장이 깨진 상태이므로, 그때는 닫히는 쪽으로 간다.
    같은 판정을 [marker_problems] 가 오류로도 낸다 — 조용히 통과하지 않게.
    """
    allowed = (
        markable_rules
        if markable_rules is not None
        else frozenset(rule.id for rule in RULES if rule.markable)
    )
    reach = suppression_map(markers, hits, allowed)
    # 불변량 4 를 어긴 표기는 억제력을 잃는다. 키는 표기 자신이다.
    effective = {key: found[0] for key, found in reach.items() if len(found) == 1}
    claimed = {(hit.path, rule_id, hit.call_ref) for (_, _, rule_id), hit in effective.items()}

    kept: dict[str, list[Hit]] = {}
    removed: list[Suppression] = []
    for rule_id, entries in hits.items():
        survivors = [hit for hit in entries if (hit.path, rule_id, hit.call_ref) not in claimed]
        if survivors:
            kept[rule_id] = survivors
    for (path, marker_line, rule_id), hit in sorted(
        effective.items(), key=lambda item: (item[0][0].as_posix(), item[0][1], item[0][2])
    ):
        marker = next(
            m for m in markers.get(path, []) if m.line == marker_line and m.rule_id == rule_id
        )
        removed.append(
            Suppression(
                rule_id=rule_id,
                path=path,
                line=hit.line,
                call_ref=hit.call_ref,
                digest=hit.digest,
                reason=marker.reason,
            )
        )
    return kept, removed


def suppression_map(
    markers: MarkerIndex,
    hits: dict[str, list[Hit]],
    allowed: frozenset[str],
) -> dict[tuple[Path, int, str], list[Hit]]:
    """표기 하나가 닿는 적중들. **억제와 표기 진단이 같은 계산을 쓴다.**

    두 벌로 두면 "억제한 것"과 "고아라고 판정한 것"이 갈린다 — 이 저장소가 반복해 겪은
    형태이고(`CLAUDE.md` 변경 이력), 그 갈림이 조용한 쪽은 늘 억제였다.
    """
    reach: dict[tuple[Path, int, str], list[Hit]] = {}
    for rule_id, entries in hits.items():
        if rule_id not in allowed:
            # **`markable=False` 규칙은 표기로 눌리지 않는다**(방어, §4-octies.3).
            # 문제로 보고만 하고 억제까지 해 주면 "판정 요청이어야 한다"는 규칙이
            # 경고문으로 내려앉는다 — 닫히는 쪽으로 둔다.
            continue
        for hit in entries:
            for marker in markers.get(hit.path, []):
                if _marker_touches(marker, rule_id, hit):
                    reach.setdefault((hit.path, marker.line, rule_id), []).append(hit)
    return reach


def _marker_touches(marker: Marker, rule_id: str, hit: Hit) -> bool:
    """이 표기가 이 적중에 닿는가. 하나라도 어긋나면 닿지 않는다.

    비교하는 것은 **규칙 id · 사유 유무 · 지문 존재 · 지문 일치 · 물리 줄 근접** 다섯이다.
    `hit.call_ref` 는 **여기서 비교하지 않는다** — 사유는 [suppress] docstring 의 e′ 정정문.
    """
    if marker.rule_id != rule_id or not has_visible_reason(marker.reason):
        return False  # 방어 c·d
    if not marker.digest or not hit.digest:
        return False  # 4′ — 지문 없는 쪽은 억제에 참여하지 않는다
    if marker.digest != hit.digest:
        return False
    if marker.line == hit.line:
        return True
    return marker.standalone and marker.line == hit.line - 1


def marker_problems(
    markers: MarkerIndex,
    hits: dict[str, list[Hit]],
    rules: tuple[Rule, ...] = RULES,
) -> list[str]:
    """표기 자체의 결함. **하나라도 있으면 스캔이 실패한다.**

    표기가 새 면제 목록이 되지 않게 거는 방어들이다(§4-octies.3). 특히 **고아 표기 실패**는
    휴리스틱에는 없던 자가 정리 기제다 — 코드가 바뀌어 위험이 사라졌는데 표기만 남는 것을
    막는다.
    """
    known = {rule.id for rule in rules}
    markable = frozenset(rule.id for rule in rules if rule.markable)
    problems: list[str] = []

    reach = suppression_map(markers, hits, markable)

    total = 0
    for path, file_markers in sorted(markers.items()):
        for marker in file_markers:
            total += 1
            shown = path.relative_to(REPO_ROOT) if path.is_relative_to(REPO_ROOT) else path
            where = f"{shown}:{marker.line}"
            if marker.rule_id not in known:
                problems.append(f"{where} — 알 수 없는 규칙 id `{marker.rule_id}`")
                continue
            if not has_visible_reason(marker.reason):
                problems.append(
                    f"{where} — 사유에 보이는 문자가 {visible_length(marker.reason)}개다"
                    f"(최소 {MIN_REASON_VISIBLE}). "
                    f"`privacy-allow: {marker.rule_id} @지문8자 — 사유`. "
                    "공백·ZWSP 같은 보이지 않는 문자는 사유가 아니다"
                )
                continue
            if marker.rule_id not in markable:
                problems.append(
                    f"{where} — `{marker.rule_id}` 는 표기로 누를 수 없다. "
                    "이 규칙의 오탐은 표기가 아니라 **판정 요청**이어야 한다(§4-octies.3)"
                )
                continue
            if not marker.digest:
                problems.append(
                    f"{where} — 지문이 없다 (`privacy-allow: {marker.rule_id} @지문8자 — 사유`). "
                    "지문 없는 표기는 아무것도 누르지 않는다. "
                    "`--update-markers` 로 채운다(로컬 전용)"
                )
                continue
            found = reach.get((path, marker.line, marker.rule_id), [])
            if len(found) > 1:
                # 불변량 4 위반. 지문이 같은 적중이 둘이면 사람이 무엇을 승인했는지
                # 확정할 수 없다 — 억제도 하지 않고(위 `suppress`) 실패로도 알린다.
                where_hits = ", ".join(f"{hit.line}행/{hit.call_ref}" for hit in found)
                problems.append(
                    f"{where} — 표기 하나가 적중 {len(found)}건에 닿는다({where_hits}). "
                    "사유가 심사받은 범위와 눌리는 범위가 어긋나므로 아무것도 누르지 않았다. "
                    "호출마다 표기를 따로 단다"
                )
                continue
            if not found:
                problems.append(
                    f"{where} — **고아 표기**: 이 자리에 지문 `@{marker.digest}` 인 "
                    f"`{marker.rule_id}` 적중이 없다. 위험이 사라졌으면 표기를 지우고, "
                    "인자가 바뀐 것이면 `--update-markers` 로 갱신한 뒤 **사유를 다시 본다**"
                )

    if total > MARKER_BUDGET:
        problems.append(
            f"표기가 {total}개로 상한 {MARKER_BUDGET}을 넘었다. "
            "상한을 올리려면 MARKER_BUDGET 을 고치는 커밋이 필요하고, 그 diff 가 리뷰에 올라간다"
        )
    return problems


def _candidates(
    rule: Rule,
    line: str,
    lines: list[str],
    number: int,
    drop: Callable[[str, str], None],
) -> list[tuple[str, str, str]]:
    """이 줄에서 `rule` 의 후보 **전부**를 `(call_ref, digest, 발췌)` 로 낸다.

    ## 왜 `bool` 이 아니라 목록인가 (§4-novies.3)

    앞선 판은 첫 후보에서 멈추고 "이 줄은 후보다" 하나만 냈다. 그러면 억제도 줄 단위가
    될 수밖에 없고, 그것이 X-1 이다. 호출별로 내야 표기도 호출별로 결속된다.

    `call_ref` 는 **호출 텍스트**(수신자·메서드·인자 전체)의 지문이고 `digest` 는
    **인자 구간만**의 지문이다. 둘을 나눈 이유: 수신자만 바뀌면(`logger` → `log`)
    호출은 다른 호출이지만 인자는 같다. 그때 표기가 옮겨 붙지 않아야 한다.

    둘 다 **내용에서 나온다.** 위치·순서에서 뽑으면 같은 줄의 호출 둘을 맞바꿨을 때 표기가
    다른 호출로 옮겨 붙는다 — 음성 대조 5가 정확히 그것을 잡는다.

    ## `search` 하나로는 왜 새는가 (게이트 10 R-1 — 양 레인 독립 합의)

    앞선 판은 `rule.pattern.search(line)` 으로 **첫 적중 하나만** 2차 판정에 넘겼다.
    그 하나가 안전하면 같은 줄의 **나머지 호출이 통째로 후보에서 빠진다.**

        if (ok) logger.info("건수 {}", draft.stats.count) else logger.info("본문 {}", draft.value)
        compute().also { logger.info("건수 {}", draft.stats.count) }
            .also { logger.info("본문 {}", draft.value) }        ← 결합하면 한 줄이 된다

    `_advance` KDoc 이 이름 붙인 **"안전한 접근이 위험한 접근의 방패가 된다"**가 호출과
    호출 **사이**에 그대로 남아 있었다. 같은 형태를 c2255dc 에서 한 번 닫았는데, 그때는
    한 호출 **안**만 보고 줄 안의 호출 개수를 보지 않았다. 순서를 뒤집으면(위험이 앞)
    잡히던 것이 그 증거다.

    지문을 낼 수 없는 적중(괄호가 안 닫힘 등)은 `digest=""` 로 낸다 — **보고는 하되 억제는
    받지 못한다**(4′). 조용히 빼면 그것이 곧 fail-open 이다.
    """
    found: list[tuple[str, str, str]] = []
    for match in rule.pattern.finditer(line):
        if rule.refine is not None and not rule.refine(match):
            drop(rule.id, rule.refine_reason)
            continue
        if rule.hardened is not None:
            index = number - 1
            window = lines[max(0, index - rule.hardened_before) : index + rule.hardened_after + 1]
            if any(rule.hardened.search(near) for near in window):
                drop(rule.id, "같은 창에서 완화 조치 확인")
                continue
        # **`match.start()` 에서 찾는다. `match.end()` 가 아니다.**
        # `LOG-BODY` 의 패턴은 여는 괄호까지 삼키고 그 뒤는 lookahead 다
        # (`{LOG_CALL}\s*\((?=...)`). `match.end()` 부터 찾으면 **그 호출의 괄호를 이미
        # 지나쳐** 인자 안의 다음 괄호를 잡거나 아무것도 못 찾는다. 실측: 괄호 없는
        # `print(f"문서 id: {...}")` 가 지문 없음으로 나와 표기가 붙지 못했다.
        # 규칙마다 패턴이 어디서 끝나는지 다르므로 **호출의 시작**을 기준으로 삼는다.
        span = _argument_span(line, match.start())
        if span is None:
            # 지문 없음 = 억제 불가. 호출을 특정할 수 없으므로 call_ref 는 매치 텍스트로
            # 만든다 — 리포트에서 구분되기만 하면 되고, 어차피 표기가 닿지 않는다.
            found.append((_digest(match.group(0)), "", line[match.start() : match.start() + 160]))
            continue
        args, close = span
        found.append(
            (
                _digest(line[match.start() : close + 1]),
                _digest(args),
                line[match.start() : match.start() + 160],
            )
        )
    return found


def scan(files: list[Path], rule_filter: set[str]) -> ScanResult:
    hits: dict[str, list[Hit]] = {}
    suppressed: dict[str, dict[str, int]] = {}
    unscanned: list[tuple[Path, int]] = []
    markers: MarkerIndex = {}

    def drop(rule_id: str, reason: str) -> None:
        suppressed.setdefault(rule_id, {}).setdefault(reason, 0)
        suppressed[rule_id][reason] += 1

    def record(
        rule_id: str,
        path: Path,
        number: int,
        seen: dict[str, int],
        candidates: list[tuple[str, str, str]],
    ) -> None:
        """후보를 적중으로 옮긴다. 같은 호출 텍스트가 한 파일에 여러 번이면 순번을 붙인다.

        순번은 **완전히 같은 텍스트끼리만** 매긴다. 같은 텍스트는 맞바꿔도 구별되지 않으므로
        순번이 순서 의존을 들여오지 않는다 — 음성 대조 5가 재는 것은 **서로 다른** 호출의
        순서이고, 그쪽은 내용이 달라 지문으로 갈린다.
        """
        for call_ref, digest, text in candidates:
            seen[call_ref] = seen.get(call_ref, 0) + 1
            ordinal = seen[call_ref]
            hits.setdefault(rule_id, []).append(
                Hit(
                    path=path,
                    line=number,
                    call_ref=call_ref if ordinal == 1 else f"{call_ref}~{ordinal}",
                    digest=digest,
                    text=text,
                )
            )

    rules = [rule for rule in RULES if not rule_filter or rule.id in rule_filter]
    for path in files:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        posix = path.as_posix()
        # ①층이 표기 색인을 만든다. 억제 층은 이 색인만 보고 재파싱하지 않는다.
        found_markers = scan_markers(lines, python_syntax=path.suffix == ".py")
        if found_markers:
            markers[path] = found_markers
        # 같은 호출 텍스트의 등장 횟수. **파일 단위**다 — call_ref 가 파일 안에서 유일해야
        # 표기가 다른 파일의 같은 호출까지 누르는 일이 없다.
        seen: dict[str, int] = {}
        for number, line in enumerate(lines, start=1):
            stripped = line.strip()
            if stripped.startswith(("#", "//", "*", '"""')):
                continue  # 주석·docstring은 후보에서 뺀다(설명문이 대량 오탐을 만든다)
            for rule in rules:
                if rule.multiline:
                    continue  # 아래 논리 줄 순회가 맡는다
                if rule.suffixes and path.suffix not in rule.suffixes:
                    continue
                if any(allowed in posix for allowed in rule.sanctioned):
                    continue
                record(rule.id, path, number, seen, _candidates(rule, line, lines, number, drop))

        # 인자 목록을 보는 규칙은 **논리 줄**에서 판정한다. ktlint가 강제하는 Kotlin
        # 줄바꿈 스타일에서 `logger.info(` 호출이 여러 줄로 갈리면 줄 단위 판정은
        # 전 줄 무적중이었다(privacy-gate 판정 §4-quater.2).
        for logical in logical_lines(lines, python_syntax=path.suffix == ".py"):
            # **끊긴 논리 줄은 fail-closed 다.** 상한에 걸려 못 읽은 호출을 조용히 넘기면
            # "검사하지 못함"이 "위반 없음"으로 집계된다 — 이 스크립트가 스캔 루트 부재와
            # `--changed` 0건에서 이미 거부한 형태다(게이트 09 M-03).
            if not logical.complete and any(o.search(logical.text) for o in ARG_LIST_OPENERS):
                unscanned.append((path, logical.number))
                continue
            line = logical.text
            number = logical.number
            if line.startswith(("#", "//", "*", '"""')):
                continue
            for rule in rules:
                if not rule.multiline:
                    continue
                if rule.suffixes and path.suffix not in rule.suffixes:
                    continue
                if any(allowed in posix for allowed in rule.sanctioned):
                    continue
                record(rule.id, path, number, seen, _candidates(rule, line, lines, number, drop))

    # ── ③층. **②가 전량을 낸 뒤에 한 번 돈다.** ────────────────────────────────
    # 검출 루프 안에서 억제하면 억제가 검출을 가로챈다 — R-1 의 직접 원인이었다.
    problems = marker_problems(markers, hits, tuple(rules))
    kept, removed = suppress(hits, markers)
    return ScanResult(kept, suppressed, unscanned, removed, problems)


def render(result: ScanResult, scanned: int, scope: str) -> tuple[str, int]:
    hits = result.hits
    lines = [
        "# 데이터 보호 불변식 스캔",
        "",
        f"검사 범위: {scope}. 검사 파일 {scanned}개. "
        "**이 결과는 후보 목록이지 판정이 아니다** — "
        "정규식은 문맥을 읽지 못하므로 오탐이 섞인다. 각 항목을 열어 사람이 확인한다.",
        "",
    ]
    if result.suppressions:
        # **전건을 위치·사유와 함께 싣는다.** 앞선 판은 개수만 남겼고, 그래서 R-1 이
        # 리포트 **안에서** 보이지 않았다 — 억제 실패가 구조적으로 조용했던 이유가 이것이다.
        lines.append(
            f"호출 지점 표기로 누른 적중 {len(result.suppressions)}건 "
            f"(상한 {MARKER_BUDGET}). **무엇을 눌렀는지 전부 적는다** — "
            "개수만 남기면 아무도 되짚지 않는다:"
        )
        lines.append("")
        for item in result.suppressions:
            shown = (
                item.path.relative_to(REPO_ROOT)
                if item.path.is_relative_to(REPO_ROOT)
                else item.path
            )
            lines.append(
                f"- `{shown}:{item.line}` — `{item.rule_id}` @{item.digest} "
                f"(호출 {item.call_ref}) — {item.reason}"
            )
        lines.append("")
    if result.suppressed:
        lines.append("2차 판정으로 제외한 적중(규칙이 눈감은 양을 드러내기 위해 함께 적는다):")
        lines.append("")
        for rule_id, reasons in sorted(result.suppressed.items()):
            detail = ", ".join(f"{reason} {count}건" for reason, count in sorted(reasons.items()))
            lines.append(f"- `{rule_id}` — {detail}")
        lines.append("")
    blocking = 0
    if result.marker_problems:
        # 표기가 새 면제 목록이 되지 않게 거는 방어들이 여기서 터진다(§4-octies.3).
        blocking += len(result.marker_problems)
        lines.extend(
            [
                f"## [BLOCK] MARKER — 억제 표기 자체가 잘못됐다 ({len(result.marker_problems)}건)",
                "",
                "- 왜: 표기는 억제의 **유일한** 통로다. 고아·사유 누락·상한 초과를 허용하면 "
                "표기가 새 면제 목록이 된다.",
                "",
            ]
        )
        lines.extend(f"- {problem}" for problem in result.marker_problems)
        lines.append("")
    if result.unscanned:
        # **BLOCK 이다.** 상한에 걸려 못 읽은 호출은 "위반 없음"이 아니라 "확인하지 않음"이고,
        # 이 스크립트는 같은 판단을 스캔 루트 부재와 `--changed` 0건에서 이미 내린다.
        blocking += len(result.unscanned)
        lines.extend(
            [
                "## [BLOCK] SCAN-INCOMPLETE — 검사하지 못한 호출이 있다 "
                f"({len(result.unscanned)}건)",
                "",
                f"- 왜: 논리 줄 결합이 {MAX_LOGICAL_LINE_SPAN}줄 상한에 걸려 끊겼다. 그 구간의 "
                "인자 목록은 **아무 규칙도 보지 못했다** — 조용히 넘기면 미검사가 통과로 집계된다.",
                "- 조치: 해당 호출을 줄이거나, 정말 그만큼 길어야 한다면 상한을 올리는 커밋으로 "
                "처리한다(그 diff 가 리뷰에 올라가는 것이 요점이다).",
                "",
            ]
        )
        for path, number in result.unscanned[:40]:
            shown = path.relative_to(REPO_ROOT) if path.is_relative_to(REPO_ROOT) else path
            lines.append(f"- `{shown}:{number}` — 논리 줄이 상한에서 끊겼다")
        if len(result.unscanned) > 40:
            lines.append(f"- … 외 {len(result.unscanned) - 40}건")
        lines.append("")
    for rule in RULES:
        found = hits.get(rule.id, [])
        if not found:
            continue
        if rule.severity == "BLOCK":
            blocking += len(found)
        lines.extend(
            [
                f"## [{rule.severity}] {rule.id} — {rule.invariant} ({len(found)}건)",
                "",
                f"- 왜: {rule.why}",
                f"- 오탐 가능: {rule.false_positive}",
                "",
            ]
        )
        for hit in found[:40]:
            shown = (
                hit.path.relative_to(REPO_ROOT) if hit.path.is_relative_to(REPO_ROOT) else hit.path
            )
            mark = hit.digest or "지문없음"
            lines.append(f"- `{shown}:{hit.line}` @{mark} — `{hit.text}`")
        if len(found) > 40:
            lines.append(f"- … 외 {len(found) - 40}건 (전체는 --rule {rule.id} 로 확인)")
        lines.append("")
    if not hits and not result.unscanned and not result.marker_problems:
        lines.append(
            "후보 없음. 다만 정규식이 못 보는 경로가 있으니 수동 감사 절차를 건너뛰지 않는다."
        )
    return "\n".join(lines), blocking


#: CI 를 알아보는 환경 변수. GitHub Actions·GitLab·CircleCI 가 공통으로 `CI` 를 준다.
CI_ENV_VARS = ("CI", "GITHUB_ACTIONS", "GITLAB_CI", "BUILDKITE", "JENKINS_URL")


def running_in_ci() -> bool:
    """CI 안인가. **모르면 CI 로 본다**가 아니라, 하나라도 있으면 CI 로 본다.

    이 판정이 틀리는 두 방향의 값이 다르다 — 로컬을 CI 로 잘못 보면 도구를 못 쓰는
    불편이고, CI 를 로컬로 잘못 보면 **게이트가 자기를 통과시킨다.** 그래서 넓게 잡는다.
    """
    return any(os.environ.get(name) for name in CI_ENV_VARS)


def update_markers(files: list[Path], rule_filter: set[str]) -> int:
    """표기의 지문을 현재 코드에 맞춰 갱신한다. **사유는 건드리지 않는다.**

    ## 셋을 건다 (§4-novies.5)

    1. **CI 에서 실행 금지** — 호출부([main])가 막는다.
    2. **사유 불변** — 지문만 바꾼다. 리뷰어가 "이 사유가 새 내용에도 맞나"를 판단할
       재료가 diff 에 남아야 한다.
    3. **전후 출력** — 지문 변경이 diff 에 한 줄로 뜬다. 인자를 추가하면 표기 줄이 함께
       바뀌므로 리뷰어가 억제 재승인을 **본다**. 표기 설계가 상쇄에 실패했던 신호가
       여기서 생긴다.

    갱신 대상은 **표기 자리에 적중이 정확히 하나** 있을 때뿐이다. 둘 이상이면 무엇을
    승인한 것인지 도구가 정할 수 없으므로 건드리지 않고 알린다 — 사람이 호출마다 표기를
    나눠 달아야 한다(불변량 4).
    """
    markable = frozenset(rule.id for rule in RULES if rule.markable)
    changed = 0
    for path in files:
        try:
            original = path.read_text(encoding="utf-8")
        except OSError:
            continue
        lines = original.splitlines()
        file_hits = scan([path], rule_filter).hits
        # 표기 자리 → 그 자리에서 그 규칙의 적중들(지문 무관). 지문이 **바뀌었기 때문에**
        # 갱신하는 것이므로 여기서는 지문을 맞추지 않는다.
        by_site: dict[tuple[int, str], list[Hit]] = {}
        for rule_id, entries in file_hits.items():
            if rule_id not in markable:
                continue
            for hit in entries:
                if hit.digest:
                    by_site.setdefault((hit.line, rule_id), []).append(hit)

        updated = list(lines)
        for marker in scan_markers(lines, python_syntax=path.suffix == ".py"):
            target = marker.line + 1 if marker.standalone else marker.line
            found = by_site.get((target, marker.rule_id), [])
            if len(found) != 1:
                if found:
                    print(
                        f"{path}:{marker.line} — 적중 {len(found)}건이라 갱신하지 않는다. "
                        "호출마다 표기를 따로 단다"
                    )
                continue
            fresh = found[0].digest
            if marker.digest == fresh:
                continue
            index = marker.line - 1
            before = updated[index]
            after = _rewrite_digest(before, marker.rule_id, fresh)
            if after == before:
                continue
            updated[index] = after
            changed += 1
            print(f"{path}:{marker.line}\n  전: {before.strip()}\n  후: {after.strip()}")
        if updated != lines:
            path.write_text("\n".join(updated) + "\n", encoding="utf-8")

    print(
        f"\n표기 {changed}건의 지문을 갱신했다. **사유는 그대로다** — "
        "diff 를 열어 사유가 새 내용에도 맞는지 확인하라."
    )
    return 0


def _rewrite_digest(line: str, rule_id: str, digest: str) -> str:
    """표기 한 줄의 지문만 바꾼다. 없으면 규칙 id 뒤에 끼운다."""
    pattern = re.compile(
        rf"({re.escape(MARKER_PREFIX)}\s*{re.escape(rule_id)})(\s*@[0-9a-f]{{{DIGEST_LENGTH}}})?"
    )
    return pattern.sub(lambda m: f"{m.group(1)} @{digest}", line, count=1)


def main(argv: list[str] | None = None) -> int:
    """`argv` 를 인자로 받는 이유: 종료 코드 분기를 테스트가 직접 돌릴 수 있어야 한다.

    범위 무결성 가드(루트 부재 2 / 0건 3)는 **정상 저장소에서는 한 번도 실행되지 않는
    분기**라, 프로세스를 띄우지 않고 부를 수 있어야 회귀로 고정된다.
    """
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--changed", action="store_true", help="git 변경분만 검사 (기본은 전수)")
    parser.add_argument(
        "--base",
        help=f"--changed의 비교 기준 ref (기본 {DEFAULT_BASE_REF}). "
        "이 ref와 HEAD의 merge-base 이후 커밋된 변경까지 포함한다.",
    )
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="--changed 결과가 0건이어도 실패시키지 않는다 (기본은 종료 코드 3)",
    )
    parser.add_argument("--rule", action="append", default=[], help="이 규칙만 (반복 가능)")
    parser.add_argument("--report-md", type=Path, help="마크다운 리포트 저장 경로")
    parser.add_argument("--no-fail", action="store_true", help="BLOCK 후보가 있어도 0으로 종료")
    parser.add_argument("--list-rules", action="store_true", help="규칙 목록 출력")
    parser.add_argument(
        "--update-markers",
        action="store_true",
        help="표기의 지문을 현재 코드에 맞춰 갱신한다 (로컬 전용 — CI 에서는 거부한다)",
    )
    args = parser.parse_args(argv)

    if args.base and not args.changed:
        parser.error("--base는 --changed와 함께 씁니다 (전수 검사에는 기준 ref가 없습니다)")

    if args.list_rules:
        for rule in RULES:
            print(f"{rule.severity:5} {rule.id:18} {rule.invariant}")
        return 0

    unknown = [name for name in args.rule if name not in {rule.id for rule in RULES}]
    if unknown:
        parser.error(f"알 수 없는 규칙: {', '.join(unknown)}")

    if args.update_markers and running_in_ci():
        print(
            "--update-markers 는 CI 에서 실행할 수 없다. "
            "CI 가 지문을 고칠 수 있으면 게이트가 자기를 통과시킨다 — "
            "지문 불일치는 사람이 사유를 다시 보라는 신호이지 자동으로 지울 것이 아니다.",
            file=sys.stderr,
        )
        return 2

    try:
        files, scope = iter_files(args.changed, args.base)
    except ScopeError as exc:
        print(f"검사 범위 오류: {exc}", file=sys.stderr)
        return 2
    if not files:
        print(f"검사 대상 파일이 없습니다 (범위: {scope}).")
        # **전수 모드에도 같은 판단을 적용한다.** 이전 판은 `--changed` 에만 이 분기를
        # 두어 자기 원칙을 절반만 적용했다 — "0건은 '위반 없음'이 아니라 '확인하지 않음'"은
        # 범위와 무관하게 참이다(판정 §4-quater.3).
        if not args.allow_empty:
            hint = (
                "범위가 맞는지 --base로 확인하거나"
                if args.changed
                else "SCAN_ROOTS 선언이 맞는지 확인하거나"
            )
            print(
                "\n검사한 파일이 0개입니다 — 이 결과는 '위반 없음'이 아니라 "
                "'확인하지 않음'입니다.\n"
                f"{hint}, 정말 빈 것이 맞으면 --allow-empty를 주십시오.",
                file=sys.stderr,
            )
            return 3
        return 0
    if args.update_markers:
        return update_markers(files, set(args.rule))
    result = scan(files, set(args.rule))
    report, blocking = render(result, len(files), scope)
    print(report)
    if args.report_md:
        args.report_md.parent.mkdir(parents=True, exist_ok=True)
        args.report_md.write_text(report + "\n", encoding="utf-8")
        print(f"\n[리포트] {args.report_md}")
    if blocking and not args.no_fail:
        print(
            f"\nBLOCK 후보 {blocking}건 — 사람이 확인해 오탐/실제 위반을 판정할 때까지 "
            "게이트를 통과시키지 않는다."
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
