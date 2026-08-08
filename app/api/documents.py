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

from fastapi import APIRouter, Query, Request, Response, status
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ValidationError

# starlette의 UploadFile을 쓴다. fastapi.UploadFile은 그 **하위** 클래스라, 폼 파서가
# 만든 starlette 객체를 isinstance로 검사하면 방향이 뒤집혀 항상 False가 된다.
from starlette.datastructures import UploadFile

from app.api.deps import CurrentUserDep, DocumentServiceDep
from app.easyread.export import MEDIA_TYPES, ExportFormat, content_disposition
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

#: 개인정보가 실리는 응답에 붙이는 헤더.
#:
#: no-store: 이 응답들에는 마스킹 원문·문서 본문이 담긴다. 파일럿은 nginx가 프론트와
#: API를 같은 호스트로 서빙하는 구성이라, 누군가 proxy_cache를 켜는 순간 캐시된 사본이
#: 소유자 검증 없이 다른 사용자에게 나갈 수 있다. 서버가 스스로 "저장하지 말라"고
#: 말해두는 것이 그 구성 실수를 막는 유일한 방법이다.
#: nosniff: 브라우저가 내용을 보고 타입을 추측하지 않게 한다 — 사용자 본문이 그대로
#: 담긴 내려받기 파일이 스크립트로 해석되는 경로를 닫는다.
PRIVATE_RESPONSE_HEADERS = {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"}


class DocumentTextRequest(BaseModel):
    """붙여넣기 업로드 요청 (JSON 모드)."""

    text: str
    title: str | None = None
    #: 담을 작업 공간. 지정하지 않으면 기본(가장 먼저 만든) 작업 공간에 담긴다 —
    #: 작업 공간을 모르는 옛 클라이언트도 그대로 동작한다.
    workspace_id: uuid.UUID | None = None


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


class ConversionReviewRequest(BaseModel):
    """검수 수정본 저장 요청. 길이·공백 규칙은 서비스가 판단한다."""

    edited_text: str


class ConversionResponse(BaseModel):
    """변환 상태·결과. 완료 전에는 결과 필드가 모두 비어 있다."""

    id: uuid.UUID
    document_id: uuid.UUID
    status: str
    easy_text: str | None = None
    #: 담당자 검수 수정본. 아직 저장하지 않았으면 None이며, 에디터 초기값은
    #: `edited_text ?? easy_text`다 (AI 초안은 KPI 기준선이라 따로 실어 보낸다).
    edited_text: str | None = None
    reviewed_at: datetime | None = None
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
    #: 검수 수정본을 저장한 시각. 목록에서 "검수함/초안" 표시를 하려면 필요한데,
    #: 상태(status)만으로는 알 수 없다 — done은 "AI 변환이 끝났다"는 뜻일 뿐이다.
    reviewed_at: datetime | None = None


class DocumentListResponse(BaseModel):
    """문서 목록 응답. 총 개수는 싣지 않는다(전수 count는 목록 조회마다 비싸다)."""

    items: list[DocumentListItem]
    limit: int
    offset: int
    #: 다음 페이지가 있는지. 한 건 더 읽어(limit+1) 판정한다.
    has_more: bool


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
        edited_text=detail.edited_text,
        reviewed_at=conversion.reviewed_at,
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
        reviewed_at=None if latest is None else latest.reviewed_at,
    )


def _parse_form_workspace_id(value: object) -> uuid.UUID | None:
    """multipart 폼의 workspace_id 파트를 식별자로 바꾼다. 없으면 None.

    JSON 모드는 Pydantic이 같은 일을 해 주지만 폼 값은 전부 문자열이라 여기서 해석한다.
    형식이 틀리면 입력 오류(422)로 돌려준다 — 값 자체는 메시지에 담지 않는다.
    """
    if not isinstance(value, str) or value == "":
        return None
    try:
        return uuid.UUID(value)
    except ValueError:
        raise InvalidInputError("작업 공간 식별자 형식이 올바르지 않습니다") from None


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
    request: Request,
    response: Response,
    current_user: CurrentUserDep,
    service: DocumentServiceDep,
) -> DocumentCreatedResponse:
    """문서를 등록하고 변환을 요청한다 (202 — 결과는 변환 조회로 확인한다).

    두 가지 입력을 받는다: JSON `{"text": ..., "title": ...}` 또는 `file` 파트를 담은
    multipart. Content-Type으로 가른다.
    """
    # 미디어 타입은 대소문자를 가리지 않는다(RFC 9110) — 클라이언트가 보낸 그대로
    # 비교하면 `Multipart/Form-Data`가 JSON 경로로 새어 들어간다.
    if request.headers.get("content-type", "").lower().startswith(_MULTIPART_PREFIX):
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
                workspace_id=_parse_form_workspace_id(form.get("workspace_id")),
            )
    else:
        payload = await _parse_text_request(request)
        document, conversion = await service.create_from_text(
            user_id=current_user.id,
            text=payload.text,
            title=payload.title,
            workspace_id=payload.workspace_id,
        )
    # 202는 "접수했으니 나중에 여기서 확인하라"는 뜻이다 — 확인할 자리를 표준 헤더로
    # 알려 주면 클라이언트가 URL을 조립하지 않아도 된다.
    response.headers["Location"] = f"/conversions/{conversion.id}"
    return DocumentCreatedResponse(
        document_id=document.id,
        conversion_id=conversion.id,
        status=conversion.status,
        char_count=document.char_count,
    )


@router.get("/documents")
async def list_documents(
    response: Response,
    current_user: CurrentUserDep,
    service: DocumentServiceDep,
    limit: Annotated[int, Query(ge=1, le=MAX_PAGE_SIZE)] = DEFAULT_PAGE_SIZE,
    offset: Annotated[int, Query(ge=0)] = 0,
    workspace_id: uuid.UUID | None = None,
) -> DocumentListResponse:
    """내 문서를 최신순으로 돌려준다 (각 문서의 최신 변환 상태 포함).

    `workspace_id`를 주면 그 작업 공간만 본다. 내 것이 아닌 식별자는 404다 — 빈 목록으로
    답하면 남의 작업 공간이 존재하는지 확인하는 수단이 된다.

    본문은 싣지 않지만 제목이 본문 첫 줄에서 유도한 사용자 콘텐츠라, 조회·내보내기와
    같은 캐시 금지 헤더를 붙인다(PRIVATE_RESPONSE_HEADERS).
    """
    response.headers.update(PRIVATE_RESPONSE_HEADERS)
    page = await service.list_documents(
        current_user.id, limit=limit, offset=offset, workspace_id=workspace_id
    )
    return DocumentListResponse(
        items=[_to_list_item(summary) for summary in page.items],
        limit=limit,
        offset=offset,
        has_more=page.has_more,
    )


@router.delete("/documents/{document_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_document(
    document_id: uuid.UUID,
    current_user: CurrentUserDep,
    service: DocumentServiceDep,
) -> Response:
    """문서와 그 변환 결과를 즉시 파기한다. 내 것이 아니면 404.

    응답 모델을 두지 않는 이유: 204에는 본문이 없다. 지운 내용을 되돌려 주면 방금
    파기한 문서를 다시 밖으로 내보내는 셈이라, 삭제 확인은 상태 코드로만 한다.
    """
    await service.delete_document(document_id, current_user.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/conversions/{conversion_id}")
async def read_conversion(
    conversion_id: uuid.UUID,
    response: Response,
    current_user: CurrentUserDep,
    service: DocumentServiceDep,
) -> ConversionResponse:
    """변환 상태와 결과를 돌려준다. 내 것이 아니면 404(있다는 사실도 알리지 않는다).

    응답에 마스킹 원문이 실리므로 캐시 금지 헤더를 붙인다(PRIVATE_RESPONSE_HEADERS).
    """
    detail = await service.get_conversion(conversion_id, current_user.id)
    response.headers.update(PRIVATE_RESPONSE_HEADERS)
    return _to_conversion_response(detail)


@router.put("/conversions/{conversion_id}")
async def update_conversion(
    conversion_id: uuid.UUID,
    payload: ConversionReviewRequest,
    current_user: CurrentUserDep,
    service: DocumentServiceDep,
) -> ConversionResponse:
    """담당자가 고친 검수 수정본을 저장한다 (AI 초안은 그대로 남는다).

    아직 완료되지 않은 변환이면 409다 — 없는 것이 아니라 지금이 아닐 뿐이라는 뜻이다.
    """
    detail = await service.save_review(
        conversion_id, current_user.id, edited_text=payload.edited_text
    )
    return _to_conversion_response(detail)


@router.get(
    "/conversions/{conversion_id}/export",
    # 응답 본문이 JSON이 아니라 파일이다 — 실제로 내보내는 미디어 타입을 문서에 적어
    # 두지 않으면 OpenAPI가 application/json이라고 잘못 알린다.
    responses={
        200: {
            "description": "검수 완료 문서 파일 (자리표시자가 원문으로 복원된 최종 산출물)",
            "content": {media_type: {} for media_type in MEDIA_TYPES.values()},
        }
    },
)
async def export_conversion(
    conversion_id: uuid.UUID,
    current_user: CurrentUserDep,
    service: DocumentServiceDep,
    export_format: Annotated[ExportFormat, Query(alias="format")],
) -> Response:
    """검수 완료 문서를 파일로 내려준다 (docx | txt | hwpx).

    **이 응답에만 마스킹 자리표시자가 원문으로 복원되어 실린다** — 담당자가 그대로
    배포할 최종 산출물이기 때문이다(사유는 app/easyread/export.py). 목록에 없는
    형식은 쿼리 검증에서 422로 걸린다.

    응답 모델을 두지 않는 이유: 본문이 JSON이 아니라 파일 바이트다. 무엇이 나가는지는
    미디어 타입과 Content-Disposition으로 알린다.
    """
    export = await service.export_conversion(
        conversion_id, current_user.id, export_format=export_format
    )
    return Response(
        content=export.content,
        media_type=export.media_type,
        headers={
            "Content-Disposition": content_disposition(export.filename),
            **PRIVATE_RESPONSE_HEADERS,
        },
    )
