"""workspaces 테이블 접근.

documents 저장소와 같은 규약을 따른다: SQLAlchemy 예외를 밖으로 흘리지 않고, 커밋
시점은 정하지 않는다(트랜잭션 경계는 서비스가 소유한다 — app/services/workspaces.py).

소유자 조건은 언제나 WHERE에 함께 넣는다 — "찾았지만 남의 것"이라는 상태를 만들지
않으면 호출부가 소유자 검사를 빠뜨릴 수 없다(documents 저장소와 같은 규칙).
"""

import logging
import uuid
from dataclasses import dataclass

from sqlalchemy import delete, func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import ConflictError, StorageError
from app.models.document import Document
from app.models.workspace import Workspace

_logger = logging.getLogger(__name__)

#: PostgreSQL SQLSTATE. 여기서 갈라야 하는 위반이 둘이다 — 같은 이름(unique)과
#: 문서가 남은 작업 공간 삭제(FK). 둘 다 사용자가 조치할 수 있는 상황이라 409로 바꾼다.
_UNIQUE_VIOLATION = "23505"
_FOREIGN_KEY_VIOLATION = "23503"

_DUPLICATE_NAME_MESSAGE = "같은 이름의 작업 공간이 이미 있습니다"
_NOT_EMPTY_MESSAGE = "작업 공간에 문서가 남아 있습니다 — 먼저 비운 뒤 삭제해 주세요"


@dataclass(frozen=True)
class WorkspaceSummary:
    """작업 공간과 그 안의 문서 수.

    목록 화면이 작업 공간마다 문서 수를 다시 세지 않도록(N+1) 함께 돌려준다.
    삭제 가능 여부("먼저 비우세요") 판정도 이 값으로 한다.
    """

    workspace: Workspace
    document_count: int


class WorkspaceRepository:
    """작업 공간 저장소. 세션은 요청 단위로 주입받는다."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(self, *, user_id: uuid.UUID, name: str) -> Workspace:
        """작업 공간을 INSERT 한다 (flush까지만 — 확정은 호출자가 commit으로 한다).

        중복 이름 검사를 `SELECT` 선행이 아니라 unique 제약 위반 처리로 하는 이유는
        users 저장소와 같다 — 조회와 삽입 사이의 틈은 DB 제약만이 닫는다.

        Raises:
            ConflictError: 같은 사용자에게 같은 이름의 작업 공간이 이미 있다.
            StorageError: 그 밖의 제약을 위반했다 (입력 문제가 아니라 코드 버그다).
        """
        workspace = Workspace(user_id=user_id, name=name)
        self._session.add(workspace)
        try:
            await self._session.flush()
        except IntegrityError as exc:
            await self._session.rollback()
            raise self._translate(exc, unique_message=_DUPLICATE_NAME_MESSAGE) from None
        return workspace

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다. 호출 시점은 서비스가 정한다."""
        await self._session.commit()

    async def list_for_user(self, user_id: uuid.UUID) -> list[WorkspaceSummary]:
        """내 작업 공간을 만든 순서대로 돌려준다 (각 공간의 문서 수 포함).

        페이지네이션을 두지 않는 이유: 작업 공간은 사람이 손으로 만드는 몇 개짜리
        목록이고, 화면(머리말 드롭다운)이 늘 전부를 보여준다. 쪽을 나누면 "기본
        작업 공간이 목록에 없는" 상태가 만들어질 수 있다.

        정렬이 오래된 순인 이유: 첫 번째 항목이 곧 기본 작업 공간이다
        (`get_default_for_user`와 같은 기준을 써야 화면과 서버가 어긋나지 않는다).
        """
        result = await self._session.execute(
            select(Workspace, func.count(Document.id))
            # outer join: 문서가 하나도 없는 작업 공간도 목록에 나와야 한다.
            .outerjoin(Document, Document.workspace_id == Workspace.id)
            .where(Workspace.user_id == user_id)
            # id는 PK라 나머지 컬럼이 함수 종속이다 — PostgreSQL은 이 그룹화를 허용한다.
            .group_by(Workspace.id)
            .order_by(Workspace.created_at, Workspace.id)
        )
        return [
            WorkspaceSummary(workspace=workspace, document_count=count)
            for workspace, count in result
        ]

    async def get_for_user(self, workspace_id: uuid.UUID, user_id: uuid.UUID) -> Workspace | None:
        """소유자를 확인하며 작업 공간을 찾는다. 남의 것·없는 것 모두 None."""
        result = await self._session.execute(
            select(Workspace).where(Workspace.id == workspace_id, Workspace.user_id == user_id)
        )
        return result.scalar_one_or_none()

    async def get_default_for_user(self, user_id: uuid.UUID) -> Workspace | None:
        """기본 작업 공간(가장 먼저 만든 것)을 돌려준다. 하나도 없으면 None.

        "기본"을 따로 표시하는 컬럼을 두지 않는 이유: 표시 컬럼은 언제든 두 개가 켜지거나
        전부 꺼진 상태가 될 수 있고, 그 상태를 고칠 방법이 코드에 없다. 가입 때 만든
        공간이 늘 가장 오래된 것이고, 마지막 하나는 지울 수 없으므로(서비스 규칙) 이
        기준은 언제나 정확히 하나를 가리킨다.

        id를 정렬에 덧붙이는 이유는 documents 목록과 같다 — created_at은 트랜잭션 시각이라
        같은 요청에서 만들어진 행끼리 동률이 될 수 있다.
        """
        result = await self._session.execute(
            select(Workspace)
            .where(Workspace.user_id == user_id)
            .order_by(Workspace.created_at, Workspace.id)
            .limit(1)
        )
        return result.scalar_one_or_none()

    async def rename_for_user(
        self, workspace_id: uuid.UUID, user_id: uuid.UUID, *, name: str
    ) -> Workspace | None:
        """소유자를 확인하며 이름을 바꾼다 (flush까지만 — 확정은 호출자가 commit).

        소유자 조건을 UPDATE의 WHERE에 함께 넣어 조회-후-수정 사이의 틈을 없앤다
        (conversions 저장소의 조건부 UPDATE와 같은 규칙). 갱신 여부는 RETURNING으로 본다
        — rowcount는 드라이버마다 타입이 다르다.

        Returns:
            바꾼 작업 공간. None이면 없거나 내 것이 아니다(호출부가 404로 바꾼다).

        Raises:
            ConflictError: 같은 이름의 작업 공간이 이미 있다.
            StorageError: 그 밖의 제약을 위반했다.
        """
        try:
            result = await self._session.execute(
                update(Workspace)
                .where(Workspace.id == workspace_id, Workspace.user_id == user_id)
                .values(name=name)
                .returning(Workspace.id)
                .execution_options(synchronize_session=False)
            )
        except IntegrityError as exc:
            await self._session.rollback()
            raise self._translate(exc, unique_message=_DUPLICATE_NAME_MESSAGE) from None
        if result.scalar_one_or_none() is None:
            return None
        # UPDATE는 세션이 들고 있는 객체를 갱신하지 않는다 — 같은 세션이 이 행을 이미
        # 읽어 뒀다면 RETURNING으로 엔터티를 받아도 예전 이름 그대로다(식별자 맵이 이긴다).
        # populate_existing으로 다시 읽어 응답이 방금 저장한 값을 싣게 한다
        # (conversions 저장소가 refresh로 하는 일과 같다).
        return await self._session.get(Workspace, workspace_id, populate_existing=True)

    async def delete_for_user(self, workspace_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """소유자를 확인하며 작업 공간을 지운다 (flush까지만 — 확정은 호출자가 commit).

        문서가 남아 있으면 FK(NO ACTION)가 삭제를 거부한다. 서비스가 앞에서 문서 수를
        보고 409로 먼저 막지만, 그 검사와 DELETE 사이에 새 문서가 들어올 수 있다 —
        그 틈을 닫는 것은 DB뿐이므로 위반을 같은 뜻의 도메인 예외로 바꾼다.

        Returns:
            지웠으면 True. False면 없거나 내 것이 아니다(호출부가 404로 바꾼다).

        Raises:
            ConflictError: 작업 공간에 문서가 남아 있다.
            StorageError: 그 밖의 제약을 위반했다.
        """
        try:
            result = await self._session.execute(
                delete(Workspace)
                .where(Workspace.id == workspace_id, Workspace.user_id == user_id)
                # RETURNING으로 삭제 여부를 본다(rowcount는 드라이버마다 타입이 다르다) —
                # documents 저장소의 조건부 삭제와 같은 규칙이다.
                .returning(Workspace.id)
                .execution_options(synchronize_session=False)
            )
        except IntegrityError as exc:
            await self._session.rollback()
            raise self._translate(exc, foreign_key_message=_NOT_EMPTY_MESSAGE) from None
        return result.scalar_one_or_none() is not None

    def _translate(
        self,
        exc: IntegrityError,
        *,
        unique_message: str | None = None,
        foreign_key_message: str | None = None,
    ) -> Exception:
        """제약 위반을 도메인 예외로 바꾼다.

        어느 분기든 `from exc`로 원본을 매달지 않는다 — PostgreSQL은 제약 위반 DETAIL에
        실패한 행 전체(=작업 공간 이름)를 담고, 그 문자열은 SQLAlchemy의 hide_parameters로
        가려지지 않는다 (app/repositories/users.py 참고). 같은 이유로 예외 메시지·DETAIL은
        로그에도 남기지 않고 SQLSTATE와 제약 이름만 남긴다.
        """
        sqlstate = getattr(exc.orig, "sqlstate", None)
        if sqlstate == _UNIQUE_VIOLATION and unique_message is not None:
            return ConflictError(unique_message)
        if sqlstate == _FOREIGN_KEY_VIOLATION and foreign_key_message is not None:
            return ConflictError(foreign_key_message)
        # 나머지(NOT NULL·users FK 등)는 입력 문제가 아니라 우리 코드의 버그다 —
        # 4xx로 감싸면 서버 버그가 조용히 묻히므로 5xx가 되는 예외로 올린다.
        _logger.error(
            "workspaces 저장 제약 위반: sqlstate=%s constraint=%s",
            sqlstate,
            getattr(exc.orig, "constraint_name", None),
        )
        return StorageError("작업 공간을 저장하지 못했습니다")
