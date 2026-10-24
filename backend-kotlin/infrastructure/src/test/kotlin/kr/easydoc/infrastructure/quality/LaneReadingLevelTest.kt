package kr.easydoc.infrastructure.quality

import kr.easydoc.core.document.ReadingLevel
import kr.easydoc.core.exceptions.ConfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 수준 선택만 검증한다. provider·변환을 시작하지 않는 오프라인 테스트다([GoldenLaneVariantTest] 와 같은 방침). */
class LaneReadingLevelTest {
    @Test
    @DisplayName("미설정이면 grade_5_6 — 이 노브를 쓰지 않는 실행과 같다")
    fun `미설정은 기본 수준이다`() {
        assertThat(LaneReadingLevel.from { null }).isEqualTo(ReadingLevel.GRADE_5_6)
    }

    @Test
    @DisplayName("빈 값도 미설정과 같이 기본 수준으로 접는다")
    fun `빈 값이면 기본 수준`() {
        assertThat(LaneReadingLevel.from(env("   "))).isEqualTo(ReadingLevel.GRADE_5_6)
    }

    @Test
    @DisplayName("이 레인이 재는 두 수준의 wire 이름을 그대로 고른다")
    fun `두 값을 고른다`() {
        assertThat(LaneReadingLevel.from(env("grade_5_6"))).isEqualTo(ReadingLevel.GRADE_5_6)
        assertThat(LaneReadingLevel.from(env("grade_3_4"))).isEqualTo(ReadingLevel.GRADE_3_4)
    }

    @Test
    @DisplayName("middle_school 은 judge 기준이 없어 유료 호출 전에 거절한다 — 변환과 채점이 갈린다")
    fun `중학교 수준을 거절한다`() {
        assertThatThrownBy { LaneReadingLevel.from(env("middle_school")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneReadingLevel.ENV)
            .hasMessageContaining("middle_school")
            .hasMessageContaining("judge")
            .hasMessageContaining("중학교")
            .hasMessageContaining("grade_5_6")
            .hasMessageContaining("grade_3_4")
    }

    @Test
    @DisplayName("대문자는 계약 경로(ReadingLevel.ofWireName)와 같이 다른 값으로 본다")
    fun `대소문자를 구분한다`() {
        assertThatThrownBy { LaneReadingLevel.from(env("GRADE_3_4")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("GRADE_3_4")
    }

    @Test
    @DisplayName("알 수 없는 수준은 유료 호출 전에 거절한다")
    fun `알 수 없는 수준을 거절한다`() {
        assertThatThrownBy { LaneReadingLevel.from(env("grade_1_2")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(LaneReadingLevel.ENV)
            .hasMessageContaining("grade_1_2")
            .hasMessageContaining("grade_5_6")
            .hasMessageContaining("grade_3_4")
    }

    private fun env(value: String): (String) -> String? = mapOf(LaneReadingLevel.ENV to value)::get
}
