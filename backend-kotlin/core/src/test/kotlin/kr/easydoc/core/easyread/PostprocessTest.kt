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

/**
 * 후처리가 껍데기만 벗기고 **본문은 건드리지 않는지** 고정한다.
 *
 * ## 음성 대조가 이 테스트의 요점이다
 *
 * 두 실패의 비용이 비대칭이다.
 *
 * - **껍데기가 남으면**: 검수자 눈에 즉시 보이고, 지우면 끝난다.
 * - **본문이 잘리면**: 무엇이 지워졌는지 원문과 대조하지 않으면 알 수 없다. 지워진 것이
 *   대상 조건이나 신청 마감일이면 시민이 잘못된 안내를 받는다.
 *
 * 그래서 `keep_*` 케이스들이 양성 케이스만큼 많다. "다음은 심사 결과입니다."처럼
 * **정상 본문의 첫 줄이 머리말과 겹쳐 보이는** 자리를 골라 두었다 — 신호를 '결과'
 * 부분 문자열까지 넓히면 그 순간 전부 깨진다.
 *
 * ## 정규식 방언 차이를 잡는다
 *
 * 같은 패턴 문자열이라도 Python `re` 와 Java 정규식은 뜻이 갈리는 자리가 있다.
 * 이 케이스들이 그것을 실행으로 확인한다.
 *
 * - `\Z` — Java 는 **마지막 줄바꿈 앞**에서도 성립하고 Python 은 절대 끝에서만 성립한다.
 *   구현은 Java `\z` 를 쓴다(`fence_close_trailing_spaces`).
 * - `\s` — Java 기본값은 ASCII 전용이라 NBSP 를 공백으로 보지 않는다(`nbsp_padding`,
 *   `preamble_colon_with_spaces`).
 */
class PostprocessTest {
    @ParameterizedTest(name = "{0} — {1}")
    @MethodSource("cases")
    @DisplayName("Python 실측 케이스와 결과가 같다")
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
        // 케이스 목록이 조용히 줄어드는 사고를 잡는다. 음성 대조가 사라지면 이 파일은
        // "껍데기를 잘 벗기는가"만 검사하게 되고, 정작 비싼 실패 방향이 비어 버린다.
        val negatives = cases().count { (it.get()[0] as String).startsWith("keep_") }
        assertThat(negatives).isGreaterThanOrEqualTo(MIN_NEGATIVE_CASES)
    }

    @Test
    @DisplayName("머리말 신호를 '결과' 부분 문자열로 넓히지 않았다")
    fun `결과라는 낱말만으로는 지우지 않는다`() {
        // 이 단언은 신호 정규식의 **좁음**을 직접 고정한다. 위 케이스 목록과 달리 스냅샷을
        // 다시 뽑아도 사라지지 않는다 — 넓히는 변경을 하려면 이 테스트를 손대야 한다.
        val body = "다음은 심사 결과입니다.\n결과를 확인하세요."
        assertThat(postprocess(body)).isEqualTo(body)
    }

    @Test
    @DisplayName("머리말 뒤에 본문이 없으면 통째로 날리지 않는다")
    fun `한 줄짜리 응답을 비우지 않는다`() {
        // 이것이 없으면 한 줄 응답이 통째로 사라져 빈 변환 결과가 저장된다.
        val onlyLine = "다음은 변환 결과입니다:"
        assertThat(postprocess(onlyLine)).isEqualTo(onlyLine)
    }

    private companion object {
        const val SNAPSHOT_RESOURCE = "/kr/easydoc/core/easyread/python-prompt-snapshot.json"
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
