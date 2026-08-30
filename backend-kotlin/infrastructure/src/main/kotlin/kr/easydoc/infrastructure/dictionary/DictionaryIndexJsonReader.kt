package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.DictionaryEntry
import kr.easydoc.core.dictionary.DictionaryExample
import kr.easydoc.core.dictionary.DictionaryIndex
import kr.easydoc.core.dictionary.ReplaceStrategy
import kr.easydoc.core.dictionary.RiskLevel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream

/**
 * `easy_dict.index.json`(easy-dictionary §4.3)을 [DictionaryIndex] 로 옮기는 어댑터.
 *
 * core 에는 JSON 라이브러리가 없으므로(`core/build.gradle.kts`) 축약 wire 키(`t`/`e`/`d`/`s`/
 * `r`/`p`/`g`/`c`/`x`)를 도메인 이름으로 푸는 자리는 **여기 한 곳**이다. 색인은 1.5MB 라 변환
 * 1건마다 읽지 않는다 — 조립 시점(`ConversionWorkerConfiguration`)에 한 번 읽어 재사용하고,
 * 만들어진 [DictionaryIndex] 는 읽기 전용이라 여러 스레드가 함께 써도 안전하다.
 */
class DictionaryIndexJsonReader(private val json: JsonMapper = JsonMapper.builder().build()) {
    /** 배포 사본을 클래스패스에서 읽는다. 도커 이미지가 실제로 타는 경로다. */
    fun readClasspathResource(path: String = RESOURCE_PATH): DictionaryIndex {
        val stream =
            javaClass.getResourceAsStream(path)
                ?: error("사전 색인 리소스가 없다: $path — ./gradlew :infrastructure:syncDictionaryIndex 를 돌린다.")
        return stream.use(::read)
    }

    /**
     * 색인 문서 하나를 읽는다.
     *
     * **`schema_version` 을 단언한다.** 지원하지 않는 버전에서 기동을 실패시키지 않으면, 스키마가
     * 바뀐 색인을 옛 규칙으로 읽어 조용히 틀린 지침을 프롬프트에 싣게 된다 — 잘못된 사전 지침은
     * 사전이 없는 것보다 나쁘다.
     */
    fun read(stream: InputStream): DictionaryIndex {
        val root = json.readTree(stream)
        val version = root.path(FIELD_SCHEMA_VERSION).stringValue("")
        check(version == SUPPORTED_SCHEMA_VERSION) {
            "지원하지 않는 사전 색인 스키마다: '$version' (지원: '$SUPPORTED_SCHEMA_VERSION')"
        }

        return DictionaryIndex.of(
            entries = entries(root.path(FIELD_ENTRIES)),
            surfaceIndex = surfaceIndex(root.path(FIELD_SURFACE_INDEX)),
            josa = root.path(FIELD_JOSA).toList().map { it.stringValue("") },
        )
    }

    private fun entries(node: JsonNode): Map<Int, DictionaryEntry> =
        node.properties().associate { (id, value) -> id.toInt() to entry(value) }

    private fun entry(node: JsonNode): DictionaryEntry =
        DictionaryEntry(
            term = required(node, FIELD_TERM),
            easyTerm = required(node, FIELD_EASY_TERM),
            strategy = ReplaceStrategy.ofWire(required(node, FIELD_STRATEGY)),
            risk = RiskLevel.ofWire(required(node, FIELD_RISK)),
            priority = node.path(FIELD_PRIORITY).asInt(),
            definition = optional(node, FIELD_DEFINITION),
            caution = optional(node, FIELD_CAUTION),
            tags = node.path(FIELD_TAGS).toList().map { it.stringValue("") },
            examples = node.path(FIELD_EXAMPLES).toList().map(::example),
        )

    private fun example(node: JsonNode): DictionaryExample =
        DictionaryExample(
            before = required(node, FIELD_EXAMPLE_BEFORE),
            after = required(node, FIELD_EXAMPLE_AFTER),
            isGolden = node.path(FIELD_EXAMPLE_GOLDEN).booleanValue(false),
        )

    /**
     * 표면형 → entry id 나열.
     *
     * **정렬하지 않는다** — 이 순서가 easy-dictionary §6.8 의 승자 순서이고, export 시점에 구워져
     * 들어온다([DictionaryIndex.of] KDoc).
     */
    private fun surfaceIndex(node: JsonNode): Map<String, List<Int>> =
        node.properties().associate { (surface, ids) -> surface to ids.toList().map { it.asInt() } }

    /** 색인이 반드시 채워 주는 필드. 비어 있으면 색인이 깨진 것이므로 적재 시점에 거절한다. */
    private fun required(
        node: JsonNode,
        field: String,
    ): String =
        node.path(field).stringValue("").ifEmpty {
            error("사전 색인 엔트리에 '$field' 값이 없다")
        }

    /** 색인에서 실제로 `null` 로 올 수 있는 필드. 빈 문자열도 없는 값으로 본다. */
    private fun optional(
        node: JsonNode,
        field: String,
    ): String? = node.path(field).stringValue("").ifEmpty { null }

    companion object {
        /**
         * 지원하는 색인 스키마 버전 (`dictionary/src/easydict/models.py::SCHEMA_VERSION`).
         *
         * 코드와 함께 바뀌어야 하는 wire 불변식이라 구성값이 아니라 상수다(CLAUDE.md 「상수와
         * 구성 관리」). 사전 쪽에서 이 값이 올라가면 여기도 함께 올리며, 그때 축약 키 해석이
         * 그대로인지 먼저 확인한다.
         */
        const val SUPPORTED_SCHEMA_VERSION: String = "1.0.0"

        /** 커밋된 배포 사본. `infrastructure/build.gradle.kts` 의 `syncDictionaryIndex` 가 채운다. */
        const val RESOURCE_PATH: String = "/dictionary/easy_dict.index.json"

        private const val FIELD_SCHEMA_VERSION = "schema_version"
        private const val FIELD_JOSA = "josa"
        private const val FIELD_SURFACE_INDEX = "surface_index"
        private const val FIELD_ENTRIES = "entries"
        private const val FIELD_TERM = "t"
        private const val FIELD_EASY_TERM = "e"
        private const val FIELD_DEFINITION = "d"
        private const val FIELD_STRATEGY = "s"
        private const val FIELD_RISK = "r"
        private const val FIELD_PRIORITY = "p"
        private const val FIELD_TAGS = "g"
        private const val FIELD_CAUTION = "c"
        private const val FIELD_EXAMPLES = "x"
        private const val FIELD_EXAMPLE_BEFORE = "b"
        private const val FIELD_EXAMPLE_AFTER = "a"
        private const val FIELD_EXAMPLE_GOLDEN = "y"
    }
}
