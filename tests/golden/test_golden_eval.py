"""골든셋 실제 LLM 평가 — 기본 실행에서 제외된다(pytest addopts의 -m 'not llm').

실행: `GOLDEN_PROVIDER=anthropic uv run pytest tests/golden -m llm`
개별 문서가 아니라 집계(통과율)로 판정한다 — KPI 기준은 master-plan 7장.
실패 출력에는 문서 id와 사유 코드·건수만 남긴다 (본문·문장·팩트 리터럴 출력 금지).
"""

import os
from collections.abc import AsyncIterator
from statistics import fmean

import pytest
from pydantic import BaseModel

from app.config import Settings
from app.easyread.goldenset import GoldenDocument, load_documents
from app.easyread.judge import JudgeScore, judge_conversion
from app.easyread.style_rules import check_style
from app.exceptions import LLMProviderError
from app.llm.factory import create_provider
from app.llm.provider import LLMProvider
from app.services.conversion import ConversionOutcome, ConversionService
from tests.golden import DOCUMENTS_DIR

pytestmark = pytest.mark.llm

DEFAULT_PROVIDER = "anthropic"
DEFAULT_JUDGE_PROVIDER = "anthropic"
PASS_RATE_THRESHOLD = 0.9
JUDGE_SCORE_THRESHOLD = 4.0

DOCUMENTS: list[GoldenDocument] = load_documents(DOCUMENTS_DIR)


class DocumentEvaluation(BaseModel):
    """문서 한 건의 평가 결과. failures에는 사유 코드만 담는다."""

    document_id: str
    failures: list[str]

    @property
    def passed(self) -> bool:
        return not self.failures


@pytest.fixture
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


@pytest.fixture
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


def evaluate_rules(document: GoldenDocument, outcome: ConversionOutcome) -> list[str]:
    """규칙 기반 3개 조건을 확인하고 위반 사유 코드를 돌려준다."""
    failures: list[str] = []
    style = check_style(outcome.easy_text)
    if not style.passed:
        failures.append(f"스타일위반 {len(style.issues)}건")
    missing_facts = [fact for fact in document.required_facts if fact not in outcome.easy_text]
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


async def convert_all(provider: LLMProvider) -> dict[str, ConversionOutcome | None]:
    """전 문서를 변환한다. 변환 실패는 None으로 기록하고 계속 진행한다."""
    service = ConversionService(provider=provider)
    outcomes: dict[str, ConversionOutcome | None] = {}
    for document in DOCUMENTS:
        try:
            outcomes[document.id] = await service.convert(document.source_text)
        except LLMProviderError:
            # 예외 메시지에도 본문이 없도록 설계되어 있으나, 출력은 사유 코드로만 남긴다.
            outcomes[document.id] = None
    return outcomes


async def test_골든셋_규칙_기반_통과율(provider: LLMProvider) -> None:
    """세 조건(스타일 규칙·팩트 잔존·플레이스홀더 보존)을 모두 만족한 문서 비율을 판정한다."""
    outcomes = await convert_all(provider)
    evaluations: list[DocumentEvaluation] = []
    for document in DOCUMENTS:
        outcome = outcomes[document.id]
        failures = (
            ["변환실패(LLMProviderError)"] if outcome is None else evaluate_rules(document, outcome)
        )
        evaluations.append(DocumentEvaluation(document_id=document.id, failures=failures))
    pass_rate = sum(evaluation.passed for evaluation in evaluations) / len(evaluations)
    assert pass_rate >= PASS_RATE_THRESHOLD, format_report(evaluations, pass_rate)


def format_judge_report(
    scores: dict[str, JudgeScore], failures: list[str], fidelity: float, readability: float
) -> str:
    """judge 실패 리포트 — 문서 id와 점수만 출력한다.

    JudgeScore.comment에는 문서 본문 일부가 인용될 수 있어 절대 출력하지 않는다.
    """
    lines = [
        f"충실성 평균 {fidelity:.2f} / 이해 용이성 평균 {readability:.2f} "
        f"(기준 각 {JUDGE_SCORE_THRESHOLD})"
    ]
    lines.extend(
        f"- {document_id}: 충실성 {score.fidelity}, 이해 용이성 {score.readability}"
        for document_id, score in sorted(scores.items())
    )
    lines.extend(f"- {failure}" for failure in failures)
    return "\n".join(lines)


async def test_골든셋_judge_평균_점수(provider: LLMProvider, judge_provider: LLMProvider) -> None:
    """LLM-as-judge 충실성·이해 용이성 평균이 각각 기준 이상이어야 한다."""
    outcomes = await convert_all(provider)
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
    # 대부분이 실패한 provider가 남은 소수의 높은 점수로 통과하지 않도록 채점 범위를 먼저 본다.
    coverage = len(scores) / len(DOCUMENTS)
    assert coverage >= PASS_RATE_THRESHOLD, f"채점된 문서 비율 {coverage:.2f}\n" + "\n".join(
        failures
    )
    fidelity = fmean(score.fidelity for score in scores.values())
    readability = fmean(score.readability for score in scores.values())
    assert fidelity >= JUDGE_SCORE_THRESHOLD, format_judge_report(
        scores, failures, fidelity, readability
    )
    assert readability >= JUDGE_SCORE_THRESHOLD, format_judge_report(
        scores, failures, fidelity, readability
    )
