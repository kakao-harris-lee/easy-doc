package kr.easydoc.infrastructure.document

import kr.easydoc.infrastructure.actionguide.ActionGuideProperties
import kr.easydoc.infrastructure.illustration.suggestion.IllustrationSuggestionProperties
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
        assertThat(capabilities.illustrationSuggestions).isFalse()
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

    @Test
    fun `그림 카탈로그는 독립된 기능 설정을 따른다`() {
        val off =
            reviewCapabilitiesFor(ReviewSupportProperties(), ActionGuideProperties())
        val on =
            reviewCapabilitiesFor(
                ReviewSupportProperties(),
                ActionGuideProperties(),
                illustrationsEnabled = true,
            )

        assertThat(off.illustrations).isFalse()
        assertThat(on.illustrations).isTrue()
        // 켜져도 다른 기능은 따라 켜지지 않는다.
        assertThat(on.tableRelations).isFalse()
        assertThat(on.reviewHistory).isFalse()
        assertThat(on.explanations).isFalse()
    }

    @Test
    fun `그림 제안은 토글과 이용량 단가가 모두 있어야 노출된다`() {
        val toggleOnly =
            reviewCapabilitiesFor(
                ReviewSupportProperties(),
                ActionGuideProperties(),
                illustrationSuggestions = IllustrationSuggestionProperties(enabled = true),
            )
        val configured =
            reviewCapabilitiesFor(
                ReviewSupportProperties(),
                ActionGuideProperties(),
                illustrationSuggestions =
                    IllustrationSuggestionProperties(
                        enabled = true,
                        creditsPer100Chars = java.math.BigDecimal("0.1"),
                    ),
            )
        val rateOnly =
            reviewCapabilitiesFor(
                ReviewSupportProperties(),
                ActionGuideProperties(),
                illustrationSuggestions =
                    IllustrationSuggestionProperties(creditsPer100Chars = java.math.BigDecimal("0.1")),
            )

        // 단가가 없으면 접수가 503이라 기능을 노출하는 것이 거짓말이 된다(명세 §3).
        assertThat(toggleOnly.illustrationSuggestions).isFalse()
        assertThat(rateOnly.illustrationSuggestions).isFalse()
        assertThat(configured.illustrationSuggestions).isTrue()
        // 단가 0은 설정된 값이다 — fake 모드에서 노출된다.
        assertThat(
            reviewCapabilitiesFor(
                ReviewSupportProperties(),
                ActionGuideProperties(),
                illustrationSuggestions =
                    IllustrationSuggestionProperties(enabled = true, creditsPer100Chars = java.math.BigDecimal.ZERO),
            ).illustrationSuggestions,
        ).isTrue()
        // 켜져도 다른 기능은 따라 켜지지 않는다.
        assertThat(configured.illustrations).isFalse()
        assertThat(configured.actionGuide).isFalse()
    }
}
