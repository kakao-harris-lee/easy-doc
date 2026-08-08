"""documents.retention_expires_at 인덱스

Revision ID: 0005
Revises: 0004
Create Date: 2026-08-08 09:58:12.401877

보존 만료 문서 삭제 잡(app/workers/tasks.py의 purge_expired_documents)이 매일
`WHERE retention_expires_at < now() ORDER BY retention_expires_at LIMIT n`으로 대상을
고른다. 인덱스가 없으면 그 조회가 documents 전수 스캔 + 정렬이 되고, 문서가 쌓일수록
새벽 잡 한 번이 테이블을 오래 붙잡는다.

부분 인덱스(WHERE 절)를 쓰지 않는 이유: 기준값이 `now()`라 조건이 매일 움직이고,
IMMUTABLE 하지 않은 함수는 인덱스 술어에 넣을 수 없다.
"""

from collections.abc import Sequence

from alembic import op

# alembic이 사용하는 리비전 식별자.
revision: str = "0005"
down_revision: str | Sequence[str] | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """스키마를 적용한다."""
    op.create_index(
        op.f("ix_documents_retention_expires_at"),
        "documents",
        ["retention_expires_at"],
        unique=False,
    )


def downgrade() -> None:
    """스키마 변경을 되돌린다."""
    op.drop_index(op.f("ix_documents_retention_expires_at"), table_name="documents")
