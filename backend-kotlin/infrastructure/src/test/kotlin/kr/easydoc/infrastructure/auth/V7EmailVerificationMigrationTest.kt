package kr.easydoc.infrastructure.auth

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `V7__email_verification.sql` 의 소급 인증(grandfather) — V7 적용 시점에 이미 있던 행은
 * `email_verified_at` 이 채워진 채로 넘어가고, 그 뒤에 새로 가입한 행은 채워지지 않는다.
 *
 * 다른 `Jdbc*RepositoryTest` 들과 달리 마이그레이션 **경계 자체**를 잰다 — V6 까지만 적용한
 * 뒤 행을 심고, V7 을 마저 적용해 그 행이 소급되는지 확인한다.
 */
class V7EmailVerificationMigrationTest {
    @Test
    @DisplayName("V7 적용 전에 있던 사용자는 email_verified_at 이 채워진 채로 넘어간다")
    fun `기존 행은 소급 인증된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v7_backfill")

        // 1) V6 까지만 적용한다 — email_verified_at 컬럼이 아직 없는 상태.
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .target("6")
            .load()
            .migrate()

        val preexistingUserId = UUID.randomUUID()
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO users (id, email, password_hash)
                    VALUES ('$preexistingUserId', 'preexisting@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA')
                    """.trimIndent(),
                )
            }
        }

        // 2) 나머지 마이그레이션(V7 포함)을 마저 적용한다.
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val verifiedAt =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT email_verified_at FROM users WHERE id = '$preexistingUserId'",
                        ).use { rs ->
                            check(rs.next()) { "심어 둔 행이 없다" }
                            rs.getTimestamp("email_verified_at")
                        }
                }
            }

        assertThat(verifiedAt)
            .withFailMessage("V7 적용 전에 있던 사용자가 소급 인증되지 않았다")
            .isNotNull()
    }

    @Test
    @DisplayName("V7 적용 후 새로 가입한 사용자는 email_verified_at 이 비어 있다")
    fun `새 가입은 소급되지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v7_no_backfill")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val newUserId = UUID.randomUUID()
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO users (id, email, password_hash)
                    VALUES ('$newUserId', 'fresh@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA')
                    """.trimIndent(),
                )
            }
        }

        val verifiedAt =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT email_verified_at FROM users WHERE id = '$newUserId'",
                        ).use { rs ->
                            check(rs.next()) { "심어 둔 행이 없다" }
                            rs.getTimestamp("email_verified_at")
                        }
                }
            }

        assertThat(verifiedAt)
            .withFailMessage("V7 적용 후 새 가입이 소급 인증돼 버렸다 — UPDATE 가 전체 행을 덮었다")
            .isNull()
    }
}
