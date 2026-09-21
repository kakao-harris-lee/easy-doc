package kr.easydoc.application.document

import kr.easydoc.application.dictionary.ReviewedDefinitionSource
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.dictionary.ExplanationDefinitionSource
import kr.easydoc.core.dictionary.ReviewedDefinition
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ExplanationsServiceTest {
    @Test
    fun `꺼져 있으면 404 다`() {
        val world = World(enabled = false)
        val conversionId = world.seedDone()

        assertThatThrownBy { world.service.read(OWNER, conversionId) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `완료 전이면 409 다`() {
        val world = World()
        val conversionId = world.seedPending()

        assertThatThrownBy { world.service.read(OWNER, conversionId) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `검수된 정의가 없으면 빈 목록이다`() {
        val world = World()
        val conversionId = world.seedDone(sourceText = "오늘 서류를 내세요", easyText = "서류를 내야 합니다.")

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.explanations).isEmpty()
        assertThat(view.conversionId).isEqualTo(conversionId)
    }

    @Test
    fun `검수된 정의가 있으면 용어와 설명을 돌려준다`() {
        val world = World()
        val conversionId = world.seedDone(sourceText = "오늘 서류를 내세요", easyText = "서류를 내야 합니다.")
        world.definitionsResult =
            listOf(ReviewedDefinition("서류", "서류", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "준비할 서류"))

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.explanations).hasSize(1)
        val explanation = view.explanations.single()
        assertThat(explanation.term).isEqualTo("서류")
        assertThat(explanation.explanation).isEqualTo("준비할 서류")
        assertThat(explanation.sourceAnchors).isNotEmpty()
    }

    @Test
    fun `원문을 못 읽어도 설명은 돌려주고 근거만 빈다`() {
        val world = World()
        val conversionId = world.seedDoneWithoutSource(easyText = "서류를 내야 합니다.")
        world.definitionsResult =
            listOf(ReviewedDefinition("서류", "서류", ExplanationDefinitionSource.DICTIONARY_REVIEWED, "준비할 서류"))

        val view = world.service.read(OWNER, conversionId)

        assertThat(view.explanations).hasSize(1)
        val explanation = view.explanations.single()
        assertThat(explanation.explanation).isEqualTo("준비할 서류")
        assertThat(explanation.sourceAnchors).isEmpty()
    }

    private class World(enabled: Boolean = true) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        private val originals = FakeDocumentOriginalRepository(transaction)
        val conversions = FakeConversionRepository(transaction, originals)
        val documents = FakeQueryDocumentRepository(transaction)
        var definitionsResult: List<ReviewedDefinition> = emptyList()
        private val definitions = ReviewedDefinitionSource { definitionsResult }
        val service =
            ExplanationsService(enabled, conversions, definitions, documents, cipher, transaction)

        fun seedDone(
            sourceText: String = "오늘 서류를 내세요",
            easyText: String = "서류를 내야 합니다.",
        ): UUID {
            val conversionId = UUID.randomUUID()
            val documentId = UUID.randomUUID()
            seedConversion(conversionId, documentId, ConversionStatus.DONE, easyText)
            documents.seed(OWNER, documentId, sourceText)
            return conversionId
        }

        fun seedDoneWithoutSource(easyText: String): UUID {
            val conversionId = UUID.randomUUID()
            val documentId = UUID.randomUUID()
            seedConversion(conversionId, documentId, ConversionStatus.DONE, easyText)
            return conversionId
        }

        fun seedPending(): UUID {
            val conversionId = UUID.randomUUID()
            val documentId = UUID.randomUUID()
            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    conversionId,
                    documentId,
                    ConversionStatus.PENDING,
                    SourceFormat.TEXT,
                    false,
                    ConversionCiphertexts(null, null),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                )
            return conversionId
        }

        private fun seedConversion(
            conversionId: UUID,
            documentId: UUID,
            status: ConversionStatus,
            easyText: String,
        ) {
            val encryptedEasyText =
                cipher.encrypt(PlainBody(easyText), conversionId, EncryptedField.CONVERSION_EASY_TEXT)
            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    conversionId,
                    documentId,
                    status,
                    SourceFormat.TEXT,
                    false,
                    ConversionCiphertexts(encryptedEasyText, null),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                )
        }
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c2")
    }
}
