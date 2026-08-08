"""documents 테이블 접근.

users 저장소와 같은 규약을 따른다: SQLAlchemy 예외를 밖으로 흘리지 않고, 커밋
시점은 정하지 않는다(트랜잭션 경계는 서비스가 소유한다 — app/services/documents.py).
"""

import logging
import uuid
from dataclasses import dataclass

from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import StorageError
from app.models.conversion import Conversion
from app.models.document import Document

_logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class DocumentSummary:
    """문서와 그 문서의 가장 최근 변환.

    목록 화면이 문서마다 변환을 다시 조회하지 않도록(N+1) 함께 돌려준다.
    """

    document: Document
    latest_conversion: Conversion | None


@dataclass(frozen=True)
class DocumentPage:
    """목록 한 페이지.

    총 개수 대신 `has_more`만 준다 — 전수 COUNT는 문서가 쌓일수록 목록 조회마다 느려지고,
    화면에 필요한 것은 "다음 페이지가 있는지"뿐이다.
    """

    items: list[DocumentSummary]
    has_more: bool


class DocumentRepository:
    """문서 저장소. 세션은 요청(또는 워커 작업) 단위로 주입받는다."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        workspace_id: uuid.UUID,
        title: str,
        source_format: str,
        source_text_encrypted: bytes,
        char_count: int,
    ) -> Document:
        """문서를 INSERT 한다 (flush까지만 — 확정은 호출자가 commit으로 한다).

        Args:
            user_id: 소유자 식별자 (인증된 사용자).
            workspace_id: 담을 작업 공간. 소유자 확인은 서비스가 이미 마쳤다
                (app/services/documents.py) — 여기서 다시 검사하면 판정이 두 곳에 생긴다.
            title: 화면 표시용 제목. 파일명은 저장하지 않는다.
            source_format: "text" | "docx" | "pdf" | "hwpx".
            source_text_encrypted: `TextCipher.encrypt` 결과. 평문 금지.
            char_count: 공백 포함 문자 수 (크레딧 환산 기준값).

        Returns:
            식별자가 채워진 문서 (아직 커밋되지 않았다).

        Raises:
            StorageError: 제약을 위반했다.
        """
        document = Document(
            user_id=user_id,
            workspace_id=workspace_id,
            title=title,
            source_format=source_format,
            source_text_encrypted=source_text_encrypted,
            char_count=char_count,
        )
        self._session.add(document)
        try:
            await self._session.flush()
        except IntegrityError as exc:
            # users 저장소와 달리 SQLSTATE로 분기하지 않는다. 여기서 걸릴 수 있는 제약은
            # users FK(계정이 방금 삭제됨)와 NOT NULL뿐이고, 둘 다 사용자가 입력을 고쳐
            # 해결할 수 있는 문제가 아니다 — 4xx로 감싸면 서버 버그가 조용히 묻힌다.
            await self._session.rollback()
            # 예외 메시지·DETAIL은 남기지 않는다. PostgreSQL은 제약 위반 DETAIL에 실패한
            # 행 전체(=암호문·제목)를 담는다 (app/repositories/users.py 참고).
            _logger.error(
                "documents 저장 제약 위반: sqlstate=%s", getattr(exc.orig, "sqlstate", None)
            )
            raise StorageError("문서를 저장하지 못했습니다") from None
        return document

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다. 호출 시점은 서비스가 정한다."""
        await self._session.commit()

    async def get_for_user(self, document_id: uuid.UUID, user_id: uuid.UUID) -> Document | None:
        """소유자를 확인하며 문서를 찾는다. 남의 문서·없는 문서 모두 None.

        조건을 하나로 합쳐 "찾았지만 남의 것"이라는 상태 자체를 만들지 않는다 —
        호출부가 소유자 검사를 빠뜨릴 여지를 남기지 않기 위해서다.
        """
        result = await self._session.execute(
            select(Document).where(Document.id == document_id, Document.user_id == user_id)
        )
        return result.scalar_one_or_none()

    async def delete_for_user(self, document_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """소유자를 확인하며 문서를 지운다 (flush까지만 — 확정은 호출자가 commit으로 한다).

        변환 행은 FK `ondelete=CASCADE`가 DB 안에서 함께 지운다 — 애플리케이션이 두 번
        지우면 그 사이에 끼어든 워커의 UPDATE가 고아 행을 남길 수 있고, 삭제 순서를
        코드가 기억해야 한다. 파기는 한 문장이어야 원자적이다 (master-plan 3.2 "삭제
        요청 시 즉시 파기").

        소유자 조건을 WHERE에 함께 넣는 이유는 `get_for_user`와 같다 — "찾았지만 남의
        것"이라는 상태를 만들지 않으면 호출부가 소유자 검사를 빠뜨릴 수 없다.

        Returns:
            지웠으면 True. False면 없거나 내 것이 아니다(호출부가 404로 바꾼다).
        """
        result = await self._session.execute(
            delete(Document)
            .where(Document.id == document_id, Document.user_id == user_id)
            # RETURNING으로 삭제 여부를 본다(rowcount는 드라이버마다 타입이 다르다) —
            # conversions 저장소의 조건부 UPDATE와 같은 규칙이다.
            .returning(Document.id)
            .execution_options(synchronize_session=False)
        )
        return result.scalar_one_or_none() is not None

    async def delete_expired(self, *, limit: int) -> int:
        """보존 기간이 지난 문서를 최대 `limit`건 지운다 (flush까지만).

        만료 판정을 애플리케이션이 아니라 DB 시계(`now()`)로 하는 이유는
        `retention_expires_at` 기본값이 DB 시계로 찍히기 때문이다 — 두 시계가 어긋나면
        경계에 있는 문서가 하루 일찍/늦게 사라진다.

        한 번에 다 지우지 않고 건수를 끊는 이유: 만료 문서가 수만 건 쌓인 날 단일
        DELETE는 트랜잭션 하나로 그만큼의 행을 잠근다. 호출자(cron 잡)가 이 메서드를
        반복 호출하며 배치마다 커밋한다.

        Returns:
            실제로 지운 건수. 0이면 더 지울 것이 없다는 뜻이라 호출자는 반복을 멈춘다.
        """
        expired = (
            select(Document.id)
            .where(Document.retention_expires_at < func.now())
            # 오래된 것부터 지운다 — 중간에 멈춰도 "가장 오래 남아 있던 문서"가 먼저
            # 사라져, 반복 실행이 만료 대기열을 앞에서부터 줄인다.
            .order_by(Document.retention_expires_at)
            .limit(limit)
        )
        result = await self._session.execute(
            # conversions는 FK CASCADE로 함께 사라진다 (delete_for_user와 같은 이유).
            delete(Document)
            .where(Document.id.in_(expired))
            .returning(Document.id)
            .execution_options(synchronize_session=False)
        )
        return len(result.scalars().all())

    async def list_for_user(
        self,
        user_id: uuid.UUID,
        *,
        limit: int,
        offset: int,
        workspace_id: uuid.UUID | None = None,
    ) -> DocumentPage:
        """내 문서를 최신순으로 돌려준다 (각 문서의 최신 변환 상태 포함).

        `workspace_id`를 주면 그 작업 공간만 본다. 주더라도 user_id 조건은 그대로 남긴다 —
        소유자 판정을 작업 공간 소유 여부에 의존시키면, 작업 공간 검사를 빠뜨린 호출
        하나가 곧바로 남의 문서 노출이 된다.

        정렬에 id를 덧붙이는 이유: created_at은 트랜잭션 시각(now())이라 같은 요청에서
        만들어진 행끼리 동률이 된다. 동률이면 페이지 경계에서 같은 문서가 두 번 나오거나
        건너뛰어진다.

        다음 페이지 유무는 한 건 더 읽어서(limit+1) 판정한다 — COUNT 쿼리를 한 번 더
        치는 것보다 싸다.

        한계: offset 방식이라 조회 중에 새 문서가 만들어지면 경계가 밀려 같은 문서를 다시
        보게 된다(문서가 목록 맨 앞에 삽입되기 때문). 문서 수가 늘어 이것이 문제가 되면
        (created_at, id) 커서를 쓰는 키셋 페이지네이션으로 바꾼다 — 후속 과제.
        """
        conditions = [Document.user_id == user_id]
        if workspace_id is not None:
            conditions.append(Document.workspace_id == workspace_id)
        result = await self._session.execute(
            select(Document)
            .where(*conditions)
            .order_by(Document.created_at.desc(), Document.id.desc())
            .limit(limit + 1)
            .offset(offset)
        )
        documents = list(result.scalars())
        has_more = len(documents) > limit
        documents = documents[:limit]
        if not documents:
            return DocumentPage(items=[], has_more=False)

        # DISTINCT ON: 문서별로 가장 최근 변환 한 건씩만 한 번의 쿼리로 가져온다.
        # (Lean MVP는 문서당 변환이 하나지만, 재변환이 생겨도 이 쿼리는 그대로 맞는다.)
        latest = await self._session.execute(
            select(Conversion)
            .where(Conversion.document_id.in_([document.id for document in documents]))
            .order_by(Conversion.document_id, Conversion.created_at.desc(), Conversion.id.desc())
            .distinct(Conversion.document_id)
        )
        by_document = {conversion.document_id: conversion for conversion in latest.scalars()}
        return DocumentPage(
            items=[
                DocumentSummary(document=document, latest_conversion=by_document.get(document.id))
                for document in documents
            ],
            has_more=has_more,
        )
