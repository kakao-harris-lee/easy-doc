package kr.easydoc.core.quality

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `data/golden/documents/` 스키마와 원문 사실 잔존을 외부 API 없이 검사한다. */
class GoldenSchemaTest {
    private val corpus: GoldenCorpus = GoldenDocumentLoader.loadDirectory(GoldenDocumentLoader.documentsDirectory())

    @Test
    @DisplayName("승인된 골든 JSON 전건이 평가 입력 스키마를 통과한다")
    fun `스키마가 전건 통과한다`() {
        assertThat(validateCorpus(corpus)).isEmpty()
        assertThat(corpus.documents).isNotEmpty()
        assertThat(corpus.documents.map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    @DisplayName("required_facts 가 원문에 실제로 있다")
    fun `필수 사실이 원문에 있다`() {
        val missing =
            corpus.documents.flatMap { document ->
                evaluateFacts(document.id, document.sourceText, document.requiredFacts)
                    .missing
                    .map { "${document.id}:${it.canonical}" }
            }

        assertThat(missing).isEmpty()
    }

    @Test
    @DisplayName("입력 스키마 객체는 본문을 toString 에 싣지 않는다")
    fun `본문이 문자열에 없다`() {
        val sample = corpus.documents.first()
        assertThat(sample.sourceText).isNotBlank()
        assertThat(sample.toString()).doesNotContain(sample.sourceText)
        assertThat(sample.toString()).doesNotContain(sample.title)
    }
}
