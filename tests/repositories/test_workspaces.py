"""작업 공간 저장소 통합 테스트 — 실제 PostgreSQL이 필요하다.

DATABASE_URL이 없으면 conftest가 `db` 마커를 보고 자동으로 skip 한다.

여기서 보려는 것은 다섯 가지다: 소유자 격리(남의 작업 공간이 절대 보이지 않는다),
사용자 안에서의 이름 유일성, 기본 작업 공간("가장 오래된 것") 판정, 문서가 남은 공간을
DB가 지우지 못하게 막는 것, 그리고 계정 삭제가 작업 공간·문서를 함께 데려가는 것이다.
"""

import uuid

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import ConflictError
from app.models.user import User
from app.models.workspace import DEFAULT_WORKSPACE_NAME
from app.repositories.documents import DocumentRepository
from app.repositories.users import UserRepository
from app.repositories.workspaces import WorkspaceRepository

pytestmark = pytest.mark.db

_ENCRYPTED = b"gAAAAA-fake-fernet-token"


async def _user(session: AsyncSession, email: str) -> User:
    """작업 공간 소유자로 쓸 계정을 만든다 (작업 공간은 테스트가 직접 만든다)."""
    repository = UserRepository(session)
    user = await repository.create(email=email, password_hash="$argon2id$fake")
    await repository.commit()
    return user


async def _document(session: AsyncSession, user: User, workspace_id: uuid.UUID) -> uuid.UUID:
    """작업 공간에 문서 한 건을 넣는다."""
    documents = DocumentRepository(session)
    document = await documents.create(
        user_id=user.id,
        workspace_id=workspace_id,
        title="재난지원금 신청 안내",
        source_format="text",
        source_text_encrypted=_ENCRYPTED,
        char_count=12,
    )
    await documents.commit()
    return document.id


async def _age(session: AsyncSession, workspace_id: uuid.UUID, *, minutes: int) -> None:
    """작업 공간을 그만큼 과거에 만들어진 것으로 만든다.

    테스트 픽스처가 스위트를 트랜잭션 하나로 묶으므로 now()(트랜잭션 시각)가 고정되어
    created_at이 전부 같은 값이 된다. 실제 서비스에서는 요청마다 달라지는 값이라,
    "가장 오래된 것" 판정을 검증하려면 여기서 직접 벌려 줘야 한다.
    """
    await session.execute(
        text(
            "UPDATE workspaces SET created_at = created_at - make_interval(mins => :m)"
            " WHERE id = :id"
        ),
        {"m": minutes, "id": workspace_id},
    )


# --- 생성·조회 -----------------------------------------------------------------


async def test_작업_공간을_만들고_소유자로_조회한다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "ws-owner@example.com")
    repository = WorkspaceRepository(db_session)

    created = await repository.create(user_id=user.id, name=DEFAULT_WORKSPACE_NAME)
    await repository.commit()

    found = await repository.get_for_user(created.id, user.id)
    assert found is not None
    assert found.name == DEFAULT_WORKSPACE_NAME
    # timezone=True — "가장 오래된 것" 판정이 서버 타임존에 좌우되면 안 된다.
    assert found.created_at.tzinfo is not None


async def test_남의_작업_공간은_조회되지_않는다(db_session: AsyncSession) -> None:
    """소유자 격리 — "찾았지만 남의 것"이라는 상태를 만들지 않는다."""
    owner = await _user(db_session, "ws-mine@example.com")
    stranger = await _user(db_session, "ws-theirs@example.com")
    repository = WorkspaceRepository(db_session)
    workspace = await repository.create(user_id=owner.id, name="민원 안내")
    await repository.commit()

    assert await repository.get_for_user(workspace.id, stranger.id) is None
    assert await repository.get_for_user(uuid.uuid4(), owner.id) is None


async def test_같은_사용자_안에서_이름이_겹치면_409가_된다(db_session: AsyncSession) -> None:
    """DB unique 제약이 판정한다 — 조회-후-삽입 사이의 틈은 제약만이 닫는다."""
    user = await _user(db_session, "ws-dup@example.com")
    repository = WorkspaceRepository(db_session)
    await repository.create(user_id=user.id, name="민원 안내")
    await repository.commit()

    with pytest.raises(ConflictError) as error:
        await repository.create(user_id=user.id, name="민원 안내")

    # PostgreSQL DETAIL에는 실패한 행 전체(=이름)가 담긴다 — 예외에 매달리면 안 된다.
    assert error.value.__cause__ is None


async def test_다른_사용자는_같은_이름을_쓸_수_있다(db_session: AsyncSession) -> None:
    """이름 유일성의 범위는 사용자 한 명이다 — 남이 쓰는 이름 때문에 막히면 안 된다."""
    first = await _user(db_session, "ws-name-a@example.com")
    second = await _user(db_session, "ws-name-b@example.com")
    repository = WorkspaceRepository(db_session)

    await repository.create(user_id=first.id, name=DEFAULT_WORKSPACE_NAME)
    await repository.create(user_id=second.id, name=DEFAULT_WORKSPACE_NAME)
    await repository.commit()

    assert len(await repository.list_for_user(first.id)) == 1
    assert len(await repository.list_for_user(second.id)) == 1


# --- 목록·기본 작업 공간 ---------------------------------------------------------


async def test_목록은_만든_순서대로이고_문서_수를_함께_준다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "ws-list@example.com")
    repository = WorkspaceRepository(db_session)
    first = await repository.create(user_id=user.id, name=DEFAULT_WORKSPACE_NAME)
    second = await repository.create(user_id=user.id, name="민원 안내")
    await repository.commit()
    await _age(db_session, first.id, minutes=1)
    await _document(db_session, user, first.id)

    summaries = await repository.list_for_user(user.id)

    assert [summary.workspace.id for summary in summaries] == [first.id, second.id]
    assert [summary.document_count for summary in summaries] == [1, 0]


async def test_목록에_남의_작업_공간은_섞이지_않는다(db_session: AsyncSession) -> None:
    owner = await _user(db_session, "ws-list-mine@example.com")
    stranger = await _user(db_session, "ws-list-theirs@example.com")
    repository = WorkspaceRepository(db_session)
    mine = await repository.create(user_id=owner.id, name="내 공간")
    await repository.create(user_id=stranger.id, name="남의 공간")
    await repository.commit()

    summaries = await repository.list_for_user(owner.id)

    assert [summary.workspace.id for summary in summaries] == [mine.id]


async def test_기본_작업_공간은_가장_먼저_만든_것이다(db_session: AsyncSession) -> None:
    """가입 때 만든 공간이 늘 기본이 되어야 한다 — 뒤에 만든 공간이 끼어들면 안 된다."""
    user = await _user(db_session, "ws-default@example.com")
    repository = WorkspaceRepository(db_session)
    first = await repository.create(user_id=user.id, name=DEFAULT_WORKSPACE_NAME)
    await repository.create(user_id=user.id, name="민원 안내")
    await repository.commit()
    await _age(db_session, first.id, minutes=5)

    default = await repository.get_default_for_user(user.id)

    assert default is not None
    assert default.id == first.id


async def test_작업_공간이_없으면_기본도_없다(db_session: AsyncSession) -> None:
    """가입이 하나를 만들어 주므로 실제로는 나오지 않는 상태다 — 서비스가 5xx로 올린다."""
    user = await _user(db_session, "ws-none@example.com")

    assert await WorkspaceRepository(db_session).get_default_for_user(user.id) is None


# --- 이름 바꾸기 ---------------------------------------------------------------


async def test_이름을_바꾸면_바뀐_값을_돌려준다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "ws-rename@example.com")
    repository = WorkspaceRepository(db_session)
    workspace = await repository.create(user_id=user.id, name="민원 안내")
    await repository.commit()

    renamed = await repository.rename_for_user(workspace.id, user.id, name="복지 안내")
    await repository.commit()

    assert renamed is not None
    assert renamed.name == "복지 안내"
    reloaded = await repository.get_for_user(workspace.id, user.id)
    assert reloaded is not None
    assert reloaded.name == "복지 안내"


async def test_남의_작업_공간은_이름을_바꿀_수_없다(db_session: AsyncSession) -> None:
    """소유자 조건을 UPDATE의 WHERE에 함께 넣어 조회-후-수정 사이의 틈을 없앤다."""
    owner = await _user(db_session, "ws-rename-mine@example.com")
    stranger = await _user(db_session, "ws-rename-other@example.com")
    repository = WorkspaceRepository(db_session)
    workspace = await repository.create(user_id=owner.id, name="민원 안내")
    await repository.commit()

    assert await repository.rename_for_user(workspace.id, stranger.id, name="빼앗기") is None
    assert await repository.rename_for_user(uuid.uuid4(), owner.id, name="없는 것") is None

    reloaded = await repository.get_for_user(workspace.id, owner.id)
    assert reloaded is not None
    assert reloaded.name == "민원 안내"


async def test_이미_쓰는_이름으로는_바꿀_수_없다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "ws-rename-dup@example.com")
    repository = WorkspaceRepository(db_session)
    await repository.create(user_id=user.id, name="민원 안내")
    target = await repository.create(user_id=user.id, name="복지 안내")
    await repository.commit()

    with pytest.raises(ConflictError):
        await repository.rename_for_user(target.id, user.id, name="민원 안내")


# --- 삭제 ---------------------------------------------------------------------


async def test_빈_작업_공간은_지워진다(db_session: AsyncSession) -> None:
    user = await _user(db_session, "ws-delete@example.com")
    repository = WorkspaceRepository(db_session)
    workspace = await repository.create(user_id=user.id, name="비어 있는 공간")
    await repository.commit()

    assert await repository.delete_for_user(workspace.id, user.id) is True
    await repository.commit()

    assert await repository.get_for_user(workspace.id, user.id) is None


async def test_문서가_남은_작업_공간은_DB가_지우지_못하게_막는다(db_session: AsyncSession) -> None:
    """서비스가 앞에서 409로 막지만, 그 검사와 DELETE 사이의 틈은 FK만이 닫는다."""
    user = await _user(db_session, "ws-delete-busy@example.com")
    repository = WorkspaceRepository(db_session)
    workspace = await repository.create(user_id=user.id, name="문서가 있는 공간")
    await repository.commit()
    document_id = await _document(db_session, user, workspace.id)
    # 제약 위반 처리는 세션을 롤백한다 — 그 뒤에는 ORM 객체 속성 접근이 다시 DB를
    # 읽으려 하므로(만료 상태), 필요한 식별자는 미리 꺼내 둔다.
    workspace_id = workspace.id

    with pytest.raises(ConflictError) as error:
        await repository.delete_for_user(workspace_id, user.id)

    assert "먼저 비운" in str(error.value)
    # 문서도 작업 공간도 그대로 남아 있어야 한다 — 거절은 아무것도 지우지 않는다.
    remaining = await db_session.execute(
        text(
            "SELECT (SELECT count(*) FROM documents WHERE id = :document_id)"
            " + (SELECT count(*) FROM workspaces WHERE id = :workspace_id)"
        ),
        {"document_id": document_id, "workspace_id": workspace_id},
    )
    assert remaining.scalar_one() == 2


async def test_남의_작업_공간은_지워지지_않는다(db_session: AsyncSession) -> None:
    owner = await _user(db_session, "ws-delete-mine@example.com")
    stranger = await _user(db_session, "ws-delete-other@example.com")
    repository = WorkspaceRepository(db_session)
    workspace = await repository.create(user_id=owner.id, name="내 공간")
    await repository.commit()

    assert await repository.delete_for_user(workspace.id, stranger.id) is False
    assert await repository.delete_for_user(uuid.uuid4(), owner.id) is False

    assert await repository.get_for_user(workspace.id, owner.id) is not None


# --- 스키마 ------------------------------------------------------------------


async def test_계정을_지우면_작업_공간과_문서가_함께_사라진다(db_session: AsyncSession) -> None:
    """개인정보 삭제 요청이 무엇도 남기면 안 된다.

    documents.workspace_id는 NO ACTION이라 검사가 문장 끝으로 미뤄진다 — 캐스케이드가
    어느 순서로 돌든 그때는 문서도 이미 지워져 있다(RESTRICT였다면 실패했을 자리다).
    """
    user = await _user(db_session, "ws-erased@example.com")
    repository = WorkspaceRepository(db_session)
    workspace = await repository.create(user_id=user.id, name=DEFAULT_WORKSPACE_NAME)
    await repository.commit()
    document_id = await _document(db_session, user, workspace.id)

    await db_session.delete(user)
    await db_session.flush()

    # 세션 캐시(identity map)가 아니라 DB에 남아 있는지를 본다.
    remaining = await db_session.execute(
        text(
            "SELECT (SELECT count(*) FROM workspaces WHERE id = :workspace_id)"
            " + (SELECT count(*) FROM documents WHERE id = :document_id)"
        ),
        {"workspace_id": workspace.id, "document_id": document_id},
    )
    assert remaining.scalar_one() == 0


async def test_제약_이름이_naming_convention을_따른다(db_session: AsyncSession) -> None:
    """이름을 규칙으로 고정해야 나중에 제약을 바꾸는 마이그레이션을 손으로 안 쓴다.

    uq_workspaces_user_id_name은 규칙이 아니라 손으로 지은 이름이다 — 규칙(uq)은 첫
    컬럼만 담아 두 컬럼 제약이라는 사실을 숨긴다(app/models/workspace.py 참고).
    """
    result = await db_session.execute(
        text("SELECT conname FROM pg_constraint WHERE conrelid = 'workspaces'::regclass")
    )

    assert {
        "pk_workspaces",
        "uq_workspaces_user_id_name",
        "fk_workspaces_user_id_users",
    } <= set(result.scalars())


async def test_문서의_작업_공간_FK도_규칙을_따른다(db_session: AsyncSession) -> None:
    result = await db_session.execute(
        text("SELECT conname FROM pg_constraint WHERE conrelid = 'documents'::regclass")
    )

    assert "fk_documents_workspace_id_workspaces" in set(result.scalars())
