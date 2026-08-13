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

종료 코드: 0 = BLOCK 후보 없음, 1 = BLOCK 후보 있음(사람 확인 필요), 2 = 입력 오류,
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
from dataclasses import dataclass
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


RULES: tuple[Rule, ...] = (
    Rule(
        "LOG-BODY",
        "BLOCK",
        "로그에 문서 본문·개인정보가 없다",
        re.compile(rf"{LOG_CALL}\s*\([^)]*\b(?:{BODY_NAMES})\b"),
        "로그는 평문으로 수집·장기 보관된다. 한 줄만 새도 암호화 저장 정책 전체가 무의미해진다.",
        "변수명이 우연히 겹치거나(`title` 로그가 문서 ID인 경우) 길이·타입만 찍는 줄이면 오탐.",
    ),
    Rule(
        "LOG-FSTRING",
        "WARN",
        "로그에 문서 본문·개인정보가 없다",
        re.compile(rf"{LOG_CALL}\s*\(\s*f?[\"'][^\"']*\{{[^}}]*\b(?:{BODY_NAMES})\b"),
        "f-string·템플릿 보간은 지연 포매팅을 우회해 값이 곧장 문자열이 된다.",
        "포매팅 인자가 이미 마스킹·요약된 값이면 오탐.",
    ),
    Rule(
        "EXC-BODY",
        "WARN",
        "예외 메시지에 본문이 실리지 않는다",
        re.compile(rf"(?:raise|throw)\s+\w*(?:Error|Exception)\s*\([^)]*\b(?:{BODY_NAMES})\b"),
        "예외 메시지는 5xx 응답과 스택트레이스 로그 양쪽으로 흘러간다.",
        "메시지가 아니라 원인 예외를 넘기는 인자면 오탐.",
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
        if not root_path.exists():
            continue
        files.extend(
            path
            for path in root_path.rglob("*")
            if path.is_file() and path.suffix in SUFFIXES and not SKIP_PARTS & set(path.parts)
        )
    return sorted(files), FULL_SCOPE


@dataclass
class ScanResult:
    hits: dict[str, list[tuple[Path, int, str]]]
    #: 규칙별로 2차 판정에서 뺀 건수. 조용히 지우면 규칙이 언제부터 아무것도 안 보는지
    #: 알 수 없으므로 리포트에 함께 찍는다.
    suppressed: dict[str, dict[str, int]]


def scan(files: list[Path], rule_filter: set[str]) -> ScanResult:
    hits: dict[str, list[tuple[Path, int, str]]] = {}
    suppressed: dict[str, dict[str, int]] = {}

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
    return ScanResult(hits, suppressed)


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
    if not hits:
        lines.append(
            "후보 없음. 다만 정규식이 못 보는 경로가 있으니 수동 감사 절차를 건너뛰지 않는다."
        )
    return "\n".join(lines), blocking


def main() -> int:
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
    args = parser.parse_args()

    if args.base and not args.changed:
        parser.error("--base는 --changed와 함께 씁니다 (전수 검사에는 기준 ref가 없습니다)")

    if args.list_rules:
        for rule in RULES:
            print(f"{rule.severity:5} {rule.id:18} {rule.invariant}")
        return 0

    unknown = [name for name in args.rule if name not in {rule.id for rule in RULES}]
    if unknown:
        parser.error(f"알 수 없는 규칙: {', '.join(unknown)}")

    files, scope = iter_files(args.changed, args.base)
    if not files:
        print(f"검사 대상 파일이 없습니다 (범위: {scope}).")
        if args.changed and not args.allow_empty:
            print(
                "\n검사한 파일이 0개입니다 — 이 결과는 '위반 없음'이 아니라 "
                "'확인하지 않음'입니다.\n"
                "범위가 맞는지 --base로 확인하거나, 정말 빈 것이 맞으면 --allow-empty를 주십시오.",
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
