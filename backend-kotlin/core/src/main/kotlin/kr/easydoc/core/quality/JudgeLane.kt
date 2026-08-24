package kr.easydoc.core.quality

import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider

/** LLM-as-judge 레인의 실행 여부. 비밀값이 없으면 호출하지 않고 skip 한다. */
enum class JudgeLaneDecision {
    /** 비밀값이 있어 judge 를 돌릴 수 있다. */
    RUN,

    /** 비밀값이 없어 이 레인을 건너뛴다. */
    SKIPPED_MISSING_SECRET,
}

/** opt-in judge 레인의 입장 판정. */
object JudgeLane {
    fun decide(secret: String?): JudgeLaneDecision =
        if (secret.isNullOrBlank()) JudgeLaneDecision.SKIPPED_MISSING_SECRET else JudgeLaneDecision.RUN
}

/** judge 한 건의 판정. 원문·변환문은 들지 않는다. */
class JudgeScore(
    val documentId: String,
    val passed: Boolean,
) {
    override fun toString(): String = "JudgeScore(id=$documentId, passed=$passed)"
}

/**
 * 변환문이 필수 사실을 지켰는지 LLM 에 묻는다.
 * 호출 여부는 [JudgeLane] 이 정한다 — 이 클래스를 비밀값 없이 만들지 마라.
 */
class GoldenJudge(private val provider: LlmProvider) {
    fun score(
        document: GoldenDocument,
        converted: String,
    ): JudgeScore {
        val completion = provider.complete(LlmPrompt.forJudge(document.sourceText, converted, document.requiredFacts))
        return JudgeScore(document.id, passed = isYes(completion.text))
    }

    private fun isYes(text: String): Boolean = text.trim().startsWith("yes", ignoreCase = true)
}
