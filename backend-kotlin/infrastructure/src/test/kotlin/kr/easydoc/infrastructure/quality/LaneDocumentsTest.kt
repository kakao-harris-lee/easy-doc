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
    @DisplayName("빈 값도 미설정과 같이 전건으로 접는다")
    fun `빈 값이면 전건`() {
        assertThat(LaneDocuments.select(env("  "), corpus).documents).hasSize(4)
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
        val selection = LaneDocuments.select(env(" 072 ,\t023 "), corpus)

        assertThat(selection.documents.map(GoldenDocument::id)).containsExactly("023", "072")
    }

    @Test
    @DisplayName("코퍼스에 없는 id 는 거절한다 — 고른 줄 알았던 문서가 조용히 빠지지 않는다")
    fun `모르는 id 는 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("023,999"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining("999")
    }

    @Test
    @DisplayName("세 자리 숫자가 아니면 거절한다")
    fun `형식이 틀린 id 는 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("23,070"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining("23")
    }

    @Test
    @DisplayName("빈 항목도 형식 위반으로 거절한다 — 쉼표를 하나 더 찍은 값을 조용히 넘기지 않는다")
    fun `빈 항목은 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("023,,072"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
    }

    @Test
    @DisplayName("같은 id 를 두 번 적으면 거절한다 — 반복은 EASYDOC_LANE_RUNS 가 정한다")
    fun `중복 id 는 거절한다`() {
        assertThatThrownBy { LaneDocuments.select(env("023,072,023"), corpus) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneDocuments.ENV)
            .hasMessageContaining("023")
    }

    @Test
    @DisplayName("측정 조건 줄에는 몇 건 중 몇 건을 어떤 id 로 골랐는지가 남는다")
    fun `부분 선택 설명`() {
        assertThat(LaneDocuments.select(env("087,023"), corpus).description)
            .isEqualTo("documents=2/4[023,087]")
    }

    private fun env(value: String): (String) -> String? = mapOf(LaneDocuments.ENV to value)::get

    private val corpus: List<GoldenDocument> =
        listOf("023", "070", "072", "087").map { id ->
            GoldenDocument(
                id = id,
                title = "문서 $id",
                category = "안내",
                synthetic = true,
                sourceText = "원문 $id",
                requiredFacts = emptyList(),
            )
        }
}
