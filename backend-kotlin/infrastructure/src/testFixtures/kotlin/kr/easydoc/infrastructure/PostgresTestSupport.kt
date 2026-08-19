package kr.easydoc.infrastructure

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * 테스트용 PostgreSQL 컨테이너.
 *
 * 이미지를 `pgvector/pgvector:pg16` 으로 고정한다 — `docker-compose.yml` 과 CI가 쓰는
 * 이미지가 그것이고, V1 이 `CREATE EXTENSION vector` 를 하기 때문이다. 순정 postgres
 * 이미지에는 확장 파일이 없어 V1 이 실패한다.
 *
 * 컨테이너를 클래스마다 새로 띄우지 않고 **JVM 하나에 하나만** 띄운다. 테스트 클래스마다
 * 새 PostgreSQL을 띄우면 전체 실행이 분 단위로 늘고, 그러면 개발 중에 아무도 안 돌린다
 * (kotlin-spring-conventions §8).
 *
 * 격리는 컨테이너가 아니라 **데이터베이스 단위**로 한다 — 각 테스트가
 * [createEmptyDatabase] 로 새 DB를 만들어 쓰므로 서로의 스키마를 밟지 않는다.
 */
object PostgresTestSupport {
    /** compose·CI와 같은 이미지. 바꾸면 vector 확장이 없어 V1 이 깨진다. */
    private val IMAGE: DockerImageName = DockerImageName.parse("pgvector/pgvector:pg16")

    private var counter = 0

    /**
     * `max_connections` 를 기본 100 에서 올린다.
     *
     * 컨테이너가 JVM 하나에 하나뿐인데 커넥션을 쓰는 것은 **캐시된 Spring 컨텍스트마다
     * 하나씩**인 HikariCP 풀(기본 최대 10)이다. `@SpringBootTest` 는 프로퍼티 조합이
     * 다르면 컨텍스트를 새로 만들어 캐시에 남기므로, 그런 테스트 클래스가 하나 늘 때마다
     * 상한이 10 씩 깎인다.
     *
     * 실제로 넘겼다 — `DeletedAccountTokenReachTest`(X-1) 를 더하자 전체 빌드에서
     * `FATAL: sorry, too many clients already` 로 3건이 빨개졌다(개별 실행은 전건 통과).
     * **테스트가 잰 성질과 무관한 실패**라 여기서 막는다. 값을 올리는 편이 풀 크기를
     * 테스트마다 손으로 줄이는 것보다 낫다 — 후자는 다음에 추가되는 테스트가 또 빠뜨린다.
     */
    private const val MAX_CONNECTIONS = 400

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName("easydoc_template")
            .withUsername("postgres")
            .withPassword("postgres")
            .withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
            .also { it.start() }
    }

    /** 새 빈 데이터베이스를 만들고 그 JDBC URL을 돌려준다. */
    @Synchronized
    fun createEmptyDatabase(prefix: String): DatabaseHandle {
        val name = "${prefix.lowercase().replace(Regex("[^a-z0-9_]"), "_")}_${++counter}"
        adminConnection().use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE DATABASE $name") }
        }
        val url = container.jdbcUrl.replace("/${container.databaseName}", "/$name")
        return DatabaseHandle(name, url, container.username, container.password)
    }

    private fun adminConnection(): Connection =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
}

/** 테스트 한 건이 쓰는 데이터베이스 접속 정보. */
data class DatabaseHandle(
    val name: String,
    val jdbcUrl: String,
    val username: String,
    val password: String,
) {
    fun connect(): Connection = DriverManager.getConnection(jdbcUrl, username, password)

    /**
     * 첫 컬럼만 문자열 리스트로 읽는다.
     *
     * 세 테스트(baseline·기동·worker)가 같은 구현을 쓰도록 여기에 둔다. 각자 JDBC 를
     * 직접 다루면 `use` 중첩이 깊어져 읽기 어렵고, 커넥션을 닫지 않는 사본이 하나쯤 생긴다.
     */
    fun queryFirstColumn(sql: String): List<String> {
        val values = mutableListOf<String>()
        connect().use { connection ->
            connection.createStatement().use { statement ->
                val rows = statement.executeQuery(sql)
                while (rows.next()) {
                    values += rows.getString(1)
                }
            }
        }
        return values
    }

    /** 단일 정수 결과를 읽는다 (`count(*)` 등). 행이 없으면 -1. */
    fun queryInt(sql: String): Int = queryFirstColumn(sql).firstOrNull()?.toInt() ?: -1

    /** DDL·DML 여러 문장을 한 번에 실행한다. */
    fun execute(sql: String) {
        connect().use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }
}
