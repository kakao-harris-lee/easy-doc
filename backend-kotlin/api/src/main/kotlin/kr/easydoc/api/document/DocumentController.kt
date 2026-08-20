package kr.easydoc.api.document

import kr.easydoc.api.MIGRATE_PROFILE
import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.document.AcceptedUpload
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.INVALID_WORKSPACE_ID_MESSAGE
import kr.easydoc.application.document.MISSING_FILE_PART_MESSAGE
import kr.easydoc.core.document.MAX_UPLOAD_BYTES
import kr.easydoc.core.exceptions.InvalidInputException
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest
import java.util.UUID

/**
 * `POST /documents` — **한 경로가 두 입력을 받는다.**
 *
 * ## `Content-Type` 으로 가르되 대소문자를 가리지 않는다 (DC-5)
 *
 * 계약이 *"비교는 대소문자를 가리지 않는다(RFC 9110) — `Multipart/Form-Data` 가 JSON
 * 경로로 새면 안 된다"* 고 적었다. 여기서는 그 판정을 손으로 하지 않고 **매핑 두 개의
 * `consumes` 조건**에 맡긴다. Spring 의 `ConsumesRequestCondition` 은 헤더를
 * `MediaType.parseMediaType` 으로 파싱하는데 그 파서가 타입·서브타입을 소문자로 접으므로
 * 비교가 이미 대소문자 무시다. 손으로 `startsWith` 를 적으면 그 한 줄이 두 번째 판정이
 * 되고, 프레임워크 판정과 갈리는 날 어느 쪽이 이기는지 알 수 없다.
 *
 * 원본(`app/api/documents.py`)이 손으로 갈랐던 이유는 FastAPI 가 한 라우트에 두 본문
 * 스키마를 선언하지 못해서였다(본문은 한 번만 읽을 수 있다). Spring MVC 에는 그 제약이
 * 없으므로 **같은 경로에 매핑을 둘 둔다** — 계약의 `requestBody.content` 두 갈래가 코드에
 * 그대로 대응된다.
 *
 * ## 라우터에 비즈니스 판단이 없다
 *
 * 크기 상한·추출·본문 길이·소유권·저장은 전부 [DocumentService] 가 **계약이 정한 순서**로
 * 한다(`CLAUDE.md` 아키텍처 규칙 3, 계약 `POST /documents` description). 여기서 하는 일은
 * multipart 파트를 유스케이스 인자로 옮기는 것뿐이고, 그 과정에서 나는 두 거절
 * (파일 파트 없음·작업 공간 식별자 형식 오류)은 **HTTP 표현을 푸는 자리에서만 생기는
 * 오류**라 여기 남는다. 원본도 같은 자리에서 같은 두 가지를 던진다.
 *
 * ## 두 거절이 **문자열** `detail` 이어야 한다
 *
 * `@RequestPart("file") file: MultipartFile` 로 받으면 파트가 없을 때 Spring 이
 * `MissingServletRequestPartException` 을 던지고, 우리 핸들러가 그것을 계약
 * `ValidationFailed` 의 **배열** 모양으로 옮긴다. 그런데 계약은 이 갈래를
 * *"파트가 없거나 파일이 아니면 422(\"업로드할 파일(file)이 필요합니다\")"* 로,
 * 곧 **문자열** `detail` 로 정했다(`DocumentFileRequest.file`). 그래서 파트를 직접 꺼내
 * 도메인 예외로 던진다.
 *
 * 「파일이 아닌 값」도 같은 자리에서 걸린다 — `StandardMultipartHttpServletRequest` 는
 * **파일 이름이 있는 파트만** 파일로 올리고 나머지는 폼 파라미터로 둔다. 그래서
 * `getFile("file")` 이 `null` 이면 두 경우가 모두 잡힌다.
 *
 * ## 캐시 금지 헤더를 여기서 붙이지 않는다
 *
 * 전역 필터·밸브가 모든 응답에 붙인다(계약 `x-global-response-headers`). 계약이 남긴
 * **하한선 10곳**(`x-private-response-headers.applies_to`)에 `POST /documents` 가 없으므로
 * 개별 부착을 더하지 않는다 — `WorkspaceController` 의 `DELETE` 204 와 같은 판단이고,
 * 빠뜨린 것이 아니라 **하한선 목록을 계약대로 옮긴 것**이다. 헤더가 실제로 나가는지는
 * 계약 케이스가 개수까지 단언한다.
 *
 * ## `migrate` 프로필에서는 조립되지 않는다
 *
 * 면제가 아니라 **의존성**이다 — [DocumentService] 를 만드는 `DocumentConfiguration` 이
 * 같은 조건으로 빠져 있고(스키마만 옮기는 잡이 본문 암호화 키를 쥘 이유가 없다, 게이트 26
 * 조치 2), 그 프로필에서 이 컨트롤러가 남으면 **기동이 "DocumentService 빈이 없다"로
 * 실패한다**(실측: `MigrateProfileWithoutEncryptionKeyTest` 가 그 자리에서 빨개졌다).
 * 부정 목록(`!migrate`)이지 허용 목록이 아닌 것도 같은 의도다 — 새 프로필이 생기면
 * **문서 API 를 갖는 쪽**이 기본이어야 한다.
 */
@Profile("!$MIGRATE_PROFILE")
@RestController
class DocumentController(private val documentService: DocumentService) {
    /**
     * 붙여넣기 모드. 계약 `requestBody.content['application/json']`.
     *
     * `workspace_id` 를 `UUID?` 로 받는다 — 형식이 틀리면 Jackson 이 역직렬화 단계에서
     * 끊고 그 단계는 **인증 인터셉터보다 뒤다**. 즉 토큰 없이 잘못된 UUID 를 보내면 422 가
     * 아니라 401 이 나간다(계약 `info.description` 의 우선순위 절, X-A3).
     */
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

    /**
     * 파일 모드. 계약 `requestBody.content['multipart/form-data']`.
     *
     * [MultipartHttpServletRequest] 를 통째로 받는 이유는 위 KDoc 「두 거절이 문자열
     * `detail` 이어야 한다」에 있다.
     */
    @PostMapping(DOCUMENTS_PATH, consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createFromFile(
        user: AuthenticatedUser,
        request: MultipartHttpServletRequest,
    ): ResponseEntity<DocumentCreatedResponse> {
        val file = request.getFile(FILE_PART) ?: throw InvalidInputException(MISSING_FILE_PART_MESSAGE)
        return accepted(
            documentService.createFromFile(
                ownerId = user.id,
                filename = file.originalFilename,
                bytes = readBounded(file),
                title = request.getParameter(TITLE_PART),
                workspaceId = parseWorkspaceId(request.getParameter(WORKSPACE_ID_PART)),
            ),
        )
    }

    /**
     * 상한 **+1 바이트**까지만 읽는다.
     *
     * 「상한을 넘었다」는 판정에는 한 바이트면 충분하고, 그 이상 읽는 것은 거절할 파일을
     * 위해 힙을 쓰는 일이다(원본 `upload.read(MAX_UPLOAD_BYTES + 1)` 와 같은 판단).
     * 경계 판정 자체는 여기서 하지 않는다 — 계약이 정한 검사 순서의 첫 단계라
     * [DocumentService] 가 진다.
     */
    private fun readBounded(file: MultipartFile): ByteArray = file.inputStream.use { it.readNBytes(BOUNDED_READ_BYTES) }

    /**
     * 폼의 `workspace_id` 를 식별자로 바꾼다.
     *
     * 폼 값은 전부 문자열이라 JSON 모드에서 Jackson 이 하는 일을 여기서 한다. **빈 문자열은
     * 미지정과 같다**(계약 `DocumentFileRequest.workspace_id`) — React 가 값이 없을 때
     * 파트를 아예 싣지 않지만, 빈 문자열을 실어 보내는 클라이언트를 422 로 끊을 이유가 없다.
     *
     * **제출한 값을 메시지에 담지 않는다.** 계약이 그 금지를 명시했고, `detail` 이 응답
     * 본문이자 액세스 로그에 남는 자리라는 것이 그 이유다.
     */
    private fun parseWorkspaceId(value: String?): UUID? {
        if (value.isNullOrEmpty()) return null
        return runCatching { UUID.fromString(value) }
            .getOrElse { throw InvalidInputException(INVALID_WORKSPACE_ID_MESSAGE) }
    }

    /**
     * **202 다(201 이 아니다)** — 자원은 생겼지만 변환은 아직 시작 전이다.
     *
     * `Location` 이 폴링 주소를 알려 준다. 클라이언트가 `/conversions/` 를 스스로 조립하지
     * 않아도 되게 하는 표준 헤더이고, 계약이 `expose_headers` 에 넣어 두어 브라우저 JS 가
     * 읽을 수 있다. 값의 형식은 계약 `POST /documents` 202 의 인라인 헤더 선언이 정본이다.
     */
    private fun accepted(upload: AcceptedUpload): ResponseEntity<DocumentCreatedResponse> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.LOCATION, "$CONVERSION_LOCATION_PREFIX${upload.conversionId}")
            .body(DocumentCreatedResponse.of(upload))

    private companion object {
        const val DOCUMENTS_PATH = "/documents"

        /** 계약 `DocumentFileRequest.properties` 의 파트 이름 셋. */
        const val FILE_PART = "file"
        const val TITLE_PART = "title"
        const val WORKSPACE_ID_PART = "workspace_id"

        /** `Location` 값의 앞부분. 계약 `paths./conversions/{conversion_id}` 와 같은 경로다. */
        const val CONVERSION_LOCATION_PREFIX = "/conversions/"

        /** 상한 초과를 관측하기 위한 한 바이트를 더한 값. */
        val BOUNDED_READ_BYTES = (MAX_UPLOAD_BYTES + 1).toInt()
    }
}
