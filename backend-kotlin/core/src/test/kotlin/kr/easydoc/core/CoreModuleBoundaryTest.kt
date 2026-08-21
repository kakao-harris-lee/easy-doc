package kr.easydoc.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** `core` 가 Spring·DB·Jackson 을 모른다는 것을 실행으로 확인한다. */
class CoreModuleBoundaryTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "org.springframework.context.ApplicationContext",
            "org.springframework.boot.SpringApplication",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.flywaydb.core.Flyway",
            "org.postgresql.Driver",

            "com.fasterxml.jackson.databind.ObjectMapper",
            "tools.jackson.databind.ObjectMapper",

            "com.anthropic.client.AnthropicClient",
            "com.openai.client.OpenAIClient",
            "org.springframework.web.client.RestClient",
        ],
    )
    @DisplayName("core 클래스패스에 Spring·DB·Jackson·벤더 SDK 가 없다")
    fun `core 클래스패스에 프레임워크 의존성이 없다`(className: String) {
        val loaded = runCatching { Class.forName(className) }
        assertThat(loaded.isFailure)
            .withFailMessage(
                "core 클래스패스에서 %s 를 찾았다. core 는 Spring·DB·Jackson·벤더 SDK 를 몰라야 한다(계획 §3.2). " +
                    "core/build.gradle.kts 에 들어온 의존성을 되돌리고, 필요한 코드는 " +
                    "application 또는 infrastructure 로 옮겨라.",
                className,
            ).isTrue()
    }
}
