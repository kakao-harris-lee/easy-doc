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
import org.springframework.beans.TypeMismatchException
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.databind.exc.InvalidNullException
import tools.jackson.databind.exc.MismatchedInputException

/**
 * 도메인 예외·프레임워크 예외 → HTTP 응답 매핑. `app/api/errors.py` 를 그대로 옮긴 것이다.
 *
 * ## 왜 [ResponseEntityExceptionHandler] 를 상속하는가 (리뷰 C-1)
 *
 * 1차 판은 `@ExceptionHandler(Exception::class)` 백스톱 하나만 두었다. 그런데
 * `@RestControllerAdvice` 안의 그 백스톱은 **Spring MVC 가 스스로 던지는 예외까지 전부
 * 가로챈다.** 살아 있는 두 런타임 실측(2026-08-12)에서 `GET /nope`(Python 404),
 * `POST /health`(Python 405 + `Allow: GET`), `Accept: application/xml`(Python 200)이
 * 모두 Kotlin 에서 **500** 으로 나갔다.
 *
 * [ResponseEntityExceptionHandler] 는 프레임워크 예외 20종을 명시적으로 등록해 **표준
 * 상태 코드**로 되돌린다. `ExceptionHandlerMethodResolver` 가 예외 계층에서 가장 구체적인
 * 핸들러를 고르므로, 그 20종은 이 상위 클래스가 가져가고 나머지만 `Exception` 백스톱으로
 * 떨어진다.
 *
 * 본문은 상속하지 않는다 — 상위 구현은 RFC 9457 `ProblemDetail` 을 만든다. [createResponseEntity]
 * 한 곳에서 계약 형태(`{"detail": ...}`, `application/json`)로 덮는다.
 *
 * ## 왜 상위 구현의 detail 문구를 쓰지 않는가
 *
 * `ProblemDetail.detail` 은 예외 메시지에서 유도되는 경우가 많다. 그 안에 요청 본문의
 * 조각(파싱 실패 위치, 거절된 값)이 실릴 수 있어 개인정보 유출 경로가 된다. 그래서
 * **상태 코드의 표준 사유 문구**만 쓴다 — Python(Starlette)이 `{"detail":"Not Found"}`,
 * `{"detail":"Method Not Allowed"}` 를 내보내는 것과 같은 값이다.
 *
 * ## 검증 실패는 422 + 배열이다
 *
 * Spring 기본값은 400 + `ProblemDetail` 이지만 계약은 **422 + `[{loc, msg, type}]`** 이다
 * (`contracts/easy-doc-v1.yaml` 의 `ValidationFailed`). React `client.ts` 의
 * `readErrorMessage` 가 문자열/배열 두 모양을 분기 처리하므로 한쪽만 구현하면 화면에서
 * 진짜 사유가 사라진다. 항목에 `input`·`ctx` 를 넣지 않는다 — 비밀번호가 응답 본문과
 * 액세스 로그에 남는 경로다.
 *
 * ## Phase 3 에서 이어서 할 일
 *
 * - `msg`·`type` 문자열은 Pydantic 과 **바이트 동일할 수 없다**(검증 엔진이 다르다).
 *   계약이 동결한 것은 상태 코드·키 구성·입력값 미노출이고, 문구는 그 아래다.
 * - [handleHandlerMethodValidationException] 은 `spring-boot-starter-validation` 이
 *   붙어야 실제로 발생한다. HTTP 경계 테스트는 그 의존성이 들어오는 Phase 3 에서 붙인다.
 * - **성공 응답의 캐시 금지 헤더는 `ResponseEntity` 에 붙인다.** 컨트롤러가
 *   `HttpServletResponse` 에 직접 쓰면 예외가 나도 그 헤더가 오류 응답에 남는데
 *   (Python 과 반대 거동), 서블릿 API 에는 헤더 삭제가 없고 `response.reset()` 은
 *   CORS 필터가 먼저 써 둔 헤더까지 지워 버려 대안이 되지 못한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    // 상위 클래스에 commons-logging `logger` 필드가 있어 이름을 겹치지 않게 둔다.
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 매핑된 도메인 예외. 상태 코드 표는 최하단 `mappingFor` 에 있다.
     *
     * 매핑이 없으면 고정 문자열 500 으로 떨어진다 — Python `_handle_unmapped_domain_error`
     * 와 같은 동작이다.
     */
    @ExceptionHandler(EasyDocException::class)
    fun handleDomainException(exception: EasyDocException): ResponseEntity<Any> {
        val mapping = mappingFor(exception)
        if (mapping == null) {
            // 새 도메인 예외를 만들고 매핑 등록을 잊어도 응답 모양이 유지된다.
            // 조용히 500만 내보내면 매핑 누락을 아무도 모른 채 지나가므로 타입을 남긴다.
            // 메시지 인자로 예외 메시지를 넣지 않는다 — 무엇이 담길지 이 지점에서는 알 수 없다.
            log.error("매핑되지 않은 도메인 예외: {}", exception::class.java.simpleName)
            return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse(UNMAPPED_DOMAIN_MESSAGE))
        }
        val (status, headers) = mapping
        // detail 에는 도메인 예외가 스스로 만든 메시지만 담는다. 예외 메시지에 입력값을
        // 넣지 않는다는 규약(application 계층)과 짝을 이뤄 개인정보 유출을 막는다.
        return jsonError(status, ErrorResponse(exception.message ?: UNMAPPED_DOMAIN_MESSAGE), headers)
    }

    /**
     * 도메인 밖 예외의 마지막 백스톱.
     *
     * [ResponseEntityExceptionHandler] 가 등록한 프레임워크 예외 20종은 여기까지 오지
     * 않는다 — 그쪽이 더 구체적인 매핑이라 먼저 이긴다. 메시지는 고정 문자열이다.
     *
     * **여기 오는 것이 「예상하지 못한 예외」뿐이라고 적을 수 없다** (게이트 21 SEC-4 ·
     * contract-keeper §1-3). `PasswordHashingOverloadedException` 은 **우리가 설계해서
     * 던지는 배압**인데 도메인 예외가 아니라서 이 백스톱으로 떨어진다. 즉 이 자리의 ERROR
     * 로그는 「장애」와 「용량 압력」 둘을 같은 줄로 찍는다 — 운영에서 경보가 갈리지 않는
     * 자리다. 응답 코드를 500 에서 옮길지(503 + 전용 문구)는 **계약 개정 사안**이라
     * 리더 재심에 올라가 있고, 그 판정 전까지 여기 형태를 바꾸지 않는다.
     * 판정이 어느 쪽으로 나든 응답 자체는
     * [kr.easydoc.api.PasswordHashingBackpressureReachTest] 가 붙들고 있다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<Any> {
        log.error("처리하지 못한 예외: {}", exception::class.java.simpleName)
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse(UNEXPECTED_MESSAGE))
    }

    // ------------------------------------------------------------------ 검증 실패 → 422 배열

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val items =
            ex.bindingResult.allErrors.map { error ->
                // FieldError 는 rejectedValue 를 들고 있다 — 읽지 않는다.
                val field = (error as? FieldError)?.field
                ValidationErrorItem(
                    loc = listOfNotNull(BODY, field),
                    msg = error.defaultMessage ?: INVALID_INPUT_MESSAGE,
                    type = errorTypeOf(error.code),
                )
            }
        return validationError(ex, items, headers, request)
    }

    /**
     * `@RequestParam`·`@PathVariable` 에 붙은 제약 위반.
     *
     * `spring-boot-starter-validation` 이 붙기 전에는 발생하지 않는다(검증기가 없으면
     * Spring 이 이 예외를 만들지 않는다). Phase 3 에서 입력 상한을 붙일 때 HTTP 경계
     * 테스트로 고정한다.
     */
    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val items =
            ex.parameterValidationResults.flatMap { result ->
                val parameter = result.methodParameter
                result.resolvableErrors.map { error ->
                    ValidationErrorItem(
                        loc = listOfNotNull(locationOf(parameter), parameter.parameterName),
                        msg = error.defaultMessage ?: INVALID_INPUT_MESSAGE,
                        type = errorTypeOf(error.codes?.lastOrNull()),
                    )
                }
            }
        return validationError(ex, items, headers, request)
    }

    /**
     * 본문을 읽지 못했다 — 깨진 JSON 이 대표 사례다.
     *
     * Python 은 `{"loc":["body", <문자 위치>],"msg":"JSON decode error","type":"json_invalid"}`
     * 를 낸다. 문자 위치는 요청 본문의 오프셋이라 재현하지 않는다(값 자체는 아니지만 본문
     * 구조를 노출하고, 파서가 달라 어차피 같은 수가 나오지 않는다).
     */
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = validationError(ex, listOf(bodyReadItem(ex)), headers, request)

    override fun handleMissingServletRequestParameter(
        ex: MissingServletRequestParameterException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        validationError(
            ex,
            listOf(ValidationErrorItem(listOf(QUERY, ex.parameterName), FIELD_REQUIRED_MESSAGE, "missing")),
            headers,
            request,
        )

    override fun handleMissingServletRequestPart(
        ex: MissingServletRequestPartException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        validationError(
            ex,
            listOf(ValidationErrorItem(listOf(BODY, ex.requestPartName), FIELD_REQUIRED_MESSAGE, "missing")),
            headers,
            request,
        )

    /**
     * 경로·쿼리 파라미터의 타입 변환 실패 (`?limit=열개`, 잘못된 UUID 등).
     *
     * 예외 메시지에는 **거절된 값이 들어 있다** — 절대 detail 로 옮기지 않는다.
     * 기대 타입만 알려준다.
     */
    override fun handleTypeMismatch(
        ex: TypeMismatchException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val mismatch = ex as? MethodArgumentTypeMismatchException
        val location = mismatch?.parameter?.let { locationOf(it) } ?: QUERY
        val label = typeLabelOf(ex.requiredType)
        val item =
            ValidationErrorItem(
                loc = listOfNotNull(location, mismatch?.name),
                msg = "Input should be a valid ${label.second}",
                type = "${label.first}_parsing",
            )
        return validationError(ex, listOf(item), headers, request)
    }

    /**
     * 상위 클래스의 모든 프레임워크 핸들러가 마지막에 지나가는 자리.
     *
     * 상위 구현이 넘겨주는 `body` 는 `ProblemDetail` 이다. 계약 본문([ContractErrorBody])이
     * 아니면 상태 코드의 표준 사유 문구로 바꿔 `{"detail": "..."}` 하나만 남긴다.
     */
    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> =
        ResponseEntity
            .status(statusCode)
            .headers(headers)
            // application/problem+json 이 아니라 application/json 이다.
            .contentType(MediaType.APPLICATION_JSON)
            .body(body as? ContractErrorBody ?: ErrorResponse(reasonPhraseOf(statusCode)))

    private fun validationError(
        exception: Exception,
        items: List<ValidationErrorItem>,
        headers: HttpHeaders,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        handleExceptionInternal(
            exception,
            ValidationErrorResponse(items.ifEmpty { listOf(UNSPECIFIED_VALIDATION_ITEM) }),
            headers,
            // Spring 기본값은 400 이지만 계약은 422 다.
            HttpStatus.UNPROCESSABLE_ENTITY,
            request,
        )
}

/** Python `_handle_unmapped_domain_error` 의 고정 문자열. 문구를 바꾸면 화면 문구가 바뀐다. */
internal const val UNMAPPED_DOMAIN_MESSAGE: String = "요청을 처리하지 못했습니다"

/** Python `_handle_unexpected_error` 의 고정 문자열. */
internal const val UNEXPECTED_MESSAGE: String = "서버 오류가 발생했습니다"

/** Pydantic 이 필수 값 누락에 쓰는 문구. React `readErrorMessage` 가 그대로 화면에 뿌린다. */
private const val FIELD_REQUIRED_MESSAGE = "Field required"

private const val INVALID_INPUT_MESSAGE = "Input is not valid"

/** `loc` 첫 칸. Python 은 `body`·`query`·`path` 셋만 쓴다. */
private const val BODY = "body"
private const val QUERY = "query"
private const val PATH = "path"

/** 어느 파라미터인지 특정하지 못한 검증 실패의 최소 항목. */
private val UNSPECIFIED_VALIDATION_ITEM =
    ValidationErrorItem(listOf(BODY), INVALID_INPUT_MESSAGE, "value_error")

private val SNAKE_BOUNDARY = Regex("([a-z0-9])([A-Z])")

/**
 * 본문을 읽지 못한 원인을 **세 갈래**로 가른다 (게이트 20 codex C4 · 게이트 21 codex C-2).
 *
 * - **필드 누락·명시적 `null`** — [InvalidNullException]. 계약 `ValidationFailed` 의
 *   `field_missing` 예시가 정한 모양(`type: "missing"`)으로 낸다. 종전에는 이 갈래가
 *   Kotlin 생성자 널 검사의 NPE 로 새어 나가 **깨진 JSON 과 바이트 동일한 응답**이
 *   됐다 — 필드를 빠뜨린 사용자가 화면에서 "JSON decode error" 를 봤다. 두 경우를
 *   하나로 묶는 것은 [kr.easydoc.api.config.JsonRequestStrictnessConfig] 의 전역
 *   `Nulls.FAIL` 이고, 갈래를 **예외 타입**으로 가르므로 메시지 문면에 기대지 않는다.
 * - **타입 불일치** — JSON 은 멀쩡히 파싱됐고 값의 모양이 필드 타입과 다르다.
 *   스칼라 강제 변환을 끈 뒤([kr.easydoc.api.config.JsonRequestStrictnessConfig]) 이 갈래가
 *   생겼다. 계약이 `ValidationFailed` 에서 「타입 불일치」를 **배열 detail** 로 정했고,
 *   항목이 어느 필드인지 말해 주어야 클라이언트가 고칠 수 있다.
 * - **파싱 실패** — 깨진 JSON. 종전 동작 그대로다.
 *
 * **예외 메시지를 쓰지 않는다.** Jackson 의 메시지에는 거절된 값이 그대로 실린다.
 * 여기서 읽는 것은 **예외 타입과 경로(프로퍼티 이름)와 목표 타입**뿐이다.
 */
private fun bodyReadItem(exception: HttpMessageNotReadableException): ValidationErrorItem {
    val mismatch =
        generateSequence(exception.cause) { it.cause }
            .filterIsInstance<MismatchedInputException>()
            .firstOrNull()
            ?: return ValidationErrorItem(listOf(BODY), "JSON decode error", "json_invalid")

    val loc = listOf(BODY) + mismatch.path.mapNotNull { it.propertyName }
    return if (mismatch is InvalidNullException) {
        ValidationErrorItem(loc, FIELD_REQUIRED_MESSAGE, "missing")
    } else {
        val label = typeLabelOf(mismatch.targetType)
        ValidationErrorItem(loc = loc, msg = "Input should be a valid ${label.second}", type = "${label.first}_type")
    }
}

/**
 * 도메인 예외 → (상태 코드, 추가 헤더). `app/api/errors.py` 의 `_MAPPINGS` 그대로다.
 *
 * Python 은 Starlette 가 예외 MRO 를 따라 핸들러를 찾는다. Kotlin/Spring 에는 그런 탐색이
 * 없으므로 상위 예외 하나를 잡아 `when` 으로 가른다 — 결과는 같다. `is` 검사는 하위 타입에도
 * 걸리므로 `LlmTruncatedException` 이 `LlmProviderException` 매핑을 타고 502가 되는 동작이
 * 그대로 유지된다. **검사 순서가 중요하다**: 하위 타입을 상위 타입보다 먼저 둔다.
 */
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
    body: ContractErrorBody,
    headers: HttpHeaders? = null,
): ResponseEntity<Any> =
    ResponseEntity
        .status(status)
        // application/problem+json 이 아니라 application/json 이다.
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers ?: HttpHeaders())
        .body(body)

/**
 * 상태 코드의 표준 사유 문구. Starlette `HTTPException` 의 기본 detail 과 같은 값이다.
 *
 * **오류 본문을 만드는 세 경로가 전부 이 함수를 쓴다** — advice([GlobalExceptionHandler]),
 * `/error` 디스패치([ContractErrorController]), 컨테이너가 직접 만드는 응답
 * ([ContractErrorReportValve]). 경로마다 문구 규칙이 갈리면 같은 상태 코드에 다른 본문이
 * 나가고, 그 차이는 어느 경로를 탔는지를 밖에서 알려주는 신호가 된다.
 */
internal fun reasonPhraseOf(statusCode: HttpStatusCode): String =
    HttpStatus.resolve(statusCode.value())?.reasonPhrase ?: UNEXPECTED_MESSAGE

/**
 * 검증 오류 코드를 계약의 `type` 토큰으로 옮긴다.
 *
 * Pydantic 토큰(`missing`, `string_too_long` …)과 Bean Validation 코드(`NotBlank`, `Size` …)는
 * 서로 다른 어휘라 바이트 동일할 수 없다. 필수 값 누락처럼 대응이 분명한 것만 Python 토큰으로
 * 맞추고, 나머지는 코드를 snake_case 로 옮긴다.
 */
private fun errorTypeOf(code: String?): String =
    when (code) {
        null -> "value_error"
        "NotNull", "NotEmpty", "NotBlank", "Required" -> "missing"
        else -> code.replace(SNAKE_BOUNDARY, "$1_$2").lowercase()
    }

/** 파라미터가 어디서 왔는지 — 계약의 `loc` 첫 칸이 된다. */
private fun locationOf(parameter: MethodParameter): String =
    when {
        parameter.hasParameterAnnotation(PathVariable::class.java) -> PATH
        parameter.hasParameterAnnotation(RequestBody::class.java) -> BODY
        else -> QUERY
    }

/**
 * (토큰, 사람이 읽을 이름). Pydantic 의 `int_parsing` / "valid integer" 어휘에 맞춘다.
 *
 * **모르는 타입에 클래스 이름을 싣지 않는다** (게이트 21 codex C-2 후단). 종전 마지막
 * 갈래가 `requiredType.simpleName` 이라, 루트에 배열·스칼라를 보내면 응답 `msg` 에
 * `"Input should be a valid SignupRequest"` 처럼 **내부 DTO 이름**이 실렸다. 값 유출은
 * 아니지만 내부 구조를 밖에 알려 줄 이유가 없고, 계약이 그런 어휘를 정한 적도 없다.
 */
private fun typeLabelOf(requiredType: Class<*>?): Pair<String, String> =
    when (requiredType?.simpleName?.lowercase()) {
        "string" -> "string" to "string"
        "int", "integer", "long", "short" -> "int" to "integer"
        "double", "float", "bigdecimal" -> "float" to "number"
        "boolean" -> "bool" to "boolean"
        "uuid" -> "uuid" to "UUID"
        else -> "value" to "value"
    }

/**
 * 오류 응답 본문의 공통 표식. 최상위 키는 언제나 `detail` **하나뿐**이다.
 *
 * 계약(`contracts/easy-doc-v1.yaml`)이 `detail` 을 **문자열 또는 객체 배열의 union** 으로
 * 동결했다. 표식을 두는 이유는 [GlobalExceptionHandler.createResponseEntity] 가 상위
 * 클래스의 `ProblemDetail` 과 우리 본문을 구분해야 하기 때문이다.
 */
sealed interface ContractErrorBody

/** 도메인 예외 경로의 본문 — `detail` 이 사람이 읽을 한국어 문장이다. */
data class ErrorResponse(val detail: String) : ContractErrorBody

/** 검증 실패(422) 경로의 본문 — `detail` 이 객체 배열이다. */
data class ValidationErrorResponse(val detail: List<ValidationErrorItem>) : ContractErrorBody

/**
 * 검증 실패 항목. 키는 `loc`·`msg`·`type` 셋뿐이다.
 *
 * `input`·`ctx` 를 **넣지 않는다** — FastAPI 기본 핸들러는 넣지만 `app/api/errors.py` 의
 * `_handle_request_validation` 이 걷어낸다. 비밀번호가 응답 본문과 액세스 로그에 남는
 * 경로이기 때문이다.
 */
data class ValidationErrorItem(
    val loc: List<String>,
    val msg: String,
    val type: String,
)
