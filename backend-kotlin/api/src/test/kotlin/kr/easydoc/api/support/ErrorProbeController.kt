package kr.easydoc.api.support

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.LlmTruncatedException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.QueueUnavailableException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.exceptions.UnsupportedFormatException
import kr.easydoc.core.exceptions.UploadTooLargeException
import org.springframework.core.MethodParameter
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 계약 테스트 전용 프로브 컨트롤러. **테스트 소스셋에만 있고 운영 JAR 에 들어가지 않는다.**
 *
 * ## 왜 필요한가
 *
 * `api-contract-freeze` 스킬 §5: "계약 테스트는 **HTTP 경계에서** 돈다. 서비스 계층 단위
 * 테스트로 대신할 수 없다 — 상태 코드 매핑, 헤더 부착, 직렬화 이름은 전부 경계에서
 * 결정되기 때문이다."
 *
 * Phase 1 에는 도메인 예외를 던지는 운영 엔드포인트가 하나도 없다. 그렇다고 핸들러를
 * 직접 호출하는 테스트로 대신하면, 실제로 있었던 이탈(프레임워크 예외가 전부 500)이
 * 존재하는 채로 전건 초록이 된다 — 리뷰 C-2 가 지적한 그 상태다. 그래서 예외를 던지는
 * 프로브 컨트롤러를 세워 **DispatcherServlet 의 예외 해결 경로를 그대로 통과**시킨다.
 *
 * 경로 접두사 `/__probe` 는 계약의 14개 엔드포인트와 겹치지 않는다.
 */
@RestController
@RequestMapping("/__probe")
class ErrorProbeController {
    /** 도메인 예외를 이름으로 골라 던진다. 메시지는 합성이며 실제 개인정보를 넣지 않는다. */
    @GetMapping("/domain/{kind}")
    fun throwDomain(
        @PathVariable kind: String,
    ): ResponseEntity<Void> = throw domainExceptionOf(kind)

    /** 성공 경로가 `GET` 뿐인 자리 — 다른 메서드로 부르면 405 가 나와야 한다. */
    @GetMapping("/get-only")
    fun getOnly(): ResponseEntity<Void> = ResponseEntity.noContent().build()

    /**
     * 컨테이너의 **ERROR 디스패치**를 강제로 태우는 자리.
     *
     * `sendError` 를 부르면 서블릿 컨테이너가 응답을 직접 만들지 않고 `/error` 로 두 번째
     * 디스패치를 돌린다. 이 경로가 H-1 의 후보 원인 ⓐ 가 지목한 자리다 —
     * `OncePerRequestFilter.shouldNotFilterErrorDispatch()` 기본값이 `true` 라 전역 필터가
     * **여기서만** 돌지 않을 수 있다. 그 상태의 증상은 "이 응답에만 헤더가 없다" 하나뿐이라
     * 다른 테스트로는 드러나지 않는다.
     *
     * MockMvc 로는 재현되지 않는다(디스패치가 한 번뿐이다). 실제 컨테이너를 띄우는
     * [kr.easydoc.api.PrivateResponseHeadersReachTest] 가 이 자리를 쓴다.
     */
    @GetMapping("/send-error")
    fun sendError(response: HttpServletResponse) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
    }

    /**
     * `sendError(int, String)` — **두 번째 인자가 응답 본문에 실리는지** 재는 자리.
     *
     * 컨테이너는 그 문자열을 `jakarta.servlet.error.message` 요청 속성으로 넘긴다.
     * `/error` 컨트롤러가 그 속성을 읽으면 호출자가 넘긴 값이 그대로 본문이 되는데, Phase 3
     * 의 인증 필터가 `sendError(401, "비밀번호가 틀렸습니다: $입력값")` 처럼 쓰는 순간
     * 입력값이 응답 본문과 액세스 로그에 남는다. [PROBE_ECHO_MARKER] 가 응답에 나타나지
     * 않아야 한다.
     */
    @GetMapping("/send-error-message")
    fun sendErrorWithMessage(response: HttpServletResponse) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, PROBE_ECHO_MARKER)
    }

    /**
     * **응답을 커밋한 뒤** 실패하는 자리.
     *
     * 상태 줄과 헤더가 이미 소켓으로 나간 뒤에는 어떤 층도 본문을 계약 형태로 바꿀 수 없다.
     * 서블릿 API 에도 Tomcat 밸브에도 이미 보낸 바이트를 되돌리는 수단이 없기 때문이다.
     * Phase 4 의 내려받기(`GET /conversions/{id}/export`)처럼 본문이 긴 응답에서 중간에
     * 실패하면 정확히 이 모양이 된다.
     *
     * **오류 본문 조치의 경계를 못박으려고 둔다.** 여기까지 계약 형태라고 적으면 그것이
     * 거짓이 되고, 반대로 이 자리를 재지 않으면 "전역"이라는 말이 어디까지인지 아무도
     * 모른다.
     */
    @GetMapping("/commit-then-throw")
    fun commitThenThrow(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_OK
        response.contentType = "application/json"
        response.writer.write(COMMITTED_BODY_PREFIX)
        // 여기서 상태 줄·헤더가 나간다. 이후의 실패는 응답 모양을 바꿀 수 없다.
        response.flushBuffer()
        error("커밋 뒤 실패")
    }

    /** 요청 본문을 읽는 자리 — 깨진 JSON 이면 `HttpMessageNotReadableException` 이 난다. */
    @PostMapping("/body")
    fun readBody(
        @RequestBody payload: ProbePayload,
    ): ProbePayload = payload

    /** 필수 쿼리 파라미터 자리 — 누락이면 missing, 형식 오류면 타입 불일치가 난다. */
    @GetMapping("/query")
    fun readQuery(
        @RequestParam limit: Int,
    ): Map<String, Int> = mapOf("limit" to limit)

    /**
     * Bean Validation 실패를 HTTP 경계에서 재현한다.
     *
     * `spring-boot-starter-validation` 은 아직 의존성에 없다(Phase 3 에서 입력 상한을
     * 붙일 때 들어온다). 검증기 없이도 **핸들러가 실제로 타는 경로**를 확인하려고 예외를
     * 직접 만들어 던진다 — 예외가 DispatcherServlet 의 해결 경로를 그대로 통과하므로
     * 핸들러 직접 호출과 달리 상태 코드·헤더·직렬화가 모두 경계에서 결정된다.
     */
    @PostMapping("/bean-validation")
    fun beanValidationFailure(): ResponseEntity<Void> {
        val target = ProbePayload(email = "")
        val binding = BeanPropertyBindingResult(target, "probePayload")
        // rejectedValue 는 FieldError 안에 남는다 — 응답에 실리지 않는지가 이 프로브의 요점이다.
        binding.rejectValue("email", "NotBlank", "must not be blank")
        val parameter = MethodParameter(javaClass.getDeclaredMethod("beanValidationFailure"), -1)
        throw MethodArgumentNotValidException(parameter, binding)
    }

    /** 요청/응답 본문 자리표시자. 필드 이름은 계약대로 snake_case 다. */
    data class ProbePayload(val email: String)

    private companion object {
        /**
         * 프로브가 던질 도메인 예외 표.
         *
         * 메시지는 전부 합성 문자열이다 — 계약 테스트가 "예외 메시지가 그대로 detail 이
         * 된다"를 단언하므로 값이 눈에 보여야 하고, 실제 사용자 데이터를 넣으면 안 된다.
         */
        val DOMAIN_EXCEPTIONS: Map<String, () -> EasyDocException> =
            mapOf(
                "invalid-input" to { InvalidInputException("제목이 너무 깁니다") },
                "unsupported-format" to { UnsupportedFormatException("hwp 는 지원하지 않습니다") },
                "extraction" to { DocumentExtractionException("pdf 파일을 읽을 수 없습니다") },
                "too-large" to { UploadTooLargeException("파일이 너무 큽니다") },
                "duplicate-email" to { EmailAlreadyRegisteredException("이미 가입된 이메일입니다") },
                "conflict" to { ConflictException("아직 완료되지 않았습니다") },
                "credentials" to { InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다") },
                "not-found" to { NotFoundException("문서를 찾을 수 없습니다") },
                "queue" to { QueueUnavailableException("변환 요청을 등록하지 못했습니다") },
                "llm-truncated" to { LlmTruncatedException("응답이 잘렸습니다") },
                "configuration" to { ConfigurationException("JWT 비밀키가 설정되지 않았습니다") },
                "storage" to { StorageException("저장된 변환 결과를 읽을 수 없습니다") },
                "unmapped" to { EasyDocException("입력값 홍길동 이 섞인 메시지") },
            )

        /** 표에 없는 종류는 도메인 **밖** 예외가 된다 — 마지막 백스톱(500)을 재현하는 자리다. */
        fun domainExceptionOf(kind: String): EasyDocException = (DOMAIN_EXCEPTIONS[kind] ?: error("알 수 없는 프로브 종류"))()
    }
}

/**
 * `sendError(int, String)` 의 두 번째 인자로 쓰는 표식.
 *
 * 실제 개인정보가 아니라 합성 문자열이다. 응답 본문·헤더 어디에도 이 문자열이 나타나면
 * 안 된다 — 나타난다면 호출자가 넘긴 값이 그대로 밖으로 나가는 경로가 살아 있다는 뜻이다.
 */
const val PROBE_ECHO_MARKER: String = "probe-echo-표식-절대-노출-금지"

/** 커밋 뒤 실패 프로브가 먼저 흘려보내는 조각. 합성값이라 노출돼도 무방하다. */
const val COMMITTED_BODY_PREFIX: String = """{"partial":"""

/**
 * **필터가 던진 예외**를 재현하는 테스트 전용 필터.
 *
 * `@RestControllerAdvice` 는 `DispatcherServlet` 안에서만 돈다. 필터 체인에서 던져진 예외는
 * 디스패처에 닿지 못해 advice 가 잡을 기회가 없고, 컨테이너가 500 을 만들어 `/error` 로 두
 * 번째 디스패치를 돌린다. **Phase 3 의 인증 필터가 정확히 이 자리에 선다** — 토큰 파싱이
 * 실패해 예외가 새면 그 응답이 계약 형태인지가 여기서 갈린다.
 *
 * `@Component` 라 `kr.easydoc.api` 컴포넌트 스캔에 잡히지만, 지정한 한 경로에서만 던지므로
 * 다른 테스트에 영향을 주지 않는다. **테스트 소스셋에만 있어 운영 JAR 에 들어가지 않는다.**
 */
@Component
class ThrowingProbeFilter : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        if ((request as? HttpServletRequest)?.requestURI == THROWING_FILTER_PATH) {
            // 메시지에 표식을 넣는다 — 예외 메시지가 본문으로 새는지 함께 잰다.
            throw ServletException(PROBE_ECHO_MARKER)
        }
        chain.doFilter(request, response)
    }
}

/** [ThrowingProbeFilter] 가 예외를 던지는 경로. 계약의 14개 엔드포인트와 겹치지 않는다. */
const val THROWING_FILTER_PATH: String = "/__probe/filter-throws"
