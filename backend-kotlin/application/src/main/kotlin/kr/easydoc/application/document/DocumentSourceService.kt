package kr.easydoc.application.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.document.DocumentSourceView
import kr.easydoc.core.exceptions.NotFoundException
import java.util.UUID

/**
 * 원문 조회 유스케이스 — **내** 문서 한 건의 추출된 원문을 되읽는다.
 *
 * [DocumentService] 에 붙이지 않고 따로 선 이유: 저쪽은 「문서를 등록하고 변환을 요청한다」가
 * 변경 이유이고 네 저장소를 한 트랜잭션으로 묶는다. 조회는 그 어느 쪽도 아니라 협력자가
 * 저장소 하나와 암호 하나뿐이다(`ConversionQueryService` 가 `DocumentService` 와 갈린 것과
 * 같은 판단).
 *
 * **트랜잭션 경계를 열지 않는다.** 읽는 것이 `documents` 한 행이고 질의가 하나라 경계 안에서
 * 지킬 불변식이 없다 — 두 표를 함께 읽는 `ConversionQueryService` 와 갈리는 자리다.
 */
class DocumentSourceService(
    private val documents: DocumentRepository,
    private val cipher: ContentCipher,
) {
    /**
     * 내 문서의 원문을 읽는다. 없거나 내 것이 아니면 **404** — 저장소가 두 경우를 가르지
     * 않으므로 여기서도 가를 수 없고, 그것이 소유권 은닉의 형태다(계약 「남의 자원은 404」).
     *
     * 보존 기간이 지나 파기된 문서도 같은 갈래로 든다 — 행이 사라진 것이라 「없다」와 구분되지
     * 않는다.
     */
    fun read(
        ownerId: UUID,
        documentId: UUID,
    ): DocumentSourceView {
        val stored =
            documents.findOwnedSource(ownerId, documentId)
                ?: throw NotFoundException(DOCUMENT_NOT_FOUND_MESSAGE)
        return DocumentSourceView(
            documentId = stored.documentId,
            sourceFormat = stored.sourceFormat,
            charCount = stored.charCount,
            // 결속 인자는 저장할 때와 같아야 한다 — 문서 식별자와 그 열
            // (`DocumentService.store` 의 봉인과 짝이다).
            sourceText = cipher.decrypt(stored.sourceText, stored.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT),
        )
    }
}
