package kr.easydoc.core.llm

// 서비스가 벤더를 알지 않도록 하는 LLM port다. 구현체와 HTTP 클라이언트는
// infrastructure에만 있고, 설정에서 선택한 adapter를 관측 decorator가 감싼다.

/** 출력 토큰 상한 기본값. */
const val DEFAULT_MAX_TOKENS: Int = 16_000

/** 한 번의 완성 요청에 붙는 **벤더 공통** 옵션. */
data class LlmOptions(val maxTokens: Int = DEFAULT_MAX_TOKENS) {
    init {
        // 입력값이 아니라 호출 코드의 실수다 — 도메인 예외로 감싸지 않는다.
        require(maxTokens > 0) { "maxTokens 는 1 이상이어야 한다" }
    }
}

/** LLM 벤더 공통 인터페이스. 구현체는 `infrastructure`에만 둔다. */
interface LlmProvider {
    /** 벤더 이름 (`"anthropic"`, `"openai"`, `"fake"`). */
    val name: String

    /** 단일 완성 요청. */
    fun complete(
        prompt: LlmPrompt,
        options: LlmOptions = LlmOptions(),
    ): LlmCompletion
}
