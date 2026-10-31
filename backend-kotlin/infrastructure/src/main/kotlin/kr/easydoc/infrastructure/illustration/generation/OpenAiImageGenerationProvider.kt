package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.illustration.generation.IllustrationGenerationProvider
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.IllustrationProviderIoException
import kr.easydoc.core.exceptions.IllustrationProviderRejectedException
import kr.easydoc.core.exceptions.IllustrationProviderTimeoutException
import kr.easydoc.core.exceptions.IllustrationResultException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.illustration.generation.GeneratedIllustration
import kr.easydoc.core.illustration.generation.ILLUSTRATION_GENERATION_SIZE
import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest
import kr.easydoc.core.llm.LlmCallOutcome
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.security.Secret
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

const val DEFAULT_OPENAI_IMAGE_MODEL: String = "gpt-image-1"

internal const val OPENAI_IMAGES_BASE_URL: String = "https://api.openai.com"
internal const val OPENAI_IMAGES_PATH: String = "/v1/images/generations"
private const val OPENAI_IMAGE_JSON_OVERHEAD_BYTES: Int = 4 * 1024
internal const val MAX_OPENAI_IMAGE_RESPONSE_BYTES: Int =
    MAX_ILLUSTRATION_BASE64_LENGTH + OPENAI_IMAGE_JSON_OVERHEAD_BYTES
internal val OPENAI_IMAGE_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
internal val OPENAI_IMAGE_READ_TIMEOUT: Duration = Duration.ofSeconds(120)

internal data class OpenAiImageSettings(
    val apiKey: Secret,
    val model: String = DEFAULT_OPENAI_IMAGE_MODEL,
    val baseUrl: String = OPENAI_IMAGES_BASE_URL,
    val connectTimeout: Duration = OPENAI_IMAGE_CONNECT_TIMEOUT,
    val readTimeout: Duration = OPENAI_IMAGE_READ_TIMEOUT,
)

/** OpenAI Images API adapter. 운영 base URL은 고정하고 stub 주소는 모듈 테스트 생성자로만 주입한다. */
class OpenAiImageGenerationProvider internal constructor(
    private val settings: OpenAiImageSettings,
    private val clock: Clock,
) : IllustrationGenerationProvider {
    constructor(
        apiKey: Secret,
        model: String = DEFAULT_OPENAI_IMAGE_MODEL,
        connectTimeout: Duration = OPENAI_IMAGE_CONNECT_TIMEOUT,
        readTimeout: Duration = OPENAI_IMAGE_READ_TIMEOUT,
        clock: Clock = Clock.systemUTC(),
    ) : this(OpenAiImageSettings(apiKey, model, connectTimeout = connectTimeout, readTimeout = readTimeout), clock)

    override val name: String = "openai"

    private val json = JsonMapper.builder().build()

    private val client: RestClient =
        RestClient
            .builder()
            .baseUrl(settings.baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(settings.connectTimeout).build(),
                ).apply { setReadTimeout(settings.readTimeout) },
            ).build()

    override fun toString(): String = "OpenAiImageGenerationProvider(model=${settings.model})"

    override fun generate(request: IllustrationGenerationRequest): GeneratedIllustration {
        if (settings.apiKey.isBlank()) throw ConfigurationException("openai API 키가 설정되지 않았습니다")

        val startedAt = System.nanoTime()
        val pngBytes = GeneratedPngValidator.validate(extractBase64(post(requestBody(request))))
        val latencyMs = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAt).coerceAtLeast(0))
        return GeneratedIllustration(pngBytes, record(request, latencyMs))
    }

    private fun requestBody(request: IllustrationGenerationRequest): String =
        json.writeValueAsString(
            json.createObjectNode().apply {
                put("model", settings.model)
                put("prompt", request.finalPrompt)
                put("n", 1)
                put("size", "$ILLUSTRATION_GENERATION_SIZE$SIZE_SEPARATOR$ILLUSTRATION_GENERATION_SIZE")
                put("output_format", "png")
            },
        )

    private fun post(payload: String): ByteArray =
        try {
            client
                .post()
                .uri(OPENAI_IMAGES_PATH)
                .header("Authorization", "Bearer ${settings.apiKey.reveal()}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.toByteArray(StandardCharsets.UTF_8))
                .exchange { _, response ->
                    if (response.statusCode.isError) reject(response.statusCode.value())
                    response.body.use { readLimited(it) }
                }
        } catch (exc: RestClientException) {
            throw transportFailure(exc)
        }

    private fun extractBase64(raw: ByteArray): String {
        val root = readTree(raw)
        val data = root.path("data")
        if (!data.isArray || data.size() != 1) throw invalidResult("data는 정확히 1건이어야 합니다")
        val encoded = data[0].path("b64_json")
        if (encoded.stringValue("").isEmpty()) {
            throw invalidResult("b64_json이 없습니다")
        }
        return encoded.stringValue("")
    }

    private fun readTree(raw: ByteArray): JsonNode =
        try {
            json.readTree(raw) ?: throw invalidResult("응답 본문이 없습니다")
        } catch (_: JacksonException) {
            throw invalidResult("JSON을 읽을 수 없습니다")
        }

    private fun transportFailure(exc: RestClientException): LlmProviderException {
        val causes = generateSequence<Throwable>(exc) { it.cause }
        return when {
            causes.any { it is HttpTimeoutException || it is SocketTimeoutException || it is TimeoutException } -> {
                IllustrationProviderTimeoutException("openai 이미지 호출 시간 초과")
            }

            causes.any { it is IOException } -> {
                IllustrationProviderIoException("openai 이미지 I/O 실패 (${exc::class.java.simpleName})")
            }

            else -> {
                LlmProviderException("openai 이미지 호출 실패 (${exc::class.java.simpleName})")
            }
        }
    }

    private fun record(
        request: IllustrationGenerationRequest,
        latencyMs: Long,
    ): LlmCallRecord =
        LlmCallRecord(
            purpose = LlmCallPurpose.ILLUSTRATION_GENERATION,
            provider = name,
            model = settings.model,
            inputTokens = 0,
            outputTokens = 0,
            latencyMs = latencyMs,
            estimatedCostUsd = null,
            pricingInputUsdPerMtok = null,
            pricingOutputUsdPerMtok = null,
            charCount = request.finalPrompt.codePointCount(0, request.finalPrompt.length),
            calledAt = clock.instant(),
            outcome = LlmCallOutcome.COMPLETED,
        )

    private fun reject(statusCode: Int): Nothing =
        throw IllustrationProviderRejectedException("openai 이미지 호출 거절 (HTTP $statusCode)")

    private fun invalidResult(reason: String) = IllustrationResultException("openai 이미지 응답 형식 오류 ($reason)")

    private companion object {
        const val SIZE_SEPARATOR = "x"
    }
}

private const val RESPONSE_READ_BUFFER_BYTES: Int = 8 * 1024

private fun readLimited(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(RESPONSE_READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        total += read
        if (total > MAX_OPENAI_IMAGE_RESPONSE_BYTES) {
            throw IllustrationResultException("openai 이미지 응답 형식 오류 (응답 크기 상한 초과)")
        }
        output.write(buffer, 0, read)
    }
}
