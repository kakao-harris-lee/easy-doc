package kr.easydoc.core.actionguide

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

/** Provider와 사용자 저장 요청에 동일하게 적용할 엄격한 v1 JSON codec. */
object ActionGuideCandidateParser {
    fun parseAndValidate(
        rawJson: String,
        sourceUnits: List<String>,
    ): ActionGuideCandidate {
        val decoded = decodeInternal(rawJson, validateStructure = false)
        ActionGuideCandidateValidator.validateBeforeAnchorCompaction(decoded, sourceUnits)
        return ActionGuideCandidateAnchorCompactor
            .compact(decoded, sourceUnits)
            .also { ActionGuideCandidateValidator.validate(it, sourceUnits) }
    }

    /** 이미 원문과 대조해 저장했던 암호문을 읽을 때 구조만 다시 검사한다. 새 입력 수락에는 쓰지 않는다. */
    fun decode(rawJson: String): ActionGuideCandidate = decodeInternal(rawJson, validateStructure = true)

    private fun decodeInternal(
        rawJson: String,
        validateStructure: Boolean,
    ): ActionGuideCandidate {
        if (rawJson.length > MAX_LLM_JSON_CHARS) invalidCandidateJson()
        val root =
            try {
                Json.parseToJsonElement(rawJson).requiredObject()
            } catch (_: SerializationException) {
                invalidCandidateJson()
            }
        root.exactKeys("schema_version", "sections")
        val sections =
            root.requiredArray("sections").map { rawSection ->
                val section = rawSection.requiredObject()
                section.exactKeys("kind", "status", "items")
                ActionGuideSection(
                    ActionGuideSectionKind.ofWireName(section.requiredString("kind")),
                    ActionGuideSectionStatus.ofWireName(section.requiredString("status")),
                    section.requiredArray("items").map(::decodeItem),
                )
            }
        return ActionGuideCandidate(root.requiredInt("schema_version"), sections)
            .also { candidate ->
                if (validateStructure) ActionGuideCandidateValidator.validateStructure(candidate)
            }
    }

    /** 필드 순서까지 고정한 JSON. 암호화 저장 전 반드시 [parseAndValidate] 또는 validator를 거친다. */
    fun encode(candidate: ActionGuideCandidate): String {
        ActionGuideCandidateValidator.validateStructure(candidate)
        return buildJsonObject {
            put("schema_version", candidate.schemaVersion)
            put(
                "sections",
                buildJsonArray {
                    candidate.sections.forEach { section ->
                        add(
                            buildJsonObject {
                                put("kind", section.kind.wireName)
                                put("status", section.status.wireName)
                                put("items", buildJsonArray { section.items.forEach { add(encodeItem(it)) } })
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun decodeItem(element: JsonElement): ActionGuideItem {
        val item = element.requiredObject()
        item.exactKeys("text", "cautions", "source_anchors")
        return ActionGuideItem(
            item.requiredString("text"),
            item.requiredArray("cautions").map { it.requiredString() },
            item.requiredArray("source_anchors").map { element ->
                val anchor = element.requiredObject()
                anchor.exactKeys("source_unit_indexes", "quote")
                ActionGuideSourceAnchor(
                    anchor.requiredArray("source_unit_indexes").map { it.requiredInt() },
                    anchor.requiredString("quote"),
                )
            },
        )
    }

    private fun encodeItem(item: ActionGuideItem): JsonObject =
        buildJsonObject {
            put("text", item.text)
            put("cautions", buildJsonArray { item.cautions.forEach { add(JsonPrimitive(it)) } })
            put(
                "source_anchors",
                buildJsonArray {
                    item.sourceAnchors.forEach { anchor ->
                        add(
                            buildJsonObject {
                                put(
                                    "source_unit_indexes",
                                    buildJsonArray {
                                        anchor.sourceUnitIndexes.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                put("quote", anchor.quote)
                            },
                        )
                    }
                },
            )
        }
}

private fun JsonElement.requiredObject(): JsonObject = this as? JsonObject ?: invalidCandidateJson()

private fun JsonElement.requiredArray(): JsonArray = this as? JsonArray ?: invalidCandidateJson()

private fun JsonElement.requiredString(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidCandidateJson()

private fun JsonElement.requiredInt(): Int =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalidCandidateJson()

private fun JsonObject.requiredArray(key: String): JsonArray = get(key)?.requiredArray() ?: invalidCandidateJson()

private fun JsonObject.requiredString(key: String): String = get(key)?.requiredString() ?: invalidCandidateJson()

private fun JsonObject.requiredInt(key: String): Int = get(key)?.requiredInt() ?: invalidCandidateJson()

private fun JsonObject.exactKeys(vararg expected: String) {
    if (keys != expected.toSet()) invalidCandidateJson()
}

private fun invalidCandidateJson(): Nothing = throw InvalidInputException("행동 안내문 후보 형식이 올바르지 않습니다.")
