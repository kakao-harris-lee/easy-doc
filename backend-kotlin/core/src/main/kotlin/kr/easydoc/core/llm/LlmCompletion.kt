package kr.easydoc.core.llm

/** 모델이 생성을 멈춘 이유. **벤더 어휘가 아니라 우리 어휘로 정규화한 값이다.** */
enum class LlmFinishReason {
    /** 모델이 스스로 답을 끝냈다. 정상 경로. */
    END_TURN,

    /** 출력 상한에 걸려 잘렸다. 재시도·분할 판단은 변환 서비스 몫이다. */
    MAX_TOKENS,

    /** 지정한 정지 문자열을 만났다. 현재 우리는 정지 문자열을 보내지 않는다. */
    STOP_SEQUENCE,

    /** 안전 분류기가 요청을 거절했다. */
    REFUSAL,

    /** 위 어디에도 해당하지 않거나 벤더가 값을 주지 않았다. */
    OTHER,
}

/** LLM 완성 응답. */
data class LlmCompletion(
    /** 모델이 생성한 본문. */
    val text: String,
    /** 벤더 이름. [LlmProvider.name] 과 같은 값을 어댑터가 채운다. */
    val provider: String,
    /** **응답이 보고한** 모델 이름. 설정값이 아니다(위 KDoc). */
    val model: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val finishReason: LlmFinishReason = LlmFinishReason.OTHER,
) {
    /** 출력 상한에 걸려 잘렸는가. 원본 `LLMResponse.truncated` 의 자리다. */
    val truncated: Boolean
        get() = finishReason == LlmFinishReason.MAX_TOKENS

    /** 본문은 길이만 남긴다. 나머지는 개인정보가 아니므로 그대로 둔다. */
    override fun toString(): String =
        "LlmCompletion(provider=$provider, model=$model, text=${text.length}자, " +
            "inputTokens=$inputTokens, outputTokens=$outputTokens, finishReason=$finishReason)"
}
