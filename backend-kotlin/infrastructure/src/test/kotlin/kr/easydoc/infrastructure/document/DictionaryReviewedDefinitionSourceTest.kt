package kr.easydoc.infrastructure.document

import kr.easydoc.core.dictionary.DefinitionReviewStatus
import kr.easydoc.core.dictionary.DictionaryEntry
import kr.easydoc.core.dictionary.DictionaryIndex
import kr.easydoc.core.dictionary.ExplanationDefinitionSource
import kr.easydoc.core.dictionary.ReplaceStrategy
import kr.easydoc.core.dictionary.RiskLevel
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.infrastructure.dictionary.DictionaryIndexHolder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** [DictionaryReviewedDefinitionSource] - 검수된 정의만 근거로 새는지, 나머지는 조용히 거절되는지 확인한다. */
class DictionaryReviewedDefinitionSourceTest {
    private fun indexOf(entries: Map<Int, DictionaryEntry>): DictionaryIndex {
        val surfaceIndex = LinkedHashMap<String, List<Int>>()
        entries.forEach { (id, entry) -> surfaceIndex[entry.term] = listOf(id) }
        return DictionaryIndex.of(entries, surfaceIndex, TEST_JOSA)
    }

    private fun entry(
        term: String,
        status: DefinitionReviewStatus,
        definition: String?,
    ) = DictionaryEntry(
        term = term,
        easyTerm = "쉬운 $term",
        strategy = ReplaceStrategy.GLOSS,
        risk = RiskLevel.NONE,
        priority = 0,
        definition = definition,
        definitionReviewStatus = status,
    )

    @Test
    @DisplayName("REVIEWED 이고 정의가 비어있지 않으면 표제어·표면형·정의를 담은 ReviewedDefinition 하나를 낸다")
    fun `검수된 정의를 그대로 낸다`() {
        val index = indexOf(mapOf(1 to entry("서류", DefinitionReviewStatus.REVIEWED, "종이 문서입니다.")))
        val holder = DictionaryIndexHolder(enabled = true) { index }
        val source = DictionaryReviewedDefinitionSource(holder)

        val result = source.find("서류를 내세요")

        assertThat(result).hasSize(1)
        val definition = result.single()
        assertThat(definition.term).isEqualTo("서류")
        assertThat(definition.surface).isEqualTo("서류")
        assertThat(definition.definitionSource).isEqualTo(ExplanationDefinitionSource.DICTIONARY_REVIEWED)
        assertThat(definition.explanation).isEqualTo("종이 문서입니다.")
    }

    @Test
    @DisplayName("UNVERIFIED(기본값) 이면 근거 없이 빈 목록을 낸다")
    fun `검수되지 않은 정의는 걸러진다`() {
        val index = indexOf(mapOf(1 to entry("서류", DefinitionReviewStatus.UNVERIFIED, "종이 문서입니다.")))
        val holder = DictionaryIndexHolder(enabled = true) { index }
        val source = DictionaryReviewedDefinitionSource(holder)

        assertThat(source.find("서류를 내세요")).isEmpty()
    }

    @Test
    @DisplayName("REVIEWED 이어도 정의가 비어있으면 빈 목록을 낸다")
    fun `정의가 빈 문자열이면 걸러진다`() {
        val index = indexOf(mapOf(1 to entry("서류", DefinitionReviewStatus.REVIEWED, "   ")))
        val holder = DictionaryIndexHolder(enabled = true) { index }
        val source = DictionaryReviewedDefinitionSource(holder)

        assertThat(source.find("서류를 내세요")).isEmpty()
    }

    @Test
    @DisplayName("사전이 꺼져 있으면(indexOrNull == null) 빈 목록이 아니라 ConfigurationException 이다")
    fun `사전이 꺼져 있으면 즉시 실패한다`() {
        val holder = DictionaryIndexHolder(enabled = false) { error("disabled 이면 loader 는 불리지 않는다") }
        val source = DictionaryReviewedDefinitionSource(holder)

        assertThatThrownBy { source.find("서류를 내세요") }
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("활용형으로 매치되면 surface 는 매치된 표기, term 은 표제어 원형이다")
    fun `활용형 매치는 표면형과 표제어를 분리해서 낸다`() {
        val entries = mapOf(1 to entry("서류", DefinitionReviewStatus.REVIEWED, "종이 문서입니다."))
        val surfaceIndex = mapOf("서류" to listOf(1), "서류는" to listOf(1))
        val index = DictionaryIndex.of(entries, surfaceIndex, TEST_JOSA)
        val holder = DictionaryIndexHolder(enabled = true) { index }
        val source = DictionaryReviewedDefinitionSource(holder)

        val result = source.find("서류는 꼭 내세요")

        assertThat(result).hasSize(1)
        val definition = result.single()
        assertThat(definition.term).isEqualTo("서류")
        assertThat(definition.surface).isEqualTo("서류는")
    }

    private companion object {
        val TEST_JOSA: List<String> = listOf("은", "는", "이", "가", "을", "를")
    }
}
