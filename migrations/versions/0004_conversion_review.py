"""conversions 검수 수정본

Revision ID: 0004
Revises: 0003
Create Date: 2026-08-07 10:12:41.508233

담당자 검수 수정본을 AI 초안과 **별도 컬럼**에 담는다. 초안(easy_text_encrypted)을
덮어쓰지 않는 이유는 수정률 KPI(초안 대비 편집 비율, master-plan 7장)의 기준선이
초안이기 때문이다 — 덮어쓰면 그 값을 되살릴 방법이 없다.

수정본도 원문·초안과 같은 이유로 암호화(bytea) 저장한다 — 이 테이블을 직접 조회해도
본문이 보이지 않아야 한다 (master-plan 3.2).

기존 행에는 NULL이 들어간다(아직 검수하지 않은 변환) — 기본값을 채우지 않으므로
되돌리기도 컬럼 삭제 두 줄로 끝난다.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

# alembic이 사용하는 리비전 식별자.
revision: str = "0004"
down_revision: str | Sequence[str] | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """스키마를 적용한다."""
    op.add_column(
        "conversions", sa.Column("edited_text_encrypted", sa.LargeBinary(), nullable=True)
    )
    op.add_column(
        "conversions", sa.Column("reviewed_at", sa.DateTime(timezone=True), nullable=True)
    )


def downgrade() -> None:
    """스키마 변경을 되돌린다 (검수 수정본은 함께 사라진다)."""
    op.drop_column("conversions", "reviewed_at")
    op.drop_column("conversions", "edited_text_encrypted")
