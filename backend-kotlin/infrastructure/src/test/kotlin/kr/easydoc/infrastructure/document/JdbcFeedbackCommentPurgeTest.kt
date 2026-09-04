package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.FeedbackCommentPurgeObserver
import kr.easydoc.application.document.FeedbackCommentPurgePolicy
import kr.easydoc.application.document.FeedbackCommentPurgeResult
import kr.easydoc.application.document.PurgeFeedbackComments
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.db.SpringTransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

/**
 * 피드백 자유 의견 파기 — 실제 PostgreSQL 에서 `submitted_at` 기준 나이 판정과 배치
 * 반복을 잰다.
 *
 * `submitted_at`(재제출 시각)을 나이 기준으로 쓰는 이유는 `FeedbackProperties` KDoc 이
 * 정본이다 — `updated_at` 은 키 회전이 내용 변경 없이도 미는 열이라 삭제 시계로 쓰면
 * 회전이 도는 한 영원히 지워지지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcFeedbackCommentPurgeTest {
    private lateinit var database: DatabaseHandle
    private lateinit var jdbc: JdbcClient
    private lateinit var dataSource: DataSource

    @BeforeAll
    fun prepare() {
        database = PostgresTestSupport.createEmptyDatabase("feedback_comment_purge")
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        dataSource = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)
        jdbc = JdbcClient.create(dataSource)
    }

    @BeforeEach
    fun cleanFeedback() {
        jdbc.sql("DELETE FROM conversion_feedback").update()
    }

    @Test
    @DisplayName("보존 일수보다 오래된 의견은 세 열이 비워지고 척도 숫자는 남는다")
    fun `오래된 의견을 비운다`() {
        val conversionId = insertFeedback(ageDays = RETENTION_DAYS + 1, withComment = true)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedComments).isEqualTo(1)
        val row = readRow(conversionId)
        assertThat(row.commentEncrypted).isNull()
        assertThat(row.scheme).isNull()
        assertThat(row.keyVersion).isNull()
        assertThat(row.publishIntent).isEqualTo("as_is")
        assertThat(row.qualityScore).isEqualTo(4)
        assertThat(row.minutesSpent).isEqualTo(MINUTES_SPENT)
    }

    @Test
    @DisplayName("보존 일수 안의 의견은 그대로 둔다")
    fun `최근 의견은 남긴다`() {
        val conversionId = insertFeedback(ageDays = 1, withComment = true)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedComments).isZero()
        assertThat(readRow(conversionId).commentEncrypted).isNotNull()
    }

    @Test
    @DisplayName("dry-run 은 건수만 세고 비우지 않는다")
    fun `dry-run 은 비우지 않는다`() {
        val conversionId = insertFeedback(ageDays = RETENTION_DAYS + 1, withComment = true)

        val result = purge(dryRun = true).run()

        assertThat(result.dryRun).isTrue()
        assertThat(result.purgedComments).isEqualTo(1)
        assertThat(readRow(conversionId).commentEncrypted).isNotNull()
    }

    @Test
    @DisplayName("의견이 없는 행은 애초에 대상이 아니다")
    fun `의견 없는 행은 건드리지 않는다`() {
        insertFeedback(ageDays = RETENTION_DAYS + 10, withComment = false)

        val result = purge(dryRun = false).run()

        assertThat(result.purgedComments).isZero()
    }

    @Test
    @DisplayName("한 스케줄이 배치를 넘겨 대상을 모두 비운다")
    fun `배치보다 많은 대상을 한 번에 비운다`() {
        val first = insertFeedback(ageDays = RETENTION_DAYS + 10, withComment = true)
        val second = insertFeedback(ageDays = RETENTION_DAYS + 5, withComment = true)
        val third = insertFeedback(ageDays = RETENTION_DAYS + 1, withComment = true)

        val result = purge(dryRun = false, batchSize = 2).run()

        assertThat(result.purgedComments).isEqualTo(3)
        assertThat(readRow(first).commentEncrypted).isNull()
        assertThat(readRow(second).commentEncrypted).isNull()
        assertThat(readRow(third).commentEncrypted).isNull()
    }

    @Test
    @DisplayName("결과 문자열에 의견 내용이 없다")
    fun `결과에 의견 내용이 없다`() {
        insertFeedback(ageDays = RETENTION_DAYS + 1, withComment = true, comment = COMMENT_HEX)

        val result = purge(dryRun = false).run()

        assertThat(result.toString()).doesNotContain(COMMENT_HEX)
    }

    private fun purge(
        dryRun: Boolean,
        batchSize: Int = BATCH,
        retentionDays: Int = RETENTION_DAYS,
    ): PurgeFeedbackComments =
        PurgeFeedbackComments(
            store = JdbcFeedbackCommentPurge(jdbc),
            transaction = SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(dataSource))),
            observer = NoopObserver,
            policy =
                FeedbackCommentPurgePolicy(
                    enabled = true,
                    dryRun = dryRun,
                    batchSize = batchSize,
                    retentionDays = retentionDays,
                ),
        )

    private fun insertFeedback(
        ageDays: Int,
        withComment: Boolean,
        comment: String = COMMENT_HEX,
    ): UUID {
        val conversionId = UUID.randomUUID()
        val commentSql = if (withComment) "'\\x$comment'::bytea" else "NULL"
        val schemeSql = if (withComment) "'aes256gcm-v1'" else "NULL"
        val keyVersionSql = if (withComment) "1" else "NULL"
        database.execute(
            """
            INSERT INTO conversion_feedback
                (conversion_id, user_id, publish_intent, quality_score, minutes_spent,
                 comment_encrypted, encryption_scheme, key_version, submitted_at, updated_at)
            VALUES ('$conversionId', '${UUID.randomUUID()}', 'as_is', 4, $MINUTES_SPENT,
                    $commentSql, $schemeSql, $keyVersionSql,
                    now() - interval '$ageDays days', now() - interval '$ageDays days');
            """.trimIndent(),
        )
        return conversionId
    }

    private fun readRow(conversionId: UUID): Row =
        jdbc
            .sql(
                """
                SELECT comment_encrypted, encryption_scheme, key_version,
                       publish_intent, quality_score, minutes_spent
                FROM conversion_feedback WHERE conversion_id = :id
                """.trimIndent(),
            ).param("id", conversionId)
            .query { rs, _ ->
                Row(
                    commentEncrypted = rs.getBytes("comment_encrypted"),
                    scheme = rs.getString("encryption_scheme"),
                    keyVersion = rs.getObject("key_version") as Int?,
                    publishIntent = rs.getString("publish_intent"),
                    qualityScore = rs.getInt("quality_score"),
                    minutesSpent = rs.getInt("minutes_spent"),
                )
            }.single()

    private class Row(
        val commentEncrypted: ByteArray?,
        val scheme: String?,
        val keyVersion: Int?,
        val publishIntent: String,
        val qualityScore: Int,
        val minutesSpent: Int,
    )

    private object NoopObserver : FeedbackCommentPurgeObserver {
        override fun record(result: FeedbackCommentPurgeResult) = Unit
    }

    private companion object {
        const val BATCH: Int = 100
        const val RETENTION_DAYS: Int = 30
        const val MINUTES_SPENT: Int = 5
        const val COMMENT_HEX: String = "00112233"
    }
}
