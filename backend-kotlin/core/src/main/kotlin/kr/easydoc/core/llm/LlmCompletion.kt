package kr.easydoc.core.llm

/**
 * 모델이 생성을 멈춘 이유. **벤더 어휘가 아니라 우리 어휘로 정규화한 값이다.**
 *
 * ## 이것은 포팅이 아니라 신규다
 *
 * Python `LLMResponse` 에는 없다. 계획 §4.6 이 공통 응답 타입에 finish reason 을 요구해
 * 여기서 처음 생긴다. Python 은 `truncated` 불리언 하나만 내보내서 "잘렸다"와 "그 밖의
 * 비정상 종료"를 구분하지 못했다 — 특히 안전 분류기 거절(`refusal`)은 HTTP 200 에
 * 빈 본문으로 오기 때문에 Python 경로에서는 "빈 응답"과 뭉뚱그려진다.
 *
 * 원시 벤더 문자열(`"end_turn"` 등)을 그대로 들고 다니지 않는 이유: 그 문자열이 도메인에
 * 남으면 벤더 어휘가 `application`·`worker` 의 분기 조건으로 굳어, 벤더 SDK 타입을
 * 격리한 것과 같은 자리에서 벤더 어휘가 새어 나간다.
 */
enum class LlmFinishReason {
    /** 모델이 스스로 답을 끝냈다. 정상 경로. */
    END_TURN,

    /** 출력 상한에 걸려 잘렸다. 재시도·분할 판단은 변환 서비스 몫이다. */
    MAX_TOKENS,

    /** 지정한 정지 문자열을 만났다. 현재 우리는 정지 문자열을 보내지 않는다. */
    STOP_SEQUENCE,

    /**
     * 안전 분류기가 요청을 거절했다.
     *
     * HTTP 200 에 빈 본문으로 오므로 상태 코드만 보면 성공으로 읽힌다. 별도 값으로 두어
     * 호출부가 "빈 응답"(버그 후보)과 "거절"(입력 특성)을 가를 수 있게 한다.
     */
    REFUSAL,

    /** 위 어디에도 해당하지 않거나 벤더가 값을 주지 않았다. */
    OTHER,
}

/**
 * LLM 완성 응답.
 *
 * 원본: `app/llm/provider.py::LLMResponse` (text·model·input_tokens·output_tokens·truncated)
 * + `LLMProvider.name` ClassVar([provider]) + 계획 §4.6 이 요구한 [finishReason].
 *
 * ## [model] 은 **관측값**이다
 *
 * 설정값이 아니라 **응답이 실제로 보고한 모델 이름**을 담는다. 이 구분은 이 저장소가
 * 실패로 배운 것이다 — `tests/golden/baseline.py` 는 한때 설정값
 * (`settings.llm_model`)을 기준선에 실었다가 그 값이 `None` 인 채로 기록돼
 * "무엇으로 잰 수치인지 모른다"에 빠졌고, 그래서 `observed_models` 를 변환 응답의
 * `LLMResponse.model` 에서 **관측하도록** 고쳤다. 별칭 해석(`claude-sonnet-5` →
 * 날짜 붙은 실제 스냅샷)과 폴백이 있는 한 설정값은 주장이지 증거가 아니다.
 *
 * 어댑터는 응답 본문의 모델 이름을 그대로 싣는다. 그 값이 없으면 실패로 다룬다 —
 * 설정값으로 대신 채우면 위 실패가 그대로 되살아난다.
 *
 * ## data class 인데 toString 을 재정의하는 이유
 *
 * [text] 는 변환된 **문서 본문**이다. 기본 `toString()` 은 그것을 그대로 찍는다.
 */
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
    /**
     * 출력 상한에 걸려 잘렸는가. 원본 `LLMResponse.truncated` 의 자리다.
     *
     * 저장 필드가 아니라 [finishReason] 에서 파생시킨다 — 둘을 각각 들고 다니면 언젠가
     * `finishReason=MAX_TOKENS, truncated=false` 같은 모순 상태가 만들어지고, 그때
     * 어느 쪽이 진실인지 가릴 근거가 없다.
     */
    val truncated: Boolean
        get() = finishReason == LlmFinishReason.MAX_TOKENS

    /** 본문은 길이만 남긴다. 나머지는 개인정보가 아니므로 그대로 둔다. */
    override fun toString(): String =
        "LlmCompletion(provider=$provider, model=$model, text=${text.length}자, " +
            "inputTokens=$inputTokens, outputTokens=$outputTokens, finishReason=$finishReason)"
}
