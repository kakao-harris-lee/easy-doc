package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 반복 횟수 노브의 선택 규칙을 유료 호출 없이 고정한다. [LaneTranscriptTest] 와 같은 방침이다.
 */
class LaneRunsTest {
    @Test
    @DisplayName("미설정이면 1회다 — 반복 없이 도는 기존 동작과 같다")
    fun `미설정이면 기본값 1`() {
        assertThat(LaneRuns.of { null }).isEqualTo(1)
    }

    @Test
    @DisplayName("빈 값도 미설정과 같이 기본값으로 접는다")
    fun `빈 값이면 기본값`() {
        assertThat(LaneRuns.of(env(""))).isEqualTo(1)
    }

    @Test
    @DisplayName("양의 정수를 설정하면 그 값을 그대로 쓴다")
    fun `설정한 값을 쓴다`() {
        assertThat(LaneRuns.of(env("3"))).isEqualTo(3)
    }

    @Test
    @DisplayName("정수가 아니면 유료 호출을 시작하기 전에 거절한다")
    fun `정수가 아니면 거절한다`() {
        assertThatThrownBy { LaneRuns.of(env("세번")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneRuns.RUNS_ENV)
            .hasMessageContaining("세번")
    }

    @Test
    @DisplayName("0 은 거절한다 — 0 번 돈다는 말이 되지 않는다")
    fun `0 은 거절한다`() {
        assertThatThrownBy { LaneRuns.of(env("0")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("1 이상")
    }

    @Test
    @DisplayName("음수는 거절한다")
    fun `음수는 거절한다`() {
        assertThatThrownBy { LaneRuns.of(env("-1")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("1 이상")
    }

    private fun env(value: String): (String) -> String? = mapOf(LaneRuns.RUNS_ENV to value)::get
}
