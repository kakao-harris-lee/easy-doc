package kr.easydoc.core.quality

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** 기준선 digest 는 정규화된 JSON 내용의 정체성을 고정한다. */
class GoldenDigestTest {
    @Test
    @DisplayName("키 순서만 다른 JSON 은 같은 정규화 문자열이다")
    fun `키 순서가 달라도 digest 입력이 같다`() {
        val first = Json.parseToJsonElement("""{"id":"001","title":"A","z":1}""")
        val second = Json.parseToJsonElement("""{"z":1,"title":"A","id":"001"}""")

        assertThat(GoldenDocumentLoader.canonicalJson(first))
            .isEqualTo(GoldenDocumentLoader.canonicalJson(second))
    }

    @Test
    @DisplayName("본문이 바뀌면 digest 가 달라진다")
    fun `본문이 바뀌면 digest 가 다르다`(
        @TempDir temp: Path,
    ) {
        val file = temp.resolve("001.json").toFile()
        file.writeText("""{"id":"001","source_text":"원래 본문","required_facts":["만 65세"]}""")
        val original = GoldenDocumentLoader.jsonContentDigest(listOf(file))
        file.writeText("""{"id":"001","source_text":"다른 문서","required_facts":["만 65세"]}""")
        val swapped = GoldenDocumentLoader.jsonContentDigest(listOf(file))

        assertThat(original).isNotEqualTo(swapped)
        assertThat(original).startsWith("sha256:")
    }
}
