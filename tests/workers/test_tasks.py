"""arq 변환 작업 테스트.

DB·Redis 없이 돈다 — 저장소는 대역, LLM은 FakeProvider, 세션 스코프는 그냥 대역을
돌려주는 컨텍스트 매니저다. 검증 대상은 세 가지다: 상태 전이, 결과 암호화 저장,
그리고 **원문이 마스킹을 거치지 않고 LLM에 닿지 않는 것**.
"""

import logging
import uuid
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from datetime import UTC, datetime
from typing import Any

import pytest
from cryptography.fernet import Fernet

from app.exceptions import LLMProviderError
from app.llm.fake import FakeProvider
from app.llm.provider import (
    DEFAULT_MAX_TOKENS,
    DEFAULT_TEMPERATURE,
    LLMProvider,
    LLMResponse,
)
from app.models.conversion import Conversion, ConversionStatus
from app.models.document import Document
from app.privacy.crypto import TextCipher
from app.services.documents import deserialize_masked_items
from app.workers.tasks import PROVIDER_UNAVAILABLE_CODE, ConversionWorkerStore, convert_document
from tests.fakes import FakeConversionWorkerStore

_SOURCE = "홍길동 님(010-1234-5678)의 신청이 접수되었습니다."
_EASY = "홍길동 님, 신청이 접수되었어요. 연락처는 [[전화번호1]]입니다."


class _TruncatedProvider(LLMProvider):
    """출력 한도에서 잘린 응답을 흉내 내는 대역.

    FakeProvider는 문자열만 받아 truncated를 표현할 수 없다. 절단 판정은
    ConversionService가 하므로, 워커가 그 판정 결과를 어떻게 기록하는지 보려면
    truncated=True인 응답이 실제로 필요하다.
    """

    name = "stub"

    async def complete(
        self,
        *,
        system: str,
        user: str,
        max_tokens: int = DEFAULT_MAX_TOKENS,
        temperature: float = DEFAULT_TEMPERATURE,
    ) -> LLMResponse:
        return LLMResponse(text="쉬운 글이 도중에", model="stub", truncated=True)


@pytest.fixture
def cipher() -> TextCipher:
    """테스트마다 새 키로 만든 암호기."""
    return TextCipher(Fernet.generate_key().decode())


def _pair(cipher: TextCipher) -> tuple[Conversion, Document]:
    """워커가 읽어 갈 대기 상태 변환과 원본 문서를 만든다."""
    now = datetime.now(UTC)
    document = Document(
        id=uuid.uuid4(),
        user_id=uuid.uuid4(),
        title="신청 접수 안내",
        source_format="text",
        source_text_encrypted=cipher.encrypt(_SOURCE),
        char_count=len(_SOURCE),
        retention_expires_at=now,
        created_at=now,
    )
    conversion = Conversion(
        id=uuid.uuid4(),
        document_id=document.id,
        status=ConversionStatus.PENDING,
        missing_placeholders=[],
        created_at=now,
        updated_at=now,
    )
    return conversion, document


def _scope(
    store: ConversionWorkerStore,
) -> Callable[[], AbstractAsyncContextManager[ConversionWorkerStore]]:
    """대역 저장소를 그대로 내주는 세션 스코프."""

    @asynccontextmanager
    async def scope() -> AsyncIterator[ConversionWorkerStore]:
        yield store

    return scope


def _ctx(
    store: ConversionWorkerStore, cipher: TextCipher, provider: LLMProvider | None
) -> dict[str, Any]:
    """arq가 넘겨주는 컨텍스트를 흉내 낸다 (startup이 채우는 것과 같은 키)."""
    return {"store_scope": _scope(store), "cipher": cipher, "provider": provider}


# --- 성공 경로 -----------------------------------------------------------------


async def test_변환에_성공하면_결과를_암호화해_저장한다(cipher: TextCipher) -> None:
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))
    provider = FakeProvider(responses=[_EASY])

    await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    assert conversion.status == ConversionStatus.DONE
    assert conversion.easy_text_encrypted is not None
    assert cipher.decrypt(conversion.easy_text_encrypted) == _EASY
    assert (conversion.provider_name, conversion.model) == ("fake", "fake")
    assert conversion.failure_code is None


async def test_마스킹_항목도_암호화해_저장한다(cipher: TextCipher) -> None:
    """masked_items에는 가려졌던 원문 개인정보가 그대로 들어 있다."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(_ctx(store, cipher, FakeProvider(responses=[_EASY])), str(conversion.id))

    assert conversion.masked_items_encrypted is not None
    assert b"010-1234-5678" not in conversion.masked_items_encrypted
    items = deserialize_masked_items(cipher.decrypt(conversion.masked_items_encrypted))
    assert [(item.category, item.original) for item in items] == [("전화번호", "010-1234-5678")]


async def test_결과에서_사라진_자리표시자를_기록한다(cipher: TextCipher) -> None:
    """모델이 자리표시자를 지우면 검수 화면이 경고할 수 있어야 한다."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(
        _ctx(store, cipher, FakeProvider(responses=["연락처가 사라진 쉬운 글."])),
        str(conversion.id),
    )

    assert conversion.missing_placeholders == ["[[전화번호1]]"]


async def test_원문은_마스킹을_거쳐서만_LLM에_전달된다(cipher: TextCipher) -> None:
    """워커 경로에서도 마스킹 선행 불변식이 유지되는지 본다 (master-plan 3.2)."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))
    provider = FakeProvider(responses=[_EASY])

    await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    sent = provider.calls[0].user
    assert "010-1234-5678" not in sent
    assert "[[전화번호1]]" in sent


async def test_처리_시작을_먼저_확정한다(cipher: TextCipher) -> None:
    """변환은 수 초 걸린다 — processing을 커밋해야 조회 API가 진행 상태를 보여준다."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(_ctx(store, cipher, FakeProvider(responses=[_EASY])), str(conversion.id))

    assert store.journal == ["processing", "commit", "done", "commit"]


# --- 실패 경로 -----------------------------------------------------------------


async def test_절단된_응답은_실패로_기록된다(cipher: TextCipher) -> None:
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(_ctx(store, cipher, _TruncatedProvider()), str(conversion.id))

    assert conversion.status == ConversionStatus.FAILED
    assert conversion.failure_code == "LLMTruncatedError"
    assert conversion.easy_text_encrypted is None
    assert store.journal == ["processing", "commit", "failed", "commit"]


async def test_빈_결과는_실패로_기록된다(cipher: TextCipher) -> None:
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(_ctx(store, cipher, FakeProvider(responses=["   "])), str(conversion.id))

    assert conversion.failure_code == "LLMEmptyResultError"


async def test_provider_호출_실패는_실패로_기록된다(cipher: TextCipher) -> None:
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))
    provider = FakeProvider(responses=[LLMProviderError("anthropic 호출 실패 (HTTP 500)")])

    await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    assert conversion.failure_code == "LLMProviderError"


async def test_API_키가_없으면_처리하지_않고_실패로_남긴다(cipher: TextCipher) -> None:
    """대기 상태로 방치하면 사용자는 끝나지 않는 진행 표시만 본다."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(_ctx(store, cipher, None), str(conversion.id))

    assert conversion.status == ConversionStatus.FAILED
    assert conversion.failure_code == PROVIDER_UNAVAILABLE_CODE
    # 처리를 시작하지도 않았다.
    assert store.journal == ["failed", "commit"]


async def test_복호화할_수_없는_원문은_실패로_기록된다(cipher: TextCipher) -> None:
    """키를 교체한 뒤 옛 문서가 남은 상황 — 조용히 빈 본문을 변환하면 안 된다."""
    conversion, document = _pair(TextCipher(Fernet.generate_key().decode()))
    store = FakeConversionWorkerStore((conversion, document))
    provider = FakeProvider(responses=[_EASY])

    await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    assert conversion.failure_code == "StorageError"
    assert provider.calls == []


# --- 잘못된 입력 ---------------------------------------------------------------


async def test_없는_변환이면_아무_일도_하지_않는다(cipher: TextCipher) -> None:
    store = FakeConversionWorkerStore()

    await convert_document(_ctx(store, cipher, FakeProvider(responses=[])), str(uuid.uuid4()))

    assert store.journal == []


async def test_식별자_형식이_틀리면_아무_일도_하지_않는다(cipher: TextCipher) -> None:
    """우리가 넣지 않은 값이 큐에 있는 경우 — 재시도해도 달라지지 않는다."""
    store = FakeConversionWorkerStore()

    await convert_document(_ctx(store, cipher, FakeProvider(responses=[])), "not-a-uuid")

    assert store.journal == []


# --- 로그 ---------------------------------------------------------------------


async def test_로그에_문서_본문이_남지_않는다(
    cipher: TextCipher, caplog: pytest.LogCaptureFixture
) -> None:
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    with caplog.at_level(logging.DEBUG, logger="app.workers.tasks"):
        await convert_document(
            _ctx(store, cipher, FakeProvider(responses=[_EASY])), str(conversion.id)
        )

    assert "홍길동" not in caplog.text
    assert "010-1234-5678" not in caplog.text
    # 진단에 필요한 식별자·소요 시간은 남아야 한다.
    assert str(conversion.id) in caplog.text
    assert "elapsed_ms" in caplog.text


async def test_실패_로그에도_사유_코드만_남는다(
    cipher: TextCipher, caplog: pytest.LogCaptureFixture
) -> None:
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))
    provider = FakeProvider(responses=[LLMProviderError("본문 조각이 섞인 메시지")])

    with caplog.at_level(logging.DEBUG, logger="app.workers.tasks"):
        await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    assert "LLMProviderError" in caplog.text
    # 예외 메시지는 무엇이 담길지 알 수 없다 — 타입 이름만 남긴다.
    assert "본문 조각이 섞인 메시지" not in caplog.text
