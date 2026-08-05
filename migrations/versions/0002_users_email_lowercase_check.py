"""users 이메일 소문자 CHECK 제약

Revision ID: 0002
Revises: 0001
Create Date: 2026-08-06 07:14:44.855304

0001에 넣지 않고 별도 리비전으로 나눈 이유: 이미 0001로 스탬프된 데이터베이스가
있으면 0001을 제자리 수정해도 영원히 적용되지 않는다. 적용된 리비전은 고쳐도 다시
실행되지 않으므로, 스키마 변경은 언제나 새 리비전으로 쌓는다.
"""

from collections.abc import Sequence

from alembic import op

# alembic이 사용하는 리비전 식별자.
revision: str = "0002"
down_revision: str | Sequence[str] | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """스키마를 적용한다."""
    # 이메일 정규화는 AuthService가 하지만, 서비스를 거치지 않는 경로(운영 스크립트,
    # 데이터 이관)가 대소문자만 다른 값을 넣으면 unique 인덱스가 무력해진다.
    op.create_check_constraint(op.f("ck_users_email_lowercase"), "users", "email = lower(email)")


def downgrade() -> None:
    """스키마 변경을 되돌린다."""
    op.drop_constraint(op.f("ck_users_email_lowercase"), "users", type_="check")
