package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.security.Secret
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration

const val OPENAI_PROVIDER_NAME: String = "openai"
const val DEFAULT_OPENAI_MODEL: String = "gpt-4.1"

internal const val OPENAI_BASE_URL: String = "https://api.openai.com"
internal const val OPENAI_RESPONSES_PATH: String = "/v1/responses"

val OPENAI_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
val OPENAI_READ_TIMEOUT: Duration = Duration.ofSeconds(120)

data class OpenAiSettings(
    val apiKey: Secret = Secret.EMPTY,
    val model: String = DEFAULT_OPENAI_MODEL,
    val baseUrl: String = OPENAI_BASE_URL,
    val connectTimeout: Duration = OPENAI_CONNECT_TIMEOUT,
    val readTimeout: Duration = OPENAI_READ_TIMEOUT,
)

/** OpenAI Responses API 어댑터. 요청 저장은 항상 비활성화한다. */
class OpenAiProvider(private val settings: OpenAiSettings) : LlmProvider {
    override val name: String = OPENAI_PROVIDER_NAME

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

    override fun toString(): String = "OpenAiProvider(model=${settings.model})"

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        if (settings.apiKey.isBlank()) {
            throw ConfigurationException("openai API 키가 설정되지 않았습니다")
        }

        val payload = json.writeValueAsString(requestBody(prompt, options))
        return parse(post(payload))
    }

    private fun requestBody(
        prompt: LlmPrompt,
        options: LlmOptions,
    ) = json.createObjectNode().apply {
        put("model", settings.model)
        put("instructions", prompt.system)
        put("input", prompt.user)
        put("max_output_tokens", options.maxTokens)
        put("store", false)
    }

    private fun post(payload: String): String =
        execute(payload)?.toString(StandardCharsets.UTF_8) ?: throw failure("응답 본문 없음")

    private fun execute(payload: String): ByteArray? =
        try {
            client
                .post()
                .uri(OPENAI_RESPONSES_PATH)
                .header("Authorization", "Bearer ${settings.apiKey.reveal()}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.toByteArray(StandardCharsets.UTF_8))
                .retrieve()
                .onStatus({ status -> status.isError }) { _, response ->
                    throw failure("HTTP ${response.statusCode.value()}")
                }.body(ByteArray::class.java)
        } catch (exc: RestClientException) {
            throw failure(exc::class.java.simpleName)
        }

    private fun parse(raw: String): LlmCompletion {
        val node = readTree(raw)
        val model = requiredText(node, "model")
        val inputTokens = requiredTokenCount(node, "input_tokens")
        val outputTokens = requiredTokenCount(node, "output_tokens")
        val text = extractText(node)

        return LlmCompletion(
            text = text,
            provider = name,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            finishReason = finishReason(node),
        )
    }

    private fun readTree(raw: String): JsonNode =
        try {
            json.readTree(raw)
        } catch (exc: JacksonException) {
            throw failure("응답 형식 오류: ${exc::class.java.simpleName}")
        }
}

private fun extractText(node: JsonNode): String =
    node
        .path("output")
        .filter { item -> item.path("type").stringValue("") == "message" }
        .flatMap { item -> item.path("content").toList() }
        .filter { content -> content.path("type").stringValue("") == "output_text" }
        .joinToString("") { content -> content.path("text").stringValue("") }

private fun finishReason(node: JsonNode): LlmFinishReason {
    if (hasRefusal(node)) return LlmFinishReason.REFUSAL

    return when (requiredText(node, "status")) {
        "completed" -> {
            LlmFinishReason.END_TURN
        }

        "incomplete" -> {
            when (node.path("incomplete_details").path("reason").stringValue("")) {
                "max_output_tokens" -> LlmFinishReason.MAX_TOKENS
                "content_filter" -> LlmFinishReason.REFUSAL
                else -> LlmFinishReason.OTHER
            }
        }

        "failed", "cancelled" -> {
            throw failure("응답이 완료되지 않았습니다")
        }

        else -> {
            LlmFinishReason.OTHER
        }
    }
}

private fun hasRefusal(node: JsonNode): Boolean =
    node
        .path("output")
        .flatMap { item -> item.path("content").toList() }
        .any { content -> content.path("type").stringValue("") == "refusal" }

private fun requiredText(
    node: JsonNode,
    field: String,
): String = node.path(field).stringValue("").ifEmpty { throw failure("응답에 $field 값이 없습니다") }

private fun requiredTokenCount(
    node: JsonNode,
    field: String,
): Int {
    val value = node.path("usage").path(field)
    if (!value.isIntegralNumber) throw failure("응답 usage.$field 가 정수가 아닙니다")
    val count = value.asInt()
    if (count < 0) throw failure("응답 usage.$field 가 음수입니다")
    return count
}

private fun failure(reason: String) = LlmProviderException("openai 호출 실패 ($reason)")
