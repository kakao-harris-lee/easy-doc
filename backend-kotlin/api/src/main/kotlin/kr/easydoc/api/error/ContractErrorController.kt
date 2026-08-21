package kr.easydoc.api.error

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `/error` 디스패치의 본문을 계약 형태로 만든다. */
@RestController
class ContractErrorController : ErrorController {
    /**
     * 컨테이너가 등록한 오류 페이지 경로. 기본값은 `/error` 이고
     * `ErrorMvcAutoConfiguration.ErrorPageCustomizer` 가 같은 속성을 본다.
     */
    @RequestMapping("\${server.error.path:\${error.path:/error}}")
    fun handleError(request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        // 읽는 요청 속성은 상태 코드 하나뿐이다. message·exception 은 읽지 않는다.
        val statusCode = clampedHttpStatusCode(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
        return ResponseEntity
            .status(statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(reasonPhraseOf(HttpStatusCode.valueOf(statusCode))))
    }
}
