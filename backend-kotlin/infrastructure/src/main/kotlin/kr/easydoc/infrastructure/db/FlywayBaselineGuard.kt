package kr.easydoc.infrastructure.db

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.sql.Connection

/**
 * Flyway 기동 전략. 계획 §4.2-4의 "schema checksum이 일치할 때만 baseline version 1을
 * 기록한다"를 코드로 옮긴 것이다.
 */
@Configuration(proxyBeanMethods = false)
class FlywayBaselineGuard {
    private val logger = LoggerFactory.getLogger(FlywayBaselineGuard::class.java)

    @Bean
    fun flywayMigrationStrategy(): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            // **판정과 baseline 은 같은 연결·같은 잠금 아래에서 한다** (2회차 codex #3·#4).
            //
            // 앞선 판은 ⑴ 이력 확인 ⑵ 테이블 수 ⑶ 지문 대조 ⑷ baseline ⑸ migrate 가
            // **각각 다른 연결**이었고 어떤 잠금에도 덮이지 않았다. 그래서 두 종류가 열려
            // 있었다.
            //
            //   - **TOCTOU**: 지문을 읽은 뒤 baseline 을 기록하기 전에 스키마가 바뀌면,
            //     **확인하지 않은 스키마에 baseline 이 찍힌다.** 그 뒤 V2 부터 적용되므로
            //     기준선과 다른 스키마 위에 마이그레이션이 쌓인다.
            //   - **동시 기동**: api·worker·migrate 프로필이 함께 뜨면 셋이 같은 판정을
            //     동시에 하고 각자 baseline 을 시도한다.
            //
            // 세션 advisory lock 하나로 둘 다 닫는다. 잠금을 **잡은 연결을 열어 둔 채**
            // baseline·migrate 를 부르므로, 그 사이에 다른 인스턴스가 끼어들 수 없다.
            // Flyway 자신의 이력 테이블 잠금과는 층이 다르다 — 그쪽은 이력 쓰기를 지키고
            // 이쪽은 **"읽고 판정한 것과 기록하는 것이 같은 스키마"** 를 지킨다.
            flyway.configuration.dataSource.connection.use { connection ->
                withAdvisoryLock(connection) {
                    if (needsPythonBaseline(flyway, connection)) {
                        verifyMatchesPythonBaseline(connection)
                        verifyAlembicHead(connection)
                        logger.info(
                            "기존 Python 스키마를 확인했다. Flyway baseline version=1 을 기록하고 V2부터 적용한다.",
                        )
                        flyway.baseline()
                    }
                    val result = flyway.migrate()
                    // 로그에 남기는 것은 개수와 버전뿐이다 — 본문·개인정보가 실릴 자리가 아니다.
                    // privacy-allow: LOG-BODY @9b55d330 — 적용 개수와 스키마 버전만 보간한다.
                    logger.info(
                        "Flyway 마이그레이션 완료: applied={} targetSchemaVersion={}",
                        result.migrationsExecuted,
                        result.targetSchemaVersion ?: "(변경 없음)",
                    )
                }
            }
        }

    /** 세션 advisory lock 을 잡고 [block] 을 돌린다. **잠금은 이 연결이 살아 있는 동안 유지된다.** */
    private fun <T> withAdvisoryLock(
        connection: Connection,
        block: () -> T,
    ): T {
        connection.prepareStatement("SELECT pg_advisory_lock(?)").use { statement ->
            statement.setLong(1, MIGRATION_LOCK_KEY)
            statement.executeQuery().use { it.next() }
        }
        try {
            return block()
        } finally {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                statement.setLong(1, MIGRATION_LOCK_KEY)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    /** Flyway 이력이 없는데 애플리케이션 테이블은 이미 있는 상태인가. */
    private fun needsPythonBaseline(
        flyway: Flyway,
        connection: Connection,
    ): Boolean {
        val hasFlywayHistory = flyway.info().all().any { it.installedOn != null }
        if (hasFlywayHistory) return false
        return SchemaFingerprint.userTableCount(connection) > 0
    }

    /** 지문이 기준선과 다르면 기동을 실패시킨다. */
    private fun verifyMatchesPythonBaseline(connection: Connection) {
        val expected = SchemaFingerprint.expectedPythonBaseline()
        val actual = SchemaFingerprint.of(connection)
        if (expected == actual) return

        throw IllegalStateException(
            buildString {
                appendLine("기존 스키마가 Python 기준선과 다르다. baseline 을 기록하지 않고 기동을 중단한다.")
                appendLine("(계획 §4.2-4: schema checksum이 일치할 때만 baseline version 1을 기록한다.)")
                appendLine()
                append(SchemaFingerprint.describeDifference(expected, actual))
                appendLine()
                appendLine("확인할 것:")
                appendLine("  - 대상 DB가 Alembic 0006까지 올라와 있는가")
                appendLine("  - 손으로 만든 테이블이 public 스키마에 섞여 있지 않은가")
                appendLine("  - Alembic 리비전이 0006 이후로 늘었다면 V1 과 기준선 지문을 함께 갱신했는가")
            },
        )
    }

    /** Alembic head 가 기준선이 가정한 리비전인지 확인한다. */
    private fun verifyAlembicHead(connection: Connection) {
        // **존재를 먼저 따로 묻는다.** `WHERE to_regclass(...) IS NOT NULL` 로 한 문장에
        // 담으면 안 된다 — PostgreSQL 은 계획 단계에서 relation 을 해석하므로 조건이
        // 걸러 주기 전에 `relation "alembic_version" does not exist` 로 죽는다(실측).
        val exists =
            connection.prepareStatement(ALEMBIC_TABLE_EXISTS_SQL).use { statement ->
                statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        if (!exists) return
        val actual =
            connection.prepareStatement(ALEMBIC_HEAD_SQL).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        if (actual.isEmpty() || actual == listOf(EXPECTED_ALEMBIC_HEAD)) return

        throw IllegalStateException(
            buildString {
                appendLine("Alembic 리비전이 기준선의 가정과 다르다. baseline 을 기록하지 않고 기동을 중단한다.")
                appendLine("  기대: $EXPECTED_ALEMBIC_HEAD / 실제: ${actual.sorted().joinToString(", ")}")
                appendLine()
                appendLine("지문(스키마 모양)은 같은데 리비전이 다르면, 값만 바꾸는 리비전이 적용됐다는 뜻이다.")
                appendLine("V1 기준선이 그 상태를 가정하지 않았으므로 그대로 baseline 을 찍으면 안 된다.")
                appendLine("확인할 것:")
                appendLine("  - 리비전을 올렸다면 V1 과 기준선 지문·이 상수를 함께 갱신했는가")
                appendLine("  - 여러 head 가 있다면 Alembic 분기를 먼저 병합했는가")
            },
        )
    }

    /**
     * `private` 이 아니라 `internal` 인 이유는 하나뿐이다 — **테스트가 같은 잠금을 잡아야
     * 한다.** TOCTOU 탐침(`FlywayBaselineGuardTest`)은 잠금을 존중하는 경쟁 액터를 흉내 내
     * 판정 직전에 스키마를 바꾸려 든다. 그 액터가 키를 따로 적어 두면 키가 바뀌는 날
     * 탐침은 아무것도 막지 않으면서 초록으로 남는다.
     */
    internal companion object {
        /** advisory lock 키. 이 저장소가 이 목적으로만 쓰는 임의의 상수다. */
        const val MIGRATION_LOCK_KEY = 4_820_115_001L

        /** 이 기준선이 가정하는 Alembic head. `migrations/versions/0006_workspaces.py` 의 `revision`. */
        const val EXPECTED_ALEMBIC_HEAD = "0006"

        /** 테이블 존재 확인. 없는 DB(순수 Kotlin)에서 조회가 죽지 않게 먼저 묻는다. */
        const val ALEMBIC_TABLE_EXISTS_SQL =
            "SELECT to_regclass('public.alembic_version') IS NOT NULL"

        /** `alembic_version` 을 **읽기만** 한다. 쓰지 않는다(계획 §4.2-7). */
        const val ALEMBIC_HEAD_SQL = "SELECT version_num FROM alembic_version"
    }
}
