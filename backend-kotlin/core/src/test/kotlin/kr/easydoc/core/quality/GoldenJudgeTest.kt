package kr.easydoc.core.quality

import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [GoldenJudge] 가 판정문 둘째 줄을 실패 사유로 뽑아내는지 확인한다.
 * 사유가 있어야 유료 골든 런 뒤에 「왜」 no 인지 사람이 다시 손으로 재구성하지 않는다.
 */
class GoldenJudgeTest {
    @Test
    fun `judge 출력 상한을 전달하고 잘린 yes는 거절한다`() {
        val provider = FakeLlmProvider(listOf(FakeLlmTurn.Reply("yes", finishReason = LlmFinishReason.MAX_TOKENS)))
        val score = GoldenJudge(provider, LlmOptions(maxTokens = 1_024)).score(document, "표본")
        assertThat(
            provider.calls
                .single()
                .options.maxTokens,
        ).isEqualTo(1_024)
        assertThat(score.passed).isFalse()
    }

    @Test
    fun `yes 로 시작하는 설명이나 상반된 판정은 통과시키지 않는다`() {
        listOf("yesterday", "yes\n하지만 날짜가 바뀌었습니다", "yes, no").forEach { response ->
            val score = GoldenJudge(FakeLlmProvider.replying(response)).score(document, "표본")
            assertThat(score.passed).isFalse()
        }
    }

    private val document =
        GoldenDocument(
            id = "062",
            title = "안내",
            category = "복지 안내문",
            synthetic = true,
            sourceText = "2023년 표본조사 대상은 302명입니다.",
            requiredFacts = listOf(RequiredFact("2023년"), RequiredFact("302명")),
        )

    @Test
    @DisplayName("yes 면 통과이고 사유는 없다")
    fun `yes 는 통과`() {
        val judge = GoldenJudge(FakeLlmProvider.replying("yes"))

        val score = judge.score(document, "2023년 표본조사 대상은 302명입니다.")

        assertThat(score.passed).isTrue()
        assertThat(score.reason).isNull()
    }

    @Test
    @DisplayName("no 다음 줄이 사유로 남는다")
    fun `no 다음 줄을 사유로 남긴다`() {
        val judge = GoldenJudge(FakeLlmProvider.replying("no\n2023년이 빠졌다"))

        val score = judge.score(document, "표본조사 대상은 302명입니다.")

        assertThat(score.passed).isFalse()
        assertThat(score.reason).isEqualTo("2023년이 빠졌다")
    }

    @Test
    @DisplayName("no 만 있고 사유 줄이 없으면 사유는 null 이다")
    fun `사유 줄이 없으면 null`() {
        val judge = GoldenJudge(FakeLlmProvider.replying("No"))

        val score = judge.score(document, "대상은 302명입니다.")

        assertThat(score.passed).isFalse()
        assertThat(score.reason).isNull()
    }

    @Test
    @DisplayName("사유가 최대 길이를 넘으면 잘린다")
    fun `사유는 최대 길이로 잘린다`() {
        val longReason = "빠진 사실 ".repeat(60)
        val judge = GoldenJudge(FakeLlmProvider.replying("no\n$longReason"))

        val score = judge.score(document, "대상은 302명입니다.")

        assertThat(score.passed).isFalse()
        assertThat(score.reason).hasSize(GoldenJudge.MAX_REASON_LENGTH)
        assertThat(longReason).startsWith(score.reason!!)
    }

    @Test
    @DisplayName("판정 앞에 빈 줄이 있어도 사유는 no 줄 다음에서 찾는다")
    fun `판정문 앞 빈 줄을 무시하고 사유를 찾는다`() {
        val judge = GoldenJudge(FakeLlmProvider.replying("\nno\n2023년이 빠졌다"))

        val score = judge.score(document, "표본조사 대상은 302명입니다.")

        assertThat(score.passed).isFalse()
        assertThat(score.reason).isEqualTo("2023년이 빠졌다")
    }

    @Test
    @DisplayName("toString 에 원문·변환문이 실리지 않는다")
    fun `toString 은 사유만 담고 본문은 담지 않는다`() {
        val judge = GoldenJudge(FakeLlmProvider.replying("no\n2023년이 빠졌다"))

        val score = judge.score(document, "표본조사 대상은 302명입니다.")

        assertThat(score.toString()).contains("2023년이 빠졌다")
        assertThat(score.toString()).doesNotContain(document.sourceText)
    }
}
