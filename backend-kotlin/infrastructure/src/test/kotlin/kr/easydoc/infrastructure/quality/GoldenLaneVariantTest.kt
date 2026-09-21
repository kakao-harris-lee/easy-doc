package kr.easydoc.infrastructure.quality

import kr.easydoc.core.exceptions.ConfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 변형 선택만 검증한다. provider·비용·문서 변환을 시작하지 않는 오프라인 테스트다. */
class GoldenLaneVariantTest {
    @Test
    @DisplayName("변형 환경변수가 없으면 기존 기준선을 고른다")
    fun `미설정은 기준선이다`() {
        val variant = GoldenLaneVariant.from { null }

        assertThat(variant).isEqualTo(GoldenLaneVariant.BASELINE)
        assertThat(variant.explanationPromptVersion.name).isEqualTo("BASELINE")
    }

    @Test
    @DisplayName("r3를 명시하면 R3 프롬프트 정책을 고른다")
    fun `r3를 명시한다`() {
        val variant = GoldenLaneVariant.from { name -> if (name == GoldenLaneVariant.ENV) "r3" else null }

        assertThat(variant).isEqualTo(GoldenLaneVariant.R3)
        assertThat(variant.explanationPromptVersion.name).isEqualTo("R3")
    }

    @Test
    @DisplayName("알 수 없는 변형은 유료 호출 전에 거절한다")
    fun `알 수 없는 변형을 거절한다`() {
        assertThatThrownBy {
            GoldenLaneVariant.from { name -> if (name == GoldenLaneVariant.ENV) "experimental" else null }
        }.isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining(GoldenLaneVariant.ENV)
            .hasMessageContaining("baseline")
            .hasMessageContaining("r3")
    }
}
