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

    **이 객체가 근원이다.** `documents`(무엇을 쟀는가 = 분모)·`outcomes`(무엇을 보고
    만들었는가)·`measurement`(무엇이 나왔는가)·`observed_models`(어떤 모델이 냈는가)가
    **같은 pass 에서** 한 객체에 함께 실린다. 네 값이 한 객체에서만 나오므로 뒤에 오는 어떤
    소비자도 "측정치는 이 실행·대상은 다른 실행"을 조립할 수 없다 — 결속이 검사가 아니라
    구조로 성립한다.

    ## 그릇과 내용물을 세 번 헷갈렸다

    이 사슬에서 같은 착오를 **세 번** 했다. **그릇(값이 담기는 자리)을 묶고 내용물(그 값이
    무엇을 보고 나왔는가)을 안 묶은 것**이다.

    1. **judge 를 기준선에서 뺄 때**(7269eed) — 기준선에 실리는 `report.judge` 의 출처를
       구조로 묶을 수 없다고 보고 값을 뺐다. 리포트라는 그릇은 결속돼 있었지만 **나중에
       대입되는 필드의 출처**는 그 결속이 말해 주지 않았기 때문이다. 진단은 맞았는데
       처방이 그릇 쪽만 봤다 — 대입되는 값이 무엇을 보고 나왔는지는 그대로 자유 변수였다.
    2. **리포트를 결속할 때**(98cbe17·353f0c8) — judge 관측을 **붙이는 자리**를
       `for_evaluation(evaluation)` 으로 묶었다. 그런데 judge 테스트는 채점할
       `outcomes` 를 **별도 fixture 로** 받았다. 붙이는 자리(그릇)는 이번 평가인데
       채점 대상(내용물)은 아무 outcomes 여도 됐다 — A 의 outcomes 를 채점해 B 의 평가
       리포트에 싣는 상태가 구조적으로 표현 가능했고, 실제로 만들어 확인했다(10번째 지적).
    3. **`outcomes` 를 결속할 때**(5348afa, 이번에 고친다) — 채점 대상은 묶었는데
       **분모**를 안 묶었다. `evaluate_all(outcomes, documents)` 이 `documents` 를 받아
       쓰고 **버렸고**, 하류(지문·judge 순회·커버리지 분모)는 모듈 전역 `DOCUMENTS` 를
       다시 읽었다. 그래서 "지문은 전역 56건, 수치는 다른 집합"이 한 파일에 실릴 수 있었다
       — **지문이 막으려던 분모 우회가 지문 자신에게 열려 있었다**(11번째 지적). 실측으로
       확인했다: 통과하는 20건만 골라 56칸을 채운 평가가 `56/56`(1.000)을 내고, 그 수치가
       **진짜 56건 코퍼스의 corpus_sha256** 을 단 기준선으로 기록됐다(전량 평가는 20/56).

    세 번 모두 모양이 같다. 값이 담기는 **자리**는 구조로 묶으면서, 그 값이 **무엇을 보고
    나왔는지**는 소비자가 다른 데서 구하도록 남겨 뒀다. 남은 자유 변수 위에 검사를 얹는
    것으로는 닫히지 않았다 — 검사가 묶은 것과 실제로 쓰이는 것이 매번 어긋났다.

    ## 왜 네 번째는 없는가

    **평가 결과를 다루는 모든 자리가 한 객체에서 읽는다.** 무엇을 쟀는지(`documents`)·
    무엇을 봤는지(`outcomes`)·무엇이 나왔는지(`measurement`)·무엇이 만들었는지
    (`observed_models`)가 전부 이 객체에 있어, 소비자가 값을 **다른 데서 구할 이유가 없다**.
    지문은 `Fingerprint.of(evaluation.documents)`, judge 순회는 `evaluation.documents`,
    채점 대상은 `evaluation.outcomes`, 붙이는 자리는 `for_evaluation(evaluation)` 이다.
    모든 끝이 같은 `evaluation` 한 값에서 유도되므로 **둘을 어긋나게 하는 표현이 없다** —
    어긋나려면 서로 다른 두 `evaluation` 이 필요한데, 그러면 지문도 수치도 리포트도 함께
    옮겨 가 두 실행이 섞이지 않는다.

    남은 자유 변수가 생기는 자리는 하나뿐이다: **새 소비자가 전역을 읽는 것.** 그래서
    규약은 "평가 결과를 다루면 전역을 읽지 않는다"이고, 전역을 읽는 자리가 남아 있다면
    그것이 평가와 무관하다는 근거가 그 자리에 적혀 있어야 한다
    (`tests/golden/test_golden_eval.py` 의 코퍼스 전건 검사가 유일한 예다).
    """

    #: **이 평가가 실제로 잰 문서 집합 — 분모다.** 여기서 나온 수치의 지문
    #: (`Fingerprint.of`)도, 같은 산출물을 다시 도는 축(judge)의 순회와 커버리지 분모도
    #: 이 값에서 나와야 한다. 하류가 모듈 전역을 다시 읽으면 "지문은 전역·수치는 다른 집합"이
    #: 만들어지고, 그것은 지문이 막으려던 바로 그 우회다(11번째 지적).
    documents: list[GoldenDocument]
    #: **이 평가가 실제로 본 변환 결과.** 규칙 평가가 여기서 나왔으므로, 같은 산출물을
    #: 다시 보는 축(judge)은 이 값을 채점해야 "무엇을 채점했는가"가 측정치와 같은 근원을
    #: 갖는다. 별도 fixture 로 받으면 그 축만 다른 실행을 볼 수 있다.
    outcomes: Mapping[str, ConversionOutcome | None]
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

    **입력 자체도 결과에 실어 돌려준다**(`documents`·`outcomes`). 인자를 받아 쓰고 버리면
    "무엇을 쟀는가"가 결과에 남지 않아, 하류가 그것을 **모듈 전역에서 다시 구한다.**
    그 두 값이 갈리는 것이 이 사슬의 세 번째 결함이었다.

    - `outcomes` — judge 는 규칙 평가와 같은 변환 결과를 채점하는데, 그 대상을 따로 받으면
      "무엇을 채점했는가"가 이 평가와 갈릴 수 있다(10번째 지적).
    - `documents` — 지문(`Fingerprint.of`)과 judge 커버리지 분모가 여기서 나와야 한다.
      전역에서 구하면 축소된 집합으로 잰 수치가 **전량 코퍼스의 지문을 달고** 기록된다
      (11번째 지적). 인자를 받아 놓고 버리는 것이 정확히 그 통로였다.

    여기서 함께 돌려주면 소비자가 대상도 분모도 다른 데서 구할 이유가 없어진다.
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
        # 바깥 컨테이너는 복제된다(pydantic) — 호출자가 나중에 자기 목록을 고쳐도 이 평가가
        # 든 분모는 움직이지 않는다. 문서 객체 자체는 그대로라 지문은 잰 그 문서에서 나온다.
        documents=list(documents),
        outcomes=outcomes,
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
