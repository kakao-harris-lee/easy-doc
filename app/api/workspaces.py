"""작업 공간 라우터 — 목록·만들기·이름 바꾸기·삭제.

입력 규칙(이름 길이·빈 값)은 여기서 판단하지 않는다. `WorkspaceService`가 단일 기준을
갖고, 라우터는 도메인 예외를 그대로 흘려보내 errors.py가 HTTP로 바꾼다.

**삭제 화면은 이번 범위에 없다(API만 제공).** 스프린트 4 계획서의 UI 항목은 목록·전환·
만들기·이름 바꾸기까지이고, 되돌릴 수 없는 조작을 계획에 없던 자리에 얹으면 실수로
누르는 경로부터 만들어진다. 화면은 다음 스프린트에서 확인 절차와 함께 붙인다.
"""

import uuid
from datetime import datetime

from fastapi import APIRouter, Response, status
from pydantic import BaseModel

from app.api.deps import CurrentUserDep, WorkspaceServiceDep

# 문서 라우터가 정의한 캐시 금지 헤더를 그대로 쓴다 — 정책이 갈라지면 한쪽만 캐시
# 금지가 빠진다. 작업 공간 이름도 사용자가 적은 콘텐츠라 같은 취급을 받아야 한다.
from app.api.documents import PRIVATE_RESPONSE_HEADERS
from app.models.workspace import Workspace
from app.repositories.workspaces import WorkspaceSummary

router = APIRouter(tags=["workspaces"])


class WorkspaceNameRequest(BaseModel):
    """작업 공간 만들기·이름 바꾸기 요청. 길이·빈 값 규칙은 서비스가 판단한다."""

    name: str


class WorkspaceResponse(BaseModel):
    """작업 공간 한 건 (만들기·이름 바꾸기 응답)."""

    id: uuid.UUID
    name: str
    created_at: datetime


class WorkspaceListItem(WorkspaceResponse):
    """목록 한 줄. 문서 수는 목록에서만 준다.

    단건 응답에 싣지 않는 이유: 방금 만든 공간은 늘 0이고, 이름만 바꾼 공간의 문서 수는
    화면이 이미 알고 있다. 응답마다 COUNT를 붙이면 쓰지 않는 값을 위해 질의가 는다.
    """

    document_count: int


class WorkspaceListResponse(BaseModel):
    """작업 공간 목록. 첫 번째 항목이 기본 작업 공간이다(가장 먼저 만든 것)."""

    items: list[WorkspaceListItem]


def _to_response(workspace: Workspace) -> WorkspaceResponse:
    """ORM 객체를 공개 필드만 남겨 옮긴다."""
    return WorkspaceResponse(id=workspace.id, name=workspace.name, created_at=workspace.created_at)


def _to_list_item(summary: WorkspaceSummary) -> WorkspaceListItem:
    """목록 한 줄로 옮긴다."""
    return WorkspaceListItem(
        id=summary.workspace.id,
        name=summary.workspace.name,
        created_at=summary.workspace.created_at,
        document_count=summary.document_count,
    )


@router.get("/workspaces")
async def list_workspaces(
    response: Response,
    current_user: CurrentUserDep,
    service: WorkspaceServiceDep,
) -> WorkspaceListResponse:
    """내 작업 공간을 만든 순서대로 돌려준다 (각 공간의 문서 수 포함).

    이름은 사용자가 적은 콘텐츠라 문서 목록과 같은 캐시 금지 헤더를 붙인다.
    """
    response.headers.update(PRIVATE_RESPONSE_HEADERS)
    summaries = await service.list_workspaces(current_user.id)
    return WorkspaceListResponse(items=[_to_list_item(summary) for summary in summaries])


@router.post("/workspaces", status_code=status.HTTP_201_CREATED)
async def create_workspace(
    payload: WorkspaceNameRequest,
    response: Response,
    current_user: CurrentUserDep,
    service: WorkspaceServiceDep,
) -> WorkspaceResponse:
    """작업 공간을 만든다. 같은 이름이 이미 있으면 409."""
    response.headers.update(PRIVATE_RESPONSE_HEADERS)
    return _to_response(await service.create_workspace(current_user.id, name=payload.name))


@router.patch("/workspaces/{workspace_id}")
async def rename_workspace(
    workspace_id: uuid.UUID,
    payload: WorkspaceNameRequest,
    response: Response,
    current_user: CurrentUserDep,
    service: WorkspaceServiceDep,
) -> WorkspaceResponse:
    """작업 공간 이름을 바꾼다. 내 것이 아니면 404, 같은 이름이 이미 있으면 409.

    PUT이 아니라 PATCH인 이유: 바꾸는 것이 이름 하나뿐이라, 전체 표현을 보내는 PUT은
    클라이언트가 나머지 필드(만든 시각 등)까지 실어 보내야 한다는 뜻이 된다.
    """
    response.headers.update(PRIVATE_RESPONSE_HEADERS)
    return _to_response(
        await service.rename_workspace(workspace_id, current_user.id, name=payload.name)
    )


@router.delete("/workspaces/{workspace_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workspace(
    workspace_id: uuid.UUID,
    current_user: CurrentUserDep,
    service: WorkspaceServiceDep,
) -> Response:
    """빈 작업 공간을 지운다. 내 것이 아니면 404.

    문서가 남아 있거나 마지막 하나면 409다 — 요청 자체는 올바르고, 사용자가 취할
    조치(문서를 옮기거나 지우기)가 422와 다르다.

    응답 모델을 두지 않는 이유: 204에는 본문이 없다(문서 삭제와 같은 규칙).
    """
    await service.delete_workspace(workspace_id, current_user.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
