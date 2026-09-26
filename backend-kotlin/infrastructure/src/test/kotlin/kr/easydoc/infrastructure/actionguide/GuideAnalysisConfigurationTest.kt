package kr.easydoc.infrastructure.actionguide

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class GuideAnalysisConfigurationTest {
    @Test
    fun `default is disabled and fake requires explicit local or test`() {
        assertThat(requireFakeAnalysisConfiguration(MockEnvironment())).isFalse()
        val enabled = MockEnvironment().withProperty("easydoc.action-guide.analysis-enabled", "true")
        listOf(
            emptyArray(),
            arrayOf("local"),
            arrayOf("action-guide-analysis-fake"),
            arrayOf("local", "action-guide-analysis-fake", "production"),
        ).forEach { profiles ->
            enabled.setActiveProfiles(*profiles)
            assertThatThrownBy {
                requireFakeAnalysisConfiguration(
                    enabled,
                )
            }.isInstanceOf(IllegalStateException::class.java)
        }
        enabled.setActiveProfiles("test", "action-guide-analysis-fake")
        assertThat(requireFakeAnalysisConfiguration(enabled)).isTrue()
    }
}
