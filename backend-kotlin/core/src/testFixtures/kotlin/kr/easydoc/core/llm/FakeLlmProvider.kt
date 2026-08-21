package kr.easydoc.core.llm

/** [FakeLlmProvider] 가 돌려줄 한 차례의 결과. */
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

/** 준비된 응답을 순서대로 돌려주는 테스트 대역. */
class FakeLlmProvider(
    turns: List<FakeLlmTurn>,
    /** 어댑터가 완성 요청 **1건**을 만들기 위해 실제로 전송하는 횟수. */
    private val transportAttemptsPerCall: Int = 1,
) : LlmProvider {
    override val name: String = "fake"

    private val remaining = ArrayDeque(turns)

    /** 지금까지 받은 호출. 순서·인자를 그대로 보존한다. */
    val calls: MutableList<FakeLlmCall> = mutableListOf()

    /** 전송 시도 누계. [calls] 와 **따로** 센다 — 두 수를 가르는 것이 CNV-01 의 요구다. */
    var transportAttempts: Int = 0
        private set

    /** 아직 쓰이지 않은 응답 수. 테스트가 "준비한 만큼 정확히 불렸는가"를 단언할 수 있다. */
    val unusedTurns: Int
        get() = remaining.size

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion {
        calls += FakeLlmCall(prompt = prompt, options = options)
        // 실패한 완성 요청도 전송은 일어났다. 성공 경로에서만 세면 재전송 계측이 거짓이 된다.
        transportAttempts += transportAttemptsPerCall
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
