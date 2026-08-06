"""문서 라우터 — 업로드·목록·변환 결과 조회.

입력 검증 규칙(빈 본문, 길이·크기 상한, 제목 유도)은 여기서 판단하지 않는다.
`DocumentService`가 단일 기준을 갖고, 라우터는 도메인 예외를 그대로 흘려보내
errors.py가 HTTP로 바꾼다.

응답 스키마를 이 계층에 두는 이유는 **무엇을 밖으로 내보내는지 한 자리에서 보이게**
하기 위해서다 — masked_items에는 원문 개인정보가 담기므로(소유자 검수용) ORM 객체나
서비스 결과를 그대로 직렬화하지 않고 필드를 손으로 옮긴다.
"""

import json
import uuid
from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Query, Request, status
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ValidationError

# starlette의 UploadFile을 쓴다. fastapi.UploadFile은 그 **하위** 클래스라, 폼 파서가
# 만든 starlette 객체를 isinstance로 검사하면 방향이 뒤집혀 항상 False가 된다.
from starlette.datastructures import UploadFile

from app.api.deps import CurrentUserDep, DocumentServiceDep
from app.exceptions import InvalidInputError
from app.ingest.extractors import MAX_UPLOAD_BYTES
from app.models.conversion import Conversion
from app.repositories.documents import DocumentSummary
from app.services.documents import (
    DEFAULT_PAGE_SIZE,
    MAX_PAGE_SIZE,
    ConversionDetail,
    MaskedItemView,
)

router = APIRouter(tags=["documents"])

_MULTIPART_PREFIX = "multipart/form-data"


class DocumentTextRequest(BaseModel):
    """붙여넣기 업로드 요청 (JSON 모드)."""

    text: str
    title: str | None = None


class DocumentCreatedResponse(BaseModel):
    """업로드 접수 응답. 변환은 아직 시작되지 않았다(202)."""

    document_id: uuid.UUID
    conversion_id: uuid.UUID
    status: str
    #: 공백 포함 문자 수. 크레딧 환산(1,000자=1크레딧)의 기준값이다.
    char_count: int


class MaskedItemResponse(BaseModel):
    """검수 화면에 보여줄 마스킹 항목.

    original은 가려졌던 실제 개인정보다 — 소유자 인증을 통과한 조회에서만 실린다.
    """

    category: str
    placeholder: str
    original: str


class ConversionResponse(BaseModel):
    """변환 상태·결과. 완료 전에는 결과 필드가 모두 비어 있다."""

    id: uuid.UUID
    document_id: uuid.UUID
    status: str
    easy_text: str | None = None
    masked_items: list[MaskedItemResponse] = []
    missing_placeholders: list[str] = []
    model: str | None = None
    provider_name: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None
    #: 실패 사유 코드(예외 클래스명). 본문·모델 응답은 담기지 않는다.
    failure_code: str | None = None


class DocumentListItem(BaseModel):
    """문서 목록 한 줄 (문서 메타 + 최신 변환 상태)."""

    id: uuid.UUID
    title: str
    source_format: str
    char_count: int
    created_at: datetime
    retention_expires_at: datetime
    conversion_id: uuid.UUID | None = None
    status: str | None = None


class DocumentListResponse(BaseModel):
    """문서 목록 응답. 총 개수는 싣지 않는다(전수 count는 목록 조회마다 비싸다)."""

    items: list[DocumentListItem]
    limit: int
    offset: int


def _to_masked_item(item: MaskedItemView) -> MaskedItemResponse:
    """서비스 결과를 응답 스키마로 옮긴다."""
    return MaskedItemResponse(
        category=item.category, placeholder=item.placeholder, original=item.original
    )


def _to_conversion_response(detail: ConversionDetail) -> ConversionResponse:
    """변환 상세를 응답으로 옮긴다.

    완료 전에는 easy_text·masked_items가 비어 있고(서비스가 복호화하지 않는다),
    실패한 경우에만 failure_code가 채워진다.
    """
    conversion = detail.conversion
    return ConversionResponse(
        id=conversion.id,
        document_id=conversion.document_id,
        status=conversion.status,
        easy_text=detail.easy_text,
        masked_items=[_to_masked_item(item) for item in detail.masked_items],
        missing_placeholders=list(conversion.missing_placeholders),
        model=conversion.model,
        provider_name=conversion.provider_name,
        input_tokens=conversion.input_tokens,
        output_tokens=conversion.output_tokens,
        failure_code=conversion.failure_code,
    )


def _to_list_item(summary: DocumentSummary) -> DocumentListItem:
    """문서 요약을 목록 한 줄로 옮긴다. 본문·암호문은 절대 싣지 않는다."""
    latest: Conversion | None = summary.latest_conversion
    return DocumentListItem(
        id=summary.document.id,
        title=summary.document.title,
        source_format=summary.document.source_format,
        char_count=summary.document.char_count,
        created_at=summary.document.created_at,
        retention_expires_at=summary.document.retention_expires_at,
        conversion_id=None if latest is None else latest.id,
        status=None if latest is None else latest.status,
    )


async def _parse_text_request(request: Request) -> DocumentTextRequest:
    """JSON 본문을 읽어 검증한다.

    FastAPI의 선언적 파싱을 쓰지 못하는 이유는 한 엔드포인트가 JSON과 multipart를 함께
    받기 때문이다(본문은 한 번만 읽을 수 있어 두 방식을 동시에 선언할 수 없다).
    검증 실패는 RequestValidationError로 되돌려 errors.py의 핸들러를 그대로 태운다 —
    그 핸들러가 응답에서 입력값 에코를 걷어낸다.
    """
    try:
        payload = await request.json()
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise InvalidInputError("요청 본문이 올바른 JSON이 아닙니다") from None
    try:
        return DocumentTextRequest.model_validate(payload)
    except ValidationError as exc:
        raise RequestValidationError(exc.errors()) from None


@router.post("/documents", status_code=status.HTTP_202_ACCEPTED)
async def create_document(
    request: Request, current_user: CurrentUserDep, service: DocumentServiceDep
) -> DocumentCreatedResponse:
    """문서를 등록하고 변환을 요청한다 (202 — 결과는 변환 조회로 확인한다).

    두 가지 입력을 받는다: JSON `{"text": ..., "title": ...}` 또는 `file` 파트를 담은
    multipart. Content-Type으로 가른다.
    """
    if request.headers.get("content-type", "").startswith(_MULTIPART_PREFIX):
        async with request.form() as form:
            upload = form.get("file")
            if not isinstance(upload, UploadFile):
                raise InvalidInputError("업로드할 파일(file)이 필요합니다")
            title = form.get("title")
            # 상한+1까지만 읽는다 — 초과 파일 때문에 메모리를 통째로 쓰지 않으면서도
            # "상한을 넘었다"는 판정에는 충분하다.
            data = await upload.read(MAX_UPLOAD_BYTES + 1)
            document, conversion = await service.create_from_file(
                user_id=current_user.id,
                filename=upload.filename or "",
                data=data,
                title=title if isinstance(title, str) else None,
            )
    else:
        payload = await _parse_text_request(request)
        document, conversion = await service.create_from_text(
            user_id=current_user.id, text=payload.text, title=payload.title
        )
    return DocumentCreatedResponse(
        document_id=document.id,
        conversion_id=conversion.id,
        status=conversion.status,
        char_count=document.char_count,
    )


@router.get("/documents")
async def list_documents(
    current_user: CurrentUserDep,
    service: DocumentServiceDep,
    limit: Annotated[int, Query(ge=1, le=MAX_PAGE_SIZE)] = DEFAULT_PAGE_SIZE,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> DocumentListResponse:
    """내 문서를 최신순으로 돌려준다 (각 문서의 최신 변환 상태 포함)."""
    summaries = await service.list_documents(current_user.id, limit=limit, offset=offset)
    return DocumentListResponse(
        items=[_to_list_item(summary) for summary in summaries], limit=limit, offset=offset
    )


@router.get("/conversions/{conversion_id}")
async def read_conversion(
    conversion_id: uuid.UUID, current_user: CurrentUserDep, service: DocumentServiceDep
) -> ConversionResponse:
    """변환 상태와 결과를 돌려준다. 내 것이 아니면 404(있다는 사실도 알리지 않는다)."""
    detail = await service.get_conversion(conversion_id, current_user.id)
    return _to_conversion_response(detail)
