package kr.easydoc.api

import kr.easydoc.api.support.ContractSpec
import kr.easydoc.application.health.HealthDiagnosis
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.MigrationCatalog
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Phase 1 종료 조건 검증 — 갈래 1: 빈 DB (계획 §5 Phase 1). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiStartupOnEmptyDatabaseTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @DisplayName("빈 DB 에서 기동하고 /health 가 200 · 두 의존 서비스 진단이 **참**이다")
    fun `빈 DB 에서 기동한다`() {
        val response = StartupDatabases.httpGet(port, "/health")

        assertThat(port).isGreaterThan(0)
        assertThat(response.statusCode()).isEqualTo(200)

        StartupDatabases.assertDependenciesUp(response)
    }

    @Test
    @DisplayName("Flyway 가 V1·V2 를 적용했다")
    fun `스키마가 적용됐다`() {
        assertThat(StartupDatabases.appliedVersions(StartupDatabases.empty))
            .containsExactlyElementsOf(MigrationCatalog.versions)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            StartupDatabases.bind(registry, StartupDatabases.empty)
        }
    }
}

/** Phase 1 종료 조건 검증 — 갈래 2: 기존 schema snapshot. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiStartupOnPythonSnapshotTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @DisplayName("기존 Python 스키마 위에서 기동하고 /health 가 200 · 두 의존 서비스 진단이 **참**이다")
    fun `기존 스키마에서 기동한다`() {
        val response = StartupDatabases.httpGet(port, "/health")

        assertThat(response.statusCode()).isEqualTo(200)

        StartupDatabases.assertDependenciesUp(response)
    }

    @Test
    @DisplayName("baseline 이 기록되고 alembic_version 은 그대로다")
    fun `baseline 을 기록하고 alembic_version 을 건드리지 않는다`() {
        assertThat(StartupDatabases.appliedVersions(StartupDatabases.pythonSnapshot))
            .containsExactlyElementsOf(MigrationCatalog.versions)

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

    /** 갈래 2 — "Alembic 이 0006 까지 올린 DB". */
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

    /** 진짜 HTTP 로 부른다. */
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

    /** `/health` 가 실 배선에서 의존 서비스를 진단했음을 단언한다. */
    fun assertDependenciesUp(response: HttpResponse<String>) {
        val body = ObjectMapper().readValue(response.body(), Map::class.java)
        assertThat(body.keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.schemaRequired(HEALTH_SCHEMA))

        val checks = body[CHECKS_PROPERTY] as Map<*, *>
        assertThat(checks)
            .describedAs("DataSource 가 있는 컨텍스트인데 진단이 비어 있다 — probe 빈이 조립되지 않았다")
            .isNotEmpty()
        assertThat(checks.keys.map { it.toString() }.toSet())
            .isEqualTo(ContractSpec.healthCheckKeys())
        assertThat(checks.values).allSatisfy { assertThat(it).isEqualTo(true) }
        assertThat(body[STATUS_PROPERTY]).isEqualTo(HealthDiagnosis.STATUS_OK)
    }

    private const val HEALTH_SCHEMA = "HealthResponse"
    private const val STATUS_PROPERTY = "status"
    private const val CHECKS_PROPERTY = "checks"
}
