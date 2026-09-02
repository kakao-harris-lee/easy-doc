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
        val id = text(root, "id")
        require(SAFE_DOCUMENT_ID.matches(id)) {
            "골든 문서 id \"$id\" (${file.name}) 는 안전한 파일명 문법을 어긴다 — 영숫자·`.`·`_`·`-` 만 " +
                "허용한다. 이 id 는 LaneTranscript·LaneDictionary 등 소비자가 파일명에 그대로 쓰므로, " +
                "코퍼스에 들어오는 시점에 막는다. 이 문서의 id 를 고쳐라."
        }
        return GoldenDocument(
            id = id,
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

    /**
     * 골든 문서 id 의 안전한 파일명 문법 — 코퍼스 스키마 차원의 계약이다.
     *
     * 문서 id 는 이 로더를 거친 뒤 여러 소비자가 파일명에 그대로 쓴다 —
     * `LaneTranscript`(`<디렉터리>/<id>.txt` 로 변환문을 쓴다)와 `LaneDictionary` 파일 주입
     * 모드(`<디렉터리>/<id>.txt` 를 읽는다)가 그 예다. [loadFile] 이 이 문법을 로드 시점에
     * 강제하므로, 이 값을 통과한 코퍼스를 쓰는 모든 소비자가 별도 검사 없이 이 불변식을
     * 물려받는다.
     *
     * 영숫자·`.`·`_`·`-` 만 허용한다 — 특히 `/`(POSIX)·`\`(Windows) 경로 구분자를 뺀 것이
     * 핵심이다. 실제 골든 코퍼스 문서(`data/golden/documents` 아래 JSON 파일)의 id 는 전부
     * 세 자리 숫자라 이 문법보다 훨씬 좁지만, 여기는 그보다 넓게 — 다만 경로 이스케이프는
     * 불가능하게 — 허용한다.
     *
     * 소비자는 이 값을 그대로 참조해야 한다(예: `LaneTranscript.SAFE_DOCUMENT_ID`) — 같은
     * 문법을 두 곳에 따로 적으면 값 출처가 둘이 되어 여기서 문법을 넓히거나 좁혀도 다른 쪽이
     * 조용히 어긋난다.
     */
    val SAFE_DOCUMENT_ID: Regex = Regex("^[A-Za-z0-9._-]+$")
}
