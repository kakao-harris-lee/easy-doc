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
    fun `낸 제안이 모두 탈락하면 빈 결과와 탈락 수를 저장한다`() {
        val result = analyze(jsonWith(factInventingSuggestion, factInventingSuggestion))

        assertThat(validSetOf(result).suggestions).isEmpty()
        assertThat(validSetOf(result).droppedCount).isEqualTo(2)
    }

    @Test
    fun `앵커가 원문과 맞지 않는 제안은 버려진다`() {
        val wrongQuote = procedureSuggestion.replace("주민센터에 제출합니다", "동사무소에 제출합니다")
        val wrongIndex = procedureSuggestion.replace("\"source_unit_indexes\":[1]", "\"source_unit_indexes\":[9]")

        assertThat(validSetOf(analyze(jsonWith(wrongQuote))).droppedCount).isEqualTo(1)
        assertThat(validSetOf(analyze(jsonWith(wrongIndex))).droppedCount).isEqualTo(1)
    }

    @Test
    fun `원문 앵커 줄의 숫자는 짧은 인용 밖에 있어도 근거로 인정한다`() {
        // 005-긴급복지지원.json 발췌. 저장 본문은 실제 변환이 아닌 합성 fixture다.
        val source = listOf("선지원 후처리 원칙에 따라 요청일부터 원칙적으로 48시간 이내에 지원을 개시합니다.")
        val draft = example("procedure", "요청일부터 원칙적으로", "48시간 이내에 지원", "48시간", "지원 순서")
        val set = validSetOf(IllustrationSuggestionParser.parseAndValidate(jsonWith(draft), source, listOf("합성 본문")))
        assertThat(set.suggestions).hasSize(1)
        assertThat(set.droppedCount).isZero()
    }

    @Test
    fun `근거에 없는 기간과 금액은 장면 보존 사실 대체텍스트 각각에서 탈락한다`() {
        val source = listOf("요청일부터 원칙적으로 48시간 이내에 지원을 개시합니다.")
        val invented =
            listOf(
                example("procedure", "지원을 개시합니다", "14일 이내 지원", "48시간", "지원 순서"),
                example("procedure", "지원을 개시합니다", "5만원 지급", "48시간", "지원 순서"),
                example("procedure", "지원을 개시합니다", "지원 순서", "14일 이내 지급", "지원 순서"),
                example("procedure", "지원을 개시합니다", "지원 순서", "5만원 지급", "지원 순서"),
                example("procedure", "지원을 개시합니다", "지원 순서", "48시간", "14일 이내 지급"),
                example("procedure", "지원을 개시합니다", "지원 순서", "48시간", "5만원 지급"),
            )
        invented.forEach { draft ->
            val set =
                validSetOf(IllustrationSuggestionParser.parseAndValidate(jsonWith(draft), source, listOf("합성 본문")))
            assertThat(set.suggestions).isEmpty()
            assertThat(set.droppedCount).isEqualTo(1)
        }
    }

    @Test
    fun `서수 두 번째는 허용하지만 근거 없는 창구 번호는 탈락한다`() {
        val source = listOf("신청자는 안내를 받은 뒤 창구를 방문합니다.")
        val ordinal = example("procedure", "창구를 방문합니다", "두 번째 창구를 방문", "안내를 받음", "두 번째 창구")
        val invented = example("procedure", "창구를 방문합니다", "3번 창구를 방문", "안내를 받음", "창구 방문")
        val set =
            validSetOf(
                IllustrationSuggestionParser.parseAndValidate(jsonWith(ordinal, invented), source, listOf("합성 본문")),
            )
        assertThat(set.suggestions).hasSize(1)
        assertThat(set.droppedCount).isEqualTo(1)
    }

    @Test
    fun `비대면과 방문 비교 및 소득별 지원 금액은 원문 줄에 근거한다`() {
        // 003-주민등록-사실조사.json 및 089-청년내일저축계좌.json 발췌. 저장 본문은 합성 fixture다.
        val source =
            listOf(
                "가. 비대면 조사: 정부24 앱에서 세대주 본인 인증을 마친 후 세대원 정보를 확인하고 이상 유무를 기재하시면 됩니다.",
                "나. 방문 조사: 비대면 조사에 참여하지 아니한 세대와 복지 확인이 필요한 세대는 조사원이 직접 방문합니다.",
                "중위소득 50%초과~100%이하 : 10만원 정액 매칭",
                "중위소득 50%이하 : 30만원 정액 매칭(3년간 통장유지, 근로활동 지속, 교육이수, 자금사용계획서 제출시 적립금 전액 지급)",
            )
        val comparison =
            example("comparison", "비대면 조사", "비대면 조사와 방문 조사를 비교", "비대면 미참여 세대 방문", "조사 방법 비교", "[0,1]")
        val matching = example("comparison", "정액 매칭", "10만원과 30만원 매칭 비교", "중위소득 50% 이하", "소득별 지원", indexes = "[2,3]")
        val invented = example("comparison", "정액 매칭", "5만원 매칭", "중위소득 50% 이하", "소득별 지원", indexes = "[2,3]")
        val set =
            validSetOf(
                IllustrationSuggestionParser.parseAndValidate(
                    jsonWith(comparison, matching, invented),
                    source,
                    listOf("합성 본문"),
                ),
            )
        assertThat(set.suggestions).hasSize(2)
        assertThat(set.droppedCount).isEqualTo(1)
    }

    @Test
    fun `숫자가 없는 다른 조사 경로는 원문에 없어도 사실 존재 검사로 판별하지 못한다`() {
        // 003-주민등록-사실조사.json 발췌. 저장 본문은 합성 fixture다.
        val source =
            listOf(
                "가. 비대면 조사: 정부24 앱에서 세대주 본인 인증을 마친 후 세대원 정보를 확인하고 이상 유무를 기재하시면 됩니다.",
                "나. 방문 조사: 비대면 조사에 참여하지 아니한 세대와 복지 확인이 필요한 세대는 조사원이 직접 방문합니다.",
            )
        val invented = example("comparison", "비대면 조사", "비대면 조사와 전화 조사를 비교", "전화 조사로 응답", "조사 방법 비교")

        val set =
            validSetOf(IllustrationSuggestionParser.parseAndValidate(jsonWith(invented), source, listOf("합성 본문")))

        assertThat(set.suggestions).hasSize(1)
        assertThat(set.droppedCount).isZero()
    }

    @Suppress("LongParameterList") // 각 JSON 필드를 독립적으로 바꾸어 환각 검사를 확인한다.
    private fun example(
        purpose: String,
        quote: String,
        scene: String,
        fact: String,
        alt: String,
        indexes: String = "[0]",
    ): String =
        """{"purpose":"$purpose","reason":"관계를 설명합니다","body_range":{"start":0,"end":0},
           "source_anchors":[{"source_unit_indexes":$indexes,"quote":"$quote"}],
           "scenes":["$scene"],"preserved_facts":["$fact"],"alt_text_draft":"$alt"}"""

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
