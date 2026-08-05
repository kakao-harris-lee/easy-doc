"""골든셋 실제 LLM 평가 — 기본 실행에서 제외된다(pytest addopts의 -m 'not llm').

실행: `GOLDEN_PROVIDER=anthropic uv run pytest tests/golden -m llm`
개별 문서가 아니라 집계로 판정하되, 날조(fidelity 바닥)는 개별로 막는다.
변환은 모듈 스코프 fixture에서 한 번만 수행하고 규칙 평가와 judge 평가가 공유한다.
실패 출력에는 문서 id와 사유 코드·건수만 남긴다 (본문·문장·팩트 리터럴 출력 금지).
"""

import os
from collections import Counter
from collections.abc import AsyncIterator

import pytest
import pytest_asyncio
from pydantic import BaseModel

from app.config import Settings
from app.easyread.goldenset import GoldenDocument, load_documents
from app.easyread.judge import (
    DEFAULT_FIDELITY_FLOOR,
    JudgeScore,
    find_low_fidelity,
    judge_conversion,
)
from app.easyread.style_rules import check_style
from app.exceptions import LLMProviderError
from app.llm.factory import create_provider
from app.llm.provider import LLMProvider
from app.services.conversion import ConversionOutcome, ConversionService
from tests.golden import DOCUMENTS_DIR

pytestmark = pytest.mark.llm

DEFAULT_PROVIDER = "anthropic"
DEFAULT_JUDGE_PROVIDER = "anthropic"

# 통계 한계: n=20 단일 실행이다. 문서별 성공 확률이 진짜 90%인 provider라도
# 20건 중 18건 미만이 나올 확률이 약 32%다 — 정상 provider가 우연히 불통과할 수 있다.
# master-plan 7장 KPI 수치를 게이트로 차용한 값이며, 경계에서 흔들릴 경우
# 재실행·표본 확대 판단은 사람이 한다(자동 재시도로 숨기지 않는다).
PASS_RATE_THRESHOLD = 0.9
# 채점이 이루어진 문서 비율 하한 — 대부분 실패한 provider가 남은 소수의 높은 점수로
# 통과하는 것을 막는 별도 기준이다(통과율 기준과 목적이 달라 상수를 분리한다).
JUDGE_COVERAGE_THRESHOLD = 0.9
JUDGE_SCORE_THRESHOLD = 4.0
# 바닥 게이트: fidelity 1~2는 중요 정보 누락·날조라 평균으로 상쇄할 수 없다.
# 평균 게이트만 두면 15건 5점 + 5건 1점(날조 25%)이 평균 4.0으로 통과한다.
# 판정 자체는 judge.find_low_fidelity()에 있고 기본 스위트가 회귀 테스트로 고정한다
# (이 파일은 -m llm이라 기본 실행에서 제외되므로 여기에만 두면 보호되지 않는다).
FIDELITY_FLOOR = DEFAULT_FIDELITY_FLOOR

DOCUMENTS: list[GoldenDocument] = load_documents(DOCUMENTS_DIR)


class DocumentEvaluation(BaseModel):
    """문서 한 건의 평가 결과. failures에는 사유 코드만 담는다."""

    document_id: str
    failures: list[str]

    @property
    def passed(self) -> bool:
        return not self.failures


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def provider() -> AsyncIterator[LLMProvider]:
    """GOLDEN_PROVIDER로 지정한 변환용 provider (키가 없으면 skip)."""
    name = os.environ.get("GOLDEN_PROVIDER", DEFAULT_PROVIDER)
    created = create_provider(name, Settings())
    if created is None:
        pytest.skip(f"{name} API 키 없음 — 골든셋 평가를 건너뜁니다")
    try:
        yield created
    finally:
        await created.aclose()


@pytest_asyncio.fixture(scope="module", loop_scope="module")
async def judge_provider() -> AsyncIterator[LLMProvider]:
    """GOLDEN_JUDGE_PROVIDER로 지정한 채점용 provider (키가 없으면 skip)."""
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
    """전 문서를 한 번만 변환해 규칙 평가와 judge 평가가 공유한다(LLM 호출 절감)."""
    return await convert_all(provider)


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


def evaluate_rules(document: GoldenDocument, outcome: ConversionOutcome) -> list[str]:
    """규칙 기반 3개 조건을 확인하고 위반 사유 코드를 돌려준다."""
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


def format_report(evaluations: list[DocumentEvaluation], pass_rate: float) -> str:
    """실패 리포트 — 문서 id와 사유 코드만 출력한다."""
    lines = [f"통과율 {pass_rate:.2f} (기준 {PASS_RATE_THRESHOLD})"]
    lines.extend(
        f"- {evaluation.document_id}: {', '.join(evaluation.failures)}"
        for evaluation in evaluations
        if not evaluation.passed
    )
    return "\n".join(lines)


def format_judge_report(
    scores: dict[str, JudgeScore], failures: list[str], fidelity: float, readability: float
) -> str:
    """judge 리포트 — 문서 id와 점수만 출력한다.

    JudgeScore.comment에는 문서 본문 일부가 인용될 수 있어 절대 출력하지 않는다.
    """
    lines = [
        f"충실성 평균 {fidelity:.2f} / 이해 용이성 평균 {readability:.2f} "
        f"(기준 각 {JUDGE_SCORE_THRESHOLD}, 바닥 {FIDELITY_FLOOR} 초과)"
    ]
    lines.extend(
        f"- {document_id}: 충실성 {score.fidelity}, 이해 용이성 {score.readability}"
        for document_id, score in sorted(scores.items())
    )
    lines.extend(f"- {failure}" for failure in failures)
    return "\n".join(lines)


@pytest.mark.asyncio(loop_scope="module")
async def test_골든셋_규칙_기반_통과율(outcomes: dict[str, ConversionOutcome | None]) -> None:
    """세 조건(스타일 규칙·팩트 잔존·플레이스홀더 보존)을 모두 만족한 문서 비율을 판정한다."""
    evaluations: list[DocumentEvaluation] = []
    for document in DOCUMENTS:
        outcome = outcomes[document.id]
        failures = (
            ["변환실패(LLMProviderError)"] if outcome is None else evaluate_rules(document, outcome)
        )
        evaluations.append(DocumentEvaluation(document_id=document.id, failures=failures))
    pass_rate = sum(evaluation.passed for evaluation in evaluations) / len(evaluations)
    assert pass_rate >= PASS_RATE_THRESHOLD, format_report(evaluations, pass_rate)


@pytest.mark.asyncio(loop_scope="module")
async def test_골든셋_judge_점수(
    outcomes: dict[str, ConversionOutcome | None], judge_provider: LLMProvider
) -> None:
    """judge 평균이 기준 이상이고, 충실성 바닥(날조 의심) 문서가 하나도 없어야 한다."""
    scores: dict[str, JudgeScore] = {}
    failures: list[str] = []
    for document in DOCUMENTS:
        outcome = outcomes[document.id]
        if outcome is None:
            failures.append(f"{document.id}: 변환실패")
            continue
        try:
            scores[document.id] = await judge_conversion(
                judge_provider, source=document.source_text, converted=outcome.easy_text
            )
        except LLMProviderError:
            failures.append(f"{document.id}: 채점실패")

    coverage = len(scores) / len(DOCUMENTS)
    assert coverage >= JUDGE_COVERAGE_THRESHOLD, f"채점된 문서 비율 {coverage:.2f}\n" + "\n".join(
        failures
    )

    fidelity = sum(score.fidelity for score in scores.values()) / len(scores)
    readability = sum(score.readability for score in scores.values()) / len(scores)
    report = format_judge_report(scores, failures, fidelity, readability)

    # 바닥 게이트를 평균보다 먼저 본다 — 날조가 섞였다면 평균이 높아도 실패여야 한다.
    low_fidelity = find_low_fidelity(scores, FIDELITY_FLOOR)
    assert not low_fidelity, f"충실성 {FIDELITY_FLOOR} 이하 문서: {low_fidelity}\n{report}"
    assert fidelity >= JUDGE_SCORE_THRESHOLD, report
    assert readability >= JUDGE_SCORE_THRESHOLD, report
