"""arq 변환 작업 테스트.

DB·Redis 없이 돈다 — 저장소는 대역, LLM은 FakeProvider, 세션 스코프는 그냥 대역을
돌려주는 컨텍스트 매니저다. 검증 대상은 세 가지다: 상태 전이, 결과 암호화 저장,
그리고 **원문이 마스킹을 거치지 않고 LLM에 닿지 않는 것**.
"""

import asyncio
import logging
import uuid
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from datetime import UTC, datetime
from typing import Any

import pytest
from arq.worker import Retry
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

_SOURCE = "홍길동 님(900101-1234567)의 신청이 접수되었습니다."
_EASY = "홍길동 님, 신청을 받았어요. 등록번호는 [[주민등록번호1]]입니다."


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
    assert b"900101-1234567" not in conversion.masked_items_encrypted
    items = deserialize_masked_items(cipher.decrypt(conversion.masked_items_encrypted))
    assert [(item.category, item.original) for item in items] == [
        ("주민등록번호", "900101-1234567")
    ]


async def test_결과에서_사라진_자리표시자를_기록한다(cipher: TextCipher) -> None:
    """모델이 자리표시자를 지우면 검수 화면이 경고할 수 있어야 한다."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(
        _ctx(store, cipher, FakeProvider(responses=["등록번호가 사라진 쉬운 글."])),
        str(conversion.id),
    )

    assert conversion.missing_placeholders == ["[[주민등록번호1]]"]


async def test_원문은_마스킹을_거쳐서만_LLM에_전달된다(cipher: TextCipher) -> None:
    """워커 경로에서도 마스킹 선행 불변식이 유지되는지 본다 (master-plan 3.2)."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))
    provider = FakeProvider(responses=[_EASY])

    await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    sent = provider.calls[0].user
    assert "900101-1234567" not in sent
    assert "[[주민등록번호1]]" in sent


async def test_처리_시작을_먼저_확정한다(cipher: TextCipher) -> None:
    """변환은 수 초 걸린다 — processing을 커밋해야 조회 API가 진행 상태를 보여준다."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document))

    await convert_document(_ctx(store, cipher, FakeProvider(responses=[_EASY])), str(conversion.id))

    assert store.journal == ["processing", "commit", "done", "commit"]


# --- 멱등성 (재시도·중복 배달) --------------------------------------------------


async def test_이미_완료된_변환은_다시_처리하지_않는다(cipher: TextCipher) -> None:
    """재시도가 done 결과를 덮어쓰면 LLM 비용을 두 번 치르고 결과가 바뀐다."""
    conversion, document = _pair(cipher)
    conversion.status = ConversionStatus.DONE
    conversion.easy_text_encrypted = cipher.encrypt("이미 만들어 둔 결과")
    store = FakeConversionWorkerStore((conversion, document))
    provider = FakeProvider(responses=[_EASY])

    await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    # LLM을 아예 부르지 않는다 — 중복 과금 방지의 핵심 단언이다.
    assert provider.calls == []
    assert store.journal == []
    assert cipher.decrypt(conversion.easy_text_encrypted) == "이미 만들어 둔 결과"


async def test_중단된_작업을_다시_돌려도_결과는_한_번만_남는다(cipher: TextCipher) -> None:
    """워커가 SIGTERM으로 죽으면 arq는 CancelledError로 보고 그 작업을 다시 돌린다.

    첫 시도는 결과 저장 직전에 끊기고(BaseException이라 우리 except를 지나쳐 arq의
    재큐 경로로 간다), 두 번째 시도가 처음부터 다시 도는 상황이다.
    """
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document), done_error=asyncio.CancelledError())
    first = FakeProvider(responses=[_EASY])

    with pytest.raises(asyncio.CancelledError):
        await convert_document(_ctx(store, cipher, first), str(conversion.id))

    # 결과가 저장되지 못했으니 재시도는 실제로 다시 변환해야 한다.
    assert conversion.status == ConversionStatus.PROCESSING
    store.done_error = None
    second = FakeProvider(responses=[_EASY])
    await convert_document(_ctx(store, cipher, second), str(conversion.id))

    assert conversion.status == ConversionStatus.DONE
    assert len(second.calls) == 1
    assert conversion.easy_text_encrypted is not None
    assert cipher.decrypt(conversion.easy_text_encrypted) == _EASY

    # 세 번째 배달(중복)은 이미 done이므로 LLM을 부르지 않는다.
    third = FakeProvider(responses=[_EASY])
    await convert_document(_ctx(store, cipher, third), str(conversion.id))
    assert third.calls == []


async def test_다른_워커가_먼저_끝냈으면_물러난다(cipher: TextCipher) -> None:
    """읽은 뒤 선점 전에 다른 워커가 완료시킨 경우 — 조건부 UPDATE가 유일한 판정이다."""
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document), preempted=True)
    provider = FakeProvider(responses=[_EASY])

    await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    assert store.journal == ["processing"]
    assert provider.calls == []


# --- 예상 못 한 오류 → 재시도 ----------------------------------------------------


async def test_결과_저장_중_DB_장애는_재시도로_바뀐다(cipher: TextCipher) -> None:
    """arq는 일반 예외를 재시도하지 않고 작업을 영구히 버린다 (Worker.run_job).

    그대로 두면 이미 비용을 치른 LLM 결과가 사라지고 행은 processing에 굳는다.
    """
    conversion, document = _pair(cipher)
    store = FakeConversionWorkerStore((conversion, document), done_error=OSError("db down"))
    provider = FakeProvider(responses=[_EASY])

    with pytest.raises(Retry):
        await convert_document(_ctx(store, cipher, provider), str(conversion.id))

    # 변환은 실제로 일어났고(비용 발생), 저장만 실패했다 — 재시도가 유일한 구제책이다.
    assert len(provider.calls) == 1
    assert conversion.status == ConversionStatus.PROCESSING


async def test_조회_중_오류도_재시도로_바뀐다(cipher: TextCipher) -> None:
    store = FakeConversionWorkerStore(load_error=OSError("connection reset"))

    with pytest.raises(Retry):
        await convert_document(_ctx(store, cipher, FakeProvider(responses=[])), str(uuid.uuid4()))


async def test_재시도_예약_로그에_예외_메시지를_남기지_않는다(
    cipher: TextCipher, caplog: pytest.LogCaptureFixture
) -> None:
    """DB 예외 메시지에는 SQL 파라미터(암호문·제목)가, Redis 예외에는 접속 URL이 섞인다."""
    store = FakeConversionWorkerStore(load_error=OSError("host=db user=postgres password=hunter2"))

    with (
        caplog.at_level(logging.DEBUG, logger="app.workers.tasks"),
        pytest.raises(Retry),
    ):
        await convert_document(_ctx(store, cipher, None), str(uuid.uuid4()))

    assert "OSError" in caplog.text
    assert "hunter2" not in caplog.text


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
    assert "900101-1234567" not in caplog.text
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
