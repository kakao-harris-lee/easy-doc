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

/**
 * judge 한 건의 판정. 원문·변환문은 들지 않는다.
 *
 * [reason] 은 [passed] 가 `false` 일 때만 값을 가질 수 있다 — 판정문 둘째 줄(첫 비어있지
 * 않은 줄)을 최대 [GoldenJudge.MAX_REASON_LENGTH] 자로 자른 값이다. 사유 줄이 없으면 `null`.
 */
class JudgeScore(
    val documentId: String,
    val passed: Boolean,
    val reason: String? = null,
) {
    override fun toString(): String = "JudgeScore(id=$documentId, passed=$passed, reason=$reason)"
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
        val text = completion.text
        val passed = isYes(text)
        return JudgeScore(document.id, passed = passed, reason = if (passed) null else reasonLineOf(text))
    }

    private fun isYes(text: String): Boolean = text.trim().startsWith("yes", ignoreCase = true)

    /**
     * 판정 줄 다음의 첫 비어있지 않은 줄을 사유로 뽑는다. 본문을 되풀이하지 못하게 길이를 제한한다.
     *
     * [isYes] 처럼 먼저 `trim` 한 뒤 줄을 나눈다 — 그러지 않으면 답이 빈 줄로 시작할 때
     * (`"\nno\n..."`) `drop(1)` 이 그 빈 줄만 건너뛰어 판정 줄("no")이 사유로 잘못 잡힌다.
     */
    private fun reasonLineOf(text: String): String? =
        text
            .trim()
            .lines()
            .drop(1)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_REASON_LENGTH)

    companion object {
        /** 사유 한 줄의 최대 길이. 사실 하나를 적기엔 충분하고 본문을 되풀이하기엔 부족한 길이다. */
        internal const val MAX_REASON_LENGTH = 200
    }
}
