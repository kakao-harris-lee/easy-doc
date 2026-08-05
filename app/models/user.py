"""사용자 계정 ORM 모델."""

import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base


class User(Base):
    """이메일로 가입한 사용자.

    비밀번호는 argon2 해시만 저장한다 — 평문·복호화 가능한 형태로 두지 않는다.
    """

    __tablename__ = "users"
    __table_args__ = (
        # 이메일 정규화는 AuthService가 하지만, 서비스를 거치지 않는 경로(운영 스크립트,
        # 데이터 이관)가 대소문자만 다른 계정을 만들면 unique 인덱스가 무력해진다.
        # 제약 이름은 naming convention이 ck_users_email_lowercase로 확장한다.
        CheckConstraint("email = lower(email)", name="email_lowercase"),
    )

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    # timezone=True: 보존 기간(기본 30일) 만료 계산이 서버 타임존에 좌우되지 않게
    # UTC 오프셋까지 저장한다 (master-plan 3.2).
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
