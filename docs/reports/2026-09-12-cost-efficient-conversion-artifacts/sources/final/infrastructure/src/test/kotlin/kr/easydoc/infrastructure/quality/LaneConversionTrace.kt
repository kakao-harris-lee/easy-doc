package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConversionResult
import kr.easydoc.core.easyread.checkRepairStyle
import kr.easydoc.core.easyread.checkStyle
import kr.easydoc.core.easyread.findMissingFacts
import kr.easydoc.core.easyread.postprocess
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import tools.jackson.databind.json.JsonMapper

/** 기존 유료 레인의 변환 provider만 감싼다. judge·제품 로그에는 본문 기록을 추가하지 않는다. */
internal class LaneConversionTrace(
    private val delegate: LlmProvider,
    private val transcript: LaneTranscript,
) : LlmProvider {
    private val calls = mutableListOf<TraceCall>()
    override val name: String get() = delegate.name

    fun beginDocument() = calls.clear()

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        if (!transcript.enabled) return delegate.complete(prompt, options)
        return try {
            delegate.complete(prompt, options).also { calls += TraceCall(it, null) }
        } catch (exc: LlmProviderException) {
            calls += TraceCall(null, exc.javaClass.simpleName)
            throw exc
        }
    }

    fun save(
        documentId: String,
        source: String,
        result: ConversionResult,
        run: Int?,
    ) {
        if (!transcript.enabled) return
        val converted = result as? ConversionResult.Converted
        val record =
            mapOf(
                "converted" to (converted != null),
                "repairAdopted" to converted?.repaired,
                "failureKind" to (result as? ConversionResult.Failed)?.kind?.name,
                "finalText" to converted?.easyText?.value,
                "calls" to calls.mapIndexed { index, call -> call.details(index, source) },
            )
        transcript.savePasses(documentId, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record), run)
    }

    override fun toString(): String = "LaneConversionTrace(enabled=${transcript.enabled}, calls=${calls.size})"

    private class TraceCall(
        val completion: LlmCompletion?,
        val failureClass: String?,
    ) {
        fun details(
            index: Int,
            source: String,
        ): Map<String, Any?> {
            val body = completion?.text?.let(::postprocess)
            return mapOf(
                "purpose" to if (index == 0) "convert" else "repair",
                "rawText" to completion?.text,
                "text" to body,
                "finishReason" to completion?.finishReason?.name,
                "failureClass" to failureClass,
                "inputTokens" to completion?.inputTokens,
                "outputTokens" to completion?.outputTokens,
                "estimatedCostUsd" to completion?.estimatedCostUsd,
                "model" to completion?.model,
                "latencyMs" to completion?.latencyMs,
                "repairStyleIssues" to
                    body?.let { text ->
                        checkRepairStyle(source, text).issues.map {
                            mapOf("kind" to it.kind.name, "sentence" to it.sentence, "word" to it.word)
                        }
                    },
                "styleIssues" to
                    body?.let { text ->
                        checkStyle(text).issues.map {
                            mapOf("kind" to it.kind.name, "sentence" to it.sentence, "word" to it.word)
                        }
                    },
                "missingFacts" to
                    body?.let { text ->
                        findMissingFacts(source, text).map { mapOf("kind" to it.kind.name, "value" to it.value) }
                    },
            )
        }
    }

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
    }
}
