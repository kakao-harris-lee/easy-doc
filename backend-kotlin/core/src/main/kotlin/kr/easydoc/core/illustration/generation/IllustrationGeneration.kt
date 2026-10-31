package kr.easydoc.core.illustration.generation

import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord

const val ILLUSTRATION_GENERATION_SIZE: Int = 1024

/** 서버가 확정한 프롬프트로 요청하는 그림 한 장. 클라이언트 임의 프롬프트 입력 경계가 아니다. */
data class IllustrationGenerationRequest(val finalPrompt: String) {
    init {
        require(finalPrompt.isNotBlank()) { "최종 그림 생성 프롬프트는 비워 둘 수 없습니다" }
    }

    override fun toString(): String = "IllustrationGenerationRequest(finalPrompt=${finalPrompt.length}자)"
}

/** 공통 PNG 검증을 통과한 한 장과 그 provider 호출 기록. */
data class GeneratedIllustration(
    val pngBytes: ByteArray,
    val record: LlmCallRecord,
) {
    init {
        require(record.purpose == LlmCallPurpose.ILLUSTRATION_GENERATION) {
            "그림 생성 결과에는 그림 생성 원장 목적이 필요합니다"
        }
    }

    override fun toString(): String = "GeneratedIllustration(pngBytes=${pngBytes.size}, record=$record)"
}
