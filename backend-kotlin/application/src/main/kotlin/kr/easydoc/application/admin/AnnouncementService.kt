package kr.easydoc.application.admin

import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.privacy.CONTENT_MASK
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 공지 한 건 — V17 `announcements`(어드민 최소 계획 §2 결정 5). */
data class Announcement(
    val id: UUID,
    val body: String,
    val active: Boolean,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** 공지 본문을 찍지 않는다(인구조사 규약) — 운영자가 쓰지만 사용자 화면에 그대로 뜨는 문구다. */
    override fun toString(): String =
        "Announcement(id=$id, body=$CONTENT_MASK, active=$active, createdBy=$createdBy, " +
            "createdAt=$createdAt, updatedAt=$updatedAt)"
}

/** `announcements`(V17) 저장소. */
interface AnnouncementRepository {
    fun create(
        id: UUID,
        body: String,
        createdBy: UUID,
        createdAt: Instant,
    ): Announcement

    /** 관리자 목록 — 활성·비활성 전부, 최신순. */
    fun listAll(): List<Announcement>

    /** id로 하나를 읽는다. 없으면 `null` — [AnnouncementService.update]의 무변경 조기 반환이 쓴다. */
    fun find(id: UUID): Announcement?

    /** [body]·[active] 는 각각 선택 갱신 — `null`이면 그 필드는 바꾸지 않는다. 없으면 `null`. */
    fun update(
        id: UUID,
        body: String?,
        active: Boolean?,
        updatedAt: Instant,
    ): Announcement?

    /** 사용자용 — 활성 공지만, 최신순 최대 [limit]건. */
    fun listActive(limit: Int): List<Announcement>
}

/**
 * 공지 유스케이스 — 어드민 최소 계획 `docs/plans/2026-09-07-admin-minimum.md` §2 결정 5.
 * 관리자: `GET`·`POST /admin/announcements`, `PATCH /admin/announcements/{id}`.
 * 사용자: `GET /announcements/active`(인증 사용자 전용, 활성 최대 5건 최신순).
 */
class AnnouncementService(
    private val repository: AnnouncementRepository,
    private val clock: Clock,
) {
    fun create(
        rawBody: String,
        createdBy: UUID,
    ): Announcement {
        val body = requireValidAnnouncementBody(rawBody)
        return repository.create(UUID.randomUUID(), body, createdBy, clock.instant())
    }

    fun listAll(): List<Announcement> = repository.listAll()

    /**
     * `body`·`active` 둘 다 없으면(생략이든 명시적 `null`이든 — 계약 `updateAnnouncement`
     * 「명시적 null은 생략과 같다」) **아무것도 바꾸지 않고 현재 행을 그대로 돌려준다** —
     * `updated_at`을 건드리지 않고 감사 로그도 남기지 않는다(독립 리뷰 지적, 호출부인
     * `AdminAnnouncementController.update`가 이 경우를 판별해 로그를 건너뛴다).
     */
    fun update(
        id: UUID,
        rawBody: String?,
        active: Boolean?,
    ): Announcement {
        val body = rawBody?.let(::requireValidAnnouncementBody)
        if (body == null && active == null) {
            return repository.find(id) ?: throw NotFoundException(ANNOUNCEMENT_NOT_FOUND_MESSAGE)
        }
        return repository.update(id, body, active, clock.instant())
            ?: throw NotFoundException(ANNOUNCEMENT_NOT_FOUND_MESSAGE)
    }

    fun activeAnnouncements(): List<Announcement> = repository.listActive(ACTIVE_LIMIT)

    companion object {
        const val ANNOUNCEMENT_NOT_FOUND_MESSAGE = "공지를 찾을 수 없습니다"

        /** 계획 §2 결정 5 — 사용자 화면이 상단 배너로 보여주는 최대 건수. */
        const val ACTIVE_LIMIT = 5
    }
}
