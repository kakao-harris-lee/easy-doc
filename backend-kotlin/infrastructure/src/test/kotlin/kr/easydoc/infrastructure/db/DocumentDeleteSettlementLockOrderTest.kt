package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.JdbcAccountDeletionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/**
 * **문서 삭제와 worker 정산이 잠금을 같은 순서로 잡는가.**
 *
 * worker 는 언제나 「작업 행 → 이용량 계정」 순서로 잡는다(`ProcessActionGuideJob`·
 * `ProcessIllustrationSuggestionJob`). 삭제가 계정을 **먼저** 쥔 채 아직 잠그지 않은 작업 행을
 * 기다리면 사이클이 닫히고, PostgreSQL 은 둘 중 하나를 죽인다 — 삭제(또는 그 삭제를 묶은 탈퇴·
 * 보존 만료 파기 배치 전체)가 통째로 되돌려지거나, 이미 돈을 쓴 정산이 사라진다.
 *
 * 그 사이클은 두 자리에서 열린다.
 *
 * ⑴ **문서 한 건** — 한 문서에 두 가족의 작업이 동시에 활성일 수 있다. 가족마다 BEFORE DELETE
 *    트리거를 두면 이름 순서 때문에 한 가족을 정산한(=계정을 잡은) 뒤에야 다음 가족을 잠근다.
 *    V35 의 단일 조정 트리거가 두 가족의 행을 먼저 모두 잠가 막는다.
 * ⑵ **문서 여러 건** — 트리거는 행 단위라 `OLD.id` 의 작업만 미리 잠근다. 탈퇴·파기처럼 한
 *    문장이 여러 문서를 지우면 첫 문서를 정산하며 계정을 쥔 채 둘째 문서의 작업을 기다린다.
 *    삭제 **전에** [DocumentJobLocks] 가 배치 전체의 활성 작업을 정해진 순서로 잠가 막는다.
 *
 * 두 경우 모두 「삭제가 작업 행 앞에서 멈춰 서 있는 동안 worker 가 계정을 잡을 수 있는가」로
 * 잰다 — 잡히면 순서가 같은 것이고, 교착하면 삭제가 계정을 먼저 쥔 것이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentDeleteSettlementLockOrderTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var tx: TransactionTemplate
    private lateinit var accountDeletion: JdbcAccountDeletionRepository
    private lateinit var pool: ExecutorService

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
        jdbc = JdbcClient.create(dataSource)
        tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
        accountDeletion = JdbcAccountDeletionRepository(jdbc)
        pool = Executors.newSingleThreadExecutor()
    }

    @AfterEach
    fun shutdown() {
        pool.shutdownNow()
    }

    @Test
    @DisplayName("문서 한 건 삭제가 그림 제안 정산과 교착하지 않는다")
    fun `문서 한 건 삭제가 그림 제안 정산과 교착하지 않는다`() {
        val seeded = seedOneDocument()

        raceDeletionAgainstHolderOf(seeded, SUGGESTION_JOBS) { deleteDocument(seeded.documentIds.single()) }

        assertSettledOnce(seeded)
    }

    @Test
    @DisplayName("문서 한 건 삭제가 행동 안내 정산과 교착하지 않는다 — 반대 순서도 같은 규칙이다")
    fun `문서 한 건 삭제가 행동 안내 정산과 교착하지 않는다`() {
        val seeded = seedOneDocument()

        raceDeletionAgainstHolderOf(seeded, ACTION_GUIDE_JOBS) { deleteDocument(seeded.documentIds.single()) }

        assertSettledOnce(seeded)
    }

    @Test
    @DisplayName("탈퇴의 여러 문서 삭제가 그림 제안 정산과 교착하지 않는다")
    fun `탈퇴의 여러 문서 삭제가 그림 제안 정산과 교착하지 않는다`() {
        // 먼저 훑는 문서는 행동 안내, 나중 문서는 그림 제안 — 배치 잠금이 없으면 앞 문서를
        // 정산하며 계정을 쥔 채 뒤 문서의 제안 작업을 기다린다.
        val seeded = seedTwoDocuments(first = ACTION_GUIDE_JOBS, second = SUGGESTION_JOBS)

        raceDeletionAgainstHolderOf(seeded, SUGGESTION_JOBS) { deleteAccount(seeded.ownerId) }

        assertSettledAfterAccountDeletion(seeded)
    }

    @Test
    @DisplayName("탈퇴의 여러 문서 삭제가 행동 안내 정산과 교착하지 않는다 — 반대 순서도 같은 규칙이다")
    fun `탈퇴의 여러 문서 삭제가 행동 안내 정산과 교착하지 않는다`() {
        val seeded = seedTwoDocuments(first = SUGGESTION_JOBS, second = ACTION_GUIDE_JOBS)

        raceDeletionAgainstHolderOf(seeded, ACTION_GUIDE_JOBS) { deleteAccount(seeded.ownerId) }

        assertSettledAfterAccountDeletion(seeded)
    }

    /**
     * worker 정산과 같은 순서(작업 행 → 이용량 계정)로 잠그는 트랜잭션을 띄워 놓고 그 사이에
     * [delete] 를 돌린다. 삭제가 작업 행 앞에서 **실제로 멈춰 서 있는 것**을 본 뒤에야 worker 가
     * 계정을 잡는다 — 시간 맞추기가 아니라 관찰된 대기다.
     */
    private fun raceDeletionAgainstHolderOf(
        seeded: Seeded,
        heldTable: String,
        delete: () -> Unit,
    ) {
        val heldJobId = seeded.jobIds.getValue(heldTable)
        dataSource.connection.use { worker ->
            worker.autoCommit = false
            // worker 가 `lockIfHeld` 로 잡는 그 행이다.
            execute(worker, "SELECT id FROM $heldTable WHERE id = '$heldJobId' FOR UPDATE")
            val deletion = pool.submit { delete() }

            assertThatThrownBy { deletion.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS) }
                .describedAs("삭제가 worker 가 쥔 작업 행을 기다리지 않았다 — 경합이 성립하지 않았다")
                .isInstanceOf(TimeoutException::class.java)

            // 정산이 계정 행을 잡는 자리 그대로다. 금액은 건드리지 않는다 — 이 시험이 재는 것은
            // 잠금 순서이지 정산 산술이 아니다(그쪽은 흐름 시험의 몫). 삭제가 계정을 먼저 쥐고
            // 있으면 여기서 `deadlock detected` 로 끊긴다.
            execute(
                worker,
                "UPDATE workspace_credit_accounts SET updated_at = now() " +
                    "WHERE workspace_id = '${seeded.workspaceId}'",
            )
            worker.commit()
            deletion.get(HANDOFF_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun deleteDocument(documentId: UUID) {
        execute("DELETE FROM documents WHERE id = '$documentId'")
    }

    /** 탈퇴 경로 그대로다 — 한 트랜잭션 안에서 문서를 먼저 지우고 사용자를 지운다. */
    private fun deleteAccount(ownerId: UUID) {
        tx.executeWithoutResult { accountDeletion.deleteUser(ownerId) }
    }

    /** 문서 한 건 삭제 — 원장이 남아 있어 해제가 **정확히 한 번**인지까지 본다. */
    private fun assertSettledOnce(seeded: Seeded) {
        seeded.jobIds.forEach { (table, jobId) ->
            assertThat(jobState(table, jobId)).isEqualTo("superseded|released")
            assertThat(releaseCount(table, jobId))
                .describedAs("$table 해제 거래 수")
                .isEqualTo(1)
        }
        assertThat(documentCount(seeded.ownerId)).isZero()
    }

    /** 탈퇴는 원장을 사용자와 함께 지운다(`owner_user_id` CASCADE) — 작업 행의 정산만 남는다. */
    private fun assertSettledAfterAccountDeletion(seeded: Seeded) {
        seeded.jobIds.forEach { (table, jobId) ->
            assertThat(jobState(table, jobId)).isEqualTo("superseded|released")
        }
        assertThat(documentCount(seeded.ownerId)).isZero()
        assertThat(queryStrings("SELECT count(*) FROM users WHERE id = '${seeded.ownerId}'").single())
            .isEqualTo("0")
    }

    /** 한 문서에 두 가족이 모두 활성 — 트리거 하나가 도는 자리다. */
    private fun seedOneDocument(): Seeded {
        val owner = insertOwner()
        val document = insertDocument(owner)
        return Seeded(
            owner = owner,
            documentIds = listOf(document.documentId),
            jobIds =
                mapOf(
                    ACTION_GUIDE_JOBS to insertJob(owner, document, ACTION_GUIDE_JOBS),
                    SUGGESTION_JOBS to insertJob(owner, document, SUGGESTION_JOBS),
                ),
        )
    }

    /** 문서 둘에 가족 하나씩 — 한 문장이 여러 문서를 지우는 자리다. */
    private fun seedTwoDocuments(
        first: String,
        second: String,
    ): Seeded {
        val owner = insertOwner()
        val firstDocument = insertDocument(owner)
        val secondDocument = insertDocument(owner)
        val jobIds =
            mapOf(
                first to insertJob(owner, firstDocument, first),
                second to insertJob(owner, secondDocument, second),
            )
        // 삭제가 훑는 물리 순서가 이 시나리오의 전제다 — 앞 문서를 정산한 **뒤에** 뒤 문서에서
        // 막혀야 사이클이 열린다. 순서가 뒤집히면 경합이 성립하지 않으므로 먼저 세워 둔다.
        assertThat(physicalOrder(owner.ownerId))
            .describedAs("삭제가 훑을 문서 순서")
            .containsExactly(firstDocument.documentId, secondDocument.documentId)
        return Seeded(
            owner = owner,
            documentIds = listOf(firstDocument.documentId, secondDocument.documentId),
            jobIds = jobIds,
        )
    }

    private fun insertOwner(): Owner {
        val ownerId = UUID.randomUUID()
        val workspaceId = UUID.randomUUID()
        execute(
            "INSERT INTO users (id,email,password_hash) " +
                "VALUES ('$ownerId','lock-$ownerId@example.test','$DUMMY_PHC')",
        )
        execute("INSERT INTO workspaces (id,user_id,name) VALUES ('$workspaceId','$ownerId','공간')")
        execute(
            "INSERT INTO workspace_credit_accounts (workspace_id,balance,reserved) " +
                "VALUES ('$workspaceId',10,2)",
        )
        return Owner(ownerId, workspaceId)
    }

    private fun insertDocument(owner: Owner): SeededDocument {
        val documentId = UUID.randomUUID()
        val conversionId = UUID.randomUUID()
        execute(
            """
            INSERT INTO documents
                (id,user_id,workspace_id,title,source_format,source_text_encrypted,
                 encryption_scheme,key_version,char_count)
            VALUES ('$documentId','${owner.ownerId}','${owner.workspaceId}','제목','txt','\x01'::bytea,
                    'aes256gcm-v1',1,10)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO conversions
                (id,document_id,status,easy_text_encrypted,encryption_scheme,key_version,content_revision)
            VALUES ('$conversionId','$documentId','done','\x02'::bytea,'aes256gcm-v1',1,1)
            """.trimIndent(),
        )
        return SeededDocument(documentId, conversionId)
    }

    /** 활성(queued) 작업 한 건과 그 예약 원장 한 행 — 운영이 만드는 모양 그대로다. */
    private fun insertJob(
        owner: Owner,
        document: SeededDocument,
        table: String,
    ): UUID {
        val jobId = UUID.randomUUID()
        val reasonColumn = if (table == ACTION_GUIDE_JOBS) "action_guide_job_id" else "illustration_suggestion_job_id"
        val reason = if (table == ACTION_GUIDE_JOBS) "action_guide" else "illustration_suggestion"
        execute(
            """
            INSERT INTO $table
                (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
                 expected_content_revision, based_on_content_revision, input_fingerprint,
                 status, settlement, reserved_credits)
            VALUES ('$jobId', gen_random_uuid(), '${owner.ownerId}', '${owner.workspaceId}',
                    '${document.documentId}', '${document.conversionId}',
                    1, 1, '$FINGERPRINT', 'queued', 'reserved', 1.0)
            """.trimIndent(),
        )
        execute(
            """
            INSERT INTO credit_transactions
                (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
                 reserved_delta, reason, $reasonColumn)
            VALUES (gen_random_uuid(), '${owner.workspaceId}', '${owner.ownerId}',
                    '${document.documentId}', 'reserve', 0, 1.0, '$reason', '$jobId')
            """.trimIndent(),
        )
        return jobId
    }

    /** 삭제 문장이 행을 훑는 순서 — `ORDER BY` 없는 seq scan 은 물리 순서를 따른다. */
    private fun physicalOrder(ownerId: UUID): List<UUID> =
        jdbc
            .sql("SELECT id FROM documents WHERE user_id = '$ownerId' ORDER BY ctid")
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun jobState(
        table: String,
        jobId: UUID,
    ): String = queryStrings("SELECT status || '|' || settlement FROM $table WHERE id = '$jobId'").single()

    private fun releaseCount(
        table: String,
        jobId: UUID,
    ): Int {
        val column = if (table == ACTION_GUIDE_JOBS) "action_guide_job_id" else "illustration_suggestion_job_id"
        return queryStrings(
            "SELECT count(*) FROM credit_transactions WHERE kind = 'release' AND $column = '$jobId'",
        ).single().toInt()
    }

    private fun documentCount(ownerId: UUID): Int =
        queryStrings("SELECT count(*) FROM documents WHERE user_id = '$ownerId'").single().toInt()

    private fun queryStrings(sql: String): List<String> = jdbc.sql(sql).query { rs, _ -> rs.getString(1) }.list()

    private fun execute(sql: String) {
        jdbc.sql(sql).update()
    }

    private fun execute(
        connection: Connection,
        sql: String,
    ) {
        connection.createStatement().use { it.execute(sql) }
    }

    private class Owner(
        val ownerId: UUID,
        val workspaceId: UUID,
    )

    private class SeededDocument(
        val documentId: UUID,
        val conversionId: UUID,
    )

    private class Seeded(
        val owner: Owner,
        val documentIds: List<UUID>,
        val jobIds: Map<String, UUID>,
    ) {
        val ownerId: UUID get() = owner.ownerId
        val workspaceId: UUID get() = owner.workspaceId
    }

    private companion object {
        const val ACTION_GUIDE_JOBS = "action_guide_jobs"
        const val SUGGESTION_JOBS = "illustration_suggestion_jobs"
        const val DUMMY_PHC = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c29tZXNhbHQ\$aGFzaGhhc2hoYXNoaGFzaGhhc2g"

        /** 두 작업 표의 `…_fingerprint_length` 가 정확히 64자를 요구한다. */
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** 삭제가 작업 행 앞에서 멈춰 선 것을 확인할 만큼은 길고, 시험을 늘어뜨리지 않을 만큼은 짧은 창. */
        const val BLOCKED_MILLIS = 500L
        const val HANDOFF_SECONDS = 30L
    }
}
