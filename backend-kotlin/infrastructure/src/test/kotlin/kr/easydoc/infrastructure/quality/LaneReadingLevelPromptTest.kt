package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.core.document.ReadingLevel
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.quality.GoldenDocument
import kr.easydoc.core.quality.GoldenJudge
import kr.easydoc.core.quality.RequiredFact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 노브가 고른 수준이 **변환 프롬프트와 judge 프롬프트 둘 다**에 닿는지 대역으로 고정한다.
 * 한쪽만 바뀌면 원가 측정이 「3~4학년으로 변환한 글을 5~6학년 기준으로 채점한」 값이 된다.
 */
class LaneReadingLevelPromptTest {
    @Test
    @DisplayName("grade_3_4 노브는 변환 프롬프트의 역할 문구를 초등 3~4학년으로 바꾼다")
    fun `변환 프롬프트가 더 쉬운 단계 역할을 쓴다`() {
        val provider = FakeLlmProvider.replying(REPLY, REPLY)

        ConvertDocumentUseCase(provider).convert(SOURCE, readingLevel = level("grade_3_4"))

        val system = firstSystemPrompt(provider)
        assertThat(system).contains("초등학교 3~4학년 수준의 어휘와 문장으로 다시 쓰는 편집자")
        assertThat(system).doesNotContain("초등학교 5~6학년 수준의 어휘와 문장으로 다시 쓰는 편집자")
    }

    @Test
    @DisplayName("노브가 없는 실행의 변환 프롬프트는 기존 역할 문구를 그대로 쓴다")
    fun `미설정은 기존 변환 역할을 쓴다`() {
        val provider = FakeLlmProvider.replying(REPLY, REPLY)

        ConvertDocumentUseCase(provider).convert(SOURCE, readingLevel = LaneReadingLevel.from { null })

        assertThat(firstSystemPrompt(provider))
            .contains("초등학교 5~6학년 수준의 어휘와 문장으로 다시 쓰는 편집자")
    }

    @Test
    @DisplayName("grade_3_4 노브는 judge 가 묻는 기준 학년도 함께 바꾼다")
    fun `judge 프롬프트가 초등 3~4학년을 묻는다`() {
        val provider = FakeLlmProvider.replying("yes")

        GoldenJudge(provider, readingLevel = level("grade_3_4")).score(DOCUMENT, "만 65세 어르신은 매월 25일에 받습니다.")

        val system = firstSystemPrompt(provider)
        assertThat(system).contains("초등학교 3~4학년")
        assertThat(system).doesNotContain("초등학교 5~6학년")
    }

    private fun level(wireName: String): ReadingLevel =
        LaneReadingLevel.from { name -> wireName.takeIf { name == LaneReadingLevel.ENV } }

    /** 첫 호출의 시스템 프롬프트 — 변환은 1차 호출, judge 는 유일한 호출이다. 본문은 user 쪽이다. */
    private fun firstSystemPrompt(provider: FakeLlmProvider): String =
        provider.calls
            .first()
            .prompt.system

    private companion object {
        const val SOURCE: String = "만 65세 이상 어르신에게 매월 25일에 기초연금을 지급합니다."
        const val REPLY: String = "만 65세가 넘은 어르신은 매월 25일에 기초연금을 받습니다."

        val DOCUMENT: GoldenDocument =
            GoldenDocument(
                id = "001",
                title = "안내",
                category = "복지 안내문",
                synthetic = true,
                sourceText = SOURCE,
                requiredFacts = listOf(RequiredFact("만 65세"), RequiredFact("매월 25일")),
            )
    }
}
