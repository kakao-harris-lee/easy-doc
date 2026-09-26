package kr.easydoc.infrastructure.actionguide

import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.actionguide.ExtractedGuideAction
import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideInformation
import kr.easydoc.core.actionguide.GuideInformationStatus
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.llm.LlmPrompt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class GuideProviderAnalysisParserTest {
    private val source = listOf("작년 신청자, 보호자, 지원자, 대리인이 대상입니다.", "방문 1주 전에 문의하세요.")
    private val absent = GuideInformation(GuideInformationStatus.NOT_IN_SOURCE, null, emptyList())
    private val instruction =
        GuideInformation(GuideInformationStatus.PRESENT, "문의하세요.", listOf(ActionGuideSourceAnchor(listOf(1), "문의하세요.")))
    private val action =
        ExtractedGuideAction(
            "inquiry",
            instruction,
            absent,
            GuideInformation(
                GuideInformationStatus.PRESENT,
                "작년 신청자, 보호자, 지원자, 대리인",
                listOf(ActionGuideSourceAnchor(listOf(0), "작년 신청자, 보호자, 지원자, 대리인")),
            ),
            listOf(
                GuideInformation(
                    GuideInformationStatus.PRESENT,
                    "방문 1주 전",
                    listOf(ActionGuideSourceAnchor(listOf(1), "방문 1주 전")),
                ),
            ),
            absent,
            absent,
            absent,
            emptyList(),
            emptyList(),
        )
    private val result =
        GuideAnalysisResult(
            GuideSuitability.GUIDE,
            GuideActionPresence.FOUND,
            "문의 안내",
            emptyList(),
            listOf(action),
            listOf(
                GuideUnitAssessment(0, GuideCoverageStatus.ACTION, listOf("inquiry")),
                GuideUnitAssessment(1, GuideCoverageStatus.ACTION, listOf("inquiry")),
            ),
            emptyList(),
            true,
        )
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `model cannot certify review and inquiry keeps all recipients past context and advance condition`() {
        val parsed = GuideProviderAnalysisParser.parse(mapper.writeValueAsString(result), source)
        assertThat(parsed.extractionReviewComplete).isFalse()
        assertThat(parsed.actions).hasSize(1)
        assertThat(
            parsed.actions
                .single()
                .beneficiaries.text,
        ).contains("작년 신청자", "보호자", "지원자", "대리인")
        assertThat(
            parsed.actions
                .single()
                .conditions
                .single()
                .text,
        ).isEqualTo("방문 1주 전")
        assertThat(
            parsed.actions
                .single()
                .deadline.status,
        ).isEqualTo(GuideInformationStatus.NOT_IN_SOURCE)
        val prompt = LlmPrompt.forActionGuideAnalysis(source.joinToString("\n"), "담당자가 편집한 본문", "grade_3_4")
        assertThat(prompt.user).contains(source[0], source[1], "담당자가 편집한 본문")
        assertThat(prompt.system).contains("grade_3_4")
    }

    @Test
    fun `provider cannot shorten original denominator or map action to unrelated unit`() {
        assertThatThrownBy {
            GuideProviderAnalysisParser.parse(
                mapper.writeValueAsString(result.copy(coverage = result.coverage.take(1))),
                source,
            )
        }.isInstanceOf(InvalidInputException::class.java)
        val unmapped = result.copy(actions = listOf(action.copy(beneficiaries = absent)))
        assertThatThrownBy {
            GuideProviderAnalysisParser.parse(mapper.writeValueAsString(unmapped), source)
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `grounded order evidence does not permit cyclic prerequisites`() {
        val first = action.copy(afterActionIds = listOf("second"), orderEvidence = instruction.evidence)
        val second =
            action.copy(
                id = "second",
                afterActionIds = listOf("inquiry"),
                orderEvidence = instruction.evidence,
            )
        val cyclic =
            result.copy(
                actions = listOf(first, second),
                coverage =
                    result.coverage.map {
                        it.copy(actionIds = listOf("inquiry", "second"))
                    },
            )
        assertThatThrownBy {
            GuideProviderAnalysisParser.parse(mapper.writeValueAsString(cyclic), source)
        }.isInstanceOf(InvalidInputException::class.java)
    }
}
