#!/usr/bin/env python3
"""마이그레이션 데이터 보호 불변식 기계 스캔 (Python + Kotlin 소스 동시).

이 스크립트는 **판정하지 않는다.** 위반 후보를 모아 사람 앞에 놓을 뿐이다.
정규식은 문맥을 읽지 못하므로 오탐이 반드시 섞인다 — 자동 차단에 쓰면 곧
"어차피 오탐"이라며 전체를 무시하게 되고, 그때 진짜 유출이 지나간다.

실행:
    uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py
    # 변경분만 (Phase 진행 중 빠른 회전)
    uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py --changed
    # 특정 규칙만 / 마크다운 리포트
    uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py \
        --rule LOG-BODY --report-md docs/migration/_workspace/07_privacy-gate_scan.md

종료 코드: 0 = BLOCK 후보 없음, 1 = BLOCK 후보 있음(사람 확인 필요), 2 = 입력 오류.
`--no-fail`을 주면 항상 0으로 끝난다(리포트 수집 용도).
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]

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
BODY_NAMES = (
    r"text|body|content|source_text|sourceText|easy_text|easyText|masked_text|maskedText|"
    r"original|plaintext|plain_text|raw|password|secret|token|email|payload|prompt|"
    r"converted|review|comment|title|filename"
)
LOG_CALL = (
    r"(?:_?logger?\.(?:debug|info|warning|warn|error|exception|trace)"
    r"|print|println|System\.out\.print)"
)


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
            r"(?:fernet[_-]?key|jwt[_-]?secret|api[_-]?key|secret[_-]?key|password)\s*[:=]\s*[\"'][^\"'\s]{12,}[\"']",
            re.IGNORECASE,
        ),
        "코드·커밋에 들어간 키는 히스토리에서 지워지지 않는다.",
        "테스트 fixture·예시 문자열이면 오탐이지만, 실제 키가 아님을 사람이 확인해야 한다.",
    ),
    Rule(
        "XML-DTD",
        "BLOCK",
        "문서 파서는 DTD·외부 엔터티를 거부한다",
        re.compile(
            r"DocumentBuilderFactory\.newInstance|XMLInputFactory\.newInstance|SAXParserFactory\.newInstance"
            r"|xml\.etree|ElementTree\.(?:parse|fromstring)"
        ),
        "기본 설정 XML 파서는 XXE·billion laughs에 열려 있다. "
        "Python 쪽은 expat DTD 핸들러로 막고 있다.",
        "바로 다음 줄들에서 setFeature/setProperty로 DTD를 끄고 있으면 정상 — "
        "반드시 주변 줄을 확인.",
        frozenset({".kt", ".kts", ".java", ".py"}),
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


def iter_files(changed_only: bool) -> list[Path]:
    if changed_only:
        try:
            out = subprocess.run(
                ["git", "-C", str(REPO_ROOT), "diff", "--name-only", "HEAD"],
                capture_output=True,
                text=True,
                check=True,
            ).stdout
            untracked = subprocess.run(
                ["git", "-C", str(REPO_ROOT), "ls-files", "--others", "--exclude-standard"],
                capture_output=True,
                text=True,
                check=True,
            ).stdout
        except (OSError, subprocess.CalledProcessError) as exc:
            print(
                f"[경고] git 변경분 조회 실패({type(exc).__name__}) — 전수 검사로 전환",
                file=sys.stderr,
            )
            return iter_files(False)
        names = [line for line in (out + untracked).splitlines() if line.strip()]
        # 전수 검사와 같은 범위로 좁힌다 — 그러지 않으면 이 스크립트 자신(규칙 문자열)까지
        # 후보로 잡혀 리포트가 오탐으로 시작한다.
        return [
            path
            for name in names
            if (path := REPO_ROOT / name).is_file()
            and path.suffix in SUFFIXES
            and any(path.is_relative_to(REPO_ROOT / root) for root in SCAN_ROOTS)
            and not SKIP_PARTS & set(path.parts)
        ]

    files: list[Path] = []
    for root in SCAN_ROOTS:
        base = REPO_ROOT / root
        if not base.exists():
            continue
        files.extend(
            path
            for path in base.rglob("*")
            if path.is_file() and path.suffix in SUFFIXES and not SKIP_PARTS & set(path.parts)
        )
    return sorted(files)


def scan(files: list[Path], rule_filter: set[str]) -> dict[str, list[tuple[Path, int, str]]]:
    hits: dict[str, list[tuple[Path, int, str]]] = {}
    rules = [rule for rule in RULES if not rule_filter or rule.id in rule_filter]
    for path in files:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for number, line in enumerate(lines, start=1):
            stripped = line.strip()
            if stripped.startswith(("#", "//", "*", '"""')):
                continue  # 주석·docstring은 후보에서 뺀다(설명문이 대량 오탐을 만든다)
            for rule in rules:
                if rule.suffixes and path.suffix not in rule.suffixes:
                    continue
                posix = path.as_posix()
                if any(allowed in posix for allowed in rule.sanctioned):
                    continue
                if rule.pattern.search(line):
                    hits.setdefault(rule.id, []).append((path, number, stripped[:160]))
    return hits


def render(hits: dict[str, list[tuple[Path, int, str]]], scanned: int) -> tuple[str, int]:
    lines = [
        "# 데이터 보호 불변식 스캔",
        "",
        f"검사 파일 {scanned}개. **이 결과는 후보 목록이지 판정이 아니다** — "
        "정규식은 문맥을 읽지 못하므로 오탐이 섞인다. 각 항목을 열어 사람이 확인한다.",
        "",
    ]
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
    parser.add_argument("--rule", action="append", default=[], help="이 규칙만 (반복 가능)")
    parser.add_argument("--report-md", type=Path, help="마크다운 리포트 저장 경로")
    parser.add_argument("--no-fail", action="store_true", help="BLOCK 후보가 있어도 0으로 종료")
    parser.add_argument("--list-rules", action="store_true", help="규칙 목록 출력")
    args = parser.parse_args()

    if args.list_rules:
        for rule in RULES:
            print(f"{rule.severity:5} {rule.id:18} {rule.invariant}")
        return 0

    unknown = [name for name in args.rule if name not in {rule.id for rule in RULES}]
    if unknown:
        parser.error(f"알 수 없는 규칙: {', '.join(unknown)}")

    files = iter_files(args.changed)
    if not files:
        print("검사 대상 파일이 없습니다.")
        return 0
    hits = scan(files, set(args.rule))
    report, blocking = render(hits, len(files))
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
