"""보존 만료 문서 파기 잡의 수동 실행 진입점.

평소에는 arq cron이 매일 04:00 KST에 같은 작업을 돌린다(app/workers/settings.py).
이 모듈은 그 사이에 파기를 앞당겨야 할 때 — 기관이 삭제를 요청했거나, 잡이 실패한
다음 날을 기다릴 수 없을 때 — 운영자가 손으로 한 번 돌리는 자리다::

    docker compose exec worker python -m app.workers.purge

워커 기동과 **같은 배선**(startup/shutdown)을 쓴다. 파기 전용으로 세션을 따로 열면
cron 경로와 다른 코드가 되어, 손으로 돌린 결과가 자동 실행 결과와 같다는 보장이
사라진다.
"""

import asyncio
import logging
from typing import Any

from app.workers.settings import shutdown, startup
from app.workers.tasks import purge_expired_documents


async def _run() -> None:
    """워커 컨텍스트를 만들어 파기 잡을 한 번 돌리고 정리한다."""
    ctx: dict[str, Any] = {}
    await startup(ctx)
    try:
        await purge_expired_documents(ctx)
    finally:
        await shutdown(ctx)


def main() -> None:
    """수동 실행 진입점. 결과(삭제 건수)는 잡이 로그로 남긴다."""
    # arq가 워커에 걸어 주는 로깅 설정이 여기서는 없다 — 삭제 건수를 볼 수 있게 켠다.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    asyncio.run(_run())


if __name__ == "__main__":
    main()
