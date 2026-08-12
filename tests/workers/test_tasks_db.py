"""워커 통합 테스트 — 실제 PostgreSQL 저장소로 변환 작업을 돌린다.

대역 저장소 테스트(test_tasks.py)가 잡지 못하는 것을 본다: 조건부 UPDATE가 실제
SQL에서도 같은 판정을 내리는지, 그리고 워커가 쓴 값이 진짜로 DB에 암호문으로 남는지.
LLM만 FakeProvider로 대체한다.
"""

import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import pytest
from cryptography.fernet import Fernet
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.llm.fake import FakeProvider
from app.models.conversion import Conversion, ConversionStatus
from app.privacy.crypto import TextCipher
from app.repositories.conversions import ConversionRepository
from app.repositories.documents import DocumentRepository
from app.repositories.users import UserRepository
from app.repositories.workspaces import WorkspaceRepository
from app.services.documents import deserialize_masked_items
from app.workers.tasks import ConversionWorkerStore, convert_document

pytestmark = pytest.mark.db

_SOURCE = "홍길동 님(900101-1234567)의 신청이 접수되었습니다."
_EASY = "홍길동 님, 신청을 받았어요. 등록번호는 [[주민등록번호1]]입니다."


@pytest.fixture
def cipher() -> TextCipher:
    """테스트마다 새 키로 만든 암호기."""
    return TextCipher(Fernet.generate_key().decode())


async def _pending_conversion(session: AsyncSession, cipher: TextCipher) -> Conversion:
    """API가 만들어 두는 상태(문서 + 대기 변환)를 실제 저장소로 만든다."""
    users = UserRepository(session)
    user = await users.create(
        email=f"worker-{uuid.uuid4().hex[:8]}@example.com", password_hash="$argon2id$fake"
    )
    workspace = await WorkspaceRepository(session).create(user_id=user.id, name="기본 작업 공간")
    documents = DocumentRepository(session)
    document = await documents.create(
        user_id=user.id,
        workspace_id=workspace.id,
        title="신청 접수 안내",
        source_format="text",
        source_text_encrypted=cipher.encrypt(_SOURCE),
        char_count=len(_SOURCE),
    )
    conversions = ConversionRepository(session)
    conversion = await conversions.create_pending(document.id)
    await conversions.commit()
    return conversion


def _ctx(session: AsyncSession, cipher: TextCipher, provider: FakeProvider) -> dict[str, Any]:
    """실제 저장소를 쓰는 arq 컨텍스트. 세션은 테스트가 소유한다(롤백 격리)."""

    @asynccontextmanager
    async def scope() -> AsyncIterator[ConversionWorkerStore]:
        yield ConversionRepository(session)

    return {"store_scope": scope, "cipher": cipher, "provider": provider}


async def test_실제_저장소로_변환을_끝내면_암호문이_DB에_남는다(
    db_session: AsyncSession, cipher: TextCipher
) -> None:
    conversion = await _pending_conversion(db_session, cipher)
    provider = FakeProvider(responses=[_EASY])

    await convert_document(_ctx(db_session, cipher, provider), str(conversion.id))

    await db_session.refresh(conversion)
    assert conversion.status == ConversionStatus.DONE
    assert conversion.easy_text_encrypted is not None
    assert cipher.decrypt(conversion.easy_text_encrypted) == _EASY
    assert conversion.masked_items_encrypted is not None
    items = deserialize_masked_items(cipher.decrypt(conversion.masked_items_encrypted))
    assert [item.original for item in items] == ["900101-1234567"]
    assert conversion.failure_code is None

    # 컬럼을 직접 읽어 평문이 없는지 본다 — ORM을 거치면 복호화 여부를 알 수 없다.
    stored = await db_session.execute(
        text(
            "SELECT encode(easy_text_encrypted, 'escape') || ' '"
            " || encode(masked_items_encrypted, 'escape') FROM conversions WHERE id = :id"
        ),
        {"id": conversion.id},
    )
    blob = stored.scalar_one()
    assert "900101-1234567" not in blob
    assert "홍길동" not in blob


async def test_실제_저장소에서도_완료된_변환은_다시_처리하지_않는다(
    db_session: AsyncSession, cipher: TextCipher
) -> None:
    """조건부 UPDATE가 실제 SQL에서도 재처리를 막는지 본다 (중복 과금 방지)."""
    conversion = await _pending_conversion(db_session, cipher)
    await convert_document(
        _ctx(db_session, cipher, FakeProvider(responses=[_EASY])), str(conversion.id)
    )

    second = FakeProvider(responses=["다시 만든 결과"])
    await convert_document(_ctx(db_session, cipher, second), str(conversion.id))

    await db_session.refresh(conversion)
    assert second.calls == []
    assert conversion.easy_text_encrypted is not None
    assert cipher.decrypt(conversion.easy_text_encrypted) == _EASY


async def test_실제_저장소에서_도메인_예외는_실패로_기록된다(
    db_session: AsyncSession, cipher: TextCipher
) -> None:
    conversion = await _pending_conversion(db_session, cipher)

    await convert_document(
        _ctx(db_session, cipher, FakeProvider(responses=["   "])), str(conversion.id)
    )

    await db_session.refresh(conversion)
    assert conversion.status == ConversionStatus.FAILED
    assert conversion.failure_code == "LLMEmptyResultError"
