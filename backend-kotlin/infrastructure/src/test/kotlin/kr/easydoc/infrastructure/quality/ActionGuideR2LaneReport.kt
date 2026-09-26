package kr.easydoc.infrastructure.quality

import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.core.actionguide.ActionGuideCandidate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/** R2 후보 본문을 로그에 섞지 않고 구조·호출·비용만 요약하는 레인 보고서. */
@Suppress("LongParameterList")
internal class ActionGuideR2LaneReport(
    private val description: String,
    private val journal: LaneJournal,
    private val spendLimit: LaneSpendLimit,
    private val maxUsd: BigDecimal,
    private val maxCalls: Int,
    private val cohort: String = "unknown",
    private val plannedReservationUsd: BigDecimal? = null,
) {
    private val measurements = mutableListOf<ActionGuideR2Measurement>()
    private val qualityFailures = mutableListOf<String>()
    private val infrastructureFailures = mutableListOf<String>()

    fun record(
        documentId: String,
        run: Int?,
        result: ActionGuideRunResult,
        elapsed: Duration,
        invalidReason: String? = null,
    ) {
        val repetition = run ?: 1
        val measurement =
            when (result) {
                is ActionGuideRunResult.ValidAnalysis -> {
                    error("v1 품질 레인은 행동 분석 결과를 평가하지 않습니다")
                }

                is ActionGuideRunResult.Valid -> {
                    ActionGuideR2Measurement(
                        documentId = documentId,
                        run = repetition,
                        outcome = R2Outcome.VALID,
                        inputTokens = result.record.inputTokens,
                        outputTokens = result.record.outputTokens,
                        latencyMs = result.record.latencyMs ?: elapsed.toMillis(),
                        candidate = structureOf(result.candidate),
                    )
                }

                is ActionGuideRunResult.Invalid -> {
                    val reason = invalidReason ?: "runner_invalid_without_reason"
                    qualityFailures += "$cohort/$documentId run=$repetition: parser=$reason"
                    ActionGuideR2Measurement(
                        documentId = documentId,
                        run = repetition,
                        outcome = R2Outcome.INVALID,
                        inputTokens = result.record.inputTokens,
                        outputTokens = result.record.outputTokens,
                        latencyMs = result.record.latencyMs ?: elapsed.toMillis(),
                        candidate = null,
                        failureReason = reason.replace(Regex("\\s+"), "_"),
                    )
                }

                is ActionGuideRunResult.ProviderFailed -> {
                    infrastructureFailures +=
                        "$cohort/$documentId run=$repetition: provider 호출 실패 — 원인 " +
                        (result.record.failureClass ?: "미기록")
                    ActionGuideR2Measurement(
                        documentId = documentId,
                        run = repetition,
                        outcome = R2Outcome.PROVIDER_FAILED,
                        inputTokens = result.record.inputTokens,
                        outputTokens = result.record.outputTokens,
                        latencyMs = result.record.latencyMs ?: elapsed.toMillis(),
                        candidate = null,
                    )
                }
            }
        measurements += measurement
    }

    fun failures(): List<String> = qualityFailures + infrastructureFailures

    fun render(): String {
        val valid = measurements.count { it.outcome == R2Outcome.VALID }
        val invalid = measurements.count { it.outcome == R2Outcome.INVALID }
        val providerFailed = measurements.count { it.outcome == R2Outcome.PROVIDER_FAILED }
        val availableSections = measurements.sumOf { it.candidate?.availableSections ?: 0 }
        val reviewSections = measurements.sumOf { it.candidate?.needsReviewSections ?: 0 }
        val absentSections = measurements.sumOf { it.candidate?.notInSourceSections ?: 0 }
        val itemCount = measurements.sumOf { it.candidate?.itemCount ?: 0 }
        val anchorCount = measurements.sumOf { it.candidate?.anchorCount ?: 0 }
        val elapsed = measurements.map { it.latencyMs }
        return buildString {
            appendLine("R2 행동 안내문 LLM 레인 — $description")
            appendLine(
                "실행 ${measurements.size}건 · 구조 검증 통과 $valid · " +
                    "후보 무효 $invalid · provider 실패 $providerFailed",
            )
            appendLine(
                "섹션 상태 합계 — available $availableSections · needs_review $reviewSections · " +
                    "not_in_source $absentSections · 항목 $itemCount · 근거 $anchorCount",
            )
            appendLine(
                "LLM 호출 ${journal.calls}회/${maxCalls}회 · 재시도 ${journal.retries}회 · " +
                    "입력 ${journal.inputTokens} 토큰 · 출력 ${journal.outputTokens} 토큰",
            )
            appendLine(
                "보수적 예약 US$${spendLimit.reservedUsd.setScale(6, RoundingMode.HALF_UP)} · " +
                    "상한 US$${maxUsd.setScale(6, RoundingMode.HALF_UP)} · " +
                    costLine(),
            )
            appendLine("사전 예약 상계 ${plannedReservationLine()}")
            appendLine(
                "지연 — 합계 ${elapsed.sum()}ms · 중앙값 ${median(elapsed)}ms · " +
                    "p95 ${percentile95(elapsed)}ms (n=${elapsed.size}, 탐색치) · " +
                    "최대 ${elapsed.maxOrNull() ?: 0}ms",
            )
            measurements.forEach { measurement ->
                appendLine(
                    "result cohort=$cohort document=${measurement.documentId} run=${measurement.run} " +
                        "outcome=${measurement.outcome.wireName} latency_ms=${measurement.latencyMs} " +
                        "input_tokens=${measurement.inputTokens} output_tokens=${measurement.outputTokens}" +
                        measurement.failureReasonSuffix(),
                )
            }
            append(appendSection("품질 실패", qualityFailures))
            append(appendSection("인프라 실패", infrastructureFailures))
        }.trimEnd()
    }

    private fun plannedReservationLine(): String =
        plannedReservationUsd?.let { "US$${it.setScale(6, RoundingMode.HALF_UP)}" } ?: "미계산"

    private fun costLine(): String {
        val cost = journal.estimatedCostUsd
        return if (cost == null) {
            "실측 비용 미산출(단가 없는 호출 ${journal.costUnknownCalls}건)"
        } else {
            "실측 예상 비용 US$${cost.setScale(6, RoundingMode.HALF_UP)}"
        }
    }

    private fun appendSection(
        title: String,
        lines: List<String>,
    ): String =
        if (lines.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine("$title — ${lines.size}건")
                lines.forEach(::appendLine)
            }
        }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle] + 1) / 2
        } else {
            sorted[middle]
        }
    }

    /** 작은 표본의 분포를 숨기지 않도록 nearest-rank p95와 표본 수를 함께 남긴다. */
    private fun percentile95(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val rank = ((sorted.size * 95) + 99) / 100
        return sorted[rank - 1]
    }

    private fun structureOf(candidate: ActionGuideCandidate): R2CandidateStructure =
        R2CandidateStructure(
            availableSections = candidate.sections.count { it.status.wireName == "available" },
            needsReviewSections = candidate.sections.count { it.status.wireName == "needs_review" },
            notInSourceSections = candidate.sections.count { it.status.wireName == "not_in_source" },
            itemCount = candidate.sections.sumOf { it.items.size },
            anchorCount = candidate.sections.sumOf { section -> section.items.sumOf { it.sourceAnchors.size } },
        )

    private enum class R2Outcome {
        VALID,
        INVALID,
        PROVIDER_FAILED,
        ;

        val wireName: String
            get() = name.lowercase()
    }

    private data class R2CandidateStructure(
        val availableSections: Int,
        val needsReviewSections: Int,
        val notInSourceSections: Int,
        val itemCount: Int,
        val anchorCount: Int,
    )

    private data class ActionGuideR2Measurement(
        val documentId: String,
        val run: Int,
        val outcome: R2Outcome,
        val inputTokens: Int,
        val outputTokens: Int,
        val latencyMs: Long,
        val candidate: R2CandidateStructure?,
        val failureReason: String? = null,
    )

    private fun ActionGuideR2Measurement.failureReasonSuffix(): String =
        failureReason?.let { " parser_failure=$it" } ?: ""
}
