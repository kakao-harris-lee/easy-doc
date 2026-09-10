package kr.easydoc.infrastructure.credit

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * `V21__credit_cycle_allowance.sql` — 크레딧을 「구독 주기에 포함된 이용량」으로 바꾼
 * 사용자 결정(2026-09-10). `V15CreditAccountsMigrationTest`와 같은 형태로 마이그레이션
 * 경계 자체를 잰다 — V20 까지만 적용한 뒤 행을 심고, V21 을 마저 적용해 backfill 이
 * 일어나는지, 그리고 `credit_transactions.kind` CHECK 가 새 값을 받는지 확인한다.
 */
class V21CreditCycleAllowanceMigrationTest {
    @Test
    @DisplayName("V21 적용 전에 있던 계정은 allowance = balance, cycle_ends_at = null 로 backfill 된다 — 주기 없음")
    fun `기존 계정은 주기 없음으로 backfill 된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v21_backfill")
        val (userId, workspaceId) = seedWorkspaceAt(database, target = "20")
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved) " +
                        "VALUES ('$workspaceId', 42, 5)",
                )
            }
        }

        migrateAll(database)

        val row =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT allowance, cycle_ends_at, cycle_started_at IS NOT NULL AS has_started, " +
                                "cycle_renews FROM workspace_credit_accounts WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            check(rs.next()) { "backfill 된 계정 행이 없다" }
                            listOf(
                                rs.getInt("allowance"),
                                rs.getObject("cycle_ends_at"),
                                rs.getBoolean("has_started"),
                                rs.getBoolean("cycle_renews"),
                            )
                        }
                }
            }

        assertThat(row[0]).isEqualTo(42)
        assertThat(row[1]).isNull()
        assertThat(row[2]).isEqualTo(true)
        // cycle_renews 도 기본값 false 로 backfill 된다 — 주기가 없으니 의미가 없지만, 열
        // 자체는 DEFAULT false 로 채워진다.
        assertThat(row[3]).isEqualTo(false)
        assertThat(userId).isNotNull()
    }

    @Test
    @DisplayName(
        "음수 잔액 계정(집행 꺼짐에서 초과 사용)이 있어도 V21 이 성공하고 그 계정의 allowance 는 0 이다 " +
            "— GREATEST(balance, 0), HIGH 리뷰 지적",
    )
    fun `음수 잔액 계정은 마이그레이션을 깨지 않고 allowance 0 으로 backfill 된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v21_negative_balance_backfill")
        val (_, workspaceId) = seedWorkspaceAt(database, target = "20")
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                // 집행 스위치(easydoc.credits.enforced)가 꺼진 상태에서 초과 사용하면
                // balance 가 음수로 기록될 수 있다(V15 머리주석) — 지금 파일럿이 실제로
                // 이 상태다. V21 이전에는 balance 에 CHECK 가 없어 이런 행이 실제로 있을
                // 수 있다.
                statement.executeUpdate(
                    "INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved) " +
                        "VALUES ('$workspaceId', -7, 0)",
                )
            }
        }

        // V21 마이그레이션 자체가 실패 없이 끝나야 한다 — `allowance = balance`(GREATEST
        // 없이)였다면 새 CHECK(allowance >= 0)에 이 UPDATE 문장이 걸려 마이그레이션
        // 전체가 예외로 죽었을 것이다.
        migrateAll(database)

        val row =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT balance, allowance, cycle_ends_at FROM workspace_credit_accounts " +
                                "WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            check(rs.next()) { "backfill 된 계정 행이 없다" }
                            Triple(rs.getInt("balance"), rs.getInt("allowance"), rs.getObject("cycle_ends_at"))
                        }
                }
            }

        // balance 자체은 그대로 음수로 남는다 — 청구 근거이므로 건드리지 않는다.
        assertThat(row.first).isEqualTo(-7)
        // allowance 는 0 이다 — 음수가 아니라 GREATEST(balance, 0).
        assertThat(row.second).isZero()
        // 주기가 없으므로(cycle_ends_at null) 이 allowance 값은 애초에 쓰이지 않는다.
        assertThat(row.third).isNull()
    }

    @Test
    @DisplayName("V21 이후 새로 만든 계정(0 잔액)은 allowance 기본값 0, cycle_ends_at null 이다")
    fun `새 계정은 기본값을 그대로 받는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v21_new_account_defaults")
        val (_, workspaceId) = seedWorkspaceAt(database, target = null)
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved) " +
                        "VALUES ('$workspaceId', 0, 0)",
                )
            }
        }

        val row =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT allowance, cycle_ends_at FROM workspace_credit_accounts " +
                                "WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            check(rs.next())
                            rs.getInt("allowance") to rs.getObject("cycle_ends_at")
                        }
                }
            }

        assertThat(row.first).isZero()
        assertThat(row.second).isNull()
    }

    @Test
    @DisplayName("credit_transactions.kind CHECK 는 cycle_set·cycle_reset 을 받고 다른 값은 거절한다")
    fun `kind CHECK 는 새 값을 받는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v21_kind_check")
        val (userId, workspaceId) = seedWorkspaceAt(database, target = null)
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved) " +
                        "VALUES ('$workspaceId', 0, 0)",
                )
            }
        }

        insertTransaction(database, workspaceId, userId, kind = "cycle_set")
        insertTransaction(database, workspaceId, userId, kind = "cycle_reset")

        val count =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*) FROM credit_transactions WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                }
            }
        assertThat(count).isEqualTo(2)

        assertThatThrownBy { insertTransaction(database, workspaceId, userId, kind = "not_a_real_kind") }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("credit_transactions.reason CHECK 는 cycle_end 를 받고 다른 값은 거절한다")
    fun `reason CHECK 는 새 값을 받는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v21_reason_check")
        val (userId, workspaceId) = seedWorkspaceAt(database, target = null)
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved) " +
                        "VALUES ('$workspaceId', 0, 0)",
                )
            }
        }

        insertTransaction(database, workspaceId, userId, kind = "cycle_reset", reason = "cycle_end")

        val count =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*) FROM credit_transactions WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                }
            }
        assertThat(count).isEqualTo(1)

        assertThatThrownBy {
            insertTransaction(database, workspaceId, userId, kind = "cycle_reset", reason = "not_a_real_reason")
        }.isInstanceOf(SQLException::class.java)
    }

    private fun insertTransaction(
        database: DatabaseHandle,
        workspaceId: UUID,
        ownerId: UUID,
        kind: String,
        reason: String = "manual",
    ) {
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO credit_transactions " +
                        "(id, workspace_id, owner_user_id, kind, balance_delta, reserved_delta, reason) " +
                        "VALUES ('${UUID.randomUUID()}', '$workspaceId', '$ownerId', '$kind', 10, 0, '$reason')",
                )
            }
        }
    }

    /** V20 까지, 또는(=null) 전체 마이그레이션을 적용한 뒤 사용자·워크스페이스를 심는다. */
    private fun seedWorkspaceAt(
        database: DatabaseHandle,
        target: String?,
    ): Pair<UUID, UUID> {
        val flyway =
            Flyway
                .configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
        (if (target != null) flyway.target(target) else flyway).load().migrate()

        val userId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO users (id, email, password_hash)
                    VALUES ('$userId', 'v21-$workspaceId@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO workspaces (id, user_id, name)
                    VALUES ('$workspaceId', '$userId', 'V21 대상 공간')
                    """.trimIndent(),
                )
            }
        }
        return userId to workspaceId
    }

    private fun migrateAll(database: DatabaseHandle) {
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
