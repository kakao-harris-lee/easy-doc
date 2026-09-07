package kr.easydoc.api.admin

import kr.easydoc.api.auth.AuthenticatedUser
import kr.easydoc.application.admin.AnnouncementService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `GET`·`POST /admin/announcements`·`PATCH /admin/announcements/{id}` — 어드민 최소
 * 계획 §2 결정 5. 사용자용 `GET /announcements/active`는
 * [kr.easydoc.api.announcement.AnnouncementController]가 맡는다(관리자 전용이 아니다).
 */
@RestController
@RequestMapping("/admin/announcements")
class AdminAnnouncementController(private val service: AnnouncementService) {
    @GetMapping
    fun list(): ResponseEntity<AnnouncementListResponse> =
        adminResponse(HttpStatus.OK).body(AnnouncementListResponse(service.listAll().map(AnnouncementResponse::of)))

    /** **201** — 자원이 실제로 생겼다. */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        user: AuthenticatedUser,
        @RequestBody request: AnnouncementCreateRequest,
    ): ResponseEntity<AnnouncementResponse> {
        val created = service.create(request.body, user.id)
        AdminActionLog.record(ACTION_ANNOUNCEMENT_CREATE, user.id, created.id)
        return adminResponse(HttpStatus.CREATED).body(AnnouncementResponse.of(created))
    }

    /**
     * `body`·`active` 둘 다 없으면(명시적 `null`도 생략과 같다 — 계약 참고) 아무것도 바꾸지
     * 않는다 — `AnnouncementService.update`가 조기 반환하므로 여기서도 감사 로그를
     * 남기지 않는다(독립 리뷰 지적, 실제로 바뀐 변경만 로그에 남긴다).
     */
    @PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        user: AuthenticatedUser,
        @PathVariable("id") id: UUID,
        @RequestBody request: AnnouncementUpdateRequest,
    ): ResponseEntity<AnnouncementResponse> {
        val updated = service.update(id, request.body, request.active)
        if (request.body != null || request.active != null) {
            AdminActionLog.record(ACTION_ANNOUNCEMENT_UPDATE, user.id, id)
        }
        return adminResponse(HttpStatus.OK).body(AnnouncementResponse.of(updated))
    }

    private companion object {
        const val ACTION_ANNOUNCEMENT_CREATE = "announcement_create"
        const val ACTION_ANNOUNCEMENT_UPDATE = "announcement_update"
    }
}
