"""비동기 작업 큐 추상화 (arq + Redis).

서비스 계층은 arq를 알지 않는다 — "이름과 인자를 큐에 넣는다"는 좁은 계약(`TaskQueue`)만
알고, 런타임 구현과 테스트 대역이 그 자리를 채운다. app/db.py와 같은 이유로 전역
싱글턴을 두지 않는다: 연결 수명은 FastAPI lifespan이 소유한다.
"""

import logging
from typing import Protocol

from arq.connections import ArqRedis, RedisSettings, create_pool
from redis.exceptions import RedisError

_logger = logging.getLogger(__name__)

#: 변환 워커 작업 이름. 넣는 쪽(app/services/documents.py)과 받는 쪽
#: (app/workers/settings.py의 등록)이 이 상수 하나를 공유한다 — 문자열을 양쪽에
#: 적어두면 한쪽만 고쳤을 때 작업이 조용히 큐에 쌓이기만 한다.
CONVERT_DOCUMENT_TASK = "convert_document"


class TaskQueue(Protocol):
    """작업 등록 계약. 실패하면 예외를 던진다(호출부가 실패를 기록할 수 있게)."""

    async def enqueue(self, name: str, *args: object) -> None:
        """이름이 가리키는 워커 작업을 인자와 함께 큐에 넣는다."""
        ...


class ArqTaskQueue:
    """arq Redis 풀 위의 TaskQueue 구현."""

    def __init__(self, pool: ArqRedis) -> None:
        self._pool = pool

    async def enqueue(self, name: str, *args: object) -> None:
        """arq 큐에 작업을 등록한다.

        반환되는 Job 핸들은 쓰지 않는다 — 결과 조회는 큐가 아니라 conversions 테이블의
        상태로 한다(워커가 실패해도 사용자에게 보일 기록이 DB에 남아야 하기 때문).
        """
        await self._pool.enqueue_job(name, *args)

    async def aclose(self) -> None:
        """커넥션 풀을 정리한다. 호출 시점은 lifespan이 정한다."""
        await self._pool.aclose()


async def create_task_queue(redis_url: str) -> ArqTaskQueue | None:
    """Redis에 연결해 큐 클라이언트를 만든다. 연결하지 못하면 None.

    실패를 예외로 올리지 않는 이유: Redis가 없어도 앱은 떠야 한다(/health로 배포를
    진단할 수 있어야 하고, 인증·조회 API는 큐와 무관하다). 큐가 필요한 요청만
    `get_task_queue`가 503으로 막는다.
    """
    settings = RedisSettings.from_dsn(redis_url)
    # 기동이 재시도로 수십 초 매달리지 않게 한 번만 시도한다.
    settings.conn_retries = 0
    try:
        return ArqTaskQueue(await create_pool(settings))
    except (OSError, RedisError) as exc:
        # 접속 URL은 남기지 않는다 — 비밀번호가 들어 있을 수 있다.
        _logger.warning("작업 큐에 연결하지 못했습니다: %s", type(exc).__name__)
        return None
