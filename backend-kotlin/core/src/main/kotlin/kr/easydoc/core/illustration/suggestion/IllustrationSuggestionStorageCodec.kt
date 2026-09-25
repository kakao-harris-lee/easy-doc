package kr.easydoc.core.illustration.suggestion

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.llm.MAX_LLM_JSON_CHARS
import java.util.UUID

/**
 * 검증을 마친 [IllustrationSuggestionSet] 을 **암호화 저장 평문**으로 옮기는 codec
 * (`ActionGuideCandidateParser.encode`/`decode` 와 같은 자리·같은 규약).
 *
 * LLM 출력 reader([IllustrationSuggestionParser])와 나뉘는 이유는 다루는 값이 다르기 때문이다 —
 * 저장 평문에는 **서버가 붙인** `suggestion_id`·`analysis_version`·`dropped_count` 가 들어 있고,
 * LLM 출력에는 그 셋이 없다(있으면 결과 전체가 무효다). 한 reader 로 합치면 그 경계가 사라진다.
 *
 * [decode] 는 **이미 원문과 대조해 저장한** 암호문을 다시 읽는 자리라 구조만 본다. 새 입력을
 * 받아들이는 데 쓰지 않는다.
 */
object IllustrationSuggestionStorageCodec {
    /** 저장 평문도 LLM 출력과 같은 상한으로 먼저 자른다 — 손상된 거대 암호문이 파서를 태우지 않게. */
    const val MAX_PLAINTEXT_CHARS: Int = MAX_LLM_JSON_CHARS

    /** 필드 순서까지 고정한 JSON. 저장 전 반드시 파서·검증기를 거친 값만 넘긴다. */
    fun encode(suggestions: IllustrationSuggestionSet): String =
        buildJsonObject {
            put("schema_version", suggestions.schemaVersion)
            put("analysis_version", suggestions.analysisVersion)
            put("dropped_count", suggestions.droppedCount)
            put("suggestions", buildJsonArray { suggestions.suggestions.forEach { add(encodeSuggestion(it)) } })
        }.toString()

    fun decode(rawJson: String): IllustrationSuggestionSet {
        if (rawJson.length > MAX_PLAINTEXT_CHARS) corruptSuggestions()
        val root =
            try {
                Json.parseToJsonElement(rawJson).requiredObject()
            } catch (_: SerializationException) {
                corruptSuggestions()
            }
        root.exactKeys("schema_version", "analysis_version", "dropped_count", "suggestions")
        return IllustrationSuggestionSet(
            schemaVersion = root.requiredInt("schema_version"),
            analysisVersion = root.requiredString("analysis_version"),
            suggestions = root.requiredArray("suggestions").map(::decodeSuggestion),
            droppedCount = root.requiredInt("dropped_count"),
        )
    }

    private fun encodeSuggestion(suggestion: IllustrationSuggestion): JsonObject =
        buildJsonObject {
            put("suggestion_id", suggestion.suggestionId.toString())
            put("purpose", suggestion.purpose.wireName)
            put("reason", suggestion.reason)
            put(
                "body_range",
                buildJsonObject {
                    put("start", suggestion.bodyRange.start)
                    put("end", suggestion.bodyRange.end)
                },
            )
            put(
                "source_anchors",
                buildJsonArray {
                    suggestion.sourceAnchors.forEach { anchor ->
                        add(
                            buildJsonObject {
                                put(
                                    "source_unit_indexes",
                                    buildJsonArray { anchor.sourceUnitIndexes.forEach { add(JsonPrimitive(it)) } },
                                )
                                put("quote", anchor.quote)
                            },
                        )
                    }
                },
            )
            put("scenes", buildJsonArray { suggestion.scenes.forEach { add(JsonPrimitive(it)) } })
            put(
                "preserved_facts",
                buildJsonArray { suggestion.preservedFacts.forEach { add(JsonPrimitive(it)) } },
            )
            put("alt_text_draft", suggestion.altTextDraft)
        }

    private fun decodeSuggestion(element: JsonElement): IllustrationSuggestion {
        val suggestion = element.requiredObject()
        suggestion.exactKeys(
            "suggestion_id",
            "purpose",
            "reason",
            "body_range",
            "source_anchors",
            "scenes",
            "preserved_facts",
            "alt_text_draft",
        )
        val range = (suggestion["body_range"] ?: corruptSuggestions()).requiredObject()
        range.exactKeys("start", "end")
        return IllustrationSuggestion(
            suggestionId =
                try {
                    UUID.fromString(suggestion.requiredString("suggestion_id"))
                } catch (_: IllegalArgumentException) {
                    corruptSuggestions()
                },
            purpose =
                IllustrationSuggestionPurpose.fromWireName(suggestion.requiredString("purpose"))
                    ?: corruptSuggestions(),
            reason = suggestion.requiredString("reason"),
            bodyRange = IllustrationSuggestionBodyRange(range.requiredInt("start"), range.requiredInt("end")),
            sourceAnchors = suggestion.requiredArray("source_anchors").map(::decodeAnchor),
            scenes = suggestion.requiredArray("scenes").map { it.requiredString() },
            preservedFacts = suggestion.requiredArray("preserved_facts").map { it.requiredString() },
            altTextDraft = suggestion.requiredString("alt_text_draft"),
        )
    }

    private fun decodeAnchor(element: JsonElement): IllustrationSuggestionSourceAnchor {
        val anchor = element.requiredObject()
        anchor.exactKeys("source_unit_indexes", "quote")
        return IllustrationSuggestionSourceAnchor(
            anchor.requiredArray("source_unit_indexes").map { it.requiredInt() },
            anchor.requiredString("quote"),
        )
    }

    /** 손상 사유를 구분하지 않고 같은 문구로 끊는다 — 사유에 본문 조각이 실리면 안 된다. */
    const val CORRUPT_SUGGESTIONS_MESSAGE: String = "저장된 그림 제안을 읽을 수 없습니다"
}

private fun corruptSuggestions(): Nothing =
    throw InvalidInputException(IllustrationSuggestionStorageCodec.CORRUPT_SUGGESTIONS_MESSAGE)

private fun JsonElement.requiredObject(): JsonObject = this as? JsonObject ?: corruptSuggestions()

private fun JsonElement.requiredArray(): JsonArray = this as? JsonArray ?: corruptSuggestions()

private fun JsonElement.requiredString(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: corruptSuggestions()

private fun JsonElement.requiredInt(): Int =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: corruptSuggestions()

private fun JsonObject.exactKeys(vararg expected: String) {
    if (keys != expected.toSet()) corruptSuggestions()
}

private fun JsonObject.requiredArray(key: String): JsonArray = get(key)?.requiredArray() ?: corruptSuggestions()

private fun JsonObject.requiredString(key: String): String = get(key)?.requiredString() ?: corruptSuggestions()

private fun JsonObject.requiredInt(key: String): Int = get(key)?.requiredInt() ?: corruptSuggestions()
