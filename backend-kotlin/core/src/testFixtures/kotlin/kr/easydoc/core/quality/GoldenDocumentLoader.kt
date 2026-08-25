package kr.easydoc.core.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** `data/golden/documents/` 를 읽어 [GoldenCorpus] 로 만든다. JSON 라이브러리는 testFixtures 에만 있다. */
object GoldenDocumentLoader {
    fun loadDirectory(directory: File): GoldenCorpus {
        require(directory.isDirectory) { "골든 문서 디렉터리가 없다: $directory" }
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" }?.sortedBy { it.name }
        require(!files.isNullOrEmpty()) { "골든 문서 JSON 이 하나도 없다: $directory" }
        return GoldenCorpus(
            documents = files.map { loadFile(it) },
            files = files.map { it.name },
            contentDigest = jsonContentDigest(files),
        )
    }

    fun loadFile(file: File): GoldenDocument {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return GoldenDocument(
            id = text(root, "id"),
            title = text(root, "title"),
            category = text(root, "category"),
            synthetic = root["synthetic"]?.jsonPrimitive?.booleanOrNull ?: false,
            sourceText = text(root, "source_text"),
            requiredFacts = facts(root["required_facts"]),
        )
    }

    /** `{id}.txt` 변환 스냅샷. 키는 문서 id 다. */
    fun loadConversions(directory: File): Map<String, String> {
        require(directory.isDirectory) { "골든 변환 디렉터리가 없다: $directory" }
        val files = directory.listFiles { file -> file.isFile && file.extension == "txt" }?.sortedBy { it.name }
        require(!files.isNullOrEmpty()) { "골든 변환 결과가 하나도 없다: $directory" }
        return files.associate { it.nameWithoutExtension to it.readText().trimEnd() }
    }

    fun documentsDirectory(): File = directory(DIRECTORY_PROPERTY, DIRECTORY_RELATIVE)

    fun conversionsDirectory(): File {
        val override = System.getProperty(CONVERSIONS_PROPERTY)
        if (!override.isNullOrBlank()) {
            return File(override)
        }
        return File(documentsDirectory().parentFile, CONVERSIONS_FOLDER)
    }

    fun jsonContentDigest(files: List<File>): String {
        val canonical =
            buildString {
                files.sortedBy { it.name }.forEach { file ->
                    append(file.name)
                    append('\u0000')
                    append(canonicalJson(Json.parseToJsonElement(file.readText())))
                    append('\u0000')
                }
            }
        return sha256Hex(canonical)
    }

    internal fun canonicalJson(element: JsonElement): String =
        when (element) {
            JsonNull -> {
                "null"
            }

            is JsonPrimitive -> {
                element.toString()
            }

            is JsonArray -> {
                element.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
            }

            is JsonObject -> {
                element.entries
                    .sortedBy { it.key }
                    .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                        JsonPrimitive(key).toString() + ":" + canonicalJson(value)
                    }
            }
        }

    private fun directory(
        property: String,
        relative: String,
    ): File {
        val override = System.getProperty(property)
        if (!override.isNullOrBlank()) {
            return File(override)
        }
        val sourceRoot =
            System.getProperty(SOURCE_ROOT_PROPERTY)
                ?: error(
                    "시스템 속성 $SOURCE_ROOT_PROPERTY 이 없다 — 골든 문서를 찾을 기준점이 없다. " +
                        "build.gradle.kts 의 테스트 태스크 설정을 확인한다.",
                )
        val candidate = File(sourceRoot).parentFile?.resolve(relative)
        require(candidate != null && candidate.isDirectory) {
            "골든 문서 디렉터리를 찾지 못했다: $candidate"
        }
        return candidate
    }

    private fun facts(node: JsonElement?): List<RequiredFact> {
        val array = node as? JsonArray ?: return emptyList()
        return array.map { fact ->
            when (fact) {
                is JsonPrimitive -> {
                    RequiredFact(fact.content)
                }

                is JsonObject -> {
                    RequiredFact(
                        canonical = text(fact, "canonical"),
                        accept = fact["accept"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    )
                }

                else -> {
                    RequiredFact("")
                }
            }
        }
    }

    private fun text(
        obj: JsonObject,
        key: String,
    ): String = obj[key]?.jsonPrimitive?.contentOrNull ?: ""

    const val DIRECTORY_PROPERTY: String = "easydoc.golden.documents.dir"
    const val CONVERSIONS_PROPERTY: String = "easydoc.golden.conversions.dir"
    private const val SOURCE_ROOT_PROPERTY: String = "easydoc.kotlin.source.root"
    private const val DIRECTORY_RELATIVE: String = "data/golden/documents"
    private const val CONVERSIONS_FOLDER: String = "conversions"
}
