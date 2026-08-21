package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// LLM provider 조립 — **이 모듈이 소유한다.**
//
// ## 왜 여기인가 (모듈 경계에서 유일하게 가능한 자리)
//
// 조립하려면 두 가지를 동시에 봐야 한다 — 설정값과 구현 클래스(`AnthropicProvider`).
// 그 둘을 함께 볼 수 있는 모듈이 `infrastructure` 하나뿐이다.
//
//   - `api`·`worker` 는 `runtimeOnly(project(":infrastructure"))` 다. 컴파일 시점에
//     `AnthropicProvider` 타입을 볼 수 없으므로 `@Bean` 팩터리를 둘 수 없다. 이 제약은
//     사고가 아니라 설계다 — api 소스가 LLM·JDBC 타입을 보지 못하게 막는 것이 목적이다.
//   - `application` 은 `infrastructure` 를 아예 의존하지 않는다(포트만 안다).
//   - `core` 는 Spring 을 모른다.
//
// 그래서 설정 바인딩(`LlmProperties`)도 여기로 내렸다. 이전에는 `api` 의
// `EasyDocProperties.LlmProperties` 에 있었는데, 그 자리에 두면 값을 읽는 쪽과 쓰는 쪽이
// 서로를 볼 수 없어 **아무도 조립할 수 없는 설정**이 된다.
//
// 배선은 `@ComponentScan`·`@ConfigurationPropertiesScan` 이 `kr.easydoc` 전체를 훑는 것으로
// 성립한다(`ApiApplication`·`WorkerApplication`). 즉 runtimeOnly 의존만으로 충분하고,
// api·worker 소스는 여전히 이 파일의 어떤 타입도 보지 못한다.
//
// ## `baseUrl` 은 설정으로 열지 않는다 (privacy-gate 판정 X-11 해제 조건 1)
//
// [LlmProperties] 에 `baseUrl` 필드가 **없다.** 이것은 누락이 아니라 조건이다 —
// 문서 본문이 나가는 **대상**을 바꾸는 값이라, 설정 한 줄로 평문 `http` 나 제3자 호스트로
// 돌릴 수 있으면 안 된다. `AnthropicSettings.baseUrl` 은 생성자 기본 인자(컴파일 상수)로
// 남고 테스트 스텁 서버만 그것을 쓴다. **열어야 할 사유가 생기면 그것은 `privacy-gate`
// 재판정 사안이지 구현 재량이 아니다.** 이 조건은 `LlmProviderConfigurationTest` 의
// 「설정 표면에 호출 대상(엔드포인트)을 여는 필드가 없다」가 상시 확인한다 —
// 주석으로만 두면 다음 회차에 필드가 하나 늘어난다.

/** LLM 벤더 설정. 바인딩 접두사는 `easydoc.llm`. */
@ConfigurationProperties(prefix = "easydoc.llm")
data class LlmProperties(
    val provider: String = ANTHROPIC_PROVIDER_NAME,
    val model: String? = null,
    val effort: String? = null,
    val anthropicApiKey: Secret = Secret.EMPTY,
)

/** 설정에서 [LlmProvider] 구현체를 고른다. */
@Configuration(proxyBeanMethods = false)
class LlmProviderConfiguration {
    @Bean
    fun llmProvider(properties: LlmProperties): LlmProvider =
        when (properties.provider.lowercase()) {
            ANTHROPIC_PROVIDER_NAME -> {
                AnthropicProvider(anthropicSettings(properties))
            }

            else -> {
                throw ConfigurationException(
                    "지원하지 않는 LLM provider 설정입니다 (가능: $ANTHROPIC_PROVIDER_NAME)",
                )
            }
        }

    /**
     * `baseUrl`·타임아웃을 **넘기지 않는다.** 기본값(컴파일 상수)을 그대로 쓴다 —
     * 위 「`baseUrl` 은 설정으로 열지 않는다」 참고.
     */
    private fun anthropicSettings(properties: LlmProperties) =
        AnthropicSettings(
            apiKey = properties.anthropicApiKey,
            model = properties.model ?: DEFAULT_ANTHROPIC_MODEL,
            effort = AnthropicEffort.from(properties.effort),
        )
}
