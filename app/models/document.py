"""업로드 문서 ORM 모델.

원문은 평문으로 저장하지 않는다 — `source_text_encrypted`는 `TextCipher`가 만든
Fernet 토큰이다 (master-plan 3.2). 파일명은 저장하지 않는다: 파일명 자체가 개인정보일
수 있고(`홍길동_주민등록등본.pdf`), 화면 표시에 필요한 것은 제목뿐이다.
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, LargeBinary, SmallInteger, String, func, text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base
from app.privacy.crypto import CURRENT_KEY_VERSION

#: 제목 최대 길이(컬럼 상한과 같은 값). 서비스가 유도·검증에 함께 쓴다.
MAX_TITLE_LENGTH = 255

#: 업로드 원문 보존 기간(일). 지난 문서는 자동 삭제된다 (master-plan 3.2).
RETENTION_DAYS = 30

#: 보존 만료 시각의 DB 기본값. 모델과 마이그레이션이 **같은 문자열**을 써야
#: alembic이 매번 없는 차이를 만들어내지 않는다(compare_server_default=True).
RETENTION_DEFAULT_SQL = f"now() + interval '{RETENTION_DAYS} days'"


class Document(Base):
    """사용자가 올린(또는 붙여넣은) 원문 문서."""

    __tablename__ = "documents"

    # users와 같은 uuid4를 쓴다. 시간 정렬형(uuid7)이 B-tree 삽입에 유리하지만
    # 표준 라이브러리에 없어 의존성을 하나 더 지게 되고, 파일럿 규모(문서 수만 건)에서
    # 인덱스 단편화는 측정되지 않는 수준이다. 필요해지면 그때 바꾼다.
    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    # ondelete=CASCADE: 계정을 지우면 문서도 함께 사라져야 한다(개인정보 삭제 요구).
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    title: Mapped[str] = mapped_column(String(MAX_TITLE_LENGTH))
    #: "text" | "docx" | "pdf" | "hwpx". 입력 경로 추적·통계용.
    source_format: Mapped[str] = mapped_column(String(16))
    #: Fernet 암호문. 평문 원문을 컬럼에 넣는 코드는 작성하지 않는다.
    source_text_encrypted: Mapped[bytes] = mapped_column(LargeBinary)
    #: 위 암호문을 만든 키의 세대. 키 교체 시 재암호화 대상을 고르는 유일한 단서다
    #: (app/privacy/crypto.py의 CURRENT_KEY_VERSION 참고).
    key_version: Mapped[int] = mapped_column(
        SmallInteger, default=CURRENT_KEY_VERSION, server_default=text("1")
    )
    #: 공백 포함 문자 수. 크레딧 환산(1,000자=1크레딧) 기준값을 저장 시점에 고정한다 —
    #: 나중에 본문을 복호화해 다시 세지 않아도 과금 근거가 남는다.
    char_count: Mapped[int]
    #: 보존 만료 시각. 지난 문서는 매일 도는 파기 잡이 지운다
    #: (app/workers/tasks.py의 purge_expired_documents).
    #: index: 그 잡이 `WHERE retention_expires_at < now()`로 대상을 고른다 — 없으면
    #: 매일 새벽 전수 스캔이 된다.
    retention_expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=text(RETENTION_DEFAULT_SQL), index=True
    )
    # timezone=True: 보존 만료 판정이 서버 타임존에 좌우되지 않게 한다.
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
