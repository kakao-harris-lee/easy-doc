package kr.easydoc.core.easyread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** 프롬프트 전문이 이식 과정에서 한 글자도 표류하지 않았는지 전건 대조한다. */
class PromptTextSnapshotTest {
    @Test
    @DisplayName("구분자 태그 이름과 id 바이트 수가 같다")
    fun `구분자 상수가 일치한다`() {
        assertThat(DOCUMENT_TAG_NAME).isEqualTo(SNAPSHOT.string("DOCUMENT_TAG_NAME"))
        assertThat(CONVERTED_TAG_NAME).isEqualTo(SNAPSHOT.string("CONVERTED_TAG_NAME"))
        assertThat(DOCUMENT_ID_BYTES).isEqualTo(
            SNAPSHOT
                .getValue("_DOCUMENT_ID_BYTES")
                .jsonPrimitive.int,
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instructionConstants")
    @DisplayName("지시문 상수가 전문 일치한다")
    fun `지시문 상수가 전문 일치한다`(
        key: String,
        actual: String,
    ) {
        assertThat(actual).isEqualTo(SNAPSHOT.string(key))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("systemPromptCases")
    @DisplayName("시스템 프롬프트 조립 결과가 전문 일치한다")
    fun `시스템 프롬프트가 전문 일치한다`(
        @Suppress("UNUSED_PARAMETER") name: String,
        sourceText: String,
        expected: String,
    ) {
        assertThat(buildSystemPrompt(sourceText)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userPromptCases")
    @DisplayName("사용자 프롬프트 조립 결과가 전문 일치한다")
    fun `사용자 프롬프트가 전문 일치한다`(
        @Suppress("UNUSED_PARAMETER") name: String,
        sourceText: String,
        expected: String,
    ) {
        assertThat(buildUserPrompt(sourceText, FIXED_IDS)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repairPromptCases")
    @DisplayName("보정 프롬프트 조립 결과가 전문 일치한다")
    fun `보정 프롬프트가 전문 일치한다`(
        @Suppress("UNUSED_PARAMETER") name: String,
        convertedText: String,
        violations: List<SentenceIssue>,
        expectedSystem: String,
        expectedUser: String,
    ) {
        val prompt = buildRepairPrompt(ModelDraft(convertedText), violations, documentIds = FIXED_IDS)
        assertThat(prompt.system).isEqualTo(expectedSystem)
        assertThat(prompt.user).isEqualTo(expectedUser)
    }

    // ── [구조 절 취급] 방어 — P0-4 S8-2 리뷰 HIGH-2 ────────────────────────────────────
    //
    // 인용을 담은 [구조] 절이 실릴 때만 시스템 프롬프트에 방어 문구가 붙어야 한다. 위의
    // 파라미터화 스냅샷 테스트(`buildSystemPrompt(sourceText)`)는 이미 `structureSection`
    // 인자 없이(기본값 `null`) 부르므로, 이 인자가 생긴 뒤에도 그 골든 스냅샷이 그대로
    // 통과한다는 사실 자체가 B1(널 경로 불변)의 증거다 — 아래는 그 사실을 명시적으로도
    // 고정하고, 인용이 있는/없는 두 갈래를 함께 고정한다.

    @Test
    @DisplayName("구조 절 인자가 없으면(널) 시스템 프롬프트에 방어 절이 없다 — B1 널 경로 불변")
    fun `구조 절이 없으면 방어 문구도 없다`() {
        val withoutArg = buildSystemPrompt("본문입니다.")
        val withExplicitNull = buildSystemPrompt("본문입니다.", null)

        assertThat(withoutArg).isEqualTo(withExplicitNull)
        assertThat(withoutArg).doesNotContain("[구조 절 취급]")
    }

    @Test
    @DisplayName("인용이 있는 [구조] 절(다중 run)이 실리면 시스템 프롬프트에 방어 절이 붙는다")
    fun `인용이 있으면 방어 절이 붙는다`() {
        val structure =
            renderStructureSection(
                SourceStructure(listOf(UnitKind.TABLE_CELL, UnitKind.TABLE_CELL)),
                listOf("구분", "금액"),
                FIXED_IDS,
                maxRuns = 40,
            )

        val system = buildSystemPrompt("본문입니다.", structure)

        assertThat(system).contains("[구조 절 취급]")
        assertThat(system).contains(STRUCTURE_QUOTE_GUARD)
        // 문서 취급 절과 자가 점검 절 사이에 온다 — INJECTION_GUARD 뒤, SELF_CHECK 앞.
        assertThat(system.indexOf("[문서 취급]")).isLessThan(system.indexOf("[구조 절 취급]"))
        assertThat(system.indexOf("[구조 절 취급]")).isLessThan(system.indexOf("[출력 전 자가 점검]"))
    }

    @Test
    @DisplayName("인용이 없는 단위 문장 경로(재변환)는 방어 절을 붙이지 않는다")
    fun `단위 문장 경로는 방어 절이 없다`() {
        val unitSentence =
            renderStructureSection(
                SourceStructure(listOf(UnitKind.TABLE_CELL)),
                listOf("구분"),
                FIXED_IDS,
                maxRuns = 40,
            )
        checkNotNull(unitSentence)
        assertThat(unitSentence).doesNotContain(STRUCTURE_TAG_NAME)

        val system = buildSystemPrompt("본문입니다.", unitSentence)

        assertThat(system).doesNotContain("[구조 절 취급]")
    }

    @Test
    @DisplayName("보정 시스템 프롬프트도 인용이 있을 때만 같은 방어 절을 붙인다")
    fun `보정 프롬프트도 인용 유무에 따라 방어 절을 붙인다`() {
        val structure =
            renderStructureSection(
                SourceStructure(listOf(UnitKind.TABLE_CELL, UnitKind.TABLE_CELL)),
                listOf("구분", "금액"),
                FIXED_IDS,
                maxRuns = 40,
            )
        val draft = ModelDraft("변환문입니다.")

        val withStructure = buildRepairPrompt(draft, emptyList(), documentIds = FIXED_IDS, structureSection = structure)
        val withoutStructure = buildRepairPrompt(draft, emptyList(), documentIds = FIXED_IDS)

        assertThat(withStructure.system).contains("[구조 절 취급]")
        assertThat(withoutStructure.system).doesNotContain("[구조 절 취급]")
    }

    companion object {
        private const val SNAPSHOT_RESOURCE = "/kr/easydoc/core/easyread/prompt-snapshot.json"

        private val SNAPSHOT: JsonObject = loadSnapshot()

        /** 스냅샷을 뽑을 때 고정해 둔 id 와 같은 값을 낸다 — 다르면 구분자 태그가 스냅샷과 갈린다. */
        private val FIXED_IDS = DocumentIdGenerator { SNAPSHOT.string("_fixed_document_id") }

        private fun loadSnapshot(): JsonObject {
            val stream =
                PromptTextSnapshotTest::class.java.getResourceAsStream(SNAPSHOT_RESOURCE)
                    ?: error(
                        "프롬프트 전문 스냅샷이 없다: $SNAPSHOT_RESOURCE — " +
                            "이 파일이 없으면 프롬프트 이식이 검증되지 않은 채 통과한다.",
                    )
            return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        }

        private fun JsonObject.string(key: String): String =
            getValue(key)
                .jsonPrimitive.content

        @JvmStatic
        fun instructionConstants(): List<Arguments> =
            listOf(
                "_ROLE" to ROLE,
                "_LENGTH_INSTRUCTION" to LENGTH_INSTRUCTION,
                "_SPLIT_EXAMPLES" to SPLIT_EXAMPLES,
                "_REPLACEMENT_INSTRUCTION" to REPLACEMENT_INSTRUCTION,
                "_EXPLAIN_INSTRUCTION" to EXPLAIN_INSTRUCTION,
                "_SELF_CHECK_INSTRUCTION" to SELF_CHECK_INSTRUCTION,
                "_CONDITIONAL_INSTRUCTION" to CONDITIONAL_INSTRUCTION,
                "INJECTION_GUARD" to INJECTION_GUARD,
                "_OUTPUT_INSTRUCTION" to OUTPUT_INSTRUCTION,
                "_REPAIR_ROLE" to REPAIR_ROLE,
                "_REPAIR_INSTRUCTION" to REPAIR_INSTRUCTION,
            ).map { (key, actual) -> Arguments.of(key, actual) }

        @JvmStatic
        fun systemPromptCases(): List<Arguments> = bodyCases("system_prompts")

        @JvmStatic
        fun userPromptCases(): List<Arguments> = bodyCases("user_prompts")

        private fun bodyCases(key: String): List<Arguments> =
            SNAPSHOT.getValue(key).jsonArray.map { element ->
                val case = element.jsonObject
                Arguments.of(
                    case.string("name"),
                    case.string("source_text"),
                    case.string("expected"),
                )
            }

        @JvmStatic
        fun repairPromptCases(): List<Arguments> =
            SNAPSHOT.getValue("repair_prompts").jsonArray.map { element ->
                val case = element.jsonObject
                val violations =
                    case.getValue("violations").jsonArray.map { issue ->
                        val fields = issue.jsonObject
                        SentenceIssue(
                            sentence = fields.string("sentence"),
                            kind = StyleRuleKind.DIFFICULT_WORD,
                            reason = fields.string("reason"),
                            word = fields.getValue("word").jsonPrimitive.contentOrNull,
                        )
                    }
                Arguments.of(
                    case.string("name"),
                    case.string("converted_text"),
                    violations,
                    case.string("expected_system"),
                    case.string("expected_user"),
                )
            }
    }
}
