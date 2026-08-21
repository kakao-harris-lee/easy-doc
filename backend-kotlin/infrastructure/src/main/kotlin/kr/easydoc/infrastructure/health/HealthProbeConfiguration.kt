package kr.easydoc.infrastructure.health

import kr.easydoc.application.health.DependencyProbe
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * `/health` 가 읽는 의존 서비스 진단 — **DB 와 작업 큐**.
 *
 * ## 두 진단이 같은 DataSource 를 쓴다
 *
 * 계약이 그것을 명시했다 — *"큐가 PostgreSQL lease 테이블로 옮겨졌으므로 Redis 는 이
 * 런타임의 의존 서비스가 아니다. `checks` 의 `queue` 키는 **남는다**: 큐가 사라진 것이
 * 아니라 **같은 DataSource 위의 테이블**이 됐고, 「그 작업 테이블을 읽을 수 있는가」는
 * 여전히 별개 진단이다."*
 *
 * **별개인 이유가 실재한다.** 커넥션은 살아 있는데 마이그레이션이 밀려 작업 테이블이 없거나
 * 권한이 없는 상태가 있다 — 그때 `database: true`·`queue: false` 가 나가고, 그것이 배포
 * 진단으로서 이 엔드포인트가 존재하는 이유다(계약이 인용한 `app/api/deps.py` 의 설계 의도).
 * 두 진단을 한 키로 합치면 그 상태를 표현할 자리가 없어진다.
 *
 * ## 던지지 않는다
 *
 * [DependencyProbe] 규약이다. 예외가 올라가면 `/health` 가 5xx 가 되고 계약의
 * *"항상 200"* 이 깨진다. 여기서 접는 것이 첫 겹이고, `HealthDiagnosis` 가 한 겹 더 접는다.
 *
 * ## 무엇도 로깅하지 않는다
 *
 * JDBC 예외 메시지에는 접속 URL 이 실리고, 드라이버·풀 구성에 따라 사용자 이름까지 실린다.
 * `/health` 는 **인증 없이 누구나 부를 수 있는** 엔드포인트라 호출 빈도가 통제되지 않으므로,
 * 여기서 로그를 남기면 장애 중에 그 문자열이 로그를 가득 채운다. 진단 결과는 응답 본문의
 * 불리언 하나로 이미 관측 가능하다.
 *
 * ## `migrate` 프로필에서도 조립된다
 *
 * `DocumentConfiguration`·`CryptoConfiguration` 과 달리 프로필 조건이 없다. 이 설정이
 * 요구하는 것은 [JdbcClient] 하나이고 그 빈은 세 프로필 모두에 있다 — 조건을 붙이면
 * 스키마 이관 잡의 `/health` 가 `{}`·`ok` 를 내면서 "확인했고 정상"처럼 보인다.
 */
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
         *
         * `count(*)` 로 전수를 세지 않는 이유: 인증 없이 누구나 부르는 엔드포인트에 큐 길이에
         * 비례하는 일을 붙이면 그 자체가 값싼 부하 수단이 된다.
         *
         * 테이블 이름을 `V5__conversion_jobs.sql` 과 같은 문자열로 적는다. 상수를 공유할
         * 자리가 없다 — 마이그레이션은 그 시점 스키마의 스냅샷이라 코드 상수를 참조할 수 없고,
         * 이름이 갈리면 이 진단이 곧바로 `false` 를 내 시끄럽게 드러난다.
         */
        const val QUEUE_QUERY = "SELECT 1 FROM conversion_jobs WHERE false"
    }
}

/**
 * 질의 한 방으로 판정하는 probe.
 *
 * 두 진단이 같은 클래스를 쓰는 것이 요점이다 — 다르게 접는 두 구현을 두면 한쪽만 예외를
 * 흘리는 상태가 생긴다.
 *
 * `toString()` 을 재정의하지 않는다 — 담긴 것이 진단 이름과 상수 질의뿐이라 샐 값이 없다.
 * 그래도 `data class` 로 두지 않는다: [JdbcClient] 가 필드에 있고 그 `toString()` 이 무엇을
 * 담을지는 이 파일이 통제하지 못한다.
 */
private class QueryProbe(
    override val dependency: String,
    private val query: String,
    private val jdbcClient: JdbcClient,
) : DependencyProbe {
    /**
     * 질의가 예외 없이 끝나면 `true`.
     *
     * 결과 **행 수를 보지 않는다** — 큐 진단은 `WHERE false` 라 0행이 정상이다. 재는 것은
     * "이 질의를 서버가 받아 처리했는가"이지 "데이터가 있는가"가 아니다.
     */
    override fun isReachable(): Boolean =
        runCatching {
            jdbcClient.sql(query).query { _, _ -> Unit }.list()
            true
        }.getOrDefault(false)
}
