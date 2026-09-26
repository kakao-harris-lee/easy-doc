package kr.easydoc.core.actionguide

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GuideAnalysisPolicyTest {
    private val source = listOf("사업 소개", "7일 전에 문의하세요")
    private val anchor = ActionGuideSourceAnchor(listOf(1), source[1])
    private val missing = GuideInformation(GuideInformationStatus.NOT_IN_SOURCE, null, emptyList())
    private val action =
        ExtractedGuideAction(
            "inquiry",
            GuideInformation(
                GuideInformationStatus.PRESENT,
                source[1],
                listOf(anchor),
            ),
            missing,
            missing,
            emptyList(),
            missing,
            missing,
            missing,
            emptyList(),
            emptyList(),
        )
    private val result =
        GuideAnalysisResult(
            GuideSuitability.GUIDE,
            GuideActionPresence.FOUND,
            "문의 안내",
            listOf(anchor),
            listOf(action),
            listOf(
                GuideUnitAssessment(0, GuideCoverageStatus.CONTEXT, emptyList()),
                GuideUnitAssessment(1, GuideCoverageStatus.ACTION, listOf("inquiry")),
            ),
            emptyList(),
            true,
        )

    @Test
    fun `one grounded action is sufficient and beneficiaries are not the actor`() {
        GuideAnalysisPolicy.validate(result, source)
        assertThat(GuideAnalysisPolicy.allowedModes(result)).containsExactlyElementsOf(GuideOutputMode.entries)
        assertThat(action.actor.status).isEqualTo(GuideInformationStatus.NOT_IN_SOURCE)
    }

    @Test
    fun `dropping or duplicating a source unit is rejected`() {
        listOf(result.coverage.take(1), result.coverage + result.coverage[0]).forEach { coverage ->
            assertThatThrownBy { GuideAnalysisPolicy.validate(result.copy(coverage = coverage), source) }
                .isInstanceOf(InvalidInputException::class.java)
        }
    }

    @Test
    fun `complete coverage does not enable modes with unreviewed extraction or false absence signal`() {
        assertThat(GuideAnalysisPolicy.allowedModes(result.copy(extractionReviewComplete = false))).isEmpty()
        val signal =
            result.copy(
                unresolvedSignals = listOf("possible_missed_action", "false_not_in_source"),
                extractionReviewComplete = false,
            )
        GuideAnalysisPolicy.validate(signal, source)
        assertThat(GuideAnalysisPolicy.allowedModes(signal)).isEmpty()
        assertThatThrownBy { GuideAnalysisPolicy.validate(signal.copy(extractionReviewComplete = true), source) }
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `non guide uncertain and actionless guide cannot offer generation modes`() {
        listOf(GuideSuitability.NON_GUIDE, GuideSuitability.UNCERTAIN).forEach {
            assertThat(GuideAnalysisPolicy.allowedModes(result.copy(suitability = it))).isEmpty()
        }
        assertThat(
            GuideAnalysisPolicy.allowedModes(
                result.copy(
                    actionPresence = GuideActionPresence.NONE,
                    actions = emptyList(),
                ),
            ),
        ).isEmpty()
        assertThat(GuideAnalysisPolicy.allowedModes(result.copy(suitability = GuideSuitability.MIXED))).hasSize(2)
    }

    @Test
    fun `fabricated anchor unsupported order and false absence payload are rejected`() {
        val invalid =
            listOf(
                action.copy(
                    instruction =
                        action.instruction.copy(
                            evidence = listOf(ActionGuideSourceAnchor(listOf(1), "")),
                        ),
                ),
                action.copy(
                    instruction =
                        action.instruction.copy(
                            evidence = listOf(ActionGuideSourceAnchor(listOf(1), "방문하세요")),
                        ),
                ),
                action.copy(afterActionIds = listOf("invented")),
                action.copy(contact = missing.copy(text = "123")),
            )
        invalid.forEach {
            assertThatThrownBy { GuideAnalysisPolicy.validate(result.copy(actions = listOf(it)), source) }
                .isInstanceOf(InvalidInputException::class.java)
        }
    }
}
