#!/usr/bin/env python3
"""Python 기준 parity fixture 생성기.

`parity/fixtures/{도메인}/*.json`에 "이 입력에 Python은 이 값을 냈다"를 기록한다.
Kotlin 테스트가 **같은 파일**을 읽어 자기 출력과 비교해야 진짜 동등성 증명이 된다 —
Kotlin 쪽에서 기대값을 다시 손으로 적으면 두 구현이 아니라 두 벌의 사람 해석을 비교하게 된다.

실행:
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
        --domain masking --domain style
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --list
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
        verify-crypto --actual parity/actual/crypto/kotlin-encrypt.json
    uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py \
        verify-jwt --actual parity/actual/jwt/kotlin-issue.json

`verify-*` 서브커맨드는 **역방향**(Kotlin 산출물을 Python이 읽는 방향) 전용이다.
검증에 성공하든 실패하든 실행 증거 파일(proof)을 남긴다 — `compare_parity.py`는 그
파일이 있어야만 역방향 케이스를 닫는다. 증거가 없으면 통과가 아니라 **미검증**이다.

입력 문자열은 전부 합성(synthetic)이다. 실제 사용자 문서·실제 개인정보를 fixture에
넣지 않는다 — fixture는 저장소에 커밋되어 영구히 남는다.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import sys
import unicodedata
import uuid
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta, tzinfo
from pathlib import Path
from types import ModuleType
from typing import Any, cast
from unittest.mock import patch

REPO_ROOT = Path(__file__).resolve().parents[4]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

DEFAULT_OUT = REPO_ROOT / "parity" / "fixtures"

#: 기본 정규화. 두 런타임이 "같다"고 볼 표기 차이만 담는다.
#: 이 목록을 늘릴 때는 반드시 왜 그 차이가 무해한지 근거를 함께 남긴다.
BASE_NORMALIZATION = ["nfc", "lf"]

#: 자격증명 도메인(jwt·argon2)은 정규화를 쓰지 않는다. 기대값이 전부 ASCII(불리언·UUID·
#: 16진 해시·PHC 문자열)라 접을 표기 차이가 없고, 무엇보다 **비밀번호 바이트를 NFC로 접으면
#: argon2 해시가 달라진다**. 정규화는 줄이는 방향으로 관리한다(스킬 핵심 원칙 4).
NO_NORMALIZATION: list[str] = []

Case = dict[str, Any]
Builder = Callable[[], tuple[str, list[str], list[Case]]]


def _case(case_id: str, description: str, payload: Any, expected: Any, **extra: Any) -> Case:
    case: Case = {
        "id": case_id,
        "description": description,
        "input": payload,
        "expected": expected,
    }
    case.update(extra)
    return case


def _external(
    *, script: str, proof: str, required_cases: int, actual_schema: dict[str, str]
) -> dict[str, Any]:
    """역방향(Kotlin → Python) 케이스임을 표시한다.

    이런 케이스는 Kotlin 산출물을 값으로 비교해서는 닫을 수 없다 — Kotlin이 기대값을
    그대로 되받아 적으면 아무것도 실행하지 않고도 "일치"가 나온다. 그래서 비교기는 이
    표시가 붙은 케이스의 `actual`을 아예 보지 않고, `verify-*` 서브커맨드가 남긴 실행
    증거 파일(`proof`)만 근거로 인정한다. 증거가 없으면 **미검증**이다.
    """
    return {
        "mode": "external",
        "script": script,
        "proof": proof,
        "required_cases": required_cases,
        "actual_schema": actual_schema,
    }


# ------------------------------------------------------------------- 고정 시각 주입


class _FrozenClock:
    """`datetime.now()` 자리에 끼워 넣는 고정 시계.

    JWT의 `exp` 검증은 실행 시각에 좌우된다. 벽시계로 검증하면 fixture를 만든 날과 돌리는
    날이 다를 때 같은 토큰의 판정이 뒤집혀 parity 검증 자체가 무의미해진다. 그래서 fixture는
    케이스마다 기준 시각(`verify_at`, epoch 초)을 못박고 검증할 때 그 시각을 주입한다.
    """

    def __init__(self, moment: datetime) -> None:
        self._moment = moment

    def now(self, tz: tzinfo | None = None) -> datetime:
        return self._moment if tz is None else self._moment.astimezone(tz)


@contextmanager
def _frozen(module: ModuleType, moment: datetime) -> Iterator[None]:
    """`module.datetime.now()`가 `moment`를 반환하도록 잠시 바꾼다.

    발급은 `app.services.auth`의 시계를, 검증은 PyJWT(`jwt.api_jwt`)의 시계를 묶는다.
    두 곳을 동시에 묶지 않는다 — `jwt.encode`는 `datetime` 클래스로 `isinstance` 검사를
    하므로 발급 중에 PyJWT 쪽 시계를 바꾸면 인코딩이 깨진다.
    """
    with patch.object(module, "datetime", _FrozenClock(moment)):
        yield


#: `AuthService.resolve_token`·`_issue_token`은 저장소를 만지지 않는다. 이 자리에 실제
#: 저장소를 끼우면 fixture 생성이 DB에 묶이므로, 호출되면 그 자체가 결함인 자리표시자를 둔다.
_UNUSED_STORE = object()


# --------------------------------------------------------------------------- 마스킹


def build_masking() -> tuple[str, list[str], list[Case]]:
    """개인정보 마스킹 — 패턴 우선순위·자리표시자 번호·구간 겹침"""
    from app.privacy.masking import mask_text

    samples: list[tuple[str, str]] = [
        ("plain", "이 안내문에는 개인정보가 없습니다."),
        ("rrn-hyphen", "주민등록번호 900101-1234567 를 확인합니다."),
        ("rrn-no-sep", "번호는 9001011234567 입니다."),
        ("rrn-foreigner", "외국인등록번호 900101-5234567 확인."),
        ("email-with-digits", "문의는 user01234567@example.com 으로 주세요."),
        ("phone-mobile", "연락처 010-1234-5678 로 전화 주세요."),
        ("phone-seoul", "02-123-4567 로 문의하세요."),
        ("card", "카드번호 1234-5678-9012-3456 을 입력합니다."),
        ("account", "계좌 123-456-789012 로 입금하세요."),
        ("multi-same-category", "010-1234-5678 또는 010-8765-4321 로 연락 주세요."),
        ("multi-category", "kim@example.com / 010-1234-5678 / 900101-1234567"),
        ("adjacent", "010-1234-5678010-8765-4321"),
        ("empty", ""),
        ("newline", "이름: 홍길동\n전화: 010-1234-5678\n메일: a@b.co.kr"),
    ]
    cases = [
        _case(
            f"masking-{name}",
            "mask_text 우선순위·번호 매김·구간 겹침 처리",
            {"text": text},
            {
                "masked_text": (result := mask_text(text)).masked_text,
                "items": [
                    {
                        "category": item.category.value,
                        "placeholder": item.placeholder,
                        "original": item.original.get_secret_value(),
                    }
                    for item in result.items
                ],
            },
        )
        for name, text in samples
    ]
    return "app/privacy/masking.py::mask_text", BASE_NORMALIZATION, cases


# ----------------------------------------------------------------------- 텍스트 정규화


def build_text() -> tuple[str, list[str], list[Case]]:
    """제어문자 제거 — XML 1.0 비허용 문자만, 탭·개행·복귀 유지"""
    from app.text import strip_control_chars

    samples: list[tuple[str, str]] = [
        ("keeps-tab-lf-cr", "가\t나\n다\r라"),
        ("drops-nul", "가\x00나"),
        ("drops-vt-ff", "가\x0b나\x0c다"),
        ("drops-del", "가\x7f나"),
        ("drops-c1-range", "가\x1f나\x0e다\x08라"),
        ("empty", ""),
        ("only-control", "\x00\x01\x02"),
    ]
    cases = [
        _case(
            f"text-{name}",
            "strip_control_chars — XML 1.0 비허용 제어문자만 제거(탭·개행·복귀 유지)",
            {"text": text},
            {"text": strip_control_chars(text)},
        )
        for name, text in samples
    ]
    return "app/text.py::strip_control_chars", BASE_NORMALIZATION, cases


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


def build_style() -> tuple[str, list[str], list[Case]]:
    """스타일 검사 — 문장 분리, 어려운 표현, 뜻풀이 축자 삽입, 위반 목록"""
    from app.easyread.style_rules import (
        check_style,
        find_difficult_words,
        find_gloss_collisions,
        split_sentences,
    )

    cases = [
        _case(
            f"style-{name}",
            "split_sentences / find_difficult_words / find_gloss_collisions / check_style",
            {"text": text},
            {
                "sentences": split_sentences(text),
                "difficult_words": find_difficult_words(text),
                "gloss_collisions": find_gloss_collisions(text),
                "check_style": {
                    "total_sentences": (result := check_style(text)).total_sentences,
                    "issues": [issue.model_dump() for issue in result.issues],
                },
            },
        )
        for name, text in _STYLE_SAMPLES
    ]
    return "app/easyread/style_rules.py::check_style 외", BASE_NORMALIZATION, cases


def build_style_tables() -> tuple[str, list[str], list[Case]]:
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
    cases = [
        _case(
            "style-tables-counts",
            "표 크기 — 낱말 하나가 조용히 빠지면 여기서 먼저 걸린다",
            {},
            counts,
        ),
        _case(
            "style-tables-full",
            "표 전문 — 순서까지 포함해 동일해야 한다(사전 정의 순서가 프롬프트 출력 순서다)",
            {},
            tables,
        ),
    ]
    return "app/easyread/style_rules.py 상수", BASE_NORMALIZATION, cases


# ---------------------------------------------------------------------------- 프롬프트


def build_prompts() -> tuple[str, list[str], list[Case]]:
    """프롬프트 렌더링 — 시스템·사용자·보정 프롬프트 전문"""
    from app.easyread.prompts import build_repair_prompt, build_system_prompt, build_user_prompt
    from app.easyread.style_rules import check_style

    masked_samples: list[tuple[str, str]] = [
        ("no-difficult-word", "신청은 9월에 합니다."),
        ("one-difficult-word", "감면을 받으려면 신청서를 제출하세요."),
        ("with-placeholder", "문의는 [[전화번호1]] 로 하세요. 감면 대상을 확인합니다."),
        (
            "many-difficult-words",
            "수급자는 부양의무자 기준을 충족해야 하며 소급 적용이 가능합니다.",
        ),
    ]
    cases: list[Case] = []
    for name, masked in masked_samples:
        issues = check_style(masked).issues
        repair_system, repair_user = build_repair_prompt(masked, issues)
        cases.append(
            _case(
                f"prompts-{name}",
                "build_system_prompt / build_user_prompt / build_repair_prompt",
                {"masked_text": masked, "violations": [issue.model_dump() for issue in issues]},
                {
                    "system_prompt": build_system_prompt(masked),
                    "user_prompt": build_user_prompt(masked),
                    "repair_system_prompt": repair_system,
                    "repair_user_prompt": repair_user,
                },
            )
        )
    # 사용자·보정 프롬프트는 요청마다 난수 문서 id를 넣는다(prompt injection 방어).
    # 그 자리만 가리고 비교한다 — 난수 자체가 같을 수는 없다.
    return (
        "app/easyread/prompts.py::build_system_prompt / build_user_prompt / build_repair_prompt",
        [*BASE_NORMALIZATION, "mask_document_id"],
        cases,
    )


# --------------------------------------------------------------------------- 후처리


def build_postprocess() -> tuple[str, list[str], list[Case]]:
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
    cases = [
        _case(
            f"postprocess-{name}",
            "postprocess — 코드 펜스·머리말 제거 조건(과잉 제거 금지)",
            {"raw": raw},
            {"text": postprocess(raw)},
        )
        for name, raw in samples
    ]
    return "app/easyread/postprocess.py::postprocess", BASE_NORMALIZATION, cases


# ------------------------------------------------------------------------ 보정 채택


def build_repair_adoption() -> tuple[str, list[str], list[Case]]:
    """보정 채택 판정 — 자리표시자 유실·위반 건수 악화 가드"""
    from app.easyread.style_rules import check_style
    from app.services.conversion import _accepts_repair

    samples: list[tuple[str, str, str, list[str]]] = [
        ("improves", "결과가 보여지고 있습니다.", "결과를 보여 드립니다.", []),
        ("worsens", "감면을 받으세요.", "감면을 받으시고, 가, 나, 다, 라를 준비하세요.", []),
        ("equal-count", "감면을 받으세요.", "제출을 하세요.", []),
        (
            "loses-placeholder",
            "문의는 [[전화번호1]] 감면 대상입니다.",
            "문의해 주세요.",
            ["[[전화번호1]]"],
        ),
        ("placeholder-absent-in-original", "감면 대상입니다.", "깎아 드립니다.", ["[[전화번호1]]"]),
    ]
    cases = [
        _case(
            f"repair-{name}",
            "_accepts_repair — 자리표시자 유실·위반 건수 악화 시 1차 결과 채택",
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
        )
        for name, original, candidate, placeholders in samples
    ]
    return "app/services/conversion.py::_accepts_repair", BASE_NORMALIZATION, cases


# -------------------------------------------------------------------------- 내보내기


def build_export() -> tuple[str, list[str], list[Case]]:
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
        ("basic", "문의는 [[전화번호1]] 입니다.", {"[[전화번호1]]": "010-1234-5678"}),
        ("unknown-placeholder", "문의는 [[전화번호9]] 입니다.", {"[[전화번호1]]": "010-1234-5678"}),
        (
            "single-pass",
            "값은 [[이메일1]] 입니다.",
            {"[[이메일1]]": "[[전화번호1]]", "[[전화번호1]]": "010-1234-5678"},
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
    return (
        "app/easyread/export.py::export_filename / content_disposition / "
        "restore_placeholders / render_export(TXT)",
        BASE_NORMALIZATION,
        cases,
    )


# ------------------------------------------------------------------------------ 암호


def build_crypto() -> tuple[str, list[str], list[Case]]:
    """Fernet 교차 런타임 — 정방향·역방향·변조·다른 키"""
    from cryptography.fernet import Fernet

    from app.privacy.crypto import CURRENT_KEY_VERSION, TextCipher

    key = Fernet.generate_key().decode()
    other_key = Fernet.generate_key().decode()
    cipher = TextCipher(key)

    plaintexts: list[tuple[str, str]] = [
        ("ascii", "hello world"),
        ("hangul", "기초연금 신청 안내입니다."),
        ("empty", ""),
        ("long", "가나다라마바사" * 2000),
        ("emoji-and-control", "줄바꿈\n탭\t끝"),
    ]
    cases: list[Case] = [
        _case(
            f"crypto-decrypt-{name}",
            "Python이 만든 Fernet 토큰을 Kotlin이 복호화한다",
            {"key": key, "token": cipher.encrypt(text).decode("ascii")},
            {"outcome": "ok", "plaintext": text, "key_version": CURRENT_KEY_VERSION},
        )
        for name, text in plaintexts
    ]
    good = cipher.encrypt("변조 대상").decode("ascii")
    tampered = base64.urlsafe_b64encode(
        bytes([base64.urlsafe_b64decode(good)[0] ^ 0x01, *base64.urlsafe_b64decode(good)[1:]])
    ).decode("ascii")
    cases.append(
        _case(
            "crypto-tampered",
            "변조된 토큰은 반드시 복호화 실패여야 한다 — 조용히 통과하면 인증 암호화가 아니다",
            {"key": key, "token": tampered},
            {"outcome": "invalid_token"},
        )
    )
    cases.append(
        _case(
            "crypto-wrong-key",
            "다른 키로는 복호화되지 않는다",
            {"key": other_key, "token": good},
            {"outcome": "invalid_token"},
        )
    )
    cases.append(
        _case(
            "crypto-garbage",
            "Fernet 토큰이 아닌 바이트",
            {"key": key, "token": "not-a-fernet-token"},
            {"outcome": "invalid_token"},
        )
    )
    cases.append(
        _case(
            "crypto-roundtrip-request",
            "역방향 검증용 — Kotlin이 이 평문들을 이 키로 암호화하고 verify-crypto로 확인한다. "
            "Kotlin 결과 파일에 이 id를 적어도 닫히지 않는다 — 증거 파일만 인정한다",
            {"key": key, "plaintexts": [text for _, text in plaintexts]},
            {"outcome": "verified_externally"},
            verification=_external(
                script="dump_parity_fixtures.py verify-crypto",
                proof="verify-crypto.verified.json",
                required_cases=len(plaintexts),
                actual_schema={
                    "key": "이 케이스의 key를 그대로",
                    "token": "Kotlin이 만든 Fernet 토큰(ASCII)",
                    "expected_plaintext": "암호화 전 평문",
                },
            ),
        )
    )
    return "app/privacy/crypto.py::TextCipher", ["nfc"], cases


# ------------------------------------------------------------------------------- JWT

#: fixture 전용 합성 시크릿. 운영 키를 넣지 않는다. 길이는 MIN_JWT_SECRET_BYTES(32) 이상.
JWT_SECRET = "parity-jwt-secret-" + "0" * 32
#: 공격자 키 역할. 이 키로 서명한 토큰은 반드시 거부돼야 한다.
JWT_OTHER_SECRET = "parity-jwt-other-secret-" + "1" * 32
#: 하한 경계 — 정확히 32바이트라 받아들여져야 한다.
JWT_MIN_SECRET = "m" * 32
#: 하한에서 1바이트 모자란다 — ConfigurationError로 기동이 끊겨야 한다.
JWT_TOO_SHORT_SECRET = "s" * 31

JWT_SUBJECT = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
JWT_OTHER_SUBJECT = "00000000-0000-4000-8000-000000000000"
#: 발급 기준 시각. 벽시계를 쓰지 않는다 — 아래 `verify_at`은 전부 이 값에서 파생된다.
JWT_ISSUED_AT = datetime(2026, 1, 2, 3, 4, 5, tzinfo=UTC)
JWT_EXPIRE_MINUTES = 30


def build_jwt() -> tuple[str, list[str], list[Case]]:
    """JWT 교차 런타임 — 양방향·만료 경계·서명 위조·알고리즘 혼동·시크릿 하한"""
    import jwt as pyjwt
    from jwt import api_jwt

    from app.exceptions import ConfigurationError, InvalidCredentialsError
    from app.services import auth as auth_module
    from app.services.auth import (
        _ALGORITHM,
        _TOKEN_TYPE,
        MIN_JWT_SECRET_BYTES,
        AuthService,
        UserStore,
        WorkspaceCreator,
    )

    expires_at = JWT_ISSUED_AT + timedelta(minutes=JWT_EXPIRE_MINUTES)
    exp = int(expires_at.timestamp())
    issued_epoch = int(JWT_ISSUED_AT.timestamp())
    live_at = issued_epoch + 60

    def service(secret: str) -> AuthService:
        return AuthService(
            cast(UserStore, _UNUSED_STORE),
            cast(WorkspaceCreator, _UNUSED_STORE),
            secret,
            JWT_EXPIRE_MINUTES,
        )

    def issue(secret: str, subject: str) -> str:
        # 실제 발급 경로(`_issue_token`)를 고정 시계 아래에서 돌린다 — 클레임 구성을
        # 손으로 옮겨 적으면 두 구현이 아니라 두 벌의 사람 해석을 비교하게 된다.
        with _frozen(auth_module, JWT_ISSUED_AT):
            return service(secret)._issue_token(uuid.UUID(subject))

    def encode(claims: dict[str, Any], secret: str) -> str:
        return pyjwt.encode(claims, secret, algorithm=_ALGORITHM)

    def b64(raw: bytes) -> str:
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

    def raw_token(header: dict[str, str], claims: dict[str, Any], sign_with: str | None) -> str:
        """헤더를 직접 조립한 토큰 — PyJWT가 만들어 주지 않는 공격 형태를 만든다."""
        head = b64(json.dumps(header, separators=(",", ":"), sort_keys=True).encode("utf-8"))
        body = b64(json.dumps(claims, separators=(",", ":"), sort_keys=True).encode("utf-8"))
        if sign_with is None:
            return f"{head}.{body}."
        signature = hmac.new(
            sign_with.encode("utf-8"), f"{head}.{body}".encode("ascii"), hashlib.sha256
        ).digest()
        return f"{head}.{body}.{b64(signature)}"

    def outcome(secret: str, token: str, verify_at: int) -> dict[str, Any]:
        """`AuthService`를 그 기준 시각에 실제로 돌린 결과."""
        try:
            svc = service(secret)
        except ConfigurationError:
            return {"outcome": "configuration_error", "min_secret_bytes": MIN_JWT_SECRET_BYTES}
        with _frozen(api_jwt, datetime.fromtimestamp(verify_at, UTC)):
            try:
                subject = svc.resolve_token(token)
            except InvalidCredentialsError:
                return {"outcome": "invalid_credentials"}
            claims = pyjwt.decode(
                token,
                secret,
                algorithms=[_ALGORITHM],
                options={"require": ["sub", "exp", "typ"]},
            )
        return {
            "outcome": "ok",
            "subject": str(subject),
            "claims": {"sub": claims["sub"], "exp": claims["exp"], "typ": claims["typ"]},
        }

    valid = issue(JWT_SECRET, JWT_SUBJECT)
    head, _, signature = valid.split(".")
    base_claims: dict[str, Any] = {"sub": JWT_SUBJECT, "exp": exp, "typ": _TOKEN_TYPE}
    tampered_body = b64(
        json.dumps({**base_claims, "sub": JWT_OTHER_SUBJECT}, separators=(",", ":")).encode("utf-8")
    )

    #: (id, 설명, 시크릿, 토큰, 검증 기준 시각)
    samples: list[tuple[str, str, str, str, int]] = [
        (
            "valid",
            "Python이 발급한 토큰을 Kotlin이 읽는다 — sub·exp·typ 전부 대조",
            JWT_SECRET,
            valid,
            live_at,
        ),
        (
            "exp-boundary-one-second-before",
            "만료 1초 전은 유효하다 — 경계 판정이 갈리면 로그인이 조기에 끊긴다",
            JWT_SECRET,
            valid,
            exp - 1,
        ),
        (
            "exp-boundary-exact",
            "기준 시각이 exp와 같으면 만료다 (PyJWT: exp <= now). "
            "JVM 라이브러리는 exp < now로 보는 것이 많아 여기서 갈린다",
            JWT_SECRET,
            valid,
            exp,
        ),
        ("expired", "만료된 토큰은 거부된다", JWT_SECRET, valid, exp + 60),
        (
            "wrong-secret",
            "다른 키로는 검증되지 않는다",
            JWT_OTHER_SECRET,
            valid,
            live_at,
        ),
        (
            "forged-signature",
            "공격자 키로 서명한 토큰 — 조용히 통과하면 아무나 로그인한다",
            JWT_SECRET,
            issue(JWT_OTHER_SECRET, JWT_OTHER_SUBJECT),
            live_at,
        ),
        (
            "tampered-payload",
            "sub만 바꾸고 서명은 그대로 — 남의 계정으로 바뀌면 안 된다",
            JWT_SECRET,
            f"{head}.{tampered_body}.{signature}",
            live_at,
        ),
        (
            "alg-none",
            "alg=none·서명 없음 — 알고리즘 혼동의 고전. 반드시 거부",
            JWT_SECRET,
            raw_token({"alg": "none", "typ": "JWT"}, base_claims, None),
            live_at,
        ),
        (
            "alg-rs256-header",
            "헤더만 RS256으로 바꾸고 HMAC으로 서명 — 헤더의 alg를 믿으면 뚫린다",
            JWT_SECRET,
            raw_token({"alg": "RS256", "typ": "JWT"}, base_claims, JWT_SECRET),
            live_at,
        ),
        (
            "missing-exp",
            "exp 없는 토큰은 만료되지 않는 영구 자격증명이 된다 — require로 막는다",
            JWT_SECRET,
            encode({"sub": JWT_SUBJECT, "typ": _TOKEN_TYPE}, JWT_SECRET),
            live_at,
        ),
        (
            "missing-typ",
            "typ 없는 토큰 거부 (require)",
            JWT_SECRET,
            encode({"sub": JWT_SUBJECT, "exp": exp}, JWT_SECRET),
            live_at,
        ),
        (
            "missing-sub",
            "sub 없는 토큰 거부 (require)",
            JWT_SECRET,
            encode({"exp": exp, "typ": _TOKEN_TYPE}, JWT_SECRET),
            live_at,
        ),
        (
            "wrong-typ",
            "다른 용도로 발급한 토큰을 액세스 토큰으로 쓰려는 시도",
            JWT_SECRET,
            encode({"sub": JWT_SUBJECT, "exp": exp, "typ": "refresh"}, JWT_SECRET),
            live_at,
        ),
        (
            "non-uuid-subject",
            "sub가 UUID가 아니면 우리가 발급한 토큰이 아니다",
            JWT_SECRET,
            encode({"sub": "not-a-uuid", "exp": exp, "typ": _TOKEN_TYPE}, JWT_SECRET),
            live_at,
        ),
        ("garbage", "JWT 형식이 아닌 문자열", JWT_SECRET, "not-a-jwt", live_at),
        (
            "secret-exactly-min-bytes",
            f"시크릿 {MIN_JWT_SECRET_BYTES}바이트는 하한이라 받아들여진다",
            JWT_MIN_SECRET,
            issue(JWT_MIN_SECRET, JWT_SUBJECT),
            live_at,
        ),
        (
            "secret-one-byte-short",
            f"{MIN_JWT_SECRET_BYTES - 1}바이트 시크릿은 기동 경로에서 설정 오류로 끊는다 "
            "(PyJWT는 경고만 하고 서명해 준다)",
            JWT_TOO_SHORT_SECRET,
            valid,
            live_at,
        ),
    ]
    cases = [
        _case(
            f"jwt-{name}",
            description,
            {"secret": secret, "token": token, "verify_at": verify_at},
            outcome(secret, token, verify_at),
        )
        for name, description, secret, token, verify_at in samples
    ]
    cases.append(
        _case(
            "jwt-roundtrip-request",
            "역방향 검증용 — Kotlin이 이 subject들로 토큰을 발급하고 verify-jwt로 Python이 읽는다. "
            "만료 동작까지 보이려면 expected_outcome=invalid_credentials 케이스를 함께 낸다",
            {
                "secret": JWT_SECRET,
                "expire_minutes": JWT_EXPIRE_MINUTES,
                "algorithm": _ALGORITHM,
                "token_type": _TOKEN_TYPE,
                "subjects": [JWT_SUBJECT, JWT_OTHER_SUBJECT],
            },
            {"outcome": "verified_externally"},
            verification=_external(
                script="dump_parity_fixtures.py verify-jwt",
                proof="verify-jwt.verified.json",
                required_cases=2,
                actual_schema={
                    "secret": "이 케이스의 secret을 그대로",
                    "token": "Kotlin이 발급한 토큰",
                    "verify_at": "이 토큰을 평가할 기준 시각(epoch 초, UTC)",
                    "expected_subject": "토큰에 담은 sub (ok 케이스만)",
                    "expected_outcome": "ok | invalid_credentials (생략 시 ok)",
                },
            ),
        )
    )
    return (
        "app/services/auth.py::AuthService._issue_token / AuthService.resolve_token",
        NO_NORMALIZATION,
        cases,
    )


# ---------------------------------------------------------------------------- Argon2

#: 예전 파라미터로 만들어진 해시를 흉내 낸다. 현재 `_HASHER`(t=3, m=65536, p=4)보다
#: 모든 비용이 낮아 `check_needs_rehash`가 True를 내야 한다.
ARGON2_LEGACY_TIME_COST = 2
ARGON2_LEGACY_MEMORY_COST = 8192
ARGON2_LEGACY_PARALLELISM = 2


def build_argon2() -> tuple[str, list[str], list[Case]]:
    """Argon2 PHC — 기존 해시 그대로 검증, 재해시 판정, 변조·오입력 거부"""
    return asyncio.run(_argon2_cases())


async def _argon2_cases() -> tuple[str, list[str], list[Case]]:
    from argon2 import PasswordHasher, extract_parameters
    from argon2.exceptions import InvalidHashError

    from app.services.auth import _HASHER, MIN_PASSWORD_LENGTH, hash_password, verify_password

    async def outcome(phc: str, password: str) -> dict[str, Any]:
        """실제 검증 경로(`verify_password`)와 재해시 판정(`check_needs_rehash`)의 결과.

        Argon2 해시는 솔트가 매번 새로 생성되므로 **출력 문자열을 비교할 수 없다.**
        그래서 fixture는 언제나 "주어진 PHC 문자열을 검증하는" 방향으로만 짠다.
        """
        try:
            needs_rehash: bool | None = _HASHER.check_needs_rehash(phc)
        except InvalidHashError:
            # PHC 형식이 깨져 파라미터를 읽을 수 없다 — 참/거짓 어느 쪽도 아니다.
            needs_rehash = None
        return {
            "verified": await verify_password(phc, password),
            "needs_rehash": needs_rehash,
            # 파일을 읽는 과정에서 비밀번호가 NFC/NFD로 접히면 해시가 통째로 달라진다.
            # 양쪽이 같은 바이트를 넣었는지 여기서 먼저 드러난다.
            "password_utf8_sha256": hashlib.sha256(password.encode("utf-8")).hexdigest(),
        }

    hangul = "비밀번호-가나다-1234"
    passwords: list[tuple[str, str, str]] = [
        ("ascii", "correct-horse-battery-staple", "ASCII 비밀번호"),
        ("hangul", hangul, "한글 비밀번호 — UTF-8 바이트가 그대로 해싱된다"),
        (
            "long",
            "가나다라마바사" * 40,
            "긴 비밀번호 — argon2는 입력 길이에 비용이 좌우되지 않는다",
        ),
        (
            "empty",
            "",
            f"빈 비밀번호 — 해셔 자체는 막지 않는다. 길이 하한({MIN_PASSWORD_LENGTH}자)은 "
            "가입 경로(AuthService.signup)의 정책이지 해시 계층의 동작이 아니다",
        ),
        (
            "symbols",
            "p@ss\twith\nnewline & 공백 ",
            "제어문자·공백이 섞인 비밀번호도 그대로 해싱된다",
        ),
    ]

    cases: list[Case] = []
    for name, password, description in passwords:
        phc = await hash_password(password)
        cases.append(
            _case(
                f"argon2-verify-{name}",
                f"{description} — Python이 만든 PHC를 Kotlin이 그대로 검증한다",
                {"phc": phc, "password": password},
                await outcome(phc, password),
            )
        )

    ascii_phc = await hash_password("correct-horse-battery-staple")
    cases.append(
        _case(
            "argon2-wrong-password",
            "틀린 비밀번호는 거부된다",
            {"phc": ascii_phc, "password": "correct-horse-battery-stapl"},
            await outcome(ascii_phc, "correct-horse-battery-stapl"),
        )
    )
    hangul_phc = await hash_password(hangul)
    hangul_nfd = unicodedata.normalize("NFD", hangul)
    cases.append(
        _case(
            "argon2-hangul-nfd-mismatch",
            "NFC로 해싱한 비밀번호는 NFD 표기로 검증되지 않는다 — 이 도메인에 nfc 정규화를 "
            "걸면 안 되는 이유다. 정규화가 이 케이스를 통과시키면 검증이 은폐로 바뀐다",
            {"phc": hangul_phc, "password": hangul_nfd},
            await outcome(hangul_phc, hangul_nfd),
        )
    )
    # 해시 본문 마지막 글자만 바꾼다 — 파라미터 구획은 멀쩡하므로 재해시 판정은 살아 있고
    # 검증만 실패해야 한다.
    flipped = "A" if ascii_phc[-1] != "A" else "B"
    tampered_phc = ascii_phc[:-1] + flipped
    cases.append(
        _case(
            "argon2-tampered-phc",
            "변조된 PHC 문자열은 거부된다 (파라미터 구획은 온전하므로 재해시 판정은 살아 있다)",
            {"phc": tampered_phc, "password": "correct-horse-battery-staple"},
            await outcome(tampered_phc, "correct-horse-battery-staple"),
        )
    )
    cases.append(
        _case(
            "argon2-invalid-phc",
            "PHC 형식이 아닌 문자열 — 검증 실패이고 재해시 판정은 불가(null)다. "
            "예외를 밖으로 흘리면 '불일치'와 '해시가 깨짐'이 응답으로 구분돼 새어 나간다",
            {"phc": "not-a-phc-string", "password": "correct-horse-battery-staple"},
            await outcome("not-a-phc-string", "correct-horse-battery-staple"),
        )
    )

    legacy_hasher = PasswordHasher(
        time_cost=ARGON2_LEGACY_TIME_COST,
        memory_cost=ARGON2_LEGACY_MEMORY_COST,
        parallelism=ARGON2_LEGACY_PARALLELISM,
    )
    legacy_password = "legacy-parameter-password"
    legacy_phc = legacy_hasher.hash(legacy_password)
    cases.append(
        _case(
            "argon2-legacy-verify",
            "낮은 파라미터로 만든 기존 해시도 그대로 검증된다 — 이것이 깨지면 기존 사용자가 "
            "전부 로그인하지 못한다. 재해시는 **로그인 성공 시에만** 하고(_rehash_if_outdated), "
            "실패해도 로그인은 계속 진행한다(best-effort)",
            {"phc": legacy_phc, "password": legacy_password},
            await outcome(legacy_phc, legacy_password),
        )
    )
    cases.append(
        _case(
            "argon2-legacy-wrong-password",
            "낮은 파라미터 해시라도 틀린 비밀번호는 거부된다 (재해시 판정은 그대로 True)",
            {"phc": legacy_phc, "password": "wrong-password"},
            await outcome(legacy_phc, "wrong-password"),
        )
    )

    def parameters(phc: str) -> dict[str, Any]:
        params = extract_parameters(phc)
        return {
            "type": params.type.name,
            "version": params.version,
            "time_cost": params.time_cost,
            "memory_cost": params.memory_cost,
            "parallelism": params.parallelism,
            "salt_len": params.salt_len,
            "hash_len": params.hash_len,
            "phc_prefix": "$".join(phc.split("$")[:4]) + "$",
        }

    cases.append(
        _case(
            "argon2-phc-parameters",
            "PHC 문자열 파싱 — 형식·버전·비용 파라미터를 같은 값으로 읽어야 한다",
            {"phc": ascii_phc},
            parameters(ascii_phc),
        )
    )
    cases.append(
        _case(
            "argon2-legacy-phc-parameters",
            "임의 파라미터의 PHC도 읽을 수 있어야 한다 (현재 설정값을 가정해 파싱하면 안 된다)",
            {"phc": legacy_phc},
            parameters(legacy_phc),
        )
    )
    cases.append(
        _case(
            "argon2-current-parameters",
            "양쪽 해셔의 설정값 — 라이브러리 기본값에 기대지 않고 명시 고정한 값이다. "
            "여기가 갈리면 로그인마다 불필요한 재해시가 돌거나 반대로 이관이 멈춘다",
            {},
            {
                "time_cost": _HASHER.time_cost,
                "memory_cost": _HASHER.memory_cost,
                "parallelism": _HASHER.parallelism,
            },
        )
    )
    return (
        "app/services/auth.py::hash_password / verify_password / "
        "AuthService._rehash_if_outdated(_HASHER.check_needs_rehash)",
        NO_NORMALIZATION,
        cases,
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
    "crypto": build_crypto,
    "jwt": build_jwt,
    "argon2": build_argon2,
}


def summary(builder: Builder) -> str:
    """빌더 docstring 첫 줄 (없으면 빈 문자열)."""
    doc = (builder.__doc__ or "").strip()
    return doc.splitlines()[0] if doc else ""


def dump(domains: list[str], out_root: Path) -> int:
    written = 0
    for domain in domains:
        source, normalization, cases = BUILDERS[domain]()
        payload = {
            "domain": domain,
            "source": source,
            "generator": "dump_parity_fixtures.py",
            "generated_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "normalization": normalization,
            "cases": cases,
        }
        target = out_root / domain / f"{domain}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
            encoding="utf-8",
        )
        shown = target.relative_to(REPO_ROOT) if target.is_relative_to(REPO_ROOT) else target
        print(f"[생성] {shown} — {len(cases)}건 (source: {source})")
        written += 1
    return 0 if written else 1


#: 역방향 검증의 실행 증거 파일 이름. fixture의 `verification.proof`와 같아야 하고,
#: Kotlin 산출물(`--actual`)과 **같은 디렉터리**에 놓인다 — `compare_parity.py`가 거기서 찾는다.
PROOF_NAMES = {
    "verify-crypto": ("crypto-roundtrip-request", "verify-crypto.verified.json"),
    "verify-jwt": ("jwt-roundtrip-request", "verify-jwt.verified.json"),
}


def _write_proof(
    command: str, actual_path: Path, proof_path: Path | None, checked: int, failures: list[str]
) -> Path:
    """역방향 검증을 **실제로 돌렸다**는 증거를 남긴다.

    이 파일이 없으면 `compare_parity.py`는 해당 케이스를 통과가 아니라 미검증으로 센다.
    실패했을 때도 남긴다 — 실패를 침묵으로 두면 미검증과 구분되지 않는다.
    failures에는 케이스 id와 사유 분류만 담는다(평문·토큰·키 금지).
    """
    fixture_case, default_name = PROOF_NAMES[command]
    target = proof_path or actual_path.parent / default_name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(
            {
                "script": f"dump_parity_fixtures.py {command}",
                "fixture_case": fixture_case,
                "status": "fail" if failures else "pass",
                "checked": checked,
                "actual": str(actual_path),
                "verified_at": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "failures": failures,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return target


def _report_verification(command: str, target: Path, checked: int, failures: list[str]) -> int:
    if failures:
        print(f"역방향 검증 실패 ({command}):")
        for failure in failures:
            print(f"  - {failure}")
        print(f"[증거] {target} (status: fail)")
        return 1
    print(f"역방향 검증 통과 ({command}): {checked}건")
    print(f"[증거] {target} (status: pass)")
    return 0


def verify_crypto(actual_path: Path, proof_path: Path | None) -> int:
    """Kotlin이 만든 암호문을 Python이 읽는 역방향 검증.

    입력 JSON: {"cases": [{"id": ..., "key": ..., "token": ..., "expected_plaintext": ...}]}
    """
    from app.exceptions import StorageError
    from app.privacy.crypto import TextCipher

    payload = json.loads(actual_path.read_text(encoding="utf-8"))
    cases = payload.get("cases", [])
    failures: list[str] = []
    for case in cases:
        case_id = case.get("id", "?")
        try:
            got = TextCipher(case["key"]).decrypt(case["token"].encode("ascii"))
        except (StorageError, Exception) as exc:  # noqa: BLE001 - 어떤 실패든 불일치로 본다
            failures.append(f"{case_id}: 복호화 실패 ({type(exc).__name__})")
            continue
        if got != case.get("expected_plaintext"):
            failures.append(
                f"{case_id}: 평문 불일치 "
                f"(길이 기대 {len(case.get('expected_plaintext', ''))} / 실제 {len(got)})"
            )
    target = _write_proof("verify-crypto", actual_path, proof_path, len(cases), failures)
    return _report_verification("verify-crypto", target, len(cases), failures)


def verify_jwt(actual_path: Path, proof_path: Path | None) -> int:
    """Kotlin이 발급한 토큰을 Python이 읽는 역방향 검증.

    입력 JSON: {"cases": [{"id", "secret", "token", "verify_at",
                           "expected_subject", "expected_outcome"}]}
    `verify_at`(epoch 초)을 반드시 넣는다 — 벽시계로 판정하면 같은 산출물이 실행 시각에
    따라 통과와 실패를 오간다.
    """
    from jwt import api_jwt

    from app.exceptions import ConfigurationError, InvalidCredentialsError
    from app.services.auth import AuthService, UserStore, WorkspaceCreator

    payload = json.loads(actual_path.read_text(encoding="utf-8"))
    cases = payload.get("cases", [])
    failures: list[str] = []
    for case in cases:
        case_id = case.get("id", "?")
        expected_outcome = case.get("expected_outcome", "ok")
        if "verify_at" not in case:
            failures.append(f"{case_id}: verify_at 누락 — 기준 시각 없이는 만료 판정이 무의미하다")
            continue
        try:
            service = AuthService(
                cast(UserStore, _UNUSED_STORE),
                cast(WorkspaceCreator, _UNUSED_STORE),
                case["secret"],
                JWT_EXPIRE_MINUTES,
            )
        except ConfigurationError:
            failures.append(f"{case_id}: 시크릿이 최소 길이 미달")
            continue
        moment = datetime.fromtimestamp(int(case["verify_at"]), UTC)
        with _frozen(api_jwt, moment):
            try:
                subject = str(service.resolve_token(case["token"]))
            except InvalidCredentialsError:
                if expected_outcome != "invalid_credentials":
                    failures.append(f"{case_id}: 검증 실패 (InvalidCredentialsError)")
                continue
        if expected_outcome == "invalid_credentials":
            failures.append(f"{case_id}: 거부돼야 할 토큰이 통과했다")
        elif subject != case.get("expected_subject"):
            # 토큰·sub 값을 메시지에 담지 않는다 — 토큰 자체가 자격증명이다.
            failures.append(f"{case_id}: sub 불일치")
    target = _write_proof("verify-jwt", actual_path, proof_path, len(cases), failures)
    return _report_verification("verify-jwt", target, len(cases), failures)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "command", nargs="?", default="dump", choices=["dump", "verify-crypto", "verify-jwt"]
    )
    parser.add_argument(
        "--domain", action="append", default=[], help="생성할 도메인 (반복 가능, 기본 전체)"
    )
    parser.add_argument(
        "--out", type=Path, default=DEFAULT_OUT, help=f"출력 루트 (기본 {DEFAULT_OUT})"
    )
    parser.add_argument("--actual", type=Path, help="verify-crypto / verify-jwt 입력 JSON")
    parser.add_argument(
        "--proof",
        type=Path,
        help="실행 증거 파일 경로 (기본: --actual 과 같은 디렉터리의 verify-*.verified.json)",
    )
    parser.add_argument("--list", action="store_true", help="도메인 목록만 출력")
    args = parser.parse_args()

    if args.list:
        for name, builder in BUILDERS.items():
            print(f"{name:16} {summary(builder)}")
        return 0
    if args.command in PROOF_NAMES:
        if args.actual is None:
            parser.error(f"{args.command}에는 --actual 이 필요합니다")
        verify = verify_crypto if args.command == "verify-crypto" else verify_jwt
        return verify(args.actual, args.proof)

    unknown = [d for d in args.domain if d not in BUILDERS]
    if unknown:
        parser.error(f"알 수 없는 도메인: {', '.join(unknown)} (가능: {', '.join(BUILDERS)})")
    return dump(args.domain or list(BUILDERS), args.out)


if __name__ == "__main__":
    raise SystemExit(main())
