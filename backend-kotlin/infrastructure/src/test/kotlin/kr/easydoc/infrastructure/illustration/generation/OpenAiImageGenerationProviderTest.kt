package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.IllustrationProviderIoException
import kr.easydoc.core.exceptions.IllustrationProviderRejectedException
import kr.easydoc.core.exceptions.IllustrationProviderTimeoutException
import kr.easydoc.core.exceptions.IllustrationResultException
import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.security.Secret
import kr.easydoc.infrastructure.llm.StubLlmServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

class OpenAiImageGenerationProviderTest {
    private lateinit var server: StubLlmServer
    private val json = JsonMapper.builder().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun start() {
        server = StubLlmServer()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `Images API 요청 계약을 지킨다`() {
        server.replyWith(body = successBody())

        val result = provider().generate(request())

        val httpRequest = server.singleRequest()
        assertThat(httpRequest.method).isEqualTo("POST")
        assertThat(httpRequest.path).isEqualTo("/v1/images/generations")
        assertThat(httpRequest.header("authorization")).isEqualTo("Bearer $TEST_API_KEY")
        assertThat(httpRequest.header("content-type")).contains("application/json")
        val body = json.readTree(httpRequest.body)
        assertThat(body.path("model").stringValue("")).isEqualTo(DEFAULT_OPENAI_IMAGE_MODEL)
        assertThat(body.path("prompt").stringValue("")).isEqualTo(SENSITIVE_PROMPT)
        assertThat(body.path("n").asInt()).isEqualTo(1)
        assertThat(body.path("size").stringValue("")).isEqualTo("1024x1024")
        assertThat(body.path("output_format").stringValue("")).isEqualTo("png")
        assertThat(body.size()).isEqualTo(5)
        assertThat(result.record.purpose).isEqualTo(LlmCallPurpose.ILLUSTRATION_GENERATION)
        assertThat(result.record.provider).isEqualTo("openai")
        assertThat(result.record.model).isEqualTo(DEFAULT_OPENAI_IMAGE_MODEL)
        assertThat(result.record.estimatedCostUsd).isNull()
        assertThat(result.record.charCount).isEqualTo(SENSITIVE_PROMPT.codePointCount(0, SENSITIVE_PROMPT.length))
        assertThat(result.record.calledAt).isEqualTo(clock.instant())
    }

    @Test
    fun `이미지 모델은 구성할 수 있다`() {
        server.replyWith(body = successBody())

        provider(settings().copy(model = "gpt-image-custom")).generate(request())

        assertThat(json.readTree(server.singleRequest().body).path("model").stringValue(""))
            .isEqualTo("gpt-image-custom")
    }

    @Test
    fun `API 키가 없으면 요청을 시작하지 않는다`() {
        val provider = provider(settings().copy(apiKey = Secret.EMPTY))

        assertThatThrownBy { provider.generate(request()) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageNotContaining(TEST_API_KEY)
        assertThat(server.received).isEmpty()
    }

    @Test
    fun `잘못된 base64는 결과 형식 오류로 거절한다`() {
        assertInvalidResult("not-base64***")
    }

    @Test
    fun `URL과 빈 응답과 다중 응답을 거절한다`() {
        assertInvalidResultBodies(
            """{"data":[{"url":"https://example.invalid/image.png"}]}""",
            """{"data":[]}""",
            """{}""",
            """{"data":[{"b64_json":"${pngBase64()}"},{"b64_json":"${pngBase64()}"}]}""",
            """{"data":[{"b64_json":""}]}""",
            "not-json",
        )
    }

    @Test
    fun `8MiB를 넘는 평문 바이트를 거절한다`() {
        val encoded = Base64.getEncoder().encodeToString(ByteArray(MAX_ILLUSTRATION_PNG_BYTES + 1))

        assertInvalidResult(encoded)
    }

    @Test
    fun `HTTP 성공 응답 스트림은 Content-Length 유무와 무관하게 상한을 강제한다`() {
        val oversized = ByteArray(MAX_OPENAI_IMAGE_RESPONSE_BYTES + 1) { ' '.code.toByte() }

        listOf(false, true).forEach { chunked ->
            server.replyWith(body = oversized, chunked = chunked)

            assertThatThrownBy { provider().generate(request()) }
                .isInstanceOf(IllustrationResultException::class.java)
                .hasMessageContaining("응답 크기")
                .hasMessageNotContaining(SENSITIVE_PROMPT)
        }
        assertThat(server.received).hasSize(2)
    }

    @Test
    fun `base64 디코딩 전에 인코딩 길이로 평문 상한 초과를 거절한다`() {
        val encoded = "!".repeat(MAX_ILLUSTRATION_BASE64_LENGTH + 1)
        server.replyWith(body = """{"data":[{"b64_json":"$encoded"}]}""")

        assertThatThrownBy { provider().generate(request()) }
            .isInstanceOf(IllustrationResultException::class.java)
            .hasMessageContaining("base64 길이")
            .hasMessageNotContaining(encoded)
    }

    @Test
    fun `8MiB 이하 고압축 초대형 PNG를 픽셀 디코딩 전에 거절한다`() {
        val encoded = Base64.getEncoder().encodeToString(hugeSolidPng(HUGE_PNG_DIMENSION))
        server.replyWith(body = """{"data":[{"b64_json":"$encoded"}]}""")

        assertThat(Base64.getDecoder().decode(encoded).size).isLessThan(MAX_ILLUSTRATION_PNG_BYTES)
        assertThatThrownBy { provider().generate(request()) }
            .isInstanceOf(IllustrationResultException::class.java)
            .hasMessageContaining("1024x1024")
    }

    @Test
    fun `PNG가 아닌 MIME 바이트를 거절한다`() {
        val jpeg = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg)

        assertInvalidResult(Base64.getEncoder().encodeToString(jpeg.toByteArray()))
    }

    @Test
    fun `PNG signature만 있고 디코딩할 수 없는 응답을 거절한다`() {
        val damaged = PNG_SIGNATURE + byteArrayOf(1, 2, 3, 4)

        assertInvalidResult(Base64.getEncoder().encodeToString(damaged))
    }

    @Test
    fun `1024x1024가 아닌 PNG를 거절한다`() {
        assertInvalidResult(pngBase64(width = 1023, height = 1024))
        assertInvalidResult(pngBase64(width = 1024, height = 1025))
    }

    @Test
    fun `HTTP 거절은 구분하고 키 프롬프트 base64 vendor 본문을 예외에 넣지 않는다`() {
        val vendorBody = """{"error":{"message":"$TEST_API_KEY $SENSITIVE_PROMPT $SENSITIVE_BASE64"}}"""
        server.replyWith(status = 429, body = vendorBody)

        assertThatThrownBy { provider().generate(request()) }
            .isInstanceOf(IllustrationProviderRejectedException::class.java)
            .hasMessageContaining("HTTP 429")
            .hasMessageNotContaining(TEST_API_KEY)
            .hasMessageNotContaining(SENSITIVE_PROMPT)
            .hasMessageNotContaining(SENSITIVE_BASE64)
            .hasMessageNotContaining("vendor")
        assertThat(server.received).hasSize(1)
    }

    @Test
    fun `timeout은 구분하고 자동 재시도하지 않는다`() {
        server.replyWith(body = successBody(), delay = Duration.ofMillis(250))
        val provider = provider(settings().copy(readTimeout = Duration.ofMillis(50)))

        assertThatThrownBy { provider.generate(request()) }
            .isInstanceOf(IllustrationProviderTimeoutException::class.java)
            .hasMessageNotContaining(SENSITIVE_PROMPT)
        assertThat(server.received).hasSize(1)
    }

    @Test
    fun `연결 입력 출력 실패를 timeout과 구분한다`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val provider =
            OpenAiImageGenerationProvider(
                settings().copy(baseUrl = "http://127.0.0.1:$port", connectTimeout = Duration.ofMillis(100)),
                clock,
            )

        assertThatThrownBy { provider.generate(request()) }
            .isInstanceOf(IllustrationProviderIoException::class.java)
            .hasMessageNotContaining(TEST_API_KEY)
            .hasMessageNotContaining(SENSITIVE_PROMPT)
    }

    @Test
    fun `provider와 설정의 toString에 API 키를 노출하지 않는다`() {
        val settings = settings()
        val provider = provider(settings)

        assertThat(provider.toString()).doesNotContain(TEST_API_KEY)
        assertThat(settings.toString()).doesNotContain(TEST_API_KEY)
    }

    private fun assertInvalidResult(encoded: String) {
        val previousRequests = server.received.size
        server.replyWith(body = """{"data":[{"b64_json":"$encoded"}]}""")

        assertThatThrownBy { provider().generate(request()) }
            .isInstanceOf(IllustrationResultException::class.java)
            .hasMessageNotContaining(encoded)
            .hasMessageNotContaining(SENSITIVE_PROMPT)
        assertThat(server.received).hasSize(previousRequests + 1)
    }

    private fun assertInvalidResultBodies(vararg bodies: String) {
        bodies.forEach { body ->
            server.replyWith(body = body)

            assertThatThrownBy { provider().generate(request()) }
                .isInstanceOf(IllustrationResultException::class.java)
                .hasMessageNotContaining(SENSITIVE_PROMPT)
                .hasMessageNotContaining(TEST_API_KEY)
        }
        assertThat(server.received).hasSize(bodies.size)
    }

    private fun provider(settings: OpenAiImageSettings = settings()): OpenAiImageGenerationProvider =
        OpenAiImageGenerationProvider(settings, clock)

    private fun settings(): OpenAiImageSettings =
        OpenAiImageSettings(
            apiKey = Secret(TEST_API_KEY),
            baseUrl = server.baseUrl,
        )

    private fun request(): IllustrationGenerationRequest = IllustrationGenerationRequest(SENSITIVE_PROMPT)

    private fun successBody(): String = """{"data":[{"b64_json":"${pngBase64()}"}]}"""

    private fun pngBase64(
        width: Int = 1024,
        height: Int = 1024,
    ): String {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun hugeSolidPng(dimension: Int): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { png ->
            png.write(PNG_SIGNATURE)
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { ihdr ->
                ihdr.writeInt(dimension)
                ihdr.writeInt(dimension)
                ihdr.writeByte(8)
                ihdr.writeByte(2)
                ihdr.writeByte(0)
                ihdr.writeByte(0)
                ihdr.writeByte(0)
            }
            writePngChunk(png, "IHDR", header.toByteArray())

            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { zlib ->
                val row = ByteArray(dimension * 3 + 1)
                repeat(dimension) { zlib.write(row) }
            }
            writePngChunk(png, "IDAT", compressed.toByteArray())
            writePngChunk(png, "IEND", ByteArray(0))
        }
        return output.toByteArray()
    }

    private fun writePngChunk(
        output: DataOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(data.size)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        output.writeInt(crc.value.toInt())
    }

    private companion object {
        const val TEST_API_KEY = "sk-openai-image-test-DO-NOT-LEAK-0123456789"
        const val SENSITIVE_PROMPT = "서버가 만든 최종 프롬프트-DO-NOT-LEAK"
        const val SENSITIVE_BASE64 = "c2Vuc2l0aXZlLWJhc2U2NC1ETy1OT1QtTEVBSw=="
        const val HUGE_PNG_DIMENSION = 12_000
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
