package kr.easydoc.api.error

import kr.easydoc.application.document.UPLOAD_TOO_LARGE_MESSAGE
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
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

/**
 * 도메인 예외·프레임워크 예외 → HTTP 응답 매핑. **정본은 `contracts/easy-doc-v1.yaml` 이다**
 * — 출발점은 `app/api/errors.py` 였으나 계약이 갈린 자리에서는 계약을 따른다(예: 502 폐기).
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
     * **프레임워크 20종이 가져가지 않은 예외 전부** — 도메인 예외와 그 밖의 것.
     *
     * ## 왜 갈래 둘이 한 메서드인가
     *
     * 종전에는 `@ExceptionHandler(EasyDocException)` 과 `@ExceptionHandler(Exception)` 두
     * 메서드였다. 하나로 합친 것은 **동작이 아니라 구조**의 변경이다 —
     * `ExceptionHandlerMethodResolver` 는 예외 계층에서 가장 가까운 핸들러를 고르므로,
     * 상위 클래스가 명시 등록한 프레임워크 예외 20종은 어느 쪽이든 그쪽이 이긴다. 남는
     * 것은 「도메인 예외인가 아닌가」 한 갈래뿐이고, 그 판정을 여기서 한다.
     *
     * 합친 계기는 detekt `TooManyFunctions`(임계값 11) 였다. **임계값을 올리지 않았다** —
     * 신호가 가리킨 것이 실제로 「이 클래스가 두 일을 한다」였고, 그중 하나(프레임워크 예외
     * 정규화)는 상위 클래스 오버라이드로 고정돼 있어 줄일 수 없다. 줄일 수 있는 쪽은 우리가
     * 더한 두 `@ExceptionHandler` 였고, 둘은 애초에 같은 질문의 두 답이다.
     *
     * ## 매핑이 없으면 고정 문자열 500
     *
     * 새 도메인 예외를 만들고 매핑 등록을 잊어도 응답 모양이 유지된다. 조용히 500 만
     * 내보내면 매핑 누락을 아무도 모른 채 지나가므로 **예외 타입**을 로그에 남긴다 —
     * 메시지는 남기지 않는다(무엇이 담길지 이 지점에서는 알 수 없다).
     *
     * ## 도메인 밖 예외가 「예상하지 못한 예외」뿐이라고 적을 수 없다
     *
     * (게이트 21 SEC-4 · contract-keeper §1-3) `PasswordHashingOverloadedException` 은
     * **우리가 설계해서 던지는 배압**인데 도메인 예외가 아니라서 이 갈래로 떨어진다. 즉
     * 이 자리의 ERROR 로그는 「장애」와 「용량 압력」 둘을 같은 줄로 찍는다 — 운영에서 경보가
     * 갈리지 않는 자리다. 응답 코드를 503 + 전용 문구로 옮길지는 **계약 개정 사안**이라
     * 리더 재심에 올라가 있고, 그 판정 전까지 형태를 바꾸지 않는다. 응답 자체는
     * [kr.easydoc.api.PasswordHashingBackpressureReachTest] 가 붙들고 있다.
     *
     * **비율을 적어 둔다** — 배압 요청 **한 건마다 ERROR 한 줄**이다. privacy-gate 4b 의
     * 240 동시 실측에서 로그인 성공률이 6.7% 였으므로, 그 부하에서 나머지 93.3% 가 전부
     * 이 줄을 찍는다. 「드물게 섞인다」가 아니라 **부하 시 로그의 대부분**이라는 뜻이다.
     */
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
    ): ResponseEntity<Any>? = validationError(ex, listOf(missingItem(QUERY, ex.parameterName)), headers, request)

    override fun handleMissingServletRequestPart(
        ex: MissingServletRequestPartException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = validationError(ex, listOf(missingItem(BODY, ex.requestPartName)), headers, request)

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
     * 컨테이너 multipart 상한 초과 → **413 + 계약 문구** (계획 §5 D-2).
     *
     * ## 오버라이드가 필요한 이유
     *
     * 상위 클래스가 이 예외를 이미 처리 목록에 갖고 있어 **상태는 413 이 나간다.** 그러나
     * 본문은 [createResponseEntity] 가 만드는 **영어 reason phrase**(`Content Too Large`)라
     * 계약 `PayloadTooLarge` 의 문구가 아니다. 즉 손대지 않으면 「상태는 맞고 본문은 틀린」
     * 응답이 나가고, 상태만 재는 테스트는 그것을 통과시킨다.
     *
     * 새 `@ExceptionHandler` 를 더하지 않고 **메서드를 오버라이드한다** — 같은 advice 안에
     * 같은 예외 타입을 겨눈 핸들러가 둘이면 Spring 이 기동 시점에
     * `Ambiguous @ExceptionHandler` 로 끊는다.
     *
     * ## 이것은 backstop 이고 정확 경계는 서비스가 잰다
     *
     * 계약 상한의 판정은 `DocumentService` 가 `MAX_UPLOAD_BYTES` 로 한다. 컨테이너 상한은
     * 그보다 넉넉하게 두었으므로(`application.yml`) 이 자리는 **컨테이너 상한마저 넘은
     * 요청**에서만 돈다. 컨테이너 판정에 계약을 걸지 않는 이유는 Spring 이 Tomcat 의 초과를
     * 알아내는 방식이 **예외 메시지 문자열 매칭**이라, 메시지가 바뀌거나 번역되면 413 이
     * 조용히 500 이 되기 때문이다(계획 §1.5 설계 지점 2 ⑵).
     *
     * 두 자리가 같은 문구를 쓰는 것은 사용자에게 같은 사건이기 때문이다 — 문구의 정본은
     * [UPLOAD_TOO_LARGE_MESSAGE] 하나이고 계약 `PayloadTooLarge.examples.too_large` 와
     * 같은지는 계약 케이스가 잰다.
     */
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

    /**
     * 검증 실패 응답 조립.
     *
     * 상위 클래스의 `protected handleExceptionInternal` 을 불러야 해서 **멤버로 남는다** —
     * 최상위 함수로 내리면 그 메서드에 닿지 못한다. 나머지 조립 헬퍼는 파일 하단의 최상위
     * 함수들이다(이 클래스의 공개 표면을 프레임워크 핸들러로만 유지한다).
     */
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
 * 도메인 예외 → (상태 코드, 추가 헤더). **정본은 `contracts/easy-doc-v1.yaml` 이다.**
 *
 * 상위 예외 하나를 잡아 `when` 으로 가른다. `is` 검사는 하위 타입에도 걸리므로
 * `DecryptionFailedException` 이 `StorageException` 매핑을 타고 500 이 되는 동작이 그대로
 * 유지된다. **검사 순서가 중요하다**: 하위 타입을 상위 타입보다 먼저 둔다.
 *
 * **502 는 이 표에 없다** (계약 v1.3.0, `x-retired-responses`). 계약이 502 를 선언하는
 * 오퍼레이션이 0개이므로 어떤 응답도 502 여서는 안 된다. 표에 없는 도메인 예외
 * (`LlmProviderException` 계열 포함)는 `else -> null` 로 떨어져 500 [UNMAPPED_DOMAIN_MESSAGE]
 * 가 되고, 그 문구는 계약 `InternalError` 의 선언된 예시다. LLM 실패는 HTTP 상태가 아니라
 * `ConversionResponse.failure_code` 로 사용자에게 간다 — 동기로 LLM 을 부르는 오퍼레이션이 없다.
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
