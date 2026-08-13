package kr.easydoc.infrastructure.llm

import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * provider 조립의 소유자가 `infrastructure` 라는 결정의 회귀 테스트.
 *
 * 배선 자체(스캔이 이 `@Configuration` 에 닿는가)는 `ApiStartupWithDatabaseTest` 가
 * 실제 컨텍스트를 띄워 확인한다. 여기서는 **선택 규칙과 설정 표면**을 본다.
 */
class LlmProviderConfigurationTest {
    private val configuration = LlmProviderConfiguration()

    @Test
    @DisplayName("기본 설정이면 anthropic 어댑터를 만든다")
    fun `기본값은 anthropic 이다`() {
        val provider = configuration.llmProvider(LlmProperties())

        assertThat(provider).isInstanceOf(AnthropicProvider::class.java)
        assertThat(provider.name).isEqualTo(ANTHROPIC_PROVIDER_NAME)
        // 모델을 지정하지 않으면 어댑터 기본값이다. 모델 선택은 품질 결정이라 코드가 아니라
        // 설정으로 바꾼다 — 여기서 값을 바꾸면 골든셋 통과율 기준선이 흔들린다.
        assertThat(provider.toString()).contains(DEFAULT_ANTHROPIC_MODEL)
    }

    @Test
    @DisplayName("설정한 모델·effort 가 어댑터에 전달된다")
    fun `설정을 어댑터로 넘긴다`() {
        val provider =
            configuration.llmProvider(
                LlmProperties(model = "claude-sonnet-5-20260101", effort = "medium"),
            )

        assertThat(provider.toString()).contains("claude-sonnet-5-20260101")
        assertThat(provider.toString()).contains(AnthropicEffort.MEDIUM.toString())
    }

    @Test
    @DisplayName("키가 없어도 조립은 된다 — 기동을 막지 않는다")
    fun `키 미설정은 기동을 막지 않는다`() {
        // 키가 없어도 앱은 뜨고 `/health` 로 진단할 수 있어야 한다. 거절은 호출 시점이다
        // (`AnthropicProvider.complete` 의 키 검사).
        assertThat(configuration.llmProvider(LlmProperties(anthropicApiKey = Secret.EMPTY)))
            .isInstanceOf(AnthropicProvider::class.java)
    }

    @Test
    @DisplayName("모르는 벤더 이름은 조립 시점에 거절한다")
    fun `지원하지 않는 provider 는 던진다`() {
        // 열거값 오타는 배포 설정 오류다. 지금 던지지 않으면 모든 변환이 런타임에 실패하고
        // 운영자는 첫 변환이 실패한 뒤에야 안다. 메시지에 설정값을 되비추지 않는다.
        assertThatThrownBy { configuration.llmProvider(LlmProperties(provider = "openai")) }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("지원하지 않는")
            .hasMessageNotContaining("openai")
    }

    @Test
    @DisplayName("잘못된 effort 값도 조립 시점에 거절한다")
    fun `지원하지 않는 effort 는 던진다`() {
        assertThatThrownBy { configuration.llmProvider(LlmProperties(effort = "turbo")) }
            .isInstanceOf(ConfigurationException::class.java)
    }

    @Test
    @DisplayName("설정 표면에 호출 대상(엔드포인트)을 여는 필드가 없다")
    fun `baseUrl 을 설정으로 열지 않는다`() {
        // privacy-gate 판정 X-11 해제 조건 1. `baseUrl` 은 **문서 본문이 나가는 대상**을
        // 바꾸는 값이라, 설정 한 줄로 평문 http 나 제3자 호스트로 돌릴 수 있으면 안 된다.
        // 주석으로만 두면 다음 회차에 필드가 하나 늘어난다 — 이름 규칙으로 상시 감시한다.
        val endpointish =
            // Kotlin 리플렉션(kotlin-reflect)을 쓰지 않는다 — 이 모듈에 명시적 의존이 없어
            // 전이 의존성이 빠지는 날 가드가 조용히 사라진다. 자바 리플렉션이면 언제나 있다.
            LlmProperties::class.java.declaredFields
                .map { it.name }
                .filter { name ->
                    listOf("url", "uri", "endpoint", "host", "baseurl").any { name.lowercase().contains(it) }
                }

        assertThat(endpointish)
            .withFailMessage {
                "LlmProperties 에 호출 대상을 여는 것으로 보이는 필드가 생겼다: $endpointish\n" +
                    "  이것을 여는 것은 구현 재량이 아니라 privacy-gate 재판정 사안이다 " +
                    "(판정 07_privacy-gate_masking-verdicts.md §4.3 해제 조건 1). " +
                    "테스트 스텁 서버는 AnthropicSettings 생성자 인자를 쓰면 되고, 설정 바인딩은 필요 없다."
            }.isEmpty()
    }
}
