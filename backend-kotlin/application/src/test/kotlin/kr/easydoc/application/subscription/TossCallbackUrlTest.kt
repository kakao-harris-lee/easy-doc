package kr.easydoc.application.subscription

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TossCallbackUrlTest {
    @Test
    fun `콜백 경로는 계약이 고정한 값이다`() {
        assertThat(TossCallbackUrl.PATH).isEqualTo("/api/payments/toss/billing/callback")
    }

    @Test
    fun `공개 기준 URL 의 끝 슬래시를 겹치지 않게 이어 붙인다`() {
        val expected = "https://easydoc.kr/api/payments/toss/billing/callback"
        assertThat(TossCallbackUrl.from("https://easydoc.kr")).isEqualTo(expected)
        assertThat(TossCallbackUrl.from("https://easydoc.kr/")).isEqualTo(expected)
        assertThat(TossCallbackUrl.from("https://easydoc.kr///")).isEqualTo(expected)
    }
}
