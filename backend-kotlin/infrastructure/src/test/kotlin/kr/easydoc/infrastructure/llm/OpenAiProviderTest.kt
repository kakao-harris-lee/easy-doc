package kr.easydoc.infrastructure.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.privacy.maskText
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class OpenAiProviderTest {
    private lateinit var server: StubLlmServer
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun start() {
        server = StubLlmServer()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    @DisplayName("Responses API에 저장 비활성화와 마스킹된 입력을 전송한다")
    fun `요청 계약을 지킨다`() {
        server.replyWith(body = successBody())

        provider().complete(conversionPrompt(), LlmOptions(maxTokens = 4_096))

        val request = server.singleRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/responses")
        assertThat(request.header("authorization")).isEqualTo("Bearer $TEST_API_KEY")
        assertThat(request.header("content-type")).contains("application/json")
        assertThat(request.wireDump()).doesNotContain(RRN_IN_SOURCE)

        val body = json.readTree(request.body)
        assertThat(body.path("model").stringValue("")).isEqualTo(DEFAULT_OPENAI_MODEL)
        assertThat(body.path("instructions").stringValue("")).contains("[변환 규칙]")
        assertThat(body.path("input").stringValue("")).contains("[[주민등록번호1]]")
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(4_096)
        assertThat(body.path("store").asBoolean()).isFalse()
    }

    @Test
    @DisplayName("Responses API 응답을 공통 완료 타입으로 변환한다")
    fun `응답을 매핑한다`() {
        server.replyWith(body = successBody())

        val completion = provider().complete(conversionPrompt())

        assertThat(completion.text).isEqualTo("쉬운 글 결과입니다.")
        assertThat(completion.provider).isEqualTo(OPENAI_PROVIDER_NAME)
        assertThat(completion.model).isEqualTo("gpt-4.1-2026-01-01")
        assertThat(completion.inputTokens).isEqualTo(123)
        assertThat(completion.outputTokens).isEqualTo(45)
        assertThat(completion.finishReason).isEqualTo(LlmFinishReason.END_TURN)
    }

    @Test
    @DisplayName("max_output_tokens로 미완료된 응답은 잘림으로 매핑한다")
    fun `출력 상한을 매핑한다`() {
        server.replyWith(
            body =
                successBody(
                    status = "incomplete",
                    incompleteReason = "max_output_tokens",
                ),
        )

        assertThat(provider().complete(conversionPrompt()).finishReason)
            .isEqualTo(LlmFinishReason.MAX_TOKENS)
    }

    @Test
    @DisplayName("API 키가 없으면 외부 요청 전에 실패한다")
    fun `키 미설정을 거절한다`() {
        val provider = OpenAiProvider(settings().copy(apiKey = Secret.EMPTY))

        assertThatThrownBy { provider.complete(conversionPrompt()) }
            .isInstanceOf(ConfigurationException::class.java)
        assertThat(server.received).isEmpty()
    }

    @Test
    @DisplayName("벤더 오류 본문과 API 키를 예외에 노출하지 않는다")
    fun `오류를 안전하게 매핑한다`() {
        server.replyWith(status = 429, body = """{"error":{"message":"$TEST_API_KEY $RRN_IN_SOURCE"}}""")

        assertThatThrownBy { provider().complete(conversionPrompt()) }
            .isInstanceOf(LlmProviderException::class.java)
            .hasMessageContaining("openai 호출 실패")
            .hasMessageNotContaining(TEST_API_KEY)
            .hasMessageNotContaining(RRN_IN_SOURCE)
    }

    private fun provider(): OpenAiProvider = OpenAiProvider(settings())

    private fun settings(): OpenAiSettings =
        OpenAiSettings(
            apiKey = Secret(TEST_API_KEY),
            baseUrl = server.baseUrl,
        )

    private fun conversionPrompt(): LlmPrompt =
        LlmPrompt.forConversion(
            maskText("신청자 $RRN_IN_SOURCE 님께 안내드립니다.").maskedText,
            DocumentIdGenerator { "0123456789ab" },
        )

    private fun successBody(
        status: String = "completed",
        incompleteReason: String? = null,
    ): String {
        val incomplete = incompleteReason?.let { """, "incomplete_details":{"reason":"$it"}""" }.orEmpty()
        return """
            {
              "id": "resp_stub",
              "status": "$status",
              "model": "gpt-4.1-2026-01-01",
              "output": [{
                "type": "message",
                "content": [{"type": "output_text", "text": "쉬운 글 결과입니다."}]
              }],
              "usage": {"input_tokens": 123, "output_tokens": 45}
              $incomplete
            }
            """.trimIndent()
    }

    private companion object {
        const val TEST_API_KEY = "sk-openai-test-DO-NOT-LEAK-0123456789"
        const val RRN_IN_SOURCE = "900101-1234567"
    }
}
