package kr.easydoc.core.actionguide

import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ActionGuideCandidateTest {
    private val sourceUnits = listOf("신청 대상은 19세 이상입니다.", "신청은 9월 1일까지 주민센터에서 합니다.")
    private val eligibilityText = "\"text\":\"신청 대상은 19세 이상입니다.\""
    private val validJson =
        """{"schema_version":1,"sections":[
          {"kind":"eligibility","status":"available","items":[
            {"text":"신청 대상은 19세 이상입니다.","cautions":[],"source_anchors":[
              {"source_unit_indexes":[0],"quote":"신청 대상은 19세 이상입니다."}]}]},
          {"kind":"benefits","status":"not_in_source","items":[]},
          {"kind":"documents","status":"not_in_source","items":[]},
          {"kind":"steps","status":"not_in_source","items":[]},
          {"kind":"exceptions","status":"not_in_source","items":[]},
          {"kind":"contact","status":"not_in_source","items":[]}
        ]}"""

    @Test
    fun `six fixed sections with verified quote parse`() {
        val candidate = ActionGuideCandidateParser.parseAndValidate(validJson, sourceUnits)

        assertThat(candidate.schemaVersion).isEqualTo(1)
        assertThat(candidate.sections.map { it.kind.wireName })
            .containsExactly("eligibility", "benefits", "documents", "steps", "exceptions", "contact")
        assertThat(
            candidate.sections
                .first()
                .items
                .single()
                .sourceAnchors
                .single()
                .sourceUnitIndexes,
        ).containsExactly(0)
        ActionGuideCandidateValidator.validate(candidate, sourceUnits)
        assertThat(ActionGuideCandidateParser.decode(ActionGuideCandidateParser.encode(candidate))).isEqualTo(candidate)
    }

    @Test
    fun `unknown and duplicate sections are rejected`() {
        invalid(validJson.replace("\"contact\"", "\"other\""))
        invalid(validJson.replace("\"contact\"", "\"eligibility\""))
    }

    @Test
    fun `unknown fields and unsupported schema version are rejected`() {
        invalid(validJson.replace("\"schema_version\":1", "\"schema_version\":2"))
        invalid(validJson.replace(eligibilityText, "$eligibilityText,\"html\":\"<b>unsafe</b>\""))
    }

    @Test
    fun `anchor index and exact quote must match source`() {
        invalid(validJson.replace("\"source_unit_indexes\":[0]", "\"source_unit_indexes\":[2]"))
        invalid(validJson.replace("\"quote\":\"신청 대상은 19세 이상입니다.\"", "\"quote\":\"다른 사실\""))
        invalid(validJson.replace("\"source_unit_indexes\":[0]", "\"source_unit_indexes\":[0,0]"))
    }

    @Test
    fun `available needs anchor and not in source has no items`() {
        val parsed = ActionGuideCandidateParser.decode(validJson)
        val first = parsed.sections.first()
        val unanchored = first.copy(items = listOf(first.items.single().copy(sourceAnchors = emptyList())))
        assertThatThrownBy {
            ActionGuideCandidateValidator.validateStructure(
                parsed.copy(sections = listOf(unanchored) + parsed.sections.drop(1)),
            )
        }.isInstanceOf(InvalidInputException::class.java)
        val unavailable = "\"kind\":\"benefits\",\"status\":\"not_in_source\",\"items\":[]"
        val replacement = "\"items\":[{\"text\":\"혜택\",\"cautions\":[],\"source_anchors\":[]}]"
        val withItem = unavailable.replace("\"items\":[]", replacement)
        invalid(validJson.replace(unavailable, withItem))
        val reviewSection = unanchored.copy(status = ActionGuideSectionStatus.NEEDS_REVIEW)
        val reviewCandidate = parsed.copy(sections = listOf(reviewSection) + parsed.sections.drop(1))
        val review = ActionGuideCandidateParser.encode(reviewCandidate)
        assertThat(
            ActionGuideCandidateParser
                .parseAndValidate(review, sourceUnits)
                .sections
                .first()
                .status,
        ).isEqualTo(ActionGuideSectionStatus.NEEDS_REVIEW)
    }

    @Test
    fun `item and whole candidate code point limits reject without truncation`() {
        val longText = "😀".repeat(501)
        invalid(validJson.replace(eligibilityText, "\"text\":\"$longText\""))
        val many = "가".repeat(490)
        val oversizedItem = ActionGuideItem(many, emptyList(), emptyList())
        val oversizedSection =
            ActionGuideSection(
                ActionGuideSectionKind.ELIGIBILITY,
                ActionGuideSectionStatus.NEEDS_REVIEW,
                List(9) { oversizedItem },
            )
        val parsed = ActionGuideCandidateParser.decode(validJson)
        assertThatThrownBy {
            ActionGuideCandidateValidator.validateStructure(
                parsed.copy(
                    sections =
                        listOf(oversizedSection) + parsed.sections.drop(1),
                ),
            )
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `fabricated fact cannot be available`() {
        invalid(validJson.replace(eligibilityText, "\"text\":\"신청 대상은 20세 이상입니다.\""))
    }

    private fun invalid(json: String) {
        assertThatThrownBy { ActionGuideCandidateParser.parseAndValidate(json, sourceUnits) }
            .isInstanceOf(InvalidInputException::class.java)
    }
}
