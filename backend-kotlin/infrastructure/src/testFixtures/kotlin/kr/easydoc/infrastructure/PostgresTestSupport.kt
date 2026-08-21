package kr.easydoc.infrastructure

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/** 테스트용 PostgreSQL 컨테이너. */
object PostgresTestSupport {
    /** compose·CI와 같은 이미지. 바꾸면 vector 확장이 없어 V1 이 깨진다. */
    private val IMAGE: DockerImageName = DockerImageName.parse("pgvector/pgvector:pg16")

    private var counter = 0

    /** `max_connections` 를 기본 100 에서 올린다. */
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

    /** 첫 컬럼만 문자열 리스트로 읽는다. */
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
