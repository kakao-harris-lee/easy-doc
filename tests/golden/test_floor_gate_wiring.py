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

from pathlib import Path
from typing import Any

import pytest

from app.config import Settings
from app.easyread.goldenset import GoldenDocument
from app.llm.fake import FakeProvider
from app.services.conversion import ConversionOutcome
from tests.golden import report as golden_report
from tests.golden import test_golden_eval as harness
from tests.golden.baseline import (
    Baseline,
    Fingerprint,
    GroupMeasurement,
    JudgeObservation,
    Measurement,
    RunContext,
    Verdict,
    baseline_body,
    compare,
    write_baseline,
)
from tests.golden.evaluation import RuleEvaluation, evaluate_all

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


def _preserving_outcomes(model: str = "fake") -> dict[str, ConversionOutcome | None]:
    """필수 정보를 전부 보존한 변환 결과 — 팩트 게이트를 통과시키는 입력.

    `model`은 **측정치에 영향을 주지 않는다.** 규칙 평가는 `easy_text`만 보므로, 모델명만
    바꾼 두 호출은 수치가 같고 조건(`observed_models`)만 다른 두 실행이 된다 — 남의 리포트가
    최신이어도 기준선 조건이 새지 않는지 가르는 입력이 정확히 이것이다.
    """
    return {
        document.id: ConversionOutcome(
            easy_text=document.source_text, masked_items=[], model=model, provider_name="fake"
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
    evaluation = evaluate_all(_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    assert evaluation.fact_losses, "전제 실패 — 이 입력은 팩트를 잃은 상태여야 한다"
    with pytest.raises(AssertionError, match="필수 정보 누락"):
        harness.test_필수_정보가_보존된다(evaluation)


def test_필수_정보를_보존하면_하네스가_통과한다() -> None:
    """게이트가 무조건 실패하는 것이 아님을 함께 고정한다."""
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    assert evaluation.fact_losses == []
    harness.test_필수_정보가_보존된다(evaluation)


def test_문서_한_건만_팩트를_잃어도_하네스가_실패한다() -> None:
    """**비교식 자체**를 고정한다 — 상수도 문구도 건드리지 않는 완화가 무검출이었다.

    `total <= REQUIRED_FACT_LOSS_LIMIT`를 `len(losses) <= 14`로 바꾸면 상수(0)와 "절대 기준"
    문구가 그대로라 기존 배선 테스트를 모두 통과했다(2026-08-12 교차 리뷰 T-2). 14는 계획이
    **첫 실행에서 실패할 것으로 예고한 바로 그 숫자**여서, 게이트가 빨간불이 될 때 가장
    자연스러운 편집이 정확히 그 모양이다.

    그래서 여기서는 **누락이 있는 문서가 한 건**인 입력을 쓴다. 문서 수로 재는 완화는 이
    입력을 통과시키고, 누락 건수로 재는 현행 비교식만 막는다.
    """
    outcomes = _preserving_outcomes()
    target = harness.DOCUMENTS[0]
    outcomes[target.id] = ConversionOutcome(
        easy_text="-", masked_items=[], model="fake", provider_name="fake"
    )
    evaluation = evaluate_all(outcomes, harness.DOCUMENTS)
    harness.build_report(evaluation)
    assert [loss.document_id for loss in evaluation.fact_losses] == [target.id], (
        "전제 실패 — 팩트를 잃은 문서가 정확히 한 건이어야 한다"
    )
    with pytest.raises(AssertionError, match=r"필수 정보 누락 \d+건 / 문서 1건"):
        harness.test_필수_정보가_보존된다(evaluation)


def test_변환에_실패한_문서가_있으면_팩트_게이트가_통과하지_않는다() -> None:
    """판정하지 못한 것은 통과가 아니다.

    변환 실패 문서는 `find_fact_losses`의 입력에 아예 들어가지 않아 누락 0건이 된다. 즉
    provider가 전건 실패한 실행이 **공허하게 통과**한다 — 절대 기준의 가장 큰 구멍이다.
    """
    outcomes = _preserving_outcomes()
    outcomes[harness.DOCUMENTS[0].id] = None
    evaluation = evaluate_all(outcomes, harness.DOCUMENTS)
    harness.build_report(evaluation)
    assert evaluation.fact_losses == [], "전제 — 변환 실패 문서는 누락으로도 잡히지 않는다"
    with pytest.raises(AssertionError, match="변환 실패"):
        harness.test_필수_정보가_보존된다(evaluation)


def test_필수_정보_게이트는_절대_기준이다() -> None:
    """상대 기준이 아니다 — '직전에도 빠졌으니 괜찮다'는 경로가 없어야 한다.

    허용치가 0이 아니게 되면(혹은 하한선 판정에 얹히면) 이 검사가 깨진다.
    """
    assert harness.REQUIRED_FACT_LOSS_LIMIT == 0
    evaluation = evaluate_all(_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    with pytest.raises(AssertionError, match="절대 기준"):
        harness.test_필수_정보가_보존된다(evaluation)


# ═══════════════════════════════════════════ 차단축 2: 상대 하한선


def _baseline_at(
    passed: tuple[int, int],
    synthetic: tuple[int, int],
    fingerprint: Fingerprint | None = None,
) -> Baseline:
    measurement = Measurement(
        overall=GroupMeasurement(passed=passed[0], documents=passed[1]),
        synthetic=GroupMeasurement(passed=synthetic[0], documents=synthetic[1]),
        collected=GroupMeasurement(
            passed=passed[0] - synthetic[0], documents=passed[1] - synthetic[1]
        ),
    )
    return Baseline.model_validate(
        baseline_body(
            fingerprint if fingerprint is not None else Fingerprint.of(harness.DOCUMENTS),
            measurement,
            RunContext(provider="fake"),
        )
    )


def _matching_baseline(
    evaluation: RuleEvaluation, fingerprint: Fingerprint | None = None
) -> Baseline:
    """이번 측정치와 **정확히 같은** 기준선 — 수치로는 통과하는 상태를 만든다.

    지문 배선을 보는 테스트가 이것을 쓴다. 수치가 어긋나면 무엇 때문에 막혔는지 갈리므로,
    막히는 이유를 지문 하나로 좁혀 둔다.
    """
    measurement = evaluation.measurement
    return _baseline_at(
        (measurement.overall.passed, measurement.overall.documents),
        (measurement.synthetic.passed, measurement.synthetic.documents),
        fingerprint,
    )


def test_직전_기록보다_낮으면_하네스가_실패한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """하네스가 기준선을 읽어 `compare`로 판정하는지 고정한다."""
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
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
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    same = _baseline_at(
        (evaluation.measurement.overall.passed, evaluation.measurement.overall.documents),
        (evaluation.measurement.synthetic.passed, evaluation.measurement.synthetic.documents),
    )
    monkeypatch.setattr(harness, "load_baseline", lambda: same)
    harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)


def test_기준선이_없으면_하네스가_실패한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """기준선 부재를 통과로 처리하지 않는지 하네스 수준에서 고정한다."""
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
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
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
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


def test_코퍼스_지문이_다르면_하네스가_비교_불가로_떨어진다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """**지문 배선**을 고정한다 — 판정 함수가 아니라 하네스가 *현재* 지문을 넘기는지를 본다.

    하네스가 기준선 자신의 지문을 넘기도록 한 줄만 바꾸면 지문 비교가 항등식이 되어 두 축이
    통째로 죽는데, 그 상태로 스위트 916건이 전부 초록이었다(2026-08-12 교차 리뷰 T-1).
    기존 배선 테스트는 기준선을 `Fingerprint.of(harness.DOCUMENTS)`로 만들어 양변이 늘 같았고,
    "양변이 다를 때"만 반응했다. **코퍼스 지문이 8지점에서 견고하다는 판정도 이 배선 위에서만
    성립한다.**

    수치는 기준선과 정확히 같게 두었다 — 막히는 이유가 지문 하나로 좁혀진다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    drifted = _matching_baseline(evaluation, Fingerprint.of(harness.DOCUMENTS[:-1]))
    monkeypatch.setattr(harness, "load_baseline", lambda: drifted)
    with pytest.raises(AssertionError, match="코퍼스 구성이 바뀌었다"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)


def test_판정_기준_지문이_다르면_하네스가_비교_불가로_떨어진다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """자[尺]가 바뀐 기준선과의 비교도 하네스 수준에서 막혀야 한다(위와 같은 배선, 다른 축)."""
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    other_criteria = Fingerprint.of(harness.DOCUMENTS).model_copy(
        update={"criteria_sha256": "0" * 64}
    )
    monkeypatch.setattr(
        harness, "load_baseline", lambda: _matching_baseline(evaluation, other_criteria)
    )
    with pytest.raises(AssertionError, match="판정 기준이 바뀌었다"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)


def test_측정이_코퍼스_전건이_아니면_하네스가_실패한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """분모를 줄이는 우회는 **지문에 걸리지 않는다** — 지문은 언제나 전건으로 계산되므로.

    측정만 축소 목록으로 돌면 통과율이 실력과 무관하게 오르는데 코퍼스 지문은 그대로다.
    0건 측정이 만점으로 보이는 경로도 같은 자리에서 닫힌다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    monkeypatch.setattr(harness, "load_baseline", lambda: _matching_baseline(evaluation))
    shrunk = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS[:-1])
    with pytest.raises(AssertionError, match="코퍼스 전건이 아니다"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(shrunk)


def test_하한선_판정이_리포트에_실린다(monkeypatch: pytest.MonkeyPatch) -> None:
    """하락 방향이 보이는 나머지 한 경로 — 리포트의 `── 상대 하한선 ──` 절.

    하네스가 `report.floor`를 붙이지 않게 해도 916건이 전부 통과했다(교차 리뷰 T-3의 C8).
    통과하는 실행에서도 판정이 남아야 "이번에 무엇을 근거로 통과했는가"를 볼 수 있다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    monkeypatch.setattr(harness, "load_baseline", lambda: _matching_baseline(evaluation))
    harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    report = golden_report.for_evaluation(evaluation)
    assert report is not None
    assert report.floor is not None, "판정이 리포트에 실리지 않았다"
    assert report.floor.verdict is Verdict.HELD
    assert "── 상대 하한선 ──" in report.render()


def test_하락한_수치로_기록하면_실패_메시지에_방향이_남는다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """**기록 실행이 하한선을 낮추는 경우.** 메시지에 '하락'이 있어야 한다.

    `compare`는 하락·차단을 이미 계산해 두는데 기록 분기의 메시지는 "`measurement`가 바뀐다"
    까지만 적었다(2026-08-12 교차 리뷰 T-3). 36/56 기준선에 10/56을 기록 모드로 넣으면
    디스크가 10/56이 되는데도 pytest 출력만 보면 개선 기록과 구분되지 않았다 — 리뷰어가
    `baseline.json` diff의 숫자를 직접 읽지 않는 한 하한선이 내려간 것을 알 수 없다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    total = evaluation.measurement.overall
    higher = _baseline_at(
        (total.passed + 1, total.documents),
        (evaluation.measurement.synthetic.passed + 1, evaluation.measurement.synthetic.documents),
    )
    monkeypatch.setattr(harness, "load_baseline", lambda: higher)
    monkeypatch.setattr(harness, "recording_requested", lambda: True)
    monkeypatch.setattr(harness, "baseline_changes", lambda body: ["- **`measurement` 가 바뀐다**"])
    monkeypatch.setattr(harness, "write_baseline", lambda body: "기록됨")

    with pytest.raises(AssertionError) as caught:
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    message = str(caught.value)
    assert "기록 실행" in message
    assert "하한선을 낮춘다" in message
    assert "낮아졌다" in message, "방향이 없으면 하락 기록과 개선 기록이 같은 모양이다"


def test_바뀔_것이_없는_기록_실행은_정상_판정으로_떨어진다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """관측이 커밋된 기준선과 정확히 같으면 플래그 없이 돌린 실행과 판정이 정의상 같다."""
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
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


# ═══════════════════ 기록 경로의 결속: 측정치와 조건이 한 객체(evaluation)에서 온다


class _Recording:
    """기록 경로를 tmp 로만 태우는 배선. 저장소 `baseline.json`은 절대 건드리지 않는다.

    `baseline_body`·`write_baseline` 호출 여부를 함께 세는 이유: 결속 검사는 **본문을 만들기
    전에** 막아야 한다. 만들고 나서 막으면 "무엇을 쓰려 했는가"가 이미 남의 조건으로 조립된
    뒤라, 가드를 한 겹 걷어내는 편집에 그대로 노출된다.
    """

    def __init__(
        self, monkeypatch: pytest.MonkeyPatch, evaluation: RuleEvaluation, path: Path
    ) -> None:
        self.bodies: list[dict[str, Any]] = []
        self.attempts: list[dict[str, Any]] = []
        self.written: list[dict[str, Any]] = []
        self._path = path
        monkeypatch.setattr(harness, "load_baseline", lambda: _matching_baseline(evaluation))
        monkeypatch.setattr(harness, "recording_requested", lambda: True)
        monkeypatch.setattr(harness, "baseline_changes", lambda body: ["- 기준선을 새로 만든다"])
        monkeypatch.setattr(harness, "baseline_body", self._body)
        monkeypatch.setattr(harness, "write_baseline", self._write)

    def _body(
        self,
        fingerprint: Fingerprint,
        measurement: Measurement,
        context: RunContext,
    ) -> dict[str, Any]:
        # 이 함수는 `harness.baseline_body` 를 갈아 끼우는 대체품이라 **진짜 시그니처와
        # 짝이 맞아야 한다.** 어긋나면 기록 경로가 TypeError 로 터지고, 그것은 게이트가
        # 막은 것과 구분되지 않는다.
        body = baseline_body(fingerprint, measurement, context)
        self.bodies.append(body)
        return body

    def _write(self, body: dict[str, Any]) -> Path:
        # 실제 `write_baseline`을 **먼저** 부른다 — fail-closed 가드가 거부하면 예외가
        # 나가고 `written`은 비어 있다. 즉 `written`은 "쓰려고 했다"가 아니라
        # **"실제로 쓰였다"**를 뜻한다. 시도만 세면 가드가 막은 실행과 통과한 실행이
        # 같은 모양이 되어, 이 파일이 확인하려는 구분이 사라진다.
        self.attempts.append(body)
        written = write_baseline(body, path=self._path)
        self.written.append(body)
        return written


def _judge_at(fidelity: float, *, low: list[str]) -> JudgeObservation:
    """구분 가능한 judge 관측 하나 — 어느 실행의 것인지 값으로 알아볼 수 있게 만든다."""
    return JudgeObservation(
        scored=len(harness.DOCUMENTS),
        documents=len(harness.DOCUMENTS),
        fidelity_mean=fidelity,
        readability_mean=fidelity,
        low_fidelity_ids=low,
    )


def test_남의_리포트가_최신이어도_judge_가_기준선에_새지_않는다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """**8번째 지적의 입력이 이제 깨끗이 기록된다.**

    문제의 입력은 그대로다: `build_report` 가 여러 번 불리는 프로세스(배선 테스트가 그렇다)
    에서는 남의 실행 리포트가 최신일 수 있다. 예전에는 기록 경로가 `golden_report.latest()`
    로 리포트를 집어 judge 를 **가져왔기 때문에** 수치·조건은 이번 실행인데 judge 만 남의
    실행인 기준선이 만들어졌고, 그래서 출처를 검사해 기록 자체를 거부했다.

    2026-08-13에 judge 를 기준선에서 뺐다 — judge 는 비차단축이라 애초에 하한선의 구성요소가
    아니다(`Baseline` docstring). 리포트에서 **읽는 값이 없어지자** 이 입력은 더 이상 오염될
    수 없다. 그래서 거부가 아니라 **정상 기록**으로 끝나고, 쓰인 본문에는 이번 실행의 조건만
    있고 judge 자리는 아예 없다. 거부를 지웠으면 그 자리에 남는 것은 이 성질이다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    harness.build_report(evaluation)
    # 남의 실행 리포트를 최신으로 세운다 — 수치는 같고 조건(observed)·judge 만 다르다.
    stale_source = evaluate_all(
        _preserving_outcomes("stale-model-from-another-run"), harness.DOCUMENTS
    )
    stale = harness.build_report(stale_source)
    assert golden_report.latest() is stale, "전제 — 남의 리포트가 최신이다"
    assert stale.measurement == evaluation.measurement, "전제 — 수치는 같다(정수 쌍이라 흔하다)"
    assert stale.measurement is not evaluation.measurement, "전제 — 별개 객체다"
    assert stale.context.observed_models == ["stale-model-from-another-run"], "전제 — 조건만 다르다"
    stale.judge = _judge_at(1.25, low=["stale-run-doc"])  # 남의 실행에서 채점된 관측

    path = tmp_path / "baseline.json"
    recording = _Recording(monkeypatch, evaluation, path)
    with pytest.raises(AssertionError, match="기록 실행"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    assert len(recording.written) == 1, "오염될 값이 없는데 기록이 막혔다"
    body = recording.written[0]
    assert "judge_observed" not in body, "남의 judge 가 기준선으로 샜다"
    assert body["context"]["observed_models"] == ["this-run"], (
        "본문은 `evaluation` 에서만 온다 — 남의 리포트가 최신이어도 조건이 새지 않는다"
    )
    assert path.exists()


def test_남의_리포트에는_이번_실행의_판정이_실리지_않는다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """**9번째 지적** — 기준선 파일이 아니라 **사람이 읽는 리포트**가 오염되던 자리다.

    직전 커밋은 "리포트에 쓰는 것은 값을 읽는 게 아니라 방향이 반대이므로 커밋되는 파일의
    무결성과 무관하다"고 방어했다. 그 방어가 틀렸다. `golden_report.latest()` 가 남의
    리포트일 때 기록 경로는 이번 실행의 `floor` 판정과 `baseline_changes` 를 **그 리포트에
    실었고**, 사람이 읽는 것은 그 리포트다 — 다른 실행의 수치 위에 이번 판정이 얹힌다.
    (고치기 전 이 입력을 태우면 stale 리포트의 렌더에 `── 상대 하한선 ──`·`── 기준선 기록 ──`
    절이 나타났고 이번 실행의 리포트는 비어 있었다.)

    검사를 얹어 막지 않았다. 조회를 `for_evaluation(evaluation)` 으로 바꿔 **남의 리포트가
    잡힐 통로 자체를 없앴다.** 그래서 여기서 보는 것은 "거부됐다"가 아니라 **"남의 리포트는
    건드려지지 않았고 이번 리포트에는 그대로 실렸다"**이다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    own = harness.build_report(evaluation)
    stale = harness.build_report(
        evaluate_all(_preserving_outcomes("stale-model-from-another-run"), harness.DOCUMENTS)
    )
    assert golden_report.latest() is stale, "전제 — 남의 리포트가 최신이다"

    path = tmp_path / "baseline.json"
    recording = _Recording(monkeypatch, evaluation, path)
    with pytest.raises(AssertionError, match="기록 실행"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)

    # ① 남의 리포트는 손대지 않는다 — 렌더에도 이번 실행의 절이 나타나지 않는다.
    assert stale.floor is None, "남의 리포트에 이번 실행의 하한선 판정이 실렸다"
    assert stale.baseline_changes == [], "남의 리포트에 이번 실행의 기준선 변경 내역이 실렸다"
    rendered = stale.render()
    assert "── 상대 하한선 ──" not in rendered
    assert "── 기준선 기록 ──" not in rendered
    # ② 이번 평가의 리포트에는 그대로 실린다 — 막아서 아무데도 안 실리는 것과 구분한다.
    assert own.floor is not None
    assert own.baseline_changes == ["- 기준선을 새로 만든다"]
    assert len(recording.written) == 1, "정상 기록이 막혔다"


def test_같은_평가의_리포트에_붙은_judge_도_기준선에_실리지_않는다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """**정상 경로에서도 judge 는 실리지 않는다.** 남의 리포트만 보면 "출처가 나쁠 때만
    빠진다"와 구분되지 않는다 — 빠지는 이유는 출처가 아니라 judge 가 하한선의 구성요소가
    아니라는 것이다.

    실제 실행의 순서가 이것이다 — `evaluation` fixture 가 리포트를 세우고, judge 테스트가
    그 리포트에 관측을 붙이고(`report.judge = ...`), 하한선 테스트가 기록한다. 그 관측은
    리포트에 그대로 남아 렌더·경고로 보이고, 기준선 파일에만 들어가지 않는다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    report = harness.build_report(evaluation)
    observation = _judge_at(4.5, low=[])
    report.judge = observation  # judge 테스트가 이번 실행의 리포트에 붙이는 자리

    path = tmp_path / "baseline.json"
    recording = _Recording(monkeypatch, evaluation, path)
    with pytest.raises(AssertionError, match="기록 실행"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    assert len(recording.written) == 1, "기록되지 않았다"
    body = recording.written[0]
    assert "judge_observed" not in body, "judge 는 비차단축이라 하한선에 실리지 않는다"
    assert body["context"]["observed_models"] == ["this-run"]
    # 정보를 잃은 것이 아니다 — 관측은 리포트에 그대로 남는다.
    assert report.judge is observation
    assert path.exists()


def test_judge_를_재지_않은_실행도_기준선을_기록한다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """부수 경로 — **judge 는 비차단이다.** 키가 없어 judge 테스트가 skip 되거나 실패해서
    `report.judge` 가 비어도 기준선은 기록돼야 한다.

    judge 자격 증명 하나 때문에 두 차단축의 기록이 막히는 것은 `judge_provider` fixture 가
    실패가 아니라 skip 인 이유와 같은 자리다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    report = harness.build_report(evaluation)
    assert report.judge is None, "전제 — judge 를 재지 않은 상태(키 없음 → skip)"

    path = tmp_path / "baseline.json"
    recording = _Recording(monkeypatch, evaluation, path)
    with pytest.raises(AssertionError, match="기록 실행"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    assert len(recording.written) == 1, "judge 가 없다는 이유로 기록이 막혔다 — 과잉이다"
    assert "judge_observed" not in recording.written[0]
    assert path.exists()


def test_같은_평가로_세운_리포트는_그대로_기록된다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """**정상 경로는 여전히 기록된다.** 거부되는 쪽만 보면 게이트를 잠가 버린 것과 구분되지 않는다.

    앞선 수정이 정확히 그렇게 결함을 남겼다 — 가드를 넣으면서 조건 유도 자리를 빠뜨려
    기록 경로가 통째로 막혔는데, 거부 시나리오만 확인해 통과했다.
    기록된 조건이 **이번 실행의 모델 증거**인지까지 본다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    harness.build_report(evaluation)
    path = tmp_path / "baseline.json"
    recording = _Recording(monkeypatch, evaluation, path)

    # 기록 실행은 판정이 아니므로 실패로 끝나는 것이 정상이다 — 중요한 것은 **기록됐는가**다.
    with pytest.raises(AssertionError, match="기록 실행"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    assert len(recording.written) == 1, "결속된 리포트인데 기록되지 않았다"
    body = recording.written[0]
    assert body["context"]["observed_models"] == ["this-run"], (
        "이번 실행의 모델 증거가 아니다 — 수치와 조건은 같은 실행에서 와야 한다"
    )
    assert body["measurement"] == evaluation.measurement.model_dump()
    assert path.exists()


def test_리포트가_없으면_기록하지_않는다(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """부수 경로 — `report is None`은 fail-closed다(무변경).

    리포트가 없다는 것은 이번 실행이 리포트로 조립되지 않았다는 뜻이다. 예전에는 인자 없는
    `run_context()`로 떨어져 관측 모델이 비고 `write_baseline` 가드가 거부했다. 지금은
    측정치·조건이 `evaluation` 에 있어 그 자리에서는 거부되지 않으므로, 기록 경로가 **본문을
    만들기 전에** 리포트 부재를 보고 거부한다. 결과는 같다 — 기준선을 쓰지 않는다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    golden_report.reset()  # 리포트를 세우지 않은 상태를 만든다
    assert golden_report.for_evaluation(evaluation) is None
    path = tmp_path / "baseline.json"
    recording = _Recording(monkeypatch, evaluation, path)
    with pytest.raises(AssertionError, match="리포트가 없다"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    assert recording.bodies == [], "리포트 부재를 본문 조립 전에 막아야 한다"
    assert recording.attempts == [], "write 를 시도했다 — 가드에 기대지 말고 먼저 막는다"
    assert recording.written == [], "기준선을 썼다"
    assert not path.exists()


def test_남의_리포트만_있으면_이번_실행은_기록하지_않는다(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """가드의 뜻이 **"이번 평가의 리포트가 있는가"**로 좁혀졌음을 고정한다.

    예전 조회(`latest()`)에서는 남의 리포트가 하나라도 있으면 가드가 통과했다 — 리포트가
    "있기는" 했으니까. 그 통과가 곧 남의 리포트에 이번 판정을 쓰는 경로였다. 이제 조회가
    결속을 요구하므로, 리포트가 있어도 **이번 평가의 것이 아니면** 기록하지 않는다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    stale = harness.build_report(
        evaluate_all(_preserving_outcomes("stale-model-from-another-run"), harness.DOCUMENTS)
    )
    assert golden_report.latest() is stale, "전제 — 리포트가 있기는 하다(남의 것이다)"
    path = tmp_path / "baseline.json"
    recording = _Recording(monkeypatch, evaluation, path)
    with pytest.raises(AssertionError, match="리포트가 없다"):
        harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    assert recording.bodies == [], "본문 조립 전에 막아야 한다"
    assert recording.written == [], "기준선을 썼다"
    assert stale.floor is None, "남의 리포트에 이번 실행의 판정이 실렸다"
    assert stale.baseline_changes == []
    assert not path.exists()


def test_비기록_모드는_남의_리포트가_최신이어도_정상_판정한다(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """부수 경로 — 기록에 관한 것은 **기록 분기 안에만** 있다(무변경).

    비기록 실행은 기준선을 쓰지 않으므로 남의 조건이 파일에 실릴 일이 없다. 기록 분기 밖으로
    무언가를 끌어올리면 평범한 판정 실행이 리포트 부재·순서만으로 막힌다 — 그런 일이 없음을
    남의 실행 리포트를 최신으로 둔 채 확인한다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    own = harness.build_report(evaluation)
    stale = harness.build_report(
        evaluate_all(_preserving_outcomes("stale-model-from-another-run"), harness.DOCUMENTS)
    )
    monkeypatch.setattr(harness, "load_baseline", lambda: _matching_baseline(evaluation))
    monkeypatch.setattr(harness, "recording_requested", lambda: False)
    monkeypatch.setattr(
        harness, "write_baseline", lambda body: pytest.fail("비기록 모드가 기준선을 썼다")
    )
    harness.test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation)
    # 판정 자체는 기록 분기 밖이라 비기록 실행에서도 리포트에 실린다 — **이번 리포트에만**.
    assert own.floor is not None
    assert stale.floor is None, "비기록 실행이 남의 리포트에 판정을 실었다"


def test_평가가_관측_모델을_측정치와_함께_싣는다() -> None:
    """**구조적 결속의 근원** — `evaluate_all` 이 measurement 와 observed_models 를 같은
    outcomes 로 한 객체에 싣는다. 둘이 한 객체에서 나오므로 기록 경로가 조건을 다른 곳에서
    맞출 필요가 없다.
    """
    evaluation = evaluate_all(_preserving_outcomes("model-x"), harness.DOCUMENTS)
    assert evaluation.observed_models == ["model-x"]
    # 변환이 전건 실패하면 관측 모델도 빈다 — write_baseline 가드가 무-모델 기록을 거부한다.
    assert evaluate_all({}, harness.DOCUMENTS).observed_models == []
    # build_report 의 조건도 같은 값에서 온다 — outcomes 를 따로 받지 않는다.
    report = harness.build_report(evaluation)
    assert report.context.observed_models == evaluation.observed_models


def test_build_report_는_조건을_따로_받지_않는다() -> None:
    """6번째 지적의 공격(`build_report(result, 다른_outcomes)`)이 **표현 불가**임을 고정한다.

    조건을 result 와 분리해 넘기는 통로가 있으면 측정치 동일성을 지키면서 남의 조건을 싣는
    상태를 만들 수 있다. 그 통로가 없어야 구조로 닫힌다 — mypy 도 이 호출을 거부한다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    with pytest.raises(TypeError):
        harness.build_report(evaluation, {})  # type: ignore[call-arg]  # 조건 인자 통로 부재 고정


def test_리포트는_평가에_결속해야만_등록된다() -> None:
    """**결속 없는 등록 통로가 없다** — 9번째 지적을 구조로 닫는 자리다.

    등록에 평가를 요구하지 않으면 "누구 것인지 말할 수 없는 리포트"가 생기고, 그런 리포트를
    나중에 `latest()` 로 집어 오는 경로가 다시 열린다. 인자를 필수로 두면 그 상태가 애초에
    만들어지지 않는다(mypy 도 이 호출을 거부한다).
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    report = harness.build_report(evaluation)
    with pytest.raises(TypeError):
        golden_report.record(report)  # type: ignore[call-arg]  # 결속 인자 필수 고정
    assert golden_report.for_evaluation(evaluation) is report


def test_수치가_같은_다른_평가의_리포트는_잡히지_않는다() -> None:
    """키는 **값이 아니라 객체 동일성**이다.

    값으로 잡으면 수치가 같은 두 실행이 겹친다 — 통과 수는 정수 쌍이라 같은 값이 흔하고,
    아래 두 평가가 정확히 그 입력이다(`==` 로는 구분되지 않고 `is` 로만 갈린다).
    """
    first = evaluate_all(_preserving_outcomes("run-a"), harness.DOCUMENTS)
    second = evaluate_all(_preserving_outcomes("run-b"), harness.DOCUMENTS)
    assert first.measurement == second.measurement, "전제 — 값으로는 구분되지 않는다"
    report = harness.build_report(first)
    assert golden_report.for_evaluation(first) is report
    assert golden_report.for_evaluation(second) is None, "값이 같다는 이유로 남의 리포트가 잡혔다"


# ═══════════════════════════════════════════ 비차단축: judge


async def test_날조가_섞여도_judge는_차단하지_않는다() -> None:
    """2026-08-12 결정의 배선 — judge는 경고로 남기고 실패시키지 않는다.

    예전에는 이 입력이 `AssertionError`를 냈다. 그 보호를 대체한 것이 필수 정보 보존
    게이트이며, 위 `test_필수_정보가_빠지면_하네스가_실패한다`가 그 자리를 지킨다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    responses: list[str | Exception] = [_HIGH] * (len(harness.DOCUMENTS) - _FABRICATED_COUNT)
    responses += [_FABRICATED] * _FABRICATED_COUNT
    with pytest.warns(UserWarning, match="judge 비차단"):
        await harness.test_judge_점수를_기록한다(evaluation, FakeProvider(responses=responses))


async def test_날조는_경고와_리포트에_남는다() -> None:
    """차단하지 않는 것과 조용히 넘기는 것은 다르다 — 바닥 판정은 계속 돌아야 한다.

    `find_low_fidelity` 호출을 지우면 이 테스트가 깨진다.
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    report = harness.build_report(evaluation)
    responses: list[str | Exception] = [_FABRICATED] * len(harness.DOCUMENTS)
    with pytest.warns(UserWarning, match="충실성"):
        await harness.test_judge_점수를_기록한다(evaluation, FakeProvider(responses=responses))
    assert report.judge is not None
    assert len(report.judge.low_fidelity_ids) == len(harness.DOCUMENTS)
    assert "충실성" in report.render()


async def test_judge_점수가_리포트에_실린다() -> None:
    """통과하는 실행에서도 judge 수치가 남아야 한다 — 기록·경고용이라는 뜻이 이것이다."""
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    report = harness.build_report(evaluation)
    responses: list[str | Exception] = [_HIGH] * len(harness.DOCUMENTS)
    await harness.test_judge_점수를_기록한다(evaluation, FakeProvider(responses=responses))
    assert report.judge is not None
    assert report.judge.scored == len(harness.DOCUMENTS)
    assert report.judge.fidelity_mean == 5.0
    assert report.judge.low_fidelity_ids == []


async def test_남의_리포트에는_judge_관측이_실리지_않는다() -> None:
    """judge 도 같은 구조로 닫힌다 — 관측을 실을 리포트를 **이번 평가로** 찾는다.

    예전에는 `golden_report.latest()` 에 대입해서, 남의 리포트가 최신이면 이번 실행의 judge
    수치가 남의 리포트에 실렸다(하한선 기록 경로의 9번째 지적과 같은 모양이다). 사람이 읽는
    것이 그 리포트라는 점도 같다.
    """
    evaluation = evaluate_all(_preserving_outcomes("this-run"), harness.DOCUMENTS)
    own = harness.build_report(evaluation)
    stale = harness.build_report(
        evaluate_all(_preserving_outcomes("stale-model-from-another-run"), harness.DOCUMENTS)
    )
    assert golden_report.latest() is stale, "전제 — 남의 리포트가 최신이다"
    responses: list[str | Exception] = [_HIGH] * len(harness.DOCUMENTS)
    await harness.test_judge_점수를_기록한다(evaluation, FakeProvider(responses=responses))
    assert stale.judge is None, "남의 리포트에 이번 실행의 judge 관측이 실렸다"
    assert stale.judge_notes == []
    assert own.judge is not None, "이번 실행의 리포트에는 실려야 한다"


async def test_judge_는_이번_평가의_outcomes_를_채점한다() -> None:
    """**10번째 지적** — 붙이는 자리는 묶였는데 **채점 대상**이 안 묶여 있었다.

    직전 커밋은 관측을 실을 리포트를 `for_evaluation(evaluation)` 으로 찾게 해 그릇을
    결속했다. 그런데 judge 테스트는 채점할 `outcomes` 를 **별도 fixture 로** 받았다 —
    그릇만 묶고 내용물을 안 묶은 것이라, A 의 outcomes 를 채점해 B 의 평가 리포트에 싣는
    상태가 구조적으로 표현 가능했다.

    고치기 전 그 상태를 실제로 만들었다(`_preserving_outcomes()` 중 첫 문서만 `None` 인
    남의 outcomes 를 넘김). 이번 평가는 전건 변환 성공인데도 리포트에는 `001: 변환실패`
    라는 남의 note 가 실렸고, 저충실성 지목이 `['002']` 로 **한 칸 밀렸다** — 응답열이
    건너뛴 문서만큼 어긋났기 때문이다. 이번 평가의 outcomes 로 채점하면 `['001']` 이다.

    이제 채점 대상이 `evaluation.outcomes` 뿐이라 그 어긋남을 만들 표현이 없다. 여기서
    보는 것은 **"거부됐다"가 아니라 지목이 이번 평가의 outcomes 를 따른다**는 것이다.
    """
    docs = harness.DOCUMENTS
    evaluation = evaluate_all(_preserving_outcomes("this-run"), docs)
    report = harness.build_report(evaluation)
    assert evaluation.conversion_failures == [], "전제 — 이번 평가는 전건 변환 성공이다"

    # 첫 응답만 날조 — 어느 문서가 지목되는지가 **채점 대상**에 달린 입력이다.
    responses: list[str | Exception] = [_HIGH] * len(docs)
    responses[0] = _FABRICATED
    with pytest.warns(UserWarning, match="충실성"):
        await harness.test_judge_점수를_기록한다(evaluation, FakeProvider(responses=responses))

    assert report.judge is not None
    assert report.judge.low_fidelity_ids == [docs[0].id], (
        "지목이 이번 평가의 outcomes 를 따르지 않는다 — 다른 실행의 산출물을 채점했다"
    )
    assert report.judge.scored == len(docs), "이번 평가는 전건 채점 대상이다"
    assert not any("변환실패" in note for note in report.judge_notes), (
        "이번 평가에는 변환 실패가 없는데 남의 실행 note 가 실렸다"
    )


async def test_judge_는_채점_대상을_따로_받지_않는다() -> None:
    """**통로 부재를 고정한다** — 남겨 두면 그릇만 묶은 상태로 되돌아간다.

    `outcomes` fixture 자체는 남아 있다(`evaluate_all` 의 입력이다). 없앤 것은 **judge 가
    그것을 직접 받는 것**이다. 인자가 있으면 `test_judge_점수를_기록한다(evaluation,
    다른_outcomes, provider)` 로 위 어긋남을 다시 조립할 수 있다 — mypy 도 이 호출을
    거부한다(`build_report` 의 조건 인자를 없앨 때와 같은 자리).
    """
    evaluation = evaluate_all(_preserving_outcomes(), harness.DOCUMENTS)
    harness.build_report(evaluation)
    provider = FakeProvider(responses=[_HIGH] * len(harness.DOCUMENTS))
    with pytest.raises(TypeError):
        await harness.test_judge_점수를_기록한다(
            evaluation,
            # 대상 인자 통로 부재 고정 — mypy 도 이 호출을 거부한다(정적으로도 통로가 없다).
            _preserving_outcomes(),  # type: ignore[arg-type]
            provider,  # type: ignore[call-arg]
        )


def test_평가가_채점_대상을_측정치와_함께_싣는다() -> None:
    """근원이 하나임을 고정한다 — `evaluate_all` 이 잰 그 산출물이 평가에 실린다.

    비교는 **객체 동일성**으로 한다. 값 비교(`==`)를 쓰면 실패 출력에 변환문 전문이 실리는데,
    이 하네스는 문서 id·사유 코드·수치까지만 남기는 규약이다(`report.py` 모듈 docstring).
    동일성이 더 강한 판정이기도 하다 — 같은 값의 다른 객체를 실어도 걸린다.

    바깥 dict 는 pydantic 이 복제해 별개다. 호출자가 나중에 자기 dict 를 고쳐도 평가가 든
    대상은 움직이지 않는다는 뜻이라, 이 방향으로도 대상이 갈릴 수 없다.
    """
    outcomes = _preserving_outcomes("model-x")
    evaluation = evaluate_all(outcomes, harness.DOCUMENTS)
    assert set(evaluation.outcomes) == set(outcomes), "채점 대상의 문서 집합이 다르다"
    assert all(evaluation.outcomes[key] is outcomes[key] for key in outcomes), (
        "평가가 든 산출물이 잰 것과 다른 객체다 — judge 가 채점하는 것과 규칙 평가가 잰 것이 갈린다"
    )
    assert evaluation.outcomes is not outcomes, (
        "바깥 dict 는 복제본이다(호출자 변경에 흔들리지 않는다)"
    )
    # 변환 실패도 그대로 실린다 — judge 가 "무엇을 못 쟀는가"를 같은 근원에서 본다.
    partial = dict(outcomes)
    partial[harness.DOCUMENTS[0].id] = None
    assert evaluate_all(partial, harness.DOCUMENTS).outcomes[harness.DOCUMENTS[0].id] is None


# ═══════════════════════════════════════════ 게이트 실행 전제: 자격 증명


def test_자격_증명이_없으면_게이트가_실패한다(monkeypatch: pytest.MonkeyPatch) -> None:
    """**측정 0건은 통과가 아니다.**

    예전에는 provider fixture가 `pytest.skip`이라, `-m llm`으로 게이트를 명시 실행해도 키가
    없으면 `3 skipped, 63 deselected` + 종료 코드 0으로 끝났다(2026-08-12 codex 리뷰 #2가
    직접 재현). 판정하지 못한 것이 통과로 보이는 상태이며, 계획이 지정한 기준선 최초 기록
    명령도 같은 함정에 걸린다 — 파일이 생기지 않은 채 성공 종료한다.
    """
    monkeypatch.setattr(harness, "create_provider", lambda name, settings: None)
    with pytest.raises(AssertionError, match="자격 증명"):
        harness.require_provider("anthropic", Settings())


def test_자격_증명이_있으면_provider를_그대로_돌려준다(monkeypatch: pytest.MonkeyPatch) -> None:
    """게이트가 무조건 실패하는 것이 아님을 함께 고정한다."""
    created = FakeProvider(responses=[])
    monkeypatch.setattr(harness, "create_provider", lambda name, settings: created)
    assert harness.require_provider("anthropic", Settings()) is created


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
