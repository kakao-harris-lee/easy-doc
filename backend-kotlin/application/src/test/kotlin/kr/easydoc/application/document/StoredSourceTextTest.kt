package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** [StoredSourceText.structureOrBody] — 「불변식이 깨지면 예외가 아니라 전부 BODY」(계획 §1.2). */
class StoredSourceTextTest {
    @Test
    @DisplayName("structure 가 null 이면(옛 문서) 전부 BODY 로 접는다")
    fun `null 이면 전부 BODY 다`() {
        val stored = storedSourceText(structure = null)

        assertThat(stored.structureOrBody(3).kinds)
            .containsExactly(UnitKind.BODY, UnitKind.BODY, UnitKind.BODY)
    }

    @Test
    @DisplayName("structure 크기가 unitCount 와 다르면 예외 없이 전부 BODY 로 접는다")
    fun `크기가 어긋나면 전부 BODY 다`() {
        val mismatched = SourceStructure(listOf(UnitKind.LIST_ITEM))
        val stored = storedSourceText(structure = mismatched)

        assertThat(stored.structureOrBody(2).kinds).containsExactly(UnitKind.BODY, UnitKind.BODY)
    }

    @Test
    @DisplayName("structure 크기가 unitCount 와 같으면 저장된 값을 그대로 돌려준다")
    fun `크기가 같으면 저장된 값을 그대로 돌려준다`() {
        val matching = SourceStructure(listOf(UnitKind.LIST_ITEM, UnitKind.BODY))
        val stored = storedSourceText(structure = matching)

        assertThat(stored.structureOrBody(2).kinds).containsExactly(UnitKind.LIST_ITEM, UnitKind.BODY)
    }

    private fun storedSourceText(structure: SourceStructure?): StoredSourceText =
        StoredSourceText(
            documentId = UUID.randomUUID(),
            sourceFormat = SourceFormat.TEXT,
            charCount = 4,
            sourceText = EncryptedContent(byteArrayOf(0), EncryptionScheme.AES_256_GCM_V1, 1),
            workspaceId = UUID.randomUUID(),
            structure = structure,
        )
}
