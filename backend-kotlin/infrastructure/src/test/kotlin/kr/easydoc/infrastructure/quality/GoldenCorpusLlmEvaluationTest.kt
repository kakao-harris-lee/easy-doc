package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionFailureKind
import kr.easydoc.application.conversion.ConversionResult
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.quality.GoldenDocument
import kr.easydoc.core.quality.GoldenDocumentLoader
import kr.easydoc.core.quality.GoldenJudge
import kr.easydoc.core.quality.JudgeScore
import kr.easydoc.core.quality.evaluateFacts
import kr.easydoc.core.quality.evaluateStyle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 유료 LLM 으로 골든 문서를 변환하고 스타일·사실·judge 로 채점한다.
 * `./gradlew testLlm` 으로만 열리며, 선택한 provider 의 비밀값이 없으면 skip 한다.
 *
 * **이 레인은 측정 도구다.** 그래서 세 가지가 결과에 함께 실린다: 무엇으로 쟀는지
 * ([GoldenLlmLane]), 실패가 모델 탓인지 인프라 탓인지([LaneReport]), 인프라가 얼마나 흔들렸는지
 * ([LaneJournal]). 셋 중 하나라도 없으면 통과율이 무엇을 뜻하는지 말할 수 없다.
 */
@Tag("llm")
class GoldenCorpusLlmEvaluationTest {
    @Test
    @DisplayName("비밀값이 있으면 제품과 같은 설정으로 골든 변환 결과를 채점한다")
    fun `골든 변환을 채점한다`() {
        val ready =
            when (val plan = GoldenLlmLane.plan(System::getenv)) {
                is LanePlan.Ready -> plan
                is LanePlan.Skipped -> abort<LanePlan.Ready>(plan.reason)
                is LanePlan.Unusable -> fail<LanePlan.Ready>(plan.reason)
            }

        // 사전 컨텍스트는 **문서 목록을 안 뒤** 정한다 — 몇 건에 실렸는지가 측정 조건의 일부라
        // 요약 문자열을 만들기 전에 알아야 한다.
        val documents = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory()).documents
        val dictionary =
            when (val plan = LaneDictionary.plan(System::getenv, documents.map(GoldenDocument::id))) {
                is LaneDictionaryPlan.Ready -> plan.dictionary
                is LaneDictionaryPlan.Unusable -> fail<LaneDictionary>(plan.reason)
            }

        val journal = LaneJournal()
        val report = LaneReport("${ready.description} · ${dictionary.description}", journal)
        // 제품이 조립한 provider 를 레인 계측이 감싼다. 변환도 judge 도 같은 계측을 지난다.
        val provider = LaneInstrumentedProvider(ready.provider, journal)
        val grader =
            LaneGrader(
                // defaultOptions 는 제품 조립(ConversionWorkerConfiguration)과 같은 값이다 —
                // ready.options 가 이미 easydoc.llm.max-output-tokens 해석을 실었다
                // (GoldenLlmLane.assemble KDoc). judge 는 채점용 별도 호출이라 대상이 아니다.
                converter = ConvertDocumentUseCase(provider, defaultOptions = ready.options),
                judge = GoldenJudge(provider),
                journal = journal,
                report = report,
                dictionary = dictionary,
            )

        // 문서를 **순차로** 돈다 — 이유는 LaneInstrumentedProvider KDoc 「호출 간격」 절에 있다.
        documents.forEach(grader::grade)

        // 통과한 실행에서도 측정 조건과 집계가 남아야 한다(`testLlm` 은 stdout 을 보여 준다).
        println(report.render())
        assertThat(report.failures())
            .withFailMessage { report.render() }
            .isEmpty()
    }
}

/**
 * 문서 한 건의 채점. 레인 재료를 한 곳에 묶어, 채점 단계마다 같은 넷을 인자로 끌고 다니지 않는다.
 */
private class LaneGrader(
    private val converter: ConvertDocumentUseCase,
    private val judge: GoldenJudge,
    private val journal: LaneJournal,
    private val report: LaneReport,
    private val dictionary: LaneDictionary,
) {
    fun grade(document: GoldenDocument) {
        journal.beginDocument(document.id)
        val startedAt = System.nanoTime()
        val result = converter.convert(document.sourceText, dictionaryContext = dictionary.contextFor(document.id))
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        when (result) {
            is ConversionResult.Converted -> {
                score(document, result, elapsed)
            }

            is ConversionResult.Failed -> {
                recordFailed(document, result, elapsed)
            }
        }
    }

    /** 변환 실패는 여기서 끝난다 — 채점할 본문이 없다. 실패의 **원인**은 저널에서 가져온다. */
    private fun recordFailed(
        document: GoldenDocument,
        result: ConversionResult.Failed,
        elapsed: Duration,
    ) {
        report.recordConversionFailure(
            documentId = document.id,
            kind = result.kind,
            fault = journal.lastFault(document.id),
            retries = journal.retriesFor(document.id),
        )
        report.recordDocument(
            LaneMeasurement(
                documentId = document.id,
                sourceChars = charCountOf(document.sourceText),
                // 채점할 본문도 스타일 판정도 없다. 0 이나 false 로 채우면 분포와 통과율이 거짓이 된다.
                convertedChars = null,
                outputTokens = result.usage.outputTokens,
                truncated = result.kind == ConversionFailureKind.TRUNCATED,
                truncatedCalls = journal.truncatedCallsFor(document.id),
                stylePassed = null,
            ),
            elapsed,
        )
    }

    private fun score(
        document: GoldenDocument,
        result: ConversionResult.Converted,
        elapsed: Duration,
    ) {
        val converted = result.easyText.value
        val style = evaluateStyle(document.id, converted)
        val facts = evaluateFacts(document.id, converted, document.requiredFacts)
        if (!facts.passed) {
            report.recordQualityFailure(document.id, "사실 누락 ${facts.missing.size}")
        }
        val judged = judgeOrRecord(document, converted)
        if (judged != null && !judged.passed) {
            report.recordQualityFailure(document.id, "judge 실패")
        }
        report.recordDocument(
            LaneMeasurement(
                documentId = document.id,
                // 계약 `char_count` 와 같은 기준으로 센다 — 코드 단위가 아니라 코드 포인트다.
                sourceChars = charCountOf(document.sourceText),
                convertedChars = charCountOf(converted),
                outputTokens = result.usage.outputTokens,
                // 변환은 성공했지만 보정 호출이 잘렸을 수 있다 — 그 갈래는 여기에만 남는다.
                truncated = false,
                truncatedCalls = journal.truncatedCallsFor(document.id),
                stylePassed = style.passed,
            ),
            elapsed,
        )

        // 본문이 채점 결과에도 요약에도 실리지 않는지 문서마다 확인한다(CLAUDE.md 관측 규칙).
        assertThat(style.toString()).doesNotContain(converted)
        assertThat(facts.toString()).doesNotContain(converted)
        assertThat(judged.toString()).doesNotContain(converted)
        assertThat(report.render()).doesNotContain(converted)
    }

    private fun judgeOrRecord(
        document: GoldenDocument,
        converted: String,
    ): JudgeScore? =
        try {
            journal.beginJudge(document.id)
            judge.score(document, converted)
        } catch (exc: LlmProviderException) {
            // GoldenJudge 는 예외를 잡지 않는다. 여기서 잡지 않으면 문서 한 건의 429 가 레인
            // 전체를 끝내고, 남은 문서는 통과도 실패도 아닌 채로 측정되지 않는다.
            report.recordJudgeFailure(document.id, LaneFaults.of(exc), journal.retriesFor(document.id))
            null
        }
}
