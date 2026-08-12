package kr.easydoc.worker

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import javax.sql.DataSource

/**
 * worker 진입점이 기동되는지 확인한다.
 *
 * 두 가지를 함께 본다.
 * - HTTP 서버를 띄우지 않는다 (`web-application-type: none`)
 * - **Flyway 를 돌리지 않는다** — 마이그레이션 주체는 `migrate` profile 하나뿐이다.
 *   worker 도 migrate 하면 api·worker·migrate 셋이 동시에 기동될 때 Flyway 잠금 경합과
 *   부분 적용 상태가 생긴다.
 */
@SpringBootTest
class WorkerStartupTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    @DisplayName("worker 가 기동하고 DataSource 를 갖는다")
    fun `worker 가 기동한다`() {
        assertThat(context.environment.activeProfiles).isNotNull()
        assertThat(dataSource).isNotNull()
    }

    @Test
    @DisplayName("worker 는 웹 서버를 띄우지 않는다")
    fun `웹 컨텍스트가 아니다`() {
        assertThat(context).isNotInstanceOf(
            org.springframework.web.context.WebApplicationContext::class.java,
        )
    }

    @Test
    @DisplayName("worker 는 스키마를 적용하지 않는다")
    fun `Flyway 를 돌리지 않는다`() {
        // 이 DB에는 아무도 마이그레이션을 돌리지 않았다. worker 기동만으로 테이블이
        // 생겼다면 worker 가 migrate 를 한 것이다.
        assertThat(database.queryInt(TABLE_COUNT_SQL)).isZero()
    }

    companion object {
        private const val TABLE_COUNT_SQL =
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'public' AND c.relkind = 'r'"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("worker_startup")
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
