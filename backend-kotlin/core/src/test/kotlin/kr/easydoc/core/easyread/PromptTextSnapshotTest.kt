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

/**
 * 프롬프트 **전문**이 이식 과정에서 한 글자도 표류하지 않았는지 전건 대조한다.
 *
 * ## 왜 전문 대조인가
 *
 * 프롬프트는 코드가 아니라 큐레이션 데이터다. 실측 튜닝의 산출물이고 어디에서도
 * 유도되지 않는다 — "X → Y" 화살표를 버리고 "(뜻: ...)" 풀이 형식으로 바꾼 결정 하나에도
 * 2026-08-09 문서 020 실측이 뒤에 있다. 조사 하나가 달라져도 눈으로는 찾을 수 없고,
 * 증상은 골든셋 통과율이 조용히 몇 퍼센트 떨어지는 형태로만 나타난다.
 * **"옮겼다"는 주장은 대조 없이 근거가 아니다**(`StyleRuleDataSnapshotTest` 와 같은 취지).
 *
 * ## 무엇을 대조하는가
 *
 * 상수 하나하나뿐 아니라 **조립 결과 전체**를 본다. 상수가 전부 맞아도 절 순서나 사이
 * 줄바꿈 개수가 어긋나면 모델이 받는 것은 다른 프롬프트다. 조립 결과를 고정해 두면
 * 두 층(데이터 이식 / 조립 로직 이식)이 각각 검증된다.
 *
 * 본문 케이스는 **마스킹 전 원문**에서 시작해 Kotlin 마스킹을 통과시킨다. 스냅샷의
 * `masked_text` 와 대조하므로 두 구현이 그 이음매에서도 함께 검증된다 — 프롬프트가 맞아도
 * 마스킹 결과가 다르면 실제로 모델에게 가는 문자열은 다르다.
 *
 * ## 이 테스트가 깨지면
 *
 * 프롬프트를 "고치는" 것이 아니라 **왜 갈라졌는지 먼저 찾는다.** 품질 개선 의도의 수정은
 * 별건이고, 그때는 스냅샷을 다시 뽑는 것이 아니라 승인 경로를 밟는다(골든셋 실측 동반).
 */
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
        // 난수 id 는 스냅샷을 뽑을 때 고정한 값으로 맞춘다. 무작위성 자체는
        // PromptInjectionGuardTest 가 따로 본다.
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
        private const val SNAPSHOT_RESOURCE = "/kr/easydoc/core/easyread/python-prompt-snapshot.json"

        private val SNAPSHOT: JsonObject = loadSnapshot()

        /** 스냅샷을 뽑을 때 Python 쪽에 고정해 둔 id 와 같은 값을 낸다. */
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
