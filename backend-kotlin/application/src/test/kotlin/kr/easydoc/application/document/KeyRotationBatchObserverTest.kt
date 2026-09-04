package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.Conversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.Document
import kr.easydoc.core.document.DocumentListing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `KeyRotationBatch.run()` 이 가족을 순회하다 **도중에 실패해도** 이미 끝난 가족의 결과는
 * observer 에 이미 남아 있다는 것을 잰다 — 독립 코드 리뷰(PR #15) MEDIUM 지적의 회귀 고정판.
 *
 * 실 DB 를 쓰지 않는다 — 이 케이스가 재는 것은 순서·예외 전파뿐이고, 회전 로직 자체(가족
 * 넷 실제 회전·재실행 idempotent)는 `infrastructure` 의 `KeyRotationBatchTest` 가 잰다.
 */
class KeyRotationBatchObserverTest {
    @Test
    @DisplayName("세 번째 가족(conversions)이 던지면 앞선 두 가족은 이미 관측됐고, 예외는 그대로 전파된다")
    fun `가족 중간 실패에도 앞선 결과는 이미 관측됐다`() {
        val failure = RuntimeException("conversions 후보 조회 실패")
        val recorded = mutableListOf<FamilyRotationOutcome>()
        val stores =
            SealedStores(
                documents = NoCandidateDocuments,
                originals = NoCandidateOriginals,
                conversions = FailingConversions(failure),
                feedback = UnreachableFeedback,
            )
        val batch =
            KeyRotationBatch(
                stores = stores,
                rotation = EnvelopeRotation(stores = stores, cipher = StubCipher, transaction = PassthroughTransaction),
                cipher = StubCipher,
                policy = KeyRotationPolicy(batchSize = 10),
                observer = KeyRotationObserver { outcome -> recorded += outcome },
            )

        assertThatThrownBy { batch.run() }.isSameAs(failure)

        assertThat(recorded)
            .describedAs("세 번째 가족에서 던졌는데 앞선 두 가족의 결과가 observer 에 남지 않았다")
            .extracting<String> { it.family }
            .containsExactly("documents", "document_originals")
    }

    /** 후보가 없다 — `idsOlderThan` 만 의미가 있고 나머지는 이 케이스에서 불리지 않는다. */
    private object NoCandidateDocuments : DocumentRepository {
        override fun insert(
            ownerId: UUID,
            draft: DocumentDraft,
            sourceText: EncryptedContent,
        ): Document = error(UNREACHABLE)

        override fun listOwned(
            ownerId: UUID,
            workspaceId: UUID?,
            limit: Int,
            offset: Int,
        ): List<DocumentListing> = error(UNREACHABLE)

        override fun findOwnedSource(
            ownerId: UUID,
            documentId: UUID,
        ): StoredSourceText? = error(UNREACHABLE)

        override fun lockSourceText(documentId: UUID): EncryptedContent? = error(UNREACHABLE)

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            sourceText: EncryptedContent,
        ): Boolean = error(UNREACHABLE)

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = emptyList()

        override fun deleteOwned(
            ownerId: UUID,
            documentId: UUID,
        ): Boolean = error(UNREACHABLE)
    }

    /** 후보가 없다 — `documentIdsOlderThan` 만 의미가 있다. */
    private object NoCandidateOriginals : DocumentOriginalRepository {
        override fun insert(
            ownerId: UUID,
            documentId: UUID,
            original: StoredOriginal,
        ) = error(UNREACHABLE)

        override fun findOwned(
            ownerId: UUID,
            documentId: UUID,
        ): StoredOriginal? = error(UNREACHABLE)

        override fun lockOriginal(documentId: UUID): EncryptedContent? = error(UNREACHABLE)

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            original: EncryptedContent,
        ): Boolean = error(UNREACHABLE)

        override fun documentIdsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = emptyList()
    }

    /** 후보 조회 자체가 던진다 — 세 번째 가족의 실패를 재현하는 자리. */
    private class FailingConversions(private val failure: RuntimeException) : ConversionRepository {
        override fun insertPending(
            id: UUID,
            documentId: UUID,
            scheme: String,
            keyVersion: Int,
        ): Conversion = error(UNREACHABLE)

        override fun findOwnedResult(
            ownerId: UUID,
            conversionId: UUID,
        ): StoredConversion? = error(UNREACHABLE)

        override fun findOwnedExport(
            ownerId: UUID,
            conversionId: UUID,
        ): StoredExport? = error(UNREACHABLE)

        override fun lockEnvelope(conversionId: UUID): ConversionEnvelope? = error(UNREACHABLE)

        override fun rewriteEnvelope(
            expected: ConversionEnvelope,
            scheme: String,
            keyVersion: Int,
            ciphertexts: ConversionCiphertexts,
        ): Boolean = error(UNREACHABLE)

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = throw failure

        override fun lockOwnedForReview(
            ownerId: UUID,
            conversionId: UUID,
        ): LockedConversion? = error(UNREACHABLE)

        override fun saveReview(
            ownerId: UUID,
            expected: ConversionEnvelope,
            requiredStatus: ConversionStatus,
            updated: ConversionEnvelope,
        ): Boolean = error(UNREACHABLE)
    }

    /** 네 번째 가족 — 세 번째가 던지므로 이 자리까지 순회가 닿지 않는다. */
    private object UnreachableFeedback : ConversionFeedbackRepository {
        override fun upsert(
            ownerId: UUID,
            feedback: StoredFeedback,
        ): Instant = error(UNREACHABLE)

        override fun lockComment(conversionId: UUID): LockedFeedbackComment? = error(UNREACHABLE)

        override fun rewriteComment(
            conversionId: UUID,
            expected: EncryptedContent,
            comment: EncryptedContent,
        ): Boolean = error(UNREACHABLE)

        override fun conversionIdsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = error(UNREACHABLE)
    }

    /** `rotateOne` 이 이 케이스에서 한 번도 불리지 않으므로 값 자체는 임의여도 된다. */
    private object StubCipher : ContentCipher {
        override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1
        override val writeKeyVersion: Int = 2

        override fun encryptBytes(
            plain: PlainBytes,
            record: UUID,
            field: EncryptedField,
        ): EncryptedContent = error(UNREACHABLE)

        override fun decryptBytes(
            content: EncryptedContent,
            record: UUID,
            field: EncryptedField,
        ): PlainBytes = error(UNREACHABLE)

        override fun encrypt(
            plain: PlainBody,
            record: UUID,
            field: EncryptedField,
        ): EncryptedContent = error(UNREACHABLE)

        override fun decrypt(
            content: EncryptedContent,
            record: UUID,
            field: EncryptedField,
        ): PlainBody = error(UNREACHABLE)
    }

    /** `EnvelopeRotation` 의 트랜잭션 경계 — 이 케이스에서 열릴 일이 없다. */
    private object PassthroughTransaction : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private companion object {
        const val UNREACHABLE = "이 케이스에서는 불리지 않아야 한다 — 후보가 없거나(가족 1·2) 던진다(가족 3)"
    }
}
