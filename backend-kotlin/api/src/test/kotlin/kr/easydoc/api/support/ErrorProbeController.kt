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
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.InvalidOAuthStateException
import kr.easydoc.core.exceptions.LlmTruncatedException
import kr.easydoc.core.exceptions.NotFoundException
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

/** 계약 테스트 전용 프로브 컨트롤러. 테스트 소스셋에만 있고 운영 JAR 에 들어가지 않는다. */
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

    /** 컨테이너의 ERROR 디스패치를 강제로 태우는 자리. */
    @GetMapping("/send-error")
    fun sendError(response: HttpServletResponse) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
    }

    /** `sendError(int, String)` — 두 번째 인자가 응답 본문에 실리는지 재는 자리. */
    @GetMapping("/send-error-message")
    fun sendErrorWithMessage(response: HttpServletResponse) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, PROBE_ECHO_MARKER)
    }

    /** 응답을 커밋한 뒤 실패하는 자리. */
    @GetMapping("/commit-then-throw")
    fun commitThenThrow(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_OK
        response.contentType = "application/json"
        response.writer.write(COMMITTED_BODY_PREFIX)

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

    /** Bean Validation 실패를 HTTP 경계에서 재현한다. */
    @PostMapping("/bean-validation")
    fun beanValidationFailure(): ResponseEntity<Void> {
        val target = ProbeValidationPayload(probe = "")
        val binding = BeanPropertyBindingResult(target, "probeValidationPayload")

        binding.rejectValue("probe", "NotBlank", "must not be blank")
        val parameter = MethodParameter(javaClass.getDeclaredMethod("beanValidationFailure"), -1)
        throw MethodArgumentNotValidException(parameter, binding)
    }

    /** 요청/응답 본문 자리표시자. 필드 이름은 계약대로 snake_case 다. */
    data class ProbePayload(val email: String)

    /**
     * 스키마 층 검증 프로브 전용 본문. 계약의 어떤 필드도 흉내 내지 않는다 —
     * 이름이 계약 필드와 겹치면 그 필드가 스키마 층에서 검증돼도 된다는 뜻으로 읽힌다.
     */
    data class ProbeValidationPayload(val probe: String)

    private companion object {
        /** 프로브가 던질 도메인 예외 표. */
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
                "llm-truncated" to { LlmTruncatedException("응답이 잘렸습니다") },
                "configuration" to { ConfigurationException("JWT 비밀키가 설정되지 않았습니다") },
                "storage" to { StorageException("저장된 변환 결과를 읽을 수 없습니다") },
                "oauth-state" to { InvalidOAuthStateException("요청이 만료되었거나 이미 사용되었습니다") },
                "external-unavailable" to { ExternalServiceUnavailableException("구글에 연결하지 못했습니다") },
                "unmapped" to { EasyDocException("입력값 홍길동 이 섞인 메시지") },
            )

        /** 표에 없는 종류는 도메인 밖 예외가 된다 — 마지막 백스톱(500)을 재현하는 자리다. */
        fun domainExceptionOf(kind: String): EasyDocException = (DOMAIN_EXCEPTIONS[kind] ?: error("알 수 없는 프로브 종류"))()
    }
}

/** `sendError(int, String)` 의 두 번째 인자로 쓰는 표식. */
const val PROBE_ECHO_MARKER: String = "probe-echo-표식-절대-노출-금지"

/** 커밋 뒤 실패 프로브가 먼저 흘려보내는 조각. 합성값이라 노출돼도 무방하다. */
const val COMMITTED_BODY_PREFIX: String = """{"partial":"""

/** 필터가 던진 예외를 재현하는 테스트 전용 필터. */
@Component
class ThrowingProbeFilter : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        if ((request as? HttpServletRequest)?.requestURI == THROWING_FILTER_PATH) {
            throw ServletException(PROBE_ECHO_MARKER)
        }
        chain.doFilter(request, response)
    }
}

/** [ThrowingProbeFilter] 가 예외를 던지는 경로. 계약의 14개 엔드포인트와 겹치지 않는다. */
const val THROWING_FILTER_PATH: String = "/__probe/filter-throws"
