package kr.easydoc.core.easyread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** 후처리가 껍데기만 벗기고 본문은 건드리지 않는지 고정한다. */
class PostprocessTest {
    @ParameterizedTest(name = "{0} — {1}")
    @MethodSource("cases")
    @DisplayName("스냅샷 케이스와 결과가 같다")
    fun `후처리 결과가 스냅샷과 일치한다`(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") note: String,
        raw: String,
        expected: String,
    ) {
        assertThat(postprocess(raw)).isEqualTo(expected)
    }

    @Test
    @DisplayName("본문을 지우는 방향의 실패는 케이스로 덮여 있다")
    fun `과잉 제거 음성 대조가 실재한다`() {
        val negatives = cases().count { (it.get()[0] as String).startsWith("keep_") }
        assertThat(negatives).isGreaterThanOrEqualTo(MIN_NEGATIVE_CASES)
    }

    @Test
    @DisplayName("머리말 신호를 '결과' 부분 문자열로 넓히지 않았다")
    fun `결과라는 낱말만으로는 지우지 않는다`() {
        val body = "다음은 심사 결과입니다.\n결과를 확인하세요."
        assertThat(postprocess(body)).isEqualTo(body)
    }

    @Test
    @DisplayName("머리말 뒤에 본문이 없으면 통째로 날리지 않는다")
    fun `한 줄짜리 응답을 비우지 않는다`() {
        val onlyLine = "다음은 변환 결과입니다:"
        assertThat(postprocess(onlyLine)).isEqualTo(onlyLine)
    }

    private companion object {
        const val SNAPSHOT_RESOURCE = "/kr/easydoc/core/easyread/prompt-snapshot.json"
        const val MIN_NEGATIVE_CASES = 9

        @JvmStatic
        fun cases(): List<Arguments> =
            loadSnapshot().getValue("postprocess").jsonArray.map { element ->
                val case = element.jsonObject
                Arguments.of(
                    case.string("name"),
                    case.string("note"),
                    case.string("raw"),
                    case.string("expected"),
                )
            }

        fun loadSnapshot(): JsonObject {
            val stream =
                PostprocessTest::class.java.getResourceAsStream(SNAPSHOT_RESOURCE)
                    ?: error("후처리 스냅샷이 없다: $SNAPSHOT_RESOURCE")
            return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        }

        fun JsonObject.string(key: String): String =
            getValue(key)
                .jsonPrimitive.content
    }
}
