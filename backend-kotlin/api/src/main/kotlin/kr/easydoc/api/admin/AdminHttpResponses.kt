package kr.easydoc.api.admin

import kr.easydoc.api.config.privateResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * 관리자 컨트롤러 다섯 곳(`AdminWorkspaceController`·`AdminInvoiceRequestController`·
 * `AdminErrorController`·`AdminUsageController`·`AdminAnnouncementController`)이 공유하는
 * 사적 응답 헤더 부착 — `WorkspaceController.private`와 같은 하한선을 공유한다.
 */
internal fun adminResponse(status: HttpStatus): ResponseEntity.BodyBuilder =
    ResponseEntity
        .status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .privateResponse()
