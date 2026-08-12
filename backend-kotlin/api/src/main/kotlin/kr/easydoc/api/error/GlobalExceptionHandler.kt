package kr.easydoc.api.error

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.QueueUnavailableException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.exceptions.UnsupportedFormatException
import kr.easydoc.core.exceptions.UploadTooLargeException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 도메인 예외 → HTTP 응답 매핑. `app/api/errors.py` 를 그대로 옮긴 것이다.
 *
 * ## 왜 골격 단계에서 먼저 만드는가
 *
 * Spring Boot 는 기본적으로 RFC 9457 `ProblemDetail`
 * (`{"type","title","status","detail","instance"}`, `Content-Type: application/problem+json`)
 * 을 내보낸다. 우리 v1 계약은 **`{"detail": ...}`** 한 가지다. `ProblemDetail` 도
 * `detail` 키를 가져 겉보기에 비슷해서 그냥 두기 쉬운데, 그대로 두면 추가 키가 섞이고
 * Content-Type 이 달라지고 검증 실패가 400이 된다(계약은 422).
 *
 * 이것을 나중에 붙이면 이미 작성된 컨트롤러들이 기본 형식에 의존한 채 굳는다.
 * 그래서 엔드포인트가 `/health` 하나뿐인 Phase 1에서 먼저 세운다.
 *
 * ## 아직 옮기지 않은 것 (Phase 3)
 *
 * - **검증 실패(422) 응답의 `detail` 배열** — `{"detail": [{"loc","msg","type"}]}` 모양.
 *   Phase 1에는 요청 본문을 받는 엔드포인트가 없어 재현할 대상이 없다. 옮길 때
 *   `rejectedValue` 를 반드시 걷어내야 한다 — Spring 의 `BindingResult` 는 거절된 입력값을
 *   들고 있고, 그대로 직렬화하면 비밀번호가 응답 본문과 액세스 로그에 남는다.
 *   Python `_handle_request_validation` 이 `input`·`ctx` 를 버리는 이유가 그것이다.
 * - **미처리 500 응답의 CORS 헤더(U-1)** — Python 은 미들웨어 순서상 붙이지 못하고
 *   React 가 그 동작(`status = 0`)에 의존한다. 재현할지 개선할지 리더 판단이 남아 있어
 *   이 Phase 에서는 CORS 를 설정하지 않는다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 매핑된 도메인 예외.
     *
     * Python 은 Starlette 가 예외 MRO 를 따라 핸들러를 찾는다. Kotlin/Spring 에는 그런
     * 탐색이 없으므로 상위 예외 하나를 잡아 `when (exception)` 으로 가른다 — 결과는 같다.
     * `is` 검사는 하위 타입에도 걸리므로 `LlmTruncatedException` 이
     * `LlmProviderException` 매핑을 타고 502가 되는 동작이 그대로 유지된다.
     *
     * **검사 순서가 중요하다**: 하위 타입을 상위 타입보다 먼저 둔다.
     */
    @ExceptionHandler(EasyDocException::class)
    fun handleDomainException(exception: EasyDocException): ResponseEntity<ErrorResponse> {
        val mapping = mappingFor(exception)
        if (mapping == null) {
            // 새 도메인 예외를 만들고 매핑 등록을 잊어도 응답 모양이 유지된다.
            // 조용히 500만 내보내면 매핑 누락을 아무도 모른 채 지나가므로 타입을 남긴다.
            // 메시지 인자로 예외 메시지를 넣지 않는다 — 무엇이 담길지 이 지점에서는 알 수 없다.
            logger.error("매핑되지 않은 도메인 예외: {}", exception::class.java.simpleName)
            return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, UNMAPPED_DOMAIN_MESSAGE)
        }
        val (status, headers) = mapping
        // detail 에는 도메인 예외가 스스로 만든 메시지만 담는다. 예외 메시지에 입력값을
        // 넣지 않는다는 규약(application 계층)과 짝을 이뤄 개인정보 유출을 막는다.
        return jsonError(status, exception.message ?: UNMAPPED_DOMAIN_MESSAGE, headers)
    }

    /**
     * 도메인 밖 예외의 마지막 백스톱.
     *
     * 이것이 없으면 예상하지 못한 예외가 Spring 기본 `ProblemDetail` 응답이 되어,
     * `{"detail": ...}` 하나만 기대하는 클라이언트가 응답을 읽지 못한다.
     * 메시지는 고정 문자열이다 — 예외에 무엇이 담길지 알 수 없다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<ErrorResponse> {
        logger.error("처리하지 못한 예외: {}", exception::class.java.simpleName)
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_MESSAGE)
    }

    private fun mappingFor(exception: EasyDocException): Pair<HttpStatus, HttpHeaders?>? =
        when (exception) {
            // 입력 오류·지원하지 않는 형식·추출 실패 → 422.
            // 업로드 파일 문제도 사용자가 고칠 수 있는 입력 오류다 — 415가 아니라 422로 통일한다.
            is InvalidInputException,
            is UnsupportedFormatException,
            is DocumentExtractionException,
            -> {
                HttpStatus.UNPROCESSABLE_ENTITY to null
            }

            // 크기 초과만 413으로 가른다 — "파일을 나눠 올리라"는 안내가 형식 오류와 다르다.
            is UploadTooLargeException -> {
                HttpStatus.PAYLOAD_TOO_LARGE to null
            }

            // 이메일 중복·상태 충돌 → 409.
            is EmailAlreadyRegisteredException,
            is ConflictException,
            -> {
                HttpStatus.CONFLICT to null
            }

            // 401에 요구되는 표준 헤더. 클라이언트가 재인증 방식을 안다.
            is InvalidCredentialsException -> {
                HttpStatus.UNAUTHORIZED to HttpHeaders().apply { set(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            }

            is NotFoundException -> {
                HttpStatus.NOT_FOUND to null
            }

            // LLM·큐 장애 → 502. 재시도하면 되는 상황임을 알린다.
            is LlmProviderException,
            is QueueUnavailableException,
            -> {
                HttpStatus.BAD_GATEWAY to null
            }

            is ConfigurationException -> {
                HttpStatus.SERVICE_UNAVAILABLE to null
            }

            // 서버 버그. 메시지는 저장소가 만든 고정 문자열이라 그대로 내보내도 안전하다.
            is StorageException -> {
                HttpStatus.INTERNAL_SERVER_ERROR to null
            }

            else -> {
                null
            }
        }

    private fun jsonError(
        status: HttpStatus,
        detail: String,
        headers: HttpHeaders? = null,
    ): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(status)
            // application/problem+json 이 아니라 application/json 이다.
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers ?: HttpHeaders())
            .body(ErrorResponse(detail))

    companion object {
        /** Python `_handle_unmapped_domain_error` 의 고정 문자열. 문구를 바꾸면 화면 문구가 바뀐다. */
        const val UNMAPPED_DOMAIN_MESSAGE: String = "요청을 처리하지 못했습니다"

        /** Python `_handle_unexpected_error` 의 고정 문자열. */
        const val UNEXPECTED_MESSAGE: String = "서버 오류가 발생했습니다"
    }
}

/**
 * 오류 응답 본문. 키는 `detail` 하나뿐이다.
 *
 * 도메인 예외 경로에서는 문자열이고, 검증 실패(422) 경로에서는 객체 배열이다 —
 * React `client.ts` 의 `readErrorMessage` 가 두 모양을 모두 처리한다. 배열 형태는
 * Phase 3에서 요청 본문을 받는 엔드포인트와 함께 붙인다.
 */
data class ErrorResponse(val detail: String)
