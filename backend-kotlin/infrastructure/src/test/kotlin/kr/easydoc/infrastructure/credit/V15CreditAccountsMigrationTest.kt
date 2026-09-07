package kr.easydoc.infrastructure.credit

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `V15__credit_accounts.sql` 의 backfill — V15 적용 시점에 이미 있던 워크스페이스는
 * 0 잔액 계정 행을 받는다(계획 §2 결정 2). `V7EmailVerificationMigrationTest` 와 같은
 * 형태로 마이그레이션 경계 자체를 잰다 — V14 까지만 적용한 뒤 행을 심고, V15 를 마저
 * 적용해 backfill 이 일어나는지 확인한다.
 */
class V15CreditAccountsMigrationTest {
    @Test
    @DisplayName("V15 적용 전에 있던 워크스페이스는 0 잔액 계정 행을 받는다")
    fun `기존 워크스페이스는 backfill 된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v15_backfill")

        // 1) V14 까지만 적용한다 — workspace_credit_accounts 가 아직 없는 상태.
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .target("14")
            .load()
            .migrate()

        val userId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO users (id, email, password_hash)
                    VALUES ('$userId', 'v15-backfill@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO workspaces (id, user_id, name)
                    VALUES ('$workspaceId', '$userId', '기존 워크스페이스')
                    """.trimIndent(),
                )
            }
        }

        // 2) 나머지 마이그레이션(V15 포함)을 마저 적용한다.
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val account =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT balance, reserved FROM workspace_credit_accounts " +
                                "WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            check(rs.next()) { "backfill 된 계정 행이 없다" }
                            rs.getInt("balance") to rs.getInt("reserved")
                        }
                }
            }

        assertThat(account).isEqualTo(0 to 0)
    }

    @Test
    @DisplayName("V15 적용 후 새로 만든 워크스페이스는 backfill 대상이 아니다 — 애플리케이션이 ensureAccount 로 만든다")
    fun `새 워크스페이스는 backfill 되지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v15_no_backfill")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val userId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO users (id, email, password_hash)
                    VALUES ('$userId', 'v15-no-backfill@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO workspaces (id, user_id, name)
                    VALUES ('$workspaceId', '$userId', '새 워크스페이스')
                    """.trimIndent(),
                )
            }
        }

        val exists =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*) FROM workspace_credit_accounts WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            rs.next()
                            rs.getInt(1) > 0
                        }
                }
            }

        assertThat(exists)
            .withFailMessage("마이그레이션 자체가 새 워크스페이스에도 계정 행을 만들었다 — backfill 은 일회성이어야 한다")
            .isFalse()
    }
}
