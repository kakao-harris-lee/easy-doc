"""easydict: 쉬운 말 사전 코어 라이브러리.

지적·발달장애인을 위한 공공/복지 문서 쉬운 글 변환 서비스(easy-doc)의
RAG 사전 계층. 공공데이터 순화어 대조표를 정규화·증강해 SQLite/JSON
산출물로 만든다. 표준 라이브러리만 사용한다(외부 패키지 의존성 없음).

이 패키지 `__init__`은 `models`/`normalize` 두 모듈(이 서브모듈만
현재 구현되어 있음)의 공개 심볼만 재노출한다. `build`/`export`/`lookup`
모듈이 추가되면 각자 필요한 곳에서 `from easydict import build` 등으로
직접 import하면 된다 — 여기서 미리 끌어오면 아직 없는 모듈 때문에
패키지 전체 import가 깨진다.
"""
from __future__ import annotations

from .models import (
    POS_VALUES,
    REPLACE_STRATEGIES,
    RISK_LEVELS,
    SCHEMA_VERSION,
    STATUSES,
    TAG_CATALOG,
    VARIANT_KINDS,
    Entry,
    Example,
    Source,
    Variant,
)

__version__ = SCHEMA_VERSION

__all__ = [
    "SCHEMA_VERSION",
    "TAG_CATALOG",
    "REPLACE_STRATEGIES",
    "RISK_LEVELS",
    "STATUSES",
    "POS_VALUES",
    "VARIANT_KINDS",
    "Source",
    "Variant",
    "Example",
    "Entry",
]
