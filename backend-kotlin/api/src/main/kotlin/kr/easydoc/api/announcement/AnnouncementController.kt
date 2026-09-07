package kr.easydoc.api.announcement

import kr.easydoc.api.admin.ActiveAnnouncementListResponse
import kr.easydoc.api.admin.ActiveAnnouncementResponse
import kr.easydoc.application.admin.AnnouncementService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /announcements/active` — 인증 사용자 전용(관리자가 아니어도 된다), 활성 공지
 * 최신순 최대 5건. `AppLayout`이 상단 배너로 보여준다(어드민 최소 계획 §2 결정 5).
 */
@RestController
@RequestMapping("/announcements")
class AnnouncementController(private val service: AnnouncementService) {
    @GetMapping("/active")
    fun active(): ResponseEntity<ActiveAnnouncementListResponse> =
        ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ActiveAnnouncementListResponse(service.activeAnnouncements().map(ActiveAnnouncementResponse::of)))
}
