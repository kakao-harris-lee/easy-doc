package kr.easydoc.api.error

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

// 오류 본문 계약의 공통 조각.
//
// 본문을 만드는 경로가 셋이라 조각을 한곳에 모은다 — advice(GlobalExceptionHandler),
// `/error` 디스패치(ContractErrorController), 컨테이너가 직접 만드는 응답
// (ContractErrorReportValve). 경로마다 값이 갈리면 같은 상태 코드에 다른 본문이 나가고,
// 그 차이가 "이 응답은 어느 층에서 만들어졌다"는 정보를 밖으로 흘린다.

/** 계약 오류 본문의 Content-Type. */
internal const val CONTRACT_ERROR_CONTENT_TYPE: String = "application/json;charset=UTF-8"

/** HTTP 상태 코드로 쓸 수 있는 값으로 좁힌다. */
internal fun clampedHttpStatusCode(raw: Int?): Int =
    raw?.takeIf { it in MIN_HTTP_STATUS..MAX_HTTP_STATUS }
        ?: HttpStatus.INTERNAL_SERVER_ERROR.value()

/** `{"detail":"…"}` 한 줄을 만든다. **Jackson 없이 JSON 을 만드는 유일한 자리다.** */
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
