package kr.easydoc.api

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * **Phase 1 종료 조건 검증 — 갈래 1: 빈 DB** (계획 §5 Phase 1).
 *
 * > 빈 DB와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함.
 *
 * `@WebMvcTest` 가 아니라 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 다 — 실제
 * 서블릿 컨테이너와 실제 DataSource·Flyway 배선을 지난다. 배선이 빠진 채 컨트롤러만
 * 도는 상황을 통과시키지 않기 위해서다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiStartupOnEmptyDatabaseTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @DisplayName("빈 DB 에서 기동하고 /health 가 200 ok 를 돌려준다")
    fun `빈 DB 에서 기동한다`() {
        val response = StartupDatabases.httpGet(port, "/health")

        assertThat(port).isGreaterThan(0)
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("""{"status":"ok"}""")
    }

    @Test
    @DisplayName("Flyway 가 V1·V2 를 적용했다")
    fun `스키마가 적용됐다`() {
        assertThat(StartupDatabases.appliedVersions(StartupDatabases.empty))
            .containsExactly("1", "2")
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            StartupDatabases.bind(registry, StartupDatabases.empty)
        }
    }
}

/**
 * **Phase 1 종료 조건 검증 — 갈래 2: 기존 schema snapshot**.
 *
 * Alembic 0006 까지 올라온 DB 위에서 기동한다. `FlywayBaselineGuard` 가 지문을 대조해
 * baseline version 1 을 기록하고 V2만 적용해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiStartupOnPythonSnapshotTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @DisplayName("기존 Python 스키마 위에서 기동하고 /health 가 200 ok 를 돌려준다")
    fun `기존 스키마에서 기동한다`() {
        val response = StartupDatabases.httpGet(port, "/health")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("""{"status":"ok"}""")
    }

    @Test
    @DisplayName("baseline 이 기록되고 alembic_version 은 그대로다")
    fun `baseline 을 기록하고 alembic_version 을 건드리지 않는다`() {
        assertThat(StartupDatabases.appliedVersions(StartupDatabases.pythonSnapshot))
            .containsExactly("1", "2")

        // 계획 §4.2-7: Python 제거 전까지 alembic_version 은 보존하고 Kotlin이 수정하지 않는다.
        val alembicVersion =
            StartupDatabases.pythonSnapshot
                .queryFirstColumn("SELECT version_num FROM alembic_version")
                .firstOrNull()
        assertThat(alembicVersion).isEqualTo("0006")
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            StartupDatabases.bind(registry, StartupDatabases.pythonSnapshot)
        }
    }
}

/** 두 기동 갈래가 쓰는 데이터베이스 준비. */
private object StartupDatabases {
    /** 갈래 1 — 테이블이 하나도 없는 DB. */
    val empty: DatabaseHandle by lazy { PostgresTestSupport.createEmptyDatabase("startup_empty") }

    /**
     * 갈래 2 — "Alembic 이 0006 까지 올린 DB".
     *
     * V1 적용 → Flyway 장부 제거 → `alembic_version` 삽입으로 만든다.
     * `PythonSchemaBaselineTest` 가 V1 ≡ Alembic 을 증명하므로 이 대역이 성립한다.
     */
    val pythonSnapshot: DatabaseHandle by lazy {
        val database = PostgresTestSupport.createEmptyDatabase("startup_snapshot")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .target("1")
            .load()
            .migrate()
        database.execute(
            """
            DROP TABLE flyway_schema_history;
            CREATE TABLE alembic_version (version_num varchar(32) NOT NULL
                CONSTRAINT alembic_version_pkc PRIMARY KEY);
            INSERT INTO alembic_version VALUES ('0006');
            """.trimIndent(),
        )
        database
    }

    /**
     * 진짜 HTTP 로 부른다.
     *
     * Spring 테스트 클라이언트 대신 JDK `HttpClient` 를 쓰는 이유는 두 가지다.
     * ① 서블릿 컨테이너가 실제로 떠서 소켓을 열었다는 것까지 확인된다.
     * ② Spring Boot 4 가 테스트 클라이언트를 기술별 모듈로 재배치했는데, 기동 확인이
     *    그 재배치에 묶일 이유가 없다.
     */
    fun httpGet(
        port: Int,
        path: String,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun bind(
        registry: DynamicPropertyRegistry,
        database: DatabaseHandle,
    ) {
        registry.add("spring.datasource.url") { database.jdbcUrl }
        registry.add("spring.datasource.username") { database.username }
        registry.add("spring.datasource.password") { database.password }
    }

    fun appliedVersions(database: DatabaseHandle): List<String> =
        database.queryFirstColumn("SELECT version FROM flyway_schema_history ORDER BY installed_rank")
}
