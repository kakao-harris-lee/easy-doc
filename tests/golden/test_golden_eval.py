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
from app.llm.factory import applied_effort, create_provider
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
    """전 문서를 한 번만 변환한다(LLM 호출 절감) — **`evaluate_all` 의 입력이다.**

    테스트가 이 fixture 를 **직접 받지 않는다.** 세 축이 공유하는 것은 맞지만, 공유는
    `evaluation` 을 거쳐서 한다(`RuleEvaluation.outcomes`). 축이 여기서 따로 받으면 그
    축만 다른 실행의 산출물을 볼 수 있고, judge 가 정확히 그 상태였다(10번째 지적).
    """
    return await convert_all(provider)


@pytest.fixture(scope="module")
def evaluation(outcomes: dict[str, ConversionOutcome | None]) -> RuleEvaluation:
    """규칙 평가를 만들고 **리포트를 먼저 세운다.**

    리포트를 여기서 세우는 이유: 아래 테스트가 하나라도 실패하거나 skip되어도 이번 실행의
    수치가 남아야 한다. 통과 실행에서 수치가 사라지던 것이 상대 하한선을 세우지 못한
    원인이었다(`tests/golden/report.py`).

    **모듈 전역 `DOCUMENTS` 를 읽는 것은 여기가 맞다.** 이 자리가 "무엇을 평가하는가"를
    *고르는* 곳이고, 고른 집합은 `evaluate_all` 이 결과에 실어 하류로 넘긴다. 위험한 것은
    전역을 읽는 것 자체가 아니라 **평가 결과를 다루는 자리가 전역을 다시 읽는 것**이다 —
    그러면 고른 집합과 다시 읽은 집합이 갈릴 수 있다(11번째 지적).
    """
    result = evaluate_all(outcomes, DOCUMENTS)
    build_report(result)
    return result


def build_report(result: RuleEvaluation) -> golden_report.GoldenRunReport:
    """이번 실행의 리포트를 세워 붙든다. 배선 테스트도 같은 경로를 쓴다.

    조건(`RunContext`)을 `result.observed_models` 에서 만든다 — 측정치와 조건을 한
    객체(`result`)에서 함께 꺼내므로, 리포트 안에서 수치와 조건이 서로 다른 실행에서
    오는 일이 없다. 예전에는 `outcomes` 를 따로 받아 조건을 다시 유도했고, 그 자유 변수가
    "측정치는 이 평가·조건은 다른 실행"을 만드는 통로였다: `build_report(result, 다른_outcomes)`
    는 measurement 동일성을 지키면서 남의 observed 를 context 에 실었다(6번째 지적).
    이제 그 인자가 없어 그 호출 자체가 표현 불가다.

    지문도 `result` 에서 만든다(`result.documents`). 예전에는 모듈 전역 `DOCUMENTS` 로
    계산해, 축소된 집합으로 만든 평가를 태우면 **지문은 56건인데 통과율의 분모는 20건**인
    리포트가 나왔다 — 사람이 읽는 줄에 "코퍼스 56건"이 찍히고 그 아래 분모가 20이었다.
    지문은 "이 수치가 무엇을 재서 나온 것인가"인데 그 '무엇'을 수치와 다른 데서 구하고
    있었다(11번째 지적). 이제 분모와 지문이 한 객체에서 나와 어긋날 표현이 없다.

    리포트를 **`result` 에 결속해** 등록한다(`golden_report.record` 의 두 번째 인자). 아래
    두 테스트는 이 결속을 통해 `for_evaluation(evaluation)` 으로 자기 리포트만 받는다 —
    "마지막으로 세워진 리포트"를 집어 오는 통로가 없어야 남의 리포트가 잡히지 않는다.

    조건을 **한 번만 만들어 지문과 리포트 양쪽에 넘긴다**(2026-08-13, producer 축). 지문이
    provider·관측 모델·effort 를 담게 되면서 `run_context()` 를 두 번 부르면 한 리포트 안에서
    지문의 producer 와 `context` 가 갈릴 표현이 생긴다 — 같은 객체를 쓰면 그 표현이 없다.
    """
    context = run_context(result.observed_models)
    return golden_report.record(
        golden_report.GoldenRunReport(
            fingerprint=Fingerprint.of(result.documents, context),
            context=context,
            targets=TARGETS,
            measurement=result.measurement,
            failure_reasons=result.failure_reasons,
            conversion_failures=result.conversion_failures,
            fact_losses=result.fact_losses,
        ),
        result,
    )


def run_context(observed_models: list[str]) -> RunContext:
    """비교의 조건 — 판정에 쓰지 않고 기록만 한다.

    `observed_models` 는 **호출자가 측정치와 함께 들고 있던 값**을 그대로 받는다
    (`RuleEvaluation.observed_models`). 여기서 outcomes 로부터 다시 유도하지 않는다 —
    조건을 두 번 유도하면 두 값이 갈릴 수 있고, 갈리면 어느 쪽이 그 수치의 조건인지 말할
    수 없다. `LLMResponse.model`(관측)과 `settings.llm_model`(설정값)을 둘 다 싣는 이유는
    그대로다: 설정값은 별칭 해석·폴백이 있으면 실제와 갈리므로 관측값이 증거다.

    나머지(provider·judge_provider·model·effort)는 **환경·설정에서** 오므로 outcomes 와
    무관하다. 그래서 이 값들은 여기서 매번 새로 읽어도 결속과 상관없다 — 결속이 필요한 것은
    관측 모델뿐이고, 그것은 인자로 받아 이미 measurement 와 한 객체에 묶여 있다.

    **effort 는 설정값이 아니라 `applied_effort` 를 쓴다**(2026-08-13, 교차 종합 X-5).
    `settings.llm_effort` 를 그대로 실으면 이 실행에 **적용되지도 않은 값**이 지문의
    producer 축에 들어간다 — `create_provider` 는 effort 를 Anthropic 에만 넘기므로, OpenAI
    레인에서는 `LLM_EFFORT` 를 바꿔도 모델에 닿지 않는데 지문만 갈려 비교 불가가 된다
    (`.env` 에 `LLM_EFFORT=low` 가 실제로 들어 있다). 지문을 과민하게 만드는 것은 하한선이
    영영 축적되지 않는 반대 방향 고장이다.

    **값을 지문에서 빼지는 않았다.** Anthropic 레인에서 effort 는 진짜 교란 변수이고 이
    저장소가 9%p 하락의 후보로 스스로 지목한 값이다(`04_goldenset-first-run.md`). 없앤 것은
    값이 아니라 *적용되지 않은 값이 지문을 흔드는 경로*다. 판단의 정본은 `app/llm/factory.py`
    한 곳이라 여기와 provider 생성 쪽이 갈릴 수 없다.
    """
    settings = Settings()
    provider = os.environ.get("GOLDEN_PROVIDER", DEFAULT_PROVIDER)
    return RunContext(
        provider=provider,
        judge_provider=os.environ.get("GOLDEN_JUDGE_PROVIDER", DEFAULT_JUDGE_PROVIDER),
        model=settings.llm_model,
        observed_models=observed_models,
        effort=applied_effort(provider, settings),
    )


async def convert_all(provider: LLMProvider) -> dict[str, ConversionOutcome | None]:
    """전 문서를 변환한다. 변환 실패는 None으로 기록하고 계속 진행한다.

    여기도 전역을 읽지만 **평가 결과를 다루는 자리가 아니다** — 산출물을 만드는 쪽이다.
    평가와 갈려도 조용하지 않다: 여기서 빠진 문서는 `evaluate_all` 에서 `outcomes.get`
    이 `None` 이 되어 **변환 실패**로 세어지고, 변환 실패는 차단축이 잡는다.
    """
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
    evaluation: RuleEvaluation,
    judge_provider: LLMProvider,
) -> None:
    """**차단하지 않는다.** 점수를 재서 리포트와 경고로 남긴다.

    차단하지 않는 이유는 채점자를 고정할 수단이 없어서다 — 모델이 바뀌면 우리 코드를 한 줄도
    고치지 않았는데 CI가 빨개진다. 그렇다고 그냥 통과시키지도 않는다. 목표선을 밑돌면
    `UserWarning`으로 올리고 리포트에 수치를 싣는다.

    이 축이 막던 정보 누락은 위의 필수 정보 보존 게이트가 LLM 없이 절대 기준으로 받는다.

    **`evaluation` 하나만 받는다 — 순회 대상·채점 대상·붙이는 자리가 여기서 함께 나온다.**
    도는 문서는 `evaluation.documents`, 채점은 `evaluation.outcomes`, 관측을 실을 리포트는
    `for_evaluation(evaluation)`.

    이 자리에서 그릇과 내용물을 세 번 헷갈렸다(`RuleEvaluation` docstring 이 정본이다).
    한 번은 기준선에서 judge 를 뺄 때 — 리포트는 결속돼 있었지만 **나중에 대입되는 필드**의
    출처는 그 결속 밖이었다. 두 번째는 관측을 **붙이는 자리**를 이번 평가로 묶고서 채점할
    `outcomes` 는 **별도 fixture 로** 받은 것이다. 그릇만 묶고 내용물을 안 묶은 것이라,
    A 의 outcomes 를 채점해 B 의 평가 리포트에 싣는 상태가 그대로 표현 가능했다(10번째 지적).
    세 번째가 **여기서 문서를 도는 이 loop 였다** — 채점 대상은 이번 평가인데 `for` 가
    모듈 전역 `DOCUMENTS` 를 돌았다. 20건만 평가한 실행에서도 judge 는 56건을 돌아, 평가가
    본 적 없는 문서를 채점하고 커버리지 분모까지 56으로 적었다(11번째 지적).

    네 번째가 없는 이유는 세 끝이 **같은 객체에서** 나오기 때문이다. 다른 대상을 채점하려면
    다른 `evaluation` 을 넘겨야 하는데, 그러면 도는 문서도 붙는 리포트도 함께 옮겨 간다.
    `outcomes` 도 문서 목록도 **따로 받는 인자가 없다**는 것이 이 성질의 전부다 — 남겨 두면
    통로가 그대로 남는다.
    """
    scores: dict[str, JudgeScore] = {}
    notes: list[str] = []
    for document in evaluation.documents:
        outcome = evaluation.outcomes.get(document.id)
        if outcome is None:
            notes.append(f"{document.id}: 변환실패")
            continue
        try:
            scores[document.id] = await judge_conversion(
                judge_provider, source=document.source_text, converted=outcome.easy_text
            )
        except LLMProviderError:
            notes.append(f"{document.id}: 채점실패")

    observation = summarize_judge(evaluation, scores)
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

    report = golden_report.for_evaluation(evaluation)
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

    **judge 테스트와의 순서 제약은 없다**(2026-08-13). 예전에는 기준선이 judge 관측치를
    함께 담아 judge 가 뒤에 오면 기록되는 값이 비었지만, 이제 기준선에 judge 가 실리지
    않는다(`Baseline` docstring). 판정도 기록도 judge 와 무관하게 돈다 — 키가 없어 judge 가
    건너뛰어도 이 게이트는 그대로다.
    """
    # 분모가 코퍼스 전건인지 먼저 본다. 측정 대상을 줄이면 통과율은 실력과 무관하게 오른다.
    #
    # **여기가 이 함수에서 모듈 전역 `DOCUMENTS` 를 읽는 유일한 자리이고, 그래야 하는
    # 자리다.** 이 검사의 내용 자체가 "이번 평가가 든 분모(`evaluation.documents`)와 코퍼스
    # 전량이 같은가"라는 **대조**라, 양쪽이 다 필요하다. 전역에서 값을 가져와 이번 실행의
    # 산출물(지문·수치·리포트)에 **싣지 않는다** — 대조만 하고 버린다. 섞이려면 전역의 값이
    # 결과물에 실려야 하는데 그 통로가 여기엔 없다.
    #
    # 아래 지문과 역할이 갈린다. 이 검사는 **개수** 축(축소된 집합)을, 지문은 **집합** 축
    # (같은 개수로 다른 문서를 채우는 우회)을 받는다. 예전에는 지문이 전역으로 계산돼
    # 집합 축이 통째로 비어 있었다 — 통과하는 20건을 56칸에 채우면 개수 검사는 통과하고
    # 지문은 진짜 코퍼스를 가리켜, `56/56`(1.000)이 전량 코퍼스의 지문을 달고 기록됐다
    # (전량 평가는 20/56, 11번째 지적). 지문이 평가가 든 집합에서 나오면서 그쪽이 닫혔다.
    measured = evaluation.measurement.overall.documents
    assert measured == len(DOCUMENTS), (
        f"측정 대상이 코퍼스 전건이 아니다 — {measured}/{len(DOCUMENTS)}건만 셌다. "
        "게이트 실행은 코퍼스 전량을 재야 한다"
    )
    report = golden_report.for_evaluation(evaluation)
    # 지문은 **이번 평가가 든 문서 집합**에서 만든다. 전역으로 계산하면 수치와 지문이 서로
    # 다른 집합에서 와, 지문이 막으려던 분모 우회가 지문 자신에게 열린다(11번째 지적).
    #
    # 조건도 **여기서 한 번만** 만든다(2026-08-13, producer 축). 지문이 provider·관측 모델·
    # effort 를 담게 되면서, 판정에 쓰는 지문과 아래 기록에 쓰는 지문이 서로 다른 `run_context()`
    # 호출에서 나오면 "판정은 통과인데 기록되는 producer 는 다른 값"이 표현 가능해진다.
    # 관측 모델은 `evaluation` 에서 오고 나머지는 환경·설정에서 오는데, 그 환경을 두 번 읽지
    # 않는 것으로 그 표현을 없앤다.
    context = run_context(evaluation.observed_models)
    fingerprint = Fingerprint.of(evaluation.documents, context)
    judgement = compare(load_baseline(), fingerprint, evaluation.measurement)
    if report is not None:
        report.floor = judgement

    if recording_requested():
        # 기록할 측정치와 그 조건(관측 모델)을 **둘 다 evaluation 에서** 꺼낸다.
        # `evaluate_all` 이 같은 outcomes 로 measurement 와 observed_models 를 한 pass 에서
        # 만들어 `RuleEvaluation` 한 객체에 실었으므로, 이 둘은 서로 다른 실행에서 올 수 없다.
        # 그래서 이 둘에 대해서는 `report.context` 를 신뢰하지 않고 결속을 확인하지도 않는다 —
        # 확인할 상태 자체가 만들어지지 않는다.
        #
        # 이 자리에서 검사를 다섯 번 얹었고, 그때마다 검사가 묶은 것(measurement)과 기록되는
        # 것(`report.context`)이 어긋나 새 구멍이 났다. 6번째 지적이 정확히 그 어긋남이었다:
        # `build_report(result, 다른_outcomes)` 는 measurement 동일성(is)을 지키면서 남의
        # observed 를 context 에 실었다. 이제 `build_report` 가 outcomes 를 받지 않고
        # `run_context` 가 관측 모델을 인자로만 받으므로, 그 호출도 이 기록 경로도 남의 조건을
        # 실을 통로가 없다. **`report.context` 로 갈아타지 않고 `evaluation` 이 든 값을 쓰는
        # 이유가 이것이다** — 리포트를 거치면 다시 "그 리포트가 이번 평가의 것인가"를 확인해야
        # 하고(검사 추가), `evaluation` 을 직접 보면 그 확인이 필요 없다(구조).
        #
        # **기준선 본문은 이제 `evaluation` 한 객체에서만 나온다.** 2026-08-13에 judge 관측을
        # 기준선에서 뺐다. judge 는 비차단축이라 애초에 **하한선의 구성요소가 아니었다** —
        # 채점 모델을 고정할 수단이 없어 우리 코드를 고치지 않아도 값이 움직인다. 있을 이유가
        # 없는 값을 파일에 실은 탓에 "이 judge 수치가 어느 실행의 것인가"라는 출처 문제가
        # 생겼고, 이 자리에 검사를 여섯 번 얹었다. 그릇(리포트)은 구조로 묶을 수 있어도
        # **나중에 대입되는 내용물(`report.judge`)의 출처는 구조로 묶이지 않는다** — 리포트는
        # 세워진 뒤에 judge 테스트가 관측을 대입하기 때문이다. 그래서 일곱 번째 검사를 얹는
        # 대신 값을 뺐고, **리포트에서 읽는 값이 없어지자 출처를 확인할 대상도 함께 사라져**
        # `report.measurement is evaluation.measurement` 검사를 지웠다. judge 관측은 실행
        # 리포트에 그대로 남는다 — 정보를 잃은 것이 아니라 하한선에서 내린 것이다.
        #
        # **직전 커밋의 방어("ⓑ 는 쓰기라서 안전하다")는 틀렸다** — 9번째 지적이 그것을 깼다.
        # 그 방어는 보호 대상을 커밋되는 `baseline.json` 하나로 잡고 "리포트에 쓰는 것은
        # 표시일 뿐 파일 무결성과 무관하다"고 적었다. 대상을 잘못 잡았다 — **사람이 읽는 것은
        # 그 리포트다.** `latest()` 가 남의 리포트일 때 이 자리는 이번 실행의 `floor` 판정과
        # `baseline_changes` 를 남의 실행 리포트에 실었고, 그 리포트를 읽는 사람은 다른 실행의
        # 수치 위에 이번 판정이 얹힌 것을 한 실행의 결과로 읽는다. 기준선 파일이 안전해도
        # 리포트가 오염된다 — 방향만 반대일 뿐 두 실행이 섞이는 것은 같다.
        #
        # 그래서 일곱 번째 검사를 얹지 않고 **통로를 없앴다.** 리포트는
        # `golden_report.for_evaluation(evaluation)` 으로만 찾는다 — `record` 가 리포트를 만든
        # 평가에 결속해 등록하고 조회는 그 평가로 한다(키는 값이 아니라 **객체 동일성**이다.
        # 값으로 잡으면 수치가 같은 두 실행이 겹친다). 남의 리포트가 최신이어도 이 조회에
        # 잡히지 않으므로 **"남의 리포트에 이번 판정을 쓴 상태"가 만들어질 수 없다.** 검사로
        # 그 상태를 잡아내는 것과 그 상태를 만들 수 없게 하는 것은 다르다 — 이 자리에 검사를
        # 다섯 번 얹었고 그때마다 검사가 묶은 것과 실제로 쓰이는 것이 어긋나 새 구멍이 났다.
        #
        # 남는 `report is None` 은 그대로 fail-closed 가드다. 다만 이제 "리포트가 있는가"가
        # 아니라 **"이번 평가의 리포트가 있는가"**를 뜻한다 — 조회가 결속을 요구하므로 None 은
        # 이번 실행이 리포트로 조립되지 않았다는 뜻이고, 그때는 커밋된 하한선을 갱신하지 않는다.
        # 기록되는 본문은 여전히 `evaluation` 에서만 온다.
        if report is None:
            raise AssertionError(
                "기준선을 쓰지 않는다 — 이번 실행의 리포트가 없다(fail-closed). 리포트가 "
                "세워진 정상 실행에서만 커밋된 하한선을 갱신한다."
            )
        # **본문 세 조각이 전부 `evaluation` 에서 나온다** — 지문(무엇을 쟀는가)·측정치
        # (무엇이 나왔는가)·조건(무엇이 만들었는가). 지문만 전역에서 오던 것이 11번째
        # 지적이었다. 그때는 기록된 파일 하나 안에서 `corpus_sha256` 는 진짜 56건 코퍼스를
        # 가리키고 `measurement` 는 다른 집합에서 온 수치였다 — 그리고 다음 실행은 그
        # 지문이 맞아떨어지므로 **비교 가능**으로 읽어 그 수치를 하한선으로 삼는다.
        #
        # 지문과 조건은 **판정에 쓴 그 값 그대로**다(위에서 한 번 만들었다). 여기서 다시
        # 만들면 "판정한 지문"과 "기록한 지문"이 갈릴 수 있고, 갈리면 이 실행이 무엇과
        # 비교해 통과했는지와 무엇을 하한선으로 남겼는지가 서로 다른 이야기가 된다.
        body = baseline_body(fingerprint, evaluation.measurement, context)
        changes = baseline_changes(body)
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


def summarize_judge(evaluation: RuleEvaluation, scores: dict[str, JudgeScore]) -> JudgeObservation:
    """judge 관측치 집계. 채점이 0건이어도 안전하게 0으로 떨어진다.

    커버리지의 **분모를 `evaluation` 에서 받는다.** 전역을 읽으면 "56건 중 20건 채점"처럼
    평가가 본 적 없는 문서가 분모에 들어가고, 커버리지 경고("대부분 채점에 실패했다면 아래
    평균은 남은 소수만의 값이다")가 엉뚱한 실행을 가리킨다. 분자(`scores`)는 바로 위에서
    `evaluation.outcomes` 를 채점해 만든 값이라, 분모도 같은 객체에서 와야 한 실행의 비율이
    된다(11번째 지적).
    """
    count = len(scores)
    return JudgeObservation(
        scored=count,
        documents=len(evaluation.documents),
        fidelity_mean=(sum(score.fidelity for score in scores.values()) / count) if count else 0.0,
        readability_mean=(
            (sum(score.readability for score in scores.values()) / count) if count else 0.0
        ),
        low_fidelity_ids=find_low_fidelity(scores, FIDELITY_FLOOR),
    )
