package kr.easydoc.api.support

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

private val JSON = ObjectMapper()

/** 오류 본문 계약의 판정 한 곳. */
fun assertContractErrorBody(
    response: RawHttpResponse,
    expectedStatus: Int,
) {
    assertThat(response.statusCode)
        .withFailMessage(
            "상태 코드가 %d 가 아니라 %d 다 — 이 측정의 전제가 깨졌다",
            expectedStatus,
            response.statusCode,
        ).isEqualTo(expectedStatus)

    assertThat(response.contentType.orEmpty())
        .withFailMessage(
            "오류 본문 Content-Type 이 '%s' 다 — 계약은 application/json 하나다 " +
                "(problem+json 도 text/html 도 아니다)",
            response.contentType,
        ).startsWith("application/json")

    val root = parseJsonBody(response)
    assertThat(root.isObject)
        .withFailMessage("오류 본문이 JSON 객체가 아니다: %s", response.bodyText.take(BODY_PREVIEW_LENGTH))
        .isTrue()

    assertThat(root.propertyNames())
        .withFailMessage(
            "오류 본문의 최상위 키가 %s 다 — 계약은 detail 하나뿐이다. 실제 본문: %s",
            root.propertyNames(),
            response.bodyText.take(BODY_PREVIEW_LENGTH),
        ).containsExactly("detail")

    assertThat(response.bodyText)
        .withFailMessage("오류 본문에 호출자가 넘긴 문자열이 그대로 실렸다 — 입력값 에코 금지 위반")
        .doesNotContain(PROBE_ECHO_MARKER)
}

private fun parseJsonBody(response: RawHttpResponse): JsonNode =
    runCatching { JSON.readTree(response.body) }
        .getOrElse { failure ->
            throw AssertionError(
                "오류 본문이 JSON 이 아니다 (${failure::class.java.simpleName}). " +
                    "실제 본문: ${response.bodyText.take(BODY_PREVIEW_LENGTH)}",
                failure,
            )
        }

/** 실패 메시지에 본문을 통째로 붙이면 읽을 수 없다. 프로브 본문은 합성값이라 노출해도 된다. */
private const val BODY_PREVIEW_LENGTH = 200
