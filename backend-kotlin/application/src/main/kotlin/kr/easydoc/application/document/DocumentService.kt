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

/**
 * 업로드 접수 결과. 계약 `DocumentCreatedResponse` 의 네 필드 그대로다.
 *
 * `status` 가 언제나 [ConversionStatus.PENDING] 인데도 실어 보내는 이유는 계약이 그렇게
 * 정했기 때문이다 — 202 는 "접수됐고 변환은 아직 시작 전"이라는 뜻이고, 그 사실을 응답이
 * 스스로 말한다.
 *
 * 문자열 필드가 하나도 없어 `data class` 로 두어도 `toString()` 이 샐 것이 없다.
 */
data class AcceptedUpload(
    val documentId: UUID,
    val conversionId: UUID,
    val status: ConversionStatus,
    val charCount: Int,
)

/**
 * 문서 등록 유스케이스 — 붙여넣기·파일 두 입력을 받아 **저장하고 작업을 등록한다.**
 *
 * 요구 정본: 계약 `paths./documents.post`·`get`, `x-input-limits`,
 * `migration-safety-gate` I-4·I-7·I-10.
 *
 * ## 검사 순서를 계약이 정한다
 *
 * *"파일 크기(413) → 추출(422) → 본문 길이(422) → 작업 공간 소유권(404) → 저장"*.
 * 마지막 순서에 이유가 붙어 있다 — **작업 공간 확인이 저장보다 먼저여야** 거절당한 업로드가
 * 기본 공간에 남지 않는다.
 *
 * 길이 판정을 두 입력이 **만나는 자리**에 둔 것도 요구다. 입력 방식에 따라 변환 가능
 * 여부가 달라지면 사용자에게 설명할 수 없다.
 *
 * ## 저장·등록이 한 트랜잭션이다
 *
 * 문서 행 · 변환 행 · 작업 행이 같은 트랜잭션에서 확정된다(계획 §4.4). 큐가 같은
 * PostgreSQL 이므로 "DB 커밋 성공, 큐 등록 실패" 간극이 **구조적으로** 생기지 않는다.
 * 원본은 `INSERT → commit → enqueue` 였고 그 순서가 필요했던 이유(워커가 다른 저장소를
 * 본다)가 사라졌다. [ConversionQueue] KDoc 에 계약 조항과의 관계를 적어 두었다.
 *
 * ## 평문이 이 클래스 밖으로 나가지 않는다
 *
 * 본문은 [PlainBody] 로 감싼 뒤 [ContentCipher] 를 지나야만 저장소에 닿는다 — 저장소 포트가
 * [kr.easydoc.core.crypto.EncryptedContent] 만 받으므로 **암호화를 빠뜨린 호출은 컴파일되지
 * 않는다.** 이 클래스는 아무것도 로깅하지 않는다: 남길 만한 것이 식별자와 상태뿐인데 그것은
 * 접근 로그가 이미 든다.
 *
 * ## 마스킹은 여기 없다 — 그래서 **평문 컬럼에 본문을 옮기지 않는다**
 *
 * 업로드 경로에 마스킹이 없다(원본도 `mask_text` 를 import 하지 않는다). 마스킹 선행
 * 불변식의 대상은 **LLM 전송**이고 그것은 워커의 일이다. 이 경로가 지키는 선행은
 * **암호화 선행**이다.
 *
 * 그 사실의 직접적인 귀결이 제목 규칙이다 — `documents.title` 은 평문 컬럼이고 이 시점에는
 * 마스킹도 돌지 않으므로, **본문에서 제목을 만들면** 원문 조각이 아무 방어도 없이 평문으로
 * 남는다(게이트 27 Critical ①). [kr.easydoc.core.document.resolveTitle] 은 사용자가 제목
 * 칸에 적어 준 값만 쓰고, 이 클래스는 본문도 **파일 이름도** 그 함수에 넘기지 않는다
 * (2026-08-20 재판정 — 파일 이름 갈래를 되돌린 사유는 그 함수의 KDoc ⑵ 다).
 */
class DocumentService(
    private val storage: DocumentStorage,
    private val workspaces: WorkspaceLookup,
    private val cipher: ContentCipher,
    private val extractor: DocumentTextExtractor,
    private val transaction: TransactionRunner,
) {
    /**
     * 붙여넣은 본문으로 문서를 만들고 변환을 요청한다.
     *
     * @throws InvalidInputException 본문이 비었거나 길이 상한을 넘었다 → 422.
     * @throws NotFoundException 지정한 작업 공간이 없거나 내 것이 아니다 → 404.
     */
    fun createFromText(
        ownerId: UUID,
        text: String,
        title: String?,
        workspaceId: UUID?,
    ): AcceptedUpload {
        if (text.isBlank()) throw InvalidInputException(EMPTY_BODY_MESSAGE)
        // 제목을 안 주면 대체 제목이다. 본문은 제목이 되지 않는다.
        return store(ownerId, text, SourceFormat.TEXT, title, workspaceId)
    }

    /**
     * 업로드 파일에서 본문을 뽑아 문서를 만들고 변환을 요청한다.
     *
     * [filename] 은 **형식 판별에만** 쓰이고 버려진다 — 저장하지도 로그에 남기지도 않는다
     * (계약 `DocumentTextRequest.title`, `migration-safety-gate` I-4). 중간에 이 값을 제목의
     * 바탕으로 쓰는 판이 있었고 2026-08-20 재판정으로 되돌렸다. 사유는
     * [kr.easydoc.core.document.resolveTitle] KDoc ⑵ 에 있다.
     *
     * @throws UploadTooLargeException 파일이 크기 상한을 넘었다 → 413.
     * @throws kr.easydoc.core.exceptions.UnsupportedFormatException 지원하지 않는 확장자 → 422.
     * @throws DocumentExtractionException 텍스트를 뽑지 못했거나 결과가 비었다 → 422.
     * @throws InvalidInputException 본문 길이 상한을 넘었다 → 422.
     * @throws NotFoundException 지정한 작업 공간이 없거나 내 것이 아니다 → 404.
     */
    fun createFromFile(
        ownerId: UUID,
        filename: String?,
        bytes: ByteArray,
        title: String?,
        workspaceId: UUID?,
    ): AcceptedUpload {
        // 크기 판정이 추출보다 먼저다 — 계약이 정한 순서이고, 상한을 넘는 바이트를 파서에
        // 넘기지 않는 것이 압축 폭탄 방어의 첫 단계이기도 하다(I-10).
        if (bytes.size > MAX_UPLOAD_BYTES) throw UploadTooLargeException(UPLOAD_TOO_LARGE_MESSAGE)
        val extracted = extractor.extract(filename, bytes)
        // 빈 docx·hwpx 는 예외 없이 빈 문자열을 돌려준다 — 빈 문서 판정은 추출 결과로 한다.
        if (extracted.text.isBlank()) throw DocumentExtractionException(NO_TEXT_IN_DOCUMENT_MESSAGE)
        // `filename` 은 여기서 끝난다 — 추출기가 형식을 가리는 데 썼고, 그 아래로 흐르지 않는다.
        return store(ownerId, extracted.text, extracted.format, title, workspaceId)
    }

    /**
     * 내 문서 목록을 최신순으로 돌려준다. 작업 공간을 주면 그 안만 본다.
     *
     * **남의 작업 공간 식별자에는 빈 목록이 아니라 404 다** — 빈 목록은 "그 작업 공간은
     * 비어 있다"는 사실을 알려 주는 셈이라, 남의 공간이 존재하는지 확인하는 수단이 된다.
     *
     * 다음 쪽 유무를 판정할 수 있도록 **한 건 더 읽어** 돌려준다. 총 개수를 세지 않는
     * 이유는 계약이 적었다(전수 count 는 목록 조회마다 비싸다). 잘라내기와 `has_more`
     * 판정은 응답을 만드는 층의 일이다.
     */
    fun list(
        ownerId: UUID,
        workspaceId: UUID?,
        limit: Int,
        offset: Int,
    ): List<DocumentListing> {
        if (workspaceId != null) requireOwnedWorkspace(ownerId, workspaceId)
        return storage.documents.listOwned(ownerId, workspaceId, limit + 1, offset)
    }

    /**
     * 길이를 판정하고, 한 트랜잭션 안에서 **소유권 확인 → 문서 → 변환 → 작업**을 만든다.
     *
     * 식별자를 트랜잭션 **밖에서** 만들지 않는다 — 만들어도 되지만, 암호화가 트랜잭션 안에
     * 있어야 실패 시 평문이 든 중간 상태가 남지 않는다. 암호화 비용은 4,000자 한 건이라
     * 트랜잭션을 오래 붙잡지 않는다.
     *
     * 인자가 [givenTitle] **하나**인 것이 이 함수의 성질이다 — 두 입력(붙여넣기·파일)이 제목에
     * 대해 같은 것을 넘긴다. 파일 이름이 흐를 통로를 한때 여기 뚫었다가 2026-08-20 재판정으로
     * 막았다. 통로가 없으면 되살리는 편집이 시그니처에 드러난다.
     */
    private fun store(
        ownerId: UUID,
        text: String,
        sourceFormat: SourceFormat,
        givenTitle: String?,
        workspaceId: UUID?,
    ): AcceptedUpload {
        val charCount = charCountOf(text)
        if (charCount > MAX_CONVERTIBLE_CHARS) throw InvalidInputException(BODY_TOO_LONG_MESSAGE)

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

    /**
     * 문서를 담을 작업 공간을 정한다. 지정이 없으면 기본(가장 먼저 만든) 공간이다.
     *
     * @throws NotFoundException 지정한 작업 공간이 없거나 내 것이 아니다.
     * @throws StorageException 작업 공간이 하나도 없다 — 우리 불변식이 깨진 상태다.
     */
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
