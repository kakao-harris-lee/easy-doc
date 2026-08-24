package kr.easydoc.core.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 기준선은 커밋된 파일이다. 일반 테스트가 그 파일을 다시 쓰지 않으므로,
 * 문서 정체성이나 변환 채점이 바뀌면 이 테스트가 실패하고 리뷰 승인 후에만 파일을 고친다.
 */
class GoldenBaselineTest {
    @Test
    @DisplayName("현재 골든 코퍼스와 변환 채점이 커밋된 기준선과 같다")
    fun `기준선과 같다`() {
        val corpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory())
        val conversions = GoldenDocumentLoader.loadConversions(GoldenDocumentLoader.conversionsDirectory())
        val evaluation = evaluateConvertedCorpus(corpus, conversions)
        val current = corpus.baseline.withQuality(evaluation.quality)
        val recorded = loadRecorded()

        assertThat(evaluation.missingConversionIds).isEmpty()
        assertThat(baselineMismatch(current, recorded))
            .describedAs(
                "기준선이 바뀌면 이 파일이 아니라 리뷰에서 승인한다: $BASELINE_RESOURCE",
            ).isNull()
    }

    @Test
    @DisplayName("건수가 같아도 내용 digest 가 다르면 승인 없는 갱신으로 본다")
    fun `내용이 바뀌면 거부한다`() {
        val recorded =
            GoldenBaseline(
                documentCount = RECORDED_DOCUMENTS,
                requiredFactCount = RECORDED_FACTS,
                files = listOf("001.json"),
                ids = listOf("001"),
                contentDigest = "sha256:aaaa",
            )
        val swapped =
            GoldenBaseline(
                documentCount = RECORDED_DOCUMENTS,
                requiredFactCount = RECORDED_FACTS,
                files = listOf("001.json"),
                ids = listOf("001"),
                contentDigest = "sha256:bbbb",
            )

        assertThat(baselineMismatch(recorded, recorded)).isNull()
        assertThat(baselineMismatch(swapped, recorded)).contains("contentDigest").contains("리뷰 승인")
    }

    @Test
    @DisplayName("변환 채점 건수가 내려가면 승인 없는 갱신으로 본다")
    fun `품질 퇴보를 거부한다`() {
        val recorded =
            GoldenBaseline(
                documentCount = 1,
                requiredFactCount = 1,
                files = listOf("001.json"),
                ids = listOf("001"),
                contentDigest = "sha256:aaaa",
                quality = QualityCounts(stylePassCount = 1, factPassCount = 1),
            )
        val worse = recorded.withQuality(QualityCounts(stylePassCount = 0, factPassCount = 1))

        assertThat(baselineMismatch(worse, recorded)).contains("quality")
    }

    private fun loadRecorded(): GoldenBaseline {
        val stream =
            GoldenBaselineTest::class.java.getResourceAsStream(BASELINE_RESOURCE)
                ?: error("기준선 파일이 없다: $BASELINE_RESOURCE — 승인 없이 지워지지 않아야 한다")
        val json = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        return GoldenBaseline(
            documentCount = json.getValue("documentCount").jsonPrimitive.int,
            requiredFactCount = json.getValue("requiredFactCount").jsonPrimitive.int,
            files = json.getValue("files").jsonArray.map { it.jsonPrimitive.content },
            ids = json.getValue("ids").jsonArray.map { it.jsonPrimitive.content },
            contentDigest = json.getValue("contentDigest").jsonPrimitive.content,
            quality =
                QualityCounts(
                    stylePassCount = json.getValue("stylePassCount").jsonPrimitive.int,
                    factPassCount = json.getValue("factPassCount").jsonPrimitive.int,
                ),
        )
    }

    private companion object {
        const val BASELINE_RESOURCE: String = "/kr/easydoc/core/quality/golden-baseline.json"
        const val RECORDED_DOCUMENTS: Int = 56
        const val RECORDED_FACTS: Int = 253
    }
}
