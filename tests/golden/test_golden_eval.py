"""골든셋 실제 LLM 평가 — 기본 실행에서 제외된다(pytest addopts의 -m 'not llm').

실행: `GOLDEN_PROVIDER=anthropic uv run pytest tests/golden -m llm`
기준선 갱신: 같은 명령에 `GOLDEN_RECORD_BASELINE=1` 을 붙인다. **기록 실행은 판정이
아니므로** 기준선이 바뀌면 실패로 끝난다 — diff를 커밋한 뒤 플래그 없이 다시 돌린다.

변환은 모듈 스코프 fixture에서 한 번만 수행하고 세 축이 공유한다.
실패 출력에는 문서 id와 사유 코드·건수만 남긴다 (본문·문장·팩트 리터럴 출력 금지).

**자격 증명이 없으면 skip이 아니라 실패한다**(`require_provider`). 이 모듈은 `-m llm`으로
명시 선택했을 때만 도는데, 그때 키가 없으면 측정이 0건이고 0건은 통과가 아니다.

통과율의 **정의**(무엇을 실패로 세고 무엇을 분모에 넣는가)는 여기 없다 —
`tests/golden/evaluation.py`에 있고, 판정 기준 지문이 그 모듈을 통째로 걷는다.

## 세 축과 그 차단 성격 (2026-08-12 사용자 결정)

| 축 | 차단하는가 | 기준 |
|---|---|---|
| 규칙 기반 통과율 | **차단** | **상대** — 직전에 기록된 측정치보다 낮아지지 않는다 |
| 필수 정보 보존 | **차단** | **절대** — 누락 0건. LLM을 쓰지 않는 결정적 검사다 |
| judge 점수 | **차단하지 않는다** | 기록·경고용. 목표선 대비 위치를 리포트에 싣는다 |

세 축의 성격이 다른 이유:

- **규칙 통과율이 상대인 이유.** 실측이 0.446~0.643인데 절대선 0.90을 차단으로 두면
  게이트가 상시 빨간불이 된다. 실수집 52.8%는 구현 결함이 아니라 **코퍼스 난이도**로
  판정됐다(`docs/migration/_workspace/02_quality-baseline.md` §4·§5.4 — 2,000자를 경계로
  통과율이 91%→7%로 무너지고, 최장 문장 상위 8건이 전부 실수집의 PDF 추출 아티팩트다).
  현재 성능을 합격선으로 옮기지 않으면서 회귀만 막는 방법이 상대 하한선이다.
- **judge가 차단하지 않는 이유.** 채점 모델을 고정할 수단이 없다 —
  `GOLDEN_JUDGE_PROVIDER`는 벤더만 고르고 모델은 `settings.llm_model`을 따른다.
  모델이 바뀌면 **우리 코드를 한 줄도 고치지 않았는데** CI가 빨개진다. 점수는 계속 재고
  기록하되 판정에는 쓰지 않는다(`02_quality-baseline.md` §6.5).
- **필수 정보 보존만 절대인 이유.** judge가 차단하지 못하게 되면서
  `DEFAULT_FIDELITY_FLOOR`(judge 축)가 막던 중요 정보 누락이 무방비가 된다. 금액·기한·
  대상이 빠진 결과물은 품질이 낮은 안내문이 아니라 **틀린 안내문**이라 "직전보다
  나빠지지 않았으면 됨"의 대상이 아니다.

⚠ **이 절대 기준은 지금 통과하지 못할 가능성이 높다.** 마지막으로 저장된 실행
(2026-08-08 anthropic)의 팩트 잔존율은 90.1%였고 **14개 문서에서 누락**이 있었다
(`02_quality-baseline.md` §5.3). 그 상태를 통과로 만들어 주지 않는 것이 이 게이트의
목적이다 — 수치를 낮춰 맞추지 말고, 무엇이 빠지는지를 리포트로 보고 프롬프트를 고친다.
"""

import os
import warnings
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio

from app.config import Settings
from app.easyread.goldenset import GoldenDocument, load_documents
from app.easyread.judge import (
    DEFAULT_FIDELITY_FLOOR,
    JudgeScore,
    find_low_fidelity,
    judge_conversion,
)
from app.exceptions import LLMProviderError
from app.llm.factory import create_provider
from app.llm.provider import LLMProvider
from app.services.conversion import ConversionOutcome, ConversionService
from tests.golden import DOCUMENTS_DIR
from tests.golden import report as golden_report
from tests.golden.baseline import (
    Fingerprint,
    JudgeObservation,
    RunContext,
    baseline_body,
    baseline_changes,
    compare,
    load_baseline,
    recording_requested,
    write_baseline,
)
from tests.golden.evaluation import DocumentEvaluation, RuleEvaluation, evaluate_all

pytestmark = pytest.mark.llm

DEFAULT_PROVIDER = "anthropic"
DEFAULT_JUDGE_PROVIDER = "anthropic"

# ─────────────────────────────────────────────────────────────────────────────
# 목표선 — **차단하지 않는다.** 2026-08-12 결정으로 통과율의 차단 기준은 상대 하한선
# (직전 기록 대비)으로 바뀌었고, 아래 수치들은 "지금 어디쯤인가"를 읽는 눈금으로 남는다.
# 지우지 않는 이유: 0.90은 master-plan §7 KPI가 정한 제품 목표이고, 차단에서 내려왔다고
# 목표가 사라진 것이 아니다. 리포트가 매 실행 목표 대비 현재를 함께 찍는다.
#
# 통계 한계(그대로 유효): n=20~56 단일 실행이다. 문서별 성공 확률이 진짜 90%인 provider도
# 20건 중 18건 미만이 나올 확률이 약 32%다. 경계에서 흔들릴 경우 재실행·표본 확대 판단은
# 사람이 한다(자동 재시도로 숨기지 않는다).
# ─────────────────────────────────────────────────────────────────────────────
PASS_RATE_THRESHOLD = 0.9
# 채점이 이루어진 문서 비율 하한 — 대부분 실패한 provider가 남은 소수의 높은 점수로
# 통과하는 것을 막는 별도 기준이다(통과율 기준과 목적이 달라 상수를 분리한다).
JUDGE_COVERAGE_THRESHOLD = 0.9
JUDGE_SCORE_THRESHOLD = 4.0
# 바닥 게이트: fidelity 1~2는 중요 정보 누락·날조라 평균으로 상쇄할 수 없다.
# 평균 게이트만 두면 15건 5점 + 5건 1점(날조 25%)이 평균 4.0으로 통과한다.
# **판정에는 쓰지 않는다**(judge 비차단). 계속 계산해 리포트·경고로 남기며, 이 축이 막던
# 정보 누락은 아래 필수 정보 보존 게이트가 LLM 없이 절대 기준으로 받는다.
FIDELITY_FLOOR = DEFAULT_FIDELITY_FLOOR

#: 필수 정보 보존의 **절대** 허용치. 상대 기준의 대상이 아니다 —
#: 필수 사실이 빠진 안내문은 품질이 낮은 것이 아니라 틀린 것이다.
REQUIRED_FACT_LOSS_LIMIT = 0

DOCUMENTS: list[GoldenDocument] = load_documents(DOCUMENTS_DIR)

TARGETS = golden_report.Targets(
    pass_rate=PASS_RATE_THRESHOLD,
    judge_coverage=JUDGE_COVERAGE_THRESHOLD,
    judge_score=JUDGE_SCORE_THRESHOLD,
    fidelity_floor=FIDELITY_FLOOR,
)


def require_provider(name: str, settings: Settings) -> LLMProvider:
    """게이트 실행에 쓸 provider. **없으면 skip이 아니라 실패다.**

    이 모듈은 `-m llm`으로 **명시 선택했을 때만** 돈다(기본 addopts는 `-m 'not llm'`).
    즉 이 함수가 불렸다는 것 자체가 "게이트를 돌리라"는 지시이고, 그때 자격 증명이 없으면
    측정이 0건이다. 0건을 skip으로 넘기면 `3 skipped` + 종료 코드 0이 되어 **판정하지 못한
    것이 통과로 보인다**(2026-08-12 codex 리뷰 #2 — 명시 실행에서 직접 재현됐다).

    계획이 지정한 기준선 최초 기록 명령(`GOLDEN_RECORD_BASELINE=1 … -m llm`)에도 같은 함정이
    있었다. 키 설정이 잘못된 채로 돌리면 `baseline.json`이 생기지 않은 채 성공 종료해,
    운영자는 기준선이 기록됐다고 믿게 된다.
    """
    created = create_provider(name, settings)
    if created is None:
        raise AssertionError(
            f"{name} 자격 증명이 없어 골든셋 게이트를 실행할 수 없다 — **측정 0건은 통과가 "
            "아니다.** 키를 주고 다시 돌리거나, 게이트를 돌릴 생각이 아니었다면 `-m llm`을 "
            "빼라(기본 실행은 이 모듈을 애초에 선택하지 않는다)"
        )
    return created


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def provider() -> AsyncIterator[LLMProvider]:
    """GOLDEN_PROVIDER로 지정한 변환용 provider (**키가 없으면 실패**)."""
    created = require_provider(os.environ.get("GOLDEN_PROVIDER", DEFAULT_PROVIDER), Settings())
    try:
        yield created
    finally:
        await created.aclose()


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def judge_provider() -> AsyncIterator[LLMProvider]:
    """GOLDEN_JUDGE_PROVIDER로 지정한 채점용 provider (키가 없으면 skip).

    **변환용 provider와 달리 여기는 skip이 맞다.** judge는 비차단축이라(2026-08-12 결정)
    채점을 못 해도 판정하지 못한 차단축이 생기지 않는다. 반대로 여기서 실패시키면 judge
    자격 증명이 없다는 이유로 두 차단축의 판정까지 막게 된다.
    """
    name = os.environ.get("GOLDEN_JUDGE_PROVIDER", DEFAULT_JUDGE_PROVIDER)
    created = create_provider(name, Settings())
    if created is None:
        pytest.skip(f"{name} API 키 없음 — judge 채점을 건너뜁니다")
    try:
        yield created
    finally:
        await created.aclose()


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def outcomes(provider: LLMProvider) -> dict[str, ConversionOutcome | None]:
    """전 문서를 한 번만 변환해 세 축이 공유한다(LLM 호출 절감)."""
    return await convert_all(provider)


@pytest.fixture(scope="module")
def evaluation(outcomes: dict[str, ConversionOutcome | None]) -> RuleEvaluation:
    """규칙 평가를 만들고 **리포트를 먼저 세운다.**

    리포트를 여기서 세우는 이유: 아래 테스트가 하나라도 실패하거나 skip되어도 이번 실행의
    수치가 남아야 한다. 통과 실행에서 수치가 사라지던 것이 상대 하한선을 세우지 못한
    원인이었다(`tests/golden/report.py`).
    """
    result = evaluate_all(outcomes, DOCUMENTS)
    build_report(result, outcomes)
    return result


def build_report(
    result: RuleEvaluation,
    outcomes: dict[str, ConversionOutcome | None] | None = None,
) -> golden_report.GoldenRunReport:
    """이번 실행의 리포트를 세워 붙든다. 배선 테스트도 같은 경로를 쓴다."""
    return golden_report.record(
        golden_report.GoldenRunReport(
            fingerprint=Fingerprint.of(DOCUMENTS),
            context=run_context(outcomes),
            targets=TARGETS,
            measurement=result.measurement,
            failure_reasons=result.failure_reasons,
            conversion_failures=result.conversion_failures,
            fact_losses=result.fact_losses,
        )
    )


def run_context(
    outcomes: dict[str, ConversionOutcome | None] | None = None,
) -> RunContext:
    """비교의 조건 — 판정에 쓰지 않고 기록만 한다.

    `observed_models`는 **변환 응답이 실제로 보고한 모델**이다(`LLMResponse.model`).
    `settings.llm_model`은 설정값이라 별칭 해석·폴백이 있으면 실제와 갈린다 — 그래서
    둘을 따로 싣는다. 기준선 기록은 관측값이 있어야만 허용된다(`write_baseline`).
    """
    settings = Settings()
    observed = sorted({o.model for o in (outcomes or {}).values() if o is not None})
    return RunContext(
        provider=os.environ.get("GOLDEN_PROVIDER", DEFAULT_PROVIDER),
        judge_provider=os.environ.get("GOLDEN_JUDGE_PROVIDER", DEFAULT_JUDGE_PROVIDER),
        model=settings.llm_model,
        observed_models=observed,
        effort=settings.llm_effort,
    )


async def convert_all(provider: LLMProvider) -> dict[str, ConversionOutcome | None]:
    """전 문서를 변환한다. 변환 실패는 None으로 기록하고 계속 진행한다."""
    service = ConversionService(provider=provider)
    results: dict[str, ConversionOutcome | None] = {}
    for document in DOCUMENTS:
        try:
            results[document.id] = await service.convert(document.source_text)
        except LLMProviderError:
            # 예외 메시지에도 본문이 없도록 설계되어 있으나, 출력은 사유 코드로만 남긴다.
            results[document.id] = None
    return results


def format_report(evaluations: list[DocumentEvaluation]) -> str:
    """실패 문서 목록 — 문서 id와 사유 코드만 출력한다."""
    return "\n".join(
        f"- {evaluation.document_id}: {', '.join(evaluation.failures)}"
        for evaluation in evaluations
        if not evaluation.passed
    )


# ═══════════════════════════════════════════════════════════ 차단축 1: 필수 정보 보존


def test_필수_정보가_보존된다(evaluation: RuleEvaluation) -> None:
    """**절대 기준.** 필수 사실이 하나라도 빠지면 차단한다 — LLM을 쓰지 않는 결정적 검사다.

    이 게이트만 절대인 근거는 모듈 docstring에 있다. 요약하면 금액·기한·대상 누락은 품질
    저하가 아니라 **틀린 안내문**이고, "직전보다 나빠지지 않았으면 됨"으로 다룰 수 없다.

    판정은 `RequiredFact.retained_in`의 부분 문자열 일치뿐이라 모델도 난수도 개입하지
    않는다 — 절대 기준을 세울 수 있는 것은 검사가 결정적이기 때문이다.
    """
    # 판정하지 못한 문서는 통과가 아니다. 변환에 실패한 문서는 `find_fact_losses`의 입력에
    # 아예 들어가지 않으므로, 전건 실패한 실행은 누락 0건으로 **공허하게 통과**한다.
    assert not evaluation.conversion_failures, (
        f"변환 실패 {len(evaluation.conversion_failures)}건 — 그 문서들은 필수 정보 보존을 "
        "판정하지 못했다. 판정하지 못한 것을 통과로 셀 수 없다: "
        + ", ".join(evaluation.conversion_failures)
    )
    losses = evaluation.fact_losses
    total = sum(loss.missing for loss in losses)
    detail = "\n".join(
        f"- {loss.document_id}: {loss.missing}/{loss.required}건 누락" for loss in losses
    )
    # 비교식은 **누락 건수**를 본다. 문서 수(`len(losses)`)로 바꾼 완화가 상수·문구를 그대로
    # 둔 채 무검출이었다(2026-08-12 교차 리뷰 T-2) — 예고된 "14문서까지는 봐준다"가 그 편집이다.
    assert total <= REQUIRED_FACT_LOSS_LIMIT, (
        f"필수 정보 누락 {total}건 / 문서 {len(losses)}건 "
        f"(허용 {REQUIRED_FACT_LOSS_LIMIT}건 — 절대 기준, 상대 하한선의 대상이 아니다)\n"
        f"{detail}\n"
        "이 축은 완화하지 않는다. 금액·기한·대상이 빠진 결과물은 품질이 낮은 안내문이 아니라 "
        "틀린 안내문이다 — 고칠 곳은 기준이 아니라 프롬프트다"
    )


# ═══════════════════════════════════════════════════════════ 비차단축: judge 점수


@pytest.mark.asyncio(loop_scope="module")
async def test_judge_점수를_기록한다(
    outcomes: dict[str, ConversionOutcome | None], judge_provider: LLMProvider
) -> None:
    """**차단하지 않는다.** 점수를 재서 리포트와 경고로 남긴다.

    차단하지 않는 이유는 채점자를 고정할 수단이 없어서다 — 모델이 바뀌면 우리 코드를 한 줄도
    고치지 않았는데 CI가 빨개진다. 그렇다고 그냥 통과시키지도 않는다. 목표선을 밑돌면
    `UserWarning`으로 올리고 리포트에 수치를 싣는다.

    이 축이 막던 정보 누락은 위의 필수 정보 보존 게이트가 LLM 없이 절대 기준으로 받는다.
    """
    scores: dict[str, JudgeScore] = {}
    notes: list[str] = []
    for document in DOCUMENTS:
        outcome = outcomes.get(document.id)
        if outcome is None:
            notes.append(f"{document.id}: 변환실패")
            continue
        try:
            scores[document.id] = await judge_conversion(
                judge_provider, source=document.source_text, converted=outcome.easy_text
            )
        except LLMProviderError:
            notes.append(f"{document.id}: 채점실패")

    observation = summarize_judge(scores)
    coverage = observation.scored / observation.documents if observation.documents else 0.0
    if coverage < JUDGE_COVERAGE_THRESHOLD:
        notes.append(
            f"채점 커버리지 {coverage:.2f} < 목표 {JUDGE_COVERAGE_THRESHOLD} — "
            "대부분 채점에 실패했다면 아래 평균은 남은 소수만의 값이다"
        )
    if observation.low_fidelity_ids:
        notes.append(
            f"충실성 {FIDELITY_FLOOR} 이하 문서 {len(observation.low_fidelity_ids)}건: "
            f"{', '.join(observation.low_fidelity_ids)} — 날조·중요 정보 누락 의심. "
            "차단하지는 않으나 필수 정보 보존 게이트와 함께 읽을 것"
        )
    if scores and observation.fidelity_mean < JUDGE_SCORE_THRESHOLD:
        notes.append(f"충실성 평균 {observation.fidelity_mean:.2f} < 목표 {JUDGE_SCORE_THRESHOLD}")
    if scores and observation.readability_mean < JUDGE_SCORE_THRESHOLD:
        notes.append(
            f"이해 용이성 평균 {observation.readability_mean:.2f} < 목표 {JUDGE_SCORE_THRESHOLD}"
        )

    report = golden_report.latest()
    if report is not None:
        report.judge = observation
        report.judge_notes = notes
    for note in notes:
        # 경고로 올린다 — 통과시키되 조용하지는 않게. pytest가 요약에 모아 보여 준다.
        warnings.warn(f"[judge 비차단] {note}", UserWarning, stacklevel=2)


# ═══════════════════════════════════════════════════════ 차단축 2: 규칙 통과율(상대)


def test_규칙_기반_통과율이_직전_기록보다_낮지_않다(evaluation: RuleEvaluation) -> None:
    """**상대 기준.** 직전에 기록된 측정치보다 낮아지면 차단한다.

    지문이 다르면(코퍼스나 판정 기준이 바뀌면) 수치를 비교하지 않고 **비교 불가**로
    떨어진다 — 문서를 빼서 통과율이 오르는 경로를 막는 자리다.

    기록 모드(`GOLDEN_RECORD_BASELINE=1`)에서 기준선이 **실제로 바뀌면 이 테스트는
    실패한다.** 기록 실행은 판정이 아니기 때문이다 — 기준선을 방금 갱신한 실행과 애초에
    문제없던 실행이 같은 결과를 내면 자동화가 둘을 구분하지 못한다.

    **judge 테스트보다 뒤에 있어야 한다.** 기준선은 judge 관측치를 함께 담는데(비교하지는
    않고 기록만 한다), pytest는 파일에 적힌 순서로 돌기 때문에 judge가 뒤에 오면 기록되는
    값이 언제나 비어 있다. 판정 자체는 순서와 무관하다 — judge가 건너뛰어도(키 없음)
    이 게이트는 그대로 돈다.
    """
    # 분모가 코퍼스 전건인지 먼저 본다. 측정 대상을 줄이면 통과율은 실력과 무관하게 오르고,
    # 그것은 문서를 코퍼스에서 빼는 우회와 같은데 **지문은 전건으로 계산되므로** 코퍼스 지문에
    # 걸리지 않는다. 0건 측정이 만점으로 보이는 경로도 여기서 닫힌다.
    measured = evaluation.measurement.overall.documents
    assert measured == len(DOCUMENTS), (
        f"측정 대상이 코퍼스 전건이 아니다 — {measured}/{len(DOCUMENTS)}건만 셌다. "
        "지문은 전건으로 계산되므로 이 어긋남은 지문에 걸리지 않는다"
    )
    report = golden_report.latest()
    judgement = compare(load_baseline(), Fingerprint.of(DOCUMENTS), evaluation.measurement)
    if report is not None:
        report.floor = judgement

    if recording_requested():
        # 조건은 **리포트가 들고 있는 것을 그대로** 쓴다. 여기서 `run_context()`를 새로
        # 부르면 안 된다 — 관측 모델(`observed_models`)은 **변환 결과에서만** 나오는데
        # 인자 없는 호출은 `outcomes`가 `None`이라 그 목록이 항상 비고, `write_baseline`의
        # fail-closed 가드가 **유효한 실행까지 거부**한다(2026-08-12 실측: 가드를 넣으면서
        # 이 자리를 빠뜨려 기록 경로가 통째로 막혔다). 리포트의 `context`는 이미 같은
        # `outcomes`로 만들어진 값이라(`build_report` → `run_context(outcomes)`) 그대로 쓴다.
        # 새로 유도하지 않는 이유는 하나 더 있다 — **같은 실행의 조건을 두 번 유도하면 두 값이
        # 갈릴 수 있고, 갈리면 어느 쪽이 그 수치의 조건인지 말할 수 없다.**
        # `report`가 `None`인 경로만 `run_context()`로 떨어진다. 그때는 이번 실행의 수치를
        # 세운 리포트 자체가 없다는 뜻이라, 가드가 기록을 거부하는 것이 옳다(fail-closed).
        #
        # 다만 리포트를 그대로 쓰려면 **그 리포트가 이번 평가의 것이어야 한다.**
        # `golden_report.latest()`가 돌려주는 것은 "마지막으로 세워진 리포트"일 뿐,
        # 이번 평가에 결속된 리포트가 아니다 — `build_report`는 모듈 공개 함수라
        # `tests/golden/test_floor_gate_wiring.py`의 배선 테스트들도 **같은 경로로**
        # 리포트를 세우고, 이 함수를 직접 호출한다. 결속을 확인하지 않으면 **수치는 이번
        # 평가에서 오고 조건(모델 증거 `context.observed_models`)은 다른 실행에서 온**
        # 기준선이 만들어진다. 그 파일은 자기가 무엇의 하한선인지 **잘못 말한다.**
        #
        # 조건이 비어 있는 것보다 나쁘다. **빈 조건은 `write_baseline`의 fail-closed
        # 가드가 막지만(provider·observed_models·effort), 남의 조건은 전부 채워져 있어
        # 그럴듯하고 그대로 통과한다.** 그래서 여기서 결속 자체를 먼저 본다.
        #
        # **비교는 `is`다 — 값 동등성으로는 결속이 증명되지 않는다.** `Measurement`는
        # pydantic 모델이라 `!=`가 필드 **값** 비교인데, 통과율은 정수 쌍
        # (`passed`/`documents`) 셋뿐이라 **두 실행이 같은 수치를 내는 일이 흔하다** —
        # 특히 배선 테스트가 실제 코퍼스 수치를 흉내 내면 그대로 겹친다. 겹치면 값 검사는
        # 통과하고 **다른 실행의 모델 증거가 그대로 기준선에 실린다.** 값 검사가 보는 것은
        # 결속이 아니라 *수치가 우연히 같은지*다(2026-08-12 실측: 수치는 이번 평가와 같은
        # 0/56이고 조건만 `stale-model-from-another-run`인 리포트가 값 검사를 그대로
        # 통과해 임시 경로에 기록됐다 — 남의 모델 증거를 실은 채로).
        #
        # 그래서 묻는 것은 "값이 같은가"가 아니라 **"그 객체가 이번 평가에서 왔는가"**다.
        # `is`가 성립하는 근거는 실측이다 — pydantic v2는 `revalidate_instances`가
        # 기본값(`'never'`)이면 중첩 모델 필드에 **넘긴 인스턴스를 그대로 보관한다.**
        # `build_report`가 `measurement=result.measurement`로 넘긴 바로 그 객체가
        # `report.measurement`로 되돌아오므로, `is`는 "이 리포트는 이 평가로 세워졌다"를
        # 증명한다. 그 전제 자체는 `tests/golden/test_floor_gate_wiring.py`가 따로 고정한다
        # — 전제가 깨지면 유효한 실행까지 거부되는데(fail-closed라 안전한 방향이지만
        # 원인이 안 보인다) 그때 원인을 말해 줄 자리가 필요해서다.
        #
        # 실행 토큰(uuid4 등)을 새로 싣지 않은 이유: 필드를 늘리지 않아도 동일성이
        # 성립하고, 토큰을 만들어 두면 **그 토큰을 실수로 재사용하는 경로가 또 생긴다.**
        if report is not None and report.measurement is not evaluation.measurement:
            raise AssertionError(
                "기준선을 쓰지 않는다 — 기록할 조건이 **이번 평가에 결속되지 않았다.** "
                "리포트의 측정치가 이번 평가의 측정치와 **같은 객체가 아니다**(다른 "
                "실행의 리포트가 잡혔다). 수치가 우연히 같아도 결속이 아니다 — 통과율은 "
                "정수 쌍이라 다른 실행에서도 같은 값이 나온다. 수치와 조건이 서로 다른 "
                "실행에서 오면 그 기준선은 자기가 무엇의 하한선인지 잘못 말한다."
            )
        body = baseline_body(
            Fingerprint.of(DOCUMENTS),
            evaluation.measurement,
            report.context if report is not None else run_context(),
            report.judge if report is not None else None,
        )
        changes = baseline_changes(body)
        if report is not None:
            report.baseline_changes = changes
        if changes:
            path = write_baseline(body)
            # 지적 건수가 아니라 **쓸 내용과 이전 내용의 차이**로 판정한다. 지적 0건인
            # 새 파일 생성이 조용히 통과하던 것이 원장에서 겪은 결함이다.
            #
            # `pytest.fail`이 아니라 `AssertionError`를 쓴다 — `pytest.fail`이 던지는
            # `Failed`는 `BaseException` 계열이라 배선 테스트가 `pytest.raises(Exception)`
            # 으로 잡지 못한다. 게이트가 실제로 막는지 확인할 수 없는 예외는 쓰지 않는다.
            # 사람이 보는 첫 화면에 **방향**이 있어야 한다. `compare`는 하락·차단을 이미
            # 계산해 두었는데 예전 메시지는 "`measurement`가 바뀐다"까지만 적어, 하락 기록과
            # 개선 기록이 pytest 출력에서 같은 모양이었다(2026-08-12 교차 리뷰 T-3).
            # 기준선을 낮추는 기록은 diff의 숫자를 직접 읽지 않으면 구분되지 않았다.
            raise AssertionError(
                f"기록 실행 — 판정이 아니다. 기준선을 갱신했다: {path}\n"
                + (
                    "⚠ **이 기록은 하한선을 낮춘다** — 아래 판정이 '하락'이다. 그대로 커밋하면 "
                    "낮아진 수치가 다음 실행부터 합격선이 된다\n"
                    if judgement.blocking
                    else ""
                )
                + judgement.summary()
                + "\n"
                + "\n".join(changes)
                + "\n이 실행으로는 게이트를 닫지 못한다. diff를 커밋해 리뷰에 올린 뒤 "
                "`GOLDEN_RECORD_BASELINE` **없이** 다시 돌린 결과로 판정한다"
            )
        # 바뀐 것이 없으면 아래 정상 판정으로 떨어진다 — 관측이 커밋된 기준선과 정확히
        # 같다는 뜻이라 플래그 없이 돌린 실행과 판정이 정의상 같다.

    assert not judgement.blocking, (
        judgement.summary() + "\n\n실패 문서:\n" + format_report(evaluation.evaluations)
    )


def summarize_judge(scores: dict[str, JudgeScore]) -> JudgeObservation:
    """judge 관측치 집계. 채점이 0건이어도 안전하게 0으로 떨어진다."""
    count = len(scores)
    return JudgeObservation(
        scored=count,
        documents=len(DOCUMENTS),
        fidelity_mean=(sum(score.fidelity for score in scores.values()) / count) if count else 0.0,
        readability_mean=(
            (sum(score.readability for score in scores.values()) / count) if count else 0.0
        ),
        low_fidelity_ids=find_low_fidelity(scores, FIDELITY_FLOOR),
    )
