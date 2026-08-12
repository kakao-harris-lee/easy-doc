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

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName("easydoc_template")
            .withUsername("postgres")
            .withPassword("postgres")
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
