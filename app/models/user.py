"""사용자 계정 ORM 모델."""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class User(Base):
    """이메일로 가입한 사용자.

    비밀번호는 argon2 해시만 저장한다 — 평문·복호화 가능한 형태로 두지 않는다.
    """

    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    # timezone=True: 보존 기간(기본 30일) 만료 계산이 서버 타임존에 좌우되지 않게
    # UTC 오프셋까지 저장한다 (master-plan 3.2).
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
