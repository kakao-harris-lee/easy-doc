"""보존 만료 문서 파기 잡 테스트 (대역 저장소 — DB·Redis 없이 돈다).

여기서 보려는 것은 세 가지다: 더 지울 것이 없으면 멈추는가, 배치마다 확정하는가,
그리고 **로그에 삭제 건수 말고 아무것도 남지 않는가**.

경계 판정(만료/미만료)은 DB 시계가 하므로 대역으로는 확인할 수 없다 —
tests/repositories/test_documents.py의 `@pytest.mark.db` 테스트가 맡는다.
"""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import pytest

from app.workers.tasks import (
    RETENTION_BATCH_SIZE,
    RETENTION_MAX_BATCHES,
    RetentionStore,
    purge_expired_documents,
)
from tests.fakes import FakeRetentionStore


def _ctx(store: RetentionStore) -> dict[str, Any]:
    """대역 저장소를 돌려주는 arq 컨텍스트."""

    @asynccontextmanager
    async def scope() -> AsyncIterator[RetentionStore]:
        yield store

    return {"retention_scope": scope}


async def test_지울_것이_없으면_한_번만_조회하고_끝낸다() -> None:
    store = FakeRetentionStore()

    await purge_expired_documents(_ctx(store))

    assert store.journal == ["delete_expired", "commit"]
    assert store.limits == [RETENTION_BATCH_SIZE]


async def test_상한만큼_지웠으면_다음_배치를_이어서_지운다() -> None:
    """상한을 꽉 채웠다는 것은 더 남아 있을 수 있다는 뜻이다 — 적게 지운 배치에서 멈춘다."""
    store = FakeRetentionStore([RETENTION_BATCH_SIZE, RETENTION_BATCH_SIZE, 7])

    await purge_expired_documents(_ctx(store))

    assert store.journal.count("delete_expired") == 3
    # 배치마다 확정한다 — 도중에 워커가 죽어도 지운 만큼은 되돌아가지 않는다.
    assert store.journal == ["delete_expired", "commit"] * 3


async def test_배치_상한에_걸리면_경고를_남기고_멈춘다(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """다음 실행이 이어서 지우므로 오류는 아니지만, 대기열이 하루치를 넘었다는 신호다."""
    store = FakeRetentionStore([RETENTION_BATCH_SIZE] * (RETENTION_MAX_BATCHES + 5))

    with caplog.at_level(logging.WARNING, logger="app.workers.tasks"):
        await purge_expired_documents(_ctx(store))

    assert store.journal.count("delete_expired") == RETENTION_MAX_BATCHES
    assert "배치 상한" in caplog.text


async def test_삭제_건수를_로그로_남긴다(caplog: pytest.LogCaptureFixture) -> None:
    store = FakeRetentionStore([RETENTION_BATCH_SIZE, 12])

    with caplog.at_level(logging.INFO, logger="app.workers.tasks"):
        await purge_expired_documents(_ctx(store))

    assert f"deleted={RETENTION_BATCH_SIZE + 12}" in caplog.text


async def test_실패해도_예외를_올리지_않고_사유_타입만_남긴다(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """매일 다시 도는 잡이라 재시도하지 않는다. DB 예외 메시지에는 암호문·제목이 실린다."""
    store = FakeRetentionStore(error=RuntimeError("connection to 10.0.0.1 failed: 홍길동"))

    with caplog.at_level(logging.ERROR, logger="app.workers.tasks"):
        await purge_expired_documents(_ctx(store))

    assert "reason=RuntimeError" in caplog.text
    assert "홍길동" not in caplog.text
    assert "10.0.0.1" not in caplog.text
