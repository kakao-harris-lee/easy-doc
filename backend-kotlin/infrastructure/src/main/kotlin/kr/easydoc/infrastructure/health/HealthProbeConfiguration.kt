package kr.easydoc.infrastructure.health

import kr.easydoc.application.health.DependencyProbe
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

/** `/health` 가 읽는 의존 서비스 진단 — **DB 와 작업 큐**. */
@Configuration(proxyBeanMethods = false)
class HealthProbeConfiguration {
    /** 계약 `HealthResponse.checks` 의 `database` 키. */
    @Bean
    fun databaseProbe(jdbcClient: JdbcClient): DependencyProbe =
        QueryProbe(DATABASE_DEPENDENCY, DATABASE_QUERY, jdbcClient)

    /** 계약 `HealthResponse.checks` 의 `queue` 키. 같은 DataSource 위의 작업 테이블을 읽는다. */
    @Bean
    fun queueProbe(jdbcClient: JdbcClient): DependencyProbe = QueryProbe(QUEUE_DEPENDENCY, QUEUE_QUERY, jdbcClient)

    private companion object {
        const val DATABASE_DEPENDENCY = "database"
        const val QUEUE_DEPENDENCY = "queue"

        /**
         * 커넥션을 얻어 서버가 답하는지만 본다. 상수 하나를 고르는 이유는 계획이나 통계에
         * 좌우되지 않고, 어떤 테이블에도 의존하지 않기 때문이다.
         */
        const val DATABASE_QUERY = "SELECT 1"

        /**
         * 작업 테이블을 **실제로 읽는다**. `WHERE false` 라 행을 하나도 훑지 않지만, 테이블이
         * 없거나 권한이 없으면 여기서 실패한다 — 그것이 이 진단이 재려는 상태다.
         */
        const val QUEUE_QUERY = "SELECT 1 FROM conversion_jobs WHERE false"
    }
}

/** 질의 한 방으로 판정하는 probe. */
private class QueryProbe(
    override val dependency: String,
    private val query: String,
    private val jdbcClient: JdbcClient,
) : DependencyProbe {
    /** 질의가 예외 없이 끝나면 `true`. */
    override fun isReachable(): Boolean =
        runCatching {
            jdbcClient.sql(query).query { _, _ -> Unit }.list()
            true
        }.getOrDefault(false)
}
