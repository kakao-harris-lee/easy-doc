package kr.easydoc.api.admin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import kr.easydoc.application.admin.Announcement
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Instant

/** `POST /admin/announcements` 요청 본문. */
data class AnnouncementCreateRequest(
    @get:JsonProperty("body") val body: String,
) {
    /** 공지 본문을 찍지 않는다(인구조사 규약) — 화면에 그대로 뜨는 문구다. */
    override fun toString(): String = "AnnouncementCreateRequest(body=$CONTENT_MASK)"
}

/**
 * `PATCH /admin/announcements/{id}` 요청 본문 — 둘 다 선택. 전역 null 처리는 실패다
 * (`JsonRequestStrictnessConfig`) — 둘 다 이 표식으로 그 기본을 뒤집는다
 * (`DocumentTextRequest.title`과 같은 관행).
 */
data class AnnouncementUpdateRequest
    @JsonCreator
    constructor(
        @param:JsonProperty("body")
        @param:JsonSetter(nulls = Nulls.SET)
        val body: String?,
        @param:JsonProperty("active")
        @param:JsonSetter(nulls = Nulls.SET)
        val active: Boolean?,
    ) {
        override fun toString(): String = "AnnouncementUpdateRequest(body=$CONTENT_MASK, active=$active)"
    }

/** `GET`·`POST /admin/announcements`·`PATCH /admin/announcements/{id}` 응답 한 건. */
data class AnnouncementResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("body") val body: String,
    @get:JsonProperty("active") val active: Boolean,
    @get:JsonProperty("created_by") val createdBy: String,
    @get:JsonProperty("created_at") val createdAt: Instant,
    @get:JsonProperty("updated_at") val updatedAt: Instant,
) {
    /** 공지 본문을 찍지 않는다(인구조사 규약). */
    override fun toString(): String =
        "AnnouncementResponse(id=$id, body=$CONTENT_MASK, active=$active, createdBy=$createdBy, " +
            "createdAt=$createdAt, updatedAt=$updatedAt)"

    companion object {
        fun of(announcement: Announcement): AnnouncementResponse =
            AnnouncementResponse(
                id = announcement.id.toString(),
                body = announcement.body,
                active = announcement.active,
                createdBy = announcement.createdBy.toString(),
                createdAt = announcement.createdAt,
                updatedAt = announcement.updatedAt,
            )
    }
}

/** `GET /admin/announcements` 응답. */
data class AnnouncementListResponse(
    @get:JsonProperty("items") val items: List<AnnouncementResponse>,
)

/** `GET /announcements/active` 응답 항목 — 사용자 화면 배너용, 관리자 필드(작성자 등) 없음. */
data class ActiveAnnouncementResponse(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("body") val body: String,
    @get:JsonProperty("created_at") val createdAt: Instant,
) {
    /** 공지 본문을 찍지 않는다(인구조사 규약). */
    override fun toString(): String = "ActiveAnnouncementResponse(id=$id, body=$CONTENT_MASK, createdAt=$createdAt)"

    companion object {
        fun of(announcement: Announcement): ActiveAnnouncementResponse =
            ActiveAnnouncementResponse(
                id = announcement.id.toString(),
                body = announcement.body,
                createdAt = announcement.createdAt,
            )
    }
}

/** `GET /announcements/active` 응답. */
data class ActiveAnnouncementListResponse(
    @get:JsonProperty("items") val items: List<ActiveAnnouncementResponse>,
)
