package kr.easydoc.worker

import kr.easydoc.application.actionguide.ActionGuideJobWorkerPolicy
import kr.easydoc.application.actionguide.ProcessActionGuideJob
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(
    properties = [
        "easydoc.action-guide.enabled=true",
        "easydoc.action-guide.worker-enabled=true",
        "spring.task.scheduling.enabled=false",
    ],
)
@ActiveProfiles("worker", "local", "action-guide-fake")
class ActionGuideWorkerStartupTest {
    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `fake worker에서 행동 안내 poller가 조립된다`() {
        assertThat(context.getBean(ProcessActionGuideJob::class.java)).isNotNull()
        assertThat(context.getBean(ActionGuideJobPoller::class.java)).isNotNull()
    }

    @Test
    fun `리스 재획득 상한이 worker 설정 기본값으로 묶인다`() {
        assertThat(context.getBean(ActionGuideJobWorkerPolicy::class.java).maxLeaseAttempts)
            .isEqualTo(DEFAULT_MAX_LEASE_ATTEMPTS)
    }

    companion object {
        /** worker `application.yml` 의 `easydoc.action-guide.max-lease-attempts` 기본값. */
        private const val DEFAULT_MAX_LEASE_ATTEMPTS = 5

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
