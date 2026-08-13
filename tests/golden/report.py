"""골든셋 실행 리포트 — **통과하든 실패하든 수치를 남긴다.**

이 파일이 있어야 하는 이유는 단순하다. 기존 하네스의 수치는 전부 `assert` 메시지였고,
`assert`는 **실패할 때만** 메시지를 낸다. 그래서 게이트를 통과한 실행은 아무 수치도 남기지
않았고(`02_quality-baseline.md` §2·§6.5가 같은 지적), 기준선이 축적되지 않았다.
상대 하한선("직전에 기록된 측정치보다 낮아지지 않는다")은 직전 측정치가 **있을 때만**
성립하므로, 항상 수치를 남기는 이 리포트가 하한선 장치의 선행 조건이다.

집단을 나눠 싣는다. 합성 20건과 실수집 36건은 분포가 달라(합성 스타일 위반 0/20 대
실수집 11/36, 원문 위반 총계도 문서당 17.9 대 37.1) 합친 평균이 어느 집단도 대표하지
않는다(`02_quality-baseline.md` §4.3·§4.8·§5.3).

목표선(master-plan §7 KPI 0.90 등)은 **차단하지 않지만 함께 찍는다.** 차단선에서 내려온
수치를 리포트에서까지 지우면 "지금 목표에서 얼마나 떨어져 있는가"를 볼 방법이 사라진다.

개인정보: 문서 id·건수·사유 코드·점수만 싣는다. 본문·문장·팩트 리터럴·judge 코멘트는
싣지 않는다(`JudgeScore.comment`에는 본문 일부가 인용될 수 있다).
"""

import json
import os
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict

from app.easyread.goldenset import FactLoss
from tests.golden.baseline import (
    Fingerprint,
    FloorJudgement,
    JudgeObservation,
    Measurement,
    RunContext,
)
from tests.golden.evaluation import RuleEvaluation

#: 리포트 파일을 쓸 디렉터리. **기본값은 없다** — 지정했을 때만 파일로 남긴다.
#: 저장소에 리포트를 자동으로 떨어뜨리면 `.gitignore`를 손대야 하는데, 이번 작업의 소유
#: 범위 밖이다. 터미널 출력만으로도 "통과 실행도 수치를 남긴다"는 요구는 충족되고,
#: CI가 산출물을 보관하고 싶으면 이 환경변수를 준다.
REPORT_DIR_ENV = "GOLDEN_REPORT_DIR"

#: 표시용 — **마지막으로 세워진** 리포트. 터미널 요약(`conftest.py`)이 실행 끝에 한 번
#: 출력할 대상을 고르는 데만 쓴다. 이 값을 **읽어서 쓰거나 여기에 값을 대입하는 경로는
#: 없어야 한다**(아래 `for_evaluation` 참조).
_LATEST: "GoldenRunReport | None" = None

#: 평가 → 그 평가로 세운 리포트. **키는 값이 아니라 객체 동일성**이다(`for_evaluation`이
#: `is`로 찾는다). 값으로 키를 잡으면 수치가 같은 두 실행이 겹치는데, 통과 수는 정수 쌍이라
#: 같은 값이 흔하다(`test_floor_gate_wiring.py`가 그 입력을 실제로 만든다).
#: 실행당 항목 수는 리포트를 세우는 횟수와 같다 — 실제 게이트 실행에서는 모듈 스코프 fixture가
#: 한 번 세우므로 1건이고, 여러 번 세우는 배선 테스트는 `reset()`으로 매 테스트 씻는다.
_BOUND: "list[tuple[RuleEvaluation, GoldenRunReport]]" = []


class Targets(BaseModel):
    """목표선. **차단하지 않는다** — 현재 위치를 읽기 위한 눈금이다.

    값의 정본은 `tests/golden/test_golden_eval.py`의 상수다. 여기서 다시 정의하지 않고
    받아 오는 이유는 두 벌이 되면 한쪽만 고쳐지기 때문이다.
    """

    model_config = ConfigDict(extra="forbid")

    pass_rate: float
    judge_coverage: float
    judge_score: float
    fidelity_floor: int


class GoldenRunReport(BaseModel):
    """한 실행의 전체 수치."""

    model_config = ConfigDict(extra="forbid")

    fingerprint: Fingerprint
    context: RunContext
    targets: Targets
    measurement: Measurement
    #: 사유 코드별 건수 (문서 단위가 아니라 위반 건수). 코드는 style_rules가 만든 상수다.
    failure_reasons: dict[str, int] = {}
    #: 변환 자체가 실패한 문서 id.
    conversion_failures: list[str] = []
    #: 필수 정보 보존 게이트 결과 — **절대 기준(0건)** 축이다.
    fact_losses: list[FactLoss] = []
    #: judge 관측 — 비차단.
    judge: JudgeObservation | None = None
    judge_notes: list[str] = []
    #: 상대 하한선 판정.
    floor: FloorJudgement | None = None
    #: 기록 모드에서 기준선이 어떻게 바뀌는가.
    baseline_changes: list[str] = []

    def render(self) -> str:
        lines = [
            "",
            "═══ 골든셋 실행 리포트 ═══",
            f"코퍼스 {self.fingerprint.document_count}건 "
            f"(합성 {self.fingerprint.synthetic_count} / 실수집 "
            f"{self.fingerprint.collected_count}) · 지문 코퍼스 "
            f"{self.fingerprint.corpus_sha256[:12]} · 기준 "
            f"{self.fingerprint.criteria_sha256[:12]} · producer "
            f"{self.fingerprint.producer.label()}",
            f"변환 provider {self.context.provider}"
            + (f" · judge {self.context.judge_provider}" if self.context.judge_provider else "")
            + (f" · model {self.context.model}" if self.context.model else ""),
            "",
            "── 규칙 기반 통과율 (차단축: 상대 하한선) ──",
        ]
        for label, group in self.measurement.groups():
            gap = group.pass_rate - self.targets.pass_rate
            lines.append(
                f"  {label:<4} {group.passed:>3}/{group.documents:<3} = "
                f"{group.pass_rate:.3f}   목표 {self.targets.pass_rate:.2f} 대비 {gap:+.3f}"
            )
        if self.conversion_failures:
            lines.append(
                f"  변환 실패 {len(self.conversion_failures)}건: "
                + ", ".join(self.conversion_failures)
            )
        if self.failure_reasons:
            lines.append("  실패 사유(위반 건수):")
            lines += [
                f"    - {reason}: {count}"
                for reason, count in sorted(
                    self.failure_reasons.items(), key=lambda pair: (-pair[1], pair[0])
                )
            ]
        lines += ["", "── 필수 정보 보존 (차단축: 절대 기준 0건, LLM 미사용) ──"]
        if self.fact_losses:
            total = sum(loss.missing for loss in self.fact_losses)
            lines.append(f"  누락 {total}건 / 문서 {len(self.fact_losses)}건")
            lines += [
                f"    - {loss.document_id}: {loss.missing}/{loss.required}건 누락"
                for loss in self.fact_losses
            ]
        else:
            lines.append("  누락 0건 — 충족")
        lines += ["", "── judge (비차단: 기록·경고용) ──"]
        if self.judge is None:
            lines.append("  채점하지 않음 (키 없음 또는 건너뜀)")
        else:
            coverage = self.judge.scored / self.judge.documents if self.judge.documents else 0.0
            lines += [
                f"  커버리지 {self.judge.scored}/{self.judge.documents} = {coverage:.3f} "
                f"(목표 {self.targets.judge_coverage:.2f})",
                f"  충실성 평균 {self.judge.fidelity_mean:.2f} / 이해 용이성 평균 "
                f"{self.judge.readability_mean:.2f} (목표 각 {self.targets.judge_score:.1f})",
                f"  충실성 {self.targets.fidelity_floor} 이하 문서 "
                f"{len(self.judge.low_fidelity_ids)}건"
                + (
                    f": {', '.join(self.judge.low_fidelity_ids)}"
                    if self.judge.low_fidelity_ids
                    else ""
                ),
            ]
        lines += [f"  ⚠ {note}" for note in self.judge_notes]
        if self.judge is not None:
            lines.append(
                "  judge 점수는 차단하지 않는다 — 채점 모델을 고정할 수단이 없어 모델이 "
                "바뀌면 우리 코드를 고치지 않아도 값이 움직인다"
            )
        if self.floor is not None:
            lines += ["", "── 상대 하한선 ──", *self.floor.summary().splitlines()]
        if self.baseline_changes:
            lines += ["", "── 기준선 기록 ──", *self.baseline_changes]
        lines.append("═════════════════════════")
        return "\n".join(lines)

    def as_dict(self) -> dict[str, Any]:
        return dict(json.loads(self.model_dump_json()))


def record(report: GoldenRunReport, evaluation: RuleEvaluation) -> GoldenRunReport:
    """리포트를 **그 리포트를 만든 평가에 결속해** 등록한다.

    `evaluation` 은 선택 인자가 아니다. 결속 없이 등록할 수 있으면 "이 리포트는 누구 것인가"를
    말할 수 없는 리포트가 생기고, 그런 리포트를 나중에 `latest()` 로 집어 오는 경로가 다시
    열린다. 인자를 필수로 두면 그 상태가 애초에 만들어지지 않는다.

    터미널 요약은 계속 `latest()` 를 본다 — 출력할 리포트 하나를 고르는 표시용 용도이고,
    거기서는 "마지막으로 세워진 것"이 정확히 맞는 뜻이다.
    """
    global _LATEST
    _LATEST = report
    _BOUND.append((evaluation, report))
    directory = os.environ.get(REPORT_DIR_ENV, "").strip()
    if directory:
        path = Path(directory) / "golden-report.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(report.as_dict(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    return report


def for_evaluation(evaluation: RuleEvaluation) -> GoldenRunReport | None:
    """**이 평가로 세운** 리포트만 돌려준다. 없으면 `None`.

    기록 경로가 리포트를 찾는 유일한 통로다. `latest()` 와 다른 점이 이 함수의 존재 이유다 —
    `latest()` 는 "마지막으로 세워진" 리포트라 이번 평가와 아무 관계가 없을 수 있고,
    `build_report` 가 여러 번 불리는 프로세스(배선 테스트가 그렇다)에서는 실제로 남의 실행
    리포트가 최신이다. 그 리포트를 집어 오면 이번 실행의 판정이 남의 리포트에 실린다.

    찾기는 `is` 다 — 값(`==`)으로 찾으면 수치가 같은 두 실행이 겹친다. 통과 수는 정수 쌍이라
    같은 값이 흔하고, 이번 사가에서 그 입력을 이미 확인했다.

    **방향은 면죄부가 아니다.** 리포트에서 값을 읽는 것만 위험하고 쓰는 것은 안전하다는
    방어를 한 번 했는데(9번째 지적이 그 방어를 깼다), 쓰기는 커밋되는 기준선 파일 대신
    **사람이 읽는 리포트**를 오염시킨다. 읽기든 쓰기든 남의 리포트를 집는 순간 두 실행이
    섞이고, 섞인 쪽을 사람이 읽는다는 점에서 결과는 같다.
    """
    for source, report in reversed(_BOUND):
        if source is evaluation:
            return report
    return None


def latest() -> GoldenRunReport | None:
    """**표시용** — 마지막으로 세워진 리포트(터미널 요약이 무엇을 출력할지 고른다).

    여기서 받은 리포트에 값을 대입하거나 여기서 값을 읽어 판정·기록에 쓰지 마라. 이번 실행의
    리포트가 필요하면 `for_evaluation` 을 써라 — 이 함수는 "이번 실행"을 뜻하지 않는다.
    """
    return _LATEST


def reset() -> None:
    """테스트용 — 붙들고 있던 리포트와 결속 등록부를 함께 버린다."""
    global _LATEST
    _LATEST = None
    _BOUND.clear()
