"""작업 공간 ORM 모델.

문서를 나눠 담는 상자다. **소유자는 사용자 한 명뿐이다** — 팀 공유는 Lean MVP 범위
밖(P1)이라, 접근 검증은 documents와 같은 `user_id` 조건 하나로 끝난다. 나중에 공유가
생기면 멤버십 테이블이 추가되고 그때 조건이 바뀐다.

이름에는 사용자가 적은 문자열이 담긴다(개인정보가 섞일 수 있다) — 로그에 남기지 않고,
목록 응답에는 캐시 금지 헤더를 붙인다(app/api/workspaces.py).
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base

#: 이름 최대 길이(컬럼 상한과 같은 값). 서비스가 검증에 함께 쓴다.
MAX_WORKSPACE_NAME_LENGTH = 50

#: 가입 시 자동으로 만들어 주는 첫 작업 공간의 이름. 마이그레이션 0006의 백필도
#: **같은 문자열**을 쓴다(그쪽은 이 상수를 import 하지 않는다 — 마이그레이션은 그 시점
#: 스키마의 스냅샷이라 애플리케이션 상수가 바뀌어도 따라 변하면 안 된다).
DEFAULT_WORKSPACE_NAME = "기본 작업 공간"


class Workspace(Base):
    """사용자가 문서를 나눠 담는 작업 공간."""

    __tablename__ = "workspaces"
    __table_args__ = (
        # 같은 사용자 안에서만 이름이 겹치지 않으면 된다 — 남이 쓰는 이름 때문에
        # 내가 이름을 못 짓는 일이 없어야 한다. 위반은 저장소가 23505로 잡아
        # 409("같은 이름이 이미 있습니다")로 바꾼다.
        #
        # 이름을 손으로 적는 이유: naming convention의 uq 규칙은
        # `uq_%(table_name)s_%(column_0_name)s`라 첫 컬럼만 보고 `uq_workspaces_user_id`가
        # 된다 — 두 컬럼 제약인데 이름이 그 사실을 숨긴다. 규칙에 %(constraint_name)s가
        # 없으므로 여기서 준 이름이 그대로 쓰인다.
        UniqueConstraint("user_id", "name", name="uq_workspaces_user_id_name"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    # ondelete=CASCADE: 계정을 지우면 작업 공간도 함께 사라진다(documents와 같은 규칙).
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(MAX_WORKSPACE_NAME_LENGTH))
    # timezone=True: 기본 작업 공간을 "가장 오래된 것"으로 고르므로(app/repositories/
    # workspaces.py) 이 값의 정렬이 서버 타임존에 좌우되면 안 된다.
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
