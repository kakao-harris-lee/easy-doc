package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/** 원문 조회 유스케이스 — 소유 판정과 복호화 결속을 잰다. */
class DocumentSourceServiceTest {
    @Test
    @DisplayName("내 문서의 원문이 그대로 나온다 — 형식·문자 수도 같은 행에서 온다")
    fun `내 문서의 원문을 읽는다`() {
        val owner = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val documents = FakeSourceRepository()
        documents.put(owner, documentId, SourceFormat.DOCX, SOURCE)

        val view = service(documents).read(owner, documentId)

        assertThat(view.documentId).isEqualTo(documentId)
        assertThat(view.sourceFormat).isEqualTo(SourceFormat.DOCX)
        assertThat(view.charCount).isEqualTo(SOURCE.length)
        assertThat(view.sourceText.value).isEqualTo(SOURCE)
    }

    @Test
    @DisplayName("복호화 결속 인자가 **저장할 때와 같다** — 문서 식별자와 그 열이다")
    fun `복호화가 저장과 같은 인자로 묶인다`() {
        val owner = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val documents = FakeSourceRepository()
        documents.put(owner, documentId, SourceFormat.TEXT, SOURCE)
        val cipher = FakeContentCipher(writeKeyVersion = 1)

        DocumentSourceService(documents, cipher).read(owner, documentId)

        assertThat(cipher.decryptions)
            .describedAs("결속이 갈리면 실물에서 태그 검증이 실패한다")
            .containsExactly(documentId to EncryptedField.DOCUMENT_SOURCE_TEXT)
    }

    @Test
    @DisplayName("**남의 문서는 404 다**(403 아님) — 저장소에 소유자가 그대로 전달된다")
    fun `남의 문서는 404 다`() {
        val documentId = UUID.randomUUID()
        val documents = FakeSourceRepository()
        documents.put(UUID.randomUUID(), documentId, SourceFormat.TEXT, SOURCE)
        val stranger = UUID.randomUUID()

        assertThatThrownBy { service(documents).read(stranger, documentId) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(DOCUMENT_NOT_FOUND_MESSAGE)

        assertThat(documents.queries)
            .describedAs("소유자가 질의까지 가지 않으면 소유 술어를 SQL 이 질 수 없다")
            .containsExactly(stranger to documentId)
    }

    @Test
    @DisplayName("없는 문서도 **같은 404 와 같은 문구다** — 두 경우가 구분되지 않는다")
    fun `없는 문서도 같은 404 다`() {
        val owner = UUID.randomUUID()

        assertThatThrownBy { service(FakeSourceRepository()).read(owner, UUID.randomUUID()) }
            .isInstanceOf(NotFoundException::class.java)
            .hasMessage(DOCUMENT_NOT_FOUND_MESSAGE)
    }

    @Test
    @DisplayName("찾지 못한 문서는 **복호화를 시도하지 않는다** — 열 것이 없다")
    fun `없는 문서에는 복호화가 없다`() {
        val cipher = FakeContentCipher(writeKeyVersion = 1)

        runCatching { DocumentSourceService(FakeSourceRepository(), cipher).read(UUID.randomUUID(), UUID.randomUUID()) }

        assertThat(cipher.decryptions).isEmpty()
    }

    private fun service(documents: DocumentRepository): DocumentSourceService =
        DocumentSourceService(documents, FakeContentCipher(writeKeyVersion = 1))

    /** 소유자를 키의 일부로 든 원문 저장소 대역. 회전 팔은 이 경로가 부르지 않는다. */
    private class FakeSourceRepository : DocumentRepository {
        private val rows = mutableMapOf<Pair<UUID, UUID>, StoredSourceText>()

        /** 조회에 넘어온 `(소유자, 문서)` 짝 전부. 소유자가 저장소까지 갔는가를 재는 재료다. */
        val queries = mutableListOf<Pair<UUID, UUID>>()

        fun put(
            ownerId: UUID,
            documentId: UUID,
            format: SourceFormat,
            text: String,
        ) {
            rows[ownerId to documentId] =
                StoredSourceText(
                    documentId = documentId,
                    sourceFormat = format,
                    charCount = text.length,
                    // 대역 암호는 바이트를 그대로 든다 — 이 케이스가 재는 것은 결속과 왕복이다.
                    sourceText = EncryptedContent(text.toByteArray(Charsets.UTF_8), SCHEME, 1),
                )
        }

        override fun findOwnedSource(
            ownerId: UUID,
            documentId: UUID,
        ): StoredSourceText? {
            queries += ownerId to documentId
            return rows[ownerId to documentId]
        }

        override fun insert(
            ownerId: UUID,
            draft: DocumentDraft,
            sourceText: EncryptedContent,
        ) = error("조회 경로가 문서를 만들지 않는다")

        override fun listOwned(
            ownerId: UUID,
            workspaceId: UUID?,
            limit: Int,
            offset: Int,
        ) = error("조회 경로가 목록을 읽지 않는다")

        override fun lockSourceText(documentId: UUID): EncryptedContent? = error("조회 경로가 행을 잠그지 않는다")

        override fun rewriteEnvelope(
            documentId: UUID,
            expected: EncryptedContent,
            sourceText: EncryptedContent,
        ): Boolean = error("조회 경로가 봉투를 다시 쓰지 않는다")

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = error("조회 경로가 회전 후보를 고르지 않는다")

        override fun deleteOwned(
            ownerId: UUID,
            documentId: UUID,
        ): Boolean = error("조회 경로가 문서를 지우지 않는다")
    }

    private companion object {
        /** 원문에는 마스킹 **전** 값이 들어 있다 — 이 표본이 그 사실을 함께 말한다. */
        const val SOURCE: String = "민원 안내문 본문. 주민등록번호 900101-1234567 포함."

        val SCHEME: String = EncryptionScheme.AES_256_GCM_V1
    }
}
