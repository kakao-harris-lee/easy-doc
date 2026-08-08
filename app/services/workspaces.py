"""작업 공간 만들기·이름 바꾸기·삭제 비즈니스 로직.

이 계층이 지키는 규칙은 셋이다.

1. **소유자 격리.** 남의 작업 공간과 없는 작업 공간을 구분하지 않는다(둘 다 404) —
   구분하면 식별자의 존재 여부가 새어 나가 다른 사용자의 활동을 추론할 수 있다.
2. **문서가 든 공간은 지우지 않는다.** 삭제로 문서를 함께 잃는 경로를 만들지 않고,
   "먼저 비우세요"라고 돌려준다(409). 마지막 판정은 FK가 한다(저장소 참고).
3. **작업 공간은 최소 한 개.** 전부 지우면 업로드가 갈 곳을 잃는다 — 마지막 하나는
   거절한다(409).

트랜잭션 경계는 이 계층이 소유한다 — 저장소는 flush까지만 하고 커밋 시점은 서비스가
정한다(auth·documents 서비스와 같은 규약).
"""

import uuid
from typing import Protocol

from app.exceptions import ConflictError, InvalidInputError, NotFoundError
from app.models.workspace import MAX_WORKSPACE_NAME_LENGTH, Workspace
from app.repositories.workspaces import WorkspaceSummary
from app.text import strip_control_chars

_NOT_FOUND_MESSAGE = "작업 공간을 찾을 수 없습니다"


class WorkspaceStore(Protocol):
    """WorkspaceService가 저장소에 요구하는 계약 (구현: WorkspaceRepository)."""

    async def create(self, *, user_id: uuid.UUID, name: str) -> Workspace:
        """작업 공간을 저장 대기 상태로 만든다(커밋하지 않는다). 이름이 겹치면 도메인 예외."""
        ...

    async def commit(self) -> None:
        """진행 중인 트랜잭션을 확정한다."""
        ...

    async def list_for_user(self, user_id: uuid.UUID) -> list[WorkspaceSummary]:
        """내 작업 공간을 만든 순서대로 돌려준다 (문서 수 포함)."""
        ...

    async def rename_for_user(
        self, workspace_id: uuid.UUID, user_id: uuid.UUID, *, name: str
    ) -> Workspace | None:
        """소유자를 확인하며 이름을 바꾼다(커밋하지 않는다). 없거나 남의 것이면 None."""
        ...

    async def delete_for_user(self, workspace_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """소유자를 확인하며 지운다(커밋하지 않는다). 없거나 남의 것이면 False."""
        ...


class WorkspaceService:
    """작업 공간 조회·생성·이름 변경·삭제."""

    def __init__(self, *, workspaces: WorkspaceStore) -> None:
        self._workspaces = workspaces

    async def list_workspaces(self, user_id: uuid.UUID) -> list[WorkspaceSummary]:
        """내 작업 공간을 만든 순서대로 돌려준다 (첫 번째가 기본 작업 공간이다)."""
        return await self._workspaces.list_for_user(user_id)

    async def create_workspace(self, user_id: uuid.UUID, *, name: str) -> Workspace:
        """작업 공간을 만든다.

        Raises:
            InvalidInputError: 이름이 비었거나 길이 상한을 넘었다.
            ConflictError: 같은 이름의 작업 공간이 이미 있다 (저장소가 판정).
        """
        workspace = await self._workspaces.create(user_id=user_id, name=_clean_name(name))
        await self._workspaces.commit()
        return workspace

    async def rename_workspace(
        self, workspace_id: uuid.UUID, user_id: uuid.UUID, *, name: str
    ) -> Workspace:
        """작업 공간 이름을 바꾼다.

        Raises:
            InvalidInputError: 이름이 비었거나 길이 상한을 넘었다.
            NotFoundError: 없거나 내 것이 아니다.
            ConflictError: 같은 이름의 작업 공간이 이미 있다 (저장소가 판정).
        """
        workspace = await self._workspaces.rename_for_user(
            workspace_id, user_id, name=_clean_name(name)
        )
        if workspace is None:
            raise NotFoundError(_NOT_FOUND_MESSAGE)
        await self._workspaces.commit()
        return workspace

    async def delete_workspace(self, workspace_id: uuid.UUID, user_id: uuid.UUID) -> None:
        """빈 작업 공간을 지운다.

        문서 수와 남은 개수를 목록 조회 한 번으로 함께 본다 — 삭제 판정에 필요한 사실이
        둘 다 그 응답에 들어 있어, 질의를 두 번 나눌 이유가 없다.

        Raises:
            NotFoundError: 없거나 내 것이 아니다.
            ConflictError: 문서가 남아 있거나, 마지막 남은 작업 공간이다.
        """
        summaries = await self._workspaces.list_for_user(user_id)
        target = next(
            (summary for summary in summaries if summary.workspace.id == workspace_id), None
        )
        if target is None:
            raise NotFoundError(_NOT_FOUND_MESSAGE)
        if target.document_count > 0:
            # 문서를 함께 지우는 편이 손은 덜 가지만, 되돌릴 수 없는 파기를 "정리"라는
            # 이름으로 실행하게 된다. 무엇이 사라지는지 사용자가 먼저 보게 한다.
            raise ConflictError("작업 공간에 문서가 남아 있습니다 — 먼저 비운 뒤 삭제해 주세요")
        if len(summaries) == 1:
            # 전부 지우면 업로드가 갈 곳을 잃고, 기본 작업 공간(가장 오래된 것)이라는
            # 기준도 가리킬 대상이 없어진다.
            raise ConflictError("작업 공간은 적어도 하나 있어야 합니다")
        if not await self._workspaces.delete_for_user(workspace_id, user_id):
            # 위 조회와 DELETE 사이에 사라진 경우다 — 없는 것과 같은 결론을 알린다.
            raise NotFoundError(_NOT_FOUND_MESSAGE)
        await self._workspaces.commit()


def _clean_name(name: str) -> str:
    """이름을 정규화하고 검증한다.

    제어문자를 먼저 걷어낸다 — 남겨두면 목록·내보내기(XML)에서 뒤늦게 터진다
    (app/text.py). 걷어낸 뒤에 빈 이름·길이를 판정해야 "제어문자만 담긴 이름"이 빈
    이름과 같은 취급을 받는다.

    상한을 넘는 이름은 자르지 않고 거절한다 — 사용자가 직접 적은 이름이라, 말없이
    자르면 그 사람이 담은 뜻이 사라진다(문서 제목의 자동 유도와 다른 자리다).

    Raises:
        InvalidInputError: 이름이 비었거나 길이 상한을 넘었다. 메시지에 입력값을
            되풀이하지 않는다(개인정보가 섞일 수 있다).
    """
    cleaned = strip_control_chars(name).strip()
    if not cleaned:
        raise InvalidInputError("작업 공간 이름을 입력해 주세요")
    if len(cleaned) > MAX_WORKSPACE_NAME_LENGTH:
        raise InvalidInputError(f"작업 공간 이름은 {MAX_WORKSPACE_NAME_LENGTH}자 이하여야 합니다")
    return cleaned
