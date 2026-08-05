"""conversions 테이블 접근.

상태 전이(pending → processing → done/failed)를 여기 모아 둔다. 상태 문자열을
호출부가 직접 대입하면 CHECK 제약 위반이 런타임까지 살아남고, 무엇을 함께 채워야
하는지(모델·토큰·실패 코드)가 자리마다 달라진다.

커밋은 하지 않는다 — 트랜잭션 경계는 호출자(서비스·워커)가 소유한다.
"""

import logging
import uuid

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.exceptions import StorageError
from app.models.conversion import Conversion, ConversionStatus
from app.models.document import Document

_logger = logging.getLogger(__name__)


class ConversionRepository:
    """변환 작업 저장소. 세션은 요청(또는 워커 작업) 단위로 주입받는다."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create_pending(self, document_id: uuid.UUID) -> Conversion:
        """대기 상태 변환 작업을 INSERT 한다 (flush까지만).

        Raises:
            StorageError: 제약을 위반했다 (documents FK 등 — 코드 버그다).
        """
        conversion = Conversion(document_id=document_id, status=ConversionStatus.PENDING)
        self._session.add(conversion)
        try:
            await self._session.flush()
        except IntegrityError as exc:
            await self._session.rollback()
            # 분기하지 않는 이유·DETAIL을 남기지 않는 이유는 documents 저장소 참고.
            _logger.error(
                "conversions 저장 제약 위반: sqlstate=%s", getattr(exc.orig, "sqlstate", None)
            )
            raise StorageError("변환 작업을 저장하지 못했습니다") from None
        return conversion

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다. 호출 시점은 서비스·워커가 정한다."""
        await self._session.commit()

    async def get_for_user(self, conversion_id: uuid.UUID, user_id: uuid.UUID) -> Conversion | None:
        """소유자를 확인하며 변환을 찾는다. 남의 것·없는 것 모두 None.

        conversions에는 user_id가 없으므로 documents로 조인해 소유자를 확인한다.
        소유 정보를 복제해 두면 두 테이블이 어긋나는 순간 검증이 무력해진다.
        """
        result = await self._session.execute(
            select(Conversion)
            .join(Document, Conversion.document_id == Document.id)
            .where(Conversion.id == conversion_id, Document.user_id == user_id)
        )
        return result.scalar_one_or_none()

    async def get_with_document(
        self, conversion_id: uuid.UUID
    ) -> tuple[Conversion, Document] | None:
        """변환과 원본 문서를 함께 읽는다 (워커 전용 — 소유자 검증 없음).

        워커는 큐가 넘겨준 식별자로 일하며 사용자 요청 맥락이 없다. 사용자에게 응답하는
        경로에서는 반드시 `get_for_user`를 쓸 것.
        """
        result = await self._session.execute(
            select(Conversion, Document)
            .join(Document, Conversion.document_id == Document.id)
            .where(Conversion.id == conversion_id)
        )
        row = result.one_or_none()
        return None if row is None else (row[0], row[1])

    async def mark_processing(self, conversion: Conversion) -> None:
        """처리 시작을 기록한다."""
        conversion.status = ConversionStatus.PROCESSING
        await self._session.flush()

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
        """변환 성공 결과를 기록한다.

        본문과 마스킹 항목은 **암호화된 상태로만** 받는다 — 평문을 받는 시그니처를 두면
        암호화를 빠뜨린 호출이 타입 검사를 통과해 버린다.
        """
        conversion.status = ConversionStatus.DONE
        conversion.easy_text_encrypted = easy_text_encrypted
        conversion.masked_items_encrypted = masked_items_encrypted
        conversion.missing_placeholders = missing_placeholders
        conversion.provider_name = provider_name
        conversion.model = model
        conversion.input_tokens = input_tokens
        conversion.output_tokens = output_tokens
        conversion.failure_code = None
        await self._session.flush()

    async def mark_failed(self, conversion: Conversion, failure_code: str) -> None:
        """변환 실패를 기록한다.

        Args:
            failure_code: 예외 클래스명 같은 짧은 코드. 문서 본문·모델 응답·예외 메시지를
                넣지 않는다 (이 값은 API 응답으로 그대로 나간다).
        """
        conversion.status = ConversionStatus.FAILED
        conversion.failure_code = failure_code
        await self._session.flush()
