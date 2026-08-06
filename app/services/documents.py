"""문서 업로드·변환 요청·결과 조회 비즈니스 로직.

이 계층이 지키는 두 가지 순서가 있다.

1. **평문은 저장소에 닿지 않는다.** 원문·변환 결과·마스킹 항목은 `TextCipher`를
   통과한 뒤에만 저장한다 (master-plan 3.2). 저장소 시그니처가 암호문(bytes)만
   받도록 되어 있어, 암호화를 빠뜨린 호출은 타입 검사에서 걸린다.
2. **INSERT → commit → enqueue.** 커밋 전에 큐에 넣으면 워커가 아직 존재하지 않는
   행을 읽으러 가서 곧바로 실패한다. 반대로 큐 등록이 실패하면 이미 커밋된 변환이
   영원히 pending으로 굳으므로, 그 자리에서 실패로 표시하고 사용자에게 알린다.

변환 자체는 여기서 하지 않는다 — 워커가 `ConversionService`를 거쳐 수행하며, 그래야
마스킹 선행 불변식이 한 곳에서만 지켜진다.
"""

import json
import logging
import uuid
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Protocol

from anyio import to_thread
from pydantic import BaseModel, ValidationError

from app.exceptions import (
    DocumentExtractionError,
    InvalidInputError,
    NotFoundError,
    QueueUnavailableError,
    StorageError,
    UploadTooLargeError,
)
from app.ingest.extractors import MAX_EXTRACTED_CHARS, MAX_UPLOAD_BYTES, extract_text
from app.models.conversion import Conversion, ConversionStatus
from app.models.document import MAX_TITLE_LENGTH, Document
from app.privacy.crypto import TextCipher
from app.privacy.masking import MaskedItem
from app.queue import CONVERT_DOCUMENT_TASK, TaskQueue
from app.repositories.documents import DocumentSummary

_logger = logging.getLogger(__name__)

#: 붙여넣기 입력의 source_format 값. 파일 입력은 확장자를 그대로 쓴다.
TEXT_SOURCE_FORMAT = "text"

#: 큐 등록 실패를 나타내는 failure_code. 예외 클래스명과 같은 네임스페이스를 쓴다.
ENQUEUE_FAILURE_CODE = "EnqueueFailed"

#: 제목을 유도할 수 없을 때(첫 줄이 없거나 공백뿐일 때) 쓰는 이름.
FALLBACK_TITLE = "제목 없음"

#: 목록 조회 기본·최대 개수. 라우터의 Query 제약과 같은 값을 쓴다.
DEFAULT_PAGE_SIZE = 20
MAX_PAGE_SIZE = 100


class MaskedItemView(BaseModel):
    """복호화된 마스킹 항목.

    `original`에 실제 개인정보가 담긴다 — 인증된 소유자의 검수 화면 응답에만 싣는다.
    저장 형태(SecretStr)와 달리 평문 str인 이유는 이미 복호화된 표현이기 때문이다.
    """

    category: str
    placeholder: str
    original: str


@dataclass(frozen=True)
class ConversionDetail:
    """변환 한 건의 조회 결과 (복호화 완료).

    ORM 객체를 그대로 들고 다니는 이유: 상태·모델·토큰 같은 비밀 아닌 필드를 다시
    옮겨 적으면 컬럼이 늘 때마다 두 곳을 고쳐야 한다. 복호화가 필요한 두 필드만
    따로 담는다. 완료 전 상태에서는 둘 다 비어 있다.
    """

    conversion: Conversion
    easy_text: str | None
    masked_items: list[MaskedItemView]


def serialize_masked_items(items: list[MaskedItem]) -> str:
    """마스킹 항목을 저장용 JSON 문자열로 만든다.

    **이 결과는 반드시 암호화해서 저장한다** — 여기에는 마스킹 대상이던 원문
    개인정보가 그대로 들어 있다 (master-plan 3.2). 로그로 내보내지 않는다.
    """
    return json.dumps(
        [
            {
                "category": item.category.value,
                "placeholder": item.placeholder,
                "original": item.original.get_secret_value(),
            }
            for item in items
        ],
        ensure_ascii=False,
    )


def deserialize_masked_items(raw: str) -> list[MaskedItemView]:
    """복호화된 JSON에서 마스킹 항목을 되살린다.

    Raises:
        StorageError: 저장된 값이 우리가 쓴 형식이 아니다. 사용자 입력 문제가 아니므로
            5xx로 올린다 — 조용히 빈 목록으로 넘기면 검수 화면이 "가린 항목 없음"으로
            보여 원문 대조가 무력해진다.
    """
    try:
        payload = json.loads(raw)
        return [MaskedItemView.model_validate(entry) for entry in payload]
    except (json.JSONDecodeError, TypeError, ValidationError):
        # 원본 예외를 매달지 않는다 — 메시지에 개인정보가 섞인 JSON 조각이 실린다.
        raise StorageError("저장된 변환 결과를 읽을 수 없습니다") from None


class DocumentStore(Protocol):
    """DocumentService가 문서 저장소에 요구하는 계약 (구현: DocumentRepository)."""

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        title: str,
        source_format: str,
        source_text_encrypted: bytes,
        char_count: int,
    ) -> Document:
        """문서를 저장 대기 상태로 만든다(커밋하지 않는다)."""
        ...

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다."""
        ...

    async def list_for_user(
        self, user_id: uuid.UUID, *, limit: int, offset: int
    ) -> list[DocumentSummary]:
        """내 문서를 최신순으로 돌려준다 (최신 변환 상태 포함)."""
        ...


class ConversionStore(Protocol):
    """DocumentService가 변환 저장소에 요구하는 계약 (구현: ConversionRepository).

    워커가 쓰는 메서드(mark_processing/mark_done)는 여기 없다 — 요청 처리 경로가
    가질 수 있는 권한을 필요한 만큼으로 좁힌다.
    """

    async def create_pending(self, document_id: uuid.UUID) -> Conversion:
        """대기 상태 변환을 만든다(커밋하지 않는다)."""
        ...

    async def get_for_user(self, conversion_id: uuid.UUID, user_id: uuid.UUID) -> Conversion | None:
        """소유자를 확인하며 변환을 찾는다. 남의 것·없는 것 모두 None."""
        ...

    async def mark_failed(self, conversion: Conversion, failure_code: str) -> None:
        """변환 실패를 기록한다."""
        ...

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다."""
        ...


class DocumentService:
    """문서 등록과 변환 요청·조회.

    두 저장소는 **같은 요청 세션을 공유한다** — 커밋 한 번으로 문서와 변환이 함께
    확정되므로, 문서만 남고 변환이 없는 중간 상태가 만들어지지 않는다.
    """

    def __init__(
        self,
        *,
        documents: DocumentStore,
        conversions: ConversionStore,
        cipher: TextCipher,
        queue: TaskQueue,
    ) -> None:
        self._documents = documents
        self._conversions = conversions
        self._cipher = cipher
        self._queue = queue

    async def create_from_text(
        self, *, user_id: uuid.UUID, text: str, title: str | None = None
    ) -> tuple[Document, Conversion]:
        """붙여넣은 본문으로 문서를 만들고 변환을 요청한다.

        Raises:
            InvalidInputError: 본문이 비었거나 길이 상한을 넘었다.
            QueueUnavailableError: 큐에 작업을 등록하지 못했다.
        """
        if not text.strip():
            raise InvalidInputError("본문이 비어 있습니다")
        # 바이트가 아니라 문자 수로 잰다 — 한국어는 UTF-8에서 글자당 3바이트라
        # 바이트 기준으로 막으면 실제 분량의 1/3에서 잘린다. 추출 경로의
        # MAX_EXTRACTED_CHARS와 같은 기준을 써서 입력 방식에 따라 한도가 달라지지 않게 한다.
        if len(text) > MAX_EXTRACTED_CHARS:
            raise InvalidInputError(f"문서가 너무 깁니다 (최대 {MAX_EXTRACTED_CHARS:,}자)")
        return await self._store_and_enqueue(
            user_id=user_id, text=text, source_format=TEXT_SOURCE_FORMAT, title=title
        )

    async def create_from_file(
        self, *, user_id: uuid.UUID, filename: str, data: bytes, title: str | None = None
    ) -> tuple[Document, Conversion]:
        """업로드 파일에서 본문을 뽑아 문서를 만들고 변환을 요청한다.

        Raises:
            UploadTooLargeError: 파일이 크기 상한을 넘었다.
            UnsupportedFormatError: 지원하지 않는 확장자다.
            DocumentExtractionError: 텍스트를 뽑지 못했거나 결과가 비었다.
            QueueUnavailableError: 큐에 작업을 등록하지 못했다.
        """
        if len(data) > MAX_UPLOAD_BYTES:
            # 파일명·내용은 메시지에 넣지 않는다(개인정보).
            raise UploadTooLargeError(
                f"파일이 너무 큽니다 (최대 {MAX_UPLOAD_BYTES // (1024 * 1024)}MB)"
            )
        # extract_text는 동기 CPU 바운드다 — 이벤트 루프에서 직접 부르면 큰 문서 하나가
        # 모든 요청을 멈춘다. 공유 CapacityLimiter는 두지 않는다: 추출량 상한
        # (MAX_UPLOAD_BYTES·압축 해제 예산·MAX_EXTRACTED_CHARS)이 모듈 안에 이미 있어
        # argon2처럼 건당 메모리가 고정 폭발하는 구조가 아니다.
        text = await to_thread.run_sync(extract_text, filename, data)
        if not text.strip():
            # 빈 docx·hwpx는 예외 없이 ""를 돌려준다 — 빈 문서 판정은 추출 결과로 한다.
            raise DocumentExtractionError("문서에서 텍스트를 찾을 수 없습니다")
        return await self._store_and_enqueue(
            user_id=user_id,
            text=text,
            source_format=_source_format(filename),
            title=title,
        )

    async def list_documents(
        self, user_id: uuid.UUID, *, limit: int, offset: int
    ) -> list[DocumentSummary]:
        """내 문서 목록을 최신순으로 돌려준다."""
        return await self._documents.list_for_user(user_id, limit=limit, offset=offset)

    async def get_conversion(
        self, conversion_id: uuid.UUID, user_id: uuid.UUID
    ) -> ConversionDetail:
        """변환 한 건을 소유자 권한으로 읽고, 완료됐으면 결과를 복호화한다.

        Raises:
            NotFoundError: 없거나 내 것이 아니다.
            StorageError: 저장된 암호문을 읽을 수 없다.
        """
        conversion = await self._conversions.get_for_user(conversion_id, user_id)
        if conversion is None:
            # 남의 변환과 없는 변환을 구분하지 않는다 — 구분하면 식별자의 존재 여부가
            # 새어 나가 다른 사용자의 활동을 추론할 수 있다.
            raise NotFoundError("변환 결과를 찾을 수 없습니다")
        if conversion.status != ConversionStatus.DONE or conversion.easy_text_encrypted is None:
            # 진행 중·실패 상태에서는 복호화할 것이 없다. 상태와 failure_code만 나간다.
            return ConversionDetail(conversion=conversion, easy_text=None, masked_items=[])
        masked_items = (
            deserialize_masked_items(self._cipher.decrypt(conversion.masked_items_encrypted))
            if conversion.masked_items_encrypted is not None
            else []
        )
        return ConversionDetail(
            conversion=conversion,
            easy_text=self._cipher.decrypt(conversion.easy_text_encrypted),
            masked_items=masked_items,
        )

    async def _store_and_enqueue(
        self, *, user_id: uuid.UUID, text: str, source_format: str, title: str | None
    ) -> tuple[Document, Conversion]:
        """문서·변환을 저장하고 확정한 뒤 큐에 작업을 넣는다 (이 순서를 지킨다)."""
        document = await self._documents.create(
            user_id=user_id,
            title=_resolve_title(title, text),
            source_format=source_format,
            source_text_encrypted=self._cipher.encrypt(text),
            char_count=len(text),
        )
        conversion = await self._conversions.create_pending(document.id)
        # 커밋이 먼저다. 워커는 다른 프로세스라, 커밋 전에 큐에 넣으면 아직 보이지 않는
        # 행을 읽으러 가서 곧바로 실패한다.
        await self._documents.commit()
        await self._enqueue(conversion)
        return document, conversion

    async def _enqueue(self, conversion: Conversion) -> None:
        """변환 작업을 큐에 넣는다. 실패하면 그 사실을 DB에 남기고 502로 알린다."""
        try:
            await self._queue.enqueue(CONVERT_DOCUMENT_TASK, str(conversion.id))
        except Exception as exc:
            # 넓게 잡는다: 큐 클라이언트가 어떤 예외를 던지는지는 구현 세부이고, 여기서
            # 놓치면 이미 커밋된 변환이 영원히 pending으로 굳는다(사용자는 끝나지 않는
            # 진행 표시만 본다). 사유는 예외 **타입**만 남긴다 — Redis 예외 메시지에는
            # 접속 URL(비밀번호 포함)이 실릴 수 있다.
            _logger.error(
                "변환 작업 등록 실패: conversion_id=%s reason=%s",
                conversion.id,
                type(exc).__name__,
            )
            await self._conversions.mark_failed(conversion, ENQUEUE_FAILURE_CODE)
            await self._conversions.commit()
            raise QueueUnavailableError("변환 요청을 등록하지 못했습니다") from None


def _source_format(filename: str) -> str:
    """확장자로 입력 형식 이름을 만든다 ("docx"·"pdf"·"hwpx").

    `extract_text`가 이미 지원 형식만 통과시킨 뒤에 부른다 — 여기서 다시 검사하면
    지원 목록이 두 곳에 생긴다.
    """
    return PurePosixPath(filename).suffix.lower().lstrip(".")


def _resolve_title(title: str | None, text: str) -> str:
    """제목을 정한다. 지정이 없으면 본문 첫 줄에서 유도한다.

    파일명을 쓰지 않는 이유: 파일명 자체가 개인정보일 수 있다(`홍길동_주민등록등본.pdf`).
    같은 이유로 파일명은 저장도 로깅도 하지 않는다.

    상한을 넘는 제목은 거부하지 않고 자른다 — 목록에 보일 이름일 뿐이라, 긴 첫 줄을
    가진 문서 업로드를 통째로 실패시킬 이유가 없다.
    """
    candidate = (title or "").strip()
    if not candidate:
        candidate = next((line.strip() for line in text.splitlines() if line.strip()), "")
    return candidate[:MAX_TITLE_LENGTH] or FALLBACK_TITLE
