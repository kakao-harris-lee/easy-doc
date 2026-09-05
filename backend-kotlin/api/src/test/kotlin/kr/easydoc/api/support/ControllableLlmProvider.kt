package kr.easydoc.api.support

import kr.easydoc.core.llm.FakeLlmCall
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider

/**
 * `@WebMvcTest` 슬라이스가 쓰는 통제 가능한 LLM 대역.
 *
 * [FakeLlmProvider] 는 생성 시점에 고정된 큐를 소진하면 던지는데, `@WebMvcTest` 는 한
 * 테스트 클래스 안의 모든 메서드가 **같은 컨텍스트·같은 빈 인스턴스**를 공유한다
 * (`@DirtiesContext` 없이). 그래서 시나리오마다 다른 응답이 필요한 재변환 계약 테스트는
 * 이 래퍼로 매 테스트가 큐를 [willReturn] 으로 갈아 끼운다.
 */
class ControllableLlmProvider : LlmProvider {
    override val name: String = "fake"

    private var delegate: FakeLlmProvider = FakeLlmProvider(emptyList())

    /** 이 다음 완성 요청들이 순서대로 낼 응답을 정한다. */
    fun willReturn(vararg turns: FakeLlmTurn) {
        delegate = FakeLlmProvider(turns.toList())
    }

    /** 지금까지 받은 호출 — 마지막 [willReturn] 이후 누적이다. */
    val calls: List<FakeLlmCall> get() = delegate.calls

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion = delegate.complete(prompt, options)
}
