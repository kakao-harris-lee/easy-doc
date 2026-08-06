"""documents·conversions 테이블

Revision ID: 0003
Revises: 0002
Create Date: 2026-08-06 08:53:09.155693

원문·변환 결과·마스킹 항목은 bytea(Fernet 암호문)로 저장한다 — 이 테이블을 직접
조회해도 본문이 보이지 않아야 한다 (master-plan 3.2).

status 값 목록은 애플리케이션 상수(`ConversionStatus`)를 import 하지 않고 SQL로 직접
적는다. 마이그레이션은 그 시점 스키마의 스냅샷이라, 나중에 상태가 추가돼도 이미
적용된 이 리비전의 내용이 따라 바뀌면 안 되기 때문이다.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

# alembic이 사용하는 리비전 식별자.
revision: str = "0003"
down_revision: str | Sequence[str] | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """스키마를 적용한다."""
    op.create_table(
        "documents",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("title", sa.String(length=255), nullable=False),
        sa.Column("source_format", sa.String(length=16), nullable=False),
        sa.Column("source_text_encrypted", sa.LargeBinary(), nullable=False),
        # Fernet 토큰에는 키 식별자가 없다 — 키 교체 때 재암호화 대상을 고르려면
        # 세대 번호를 함께 저장해 두는 수밖에 없다 (app/privacy/crypto.py 참고).
        sa.Column("key_version", sa.SmallInteger(), server_default=sa.text("1"), nullable=False),
        sa.Column("char_count", sa.Integer(), nullable=False),
        # 기본 30일 보존 (master-plan 3.2). 만료 문서 삭제 잡은 후속 과제이고
        # 여기서는 판단 기준이 되는 시각만 DB 시계로 못박는다.
        sa.Column(
            "retention_expires_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now() + interval '30 days'"),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        # ondelete=CASCADE: 계정을 지우면 문서·변환이 함께 사라진다(개인정보 삭제).
        sa.ForeignKeyConstraint(
            ["user_id"], ["users.id"], name=op.f("fk_documents_user_id_users"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_documents")),
    )
    # 목록 조회(내 문서)와 소유자 검증이 전부 user_id로 들어온다.
    op.create_index(op.f("ix_documents_user_id"), "documents", ["user_id"], unique=False)

    op.create_table(
        "conversions",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("document_id", sa.Uuid(), nullable=False),
        sa.Column(
            "status", sa.String(length=16), server_default=sa.text("'pending'"), nullable=False
        ),
        sa.Column("easy_text_encrypted", sa.LargeBinary(), nullable=True),
        sa.Column("masked_items_encrypted", sa.LargeBinary(), nullable=True),
        sa.Column("key_version", sa.SmallInteger(), server_default=sa.text("1"), nullable=False),
        # 자리표시자 라벨만 담기므로 개인정보가 아니다 — 평문 JSONB로 둔다.
        sa.Column(
            "missing_placeholders",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("provider_name", sa.String(length=32), nullable=True),
        sa.Column("model", sa.String(length=128), nullable=True),
        sa.Column("input_tokens", sa.Integer(), nullable=True),
        sa.Column("output_tokens", sa.Integer(), nullable=True),
        # 예외 클래스명만 담는다(본문·모델 응답 금지).
        sa.Column("failure_code", sa.String(length=64), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('pending', 'processing', 'done', 'failed')",
            name=op.f("ck_conversions_status_valid"),
        ),
        sa.ForeignKeyConstraint(
            ["document_id"],
            ["documents.id"],
            name=op.f("fk_conversions_document_id_documents"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_conversions")),
    )
    op.create_index(
        op.f("ix_conversions_document_id"), "conversions", ["document_id"], unique=False
    )


def downgrade() -> None:
    """스키마 변경을 되돌린다."""
    op.drop_index(op.f("ix_conversions_document_id"), table_name="conversions")
    op.drop_table("conversions")
    op.drop_index(op.f("ix_documents_user_id"), table_name="documents")
    op.drop_table("documents")
