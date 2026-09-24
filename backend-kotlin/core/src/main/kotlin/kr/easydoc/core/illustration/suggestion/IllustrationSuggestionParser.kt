package kr.easydoc.core.illustration.suggestion

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * LLM 이 낸 그림 제안 JSON 의 엄격한 v1 reader(명세 §4·§5).
 *
 * LLM 출력에는 **제안 목록만** 있다 — `analysis_version`·`suggestion_id`·`dropped_count` 는
 * 서버가 붙이는 값이라 출력 스키마에 없고, 들어 있으면 모르는 키로 보고 결과 전체를 무효로
 * 만든다. 형식 위반을 예외가 아니라 `null` 로 끊어 [IllustrationSuggestionAnalysis] 한
 * 갈래로 모은다 — 형식 위반은 이 기능에서 예외 상황이 아니라 예상된 결과 중 하나다.
 */
object IllustrationSuggestionParser {
    /** 행동 안내 후보(`ActionGuideCandidateParser`)와 같은 입력 크기 상한. 파싱 전에 자른다. */
    private const val MAX_JSON_CHARS = 262_144

    private val SUGGESTION_KEYS =
        arrayOf("purpose", "reason", "body_range", "source_anchors", "scenes", "preserved_facts", "alt_text_draft")

    /**
     * 원문([sourceUnits])·저장 본문 줄 수([savedBodyLineCount])와 대조해 결과 한 건을 낸다.
     * [suggestionIds] 는 검증을 통과한 제안에만 쓰인다.
     */
    fun parseAndValidate(
        rawJson: String,
        sourceUnits: List<String>,
        savedBodyLineCount: Int,
        suggestionIds: IllustrationSuggestionIdGenerator = RandomIllustrationSuggestionIds,
    ): IllustrationSuggestionAnalysis {
        val drafts = decode(rawJson) ?: return IllustrationSuggestionAnalysis.InvalidStructure
        return IllustrationSuggestionValidator.validate(drafts, sourceUnits, savedBodyLineCount, suggestionIds)
    }

    /** 형식을 어기면 `null`. 각 갈래가 곧 「결과 전체 무효」라 `ReturnCount` 를 벗어난다. */
    @Suppress("ReturnCount")
    private fun decode(rawJson: String): List<IllustrationSuggestionDraft>? {
        if (rawJson.length > MAX_JSON_CHARS) return null
        val root =
            try {
                Json.parseToJsonElement(rawJson) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
        if (!root.hasExactKeys("schema_version", "suggestions")) return null
        if (root.intValue("schema_version") != IllustrationSuggestionValidator.SCHEMA_VERSION) return null
        val suggestions = root.arrayValue("suggestions") ?: return null
        return suggestions.map { decodeSuggestion(it) ?: return null }
    }

    /** 위와 같은 이유로 갈래마다 끊는다. */
    @Suppress("ReturnCount")
    private fun decodeSuggestion(element: JsonElement): IllustrationSuggestionDraft? {
        val suggestion = element as? JsonObject ?: return null
        if (!suggestion.hasExactKeys(*SUGGESTION_KEYS)) return null
        val purpose =
            IllustrationSuggestionPurpose.fromWireName(suggestion.stringValue("purpose") ?: return null)
                ?: return null
        val anchors =
            (suggestion.arrayValue("source_anchors") ?: return null).map { decodeAnchor(it) ?: return null }
        return IllustrationSuggestionDraft(
            purpose = purpose,
            reason = suggestion.stringValue("reason") ?: return null,
            bodyRange = decodeBodyRange(suggestion["body_range"]) ?: return null,
            sourceAnchors = anchors,
            scenes = suggestion.stringListValue("scenes") ?: return null,
            preservedFacts = suggestion.stringListValue("preserved_facts") ?: return null,
            altTextDraft = suggestion.stringValue("alt_text_draft") ?: return null,
        )
    }

    private fun decodeBodyRange(element: JsonElement?): IllustrationSuggestionBodyRange? {
        val range = (element as? JsonObject)?.takeIf { it.hasExactKeys("start", "end") } ?: return null
        val start = range.intValue("start")
        val end = range.intValue("end")
        return if (start == null || end == null) null else IllustrationSuggestionBodyRange(start, end)
    }

    private fun decodeAnchor(element: JsonElement): IllustrationSuggestionSourceAnchor? {
        val keys = arrayOf("source_unit_indexes", "quote")
        val anchor = (element as? JsonObject)?.takeIf { it.hasExactKeys(*keys) } ?: return null
        val indexes = anchor.intListValue("source_unit_indexes")
        val quote = anchor.stringValue("quote")
        return if (indexes == null || quote == null) null else IllustrationSuggestionSourceAnchor(indexes, quote)
    }
}

/**
 * 검증 전의 LLM 출력 한 건 — 식별자가 없다. 서버는 원문 대조를 통과한 제안에만 식별자를
 * 붙여 [IllustrationSuggestion] 으로 바꾼다(명세 §4).
 */
internal data class IllustrationSuggestionDraft(
    val purpose: IllustrationSuggestionPurpose,
    val reason: String,
    val bodyRange: IllustrationSuggestionBodyRange,
    val sourceAnchors: List<IllustrationSuggestionSourceAnchor>,
    val scenes: List<String>,
    val preservedFacts: List<String>,
    val altTextDraft: String,
) {
    /** [IllustrationSuggestion] 과 같은 이유로 본문을 찍지 않는다. */
    override fun toString(): String =
        "IllustrationSuggestionDraft(purpose=$purpose, anchorCount=${sourceAnchors.size}, " +
            "sceneCount=${scenes.size}, factCount=${preservedFacts.size})"
}

private fun JsonObject.hasExactKeys(vararg expected: String): Boolean = keys == expected.toSet()

private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.intValue(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonObject.stringValue(key: String): String? = get(key)?.stringValue()

private fun JsonObject.intValue(key: String): Int? = get(key)?.intValue()

private fun JsonObject.arrayValue(key: String): JsonArray? = get(key) as? JsonArray

private fun JsonObject.stringListValue(key: String): List<String>? =
    arrayValue(key)?.map { it.stringValue() ?: return null }

private fun JsonObject.intListValue(key: String): List<Int>? = arrayValue(key)?.map { it.intValue() ?: return null }
