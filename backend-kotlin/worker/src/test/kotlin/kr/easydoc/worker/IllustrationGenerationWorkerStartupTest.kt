package kr.easydoc.worker

import kr.easydoc.application.illustration.generation.IllustrationGenerationJobWorkerPolicy
import kr.easydoc.application.illustration.generation.ProcessIllustrationGenerationJob
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.illustration.generation.IllustrationGenerationProperties
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
        "easydoc.illustration-generation.enabled=true",
        "easydoc.illustration-generation.worker-enabled=true",
        "easydoc.illustration-generation.max-lease-attempts=2",
        "easydoc.illustration-generation.credits-per-image=0",
        "spring.task.scheduling.enabled=false",
    ],
)
@ActiveProfiles("worker", "local", "illustration-generation-fake")
class IllustrationGenerationWorkerStartupTest {
    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `local fake 프로필에서만 무과금 생성 worker가 조립된다`() {
        assertThat(context.getBean(ProcessIllustrationGenerationJob::class.java)).isNotNull()
        assertThat(context.getBean(IllustrationGenerationJobPoller::class.java)).isNotNull()
        assertThat(context.getBean(IllustrationGenerationJobWorkerPolicy::class.java).maxLeaseAttempts)
            .isEqualTo(2)
            .isNotEqualTo(IllustrationGenerationProperties.DEFAULT_MAX_LEASE_ATTEMPTS)
    }

    companion object {
        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("illustration_generation_worker_startup")
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
