package kr.easydoc.api

import jakarta.validation.Validation
import kr.easydoc.api.document.GuideRevisionRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RevisionBoundaryTest {
    @Test
    fun `content and analysis start at one while review starts at zero with exact JS ceiling`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val validator = factory.validator
            val ceiling = 9_007_199_254_740_991L
            assertThat(validator.validate(GuideRevisionRequest(1, 1, 0))).isEmpty()
            assertThat(validator.validate(GuideRevisionRequest(ceiling, ceiling, ceiling))).isEmpty()
            listOf(
                GuideRevisionRequest(0, 1, 0) to "expectedContentRevision",
                GuideRevisionRequest(1, 0, 0) to "expectedAnalysisRevision",
                GuideRevisionRequest(1, 1, -1) to "expectedReviewRevision",
                GuideRevisionRequest(ceiling + 1, 1, 0) to "expectedContentRevision",
                GuideRevisionRequest(1, ceiling + 1, 0) to "expectedAnalysisRevision",
                GuideRevisionRequest(1, 1, ceiling + 1) to "expectedReviewRevision",
            ).forEach { (request, field) ->
                assertThat(validator.validate(request).map { it.propertyPath.toString() }).containsExactly(field)
            }
        }
    }
}
