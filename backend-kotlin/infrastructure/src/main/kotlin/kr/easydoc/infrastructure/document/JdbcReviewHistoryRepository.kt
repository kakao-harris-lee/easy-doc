package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.ReviewHistoryCursor
import kr.easydoc.application.document.ReviewHistoryEventToStore
import kr.easydoc.application.document.ReviewHistoryEventType
import kr.easydoc.application.document.ReviewHistoryRepository
import kr.easydoc.application.document.ReviewHistorySnapshotKind
import kr.easydoc.application.document.ReviewHistorySnapshotToStore
import kr.easydoc.application.document.StoredReviewHistoryEvent
import kr.easydoc.application.document.StoredReviewHistorySnapshot
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.exceptions.StorageException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** JDBC adapter for the append-only R5 event log and its encrypted bounded snapshots. */
class JdbcReviewHistoryRepository(private val jdbc: JdbcClient) : ReviewHistoryRepository {
    override fun append(
        ownerId: UUID,
        event: ReviewHistoryEventToStore,
        snapshot: ReviewHistorySnapshotToStore?,
    ) {
        val actualSnapshotId = snapshot?.let { insertSnapshot(ownerId, it) }
        if (snapshot != null && actualSnapshotId == null) throw StorageException(STORAGE_FAILURE_MESSAGE)
        val inserted = insertEvent(ownerId, event, actualSnapshotId)
        if (inserted != 1) throw StorageException(STORAGE_FAILURE_MESSAGE)
        pruneSnapshots(ownerId, event.conversionId)
    }

    private fun insertSnapshot(
        ownerId: UUID,
        snapshot: ReviewHistorySnapshotToStore,
    ): UUID? =
        jdbc
            .sql(
                """
                INSERT INTO review_snapshots
                    (id, conversion_id, content_revision, artifact_revision, kind,
                     payload_encrypted, encryption_scheme, key_version)
                SELECT :id, :conversionId, :contentRevision, :artifactRevision, :kind,
                       :payload, :scheme, :keyVersion
                WHERE EXISTS (
                    SELECT 1 FROM conversions c
                    JOIN documents d ON d.id = c.document_id
                    WHERE c.id = :conversionId AND d.user_id = :ownerId
                      AND d.retention_expires_at > now()
                )
                RETURNING id
                """.trimIndent(),
            ).param("id", snapshot.snapshotId)
            .param("conversionId", snapshot.conversionId)
            .param("contentRevision", snapshot.contentRevision)
            .param("artifactRevision", snapshot.artifactRevision)
            .param("kind", snapshot.kind?.wireName)
            .param("payload", snapshot.payload.bytes)
            .param("scheme", snapshot.payload.scheme)
            .param("keyVersion", snapshot.payload.keyVersion)
            .param("ownerId", ownerId)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .optional()
            .orElse(null)

    private fun insertEvent(
        ownerId: UUID,
        event: ReviewHistoryEventToStore,
        snapshotId: UUID?,
    ): Int =
        jdbc
            .sql(
                """
                INSERT INTO review_events
                    (id, conversion_id, event_type, actor_user_id, created_at,
                     content_revision, artifact_revision, item_id, assessment_id, guide_id, snapshot_id)
                SELECT :id, :conversionId, :eventType, :actorUserId, :createdAt,
                       :contentRevision, :artifactRevision, :itemId, :assessmentId, :guideId, :snapshotId
                WHERE EXISTS (
                    SELECT 1 FROM conversions c
                    JOIN documents d ON d.id = c.document_id
                    WHERE c.id = :conversionId AND d.user_id = :ownerId
                      AND d.retention_expires_at > now()
                )
                """.trimIndent(),
            ).param("id", event.eventId)
            .param("conversionId", event.conversionId)
            .param("eventType", event.eventType.wireName)
            .param("actorUserId", event.actorUserId)
            .param("createdAt", OffsetDateTime.ofInstant(event.createdAt, java.time.ZoneOffset.UTC))
            .param("contentRevision", event.contentRevision)
            .param("artifactRevision", event.artifactRevision)
            .param("itemId", event.itemId)
            .param("assessmentId", event.assessmentId)
            .param("guideId", event.guideId)
            .param("snapshotId", snapshotId)
            .param("ownerId", ownerId)
            .update()

    private fun pruneSnapshots(
        ownerId: UUID,
        conversionId: UUID,
    ) {
        // Keep the newest twenty snapshot records. This bounds payload storage even when many
        // item revisions are recorded for one body, and the event row survives with snapshot_id
        // nulled by the FK when a snapshot is pruned.
        jdbc
            .sql(
                """
                DELETE FROM review_snapshots s
                WHERE EXISTS (
                    SELECT 1 FROM conversions c JOIN documents d ON d.id = c.document_id
                    WHERE c.id = s.conversion_id AND d.user_id = :ownerId
                ) AND s.id IN (
                    SELECT id FROM review_snapshots
                    WHERE conversion_id = :conversionId
                    ORDER BY created_at DESC, id DESC
                    OFFSET $MAX_SNAPSHOT_ROWS
                )
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .update()
    }

    override fun pageOwned(
        ownerId: UUID,
        conversionId: UUID,
        cutoff: Instant,
        after: ReviewHistoryCursor?,
        limit: Int,
    ): List<StoredReviewHistoryEvent> {
        require(limit in 1..MAX_PAGE_ROWS)
        val afterClause =
            if (after == null) {
                ""
            } else {
                " AND (e.created_at < :afterCreatedAt OR (e.created_at = :afterCreatedAt AND e.id < :afterId))"
            }
        val query =
            jdbc
                .sql(
                    """
                    SELECT e.id, e.conversion_id, e.event_type, e.actor_user_id, e.created_at,
                           e.content_revision, e.artifact_revision, e.item_id, e.assessment_id, e.guide_id,
                           s.id AS snapshot_row_id, s.content_revision AS snapshot_content_revision,
                           s.artifact_revision AS snapshot_artifact_revision, s.kind AS snapshot_kind,
                           s.payload_encrypted AS snapshot_payload, s.encryption_scheme AS snapshot_scheme,
                           s.key_version AS snapshot_key_version
                    FROM review_events e
                    JOIN conversions c ON c.id = e.conversion_id
                    JOIN documents d ON d.id = c.document_id
                    LEFT JOIN review_snapshots s ON s.id = e.snapshot_id
                    WHERE e.conversion_id = :conversionId AND d.user_id = :ownerId
                      AND d.retention_expires_at > now()
                      AND e.created_at <= :cutoff
                      $afterClause
                    ORDER BY e.created_at DESC, e.id DESC
                    LIMIT :limit
                    """.trimIndent(),
                ).param("conversionId", conversionId)
                .param("ownerId", ownerId)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC))
                .param("limit", limit)
        if (after != null) {
            query.param("afterCreatedAt", OffsetDateTime.ofInstant(after.createdAt, java.time.ZoneOffset.UTC))
            query.param("afterId", after.eventId)
        }
        return query.query(::row).list()
    }

    override fun lockSnapshot(snapshotId: UUID): StoredReviewHistorySnapshot? =
        jdbc
            .sql("SELECT * FROM review_snapshots WHERE id = :id FOR NO KEY UPDATE")
            .param("id", snapshotId)
            .query(::snapshotRow)
            .optional()
            .orElse(null)

    override fun rewriteSnapshotEnvelope(
        expected: StoredReviewHistorySnapshot,
        payload: EncryptedContent,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE review_snapshots
                SET payload_encrypted = :payload, encryption_scheme = :scheme, key_version = :keyVersion
                WHERE id = :id AND encryption_scheme = :expectedScheme AND key_version = :expectedKeyVersion
                  AND payload_encrypted = :expectedPayload
                """.trimIndent(),
            ).param("payload", payload.bytes)
            .param("scheme", payload.scheme)
            .param("keyVersion", payload.keyVersion)
            .param("id", expected.snapshotId)
            .param("expectedScheme", expected.payload.scheme)
            .param("expectedKeyVersion", expected.payload.keyVersion)
            .param("expectedPayload", expected.payload.bytes)
            .update() == 1

    override fun snapshotIdsOlderThan(
        keyVersion: Int,
        after: UUID,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM review_snapshots
                WHERE key_version < :keyVersion AND id > :after
                ORDER BY id ASC LIMIT :limit
                """.trimIndent(),
            ).param("keyVersion", keyVersion)
            .param("after", after)
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()

    private fun row(
        rs: ResultSet,
        ignored: Int,
    ): StoredReviewHistoryEvent {
        val eventType =
            ReviewHistoryEventType.entries.firstOrNull { it.wireName == rs.getString("event_type") }
                ?: throw StorageException(STORAGE_FAILURE_MESSAGE)
        val snapshot =
            rs.getObject("snapshot_row_id", UUID::class.java)?.let {
                StoredReviewHistorySnapshot(
                    snapshotId = it,
                    conversionId = rs.getObject("conversion_id", UUID::class.java),
                    contentRevision = rs.getLong("snapshot_content_revision"),
                    artifactRevision = rs.getObject("snapshot_artifact_revision", Long::class.javaObjectType),
                    kind =
                        rs.getString("snapshot_kind")?.let { wire ->
                            ReviewHistorySnapshotKind.entries.firstOrNull { it.wireName == wire }
                                ?: throw StorageException(STORAGE_FAILURE_MESSAGE)
                        },
                    payload =
                        EncryptedContent(
                            rs.getBytes("snapshot_payload"),
                            rs.getString("snapshot_scheme"),
                            rs.getInt("snapshot_key_version"),
                        ),
                )
            }
        return StoredReviewHistoryEvent(
            eventId = rs.getObject("id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            eventType = eventType,
            actorUserId = rs.getObject("actor_user_id", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            contentRevision = rs.getLong("content_revision"),
            artifactRevision = rs.getObject("artifact_revision", Long::class.javaObjectType),
            itemId = rs.getObject("item_id", UUID::class.java),
            assessmentId = rs.getObject("assessment_id", UUID::class.java),
            guideId = rs.getObject("guide_id", UUID::class.java),
            snapshot = snapshot,
        )
    }

    private fun snapshotRow(
        rs: ResultSet,
        ignored: Int,
    ): StoredReviewHistorySnapshot =
        StoredReviewHistorySnapshot(
            snapshotId = rs.getObject("id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            contentRevision = rs.getLong("content_revision"),
            artifactRevision = rs.getObject("artifact_revision", Long::class.javaObjectType),
            kind =
                rs.getString("kind")?.let { wire ->
                    ReviewHistorySnapshotKind.entries.firstOrNull { it.wireName == wire }
                        ?: throw StorageException(STORAGE_FAILURE_MESSAGE)
                },
            payload =
                EncryptedContent(
                    rs.getBytes("payload_encrypted"),
                    rs.getString("encryption_scheme"),
                    rs.getInt("key_version"),
                ),
        )

    private companion object {
        const val MAX_SNAPSHOT_ROWS: Int = 20
        const val MAX_PAGE_ROWS: Int = 101
        const val STORAGE_FAILURE_MESSAGE: String = "검수 기록을 저장할 수 없습니다"
    }
}
