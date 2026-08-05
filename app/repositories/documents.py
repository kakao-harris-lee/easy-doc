"""documents 테이블 접근.

users 저장소와 같은 규약을 따른다: SQLAlchemy 예외를 밖으로 흘리지 않고, 커밋
시점은 정하지 않는다(트랜잭션 경계는 서비스가 소유한다 — app/services/documents.py).
"""

import logging
import uuid
from dataclasses import dataclass

from sqlalchemy import select
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


class DocumentRepository:
    """문서 저장소. 세션은 요청(또는 워커 작업) 단위로 주입받는다."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        title: str,
        source_format: str,
        source_text_encrypted: bytes,
        char_count: int,
    ) -> Document:
        """문서를 INSERT 한다 (flush까지만 — 확정은 호출자가 commit으로 한다).

        Args:
            user_id: 소유자 식별자 (인증된 사용자).
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

    async def list_for_user(
        self, user_id: uuid.UUID, *, limit: int, offset: int
    ) -> list[DocumentSummary]:
        """내 문서를 최신순으로 돌려준다 (각 문서의 최신 변환 상태 포함).

        정렬에 id를 덧붙이는 이유: created_at은 트랜잭션 시각(now())이라 같은 요청에서
        만들어진 행끼리 동률이 된다. 동률이면 페이지 경계에서 같은 문서가 두 번 나오거나
        건너뛰어진다.
        """
        result = await self._session.execute(
            select(Document)
            .where(Document.user_id == user_id)
            .order_by(Document.created_at.desc(), Document.id.desc())
            .limit(limit)
            .offset(offset)
        )
        documents = list(result.scalars())
        if not documents:
            return []

        # DISTINCT ON: 문서별로 가장 최근 변환 한 건씩만 한 번의 쿼리로 가져온다.
        # (Lean MVP는 문서당 변환이 하나지만, 재변환이 생겨도 이 쿼리는 그대로 맞는다.)
        latest = await self._session.execute(
            select(Conversion)
            .where(Conversion.document_id.in_([document.id for document in documents]))
            .order_by(Conversion.document_id, Conversion.created_at.desc(), Conversion.id.desc())
            .distinct(Conversion.document_id)
        )
        by_document = {conversion.document_id: conversion for conversion in latest.scalars()}
        return [
            DocumentSummary(document=document, latest_conversion=by_document.get(document.id))
            for document in documents
        ]
