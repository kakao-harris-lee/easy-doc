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

    @Test
    fun `형식 검사는 원문 없이 인용과 줄 번호 모양만 본다`() {
        assertThat(isSourceAnchorShapeValid(listOf(0, 1), "원문에 없어도 형식은 통과한다")).isTrue()
        assertThat(isSourceAnchorShapeValid(listOf(0), " ")).isFalse()
        assertThat(isSourceAnchorShapeValid(emptyList(), "주민센터")).isFalse()
    }

    @Test
    fun `줄 번호는 중복 없이 오름차순이어야 한다`() {
        assertThat(isSourceAnchorShapeValid(listOf(1, 0), "주민센터")).isFalse()
        assertThat(isSourceAnchorShapeValid(listOf(0, 0), "주민센터")).isFalse()
    }

    @Test
    fun `인용 길이 상한은 코드 포인트로 잰다`() {
        assertThat(isSourceAnchorShapeValid(listOf(0), "😀".repeat(MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS))).isTrue()
        assertThat(isSourceAnchorShapeValid(listOf(0), "가".repeat(MAX_SOURCE_ANCHOR_QUOTE_CODE_POINTS + 1))).isFalse()
    }
}
