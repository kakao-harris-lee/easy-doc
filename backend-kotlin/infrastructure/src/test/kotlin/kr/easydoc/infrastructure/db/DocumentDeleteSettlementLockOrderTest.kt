package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **문서 삭제와 worker 정산이 잠금을 같은 순서로 잡는가.**
 *
 * 한 문서에는 행동 안내 작업과 그림 제안 작업이 **동시에** 활성일 수 있다. 문서 삭제의 BEFORE
 * DELETE 정산이 한 가족의 작업을 잠그고 이용량 계정을 잡은 **뒤에** 다른 가족의 작업을 잠그면,
 * 그 다른 가족을 정산 중인 worker(작업 행 → 계정 순서)와 사이클이 닫힌다. 그때 PostgreSQL 은
 * 둘 중 하나를 죽이고 — 문서 삭제(또는 그 삭제를 묶은 보존 만료 파기 배치 전체)가 통째로
 * 되돌려지거나, 이미 돈을 쓴 정산이 사라진다.
 *
 * 그래서 삭제 정산은 **두 가족의 활성 작업 행을 먼저, 정해진 순서로 모두 잠근 뒤에야** 계정을
 * 건드린다(V35 의 단일 조정 트리거).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentDeleteSettlementLockOrderTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepare() {
        val database = PostgresTestSupport.createEmptyDatabase("document_delete_lock_order")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
    }

    @Test
    @DisplayName("그림 제안 정산 중에 문서를 지워도 교착하지 않는다")
    fun `문서 삭제가 그림 제안 정산과 교착하지 않는다`() {
        val seeded = seed()

        deleteRacingWith(seeded, SUGGESTION_JOBS, seeded.suggestionJobId)

        assertThat(jobState(SUGGESTION_JOBS, seeded.suggestionJobId)).isEqualTo("superseded|released")
        assertThat(jobState(ACTION_GUIDE_JOBS, seeded.actionGuideJobId)).isEqualTo("superseded|released")
        assertThat(documentCount(seeded.documentId)).isZero()
    }

    @Test
    @DisplayName("행동 안내 정산 중에 문서를 지워도 교착하지 않는다 — 반대 순서도 같은 규칙이다")
    fun `문서 삭제가 행동 안내 정산과 교착하지 않는다`() {
        val seeded = seed()

        deleteRacingWith(seeded, ACTION_GUIDE_JOBS, seeded.actionGuideJobId)

        assertThat(jobState(SUGGESTION_JOBS, seeded.suggestionJobId)).isEqualTo("superseded|released")
        assertThat(jobState(ACTION_GUIDE_JOBS, seeded.actionGuideJobId)).isEqualTo("superseded|released")
        assertThat(documentCount(seeded.documentId)).isZero()
    }

    /**
     * worker 정산과 같은 순서(작업 행 → 이용량 계정)로 잠그는 트랜잭션을 띄워 놓고 그 사이에
     * 문서를 지운다. 삭제 쪽이 계정을 먼저 잡으면 여기서 `deadlock detected` 로 끊긴다.
     */
    private fun deleteRacingWith(
        seeded: Seeded,
        table: String,
        jobId: UUID,
    ) {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val locked = CountDownLatch(1)
            val settlement =
                pool.submit {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        // worker 가 `lockIfHeld` 로 잡는 그 행이다.
                        execute(connection, "SELECT id FROM $table WHERE id = '$jobId' FOR UPDATE")
                        locked.countDown()
                        // 삭제가 계정을 먼저 잡을 시간을 준다 — 그 순서라면 아래에서 교착한다.
                        Thread.sleep(BLOCKED_MILLIS)
                        // 정산이 계정 행을 잡는 자리 그대로다. 금액은 건드리지 않는다 — 이 시험이
                        // 재는 것은 잠금 순서이지 정산 산술이 아니다(그쪽은 흐름 시험의 몫).
                        execute(
                            connection,
                            "UPDATE workspace_credit_accounts SET updated_at = now() " +
                                "WHERE workspace_id = '${seeded.workspaceId}'",
                        )
                        connection.commit()
                    }
                }
            check(locked.await(HANDOFF_SECONDS, TimeUnit.SECONDS)) { "정산 트랜잭션이 작업 행을 잠그지 못했다" }

            dataSource.connection.use { connection ->
                execute(connection, "DELETE FROM documents WHERE id = '${seeded.documentId}'")
            }
            settlement.get(HANDOFF_SECONDS, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun seed(): Seeded {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        val actionGuideJobId = UUID.randomUUID()
        val suggestionJobId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            execute(
                connection,
                "INSERT INTO users (id,email,password_hash) " +
                    "VALUES ('$ownerId','lock-$ownerId@example.test','$DUMMY_PHC')",
            )
            execute(connection, "INSERT INTO workspaces (id,user_id,name) VALUES ('$workspaceId','$ownerId','공간')")
            execute(
                connection,
                "INSERT INTO workspace_credit_accounts (workspace_id,balance,reserved) VALUES ('$workspaceId',10,2)",
            )
            execute(
                connection,
                """
                INSERT INTO documents
                    (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                     encryption_scheme,key_version,char_count)
                VALUES ('$documentId','$ownerId','$workspaceId','제목','txt','\x01'::bytea,'aes256gcm-v1',1,10)
                """.trimIndent(),
            )
            execute(
                connection,
                """
                INSERT INTO conversions
                    (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
                VALUES ('$conversionId','$documentId','done','\x02'::bytea,'aes256gcm-v1',1,1)
                """.trimIndent(),
            )
            insertJob(connection, ACTION_GUIDE_JOBS, actionGuideJobId, ownerId, workspaceId, documentId, conversionId)
            insertJob(connection, SUGGESTION_JOBS, suggestionJobId, ownerId, workspaceId, documentId, conversionId)
        }
        return Seeded(ownerId, workspaceId, documentId, actionGuideJobId, suggestionJobId)
    }

    /** 활성(queued) 작업 한 건과 그 예약 원장 한 행 — 운영이 만드는 모양 그대로다. */
    @Suppress("LongParameterList")
    private fun insertJob(
        connection: Connection,
        table: String,
        jobId: UUID,
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
    ) {
        val reasonColumn = if (table == ACTION_GUIDE_JOBS) "action_guide_job_id" else "illustration_suggestion_job_id"
        val reason = if (table == ACTION_GUIDE_JOBS) "action_guide" else "illustration_suggestion"
        execute(
            connection,
            """
            INSERT INTO $table
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, based_on_content_revision, input_fingerprint,
                 status, settlement, reserved_credits)
            VALUES ('$jobId', gen_random_uuid(), '$ownerId', '$workspaceId', '$documentId', '$conversionId',
                    1, 1, '$FINGERPRINT', 'queued', 'reserved', 1.0)
            """.trimIndent(),
        )
        execute(
            connection,
            """
            INSERT INTO credit_transactions
                (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                 reserved_delta, reason, $reasonColumn)
            VALUES (gen_random_uuid(), '$workspaceId', '$ownerId', '$documentId', 'reserve', 0, 1.0,
                    '$reason', '$jobId')
            """.trimIndent(),
        )
    }

    private fun jobState(
        table: String,
        jobId: UUID,
    ): String = queryOne("SELECT status || '|' || settlement FROM $table WHERE id = '$jobId'")

    private fun documentCount(documentId: UUID): Int =
        queryOne("SELECT count(*) FROM documents WHERE id = '$documentId'").toInt()

    private fun queryOne(sql: String): String =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next()) { "행이 없다: $sql" }
                    rows.getString(1)
                }
            }
        }

    private fun execute(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    private class Seeded(
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val actionGuideJobId: UUID,
        val suggestionJobId: UUID,
    )

    private companion object {
        const val ACTION_GUIDE_JOBS = "action_guide_jobs"
        const val SUGGESTION_JOBS = "illustration_suggestion_jobs"
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** 두 작업 표의 `…_fingerprint_length` 가 정확히 64자를 요구한다. */
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** 삭제가 계정을 먼저 잡을 만큼은 길고, 시험을 늘어뜨리지 않을 만큼은 짧은 창. */
        const val BLOCKED_MILLIS = 500L
        const val HANDOFF_SECONDS = 30L
    }
}
