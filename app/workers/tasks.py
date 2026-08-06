"""arq 변환 작업.

워커는 사용자 요청 맥락 없이 큐가 넘겨준 식별자만 들고 일한다. 그래서 여기서 지켜야
할 규칙이 둘이다.

1. **마스킹 선행.** 원문은 `ConversionService`를 통해서만 LLM에 닿는다 — 이 모듈에
   provider를 직접 부르는 코드는 없다 (master-plan 3.2). 서비스가 마스킹 → 프롬프트 →
   호출 순서를 소유하므로, 워커 경로에서도 불변식이 저절로 지켜진다.
2. **본문 비유출.** 로그·예외에 문서 본문·변환 결과·모델 응답을 담지 않는다. 남기는
   것은 변환 식별자·상태·실패 코드·소요 시간뿐이다.

실패 처리 정책: **도메인 예외만** 실패로 기록한다(failure_code = 예외 클래스명).
그 밖의 예외(DB 일시 장애 등)는 그대로 올려 arq가 재시도하게 둔다 — 조용히 "실패"로
확정하면 잠깐의 네트워크 문제로 변환이 영구 소실된다. 재시도까지 모두 실패하면 상태가
processing에 머무는데, 오래된 processing을 정리하는 잡은 후속 과제다.
"""

import logging
import time
import uuid
from collections.abc import Callable
from contextlib import AbstractAsyncContextManager
from typing import Any, Protocol

from app.exceptions import EasyDocError
from app.llm.provider import LLMProvider
from app.models.conversion import Conversion
from app.models.document import Document
from app.privacy.crypto import TextCipher
from app.services.conversion import ConversionService
from app.services.documents import serialize_masked_items

_logger = logging.getLogger(__name__)

#: LLM provider를 만들 수 없을 때(API 키 미설정) 남기는 실패 코드.
PROVIDER_UNAVAILABLE_CODE = "ProviderUnavailable"


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

    async def mark_processing(self, conversion: Conversion) -> None:
        """처리 시작을 기록한다."""
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


# ctx가 dict[str, Any]인 것은 arq가 정한 모양이라 좁힐 수 없다. 꺼낸 값은 즉시 지역
# 변수에 타입을 붙여 Any가 아래로 퍼지지 않게 한다.
async def convert_document(ctx: dict[str, Any], conversion_id: str) -> None:
    """변환 한 건을 수행한다 (arq 진입점).

    ctx에서 읽는 값은 `app/workers/settings.py`의 startup이 채운다:
    `store_scope`(StoreScope), `cipher`(TextCipher), `provider`(LLMProvider | None).

    Args:
        conversion_id: conversions.id의 문자열 표현. 큐에는 식별자만 실린다 —
            본문을 큐에 넣으면 Redis에 평문 개인정보가 쌓인다.
    """
    try:
        identifier = uuid.UUID(conversion_id)
    except ValueError:
        # 우리가 넣지 않은 값이 큐에 있다. 재시도해도 달라지지 않으므로 여기서 끝낸다.
        # 값 자체는 로그에 남기지 않는다(무엇이 들어올지 알 수 없다).
        _logger.error("변환 작업 식별자 형식이 올바르지 않습니다")
        return

    store_scope: StoreScope = ctx["store_scope"]
    cipher: TextCipher = ctx["cipher"]
    provider: LLMProvider | None = ctx["provider"]
    started = time.monotonic()

    async with store_scope() as store:
        loaded = await store.get_with_document(identifier)
        if loaded is None:
            # 보존 기간이 지나 삭제됐거나 계정이 사라진 경우 — 재시도할 일이 아니다.
            _logger.warning("변환 대상을 찾을 수 없습니다: conversion_id=%s", identifier)
            return
        conversion, document = loaded

        if provider is None:
            # 키 없이 워커가 떴다. 대기 상태로 방치하면 사용자는 끝나지 않는 진행 표시만 본다.
            await _fail(store, conversion, PROVIDER_UNAVAILABLE_CODE, started)
            return

        await store.mark_processing(conversion)
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
