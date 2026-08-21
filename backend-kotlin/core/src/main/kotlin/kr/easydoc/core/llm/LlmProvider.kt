package kr.easydoc.core.llm

// LLM 벤더 공통 인터페이스.
//
// 원본: app/llm/provider.py
//
// ## 이 인터페이스가 존재하는 이유 (CLAUDE.md 아키텍처 규칙 1)
//
// 모든 LLM 호출은 이 인터페이스를 통해서만 한다. 벤더 SDK(anthropic, openai 등)를
// 서비스 코드에서 직접 import 하지 않는다. 새 벤더는 구현체 추가로만 대응한다.
//
// **규칙을 문서로만 두지 않는다.** 벤더 SDK 도, HTTP 클라이언트도 `core` 의 의존성에
// 아예 넣지 않으므로 core 안에서는 그것들을 import 하는 코드가 컴파일되지 않는다.
// `CoreModuleBoundaryTest` 가 클래스패스로 그 사실을 확인한다.
//
// ## 동기(blocking) 시그니처인 이유
//
// Python 은 `async def complete` 이지만 Kotlin 런타임은 Spring MVC 블로킹 스택이다
// (kotlin-spring-conventions §3 — 파이프라인의 무거운 구간이 전부 blocking 이라
// WebFlux 로 얻을 것이 없다). suspend 를 붙이면 호출부마다 코루틴 경계를 만들어야 하고
// 그 경계는 트랜잭션 전파와 스택 트레이스를 흐린다.
//
// ## no-training 계약
//
// 모든 구현체는 **입력 데이터 학습 미사용(no-training)** 조건을 전제로 한다.
// 구현체를 새로 추가할 때 그 전제를 파일 주석에 명시한다 (CLAUDE.md 보안·데이터 규칙).

/** 출력 토큰 상한 기본값. */
const val DEFAULT_MAX_TOKENS: Int = 16_000

/** 한 번의 완성 요청에 붙는 **벤더 공통** 옵션. */
data class LlmOptions(val maxTokens: Int = DEFAULT_MAX_TOKENS) {
    init {
        // 입력값이 아니라 호출 코드의 실수다 — 도메인 예외로 감싸지 않는다.
        require(maxTokens > 0) { "maxTokens 는 1 이상이어야 한다" }
    }
}

/** LLM 벤더 공통 인터페이스. 구현체는 `infrastructure` 에만 둔다. */
interface LlmProvider {
    /** 벤더 이름 (`"anthropic"`, `"openai"`, `"fake"`). */
    val name: String

    /** 단일 완성 요청. */
    fun complete(
        prompt: LlmPrompt,
        options: LlmOptions = LlmOptions(),
    ): LlmCompletion
}
