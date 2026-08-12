"""통과율의 **정의** — 분자와 분모를 만드는 유일한 경로.

이 모듈이 따로 있는 이유는 지문 때문이다. 상대 하한선("직전 기록보다 낮아지지 않는다")은
**같은 정의로 잰 수치끼리만** 성립한다. 코퍼스 지문이 "무엇을 재는가"를, 판정 기준 지문이
"어떤 자로 재는가"를 못박는 동안 세 번째 다리인 **"통과란 무엇인가"**는 어디에도 묶여 있지
않았다(2026-08-12 교차 리뷰 Q-2). 실측된 상태였다 — 팩트 조건·스타일 조건·플레이스홀더
조건·변환 실패 처리를 **각각** 제거해도 스위트 916건이 전부 통과했고, 팩트 조건만 빼면 세
집단이 모두 올라(0.643→0.804) `IMPROVED`(비차단)로 판정된다. 재기록까지 마치면 **바뀐 정의
위에 새 하한선이 굳는다.**

그래서 정의를 한 모듈에 모았다. `tests/golden/baseline.py`의 `criteria_payload`가 **이 모듈
전체**를 판정 기준 지문에 넣는다 — 손으로 적은 심볼 목록이 아니라 모듈 단위라, 여기에 조건이
새로 생기거나 기존 조건이 사라지면 지문이 저절로 움직이고 이전 기준선과의 비교는 "비교 불가"로
떨어진다. 뒤집으면 규약이 하나 생긴다: **통과·실패를 가르는 코드는 반드시 여기 둔다.** 이
모듈 밖에서 판정하면 그 판정은 지문에 잡히지 않는다.

각 조건이 실제로 통과율을 움직이는지는 `tests/golden/test_pass_rate_definition.py`가 하나씩
고정한다. 이전에는 `evaluate_rules`의 주석 한 줄이 유일한 강제 수단이었다.
"""

from collections import Counter
from collections.abc import Mapping, Sequence

from pydantic import BaseModel

from app.easyread.goldenset import FactLoss, GoldenDocument, find_fact_losses
from app.easyread.style_rules import check_style
from app.services.conversion import ConversionOutcome
from tests.golden.baseline import GroupMeasurement, Measurement


class DocumentEvaluation(BaseModel):
    """문서 한 건의 평가 결과. failures에는 사유 코드만 담는다."""

    document_id: str
    synthetic: bool
    failures: list[str]

    @property
    def passed(self) -> bool:
        """**통과의 정의** — 사유가 하나도 없을 때만 통과다.

        여기에 예외("이 사유는 봐준다")를 더하는 것이 통과율을 올리는 가장 싼 길이라,
        이 한 줄이 지문 안에 있어야 한다.
        """
        return not self.failures


class RuleEvaluation(BaseModel):
    """규칙 기반 평가 전체 — LLM 호출 없이 변환 결과만 보고 만든다.

    변환 산출물만 있으면 이 객체는 결정적으로 만들어진다. 그래서 배선 테스트가
    FakeProvider로 같은 경로를 돌릴 수 있고, 차단 게이트가 실제로 걸리는지를 기본
    스위트(LLM 없음)에서 고정할 수 있다.

    `measurement` 와 `observed_models` 는 **같은 outcomes 로 한 pass 에서** 만들어져 한
    객체에 함께 실린다. 수치와 그 수치의 조건(어떤 모델이 냈는가)이 한 객체에서 나오므로,
    기록 경로가 조건을 다른 곳(리포트)에서 맞출 필요가 없다 — 결속이 구조로 성립한다.
    이것이 "측정치를 만드는 것이 증거도 함께 만들면 결속을 확인할 일이 없어진다"의 자리다.
    """

    evaluations: list[DocumentEvaluation]
    measurement: Measurement
    failure_reasons: dict[str, int]
    conversion_failures: list[str]
    fact_losses: list[FactLoss]
    #: 이 측정치를 만든 변환들이 **실제로 보고한** 모델(`ConversionOutcome.model` ←
    #: `LLMResponse.model`). `measurement` 와 같은 pass·같은 outcomes 에서 모은다 —
    #: 그래서 이 목록은 언제나 이 측정치의 조건이지, 다른 실행의 것일 수 없다.
    observed_models: list[str]


def evaluate_rules(document: GoldenDocument, outcome: ConversionOutcome) -> list[str]:
    """규칙 기반 3개 조건을 확인하고 위반 사유 코드를 돌려준다.

    팩트 유실이 여기 **그대로 남아 있는 것은 의도**다. 절대 기준의 필수 정보 보존 게이트가
    따로 생겼지만, 이 목록에서 빼면 통과율의 정의가 바뀌어 과거 실측치(2026-08-08 AND
    통과율 0.643)와 비교가 끊긴다 — 상대 하한선은 정의가 같아야 성립한다. 두 축이 같은
    사실을 겹쳐 보는 대가로 통과율의 의미를 보존한다.
    """
    failures: list[str] = []
    style = check_style(outcome.easy_text)
    if not style.passed:
        # issue.reason은 style_rules(SSOT)가 만든 상수 문자열이라 본문이 아니다.
        # 사유별 건수까지 남겨야 프롬프트를 어느 방향으로 고칠지 판단할 수 있다.
        counts = Counter(issue.reason for issue in style.issues)
        detail = ", ".join(f"{reason} {count}" for reason, count in counts.most_common())
        failures.append(f"스타일위반 {len(style.issues)}건({detail})")
    missing_facts = document.missing_facts(outcome.easy_text)
    if missing_facts:
        failures.append(f"팩트유실 {len(missing_facts)}/{len(document.required_facts)}건")
    if outcome.missing_placeholders:
        failures.append(f"플레이스홀더유실 {len(outcome.missing_placeholders)}건")
    return failures


def evaluate_all(
    outcomes: Mapping[str, ConversionOutcome | None], documents: Sequence[GoldenDocument]
) -> RuleEvaluation:
    """변환 결과 전체를 규칙으로 평가한다 — **LLM 호출 없음, 결정적**.

    문서 목록을 인자로 받는다. 모듈 전역을 읽으면 "무엇을 셌는가"가 호출자에게 보이지 않아,
    지문은 코퍼스 전량으로 계산되는데 평가만 축소된 목록으로 도는 어긋남을 만들 수 있다.

    `observed_models` 를 **이 loop 에서 함께** 모은다 — measurement 를 만든 바로 그
    outcomes(성공한 변환)에서 나온 모델이라, 수치와 조건이 같은 pass 에 묶인다. 따로
    유도하지 않으므로 "측정치는 이 평가·조건은 다른 실행"이 될 통로가 없다.
    """
    evaluations: list[DocumentEvaluation] = []
    reasons: Counter[str] = Counter()
    conversion_failures: list[str] = []
    converted: dict[str, tuple[GoldenDocument, str]] = {}
    observed: set[str] = set()
    for document in documents:
        outcome = outcomes.get(document.id)
        if outcome is None:
            # 변환 실패는 **통과가 아니다.** 판정하지 못한 것을 통과로 세면 provider가
            # 전건 실패한 실행이 만점으로 보인다.
            conversion_failures.append(document.id)
            failures = ["변환실패(LLMProviderError)"]
        else:
            converted[document.id] = (document, outcome.easy_text)
            failures = evaluate_rules(document, outcome)
            reasons.update(issue.reason for issue in check_style(outcome.easy_text).issues)
            # 측정에 실제로 들어간 변환이 보고한 모델만 담는다 — 이 수치의 조건이다.
            observed.add(outcome.model)
        evaluations.append(
            DocumentEvaluation(
                document_id=document.id, synthetic=document.synthetic, failures=failures
            )
        )
    return RuleEvaluation(
        evaluations=evaluations,
        measurement=measure(evaluations),
        failure_reasons=dict(reasons),
        conversion_failures=conversion_failures,
        fact_losses=find_fact_losses(converted),
        observed_models=sorted(observed),
    )


def measure(evaluations: list[DocumentEvaluation]) -> Measurement:
    """집단별 통과 측정치. **합성과 실수집을 나눈다** — 합친 평균은 어느 집단도 대표하지 않는다.

    **분모는 평가 전건이다.** 실패한 문서를 분모에서 빼면 통과율은 실력과 무관하게 오르고,
    그것은 문서를 코퍼스에서 빼는 우회와 구조가 같다(코퍼스 지문이 막는 바로 그 경로다).
    """

    def group(subset: list[DocumentEvaluation]) -> GroupMeasurement:
        return GroupMeasurement(documents=len(subset), passed=sum(item.passed for item in subset))

    return Measurement(
        overall=group(evaluations),
        synthetic=group([item for item in evaluations if item.synthetic]),
        collected=group([item for item in evaluations if not item.synthetic]),
    )
