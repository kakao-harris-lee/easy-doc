package kr.easydoc.core.segment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 행동 안내 후보(ER-06)와 그림 제안(R7 ER-17)이 함께 쓰는 원문 앵커 규칙.
 * 행동 안내 쪽 동작은 `ActionGuideCandidateTest` 가 계속 재고, 여기서는 규칙 자체를 잰다.
 */
class SourceAnchorEvidenceTest {
    private val sourceUnits =
        listOf(
            "신청 대상은 만 열아홉 살 이상입니다.",
            "신청서는 주민센터에 냅니다.",
            "결과는 주민센터에서 알려 드립니다.",
        )

    @Test
    fun `지정한 모든 줄에 있는 인용은 근거로 인정한다`() {
        assertThat(isSourceAnchorSupported(listOf(1, 2), "주민센터", sourceUnits)).isTrue()
    }

    @Test
    fun `이어진 줄을 건너뛰지 않으면 줄바꿈을 넘는 인용도 근거로 인정한다`() {
        assertThat(isSourceAnchorSupported(listOf(0, 1), "이상입니다.\n신청서는", sourceUnits)).isTrue()
    }

    @Test
    fun `떨어진 줄은 이어 붙이지 않는다`() {
        assertThat(isSourceAnchorSupported(listOf(0, 2), "이상입니다.\n신청서는", sourceUnits)).isFalse()
    }

    @Test
    fun `그 줄에 없는 인용은 근거가 아니다`() {
        assertThat(isSourceAnchorSupported(listOf(1), "동사무소", sourceUnits)).isFalse()
    }

    @Test
    fun `원문 범위 밖 줄 번호는 근거가 아니다`() {
        assertThat(isSourceAnchorSupported(listOf(3), "주민센터", sourceUnits)).isFalse()
        assertThat(isSourceAnchorSupported(listOf(-1), "주민센터", sourceUnits)).isFalse()
    }

    @Test
    fun `줄 번호가 하나도 없으면 근거가 아니다`() {
        assertThat(isSourceAnchorSupported(emptyList(), "주민센터", sourceUnits)).isFalse()
    }
}
