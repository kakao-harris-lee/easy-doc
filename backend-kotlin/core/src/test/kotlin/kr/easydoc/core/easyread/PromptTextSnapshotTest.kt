package kr.easydoc.core.easyread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.maskText
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
        maskedText: String,
        expected: String,
    ) {
        val masked = maskText(sourceText).maskedText
        assertThat(masked.value)
            .withFailMessage(
                "마스킹 결과가 갈렸다. 프롬프트가 맞아도 모델이 받는 본문이 다르다.\n기대: %s\n실제: %s",
                maskedText,
                masked.value,
            ).isEqualTo(maskedText)
        assertThat(buildSystemPrompt(masked)).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("userPromptCases")
    @DisplayName("사용자 프롬프트 조립 결과가 전문 일치한다")
    fun `사용자 프롬프트가 전문 일치한다`(
        @Suppress("UNUSED_PARAMETER") name: String,
        sourceText: String,
        maskedText: String,
        expected: String,
    ) {
        val masked = maskText(sourceText).maskedText
        assertThat(masked.value).isEqualTo(maskedText)

        assertThat(buildUserPrompt(masked, FIXED_IDS)).isEqualTo(expected)
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
        val prompt = buildRepairPrompt(ModelDraft(convertedText), violations, FIXED_IDS)
        assertThat(prompt.system).isEqualTo(expectedSystem)
        assertThat(prompt.user).isEqualTo(expectedUser)
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
                "PLACEHOLDER_INSTRUCTION" to PLACEHOLDER_INSTRUCTION,
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
                    case.string("masked_text"),
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
