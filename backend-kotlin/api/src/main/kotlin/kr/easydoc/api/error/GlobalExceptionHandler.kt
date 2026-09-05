package kr.easydoc.api.error

import kr.easydoc.application.document.UPLOAD_TOO_LARGE_MESSAGE
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.EmailNotVerifiedException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidOAuthStateException
import kr.easydoc.core.exceptions.InvalidVerificationCodeException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.RateLimitedException
import kr.easydoc.core.exceptions.ReconversionBudgetExhaustedException
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
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.databind.exc.InvalidNullException
import tools.jackson.databind.exc.MismatchedInputException

// 해싱 배압 매핑 검증: `PasswordHashingBackpressureReachTest`.

/**
 * 도메인 예외·프레임워크 예외 → HTTP 응답 매핑. **정본은 `contracts/easy-doc-v1.yaml` 이다**
 * — 출발점은 `app/api/errors.py` 였으나 계약이 갈린 자리에서는 계약을 따른다(예: 502 폐기).
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    // 상위 클래스에 commons-logging `logger` 필드가 있어 이름을 겹치지 않게 둔다.
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** **프레임워크 20종이 가져가지 않은 예외 전부** — 도메인 예외와 그 밖의 것. */
    @ExceptionHandler(Exception::class)
    fun handleUnmapped(exception: Exception): ResponseEntity<Any> {
        val mapping = (exception as? EasyDocException)?.let(::mappingFor)
        if (mapping != null) {
            val (status, headers) = mapping
            // detail 에는 도메인 예외가 스스로 만든 메시지만 담는다. 예외 메시지에 입력값을
            // 넣지 않는다는 규약(application 계층)과 짝을 이뤄 개인정보 유출을 막는다.
            return jsonError(status, ErrorResponse(exception.message ?: UNMAPPED_DOMAIN_MESSAGE), headers)
        }
        // **두 갈래를 서로 다른 줄로 찍는다.** 운영에서 「매핑을 빠뜨렸다」와 「예상 못 한
        // 예외가 났다」는 다른 사건이고, 한 줄로 합치면 그 구분이 로그에서 사라진다.
        // 메시지 인자로 예외 메시지를 넣지 않는다 — 무엇이 담길지 이 지점에서는 알 수 없다.
        val type = exception::class.java.simpleName
        val message =
            if (exception is EasyDocException) {
                log.error("매핑되지 않은 도메인 예외: {}", type)
                UNMAPPED_DOMAIN_MESSAGE
            } else {
                log.error("처리하지 못한 예외: {}", type)
                UNEXPECTED_MESSAGE
            }
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse(message))
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
                //
                // `field` 는 Bean Validation 이 리플렉션으로 본 **Kotlin 프로퍼티 이름**
                // (camelCase)이다 — Jackson `@JsonProperty` 별칭을 모른다(그 별칭은
                // 역직렬화 단계에서만 쓰이고, `@Valid` 검증은 그 뒤 이미 만들어진 객체를
                // 본다). `bodyReadItem` 의 `mismatch.path`(Jackson 자체가 잰 JSON 경로)와
                // 달리 여기는 wire 이름으로 옮겨야 계약의 snake_case `loc` 과 맞는다.
                val field = (error as? FieldError)?.field?.let(::snakeCase)
                ValidationErrorItem(
                    loc = listOfNotNull(BODY, field),
                    msg = error.defaultMessage ?: INVALID_INPUT_MESSAGE,
                    type = errorTypeOf(error.code),
                )
            }
        return validationError(ex, items, headers, request)
    }

    /** `@RequestParam`·`@PathVariable` 에 붙은 제약 위반. */
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

    /** 본문을 읽지 못했다 — 깨진 JSON 이 대표 사례다. */
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
    ): ResponseEntity<Any>? = validationError(ex, listOf(missingItem(QUERY, ex.parameterName)), headers, request)

    override fun handleMissingServletRequestPart(
        ex: MissingServletRequestPartException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = validationError(ex, listOf(missingItem(BODY, ex.requestPartName)), headers, request)

    /** 경로·쿼리 파라미터의 타입 변환 실패 (`?limit=열개`, 잘못된 UUID 등). */
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

    /** 컨테이너 multipart 상한 초과 → **413 + 계약 문구** (계획 §5 D-2). */
    override fun handleMaxUploadSizeExceededException(
        ex: MaxUploadSizeExceededException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        handleExceptionInternal(
            ex,
            ErrorResponse(UPLOAD_TOO_LARGE_MESSAGE),
            headers,
            HttpStatus.PAYLOAD_TOO_LARGE,
            request,
        )

    /** 상위 클래스의 모든 프레임워크 핸들러가 마지막에 지나가는 자리. */
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

    /** 검증 실패 응답 조립. */
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

/** 필수 값이 없다 — 위치(`body`·`query`)만 갈린다. 두 핸들러가 같은 모양을 만든다. */
private fun missingItem(
    location: String,
    name: String,
): ValidationErrorItem = ValidationErrorItem(listOf(location, name), FIELD_REQUIRED_MESSAGE, "missing")

/** Python `_handle_unmapped_domain_error` 의 고정 문자열. 문구를 바꾸면 화면 문구가 바뀐다. */
internal const val UNMAPPED_DOMAIN_MESSAGE: String = "요청을 처리하지 못했습니다"

/** Python `_handle_unexpected_error` 의 고정 문자열. */
internal const val UNEXPECTED_MESSAGE: String = "서버 오류가 발생했습니다"

/** Pydantic 이 필수 값 누락에 쓰는 문구. React `readErrorMessage` 가 그대로 화면에 뿌린다. */
private const val FIELD_REQUIRED_MESSAGE = "Field required"

/** 계약 `components/responses/ReconversionBudgetExhausted.headers`. */
private const val RECONVERSION_REMAINING_BUDGET_HEADER = "X-Remaining-Call-Budget"

private const val INVALID_INPUT_MESSAGE = "Input is not valid"

/** `loc` 첫 칸. Python 은 `body`·`query`·`path` 셋만 쓴다. */
private const val BODY = "body"
private const val QUERY = "query"
private const val PATH = "path"

/** 어느 파라미터인지 특정하지 못한 검증 실패의 최소 항목. */
private val UNSPECIFIED_VALIDATION_ITEM =
    ValidationErrorItem(listOf(BODY), INVALID_INPUT_MESSAGE, "value_error")

private val SNAKE_BOUNDARY = Regex("([a-z0-9])([A-Z])")

/** 본문을 읽지 못한 원인을 **세 갈래**로 가른다 (게이트 20 codex C4 · 게이트 21 codex C-2). */
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
 * 도메인 예외 → (상태 코드, 추가 헤더). **정본은 `contracts/easy-doc-v1.yaml` 이다.**
 *
 * 갈래 수가 늘 때마다 순환 복잡도가 함께 는다 — 이 함수의 복잡도는 **매핑 갈래의 수**이지
 * 로직의 얽힘이 아니다(각 갈래는 서로 독립이고 상태 하나만 정한다). 억제는 이 함수 하나에
 * 걸리고 갈래를 나누는 판단 자체로 번지지 않는다.
 */
@Suppress("CyclomaticComplexMethod")
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

        // 인증 코드가 오답·만료·무효 — 사유를 가르지 않는다(`InvalidVerificationCodeException` KDoc).
        is InvalidVerificationCodeException -> {
            HttpStatus.BAD_REQUEST to null
        }

        // 이메일 인증 전이라 이 동작을 할 수 없다 — `POST /documents` 전용.
        is EmailNotVerifiedException -> {
            HttpStatus.FORBIDDEN to null
        }

        // 재발송 쿨다운 안 — 계약이 요구하는 `Retry-After` 를 여기서 싣는다.
        is RateLimitedException -> {
            HttpStatus.TOO_MANY_REQUESTS to
                HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString()) }
        }

        // 재변환 호출 예산 소진 — 쿨다운이 아니라 문서당 영구 상한이라 `Retry-After` 가
        // 없다(`RateLimitedException` 과 다른 429). 잔여 예산은 본문이 아니라 헤더로
        // 낸다 — `x-error-body-universality`(오류 응답 본문은 언제나 detail 하나)를
        // 지키면서 `Retry-After` 와 같은 자리에 값을 싣는다(계약
        // `components/responses/ReconversionBudgetExhausted`).
        is ReconversionBudgetExhaustedException -> {
            HttpStatus.TOO_MANY_REQUESTS to
                HttpHeaders().apply {
                    set(RECONVERSION_REMAINING_BUDGET_HEADER, exception.remainingCallBudget.toString())
                }
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

        // 요청 자체가 무효다(만료·재사용·바인딩 불일치) — 입력 규칙 위반(422)도 자원 상태
        // 충돌(409)도 아니다. `POST /auth/oauth/{provider}/callback` 전용.
        is InvalidOAuthStateException -> {
            HttpStatus.BAD_REQUEST to null
        }

        // 동기로 부른 하위 시스템(소셜 로그인 제공자)에 닿지 못했다. `x-retired-responses`가
        // 예고한 재도입 자리 — 예외 메시지는 항상 고정 문구이고 벤더 오류 텍스트를 담지 않는다.
        is ExternalServiceUnavailableException -> {
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

/** 상태 코드의 표준 사유 문구. Starlette `HTTPException` 의 기본 detail 과 같은 값이다. */
internal fun reasonPhraseOf(statusCode: HttpStatusCode): String =
    HttpStatus.resolve(statusCode.value())?.reasonPhrase ?: UNEXPECTED_MESSAGE

/** 검증 오류 코드를 계약의 `type` 토큰으로 옮긴다. */
private fun errorTypeOf(code: String?): String =
    when (code) {
        null -> "value_error"
        "NotNull", "NotEmpty", "NotBlank", "Required" -> "missing"
        else -> code.replace(SNAKE_BOUNDARY, "$1_$2").lowercase()
    }

/** Kotlin 프로퍼티 이름(camelCase) → 계약의 wire 이름(snake_case). `errorTypeOf` 와 같은 변환. */
private fun snakeCase(name: String): String = name.replace(SNAKE_BOUNDARY, "$1_$2").lowercase()

/** 파라미터가 어디서 왔는지 — 계약의 `loc` 첫 칸이 된다. */
private fun locationOf(parameter: MethodParameter): String =
    when {
        parameter.hasParameterAnnotation(PathVariable::class.java) -> PATH
        parameter.hasParameterAnnotation(RequestBody::class.java) -> BODY
        else -> QUERY
    }

/** (토큰, 사람이 읽을 이름). Pydantic 의 `int_parsing` / "valid integer" 어휘에 맞춘다. */
private fun typeLabelOf(requiredType: Class<*>?): Pair<String, String> =
    when (requiredType?.simpleName?.lowercase()) {
        "string" -> "string" to "string"
        "int", "integer", "long", "short" -> "int" to "integer"
        "double", "float", "bigdecimal" -> "float" to "number"
        "boolean" -> "bool" to "boolean"
        "uuid" -> "uuid" to "UUID"
        else -> "value" to "value"
    }

/** 오류 응답 본문의 공통 표식. 최상위 키는 언제나 `detail` **하나뿐**이다. */
sealed interface ContractErrorBody

/** 도메인 예외 경로의 본문 — `detail` 이 사람이 읽을 한국어 문장이다. */
data class ErrorResponse(val detail: String) : ContractErrorBody

/** 검증 실패(422) 경로의 본문 — `detail` 이 객체 배열이다. */
data class ValidationErrorResponse(val detail: List<ValidationErrorItem>) : ContractErrorBody

/** 검증 실패 항목. 키는 `loc`·`msg`·`type` 셋뿐이다. */
data class ValidationErrorItem(
    val loc: List<String>,
    val msg: String,
    val type: String,
)
