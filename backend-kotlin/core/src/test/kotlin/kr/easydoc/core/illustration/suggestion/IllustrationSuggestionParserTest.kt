package kr.easydoc.core.illustration.suggestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * R7 ER-17 그림 제안 파서·검증기(`docs/plans/2026-09-24-r7-er17-suggestion-spec.md` §4).
 * 구조 위반은 결과 전체 무효, 의미 위반은 제안 단위 탈락이라는 두 층을 각각 잰다.
 */
class IllustrationSuggestionParserTest {
    private val sourceUnits =
        listOf(
            "신청하려면 먼저 신청서와 신분증을 준비합니다.",
            "준비한 서류는 주민센터에 제출합니다.",
            "심사가 끝나면 결과를 문자로 알려 드립니다.",
        )

    /** 프롬프트가 줄 번호를 붙이는 기준과 같은 `splitUnits(savedBody)` 결과를 흉내 낸다. */
    private val savedBodyUnits =
        listOf(
            "먼저 신청서와 신분증을 준비해요.",
            "준비한 서류는 주민센터에 내요.",
            "심사가 끝나면 결과를 문자로 알려 줘요.",
        )

    private val anchors =
        "\"source_anchors\":[{\"source_unit_indexes\":[0],\"quote\":\"신청서와 신분증을 준비합니다\"}," +
            "{\"source_unit_indexes\":[1],\"quote\":\"주민센터에 제출합니다\"}," +
            "{\"source_unit_indexes\":[2],\"quote\":\"결과를 문자로 알려 드립니다\"}]"
    private val scenes =
        "\"scenes\":[\"신청서와 신분증을 준비하는 모습\",\"서류를 주민센터에 제출하는 모습\"," +
            "\"결과를 문자로 알려 주는 모습\"]"
    private val preservedFacts = "\"preserved_facts\":[\"서류를 준비한 뒤에 주민센터에 제출합니다\"]"
    private val reason = "신청 순서를 그림으로 보면 이해하기 쉽습니다."
    private val altTextDraft = "신청서 준비, 주민센터 제출, 결과 통보 순서를 보여 주는 그림"
    private val bodyRange = "\"body_range\":{\"start\":0,\"end\":2}"

    private val procedureSuggestion =
        """{"purpose":"procedure","reason":"$reason",$bodyRange,$anchors,$scenes,$preservedFacts,
           "alt_text_draft":"$altTextDraft"}"""

    /** 원문에 없는 기간(14일)을 장면에 더한 제안 — 앵커 근거로 확인할 수 없는 사실이다. */
    private val factInventingSuggestion =
        procedureSuggestion.replace("결과를 문자로 알려 주는 모습", "결과를 14일 안에 문자로 알려 주는 모습")

    private val validJson = jsonWith(procedureSuggestion)

    @Test
    fun `절차 문맥 제안 하나가 원문 근거와 함께 통과한다`() {
        val result = analyze(validJson, FixedSuggestionIds(FIRST_ID))

        val set = validSetOf(result)
        assertThat(set.schemaVersion).isEqualTo(1)
        assertThat(set.analysisVersion).isEqualTo(ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION)
        assertThat(set.droppedCount).isZero()
        val suggestion = set.suggestions.single()
        assertThat(suggestion.suggestionId).isEqualTo(FIRST_ID)
        assertThat(suggestion.purpose).isEqualTo(IllustrationSuggestionPurpose.PROCEDURE)
        assertThat(suggestion.bodyRange).isEqualTo(IllustrationSuggestionBodyRange(0, 2))
        assertThat(suggestion.sourceAnchors.map { it.sourceUnitIndexes })
            .containsExactly(listOf(0), listOf(1), listOf(2))
        assertThat(suggestion.scenes).hasSize(3)
        assertThat(suggestion.preservedFacts).hasSize(1)
        assertThat(suggestion.altTextDraft).isEqualTo(altTextDraft)
    }

    @Test
    fun `식별자는 서버가 순서대로 부여하고 LLM 출력의 값을 쓰지 않는다`() {
        val two = jsonWith(procedureSuggestion, procedureSuggestion)

        val result = analyze(two, FixedSuggestionIds(FIRST_ID, SECOND_ID))

        assertThat(validSetOf(result).suggestions.map { it.suggestionId }).containsExactly(FIRST_ID, SECOND_ID)
    }

    @Test
    fun `원문에 없는 숫자를 넣은 제안은 버려지고 나머지는 남는다`() {
        val result = analyze(jsonWith(procedureSuggestion, factInventingSuggestion), FixedSuggestionIds(FIRST_ID))

        val set = validSetOf(result)
        assertThat(set.suggestions).hasSize(1)
        assertThat(set.suggestions.single().suggestionId).isEqualTo(FIRST_ID)
        assertThat(set.droppedCount).isEqualTo(1)
    }

    @Test
    @DisplayName("연락처만 있는 문서처럼 제안이 0건이면 '제안 없음'이지 실패가 아니다")
    fun `제안이 0건이면 정상 결과다`() {
        val set = validSetOf(analyze("""{"schema_version":1,"suggestions":[]}"""))

        assertThat(set.suggestions).isEmpty()
        assertThat(set.droppedCount).isZero()
    }

    @Test
    fun `낸 제안이 모두 탈락하면 결과 전체가 무효다`() {
        val result = analyze(jsonWith(factInventingSuggestion, factInventingSuggestion))

        assertThat(result).isEqualTo(IllustrationSuggestionAnalysis.AllDropped(2))
    }

    @Test
    fun `앵커가 원문과 맞지 않는 제안은 버려진다`() {
        val wrongQuote = procedureSuggestion.replace("주민센터에 제출합니다", "동사무소에 제출합니다")
        val wrongIndex = procedureSuggestion.replace("\"source_unit_indexes\":[1]", "\"source_unit_indexes\":[9]")

        assertThat(analyze(jsonWith(wrongQuote))).isEqualTo(IllustrationSuggestionAnalysis.AllDropped(1))
        assertThat(analyze(jsonWith(wrongIndex))).isEqualTo(IllustrationSuggestionAnalysis.AllDropped(1))
    }

    @Test
    @DisplayName("서버가 붙이는 값(suggestion_id·dropped_count·analysis_version)을 LLM 이 내면 무효다")
    fun `서버가 부여하는 필드를 출력에 넣으면 결과 전체가 무효다`() {
        val withServerId = "\"suggestion_id\":\"$FIRST_ID\",\"purpose\":\"procedure\""
        invalid(validJson.replace("\"purpose\":\"procedure\"", withServerId))
        invalid(validJson.replace("\"schema_version\":1", "\"schema_version\":1,\"dropped_count\":0"))
        invalid(validJson.replace("\"schema_version\":1", "\"schema_version\":1,\"analysis_version\":\"직접 지정\""))
    }

    @Test
    fun `모르는 필드와 지원하지 않는 스키마 버전은 결과 전체를 무효로 만든다`() {
        invalid(validJson.replace("\"schema_version\":1", "\"schema_version\":2"))
        invalid(validJson.replace("\"purpose\":\"procedure\"", "\"purpose\":\"procedure\",\"html\":\"<b>x</b>\""))
        invalid(validJson.replace("\"purpose\":\"procedure\"", "\"purpose\":\"decoration\""))
        invalid("JSON 이 아니다")
        invalid(validJson + " ".repeat(300_000))
    }

    @Test
    fun `제안 수와 길이 상한을 넘으면 결과 전체가 무효다`() {
        invalid(jsonWith(*Array(6) { procedureSuggestion }))
        invalid(validJson.replace(reason, "가".repeat(301)))
        invalid(validJson.replace(reason, ""))
        invalid(validJson.replace("신청서와 신분증을 준비하는 모습", "나".repeat(201)))
        invalid(validJson.replace("서류를 준비한 뒤에 주민센터에 제출합니다", "다".repeat(201)))
        invalid(validJson.replace(altTextDraft, "라".repeat(301)))
        invalid(validJson.replace(altTextDraft, ""))
    }

    @Test
    fun `장면과 보존 사실과 앵커의 개수 범위를 지킨다`() {
        invalid(validJson.replace(scenes, "\"scenes\":[]"))
        invalid(validJson.replace(scenes, "\"scenes\":[${List(7) { "\"장면\"" }.joinToString(",")}]"))
        invalid(
            validJson.replace(
                preservedFacts,
                "\"preserved_facts\":[${List(11) { "\"사실\"" }.joinToString(",")}]",
            ),
        )
        invalid(validJson.replace(anchors, "\"source_anchors\":[]"))
        invalid(validJson.replace("\"source_unit_indexes\":[0]", "\"source_unit_indexes\":[0,0]"))
        invalid(validJson.replace("\"source_unit_indexes\":[0]", "\"source_unit_indexes\":[]"))
    }

    @Test
    fun `본문 줄 범위는 저장 본문 줄 수 안이어야 한다`() {
        invalid(validJson.replace(bodyRange, "\"body_range\":{\"start\":0,\"end\":3}"))
        invalid(validJson.replace(bodyRange, "\"body_range\":{\"start\":-1,\"end\":2}"))
        invalid(validJson.replace(bodyRange, "\"body_range\":{\"start\":2,\"end\":1}"))
    }

    @Test
    @DisplayName("길이 상한은 코드 포인트로 센다 — 이모지가 두 글자로 세이지 않는다")
    fun `길이 상한은 코드 포인트로 잰다`() {
        val scene = "신청서와 신분증을 준비하는 모습"

        assertThat(validSetOf(analyze(validJson.replace(scene, "😀".repeat(200)))).suggestions).hasSize(1)
        invalid(validJson.replace(scene, "😀".repeat(201)))
    }

    @Test
    @DisplayName("toString 에 원문 인용·장면·대체텍스트가 실리지 않는다")
    fun `toString 은 본문을 가린다`() {
        val set = validSetOf(analyze(validJson, FixedSuggestionIds(FIRST_ID)))
        val suggestion = set.suggestions.single()

        val rendered = listOf(set, suggestion, suggestion.sourceAnchors.first()).joinToString(" ") { it.toString() }

        assertThat(rendered).doesNotContain(
            "신청서와 신분증을 준비합니다",
            "신청서와 신분증을 준비하는 모습",
            "서류를 준비한 뒤에 주민센터에 제출합니다",
            altTextDraft,
            reason,
        )
        assertThat(rendered).contains("anchorCount=3", "suggestionCount=1")
    }

    private fun jsonWith(vararg suggestions: String): String =
        """{"schema_version":1,"suggestions":[${suggestions.joinToString(",")}]}"""

    private fun analyze(
        rawJson: String,
        suggestionIds: IllustrationSuggestionIdGenerator = FixedSuggestionIds(FIRST_ID, SECOND_ID),
    ): IllustrationSuggestionAnalysis =
        IllustrationSuggestionParser.parseAndValidate(rawJson, sourceUnits, savedBodyUnits, suggestionIds)

    private fun validSetOf(result: IllustrationSuggestionAnalysis): IllustrationSuggestionSet {
        assertThat(result).isInstanceOf(IllustrationSuggestionAnalysis.Valid::class.java)
        return (result as IllustrationSuggestionAnalysis.Valid).suggestions
    }

    private fun invalid(rawJson: String) {
        assertThat(analyze(rawJson)).isEqualTo(IllustrationSuggestionAnalysis.InvalidStructure)
    }

    /** 순서가 정해진 식별자만 내는 테스트 대역 — 제안마다 새 값을 요구하는 계약을 드러낸다. */
    private class FixedSuggestionIds(private vararg val ids: UUID) : IllustrationSuggestionIdGenerator {
        private var issued = 0

        override fun next(): UUID = ids[issued++]
    }

    private companion object {
        val FIRST_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SECOND_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
