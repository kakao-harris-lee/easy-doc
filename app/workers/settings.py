"""arq 워커 설정.

실행::

    uv run arq app.workers.settings.WorkerSettings

DB 엔진·LLM provider·암호기는 워커 수명 단위로 한 번 만들어 ctx에 담는다 — 작업마다
새로 만들면 커넥션 풀이 재사용되지 않는다(provider 수명 규약은 app/llm/provider.py).
세션만은 작업마다 새로 연다: 워커는 여러 작업을 동시에 돌리므로 세션을 공유하면
한 작업의 롤백이 다른 작업의 트랜잭션까지 무너뜨린다.
"""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from arq.connections import RedisSettings
from arq.worker import func
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.config import Settings
from app.db import create_engine_and_factory
from app.exceptions import ConfigurationError
from app.llm.factory import create_provider
from app.privacy.crypto import TextCipher
from app.queue import CONVERT_DOCUMENT_TASK
from app.repositories.conversions import ConversionRepository
from app.workers.tasks import ConversionWorkerStore, StoreScope, convert_document

_logger = logging.getLogger(__name__)


def _make_store_scope(session_factory: async_sessionmaker[AsyncSession]) -> StoreScope:
    """작업마다 세션을 열고 닫는 저장소 스코프를 만든다."""

    @asynccontextmanager
    async def scope() -> AsyncIterator[ConversionWorkerStore]:
        async with session_factory() as session:
            yield ConversionRepository(session)

    return scope


# ctx가 dict[str, Any]인 것은 arq가 정한 모양이다(작업 쪽에서 타입을 붙여 쓴다).
async def startup(ctx: dict[str, Any]) -> None:
    """워커 기동 — 엔진·저장소 스코프·암호기·provider를 준비한다.

    암호화 키가 없으면 기동을 멈춘다. 키 없이 뜨면 들어오는 작업마다 복호화 단계에서
    실패해 사용자의 문서만 축내므로, 배포 직후 로그에서 바로 드러나는 편이 낫다.
    API 키는 이와 달리 없어도 뜬다 — 그쪽은 작업이 `ProviderUnavailable`로 기록되어
    사용자에게 상태로 전달된다.
    """
    settings = Settings()
    if settings.fernet_key is None:
        raise ConfigurationError("문서 암호화 키(FERNET_KEY)가 설정되지 않았습니다")

    engine, session_factory = create_engine_and_factory(settings.database_url)
    # 만들자마자 ctx에 넣는다 — 아래에서 예외가 나면 shutdown이 정리할 수 있어야 하는데,
    # 마지막에 몰아 넣으면 그 엔진은 아무도 dispose 하지 못하고 남는다.
    ctx["engine"] = engine
    ctx["store_scope"] = _make_store_scope(session_factory)
    ctx["cipher"] = TextCipher(settings.fernet_key.get_secret_value())

    provider = create_provider(settings.llm_provider, settings)
    if provider is None:
        _logger.warning(
            "LLM API 키가 없어 변환 작업이 실패로 기록됩니다: provider=%s", settings.llm_provider
        )
    ctx["provider"] = provider


async def shutdown(ctx: dict[str, Any]) -> None:
    """워커 종료 — provider의 커넥션 풀과 DB 엔진을 정리한다.

    startup이 도중에 실패했을 수 있으므로 있는 것만 정리한다.
    """
    provider = ctx.get("provider")
    if provider is not None:
        await provider.aclose()
    engine = ctx.get("engine")
    if engine is not None:
        await engine.dispose()


class WorkerSettings:
    """arq 진입 설정. arq가 클래스 속성을 그대로 읽어 Worker에 넘긴다.

    `redis_settings`는 모듈 import 시점에 계산된다 — arq가 인스턴스가 아니라 클래스의
    `__dict__`를 보기 때문에 프로퍼티로는 대체할 수 없다. 이 모듈은 워커를 실행할 때만
    import되므로 API 프로세스에는 영향이 없다.
    """

    #: 작업 이름을 상수로 못박는다 — 함수 이름을 바꿔도 큐에 쌓인 작업이 유실되지 않고,
    #: 넣는 쪽(app/queue.py)과 어긋나면 이 자리에서 드러난다.
    functions = [func(convert_document, name=CONVERT_DOCUMENT_TASK)]
    redis_settings = RedisSettings.from_dsn(Settings().redis_url)
    on_startup = startup
    on_shutdown = shutdown

    #: 재시도 상한. 기본값과 같은 5지만 명시한다 — 작업이 `Retry`를 던지는 전제 위에
    #: 서 있는 값이라(app/workers/tasks.py), 라이브러리 기본값이 바뀌면 실패 정책이
    #: 조용히 달라진다. 30초 간격 × 최대 4회 재시도 = 약 2분간 일시 장애를 견딘다.
    max_tries = 5

    #: 작업 하나의 제한 시간(초). provider 타임아웃 60초 × 재시도 2회에 백오프까지 더하면
    #: 최악 약 180초라, 그 위에 여유를 둔 값이다. 기본값과 같지만 위 계산에 의존하는
    #: 숫자이므로 명시한다 — provider 설정을 올리면 여기도 함께 봐야 한다.
    job_timeout = 300
