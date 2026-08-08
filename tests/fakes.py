"""테스트 대역 모음.

서비스·API 테스트가 DB 없이 돌 수 있게 하는 최소 구현이다. 실제 저장소와 같은
계약(`app.services.auth.UserStore`, `app.services.documents.DocumentStore` 등)을
만족해야 하며, 계약이 어긋나면 서비스에 넘기는 지점에서 mypy가 잡는다.

문서 쪽 대역은 `journal`(호출 순서 기록)을 공유한다. "INSERT → commit → enqueue"
순서가 이 계층의 핵심 규칙이라, 결과만 보고는 확인할 수 없기 때문이다.
"""

import uuid
from datetime import UTC, datetime, timedelta

from app.exceptions import ConflictError, EmailAlreadyRegisteredError
from app.models.conversion import Conversion, ConversionStatus
from app.models.document import RETENTION_DAYS, Document
from app.models.user import User
from app.models.workspace import Workspace
from app.repositories.documents import DocumentPage, DocumentSummary
from app.repositories.workspaces import WorkspaceSummary


class FakeUserRepository:
    """딕셔너리 기반 사용자 저장소 대역.

    실제 저장소처럼 create는 확정하지 않고 commit이 따로 호출된다. 트랜잭션 경계를
    서비스가 제대로 소유하는지 보려고 commit 호출 횟수를 기록한다.
    """

    def __init__(self) -> None:
        self._users: dict[str, User] = {}
        self.commits = 0

    async def create(self, *, email: str, password_hash: str) -> User:
        """저장한다. 같은 이메일이면 실제 저장소와 같은 도메인 예외를 던진다."""
        if email in self._users:
            raise EmailAlreadyRegisteredError("이미 가입된 이메일입니다")
        user = User(
            id=uuid.uuid4(),
            email=email,
            password_hash=password_hash,
            created_at=datetime.now(UTC),
        )
        # flush 이후 같은 트랜잭션 안에서는 조회되는 상태를 흉내 낸다.
        self._users[email] = user
        return user

    async def commit(self) -> None:
        """커밋 호출을 기록한다."""
        self.commits += 1

    async def get_by_email(self, email: str) -> User | None:
        """이메일로 찾는다. 없으면 None."""
        return self._users.get(email)

    async def get_by_id(self, user_id: uuid.UUID) -> User | None:
        """식별자로 찾는다. 없으면 None."""
        return next((user for user in self._users.values() if user.id == user_id), None)

    def add(self, user: User) -> None:
        """이미 만들어진 사용자를 그대로 넣는다.

        가입 흐름을 거치지 않는 테스트용이다 — argon2 해싱은 의도적으로 느려서
        (건당 약 23ms) 인증이 주제가 아닌 테스트마다 치를 비용이 아니다.
        """
        self._users[user.email] = user


class FakeDocumentStore:
    """문서 저장소 대역 (`app.services.documents.DocumentStore`).

    실제 저장소처럼 create는 확정하지 않고 commit이 따로 호출된다.
    """

    def __init__(self, journal: list[str] | None = None) -> None:
        self.journal: list[str] = [] if journal is None else journal
        self.documents: dict[uuid.UUID, Document] = {}
        #: FakeConversionStore가 함께 쓰는 공간. 두 대역이 하나의 세션을 흉내 낸다.
        self.conversions_by_document: dict[uuid.UUID, Conversion] = {}
        self.commits = 0

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
        """문서를 만든다. DB가 채워 줄 시각 컬럼은 여기서 대신 채운다."""
        self.journal.append("create_document")
        now = datetime.now(UTC)
        document = Document(
            id=uuid.uuid4(),
            user_id=user_id,
            workspace_id=workspace_id,
            title=title,
            source_format=source_format,
            source_text_encrypted=source_text_encrypted,
            char_count=char_count,
            retention_expires_at=now + timedelta(days=RETENTION_DAYS),
            created_at=now,
        )
        self.documents[document.id] = document
        return document

    async def commit(self) -> None:
        """커밋 호출을 기록한다."""
        self.journal.append("commit")
        self.commits += 1

    async def delete_for_user(self, document_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """소유자를 확인하며 문서를 지운다. 실제 저장소의 FK CASCADE를 손으로 흉내 낸다."""
        self.journal.append("delete_document")
        document = self.documents.get(document_id)
        if document is None or document.user_id != user_id:
            return False
        del self.documents[document_id]
        # DB에서는 ondelete=CASCADE가 변환 행을 함께 지운다.
        self.conversions_by_document.pop(document_id, None)
        return True

    async def list_for_user(
        self,
        user_id: uuid.UUID,
        *,
        limit: int,
        offset: int,
        workspace_id: uuid.UUID | None = None,
    ) -> DocumentPage:
        """내 문서를 최신순으로 돌려준다 (실제 저장소의 정렬·페이지네이션과 같은 규칙)."""
        mine = sorted(
            (
                document
                for document in self.documents.values()
                if document.user_id == user_id
                # 실제 저장소와 같이 소유자 조건은 작업 공간 필터와 별개로 늘 건다.
                and (workspace_id is None or document.workspace_id == workspace_id)
            ),
            key=lambda document: (document.created_at, document.id),
            reverse=True,
        )
        # 실제 저장소와 같이 한 건 더 떠서 다음 페이지 유무를 판정한다.
        window = mine[offset : offset + limit + 1]
        return DocumentPage(
            items=[
                DocumentSummary(
                    document=document,
                    latest_conversion=self.conversions_by_document.get(document.id),
                )
                for document in window[:limit]
            ],
            has_more=len(window) > limit,
        )


class FakeWorkspaceStore:
    """작업 공간 저장소 대역.

    세 계약을 한 클래스로 만족한다 — `WorkspaceStore`(서비스), `WorkspaceLookup`(문서
    서비스), `WorkspaceCreator`(인증 서비스). 실제 저장소도 같은 한 구현이 셋을
    만족하므로, 대역을 쪼개면 진짜와 다른 구조를 검증하게 된다.

    `documents`를 주면 문서 수를 그 대역에서 센다 — 삭제 거절("먼저 비우세요")과
    목록 응답이 실제와 같은 값을 보게 하려면 두 대역이 같은 세계를 봐야 한다.
    """

    def __init__(self, documents: FakeDocumentStore | None = None) -> None:
        self._documents = documents
        self.workspaces: dict[uuid.UUID, Workspace] = {}
        self.commits = 0
        #: 만든 순서를 created_at에 반영하기 위한 일련번호. 실제 DB는 트랜잭션 시각을
        #: 쓰지만, 대역에서는 같은 마이크로초에 여러 개가 만들어져 "가장 오래된 것"이
        #: 흔들릴 수 있다.
        self._sequence = 0

    def add(self, user_id: uuid.UUID, name: str) -> Workspace:
        """가입 흐름을 거치지 않고 작업 공간을 넣는다 (FakeUserRepository.add와 같은 용도)."""
        self._sequence += 1
        workspace = Workspace(
            id=uuid.uuid4(),
            user_id=user_id,
            name=name,
            created_at=datetime.now(UTC) + timedelta(microseconds=self._sequence),
        )
        self.workspaces[workspace.id] = workspace
        return workspace

    async def create(self, *, user_id: uuid.UUID, name: str) -> Workspace:
        """저장한다. 같은 사용자에게 같은 이름이 있으면 실제 저장소와 같은 도메인 예외."""
        if self._find_by_name(user_id, name) is not None:
            raise ConflictError("같은 이름의 작업 공간이 이미 있습니다")
        return self.add(user_id, name)

    async def commit(self) -> None:
        """커밋 호출을 기록한다."""
        self.commits += 1

    async def list_for_user(self, user_id: uuid.UUID) -> list[WorkspaceSummary]:
        """내 작업 공간을 만든 순서대로 돌려준다 (문서 수 포함)."""
        return [
            WorkspaceSummary(workspace=workspace, document_count=self._count(workspace.id))
            for workspace in self._mine(user_id)
        ]

    async def get_for_user(self, workspace_id: uuid.UUID, user_id: uuid.UUID) -> Workspace | None:
        """소유자를 확인하며 찾는다. 남의 것·없는 것 모두 None."""
        workspace = self.workspaces.get(workspace_id)
        if workspace is None or workspace.user_id != user_id:
            return None
        return workspace

    async def get_default_for_user(self, user_id: uuid.UUID) -> Workspace | None:
        """가장 먼저 만든 작업 공간. 하나도 없으면 None."""
        return next(iter(self._mine(user_id)), None)

    async def rename_for_user(
        self, workspace_id: uuid.UUID, user_id: uuid.UUID, *, name: str
    ) -> Workspace | None:
        """소유자를 확인하며 이름을 바꾼다. 같은 이름이 있으면 실제 저장소와 같은 예외."""
        workspace = await self.get_for_user(workspace_id, user_id)
        if workspace is None:
            return None
        existing = self._find_by_name(user_id, name)
        if existing is not None and existing.id != workspace_id:
            raise ConflictError("같은 이름의 작업 공간이 이미 있습니다")
        workspace.name = name
        return workspace

    async def delete_for_user(self, workspace_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """소유자를 확인하며 지운다. 문서가 남아 있으면 FK 위반을 흉내 낸 도메인 예외."""
        workspace = await self.get_for_user(workspace_id, user_id)
        if workspace is None:
            return False
        if self._count(workspace_id) > 0:
            raise ConflictError("작업 공간에 문서가 남아 있습니다 — 먼저 비운 뒤 삭제해 주세요")
        del self.workspaces[workspace_id]
        return True

    def _mine(self, user_id: uuid.UUID) -> list[Workspace]:
        """내 작업 공간을 오래된 순으로 (실제 저장소와 같은 정렬)."""
        return sorted(
            (workspace for workspace in self.workspaces.values() if workspace.user_id == user_id),
            key=lambda workspace: (workspace.created_at, workspace.id),
        )

    def _find_by_name(self, user_id: uuid.UUID, name: str) -> Workspace | None:
        """같은 사용자 안에서 이름으로 찾는다 (unique 제약이 보는 범위와 같다)."""
        return next(
            (
                workspace
                for workspace in self.workspaces.values()
                if workspace.user_id == user_id and workspace.name == name
            ),
            None,
        )

    def _count(self, workspace_id: uuid.UUID) -> int:
        """작업 공간에 담긴 문서 수."""
        if self._documents is None:
            return 0
        return sum(
            1
            for document in self._documents.documents.values()
            if document.workspace_id == workspace_id
        )


class FakeConversionStore:
    """변환 저장소 대역 (`app.services.documents.ConversionStore`).

    문서 저장소와 같은 "세션"을 공유한다 — commit을 문서 저장소에 위임해, 커밋 한 번이
    둘을 함께 확정한다는 실제 동작을 그대로 흉내 낸다.
    """

    def __init__(self, documents: FakeDocumentStore) -> None:
        self._documents = documents
        self.conversions: dict[uuid.UUID, Conversion] = {}

    async def create_pending(self, document_id: uuid.UUID) -> Conversion:
        """대기 상태 변환을 만든다."""
        self._documents.journal.append("create_conversion")
        now = datetime.now(UTC)
        conversion = Conversion(
            id=uuid.uuid4(),
            document_id=document_id,
            status=ConversionStatus.PENDING,
            missing_placeholders=[],
            created_at=now,
            updated_at=now,
        )
        self.conversions[conversion.id] = conversion
        self._documents.conversions_by_document[document_id] = conversion
        return conversion

    async def get_for_user(self, conversion_id: uuid.UUID, user_id: uuid.UUID) -> Conversion | None:
        """소유자를 확인하며 변환을 찾는다 (실제 저장소의 documents 조인에 대응)."""
        conversion = self.conversions.get(conversion_id)
        if conversion is None:
            return None
        document = self._documents.documents.get(conversion.document_id)
        if document is None or document.user_id != user_id:
            return None
        return conversion

    async def get_for_user_with_document(
        self, conversion_id: uuid.UUID, user_id: uuid.UUID
    ) -> tuple[Conversion, Document] | None:
        """소유자를 확인하며 변환과 원본 문서를 함께 돌려준다 (내보내기용)."""
        conversion = await self.get_for_user(conversion_id, user_id)
        if conversion is None:
            return None
        return conversion, self._documents.documents[conversion.document_id]

    async def save_review(self, conversion: Conversion, *, edited_text_encrypted: bytes) -> bool:
        """검수 수정본을 기록한다. 실제 저장소와 같이 AI 초안은 건드리지 않는다.

        완료 상태 판정도 실제 저장소(조건부 UPDATE)와 같은 규칙으로 흉내 낸다.
        """
        if conversion.status != ConversionStatus.DONE:
            return False
        conversion.edited_text_encrypted = edited_text_encrypted
        # 실제 저장소는 DB 시계(now())로 찍는다. 대역에는 DB가 없으므로 여기서 채운다.
        conversion.reviewed_at = datetime.now(UTC)
        return True

    async def mark_failed(self, conversion: Conversion, failure_code: str) -> None:
        """실패를 기록한다."""
        conversion.status = ConversionStatus.FAILED
        conversion.failure_code = failure_code

    async def commit(self) -> None:
        """문서 저장소와 같은 트랜잭션을 확정한다."""
        await self._documents.commit()


class FakeConversionWorkerStore:
    """워커용 변환 저장소 대역 (`app.workers.tasks.ConversionWorkerStore`).

    상태 전이 순서가 이 계층의 관찰 대상이라(processing을 커밋해야 조회 API가 진행
    상태를 보여준다) 호출 순서를 journal에 남긴다.

    장애 주입 인자:

    - `preempted`: 선점에 실패한 것처럼 mark_processing이 False를 돌려준다(읽은 뒤
      UPDATE 사이에 다른 워커가 끝낸 상황).
    - `load_error`: 조회 시 던질 예외 (DB 일시 장애).
    - `done_error`: 결과 저장 시 던질 예외. 테스트가 중간에 None으로 지워 재시도
      성공을 흉내 낼 수 있다.
    """

    def __init__(
        self,
        pair: tuple[Conversion, Document] | None = None,
        *,
        preempted: bool = False,
        load_error: Exception | None = None,
        done_error: BaseException | None = None,
    ) -> None:
        self._pair = pair
        self._preempted = preempted
        self._load_error = load_error
        self.done_error = done_error
        self.journal: list[str] = []
        self.commits = 0

    async def get_with_document(
        self, conversion_id: uuid.UUID
    ) -> tuple[Conversion, Document] | None:
        """준비된 변환과 문서를 돌려준다. 식별자가 다르면 None."""
        if self._load_error is not None:
            raise self._load_error
        if self._pair is None or self._pair[0].id != conversion_id:
            return None
        return self._pair

    async def mark_processing(self, conversion: Conversion) -> bool:
        """처리 시작을 기록한다. 실제 저장소의 조건부 UPDATE와 같은 규칙으로 판정한다."""
        self.journal.append("processing")
        if self._preempted or conversion.status == ConversionStatus.DONE:
            return False
        conversion.status = ConversionStatus.PROCESSING
        conversion.failure_code = None
        return True

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
        """성공 결과를 기록한다."""
        if self.done_error is not None:
            raise self.done_error
        self.journal.append("done")
        conversion.status = ConversionStatus.DONE
        conversion.easy_text_encrypted = easy_text_encrypted
        conversion.masked_items_encrypted = masked_items_encrypted
        conversion.missing_placeholders = missing_placeholders
        conversion.provider_name = provider_name
        conversion.model = model
        conversion.input_tokens = input_tokens
        conversion.output_tokens = output_tokens
        # 실제 저장소와 같이 이전 시도의 실패 사유를 지운다.
        conversion.failure_code = None

    async def mark_failed(self, conversion: Conversion, failure_code: str) -> None:
        """실패를 기록한다."""
        self.journal.append("failed")
        conversion.status = ConversionStatus.FAILED
        conversion.failure_code = failure_code

    async def commit(self) -> None:
        """커밋 호출을 기록한다."""
        self.journal.append("commit")
        self.commits += 1


class FakeRetentionStore:
    """보존 만료 파기 저장소 대역 (`app.workers.tasks.RetentionStore`).

    `batches`는 delete_expired가 차례로 돌려줄 삭제 건수다 — 목록이 바닥나면 0을
    돌려준다(더 지울 것이 없는 상태). 배치마다 커밋하는지 보려고 호출 순서를 남긴다.

    `error`를 주면 첫 삭제 시도에서 그 예외를 던진다 (DB 장애 경로 테스트용).
    """

    def __init__(self, batches: list[int] | None = None, *, error: Exception | None = None) -> None:
        self._batches = list(batches or [])
        self._error = error
        self.journal: list[str] = []
        self.limits: list[int] = []

    async def delete_expired(self, *, limit: int) -> int:
        """준비된 배치를 차례로 돌려준다. 요청받은 상한도 기록한다."""
        self.journal.append("delete_expired")
        self.limits.append(limit)
        if self._error is not None:
            raise self._error
        return self._batches.pop(0) if self._batches else 0

    async def commit(self) -> None:
        """커밋 호출을 기록한다."""
        self.journal.append("commit")


class FakeTaskQueue:
    """작업 큐 대역 (`app.queue.TaskQueue`).

    `error`를 주면 등록 시 그 예외를 던진다 — Redis 장애 경로 테스트용.
    """

    def __init__(self, journal: list[str] | None = None, error: Exception | None = None) -> None:
        self.journal: list[str] = [] if journal is None else journal
        self.jobs: list[tuple[str, tuple[object, ...]]] = []
        self.job_ids: list[str | None] = []
        self._error = error

    async def enqueue(self, name: str, *args: object, job_id: str | None = None) -> None:
        """등록 시도를 기록한다. 실패 주입 시에도 시도 자체는 기록한다."""
        self.journal.append("enqueue")
        if self._error is not None:
            raise self._error
        self.jobs.append((name, args))
        self.job_ids.append(job_id)
