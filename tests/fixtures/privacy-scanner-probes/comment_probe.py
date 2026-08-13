"""스캐너 음성 대조용 합성 파일 (파이썬 문법). 제품 코드가 아니다.

`#`는 `.py`에서만 줄 주석이므로 같은 결함을 파이썬 쪽에서도 따로 확인한다 —
한쪽만 고치면 다른 쪽이 남는다.

`SCAN_ROOTS` 밖(`tests/fixtures/`)에 있어 전수 스캔이 자기 탐침을 잡지 않는다
(그 배치 자체를 `test_합성_탐침이_스캔_루트_밖에_있다`가 단언한다).

**실행되는 코드가 아니지만 유효한 코드로 쓴다.** `ruff`·`mypy`가 이 파일도 검사하므로,
문법을 어겨 두면 그 게이트를 통과시키려고 결국 예외 경로를 파게 된다 — 탐침 하나 때문에
검사 범위에 구멍을 내는 것이 이 저장소가 반복해서 거부해 온 형태다.
"""

import logging
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class _Stats:
    count: int


@dataclass
class _Draft:
    """본문(`value`)과 집계(`stats`)를 함께 든 값 — 실물 `ConversionResult`의 모양이다."""

    stats: _Stats
    value: str


def 줄주석_닫는괄호(draft: _Draft) -> None:
    logger.info(
        "완료 %s %s",
        draft.stats.count,  # 건수) 설명
        draft.value,
    )


def 주석없음(draft: _Draft) -> None:
    logger.info(
        "완료 %s %s",
        draft.stats.count,
        draft.value,
    )
