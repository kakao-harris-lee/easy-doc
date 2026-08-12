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

#: 리포트 파일을 쓸 디렉터리. **기본값은 없다** — 지정했을 때만 파일로 남긴다.
#: 저장소에 리포트를 자동으로 떨어뜨리면 `.gitignore`를 손대야 하는데, 이번 작업의 소유
#: 범위 밖이다. 터미널 출력만으로도 "통과 실행도 수치를 남긴다"는 요구는 충족되고,
#: CI가 산출물을 보관하고 싶으면 이 환경변수를 준다.
REPORT_DIR_ENV = "GOLDEN_REPORT_DIR"

_LATEST: "GoldenRunReport | None" = None


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
            f"{self.fingerprint.criteria_sha256[:12]}",
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


def record(report: GoldenRunReport) -> GoldenRunReport:
    """이번 실행의 리포트를 붙든다. 터미널 요약이 통과·실패와 무관하게 이것을 출력한다."""
    global _LATEST
    _LATEST = report
    directory = os.environ.get(REPORT_DIR_ENV, "").strip()
    if directory:
        path = Path(directory) / "golden-report.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(report.as_dict(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    return report


def latest() -> GoldenRunReport | None:
    return _LATEST


def reset() -> None:
    """테스트용 — 붙들고 있던 리포트를 버린다."""
    global _LATEST
    _LATEST = None
