package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO

class FakeIllustrationGenerationProviderTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `네트워크 없이 결정적 1024x1024 PNG를 만들고 공통 검증을 통과한다`() {
        val provider = FakeIllustrationGenerationProvider(clock = clock)
        val request = IllustrationGenerationRequest("서버가 확정한 최종 프롬프트")

        val first = provider.generate(request)
        val second = provider.generate(request)

        assertThat(first.pngBytes).isEqualTo(second.pngBytes)
        assertThat(first.pngBytes)
            .isEqualTo(FakeIllustrationGenerationProvider(clock = clock).generate(request).pngBytes)
        val image = ImageIO.read(ByteArrayInputStream(first.pngBytes))
        assertThat(image.width).isEqualTo(1024)
        assertThat(image.height).isEqualTo(1024)
        assertThat(provider.calls).containsExactly(request, request)
    }

    @Test
    fun `fake 호출 기록은 비용 0과 그림 생성 목적을 갖고 작업 상태를 알지 못한다`() {
        val provider = FakeIllustrationGenerationProvider(clock = clock)

        val result = provider.generate(IllustrationGenerationRequest("최종 프롬프트"))

        assertThat(result.record.purpose).isEqualTo(LlmCallPurpose.ILLUSTRATION_GENERATION)
        assertThat(result.record.provider).isEqualTo("fake")
        assertThat(result.record.model).isEqualTo("fake-illustration-generation-r7")
        assertThat(result.record.estimatedCostUsd).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.record.outcome).isEqualTo(LlmCallOutcome.COMPLETED)
        assertThat(result.record.charCount).isEqualTo(7)
        assertThat(result.record.calledAt).isEqualTo(clock.instant())
        assertThat(result.record.latencyMs).isZero()
    }
}
