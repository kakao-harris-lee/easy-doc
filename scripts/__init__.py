"""운영자용 실행 스크립트 모음.

패키지로 두는 이유는 하나다: 테스트가 스크립트의 순수 로직을 import 할 수 있어야
한다(`from scripts.pilot_report import edit_ratio`). `__init__.py`가 없으면 같은 파일이
`pilot_report`와 `scripts.pilot_report` 두 이름으로 잡혀 mypy가 중복으로 거절한다.

스크립트를 파일로 직접 실행하는 방식(`uv run python scripts/pilot_report.py`)은
그대로다 — 각 스크립트가 리포 루트를 sys.path에 넣어 `app` 패키지를 찾는다.
"""
