package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 계획 §4.2-4 "기존 DB는 schema checksum이 일치할 때만 baseline version 1을 기록한다"의
 * 회귀 테스트.
 *
 * 세 갈래를 모두 본다.
 * - 빈 DB → baseline 없이 V1부터 적용
 * - Python 기준선과 같은 기존 스키마 → baseline 기록 후 V2만 적용
 * - 기준선과 다른 기존 스키마 → **기동 실패** (조용히 얹지 않는다)
 *
 * "Python이 만든 기존 스키마"는 V1을 적용해 만든다. [PythonSchemaBaselineTest] 가
 * V1 ≡ Alembic 을 이미 증명하므로 이 대역은 정당하다.
 */
class FlywayBaselineGuardTest {
    private companion object {
        /** 동시 기동 탐침의 스레드 수. 프로필 셋(api·worker·migrate)을 흉내 낸다. */
        const val CONCURRENT_STARTS = 3
    }

    private val guard = FlywayBaselineGuard()

    @Test
    @DisplayName("빈 DB — baseline 없이 V1부터 적용된다")
    fun `빈 DB 는 V1 부터 적용된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_empty")

        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(appliedVersions(database)).containsExactly("1", "2")
    }

    @Test
    @DisplayName("기존 Python 스키마 — baseline 을 기록하고 V2만 적용한다")
    fun `기존 Python 스키마를 baseline 한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_existing")
        givenAlembicManagedSchema(database)

        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        // baseline(1) 이 기록되고 V1은 재적용되지 않는다. V2만 새로 적용된다.
        assertThat(appliedVersions(database)).containsExactly("1", "2")
        assertThat(migrationTypes(database)).containsExactly("BASELINE", "SQL")

        // alembic_version 은 손대지 않았다 (계획 §4.2-7).
        assertThat(alembicVersion(database)).isEqualTo("0006")

        // V2는 실제로 적용됐다.
        assertThat(database.connect().use { SchemaFingerprint.of(it) })
            .contains("column documents 11 encryption_scheme")
    }

    @Test
    @DisplayName("기준선과 다른 기존 스키마 — 기동을 실패시킨다")
    fun `기준선과 다르면 baseline 하지 않고 실패한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_drift")
        givenAlembicManagedSchema(database)
        // 운영 중 누군가 손으로 컬럼을 붙인 상황. baseline-on-migrate=true 였다면
        // 아무 확인 없이 통과했을 자리다.
        database.execute("ALTER TABLE users ADD COLUMN nickname varchar(50)")

        assertThatThrownBy { guard.flywayMigrationStrategy().migrate(flywayFor(database)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("기존 스키마가 Python 기준선과 다르다")
            // 무엇이 다른지 메시지에 담긴다 — 급할 때 필요한 것은 값이 아니라 원인이다.
            .hasMessageContaining("nickname")

        // 실패했으므로 Flyway 장부가 만들어지지 않았다.
        assertThat(hasFlywayHistoryTable(database)).isFalse()
    }

    @Test
    @DisplayName("이미 Flyway 가 관리하는 DB 는 baseline 을 다시 하지 않는다")
    fun `두 번 실행해도 baseline 이 중복되지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_idempotent")
        givenAlembicManagedSchema(database)

        guard.flywayMigrationStrategy().migrate(flywayFor(database))
        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(appliedVersions(database)).containsExactly("1", "2")
    }

    @Test
    @DisplayName("Alembic head 가 0006 이 아니면 baseline 하지 않고 실패한다")
    fun `리비전이 다르면 실패한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_alembic_head")
        givenAlembicManagedSchema(database)
        // **지문은 그대로다.** 값만 바꾸는 리비전(백필 등)은 스키마 모양을 안 건드리므로
        // 지문 대조를 통과한다 — 그 축만으로는 이 상태를 가려낼 수 없다는 것이 요점이다.
        database.execute("UPDATE alembic_version SET version_num = '0007'")

        assertThatThrownBy { guard.flywayMigrationStrategy().migrate(flywayFor(database)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Alembic 리비전이 기준선의 가정과 다르다")
            .hasMessageContaining("0007")

        assertThat(hasFlywayHistoryTable(database)).isFalse()
    }

    @Test
    @DisplayName("지문만으로는 리비전 차이를 못 잡는다 — 새 검사가 필요한 이유")
    fun `지문은 리비전 변경에 반응하지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_head_axis")
        givenAlembicManagedSchema(database)
        val before = fingerprintOf(database)

        database.execute("UPDATE alembic_version SET version_num = '0007'")

        assertThat(fingerprintOf(database))
            .describedAs("지문이 리비전에 반응한다면 이 검사가 중복이라는 뜻이다")
            .isEqualTo(before)
    }

    @Test
    @DisplayName("alembic_version 이 없는 DB 에서도 baseline 이 된다")
    fun `alembic 테이블이 없어도 통과한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_no_alembic")
        givenAlembicManagedSchema(database)
        database.execute("DROP TABLE alembic_version")

        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(appliedVersions(database)).containsExactly("1", "2")
    }

    @Test
    @DisplayName("baseline 을 마쳐도 alembic_version 은 그대로다 — 읽기만 한다")
    fun `alembic_version 을 쓰지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_alembic_readonly")
        givenAlembicManagedSchema(database)

        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(alembicVersion(database)).isEqualTo("0006")
    }

    @Test
    @DisplayName("동시 기동에서도 baseline 이 한 번만 찍힌다")
    fun `동시 기동이 직렬화된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_concurrent")
        givenAlembicManagedSchema(database)

        // api·worker·migrate 프로필이 함께 뜨는 상황. 앞선 판은 판정과 baseline 이 서로
        // 다른 연결이라 셋이 같은 판정을 동시에 하고 각자 baseline 을 시도했다.
        val pool = Executors.newFixedThreadPool(CONCURRENT_STARTS)
        try {
            val failures =
                pool
                    .invokeAll(
                        (1..CONCURRENT_STARTS).map {
                            Callable {
                                runCatching {
                                    guard.flywayMigrationStrategy().migrate(flywayFor(database))
                                }.exceptionOrNull()
                            }
                        },
                    ).mapNotNull { it.get() }

            assertThat(failures)
                .describedAs("동시 기동 중 하나라도 실패하면 잠금이 늦거나 없다")
                .isEmpty()
        } finally {
            pool.shutdownNow()
        }

        assertThat(appliedVersions(database)).containsExactly("1", "2")
        assertThat(migrationTypes(database).first()).isEqualTo("BASELINE")
    }

    // --- 준비 ---------------------------------------------------------------

    /**
     * "Alembic이 0006까지 올린 DB"를 만든다.
     *
     * V1을 적용해 스키마를 만든 뒤 Flyway 장부를 지우고 `alembic_version` 을 넣는다 —
     * 이것이 Python 런타임만 돌던 환경의 상태다.
     */
    private fun givenAlembicManagedSchema(database: DatabaseHandle) {
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
    }

    private fun flywayFor(database: DatabaseHandle): Flyway =
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .baselineVersion("1")
            .load()

    // --- 조회 ---------------------------------------------------------------

    private fun appliedVersions(database: DatabaseHandle): List<String> =
        query(database, "SELECT version FROM flyway_schema_history ORDER BY installed_rank")

    private fun migrationTypes(database: DatabaseHandle): List<String> =
        query(database, "SELECT type FROM flyway_schema_history ORDER BY installed_rank")

    private fun fingerprintOf(database: DatabaseHandle): String =
        java.sql.DriverManager
            .getConnection(database.jdbcUrl, database.username, database.password)
            .use { SchemaFingerprint.of(it) }

    private fun alembicVersion(database: DatabaseHandle): String? =
        query(database, "SELECT version_num FROM alembic_version").firstOrNull()

    private fun hasFlywayHistoryTable(database: DatabaseHandle): Boolean =
        query(
            database,
            "SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'public' AND c.relname = 'flyway_schema_history'",
        ).isNotEmpty()

    private fun query(
        database: DatabaseHandle,
        sql: String,
    ): List<String> = database.queryFirstColumn(sql)
}
