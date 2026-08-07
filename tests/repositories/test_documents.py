"""문서·변환 저장소 통합 테스트 — 실제 PostgreSQL이 필요하다.

DATABASE_URL이 없으면 conftest가 `db` 마커를 보고 자동으로 skip 한다.

여기서 보려는 것은 세 가지다: 소유자 격리(남의 문서가 절대 보이지 않는다), 상태
전이(pending → processing → done/failed), 그리고 30일 보존 기본값이다.
"""

import uuid
from datetime import timedelta

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import StorageError
from app.models.conversion import Conversion, ConversionStatus
from app.models.document import RETENTION_DAYS, Document
from app.models.user import User
from app.privacy.crypto import CURRENT_KEY_VERSION
from app.repositories.conversions import ConversionRepository
from app.repositories.documents import DocumentRepository
from app.repositories.users import UserRepository

pytestmark = pytest.mark.db

_ENCRYPTED = b"gAAAAA-fake-fernet-token"
_TITLE = "재난지원금 신청 안내"


async def _user(session: AsyncSession, email: str) -> User:
    """문서 소유자로 쓸 계정을 만든다."""
    repository = UserRepository(session)
    user = await repository.create(email=email, password_hash="$argon2id$fake")
    await repository.commit()
    return user


async def _document(
    repository: DocumentRepository, user: User, *, title: str = _TITLE, char_count: int = 12
) -> Document:
    """문서를 저장하고 확정한다 (서비스가 하는 순서 그대로)."""
    document = await repository.create(
        user_id=user.id,
        title=title,
        source_format="text",
        source_text_encrypted=_ENCRYPTED,
        char_count=char_count,
    )
    await repository.commit()
    return document


# --- 문서 저장·조회 -------------------------------------------------------------


async def test_문서를_저장하고_소유자로_조회한다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "owner@example.com")
    repository = DocumentRepository(db_session)

    created = await _document(repository, user, char_count=42)
    found = await repository.get_for_user(created.id, user.id)

    assert found is not None
    assert found.title == _TITLE
    assert found.source_format == "text"
    assert found.char_count == 42
    # bytea 왕복 — 암호문이 인코딩 변환으로 훼손되면 복호화가 통째로 깨진다.
    assert found.source_text_encrypted == _ENCRYPTED
    # 키 세대 — Fernet 토큰에는 키 식별자가 없어 이 컬럼이 키 교체의 유일한 단서다.
    assert found.key_version == CURRENT_KEY_VERSION


async def test_남의_문서는_조회되지_않는다(db_session: AsyncSession) -> None:
    """소유자 격리 — "찾았지만 남의 것"이라는 상태를 만들지 않는다."""
    owner = await _user(db_session, "owner2@example.com")
    stranger = await _user(db_session, "stranger@example.com")
    repository = DocumentRepository(db_session)
    document = await _document(repository, owner)

    assert await repository.get_for_user(document.id, stranger.id) is None
    assert await repository.get_for_user(uuid.uuid4(), owner.id) is None


async def test_보존_만료가_기본_30일_뒤로_설정된다(db_session: AsyncSession) -> None:
    """자동 삭제 정책(master-plan 3.2)의 판단 기준이 저장 시점에 못박혀야 한다."""
    user = await _user(db_session, "retention@example.com")
    repository = DocumentRepository(db_session)

    document = await _document(repository, user)

    await db_session.refresh(document)
    assert document.retention_expires_at.tzinfo is not None
    # created_at·retention_expires_at 모두 같은 트랜잭션의 now()라 차이가 정확히 떨어진다.
    assert document.retention_expires_at - document.created_at == timedelta(days=RETENTION_DAYS)


async def test_없는_사용자의_문서는_저장되지_않는다(db_session: AsyncSession) -> None:
    """FK 위반은 입력 문제가 아니라 코드 버그다 — 4xx가 아니라 5xx가 되는 예외로 올린다."""
    repository = DocumentRepository(db_session)

    with pytest.raises(StorageError) as error:
        await repository.create(
            user_id=uuid.uuid4(),
            title=_TITLE,
            source_format="text",
            source_text_encrypted=_ENCRYPTED,
            char_count=1,
        )

    # PostgreSQL DETAIL에는 실패한 행 전체가 담긴다 — 예외에 매달리면 안 된다.
    assert error.value.__cause__ is None
    assert _TITLE not in str(error.value)


# --- 변환 생성·소유자 격리 -------------------------------------------------------


async def test_변환을_대기_상태로_만든다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "pending@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)

    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    assert conversion.status == ConversionStatus.PENDING
    assert conversion.missing_placeholders == []
    assert conversion.easy_text_encrypted is None
    assert conversion.failure_code is None
    assert conversion.key_version == CURRENT_KEY_VERSION


async def test_남의_변환은_조회되지_않는다(db_session: AsyncSession) -> None:
    """conversions에는 user_id가 없다 — documents 조인으로 소유자를 확인해야 한다."""
    owner = await _user(db_session, "cowner@example.com")
    stranger = await _user(db_session, "cstranger@example.com")
    document = await _document(DocumentRepository(db_session), owner)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    assert await conversions.get_for_user(conversion.id, owner.id) is not None
    assert await conversions.get_for_user(conversion.id, stranger.id) is None
    assert await conversions.get_for_user(uuid.uuid4(), owner.id) is None


async def test_내보내기는_소유자_확인과_함께_문서를_읽는다(db_session: AsyncSession) -> None:
    """내보내기는 문서 제목이 필요하다 — 워커용 조회(소유자 검증 없음)를 쓰면 안 된다."""
    owner = await _user(db_session, "exporter@example.com")
    stranger = await _user(db_session, "notmine@example.com")
    document = await _document(DocumentRepository(db_session), owner, title="복지 안내")
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    loaded = await conversions.get_for_user_with_document(conversion.id, owner.id)

    assert loaded is not None
    assert loaded[0].id == conversion.id
    assert loaded[1].title == "복지 안내"
    assert await conversions.get_for_user_with_document(conversion.id, stranger.id) is None
    assert await conversions.get_for_user_with_document(uuid.uuid4(), owner.id) is None


async def test_워커는_변환과_문서를_함께_읽는다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "worker@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    loaded = await conversions.get_with_document(conversion.id)

    assert loaded is not None
    assert loaded[0].id == conversion.id
    assert loaded[1].source_text_encrypted == _ENCRYPTED
    assert await conversions.get_with_document(uuid.uuid4()) is None


# --- 상태 전이 -----------------------------------------------------------------


async def test_처리_시작에서_완료까지_상태가_이어진다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "done@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)

    assert await conversions.mark_processing(conversion) is True
    assert conversion.status == ConversionStatus.PROCESSING

    await conversions.mark_done(
        conversion,
        easy_text_encrypted=b"gAAAAA-easy",
        masked_items_encrypted=b"gAAAAA-items",
        missing_placeholders=["[[전화번호1]]"],
        provider_name="fake",
        model="fake-model",
        input_tokens=11,
        output_tokens=22,
    )
    await conversions.commit()

    reloaded = await conversions.get_for_user(conversion.id, user.id)
    assert reloaded is not None
    assert reloaded.status == ConversionStatus.DONE
    assert reloaded.easy_text_encrypted == b"gAAAAA-easy"
    assert reloaded.masked_items_encrypted == b"gAAAAA-items"
    # JSONB 왕복 — 리스트가 문자열로 눌리면 검수 화면 경고가 깨진다.
    assert reloaded.missing_placeholders == ["[[전화번호1]]"]
    assert (reloaded.input_tokens, reloaded.output_tokens) == (11, 22)
    assert reloaded.failure_code is None


async def test_실패는_사유_코드만_남긴다(db_session: AsyncSession) -> None:
    """failure_code는 API 응답으로 그대로 나간다 — 본문·메시지가 섞이면 안 된다."""
    user = await _user(db_session, "failed@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)

    await conversions.mark_failed(conversion, "LLMTruncatedError")
    await conversions.commit()

    reloaded = await conversions.get_for_user(conversion.id, user.id)
    assert reloaded is not None
    assert reloaded.status == ConversionStatus.FAILED
    assert reloaded.failure_code == "LLMTruncatedError"
    assert reloaded.easy_text_encrypted is None


async def test_완료된_변환은_다시_처리_상태로_돌아가지_않는다(db_session: AsyncSession) -> None:
    """재시도·중복 배달로 워커가 같은 작업을 또 집어도 완료 결과를 덮어쓰면 안 된다.

    읽고-나서-쓰는 방식은 그 사이에 틈이 있다 — 조건부 UPDATE만이 틈 없는 판정이다.
    """
    user = await _user(db_session, "idempotent@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.mark_done(
        conversion,
        easy_text_encrypted=b"gAAAAA-easy",
        masked_items_encrypted=b"gAAAAA-items",
        missing_placeholders=[],
        provider_name="fake",
        model="fake-model",
        input_tokens=1,
        output_tokens=2,
    )
    await conversions.commit()

    assert await conversions.mark_processing(conversion) is False

    reloaded = await conversions.get_for_user(conversion.id, user.id)
    assert reloaded is not None
    assert reloaded.status == ConversionStatus.DONE
    assert reloaded.easy_text_encrypted == b"gAAAAA-easy"


async def test_검수_수정본은_초안을_남겨둔_채_저장된다(db_session: AsyncSession) -> None:
    """AI 초안은 수정률 KPI(master-plan 7장)의 기준선이라 덮어쓰면 되살릴 수 없다."""
    user = await _user(db_session, "review@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.mark_done(
        conversion,
        easy_text_encrypted=b"gAAAAA-easy",
        masked_items_encrypted=b"gAAAAA-items",
        missing_placeholders=[],
        provider_name="fake",
        model="fake-model",
        input_tokens=1,
        output_tokens=2,
    )
    await conversions.commit()

    assert await conversions.save_review(conversion, edited_text_encrypted=b"gAAAAA-edited") is True
    assert (
        await conversions.save_review(conversion, edited_text_encrypted=b"gAAAAA-edited-2") is True
    )
    await conversions.commit()

    reloaded = await conversions.get_for_user(conversion.id, user.id)
    assert reloaded is not None
    # 재수정은 덮어쓰되 초안은 그대로다.
    assert reloaded.edited_text_encrypted == b"gAAAAA-edited-2"
    assert reloaded.easy_text_encrypted == b"gAAAAA-easy"
    assert reloaded.status == ConversionStatus.DONE
    # 검수 시각은 DB 시계로 찍는다(집계 기준이 서버 시계마다 달라지면 안 된다).
    assert reloaded.reviewed_at is not None
    assert reloaded.reviewed_at.tzinfo is not None


async def test_완료되지_않은_변환에는_수정본이_저장되지_않는다(db_session: AsyncSession) -> None:
    """상태 검사를 UPDATE의 WHERE에 넣어 읽고-나서-쓰는 사이의 틈을 닫는다.

    서비스가 앞에서 한 번 걸러내지만, 그 사이에 워커가 상태를 바꿀 수 있다 —
    판정은 DB가 한 번에 해야 한다 (mark_processing과 같은 규칙).
    """
    user = await _user(db_session, "racing@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    assert await conversions.save_review(conversion, edited_text_encrypted=b"gAAAAA-x") is False

    reloaded = await conversions.get_for_user(conversion.id, user.id)
    assert reloaded is not None
    assert reloaded.edited_text_encrypted is None
    assert reloaded.reviewed_at is None


async def test_검수_전_변환에는_수정본_컬럼이_비어_있다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "unreviewed@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)

    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    assert conversion.edited_text_encrypted is None
    assert conversion.reviewed_at is None


async def test_재시도는_이전_실패_사유를_지운다(db_session: AsyncSession) -> None:
    """실패 후 재시도로 처리 중이 됐는데 실패 코드가 남아 있으면 응답이 앞뒤가 안 맞는다."""
    user = await _user(db_session, "retry@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.mark_failed(conversion, "LLMProviderError")
    await conversions.commit()

    assert await conversions.mark_processing(conversion) is True

    assert conversion.status == ConversionStatus.PROCESSING
    assert conversion.failure_code is None


async def test_상태를_바꾸면_updated_at을_다시_쓴다(db_session: AsyncSession) -> None:
    """onupdate 배선 확인 — UPDATE 문이 updated_at을 함께 실어 보내야 한다.

    값이 **커지는지**는 여기서 볼 수 없다. 테스트 픽스처가 전체를 트랜잭션 하나로
    묶으므로 now()(트랜잭션 시각)가 테스트 내내 고정된다. 배선이 빠지면 SQLAlchemy가
    updated_at을 UPDATE에서 아예 빼버리고, 그러면 갱신 이력이 영원히 생성 시각에 머문다.
    """
    user = await _user(db_session, "touched@example.com")
    document = await _document(DocumentRepository(db_session), user)
    conversions = ConversionRepository(db_session)
    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    await db_session.execute(
        text("UPDATE conversions SET updated_at = created_at - interval '1 hour' WHERE id = :id"),
        {"id": conversion.id},
    )
    assert await conversions.mark_processing(conversion) is True
    await conversions.commit()

    await db_session.refresh(conversion)
    assert conversion.updated_at == conversion.created_at
    assert conversion.updated_at.tzinfo is not None


async def test_정의되지_않은_상태는_DB가_거부한다(db_session: AsyncSession) -> None:
    """CHECK 제약이 애플리케이션 밖 경로(운영 SQL·수동 복구)까지 막는다."""
    user = await _user(db_session, "check@example.com")
    document = await _document(DocumentRepository(db_session), user)

    db_session.add(Conversion(document_id=document.id, status="완료됨"))
    with pytest.raises(IntegrityError):
        await db_session.flush()
    await db_session.rollback()


# --- 목록 ---------------------------------------------------------------------


async def _age(session: AsyncSession, document: Document, *, minutes: int) -> None:
    """문서를 그만큼 과거에 만들어진 것으로 만든다.

    테스트 픽스처가 스위트 전체를 트랜잭션 하나로 묶으므로 now()(트랜잭션 시각)가
    고정되어 created_at이 전부 같은 값이 된다. 실제 서비스에서는 요청마다 달라지는
    값이라, 정렬을 검증하려면 여기서 직접 벌려 줘야 한다.
    """
    await session.execute(
        text(
            "UPDATE documents SET created_at = created_at - make_interval(mins => :m)"
            " WHERE id = :id"
        ),
        {"m": minutes, "id": document.id},
    )


async def test_목록은_최신순이고_최신_변환_상태를_함께_준다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "list@example.com")
    documents = DocumentRepository(db_session)
    conversions = ConversionRepository(db_session)
    first = await _document(documents, user, title="첫 번째")
    await _age(db_session, first, minutes=1)
    second = await _document(documents, user, title="두 번째")
    conversion = await conversions.create_pending(second.id)
    await conversions.mark_done(
        conversion,
        easy_text_encrypted=b"gAAAAA-easy",
        masked_items_encrypted=b"gAAAAA-items",
        missing_placeholders=[],
        provider_name="fake",
        model="fake-model",
        input_tokens=1,
        output_tokens=2,
    )
    await conversions.commit()

    page = await documents.list_for_user(user.id, limit=20, offset=0)

    assert [summary.document.id for summary in page.items] == [second.id, first.id]
    assert page.items[0].latest_conversion is not None
    assert page.items[0].latest_conversion.status == ConversionStatus.DONE
    # 변환을 아직 만들지 않은 문서도 목록에는 나와야 한다.
    assert page.items[1].latest_conversion is None
    assert page.has_more is False


async def test_목록에_남의_문서는_섞이지_않는다(db_session: AsyncSession) -> None:
    owner = await _user(db_session, "mine@example.com")
    stranger = await _user(db_session, "theirs@example.com")
    documents = DocumentRepository(db_session)
    mine = await _document(documents, owner, title="내 문서")
    await _document(documents, stranger, title="남의 문서")

    page = await documents.list_for_user(owner.id, limit=20, offset=0)

    assert [summary.document.id for summary in page.items] == [mine.id]


async def test_목록은_limit과_offset을_따른다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "paged@example.com")
    documents = DocumentRepository(db_session)
    for index in range(3):
        document = await _document(documents, user, title=f"문서 {index}")
        await _age(db_session, document, minutes=3 - index)

    page = await documents.list_for_user(user.id, limit=2, offset=0)
    rest = await documents.list_for_user(user.id, limit=2, offset=2)

    assert [summary.document.title for summary in page.items] == ["문서 2", "문서 1"]
    assert [summary.document.title for summary in rest.items] == ["문서 0"]
    # 다음 페이지 유무는 한 건 더 읽어 판정한다 (COUNT 쿼리 없이).
    assert page.has_more is True
    assert rest.has_more is False


async def test_문서가_없으면_빈_목록이다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "empty@example.com")

    page = await DocumentRepository(db_session).list_for_user(user.id, limit=20, offset=0)

    assert page.items == []
    assert page.has_more is False


# --- 스키마 ------------------------------------------------------------------


async def test_계정을_지우면_문서와_변환도_사라진다(db_session: AsyncSession) -> None:
    """개인정보 삭제 요청이 문서를 남겨두면 안 된다 (ondelete=CASCADE)."""
    user = await _user(db_session, "erased@example.com")
    documents = DocumentRepository(db_session)
    conversions = ConversionRepository(db_session)
    document = await _document(documents, user)
    conversion = await conversions.create_pending(document.id)
    await conversions.commit()

    await db_session.delete(user)
    await db_session.flush()

    # 세션 캐시(identity map)가 아니라 DB에 남아 있는지를 본다 — get()은 이미 읽어둔
    # 객체를 그대로 돌려주므로 삭제를 검증할 수 없다.
    remaining = await db_session.execute(
        text(
            "SELECT (SELECT count(*) FROM documents WHERE id = :document_id)"
            " + (SELECT count(*) FROM conversions WHERE id = :conversion_id)"
        ),
        {"document_id": document.id, "conversion_id": conversion.id},
    )
    assert remaining.scalar_one() == 0


async def test_제약_이름이_naming_convention을_따른다(db_session: AsyncSession) -> None:
    """이름을 규칙으로 고정해야 나중에 제약을 바꾸는 마이그레이션을 손으로 안 쓴다."""
    result = await db_session.execute(
        text("SELECT conname FROM pg_constraint WHERE conrelid = 'conversions'::regclass")
    )

    assert {
        "pk_conversions",
        "ck_conversions_status_valid",
        "fk_conversions_document_id_documents",
    } <= set(result.scalars())
