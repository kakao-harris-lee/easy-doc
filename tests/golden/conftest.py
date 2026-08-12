"""골든셋 실행 리포트를 **통과·실패와 무관하게** 터미널에 출력한다.

`assert` 메시지는 실패할 때만 보인다. 하네스의 수치가 전부 `assert` 메시지였기 때문에
게이트를 통과한 실행은 아무 수치도 남기지 않았고, 그래서 "직전 측정치"가 축적되지 않아
상대 하한선을 세울 수 없었다(`tests/golden/report.py` 모듈 docstring).

`pytest_terminal_summary`는 테스트 결과와 무관하게 실행 끝에 한 번 불린다 — 통과한
실행에서도 수치가 보이게 하는 자리가 여기다.
"""

from _pytest.terminal import TerminalReporter  # pytest가 공개 경로로 내보내지 않는 타입

from tests.golden import report as golden_report


def pytest_terminal_summary(terminalreporter: TerminalReporter) -> None:
    """이번 실행이 남긴 골든셋 리포트를 출력한다 (없으면 조용히 지나간다)."""
    latest = golden_report.latest()
    if latest is None:
        return
    terminalreporter.write_line(latest.render())
