package kr.easydoc.infrastructure.document

import kr.easydoc.infrastructure.actionguide.ActionGuideProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReviewCapabilityFlagsTest {
    @Test
    fun `행동 안내는 기본적으로 비공개다`() {
        val capabilities =
            reviewCapabilitiesFor(ReviewSupportProperties(), ActionGuideProperties())

        assertThat(capabilities.actionGuide).isFalse()
        assertThat(capabilities.reviewSupport).isFalse()
    }

    @Test
    fun `각 검수 기능 플래그가 조회 응답에 독립적으로 반영된다`() {
        val capabilities =
            reviewCapabilitiesFor(
                ReviewSupportProperties(enabled = true),
                ActionGuideProperties(enabled = true),
            )

        assertThat(capabilities.actionGuide).isTrue()
        assertThat(capabilities.reviewSupport).isTrue()
    }
}
