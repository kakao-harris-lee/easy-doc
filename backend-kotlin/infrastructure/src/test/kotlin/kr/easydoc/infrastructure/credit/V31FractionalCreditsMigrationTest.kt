package kr.easydoc.infrastructure.credit

import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/** V31은 기존 숫자를 재표현만 하고, 모든 크레딧 저장 열을 정확한 0.1 단위로 확장한다. */
class V31FractionalCreditsMigrationTest {
    @Test
    @DisplayName("V31은 기존 잔액·예약·allowance·원장·변환·action-guide 예약량을 수치 그대로 보존한다")
    fun `기존 크레딧 숫자는 변환 뒤에도 보존된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v31_fractional_preservation")
        val seed = seedAtV30(database)

        migrate(database)

        val values =
            database.queryFirstColumn(
                """
                SELECT balance::text || ',' || reserved::text || ',' || allowance::text
                FROM workspace_credit_accounts WHERE workspace_id = '${seed.workspaceId}'
                UNION ALL
                SELECT balance_delta::text || ',' || reserved_delta::text
                FROM credit_transactions WHERE workspace_id = '${seed.workspaceId}'
                UNION ALL
                SELECT credits_reserved::text
                FROM conversions WHERE document_id = '${seed.documentId}'
                UNION ALL
                SELECT reserved_credits::text
                FROM action_guide_jobs WHERE document_id = '${seed.documentId}'
                """.trimIndent(),
            )

        assertThat(values).contains("42,5,50", "42,0", "0,3", "0,2", "3", "2")
        assertThat(columnType(database, "workspace_credit_accounts", "balance")).isEqualTo("numeric")
        assertThat(columnType(database, "workspace_credit_accounts", "reserved")).isEqualTo("numeric")
        assertThat(columnType(database, "workspace_credit_accounts", "allowance")).isEqualTo("numeric")
        assertThat(columnType(database, "credit_transactions", "balance_delta")).isEqualTo("numeric")
        assertThat(columnType(database, "credit_transactions", "reserved_delta")).isEqualTo("numeric")
        assertThat(columnType(database, "conversions", "credits_reserved")).isEqualTo("numeric")
        assertThat(columnType(database, "action_guide_jobs", "reserved_credits")).isEqualTo("numeric")
    }

    @Test
    @DisplayName("V31이 재정의한 action-guide 삭제 정산은 저장된 예약량을 그대로 release한다")
    fun `기존 action guide 예약은 문서 삭제 때 저장량으로 release된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v31_fractional_action_guide_release")
        val seed = seedAtV30(database)
        migrate(database)

        database.execute("DELETE FROM documents WHERE id = '${seed.documentId}'")

        assertThat(
            database
                .queryFirstColumn(
                    "SELECT reserved::text FROM workspace_credit_accounts WHERE workspace_id = '${seed.workspaceId}'",
                ).single(),
        ).isEqualTo("3")
        assertThat(
            database
                .queryFirstColumn(
                    "SELECT reserved_delta::text FROM credit_transactions " +
                        "WHERE action_guide_job_id = '${seed.actionGuideJobId}' AND kind = 'release'",
                ).single(),
        ).isEqualTo("-2")
        assertThat(
            database
                .queryFirstColumn(
                    "SELECT status || ',' || settlement FROM action_guide_jobs WHERE id = '${seed.actionGuideJobId}'",
                ).single(),
        ).isEqualTo("superseded,released")
    }

    @Test
    @DisplayName("V31은 DB 직접 쓰기의 0.01 크레딧을 반올림하지 않고 거절한다")
    fun `소수 둘째자리는 DB에서도 거절된다`() {
        val database = PostgresTestSupport.createEmptyDatabase("v31_fractional_check")
        val seed = seedAtV30(database)
        migrate(database)

        assertCheckViolation("ck_workspace_credit_accounts_balance_tenth") {
            database.execute(
                "UPDATE workspace_credit_accounts SET balance = 1.01 WHERE workspace_id = '${seed.workspaceId}'",
            )
        }
        assertCheckViolation("ck_conversions_credits_reserved_tenth") {
            database.execute(
                "UPDATE conversions SET credits_reserved = 1.01 WHERE document_id = '${seed.documentId}'",
            )
        }
        assertCheckViolation("ck_credit_transactions_balance_delta_tenth") {
            database.execute(
                "INSERT INTO credit_transactions " +
                    "(id, workspace_id, owner_user_id, kind, balance_delta, reserved_delta, reason) " +
                    "VALUES ('${UUID.randomUUID()}', '${seed.workspaceId}', '${seed.ownerId}', " +
                    "'grant', 1.01, 0, 'manual')",
            )
        }
        assertCheckViolation("ck_action_guide_jobs_reserved_credits_tenth") {
            database.execute(
                """
                INSERT INTO action_guide_jobs
                    (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                     expected_content_revision, based_on_content_revision, input_fingerprint,
                     status, settlement, reserved_credits)
                VALUES
                    ('${UUID.randomUUID()}', '${UUID.randomUUID()}', '${seed.ownerId}',
                     '${seed.workspaceId}', '${seed.documentId}', '${seed.conversionId}', 1, 1,
                     '0000000000000000000000000000000000000000000000000000000000000000',
                     'succeeded', 'consumed', 0.01)
                """.trimIndent(),
            )
        }
    }

    private fun assertCheckViolation(
        constraint: String,
        action: () -> Unit,
    ) {
        val thrown = catchThrowable(action)
        assertThat(thrown).isInstanceOf(SQLException::class.java)
        val sqlException = thrown as SQLException
        assertThat(sqlException.sqlState).isEqualTo("23514")
        assertThat(sqlException.message).contains(constraint)
    }

    private fun seedAtV30(database: DatabaseHandle): Seed {
        migrate(database, target = "30")
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        val actionGuideJobId = UUID.randomUUID()
        database.execute(
            """
            INSERT INTO users (id, email, password_hash)
            VALUES ('$ownerId', 'v31-$ownerId@example.test', '${'$'}argon2id${'$'}v=19${'$'}m=1,t=1,p=1${'$'}c2FsdA${'$'}aGFzaA');
            INSERT INTO workspaces (id, user_id, name)
            VALUES ('$workspaceId', '$ownerId', 'V31 대상 공간');
            INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved, allowance)
            VALUES ('$workspaceId', 42, 5, 50);
            INSERT INTO credit_transactions
                (id, workspace_id, owner_user_id, kind, balance_delta, reserved_delta, reason)
            VALUES ('${UUID.randomUUID()}', '$workspaceId', '$ownerId', 'grant', 42, 0, 'signup');
            INSERT INTO documents
                (id, user_id, workspace_id, title, source_format, source_text_encrypted,
                 char_count, encryption_scheme, key_version)
            VALUES ('$documentId', '$ownerId', '$workspaceId', 'V31', 'txt', decode('00', 'hex'),
                    100, 'aes256gcm-v1', 1);
            INSERT INTO conversions (id, document_id, status, encryption_scheme, key_version, credits_reserved)
            VALUES ('$conversionId', '$documentId', 'pending', 'aes256gcm-v1', 1, 3);
            INSERT INTO credit_transactions
                (id, workspace_id, owner_user_id, document_id, kind, balance_delta, reserved_delta, reason)
            VALUES ('${UUID.randomUUID()}', '$workspaceId', '$ownerId', '$documentId', 'reserve', 0, 3, 'conversion');
            INSERT INTO action_guide_jobs
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, based_on_content_revision, input_fingerprint,
                 status, settlement, reserved_credits)
            VALUES ('$actionGuideJobId', '${UUID.randomUUID()}', '$ownerId', '$workspaceId', '$documentId', '$conversionId',
                    1, 1, '0000000000000000000000000000000000000000000000000000000000000000',
                    'queued', 'reserved', 2);
            INSERT INTO credit_transactions
                (id, workspace_id, owner_user_id, document_id, kind, balance_delta, reserved_delta, reason,
                 action_guide_job_id)
            VALUES ('${UUID.randomUUID()}', '$workspaceId', '$ownerId', '$documentId', 'reserve', 0, 2, 'action_guide',
                    '$actionGuideJobId');
            """.trimIndent(),
        )
        return Seed(ownerId, workspaceId, documentId, conversionId, actionGuideJobId)
    }

    private fun migrate(
        database: DatabaseHandle,
        target: String? = null,
    ) {
        val config =
            Flyway
                .configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
        (if (target == null) config else config.target(target)).load().migrate()
    }

    private fun columnType(
        database: DatabaseHandle,
        table: String,
        column: String,
    ): String =
        database
            .queryFirstColumn(
                "SELECT data_type FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = '$table' AND column_name = '$column'",
            ).single()

    private data class Seed(
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
        val actionGuideJobId: UUID,
    )
}
