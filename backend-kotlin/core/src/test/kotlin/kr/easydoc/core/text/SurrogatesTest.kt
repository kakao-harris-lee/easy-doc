package kr.easydoc.core.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 짝 없는 UTF-16 서로게이트의 판정 한 곳 — Spring 도 DB 도 없이 돈다(계획 §3.2). */
class SurrogatesTest {
    @Test
    @DisplayName("짝 없는 서로게이트를 네 모양 전부에서 찾아낸다 (상위 뒤 없음 · 홀로 하위 · 끝 잘림 · 상위 연속)")
    fun `짝 없는 서로게이트를 찾아낸다`() {
        BROKEN.forEach { (label, probe) ->
            assertThat(hasUnpairedSurrogate(probe)).describedAs("%s", label).isTrue()
        }
    }

    @Test
    @DisplayName("정상 텍스트와 **짝을 이룬 쌍**은 짝 없는 서로게이트가 아니다 — 판정이 넓어지지 않았다")
    fun `정상 텍스트는 짝 없는 서로게이트가 아니다`() {
        INTACT.forEach { (label, probe) ->
            assertThat(hasUnpairedSurrogate(probe)).describedAs("%s", label).isFalse()
        }
    }

    @Test
    @DisplayName("걷어내면 짝 없는 서로게이트만 사라지고 나머지 문자는 그대로다")
    fun `짝 없는 것만 걷어낸다`() {
        assertThat(stripUnpairedSurrogates("x\uD800y")).isEqualTo("xy")
        assertThat(stripUnpairedSurrogates("x\uDC00y")).isEqualTo("xy")
        assertThat(stripUnpairedSurrogates("안내문\uD83D")).isEqualTo("안내문")
        assertThat(stripUnpairedSurrogates("\uD800\uD800")).isEmpty()

        assertThat(stripUnpairedSurrogates("🙂\uD800🙂")).isEqualTo("🙂🙂")
    }

    @Test
    @DisplayName("걷어낸 결과에는 짝 없는 서로게이트가 없다 — UTF-8 왕복이 값을 바꾸지 않는다")
    fun `걷어낸 결과가 UTF-8 로 왕복한다`() {
        BROKEN.forEach { (label, probe) ->
            val cleaned = stripUnpairedSurrogates(probe)

            assertThat(hasUnpairedSurrogate(cleaned)).describedAs("%s — 걷어낸 뒤에도 남았다", label).isFalse()
            assertThat(String(cleaned.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
                .describedAs("%s — 걷어낸 값이 UTF-8 왕복에서 바뀌었다", label)
                .isEqualTo(cleaned)
        }
    }

    @Test
    @DisplayName("정상 텍스트는 **바뀌지 않는다** — 이모지도 결합 문자도 그대로 남는다")
    fun `정상 텍스트는 그대로다`() {
        INTACT.forEach { (label, probe) ->
            assertThat(stripUnpairedSurrogates(probe)).describedAs("%s", label).isEqualTo(probe)
        }
    }

    /** 두 함수가 같은 판정을 쓴다는 것을 값으로 되짚는다. */
    @Test
    @DisplayName("「있다고 말하는 것」과 「걷어내는 것」이 정확히 같은 값에서 갈린다")
    fun `두 함수가 같은 판정을 쓴다`() {
        val probes = BROKEN + INTACT

        assertThat(probes).describedAs("표본이 비면 이 대조는 아무것도 재지 않는다").isNotEmpty()
        probes.forEach { (label, probe) ->
            assertThat(hasUnpairedSurrogate(probe))
                .describedAs("%s — 판정과 정제가 갈렸다", label)
                .isEqualTo(stripUnpairedSurrogates(probe) != probe)
        }
    }

    private companion object {
        /** 짝 없는 서로게이트가 든 값들. 라벨은 실패 메시지에만 쓴다. */
        val BROKEN =
            listOf(
                "상위 서로게이트 뒤에 하위가 없다" to "x\uD800y",
                "하위 서로게이트가 홀로 나온다" to "x\uDC00y",
                "상위 서로게이트로 문자열이 끝난다" to "안내문\uD83D",
                "상위 뒤에 또 상위가 온다" to "\uD800\uD800",
            )

        /** 짝 없는 서로게이트가 없는 값들. 정제가 넓어지지 않았음을 재는 표본이다. */
        val INTACT =
            listOf(
                "빈 값" to "",
                "한글" to "행정복지센터에서 신청하세요.",
                "짝 맞는 서로게이트 쌍(이모지)" to "이모지 🙂 와 결합 문자 각́",
                "BMP 밖 문자" to "𝓐𝓑𝓒",
                "제어문자" to "\u0000앞\u0007뒤",
            )
    }
}
