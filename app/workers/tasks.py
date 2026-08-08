"""arq 워커 작업 — 문서 변환과 보존 만료 문서 파기.

워커는 사용자 요청 맥락 없이 큐가 넘겨준 식별자만 들고 일한다. 그래서 여기서 지켜야
할 규칙이 둘이다.

1. **마스킹 선행.** 원문은 `ConversionService`를 통해서만 LLM에 닿는다 — 이 모듈에
   provider를 직접 부르는 코드는 없다 (master-plan 3.2). 서비스가 마스킹 → 프롬프트 →
   호출 순서를 소유하므로, 워커 경로에서도 불변식이 저절로 지켜진다.
2. **본문 비유출.** 로그·예외에 문서 본문·변환 결과·모델 응답을 담지 않는다. 남기는
   것은 변환 식별자·상태·실패 코드·소요 시간뿐이다.

실패 처리 정책:

- **도메인 예외**는 실패로 기록한다(failure_code = 예외 클래스명). 사용자가 원인을 알고
  다시 시도할 수 있는 상태다.
- **그 밖의 예외**(DB 일시 장애 등)는 `Retry`로 바꿔 던진다. arq는 일반 예외를 재시도하지
  **않고** 작업을 영구히 버린다(arq 0.28 `Worker.run_job`: `Retry`·`RetryJob`·
  `CancelledError`만 재큐한다). 그냥 올리면 mark_done 직전의 DB 장애 한 번에, 이미 비용을
  치른 LLM 결과가 사라지고 행은 processing에 굳는다. 재시도 횟수는 WorkerSettings의
  `max_tries`가 제한한다.

멱등성: 재시도와 중복 배달이 전제인 큐라, 이미 done인 변환은 **다시 처리하지 않는다**
(LLM 비용 이중 지불과, 사용자가 이미 받아 본 결과가 바뀌는 것을 막는다). 판정은 읽은
값이 아니라 `mark_processing`의 조건부 UPDATE가 한다 — 읽고-나서-쓰는 사이의 틈을 없앤다.

보존 만료 파기(`purge_expired_documents`)는 사용자 요청이 아니라 cron이 부르는 작업이다
(등록: `app/workers/settings.py`의 `WorkerSettings.cron_jobs`). 남기는 로그는 삭제
**건수**뿐이다 — 어떤 문서가 지워졌는지는 이미 사라진 개인정보에 대한 기록이 된다.
"""

import logging
import time
import uuid
from collections.abc import Callable
from contextlib import AbstractAsyncContextManager
from datetime import timedelta
from typing import Any, Protocol

from arq.worker import Retry

from app.exceptions import EasyDocError
from app.llm.provider import LLMProvider
from app.models.conversion import Conversion, ConversionStatus
from app.models.document import Document
from app.privacy.crypto import TextCipher
from app.services.conversion import ConversionService
from app.services.documents import serialize_masked_items

_logger = logging.getLogger(__name__)

#: LLM provider를 만들 수 없을 때(API 키 미설정) 남기는 실패 코드.
PROVIDER_UNAVAILABLE_CODE = "ProviderUnavailable"

#: 예상 못 한 오류로 재시도할 때 기다리는 시간. DB·네트워크가 잠깐 흔들린 경우를
#: 상정한 값이다 — 즉시 재시도하면 장애 중인 자원을 계속 두드려 회복을 방해한다.
RETRY_DEFER = timedelta(seconds=30)


class ConversionWorkerStore(Protocol):
    """워커가 변환 저장소에 요구하는 계약 (구현: ConversionRepository).

    요청 처리 경로가 쓰는 계약(`app.services.documents.ConversionStore`)과 분리해 둔다 —
    워커만 상태를 processing·done으로 옮길 수 있고, API는 소유자 조회만 할 수 있다.
    """

    async def get_with_document(
        self, conversion_id: uuid.UUID
    ) -> tuple[Conversion, Document] | None:
        """변환과 원본 문서를 함께 읽는다. 없으면 None."""
        ...

    async def mark_processing(self, conversion: Conversion) -> bool:
        """처리 시작을 기록한다. 이미 끝난 변환이면 아무것도 바꾸지 않고 False."""
        ...

    async def mark_done(
        self,
        conversion: Conversion,
        *,
        easy_text_encrypted: bytes,
        masked_items_encrypted: bytes,
        missing_placeholders: list[str],
        provider_name: str,
        model: str,
        input_tokens: int,
        output_tokens: int,
    ) -> None:
        """변환 성공 결과를 기록한다 (본문은 암호문으로만 받는다)."""
        ...

    async def mark_failed(self, conversion: Conversion, failure_code: str) -> None:
        """변환 실패를 기록한다."""
        ...

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다."""
        ...


#: 작업 하나가 쓸 저장소(=세션)를 열고 닫는 스코프. on_startup이 만들어 ctx에 넣는다.
StoreScope = Callable[[], AbstractAsyncContextManager[ConversionWorkerStore]]


class RetentionStore(Protocol):
    """보존 만료 파기 잡이 문서 저장소에 요구하는 계약 (구현: DocumentRepository).

    변환 저장소 계약과 마찬가지로 필요한 만큼으로 좁힌다 — 이 잡은 문서를 **지우기만**
    하며, 읽거나 만들 권한은 갖지 않는다.
    """

    async def delete_expired(self, *, limit: int) -> int:
        """보존 기간이 지난 문서를 최대 limit건 지운다(커밋하지 않는다). 지운 건수를 준다."""
        ...

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다."""
        ...


#: 파기 잡이 쓸 저장소(=세션)를 열고 닫는 스코프. on_startup이 만들어 ctx에 넣는다.
RetentionScope = Callable[[], AbstractAsyncContextManager[RetentionStore]]

#: 한 번에 지우는 문서 수. 트랜잭션 하나가 잠그는 행 수를 묶는다 — 만료 문서가 수만 건
#: 쌓인 날 단일 DELETE는 그만큼을 한 트랜잭션에 담아, 그 사이 업로드·조회가 밀린다.
RETENTION_BATCH_SIZE = 500

#: 한 번의 실행이 도는 최대 배치 수. 안전장치다 — 저장소가 지웠다고 하는데 실제로는
#: 줄지 않는 상황(버그)에서 잡이 영원히 돌지 않게 한다. 하루 최대 50만 건이면
#: 파일럿 규모에서는 사실상 상한에 닿지 않는다.
RETENTION_MAX_BATCHES = 1_000


# ctx가 dict[str, Any]인 것은 arq가 정한 모양이라 좁힐 수 없다.
async def convert_document(ctx: dict[str, Any], conversion_id: str) -> None:
    """변환 한 건을 수행한다 (arq 진입점).

    ctx에서 읽는 값은 `app/workers/settings.py`의 startup이 채운다:
    `store_scope`(StoreScope), `cipher`(TextCipher), `provider`(LLMProvider | None).

    Args:
        conversion_id: conversions.id의 문자열 표현. 큐에는 식별자만 실린다 —
            본문을 큐에 넣으면 Redis에 평문 개인정보가 쌓인다.

    Raises:
        Retry: 도메인 예외가 아닌 오류가 났다. 잠시 뒤 다시 시도한다.
    """
    try:
        identifier = uuid.UUID(conversion_id)
    except ValueError:
        # 우리가 넣지 않은 값이 큐에 있다. 재시도해도 달라지지 않으므로 여기서 끝낸다.
        # 값 자체는 로그에 남기지 않는다(무엇이 들어올지 알 수 없다).
        _logger.error("변환 작업 식별자 형식이 올바르지 않습니다")
        return

    # ctx에서 꺼낸 값은 즉시 타입을 붙인다 — Any가 아래로 퍼지면 배선 실수를 mypy가
    # 잡아 주지 못한다.
    store_scope: StoreScope = ctx["store_scope"]
    cipher: TextCipher = ctx["cipher"]
    provider: LLMProvider | None = ctx["provider"]
    started = time.monotonic()

    try:
        async with store_scope() as store:
            await _process(
                store=store,
                cipher=cipher,
                provider=provider,
                identifier=identifier,
                started=started,
            )
    except Retry:
        raise
    except Exception as exc:
        # 여기까지 온 것은 도메인 예외가 아니다 — DB·네트워크가 흔들렸거나 우리 버그다.
        # 그냥 올리면 arq가 작업을 **영구히 버리므로**(모듈 docstring 참고) 명시적으로
        # 재시도로 바꾼다. 사유는 타입만 남긴다: DB 예외 메시지에는 SQL 파라미터(=암호문·
        # 제목)가, Redis 예외에는 접속 URL이 섞여 들어온다.
        _logger.warning(
            "변환 작업 재시도 예약: conversion_id=%s reason=%s elapsed_ms=%d",
            identifier,
            type(exc).__name__,
            _elapsed_ms(started),
        )
        raise Retry(defer=RETRY_DEFER) from None


# ctx가 dict[str, Any]인 것은 arq가 정한 모양이라 좁힐 수 없다.
async def purge_expired_documents(ctx: dict[str, Any]) -> None:
    """보존 기간이 지난 문서를 지운다 (arq cron 진입점 — master-plan 3.2).

    "기본 보존 30일 후 자동 삭제"를 이행하는 유일한 경로다. 만료 판정과 삭제는 DB가
    한 문장으로 하고(`DocumentRepository.delete_expired`), 여기서는 배치를 반복하며
    배치마다 커밋한다 — 도중에 워커가 죽어도 이미 지운 만큼은 확정되어 있고, 다음
    실행이 남은 것부터 이어서 지운다.

    실패를 `Retry`로 바꾸지 않는 이유: 이 잡은 매일 다시 돈다. 일시 장애로 오늘 못
    지운 문서는 내일 지워지므로, 재시도로 장애 중인 DB를 계속 두드릴 이유가 없다.

    ctx에서 읽는 값은 `app/workers/settings.py`의 startup이 채운다:
    `retention_scope`(RetentionScope).

    수동 실행(파기를 앞당겨야 할 때)::

        docker compose exec worker python -m app.workers.purge
    """
    retention_scope: RetentionScope = ctx["retention_scope"]
    started = time.monotonic()
    deleted = 0
    try:
        async with retention_scope() as store:
            for _ in range(RETENTION_MAX_BATCHES):
                batch = await store.delete_expired(limit=RETENTION_BATCH_SIZE)
                # 배치마다 확정한다 — 다음 배치가 실패해도 여기까지는 되돌아가지 않는다.
                await store.commit()
                deleted += batch
                if batch < RETENTION_BATCH_SIZE:
                    # 상한보다 적게 지웠다는 것은 더 지울 것이 없다는 뜻이다.
                    break
            else:
                # 상한까지 돌고도 끝나지 않았다. 다음 실행이 이어서 지우므로 오류는
                # 아니지만, 만료 대기열이 하루치 처리량을 넘었다는 신호라 남긴다.
                _logger.warning("보존 만료 삭제가 배치 상한에 걸렸습니다: deleted=%d", deleted)
    except Exception as exc:
        # 사유는 타입만 남긴다 — DB 예외 메시지에는 SQL 파라미터(=암호문·제목)가 실린다.
        _logger.error(
            "보존 만료 삭제 실패: deleted=%d reason=%s elapsed_ms=%d",
            deleted,
            type(exc).__name__,
            _elapsed_ms(started),
        )
        return
    # 어떤 문서를 지웠는지는 남기지 않는다 — 이미 파기한 개인정보의 흔적이 로그에 남는다.
    _logger.info("보존 만료 삭제 완료: deleted=%d elapsed_ms=%d", deleted, _elapsed_ms(started))


async def _process(
    *,
    store: ConversionWorkerStore,
    cipher: TextCipher,
    provider: LLMProvider | None,
    identifier: uuid.UUID,
    started: float,
) -> None:
    """세션이 열린 상태에서 실제 변환을 수행한다.

    도메인 예외는 여기서 실패로 확정하고, 그 밖의 예외는 호출자가 재시도로 바꾸도록
    그대로 통과시킨다.
    """
    loaded = await store.get_with_document(identifier)
    if loaded is None:
        # 보존 기간이 지나 삭제됐거나 계정이 사라진 경우 — 재시도할 일이 아니다.
        _logger.warning("변환 대상을 찾을 수 없습니다: conversion_id=%s", identifier)
        return
    conversion, document = loaded

    if conversion.status == ConversionStatus.DONE:
        # 재시도·중복 배달이다. 다시 변환하면 LLM 비용을 또 치르고, 사용자가 이미 받아 본
        # 결과가 새 결과로 바뀐다(모델은 결정적이지 않다).
        _logger.info("이미 완료된 변환입니다: conversion_id=%s", identifier)
        return

    if provider is None:
        # 키 없이 워커가 떴다. 대기 상태로 방치하면 사용자는 끝나지 않는 진행 표시만 본다.
        await _fail(store, conversion, PROVIDER_UNAVAILABLE_CODE, started)
        return

    if not await store.mark_processing(conversion):
        # 위 상태 검사와 이 UPDATE 사이에 다른 워커가 끝냈다. 조건부 UPDATE가 유일하게
        # 틈 없는 판정이므로 여기서 물러난다.
        _logger.info("다른 작업이 이미 완료했습니다: conversion_id=%s", identifier)
        return
    # 여기서 커밋해야 조회 API가 "처리 중"을 보여줄 수 있다(변환은 수 초 걸린다).
    await store.commit()

    try:
        source_text = cipher.decrypt(document.source_text_encrypted)
        # 원문은 반드시 이 서비스를 거친다 — 마스킹 선행 불변식의 유일한 관문이다.
        outcome = await ConversionService(provider).convert(source_text)
    except EasyDocError as exc:
        # 예외 메시지는 남기지 않는다. 도메인 예외라 본문을 담지 않기로 되어 있지만,
        # 그 규약을 이 자리에서 다시 확인할 수는 없다 — 타입 이름만 기록한다.
        await _fail(store, conversion, type(exc).__name__, started)
        return

    await store.mark_done(
        conversion,
        easy_text_encrypted=cipher.encrypt(outcome.easy_text),
        masked_items_encrypted=cipher.encrypt(serialize_masked_items(outcome.masked_items)),
        missing_placeholders=outcome.missing_placeholders,
        provider_name=outcome.provider_name,
        model=outcome.model,
        input_tokens=outcome.input_tokens,
        output_tokens=outcome.output_tokens,
    )
    await store.commit()
    _logger.info("변환 완료: conversion_id=%s elapsed_ms=%d", identifier, _elapsed_ms(started))


async def _fail(
    store: ConversionWorkerStore, conversion: Conversion, failure_code: str, started: float
) -> None:
    """실패를 확정하고 사유 코드만 로그에 남긴다."""
    await store.mark_failed(conversion, failure_code)
    await store.commit()
    _logger.warning(
        "변환 실패: conversion_id=%s code=%s elapsed_ms=%d",
        conversion.id,
        failure_code,
        _elapsed_ms(started),
    )


def _elapsed_ms(started: float) -> int:
    """작업 시작 이후 경과 시간(밀리초). 벽시계가 아니라 단조 시계를 쓴다."""
    return int((time.monotonic() - started) * 1000)
