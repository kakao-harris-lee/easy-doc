"""변환 작업 ORM 모델.

한 문서에 변환 작업 하나가 원칙이지만(Lean MVP), 재변환을 대비해 별도 테이블로 둔다.
결과 본문과 마스킹 항목은 원문과 마찬가지로 암호화해 저장한다 — 변환 결과에도
플레이스홀더로 가려지지 않은 개인정보가 남을 수 있고, `masked_items`는 아예 원문
개인정보 그 자체다 (master-plan 3.2).
"""

import uuid
from datetime import datetime
from enum import StrEnum

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    LargeBinary,
    SmallInteger,
    String,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base
from app.privacy.crypto import CURRENT_KEY_VERSION


class ConversionStatus(StrEnum):
    """변환 작업 상태.

    API·워커·DB CHECK 제약이 이 정의 하나를 공유한다 — 문자열을 각자 적어두면
    오타가 런타임까지 살아남는다.
    """

    PENDING = "pending"
    PROCESSING = "processing"
    DONE = "done"
    FAILED = "failed"


#: status CHECK 제약 표현식. 마이그레이션에는 같은 뜻의 SQL을 **직접** 적는다 —
#: 마이그레이션은 그 시점 스키마의 스냅샷이라 애플리케이션 상수를 따라 변하면 안 된다.
STATUS_CHECK_SQL = "status IN ('pending', 'processing', 'done', 'failed')"

#: 실패 사유 코드 최대 길이. 예외 클래스명만 담으므로 짧다(본문·메시지 금지).
MAX_FAILURE_CODE_LENGTH = 64


class Conversion(Base):
    """문서 한 건에 대한 쉬운 글 변환 작업과 그 결과."""

    __tablename__ = "conversions"
    __table_args__ = (
        # 상태 오타는 목록·상세 화면을 조용히 망가뜨린다. 애플리케이션 밖 경로(운영
        # 스크립트, 수동 복구 SQL)까지 막으려면 DB가 마지막 방어선이어야 한다.
        # 제약 이름은 naming convention이 ck_conversions_status_valid로 확장한다.
        CheckConstraint(STATUS_CHECK_SQL, name="status_valid"),
    )

    # PK 선택 사유는 app/models/document.py 참고 (users와 같은 uuid4).
    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    document_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("documents.id", ondelete="CASCADE"), index=True
    )
    status: Mapped[str] = mapped_column(
        String(16), default=ConversionStatus.PENDING, server_default=text("'pending'")
    )
    #: 변환 결과(Fernet 암호문). 완료 전에는 NULL.
    easy_text_encrypted: Mapped[bytes | None] = mapped_column(LargeBinary)
    #: 담당자 검수 수정본(Fernet 암호문). 저장 전에는 NULL이고, 있으면 이 값이 최종본이다.
    #:
    #: AI 초안(easy_text_encrypted)을 덮어쓰지 않고 컬럼을 따로 두는 이유는 **수정률
    #: KPI**(초안 대비 편집 비율, master-plan 7장) 때문이다 — 초안을 덮어쓰는 순간
    #: "사람이 얼마나 고쳤는가"의 기준선이 사라지고 되살릴 방법이 없다.
    edited_text_encrypted: Mapped[bytes | None] = mapped_column(LargeBinary)
    #: 마스킹 항목 JSON을 암호화한 값. 원문 개인정보가 그대로 들어 있다.
    masked_items_encrypted: Mapped[bytes | None] = mapped_column(LargeBinary)
    #: 위 두 암호문을 만든 키의 세대 (documents.key_version과 같은 이유 — 문서와 변환이
    #: 서로 다른 시점에 암호화되므로 키 교체 중이면 세대가 갈릴 수 있다).
    key_version: Mapped[int] = mapped_column(
        SmallInteger, default=CURRENT_KEY_VERSION, server_default=text("1")
    )
    #: 결과에서 사라진 자리표시자 라벨 목록. 라벨뿐이라 개인정보가 아니므로 평문 JSONB다
    #: (검수 화면 경고용 — app/services/conversion.py 참고).
    missing_placeholders: Mapped[list[str]] = mapped_column(
        JSONB, default=list, server_default=text("'[]'::jsonb")
    )
    provider_name: Mapped[str | None] = mapped_column(String(32))
    model: Mapped[str | None] = mapped_column(String(128))
    input_tokens: Mapped[int | None]
    output_tokens: Mapped[int | None]
    #: 실패 사유 코드 = 예외 클래스명. 문서 본문·모델 응답은 절대 담지 않는다.
    failure_code: Mapped[str | None] = mapped_column(String(MAX_FAILURE_CODE_LENGTH))
    #: 검수 수정본을 마지막으로 저장한 시각. NULL이면 아직 사람이 손대지 않은 초안이다
    #: (목록의 "검수 여부" 표시와 수정률 KPI 집계가 이 값을 본다).
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    # onupdate: UPDATE 문에 now()를 실어 보낸다(애플리케이션 시계가 아니라 DB 시계).
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
