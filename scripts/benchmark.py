"""LLM 벤더 비교 벤치마크 — 골든셋 전 문서를 provider별로 변환해 지표를 모은다.

실행 예:
    uv run python scripts/benchmark.py --providers anthropic,openai --judge anthropic

측정: 규칙 통과율(check_style) · 팩트 잔존율 · 플레이스홀더 유실 · judge 점수(옵션)
     · 충실성 바닥(fidelity<=2) 문서 수 · 응답 지연(초, 변환 호출만)
     · 출력 문자 수 · 입력/출력 토큰.

지연 측정의 정확성을 위해 동시 실행하지 않고 순차로 호출한다. provider마다 측정 전
워밍업 호출을 한 번 버려 커넥션 수립 비용이 첫 문서에 실리지 않게 한다.

보안: 리포트·표준출력에 문서 본문과 변환 결과를 절대 남기지 않는다.
      문서 ID·수치·실패 사유 코드만 기록한다 (CLAUDE.md 보안·데이터 규칙).
"""

import argparse
import asyncio
import statistics
import sys
import time
from datetime import datetime
from pathlib import Path

from pydantic import BaseModel

REPO_ROOT = Path(__file__).resolve().parents[1]
# 스크립트를 직접 실행하면 sys.path[0]이 scripts/라 app 패키지를 찾지 못한다.
# 프로젝트를 설치형 패키지로 만들지 않았으므로 리포 루트를 직접 넣어 준다.
sys.path.insert(0, str(REPO_ROOT))

from app.config import Settings  # noqa: E402
from app.easyread.goldenset import GoldenDocument, load_documents  # noqa: E402
from app.easyread.judge import (  # noqa: E402
    DEFAULT_FIDELITY_FLOOR,
    JudgeScore,
    find_low_fidelity,
    judge_conversion,
)
from app.easyread.style_rules import check_style  # noqa: E402
from app.exceptions import LLMProviderError  # noqa: E402
from app.llm.factory import create_provider  # noqa: E402
from app.llm.provider import LLMProvider  # noqa: E402
from app.services.conversion import ConversionService  # noqa: E402

DOCUMENTS_DIR = REPO_ROOT / "tests" / "golden" / "documents"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "docs" / "benchmarks"
DEFAULT_PROVIDERS = "anthropic,openai"
# 충실성 바닥 — 이하이면 중요 정보 누락·날조 의심이라 평균과 별도로 센다.
# 판정은 judge.find_low_fidelity()를 재사용해 평가 하네스와 기준이 갈라지지 않게 한다.
FIDELITY_FLOOR = DEFAULT_FIDELITY_FLOOR
# 워밍업용 최소 입력. 결과는 버리고 측정에 넣지 않는다.
WARMUP_TEXT = "안내문입니다."


class DocumentMeasurement(BaseModel):
    """문서 한 건의 측정값. 본문·변환 결과는 담지 않는다."""

    document_id: str
    succeeded: bool
    failure_code: str = "-"
    model: str = "-"
    style_passed: bool = False
    style_issues: int = 0
    facts_total: int = 0
    facts_kept: int = 0
    missing_placeholders: int = 0
    latency_seconds: float = 0.0
    output_chars: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    fidelity: int | None = None
    readability: int | None = None


class ProviderBenchmark(BaseModel):
    """provider 한 곳의 전체 측정 결과."""

    provider_name: str
    model: str
    measurements: list[DocumentMeasurement]


class ProviderSummary(BaseModel):
    """리포트 비교 표에 들어가는 집계값.

    성공한 호출에서만 나오는 값(지연·토큰·judge)은 전멸한 provider에서 0으로
    보이면 오해를 부르므로 None으로 두고 리포트에서 "-"로 렌더링한다.
    """

    provider_name: str
    model: str
    documents: int
    failures: int
    style_pass_rate: float
    fact_retention: float
    placeholder_loss: int
    judged: int
    fidelity_average: float | None
    readability_average: float | None
    low_fidelity: int
    latency_median: float | None
    latency_max: float | None
    input_tokens_average: float | None
    output_tokens_average: float | None


def average_or_none(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


async def measure_document(
    service: ConversionService,
    document: GoldenDocument,
    judge_provider: LLMProvider | None,
) -> DocumentMeasurement:
    """문서 한 건을 변환·채점하고 측정값을 만든다. 변환 실패는 실패로 기록하고 계속한다."""
    started = time.perf_counter()
    try:
        outcome = await service.convert(document.source_text)
    except LLMProviderError as error:
        # 예외 유형이 절단·빈 결과·호출 실패를 구분한다 (app/exceptions.py).
        return DocumentMeasurement(
            document_id=document.id,
            succeeded=False,
            failure_code=type(error).__name__,
            facts_total=len(document.required_facts),
            latency_seconds=time.perf_counter() - started,
        )
    latency = time.perf_counter() - started

    style = check_style(outcome.easy_text)
    # 팩트 잔존 판정은 GoldenDocument.missing_facts() 한 곳만 쓴다(평가 테스트와 동일 기준).
    missing_facts = document.missing_facts(outcome.easy_text)
    fidelity: int | None = None
    readability: int | None = None
    if judge_provider is not None:
        try:
            score = await judge_conversion(
                judge_provider, source=document.source_text, converted=outcome.easy_text
            )
        except LLMProviderError as error:
            print(f"    {document.id}: 채점 실패 ({type(error).__name__})")
        else:
            fidelity, readability = score.fidelity, score.readability

    return DocumentMeasurement(
        document_id=document.id,
        succeeded=True,
        model=outcome.model,
        style_passed=style.passed,
        style_issues=len(style.issues),
        facts_total=len(document.required_facts),
        facts_kept=len(document.required_facts) - len(missing_facts),
        missing_placeholders=len(outcome.missing_placeholders),
        latency_seconds=latency,
        output_chars=len(outcome.easy_text),
        input_tokens=outcome.input_tokens,
        output_tokens=outcome.output_tokens,
        fidelity=fidelity,
        readability=readability,
    )


async def warm_up(service: ConversionService) -> None:
    """커넥션 수립 비용이 첫 문서 지연에 섞이지 않도록 버리는 호출을 한 번 한다."""
    try:
        await service.convert(WARMUP_TEXT)
    except LLMProviderError as error:
        print(f"    워밍업 호출 실패 ({type(error).__name__}) — 측정은 그대로 진행합니다")


async def measure_provider(
    provider: LLMProvider,
    documents: list[GoldenDocument],
    judge_provider: LLMProvider | None,
) -> ProviderBenchmark:
    """provider 하나로 골든셋 전 문서를 순차 측정한다."""
    service = ConversionService(provider=provider)
    await warm_up(service)
    measurements: list[DocumentMeasurement] = []
    for document in documents:
        measurement = await measure_document(service, document, judge_provider)
        measurements.append(measurement)
        if measurement.succeeded:
            print(
                f"    {document.id}: 성공 "
                f"규칙 {'통과' if measurement.style_passed else '위반'} "
                f"팩트 {measurement.facts_kept}/{measurement.facts_total} "
                f"지연 {measurement.latency_seconds:.2f}초"
            )
        else:
            print(
                f"    {document.id}: 변환 실패({measurement.failure_code}) "
                f"지연 {measurement.latency_seconds:.2f}초"
            )
    # 모델명은 응답이 알려 준 값을 쓴다 — 전부 실패했으면 확인할 방법이 없어 "-"로 둔다.
    model = next((m.model for m in measurements if m.succeeded), "-")
    return ProviderBenchmark(provider_name=provider.name, model=model, measurements=measurements)


def summarize(benchmark: ProviderBenchmark) -> ProviderSummary:
    """provider 측정값을 비교 표용 집계로 줄인다."""
    measurements = benchmark.measurements
    successes = [m for m in measurements if m.succeeded]
    facts_total = sum(m.facts_total for m in measurements)
    # 바닥 판정을 평가 하네스와 같은 함수로 하기 위해 점수만 JudgeScore로 되살린다.
    # comment는 본문 인용 위험 때문에 측정값에 보관하지 않으며 판정에도 쓰이지 않는다.
    judged_scores = {
        m.document_id: JudgeScore(fidelity=m.fidelity, readability=m.readability, comment="")
        for m in successes
        if m.fidelity is not None and m.readability is not None
    }
    fidelities = [score.fidelity for score in judged_scores.values()]
    readabilities = [score.readability for score in judged_scores.values()]
    latencies = [m.latency_seconds for m in successes]
    return ProviderSummary(
        provider_name=benchmark.provider_name,
        model=benchmark.model,
        documents=len(measurements),
        failures=len(measurements) - len(successes),
        # 분모는 전체 문서 수 — 실패한 문서를 빼면 실패가 많은 벤더가 유리해진다.
        style_pass_rate=(
            sum(1 for m in successes if m.style_passed) / len(measurements) if measurements else 0.0
        ),
        fact_retention=(
            sum(m.facts_kept for m in measurements) / facts_total if facts_total else 0.0
        ),
        placeholder_loss=sum(m.missing_placeholders for m in measurements),
        judged=len(judged_scores),
        fidelity_average=average_or_none([float(value) for value in fidelities]),
        readability_average=average_or_none([float(value) for value in readabilities]),
        low_fidelity=len(find_low_fidelity(judged_scores, FIDELITY_FLOOR)),
        # 지연·토큰은 성공한 호출만 집계한다(실패는 조기 종료라 분포가 다르다).
        # n=20 단일 실행이라 p95는 사실상 두 번째 큰 값이므로 중앙값·최대값만 낸다.
        latency_median=statistics.median(latencies) if latencies else None,
        latency_max=max(latencies) if latencies else None,
        input_tokens_average=average_or_none([float(m.input_tokens) for m in successes]),
        output_tokens_average=average_or_none([float(m.output_tokens) for m in successes]),
    )


def format_number(value: float | None, digits: int = 2) -> str:
    """측정값이 없으면 0이 아니라 "-"로 표기한다(전멸 provider 오해 방지)."""
    return f"{value:.{digits}f}" if value is not None else "-"


def format_judge_average(value: float | None, judged: int) -> str:
    return f"{value:.2f} ({judged}건)" if value is not None else "-"


def render_comparison_table(summaries: list[ProviderSummary]) -> str:
    header = (
        "| provider | 모델 | 문서 | 실패 | 규칙 통과율 | 팩트 잔존율 | 플레이스홀더 유실 "
        "| judge 충실성 | judge 이해 용이성 | 충실성≤2 문서 | 지연 중앙값(초) | 지연 최대(초) "
        "| 평균 입력 토큰 | 평균 출력 토큰 |\n"
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: "
        "| ---: | ---: |"
    )
    rows = [
        f"| {s.provider_name} | {s.model} | {s.documents} | {s.failures} "
        f"| {s.style_pass_rate:.0%} | {s.fact_retention:.0%} | {s.placeholder_loss} "
        f"| {format_judge_average(s.fidelity_average, s.judged)} "
        f"| {format_judge_average(s.readability_average, s.judged)} | {s.low_fidelity} "
        f"| {format_number(s.latency_median)} | {format_number(s.latency_max)} "
        f"| {format_number(s.input_tokens_average, 0)} "
        f"| {format_number(s.output_tokens_average, 0)} |"
        for s in summaries
    ]
    return "\n".join([header, *rows])


def render_detail_table(benchmark: ProviderBenchmark) -> str:
    header = (
        "| 문서 ID | 결과 | 규칙 위반 | 팩트 잔존 | 플레이스홀더 유실 | 충실성 | 이해 용이성 "
        "| 지연(초) | 출력 문자 | 입력 토큰 | 출력 토큰 |\n"
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    )
    rows = [
        f"| {m.document_id} | {'성공' if m.succeeded else m.failure_code} | {m.style_issues} "
        f"| {m.facts_kept}/{m.facts_total} | {m.missing_placeholders} "
        f"| {m.fidelity if m.fidelity is not None else '-'} "
        f"| {m.readability if m.readability is not None else '-'} "
        f"| {m.latency_seconds:.2f} | {m.output_chars} | {m.input_tokens} | {m.output_tokens} |"
        for m in benchmark.measurements
    ]
    return "\n".join([header, *rows])


def render_report(
    benchmarks: list[ProviderBenchmark], judge_name: str, generated_at: datetime
) -> str:
    """벤치마크 리포트 markdown을 만든다 (문서 본문·변환 결과 미포함)."""
    summaries = [summarize(benchmark) for benchmark in benchmarks]
    sections = [
        f"# LLM 벤치마크 결과 ({generated_at:%Y-%m-%d %H:%M})",
        "",
        f"- 골든셋: 합성 {summaries[0].documents}건 (`tests/golden/documents`)",
        f"- judge: {judge_name}",
        f"- 충실성≤{FIDELITY_FLOOR} 문서는 정보 누락·날조 의심 — 평균과 별도로 확인할 것.",
        "- 지연은 문서 1건당 변환 호출만 측정했다. 문서 길이 편차(555~1146자)가 그대로",
        "  섞여 있고 각 문서를 1회만 호출한 단일 실행이라 반복 측정 통계가 아니다.",
        "  provider 간 상대 비교 참고용으로만 쓰고, 절대 지연 보증에는 쓰지 않는다.",
        "- 측정값이 없는 칸은 `-`로 표기한다(0이 아님).",
        "- 이 리포트에는 문서 본문과 변환 결과를 기록하지 않는다 (문서 ID·수치만).",
        "",
        "## provider 비교",
        "",
        render_comparison_table(summaries),
        "",
    ]
    for benchmark in benchmarks:
        sections.extend(
            [
                f"## 문서별 상세 — {benchmark.provider_name}",
                "",
                render_detail_table(benchmark),
                "",
            ]
        )
    sections.extend(["## 벤더 확정 판단 근거", "", "<!-- 사람이 채웁니다. -->", ""])
    return "\n".join(sections)


async def run(providers: list[str], judge: str | None, output_dir: Path) -> int:
    """벤치마크를 실행하고 종료 코드를 돌려준다."""
    settings = Settings()
    documents = load_documents(DOCUMENTS_DIR)
    print(f"골든셋 {len(documents)}건 로드")

    judge_provider: LLMProvider | None = None
    if judge is not None:
        judge_provider = create_provider(judge, settings)
        if judge_provider is None:
            print(f"경고: {judge} API 키가 없어 judge 채점 없이 진행합니다")

    benchmarks: list[ProviderBenchmark] = []
    try:
        for name in providers:
            provider = create_provider(name, settings)
            if provider is None:
                print(f"경고: {name} API 키가 없어 건너뜁니다")
                continue
            print(f"[{name}] 워밍업 후 측정 시작")
            try:
                benchmarks.append(await measure_provider(provider, documents, judge_provider))
            finally:
                await provider.aclose()
    finally:
        if judge_provider is not None:
            await judge_provider.aclose()

    if not benchmarks:
        print("경고: 모든 provider를 건너뛰었습니다 — 리포트를 생성하지 않습니다")
        # 측정이 하나도 없었다는 사실을 종료 코드로 알린다(CI·스크립트가 성공으로 오인 금지).
        return 1

    generated_at = datetime.now()
    output_dir.mkdir(parents=True, exist_ok=True)
    # 같은 날 여러 번 돌려도 이전 결과를 덮어쓰지 않도록 시각까지 파일명에 넣는다.
    report_path = output_dir / f"{generated_at:%Y-%m-%d-%H%M}-llm-benchmark.md"
    judge_name = judge if judge is not None and judge_provider is not None else "사용 안 함"
    report_path.write_text(render_report(benchmarks, judge_name, generated_at), encoding="utf-8")
    print(f"리포트 저장: {report_path}")
    return 0


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="골든셋으로 LLM provider를 비교하는 벤치마크 (문서 본문은 기록하지 않음)"
    )
    parser.add_argument(
        "--providers",
        default=DEFAULT_PROVIDERS,
        help=f"쉼표로 구분한 provider 이름 (기본: {DEFAULT_PROVIDERS})",
    )
    parser.add_argument(
        "--judge",
        default=None,
        help="LLM-as-judge 채점에 쓸 provider 이름 (지정하지 않으면 채점 생략)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"리포트를 저장할 디렉터리 (기본: {DEFAULT_OUTPUT_DIR})",
    )
    return parser.parse_args(argv)


def main() -> int:
    args = parse_args()
    providers = [name.strip() for name in str(args.providers).split(",") if name.strip()]
    if not providers:
        print("오류: --providers에 provider 이름을 하나 이상 지정하세요")
        return 2
    judge: str | None = args.judge
    output_dir: Path = args.output
    try:
        return asyncio.run(run(providers, judge, output_dir))
    except ValueError as error:
        # create_provider가 지원하지 않는 이름을 거부한 경우 — 트레이스백 대신 한 줄로 알린다.
        print(f"오류: {error}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
