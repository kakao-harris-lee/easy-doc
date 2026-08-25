package kr.easydoc.core.quality

import kr.easydoc.core.llm.FakeLlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 골든 코퍼스의 변환 결과에 스타일 규칙과 사실 평가를 적용한다. */
class GoldenCorpusEvaluationTest {
    @Test
    @DisplayName("승인된 골든 변환 전건을 스타일·사실로 채점한다")
    fun `골든 변환 결과를 채점한다`() {
        val corpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory())
        val conversions = GoldenDocumentLoader.loadConversions(GoldenDocumentLoader.conversionsDirectory())
        val evaluation = evaluateConvertedCorpus(corpus, conversions)
        val sample = corpus.documents.first()
        val converted = conversions.getValue(sample.id)

        assertThat(evaluation.missingConversionIds).isEmpty()
        assertThat(evaluation.reports).hasSize(corpus.documents.size)
        assertThat(evaluation.quality.factPassCount).isEqualTo(corpus.documents.size)
        assertThat(evaluation.toString()).doesNotContain(sample.sourceText)
        assertThat(evaluation.toString()).doesNotContain(converted)
        assertThat(evaluateStyle(sample.id, converted).toString()).doesNotContain(converted)
    }

    @Test
    @DisplayName("변환문이 원문에 있는 사실을 빠뜨리면 사실 평가가 실패한다")
    fun `변환문 사실 누락을 검출한다`() {
        val document =
            GoldenDocument(
                id = "001",
                title = "안내",
                category = "복지 안내문",
                synthetic = true,
                sourceText = "만 65세에게 매월 25일에 지급합니다.",
                requiredFacts = listOf(RequiredFact("만 65세"), RequiredFact("매월 25일")),
            )
        val corpus = GoldenCorpus(listOf(document), files = listOf("001.json"))

        val missing = evaluateConvertedCorpus(corpus, mapOf("001" to "지원 대상에게 돈을 드립니다."))
        val present =
            evaluateConvertedCorpus(
                corpus,
                mapOf("001" to "만 65세 어르신은 매월 25일에 받습니다."),
            )

        assertThat(
            missing.reports
                .single()
                .facts
                .passed,
        ).isFalse()
        assertThat(
            present.reports
                .single()
                .facts
                .passed,
        ).isTrue()
        assertThat(evaluateFacts(document.id, document.sourceText, document.requiredFacts).passed).isTrue()
    }

    @Test
    @DisplayName("변환 스냅샷이 없는 문서는 누락으로 남긴다")
    fun `변환 누락을 검출한다`() {
        val document =
            GoldenDocument(
                id = "001",
                title = "안내",
                category = "복지 안내문",
                synthetic = true,
                sourceText = "만 65세입니다.",
                requiredFacts = listOf(RequiredFact("만 65세")),
            )
        val corpus = GoldenCorpus(listOf(document), files = listOf("001.json"))

        val evaluation = evaluateConvertedCorpus(corpus, emptyMap())

        assertThat(evaluation.missingConversionIds).containsExactly("001")
        assertThat(evaluation.reports).isEmpty()
    }

    @Test
    @DisplayName("judge 를 넘기면 변환 결과마다 채점한다")
    fun `judge 가 변환 결과를 채점한다`() {
        val document =
            GoldenDocument(
                id = "001",
                title = "안내",
                category = "복지 안내문",
                synthetic = true,
                sourceText = "만 65세에게 매월 25일에 지급합니다.",
                requiredFacts = listOf(RequiredFact("만 65세"), RequiredFact("매월 25일")),
            )
        val converted = "만 65세 어르신은 매월 25일에 받습니다."
        val provider = FakeLlmProvider.replying("yes")
        val evaluation =
            evaluateConvertedCorpus(
                GoldenCorpus(listOf(document), files = listOf("001.json")),
                mapOf("001" to converted),
                judge = GoldenJudge(provider),
            )

        assertThat(
            evaluation.reports
                .single()
                .judge
                ?.passed,
        ).isTrue()
        assertThat(provider.calls).hasSize(1)
        assertThat(
            provider.calls
                .single()
                .prompt
                .toString(),
        ).doesNotContain(converted)
    }
}
