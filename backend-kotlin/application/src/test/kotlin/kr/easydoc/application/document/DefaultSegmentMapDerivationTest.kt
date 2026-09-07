package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import kr.easydoc.core.segment.compliantSourceUnits
import kr.easydoc.core.segment.splitUnits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `DefaultSegmentMapDerivation.deriveOrNull` 이 채우는 `compliantSourceUnits` — 계획 §11
 * 수용 기준 C2. `kr.easydoc.core.segment.CompliantSourceUnitsTest` 의 C1 규칙이 여기서도
 * 그대로 성립하고, 원천은 원문임을 잰다(§11.1 — 화면이 읽는 문단이 그 대상이다).
 */
class DefaultSegmentMapDerivationTest {
    private val cipher = FakeContentCipher(writeKeyVersion = 1)
    private val derivation = DefaultSegmentMapDerivation(cipher)
    private val documentId: UUID = UUID.randomUUID()

    @Test
    @DisplayName("C2 — compliantSourceUnits 가 원문에 C1 규칙을 적용한 색인과 같다")
    fun `유도된 지도가 원문 기준 통과 색인을 낸다`() {
        val source = "짧은 문장입니다.\n\n   \n" + "가".repeat(60) + "."
        val stored = storedSourceText(source)

        val map = derivation.deriveOrNull(stored, PlainBody("쉬운 글 한 줄"))

        assertThat(map).isNotNull()
        assertThat(map!!.compliantSourceUnits).isEqualTo(compliantSourceUnits(splitUnits(source)))
        assertThat(map.compliantSourceUnits).containsExactly(0)
    }

    @Test
    @DisplayName("C2 — source 가 null 이면 여전히 null 이다")
    fun `원문이 없으면 null`() {
        assertThat(derivation.deriveOrNull(null, PlainBody("쉬운 글"))).isNull()
    }

    @Test
    @DisplayName("C2 — body 가 null 이면 여전히 null 이다")
    fun `본문이 없으면 null`() {
        assertThat(derivation.deriveOrNull(storedSourceText("원문"), null)).isNull()
    }

    @Test
    @DisplayName("S8-3 — 저장된 구조가 있으면 sourceUnitKinds 가 그 종류를 그대로 옮긴다")
    fun `유도된 지도가 저장된 구조를 옮긴다`() {
        val source = "일반 문장\n1. 목록 항목"
        val structure = SourceStructure(listOf(UnitKind.BODY, UnitKind.LIST_ITEM))
        val stored = storedSourceText(source, structure)

        val map = derivation.deriveOrNull(stored, PlainBody("쉬운 글"))

        assertThat(map).isNotNull()
        assertThat(map!!.sourceUnitKinds).isEqualTo(listOf(UnitKind.BODY, UnitKind.LIST_ITEM))
    }

    @Test
    @DisplayName("S8-3 — 저장된 구조가 없으면(null) sourceUnitKinds 는 전부 BODY 다")
    fun `구조가 없으면 전부 BODY`() {
        val source = "한 줄\n또 한 줄"
        val stored = storedSourceText(source, structure = null)

        val map = derivation.deriveOrNull(stored, PlainBody("쉬운 글"))

        assertThat(map).isNotNull()
        assertThat(map!!.sourceUnitKinds).isEqualTo(listOf(UnitKind.BODY, UnitKind.BODY))
    }

    @Test
    @DisplayName("S8-3 — 저장된 구조 크기가 원본 단위 수와 어긋나면 전부 BODY 로 접는다")
    fun `구조 크기가 어긋나면 전부 BODY`() {
        val source = "한 줄\n또 한 줄\n마지막 줄"
        val mismatched = SourceStructure(listOf(UnitKind.LIST_ITEM))
        val stored = storedSourceText(source, mismatched)

        val map = derivation.deriveOrNull(stored, PlainBody("쉬운 글"))

        assertThat(map).isNotNull()
        assertThat(map!!.sourceUnitKinds).isEqualTo(listOf(UnitKind.BODY, UnitKind.BODY, UnitKind.BODY))
    }

    private fun storedSourceText(
        text: String,
        structure: SourceStructure? = null,
    ): StoredSourceText =
        StoredSourceText(
            documentId = documentId,
            sourceFormat = SourceFormat.TEXT,
            charCount = text.length,
            sourceText = cipher.encrypt(PlainBody(text), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT),
            workspaceId = UUID.randomUUID(),
            structure = structure,
        )
}
