"""users 테이블 + pgvector 확장

Revision ID: 0001
Revises:
Create Date: 2026-08-06 06:28:07.871328

"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# alembic이 사용하는 리비전 식별자.
revision: str = "0001"
down_revision: str | Sequence[str] | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """스키마를 적용한다."""
    # pgvector 이미지는 확장 파일을 제공할 뿐, 데이터베이스에 설치까지 해주지는 않는다.
    # 쉬운 말 사전 RAG(master-plan P0-5)에서 vector 컬럼을 쓰게 되므로, 그때 로컬만
    # 수동 설치되는 상황이 생기지 않도록 스키마 이력의 맨 앞에서 한 번 보장한다
    # (로컬·CI·운영이 같은 명령으로 같은 상태가 된다).
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("email", sa.String(length=255), nullable=False),
        sa.Column("password_hash", sa.String(length=255), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_users")),
    )
    # unique 인덱스 하나가 유일성 제약과 조회 인덱스를 겸한다(로그인 시 이메일 조회).
    op.create_index(op.f("ix_users_email"), "users", ["email"], unique=True)


def downgrade() -> None:
    """스키마 변경을 되돌린다.

    vector 확장은 되돌리지 않는다 — 데이터베이스 전역 공유 자원이라 다른 스키마·
    테이블이 이미 쓰고 있을 수 있고, DROP은 그것들까지 깨뜨린다.
    """
    op.drop_index(op.f("ix_users_email"), table_name="users")
    op.drop_table("users")
