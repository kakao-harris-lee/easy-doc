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
import math
import re
import subprocess
import sys
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
#: `LOG-BODY` 2차 판정이 **안전하다고 보는 멤버 이름**. 본문 이름 뒤에 이 멤버 접근만
#: 오면 후보에서 뺀다 (`draft.stats.masked_total` 처럼).
#:
#: **`value`·`text`·`body`·`content`·`original`·`raw` 는 절대 넣지 않는다.** 그것들이
#: 들어가는 순간 이 훅이 규칙 자체를 무력화한다. 이 금지는 아래 자기검사가 강제한다.
#:
#: 근거는 계획 §4.4 와 `CLAUDE.md` 보안 규칙의 **허용목록**이다 — 로그에 남겨도 되는 것은
#: 문서 ID·길이·처리 상태·시도 횟수·실패 코드까지다. 이름을 더할 때마다 근거를 적는다.
_SAFE_MEMBERS = (
    # 식별자 — 계획 §4.4 가 로그에 명시 허용
    r"id|document_id|documentId|conversion_id|conversionId|"
    # 개수·길이 (snake_case)
    r"length|size|count|\w+_count|\w+_counts|\w+_total|\w+_chars|"
    # 개수·길이 (camelCase) — 위 snake 목록의 **대칭**이다. 이 저장소는 Python 과 Kotlin 을
    # 함께 스캔하는데 snake 표기만 적어 두면 Kotlin 쪽은 같은 성질의 값마다 예외를 하나씩
    # 더하게 된다. 표기 규약이 둘이면 목록도 둘이어야 도달이 같아진다.
    r"\w+Count|\w+Counts|\w+Total|\w+Chars|\w+Id|"
    # 상태·분류값(열거형)
    r"status|state|stage|kind|category|\w+_category|\w+Category|"
    # §4.4 가 명시 허용한 넷 중 나머지
    r"attempt|attempts|failure_code|failureCode|"
    # 아래 셋은 실물 오탐에서 왔다. 넓은 패턴으로 일반화하지 않고 **이름 그대로** 적는다 —
    # `\w+Version` 같은 일반화는 무엇이 더 들어올지 모르는 채로 문을 여는 것이다.
    #   suggested_facts     — 추출된 팩트 후보 **개수** (scripts/collect_golden.py)
    #   migrationsExecuted  — 적용된 마이그레이션 **개수** (FlywayBaselineGuard.kt)
    #   targetSchemaVersion — 스키마 **버전 식별자**, 본문이 실릴 자리가 아니다 (같은 파일)
    r"suggested_facts|migrationsExecuted|targetSchemaVersion"
)

#: 안전 멤버 **앞에 올 수 있는 한정자**. 그 자체로는 안전하지 않고, 뒤에 안전 멤버가
#: 이어질 때만 통과시킨다 — `draft.stats` 단독 보간이나 `draft.document.text` 는 후보로 남는다.
#:
#: `document` 는 판정문 표에 없었으나 실물(`draft.document.id`)이 2단 접근이라 더했다.
#: 한정자로만 두었으므로 `document.value`·`document.text` 는 여전히 잡힌다.
_SAFE_QUALIFIERS = ("stats", "document")

_SAFE_MEMBER_RE = re.compile(rf"(?:{_SAFE_MEMBERS})\Z")

#: 금지 멤버 자기검사 — 목록이 넓어져 규칙을 무력화하는 것을 **모듈 적재 시점에** 막는다.
#: 주석으로만 두면 다음 사람이 `value` 를 한 줄 더한다.
for _forbidden in ("value", "text", "body", "content", "original", "raw"):
    if _SAFE_MEMBER_RE.match(_forbidden) or _forbidden in _SAFE_QUALIFIERS:
        raise AssertionError(
            f"안전 멤버/한정자 목록에 {_forbidden!r} 가 들어갔다 — "
            "LOG-BODY 훅이 규칙 자체를 무력화한다."
        )

_IDENTIFIER_RE = re.compile(r"[A-Za-z_]\w*")


def safe_access_chain(text: str, position: int) -> bool:
    """`text[position:]`에서 시작하는 **접근 사슬 전체**가 안전한지 판정한다.

    ## 정규식 종단 고정을 버린 이유 (게이트 09 M-01)

    앞선 판은 `\\.(?:한정자\\.)*(?:안전멤버)\\b(?!\\.)` 라는 정규식 하나였다. `(?!\\.)` 는
    **점이 곧바로 이어지는 경우만** 막는다. 실측된 탈출 셋:

        draft.id[0]                 첨자 — `[` 는 점이 아니라 통과
        draft.stats.count().value   호출 — `(` 는 점이 아니라 통과
        draft.id . value            공백 낀 점 — `(?!\\.)` 는 공백을 보고 만족

    셋째가 특히 나쁘다. **논리 줄 결합(`" ".join`)이 ktlint 강제 다중 줄 체인을 정확히 그
    ` .` 모양으로 만든다** — 즉 이 저장소의 표준 표기가 안전 판정을 받고 있었다.

    §4-sexies.3 의 원 요구는 *"접근 사슬 전체가 한정자 + 종단 안전 멤버로만 이뤄질 것"*
    이었다. 정규식 종단은 그 요구의 **근사**였고, 근사가 새는 자리가 셋이었다. 그래서
    종결자를 열거하는 대신 **사슬을 끝까지 읽는다** — 열거는 다음 표기에서 또 빠진다.

    ## 판정

    - 사슬이 비어 있으면(맨 이름) 안전하지 않다.
    - 마지막 마디를 뺀 전부가 한정자여야 하고, 마지막 마디가 안전 멤버여야 한다.
    - 어느 마디 뒤에든 `(`·`[` 가 오면 안전하지 않다 — 호출·첨자의 결과는 우리가 아는 값이
      아니다.
    """
    segments: list[str] = []
    index = position
    length = len(text)
    while True:
        while index < length and text[index] in " \t":
            index += 1
        if index >= length or text[index] != ".":
            break
        index += 1
        while index < length and text[index] in " \t":
            index += 1
        identifier = _IDENTIFIER_RE.match(text, index)
        if identifier is None:
            return False  # `..` 이나 `.(` 처럼 우리가 읽을 수 없는 모양 — 안전하다고 하지 않는다
        segments.append(identifier.group())
        index = identifier.end()
        probe = index
        while probe < length and text[probe] in " \t":
            probe += 1
        if probe < length and text[probe] in "([":
            return False  # 호출·첨자 — 결과값의 성질을 알 수 없다

    if not segments:
        return False
    return all(part in _SAFE_QUALIFIERS for part in segments[:-1]) and bool(
        _SAFE_MEMBER_RE.match(segments[-1])
    )


def balanced_arguments(text: str, open_paren: int) -> str:
    """`text[open_paren]` 의 `(` 에 대응하는 `)` 까지의 **인자 구간**을 돌려준다.

    ## 왜 `[^)]*` 를 버렸나 (게이트 09 M-09)

    `LOG-BODY` 패턴이 `{LOG_CALL}\\s*\\([^)]*\\b(?:본문이름)\\b` 였다. `[^)]*` 는 **첫 `)`
    에서 끊긴다** — 메시지 문자열 안의 괄호 하나만 있어도 그 뒤 인자를 못 본다:

        logger.info("완료(1단계)", draft.value)     ← `draft.value` 가 구간 밖

    괄호 균형을 세면 문자열 안의 괄호를 세지 않아야 하므로, 인자 구간 추출도 따옴표 상태를
    함께 읽는다. 닫는 괄호를 못 찾으면 **남은 전부**를 돌려준다 — 게이트가 틀릴 때는
    과검사 쪽으로 틀린다.
    """
    depth = 0
    quote: str | None = None
    index = open_paren
    length = len(text)
    while index < length:
        char = text[index]
        if quote is not None:
            if char == "\\":
                index += 2
                continue
            if text.startswith(quote, index):
                index += len(quote)
                quote = None
                continue
            index += 1
            continue
        for opener in ('"""', "'''", '"', "'"):
            if text.startswith(opener, index):
                quote = opener
                index += len(opener)
                break
        else:
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    return text[open_paren + 1 : index]
            index += 1
    return text[open_paren + 1 :]


def log_body_is_real_candidate(match: re.Match[str]) -> bool:
    """`LOG-BODY` 적중이 **진짜 후보**인지 2차 판정한다 (privacy-gate 판정 §4-quater.1).

    패턴은 "로그 호출이 있고 그 뒤 어딘가에 본문 이름이 있다"까지만 본다(값싼 예비 판정).
    실제 판정은 여기서 한다 — **균형 괄호로 인자 구간을 잘라내고**(M-09), 그 안의 본문
    이름마다 **접근 사슬 전체**를 읽는다(M-01).

    거르는 것은 `draft.stats.masked_total` 처럼 사슬이 한정자 + 집계 멤버로만 끝나는 줄이다.
    맨 이름·`draft.value`·첨자·호출·공백 낀 점 뒤의 위험 멤버는 전부 후보로 남는다.

    **인자 구간 안의 본문 이름을 전부 본다.** 하나라도 안전하지 않으면 후보다 —
    `logger.info("{} {}", draft.value, draft.stats.count)` 에서 안전한 쪽만 보고 넘기면
    진짜 유출을 놓친다.
    """
    line = match.string
    open_paren = line.find("(", match.start())
    if open_paren < 0:
        return True  # 패턴이 `(` 를 요구하므로 여기 오지 않는다. 와도 후보로 남긴다.
    arguments = balanced_arguments(line, open_paren)
    base = open_paren + 1
    found = False
    for occurrence in re.finditer(rf"\b(?:{BODY_NAMES})\b", arguments):
        found = True
        if not safe_access_chain(line, base + occurrence.end()):
            return True
    # 인자 구간에 본문 이름이 없으면 이 적중은 애초에 후보가 아니다(예비 판정의 잔여).
    return not found


LOG_CALL = (
    r"(?:_?logger?\.(?:debug|info|warning|warn|error|exception|trace)"
    r"|print|println|System\.out\.print)"
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


RULES: tuple[Rule, ...] = (
    Rule(
        "LOG-BODY",
        "BLOCK",
        "로그에 문서 본문·개인정보가 없다",
        # 예비 판정만 한다 — "로그 호출이 있고 그 뒤 어딘가에 본문 이름이 있다".
        # `[^)]*` 를 쓰지 않는 이유는 `balanced_arguments` KDoc(M-09). 실제 판정은 refine 이다.
        re.compile(rf"{LOG_CALL}\s*\((?=[^\n]*\b(?:{BODY_NAMES})\b)"),
        "로그는 평문으로 수집·장기 보관된다. 한 줄만 새도 암호화 저장 정책 전체가 무의미해진다.",
        "변수명이 우연히 겹치거나(`title` 로그가 문서 ID인 경우) 길이·타입만 찍는 줄이면 오탐.",
        refine=log_body_is_real_candidate,
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
        re.compile(r"\b(?:403|FORBIDDEN|Forbidden)\b"),
        "403은 '있지만 네 것이 아니다'를 알린다 — 자원 존재 자체가 유출이다.",
        "CORS·인증 미들웨어·프런트 문구·테스트의 403 기대값이면 오탐. 소유권 분기인지만 확인.",
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
        frozenset({".kt", ".kts", ".java", ".py"}),
        (),
        None,
        # DTD·외부 엔터티를 끄는 호출들. Python expat 핸들러와 JAXP/StAX 기능 플래그를 함께 본다.
        re.compile(
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
    ),
    Rule(
        "CACHE-HEADER",
        "WARN",
        "개인정보 응답에 no-store·nosniff를 붙인다",
        re.compile(r"Cache-Control\s*[\"'=:,)]|no-store|nosniff|X-Content-Type-Options"),
        "누락 탐지가 아니라 **분포 확인**용이다. "
        "개인정보 응답 수 대비 헤더 지정 지점이 적으면 빠진 곳이 있다.",
        "여기 걸린 줄은 대부분 정상 — 걸리지 *않은* 개인정보 엔드포인트를 찾는 것이 목적이다.",
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
    hits: dict[str, list[tuple[Path, int, str]]]
    #: 규칙별로 2차 판정에서 뺀 건수. 조용히 지우면 규칙이 언제부터 아무것도 안 보는지
    #: 알 수 없으므로 리포트에 함께 찍는다.
    suppressed: dict[str, dict[str, int]]
    #: **검사하지 못한** 논리 줄 (상한에 걸려 끊긴 호출). 빈 목록이 아니면 BLOCK 이다 —
    #: "검사했는데 없음"과 "검사하지 못함"은 다르다(게이트 09 M-03).
    unscanned: list[tuple[Path, int]] = field(default_factory=list)


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
    in_block_comment: bool = False

    @property
    def open(self) -> bool:
        """논리 줄이 아직 닫히지 않았는가."""
        return self.depth > 0 or self.quote is not None or self.in_block_comment


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


def _advance(line: str, state: LexState, python_syntax: bool) -> tuple[LexState, str]:
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
    depth, quote, block = state.depth, state.quote, state.in_block_comment
    kept: list[str] = []
    index = 0
    length = len(line)
    while index < length:
        rest = line[index:]
        if block:
            if rest.startswith("*/"):
                block = False
                index += 2
                kept.append(" ")  # 토큰이 붙지 않게 자리만 남긴다
            else:
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
            break  # 줄 주석 — 나머지는 코드가 아니다
        if not python_syntax and rest.startswith("/*"):
            block = True
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
    return LexState(depth=depth, quote=quote, in_block_comment=block), "".join(kept)


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
            state, code = _advance(lines[index], state, python_syntax)
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


#: `multiline` 규칙은 반드시 `opener` 를 갖는다 — 없으면 그 규칙만 fail-closed 밖으로 빠진다.
for _rule in RULES:
    if _rule.multiline and _rule.opener is None:
        raise AssertionError(
            f"multiline 규칙 {_rule.id} 에 opener 가 없다 — 끊긴 논리 줄에서 이 규칙이 "
            "볼 호출이 있었는지 판정할 수 없어 fail-closed 가 그 규칙만 놓친다."
        )

#: 끊긴 논리 줄이 **실제로 무언가를 가렸는가**를 판정할 때 쓰는 호출 시작 모양 전부.
ARG_LIST_OPENERS = tuple(rule.opener for rule in RULES if rule.multiline and rule.opener)


def scan(files: list[Path], rule_filter: set[str]) -> ScanResult:
    hits: dict[str, list[tuple[Path, int, str]]] = {}
    suppressed: dict[str, dict[str, int]] = {}
    unscanned: list[tuple[Path, int]] = []

    def drop(rule_id: str, reason: str) -> None:
        suppressed.setdefault(rule_id, {}).setdefault(reason, 0)
        suppressed[rule_id][reason] += 1

    rules = [rule for rule in RULES if not rule_filter or rule.id in rule_filter]
    for path in files:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        posix = path.as_posix()
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
                match = rule.pattern.search(line)
                if match is None:
                    continue
                if rule.refine is not None and not rule.refine(match):
                    drop(rule.id, "값의 모양이 불변식 대상이 아님")
                    continue
                if rule.hardened is not None:
                    index = number - 1
                    window = lines[
                        max(0, index - rule.hardened_before) : index + rule.hardened_after + 1
                    ]
                    if any(rule.hardened.search(near) for near in window):
                        drop(rule.id, "같은 창에서 완화 조치 확인")
                        continue
                hits.setdefault(rule.id, []).append((path, number, stripped[:160]))

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
                match = rule.pattern.search(line)
                if match is None:
                    continue
                if rule.refine is not None and not rule.refine(match):
                    drop(rule.id, "값의 모양이 불변식 대상이 아님")
                    continue
                # `hardened` 창은 **물리 줄 기준**을 유지한다. 지금 multiline 규칙 중
                # 창을 쓰는 것은 없지만(`XML-DTD`만 쓴다), 나중에 켤 때 기준이 조용히
                # 갈리지 않도록 여기서 물리 줄 인덱스를 쓴다는 것을 못박는다.
                if rule.hardened is not None:
                    index = number - 1
                    window = lines[
                        max(0, index - rule.hardened_before) : index + rule.hardened_after + 1
                    ]
                    if any(rule.hardened.search(near) for near in window):
                        drop(rule.id, "같은 창에서 완화 조치 확인")
                        continue
                hits.setdefault(rule.id, []).append((path, number, line[:160]))
    return ScanResult(hits, suppressed, unscanned)


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
    if result.suppressed:
        lines.append("2차 판정으로 제외한 적중(규칙이 눈감은 양을 드러내기 위해 함께 적는다):")
        lines.append("")
        for rule_id, reasons in sorted(result.suppressed.items()):
            detail = ", ".join(f"{reason} {count}건" for reason, count in sorted(reasons.items()))
            lines.append(f"- `{rule_id}` — {detail}")
        lines.append("")
    blocking = 0
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
        for path, number, text in found[:40]:
            shown = path.relative_to(REPO_ROOT) if path.is_relative_to(REPO_ROOT) else path
            lines.append(f"- `{shown}:{number}` — `{text}`")
        if len(found) > 40:
            lines.append(f"- … 외 {len(found) - 40}건 (전체는 --rule {rule.id} 로 확인)")
        lines.append("")
    if not hits and not result.unscanned:
        lines.append(
            "후보 없음. 다만 정규식이 못 보는 경로가 있으니 수동 감사 절차를 건너뛰지 않는다."
        )
    return "\n".join(lines), blocking


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
