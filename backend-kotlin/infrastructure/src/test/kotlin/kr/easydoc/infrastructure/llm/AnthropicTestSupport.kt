package kr.easydoc.infrastructure.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.privacy.maskText
import kr.easydoc.core.security.Secret
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 어댑터 테스트가 공유하는 재료.
 *
 * **실제 Anthropic API 를 부르는 테스트는 하나도 없다.** 전부 [StubAnthropicServer] 로
 * 향한다(비용·재현성). 실제 호출이 필요해지면 `@Tag("llm")` 을 붙여 기본 실행에서
 * 제외해야 한다(루트 build.gradle.kts 의 `excludeTags("llm")`).
 */
internal object AnthropicTestSupport {
    /** 테스트용 API 키. 요청·예외·로그 어디에도 이 문자열이 새면 안 된다. */
    const val TEST_API_KEY: String = "sk-ant-test-DO-NOT-LEAK-0123456789"

    /** 마스킹 대상이 들어 있는 본문 — 자리표시자 치환 여부를 와이어에서 확인하기 위해. */
    const val RRN_IN_SOURCE: String = "900101-1234567"

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

    /** 마스킹을 실제로 통과시킨 변환 프롬프트. 주민등록번호 한 건이 자리표시자로 바뀐다. */
    fun conversionPrompt(body: String = "신청자 $RRN_IN_SOURCE 님께 안내드립니다."): LlmPrompt =
        LlmPrompt.forConversion(maskText(body).maskedText, fixedIds)

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
