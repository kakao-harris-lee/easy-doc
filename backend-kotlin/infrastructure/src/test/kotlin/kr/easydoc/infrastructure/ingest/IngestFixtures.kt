package kr.easydoc.infrastructure.ingest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 추출 테스트가 쓰는 fixture 적재기와 즉석 생성기. */
internal object IngestFixtures {
    private const val ROOT = "/fixtures/ingest"

    /** 참고값 oracle. 정답이 아니다 — 갈리면 어느 쪽이 요구에 맞는지 판단해 기록한다. */
    val repoOracle: JsonObject by lazy { readJson("repo-fixtures-oracle.json") }

    /** spike 가 만든 합성 fixture 셋의 참고값. */
    val spikeOracle: JsonObject by lazy { readJson("spike-oracle.json") }

    fun bytes(name: String): ByteArray =
        requireNotNull(IngestFixtures::class.java.getResourceAsStream("$ROOT/$name")) {
            "fixture 를 찾지 못했다: $ROOT/$name"
        }.use { it.readBytes() }

    /** oracle 의 `{"text": ...}` 값. */
    fun expectedText(
        oracle: JsonObject,
        key: String,
    ): String =
        oracle
            .getValue(key)
            .jsonObject
            .getValue("text")
            .jsonPrimitive.content

    /** oracle 의 블록 배열(`_raw_docx_blocks` 또는 `...::blocks`). */
    fun expectedBlocks(
        oracle: JsonObject,
        vararg path: String,
    ): List<String> {
        var node = oracle.getValue(path.first())
        for (step in path.drop(1)) node = node.jsonObject.getValue(step)
        return node.jsonArray.map { it.jsonPrimitive.content }
    }

    private fun readJson(name: String): JsonObject = Json.parseToJsonElement(bytes(name).decodeToString()).jsonObject

    /** zip 아카이브 하나를 만든다. */
    fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return sink.toByteArray()
    }

    /** 아카이브 안의 한 항목만 [replacement] 로 바꾼 사본을 만든다. */
    fun withEntryReplaced(
        archive: ByteArray,
        entryName: String,
        replacement: ByteArray,
    ): ByteArray = zipOf(entriesOf(archive).toMutableMap().apply { put(entryName, replacement) })

    /** 내용은 그대로 두고 다시 포장만 한 사본. 위 변형의 대조군이다. */
    fun repackaged(archive: ByteArray): ByteArray = zipOf(entriesOf(archive))

    fun entriesOf(archive: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }
}
