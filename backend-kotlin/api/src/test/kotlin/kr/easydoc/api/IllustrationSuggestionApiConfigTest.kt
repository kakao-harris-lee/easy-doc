package kr.easydoc.api

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.illustration.suggestion.IllustrationSuggestionProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * **문서당 provider 시도 상한은 api 프로세스가 집행한다** —
 * `JdbcIllustrationSuggestionJobRepository.insert` 가 접수 트랜잭션에서 세고,
 * 그 저장소는 `IllustrationSuggestionConfiguration`(`!migrate` 프로필)이 api 컨텍스트에도 만든다.
 *
 * 그래서 이 키가 worker `application.yml` 에만 있으면 운영자가 환경변수를 올려도 api 는 계속 Kotlin
 * 기본값(3)을 쓴다 — 설정이 절반만 듣는 상태이고, 어느 로그도 그 사실을 말하지 않는다. 이 시험은
 * ⑴ api yml 이 같은 키·환경변수·기본값을 선언하는지, ⑵ api 컨텍스트가 그 값을 실제로 바인딩하는지를
 * 각각 잰다.
 */
@SpringBootTest(
    properties = [
        // Kotlin 기본값과 다른 값을 일부러 준다 — 배선이 끊기면 기본값이 나와 실패한다.
        "easydoc.illustration-suggestions.max-provider-attempts-per-conversion=" +
            "$OVERRIDDEN_SUGGESTION_PROVIDER_ATTEMPTS",
    ],
)
class IllustrationSuggestionApiConfigTest {
    @Autowired
    private lateinit var properties: IllustrationSuggestionProperties

    @Autowired
    private lateinit var environment: ConfigurableEnvironment

    @Test
    @DisplayName("api 컨텍스트가 문서당 시도 상한을 설정에서 바인딩한다 — 기본값이 아니다")
    fun `api 가 상한을 설정에서 읽는다`() {
        assertThat(properties.maxProviderAttemptsPerConversion)
            .isEqualTo(OVERRIDDEN_SUGGESTION_PROVIDER_ATTEMPTS)
            .isNotEqualTo(IllustrationSuggestionProperties.DEFAULT_MAX_PROVIDER_ATTEMPTS)
    }

    @Test
    @DisplayName("api 설정 파일이 접수 쪽이 읽는 키를 전부 환경변수와 기본값으로 선언한다")
    fun `api 설정 파일이 키를 선언한다`() {
        // 테스트의 property override 가 가려 주지 않도록 application.yml 자체를 직접 읽는다.
        assertThat(declaredInApiYml(MAX_PROVIDER_ATTEMPTS_KEY))
            .describedAs("api yml 이 문서당 시도 상한을 선언하지 않는다 — 운영자가 올려도 api 는 기본값을 쓴다")
            .containsExactly(
                "\${$MAX_PROVIDER_ATTEMPTS_ENV:${IllustrationSuggestionProperties.DEFAULT_MAX_PROVIDER_ATTEMPTS}}",
            )
        assertThat(declaredInApiYml(ENABLED_KEY)).containsExactly("\${$ENABLED_ENV:false}")
        assertThat(declaredInApiYml(CREDITS_KEY)).containsExactly("\${$CREDITS_ENV:}")
    }

    private fun declaredInApiYml(key: String): List<String> =
        environment.propertySources
            .filterIsInstance<EnumerablePropertySource<*>>()
            .filter { it.name.contains(API_CONFIG_RESOURCE) }
            .mapNotNull { it.getProperty(key)?.toString() }

    companion object {
        private const val API_CONFIG_RESOURCE = "application.yml"

        private const val MAX_PROVIDER_ATTEMPTS_KEY =
            "easydoc.illustration-suggestions.max-provider-attempts-per-conversion"
        private const val MAX_PROVIDER_ATTEMPTS_ENV = "EASYDOC_ILLUSTRATION_SUGGESTIONS_MAX_PROVIDER_ATTEMPTS"
        private const val ENABLED_KEY = "easydoc.illustration-suggestions.enabled"
        private const val ENABLED_ENV = "EASYDOC_ILLUSTRATION_SUGGESTIONS_ENABLED"
        private const val CREDITS_KEY = "easydoc.illustration-suggestions.credits-per-100-chars"
        private const val CREDITS_ENV = "EASYDOC_ILLUSTRATION_SUGGESTIONS_CREDITS_PER_100_CHARS"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("illustration_suggestion_api_config")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}

/** 어노테이션 인자라 컴파일 상수여야 한다. 운영 기본값(3)과 반드시 달라야 의미가 있다. */
private const val OVERRIDDEN_SUGGESTION_PROVIDER_ATTEMPTS: Int = 2
