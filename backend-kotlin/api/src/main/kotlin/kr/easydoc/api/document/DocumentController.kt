package kr.easydoc.api.document

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.AcceptedUpload
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.MISSING_FILE_PART_MESSAGE
import kr.easydoc.core.document.MAX_UPLOAD_BYTES
import kr.easydoc.core.exceptions.InvalidInputException
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest
import java.util.UUID

// 마이그레이션 프로필 검증: `MigrateProfileWithoutEncryptionKeyTest`.

/**
 * `POST /documents` — **한 경로가 두 입력을 받는다.** 그리고 같은 경로의 `GET` — 목록.
 * 그리고 `DELETE /documents/{document_id}` — 즉시 파기.
 */
@Profile("!$MIGRATE_PROFILE")
@RestController
class DocumentController(private val documentService: DocumentService) {
    /** 붙여넣기 모드. 계약 `requestBody.content['application/json']`. */
    @PostMapping(DOCUMENTS_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createFromText(
        user: AuthenticatedUser,
        @RequestBody request: DocumentTextRequest,
    ): ResponseEntity<DocumentCreatedResponse> =
        accepted(
            documentService.createFromText(
                ownerId = user.id,
                text = request.text,
                title = request.title,
                workspaceId = request.workspaceId,
            ),
        )

    /** 파일 모드. 계약 `requestBody.content['multipart/form-data']`. */
    @PostMapping(DOCUMENTS_PATH, consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createFromFile(
        user: AuthenticatedUser,
        request: MultipartHttpServletRequest,
    ): ResponseEntity<DocumentCreatedResponse> {
        val file = request.getFile(FILE_PART) ?: throw InvalidInputException(MISSING_FILE_PART_MESSAGE)
        // **`workspace_id` 를 여기서 파싱하지 않는다.** 인자 자리에서 파싱하면 Kotlin 의
        // 인자 평가 순서가 계약 검사 순서를 앞질러 상한 초과 요청에 422 가 나간다.
        return accepted(
            documentService.createFromFile(
                ownerId = user.id,
                filename = file.originalFilename,
                bytes = readBounded(file),
                title = request.getParameter(TITLE_PART),
                rawWorkspaceId = request.getParameter(WORKSPACE_ID_PART),
            ),
        )
    }

    /** `GET /documents` — 내 문서를 최신순으로. */
    @GetMapping(DOCUMENTS_PATH)
    fun list(
        user: AuthenticatedUser,
        @RequestParam(name = LIMIT_PARAM, defaultValue = LIST_LIMIT_DEFAULT)
        @Min(LIST_LIMIT_MIN)
        @Max(LIST_LIMIT_MAX)
        limit: Int,
        @RequestParam(name = OFFSET_PARAM, defaultValue = LIST_OFFSET_DEFAULT)
        @Min(LIST_OFFSET_MIN)
        offset: Int,
        @RequestParam(name = WORKSPACE_ID_PART, required = false) workspaceId: UUID?,
    ): ResponseEntity<DocumentListResponse> {
        val fetched = documentService.list(ownerId = user.id, workspaceId = workspaceId, limit = limit, offset = offset)
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
            .header(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            .body(DocumentListResponse.of(fetched, limit = limit, offset = offset))
    }

    /** `DELETE /documents/{document_id}` — 즉시 파기. **204 이고 본문이 없다.** */
    @DeleteMapping(DOCUMENT_ITEM_PATH)
    fun delete(
        user: AuthenticatedUser,
        @PathVariable(DOCUMENT_ID_VARIABLE) documentId: UUID,
    ): ResponseEntity<Void> {
        documentService.delete(ownerId = user.id, documentId = documentId)
        return ResponseEntity.noContent().build()
    }

    /** 상한 **+1 바이트**까지만 읽는다. */
    private fun readBounded(file: MultipartFile): ByteArray = file.inputStream.use { it.readNBytes(BOUNDED_READ_BYTES) }

    /** **202 다(201 이 아니다)** — 자원은 생겼지만 변환은 아직 시작 전이다. */
    private fun accepted(upload: AcceptedUpload): ResponseEntity<DocumentCreatedResponse> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.LOCATION, "$CONVERSION_LOCATION_PREFIX${upload.conversionId}")
            .body(DocumentCreatedResponse.of(upload))

    private companion object {
        const val DOCUMENTS_PATH = "/documents"

        /** 계약 `paths./documents/{document_id}` — 경로 문자열과 **변수 이름**. */
        const val DOCUMENT_ITEM_PATH = "/documents/{document_id}"
        const val DOCUMENT_ID_VARIABLE = "document_id"

        /** 계약 `DocumentFileRequest.properties` 의 파트 이름 셋. */
        const val FILE_PART = "file"
        const val TITLE_PART = "title"

        /** 파일 모드의 파트 이름이자 **`GET` 의 쿼리 파라미터 이름**이다. */
        const val WORKSPACE_ID_PART = "workspace_id"

        /** 계약 `paths./documents.get.parameters` 의 이름 둘. */
        const val LIMIT_PARAM = "limit"
        const val OFFSET_PARAM = "offset"

        /**
         * 하한선 10곳에 붙는 사적 응답 헤더. 값의 정본은 계약 `components/headers` 의 각
         * 컴포넌트이고, 실제로 나가는 값이 그 `const` 와 같은지는 계약 케이스가 잰다.
         */
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NO_STORE = "no-store"
        const val NOSNIFF = "nosniff"

        /** `Location` 값의 앞부분. 계약 `paths./conversions/{conversion_id}` 와 같은 경로다. */
        const val CONVERSION_LOCATION_PREFIX = "/conversions/"

        /** 상한 초과를 관측하기 위한 한 바이트를 더한 값. */
        val BOUNDED_READ_BYTES = (MAX_UPLOAD_BYTES + 1).toInt()
    }
}
