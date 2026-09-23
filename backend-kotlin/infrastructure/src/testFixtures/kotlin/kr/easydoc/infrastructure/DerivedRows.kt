package kr.easydoc.infrastructure

import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * V28~V34 가 문서·변환에 매단 파생 표의 **인구조사와 seeding**.
 *
 * 보존 만료 파기(`JdbcRetentionPurgeTest`)와 회원 탈퇴(`JdbcAccountDeletionRepositoryTest`)가
 * 같은 CASCADE 범위를 재므로 표 목록과 심는 SQL 을 여기 한 곳에 둔다 — 두 곳에 나눠 두면
 * V35 가 표를 더할 때 한쪽만 늘어나고, 늘지 않은 쪽은 「전부 지워졌다」를 더 작은 집합에서만
 * 확인하면서 계속 초록이다.
 *
 * Spring `JdbcClient` 대신 순수 JDBC 를 쓴다 — testFixtures 클래스패스에 `spring-jdbc` 를
 * 새로 들이지 않기 위해서다(`api` 테스트도 이 산출물을 당긴다).
 */
object DerivedRows {
    const val DOCUMENT_ID: String = "document_id"
    const val CONVERSION_ID: String = "conversion_id"

    /** `ck_action_guide_jobs_fingerprint_length` 이 정확히 64자를 요구한다. */
    const val ACTION_GUIDE_FINGERPRINT: String =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    /** `ck_action_guide_jobs_reserved_credits_positive` 과 `…_tenth`(V31)를 함께 만족한다. */
    val RESERVED_CREDITS: BigDecimal = BigDecimal("1.0")

    /** 봉인된 payload 자리. 이 fixture 는 복호화를 재지 않으므로 내용은 아무 바이트면 된다. */
    val PAYLOAD: ByteArray = byteArrayOf(1, 2, 3, 4)

    /**
     * 표 이름과 그 표를 문서·변환에 잇는 열. 전부 `ON DELETE CASCADE` 라 문서 하나가
     * 사라지면 함께 사라져야 한다 — FK 를 일부러 두지 않은 `action_guide_jobs`(V29,
     * 청구 근거 보존)는 여기 없다.
     */
    val CENSUS: List<Pair<String, String>> =
        listOf(
            "review_assessments" to CONVERSION_ID,
            "document_table_structures" to DOCUMENT_ID,
            "review_snapshots" to CONVERSION_ID,
            "review_events" to CONVERSION_ID,
            "illustration_placements" to CONVERSION_ID,
            "action_guide_candidates" to CONVERSION_ID,
            "action_guides" to CONVERSION_ID,
        )

    /**
     * [CENSUS] 의 모든 표에 행 하나씩과, `action_guide_candidates` 가 FK 로 요구하는 행동
     * 안내 작업 행 하나를 심는다. 반환값은 그 작업 id 다 — 그 표만 파기 뒤에도 남는지
     * 따로 보게 된다.
     *
     * [jobStatus]·[jobSettlement] 기본값은 **끝난 작업**이다. V29 의 문서 삭제 트리거가
     * 정산하는 대상은 `queued`·`running` 뿐이라, 기본값으로는 그 갈래를 건드리지 않고
     * CASCADE 범위만 잰다.
     */
    @Suppress("LongParameterList") // 심을 행이 매다는 축의 수다 — 도메인 입력 복잡도가 아니다.
    fun seed(
        dataSource: DataSource,
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
        jobStatus: String = "succeeded",
        jobSettlement: String = "consumed",
    ): UUID {
        val jobId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            seedReviewSupport(connection, conversionId)
            seedTableStructure(connection, documentId)
            seedReviewHistory(connection, ownerId, conversionId)
            seedIllustrationPlacements(connection, conversionId)
            seedActionGuideJob(connection, JobRow(jobId, ownerId, workspaceId, documentId, conversionId))
            seedActionGuideContent(connection, conversionId, jobId)
            if (jobStatus != "succeeded" || jobSettlement != "consumed") {
                moveJob(connection, jobId, jobStatus, jobSettlement)
            }
        }
        return jobId
    }

    /** [CENSUS] 순서대로 「표 이름 → 남은 행 수」. 단언은 부르는 쪽이 한다. */
    fun counts(
        dataSource: DataSource,
        documentId: UUID,
        conversionId: UUID,
    ): Map<String, Int> =
        dataSource.connection.use { connection ->
            CENSUS.associate { (table, column) ->
                table to count(connection, table, column, if (column == DOCUMENT_ID) documentId else conversionId)
            }
        }

    private fun seedReviewSupport(
        connection: Connection,
        conversionId: UUID,
    ) = update(
        connection,
        """
        INSERT INTO review_assessments
            (id, conversion_id, content_revision, analyzer_version,
             payload_encrypted, encryption_scheme, key_version)
        VALUES (?, ?, 1, 'fact-preservation-v1', ?, 'aes256gcm-v1', 1)
        """,
        UUID.randomUUID(),
        conversionId,
        PAYLOAD,
    )

    private fun seedTableStructure(
        connection: Connection,
        documentId: UUID,
    ) = update(
        connection,
        """
        INSERT INTO document_table_structures
            (document_id, payload_encrypted, encryption_scheme, key_version)
        VALUES (?, ?, 'aes256gcm-v1', 1)
        """,
        documentId,
        PAYLOAD,
    )

    private fun seedReviewHistory(
        connection: Connection,
        ownerId: UUID,
        conversionId: UUID,
    ) {
        val snapshotId = UUID.randomUUID()
        update(
            connection,
            """
            INSERT INTO review_snapshots
                (id, conversion_id, content_revision, kind, payload_encrypted, encryption_scheme, key_version)
            VALUES (?, ?, 1, 'review_assessment', ?, 'aes256gcm-v1', 1)
            """,
            snapshotId,
            conversionId,
            PAYLOAD,
        )
        update(
            connection,
            """
            INSERT INTO review_events
                (id, conversion_id, event_type, actor_user_id, created_at, content_revision, snapshot_id)
            VALUES (?, ?, 'item_confirmed', ?, now(), 1, ?)
            """,
            UUID.randomUUID(),
            conversionId,
            ownerId,
            snapshotId,
        )
    }

    private fun seedIllustrationPlacements(
        connection: Connection,
        conversionId: UUID,
    ) = update(
        connection,
        """
        INSERT INTO illustration_placements
            (id, conversion_id, content_revision, payload_encrypted, encryption_scheme, key_version)
        VALUES (?, ?, 1, ?, 'aes256gcm-v1', 1)
        """,
        UUID.randomUUID(),
        conversionId,
        PAYLOAD,
    )

    private fun seedActionGuideContent(
        connection: Connection,
        conversionId: UUID,
        jobId: UUID,
    ) {
        update(
            connection,
            """
            INSERT INTO action_guide_candidates
                (id, job_id, conversion_id, based_on_content_revision,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (?, ?, ?, 1, ?, 'aes256gcm-v1', 1)
            """,
            UUID.randomUUID(),
            jobId,
            conversionId,
            PAYLOAD,
        )
        update(
            connection,
            """
            INSERT INTO action_guides
                (id, conversion_id, based_on_content_revision, guide_revision, status,
                 payload_encrypted, encryption_scheme, key_version)
            VALUES (?, ?, 1, 1, 'draft', ?, 'aes256gcm-v1', 1)
            """,
            UUID.randomUUID(),
            conversionId,
            PAYLOAD,
        )
    }

    /** 결과 행을 먼저 심을 수 있도록 끝난 상태로 넣는다 — 필요하면 [moveJob] 이 옮긴다. */
    private fun seedActionGuideJob(
        connection: Connection,
        job: JobRow,
    ) = update(
        connection,
        """
        INSERT INTO action_guide_jobs
            (id, request_id, owner_user_id, workspace_id, document_id, conversion_id,
             expected_content_revision, based_on_content_revision, input_fingerprint,
             status, reserved_credits, settlement)
        VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, 'succeeded', ?, 'consumed')
        """,
        job.jobId,
        UUID.randomUUID(),
        job.ownerId,
        job.workspaceId,
        job.documentId,
        job.conversionId,
        ACTION_GUIDE_FINGERPRINT,
        RESERVED_CREDITS,
    )

    private fun moveJob(
        connection: Connection,
        jobId: UUID,
        status: String,
        settlement: String,
    ) = update(
        connection,
        "UPDATE action_guide_jobs SET status = ?, settlement = ? WHERE id = ?",
        status,
        settlement,
        jobId,
    )

    private fun count(
        connection: Connection,
        table: String,
        column: String,
        value: UUID,
    ): Int =
        connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?").use { statement ->
            statement.setObject(1, value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun update(
        connection: Connection,
        sql: String,
        vararg params: Any,
    ) {
        connection.prepareStatement(sql.trimIndent()).use { statement ->
            params.forEachIndexed { index, value ->
                when (value) {
                    is ByteArray -> statement.setBytes(index + 1, value)
                    else -> statement.setObject(index + 1, value)
                }
            }
            statement.executeUpdate()
        }
    }

    /** 행동 안내 작업 한 행이 매다는 축들. 인자 수를 줄이려고 묶는다. */
    private class JobRow(
        val jobId: UUID,
        val ownerId: UUID,
        val workspaceId: UUID,
        val documentId: UUID,
        val conversionId: UUID,
    )
}
