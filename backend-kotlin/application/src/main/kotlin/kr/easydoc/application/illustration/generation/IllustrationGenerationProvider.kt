package kr.easydoc.application.illustration.generation

import kr.easydoc.core.illustration.generation.GeneratedIllustration
import kr.easydoc.core.illustration.generation.IllustrationGenerationRequest

/**
 * 요청 한 건당 PNG 한 장을 만드는 전용 포트. 구현은 provider 호출을 한 번만 시작하며 자동 재시도하지 않는다.
 * DB, 크레딧, 작업 상태는 이 경계 밖의 책임이다.
 */
interface IllustrationGenerationProvider {
    val name: String

    fun generate(request: IllustrationGenerationRequest): GeneratedIllustration
}
