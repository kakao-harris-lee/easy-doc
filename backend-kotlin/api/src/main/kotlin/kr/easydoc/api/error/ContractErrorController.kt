package kr.easydoc.api.error

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/error` 디스패치의 본문을 계약 형태로 만든다.
 *
 * ## 무엇이 있었나 (2026-08-12 실측)
 *
 * `GlobalExceptionHandler` 는 **`DispatcherServlet` 안에서 던져진 예외**만 잡는다. 그
 * 바깥에서 만들어진 오류 응답은 컨테이너가 `/error` 로 두 번째 디스패치를 돌리고, 거기서
 * Spring Boot 기본 [org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController]
 * 가 응답한다. 원시 소켓 측정 결과:
 *
 * | 요청 | 고치기 전 본문의 최상위 키 |
 * |---|---|
 * | `sendError(503)` | `timestamp`, `status`, `error`, `path` |
 * | 필터가 던진 예외 → 500 | `timestamp`, `status`, `error`, `path` |
 * | `GET /error` 직접 | `timestamp`, `status`(=`999`), `error`(=`"None"`) |
 *
 * 계약이 요구하는 것은 **최상위 키 `detail` 하나**인데 셋 다 그것이 아니다. 지금은 운영
 * 코드가 `sendError` 를 부르지 않아 드러나지 않지만, **Phase 3 에서 인증 필터가 401 을
 * `sendError` 로 내는 것이 가장 흔한 구현**이다. 그때 발견하면 이미 그 위에 인증 코드가
 * 쌓여 있다.
 *
 * ## 왜 `ErrorAttributes` 교체가 아니라 컨트롤러 교체인가
 *
 * `ErrorAttributes` 만 바꾸면 `BasicErrorController` 가 그 map 을 그대로 직렬화하므로
 * `{"detail": …}` 를 만들 수는 있다. 하지만 `BasicErrorController` 에는 `Accept: text/html`
 * 을 받는 `errorHtml` 분기가 따로 있어 **본문 형태가 요청 헤더에 따라 갈린다.** 계약은
 * 갈리지 않는다. 컨트롤러를 통째로 대신하면 분기 자체가 사라진다.
 *
 * `ErrorMvcAutoConfiguration.basicErrorController` 가
 * `@ConditionalOnMissingBean(ErrorController::class, search = CURRENT)` 이므로, 이 빈이
 * 있으면 기본 컨트롤러는 아예 만들어지지 않는다.
 *
 * ## 상태 코드 말고는 아무것도 읽지 않는다
 *
 * 요청 속성에는 `jakarta.servlet.error.message` 와 `jakarta.servlet.error.exception` 이
 * 함께 실려 있다. **둘 다 읽지 않는다.** `sendError(401, "비밀번호가 틀렸습니다: $입력값")`
 * 같은 호출 한 번이면 입력값이 응답 본문과 액세스 로그에 남는다. 상태 코드에서 유도한
 * 표준 사유 문구만 쓰면 그 경로가 아예 없다 — 인증 실패 응답의 균일성도 같은 이유로
 * 유지된다.
 */
@RestController
class ContractErrorController : ErrorController {
    /**
     * 컨테이너가 등록한 오류 페이지 경로. 기본값은 `/error` 이고
     * `ErrorMvcAutoConfiguration.ErrorPageCustomizer` 가 같은 속성을 본다.
     *
     * `produces` 를 두지 않고 [MediaType.APPLICATION_JSON] 을 응답에 직접 적는다. 명시된
     * Content-Type 은 내용 협상보다 우선하므로 `Accept` 가 무엇이든 같은 본문이 나간다.
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
