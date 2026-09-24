package kr.easydoc.worker

import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobWorkerPolicy
import kr.easydoc.application.illustration.suggestion.ProcessIllustrationSuggestionJob
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.illustration.suggestion.IllustrationSuggestionProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** fake 프로필 worker 가 그림 제안 poller 를 조립하고 상한을 설정에서 읽는지 확인한다. */
@SpringBootTest(
    properties = [
        "easydoc.illustration-suggestions.enabled=true",
        "easydoc.illustration-suggestions.worker-enabled=true",
        // Kotlin 기본값과 다른 값을 일부러 준다 — 상한이 설정에서 온다는 것을 기본값이 가리지 못하게.
        "easydoc.illustration-suggestions.max-lease-attempts=$OVERRIDDEN_SUGGESTION_LEASE_ATTEMPTS",
        // fake 모드의 무과금 — 0은 설정된 값이며 미설정(503)과 다르다.
        "easydoc.illustration-suggestions.credits-per-100-chars=0",
        "spring.task.scheduling.enabled=false",
    ],
)
@ActiveProfiles("worker", "local", "illustration-suggestion-fake")
class IllustrationSuggestionWorkerStartupTest {
    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var environment: ConfigurableEnvironment

    @Test
    @DisplayName("fake worker 에서 그림 제안 poller 가 조립된다")
    fun `fake worker에서 poller가 조립된다`() {
        assertThat(context.getBean(ProcessIllustrationSuggestionJob::class.java)).isNotNull()
        assertThat(context.getBean(IllustrationSuggestionJobPoller::class.java)).isNotNull()
    }

    @Test
    @DisplayName("리스 재획득 상한은 Kotlin 기본값이 아니라 설정에서 온다")
    fun `리스 상한이 설정에서 온다`() {
        assertThat(context.getBean(IllustrationSuggestionJobWorkerPolicy::class.java).maxLeaseAttempts)
            .isEqualTo(OVERRIDDEN_SUGGESTION_LEASE_ATTEMPTS)
            .isNotEqualTo(IllustrationSuggestionProperties.DEFAULT_MAX_LEASE_ATTEMPTS)
    }

    @Test
    @DisplayName("worker 설정 파일이 상한 키를 환경변수와 기본값으로 선언한다")
    fun `설정 파일이 상한 키를 선언한다`() {
        // 테스트의 property override 가 가려 주지 않도록 application.yml 자체를 직접 읽는다.
        val declared =
            environment.propertySources
                .filterIsInstance<EnumerablePropertySource<*>>()
                .filter { it.name.contains(WORKER_CONFIG_RESOURCE) }
                .mapNotNull { it.getProperty(MAX_PROVIDER_ATTEMPTS_KEY)?.toString() }

        assertThat(declared).hasSize(1)
        assertThat(declared.single())
            .isEqualTo(
                "\${$MAX_PROVIDER_ATTEMPTS_ENV:${IllustrationSuggestionProperties.DEFAULT_MAX_PROVIDER_ATTEMPTS}}",
            )
    }

    companion object {
        private const val WORKER_CONFIG_RESOURCE = "application.yml"
        private const val MAX_PROVIDER_ATTEMPTS_KEY =
            "easydoc.illustration-suggestions.max-provider-attempts-per-conversion"
        private const val MAX_PROVIDER_ATTEMPTS_ENV = "EASYDOC_ILLUSTRATION_SUGGESTIONS_MAX_PROVIDER_ATTEMPTS"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("illustration_suggestion_worker_startup")
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

/** 어노테이션 인자라 컴파일 상수여야 한다. 운영 기본값(5)과 반드시 달라야 의미가 있다. */
private const val OVERRIDDEN_SUGGESTION_LEASE_ATTEMPTS: Int = 2
