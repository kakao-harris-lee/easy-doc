package kr.easydoc.core.illustration.generation

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
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionBodyRange
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionPurpose
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionSourceAnchor
import java.util.UUID

@Suppress("TooManyFunctions")
object IllustrationGenerationStorageCodec {
    const val MAX_PLAINTEXT_CHARS: Int = 200_000

    fun encode(input: IllustrationGenerationInput): String =
        buildJsonObject {
            put("final_prompt", input.finalPrompt)
            put("metadata", encodeMetadata(input.metadata))
            put("execution_mode", input.executionMode)
        }.toString()

    fun decode(rawJson: String): IllustrationGenerationInput {
        if (rawJson.length > MAX_PLAINTEXT_CHARS) corrupt()
        val root =
            try {
                Json.parseToJsonElement(rawJson).requiredObject()
            } catch (_: SerializationException) {
                corrupt()
            }
        root.exactKeys("final_prompt", "metadata", "execution_mode")
        val mode = root.requiredString("execution_mode")
        if (mode !in setOf("fake", "provider")) corrupt()
        return IllustrationGenerationInput(
            root.requiredString("final_prompt"),
            decodeMetadata(root.requiredObject("metadata")),
            mode,
        )
    }

    private fun encodeMetadata(metadata: IllustrationGenerationMetadata): JsonObject =
        buildJsonObject {
            put("suggestion_id", metadata.suggestionId.toString())
            put("analysis_version", metadata.analysisVersion)
            put("configuration_revision", metadata.configurationRevision)
            put("purpose", metadata.purpose.wireName)
            put("reason", metadata.reason)
            put(
                "body_range",
                buildJsonObject {
                    put("start", metadata.bodyRange.start)
                    put("end", metadata.bodyRange.end)
                },
            )
            put(
                "source_anchors",
                buildJsonArray {
                    metadata.sourceAnchors.forEach { anchor ->
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
            put("scenes", stringArray(metadata.scenes))
            put("preserved_facts", stringArray(metadata.preservedFacts))
            put("alt_text", metadata.altText)
        }

    private fun decodeMetadata(element: JsonElement): IllustrationGenerationMetadata {
        val value = element.requiredObject()
        value.exactKeys(
            "suggestion_id",
            "analysis_version",
            "configuration_revision",
            "purpose",
            "reason",
            "body_range",
            "source_anchors",
            "scenes",
            "preserved_facts",
            "alt_text",
        )
        val range = value.requiredObject("body_range")
        range.exactKeys("start", "end")
        return IllustrationGenerationMetadata(
            suggestionId =
                try {
                    UUID.fromString(value.requiredString("suggestion_id"))
                } catch (_: IllegalArgumentException) {
                    corrupt()
                },
            analysisVersion = value.requiredString("analysis_version"),
            configurationRevision = value.requiredString("configuration_revision"),
            purpose = IllustrationSuggestionPurpose.fromWireName(value.requiredString("purpose")) ?: corrupt(),
            reason = value.requiredString("reason"),
            bodyRange = IllustrationSuggestionBodyRange(range.requiredInt("start"), range.requiredInt("end")),
            sourceAnchors = value.requiredArray("source_anchors").map(::decodeAnchor),
            scenes = value.requiredArray("scenes").map { it.requiredString() },
            preservedFacts = value.requiredArray("preserved_facts").map { it.requiredString() },
            altText = value.requiredString("alt_text"),
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

    private fun stringArray(values: List<String>): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun corrupt(): Nothing = throw InvalidInputException(CORRUPT_GENERATION_INPUT_MESSAGE)

    private fun JsonElement.requiredObject(): JsonObject = this as? JsonObject ?: corrupt()

    private fun JsonElement.requiredArray(): JsonArray = this as? JsonArray ?: corrupt()

    private fun JsonElement.requiredString(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: corrupt()

    private fun JsonElement.requiredInt(): Int =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: corrupt()

    private fun JsonObject.exactKeys(vararg expected: String) {
        if (keys != expected.toSet()) corrupt()
    }

    private fun JsonObject.requiredObject(key: String): JsonObject = get(key)?.requiredObject() ?: corrupt()

    private fun JsonObject.requiredArray(key: String): JsonArray = get(key)?.requiredArray() ?: corrupt()

    private fun JsonObject.requiredString(key: String): String = get(key)?.requiredString() ?: corrupt()

    private fun JsonObject.requiredInt(key: String): Int = get(key)?.requiredInt() ?: corrupt()

    const val CORRUPT_GENERATION_INPUT_MESSAGE: String = "저장된 그림 생성 입력을 읽을 수 없습니다"
}
