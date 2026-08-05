"""ORM 모델 패키지.

여기서 모든 모델을 import 해 `Base.metadata`에 등록한다. alembic autogenerate가
보는 metadata는 이 패키지를 import 한 뒤의 상태이므로, 새 모델을 만들면 반드시
아래 목록에 추가해야 한다 — 빠뜨리면 autogenerate가 그 테이블을 "지워야 할
테이블"로 오인한다.
"""

from app.models.user import User

__all__ = ["User"]
