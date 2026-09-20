package kr.easydoc.infrastructure.quality

import kr.easydoc.application.actionguide.ActionGuideRunResult
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.actionguide.ActionGuideCandidateParser
import kr.easydoc.core.actionguide.ActionGuideJobStatus
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.quality.GoldenDocument
import kr.easydoc.core.quality.GoldenDocumentLoader
import kr.easydoc.core.segment.splitUnits
import kr.easydoc.infrastructure.actionguide.ActionGuideGenerationInput
import kr.easydoc.infrastructure.actionguide.ActionGuideInputSource
import kr.easydoc.infrastructure.actionguide.ProviderActionGuideJobRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 승인된 R2 action-guide 표본을 production runner로 한 번씩 생성한다.
 *
 * 기존 Kotlin `testLlm` 태스크의 provider 조립·계측·보수적 달러 예약을 재사용한다. 이 테스트는
 * `EASYDOC_R2_LANE_ENABLED=true`가 없으면 호출하지 않고, 보류 표본은 cohort를 명시하지 않으면
 * 절대 선택하지 않는다. 구조 검증 통과는 사람의 의미 판정을 대신하지 않으므로, 선택적으로
 * 지정한 transcript 디렉터리에 후보 원문을 남겨 최종 검수에 사용한다.
 */
@Tag("llm")
class ActionGuideR2EvaluationTest {
    @Test
    @DisplayName("R2 action-guide 표본을 명시적 비용·호출 상한 안에서 평가한다")
    @Suppress("LongMethod", "NestedBlockDepth")
    fun `R2 action guide 평가`() {
        val plan =
            when (val candidate = ActionGuideR2Lane.plan(System::getenv)) {
                ActionGuideR2LanePlan.Disabled -> {
                    abort<ActionGuideR2LanePlan.Ready>(
                        "${ActionGuideR2Lane.ENABLED_ENV}=true 없이는 R2 유료 레인을 열지 않는다",
                    )
                }

                is ActionGuideR2LanePlan.Skipped -> {
                    abort<ActionGuideR2LanePlan.Ready>(candidate.reason)
                }

                is ActionGuideR2LanePlan.Unusable -> {
                    fail<ActionGuideR2LanePlan.Ready>(candidate.reason)
                }

                is ActionGuideR2LanePlan.Ready -> {
                    candidate
                }
            }

        val documents = loadDocuments(plan)
        val savedBodies = loadSavedBodies(documents)
        val journal = LaneJournal(retryBudget = 0)
        val spendLimit =
            LaneSpendLimit(
                maxUsd = plan.maxUsd,
                inputPrice = checkNotNull(plan.pricing.inputUsdPerMillionTokens),
                outputPrice = checkNotNull(plan.pricing.outputUsdPerMillionTokens),
            )
        val plannedReservationUsd = plannedReservation(plan, documents, savedBodies, spendLimit)
        check(plannedReservationUsd <= plan.maxUsd) {
            "R2 사전 예약액이 달러 상한을 넘는다: planned=$plannedReservationUsd cap=${plan.maxUsd} " +
                "calls=${plan.documentIds.size * plan.runs} — 유료 호출을 시작하지 않는다"
        }
        val description =
            "${plan.description} · cohort=${plan.cohort.name.lowercase()} · " +
                "documents=${plan.documentIds.joinToString(",")} · runs=${plan.runs} · " +
                "max_calls=${plan.maxCalls} · planned_reserved_usd=$plannedReservationUsd · " +
                "cap_usd=${plan.maxUsd}"
        val transcript = planTranscript(plan, documents)
        val report =
            ActionGuideR2LaneReport(
                description = description,
                journal = journal,
                spendLimit = spendLimit,
                maxUsd = plan.maxUsd,
                maxCalls = plan.maxCalls,
                cohort = plan.cohort.name.lowercase(),
                plannedReservationUsd = plannedReservationUsd,
            )
        transcript.writeConditions(description)
        println(
            "R2 budget preflight: calls=${plan.documentIds.size * plan.runs} " +
                "planned_reserved_usd=$plannedReservationUsd cap_usd=${plan.maxUsd}",
        )
        if (System.getenv(ActionGuideR2Lane.PREFLIGHT_ONLY_ENV).equals("true", ignoreCase = true)) {
            println("R2 preflight only: provider 호출을 시작하지 않았다")
            return
        }
        val instrumentedProvider =
            LaneInstrumentedProvider(
                delegate = plan.provider,
                journal = journal,
                policy = LaneRetryPolicy(maxAttempts = 1),
                pause = { },
                spendLimit = spendLimit,
            )

        documents.forEach { document ->
            val runIndices = if (plan.runs == 1) listOf<Int?>(null) else (1..plan.runs).toList()
            runIndices.forEach { run ->
                check(journal.calls < plan.maxCalls) {
                    "R2 호출 상한을 넘기기 전에 중단: calls=${journal.calls}, max=${plan.maxCalls}"
                }
                val journalId = journalIdOf(document.id, run)
                journal.beginDocument(journalId)
                val input = ActionGuideGenerationInput(document.sourceText, savedBodies.getValue(document.id))
                val capturingProvider = CapturingProvider(instrumentedProvider)
                val runner =
                    ProviderActionGuideJobRunner(
                        input = ActionGuideInputSource { input },
                        provider = capturingProvider,
                    )
                val startedAt = System.nanoTime()
                val result = runner.run(job())
                val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
                val parserFailure =
                    if (result is ActionGuideRunResult.Invalid) {
                        invalidReason(capturingProvider.lastCompletion, input.sourceText)
                    } else {
                        null
                    }
                report.record(document.id, run, result, elapsed, parserFailure)
                when (result) {
                    is ActionGuideRunResult.Valid -> {
                        val json = ActionGuideCandidateParser.encode(result.candidate)
                        transcript.save(document.id, json, run)
                    }

                    is ActionGuideRunResult.Invalid -> {
                        // 원문 응답은 로그에 내지 않고, 명시한 opt-in transcript 경로에만 보존한다.
                        capturingProvider.lastCompletion?.let { completion ->
                            transcript.save(document.id, completion.text, run)
                        }
                    }

                    is ActionGuideRunResult.ProviderFailed -> {
                        Unit
                    }
                }
            }
        }

        println(report.render())
        assertThat(journal.calls)
            .withFailMessage { report.render() }
            .isLessThanOrEqualTo(plan.maxCalls)
        assertThat(report.failures())
            .withFailMessage { report.render() }
            .isEmpty()
    }

    private fun plannedReservation(
        plan: ActionGuideR2LanePlan.Ready,
        documents: List<GoldenDocument>,
        savedBodies: Map<String, String>,
        spendLimit: LaneSpendLimit,
    ): java.math.BigDecimal =
        documents.fold(java.math.BigDecimal.ZERO) { total, document ->
            var documentTotal = java.math.BigDecimal.ZERO
            repeat(plan.runs) {
                val prompt = LlmPrompt.forActionGuide(document.sourceText, savedBodies.getValue(document.id))
                documentTotal += spendLimit.reservationFor(prompt, plan.options)
            }
            total + documentTotal
        }

    @Suppress("ReturnCount")
    private fun invalidReason(
        completion: LlmCompletion?,
        sourceText: String,
    ): String {
        if (completion == null) return "completion_missing"
        if (completion.finishReason != LlmFinishReason.END_TURN) {
            return "finish_reason=${completion.finishReason.name}"
        }
        if (completion.text.isBlank()) return "empty_text"
        return try {
            ActionGuideCandidateParser.parseAndValidate(completion.text, splitUnits(sourceText))
            "runner_invalid_without_parser_error"
        } catch (failure: InvalidInputException) {
            "parser=${failure.message?.takeIf(String::isNotBlank) ?: "invalid_input"}"
        }
    }

    private fun loadDocuments(plan: ActionGuideR2LanePlan.Ready): List<GoldenDocument> {
        val corpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory())
        val byId = corpus.documents.associateBy(GoldenDocument::id)
        val missing = plan.documentIds.filterNot(byId::containsKey)
        check(missing.isEmpty()) {
            "R2 표본 문서가 현재 골든 디렉터리에 없다: ${missing.joinToString(",")}" +
                " — 유료 호출 전에 기준선과 자료 경로를 맞춰라"
        }
        return plan.documentIds.map(byId::getValue)
    }

    private fun loadSavedBodies(documents: List<GoldenDocument>): Map<String, String> {
        val directory = GoldenDocumentLoader.conversionsDirectory().toPath()
        return documents.associate { document ->
            val file = directory.resolve("${document.id}.txt")
            check(Files.isRegularFile(file)) {
                "R2 문서 ${document.id}의 저장 본문 스냅샷이 없다: $file — 유료 호출 전에 중단한다"
            }
            document.id to Files.readString(file).trimEnd()
        }
    }

    private fun planTranscript(
        plan: ActionGuideR2LanePlan.Ready,
        documents: List<GoldenDocument>,
    ): LaneTranscript {
        val env: (String) -> String? = { key ->
            if (key == LaneTranscript.DIRECTORY_ENV) {
                System.getenv(ActionGuideR2Lane.TRANSCRIPT_ENV)
            } else {
                System.getenv(key)
            }
        }
        return when (
            val result = LaneTranscript.plan(env, documents.map(GoldenDocument::id), plan.runs)
        ) {
            is LaneTranscriptPlan.Ready -> result.transcript
            is LaneTranscriptPlan.Unusable -> fail<LaneTranscript>(result.reason)
        }
    }

    private fun journalIdOf(
        documentId: String,
        run: Int?,
    ): String = if (run == null) documentId else "$documentId#run$run"

    private fun job(): StoredActionGuideJob =
        StoredActionGuideJob(
            jobId = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            workspaceId = UUID.randomUUID(),
            documentId = UUID.randomUUID(),
            conversionId = UUID.randomUUID(),
            requestId = UUID.randomUUID(),
            expectedGuideRevision = null,
            basedOnContentRevision = 1,
            reservedCredits = java.math.BigDecimal.ONE,
            status = ActionGuideJobStatus.RUNNING,
            failureCode = null,
            executionId = UUID.randomUUID(),
            providerStartedAt = Instant.now(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    /** 응답 본문을 기억하지만 로그나 [ActionGuideR2LaneReport]에는 내보내지 않는다. */
    private class CapturingProvider(private val delegate: LlmProvider) : LlmProvider {
        var lastCompletion: LlmCompletion? = null
            private set

        override val name: String
            get() = delegate.name

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ): LlmCompletion = delegate.complete(prompt, options).also { lastCompletion = it }
    }
}
