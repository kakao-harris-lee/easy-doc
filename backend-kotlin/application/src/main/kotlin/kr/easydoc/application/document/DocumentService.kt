package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.DocumentListing
import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.MAX_UPLOAD_BYTES
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.document.resolveTitle
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
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

/**
 * 저장할 콘텐츠 한 벌 — 변환이 돌 **텍스트**와, 그것을 뽑아낸 **원본 파일**(있으면).
 *
 * 셋을 함께 드는 이유: 형식은 텍스트가 어디서 왔는지를 말하고 원본은 그 출처 자체라, 셋이
 * 어긋난 조합(`text` 형식인데 원본이 있다)은 결함이지 표현할 값이 아니다. 붙여넣기 팔에서만
 * [original] 이 `null` 이다.
 */
private class UploadContent(
    val text: String,
    val sourceFormat: SourceFormat,
    val original: PlainBytes?,
)

/** 문서 등록 유스케이스 — 붙여넣기·파일 두 입력을 받아 **저장하고 작업을 등록한다.** */
class DocumentService(
    private val storage: DocumentStorage,
    private val workspaces: WorkspaceLookup,
    private val cipher: ContentCipher,
    private val extractor: DocumentTextExtractor,
    private val transaction: TransactionRunner,
    private val users: UserRepository,
) {
    /**
     * 붙여넣은 본문으로 문서를 만들고 변환을 요청한다.
     *
     * **작업 공간 식별자를 파싱하지 않은 원문으로 받는다** — 파일 팔과 같은 이유이고 같은
     * 함수를 쓴다. 두 팔이 같은 결함에 다른 `detail` 모양을 내면 계약 위반이다.
     */
    fun createFromText(
        ownerId: UUID,
        text: String,
        title: String?,
        rawWorkspaceId: String?,
    ): AcceptedUpload {
        requireVerifiedEmail(ownerId)
        if (text.isBlank()) throw InvalidInputException(EMPTY_BODY_MESSAGE)
        // 제목을 안 주면 대체 제목이다. 본문은 제목이 되지 않는다.
        // 붙여넣기에는 원본 파일이 없다 — `null` 이 그 사실이다.
        return store(ownerId, UploadContent(text, SourceFormat.TEXT, original = null), title) {
            parseWorkspaceId(rawWorkspaceId)
        }
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
        requireVerifiedEmail(ownerId)
        // 크기 판정이 추출보다 먼저다 — 계약이 정한 순서이고, 상한을 넘는 바이트를 파서에
        // 넘기지 않는 것이 압축 폭탄 방어의 첫 단계이기도 하다(I-10).
        if (bytes.size > MAX_UPLOAD_BYTES) throw UploadTooLargeException(UPLOAD_TOO_LARGE_MESSAGE)
        val extracted = extractor.extract(filename, bytes)
        // 빈 docx·hwpx 는 예외 없이 빈 문자열을 돌려준다 — 빈 문서 판정은 추출 결과로 한다.
        if (extracted.text.isBlank()) throw DocumentExtractionException(NO_TEXT_IN_DOCUMENT_MESSAGE)
        // `filename` 은 여기서 끝난다 — 추출기가 형식을 가리는 데 썼고, 그 아래로 흐르지 않는다.
        //
        // **추출 텍스트와 원본을 함께 저장한다.** 원본을 남긴다고 추출 텍스트를 없애지 않는다 —
        // 변환·마스킹은 텍스트로 돌고(§4), 원본은 §6.5 의 「원본 형식 내보내기」가 쓴다.
        //
        // **TXT 만 예외다 — 원본을 저장하지 않는다.** 평문에는 §6.5 가 반영할 서식이 애초에
        // 없어(`PackagedOriginalReflector` 의 `SourceFormat.TXT` 갈래) 저장해도 쓰이지 않는다.
        // 그런데도 저장하면 `hasStoredOriginal` 이 참이 되어 `ConversionExportService.export`
        // 가 `reflect() == null` 을 "저장된 원본을 읽을 수 없다"(500)로 오인한다 — 반영할
        // 대상이 없다는 사실과 반영에 실패했다는 사실이 같은 신호에 실리기 때문이다. 원본을
        // 남기지 않으면 붙여넣기와 같은 길로 가서 `not_applicable` 로 정확히 판정되고, 내보내기는
        // 검수본으로 새 텍스트 파일을 만드는 자연스러운 경로를 그대로 탄다.
        val original = if (extracted.format == SourceFormat.TXT) null else PlainBytes(bytes)
        return store(ownerId, UploadContent(extracted.text, extracted.format, original), title) {
            parseWorkspaceId(rawWorkspaceId)
        }
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
        content: UploadContent,
        givenTitle: String?,
        requestedWorkspaceId: () -> UUID?,
    ): AcceptedUpload {
        val charCount = charCountOf(content.text)
        if (charCount > MAX_CONVERTIBLE_CHARS) throw InvalidInputException(BODY_TOO_LONG_MESSAGE)

        // 작업 공간 단계 — 형식(422) 다음 소유권(404). 형식은 여기, 소유권은 트랜잭션 안이다.
        val workspaceId = requestedWorkspaceId()

        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()

        // 결속(record + column)을 여기서 정한다. 행 식별자가 AEAD 에 실리므로 UUID 를
        // 먼저 만들어야 하고, 그래서 저장소가 아니라 이 자리가 암호화한다(계획 §4.1).
        //
        // **봉인은 트랜잭션 밖이다.** 원본은 최대 10MB 라 AEAD 한 번이 짧지 않고, 그것을
        // 열린 트랜잭션 안에서 돌리면 스냅샷과 연결을 그만큼 오래 붙잡는다. UUID 를 먼저
        // 뽑아 두면 결속에 필요한 것이 전부 갖춰지므로 트랜잭션을 열 이유가 없다
        // (프로젝트 `CLAUDE.md` 「장시간 작업을 DB transaction 안에서 실행하지 않는다」).
        val sealed = cipher.encrypt(PlainBody(content.text), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        // 붙여넣기 경로에는 원본이 없다 — `null` 이 그대로 「봉할 것이 없다」다.
        val sealedOriginal =
            content.original?.let {
                StoredOriginal(
                    bytes = cipher.encryptBytes(it, documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES),
                    byteSize = it.size,
                )
            }

        return transaction.inTransaction {
            val resolvedWorkspaceId = resolveWorkspaceId(ownerId, workspaceId)

            val draft =
                DocumentDraft(
                    id = documentId,
                    workspaceId = resolvedWorkspaceId,
                    // **본문(`text`)도 파일 이름도 넘기지 않는다.** 제목은 평문 컬럼이고 이
                    // 시점에는 마스킹이 돌지 않았다 — 게이트 27 Critical ① + 2026-08-20 재판정.
                    title = resolveTitle(givenTitle),
                    sourceFormat = content.sourceFormat,
                    charCount = charCount,
                )

            storage.documents.insert(ownerId, draft, sealed)
            // 원본이 있으면 **같은 경계 안에서** 이어 쓴다. 그때만 `document_originals` 에 행이
            // 생긴다(V3 의 「행이 없다」 표현). 여기서 실패하면 위 문서·아래 변환·작업이 함께
            // 되돌아간다 — 「원본 저장은 실패했는데 업로드는 성공」이 구조적으로 없다.
            //
            // 문서 다음인 것은 FK 때문이다 — `documents` 행이 같은 트랜잭션 안에 이미 있어야 한다.
            if (sealedOriginal != null) storage.originals.insert(ownerId, documentId, sealedOriginal)
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
     * 이메일 인증을 마쳤는지 본다 — **두 입력 팔의 첫 검사다**(계약 `POST /documents`
     * 검사 순서 0번째, `x-input-limits` 보다도 앞). 캐릭터 수·파일 크기 같은 요청 내용은
     * 아직 보지 않은 채로 거절한다: 애초에 이 사용자가 할 수 없는 일이라면 본문을 검증할
     * 이유가 없다(`AuthService.signup` 이 `accessTokens.ensureConfigured()` 를 비밀번호
     * 해시 계산보다 먼저 보는 것과 같은 순서 감각).
     *
     * 소유자 조회 한 번을 여기서 새로 한다 — `ownerId` 는 인증 토큰의 `sub` 일 뿐 사용자
     * 행을 이미 읽어 온 상태가 아니다.
     */
    private fun requireVerifiedEmail(ownerId: UUID) {
        val user = users.findById(ownerId) ?: return
        if (user.emailVerifiedAt == null) {
            throw EmailNotVerifiedException(EMAIL_VERIFICATION_REQUIRED_MESSAGE)
        }
    }

    /** 요청의 `workspace_id` 를 식별자로 바꾼다 — **두 입력 팔이 공유한다.** 빈 문자열·부재는 「지정 없음」이다. */
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
