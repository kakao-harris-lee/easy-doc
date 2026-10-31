package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.illustration.generation.IllustrationGenerationProvider
import kr.easydoc.core.illustration.generation.GeneratedIllustration
import kr.easydoc.core.illustration.generation.ILLUSTRATION_GENERATION_SIZE
import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Clock
import java.util.Base64
import javax.imageio.ImageIO

/** 네트워크 없이 같은 PNG 검증을 통과하는 결정적 그림 생성 adapter. */
class FakeIllustrationGenerationProvider(private val clock: Clock = Clock.systemUTC()) :
    IllustrationGenerationProvider {
    override val name: String = "fake"

    val calls: MutableList<IllustrationGenerationRequest> = mutableListOf()

    override fun generate(request: IllustrationGenerationRequest): GeneratedIllustration {
        calls += request
        val pngBytes = GeneratedPngValidator.validate(Base64.getEncoder().encodeToString(createPng()))
        return GeneratedIllustration(pngBytes, record(request))
    }

    @Suppress("MagicNumber")
    private fun createPng(): ByteArray {
        val image =
            BufferedImage(ILLUSTRATION_GENERATION_SIZE, ILLUSTRATION_GENERATION_SIZE, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(242, 247, 251)
            graphics.fillRect(0, 0, ILLUSTRATION_GENERATION_SIZE, ILLUSTRATION_GENERATION_SIZE)
            graphics.color = Color(76, 125, 165)
            graphics.fillRoundRect(176, 224, 672, 576, 48, 48)
            graphics.color = Color(238, 180, 76)
            graphics.fillOval(368, 336, 288, 288)
            graphics.color = Color(255, 255, 255)
            graphics.fillRect(256, 688, 512, 32)
        } finally {
            graphics.dispose()
        }
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "JDK PNG writer를 찾을 수 없습니다" }
        return output.toByteArray()
    }

    private fun record(request: IllustrationGenerationRequest): LlmCallRecord =
        LlmCallRecord(
            purpose = LlmCallPurpose.ILLUSTRATION_GENERATION,
            provider = name,
            model = "fake-illustration-generation-r7",
            inputTokens = 0,
            outputTokens = 0,
            latencyMs = 0,
            estimatedCostUsd = BigDecimal.ZERO,
            pricingInputUsdPerMtok = null,
            pricingOutputUsdPerMtok = null,
            charCount = request.finalPrompt.codePointCount(0, request.finalPrompt.length),
            calledAt = clock.instant(),
            outcome = LlmCallOutcome.COMPLETED,
        )
}
