package kr.easydoc.infrastructure.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.security.Secret
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** 어댑터 테스트가 공유하는 재료. */
internal object AnthropicTestSupport {
    /** 테스트용 API 키. 요청·예외·로그 어디에도 이 문자열이 새면 안 된다. */
    const val TEST_API_KEY: String = "sk-ant-test-DO-NOT-LEAK-0123456789"

    val json: JsonMapper = JsonMapper.builder().build()

    private val fixedIds = DocumentIdGenerator { "0123456789ab" }

    fun settings(
        baseUrl: String,
        apiKey: String = TEST_API_KEY,
        effort: AnthropicEffort? = null,
    ): AnthropicSettings =
        AnthropicSettings(
            apiKey = Secret(apiKey),
            effort = effort,
            baseUrl = baseUrl,
        )

    /** 변환 프롬프트 픽스처. */
    fun conversionPrompt(body: String = "신청자 님께 안내드립니다."): LlmPrompt = LlmPrompt.forConversion(body, fixedIds)

    /** Messages API 성공 응답 한 건. */
    fun successBody(
        text: String = "쉬운 글 결과입니다.",
        model: String = "claude-sonnet-5-20260101",
        stopReason: String = "end_turn",
        inputTokens: Int = 11,
        outputTokens: Int = 22,
    ): String =
        """
        {
          "id": "msg_stub",
          "type": "message",
          "role": "assistant",
          "model": "$model",
          "content": [{"type": "text", "text": "$text"}],
          "stop_reason": "$stopReason",
          "usage": {"input_tokens": $inputTokens, "output_tokens": $outputTokens}
        }
        """.trimIndent()

    /** `content` 가 빈 응답. 사고 블록만 오거나 분류기가 거절한 경우의 모양이다. */
    fun emptyBody(stopReason: String): String =
        """
        {
          "id": "msg_stub",
          "type": "message",
          "model": "claude-sonnet-5-20260101",
          "content": [],
          "stop_reason": "$stopReason",
          "usage": {"input_tokens": 5, "output_tokens": 0}
        }
        """.trimIndent()

    fun parse(body: String): JsonNode = json.readTree(body)
}
