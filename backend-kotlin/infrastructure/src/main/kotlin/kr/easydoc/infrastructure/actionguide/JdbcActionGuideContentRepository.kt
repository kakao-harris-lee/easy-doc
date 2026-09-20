package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideContentRepository
import kr.easydoc.application.actionguide.StoredActionGuide
import kr.easydoc.application.actionguide.StoredActionGuideCandidate
import kr.easydoc.application.actionguide.StoredActionGuideJob
import kr.easydoc.core.crypto.EncryptedContent
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** 본문은 암호문만 다루고, 각 사용자 경로에서 소유권과 보존기간을 한 SQL로 확인한다. */
@Suppress("TooManyFunctions")
class JdbcActionGuideContentRepository(private val jdbc: JdbcClient) : ActionGuideContentRepository {
    override fun insertCandidate(
        job: StoredActionGuideJob,
        candidate: StoredActionGuideCandidate,
    ): Boolean {
        require(candidate.jobId == job.jobId && candidate.conversionId == job.conversionId)
        require(candidate.basedOnContentRevision == job.basedOnContentRevision)
        return jdbc
            .sql(
                """
                INSERT INTO action_guide_candidates
                    (id, job_id, conversion_id, based_on_content_revision,
                     payload_encrypted, encryption_scheme, key_version, created_at)
                SELECT :id, j.id, c.id, :revision, :payload, :scheme, :keyVersion, :createdAt
                FROM action_guide_jobs j
                JOIN conversions c ON c.id = j.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE j.id = :jobId AND j.owner_user_id = :ownerId
                  AND j.document_id = d.id AND j.conversion_id = :conversionId
                  AND j.based_on_content_revision = :revision
                  AND j.status IN ('running', 'succeeded')
                  AND c.content_revision = :revision AND d.user_id = :ownerId
                  AND d.retention_expires_at > now()
                ON CONFLICT (job_id) DO NOTHING
                """.trimIndent(),
            ).param("id", candidate.candidateId)
            .param("jobId", candidate.jobId)
            .param("ownerId", job.ownerId)
            .param("conversionId", candidate.conversionId)
            .param("revision", candidate.basedOnContentRevision)
            .param("payload", candidate.payload.bytes)
            .param("scheme", candidate.payload.scheme)
            .param("keyVersion", candidate.payload.keyVersion)
            .param("createdAt", utc(candidate.createdAt))
            .update() == 1
    }

    override fun findCandidateForJobOwned(
        ownerId: UUID,
        conversionId: UUID,
        jobId: UUID,
    ): StoredActionGuideCandidate? =
        jdbc
            .sql(
                """
                SELECT a.* FROM action_guide_candidates a
                JOIN conversions c ON c.id = a.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE a.job_id = :jobId AND a.conversion_id = :conversionId
                  AND d.user_id = :ownerId AND d.retention_expires_at > now()
                """.trimIndent(),
            ).param("jobId", jobId)
            .param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .query(::candidateRow)
            .optional()
            .orElse(null)

    override fun findCandidateOwned(
        ownerId: UUID,
        conversionId: UUID,
        candidateId: UUID,
    ): StoredActionGuideCandidate? =
        jdbc
            .sql(
                """
                SELECT a.* FROM action_guide_candidates a
                JOIN conversions c ON c.id = a.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE a.id = :candidateId AND a.conversion_id = :conversionId
                  AND d.user_id = :ownerId AND d.retention_expires_at > now()
                """.trimIndent(),
            ).param("candidateId", candidateId)
            .param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .query(::candidateRow)
            .optional()
            .orElse(null)

    override fun findGuideOwned(
        ownerId: UUID,
        conversionId: UUID,
    ): StoredActionGuide? =
        jdbc
            .sql(
                """
                SELECT g.* FROM action_guides g
                JOIN conversions c ON c.id = g.conversion_id
                JOIN documents d ON d.id = c.document_id
                WHERE g.conversion_id = :conversionId AND d.user_id = :ownerId
                  AND d.retention_expires_at > now()
                """.trimIndent(),
            ).param("conversionId", conversionId)
            .param("ownerId", ownerId)
            .query(::guideRow)
            .optional()
            .orElse(null)

    override fun saveGuide(
        ownerId: UUID,
        expectedContentRevision: Long,
        expectedGuideRevision: Long?,
        guide: StoredActionGuide,
    ): Boolean {
        require(guide.basedOnContentRevision == expectedContentRevision)
        require(guide.status == "draft" || guide.status == "reviewed")
        require(guide.guideRevision == (expectedGuideRevision ?: 0L) + 1L)
        return if (expectedGuideRevision == null) {
            insertGuide(ownerId, expectedContentRevision, guide)
        } else {
            updateGuide(ownerId, expectedContentRevision, expectedGuideRevision, guide)
        }
    }

    private fun insertGuide(
        ownerId: UUID,
        contentRevision: Long,
        guide: StoredActionGuide,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO action_guides
                    (id, conversion_id, based_on_content_revision, guide_revision, status,
                     payload_encrypted, encryption_scheme, key_version,
                     reviewed_at, reviewed_by, created_at, updated_at)
                SELECT :id, c.id, :contentRevision, :guideRevision, :status,
                       :payload, :scheme, :keyVersion,
                       :reviewedAt, :reviewedBy, :createdAt, :updatedAt
                FROM conversions c JOIN documents d ON d.id = c.document_id
                WHERE c.id = :conversionId AND c.content_revision = :contentRevision
                  AND d.user_id = :ownerId AND d.retention_expires_at > now()
                ON CONFLICT (conversion_id) DO NOTHING
                """.trimIndent(),
            ).bindGuide(guide)
            .param("contentRevision", contentRevision)
            .param("ownerId", ownerId)
            .update() == 1

    private fun updateGuide(
        ownerId: UUID,
        contentRevision: Long,
        expectedGuideRevision: Long,
        guide: StoredActionGuide,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE action_guides g
                SET based_on_content_revision = :contentRevision,
                    guide_revision = :guideRevision, status = :status,
                    payload_encrypted = :payload, encryption_scheme = :scheme,
                    key_version = :keyVersion, reviewed_at = :reviewedAt,
                    reviewed_by = :reviewedBy, updated_at = :updatedAt
                FROM conversions c JOIN documents d ON d.id = c.document_id
                WHERE g.id = :id AND g.conversion_id = :conversionId
                  AND g.conversion_id = c.id AND g.guide_revision = :expectedGuideRevision
                  AND c.content_revision = :contentRevision
                  AND d.user_id = :ownerId AND d.retention_expires_at > now()
                """.trimIndent(),
            ).bindGuide(guide)
            .param("contentRevision", contentRevision)
            .param("expectedGuideRevision", expectedGuideRevision)
            .param("ownerId", ownerId)
            .update() == 1

    private fun JdbcClient.StatementSpec.bindGuide(guide: StoredActionGuide): JdbcClient.StatementSpec =
        param("id", guide.guideId)
            .param("conversionId", guide.conversionId)
            .param("guideRevision", guide.guideRevision)
            .param("status", guide.status)
            .param("payload", guide.payload.bytes)
            .param("scheme", guide.payload.scheme)
            .param("keyVersion", guide.payload.keyVersion)
            .param("reviewedAt", guide.reviewedAt?.let(::utc))
            .param("reviewedBy", guide.reviewedBy)
            .param("createdAt", utc(guide.createdAt))
            .param("updatedAt", utc(guide.updatedAt))

    private fun candidateRow(
        rs: ResultSet,
        ignored: Int,
    ): StoredActionGuideCandidate =
        StoredActionGuideCandidate(
            candidateId = rs.getObject("id", UUID::class.java),
            jobId = rs.getObject("job_id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            basedOnContentRevision = rs.getLong("based_on_content_revision"),
            payload = envelope(rs),
            createdAt = instant(rs, "created_at"),
        )

    private fun guideRow(
        rs: ResultSet,
        ignored: Int,
    ): StoredActionGuide =
        StoredActionGuide(
            guideId = rs.getObject("id", UUID::class.java),
            conversionId = rs.getObject("conversion_id", UUID::class.java),
            basedOnContentRevision = rs.getLong("based_on_content_revision"),
            guideRevision = rs.getLong("guide_revision"),
            status = rs.getString("status"),
            payload = envelope(rs),
            reviewedAt = rs.getObject("reviewed_at", OffsetDateTime::class.java)?.toInstant(),
            reviewedBy = rs.getObject("reviewed_by", UUID::class.java),
            createdAt = instant(rs, "created_at"),
            updatedAt = instant(rs, "updated_at"),
        )

    private fun envelope(rs: ResultSet): EncryptedContent =
        EncryptedContent(rs.getBytes("payload_encrypted"), rs.getString("encryption_scheme"), rs.getInt("key_version"))

    private fun instant(
        rs: ResultSet,
        name: String,
    ): Instant = rs.getObject(name, OffsetDateTime::class.java).toInstant()

    private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
}
