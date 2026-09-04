package kr.easydoc.core.llm

// 서비스가 벤더를 알지 않도록 하는 LLM port다. 구현체와 HTTP 클라이언트는
// infrastructure에만 있고, 설정에서 선택한 adapter를 관측 decorator가 감싼다.

/**
 * 출력 토큰 상한 기본값 — **provider 안전 fallback**이다. 값을 그대로 「제품 운영값」으로
 * 읽지 마라. 이 상수는 어느 provider에서도 무설정 조립이 성공해야 한다는 불변식을 지키는
 * 하한 안전값이고, 그 경계는 OpenAI 기본 모델(gpt-4.1) 출력 한도(32,768)다.
 *
 * **제품 운영값은 `application.yml` `easydoc.llm.max-output-tokens`(64,000, 게이트 ⓪ 3차
 * 측정 조건)**이고, 그 값은 [kr.easydoc.infrastructure.llm.LlmProperties.validatedMaxOutputTokens]
 * 를 거쳐 조립된다 — 이 상수는 그 설정이 없을 때만 쓰인다.
 */
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
