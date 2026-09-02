package kr.easydoc.core.llm

// 서비스가 벤더를 알지 않도록 하는 LLM port다. 구현체와 HTTP 클라이언트는
// infrastructure에만 있고, 설정에서 선택한 adapter를 관측 decorator가 감싼다.

/** 출력 토큰 상한 기본값. */
const val DEFAULT_MAX_TOKENS: Int = 16_000

/** 한 번의 완성 요청에 붙는 **벤더 공통** 옵션. */
data class LlmOptions(val maxTokens: Int = DEFAULT_MAX_TOKENS) {
    init {
        // 이 생성자를 직접 부르는 코드에게는 여전히 호출 코드의 실수다 — 도메인 예외로
        // 감싸지 않는다. 하지만 운영자 설정값(`easydoc.llm.max-output-tokens`)이 이
        // 생성자까지 흘러드는 경로도 있다 — 그 composition root
        // (`kr.easydoc.infrastructure.llm.LlmProperties.validatedMaxOutputTokens`,
        // `kr.easydoc.infrastructure.queue.ConversionWorkerConfiguration`) 는 값을
        // 여기로 넘기기 **전에** 운영자 오류(도메인 예외 `ConfigurationException`)로
        // 거절해야 한다. 아래 `require` 는 그 계약이 지켜지지 않았을 때의 마지막
        // 방어선일 뿐, 운영자 입력의 1차 검증 자리가 아니다.
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
