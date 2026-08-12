"""차단 게이트가 골든셋 하네스에 **실제로 연결되어 있는지** 기본 스위트에서 고정한다.

`tests/golden/test_golden_eval.py`는 `-m llm`이라 기본 실행에서 제외된다. 그래서 판정
함수만 테스트하면 "하네스가 그 함수를 호출하는가"는 무방비다 — 호출을 지워도 기본 스위트는
초록색으로 남는다. 여기서는 API 키 없이 준비된 변환 결과와 FakeProvider로 하네스 함수를
직접 돌려 배선을 확인한다 (실제 LLM 호출 없음).

**요구 사항이기도 하다**: 차단 게이트는 LLM 없이 돌아야 한다. 그렇지 않으면 CI에서 한 번도
돌지 않는다. `tests/golden/test_baseline_gate.py`가 판정 로직 자체를, 이 파일이 그 로직이
하네스에 물려 있는지를 맡는다.

## 2026-08-12 이후 무엇이 바뀌었나

judge는 더 이상 차단하지 않는다(채점 모델을 고정할 수단이 없어 모델이 바뀌면 우리 코드를
고치지 않아도 CI가 빨개진다). 그래서 이 파일이 예전에 고정하던 "날조가 섞이면 하네스가
실패한다"는 **더 이상 성립하지 않는다.** 다만 그 보호를 그냥 지우지 않았다.

- 바닥 판정은 계속 돈다 — `find_low_fidelity` 호출이 사라지면 아래 경고·리포트 검사가 깨진다
- 그 축이 막던 **중요 정보 누락**은 필수 정보 보존 게이트가 LLM 없이 절대 기준으로 받는다
"""

import pytest

from app.easyread.goldenset import GoldenDocument
from app.llm.fake import FakeProvider
from app.services.conversion import ConversionOutcome
from tests.golden import report as golden_report
from tests.golden import test_golden_eval as harness
from tests.golden.baseline import (
    Baseline,
    Fingerprint,
    GroupMeasurement,
    Measurement,
    RunContext,
    baseline_body,
    compare,
)

_EASY_TEXT = "오늘 서류를 내세요."
_HIGH = '{"fidelity": 5, "readability": 5, "comment": "-"}'
_FABRICATED = '{"fidelity": 1, "readability": 5, "comment": "-"}'
# 필요한 시나리오는 "평균 게이트는 통과하는데 바닥 위반이 섞여 있는 입력" 하나뿐이다.
# 날조를 1건만 섞으면 평균은 5 - 4/n이라 n이 4 이상인 한 언제나 목표선(4.0) 이상이고,
# 바닥 위반은 확실히 존재한다. 골든셋 하한은 20건이므로 조건은 항상 만족된다.
_FABRICATED_COUNT = 1


def _outcomes(easy_text: str = _EASY_TEXT) -> dict[str, ConversionOutcome | None]:
    """전 문서가 같은 문장으로 변환된 상황을 만든다."""
    return {
        document.id: ConversionOutcome(
            easy_text=easy_text, masked_items=[], model="fake", provider_name="fake"
        )
        for document in harness.DOCUMENTS
    }


def _preserving_outcomes() -> dict[str, ConversionOutcome | None]:
    """필수 정보를 전부 보존한 변환 결과 — 팩트 게이트를 통과시키는 입력."""
    return {
        document.id: ConversionOutcome(
            easy_text=document.source_text, masked_items=[], model="fake", provider_name="fake"
        )
        for document in harness.DOCUMENTS
    }


@pytest.fixture(autouse=True)
def _clean_report() -> object:
    """리포트 홀더는 모듈 전역이라 테스트끼리 새어 나가지 않게 씻는다."""
    golden_report.reset()
    yield None
    golden_report.reset()


# ═══════════════════════════════════════════ 차단축 1: 필수 정보 보존 (절대 기준)


def test_필수_정보가_빠지면_하네스가_실패한다() -> None:
    """하네스가 `find_fact_losses`를 실제로 호출하는지 고정한다.

    judge가 차단하지 않게 된 뒤로 정보 누락을 막는 유일한 장치라, 이 배선이 끊기면
    금액·기한·대상이 빠진 변환문이 그대로 통과한다.
    """
    evaluation = harness.evaluate_all(_outcomes())
    harness.build_report(evaluation)
    assert evaluation.fact_losses, "전제 실패 — 이 입력은 팩트를 잃은 상태여야 한다"
    with pytest.raises(AssertionError, match="필수 정보 누락"):
        harness.test_필수_정보가_보존된다(evaluation)


def test_필수_정보를_보존하면_하네스가_통과한다() -> None:
    """게이트가 무조건 실패하는 것이 아님을 함께 고정한다."""
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    assert evaluation.fact_losses == []
    harness.test_필수_정보가_보존된다(evaluation)


def test_필수_정보_게이트는_절대_기준이다() -> None:
    """상대 기준이 아니다 — '직전에도 빠졌으니 괜찮다'는 경로가 없어야 한다.

    허용치가 0이 아니게 되면(혹은 하한선 판정에 얹히면) 이 검사가 깨진다.
    """
    assert harness.REQUIRED_FACT_LOSS_LIMIT == 0
    evaluation = harness.evaluate_all(_outcomes())
    harness.build_report(evaluation)
    with pytest.raises(AssertionError, match="절대 기준"):
        harness.test_필수_정보가_보존된다(evaluation)


# ═══════════════════════════════════════════ 차단축 2: 상대 하한선


def _baseline_at(passed: tuple[int, int], synthetic: tuple[int, int]) -> Baseline:
    measurement = Measurement(
        overall=GroupMeasurement(passed=passed[0], documents=passed[1]),
        synthetic=GroupMeasurement(passed=synthetic[0], documents=synthetic[1]),
        collected=GroupMeasurement(
            passed=passed[0] - synthetic[0], documents=passed[1] - synthetic[1]
        ),
    )
    return Baseline.model_validate(
        baseline_body(
            Fingerprint.of(harness.DOCUMENTS),
            measurement,
            RunContext(provider="fake"),
            None,
        )
    )


def test_직전_기록보다_낮으면_하네스가_실패한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """하네스가 기준선을 읽어 `compare`로 판정하는지 고정한다."""
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    total = evaluation.measurement.overall
    higher = _baseline_at(
        (total.passed + 1, total.documents),
        (evaluation.measurement.synthetic.passed + 1, evaluation.measurement.synthetic.documents),
    )
    monkeypatch.setattr(harness, "load_baseline", lambda: higher)
    with pytest.raises(AssertionError, match="낮아졌다"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)


def test_직전_기록과_같으면_하네스가_통과한다(monkeypatch: pytest.MonkeyPatch) -> None:
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    same = _baseline_at(
        (evaluation.measurement.overall.passed, evaluation.measurement.overall.documents),
        (evaluation.measurement.synthetic.passed, evaluation.measurement.synthetic.documents),
    )
    monkeypatch.setattr(harness, "load_baseline", lambda: same)
    harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)


def test_기준선이_없으면_하네스가_실패한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """기준선 부재를 통과로 처리하지 않는지 하네스 수준에서 고정한다."""
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    monkeypatch.setattr(harness, "load_baseline", lambda: None)
    with pytest.raises(AssertionError, match="기준선이 없다"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)


def test_기록_실행은_게이트를_닫지_못한다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: object
) -> None:
    """**기록 실행은 판정이 아니다.**

    기준선을 방금 갱신한 실행과 애초에 문제없던 실행이 같은 결과를 내면 자동화가 둘을
    구분하지 못한다 — 참고 갈림 원장이 실제로 겪은 결함이다(X-09/X-12).
    여기서는 수치가 **유지**(통과 조건)인데도 기록이 일어나면 실패해야 한다는 것을 본다.
    """
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    same = _baseline_at(
        (evaluation.measurement.overall.passed, evaluation.measurement.overall.documents),
        (evaluation.measurement.synthetic.passed, evaluation.measurement.synthetic.documents),
    )
    monkeypatch.setattr(harness, "load_baseline", lambda: same)
    monkeypatch.setattr(harness, "recording_requested", lambda: True)
    # 기준선이 바뀐다고 보고하되 실제 파일은 건드리지 않는다.
    monkeypatch.setattr(harness, "baseline_changes", lambda body: ["- 기준선을 새로 만든다"])
    written: list[object] = []

    def _capture(body: object) -> str:
        written.append(body)
        return "기록됨"

    monkeypatch.setattr(harness, "write_baseline", _capture)

    # 판정만 보면 통과다 — 그런데도 기록 실행이라 실패해야 한다.
    assert (
        compare(same, Fingerprint.of(harness.DOCUMENTS), evaluation.measurement).blocking is False
    )
    with pytest.raises(AssertionError, match="기록 실행"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    assert written, "기록 모드인데 기준선을 쓰지 않았다"


def test_바뀔_것이_없는_기록_실행은_정상_판정으로_떨어진다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """관측이 커밋된 기준선과 정확히 같으면 플래그 없이 돌린 실행과 판정이 정의상 같다."""
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    same = _baseline_at(
        (evaluation.measurement.overall.passed, evaluation.measurement.overall.documents),
        (evaluation.measurement.synthetic.passed, evaluation.measurement.synthetic.documents),
    )
    monkeypatch.setattr(harness, "load_baseline", lambda: same)
    monkeypatch.setattr(harness, "recording_requested", lambda: True)
    monkeypatch.setattr(harness, "baseline_changes", lambda body: [])
    monkeypatch.setattr(
        harness, "write_baseline", lambda body: pytest.fail("바뀐 것이 없는데 파일을 다시 썼다")
    )
    harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)


# ═══════════════════════════════════════════ 비차단축: judge


async def test_날조가_섞여도_judge는_차단하지_않는다() -> None:
    """2026-08-12 결정의 배선 — judge는 경고로 남기고 실패시키지 않는다.

    예전에는 이 입력이 `AssertionError`를 냈다. 그 보호를 대체한 것이 필수 정보 보존
    게이트이며, 위 `test_필수_정보가_빠지면_하네스가_실패한다`가 그 자리를 지킨다.
    """
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    responses: list[str | Exception] = [_HIGH] * (len(harness.DOCUMENTS) - _FABRICATED_COUNT)
    responses += [_FABRICATED] * _FABRICATED_COUNT
    with pytest.warns(UserWarning, match="judge 비차단"):
        await harness.test_judge_점수를_기록한다(
            _preserving_outcomes(), FakeProvider(responses=responses)
        )


async def test_날조는_경고와_리포트에_남는다() -> None:
    """차단하지 않는 것과 조용히 넘기는 것은 다르다 — 바닥 판정은 계속 돌아야 한다.

    `find_low_fidelity` 호출을 지우면 이 테스트가 깨진다.
    """
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    responses: list[str | Exception] = [_FABRICATED] * len(harness.DOCUMENTS)
    with pytest.warns(UserWarning, match="충실성"):
        await harness.test_judge_점수를_기록한다(
            _preserving_outcomes(), FakeProvider(responses=responses)
        )
    report = golden_report.latest()
    assert report is not None
    assert report.judge is not None
    assert len(report.judge.low_fidelity_ids) == len(harness.DOCUMENTS)
    assert "충실성" in report.render()


async def test_judge_점수가_리포트에_실린다() -> None:
    """통과하는 실행에서도 judge 수치가 남아야 한다 — 기록·경고용이라는 뜻이 이것이다."""
    evaluation = harness.evaluate_all(_preserving_outcomes())
    harness.build_report(evaluation)
    responses: list[str | Exception] = [_HIGH] * len(harness.DOCUMENTS)
    await harness.test_judge_점수를_기록한다(
        _preserving_outcomes(), FakeProvider(responses=responses)
    )
    report = golden_report.latest()
    assert report is not None
    assert report.judge is not None
    assert report.judge.scored == len(harness.DOCUMENTS)
    assert report.judge.fidelity_mean == 5.0
    assert report.judge.low_fidelity_ids == []


def test_목표선_상수가_남아_있다() -> None:
    """0.90은 차단에서 내려왔을 뿐 지워진 것이 아니다 — 리포트가 목표 대비 현재를 찍는다."""
    assert harness.PASS_RATE_THRESHOLD == 0.9
    assert harness.JUDGE_COVERAGE_THRESHOLD == 0.9
    assert harness.JUDGE_SCORE_THRESHOLD == 4.0
    assert harness.FIDELITY_FLOOR == 2


def test_하네스_문서_수가_스키마_하한을_넘는다() -> None:
    """위 시나리오들이 성립하는 전제 — 문서가 충분히 있어야 평균 계산이 의미를 가진다."""
    assert len(harness.DOCUMENTS) >= 20
    assert isinstance(harness.DOCUMENTS[0], GoldenDocument)
