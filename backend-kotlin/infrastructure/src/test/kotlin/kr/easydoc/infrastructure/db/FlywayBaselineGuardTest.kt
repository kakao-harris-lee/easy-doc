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
 *
 * 세 갈래를 모두 본다.
 * - 빈 DB → baseline 없이 V1부터 적용
 * - Python 기준선과 같은 기존 스키마 → baseline 기록 후 V1 을 제외한 나머지만 적용
 * - 기준선과 다른 기존 스키마 → **기동 실패** (조용히 얹지 않는다)
 *
 * "Python이 만든 기존 스키마"는 V1을 적용해 만든다. [PythonSchemaBaselineTest] 가
 * V1 ≡ Alembic 을 이미 증명하므로 이 대역은 정당하다.
 */
class FlywayBaselineGuardTest {
    private companion object {
        /** 동시 기동 탐침의 스레드 수. 프로필 셋(api·worker·migrate)을 흉내 낸다. */
        const val CONCURRENT_STARTS = 3

        /**
         * 임계 구간에서 최소한 이만큼의 질의가 관측돼야 추적이 살아 있다고 본다.
         *
         * 기동 하나가 판정에만 이력 확인 · 테이블 수 · 지문 · Alembic 존재 · Alembic head
         * 로 다섯 번을 쓰므로 셋이면 훨씬 많다. 하한을 낮게 잡은 것은 Flyway 판올림으로
         * 내부 질의 수가 바뀌어도 이 테스트가 그 때문에 깨지지 않게 하려는 것이고,
         * 목적은 "추적이 통째로 죽었는가"를 가르는 것뿐이다.
         */
        const val MIN_CRITICAL_STATEMENTS = 6

        /**
         * TOCTOU 탐침에서 경쟁 액터가 잠금을 쥐고 버티는 시간.
         *
         * 잠금이 **있을 때** 이 값은 결과에 영향을 주지 않는다(가드는 액터가 놓을 때까지
         * 무조건 기다린다). 영향을 주는 것은 음성 대조 쪽이다 — 잠금을 떼면 가드가
         * 이 시간 안에 판정·baseline·migrate 를 끝내야 탐침이 빨개진다. 실측한 가드
         * 왕복은 100ms 안쪽이라 넉넉하게 잡았다.
         */
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

        // baseline(1) 이 기록되고 V1은 재적용되지 않는다. 그 뒤 버전만 새로 적용된다.
        // 기대값은 손으로 세지 않는다 — 근거는 MigrationCatalog KDoc.
        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
        assertThat(migrationTypes(database)).containsExactlyElementsOf(MigrationCatalog.typesAfterPythonBaseline)

        // alembic_version 은 손대지 않았다 (계획 §4.2-7).
        assertThat(alembicVersion(database)).isEqualTo("0006")

        // V1 뒤의 마이그레이션이 실제로 적용됐다(V2 가 더한 컬럼이 있다).
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

        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
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

    /**
     * ## 종전 단언이 왜 바뀌었나 (리뷰 T-A⑵)
     *
     * 앞선 판의 단언은 `failures.isEmpty()` 하나 — **"예외가 안 났음"** 이었다. 리뷰가
     * "잠금을 떼도 초록일 수 있다"고 지적해 일회용 worktree 에서 실측했고, 지적이 맞았다.
     * `withAdvisoryLock` 을 `run` 으로 바꾸고 4회 돌렸을 때 **매번 9 tests / 0 failures**
     * 였다. Flyway 가 이력 테이블에 자기 잠금을 걸어 `baseline()`·`migrate()` 구간을
     * 알아서 직렬화하고 중복 baseline 도 조용히 넘기기 때문이다.
     *
     * 그래서 대리 지표를 버리고 **잠금이 지키기로 한 것 자체**를 잰다 — 판정(이력 확인 ·
     * 테이블 수 · 지문 · Alembic head)부터 기록(baseline · migrate)까지가 기동마다 겹치지
     * 않는가. 그 구간은 우리 잠금 말고는 아무도 지키지 않으므로, 잠금을 떼면 곧바로 겹친다.
     */
    @Test
    @DisplayName("동시 기동의 임계 구간이 서로 겹치지 않는다")
    fun `동시 기동이 직렬화된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("guard_concurrent")
        givenAlembicManagedSchema(database)
        val tracer = MigrationStatementTracer()

        // api·worker·migrate 프로필이 함께 뜨는 상황. 앞선 판은 판정과 baseline 이 서로
        // 다른 연결이라 셋이 같은 판정을 동시에 하고 각자 baseline 을 시도했다.
        // 출발선을 barrier 로 맞춘다 — 스레드 기동 지연이 우연한 직렬화를 만들면
        // "겹치지 않았다"가 잠금 덕분인지 순서 덕분인지 갈리지 않는다.
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

        // ⑴ 탐침이 살아 있는가. 이 단언이 없으면 추적이 깨졌을 때 "겹침 0" 으로 조용히 통과한다.
        assertThat(tracer.criticalWindows())
            .describedAs("기동 %d 개가 전부 스키마 질의를 남겨야 한다:\n%s", CONCURRENT_STARTS, tracer.describeWindows())
            .hasSize(CONCURRENT_STARTS)
        assertThat(tracer.criticalStatements().size)
            .describedAs("판정·기록 구간의 질의 수가 너무 적다 — 추적이 일부만 잡고 있다")
            .isGreaterThanOrEqualTo(MIN_CRITICAL_STATEMENTS)

        // ⑵ 본 단언: 임계 구간이 겹치지 않는다. 잠금을 떼면 여기가 빨개진다.
        assertThat(tracer.overlappingWindows())
            .describedAs(
                "두 기동이 판정·기록 구간에 동시에 들어갔다 — 잠금이 없거나 늦다.\n" +
                    "관측된 구간:\n%s",
                tracer.describeWindows(),
            ).isEmpty()

        // ⑶ 결과: baseline 은 정확히 한 번이고, 아무도 실패하지 않았다.
        //     (실패 없음은 직렬화의 증거가 아니라 부작용이 없었다는 확인이다.)
        assertThat(migrationTypes(database)).containsExactlyElementsOf(MigrationCatalog.typesAfterPythonBaseline)
        assertThat(appliedVersions(database)).containsExactlyElementsOf(MigrationCatalog.versions)
        assertThat(failures).describedAs("동시 기동 중 하나가 실패했다").isEmpty()
    }

    /**
     * TOCTOU 축 (리뷰 T-A⑶). codex #4 의 원 지적은 두 갈래였는데 실증은 동시 기동 쪽만
     * 있었다. 이것이 나머지 갈래다 — **지문을 읽은 뒤 baseline 을 찍기 전에 스키마가 바뀌면
     * 확인하지 않은 스키마에 baseline 이 찍힌다.**
     *
     * 재현 방법: 같은 advisory lock 을 존중하는 경쟁 액터를 세운다. 액터가 먼저 잠금을
     * 잡고, 가드가 뒤늦게 들어오려 하는 동안 스키마를 바꾼 뒤 잠금을 놓는다.
     *
     * - 잠금이 있으면 가드는 **바뀐 뒤에** 읽으므로 어긋남을 보고 기동을 거부한다.
     * - 잠금이 없으면 가드는 액터를 기다리지 않고 **바뀌기 전에** 읽어 통과시키고,
     *   baseline 을 찍은 다음에야 스키마가 바뀐다. 그 상태가 정확히 원 지적이 말한
     *   "확인하지 않은 스키마 위의 baseline" 이다.
     *
     * advisory lock 은 협조적이라 잠금을 무시하는 임의의 DDL 은 막지 못한다. 여기서 재는
     * 것은 **같은 잠금을 쓰는 마이그레이터끼리** 판정과 기록 사이가 열리지 않는다는 것이다 —
     * 그것이 이 잠금이 실제로 약속한 범위다.
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
                    // 가드가 잠금을 존중한다면 이 시간 내내 아무것도 읽지 못한다.
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

        // 확인하지 않은 스키마에 baseline 이 찍히지 않았다.
        assertThat(hasFlywayHistoryTable(database)).isFalse()
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

    /**
     * 가드와 **같은** advisory lock 을 잡고 [block] 을 돌린다.
     *
     * 키를 여기 따로 적지 않고 [FlywayBaselineGuard.MIGRATION_LOCK_KEY] 를 그대로 쓴다 —
     * 베껴 두면 키가 바뀌는 날 이 탐침은 다른 잠금을 잡고도 초록으로 남는다.
     */
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
