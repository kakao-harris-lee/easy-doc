package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.readText

class LaneConversionTraceTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun `보정 전후와 실제 기각 결정을 기록하고 judge는 제외한다`(
        @TempDir temp: Path,
    ) {
        val transcript = transcript(temp)
        val draft = "신청 기간은 2026년 9월 12일까지입니다. 신청 안내문이 보여지고 있습니다."
        val provider = replies(draft, "신청할 수 있어요.", "yes")
        val trace = LaneConversionTrace(provider, transcript)
        val source = "2026년 9월 12일까지 신청합니다."
        trace.beginDocument()
        val result = ConvertDocumentUseCase(trace).convert(source)
        trace.save("001", source, result, 2)
        provider.complete(LlmPrompt.forConversion("judge는 별도 provider"))

        val saved = json.readTree(temp.resolve("passes/001-run2.json").readText())
        assertThat(saved.path("calls").size()).isEqualTo(2)
        assertThat(saved.at("/calls/0/text").stringValue("")).isEqualTo(draft)
        assertThat(saved.at("/calls/0/userPrompt").stringValue("")).contains(source)
        assertThat(saved.at("/calls/0/systemPrompt").stringValue("")).contains("초등학교 5~6학년")
        assertThat(saved.at("/calls/1/userPrompt").stringValue("")).contains(source, draft)
        assertThat(saved.at("/calls/0/styleIssues").size()).isGreaterThan(0)
        assertThat(saved.at("/calls/1/missingFacts").size()).isGreaterThan(0)
        assertThat(saved.path("repairAdopted").asBoolean()).isFalse()
        assertThat(saved.path("finalText").stringValue("")).isEqualTo(draft)
        assertThat(trace.toString()).doesNotContain(draft)
    }

    @Test
    fun `보정 없는 결과와 절단 실패를 구분하며 문서 사이에 기록을 비운다`(
        @TempDir temp: Path,
    ) {
        val transcript = transcript(temp)
        var count = 0
        val provider =
            object : LlmProvider {
                override val name = "fake"

                override fun complete(
                    prompt: LlmPrompt,
                    options: LlmOptions,
                ): LlmCompletion =
                    completion("신청할 수 있어요.", if (count++ == 0) LlmFinishReason.END_TURN else LlmFinishReason.MAX_TOKENS)
            }
        val trace = LaneConversionTrace(provider, transcript)
        val converter = ConvertDocumentUseCase(trace)
        for (id in listOf("001", "002")) {
            trace.beginDocument()
            trace.save(id, "신청하세요.", converter.convert("신청하세요."), null)
        }
        val first = json.readTree(temp.resolve("passes/001.json").readText())
        val second = json.readTree(temp.resolve("passes/002.json").readText())
        assertThat(first.path("calls").size()).isEqualTo(1)
        assertThat(first.path("converted").asBoolean()).isTrue()
        assertThat(first.path("repairAdopted").asBoolean()).isFalse()
        assertThat(second.path("calls").size()).isEqualTo(1)
        assertThat(second.path("converted").asBoolean()).isFalse()
        assertThat(second.path("failureKind").stringValue("")).isEqualTo("TRUNCATED")
    }

    @Test
    fun `기록을 끄면 결과와 호출 수를 바꾸지 않는다`() {
        val transcript = (LaneTranscript.plan({ null }, emptyList()) as LaneTranscriptPlan.Ready).transcript
        val trace = LaneConversionTrace(replies("신청할 수 있어요."), transcript)
        trace.beginDocument()
        val result = ConvertDocumentUseCase(trace).convert("신청하세요.")
        trace.save("../unsafe", "신청하세요.", result, null)
        assertThat(result.usage.llmCalls).isEqualTo(1)
    }

    private fun transcript(temp: Path): LaneTranscript =
        (LaneTranscript.plan({ temp.toString() }, listOf("001", "002")) as LaneTranscriptPlan.Ready).transcript

    private fun replies(vararg texts: String): LlmProvider =
        object : LlmProvider {
            private var index = 0
            override val name = "fake"

            override fun complete(
                prompt: LlmPrompt,
                options: LlmOptions,
            ): LlmCompletion = completion(texts[index++])
        }

    private fun completion(
        text: String,
        finishReason: LlmFinishReason = LlmFinishReason.END_TURN,
    ): LlmCompletion =
        LlmCompletion(
            text = text,
            provider = "fake",
            model = "fake",
            inputTokens = 1,
            outputTokens = 1,
            finishReason = finishReason,
        )
}
