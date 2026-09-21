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
    fun `parse compacts only adjacent anchors and preserves exact source span`() {
        val sourceUnits = compactableSourceUnits()
        val anchors =
            listOf(0, 2, 3, 5, 7, 8, 10, 12, 14, 16, 18).map { index ->
                ActionGuideSourceAnchor(listOf(index), sourceUnits[index])
            }

        val candidate = ActionGuideCandidateParser.parseAndValidate(candidateJson(anchors), sourceUnits)
        val compacted =
            candidate.sections
                .first()
                .items
                .single()
                .sourceAnchors

        assertThat(compacted.map(ActionGuideSourceAnchor::sourceUnitIndexes))
            .containsExactly(
                listOf(0),
                listOf(2, 3),
                listOf(5),
                listOf(7, 8),
                listOf(10),
                listOf(12),
                listOf(14),
                listOf(16),
                listOf(18),
            )
        assertThat(compacted[1].quote).isEqualTo("첫 연결 근거\n둘째 연결 근거")
        assertThat(compacted[3].quote).isEqualTo("넷째 연결 근거\n다섯째 연결 근거")
        assertThat(compacted).hasSizeLessThanOrEqualTo(10)
    }

    @Test
    fun `parse keeps anchors separated by a source gap`() {
        val sourceUnits = compactableSourceUnits()
        val anchors =
            listOf(0, 2).map { index ->
                ActionGuideSourceAnchor(listOf(index), sourceUnits[index])
            }

        val candidate = ActionGuideCandidateParser.parseAndValidate(candidateJson(anchors), sourceUnits)

        assertThat(
            candidate.sections
                .first()
                .items
                .single()
                .sourceAnchors
                .map { it.sourceUnitIndexes },
        ).containsExactly(listOf(0), listOf(2))
    }

    @Test
    fun `invalid original anchor cannot be laundered by compaction`() {
        val sourceUnits = compactableSourceUnits()
        val anchors =
            listOf(0, 2, 3, 5, 7, 8, 10, 12, 14, 16, 18)
                .map { index ->
                    ActionGuideSourceAnchor(listOf(index), sourceUnits[index])
                }.toMutableList()
        anchors[2] = anchors[2].copy(quote = "조작된 근거")

        assertThatThrownBy {
            ActionGuideCandidateParser.parseAndValidate(candidateJson(anchors), sourceUnits)
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `already valid adjacent anchors remain unchanged`() {
        val units = compactableSourceUnits()
        val anchors = listOf(0, 2, 3).map { ActionGuideSourceAnchor(listOf(it), units[it]) }
        val result = ActionGuideCandidateParser.parseAndValidate(candidateJson(anchors), units)
        assertThat(
            result.sections
                .first()
                .items
                .single()
                .sourceAnchors,
        ).containsExactlyElementsOf(anchors)
    }

    @Test
    fun `overflow with no safe adjacency remains invalid`() {
        val units = List(21) { "일반 안내 근거" }.toMutableList()
        units[0] = sourceUnits[0]
        val anchors = (0..20 step 2).map { ActionGuideSourceAnchor(listOf(it), units[it]) }
        assertThatThrownBy {
            ActionGuideCandidateParser.parseAndValidate(candidateJson(anchors), units)
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `adjacent quotes cannot be combined beyond the existing quote cap`() {
        val units = List(20) { "가".repeat(600) }.toMutableList()
        units[0] = sourceUnits[0]
        val anchors =
            listOf(0, 2, 3, 5, 7, 9, 11, 13, 15, 17, 19)
                .map { ActionGuideSourceAnchor(listOf(it), units[it]) }
        assertThatThrownBy {
            ActionGuideCandidateParser.parseAndValidate(candidateJson(anchors), units)
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

    private fun candidateJson(anchors: List<ActionGuideSourceAnchor>): String {
        val encodedAnchors =
            anchors.joinToString(",") { anchor ->
                val indexes = anchor.sourceUnitIndexes.joinToString(",")
                "{\"source_unit_indexes\":[$indexes],\"quote\":\"${anchor.quote}\"}"
            }
        return """{"schema_version":1,"sections":[
          {"kind":"eligibility","status":"available","items":[
            {"text":"신청 대상은 19세 이상입니다.","cautions":[],"source_anchors":[$encodedAnchors]}]},
          {"kind":"benefits","status":"not_in_source","items":[]},
          {"kind":"documents","status":"not_in_source","items":[]},
          {"kind":"steps","status":"not_in_source","items":[]},
          {"kind":"exceptions","status":"not_in_source","items":[]},
          {"kind":"contact","status":"not_in_source","items":[]}
        ]}"""
    }

    private fun compactableSourceUnits(): List<String> =
        listOf(
            "신청 대상은 19세 이상입니다.",
            "연결되지 않은 근거",
            "첫 연결 근거",
            "둘째 연결 근거",
            "연결되지 않은 중간 근거",
            "셋째 연결 근거",
            "연결되지 않은 중간 근거",
            "넷째 연결 근거",
            "다섯째 연결 근거",
            "연결되지 않은 중간 근거",
            "여섯째 근거",
            "연결되지 않은 중간 근거",
            "일곱째 근거",
            "연결되지 않은 중간 근거",
            "여덟째 근거",
            "연결되지 않은 중간 근거",
            "아홉째 근거",
            "연결되지 않은 중간 근거",
            "열째 근거",
        )
}
