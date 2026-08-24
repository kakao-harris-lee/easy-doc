package kr.easydoc.core.quality

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 기준선은 커밋된 파일이다. 일반 테스트가 그 파일을 다시 쓰지 않으므로,
 * 건수가 바뀌면 이 테스트가 실패하고 리뷰 승인 후에만 파일을 고친다.
 */
class GoldenBaselineTest {
    @Test
    @DisplayName("현재 골든 코퍼스가 커밋된 기준선과 같다")
    fun `기준선과 같다`() {
        val current = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory()).baseline
        val recorded = loadRecorded()

        assertThat(baselineMismatch(current, recorded))
            .describedAs(
                "기준선이 바뀌면 이 파일이 아니라 리뷰에서 승인한다: $BASELINE_RESOURCE",
            ).isNull()
        assertThat(current.documentCount).isEqualTo(recorded.documentCount)
        assertThat(current.requiredFactCount).isEqualTo(recorded.requiredFactCount)
    }

    @Test
    @DisplayName("건수가 다르면 승인 없는 갱신으로 본다")
    fun `불일치를 거부한다`() {
        val recorded = GoldenBaseline(documentCount = RECORDED_DOCUMENTS, requiredFactCount = RECORDED_FACTS)
        val drifted = GoldenBaseline(documentCount = RECORDED_DOCUMENTS + 1, requiredFactCount = RECORDED_FACTS)

        assertThat(baselineMismatch(recorded, recorded)).isNull()
        assertThat(baselineMismatch(drifted, recorded)).contains("리뷰 승인")
    }

    private fun loadRecorded(): GoldenBaseline {
        val stream =
            GoldenBaselineTest::class.java.getResourceAsStream(BASELINE_RESOURCE)
                ?: error("기준선 파일이 없다: $BASELINE_RESOURCE — 승인 없이 지워지지 않아야 한다")
        val json = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        return GoldenBaseline(
            documentCount = json.getValue("documentCount").jsonPrimitive.int,
            requiredFactCount = json.getValue("requiredFactCount").jsonPrimitive.int,
        )
    }

    private companion object {
        const val BASELINE_RESOURCE: String = "/kr/easydoc/core/quality/golden-baseline.json"
        const val RECORDED_DOCUMENTS: Int = 56
        const val RECORDED_FACTS: Int = 253
    }
}
