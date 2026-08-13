package kr.easydoc.core.llm

/**
 * [FakeLlmProvider] 가 돌려줄 한 차례의 결과.
 *
 * 원본: `app/llm/fake.py` 의 `responses: list[str | Exception]`. Python 은 문자열과 예외를
 * 같은 리스트에 섞었는데, Kotlin 에서는 sealed 로 갈라 어느 쪽인지 타입이 말하게 한다.
 */
sealed interface FakeLlmTurn {
    /** 준비된 응답을 돌려준다. */
    data class Reply(
        val text: String,
        val model: String = "fake-model",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val finishReason: LlmFinishReason = LlmFinishReason.END_TURN,
    ) : FakeLlmTurn

    /** 그 차례에 예외를 던진다 — 실패 경로 테스트용. */
    data class Fail(val error: RuntimeException) : FakeLlmTurn
}

/** [FakeLlmProvider] 가 기록한 호출 인자. 원본: `app/llm/fake.py::FakeCall`. */
data class FakeLlmCall(
    val prompt: LlmPrompt,
    val options: LlmOptions,
)

/**
 * 준비된 응답을 순서대로 돌려주는 테스트 대역.
 *
 * 원본: `app/llm/fake.py::FakeProvider`.
 *
 * **실제 API 를 부르지 않는다.** 단위 테스트에서 LLM 호출을 대체하는 것이 존재 이유이고,
 * `core` 가 Spring·DB·HTTP 없이 테스트 가능해야 한다는 조건(계획 §3.2)을 만족시킨다.
 *
 * `main` 이 아니라 `testFixtures` 에 두는 이유: 제품 런타임에 가짜 구현이 실려 있으면
 * 배선 실수 하나로 운영에서 고정 문자열이 변환 결과로 나간다. 여기 두면 제품 클래스패스에
 * 아예 올라오지 않고, `application`·`worker` 테스트는 `testFixtures(project(":core"))`
 * 로 가져다 쓸 수 있다.
 */
class FakeLlmProvider(turns: List<FakeLlmTurn>) : LlmProvider {
    override val name: String = "fake"

    private val remaining = ArrayDeque(turns)

    /** 지금까지 받은 호출. 순서·인자를 그대로 보존한다. */
    val calls: MutableList<FakeLlmCall> = mutableListOf()

    /** 아직 쓰이지 않은 응답 수. 테스트가 "준비한 만큼 정확히 불렸는가"를 단언할 수 있다. */
    val unusedTurns: Int
        get() = remaining.size

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        calls += FakeLlmCall(prompt = prompt, options = options)
        // 소진 시 조용히 넘기지 않는다 — 준비한 응답 수와 실제 호출 수의 불일치는
        // 대개 호출 상한 계약(문서당 최대 2회)이 깨졌다는 신호다.
        // 원본은 IndexError 를 그대로 노출했다. 여기서는 사유를 담은 예외로 바꾼다.
        val turn =
            remaining.removeFirstOrNull()
                ?: error("FakeLlmProvider 에 준비된 응답이 없다 (호출 ${calls.size}번째)")

        return when (turn) {
            is FakeLlmTurn.Fail -> {
                throw turn.error
            }

            is FakeLlmTurn.Reply -> {
                LlmCompletion(
                    text = turn.text,
                    provider = name,
                    model = turn.model,
                    inputTokens = turn.inputTokens,
                    outputTokens = turn.outputTokens,
                    finishReason = turn.finishReason,
                )
            }
        }
    }

    companion object {
        /** 본문만 정해 주는 흔한 경우의 지름길. */
        fun replying(vararg texts: String): FakeLlmProvider =
            FakeLlmProvider(texts.map { FakeLlmTurn.Reply(text = it) })
    }
}
