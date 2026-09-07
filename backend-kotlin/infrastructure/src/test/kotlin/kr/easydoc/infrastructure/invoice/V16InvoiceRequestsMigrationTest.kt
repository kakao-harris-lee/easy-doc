package kr.easydoc.infrastructure.invoice

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * `V16__invoice_requests.sql` 의 제약 — 부분 유니크 색인(같은 워크스페이스·같은 기간의
 * `requested` 중복 방지)과 상태 CHECK. `V15CreditAccountsMigrationTest` 와 같은 형태로
 * 마이그레이션 경계 자체를 잰다.
 */
class V16InvoiceRequestsMigrationTest {
    @Test
    @DisplayName("같은 워크스페이스·같은 기간의 requested 두 번째 삽입은 유니크 위반이다")
    fun `부분 유니크 색인이 중복 requested 를 막는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v16_unique_open_period")
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
                    VALUES ('$userId', 'v16-unique@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO workspaces (id, user_id, name) VALUES ('$workspaceId', '$userId', '기존 워크스페이스')",
                )
                statement.executeUpdate(insertRequestSql(workspaceId, userId, "requested"))
            }
        }

        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                assertThatThrownBy { statement.executeUpdate(insertRequestSql(workspaceId, userId, "requested")) }
                    .isInstanceOf(SQLException::class.java)
            }
        }
    }

    @Test
    @DisplayName("issued로 처리된 뒤에는 같은 기간을 다시 requested로 넣을 수 있다")
    fun `처리된 요청은 유니크 대상이 아니다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v16_unique_after_issued")
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
                    VALUES ('$userId', 'v16-reissue@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO workspaces (id, user_id, name) VALUES ('$workspaceId', '$userId', '기존 워크스페이스')",
                )
                statement.executeUpdate(insertRequestSql(workspaceId, userId, "issued"))
                // 두 번째는 예외 없이 들어가야 한다.
                statement.executeUpdate(insertRequestSql(workspaceId, userId, "requested"))
            }
        }

        val count =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*) FROM invoice_requests WHERE workspace_id = '$workspaceId'",
                        ).use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                }
            }
        assertThat(count).isEqualTo(2)
    }

    private fun insertRequestSql(
        workspaceId: UUID,
        ownerId: UUID,
        status: String,
    ): String =
        """
        INSERT INTO invoice_requests (
            id, workspace_id, owner_user_id, business_number, company_name, contact_email,
            period_from, period_to, status
        ) VALUES (
            '${UUID.randomUUID()}', '$workspaceId', '$ownerId', '2208162517', '쉬운글 주식회사',
            'req@example.test', '2026-08-01', '2026-08-31', '$status'
        )
        """.trimIndent()
}
