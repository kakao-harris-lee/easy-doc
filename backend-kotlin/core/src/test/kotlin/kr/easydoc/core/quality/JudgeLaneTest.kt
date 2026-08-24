package kr.easydoc.core.quality

import kr.easydoc.core.llm.FakeLlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** LLM-as-judge 는 비밀값이 있는 opt-in 레인이다. 기본 실행은 skip 경로만 고정한다. */
class JudgeLaneTest {
    @Test
    @DisplayName("비밀값이 없으면 judge 레인을 명시적으로 skip 한다")
    fun `비밀값이 없으면 skip 한다`() {
        assertThat(JudgeLane.decide(null)).isEqualTo(JudgeLaneDecision.SKIPPED_MISSING_SECRET)
        assertThat(JudgeLane.decide("")).isEqualTo(JudgeLaneDecision.SKIPPED_MISSING_SECRET)
        assertThat(JudgeLane.decide("   ")).isEqualTo(JudgeLaneDecision.SKIPPED_MISSING_SECRET)
    }

    @Test
    @DisplayName("비밀값이 있으면 레인을 연다 — 실제 호출은 대역으로만 확인한다")
    fun `비밀값이 있으면 레인을 연다`() {
        assertThat(JudgeLane.decide("not-a-real-key")).isEqualTo(JudgeLaneDecision.RUN)

        val document =
            GoldenDocument(
                id = "001",
                title = "안내",
                category = "복지 안내문",
                synthetic = true,
                sourceText = "만 65세에게 매월 25일에 지급합니다.",
                requiredFacts = listOf(RequiredFact("만 65세"), RequiredFact("매월 25일")),
            )
        val provider = FakeLlmProvider.replying("yes")
        val score = GoldenJudge(provider).score(document, "만 65세 어르신은 매월 25일에 받습니다.")

        assertThat(score.passed).isTrue()
        assertThat(provider.calls).hasSize(1)
        assertThat(
            provider.calls
                .single()
                .prompt
                .toString(),
        ).doesNotContain(document.sourceText)
        assertThat(score.toString()).doesNotContain(document.sourceText)
    }

    @Test
    @Tag("llm")
    @DisplayName("유료 LLM judge 는 비밀값이 있을 때만 연다 — 기본 Gradle 실행에서 제외하고 호출하지 않는다")
    fun `유료 레인은 비밀값이 없으면 skip 한다`() {
        val secret =
            listOf(System.getenv("OPENAI_API_KEY"), System.getenv("ANTHROPIC_API_KEY"))
                .firstOrNull { !it.isNullOrBlank() }
        val decision = JudgeLane.decide(secret)
        if (secret.isNullOrBlank()) {
            assertThat(decision).isEqualTo(JudgeLaneDecision.SKIPPED_MISSING_SECRET)
        } else {
            assertThat(decision).isEqualTo(JudgeLaneDecision.RUN)
        }
    }
}
