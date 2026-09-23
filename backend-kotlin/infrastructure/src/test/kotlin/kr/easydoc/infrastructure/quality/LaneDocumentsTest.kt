package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.quality.GoldenDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 문서 필터의 선택 규칙을 유료 호출 없이 고정한다. [LaneRunsTest] 와 같은 방침이다 — 잘못된
 * 값은 유료 호출이 한 건이라도 나가기 전에 거절해야 한다.
 */
class LaneDocumentsTest {
    @Test
    @DisplayName("미설정이면 코퍼스 전건이다 — 필터를 쓰지 않는 기존 실행과 같다")
    fun `미설정이면 전건`() {
        val selection = LaneDocuments.select({ null }, corpus)

        assertThat(selection.documents.map(GoldenDocument::id)).containsExactly("023", "070", "072", "087")
        assertThat(selection.description).isEqualTo("documents=all(4)")
    }

    @Test
    @DisplayName("공백 값은 전건이 아니라 오류다 — 의도치 않게 전건을 사지 않게")
    fun `공백 값은 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("  "), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining("비어 있으면 전건이 아니라 오류다")
    }

    @Test
    @DisplayName("고른 문서만 돌되 순서는 환경변수가 아니라 코퍼스 순서다")
    fun `부분 선택은 코퍼스 순서`() {
        val selection = LaneDocuments.select(env("087,023,072"), corpus)

        assertThat(selection.documents.map(GoldenDocument::id)).containsExactly("023", "072", "087")
    }

    @Test
    @DisplayName("id 주변 공백은 무시한다 — 여러 줄로 나눠 적은 값도 그대로 받는다")
    fun `공백을 다듬는다`() {
        val selection = LaneDocuments.select(env(" 072 ,\r\n\t023 "), corpus)

        assertThat(selection.documents.map(GoldenDocument::id)).containsExactly("023", "072")
    }

    @Test
    @DisplayName("코퍼스 전체를 나열하면 미설정과 같은 documents=all(N) 문구로 접힌다")
    fun `전체 나열은 all 로 접힌다`() {
        val selection = LaneDocuments.select(env("087,023,072,070"), corpus)

        assertThat(selection.documents.map(GoldenDocument::id)).containsExactly("023", "070", "072", "087")
        assertThat(selection.description).isEqualTo("documents=all(4)")
    }

    @Test
    @DisplayName("GoldenDocumentLoader.SAFE_DOCUMENT_ID 문법만 맞으면 세 자리가 아닌 id 도 선택된다")
    fun `형식만 맞으면 세 자리가 아닌 id 도 선택된다`() {
        val corpusWithLongId = corpus + goldenDocument("001_v2.final-draft")

        val selection = LaneDocuments.select(env("001_v2.final-draft"), corpusWithLongId)

        assertThat(selection.documents.map(GoldenDocument::id)).containsExactly("001_v2.final-draft")
    }

    @Test
    @DisplayName("코퍼스에 없는 id 는 거절한다 — 고른 줄 알았던 문서가 조용히 빠지지 않는다")
    fun `모르는 id 는 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("023,999"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining(UNKNOWN_REASON)
            .hasMessageContaining("'999'")
    }

    @Test
    @DisplayName("코퍼스에 없는 id 가 여럿이면 모두 나열한다")
    fun `모르는 id 를 모두 나열한다`() {
        assertThatThrownBy { LaneDocuments.select(env("998,999"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(UNKNOWN_REASON)
            .hasMessageContaining("'998'")
            .hasMessageContaining("'999'")
    }

    @Test
    @DisplayName("문법을 어기면 거절한다")
    fun `형식이 틀린 id 는 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("023!,070"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining(FORMAT_REASON)
            .hasMessageContaining("'023!'")
    }

    @Test
    @DisplayName("형식과 미존재를 동시에 어기면 형식 사유를 먼저 알린다")
    fun `형식 위반이 미존재보다 우선한다`() {
        assertThatThrownBy { LaneDocuments.select(env("a b,070"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(FORMAT_REASON)
            .hasMessageContaining("'a b'")
            .hasMessageNotContaining(UNKNOWN_REASON)
    }

    @Test
    @DisplayName("빈 항목도 형식 위반으로 거절한다 — 쉼표를 하나 더 찍은 값을 조용히 넘기지 않는다")
    fun `빈 항목은 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("023,,072"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining(FORMAT_REASON)
    }

    @Test
    @DisplayName("같은 id 를 두 번 적으면 거절한다 — 반복은 EASYDOC_LANE_RUNS 가 정한다")
    fun `중복 id 는 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("023,072,023"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining(DUPLICATE_REASON)
            .hasMessageContaining("'023'")
    }

    @Test
    @DisplayName("중복이면서 코퍼스에도 없으면 중복 사유를 먼저 알린다")
    fun `중복이 미존재보다 우선한다`() {
        assertThatThrownBy { LaneDocuments.select(env("999,999"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(DUPLICATE_REASON)
            .hasMessageNotContaining(UNKNOWN_REASON)
    }

    @Test
    @DisplayName("측정 조건 줄에는 몇 건 중 몇 건을 어떤 id 로 골랐는지가 남는다")
    fun `부분 선택 설명`() {
        assertThat(LaneDocuments.select(env("087,023"), corpus).description)
            .isEqualTo("documents=2/4[023,087]")
    }

    private fun env(value: String): (String) -> String? = mapOf(LaneDocuments.ENV to value)::get

    private val corpus: List<GoldenDocument> = listOf("023", "070", "072", "087").map(::goldenDocument)

    private fun goldenDocument(id: String): GoldenDocument =
        GoldenDocument(
            id = id,
            title = "문서 $id",
            category = "안내",
            synthetic = true,
            sourceText = "원문 $id",
            requiredFacts = emptyList(),
        )

    private companion object {
        /** [LaneDocuments] 의 rejectionOf 세 사유 중 형식 위반 — 우선순위 검증에 정확한 사유로 비교하려고 복제한다. */
        const val FORMAT_REASON = "문서 id 문법에 맞지 않는다(GoldenDocumentLoader.SAFE_DOCUMENT_ID)"

        /** [LaneDocuments] 의 rejectionOf 세 사유 중 중복. */
        const val DUPLICATE_REASON = "같은 문서 id 가 두 번 이상 있다"

        /** [LaneDocuments] 의 rejectionOf 세 사유 중 미존재. */
        const val UNKNOWN_REASON = "골든 코퍼스에 없는 문서 id 다"
    }
}
