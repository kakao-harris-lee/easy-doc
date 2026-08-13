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

/**
 * 출력 토큰 상한 기본값.
 *
 * 원본: `app/llm/provider.py::DEFAULT_MAX_TOKENS`.
 *
 * **상한이지 지출이 아니다** — 과금은 실제 생성한 토큰만큼이다. 넉넉히 잡는 이유는
 * 현행 Claude 모델이 `thinking` 미지정 시 적응형 사고를 켜고, 그 사고 토큰이
 * `max_tokens` 를 본문과 **나눠 쓰기** 때문이다. 1차 벤치마크
 * (`docs/benchmarks/2026-08-08-1642-llm-benchmark.md`)에서 anthropic 장문 20건(36%)이
 * 빈 응답·절단으로 실패한 원인이 옛 상한 4,096 이었다(완주 문서도 출력 토큰 중앙값
 * 3,616 으로 상한에 붙어 있었다).
 *
 * 16,000 은 비스트리밍 요청의 권장 상한이다 — 더 키우면 HTTP 타임아웃 구간에 들어간다.
 */
const val DEFAULT_MAX_TOKENS: Int = 16_000

/**
 * 한 번의 완성 요청에 붙는 **벤더 공통** 옵션.
 *
 * ## 여기에 없는 것과 그 이유
 *
 * - **temperature**: Python 시그니처(`DEFAULT_TEMPERATURE = 0.2`)에는 있지만 옮기지
 *   않았다. 현행 Claude 모델은 샘플링 파라미터(temperature/top_p/top_k)를 지원하지
 *   않아 기본값 외 값을 보내면 400 이고, `app/llm/anthropic_provider.py` 도 그래서
 *   **받아 놓고 보내지 않는다**. 어느 구현체도 지키지 않는 인자는 타입이 하는 거짓말이라
 *   빼고, 출력 성향은 프롬프트로 제어한다. 되살릴 근거가 생기면 그때 넣는다.
 * - **effort**: Anthropic 에만 있는 파라미터다(`app/llm/factory.py::applied_effort` —
 *   OpenAI 구현체는 인자 자체가 없어 설정값이 모델에 **닿지 않는다**). 공통 옵션에 두면
 *   "설정했는데 아무 일도 일어나지 않는" 자리가 생기므로 어댑터 설정으로 내렸다.
 */
data class LlmOptions(val maxTokens: Int = DEFAULT_MAX_TOKENS) {
    init {
        // 입력값이 아니라 호출 코드의 실수다 — 도메인 예외로 감싸지 않는다.
        require(maxTokens > 0) { "maxTokens 는 1 이상이어야 한다" }
    }
}

/**
 * LLM 벤더 공통 인터페이스. 구현체는 `infrastructure` 에만 둔다.
 *
 * 원본: `app/llm/provider.py::LLMProvider`.
 */
interface LlmProvider {
    /**
     * 벤더 이름 (`"anthropic"`, `"openai"`, `"fake"`).
     *
     * 원본: `LLMProvider.name` ClassVar. Python 은 이 값을 응답에 싣지 않아 호출부가
     * 따로 들고 다녔는데, Kotlin 은 [LlmCompletion.provider] 로 응답에 함께 실어
     * "무엇이 이 결과를 냈는가"가 값 하나에서 끊기지 않게 한다.
     */
    val name: String

    /**
     * 단일 완성 요청.
     *
     * **원문 `String` 을 받는 오버로드를 만들지 않는다** — [LlmPrompt] 가 존재하는 이유
     * 전부가 그것이다(마스킹 선행 불변식, `Masking.kt` KDoc).
     *
     * 자원 정리(`aclose`) 대응물을 두지 않은 이유: Python 은 SDK 의 HTTP 커넥션 풀을
     * 명시적으로 닫아야 했지만, JVM 어댑터가 쓰는 클라이언트는 인스턴스 수명과 함께
     * 정리된다. 필요해지면 그때 구현체 쪽에서 `AutoCloseable` 로 여는 편이 인터페이스를
     * 넓히는 것보다 좁다.
     *
     * @throws kr.easydoc.core.exceptions.LlmProviderException 호출 실패(HTTP 오류·타임아웃·응답 형식 오류).
     * @throws kr.easydoc.core.exceptions.LlmEmptyResultException 응답 본문이 비었다.
     * @throws kr.easydoc.core.exceptions.ConfigurationException 벤더 설정이 비어 기능을 제공할 수 없다.
     */
    fun complete(
        prompt: LlmPrompt,
        options: LlmOptions = LlmOptions(),
    ): LlmCompletion
}
