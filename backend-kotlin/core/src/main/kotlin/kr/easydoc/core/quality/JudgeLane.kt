package kr.easydoc.core.quality

import kr.easydoc.core.document.ReadingLevel
import kr.easydoc.core.llm.LlmOptions
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
 * 변환문이 의미를 보존하며 목표 독해 수준에서 이해 가능한지 기존 평가 호출에서 함께 묻는다.
 * 호출 여부는 [JudgeLane] 이 정한다 — 이 클래스를 비밀값 없이 만들지 마라.
 *
 * [readingLevel] 은 **변환에 쓴 수준과 같아야 한다** — 다르면 「3~4학년으로 변환한 글을
 * 5~6학년 기준으로 채점한」 판정이 된다. 기본값이 제품 기본 수준이라 수준을 고르지 않는
 * 기존 호출은 그대로 동작한다.
 */
class GoldenJudge(
    private val provider: LlmProvider,
    private val options: LlmOptions = LlmOptions(),
    private val readingLevel: ReadingLevel = ReadingLevel.GRADE_5_6,
) {
    fun score(
        document: GoldenDocument,
        converted: String,
    ): JudgeScore {
        val prompt =
            LlmPrompt.forJudge(document.sourceText, converted, document.requiredFacts, readingLevel)
        val completion = provider.complete(prompt, options)
        val text = completion.text
        val passed = !completion.truncated && isYes(text)
        return JudgeScore(document.id, passed = passed, reason = if (passed) null else reasonLineOf(text))
    }

    private fun isYes(text: String): Boolean = text.trim().equals("yes", ignoreCase = true)

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
