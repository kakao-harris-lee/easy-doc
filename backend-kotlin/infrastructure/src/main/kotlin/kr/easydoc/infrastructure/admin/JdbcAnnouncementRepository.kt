package kr.easydoc.infrastructure.admin

import kr.easydoc.application.admin.Announcement
import kr.easydoc.application.admin.AnnouncementRepository
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** `announcements` 접근. 스키마는 `V17__admin.sql`. */
class JdbcAnnouncementRepository(private val jdbc: JdbcClient) : AnnouncementRepository {
    override fun create(
        id: UUID,
        body: String,
        createdBy: UUID,
        createdAt: Instant,
    ): Announcement =
        jdbc
            .sql(
                """
                INSERT INTO announcements (id, body, active, created_by, created_at, updated_at)
                VALUES (:id, :body, true, :createdBy, :createdAt, :createdAt)
                RETURNING $COLUMNS
                """.trimIndent(),
            ).param("id", id)
            .param("body", body)
            .param("createdBy", createdBy)
            .param("createdAt", createdAt.atOffset(java.time.ZoneOffset.UTC))
            .query { rs, _ -> toAnnouncement(rs) }
            .single()

    override fun listAll(): List<Announcement> =
        jdbc
            .sql("SELECT $COLUMNS FROM announcements ORDER BY created_at DESC, id DESC")
            .query { rs, _ -> toAnnouncement(rs) }
            .list()

    override fun find(id: UUID): Announcement? =
        jdbc
            .sql("SELECT $COLUMNS FROM announcements WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> toAnnouncement(rs) }
            .optional()
            .orElse(null)

    /** 갱신할 필드만 `coalesce`로 바꾼다 — `null`이면 그 필드는 그대로다. */
    override fun update(
        id: UUID,
        body: String?,
        active: Boolean?,
        updatedAt: Instant,
    ): Announcement? =
        jdbc
            .sql(
                """
                UPDATE announcements
                SET body = coalesce(:body, body), active = coalesce(:active, active), updated_at = :updatedAt
                WHERE id = :id
                RETURNING $COLUMNS
                """.trimIndent(),
            ).param("id", id)
            .param("body", body)
            .param("active", active)
            .param("updatedAt", updatedAt.atOffset(java.time.ZoneOffset.UTC))
            .query { rs, _ -> toAnnouncement(rs) }
            .optional()
            .orElse(null)

    override fun listActive(limit: Int): List<Announcement> =
        jdbc
            .sql(
                """
                SELECT $COLUMNS FROM announcements
                WHERE active
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("limit", limit)
            .query { rs, _ -> toAnnouncement(rs) }
            .list()

    private fun toAnnouncement(rs: ResultSet): Announcement =
        Announcement(
            id = rs.getObject("id", UUID::class.java),
            body = rs.getString("body"),
            active = rs.getBoolean("active"),
            createdBy = rs.getObject("created_by", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        )

    private companion object {
        const val COLUMNS = "id, body, active, created_by, created_at, updated_at"
    }
}
