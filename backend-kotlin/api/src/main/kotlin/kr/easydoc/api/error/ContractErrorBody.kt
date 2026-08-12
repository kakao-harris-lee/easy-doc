package kr.easydoc.api.error

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

// 오류 본문 계약의 공통 조각.
//
// 본문을 만드는 경로가 셋이라 조각을 한곳에 모은다 — advice(GlobalExceptionHandler),
// `/error` 디스패치(ContractErrorController), 컨테이너가 직접 만드는 응답
// (ContractErrorReportValve). 경로마다 값이 갈리면 같은 상태 코드에 다른 본문이 나가고,
// 그 차이가 "이 응답은 어느 층에서 만들어졌다"는 정보를 밖으로 흘린다.

/**
 * 계약 오류 본문의 Content-Type.
 *
 * `application/problem+json` 이 아니다 — 계약은 `application/json` 하나이고, 그것을
 * 기대하는 프록시·클라이언트가 `problem+json` 에서 갈린다.
 */
internal const val CONTRACT_ERROR_CONTENT_TYPE: String = "application/json;charset=UTF-8"

/**
 * HTTP 상태 코드로 쓸 수 있는 값으로 좁힌다.
 *
 * 범위 밖 값이나 없는 값을 그대로 쓰면 `HttpStatusCode.valueOf` 가 던져 **오류 응답을
 * 만들다가 다시 오류가 난다.** 고치기 전 `GET /error` 가 본문에 `"status":999` 를 싣던
 * 자리이기도 하다(`DefaultErrorAttributes` 가 상태 속성이 없을 때 쓰는 자리표시자).
 */
internal fun clampedHttpStatusCode(raw: Int?): Int =
    raw?.takeIf { it in MIN_HTTP_STATUS..MAX_HTTP_STATUS }
        ?: HttpStatus.INTERNAL_SERVER_ERROR.value()

/**
 * `{"detail":"…"}` 한 줄을 만든다. **Jackson 없이 JSON 을 만드는 유일한 자리다.**
 *
 * Tomcat 밸브는 Spring `HttpMessageConverter` 를 쓸 수 없다 — 서블릿 컨테이너가 요청을
 * 서블릿에 매핑조차 하지 못한 응답에서 도는 코드이기 때문이다. 담는 값이
 * [reasonPhraseOf] 가 만든 문자열(HTTP 표준 사유 문구 또는 고정 한국어 문장)뿐이라
 * 이스케이프 대상이 사실상 없지만, **여기에 다른 문자열을 넣는 변경이 조용히 깨진 JSON 을
 * 만들지 않도록** 최소 이스케이프를 둔다.
 */
internal fun contractErrorJson(statusCode: Int): String {
    val detail = reasonPhraseOf(HttpStatusCode.valueOf(clampedHttpStatusCode(statusCode)))
    return """{"detail":"${escapeJsonString(detail)}"}"""
}

/** JSON 문자열 리터럴 안에서 반드시 escape 해야 하는 것만 다룬다. */
private fun escapeJsonString(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
    }

private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599
