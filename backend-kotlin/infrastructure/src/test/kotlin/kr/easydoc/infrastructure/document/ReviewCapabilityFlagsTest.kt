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
        assertThat(capabilities.tableRelations).isFalse()
        assertThat(capabilities.reviewHistory).isFalse()
        assertThat(capabilities.explanations).isFalse()
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

    @Test
    fun `표와 이력은 독립된 기능 설정을 따른다`() {
        val tableOnly = reviewCapabilitiesFor(ReviewSupportProperties(), ActionGuideProperties(), true, false)
        val historyOnly = reviewCapabilitiesFor(ReviewSupportProperties(), ActionGuideProperties(), false, true)
        assertThat(tableOnly.tableRelations).isTrue()
        assertThat(tableOnly.reviewHistory).isFalse()
        assertThat(historyOnly.tableRelations).isFalse()
        assertThat(historyOnly.reviewHistory).isTrue()
    }

    @Test
    fun `용어 설명은 독립된 기능 설정을 따른다`() {
        val off =
            reviewCapabilitiesFor(ReviewSupportProperties(), ActionGuideProperties())
        val on =
            reviewCapabilitiesFor(
                ReviewSupportProperties(),
                ActionGuideProperties(),
                explanationsEnabled = true,
            )

        assertThat(off.explanations).isFalse()
        assertThat(on.explanations).isTrue()
        // 켜져도 다른 기능은 따라 켜지지 않는다.
        assertThat(on.tableRelations).isFalse()
        assertThat(on.reviewHistory).isFalse()
    }
}
