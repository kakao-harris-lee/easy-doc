package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.MAX_UPLOAD_BYTES
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.document.resolveTitle
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.exceptions.UploadTooLargeException
import java.util.UUID

/** 업로드 접수 결과. 계약 `DocumentCreatedResponse` 의 네 필드 그대로다. */
data class AcceptedUpload(
    val documentId: UUID,
    val conversionId: UUID,
    val status: ConversionStatus,
    val charCount: Int,
)

/** 문서 등록 유스케이스 — 붙여넣기·파일 두 입력을 받아 **저장하고 작업을 등록한다.** */
class DocumentService(
    private val storage: DocumentStorage,
    private val workspaces: WorkspaceLookup,
    private val cipher: ContentCipher,
    private val extractor: DocumentTextExtractor,
    private val transaction: TransactionRunner,
) {
    /** 붙여넣은 본문으로 문서를 만들고 변환을 요청한다. */
    fun createFromText(
        ownerId: UUID,
        text: String,
        title: String?,
        workspaceId: UUID?,
    ): AcceptedUpload {
        if (text.isBlank()) throw InvalidInputException(EMPTY_BODY_MESSAGE)
        // 제목을 안 주면 대체 제목이다. 본문은 제목이 되지 않는다.
        return store(ownerId, text, SourceFormat.TEXT, title) { workspaceId }
    }

    /**
     * 업로드 파일에서 본문을 뽑아 문서를 만들고 변환을 요청한다.
     *
     * **작업 공간 식별자를 파싱하지 않은 원문으로 받는다** — 인자 자리에서 파싱하면 언어의
     * 인자 평가 순서가 계약 검사 순서를 앞지른다.
     */
    fun createFromFile(
        ownerId: UUID,
        filename: String?,
        bytes: ByteArray,
        title: String?,
        rawWorkspaceId: String?,
    ): AcceptedUpload {
        // 크기 판정이 추출보다 먼저다 — 계약이 정한 순서이고, 상한을 넘는 바이트를 파서에
        // 넘기지 않는 것이 압축 폭탄 방어의 첫 단계이기도 하다(I-10).
        if (bytes.size > MAX_UPLOAD_BYTES) throw UploadTooLargeException(UPLOAD_TOO_LARGE_MESSAGE)
        val extracted = extractor.extract(filename, bytes)
        // 빈 docx·hwpx 는 예외 없이 빈 문자열을 돌려준다 — 빈 문서 판정은 추출 결과로 한다.
        if (extracted.text.isBlank()) throw DocumentExtractionException(NO_TEXT_IN_DOCUMENT_MESSAGE)
        // `filename` 은 여기서 끝난다 — 추출기가 형식을 가리는 데 썼고, 그 아래로 흐르지 않는다.
        return store(ownerId, extracted.text, extracted.format, title) { parseWorkspaceId(rawWorkspaceId) }
    }

    /** 내 문서 목록을 최신순으로 돌려준다. 작업 공간을 주면 그 안만 본다. */
    fun list(
        ownerId: UUID,
        workspaceId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DocumentListing> {
        if (workspaceId != null) requireOwnedWorkspace(ownerId, workspaceId)
        return storage.documents.listOwned(ownerId, workspaceId, limit + 1, offset)
    }

    /** 문서 한 건을 **즉시 파기한다.** 보존 기간(30일)을 기다리지 않는 경로이고 복구 수단이 없다. */
    fun delete(
        ownerId: UUID,
        documentId: UUID,
    ) {
        transaction.inTransaction {
            // 0행은 「없다」와 「남의 것」을 합친 상태다. 저장소가 그 둘을 가르지 않으므로
            // 여기서도 가를 수 없고, 그것이 소유권 은닉의 형태다.
            if (!storage.documents.deleteOwned(ownerId, documentId)) {
                throw NotFoundException(DOCUMENT_NOT_FOUND_MESSAGE)
            }
        }
    }

    /**
     * 길이를 판정하고, 한 트랜잭션 안에서 **소유권 확인 → 문서 → 변환 → 작업**을 만든다.
     *
     * [requestedWorkspaceId] 는 **지연 평가다** — 형식 판정의 자리를 아래 한 줄로 못박는다.
     */
    private fun store(
        ownerId: UUID,
        text: String,
        sourceFormat: SourceFormat,
        givenTitle: String?,
        requestedWorkspaceId: () -> UUID?,
    ): AcceptedUpload {
        val charCount = charCountOf(text)
        if (charCount > MAX_CONVERTIBLE_CHARS) throw InvalidInputException(BODY_TOO_LONG_MESSAGE)

        // 작업 공간 단계 — 형식(422) 다음 소유권(404). 형식은 여기, 소유권은 트랜잭션 안이다.
        val workspaceId = requestedWorkspaceId()

        return transaction.inTransaction {
            val resolvedWorkspaceId = resolveWorkspaceId(ownerId, workspaceId)
            val documentId = UUID.randomUUID()
            val conversionId = UUID.randomUUID()

            val draft =
                DocumentDraft(
                    id = documentId,
                    workspaceId = resolvedWorkspaceId,
                    // **본문(`text`)도 파일 이름도 넘기지 않는다.** 제목은 평문 컬럼이고 이
                    // 시점에는 마스킹이 돌지 않았다 — 게이트 27 Critical ① + 2026-08-20 재판정.
                    title = resolveTitle(givenTitle),
                    sourceFormat = sourceFormat,
                    charCount = charCount,
                )
            // 결속(record + column)을 여기서 정한다. 행 식별자가 AEAD 에 실리므로 UUID 를
            // 먼저 만들어야 하고, 그래서 저장소가 아니라 이 자리가 암호화한다(계획 §4.1).
            val sealed = cipher.encrypt(PlainBody(text), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)

            storage.documents.insert(ownerId, draft, sealed)
            val conversion =
                storage.conversions.insertPending(
                    id = conversionId,
                    documentId = documentId,
                    scheme = cipher.writeScheme,
                    keyVersion = cipher.writeKeyVersion,
                )
            storage.queue.enqueue(conversionId)

            AcceptedUpload(
                documentId = documentId,
                conversionId = conversionId,
                status = conversion.status,
                charCount = charCount,
            )
        }
    }

    /** 폼의 `workspace_id` 를 식별자로 바꾼다. 빈 문자열·부재는 「지정 없음」이다. */
    private fun parseWorkspaceId(value: String?): UUID? {
        if (value.isNullOrEmpty()) return null
        return runCatching { UUID.fromString(value) }
            .getOrElse { throw InvalidInputException(INVALID_WORKSPACE_ID_MESSAGE) }
    }

    /** 문서를 담을 작업 공간을 정한다. 지정이 없으면 기본(가장 먼저 만든) 공간이다. */
    private fun resolveWorkspaceId(
        ownerId: UUID,
        workspaceId: UUID?,
    ): UUID =
        if (workspaceId == null) {
            workspaces.findDefaultId(ownerId) ?: throw StorageException(NO_WORKSPACE_MESSAGE)
        } else {
            requireOwnedWorkspace(ownerId, workspaceId)
        }

    private fun requireOwnedWorkspace(
        ownerId: UUID,
        workspaceId: UUID,
    ): UUID =
        workspaces.findOwnedId(ownerId, workspaceId)
            ?: throw NotFoundException(WORKSPACE_NOT_FOUND_FOR_DOCUMENT_MESSAGE)
}
