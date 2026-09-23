package kr.easydoc.worker

import kr.easydoc.application.actionguide.ActionGuideJobWorkerPolicy
import kr.easydoc.application.actionguide.ProcessActionGuideJob
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.actionguide.ActionGuideProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(
    properties = [
        "easydoc.action-guide.enabled=true",
        "easydoc.action-guide.worker-enabled=true",
        // Kotlin 기본값과 다른 값을 일부러 준다 — 상한이 설정에서 온다는 것을 기본값이 가리지 못하게.
        "easydoc.action-guide.max-lease-attempts=$OVERRIDDEN_MAX_LEASE_ATTEMPTS",
        "spring.task.scheduling.enabled=false",
    ],
)
@ActiveProfiles("worker", "local", "action-guide-fake")
class ActionGuideWorkerStartupTest {
    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var environment: ConfigurableEnvironment

    @Test
    fun `fake worker에서 행동 안내 poller가 조립된다`() {
        assertThat(context.getBean(ProcessActionGuideJob::class.java)).isNotNull()
        assertThat(context.getBean(ActionGuideJobPoller::class.java)).isNotNull()
    }

    @Test
    fun `리스 재획득 상한은 Kotlin 기본값이 아니라 설정에서 온다`() {
        // 기본값 5가 아니라 위에서 준 값이어야 한다 — 배선이 끊기면 기본값이 나와 실패한다.
        assertThat(context.getBean(ActionGuideJobWorkerPolicy::class.java).maxLeaseAttempts)
            .isEqualTo(OVERRIDDEN_MAX_LEASE_ATTEMPTS)
            .isNotEqualTo(ActionGuideProperties.DEFAULT_MAX_LEASE_ATTEMPTS)
    }

    @Test
    fun `worker 설정 파일이 상한 키를 환경변수와 기본값으로 선언한다`() {
        // 테스트의 property override가 가려 주지 않도록 application.yml 자체를 직접 읽는다.
        // yml 줄이 사라지면 이 목록이 비어 실패한다 — 운영은 Kotlin 기본값에 기대지 않는다.
        val declared =
            environment.propertySources
                .filterIsInstance<EnumerablePropertySource<*>>()
                .filter { it.name.contains(WORKER_CONFIG_RESOURCE) }
                .mapNotNull { it.getProperty(MAX_LEASE_ATTEMPTS_KEY)?.toString() }

        assertThat(declared).hasSize(1)
        assertThat(declared.single())
            .isEqualTo("\${$MAX_LEASE_ATTEMPTS_ENV:${ActionGuideProperties.DEFAULT_MAX_LEASE_ATTEMPTS}}")
    }

    companion object {
        private const val WORKER_CONFIG_RESOURCE = "application.yml"
        private const val MAX_LEASE_ATTEMPTS_KEY = "easydoc.action-guide.max-lease-attempts"
        private const val MAX_LEASE_ATTEMPTS_ENV = "EASYDOC_ACTION_GUIDE_MAX_LEASE_ATTEMPTS"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("action_guide_worker_startup")
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
private const val OVERRIDDEN_MAX_LEASE_ATTEMPTS: Int = 3
