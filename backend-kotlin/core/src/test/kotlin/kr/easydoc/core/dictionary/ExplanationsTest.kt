package kr.easydoc.core.dictionary

import kr.easydoc.core.easyread.SourceAnchor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExplanationsTest {
    @Test
    fun `같은 용어는 한 건으로 합친다`() {
        val definitions =
            listOf(
                ReviewedDefinition("서류", "서류", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "첫 번째 설명"),
                ReviewedDefinition("서류", "서류", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "두 번째 설명"),
            )

        val explanations = deriveExplanations(definitions, sourceUnits = listOf("오늘 서류를 내세요"))

        assertThat(explanations).hasSize(1)
        assertThat(explanations.single().explanation).isEqualTo("첫 번째 설명")
    }

    @Test
    fun `본문 등장 순서를 지킨다`() {
        val definitions =
            listOf(
                ReviewedDefinition("과태료", "과태료", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "규칙을 안 지켜서 내는 돈"),
                ReviewedDefinition("서류", "서류", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "준비할 서류"),
            )

        val explanations = deriveExplanations(definitions, sourceUnits = listOf("과태료와 서류"))

        assertThat(explanations.map { it.term }).containsExactly("과태료", "서류")
    }

    @Test
    fun `매치된 위치를 원문 색인으로 담는다`() {
        val definitions =
            listOf(
                ReviewedDefinition("서류", "서류", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "준비할 서류"),
            )
        val sourceUnits = listOf("오늘 서류를", "내세요")

        val explanations = deriveExplanations(definitions, sourceUnits)

        assertThat(explanations.single().sourceAnchors).isEqualTo(listOf(SourceAnchor(listOf(0), "서류")))
    }

    @Test
    fun `위치를 못 찾으면 근거 없이 남긴다`() {
        val definitions =
            listOf(
                ReviewedDefinition("서류", "구비서류", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "준비할 서류"),
            )
        val sourceUnits = listOf("오늘 서류를", "내세요")

        val explanations = deriveExplanations(definitions, sourceUnits)

        assertThat(explanations).hasSize(1)
        assertThat(explanations.single().sourceAnchors).isEmpty()
    }

    @Test
    fun `활용형이 여러 개면 색인을 합친다`() {
        val definitions =
            listOf(
                ReviewedDefinition("서류", "서류를", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "준비할 서류"),
                ReviewedDefinition("서류", "서류는", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "준비할 서류"),
            )
        val sourceUnits = listOf("오늘 서류를 내고", "다음 서류는 나중에", "관계없는 문장")

        val explanations = deriveExplanations(definitions, sourceUnits)

        val sourceUnitIndexes =
            explanations
                .single()
                .sourceAnchors
                .single()
                .sourceUnitIndexes
        assertThat(sourceUnitIndexes).containsExactly(0, 1)
    }
}
