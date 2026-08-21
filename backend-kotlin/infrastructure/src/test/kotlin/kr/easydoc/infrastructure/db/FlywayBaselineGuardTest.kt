package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.MigrationCatalog
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * 계획 §4.2-4 "기존 DB는 schema checksum이 일치할 때만 baseline version 1을 기록한다"의
 * 회귀 테스트.
 */
class FlywayBaselineGuardTest {
    private companion object {
        /** 동시 기동 탐침의 스레드 수. 프로필 셋(api·worker·migrate)을 흉내 낸다. */
        const val CONCURRENT_STARTS = 3

        /** 임계 구간에서 최소한 이만큼의 질의가 관측돼야 추적이 살아 있다고 본다. */
        const val MIN_CRITICAL_STATEMENTS = 6

        /** TOCTOU 탐침에서 경쟁 액터가 잠금을 쥐고 버티는 시간. */
        const val INTRUDER_GRACE_MILLIS = 750L
    }

    private val guard = FlywayBaselineGuard()

    @Test
    @DisplayName("빈 DB — baseline 없이 V1부터 적용된다")
    fun `빈 DB 는 V1 부터 적용된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_empty")

        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
    }

    @Test
    @DisplayName("기존 Python 스키마 — baseline 을 기록하고 V1 뒤의 마이그레이션만 적용한다")
    fun `기존 Python 스키마를 baseline 한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_existing")
        givenAlembicManagedSchema(database)

        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
        assertThat(migrationTypes(database)).containsExactlyElementsOf(MigrationCatalog.typesAfterPythonBaseline)

        assertThat(alembicVersion(database)).isEqualTo("0006")

        assertThat(database.connect().use { SchemaFingerprint.of(it) })
            .contains("column documents 11 encryption_scheme")
    }

    @Test
    @DisplayName("기준선과 다른 기존 스키마 — 기동을 실패시킨다")
    fun `기준선과 다르면 baseline 하지 않고 실패한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_drift")
        givenAlembicManagedSchema(database)

        database.execute("ALTER TABLE users ADD COLUMN nickname varchar(50)")

        assertThatThrownBy { guard.flywayMigrationStrategy().migrate(flywayFor(database)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("기존 스키마가 Python 기준선과 다르다")
            .hasMessageContaining("nickname")

        assertThat(hasFlywayHistoryTable(database)).isFalse()
    }

    @Test
    @DisplayName("이미 Flyway 가 관리하는 DB 는 baseline 을 다시 하지 않는다")
    fun `두 번 실행해도 baseline 이 중복되지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_idempotent")
        givenAlembicManagedSchema(database)

        guard.flywayMigrationStrategy().migrate(flywayFor(database))
        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
    }

    @Test
    @DisplayName("Alembic head 가 0006 이 아니면 baseline 하지 않고 실패한다")
    fun `리비전이 다르면 실패한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_alembic_head")
        givenAlembicManagedSchema(database)

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

        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
    }

    @Test
    @DisplayName("baseline 을 마쳐도 alembic_version 은 그대로다 — 읽기만 한다")
    fun `alembic_version 을 쓰지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_alembic_readonly")
        givenAlembicManagedSchema(database)

        guard.flywayMigrationStrategy().migrate(flywayFor(database))

        assertThat(alembicVersion(database)).isEqualTo("0006")
    }

    /** ## 종전 단언이 왜 바뀌었나 (리뷰 T-A⑵) */
    @Test
    @DisplayName("동시 기동의 임계 구간이 서로 겹치지 않는다")
    fun `동시 기동이 직렬화된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_concurrent")
        givenAlembicManagedSchema(database)
        val tracer = MigrationStatementTracer()

        val startLine = CyclicBarrier(CONCURRENT_STARTS)
        val pool = Executors.newFixedThreadPool(CONCURRENT_STARTS)
        val failures =
            try {
                pool
                    .invokeAll(
                        (1..CONCURRENT_STARTS).map {
                            Callable {
                                startLine.await()
                                runCatching {
                                    guard.flywayMigrationStrategy().migrate(flywayFor(database, tracer))
                                }.exceptionOrNull()
                            }
                        },
                    ).mapNotNull { it.get() }
            } finally {
                pool.shutdownNow()
            }

        assertThat(tracer.criticalWindows())
            .describedAs("기동 %d 개가 전부 스키마 질의를 남겨야 한다:\n%s", CONCURRENT_STARTS, tracer.describeWindows())
            .hasSize(CONCURRENT_STARTS)
        assertThat(tracer.criticalStatements().size)
            .describedAs("판정·기록 구간의 질의 수가 너무 적다 — 추적이 일부만 잡고 있다")
            .isGreaterThanOrEqualTo(MIN_CRITICAL_STATEMENTS)

        assertThat(tracer.overlappingWindows())
            .describedAs(
                "두 기동이 판정·기록 구간에 동시에 들어갔다 — 잠금이 없거나 늦다.\n" +
                    "관측된 구간:\n%s",
                tracer.describeWindows(),
            ).isEmpty()

        assertThat(migrationTypes(database)).containsExactlyElementsOf(MigrationCatalog.typesAfterPythonBaseline)
        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
        assertThat(failures).describedAs("동시 기동 중 하나가 실패했다").isEmpty()
    }

    /**
     * TOCTOU 축 (리뷰 T-A⑶). codex #4 의 원 지적은 두 갈래였는데 실증은 동시 기동 쪽만
     * 있었다. 이것이 나머지 갈래다 — 지문을 읽은 뒤 baseline 을 찍기 전에 스키마가 바뀌면
     * 확인하지 않은 스키마에 baseline 이 찍힌다.
     */
    @Test
    @DisplayName("판정 직전에 바뀐 스키마는 baseline 되지 않는다 — TOCTOU")
    fun `판정과 기록 사이에 스키마 변경이 끼어들지 못한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_toctou")
        givenAlembicManagedSchema(database)

        val lockHeld = CountDownLatch(1)
        val intruder =
            Thread {
                withMigrationLock(database) {
                    lockHeld.countDown()

                    Thread.sleep(INTRUDER_GRACE_MILLIS)
                    database.execute("ALTER TABLE users ADD COLUMN nickname varchar(50)")
                }
            }
        intruder.start()
        lockHeld.await()

        try {
            assertThatThrownBy { guard.flywayMigrationStrategy().migrate(flywayFor(database)) }
                .describedAs("경쟁 액터가 바꾼 스키마를 가드가 보지 못했다 — 판정과 기록 사이가 열려 있다")
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("기존 스키마가 Python 기준선과 다르다")
                .hasMessageContaining("nickname")
        } finally {
            intruder.join()
        }

        assertThat(hasFlywayHistoryTable(database)).isFalse()
    }

    /** "Alembic이 0006까지 올린 DB"를 만든다. */
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
        flywayOn(TracingDataSource(database, MigrationStatementTracer()))

    /** 실행된 SQL 을 [tracer] 에 남기는 판. 임계 구간을 재는 테스트만 쓴다. */
    private fun flywayFor(
        database: DatabaseHandle,
        tracer: MigrationStatementTracer,
    ): Flyway = flywayOn(TracingDataSource(database, tracer))

    private fun flywayOn(dataSource: DataSource): Flyway =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineVersion("1")
            .load()

    /** 가드와 같은 advisory lock 을 잡고 [block] 을 돌린다. */
    private fun withMigrationLock(
        database: DatabaseHandle,
        block: () -> Unit,
    ) {
        database.connect().use { connection ->
            connection.callMigrationLock("SELECT pg_advisory_lock(?)")
            try {
                block()
            } finally {
                connection.callMigrationLock("SELECT pg_advisory_unlock(?)")
            }
        }
    }

    private fun Connection.callMigrationLock(sql: String) {
        prepareStatement(sql).use { statement ->
            statement.setLong(1, FlywayBaselineGuard.MIGRATION_LOCK_KEY)
            statement.executeQuery().use { it.next() }
        }
    }

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
