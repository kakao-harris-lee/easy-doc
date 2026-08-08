"""workspaces 테이블 + documents.workspace_id

Revision ID: 0006
Revises: 0005
Create Date: 2026-08-08 12:10:33.902114

기존 데이터가 있는 DB에서도 한 번에 올라가야 한다. 그래서 세 걸음으로 나눈다.

1. 사용자마다 "기본 작업 공간"을 만든다 (아직 문서를 하나도 잃지 않는 단계).
2. 기존 문서를 소유자의 그 작업 공간으로 귀속시킨다 (nullable 컬럼에 백필).
3. 그제서야 NOT NULL로 승격하고 FK·인덱스를 건다.

이름 문자열("기본 작업 공간")을 애플리케이션 상수(DEFAULT_WORKSPACE_NAME)에서
import 하지 않고 SQL에 직접 적는다 — 마이그레이션은 적용 시점 스키마의 스냅샷이라,
나중에 상수를 바꿔도 이미 적용된 이 리비전의 내용이 따라 변하면 안 된다 (0003과 같은 규칙).
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# alembic이 사용하는 리비전 식별자.
revision: str = "0006"
down_revision: str | Sequence[str] | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """스키마를 적용한다."""
    op.create_table(
        "workspaces",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(length=50), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        # ondelete=CASCADE: 계정을 지우면 작업 공간도 함께 사라진다(documents와 같은 규칙).
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_workspaces_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_workspaces")),
        # 같은 사용자 안에서만 이름 중복을 막는다. 이름을 손으로 적는 이유는 모델과 같다
        # (naming convention의 uq 규칙은 첫 컬럼만 이름에 담는다).
        sa.UniqueConstraint("user_id", "name", name="uq_workspaces_user_id_name"),
    )
    # 목록 조회와 소유자 검증이 전부 user_id로 들어온다.
    op.create_index(op.f("ix_workspaces_user_id"), "workspaces", ["user_id"], unique=False)

    # --- 백필 -----------------------------------------------------------------
    # 1) 사용자마다 기본 작업 공간 하나. gen_random_uuid()는 PostgreSQL 13부터 내장이다
    #    (파일럿 스택은 pg16 — docker-compose.yml).
    op.execute(
        sa.text(
            "INSERT INTO workspaces (id, user_id, name, created_at)"
            " SELECT gen_random_uuid(), id, '기본 작업 공간', now() FROM users"
        )
    )

    # 2) 기존 문서를 소유자의 작업 공간으로 귀속시킨다. 이 시점에는 사용자당 작업 공간이
    #    정확히 하나뿐이라 조인 결과가 갈리지 않는다.
    op.add_column("documents", sa.Column("workspace_id", sa.Uuid(), nullable=True))
    op.execute(
        sa.text(
            "UPDATE documents AS d SET workspace_id = w.id"
            " FROM workspaces AS w WHERE w.user_id = d.user_id"
        )
    )

    # 3) 백필이 끝난 뒤에야 NOT NULL로 승격한다. 여기서 실패한다면 소유자가 없는 문서가
    #    남아 있다는 뜻이고, 그건 조용히 넘길 문제가 아니다(FK가 이미 막고 있어야 한다).
    op.alter_column("documents", "workspace_id", nullable=False)
    op.create_index(op.f("ix_documents_workspace_id"), "documents", ["workspace_id"], unique=False)
    # ondelete를 주지 않는다(NO ACTION) — 문서가 든 작업 공간은 DB가 지우지 못하게 막는다
    # (사유는 app/models/document.py).
    op.create_foreign_key(
        op.f("fk_documents_workspace_id_workspaces"),
        "documents",
        "workspaces",
        ["workspace_id"],
        ["id"],
    )


def downgrade() -> None:
    """스키마 변경을 되돌린다.

    문서는 남기고 귀속만 푼다 — 되돌리기가 사용자 데이터를 지우면 안 된다.
    """
    op.drop_constraint(
        op.f("fk_documents_workspace_id_workspaces"), "documents", type_="foreignkey"
    )
    op.drop_index(op.f("ix_documents_workspace_id"), table_name="documents")
    op.drop_column("documents", "workspace_id")
    op.drop_index(op.f("ix_workspaces_user_id"), table_name="workspaces")
    op.drop_table("workspaces")
