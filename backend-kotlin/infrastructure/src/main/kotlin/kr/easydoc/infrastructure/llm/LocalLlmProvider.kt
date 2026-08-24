package kr.easydoc.infrastructure.llm

import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider

/** Compose·로컬 E2E 가 실제 유료 호출 없이 변환을 끝낼 때 쓰는 대역. */
const val FAKE_PROVIDER_NAME: String = "fake"

const val FAKE_MODEL_NAME: String = "fake-passthrough"

/**
 * 스타일 규칙을 통과하는 고정 문장을 돌려준다. 품질 평가용이 아니라 **상태 전이** 용이다.
 * 프롬프트 본문은 읽지 않는다 — 읽으면 이 타입이 사용자 콘텐츠를 들고 있는 것처럼 보인다.
 */
class LocalLlmProvider : LlmProvider {
    override val name: String = FAKE_PROVIDER_NAME

    override fun complete(
        prompt: LlmPrompt,
        options: LlmOptions,
    ): LlmCompletion =
        LlmCompletion(
            text = CLEAN_REPLY,
            provider = name,
            model = FAKE_MODEL_NAME,
            inputTokens = 0,
            outputTokens = 0,
            finishReason = LlmFinishReason.END_TURN,
        )

    override fun toString(): String = "LocalLlmProvider(model=$FAKE_MODEL_NAME)"

    private companion object {
        /** ConvertDocumentUseCase 테스트의 깨끗한 결과와 같은 문장 — 보정 패스를 타지 않는다. */
        const val CLEAN_REPLY: String = "오늘 서류를 내세요."
    }
}
